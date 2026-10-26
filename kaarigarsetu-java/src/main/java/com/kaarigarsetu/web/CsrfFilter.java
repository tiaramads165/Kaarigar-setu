package com.kaarigarsetu.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Every state-changing request (POST/PUT/PATCH/DELETE, including login and OTP requests) must carry the session's
 * CSRF token in X-CSRF-Token; a cross-origin Origin header is refused as defence in depth. The token is delivered
 * by GET /api/auth/session or the page's meta tag.
 */
public final class CsrfFilter implements Filter {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (SAFE.contains(http.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        HttpSession session = http.getSession(false);
        if (session == null || !Sessions.csrfMatches(session, http.getHeader("X-CSRF-Token")) || !sameOrigin(http)) {
            SecurityEvents.record(http, "CSRF_REJECTED");
            ApiErrors.write((HttpServletResponse) response, 403, "csrf");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) return true;   // non-browser clients; the token is still required
        try {
            URI uri = URI.create(origin);
            String host = request.getHeader("Host");
            if (host == null || uri.getHost() == null) return false;
            String originHost = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return originHost.equalsIgnoreCase(host);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }
}
