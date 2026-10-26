package com.kaarigarsetu.dao;

import com.kaarigarsetu.support.TestCiphers;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.model.Assessment;
import com.kaarigarsetu.model.Attestation;
import com.kaarigarsetu.model.AuditLog;
import com.kaarigarsetu.model.Credential;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.FraudFlag;
import com.kaarigarsetu.model.TrustEdge;
import com.kaarigarsetu.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.stream.Stream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a PostgreSQL Testcontainer by default. Machines without Docker can instead point
 * TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD at a throwaway database whose name ends in "_test"
 * (the test wipes the public schema, so it refuses any other name).
 */
class DaoIntegrationTest {
    private static final String INJECTION = "' OR '1'='1";
    private static final String LOCAL_URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() {
        if (LOCAL_URL == null) {
            postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("kaarigar_setu_test").withUsername("test").withPassword("test");
            postgres.start();
        } else if (!LOCAL_URL.endsWith("_test")) {
            throw new IllegalStateException("TEST_DB_URL must name a database ending in _test");
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private final ConnectionProvider connectionProvider = () -> LOCAL_URL != null
            ? DriverManager.getConnection(LOCAL_URL, System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")),
                    System.getenv().getOrDefault("TEST_DB_PASSWORD", ""))
            : DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

    private WorkerDao workerDao;
    private EmployerDao employerDao;
    private AssessmentDao assessmentDao;
    private AttestationDao attestationDao;
    private TrustEdgeDao trustEdgeDao;
    private CredentialDao credentialDao;
    private FraudFlagDao fraudFlagDao;
    private AuditLogDao auditLogDao;

    @BeforeEach
    void setUp() throws Exception {
        try (var connection = connectionProvider.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            Path databaseDir = Path.of("..", "database");
            List<Path> scripts = new java.util.ArrayList<>(List.of(databaseDir.resolve("schema.sql")));
            try (Stream<Path> migrations = Files.list(databaseDir.resolve("migrations"))) {
                migrations.filter(f -> f.getFileName().toString().endsWith(".sql")).sorted().forEach(scripts::add);
            }
            for (Path script : scripts) {
                statement.execute(Files.readString(script));
            }
        }

        workerDao = new WorkerDao(connectionProvider, TestCiphers.data());
        employerDao = new EmployerDao(connectionProvider, TestCiphers.data());
        assessmentDao = new AssessmentDao(connectionProvider, TestCiphers.data());
        attestationDao = new AttestationDao(connectionProvider, TestCiphers.data());
        trustEdgeDao = new TrustEdgeDao(connectionProvider);
        credentialDao = new CredentialDao(connectionProvider, TestCiphers.data());
        fraudFlagDao = new FraudFlagDao(connectionProvider, TestCiphers.data());
        auditLogDao = new AuditLogDao(connectionProvider, TestCiphers.audit());
    }

    @Test
    void workerDaoInsertsFindsUpdatesAndHandlesMissingWorker() {
        Worker worker = insertWorker("Asha", "+9101");

        assertEquals("Asha", workerDao.findById(worker.workerId()).orElseThrow().name());
        assertTrue(workerDao.updateScores(worker.workerId(), new BigDecimal("90.00"), new BigDecimal("80.00")));
        assertEquals(0, new BigDecimal("90.00").compareTo(workerDao.findById(worker.workerId()).orElseThrow().skillScore()));
        assertFalse(workerDao.findById(99999L).isPresent());
        assertFalse(workerDao.updateScores(99999L, BigDecimal.ONE, BigDecimal.ONE));
    }

    @Test
    void employerDaoInsertsAndFindsEmployer() {
        Employer employer = insertEmployer("Demo Employer", "+9191");

        assertEquals("Demo Employer", employerDao.findById(employer.employerId()).orElseThrow().name());
        assertTrue(employer.verified());
    }

    @Test
    void assessmentDaoInsertsAndRetrievesAssessment() {
        Worker worker = insertWorker("Ravi", "+9102");

        Assessment assessment = assessmentDao.insert(new Assessment(null, worker.workerId(), "Carpentry",
                "clean transcript", new BigDecimal("77.50"), "Good work", null));

        assertEquals("Good work", assessmentDao.findById(assessment.assessmentId()).orElseThrow().feedback());
    }

    @Test
    void attestationDaoInsertsRetrievesAndUpdatesStatus() {
        Worker worker = insertWorker("Meena", "+9103");
        Employer employer = insertEmployer("Verified Employer", "+9192");

        Attestation attestation = insertAttestation(worker.workerId(), employer.employerId(), "safe-hash");

        assertEquals("PENDING", attestationDao.findById(attestation.attestationId()).orElseThrow().status());
        assertTrue(attestationDao.updateStatus(attestation.attestationId(), "APPROVED"));
        assertEquals("APPROVED", attestationDao.findById(attestation.attestationId()).orElseThrow().status());
    }

    @Test
    void trustEdgeDaoInsertsAndRetrievesEdges() {
        Worker source = insertWorker("Source", "+9104");
        Worker target = insertWorker("Target", "+9105");

        TrustEdge edge = trustEdgeDao.insert(new TrustEdge(null, source.workerId(), target.workerId(),
                new BigDecimal("0.8000"), true));

        assertNotNull(trustEdgeDao.findById(edge.edgeId()).orElseThrow());
        assertEquals(1, trustEdgeDao.findBySourceId(source.workerId()).size());
    }

    @Test
    void credentialDaoInsertsAndRetrievesCredential() {
        Worker worker = insertWorker("Credential Worker", "+9106");

        Credential credential = credentialDao.insert(new Credential(null, worker.workerId(),
                "demo payload", "demo signature", null));

        assertEquals("demo signature", credentialDao.findById(credential.credentialId()).orElseThrow().signature());
    }

    @Test
    void fraudFlagDaoInsertsRetrievesAndUpdatesReviewerDecision() {
        Worker worker = insertWorker("Flag Worker", "+9107");
        Employer employer = insertEmployer("Flag Employer", "+9193");
        Attestation attestation = insertAttestation(worker.workerId(), employer.employerId(), "flag-hash");

        FraudFlag flag = fraudFlagDao.insert(new FraudFlag(null, attestation.attestationId(),
                new BigDecimal("0.9500"), "PENDING", null, null));

        assertEquals(1, fraudFlagDao.findFlaggedRecords().size());
        assertTrue(fraudFlagDao.updateReviewerDecision(flag.flagId(), "VALID", "reviewer", LocalDateTime.now()));
        assertEquals("VALID", fraudFlagDao.findById(flag.flagId()).orElseThrow().reviewerDecision());
    }

    @Test
    void auditLogDaoInsertsAndRetrievesLog() {
        AuditLog auditLog = auditLogDao.insert(new AuditLog(null, "WORKER_CREATED", "system",
                "created demo worker", null));

        assertEquals("WORKER_CREATED", auditLogDao.findById(auditLog.logId()).orElseThrow().eventType());
    }

    @Test
    void maliciousLookingInputIsStoredAndQueriedAsLiteralValues() {
        Worker worker = workerDao.insert(new Worker(null, INJECTION, INJECTION, "Masonry", "Hindi",
                BigDecimal.ZERO, BigDecimal.ZERO, null));
        Employer employer = employerDao.insert(new Employer(null, INJECTION, "+91-injection", false, null));
        Assessment assessment = assessmentDao.insert(new Assessment(null, worker.workerId(), "Masonry",
                INJECTION, BigDecimal.ONE, INJECTION, null));
        Attestation attestation = attestationDao.insert(new Attestation(null, worker.workerId(), employer.employerId(),
                INJECTION, "PENDING", BigDecimal.ZERO, null));
        Credential credential = credentialDao.insert(new Credential(null, worker.workerId(), INJECTION, INJECTION, null));
        Worker target = insertWorker("Injection Target", "+91-target");
        trustEdgeDao.insert(new TrustEdge(null, worker.workerId(), target.workerId(), BigDecimal.ONE, true));
        fraudFlagDao.insert(new FraudFlag(null, attestation.attestationId(), BigDecimal.ONE, "PENDING", null, null));
        AuditLog auditLog = auditLogDao.insert(new AuditLog(null, "TEST", INJECTION, INJECTION, null));

        assertEquals(worker.workerId(), workerDao.findByPhone(INJECTION).orElseThrow().workerId());
        assertEquals(employer.employerId(), employerDao.findByPhone("+91-injection").orElseThrow().employerId());
        // sealed columns cannot be searched by value (every sealing differs): the hostile text is proved to be stored and returned as data instead
        assertEquals(INJECTION, assessmentDao.findById(assessment.assessmentId()).orElseThrow().transcript());
        assertEquals(INJECTION, assessmentDao.findById(assessment.assessmentId()).orElseThrow().feedback());
        assertEquals(attestation.attestationId(), attestationDao.findByOtpHash(INJECTION).orElseThrow().attestationId());
        assertTrue(trustEdgeDao.findBySourceIdText(INJECTION).isEmpty());
        assertEquals(INJECTION, credentialDao.findById(credential.credentialId()).orElseThrow().signedPayload());
        assertEquals(INJECTION, workerDao.findById(worker.workerId()).orElseThrow().name());
        assertTrue(fraudFlagDao.findByReviewerDecision(INJECTION).isEmpty());
        assertEquals(auditLog.logId(), auditLogDao.findByActor(INJECTION).orElseThrow().logId());
    }

    @Test
    void databaseConstraintsAreConvertedToDataAccessException() {
        assertThrows(DataAccessException.class, () -> assessmentDao.insert(new Assessment(null, 404L,
                "Unknown", "bad foreign key", BigDecimal.ONE, null, null)));

        assertThrows(DataAccessException.class, () -> workerDao.insert(new Worker(null, null,
                "+9108", "Plumbing", "Hindi", BigDecimal.ZERO, BigDecimal.ZERO, null)));

        insertWorker("Unique Phone", "+9109");
        assertThrows(DataAccessException.class, () -> insertWorker("Duplicate Phone", "+9109"));
    }

    private Worker insertWorker(String name, String phone) {
        return workerDao.insert(new Worker(null, name, phone, "Tailoring", "Hindi",
                new BigDecimal("50.00"), new BigDecimal("40.00"), null));
    }

    private Employer insertEmployer(String name, String phone) {
        return employerDao.insert(new Employer(null, name, phone, true, null));
    }

    private Attestation insertAttestation(long workerId, long employerId, String otpHash) {
        return attestationDao.insert(new Attestation(null, workerId, employerId, otpHash,
                "PENDING", new BigDecimal("0.1000"), null));
    }
}
