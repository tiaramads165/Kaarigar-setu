package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TrustEdgeRow(long edgeId, String sourceKind, long sourceId, long targetId, BigDecimal weight,
                           boolean verified, Long attestationId, LocalDateTime createdAt, BigDecimal anomalyScore) {
}
