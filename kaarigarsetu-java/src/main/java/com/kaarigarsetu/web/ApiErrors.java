package com.kaarigarsetu.web;

import com.kaarigarsetu.exception.AuthenticationFailedException;
import com.kaarigarsetu.exception.ChallengeException;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.ConsentRequiredException;
import com.kaarigarsetu.exception.DuplicateAttestationException;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.exception.LowTrustScoreException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ProofOfWorkRequiredException;
import com.kaarigarsetu.exception.RateLimitExceededException;
import com.kaarigarsetu.exception.UnintelligibleAudioException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.util.Json;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to an HTTP status and a short machine code. Bodies never contain exception messages, stack traces
 * or SQL: clients show their own text for the code, and unexpected failures are logged under a random reference id
 * that is returned so support can find them.
 */
public final class ApiErrors {
    private static final System.Logger LOG = System.getLogger("ks.web.errors");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiErrors() {}

    public static void write(HttpServletResponse response, Throwable failure) throws IOException {
        if (response.isCommitted()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        int status;
        String code;
        if (failure instanceof ConsentRequiredException) { status = 403; code = "consent_required"; }
        else if (failure instanceof InvalidOTPException) { status = 401; code = "invalid_otp"; }
        else if (failure instanceof AuthenticationFailedException) { status = 401; code = "invalid_credentials"; }
        else if (failure instanceof ForbiddenException) { status = 403; code = "forbidden"; }
        else if (failure instanceof LowTrustScoreException) { status = 403; code = "low_trust"; }
        else if (failure instanceof RateLimitExceededException limited) {
            status = 429; code = "rate_limited";
            response.setHeader("Retry-After", String.valueOf(limited.retryAfterSeconds()));
        }
        else if (failure instanceof ProofOfWorkRequiredException puzzle) {
            status = 428; code = "proof_of_work_required";
            body.put("challenge", puzzle.challenge());
            body.put("bits", puzzle.bits());
        }
        else if (failure instanceof ChallengeException challenge) {
            boolean notHeard = challenge.kind() == ChallengeException.Kind.NOT_HEARD;
            status = notHeard ? 422 : 428;
            code = notHeard ? "code_not_heard" : "challenge_required";
        }
        else if (failure instanceof UnintelligibleAudioException) { status = 422; code = "unintelligible"; }
        else if (failure instanceof ValidationException) { status = 400; code = "invalid_request"; }
        else if (failure instanceof NotFoundException) { status = 404; code = "not_found"; }
        else if (failure instanceof DuplicateAttestationException) { status = 409; code = "duplicate"; }
        else if (failure instanceof ConflictException) { status = 409; code = "conflict"; }
        else if (failure instanceof ExternalServiceException) { status = 503; code = "upstream_unavailable"; }
        else {
            byte[] ref = new byte[4];
            RANDOM.nextBytes(ref);
            String id = HexFormat.of().formatHex(ref);
            LOG.log(System.Logger.Level.ERROR, "Unhandled error ref=" + id, failure);
            status = 500; code = "internal";
            body.put("ref", id);
        }
        body.put("error", code);
        send(response, status, body);
    }

    public static void write(HttpServletResponse response, int status, String code) throws IOException {
        send(response, status, Map.of("error", code));
    }

    private static void send(HttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.toJson(body));
    }
}
