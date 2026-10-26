package com.kaarigarsetu.worker;

import com.kaarigarsetu.assessment.TradeCatalog;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.ConsentRequiredException;
import com.kaarigarsetu.exception.DataAccessException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.CredentialRecord;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.PhoneProtector;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Worker registration (with DPDP consent) and the Skill & Trust Passport read model. */
public class WorkerService {
    public static final String CONSENT_VERSION = "2026-10-v1";
    private static final Set<String> LANGUAGES = Set.of("hi", "bn", "en");

    public record AnswerLine(int questionNo, BigDecimal score, boolean needsReview) {}

    public record Passport(long workerId, String name, String trade, String language, BigDecimal skillScore,
                           BigDecimal trustScore, int verifiedEmployerCount, UUID latestCredentialRef,
                           boolean latestCredentialRevoked, List<AnswerLine> latestAssessment,
                           String skillBand, Map<String, BigDecimal> skillAreas, boolean nearBoundary,
                           String trustBand, Map<String, Object> trustReport) {}

    private final WorkerDao workerDao;
    private final AssessmentDao assessmentDao;
    private final AttestationDao attestationDao;
    private final CredentialDao credentialDao;
    private final PhoneProtector phones;
    private final AuditLogger audit;
    private final Transactions transactions;
    private final TradeCatalog trades;

    public WorkerService(WorkerDao workerDao, AssessmentDao assessmentDao, AttestationDao attestationDao,
                         CredentialDao credentialDao, PhoneProtector phones, AuditLogger audit, Transactions transactions, TradeCatalog trades) {
        this.workerDao = workerDao;
        this.assessmentDao = assessmentDao;
        this.attestationDao = attestationDao;
        this.credentialDao = credentialDao;
        this.phones = phones;
        this.audit = audit;
        this.transactions = transactions;
        this.trades = trades;
    }

    public Worker register(String name, String rawPhone, String trade, String language, boolean consent) {
        if (!consent) throw new ConsentRequiredException("Consent is required before any personal data is recorded");
        String cleanName = cleanName(name);
        String canonicalTrade = trades.find(trade).map(TradeCatalog.Trade::name).orElseThrow(() -> new ValidationException("Unsupported trade"));
        String lang = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(lang)) throw new ValidationException("Unsupported language");
        String phone = PhoneProtector.normalize(rawPhone);
        String lookup = phones.lookupKey(phone);

        Worker created;
        try {
            created = transactions.inTransaction(() -> {
                if (workerDao.findByPhone(lookup).isPresent()) throw new ConflictException("Already registered");
                return workerDao.insertProtected(new Worker(null, cleanName, lookup, canonicalTrade, lang, BigDecimal.ZERO,
                        BigDecimal.ZERO, null), phones.encrypt(phone), CONSENT_VERSION);
            });
        } catch (DataAccessException exception) {
            if (exception.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                throw new ConflictException("Already registered");
            }
            throw exception;
        }
        audit.log("WORKER_REGISTERED", "worker:" + created.workerId(), "consent=" + CONSENT_VERSION + " trade=" + canonicalTrade + " language=" + lang);
        return created;
    }

    public Optional<Worker> findByPhone(String rawPhone) {
        return workerDao.findByPhone(phones.lookupKey(PhoneProtector.normalize(rawPhone)));
    }

    public Passport passport(long workerId) {
        Worker worker = workerDao.findById(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
        Optional<WorkerDao.StoredProfile> stored = workerDao.findSkillProfile(workerId);
        Optional<WorkerDao.StoredTrust> trust = workerDao.findTrustProfile(workerId);
        List<CredentialRecord> credentials = credentialDao.findByWorker(workerId);
        Optional<CredentialRecord> latest = credentials.stream().findFirst();
        List<AnswerLine> lines = assessmentDao.findLatestAttemptId(workerId)
                .map(attempt -> assessmentDao.findAttempt(workerId, attempt).stream()
                        .map(a -> new AnswerLine(a.questionNo(), a.score(), a.needsReview())).toList())
                .orElse(List.of());
        int approved = attestationDao.countApprovedForWorker(workerId);
        boolean noTrustYet = trust.map(WorkerDao.StoredTrust::band).filter(b -> !b.isBlank()).isEmpty();
        // a worker who registered after the last recalculation has nothing stored yet; with no confirmations that is "no history", not "not calculated"
        String trustBand = noTrustYet && approved == 0 ? "NO_HISTORY" : trust.map(WorkerDao.StoredTrust::band).orElse(null);
        Map<String, Object> trustReport = noTrustYet && approved == 0
                ? Map.of("feedbackCount", 0.0, "employerCount", 0.0, "tips", List.of("No employer has confirmed your work yet. Ask someone you have worked for to confirm it from their dashboard."))
                : report(trust);
        return new Passport(workerId, worker.name(), worker.trade(), worker.language(), worker.skillScore(), worker.trustScore(),
                approved, latest.map(CredentialRecord::credentialRef).orElse(null),
                latest.map(CredentialRecord::revoked).orElse(false), lines,
                stored.map(WorkerDao.StoredProfile::band).orElse(null), areas(stored), stored.map(WorkerDao.StoredProfile::nearBoundary).orElse(false),
                trustBand, trustReport);
    }

    /** The stored JSON object (area -> score) as an ordered map; empty when the worker has no breakdown yet. */
    private static Map<String, BigDecimal> areas(Optional<WorkerDao.StoredProfile> stored) {
        Map<String, BigDecimal> areas = new java.util.LinkedHashMap<>();
        String json = stored.map(WorkerDao.StoredProfile::breakdownJson).orElse(null);
        if (json == null || json.isBlank()) return areas;
        try {
            com.google.gson.JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(e -> areas.put(e.getKey(), e.getValue().getAsBigDecimal()));
        } catch (RuntimeException malformed) {
            areas.clear();                       // a corrupt breakdown must never break the passport page
        }
        return areas;
    }

    /** The stored trust explanation as a plain map for the page; empty when absent or unreadable (a corrupt value must never break the dashboard). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> report(Optional<WorkerDao.StoredTrust> trust) {
        String json = trust.map(WorkerDao.StoredTrust::reportJson).orElse(null);
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object parsed = com.kaarigarsetu.util.Json.gson().fromJson(json, Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (RuntimeException malformed) {
            return Map.of();
        }
    }

    private static String cleanName(String raw) {
        if (raw == null) throw new ValidationException("Name is required");
        String name = Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        int length = name.codePointCount(0, name.length());
        if (length < 2 || length > 80) throw new ValidationException("Name must be 2 to 80 characters");
        return name;
    }
}
