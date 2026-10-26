package com.kaarigarsetu.privacy;

import com.kaarigarsetu.support.TestCiphers;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.OtpChallengeDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.dao.TrustedDeviceDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DPDP erasure, checked in the database itself: what is gone, what must remain, and that nothing else is touched. */
class ErasureIntegrationTest {
    private static final String URL = System.getenv("TEST_DB_URL");

    @TempDir Path exports;
    private final ConnectionProvider provider = () -> DriverManager.getConnection(URL,
            System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    private WorkerDao workers;
    private EmployerDao employers;
    private AttestationDao attestations;
    private CredentialDao credentials;
    private TrustEdgeDao edges;
    private OtpChallengeDao otps;
    private TrustedDeviceDao devices;
    private AssessmentDao assessments;
    private FraudFlagDao flags;
    private final TrustScoreService trust = mock(TrustScoreService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private ErasureService erasure;

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
        workers = new WorkerDao(provider, TestCiphers.data());
        employers = new EmployerDao(provider, TestCiphers.data());
        attestations = new AttestationDao(provider, TestCiphers.data());
        credentials = new CredentialDao(provider, TestCiphers.data());
        edges = new TrustEdgeDao(provider);
        otps = new OtpChallengeDao(provider);
        devices = new TrustedDeviceDao(provider);
        assessments = new AssessmentDao(provider, TestCiphers.data());
        flags = new FraudFlagDao(provider, TestCiphers.data());
        erasure = new ErasureService(workers, employers, attestations, credentials, edges, otps, devices, trust, audit, Transactions.none(), exports);
    }

    private Worker worker(String key) {
        return workers.insertProtected(new Worker(null, "Real Name " + key, key, "electrician", "hi", new BigDecimal("80"), new BigDecimal("50"), null), new byte[]{1, 2, 3}, "v1");
    }

    private Employer employer(String key) {
        return employers.insertProtected(new Employer(null, "Real Employer " + key, key, false, null), new byte[]{4, 5}, "v1");
    }

    private int count(String sql) throws Exception {
        try (Connection c = provider.getConnection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void erasingAWorkerRemovesEverythingAboutThemAndLeavesOthersUntouched() throws Exception {
        Worker target = worker("h1:target"), bystander = worker("h1:other");
        Employer employer = employer("h1:emp");
        UUID verifiedAnswerAttempt = UUID.randomUUID();
        long targetAnswer = assessments.insertAnswer(new AssessmentAnswer(null, target.workerId(), "electrician", verifiedAnswerAttempt, 1, "hi", "my voice transcript", new BigDecimal("70"), "f", false, null, "stub", null));
        long bystanderAnswer = assessments.insertAnswer(new AssessmentAnswer(null, bystander.workerId(), "electrician", UUID.randomUUID(), 1, "hi", "someone else", new BigDecimal("70"), "f", false, null, "stub", null));
        com.kaarigarsetu.dao.ReplayDao replayDao = new com.kaarigarsetu.dao.ReplayDao(provider);
        replayDao.store(targetAnswer, new long[]{1, 2, 3}, new int[]{0, 1, 2}, new long[]{11, 12});
        replayDao.store(bystanderAnswer, new long[]{4, 5}, new int[]{0, 1}, new long[]{13});
        AttestationDetail about = attestations.insertWorkflow(target.workerId(), employer.employerId(), "o", "wiring", 3, true, LocalDate.now(), "ip", "dev");
        attestations.decide(about.attestationId(), "PENDING_REVIEW", new BigDecimal("0.9"));
        flags.insertWithFeatures(about.attestationId(), new BigDecimal("0.9"), "{}");
        AttestationDetail keep = attestations.insertWorkflow(bystander.workerId(), employer.employerId(), "o2", "wiring", 3, true, LocalDate.now(), "ip", "dev");
        edges.insertAttestationEdge(employer.employerId(), target.workerId(), BigDecimal.ONE, about.attestationId());
        edges.insertAttestationEdge(employer.employerId(), bystander.workerId(), BigDecimal.ONE, keep.attestationId());
        UUID ref = UUID.randomUUID();
        credentials.insertIssued(target.workerId(), "{}", "sig", LocalDateTime.now().plusDays(1), "kid", ref);
        Path exported = Files.writeString(exports.resolve(ref + ".json"), "{\"credential\":{}}");
        otps.save("login:WORKER:h1:target", "s$h", Instant.now().plusSeconds(60), "ip");
        otps.save("login:WORKER:h1:other", "s$h", Instant.now().plusSeconds(60), "ip");
        otps.save("login:WORKER:h1:target:d41", "s$h", Instant.now().plusSeconds(60), "ip");          // a trusted device's code
        otps.save("login:WORKER:h1:target:bAbC_dEf-123", "s$h", Instant.now().plusSeconds(60), "ip");  // a bound code: the key contains an underscore (a LIKE wildcard)
        otps.save("login:WORKER:h1:other:d42", "s$h", Instant.now().plusSeconds(60), "ip");
        devices.insert("WORKER", target.workerId(), "token-hash-target", 5);
        devices.insert("WORKER", bystander.workerId(), "token-hash-other", 5);

        erasure.eraseWorker(target.workerId(), "ERASE MY DATA");

        assertTrue(workers.findById(target.workerId()).isEmpty());
        assertTrue(workers.findByPhone("h1:target").isEmpty());
        assertEquals(0, count("SELECT count(*) FROM assessments WHERE worker_id = " + target.workerId()));
        assertEquals(0, count("SELECT count(*) FROM answer_fingerprints WHERE assessment_id = " + targetAnswer), "the recording's fingerprints go with the answer");
        assertEquals(0, count("SELECT count(*) FROM answer_shingles WHERE assessment_id = " + targetAnswer), "and so do the word fingerprints");
        assertEquals(2, count("SELECT count(*) FROM answer_fingerprints WHERE assessment_id = " + bystanderAnswer), "another worker's are untouched");
        assertEquals(1, count("SELECT count(*) FROM answer_shingles WHERE assessment_id = " + bystanderAnswer));
        assertEquals(0, count("SELECT count(*) FROM attestations WHERE worker_id = " + target.workerId()));
        assertEquals(0, count("SELECT count(*) FROM fraud_flags"), "flags on the erased worker's attestations go too");
        assertEquals(0, count("SELECT count(*) FROM credentials WHERE worker_id = " + target.workerId()));
        assertEquals(0, count("SELECT count(*) FROM trust_edges WHERE target_id = " + target.workerId()));
        assertEquals(0, count("SELECT count(*) FROM otp_challenges WHERE challenge_key LIKE 'login:WORKER:h1:target%'"), "the codes of every device go, not only the bare one");
        assertEquals(0, count("SELECT count(*) FROM trusted_devices WHERE account_id = " + target.workerId()), "the remembered browsers go");
        assertFalse(Files.exists(exported), "the exported credential file is deleted from disk");

        assertTrue(workers.findById(bystander.workerId()).isPresent());
        assertEquals(1, count("SELECT count(*) FROM assessments WHERE worker_id = " + bystander.workerId()));
        assertEquals(1, count("SELECT count(*) FROM trust_edges"), "the other worker's edge survives");
        assertEquals(2, count("SELECT count(*) FROM otp_challenges WHERE challenge_key LIKE 'login:WORKER:h1:other%'"));
        assertEquals(1, count("SELECT count(*) FROM trusted_devices WHERE account_id = " + bystander.workerId()), "another worker's remembered browser stays");
        verify(audit).log(eq("WORKER_ERASED"), eq("worker:" + target.workerId()), contains("credentials=1 files=1"));
        verify(trust).recompute();
    }

    @Test
    void workerOriginatedLegacyEdgesAreRemovedToo() throws Exception {
        Worker a = worker("h1:a"), b = worker("h1:b");
        edges.insert(new com.kaarigarsetu.model.TrustEdge(null, a.workerId(), b.workerId(), BigDecimal.ONE, true));
        erasure.eraseWorker(a.workerId(), "ERASE MY DATA");
        assertEquals(0, count("SELECT count(*) FROM trust_edges"), "the source foreign key was dropped, so the DAO must delete explicitly");
    }

    @Test
    void erasingAnEmployerAnonymisesThemButKeepsTheEndorsementsWorkersEarned() throws Exception {
        Worker w = worker("h1:w");
        Employer e = employer("h1:e");
        Employer unrelated = employer("h1:u");
        AttestationDetail approved = attestations.insertWorkflow(w.workerId(), e.employerId(), "o", "wiring", 3, true, LocalDate.now(), "ip", "dev");
        attestations.decide(approved.attestationId(), "APPROVED", BigDecimal.ZERO);
        Worker w2 = worker("h1:w2");
        AttestationDetail open = attestations.insertWorkflow(w2.workerId(), e.employerId(), "o2", "wiring", 3, true, LocalDate.now(), "ip", "dev");
        otps.save("login:EMPLOYER:h1:e", "s$h", Instant.now().plusSeconds(60), "ip");

        erasure.eraseEmployer(e.employerId(), "ERASE MY DATA");

        Employer after = employers.findById(e.employerId()).orElseThrow();
        assertEquals("Erased employer", after.name());
        assertTrue(after.phone().startsWith("erased:"));
        assertTrue(employers.findPhoneEncrypted(e.employerId()).isEmpty());
        assertTrue(employers.findByPhone("h1:e").isEmpty());
        assertEquals("APPROVED", attestations.findDetail(approved.attestationId()).orElseThrow().status(), "the worker's endorsement stays");
        assertEquals("EXPIRED", attestations.findDetail(open.attestationId()).orElseThrow().status(), "a request still waiting for a code is closed");
        assertEquals(0, count("SELECT count(*) FROM otp_challenges WHERE challenge_key = 'login:EMPLOYER:h1:e'"));
        assertEquals("Real Employer h1:u", employers.findById(unrelated.employerId()).orElseThrow().name());
        assertThrows(NotFoundException.class, () -> erasure.eraseEmployer(e.employerId(), "ERASE MY DATA"), "second erase of the same account");
    }

    @Test
    void theConfirmationPhraseIsRequiredAndNothingHappensWithoutIt() throws Exception {
        Worker w = worker("h1:w");
        for (String bad : new String[]{null, "", "yes", "erase my data", "ERASE"}) {
            assertThrows(ValidationException.class, () -> erasure.eraseWorker(w.workerId(), bad), String.valueOf(bad));
            assertThrows(ValidationException.class, () -> erasure.eraseEmployer(1, bad));
        }
        assertTrue(workers.findById(w.workerId()).isPresent());
        assertDoesNotThrow(() -> erasure.eraseWorker(w.workerId(), "  ERASE MY DATA  "), "surrounding whitespace is tolerated");
        assertThrows(NotFoundException.class, () -> erasure.eraseWorker(w.workerId(), "ERASE MY DATA"));
    }

    @Test
    void aFailingTrustRefreshNeverBlocksErasure() {
        Worker w = worker("h1:w");
        when(trust.recompute()).thenThrow(new IllegalStateException("ml down"));
        assertDoesNotThrow(() -> erasure.eraseWorker(w.workerId(), "ERASE MY DATA"));
        verify(audit).log(eq("TRUST_REFRESH_FAILED"), anyString(), anyString());
        assertTrue(workers.findById(w.workerId()).isEmpty());
    }
}
