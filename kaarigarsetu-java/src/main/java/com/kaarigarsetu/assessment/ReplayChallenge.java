package com.kaarigarsetu.assessment;

import com.kaarigarsetu.exception.ChallengeException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * The spoken challenge of one question of one attempt: three random digits (1 to 9) the worker must say at the end of the answer. The code is made when the
 * question is ASKED, so a recording made before cannot contain it. It is stateless: the code is an HMAC of (worker, attempt, question, issue time) and the
 * token the page sends back with the answer carries the issue time and its own HMAC, so a token from another worker, attempt or question, an altered or a
 * stale one, is refused. It proves the recording was made after the question was shown, not WHO said it (see docs/REPLAY_DETECTION.md).
 */
public final class ReplayChallenge {
    /** @param minutes how long the code stays good, for the page to say so */
    public record Issued(String code, String token, int minutes) {
    }

    private static final int DIGITS = 3;

    private final byte[] key;
    private final Clock clock;
    private final Duration lifetime;

    public ReplayChallenge(byte[] key, Clock clock, Duration lifetime) {
        this.key = key.clone();
        this.clock = clock;
        this.lifetime = lifetime;
    }

    public Issued issue(long workerId, UUID attemptId, int questionNo) {
        long issued = clock.instant().getEpochSecond();
        return new Issued(code(workerId, attemptId, questionNo, issued), issued + "." + mac("token", workerId, attemptId, questionNo, issued), (int) lifetime.toMinutes());
    }

    /** @return the code this token stands for @throws ChallengeException (REQUIRED) when the token is missing, malformed, forged, for another question, from the future or past its lifetime */
    public String codeFor(long workerId, UUID attemptId, int questionNo, String token) {
        try {
            if (token == null || token.length() > 100) throw new ChallengeException(ChallengeException.Kind.REQUIRED, "A fresh code is needed");
            int dot = token.indexOf('.');
            long issued = Long.parseLong(token.substring(0, dot));
            String presented = token.substring(dot + 1);
            boolean authentic = MessageDigest.isEqual(mac("token", workerId, attemptId, questionNo, issued).getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
            Instant at = Instant.ofEpochSecond(issued);
            boolean fresh = !at.isAfter(clock.instant().plusSeconds(60)) && !at.plus(lifetime).isBefore(clock.instant());
            if (!authentic || !fresh) throw new ChallengeException(ChallengeException.Kind.REQUIRED, "A fresh code is needed");
            return code(workerId, attemptId, questionNo, issued);
        } catch (RuntimeException failure) {
            if (failure instanceof ChallengeException challenge) throw challenge;
            throw new ChallengeException(ChallengeException.Kind.REQUIRED, "A fresh code is needed");
        }
    }

    private String code(long workerId, UUID attemptId, int questionNo, long issued) {
        byte[] digest = hmac("code", workerId, attemptId, questionNo, issued);
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < DIGITS; i++) code.append(1 + (digest[i] & 0xFF) % 9);
        return code.toString();
    }

    private String mac(String purpose, long workerId, UUID attemptId, int questionNo, long issued) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(purpose, workerId, attemptId, questionNo, issued));
    }

    private byte[] hmac(String purpose, long workerId, UUID attemptId, int questionNo, long issued) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(("challenge-" + purpose + ":" + workerId + "|" + attemptId + "|" + questionNo + "|" + issued).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
