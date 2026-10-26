package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.RateLimitExceededException;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void allowsUpToTheLimitThenRefusesWithARetryHint() {
        for (int i = 0; i < 3; i++) limiter.check("ip", 3, Duration.ofMinutes(1));
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class, () -> limiter.check("ip", 3, Duration.ofMinutes(1)));
        assertTrue(e.retryAfterSeconds() >= 1 && e.retryAfterSeconds() <= 60);
    }

    @Test
    void theWindowSlidesAndKeysAreIndependent() {
        for (int i = 0; i < 3; i++) limiter.check("a", 3, Duration.ofMinutes(1));
        assertDoesNotThrow(() -> limiter.check("b", 3, Duration.ofMinutes(1)));
        clock.advance(Duration.ofSeconds(61));
        assertDoesNotThrow(() -> limiter.check("a", 3, Duration.ofMinutes(1)));
    }

    @Test
    void refusedAttemptsDoNotExtendTheLockout() {
        for (int i = 0; i < 2; i++) limiter.check("k", 2, Duration.ofSeconds(10));
        for (int i = 0; i < 20; i++) assertThrows(RateLimitExceededException.class, () -> limiter.check("k", 2, Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(10));
        assertDoesNotThrow(() -> limiter.check("k", 2, Duration.ofSeconds(10)));
    }

    @Test
    void evictionDropsIdleKeys() {
        limiter.check("idle", 1, Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(6));
        limiter.evictExpired(Duration.ofSeconds(5));
        assertDoesNotThrow(() -> limiter.check("idle", 1, Duration.ofSeconds(5)));
    }
}
