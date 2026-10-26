package com.kaarigarsetu.security;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DataCipherTest {
    private static String key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private final DataKeys ring = DataKeys.parse("k2:" + key(2) + ",k1:" + key(1));
    private final DataCipher data = DataCipher.of(ring, DataCipher.DATA, false);

    @Test
    void textRoundTripsAndTheStoredFormNamesTheKeyButRevealsNothingElse() {
        String sealed = data.seal("assessments.transcript", 7, "I would first switch off the main supply");
        assertTrue(sealed.startsWith("enc:v1:k2:"), sealed);
        assertFalse(sealed.contains("switch off"));
        assertEquals("I would first switch off the main supply", data.open("assessments.transcript", 7, sealed));
        assertEquals("k2", DataCipher.keyIdOf(sealed));
        assertTrue(data.usesCurrentKey(sealed));
    }

    @Test
    void everySealingOfTheSameValueLooksDifferent() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) seen.add(data.seal("workers.trust_score", 1, "81.30"));
        assertEquals(50, seen.size(), "a fresh random nonce each time: equal scores cannot be recognised by equal ciphertext");
    }

    @Test
    void emptyTextAndUnicodeSurvive() {
        assertEquals("", data.open("p", 1, data.seal("p", 1, "")));
        String hindi = "मैं पहले मुख्य सप्लाई बंद करूँगा \u0000 🔧";
        assertEquals(hindi, data.open("p", 1, data.seal("p", 1, hindi)));
    }

    @Test
    void nullStaysNullSoSqlNullKeepsItsMeaning() {
        assertNull(data.seal("p", 1, null));
        assertNull(data.open("p", 1, null));
        assertNull(data.sealDecimal("p", 1, null));
        assertNull(data.openInt("p", 1, null));
        assertNull(data.sealBool("p", 1, null));
        assertNull(data.openDate("p", 1, null));
    }

    @Test
    void aValueOpensOnlyWhereItWasSealed() {
        String sealed = data.seal("workers.trust_score", 42, "90.00");
        assertThrows(SealedDataException.class, () -> data.open("workers.trust_score", 43, sealed), "copied onto another row");
        assertThrows(SealedDataException.class, () -> data.open("workers.skill_score", 42, sealed), "copied into another column");
        assertDoesNotThrow(() -> data.open("workers.trust_score", 42, sealed));
    }

    @Test
    void anyAlterationIsDetected() {
        String sealed = data.seal("p", 1, "secret value");
        char[] chars = sealed.toCharArray();
        int last = chars.length - 3;
        chars[last] = chars[last] == 'A' ? 'B' : 'A';
        assertThrows(SealedDataException.class, () -> data.open("p", 1, new String(chars)));
        assertThrows(SealedDataException.class, () -> data.open("p", 1, sealed.substring(0, sealed.length() - 6)), "truncated");
        assertThrows(SealedDataException.class, () -> data.open("p", 1, "enc:v1:k2:"), "empty blob");
        assertThrows(SealedDataException.class, () -> data.open("p", 1, "enc:v1:k2:!!!not-base64!!!"));
        assertThrows(SealedDataException.class, () -> data.open("p", 1, "enc:v1:nokey"));
    }

    @Test
    void aKeyThatIsNotInTheRingCannotOpenAndAnotherDomainCannotEither() {
        String sealed = data.seal("p", 1, "secret");
        DataCipher otherServer = DataCipher.of(DataKeys.parse("k2:" + key(9)), DataCipher.DATA, false);
        assertThrows(SealedDataException.class, () -> otherServer.open("p", 1, sealed), "same key id, different key");
        DataCipher droppedKey = DataCipher.of(DataKeys.parse("k3:" + key(3)), DataCipher.DATA, false);
        SealedDataException missing = assertThrows(SealedDataException.class, () -> droppedKey.open("p", 1, sealed));
        assertTrue(missing.getMessage().contains("key this server does not hold"));
        assertThrows(SealedDataException.class, () -> data.forDomain(DataCipher.AUDIT).open("p", 1, sealed), "the audit domain has its own key");
    }

    @Test
    void anOldKeyStillOpensAfterRotationAndNewDataUsesTheNewKey() {
        DataCipher before = DataCipher.of(DataKeys.parse("k1:" + key(1)), DataCipher.DATA, false);
        String old = before.seal("p", 1, "sealed last year");
        DataCipher after = DataCipher.of(DataKeys.parse("k2:" + key(2) + ",k1:" + key(1)), DataCipher.DATA, false);
        assertEquals("sealed last year", after.open("p", 1, old));
        assertFalse(after.usesCurrentKey(old), "it is due for re-sealing");
        assertTrue(after.usesCurrentKey(after.seal("p", 1, "new")));
    }

    @Test
    void plaintextIsAcceptedAndCountedUnlessSealingIsRequired() {
        assertEquals("81.30", data.open("workers.skill_score", 1, "81.30"));
        assertEquals(1, data.unsealedReads());
        DataCipher strict = DataCipher.of(ring, DataCipher.DATA, true);
        SealedDataException refused = assertThrows(SealedDataException.class, () -> strict.open("workers.skill_score", 1, "81.30"));
        assertTrue(refused.getMessage().contains("workers.skill_score"));
        assertFalse(refused.getMessage().contains("81.30"), "the value itself is never put in a message");
    }

    @Test
    void typedValuesRoundTripIncludingThePlainFormsPostgresWouldHaveWritten() {
        assertEquals(new BigDecimal("81.30"), data.openDecimal("p", 1, data.sealDecimal("p", 1, new BigDecimal("81.30"))));
        assertEquals(new BigDecimal("0.00"), data.openDecimal("p", 1, "0.00"), "a legacy numeric::text value");
        assertEquals(5, data.openInt("p", 1, data.sealInt("p", 1, 5)));
        assertEquals(true, data.openBool("p", 1, data.sealBool("p", 1, true)));
        assertEquals(false, data.openBool("p", 1, data.sealBool("p", 1, false)));
        assertEquals(true, data.openBool("p", 1, "true"), "a legacy boolean::text value");
        assertEquals(false, data.openBool("p", 1, "false"));
        assertEquals(LocalDate.of(2026, 10, 2), data.openDate("p", 1, data.sealDate("p", 1, LocalDate.of(2026, 10, 2))));
        assertEquals(LocalDate.of(2026, 10, 2), data.openDate("p", 1, "2026-10-02"), "a legacy date::text value");
    }

    @Test
    void theRingIsParsedStrictlyAndNeverEchoesKeyMaterial() {
        for (String bad : new String[]{null, "", " ", "k1", "K1:" + key(1), "k1:" + key(1) + ",k1:" + key(2), "k1:short", "k1:!!!", "this-is-not-a-key-ring",
                "toolongidentifier1:" + key(1)}) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DataKeys.parse(bad), String.valueOf(bad));
            assertFalse(failure.getMessage().contains(key(1)), "no key material in the message");
        }
        assertEquals("k2", ring.currentId());
        assertEquals(Set.of("k1", "k2"), ring.ids());
        assertEquals("k1", DataKeys.single(new byte[32]).currentId());
    }
}
