package com.kaarigarsetu.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Response hardening on every response. The CSP forbids inline script and style, so pages carry no inline JS/CSS
 * and an injected script tag would not run (defence in depth behind output encoding).
 */
public final class SecurityHeadersFilter implements Filter {
    static final String CSP = String.join("; ",
            "default-src 'none'", "script-src 'self'", "style-src 'self'", "img-src 'self' data:", "media-src 'self' blob:",
            "connect-src 'self'", "form-action 'self'", "base-uri 'none'", "frame-ancestors 'none'");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        out.setHeader("Content-Security-Policy", CSP);
        out.setHeader("X-Content-Type-Options", "nosniff");
        out.setHeader("X-Frame-Options", "DENY");
        out.setHeader("Referrer-Policy", "no-referrer");
        out.setHeader("Permissions-Policy", "microphone=(self), camera=(), geolocation=(), payment=()");
        out.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        if (http.isSecure() || "https".equalsIgnoreCase(http.getHeader("X-Forwarded-Proto"))) {
            out.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        // Reproducible WAR builds stamp files with a 1980 mtime that browsers read as "fresh for years": revalidate.
        out.setHeader("Cache-Control", http.getServletPath().startsWith("/static") ? "no-cache" : "no-store");
        chain.doFilter(request, response);
    }
}
