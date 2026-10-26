package com.kaarigarsetu.support;

import com.kaarigarsetu.security.DataCipher;
import com.kaarigarsetu.security.DataKeys;

import java.util.Arrays;

/** Ciphers over a fixed throw-away key ring for tests. The data cipher is STRICT: a code path that stores plaintext in a sealed column fails the test that reads it back. */
public final class TestCiphers {
    public static final byte[] KEY = filled(7);
    public static final DataKeys KEYS = DataKeys.single(KEY);

    private TestCiphers() {}

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    public static DataCipher data() {
        return DataCipher.of(KEYS, DataCipher.DATA, true);
    }

    public static DataCipher audit() {
        return DataCipher.of(KEYS, DataCipher.AUDIT, false);
    }

    public static DataCipher files() {
        return DataCipher.of(KEYS, DataCipher.FILES, false);
    }
}
