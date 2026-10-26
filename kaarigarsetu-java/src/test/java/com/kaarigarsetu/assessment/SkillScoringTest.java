package com.kaarigarsetu.assessment;

import com.kaarigarsetu.assessment.SkillScoring.QuestionScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillScoringTest {
    private static final BigDecimal PASS = new BigDecimal("70"), BORDERLINE = new BigDecimal("50"), MARGIN = new BigDecimal("5");

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static SkillProfile profile(String overall, QuestionScore... questions) {
        return SkillScoring.profile(d(overall), List.of(questions), PASS, BORDERLINE, MARGIN);
    }

    private static final QuestionScore ONE = new QuestionScore("tools", d("50"));

    @Test
    void bandsFollowTheAgreedThresholdsExactlyAtTheEdges() {
        assertEquals(Band.PASS, profile("100", ONE).band());
        assertEquals(Band.PASS, profile("70", ONE).band());
        assertEquals(Band.BORDERLINE, profile("69.99", ONE).band());
        assertEquals(Band.BORDERLINE, profile("50", ONE).band());
        assertEquals(Band.NEEDS_REVIEW, profile("49.99", ONE).band());
        assertEquals(Band.NEEDS_REVIEW, profile("0", ONE).band());
    }

    @Test
    void thresholdsAreConfigurable() {
        SkillProfile strict = SkillScoring.profile(d("75"), List.of(ONE), d("80"), d("60"), MARGIN);
        assertEquals(Band.BORDERLINE, strict.band());
    }

    @Test
    void nearBoundaryMeansStrictlyWithinTheMarginOfEitherThreshold() {
        assertTrue(profile("72", ONE).nearBoundary());
        assertTrue(profile("66", ONE).nearBoundary());
        assertTrue(profile("52.5", ONE).nearBoundary());
        assertTrue(profile("46", ONE).nearBoundary());
        assertFalse(profile("75", ONE).nearBoundary(), "exactly the margin away is not near");
        assertFalse(profile("60", ONE).nearBoundary());
        assertFalse(profile("20", ONE).nearBoundary());
        assertFalse(profile("100", ONE).nearBoundary());
    }

    @Test
    void eachAreaScoreIsTheMeanOfItsQuestionsInTheStandardDisplayOrder() {
        SkillProfile p = profile("60",
                new QuestionScore("planning", d("40")), new QuestionScore("tools", d("100")), new QuestionScore("planning", d("61")),
                new QuestionScore("knowledge", d("50")), new QuestionScore("tools", d("80")));
        assertEquals(List.of("tools", "knowledge", "planning"), List.copyOf(p.areas().keySet()), "standard order, not the order questions arrived in");
        assertEquals(0, d("90.00").compareTo(p.areas().get("tools")));
        assertEquals(0, d("50.50").compareTo(p.areas().get("planning")));
        assertEquals(0, d("50.00").compareTo(p.areas().get("knowledge")));
    }

    @Test
    void anUnknownAreaIsKeptAfterTheStandardOnesAndAMissingOneCountsAsKnowledge() {
        SkillProfile p = profile("50", new QuestionScore("welding-process", d("10")), new QuestionScore(null, d("20")),
                new QuestionScore(" ", d("40")), new QuestionScore("tools", d("90")));
        assertEquals(List.of("tools", "knowledge", "welding-process"), List.copyOf(p.areas().keySet()));
        assertEquals(0, d("30.00").compareTo(p.areas().get("knowledge")));
    }

    @Test
    void theOverallScoreIsPassedThroughUnchangedSoTheHeadlineNumberDoesNotDependOnTheAreas() {
        assertEquals(0, d("73.45").compareTo(profile("73.45", new QuestionScore("tools", d("0"))).overall()));
    }

    @Test
    void theAreaMapCannotBeChangedByTheCaller() {
        assertThrows(UnsupportedOperationException.class, () -> profile("50", ONE).areas().put("x", BigDecimal.ONE));
    }

    @Test
    void invalidInputIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SkillScoring.profile(d("50"), List.of(), PASS, BORDERLINE, MARGIN));
        assertThrows(IllegalArgumentException.class, () -> SkillScoring.profile(d("50"), null, PASS, BORDERLINE, MARGIN));
        assertThrows(IllegalArgumentException.class, () -> SkillScoring.profile(d("50"), List.of(ONE), BORDERLINE, PASS, MARGIN), "borderline must be below pass");
        assertThrows(IllegalArgumentException.class, () -> SkillScoring.profile(d("50"), List.of(ONE), PASS, BORDERLINE, d("-1")));
        assertThrows(NullPointerException.class, () -> SkillScoring.profile(null, List.of(ONE), PASS, BORDERLINE, MARGIN));
    }
}
