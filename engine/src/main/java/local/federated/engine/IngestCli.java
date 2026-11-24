package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Offline local adapters; close the engine normally before opening its embedded store here. */
public final class IngestCli {
    static Path home(){return Path.of(System.getProperty("engine.home",System.getProperty("user.dir"))).toAbsolutePath().normalize();}
    static JsonNode read(Path path)throws Exception {
        byte[] bytes;try(var in=Files.newInputStream(path)){bytes=in.readNBytes(1024*1024+1);}
        if(bytes.length>1024*1024)throw new IllegalArgumentException("MANIFEST_TOO_LARGE");
        String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        return Contract.JSON.readTree(text);
    }
    public static void main(String[] args) {
        try {run(args);}catch(Exception e){System.err.println("Import failed; no source writes. Check config, permissions, input and exclusive store access.");System.exit(1);}
    }
    static void run(String[] args)throws Exception {
        if(args.length==0||!Set.of("posts","files","business-json","mysql","status","run-status","users","events","events-local").contains(args[0]))throw new IllegalArgumentException("INVALID_COMMAND");
        var env=System.getenv();String domain=env.getOrDefault("ENGINE_DOMAIN","demo");
        String source=env.getOrDefault("ENGINE_SOURCE",args[0].equals("posts")?"posts":args[0].equals("files")?"files":"business");
        Path directory=ItemStore.dataDirectory(home());
        try(var store=new ItemStore(directory)) {
            var summary=Contract.JSON.createObjectNode().put("stage","D4").put("mode",args[0]);var results=summary.putArray("results");
            String runId=Set.of("status","run-status").contains(args[0])?null:store.beginRun(args[0]);
            if(runId!=null)summary.put("runId",runId);
            try {
            switch(args[0]) {
                case "users", "events", "events-local" -> {
                    if (args.length != 2) throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var input = read(Path.of(args[1]));
                    String field = args[0].equals("users") ? "users" : "events";
                    if (!input.isObject() || input.size() != 2 || !input.path("schemaVersion").isTextual() || !input.path("schemaVersion").asText().equals("1.0")
                            || !input.path(field).isArray() || input.path(field).isEmpty() || input.path(field).size() > 100) throw new IllegalArgumentException("INVALID_BATCH");
                    var behavior = new UserBehaviorStore(store, BehaviorPolicy.load(env.containsKey("ENGINE_BEHAVIOR_POLICY") ? Path.of(env.get("ENGINE_BEHAVIOR_POLICY")) : null));
                    int index = 0;
                    for (var row : input.path(field)) {
                        ItemStore.Outcome outcome;
                        if (args[0].equals("events-local")) {
                            if (!row.isObject() || row.size() != 2 || !row.has("event") || !row.path("localUserId").isTextual()) {
                                outcome = new ItemStore.Outcome("rejected", "INVALID_LOCAL_EVENT");
                            } else {
                                String canonical = behavior.resolveLocalUser(domain, row.path("event").path("source").asText(), row.path("localUserId").asText());
                                if (canonical == null || !row.path("event").isObject() || row.path("event").has("userId")) {
                                    outcome = new ItemStore.Outcome("rejected", "SOURCE_IDENTITY_UNMAPPED");
                                } else {
                                    com.fasterxml.jackson.databind.node.ObjectNode event = row.path("event").deepCopy();
                                    event.put("userId", canonical);
                                    outcome = behavior.applyEvent(domain, event, null);
                                }
                            }
                        } else {
                            outcome = field.equals("users") ? behavior.applyUser(domain, row) : behavior.applyEvent(domain, row, null);
                        }
                        results.addObject().put("index", index++).put("status", outcome.status()).put("code", outcome.code());
                    }
                }
                case "run-status" -> {
                    if(args.length!=2)throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var result=store.runStatus(args[1]);if(result==null)throw new IllegalArgumentException("NOT_FOUND");results.add(result);
                }
                case "status" -> {
                    if(args.length!=2)throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var state=store.status(args[1]);
                    if(state==null)throw new IllegalArgumentException("NOT_FOUND");
                    // Admin local tool reports status and locations, not raw text or ACLs.
                    var result=results.addObject().put("state",state.path("state").asText()).put("code",state.path("code").asText("OK")).put("version",state.path("version").asLong());
                    result.put("parts",state.path("derived").size());
                    result.set("jobs",state.path("jobs"));
                    var locations=result.putArray("locations");for(var part:state.path("derived"))locations.add(part.path("location"));
                }
                case "posts", "business-json" -> {
                    if(args.length!=2)throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var rows=read(Path.of(args[1]));if(!rows.isArray()||rows.size()>10000)throw new IllegalArgumentException("INVALID_ROWS");
                    int i=0;for(var row:rows) {
                        var result=results.addObject().put("index",i++);
                        try{var out=store.apply(SourceAdapters.map(row,domain,source,args[0].equals("posts")));result.put("status",out.status()).put("code",out.code());}
                        catch(IllegalArgumentException e){result.put("status","rejected").put("code","INVALID_SOURCE_ROW");}
                    }
                    if(args[0].equals("business-json"))summary.put("synthetic",true);
                }
                case "files" -> {
                    if(args.length!=3)throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var manifest=read(Path.of(args[1]));
                    if(!manifest.isObject()||manifest.size()!=2||!manifest.path("schemaVersion").isTextual()||!manifest.path("schemaVersion").asText().equals("1.0")||!manifest.path("files").isArray()||manifest.path("files").isEmpty()||manifest.path("files").size()>100)throw new IllegalArgumentException("INVALID_MANIFEST");
                    var groups=new HashSet<>(List.of(env.getOrDefault("ENGINE_PARSE_GROUPS","").split(",")));groups.remove("");
                    var principal=new ItemStore.Principal(env.getOrDefault("ENGINE_PARSE_USER",""),Set.copyOf(groups));
                    var importer=new FileImporter(store,Path.of(args[2]),directory.resolve("work"),principal);
                    int i=0;for(var entry:manifest.path("files")) {
                        if(entry.size()!=2||!entry.has("item")||!entry.path("path").isTextual())throw new IllegalArgumentException("INVALID_MANIFEST_ENTRY");
                        if(!domain.equals(entry.path("item").path("object").path("domain").asText())||!source.equals(entry.path("item").path("object").path("source").asText()))throw new IllegalArgumentException("SOURCE_SCOPE_MISMATCH");
                        var out=store.apply(entry.get("item"));var result=results.addObject().put("index",i++).put("status",out.status()).put("code",out.code());
                        if(!out.status().equals("rejected")&&!out.status().equals("stale")&&!entry.path("item").path("deleted").asBoolean()) {
                            try{result.put("parseState",importer.parse(Contract.key(entry.path("item").path("object")),entry.path("path").asText()));}
                            catch(IllegalArgumentException e){result.put("parseState","rejected").put("code","PARSE_FORBIDDEN");}
                        }
                    }
                }
                case "mysql" -> {
                    if(args.length!=1)throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    var config=new SourceAdapters.MySqlConfig(env.get("ENGINE_MYSQL_HOST"),Integer.parseInt(env.getOrDefault("ENGINE_MYSQL_PORT","3306")),env.get("ENGINE_MYSQL_DATABASE"),env.get("ENGINE_MYSQL_VIEW"),env.get("ENGINE_MYSQL_USER"),env.get("ENGINE_MYSQL_PASSWORD"));
                    int[] counts=new int[4];
                    SourceAdapters.mysql(config,row->{
                        try{var out=store.apply(SourceAdapters.map(row,domain,source,false));counts[switch(out.status()){case "applied"->0;case "duplicate"->1;case "stale"->2;default->3;}]++;}
                        catch(IllegalArgumentException e){counts[3]++;}
                        catch(Exception e){throw new IllegalStateException("LOCAL_STORE_FAILED");}
                    });
                    summary.put("applied",counts[0]).put("duplicate",counts[1]).put("stale",counts[2]).put("rejected",counts[3]);
                }
            }
            if(runId!=null){boolean errors=summary.path("rejected").asInt()>0;
                for(var r:results)errors|=r.path("status").asText().equals("rejected")||r.has("parseState")&&!r.path("parseState").asText().equals("ready");
                String state=errors?"completed_with_errors":"completed";summary.put("runState",state);store.finishRun(runId,state,summary);}
            }catch(Exception e){if(runId!=null){summary.put("runState","failed").put("code","IMPORT_FAILED");store.finishRun(runId,"failed",summary);}throw e;}
            System.out.println(Contract.JSON.writeValueAsString(summary));
        }
    }
}
