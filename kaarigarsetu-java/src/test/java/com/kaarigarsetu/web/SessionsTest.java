package com.kaarigarsetu.web;

import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.security.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionsTest {
    private final Map<String, Object> attrs = new HashMap<>();
    private final HttpSession session = mock(HttpSession.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    SessionsTest() {
        doAnswer(i -> attrs.put(i.getArgument(0), i.getArgument(1))).when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString())).thenAnswer(i -> attrs.get(i.<String>getArgument(0)));
        doAnswer(i -> attrs.remove(i.<String>getArgument(0))).when(session).removeAttribute(anyString());
        when(request.getSession(true)).thenReturn(session);
        when(request.getSession()).thenReturn(session);
        when(request.getSession(false)).thenReturn(session);
    }

    @Test
    void loginChangesTheSessionIdAndIssuesAFreshCsrfToken() {
        attrs.put(Sessions.CSRF, "pre-login-token");
        attrs.put(Sessions.MODERATOR_PENDING, "half-logged-in");
        String token = Sessions.login(request, new AuthenticatedUser(Role.WORKER, 42));
        verify(request).changeSessionId();
        assertNotEquals("pre-login-token", token);
        assertEquals(token, attrs.get(Sessions.CSRF));
        assertNull(attrs.get(Sessions.MODERATOR_PENDING));
        assertEquals(new AuthenticatedUser(Role.WORKER, 42L), Sessions.current(request).orElseThrow());
    }

    @Test
    void aCorruptedSessionIsTreatedAsLoggedOut() {
        assertTrue(Sessions.current(request).isEmpty());
        attrs.put(Sessions.ROLE, "SUPERUSER");
        attrs.put(Sessions.USER_ID, 1L);
        assertTrue(Sessions.current(request).isEmpty());
        attrs.put(Sessions.ROLE, "WORKER");
        attrs.put(Sessions.USER_ID, "1");
        assertTrue(Sessions.current(request).isEmpty());
    }

    @Test
    void csrfTokensAreLongUniqueAndComparedStrictly() {
        String a = Sessions.rotateCsrf(session);
        String b = Sessions.rotateCsrf(session);
        assertNotEquals(a, b);
        assertTrue(a.length() >= 43);
        assertTrue(Sessions.csrfMatches(session, b));
        assertFalse(Sessions.csrfMatches(session, a));
        assertFalse(Sessions.csrfMatches(session, null));
        assertFalse(Sessions.csrfMatches(session, b + "x"));
    }
}
