package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryTableTest {
    @TempDir Path temp;
    @Test void onlineRowsPreserveIdAndFailuresHideOldValuesUntilRetry()throws Exception {
        Path root=Files.createDirectory(temp.resolve("input"));Path file=root.resolve("people.csv");
        Files.writeString(file,"id,title,phone,total\n001,模拟部署人员,01001234,12.50\n002,模拟接入人员,020,10\n");
        var source=Contract.JSON.createObjectNode().put("sourceId","records").put("label","模拟台账").put("directory",root.toString()).put("parseAllowed",true).put("deleteMissing",true).put("scanIntervalSeconds",0);
        var acl=source.putObject("acl").put("public",false);acl.putArray("users");acl.putArray("groups").add("research");source.putObject("parseAs").put("user","parser").putArray("groups").add("research");
        var mapping=source.putObject("tableMapping").put("schemaVersion","1.0").put("idColumn","id").put("titleColumn","title");
        var fields=mapping.putObject("fields");fields.putObject("phone").put("column","phone").put("type","string");fields.putObject("amount").put("column","total").put("type","decimal");
        var settings=Contract.JSON.createObjectNode().put("schemaVersion","1.0");settings.putArray("sources").add(source);Path config=temp.resolve("sources.json");Files.write(config,Contract.JSON.writeValueAsBytes(settings));
        try(var store=new ItemStore(temp.resolve("db"));var imports=new DirectoryImports(store,config,temp.resolve("work"),"demo")) {
            var preview=imports.preview("records","people.csv");assertEquals(2,preview.path("total").asInt());assertTrue(store.projections("demo").isEmpty());
            assertThrows(IllegalArgumentException.class,()->imports.preview("records","../sources.json"));
            String run=imports.enqueue("records",null);imports.process(run);assertEquals("completed",imports.status(run,0).path("state").asText());
            String key="demo:records:"+Contract.digest(Contract.JSON.valueToTree(List.of("people.csv","001")));
            assertEquals("01001234",store.item(key).path("typedFields").path("phone").path("value").asText());assertFalse(store.projection(key).parts().get(0).path("text").asText().contains("01001234"));
            assertEquals("001",store.item(key).path("location").path("rowKey").asText());
            run=imports.enqueue("records",null);imports.process(run);assertEquals(1,store.item(key).path("version").asInt());
            Files.writeString(file,"id,title,phone,total\n001,模拟部署人员,01001234,bad\n");run=imports.enqueue("records",null);imports.process(run);
            assertEquals("completed_with_errors",imports.status(run,0).path("state").asText());assertFalse(store.projection(key).ready());
            Files.writeString(file,"id,title,phone,total\n001,模拟部署人员,01001234,15\n");String retry=imports.retry(run);imports.process(retry);assertTrue(store.projection(key).ready());
            String gone="demo:records:"+Contract.digest(Contract.JSON.valueToTree(List.of("people.csv","002")));assertTrue(store.item(gone).path("deleted").asBoolean());
            var entries=imports.status(retry,0).path("entries");boolean located=false;
            for(var entry:entries)if(entry.path("state").asText().equals("deleted")){assertEquals(gone,entry.path("objectKey").asText());located=true;}
            assertTrue(located);
            source.put("parseAllowed",false);Files.write(config,Contract.JSON.writeValueAsBytes(settings));run=imports.enqueue("records",null);imports.process(run);assertFalse(store.projection(key).ready());
        }
    }
}
