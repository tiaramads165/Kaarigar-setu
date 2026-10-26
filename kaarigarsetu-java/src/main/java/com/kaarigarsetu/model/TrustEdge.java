package com.kaarigarsetu.model;

import java.math.BigDecimal;

public record TrustEdge(
        Long edgeId,
        Long sourceId,
        Long targetId,
        BigDecimal weight,
        boolean verified
) {
}
