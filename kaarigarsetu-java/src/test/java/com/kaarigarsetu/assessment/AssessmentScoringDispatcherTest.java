package com.kaarigarsetu.assessment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessmentScoringDispatcherTest {
    private static final List<String> TEN = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    private final AssessmentScoringDispatcher dispatcher = new AssessmentScoringDispatcher(List.of(
            new ElectricianAssessmentService(), new PlumberAssessmentService()));

    @Test
    void dispatchesToElectricianAtRuntimeAndScoresAnsweredQuestions() {
        AssessmentResult result = dispatcher.score(" electrician ", TEN);

        assertEquals("Electrician", result.trade());
        assertEquals(0, new BigDecimal("100.00").compareTo(result.score()));
        assertEquals("Strong electrical safety and troubleshooting responses.", result.feedback());
    }

    @Test
    void dispatchesToPlumberAndRejectsInvalidInputs() {
        AssessmentResult result = dispatcher.score("Plumber", List.of("a", "", "c", "", "e", "f", "", "h", "", "j"));

        assertEquals(0, new BigDecimal("60.00").compareTo(result.score()));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.score("Mason", TEN));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.score("Plumber", List.of("a")));
    }
}
