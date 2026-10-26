package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

/** The service-layer result returned before an assessment is persisted. */
public record AssessmentResult(String trade, BigDecimal score, String feedback) {
}
