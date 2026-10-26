package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One answered question inside an assessment attempt (five of these make a full assessment). */
public record AssessmentAnswer(Long assessmentId, long workerId, String trade, UUID attemptId, int questionNo, String language,
                               String transcript, BigDecimal score, String feedback, boolean needsReview,
                               String reviewReason, String provider, LocalDateTime createdAt) {
}
