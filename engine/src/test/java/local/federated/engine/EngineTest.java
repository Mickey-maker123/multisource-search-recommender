package local.federated.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;

class EngineTest {
    static final String KEY = "test-only-key-00000000000000000000";
    @Test void configurationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> new EngineServer("0.0.0.0", 0, KEY));
        assertThrows(IllegalArgumentException.class, () -> new EngineServer("127.0.0.1", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> new EngineServer("127.0.0.1", -1, KEY));
    }
    @Test void httpBoundariesAndReadiness() throws Exception {
        try (var server = new EngineServer("127.0.0.1", 0, KEY)) {
            server.start(); var http = HttpClient.newHttpClient();
            String root = "http://127.0.0.1:" + server.port() + "/api/engine/v1/";
            var health = http.send(HttpRequest.newBuilder(URI.create(root + "health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode()); assertTrue(health.body().contains("\"ready\":false"));
            for (var endpoint : List.of("items:batch", "users:batch", "events:batch", "search", "recommendations", "analyze")) {
                var url = URI.create(root + endpoint);
                assertEquals(401, http.send(HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(401, http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer wrong").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                var response = http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + KEY).POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"spoof\"}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(503, response.statusCode()); assertFalse(response.body().contains("spoof"));
                assertEquals(405, http.send(HttpRequest.newBuilder(url).header("Authorization", "Bearer " + KEY).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(root + "health/extra")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(root + "health")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void fusionSupportsOpaqueIdsAndDeduplicatesEachRoute() {
        var hits = Rrf.fuse(List.of(List.of("demo:files:b", "demo:files:b", "demo:posts:a"), List.of("demo:posts:a")), Map.of());
        assertEquals("demo:posts:a", hits.get(0).itemId());
        assertEquals(1.0 / 61, hits.get(1).score(), 1e-12);
        assertTrue(hits.get(0).normalized() <= 1);
        assertEquals(List.of(), Rrf.fuse(List.of(), Map.of()));
        assertEquals("a", Rrf.fuse(List.of(List.of("b"), List.of("a")), Map.of()).get(0).itemId());
        assertEquals("b", Rrf.fuse(List.of(List.of("a"), List.of("b")), Map.of("a", "2026-11-05T10:00:00+08:00", "b", "2026-11-05T03:00:00Z")).get(0).itemId());
    }
}
