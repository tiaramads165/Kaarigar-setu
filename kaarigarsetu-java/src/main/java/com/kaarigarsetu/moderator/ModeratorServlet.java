package com.kaarigarsetu.moderator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.AuthenticationFailedException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moderator role only; every action is attributed to the signed-in NAMED moderator. GET flagged, employers, disputes, security, audit-check, me, pending.
 * POST decision, vet-employer, dispute-decision (these two answer 202 and wait for a second moderator when two-person approval is on),
 * pending/{id}/approve and pending/{id}/reject.
 */
@WebServlet(name = "ModeratorServlet", urlPatterns = {"/api/moderator/*"})
public class ModeratorServlet extends BaseServlet {

    /** The signed-in moderator, re-read on every call so a disabled account stops working at once. */
    private Moderator moderator(HttpServletRequest request) {
        return app().moderatorDao.findById(user(request).id()).filter(Moderator::active).orElseThrow(() -> new AuthenticationFailedException("Moderator account is not active"));
    }

    private static Map<String, Object> pendingRow(ModeratorService.PendingView view) {
        var a = view.action();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actionId", a.actionId());
        row.put("type", a.type());
        row.put("targetId", a.targetId());
        row.put("target", view.target());
        row.put("verdict", a.verdict());
        row.put("proposedBy", a.proposedByUsername());
        row.put("proposedAt", a.proposedAt().toString());
        row.put("expiresAt", view.expiresAt().toString());
        row.put("canDecide", view.canDecide());
        return row;
    }

    private void outcome(HttpServletResponse response, ModeratorService.Outcome outcome, String doneKey) throws IOException {
        if (outcome.executed()) json(response, 200, Map.of(doneKey, true));
        else json(response, 202, Map.of("pending", true, "actionId", outcome.actionId()));
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (String.join("/", segments(request))) {
            case "flagged" -> json(response, 200, Map.of("flagged", app().moderation.queue()));
            case "audit-check" -> {
                var anchors = app().audit.anchors(500);
                var file = app().auditFile.verify(anchors);          // chain + keyed head + the copies pinned in the database
                app().audit.anchorFileHead();                         // pin everything up to now, so the next check covers it
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("fileChainValid", file.valid());
                body.put("fileLines", file.lines());
                body.put("fileDetail", file.reason());
                body.put("anchorsChecked", anchors.size());
                app().audit.databaseChain().ifPresent(chain -> {
                    body.put("databaseChainValid", chain.valid());
                    body.put("databaseRows", chain.checked());
                });
                json(response, 200, body);
            }
            case "employers" -> {
                boolean onlyUnvetted = !"1".equals(request.getParameter("all"));
                int offset = 0;
                String requested = request.getParameter("offset");
                if (requested != null) {
                    try {
                        offset = Integer.parseInt(requested);
                    } catch (NumberFormatException notANumber) {
                        throw new ValidationException("offset must be a number");
                    }
                    if (offset < 0 || offset > 1_000_000) throw new ValidationException("offset out of range");
                }
                json(response, 200, Map.of("employers", app().moderation.employers(onlyUnvetted, offset), "offset", offset, "pageSize", 200));
            }
            case "disputes" -> json(response, 200, Map.of("disputes", app().moderation.disputes()));
            case "me" -> {
                Moderator me = moderator(request);
                json(response, 200, Map.of("moderatorId", me.moderatorId(), "username", me.username(), "displayName", me.displayName(),
                        "twoPersonActions", app().settings.twoPersonActions(), "lifetimeHours", app().settings.twoPersonLifetimeHours()));
            }
            case "pending" -> json(response, 200, Map.of("pending", app().moderation.pending(moderator(request)).stream().map(ModeratorServlet::pendingRow).toList()));
            case "security" -> json(response, 200, app().securityMonitor.report());
            default -> throw new NotFoundException("Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String action = String.join("/", segments(request));
        JsonObject body = readJson(request);
        Moderator me = moderator(request);
        String[] parts = segments(request);
        if (parts.length == 3 && parts[0].equals("pending") && (parts[2].equals("approve") || parts[2].equals("reject"))) {
            long id;
            try {
                id = Long.parseLong(parts[1]);
            } catch (NumberFormatException notANumber) {
                throw new NotFoundException("Not found");
            }
            if (parts[2].equals("approve")) {
                app().moderation.approve(id, me);
                json(response, 200, Map.of("approved", true));
            } else {
                app().moderation.reject(id, me);
                json(response, 200, Map.of("closed", true));
            }
            return;
        }
        switch (action) {
            case "decision" -> {
                JsonElement flag = body.get("flagId");
                if (flag == null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isNumber()) throw new ValidationException("flagId is required");
                boolean approved = app().moderation.decide(flag.getAsLong(), string(body, "decision"), me.username());
                json(response, 200, Map.of("approved", approved));
            }
            case "dispute-decision" -> {
                JsonElement attestation = body.get("attestationId");
                if (attestation == null || !attestation.isJsonPrimitive() || !attestation.getAsJsonPrimitive().isNumber()) throw new ValidationException("attestationId is required");
                outcome(response, app().moderation.decideDispute(attestation.getAsLong(), string(body, "decision"), me), "decided");
            }
            case "vet-employer" -> {
                JsonElement employer = body.get("employerId");
                if (employer == null || !employer.isJsonPrimitive() || !employer.getAsJsonPrimitive().isNumber()) throw new ValidationException("employerId is required");
                outcome(response, app().moderation.vetEmployer(employer.getAsLong(), me), "vetted");
            }
            default -> throw new NotFoundException("Not found");
        }
    }
}
