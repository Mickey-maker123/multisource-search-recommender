package local.federated.engine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;

class SourceAdaptersTest {
    @TempDir Path temp;
    @Test void businessAndPostMappingAndSoftDelete()throws Exception {
        var row=Contract.JSON.readTree(Files.readAllBytes(Path.of("../fixtures/d1/business-view.json"))).get(0);
        try(var store=new ItemStore(temp.resolve("db"))) {
            assertEquals("applied",store.apply(SourceAdapters.map(row,"demo","business",false)).status());
            assertEquals("duplicate",store.apply(SourceAdapters.map(row,"demo","business",false)).status());
            assertEquals("applied",store.apply(SourceAdapters.map(row,"demo","posts",true)).status());
            var deleted=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();deleted.put("version",2).put("deleted",true);
            assertEquals("applied",store.apply(SourceAdapters.map(deleted,"demo","business",false)).status());
            assertFalse(store.item("demo:business:case-001").has("text"));
            var html=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();html.put("body","<script>bad</script>");
            assertThrows(IllegalArgumentException.class,()->SourceAdapters.map(html,"demo","posts",true));
            var comment=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();comment.put("commentText","private");
            assertThrows(IllegalArgumentException.class,()->SourceAdapters.map(comment,"demo","posts",true));
            var missing=(com.fasterxml.jackson.databind.node.ObjectNode)row.deepCopy();missing.remove("version");
            assertThrows(IllegalArgumentException.class,()->SourceAdapters.map(missing,"demo","business",false));
        }
    }
    @Test void sourceCredentialsIdentifiersAndGrantScopesFailClosed() {
        assertDoesNotThrow(()->SourceAdapters.checkGrants(List.of("GRANT USAGE ON *.* TO 'reader'@'localhost'","GRANT SELECT ON `engine_source_v01`.`cases_v1` TO 'reader'@'localhost'"),"engine_source_v01","cases_v1"));
        for(var grant:List.of("GRANT ALL PRIVILEGES ON *.* TO 'reader'@'localhost'","GRANT SELECT, INSERT ON `engine_source_v01`.`cases_v1` TO 'reader'@'localhost'","GRANT SELECT ON *.* TO 'reader'@'localhost'","GRANT SELECT ON `engine_source_v01`.* TO 'reader'@'localhost'","GRANT SELECT ON `engine_source_v01`.`cases_v1` TO 'reader'@'localhost' WITH GRANT OPTION","GRANT `role`@`%` TO 'reader'@'localhost'"))
            assertThrows(IllegalArgumentException.class,()->SourceAdapters.checkGrants(List.of(grant),"engine_source_v01","cases_v1"));
        assertThrows(IllegalArgumentException.class,()->SourceAdapters.checkGrants(List.of(),"engine_source_v01","cases_v1"));
        assertThrows(IllegalArgumentException.class,()->new SourceAdapters.MySqlConfig("localhost",3306,"community_lzh_week3","view","reader","not-a-real-password"));
        assertThrows(IllegalArgumentException.class,()->new SourceAdapters.MySqlConfig("localhost",3306,"engine_source_v01","view;DROP TABLE x","reader","test"));
        assertThrows(IllegalArgumentException.class,()->new SourceAdapters.MySqlConfig("localhost?allowMultiQueries=true",3306,"engine_source_v01","view","reader","test"));
        assertFalse(new SourceAdapters.MySqlConfig("localhost",3306,"engine_source_v01","view","reader","test-secret").toString().contains("test-secret"));
    }
    @Test void jdbcViewPipelineReadsOnlySelectAndActuallyImportsRows()throws Exception {
        // This is a JDBC/H2 fixture with mocked SHOW GRANTS, not a real MySQL service test.
        try(var source=DriverManager.getConnection("jdbc:h2:mem:source;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            try(var s=source.createStatement()) {
                s.execute("CREATE SCHEMA engine_source_v01");
                s.execute("CREATE TABLE engine_source_v01.rows (native_id VARCHAR PRIMARY KEY,version BIGINT,title VARCHAR,body VARCHAR,category VARCHAR,status VARCHAR,updated_at TIMESTAMP,acl_group VARCHAR,deleted BOOLEAN)");
                s.execute("INSERT INTO engine_source_v01.rows VALUES ('case-001',1,'mock title','mock body','demo','open',TIMESTAMP '2026-11-06 00:00:00','research',FALSE)");
                s.execute("CREATE VIEW engine_source_v01.cases_v1 AS SELECT * FROM engine_source_v01.rows");
            }
            List<String> statements=new ArrayList<>();boolean[] readonly={false};
            Connection wrapped=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("setReadOnly"))readonly[0]=(boolean)args[0];
                if(method.getName().equals("prepareStatement")) {
                    String sql=(String)args[0];statements.add(sql);assertTrue(sql.startsWith("SELECT "));
                }
                if(method.getName().equals("createStatement"))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                    if(m.getName().equals("executeQuery")&&a[0].equals("SHOW GRANTS")) {
                        statements.add("SHOW GRANTS");
                        return source.createStatement().executeQuery("SELECT 'GRANT SELECT ON `engine_source_v01`.`cases_v1` TO reader'");
                    }
                    if(m.getName().equals("close"))return null;
                    throw new AssertionError("Unexpected SQL method");
                });
                try{return method.invoke(source,args);}catch(InvocationTargetException e){throw e.getCause();}
            });
            try(var store=new ItemStore(temp.resolve("db"))) {
                SourceAdapters.readView(wrapped,"engine_source_v01","cases_v1",row->{
                    try{assertEquals("applied",store.apply(SourceAdapters.map(row,"demo","business",false)).status());}
                    catch(Exception e){throw new RuntimeException(e);}
                });
                assertEquals("mock body",store.item("demo:business:case-001").path("text").asText());
            }
            assertTrue(readonly[0]);assertEquals(3,statements.size());
        }
    }
}
