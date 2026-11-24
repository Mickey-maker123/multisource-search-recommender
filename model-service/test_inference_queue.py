import asyncio
import threading
import unittest

from inference_queue import InferenceQueue, QueueExpired, QueueFull, ClientGone, connected_call


class QueueTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.queue = InferenceQueue(capacity=8, wait_seconds=1, query_burst=2)

    async def asyncTearDown(self):
        await self.queue.close()

    async def test_priority_and_index_fairness(self):
        entered, release = asyncio.Event(), asyncio.Event()
        order = []
        async def blocker():
            entered.set()
            await release.wait()
        active = asyncio.create_task(self.queue.run(blocker, 'index'))
        await entered.wait()
        async def record(name):
            order.append(name)
            return name
        tasks = [asyncio.create_task(self.queue.run(lambda: record('index'), 'index'))]
        tasks += [asyncio.create_task(self.queue.run(lambda i=i: record(str(i)))) for i in range(4)]
        await asyncio.sleep(0)
        release.set()
        await asyncio.gather(active, *tasks)
        self.assertEqual(order, ['0', '1', 'index', '2', '3'])

    async def test_cancelled_native_inference_retains_slot(self):
        native_started, native_release = threading.Event(), threading.Event()
        second_entered = asyncio.Event()
        def native():
            native_started.set()
            native_release.wait(2)
        async def second():
            second_entered.set()
        first = asyncio.create_task(self.queue.run(lambda: asyncio.to_thread(native)))
        await asyncio.to_thread(native_started.wait, 1)
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        following = asyncio.create_task(self.queue.run(second))
        await asyncio.sleep(.02)
        self.assertFalse(second_entered.is_set())
        self.assertTrue(self.queue.status()['active'])
        native_release.set()
        await following

    async def test_full_timeout_and_recovery(self):
        self.queue.capacity = 1
        self.queue.wait_seconds = .03
        entered, release = asyncio.Event(), asyncio.Event()
        async def blocked():
            entered.set()
            await release.wait()
        active = asyncio.create_task(self.queue.run(blocked))
        await entered.wait()
        called = []
        async def waiting():
            called.append(True)
        pending = asyncio.create_task(self.queue.run(waiting))
        await asyncio.sleep(0)
        with self.assertRaises(QueueFull):
            await self.queue.run(waiting)
        with self.assertRaises(QueueExpired):
            await pending
        release.set()
        await active
        await self.queue.run(waiting)
        self.assertEqual(called, [True])

    async def test_failure_does_not_stop_queue(self):
        async def broken():
            raise RuntimeError('controlled')
        with self.assertRaises(RuntimeError):
            await self.queue.run(broken)
        self.assertEqual(await self.queue.run(lambda: asyncio.sleep(0, result=42)), 42)
        self.assertEqual(self.queue.status()['failed'], 1)

    async def test_execution_timeout_is_not_queue_timeout(self):
        async def broken():
            raise TimeoutError('inference failed')
        with self.assertRaises(TimeoutError):
            await self.queue.run(broken)
        self.assertEqual(self.queue.status()['expired'], 0)

    async def test_cancelled_waiter_never_executes(self):
        entered, release = asyncio.Event(), asyncio.Event()
        async def blocked():
            entered.set()
            await release.wait()
        active = asyncio.create_task(self.queue.run(blocked))
        await entered.wait()
        called = []
        async def waiting():
            called.append(True)
        pending = asyncio.create_task(self.queue.run(waiting))
        await asyncio.sleep(0)
        pending.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await pending
        release.set()
        await active
        self.assertEqual(called, [])

    async def test_http_disconnect_stops_future_quanta_but_keeps_active_slot(self):
        entered, release = asyncio.Event(), asyncio.Event()
        messages = asyncio.Queue()
        called = []
        async def quantum(number):
            called.append(number)
            entered.set()
            await release.wait()
        async def batch():
            for number in range(4):
                await self.queue.run(lambda: quantum(number), 'index')
        caller = asyncio.create_task(connected_call(batch, messages.get))
        await entered.wait()
        await messages.put({'type': 'http.disconnect'})
        with self.assertRaises(ClientGone):
            await caller
        self.assertTrue(self.queue.status()['active'])
        release.set()
        await self.queue.run(lambda: asyncio.sleep(0))
        self.assertEqual(called, [0])
        self.assertEqual(self.queue.status()['cancelled'], 1)

    async def test_connected_completion_and_errors_clean_up_receiver(self):
        closed = asyncio.Event()
        async def receive():
            try:
                await asyncio.Event().wait()
            finally:
                closed.set()
        self.assertEqual(await connected_call(lambda: asyncio.sleep(.001, result=42), receive), 42)
        self.assertTrue(closed.is_set())
        async def error():
            raise ValueError('controlled')
        with self.assertRaises(ValueError):
            await connected_call(error, receive)

    async def test_shutdown_waits_for_active_and_drains_accepted_work(self):
        entered, release = asyncio.Event(), asyncio.Event()
        async def active():
            entered.set()
            await release.wait()
        first = asyncio.create_task(self.queue.run(active))
        await entered.wait()
        waiting = asyncio.create_task(self.queue.run(lambda: asyncio.sleep(0, result=7)))
        await asyncio.sleep(0)
        closing = asyncio.create_task(self.queue.close())
        await asyncio.sleep(0)
        self.assertFalse(closing.done())
        with self.assertRaises(QueueFull):
            await self.queue.run(lambda: asyncio.sleep(0))
        release.set()
        await first
        self.assertEqual(await waiting, 7)
        await closing
        self.assertFalse(self.queue.status()['active'])


if __name__ == '__main__':
    unittest.main()
