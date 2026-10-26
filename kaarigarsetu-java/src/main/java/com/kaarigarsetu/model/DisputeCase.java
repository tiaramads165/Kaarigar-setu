package com.kaarigarsetu.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A worker's dispute as a moderator sees it. No phone numbers; the worker is only an id, the employer a business name. */
public record DisputeCase(long attestationId, long workerId, long employerId, String employerName, String jobRole, LocalDate workDate, Integer ratingQuality,
                          Integer ratingPunctuality, Integer ratingConduct, Boolean wouldHireAgain, String reason, LocalDateTime disputedAt) {
}
