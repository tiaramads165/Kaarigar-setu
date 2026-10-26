package com.kaarigarsetu.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Common scoring contract for a trade assessment.  Concrete services provide
 * trade-specific feedback while this class keeps the scoring rule consistent.
 */
public abstract class AssessmentService {
    /** Every assessment is exactly this many questions (the rubric files carry the same number). One definition, used by scoring, completion and credential rules. */
    public static final int QUESTIONS_PER_ASSESSMENT = 10;

    public abstract String supportedTrade();

    public final AssessmentResult assess(List<String> answers) {
        Objects.requireNonNull(answers, "answers must not be null");
        if (answers.size() != QUESTIONS_PER_ASSESSMENT) {
            throw new IllegalArgumentException("An assessment must contain exactly " + QUESTIONS_PER_ASSESSMENT + " answers");
        }

        long answered = answers.stream().filter(this::hasContent).count();
        BigDecimal score = BigDecimal.valueOf(answered * 100.0 / answers.size())
                .setScale(2, RoundingMode.HALF_UP);
        return new AssessmentResult(supportedTrade(), score, feedbackFor(score));
    }

    /**
     * Template step for the ML-scored pipeline: the ten per-question scores (0-100, from the scoring service) are
     * averaged by this one shared rule, and each trade subclass supplies its own feedback wording.
     */
    public final AssessmentResult summarize(List<BigDecimal> perQuestionScores) {
        Objects.requireNonNull(perQuestionScores, "scores must not be null");
        if (perQuestionScores.size() != QUESTIONS_PER_ASSESSMENT) {
            throw new IllegalArgumentException("An assessment must contain exactly " + QUESTIONS_PER_ASSESSMENT + " scores");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal score : perQuestionScores) {
            if (score == null || score.signum() < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("each score must be between 0 and 100");
            }
            total = total.add(score);
        }
        BigDecimal mean = total.divide(BigDecimal.valueOf(perQuestionScores.size()), 2, RoundingMode.HALF_UP);
        return new AssessmentResult(supportedTrade(), mean, feedbackFor(mean));
    }

    protected abstract String feedbackFor(BigDecimal score);

    private boolean hasContent(String answer) {
        return answer != null && !answer.isBlank();
    }
}
