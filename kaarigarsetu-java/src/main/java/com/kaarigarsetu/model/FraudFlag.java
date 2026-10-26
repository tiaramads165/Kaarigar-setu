package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FraudFlag(
        Long flagId,
        Long attestationId,
        BigDecimal anomalyScore,
        String reviewerDecision,
        String reviewedBy,
        LocalDateTime reviewedAt
) {
}
