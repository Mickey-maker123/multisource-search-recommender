package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary and authorization check with transport fixture; not an ES throughput measurement. */
class CapacityBoundaryTest {
    @TempDir Path temp;
    @Test @Timeout(180) void tenThousandAndOneObjectsRemainQueryableAndRevocationIsCurrent() throws Exception {
        try(var store=new ItemStore(temp);var index=new SearchTest.IndexFixture()) {
            var prototype=IngestionTest.item("capacity",1);
            for(int i=0;i<10001;i++) {
                var item=prototype.deepCopy();item.withObject("object").put("nativeId","boundary-"+i);
                assertEquals("applied",store.apply(item).status());
            }
            assertEquals(10001,store.metadata("demo").size());
            try(var search=new SearchService(store,index.client(),null,"demo",.4)) {
                search.sync();
                assertEquals(5,search.search(SearchTest.request(5),SearchTest.ALICE).path("items").size());
                int allowed=0;
                for(var filter:index.lastSearch.path("query").path("bool").path("filter"))
                    if(filter.path("terms").has("root_key"))allowed=filter.path("terms").path("root_key").size();
                assertEquals(0,allowed,"ordinary search must not serialize a full authorized root whitelist");
                assertTrue(index.lastSearch.toString().contains("access_scopes"));
                var users=new UserBehaviorStore(store,BehaviorPolicy.load(null),UserBehaviorTest.CLOCK);
                users.applyUser("demo",UserBehaviorTest.user("alice"));
                var rec=new RecommendationService(store,users,null,"demo");
                var response=rec.recommend(RecommendationTest.request(5),SearchTest.ALICE);
                assertEquals(5,response.path("items").size());
                var first=response.path("items").get(0).path("item").path("object");
                var revoked=prototype.deepCopy();revoked.set("object",first.deepCopy());revoked.put("version",2);
                revoked.withObject("acl").putArray("groups");revoked.withObject("acl").putArray("users");revoked.withObject("acl").put("public",false);
                store.apply(revoked);
                for(var row:rec.recommend(RecommendationTest.request(5),SearchTest.ALICE).path("items"))assertNotEquals(first,row.path("item").path("object"));
                for(var row:search.search(SearchTest.request(5),SearchTest.ALICE).path("items"))assertNotEquals(first,row.path("item").path("object"));
            }
        }
    }
}
