package com.kaarigarsetu.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A confirmation about a worker as the worker sees it: who gave it, what they said, and what the worker did about it. The employer is identified by the
 * business name given at registration, never by phone number.
 *
 * @param workerResponse   null (no answer yet), ACCEPTED or DISPUTED
 * @param disputeDecision  null while open, then UPHELD (it counts) or REMOVED (it does not)
 */
public record WorkerConfirmation(long attestationId, long employerId, String employerName, String jobRole, LocalDate workDate, Integer ratingQuality,
                                 Integer ratingPunctuality, Integer ratingConduct, Boolean wouldHireAgain, LocalDateTime decidedAt, String workerResponse,
                                 LocalDateTime respondedAt, String disputeReason, String disputeDecision) {
    /** True while the confirmation is held out of every score. */
    public boolean heldOut() {
        return "DISPUTED".equals(workerResponse) && !"UPHELD".equals(disputeDecision);
    }
}
