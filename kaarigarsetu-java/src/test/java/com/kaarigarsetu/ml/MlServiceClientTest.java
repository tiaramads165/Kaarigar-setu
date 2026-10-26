package com.kaarigarsetu.ml;

import com.kaarigarsetu.exception.ExternalServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Talks to a real local HTTP server, so the HTTP version, headers and response validation are exercised for real. */
class MlServiceClientTest {
    private HttpServer server;
    private final AtomicReference<String> response = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private MlServiceClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastPath.set(exchange.getRequestURI().toString());
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new MlServiceClient("http://127.0.0.1:" + server.getAddress().getPort(), "s3cret-token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void reply(String json) {
        response.set(json);
    }

    @Test
    void evaluateSendsTheInternalTokenAndParsesAValidResponse() {
        reply("{\"trade\":\"electrician\",\"score\":72.5,\"feedback\":\"ok\",\"needs_review\":false,\"provider\":\"stub\"}");
        MlGateway.Evaluation e = client.evaluate("electrician", "namaste", 3, "hi");
        assertEquals(72.5, e.score());
        assertFalse(e.needsReview());
        assertEquals("s3cret-token", lastToken.get());
        assertEquals("/internal/ml/evaluate", lastPath.get());
        assertTrue(lastBody.get().contains("\"question_id\":3"));
    }

    @Test
    void noTokenHeaderWhenNoneConfigured() {
        MlServiceClient bare = new MlServiceClient("http://127.0.0.1:" + server.getAddress().getPort());
        reply("{\"anomaly_score\":0.1,\"flagged\":false}");
        bare.fraudCheck(new MlGateway.FraudFeatures(1, 1, 500, 0));
        assertNull(lastToken.get());
    }

    @Test
    void outOfRangeOrMistypedScoresAreRejectedNotTrusted() {
        for (String bad : new String[]{
                "{\"trade\":\"e\",\"score\":150,\"provider\":\"p\"}",
                "{\"trade\":\"e\",\"score\":-1,\"provider\":\"p\"}",
                "{\"trade\":\"e\",\"score\":\"99\",\"provider\":\"p\"}",
                "{\"trade\":\"e\",\"provider\":\"p\"}",
                "[1,2,3]", "not json", ""}) {
            reply(bad);
            assertThrows(ExternalServiceException.class, () -> client.evaluate("electrician", "t", 1, "hi"), bad);
        }
    }

    @Test
    void feedbackIsCappedAndStrippedOfControlCharacters() {
        reply("{\"trade\":\"e\",\"score\":50,\"feedback\":\"" + "a\\nb".repeat(400) + "\",\"provider\":\"p\"}");
        String feedback = client.evaluate("e", "t", 1, "hi").feedback();
        assertTrue(feedback.length() <= 500);
        assertFalse(feedback.contains("\n"));
    }

    @Test
    void non2xxAndUnreachableBothBecomeAGenericExternalServiceError() {
        status.set(500);
        reply("{\"detail\":\"Traceback (most recent call last) ... secret\"}");
        ExternalServiceException e = assertThrows(ExternalServiceException.class, () -> client.evaluate("e", "t", 1, "hi"));
        assertFalse(e.getMessage().contains("Traceback"));

        MlServiceClient dead = new MlServiceClient("http://127.0.0.1:1", "x");
        assertThrows(ExternalServiceException.class, () -> dead.evaluate("e", "t", 1, "hi"));
    }

    @Test
    void fraudCheckRequiresARealBooleanAndAnInRangeScore() {
        reply("{\"anomaly_score\":0.93,\"flagged\":true}");
        MlGateway.FraudResult ok = client.fraudCheck(new MlGateway.FraudFeatures(9, 9, 1, 1));
        assertTrue(ok.flagged());
        assertEquals(0.93, ok.anomalyScore(), 1e-9);
        for (String bad : new String[]{"{\"anomaly_score\":0.5}", "{\"anomaly_score\":0.5,\"flagged\":\"yes\"}", "{\"anomaly_score\":2,\"flagged\":false}"}) {
            reply(bad);
            assertThrows(ExternalServiceException.class, () -> client.fraudCheck(new MlGateway.FraudFeatures(1, 1, 1, 0)), bad);
        }
    }

    @Test
    void trustScoresAreValidatedPerNode() {
        reply("{\"scaled_scores\":{\"w:1\":100,\"w:2\":42.5}}");
        Map<String, Double> scores = client.trustScores(List.of("w:1", "w:2"), List.of(new MlGateway.TrustEdge("e:1", "w:1", 1.0)));
        assertEquals(42.5, scores.get("w:2"));
        reply("{\"scaled_scores\":{\"w:1\":1000}}");
        assertThrows(ExternalServiceException.class, () -> client.trustScores(List.of("w:1"), List.of()));
        reply("{}");
        assertThrows(ExternalServiceException.class, () -> client.trustScores(List.of("w:1"), List.of()));
    }

    @Test
    void rubricNeedsAtLeastOneQuestionAndSaneIds() {
        reply("{\"trade\":\"electrician\",\"version\":\"v1\",\"test_only\":true,\"questions\":[{\"id\":1,\"text\":\"q\",\"max_marks\":5}]}");
        MlGateway.Rubric r = client.rubric("electrician", "hi");
        assertTrue(r.testOnly());
        assertEquals(1, r.questions().size());
        reply("{\"trade\":\"electrician\",\"version\":\"v1\",\"questions\":[]}");
        assertThrows(ExternalServiceException.class, () -> client.rubric("electrician", "hi"));
        reply("{\"trade\":\"e\",\"version\":\"v1\",\"questions\":[{\"id\":1.5,\"text\":\"q\",\"max_marks\":5}]}");
        assertThrows(ExternalServiceException.class, () -> client.rubric("e", "hi"));
    }

    @Test
    void rubricQuestionsCarryTheirLevelAndAreaAndOldReplyShapesStillWork() {
        reply("{\"trade\":\"e\",\"version\":\"v\",\"questions\":[{\"id\":1,\"text\":\"q\",\"max_marks\":9,\"level\":\"advanced\",\"area\":\"troubleshooting\"}]}");
        MlGateway.Question q = client.rubric("e", "hi").questions().get(0);
        assertEquals("advanced", q.level());
        assertEquals("troubleshooting", q.area());
        reply("{\"trade\":\"e\",\"version\":\"v\",\"questions\":[{\"id\":1,\"text\":\"q\",\"max_marks\":9}]}");
        assertEquals("knowledge", client.rubric("e", "hi").questions().get(0).area(), "a reply without an area defaults rather than failing");
    }

    @Test
    void anAreaOrLevelThatIsNotAPlainLabelIsRejectedSoItCanNeverReachAPage() {
        for (String bad : new String[]{"<script>", "Tools", "a", "x".repeat(21), "with space", "area;drop"}) {
            reply("{\"trade\":\"e\",\"version\":\"v\",\"questions\":[{\"id\":1,\"text\":\"q\",\"max_marks\":9,\"area\":\"" + bad + "\"}]}");
            assertThrows(ExternalServiceException.class, () -> client.rubric("e", "hi"), bad);
        }
    }

    @Test
    void tradeNamesAreUrlEncodedSoTheyCannotInjectPathOrQuery() {
        reply("{\"trade\":\"x\",\"version\":\"v\",\"questions\":[{\"id\":1,\"text\":\"q\",\"max_marks\":1}]}");
        client.rubric("a/../b?x=1", "hi&evil=1");
        assertFalse(lastPath.get().contains("/../"));
        assertEquals(1, lastPath.get().chars().filter(c -> c == '?').count());
        assertFalse(lastPath.get().contains("&evil=1"));
    }

    @Test
    void transcribeSendsMultipartAndSanitisesTheFilename() {
        reply("{\"transcript\":\"namaste\",\"language\":\"hi\"}");
        MlGateway.Transcript t = client.transcribe(new byte[]{1, 2, 3}, "../../etc/pass\"wd.wav", "hi");
        assertEquals("namaste", t.transcript());
        assertTrue(lastBody.get().contains("name=\"audio\""));
        assertFalse(lastBody.get().contains("../"));
        assertFalse(lastBody.get().contains("pass\"wd"));
    }

    @Test
    void fingerprintParsesLandmarksAndSendsTheAudioAsMultipart() {
        reply("{\"hashes\":[5,70000,8388607],\"times\":[0,12,40],\"seconds\":3.2,\"landmarks\":3}");
        MlGateway.Fingerprint f = client.fingerprint(new byte[]{1, 2, 3}, "../a\"b.wav");
        assertArrayEquals(new int[]{5, 70000, 8388607}, f.hashes());
        assertArrayEquals(new int[]{0, 12, 40}, f.times());
        assertEquals(3.2, f.seconds());
        assertTrue(lastPath.get().endsWith("/audio-fingerprint"));
        assertEquals("s3cret-token", lastToken.get());
        assertFalse(lastBody.get().contains("../"));
    }

    @Test
    void fingerprintRefusesAnythingThatIsNotAConsistentSetOfBoundedNumbers() {
        for (String bad : new String[]{
                "{\"hashes\":[1,2],\"times\":[1],\"seconds\":1}",                    // lists of different length
                "{\"hashes\":[8388608],\"times\":[1],\"seconds\":1}",                // more than 23 bits
                "{\"hashes\":[-1],\"times\":[1],\"seconds\":1}",
                "{\"hashes\":[\"x\"],\"times\":[1],\"seconds\":1}",
                "{\"hashes\":[1],\"times\":[2000000],\"seconds\":1}",
                "{\"hashes\":[1],\"times\":[1],\"seconds\":-3}",
                "{\"hashes\":[1],\"times\":[1],\"seconds\":99999}",
                "{\"times\":[1],\"seconds\":1}",
                "[]"}) {
            reply(bad);
            assertThrows(ExternalServiceException.class, () -> client.fingerprint(new byte[]{1}, "a.wav"), bad);
        }
        reply("{\"hashes\":[" + "1,".repeat(4000) + "1],\"times\":[" + "1,".repeat(4000) + "1],\"seconds\":1}");
        assertThrows(ExternalServiceException.class, () -> client.fingerprint(new byte[]{1}, "a.wav"), "more landmarks than the service ever returns");
    }

    @Test
    void fingerprintTurnsAnErrorStatusIntoAnExternalServiceFailure() {
        status.set(422);
        reply("{\"detail\":\"not a readable WAV file\"}");
        assertThrows(ExternalServiceException.class, () -> client.fingerprint(new byte[]{1}, "a.wav"));
    }
}
