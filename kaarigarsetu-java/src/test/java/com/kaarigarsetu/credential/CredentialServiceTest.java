package com.kaarigarsetu.credential;

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
import com.kaarigarsetu.security.SignatureService;
import com.kaarigarsetu.support.MutableClock;
import com.kaarigarsetu.trust.TrustGraphService;
import com.kaarigarsetu.util.CredentialExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The credential service against an in-memory credential table, a real RSA key and the real QR/exporter code. */
class CredentialServiceTest {
    private static final long WORKER = 7L;

    @TempDir Path exportDir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T09:00:00Z"));
    private final List<CredentialRecord> table = new ArrayList<>();
    private final CredentialDao credentialDao = mock(CredentialDao.class);
    private final WorkerDao workerDao = mock(WorkerDao.class);
    private final AssessmentDao assessmentDao = mock(AssessmentDao.class);
    private final AttestationDao attestationDao = mock(AttestationDao.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final SignatureService signatures = new SignatureService();
    private final UUID subject = UUID.randomUUID();
    private CredentialService service;
    private Worker worker = new Worker(WORKER, "Ramesh Kumar", "h1:opaque", "electrician", "hi", new BigDecimal("82.50"), new BigDecimal("64.00"), null);
    private boolean needsReview = false;
    private int answered = 10;

    @BeforeEach
    void setUp() {
        when(workerDao.findById(WORKER)).thenAnswer(i -> Optional.of(worker));
        when(workerDao.findSubjectId(WORKER)).thenReturn(Optional.of(subject));
        UUID attempt = UUID.randomUUID();
        when(assessmentDao.findLatestAttemptId(WORKER)).thenReturn(Optional.of(attempt));
        when(assessmentDao.findAttempt(eq(WORKER), eq(attempt))).thenAnswer(i -> java.util.stream.IntStream.rangeClosed(1, answered)
                .mapToObj(n -> new AssessmentAnswer((long) n, WORKER, "electrician", attempt, n, "hi", "t", new BigDecimal("80"), "f", needsReview, null, "stub", null))
                .toList());
        when(attestationDao.countApprovedForWorker(WORKER)).thenReturn(3);

        when(credentialDao.insertIssued(anyLong(), anyString(), anyString(), any(), anyString(), any())).thenAnswer(i -> {
            CredentialRecord r = new CredentialRecord(table.size() + 1L, i.getArgument(5), i.getArgument(0), i.getArgument(1), i.getArgument(2),
                    LocalDateTime.now(clock), i.getArgument(3), null, null, i.getArgument(4));
            table.add(r);
            return r;
        });
        when(credentialDao.findByRef(any())).thenAnswer(i -> table.stream().filter(r -> r.credentialRef().equals(i.getArgument(0))).findFirst());
        when(credentialDao.revoke(any(), anyString())).thenAnswer(i -> {
            for (int n = 0; n < table.size(); n++) {
                CredentialRecord r = table.get(n);
                if (r.credentialRef().equals(i.getArgument(0)) && !r.revoked()) {
                    table.set(n, withRevocation(r, i.getArgument(1)));
                    return true;
                }
            }
            return false;
        });
        when(credentialDao.revokeAllLiveForWorker(anyLong(), anyString())).thenAnswer(i -> {
            int changed = 0;
            for (int n = 0; n < table.size(); n++) if (!table.get(n).revoked()) { table.set(n, withRevocation(table.get(n), i.getArgument(1))); changed++; }
            return changed;
        });
        service = new CredentialService(credentialDao, workerDao, assessmentDao, attestationDao, signatures, new TrustGraphService(),
                new CredentialExporter(), new QRGenerator(), audit, AppSettings.of(Map.of("PUBLIC_BASE_URL", "https://ks.example", "CREDENTIAL_VALID_DAYS", "30")),
                clock, Transactions.none(), exportDir, com.kaarigarsetu.support.TestCiphers.files());
    }

    private CredentialRecord withRevocation(CredentialRecord r, String reason) {
        return new CredentialRecord(r.credentialId(), r.credentialRef(), r.workerId(), r.signedPayload(), r.signature(), r.issuedAt(), r.expiresAt(),
                LocalDateTime.now(clock), reason, r.keyId());
    }

    @Test
    void theSignedPayloadIsCanonicalSoAnyJsonLibraryCanRebuildTheSignedBytes() throws Exception {
        CredentialService.Issued issued = service.issue(WORKER, "worker:7");
        com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(issued.payload());
        assertEquals(issued.payload(), com.kaarigarsetu.util.CanonicalJson.canonicalize(parsed), "payload must equal its own canonical form");
        assertTrue(issued.payload().contains("\"skillScore\":82.5,"), "82.50 is signed as 82.5, the form every JSON library writes");
        // the shared document says how to rebuild the bytes, and a re-serialised copy (what any other language produces) still verifies
        com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString(service.signedDocument(issued.ref())).getAsJsonObject();
        assertEquals("RFC8785-JCS", document.getAsJsonObject("proof").get("canonicalization").getAsString());
        assertFalse(document.getAsJsonObject("proof").has("signedPayload"));
        String rebuilt = com.kaarigarsetu.util.CanonicalJson.canonicalize(document.get("credential"));
        assertTrue(service.verifyRaw(rebuilt, document.getAsJsonObject("proof").get("signature").getAsString()).valid());
    }

    @Test
    void aCredentialIssuedBeforeCanonicalSigningStillVerifiesAndCarriesItsExactSignedText() throws Exception {
        // legacy text: key order as built, a score written 82.50, no sorting
        String legacy = "{\"id\":\"urn:uuid:" + UUID.randomUUID() + "\",\"issuer\":{\"keyId\":\"" + signatures.keyId() + "\"},\"score\":82.50}";
        UUID ref = UUID.fromString(com.google.gson.JsonParser.parseString(legacy).getAsJsonObject().get("id").getAsString().replace("urn:uuid:", ""));
        table.add(new CredentialRecord(99, ref, WORKER, legacy, signatures.sign(legacy), LocalDateTime.now(clock), LocalDateTime.now(clock).plusDays(5), null, null, signatures.keyId()));
        com.google.gson.JsonObject proof = com.google.gson.JsonParser.parseString(service.signedDocument(ref)).getAsJsonObject().getAsJsonObject("proof");
        assertEquals("none", proof.get("canonicalization").getAsString());
        assertEquals(legacy, proof.get("signedPayload").getAsString(), "the exact signed text travels with the proof");
    }

    @Test
    void theRevocationListIsSignedAndNamesRevokedCredentialsWithoutReasons() throws Exception {
        CredentialService.Issued first = service.issue(WORKER, "worker:7");
        service.revoke(first.ref(), "worker typed their phone number here 9876543210", "worker:7");
        when(credentialDao.findRevoked(anyInt())).thenAnswer(i -> table.stream().filter(CredentialRecord::revoked).toList());
        com.google.gson.JsonObject document = com.google.gson.JsonParser.parseString(service.revocationListDocument()).getAsJsonObject();
        com.google.gson.JsonObject list = document.getAsJsonObject("list");
        assertEquals(first.ref().toString(), list.getAsJsonArray("revoked").get(0).getAsJsonObject().get("ref").getAsString());
        assertFalse(document.toString().contains("9876543210"), "reasons are free text and stay out of a public list");
        String canonical = com.kaarigarsetu.util.CanonicalJson.canonicalize(list);
        assertTrue(signatures.verify(canonical, document.getAsJsonObject("proof").get("signature").getAsString(), signatures.keyId()));
        list.getAsJsonArray("revoked").remove(0);                                           // a cache quietly drops the entry ...
        assertFalse(signatures.verify(com.kaarigarsetu.util.CanonicalJson.canonicalize(list), document.getAsJsonObject("proof").get("signature").getAsString(), signatures.keyId()));
    }

    @Test
    void issuedCredentialVerifiesAndHoldsNoDirectIdentifiers() throws Exception {
        CredentialService.Issued issued = service.issue(WORKER, "worker:7");
        assertTrue(service.verify(issued.ref()).valid());
        assertTrue(issued.payload().contains("urn:ks:subject:" + subject));
        assertFalse(issued.payload().contains("Ramesh"), "no name");
        assertFalse(issued.payload().contains("opaque"), "no phone key");
        assertFalse(issued.payload().contains("9876"), "no phone number");
        assertTrue(issued.payload().contains("\"verifiedEmployerCount\":3"));
        assertTrue(signatures.verify(issued.payload(), issued.signature()));
        assertTrue(Files.exists(exportDir.resolve(issued.ref() + ".json")), "exported for sharing");
        verify(audit).log(eq("CREDENTIAL_ISSUED"), eq("worker:7"), contains(issued.ref().toString()));
    }

    @Test
    void theSkillBandIsPartOfTheSignedClaimsAndShownOnVerificationButOlderCredentialsWithoutOneStillVerify() {
        CredentialService.Issued without = service.issue(WORKER, "a");                                      // no profile stored yet
        assertFalse(without.payload().contains("skillBand"));
        assertTrue(service.verify(without.ref()).valid());
        assertFalse(service.verify(without.ref()).summary().containsKey("skillBand"));

        when(workerDao.findSkillProfile(WORKER)).thenReturn(Optional.of(new WorkerDao.StoredProfile("BORDERLINE", "{}", false)));
        CredentialService.Issued with = service.issue(WORKER, "a");
        assertTrue(with.payload().contains("\"skillBand\":\"BORDERLINE\""));
        assertEquals("BORDERLINE", service.verify(with.ref()).summary().get("skillBand"));
        when(workerDao.findTrustProfile(WORKER)).thenReturn(Optional.of(new WorkerDao.StoredTrust("GOOD", "{}")));
        CredentialService.Issued withTrust = service.issue(WORKER, "a");
        assertTrue(withTrust.payload().contains("\"trustBand\":\"GOOD\""));
        assertEquals("GOOD", service.verify(withTrust.ref()).summary().get("trustBand"));
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(withTrust.payload().replace("GOOD", "EXCELLENT"), withTrust.signature()).state());
        String forged = with.payload().replace("BORDERLINE", "PASS");
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(forged, with.signature()).state(), "the band cannot be edited without breaking the signature");
    }

    @Test
    void aTamperedStoredPayloadIsDetectedEvenThoughTheRowExists() {
        CredentialService.Issued issued = service.issue(WORKER, "a");
        CredentialRecord original = table.get(0);
        table.set(0, new CredentialRecord(original.credentialId(), original.credentialRef(), original.workerId(),
                original.signedPayload().replace("82.5", "99.9"), original.signature(), original.issuedAt(), original.expiresAt(), null, null, original.keyId()));
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verify(issued.ref()).state());
    }

    @Test
    void verifyRawChecksTheSignatureThenLooksUpRevocation() {
        CredentialService.Issued issued = service.issue(WORKER, "a");
        assertEquals(CredentialService.State.VALID, service.verifyRaw(issued.payload(), issued.signature()).state());
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(issued.payload().replace("electrician", "plumber"), issued.signature()).state());
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(null, issued.signature()).state());
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(issued.payload(), null).state());
        service.revoke(issued.ref(), "lost phone", "worker:7");
        assertEquals(CredentialService.State.REVOKED, service.verifyRaw(issued.payload(), issued.signature()).state());
    }

    @Test
    void aSignedButMalformedPayloadIsInvalidNotAnError() {
        String junk = "{\"id\":\"urn:uuid:not-a-uuid\"}";
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(junk, signatures.sign(junk)).state());
        String noId = "{\"x\":1}";
        assertEquals(CredentialService.State.INVALID_SIGNATURE, service.verifyRaw(noId, signatures.sign(noId)).state());
    }

    @Test
    void aNewIssuanceSupersedesTheOldOneSoAStaleScoreCannotBeReplayed() {
        CredentialService.Issued first = service.issue(WORKER, "a");
        worker = new Worker(WORKER, "Ramesh", "h1:opaque", "electrician", "hi", new BigDecimal("90.00"), new BigDecimal("70.00"), null);
        CredentialService.Issued second = service.issue(WORKER, "a");
        assertEquals(CredentialService.State.REVOKED, service.verify(first.ref()).state());
        assertEquals(CredentialService.State.VALID, service.verify(second.ref()).state());
    }

    @Test
    void credentialsExpire() {
        CredentialService.Issued issued = service.issue(WORKER, "a");
        clock.advance(Duration.ofDays(29));
        assertTrue(service.verify(issued.ref()).valid());
        clock.advance(Duration.ofDays(2));
        assertEquals(CredentialService.State.EXPIRED, service.verify(issued.ref()).state());
    }

    @Test
    void revocationIsIdempotentAndKeepsTheFirstReasonAndTheSummary() {
        CredentialService.Issued issued = service.issue(WORKER, "a");
        assertTrue(service.revoke(issued.ref(), "x".repeat(500), "moderator:1"));
        assertFalse(service.revoke(issued.ref(), "other", "moderator:1"));
        CredentialService.Verification v = service.verify(issued.ref());
        assertEquals(CredentialService.State.REVOKED, v.state());
        assertEquals(200, v.summary().get("revocationReason").toString().length());
        verify(audit, times(1)).log(eq("CREDENTIAL_REVOKED"), anyString(), anyString());
        assertEquals(CredentialService.State.NOT_FOUND, service.verify(UUID.randomUUID()).state());
    }

    @Test
    void issuingNeedsACompleteAssessmentFreeOfReviewFlagsAndEnoughTrust() {
        answered = 3;
        assertThrows(ConflictException.class, () -> service.issue(WORKER, "a"));
        answered = 10;
        needsReview = true;
        assertThrows(ConflictException.class, () -> service.issue(WORKER, "a"));
        needsReview = false;
        assertTrue(service.reissueIfEligible(WORKER, "a").isPresent());

        AppSettings strict = AppSettings.of(Map.of("MIN_TRUST_FOR_CREDENTIAL", "80"));
        CredentialService strictService = new CredentialService(credentialDao, workerDao, assessmentDao, attestationDao, signatures, new TrustGraphService(),
                new CredentialExporter(), new QRGenerator(), audit, strict, clock, Transactions.none(), exportDir, com.kaarigarsetu.support.TestCiphers.files());
        assertThrows(LowTrustScoreException.class, () -> strictService.issue(WORKER, "a"));
        assertTrue(strictService.reissueIfEligible(WORKER, "a").isEmpty(), "best-effort refresh never throws for 'not eligible'");
        when(workerDao.findById(99L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> service.issue(99L, "a"));
    }

    @Test
    void signedDocumentCarriesTheProofAndQrPointsAtTheVerifyPage() {
        CredentialService.Issued issued = service.issue(WORKER, "a");
        String doc = service.signedDocument(issued.ref());
        assertTrue(doc.contains("RsaSignature2018-PKCS1v15-SHA256") && doc.contains(signatures.keyId()));
        assertEquals("https://ks.example/verify/" + issued.ref(), service.verificationUrl(issued.ref()));
        byte[] png = service.qrPng(issued.ref());
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertThrows(NotFoundException.class, () -> service.qrPng(UUID.randomUUID()));
        assertThrows(NotFoundException.class, () -> service.signedDocument(UUID.randomUUID()));
        assertEquals(Optional.of(WORKER), service.ownerId(issued.ref()));
    }
}
