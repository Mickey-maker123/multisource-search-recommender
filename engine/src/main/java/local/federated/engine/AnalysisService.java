package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/** Exact source-state analysis under the same lock used by ingestion and user updates. */
final class AnalysisService {
    private final ItemStore store;
    private final String domain;
    private final Contract contract;
    private final UserBehaviorStore users;

    AnalysisService(ItemStore store, String domain, UserBehaviorStore users) throws IOException {
        this.store = store;
        this.users = users;
        this.domain = domain;
        this.contract = new Contract();
    }

    ObjectNode source(JsonNode ref, LocalIdentities.Identity identity) throws Exception {
        contract.validateObjectRef(ref);
        synchronized (store) {
            identity = users.resolve(identity);
            if (identity == null) throw new SearchService.Failure(401, "UNAUTHORIZED");
            String key = Contract.key(ref);
            if (!domain.equals(identity.domain()) || !domain.equals(ref.path("domain").asText())
                    || !store.canAccess(key, identity.principal(), false))
                throw new SearchService.Failure(404, "OBJECT_UNAVAILABLE");
            return Contract.JSON.createObjectNode().put("schemaVersion", "1.0")
                .put("requestId", UUID.randomUUID().toString()).set("item", store.visibleItem(key, identity.principal()));
        }
    }

    ObjectNode sourceContent(JsonNode request, LocalIdentities.Identity identity) throws Exception {
        contract.validateSourceContent(request);
        var ref = request.path("object");
        synchronized (store) {
            identity = users.resolve(identity);
            if (identity == null) throw new SearchService.Failure(401, "UNAUTHORIZED");
            String key = Contract.key(ref);
            if (!domain.equals(identity.domain()) || !domain.equals(ref.path("domain").asText())
                    || !store.canAccess(key, identity.principal(), false))
                throw new SearchService.Failure(404, "OBJECT_UNAVAILABLE");
            var projection = store.projection(key);
            if (request.path("version").asLong() != projection.item().path("version").asLong()
                    || request.has("contentGuard") && !request.path("contentGuard").asText().equals(projection.guard()))
                throw new SearchService.Failure(409, "SOURCE_CHANGED");
            int offset = request.path("offset").asInt(), limit = request.path("limit").asInt();
            boolean anchorMatched = false;
            if (request.has("anchor")) {
                var anchor = request.path("anchor");
                for (int i = 0; i < projection.parts().size(); i++) {
                    var location = projection.parts().get(i).path("location");
                    boolean matches = true;
                    for (var fields = anchor.fieldNames(); fields.hasNext();) {
                        String field = fields.next();
                        if (field.equals("charStart")) matches &= location.has("charStart") && location.has("charEnd")
                            && location.path("charStart").asLong() <= anchor.path(field).asLong()
                            && anchor.path(field).asLong() < location.path("charEnd").asLong();
                        else if (field.equals("charEnd")) matches &= location.has("charEnd") && anchor.path(field).asLong() <= location.path("charEnd").asLong();
                        else matches &= anchor.get(field).equals(location.get(field));
                    }
                    if (matches) { offset = i; anchorMatched = true; break; }
                }
            }
            var status = store.status(key);
            var result = Contract.JSON.createObjectNode().put("schemaVersion", "1.1")
                .put("requestId", UUID.randomUUID().toString()).put("version", request.path("version").asLong())
                .put("contentGuard", projection.guard()).put("state", status.path("state").asText())
                .put("code", status.path("code").asText("OK")).put("offset", offset)
                .put("totalParts", projection.parts().size());
            result.set("object", ref.deepCopy());
            if (request.has("anchor")) result.put("anchorMatched", anchorMatched);
            var parts = result.putArray("parts");
            int end = Math.min(offset + limit, projection.parts().size());
            for (int i = offset; i < end; i++) {
                var part = projection.parts().get(i);
                var entry = parts.addObject().put("index", i).put("text", part.path("text").asText());
                entry.set("location", part.path("location").deepCopy());
            }
            result.put("hasMore", end < projection.parts().size());
            if (end < projection.parts().size()) result.put("nextOffset", end);
            return result;
        }
    }

    ObjectNode analyze(JsonNode request, LocalIdentities.Identity identity) throws Exception {
        contract.validateAnalyze(request);
        if (!domain.equals(identity.domain())) throw new SearchService.Failure(403, "DOMAIN_FORBIDDEN");
        synchronized (store) {
            identity = users.resolve(identity);
            if (identity == null) throw new SearchService.Failure(401, "UNAUTHORIZED");
            String template = request.path("template").asText();
            var result = Contract.JSON.createObjectNode().put("schemaVersion", "1.0")
                .put("requestId", UUID.randomUUID().toString()).put("template", template);
            if (template.equals("relations")) {
                String key = Contract.key(request.path("item"));
                if (!domain.equals(request.path("item").path("domain").asText()) || !store.canAccess(key, identity.principal(), false))
                    throw new SearchService.Failure(404, "OBJECT_UNAVAILABLE");
                var relations = result.putArray("relations");
                Set<String> seen = new HashSet<>();
                for (var relation : store.item(key).path("relations")) {
                    if (!relation.path("kind").asText().equals("explicit")) continue;
                    var target = relation.path("target");
                    String targetKey = Contract.key(target);
                    if (domain.equals(target.path("domain").asText()) && seen.add(targetKey)
                            && store.canAccess(targetKey, identity.principal(), false)
                            && SearchService.matches(store.item(targetKey), request.path("filters"))) {
                        if (relations.size() == 100) throw new SearchService.Failure(422, "ANALYSIS_LIMIT_EXCEEDED");
                        relations.addObject().put("kind", "explicit").set("target", target);
                    }
                }
                return result;
            }
            Map<String, Integer> groups = new TreeMap<>();
            int count = 0;
            for (var metadata : store.metadata(domain)) {
                var item=metadata.item();
                if (!domain.equals(item.path("object").path("domain").asText()) || item.has("parent")
                        || Set.of("chunk", "attachment").contains(item.path("type").asText())
                        || !store.canAccess(Contract.key(item.path("object")), identity.principal(), false)
                        || !SearchService.matches(item, request.path("filters"))) continue;
                count++;
                if (template.equals("group_by")) {
                    String field = request.path("groupBy").asText();
                    String value = switch (field) {
                        case "source" -> item.path("object").path("source").asText();
                        case "type" -> item.path("type").asText();
                        default -> item.path("fields").path(field).asText();
                    };
                    // Missing values have an explicit bucket distinct from every real string.
                    String bucket = value.isEmpty() ? "missing" : "value:" + value;
                    groups.merge(bucket, 1, Integer::sum);
                    if (groups.size() > 100) throw new SearchService.Failure(422, "ANALYSIS_LIMIT_EXCEEDED");
                }
            }
            if (template.equals("count")) result.put("count", count);
            else {
                var output = result.putArray("groups");
                groups.forEach((key, value) -> output.addObject().put("key", key).put("count", value));
            }
            return result;
        }
    }
}
