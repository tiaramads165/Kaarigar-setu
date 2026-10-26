package com.kaarigarsetu.moderator;

import com.kaarigarsetu.attestation.AttestationRules;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.ModeratorActionDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.ForbiddenException;
import com.kaarigarsetu.model.Moderator;
import com.kaarigarsetu.model.ModeratorAction;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.FlaggedAttestation;
import com.kaarigarsetu.model.FraudFlag;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;

import java.util.List;
import java.util.Locale;

/**
 * The human-in-the-loop: fraud-flagged attestations, employer vetting and worker disputes, all attributed to the NAMED moderator who did them.
 *
 * <p>Vetting an employer and ruling on a dispute move trust, so with two-person approval on (TWO_PERSON_ACTIONS, the default) one moderator only PROPOSES
 * and the step takes effect when a DIFFERENT moderator approves it. One stolen login, or one fooled or dishonest moderator, can then no longer vet a fake
 * employer or remove a true rating alone. The rule is checked here, in the approving UPDATE (ModeratorActionDao.approve) and by a CHECK constraint.
 */
public class ModeratorService {
    /** What a request did: carried out at once, or queued for a second moderator (then {@code actionId} identifies the proposal). */
    public record Outcome(boolean executed, Long actionId) {
        static Outcome done() { return new Outcome(true, null); }
        static Outcome queued(long actionId) { return new Outcome(false, actionId); }
    }

    /** A proposal as the approvals screen shows it. */
    public record PendingView(ModeratorAction action, String target, boolean canDecide, java.time.LocalDateTime expiresAt) {}

    private final FraudFlagDao fraudFlagDao;
    private final AttestationDao attestationDao;
    private final TrustEdgeDao trustEdgeDao;
    private final EmployerDao employerDao;
    private final TrustScoreService trust;
    private final CredentialService credentials;
    private final AuditLogger audit;
    private final Transactions transactions;
    private final ModeratorActionDao actions;
    private final AppSettings settings;

    public ModeratorService(FraudFlagDao fraudFlagDao, AttestationDao attestationDao, TrustEdgeDao trustEdgeDao, EmployerDao employerDao,
                            TrustScoreService trust, CredentialService credentials, AuditLogger audit, Transactions transactions,
                            ModeratorActionDao actions, AppSettings settings) {
        this.fraudFlagDao = fraudFlagDao;
        this.attestationDao = attestationDao;
        this.trustEdgeDao = trustEdgeDao;
        this.employerDao = employerDao;
        this.trust = trust;
        this.credentials = credentials;
        this.audit = audit;
        this.transactions = transactions;
        this.actions = actions;
        this.settings = settings;
    }

    /** Disputes the workers have raised, waiting for a ruling (no phone numbers). */
    public List<com.kaarigarsetu.model.DisputeCase> disputes() {
        return attestationDao.findOpenDisputes();
    }

    private boolean needsSecondPerson(String type) {
        return settings.twoPersonActions().contains(type);
    }

    /**
     * Rules on a worker's dispute: UPHELD (the confirmation was fair and counts again) or REMOVED (it never counts). With two-person approval on this only
     * PROPOSES the ruling. A dispute is ruled on once.
     */
    public Outcome decideDispute(long attestationId, String decision, Moderator actor) {
        String verdict = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!verdict.equals("UPHELD") && !verdict.equals("REMOVED")) throw new ValidationException("Decision must be UPHELD or REMOVED");
        if (!needsSecondPerson(ModeratorAction.DISPUTE_DECISION)) {
            long workerId = applyDispute(attestationId, verdict, actor.username());
            afterDispute(attestationId, verdict, workerId, "moderator:" + actor.username(), "");
            return Outcome.done();
        }
        if (!attestationDao.hasOpenDispute(attestationId)) throw new NotFoundException("No open dispute with this id");
        return propose(ModeratorAction.DISPUTE_DECISION, attestationId, verdict, actor);
    }

    private long applyDispute(long attestationId, String verdict, String decidedBy) {
        return attestationDao.decideDispute(attestationId, verdict, decidedBy).orElseThrow(() -> new NotFoundException("No open dispute with this id"));
    }

    private void afterDispute(long attestationId, String verdict, long workerId, String actor, String how) {
        audit.log("DISPUTE_DECIDED", actor, "attestation=" + attestationId + " decision=" + verdict + how);
        try {
            trust.recompute();
            credentials.reissueIfEligible(workerId, "system:dispute-ruling");
        } catch (RuntimeException failure) {
            audit.log("TRUST_REFRESH_FAILED", "system", "worker=" + workerId);
        }
    }

    /** Employers for the vetting list (no phone numbers). */
    public List<EmployerDao.EmployerSummary> employers(boolean onlyUnvetted) {
        return employers(onlyUnvetted, 0);
    }

    /** A page of 200: {@code offset} skips that many first (the list is long once there are many employers). */
    public List<EmployerDao.EmployerSummary> employers(boolean onlyUnvetted, int offset) {
        return employerDao.listForVetting(onlyUnvetted, 200, Math.max(0, offset));
    }

    /**
     * A moderator vouches for an employer after an identity or business check done OUTSIDE this application (the evidence is not stored here, only who vetted
     * and when). Vetted employers are the trust anchor of the Excellent band, so with two-person approval on this only PROPOSES the vetting, and trust scores and
     * the credentials of the workers they confirmed are refreshed when a second moderator approves.
     */
    public Outcome vetEmployer(long employerId, Moderator actor) {
        if (!needsSecondPerson(ModeratorAction.VET_EMPLOYER)) {
            applyVet(employerId, actor.username());
            afterVet(employerId, "moderator:" + actor.username(), "");
            return Outcome.done();
        }
        var employer = employerDao.findById(employerId).orElseThrow(() -> new NotFoundException("Employer not found"));
        if (employer.verified()) throw new ConflictException("This employer is already vetted");
        return propose(ModeratorAction.VET_EMPLOYER, employerId, null, actor);
    }

    private void applyVet(long employerId, String vettedBy) {
        if (!employerDao.vet(employerId, vettedBy)) throw new NotFoundException("Employer not found");
    }

    private void afterVet(long employerId, String actor, String how) {
        audit.log("EMPLOYER_VETTED", actor, "employer=" + employerId + how);
        try {
            trust.recompute();
            for (long workerId : attestationDao.findApprovedWorkerIds(employerId)) credentials.reissueIfEligible(workerId, "system:vetting");
        } catch (RuntimeException failure) {
            audit.log("TRUST_REFRESH_FAILED", "system", "employer=" + employerId);
        }
    }

    // ------------------------------------------------------------------ two-person approval

    private Outcome propose(String type, long targetId, String verdict, Moderator actor) {
        actions.expireOlderThan(settings.twoPersonLifetimeHours());
        long id = actions.propose(type, targetId, verdict, actor.moderatorId())
                .orElseThrow(() -> new ConflictException("A proposal for this is already waiting for a second moderator"));
        audit.log("MODERATOR_ACTION_PROPOSED", "moderator:" + actor.username(), "action=" + id + " type=" + type + " target=" + targetId + (verdict == null ? "" : " verdict=" + verdict));
        return Outcome.queued(id);
    }

    /** What is waiting for a second moderator, for the approvals screen. Proposals past their lifetime are expired first. */
    public List<PendingView> pending(Moderator me) {
        int lifetime = settings.twoPersonLifetimeHours();
        actions.expireOlderThan(lifetime);
        java.util.Map<Long, String> disputes = new java.util.HashMap<>();
        for (var d : attestationDao.findOpenDisputes()) disputes.put(d.attestationId(), "confirmation " + d.attestationId() + " from " + d.employerName() + " (" + d.jobRole() + "), worker " + d.workerId());
        List<PendingView> out = new java.util.ArrayList<>();
        for (ModeratorAction action : actions.findPending(lifetime)) {
            String target = action.type().equals(ModeratorAction.VET_EMPLOYER)
                    ? "employer " + action.targetId() + " " + employerDao.findById(action.targetId()).map(e -> "(" + e.name() + ")").orElse("(not found)")
                    : disputes.getOrDefault(action.targetId(), "confirmation " + action.targetId() + " (no longer disputed)");
            out.add(new PendingView(action, target, action.proposedBy() != me.moderatorId(), action.proposedAt().plusHours(lifetime)));
        }
        return out;
    }

    /**
     * A moderator OTHER than the proposer carries the step out. The "different person" rule is enforced here (with an audited refusal, so an attempt is visible
     * on the Security tab), again by the approving UPDATE and again by the table's CHECK constraint. Marking it executed and doing it are one transaction.
     */
    public void approve(long actionId, Moderator approver) {
        ModeratorAction found = actions.findById(actionId).orElseThrow(() -> new NotFoundException("No such proposal"));
        if (found.proposedBy() == approver.moderatorId()) {
            audit.log("MODERATOR_SELF_APPROVAL_REFUSED", "moderator:" + approver.username(), "action=" + actionId + " type=" + found.type() + " target=" + found.targetId());
            throw new ForbiddenException("You proposed this: a different moderator must approve it");
        }
        int lifetime = settings.twoPersonLifetimeHours();
        String label = found.proposedByUsername() + " (approved by " + approver.username() + ")";
        long[] worker = new long[1];
        transactions.inTransaction(() -> {
            if (!actions.approve(actionId, approver.moderatorId(), lifetime)) {
                ModeratorAction now = actions.findById(actionId).orElseThrow(() -> new NotFoundException("No such proposal"));
                throw new ConflictException(now.status().equals("PENDING") ? "This proposal has expired" : "This proposal was already " + now.status().toLowerCase(Locale.ROOT));
            }
            if (found.type().equals(ModeratorAction.VET_EMPLOYER)) applyVet(found.targetId(), label);
            else worker[0] = applyDispute(found.targetId(), found.verdict(), label);
            return null;
        });
        audit.log("MODERATOR_ACTION_APPROVED", "moderator:" + approver.username(), "action=" + actionId + " type=" + found.type() + " target=" + found.targetId()
                + " proposed_by=" + found.proposedByUsername());
        String how = " proposed_by=" + found.proposedByUsername() + " approved_by=" + approver.username();
        if (found.type().equals(ModeratorAction.VET_EMPLOYER)) afterVet(found.targetId(), "moderator:" + approver.username(), how);
        else afterDispute(found.targetId(), found.verdict(), worker[0], "moderator:" + approver.username(), how);
    }

    /** The proposer withdraws their own proposal, or a different moderator rejects it. */
    public void reject(long actionId, Moderator moderator) {
        ModeratorAction found = actions.findById(actionId).orElseThrow(() -> new NotFoundException("No such proposal"));
        boolean own = found.proposedBy() == moderator.moderatorId();
        boolean closed = own ? actions.withdraw(actionId, moderator.moderatorId()) : actions.reject(actionId, moderator.moderatorId());
        if (!closed) throw new ConflictException("This proposal was already " + actions.findById(actionId).map(a -> a.status().toLowerCase(Locale.ROOT)).orElse("closed"));
        audit.log(own ? "MODERATOR_ACTION_WITHDRAWN" : "MODERATOR_ACTION_REJECTED", "moderator:" + moderator.username(),
                "action=" + actionId + " type=" + found.type() + " target=" + found.targetId() + " proposed_by=" + found.proposedByUsername());
    }

    public List<FlaggedAttestation> queue() {
        return fraudFlagDao.findPendingQueue();
    }

    /**
     * @param decision "VALID" (attestation is genuine, approve it) or "FRAUD" (reject it)
     * @return true if the attestation was approved
     */
    public boolean decide(long flagId, String decision, String moderator) {
        String verdict = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!verdict.equals("VALID") && !verdict.equals("FRAUD")) throw new ValidationException("Decision must be VALID or FRAUD");
        FraudFlag flag = fraudFlagDao.findById(flagId).orElseThrow(() -> new NotFoundException("Flag not found"));
        AttestationDetail detail = attestationDao.findDetail(flag.attestationId()).orElseThrow(() -> new NotFoundException("Attestation not found"));
        boolean approve = verdict.equals("VALID");

        transactions.inTransaction(() -> {
            if (!fraudFlagDao.decideIfPending(flagId, verdict, moderator)) throw new ConflictException("Flag was already decided");
            if (!attestationDao.moderate(detail.attestationId(), approve ? "APPROVED" : "REJECTED")) {
                throw new ConflictException("Attestation is not awaiting review");
            }
            if (approve) {
                trustEdgeDao.insertAttestationEdge(detail.employerId(), detail.workerId(), AttestationRules.edgeWeight(detail.wouldHireAgain()), detail.attestationId());
            }
            return null;
        });
        audit.log("MODERATOR_DECISION", "moderator:" + moderator, "flag=" + flagId + " attestation=" + detail.attestationId() + " decision=" + verdict);
        if (approve) {
            try {
                trust.recompute();
                credentials.reissueIfEligible(detail.workerId(), "system:moderation");
            } catch (RuntimeException failure) {
                audit.log("TRUST_REFRESH_FAILED", "system", "worker=" + detail.workerId());
            }
        }
        return approve;
    }
}
