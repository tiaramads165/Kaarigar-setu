package com.kaarigarsetu.attestation;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.DuplicateAttestationException;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.RateLimitExceededException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.InMemoryOtpChallengeStore;
import com.kaarigarsetu.security.OtpSender;
import com.kaarigarsetu.security.OtpService;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.security.RateLimiter;
import com.kaarigarsetu.support.MutableClock;
import com.kaarigarsetu.trust.TrustScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The attestation workflow with every collaborator mocked except the OTP service, so the security order of checks is what is tested. */
class AttestationWorkflowServiceTest {
    private static final long EMPLOYER = 11L, WORKER = 22L, ATTESTATION = 33L;
    private static byte[] key(int v) { byte[] b = new byte[32]; Arrays.fill(b, (byte) v); return b; }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final PhoneProtector phones = new PhoneProtector(key(1), key(2), new SecureRandom());
    private final AttestationDao attestationDao = mock(AttestationDao.class);
    private final EmployerDao employerDao = mock(EmployerDao.class);
    private final WorkerDao workerDao = mock(WorkerDao.class);
    private final FraudFlagDao fraudFlagDao = mock(FraudFlagDao.class);
    private final TrustEdgeDao trustEdgeDao = mock(TrustEdgeDao.class);
    private final OtpSender sender = mock(OtpSender.class);
    private final MlGateway ml = mock(MlGateway.class);
    private final TrustScoreService trust = mock(TrustScoreService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final OtpService otp = new OtpService(new InMemoryOtpChallengeStore(), Transactions.none(), clock, new SecureRandom(), "p".getBytes(), 5, Duration.ofMinutes(5));
    private AttestationWorkflowService service;
    private final String employerKey = phones.lookupKey("+919000000001");
    private final String workerKey = phones.lookupKey("+919000000002");

    private AttestationDetail detail(String status) {
        return new AttestationDetail(ATTESTATION, WORKER, EMPLOYER, status, null, "wiring", 10, true, LocalDate.of(2026, 10, 1), null, null);
    }

    @BeforeEach
    void setUp() {
        service = new AttestationWorkflowService(attestationDao, employerDao, workerDao, fraudFlagDao, trustEdgeDao, otp, sender, phones, ml, trust,
                credentials, audit, new RateLimiter(clock), clock, Transactions.none());
        when(employerDao.findById(EMPLOYER)).thenReturn(Optional.of(new Employer(EMPLOYER, "E", employerKey, false, null)));
        when(employerDao.findPhoneEncrypted(EMPLOYER)).thenReturn(Optional.of(phones.encrypt("+919000000001")));
        when(workerDao.findByPhone(workerKey)).thenReturn(Optional.of(new Worker(WORKER, "W", workerKey, "electrician", "hi", BigDecimal.ZERO, BigDecimal.ZERO, null)));
        when(attestationDao.insertWorkflow(anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(detail("OTP_PENDING"));
        when(attestationDao.insertWorkflow(anyLong(), anyLong(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(detail("OTP_PENDING"));
        when(attestationDao.findDetail(ATTESTATION)).thenReturn(Optional.of(detail("OTP_PENDING")));
        when(attestationDao.findNetworkHashes(ATTESTATION)).thenReturn(Optional.of(new AttestationDao.NetworkHashes("ip", "dev")));
        when(attestationDao.countFromIpSince(any(), any())).thenReturn(1);
        when(attestationDao.countFromDeviceSince(any(), any())).thenReturn(1);
        when(attestationDao.minutesSincePreviousByEmployer(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(attestationDao.reciprocityRatio(EMPLOYER)).thenReturn(0.0);
        when(attestationDao.decide(anyLong(), anyString(), any())).thenReturn(true);
    }

    private String request() {
        service.requestOtp(EMPLOYER, "9000000002", "wiring", 10, true, LocalDate.of(2026, 10, 1), "1.2.3.4", "dev");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).send(eq("+919000000001"), eq("attestation"), code.capture());
        return code.getValue();
    }

    @Test
    void theOtpGoesToTheEmployersOwnPhoneAndNeverToTheWorkersOrTheCaller() {
        String code = request();
        assertTrue(code.matches("[0-9]{6}"));
        verify(sender, never()).send(eq("+919000000002"), anyString(), anyString());
        verify(attestationDao).updateOtpHash(eq(ATTESTATION), argThat(h -> !h.contains(code)));
    }

    @Test
    void theEmployersRatingsAreValidatedAndHandedToTheDatabase() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        for (int bad : new int[]{0, 6, -3}) {
            assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 5, true, day, "ip", "d", bad, 3, 3), "quality " + bad);
            assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 5, true, day, "ip", "d", 3, bad, 3), "punctuality " + bad);
            assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 5, true, day, "ip", "d", 3, 3, bad), "conduct " + bad);
        }
        verifyNoInteractions(attestationDao, sender);
        service.requestOtp(EMPLOYER, "9000000002", "wiring", 5, true, day, "ip", "d", 5, 4, 3);
        verify(attestationDao).insertWorkflow(eq(WORKER), eq(EMPLOYER), anyString(), eq("wiring"), eq(5), eq(true), eq(day), anyString(), anyString(), eq(5), eq(4), eq(3));
    }

    @Test
    void anEmployerCannotAttestThemselvesOrAnUnknownWorker() {
        when(employerDao.findById(EMPLOYER)).thenReturn(Optional.of(new Employer(EMPLOYER, "E", workerKey, false, null)));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 1), "ip", "d"));
        when(employerDao.findById(EMPLOYER)).thenReturn(Optional.of(new Employer(EMPLOYER, "E", employerKey, false, null)));
        assertThrows(NotFoundException.class, () -> service.requestOtp(EMPLOYER, "9111111111", "wiring", 1, true, LocalDate.of(2026, 10, 1), "ip", "d"));
        verifyNoInteractions(sender);
    }

    @Test
    void aSecondLiveAttestationForTheSamePairIsRefusedBeforeAnyCodeIsSent() {
        when(attestationDao.existsLive(WORKER, EMPLOYER)).thenReturn(true);
        assertThrows(DuplicateAttestationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 1), "ip", "d"));
        verifyNoInteractions(sender);
    }

    @Test
    void inputsAreValidatedBeforeAnythingTouchesTheDatabase() {
        LocalDate ok = LocalDate.of(2026, 10, 1);
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "x", 1, true, ok, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", null, 1, true, ok, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "r".repeat(81), 1, true, ok, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", -1, true, ok, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 4000, true, ok, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, null, "ip", "d"));
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 9), "ip", "d"), "two days ahead is the real future");
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2025, 9, 1), "ip", "d"), "over a year ago");
        assertThrows(ValidationException.class, () -> service.requestOtp(EMPLOYER, "not-a-phone", "wiring", 1, true, ok, "ip", "d"));
        verifyNoInteractions(attestationDao, sender);
    }

    @Test
    void anEmployerWhoseLocalDateIsAlreadyTomorrowInUtcCanStillRecordWorkDoneToday() {
        // 2026-10-07 is "today" on the server clock (UTC); in India between 00:00 and 05:30 the employer's calendar already says 2026-10-08
        assertDoesNotThrow(() -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 8), "ip", "d"));
    }

    @Test
    void requestsAreRateLimitedPerEmployer() {
        for (int i = 0; i < 10; i++) service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 1), "ip", "d");
        assertThrows(RateLimitExceededException.class, () -> service.requestOtp(EMPLOYER, "9000000002", "wiring", 1, true, LocalDate.of(2026, 10, 1), "ip", "d"));
    }

    @Test
    void aCleanAttestationIsApprovedCreatesTheTrustEdgeAndRefreshesTrustAndCredential() {
        String code = request();
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.12, false));
        AttestationWorkflowService.Decision d = service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        assertEquals("APPROVED", d.status());
        assertFalse(d.flagged());
        verify(trustEdgeDao).insertAttestationEdge(eq(EMPLOYER), eq(WORKER), eq(new BigDecimal("1.0000")), eq(ATTESTATION));
        verify(employerDao, never()).markVerified(anyLong());      // an approved confirmation no longer verifies the employer: only a moderator can vet
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(WORKER), anyString());
        verify(fraudFlagDao, never()).insertWithFeatures(anyLong(), any(), any());
    }

    @Test
    void aFlaggedAttestationGoesToHumanReviewWithTheEvidenceAndNoTrustEdge() {
        String code = request();
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.97, true));
        AttestationWorkflowService.Decision d = service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        assertEquals("PENDING_REVIEW", d.status());
        assertTrue(d.flagged());
        verify(fraudFlagDao).insertWithFeatures(eq(ATTESTATION), eq(new BigDecimal("0.9700")), contains("ip_frequency"));
        verify(trustEdgeDao, never()).insertAttestationEdge(anyLong(), anyLong(), any(), anyLong());
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void ifTheMlServiceIsDownTheAttestationFailsSafeIntoReviewInsteadOfBeingApproved() {
        String code = request();
        when(ml.fraudCheck(any())).thenThrow(new ExternalServiceException("down"));
        AttestationWorkflowService.Decision d = service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        assertEquals("PENDING_REVIEW", d.status());
        verify(fraudFlagDao).insertWithFeatures(eq(ATTESTATION), any(), contains("ml_unavailable"));
        verify(trustEdgeDao, never()).insertAttestationEdge(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void aWrongCodeNeverReachesTheFraudModelAndIsAudited() {
        request();
        assertThrows(InvalidOTPException.class, () -> service.verifyOtp(EMPLOYER, ATTESTATION, "abcdef", "ip"));
        verifyNoInteractions(ml);
        verify(audit).log(eq("ATTESTATION_OTP_FAILED"), anyString(), contains("attestation=" + ATTESTATION));
        verify(attestationDao, never()).decide(anyLong(), anyString(), any());
    }

    @Test
    void anotherEmployerCannotSeeOrConfirmSomeoneElsesAttestation() {
        String code = request();
        assertThrows(NotFoundException.class, () -> service.verifyOtp(999L, ATTESTATION, code, "ip"));
    }

    @Test
    void aReplayedConfirmationIsRejected() {
        String code = request();
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.1, false));
        service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        when(attestationDao.findDetail(ATTESTATION)).thenReturn(Optional.of(detail("APPROVED")));
        assertThrows(ConflictException.class, () -> service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip"));
        when(attestationDao.findDetail(ATTESTATION)).thenReturn(Optional.of(detail("OTP_PENDING")));
        when(attestationDao.decide(anyLong(), anyString(), any())).thenReturn(false);
        String second = request();
        assertThrows(ConflictException.class, () -> service.verifyOtp(EMPLOYER, ATTESTATION, second, "ip"), "lost a race with another decision");
    }

    @Test
    void aFailureWhileRefreshingTrustNeverUndoesAnApprovedAttestation() {
        String code = request();
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.1, false));
        when(trust.recompute()).thenThrow(new IllegalStateException("graph exploded"));
        AttestationWorkflowService.Decision d = assertDoesNotThrow(() -> service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip"));
        assertEquals("APPROVED", d.status());
        verify(audit).log(eq("TRUST_REFRESH_FAILED"), anyString(), anyString());
    }

    @Test
    void theFeaturesSentToTheModelAreClampedAndTheFirstAttestationLooksOrdinary() {
        String code = request();
        when(attestationDao.countFromIpSince(any(), any())).thenReturn(0);
        when(attestationDao.reciprocityRatio(EMPLOYER)).thenReturn(0.25);
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.1, false));
        service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        ArgumentCaptor<MlGateway.FraudFeatures> captured = ArgumentCaptor.forClass(MlGateway.FraudFeatures.class);
        verify(ml).fraudCheck(captured.capture());
        assertEquals(1.0, captured.getValue().ipFrequency(), "at least the attestation itself");
        assertEquals(1440.0, captured.getValue().minutesSincePrevious(), "no previous attestation = one ordinary day, not an extreme gap");
        assertEquals(0.25, captured.getValue().reciprocityRatio());
    }

    @Test
    void veryLongGapsAreCappedAtOneWeek() {
        String code = request();
        when(attestationDao.minutesSincePreviousByEmployer(anyLong(), anyLong())).thenReturn(Optional.of(500_000.0));
        when(ml.fraudCheck(any())).thenReturn(new MlGateway.FraudResult(0.1, false));
        service.verifyOtp(EMPLOYER, ATTESTATION, code, "ip");
        ArgumentCaptor<MlGateway.FraudFeatures> captured = ArgumentCaptor.forClass(MlGateway.FraudFeatures.class);
        verify(ml).fraudCheck(captured.capture());
        assertEquals(10_080.0, captured.getValue().minutesSincePrevious());
    }
}
