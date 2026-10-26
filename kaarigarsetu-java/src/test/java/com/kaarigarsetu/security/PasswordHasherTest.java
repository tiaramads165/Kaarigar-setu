package com.kaarigarsetu.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void correctPasswordMatchesAndWrongOneDoesNot() {
        String stored = PasswordHasher.hash("s3cret-pass".toCharArray());
        assertTrue(PasswordHasher.matches("s3cret-pass".toCharArray(), stored));
        assertFalse(PasswordHasher.matches("s3cret-pasS".toCharArray(), stored));
        assertFalse(PasswordHasher.matches(new char[0], stored));
    }

    @Test
    void hashesAreSaltedSoEqualPasswordsDiffer_andCarryTheAlgorithmAndCost() {
        String a = PasswordHasher.hash("same".toCharArray());
        String b = PasswordHasher.hash("same".toCharArray());
        assertNotEquals(a, b);
        assertTrue(a.startsWith("pbkdf2-sha256$210000$"));
        assertFalse(a.contains("same"));
    }

    @Test
    void malformedOrDowngradedStoredValuesNeverMatch() {
        for (String bad : new String[]{"", "plain-text", "pbkdf2-sha256$1$AAAA$AAAA", "md5$100000$AAAA$AAAA", "pbkdf2-sha256$x$AAAA$AAAA",
                "pbkdf2-sha256$210000$!!$!!", "pbkdf2-sha256$99999999999$AAAA$AAAA"}) {
            assertFalse(PasswordHasher.matches("x".toCharArray(), bad), bad);
        }
    }
}
