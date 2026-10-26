package com.kaarigarsetu.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All non-database configuration, read from environment variables (blueprint Phase 0: nothing secret is ever
 * hard-coded or committed). Tests build instances from a plain map via {@link #of(Map)}.
 */
public final class AppSettings {
    private final Map<String, String> env;

    private AppSettings(Map<String, String> env) {
        this.env = env;
    }

    public static AppSettings fromEnvironment() {
        return new AppSettings(new HashMap<>(System.getenv()));
    }

    public static AppSettings of(Map<String, String> values) {
        return new AppSettings(new HashMap<>(values));
    }

    public String appEnv() { return get("APP_ENV", "dev").toLowerCase(Locale.ROOT); }
    public boolean isProduction() { return appEnv().equals("prod") || appEnv().equals("production"); }

    public String mlServiceUrl() { return get("ML_SERVICE_URL", "http://localhost:8001"); }
    public String mlInternalToken() { return get("ML_INTERNAL_TOKEN", ""); }

    public byte[] phoneEncryptionKey() { return keyBytes("PHONE_ENC_KEY", 32, true); }
    public byte[] phoneHmacKey() { return keyBytes("PHONE_HMAC_KEY", 32, false); }
    /** Optional server-side pepper mixed into OTP hashes so a leaked database alone cannot brute-force codes. */
    public byte[] otpPepper() { return get("OTP_PEPPER", "").getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /**
     * The keys that encrypt stored transcripts, scores, the audit file and credential exports (see DataCipher). DATA_ENC_KEYS is a ring
     * "id:base64,id:base64" whose first key encrypts; DATA_ENC_KEY alone is a ring of one. Keep it out of the database and the data directory.
     */
    public com.kaarigarsetu.security.DataKeys dataKeys() {
        String ring = get("DATA_ENC_KEYS", "");
        if (ring.isBlank()) {
            String single = get("DATA_ENC_KEY", "");
            ring = single.isBlank() ? "" : "k1:" + single;
        }
        return com.kaarigarsetu.security.DataKeys.parse(ring);
    }
    /** Refuse to read a stored value that is not encrypted. On in production; off in development so a database can be converted while the app runs. */
    public boolean requireSealedAtRest() { return Boolean.parseBoolean(get("REQUIRE_SEALED_AT_REST", String.valueOf(isProduction()))); }

    public Path keystorePath() { return Path.of(get("KEYSTORE_PATH", "data/credential-keystore.p12")); }
    public char[] keystorePassword() { return get("KEYSTORE_PASSWORD", "").toCharArray(); }
    public Path retiredKeysDir() { return Path.of(get("RETIRED_KEYS_DIR", "data/keys/retired")); }
    public String keyAlias() { return get("KEY_ALIAS", "credential-signing"); }

    public boolean cookieSecure() { return Boolean.parseBoolean(get("COOKIE_SECURE", String.valueOf(!appEnv().equals("dev")))); }
    public boolean trustForwardedFor() { return Boolean.parseBoolean(get("TRUST_FORWARDED_FOR", "false")); }
    public String publicBaseUrl() { return get("PUBLIC_BASE_URL", "http://localhost:8080"); }
    public Path auditLogPath() { return Path.of(get("AUDIT_LOG_PATH", "data/audit.log")); }
    public byte[] auditHmacKey() { return get("AUDIT_HMAC_KEY", get("PHONE_HMAC_KEY", "")).getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /** The moderator actions that take effect only when a SECOND moderator approves them (see ModeratorService). The value NONE means single-moderator mode (development only; refused when APP_ENV=prod). */
    public java.util.Set<String> twoPersonActions() {
        java.util.Set<String> actions = new java.util.LinkedHashSet<>();
        String configured = get("TWO_PERSON_ACTIONS", "VET_EMPLOYER,DISPUTE_DECISION");     // a blank value means "unset", so switching it off takes the word NONE
        if (configured.trim().equalsIgnoreCase("none")) return actions;
        for (String name : configured.split(",")) {
            if (!name.isBlank()) actions.add(name.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return actions;
    }
    /** How long a proposal waits for its second moderator before it expires. */
    public int twoPersonLifetimeHours() { return integer("TWO_PERSON_LIFETIME_HOURS", 72); }

    /** One-click sign-in for the accounts made by the demo seeder. Development only: validate() refuses it in any other APP_ENV. */
    public boolean demoLoginEnabled() { return Boolean.parseBoolean(get("DEMO_LOGIN", "false")); }

    public int otpTtlSeconds() { return integer("OTP_TTL_SECONDS", 300); }
    public int otpMaxAttempts() { return integer("OTP_MAX_ATTEMPTS", 5); }
    /** Difficulty (leading zero bits) of the puzzle an UNKNOWN device solves before a login code is sent; 0 switches it off. 16 bits is about 65,000 hashes: well under a second. */
    public int otpPowBits() { return integer("OTP_POW_BITS", 16); }
    /** A browser that signed in is remembered this long without use, and at most this many per account (the oldest is dropped). */
    public int trustedDeviceDays() { return integer("TRUSTED_DEVICE_DAYS", 180); }
    public int maxTrustedDevices() { return integer("MAX_TRUSTED_DEVICES", 5); }
    /**
     * Replay detection (docs/REPLAY_DETECTION.md). The challenge shows each question with a random spoken code the worker must say at the end of the answer,
     * so a recording made earlier cannot contain it. The fingerprint and text checks look for an answer that is the same recording, or the same words, as an
     * earlier one by anyone. Both are on by default; turning one off is for development and measurement.
     */
    public boolean replayChallengeEnabled() { return Boolean.parseBoolean(get("REPLAY_CHALLENGE", "true")); }
    public int replayChallengeTries() { return integer("REPLAY_CHALLENGE_TRIES", 3); }
    public int replayChallengeMinutes() { return integer("REPLAY_CHALLENGE_MINUTES", 15); }
    public boolean replayDetectionEnabled() { return Boolean.parseBoolean(get("REPLAY_DETECTION", "true")); }
    public int replayMinAudioMatches() { return integer("REPLAY_MIN_MATCHES", 25); }
    public int replayMinLandmarks() { return integer("REPLAY_MIN_LANDMARKS", 100); }
    public int replayTextMinShingles() { return integer("REPLAY_TEXT_MIN_SHINGLES", 14); }
    public int replayTextMinCommon() { return integer("REPLAY_TEXT_MIN_COMMON", 8); }
    public double replayTextMinShare() {
        try {
            return Double.parseDouble(get("REPLAY_TEXT_MIN_SHARE", "0.5"));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    public int credentialValidDays() { return integer("CREDENTIAL_VALID_DAYS", 180); }
    /** How long after a confirmation was approved the worker may still dispute it. */
    public int disputeWindowDays() { return integer("DISPUTE_WINDOW_DAYS", 30); }
    /** How many unresolved disputes one worker may have at once: disputing is a request for a ruling, not a way to hide ratings. */
    public int disputeMaxOpen() { return integer("DISPUTE_MAX_OPEN", 3); }
    public int minTrustForCredential() { return integer("MIN_TRUST_FOR_CREDENTIAL", 0); }
    public int passThreshold() { return integer("PASS_THRESHOLD", 70); }
    public int borderlineThreshold() { return integer("BORDERLINE_THRESHOLD", 50); }
    /** A score this close to a threshold is reported as "near a boundary". */
    public int boundaryMargin() { return integer("BAND_BOUNDARY_MARGIN", 5); }
    /** Fixed by the scoring rule and the rubric files, not an environment setting. */
    public int questionsPerAssessment() { return com.kaarigarsetu.assessment.AssessmentService.QUESTIONS_PER_ASSESSMENT; }
    public int maxAudioBytes() { return integer("MAX_AUDIO_BYTES", 2 * 1024 * 1024); }
    public int dbPoolSize() { return integer("DB_POOL_SIZE", 10); }
    /** "console" (default: codes are logged, development and closed pilots only) or "http" (a real SMS gateway, see docs/SMS_SETUP.md). */
    public String smsProvider() { return get("SMS_PROVIDER", "console").toLowerCase(Locale.ROOT); }
    public boolean smsEnabled() { return smsProvider().equals("http"); }
    public String smsUrl() { return get("SMS_URL", ""); }
    /** Name and value of the authentication header (the value is the secret: environment only). Leave the name empty if the key is in the URL or body. */
    public String smsAuthHeaderName() { return get("SMS_AUTH_HEADER", "Authorization"); }
    public String smsAuthHeaderValue() { return get("SMS_AUTH_VALUE", ""); }
    public boolean smsJson() { return !get("SMS_CONTENT_TYPE", "json").equalsIgnoreCase("form"); }
    public String smsBodyTemplate() { return get("SMS_BODY", ""); }
    public String smsSenderId() { return get("SMS_SENDER", ""); }
    public String smsMessageTemplate() { return get("SMS_MESSAGE", "Your Kaarigar Setu code is {code}. It expires in " + (otpTtlSeconds() / 60) + " minutes. Do not share it with anyone."); }
    public boolean allowConsoleOtp() { return Boolean.parseBoolean(get("ALLOW_CONSOLE_OTP", "false")); }

    /** Fail fast on a misconfigured deployment, reporting every problem at once. */
    public void validate() {
        List<String> problems = new ArrayList<>();
        check(problems, "PHONE_ENC_KEY", this::phoneEncryptionKey);
        check(problems, "PHONE_HMAC_KEY", this::phoneHmacKey);
        check(problems, "DATA_ENC_KEYS", () -> {
            if (dataKeys().containsKeyMaterial(phoneEncryptionKey())) throw new IllegalStateException("DATA_ENC_KEYS must not reuse PHONE_ENC_KEY: one secret per purpose");
        });
        if (isProduction() && !requireSealedAtRest()) problems.add("REQUIRE_SEALED_AT_REST must be true when APP_ENV=prod");
        if (keystorePassword().length == 0) problems.add("KEYSTORE_PASSWORD is required");
        if (borderlineThreshold() < 0 || borderlineThreshold() >= passThreshold() || passThreshold() > 100 || boundaryMargin() < 0) {
            problems.add("BORDERLINE_THRESHOLD must be below PASS_THRESHOLD (at most 100) and BAND_BOUNDARY_MARGIN must not be negative");
        }
        for (String action : twoPersonActions()) {
            if (!action.equals("VET_EMPLOYER") && !action.equals("DISPUTE_DECISION")) problems.add("TWO_PERSON_ACTIONS: unknown action " + action + " (use VET_EMPLOYER, DISPUTE_DECISION)");
        }
        if (twoPersonLifetimeHours() < 1 || twoPersonLifetimeHours() > 720) problems.add("TWO_PERSON_LIFETIME_HOURS must be between 1 and 720");
        if (otpPowBits() < 0 || otpPowBits() > 26) problems.add("OTP_POW_BITS must be between 0 (off) and 26");
        if (trustedDeviceDays() < 1 || trustedDeviceDays() > 730) problems.add("TRUSTED_DEVICE_DAYS must be between 1 and 730");
        if (maxTrustedDevices() < 1 || maxTrustedDevices() > 20) problems.add("MAX_TRUSTED_DEVICES must be between 1 and 20");
        if (replayChallengeTries() < 1 || replayChallengeTries() > 5) problems.add("REPLAY_CHALLENGE_TRIES must be between 1 and 5");
        if (replayChallengeMinutes() < 1 || replayChallengeMinutes() > 120) problems.add("REPLAY_CHALLENGE_MINUTES must be between 1 and 120");
        if (replayMinAudioMatches() < 8 || replayMinAudioMatches() > 500) problems.add("REPLAY_MIN_MATCHES must be between 8 and 500 (below 8 a coincidence could match)");
        if (replayMinLandmarks() < 20 || replayMinLandmarks() > 3000) problems.add("REPLAY_MIN_LANDMARKS must be between 20 and 3000");
        if (replayTextMinShingles() < 8 || replayTextMinShingles() > 400) problems.add("REPLAY_TEXT_MIN_SHINGLES must be between 8 and 400");
        if (replayTextMinCommon() < 4 || replayTextMinCommon() > 200) problems.add("REPLAY_TEXT_MIN_COMMON must be between 4 and 200");
        if (replayTextMinShare() < 0.5 || replayTextMinShare() > 1.0) problems.add("REPLAY_TEXT_MIN_SHARE must be between 0.5 and 1.0");
        if (isProduction() && !replayDetectionEnabled()) problems.add("REPLAY_DETECTION must stay on when APP_ENV=prod");
        if (demoLoginEnabled() && !appEnv().equals("dev")) problems.add("DEMO_LOGIN=true is only allowed when APP_ENV=dev");
        if (!smsProvider().equals("console") && !smsProvider().equals("http")) problems.add("SMS_PROVIDER must be console or http");
        if (smsEnabled()) {
            if (smsUrl().isBlank()) problems.add("SMS_URL is required when SMS_PROVIDER=http");
            else if (isProduction() && !smsUrl().startsWith("https://")) problems.add("SMS_URL must use https when APP_ENV=prod");
            if (smsBodyTemplate().isBlank()) problems.add("SMS_BODY is required when SMS_PROVIDER=http (see docs/SMS_SETUP.md)");
            if (!smsAuthHeaderName().isBlank() && smsAuthHeaderValue().isBlank()) problems.add("SMS_AUTH_VALUE is required when SMS_AUTH_HEADER is set");
            if (!smsMessageTemplate().contains("{code}")) problems.add("SMS_MESSAGE must contain {code}");
        }
        if (isProduction()) {
            if (!cookieSecure()) problems.add("COOKIE_SECURE must be true when APP_ENV=prod");
            if (mlInternalToken().isBlank()) problems.add("ML_INTERNAL_TOKEN is required when APP_ENV=prod");
            if (twoPersonActions().isEmpty()) problems.add("TWO_PERSON_ACTIONS=none is not allowed when APP_ENV=prod: vetting and dispute rulings need a second moderator");
            if (!allowConsoleOtp() && !smsEnabled()) problems.add("console OTP delivery logs codes; set ALLOW_CONSOLE_OTP=true to accept that for a closed pilot, or plug in an SMS sender");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid configuration:\n - " + String.join("\n - ", problems));
        }
    }

    private void check(List<String> problems, String name, Runnable read) {
        try {
            read.run();
        } catch (IllegalStateException exception) {
            problems.add(exception.getMessage());
        }
    }

    private String get(String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int integer(String name, int fallback) {
        try {
            return Integer.parseInt(get(name, String.valueOf(fallback)));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be an integer");
        }
    }

    private byte[] keyBytes(String name, int minBytes, boolean exact) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required (base64)");
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be base64");
        }
        if (exact ? raw.length != minBytes : raw.length < minBytes) {
            throw new IllegalStateException(name + " must decode to " + (exact ? "exactly " : "at least ") + minBytes + " bytes");
        }
        return raw;
    }
}
