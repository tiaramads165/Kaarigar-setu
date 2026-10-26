package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.ProofOfWorkRequiredException;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProofOfWorkTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T09:00:00Z"));
    private final ProofOfWork pow = new ProofOfWork("test-key-test-key-test-key-1234".getBytes(), clock);

    private ProofOfWork.Solution solved(ProofOfWork.Challenge challenge) {
        return new ProofOfWork.Solution(challenge.challenge(), ProofOfWork.solve(challenge));
    }

    @Test
    void aCorrectFreshSolutionIsAcceptedOnce() {
        ProofOfWork.Challenge challenge = pow.issue(10, "addr-1");
        ProofOfWork.Solution solution = solved(challenge);
        assertDoesNotThrow(() -> pow.require(solution, 10, "addr-1"));
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solution, 10, "addr-1"), "a spent puzzle cannot be used again");
    }

    @Test
    void aMissingOrGarbageAnswerGetsANewPuzzleOfTheRequestedDifficulty() {
        for (ProofOfWork.Solution bad : new ProofOfWork.Solution[]{null, new ProofOfWork.Solution(null, null), new ProofOfWork.Solution("x", "1"),
                new ProofOfWork.Solution("v1.1.1.1.sig", "abc"), new ProofOfWork.Solution(pow.issue(8, "a").challenge(), "-1"),
                new ProofOfWork.Solution(pow.issue(8, "a").challenge(), "9".repeat(30)), new ProofOfWork.Solution("", "")}) {
            ProofOfWorkRequiredException refused = assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(bad, 12, "a"));
            assertEquals(12, refused.bits());
            ProofOfWork.Solution next = solved(new ProofOfWork.Challenge(refused.challenge(), refused.bits()));
            assertDoesNotThrow(() -> pow.require(next, 12, "a"), "the puzzle in the refusal is a real, solvable one");
        }
    }

    @Test
    void aPuzzleIsBoundToTheAddressItWasIssuedTo() {
        ProofOfWork.Solution solution = solved(pow.issue(8, "addr-1"));
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solution, 8, "addr-2"));
    }

    @Test
    void aPuzzleExpires() {
        ProofOfWork.Solution solution = solved(pow.issue(8, "addr-1"));
        clock.advance(ProofOfWork.LIFETIME.plusSeconds(1));
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solution, 8, "addr-1"));
    }

    @Test
    void aPuzzleCannotBeMadeEasierOrForged() {
        ProofOfWork.Challenge easy = pow.issue(4, "addr-1");
        ProofOfWork.Solution solutionToEasy = solved(easy);
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solutionToEasy, 14, "addr-1"), "a puzzle easier than the current requirement is refused");
        String text = pow.issue(14, "addr-1").challenge();
        String lowered = text.replace(".14.", ".2.");
        assertNotEquals(text, lowered);
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(new ProofOfWork.Solution(lowered, "0"), 2, "addr-1"), "editing the difficulty breaks the signature");
        ProofOfWork other = new ProofOfWork("another-key-another-key-another-1".getBytes(), clock);
        ProofOfWork.Challenge foreign = other.issue(8, "addr-1");
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solved(foreign), 8, "addr-1"), "a puzzle signed with another key");
    }

    @Test
    void aWrongNumberIsNotASolution() {
        ProofOfWork.Challenge challenge = pow.issue(16, "addr-1");
        long found = 0;
        while (ProofOfWork.leadingZeroBits(sha(challenge.challenge() + ":" + found)) >= 16) found++;      // the first number that does NOT solve it (almost always 0)
        long wrong = found;
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(new ProofOfWork.Solution(challenge.challenge(), Long.toString(wrong)), 16, "addr-1"));
        assertDoesNotThrow(() -> pow.require(new ProofOfWork.Solution(challenge.challenge(), ProofOfWork.solve(challenge)), 16, "addr-1"), "the real answer still works: a wrong try spends nothing");
    }

    @Test
    void leadingZeroBitsCountsBitsNotBytes() {
        assertEquals(0, ProofOfWork.leadingZeroBits(new byte[]{(byte) 0x80}));
        assertEquals(3, ProofOfWork.leadingZeroBits(new byte[]{0x10}));
        assertEquals(8, ProofOfWork.leadingZeroBits(new byte[]{0, (byte) 0xff}));
        assertEquals(12, ProofOfWork.leadingZeroBits(new byte[]{0, 0x0f}));
        assertEquals(16, ProofOfWork.leadingZeroBits(new byte[]{0, 0}));
    }

    @Test
    void spentPuzzlesAreForgottenOnlyAfterTheyWouldHaveExpiredAnyway() {
        ProofOfWork.Solution solution = solved(pow.issue(6, "a"));
        pow.require(solution, 6, "a");
        pow.evictExpired();
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solution, 6, "a"), "still remembered while it could still be valid");
        clock.advance(Duration.ofMinutes(3));
        pow.evictExpired();
        assertThrows(ProofOfWorkRequiredException.class, () -> pow.require(solution, 6, "a"), "and expired by then, so still refused");
    }

    private static byte[] sha(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
