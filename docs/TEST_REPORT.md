# Test Report (Phase 8)

Everything below was run on the same commit of the code, on macOS (Apple Silicon), JDK 21 compiling for the Java 17 API
(`maven.compiler.release=17`), PostgreSQL 16, Python 3.11. Numbers are copied from the actual runs, not estimated.

## 1. Suites and results

| Suite | Where | Tests | Result | How to run |
|---|---|---|---|---|
| Java unit + database integration (JUnit 5, Mockito) | `kaarigarsetu-java/src/test` | **498** | all pass | `cd kaarigarsetu-java && TEST_DB_URL=jdbc:postgresql://localhost:5432/<name>_test mvn test` |
| ML service (pytest) | `kaarigarsetu-ml-service/tests` | **398** | all pass | `cd kaarigarsetu-ml-service && .venv/bin/python -m pytest` |
| JavaFX console (JUnit 5) | `kaarigarsetu-moderator-console/src/test` | **18** | all pass | `cd kaarigarsetu-moderator-console && mvn test` |
| End-to-end against the running stack | `scripts/e2e.py` | **80 checks** in 10 sections | all pass | `STT_PROVIDER=stub ./scripts/dev.sh`, then `JAVA_LOG=data/logs/dev.log python3 scripts/e2e.py` (the scripted checks need the labelled test-fixture transcriber, because they upload synthetic tones, not speech) |
| **Total automated checks** | | **467** | **0 failures** | |

The Java database tests need a throw-away PostgreSQL database whose name ends in `_test` (they wipe it); without one they
skip themselves. Docker/Testcontainers is used instead when `TEST_DB_URL` is not set.

Java tests by area (before the rubric/trade additions): security 52, DAO 29 (incl. SQL-injection fuzz), attestation 16, web filters/session 14, assessment 11,
ML client 10, credential 9, worker/employer 8, trust 7, architecture guards 7, util 6, moderator 6, privacy/erasure 5, health 1.

## 2. Coverage per domain

Java coverage is JaCoCo. "Unit" is the Maven test run alone. "Merged" adds a live end-to-end run: Tomcat is started with the
JaCoCo agent, `scripts/e2e.py` drives it, and the two data files are merged (`./scripts/coverage.sh`). The servlet layer is
mostly exercised that way, which is why the two columns differ so much there.

| Domain | Packages | Unit instr. | Merged instr. | Merged branch |
|---|---|---|---|---|
| 1. Core Java / application | dao, worker, employer, assessment, attestation, moderator, privacy, web, config, model, exception, util | 64.2 % | **87.0 %** | 70.6 % |
| 2. Java-to-ML boundary | ml (client, response validation), trust (PageRank orchestration) | 89.9 % | **90.3 %** | 75.0 % |
| 3. Security | security, credential | 83.5 % | **93.1 %** | 83.0 % |
| **Java total** | | 71.4 % | **88.9 %** | 74.5 % |
| ML service (Python, line coverage) | `app/` | | **90 %** (641 statements, 62 missed) | |

Per package (merged instructions / branches): security 94.2 / 87.0, credential 91.0 / 74.4, dao 91.3 / 79.8, worker 93.7 / 79.4,
moderator 91.6 / 77.8, ml 91.2 / 75.0, attestation 88.5 / 75.8, trust 88.5 / 75.0, assessment 86.3 / 69.9, privacy 86.0 / 83.3,
employer 85.9 / 65.0, web 74.9 / 60.4, config 78.3 / 50.0.

Lowest-covered areas, honestly: `app/services/whisper_client.py` 59 % (Whisper is not installed here, see section 5), the
`train_isolation_forest.py` / `rubric_agreement.py` command-line entry points, and `config` (`DatabaseConfig`/startup validation
paths that only run in a real deployment).

## 2b. Scoring features added after the first test pass

| Feature | What is tested | Where |
|---|---|---|
| **Trust (credit) score** from employer feedback and employer reputation | the formula in isolation (28 tests: satisfaction weights, employer credibility, recency, engagement, leniency calibration, Bayesian shrinkage, confidence, penalties, tips, and a 3 000-case property test that every score is within 0 to 100 with a matching band); the service (PageRank standing changes weight, graceful degradation when the ML service is down, graph edge ranges, fraud rulings); the database queries; rating validation | `ReputationScoringTest`, `TrustScoreServiceTest`, `PipelineDaoIntegrationTest`, `AttestationWorkflowServiceTest`, e2e s.6 |
| **Skill bands and per-area breakdown** | band thresholds at the exact edges, near-boundary detection, area means and ordering, persistence, passport and credential fields | `SkillScoringTest`, `AssessmentPipelineServiceTest`, `CredentialServiceTest`, e2e s.5 and 7 |
| **Dev-only demo sign-in** | disabled unless `APP_ENV=dev` and `DEMO_LOGIN=true`; only seeded accounts, never a real one, never the moderator; the app refuses to start with it enabled elsewhere | `AuthServiceTest`, `AppSettingsDemoAndBandsTest`, e2e s.3 |
| **Demo data** | valid, made-up numbers only, covers the intended cases, and the model turns it into the intended story | `DemoSeedDataTest` |
| **QR codes** | decoded like a scanner would, after scaling, with damage; 4-module quiet zone | `QRGeneratorTest` |

## 3. The three Domain-3 checks the blueprint names

| Required check | What was done | Evidence |
|---|---|---|
| SQL-injection fuzzing against every DAO | Reflection finds every public DAO method with a `String` parameter (24 methods across 9 DAOs). Each String parameter is replaced in turn by 13 payloads (`' OR '1'='1`, stacked `DROP TABLE`/`TRUNCATE`/`DROP SCHEMA`, UNION, `$$`-quoting, NUL byte, 5 000-character string, ...): 429 calls. Pass = no SQL syntax error (SQLSTATE class 42) and every pre-existing row byte-identical afterwards, no table dropped. A control test runs the same payloads through *concatenated* SQL and confirms the detector fires, so a pass is meaningful. Two methods take a model record and are covered by the explicit literal-value tests instead. | `DaoSqlInjectionFuzzTest`, `DaoIntegrationTest.maliciousLookingInputIsStoredAndQueriedAsLiteralValues`, `PipelineDaoIntegrationTest` |
| No string-built SQL anywhere (standing rule) | Source scan of all production code: every `String sql = ...` and every `prepareStatement(...)` argument must be built from literals only; no `java.sql.Statement`. The guard is itself tested against a violating example. | `NoStringBuiltSqlTest` |
| OTP brute force expects lockout | Unit: 5 wrong guesses lock the challenge, the correct code is refused afterwards, codes expire at 5 minutes, single use, bound to role and phone, only a salted hash stored. Live: 5 wrong codes all 401, then the correct code is still refused; unregistered numbers get the identical response. | `OtpServiceTest`, `AuthServiceTest`, e2e section 4 |
| Tampered signature expects rejection | Unit: a one-byte change to the payload or the signature fails; garbage signatures never throw; weak (1024-bit) keys refused; a credential signed before a restart still verifies after it (PKCS#12 keystore) and is rejected by a different key. Live: tampered downloaded payload and a garbage signature both give `INVALID_SIGNATURE`; revoked gives `REVOKED`. | `SignatureServiceKeystoreTest`, `CredentialServiceTest`, e2e section 7 |

## 4. OWASP Top 10 checklist (blueprint section 7)

Status means: the mitigation exists in code **and** there is an automated test that would fail if it were removed.
The reviewer columns are intentionally blank: a person on the team has to read this and sign it.

| OWASP category | Mitigation | Automated evidence | Status | Reviewer / date |
|---|---|---|---|---|
| Injection | PreparedStatement only; PBKDF2/HMAC for secrets; strict input validation (phone, names, trade/language allow-lists, WAV header) | `DaoSqlInjectionFuzzTest`, `NoStringBuiltSqlTest`, `WavValidatorTest`, e2e "SQL-injection payload ... rejected / stored as plain text" | Met | |
| Broken authentication | OTP: 6 digits, PBKDF2 hash + server pepper, 5 attempts, 5 minutes, single use, persisted lockout; moderator = password (PBKDF2, 210 000 iterations) **plus** OTP; session id changes on login (fixation); rate limits per phone/IP/user; identical responses for unknown accounts | `OtpServiceTest`, `AuthServiceTest`, `SessionsTest`, `RateLimiterTest`, e2e sections 3, 4, 9 | Met (see residual risk R1) | |
| Sensitive data exposure | Phone numbers: AES-GCM at rest + keyed HMAC lookup key; transcripts, scores, ratings, names, payloads, the audit file and credential exports AES-256-GCM encrypted at rest (`EncryptionAtRestIntegrationTest`, harness DATA-06 and DATA-07); credential subject is a pseudonymous UUID (no name/phone); RSA-3072 signatures; secrets only from environment / keystore; audit log masks phone-shaped values; raw audio zeroed after transcription; TLS at the proxy, `Secure`/`HttpOnly`/`SameSite=Strict` cookie, HSTS | `PhoneProtectorTest`, `AuditLoggerTest`, `CredentialServiceTest.issuedCredentialVerifiesAndHoldsNoDirectIdentifiers`, `AssessmentPipelineServiceTest`, `PipelineDaoIntegrationTest.phoneNumbersAreNeverStoredInTheClear...`, e2e "credential never contains name or phone", "queue exposes no phone numbers" | Met in code; TLS verified only as configuration (R3) | |
| Broken access control | Deny-by-default `AuthFilter` (role per route; anything unlisted under `/api/` needs a session); owner checks (a worker cannot read or revoke another worker's credential, 404 not 403); employer/worker/moderator separation; CSRF token + Origin check on every state-changing request | `AuthFilterTest`, `CsrfFilterTest`, e2e section 3 and 7 | Met | |
| Security misconfiguration | Startup validation refuses a weak/missing configuration in prod; least-privilege DB role (DML only, DELETE on three tables, audit log append-only); hardened Tomcat (no shutdown/AJP port, no version banner, no stack traces); security headers incl. strict CSP | `AppSettings.validate`, e2e section 1 (headers), hardened `server.xml` run end to end, privilege check of `database/setup_roles.sql` | Met | |
| Cross-site scripting | `c:out` on every EL expression, no scriptlets (compile error), no inline script or handlers (CSP `script-src 'self'`), browser code never uses `innerHTML`/`eval` | `XssGuardTest` (also proves it would catch a violation), e2e CSP check | Met | |
| Insecure deserialization / untrusted data | Java validates every ML response (types, ranges, lengths) before use; ML side validates with strict pydantic schemas; LLM prompt fenced with a nonce, scores clamped and totals recomputed, injection attempts flagged for review; no Java serialization used | `MlServiceClientTest`, ML `test_evaluate.py` (prompt-injection cases) | Met | |
| Vulnerable components | Pinned versions; `pip-audit` and OSV scan in CI; Tomcat/pgjdbc/starlette/python-multipart upgraded after the scan found advisories (section 6) | `pip-audit -r requirements.txt` and `scripts/osv_scan_maven.py`: no known vulnerabilities (run 2026-10-07) | Met for application dependencies; OS/base-image packages not gated (R4) | |
| Insufficient logging & monitoring | `AuditLogger` writes every security-relevant event to a hash-chained DB table (append-only triggers, `verify_audit_chain()`) and an HMAC-keyed hash-chained file; moderator console and `GET /api/moderator/audit-check` verify them | `ChainedAuditFileSinkTest`, `PipelineDaoIntegrationTest.auditLogIsAppendOnlyAndHashChained`, e2e "audit chain still verifies" | Met | |

## 5. What was not tested, and why

* **The LLM scorer with a real model**: no API key was available. The wiring, validation, fail-safe behaviour and prompt-injection
  defences are tested with a fake LLM transport; the real provider is unverified. Until it is, scoring is keyword matching, which checks
  which words were said and cannot judge correctness.
* **Whisper on real worker speech.** Whisper (`large-v3-turbo`, chosen by a Hindi benchmark, see `docs/ML_EVALUATION.md`) was run for real, through the whole stack, but only on *synthetic* speech (macOS
  Hindi and Indian-English text-to-speech voices, clean audio). A correct Hindi answer was transcribed with spelling variants (fixed in the
  keyword matcher), a long English sentence came back complete, and unrelated answers scored 0. Accuracy on real workers (accents, dialects,
  background noise, phone microphones) has **not** been measured; do that with real recordings before relying on it.
* **Rubric agreement with a human grader** (blueprint Domain 2): not possible honestly. It needs the real rubric and
  human-graded transcripts, neither of which exist yet. The measurement tool is written and its arithmetic is tested
  (`app/training/rubric_agreement.py`); see `docs/ML_EVALUATION.md` section 4.
* **Browser behaviour of the JavaScript** (microphone recording, WAV encoding, page flows) was not driven in a real browser in
  this phase; the pages are rendered and their security properties are checked by tests and the e2e script, and the
  JavaFX console was rendered and driven against the live backend in Phase 6.
* **The Docker images and `docker compose up`**: Docker is not installed on the development machine. The pieces were
  validated separately against the real components (Postgres init script on a fresh database, entrypoint keystore and
  hash modes, the exact hardened `server.xml` on Tomcat 10.1.60 with the full e2e suite) and the YAML/shell syntax was
  checked, but the images have never been built and the compose file has never been started. The CI `images` job is the first real
  build.
* **GitHub Actions**: the workflow's YAML parses and each command in it was run locally in the same order, but it has not
  run on GitHub.
* **Load / performance** and **penetration testing by a third party**: out of scope.

## 6. Defects and gaps found while testing (and fixed)

| # | Finding | Found by | Fix |
|---|---|---|---|
| 1 | **No DPDP deletion path existed** (an `erased_at` column but nothing used it). | Reviewing the services for Phase 8 | `ErasureService` + `POST /api/worker/erase` and `/api/employer/erase`, UI card, `docs/PRIVACY_DPDP.md`; 5 DB-level tests + 13 e2e checks |
| 2 | The role filter had **no rule for `/api/employer/**`**, so under deny-by-default any logged-in role could reach an employer endpoint. | Writing the erase endpoint | Rule added; `AuthFilterTest.employerAccountEndpointsAreEmployerOnly`; servlet also checks the role |
| 3 | The app DB role could not DELETE, so erasure returned HTTP 500 in the live stack (unit tests had passed). | e2e section 10 | `GRANT DELETE` on exactly `workers, trust_edges, otp_challenges` in `setup_roles.sql` |
| 4 | **Known vulnerabilities in pinned dependencies**: starlette 0.37.2 (7 advisories), python-multipart 0.0.9 (7; it parses every audio upload), PostgreSQL JDBC 42.7.3 (2), Tomcat 10.1.34 (22), pytest in the runtime requirements. | `pip-audit`, OSV | fastapi 0.142.2 / starlette 1.7.0 / python-multipart 0.0.32, pgjdbc 42.7.13, Tomcat 10.1.60; pytest moved to `requirements-dev.txt`; all 58 ML tests re-run; re-scan clean |
| 5 | Java was compiled with `-source/-target 17` on JDK 21, which does not stop use of newer APIs. | Reviewing the build | `maven.compiler.release=17` (the whole code base and tests still compile) |
| 6 | `httpx` is a runtime dependency of the ML service but sat only in the test requirements. | Dockerfile review | Moved to `requirements.txt` |
| 7 | The OTP-stub test of unknown accounts compared exception *messages*, which differ. | `AuthServiceTest` | Not a defect: clients only see the HTTP status and code (`ApiErrors` never returns the message). The test now asserts what a client sees. |

## 7. Residual risks and open items

* **R1 OTP hash strength.** A 6-digit code has only 10^6 values, so a leaked database could be brute-forced offline even with
  PBKDF2. The server-side `OTP_PEPPER` (kept outside the database) and the 5-minute expiry are the mitigations; keep the pepper secret.
* **R2 OTP delivery.** Only a console sender exists; codes appear in the server log. Before real use an SMS gateway must be plugged
  into `OtpSender`, and `APP_ENV=prod` deliberately refuses to start until `ALLOW_CONSOLE_OTP=true` is set on purpose.
* **R3 TLS** is terminated by the Caddy proxy in `docker-compose.yml`; it has not been exercised here (no Docker).
* **R4 Image scanning** (Trivy) is report-only in CI so base-image findings do not block; decide a gating policy.
* **R5 The rubric is TEST ONLY.** Scores from the placeholder rubric mean nothing; the real electrician rubric must be supplied.
* **R6 Synthetic fraud data.** Isolation Forest metrics demonstrate the method only.
