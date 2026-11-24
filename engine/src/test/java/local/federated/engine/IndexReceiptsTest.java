package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;

class IndexReceiptsTest {
    @TempDir Path temp;
    @Test void failedBulkCannotSurviveAsSuccessAcrossDatabaseReopen()throws Exception {
        try(var index=new SearchTest.IndexFixture()){
            try(var store=new ItemStore(temp)){
                store.apply(IngestionTest.item("a",1));
                try(var search=new SearchService(store,index.client(),null,"demo",.4)){search.sync();}
                store.apply(IngestionTest.item("a",2).put("text","new text"));index.failBulk=true;
                try(var search=new SearchService(store,index.client(),null,"demo",.4)){
                    assertThrows(java.io.IOException.class,search::sync);
                }
            }
            index.failBulk=false;
            try(var store=new ItemStore(temp);var search=new SearchService(store,index.client(),null,"demo",.4)){
                search.sync();assertEquals(1,search.runtimeStatus().path("keywordObjects").asInt());
                assertEquals("new text",index.docs.values().iterator().next().path("text").asText());
                int writes=index.writes;search.sync();assertEquals(writes,index.writes);
                store.apply(IngestionTest.item("a",3).put("deleted",true));search.sync();assertTrue(index.docs.isEmpty());
            }
            try(var store=new ItemStore(temp);var search=new SearchService(store,index.client(),null,"demo",.4)){
                search.sync();assertTrue(index.docs.isEmpty());
            }
        }
    }
    @Test void oldGuardAndDifferentIndexCannotAcknowledgeCurrentObject()throws Exception {
        try(var store=new ItemStore(temp)){
            store.apply(IngestionTest.item("a",1));var old=store.projection("demo:posts:a");
            var receipts=new IndexReceipts(store);
            assertTrue(receipts.acknowledge("index-one",old,true));
            var keywords=new HashMap<String,String>();var vectors=new HashMap<String,String>();
            receipts.load("index-two",keywords,vectors);assertTrue(keywords.isEmpty());
            store.apply(IngestionTest.item("a",2));assertFalse(receipts.acknowledge("index-one",old,true));
            receipts.forget("index-one",old.key());receipts.load("index-one",keywords,vectors);assertTrue(keywords.isEmpty());
        }
    }
    @Test void mixedBulkFailureRetriesWholeUnacknowledgedGroup()throws Exception {
        try(var store=new ItemStore(temp);var index=new SearchTest.IndexFixture()){
            for(int i=0;i<30;i++)store.apply(IngestionTest.item("batch-"+i,1));
            index.failBulkAt=1;
            try(var search=new SearchService(store,index.client(),null,"demo",.4)){assertThrows(java.io.IOException.class,search::sync);}
            assertEquals(24,index.docs.size(),"partial writes exist but must not be acknowledged");
            index.failBulkAt=-1;
            try(var search=new SearchService(store,index.client(),null,"demo",.4)){
                search.sync();assertEquals(30,index.docs.size());assertEquals(30,search.runtimeStatus().path("keywordObjects").asInt());
            }
        }
    }
}
