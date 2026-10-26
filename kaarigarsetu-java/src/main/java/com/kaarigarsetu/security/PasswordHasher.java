package com.kaarigarsetu.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hashing (JDK only, no extra dependency). Stored form:
 * {@code pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>}.
 * Generate a value for MODERATOR_PASSWORD_HASH with:  java -cp kaarigarsetu-java.jar com.kaarigarsetu.security.PasswordHasher
 */
public final class PasswordHasher {
    private static final int ITERATIONS = 210_000;   // OWASP guidance for PBKDF2-HMAC-SHA256
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    /** Constant-time. A malformed stored value never matches. */
    public static boolean matches(char[] password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 5_000_000) return false;
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, Base64.getDecoder().decode(parts[2]), iterations));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password, salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash password", exception);
        }
    }

    public static void main(String[] args) {
        java.io.Console console = System.console();
        char[] password = console != null ? console.readPassword("Moderator password: ")
                : new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8).nextLine().toCharArray();
        System.out.println(hash(password));
    }
}
