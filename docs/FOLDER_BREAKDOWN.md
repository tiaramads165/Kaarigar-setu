# Kaarigar Setu: folder-by-folder breakdown

What each folder of the project holds and what it does. Paths are relative to `Karigar-Setu-rebuilt/`.

---

# 1. `kaarigarsetu-java/` (the web application)

## 1.1 `src/main/java/com/kaarigarsetu/` (144 files in 17 packages)

| Package | Files | What it does |
|---|---|---|
| `model/` (18) | `Worker`, `Employer`, `Assessment`, `AssessmentAnswer`, `Attestation`, `AttestationDetail`, `WorkerConfirmation`, `FlaggedAttestation`, `FraudFlag`, `TrustEdge`, `TrustEdgeRow`, `Credential`, `CredentialRecord`, `DisputeCase`, `Moderator`, `ModeratorAction`, `TrustedDevice`, `AuditLog` | Java records: the plain data shapes |
| `exception/` (15) | `InvalidOTPException`, `DuplicateAttestationException`, `LowTrustScoreException`, `ConsentRequiredException`, `UnintelligibleAudioException`, `ExternalServiceException`, `ConflictException`, `NotFoundException`, `ValidationException`, `ForbiddenException`, `RateLimitExceededException`, `AuthenticationFailedException`, `ChallengeException`, `ProofOfWorkRequiredException`, `DataAccessException` | our own error types |
| `config/` (5) | `AppSettings`, `DatabaseConfig`, `PooledConnectionProvider`, `ConnectionProvider`, `Transactions` | environment settings, connection pool, transactions |
| `util/` (5) | `Json`, `CanonicalJson`, `WavValidator`, `AuditLogWriter`, `CredentialExporter` | JSON, signing format, audio check, file writing |
| `dao/` (14) | `JdbcDaoSupport` plus `WorkerDao`, `EmployerDao`, `AssessmentDao`, `AttestationDao`, `TrustEdgeDao`, `CredentialDao`, `FraudFlagDao`, `OtpChallengeDao`, `TrustedDeviceDao`, `ModeratorDao`, `ModeratorActionDao`, `AuditLogDao`, `ReplayDao` | all SQL, one class per table |
| `assessment/` (19) | `AssessmentService` (abstract) and six trade subclasses (`Electrician`, `Plumber`, `Carpenter`, `Mason`, `Welder`, `Painter`), `AssessmentScoringDispatcher`, `AssessmentPipelineService`, `AssessmentServlet`, `AssessmentResult`, `Band`, `SkillScoring`, `SkillProfile`, `TradeCatalog`, and the replay-check classes `ReplayDetector`, `ReplayChallenge`, `SpokenCode`, `TextShingles` | the voice test and its scoring |
| `worker/` (2), `employer/` (2) | `WorkerService`, `WorkerServlet`, `EmployerService`, `EmployerServlet` | registration and the passport |
| `attestation/` (8) | `AttestationWorkflowService`, `AttestationServlet`, `ConfirmationResponseService`, `AttestationRules`, `AttestationService`, `AttestationStore`, `AttestationRepository`, `DaoAttestationRepository` | employer confirmation; the worker's accept or dispute |
| `trust/` (7) | `ReputationScoring`, `TrustScoreService`, `TrustGraphService`, `TrustBand`, `Edge`, `TrustGraphRepository`, `DaoTrustGraphRepository` | the trust formula and the endorsement graph |
| `credential/` (3) | `CredentialService`, `CredentialServlet`, `QRGenerator` | issuing, checking and the QR code of the signed credential |
| `moderator/` (3) | `ModeratorService`, `ModeratorServlet`, `SecurityMonitor` | staff queues, two-person approval, the overview |
| `web/` (10) | `AppContext`, `AppBootstrap`, `BaseServlet`, `ApiErrors`, `AuthServlet`, `PageServlets`, `Sessions`, `CsrfFilter`, `SecurityHeadersFilter`, `SecurityEvents` | request entry and exit, application wiring, sessions |
| `security/` (24) | `AuthFilter`, `AuthService`, `OtpService`, `OtpSender`, `ConsoleOtpSender`, `HttpSmsOtpSender`, `OtpChallengeStore`, `InMemoryOtpChallengeStore`, `PasswordHasher`, `PhoneProtector`, `ProofOfWork`, `RateLimiter`, `Role`, `AuthenticatedUser`, `SignatureService`, `PresentationTokens`, `DataCipher`, `DataKeys`, `SealedDataException`, `AuditLogger`, `AuditLogRepository`, `DaoAuditLogRepository`, `AuditFileSink`, `ChainedAuditFileSink` | sign-in codes, access rules, signing, encryption, audit |
| `ml/` (2) | `MlGateway`, `MlServiceClient` | the link to the Python service |
| `privacy/` (1) | `ErasureService` | deleting a person's data |
| `admin/` (5) | `ModeratorAdminCli`, `DemoSeed`, `AuditVerifyCli`, `SealAtRestCli`, `SealedColumns` | operator tools |
| root | `HealthServlet` | the `/health` check |

## 1.2 `src/main/webapp/` (what the user sees)

| Folder | Files | What it does |
|---|---|---|
| `WEB-INF/jsp/` | `index`, `worker-dashboard`, `assessment`, `employer-dashboard`, `verify`, `demo`, `error` | the 7 pages |
| `static/js/` | `common`, `auth`, `pow`, `worker`, `assessment`, `wav`, `employer`, `demo` | the page scripts: shared helpers, sign-in, the passport, the voice test, audio conversion, the employer form |
| `static/css/` | `app.css` | the single stylesheet |
| `WEB-INF/` | `web.xml` | the filters, JSP rules and error pages |

## 1.3 Other parts of this folder

| Path | What it holds |
|---|---|
| `src/main/resources/trades.json` | the six trades, in English and Hindi |
| `src/test/java/` | 64 test files, 498 tests, grouped by `assessment`, `attestation`, `trust`, `credential`, `moderator`, `security`, `dao`, `web`, `util`, `architecture`, `privacy`, `admin`, `config` |
| `pom.xml`, `pom-rb.xml` | the build files (`pom-rb.xml` is the development run) |
| `spotbugs-exclude.xml` | static-analysis exceptions, each with its reason |
| `Dockerfile` | builds the application image |

---

# 2. `kaarigarsetu-ml-service/` (the Python service)

| Path | What it holds |
|---|---|
| `app/main.py`, `config.py`, `security.py` | the FastAPI app, settings, the shared-secret check |
| `app/routes/` | `transcribe`, `audio` (fingerprint), `evaluate`, `rubric`, `fraud`, `trust` |
| `app/services/` | speech to text: `faster_whisper_client`, `whisper_client`, `stub_transcriber` · scoring: `evaluation_service`, `rubric_evaluator`, `rubric_store`, `guard`, `llm_client` · `audio_fingerprint` · `fraud_detector` · `graph_engine` (PageRank) |
| `app/models/` | six rubric files plus `_common.json`; the trained fraud model with its checksum and metrics; the language-model prompts |
| `app/training/` | scripts that train and check the fraud model on synthetic data |
| `tests/` | 398 tests |
| `benchmarks/` | speech-accuracy and replay-check benchmarks |

---

# 3. `kaarigarsetu-moderator-console/` (the JavaFX staff tool)

12 files in `src/main/java/com/kaarigarsetu/console/`, 18 tests.

| Files | What they do |
|---|---|
| `ModeratorConsoleApp` | the window: sign-in and the five tabs (Fraud review, Employers, Disputes, Approvals, Security) |
| `Launcher` | starts the application |
| `ModeratorApi`, `HttpModeratorApi`, `ModeratorIdentity` | the interface to the Java API, its HTTP implementation, who is signed in |
| `FlaggedRow`, `EmployerRow`, `DisputeRow`, `PendingRow` | one row of each table |
| `SecurityOverview`, `ActionResult`, `ConsoleException` | the overview data, action results, console errors |

---

# 4. `database/`

| File | What it holds |
|---|---|
| `schema.sql` | the first tables |
| `migrations/002` to `011` | the pipeline columns; skill bands; employer ratings; employer vetting; worker disputes; the counted-confirmations view; named moderators and two-person approval; trusted devices; encrypted columns; replay-detection tables |
| `setup_roles.sql` | the restricted database role the application uses |

---

# 5. `scripts/`

| Group | Files |
|---|---|
| Set-up and run | `dev-setup.sh`, `dev.sh`, `dev-https.sh`, `dev_https_proxy.py`, `console.sh`, `compose-setup.sh`, `demo-seed.sh`, `otp.sh` |
| Tests | `e2e.py` (192 checks), `api_matrix.py` (92), `security_attack_demo.py` (57 attacks), `coverage.sh`, `pow_js_check.js` |
| Credentials and keys | `verify_credential.py`, `QrDecode.java`, `generate-signing-key.sh`, `rotate-signing-key.sh` |
| Operator tools | `moderator-admin.sh`, `seal-at-rest.sh`, `audit_decrypt.py` |
| Other | `demo_fraud_burst.py`, `llm_check.py`, `osv_scan_maven.py` |

---

# 6. Deployment

| Path | What it holds |
|---|---|
| `docker-compose.yml` | four services: database, ML service, application, HTTPS proxy |
| `docker/postgres/init.sh` | creates the schema, applies the migrations, creates the restricted role |
| `docker/tomcat/server.xml` | hardened Tomcat settings |
| `docker/app/entrypoint.sh` | starts the application container |
| `docker/caddy/Caddyfile` | the HTTPS proxy |
| `.github/workflows/ci.yml` | the build and test pipeline |
| `.env.example` | the settings template |

---

# 7. `docs/`

| Group | Files |
|---|---|
| Design | `PROJECT_OVERVIEW`, `TRUST_SCORE`, `TRUST_AND_NETWORK_SCORES`, `CREDENTIAL_TRUST`, `REPLAY_DETECTION` |
| Reference | `API_REFERENCE`, `SECURITY_RUNBOOK`, `PRIVACY_DPDP` |
| Security | `SECURITY_ARCHITECTURE`, `STRIDE_THREAT_MODEL`, `PENTEST_REPORT`, `PENTEST_RESULTS`, `SECURITY_SCAN_REPORT` |
| Testing and status | `TEST_REPORT`, `IMPLEMENTATION_STATUS` |
| Review material | `IMPLEMENTATION_WALKTHROUGH` (md and PDF), `PROJECT_REVIEW_ROLES` (md and PDF), `CONTRIBUTIONS`, `FOLDER_BREAKDOWN`, `review-screenshots/`, `screenshots/` |

---

# 8. Root

| File | What it is |
|---|---|
| `README.md`, `README_JAVA_FEATURES.md` | the overview and the Java-features guide |
| `.env.local`, `data/` | development secrets and runtime data (not meant to be shared) |
