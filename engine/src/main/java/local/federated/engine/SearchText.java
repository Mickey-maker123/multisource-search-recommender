package local.federated.engine;

/** Supplemental lexical view; the original source text and vector inputs remain unchanged. */
final class SearchText {
    private SearchText() {}
    static String terms(String text) {
        return text.replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
            .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
            .replaceAll("[._:/\\\\-]+", " ");
    }
}
