package local.federated.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Ingestion, authenticated retrieval and user-behavior recommendations. */
public final class EngineServer implements AutoCloseable {
    private static final String PREFIX = "/api/engine/v1/";
    private static final Set<String> BUSINESS = Set.of("items:batch", "users:batch", "events:batch", "search", "recommendations", "analyze", "source", "source-content");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.CallerRunsPolicy());
    private final byte[] adminKey;
    private final ItemStore store;
    private final String domain;
    private final SearchService search;
    private final LocalIdentities identities;
    private final UserBehaviorStore users;
    private final RecommendationService recommendations;
    private final AnalysisService analysis;
    private final DirectoryImports imports;

    public EngineServer(String host, int port, String key) throws IOException {
        this(host,port,key,null,"demo");
    }
    public EngineServer(String host, int port, String key, ItemStore store, String domain) throws IOException {
        this(host,port,key,store,domain,null,null);
    }
    public EngineServer(String host,int port,String key,ItemStore store,String domain,SearchService search,LocalIdentities identities)throws IOException {
        this(host, port, key, store, domain, search, identities, BehaviorPolicy.load(null));
    }
    public EngineServer(String host,int port,String key,ItemStore store,String domain,SearchService search,LocalIdentities identities,BehaviorPolicy policy)throws IOException {
        this(host,port,key,store,domain,search,identities,policy,null);
    }
    public EngineServer(String host,int port,String key,ItemStore store,String domain,SearchService search,LocalIdentities identities,BehaviorPolicy policy,java.nio.file.Path directoryConfig)throws IOException {
        // Remote exposure waits for D6 TLS/auth setup.
        if (!"127.0.0.1".equals(host)) throw new IllegalArgumentException("Engine requires loopback host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (key == null || key.length() < 32 || key.length() > 256 || !key.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("ENGINE_ADMIN_KEY requires 32..256 ASCII token characters");
        adminKey = ("Bearer " + key).getBytes(StandardCharsets.US_ASCII);
        if(domain==null||!domain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("INVALID_DOMAIN");
        this.store=store;this.domain=domain;
        this.search=search;this.identities=identities;
        try {
            this.users = store == null ? null : new UserBehaviorStore(store, policy);
            this.recommendations = store == null ? null : new RecommendationService(store, users, search, domain);
        } catch (java.sql.SQLException error) {
            throw new IOException("User store unavailable", error);
        }
        this.analysis = store == null ? null : new AnalysisService(store, domain, users);
        try {this.imports=directoryConfig==null?null:new DirectoryImports(store,directoryConfig,directoryConfig.getParent().resolve("directory-work"),domain);}
        catch(Exception e){throw new IOException("Directory configuration unavailable",e);}
        if (search != null) search.bindUsers(users);
        server = HttpServer.create(new InetSocketAddress(host, port), 32);
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() { server.start();if(imports!=null)imports.start(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); workers.shutdownNow();if(imports!=null)imports.close();if(search!=null)search.close(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (Set.of("/", "/demo", "/demo/app.js", "/demo/style.css").contains(path)) {
                demo(exchange, path); return;
            }
            String endpoint = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (path.startsWith("/api/engine/admin/v1/import")) { directoryRoute(exchange,path);return; }
            if (path.equals("/api/engine/admin/v1/status")) {
                var auth = exchange.getRequestHeaders().get("Authorization");
                if (auth == null || auth.size() != 1 || !MessageDigest.isEqual(adminKey, auth.get(0).getBytes(StandardCharsets.UTF_8))) {
                    error(exchange,401,"UNAUTHORIZED");return;
                }
                if (!exchange.getRequestMethod().equals("GET")) { error(exchange,405,"METHOD_NOT_ALLOWED");return; }
                var status = Contract.JSON.createObjectNode().put("schemaVersion","1.0").put("observedAt",java.time.Instant.now().toString());
                if (store == null) status.putObject("store").put("state","not_configured");
                else try { status.set("store",store.runtimeStatus()); }
                catch (java.sql.SQLException e) { status.putObject("store").put("state","unavailable"); }
                if (search == null) status.putObject("search").put("state","not_configured");
                else try { status.set("search",search.runtimeStatus()); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    status.putObject("search").put("state","unavailable");
                }
                reply(exchange,200,status);return;
            }
            if (endpoint.equals("health")) {
                if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
                reply(exchange, 200, Map.of("schemaVersion", "1.0", "status", "ok", "stage", "D7",
                    "ready", store!=null&&search!=null&&search.available()&&identities!=null, "capabilities", Map.of("health", true, "ingestion", store!=null, "search", search!=null&&search.available(),
                        "personalization", recommendations!=null&&identities!=null, "analysis", analysis!=null&&identities!=null), "dependencies", store==null?"not_connected":"embedded_store"));
                return;
            }
            if (!BUSINESS.contains(endpoint)) { error(exchange, 404, "NOT_FOUND"); return; }
            var supplied = exchange.getRequestHeaders().get("Authorization");
            boolean queryRoute = Set.of("analyze", "source", "source-content").contains(endpoint) && analysis != null || endpoint.equals("search") && search != null || endpoint.equals("recommendations") && recommendations != null;
            boolean ownEvents = endpoint.equals("events:batch") && users != null && identities != null && supplied != null && supplied.size() == 1
                    && !MessageDigest.isEqual(adminKey, supplied.get(0).getBytes(StandardCharsets.UTF_8));
            if((queryRoute||ownEvents)&&identities!=null) {
                if(!exchange.getRequestMethod().equals("POST")){error(exchange,405,"METHOD_NOT_ALLOWED");return;}
                LocalIdentities.Identity identity;
                try{
                    identity=identities.authenticate(supplied!=null&&supplied.size()==1?supplied.get(0):null);
                    if(identity!=null)identity=users.resolve(identity);
                }catch(IOException e){error(exchange,503,"AUTH_UNAVAILABLE");return;}
                if(identity==null){error(exchange,401,"UNAUTHORIZED");return;}
                var input=readJson(exchange);if(input==null)return;
                try{
                    if(ownEvents)reply(exchange,200,batch(input,"events",identity));
                    else if(endpoint.equals("source"))reply(exchange,200,analysis.source(input,identity));
                    else if(endpoint.equals("source-content"))reply(exchange,200,analysis.sourceContent(input,identity));
                    else if(endpoint.equals("analyze"))reply(exchange,200,analysis.analyze(input,identity));
                    else if(endpoint.equals("recommendations"))reply(exchange,200,recommendations.recommend(input,identity));
                    else reply(exchange,200,search.search(input,identity));
                }
                catch(SearchService.Failure e){error(exchange,e.status,e.code);}
                catch(IllegalArgumentException e){error(exchange,422,endpoint.equals("search")?"INVALID_SEARCH":"INVALID_INPUT");}
                catch(Exception e){error(exchange,503,endpoint.equals("search")?"SEARCH_UNAVAILABLE":"CAPABILITY_UNAVAILABLE");}
                return;
            }
            if (supplied == null || supplied.size() != 1 || !MessageDigest.isEqual(adminKey, supplied.get(0).getBytes(StandardCharsets.UTF_8))) {
                error(exchange, 401, "UNAUTHORIZED"); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
            if(endpoint.equals("items:batch")&&store!=null){items(exchange);return;}
            if((endpoint.equals("users:batch")||endpoint.equals("events:batch"))&&users!=null){
                var input=readJson(exchange);if(input==null)return;
                reply(exchange,200,batch(input,endpoint.equals("users:batch")?"users":"events",null));return;
            }
            error(exchange, 503, "CAPABILITY_NOT_READY");
        } catch(java.sql.SQLException e) { error(exchange,503,"STORE_UNAVAILABLE");
        } catch(IllegalArgumentException e) { error(exchange,422,"INVALID_INPUT");
        } finally { exchange.close(); }
    }

    private void directoryRoute(HttpExchange exchange,String path)throws IOException {
        var supplied=exchange.getRequestHeaders().get("Authorization");
        if(supplied==null||supplied.size()!=1||!MessageDigest.isEqual(adminKey,supplied.get(0).getBytes(StandardCharsets.UTF_8))) {
            error(exchange,401,"UNAUTHORIZED");return;
        }
        String route=path.substring("/api/engine/admin/v1/".length());
        boolean sources=route.equals("import-sources"),create=route.equals("imports"),preview=route.equals("import-preview"),retry=route.matches("imports/[a-f0-9-]{36}/retry"),status=route.matches("imports/[a-f0-9-]{36}");
        if(!sources&&!create&&!retry&&!status&&!preview){error(exchange,404,"NOT_FOUND");return;}
        if(!exchange.getRequestMethod().equals(create||retry||preview?"POST":"GET")){error(exchange,405,"METHOD_NOT_ALLOWED");return;}
        if(imports==null){error(exchange,503,"DIRECTORY_IMPORT_NOT_CONFIGURED");return;}
        try {
            String query=exchange.getRequestURI().getRawQuery();
            if(query!=null&&(!status||!query.matches("offset=[0-9]{1,5}")))throw new IllegalArgumentException("INVALID_QUERY");
            if(sources){reply(exchange,200,imports.sources());return;}
            if(status){reply(exchange,200,imports.status(route.split("/")[1],query==null?0:Integer.parseInt(query.substring(7))));return;}
            var input=readJson(exchange);if(input==null)return;
            if(preview) {
                if(!input.isObject()||input.size()!=3||!input.path("schemaVersion").isTextual()||!input.path("schemaVersion").asText().equals("1.1")||!input.path("sourceId").isTextual()||!input.path("relativePath").isTextual())throw new IllegalArgumentException("INVALID_INPUT");
                reply(exchange,200,imports.preview(input.path("sourceId").asText(),input.path("relativePath").asText()));return;
            }
            if(!input.isObject()||!input.path("schemaVersion").isTextual()||!input.path("schemaVersion").asText().equals("1.0")
                    ||input.size()!=(create?2:1)||create&&!input.path("sourceId").isTextual())throw new IllegalArgumentException("INVALID_INPUT");
            String id=create?imports.enqueue(input.path("sourceId").asText(),null):imports.retry(route.split("/")[1]);
            reply(exchange,202,Map.of("schemaVersion","1.0","runId",id));
        } catch(SearchService.Failure e){error(exchange,e.status,e.code);}
        catch(IllegalArgumentException e){error(exchange,422,"INVALID_DIRECTORY_INPUT");}
        catch(Exception e){error(exchange,503,"DIRECTORY_IMPORT_UNAVAILABLE");}
    }

    private void demo(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "METHOD_NOT_ALLOWED"); return; }
        String name = path.endsWith("app.js") ? "app.js" : path.endsWith("style.css") ? "style.css" : "index.html";
        try (var stream = EngineServer.class.getResourceAsStream("/demo/" + name)) {
            if (stream == null) { error(exchange, 404, "NOT_FOUND"); return; }
            byte[] bytes = stream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", name.endsWith("js") ? "text/javascript; charset=utf-8" : name.endsWith("css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Object batch(com.fasterxml.jackson.databind.JsonNode input, String field, LocalIdentities.Identity actor) throws IOException, java.sql.SQLException {
        if (!input.isObject() || input.size() != 2 || !input.path("schemaVersion").isTextual() || !input.path("schemaVersion").asText().equals("1.0")
                || !input.path(field).isArray() || input.path(field).isEmpty() || input.path(field).size() > 100) throw new IllegalArgumentException("INVALID_BATCH");
        var response = Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("requestId", UUID.randomUUID().toString());
        var results = response.putArray("results");
        int index = 0;
        for (var entry : input.path(field)) {
            ItemStore.Outcome outcome = field.equals("users") ? users.applyUser(domain, entry) : users.applyEvent(domain, entry, actor);
            results.addObject().put("index", index++).put("status", outcome.status()).put("code", outcome.code());
        }
        return response;
    }

    private void items(HttpExchange exchange)throws IOException,java.sql.SQLException {
        var input=readJson(exchange);if(input==null)return;
        if(!input.isObject()||input.size()!=2||!input.path("schemaVersion").isTextual()||!Set.of("1.0","1.1").contains(input.path("schemaVersion").asText())||!input.path("items").isArray()||input.path("items").isEmpty()||input.path("items").size()>100){error(exchange,422,"INVALID_BATCH");return;}
        var result=Contract.JSON.createObjectNode().put("schemaVersion",input.path("schemaVersion").asText()).put("requestId",UUID.randomUUID().toString());
        var rows=result.putArray("results");int i=0;
        for(var item:input.path("items")) {
            if(input.path("schemaVersion").asText().equals("1.0")&&item.has("schemaVersion")) {
                rows.addObject().put("index",i++).put("status","rejected").put("code","ITEM_VERSION_MISMATCH");continue;
            }
            boolean managed=false;
            if(imports!=null)try{managed=imports.manages(item.path("object").path("source").asText());}
            catch(Exception e){error(exchange,503,"DIRECTORY_CONFIG_UNAVAILABLE");return;}
            var out=managed?new ItemStore.Outcome("rejected","DIRECTORY_SOURCE_OWNED"):
                domain.equals(item.path("object").path("domain").asText())?store.apply(item):new ItemStore.Outcome("rejected","DOMAIN_FORBIDDEN");
            rows.addObject().put("index",i++).put("status",out.status()).put("code",out.code());
        }
        reply(exchange,200,result);
    }
    private com.fasterxml.jackson.databind.JsonNode readJson(HttpExchange exchange)throws IOException {
        String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
        if(contentType==null||!contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;\\s*charset=utf-8)?")){error(exchange,400,"CONTENT_TYPE_REQUIRED");return null;}
        byte[] body=exchange.getRequestBody().readNBytes(1024*1024+1);
        if(body.length>1024*1024){error(exchange,413,"PAYLOAD_TOO_LARGE");return null;}
        com.fasterxml.jackson.databind.JsonNode input;
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(body)).toString();
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            input=Contract.JSON.readTree(text);
        }catch(IOException e){error(exchange,400,"INVALID_JSON");return null;}
        if(input==null){error(exchange,400,"INVALID_JSON");return null;}return input;
    }

    private void error(HttpExchange exchange, int status, String code) throws IOException {
        reply(exchange, status, Map.of("schemaVersion", "1.0", "code", code,
            "requestId", UUID.randomUUID().toString()));
    }
    private void reply(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = json.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.httpserver.maxReqTime","15");
        System.setProperty("sun.net.httpserver.maxRspTime","60");
        var env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("ENGINE_PORT", "18080"));
        if (port == 0) throw new IllegalArgumentException("ENGINE_PORT must be 1..65535");
        var store=new ItemStore(ItemStore.dataDirectory(IngestCli.home()));
        SearchService search=null;LocalIdentities identities=null;
        EngineServer service;
        try{
            if(env.containsKey("ENGINE_QUERY_AUTH_FILE")) {
                var file=java.nio.file.Path.of(env.get("ENGINE_QUERY_AUTH_FILE")).toRealPath();
                if(!file.startsWith(IngestCli.home().resolve(".local").toRealPath()))throw new IllegalArgumentException("AUTH_FILE_MUST_BE_PRIVATE");
                identities=new LocalIdentities(file);
            }
            if(env.containsKey("ENGINE_ES_URL")) {
                if(identities==null)throw new IllegalArgumentException("Configure ENGINE_QUERY_AUTH_FILE");
                var index=new SearchIndex(java.net.URI.create(env.get("ENGINE_ES_URL")),env.getOrDefault("ENGINE_ES_INDEX","engine-v01-items"));
                EmbeddingClient model=env.containsKey("ENGINE_MODEL_URL")?new EmbeddingClient(java.net.URI.create(env.get("ENGINE_MODEL_URL")),env.get("EMBEDDING_API_KEY"),java.time.Duration.ofSeconds(30)):null;
                if(model!=null&&!env.containsKey("ENGINE_SEMANTIC_MIN"))throw new IllegalArgumentException("Configure an explicitly calibrated ENGINE_SEMANTIC_MIN");
                search=new SearchService(store,index,model,env.getOrDefault("ENGINE_DOMAIN","demo"),Double.parseDouble(env.getOrDefault("ENGINE_SEMANTIC_MIN","0.4")));
                search.configureLowMatchThreshold(Double.parseDouble(env.getOrDefault("ENGINE_LOW_MATCH_COSINE",env.getOrDefault("ENGINE_SEMANTIC_MIN","0.4"))));
                search.start();
            }
            BehaviorPolicy policy = BehaviorPolicy.load(env.containsKey("ENGINE_BEHAVIOR_POLICY") ? java.nio.file.Path.of(env.get("ENGINE_BEHAVIOR_POLICY")) : null);
            java.nio.file.Path directoryConfig=null;
            if(env.containsKey("ENGINE_DIRECTORY_SOURCES")) {
                directoryConfig=java.nio.file.Path.of(env.get("ENGINE_DIRECTORY_SOURCES")).toRealPath();
                if(!directoryConfig.startsWith(IngestCli.home().resolve(".local").toRealPath()))throw new IllegalArgumentException("DIRECTORY_CONFIG_MUST_BE_PRIVATE");
            }
            service = new EngineServer(env.getOrDefault("ENGINE_HOST", "127.0.0.1"), port, env.get("ENGINE_ADMIN_KEY"),store,env.getOrDefault("ENGINE_DOMAIN","demo"),search,identities,policy,directoryConfig);
        }
        catch(Exception e){if(search!=null)search.close();store.close();throw e;}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{service.close();try{store.close();}catch(Exception ignored){}}));
        service.start();
        System.out.println("Engine v0.2.0 listening on 127.0.0.1:" + service.port());
    }
}
