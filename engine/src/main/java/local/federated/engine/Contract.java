package local.federated.engine;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.io.*;
import java.util.*;

final class Contract {
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JsonSchema item;
    private final JsonSchema item11;
    private final JsonSchema search;
    private final JsonSchema search11;
    private final JsonSchema user;
    private final JsonSchema event;
    private final JsonSchema recommendations;
    private final JsonSchema analyze;
    private final JsonSchema objectRef;
    private final JsonSchema sourceContent;
    Contract() throws IOException {
        try (var stream = Contract.class.getResourceAsStream("/contracts/v1/schema.json")) {
            if (stream == null) throw new IOException("Missing bundled contract");
            ObjectNode root = (ObjectNode)JSON.readTree(stream);
            root.put("$ref", "#/$defs/Item");
            item = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy();root.put("$ref","#/$defs/Search");
            search = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy();root.put("$ref","#/$defs/User");
            user = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy();root.put("$ref","#/$defs/Event");
            event = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy();root.put("$ref","#/$defs/Recommendations");
            recommendations = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy(); root.put("$ref", "#/$defs/Analyze");
            analyze = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
            root = root.deepCopy(); root.put("$ref", "#/$defs/ObjectRef");
            objectRef = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        }
        try (var stream = Contract.class.getResourceAsStream("/contracts/v1.6/source-content.schema.json")) {
            if (stream == null) throw new IOException("Missing source content contract");
            sourceContent = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(stream));
        }
        try (var stream = Contract.class.getResourceAsStream("/contracts/v1.6/content.schema.json")) {
            if (stream == null) throw new IOException("Missing extended content contract");
            item11 = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(stream));
        }
        try(var stream=Contract.class.getResourceAsStream("/contracts/v1.6/search.schema.json")) {
            if(stream==null)throw new IOException("Missing extended search contract");
            search11=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(JSON.readTree(stream));
        }
    }
    void validateSourceContent(JsonNode value) {
        if (!sourceContent.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_SOURCE_CONTENT");
        var anchor = value.path("anchor");
        if (anchor.has("charEnd") && anchor.path("charEnd").asLong() < anchor.path("charStart").asLong())
            throw new IllegalArgumentException("INVALID_LOCATION");
    }
    void validateObjectRef(JsonNode value) {
        if (!objectRef.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_OBJECT_REF");
    }
    void validateAnalyze(JsonNode value) {
        if (!analyze.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_ANALYZE");
        // Reuse the same precise filter time validation as recommendations.
        var filters = JSON.createObjectNode().put("schemaVersion", "1.0").put("kind", "personal").put("pageSize", 1);
        if (value.has("filters")) filters.set("filters", value.get("filters"));
        validateRecommendations(filters);
    }
    void validateSearch(JsonNode value){
        if(!(value.path("schemaVersion").asText().equals("1.1")?search11:search).validate(value).isEmpty())throw new IllegalArgumentException("INVALID_SEARCH");
        TypedFilters.validate(value.path("filters").path("typed"));
    }
    void validateUser(JsonNode value) {
        if (!user.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_USER");
        for (String field : List.of("preferences", "subscriptions")) {
            for (JsonNode term : value.path(field)) {
                if (term.asText().isBlank() || term.asText().codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("INVALID_PREFERENCE");
                }
            }
        }
    }
    void validateEvent(JsonNode value) {
        if (!event.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_EVENT");
        try {
            java.time.Instant.parse(value.path("occurredAt").asText());
            if (value.has("task")) java.time.Instant.parse(value.path("task").path("expiresAt").asText()).toEpochMilli();
        } catch (java.time.DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("INVALID_TIME");
        }
    }
    void validateRecommendations(JsonNode value) {
        if (!recommendations.validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_RECOMMENDATIONS");
        try {
            JsonNode filters = value.path("filters");
            java.time.Instant from = filters.has("updatedFrom") ? java.time.Instant.parse(filters.get("updatedFrom").asText()) : null;
            java.time.Instant to = filters.has("updatedTo") ? java.time.Instant.parse(filters.get("updatedTo").asText()) : null;
            if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("INVALID_TIME_RANGE");
            if (value.has("task")) java.time.Instant.parse(value.path("task").path("expiresAt").asText()).toEpochMilli();
        } catch (java.time.DateTimeException | ArithmeticException error) {
            throw new IllegalArgumentException("INVALID_TIME");
        }
    }
    void validateItem(JsonNode value) {
        if (!(value.has("schemaVersion") ? item11 : item).validate(value).isEmpty()) throw new IllegalArgumentException("INVALID_ITEM");
        for(var field:value.path("typedFields")) {
            String type=field.path("type").asText();var raw=field.path("value");
            if(type.equals("decimal")&&!raw.isTextual()||type.equals("boolean")&&!raw.isBoolean()
                    ||Set.of("string","date").contains(type)&&!raw.isTextual())throw new IllegalArgumentException("INVALID_FIELD_TYPE");
            TableMapping.value(type,raw.asText());
        }
        try { java.time.OffsetDateTime.parse(value.path("updatedAt").asText()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("INVALID_TIME"); }
        var location = value.path("location");
        if (location.has("charEnd") && location.path("charEnd").asLong() < location.path("charStart").asLong())
            throw new IllegalArgumentException("INVALID_LOCATION");
        if (!value.path("deleted").asBoolean()) {
            var uri = java.net.URI.create(location.path("uri").asText());
            boolean source="engine-source".equals(uri.getScheme())&&value.path("object").path("domain").asText().equals(uri.getRawAuthority());
            boolean web="https".equals(uri.getScheme())&&uri.getHost()!=null;
            if ((!source&&!web) || uri.getUserInfo() != null)
                throw new IllegalArgumentException("INVALID_LOCATION");
        }
    }
    static String key(JsonNode ref) { return ref.path("domain").asText()+":"+ref.path("source").asText()+":"+ref.path("nativeId").asText(); }
    static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var out = JSON.createObjectNode();
            var names = new TreeSet<String>(); value.fieldNames().forEachRemaining(names::add);
            names.forEach(k -> out.set(k, canonical(value.get(k)))); return out;
        }
        if (value.isArray()) {
            var out = JSON.createArrayNode(); value.forEach(v -> out.add(canonical(v))); return out;
        }
        return value;
    }
    static String digest(JsonNode value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(canonical(value)))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
