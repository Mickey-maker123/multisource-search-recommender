package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One embedded database, no network listener or source-system writes. */
public final class ItemStore implements AutoCloseable {
    private final Connection db;
    private final Contract contract = new Contract();
    private final ProjectionCatalog catalog;
    public record Outcome(String status, String code) {}
    public record Ticket(String id, String key, long version, String guard) {}
    public record Principal(String user, Set<String> groups) {}
    Connection connection() { return db; }

    /** Aggregate operational states only; never expose payloads, paths or identities. */
    public synchronized ObjectNode runtimeStatus() throws SQLException {
        var result = Contract.JSON.createObjectNode().put("state", "available");
        for (String table : List.of("items", "jobs", "import_runs", "directory_runs")) {
            var counts = result.putObject(table);
            try (var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT state, COUNT(*) FROM " + table + " GROUP BY state")) {
                while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
            }
        }
        return result;
    }

    public ItemStore(Path directory) throws SQLException, IOException {
        Files.createDirectories(directory);
        // Never accept JDBC URLs, semicolon options or a caller-selected database name.
        String file = directory.toRealPath().resolve("engine-v01").toString();
        if (file.contains(";") || file.contains("\n")) throw new IllegalArgumentException("INVALID_DATA_PATH");
        db = DriverManager.getConnection("jdbc:h2:file:"+file+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
        try (var s = db.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS items (item_key VARCHAR(194) PRIMARY KEY, version BIGINT NOT NULL, digest VARCHAR(64) NOT NULL, payload CLOB NOT NULL, state VARCHAR(32) NOT NULL, code VARCHAR(64), derived CLOB, file_digest VARCHAR(64))");
            s.execute("CREATE TABLE IF NOT EXISTS jobs (job_id VARCHAR(36) PRIMARY KEY, item_key VARCHAR(194) NOT NULL, version BIGINT NOT NULL, guard VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL, code VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS import_runs (run_id VARCHAR(36) PRIMARY KEY, mode VARCHAR(32) NOT NULL, state VARCHAR(32) NOT NULL, summary CLOB)");
            s.execute("CREATE TABLE IF NOT EXISTS directory_runs (run_id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL, code VARCHAR(64) NOT NULL, config_guard VARCHAR(64) NOT NULL, previous_id VARCHAR(36), scan_complete BOOLEAN DEFAULT FALSE, total INT DEFAULT 0, processed INT DEFAULT 0, errors INT DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS directory_entries (run_id VARCHAR(36) NOT NULL, seq INT NOT NULL, relative_path VARCHAR(1024) NOT NULL, item_key VARCHAR(194), state VARCHAR(32) NOT NULL, code VARCHAR(64) NOT NULL, PRIMARY KEY(run_id,seq))");
            s.execute("CREATE TABLE IF NOT EXISTS directory_files (source_id VARCHAR(64) NOT NULL, relative_path VARCHAR(1024) NOT NULL, item_key VARCHAR(194) NOT NULL, item_version BIGINT NOT NULL, item_digest VARCHAR(64) NOT NULL, fingerprint VARCHAR(64) NOT NULL, PRIMARY KEY(source_id,relative_path))");
            s.execute("CREATE TABLE IF NOT EXISTS embedding_cache (cache_id VARCHAR(64) PRIMARY KEY, item_key VARCHAR(194) NOT NULL, vector CLOB NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE INDEX IF NOT EXISTS embedding_cache_item ON embedding_cache(item_key)");
            s.execute("CREATE TABLE IF NOT EXISTS item_parents (item_key VARCHAR(194) PRIMARY KEY,parent_key VARCHAR(194))");
            s.execute("CREATE INDEX IF NOT EXISTS item_parents_parent ON item_parents(parent_key)");
            try(var missing=db.prepareStatement("SELECT item_key,payload FROM items WHERE item_key NOT IN (SELECT item_key FROM item_parents)");var rows=missing.executeQuery()){
                while(rows.next())updateParent(rows.getString(1),Contract.JSON.readTree(rows.getString(2)));
            }
            catalog=new ProjectionCatalog(this);
            s.executeUpdate("UPDATE directory_runs SET state='failed',code='PROCESS_INTERRUPTED' WHERE state='running'");
            s.executeUpdate("UPDATE import_runs SET state='failed' WHERE state='running'");
            // A killed parser cannot leave a permanent running job after reopening the store.
            s.executeUpdate("UPDATE jobs SET state='failed', code='PROCESS_INTERRUPTED' WHERE state='running'");
            s.executeUpdate("UPDATE items SET state='failed', code='PROCESS_INTERRUPTED' WHERE state='parsing'");
        }
    }
    public static Path dataDirectory(Path home) throws IOException {
        Path root = home.toRealPath();
        Path path = root.resolve(".local/engine-v01");
        for (Path p = path; p != null && !p.equals(root); p = p.getParent())
            if (Files.exists(p) && (Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root)))
                throw new IllegalArgumentException("UNSAFE_DATA_DIRECTORY");
        return path;
    }
    private record DirectoryBinding(String source, String relative, String fingerprint) {}
    public synchronized Outcome apply(JsonNode input) throws SQLException, IOException {
        return apply(input,null);
    }
    synchronized Outcome applyDirectory(JsonNode input,String source,String relative,String fingerprint) throws SQLException,IOException {
        return apply(input,new DirectoryBinding(source,relative,fingerprint));
    }
    private Outcome apply(JsonNode input,DirectoryBinding binding) throws SQLException, IOException {
        try {
            contract.validateItem(input);
            String key = Contract.key(input.path("object"));
            long version = input.path("version").asLong();
            String digest = Contract.digest(input);
            JsonNode previous = row(key);
            if (previous != null) {
                long current = previous.path("version").asLong();
                if (version < current) return new Outcome("stale", "OLDER_VERSION");
                if (version == current) return previous.path("digest").asText().equals(digest)
                    ? new Outcome("duplicate", "SAME_VERSION") : new Outcome("rejected", "VERSION_CONFLICT");
            }
            checkReferences(input, key);
            ObjectNode payload = input.deepCopy();
            if (input.path("deleted").asBoolean()) {
                payload.removeAll();
                for (String f : List.of("object", "version", "type", "deleted", "updatedAt")) payload.set(f, input.get(f));
            }
            db.setAutoCommit(false);
            try (var p = db.prepareStatement("MERGE INTO items (item_key,version,digest,payload,state,code,derived,file_digest) KEY(item_key) VALUES (?,?,?,?,?,NULL,NULL,NULL)")) {
                p.setString(1,key); p.setLong(2,version); p.setString(3,digest); p.setString(4,Contract.JSON.writeValueAsString(payload));
                p.setString(5,input.path("deleted").asBoolean() ? "deleted" : input.path("text").asText().isBlank() ? "pending" : "ready");
                p.executeUpdate();
                updateParent(key,payload);
                invalidateDescendants(key);
                if(binding!=null)try(var owner=db.prepareStatement("MERGE INTO directory_files (source_id,relative_path,item_key,item_version,item_digest,fingerprint) KEY(source_id,relative_path) VALUES (?,?,?,?,?,?)")) {
                    owner.setString(1,binding.source());owner.setString(2,binding.relative());owner.setString(3,key);owner.setLong(4,version);
                    owner.setString(5,Contract.digest(payload));owner.setString(6,binding.fingerprint());owner.executeUpdate();
                }
                db.commit();
            } catch (SQLException | IOException | RuntimeException e) { db.rollback(); throw e; }
            finally { db.setAutoCommit(true); }
            return new Outcome("applied", "OK");
        } catch (IllegalArgumentException e) { return new Outcome("rejected", e.getMessage()!=null&&e.getMessage().matches("[A-Z_]{1,64}")?e.getMessage():"INVALID_ITEM"); }
    }
    private void checkReferences(JsonNode value, String key) throws SQLException, IOException {
        String domain = value.path("object").path("domain").asText();
        if (value.has("parent")) {
            JsonNode ref = value.get("parent"); Set<String> seen = new HashSet<>(Set.of(key));
            while (true) {
                if (!domain.equals(ref.path("domain").asText())) throw new IllegalArgumentException("CROSS_DOMAIN_PARENT");
                String parentKey = Contract.key(ref);
                if (!seen.add(parentKey)) throw new IllegalArgumentException("PARENT_CYCLE");
                JsonNode parent = item(parentKey);
                if (parent == null || parent.path("deleted").asBoolean()) throw new IllegalArgumentException("PARENT_UNAVAILABLE");
                if (!parent.has("parent")) break;
                ref = parent.get("parent");
            }
        }
        for (var relation : value.path("relations"))
            if (!domain.equals(relation.path("target").path("domain").asText())) throw new IllegalArgumentException("CROSS_DOMAIN_RELATION");
    }
    private void invalidateDescendants(String key) throws SQLException, IOException {
        Set<String> affected = new HashSet<>(Set.of(key));
        Deque<String> pending=new ArrayDeque<>(List.of(key));
        while(!pending.isEmpty())try(var p=db.prepareStatement("SELECT item_key FROM item_parents WHERE parent_key=?")){
            p.setString(1,pending.removeFirst());try(var rows=p.executeQuery()){
                while(rows.next()){String child=rows.getString(1);if(affected.add(child))pending.addLast(child);}
            }
        }
        for (var child : affected) {
            catalog.dirty(child);
            try(var p=db.prepareStatement("DELETE FROM embedding_cache WHERE item_key=?")){p.setString(1,child);p.executeUpdate();}
            if (!child.equals(key)) try (var p=db.prepareStatement("UPDATE items SET derived=NULL, state=CASE WHEN state='deleted' THEN state ELSE 'pending' END, code='ANCESTOR_CHANGED' WHERE item_key=?")) {
                p.setString(1,child); p.executeUpdate();
            }
            try (var p=db.prepareStatement("UPDATE jobs SET state='superseded',code='SOURCE_CHANGED' WHERE item_key=? AND state='running'")) { p.setString(1,child);p.executeUpdate(); }
        }
    }
    private void updateParent(String key,JsonNode item)throws SQLException {
        try(var p=db.prepareStatement("MERGE INTO item_parents (item_key,parent_key) KEY(item_key) VALUES (?,?)")){
            p.setString(1,key);p.setString(2,item.has("parent")?Contract.key(item.path("parent")):null);p.executeUpdate();
        }
    }
    private JsonNode row(String key) throws SQLException, IOException {
        try(var p=db.prepareStatement("SELECT version,digest,payload,state,code,derived FROM items WHERE item_key=?")) {
            p.setString(1,key);try(var r=p.executeQuery()) {
                if(!r.next()) return null;
                var out=Contract.JSON.createObjectNode().put("version",r.getLong(1)).put("digest",r.getString(2)).put("state",r.getString(4)).put("code",r.getString(5));
                out.set("item",Contract.JSON.readTree(r.getString(3)));
                if(r.getString(6)!=null) out.set("derived",Contract.JSON.readTree(r.getString(6)));
                return out;
            }
        }
    }
    public synchronized JsonNode status(String key) throws SQLException, IOException {
        ObjectNode row=(ObjectNode)row(key);if(row==null)return null;
        var jobs=row.putArray("jobs");
        try(var p=db.prepareStatement("SELECT job_id,version,state,code FROM jobs WHERE item_key=? ORDER BY created_at DESC LIMIT 20")){
            p.setString(1,key);try(var r=p.executeQuery()){while(r.next())jobs.addObject().put("jobId",r.getString(1)).put("version",r.getLong(2)).put("state",r.getString(3)).put("code",r.getString(4));}
        }return row;
    }
    public synchronized JsonNode item(String key) throws SQLException, IOException { var r=row(key); return r==null?null:r.get("item"); }
    public record Projection(String key,JsonNode item,String guard,List<JsonNode> parts,boolean ready) {}
    public synchronized Projection projection(String key)throws SQLException,IOException {
        var row=row(key);if(row==null)return null;
        var item=row.path("item");boolean ready="ready".equals(row.path("state").asText())&&!item.path("deleted").asBoolean();
        var signature=Contract.JSON.createObjectNode();signature.set("row",row);
        if(ready)try{signature.put("ancestors",guard(key));}catch(IllegalArgumentException e){ready=false;}
        List<JsonNode> parts=new ArrayList<>();
        if(ready){
            if(row.path("derived").isArray())row.path("derived").forEach(parts::add);
            else if(!item.path("text").asText().isBlank()){var part=Contract.JSON.createObjectNode();part.set("text",item.get("text"));part.set("location",item.get("location"));parts.add(part);}
        }
        return new Projection(key,item,Contract.digest(signature),List.copyOf(parts),ready&&!parts.isEmpty());
    }
    public synchronized List<Projection> projections(String domain)throws SQLException,IOException {
        List<Projection> out=new ArrayList<>();
        for(var item:allItems())if(domain.equals(item.path("object").path("domain").asText()))out.add(projection(Contract.key(item.path("object"))));
        return out;
    }
    public synchronized List<Projection> metadata(String domain)throws SQLException,IOException {
        return catalog.entries().stream().filter(p->domain.equals(p.item().path("object").path("domain").asText())).toList();
    }
    synchronized void markProjectionDirty(String key)throws SQLException {catalog.dirty(key);}
    synchronized Projection metadataItem(String key)throws SQLException,IOException {return catalog.get(key);}
    synchronized long metadataRevision(){return catalog.revision();}
    synchronized List<Projection> shortlist(String domain,Set<String> terms,Set<String> seeds,
            ProjectionCatalog.Eligibility eligible,java.util.function.ToDoubleFunction<Projection> score,int limit)throws SQLException,IOException {
        return catalog.shortlist(domain,terms,seeds,eligible,score,limit);
    }
    synchronized List<JsonNode> accessScopes(String key)throws SQLException,IOException {
        List<JsonNode> scopes=new ArrayList<>();
        for(var item:chain(key))scopes.add(item.path("acl").deepCopy());
        return List.copyOf(scopes);
    }
    public synchronized List<JsonNode> allItems() throws SQLException, IOException {
        List<JsonNode> out=new ArrayList<>();
        try(var p=db.prepareStatement("SELECT payload FROM items ORDER BY item_key");var r=p.executeQuery()) {while(r.next()) out.add(Contract.JSON.readTree(r.getString(1)));}return out;
    }
    private List<JsonNode> chain(String key) throws SQLException, IOException {
        List<JsonNode> chain=new ArrayList<>();Set<String> seen=new HashSet<>();
        while(true) {
            if(!seen.add(key)) throw new IllegalArgumentException("PARENT_CYCLE");
            var item=item(key);
            if(item==null||item.path("deleted").asBoolean()) throw new IllegalArgumentException("OBJECT_UNAVAILABLE");
            chain.add(item); if(!item.has("parent")) return chain;key=Contract.key(item.get("parent"));
        }
    }
    public synchronized boolean canAccess(String key, Principal principal, boolean parsing) throws SQLException, IOException {
        if(principal==null || principal.user()==null || principal.user().isBlank()) return false;
        try {
            Set<String> seen=new HashSet<>();
            while(true) {
                if(!seen.add(key))return false;
                var projection=catalog.get(key);
                if(projection==null||projection.item().path("deleted").asBoolean())return false;
                var item=projection.item();
                var acl=item.path("acl");boolean allowed=acl.path("public").asBoolean();
                for(var u:acl.path("users")) allowed|=u.asText().equals(principal.user());
                for(var g:acl.path("groups")) allowed|=principal.groups().contains(g.asText());
                if(!allowed || parsing&&!item.path("parseAllowed").asBoolean()) return false;
                if(!item.has("parent"))break;
                key=Contract.key(item.path("parent"));
            }
            return true;
        }catch(IllegalArgumentException e){return false;}
    }
    synchronized ObjectNode visibleItem(String key, Principal principal) throws SQLException, IOException {
        return visibleItem(key,principal,false);
    }
    synchronized ObjectNode visibleItem(String key, Principal principal,boolean extended) throws SQLException, IOException {
        if (!canAccess(key, principal, false)) return null;
        ObjectNode visible = item(key).deepCopy();
        // Legacy readers receive the extracted-text representation, not new format metadata.
        if(!extended&&visible.has("schemaVersion")) {
            visible.remove("schemaVersion");
            visible.remove("typedFields");
            if(visible.path("mediaType").asText().equals("text/html"))visible.put("mediaType","text/plain");
        }
        if (visible.has("relations")) {
            var relations = visible.putArray("relations");
            for (var relation : item(key).path("relations")) {
                var target = relation.path("target");
                if (visible.path("object").path("domain").asText().equals(target.path("domain").asText())
                        && canAccess(Contract.key(target), principal, false)) relations.add(relation);
            }
        }
        return visible;
    }

    private String guard(String key) throws SQLException, IOException {
        var chain=Contract.JSON.createArrayNode();
        for(var item:chain(key)) chain.add(Contract.digest(item));
        return Contract.digest(chain);
    }
    public synchronized Ticket begin(String key, Principal principal) throws SQLException, IOException {
        if(!canAccess(key,principal,true)) {
            try(var p=db.prepareStatement("INSERT INTO jobs (job_id,item_key,version,guard,state,code) VALUES (?,?,0,'','rejected','PARSE_FORBIDDEN')")){p.setString(1,UUID.randomUUID().toString());p.setString(2,key);p.executeUpdate();}
            throw new IllegalArgumentException("PARSE_FORBIDDEN");
        }
        String id=UUID.randomUUID().toString();long v=item(key).path("version").asLong();String guard=guard(key);
        try(var p=db.prepareStatement("UPDATE jobs SET state='superseded',code='NEW_ATTEMPT' WHERE item_key=? AND state='running'")){p.setString(1,key);p.executeUpdate();}
        try(var p=db.prepareStatement("INSERT INTO jobs (job_id,item_key,version,guard,state,code) VALUES (?,?,?,?,'running',NULL)")) {
            p.setString(1,id);p.setString(2,key);p.setLong(3,v);p.setString(4,guard);p.executeUpdate();
        }
        catalog.dirty(key);
        try(var p=db.prepareStatement("UPDATE items SET state='parsing',code=NULL,derived=NULL WHERE item_key=?")){p.setString(1,key);p.executeUpdate();}
        return new Ticket(id,key,v,guard);
    }
    public synchronized void bindFile(Ticket ticket,byte[] bytes)throws Exception {
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        try(var p=db.prepareStatement("SELECT version,file_digest FROM items WHERE item_key=?")) {
            p.setString(1,ticket.key());try(var r=p.executeQuery()) {
                if(!r.next()||r.getLong(1)!=ticket.version())throw new IllegalArgumentException("SOURCE_CHANGED");
                if(r.getString(2)!=null&&!hash.equals(r.getString(2)))throw new IllegalArgumentException("FILE_VERSION_CONFLICT");
            }
        }
        try(var p=db.prepareStatement("UPDATE items SET file_digest=? WHERE item_key=? AND version=?")){p.setString(1,hash);p.setString(2,ticket.key());p.setLong(3,ticket.version());p.executeUpdate();}
    }
    public synchronized boolean finish(Ticket ticket, Principal principal, String state, String code, JsonNode parts) throws SQLException, IOException {
        if(!Set.of("ready","failed","rejected","needs_ocr").contains(state)) throw new IllegalArgumentException("INVALID_JOB_STATE");
        boolean current=false;
        try(var p=db.prepareStatement("SELECT state FROM jobs WHERE job_id=?")){p.setString(1,ticket.id());try(var r=p.executeQuery()){current=r.next()&&r.getString(1).equals("running");}}
        current=current&&canAccess(ticket.key(),principal,true)&&guard(ticket.key()).equals(ticket.guard());
        if(!current){state="superseded";code="SOURCE_CHANGED";}
        db.setAutoCommit(false);
        try {
            if(current)catalog.dirty(ticket.key());
            if(current) try(var p=db.prepareStatement("UPDATE items SET state=?,code=?,derived=? WHERE item_key=? AND version=?")) {
                p.setString(1,state);p.setString(2,code);p.setString(3,parts==null?null:Contract.JSON.writeValueAsString(parts));p.setString(4,ticket.key());p.setLong(5,ticket.version());p.executeUpdate();
            }
            try(var p=db.prepareStatement("UPDATE jobs SET state=?,code=? WHERE job_id=?")){p.setString(1,state);p.setString(2,code);p.setString(3,ticket.id());p.executeUpdate();}
            db.commit();
        }catch(SQLException | IOException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
        return current;
    }
    public synchronized String beginRun(String mode)throws SQLException {
        String id=UUID.randomUUID().toString();
        try(var p=db.prepareStatement("INSERT INTO import_runs VALUES (?,?,'running',NULL)")){p.setString(1,id);p.setString(2,mode);p.executeUpdate();}return id;
    }
    public synchronized void finishRun(String id,String state,JsonNode summary)throws SQLException,IOException {
        try(var p=db.prepareStatement("UPDATE import_runs SET state=?,summary=? WHERE run_id=?")){p.setString(1,state);p.setString(2,Contract.JSON.writeValueAsString(summary));p.setString(3,id);p.executeUpdate();}
    }
    public synchronized JsonNode runStatus(String id)throws SQLException,IOException {
        try(var p=db.prepareStatement("SELECT state,summary FROM import_runs WHERE run_id=?")){p.setString(1,id);try(var r=p.executeQuery()){
            if(!r.next())return null;var out=Contract.JSON.createObjectNode().put("state",r.getString(1));
            if(r.getString(2)!=null)out.set("summary",Contract.JSON.readTree(r.getString(2)));return out;
        }}
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
