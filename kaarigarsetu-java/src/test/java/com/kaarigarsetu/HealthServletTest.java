package com.kaarigarsetu;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthServletTest {
    @Test
    void reportsThatTheServiceIsRunning() throws Exception {
        StringWriter body = new StringWriter();
        PrintWriter writer = new PrintWriter(body);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(writer);

        new HealthServlet().doGet(mock(HttpServletRequest.class), response);
        writer.flush();

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString().contains("running"));
    }
}
