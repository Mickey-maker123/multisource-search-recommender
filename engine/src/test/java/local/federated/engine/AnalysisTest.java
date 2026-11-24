package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisTest {
    @TempDir Path temp;
    private com.fasterxml.jackson.databind.node.ObjectNode request(String template) {
        return Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("template", template);
    }
    @Test void exactRootsGroupsPermissionsAndCurrentVersions() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null));
            var service = new AnalysisService(store, "demo", users);
            var parent = IngestionTest.item("parent", 1);
            assertEquals("applied", store.apply(parent).status());
            var child = IngestionTest.item("child", 1);
            child.set("parent", parent.get("object"));
            assertEquals("applied", store.apply(child).status());
            assertEquals(1, service.analyze(request("count"), UserBehaviorTest.ALICE).path("count").asInt());
            var groups = service.analyze(request("group_by").put("groupBy", "source"), UserBehaviorTest.ALICE).path("groups");
            assertEquals("value:posts", groups.get(0).path("key").asText());
            assertEquals(1, groups.get(0).path("count").asInt());
            var query = request("count"); query.withObject("filters").put("category", "absent");
            assertEquals(0, service.analyze(query, UserBehaviorTest.ALICE).path("count").asInt());
            parent.put("version", 2); parent.withObject("acl").putArray("groups");
            assertEquals("applied", store.apply(parent).status());
            assertEquals(0, service.analyze(request("count"), UserBehaviorTest.ALICE).path("count").asInt());
            parent.put("version", 3).put("deleted", true); store.apply(parent);
            assertEquals(0, service.analyze(request("count"), UserBehaviorTest.ALICE).path("count").asInt());
        }
    }
    @Test void relationsRequireBothEndpointsAndOnlyExplicitEdges() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var service = new AnalysisService(store, "demo", new UserBehaviorStore(store, BehaviorPolicy.load(null)));
            var source = IngestionTest.item("source", 1);
            var target = IngestionTest.item("target", 1);
            store.apply(target);
            source.putArray("relations").addObject().put("kind", "explicit").set("target", target.get("object"));
            store.apply(source);
            var query = request("relations"); query.set("item", source.get("object"));
            assertEquals(1, service.analyze(query, UserBehaviorTest.ALICE).path("relations").size());
            target.put("version", 2); target.withObject("acl").putArray("groups"); store.apply(target);
            assertTrue(service.analyze(query, UserBehaviorTest.ALICE).path("relations").isEmpty());
            assertTrue(service.source(source.get("object"), UserBehaviorTest.ALICE).path("item").path("relations").isEmpty());
            assertTrue(store.visibleItem("demo:posts:source", UserBehaviorTest.ALICE.principal()).path("relations").isEmpty());
            source.put("version", 2); source.withObject("acl").putArray("groups"); store.apply(source);
            assertEquals(404, assertThrows(SearchService.Failure.class, () -> service.analyze(query, UserBehaviorTest.ALICE)).status);
        }
    }
    @Test void missingBucketsAndManagedRevocationStayDistinct() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null));
            var service = new AnalysisService(store, "demo", users);
            var absent = IngestionTest.item("absent", 1);
            absent.putObject("fields");
            var literal = IngestionTest.item("literal", 1);
            literal.withObject("fields").put("category", "missing");
            store.apply(absent); store.apply(literal);
            var result = service.analyze(request("group_by").put("groupBy", "category"), UserBehaviorTest.ALICE);
            assertEquals("missing", result.path("groups").get(0).path("key").asText());
            assertEquals("value:missing", result.path("groups").get(1).path("key").asText());
            var user = UserBehaviorTest.user("alice");
            users.applyUser("demo", user);
            user.put("version", 2).put("active", false);
            users.applyUser("demo", user);
            assertEquals(401, assertThrows(SearchService.Failure.class, () -> service.analyze(request("count"), UserBehaviorTest.ALICE)).status);
            assertEquals(401, assertThrows(SearchService.Failure.class, () -> service.source(absent.get("object"), UserBehaviorTest.ALICE)).status);
        }
    }

    @Test void whitelistTimesMissingBucketsAndLimit() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var service = new AnalysisService(store, "demo", new UserBehaviorStore(store, BehaviorPolicy.load(null)));
            assertThrows(IllegalArgumentException.class, () -> service.analyze(request("sql"), UserBehaviorTest.ALICE));
            assertThrows(IllegalArgumentException.class, () -> service.analyze(request("group_by").put("groupBy", "text"), UserBehaviorTest.ALICE));
            var invalid = request("count"); invalid.withObject("filters").put("updatedFrom", "bad");
            assertThrows(IllegalArgumentException.class, () -> service.analyze(invalid, UserBehaviorTest.ALICE));
            for (int i = 0; i < 101; i++) {
                var item = IngestionTest.item("row-" + i, 1);
                item.withObject("fields").put("category", "group-" + i);
                assertEquals("applied", store.apply(item).status());
            }
            assertEquals(101, service.analyze(request("count"), UserBehaviorTest.ALICE).path("count").asInt());
            assertEquals("ANALYSIS_LIMIT_EXCEEDED", assertThrows(SearchService.Failure.class, () -> service.analyze(request("group_by").put("groupBy", "category"), UserBehaviorTest.ALICE)).code);
        }
    }
}
