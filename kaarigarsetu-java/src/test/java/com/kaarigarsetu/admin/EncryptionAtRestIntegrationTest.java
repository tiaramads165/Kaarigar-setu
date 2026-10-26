package com.kaarigarsetu.admin;

import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.AuditLogDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.DataCipher;
import com.kaarigarsetu.security.DataKeys;
import com.kaarigarsetu.security.SealedDataException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Encryption at rest against a real PostgreSQL: what the database file or a dump holds, that every sealed column is covered by the operator's registry
 * (the same labels the data-access classes use), and that the operator's tool converts, checks and re-keys correctly.
 */
class EncryptionAtRestIntegrationTest {
    private static final String URL = System.getenv("TEST_DB_URL");
    private static final String[] CANARIES = {"Zarina Canary", "CANARY-TRANSCRIPT first I would switch off the supply", "CANARY-FEEDBACK mentions the earthing", "CANARY-REASON low confidence",
            "Canary Builders", "CANARY-JOB wiring", "CANARY-DISPUTE I never worked there", "CANARY-FEATURES", "CANARY-PAYLOAD", "81.30", "77.25", "BORDERLINE", "EXCELLENT",
            "CANARY-AUDIT-DETAILS", "CANARY-BREAKDOWN", "CANARY-REPORT"};

    @TempDir Path exports;
    private final ConnectionProvider provider = () -> DriverManager.getConnection(URL,
            System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    private static final String K1 = Base64.getEncoder().encodeToString(filled(7));
    private static final String K2 = Base64.getEncoder().encodeToString(filled(8));
    private DataCipher data;
    private WorkerDao workers;
    private EmployerDao employers;
    private AssessmentDao assessments;
    private AttestationDao attestations;
    private FraudFlagDao flags;
    private CredentialDao credentials;
    private AuditLogDao audit;

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static DataCipher cipher(String ring, String domain, boolean strict) {
        return DataCipher.of(DataKeys.parse(ring), domain, strict);
    }

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(URL != null && URL.endsWith("_test"), "TEST_DB_URL must name a *_test database");
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS public CASCADE");
            s.execute("CREATE SCHEMA public");
            Path dir = Path.of("..", "database");
            s.execute(Files.readString(dir.resolve("schema.sql")));
            try (Stream<Path> migrations = Files.list(dir.resolve("migrations"))) {
                for (Path m : migrations.filter(f -> f.toString().endsWith(".sql")).sorted().toList()) s.execute(Files.readString(m));
            }
        }
        useCipher(cipher("k1:" + K1, DataCipher.DATA, true));
    }

    private void useCipher(DataCipher cipher) {
        data = cipher;
        workers = new WorkerDao(provider, data);
        employers = new EmployerDao(provider, data);
        assessments = new AssessmentDao(provider, data);
        attestations = new AttestationDao(provider, data);
        flags = new FraudFlagDao(provider, data);
        credentials = new CredentialDao(provider, data);
        audit = new AuditLogDao(provider, cipher("k1:" + K1, DataCipher.AUDIT, false));
    }

    private SealAtRestCli tool(String ring, boolean strictOpen) {
        DataCipher dataCipher = cipher(ring, DataCipher.DATA, strictOpen);
        return new SealAtRestCli(provider, dataCipher, dataCipher.forDomain(DataCipher.FILES), null, exports, new PrintStream(out), new PrintStream(err));
    }

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** One of everything the application stores, carrying recognisable text. */
    private long populate() {
        Worker worker = workers.insertProtected(new Worker(null, "Zarina Canary", "h1:canary-worker", "electrician", "hi", new BigDecimal("81.30"), new BigDecimal("77.25"), null), new byte[]{1}, "v1");
        long workerId = worker.workerId();
        workers.updateSkillProfile(workerId, new BigDecimal("81.30"), "BORDERLINE", "{\"note\":\"CANARY-BREAKDOWN\"}", true);
        workers.updateTrustProfile(workerId, new BigDecimal("77.25"), "EXCELLENT", "{\"note\":\"CANARY-REPORT\"}");
        Employer employer = employers.insertProtected(new Employer(null, "Canary Builders", "h1:canary-employer", false, null), new byte[]{2}, "v1");
        assessments.insertAnswer(new AssessmentAnswer(null, workerId, "electrician", UUID.randomUUID(), 1, "hi", "CANARY-TRANSCRIPT first I would switch off the supply",
                new BigDecimal("77.25"), "CANARY-FEEDBACK mentions the earthing", true, "CANARY-REASON low confidence", "stub", null));
        AttestationDetail attestation = attestations.insertWorkflow(workerId, employer.employerId(), "otp-hash", "CANARY-JOB wiring", 12, true, LocalDate.of(2026, 10, 2), "ip", "dev", 5, 4, 5);
        attestations.decide(attestation.attestationId(), "APPROVED", new BigDecimal("0.1"));
        assertTrue(attestations.disputeByWorker(attestation.attestationId(), workerId, "CANARY-DISPUTE I never worked there", 30, 3));
        flags.insertWithFeatures(attestation.attestationId(), new BigDecimal("0.9"), "{\"CANARY-FEATURES\":9.0}");
        credentials.insertIssued(workerId, "{\"CANARY-PAYLOAD\":true}", "signature", LocalDateTime.now().plusDays(30), "key-1", UUID.randomUUID());
        audit.insert(new AuditLog(null, "CANARY_EVENT", "worker:" + workerId, "CANARY-AUDIT-DETAILS", null));
        return workerId;
    }

    private String dumpOf(String... tables) throws Exception {
        StringBuilder dump = new StringBuilder();
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            for (String table : tables) {
                try (ResultSet r = s.executeQuery("SELECT t::text FROM " + table + " t")) {
                    while (r.next()) dump.append(r.getString(1)).append('\n');
                }
            }
        }
        return dump.toString();
    }

    private long count(String sql) throws Exception {
        try (Connection c = provider.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ what is stored

    @Test
    void aCopyOfTheDatabaseHoldsNoneOfTheTextTheScoresOrTheNamesAndEverythingReadsBack() throws Exception {
        long workerId = populate();
        String dump = dumpOf("workers", "employers", "assessments", "attestations", "fraud_flags", "credentials", "audit_log");
        for (String canary : CANARIES) assertFalse(dump.contains(canary), "plaintext found in a copy of the database: " + canary);
        assertTrue(dump.contains("enc:v1:k1:"));

        Worker worker = workers.findById(workerId).orElseThrow();
        assertEquals("Zarina Canary", worker.name());
        assertEquals(0, new BigDecimal("81.30").compareTo(worker.skillScore()));
        assertEquals(0, new BigDecimal("77.25").compareTo(worker.trustScore()));
        WorkerDao.StoredProfile profile = workers.findSkillProfile(workerId).orElseThrow();
        assertEquals("BORDERLINE", profile.band());
        assertTrue(profile.nearBoundary());
        assertEquals("{\"note\":\"CANARY-BREAKDOWN\"}", profile.breakdownJson());
        assertEquals("EXCELLENT", workers.findTrustProfile(workerId).orElseThrow().band());
        AuditLog row = audit.findByType("CANARY_EVENT", 1).get(0);
        assertEquals("CANARY-AUDIT-DETAILS", row.details());
        assertEquals("CANARY_EVENT", row.eventType(), "the type stays plain so the Security tab can count it");
    }

    @Test
    void whatStaysPlainIsExactlyWhatIsFilteredOnAndNothingElseIsLeftBehind() throws Exception {
        populate();
        assertEquals(1, count("SELECT count(*) FROM workers WHERE trade = 'electrician' AND language = 'hi'"), "trade and language stay plain");
        assertEquals(1, count("SELECT count(*) FROM attestations WHERE status = 'APPROVED' AND worker_response = 'DISPUTED'"), "workflow status stays plain");
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE event_type = 'CANARY_EVENT'"));
        assertEquals(0, count("SELECT count(*) FROM workers WHERE name NOT LIKE 'enc:v1:%' OR skill_score NOT LIKE 'enc:v1:%' OR trust_score NOT LIKE 'enc:v1:%'"));
        assertEquals(0, count("SELECT count(*) FROM attestations WHERE job_role NOT LIKE 'enc:v1:%' OR rating_quality NOT LIKE 'enc:v1:%' OR dispute_reason NOT LIKE 'enc:v1:%' "
                + "OR work_date NOT LIKE 'enc:v1:%' OR would_hire_again NOT LIKE 'enc:v1:%' OR duration_days NOT LIKE 'enc:v1:%'"));
    }

    @Test
    void theOperatorsRegistryUsesTheSameLabelsAsTheDataAccessClasses() {
        populate();
        assertEquals(0, tool("k1:" + K1, true).run(new String[]{"check"}), "check opens every sealed value with the registry's labels: " + out + err);
        String report = out.toString();
        for (SealedColumns.Column column : SealedColumns.ALL) assertTrue(report.contains(column.purpose()), column.purpose() + " is in the report");
        assertTrue(report.contains("ok: everything that should be encrypted is, and opens"), report);
    }

    @Test
    void everyColumnTheMigrationTurnedIntoTextIsInTheRegistry() throws Exception {
        List<String> registered = SealedColumns.ALL.stream().map(SealedColumns.Column::purpose).toList();
        for (String expected : new String[]{"workers.name", "workers.skill_score", "workers.trust_score", "workers.skill_band", "workers.trust_band", "workers.skill_breakdown", "workers.trust_report",
                "workers.band_near_boundary", "employers.name", "assessments.transcript", "assessments.score", "assessments.feedback", "assessments.review_reason", "attestations.job_role",
                "attestations.duration_days", "attestations.would_hire_again", "attestations.work_date", "attestations.rating_quality", "attestations.rating_punctuality",
                "attestations.rating_conduct", "attestations.dispute_reason", "fraud_flags.features", "credentials.signed_payload"}) {
            assertTrue(registered.contains(expected), expected);
            String[] parts = expected.split("\\.");
            assertEquals("text", dataType(parts[0], parts[1]), expected + " must be able to hold a sealed value");
        }
    }

    private String dataType(String table, String column) throws Exception {
        try (Connection c = provider.getConnection(); var s = c.prepareStatement("SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            s.setString(1, table);
            s.setString(2, column);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ tampering

    @Test
    void aCiphertextCopiedToAnotherRowOrColumnIsRefusedNotBelieved() throws Exception {
        long victim = populate();
        Worker other = workers.insertProtected(new Worker(null, "Other Worker", "h1:other", "mason", "hi", new BigDecimal("40"), new BigDecimal("20"), null), new byte[]{3}, "v1");
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            // an attacker who can write the database but has no key copies the victim's sealed trust score onto another worker
            s.execute("UPDATE workers SET trust_score = (SELECT trust_score FROM workers WHERE worker_id = " + victim + ") WHERE worker_id = " + other.workerId());
        }
        assertThrows(SealedDataException.class, () -> workers.findById(other.workerId()));
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE workers SET trust_score = skill_score WHERE worker_id = " + victim);            // and into a different column of the same row
        }
        assertThrows(SealedDataException.class, () -> workers.findById(victim));
    }

    @Test
    void anAlteredCiphertextIsRefused() throws Exception {
        long workerId = populate();
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE assessments SET transcript = substr(transcript, 1, length(transcript) - 4) || 'AAAA' WHERE worker_id = " + workerId);
        }
        assertThrows(SealedDataException.class, () -> assessments.findAttempt(workerId, latestAttempt(workerId)));
    }

    private UUID latestAttempt(long workerId) {
        return assessments.findLatestAttemptId(workerId).orElseThrow();
    }

    @Test
    void plaintextWhereSealingIsRequiredIsRefusedAndNeverPutInTheMessage() throws Exception {
        long workerId = populate();
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE workers SET name = 'Plain Name Planted' WHERE worker_id = " + workerId);
        }
        SealedDataException refused = assertThrows(SealedDataException.class, () -> workers.findById(workerId));
        assertFalse(refused.getMessage().contains("Plain Name Planted"));
    }

    // ------------------------------------------------------------------------------------------------------------------ the tool

    /** Rows as an older version, before encryption was switched on, would have left them: plain text in the (now text) columns. */
    private long legacyRows() throws Exception {
        long workerId;
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO workers (name, phone, trade, language, skill_score, trust_score, skill_band, skill_breakdown, trust_report, band_near_boundary) "
                    + "VALUES ('Legacy Lata', 'h1:legacy', 'plumber', 'hi', '64.50', '58.00', 'BORDERLINE', '{\"a\":1}', '{\"tips\":[]}', 'true')");
            try (ResultSet r = s.executeQuery("SELECT worker_id FROM workers WHERE phone = 'h1:legacy'")) {
                r.next();
                workerId = r.getLong(1);
            }
            s.execute("INSERT INTO employers (name, phone) VALUES ('Legacy Traders', 'h1:legacy-e')");
            s.execute("INSERT INTO assessments (worker_id, trade, transcript, score, feedback) VALUES (" + workerId + ", 'plumber', 'legacy transcript words', '64.50', 'legacy feedback')");
            s.execute("INSERT INTO credentials (worker_id, signed_payload, signature) VALUES (" + workerId + ", '{\"legacy\":true}', 'sig')");
        }
        return workerId;
    }

    @Test
    void theToolFindsPlainValuesAndEncryptsThemWithoutChangingTheirMeaning() throws Exception {
        long workerId = legacyRows();
        assertEquals(1, tool("k1:" + K1, false).run(new String[]{"check"}), "plain values make check fail");
        assertTrue(err.toString().contains("plain or cannot be opened"), err.toString());

        // the application can still read them while they are plain (not strict), and counts that it did
        DataCipher lenient = cipher("k1:" + K1, DataCipher.DATA, false);
        useCipher(lenient);
        assertEquals("Legacy Lata", workers.findById(workerId).orElseThrow().name());
        assertTrue(lenient.unsealedReads() > 0);
        assertThrows(SealedDataException.class, () -> new WorkerDao(provider, cipher("k1:" + K1, DataCipher.DATA, true)).findById(workerId), "a strict server refuses them");

        out.reset();
        err.reset();
        assertEquals(0, tool("k1:" + K1, false).run(new String[]{"seal"}), err.toString());
        assertEquals(0, count("SELECT count(*) FROM workers WHERE name NOT LIKE 'enc:v1:%' OR skill_score NOT LIKE 'enc:v1:%' OR band_near_boundary NOT LIKE 'enc:v1:%'"));
        assertEquals(0, count("SELECT count(*) FROM assessments WHERE transcript NOT LIKE 'enc:v1:%' OR score NOT LIKE 'enc:v1:%'"));
        assertEquals(0, new SealAtRestCli(provider, cipher("k1:" + K1, DataCipher.DATA, true), cipher("k1:" + K1, DataCipher.FILES, true), null, exports, new PrintStream(out), new PrintStream(err))
                .run(new String[]{"check"}), "check passes in strict mode once everything is sealed");

        useCipher(cipher("k1:" + K1, DataCipher.DATA, true));
        Worker worker = workers.findById(workerId).orElseThrow();
        assertEquals("Legacy Lata", worker.name());
        assertEquals(0, new BigDecimal("64.50").compareTo(worker.skillScore()));
        assertTrue(workers.findSkillProfile(workerId).orElseThrow().nearBoundary());
        long assessmentId = count("SELECT assessment_id FROM assessments WHERE worker_id = " + workerId);
        assertEquals("legacy transcript words", assessments.findById(assessmentId).orElseThrow().transcript());
        assertEquals(0, new BigDecimal("64.50").compareTo(assessments.findById(assessmentId).orElseThrow().score()));
    }

    @Test
    void sealingTwiceChangesNothingTheSecondTime() throws Exception {
        legacyRows();
        tool("k1:" + K1, false).run(new String[]{"seal"});
        String before = dumpOf("workers", "employers", "assessments", "credentials");
        out.reset();
        assertEquals(0, tool("k1:" + K1, false).run(new String[]{"seal"}));
        assertEquals(before, dumpOf("workers", "employers", "assessments", "credentials"), "an already sealed value is not sealed again");
        assertTrue(out.toString().contains("nothing to do"), out.toString());
    }

    @Test
    void rotatingTheKeyReEncryptsEverythingAndTheOldKeyCanThenBeDropped() throws Exception {
        long workerId = populate();
        String ring = "k2:" + K2 + ",k1:" + K1;
        assertEquals(0, tool(ring, false).run(new String[]{"check"}), "values under the older key are fine, but reported");
        assertTrue(out.toString().contains("still sealed with an older key"), out.toString());
        out.reset();
        assertEquals(0, tool(ring, false).run(new String[]{"seal"}), err.toString());
        assertEquals(0, count("SELECT count(*) FROM workers WHERE name NOT LIKE 'enc:v1:k2:%'"), "everything now carries the new key's id");
        assertEquals(0, count("SELECT count(*) FROM attestations WHERE job_role NOT LIKE 'enc:v1:k2:%' OR dispute_reason NOT LIKE 'enc:v1:k2:%'"));

        useCipher(cipher("k2:" + K2, DataCipher.DATA, true));                          // the old key is gone from the ring
        assertEquals("Zarina Canary", workers.findById(workerId).orElseThrow().name());
        out.reset();
        assertEquals(0, tool("k2:" + K2, false).run(new String[]{"check"}), out + " " + err);
        assertFalse(out.toString().contains("older key:"), out.toString());
    }

    @Test
    void droppingTheOldKeyBeforeReEncryptingMakesValuesUnreadableAndCheckSaysSo() throws Exception {
        populate();
        assertEquals(1, tool("k2:" + K2, false).run(new String[]{"check"}));
        assertTrue(err.toString().contains("cannot be opened"), err.toString());
    }

    @Test
    void exportedCredentialFilesAreEncryptedByTheTool() throws Exception {
        String reference = UUID.randomUUID().toString();
        Files.writeString(exports.resolve(reference + ".json"), "{\"credential\":{\"score\":81.30}}");
        assertEquals(1, tool("k1:" + K1, false).run(new String[]{"check"}));
        out.reset();
        assertEquals(0, tool("k1:" + K1, false).run(new String[]{"seal"}));
        String stored = Files.readString(exports.resolve(reference + ".json"));
        assertTrue(stored.startsWith("enc:v1:k1:") && !stored.contains("81.30"), stored);
        assertEquals("{\"credential\":{\"score\":81.30}}", cipher("k1:" + K1, DataCipher.FILES, false).open("credential.export", reference, stored));
        assertThrows(SealedDataException.class, () -> cipher("k1:" + K1, DataCipher.FILES, false).open("credential.export", UUID.randomUUID().toString(), stored), "a file renamed to another reference does not open");
    }

    // ------------------------------------------------------------------------------------------------------------------ the audit table

    @Test
    void auditDetailsAreSealedInTheTableAndTheDatabasesOwnHashChainStillVerifiesOverTheCiphertext() throws Exception {
        for (int i = 0; i < 5; i++) audit.insert(new AuditLog(null, "TEST_EVENT", "worker:" + i, "details number " + i + " CANARY-AUDIT-DETAILS", null));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE details NOT LIKE 'enc:v1:%'"));
        assertFalse(dumpOf("audit_log").contains("CANARY-AUDIT-DETAILS"));
        var chain = audit.verifyChain();
        assertTrue(chain.valid(), "the chain is computed by the database over what is stored, so it needs no key");
        assertEquals("details number 3 CANARY-AUDIT-DETAILS", audit.findByType("TEST_EVENT", 10).stream().filter(r -> r.actor().equals("worker:3")).findFirst().orElseThrow().details());
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            s.execute("UPDATE audit_log SET event_type = 'FORGED' WHERE actor = 'worker:2'");
            s.execute("ALTER TABLE audit_log ENABLE TRIGGER trg_audit_log_no_update");
        }
        assertFalse(audit.verifyChain().valid(), "editing a row is still detected");
    }

    @Test
    void anAuditDetailMovedUnderAnotherEventTypeIsRefused() throws Exception {
        audit.insert(new AuditLog(null, "BORING_EVENT", "worker:1", "harmless", null));
        audit.insert(new AuditLog(null, "SERIOUS_EVENT", "worker:1", "serious details", null));
        // moving a sealed detail between events cannot be done through the append-only table, so this checks the binding with the cipher itself
        DataCipher auditCipher = cipher("k1:" + K1, DataCipher.AUDIT, false);
        String sealed = auditCipher.seal("audit_log.details", "BORING_EVENT|worker:1", "harmless");
        assertThrows(SealedDataException.class, () -> auditCipher.open("audit_log.details", "SERIOUS_EVENT|worker:1", sealed));
        assertEquals(2, audit.recent(List.of("BORING_EVENT", "SERIOUS_EVENT"), 10).size());
    }

    @Test
    void theToolReportsTheLegacyPlainAuditRowsItCannotChange() throws Exception {
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("INSERT INTO audit_log (event_type, actor, details) VALUES ('OLD_EVENT', 'worker:1', 'written before sealing')");
        }
        audit.insert(new AuditLog(null, "NEW_EVENT", "worker:1", "written after sealing", null));
        assertEquals(0, tool("k1:" + K1, false).run(new String[]{"check"}), "legacy audit rows are reported but are not a failure: the table is append-only");
        assertTrue(out.toString().contains("1 legacy") && out.toString().contains("append-only"), out.toString());
        assertEquals("written before sealing", audit.findByType("OLD_EVENT", 1).get(0).details(), "and they still read");
    }

    @Test
    void theUsageMessageIsShownForAnythingElse() {
        assertEquals(2, tool("k1:" + K1, false).run(new String[]{}));
        assertEquals(2, tool("k1:" + K1, false).run(new String[]{"drop"}));
        assertEquals(2, tool("k1:" + K1, false).run(new String[]{"seal", "--force"}));
    }
}
