package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class CarpenterAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Carpenter";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong carpentry joinery and finishing responses."
                : "Review joinery, measuring and finishing practice.";
    }
}
