package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Attestation(
        Long attestationId,
        Long workerId,
        Long employerId,
        String otpHash,
        String status,
        BigDecimal anomalyScore,
        LocalDateTime createdAt
) {
}
