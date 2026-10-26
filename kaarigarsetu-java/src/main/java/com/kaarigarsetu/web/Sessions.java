package com.kaarigarsetu.web;

import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Session attribute names and the two security-critical operations on them: login (fixation-safe) and CSRF. */
public final class Sessions {
    public static final String ROLE = "role";
    public static final String USER_ID = "userId";
    public static final String LOGIN_AT = "loginAt";
    public static final String CSRF = "csrf";
    public static final String MODERATOR_PENDING = "moderatorPending";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Sessions() {}

    /** A fresh session id on every privilege change (session fixation), plus a fresh CSRF token. */
    public static String login(HttpServletRequest request, AuthenticatedUser user) {
        request.getSession(true);
        request.changeSessionId();
        HttpSession session = request.getSession();
        session.removeAttribute(MODERATOR_PENDING);
        session.setAttribute(ROLE, user.role().name());
        session.setAttribute(USER_ID, user.id());
        session.setAttribute(LOGIN_AT, Instant.now());
        return rotateCsrf(session);
    }

    public static Optional<AuthenticatedUser> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(ROLE) == null || session.getAttribute(USER_ID) == null) return Optional.empty();
        try {
            return Optional.of(new AuthenticatedUser(Role.valueOf((String) session.getAttribute(ROLE)), (Long) session.getAttribute(USER_ID)));
        } catch (IllegalArgumentException | ClassCastException malformed) {
            return Optional.empty();
        }
    }

    public static String csrfToken(HttpSession session) {
        Object existing = session.getAttribute(CSRF);
        return existing instanceof String token ? token : rotateCsrf(session);
    }

    public static String rotateCsrf(HttpSession session) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        session.setAttribute(CSRF, token);
        return token;
    }

    /** Constant-time comparison. */
    public static boolean csrfMatches(HttpSession session, String presented) {
        Object expected = session.getAttribute(CSRF);
        return expected instanceof String token && presented != null
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }
}
