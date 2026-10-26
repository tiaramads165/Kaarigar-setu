package com.kaarigarsetu.moderator;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.ModeratorActionDao;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.model.ModeratorAction;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.FraudFlag;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModeratorServiceTest {
    private final FraudFlagDao flags = mock(FraudFlagDao.class);
    private final AttestationDao attestations = mock(AttestationDao.class);
    private final TrustEdgeDao edges = mock(TrustEdgeDao.class);
    private final EmployerDao employers = mock(EmployerDao.class);
    private final TrustScoreService trust = mock(TrustScoreService.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ModeratorActionDao actions = mock(ModeratorActionDao.class);
    private final Moderator alice = new Moderator(1L, "alice", "Alice", "h", new byte[0], true, null, "operator:t");
    private final Moderator bob = new Moderator(2L, "bob", "Bob", "h", new byte[0], true, null, "operator:t");
    private ModeratorService service;       // single-moderator mode: everything takes effect at once
    private ModeratorService twoPerson;     // the default: vetting and rulings need a second moderator

    private ModeratorService build(Map<String, String> env) {
        return new ModeratorService(flags, attestations, edges, employers, trust, credentials, audit, Transactions.none(), actions, AppSettings.of(env));
    }

    @BeforeEach
    void setUp() {
        service = build(Map.of("TWO_PERSON_ACTIONS", "none"));
        twoPerson = build(Map.of());
        when(flags.findById(5L)).thenReturn(Optional.of(new FraudFlag(5L, 33L, new BigDecimal("0.95"), "PENDING", null, null)));
        when(attestations.findDetail(33L)).thenReturn(Optional.of(new AttestationDetail(33L, 22L, 11L, "PENDING_REVIEW", null, "wiring", 5, false, LocalDate.now(), null, null)));
        when(flags.decideIfPending(anyLong(), anyString(), anyString())).thenReturn(true);
        when(attestations.moderate(anyLong(), anyString())).thenReturn(true);
    }

    @Test
    void validApprovesCreatesAnEdgeWeightedByTheEmployersAnswerAndRefreshesTrust() {
        assertTrue(service.decide(5L, " valid ", "mod-1"));
        verify(attestations).moderate(33L, "APPROVED");
        verify(edges).insertAttestationEdge(eq(11L), eq(22L), eq(new BigDecimal("0.4000")), eq(33L));
        verify(employers, never()).markVerified(anyLong());      // approving one confirmation must not vet the employer
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(22L), anyString());
        verify(audit).log(eq("MODERATOR_DECISION"), eq("moderator:mod-1"), contains("decision=VALID"));
    }

    @Test
    void rulingOnADisputeIsRecordedAndRefreshesTrustAndTheWorkersCredential() {
        when(attestations.decideDispute(33L, "REMOVED", "alice")).thenReturn(Optional.of(22L));
        assertTrue(service.decideDispute(33L, " removed ", alice).executed());
        verify(audit).log(eq("DISPUTE_DECIDED"), eq("moderator:alice"), contains("decision=REMOVED"));
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(22L), anyString());
    }

    @Test
    void aRulingMustBeUpheldOrRemovedAndNeedsAnOpenDispute() {
        assertThrows(ValidationException.class, () -> service.decideDispute(33L, "MAYBE", alice));
        assertThrows(ValidationException.class, () -> service.decideDispute(33L, null, alice));
        when(attestations.decideDispute(77L, "UPHELD", "alice")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.decideDispute(77L, "UPHELD", alice));
        verify(audit, never()).log(eq("DISPUTE_DECIDED"), anyString(), anyString());
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void vettingAnEmployerIsRecordedRefreshesTrustAndReissuesTheCredentialsOfThoseItConfirmed() {
        when(employers.vet(11L, "alice")).thenReturn(true);
        when(attestations.findApprovedWorkerIds(11L)).thenReturn(java.util.List.of(22L, 23L));
        service.vetEmployer(11L, alice);
        verify(audit).log(eq("EMPLOYER_VETTED"), eq("moderator:alice"), contains("employer=11"));
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(22L), anyString());
        verify(credentials).reissueIfEligible(eq(23L), anyString());
    }

    @Test
    void vettingAnEmployerThatDoesNotExistFailsAndRecordsNothing() {
        when(employers.vet(99L, "alice")).thenReturn(false);
        assertThrows(NotFoundException.class, () -> service.vetEmployer(99L, alice));
        verify(audit, never()).log(eq("EMPLOYER_VETTED"), anyString(), anyString());
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void aFailureWhileRefreshingScoresDoesNotUndoTheVetting() {
        when(employers.vet(11L, "alice")).thenReturn(true);
        doThrow(new IllegalStateException("ml down")).when(trust).recompute();
        service.vetEmployer(11L, alice);
        verify(audit).log(eq("TRUST_REFRESH_FAILED"), eq("system"), contains("employer=11"));
    }

    @Test
    void fraudRejectsAndCreatesNoEdgeAndNoTrustChange() {
        assertFalse(service.decide(5L, "FRAUD", "mod-1"));
        verify(attestations).moderate(33L, "REJECTED");
        verifyNoInteractions(edges, trust, credentials);
        verify(employers, never()).markVerified(anyLong());
    }

    @Test
    void anythingOtherThanValidOrFraudIsRefusedBeforeTouchingData() {
        for (String bad : new String[]{null, "", "approve", "VALID; DROP TABLE", "fraud!"}) assertThrows(ValidationException.class, () -> service.decide(5L, bad, "m"), String.valueOf(bad));
        verifyNoInteractions(attestations, edges);
        verify(flags, never()).decideIfPending(anyLong(), anyString(), anyString());
    }

    @Test
    void aFlagCanBeDecidedOnlyOnce() {
        when(flags.decideIfPending(anyLong(), anyString(), anyString())).thenReturn(false);
        assertThrows(ConflictException.class, () -> service.decide(5L, "VALID", "m"));
        verify(attestations, never()).moderate(anyLong(), anyString());
        verifyNoInteractions(edges);
    }

    @Test
    void unknownFlagsAreNotFound() {
        when(flags.findById(404L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.decide(404L, "VALID", "m"));
    }

    @Test
    void aTrustRefreshFailureIsAuditedButTheDecisionStands() {
        when(trust.recompute()).thenThrow(new IllegalStateException("boom"));
        assertTrue(assertDoesNotThrow(() -> service.decide(5L, "VALID", "m")));
        verify(audit).log(eq("TRUST_REFRESH_FAILED"), anyString(), anyString());
    }

    // ------------------------------------------------------------------ two-person approval

    private ModeratorAction pendingVet(long id, long proposer, String proposerName) {
        return new ModeratorAction(id, "VET_EMPLOYER", 11L, null, proposer, proposerName, LocalDateTime.now(), "PENDING", null, null, null);
    }

    private ModeratorAction pendingRuling(long id, long proposer, String proposerName) {
        return new ModeratorAction(id, "DISPUTE_DECISION", 33L, "REMOVED", proposer, proposerName, LocalDateTime.now(), "PENDING", null, null, null);
    }

    @Test
    void withTwoPersonApprovalVettingOnlyProposesAndChangesNothing() {
        when(employers.findById(11L)).thenReturn(Optional.of(new Employer(11L, "Sharma", null, false, null)));
        when(actions.propose("VET_EMPLOYER", 11L, null, 1L)).thenReturn(Optional.of(7L));
        ModeratorService.Outcome outcome = twoPerson.vetEmployer(11L, alice);
        assertFalse(outcome.executed());
        assertEquals(7L, outcome.actionId());
        verify(employers, never()).vet(anyLong(), anyString());
        verify(audit).log(eq("MODERATOR_ACTION_PROPOSED"), eq("moderator:alice"), contains("type=VET_EMPLOYER"));
        verify(audit, never()).log(eq("EMPLOYER_VETTED"), anyString(), anyString());
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void withTwoPersonApprovalARulingOnlyProposesAndNeedsAnOpenDispute() {
        when(attestations.hasOpenDispute(33L)).thenReturn(true);
        when(actions.propose("DISPUTE_DECISION", 33L, "REMOVED", 1L)).thenReturn(Optional.of(8L));
        assertFalse(twoPerson.decideDispute(33L, "removed", alice).executed());
        verify(attestations, never()).decideDispute(anyLong(), anyString(), anyString());
        when(attestations.hasOpenDispute(77L)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> twoPerson.decideDispute(77L, "UPHELD", alice));
        assertThrows(ValidationException.class, () -> twoPerson.decideDispute(33L, "MAYBE", alice));
    }

    @Test
    void proposingForAnAlreadyVettedOrUnknownEmployerIsRefused() {
        when(employers.findById(12L)).thenReturn(Optional.of(new Employer(12L, "Done", null, true, null)));
        assertThrows(ConflictException.class, () -> twoPerson.vetEmployer(12L, alice));
        when(employers.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> twoPerson.vetEmployer(99L, alice));
        verify(actions, never()).propose(anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void aSecondProposalForTheSameTargetIsAConflict() {
        when(employers.findById(11L)).thenReturn(Optional.of(new Employer(11L, "Sharma", null, false, null)));
        when(actions.propose("VET_EMPLOYER", 11L, null, 2L)).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> twoPerson.vetEmployer(11L, bob));
    }

    @Test
    void aDifferentModeratorApprovingCarriesOutTheVettingNamingBothPeopleAndRefreshesTrust() {
        when(actions.findById(7L)).thenReturn(Optional.of(pendingVet(7L, 1L, "alice")));
        when(actions.approve(eq(7L), eq(2L), anyInt())).thenReturn(true);
        when(employers.vet(eq(11L), anyString())).thenReturn(true);
        when(attestations.findApprovedWorkerIds(11L)).thenReturn(List.of(22L));
        twoPerson.approve(7L, bob);
        verify(employers).vet(11L, "alice (approved by bob)");
        verify(audit).log(eq("MODERATOR_ACTION_APPROVED"), eq("moderator:bob"), contains("proposed_by=alice"));
        verify(audit).log(eq("EMPLOYER_VETTED"), eq("moderator:bob"), argThat(d -> d.contains("proposed_by=alice") && d.contains("approved_by=bob")));
        verify(trust).recompute();
        verify(credentials).reissueIfEligible(eq(22L), anyString());
    }

    @Test
    void aDifferentModeratorApprovingARulingCarriesItOut() {
        when(actions.findById(8L)).thenReturn(Optional.of(pendingRuling(8L, 1L, "alice")));
        when(actions.approve(eq(8L), eq(2L), anyInt())).thenReturn(true);
        when(attestations.decideDispute(eq(33L), eq("REMOVED"), anyString())).thenReturn(Optional.of(22L));
        twoPerson.approve(8L, bob);
        verify(attestations).decideDispute(33L, "REMOVED", "alice (approved by bob)");
        verify(audit).log(eq("DISPUTE_DECIDED"), eq("moderator:bob"), argThat(d -> d.contains("decision=REMOVED") && d.contains("approved_by=bob")));
    }

    @Test
    void aModeratorCannotApproveTheirOwnProposalAndTheAttemptIsAudited() {
        when(actions.findById(7L)).thenReturn(Optional.of(pendingVet(7L, 1L, "alice")));
        assertThrows(ForbiddenException.class, () -> twoPerson.approve(7L, alice));
        verify(actions, never()).approve(anyLong(), anyLong(), anyInt());
        verify(employers, never()).vet(anyLong(), anyString());
        verify(audit).log(eq("MODERATOR_SELF_APPROVAL_REFUSED"), eq("moderator:alice"), contains("action=7"));
        verifyNoInteractions(trust, credentials);
    }

    @Test
    void anApprovalThatLostTheRaceOrArrivedTooLateDoesNothing() {
        when(actions.findById(7L)).thenReturn(Optional.of(pendingVet(7L, 1L, "alice")));
        when(actions.approve(anyLong(), anyLong(), anyInt())).thenReturn(false);
        assertThrows(ConflictException.class, () -> twoPerson.approve(7L, bob), "still pending but the update refused it: past its lifetime");
        when(actions.findById(7L)).thenReturn(Optional.of(new ModeratorAction(7L, "VET_EMPLOYER", 11L, null, 1L, "alice", LocalDateTime.now(), "EXECUTED", 3L, "carol", LocalDateTime.now())));
        ConflictException already = assertThrows(ConflictException.class, () -> twoPerson.approve(7L, bob));
        assertTrue(already.getMessage().contains("executed"));
        verify(employers, never()).vet(anyLong(), anyString());
        verify(audit, never()).log(eq("MODERATOR_ACTION_APPROVED"), anyString(), anyString());
        assertThrows(NotFoundException.class, () -> twoPerson.approve(404L, bob));
    }

    @Test
    void theProposerWithdrawsAndAnotherModeratorRejects() {
        when(actions.findById(7L)).thenReturn(Optional.of(pendingVet(7L, 1L, "alice")));
        when(actions.withdraw(7L, 1L)).thenReturn(true);
        when(actions.reject(7L, 2L)).thenReturn(true);
        twoPerson.reject(7L, alice);
        verify(audit).log(eq("MODERATOR_ACTION_WITHDRAWN"), eq("moderator:alice"), contains("action=7"));
        twoPerson.reject(7L, bob);
        verify(audit).log(eq("MODERATOR_ACTION_REJECTED"), eq("moderator:bob"), contains("action=7"));
        when(actions.reject(7L, 3L)).thenReturn(false);
        Moderator carol = new Moderator(3L, "carol", "Carol", "h", new byte[0], true, null, "operator:t");
        assertThrows(ConflictException.class, () -> twoPerson.reject(7L, carol));
    }

    @Test
    void onlyTheOtherModeratorIsOfferedTheDecision() {
        when(actions.findPending(anyInt())).thenReturn(List.of(pendingVet(7L, 1L, "alice")));
        when(employers.findById(11L)).thenReturn(Optional.of(new Employer(11L, "Sharma", null, false, null)));
        assertFalse(twoPerson.pending(alice).get(0).canDecide());
        assertTrue(twoPerson.pending(bob).get(0).canDecide());
        assertTrue(twoPerson.pending(bob).get(0).target().contains("Sharma"));
        verify(actions, atLeastOnce()).expireOlderThan(72);
    }

    @Test
    void theFraudQueueDecisionIsStillSingleButAttributedToTheNamedModerator() {
        twoPerson.decide(5L, "VALID", "alice");
        verify(audit).log(eq("MODERATOR_DECISION"), eq("moderator:alice"), contains("decision=VALID"));
        verifyNoInteractions(actions);
    }
}
