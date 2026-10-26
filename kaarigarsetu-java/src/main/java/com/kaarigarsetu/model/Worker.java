package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Worker(
        Long workerId,
        String name,
        String phone,
        String trade,
        String language,
        BigDecimal skillScore,
        BigDecimal trustScore,
        LocalDateTime createdAt
) {
}
