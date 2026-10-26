package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class MasonAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Mason";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong masonry layout, mixing and finishing responses."
                : "Review wall layout, mortar mixing and finishing practice.";
    }
}
