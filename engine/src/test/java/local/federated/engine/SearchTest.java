package local.federated.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.net.*;
import java.time.*;
import java.util.*;

class SearchTest {
    @TempDir Path temp;
    static final LocalIdentities.Identity ALICE=new LocalIdentities.Identity("demo",IngestionTest.ALICE);
    static JsonNode request(int size)throws Exception{return Contract.JSON.readTree("{\"schemaVersion\":\"1.0\",\"query\":\"test\",\"mode\":\"keyword\",\"pageSize\":"+size+"}");}
    /** Transport fixture only: no claim to simulate actual BM25 or vector quality. */
    static final class IndexFixture implements AutoCloseable {
        final HttpServer server;
        final Map<String,JsonNode> docs=new LinkedHashMap<>();
        volatile JsonNode lastSearch;
        java.util.function.Predicate<JsonNode> hitFilter=d->true;
        String indexUuid="fixture-index-1";
        int writes;
        boolean failBulk;
        int failBulkAt=-1;
        IndexFixture()throws Exception {
            HttpServer created=null;
            for(int port=19290;port<=19299;port++)try{created=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);break;}catch(java.io.IOException ignored){}
            if(created==null)throw new java.io.IOException("No free test ES port");server=created;
            server.createContext("/",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();JsonNode input=null;String bulk="";
                    if(!method.equals("GET")){byte[] data=exchange.getRequestBody().readAllBytes();if(path.endsWith("/_bulk"))bulk=new String(data,java.nio.charset.StandardCharsets.UTF_8);else if(data.length>0)input=Contract.JSON.readTree(data);}
                    Object response=Map.of();int status=200;
                    if(path.equals("/"))response=Map.of("cluster_name","engine-v01");
                    else if(path.endsWith("/_mapping")){status=404;}
                    else if(path.endsWith("/_settings"))response=Map.of(path.split("/")[1],Map.of("settings",Map.of("index",Map.of("uuid",indexUuid))));
                    else if(path.endsWith("/_bulk")){
                        var items=Contract.JSON.createArrayNode();var lines=bulk.split("\n");
                        for(int i=0;i<lines.length;i+=2){
                            var action=Contract.JSON.readTree(lines[i]);var document=Contract.JSON.readTree(lines[i+1]);
                            boolean failed=failBulk||i/2==failBulkAt;
                            if(!failed){docs.put(action.path("index").path("_id").asText(),document);writes++;}
                            items.addObject().putObject("index").put("status",failed?429:201);
                        }
                        response=Map.of("errors",failBulk||failBulkAt>=0,"items",items);
                    }
                    else if(path.contains("/_doc/")){docs.put(path.substring(path.lastIndexOf('/')+1),input);writes++;}
                    else if(path.endsWith("/_delete_by_query")){
                        Set<String> keys=new HashSet<>();keys.add(input.path("query").path("term").path("root_key").asText());
                        input.path("query").path("terms").path("root_key").forEach(k->keys.add(k.asText()));
                        docs.values().removeIf(d->keys.contains(d.path("root_key").asText()));
                    }
                    else if(path.endsWith("/_search")) {
                        lastSearch=input;
                        var hits=Contract.JSON.createArrayNode();Set<String> allowed=new HashSet<>();
                        boolean vector=input.has("knn");
                        var filters=vector?input.path("knn").path("filter").path("bool").path("filter"):input.path("query").path("bool").path("filter");
                        for(var filter:filters)if(filter.path("terms").has("root_key"))for(var key:filter.path("terms").path("root_key"))allowed.add(key.asText());
                        for(var d:docs.values())if(hitFilter.test(d)&&d.path("kind").asText().equals(vector?"vector":"keyword")&&matchesFilters(d,filters)){var hit=hits.addObject().put("_score",1);hit.set("_source",d);}
                        response=Map.of("hits",Map.of("hits",hits),"_shards",Map.of("failed",0),"timed_out",false);
                    }
                    byte[] bytes=Contract.JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }finally{exchange.close();}
            });server.start();
        }
        static boolean matchesFilters(JsonNode document,JsonNode filters){for(var filter:filters)if(!matchesClause(document,filter,""))return false;return true;}
        static boolean matchesClause(JsonNode document,JsonNode query,String prefix) {
            if(query.has("nested")) {
                var nested=query.path("nested");String path=nested.path("path").asText();
                for(var scope:document.path(path))if(matchesClause(scope,nested.path("query"),path+"."))return true;
                return false;
            }
            if(query.has("bool")) {
                var b=query.path("bool");
                for(String field:List.of("filter","must"))for(var clause:b.path(field))if(!matchesClause(document,clause,prefix))return false;
                for(var clause:b.path("must_not"))if(matchesClause(document,clause,prefix))return false;
                int matched=0;for(var clause:b.path("should"))if(matchesClause(document,clause,prefix))matched++;
                return matched>=b.path("minimum_should_match").asInt();
            }
            for(String kind:List.of("term","terms"))if(query.has(kind)) {
                var entry=query.path(kind).fields().next();String field=entry.getKey();if(!prefix.isEmpty())field=field.substring(prefix.length());
                var actual=document.path(field);List<String> values=new ArrayList<>();if(actual.isArray())actual.forEach(v->values.add(v.asText()));else if(!actual.isMissingNode())values.add(actual.asText());
                if(kind.equals("term"))return values.contains(entry.getValue().asText());
                for(var value:entry.getValue())if(values.contains(value.asText()))return true;
                return false;
            }
            return true;
        }
        SearchIndex client(){return new SearchIndex(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"engine-v01-items-test-abcdef12");}
        public void close(){server.stop(0);}
    }
    @Test void indexAndModelConnectionsRejectCommunityTargets() {
        assertThrows(IllegalArgumentException.class,()->new SearchIndex(URI.create("http://127.0.0.1:9200"),"engine-v01-items"));
        assertThrows(IllegalArgumentException.class,()->new SearchIndex(URI.create("http://127.0.0.1:19200"),"forum-post-lzh-week3"));
        assertThrows(IllegalArgumentException.class,()->new SearchIndex(URI.create("http://external.test:19200"),"engine-v01-items"));
        assertThrows(IllegalArgumentException.class,()->new EmbeddingClient(URI.create("http://127.0.0.1:8000"),EngineTest.KEY,Duration.ofSeconds(1)));
    }
    @Test void keywordCandidateBudgetCountsObjectsAndKeepsAclFilter()throws Exception {
        try(var backend=new IndexFixture()) {
            backend.client().recall("demo",List.of("authorized-root"),Contract.JSON.createObjectNode(),"test",null,0.4);
            var sent=backend.lastSearch;
            assertEquals("root_key",sent.path("collapse").path("field").asText());
            assertEquals(500,sent.path("size").asInt());
            assertEquals("authorized-root",sent.path("query").path("bool").path("filter").get(1).path("terms").path("root_key").get(0).asText());
            assertEquals("desc",sent.path("sort").get(0).path("_score").asText());
        }
    }
    @Test void newFeedbackIsExplicitAndLegacyResponseStaysUnchanged()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var backend=new IndexFixture();var search=new SearchService(store,backend.client(),null,"demo",0.4)) {
            store.apply(IngestionTest.item("a",1).put("text","test body"));search.sync();
            assertFalse(search.search(request(10),ALICE).has("matchStatus"));
            var modern=(com.fasterxml.jackson.databind.node.ObjectNode)request(10);modern.put("schemaVersion","1.1");
            assertEquals("keyword_results",search.search(modern,ALICE).path("matchStatus").asText());
            modern.put("mode","hybrid");
            assertEquals("incomplete",search.search(modern,ALICE).path("matchStatus").asText());
            assertThrows(IllegalArgumentException.class,()->search.configureLowMatchThreshold(.2));
            assertThrows(IllegalArgumentException.class,()->search.configureLowMatchThreshold(Double.NaN));
        }
    }
    @Test void queryNormalizationFiltersAndImpersonationAreRejected()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var backend=new IndexFixture();var search=new SearchService(store,backend.client(),null,"demo",0.4)) {
            for(String input:List.of("{\"schemaVersion\":\"1.0\",\"query\":\"   \",\"mode\":\"keyword\",\"pageSize\":1}","{\"schemaVersion\":\"1.0\",\"query\":\"test\",\"mode\":\"keyword\",\"pageSize\":1,\"userId\":\"admin\"}","{\"schemaVersion\":\"1.0\",\"query\":\"test\",\"mode\":\"keyword\",\"pageSize\":1,\"filters\":{\"updatedFrom\":\"bad\"}}"))
                assertThrows(IllegalArgumentException.class,()->search.search(Contract.JSON.readTree(input),ALICE));
        }
    }
    @Test void paginationRechecksDeleteAndAclAndBindsPrincipalAndQuery()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var backend=new IndexFixture();var search=new SearchService(store,backend.client(),null,"demo",0.4)) {
            for(String id:List.of("a","b","c"))store.apply(IngestionTest.item(id,1).put("text","test body"));search.sync();
            var first=search.search(request(1),ALICE);String cursor=first.path("nextCursor").asText();assertTrue(first.path("hasMore").asBoolean());
            var next=(com.fasterxml.jackson.databind.node.ObjectNode)request(1);next.put("cursor",cursor);
            var other=new LocalIdentities.Identity("demo",new ItemStore.Principal("bob",Set.of("research")));
            assertEquals("CURSOR_MISMATCH",assertThrows(SearchService.Failure.class,()->search.search(next,other)).code);
            var changed=next.deepCopy().put("query","different");assertEquals("CURSOR_MISMATCH",assertThrows(SearchService.Failure.class,()->search.search(changed,ALICE)).code);
            var tampered=next.deepCopy().put("cursor",cursor+"x");assertEquals("INVALID_CURSOR",assertThrows(SearchService.Failure.class,()->search.search(tampered,ALICE)).code);
            var firstId=first.path("items").get(0).path("item").path("object").path("nativeId").asText();
            for(String id:List.of("a","b","c"))if(!id.equals(firstId))store.apply(IngestionTest.item(id,2).put("deleted",true));
            var page=search.search(next,ALICE);assertTrue(page.path("items").isEmpty());assertFalse(page.path("hasMore").asBoolean());
        }
    }
    @Test void cursorExpiresAfterThirtyMinutes()throws Exception {
        final long[] now={1000};Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now[0]);}};
        try(var store=new ItemStore(temp.resolve("db"));var backend=new IndexFixture();var search=new SearchService(store,backend.client(),null,"demo",0.4,clock)) {
            store.apply(IngestionTest.item("a",1));store.apply(IngestionTest.item("b",1));search.sync();
            var first=search.search(request(1),ALICE);var next=(com.fasterxml.jackson.databind.node.ObjectNode)request(1);next.put("cursor",first.path("nextCursor").asText());
            now[0]+=30*60*1000+1;assertEquals("CURSOR_EXPIRED",assertThrows(SearchService.Failure.class,()->search.search(next,ALICE)).code);
        }
    }
    @Test void trustedIdentityReloadsGroupsAndRevocationAndRejectsDuplicateKeys()throws Exception {
        Path file=temp.resolve("auth.json");String key=EngineTest.KEY;
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        var entry=Contract.JSON.createObjectNode().put("tokenSha256",hash).put("domain","demo").put("userId","alice").put("active",true);entry.putArray("groups").add("research");
        var config=Contract.JSON.createObjectNode();config.putArray("principals").add(entry);Files.writeString(file,config.toString());var auth=new LocalIdentities(file);
        assertEquals(Set.of("research"),auth.authenticate("Bearer "+key).principal().groups());entry.putArray("groups");Files.writeString(file,config.toString());assertTrue(auth.authenticate("Bearer "+key).principal().groups().isEmpty());
        entry.put("active",false);Files.writeString(file,config.toString());assertNull(auth.authenticate("Bearer "+key));
        ((com.fasterxml.jackson.databind.node.ArrayNode)config.path("principals")).add(entry);Files.writeString(file,config.toString());assertThrows(java.io.IOException.class,()->auth.authenticate("Bearer "+key));
    }
    @Test void keywordSearchIsPublishedBeforeSlowModelFinishes()throws Exception {
        var arrived=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var modelServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        modelServer.createContext("/",exchange->{try{arrived.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);exchange.sendResponseHeaders(503,-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});modelServer.start();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try(var store=new ItemStore(temp.resolve("db"));var backend=new IndexFixture();var search=new SearchService(store,backend.client(),new EmbeddingClient(URI.create("http://127.0.0.1:"+modelServer.getAddress().getPort()),EngineTest.KEY,Duration.ofSeconds(10)),"demo",0.4)) {
            store.apply(IngestionTest.item("a",1));var syncing=pool.submit(()->{try{search.sync();}catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(arrived.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(search.available());assertEquals(1,search.search(request(10),ALICE).path("items").size());
            release.countDown();syncing.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }finally{release.countDown();modelServer.stop(0);pool.shutdownNow();}
    }
}
