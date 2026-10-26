package com.kaarigarsetu.worker;

import com.kaarigarsetu.assessment.TradeCatalog;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.employer.EmployerService;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.ConsentRequiredException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.CredentialRecord;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.PhoneProtector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerAndEmployerServiceTest {
    private static byte[] key(int v) { byte[] b = new byte[32]; Arrays.fill(b, (byte) v); return b; }

    private final PhoneProtector phones = new PhoneProtector(key(1), key(2), new SecureRandom());
    private final WorkerDao workerDao = mock(WorkerDao.class);
    private final EmployerDao employerDao = mock(EmployerDao.class);
    private final AssessmentDao assessmentDao = mock(AssessmentDao.class);
    private final AttestationDao attestationDao = mock(AttestationDao.class);
    private final CredentialDao credentialDao = mock(CredentialDao.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private WorkerService workers;
    private EmployerService employers;

    @BeforeEach
    void setUp() {
        workers = new WorkerService(workerDao, assessmentDao, attestationDao, credentialDao, phones, audit, Transactions.none(), TradeCatalog.load());
        employers = new EmployerService(employerDao, phones, audit, Transactions.none());
        when(workerDao.findByPhone(anyString())).thenReturn(Optional.empty());
        when(workerDao.insertProtected(any(), any(), anyString())).thenAnswer(i -> {
            Worker w = i.getArgument(0);
            return new Worker(7L, w.name(), w.phone(), w.trade(), w.language(), w.skillScore(), w.trustScore(), null);
        });
        when(employerDao.findByPhone(anyString())).thenReturn(Optional.empty());
        when(employerDao.insertProtected(any(), any(), anyString())).thenAnswer(i -> {
            Employer e = i.getArgument(0);
            return new Employer(9L, e.name(), e.phone(), false, null);
        });
    }

    @Test
    void noConsentMeansNothingIsRecordedAtAll() {
        assertThrows(ConsentRequiredException.class, () -> workers.register("Ramesh", "9876543210", "electrician", "hi", false));
        assertThrows(ConsentRequiredException.class, () -> employers.register("Sharma", "9876543210", false));
        verifyNoInteractions(workerDao, employerDao, audit);
    }

    @Test
    void registrationStoresOnlyTheOpaqueLookupKeyAndTheEncryptedPhoneWithTheConsentVersion() {
        Worker w = workers.register("  Ramesh   Kumar ", "+91 98765-43210", "Electrician", "HI", true);
        assertEquals("Ramesh Kumar", w.name());
        assertEquals("Electrician", w.trade());
        assertEquals("hi", w.language());
        assertNotEquals("+919876543210", w.phone());
        assertTrue(w.phone().startsWith("h1:"));
        ArgumentCaptor<byte[]> encrypted = ArgumentCaptor.forClass(byte[].class);
        verify(workerDao).insertProtected(any(), encrypted.capture(), eq(WorkerService.CONSENT_VERSION));
        assertEquals("+919876543210", phones.decrypt(encrypted.getValue()));
        verify(audit).log(eq("WORKER_REGISTERED"), eq("worker:7"), argThat(d -> !d.contains("9876543210") && d.contains("consent=")));
    }

    @Test
    void theSameNumberInDifferentFormatsIsTheSameAccount() {
        when(workerDao.findByPhone(phones.lookupKey("+919876543210"))).thenReturn(Optional.of(mock(Worker.class)));
        for (String format : new String[]{"9876543210", "+91 98765 43210", "09876543210", "919876543210"}) {
            assertThrows(ConflictException.class, () -> workers.register("Ramesh", format, "electrician", "hi", true), format);
        }
        verify(workerDao, never()).insertProtected(any(), any(), anyString());
    }

    @Test
    void aRaceOnTheUniqueIndexBecomesAConflictAndOtherDatabaseErrorsAreNotSwallowed() {
        doThrow(new DataAccessException("x", new SQLException("dup", "23505"))).when(workerDao).insertProtected(any(), any(), anyString());
        assertThrows(ConflictException.class, () -> workers.register("Ramesh", "9876543210", "electrician", "hi", true));
        doThrow(new DataAccessException("x", new SQLException("down", "08006"))).when(workerDao).insertProtected(any(), any(), anyString());
        assertThrows(DataAccessException.class, () -> workers.register("Ramesh", "9876543210", "electrician", "hi", true));
    }

    @Test
    void workerFieldsAreValidatedAgainstAllowLists() {
        assertThrows(ValidationException.class, () -> workers.register("R", "9876543210", "electrician", "hi", true), "name too short");
        assertThrows(ValidationException.class, () -> workers.register("x".repeat(81), "9876543210", "electrician", "hi", true));
        assertThrows(ValidationException.class, () -> workers.register(null, "9876543210", "electrician", "hi", true));
        assertThrows(ValidationException.class, () -> workers.register("Ramesh", "9876543210", "astronaut", "hi", true));
        assertThrows(ValidationException.class, () -> workers.register("Ramesh", "9876543210", null, "hi", true));
        assertThrows(ValidationException.class, () -> workers.register("Ramesh", "9876543210", "electrician", "fr", true));
        assertThrows(ValidationException.class, () -> workers.register("Ramesh", "5876543210", "electrician", "hi", true));
        assertEquals("Ram Esh", workers.register("Ram\u0000\nEsh", "9876543210", "plumber", "bn", true).name(), "control characters become spaces");
    }

    @Test
    void everyTradeInTheCatalogCanRegisterAndIsStoredWithItsCanonicalName() {
        for (TradeCatalog.Trade trade : TradeCatalog.load().all()) {
            Worker w = workers.register("Test Worker", "9" + (100_000_000 + Math.abs(trade.key().hashCode()) % 899_999_999), trade.key().toUpperCase(), "hi", true);
            assertEquals(trade.name(), w.trade());
        }
    }

    @Test
    void employerRegistrationFollowsTheSameRules() {
        Employer e = employers.register("Sharma  Builders", "9876543210", true);
        assertEquals("Sharma Builders", e.name());
        assertTrue(e.phone().startsWith("h1:"));
        verify(audit).log(eq("EMPLOYER_REGISTERED"), eq("employer:9"), anyString());
        when(employerDao.findByPhone(anyString())).thenReturn(Optional.of(e));
        assertThrows(ConflictException.class, () -> employers.register("Sharma", "9876543210", true));
        assertThrows(ValidationException.class, () -> employers.register("S", "9876543210", true));
        assertThrows(ValidationException.class, () -> employers.register(null, "9876543210", true));
        assertTrue(employers.findByPhone("9876543210").isPresent());
    }

    @Test
    void thePassportAssemblesScoresLatestCredentialAndTheLatestAssessment() {
        UUID attempt = UUID.randomUUID(), ref = UUID.randomUUID();
        when(workerDao.findById(7L)).thenReturn(Optional.of(new Worker(7L, "Ramesh", "k", "electrician", "hi", new BigDecimal("82"), new BigDecimal("40"), null)));
        when(credentialDao.findByWorker(7L)).thenReturn(List.of(new CredentialRecord(2, ref, 7L, "{}", "s", LocalDateTime.now(), null, LocalDateTime.now(), "x", "k")));
        when(assessmentDao.findLatestAttemptId(7L)).thenReturn(Optional.of(attempt));
        when(assessmentDao.findAttempt(7L, attempt)).thenReturn(List.of(new AssessmentAnswer(1L, 7L, "electrician", attempt, 1, "hi", "t", new BigDecimal("90"), "f", true, "r", "p", null)));
        when(attestationDao.countApprovedForWorker(7L)).thenReturn(2);
        WorkerService.Passport p = workers.passport(7L);
        assertEquals(ref, p.latestCredentialRef());
        assertTrue(p.latestCredentialRevoked());
        assertEquals(2, p.verifiedEmployerCount());
        assertEquals(1, p.latestAssessment().size());
        assertTrue(p.latestAssessment().get(0).needsReview());
        when(workerDao.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> workers.passport(99L));
    }

    @Test
    void thePassportCarriesTheBandAndTheAreaBreakdownInOrder() {
        when(workerDao.findById(7L)).thenReturn(Optional.of(new Worker(7L, "Ramesh", "k", "electrician", "hi", new BigDecimal("72"), new BigDecimal("40"), null)));
        when(credentialDao.findByWorker(7L)).thenReturn(List.of());
        when(assessmentDao.findLatestAttemptId(7L)).thenReturn(Optional.empty());
        when(workerDao.findSkillProfile(7L)).thenReturn(Optional.of(new WorkerDao.StoredProfile("PASS", "{\"tools\":90.00,\"planning\":55.50}", true)));
        WorkerService.Passport p = workers.passport(7L);
        assertEquals("PASS", p.skillBand());
        assertTrue(p.nearBoundary());
        assertEquals(List.of("tools", "planning"), List.copyOf(p.skillAreas().keySet()));
        assertEquals(0, new BigDecimal("55.50").compareTo(p.skillAreas().get("planning")));
    }

    @Test
    void thePassportCarriesTheTrustBandAndTheReportWithItsTipsAndACorruptReportNeverBreaksIt() {
        when(workerDao.findById(7L)).thenReturn(Optional.of(new Worker(7L, "Ramesh", "k", "electrician", "hi", BigDecimal.ONE, new BigDecimal("72"), null)));
        when(credentialDao.findByWorker(7L)).thenReturn(List.of());
        when(assessmentDao.findLatestAttemptId(7L)).thenReturn(Optional.empty());
        when(workerDao.findTrustProfile(7L)).thenReturn(Optional.of(new WorkerDao.StoredTrust("GOOD", "{\"employerCount\":3,\"satisfaction\":0.81,\"tips\":[\"Ask for more\"]}")));
        WorkerService.Passport p = workers.passport(7L);
        assertEquals("GOOD", p.trustBand());
        assertEquals(3.0, p.trustReport().get("employerCount"));
        assertEquals(List.of("Ask for more"), p.trustReport().get("tips"));
        for (String bad : new String[]{"not json", "[1]", "", null}) {
            when(workerDao.findTrustProfile(7L)).thenReturn(Optional.of(new WorkerDao.StoredTrust("GOOD", bad)));
            assertTrue(workers.passport(7L).trustReport().isEmpty(), String.valueOf(bad));
        }
        // nothing stored and nobody has confirmed the worker: "no history yet" with the tip, never "not calculated"
        when(workerDao.findTrustProfile(7L)).thenReturn(Optional.empty());
        WorkerService.Passport fresh = workers.passport(7L);
        assertEquals("NO_HISTORY", fresh.trustBand());
        assertTrue(fresh.trustReport().get("tips").toString().contains("No employer has confirmed"));
        // confirmations exist but the scores were not recalculated yet: still "not calculated" rather than a wrong "no history"
        when(attestationDao.countApprovedForWorker(7L)).thenReturn(2);
        assertNull(workers.passport(7L).trustBand());
    }

    @Test
    void aCorruptStoredBreakdownNeverBreaksThePassportPage() {
        when(workerDao.findById(7L)).thenReturn(Optional.of(new Worker(7L, "Ramesh", "k", "electrician", "hi", BigDecimal.ONE, BigDecimal.ONE, null)));
        when(credentialDao.findByWorker(7L)).thenReturn(List.of());
        when(assessmentDao.findLatestAttemptId(7L)).thenReturn(Optional.empty());
        for (String bad : new String[]{"not json", "[1,2]", "{\"tools\":\"high\"}", "", null}) {
            when(workerDao.findSkillProfile(7L)).thenReturn(Optional.of(new WorkerDao.StoredProfile("PASS", bad, false)));
            assertTrue(workers.passport(7L).skillAreas().isEmpty(), String.valueOf(bad));
        }
    }

    @Test
    void aWorkerWithNoHistoryHasAnEmptyButValidPassport() {
        when(workerDao.findById(7L)).thenReturn(Optional.of(new Worker(7L, "Ramesh", "k", "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(credentialDao.findByWorker(7L)).thenReturn(List.of());
        when(assessmentDao.findLatestAttemptId(7L)).thenReturn(Optional.empty());
        WorkerService.Passport p = workers.passport(7L);
        assertNull(p.latestCredentialRef());
        assertFalse(p.latestCredentialRevoked());
        assertTrue(p.latestAssessment().isEmpty());
    }
}
