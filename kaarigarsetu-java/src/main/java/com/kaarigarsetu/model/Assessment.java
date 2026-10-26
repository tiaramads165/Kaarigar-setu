package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Assessment(
        Long assessmentId,
        Long workerId,
        String trade,
        String transcript,
        BigDecimal score,
        String feedback,
        LocalDateTime createdAt
) {
}
