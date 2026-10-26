package com.kaarigarsetu.assessment;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves a concrete assessment service at runtime from the requested trade. */
public class AssessmentScoringDispatcher {
    private final List<AssessmentService> services;

    public AssessmentScoringDispatcher(List<AssessmentService> services) {
        this.services = List.copyOf(Objects.requireNonNull(services, "services must not be null"));
    }

    public AssessmentResult score(String trade, List<String> answers) {
        return resolve(trade).assess(answers);
    }

    /** Polymorphic: the trade's own subclass averages the ML scores and writes its own feedback. */
    public AssessmentResult summarize(String trade, List<java.math.BigDecimal> perQuestionScores) {
        return resolve(trade).summarize(perQuestionScores);
    }

    private AssessmentService resolve(String trade) {
        String normalizedTrade = normalize(trade);
        return services.stream()
                .filter(service -> normalize(service.supportedTrade()).equals(normalizedTrade))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported trade: " + trade));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("trade must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
