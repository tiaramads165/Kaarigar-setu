package com.kaarigarsetu.trust;

import java.math.BigDecimal;
import java.util.Objects;

public record Edge(String source, String target, BigDecimal weight) {
    public Edge {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(weight, "weight must not be null");
        if (weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("weight must be between zero and one");
        }
    }
}
