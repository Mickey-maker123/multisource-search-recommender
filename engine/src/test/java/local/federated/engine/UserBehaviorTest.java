package local.federated.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class UserBehaviorTest {
    @TempDir Path temp;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T08:00:00Z"), ZoneOffset.UTC);
    static final LocalIdentities.Identity ALICE = new LocalIdentities.Identity("demo", new ItemStore.Principal("alice", Set.of("research")));

    static ObjectNode user(String id) {
        ObjectNode user = Contract.JSON.createObjectNode().put("userId", id).put("version", 1).put("active", true).put("allowBehaviorProfile", true);
        var identities = user.putArray("identities");
        for (String source : List.of("posts", "files", "business")) identities.addObject().put("domain", "demo").put("source", source).put("localUserId", id + "-local");
        user.putArray("groups").add("research");
        user.putArray("preferences");
        user.putArray("subscriptions");
        return user;
    }

    static ObjectNode event(String id, String action, String nativeId) {
        ObjectNode event = Contract.JSON.createObjectNode().put("eventId", id).put("schemaVersion", "1.0").put("domain", "demo").put("source", "posts")
                .put("userId", "alice").put("action", action).put("sourceAction", action).put("occurredAt", CLOCK.instant().toString());
        event.putObject("object").put("domain", "demo").put("source", "posts").put("nativeId", nativeId);
        return event;
    }

    @Test void identityNamespacesVersioningAndConflictsAreAtomic() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            ObjectNode alice = user("alice"), bob = user("bob");
            alice.putArray("identities").addObject().put("domain", "demo").put("source", "posts").put("localUserId", "100");
            bob.putArray("identities").addObject().put("domain", "demo").put("source", "files").put("localUserId", "100");
            assertEquals("applied", users.applyUser("demo", alice).status());
            assertEquals("applied", users.applyUser("demo", bob).status());
            assertEquals("alice", users.resolveLocalUser("demo", "posts", "100"));
            assertEquals("bob", users.resolveLocalUser("demo", "files", "100"));
            assertEquals("duplicate", users.applyUser("demo", alice).status());
            var conflict = bob.deepCopy().put("version", 2);
            conflict.withArray("identities").addObject().put("domain", "demo").put("source", "posts").put("localUserId", "100");
            assertEquals("IDENTITY_CONFLICT", users.applyUser("demo", conflict).code());
            assertEquals(1, users.user("demo", "bob").path("version").asLong());
            assertEquals("bob", users.resolveLocalUser("demo", "files", "100"));
            var changed = alice.deepCopy().put("version", 2);
            changed.putArray("groups");
            assertEquals("applied", users.applyUser("demo", changed).status());
            assertTrue(users.resolve(ALICE).principal().groups().isEmpty());
            assertEquals("stale", users.applyUser("demo", alice).status());
            changed.put("version", 3).put("active", false);
            users.applyUser("demo", changed);
            assertNull(users.resolve(ALICE));
            assertNull(users.resolveLocalUser("demo", "posts", "100"));
        }
    }

    @Test void eventIdsUndoAndOwnerChecksPersistAcrossRestart() throws Exception {
        Path directory = temp.resolve("db");
        try (var store = new ItemStore(directory)) {
            store.apply(IngestionTest.item("post", 1));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            users.applyUser("demo", user("alice"));
            users.applyUser("demo", user("bob"));
            var save = event("save-1", "save", "post");
            assertEquals("applied", users.applyEvent("demo", save, ALICE).status());
            assertEquals("duplicate", users.applyEvent("demo", save, ALICE).status());
            assertEquals("EVENT_ID_CONFLICT", users.applyEvent("demo", save.deepCopy().put("action", "use"), ALICE).code());
            assertEquals("ACTOR_MISMATCH", users.applyEvent("demo", event("wrong", "save", "post").put("userId", "bob"), ALICE).code());
            var wrongUndo = event("wrong-undo", "undo", "post").put("undoOf", "save-1").put("userId", "bob");
            assertEquals("INVALID_UNDO", users.applyEvent("demo", wrongUndo, null).code());
            var undo = event("undo-1", "undo", "post").put("undoOf", "save-1");
            assertEquals("applied", users.applyEvent("demo", undo, ALICE).status());
            assertEquals("applied", users.applyEvent("demo", event("undo-2", "undo", "post").put("undoOf", "save-1"), ALICE).status());
            assertEquals("INVALID_UNDO", users.applyEvent("demo", event("undo-undo", "undo", "post").put("undoOf", "undo-1"), ALICE).code());
            assertEquals("INVALID_UNDO", users.applyEvent("demo", event("unknown", "undo", "post").put("undoOf", "missing"), ALICE).code());
            assertEquals(3, users.recentEvents("demo", "alice").size());
        }
        try (var store = new ItemStore(directory)) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            assertEquals(3, users.recentEvents("demo", "alice").size());
            var recommendations = new RecommendationService(store, users, null, "demo");
            assertTrue(recommendations.profile(ALICE).interests().isEmpty());
        }
    }

    @Test void anonymousFactsStayPublicAndDoNotBecomeUserHistory() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var publicItem = IngestionTest.item("public", 1);
            publicItem.withObject("acl").put("public", true);
            store.apply(publicItem);
            store.apply(IngestionTest.item("private", 1));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            users.applyUser("demo", user("alice"));
            var anonymous = event("anonymous", "open", "public");
            anonymous.remove("userId");
            anonymous.put("sessionId", "session-1");
            assertEquals("applied", users.applyEvent("demo", anonymous, null).status());
            assertEquals("ACTOR_MISMATCH", users.applyEvent("demo", anonymous.deepCopy().put("eventId", "impersonation"), ALICE).code());
            anonymous.withObject("object").put("nativeId", "private");
            anonymous.put("eventId", "private-anon");
            assertEquals("OBJECT_UNAVAILABLE", users.applyEvent("demo", anonymous, null).code());
            assertTrue(users.recentEvents("demo", "alice").isEmpty());
        }
    }

    @Test void malformedFutureAndUnmappedFactsAreRejected() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            store.apply(IngestionTest.item("post", 1));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            users.applyUser("demo", user("alice"));
            assertEquals("INVALID_EVENT", users.applyEvent("demo", event("comment", "consume", "post").put("commentText", "private comment"), ALICE).code());
            assertEquals("FUTURE_EVENT", users.applyEvent("demo", event("future", "save", "post").put("occurredAt", CLOCK.instant().plusSeconds(301).toString()), ALICE).code());
            assertEquals("SOURCE_IDENTITY_UNMAPPED", users.applyEvent("demo", event("unmapped", "save", "post").put("source", "other"), ALICE).code());
            assertEquals("INVALID_TIME", users.applyEvent("demo", event("bad-time", "save", "post").put("occurredAt", "yesterday"), ALICE).code());
            assertEquals("DOMAIN_FORBIDDEN", users.applyEvent("demo", event("cross", "save", "post").put("domain", "elsewhere"), ALICE).code());
        }
    }

    @Test void concurrentDuplicateDeliveryHasOneFact() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            store.apply(IngestionTest.item("post", 1));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), CLOCK);
            users.applyUser("demo", user("alice"));
            var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<ItemStore.Outcome>>();
                for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> users.applyEvent("demo", event("same", "save", "post"), ALICE)));
                int applied = 0;
                for (var task : tasks) if (task.get().status().equals("applied")) applied++;
                assertEquals(1, applied);
                assertEquals(1, users.recentEvents("demo", "alice").size());
            } finally { executor.shutdownNow(); }
        }
    }
}
