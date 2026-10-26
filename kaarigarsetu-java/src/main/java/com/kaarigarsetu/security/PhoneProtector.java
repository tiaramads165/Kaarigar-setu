package com.kaarigarsetu.security;

import com.kaarigarsetu.exception.ValidationException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Phone numbers are personal data (India's DPDP Act). The database never holds one in clear:
 * the unique "phone" column stores an opaque keyed-HMAC lookup key, and the number itself is recoverable only
 * from an AES-256-GCM ciphertext (key from the environment) when we genuinely must send an SMS.
 */
public final class PhoneProtector {
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final byte[] AAD = "ks-phone-v1".getBytes(StandardCharsets.UTF_8);
    private static final Pattern INDIAN_MOBILE = Pattern.compile("[6-9][0-9]{9}");

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random;

    public PhoneProtector(byte[] aesKey32, byte[] hmacKey, SecureRandom random) {
        if (aesKey32.length != 32) throw new IllegalArgumentException("AES key must be 32 bytes");
        this.aesKey = new SecretKeySpec(aesKey32, "AES");
        this.hmacKey = new SecretKeySpec(hmacKey, "HmacSHA256");
        this.random = random;
    }

    /** Canonical form +91XXXXXXXXXX. Anything that is not an Indian mobile number is rejected generically. */
    public static String normalize(String raw) {
        if (raw == null) throw new ValidationException("Invalid phone number");
        String digits = raw.replaceAll("[\\s\\-()]", "");
        if (digits.startsWith("+91")) digits = digits.substring(3);
        else if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (!INDIAN_MOBILE.matcher(digits).matches()) throw new ValidationException("Invalid phone number");
        return "+91" + digits;
    }

    public static String mask(String normalizedPhone) {
        return "******" + normalizedPhone.substring(normalizedPhone.length() - 4);
    }

    /** Deterministic opaque key for equality lookups; fits the widened 100-character phone column. */
    public String lookupKey(String normalizedPhone) {
        return "h1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac("phone:" + normalizedPhone));
    }

    /**
     * The database key of one acoustic landmark (a 23-bit number made from the fingerprint of a recording): a keyed one-way hash, 63 bits, so the table can be
     * searched for the same landmark without holding the landmark itself. The same key as every other lookup here, with its own label.
     */
    public long landmarkKey(int landmark) {
        return keyOf("audiofp:" + landmark);
    }

    /** The database key of one word trigram of an answer's transcript (see ReplayDetector): keyed and one-way, so the words cannot be read back from it. */
    public long shingleKey(String trigram) {
        return keyOf("shingle:" + trigram);
    }

    private long keyOf(String labelled) {
        byte[] digest = hmac(labelled);
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (digest[i] & 0xFF);
        return value & Long.MAX_VALUE;
    }

    /** Keyed hash of a random browser token (trusted-device or code-binding cookie), so the database never holds a usable token. */
    public String tokenHash(String purpose, String token) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(purpose + ":" + token));
    }

    /** Keyed hash of a client address, for rate limiting and fraud features without storing raw IPs. */
    public String ipHash(String ip) {
        return hex(hmac("ip:" + (ip == null ? "unknown" : ip)));
    }

    public String deviceHash(String userAgent, String acceptLanguage) {
        return hex(hmac("device:" + userAgent + "|" + acceptLanguage));
    }

    public byte[] encrypt(String normalizedPhone) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + IV_BYTES + ciphertext.length];
            out[0] = VERSION;
            System.arraycopy(iv, 0, out, 1, IV_BYTES);
            System.arraycopy(ciphertext, 0, out, 1 + IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Phone encryption failed", exception);
        }
    }

    public String decrypt(byte[] blob) {
        if (blob == null || blob.length < 1 + IV_BYTES + 16 || blob[0] != VERSION) {
            throw new IllegalArgumentException("Unrecognised phone ciphertext");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, Arrays.copyOfRange(blob, 1, 1 + IV_BYTES)));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Phone decryption failed (wrong key or tampered data)", exception);
        }
    }

    private byte[] hmac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
