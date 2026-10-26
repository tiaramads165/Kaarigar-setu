package com.kaarigarsetu.security;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Sends one-time codes through any SMS gateway that accepts an HTTP POST. Nothing about a provider is hard-coded: the URL, the authentication
 * header, the body layout and the message wording all come from the environment (see docs/SMS_SETUP.md), so MSG91, Twilio or any other
 * gateway is a configuration change, and the key never touches the repository.
 *
 * <p>Failure is deliberately silent to the caller. Login code requests answer the same way whether or not an account exists; if a send
 * failure surfaced as an error it would reveal which phone numbers are registered. A failed send is logged (masked number, purpose, HTTP
 * status; never the code) and the user simply requests a new code.
 */
public final class HttpSmsOtpSender implements OtpSender {
    public record Config(String url, String authHeaderName, String authHeaderValue, boolean json, String bodyTemplate, String sender, String messageTemplate) {}

    private static final System.Logger LOG = System.getLogger("ks.sms");
    private final Config config;
    private final HttpClient http;

    public HttpSmsOtpSender(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void send(String normalizedPhone, String purpose, String code) {
        String masked = PhoneProtector.mask(normalizedPhone);
        try {
            String message = config.messageTemplate().replace("{code}", code).replace("{purpose}", purpose);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", config.json() ? "application/json" : "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body(normalizedPhone, message), StandardCharsets.UTF_8));
            if (!config.authHeaderName().isBlank()) request.header(config.authHeaderName(), config.authHeaderValue());
            int status = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status / 100 == 2) {
                LOG.log(System.Logger.Level.INFO, "[SMS] purpose={0} phone={1} accepted by gateway (HTTP {2})", purpose, masked, status);
            } else {
                LOG.log(System.Logger.Level.ERROR, "[SMS] SEND FAILED purpose={0} phone={1} gateway answered HTTP {2}", purpose, masked, status);
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "[SMS] SEND FAILED purpose={0} phone={1} ({2})", purpose, masked, failure.getClass().getSimpleName());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.log(System.Logger.Level.ERROR, "[SMS] SEND INTERRUPTED purpose={0} phone={1}", purpose, masked);
        }
    }

    /** Fills {to} (+91XXXXXXXXXX), {to_digits} (91XXXXXXXXXX), {to_local} (the 10 digits), {message} and {sender}, each escaped for the body format. */
    String body(String normalizedPhone, String message) {
        String digits = normalizedPhone.startsWith("+") ? normalizedPhone.substring(1) : normalizedPhone;
        Map<String, String> values = Map.of("{to}", normalizedPhone, "{to_digits}", digits, "{to_local}", digits.substring(Math.max(0, digits.length() - 10)),
                "{message}", message, "{sender}", config.sender());
        String body = config.bodyTemplate();
        for (Map.Entry<String, String> value : values.entrySet()) {
            body = body.replace(value.getKey(), config.json() ? jsonEscape(value.getValue()) : URLEncoder.encode(value.getValue(), StandardCharsets.UTF_8));
        }
        return body;
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.toString();
    }
}
