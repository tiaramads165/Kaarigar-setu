package com.kaarigarsetu.assessment;

import java.math.BigDecimal;

/** The decision band of an overall skill score. Thresholds are configuration (PASS_THRESHOLD, BORDERLINE_THRESHOLD); the agreed defaults are 70 and 50. */
public enum Band {
    PASS, BORDERLINE, NEEDS_REVIEW;

    public static Band of(BigDecimal score, BigDecimal pass, BigDecimal borderline) {
        if (score.compareTo(pass) >= 0) return PASS;
        if (score.compareTo(borderline) >= 0) return BORDERLINE;
        return NEEDS_REVIEW;
    }
}
