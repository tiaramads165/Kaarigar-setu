package com.kaarigarsetu.security;

import com.kaarigarsetu.web.ApiErrors;
import com.kaarigarsetu.web.Sessions;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Role-based access control by an ordered rule list (first match wins) with DENY BY DEFAULT: anything under /api/ that
 * matches no rule needs an authenticated session. Workers, employers and moderators each reach only their own
 * routes. API callers get JSON 401/403; browser pages are redirected to the start page. Sessions also have an
 * absolute lifetime on top of the container's idle timeout.
 */
public class AuthFilter implements Filter {
    private static final Duration MAX_SESSION_AGE = Duration.ofHours(8);
    private static final Set<Role> PUBLIC = Set.of();

    /** pattern segments: "*" = exactly one segment, "**" = anything below; method null = any. */
    private record Rule(String method, String pattern, Set<Role> roles) {
        boolean matches(String requestMethod, String path) {
            if (method != null && !method.equals(requestMethod)) return false;
            String[] want = pattern.split("/");
            String[] have = path.split("/");
            for (int i = 0; i < want.length; i++) {
                if (want[i].equals("**")) return true;
                if (i >= have.length) return false;
                if (!want[i].equals("*") && !want[i].equals(have[i])) return false;
            }
            return want.length == have.length;
        }
    }

    private static final List<Rule> RULES = List.of(
            // ---- public ----
            new Rule(null, "/static/**", PUBLIC),
            new Rule(null, "/health", PUBLIC),
            new Rule("GET", "/", PUBLIC),
            new Rule("GET", "/demo", PUBLIC),                      // the servlet itself answers 404 unless demo sign-in is enabled (dev only)
            new Rule("GET", "/verify/*", PUBLIC),
            new Rule(null, "/api/auth/**", PUBLIC),
            new Rule("POST", "/api/worker/register", PUBLIC),
            new Rule("POST", "/api/employer/register", PUBLIC),
            new Rule("GET", "/api/credential/public-key", PUBLIC),
            new Rule("GET", "/api/credential/keys", PUBLIC),
            new Rule("GET", "/api/credential/revocations", PUBLIC),
            new Rule("POST", "/api/credential/verify", PUBLIC),
            new Rule("GET", "/api/credential/*/verify", PUBLIC),
            new Rule("GET", "/api/credential/*/qr", PUBLIC),
            // ---- shared worker + moderator ----
            new Rule("POST", "/api/credential/*/revoke", Set.of(Role.WORKER, Role.MODERATOR)),
            // ---- worker ----
            new Rule(null, "/api/worker/**", Set.of(Role.WORKER)),
            new Rule(null, "/api/assessment/**", Set.of(Role.WORKER)),
            new Rule("POST", "/api/credential/issue", Set.of(Role.WORKER)),
            new Rule("GET", "/api/credential/mine", Set.of(Role.WORKER)),
            new Rule("GET", "/api/credential/*/download", Set.of(Role.WORKER)),
            new Rule("GET", "/api/credential/*/live-qr", Set.of(Role.WORKER)),
            new Rule("GET", "/worker-dashboard", Set.of(Role.WORKER)),
            new Rule("GET", "/assessment", Set.of(Role.WORKER)),
            // ---- employer ----
            new Rule(null, "/api/attestation/**", Set.of(Role.EMPLOYER)),
            new Rule(null, "/api/employer/**", Set.of(Role.EMPLOYER)),
            new Rule("GET", "/employer-dashboard", Set.of(Role.EMPLOYER)),
            // ---- moderator ----
            new Rule(null, "/api/moderator/**", Set.of(Role.MODERATOR)));

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse out = (HttpServletResponse) response;
        String path = http.getServletPath() + (http.getPathInfo() == null ? "" : http.getPathInfo());
        boolean api = path.startsWith("/api/");
        Optional<Rule> rule = RULES.stream().filter(r -> r.matches(http.getMethod(), path)).findFirst();

        if (rule.isPresent() && rule.get().roles().isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (rule.isEmpty() && !api) {
            chain.doFilter(request, response);   // unlisted non-API paths (error pages) are not data endpoints
            return;
        }

        HttpSession session = http.getSession(false);
        Optional<AuthenticatedUser> user = session == null ? Optional.empty() : Sessions.current(http);
        if (user.isEmpty()) {
            deny(http, out, api, 401, "unauthenticated");
            return;
        }
        Instant loginAt = (Instant) session.getAttribute(Sessions.LOGIN_AT);
        if (loginAt == null || Duration.between(loginAt, Instant.now()).compareTo(MAX_SESSION_AGE) > 0) {
            session.invalidate();
            deny(http, out, api, 401, "unauthenticated");
            return;
        }
        if (rule.isPresent() && !rule.get().roles().contains(user.get().role())) {
            com.kaarigarsetu.web.SecurityEvents.record(http, "ACCESS_DENIED");      // a signed-in user reaching for another role's endpoint
            deny(http, out, api, 403, "forbidden");
            return;
        }
        if (user.get().role() == Role.MODERATOR && !moderatorStillActive(http, user.get().id())) {
            session.invalidate();          // the account was disabled after this session started: it stops working now, not at the end of its 8 hours
            deny(http, out, api, 401, "unauthenticated");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Is this moderator's account still enabled? Fails closed: with no application context there is no way to tell, so the answer is no. */
    public boolean moderatorStillActive(HttpServletRequest request, long moderatorId) {
        jakarta.servlet.ServletContext context = request.getServletContext();
        Object app = context == null ? null : context.getAttribute(com.kaarigarsetu.web.AppContext.ATTRIBUTE);
        return app instanceof com.kaarigarsetu.web.AppContext application
                && application.moderatorDao.findById(moderatorId).filter(com.kaarigarsetu.model.Moderator::active).isPresent();
    }

    private static void deny(HttpServletRequest request, HttpServletResponse response, boolean api, int status, String code) throws IOException {
        if (api) ApiErrors.write(response, status, code);
        else response.sendRedirect(request.getContextPath() + "/");
    }
}
