package com.kaarigarsetu.trust;

import java.math.BigDecimal;

/** The reading of a trust score, like a credit band. NO_HISTORY is not a bad score: it means no employer has confirmed the worker's work yet. */
public enum TrustBand {
    EXCELLENT, GOOD, FAIR, BUILDING, NO_HISTORY;

    public static TrustBand of(BigDecimal score, boolean hasHistory) {
        if (!hasHistory) return NO_HISTORY;
        if (score.compareTo(BigDecimal.valueOf(80)) >= 0) return EXCELLENT;
        if (score.compareTo(BigDecimal.valueOf(65)) >= 0) return GOOD;
        if (score.compareTo(BigDecimal.valueOf(50)) >= 0) return FAIR;
        return BUILDING;
    }
}
