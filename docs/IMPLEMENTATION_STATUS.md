# Implementation Status

Phases follow the project's implementation blueprint. Phases 0 to 2 are the foundation (project set-up, the core Java classes and the database); phases 3 to 9 were completed on top of
their code (their schema, DAO signatures and service classes are kept; changes are additive, in `database/migrations/002_security_and_pipeline.sql`).
"Verified" says how it was checked; see `docs/TEST_REPORT.md` for numbers and for what was not tested.

| Phase | Status | Verified by |
|---|---|---|
| 0 Environment | Complete | `dev-setup.sh`, `dev.sh`, CI, `/health` on both services |
| 1 Database and DAO layer | Complete | `DaoIntegrationTest`, `PipelineDaoIntegrationTest`, `DaoSqlInjectionFuzzTest` against PostgreSQL 16 |
| 2 Core OOP service layer | Complete | JUnit/Mockito suites for every service (498 Java tests) |
| 3 ML service | Complete; Whisper verified on synthetic speech, LLM scorer unverified | 398 pytest tests; six trades, 10 questions each, TEST ONLY unvalidated drafts (`docs/EXPERT_REVIEW.md`; `docs/RUBRIC_AUTHORING.md`) (`docs/RUBRIC_AUTHORING.md`); Whisper `large-v3-turbo` run through the full stack with macOS-voice recordings; a **real LLM** not run (fake transport used); accuracy on real worker speech not measured |
| 4 Security layer | Complete | `security` package 94 % covered; OTP lockout, signatures, CSRF, role matrix, audit chain tested |
| 5 Web integration | Complete | 73-check end-to-end run against the live stack |
| 6 JavaFX moderator console | Complete | 18 tests; rendered and driven against the live backend |
| 7 Credential issuance and verification | Complete | signed/QR/verify/tamper/revoke/expire/supersede, unit + e2e |
| 8 Testing, all domains | Complete except the human-agreement check | `docs/TEST_REPORT.md`, `docs/ML_EVALUATION.md` |
| 9 Deployment and documentation | Written; Docker artefacts and CI never executed | see below |

## Added after the first full pass

* **Trust (credit) score engine**: structured employer feedback (quality, punctuality, conduct, would-hire-again), employer credibility and rater-leniency calibration, recency, Bayesian combination, an explainable report with tips; `docs/TRUST_SCORE.md`.
* **Skill bands and per-area breakdown** (Pass / Borderline / Needs review; tools, materials, experience, knowledge, troubleshooting, planning, customer), on the dashboard, the end of the assessment, the signed credential and the verification page.
* **Demo accounts**: `scripts/demo-seed.sh` and a dev-only one-click sign-in page.
* Moderator console launcher (`scripts/console.sh`) and session-expiry handling; QR codes that scan from a phone.

## Added beyond the blueprint (needed by project rules)

* **DPDP consent and deletion path**: consent timestamp and version stored; `ErasureService`, `POST /api/worker/erase`, `POST /api/employer/erase`, dashboard card, `docs/PRIVACY_DPDP.md`.
* **Phone numbers**: AES-GCM at rest, HMAC lookup key, masked in logs.
* **Everything else sensitive, at rest** (migration 010): transcripts, feedback, scores, ratings, job details, names, dispute reasons, fraud features, signed credential payloads, audit details, the audit file and the credential exports are AES-256-GCM encrypted by the application; see `docs/SECURITY_RUNBOOK.md` ("Encryption at rest").
* **Replay-voice detection** (migration 011; [`REPLAY_DETECTION.md`](REPLAY_DETECTION.md)): a per-question spoken code made when the question is shown, an acoustic-landmark fingerprint of every recording and a word-trigram fingerprint of every transcript (keyed hashes, never audio) compared with every earlier answer; a recording or set of words used before is stored held for review with no score, and the credential waits. Calibrated on real speech and real Whisper; its limits (a fresh recording, a live helper, a speed change for the audio check, no speaker verification) are written down.
* **Least-privilege database role** (`database/setup_roles.sql`) and a hash-chained, append-only audit log in the database as well as a keyed file chain.
* **Dependency upgrades after scanning**: fastapi/starlette/python-multipart, PostgreSQL JDBC, Tomcat 10.1.60 (details in `docs/TEST_REPORT.md` section 6).

## Phase 9 deliverables

| Deliverable | Where | State |
|---|---|---|
| `docker compose up` demo environment | `docker-compose.yml`, `docker/`, both Dockerfiles, `scripts/compose-setup.sh` | **Not run** (no Docker on the development machine). The Postgres init script, the entrypoint and the exact hardened Tomcat config were run against real components; the compose file and images were not built. |
| CI pipeline | `.github/workflows/ci.yml`, `.github/dependabot.yml` | Written; every step was run locally in the same order; **has not run on GitHub**. Trivy image scan is report-only. |
| Developer README | `README.md` | Done |
| API reference | `docs/API_REFERENCE.md` | Done |
| Security runbook | `docs/SECURITY_RUNBOOK.md` | Done |
| Demo script | `docs/DEMO_SCRIPT.md`, `scripts/demo_fraud_burst.py` | Done; the helper was run against the live stack |

## Open items

1. **The real electrician rubric** (the placeholder in `kaarigarsetu-ml-service/app/models/rubrics/` is flagged TEST ONLY).
2. **Rubric agreement with a human grader**: tool written (`app/training/rubric_agreement.py`), needs the rubric, 30+ graded transcripts and an LLM key.
3. **An SMS provider** behind `OtpSender`; until then OTPs go to the server log.
4. **Whisper on real worker recordings** (accents, noise, phone microphones) and the **LLM scorer** with a real key.
5. **First real Docker build and CI run**; then decide whether Trivy findings gate the pipeline.
6. **Legal review** of the consent text and a retention period (`docs/PRIVACY_DPDP.md`).
7. ~~Individual moderator accounts~~ (done: migration 008, named accounts and two-person approval; see `docs/SECURITY_RUNBOOK.md`).
