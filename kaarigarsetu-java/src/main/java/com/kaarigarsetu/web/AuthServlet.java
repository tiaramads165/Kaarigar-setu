package com.kaarigarsetu.web;

import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.security.AuthService;
import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.security.ProofOfWork;
import com.kaarigarsetu.security.Role;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * GET /api/auth/session | POST /api/auth/otp/request | otp/verify | moderator/login | moderator/verify | logout.
 * Workers and employers sign in with phone + OTP; moderators with their own named account's password AND an OTP.
 *
 * <p>Two HttpOnly cookies, both limited to /api/auth, support the code limits (see AuthService.requestLoginOtp): {@code ks_device} marks a browser that has
 * signed in as an account before (its own request budget and code), and {@code ks_bind} ties a code to the browser that asked for it. Neither lets anyone
 * sign in; the code is still required.
 */
@WebServlet(name = "AuthServlet", urlPatterns = {"/api/auth/*"})
public final class AuthServlet extends BaseServlet {
    static final String DEVICE_COOKIE = "ks_device";
    static final String BIND_COOKIE = "ks_bind";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!String.join("/", segments(request)).equals("session")) throw new NotFoundException("Not found");
        HttpSession session = request.getSession(true);
        Map<String, Object> body = new LinkedHashMap<>();
        Optional<AuthenticatedUser> user = Sessions.current(request);
        body.put("authenticated", user.isPresent());
        user.ifPresent(u -> {
            body.put("role", u.role().name());
            body.put("userId", u.id());
        });
        body.put("csrfToken", Sessions.csrfToken(session));
        json(response, 200, body);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (String.join("/", segments(request))) {
            case "otp/request" -> {
                JsonObject body = readJson(request);
                AuthService.OtpRequestOutcome outcome = app().auth.requestLoginOtp(role(body), string(body, "phone"), clientIp(request), cookies(request), proof(body));
                if (outcome.bindToken() != null) setCookie(response, BIND_COOKIE, outcome.bindToken(), 600);
                json(response, 200, Map.of("ok", true, "ttlSeconds", app().settings.otpTtlSeconds()));
            }
            case "otp/verify" -> {
                JsonObject body = readJson(request);
                AuthService.LoginOutcome outcome = app().auth.verifyLogin(role(body), string(body, "phone"), string(body, "code"), clientIp(request), cookies(request));
                setCookie(response, DEVICE_COOKIE, outcome.deviceToken(), app().settings.trustedDeviceDays() * 86_400);
                setCookie(response, BIND_COOKIE, "", 0);                                          // the code is spent: forget its binding
                json(response, 200, loginBody(outcome.user(), Sessions.login(request, outcome.user())));
            }
            case "moderator/login" -> {
                JsonObject body = readJson(request);
                String username = string(body, "username");
                app().auth.moderatorStart(username, string(body, "password").toCharArray(), clientIp(request));
                request.getSession(true).setAttribute(Sessions.MODERATOR_PENDING, username);
                json(response, 200, Map.of("otpRequired", true));
            }
            case "moderator/verify" -> {
                JsonObject body = readJson(request);
                HttpSession session = request.getSession(false);
                Object pending = session == null ? null : session.getAttribute(Sessions.MODERATOR_PENDING);
                if (!(pending instanceof String username)) throw new ValidationException("Start the moderator login first");
                AuthenticatedUser user = app().auth.moderatorVerify(username, string(body, "code"), clientIp(request));
                json(response, 200, loginBody(user, Sessions.login(request, user)));
            }
            case "demo-login" -> {
                JsonObject body = readJson(request);
                long id = body.has("id") && body.get("id").isJsonPrimitive() && body.get("id").getAsJsonPrimitive().isNumber() ? body.get("id").getAsLong() : -1;
                AuthenticatedUser user = app().auth.demoLogin(role(body), id);
                json(response, 200, loginBody(user, Sessions.login(request, user)));
            }
            case "logout" -> {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                json(response, 200, Map.of("ok", true));
            }
            default -> throw new NotFoundException("Not found");
        }
    }

    private static AuthService.DeviceCookies cookies(HttpServletRequest request) {
        String device = null;
        String bind = null;
        Cookie[] sent = request.getCookies();
        if (sent != null) {
            for (Cookie cookie : sent) {
                if (cookie.getName().equals(DEVICE_COOKIE)) device = cookie.getValue();
                else if (cookie.getName().equals(BIND_COOKIE)) bind = cookie.getValue();
            }
        }
        return new AuthService.DeviceCookies(device, bind);
    }

    /** The proof-of-work answer, if the client sent one: {"pow": {"challenge": "...", "counter": "12345"}}. */
    private static ProofOfWork.Solution proof(JsonObject body) {
        if (!body.has("pow") || !body.get("pow").isJsonObject()) return null;
        JsonObject pow = body.getAsJsonObject("pow");
        return new ProofOfWork.Solution(optionalString(pow, "challenge"), optionalString(pow, "counter"));   // a malformed answer is simply not a solution: the client gets a new puzzle
    }

    private void setCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(app().settings.cookieSecure());
        cookie.setPath("/api/auth");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private static Map<String, Object> loginBody(AuthenticatedUser user, String csrfToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("role", user.role().name());
        body.put("userId", user.id());
        body.put("csrfToken", csrfToken);
        return body;
    }

    private static Role role(JsonObject body) {
        try {
            return Role.valueOf(string(body, "role").toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("Unsupported role");
        }
    }
}
