package com.kaarigarsetu.model;

import java.time.LocalDateTime;

/**
 * A step that moves trust (vetting an employer, ruling on a dispute) proposed by one moderator and waiting for a different one. {@code verdict} is set for
 * a dispute ruling (UPHELD or REMOVED). {@code decidedBy} is empty until someone approves, rejects or withdraws it, and stays empty when it expires.
 */
public record ModeratorAction(long actionId, String type, long targetId, String verdict, long proposedBy, String proposedByUsername, LocalDateTime proposedAt,
                              String status, Long decidedBy, String decidedByUsername, LocalDateTime decidedAt) {
    public static final String VET_EMPLOYER = "VET_EMPLOYER";
    public static final String DISPUTE_DECISION = "DISPUTE_DECISION";
}
