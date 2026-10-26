package com.kaarigarsetu.security;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The keys that protect stored data, as a ring: the FIRST key encrypts new data, every key in the ring can still decrypt. Rotating means putting a new key
 * at the front, re-encrypting the stored values (scripts/seal-at-rest.sh), and only then dropping the old key from the ring. Written as
 * {@code id:base64key,id:base64key}, for example {@code k2:AbC...=,k1:XyZ...=}; each key is 32 random bytes. The ring comes from the environment, never
 * from the database or the data directory, so a stolen database or backup does not hold the keys that open it.
 */
public final class DataKeys {
    private static final Pattern ID = Pattern.compile("^[a-z0-9]{1,12}$");

    private final String currentId;
    private final Map<String, byte[]> keys;

    private DataKeys(String currentId, Map<String, byte[]> keys) {
        this.currentId = currentId;
        this.keys = keys;
    }

    /** @throws IllegalStateException for an empty ring, a bad id, a repeated id or a key that is not 32 bytes of base64 (the message never contains key material) */
    public static DataKeys parse(String ring) {
        if (ring == null || ring.isBlank()) throw new IllegalStateException("DATA_ENC_KEYS (or DATA_ENC_KEY) is required");
        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String entry : ring.split(",")) {
            String trimmed = entry.trim();
            int colon = trimmed.indexOf(':');
            if (colon < 0) throw new IllegalStateException("DATA_ENC_KEYS: each entry must look like id:base64key");
            String id = trimmed.substring(0, colon);
            if (!ID.matcher(id).matches()) throw new IllegalStateException("DATA_ENC_KEYS: a key id is 1 to 12 lower-case letters or digits");
            byte[] key;
            try {
                key = Base64.getDecoder().decode(trimmed.substring(colon + 1));
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalStateException("DATA_ENC_KEYS: key " + id + " is not valid base64");
            }
            if (key.length != 32) throw new IllegalStateException("DATA_ENC_KEYS: key " + id + " must be 32 bytes (openssl rand -base64 32)");
            if (keys.put(id, key) != null) throw new IllegalStateException("DATA_ENC_KEYS: key id " + id + " appears twice");
        }
        return new DataKeys(keys.keySet().iterator().next(), keys);
    }

    /** One key in the ring named {@code k1}: the form used by the single-variable setting DATA_ENC_KEY. */
    public static DataKeys single(byte[] key) {
        if (key.length != 32) throw new IllegalStateException("a data key must be 32 bytes");
        return new DataKeys("k1", Map.of("k1", key.clone()));
    }

    public String currentId() {
        return currentId;
    }

    public Set<String> ids() {
        return keys.keySet();
    }

    /** Is this exact key material in the ring? Used to refuse reusing another secret (such as the phone key) as a data key. */
    public boolean containsKeyMaterial(byte[] candidate) {
        return keys.values().stream().anyMatch(key -> java.security.MessageDigest.isEqual(key, candidate));
    }

    /** The raw key for an id, or null if the ring does not hold it (data sealed under a key that was dropped cannot be opened). */
    byte[] key(String id) {
        byte[] key = keys.get(id);
        return key == null ? null : key.clone();
    }
}
