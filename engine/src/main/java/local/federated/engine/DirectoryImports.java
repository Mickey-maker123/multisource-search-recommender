package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Durable, single-owner directory jobs sharing the live engine's H2 connection. */
final class DirectoryImports implements AutoCloseable {
    private static final Map<String,String> MEDIA = Map.of("txt", "text/plain", "md", "text/markdown", "html", "text/html", "htm", "text/html", "pdf", "application/pdf",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final ItemStore store;
    private final Path config, work;
    private final String domain;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private volatile String schedulerCode = "NOT_STARTED";
    private final Semaphore previewSlot = new Semaphore(1);
    private record Owned(String key, long version, String digest, String fingerprint) {}

    DirectoryImports(ItemStore store, Path config, Path work, String domain) throws Exception {
        this.store = store;this.config = config;this.work = work;this.domain = domain;
        DirectorySources.load(config);
        Files.createDirectories(work);
        synchronized(store) {try(var p=store.connection().prepareStatement("SELECT item_key FROM directory_files WHERE item_key NOT LIKE ? LIMIT 1")) {
            p.setString(1,domain+":%");try(var r=p.executeQuery()){if(r.next())throw new IllegalArgumentException("DIRECTORY_DOMAIN_MISMATCH");}
        }}
    }
    void start() { worker.scheduleWithFixedDelay(() -> {
        try { tick(); schedulerCode = "OK"; }
        catch (Exception e) { schedulerCode = "DIRECTORY_SCHEDULER_FAILED"; }
    }, 0, 1, TimeUnit.SECONDS); }
    @Override public void close() {
        worker.shutdownNow();
        try { if (!worker.awaitTermination(35, TimeUnit.SECONDS)) throw new IllegalStateException("IMPORT_WORKER_NOT_STOPPED"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("IMPORT_CLOSE_INTERRUPTED"); }
    }
    boolean manages(String source) throws Exception { return DirectorySources.load(config).containsKey(source); }
    ObjectNode sources() throws Exception {
        var result = Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("schedulerCode", schedulerCode);
        var rows = result.putArray("sources");
        for (var source : DirectorySources.load(config).values()) {
            var row=rows.addObject().put("sourceId", source.id()).put("label", source.label()).put("parseAllowed", source.parseAllowed())
                .put("deleteMissing", source.deleteMissing()).put("scanIntervalSeconds", source.interval()).put("maxFiles", source.maxFiles());
            synchronized(store) {try(var p=store.connection().prepareStatement("SELECT run_id FROM directory_runs WHERE source_id=? ORDER BY created_at DESC,run_id DESC LIMIT 1")) {
                p.setString(1,source.id());try(var r=p.executeQuery()){if(r.next())row.put("lastRunId",r.getString(1));}
            }}
        }
        return result;
    }
    String enqueue(String sourceId, String previous) throws Exception {
        var source = DirectorySources.load(config).get(sourceId);
        if (source == null) throw new SearchService.Failure(404, "SOURCE_NOT_CONFIGURED");
        synchronized (store) {
            if (previous != null) {
                var old = status(previous, 0);
                if (!sourceId.equals(old.path("sourceId").asText()) || !Set.of("failed", "completed_with_errors").contains(old.path("state").asText()))
                    throw new SearchService.Failure(409, "TASK_NOT_RETRYABLE");
            }
            try (var p = store.connection().prepareStatement("SELECT run_id FROM directory_runs WHERE source_id=? AND state IN ('queued','running')")) {
                p.setString(1, sourceId);try (var r = p.executeQuery()) { if (r.next()) return r.getString(1); }
            }
            try (var p = store.connection().prepareStatement("SELECT COUNT(*) FROM directory_runs WHERE state='queued'");var r = p.executeQuery()) {
                r.next();if (r.getInt(1) >= 8) throw new SearchService.Failure(429, "IMPORT_QUEUE_FULL");
            }
            String id = UUID.randomUUID().toString();
            try (var p = store.connection().prepareStatement("INSERT INTO directory_runs (run_id,source_id,state,code,config_guard,previous_id) VALUES (?,?,'queued','OK',?,?)")) {
                p.setString(1,id);p.setString(2,sourceId);p.setString(3,taskGuard(source));p.setString(4,previous);p.executeUpdate();
            }
            return id;
        }
    }
    ObjectNode status(String id, int offset) throws Exception {
        if (id == null || !id.matches("[a-f0-9-]{36}") || offset < 0 || offset > 99999) throw new IllegalArgumentException("INVALID_TASK_ID");
        synchronized (store) {
            try (var p = store.connection().prepareStatement("SELECT * FROM directory_runs WHERE run_id=?")) {
                p.setString(1,id);try (var r = p.executeQuery()) {
                    if (!r.next()) throw new SearchService.Failure(404,"TASK_NOT_FOUND");
                    var result = Contract.JSON.createObjectNode().put("schemaVersion","1.0").put("runId",id)
                        .put("sourceId",r.getString("source_id")).put("state",r.getString("state")).put("code",r.getString("code"))
                        .put("scanComplete",r.getBoolean("scan_complete")).put("total",r.getInt("total"))
                        .put("processed",r.getInt("processed")).put("errors",r.getInt("errors"));
                    if (r.getString("previous_id") != null) result.put("previousRunId", r.getString("previous_id"));
                    var rows = result.putArray("entries");
                    try (var entries = store.connection().prepareStatement("SELECT * FROM directory_entries WHERE run_id=? ORDER BY seq LIMIT 101 OFFSET ?")) {
                        entries.setString(1,id);entries.setInt(2,offset);
                        try (var e = entries.executeQuery()) { while (e.next()) {
                            if (rows.size() == 100) { result.put("nextOffset", offset+100);break; }
                            var row = rows.addObject().put("path",e.getString("relative_path")).put("state",e.getString("state")).put("code",e.getString("code"));
                            if (e.getString("item_key") != null) row.put("objectKey",e.getString("item_key"));
                        } }
                    }
                    return result;
                }
            }
        }
    }
    String retry(String id) throws Exception { var old=status(id,0);return enqueue(old.path("sourceId").asText(),id); }
    ObjectNode preview(String sourceId,String relative)throws Exception {
        if(!previewSlot.tryAcquire())throw new SearchService.Failure(429,"PREVIEW_BUSY");
        try{return previewTable(sourceId,relative);}finally{previewSlot.release();}
    }
    private ObjectNode previewTable(String sourceId,String relative)throws Exception {
        var source=DirectorySources.load(config).get(sourceId);if(source==null)throw new SearchService.Failure(404,"SOURCE_NOT_CONFIGURED");
        Path root=source.directory().toRealPath();
        if(!root.equals(source.directory())||relative==null||relative.length()>1024)throw new IllegalArgumentException("UNSAFE_FILE_PATH");
        Path rel=Path.of(relative);
        if(rel.isAbsolute()||relative.contains(":")||rel.normalize().startsWith(".."))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
        Path file=root.resolve(rel).normalize();
        for(Path part=file;!part.equals(root);part=part.getParent())if(Files.isSymbolicLink(part))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root))throw new IllegalArgumentException("UNSAFE_FILE_PATH");
        var mapped=readTable(source,root,file,relative);var response=Contract.JSON.createObjectNode().put("schemaVersion","1.1")
            .put("sourceId",sourceId).put("total",mapped.path("total").asInt()).put("errors",mapped.path("errors").asInt()).put("previewLimit",20);
        var rows=response.putArray("rows");for(int i=0;i<Math.min(20,mapped.path("rows").size());i++)rows.add(mapped.path("rows").get(i));
        return response;
    }

    void tick() throws Exception {
        var sources = DirectorySources.load(config);
        for (var source : sources.values()) if (source.interval() > 0) {
            boolean due;
            synchronized (store) {
                try (var p = store.connection().prepareStatement("SELECT MAX(created_at) FROM directory_runs WHERE source_id=?")) {
                    p.setString(1,source.id());try (var r = p.executeQuery()) {
                        r.next();var last=r.getTimestamp(1);due=last==null||last.toInstant().plusSeconds(source.interval()).isBefore(Instant.now());
                    }
                }
            }
            if (due) try {enqueue(source.id(),null);} catch(SearchService.Failure e) {if(e.status==429)break;throw e;}
        }
        String id = null;
        synchronized (store) {
            try (var p = store.connection().prepareStatement("SELECT run_id FROM directory_runs WHERE state='queued' ORDER BY created_at,run_id LIMIT 1");var r=p.executeQuery()) {
                if(r.next()) id=r.getString(1);
            }
        }
        if (id != null) process(id);
    }
    private DirectorySources.Source currentSource(String id, String guard) throws Exception {
        var source = DirectorySources.load(config).get(id);
        if (source == null || !taskGuard(source).equals(guard)) throw new IllegalArgumentException("SOURCE_CONFIG_CHANGED");
        return source;
    }
    private String taskGuard(DirectorySources.Source source) {return Contract.digest(Contract.JSON.valueToTree(List.of(domain,source.guard())));}
    void process(String id) throws Exception {
        String sourceId, guard;
        synchronized (store) {
            try (var p=store.connection().prepareStatement("SELECT source_id,config_guard,state FROM directory_runs WHERE run_id=?")) {
                p.setString(1,id);try(var r=p.executeQuery()) {
                    if(!r.next()||!r.getString(3).equals("queued"))return;
                    sourceId=r.getString(1);guard=r.getString(2);
                }
            }
            update(id,"running","OK",false,0,0,0);
        }
        int processed=0, errors=0, total=0, tableRows=0;boolean scanned=false;
        try {
            var source = currentSource(sourceId,guard);
            Path root = source.directory().toRealPath();
            if (!root.equals(source.directory()) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("UNSAFE_SOURCE_ROOT");
            List<Path> files = scan(root,source.maxFiles());
            scanned=true;total=files.size();update(id,"running","OK",true,total,0,0);
            Set<String> seen = new HashSet<>();
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                currentSource(sourceId,guard);
                String relative=root.relativize(file).toString().replace('\\','/');seen.add(relative);
                String key=null,state,code;
                try {
                    if(relative.toLowerCase(Locale.ROOT).endsWith(".csv")||relative.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                        var table=importTable(source,root,file,relative,seen,10000-tableRows);tableRows+=table.size();
                        for(var row:table) {
                            String rowState=row.path("state").asText();if(!Set.of("ready","unchanged").contains(rowState))errors++;
                            entry(id,processed++,relative+" [row "+row.path("row").asInt()+"]",row.has("key")?row.path("key").asText():null,rowState,row.path("code").asText());
                        }
                        update(id,"running","OK",true,total,processed,errors);continue;
                    }
                    key=key(sourceId,relative);
                    state=importFile(source,root,file,relative,key);
                    code=state.equals("ready")||state.equals("unchanged")?"OK":store.status(key).path("code").asText("PARSE_FAILED");
                    if(!Set.of("ready","unchanged").contains(state))errors++;
                } catch (Exception e) {
                    if(e instanceof InterruptedException||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    state="failed";code=code(e);errors++;
                    invalidateFailure(source,relative,code);
                    for(String old:ownedPaths(sourceId))if(old.startsWith(tablePrefix(relative)))invalidateFailure(source,old,code);
                }
                entry(id,processed++,relative,key,state,code);
                update(id,"running","OK",true,total,processed,errors);
            }
            currentSource(sourceId,guard);
            // No missing-file deletions after an incomplete scan or any per-file failure.
            if (source.deleteMissing() && errors==0) {
                for(var old:ownedPaths(sourceId)) if(!seen.contains(old)) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    currentSource(sourceId,guard);
                    String key;
                    synchronized(store){key=owned(sourceId,old).key();}
                    try { tombstone(sourceId,old);entry(id,processed++,old,key,"deleted","SOURCE_MISSING"); }
                    catch(Exception e){errors++;entry(id,processed++,old,key,"failed",code(e));}
                }
            }
            update(id,errors==0?"completed":"completed_with_errors",errors==0?"OK":"ENTRY_FAILURES",true,total,processed,errors);
        } catch(Exception e) {
            update(id,"failed",Thread.currentThread().isInterrupted()?"PROCESS_INTERRUPTED":code(e),scanned,total,processed,errors);
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
        }
    }
    private String tablePrefix(String relative){return "table:"+nativeId(relative)+":";}
    private ObjectNode readTable(DirectorySources.Source source,Path root,Path file,String relative)throws Exception {
        if(source.tableMapping()==null)throw new IllegalArgumentException("TABLE_MAPPING_REQUIRED");
        boolean allowed=source.acl().path("public").asBoolean();
        for(var user:source.acl().path("users"))allowed|=user.asText().equals(source.parser().user());
        for(var group:source.acl().path("groups"))allowed|=source.parser().groups().contains(group.asText());
        if(!source.parseAllowed()||!allowed)throw new IllegalArgumentException("TABLE_PROCESSING_FORBIDDEN");
        if(!file.toRealPath().startsWith(root))throw new IllegalArgumentException("UNSAFE_SOURCE_FILE");
        byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(10*1024*1024+1);}
        String format=relative.toLowerCase(Locale.ROOT).endsWith(".csv")?"csv":"xlsx";
        if(!relative.toLowerCase(Locale.ROOT).endsWith(".csv")&&!relative.toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        var mapped=new TableMapping(source.tableMapping()).map(TableWorker.snapshot(bytes,format,work));
        currentSource(source.id(),taskGuard(source));return mapped;
    }
    private com.fasterxml.jackson.databind.node.ArrayNode importTable(DirectorySources.Source source,Path root,Path file,String relative,Set<String> seen,int remaining)throws Exception {
        var mapped=readTable(source,root,file,relative);
        if(mapped.path("rows").size()>remaining)throw new IllegalArgumentException("SOURCE_TABLE_ROW_LIMIT");
        var results=Contract.JSON.createArrayNode();
        if(mapped.path("rows").isEmpty())results.addObject().put("row",0).put("state","ready").put("code","EMPTY_TABLE");
        for(var row:mapped.path("rows")) {
            currentSource(source.id(),taskGuard(source));
            var result=results.addObject().put("row",row.path("row").asInt());
            if(!row.path("code").asText().equals("OK")){result.put("state","failed").put("code",row.path("code").asText());continue;}
            String rowId=row.path("nativeId").asText(),ledger=tablePrefix(relative)+nativeId(rowId);
            String nativeKey=Contract.digest(Contract.JSON.valueToTree(List.of(relative,rowId))),key=domain+":"+source.id()+":"+nativeKey;
            seen.add(ledger);result.put("key",key);
            try {
                String fingerprint=Contract.digest(Contract.JSON.valueToTree(List.of(row,source.guard())));
                synchronized(store) {
                    var old=owned(source.id(),ledger);var existing=store.item(key);checkOwner(old,existing);
                    if(old!=null&&old.fingerprint().equals(fingerprint)&&existing!=null&&!existing.path("deleted").asBoolean()&&store.status(key).path("state").asText().equals("ready")) {
                        result.put("state","unchanged").put("code","OK");continue;
                    }
                    long version=existing==null?1:Math.addExact(existing.path("version").asLong(),1);
                    var item=Contract.JSON.createObjectNode().put("schemaVersion","1.1").put("version",version).put("type","record").put("deleted",false)
                        .put("updatedAt",Instant.now().toString()).put("title",row.path("title").asText()).put("text",row.path("text").asText())
                        .put("mediaType","application/vnd.engine.business-record+json").put("parseAllowed",true).put("language","mixed");
                    item.putObject("object").put("domain",domain).put("source",source.id()).put("nativeId",nativeKey);
                    item.putObject("location").put("uri","engine-source://"+domain+"/"+source.id()+"/"+nativeKey+"?file="+java.net.URLEncoder.encode(relative,java.nio.charset.StandardCharsets.UTF_8)).put("rowKey",rowId).put("paragraph",row.path("row").asInt());
                    item.set("acl",source.acl().deepCopy());item.set("typedFields",row.path("typedFields").deepCopy());
                    var applied=store.applyDirectory(item,source.id(),ledger,fingerprint);
                    if(!applied.status().equals("applied"))throw new IllegalArgumentException(applied.code());
                    result.put("state","ready").put("code","OK");
                }
            }catch(Exception e){result.put("state","failed").put("code",code(e));invalidateFailure(source,ledger,code(e));}
        }
        // Failed rows must not leave old values searchable. Keep their ownership for a later retry.
        if(mapped.path("errors").asInt()>0)for(String old:ownedPaths(source.id()))
            if(old.startsWith(tablePrefix(relative))&&!seen.contains(old))invalidateFailure(source,old,"TABLE_ROW_UNVERIFIED");
        return results;
    }
    private List<Path> scan(Path root,int maximum) throws IOException {
        List<Path> files=new ArrayList<>();int[] nodes={0};
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),32,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException {
                if(++nodes[0]>maximum*4+32||!dir.toRealPath().startsWith(root))throw new IOException("SCAN_LIMIT");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {
                if(!attrs.isRegularFile()||attrs.isSymbolicLink()||!file.toRealPath().startsWith(root))throw new IOException("UNSAFE_SOURCE_FILE");
                if(files.size()>=maximum||root.relativize(file).toString().length()>1024)throw new IOException("SCAN_LIMIT");
                files.add(file);return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path->root.relativize(path).toString()));return files;
    }
    private String importFile(DirectorySources.Source source,Path root,Path file,String relative,String key)throws Exception {
        String name=file.getFileName().toString(),extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        String media=MEDIA.get(extension);
        if(media==null)throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        if(!file.toRealPath().startsWith(root))throw new IllegalArgumentException("UNSAFE_SOURCE_FILE");
        byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(10*1024*1024+1);}
        if(bytes.length>10*1024*1024)throw new IllegalArgumentException("FILE_TOO_LARGE");
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String fingerprint=Contract.digest(Contract.JSON.valueToTree(media.equals("text/html")
            ?List.of(hash,source.guard(),"html-title-block-v2"):List.of(hash,source.guard())));
        synchronized(store) {
            var old=owned(source.id(),relative);var existing=store.item(key);
            checkOwner(old,existing);
            boolean same=old!=null&&fingerprint.equals(old.fingerprint())&&existing!=null&&!existing.path("deleted").asBoolean();
            if(same&&store.status(key).path("state").asText().equals("ready"))return "unchanged";
            if(!same) {
                long version=existing==null?1:Math.addExact(existing.path("version").asLong(),1);
                var item=Contract.JSON.createObjectNode().put("version",version).put("type","document").put("deleted",false)
                    .put("updatedAt",Instant.now().toString()).put("title",name.substring(0,Math.min(name.length(),512))).put("text","")
                    .put("mediaType",media).put("parseAllowed",source.parseAllowed()).put("language","mixed");
                item.putObject("object").put("domain",domain).put("source",source.id()).put("nativeId",nativeId(relative));
                item.putObject("location").put("uri","engine-source://"+domain+"/"+source.id()+"/"+nativeId(relative));
                item.set("acl",source.acl().deepCopy());
                if(media.equals("text/html"))item.put("schemaVersion","1.1");
                var applied=store.applyDirectory(item,source.id(),relative,fingerprint);
                if(!applied.status().equals("applied"))throw new IllegalArgumentException("DIRECTORY_APPLY_REJECTED");
            }
        }
        var importer=new FileImporter(store,root,work,source.parser());
        return importer.parse(key,relative,hash);
    }
    private void invalidateFailure(DirectorySources.Source source,String relative,String code)throws Exception {
        synchronized(store) {
            var old=owned(source.id(),relative);
            if(old==null)return;
            var item=store.item(old.key());
            // Never overwrite an administrator/API update that changed ownership/version.
            try {checkOwner(old,item);}catch(IllegalArgumentException conflict){return;}
            if(item==null||item.path("deleted").asBoolean())return;
            if(store.status(old.key()).path("state").asText().equals("ready")) {
                ObjectNode invalid=item.deepCopy();invalid.put("version",Math.addExact(item.path("version").asLong(),1))
                    .put("text","").put("updatedAt",Instant.now().toString()).put("parseAllowed",source.parseAllowed());
                invalid.set("acl",source.acl().deepCopy());
                store.applyDirectory(invalid,source.id(),relative,Contract.digest(Contract.JSON.valueToTree(List.of(code,source.guard(),relative))));
            }
            store.markProjectionDirty(old.key());
            try(var p=store.connection().prepareStatement("UPDATE items SET state='failed',code=?,derived=NULL WHERE item_key=?")) {
                p.setString(1,code);p.setString(2,old.key());p.executeUpdate();
            }
        }
    }
    private void checkOwner(Owned old,JsonNode item) {
        if(item!=null&&(old==null||old.version()!=item.path("version").asLong()||!old.digest().equals(Contract.digest(item))))
            throw new IllegalArgumentException("DIRECTORY_OWNERSHIP_CONFLICT");
    }
    private void tombstone(String source,String relative)throws Exception {
        synchronized(store) {
            var old=owned(source,relative);var item=store.item(old.key());checkOwner(old,item);
            if(item==null||item.path("deleted").asBoolean())return;
            ObjectNode deleted=item.deepCopy();deleted.put("version",Math.addExact(item.path("version").asLong(),1)).put("deleted",true).put("updatedAt",Instant.now().toString());
            if(!store.applyDirectory(deleted,source,relative,old.fingerprint()).status().equals("applied"))throw new IllegalArgumentException("DIRECTORY_APPLY_REJECTED");
        }
    }
    private String key(String source,String path){return domain+":"+source+":"+nativeId(path);}
    private String nativeId(String relative){return Contract.digest(Contract.JSON.valueToTree(relative));}
    private Owned owned(String source,String relative)throws SQLException {
        try(var p=store.connection().prepareStatement("SELECT item_key,item_version,item_digest,fingerprint FROM directory_files WHERE source_id=? AND relative_path=?")) {
            p.setString(1,source);p.setString(2,relative);try(var r=p.executeQuery()){return r.next()?new Owned(r.getString(1),r.getLong(2),r.getString(3),r.getString(4)):null;}
        }
    }
    private List<String> ownedPaths(String source)throws SQLException {
        synchronized(store) {
            List<String> paths=new ArrayList<>();try(var p=store.connection().prepareStatement("SELECT f.relative_path FROM directory_files f JOIN items i ON f.item_key=i.item_key WHERE f.source_id=? AND i.state<>'deleted' ORDER BY f.relative_path")) {
                p.setString(1,source);try(var r=p.executeQuery()){while(r.next())paths.add(r.getString(1));}
            }return paths;
        }
    }
    private void entry(String id,int index,String path,String key,String state,String code)throws SQLException {
        synchronized(store) {try(var p=store.connection().prepareStatement("INSERT INTO directory_entries VALUES (?,?,?,?,?,?)")) {
            p.setString(1,id);p.setInt(2,index);p.setString(3,path);p.setString(4,key);p.setString(5,state);p.setString(6,code);p.executeUpdate();
        }}
    }
    private void update(String id,String state,String code,boolean scan,int total,int processed,int errors)throws SQLException {
        synchronized(store) {try(var p=store.connection().prepareStatement("UPDATE directory_runs SET state=?,code=?,scan_complete=?,total=?,processed=?,errors=? WHERE run_id=?")) {
            p.setString(1,state);p.setString(2,code);p.setBoolean(3,scan);p.setInt(4,total);p.setInt(5,processed);p.setInt(6,errors);p.setString(7,id);p.executeUpdate();
        }}
    }
    private String code(Exception error) {
        String message=error.getMessage();return message!=null&&message.matches("[A-Z_]{1,64}")?message:"DIRECTORY_IMPORT_FAILED";
    }
}
