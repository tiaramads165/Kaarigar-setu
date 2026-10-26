# Kaarigar Setu: security architecture (for the Information Security course)

This page is the map: what is being protected, from whom, which control does what, and which test or script proves it. Details live in the documents it
links to. Where something is a weakness or only partly done, it says so, because a security design that hides its gaps cannot be reviewed.

| Document | What it holds |
|---|---|
| this page | assets, trust boundaries, CIA mapping, OWASP mapping, attack trees, risk register |
| [`STRIDE_THREAT_MODEL.md`](STRIDE_THREAT_MODEL.md) | every threat, its control and its evidence, plus residual risks |
| [`PENTEST_REPORT.md`](PENTEST_REPORT.md) | the attack-and-defence run: what was attacked, what held, what was found and fixed |
| [`FRAUD_ATTACK_SIMULATION.md`](FRAUD_ATTACK_SIMULATION.md) | fake endorsements as an attack, measured, and the fix |
| [`SECURITY_SCAN_REPORT.md`](SECURITY_SCAN_REPORT.md) | static analysis, secret scan, dependency scans, SBOM |
| [`CREDENTIAL_TRUST.md`](CREDENTIAL_TRUST.md) | how a stranger verifies a credential without trusting the app; key rotation; revocation |
| [`SECURITY_RUNBOOK.md`](SECURITY_RUNBOOK.md) | operating it safely: keys, rotation, vetting, monitoring, incident steps |
| [`PRIVACY_DPDP.md`](PRIVACY_DPDP.md) | personal data, consent, erasure |
| [`SECURITY_DEMO_SCRIPT.md`](SECURITY_DEMO_SCRIPT.md) | a ten-minute live demonstration |

## 1. What is protected

| Asset | Why it matters | Where it lives |
|---|---|---|
| A worker's **skill and trust credential** | The product. A forged or inflated one harms employers who rely on it | signed JSON, `credentials` table, signing key in a PKCS#12 keystore |
| **Phone numbers** | Personal data (DPDP); also the login identity | AES-GCM encrypted, with a keyed hash for lookup; masked in logs |
| **Transcripts, scores, ratings, names, signed credential payloads, the audit trail** | Personal data and performance data about a named person; the contents of a stolen database, backup or audit file | AES-256-GCM encrypted at rest by the application (`DataCipher`), each value tied to its own column and row; the keys are in the environment, not in the database or the data directory; see section "Encryption at rest" in the runbook |
| **Voice answers** | Personal data | held in memory only, discarded after transcription |
| **Trust and skill scores** | Integrity of the ranking | database, recomputed by the scoring service |
| **The audit trail** | Accountability after an incident | append-only chained table + HMAC-chained file + database anchors |
| **The secrets**: the signing keystore, the encryption and HMAC secrets, the OTP pepper and the service tokens | Losing any of them breaks everything above | environment variables and a keystore file, never in the repository |

## 2. Trust boundaries

```
 Browser / phone / JavaFX console            untrusted, over the internet
        |  HTTPS (Caddy in production; dev-https.sh for phones in development)
        v
 Tomcat: SecurityHeadersFilter -> CsrfFilter -> AuthFilter (roles, deny by default) -> servlets (with throttles)
        |                                   |
        | X-Internal-Token (shared secret)  | JDBC, restricted role "kaarigar_app" (DML only, audit_log append-only)
        v                                   v
 Python ML service (internal network)       PostgreSQL           data/audit.log (+ .head)   data/credential-keystore.p12
 Whisper, rubric scoring, fraud model,
 PageRank
```

Rules that follow from it: nothing from outside is trusted (including the transcript produced from a worker's voice and anything an LLM returns); the ML
service is only reachable with the token and is not exposed; the database account the application uses cannot change the schema or edit the audit log.

## 3. Confidentiality, integrity, availability

| | Threat | Controls | Proof |
|---|---|---|---|
| **Confidentiality** | Phone numbers or voice leaking; a stolen database, backup or audit file | AES-GCM + HMAC lookup, masking in logs, pseudonymous credential subject, audio never stored, no phone numbers in any API response a stranger or moderator can read; transcripts, scores, ratings, names, payloads, the audit file and the credential exports encrypted at rest | `PhoneProtectorTest`, `AuditLoggerTest`, `DataCipherTest`, `EncryptionAtRestIntegrationTest`, `ChainedAuditFileSinkTest`, harness DATA-04, DATA-06, DATA-07, e2e s.7 and 9 |
| | Another user's data read (IDOR) | owner checks answer 404 (the same as "does not exist"); deny-by-default role rules | harness ACL-01, ACL-02, ACL-03, `AuthFilterTest` |
| | Session theft | `HttpOnly`, `SameSite=Strict`, `Secure` outside dev, new session id at login, 8-hour absolute limit, destroyed on logout | harness AUTH-04, AUTH-05 |
| **Integrity** | Credential edited or forged | RSA-SHA256 over RFC 8785 canonical JSON, key id, revocation, expiry, signed revocation list, offline verifier | harness CRED-01 to CRED-06 |
| | Score gamed (fake employers, recited rubric words) | distinct-employer confidence, vetted-employer rule for the top band, stuffing guard sends recited answers to a human | `TrustAttackSimulationTest`, harness FRAUD-01, ML-02 |
| | Audit trail altered | append-only table, hash chains, keyed head, database anchors | harness AUD-01, AUD-02 |
| | Injection (SQL, XSS, template, prompt) | `PreparedStatement` only (a test fails the build otherwise), contextual output escaping, strict CSP, transcript fencing and score re-validation | `NoStringBuiltSqlTest`, `DaoSqlInjectionFuzzTest`, harness INJ-01 to INJ-03, ML-03 |
| | Request forgery | CSRF token + Origin check + `SameSite=Strict` | harness CSRF-01 |
| **Availability** | Guessing, flooding, scraping | per-phone, per-address and per-user limits; sign-up and public-verification throttles; upload size and format limits | harness AUTH-01, AUTH-07, AUTH-10, DATA-01, DATA-02, DATA-05 |
| | ML service down | fail safe: attestations go to a human, nothing is auto-approved | `AttestationWorkflowServiceTest` |
| **Accountability** | "Who did that?" | every security event audited; moderator console shows alerts and integrity | `SecurityMonitorTest`, e2e s.9 |

## 4. OWASP Top 10 (2021)

| | Where it applies here | Status |
|---|---|---|
| A01 Broken access control | role per route, owner checks, 404 on others' resources, mass-assignment ignored | held (harness ACL-*, AUTH-09, ACL-08). The worker can now see and dispute confirmations about them (R3 mitigated) |
| A02 Cryptographic failures | AES-GCM, PBKDF2 (210,000), RSA-3072, HMAC, random UUIDs, no custom crypto; stored transcripts, scores, ratings, names, payloads and the audit file AES-256-GCM encrypted with a key ring (DATA_ENC_KEYS) | held (harness DATA-06, DATA-07); canonical signing added after the harness showed credentials could not be verified by other tools |
| A03 Injection | SQL, XSS, template, path traversal, prompt injection | held (harness INJ-*, ML-03) |
| A04 Insecure design | trust-score gaming, enumeration, QR replay | **found by the harness and simulation, then fixed:** Sybil ring, registration enumeration, static QR replay, keyword stuffing. R3 is now mitigated by the worker dispute flow (residual: FRAUD-02). Open: SIM farms to the Good band |
| A05 Security misconfiguration | headers, error pages, banners, dev flags | held in production config; dev Tomcat shows its default error page (residual, INJ-02); `TRUST_FORWARDED_FOR` must be off without a proxy |
| A06 Vulnerable components | pinned versions, pip-audit, OSV, Trivy, SBOM | clean at the time of writing ([`SECURITY_SCAN_REPORT.md`](SECURITY_SCAN_REPORT.md)) |
| A07 Authentication failures | OTP with lockout, moderator password + OTP, throttles, no user enumeration, no timing oracle | held (harness AUTH-*). The targeted lock-out of a known number is fixed for returning browsers (AUTH-08, AUTH-13: trusted devices, codes bound to the browser that asked, proof of work for unknown devices); **residual:** a victim on a brand-new device can still be delayed up to an hour (AUTH-12) |
| A08 Software and data integrity | signed credentials and revocation list, hash-verified model file, CI-built artifacts, SBOM | held |
| A09 Logging and monitoring failures | audited events, tamper-evident, anchored, surfaced in the console | held after the rollback gap (AUD-02) was closed |
| A10 SSRF | the application only calls its own ML service and an optional SMS gateway, both configured, never a URL from a request | not applicable to any user-controlled URL |

## 5. Other lists that apply

**OWASP API Security Top 10 (2023).** API1 object-level authorization: owner checks (ACL-01, ACL-02). API2 authentication: OTP + throttles. API3 property-level: mass-assignment ignored
(AUTH-09), no phone numbers or grader criteria in responses. API4 resource consumption: size limits, throttles. API5 function-level authorization: deny-by-default role table
(ACL-03). API6 sensitive business flows: sign-up and confirmation throttles, fraud model, vetting. API8 misconfiguration: headers and error handling (DATA-01, DATA-03).
API9 inventory: `docs/API_REFERENCE.md`. API10 unsafe consumption: ML and LLM replies are validated, clamped and recomputed.

**OWASP Top 10 for LLM applications** (the optional LLM scorer; the keyword scorer is the default). LLM01 prompt injection: the transcript is fenced, injection phrases raise a human-review flag,
marks are clamped and totals recomputed by us (harness ML-03 exercises the keyword path; the model path needs an API key and is **not exercised** in this environment). LLM02 insecure output
handling: schema-validated, feedback stripped of links. LLM06 excessive agency: the model can only return marks; it calls nothing. **Recitation of rubric words** is caught by a guard that sends the answer to a human (ML-02).

**ASVS themes (selected).** V2 authentication, V3 sessions, V4 access control, V5 validation and encoding, V7 error handling and logging, V8 data protection, V9 communications, V13 API.
Each is covered by the rows above; there was no line-by-line ASVS audit.

## 6. Attack trees

**Goal: obtain a credential that claims a higher trust band than deserved** (AND = all needed, OR = any)

```
Show a credential with a high band
├─ OR  Forge the document
│    ├─ edit the numbers .................... blocked: signature covers every field (CRED-02)
│    ├─ sign with the attacker's own key .... blocked: key id must match a trusted key (CRED-03)
│    └─ steal the signing key ............... out of scope for the app; keystore outside the repo, rotation script, retired-key list
├─ OR  Replay someone else's valid credential
│    ├─ screenshot of the QR ................ visible, not prevented: page says "static link"; live code goes stale in 5 minutes (CRED-07)
│    └─ revoked credential .................. blocked: revocation is checked server-side and published in a signed list (CRED-06)
└─ OR  Earn the score dishonestly
     ├─ AND register several fake employers + give each a phone number ..... reaches Good only; Excellent needs a vetted employer (FRAUD-01)
     │       (OR corrupt a moderator so one is vetted) ....................... residual, audited
     ├─ one employer confirms many times ........................................ capped near 50 (distinct-employer rule)
     ├─ OR  play a good recording into the voice test
     │    ├─ the same recording from another account ........................... held, no score (REPLAY-01)
     │    ├─ the same recording with noise, another volume, a trimmed start .... held, no score (REPLAY-02)
     │    ├─ the same words on a new recording (re-voiced, time-stretched) ..... held by the word check (REPLAY-05)
     │    ├─ skip or forge the spoken code ..................................... refused, 428 (REPLAY-03)
     │    ├─ collect codes of later questions in advance ....................... refused, 409 and 429 (REPLAY-04)
     │    └─ a fresh, original recording with the code said live ............... accepted: residual (REPLAY-06); the employers' confirmations are the control
     ├─ recite the rubric words in the voice test ............................... flagged for a human (ML-02)
     └─ inject instructions into the spoken answer .............................. scored as an ordinary transcript, flagged (ML-03)
```

**Goal: do something and leave no trace**

```
Hide an action
├─ OR  edit or delete the database row ........ blocked for the app role; owner can, but the database hash chain exposes it
├─ OR  edit/delete lines in data/audit.log .... detected: hash chain (AUD-01)
├─ OR  rewrite the whole file consistently .... detected: keyed head record (AUD-01)
├─ OR  roll back to an earlier log + head ..... detected: database anchors (AUD-02)
└─ AND have the database owner's rights AND the HMAC key AND roll back both stores consistently ... not prevented; this is the insider-with-everything case
```

## 7. Risk register

Likelihood and impact are 1 (low) to 5 (high) as judged for a pilot with a few thousand workers. "After" is the score once the control is in place.

| # | Risk | Before | Control | After | Residual note |
|---|---|---|---|---|---|
| 1 | Fake employer ring inflates a worker to Excellent | L4 x I4 = 16 | vetted-employer rule (measured) | L2 x I4 = 8 | insider vetting, SIM farms to Good |
| 2 | Credentials cannot be checked by other systems | L5 x I3 = 15 | canonical signing, offline verifier, key list | L1 x I3 = 3 | none known |
| 3 | Audit file rolled back to hide an action | L2 x I5 = 10 | database anchors | L1 x I5 = 5 | needs database owner + key |
| 4 | Worker's credential shown by someone else | L4 x I3 = 12 | live code, visible static-link warning | L3 x I3 = 9 | a lent unlocked phone is still possible |
| 5 | Sign-up flooding and number enumeration | L4 x I2 = 8 | registration throttle | L2 x I2 = 4 | distributed botnets |
| 6 | Recited rubric words score well | L3 x I3 = 9 | stuffing guard to human review | L2 x I3 = 6 | questions with few rubric words cannot be guarded |
| 7 | Fake rating or smear by an employer the worker never met | L3 x I4 = 12 | worker sees every confirmation and can dispute it; a dispute holds it out of every score until a moderator rules; 30-day window, 3 open at a time | L2 x I3 = 6 | a worker who never looks is unprotected; up to 3 ratings can be hidden until ruled (FRAUD-02); one shared moderator account |
| 8 | Victim locked out of sign-in by quota exhaustion | L3 x I2 = 6 | a browser that has signed in is a trusted device with its own request budget and its own code; every code is bound to the browser that asked for it; unknown devices solve a proof-of-work puzzle and share a per-phone-and-address budget plus an hourly ceiling | L2 x I2 = 4 | a victim on a NEW device (lost phone) can still be delayed up to an hour by an attacker who pays the puzzle from about four or more addresses (AUTH-12); support-assisted recovery is not built |
| 9 | One shared moderator account | L3 x I4 = 12 | named accounts (own password, own phone for the code), every action audited under a person; vetting and dispute rulings need a second, different moderator (service, SQL and CHECK constraint); accounts made only by an operator, and the application's database role cannot write them | L2 x I3 = 6 | two moderators colluding, or the operator; still no hardware tokens or session recording |
| 10 | Secrets or keys in the repository | L2 x I5 = 10 | secret scan in CI, git-ignored `data/` and `.env.local` | L1 x I5 = 5 | none known |
| 12 | A stolen database, backup or audit file exposes transcripts, scores, names and the audit history | L3 x I4 = 12 | every such value is AES-256-GCM sealed by the application with keys that are not in the database or data directory; each value is tied to its column and row (a ciphertext copied elsewhere does not open); the audit file is encrypted line by line and still verifies; operator tool to convert, check and re-key | L1 x I4 = 4 | the key lives in the application's environment, so someone who steals the running server or its environment can read everything; the event type, actor and time of audit events, ids, statuses, timestamps, trade, language and the fraud model's anomaly score stay plain; audit-table rows written before encryption stay plain (append-only); a dropped key makes its data unreadable forever |
| 13 | A recording of a good answer is played into the voice test (the worker's own, an expert's, one from another account, or text-to-speech reading a stolen transcript) | L4 x I4 = 16 | spoken one-time code per question; acoustic-landmark fingerprint of every recording and word-trigram fingerprint of every transcript compared with every earlier answer; a match scores nothing, holds the answer and blocks the credential; a check that cannot run is held ([`REPLAY_DETECTION.md`](REPLAY_DETECTION.md)) | L2 x I4 = 8 | a fresh original recording with the live code said, a helper standing by, or live text-to-speech of an original script is accepted; no speaker verification; the audio check does not survive a speed change (the word check covers answers of 14 or more trigrams); calibrated on synthesised speech, not real phone recordings; no review queue for held answers (the worker retakes) |
| 11 | Voice recognition fails for some accents | L4 x I3 = 12 | measured baseline, better-model plan | L4 x I3 = 12 | **open**: [`ACCENT_TRAINING.md`](ACCENT_TRAINING.md); real-speaker word error is about a third |

## 8. What was verified and what was not

Run in this environment: unit and database tests, the end-to-end and API suites, the attack harness, static analysis (SpotBugs with find-sec-bugs, Bandit, Semgrep), secret scan,
dependency scans, SBOM generation, the offline verifier, the key rotation script on a scratch keystore, and the console screens rendered with demo data.

**Not run here:** the Docker/Caddy production path, OWASP ZAP and Trivy (they run in CI), the LLM scorer (no API key), real SMS delivery, a real phone scanning the QR or recording audio
over HTTPS, and real tradespeople's speech.
