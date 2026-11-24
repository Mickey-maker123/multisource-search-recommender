package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;

class EmbeddingCacheTest {
    @TempDir Path temp;
    private List<Double> vector(){var values=new ArrayList<Double>(Collections.nCopies(1024,0.0));values.set(0,1.0);return values;}
    @Test void survivesReopenButBindsActualInputObjectGuardAndModel()throws Exception {
        ItemStore.Projection saved;
        try(var store=new ItemStore(temp.resolve("db"))){
            store.apply(IngestionTest.item("a",1));store.apply(IngestionTest.item("b",1));
            saved=store.projection("demo:posts:a");
            assertNotNull(saved);
            var cache=new EmbeddingCache(store);
            assertTrue(cache.put(saved,"actual title and text",vector()));
            assertNull(cache.get(saved,"different title and text"));
            assertNull(cache.get(store.projection("demo:posts:b"),"actual title and text"));
            assertNull(new EmbeddingCache(store,"other-model",10).get(saved,"actual title and text"));
        }
        try(var store=new ItemStore(temp.resolve("db"))){
            var cache=new EmbeddingCache(store);
            assertEquals(vector(),cache.get(saved,"actual title and text"));
            store.apply(IngestionTest.item("a",2));
            assertNull(cache.get(saved,"actual title and text"));
            assertFalse(cache.put(saved,"actual title and text",vector()));
            assertEquals(0,cache.status().path("storedVectors").asInt());
        }
    }
    @Test void ancestorChangeAndDeletionRemoveDependentCache()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))){
            var parent=IngestionTest.item("parent",1);store.apply(parent);
            var child=IngestionTest.item("child",1);child.set("parent",parent.get("object"));store.apply(child);
            var cache=new EmbeddingCache(store);var p=store.projection("demo:posts:child");
            assertTrue(cache.put(p,"text",vector()));
            var revoked=IngestionTest.item("parent",2);revoked.withObject("acl").putArray("groups").add("secure");store.apply(revoked);
            assertNull(cache.get(p,"text"));assertEquals(0,cache.status().path("storedVectors").asInt());
            var latest=store.projection("demo:posts:parent");assertTrue(cache.put(latest,"text",vector()));
            store.apply(IngestionTest.item("parent",3).put("deleted",true));
            assertNull(cache.get(latest,"text"));assertEquals(0,cache.status().path("storedVectors").asInt());
        }
    }
    @Test void corruptionAndInvalidVectorsCannotEnterSearchAndStorageIsBounded()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))){
            store.apply(IngestionTest.item("a",1));var p=store.projection("demo:posts:a");var cache=new EmbeddingCache(store,"model",2);
            assertThrows(IllegalArgumentException.class,()->cache.put(p,"bad",List.of(1.0)));
            var invalid=vector();invalid.set(0,Double.NaN);assertThrows(IllegalArgumentException.class,()->cache.put(p,"bad",invalid));
            cache.put(p,"first",vector());cache.put(p,"second",vector());cache.put(p,"third",vector());
            assertEquals(2,cache.status().path("storedVectors").asInt());
            try(var statement=store.connection().createStatement()){statement.executeUpdate("UPDATE embedding_cache SET vector='[1]'");}
            assertNull(cache.get(p,"third"));
        }
    }
    @Test void searchSyncReusesCompletedEncodingAfterServiceRestart()throws Exception {
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var segmentRequests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/segments",exchange->{try{
            segmentRequests.incrementAndGet();
            var input=Contract.JSON.readTree(exchange.getRequestBody());String body=input.path("text").asText();
            var response=Map.of("policyVersion",EmbeddingClient.POLICY,"segments",List.of(Map.of("text",body,"input",input.path("title").asText()+body,
                "charStart",0,"charEnd",body.codePointCount(0,body.length()),"inputTokens",50)));
            byte[] bytes=Contract.JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});
        server.createContext("/v1/embeddings",exchange->{try{
            requests.incrementAndGet();var input=Contract.JSON.readTree(exchange.getRequestBody());int count=input.path("texts").size();
            byte[] bytes=Contract.JSON.writeValueAsBytes(Map.of("modelVersion",EmbeddingClient.MODEL,"policyVersion",EmbeddingClient.POLICY,"dimensions",1024,
                "vectors",Collections.nCopies(count,vector()),"inputTokens",Collections.nCopies(count,50),"truncated",Collections.nCopies(count,false),"elapsedMs",1));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try(var store=new ItemStore(temp.resolve("db"));var index=new SearchTest.IndexFixture()){
            store.apply(IngestionTest.item("a",1));
            var model=new EmbeddingClient(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()),EngineTest.KEY,java.time.Duration.ofSeconds(5));
            try(var first=new SearchService(store,index.client(),model,"demo",.4)){
                first.sync();
                assertEquals(1,requests.get());
                var query=(com.fasterxml.jackson.databind.node.ObjectNode)SearchTest.request(5);query.put("mode","hybrid");
                var internal=first.searchCandidates(query,SearchTest.ALICE).path("items").get(0);
                var evidence=internal.path("_vectorEvidence");
                assertEquals("demo:posts:a",evidence.path("root_key").asText());
                assertEquals(store.projection("demo:posts:a").guard(),evidence.path("guard").asText());
                assertEquals(evidence.path("guard").asText(),internal.path("_evidenceGuard").asText());
                assertEquals(internal.path("item").path("text").asText(),evidence.path("text").asText());
                assertTrue(evidence.path("location").isObject());
                String publicResponse=first.search(query,SearchTest.ALICE).toString();
                assertFalse(publicResponse.contains("_vectorEvidence"));
                assertFalse(publicResponse.contains("_evidenceGuard"));
            }
            int afterQueries=requests.get();
            int writes=index.writes,segments=segmentRequests.get();
            try(var restarted=new SearchService(store,index.client(),model,"demo",.4)){restarted.sync();}
            assertEquals(afterQueries,requests.get(),"unchanged vectors must not be encoded again");
            assertEquals(writes,index.writes,"unchanged index must not be rewritten after restart");
            assertEquals(segments,segmentRequests.get(),"unchanged index must not require segmentation");
            index.indexUuid="fixture-index-2";index.docs.clear();
            try(var recreated=new SearchService(store,index.client(),model,"demo",.4)){recreated.sync();}
            assertTrue(index.writes>writes,"new ES index UUID requires reconstruction");
            assertEquals(afterQueries,requests.get(),"encoding cache remains reusable across index rebuild");
            store.apply(IngestionTest.item("a",2).put("title","changed title"));
            try(var changed=new SearchService(store,index.client(),model,"demo",.4)){changed.sync();}
            assertEquals(afterQueries+1,requests.get(),"a new guard requires fresh encoding");
        }finally{server.stop(0);}
    }
}
