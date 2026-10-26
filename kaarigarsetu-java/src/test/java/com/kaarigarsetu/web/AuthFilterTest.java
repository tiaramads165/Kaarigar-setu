package com.kaarigarsetu.web;

import com.kaarigarsetu.security.AuthFilter;
import com.kaarigarsetu.security.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authorisation matrix: who may reach what, and that anything unlisted under /api/ is denied by default. */
class AuthFilterTest {
    private boolean moderatorEnabled = true;
    private final AuthFilter filter = new AuthFilter() {
        @Override
        public boolean moderatorStillActive(HttpServletRequest request, long moderatorId) {
            return moderatorEnabled;
        }
    };

    private record Outcome(boolean passedThrough, int status, String redirect) {}

    private Outcome call(String method, String servletPath, Role role, Instant loginAt) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getServletPath()).thenReturn(servletPath);
        when(request.getContextPath()).thenReturn("");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        if (role != null) {
            HttpSession session = mock(HttpSession.class);
            when(request.getSession(false)).thenReturn(session);
            when(session.getAttribute(Sessions.ROLE)).thenReturn(role.name());
            when(session.getAttribute(Sessions.USER_ID)).thenReturn(7L);
            when(session.getAttribute(Sessions.LOGIN_AT)).thenReturn(loginAt);
        }
        filter.doFilter(request, response, chain);
        boolean passed;
        try {
            verify(chain).doFilter(request, response);
            passed = true;
        } catch (AssertionError notCalled) {
            passed = false;
        }
        var status = org.mockito.ArgumentCaptor.forClass(Integer.class);
        int code = 200;
        try {
            verify(response).setStatus(status.capture());
            code = status.getValue();
        } catch (AssertionError | org.mockito.exceptions.base.MockitoException ignored) {
            // may have used sendError / redirect instead
        }
        String redirect = null;
        try {
            var captor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(response).sendRedirect(captor.capture());
            redirect = captor.getValue();
        } catch (AssertionError ignored) {
            // no redirect
        }
        return new Outcome(passed, code, redirect);
    }

    private static final Instant NOW = Instant.now();

    @Test
    void publicRoutesNeedNoSession() throws Exception {
        for (String path : new String[]{"/health", "/static/js/app.js", "/api/auth/login/otp"}) assertTrue(call("POST", path, null, null).passedThrough(), path);
        assertTrue(call("GET", "/", null, null).passedThrough());
        assertTrue(call("GET", "/api/credential/public-key", null, null).passedThrough());
        assertTrue(call("POST", "/api/worker/register", null, null).passedThrough());
    }

    @Test
    void theDemoPageIsReachableWithoutASessionBecauseItsServletDecidesWhetherItExists() throws Exception {
        assertTrue(call("GET", "/demo", null, null).passedThrough());
        assertTrue(call("POST", "/api/auth/demo-login", null, null).passedThrough(), "a public auth route; AuthService.demoLogin refuses it unless enabled in dev, for demo accounts only");
    }

    @Test
    void unauthenticatedApiCallsGet401AndPagesRedirect() throws Exception {
        Outcome api = call("GET", "/api/worker/profile", null, null);
        assertFalse(api.passedThrough());
        assertEquals(401, api.status());
        Outcome page = call("GET", "/worker-dashboard", null, null);
        assertFalse(page.passedThrough());
        assertEquals("/", page.redirect());
    }

    @Test
    void eachRoleReachesOnlyItsOwnRoutes() throws Exception {
        assertTrue(call("GET", "/api/worker/profile", Role.WORKER, NOW).passedThrough());
        assertTrue(call("POST", "/api/assessment/start", Role.WORKER, NOW).passedThrough());
        assertTrue(call("POST", "/api/attestation/confirm", Role.EMPLOYER, NOW).passedThrough());
        assertTrue(call("GET", "/api/moderator/flagged", Role.MODERATOR, NOW).passedThrough());

        for (Role wrong : new Role[]{Role.EMPLOYER, Role.MODERATOR}) assertEquals(403, call("GET", "/api/worker/profile", wrong, NOW).status());
        for (Role wrong : new Role[]{Role.WORKER, Role.MODERATOR}) assertEquals(403, call("POST", "/api/attestation/confirm", wrong, NOW).status());
        for (Role wrong : new Role[]{Role.WORKER, Role.EMPLOYER}) assertEquals(403, call("GET", "/api/moderator/flagged", wrong, NOW).status());
    }

    @Test
    void employerAccountEndpointsAreEmployerOnly() throws Exception {
        assertTrue(call("POST", "/api/employer/register", null, null).passedThrough(), "registration stays public");
        assertTrue(call("POST", "/api/employer/erase", Role.EMPLOYER, NOW).passedThrough());
        assertEquals(403, call("POST", "/api/employer/erase", Role.WORKER, NOW).status(), "a worker must not reach employer erasure");
        assertEquals(403, call("POST", "/api/employer/erase", Role.MODERATOR, NOW).status());
        assertEquals(401, call("POST", "/api/employer/erase", null, null).status());
        assertTrue(call("POST", "/api/worker/erase", Role.WORKER, NOW).passedThrough());
        assertEquals(403, call("POST", "/api/worker/erase", Role.EMPLOYER, NOW).status());
    }

    @Test
    void revokeIsOpenToWorkersAndModeratorsButNotEmployers() throws Exception {
        assertTrue(call("POST", "/api/credential/abc/revoke", Role.WORKER, NOW).passedThrough());
        assertTrue(call("POST", "/api/credential/abc/revoke", Role.MODERATOR, NOW).passedThrough());
        assertEquals(403, call("POST", "/api/credential/abc/revoke", Role.EMPLOYER, NOW).status());
        assertEquals(401, call("POST", "/api/credential/abc/revoke", null, null).status());
    }

    @Test
    void anUnlistedApiRouteIsDeniedByDefaultForAnonymousButNeedsOnlyLoginOtherwise() throws Exception {
        assertEquals(401, call("GET", "/api/brand-new-endpoint", null, null).status());
        assertTrue(call("GET", "/api/brand-new-endpoint", Role.WORKER, NOW).passedThrough(), "authenticated, no role rule");
    }

    @Test
    void pathTrickeryDoesNotMatchAPublicRule() throws Exception {
        assertEquals(401, call("GET", "/api/worker/../worker/profile", null, null).status());
        assertEquals(401, call("GET", "/api/worker/profile/", null, null).status());
        assertEquals(401, call("GET", "/api/credential/x/y/verify", null, null).status());
    }

    @Test
    void aSessionOlderThanEightHoursIsRefusedAndInvalidated() throws Exception {
        Outcome o = call("GET", "/api/worker/profile", Role.WORKER, NOW.minusSeconds(8 * 3600 + 5));
        assertFalse(o.passedThrough());
        assertEquals(401, o.status());
        Outcome noStamp = call("GET", "/api/worker/profile", Role.WORKER, null);
        assertEquals(401, noStamp.status());
    }

    @Test
    void aModeratorWhoseAccountWasDisabledIsLockedOutAtOnceAndTheSessionEnds() throws Exception {
        assertTrue(call("GET", "/api/moderator/pending", Role.MODERATOR, NOW).passedThrough(), "control: an enabled moderator passes");
        moderatorEnabled = false;
        Outcome out = call("GET", "/api/moderator/pending", Role.MODERATOR, NOW);
        assertFalse(out.passedThrough());
        assertEquals(401, out.status());
        assertFalse(call("POST", "/api/credential/abc/revoke", Role.MODERATOR, NOW).passedThrough(), "on every route a moderator can reach, not only /api/moderator");
        moderatorEnabled = true;
        assertTrue(call("GET", "/api/worker/me/passport", Role.WORKER, NOW).passedThrough(), "the check concerns moderators only");
    }

    @Test
    void withoutAnApplicationContextTheModeratorCheckFailsClosed() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getServletContext()).thenReturn(null);
        assertFalse(new AuthFilter().moderatorStillActive(request, 1L));
        jakarta.servlet.ServletContext context = mock(jakarta.servlet.ServletContext.class);
        when(request.getServletContext()).thenReturn(context);
        assertFalse(new AuthFilter().moderatorStillActive(request, 1L), "no application attribute: no way to know, so no");
    }
}
