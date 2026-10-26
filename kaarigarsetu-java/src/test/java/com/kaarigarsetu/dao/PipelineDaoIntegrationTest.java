package com.kaarigarsetu.dao;

import com.kaarigarsetu.support.TestCiphers;
import com.kaarigarsetu.config.ConnectionProvider;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.CredentialRecord;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.OtpChallengeStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DAO methods added for phases 4-7 (migration 002), against a throwaway PostgreSQL database named *_test
 * (TEST_DB_URL). Skipped when no such database is configured.
 */
class PipelineDaoIntegrationTest {
    private static final String URL = System.getenv("TEST_DB_URL");
    private static final long NO_SUCH = 999_999L;
    private static final String INJECTION = "x'); DROP TABLE workers; --";

    private final ConnectionProvider provider = () -> DriverManager.getConnection(URL,
            System.getenv().getOrDefault("TEST_DB_USER", System.getProperty("user.name")), System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    private WorkerDao workers;
    private EmployerDao employers;
    private AttestationDao attestations;
    private CredentialDao credentials;
    private OtpChallengeDao otps;
    private TrustEdgeDao edges;
    private FraudFlagDao flags;
    private AuditLogDao audit;

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
        otps = new OtpChallengeDao(provider);
        edges = new TrustEdgeDao(provider);
        flags = new FraudFlagDao(provider, TestCiphers.data());
        audit = new AuditLogDao(provider, TestCiphers.audit());
    }

    private Worker worker(String phoneKey) {
        return workers.insertProtected(new Worker(null, "Test Worker", phoneKey, "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null),
                new byte[]{1, 2, 3}, "dpdp-v1");
    }

    private Employer employer(String phoneKey) {
        return employers.insert(new Employer(null, "Test Employer", phoneKey, false, null));
    }

    private AttestationDetail attest(Worker w, Employer e, String otp) {
        return attestations.insertWorkflow(w.workerId(), e.employerId(), otp, "wiring", 10, true, LocalDate.now(), "ip1", "dev1");
    }

    // ------------------------------------------------------------------ skill profile

    @Test
    void theSkillScoreBandAndBreakdownAreStoredTogetherAndReadBack() {
        Worker w = worker("h1:profile");
        assertTrue(workers.updateSkillProfile(w.workerId(), new BigDecimal("72.50"), "PASS", "{\"tools\":80.00,\"planning\":65.00}", true));
        WorkerDao.StoredProfile stored = workers.findSkillProfile(w.workerId()).orElseThrow();
        assertEquals("PASS", stored.band());
        assertTrue(stored.nearBoundary());
        assertTrue(stored.breakdownJson().contains("\"planning\""));
        assertEquals(0, new BigDecimal("72.50").compareTo(workers.findById(w.workerId()).orElseThrow().skillScore()));
        assertTrue(workers.findSkillProfile(NO_SUCH).isEmpty());
        assertFalse(workers.updateSkillProfile(NO_SUCH, BigDecimal.TEN, "PASS", "{}", false));
    }

    @Test
    void aNewWorkerHasNoBandYetAndTheDatabaseRefusesAnInvalidBand() {
        Worker w = worker("h1:noband");
        WorkerDao.StoredProfile fresh = workers.findSkillProfile(w.workerId()).orElseThrow();
        assertNull(fresh.band());
        assertNull(fresh.breakdownJson());
        assertFalse(fresh.nearBoundary());
        assertThrows(DataAccessException.class, () -> workers.updateSkillProfile(w.workerId(), BigDecimal.TEN, "EXCELLENT", "{}", false));
        assertThrows(DataAccessException.class, () -> workers.updateSkillProfile(w.workerId(), BigDecimal.TEN, "PASS' OR '1'='1", "{}", false));
    }

    // ------------------------------------------------------------------ employer feedback and the trust inputs

    private AttestationDetail rated(Worker w, Employer e, String otp, int q, int p, int c, boolean hire) {
        return attestations.insertWorkflow(w.workerId(), e.employerId(), otp, "wiring", 20, hire, LocalDate.now(), "ip", "dev", q, p, c);
    }

    @Test
    void ratingsAreStoredAndReadBackAndTheDatabaseRefusesValuesOutsideOneToFive() {
        Worker w = worker("h1:r1"); Employer e = employer("h1:re1");
        AttestationDetail a = rated(w, e, "o1", 5, 3, 4, true);
        assertEquals(5, a.ratingQuality());
        assertEquals(3, a.ratingPunctuality());
        assertEquals(4, a.ratingConduct());
        Worker w2 = worker("h1:r2");
        AttestationDetail legacy = attestations.insertWorkflow(w2.workerId(), e.employerId(), "o2", "wiring", 1, null, LocalDate.now(), "ip", "dev");
        assertNull(legacy.ratingQuality(), "an attestation without ratings keeps NULLs");
        for (int bad : new int[]{0, 6, -1, 99}) {
            Worker wx = worker("h1:bad" + bad);
            assertThrows(DataAccessException.class, () -> rated(wx, e, "ob" + bad, bad, 3, 3, true), "rating " + bad);
        }
    }

    @Test
    void onlyApprovedAttestationsFeedTheTrustScoreAndTheyArriveOldestFirstWithTheirRatings() {
        Employer e1 = employer("h1:f1"), e2 = employer("h1:f2"), e3 = employer("h1:f3");
        Worker w = worker("h1:fw");
        AttestationDetail approved1 = rated(w, e1, "a1", 5, 4, 3, true);
        AttestationDetail approved2 = rated(w, e2, "a2", 4, 4, 4, false);
        AttestationDetail pending = rated(w, e3, "a3", 1, 1, 1, false);                  // never decided
        attestations.decide(approved1.attestationId(), "APPROVED", new BigDecimal("0.10"));
        attestations.decide(approved2.attestationId(), "APPROVED", new BigDecimal("0.20"));
        List<AttestationDao.FeedbackRow> rows = attestations.findApprovedFeedback();
        assertEquals(2, rows.size());
        assertEquals(approved1.attestationId(), rows.get(0).attestationId());
        assertEquals(5, rows.get(0).quality());
        assertEquals(Boolean.TRUE, rows.get(0).wouldHireAgain());
        assertEquals(0.20, rows.get(1).anomaly(), 1e-9);
        assertEquals(20, rows.get(0).durationDays());
        assertTrue(rows.stream().noneMatch(r -> r.attestationId() == pending.attestationId()));
    }

    @Test
    void employerActivityCountsApprovedConfirmationsAndFraudRulingsAndWorkerIntegrityCountsRulingsAboutThem() {
        Employer e = employer("h1:act");
        Worker w1 = worker("h1:act1"), w2 = worker("h1:act2"), w3 = worker("h1:act3");
        AttestationDetail ok = rated(w1, e, "x1", 5, 5, 5, true);
        AttestationDetail fraud = rated(w2, e, "x2", 5, 5, 5, true);
        AttestationDetail fine2 = rated(w3, e, "x3", 5, 5, 5, true);
        attestations.decide(ok.attestationId(), "APPROVED", BigDecimal.ZERO);
        attestations.decide(fine2.attestationId(), "APPROVED", BigDecimal.ZERO);
        attestations.decide(fraud.attestationId(), "PENDING_REVIEW", new BigDecimal("0.9"));
        var flag = flags.insertWithFeatures(fraud.attestationId(), new BigDecimal("0.9"), "{}");
        flags.decideIfPending(flag.flagId(), "FRAUD", "mod");
        AttestationDao.EmployerActivity activity = attestations.findEmployerActivity().stream().filter(a -> a.employerId() == e.employerId()).findFirst().orElseThrow();
        assertEquals(2, activity.approvedGiven());
        assertEquals(1, activity.fraudUpheld());
        assertEquals(Map.of(w2.workerId(), 1), attestations.findFraudUpheldByWorker());
    }

    @Test
    void theTrustScoreBandAndExplanationAreStoredTogetherAndAnInvalidBandIsRefused() {
        Worker w = worker("h1:trust");
        assertTrue(workers.updateTrustProfile(w.workerId(), new BigDecimal("81.25"), "EXCELLENT", "{\"tips\":[]}"));
        WorkerDao.StoredTrust stored = workers.findTrustProfile(w.workerId()).orElseThrow();
        assertEquals("EXCELLENT", stored.band());
        assertEquals("{\"tips\":[]}", stored.reportJson());
        assertEquals(0, new BigDecimal("81.25").compareTo(workers.findById(w.workerId()).orElseThrow().trustScore()));
        assertThrows(DataAccessException.class, () -> workers.updateTrustProfile(w.workerId(), BigDecimal.TEN, "LEGENDARY", "{}"));
        assertTrue(workers.findTrustProfile(999_999L).isEmpty());
        assertNull(workers.findTrustProfile(worker("h1:trust2").workerId()).orElseThrow().band());
    }

    // ------------------------------------------------------------------ OTP challenges

    @Test
    void otpChallengeLifecycleSavesRetiresCountsAndConsumes() {
        long first = otps.save("login:WORKER:a", "salt$hash1", Instant.now().plusSeconds(300), "ip");
        long second = otps.save("login:WORKER:a", "salt$hash2", Instant.now().plusSeconds(300), "ip");
        assertNotEquals(first, second);
        OtpChallengeStore.Challenge open = otps.findOpen("login:WORKER:a").orElseThrow();
        assertEquals(second, open.id(), "a new code retires the old one");
        assertEquals(0, open.attempts());
        otps.recordFailedAttempt(second);
        otps.recordFailedAttempt(second);
        assertEquals(2, otps.findOpen("login:WORKER:a").orElseThrow().attempts());
        otps.consume(second);
        assertTrue(otps.findOpen("login:WORKER:a").isEmpty());
        assertTrue(otps.findOpen("login:WORKER:other").isEmpty());
    }

    @Test
    void otpKeyIsAlwaysAValueNeverSql() {
        otps.save(INJECTION, "salt$hash", Instant.now().plusSeconds(60), "ip");
        assertTrue(otps.findOpen(INJECTION).isPresent());
        assertTrue(otps.findOpen("' OR '1'='1").isEmpty());
        assertDoesNotThrow(() -> worker("phone-after-injection"), "the workers table still exists");
    }

    // ------------------------------------------------------------------ workers / consent / pseudonym

    @Test
    void registrationStoresConsentEncryptedPhoneAndAPseudonymousSubject() throws Exception {
        Worker w = worker("h1:abc");
        assertArrayEquals(new byte[]{1, 2, 3}, workers.findPhoneEncrypted(w.workerId()).orElseThrow());
        UUID subject = workers.findSubjectId(w.workerId()).orElseThrow();
        assertNotEquals(subject, worker("h1:def").workerId());
        try (Connection c = provider.getConnection(); var s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT consent_at, consent_version FROM workers WHERE worker_id = " + w.workerId())) {
            assertTrue(r.next());
            assertNotNull(r.getTimestamp("consent_at"), "consent timestamp is stored");
            assertEquals("dpdp-v1", r.getString("consent_version"));
        }
        assertThrows(DataAccessException.class, () -> worker("h1:abc"), "phone key is unique");
    }

    @Test
    void injectionInWorkerFieldsIsStoredAsText() {
        Worker w = workers.insertProtected(new Worker(null, INJECTION, "h1:inj", INJECTION, "hi", BigDecimal.ZERO, BigDecimal.ZERO, null), new byte[]{9}, INJECTION);
        assertEquals(INJECTION, workers.findById(w.workerId()).orElseThrow().name());
    }

    // ------------------------------------------------------------------ attestations

    @Test
    void oneLiveAttestationPerPairButARejectedOneMayBeRetried() {
        Worker w = worker("h1:w");
        Employer e = employer("h1:e");
        AttestationDetail a = attest(w, e, "otp-1");
        assertEquals("OTP_PENDING", a.status());
        assertTrue(attestations.existsLive(w.workerId(), e.employerId()));
        assertThrows(DataAccessException.class, () -> attest(w, e, "otp-2"));
        assertTrue(attestations.decide(a.attestationId(), "REJECTED", new BigDecimal("0.1")));
        assertFalse(attestations.existsLive(w.workerId(), e.employerId()));
        assertDoesNotThrow(() -> attest(w, e, "otp-3"));
    }

    @Test
    void decideOnlyMovesAnOtpPendingAttestationExactlyOnce() {
        AttestationDetail a = attest(worker("h1:w"), employer("h1:e"), "otp");
        assertTrue(attestations.decide(a.attestationId(), "APPROVED", new BigDecimal("0.05")));
        assertFalse(attestations.decide(a.attestationId(), "REJECTED", BigDecimal.ZERO), "replayed confirmation is a no-op");
        assertEquals("APPROVED", attestations.findDetail(a.attestationId()).orElseThrow().status());
        assertFalse(attestations.moderate(a.attestationId(), "REJECTED"), "moderation applies only to PENDING_REVIEW");
    }

    @Test
    void moderationOnlyTouchesPendingReview() {
        AttestationDetail a = attest(worker("h1:w"), employer("h1:e"), "otp");
        assertTrue(attestations.decide(a.attestationId(), "PENDING_REVIEW", new BigDecimal("0.9")));
        assertTrue(attestations.moderate(a.attestationId(), "APPROVED"));
        assertFalse(attestations.moderate(a.attestationId(), "REJECTED"));
    }

    @Test
    void anUnknownStatusIsRejectedByTheDatabase() {
        AttestationDetail a = attest(worker("h1:w"), employer("h1:e"), "otp");
        assertThrows(DataAccessException.class, () -> attestations.decide(a.attestationId(), "HACKED", BigDecimal.ZERO));
    }

    @Test
    void networkCountersAreScopedToTheirHashAndWindow() {
        Worker w1 = worker("h1:w1"), w2 = worker("h1:w2");
        Employer e = employer("h1:e");
        attest(w1, e, "o1");
        attestations.insertWorkflow(w2.workerId(), e.employerId(), "o2", "wiring", 1, null, LocalDate.now(), "ip1", "dev2");
        assertEquals(2, attestations.countFromIpSince("ip1", LocalDateTime.now().minusHours(1)));
        assertEquals(1, attestations.countFromDeviceSince("dev2", LocalDateTime.now().minusHours(1)));
        assertEquals(0, attestations.countFromIpSince("ip1", LocalDateTime.now().plusHours(1)));
        assertEquals(0, attestations.countFromIpSince("' OR '1'='1", LocalDateTime.now().minusHours(1)));
    }

    @Test
    void gapBetweenAnEmployersAttestationsIsReportedAndFirstIsEmpty() {
        Employer e = employer("h1:e");
        AttestationDetail first = attest(worker("h1:w1"), e, "o1");
        assertTrue(attestations.minutesSincePreviousByEmployer(e.employerId(), first.attestationId()).isEmpty());
        AttestationDetail second = attest(worker("h1:w2"), e, "o2");
        double gap = attestations.minutesSincePreviousByEmployer(e.employerId(), second.attestationId()).orElseThrow();
        assertTrue(gap >= 0 && gap < 5);
    }

    @Test
    void aMutualVouchingRingHasReciprocityOneAndAnHonestEmployerZero() {
        Worker a = worker("h1:A"), b = worker("h1:B");
        Employer ea = employer("h1:A"), eb = employer("h1:B");   // same person is both worker and employer
        attestations.decide(attest(b, ea, "o1").attestationId(), "APPROVED", BigDecimal.ZERO);
        attestations.decide(attest(a, eb, "o2").attestationId(), "APPROVED", BigDecimal.ZERO);
        assertEquals(1.0, attestations.reciprocityRatio(ea.employerId()), 1e-9);

        Employer honest = employer("h1:H");
        attestations.decide(attest(worker("h1:W3"), honest, "o3").attestationId(), "APPROVED", BigDecimal.ZERO);
        assertEquals(0.0, attestations.reciprocityRatio(honest.employerId()), 1e-9);
        assertEquals(0.0, attestations.reciprocityRatio(999_999L), 1e-9);
    }

    @Test
    void injectionInFeedbackFieldsIsData() {
        AttestationDetail a = attestations.insertWorkflow(worker("h1:w").workerId(), employer("h1:e").employerId(), INJECTION, INJECTION, 1, null,
                LocalDate.now(), INJECTION, INJECTION);
        assertEquals(INJECTION, a.jobRole());
    }

    // ------------------------------------------------------------------ trust edges + fraud flags

    @Test
    void anAttestationProducesExactlyOneEdgeEvenIfReplayed() {
        Worker w = worker("h1:w");
        Employer e = employer("h1:e");
        AttestationDetail a = attest(w, e, "o");
        edges.insertAttestationEdge(e.employerId(), w.workerId(), new BigDecimal("1.0"), a.attestationId());
        edges.insertAttestationEdge(e.employerId(), w.workerId(), new BigDecimal("1.0"), a.attestationId());
        assertEquals(1, edges.findAllVerified().size());
    }

    @Test
    void fraudFlagsAreUniquePerAttestationAndDecidedOnce() {
        AttestationDetail a = attest(worker("h1:w"), employer("h1:e"), "o");
        var flag = flags.insertWithFeatures(a.attestationId(), new BigDecimal("0.93"), "{\"ip_frequency\":9}");
        assertThrows(DataAccessException.class, () -> flags.insertWithFeatures(a.attestationId(), new BigDecimal("0.5"), "{}"));
        assertEquals(1, flags.findPendingQueue().size());
        assertTrue(flags.decideIfPending(flag.flagId(), "FRAUD", "mod-1"));
        assertFalse(flags.decideIfPending(flag.flagId(), "VALID", "mod-2"), "already decided");
        assertTrue(flags.findPendingQueue().isEmpty());
        var again = attest(worker("h1:w9"), employer("h1:e9"), "o9");
        assertThrows(DataAccessException.class, () -> flags.decideIfPending(flags.insertWithFeatures(again.attestationId(), BigDecimal.ONE, "{}").flagId(), "MAYBE", "m"));
    }

    // ------------------------------------------------------------------ credentials

    @Test
    void credentialsSupersedeRevokeAndExpireWithoutLosingHistory() {
        Worker w = worker("h1:w");
        UUID ref1 = UUID.randomUUID(), ref2 = UUID.randomUUID();
        credentials.insertIssued(w.workerId(), "{\"v\":1}", "sig1", LocalDateTime.now().plusYears(1), "kid", ref1);
        assertEquals(1, credentials.revokeAllLiveForWorker(w.workerId(), "superseded"));
        CredentialRecord second = credentials.insertIssued(w.workerId(), "{\"v\":2}", "sig2", LocalDateTime.now().plusYears(1), "kid", ref2);
        assertFalse(second.revoked());
        assertTrue(credentials.findByRef(ref1).orElseThrow().revoked());
        assertEquals(2, credentials.findByWorker(w.workerId()).size());
        assertTrue(credentials.revoke(ref2, "first reason"));
        assertFalse(credentials.revoke(ref2, "second reason"));
        assertEquals("first reason", credentials.findByRef(ref2).orElseThrow().revocationReason());
        assertTrue(credentials.findByRef(UUID.randomUUID()).isEmpty());
    }

    // ------------------------------------------------------------------ audit log

    @Test
    void auditLogIsAppendOnlyAndHashChained() throws Exception {
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "A", "actor", "one", null));
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "B", "actor", "two", null));
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "C", "actor", "three", null));
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            assertTrue(chainValid(s));
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE audit_log SET details = 'forged' WHERE chain_seq = 2"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("DELETE FROM audit_log WHERE chain_seq = 2"));
            assertThrows(java.sql.SQLException.class, () -> s.execute("TRUNCATE audit_log"));
            assertTrue(chainValid(s));

            // The owner CAN switch the triggers off. That is why the app never runs as the owner; the chain still exposes the edit.
            s.execute("ALTER TABLE audit_log DISABLE TRIGGER trg_audit_log_no_update");
            s.execute("UPDATE audit_log SET details = 'forged' WHERE chain_seq = 2");
            try (ResultSet r = s.executeQuery("SELECT valid, first_bad_seq FROM verify_audit_chain()")) {
                assertTrue(r.next());
                assertFalse(r.getBoolean("valid"));
                assertEquals(2, r.getLong("first_bad_seq"));
            }
        }
    }

    // ------------------------------------------------------------------ the worker's say (migration 006)

    private AttestationDetail approved(Worker w, Employer e) {
        AttestationDetail a = attestations.insertWorkflow(w.workerId(), e.employerId(), "o-" + e.employerId(), "wiring", 20, true, LocalDate.now(), "ip", "dev", 5, 5, 5);
        attestations.decide(a.attestationId(), "APPROVED", new BigDecimal("0.05"));
        return a;
    }

    private int scored(Worker w) {
        return (int) attestations.findApprovedFeedback().stream().filter(f -> f.workerId() == w.workerId()).count();
    }

    @Test
    void aDisputeHoldsAConfirmationOutOfEveryCountUntilAModeratorUpholdsIt() {
        Worker w = worker("h1:dispute-w");
        Employer e = employer("h1:dispute-e");
        AttestationDetail a = approved(w, e);
        assertEquals(1, scored(w));
        assertEquals(1, attestations.countApprovedForWorker(w.workerId()));
        assertEquals(1, attestations.findEmployerActivity().stream().filter(x -> x.employerId() == e.employerId()).findFirst().orElseThrow().approvedGiven());

        assertTrue(attestations.disputeByWorker(a.attestationId(), w.workerId(), "I never worked for this person", 30, 3));
        assertEquals(0, scored(w), "held out of the trust score while the dispute is open");
        assertEquals(0, attestations.countApprovedForWorker(w.workerId()), "and out of the credential and passport counts");
        assertEquals(0, attestations.findEmployerActivity().stream().filter(x -> x.employerId() == e.employerId()).findFirst().orElseThrow().approvedGiven(),
                "and out of the employer's track record");
        assertEquals(1, attestations.countOpenDisputes(w.workerId()));
        assertEquals(1, attestations.findOpenDisputes().stream().filter(d -> d.attestationId() == a.attestationId()).count());

        assertEquals(w.workerId(), attestations.decideDispute(a.attestationId(), "UPHELD", "mod-1").orElseThrow());
        assertEquals(1, scored(w), "upheld: the confirmation was fair and counts again");
        assertEquals(0, attestations.countOpenDisputes(w.workerId()));
        assertTrue(attestations.decideDispute(a.attestationId(), "REMOVED", "mod-2").isEmpty(), "a dispute is ruled on once");
    }

    @Test
    void aRemovedConfirmationNeverCountsAgain() {
        Worker w = worker("h1:removed-w");
        AttestationDetail a = approved(w, employer("h1:removed-e"));
        assertTrue(attestations.disputeByWorker(a.attestationId(), w.workerId(), "This rating is false and unfair", 30, 3));
        attestations.decideDispute(a.attestationId(), "REMOVED", "mod-1");
        assertEquals(0, scored(w));
        var shown = attestations.findConfirmationForWorker(w.workerId(), a.attestationId()).orElseThrow();
        assertTrue(shown.heldOut() && "REMOVED".equals(shown.disputeDecision()));
    }

    @Test
    void acceptingChangesNothingAboutScoringAndIsFinal() {
        Worker w = worker("h1:accept-w");
        AttestationDetail a = approved(w, employer("h1:accept-e"));
        assertTrue(attestations.acceptByWorker(a.attestationId(), w.workerId()));
        assertEquals(1, scored(w));
        assertFalse(attestations.acceptByWorker(a.attestationId(), w.workerId()), "answered once");
        assertFalse(attestations.disputeByWorker(a.attestationId(), w.workerId(), "changed my mind about this", 30, 3), "an accepted confirmation cannot be disputed later");
    }

    @Test
    void onlyTheOwnerCanRespondAndOnlyOnApprovedConfirmations() {
        Worker owner = worker("h1:own-w"), other = worker("h1:other-w");
        AttestationDetail approved = approved(owner, employer("h1:own-e"));
        AttestationDetail pending = attest(worker("h1:pend-w"), employer("h1:pend-e"), "o-pend");
        assertFalse(attestations.acceptByWorker(approved.attestationId(), other.workerId()), "somebody else's confirmation");
        assertFalse(attestations.disputeByWorker(approved.attestationId(), other.workerId(), "not mine but I am trying", 30, 3));
        assertTrue(attestations.findConfirmationForWorker(other.workerId(), approved.attestationId()).isEmpty());
        assertFalse(attestations.acceptByWorker(pending.attestationId(), pending.workerId()), "not approved yet");
    }

    @Test
    void theDisputeLimitsAreEnforcedUnderSimultaneousRequests() throws Exception {
        Worker w = worker("h1:race-w");
        int total = 8;
        long[] ids = new long[total];
        for (int i = 0; i < total; i++) ids[i] = approved(w, employer("h1:race-e" + i)).attestationId();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(total);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (long id : ids) {
                results.add(pool.submit(() -> {
                    start.await();
                    return attestations.disputeByWorker(id, w.workerId(), "disputed all at the same moment here", 30, 3);
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            assertEquals(3, accepted, "exactly the limit gets through however the requests interleave");
            assertEquals(3, attestations.countOpenDisputes(w.workerId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theDisputeLimitsAreEnforcedInsideTheSingleUpdate() throws Exception {
        Worker w = worker("h1:limit-w");
        long[] ids = new long[4];
        for (int i = 0; i < 4; i++) ids[i] = approved(w, employer("h1:limit-e" + i)).attestationId();
        for (int i = 0; i < 3; i++) assertTrue(attestations.disputeByWorker(ids[i], w.workerId(), "dispute number " + i + " with a reason", 30, 3));
        assertFalse(attestations.disputeByWorker(ids[3], w.workerId(), "a fourth open dispute is too many", 30, 3));
        attestations.decideDispute(ids[0], "UPHELD", "mod-1");
        assertTrue(attestations.disputeByWorker(ids[3], w.workerId(), "now there is room again for this", 30, 3), "ruling on one frees a slot");
        Worker late = worker("h1:late-w");
        long oldId = approved(late, employer("h1:late-e")).attestationId();
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            s.execute("UPDATE attestations SET decided_at = CURRENT_TIMESTAMP - INTERVAL '40 days' WHERE attestation_id = " + oldId);
        }
        assertFalse(attestations.disputeByWorker(oldId, late.workerId(), "too late to say this now", 30, 3), "outside the 30-day window");
    }

    @Test
    void theDatabaseRefusesAnInconsistentDisputeState() throws Exception {
        Worker w = worker("h1:consistency-w");
        AttestationDetail a = approved(w, employer("h1:consistency-e"));
        try (Connection c = provider.getConnection(); var s = c.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE attestations SET dispute_decision = 'REMOVED' WHERE attestation_id = " + a.attestationId()),
                    "a ruling without a dispute");
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE attestations SET worker_response = 'DISPUTED' WHERE attestation_id = " + a.attestationId()),
                    "a dispute without a reason");
            assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE attestations SET worker_response = 'MAYBE' WHERE attestation_id = " + a.attestationId()));
        }
    }

    @Test
    void employerVettingIsRecordedOnceAndListedForModerators() throws Exception {
        long a = employers.insert(new com.kaarigarsetu.model.Employer(null, "Sharma Contractors", "h1:vet-a", false, null)).employerId();
        long b = employers.insert(new com.kaarigarsetu.model.Employer(null, "Verma Builders", "h1:vet-b", false, null)).employerId();
        assertEquals(2, employers.listForVetting(true, 10).stream().filter(e -> e.employerId() == a || e.employerId() == b).count());
        assertTrue(employers.vet(a, "mod-1"));
        assertTrue(employers.vet(a, "mod-2"), "idempotent");
        var unvetted = employers.listForVetting(true, 10);
        assertTrue(unvetted.stream().noneMatch(e -> e.employerId() == a) && unvetted.stream().anyMatch(e -> e.employerId() == b));
        var everyone = employers.listForVetting(false, 10);
        var first = everyone.stream().filter(e -> e.employerId() == a).findFirst().orElseThrow();
        assertTrue(first.verified());
        assertEquals("mod-1", first.verifiedBy(), "the first moderator is kept");
        assertFalse(employers.vet(987654321L, "mod-1"), "no such employer");
    }

    @Test
    void securityMonitoringQueriesCountFilterAndAgeAuditEvents() {
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "CSRF_REJECTED", "anonymous", "method=POST path=/api/x client=aaa", null));
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "CSRF_REJECTED", "anonymous", "method=POST path=/api/y client=bbb", null));
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "RATE_LIMITED", "anonymous", "method=POST path=/api/z client=ccc", null));
        audit.insert(new com.kaarigarsetu.model.AuditLog(null, "ASSESSMENT_STARTED", "worker:1", "not a security event", null));
        java.util.List<String> tracked = java.util.List.of("CSRF_REJECTED", "RATE_LIMITED", "ACCESS_DENIED");
        java.util.Map<String, Long> hour = audit.countByType(60, tracked);
        assertEquals(2L, hour.get("CSRF_REJECTED"));
        assertEquals(1L, hour.get("RATE_LIMITED"));
        assertFalse(hour.containsKey("ASSESSMENT_STARTED"), "only the requested types are counted");
        assertFalse(hour.containsKey("ACCESS_DENIED"), "types with no events are absent, not zero rows");
        java.util.List<com.kaarigarsetu.model.AuditLog> recent = audit.recent(tracked, 2);
        assertEquals(2, recent.size());
        assertEquals("RATE_LIMITED", recent.get(0).eventType(), "newest first");
        assertTrue(audit.newestAgeMinutes("CSRF_REJECTED").orElseThrow() <= 1, "just written");
        assertTrue(audit.newestAgeMinutes("NEVER_HAPPENED").isEmpty());
        // an injection attempt through the type list is just a value, never SQL
        assertTrue(audit.countByType(60, java.util.List.of("x') OR ('1'='1")).isEmpty());
    }

    private static boolean chainValid(java.sql.Statement s) throws Exception {
        try (ResultSet r = s.executeQuery("SELECT valid FROM verify_audit_chain()")) {
            r.next();
            return r.getBoolean(1);
        }
    }

    @Test
    void phoneNumbersAreNeverStoredInTheClearByTheProtectedPath() throws Exception {
        worker("h1:opaque-lookup-key");
        try (Connection c = provider.getConnection(); var s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM workers WHERE phone ~ '^[+]?[0-9]{10,13}$'")) {
            r.next();
            assertEquals(0, r.getInt(1));
        }
        Optional<Worker> byKey = workers.findByPhone("h1:opaque-lookup-key");
        assertTrue(byKey.isPresent());
        assertEquals(List.of(), workers.findAllActiveIds().stream().filter(id -> id < 0).toList());
    }

    // ------------------------------------------------------------------ trusted devices (migration 009)

    private String deviceField(long deviceId, String column) throws Exception {
        try (Connection c = provider.getConnection(); var st = c.createStatement(); var r = st.executeQuery("SELECT " + column + " FROM trusted_devices WHERE device_id = " + deviceId)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    @Test
    void aTrustedDeviceIsFoundOnlyForItsOwnAccountRoleAndTokenAndOnlyWhileInUse() throws Exception {
        TrustedDeviceDao devices = new TrustedDeviceDao(provider);
        var mine = devices.insert("WORKER", 7, "hash-mine", 5);
        assertTrue(devices.find("WORKER", 7, "hash-mine", 180).isPresent());
        assertTrue(devices.find("WORKER", 8, "hash-mine", 180).isEmpty(), "another account's id");
        assertTrue(devices.find("EMPLOYER", 7, "hash-mine", 180).isEmpty(), "the same number as an employer id is a different account");
        assertTrue(devices.find("WORKER", 7, "hash-other", 180).isEmpty(), "another token");
        try (Connection c = provider.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE trusted_devices SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '200 days' WHERE device_id = " + mine.deviceId());
        }
        assertTrue(devices.find("WORKER", 7, "hash-mine", 180).isEmpty(), "unused for longer than the trust period");
        assertEquals(1, devices.deleteIdleFor(180));
        assertNull(deviceField(mine.deviceId(), "device_id"));
    }

    @Test
    void anAccountKeepsAtMostTheConfiguredNumberOfDevicesAndLosesTheLeastRecentlyUsed() throws Exception {
        TrustedDeviceDao devices = new TrustedDeviceDao(provider);
        var first = devices.insert("WORKER", 7, "hash-1", 3);
        try (Connection c = provider.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE trusted_devices SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '5 days' WHERE device_id = " + first.deviceId());
        }
        devices.insert("WORKER", 7, "hash-2", 3);
        devices.insert("WORKER", 7, "hash-3", 3);
        devices.insert("WORKER", 7, "hash-4", 3);
        assertTrue(devices.find("WORKER", 7, "hash-1", 180).isEmpty(), "the least recently used is dropped");
        for (String kept : new String[]{"hash-2", "hash-3", "hash-4"}) assertTrue(devices.find("WORKER", 7, kept, 180).isPresent(), kept);
        devices.insert("WORKER", 99, "hash-other-account", 3);
        assertTrue(devices.find("WORKER", 99, "hash-other-account", 180).isPresent(), "trimming one account never touches another");
    }

    @Test
    void aTokenHashIsUniqueAndTouchWritesAtMostOnceAnHour() throws Exception {
        TrustedDeviceDao devices = new TrustedDeviceDao(provider);
        var one = devices.insert("EMPLOYER", 3, "same-hash", 5);
        assertThrows(com.kaarigarsetu.exception.DataAccessException.class, () -> devices.insert("WORKER", 4, "same-hash", 5), "one token, one device");
        String before = deviceField(one.deviceId(), "last_used_at");
        devices.touch(one.deviceId());
        assertEquals(before, deviceField(one.deviceId(), "last_used_at"), "used a moment ago: no write");
        try (Connection c = provider.getConnection(); var st = c.createStatement()) {
            st.execute("UPDATE trusted_devices SET last_used_at = CURRENT_TIMESTAMP - INTERVAL '3 hours' WHERE device_id = " + one.deviceId());
        }
        String old = deviceField(one.deviceId(), "last_used_at");
        devices.touch(one.deviceId());
        assertNotEquals(old, deviceField(one.deviceId(), "last_used_at"));
    }

    @Test
    void erasingAnAccountsDevicesLeavesOthersAlone() {
        TrustedDeviceDao devices = new TrustedDeviceDao(provider);
        devices.insert("WORKER", 7, "a", 5);
        devices.insert("WORKER", 7, "b", 5);
        devices.insert("WORKER", 8, "c", 5);
        assertEquals(2, devices.deleteForAccount("WORKER", 7));
        assertTrue(devices.find("WORKER", 8, "c", 180).isPresent());
        assertEquals(0, devices.deleteForAccount("EMPLOYER", 7));
    }

    @Test
    void roleMustBeWorkerOrEmployer() {
        assertThrows(com.kaarigarsetu.exception.DataAccessException.class, () -> new TrustedDeviceDao(provider).insert("MODERATOR", 1, "x", 5));
    }

    @Test
    void theVettingListIsPagedWithAStableOrderAndTheNamesComeBackDecrypted() {
        for (int n = 1; n <= 5; n++) employer("h1:page-" + n);
        var first = employers.listForVetting(true, 2, 0);
        var second = employers.listForVetting(true, 2, 2);
        var third = employers.listForVetting(true, 2, 4);
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(1, third.size());
        var ids = new java.util.HashSet<Long>();
        for (var page : java.util.List.of(first, second, third)) for (var row : page) assertTrue(ids.add(row.employerId()), "no employer appears on two pages");
        assertEquals(5, ids.size());
        assertEquals("Test Employer", first.get(0).name(), "decrypted, not the stored ciphertext");
        assertTrue(employers.listForVetting(true, 2, 50).isEmpty());
    }
}
