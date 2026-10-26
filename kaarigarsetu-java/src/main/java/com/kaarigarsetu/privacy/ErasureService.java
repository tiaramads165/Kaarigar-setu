package com.kaarigarsetu.privacy;

import com.kaarigarsetu.config.Transactions;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.OtpChallengeDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.exception.NotFoundException;
import com.kaarigarsetu.exception.ValidationException;
import com.kaarigarsetu.model.CredentialRecord;
import com.kaarigarsetu.model.Employer;
import com.kaarigarsetu.model.Worker;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.trust.TrustScoreService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The DPDP deletion path (right to erasure), self-service for the data principal.
 *
 * <p><b>Worker</b>: the worker row is deleted; assessments (transcripts), attestations about them, fraud flags on those,
 * credentials and every trust edge in or out go with it, as do the credential files exported to disk and the login
 * OTP challenges. Other workers' trust scores are recomputed afterwards.
 * <br><b>Employer</b>: name, phone lookup key and encrypted phone are erased and requests still waiting for a code are
 * closed, but the row and the attestations already given stay, because those endorsements are the worker's data.
 * <br>The audit trail keeps only the numeric id and the fact of erasure (it never held names or phone numbers, and its
 * hash chain is append-only by design). Erasure is irreversible, so the caller must send an explicit confirmation phrase.
 */
public class ErasureService {
    public static final String CONFIRMATION = "ERASE MY DATA";

    private final WorkerDao workerDao;
    private final EmployerDao employerDao;
    private final AttestationDao attestationDao;
    private final CredentialDao credentialDao;
    private final TrustEdgeDao trustEdgeDao;
    private final OtpChallengeDao otpChallengeDao;
    private final com.kaarigarsetu.dao.TrustedDeviceDao trustedDeviceDao;
    private final TrustScoreService trust;
    private final AuditLogger audit;
    private final Transactions transactions;
    private final Path credentialExportDirectory;

    public ErasureService(WorkerDao workerDao, EmployerDao employerDao, AttestationDao attestationDao, CredentialDao credentialDao,
                          TrustEdgeDao trustEdgeDao, OtpChallengeDao otpChallengeDao, com.kaarigarsetu.dao.TrustedDeviceDao trustedDeviceDao, TrustScoreService trust, AuditLogger audit,
                          Transactions transactions, Path credentialExportDirectory) {
        this.workerDao = workerDao;
        this.employerDao = employerDao;
        this.attestationDao = attestationDao;
        this.credentialDao = credentialDao;
        this.trustEdgeDao = trustEdgeDao;
        this.otpChallengeDao = otpChallengeDao;
        this.trustedDeviceDao = trustedDeviceDao;
        this.trust = trust;
        this.audit = audit;
        this.transactions = transactions;
        this.credentialExportDirectory = credentialExportDirectory;
    }

    public void eraseWorker(long workerId, String confirmation) {
        requireConfirmation(confirmation);
        Worker worker = workerDao.findById(workerId).orElseThrow(() -> new NotFoundException("Worker not found"));
        List<CredentialRecord> credentials = credentialDao.findByWorker(workerId);
        int attestations = attestationDao.findByWorker(workerId).size();

        transactions.inTransaction(() -> {
            trustEdgeDao.deleteForWorker(workerId);
            otpChallengeDao.deleteByKeyPrefix("login:WORKER:" + worker.phone());
            trustedDeviceDao.deleteForAccount("WORKER", workerId);
            if (!workerDao.deleteById(workerId)) throw new NotFoundException("Worker not found");
            return null;
        });
        int filesRemoved = 0;
        for (CredentialRecord credential : credentials) {
            filesRemoved += deleteExport(credential) ? 1 : 0;
        }
        audit.log("WORKER_ERASED", "worker:" + workerId, "credentials=" + credentials.size() + " files=" + filesRemoved + " attestations=" + attestations);
        refreshTrust(workerId);
    }

    public void eraseEmployer(long employerId, String confirmation) {
        requireConfirmation(confirmation);
        Employer employer = employerDao.findById(employerId).orElseThrow(() -> new NotFoundException("Employer not found"));
        int closed = transactions.inTransaction(() -> {
            otpChallengeDao.deleteByKeyPrefix("login:EMPLOYER:" + employer.phone());
            trustedDeviceDao.deleteForAccount("EMPLOYER", employerId);
            int expired = attestationDao.expireOpenForEmployer(employerId);
            if (!employerDao.anonymise(employerId)) throw new NotFoundException("Employer not found");
            return expired;
        });
        audit.log("EMPLOYER_ERASED", "employer:" + employerId, "openRequestsClosed=" + closed);
    }

    private static void requireConfirmation(String confirmation) {
        if (!CONFIRMATION.equals(confirmation == null ? null : confirmation.trim())) {
            throw new ValidationException("Erasure needs the confirmation phrase");
        }
    }

    private boolean deleteExport(CredentialRecord credential) {
        try {
            return Files.deleteIfExists(credentialExportDirectory.resolve(credential.credentialRef() + ".json"));
        } catch (IOException exception) {
            System.getLogger("ks.privacy").log(System.Logger.Level.ERROR, "Could not remove credential export: {0}", exception.toString());
            audit.log("ERASURE_FILE_REMAINS", "system", "credential=" + credential.credentialRef());
            return false;
        }
    }

    private void refreshTrust(long erasedWorkerId) {
        try {
            trust.recompute();
        } catch (RuntimeException failure) {
            audit.log("TRUST_REFRESH_FAILED", "system", "after erasure of worker=" + erasedWorkerId);
        }
    }
}
