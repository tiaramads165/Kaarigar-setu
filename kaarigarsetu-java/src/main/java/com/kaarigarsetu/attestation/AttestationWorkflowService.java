package com.kaarigarsetu.attestation;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.DuplicateAttestationException;
import com.kaarigarsetu.exception.ExternalServiceException;
import com.kaarigarsetu.exception.InvalidOTPException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.ml.MlGateway;
import com.kaarigarsetu.model.AttestationDetail;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.OtpSender;
import com.kaarigarsetu.security.OtpService;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.security.RateLimiter;
import com.kaarigarsetu.trust.TrustScoreService;
import com.kaarigarsetu.util.Json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Employer attests a worker's completed work.
 *
 * <p>request-otp: the (logged-in) employer names a worker and describes the work; an OTP is sent to the employer's own
 * phone as a step-up proof that the person at the keyboard is the employer. verify-otp: the code is checked
 * (5 attempts, 5 minutes, single use), the attestation is fraud-scored, and then it is either approved (trust edge
 * created, trust recomputed, credential re-issued) or flagged for a human moderator. If the ML service is down the
 * attestation fails SAFE, into human review, rather than being silently approved or lost.
 */
public class AttestationWorkflowService {
    private static final Duration FEATURE_WINDOW = Duration.ofHours(24);
    /** "No previous attestation" must look ordinary to the model (one day), not like an extreme ten-day gap. */
    private static final double FIRST_ATTESTATION_GAP_MINUTES = 1_440.0;
    /** Long gaps are all "slow and normal"; capping keeps them away from the model's extreme tail. */
    private static final double MAX_GAP_MINUTES = 10_080.0;
    private static final Duration WINDOW = Duration.ofHours(1);

    public record Requested(long attestationId, String status) {}

    public record Decision(long attestationId, String status, boolean flagged, double anomalyScore) {}

    private final AttestationDao attestationDao;
    private final EmployerDao employerDao;
    private final WorkerDao workerDao;
    private final FraudFlagDao fraudFlagDao;
    private final TrustEdgeDao trustEdgeDao;
    private final OtpService otp;
    private final OtpSender sender;
    private final PhoneProtector phones;
    private final MlGateway ml;
    private final TrustScoreService trust;
    private final CredentialService credentials;
    private final AuditLogger audit;
    private final RateLimiter limiter;
    private final Clock clock;
    private final Transactions transactions;

    public AttestationWorkflowService(AttestationDao attestationDao, EmployerDao employerDao, WorkerDao workerDao, FraudFlagDao fraudFlagDao,
                                      TrustEdgeDao trustEdgeDao, OtpService otp, OtpSender sender, PhoneProtector phones, MlGateway ml,
                                      TrustScoreService trust, CredentialService credentials, AuditLogger audit, RateLimiter limiter,
                                      Clock clock, Transactions transactions) {
        this.attestationDao = attestationDao;
        this.employerDao = employerDao;
        this.workerDao = workerDao;
        this.fraudFlagDao = fraudFlagDao;
        this.trustEdgeDao = trustEdgeDao;
        this.otp = otp;
        this.sender = sender;
        this.phones = phones;
        this.ml = ml;
        this.trust = trust;
        this.credentials = credentials;
        this.audit = audit;
        this.limiter = limiter;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Requested requestOtp(long employerId, String rawWorkerPhone, String jobRole, Integer durationDays, Boolean wouldHireAgain,
                                LocalDate workDate, String clientIp, String deviceHash) {
        return requestOtp(employerId, rawWorkerPhone, jobRole, durationDays, wouldHireAgain, workDate, clientIp, deviceHash, null, null, null);
    }

    /** @param ratingQuality, ratingPunctuality, ratingConduct the employer's 1 (poor) to 5 (excellent) ratings; null when not given */
    public Requested requestOtp(long employerId, String rawWorkerPhone, String jobRole, Integer durationDays, Boolean wouldHireAgain,
                                LocalDate workDate, String clientIp, String deviceHash,
                                Integer ratingQuality, Integer ratingPunctuality, Integer ratingConduct) {
        for (Integer rating : new Integer[]{ratingQuality, ratingPunctuality, ratingConduct}) {
            if (rating != null && (rating < 1 || rating > 5)) throw new ValidationException("Ratings must be 1 to 5");
        }
        String role = cleanRole(jobRole);
        if (durationDays != null && (durationDays < 0 || durationDays > 3650)) throw new ValidationException("Duration out of range");
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        // The server counts days in UTC, but an employer in India is already on tomorrow's date between 00:00 and 05:30 local time. One day of slack keeps
        // "worked today" valid in every time zone while still refusing a date in the real future.
        if (workDate == null || workDate.isAfter(today.plusDays(1)) || workDate.isBefore(today.minusDays(365))) throw new ValidationException("Work date out of range");
        String ipHash = phones.ipHash(clientIp);
        limiter.check("att:req:employer:" + employerId, 10, WINDOW);
        limiter.check("att:req:ip:" + ipHash, 30, WINDOW);

        Employer employer = employerDao.findById(employerId).orElseThrow(() -> new NotFoundException("Employer not found"));
        String workerKey = phones.lookupKey(PhoneProtector.normalize(rawWorkerPhone));
        Worker worker = workerDao.findByPhone(workerKey).orElseThrow(() -> new NotFoundException("Worker not found"));
        if (workerKey.equals(employer.phone())) throw new ValidationException("You cannot attest your own work");

        String employerPhone = phones.decrypt(employerDao.findPhoneEncrypted(employerId).orElseThrow(() -> new NotFoundException("Employer not found")));
        AttestationDetail created;
        OtpService.Issued issued;
        try {
            record Pair(AttestationDetail detail, OtpService.Issued issued) {}
            Pair pair = transactions.inTransaction(() -> {
                if (attestationDao.existsLive(worker.workerId(), employerId)) {
                    throw new DuplicateAttestationException("An attestation already exists for this worker and employer");
                }
                AttestationDetail row = attestationDao.insertWorkflow(worker.workerId(), employerId, "pending", role, durationDays,
                        wouldHireAgain, workDate, ipHash, deviceHash, ratingQuality, ratingPunctuality, ratingConduct);
                OtpService.Issued code = otp.issueDetailed("attestation:" + row.attestationId(), ipHash);
                attestationDao.updateOtpHash(row.attestationId(), code.storedHash());
                return new Pair(row, code);
            });
            created = pair.detail();
            issued = pair.issued();
        } catch (DataAccessException exception) {
            if (exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                throw new DuplicateAttestationException("An attestation already exists for this worker and employer");
            }
            throw exception;
        }
        sender.send(employerPhone, "attestation", issued.code());
        audit.log("ATTESTATION_OTP_REQUESTED", "employer:" + employerId, "attestation=" + created.attestationId() + " worker=" + worker.workerId());
        return new Requested(created.attestationId(), created.status());
    }

    public Decision verifyOtp(long employerId, long attestationId, String code, String clientIp) {
        AttestationDetail detail = attestationDao.findDetail(attestationId)
                .filter(d -> d.employerId() == employerId).orElseThrow(() -> new NotFoundException("Attestation not found"));
        if (!"OTP_PENDING".equals(detail.status())) throw new ConflictException("Attestation is not awaiting a code");
        limiter.check("att:verify:employer:" + employerId, 30, WINDOW);

        try {
            otp.verify("attestation:" + attestationId, code);
        } catch (InvalidOTPException exception) {
            audit.log("ATTESTATION_OTP_FAILED", "employer:" + employerId, "attestation=" + attestationId + " reason=" + exception.getMessage());
            throw exception;
        }

        MlGateway.FraudFeatures features = features(detail);
        MlGateway.FraudResult fraud;
        String note = null;
        try {
            fraud = ml.fraudCheck(features);
        } catch (ExternalServiceException unavailable) {
            fraud = new MlGateway.FraudResult(0.5, true);   // fail safe: a human decides, nothing is auto-approved
            note = "ml_unavailable";
        }
        boolean flagged = fraud.flagged();
        BigDecimal anomaly = BigDecimal.valueOf(fraud.anomalyScore()).setScale(4, RoundingMode.HALF_UP);
        String evidence = featureJson(features, note);

        transactions.inTransaction(() -> {
            if (!attestationDao.decide(attestationId, flagged ? "PENDING_REVIEW" : "APPROVED", anomaly)) {
                throw new ConflictException("Attestation was already decided");
            }
            if (flagged) {
                fraudFlagDao.insertWithFeatures(attestationId, anomaly, evidence);
            } else {
                trustEdgeDao.insertAttestationEdge(employerId, detail.workerId(), AttestationRules.edgeWeight(detail.wouldHireAgain()), attestationId);
            }
            return null;
        });

        audit.log(flagged ? "ATTESTATION_FLAGGED" : "ATTESTATION_APPROVED", "employer:" + employerId,
                "attestation=" + attestationId + " worker=" + detail.workerId() + " anomaly=" + anomaly);
        if (!flagged) refreshTrustAndCredential(detail.workerId());
        return new Decision(attestationId, flagged ? "PENDING_REVIEW" : "APPROVED", flagged, fraud.anomalyScore());
    }

    public List<AttestationDetail> forEmployer(long employerId) {
        return attestationDao.findByEmployer(employerId);
    }

    public List<AttestationDetail> forWorker(long workerId) {
        return attestationDao.findByWorker(workerId);
    }

    /** Trust and credential refresh must never undo an approved attestation, so failures are logged, not thrown. */
    void refreshTrustAndCredential(long workerId) {
        try {
            trust.recompute();
            credentials.reissueIfEligible(workerId, "system:attestation");
        } catch (RuntimeException failure) {
            System.getLogger("ks.attestation").log(System.Logger.Level.ERROR, "Trust/credential refresh failed: {0}", failure.toString());
            audit.log("TRUST_REFRESH_FAILED", "system", "worker=" + workerId);
        }
    }

    private MlGateway.FraudFeatures features(AttestationDetail detail) {
        AttestationDao.NetworkHashes hashes = attestationDao.findNetworkHashes(detail.attestationId())
                .orElse(new AttestationDao.NetworkHashes("", ""));
        LocalDateTime since = LocalDateTime.now(clock.withZone(ZoneOffset.UTC)).minus(FEATURE_WINDOW);
        double ip = hashes.ipHash() == null ? 1 : Math.max(1, attestationDao.countFromIpSince(hashes.ipHash(), since));
        double device = hashes.deviceHash() == null ? 1 : Math.max(1, attestationDao.countFromDeviceSince(hashes.deviceHash(), since));
        double minutes = attestationDao.minutesSincePreviousByEmployer(detail.employerId(), detail.attestationId()).orElse(FIRST_ATTESTATION_GAP_MINUTES);
        return new MlGateway.FraudFeatures(ip, device, Math.min(MAX_GAP_MINUTES, Math.max(0, minutes)), attestationDao.reciprocityRatio(detail.employerId()));
    }

    private static String featureJson(MlGateway.FraudFeatures f, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ip_frequency", f.ipFrequency());
        m.put("device_frequency", f.deviceFrequency());
        m.put("minutes_since_previous", Math.round(f.minutesSincePrevious() * 10) / 10.0);
        m.put("reciprocity_ratio", Math.round(f.reciprocityRatio() * 1000) / 1000.0);
        if (note != null) m.put("note", note);
        return Json.toJson(m);
    }

    private static String cleanRole(String raw) {
        if (raw == null) throw new ValidationException("Job role is required");
        String role = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        int length = role.codePointCount(0, role.length());
        if (length < 2 || length > 80) throw new ValidationException("Job role must be 2 to 80 characters");
        return role;
    }
}
