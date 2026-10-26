package com.kaarigarsetu.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns the ten per-question scores into a skill profile. A pure function (no database, no network), so its rules are easy to test and to change:
 * <ul>
 *   <li>the overall score is supplied by the caller (the Template Method in {@link AssessmentService}), so the headline number is unchanged;</li>
 *   <li>the band comes from the configured thresholds;</li>
 *   <li>each skill area's score is the plain mean of the questions that measure that area (questions weigh equally);</li>
 *   <li>"near boundary" is true when the overall score is within the margin of either threshold.</li>
 * </ul>
 */
public final class SkillScoring {
    /** Display order of the areas; any area not listed here (a new rubric) is appended after these in first-seen order. */
    public static final List<String> AREA_ORDER = List.of("tools", "materials", "experience", "knowledge", "troubleshooting", "planning", "customer");

    public record QuestionScore(String area, BigDecimal score) {}

    private SkillScoring() {}

    public static SkillProfile profile(BigDecimal overall, List<QuestionScore> questions, BigDecimal pass, BigDecimal borderline, BigDecimal margin) {
        Objects.requireNonNull(overall, "overall");
        if (questions == null || questions.isEmpty()) throw new IllegalArgumentException("no question scores");
        if (borderline.compareTo(pass) >= 0 || margin.signum() < 0) throw new IllegalArgumentException("thresholds must satisfy 0 <= borderline < pass and margin >= 0");

        Map<String, List<BigDecimal>> byArea = new LinkedHashMap<>();
        for (QuestionScore q : questions) byArea.computeIfAbsent(q.area() == null || q.area().isBlank() ? "knowledge" : q.area(), k -> new ArrayList<>()).add(q.score());
        List<String> order = new ArrayList<>(AREA_ORDER.stream().filter(byArea::containsKey).toList());
        byArea.keySet().stream().filter(a -> !order.contains(a)).forEach(order::add);

        Map<String, BigDecimal> areas = new LinkedHashMap<>();
        for (String area : order) {
            List<BigDecimal> scores = byArea.get(area);
            areas.put(area, scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
        }
        boolean near = overall.subtract(pass).abs().compareTo(margin) < 0 || overall.subtract(borderline).abs().compareTo(margin) < 0;
        return new SkillProfile(overall, Band.of(overall, pass, borderline), java.util.Collections.unmodifiableMap(areas), near);
    }
}
