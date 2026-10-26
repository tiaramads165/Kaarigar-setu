package com.kaarigarsetu.credential;

import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.security.Role;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/credential/issue | GET /mine | GET /{ref}/verify (public) | GET /{ref}/qr (public) | GET /{ref}/download
 * | POST /verify (public, payload+signature) | POST /{ref}/revoke | GET /public-key (public).
 * Public verification trusts nothing from the client: it re-checks the stored payload, signature, revocation and expiry.
 */
@WebServlet(name = "CredentialServlet", urlPatterns = {"/api/credential/*"})
public class CredentialServlet extends BaseServlet {
    /** Public verification is open to anyone (employers scanning a QR), so it is throttled per address rather than refused. */
    static final int PUBLIC_VERIFY_PER_MINUTE = 120;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] parts = segments(request);
        if (parts.length == 1 && parts[0].equals("public-key")) {
            response.setContentType("application/x-pem-file");
            response.getWriter().write(app().credentials.signatures().publicKeyPem());
        } else if (parts.length == 1 && parts[0].equals("keys")) {
            throttle(request, "verify", PUBLIC_VERIFY_PER_MINUTE, java.time.Duration.ofMinutes(1));
            json(response, 200, Map.of("keys", app().credentials.publicKeys()));
        } else if (parts.length == 1 && parts[0].equals("revocations")) {
            throttle(request, "verify", PUBLIC_VERIFY_PER_MINUTE, java.time.Duration.ofMinutes(1));
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(app().credentials.revocationListDocument());
        } else if (parts.length == 1 && parts[0].equals("mine")) {
            List<Map<String, Object>> rows = app().credentials.listForWorker(user(request).id()).stream()
                    .map(c -> Map.<String, Object>of("ref", c.credentialRef(), "issuedAt", c.issuedAt().toString(),
                            "revoked", c.revoked(), "verifyUrl", app().credentials.verificationUrl(c.credentialRef())))
                    .toList();
            json(response, 200, Map.of("credentials", rows));
        } else if (parts.length == 2 && parts[1].equals("verify")) {
            throttle(request, "verify", PUBLIC_VERIFY_PER_MINUTE, java.time.Duration.ofMinutes(1));
            UUID ref = uuid(parts[0]);
            CredentialService.Verification result = app().credentials.verify(ref);
            Map<String, Object> body = verificationBody(result);
            var live = app().credentials.presentationStatus(ref, request.getParameter("p"));
            body.put("presentation", Map.of("state", live.state().name(), "ageSeconds", live.age().toSeconds()));
            json(response, result.state() == CredentialService.State.NOT_FOUND ? 404 : 200, body);
        } else if (parts.length == 2 && parts[1].equals("live-qr")) {
            UUID ref = uuid(parts[0]);
            requireOwner(request, ref);
            throttle(request, "live", 30, java.time.Duration.ofMinutes(1));
            var live = app().credentials.liveCode(ref, "worker:" + user(request).id());
            response.setContentType("image/png");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Live-Expires-At", live.expiresAt().toString());
            response.setContentLength(live.png().length);
            response.getOutputStream().write(live.png());
        } else if (parts.length == 2 && parts[1].equals("qr")) {
            throttle(request, "verify", PUBLIC_VERIFY_PER_MINUTE, java.time.Duration.ofMinutes(1));
            byte[] png = app().credentials.qrPng(uuid(parts[0]));
            response.setContentType("image/png");
            response.setContentLength(png.length);
            response.getOutputStream().write(png);
        } else if (parts.length == 2 && parts[1].equals("download")) {
            UUID ref = uuid(parts[0]);
            requireOwner(request, ref);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"kaarigar-setu-passport-" + ref + ".json\"");
            response.getOutputStream().write(app().credentials.signedDocument(ref).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new NotFoundException("Not found");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String[] parts = segments(request);
        if (parts.length == 1 && parts[0].equals("issue")) {
            AuthenticatedUser worker = user(request);
            CredentialService.Issued issued = app().credentials.issue(worker.id(), "worker:" + worker.id());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ref", issued.ref());
            body.put("issuedAt", issued.issuedAt().toString());
            body.put("expiresAt", issued.expiresAt().toString());
            body.put("verifyUrl", app().credentials.verificationUrl(issued.ref()));
            json(response, 201, body);
        } else if (parts.length == 1 && parts[0].equals("verify")) {
            throttle(request, "verify", PUBLIC_VERIFY_PER_MINUTE, java.time.Duration.ofMinutes(1));
            JsonObject body = readJson(request);
            json(response, 200, verificationBody(app().credentials.verifyRaw(string(body, "payload"), string(body, "signature"))));
        } else if (parts.length == 2 && parts[1].equals("revoke")) {
            UUID ref = uuid(parts[0]);
            AuthenticatedUser caller = user(request);
            if (caller.role() == Role.WORKER) requireOwner(request, ref);
            JsonObject body = readJson(request);
            String reason = optionalString(body, "reason");
            boolean changed = app().credentials.revoke(ref, reason, caller.role().name().toLowerCase() + ":" + caller.id());
            json(response, 200, Map.of("revoked", changed));
        } else {
            throw new NotFoundException("Not found");
        }
    }

    private void requireOwner(HttpServletRequest request, UUID ref) {
        long owner = app().credentials.ownerId(ref).orElseThrow(() -> new NotFoundException("Credential not found"));
        if (owner != user(request).id()) throw new NotFoundException("Credential not found");   // hide other workers' credentials
    }

    static Map<String, Object> verificationBody(CredentialService.Verification result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", result.state().name());
        body.put("valid", result.valid());
        if (result.credentialRef() != null) body.put("credentialRef", result.credentialRef());
        body.put("credential", result.summary());
        return body;
    }
}
