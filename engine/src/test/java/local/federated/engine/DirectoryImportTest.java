package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;

class DirectoryImportTest {
    @TempDir Path temp;
    private ObjectNode source(Path root,String id) {
        var row=Contract.JSON.createObjectNode().put("sourceId",id).put("label","Approved documents").put("directory",root.toString())
            .put("parseAllowed",true).put("deleteMissing",true).put("scanIntervalSeconds",0);
        var acl=row.putObject("acl").put("public",false);acl.putArray("users");acl.putArray("groups").add("research");
        row.putObject("parseAs").put("user","parser").putArray("groups").add("research").add("secure");
        return row;
    }
    private Path config(JsonNode... sources)throws Exception {
        var config=Contract.JSON.createObjectNode().put("schemaVersion","1.0");var list=config.putArray("sources");
        for(var source:sources)list.add(source);
        Path path=temp.resolve("sources.json");Files.writeString(path,config.toString());return path;
    }
    private String key(String source,String relative) {return "demo:"+source+":"+Contract.digest(Contract.JSON.valueToTree(relative));}
    private String run(DirectoryImports importer,String source)throws Exception {String id=importer.enqueue(source,null);importer.process(id);return id;}

    @Test void incrementalModifyDeleteRestoreAndDuplicate()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.md"),"第一份部署资料");Files.writeString(root.resolve("b.txt"),"第二份接入资料");
        Path config=config(source(root,"docs"));
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            assertEquals("completed",imports.status(run(imports,"docs"),0).path("state").asText());
            String a=key("docs","a.md"),b=key("docs","b.txt");
            assertEquals(1,store.item(a).path("version").asLong());
            var repeated=imports.status(run(imports,"docs"),0);
            for(var entry:repeated.path("entries"))assertEquals("unchanged",entry.path("state").asText());
            assertEquals(1,store.status(a).path("jobs").size());
            Files.writeString(root.resolve("a.md"),"新版本资料");Files.delete(root.resolve("b.txt"));
            assertEquals("completed",imports.status(run(imports,"docs"),0).path("state").asText());
            assertEquals(2,store.item(a).path("version").asLong());assertTrue(store.item(b).path("deleted").asBoolean());
            assertEquals("新版本资料",store.projection(a).parts().get(0).path("text").asText());
            Files.writeString(root.resolve("b.txt"),"重新出现的资料");run(imports,"docs");
            assertEquals(3,store.item(b).path("version").asLong());assertFalse(store.item(b).path("deleted").asBoolean());
        }
    }

    @Test void parseFailureInvalidatesOldTextSuppressesDeletionAndRetryPersists()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.txt"),"old content");Files.writeString(root.resolve("b.md"),"keep until complete scan");
        Path config=config(source(root,"docs"));
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            run(imports,"docs");Files.write(root.resolve("a.txt"),new byte[]{(byte)0xff,(byte)0xfe});Files.delete(root.resolve("b.md"));
            String failed=run(imports,"docs");var outcome=imports.status(failed,0);
            assertEquals("completed_with_errors",outcome.path("state").asText());assertTrue(outcome.path("scanComplete").asBoolean());
            assertFalse(store.projection(key("docs","a.txt")).ready());assertFalse(store.item(key("docs","b.md")).path("deleted").asBoolean());
            Files.writeString(root.resolve("a.txt"),"fixed content");String retry=imports.retry(failed);imports.process(retry);
            assertEquals(failed,imports.status(retry,0).path("previousRunId").asText());assertEquals("completed",imports.status(retry,0).path("state").asText());
            assertTrue(store.item(key("docs","b.md")).path("deleted").asBoolean());
            assertEquals("completed_with_errors",imports.status(failed,0).path("state").asText());
        }
    }

    @Test void scanLimitAndMissingRootCannotDeletePreviouslyImportedItems()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.txt"),"a");Files.writeString(root.resolve("b.txt"),"b");
        var setting=source(root,"docs");Path config=config(setting);
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            run(imports,"docs");Files.delete(root.resolve("a.txt"));Files.writeString(root.resolve("c.txt"),"c");
            config(setting.put("maxFiles",1));var limited=imports.status(run(imports,"docs"),0);
            assertEquals("failed",limited.path("state").asText());assertEquals("SCAN_LIMIT",limited.path("code").asText());
            assertFalse(limited.path("scanComplete").asBoolean());assertFalse(store.item(key("docs","a.txt")).path("deleted").asBoolean());
            Files.move(root,temp.resolve("offline"));var unavailable=imports.status(run(imports,"docs"),0);
            assertEquals("failed",unavailable.path("state").asText());assertFalse(store.item(key("docs","a.txt")).path("deleted").asBoolean());
        }
    }

    @Test void pendingTaskSurvivesReopenAndRunningTaskBecomesExplicitFailure()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.txt"),"durable");
        Path config=config(source(root,"docs")),db=temp.resolve("db");String queued,running;
        try(var store=new ItemStore(db);var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            queued=imports.enqueue("docs",null);
            // Controlled crash-state setup; this is not a real OS power-loss test.
            running=UUID.randomUUID().toString();
            try(var p=store.connection().prepareStatement("INSERT INTO directory_runs(run_id,source_id,state,code,config_guard) VALUES (?,'docs','running','OK','test')")) {
                p.setString(1,running);p.executeUpdate();
            }
        }
        try(var store=new ItemStore(db);var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            assertEquals("queued",imports.status(queued,0).path("state").asText());
            assertEquals("PROCESS_INTERRUPTED",imports.status(running,0).path("code").asText());
            imports.process(queued);assertEquals("completed",imports.status(queued,0).path("state").asText());
        }
    }

    @Test void staleConfigurationDoesNotRunAndNewAclIsApplied()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.txt"),"permission changes");
        var setting=source(root,"docs");Path config=config(setting);
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            run(imports,"docs");String pending=imports.enqueue("docs",null);
            setting.withObject("acl").putArray("groups").add("secure");config(setting);
            imports.process(pending);assertEquals("SOURCE_CONFIG_CHANGED",imports.status(pending,0).path("code").asText());
            run(imports,"docs");assertFalse(store.canAccess(key("docs","a.txt"),IngestionTest.ALICE,false));
            assertTrue(store.canAccess(key("docs","a.txt"),new ItemStore.Principal("bob",Set.of("secure")),false));
            assertEquals(2,store.item(key("docs","a.txt")).path("version").asLong());
        }
    }

    @Test void itemAndOwnershipAreAtomicAndExternalUpdatesAreNotOverwritten()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.txt"),"a");Path config=config(source(root,"docs"));
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            var item=IngestionTest.item("rollback",1);
            assertThrows(java.sql.SQLException.class,()->store.applyDirectory(item,"x".repeat(65),"a.txt","a".repeat(64)));
            assertNull(store.item("demo:posts:rollback"));
            run(imports,"docs");ObjectNode external=(ObjectNode)store.item(key("docs","a.txt"));
            external.put("version",2).put("text","explicit external edit");store.apply(external);
            Files.writeString(root.resolve("a.txt"),"file changed");var outcome=imports.status(run(imports,"docs"),0);
            assertEquals("DIRECTORY_OWNERSHIP_CONFLICT",outcome.path("entries").get(0).path("code").asText());
            assertEquals("explicit external edit",store.item(key("docs","a.txt")).path("text").asText());
        }
    }

    @Test void boundedQueueAndAutomaticSourcesMakeProgress()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));ObjectNode[] sources=new ObjectNode[9];
        for(int i=0;i<9;i++)sources[i]=source(root,"src"+i).put("scanIntervalSeconds",60);
        Path config=config(sources);
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            String first=imports.enqueue("src0",null);assertEquals(first,imports.enqueue("src0",null));
            for(int i=1;i<8;i++)imports.enqueue("src"+i,null);
            assertEquals(429,assertThrows(SearchService.Failure.class,()->imports.enqueue("src8",null)).status);
            for(int i=0;i<9;i++)imports.tick();
            try(var p=store.connection().prepareStatement("SELECT COUNT(*) FROM directory_runs WHERE state='completed'");var r=p.executeQuery()) {
                r.next();assertEquals(9,r.getInt(1));
            }
        }
    }

    @Test void liveHttpImportUsesServerStoreAndRejectsArbitraryPaths()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Files.writeString(root.resolve("a.md"),"live import");
        Path config=config(source(root,"docs"));
        try(var store=new ItemStore(temp.resolve("db"));var server=new EngineServer("127.0.0.1",0,EngineTest.KEY,store,"demo",null,null,BehaviorPolicy.load(null),config)) {
            server.start();var http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.port()+"/api/engine/admin/v1/";
            assertEquals(401,http.send(HttpRequest.newBuilder(URI.create(base+"import-sources")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            var sources=http.send(HttpRequest.newBuilder(URI.create(base+"import-sources")).header("Authorization","Bearer "+EngineTest.KEY).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,sources.statusCode());assertFalse(sources.body().contains(root.toString()));
            var created=http.send(HttpRequest.newBuilder(URI.create(base+"imports")).header("Authorization","Bearer "+EngineTest.KEY).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"schemaVersion\":\"1.0\",\"sourceId\":\"docs\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,created.statusCode());String id=Contract.JSON.readTree(created.body()).path("runId").asText();
            JsonNode status=null;long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
            do {
                var response=http.send(HttpRequest.newBuilder(URI.create(base+"imports/"+id)).header("Authorization","Bearer "+EngineTest.KEY).GET().build(),HttpResponse.BodyHandlers.ofString());
                status=Contract.JSON.readTree(response.body());
                if(!Set.of("queued","running").contains(status.path("state").asText()))break;
                Thread.sleep(50);
            }while(System.nanoTime()<deadline);
            assertEquals("completed",status.path("state").asText());assertTrue(store.projection(key("docs","a.md")).ready());
            var invalid=http.send(HttpRequest.newBuilder(URI.create(base+"imports")).header("Authorization","Bearer "+EngineTest.KEY).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"schemaVersion\":\"1.0\",\"sourceId\":\"docs\",\"path\":\"C:/\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(422,invalid.statusCode());
        }
    }
}
