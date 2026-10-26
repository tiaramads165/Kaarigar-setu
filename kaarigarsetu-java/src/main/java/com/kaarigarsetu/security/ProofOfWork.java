package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.ProofOfWorkRequiredException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A self-contained "prove you did some work" check (hashcash) used instead of a third-party CAPTCHA: before the server sends a login code to an
 * UNKNOWN device, the browser must find a number whose SHA-256 together with the server's puzzle starts with a given number of zero bits. A real
 * person's browser needs well under a second; a script asking for thousands of codes pays real CPU for every one.
 *
 * <p>The puzzle is stateless: {@code v1.<expiry>.<bits>.<nonce>.<signature>}, the signature being an HMAC over the text and the caller's address hash, so a
 * puzzle cannot be forged, altered, extended, or handed to another address. Each puzzle is accepted once (a short in-memory replay set) and only for a short
 * time. It is a cost, not a barrier: a determined attacker can pay it, which is why it is one layer of several (see AuthService).
 */
public final class ProofOfWork {
    /** A puzzle for the client. */
    public record Challenge(String challenge, int bits) {}

    /** The client's answer: the puzzle it was given and the number it found. */
    public record Solution(String challenge, String counter) {}

    static final Duration LIFETIME = Duration.ofMinutes(2);
    private static final int MAX_REMEMBERED = 100_000;

    private final byte[] key;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> used = new ConcurrentHashMap<>();

    public ProofOfWork(byte[] key, Clock clock) {
        this.key = key.clone();
        this.clock = clock;
    }

    /** A new puzzle of the given difficulty, valid for this caller only. */
    public Challenge issue(int bits, String binding) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        String payload = "v1." + (clock.instant().plus(LIFETIME).getEpochSecond()) + "." + bits + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return new Challenge(payload + "." + sign(payload, binding), bits);
    }

    /**
     * Accepts a correct, fresh, unused solution of at least {@code minBits}; otherwise throws {@link ProofOfWorkRequiredException} carrying a NEW puzzle, so the
     * caller can simply solve it and try again. A solution is consumed on success.
     */
    public void require(Solution solution, int minBits, String binding) {
        if (!valid(solution, minBits, binding)) throw new ProofOfWorkRequiredException(issue(minBits, binding).challenge(), minBits);
    }

    private boolean valid(Solution solution, int minBits, String binding) {
        if (solution == null || solution.challenge() == null || solution.counter() == null) return false;
        String text = solution.challenge();
        String counter = solution.counter();
        if (counter.isEmpty() || counter.length() > 20 || !counter.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
        int lastDot = text.lastIndexOf('.');
        if (lastDot < 0) return false;
        String payload = text.substring(0, lastDot);
        if (!MessageDigest.isEqual(sign(payload, binding).getBytes(StandardCharsets.UTF_8), text.substring(lastDot + 1).getBytes(StandardCharsets.UTF_8))) return false;
        String[] parts = payload.split("\\.");
        if (parts.length != 4 || !parts[0].equals("v1")) return false;
        long expires;
        int bits;
        try {
            expires = Long.parseLong(parts[1]);
            bits = Integer.parseInt(parts[2]);
        } catch (NumberFormatException malformed) {
            return false;
        }
        long now = clock.instant().getEpochSecond();
        if (now >= expires || bits < minBits || bits > 40) return false;
        if (leadingZeroBits(sha256(text + ":" + counter)) < bits) return false;
        return markUsed(text, expires, now);
    }

    private boolean markUsed(String text, long expires, long now) {
        if (used.size() >= MAX_REMEMBERED) used.values().removeIf(until -> until <= now);
        if (used.size() >= MAX_REMEMBERED) return false;                      // a flood of valid puzzles: refuse rather than forget replays
        return used.putIfAbsent(text, expires) == null;
    }

    /** Forgets spent puzzles that have expired anyway (called from the maintenance timer). */
    public void evictExpired() {
        long now = clock.instant().getEpochSecond();
        used.values().removeIf(until -> until <= now);
    }

    public static int leadingZeroBits(byte[] digest) {
        int bits = 0;
        for (byte b : digest) {
            if (b == 0) {
                bits += 8;
                continue;
            }
            bits += Integer.numberOfLeadingZeros(b & 0xff) - 24;
            break;
        }
        return bits;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String sign(String payload, String binding) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((payload + "|" + binding).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign proof-of-work puzzle", exception);
        }
    }

    /** Test and tooling helper: finds a counter that solves the puzzle (what the browser does in JavaScript). */
    public static String solve(Challenge challenge) {
        for (long counter = 0; ; counter++) {
            if (leadingZeroBits(sha256(challenge.challenge() + ":" + counter)) >= challenge.bits()) return Long.toString(counter);
        }
    }
}
