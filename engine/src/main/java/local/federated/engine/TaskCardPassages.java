package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** A display-only choice over one authorized, version-checked document. */
final class TaskCardPassages {
    static final int WIDTH = 700;
    private static final Pattern WORD = Pattern.compile("\\p{IsHan}+|[\\p{L}&&[^\\p{IsHan}]][\\p{L}\\p{N}&&[^\\p{IsHan}]]*|\\p{N}+");
    private static final Pattern INLINE_CODE = Pattern.compile("(?<!`)`([^`\\r\\n]+)`(?!`)");
    private static final Set<String> STOP = Set.of("a","an","the","to","and","or","in","on","at","of","for",
        "from","by","as","with","how","use","using","is","are","be","this","that","it");
    private record Window(JsonNode part, int start, int end, Map<String,Integer> counts, int length) {}
    private final List<JsonNode> parts;
    private final List<Window> windows = new ArrayList<>();
    private final Map<String,Integer> documentFrequency = new HashMap<>();
    private double averageLength;
    final int units;

    private TaskCardPassages(List<JsonNode> parts, int units) {
        this.parts = parts;
        this.units = units;
        int totalTokens = 0;
        for (JsonNode part : parts) {
            String body = part.path("text").asText();
            for (int pos = 0; pos < body.length(); pos += WIDTH / 2) {
                int start = boundary(body, pos);
                int end = boundary(body, Math.min(body.length(), start + WIDTH));
                List<String> tokens = words(body.substring(start, end));
                var counts = frequencies(tokens);
                for (String term : counts.keySet()) documentFrequency.merge(term, 1, Integer::sum);
                windows.add(new Window(part, start, end, counts, tokens.size()));
                totalTokens += tokens.size();
                if (end == body.length()) break;
            }
        }
        if (!windows.isEmpty()) averageLength = (double)totalTokens / windows.size();
    }

    static TaskCardPassages prepare(String media, List<JsonNode> parts) {
        int units = 0;
        if ("text/markdown".equals(media) || "text/plain".equals(media)) {
            for (JsonNode part : parts) {
                String body = part.path("text").asText();
                JsonNode loc = part.path("location");
                units += body.length();
                if (units > 128 * 1024 || !characterRange(loc) ||
                    !loc.path("uri").equals(parts.get(0).path("location").path("uri")) ||
                    loc.path("charEnd").asInt() - loc.path("charStart").asInt() != body.codePointCount(0,body.length()))
                    return new TaskCardPassages(List.of(), 0);
            }
            return new TaskCardPassages(parts, units);
        }
        return new TaskCardPassages(List.of(), 0);
    }

    private static Map<String,Integer> frequencies(List<String> words) {
        Map<String,Integer> counts = new HashMap<>();
        for (String word : words) counts.merge(word, 1, Integer::sum);
        return counts;
    }

    private static List<String> words(String text) {
        String normalized = Normalizer.normalize(SearchText.terms(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var tokens = new ArrayList<String>();
        var matches = WORD.matcher(normalized);
        while (matches.find()) {
            String word = matches.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] points = word.codePoints().toArray();
                if (points.length == 1) tokens.add(word);
                for (int i = 1; i < points.length; i++) tokens.add(new String(points, i - 1, 2));
            } else if (!STOP.contains(word)) tokens.add(word);
        }
        return tokens;
    }

    // All slicing uses original UTF-16 indices. Source locations use Unicode code points.
    private static int boundary(String text, int i) {
        return i > 0 && i < text.length() && Character.isLowSurrogate(text.charAt(i))
            && Character.isHighSurrogate(text.charAt(i - 1)) ? i - 1 : i;
    }

    private double score(Map<String,Integer> counts, int length, Set<String> query) {
        double score = 0;
        for (String term : query) {
            int frequency = counts.getOrDefault(term, 0);
            if (frequency == 0) continue;
            int documents = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log1p((windows.size() - documents + .5) / (documents + .5));
            score += idf * frequency * 2.2 / (frequency + 1.2 * (.25 + .75 * length / averageLength));
        }
        return score;
    }

    JsonNode choose(String task, JsonNode old) {
        if (windows.size() < 2 || averageLength == 0) return old;
        Set<String> query = new HashSet<>(words(task));
        if (query.size() < 2) return old;
        String original = old.path("text").asText();
        String visible = original.substring(0, boundary(original, Math.min(original.length(), WIDTH)));
        var tokens = words(visible);
        double best = score(frequencies(tokens), tokens.size(), query);
        Window chosen = null;
        for (Window window : windows) {
            int hits = 0;
            boolean distinctive = false;
            for (String term : query) {
                if (window.counts().containsKey(term)) {
                    hits++;
                    if (documentFrequency.get(term) * 2 <= windows.size()) distinctive = true;
                }
            }
            if (hits < 2 || !distinctive) continue;
            double value = score(window.counts(), window.length(), query);
            if (value > best + 1e-9) {
                best = value;
                chosen = window;
            }
        }
        if (chosen == null) return old;
        String body = chosen.part().path("text").asText();
        int start = chosen.start(), end = chosen.end();
        ObjectNode loc = chosen.part().path("location").deepCopy();
        int base = loc.path("charStart").asInt();
        int first = base + body.codePointCount(0, start);
        loc.put("charStart", first).put("charEnd", first + body.codePointCount(start, end));
        return Contract.JSON.createObjectNode().put("text", body.substring(start, end)).set("location", loc);
    }

    JsonNode protectedCard(String task, JsonNode old) {
        JsonNode originalLocation = old.path("location");
        if (task.isBlank() || parts.isEmpty() || !characterRange(originalLocation)) return old;
        String original = old.path("text").asText();
        boolean found = false;
        for (JsonNode part : parts) {
            JsonNode parent = part.path("location");
            String body = part.path("text").asText();
            int count = parent.path("charEnd").asInt() - parent.path("charStart").asInt();
            if (!parent.path("uri").equals(originalLocation.path("uri"))) continue;
            int a = originalLocation.path("charStart").asInt() - parent.path("charStart").asInt();
            int b = originalLocation.path("charEnd").asInt() - parent.path("charStart").asInt();
            if (a < 0 || b <= a || b > count) continue;
            int start = body.offsetByCodePoints(0, a), end = body.offsetByCodePoints(0, b);
            if (end - start != original.length() || !body.startsWith(original, start)) continue;
            found = true;
            if (original.length() >= WIDTH) break;
            int last = end, first = start;
            if (end < body.length() && body.charAt(end - 1) != '\n' && body.charAt(end) != '\n') {
                int next = body.indexOf('\n', end);
                if (next < 0) next = body.length();
                if (next - start <= WIDTH) last = next;
            }
            if (start > 0 && body.charAt(start - 1) != '\n') {
                int prev = body.lastIndexOf('\n', start - 1) + 1;
                if (last - prev <= WIDTH) first = prev;
            }
            if (first != start || last != end) {
                ObjectNode loc = parent.deepCopy();
                int base = parent.path("charStart").asInt();
                loc.put("charStart", base + body.codePointCount(0, first));
                loc.put("charEnd", base + body.codePointCount(0, last));
                return Contract.JSON.createObjectNode().put("text", body.substring(first, last)).set("location", loc);
            }
            break;
        }
        if (!found) return old;
        JsonNode proposed = choose(task, old);
        if (proposed == old) return old;
        String replacement = proposed.path("text").asText();
        var codes = INLINE_CODE.matcher(original.substring(0, boundary(original, Math.min(WIDTH, original.length()))));
        while (codes.find()) if (!replacement.contains(codes.group(1))) return old;
        return proposed;
    }

    private static boolean characterRange(JsonNode location) {
        return location.size() == 3 && location.path("uri").isTextual() &&
            location.path("charStart").canConvertToInt() && location.path("charEnd").canConvertToInt();
    }
}
