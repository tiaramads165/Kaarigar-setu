package com.kaarigarsetu.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppSettingsDemoAndBandsTest {
    private static Map<String, String> valid() {
        Map<String, String> env = new HashMap<>();
        env.put("PHONE_ENC_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        env.put("PHONE_HMAC_KEY", Base64.getEncoder().encodeToString(new byte[32]));
        byte[] dataKey = new byte[32];
        java.util.Arrays.fill(dataKey, (byte) 1);
        env.put("DATA_ENC_KEY", Base64.getEncoder().encodeToString(dataKey));
        env.put("KEYSTORE_PASSWORD", "x".repeat(12));
        return env;
    }

    private static Map<String, String> with(String... kv) {
        Map<String, String> env = valid();
        for (int i = 0; i < kv.length; i += 2) env.put(kv[i], kv[i + 1]);
        return env;
    }

    @Test
    void demoSignInIsOffByDefaultAndOnlyAllowedInDev() {
        assertFalse(AppSettings.of(valid()).demoLoginEnabled());
        assertDoesNotThrow(() -> AppSettings.of(with("DEMO_LOGIN", "true", "APP_ENV", "dev")).validate());
        for (String env : new String[]{"prod", "production", "staging", "test", "demo"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> AppSettings.of(with("DEMO_LOGIN", "true", "APP_ENV", env)).validate(), env);
            assertTrue(e.getMessage().contains("DEMO_LOGIN"), env);
        }
        assertDoesNotThrow(() -> AppSettings.of(with("DEMO_LOGIN", "false", "APP_ENV", "staging")).validate());
    }

    @Test
    void bandThresholdsHaveTheAgreedDefaultsAndMustBeConsistent() {
        AppSettings defaults = AppSettings.of(valid());
        assertEquals(70, defaults.passThreshold());
        assertEquals(50, defaults.borderlineThreshold());
        assertEquals(5, defaults.boundaryMargin());
        assertDoesNotThrow(() -> AppSettings.of(with("PASS_THRESHOLD", "80", "BORDERLINE_THRESHOLD", "60")).validate());
        for (String[] bad : new String[][]{{"PASS_THRESHOLD", "50", "BORDERLINE_THRESHOLD", "50"}, {"PASS_THRESHOLD", "40", "BORDERLINE_THRESHOLD", "60"},
                {"PASS_THRESHOLD", "101"}, {"BAND_BOUNDARY_MARGIN", "-1"}, {"BORDERLINE_THRESHOLD", "-5"}}) {
            assertThrows(IllegalStateException.class, () -> AppSettings.of(with(bad)).validate(), String.join(",", bad));
        }
    }
}
