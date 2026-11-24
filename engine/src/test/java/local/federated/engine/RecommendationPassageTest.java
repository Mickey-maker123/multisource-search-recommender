package local.federated.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RecommendationPassageTest {
    @TempDir Path temp;
    private ObjectNode document(String id,String text)throws Exception {
        var item=IngestionTest.item(id,1).put("title",id).put("text",text);
        item.withObject("fields").remove("category");
        item.withObject("location").put("charStart",0).put("charEnd",text.codePointCount(0,text.length()));
        return item;
    }
    private ObjectNode task(String text) {
        var request=RecommendationTest.request(5);
        request.putObject("task").put("taskId","deep-task").put("text",text).put("expiresAt",UserBehaviorTest.CLOCK.instant().plusSeconds(600).toString());
        return request;
    }
    @Test void taskCardCompletesTheSameAuthorizedLineAndLeavesRankingIntact() throws Exception {
        try (var store = new ItemStore(temp.resolve("cards"))) {
            String line = "😀 nebula checksum\n" + "x".repeat(570) + " store a stable identifier and finish the column mapping.";
            String text = line + "\n" + "appendix ".repeat(120);
            store.apply(document("guide", text));
            store.apply(document("other", "other document"));
            var users = new UserBehaviorStore(store, BehaviorPolicy.load(null), UserBehaviorTest.CLOCK);
            var legacy = new RecommendationService(store, users, null, "demo", "legacy");
            var upgraded = new RecommendationService(store, users, null, "demo", "task-bm25");
            var request = task("nebula checksum").put("pageSize", 1);
            var a = legacy.recommend(request, UserBehaviorTest.ALICE);
            var b = upgraded.recommend(request, UserBehaviorTest.ALICE);
            var old = a.path("items").get(0);
            var next = b.path("items").get(0);
            for (String field : java.util.List.of("score", "channels", "reasons")) assertEquals(old.get(field), next.get(field));
            assertEquals(old.at("/item/object"), next.at("/item/object"));
            assertEquals(600, old.at("/item/location/charEnd").asInt());
            assertEquals(line, next.at("/item/text").asText());
            assertEquals(line.codePointCount(0, line.length()), next.at("/item/location/charEnd").asInt());
            var source = Contract.JSON.createObjectNode().put("schemaVersion","1.1").put("version",1).put("offset",0).put("limit",1);
            source.set("object", next.at("/item/object"));
            source.set("anchor", next.at("/item/location"));
            assertTrue(new AnalysisService(store,"demo",users).sourceContent(source,UserBehaviorTest.ALICE).path("anchorMatched").asBoolean());
            var secondOld = legacy.recommend(request.deepCopy().put("cursor",a.path("nextCursor").asText()), UserBehaviorTest.ALICE);
            var secondNew = upgraded.recommend(request.deepCopy().put("cursor",b.path("nextCursor").asText()), UserBehaviorTest.ALICE);
            assertEquals(secondOld.path("items"), secondNew.path("items"));
            ((ObjectNode)request.path("task")).put("expiresAt", UserBehaviorTest.CLOCK.instant().minusSeconds(1).toString());
            assertEquals(legacy.recommend(request,UserBehaviorTest.ALICE).path("items"),
                upgraded.recommend(request,UserBehaviorTest.ALICE).path("items"));
            request.remove("task");
            assertEquals(legacy.recommend(request,UserBehaviorTest.ALICE).path("items"),
                upgraded.recommend(request,UserBehaviorTest.ALICE).path("items"));
            var revoked = document("guide", "new body").put("version", 2);
            revoked.withObject("acl").putArray("groups").add("private");
            store.apply(revoked);
            assertFalse(upgraded.recommend(task("nebula checksum"),UserBehaviorTest.ALICE).path("items").toString().contains("\"guide\""));
            assertThrows(SearchService.Failure.class, () -> new AnalysisService(store,"demo",users).sourceContent(source,UserBehaviorTest.ALICE));
        }
    }
    @Test void deepUnicodePassageIsTheDisplayedAndLocatableEvidence()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            String body="😀前言 ".repeat(1400)+"星云恢复需要校验离线备份与重建索引";
            assertEquals("applied",store.apply(document("deep",body)).status());assertEquals("applied",store.apply(document("other","一般行政说明")).status());
            var users=new UserBehaviorStore(store,BehaviorPolicy.load(null),UserBehaviorTest.CLOCK);
            users.applyUser("demo",UserBehaviorTest.user("alice"));
            var rec=new RecommendationService(store,users,null,"demo");
            var result=rec.recommend(task("星云恢复校验离线备份重建索引"),UserBehaviorTest.ALICE);
            var hit=result.path("items").get(0).path("item");
            assertEquals("deep",hit.path("object").path("nativeId").asText());
            assertTrue(hit.path("text").asText().contains("星云恢复"));
            assertTrue(hit.path("location").path("charStart").asInt()>4000);
            int start=hit.path("location").path("charStart").asInt(),end=hit.path("location").path("charEnd").asInt();
            assertEquals(hit.path("text").asText(),body.substring(body.offsetByCodePoints(0,start),body.offsetByCodePoints(0,end)));
            var request=Contract.JSON.createObjectNode().put("schemaVersion","1.1").put("version",1).put("offset",0).put("limit",1);
            request.set("object",hit.path("object"));request.set("anchor",hit.path("location"));
            assertTrue(new AnalysisService(store,"demo",users).sourceContent(request,UserBehaviorTest.ALICE).path("anchorMatched").asBoolean());
            users.applyEvent("demo",UserBehaviorTest.event("dismiss-deep","dismiss","deep"),UserBehaviorTest.ALICE);
            assertFalse(rec.recommend(task("星云恢复"),UserBehaviorTest.ALICE).path("items").toString().contains("\"nativeId\":\"deep\""));
            users.applyEvent("demo",UserBehaviorTest.event("undo-deep","undo","deep").put("undoOf","dismiss-deep"),UserBehaviorTest.ALICE);
            assertEquals("deep",rec.recommend(task("星云恢复"),UserBehaviorTest.ALICE).path("items").get(0).path("item").path("object").path("nativeId").asText());
        }
    }
    @Test void laterParsedPartAndKeywordFallbackDoNotClaimSemanticEvidence()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var index=new SearchTest.IndexFixture();var search=new SearchService(store,index.client(),null,"demo",.4)) {
            var item=document("parts","fallback");assertEquals("applied",store.apply(item).status());
            var parts=Contract.JSON.createArrayNode();
            for(int i=1;i<=2;i++) {
                String text=i==1?"普通前言":"nebula recovery evidence";
                var part=parts.addObject().put("text",text);part.putObject("location").put("uri",item.path("location").path("uri").asText()).put("paragraph",i).put("charStart",0).put("charEnd",text.length());
            }
            var ticket=store.begin("demo:posts:parts",IngestionTest.ALICE);
            assertTrue(store.finish(ticket,IngestionTest.ALICE,"ready","OK",parts));search.sync();
            // Transport fixture: explicitly return the matching keyword passage, not every indexed part.
            index.hitFilter=d->d.path("text").asText().contains("nebula");
            var users=new UserBehaviorStore(store,BehaviorPolicy.load(null),UserBehaviorTest.CLOCK);
            var rec=new RecommendationService(store,users,search,"demo");
            var result=rec.recommend(task("nebula recovery evidence"),UserBehaviorTest.ALICE);
            var row=result.path("items").get(0);
            assertEquals(2,row.path("item").path("location").path("paragraph").asInt());
            assertFalse(row.path("reasons").toString().contains("semantic:"));
            assertTrue(result.path("degraded").asBoolean());
        }
    }
    @Test void relatedMatchingCanUseTheEndOfBothDocuments()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))) {
            var seed=document("seed","alpha ".repeat(1000)+"nebula recovery repair guide");assertEquals("applied",store.apply(seed).status());
            assertEquals("applied",store.apply(document("related","beta ".repeat(1000)+"nebula recovery repair guide")).status());
            assertEquals("applied",store.apply(document("unrelated","gamma policy")).status());
            var users=new UserBehaviorStore(store,BehaviorPolicy.load(null),UserBehaviorTest.CLOCK);
            var rec=new RecommendationService(store,users,null,"demo");
            var request=RecommendationTest.request(5).put("kind","related");request.set("item",seed.path("object"));
            var rows=rec.recommend(request,UserBehaviorTest.ALICE).path("items");
            assertEquals("related",rows.get(0).path("item").path("object").path("nativeId").asText());
            assertTrue(rows.get(0).path("item").path("text").asText().contains("nebula recovery"));
        }
    }
    @Test void cachedPassagesCannotOutliveReparseUpdateOrRevocation()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"))){
            store.apply(document("a","old nebula evidence"));
            var users=new UserBehaviorStore(store,BehaviorPolicy.load(null),UserBehaviorTest.CLOCK);
            var rec=new RecommendationService(store,users,null,"demo","task-bm25");
            assertTrue(rec.recommend(task("nebula"),UserBehaviorTest.ALICE).toString().contains("old nebula"));
            var ticket=store.begin("demo:posts:a",IngestionTest.ALICE);
            var parts=Contract.JSON.createArrayNode();var part=parts.addObject().put("text","new nebula evidence");part.set("location",document("a","new nebula evidence").path("location"));
            assertTrue(store.finish(ticket,IngestionTest.ALICE,"ready","OK",parts));
            var updated=rec.recommend(task("nebula"),UserBehaviorTest.ALICE);
            assertTrue(updated.toString().contains("new nebula"));assertFalse(updated.toString().contains("old nebula"));
            var revoked=document("a","revoked nebula").put("version",2);revoked.withObject("acl").putArray("groups").add("secret");
            store.apply(revoked);
            assertTrue(rec.recommend(task("nebula"),UserBehaviorTest.ALICE).path("items").isEmpty());
        }
    }
}
