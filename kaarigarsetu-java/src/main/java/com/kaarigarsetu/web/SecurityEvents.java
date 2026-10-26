package com.kaarigarsetu.web;

import com.kaarigarsetu.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Duration;

/**
 * Records security-relevant refusals (rejected CSRF token, a role reaching for another role's endpoint, a client hitting a rate limit) in the audit trail
 * so the moderator console can show them. An attacker must not be able to turn this into a flood of audit rows, so each client address is recorded at most
 * once per event type per minute: the counts therefore mean "distinct clients per minute", not "requests". It never throws: monitoring must not break a request.
 */
public final class SecurityEvents {
    private static final Duration ONE_PER = Duration.ofMinutes(1);

    private SecurityEvents() {
    }

    public static void record(HttpServletRequest request, String eventType) {
        try {
            AppContext app = AppContext.of(request.getServletContext());
            String ipHash = app.phones.ipHash(clientIp(app, request));
            try {
                app.limiter.check("audit:" + eventType + ":" + ipHash, 1, ONE_PER);
            } catch (RateLimitExceededException alreadyRecorded) {
                return;
            }
            String actor = Sessions.current(request).map(u -> u.role().name().toLowerCase() + ":" + u.id()).orElse("anonymous");
            String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
            app.audit.log(eventType, actor, "method=" + request.getMethod() + " path=" + path + " client=" + ipHash.substring(0, 12));
        } catch (RuntimeException monitoringFailure) {
            System.getLogger("ks.security").log(System.Logger.Level.WARNING, "could not record " + eventType + ": " + monitoringFailure.getMessage());
        }
    }

    /** The client address for rate limiting and the audit trail; X-Forwarded-For counts only when the deployment says a trusted proxy sets it. */
    public static String clientIp(AppContext app, HttpServletRequest request) {
        if (app.settings.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] parts = forwarded.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
