package com.kaarigarsetu.web;

import com.kaarigarsetu.assessment.AssessmentPipelineService;
import com.kaarigarsetu.assessment.AssessmentScoringDispatcher;
import com.kaarigarsetu.assessment.CarpenterAssessmentService;
import com.kaarigarsetu.assessment.ElectricianAssessmentService;
import com.kaarigarsetu.assessment.MasonAssessmentService;
import com.kaarigarsetu.assessment.PainterAssessmentService;
import com.kaarigarsetu.assessment.TradeCatalog;
import com.kaarigarsetu.assessment.WelderAssessmentService;
import com.kaarigarsetu.assessment.PlumberAssessmentService;
import com.kaarigarsetu.attestation.AttestationWorkflowService;
import com.kaarigarsetu.config.AppSettings;
import com.kaarigarsetu.config.DatabaseConfig;
import com.kaarigarsetu.config.PooledConnectionProvider;
import com.kaarigarsetu.credential.CredentialService;
import com.kaarigarsetu.credential.QRGenerator;
import com.kaarigarsetu.dao.AssessmentDao;
import com.kaarigarsetu.dao.AttestationDao;
import com.kaarigarsetu.dao.AuditLogDao;
import com.kaarigarsetu.dao.CredentialDao;
import com.kaarigarsetu.dao.EmployerDao;
import com.kaarigarsetu.dao.FraudFlagDao;
import com.kaarigarsetu.dao.OtpChallengeDao;
import com.kaarigarsetu.dao.TrustEdgeDao;
import com.kaarigarsetu.dao.WorkerDao;
import com.kaarigarsetu.employer.EmployerService;
import com.kaarigarsetu.ml.MlServiceClient;
import com.kaarigarsetu.moderator.ModeratorService;
import com.kaarigarsetu.privacy.ErasureService;
import com.kaarigarsetu.security.AuditLogger;
import com.kaarigarsetu.security.DataCipher;
import com.kaarigarsetu.security.AuthService;
import com.kaarigarsetu.security.ChainedAuditFileSink;
import com.kaarigarsetu.security.ConsoleOtpSender;
import com.kaarigarsetu.security.DaoAuditLogRepository;
import com.kaarigarsetu.security.OtpSender;
import com.kaarigarsetu.security.OtpService;
import com.kaarigarsetu.security.PhoneProtector;
import com.kaarigarsetu.security.RateLimiter;
import com.kaarigarsetu.security.SignatureService;
import com.kaarigarsetu.trust.TrustGraphService;
import com.kaarigarsetu.trust.TrustScoreService;
import com.kaarigarsetu.util.CredentialExporter;
import jakarta.servlet.ServletContext;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Composition root: every service is built once at startup and shared through the ServletContext. */
public final class AppContext implements AutoCloseable {
    public static final String ATTRIBUTE = "ks.app";

    public final AppSettings settings;
    public final PooledConnectionProvider pool;
    public final PhoneProtector phones;
    public final DataCipher dataCipher;
    public final AuditLogger audit;
    public final ChainedAuditFileSink auditFile;
    public final RateLimiter limiter;
    public final WorkerDaoBundle daos;
    public final com.kaarigarsetu.worker.WorkerService workers;
    public final EmployerService employers;
    public final AuthService auth;
    public final AssessmentPipelineService assessments;
    public final AttestationWorkflowService attestations;
    public final TrustScoreService trust;
    public final CredentialService credentials;
    public final ModeratorService moderation;
    public final com.kaarigarsetu.dao.ModeratorDao moderatorDao;
    public final com.kaarigarsetu.security.ProofOfWork proofOfWork;
    private final com.kaarigarsetu.dao.TrustedDeviceDao trustedDevices;
    public final ErasureService erasure;
    public final com.kaarigarsetu.moderator.SecurityMonitor securityMonitor;
    public final com.kaarigarsetu.attestation.ConfirmationResponseService confirmations;
    public final TradeCatalog trades;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ks-maintenance");
        thread.setDaemon(true);
        return thread;
    });

    /** The DAOs, grouped so servlets that need a direct read can reach them. */
    public record WorkerDaoBundle(WorkerDao workers, EmployerDao employers, AssessmentDao assessments, AttestationDao attestations,
                                  TrustEdgeDao trustEdges, CredentialDao credentials, FraudFlagDao fraudFlags) {}

    private AppContext(AppSettings settings, DatabaseConfig database) {
        this.settings = settings;
        SecureRandom random = new SecureRandom();
        Clock clock = Clock.systemUTC();
        this.pool = new PooledConnectionProvider(database, settings.dbPoolSize());
        this.phones = new PhoneProtector(settings.phoneEncryptionKey(), settings.phoneHmacKey(), random);

        // Encryption at rest (see DataCipher): the stored values, the audit trail and the files each get their own key derived from one ring held in the
        // environment. The audit trail is append-only and may hold older plain rows from before sealing was switched on, so it never refuses plaintext.
        DataCipher dataCipher = DataCipher.of(settings.dataKeys(), DataCipher.DATA, settings.requireSealedAtRest());
        DataCipher auditCipher = DataCipher.of(settings.dataKeys(), DataCipher.AUDIT, false);
        DataCipher fileCipher = DataCipher.of(settings.dataKeys(), DataCipher.FILES, false);
        this.dataCipher = dataCipher;
        WorkerDao workerDao = new WorkerDao(pool, dataCipher);
        EmployerDao employerDao = new EmployerDao(pool, dataCipher);
        AssessmentDao assessmentDao = new AssessmentDao(pool, dataCipher);
        AttestationDao attestationDao = new AttestationDao(pool, dataCipher);
        TrustEdgeDao trustEdgeDao = new TrustEdgeDao(pool);
        CredentialDao credentialDao = new CredentialDao(pool, dataCipher);
        FraudFlagDao fraudFlagDao = new FraudFlagDao(pool, dataCipher);
        this.daos = new WorkerDaoBundle(workerDao, employerDao, assessmentDao, attestationDao, trustEdgeDao, credentialDao, fraudFlagDao);

        this.auditFile = new ChainedAuditFileSink(settings.auditLogPath(), settings.auditHmacKey(), clock, auditCipher);
        this.audit = new AuditLogger(new DaoAuditLogRepository(new AuditLogDao(pool, auditCipher)), auditFile);
        this.limiter = new RateLimiter(clock);

        OtpSender sender = settings.smsEnabled()
                ? new com.kaarigarsetu.security.HttpSmsOtpSender(new com.kaarigarsetu.security.HttpSmsOtpSender.Config(settings.smsUrl(), settings.smsAuthHeaderName(),
                        settings.smsAuthHeaderValue(), settings.smsJson(), settings.smsBodyTemplate(), settings.smsSenderId(), settings.smsMessageTemplate()))
                : new ConsoleOtpSender();
        OtpService otp = new OtpService(new OtpChallengeDao(pool), pool, clock, random, settings.otpPepper(),
                settings.otpMaxAttempts(), Duration.ofSeconds(settings.otpTtlSeconds()));
        MlServiceClient ml = new MlServiceClient(settings.mlServiceUrl(), settings.mlInternalToken());

        this.trades = TradeCatalog.load();
        java.util.List<com.kaarigarsetu.assessment.AssessmentService> scorers = List.of(new ElectricianAssessmentService(), new PlumberAssessmentService(),
                new CarpenterAssessmentService(), new MasonAssessmentService(), new WelderAssessmentService(), new PainterAssessmentService());
        trades.requireScorersFor(scorers);      // a trade offered to workers without a scorer is a startup error, not a runtime surprise
        this.workers = new com.kaarigarsetu.worker.WorkerService(workerDao, assessmentDao, attestationDao, credentialDao, phones, audit, pool, trades);
        this.employers = new EmployerService(employerDao, phones, audit, pool);
        this.moderatorDao = new com.kaarigarsetu.dao.ModeratorDao(pool);
        com.kaarigarsetu.dao.TrustedDeviceDao trustedDeviceDao = new com.kaarigarsetu.dao.TrustedDeviceDao(pool);
        this.proofOfWork = new com.kaarigarsetu.security.ProofOfWork(phones.tokenHash("pow-key", "v1").getBytes(java.nio.charset.StandardCharsets.UTF_8), clock);
        this.auth = new AuthService(workerDao, employerDao, moderatorDao, trustedDeviceDao, proofOfWork, phones, otp, sender, limiter, audit, settings);

        AssessmentScoringDispatcher dispatcher = new AssessmentScoringDispatcher(scorers);
        com.kaarigarsetu.dao.ReplayDao replayDao = new com.kaarigarsetu.dao.ReplayDao(pool);
        this.assessments = new AssessmentPipelineService(workerDao, assessmentDao, ml, dispatcher, audit, settings,
                settings.phoneHmacKey(), clock, pool,
                new com.kaarigarsetu.assessment.ReplayDetector(replayDao, phones, settings),
                new com.kaarigarsetu.assessment.ReplayChallenge(settings.phoneHmacKey(), clock, Duration.ofMinutes(settings.replayChallengeMinutes())), replayDao, limiter);
        this.trust = new TrustScoreService(attestationDao, workerDao, ml, audit, clock);

        SignatureService signatures = SignatureService.fromKeyStore(settings.keystorePath(), settings.keystorePassword(), settings.keyAlias())
                .withRetiredKeys(settings.retiredKeysDir());
        this.credentials = new CredentialService(credentialDao, workerDao, assessmentDao, attestationDao, signatures, new TrustGraphService(),
                new CredentialExporter(), new QRGenerator(), audit, settings, clock, pool, Path.of("data", "credentials"), fileCipher);
        this.attestations = new AttestationWorkflowService(attestationDao, employerDao, workerDao, fraudFlagDao, trustEdgeDao, otp, sender,
                phones, ml, trust, credentials, audit, limiter, clock, pool);
        this.moderation = new ModeratorService(fraudFlagDao, attestationDao, trustEdgeDao, employerDao, trust, credentials, audit, pool,
                new com.kaarigarsetu.dao.ModeratorActionDao(pool), settings);

        this.erasure = new ErasureService(workerDao, employerDao, attestationDao, credentialDao, trustEdgeDao, new OtpChallengeDao(pool), trustedDeviceDao, trust, audit, pool,
                Path.of("data", "credentials"));
        this.securityMonitor = new com.kaarigarsetu.moderator.SecurityMonitor(audit, auditFile, clock);
        this.confirmations = new com.kaarigarsetu.attestation.ConfirmationResponseService(attestationDao, trust, credentials, audit, settings, clock);

        this.trustedDevices = trustedDeviceDao;
        scheduler.scheduleWithFixedDelay(() -> limiter.evictExpired(Duration.ofHours(2)), 10, 30, TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(() -> {
            proofOfWork.evictExpired();
            try {
                trustedDevices.deleteIdleFor(settings.trustedDeviceDays());
            } catch (RuntimeException failure) {
                System.getLogger("ks.auth").log(System.Logger.Level.WARNING, "expiring trusted devices failed: " + failure.getMessage());
            }
        }, 5, 360, TimeUnit.MINUTES);
        // Pin the audit file's newest line in the database every few minutes (and once soon after start), so a rolled-back file is caught.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                audit.anchorFileHead();
            } catch (RuntimeException failure) {
                System.getLogger("ks.audit").log(System.Logger.Level.WARNING, "audit anchoring failed: " + failure.getMessage());
            }
        }, 20, 300, TimeUnit.SECONDS);
    }

    public static AppContext create(AppSettings settings, DatabaseConfig database) {
        settings.validate();
        return new AppContext(settings, database);
    }

    public static AppContext of(ServletContext context) {
        return (AppContext) context.getAttribute(ATTRIBUTE);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        pool.close();
    }
}
