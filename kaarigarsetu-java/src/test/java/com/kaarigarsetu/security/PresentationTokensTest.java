package com.kaarigarsetu.security;

import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentationTokensTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T09:00:00Z"));
    private final PresentationTokens tokens = new PresentationTokens(KEY, clock);
    private final UUID ref = UUID.randomUUID();

    @Test
    void aFreshCodeIsLiveAndStopsBeingLiveAfterFiveMinutes() {
        String token = tokens.issue(ref);
        assertEquals(PresentationTokens.State.LIVE, tokens.check(ref, token).state());
        clock.advance(Duration.ofSeconds(90));
        assertEquals(90, tokens.check(ref, token).age().toSeconds());
        clock.advance(Duration.ofMinutes(3).plusSeconds(30));
        assertEquals(PresentationTokens.State.LIVE, tokens.check(ref, token).state(), "exactly 5 minutes is still live");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(PresentationTokens.State.EXPIRED, tokens.check(ref, token).state(), "a saved screenshot goes stale on its own");
    }

    @Test
    void aCodeIsBoundToOneCredential() {
        String token = tokens.issue(ref);
        assertEquals(PresentationTokens.State.INVALID, tokens.check(UUID.randomUUID(), token).state());
    }

    @Test
    void editingTheTimeToKeepACodeAliveBreaksIt() {
        String token = tokens.issue(ref);
        clock.advance(Duration.ofMinutes(10));
        long issued = Long.parseLong(token.substring(0, token.indexOf('.')));
        String reDated = (issued + 600) + token.substring(token.indexOf('.'));               // pretend it was minted 10 minutes later
        assertEquals(PresentationTokens.State.INVALID, tokens.check(ref, reDated).state());
        assertEquals(PresentationTokens.State.EXPIRED, tokens.check(ref, token).state());
    }

    @Test
    void aCodeFromAnotherKeyOrAForgedOneIsInvalid() {
        PresentationTokens other = new PresentationTokens("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8), clock);
        assertEquals(PresentationTokens.State.INVALID, tokens.check(ref, other.issue(ref)).state());
        assertEquals(PresentationTokens.State.INVALID, tokens.check(ref, clock.instant().getEpochSecond() + ".AAAA").state());
    }

    @Test
    void aCodeFromTheFutureIsNotTrusted() {
        PresentationTokens ahead = new PresentationTokens(KEY, new MutableClock(Instant.parse("2026-10-16T09:30:00Z")));
        assertEquals(PresentationTokens.State.INVALID, tokens.check(ref, ahead.issue(ref)).state());
    }

    @Test
    void emptyAndMalformedInputNeverThrows() {
        assertEquals(PresentationTokens.State.NONE, tokens.check(ref, null).state());
        assertEquals(PresentationTokens.State.NONE, tokens.check(ref, "  ").state());
        for (String bad : new String[]{".", "x.y", "12345", "-1.-1", "9".repeat(30) + ".abc", "a".repeat(500), "1.2.3", "\u0000.\u0000"}) {
            assertEquals(PresentationTokens.State.INVALID, tokens.check(ref, bad).state(), bad);
        }
    }

    @Test
    void aWeakKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PresentationTokens(new byte[4], clock));
        assertTrue(tokens.expiresAt(tokens.issue(ref)).isAfter(clock.instant()));
    }
}
