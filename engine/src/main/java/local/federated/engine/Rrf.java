package local.federated.engine;

import java.util.*;
import java.time.Instant;

/** Adapted from baseline Rrf: k=60 and active-route normalization; opaque item IDs. */
public final class Rrf {
    private Rrf() {}
    public record Hit(String itemId, double score, double normalized) {}
    public static List<Hit> fuse(List<List<String>> activeRoutes, Map<String, String> updatedTimes) {
        return fuse(activeRoutes, updatedTimes, activeRoutes == null ? List.of() : Collections.nCopies(activeRoutes.size(), 1.0));
    }
    public static List<Hit> fuse(List<List<String>> activeRoutes, Map<String, String> updatedTimes, List<Double> weights) {
        if (activeRoutes == null || activeRoutes.isEmpty()) return List.of();
        if (weights.size() != activeRoutes.size() || weights.stream().anyMatch(w -> w == null || !Double.isFinite(w) || w <= 0))
            throw new IllegalArgumentException("INVALID_RRF_WEIGHTS");
        Map<String, Double> scores = new HashMap<>();
        for (int routeIndex = 0; routeIndex < activeRoutes.size(); routeIndex++) {
            var route = activeRoutes.get(routeIndex);
            double weight = weights.get(routeIndex);
            Set<String> seen = new HashSet<>(); int rank = 0;
            for (String id : route) {
                if (!seen.add(id)) continue;
                scores.merge(id, weight / (60 + ++rank), Double::sum);
            }
        }
        double maximum = weights.stream().mapToDouble(Double::doubleValue).sum() / 61.0;
        return scores.entrySet().stream().map(e -> new Hit(e.getKey(), e.getValue(), e.getValue() / maximum))
            .sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> updatedTimes.containsKey(h.itemId()) ? Instant.parse(updatedTimes.get(h.itemId())) : Instant.MIN, Comparator.reverseOrder())
                .thenComparing(Hit::itemId)).toList();
    }
}
