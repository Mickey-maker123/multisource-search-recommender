"""Single inference owner; bounded waiting, query priority, and fair indexing."""
import asyncio
from collections import deque
from dataclasses import dataclass
import time


class QueueFull(Exception):
    pass


class ClientGone(Exception):
    pass


async def connected_call(operation, receive):
    """After the body is consumed, disconnect cancels the caller, not native work."""
    async def disconnected():
        while (await receive())['type'] != 'http.disconnect':
            pass
    work = asyncio.create_task(operation())
    watcher = asyncio.create_task(disconnected())
    try:
        done, _ = await asyncio.wait((work, watcher), return_when=asyncio.FIRST_COMPLETED)
        if watcher in done:
            watcher.result()
            raise ClientGone()
        return await work
    finally:
        for task in (work, watcher):
            if not task.done():
                task.cancel()
        await asyncio.gather(work, watcher, return_exceptions=True)


class QueueExpired(Exception):
    pass


@dataclass
class Work:
    operation: object
    result: asyncio.Future
    started: asyncio.Future
    enqueued: float


class InferenceQueue:
    def __init__(self, capacity=8, wait_seconds=5.0, query_burst=4):
        if capacity < 1 or wait_seconds <= 0 or query_burst < 1:
            raise ValueError('Invalid queue limits')
        self.capacity, self.wait_seconds, self.query_burst = capacity, wait_seconds, query_burst
        self.pending = {'query': deque(), 'index': deque()}
        self.wakeup = asyncio.Event()
        self.active = False
        self.closed = False
        self.streak = 0
        self.completed = self.failed = self.rejected = self.expired = self.cancelled = 0
        self.worker = asyncio.create_task(self._serve())

    def _prune(self):
        for kind, queue in self.pending.items():
            self.pending[kind] = deque(work for work in queue if not work.result.cancelled())

    def status(self):
        return {'active': self.active, 'capacity': self.capacity,
                'waiting': {kind: sum(not w.result.cancelled() for w in queue)
                            for kind, queue in self.pending.items()},
                'completed': self.completed, 'failed': self.failed,
                'rejected': self.rejected, 'expired': self.expired, 'cancelled': self.cancelled}

    async def run(self, operation, purpose='query'):
        if purpose not in self.pending:
            raise ValueError('Unknown request purpose')
        self._prune()
        if self.closed or sum(map(len, self.pending.values())) >= self.capacity:
            self.rejected += 1
            raise QueueFull()
        loop = asyncio.get_running_loop()
        work = Work(operation, loop.create_future(), loop.create_future(), time.perf_counter())
        self.pending[purpose].append(work)
        self.wakeup.set()
        try:
            try:
                async with asyncio.timeout(self.wait_seconds):
                    await asyncio.shield(work.started)
            except TimeoutError:
                self.expired += 1
                work.result.cancel()
                raise QueueExpired() from None
            return await asyncio.shield(work.result)
        except asyncio.CancelledError:
            self.cancelled += 1
            if work.result.done() and not work.result.cancelled():
                work.result.exception()
            work.result.cancel()
            raise

    async def _serve(self):
        while True:
            self._prune()
            if not any(self.pending.values()):
                if self.closed:
                    return
                self.wakeup.clear()
                await self.wakeup.wait()
                continue
            kind = 'query' if self.pending['query'] and (not self.pending['index'] or self.streak < self.query_burst) else 'index'
            work = self.pending[kind].popleft()
            self.streak = self.streak + 1 if kind == 'query' else 0
            self.active = True
            work.started.set_result(None)
            try:
                result = await work.operation()
                self.completed += 1
                if not work.result.done():
                    work.result.set_result(result)
            except Exception as error:
                self.failed += 1
                if not work.result.done():
                    # A consumer may clear the traceback on a handled exception.
                    # Exclude this live worker frame while retaining the failing operation.
                    work.result.set_exception(error.with_traceback(error.__traceback__.tb_next))
            finally:
                self.active = False

    async def close(self):
        self.closed = True
        self.wakeup.set()
        # The worker owns native inference, even when the HTTP caller cancels.
        await self.worker
