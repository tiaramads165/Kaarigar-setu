package com.kaarigarsetu.attestation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.web.BaseServlet;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** POST /api/attestation/request-otp | POST /api/attestation/verify-otp | GET /api/attestation/mine. Employer role only. */
@WebServlet(name = "AttestationServlet", urlPatterns = {"/api/attestation/*"})
public class AttestationServlet extends BaseServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        long employerId = user(request).id();
        JsonObject body = readJson(request);
        switch (String.join("/", segments(request))) {
            case "request-otp" -> {
                LocalDate date;
                try {
                    date = LocalDate.parse(string(body, "workDate"));
                } catch (DateTimeParseException malformed) {
                    throw new ValidationException("Bad date");
                }
                String device = app().phones.deviceHash(String.valueOf(request.getHeader("User-Agent")), String.valueOf(request.getHeader("Accept-Language")));
                Integer quality = optionalInt(body, "qualityRating"), punctuality = optionalInt(body, "punctualityRating"), conduct = optionalInt(body, "conductRating");
                if (quality == null || punctuality == null || conduct == null) throw new ValidationException("Please rate quality, punctuality and conduct");
                var requested = app().attestations.requestOtp(employerId, string(body, "workerPhone"), string(body, "jobRole"),
                        optionalInt(body, "durationDays"), optionalBoolean(body, "wouldHireAgain"), date, clientIp(request), device,
                        quality, punctuality, conduct);
                json(response, 201, requested);
            }
            case "verify-otp" -> {
                JsonElement id = body.get("attestationId");
                if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) throw new ValidationException("attestationId is required");
                json(response, 200, app().attestations.verifyOtp(employerId, id.getAsLong(), string(body, "code"), clientIp(request)));
            }
            default -> throw new NotFoundException("Not found");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!String.join("/", segments(request)).equals("mine")) throw new NotFoundException("Not found");
        json(response, 200, Map.of("attestations", app().attestations.forEmployer(user(request).id())));
    }

    private static Integer optionalInt(JsonObject body, String field) {
        JsonElement element = body.get(field);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new ValidationException(field + " must be a number");
        return element.getAsInt();
    }

    private static Boolean optionalBoolean(JsonObject body, String field) {
        JsonElement element = body.get(field);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw new ValidationException(field + " must be true or false");
        return element.getAsBoolean();
    }
}
