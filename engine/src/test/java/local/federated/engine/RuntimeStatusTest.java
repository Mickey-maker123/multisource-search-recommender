package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;

class RuntimeStatusTest {
    @TempDir Path temp;

    @Test void administratorOnlyAndAggregateStates() throws Exception {
        try (var store = new ItemStore(temp.resolve("db"));
             var server = new EngineServer("127.0.0.1", 0, EngineTest.KEY, store, "demo")) {
            store.apply(IngestionTest.item("private-title", 1));
            store.apply(IngestionTest.item("deleted", 1).put("deleted", true));
            server.start();
            var uri = URI.create("http://127.0.0.1:" + server.port() + "/api/engine/admin/v1/status");
            var http = HttpClient.newHttpClient();
            for (String token : new String[]{"", "Bearer query-only"}) {
                var request = HttpRequest.newBuilder(uri).header("Authorization", token).GET().build();
                assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            var response = http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + EngineTest.KEY).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            var status = Contract.JSON.readTree(response.body());
            assertEquals(1, status.path("store").path("items").path("ready").asInt());
            assertEquals(1, status.path("store").path("items").path("deleted").asInt());
            assertEquals("not_configured", status.path("search").path("state").asText());
            assertFalse(response.body().contains("private-title"));
            assertFalse(response.body().contains(EngineTest.KEY));
            assertEquals(405, http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + EngineTest.KEY)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void coverageChecksCurrentVersionAndDeletion() throws Exception {
        try (var store = new ItemStore(temp.resolve("coverage")); var backend = new SearchTest.IndexFixture();
             var search = new SearchService(store, backend.client(), null, "demo", 0.4)) {
            store.apply(IngestionTest.item("a", 1));search.sync();
            assertEquals(1, search.runtimeStatus().path("keywordObjects").asInt());
            store.apply(IngestionTest.item("a", 2).put("text", "changed"));
            assertEquals(0, search.runtimeStatus().path("keywordObjects").asInt());
            search.sync();
            assertEquals(1, search.runtimeStatus().path("keywordObjects").asInt());
            store.apply(IngestionTest.item("a", 3).put("deleted", true));
            assertEquals(0, search.runtimeStatus().path("readyObjects").asInt());
            assertEquals(0, search.runtimeStatus().path("keywordObjects").asInt());
        }
    }

    @Test void modelStatusFiltersUnknownFieldsAndReportsFailure() throws Exception {
        var model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/status", exchange -> {
            assertEquals("Bearer " + EngineTest.KEY, exchange.getRequestHeaders().getFirst("Authorization"));
            var status = Contract.JSON.createObjectNode().put("modelVersion", EmbeddingClient.MODEL).put("secret", "private");
            var queue = status.putObject("queue").put("active", true).put("capacity", 8).put("completed", 2).put("secret", "private");
            queue.putObject("waiting").put("query", 3).put("index", 1).put("secret", "private");
            byte[] bytes = Contract.JSON.writeValueAsBytes(status);
            exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        model.start();
        var client = new EmbeddingClient(URI.create("http://127.0.0.1:" + model.getAddress().getPort()), EngineTest.KEY, Duration.ofSeconds(1));
        try {
            var status = client.runtimeStatus();
            assertEquals(3, status.path("queue").path("waiting").path("query").asInt());
            assertFalse(status.toString().contains("private"));
            assertFalse(status.toString().contains("secret"));
        } finally { model.stop(0); }
        try (var store = new ItemStore(temp.resolve("failure")); var backend = new SearchTest.IndexFixture();
             var search = new SearchService(store, backend.client(), client, "demo", 0.4)) {
            assertEquals("unavailable", search.runtimeStatus().path("model").path("state").asText());
        }
    }
}
