package com.kaarigarsetu.credential;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.ConflictException;
import com.kaarigarsetu.exception.LowTrustScoreException;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.model.AssessmentAnswer;
import com.kaarigarsetu.model.CredentialRecord;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.PresentationTokens;
import com.kaarigarsetu.security.SignatureService;
import com.kaarigarsetu.trust.TrustGraphService;
import com.kaarigarsetu.util.CredentialExporter;
import com.kaarigarsetu.util.CanonicalJson;
import com.kaarigarsetu.util.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the portable Skill & Trust Passport.
 *
 * <p>The signed payload is W3C-Verifiable-Credential shaped and identifies the worker only by a pseudonymous subject
 * id (never a name or phone number). The exact signed text is what is stored and re-checked, so there is no
 * canonicalisation ambiguity. A new issuance supersedes (revokes) the worker's earlier credentials, and every
 * credential carries an expiry, which together defeat replay of a stale, higher score. Verification trusts nothing
 * from the client: it re-checks the signature, revocation and expiry from server-side state.
 */
public class CredentialService {
    public enum State { VALID, INVALID_SIGNATURE, REVOKED, EXPIRED, NOT_FOUND }

    public record Verification(State state, UUID credentialRef, Map<String, Object> summary) {
        public boolean valid() {
            return state == State.VALID;
        }
    }

    public record Issued(UUID ref, String payload, String signature, LocalDateTime issuedAt, LocalDateTime expiresAt, String keyId) {}

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private PresentationTokens presentation;

    private final CredentialDao credentialDao;
    private final WorkerDao workerDao;
    private final AssessmentDao assessmentDao;
    private final AttestationDao attestationDao;
    private final SignatureService signatures;
    private final TrustGraphService trustRules;
    private final CredentialExporter exporter;
    private final QRGenerator qr;
    private final AuditLogger audit;
    private final AppSettings settings;
    private final Clock clock;
    private final Transactions transactions;
    private final Path exportDirectory;
    private final com.kaarigarsetu.security.DataCipher files;

    public CredentialService(CredentialDao credentialDao, WorkerDao workerDao, AssessmentDao assessmentDao, AttestationDao attestationDao,
                             SignatureService signatures, TrustGraphService trustRules, CredentialExporter exporter, QRGenerator qr,
                             AuditLogger audit, AppSettings settings, Clock clock, Transactions transactions, Path exportDirectory,
                             com.kaarigarsetu.security.DataCipher files) {
        this.credentialDao = credentialDao;
        this.workerDao = workerDao;
        this.assessmentDao = assessmentDao;
        this.attestationDao = attestationDao;
        this.signatures = signatures;
        this.trustRules = trustRules;
        this.exporter = exporter;
        this.qr = qr;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.transactions = transactions;
        this.exportDirectory = exportDirectory;
        this.files = files;
    }

    public Issued issue(long workerId, String actor) {
        Worker worker = workerDao.findById(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
        UUID attempt = assessmentDao.findLatestAttemptId(workerId).orElseThrow(() -> new ConflictException("Complete an assessment first"));
        List<AssessmentAnswer> answers = assessmentDao.findAttempt(workerId, attempt);
        if (answers.size() < settings.questionsPerAssessment()) throw new ConflictException("Complete an assessment first");
        if (answers.stream().anyMatch(AssessmentAnswer::needsReview)) throw new ConflictException("The assessment is awaiting review");
        trustRules.requireMinimumTrust(worker.trustScore(), BigDecimal.valueOf(settings.minTrustForCredential()));   // LowTrustScoreException

        UUID subject = workerDao.findSubjectId(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
        UUID ref = UUID.randomUUID();
        LocalDateTime issuedAt = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        LocalDateTime expiresAt = issuedAt.plusDays(settings.credentialValidDays());
        int verifiedEmployers = attestationDao.countApprovedForWorker(workerId);

        String payload = buildPayload(ref, subject, worker, verifiedEmployers, issuedAt, expiresAt);
        String signature = signatures.sign(payload);
        CredentialRecord stored = transactions.inTransaction(() -> {
            credentialDao.revokeAllLiveForWorker(workerId, "superseded by a newer credential");
            return credentialDao.insertIssued(workerId, payload, signature, expiresAt, signatures.keyId(), ref);
        });
        exportFile(stored);
        audit.log("CREDENTIAL_ISSUED", actor, "credential=" + ref + " worker=" + workerId + " key=" + signatures.keyId());
        return new Issued(ref, payload, signature, stored.issuedAt(), expiresAt, signatures.keyId());
    }

    /** Best-effort refresh used after a pipeline event (new attestation, moderator approval). Never throws for "not eligible". */
    public Optional<Issued> reissueIfEligible(long workerId, String actor) {
        try {
            return Optional.of(issue(workerId, actor));
        } catch (ConflictException | LowTrustScoreException notEligibleYet) {
            return Optional.empty();
        }
    }

    public Verification verify(UUID ref) {
        Optional<CredentialRecord> found = credentialDao.findByRef(ref);
        if (found.isEmpty()) return new Verification(State.NOT_FOUND, ref, Map.of());
        return evaluate(found.get());
    }

    /**
     * Manual check of a payload + signature pair pasted by an employer. The signature is verified with the server's
     * key; revocation and expiry are then looked up by the credential id inside the payload.
     */
    public Verification verifyRaw(String payload, String signature) {
        if (payload == null || signature == null) return new Verification(State.INVALID_SIGNATURE, null, Map.of());
        try {
            JsonObject parsed = JsonParser.parseString(payload).getAsJsonObject();
            JsonObject issuer = parsed.getAsJsonObject("issuer");
            if (issuer == null || !issuer.has("keyId") || !parsed.has("id")) return new Verification(State.INVALID_SIGNATURE, null, Map.of());
            String claimedKeyId = issuer.get("keyId").getAsString();                               // only SELECTS the key; the signature still has to verify
            if (!signatures.verify(payload, signature, claimedKeyId)) return new Verification(State.INVALID_SIGNATURE, null, Map.of());
            String id = parsed.get("id").getAsString();
            UUID ref = UUID.fromString(id.replace("urn:uuid:", ""));
            return credentialDao.findByRef(ref).map(this::evaluate).orElse(new Verification(State.NOT_FOUND, ref, Map.of()));
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | ClassCastException malformed) {
            return new Verification(State.INVALID_SIGNATURE, null, Map.of());
        }
    }

    public boolean revoke(UUID ref, String reason, String actor) {
        boolean changed = credentialDao.revoke(ref, reason == null || reason.isBlank() ? "revoked" : reason.substring(0, Math.min(200, reason.length())));
        if (changed) audit.log("CREDENTIAL_REVOKED", actor, "credential=" + ref);
        return changed;
    }

    /** The shareable artefact: payload plus detached proof, as one JSON document. */
    public String signedDocument(UUID ref) {
        CredentialRecord record = credentialDao.findByRef(ref).orElseThrow(() -> new NotFoundException("Credential not found"));
        JsonObject proof = new JsonObject();
        proof.addProperty("type", "RsaSignature2018-PKCS1v15-SHA256");
        proof.addProperty("keyId", record.keyId());
        proof.addProperty("signature", record.signature());
        if (isCanonical(record.signedPayload())) {
            proof.addProperty("canonicalization", "RFC8785-JCS");          // verifiers: canonicalise "credential", then check the signature over those bytes
        } else {
            proof.addProperty("canonicalization", "none");                 // issued before canonical signing: the exact signed text travels with the proof
            proof.addProperty("signedPayload", record.signedPayload());
        }
        JsonObject document = new JsonObject();
        document.add("credential", JsonParser.parseString(record.signedPayload()));
        document.add("proof", proof);
        return Json.toJson(document);
    }

    private static boolean isCanonical(String payload) {
        try {
            return payload.equals(CanonicalJson.canonicalize(JsonParser.parseString(payload)));
        } catch (RuntimeException notCanonical) {
            return false;
        }
    }

    /** Every key a credential may have been signed with (the active one and retired ones), for third-party verifiers. */
    public List<SignatureService.KeyInfo> publicKeys() {
        return signatures.keys();
    }

    /**
     * The signed revocation list: {"list": {...}, "proof": {...}}. Third parties that verify offline fetch it to learn which credentials were revoked
     * or superseded; the signature stops a proxy or cache from quietly removing an entry. Newest 5000 revocations; reasons are left out on purpose.
     */
    public String revocationListDocument() {
        JsonObject list = new JsonObject();
        list.addProperty("type", "KaarigarSetuRevocationList");
        list.addProperty("issuedAt", clock.instant().toString());
        list.addProperty("keyId", signatures.keyId());
        com.google.gson.JsonArray revoked = new com.google.gson.JsonArray();
        for (CredentialRecord record : credentialDao.findRevoked(5000)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("ref", record.credentialRef().toString());
            entry.addProperty("revokedAt", record.revokedAt().format(ISO) + "Z");
            revoked.add(entry);
        }
        list.add("revoked", revoked);
        String canonical = CanonicalJson.canonicalize(list);
        JsonObject proof = new JsonObject();
        proof.addProperty("type", "RsaSignature2018-PKCS1v15-SHA256");
        proof.addProperty("keyId", signatures.keyId());
        proof.addProperty("canonicalization", "RFC8785-JCS");
        proof.addProperty("signature", signatures.sign(canonical));
        JsonObject document = new JsonObject();
        document.add("list", list);
        document.add("proof", proof);
        return Json.toJson(document);
    }

    /** A QR for the verification page that carries a live presentation code (see PresentationTokens). Only the credential's owner may ask. */
    public LiveCode liveCode(UUID ref, String actor) {
        credentialDao.findByRef(ref).orElseThrow(() -> new NotFoundException("Credential not found"));
        PresentationTokens tokens = presentationTokens();
        String token = tokens.issue(ref);
        audit.log("CREDENTIAL_LIVE_CODE", actor, "credential=" + ref);
        return new LiveCode(qr.png(verificationUrl(ref) + "?p=" + token, 480), tokens.expiresAt(token));
    }

    public record LiveCode(byte[] png, java.time.Instant expiresAt) {
    }

    public PresentationTokens.Status presentationStatus(UUID ref, String token) {
        return token == null || token.isBlank() ? new PresentationTokens.Status(PresentationTokens.State.NONE, java.time.Duration.ZERO)
                : presentationTokens().check(ref, token);
    }

    private synchronized PresentationTokens presentationTokens() {
        if (presentation == null) presentation = new PresentationTokens(settings.phoneHmacKey(), clock);       // same secret store as the phone-number keys
        return presentation;
    }

    public byte[] qrPng(UUID ref) {
        credentialDao.findByRef(ref).orElseThrow(() -> new NotFoundException("Credential not found"));
        return qr.png(verificationUrl(ref), 480);
    }

    public String verificationUrl(UUID ref) {
        return settings.publicBaseUrl() + "/verify/" + ref;
    }

    public List<CredentialRecord> listForWorker(long workerId) {
        return credentialDao.findByWorker(workerId);
    }

    public Optional<Long> ownerId(UUID ref) {
        return credentialDao.findByRef(ref).map(CredentialRecord::workerId);
    }

    public SignatureService signatures() {
        return signatures;
    }

    // ---------------------------------------------------------------- internals

    private Verification evaluate(CredentialRecord record) {
        if (!signatures.verify(record.signedPayload(), record.signature(), record.keyId())) {
            return new Verification(State.INVALID_SIGNATURE, record.credentialRef(), Map.of());
        }
        Map<String, Object> summary = summaryOf(record);
        if (record.revoked()) return new Verification(State.REVOKED, record.credentialRef(), summary);
        if (record.expiresAt() != null && record.expiresAt().isBefore(LocalDateTime.now(clock.withZone(ZoneOffset.UTC)))) {
            return new Verification(State.EXPIRED, record.credentialRef(), summary);
        }
        return new Verification(State.VALID, record.credentialRef(), summary);
    }

    private Map<String, Object> summaryOf(CredentialRecord record) {
        JsonObject subject = JsonParser.parseString(record.signedPayload()).getAsJsonObject().getAsJsonObject("credentialSubject");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("subject", subject.get("id").getAsString());
        summary.put("trade", subject.get("trade").getAsString());
        summary.put("skillScore", subject.get("skillScore").getAsBigDecimal());
        summary.put("trustScore", subject.get("trustScore").getAsBigDecimal());
        if (subject.has("trustBand")) summary.put("trustBand", subject.get("trustBand").getAsString());
        if (subject.has("skillBand")) summary.put("skillBand", subject.get("skillBand").getAsString());        // credentials issued before bands existed have none
        summary.put("verifiedEmployerCount", subject.get("verifiedEmployerCount").getAsInt());
        summary.put("issuedAt", record.issuedAt().format(ISO) + "Z");
        summary.put("validUntil", record.expiresAt() == null ? null : record.expiresAt().format(ISO) + "Z");
        summary.put("keyId", record.keyId());
        if (record.revoked()) summary.put("revocationReason", record.revocationReason());
        return summary;
    }

    private String buildPayload(UUID ref, UUID subject, Worker worker, int verifiedEmployers, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        Map<String, Object> issuer = new LinkedHashMap<>();
        issuer.put("id", settings.publicBaseUrl() + "/api/credential/public-key");
        issuer.put("name", "Kaarigar Setu");
        issuer.put("keyId", signatures.keyId());

        Map<String, Object> subjectClaims = new LinkedHashMap<>();
        subjectClaims.put("id", "urn:ks:subject:" + subject);          // pseudonymous: no name, no phone number
        subjectClaims.put("trade", worker.trade());
        subjectClaims.put("language", worker.language());
        subjectClaims.put("skillScore", worker.skillScore());
        subjectClaims.put("trustScore", worker.trustScore());
        workerDao.findSkillProfile(worker.workerId()).map(WorkerDao.StoredProfile::band).ifPresent(band -> subjectClaims.put("skillBand", band));
        workerDao.findTrustProfile(worker.workerId()).map(WorkerDao.StoredTrust::band).filter(b -> !b.isBlank()).ifPresent(band -> subjectClaims.put("trustBand", band));
        subjectClaims.put("verifiedEmployerCount", verifiedEmployers);

        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("@context", List.of("https://www.w3.org/ns/credentials/v2"));
        credential.put("id", "urn:uuid:" + ref);
        credential.put("type", List.of("VerifiableCredential", "KaarigarSetuSkillTrustPassport"));
        credential.put("issuer", issuer);
        credential.put("validFrom", issuedAt.format(ISO) + "Z");
        credential.put("validUntil", expiresAt.format(ISO) + "Z");
        credential.put("credentialSubject", subjectClaims);
        return CanonicalJson.canonicalize(Json.gson().toJsonTree(credential));        // RFC 8785: any language can rebuild these exact bytes
    }

    private void exportFile(CredentialRecord record) {
        try {
            // the copy kept on disk holds the worker's scores: it is stored sealed, like the database copy (the download endpoint builds its answer from the database)
            exporter.exportJson(exportDirectory.resolve(record.credentialRef() + ".json"), files.seal("credential.export", record.credentialRef(), signedDocument(record.credentialRef())));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to export credential file", exception);
        }
    }
}
