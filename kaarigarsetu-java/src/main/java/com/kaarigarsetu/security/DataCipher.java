package com.kaarigarsetu.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Encryption at rest for stored values: AES-256-GCM with a fresh random 96-bit nonce for every value.
 *
 * <p>A sealed value is the text {@code enc:v1:<keyId>:<base64url(nonce + ciphertext + tag)>}. The key id says which key of the ring sealed it (so keys can be
 * rotated); the GCM tag makes any change detectable. Each value is also tied to WHERE it lives: the associated data is {@code purpose|context}, for example
 * {@code workers.trust_score|42}, so a ciphertext copied into another column or onto another row does not open. A write-capable attacker without the key can
 * therefore neither read values nor invent them, only delete or restore them.
 *
 * <p>Separate DOMAINS ("data", "audit", "files") each use their own key derived from the ring key (HMAC-SHA256 of a label), so a weakness or leak in one use
 * does not open the others.
 *
 * <p>Values written before encryption was switched on are plain text. They are accepted on read (and counted, see {@link #unsealedReads()}) unless
 * {@code requireSealed} is set, so that a database can be converted with scripts/seal-at-rest.sh while the application runs, and a deployment that must
 * never hold plaintext can refuse it.
 */
public final class DataCipher {
    public static final String PREFIX = "enc:v1:";
    public static final String DATA = "data";
    public static final String AUDIT = "audit";
    public static final String FILES = "files";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final DataKeys ring;
    private final Map<String, SecretKeySpec> domainKeys = new ConcurrentHashMap<>();
    private final String domain;
    private final boolean requireSealed;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong unsealedReads = new AtomicLong();

    private DataCipher(DataKeys ring, String domain, boolean requireSealed) {
        this.ring = ring;
        this.domain = domain;
        this.requireSealed = requireSealed;
    }

    public static DataCipher of(DataKeys ring, String domain, boolean requireSealed) {
        return new DataCipher(ring, domain, requireSealed);
    }

    /** A cipher for the same ring but another domain (same strictness). */
    public DataCipher forDomain(String otherDomain) {
        return new DataCipher(ring, otherDomain, requireSealed);
    }

    public String domain() {
        return domain;
    }

    public static boolean isSealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** The id of the key that sealed this value, or null if it is not sealed. */
    public static String keyIdOf(String stored) {
        if (!isSealed(stored)) return null;
        int end = stored.indexOf(':', PREFIX.length());
        return end < 0 ? null : stored.substring(PREFIX.length(), end);
    }

    /** True when the value is sealed under the key that new data is sealed with. */
    public boolean usesCurrentKey(String stored) {
        return ring.currentId().equals(keyIdOf(stored));
    }

    /** How many plaintext values have been read since start (a database that is fully sealed keeps this at zero). */
    public long unsealedReads() {
        return unsealedReads.get();
    }

    // ------------------------------------------------------------------------------------------------------------------------ text

    /** Seals text under the current key. {@code null} stays {@code null}, so SQL NULL keeps its meaning. */
    public String seal(String purpose, Object context, String plain) {
        if (plain == null) return null;
        String keyId = ring.currentId();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, domainKey(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(purpose, context));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, blob, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, blob, NONCE_BYTES, encrypted.length);
            return PREFIX + keyId + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(blob);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt a stored value", exception);
        }
    }

    /**
     * Opens a sealed value. A value that is not sealed is legacy plaintext: returned as it is (and counted), or refused when sealing is required.
     * A sealed value that was sealed for another place, with another key, or has been altered is refused with {@link SealedDataException}.
     */
    public String open(String purpose, Object context, String stored) {
        if (stored == null) return null;
        if (!isSealed(stored)) {
            if (requireSealed) throw new SealedDataException("Unencrypted value found in " + purpose + " (sealing is required)");
            unsealedReads.incrementAndGet();
            return stored;
        }
        String keyId = keyIdOf(stored);
        if (keyId == null || ring.key(keyId) == null) throw new SealedDataException("Value in " + purpose + " is sealed with a key this server does not hold");
        try {
            byte[] blob = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length() + keyId.length() + 1));
            if (blob.length < NONCE_BYTES + TAG_BITS / 8) throw new SealedDataException("Sealed value in " + purpose + " is truncated");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, domainKey(keyId), new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(aad(purpose, context));
            return new String(cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new SealedDataException("Value in " + purpose + " does not open: altered, or sealed for a different place");
        }
    }

    // ------------------------------------------------------------------------------------------------------- typed values

    public String sealDecimal(String purpose, Object context, BigDecimal value) {
        return value == null ? null : seal(purpose, context, value.toPlainString());
    }

    public BigDecimal openDecimal(String purpose, Object context, String stored) {
        String plain = open(purpose, context, stored);
        return plain == null ? null : new BigDecimal(plain.trim());
    }

    public String sealInt(String purpose, Object context, Integer value) {
        return value == null ? null : seal(purpose, context, Integer.toString(value));
    }

    public Integer openInt(String purpose, Object context, String stored) {
        String plain = open(purpose, context, stored);
        return plain == null ? null : Integer.valueOf(plain.trim());
    }

    public String sealBool(String purpose, Object context, Boolean value) {
        return value == null ? null : seal(purpose, context, value ? "true" : "false");
    }

    public Boolean openBool(String purpose, Object context, String stored) {
        String plain = open(purpose, context, stored);
        if (plain == null) return null;
        String value = plain.trim().toLowerCase(java.util.Locale.ROOT);
        return value.equals("true") || value.equals("t");
    }

    public String sealDate(String purpose, Object context, LocalDate value) {
        return value == null ? null : seal(purpose, context, value.toString());
    }

    public LocalDate openDate(String purpose, Object context, String stored) {
        String plain = open(purpose, context, stored);
        return plain == null ? null : LocalDate.parse(plain.trim());
    }

    // ------------------------------------------------------------------------------------------------------------ internals

    private static byte[] aad(String purpose, Object context) {
        return (purpose + "|" + context).getBytes(StandardCharsets.UTF_8);
    }

    private SecretKeySpec domainKey(String keyId) throws GeneralSecurityException {
        SecretKeySpec cached = domainKeys.get(keyId);
        if (cached != null) return cached;
        byte[] master = ring.key(keyId);
        if (master == null) throw new GeneralSecurityException("no such key");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        SecretKeySpec derived = new SecretKeySpec(mac.doFinal(("kaarigar-setu/at-rest/" + domain).getBytes(StandardCharsets.UTF_8)), "AES");
        domainKeys.put(keyId, derived);
        return derived;
    }
}
