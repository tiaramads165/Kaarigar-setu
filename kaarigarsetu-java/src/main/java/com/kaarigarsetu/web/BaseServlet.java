package com.kaarigarsetu.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.security.AuthenticatedUser;
import com.kaarigarsetu.util.Json;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Shared plumbing: service lookup, JSON in/out, and one place where exceptions become HTTP errors. */
public abstract class BaseServlet extends HttpServlet {
    private static final int MAX_JSON_BYTES = 16 * 1024;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            super.service(request, response);
        } catch (ServletException | IOException containerFailure) {
            throw containerFailure;
        } catch (Exception failure) {
            if (failure instanceof com.kaarigarsetu.exception.RateLimitExceededException) SecurityEvents.record(request, "RATE_LIMITED");
            ApiErrors.write(response, failure);
        }
    }

    protected AppContext app() {
        return AppContext.of(getServletContext());
    }

    protected static void json(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.toJson(body));
    }

    /** A small JSON object body. Content-Type must be application/json (also a CSRF defence). */
    protected static JsonObject readJson(HttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase().startsWith("application/json")) throw new ValidationException("Expected JSON");
        if (request.getContentLengthLong() > MAX_JSON_BYTES) throw new ValidationException("Body too large");
        byte[] raw = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
        if (raw.length > MAX_JSON_BYTES) throw new ValidationException("Body too large");
        try {
            JsonElement element = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) throw new ValidationException("Expected a JSON object");
            return element.getAsJsonObject();
        } catch (RuntimeException failure) {
            if (failure instanceof ValidationException validation) throw validation;
            throw new ValidationException("Malformed JSON");
        }
    }

    protected static String string(JsonObject body, String field) {
        JsonElement element = body.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new ValidationException("Missing field " + field);
        return element.getAsString();
    }

    protected static String optionalString(JsonObject body, String field) {
        JsonElement element = body.get(field);
        return element == null || element.isJsonNull() ? null : (element.isJsonPrimitive() ? element.getAsString() : null);
    }

    protected static boolean bool(JsonObject body, String field) {
        JsonElement element = body.get(field);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    protected static AuthenticatedUser user(HttpServletRequest request) {
        return Sessions.current(request).orElseThrow(() -> new ForbiddenException("Not authenticated"));
    }

    protected static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException malformed) {
            throw new NotFoundException("Not found");
        }
    }

    protected static long number(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException malformed) {
            throw new NotFoundException("Not found");
        }
    }

    /** Client address for rate limiting; forwarded headers are honoured only when explicitly trusted. */
    protected String clientIp(HttpServletRequest request) {
        return SecurityEvents.clientIp(app(), request);
    }

    /**
     * Per-client-address throttle for PUBLIC endpoints (registration, credential verification). Raises RateLimitExceededException, which ApiErrors turns into
     * 429 with Retry-After. The address is only as trustworthy as {@link #clientIp}: keep TRUST_FORWARDED_FOR off unless a proxy overwrites the header.
     */
    protected void throttle(HttpServletRequest request, String bucket, int max, Duration window) {
        app().limiter.check(bucket + ":ip:" + app().phones.ipHash(clientIp(request)), max, window);
    }

    protected static String[] segments(HttpServletRequest request) {
        String info = request.getPathInfo();
        if (info == null || info.equals("/")) return new String[0];
        return info.substring(1).split("/", -1);
    }
}
