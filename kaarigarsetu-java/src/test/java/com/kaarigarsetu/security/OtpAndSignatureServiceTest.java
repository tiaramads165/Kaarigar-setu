package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.InvalidOTPException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtpAndSignatureServiceTest {
    @Test
    void otpCanBeUsedOnceAndLocksAfterFiveInvalidAttempts() {
        OtpService service = new OtpService();
        String otp = service.generate(10L);
        assertDoesNotThrow(() -> service.verify(10L, otp));
        assertThrows(InvalidOTPException.class, () -> service.verify(10L, otp));

        String lockedOtp = service.generate(11L);
        String incorrectOtp = lockedOtp.equals("000000") ? "999999" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(InvalidOTPException.class, () -> service.verify(11L, incorrectOtp));
        }
    }

    @Test
    void signatureRejectsTamperedPayload() {
        SignatureService service = new SignatureService();
        String payload = "{\"worker\":\"Asha\",\"score\":90}";
        String signature = service.sign(payload);

        assertTrue(service.verify(payload, signature));
        assertFalse(service.verify("{\"worker\":\"Asha\",\"score\":10}", signature));
    }
}
