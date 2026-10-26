package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class PainterAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Painter";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong surface preparation and painting responses."
                : "Review surface preparation, paint selection and finishing practice.";
    }
}
