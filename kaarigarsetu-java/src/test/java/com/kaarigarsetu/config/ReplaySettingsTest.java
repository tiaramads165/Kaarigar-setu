package com.kaarigarsetu.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReplaySettingsTest {
    private static Map<String, String> base() {
        Map<String, String> env = new HashMap<>();
        env.put("PHONE_ENC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        env.put("PHONE_HMAC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        env.put("DATA_ENC_KEY", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        env.put("KEYSTORE_PASSWORD", "x");
        return env;
    }

    private static String problems(Map<String, String> env) {
        try { AppSettings.of(env).validate(); return ""; } catch (IllegalStateException e) { return e.getMessage(); }
    }

    private static String with(String key, String value) {
        Map<String, String> env = base();
        env.put(key, value);
        return problems(env);
    }

    @Test
    void theCalibratedDefaultsAreOnAndValid() {
        AppSettings s = AppSettings.of(base());
        assertEquals("", problems(base()));
        assertTrue(s.replayChallengeEnabled());
        assertTrue(s.replayDetectionEnabled());
        assertEquals(3, s.replayChallengeTries());
        assertEquals(15, s.replayChallengeMinutes());
        assertEquals(25, s.replayMinAudioMatches());
        assertEquals(100, s.replayMinLandmarks());
        assertEquals(14, s.replayTextMinShingles());
        assertEquals(8, s.replayTextMinCommon());
        assertEquals(0.5, s.replayTextMinShare());
    }

    @Test
    void thresholdsOutsideTheirSafeRangeAreRefusedAtStartup() {
        assertTrue(with("REPLAY_CHALLENGE_TRIES", "0").contains("REPLAY_CHALLENGE_TRIES"));
        assertTrue(with("REPLAY_CHALLENGE_TRIES", "6").contains("REPLAY_CHALLENGE_TRIES"));
        assertTrue(with("REPLAY_CHALLENGE_MINUTES", "0").contains("REPLAY_CHALLENGE_MINUTES"));
        assertTrue(with("REPLAY_MIN_MATCHES", "7").contains("a coincidence could match"), "a threshold that chance can reach");
        assertTrue(with("REPLAY_MIN_LANDMARKS", "19").contains("REPLAY_MIN_LANDMARKS"));
        assertTrue(with("REPLAY_TEXT_MIN_SHINGLES", "7").contains("REPLAY_TEXT_MIN_SHINGLES"));
        assertTrue(with("REPLAY_TEXT_MIN_COMMON", "3").contains("REPLAY_TEXT_MIN_COMMON"));
        assertTrue(with("REPLAY_TEXT_MIN_SHARE", "0.4").contains("REPLAY_TEXT_MIN_SHARE"));
        assertTrue(with("REPLAY_TEXT_MIN_SHARE", "1.1").contains("REPLAY_TEXT_MIN_SHARE"));
        assertEquals("", with("REPLAY_MIN_MATCHES", "8"));
        assertEquals("", with("REPLAY_TEXT_MIN_SHARE", "1.0"));
    }

    @Test
    void detectionCannotBeSwitchedOffInProduction() {
        assertEquals("", with("REPLAY_DETECTION", "false"), "allowed while developing");
        Map<String, String> prod = base();
        prod.put("APP_ENV", "prod");
        prod.put("REPLAY_DETECTION", "false");
        assertTrue(problems(prod).contains("REPLAY_DETECTION must stay on"));
    }
}
