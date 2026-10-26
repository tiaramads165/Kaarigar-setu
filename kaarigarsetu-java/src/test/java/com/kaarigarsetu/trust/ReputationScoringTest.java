package com.kaarigarsetu.trust;

import com.kaarigarsetu.trust.ReputationScoring.EmployerStanding;
import com.kaarigarsetu.trust.ReputationScoring.Feedback;
import com.kaarigarsetu.trust.ReputationScoring.Params;
import com.kaarigarsetu.trust.ReputationScoring.Report;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ReputationScoringTest {
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final Params P = Params.defaults();
    private static final EmployerStanding GOOD_EMPLOYER = new EmployerStanding(true, 5, 0, 0.8);

    private static Feedback fb(long id, long employer, Integer q, Integer p, Integer c, Boolean hire, int daysAgo, double anomaly) {
        return new Feedback(id, 1L, employer, q, p, c, hire, 30, NOW.minusSeconds(daysAgo * 86_400L), anomaly);
    }

    private static Feedback perfect(long id, long employer) { return fb(id, employer, 5, 5, 5, true, 10, 0); }

    private static Map<Long, EmployerStanding> allGood(long... employers) {
        Map<Long, EmployerStanding> m = new HashMap<>();
        for (long e : employers) m.put(e, GOOD_EMPLOYER);
        return m;
    }

    private static Report score(List<Feedback> feedback, Map<Long, EmployerStanding> standing) {
        return ReputationScoring.score(feedback, standing, Map.of(), 0, NOW, P);
    }

    // ------------------------------------------------------------------ satisfaction

    @Test
    void satisfactionIsTheWeightedAverageOfTheAnswersGiven() {
        assertEquals(1.0, ReputationScoring.satisfaction(fb(1, 1, 5, 5, 5, true, 1, 0)), 1e-9);
        assertEquals(0.0, ReputationScoring.satisfaction(fb(1, 1, 1, 1, 1, false, 1, 0)), 1e-9);
        assertEquals(0.5, ReputationScoring.satisfaction(fb(1, 1, 3, 3, 3, null, 1, 0)), 1e-9);
        // quality 5 only: the other answers are absent, so quality alone decides
        assertEquals(1.0, ReputationScoring.satisfaction(fb(1, 1, 5, null, null, null, 1, 0)), 1e-9);
        // 0.40*1 + 0.25*0.5 + 0.20*0 + 0.15*1 over all four = 0.675
        assertEquals(0.675, ReputationScoring.satisfaction(fb(1, 1, 5, 3, 1, true, 1, 0)), 1e-9);
    }

    @Test
    void feedbackWithNoAnswersAtAllHasNoSatisfactionRatherThanZero() {
        assertNull(ReputationScoring.satisfaction(fb(1, 1, null, null, null, null, 1, 0)));
    }

    @Test
    void qualityMattersMoreThanConductWhichMattersMoreThanTheWouldHireAnswer() {
        double badQuality = ReputationScoring.satisfaction(fb(1, 1, 1, 5, 5, true, 1, 0));
        double badConduct = ReputationScoring.satisfaction(fb(1, 1, 5, 5, 1, true, 1, 0));
        double wouldNot = ReputationScoring.satisfaction(fb(1, 1, 5, 5, 5, false, 1, 0));
        assertTrue(badQuality < badConduct && badConduct < wouldNot);
    }

    // ------------------------------------------------------------------ employer credibility

    @Test
    void anEmployerWithAFullRecordAndGoodStandingIsFullyCredible_andAnUnknownOneIsInTheMiddle() {
        assertEquals(1.0, ReputationScoring.employerCredibility(new EmployerStanding(true, 5, 0, 1.0)), 1e-9);
        assertEquals(1.0, ReputationScoring.employerCredibility(new EmployerStanding(true, 50, 0, 1.0)), 1e-9, "a record beyond 5 adds nothing more");
        assertEquals(0.525, ReputationScoring.employerCredibility(EmployerStanding.unknown()), 1e-9);
        assertEquals(0.40, ReputationScoring.employerCredibility(new EmployerStanding(false, 0, 0, 0.0)), 1e-9);
    }

    @Test
    void everyFraudRulingAgainstAnEmployerCutsTheirCredibilityAndItNeverReachesZero() {
        double clean = ReputationScoring.employerCredibility(new EmployerStanding(true, 5, 0, 1.0));
        double one = ReputationScoring.employerCredibility(new EmployerStanding(true, 5, 1, 1.0));
        double three = ReputationScoring.employerCredibility(new EmployerStanding(true, 5, 3, 1.0));
        assertTrue(clean > one && one > three);
        assertEquals(0.7, one, 1e-9);
        assertTrue(ReputationScoring.employerCredibility(new EmployerStanding(false, 0, 99, 0.0)) >= 0.1);
    }

    @Test
    void credibilityIsMonotonicInEachInput() {
        assertTrue(ReputationScoring.employerCredibility(new EmployerStanding(true, 2, 0, 0.5)) > ReputationScoring.employerCredibility(new EmployerStanding(false, 2, 0, 0.5)));
        assertTrue(ReputationScoring.employerCredibility(new EmployerStanding(true, 4, 0, 0.5)) > ReputationScoring.employerCredibility(new EmployerStanding(true, 2, 0, 0.5)));
        assertTrue(ReputationScoring.employerCredibility(new EmployerStanding(true, 2, 0, 0.9)) > ReputationScoring.employerCredibility(new EmployerStanding(true, 2, 0, 0.3)));
    }

    // ------------------------------------------------------------------ recency and engagement

    @Test
    void recentWorkCountsFullyAndOldWorkFadesToAFloor() {
        assertEquals(1.0, ReputationScoring.recency(NOW, NOW, P), 1e-9);
        assertEquals(Math.exp(-1), ReputationScoring.recency(NOW.minusSeconds(365 * 86_400L), NOW, P), 1e-6);
        assertEquals(0.15, ReputationScoring.recency(NOW.minusSeconds(10 * 365 * 86_400L), NOW, P), 1e-9);
        assertEquals(1.0, ReputationScoring.recency(NOW.plusSeconds(86_400), NOW, P), 1e-9, "a timestamp in the future is not rewarded");
    }

    @Test
    void longerEngagementsCountMoreUpToAPoint() {
        assertEquals(0.6, ReputationScoring.engagement(0), 1e-9);
        assertEquals(0.8, ReputationScoring.engagement(15), 1e-9);
        assertEquals(1.0, ReputationScoring.engagement(30), 1e-9);
        assertEquals(1.0, ReputationScoring.engagement(900), 1e-9);
        assertEquals(0.8, ReputationScoring.engagement(null), 1e-9);
    }

    // ------------------------------------------------------------------ leniency calibration

    @Test
    void anAlwaysFiveStarEmployerIsMarkedDownAndAHarshOneIsMarkedUp_withinTheCap() {
        List<Feedback> all = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 6; i++) all.add(fb(id++, 10, 5, 5, 5, true, 5, 0));      // employer 10 rates everyone 5
        for (int i = 0; i < 6; i++) all.add(fb(id++, 20, 3, 3, 3, true, 5, 0));      // employer 20 rates everyone about 3
        for (int i = 0; i < 6; i++) all.add(fb(id++, 30, 4, 4, 4, true, 5, 0));
        Map<Long, Double> offsets = ReputationScoring.calibrate(all, P);
        assertTrue(offsets.get(10L) > 0, "lenient employer: subtract");
        assertTrue(offsets.get(20L) < 0, "harsh employer: add");
        assertEquals(0.10, offsets.get(10L), 1e-9, "capped");
        assertEquals(-0.10, offsets.get(20L), 1e-9, "capped");
        assertTrue(Math.abs(offsets.get(30L)) < 0.05, "an employer near the population average barely moves");
    }

    @Test
    void anEmployerWithTooFewRatingsIsNotCalibratedAtAll() {
        List<Feedback> all = List.of(perfect(1, 10), perfect(2, 10), fb(3, 20, 2, 2, 2, false, 1, 0), fb(4, 20, 2, 2, 2, false, 1, 0), fb(5, 20, 2, 2, 2, false, 1, 0));
        Map<Long, Double> offsets = ReputationScoring.calibrate(all, P);
        assertFalse(offsets.containsKey(10L));
        assertTrue(offsets.containsKey(20L));
        assertTrue(ReputationScoring.calibrate(List.of(), P).isEmpty());
        assertTrue(ReputationScoring.calibrate(List.of(fb(1, 1, null, null, null, null, 1, 0)), P).isEmpty(), "unrated feedback carries no rating habit");
    }

    @Test
    void calibrationChangesTheScoreInTheDirectionOfTheOffset() {
        List<Feedback> feedback = List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12));
        Report plain = ReputationScoring.score(feedback, allGood(10, 11, 12), Map.of(), 0, NOW, P);
        Report lenient = ReputationScoring.score(feedback, allGood(10, 11, 12), Map.of(10L, 0.1, 11L, 0.1, 12L, 0.1), 0, NOW, P);
        Report harsh = ReputationScoring.score(feedback, allGood(10, 11, 12), Map.of(10L, -0.1, 11L, -0.1, 12L, -0.1), 0, NOW, P);
        assertTrue(lenient.score().compareTo(plain.score()) < 0);
        assertEquals(0, plain.score().compareTo(harsh.score()), "a perfect rating cannot be raised above perfect");
    }

    // ------------------------------------------------------------------ the score

    @Test
    void aWorkerNobodyHasConfirmedHasNoHistoryNotABadScore() {
        Report r = score(List.of(), Map.of());
        assertEquals(TrustBand.NO_HISTORY, r.band());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.score()));
        assertEquals(0, r.feedbackCount());
        assertFalse(r.tips().isEmpty());
        assertNull(r.satisfaction());
    }

    @Test
    void oneGlowingEmployerIsNotEnoughForAGoodScore() {
        Report r = score(List.of(perfect(1, 10)), allGood(10));
        assertEquals(TrustBand.BUILDING, r.band());
        assertTrue(r.score().doubleValue() > 30 && r.score().doubleValue() < 50, "was " + r.score());
        assertEquals(0.33, r.confidence(), 1e-9);
        assertEquals(1, r.employerCount());
        assertTrue(r.tips().get(0).startsWith("Ask 2 more different employers"));
    }

    private static Map<Long, EmployerStanding> unvetted(long... employers) {
        Map<Long, EmployerStanding> m = new HashMap<>();
        for (long e : employers) m.put(e, new EmployerStanding(false, 9, 0, 1.0));       // seasoned and well connected, but no moderator has vetted them
        return m;
    }

    @Test
    void withoutAVettedEmployerTheScoreIsHeldJustBelowExcellentHoweverManyAccountsConfirm() {
        for (int accounts : new int[]{3, 5, 12}) {
            List<Feedback> many = new java.util.ArrayList<>();
            long[] ids = new long[accounts];
            for (int i = 0; i < accounts; i++) { many.add(perfect(i + 1, 100 + i)); ids[i] = 100 + i; }
            Report r = score(many, unvetted(ids));
            assertEquals(79.99, r.score().doubleValue(), 0.001, accounts + " unvetted accounts");
            assertEquals(TrustBand.GOOD, r.band());
            assertTrue(r.tips().stream().anyMatch(t -> t.contains("verified by Kaarigar Setu")), r.tips().toString());
        }
    }

    @Test
    void oneVettedEmployerAmongTheConfirmingOnesLiftsTheHold() {
        Map<Long, EmployerStanding> standing = unvetted(10, 11, 12);
        standing.put(11L, new EmployerStanding(true, 9, 0, 1.0));
        Report r = score(List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12)), standing);
        assertEquals(TrustBand.EXCELLENT, r.band());
        assertTrue(r.tips().stream().noneMatch(t -> t.contains("verified by Kaarigar Setu")));
    }

    @Test
    void theHoldNeverRaisesAScoreAndCanBeSwitchedOffByAParameter() {
        Report two = score(List.of(perfect(1, 10), perfect(2, 11)), unvetted(10, 11));
        assertTrue(two.score().doubleValue() < 79.99, "a score already below the line is untouched: " + two.score());
        assertTrue(two.tips().stream().noneMatch(t -> t.contains("verified by Kaarigar Setu")));
        Params off = new Params(0.60, 1.5, 0.25, 3, 365.0, 0.15, 0.5, 0.10, 3, 5.0, 15.0, 0.55, 0.5, false);
        Report free = ReputationScoring.score(List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12)), unvetted(10, 11, 12), Map.of(), 0, NOW, off);
        assertEquals(TrustBand.EXCELLENT, free.band());
    }

    @Test
    void threeDifferentCredibleEmployersGivingTopMarksReachExcellent() {
        Report r = score(List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12)), allGood(10, 11, 12));
        assertEquals(TrustBand.EXCELLENT, r.band());
        assertTrue(r.score().doubleValue() >= 80, "was " + r.score());
        assertEquals(1.0, r.confidence());
        assertTrue(r.tips().isEmpty() || r.tips().stream().noneMatch(t -> t.contains("more different employers")));
    }

    @Test
    void manyConfirmationsFromTheSameEmployerStillCountAsOneEmployer() {
        List<Feedback> same = List.of(perfect(1, 10), perfect(2, 10), perfect(3, 10), perfect(4, 10), perfect(5, 10));
        Report r = score(same, allGood(10));
        assertEquals(1, r.employerCount());
        assertTrue(r.score().doubleValue() < 60, "a single source cannot manufacture a high score, was " + r.score());
    }

    @Test
    void poorFeedbackFromEnoughEmployersGivesALowScore() {
        List<Feedback> poor = List.of(fb(1, 10, 2, 2, 2, false, 10, 0), fb(2, 11, 1, 2, 2, false, 10, 0), fb(3, 12, 2, 1, 2, false, 10, 0));
        Report r = score(poor, allGood(10, 11, 12));
        assertEquals(TrustBand.BUILDING, r.band());
        assertTrue(r.score().doubleValue() < 40, "was " + r.score());
        assertTrue(r.tips().stream().anyMatch(t -> t.contains("would not hire you again")));
    }

    @Test
    void theScoreRisesWithEveryRatingAndFallsWithEveryDownGrade() {
        BigDecimal previous = null;
        for (int rating = 1; rating <= 5; rating++) {
            final int rr = rating;
            Report r = score(List.of(fb(1, 10, rr, rr, rr, true, 5, 0), fb(2, 11, rr, rr, rr, true, 5, 0), fb(3, 12, rr, rr, rr, true, 5, 0)), allGood(10, 11, 12));
            if (previous != null) assertTrue(r.score().compareTo(previous) > 0, "rating " + rating);
            previous = r.score();
        }
    }

    @Test
    void theSameFeedbackCountsForMoreWhenItComesFromAMoreCredibleEmployer() {
        List<Feedback> strong = List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12));
        Map<Long, EmployerStanding> credible = allGood(10, 11, 12);
        Map<Long, EmployerStanding> shady = new HashMap<>();
        for (long e : new long[]{10, 11, 12}) shady.put(e, new EmployerStanding(false, 0, 2, 0.2));
        assertTrue(score(strong, credible).score().compareTo(score(strong, shady).score()) > 0);
    }

    @Test
    void recentConfirmationsOutweighOldOnes() {
        Report recent = score(List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12)), allGood(10, 11, 12));
        List<Feedback> old = List.of(fb(1, 10, 5, 5, 5, true, 1100, 0), fb(2, 11, 5, 5, 5, true, 1100, 0), fb(3, 12, 5, 5, 5, true, 1100, 0));
        Report stale = score(old, allGood(10, 11, 12));
        assertTrue(recent.score().compareTo(stale.score()) > 0);
        assertTrue(stale.monthsSinceLastFeedback() > 30);
        assertTrue(stale.tips().stream().anyMatch(t -> t.contains("months old")));
    }

    @Test
    void feedbackThatLooksFraudulentCountsLess() {
        List<Feedback> clean = List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12));
        List<Feedback> suspicious = List.of(fb(1, 10, 5, 5, 5, true, 10, 1.0), fb(2, 11, 5, 5, 5, true, 10, 1.0), fb(3, 12, 5, 5, 5, true, 10, 1.0));
        assertTrue(score(clean, allGood(10, 11, 12)).score().compareTo(score(suspicious, allGood(10, 11, 12)).score()) > 0);
    }

    @Test
    void ratingFreeConfirmationsCountAsNeutralAtHalfWeight_andTheWorkerIsToldToAskForRatings() {
        List<Feedback> unrated = List.of(fb(1, 10, null, null, null, null, 10, 0), fb(2, 11, null, null, null, null, 10, 0), fb(3, 12, null, null, null, null, 10, 0));
        Report r = score(unrated, allGood(10, 11, 12));
        assertTrue(r.score().doubleValue() > 40 && r.score().doubleValue() < 70, "was " + r.score());
        assertEquals(0.55, r.satisfaction(), 0.001);
        assertTrue(r.tips().stream().anyMatch(t -> t.contains("rate the work")));
        assertTrue(r.dimensionAverages().isEmpty());
    }

    @Test
    void eachRulingThatAConfirmationAboutTheWorkerWasFraudulentCostsFivePointsUpToFifteen() {
        List<Feedback> strong = List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12));
        double base = score(strong, allGood(10, 11, 12)).score().doubleValue();
        for (int flags = 1; flags <= 3; flags++) {
            Report r = ReputationScoring.score(strong, allGood(10, 11, 12), Map.of(), flags, NOW, P);
            assertEquals(base - 5.0 * flags, r.score().doubleValue(), 0.011);
            assertEquals(flags, r.integrityFlags());
        }
        Report capped = ReputationScoring.score(strong, allGood(10, 11, 12), Map.of(), 9, NOW, P);
        assertEquals(base - 15, capped.score().doubleValue(), 0.011);
        assertTrue(capped.tips().stream().anyMatch(t -> t.contains("fraudulent")));
        Params harsh = new Params(0.60, 1.5, 0.25, 3, 365.0, 0.15, 0.5, 0.10, 3, 5.0, 500.0, 0.55, 0.5, true);       // no cap on the penalty
        assertEquals(0, ReputationScoring.score(List.of(fb(1, 10, 1, 1, 1, false, 5, 0)), allGood(10), Map.of(), 9, NOW, harsh).score().compareTo(BigDecimal.ZERO), "never below zero");
    }

    // ------------------------------------------------------------------ the explanation

    @Test
    void theReportExplainsItselfWithAveragesRatesAndConsistency() {
        List<Feedback> mixed = List.of(fb(1, 10, 5, 4, 3, true, 30, 0), fb(2, 11, 3, 4, 3, false, 60, 0), fb(3, 12, 4, 4, 3, true, 90, 0));
        Report r = score(mixed, allGood(10, 11, 12));
        assertEquals(4.0, r.dimensionAverages().get("quality"));
        assertEquals(4.0, r.dimensionAverages().get("punctuality"));
        assertEquals(3.0, r.dimensionAverages().get("conduct"));
        assertEquals(0.667, r.wouldHireAgainRate(), 0.001);
        assertEquals(1.0, r.confidence());
        assertEquals(3, r.feedbackCount());
        assertEquals(1.0, r.monthsSinceLastFeedback(), 0.1);
        assertNotNull(r.consistency());
        assertTrue(r.tips().stream().anyMatch(t -> t.contains("conduct and honesty") && t.contains("3.0 out of 5")));
    }

    @Test
    void identicalFeedbackIsPerfectlyConsistentAndScatteredFeedbackIsNot() {
        Report same = score(List.of(perfect(1, 10), perfect(2, 11), perfect(3, 12)), allGood(10, 11, 12));
        Report scattered = score(List.of(fb(1, 10, 5, 5, 5, true, 5, 0), fb(2, 11, 1, 1, 1, false, 5, 0), fb(3, 12, 3, 3, 3, true, 5, 0)), allGood(10, 11, 12));
        assertEquals(100.0, same.consistency());
        assertTrue(scattered.consistency() < 60);
        assertNull(score(List.of(perfect(1, 10)), allGood(10)).consistency(), "one piece of feedback has no consistency to speak of");
    }

    @Test
    void noMoreThanFourTipsAreEverGiven() {
        List<Feedback> everythingWrong = List.of(fb(1, 10, 1, 4, 5, false, 600, 0), fb(2, 10, 2, 4, 5, false, 600, 0));
        Report r = ReputationScoring.score(everythingWrong, Map.of(10L, new EmployerStanding(false, 0, 0, 0.0)), Map.of(), 2, NOW, P);
        assertTrue(r.tips().size() <= 4);
    }

    // ------------------------------------------------------------------ safety properties

    @Test
    void whateverTheInputTheScoreIsBetweenZeroAndOneHundredAndTheBandMatchesIt() {
        Random random = new Random(42);
        for (int run = 0; run < 3000; run++) {
            int n = 1 + random.nextInt(8);
            List<Feedback> feedback = new ArrayList<>();
            Map<Long, EmployerStanding> standing = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long employer = 1 + random.nextInt(6);
                feedback.add(new Feedback(i, 1L, employer, maybe(random), maybe(random), maybe(random), random.nextInt(3) == 0 ? null : random.nextBoolean(),
                        random.nextInt(4) == 0 ? null : random.nextInt(400), NOW.minusSeconds(random.nextInt(2000) * 86_400L), random.nextDouble()));
                standing.put(employer, new EmployerStanding(random.nextBoolean(), random.nextInt(12), random.nextInt(4), random.nextDouble()));
            }
            Report r = ReputationScoring.score(feedback, standing, Map.of(), random.nextInt(6), NOW, P);
            double s = r.score().doubleValue();
            assertTrue(s >= 0 && s <= 100 && !Double.isNaN(s), "score " + s);
            assertEquals(TrustBand.of(r.score(), true), r.band());
            assertTrue(r.confidence() >= 0 && r.confidence() <= 1);
        }
    }

    private static Integer maybe(Random r) { return r.nextInt(4) == 0 ? null : 1 + r.nextInt(5); }

    @Test
    void bandsFollowTheThresholdsAtTheEdges() {
        assertEquals(TrustBand.EXCELLENT, TrustBand.of(new BigDecimal("80"), true));
        assertEquals(TrustBand.GOOD, TrustBand.of(new BigDecimal("79.99"), true));
        assertEquals(TrustBand.GOOD, TrustBand.of(new BigDecimal("65"), true));
        assertEquals(TrustBand.FAIR, TrustBand.of(new BigDecimal("64.99"), true));
        assertEquals(TrustBand.FAIR, TrustBand.of(new BigDecimal("50"), true));
        assertEquals(TrustBand.BUILDING, TrustBand.of(new BigDecimal("49.99"), true));
        assertEquals(TrustBand.NO_HISTORY, TrustBand.of(new BigDecimal("90"), false));
    }

    @Test
    void theParametersCanBeTunedWithoutTouchingTheLogic() {
        Params twoEmployersEnough = new Params(0.60, 1.5, 0.25, 2, 365.0, 0.15, 0.5, 0.10, 3, 5.0, 15.0, 0.55, 0.5, true);
        List<Feedback> two = List.of(perfect(1, 10), perfect(2, 11));
        Report strict = score(two, allGood(10, 11));
        Report relaxed = ReputationScoring.score(two, allGood(10, 11), Map.of(), 0, NOW, twoEmployersEnough);
        assertEquals(1.0, relaxed.confidence());
        assertTrue(relaxed.score().compareTo(strict.score()) > 0);
    }
}
