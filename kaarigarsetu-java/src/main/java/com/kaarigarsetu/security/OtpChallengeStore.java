package com.kaarigarsetu.security;

import java.time.Instant;
import java.util.Optional;

/** Where one-time-password challenges live: in memory for unit tests, in PostgreSQL in the application. */
public interface OtpChallengeStore {
    /** A stored, still-open challenge. {@code hash} is salted PBKDF2; the code itself is never stored. */
    record Challenge(long id, String hash, Instant expiresAt, int attempts) {}

    /** Stores a new challenge and retires any earlier open challenge for the same key. */
    long save(String key, String hash, Instant expiresAt, String ipHash);

    /** The newest unconsumed challenge for the key (row-locked when the store supports it). */
    Optional<Challenge> findOpen(String key);

    void recordFailedAttempt(long id);

    void consume(long id);
}
