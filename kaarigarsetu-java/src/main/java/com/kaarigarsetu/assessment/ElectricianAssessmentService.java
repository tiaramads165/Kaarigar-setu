package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

public class ElectricianAssessmentService extends AssessmentService {
    @Override
    public String supportedTrade() {
        return "Electrician";
    }

    @Override
    protected String feedbackFor(BigDecimal score) {
        return score.compareTo(new BigDecimal("80")) >= 0
                ? "Strong electrical safety and troubleshooting responses."
                : "Review electrical safety procedures and circuit troubleshooting.";
    }
}
