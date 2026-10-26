package com.kaarigarsetu.trust;

import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.AttestationDao.EmployerActivity;
import com.kaarigarsetu.dao.AttestationDao.FeedbackRow;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The service around the pure model: what it loads, what it asks the ML service, what it stores, and how it degrades. */
class TrustScoreServiceTest {
    private static final LocalDateTime RECENT = LocalDateTime.of(2026, 9, 25, 9, 0);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-09T09:00:00Z"));
    private final AttestationDao attestations = mock(AttestationDao.class);
    private final WorkerDao workers = mock(WorkerDao.class);
    private final MlGateway ml = mock(MlGateway.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private TrustScoreService service;

    @BeforeEach
    void setUp() {
        service = new TrustScoreService(attestations, workers, ml, audit, clock);
        when(attestations.findFraudUpheldByWorker()).thenReturn(Map.of());
        when(attestations.findEmployerActivity()).thenReturn(List.of());
    }

    private static FeedbackRow row(long id, long worker, long employer, int q, int p, int c) {
        return new FeedbackRow(id, worker, employer, q, p, c, true, 30, RECENT, 0.0);
    }

    private BigDecimal storedScore(long workerId) {
        ArgumentCaptor<BigDecimal> score = ArgumentCaptor.forClass(BigDecimal.class);
        verify(workers, atLeastOnce()).updateTrustProfile(eq(workerId), score.capture(), anyString(), anyString());
        return score.getValue();
    }

    @Test
    void threeConfirmationsFromEmployersNobodyHasVettedAreHeldBelowExcellent() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5), row(2, 1, 11, 5, 5, 5), row(3, 1, 12, 5, 5, 5)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of("E10", 100.0, "E11", 100.0, "E12", 100.0));
        when(attestations.findEmployerActivity()).thenReturn(List.of(new AttestationDao.EmployerActivity(10L, false, 9, 0), new AttestationDao.EmployerActivity(11L, false, 9, 0),
                new AttestationDao.EmployerActivity(12L, false, 9, 0)));
        service.recompute();
        ArgumentCaptor<String> band = ArgumentCaptor.forClass(String.class), json = ArgumentCaptor.forClass(String.class);
        verify(workers).updateTrustProfile(eq(1L), any(), band.capture(), json.capture());
        assertEquals("GOOD", band.getValue(), "no vetted employer: the top band is out of reach however many accounts confirm");
        assertTrue(json.getValue().contains("verified by Kaarigar Setu"), "the worker is told why");
    }

    @Test
    void everyWorkerGetsAScoreABandAnExplanationStoredTogether_andNobodyHistoryIsNoHistoryNotZeroDisguisedAsBad() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5), row(2, 1, 11, 5, 5, 5), row(3, 1, 12, 5, 5, 5)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of("E10", 100.0, "E11", 100.0, "E12", 100.0));
        when(attestations.findEmployerActivity()).thenReturn(List.of(new AttestationDao.EmployerActivity(10L, true, 5, 0)));      // employer 10 was vetted by a moderator
        Map<Long, BigDecimal> result = service.recompute();

        ArgumentCaptor<String> band = ArgumentCaptor.forClass(String.class), json = ArgumentCaptor.forClass(String.class);
        verify(workers).updateTrustProfile(eq(1L), any(), band.capture(), json.capture());
        assertEquals("EXCELLENT", band.getValue());
        assertTrue(json.getValue().contains("\"feedbackCount\":3") && json.getValue().contains("\"tips\""));
        verify(workers).updateTrustProfile(eq(2L), argThat(s -> s.signum() == 0), eq("NO_HISTORY"), contains("No employer has confirmed"));
        assertEquals(2, result.size());
        verify(audit).log(eq("TRUST_RECOMPUTED"), eq("system"), contains("feedback=3"));
    }

    @Test
    void anEmployerWithBetterNetworkStandingMakesTheSameFeedbackWorthMore() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        // worker 1 is confirmed by three standing employers, worker 2 by three peripheral ones, with identical ratings
        when(attestations.findApprovedFeedback()).thenReturn(List.of(
                row(1, 1, 10, 4, 4, 4), row(2, 1, 11, 4, 4, 4), row(3, 1, 12, 4, 4, 4),
                row(4, 2, 20, 4, 4, 4), row(5, 2, 21, 4, 4, 4), row(6, 2, 22, 4, 4, 4)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of("E10", 100.0, "E11", 95.0, "E12", 90.0, "E20", 10.0, "E21", 8.0, "E22", 5.0));
        service.recompute();
        assertTrue(storedScore(1L).compareTo(storedScore(2L)) > 0, "the PageRank standing of the employers must matter");
    }

    @Test
    void anEmployersTrackRecordAndAFraudRulingAgainstThemMoveTheirWeight() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(
                row(1, 1, 10, 4, 4, 4), row(2, 1, 11, 4, 4, 4), row(3, 1, 12, 4, 4, 4),
                row(4, 2, 20, 4, 4, 4), row(5, 2, 21, 4, 4, 4), row(6, 2, 22, 4, 4, 4)));
        when(attestations.findEmployerActivity()).thenReturn(List.of(
                new EmployerActivity(10, true, 8, 0), new EmployerActivity(11, true, 8, 0), new EmployerActivity(12, true, 8, 0),
                new EmployerActivity(20, false, 1, 2), new EmployerActivity(21, false, 1, 2), new EmployerActivity(22, false, 1, 2)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of());
        service.recompute();
        assertTrue(storedScore(1L).compareTo(storedScore(2L)) > 0);
    }

    @Test
    void ifPageRankIsUnavailableTheScoresAreStillComputedAndTheGapIsAudited() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5), row(2, 1, 11, 5, 5, 5), row(3, 1, 12, 5, 5, 5)));
        when(ml.trustScores(any(), any())).thenThrow(new ExternalServiceException("down"));
        assertDoesNotThrow(() -> service.recompute());
        assertTrue(storedScore(1L).doubleValue() > 50, "a confirmation is not lost because the ML service was down");
        verify(audit).log(eq("TRUST_PAGERANK_UNAVAILABLE"), eq("system"), anyString());
    }

    @Test
    void theGraphSentToTheMlServiceHasForwardAndWeakerReverseEdgesAllInTheAcceptedRange() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5), new FeedbackRow(2, 1, 11, 1, 1, 1, false, 5, RECENT, 1.0)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of());
        service.recompute();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<MlGateway.TrustEdge>> edges = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> nodes = ArgumentCaptor.forClass(List.class);
        verify(ml, times(2)).trustScores(nodes.capture(), edges.capture());      // call 1: employer standing (with reverse edges); call 2: the plain network standing
        List<MlGateway.TrustEdge> sent = edges.getAllValues().get(0);
        assertEquals(4, sent.size());
        assertTrue(nodes.getAllValues().get(0).containsAll(List.of("W1", "E10", "E11")));
        assertTrue(sent.stream().allMatch(e -> e.weight() >= 0.01 && e.weight() <= 1.0));
        MlGateway.TrustEdge good = sent.get(0), reverse = sent.get(1), bad = sent.get(2);
        assertEquals("E10", good.source());
        assertEquals("W1", reverse.source());
        assertEquals(good.weight() * 0.5, reverse.weight(), 1e-9);
        assertTrue(good.weight() > bad.weight(), "an unhappy, suspicious confirmation builds a weaker edge");
    }

    @Test
    void theNetworkStandingIsTheWorkersPageRankScaledTo100_storedBesideTheTrustScoreWithoutChangingIt() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5), row(2, 1, 11, 5, 5, 5), row(3, 1, 12, 5, 5, 5)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of("E10", 20.0, "E11", 20.0, "E12", 20.0, "W1", 100.0));
        service.recompute();
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(workers).updateTrustProfile(eq(1L), any(), anyString(), json.capture());
        assertTrue(json.getValue().contains("\"networkStanding\":100.0"), json.getValue());
        verify(workers).updateTrustProfile(eq(2L), any(), eq("NO_HISTORY"), argThat(j -> !j.contains("networkStanding")));   // nobody confirmed worker 2: no value, not zero
    }

    @Test
    void ifTheMlServiceIsDownThereIsNoNetworkStandingAndTheGapIsAudited() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 1, 10, 5, 5, 5)));
        when(ml.trustScores(any(), any())).thenThrow(new ExternalServiceException("down"));
        service.recompute();
        verify(workers).updateTrustProfile(eq(1L), any(), anyString(), argThat(j -> !j.contains("networkStanding")));
        verify(audit).log(eq("NETWORK_STANDING_UNAVAILABLE"), eq("system"), anyString());
    }

    @Test
    void endorsementWeightFollowsTheDesignNote_hireAgainYes1_notStated08_no04_decayOverAYear_anomalyHalves() {
        Instant now = clock.instant();
        java.util.function.BiFunction<Boolean, Double, Double> w = (hire, anomaly) ->
                ReputationScoring.endorsementWeight(new ReputationScoring.Feedback(1, 1, 1, 5, 5, 5, hire, 10, now, anomaly), now);
        assertEquals(1.0, w.apply(true, 0.0), 1e-9);
        assertEquals(0.8, w.apply(null, 0.0), 1e-9);
        assertEquals(0.4, w.apply(false, 0.0), 1e-9);
        assertEquals(0.5, w.apply(true, 1.0), 1e-9);                       // fully suspicious halves it
        Instant yearAgo = now.minus(java.time.Duration.ofDays(365));
        assertEquals(Math.exp(-1), ReputationScoring.endorsementWeight(new ReputationScoring.Feedback(1, 1, 1, 5, 5, 5, true, 10, yearAgo, 0.0), now), 1e-3);
        Instant longAgo = now.minus(java.time.Duration.ofDays(3650));
        assertEquals(0.05, ReputationScoring.endorsementWeight(new ReputationScoring.Feedback(1, 1, 1, 5, 5, 5, false, 10, longAgo, 1.0), now), 1e-9);   // never below 0.05
    }

    @Test
    void rulingsThatConfirmationsAboutAWorkerWereFraudulentLowerThatWorkersScoreOnly() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L, 2L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(
                row(1, 1, 10, 5, 5, 5), row(2, 1, 11, 5, 5, 5), row(3, 1, 12, 5, 5, 5),
                row(4, 2, 10, 5, 5, 5), row(5, 2, 11, 5, 5, 5), row(6, 2, 12, 5, 5, 5)));
        when(attestations.findFraudUpheldByWorker()).thenReturn(Map.of(2L, 2));
        when(ml.trustScores(any(), any())).thenReturn(Map.of());
        service.recompute();
        assertEquals(10.0, storedScore(1L).subtract(storedScore(2L)).doubleValue(), 0.011);
    }

    @Test
    void aWorkerWhoIsNotInTheActiveListButHasFeedbackIsStillScored() {
        when(workers.findAllActiveIds()).thenReturn(List.of());
        when(attestations.findApprovedFeedback()).thenReturn(List.of(row(1, 7, 10, 4, 4, 4)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of());
        assertTrue(service.recompute().containsKey(7L));
    }

    @Test
    void withNoFeedbackAnywhereTheMlServiceIsNotEvenCalled() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of());
        service.recompute();
        verifyNoInteractions(ml);
        verify(workers).updateTrustProfile(eq(1L), any(), eq("NO_HISTORY"), anyString());
    }

    @Test
    void aFutureTimestampIsTreatedAsNowRatherThanAsBonusRecency() {
        when(workers.findAllActiveIds()).thenReturn(List.of(1L));
        when(attestations.findApprovedFeedback()).thenReturn(List.of(new FeedbackRow(1, 1, 10, 5, 5, 5, true, 30, LocalDateTime.of(2030, 1, 1, 0, 0), 0.0)));
        when(ml.trustScores(any(), any())).thenReturn(Map.of());
        assertDoesNotThrow(() -> service.recompute());
    }
}
