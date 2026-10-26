package com.kaarigarsetu.admin;

import com.kaarigarsetu.security.ChainedAuditFileSink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline auditor tool: checks the hash chain and the keyed head record of an audit file without starting the application, and with {@code --db} also
 * the anchors that the application pinned in the database (which catch a whole-file rollback that the chain alone cannot).
 *
 *     AUDIT_HMAC_KEY=... java -cp ... com.kaarigarsetu.admin.AuditVerifyCli data/audit.log [--db]
 *
 * With --db the connection comes from DB_HOST, DB_PORT, DB_NAME, DB_USER and DB_PASSWORD. Exit code 0 = intact, 1 = tampered or unreadable, 2 = usage
 * error. Keys and passwords are read from the environment, never from the command line, so they do not show up in the process list.
 */
public final class AuditVerifyCli {
    private static final Pattern ANCHOR = Pattern.compile("^seq=(\\d+) hash=([0-9a-f]{64})$");

    private AuditVerifyCli() {
    }

    public static void main(String[] args) throws Exception {
        boolean useDb = args.length == 2 && args[1].equals("--db");
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !useDb)) {
            System.err.println("usage: AuditVerifyCli <audit-file> [--db]   (key in AUDIT_HMAC_KEY or PHONE_HMAC_KEY)");
            System.exit(2);
        }
        String key = System.getenv("AUDIT_HMAC_KEY");
        if (key == null || key.isBlank()) key = System.getenv("PHONE_HMAC_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("no key: set AUDIT_HMAC_KEY (or PHONE_HMAC_KEY)");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        if (!Files.exists(file)) {
            System.err.println("no such file: " + file);
            System.exit(2);
        }
        com.kaarigarsetu.security.DataCipher cipher = auditCipherFromEnvironment();
        List<ChainedAuditFileSink.Anchor> anchors;
        try {
            anchors = useDb ? readAnchors(cipher) : List.of();
        } catch (KeysMissing missing) {
            System.err.println("the anchors in the database are encrypted: set DATA_ENC_KEYS");
            System.exit(2);
            return;
        }
        ChainedAuditFileSink.VerifyResult result = new ChainedAuditFileSink(file, key.getBytes(StandardCharsets.UTF_8), Clock.systemUTC(), cipher).verify(anchors);
        if (result.valid()) {
            System.out.println("INTACT lines=" + result.lines() + (useDb ? " anchors=" + anchors.size() : ""));
        } else {
            System.out.println("TAMPERED line=" + result.firstBadLine() + " of " + result.lines() + " reason=" + result.reason());
            System.exit(1);
        }
    }

    /** The audit-domain cipher from DATA_ENC_KEYS / DATA_ENC_KEY, or null when no data key is set (then only a file with no encrypted lines can be checked). */
    private static com.kaarigarsetu.security.DataCipher auditCipherFromEnvironment() {
        try {
            return com.kaarigarsetu.security.DataCipher.of(com.kaarigarsetu.config.AppSettings.fromEnvironment().dataKeys(), com.kaarigarsetu.security.DataCipher.AUDIT, false);
        } catch (IllegalStateException noKey) {
            return null;
        }
    }

    /** The database anchors are sealed and no data key is configured (thrown instead of exiting inside the open connection, so it is always closed). */
    private static final class KeysMissing extends RuntimeException {
        KeysMissing() {
            super("DATA_ENC_KEYS is not set", null, false, false);
        }
    }

    private static List<ChainedAuditFileSink.Anchor> readAnchors(com.kaarigarsetu.security.DataCipher cipher) throws Exception {
        String url = "jdbc:postgresql://" + env("DB_HOST") + ":" + env("DB_PORT") + "/" + env("DB_NAME");
        List<ChainedAuditFileSink.Anchor> anchors = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, env("DB_USER"), env("DB_PASSWORD"));
             PreparedStatement statement = connection.prepareStatement("SELECT details FROM audit_log WHERE event_type = ? ORDER BY chain_seq DESC LIMIT 5000")) {
            statement.setString(1, "AUDIT_FILE_ANCHOR");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String details = rows.getString(1);
                    if (com.kaarigarsetu.security.DataCipher.isSealed(details)) {
                        if (cipher == null) throw new KeysMissing();
                        details = cipher.open("audit_log.details", "AUDIT_FILE_ANCHOR|system", details);
                    }
                    Matcher m = ANCHOR.matcher(details);
                    if (m.matches()) anchors.add(new ChainedAuditFileSink.Anchor(Long.parseLong(m.group(1)), m.group(2)));
                }
            }
        }
        return anchors;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("--db needs " + name + " in the environment");
            System.exit(2);
        }
        return value;
    }
}
