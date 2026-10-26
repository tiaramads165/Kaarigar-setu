package com.kaarigarsetu.attestation;

import java.math.BigDecimal;

/** Shared rules for turning an approved attestation into a trust edge. */
public final class AttestationRules {
    private AttestationRules() {}

    /** Base edge weight from the employer's work-quality answer: strong endorsement counts more than a lukewarm one. */
    public static BigDecimal edgeWeight(Boolean wouldHireAgain) {
        if (wouldHireAgain == null) return new BigDecimal("0.8000");
        return wouldHireAgain ? new BigDecimal("1.0000") : new BigDecimal("0.4000");
    }
}
