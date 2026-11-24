package local.federated.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Java business code supplies its own local URL, key and measured timeout budget. */
public final class EmbeddingClient {
    public static final String MODEL="bge-m3:5617a9f61b028005a4858fdac845db406aefb181:dense-fp32:maxlen-512";
    public static final String POLICY="engine-text-v1";
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public record Result(String modelVersion, List<List<Double>> vectors, List<Integer> inputTokens,
                         List<Boolean> truncated, double serviceElapsedMs, String policyVersion) {}
    public static final class ModelFailure extends IOException {
        public final int httpStatus;
        public ModelFailure(int status, String message) { super(message); this.httpStatus = status; }
    }

    public EmbeddingClient(URI baseUrl, String apiKey, Duration timeout) {
        if (apiKey == null || apiKey.isBlank() || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Missing key or invalid request budget");
        if (!"http".equals(baseUrl.getScheme())||!"127.0.0.1".equals(baseUrl.getHost())||baseUrl.getPort()==8000||baseUrl.getPort()<1||baseUrl.getUserInfo()!=null||baseUrl.getQuery()!=null||baseUrl.getFragment()!=null||!Set.of("","/").contains(baseUrl.getPath()))
            throw new IllegalArgumentException("Use an independent loopback model URL");
        this.endpoint = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/v1/embeddings");
        this.apiKey = apiKey; this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Result encode(List<String> texts) throws IOException, InterruptedException {
        return encode(texts, "query");
    }

    public JsonNode runtimeStatus() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint.resolve("status")).timeout(Duration.ofSeconds(2))
            .header("Authorization", "Bearer " + apiKey).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new ModelFailure(response.statusCode(), "Model status unavailable");
        var data = json.readTree(response.body());
        if (data == null || !MODEL.equals(data.path("modelVersion").asText()) || !data.path("queue").isObject())
            throw new ModelFailure(502, "Invalid model status");
        var result = Contract.JSON.createObjectNode().put("state", "available").put("modelVersion", MODEL);
        var queue = result.putObject("queue");
        // Explicit allowlist: upstream diagnostics must not leak paths, input text or keys.
        for (String name : List.of("capacity", "completed", "failed", "rejected", "expired", "cancelled"))
            if (data.path("queue").path(name).isIntegralNumber()) queue.set(name, data.path("queue").get(name));
        var waiting = queue.putObject("waiting");
        for (String name : List.of("query", "index"))
            if (data.path("queue").path("waiting").path(name).isIntegralNumber()) waiting.set(name, data.path("queue").path("waiting").get(name));
        if (data.path("queue").path("active").isBoolean()) queue.set("active", data.path("queue").get("active"));
        return result;
    }

    public Result encodeIndex(List<String> texts) throws IOException, InterruptedException {
        return encode(texts, "index");
    }

    private Result encode(List<String> texts, String purpose) throws IOException, InterruptedException {
        if (texts == null || texts.isEmpty() || texts.size() > 8) throw new IllegalArgumentException("Batch must contain 1..8 texts");
        for (String text : texts) {
            if (text == null || text.codePointCount(0, text.length()) > 20000 || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
                throw new IllegalArgumentException("Each text must contain 1..20000 nonblank characters");
        }
        return send(endpoint, Map.of("texts", texts, "purpose", purpose), texts.size());
    }

    private Result send(URI target, Map<String,Object> payload, int count) throws IOException, InterruptedException {
        return send(target,payload,count,timeout);
    }
    private Result send(URI target, Map<String,Object> payload, int count,Duration budget) throws IOException, InterruptedException {
        byte[] body = json.writeValueAsBytes(payload);
        if (body.length > 128 * 1024) throw new IllegalArgumentException("JSON body exceeds 128KiB");
        HttpRequest request = HttpRequest.newBuilder(target).timeout(budget)
            .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new ModelFailure(response.statusCode(), "Embedding HTTP " + response.statusCode());
        JsonNode data;
        try { data = json.readTree(response.body()); }
        catch (IOException invalid) { throw new ModelFailure(502, "Invalid model JSON"); }
        if (data == null || data.path("dimensions").asInt() != 1024 || !MODEL.equals(data.path("modelVersion").asText()) || !POLICY.equals(data.path("policyVersion").asText())
                || !data.path("vectors").isArray() || data.path("vectors").size() != count)
            throw new ModelFailure(502, "Invalid model dimensions or batch count");
        List<List<Double>> vectors = new ArrayList<>();
        for (JsonNode vector : data.path("vectors")) {
            if (!vector.isArray() || vector.size() != 1024) throw new ModelFailure(502, "Invalid vector dimension");
            List<Double> values = new ArrayList<>(); double norm2 = 0;
            for (JsonNode value : vector) {
                double x = value.asDouble(Double.NaN);
                if (!value.isNumber() || !Double.isFinite(x)) throw new ModelFailure(502, "Invalid vector values");
                values.add(x); norm2 += x * x;
            }
            if (!Double.isFinite(norm2) || Math.abs(Math.sqrt(norm2) - 1) > 1e-4)
                throw new ModelFailure(502, "Vector is not unit normalized");
            vectors.add(List.copyOf(values));
        }
        List<Integer> counts = new ArrayList<>(); List<Boolean> clipped = new ArrayList<>();
        if (!data.path("inputTokens").isArray() || !data.path("truncated").isArray()
                || data.path("inputTokens").size() != count || data.path("truncated").size() != count)
            throw new ModelFailure(502, "Missing model length metadata");
        for (int i = 0; i < count; i++) {
            if (!data.path("inputTokens").get(i).isIntegralNumber() || !data.path("truncated").get(i).isBoolean())
                throw new ModelFailure(502, "Invalid model length metadata");
            if(data.path("inputTokens").get(i).asInt()>512||data.path("truncated").get(i).asBoolean())throw new ModelFailure(502,"Model input was truncated");
            counts.add(data.path("inputTokens").get(i).asInt()); clipped.add(false);
        }
        return new Result(data.path("modelVersion").asText(), List.copyOf(vectors), List.copyOf(counts),
                          List.copyOf(clipped), data.path("elapsedMs").asDouble(), data.path("policyVersion").asText(""));
    }
    public JsonNode segments(String title,String text)throws IOException,InterruptedException {
        var target=URI.create(endpoint.toString().replace("/embeddings","/segments"));
        var request=HttpRequest.newBuilder(target).timeout(timeout).header("Authorization","Bearer "+apiKey).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("title",title,"text",text)))).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200)throw new ModelFailure(response.statusCode(),"Segmentation unavailable");
        var data=json.readTree(response.body());
        if(!POLICY.equals(data.path("policyVersion").asText())||!data.path("segments").isArray())throw new ModelFailure(502,"Invalid segmentation");
        int end=0;StringBuilder whole=new StringBuilder();
        for(var segment:data.path("segments")) {
            if(segment.path("charStart").asInt(-1)!=end||segment.path("charEnd").asInt()<=end||segment.path("inputTokens").asInt()>512||!segment.path("input").isTextual())throw new ModelFailure(502,"Invalid segment offsets");
            String body=segment.path("text").asText();end=segment.path("charEnd").asInt();whole.append(body);
            if(end>text.codePointCount(0,text.length()))throw new ModelFailure(502,"Invalid segment coverage");
        }
        if(!whole.toString().equals(text))throw new ModelFailure(502,"Incomplete body coverage");
        return data.path("segments");
    }
}
