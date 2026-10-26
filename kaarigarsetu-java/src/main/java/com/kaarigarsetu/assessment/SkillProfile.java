package com.kaarigarsetu.assessment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The result of an assessment beyond the single number: the band, the score per skill area, and whether the overall score sits close to a band
 * boundary (within the margin), in which case the band is less certain than it looks.
 */
public record SkillProfile(BigDecimal overall, Band band, Map<String, BigDecimal> areas, boolean nearBoundary) {}
