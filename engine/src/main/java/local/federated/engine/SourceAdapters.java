package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class SourceAdapters {
    private SourceAdapters() {}
    static final String COLUMNS="native_id,version,title,body,category,status,updated_at,acl_group,deleted";
    private static final Set<String> ROW_FIELDS=Set.of(COLUMNS.split(","));
    static String identifier(String s) {
        if(s==null||!s.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("INVALID_SQL_IDENTIFIER");
        return s;
    }
    public static ObjectNode map(JsonNode row,String domain,String source,boolean post) {
        Set<String> fields=new HashSet<>();row.fieldNames().forEachRemaining(fields::add);
        if(!row.isObject()||!ROW_FIELDS.containsAll(fields)||!fields.containsAll(ROW_FIELDS))throw new IllegalArgumentException("INVALID_SOURCE_ROW");
        if(!row.path("version").isIntegralNumber()||!row.path("deleted").isBoolean())throw new IllegalArgumentException("INVALID_SOURCE_ROW");
        for(String f:ROW_FIELDS)if(!Set.of("version","deleted").contains(f)&&!row.path(f).isTextual())throw new IllegalArgumentException("INVALID_SOURCE_ROW");
        var item=Contract.JSON.createObjectNode();
        item.putObject("object").put("domain",domain).put("source",source).put("nativeId",row.path("native_id").asText());
        item.set("version",row.get("version"));item.set("deleted",row.get("deleted"));item.set("updatedAt",row.get("updated_at"));item.put("type",post?"post":"record");
        if(row.path("deleted").asBoolean())return item;
        String body=row.path("body").asText();
        // Only source-supplied plain text/Markdown is supported; reject HTML instead of stripping it unsafely.
        if(post&&Pattern.compile("(?is)<\\s*/?\\s*[a-z!][^>]*>").matcher(body).find())throw new IllegalArgumentException("HTML_NOT_SUPPORTED");
        item.put("title",row.path("title").asText()).put("text",body).put("mediaType",post?"text/markdown":"application/vnd.engine.business-record+json");
        item.putObject("location").put("uri","engine-source://"+domain+"/"+source+"/"+row.path("native_id").asText()).put("rowKey",row.path("native_id").asText());
        String group=row.path("acl_group").asText();
        if(!group.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("INVALID_SOURCE_ACL");
        var acl=item.putObject("acl").put("public",false);acl.putArray("users");acl.putArray("groups").add(group);
        item.put("parseAllowed",true).put("language","mixed");
        item.putObject("fields").put("category",row.path("category").asText()).put("status",row.path("status").asText());
        return item;
    }
    public record MySqlConfig(String host,int port,String database,String view,String user,String password) {
        public MySqlConfig {
            if(host==null||!host.matches("[A-Za-z0-9.-]+")||port<1||port>65535)throw new IllegalArgumentException("INVALID_MYSQL_HOST");
            identifier(database);identifier(view);
            if(database.toLowerCase(Locale.ROOT).startsWith("community")||database.toLowerCase(Locale.ROOT).startsWith("forum")||Set.of("mysql","sys","performance_schema","information_schema").contains(database.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("FORBIDDEN_SOURCE_DATABASE");
            if(user==null||user.isBlank()||password==null||password.isBlank())throw new IllegalArgumentException("MISSING_SOURCE_CREDENTIALS");
        }
        // Never include credentials in a generated record toString or error output.
        @Override public String toString(){return "MySqlConfig[redacted]";}
    }
    public static void checkGrants(List<String> grants,String database,String view) {
        boolean select=false;
        String allowed=Pattern.quote("`"+database+"`.`"+view+"`");
        for(String grant:grants) {
            if(grant.matches("(?i)^GRANT USAGE ON \\*\\.\\* TO .+$")&&!grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION"))continue;
            if(grant.matches("(?i)^GRANT SELECT ON "+allowed+" TO .+$")&&!grant.toUpperCase(Locale.ROOT).contains("WITH GRANT OPTION")){select=true;continue;}
            throw new IllegalArgumentException("SOURCE_NOT_SELECT_ONLY");
        }
        if(!select)throw new IllegalArgumentException("SOURCE_NOT_SELECT_ONLY");
    }
    public static void mysql(MySqlConfig config,Consumer<JsonNode> consumer)throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url="jdbc:mysql://"+config.host()+":"+config.port()+"/"+config.database()+"?sslMode=VERIFY_IDENTITY&allowLoadLocalInfile=false&autoDeserialize=false&allowMultiQueries=false&useCursorFetch=true&useServerPrepStmts=true&connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=30000";
        Properties properties=new Properties();properties.setProperty("user",config.user());properties.setProperty("password",config.password());
        try(var db=DriverManager.getConnection(url,properties)){readView(db,config.database(),config.view(),consumer);}
    }
    static void readView(Connection db,String database,String view,Consumer<JsonNode> consumer)throws Exception {
        identifier(database);identifier(view);
        db.setReadOnly(true);db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setAutoCommit(false);
        try {
            List<String> grants=new ArrayList<>();
            try(var s=db.createStatement();var r=s.executeQuery("SHOW GRANTS")){while(r.next())grants.add(r.getString(1));}
            checkGrants(grants,database,view);
            try(var p=db.prepareStatement("SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                p.setString(1,database);p.setString(2,view);
                try(var r=p.executeQuery()){if(!r.next()||!view.equals(r.getString(1)))throw new IllegalArgumentException("SOURCE_NOT_A_VIEW");}
            }
            // Full read-only replay avoids missing new v1 objects behind a per-object version watermark.
            try(var p=db.prepareStatement("SELECT "+COLUMNS+" FROM `"+database+"`.`"+view+"` ORDER BY native_id",ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
                p.setFetchSize(100);p.setQueryTimeout(30);
                try(var r=p.executeQuery()) {
                    while(r.next()) {
                        var row=Contract.JSON.createObjectNode();
                        for(String f:COLUMNS.split(",")) {
                            Object value=r.getObject(f);
                            if(value==null)throw new IllegalArgumentException("NULL_SOURCE_FIELD");
                            if(f.equals("version")) {
                                String raw=value.toString();if(!raw.matches("[1-9][0-9]{0,15}"))throw new IllegalArgumentException("INVALID_SOURCE_VERSION");
                                row.put(f,Long.parseLong(raw));
                            } else if(f.equals("deleted")) {
                                if(value instanceof Boolean b)row.put(f,b);
                                else if(value.toString().equals("0")||value.toString().equals("1"))row.put(f,value.toString().equals("1"));
                                else throw new IllegalArgumentException("INVALID_SOURCE_DELETED");
                            } else if(f.equals("updated_at")&&value instanceof Timestamp t)row.put(f,t.toInstant().toString());
                            else row.put(f,value.toString());
                        }
                        consumer.accept(row);
                    }
                }
            }
        } finally { db.rollback(); }
    }
}
