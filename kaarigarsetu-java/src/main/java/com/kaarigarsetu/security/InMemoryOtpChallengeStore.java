package com.kaarigarsetu.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryOtpChallengeStore implements OtpChallengeStore {
    private static final class Row {
        String hash;
        Instant expiresAt;
        int attempts;
        boolean consumed;
    }

    private final Map<Long, Row> rows = new HashMap<>();
    private final Map<String, Long> latestByKey = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized long save(String key, String hash, Instant expiresAt, String ipHash) {   // the in-memory store keeps no per-IP data: ipHash is ignored on purpose
        Long previous = latestByKey.get(key);
        if (previous != null) rows.get(previous).consumed = true;
        Row row = new Row();
        row.hash = hash;
        row.expiresAt = expiresAt;
        long id = ids.incrementAndGet();
        rows.put(id, row);
        latestByKey.put(key, id);
        return id;
    }

    @Override
    public synchronized Optional<Challenge> findOpen(String key) {
        Long id = latestByKey.get(key);
        if (id == null) return Optional.empty();
        Row row = rows.get(id);
        return row.consumed ? Optional.empty() : Optional.of(new Challenge(id, row.hash, row.expiresAt, row.attempts));
    }

    @Override
    public synchronized void recordFailedAttempt(long id) {
        rows.get(id).attempts++;
    }

    @Override
    public synchronized void consume(long id) {
        rows.get(id).consumed = true;
    }
}
