package com.kaarigarsetu.assessment;

import com.kaarigarsetu.exception.ChallengeException;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReplayChallengeTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"));
    private final ReplayChallenge challenge = new ReplayChallenge("0123456789abcdef0123456789abcdef".getBytes(), clock, Duration.ofMinutes(15));
    private final UUID attempt = UUID.randomUUID();

    @Test
    void theTokenYieldsTheSameThreeDigitCodeAndOnlyDigitsOneToNine() {
        ReplayChallenge.Issued issued = challenge.issue(7, attempt, 3);
        assertTrue(issued.code().matches("[1-9]{3}"), issued.code());
        assertEquals(issued.code(), challenge.codeFor(7, attempt, 3, issued.token()));
        assertEquals(15, issued.minutes());
    }

    @Test
    void theCodeIsDifferentForEveryQuestionAttemptWorkerAndMoment() {
        Set<String> codes = new HashSet<>();
        for (int q = 1; q <= 10; q++) codes.add(challenge.issue(7, attempt, q).code());
        assertTrue(codes.size() >= 7, "ten questions of one attempt: " + codes);
        Set<String> attempts = new HashSet<>();
        for (int i = 0; i < 40; i++) attempts.add(challenge.issue(7, UUID.randomUUID(), 1).code());
        assertTrue(attempts.size() > 25, "40 attempts: " + attempts.size() + " different codes");
        String first = challenge.issue(7, attempt, 1).code();
        clock.advance(Duration.ofSeconds(1));
        Set<String> later = new HashSet<>();
        for (int s = 0; s < 30; s++) {
            clock.advance(Duration.ofSeconds(1));
            later.add(challenge.issue(7, attempt, 1).code());
        }
        assertTrue(later.size() > 15, "the same question asked at 30 different moments: " + later.size());
        assertNotNull(first);
    }

    @Test
    void aTokenIsBoundToItsWorkerAttemptAndQuestion() {
        String token = challenge.issue(7, attempt, 3).token();
        assertRefused(8, attempt, 3, token);
        assertRefused(7, UUID.randomUUID(), 3, token);
        assertRefused(7, attempt, 4, token);
        assertDoesNotThrow(() -> challenge.codeFor(7, attempt, 3, token));
    }

    @Test
    void aStaleTokenIsRefusedAndTheLifetimeIsHonouredToTheSecond() {
        String token = challenge.issue(7, attempt, 1).token();
        clock.advance(Duration.ofMinutes(15));
        assertDoesNotThrow(() -> challenge.codeFor(7, attempt, 1, token), "exactly at the limit");
        clock.advance(Duration.ofSeconds(1));
        assertRefused(7, attempt, 1, token);
    }

    @Test
    void aTokenFromTheFutureOrAlteredOrMalformedIsRefused() {
        ReplayChallenge later = new ReplayChallenge("0123456789abcdef0123456789abcdef".getBytes(), new MutableClock(clock.instant().plus(Duration.ofMinutes(10))), Duration.ofMinutes(15));
        assertRefused(7, attempt, 1, later.issue(7, attempt, 1).token());
        String token = challenge.issue(7, attempt, 1).token();
        String[] parts = token.split("\\.");
        assertRefused(7, attempt, 1, (Long.parseLong(parts[0]) + 1) + "." + parts[1]);
        assertRefused(7, attempt, 1, parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA");
        for (String junk : new String[]{null, "", "x", "1.", ".abc", "abc.def", "1".repeat(300), "-5.AAAA", "9999999999999999999999.AAAA"}) assertRefused(7, attempt, 1, junk);
    }

    @Test
    void anotherServersKeyMakesADifferentCodeAndATokenItCannotCheck() {
        ReplayChallenge other = new ReplayChallenge("ffffffffffffffffffffffffffffffff".getBytes(), clock, Duration.ofMinutes(15));
        assertThrows(ChallengeException.class, () -> other.codeFor(7, attempt, 1, challenge.issue(7, attempt, 1).token()));
    }

    private void assertRefused(long worker, UUID attemptId, int question, String token) {
        ChallengeException refused = assertThrows(ChallengeException.class, () -> challenge.codeFor(worker, attemptId, question, token), String.valueOf(token));
        assertEquals(ChallengeException.Kind.REQUIRED, refused.kind());
    }
}
