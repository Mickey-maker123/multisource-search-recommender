package local.federated.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;

class SourceContentTest {
    @TempDir Path temp;
    private ObjectNode request(com.fasterxml.jackson.databind.JsonNode object, long version) {
        var request = Contract.JSON.createObjectNode().put("schemaVersion", "1.1")
            .put("version", version).put("offset", 0).put("limit", 1);
        request.set("object", object);
        return request;
    }

    @Test void realParserContentAndCodepointAnchorRoundTrip() throws Exception {
        var file = (ObjectNode)Contract.JSON.readTree(Files.readAllBytes(Path.of("../fixtures/d2/files.json")))
            .path("files").get(0).path("item");
        String text = "资料😀".repeat(8000) + "最后一段";
        Path root = Files.createDirectory(temp.resolve("input"));
        Files.writeString(root.resolve("content.txt"), text);
        try (var store = new ItemStore(temp.resolve("db"))) {
            store.apply(file);
            var importer = new FileImporter(store, root, temp.resolve("work"), IngestionTest.ALICE);
            String key = Contract.key(file.path("object"));
            assertEquals("ready", importer.parse(key, "content.txt"));
            var service = new AnalysisService(store, "demo", new UserBehaviorStore(store, BehaviorPolicy.load(null)));
            // The old v1 source remains metadata; new content is an explicit paginated endpoint.
            assertTrue(service.source(file.path("object"), UserBehaviorTest.ALICE).path("item").path("text").asText().isEmpty());
            var query = request(file.path("object"), 1);
            var first = service.sourceContent(query, UserBehaviorTest.ALICE);
            assertTrue(first.path("hasMore").asBoolean());
            assertEquals(20000, first.path("parts").get(0).path("location").path("charEnd").asInt());
            query.put("offset", first.path("nextOffset").asInt()).put("contentGuard", first.path("contentGuard").asText());
            var second = service.sourceContent(query, UserBehaviorTest.ALICE);
            assertEquals(text, first.path("parts").get(0).path("text").asText() + second.path("parts").get(0).path("text").asText());
            assertFalse(second.path("hasMore").asBoolean());
            query = request(file.path("object"), 1);
            query.putObject("anchor").put("uri", file.path("location").path("uri").asText()).put("charStart", 21000).put("charEnd", 21004);
            var located = service.sourceContent(query, UserBehaviorTest.ALICE);
            assertTrue(located.path("anchorMatched").asBoolean());
            assertEquals(1, located.path("offset").asInt());
            query.withObject("anchor").put("uri", "engine-source://demo/files/other");
            assertFalse(service.sourceContent(query, UserBehaviorTest.ALICE).path("anchorMatched").asBoolean());
        }
    }

    @Test void currentVersionGuardAncestorAndIdentityAreRechecked() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null));
            var service = new AnalysisService(store, "demo", users);
            var parent = IngestionTest.item("parent", 1);
            var child = IngestionTest.item("child", 1);child.set("parent", parent.get("object"));
            store.apply(parent);store.apply(child);
            var query = request(child.path("object"), 1);
            var first = service.sourceContent(query, UserBehaviorTest.ALICE);
            query.put("contentGuard", first.path("contentGuard").asText());
            var ticket = store.begin("demo:posts:child", IngestionTest.ALICE);
            var parts = Contract.JSON.createArrayNode();parts.addObject().put("text", "new extraction").set("location", child.get("location"));
            store.finish(ticket, IngestionTest.ALICE, "ready", "OK", parts);
            assertEquals(409, assertThrows(SearchService.Failure.class, () -> service.sourceContent(query, UserBehaviorTest.ALICE)).status);
            query.remove("contentGuard");
            assertEquals("new extraction", service.sourceContent(query, UserBehaviorTest.ALICE).path("parts").get(0).path("text").asText());
            store.apply(child.put("version", 2));
            assertEquals(409, assertThrows(SearchService.Failure.class, () -> service.sourceContent(query, UserBehaviorTest.ALICE)).status);
            query.put("version", 2);
            parent.put("version", 2).withObject("acl").putArray("groups");store.apply(parent);
            assertEquals(404, assertThrows(SearchService.Failure.class, () -> service.sourceContent(query, UserBehaviorTest.ALICE)).status);
            var user = UserBehaviorTest.user("alice");users.applyUser("demo", user);
            user.put("version", 2).put("active", false);users.applyUser("demo", user);
            assertEquals(401, assertThrows(SearchService.Failure.class, () -> service.sourceContent(query, UserBehaviorTest.ALICE)).status);
        }
    }

    @Test void invalidPagingAndPendingStatesDoNotLeakDerivedText() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"))) {
            var item = IngestionTest.item("pending", 1).put("text", "");store.apply(item);
            var service = new AnalysisService(store, "demo", new UserBehaviorStore(store, BehaviorPolicy.load(null)));
            var query = request(item.path("object"), 1);
            var pending = service.sourceContent(query, UserBehaviorTest.ALICE);
            assertEquals("pending", pending.path("state").asText());assertTrue(pending.path("parts").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.sourceContent(query.deepCopy().put("limit", 21), UserBehaviorTest.ALICE));
            assertThrows(IllegalArgumentException.class, () -> service.sourceContent(query.deepCopy().put("offset", 1), UserBehaviorTest.ALICE));
            assertThrows(IllegalArgumentException.class, () -> service.sourceContent(query.deepCopy().put("schemaVersion", "1.0"), UserBehaviorTest.ALICE));
            assertThrows(IllegalArgumentException.class, () -> service.sourceContent(query.deepCopy().put("path", "private"), UserBehaviorTest.ALICE));
            store.apply(item.put("version", 2).put("deleted", true));
            assertEquals(404, assertThrows(SearchService.Failure.class, () -> service.sourceContent(query, UserBehaviorTest.ALICE)).status);
        }
    }

    @Test void authenticatedHttpContentRouteAndFrozenV1Source() throws Exception {
        String token = "query-content-test-only-00000000000000";
        var auth = Contract.JSON.createObjectNode();
        auth.putArray("principals").addObject().put("tokenSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
            .put("domain", "demo").put("userId", "alice").put("active", true).putArray("groups").add("research");
        Path file = temp.resolve("identities.json");Files.writeString(file, auth.toString());
        try (var store = new ItemStore(temp.resolve("db"));
             var server = new EngineServer("127.0.0.1", 0, EngineTest.KEY, store, "demo", null, new LocalIdentities(file))) {
            var item = IngestionTest.item("source", 1);store.apply(item);server.start();
            var http = HttpClient.newHttpClient();
            var uri = URI.create("http://127.0.0.1:" + server.port() + "/api/engine/v1/source-content");
            var body = request(item.path("object"), 1).toString();
            var denied = http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + EngineTest.KEY).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
            var response = http.send(HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals(item.path("text").asText(), Contract.JSON.readTree(response.body()).path("parts").get(0).path("text").asText());
        }
    }
}
