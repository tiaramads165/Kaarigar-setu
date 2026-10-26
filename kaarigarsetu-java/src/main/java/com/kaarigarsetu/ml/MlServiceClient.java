package com.kaarigarsetu.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Small internal HTTP client for the private Python ML service. Every response is validated against the shape and
 * ranges the Java side expects before anything is used, so a compromised or buggy upstream cannot inject
 * out-of-range scores (blueprint: "strict validation on every LLM/ML service response").
 */
public class MlServiceClient implements MlGateway {
    private static final System.Logger LOG = System.getLogger("ks.ml");
    private static final String BASE_PATH = "/internal/ml";
    private static final int MAX_FEEDBACK_CHARS = 500;

    private final HttpClient httpClient;
    private final URI baseUri;
    private final String internalToken;

    public MlServiceClient(String baseUrl) {
        this(baseUrl, "");
    }

    public MlServiceClient(String baseUrl, String internalToken) {
        // HTTP/1.1 on purpose: the JDK client otherwise sends "Upgrade: h2c" on cleartext requests, which uvicorn rejects (400).
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create(baseUrl), internalToken);
    }

    MlServiceClient(HttpClient httpClient, URI baseUri) {
        this(httpClient, baseUri, "");
    }

    MlServiceClient(HttpClient httpClient, URI baseUri, String internalToken) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    // ---------------------------------------------------------------- typed, validated API

    @Override
    public Rubric rubric(String trade, String language) {
        String path = BASE_PATH + "/rubric/" + java.net.URLEncoder.encode(trade, StandardCharsets.UTF_8).replace("+", "%20")
                + "?language=" + java.net.URLEncoder.encode(language, StandardCharsets.UTF_8);
        JsonObject body = parseObject(send(request(path).GET().build()));
        List<Question> questions = new ArrayList<>();
        for (JsonElement element : array(body, "questions")) {
            JsonObject q = element.getAsJsonObject();
            questions.add(new Question(integer(q, "id", 1, 100), string(q, "text", 1000), integer(q, "max_marks", 1, 1000),
                    q.has("level") ? word(q, "level") : "basic", q.has("area") ? word(q, "area") : "knowledge"));
        }
        if (questions.isEmpty()) throw bad("rubric has no questions");
        return new Rubric(string(body, "trade", 80), string(body, "version", 40), body.has("test_only") && body.get("test_only").getAsBoolean(), questions);
    }

    @Override
    public Transcript transcribe(byte[] audio, String filename, String language) {
        String boundary = "ks-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + safeName(filename)
                    + "\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        String query = language == null ? "" : "?language=" + java.net.URLEncoder.encode(language, StandardCharsets.UTF_8);
        HttpRequest request = request(BASE_PATH + "/transcribe" + query)
                .timeout(Duration.ofSeconds(120))   // Whisper on CPU is slow
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        JsonObject json = parseObject(send(request));
        return new Transcript(string(json, "transcript", 4000), json.has("language") ? string(json, "language", 20) : language);
    }

    @Override
    public Fingerprint fingerprint(byte[] audio, String filename) {
        String boundary = "ks-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"" + safeName(filename)
                    + "\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        HttpRequest request = request(BASE_PATH + "/audio-fingerprint")
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        JsonObject json = parseObject(send(request));
        JsonArray hashes = array(json, "hashes");
        JsonArray times = array(json, "times");
        if (hashes.size() != times.size() || hashes.size() > 4000) throw bad("landmark lists are inconsistent");
        int[] h = new int[hashes.size()];
        int[] t = new int[times.size()];
        for (int i = 0; i < h.length; i++) {
            h[i] = boundedInt(hashes.get(i), 0, (1 << 23) - 1);
            t[i] = boundedInt(times.get(i), 0, 1_000_000);
        }
        double seconds = json.has("seconds") && json.get("seconds").isJsonPrimitive() ? json.get("seconds").getAsDouble() : 0;
        if (!(seconds >= 0 && seconds <= 3600)) throw bad("recording length out of range");
        return new Fingerprint(h, t, seconds);
    }

    private static int boundedInt(JsonElement element, int min, int max) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw bad("landmark is not a number");
        long value = element.getAsLong();
        if (value < min || value > max) throw bad("landmark out of range");
        return (int) value;
    }

    @Override
    public Evaluation evaluate(String trade, String transcript, int questionId, String language) {
        JsonObject payload = new JsonObject();
        payload.addProperty("trade", trade);
        payload.addProperty("transcript", transcript);
        payload.addProperty("question_id", questionId);
        payload.addProperty("language", language);
        JsonObject json = parseObject(postJson("/evaluate", Json.toJson(payload)));
        double score = number(json, "score", 0, 100);
        return new Evaluation(string(json, "trade", 80), score, json.has("feedback") ? limited(string(json, "feedback", MAX_FEEDBACK_CHARS * 4)) : "",
                json.has("needs_review") && json.get("needs_review").getAsBoolean(),
                json.has("review_reason") && !json.get("review_reason").isJsonNull() ? limited(json.get("review_reason").getAsString()) : null,
                json.has("provider") ? string(json, "provider", 80) : "unknown");
    }

    @Override
    public FraudResult fraudCheck(FraudFeatures features) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ip_frequency", features.ipFrequency());
        payload.addProperty("device_frequency", features.deviceFrequency());
        payload.addProperty("minutes_since_previous", features.minutesSincePrevious());
        payload.addProperty("reciprocity_ratio", features.reciprocityRatio());
        JsonObject json = parseObject(postJson("/fraud-check", Json.toJson(payload)));
        if (!json.has("flagged") || !json.get("flagged").isJsonPrimitive() || !json.get("flagged").getAsJsonPrimitive().isBoolean()) {
            throw bad("flagged is missing");
        }
        return new FraudResult(number(json, "anomaly_score", 0, 1), json.get("flagged").getAsBoolean());
    }

    @Override
    public Map<String, Double> trustScores(List<String> nodes, List<TrustEdge> edges) {
        JsonObject payload = new JsonObject();
        JsonArray nodeArray = new JsonArray();
        nodes.forEach(nodeArray::add);
        JsonArray edgeArray = new JsonArray();
        for (TrustEdge edge : edges) {
            JsonObject e = new JsonObject();
            e.addProperty("source", edge.source());
            e.addProperty("target", edge.target());
            e.addProperty("weight", edge.weight());
            edgeArray.add(e);
        }
        payload.add("nodes", nodeArray);
        payload.add("edges", edgeArray);
        JsonObject json = parseObject(postJson("/trust-score", Json.toJson(payload)));
        JsonObject scaled = json.has("scaled_scores") && json.get("scaled_scores").isJsonObject() ? json.getAsJsonObject("scaled_scores") : null;
        if (scaled == null) throw bad("scaled_scores is missing");
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : scaled.entrySet()) {
            result.put(entry.getKey(), number(scaled, entry.getKey(), 0, 100));
        }
        return result;
    }

    // ---------------------------------------------------------------- legacy string API (Phase 2/5 skeleton)

    public String evaluate(String trade, String transcript) {
        JsonObject payload = new JsonObject();
        payload.addProperty("trade", trade);
        payload.addProperty("transcript", transcript);
        return postJson("/evaluate", Json.toJson(payload));
    }

    public String fraudCheck(String jsonPayload) {
        return postJson("/fraud-check", jsonPayload);
    }

    public String trustScore(String jsonPayload) {
        return postJson("/trust-score", jsonPayload);
    }

    public String postJson(String path, String jsonPayload) {
        String full = path.startsWith(BASE_PATH) ? path : BASE_PATH + path;
        return send(request(full).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build());
    }

    // ---------------------------------------------------------------- plumbing

    private HttpRequest.Builder request(String pathAndQuery) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(pathAndQuery)).timeout(Duration.ofSeconds(15));
        if (!internalToken.isEmpty()) builder.header("X-Internal-Token", internalToken);
        return builder;
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                LOG.log(System.Logger.Level.ERROR, "ML service returned HTTP {0} for {1}", response.statusCode(), request.uri().getPath());
                throw new ExternalServiceException("ML service returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            LOG.log(System.Logger.Level.ERROR, "ML service unreachable: {0}", exception.toString());
            throw new ExternalServiceException("ML service is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("ML service request was interrupted");
        }
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) throw bad("response is not a JSON object");
            return element.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw bad("response is not valid JSON");
        }
    }

    private static JsonArray array(JsonObject o, String name) {
        if (!o.has(name) || !o.get(name).isJsonArray()) throw bad(name + " is missing");
        return o.getAsJsonArray(name);
    }

    private static String string(JsonObject o, String name, int maxLength) {
        if (!o.has(name) || !o.get(name).isJsonPrimitive() || !o.get(name).getAsJsonPrimitive().isString()) throw bad(name + " is missing");
        String value = o.get(name).getAsString();
        if (value.length() > maxLength) throw bad(name + " is too long");
        return value;
    }

    private static int integer(JsonObject o, String name, int min, int max) {
        double value = number(o, name, min, max);
        if (value != Math.rint(value)) throw bad(name + " must be an integer");
        return (int) value;
    }

    private static double number(JsonObject o, String name, double min, double max) {
        if (!o.has(name) || !o.get(name).isJsonPrimitive() || !o.get(name).getAsJsonPrimitive().isNumber()) throw bad(name + " is missing");
        double value = o.get(name).getAsDouble();
        if (Double.isNaN(value) || value < min || value > max) throw bad(name + " is out of range");
        return value;
    }

    /** A short lower-case label (an area or a level): anything else is an invalid response, so it can never smuggle markup into a page. */
    private static String word(JsonObject o, String name) {
        String value = string(o, name, 20);
        if (!value.matches("[a-z]{3,20}")) throw bad(name + " is not a plain label");
        return value;
    }

    private static String limited(String text) {
        String cleaned = text.replaceAll("[\\p{Cntrl}]", " ").trim();
        return cleaned.length() > MAX_FEEDBACK_CHARS ? cleaned.substring(0, MAX_FEEDBACK_CHARS) : cleaned;
    }

    private static String safeName(String filename) {
        return filename == null ? "audio.wav" : filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ExternalServiceException bad(String why) {
        LOG.log(System.Logger.Level.ERROR, "ML response rejected: {0}", why);
        return new ExternalServiceException("ML service returned an invalid response");
    }
}
