package com.kaarigarsetu.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The moderator's view of one flagged attestation: ids and facts only, never a phone number or a name. */
public record FlaggedAttestation(long flagId, long attestationId, BigDecimal anomalyScore, String features,
                                 long workerId, long employerId, String jobRole, String reviewerDecision,
                                 LocalDateTime createdAt) {
}
