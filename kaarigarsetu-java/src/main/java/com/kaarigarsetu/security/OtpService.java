package com.kaarigarsetu.security;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.exception.InvalidOTPException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * One-time passwords: SecureRandom 6 digits, only a salted PBKDF2 hash stored (plus an optional server pepper),
 * 5-minute expiry, 5-attempt lockout, single use, constant-time comparison. Challenges are addressed by a string
 * key, so the same mechanism serves worker/employer login, attestation and moderator step-up.
 */
public class OtpService {
    private enum Outcome { OK, INVALID, LOCKED, UNAVAILABLE }

    private static final int PBKDF2_ITERATIONS = 100_000;

    private final OtpChallengeStore store;
    private final Transactions transactions;
    private final Clock clock;
    private final SecureRandom random;
    private final byte[] pepper;
    private final int maxAttempts;
    private final Duration expiry;

    /** Self-contained in-memory service, five attempts and five minutes (the Phase 2/4 defaults). */
    public OtpService() {
        this(new InMemoryOtpChallengeStore(), Transactions.none(), Clock.systemUTC(), new SecureRandom(), new byte[0], 5, Duration.ofMinutes(5));
    }

    OtpService(SecureRandom random, Clock clock) {
        this(new InMemoryOtpChallengeStore(), Transactions.none(), clock, random, new byte[0], 5, Duration.ofMinutes(5));
    }

    public OtpService(OtpChallengeStore store, Transactions transactions, Clock clock, SecureRandom random,
                      byte[] pepper, int maxAttempts, Duration expiry) {
        this.store = store;
        this.transactions = transactions;
        this.clock = clock;
        this.random = random;
        this.pepper = pepper.clone();
        this.maxAttempts = maxAttempts;
        this.expiry = expiry;
    }

    /** A freshly issued code (plaintext, shown once) and the salted hash that was stored for it. */
    public record Issued(String code, String storedHash) {}

    public String issue(String key, String ipHash) {
        return issueDetailed(key, ipHash).code();
    }

    /** Issues a fresh code for the challenge key, retiring any earlier open one. */
    public Issued issueDetailed(String key, String ipHash) {
        String otp = String.format("%06d", random.nextInt(1_000_000));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String stored = Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash(otp, salt));
        transactions.inTransaction(() -> store.save(key, stored, Instant.now(clock).plus(expiry), ipHash));
        return new Issued(otp, stored);
    }

    /**
     * Verifies and consumes the code. Wrong guesses are counted even though an exception follows: the outcome is
     * decided inside the transaction and thrown only after it commits, so the counter is never rolled back.
     */
    public void verify(String key, String submittedOtp) {
        Outcome outcome = transactions.inTransaction(() -> {
            Optional<OtpChallengeStore.Challenge> found = store.findOpen(key);
            if (found.isEmpty()) return Outcome.UNAVAILABLE;
            OtpChallengeStore.Challenge challenge = found.get();
            if (!Instant.now(clock).isBefore(challenge.expiresAt())) {
                store.consume(challenge.id());
                return Outcome.UNAVAILABLE;
            }
            if (challenge.attempts() >= maxAttempts) return Outcome.LOCKED;
            if (submittedOtp != null && matches(submittedOtp, challenge.hash())) {
                store.consume(challenge.id());
                return Outcome.OK;
            }
            store.recordFailedAttempt(challenge.id());
            return challenge.attempts() + 1 >= maxAttempts ? Outcome.LOCKED : Outcome.INVALID;
        });
        switch (outcome) {
            case OK -> { }
            case INVALID -> throw new InvalidOTPException("OTP is invalid");
            case LOCKED -> throw new InvalidOTPException("OTP is locked");
            case UNAVAILABLE -> throw new InvalidOTPException("OTP is unavailable, expired, or locked");
        }
    }

    // ---- attestation-id convenience API used by the Phase 2/4 unit tests ----

    public String generate(long attestationId) {
        return issue("attestation:" + attestationId, null);
    }

    public void verify(long attestationId, String submittedOtp) {
        verify("attestation:" + attestationId, submittedOtp);
    }

    private boolean matches(String submitted, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 2) return false;
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(expected, hash(submitted, Base64.getDecoder().decode(parts[0])));
    }

    private byte[] hash(String otp, byte[] salt) {
        try {
            String material = new String(pepper, StandardCharsets.UTF_8) + otp;
            PBEKeySpec spec = new PBEKeySpec(material.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash OTP", exception);
        }
    }
}
