package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class WelderAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Welder";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong welding technique and joint quality responses."
                : "Review weld technique, joint preparation and finishing practice.";
    }
}
