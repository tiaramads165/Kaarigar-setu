package com.kaarigarsetu.attestation;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.WorkerConfirmation;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.support.MutableClock;
import com.kaarigarsetu.trust.TrustScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfirmationResponseServiceTest {
    private static final long WORKER = 7L, ATTESTATION = 42L;
    private final AttestationDao dao = mock(AttestationDao.class);
    private final TrustScoreService trust = mock(TrustScoreService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T09:00:00Z"));
    private ConfirmationResponseService service;

    private WorkerConfirmation confirmation(String response, LocalDateTime decidedAt) {
        return new WorkerConfirmation(ATTESTATION, 3L, "Sharma Constructions", "House wiring", LocalDate.of(2026, 10, 1), 5, 4, 5, true, decidedAt, response,
                response == null ? null : decidedAt.plusDays(1), "DISPUTED".equals(response) ? "reason" : null, null);
    }

    @BeforeEach
    void setUp() {
        service = new ConfirmationResponseService(dao, trust, credentials, audit, AppSettings.of(Map.of("DISPUTE_WINDOW_DAYS", "30", "DISPUTE_MAX_OPEN", "3")), clock);
        when(dao.findConfirmationForWorker(WORKER, ATTESTATION)).thenReturn(Optional.of(confirmation(null, LocalDateTime.of(2026, 10, 15, 9, 0))));
        when(dao.acceptByWorker(ATTESTATION, WORKER)).thenReturn(true);
        when(dao.disputeByWorker(eq(ATTESTATION), eq(WORKER), anyString(), anyInt(), anyInt())).thenReturn(true);
    }

    @Test
    void listShowsHowLongTheWorkerHasToDisputeAndWhetherTheyStillCan() {
        when(dao.findConfirmationsForWorker(WORKER)).thenReturn(java.util.List.of(confirmation(null, LocalDateTime.of(2026, 10, 15, 9, 0)),
                confirmation(null, LocalDateTime.of(2026, 8, 1, 9, 0)), confirmation("ACCEPTED", LocalDateTime.of(2026, 10, 18, 9, 0))));
        var views = service.list(WORKER);
        assertTrue(views.get(0).canRespond());
        assertEquals(25, views.get(0).daysLeftToDispute(), "30 days minus the 5 that have passed");
        assertFalse(views.get(1).canRespond(), "older than the window");
        assertFalse(views.get(2).canRespond(), "already answered");
    }

    @Test
    void acceptingIsRecordedAndChangesNothingElse() {
        service.accept(WORKER, ATTESTATION);
        verify(dao).acceptByWorker(ATTESTATION, WORKER);
        verify(audit).log(eq("ATTESTATION_ACCEPTED"), eq("worker:7"), contains("attestation=42"));
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void disputingRecordsItAuditsIdsOnlyAndRefreshesTrustAndTheCredential() {
        service.dispute(WORKER, ATTESTATION, "  This employer never hired me\nat all  ");
        verify(dao).disputeByWorker(eq(ATTESTATION), eq(WORKER), eq("This employer never hired me at all"), eq(30), eq(3));
        verify(audit).log(eq("ATTESTATION_DISPUTED"), eq("worker:7"), eq("attestation=42"));
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(WORKER), anyString());
    }

    @Test
    void theReasonTextNeverReachesTheAuditTrail() {
        service.dispute(WORKER, ATTESTATION, "Call me on 9876543210 about this unfair rating");
        verify(audit, never()).log(anyString(), anyString(), contains("9876543210"));
        verify(audit, never()).log(anyString(), anyString(), contains("unfair"));
    }

    @Test
    void aReasonThatIsMissingTooShortOrTooLongIsRefusedBeforeAnythingIsRead() {
        for (String bad : new String[]{null, "", "   ", "too short", "x".repeat(301)}) {
            assertThrows(ValidationException.class, () -> service.dispute(WORKER, ATTESTATION, bad), String.valueOf(bad));
        }
        verify(dao, never()).disputeByWorker(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
        verifyNoInteractions(trust);
    }

    @Test
    void aConfirmationThatIsNotTheWorkersIsNotFoundWhetherToAcceptOrDispute() {
        when(dao.findConfirmationForWorker(WORKER, 99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.accept(WORKER, 99L));
        assertThrows(NotFoundException.class, () -> service.dispute(WORKER, 99L, "this belongs to someone else"));
        verify(dao, never()).acceptByWorker(anyLong(), anyLong());
    }

    @Test
    void aConfirmationCanBeAnsweredOnlyOnce() {
        when(dao.findConfirmationForWorker(WORKER, ATTESTATION)).thenReturn(Optional.of(confirmation("ACCEPTED", LocalDateTime.of(2026, 10, 15, 9, 0))));
        assertThrows(ConflictException.class, () -> service.accept(WORKER, ATTESTATION));
        assertThrows(ConflictException.class, () -> service.dispute(WORKER, ATTESTATION, "I accepted it but changed my mind"));
        verify(dao, never()).disputeByWorker(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void afterTheWindowHasPassedTheDisputeIsRefused() {
        clock.advance(Duration.ofDays(20));          // decided 5 days before the fixture clock, so 25 days old now: inside the 30-day window
        service.dispute(WORKER, ATTESTATION, "still inside the window here");
        clock.advance(Duration.ofDays(10));          // 35 days old now
        when(dao.findConfirmationForWorker(WORKER, ATTESTATION)).thenReturn(Optional.of(confirmation(null, LocalDateTime.of(2026, 10, 15, 9, 0))));
        assertThrows(ConflictException.class, () -> service.dispute(WORKER, ATTESTATION, "now it is far too late"));
    }

    @Test
    void tooManyOpenDisputesAreRefused() {
        when(dao.countOpenDisputes(WORKER)).thenReturn(3);
        assertThrows(ConflictException.class, () -> service.dispute(WORKER, ATTESTATION, "a fourth one at the same time"));
        verify(dao, never()).disputeByWorker(anyLong(), anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    void ifTheDatabaseRefusesTheDisputeNothingIsAuditedOrRefreshed() {
        when(dao.disputeByWorker(eq(ATTESTATION), eq(WORKER), anyString(), anyInt(), anyInt())).thenReturn(false);       // e.g. a race with another request
        assertThrows(ConflictException.class, () -> service.dispute(WORKER, ATTESTATION, "lost the race with myself"));
        verify(audit, never()).log(eq("ATTESTATION_DISPUTED"), anyString(), anyString());
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void aFailureWhileRefreshingScoresDoesNotUndoTheDispute() {
        doThrow(new IllegalStateException("ml down")).when(trust).recompute();
        service.dispute(WORKER, ATTESTATION, "the scoring service is down right now");
        verify(audit).log(eq("TRUST_REFRESH_FAILED"), eq("system"), contains("worker=7"));
        verify(audit).log(eq("ATTESTATION_DISPUTED"), anyString(), anyString());
    }
}
