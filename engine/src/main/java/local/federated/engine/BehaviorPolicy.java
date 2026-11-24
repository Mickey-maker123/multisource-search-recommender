package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class BehaviorPolicy {
    private static final Set<String> ACTIONS = Set.of("impression", "open", "download", "consume", "save", "subscribe", "endorse", "dismiss", "not_helpful", "cite", "use");
    private final JsonNode config;

    private BehaviorPolicy(JsonNode config) {
        this.config = config;
        if (!"engine-behavior-v1".equals(config.path("version").asText())
                || !config.path("historyDays").isIntegralNumber() || !config.path("historyDays").canConvertToInt()
                || !config.path("maxHistoryEvents").isIntegralNumber() || !config.path("maxHistoryEvents").canConvertToInt()
                || !config.path("halfLifeDays").isNumber()
                || config.path("historyDays").asInt() < 1 || config.path("historyDays").asInt() > 365
                || config.path("halfLifeDays").asDouble() < 1 || config.path("halfLifeDays").asDouble() > 365
                || config.path("maxHistoryEvents").asInt() < 1 || config.path("maxHistoryEvents").asInt() > 50000
                || !config.path("consumeMinEffectiveMs").isIntegralNumber() || !config.path("consumeMinEffectiveMs").canConvertToLong()
                || config.path("consumeMinEffectiveMs").asLong() < 1 || config.path("consumeMinEffectiveMs").asLong() > 86400000
                || !config.path("consumeMinCompletion").isNumber()
                || config.path("consumeMinCompletion").asDouble() <= 0 || config.path("consumeMinCompletion").asDouble() > 1
                || !config.path("communitySources").isArray()
                || !config.path("communityCommentWeight").isNumber()
                || config.path("communityCommentWeight").asDouble() < 0 || config.path("communityCommentWeight").asDouble() > 10) {
            throw new IllegalArgumentException("INVALID_BEHAVIOR_POLICY");
        }
        for (JsonNode source : config.path("communitySources")) {
            if (!source.isTextual() || !source.asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("INVALID_BEHAVIOR_POLICY");
        }
        if (config.path("weights").size() != ACTIONS.size()) throw new IllegalArgumentException("INVALID_BEHAVIOR_POLICY");
        for (String action : ACTIONS) {
            JsonNode weight = config.path("weights").path(action);
            if (!weight.isNumber() || !Double.isFinite(weight.asDouble()) || Math.abs(weight.asDouble()) > 10) throw new IllegalArgumentException("INVALID_BEHAVIOR_POLICY");
            boolean negative = action.equals("dismiss") || action.equals("not_helpful");
            if (negative && weight.asDouble() > 0 || !negative && weight.asDouble() < 0) throw new IllegalArgumentException("INVALID_BEHAVIOR_POLARITY");
        }
        for (String factual : Set.of("impression", "open", "download")) {
            if (weight(factual) != 0) throw new IllegalArgumentException("FACTUAL_ACTION_MUST_HAVE_ZERO_INTEREST_WEIGHT");
        }
    }

    public static BehaviorPolicy load(Path override) throws IOException {
        if (override != null) {
            if (Files.size(override) > 65536) throw new IllegalArgumentException("INVALID_BEHAVIOR_POLICY");
            return new BehaviorPolicy(Contract.JSON.readTree(Files.readAllBytes(override)));
        }
        try (InputStream stream = BehaviorPolicy.class.getResourceAsStream("/behavior-policy.json")) {
            if (stream == null) throw new IOException("Missing behavior policy");
            return new BehaviorPolicy(Contract.JSON.readTree(stream));
        }
    }

    public int historyDays() { return config.path("historyDays").asInt(); }
    public double halfLifeDays() { return config.path("halfLifeDays").asDouble(); }
    public int maxHistoryEvents() { return config.path("maxHistoryEvents").asInt(); }
    public double weight(String action) { return config.path("weights").path(action).asDouble(); }
    public double commentWeight() { return config.path("communityCommentWeight").asDouble(); }
    public String version() { return config.path("version").asText(); }

    public boolean communityComment(JsonNode event, JsonNode item) {
        boolean sourceMatches = false;
        for (JsonNode source : config.path("communitySources")) sourceMatches |= source.asText().equals(event.path("source").asText());
        return sourceMatches && event.path("action").asText().equals("consume")
                && event.path("sourceAction").asText().equals("valid_comment")
                && item.path("type").asText().equals("post")
                && item.path("object").path("source").asText().equals(event.path("source").asText());
    }

    public boolean effectiveConsume(JsonNode event) {
        return event.path("effectiveMs").asLong() >= config.path("consumeMinEffectiveMs").asLong()
                || event.path("completion").asDouble() >= config.path("consumeMinCompletion").asDouble();
    }
}
