package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** Operator-approved local roots; HTTP callers can select IDs, never supply paths. */
final class DirectorySources {
    record Source(String id, String label, Path directory, JsonNode acl, boolean parseAllowed,
                  ItemStore.Principal parser, boolean deleteMissing, int interval, int maxFiles,
                  String guard, JsonNode tableMapping) {}

    static Map<String, Source> load(Path config) throws Exception {
        var value = IngestCli.read(config);
        fields(value, Set.of("schemaVersion", "sources"));
        if (!value.path("schemaVersion").isTextual() || !value.path("schemaVersion").asText().equals("1.0") || !value.path("sources").isArray()
                || value.path("sources").size() > 32) throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG");
        Map<String, Source> result = new LinkedHashMap<>();
        for (var row : value.path("sources")) {
            fields(row, Set.of("sourceId", "label", "directory", "acl", "parseAllowed", "parseAs", "deleteMissing", "scanIntervalSeconds", "maxFiles", "tableMapping"));
            String id = identifier(row.path("sourceId"));
            String label = row.path("label").asText();
            if (!row.path("label").isTextual() || label.isBlank() || label.length() > 128
                    || !row.path("directory").isTextual() || !row.path("parseAllowed").isBoolean())
                throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG");
            Path root = Path.of(row.path("directory").asText()).normalize();
            if (!root.isAbsolute()) throw new IllegalArgumentException("DIRECTORY_MUST_BE_ABSOLUTE");
            var acl = row.path("acl");fields(acl, Set.of("public", "users", "groups"));
            if (!acl.path("public").isBoolean()) throw new IllegalArgumentException("INVALID_DIRECTORY_ACL");
            identifiers(acl.path("users"));identifiers(acl.path("groups"));
            var parser = row.path("parseAs");fields(parser, Set.of("user", "groups"));
            var principal = new ItemStore.Principal(identifier(parser.path("user")), identifiers(parser.path("groups")));
            boolean delete = false;
            if (row.has("deleteMissing")) {
                if (!row.path("deleteMissing").isBoolean()) throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG");
                delete = row.path("deleteMissing").asBoolean();
            }
            int interval = number(row, "scanIntervalSeconds", 60, 0, 86400);
            if (interval > 0 && interval < 60) throw new IllegalArgumentException("SCAN_INTERVAL_TOO_SHORT");
            int maxFiles = number(row, "maxFiles", 10000, 1, 10000);
            if(row.has("tableMapping"))new TableMapping(row.get("tableMapping"));
            if (result.put(id, new Source(id, label, root, acl.deepCopy(), row.path("parseAllowed").asBoolean(),
                    principal, delete, interval, maxFiles, Contract.digest(row), row.has("tableMapping")?row.get("tableMapping").deepCopy():null)) != null)
                throw new IllegalArgumentException("DUPLICATE_DIRECTORY_SOURCE");
        }
        return Map.copyOf(result);
    }
    private static int number(JsonNode row, String key, int fallback, int min, int max) {
        if (!row.has(key)) return fallback;
        if (!row.path(key).canConvertToInt() || !row.path(key).isIntegralNumber()
                || row.path(key).asInt() < min || row.path(key).asInt() > max)
            throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG");
        return row.path(key).asInt();
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG");
        node.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw new IllegalArgumentException("INVALID_DIRECTORY_CONFIG"); });
    }
    private static String identifier(JsonNode node) {
        if (!node.isTextual() || !node.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("INVALID_DIRECTORY_ID");
        return node.asText();
    }
    private static Set<String> identifiers(JsonNode node) {
        if (!node.isArray() || node.size() > 100) throw new IllegalArgumentException("INVALID_DIRECTORY_ACL");
        Set<String> values = new HashSet<>();
        for (var item : node) if (!values.add(identifier(item))) throw new IllegalArgumentException("DUPLICATE_DIRECTORY_ID");
        return Set.copyOf(values);
    }
}
