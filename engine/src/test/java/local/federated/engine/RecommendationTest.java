package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationTest {
    @TempDir Path temp;
    private void seed(ItemStore store) throws Exception {
        JsonNode corpus = Contract.JSON.readTree(Files.readAllBytes(Path.of("../fixtures/d3/items.json")));
        for (JsonNode item : corpus.path("items")) assertEquals("applied", store.apply(item).status());
    }
    static ObjectNode request(int size) {
        return Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("kind", "personal").put("pageSize", size);
    }
    private String category(JsonNode response) { return response.path("items").get(0).path("item").path("fields").path("category").asText(); }

    @Test void explicitPreferencesAndSubscriptionsProduceDifferentExplainedRecommendations() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            var alice = UserBehaviorTest.user("alice");
            alice.withArray("preferences").add("离线部署");
            var bob = UserBehaviorTest.user("bob");
            bob.withArray("preferences").add("日志审计");
            users.applyUser("demo", alice);
            users.applyUser("demo", bob);
            var recommendations = new RecommendationService(store, users, null, "demo");
            var aliceResult = recommendations.recommend(request(10), UserBehaviorTest.ALICE);
            var bobIdentity = new LocalIdentities.Identity("demo", new ItemStore.Principal("bob", Set.of("research")));
            assertEquals("offline", category(aliceResult));
            assertEquals("audit", category(recommendations.recommend(request(10), bobIdentity)));
            assertTrue(aliceResult.path("items").get(0).path("reasons").toString().contains("preference:离线部署"));
            alice.put("version", 2);
            alice.putArray("preferences");
            alice.withArray("subscriptions").add("source:business");
            users.applyUser("demo", alice);
            var subscribed = recommendations.recommend(request(10), UserBehaviorTest.ALICE);
            assertEquals("business", subscribed.path("items").get(0).path("item").path("object").path("source").asText());
            assertTrue(subscribed.path("items").get(0).path("reasons").toString().contains("subscription:source:business"));
            var upper = IngestionTest.item("upper", 1);
            upper.withObject("object").put("source", "CRM");
            store.apply(upper);
            alice.put("version", 3);
            alice.putArray("subscriptions").add("source:CRM");
            users.applyUser("demo", alice);
            assertEquals("CRM", recommendations.recommend(request(10), UserBehaviorTest.ALICE).path("items").get(0).path("item").path("object").path("source").asText());
        }
    }

    @Test void saveUndoDuplicatesAndNeutralActionsChangeOnlyEffectiveHistory() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            var recommendations = new RecommendationService(store, users, null, "demo");
            String empty = recommendations.profile(UserBehaviorTest.ALICE).revision();
            for (String action : new String[]{"open", "impression", "download"}) users.applyEvent("demo", UserBehaviorTest.event(action, action, "offline-posts"), UserBehaviorTest.ALICE);
            users.applyEvent("demo", UserBehaviorTest.event("short", "consume", "offline-posts").put("effectiveMs", 1000).put("completion", 0.1), UserBehaviorTest.ALICE);
            assertEquals(empty, recommendations.profile(UserBehaviorTest.ALICE).revision());
            var save = UserBehaviorTest.event("save", "save", "offline-posts");
            users.applyEvent("demo", save, UserBehaviorTest.ALICE);
            assertEquals("duplicate", users.applyEvent("demo", save, UserBehaviorTest.ALICE).status());
            assertEquals(3, recommendations.profile(UserBehaviorTest.ALICE).interests().get(0).weight(), 1e-9);
            assertEquals("offline", category(recommendations.recommend(request(10), UserBehaviorTest.ALICE)));
            users.applyEvent("demo", UserBehaviorTest.event("undo", "undo", "offline-posts").put("undoOf", "save"), UserBehaviorTest.ALICE);
            assertTrue(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
            users.applyEvent("demo", UserBehaviorTest.event("repeat-undo", "undo", "offline-posts").put("undoOf", "save"), UserBehaviorTest.ALICE);
            assertTrue(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
        }
    }

    @Test void consentAtCollectionAndCommunityCommentCapsAreAppliedPerScenario() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            var alice = UserBehaviorTest.user("alice").put("allowBehaviorProfile", false);
            users.applyUser("demo", alice);
            users.applyEvent("demo", UserBehaviorTest.event("opt-out-save", "save", "offline-posts"), UserBehaviorTest.ALICE);
            alice.put("version", 2).put("allowBehaviorProfile", true);
            users.applyUser("demo", alice);
            var recommendations = new RecommendationService(store, users, null, "demo");
            assertTrue(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
            for (int i = 1; i <= 4; i++) users.applyEvent("demo", UserBehaviorTest.event("comment-" + i, "consume", "offline-posts").put("sourceAction", "valid_comment"), UserBehaviorTest.ALICE);
            assertEquals(1.5, recommendations.profile(UserBehaviorTest.ALICE).interests().get(0).weight(), 1e-9);
            for (int i = 1; i <= 4; i++) {
                var consume = UserBehaviorTest.event("file-" + i, "consume", "offline-files").put("source", "files").put("sourceAction", "valid_comment").put("effectiveMs", 30000);
                consume.withObject("object").put("source", "files");
                users.applyEvent("demo", consume, UserBehaviorTest.ALICE);
            }
            double fileWeight = recommendations.profile(UserBehaviorTest.ALICE).interests().stream().filter(interest -> interest.source().equals("files")).findFirst().orElseThrow().weight();
            assertEquals(4, fileWeight, 1e-9);
        }
    }

    @Test void currentTaskExpiresWithoutCreatingLongTermHistory() throws Exception {
        final long[] time = {UserBehaviorTest.CLOCK.millis()};
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(time[0]); }
        };
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), clock);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            var recommendations = new RecommendationService(store, users, null, "demo");
            var task = request(1);
            task.putObject("task").put("taskId", "task-1").put("text", "安全事件应急处置隔离设备").put("expiresAt", Instant.ofEpochMilli(time[0] + 1000).toString());
            var first = recommendations.recommend(task, UserBehaviorTest.ALICE);
            assertEquals("incident", category(first));
            assertTrue(first.path("items").get(0).path("channels").toString().contains("task"));
            assertTrue(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
            time[0] += 1001;
            var page = task.deepCopy().put("cursor", first.path("nextCursor").asText());
            assertEquals("CURSOR_EXPIRED", assertThrows(SearchService.Failure.class, () -> recommendations.recommend(page, UserBehaviorTest.ALICE)).code);
            assertFalse(recommendations.recommend(task, UserBehaviorTest.ALICE).path("items").get(0).path("channels").toString().contains("task"));
            assertTrue(users.recentEvents("demo", "alice").isEmpty());
        }
    }

    @Test void paginationTracksProfileRevisionsAndDoesNotInvalidateOnExposure() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            var recommendations = new RecommendationService(store, users, null, "demo");
            var first = recommendations.recommend(request(1), UserBehaviorTest.ALICE);
            var next = request(1).put("cursor", first.path("nextCursor").asText());
            users.applyEvent("demo", UserBehaviorTest.event("impression", "impression", "offline-posts"), UserBehaviorTest.ALICE);
            assertFalse(recommendations.recommend(next, UserBehaviorTest.ALICE).path("items").isEmpty());
            users.applyEvent("demo", UserBehaviorTest.event("save", "save", "offline-posts"), UserBehaviorTest.ALICE);
            assertEquals("CURSOR_MISMATCH", assertThrows(SearchService.Failure.class, () -> recommendations.recommend(next, UserBehaviorTest.ALICE)).code);
            var changed = UserBehaviorTest.user("alice").put("version", 2);
            changed.putArray("groups");
            users.applyUser("demo", changed);
            assertTrue(recommendations.recommend(request(10), UserBehaviorTest.ALICE).path("items").isEmpty());
            changed.put("version", 3).put("active", false);
            users.applyUser("demo", changed);
            assertEquals(401, assertThrows(SearchService.Failure.class, () -> recommendations.recommend(request(10), UserBehaviorTest.ALICE)).status);
        }
    }

    @Test void relatedRecommendationsAndDismissUndoRespectPermissions() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            seed(store);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            var recommendations = new RecommendationService(store, users, null, "demo");
            var related = request(50).put("kind", "related");
            related.putObject("item").put("domain", "demo").put("source", "posts").put("nativeId", "offline-posts");
            var result = recommendations.recommend(related, UserBehaviorTest.ALICE);
            assertFalse(result.path("items").isEmpty());
            for (JsonNode row : result.path("items")) assertNotEquals("offline-posts", row.path("item").path("object").path("nativeId").asText());
            users.applyEvent("demo", UserBehaviorTest.event("dismiss", "dismiss", "offline-posts"), UserBehaviorTest.ALICE);
            assertFalse(recommendations.recommend(request(50), UserBehaviorTest.ALICE).path("items").toString().contains("\"nativeId\":\"offline-posts\""));
            users.applyEvent("demo", UserBehaviorTest.event("undo-dismiss", "undo", "offline-posts").put("undoOf", "dismiss"), UserBehaviorTest.ALICE);
            assertTrue(recommendations.recommend(request(50), UserBehaviorTest.ALICE).path("items").toString().contains("\"nativeId\":\"offline-posts\""));
            var hidden = UserBehaviorTest.user("alice").put("version", 2);
            hidden.putArray("groups");
            users.applyUser("demo", hidden);
            assertEquals("ITEM_UNAVAILABLE", assertThrows(SearchService.Failure.class, () -> recommendations.recommend(related, UserBehaviorTest.ALICE)).code);
        }
    }

    @Test void invalidWeightsAndClientIdentityFieldsAreRejected() throws Exception {
        var config = Contract.JSON.readTree(Files.readAllBytes(Path.of("../config/behavior-policy.json")));
        ((ObjectNode) config.path("weights")).put("download", 1);
        Path policy = temp.resolve("policy.json");
        Files.writeString(policy, config.toString());
        assertThrows(IllegalArgumentException.class, () -> BehaviorPolicy.load(policy));
        try (var store = new ItemStore(temp.resolve("db"))) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            var recommendations = new RecommendationService(store, users, null, "demo");
            assertThrows(IllegalArgumentException.class, () -> recommendations.recommend(request(10).put("userId", "other"), UserBehaviorTest.ALICE));
        }
    }

    @Test void historicalInteractionDoesNotLearnAnUnseenNewObjectVersion() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var original = IngestionTest.item("versioned", 1);
            store.apply(original);
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            users.applyEvent("demo", UserBehaviorTest.event("save-old", "save", "versioned"), UserBehaviorTest.ALICE);
            var recommendations = new RecommendationService(store, users, null, "demo");
            assertFalse(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
            var rewritten = original.deepCopy().put("version", 2).put("text", "A different document topic");
            store.apply(rewritten);
            assertTrue(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
            assertEquals(1, users.recentEvents("demo", "alice").get(0).objectVersion());
            users.applyEvent("demo", UserBehaviorTest.event("save-new", "save", "versioned"), UserBehaviorTest.ALICE);
            assertFalse(recommendations.profile(UserBehaviorTest.ALICE).interests().isEmpty());
        }
    }

    @Test void recentHistoryHasAWindowAndTimeDecay() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            store.apply(IngestionTest.item("aged", 1));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            users.applyUser("demo", UserBehaviorTest.user("alice"));
            users.applyEvent("demo", UserBehaviorTest.event("old", "save", "aged").put("occurredAt", UserBehaviorTest.CLOCK.instant().minusSeconds(31 * 86400L).toString()), UserBehaviorTest.ALICE);
            users.applyEvent("demo", UserBehaviorTest.event("week", "save", "aged").put("occurredAt", UserBehaviorTest.CLOCK.instant().minusSeconds(7 * 86400L).toString()), UserBehaviorTest.ALICE);
            var recommendations = new RecommendationService(store, users, null, "demo");
            assertEquals(1, users.recentEvents("demo", "alice").size());
            assertEquals(1.5, recommendations.profile(UserBehaviorTest.ALICE).interests().get(0).weight(), 1e-9);
        }
    }
}
