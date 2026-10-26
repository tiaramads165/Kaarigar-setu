package com.kaarigarsetu.console;

import java.util.List;

/** What the console needs from the backend. The JavaFX UI depends on this interface, not on HTTP. */
public interface ModeratorApi {
    /** Step 1: service-account password. The backend then issues an OTP (to the moderator's channel). */
    void login(String username, char[] password);

    /** Step 2: the one-time code. After this the session may read the queue. */
    void verifyOtp(String code);

    List<FlaggedRow> flagged();

    /** @param decision "VALID" (approve the attestation) or "FRAUD" (reject it) @return true if it was approved */
    boolean decide(long flagId, String decision);

    /** @return a human-readable result of the audit-log integrity check. */
    String auditCheck();

    /** Employers for the vetting list; with {@code onlyUnvetted} the ones nobody has vetted yet. */
    List<EmployerRow> employers(boolean onlyUnvetted);

    /** A moderator vouches for an employer after an identity or business check done outside the application. With two-person approval this only proposes. */
    ActionResult vetEmployer(long employerId);

    /** Confirmations a worker has disputed and no moderator has ruled on yet, oldest first. */
    List<DisputeRow> disputes();

    /** @param decision "UPHELD" (the confirmation counts again) or "REMOVED" (it stays out of the score). With two-person approval this only proposes. */
    ActionResult decideDispute(long attestationId, String decision);

    /** The signed-in moderator. */
    ModeratorIdentity me();

    /** Proposals waiting for a second moderator. */
    List<PendingRow> pending();

    /** Approve a proposal someone else made: it takes effect now. */
    void approve(long actionId);

    /** Reject a proposal someone else made, or withdraw your own. */
    void reject(long actionId);

    /** Security events, alerts and audit integrity for the Security tab. */
    SecurityOverview securityOverview();

    void logout();
}
