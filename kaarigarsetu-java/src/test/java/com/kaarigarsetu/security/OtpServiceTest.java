package com.kaarigarsetu.security;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OtpServiceTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final InMemoryOtpChallengeStore store = new InMemoryOtpChallengeStore();
    private final OtpService otp = new OtpService(store, Transactions.none(), clock, new SecureRandom(), "pepper".getBytes(), 5, Duration.ofMinutes(5));

    private static String wrongFor(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    @Test
    void codeIsSixDigitsAndVerifiesOnce() {
        String code = otp.issue("k", null);
        assertTrue(code.matches("[0-9]{6}"));
        assertDoesNotThrow(() -> otp.verify("k", code));
        assertThrows(InvalidOTPException.class, () -> otp.verify("k", code), "single use");
    }

    @Test
    void fiveWrongAttemptsLockTheChallengeEvenForTheCorrectCode() {
        String code = otp.issue("k", null);
        for (int i = 0; i < 4; i++) assertEquals("OTP is invalid", assertThrows(InvalidOTPException.class, () -> otp.verify("k", wrongFor(code))).getMessage());
        assertEquals("OTP is locked", assertThrows(InvalidOTPException.class, () -> otp.verify("k", wrongFor(code))).getMessage());
        assertThrows(InvalidOTPException.class, () -> otp.verify("k", code));
    }

    @Test
    void expiresAfterFiveMinutes() {
        String code = otp.issue("k", null);
        clock.advance(Duration.ofSeconds(299));
        assertDoesNotThrow(() -> otp.verify("k", code));
        String second = otp.issue("k2", null);
        clock.advance(Duration.ofSeconds(301));
        assertThrows(InvalidOTPException.class, () -> otp.verify("k2", second));
    }

    @Test
    void aNewCodeRetiresTheOldOne() {
        String first = otp.issue("k", null);
        String second = otp.issue("k", null);
        if (!first.equals(second)) assertThrows(InvalidOTPException.class, () -> otp.verify("k", first));
        assertDoesNotThrow(() -> otp.verify("k", second));
    }

    @Test
    void codesAreBoundToTheirKey() {
        String code = otp.issue("login:WORKER:a", null);
        assertThrows(InvalidOTPException.class, () -> otp.verify("login:WORKER:b", code));
        assertThrows(InvalidOTPException.class, () -> otp.verify("login:EMPLOYER:a", code));
    }

    @Test
    void nullOrMissingChallengesAreInvalidNotErrors() {
        assertThrows(InvalidOTPException.class, () -> otp.verify("never-issued", "123456"));
        otp.issue("k", null);
        assertThrows(InvalidOTPException.class, () -> otp.verify("k", null));
    }

    @Test
    void onlyASaltedHashIsEverStored() {
        String code = otp.issue("k", null);
        OtpService.Issued issued = otp.issueDetailed("k2", null);
        assertFalse(issued.storedHash().contains(issued.code()));
        String[] parts = issued.storedHash().split("\\$");
        assertEquals(2, parts.length, "base64(salt)$base64(hash)");
        assertEquals(16, java.util.Base64.getDecoder().decode(parts[0]).length);
        assertEquals(32, java.util.Base64.getDecoder().decode(parts[1]).length);
        assertNotNull(code);
    }

    @Test
    void aDifferentPepperCannotVerifyACodeIssuedUnderAnother() {
        InMemoryOtpChallengeStore shared = new InMemoryOtpChallengeStore();
        OtpService a = new OtpService(shared, Transactions.none(), clock, new SecureRandom(), "pepper-A".getBytes(), 5, Duration.ofMinutes(5));
        OtpService b = new OtpService(shared, Transactions.none(), clock, new SecureRandom(), "pepper-B".getBytes(), 5, Duration.ofMinutes(5));
        String code = a.issue("k", null);
        assertThrows(InvalidOTPException.class, () -> b.verify("k", code));
    }
}
