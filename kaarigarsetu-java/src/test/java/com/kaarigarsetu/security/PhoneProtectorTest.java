package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PhoneProtectorTest {
    private static byte[] filled(int n, byte v) {
        byte[] b = new byte[n];
        Arrays.fill(b, v);
        return b;
    }

    private final PhoneProtector phones = new PhoneProtector(filled(32, (byte) 7), filled(32, (byte) 9), new SecureRandom());

    @Test
    void normalisesCommonIndianFormats() {
        assertEquals("+919876543210", PhoneProtector.normalize("9876543210"));
        assertEquals("+919876543210", PhoneProtector.normalize("+91 98765-43210"));
        assertEquals("+919876543210", PhoneProtector.normalize("09876543210"));
        assertEquals("+919876543210", PhoneProtector.normalize("919876543210"));
    }

    @Test
    void rejectsAnythingThatIsNotAnIndianMobileNumberWithAGenericMessage() {
        for (String bad : new String[]{null, "", "12345", "1234567890", "98765432101", "abcdefghij", "9876543210' OR '1'='1"}) {
            ValidationException e = assertThrows(ValidationException.class, () -> PhoneProtector.normalize(bad));
            assertEquals("Invalid phone number", e.getMessage());
        }
    }

    @Test
    void encryptionRoundTripsIsRandomisedAndHidesThePlaintext() {
        byte[] a = phones.encrypt("+919876543210");
        byte[] b = phones.encrypt("+919876543210");
        assertEquals("+919876543210", phones.decrypt(a));
        assertFalse(Arrays.equals(a, b), "fresh IV per encryption");
        assertFalse(new String(a, StandardCharsets.ISO_8859_1).contains("9876543210"));
        assertEquals(1 + 12 + 13 + 16, a.length, "version + IV + ciphertext + GCM tag");
    }

    @Test
    void tamperedOrForeignCiphertextIsRejected() {
        byte[] blob = phones.encrypt("+919876543210");
        blob[blob.length - 1] ^= 1;
        assertThrows(IllegalStateException.class, () -> phones.decrypt(blob));
        PhoneProtector other = new PhoneProtector(filled(32, (byte) 1), filled(32, (byte) 9), new SecureRandom());
        assertThrows(IllegalStateException.class, () -> other.decrypt(phones.encrypt("+919876543210")));
        assertThrows(IllegalArgumentException.class, () -> phones.decrypt(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> phones.decrypt(null));
    }

    @Test
    void lookupKeyIsDeterministicKeyedOpaqueAndFitsTheColumn() {
        String key = phones.lookupKey("+919876543210");
        assertEquals(key, phones.lookupKey("+919876543210"));
        assertTrue(key.startsWith("h1:") && key.length() <= 100 && !key.contains("9876543210"));
        PhoneProtector otherKey = new PhoneProtector(filled(32, (byte) 7), filled(32, (byte) 3), new SecureRandom());
        assertNotEquals(key, otherKey.lookupKey("+919876543210"));
    }

    @Test
    void ipAndDeviceHashesAreDomainSeparatedAndNeverRaw() {
        assertNotEquals(phones.ipHash("1.2.3.4"), phones.deviceHash("1.2.3.4", ""));
        assertFalse(phones.ipHash("1.2.3.4").contains("1.2.3.4"));
        assertEquals(64, phones.ipHash("1.2.3.4").length());
        assertEquals(phones.ipHash(null), phones.ipHash(null));
    }

    @Test
    void masksAllButTheLastFourDigits() {
        assertEquals("******3210", PhoneProtector.mask("+919876543210"));
    }
}
