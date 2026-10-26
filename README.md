# Kaarigar Setu

A voice-first skill assessment and trust passport for tradespeople in India. A worker answers trade questions by voice; an AI scores
the answers against a rubric; past employers confirm the work by one-time code; a graph model turns those confirmations into a trust score; and a
cryptographically signed **Skill & Trust Passport** (with a QR code) can be checked by anyone, no login needed. A human moderator reviews anything
the fraud model flags.

> **Java features:** see [README_JAVA_FEATURES.md](README_JAVA_FEATURES.md): the abstract class and polymorphism, user-defined exceptions, Collections, file handling, JDBC, Servlets, JSP and JavaFX, each with a link to the exact code.

The base for a real electrician pilot.

> **Status:** all ten blueprint phases are implemented and tested (see [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md)).
> **Not yet real:** the electrician rubric is a clearly marked TEST ONLY placeholder (the real one is supplied separately); OTPs are printed to the
> server log (no SMS gateway yet); a real LLM has not been run, and Whisper has only been tried on synthetic speech; Docker images have not been built on the development machine.
> [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) says exactly what was and was not verified.

## Architecture

```
 browser (JSP + vanilla JS, strict CSP)     JavaFX moderator console
              |  HTTPS                                |  HTTPS (REST)
              v                                       v
       +-------------- TLS proxy (Caddy) ---------------+            only the proxy is published
                              |
                              v
        Java 17 WAR on Tomcat 10.1 (servlets, filters, JDBC DAOs)  ---- internal token, HTTP/1.1 ---->  Python 3.11 FastAPI ML service
          AuthFilter | CsrfFilter | SecurityHeaders                                                      /internal/ml/transcribe  (Whisper)
          services: assessment, attestation, trust, credential,                                          /internal/ml/evaluate    (LLM + keyword)
                    moderator, privacy (erasure)                                                         /internal/ml/fraud-check (Isolation Forest)
                              |                                                                          /internal/ml/trust-score (PageRank)
                              v
                     PostgreSQL 16  (least-privilege role, hash-chained append-only audit_log)
```

## Repository layout

```
kaarigarsetu-java/               Java web application (WAR): servlets, filters, services, JDBC DAOs, JSP pages, tests
kaarigarsetu-ml-service/         Python ML microservice and its tests, training and evaluation scripts
kaarigarsetu-moderator-console/  JavaFX moderator console (separate Maven module)
database/                        schema.sql, migrations/, setup_roles.sql (least-privilege role), seed.sql
docker/ + docker-compose.yml     Postgres init, hardened Tomcat config, TLS proxy; the demo/pilot stack
scripts/                         demo-seed.sh, console.sh, dev-setup.sh, dev.sh, e2e.py, coverage.sh, compose-setup.sh, osv_scan_maven.py, demo_fraud_burst.py
.github/workflows/ci.yml         build -> tests -> dependency scan -> end-to-end -> images -> artifact
docs/                            API reference, test report, ML evaluation, runbook, demo script, privacy/DPDP, threat model
```

## Quick start (local development, macOS or Linux)

Prerequisites: JDK 17 or newer (21 works; the code is compiled for the Java 17 API), Maven 3.9+, Python 3.11, PostgreSQL 15 or 16 (`psql`, `createdb`), `openssl`.

```bash
./scripts/dev-setup.sh      # once: generates secrets into .env.local (git-ignored), database + migrations + least-privilege role, signing key, Python venv, WAR
mkdir -p data/logs && ./scripts/dev.sh 2>&1 | tee data/logs/dev.log      # ML service on :8001, Tomcat on :8090
```

`dev-setup.sh` also creates two development moderators, `moderator` and `moderator2` (passwords `MODERATOR_PASSWORD_PLAIN` and `MODERATOR2_PASSWORD_PLAIN` in `.env.local`, sign-in codes printed like the others with `purpose=moderator`), because vetting and dispute rulings need a second person. Other moderators are created with `./scripts/moderator-admin.sh add <username> "<Name>" <phone>` (see [`docs/SECURITY_RUNBOOK.md`](docs/SECURITY_RUNBOOK.md)).

`dev-setup.sh` also installs the speech-to-text engine (`SKIP_WHISPER=1` to skip) and `dev.sh` uses it by default: `STT_PROVIDER=faster-whisper`, the same Whisper large-v3-turbo model in 8-bit (about 0.8 GB, about 1.3 GB RAM, no PyTorch), downloaded on first use at a pinned revision. The original engine stays available with `STT_PROVIDER=whisper` (openai-whisper + PyTorch, about 1.6 GB model and 3+ GB RAM; for it:  if Python cannot verify certificates, fetch the file listed by `python -c "import whisper; print(whisper._MODELS['large-v3-turbo'])"` with `curl` into `~/.cache/whisper/`). `STT_PROVIDER=stub` is a test fixture that **ignores the audio** and returns labelled canned sentences: never use it to judge the assessment.

Open <http://localhost:8090>. OTP codes appear in that terminal as `[CONSOLE OTP] purpose=... code=...`. If the app was started in the background (or you do not see the terminal), run `./scripts/otp.sh -f` in any terminal to watch codes live, or `./scripts/otp.sh` for the latest ones; it reads `data/logs/dev.log` inside this repository, wherever the repository is. The moderator password is `MODERATOR_PASSWORD_PLAIN` in `.env.local`.

**Phone microphone (HTTPS).** Browsers give a page the microphone only over `https://` (or `http://localhost` on the same computer). A phone that opens
the app by your computer's network address over plain `http://` therefore shows "The microphone only works on a secure page". For development,
start the app (`./scripts/dev.sh`), then in a second terminal run `./scripts/dev-https.sh`: it makes a self-signed certificate for this computer
(kept in `data/`, git-ignored) and serves the app on `https://<your-computer>:8443`. Open that address on the phone (same Wi-Fi), accept the
certificate warning once, and recording works. Development only; for real users terminate TLS properly (the Caddy setup in `docker-compose.yml`).


`dev-setup.sh` uses the current OS user as the database owner and creates a restricted role `kaarigar_app` that the application connects as. If Postgres is
elsewhere, set `PGHOST`, `PGUSER`, `PGPASSWORD` (and `DB_NAME`) before running it.

## Quick start (docker compose)

```bash
./scripts/compose-setup.sh          # once: random secrets into .env (git-ignored), builds images, prints how to create the moderator accounts
docker compose up -d                # Tomcat + ML + Postgres + Caddy (TLS)
```

Open <https://localhost:8443> (accept the local-CA certificate warning). Only the proxy publishes ports; Postgres and the ML service are on internal networks. Codes:
`docker compose logs -f app`. Whisper is opt-in (`docker compose build --build-arg WITH_WHISPER=1 ml`, then `STT_PROVIDER=whisper` in `.env`). Back up the `appdata` volume: it holds the signing keystore.

## Demo accounts (development only)

```bash
./scripts/demo-seed.sh            # nine made-up workers and seven employers with assessments, employer feedback, credentials and computed scores
./scripts/demo-seed.sh --reset    # delete them and create them again
```

Then open <http://localhost:8090/demo> and press **Sign in** next to anyone: no registration, no code. The page and the one-click sign-in exist only when `APP_ENV=dev` **and** `DEMO_LOGIN=true`
(the setup script writes that into `.env.local`; the app refuses to start with `DEMO_LOGIN=true` in any other environment), and they only ever open accounts made by the seeder
(consent version `DEMO-SEED`); a real account is refused. The seeder connects as the database owner, refuses to run unless `APP_ENV=dev`, and uses made-up phone numbers 90000 00101 to 00299. See `docs/TRUST_SCORE.md`
for how the trust scores in the demo data come about.

## Credential QR codes and phones

A credential's QR code holds a link to its public verification page, built from `PUBLIC_BASE_URL`. **That address must be reachable from the phone that scans it**: on a phone, `localhost` means the phone itself, so a QR that says `http://localhost:8090/...` scans fine but opens nothing.

* `./scripts/dev.sh` therefore uses this computer's network address by default (it prints `Credential QR codes will open: http://<address>:8090`). The phone must be on the **same network** as the computer. Some campus, office and public Wi-Fi networks block devices from reaching each other ("client isolation"); in that case use a phone hotspot that the computer joins, or publish the app on a real address.
* To choose the address yourself: `PUBLIC_BASE_URL=http://192.168.1.20:8090 ./scripts/dev.sh`.
* For docker compose, set `PUBLIC_HOST` and `PUBLIC_BASE_URL` in `.env` (for example `PUBLIC_BASE_URL=https://192.168.1.20:8443`). A phone will warn about Caddy's self-made certificate; a real domain name gets a normal certificate.
* The worker dashboard shows the exact address inside the QR and warns when it is a `localhost` address. If you change the address, issue the credential again (the QR is drawn from the current setting, and each issuance supersedes the old credential).
* The QR image is built to scan reliably: whole-pixel modules, the standard 4-module quiet zone and error-correction level Q; a test decodes it the way a scanner would, including after scaling and with part of it damaged.

## Tests

```bash
# Java (needs a throw-away database whose name ends in _test; the tests wipe it)
createdb ks_blueprint_test
cd kaarigarsetu-java && TEST_DB_URL=jdbc:postgresql://localhost:5432/ks_blueprint_test mvn test       # 498 tests, JaCoCo report in target/site/jacoco
cd kaarigarsetu-moderator-console && mvn test                                                            # 18 tests
# Python
cd kaarigarsetu-ml-service && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest --cov=app   # 398 tests
# End to end against a running stack (192 checks) and merged unit + e2e Java coverage.
# The scripted checks upload synthetic tones, so start the stack with the labelled test fixture:  STT_PROVIDER=stub ./scripts/dev.sh
JAVA_LOG=data/logs/dev.log python3 scripts/e2e.py
TEST_DB_URL=jdbc:postgresql://localhost:5432/ks_blueprint_test ./scripts/coverage.sh
# Attack-and-defence run: 57 real attacks against the running development stack (see docs/PENTEST_REPORT.md)
BASE_URL=http://localhost:8090 JAVA_LOG=data/logs/dev.log python3 scripts/security_attack_demo.py
# Static analysis (SpotBugs + find-sec-bugs) and software bill of materials
cd kaarigarsetu-java && mvn -Psast -DskipTests compile spotbugs:check cyclonedx:makeAggregateBom
# Dependency vulnerability scans (what CI runs)
.venv/bin/python -m pip_audit -r kaarigarsetu-ml-service/requirements.txt ; python3 scripts/osv_scan_maven.py
```

The JavaFX console: `cd kaarigarsetu-moderator-console && mvn package && java -jar target/kaarigarsetu-moderator-console.jar [https://host]`.

## Configuration

Secrets only ever come from the environment (or the keystore); nothing secret is in the repository. `.env.local` (dev) and `.env` (compose) are git-ignored; `.env.example` is a template.

| Variable | Used by | Purpose |
|---|---|---|
| `APP_ENV` | both | `dev`, `staging`, `prod`. `prod` makes the Java app refuse to start without a secure cookie, ML token, two-person approval switched on and a deliberate `ALLOW_CONSOLE_OTP=true`, and the ML service refuse the stub speech-to-text |
| `DB_HOST/PORT/NAME/USER/PASSWORD` | Java | database connection (as the restricted role `kaarigar_app`) |
| `ML_SERVICE_URL`, `ML_INTERNAL_TOKEN` | Java | where the ML service is and the shared secret sent as `X-Internal-Token` |
| `INTERNAL_API_TOKEN` | ML | the same shared secret (named differently on the Python side) |
| `DATA_ENC_KEYS` (`id:base64,id:base64`, 32 bytes each; or `DATA_ENC_KEY`), `REQUIRE_SEALED_AT_REST` | Java | encrypts stored transcripts, scores, ratings, names, signed payloads, the audit file and credential exports (AES-256-GCM, first key encrypts, all decrypt; rotation in the runbook). Required; must not reuse `PHONE_ENC_KEY`; back it up apart from the data. `REQUIRE_SEALED_AT_REST` refuses a plain stored value (on in prod) |
| `PHONE_ENC_KEY` (base64, 32 bytes), `PHONE_HMAC_KEY` (base64) | Java | AES-GCM encryption of phone numbers; keyed HMAC for the lookup key and the attempt-token signature. **Do not rotate casually** |
| `OTP_PEPPER` | Java | server-side secret mixed into OTP hashes |
| `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` | Java | PKCS#12 keystore with the credential-signing RSA key (create once with `scripts/generate-signing-key.sh`; `RSA_KEY_BITS` default 3072). **Back it up** |
| `AUDIT_LOG_PATH`, `AUDIT_HMAC_KEY` | Java | the hash-chained audit file and its HMAC key |
| `TWO_PERSON_ACTIONS` (`VET_EMPLOYER,DISPUTE_DECISION`), `TWO_PERSON_LIFETIME_HOURS` (72) | Java | the moderator actions that need a second, different moderator to approve, and how long a proposal waits. `none` (development only) switches it off; `APP_ENV=prod` refuses that. Moderators themselves are accounts in the database, made with `scripts/moderator-admin.sh` (not environment variables) |
| `PUBLIC_BASE_URL` | Java | used in QR codes and verification links |
| `COOKIE_SECURE`, `TRUST_FORWARDED_FOR` | Java | `Secure` cookie flag; trust the last `X-Forwarded-For` (only behind a proxy that overwrites it) |
| `PASS_THRESHOLD` (70), `BORDERLINE_THRESHOLD` (50), `BAND_BOUNDARY_MARGIN` (5) | Java | skill bands: Pass at 70 and above, Borderline from 50, otherwise Needs review; a score within the margin of a threshold is reported as close to the edge |
| `OTP_POW_BITS` (16), `TRUSTED_DEVICE_DAYS` (180), `MAX_TRUSTED_DEVICES` (5) | Java | the proof-of-work difficulty an unknown browser solves before a login code is sent (0 = off), how long an unused trusted device is remembered, and how many per account |
| `REPLAY_CHALLENGE` (true), `REPLAY_CHALLENGE_TRIES` (3), `REPLAY_CHALLENGE_MINUTES` (15) | Java | the spoken code every answer must contain (made when the question is shown, valid for the minutes given), and how many times a worker may try before the answer is stored and held for review |
| `REPLAY_DETECTION` (true), `REPLAY_MIN_MATCHES` (25), `REPLAY_MIN_LANDMARKS` (100), `REPLAY_TEXT_MIN_SHINGLES` (14), `REPLAY_TEXT_MIN_COMMON` (8), `REPLAY_TEXT_MIN_SHARE` (0.5) | Java | recognising a recording or a set of words that was used before; the thresholds were measured on real speech ([`docs/REPLAY_DETECTION.md`](docs/REPLAY_DETECTION.md)); refused to be switched off when `APP_ENV=prod` |
| `DISPUTE_WINDOW_DAYS` (30), `DISPUTE_MAX_OPEN` (3) | Java | how long after approval a worker may dispute a confirmation about them, and how many unresolved disputes one worker may have at once |
| `DEMO_LOGIN` (false) | Java | dev-only one-click sign-in at `/demo` for seeded demo accounts; refused unless `APP_ENV=dev` |
| `OTP_TTL_SECONDS` (300), `OTP_MAX_ATTEMPTS` (5), `CREDENTIAL_VALID_DAYS` (180), `MIN_TRUST_FOR_CREDENTIAL` (0), `MAX_AUDIO_BYTES` (2 MB), `DB_POOL_SIZE` (10) | Java | tunables |
| `STT_PROVIDER` (`faster-whisper`\|`whisper`\|`stub`), `WHISPER_MODEL` | ML | speech-to-text (`stub` refused in prod) |
| `EVALUATOR` (`llm`\|`keyword`), `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL` | ML | scorer; `llm` is chosen automatically when a key is set |

## Security design in one page

Details and evidence: [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) section 4 (OWASP Top 10), [`docs/STRIDE_THREAT_MODEL.md`](docs/STRIDE_THREAT_MODEL.md).

* **Authentication**: phone + OTP (6 digits, PBKDF2 hash + pepper, 5 attempts, 5 minutes, single use, persisted lockout; a returning browser is a trusted device with its own request budget and its own code, every code is bound to the browser that asked for it, and an unknown browser solves a small proof-of-work puzzle first, so a stranger cannot lock someone out by using up their number's budget); moderator = a named account, password + an OTP sent to that person's own phone, and vetting and dispute rulings need a second moderator to approve; new session id at every login; per-phone/IP/user rate limits; unknown accounts indistinguishable from known ones.
* **Authorisation**: deny-by-default `AuthFilter` (role per route), owner checks on credentials, CSRF token + Origin check, strict CSP and security headers.
* **Data**: phone numbers AES-GCM encrypted with an HMAC lookup key, and everything else sensitive encrypted at rest too (transcripts, scores, ratings, names, signed payloads, the audit file, credential exports: AES-256-GCM, keys outside the database, `scripts/seal-at-rest.sh`), credentials identify a worker only by a random pseudonymous id, raw audio never stored, consent recorded before anything is saved, a documented and tested deletion path ([`docs/PRIVACY_DPDP.md`](docs/PRIVACY_DPDP.md)).
* **Integrity**: RSA-3072 signed credentials (key in a PKCS#12 keystore, survives restarts), revoke / expire / supersede; hash-chained append-only audit log (database triggers + keyed file chain) with a verifier.
* **Replayed voice**: every answer must include a spoken code made at that moment, and every recording is fingerprinted (keyed hashes of acoustic landmarks and word trigrams, never audio) and compared with every earlier answer; a recording or set of words that was used before is held for review with no score, and the credential waits ([`docs/REPLAY_DETECTION.md`](docs/REPLAY_DETECTION.md)).
* **Untrusted input**: PreparedStatement only (a test enforces it, plus a SQL-injection fuzz over every DAO), WAV header validation, LLM transcript fencing and score clamping, strict validation of every ML response, ML failure fails safe to human review.
* **Supply chain**: pinned dependencies, `pip-audit` and OSV scans in CI, non-root read-only containers, hardened Tomcat (no shutdown port, no banner).

## Security review: what was attacked, measured and fixed

The project was attacked with its own tools, and every weakness found was fixed or written down. Start with [`docs/SECURITY_ARCHITECTURE.md`](docs/SECURITY_ARCHITECTURE.md) (assets, trust boundaries,
OWASP mapping, attack trees, risk register) and [`docs/PENTEST_REPORT.md`](docs/PENTEST_REPORT.md) (57 attacks, 53 blocked, 4 documented residual, what was found and fixed, what is still open).

* **Credentials anyone can verify**: RFC 8785 canonical signing, a key list, key rotation, a signed revocation list and an independent offline verifier (`scripts/verify_credential.py`); a short-lived **live code** shows whether a credential is being shown by its owner now ([`docs/CREDENTIAL_TRUST.md`](docs/CREDENTIAL_TRUST.md)).
* **Named moderators and two-person approval**: there used to be one shared moderator login, so one stolen or fooled login could vet a fake employer or remove a true rating, and the audit trail could only say "service-account". Moderators are now named people (made by an operator with `scripts/moderator-admin.sh`; the web application cannot create one), and vetting an employer or ruling on a dispute is proposed by one moderator and takes effect only when a different one approves; both names are recorded ([`docs/SECURITY_RUNBOOK.md`](docs/SECURITY_RUNBOOK.md)).
* **A say for the worker**: the code that confirms work goes to the employer's phone, so a worker could once be rated without ever being asked. Workers now see every confirmation about them and can accept or dispute it; a dispute holds it out of every score until a moderator rules ([`docs/TRUST_AND_NETWORK_SCORES.md`](docs/TRUST_AND_NETWORK_SCORES.md) section 1a).
* **Trust that is hard to buy**: a simulation showed three fake employer accounts could reach the top band; the band now needs an employer a moderator has vetted ([`docs/FRAUD_ATTACK_SIMULATION.md`](docs/FRAUD_ATTACK_SIMULATION.md)).
* **Audit trail that survives a rollback**: the audit file's newest line is anchored in the append-only database table; `AuditVerifyCli` checks a copy offline.
* **Detection**: security events are audited and the moderator console has Security and Employers tabs.
* **Pipeline**: SpotBugs + find-sec-bugs, Bandit, Semgrep, gitleaks, dependency scans, SBOM, the attack harness and a ZAP baseline in CI ([`docs/SECURITY_SCAN_REPORT.md`](docs/SECURITY_SCAN_REPORT.md)).
* A ten-minute live demonstration: [`docs/SECURITY_DEMO_SCRIPT.md`](docs/SECURITY_DEMO_SCRIPT.md).

## Documentation

| Document | What it is |
|---|---|
| [`docs/API_REFERENCE.md`](docs/API_REFERENCE.md) | every endpoint, roles, errors, rate limits, the internal ML API |
| [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md) | test results, coverage per domain, OWASP checklist with evidence, defects found and fixed, what was not tested |
| [`docs/TRUST_SCORE.md`](docs/TRUST_SCORE.md) | how the trust (credit) score is calculated from employer feedback and employer reputation, with the rules, bands and limits |
| [`docs/EXPERT_REVIEW.md`](docs/EXPERT_REVIEW.md) | what in the rubrics is an unvalidated draft and which safety-critical topics were deliberately left out |
| [`docs/RUBRIC_AUTHORING.md`](docs/RUBRIC_AUTHORING.md) | rubric file format, how a score is made, how to add or replace a trade |
| [`docs/ML_EVALUATION.md`](docs/ML_EVALUATION.md) | fraud-model precision/recall and trade-offs, LLM robustness, status of the human-agreement check |
| [`docs/SECURITY_RUNBOOK.md`](docs/SECURITY_RUNBOOK.md) | what to do when the audit log shows something suspicious, secret rotation, backups |
| [`docs/PRIVACY_DPDP.md`](docs/PRIVACY_DPDP.md) | data inventory, consent, disclosures, the deletion path |
| [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) | 12-minute demo: a worker journey and a fraud-to-moderator journey |
| [`docs/STRIDE_THREAT_MODEL.md`](docs/STRIDE_THREAT_MODEL.md) | threats, mitigations, residual risks |
| [`docs/SECURITY_ARCHITECTURE.md`](docs/SECURITY_ARCHITECTURE.md) | assets, trust boundaries, CIA and OWASP mapping, attack trees, risk register |
| [`docs/PENTEST_REPORT.md`](docs/PENTEST_REPORT.md), [`docs/PENTEST_RESULTS.md`](docs/PENTEST_RESULTS.md) | the attack-and-defence run: findings, fixes, what is open; the generated results table |
| [`docs/FRAUD_ATTACK_SIMULATION.md`](docs/FRAUD_ATTACK_SIMULATION.md) | fake endorsements as an attack, measured, and the vetting rule it led to |
| [`docs/CREDENTIAL_TRUST.md`](docs/CREDENTIAL_TRUST.md) | verifying a credential without trusting the app; key rotation; revocation; live codes |
| [`docs/SECURITY_SCAN_REPORT.md`](docs/SECURITY_SCAN_REPORT.md) | static analysis, secret scan, dependency scans, SBOM, every finding triaged |
| [`docs/SECURITY_DEMO_SCRIPT.md`](docs/SECURITY_DEMO_SCRIPT.md) | a ten-minute security demonstration |
| [`docs/Kaarigar_Setu_Security_Status.pdf`](docs/Kaarigar_Setu_Security_Status.pdf) | 16-page plain-language status: what works, what works only partly, what is not built, what was never run, and when each protection acts while the site runs |
| [`docs/Kaarigar_Setu_Implementation_Screenshots.pdf`](docs/Kaarigar_Setu_Implementation_Screenshots.pdf) | 27 pages, 28 figures: the web app, the moderator console and the command-line evidence, each captioned; every image is also in `docs/screenshots/` |
| [`docs/Kaarigar_Setu_Java_Implementation.pdf`](docs/Kaarigar_Setu_Java_Implementation.pdf) | 36 pages: the Java implementation (OOP, exceptions, collections, file handling, JDBC, servlets, JSP, JavaFX, cryptography, concurrency, the Java built for the security review, tests), 47 code listings pulled from the source with file and line |
| [`docs/REPLAY_DETECTION.md`](docs/REPLAY_DETECTION.md) | replay-voice detection: the spoken code, the acoustic and word fingerprints, measured results, and what it does not stop |
| [`docs/ACCENT_TRAINING.md`](docs/ACCENT_TRAINING.md) | measured speech-recognition accuracy on real voices and the plan to improve it |
| [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) | phase-by-phase checklist |

## Known limitations

TEST ONLY rubrics for all six trades (electrician, plumber, carpenter, mason, welder, painter); console OTP sender only; Whisper/LLM unverified with real models; fraud data synthetic; moderator account is shared (decisions are not attributable to a person);
the web UI's JavaScript was not driven in a real browser in the final test pass; Docker artefacts and GitHub Actions have never run (no Docker on the development machine).
