package com.kaarigarsetu.console;

/** A confirmation the worker disputes, as the moderator sees it: the worker's reason and the employer's side, no phone numbers. */
public record DisputeRow(long attestationId, long workerId, long employerId, String employerName, String jobRole, String workDate,
                         String ratings, String wouldHireAgain, String reason, String disputedAt) {

    /** What the details pane shows when a row is selected. */
    public String explain() {
        return "Worker " + workerId + " disputes a confirmation from " + employerName + " (employer " + employerId + ").\n"
                + "Job: " + jobRole + (workDate.isEmpty() ? "" : ", work date " + workDate) + "\n"
                + "Employer's ratings: " + ratings + "; would hire again: " + wouldHireAgain + "\n"
                + "Worker's reason: " + reason + "\n"
                + "Keep: the confirmation counts again. Remove: it never counts toward the trust score.";
    }
}
