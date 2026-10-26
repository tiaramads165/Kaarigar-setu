package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class PlumberAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Plumber";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong plumbing diagnostics and installation responses."
                : "Review leak diagnosis, fittings, and safe installation practice.";
    }
}
