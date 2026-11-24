package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RecommendationService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private final ItemStore store;
    private final UserBehaviorStore users;
    private final SearchService search;
    private final boolean taskCards;
    private final String domain;
    private final Contract contract = new Contract();
    private final byte[] secret = new byte[32];
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private record CachedPassages(String guard,List<Passage> passages) {}
    private final Map<String,CachedPassages> passageCache=new LinkedHashMap<>(64,.75f,true);
    private int cachedPassageCount;
    private record CardDocument(String guard, TaskCardPassages passages) {}
    // Accessed only after the per-page ACL/guard check, under the store lock.
    private final Map<String,CardDocument> cardDocuments = new LinkedHashMap<>(32, .75f, true);
    private int cardDocumentUnits;

    record Interest(String source, String category, Set<String> tokens, double weight, Set<String> actions, String title) {}
    record Profile(JsonNode user, List<Interest> interests, Set<String> dismissed, String revision) {}
    private record Candidate(String key, String guard, double score, List<String> channels, List<String> reasons, JsonNode part) {}
    private record Passage(JsonNode part,String text,Set<String> tokens) {}
    private record Snapshot(String owner, String request, String profile, long expires, List<Candidate> candidates, List<String> degraded) {}

    private static final class Topic {
        final String source;
        final String category;
        final String title;
        final Set<String> tokens = new LinkedHashSet<>();
        final Set<String> actions = new TreeSet<>();
        double weight;
        Topic(String source, String category, String title) { this.source = source; this.category = category; this.title = title; }
        Interest interest() { return new Interest(source, category, Set.copyOf(tokens), weight, Set.copyOf(actions), title); }
    }

    public RecommendationService(ItemStore store, UserBehaviorStore users, SearchService search, String domain) throws IOException {
        this(store, users, search, domain, System.getenv().getOrDefault("ENGINE_TASK_CARD_STRATEGY", "task-bm25"));
    }

    RecommendationService(ItemStore store, UserBehaviorStore users, SearchService search, String domain, String cardStrategy) throws IOException {
        this.store = store;
        this.users = users;
        this.search = search;
        this.domain = domain;
        this.taskCards = switch (cardStrategy) {
            case "legacy" -> false;
            case "task-bm25" -> true;
            default -> throw new IllegalArgumentException("ENGINE_TASK_CARD_STRATEGY must be legacy or task-bm25");
        };
        new SecureRandom().nextBytes(secret);
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip();
    }

    static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher words = WORD.matcher(normalize(text));
        while (words.find() && result.size() < 512) {
            String word = words.group();
            if (word.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) result.add(word);
                for (int i = 0; i + 1 < points.length && result.size() < 512; i++) result.add(new String(points, i, 2));
            } else {
                result.add(word);
            }
        }
        return Set.copyOf(result);
    }

    private static String prefix(String text, int limit) {
        int size = text.codePointCount(0, text.length());
        return size <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private String content(ItemStore.Projection projection) {
        String body = projection.parts().isEmpty() ? "" : projection.parts().get(0).path("text").asText();
        return normalize(projection.item().path("title").asText() + " " + prefix(body, 4000));
    }

    private List<Passage> passages(ItemStore.Projection projection) {
        synchronized(passageCache){
            var cached=passageCache.get(projection.key());
            if(cached!=null&&cached.guard().equals(projection.guard()))return cached.passages();
        }
        List<Passage> result=new ArrayList<>();
        for(JsonNode original:projection.parts()) {
            String body=original.path("text").asText();int count=body.codePointCount(0,body.length());
            // Bounded snippets with overlap: score every part, not only the document's opening.
            for(int start=0;start<count;start+=500) {
                int end=Math.min(start+600,count);
                String piece=body.substring(body.offsetByCodePoints(0,start),body.offsetByCodePoints(0,end));
                var part=Contract.JSON.createObjectNode().put("text",piece);
                ObjectNode location=original.path("location").deepCopy();
                if(location.has("charStart")) {
                    int base=location.path("charStart").asInt();location.put("charStart",base+start).put("charEnd",base+end);
                }
                part.set("location",location);result.add(passage(projection,part));
                if(end==count)break;
            }
        }
        List<Passage> frozen=List.copyOf(result);
        synchronized(passageCache){
            var previous=passageCache.remove(projection.key());
            if(previous!=null)cachedPassageCount-=previous.passages().size();
            if(frozen.size()<=4000){
                passageCache.put(projection.key(),new CachedPassages(projection.guard(),frozen));cachedPassageCount+=frozen.size();
            }
            while(passageCache.size()>256||cachedPassageCount>4000){
                cachedPassageCount-=passageCache.remove(passageCache.keySet().iterator().next()).passages().size();
            }
        }
        return frozen;
    }

    private static Passage passage(ItemStore.Projection projection,JsonNode part) {
        String text=normalize(projection.item().path("title").asText()+" "+part.path("text").asText());
        return new Passage(part,text,tokens(text));
    }

    private static double affinity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        int shared = 0;
        for (String term : left) if (right.contains(term)) shared++;
        return shared / (double) (left.size() + right.size() - shared);
    }

    Profile profile(LocalIdentities.Identity identity) throws Exception {
        synchronized (store) {
            JsonNode user = users.user(domain, identity.principal().user());
            var evidence = Contract.JSON.createObjectNode();
            evidence.set("user", user == null ? Contract.JSON.nullNode() : user);
            var used = evidence.putArray("events");
            Set<String> dismissed = new HashSet<>();
            if (user == null || !user.path("allowBehaviorProfile").asBoolean()) {
                return new Profile(user, List.of(), Set.of(), Contract.digest(evidence));
            }
            List<UserBehaviorStore.EventFact> facts = users.recentEvents(domain, identity.principal().user());
            Set<String> undone = new HashSet<>();
            for (var fact : facts) {
                JsonNode event = fact.event();
                if (event.path("action").asText().equals("undo")) undone.add(event.path("source").asText() + ":" + event.path("undoOf").asText());
            }
            Map<String, UserBehaviorStore.EventFact> stateSignals = new LinkedHashMap<>();
            Map<String, ItemStore.Projection> references = new HashMap<>();
            List<UserBehaviorStore.EventFact> effective = new ArrayList<>();
            for (var fact : facts) {
                JsonNode event = fact.event();
                String action = event.path("action").asText();
                if (!fact.profileAllowed() || action.equals("undo") || undone.contains(event.path("source").asText() + ":" + event.path("eventId").asText())) continue;
                String objectKey = Contract.key(event.path("object"));
                if (!references.containsKey(objectKey)) {
                    ItemStore.Projection item = store.projection(objectKey);
                    boolean visible = item != null && item.ready() && store.canAccess(objectKey, identity.principal(), false);
                    references.put(objectKey, visible ? item : null);
                }
                ItemStore.Projection attributed = references.get(objectKey);
                if (attributed == null || fact.objectVersion() != attributed.item().path("version").asLong() || !attributed.guard().equals(fact.objectGuard())) continue;
                if (Set.of("save", "subscribe", "endorse").contains(action)) {
                    stateSignals.put(event.path("source").asText() + ":" + Contract.key(event.path("object")) + ":" + action, fact);
                } else {
                    effective.add(fact);
                }
            }
            effective.addAll(stateSignals.values());
            effective.sort(Comparator.comparing((UserBehaviorStore.EventFact fact) -> Instant.parse(fact.event().path("occurredAt").asText()))
                    .thenComparing(fact -> fact.event().path("source").asText()).thenComparing(fact -> fact.event().path("eventId").asText()));
            Map<String, Integer> commentsPerDay = new HashMap<>();
            Map<String, Topic> topics = new TreeMap<>();
            for (var fact : effective) {
                JsonNode event = fact.event();
                String action = event.path("action").asText();
                String key = Contract.key(event.path("object"));
                ItemStore.Projection item = references.get(key);
                boolean comment = users.policy().communityComment(event, item.item());
                double weight = users.policy().weight(action);
                if (comment) {
                    String day = Instant.parse(event.path("occurredAt").asText()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
                    String bucket = event.path("source").asText() + ":" + key + ":" + day;
                    if (commentsPerDay.merge(bucket, 1, Integer::sum) > 3) continue;
                    weight = users.policy().commentWeight();
                } else if (action.equals("consume") && !users.policy().effectiveConsume(event)) {
                    weight = 0;
                }
                if (weight == 0) continue;
                used.addObject().put("event", Contract.digest(event)).put("object", item.guard());
                if (action.equals("dismiss") || action.equals("not_helpful")) dismissed.add(key);
                double ageDays = (users.clock().millis() - Instant.parse(event.path("occurredAt").asText()).toEpochMilli()) / 86400000.0;
                weight *= Math.pow(0.5, ageDays / users.policy().halfLifeDays());
                String source = item.item().path("object").path("source").asText();
                String category = item.item().path("fields").path("category").asText();
                Set<String> terms = tokens(content(item));
                String topicKey = category.isBlank() ? "content:" + Contract.digest(Contract.JSON.valueToTree(new TreeSet<>(terms))) : source + ":" + category;
                Topic topic = topics.computeIfAbsent(topicKey, ignored -> new Topic(source, category, item.item().path("title").asText()));
                topic.weight += weight;
                topic.actions.add(comment ? "valid_comment" : action);
                for (String term : new TreeSet<>(terms)) if (topic.tokens.size() < 512) topic.tokens.add(term);
            }
            List<Interest> interests = topics.values().stream().map(Topic::interest).filter(interest -> interest.weight() != 0)
                    .sorted(Comparator.comparingDouble((Interest interest) -> Math.abs(interest.weight())).reversed()
                            .thenComparing(Interest::source).thenComparing(Interest::category)).limit(64).toList();
            return new Profile(user, interests, Set.copyOf(dismissed), Contract.digest(evidence));
        }
    }

    private ItemStore.Projection relatedSeed(JsonNode request, LocalIdentities.Identity identity) throws Exception {
        if (!request.path("kind").asText().equals("related")) return null;
        if (!domain.equals(request.path("item").path("domain").asText())) throw new SearchService.Failure(404, "ITEM_UNAVAILABLE");
        String key = Contract.key(request.path("item"));
        ItemStore.Projection item = store.projection(key);
        if (item == null || !item.ready() || !store.canAccess(key, identity.principal(), false)) throw new SearchService.Failure(404, "ITEM_UNAVAILABLE");
        return item;
    }

    private Map<String, JsonNode> semanticSeeds(String query, JsonNode filters, LocalIdentities.Identity identity, Set<String> degraded) throws Exception {
        if (query.isBlank()) return Map.of();
        if (search == null || !search.available()) {
            degraded.add("SEMANTIC_RECOMMENDATION_UNAVAILABLE");
            return Map.of();
        }
        ObjectNode request = Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("query", prefix(query.replaceAll("\\s+", " ").strip(), 256)).put("mode", "hybrid").put("pageSize", 50);
        if (!filters.isMissingNode()) request.set("filters", filters);
        try {
            JsonNode result = search.searchCandidates(request, identity);
            result.path("degradedReasons").forEach(reason -> degraded.add(reason.asText()));
            Map<String, JsonNode> seeds = new HashMap<>();
            for (JsonNode row : result.path("items")) seeds.put(Contract.key(row.path("item").path("object")), row);
            return seeds;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception error) {
            degraded.add("SEMANTIC_RECOMMENDATION_UNAVAILABLE");
            return Map.of();
        }
    }

    public ObjectNode recommend(JsonNode request, LocalIdentities.Identity authenticated) throws Exception {
        contract.validateRecommendations(request);
        if (!domain.equals(authenticated.domain())) throw new SearchService.Failure(403, "DOMAIN_FORBIDDEN");
        LocalIdentities.Identity identity = users.resolve(authenticated);
        if (identity == null) throw new SearchService.Failure(401, "UNAUTHORIZED");
        Profile profile = profile(identity);
        ItemStore.Projection reference = relatedSeed(request, identity);
        ObjectNode normalized = request.deepCopy();
        normalized.remove("cursor");
        String binding = Contract.digest(normalized);
        String context = profile.revision() + (reference == null ? "" : reference.guard());
        boolean taskActive = request.has("task") && Instant.parse(request.path("task").path("expiresAt").asText()).toEpochMilli() > users.clock().millis();
        String task = taskActive ? request.path("task").path("text").asText() : "";
        String snapshotId;
        int offset = 0;
        Snapshot snapshot;
        if (request.has("cursor")) {
            String[] pieces = request.path("cursor").asText().split(":", -1);
            if (pieces.length != 3 || !MessageDigest.isEqual(signature(pieces[0] + ":" + pieces[1]).getBytes(StandardCharsets.US_ASCII), pieces[2].getBytes(StandardCharsets.US_ASCII))) throw new SearchService.Failure(400, "INVALID_CURSOR");
            snapshotId = pieces[0];
            try { offset = Integer.parseInt(pieces[1]); }
            catch (NumberFormatException error) { throw new SearchService.Failure(400, "INVALID_CURSOR"); }
            synchronized (snapshots) { snapshot = snapshots.get(snapshotId); }
            if (snapshot == null || snapshot.expires() < users.clock().millis()) throw new SearchService.Failure(400, "CURSOR_EXPIRED");
            if (!snapshot.owner().equals(identity.fingerprint()) || !snapshot.request().equals(binding) || !snapshot.profile().equals(context)
                    || offset < 0 || offset > snapshot.candidates().size()) throw new SearchService.Failure(400, "CURSOR_MISMATCH");
        } else {
            List<String> terms = new ArrayList<>();
            if (!task.isBlank()) terms.add(task);
            if (profile.user() != null) {
                profile.user().path("preferences").forEach(term -> terms.add(term.asText()));
                profile.user().path("subscriptions").forEach(term -> terms.add(term.asText().replace(':', ' ')));
            }
            for (Interest interest : profile.interests()) if (interest.weight() > 0 && terms.size() < 3) terms.add(interest.title());
            String query = reference == null ? normalize(String.join(" ", terms)) : content(reference);
            List<Passage> referencePassages=reference==null?List.of():passages(reference);
            Set<String> degraded = new LinkedHashSet<>();
            Map<String, JsonNode> seeds = semanticSeeds(query, request.path("filters"), identity, degraded);
            List<Candidate> candidates = new ArrayList<>();
            Set<String> taskTokens = tokens(task);
            Map<String,String> times=new HashMap<>();
            synchronized (store) {
                Set<String> lookup=new HashSet<>();taskTokens.forEach(t->lookup.add("token:"+t));
                if(reference!=null)lookup.add("category:"+reference.item().path("object").path("source").asText()+":"+reference.item().path("fields").path("category").asText());
                if(profile.user()!=null) {
                    profile.user().path("subscriptions").forEach(t->lookup.add(t.asText()));
                    for(var preference:profile.user().path("preferences")) {
                        String text=normalize(preference.asText());if(!text.isEmpty())lookup.add("char:"+text.codePointAt(0));
                    }
                }
                var eligible=store.shortlist(domain,lookup,seeds.keySet(),p->p.ready()
                    &&!profile.dismissed().contains(p.key())&&(reference==null||!p.key().equals(reference.key()))
                    &&SearchService.matches(p.item(),request.path("filters"))&&store.canAccess(p.key(),identity.principal(),false),
                    p->shortlistScore(p,profile,reference,taskTokens,seeds),500);
                for(var metadata:eligible) {
                    times.put(metadata.key(),metadata.item().path("updatedAt").asText());
                    var item=store.projection(metadata.key());
                    Candidate candidate = score(item, profile, reference, referencePassages, taskTokens, seeds.get(item.key()));
                    if (candidate != null) candidates.add(candidate);
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(candidate -> Instant.parse(times.get(candidate.key())), Comparator.reverseOrder()).thenComparing(Candidate::key));
            long expires = users.clock().millis() + 1800000;
            if (taskActive) expires = Math.min(expires, Instant.parse(request.path("task").path("expiresAt").asText()).toEpochMilli());
            snapshot = new Snapshot(identity.fingerprint(), binding, context, expires, candidates.stream().limit(500).toList(), List.copyOf(degraded));
            snapshotId = UUID.randomUUID().toString();
            synchronized (snapshots) {
                snapshots.entrySet().removeIf(entry -> entry.getValue().expires() < users.clock().millis());
                if (snapshots.size() >= 16) snapshots.remove(snapshots.keySet().iterator().next());
                snapshots.put(snapshotId, snapshot);
            }
        }
        ObjectNode response = Contract.JSON.createObjectNode().put("schemaVersion", "1.0").put("requestId", UUID.randomUUID().toString()).put("candidateLimit", 500).put("degraded", !snapshot.degraded().isEmpty());
        response.set("degradedReasons", Contract.JSON.valueToTree(snapshot.degraded()));
        var items = response.putArray("items");
        synchronized (store) {
            LocalIdentities.Identity current = users.resolve(authenticated);
            if (current == null) throw new SearchService.Failure(401, "UNAUTHORIZED");
            Profile latest = profile(current);
            ItemStore.Projection latestReference = relatedSeed(request, current);
            String latestContext = latest.revision() + (latestReference == null ? "" : latestReference.guard());
            if (!current.fingerprint().equals(identity.fingerprint()) || !context.equals(latestContext)) throw new SearchService.Failure(409, "RECOMMENDATION_CONTEXT_CHANGED");
            while (offset < snapshot.candidates().size() && items.size() < request.path("pageSize").asInt()) {
                Candidate candidate = snapshot.candidates().get(offset++);
                ItemStore.Projection item = currentCandidate(candidate, current, request.path("filters"));
                if (item == null) continue;
                ObjectNode visible = store.visibleItem(item.key(), current.principal());
                JsonNode part = candidate.part();
                String media = item.item().path("mediaType").asText();
                if (taskCards && !task.isBlank() && "personal".equals(request.path("kind").asText())
                        && ("text/markdown".equals(media) || "text/plain".equals(media))) {
                    part = displayCard(item, task, part);
                }
                visible.set("text", part.path("text"));
                visible.set("location", part.path("location"));
                ObjectNode row = items.addObject().put("score", candidate.score());
                row.set("item", visible);
                row.set("channels", Contract.JSON.valueToTree(candidate.channels()));
                row.set("reasons", Contract.JSON.valueToTree(candidate.reasons()));
            }
            while (offset < snapshot.candidates().size() && currentCandidate(snapshot.candidates().get(offset), current, request.path("filters")) == null) offset++;
        }
        boolean more = offset < snapshot.candidates().size();
        response.put("hasMore", more);
        if (more) {
            String value = snapshotId + ":" + offset;
            response.put("nextCursor", value + ":" + signature(value));
        } else {
            response.putNull("nextCursor");
            if (!request.has("cursor")) synchronized (snapshots) { snapshots.remove(snapshotId); }
        }
        return response;
    }

    private JsonNode displayCard(ItemStore.Projection item, String task, JsonNode old) throws Exception {
        CardDocument cached = cardDocuments.get(item.key());
        if (cached != null && cached.guard().equals(item.guard())) return cached.passages().protectedCard(task,old);
        var source = store.projection(item.key());
        if (source == null || !source.ready() || !item.guard().equals(source.guard())) return old;
        var passages = TaskCardPassages.prepare(source.item().path("mediaType").asText(), source.parts());
        if (cached != null) cardDocumentUnits -= cached.passages().units;
        cardDocuments.put(item.key(), new CardDocument(source.guard(), passages));
        cardDocumentUnits += passages.units;
        var entries = cardDocuments.entrySet().iterator();
        while (cardDocumentUnits > 512 * 1024 || cardDocuments.size() > 64) {
            cardDocumentUnits -= entries.next().getValue().passages().units;
            entries.remove();
        }
        return passages.protectedCard(task,old);
    }

    private ItemStore.Projection currentCandidate(Candidate candidate, LocalIdentities.Identity identity, JsonNode filters) throws Exception {
        ItemStore.Projection item = store.metadataItem(candidate.key());
        if (item == null || !item.ready() || !item.guard().equals(candidate.guard()) || !SearchService.matches(item.item(), filters)
                || !store.canAccess(item.key(), identity.principal(), false)) return null;
        return item;
    }

    private double shortlistScore(ItemStore.Projection p,Profile profile,ItemStore.Projection reference,Set<String> task,Map<String,JsonNode> seeds) {
        if(seeds.containsKey(p.key()))return 100+seeds.get(p.key()).path("score").asDouble();
        String source=p.item().path("object").path("source").asText(),category=p.item().path("fields").path("category").asText();
        String title=normalize(p.item().path("title").asText());double score=affinity(task,tokens(title));
        if(reference!=null&&!category.isBlank()&&source.equals(reference.item().path("object").path("source").asText())&&category.equals(reference.item().path("fields").path("category").asText()))score+=5;
        if(profile.user()!=null){
            for(var term:profile.user().path("preferences"))if(title.contains(normalize(term.asText())))score+=5;
            for(var term:profile.user().path("subscriptions"))if(term.asText().equals("source:"+source)||term.asText().equals("category:"+source+":"+category))score+=6;
        }
        return score;
    }

    private Candidate score(ItemStore.Projection item,Profile profile,ItemStore.Projection reference,List<Passage> referencePassages,Set<String> task,JsonNode seed) {
        if(seed!=null&&!item.guard().equals(seed.path("_evidenceGuard").asText()))seed=null;
        List<Passage> pieces=new ArrayList<>(passages(item));
        double negative=0;List<String> negativeReasons=new ArrayList<>();
        for(Interest interest:profile.interests())if(interest.weight()<0) {
            boolean category=!interest.category().isBlank()&&interest.source().equals(item.item().path("object").path("source").asText())&&interest.category().equals(item.item().path("fields").path("category").asText());
            double match=category?1:pieces.stream().mapToDouble(p->affinity(p.tokens(),interest.tokens())).max().orElse(0);
            if(match>=.12){negative+=interest.weight()*match;negativeReasons.add("behavior:"+String.join(",",new TreeSet<>(interest.actions()))+":"+interest.source()+":"+interest.category());}
        }
        JsonNode vector=seed==null?null:seed.get("_vectorEvidence");
        if(vector!=null&&(!item.key().equals(vector.path("root_key").asText())||!item.guard().equals(vector.path("guard").asText())))vector=null;
        if(vector!=null)pieces.add(passage(item,vector));
        JsonNode keyword=vector==null&&seed!=null?seed.path("item"):null;
        if(keyword!=null)pieces.add(passage(item,keyword));
        Candidate best=null;
        for(Passage piece:pieces) {
            boolean vectorPiece=vector!=null&&piece.part()==vector;
            boolean keywordPiece=keyword!=null&&piece.part()==keyword;
            Candidate candidate=scorePassage(item,piece,profile,reference,referencePassages,task,vectorPiece||keywordPiece?seed:null,vectorPiece,negative,negativeReasons);
            if(candidate!=null&&(best==null||candidate.score()>best.score()))best=candidate;
        }
        return best;
    }

    private Candidate scorePassage(ItemStore.Projection item,Passage passage,Profile profile,ItemStore.Projection reference,List<Passage> referencePassages,Set<String> task,JsonNode semantic,boolean vectorEvidence,double negative,List<String> negativeReasons) {
        String text = passage.text();
        Set<String> itemTokens = passage.tokens();
        String source = item.item().path("object").path("source").asText();
        String category = item.item().path("fields").path("category").asText();
        double score = 0;
        Set<String> channels = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        if (reference != null) {
            double related = referencePassages.stream().mapToDouble(p->affinity(itemTokens,p.tokens())).max().orElse(0);
            boolean sameCategory = source.equals(reference.item().path("object").path("source").asText()) && !category.isBlank()
                    && category.equals(reference.item().path("fields").path("category").asText());
            if (!sameCategory && related < 0.12 && semantic == null) return null;
            score += sameCategory ? 5 : 4 * related;
            channels.add("related");
            reasons.add(sameCategory ? "related:source_category:" + category : "related:content");
        } else if (profile.user() != null) {
            for (JsonNode term : profile.user().path("preferences")) {
                double match = text.contains(normalize(term.asText())) ? 1 : affinity(tokens(term.asText()), itemTokens);
                if (match > 0) { score += 5 * match; channels.add("preference"); reasons.add("preference:" + term.asText()); }
            }
            for (JsonNode term : profile.user().path("subscriptions")) {
                String subscription = Normalizer.normalize(term.asText(), Normalizer.Form.NFKC).strip();
                boolean match = subscription.equals("source:" + source) || subscription.equals("category:" + source + ":" + category)
                        || !subscription.contains(":") && text.contains(normalize(subscription));
                if (match) { score += 6; channels.add("preference"); reasons.add("subscription:" + term.asText()); }
            }
            double behavior = negative;
            reasons.addAll(negativeReasons);
            for (Interest interest : profile.interests()) {
                if(interest.weight()<=0)continue;
                boolean sameCategory = !category.isBlank() && source.equals(interest.source()) && category.equals(interest.category());
                double match = sameCategory ? 1 : affinity(itemTokens, interest.tokens());
                if (match < 0.12) continue;
                behavior += interest.weight() * match;
                reasons.add("behavior:" + String.join(",", new TreeSet<>(interest.actions())) + ":" + interest.source() + ":" + interest.category());
            }
            if (behavior != 0) { score += Math.copySign(Math.min(8, Math.log1p(Math.abs(behavior)) * 2), behavior); channels.add("preference"); }
        }
        double taskMatch = affinity(task, itemTokens);
        if (taskMatch > 0) { score += taskMatch; channels.add("task"); reasons.add("task:current"); }
        if (semantic != null) {
            score += (reference == null ? (task.isEmpty()?0.25:8) : 3) * semantic.path("score").asDouble();
            channels.add(vectorEvidence?"vector":"keyword");
            reasons.add(vectorEvidence?"semantic:current_query":"keyword:current_query");
        }
        if (reasons.isEmpty()) reasons.add("fallback:recent_authorized_content");
        return new Candidate(item.key(), item.guard(), score, List.copyOf(channels), reasons.stream().distinct().limit(20).toList(),passage.part().deepCopy());
    }

    private String signature(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
