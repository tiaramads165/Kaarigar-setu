package com.kaarigarsetu.attestation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AttestationRulesTest {
    @Test
    void strongerEndorsementWeighsMore() {
        BigDecimal yes = AttestationRules.edgeWeight(true);
        BigDecimal unknown = AttestationRules.edgeWeight(null);
        BigDecimal no = AttestationRules.edgeWeight(false);
        assertTrue(yes.compareTo(unknown) > 0 && unknown.compareTo(no) > 0);
        assertTrue(no.signum() > 0, "an attestation is never a negative edge");
        assertTrue(yes.compareTo(BigDecimal.ONE) <= 0);
    }
}
