package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An attestation with the employer's structured feedback and the decision timestamps (migration 002 and 004 columns).
 * The three ratings are 1 (poor) to 5 (excellent) and null when the employer gave none (older attestations).
 */
public record AttestationDetail(long attestationId, long workerId, long employerId, String status, BigDecimal anomalyScore,
                                String jobRole, Integer durationDays, Boolean wouldHireAgain, LocalDate workDate,
                                LocalDateTime createdAt, LocalDateTime decidedAt,
                                Integer ratingQuality, Integer ratingPunctuality, Integer ratingConduct) {
    public AttestationDetail(long attestationId, long workerId, long employerId, String status, BigDecimal anomalyScore,
                             String jobRole, Integer durationDays, Boolean wouldHireAgain, LocalDate workDate,
                             LocalDateTime createdAt, LocalDateTime decidedAt) {
        this(attestationId, workerId, employerId, status, anomalyScore, jobRole, durationDays, wouldHireAgain, workDate, createdAt, decidedAt,
                null, null, null);
    }
}
