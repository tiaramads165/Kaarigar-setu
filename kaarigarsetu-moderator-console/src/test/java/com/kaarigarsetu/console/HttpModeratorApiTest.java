package com.kaarigarsetu.console;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The console's HTTP client against a scripted fake backend: no JavaFX toolkit and no real server needed. */
class HttpModeratorApiTest {
    private HttpServer server;
    private HttpModeratorApi api;
    private final List<String> seen = new ArrayList<>();
    private volatile String lastCsrf = "";
    private volatile String lastBody = "";
    private volatile String auditReply = "{\"fileChainValid\":true,\"fileLines\":12,\"fileDetail\":\"ok\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/session", ex -> reply(ex, 200, "{\"authenticated\":false,\"csrfToken\":\"tok-1\"}"));
        server.createContext("/api/auth/moderator/login", ex -> {
            String body = read(ex);
            if (body.contains("\"password\":\"right\"")) reply(ex, 200, "{\"otpRequired\":true}");
            else reply(ex, 401, "{\"error\":\"invalid_credentials\"}");
        });
        server.createContext("/api/auth/moderator/verify", ex -> {
            if (read(ex).contains("123456")) reply(ex, 200, "{\"ok\":true,\"role\":\"MODERATOR\",\"csrfToken\":\"tok-2\"}");
            else reply(ex, 401, "{\"error\":\"invalid_otp\"}");
        });
        server.createContext("/api/moderator/flagged", ex -> reply(ex, 200,
                "{\"flagged\":[{\"flagId\":7,\"attestationId\":42,\"anomalyScore\":0.81,\"features\":\"{\\\"ip_frequency\\\":9.0}\","
                        + "\"workerId\":3,\"employerId\":5,\"jobRole\":\"Wiring\",\"reviewerDecision\":\"PENDING\",\"createdAt\":\"2026-10-07T10:00:00\"}]}"));
        server.createContext("/api/moderator/decision", ex -> {
            lastCsrf = String.valueOf(ex.getRequestHeaders().getFirst("X-CSRF-Token"));
            lastBody = read(ex);
            reply(ex, lastBody.contains("\"flagId\":99") ? 409 : 200, lastBody.contains("\"flagId\":99") ? "{\"error\":\"conflict\"}" : "{\"approved\":true}");
        });
        server.createContext("/api/moderator/audit-check", ex -> reply(ex, 200, auditReply));
        server.createContext("/api/moderator/employers", ex -> {
            lastBody = String.valueOf(ex.getRequestURI());
            reply(ex, 200, "{\"employers\":[{\"employerId\":6,\"name\":\"Sharma Contractors\",\"verified\":false,\"approvedConfirmations\":7,\"registeredAt\":\"2026-08-02T10:15:00\"},"
                    + "{\"employerId\":4,\"name\":\"Singh Electricals\",\"verified\":true,\"verifiedBy\":\"service-account\",\"approvedConfirmations\":12,\"registeredAt\":\"2026-06-20T09:05:00\"}]}");
        });
        server.createContext("/api/moderator/vet-employer", ex -> {
            lastCsrf = String.valueOf(ex.getRequestHeaders().getFirst("X-CSRF-Token"));
            lastBody = read(ex);
            if (lastBody.contains("\"employerId\":99")) reply(ex, 404, "{\"error\":\"not_found\"}");
            else if (lastBody.contains("\"employerId\":77")) reply(ex, 202, "{\"pending\":true,\"actionId\":5}");
            else reply(ex, 200, "{\"vetted\":true}");
        });
        server.createContext("/api/moderator/me", ex -> reply(ex, 200, "{\"moderatorId\":4,\"username\":\"alice\",\"displayName\":\"Alice Rao\",\"twoPersonActions\":[\"VET_EMPLOYER\",\"DISPUTE_DECISION\"],\"lifetimeHours\":72}"));
        server.createContext("/api/moderator/pending", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/moderator/pending")) {
                reply(ex, 200, "{\"pending\":[{\"actionId\":5,\"type\":\"VET_EMPLOYER\",\"targetId\":6,\"target\":\"employer 6 (Sharma Contractors)\",\"verdict\":null,\"proposedBy\":\"bob\","
                        + "\"proposedAt\":\"2026-10-22T09:30:00\",\"expiresAt\":\"2026-10-25T09:30:00\",\"canDecide\":true},"
                        + "{\"actionId\":6,\"type\":\"DISPUTE_DECISION\",\"targetId\":41,\"target\":\"confirmation 41\",\"verdict\":\"REMOVED\",\"proposedBy\":\"alice\","
                        + "\"proposedAt\":\"2026-10-22T10:00:00\",\"expiresAt\":\"2026-10-25T10:00:00\",\"canDecide\":false}]}");
                return;
            }
            lastCsrf = String.valueOf(ex.getRequestHeaders().getFirst("X-CSRF-Token"));
            lastBody = ex.getRequestMethod() + " " + path + " " + read(ex);
            reply(ex, path.contains("/6/") ? 403 : path.contains("/9/") ? 409 : 200, path.contains("/6/") ? "{\"error\":\"forbidden\"}" : path.contains("/9/") ? "{\"error\":\"conflict\"}" : "{\"approved\":true}");
        });
        server.createContext("/api/moderator/disputes", ex -> reply(ex, 200,
                "{\"disputes\":[{\"attestationId\":41,\"workerId\":14,\"employerId\":6,\"employerName\":\"Sharma Contractors\",\"jobRole\":\"Wiring\",\"workDate\":\"2026-10-02\","
                        + "\"ratingQuality\":1,\"ratingPunctuality\":1,\"ratingConduct\":null,\"wouldHireAgain\":false,\"reason\":\"I never worked for this contractor\",\"disputedAt\":\"2026-10-08T09:12:00\"}]}"));
        server.createContext("/api/moderator/dispute-decision", ex -> {
            lastCsrf = String.valueOf(ex.getRequestHeaders().getFirst("X-CSRF-Token"));
            lastBody = read(ex);
            reply(ex, lastBody.contains("\"attestationId\":99") ? 404 : 200, lastBody.contains("\"attestationId\":99") ? "{\"error\":\"not_found\"}" : "{\"decided\":true}");
        });
        server.createContext("/api/moderator/security", ex -> reply(ex, 200,
                "{\"status\":\"WARN\",\"alerts\":[{\"level\":\"WARN\",\"title\":\"Repeated failed sign-ins\",\"detail\":\"14 in the last hour\"}],"
                        + "\"counts\":{\"LOGIN_FAILED\":{\"lastHour\":14,\"last24h\":31},\"CSRF_REJECTED\":{\"lastHour\":0,\"last24h\":2}},"
                        + "\"integrity\":{\"fileChainValid\":true,\"fileLines\":812,\"fileDetail\":\"ok\",\"anchorsChecked\":33,\"databaseChainValid\":true,\"newestAnchorAgeMinutes\":2},"
                        + "\"recent\":[{\"at\":\"2026-10-16T01:29:44\",\"type\":\"LOGIN_FAILED\",\"actor\":\"anonymous\",\"details\":\"method=otp\"}]}"));
        server.createContext("/api/auth/logout", ex -> reply(ex, 200, "{\"ok\":true}"));
        server.start();
        api = new HttpModeratorApi("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String read(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void passwordThenOtpThenQueueParsesRows() {
        api.login("moderator", "right".toCharArray());
        api.verifyOtp("123456");
        List<FlaggedRow> queue = api.flagged();
        assertEquals(1, queue.size());
        FlaggedRow row = queue.get(0);
        assertEquals(7, row.flagId());
        assertEquals(42, row.attestationId());
        assertEquals(0.81, row.anomalyScore(), 1e-9);
        assertEquals("Wiring", row.jobRole());
    }

    @Test
    void wrongPasswordIsReportedWithoutLeakingDetail() {
        ConsoleException failure = assertThrows(ConsoleException.class, () -> api.login("moderator", "wrong".toCharArray()));
        assertEquals(401, failure.status());
        assertEquals("invalid_credentials", failure.code());
        assertEquals("Wrong username or password.", failure.getMessage());
    }

    @Test
    void wrongOtpIsRejectedAndOtpWithoutPasswordIsRefusedLocally() {
        assertThrows(ConsoleException.class, () -> api.verifyOtp("123456"));       // no login step yet
        api.login("moderator", "right".toCharArray());
        assertEquals("invalid_otp", assertThrows(ConsoleException.class, () -> api.verifyOtp("000000")).code());
    }

    @Test
    void decisionsCarryTheRotatedCsrfTokenAndTheRightPayload() {
        api.login("moderator", "right".toCharArray());
        api.verifyOtp("123456");
        assertTrue(api.decide(7, "VALID"));
        assertEquals("tok-2", lastCsrf, "the token returned by login (tok-2), not the pre-login one (tok-1)");
        assertTrue(lastBody.contains("\"flagId\":7") && lastBody.contains("\"decision\":\"VALID\""));
    }

    @Test
    void aConflictFromTheServerSurfacesAsAReadableError() {
        api.login("moderator", "right".toCharArray());
        api.verifyOtp("123456");
        ConsoleException failure = assertThrows(ConsoleException.class, () -> api.decide(99, "FRAUD"));
        assertEquals(409, failure.status());
        assertTrue(failure.getMessage().contains("already decided"));
    }

    @Test
    void auditCheckIsSummarisedForTheOperator() {
        assertEquals("Audit log intact: 12 file entries, ok", api.auditCheck());
    }

    @Test
    void auditCheckReportsAnchorsAndTheDatabaseChainAndFlagsAnyFailure() {
        auditReply = "{\"fileChainValid\":true,\"fileLines\":812,\"fileDetail\":\"ok\",\"anchorsChecked\":33,\"databaseChainValid\":true,\"databaseRows\":900}";
        assertEquals("Audit log intact: 812 file entries, ok, 33 database anchors matched; database chain intact (900 rows)", api.auditCheck());
        auditReply = "{\"fileChainValid\":false,\"fileLines\":700,\"fileDetail\":\"file has 700 lines but the database anchored line 812: rolled back or truncated\",\"anchorsChecked\":33}";
        assertTrue(api.auditCheck().startsWith("AUDIT LOG TAMPERED: 700 file entries, file has 700 lines but the database anchored line 812"), api.auditCheck());
        auditReply = "{\"fileChainValid\":true,\"fileLines\":5,\"fileDetail\":\"ok\",\"databaseChainValid\":false,\"databaseRows\":5}";
        assertTrue(api.auditCheck().startsWith("AUDIT LOG TAMPERED:"), "a broken database chain alone is tampering");
    }

    @Test
    void theEmployerListIsParsedAndTheUnvettedFilterIsASeparateRequest() {
        var rows = api.employers(true);
        assertEquals(2, rows.size());
        assertEquals("Sharma Contractors", rows.get(0).name());
        assertFalse(rows.get(0).vetted());
        assertTrue(rows.get(1).vetted());
        assertEquals("service-account", rows.get(1).vettedBy());
        assertEquals("/api/moderator/employers", lastBody);
        api.employers(false);
        assertEquals("/api/moderator/employers?all=1", lastBody);
    }

    @Test
    void vettingSendsTheEmployerIdWithTheCsrfTokenAndAnUnknownEmployerIsAnError() {
        api.vetEmployer(6);
        assertEquals("{\"employerId\":6}", lastBody);
        ConsoleException failure = assertThrows(ConsoleException.class, () -> api.vetEmployer(99));
        assertEquals("not_found", failure.code());
    }

    @Test
    void disputesAreParsedWithMissingRatingsShownAsADashAndNoPhoneNumber() {
        var rows = api.disputes();
        assertEquals(1, rows.size());
        DisputeRow row = rows.get(0);
        assertEquals("Sharma Contractors", row.employerName());
        assertEquals("quality 1, punctuality 1, conduct -", row.ratings());
        assertEquals("no", row.wouldHireAgain());
        assertTrue(row.explain().contains("I never worked for this contractor"));
    }

    @Test
    void aRulingSendsTheAttestationIdAndDecisionWithTheCsrfTokenAndAnUnknownDisputeIsAnError() {
        api.decideDispute(41, "REMOVED");
        assertEquals("{\"attestationId\":41,\"decision\":\"REMOVED\"}", lastBody);
        ConsoleException failure = assertThrows(ConsoleException.class, () -> api.decideDispute(99, "UPHELD"));
        assertEquals("not_found", failure.code());
    }

    @Test
    void aVettingThatNeedsASecondModeratorComesBackAsPendingWithItsId() {
        ActionResult queued = api.vetEmployer(77);
        assertTrue(queued.pending());
        assertEquals(5, queued.actionId());
        assertFalse(api.vetEmployer(6).pending(), "single-moderator mode carries it out at once (a 200)");
    }

    @Test
    void theSignedInModeratorAndThePendingApprovalsAreParsed() {
        ModeratorIdentity me = api.me();
        assertEquals("alice", me.username());
        assertEquals(List.of("VET_EMPLOYER", "DISPUTE_DECISION"), me.twoPersonActions());
        var rows = api.pending();
        assertEquals(2, rows.size());
        assertEquals("Vet employer", rows.get(0).what());
        assertTrue(rows.get(0).canDecide());
        assertFalse(rows.get(1).canDecide());
        assertEquals("Dispute: remove the confirmation", rows.get(1).what());
        assertTrue(rows.get(1).explain().contains("DIFFERENT moderator"), rows.get(1).explain());
        assertTrue(rows.get(0).explain().contains("you may approve"), rows.get(0).explain());
    }

    @Test
    void approvingAndRejectingSendTheCsrfTokenAndTheServersRefusalsBecomeReadableErrors() {
        api.approve(5);
        assertEquals("POST /api/moderator/pending/5/approve {}", lastBody);
        api.reject(5);
        assertEquals("POST /api/moderator/pending/5/reject {}", lastBody);
        ConsoleException own = assertThrows(ConsoleException.class, () -> api.approve(6));
        assertEquals(403, own.status());
        assertTrue(own.getMessage().contains("approve your own"), own.getMessage());
        ConsoleException late = assertThrows(ConsoleException.class, () -> api.approve(9));
        assertEquals("conflict", late.code());
    }

    @Test
    void theSecurityOverviewIsParsedForTheSecurityTab() {
        SecurityOverview overview = api.securityOverview();
        assertEquals("WARN", overview.status());
        assertEquals("Repeated failed sign-ins", overview.alerts().get(0).title());
        assertEquals(2, overview.counts().size());
        assertEquals(14, overview.counts().get(0).lastHour());
        assertEquals("LOGIN_FAILED", overview.recent().get(0).type());
        assertTrue(overview.integrity().contains("812 lines") && overview.integrity().contains("33 database anchors") && overview.integrity().contains("newest anchor 2 min ago"), overview.integrity());
        assertEquals("Failed sign-ins", SecurityOverview.describe("LOGIN_FAILED"));
        assertEquals("SOMETHING_NEW", SecurityOverview.describe("SOMETHING_NEW"));
    }

    @Test
    void anUnreachableServerIsANetworkError() {
        server.stop(0);
        ConsoleException failure = assertThrows(ConsoleException.class, () -> api.flagged());
        assertEquals("network", failure.code());
    }
}
