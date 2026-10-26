package com.kaarigarsetu.security;

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
 * Short-lived "live presentation" codes for credentials.
 *
 * <p>A credential's QR code is a fixed link: anyone with a screenshot can show it, for as long as the credential lives. That proves the credential is
 * genuine, not that the person showing it is the worker it belongs to. A live code adds a weaker but useful claim: "the worker's signed-in account
 * produced this a moment ago". Only the owner's authenticated session can mint one; it is bound to one credential and stops counting as live after
 * {@link #LIFETIME}, so a saved screenshot or a forwarded link goes stale on its own. It does not stop a worker from handing their unlocked phone to
 * someone else: that is a different threat, answered by checking the holder in person.
 *
 * <p>Stateless: {@code <issued-at epoch seconds>.<base64url HMAC-SHA256(key, "presentation-v1:" + ref + ":" + issued-at)>}. Nothing is stored.
 */
public final class PresentationTokens {
    public static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final int MAX_TOKEN_LENGTH = 80;

    public enum State { NONE, LIVE, EXPIRED, INVALID }

    /** {@code age} is how long ago the code was minted (zero when unknown). */
    public record Status(State state, Duration age) {
    }

    private final byte[] key;
    private final Clock clock;

    public PresentationTokens(byte[] key, Clock clock) {
        if (key == null || key.length < 16) throw new IllegalArgumentException("a key of at least 16 bytes is required");
        this.key = key.clone();
        this.clock = clock;
    }

    public String issue(UUID credentialRef) {
        long issued = clock.instant().getEpochSecond();
        return issued + "." + mac(credentialRef, issued);
    }

    public Status check(UUID credentialRef, String token) {
        if (token == null || token.isBlank()) return new Status(State.NONE, Duration.ZERO);
        if (token.length() > MAX_TOKEN_LENGTH) return invalid();
        int dot = token.indexOf('.');
        if (dot < 1 || dot == token.length() - 1) return invalid();
        long issued;
        try {
            issued = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException malformed) {
            return invalid();
        }
        String expected = mac(credentialRef, issued);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) return invalid();
        Duration age = Duration.between(Instant.ofEpochSecond(issued), clock.instant());
        if (age.compareTo(CLOCK_SKEW.negated()) < 0) return invalid();                       // minted "in the future": a clock problem, never trust it
        if (age.compareTo(LIFETIME) > 0) return new Status(State.EXPIRED, age);
        return new Status(State.LIVE, age.isNegative() ? Duration.ZERO : age);
    }

    public Instant expiresAt(String token) {
        return Instant.ofEpochSecond(Long.parseLong(token.substring(0, token.indexOf('.')))).plus(LIFETIME);
    }

    private static Status invalid() {
        return new Status(State.INVALID, Duration.ZERO);
    }

    private String mac(UUID credentialRef, long issued) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(("presentation-v1:" + credentialRef + ":" + issued).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
