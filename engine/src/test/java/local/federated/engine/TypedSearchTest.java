package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TypedSearchTest {
    @TempDir Path temp;
    @Test void exactConditionsPreserveZerosDecimalsAndCurrentPermissions()throws Exception {
        try(var store=new ItemStore(temp.resolve("db"));var backend=new SearchTest.IndexFixture();var search=new SearchService(store,backend.client(),null,"demo",0.4)) {
            for(int i=1;i<=3;i++) {
                var item=IngestionTest.item("r"+i,1).put("schemaVersion","1.1").put("type","record").put("text","deployment");
                var fields=item.putObject("typedFields");fields.putObject("phone").put("type","string").put("value",i==1?"010":"10");
                fields.putObject("amount").put("type","decimal").put("value",i==3?"9007199254740993.01":"12.5");
                assertEquals("applied",store.apply(item).status());
            }
            search.sync();var query=Contract.JSON.createObjectNode().put("schemaVersion","1.1").put("query","").put("mode","keyword").put("pageSize",10);
            var clause=query.putObject("filters").putArray("typed").addObject().put("field","phone").put("type","string").put("op","eq").put("value","010");
            var first=search.search(query,SearchTest.ALICE);assertEquals("1.1",first.path("schemaVersion").asText());assertEquals(1,first.path("items").size());
            assertEquals("r1",first.path("items").get(0).path("item").path("object").path("nativeId").asText());
            assertEquals("010",first.path("items").get(0).path("item").path("typedFields").path("phone").path("value").asText());
            clause.put("field","amount").put("type","decimal").put("op","gte").put("value","9007199254740993.00");
            assertEquals("r3",search.search(query,SearchTest.ALICE).path("items").get(0).path("item").path("object").path("nativeId").asText());
            clause.put("field","unknown");assertEquals("UNKNOWN_TYPED_FIELD",assertThrows(IllegalArgumentException.class,()->search.search(query,SearchTest.ALICE)).getMessage());
            clause.put("field","amount").put("type","string").put("op","eq");assertEquals("FIELD_TYPE_MISMATCH",assertThrows(IllegalArgumentException.class,()->search.search(query,SearchTest.ALICE)).getMessage());
            clause.put("field","phone").put("value","010");
            // Item fixture source is resolved from its actual object, not a guessed ID.
            String key=Contract.key(IngestionTest.item("r1",1).path("object"));var revoked=(com.fasterxml.jackson.databind.node.ObjectNode)store.item(key).deepCopy();
            revoked.put("version",2);revoked.withObject("acl").putArray("groups");store.apply(revoked);
            assertTrue(search.search(query,SearchTest.ALICE).path("items").isEmpty());
            var legacy=search.search(SearchTest.request(10),SearchTest.ALICE);assertEquals("1.0",legacy.path("schemaVersion").asText());
            for(var hit:legacy.path("items"))assertFalse(hit.path("item").has("typedFields"));
        }
    }
}
