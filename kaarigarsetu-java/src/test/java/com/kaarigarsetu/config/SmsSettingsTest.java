package com.kaarigarsetu.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmsSettingsTest {
    private static Map<String, String> base() {
        Map<String, String> env = new HashMap<>();
        env.put("PHONE_ENC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        env.put("PHONE_HMAC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        env.put("DATA_ENC_KEY", "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        env.put("KEYSTORE_PASSWORD", "x");
        return env;
    }

    private static Map<String, String> sms() {
        Map<String, String> env = base();
        env.put("SMS_PROVIDER", "http");
        env.put("SMS_URL", "https://gateway.example/send");
        env.put("SMS_AUTH_VALUE", "k");
        env.put("SMS_BODY", "{\"to\":\"{to}\",\"text\":\"{message}\"}");
        return env;
    }

    private static String problems(Map<String, String> env) {
        try { AppSettings.of(env).validate(); return ""; } catch (IllegalStateException e) { return e.getMessage(); }
    }

    @Test
    void consoleIsTheDefaultAndAValidSmsSetupPasses() {
        assertFalse(AppSettings.of(base()).smsEnabled());
        assertEquals("", problems(base()));
        assertEquals("", problems(sms()));
        assertTrue(AppSettings.of(sms()).smsEnabled());
    }

    @Test
    void anIncompleteSmsSetupIsRefusedAtStartupWithEveryProblemListed() {
        Map<String, String> env = sms();
        env.remove("SMS_URL"); env.remove("SMS_BODY"); env.remove("SMS_AUTH_VALUE");
        String message = problems(env);
        assertTrue(message.contains("SMS_URL") && message.contains("SMS_BODY") && message.contains("SMS_AUTH_VALUE"), message);
    }

    @Test
    void productionRefusesToRunWithoutTwoPersonApprovalAndTheListIsValidated() {
        Map<String, String> env = sms();
        env.put("APP_ENV", "prod"); env.put("COOKIE_SECURE", "true"); env.put("ML_INTERNAL_TOKEN", "t");
        assertEquals("", problems(env));
        assertEquals(java.util.Set.of("VET_EMPLOYER", "DISPUTE_DECISION"), AppSettings.of(env).twoPersonActions());
        env.put("TWO_PERSON_ACTIONS", "none");
        assertTrue(problems(env).contains("TWO_PERSON_ACTIONS"));
        Map<String, String> dev = base();
        dev.put("TWO_PERSON_ACTIONS", "none");
        assertEquals("", problems(dev), "single-moderator mode is allowed in development");
        dev.put("TWO_PERSON_ACTIONS", "vet_employer, nonsense");
        assertTrue(problems(dev).contains("nonsense".toUpperCase()));
        dev.put("TWO_PERSON_ACTIONS", "VET_EMPLOYER");
        dev.put("TWO_PERSON_LIFETIME_HOURS", "0");
        assertTrue(problems(dev).contains("TWO_PERSON_LIFETIME_HOURS"));
    }

    @Test
    void anUnknownProviderAndAMessageWithoutTheCodeAreRefused() {
        Map<String, String> env = sms();
        env.put("SMS_PROVIDER", "carrier-pigeon");
        assertTrue(problems(env).contains("SMS_PROVIDER"));
        env = sms();
        env.put("SMS_MESSAGE", "hello with no code");
        assertTrue(problems(env).contains("{code}"));
    }

    @Test
    void productionNeedsHttpsAndNoLongerNeedsTheConsoleOverrideWhenSmsIsOn() {
        Map<String, String> env = sms();
        env.put("APP_ENV", "prod"); env.put("COOKIE_SECURE", "true"); env.put("ML_INTERNAL_TOKEN", "t");
        assertEquals("", problems(env));
        env.put("SMS_URL", "http://insecure.example/send");
        assertTrue(problems(env).contains("https"));
        Map<String, String> consoleProd = base();
        consoleProd.put("APP_ENV", "prod"); consoleProd.put("COOKIE_SECURE", "true"); consoleProd.put("ML_INTERNAL_TOKEN", "t");
        assertTrue(problems(consoleProd).contains("ALLOW_CONSOLE_OTP"));
    }

    @Test
    void theDefaultMessageCarriesTheCodePlaceholderAndTheExpiry() {
        String message = AppSettings.of(base()).smsMessageTemplate();
        assertTrue(message.contains("{code}") && message.contains("5 minutes"), message);
    }

    @Test
    void theDataEncryptionKeysAreRequiredDistinctFromThePhoneKeyAndFormTheRingOrOneKey() {
        assertEquals("", problems(base()));
        assertEquals("k1", AppSettings.of(base()).dataKeys().currentId());
        Map<String, String> missing = base();
        missing.remove("DATA_ENC_KEY");
        assertTrue(problems(missing).contains("DATA_ENC_KEYS"), problems(missing));
        Map<String, String> reused = base();
        reused.put("DATA_ENC_KEY", reused.get("PHONE_ENC_KEY"));
        assertTrue(problems(reused).contains("must not reuse PHONE_ENC_KEY"));
        Map<String, String> ring = base();
        ring.remove("DATA_ENC_KEY");
        ring.put("DATA_ENC_KEYS", "k2:AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=,k1:AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");
        assertEquals("", problems(ring));
        assertEquals("k2", AppSettings.of(ring).dataKeys().currentId());
        ring.put("DATA_ENC_KEYS", "k2:not-a-key");
        assertTrue(problems(ring).contains("DATA_ENC_KEYS"));
        assertFalse(problems(ring).contains("not-a-key"), "the offending value is not echoed back");
    }

    @Test
    void sealingIsRequiredInProductionAndOptionalInDevelopment() {
        assertFalse(AppSettings.of(base()).requireSealedAtRest());
        Map<String, String> prod = sms();
        prod.put("APP_ENV", "prod"); prod.put("COOKIE_SECURE", "true"); prod.put("ML_INTERNAL_TOKEN", "t");
        assertTrue(AppSettings.of(prod).requireSealedAtRest());
        assertEquals("", problems(prod));
        prod.put("REQUIRE_SEALED_AT_REST", "false");
        assertTrue(problems(prod).contains("REQUIRE_SEALED_AT_REST"));
    }
}
