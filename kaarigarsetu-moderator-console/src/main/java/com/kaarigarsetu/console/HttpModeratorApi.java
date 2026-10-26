package com.kaarigarsetu.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * REST client for the Java backend. It keeps the session cookie in a cookie jar and echoes the backend's CSRF token
 * in X-CSRF-Token on every state-changing call (the token rotates on login, so it is refreshed from each login reply).
 * Passwords are held as char[] and never logged.
 */
public class HttpModeratorApi implements ModeratorApi {
    private final HttpClient http;
    private final URI base;
    private String csrfToken = "";
    private String pendingUsername;

    public HttpModeratorApi(String baseUrl) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void login(String username, char[] password) {
        ensureSession();
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", new String(password));
        send("POST", "api/auth/moderator/login", body);
        pendingUsername = username;
    }

    @Override
    public void verifyOtp(String code) {
        if (pendingUsername == null) throw new ConsoleException(400, "invalid_request");
        JsonObject body = new JsonObject();
        body.addProperty("code", code);
        JsonObject reply = send("POST", "api/auth/moderator/verify", body);
        if (reply.has("csrfToken")) csrfToken = reply.get("csrfToken").getAsString();   // rotated on login
    }

    @Override
    public List<FlaggedRow> flagged() {
        JsonArray rows = send("GET", "api/moderator/flagged", null).getAsJsonArray("flagged");
        List<FlaggedRow> out = new ArrayList<>();
        for (JsonElement element : rows) {
            JsonObject o = element.getAsJsonObject();
            out.add(new FlaggedRow(o.get("flagId").getAsLong(), o.get("attestationId").getAsLong(), o.get("anomalyScore").getAsDouble(),
                    text(o, "features"), o.get("workerId").getAsLong(), o.get("employerId").getAsLong(), text(o, "jobRole"), text(o, "createdAt")));
        }
        return out;
    }

    @Override
    public boolean decide(long flagId, String decision) {
        JsonObject body = new JsonObject();
        body.addProperty("flagId", flagId);
        body.addProperty("decision", decision);
        return send("POST", "api/moderator/decision", body).get("approved").getAsBoolean();
    }

    @Override
    public String auditCheck() {
        JsonObject reply = send("GET", "api/moderator/audit-check", null);
        boolean fileOk = reply.get("fileChainValid").getAsBoolean();
        boolean databaseOk = !reply.has("databaseChainValid") || reply.get("databaseChainValid").getAsBoolean();
        String anchors = reply.has("anchorsChecked") ? ", " + reply.get("anchorsChecked").getAsLong() + " database anchors matched" : "";
        String database = reply.has("databaseChainValid") ? "; database chain " + (databaseOk ? "intact (" + reply.get("databaseRows").getAsLong() + " rows)" : "BROKEN") : "";
        return (fileOk && databaseOk ? "Audit log intact: " : "AUDIT LOG TAMPERED: ") + reply.get("fileLines").getAsLong() + " file entries, "
                + text(reply, "fileDetail") + (fileOk ? anchors : "") + database;
    }

    @Override
    public List<EmployerRow> employers(boolean onlyUnvetted) {
        List<EmployerRow> out = new ArrayList<>();
        for (JsonElement element : send("GET", "api/moderator/employers" + (onlyUnvetted ? "" : "?all=1"), null).getAsJsonArray("employers")) {
            JsonObject o = element.getAsJsonObject();
            out.add(new EmployerRow(o.get("employerId").getAsLong(), text(o, "name"), o.get("verified").getAsBoolean(), text(o, "verifiedBy"),
                    o.get("approvedConfirmations").getAsInt(), text(o, "registeredAt")));
        }
        return out;
    }

    @Override
    public ActionResult vetEmployer(long employerId) {
        JsonObject body = new JsonObject();
        body.addProperty("employerId", employerId);
        return result(send("POST", "api/moderator/vet-employer", body));
    }

    private static ActionResult result(JsonObject reply) {
        return reply.has("pending") && reply.get("pending").getAsBoolean() ? new ActionResult(true, reply.get("actionId").getAsLong()) : new ActionResult(false, 0);
    }

    @Override
    public ModeratorIdentity me() {
        JsonObject reply = send("GET", "api/moderator/me", null);
        List<String> actions = new ArrayList<>();
        for (JsonElement element : reply.getAsJsonArray("twoPersonActions")) actions.add(element.getAsString());
        return new ModeratorIdentity(text(reply, "username"), text(reply, "displayName"), actions);
    }

    @Override
    public List<PendingRow> pending() {
        List<PendingRow> out = new ArrayList<>();
        for (JsonElement element : send("GET", "api/moderator/pending", null).getAsJsonArray("pending")) {
            JsonObject o = element.getAsJsonObject();
            out.add(new PendingRow(o.get("actionId").getAsLong(), text(o, "type"), text(o, "target"), text(o, "verdict"), text(o, "proposedBy"), text(o, "proposedAt"),
                    text(o, "expiresAt"), o.get("canDecide").getAsBoolean()));
        }
        return out;
    }

    @Override
    public void approve(long actionId) {
        send("POST", "api/moderator/pending/" + actionId + "/approve", new JsonObject());
    }

    @Override
    public void reject(long actionId) {
        send("POST", "api/moderator/pending/" + actionId + "/reject", new JsonObject());
    }

    @Override
    public List<DisputeRow> disputes() {
        List<DisputeRow> out = new ArrayList<>();
        for (JsonElement element : send("GET", "api/moderator/disputes", null).getAsJsonArray("disputes")) {
            JsonObject o = element.getAsJsonObject();
            String ratings = "quality " + number(o, "ratingQuality") + ", punctuality " + number(o, "ratingPunctuality") + ", conduct " + number(o, "ratingConduct");
            String hire = !o.has("wouldHireAgain") || o.get("wouldHireAgain").isJsonNull() ? "not stated" : o.get("wouldHireAgain").getAsBoolean() ? "yes" : "no";
            out.add(new DisputeRow(o.get("attestationId").getAsLong(), o.get("workerId").getAsLong(), o.get("employerId").getAsLong(), text(o, "employerName"),
                    text(o, "jobRole"), text(o, "workDate"), ratings, hire, text(o, "reason"), text(o, "disputedAt")));
        }
        return out;
    }

    @Override
    public ActionResult decideDispute(long attestationId, String decision) {
        JsonObject body = new JsonObject();
        body.addProperty("attestationId", attestationId);
        body.addProperty("decision", decision);
        return result(send("POST", "api/moderator/dispute-decision", body));
    }

    @Override
    public SecurityOverview securityOverview() {
        JsonObject reply = send("GET", "api/moderator/security", null);
        List<SecurityOverview.Alert> alerts = new ArrayList<>();
        for (JsonElement element : reply.getAsJsonArray("alerts")) {
            JsonObject o = element.getAsJsonObject();
            alerts.add(new SecurityOverview.Alert(text(o, "level"), text(o, "title"), text(o, "detail")));
        }
        List<SecurityOverview.Count> counts = new ArrayList<>();
        for (var entry : reply.getAsJsonObject("counts").entrySet()) {
            JsonObject o = entry.getValue().getAsJsonObject();
            counts.add(new SecurityOverview.Count(entry.getKey(), o.get("lastHour").getAsLong(), o.get("last24h").getAsLong()));
        }
        List<SecurityOverview.Event> recent = new ArrayList<>();
        for (JsonElement element : reply.getAsJsonArray("recent")) {
            JsonObject o = element.getAsJsonObject();
            recent.add(new SecurityOverview.Event(text(o, "at"), text(o, "type"), text(o, "actor"), text(o, "details")));
        }
        JsonObject i = reply.getAsJsonObject("integrity");
        boolean fileOk = i.get("fileChainValid").getAsBoolean();
        String integrity = "Audit file: " + (fileOk ? "intact" : "TAMPERED (" + text(i, "fileDetail") + ")") + ", " + i.get("fileLines").getAsLong() + " lines, "
                + i.get("anchorsChecked").getAsLong() + " database anchors checked"
                + (i.has("databaseChainValid") ? "; database audit chain " + (i.get("databaseChainValid").getAsBoolean() ? "intact" : "BROKEN") : "")
                + (i.has("newestAnchorAgeMinutes") ? "; newest anchor " + i.get("newestAnchorAgeMinutes").getAsLong() + " min ago" : "") + ".";
        return new SecurityOverview(text(reply, "status"), alerts, counts, recent, integrity);
    }

    @Override
    public void logout() {
        try {
            send("POST", "api/auth/logout", new JsonObject());
        } catch (ConsoleException ignored) {
            // nothing useful to do if the server is already gone
        }
        pendingUsername = null;
    }

    // ---------------------------------------------------------------- plumbing

    private void ensureSession() {
        JsonObject reply = send("GET", "api/auth/session", null);
        csrfToken = reply.get("csrfToken").getAsString();
    }

    private JsonObject send(String method, String path, JsonObject body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                .header("X-CSRF-Token", csrfToken);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonObject json = parse(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new ConsoleException(response.statusCode(), json.has("error") ? json.get("error").getAsString() : "");
            }
            return json;
        } catch (IOException exception) {
            throw new ConsoleException(0, "network");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConsoleException(0, "network");
        }
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement element = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException malformed) {
            return new JsonObject();
        }
    }

    private static String number(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : "-";
    }

    private static String text(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : "";
    }
}
