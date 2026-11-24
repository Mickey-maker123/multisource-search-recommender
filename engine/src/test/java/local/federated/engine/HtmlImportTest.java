package local.federated.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlImportTest {
    @TempDir Path temp;
    @Test void cleansHtmlKeepsTextExactlyOnceAndNeverFollowsResources() throws Exception {
        Path file=temp.resolve("manual.html");
        Files.writeString(file,"<html><head><title>工具名称 Jsoup</title><style>badcss</style></head><body><nav>navnoise</nav><h1>内网部署</h1><div><p>配置 <b>ES</b> &amp; 模型</p><p>第二段😀</p></div><script>badscript</script><div hidden>hiddennoise</div><img src='http://127.0.0.1:1/private'><a href='http://127.0.0.1:1/x'>资源链接</a></body></html>");
        var parts=new ParserWorker().parse(file,"text/html");
        assertEquals("工具名称 Jsoup",parts.get(0).path("text").asText());
        assertEquals("内网部署",parts.get(1).path("text").asText());
        StringBuilder text=new StringBuilder();int pos=0;
        for(var part:parts){text.append(part.path("text").asText());assertTrue(part.path("location").path("paragraph").asInt()>pos);pos=part.path("location").path("paragraph").asInt();}
        String body=text.toString();assertTrue(body.contains("内网部署"));assertTrue(body.contains("配置 ES & 模型"));
        assertEquals(body.indexOf("第二段"),body.lastIndexOf("第二段"));assertTrue(body.contains("资源链接"));
        for(String forbidden:new String[]{"navnoise","badcss","badscript","hiddennoise","http://"})assertFalse(body.contains(forbidden));
    }
    @Test void emptyAndMalformedHtmlHaveExplicitFailures() throws Exception {
        Path file=temp.resolve("empty.html");Files.writeString(file,"<script>only executable code</script>");
        assertEquals("EMPTY_TEXT",assertThrows(IllegalArgumentException.class,()->new ParserWorker().parse(file,"text/html")).getMessage());
        Files.write(file,new byte[]{(byte)0xff});
        assertEquals("INVALID_ENCODING",assertThrows(IllegalArgumentException.class,()->new ParserWorker().parse(file,"text/html")).getMessage());
    }
    @Test void versionedHtmlTravelsThroughBoundedFileWorker() throws Exception {
        var item=(com.fasterxml.jackson.databind.node.ObjectNode)Contract.JSON.readTree(Path.of("../fixtures/d3/items.json").toFile()).path("items").get(0).deepCopy();
        item.put("type","document").put("mediaType","text/html").put("text","").put("schemaVersion","1.1");
        item.remove("parent");item.put("parseAllowed",true);item.putObject("acl").put("public",true).putArray("users");item.withObject("acl").putArray("groups");
        var contract=new Contract();contract.validateItem(item);
        var legacy=item.deepCopy();legacy.remove("schemaVersion");assertThrows(IllegalArgumentException.class,()->contract.validateItem(legacy));
        Files.writeString(temp.resolve("manual.htm"),"<h1>在线正文</h1><p>可追溯段落</p>");
        try(var store=new ItemStore(temp.resolve("db"))) {
            assertEquals("applied",store.apply(item).status());String key=Contract.key(item.path("object"));
            assertEquals("ready",new FileImporter(store,temp,temp.resolve("work"),new ItemStore.Principal("parser",java.util.Set.of())).parse(key,"manual.htm"));
            assertTrue(store.projection(key).ready());assertEquals(1,store.projection(key).parts().get(0).path("location").path("paragraph").asInt());
            var view=store.visibleItem(key,new ItemStore.Principal("parser",java.util.Set.of()));
            assertFalse(view.has("schemaVersion"));assertEquals("text/plain",view.path("mediaType").asText());contract.validateItem(view);
        }
    }
}
