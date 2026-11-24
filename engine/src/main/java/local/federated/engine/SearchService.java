package local.federated.engine;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

public final class SearchService implements AutoCloseable {
    private final ItemStore store;
    private final SearchIndex index;
    private final EmbeddingClient model;
    private final EmbeddingCache embeddingCache;
    private final IndexReceipts receipts;
    private String indexNamespace;
    private final String domain;
    private final double minimum;
    private double lowMatchThreshold=0.55;
    private final Clock clock;
    private final Contract contract=new Contract();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Map<String,String> indexed=new ConcurrentHashMap<>(),complete=new ConcurrentHashMap<>();
    private final Map<String,Long> retryAfter=new ConcurrentHashMap<>();
    private long modelRetryAt;
    private volatile boolean available;
    private volatile String syncCode = "NOT_STARTED";
    private volatile long lastKeywordSync;
    private volatile long completedRevision=-1;
    private UserBehaviorStore users;
    private final byte[] secret=new byte[32];
    private final Map<String,Snapshot> snapshots=new LinkedHashMap<>();
    private record Ranked(JsonNode hit,double score,List<String> channels) {}
    private record Snapshot(String owner,String query,long expires,List<Ranked> hits,List<String> reasons) {}
    public static final class Failure extends IOException {
        public final String code;public final int status;
        Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
    public SearchService(ItemStore store,SearchIndex index,EmbeddingClient model,String domain,double minimum)throws IOException {
        this(store,index,model,domain,minimum,Clock.systemUTC());
    }
    SearchService(ItemStore store,SearchIndex index,EmbeddingClient model,String domain,double minimum,Clock clock)throws IOException {
        if(!Double.isFinite(minimum)||minimum<0||minimum>1)throw new IllegalArgumentException("INVALID_THRESHOLD");
        this.store=store;this.index=index;this.model=model;this.embeddingCache=new EmbeddingCache(store);this.domain=domain;this.minimum=minimum;this.lowMatchThreshold=minimum;this.clock=clock;new SecureRandom().nextBytes(secret);
        try{this.receipts=new IndexReceipts(store);}catch(java.sql.SQLException e){throw new IOException("INDEX_RECEIPTS_UNAVAILABLE",e);}
    }
    public void start(){worker.scheduleWithFixedDelay(()->{try{sync();}catch(Exception e){available=false;syncCode="INDEX_SYNC_FAILED";}},0,3,TimeUnit.SECONDS);}
    public void configureLowMatchThreshold(double value) {
        if(!Double.isFinite(value)||value<minimum||value>1)throw new IllegalArgumentException("INVALID_LOW_MATCH_THRESHOLD");
        lowMatchThreshold=value;
    }
    public boolean available(){return available;}
    public ObjectNode runtimeStatus() throws Exception {
        var result = Contract.JSON.createObjectNode().put("state", available ? "available" : "unavailable")
            .put("syncCode", syncCode).put("lastKeywordSyncEpochMs", lastKeywordSync);
        int ready = 0, keyword = 0, vector = 0;
        // Compare current guards, not the number of historical entries in the maps.
        for (var p : store.metadata(domain)) if (p.ready()) {
            ready++;
            if (p.guard().equals(indexed.get(p.key()))) keyword++;
            if (p.guard().equals(complete.get(p.key()))) vector++;
        }
        result.put("readyObjects", ready).put("keywordObjects", keyword).put("vectorObjects", vector);
        result.set("encodingCache",embeddingCache.status());
        if (model == null) result.putObject("model").put("state", "not_configured");
        else try { result.set("model", model.runtimeStatus()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        catch (IOException e) { result.putObject("model").put("state", "unavailable").put("code", "MODEL_STATUS_UNAVAILABLE"); }
        return result;
    }
    void bindUsers(UserBehaviorStore users) { this.users = users; }
    @Override public void close(){worker.shutdownNow();}
    public synchronized void sync()throws Exception {
        String namespace=index.ensure();
        if(!namespace.equals(indexNamespace)) {
            completedRevision=-1;
            indexed.clear();complete.clear();retryAfter.clear();modelRetryAt=0;
            receipts.load(namespace,indexed,complete);indexNamespace=namespace;
        }
        List<ItemStore.Projection> projections;long revision;
        synchronized(store){projections=store.metadata(domain);revision=store.metadataRevision();}
        List<Publication> pending=new ArrayList<>();
        // Publish all keyword changes before touching the optional model, including on cold start.
        for(var summary:projections)if(!summary.guard().equals(indexed.get(summary.key()))) {
            var p=store.projection(summary.key());
            pending.add(new Publication(p,keywords(p)));
            if(pending.size()==25)publish(namespace,pending,false);
        }
        publish(namespace,pending,false);
        available=true;
        lastKeywordSync=clock.millis();syncCode="KEYWORD_READY";
        if(model==null||clock.millis()<modelRetryAt)return;
        for(var summary:projections) {
            if(!summary.ready()||summary.guard().equals(complete.get(summary.key()))||clock.millis()<retryAfter.getOrDefault(summary.key(),0L))continue;
            var p=store.projection(summary.key());
            List<Map<String,Object>> docs=keywords(p);
            try {
                int vectorNumber=0;
                for(var part:p.parts()) {
                    String text=part.path("text").asText();if(text.isBlank())continue;
                    var segments=model.segments(p.item().path("title").asText(),text);
                    // Align with the model's two-input scheduling quantum and the 30s client budget.
                    for(int offset=0;offset<segments.size();offset+=2) {
                        List<String> inputs=new ArrayList<>();for(int i=offset;i<Math.min(offset+2,segments.size());i++)inputs.add(segments.get(i).path("input").asText());
                        List<List<Double>> vectors=new ArrayList<>();List<String> missing=new ArrayList<>();List<Integer> missingPositions=new ArrayList<>();
                        for(int i=0;i<inputs.size();i++) {
                            var cached=embeddingCache.get(p,inputs.get(i));vectors.add(cached);
                            if(cached==null){missing.add(inputs.get(i));missingPositions.add(i);}
                        }
                        if(!missing.isEmpty()){
                            var current=store.projection(p.key());
                            if(current==null||!current.ready()||!current.guard().equals(p.guard()))break;
                            var result=model.encodeIndex(missing);
                            for(int i=0;i<missing.size();i++){
                                var value=result.vectors().get(i);vectors.set(missingPositions.get(i),value);
                                embeddingCache.put(p,missing.get(i),value);
                            }
                        }
                        for(int i=0;i<inputs.size();i++) {
                            var segment=segments.get(offset+i);var vectorPart=Contract.JSON.createObjectNode();vectorPart.set("text",segment.get("text"));
                            ObjectNode location=part.path("location").deepCopy();int base=location.path("charStart").asInt();location.put("charStart",base+segment.path("charStart").asInt()).put("charEnd",base+segment.path("charEnd").asInt());
                            vectorPart.set("location",location);docs.add(document(p,vectorPart,"vector",vectorNumber++,vectors.get(i)));
                        }
                    }
                }
            } catch(Exception e){
                if(e instanceof InterruptedException){Thread.currentThread().interrupt();throw e;}
                retryAfter.put(p.key(),clock.millis()+30000);
                if(e instanceof EmbeddingClient.ModelFailure failure&&failure.httpStatus==422)continue;
                publish(namespace,pending,true);modelRetryAt=clock.millis()+30000;return;
            }
            var current=store.projection(p.key());
            if(current!=null&&current.guard().equals(p.guard())){
                pending.add(new Publication(p,docs));
                if(pending.size()==25)publish(namespace,pending,true);
            }
        }
        publish(namespace,pending,true);
        synchronized(store){if(store.metadataRevision()==revision&&projections.stream().filter(ItemStore.Projection::ready).allMatch(p->p.guard().equals(complete.get(p.key()))))completedRevision=revision;}
    }
    private record Publication(ItemStore.Projection projection,List<Map<String,Object>> documents) {}
    private void publish(String namespace,List<Publication> pending,boolean vectors)throws Exception {
        Map<String,List<Map<String,Object>>> replacements=new LinkedHashMap<>();List<ItemStore.Projection> accepted=new ArrayList<>();
        for(var publication:pending){
            var p=publication.projection();var current=store.metadataItem(p.key());
            if(current==null||!current.guard().equals(p.guard()))continue;
            receipts.forget(namespace,p.key());indexed.remove(p.key());complete.remove(p.key());
            replacements.put(p.key(),publication.documents());accepted.add(p);
        }
        index.replaceBatch(replacements);
        for(var p:accepted)if(receipts.acknowledge(namespace,p,vectors)){
            indexed.put(p.key(),p.guard());if(vectors)complete.put(p.key(),p.guard());
        }
        pending.clear();
    }
    private List<Map<String,Object>> keywords(ItemStore.Projection p)throws Exception {
        List<Map<String,Object>> docs=new ArrayList<>();int number=0;
        if(p.ready())for(var part:p.parts())if(!part.path("text").asText().isBlank())docs.add(document(p,part,"keyword",number++,null));return docs;
    }
    private Map<String,Object> document(ItemStore.Projection p,JsonNode part,String kind,int number,List<Double> vector)throws Exception {
        Map<String,Object> doc=new LinkedHashMap<>();String docId=Contract.digest(Contract.JSON.valueToTree(List.of(p.key(),kind,number)));
        doc.put("doc_id",docId);doc.put("root_key",p.key());doc.put("guard",p.guard());doc.put("kind",kind);doc.put("domain",domain);
        doc.put("source",p.item().path("object").path("source").asText());doc.put("type",p.item().path("type").asText());doc.put("updated_at",p.item().path("updatedAt").asText());
        doc.put("title",p.item().path("title").asText());doc.put("text",part.path("text").asText());doc.put("location",part.path("location"));
        doc.put("title_terms",SearchText.terms(p.item().path("title").asText()));doc.put("text_terms",SearchText.terms(part.path("text").asText()));
        doc.put("access_scopes",store.accessScopes(p.key()));
        for(String field:List.of("category","status"))if(p.item().path("fields").has(field))doc.put(field,p.item().path("fields").path(field).asText());
        if(vector!=null){doc.put("vector",vector);doc.put("model",EmbeddingClient.MODEL);doc.put("policy",EmbeddingClient.POLICY);}return doc;
    }
    private ObjectNode normalize(JsonNode request) {
        contract.validateSearch(request);ObjectNode out=request.deepCopy();
        String query=Normalizer.normalize(out.path("query").asText(),Normalizer.Form.NFKC).strip();
        boolean exactOnly=out.path("schemaVersion").asText().equals("1.1")&&out.path("mode").asText().equals("keyword")&&out.path("filters").has("typed");
        if(query.isBlank()&&!exactOnly||query.codePointCount(0,query.length())>256||query.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("INVALID_QUERY");out.put("query",query);
        var filters=out.path("filters");
        try {
            Instant from=filters.has("updatedFrom")?Instant.parse(filters.get("updatedFrom").asText()):null,to=filters.has("updatedTo")?Instant.parse(filters.get("updatedTo").asText()):null;
            if(from!=null&&to!=null&&!from.isBefore(to))throw new IllegalArgumentException("INVALID_TIME_RANGE");
            if(out.path("task").has("expiresAt"))Instant.parse(out.path("task").path("expiresAt").asText());
        }catch(DateTimeException e){throw new IllegalArgumentException("INVALID_TIME_RANGE");}
        out.remove("cursor");return out;
    }
    static boolean matches(JsonNode item,JsonNode filters) {
        if(!TypedFilters.matches(item,filters.path("typed")))return false;
        for(var f:Map.of("sources",item.path("object").path("source").asText(),"types",item.path("type").asText()).entrySet())if(filters.has(f.getKey())){
            boolean found=false;for(var value:filters.get(f.getKey()))found|=value.asText().equals(f.getValue());if(!found)return false;
        }
        for(String f:List.of("category","status"))if(filters.has(f)&&!filters.get(f).asText().equals(item.path("fields").path(f).asText()))return false;
        Instant time=Instant.parse(item.path("updatedAt").asText());
        return !(filters.has("updatedFrom")&&time.isBefore(Instant.parse(filters.path("updatedFrom").asText())))&&!(filters.has("updatedTo")&&!time.isBefore(Instant.parse(filters.path("updatedTo").asText())));
    }
    private boolean current(JsonNode hit,LocalIdentities.Identity identity,JsonNode filters)throws Exception {
        if(users!=null){var current=users.resolve(identity);if(current==null||!current.fingerprint().equals(identity.fingerprint()))return false;}
        String key=hit.path("root_key").asText();var p=store.metadataItem(key);
        return p!=null&&p.ready()&&identity.domain().equals(p.item().path("object").path("domain").asText())&&p.guard().equals(hit.path("guard").asText())&&matches(p.item(),filters)&&store.canAccess(key,identity.principal(),false);
    }
    private List<JsonNode> validHits(List<JsonNode> raw,LocalIdentities.Identity identity,JsonNode filters)throws Exception {
        List<JsonNode> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var hit:raw)if(current(hit,identity,filters)&&seen.add(hit.path("root_key").asText()))out.add(hit);return out;
    }
    public ObjectNode search(JsonNode request,LocalIdentities.Identity identity)throws Exception {
        return search(request, identity, true);
    }
    ObjectNode searchCandidates(JsonNode request,LocalIdentities.Identity identity)throws Exception {
        return search(request, identity, false);
    }
    private ObjectNode search(JsonNode request,LocalIdentities.Identity identity,boolean retainSnapshot)throws Exception {
        if(!identity.domain().equals(domain))throw new Failure(403,"DOMAIN_FORBIDDEN");
        var normalized=normalize(request);String fingerprint=Contract.digest(normalized);int offset=0;String id;Snapshot snapshot;
        if(!request.has("cursor")) {
            if(!available)throw new Failure(503,"SEARCH_UNAVAILABLE");
            var filters=normalized.path("filters");List<String> allowed=null;Map<String,String> times=new HashMap<>();boolean completeVectors=model!=null&&completedRevision==store.metadataRevision();
            if(filters.has("typed")) {
                allowed=new ArrayList<>();var projections=store.metadata(domain);
                var base=filters.deepCopy();((ObjectNode)base).remove("typed");Map<String,Set<String>> fields=new HashMap<>();
                for(var p:projections)if(p.ready()&&matches(p.item(),base)&&store.canAccess(p.key(),identity.principal(),false))
                    p.item().path("typedFields").fields().forEachRemaining(entry->fields.computeIfAbsent(entry.getKey(),k->new HashSet<>()).add(entry.getValue().path("type").asText()));
                TypedFilters.known(filters.path("typed"),fields);
                for(var p:projections)if(p.ready()&&matches(p.item(),filters)&&store.canAccess(p.key(),identity.principal(),false))allowed.add(p.key());
            }
            String mode=normalized.path("mode").asText(),query=normalized.path("query").asText();List<List<String>> routes=new ArrayList<>();Map<String,JsonNode> selected=new HashMap<>();Map<String,List<String>> channels=new HashMap<>();List<String> reasons=new ArrayList<>();
            if(!mode.equals("vector")) {
                List<JsonNode> hits;
                try{hits=validHits(index.recall(domain,allowed,identity.principal(),filters,query,null,minimum),identity,filters);}catch(Exception e){throw new Failure(503,"KEYWORD_UNAVAILABLE");}
                routes.add(route(hits,"keyword",selected,channels));
            }
            if(!mode.equals("keyword")) {
                if(!completeVectors)reasons.add("VECTOR_INDEX_INCOMPLETE");
                try {
                    if(model==null)throw new IOException("MODEL_NOT_CONFIGURED");
                    var vector=model.encode(List.of(query)).vectors().get(0);
                    var hits=validHits(index.recall(domain,allowed,identity.principal(),filters,query,vector,minimum),identity,filters);routes.add(route(hits,"vector",selected,channels));
                } catch(Exception e){if(mode.equals("vector"))throw new Failure(503,"VECTOR_UNAVAILABLE");reasons.add("VECTOR_UNAVAILABLE");}
            }
            // Weight semantic recall above broad lexical overlap; single-route fallback keeps its normalization.
            List<Double> weights=routes.size()==2?List.of(1.0,2.0):Collections.nCopies(routes.size(),1.0);
            for(String key:selected.keySet()){var p=store.metadataItem(key);if(p!=null)times.put(key,p.item().path("updatedAt").asText());}
            List<Ranked> ranked=new ArrayList<>();for(var hit:Rrf.fuse(routes,times,weights))ranked.add(new Ranked(selected.get(hit.itemId()),hit.normalized(),List.copyOf(channels.get(hit.itemId()))));
            snapshot=new Snapshot(identity.fingerprint(),fingerprint,clock.millis()+30*60*1000L,List.copyOf(ranked),List.copyOf(reasons));id=UUID.randomUUID().toString();
            if(retainSnapshot)synchronized(snapshots){snapshots.entrySet().removeIf(e->e.getValue().expires()<clock.millis());if(snapshots.size()>=16)snapshots.remove(snapshots.keySet().iterator().next());snapshots.put(id,snapshot);}
        } else {
            String[] parts=request.path("cursor").asText().split(":",-1);
            if(parts.length!=3||!MessageDigest.isEqual(sign(parts[0]+":"+parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII),parts[2].getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Failure(400,"INVALID_CURSOR");
            id=parts[0];try{offset=Integer.parseInt(parts[1]);}catch(NumberFormatException e){throw new Failure(400,"INVALID_CURSOR");}
            synchronized(snapshots){snapshot=snapshots.get(id);}
            if(snapshot==null||snapshot.expires()<clock.millis())throw new Failure(400,"CURSOR_EXPIRED");
            if(!snapshot.owner().equals(identity.fingerprint())||!snapshot.query().equals(fingerprint)||offset<0||offset>snapshot.hits().size())throw new Failure(400,"CURSOR_MISMATCH");
        }
        var out=Contract.JSON.createObjectNode().put("schemaVersion",request.path("schemaVersion").asText()).put("requestId",UUID.randomUUID().toString()).put("candidateLimit",500).put("degraded",!snapshot.reasons().isEmpty());
        var reasons=out.putArray("degradedReasons");snapshot.reasons().forEach(reasons::add);var items=out.putArray("items");
        // Serialize against source mutations: check and construct each response under the store lock.
        double bestCosine=0;
        StringBuilder feedbackEvidence=new StringBuilder();
        synchronized(store) {
            while(offset<snapshot.hits().size()&&items.size()<normalized.path("pageSize").asInt()) {
                var ranked=snapshot.hits().get(offset++);var hit=ranked.hit();if(!current(hit,identity,normalized.path("filters")))continue;
                bestCosine=Math.max(bestCosine,hit.path("_vectorCosine").asDouble());
                ObjectNode item=store.visibleItem(hit.path("root_key").asText(),identity.principal(),request.path("schemaVersion").asText().equals("1.1"));item.set("text",hit.path("text"));item.set("location",hit.path("location"));
                var row=items.addObject().put("score",ranked.score());row.set("item",item);row.set("channels",Contract.JSON.valueToTree(ranked.channels()));row.set("reasons",Contract.JSON.valueToTree(ranked.channels()));
                if(!retainSnapshot&&hit.has("_vectorEvidence"))row.set("_vectorEvidence",hit.path("_vectorEvidence").deepCopy());
                if(!retainSnapshot)row.put("_evidenceGuard",hit.path("guard").asText());
                if(request.path("schemaVersion").asText().equals("1.1"))feedbackEvidence.append(item.path("title").asText()).append(' ').append(item.path("text").asText()).append('\n');
            }
            while(offset<snapshot.hits().size()&&!current(snapshot.hits().get(offset).hit(),identity,normalized.path("filters")))offset++;
        }
        if(request.path("schemaVersion").asText().equals("1.1"))out.put("matchStatus",
            SearchFeedback.state(normalized.path("mode").asText(),!snapshot.reasons().isEmpty(),items.size(),bestCosine,lowMatchThreshold,normalized.path("query").asText(),feedbackEvidence.toString()));
        boolean more=offset<snapshot.hits().size();out.put("hasMore",more);if(more){String value=id+":"+offset;out.put("nextCursor",value+":"+sign(value));}else {out.putNull("nextCursor");if(!request.has("cursor"))synchronized(snapshots){snapshots.remove(id);}}return out;
    }
    private List<String> route(List<JsonNode> hits,String channel,Map<String,JsonNode> selected,Map<String,List<String>> channels) {
        List<String> ids=new ArrayList<>();for(var hit:hits){String key=hit.path("root_key").asText();ids.add(key);selected.putIfAbsent(key,hit);
            if(hit.has("_vectorCosine")) {
                var kept=(ObjectNode)selected.get(key);kept.set("_vectorCosine",hit.get("_vectorCosine"));
                var evidence=Contract.JSON.createObjectNode();
                for(String field:List.of("root_key","guard","text","location"))evidence.set(field,hit.path(field).deepCopy());
                kept.set("_vectorEvidence",evidence);
            }
            channels.computeIfAbsent(key,k->new ArrayList<>()).add(channel);}return ids;
    }
    private String sign(String value){try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
