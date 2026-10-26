package com.kaarigarsetu.attestation;

import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.WorkerConfirmation;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The worker's say in what employers write about them.
 *
 * <p>The code that authorises a confirmation goes to the EMPLOYER's phone, so the worker is never asked. This service lets the worker see each confirmation
 * and either ACCEPT it (a record, nothing else changes) or DISPUTE it with a reason. A dispute holds the confirmation out of every score until a moderator
 * rules (see ModeratorService.decideDispute). Acceptance is deliberately optional: if it were required, a worker could refuse every bad rating and the
 * trust score would be a number the worker controls. Disputing is a request for a ruling, so it is limited: a short window after the confirmation, and a
 * small number of unresolved disputes at a time.
 */
public final class ConfirmationResponseService {
    static final int MIN_REASON = 10;
    static final int MAX_REASON = 300;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^ ]]+");

    /** A confirmation plus what the worker may still do about it. */
    public record View(WorkerConfirmation confirmation, boolean canRespond, int daysLeftToDispute) {
    }

    private final AttestationDao attestations;
    private final TrustScoreService trust;
    private final CredentialService credentials;
    private final AuditLogger audit;
    private final AppSettings settings;
    private final Clock clock;

    public ConfirmationResponseService(AttestationDao attestations, TrustScoreService trust, CredentialService credentials, AuditLogger audit, AppSettings settings, Clock clock) {
        this.attestations = Objects.requireNonNull(attestations);
        this.trust = Objects.requireNonNull(trust);
        this.credentials = Objects.requireNonNull(credentials);
        this.audit = Objects.requireNonNull(audit);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<View> list(long workerId) {
        return attestations.findConfirmationsForWorker(workerId).stream().map(this::view).toList();
    }

    public void accept(long workerId, long attestationId) {
        WorkerConfirmation confirmation = own(workerId, attestationId);
        if (confirmation.workerResponse() != null) throw new ConflictException("You have already answered this confirmation");
        if (!attestations.acceptByWorker(attestationId, workerId)) throw new ConflictException("You have already answered this confirmation");
        audit.log("ATTESTATION_ACCEPTED", "worker:" + workerId, "attestation=" + attestationId);
    }

    public void dispute(long workerId, long attestationId, String rawReason) {
        String reason = rawReason == null ? "" : CONTROL.matcher(rawReason).replaceAll(" ").strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            throw new ValidationException("Please say what is wrong, in " + MIN_REASON + " to " + MAX_REASON + " characters");
        }
        WorkerConfirmation confirmation = own(workerId, attestationId);
        if (confirmation.workerResponse() != null) throw new ConflictException("You have already answered this confirmation");
        if (!view(confirmation).canRespond()) throw new ConflictException("The time to dispute this confirmation has passed");
        if (attestations.countOpenDisputes(workerId) >= settings.disputeMaxOpen()) throw new ConflictException("Too many of your disputes are still waiting for a ruling");
        // the same limits are enforced again inside the single UPDATE, so two simultaneous requests cannot both pass
        if (!attestations.disputeByWorker(attestationId, workerId, reason, settings.disputeWindowDays(), settings.disputeMaxOpen())) {
            throw new ConflictException("This confirmation can no longer be disputed");
        }
        audit.log("ATTESTATION_DISPUTED", "worker:" + workerId, "attestation=" + attestationId);        // ids only: the reason is free text and stays out of the audit trail
        refresh(workerId);
    }

    private WorkerConfirmation own(long workerId, long attestationId) {
        return attestations.findConfirmationForWorker(workerId, attestationId).orElseThrow(() -> new NotFoundException("Confirmation not found"));
    }

    private View view(WorkerConfirmation c) {
        LocalDateTime decided = c.decidedAt();
        long ageDays = decided == null ? 0 : Duration.between(decided, LocalDateTime.now(clock.withZone(ZoneOffset.UTC))).toDays();
        int left = (int) Math.max(0, settings.disputeWindowDays() - ageDays);
        return new View(c, c.workerResponse() == null && left > 0, c.workerResponse() == null ? left : 0);
    }

    private void refresh(long workerId) {
        try {
            trust.recompute();
            credentials.reissueIfEligible(workerId, "system:dispute");
        } catch (RuntimeException failure) {
            audit.log("TRUST_REFRESH_FAILED", "system", "worker=" + workerId);
        }
    }
}
