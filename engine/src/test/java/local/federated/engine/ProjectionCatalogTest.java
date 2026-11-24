package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionCatalogTest {
    @org.junit.jupiter.api.Test void shortlistUsesPostingsAndRecentOrderAcrossUpdates()throws Exception {
        try(var store=new ItemStore(temp.resolve("shortlist"))) {
            for(int i=0;i<30;i++) {
                var item=IngestionTest.item("candidate-"+i,1).put("title",i==0?"unique":"ordinary");
                item.put("updatedAt",java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString());store.apply(item);
            }
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            var found=store.shortlist("demo",java.util.Set.of("token:unique"),java.util.Set.of(),p->{checks.incrementAndGet();return true;},p->p.item().path("title").asText().equals("unique")?5:0,5);
            assertEquals("demo:posts:candidate-0",found.get(0).key());assertEquals("demo:posts:candidate-29",found.get(1).key());
            assertTrue(checks.get()<30);
            var updated=IngestionTest.item("candidate-0",2).put("title","ordinary").put("updatedAt","2025-01-01T00:00:00Z");store.apply(updated);
            found=store.shortlist("demo",java.util.Set.of("token:unique"),java.util.Set.of(),p->true,p->p.item().path("title").asText().equals("unique")?5:0,5);
            assertEquals("demo:posts:candidate-29",found.get(0).key());
            assertFalse(found.stream().anyMatch(p->p.key().equals("demo:posts:candidate-0")));
        }
    }
    @TempDir Path temp;
    @Test void metadataPersistsWithoutBodyAndTracksParseAndAncestorChanges()throws Exception {
        String siblingGuard;
        try(var store=new ItemStore(temp)){
            var parent=IngestionTest.item("parent",1);store.apply(parent);store.apply(IngestionTest.item("sibling",1));
            var child=IngestionTest.item("child",1).put("text","large body");child.set("parent",parent.path("object"));store.apply(child);
            var meta=store.metadataItem("demo:posts:child");assertTrue(meta.ready());assertFalse(meta.item().has("text"));assertTrue(meta.parts().isEmpty());
            assertEquals(store.projection(meta.key()).guard(),meta.guard());
            siblingGuard=store.metadataItem("demo:posts:sibling").guard();
            var parsing=store.begin(meta.key(),IngestionTest.ALICE);assertFalse(store.metadataItem(meta.key()).ready());
            assertTrue(store.finish(parsing,IngestionTest.ALICE,"ready","OK",null));assertTrue(store.metadataItem(meta.key()).ready());
            var revoked=IngestionTest.item("parent",2);revoked.withObject("acl").putArray("groups").add("secret");store.apply(revoked);
            assertFalse(store.canAccess(meta.key(),IngestionTest.ALICE,false));assertFalse(store.metadataItem(meta.key()).ready());
            assertEquals(siblingGuard,store.metadataItem("demo:posts:sibling").guard());
        }
        try(var reopened=new ItemStore(temp)){
            assertFalse(reopened.canAccess("demo:posts:child",IngestionTest.ALICE,false));
            assertEquals(siblingGuard,reopened.metadataItem("demo:posts:sibling").guard());
            assertEquals(3,reopened.metadata("demo").size());
        }
    }
    @Test void changingParentUpdatesTheDescendantInvalidationIndex()throws Exception {
        try(var store=new ItemStore(temp)){
            var a=IngestionTest.item("a",1);var b=IngestionTest.item("b",1);store.apply(a);store.apply(b);
            var child=IngestionTest.item("child",1);child.set("parent",a.path("object"));store.apply(child);
            child.put("version",2).set("parent",b.path("object"));store.apply(child);
            String guard=store.metadataItem("demo:posts:child").guard();
            store.apply(IngestionTest.item("a",2).put("deleted",true));
            assertEquals(guard,store.metadataItem("demo:posts:child").guard());
            store.apply(IngestionTest.item("b",2).put("deleted",true));
            assertFalse(store.canAccess("demo:posts:child",IngestionTest.ALICE,false));
            assertFalse(store.metadataItem("demo:posts:child").ready());
        }
    }
}
