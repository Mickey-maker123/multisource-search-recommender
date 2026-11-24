package local.federated.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.net.http.*;

class IngestionTest {
    @TempDir Path temp;
    static final ItemStore.Principal ALICE=new ItemStore.Principal("alice",Set.of("research"));
    static ObjectNode item(String id,long version) throws Exception {
        var sample=Contract.JSON.readTree(Files.readAllBytes(Path.of("../fixtures/d1/items.json"))).path("items").get(0).deepCopy();
        ((ObjectNode)sample.path("object")).put("nativeId",id);((ObjectNode)sample).put("version",version);
        return (ObjectNode)sample;
    }
    @Test void versionsTombstonesAndRestart()throws Exception {
        Path db=temp.resolve("store");
        try(var store=new ItemStore(db)) {
            var v1=item("post-1",1);assertEquals("applied",store.apply(v1).status());
            var v2=item("post-1",2);v2.put("text","updated");assertEquals("applied",store.apply(v2).status());
            assertEquals("stale",store.apply(v1).status());assertEquals("duplicate",store.apply(v2).status());
            var reorder=Contract.canonical(v2);assertEquals("duplicate",store.apply(reorder).status());
            v2.put("text","conflict");assertEquals("VERSION_CONFLICT",store.apply(v2).code());
            var dead=item("post-1",3).put("deleted",true);assertEquals("applied",store.apply(dead).status());
            var tombstone=store.item("demo:posts:post-1");assertFalse(tombstone.has("text"));assertFalse(tombstone.has("acl"));
        }
        try(var store=new ItemStore(db)) {
            assertEquals("deleted",store.status("demo:posts:post-1").path("state").asText());
            assertEquals("stale",store.apply(item("post-1",2)).status());assertEquals("applied",store.apply(item("post-1",4)).status());
        }
    }
    @Test void parentsAccessAndLateParserCannotRevive()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            var parent=item("parent",1);store.apply(parent);
            var child=item("child",1);child.set("parent",parent.get("object"));store.apply(child);
            String key="demo:posts:child";
            assertTrue(store.canAccess(key,ALICE,true));assertFalse(store.canAccess(key,new ItemStore.Principal("bob",Set.of()),false));
            var ticket=store.begin(key,ALICE);
            var ancestor=item("parent",2);ancestor.withObject("acl").putArray("groups");store.apply(ancestor);
            assertFalse(store.canAccess(key,ALICE,true));assertFalse(store.finish(ticket,ALICE,"ready","OK",Contract.JSON.createArrayNode().add("private")));
            assertFalse(store.status(key).has("derived"));
            var cycle=item("parent",3);cycle.set("parent",child.get("object"));assertEquals("PARENT_CYCLE",store.apply(cycle).code());
            parent.put("version",3);store.apply(parent);
            var current=store.begin(key,ALICE);store.apply(item("child",2).put("deleted",true));
            assertFalse(store.finish(current,ALICE,"ready","OK",Contract.JSON.createArrayNode()));assertFalse(store.item(key).has("text"));
        }
    }
    @Test void schemaReferencesAndParseAuthorizationFailClosed()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            var bad=item("x",1);bad.remove("acl");assertEquals("rejected",store.apply(bad).status());
            bad=item("x",1).put("commentText","secret");assertEquals("rejected",store.apply(bad).status());
            bad=item("x",1).put("version",0);assertEquals("rejected",store.apply(bad).status());
            bad=item("x",1);bad.set("parent",item("absent",1).get("object"));assertEquals("PARENT_UNAVAILABLE",store.apply(bad).code());
            bad=item("x",1);var ref=item("other",1).withObject("object").put("domain","elsewhere");bad.set("parent",ref);assertEquals("CROSS_DOMAIN_PARENT",store.apply(bad).code());
            var blocked=item("blocked",1).put("parseAllowed",false);store.apply(blocked);
            assertTrue(store.canAccess("demo:posts:blocked",ALICE,false));
            assertThrows(IllegalArgumentException.class,()->store.begin("demo:posts:blocked",ALICE));
        }
    }
    @Test void interruptedJobsPersistFailureAndNewAttemptSupersedes()throws Exception {
        var directory=temp.resolve("db");
        try(var store=new ItemStore(directory)) {
            store.apply(item("x",1));var first=store.begin("demo:posts:x",ALICE);var second=store.begin("demo:posts:x",ALICE);
            assertFalse(store.finish(first,ALICE,"ready","OK",Contract.JSON.createArrayNode()));
            assertTrue(store.finish(second,ALICE,"ready","OK",Contract.JSON.createArrayNode()));
            store.begin("demo:posts:x",ALICE);
        }
        try(var store=new ItemStore(directory)) {
            assertEquals("failed",store.status("demo:posts:x").path("state").asText());assertEquals("PROCESS_INTERRUPTED",store.status("demo:posts:x").path("code").asText());
        }
    }
    @Test void concurrentVersionsLeaveHighestVersion()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<?>> results=new ArrayList<>();
                for(int i=1;i<=12;i++){final int v=i;results.add(pool.submit(()->{try{store.apply(item("x",v));}catch(Exception e){throw new RuntimeException(e);}}));}
                for(var f:results)f.get();assertEquals(12,store.item("demo:posts:x").path("version").asLong());
            }finally{pool.shutdownNow();}
        }
    }
    @Test void actualHttpBatchValidationAndPartialSuccess()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var server=new EngineServer("127.0.0.1",0,EngineTest.KEY,store,"demo")) {
            server.start();var http=HttpClient.newHttpClient();var url=URI.create("http://127.0.0.1:"+server.port()+"/api/engine/v1/items:batch");
            java.util.function.BiFunction<String,String,HttpResponse<String>> send=(body,type)->{
                try{return http.send(HttpRequest.newBuilder(url).header("Authorization","Bearer "+EngineTest.KEY).header("Content-Type",type).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
                catch(Exception e){throw new RuntimeException(e);}
            };
            var batch=Contract.JSON.createObjectNode().put("schemaVersion","1.0");var items=batch.putArray("items");items.add(item("good",1));items.add(item("bad",0));
            var response=send.apply(batch.toString(),"application/json");assertEquals(200,response.statusCode());
            var results=Contract.JSON.readTree(response.body()).path("results");assertEquals("applied",results.get(0).path("status").asText());assertEquals("rejected",results.get(1).path("status").asText());
            assertEquals(400,send.apply("{}","text/plain").statusCode());
            assertEquals(400,send.apply("{\"a\":1,\"a\":2}","application/json").statusCode());
            assertEquals(422,send.apply("{}","application/json").statusCode());
            assertEquals(422,send.apply(batch.toString().replace("\"schemaVersion\":\"1.0\"","\"schemaVersion\":1.0"),"application/json").statusCode());
            assertEquals(413,send.apply("x".repeat(1024*1024+1),"application/json").statusCode());
            ((ObjectNode)items.get(0).path("object")).put("domain","wrong");
            assertTrue(send.apply(batch.toString(),"application/json").body().contains("DOMAIN_FORBIDDEN"));
        }
    }
}
