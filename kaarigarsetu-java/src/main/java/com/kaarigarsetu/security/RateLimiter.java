package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.RateLimitExceededException;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter (a Deque of hit times per key). In-memory, so limits are per node. */
public final class RateLimiter {
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int max, Duration window) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        int[] retryAfter = {0};
        hits.compute(key, (k, existing) -> {
            Deque<Long> queue = existing == null ? new ArrayDeque<>() : existing;
            while (!queue.isEmpty() && now - queue.peekFirst() >= windowMs) queue.pollFirst();
            if (queue.size() >= max) {
                retryAfter[0] = (int) Math.max(1, (windowMs - (now - queue.peekFirst()) + 999) / 1000);
                return queue;
            }
            queue.addLast(now);
            return queue;
        });
        if (retryAfter[0] > 0) throw new RateLimitExceededException("Too many requests", retryAfter[0]);
    }

    /** How many hits the key has within the window, without recording one. */
    public int count(String key, Duration window) {
        long now = clock.millis();
        long windowMs = window.toMillis();
        int[] n = {0};
        hits.computeIfPresent(key, (k, queue) -> {                          // under the same per-key lock as check(), so the deque is never read mid-update
            n[0] = (int) queue.stream().filter(t -> now - t < windowMs).count();
            return queue;
        });
        return n[0];
    }

    public void evictExpired(Duration maxWindow) {
        long now = clock.millis();
        hits.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() >= maxWindow.toMillis());
    }
}
