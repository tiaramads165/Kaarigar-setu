package com.kaarigarsetu.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CsrfFilterTest {
    private final CsrfFilter filter = new CsrfFilter();

    private boolean passes(String method, String sessionToken, String headerToken, String origin, String host) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeader("X-CSRF-Token")).thenReturn(headerToken);
        when(request.getHeader("Origin")).thenReturn(origin);
        when(request.getHeader("Host")).thenReturn(host);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        if (sessionToken != null) {
            HttpSession session = mock(HttpSession.class);
            when(session.getAttribute(Sessions.CSRF)).thenReturn(sessionToken);
            when(request.getSession(false)).thenReturn(session);
        }
        filter.doFilter(request, response, chain);
        try {
            verify(chain).doFilter(request, response);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    @Test
    void safeMethodsNeverNeedAToken() throws Exception {
        for (String m : new String[]{"GET", "HEAD", "OPTIONS"}) assertTrue(passes(m, null, null, null, "h"));
    }

    @Test
    void stateChangingRequestsNeedTheMatchingToken() throws Exception {
        for (String m : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            assertTrue(passes(m, "tok", "tok", null, "h"), m);
            assertFalse(passes(m, "tok", null, null, "h"), m + " missing");
            assertFalse(passes(m, "tok", "other", null, "h"), m + " wrong");
            assertFalse(passes(m, null, "tok", null, "h"), m + " no session");
        }
    }

    @Test
    void aCrossOriginRequestIsRefusedEvenWithAValidToken() throws Exception {
        assertTrue(passes("POST", "t", "t", "http://localhost:8090", "localhost:8090"));
        assertFalse(passes("POST", "t", "t", "http://evil.example", "localhost:8090"));
        assertFalse(passes("POST", "t", "t", "http://localhost:9999", "localhost:8090"));
        assertFalse(passes("POST", "t", "t", "::not a uri::", "localhost:8090"));
        assertFalse(passes("POST", "t", "t", "http://localhost:8090", null));
    }
}
