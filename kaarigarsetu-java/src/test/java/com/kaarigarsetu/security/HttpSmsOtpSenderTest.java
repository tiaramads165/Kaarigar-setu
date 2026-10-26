package com.kaarigarsetu.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpSmsOtpSenderTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>(), contentType = new AtomicReference<>(), path = new AtomicReference<>();
    private volatile int answer = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            path.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/send"; }

    private HttpSmsOtpSender sender(boolean json, String template, String message) {
        return new HttpSmsOtpSender(new HttpSmsOtpSender.Config(url(), "X-Api-Key", "secret-key-value", json, template, "KSETU", message));
    }

    @Test
    void jsonGatewayReceivesTheKeyThePhoneAndTheMessage() {
        sender(true, "{\"from\":\"{sender}\",\"to\":\"{to_digits}\",\"text\":\"{message}\"}", "Your code is {code}. \"Do not share\".")
                .send("+919876543210", "login", "123456");
        assertEquals("/v1/send", path.get());
        assertEquals("secret-key-value", auth.get());
        assertEquals("application/json", contentType.get());
        assertEquals("{\"from\":\"KSETU\",\"to\":\"919876543210\",\"text\":\"Your code is 123456. \\\"Do not share\\\".\"}", body.get());
    }

    @Test
    void formGatewayGetsUrlEncodedValues() {
        sender(false, "To={to}&Body={message}&From={sender}", "Code {code} ok").send("+919876543210", "attestation", "654321");
        assertEquals("application/x-www-form-urlencoded", contentType.get());
        assertEquals("To=%2B919876543210&Body=Code+654321+ok&From=KSETU", body.get());
    }

    @Test
    void theLocalNumberPlaceholderIsTheTenDigits() {
        sender(true, "{\"n\":\"{to_local}\"}", "{code}").send("+919876543210", "login", "111111");
        assertEquals("{\"n\":\"9876543210\"}", body.get());
    }

    @Test
    void aGatewayErrorNeverSurfacesToTheCaller_soUnknownAndKnownNumbersStayIndistinguishable() {
        answer = 500;
        assertDoesNotThrow(() -> sender(true, "{}", "{code}").send("+919876543210", "login", "222222"));
    }

    @Test
    void anUnreachableGatewayNeverSurfacesToTheCallerEither() {
        HttpSmsOtpSender down = new HttpSmsOtpSender(new HttpSmsOtpSender.Config("http://127.0.0.1:1/none", "X-Api-Key", "k", true, "{}", "S", "{code}"));
        assertDoesNotThrow(() -> down.send("+919876543210", "login", "333333"));
    }

    @Test
    void controlCharactersInAMessageCannotBreakOutOfTheJsonBody() {
        sender(true, "{\"t\":\"{message}\"}", "a\nb\\c").send("+919876543210", "login", "1");
        assertEquals("{\"t\":\"a\\nb\\\\c\"}", body.get());
    }
}
