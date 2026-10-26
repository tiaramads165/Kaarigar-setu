package com.kaarigarsetu.console;

/** A step one moderator proposed that is waiting for a different moderator, as the Approvals tab shows it. */
public record PendingRow(long actionId, String type, String target, String verdict, String proposedBy, String proposedAt, String expiresAt, boolean canDecide) {

    /** Plain-language name of what is proposed. */
    public String what() {
        return switch (type) {
            case "VET_EMPLOYER" -> "Vet employer";
            case "DISPUTE_DECISION" -> "UPHELD".equals(verdict) ? "Dispute: keep the confirmation" : "Dispute: remove the confirmation";
            default -> type;
        };
    }

    /** What the details pane shows when a row is selected. */
    public String explain() {
        return what() + ": " + target + "\nProposed by " + proposedBy + " at " + proposedAt + "; it expires at " + expiresAt + ".\n"
                + (canDecide ? "You did not propose this, so you may approve it (it then takes effect at once) or reject it."
                        : "You proposed this: it needs a DIFFERENT moderator to approve it. You can withdraw it.");
    }
}
