# Security Runbook

Short, practical steps for the person on call. Commands assume docker compose (`docker compose exec ...`); with the local dev
stack use the same SQL with `psql -d ks_blueprint` and read `data/logs/dev.log`.

## 0. Where to look

| What | Where |
|---|---|
| Audit trail (database, authoritative) | table `audit_log`: `chain_seq, event_type, actor, details, timestamp, prev_hash, entry_hash`. **`details` is encrypted** (rows written after migration 010): read it through the application's Security tab, or `scripts/seal-at-rest.sh audit-read`; `event_type`, `actor` and `timestamp` stay plain so the queries below still work |
| Audit trail (file copy, keyed hash chain) | `data/audit.log` and `audit.log.head` (in compose: volume `appdata`, `/usr/local/tomcat/data`). **Every line is encrypted**: read it with `./scripts/seal-at-rest.sh audit-read 100` or `python3 scripts/audit_decrypt.py` (an independent reader of the format) |
| Integrity check | moderator console "Verify audit log", `GET /api/moderator/audit-check`, or SQL: `SELECT * FROM verify_audit_chain();` |
| Application log | `docker compose logs app` (OTP codes with the console sender, errors with a `ref=`), `docker compose logs proxy` for access |

Audit entries hold **ids only** (`worker:7`, `employer:3`, `attestation=33`), masked phone numbers and hashed IP prefixes; never names,
transcripts or raw IPs. The log is append-only: the application role cannot UPDATE or DELETE it.

## 1. Event types

`LOGIN_OTP_REQUESTED`, `LOGIN_OTP_REQUESTED_UNKNOWN`, `LOGIN_FAILED` (reason `otp` or `password`), `LOGIN_SUCCESS`, `MODERATOR_OTP_ISSUED`,
`WORKER_REGISTERED`, `EMPLOYER_REGISTERED`, `ASSESSMENT_STARTED`, `ASSESSMENT_COMPLETED`, `REPLAY_SUSPECTED`, `REPLAY_CHECK_FAILED`, `CHALLENGE_NOT_HEARD`, `ATTESTATION_OTP_REQUESTED`, `ATTESTATION_OTP_FAILED`,
`ATTESTATION_APPROVED`, `ATTESTATION_FLAGGED`, `ATTESTATION_ACCEPTED`, `ATTESTATION_DISPUTED`, `DISPUTE_DECIDED`, `MODERATOR_DECISION`,
`MODERATOR_ACTION_PROPOSED`, `MODERATOR_ACTION_APPROVED`, `MODERATOR_ACTION_REJECTED`, `MODERATOR_ACTION_WITHDRAWN`, `MODERATOR_SELF_APPROVAL_REFUSED`,
`MODERATOR_ACCOUNT_CREATED`, `MODERATOR_ACCOUNT_DISABLED`, `MODERATOR_ACCOUNT_ENABLED`, `MODERATOR_PASSWORD_CHANGED` (these four are written by the operator's tool, actor `operator:<os user>`), `TRUST_RECOMPUTED`, `TRUST_REFRESH_FAILED`, `CREDENTIAL_ISSUED`,
`CREDENTIAL_REVOKED`, `WORKER_ERASED`, `EMPLOYER_ERASED`, `ERASURE_FILE_REMAINS`.

## 2. Suspicious patterns and what to do

Starter queries (run as the database owner; times are UTC).

**a) Credential stuffing / OTP guessing: many `LOGIN_FAILED`**
```sql
SELECT actor, count(*) AS failures, min("timestamp"), max("timestamp")
FROM audit_log WHERE event_type = 'LOGIN_FAILED' AND "timestamp" > now() - interval '1 hour'
GROUP BY actor ORDER BY failures DESC LIMIT 20;
```
Per-account and per-IP limits already return `429`, and five wrong guesses lock a code, so a high count means someone is trying, not that they got in.
Check whether the same `actor` later has `LOGIN_SUCCESS`. If an attacker is hammering a moderator login (`actor = moderator:...`), rotate the
moderator password (section 4) and check where the traffic comes from in the proxy log; block that source at the proxy/firewall.

**b) A burst of attestations from one employer or address: many `ATTESTATION_FLAGGED`**
```sql
SELECT actor, count(*) FROM audit_log
WHERE event_type IN ('ATTESTATION_FLAGGED','ATTESTATION_APPROVED') AND "timestamp" > now() - interval '1 day'
GROUP BY actor ORDER BY 2 DESC LIMIT 20;
```
The model has already routed these to the moderator queue; nothing was auto-approved. Review them in the console, decide `FRAUD` for fakes.
If an employer is clearly farming endorsements, erase or block them (section 5), and re-run trust recomputation (it happens on every decision).
Two attestations that vouch for each other (a ring) show up as high `reciprocity_ratio` in the flag's features.

**c) Moderator decisions that look wrong or come in bursts**
```sql
SELECT "timestamp", actor, chain_seq FROM audit_log WHERE event_type = 'MODERATOR_DECISION' ORDER BY chain_seq DESC LIMIT 50;
```
(`details` is encrypted, so SQL shows who and when; read the flag and decision with `./scripts/seal-at-rest.sh audit-read 200`.) Each decision is recorded as `moderator:<username>`, so the log says which person decided. Any approve of an attestation whose flag score is above 0.9 deserves a second look,
and so does one moderator who approves most of another's proposals (`MODERATOR_ACTION_APPROVED` details show `proposed_by`).

**d) `ATTESTATION_OTP_FAILED` repeated on one attestation:** an employer who cannot read their own phone, or someone trying to
confirm an attestation they were not sent. Five failures lock the code; the attestation stays `OTP_PENDING` and can be requested again.

**e) `TRUST_REFRESH_FAILED` or `ERASURE_FILE_REMAINS`:** not attacks, but need action. The first means the ML service was unreachable while an
attestation was approved (the approval stands; recompute runs on the next decision). The second means a credential file could not be deleted from
disk after an erasure: delete `data/credentials/<ref>.json` by hand.

**f) Many `LOGIN_OTP_REQUESTED_UNKNOWN`:** someone probing phone numbers. Responses are identical for known and unknown numbers, so nothing leaks;

**g) `REPLAY_SUSPECTED`: a recording or a set of words was used before** (details: `status=REPLAY_AUDIO|REPLAY_TEXT|REPLAY_BOTH`, `match=<assessment id>`, `sameWorker=true|false`, the audio matches and the word share; never audio, words or names)
```sql
SELECT actor, count(*) FROM audit_log WHERE event_type = 'REPLAY_SUSPECTED' AND "timestamp" > now() - interval '1 day' GROUP BY actor ORDER BY 2 DESC LIMIT 20;
SELECT a.assessment_id, a.worker_id, a.question_no, a.replay_status, a.challenge_status, a.fingerprint_count, a.shingle_count, a.created_at
FROM assessments a WHERE a.replay_status LIKE 'REPLAY_%' ORDER BY a.assessment_id DESC LIMIT 50;
```
The answer was stored with score 0 and held for review; the credential cannot be issued while any answer of the attempt is held. Read it like this:
`REPLAY_AUDIO` is the same recording (the strong signal: it survives noise, volume, trim and a speaker-to-microphone hop); `REPLAY_TEXT` is the same words on different audio
(a time-stretched or re-voiced copy, or two people sharing a script); `sameWorker=true` is one worker reusing their own answer across questions or attempts, which is usually
someone who recorded once and replayed it; `sameWorker=false` is a recording or script passed between accounts, so look at both workers' other answers. There is no review queue yet:
the worker is told the answer is held and records it again. A burst from one account, or the same `match=` id turning up again and again, is the pattern to act on (erase or block the accounts, section 5).
A worker who says it was their own honest answer: ask them to answer again in a quiet place; a real second answer does not match the first.

**h) `REPLAY_CHECK_FAILED`:** the recording could not be fingerprinted (the ML service was down, or returned something the application would not accept). The answer was stored held for review,
not waved through. Bring the ML service back; the affected workers can answer again. Repeated failures on every answer mean the ML service or its `/internal/ml/audio-fingerprint` route is broken.

**i) `CHALLENGE_NOT_HEARD`:** after `REPLAY_CHALLENGE_TRIES` tries the speech recogniser still did not hear the spoken code. The answer is stored held for review with its score (this is not evidence of replay on its own:
noise, an accent the recogniser misses or a worker who did not understand the instruction all look the same). Many in a day from one account is the pattern of someone playing a recording that cannot contain the code.
If the rate across ALL workers is high, the recogniser is missing digits: check the ML service and consider `REPLAY_CHALLENGE_TRIES` before switching the challenge off (`REPLAY_CHALLENGE=false` is allowed, `REPLAY_DETECTION=false` is refused in production).

**What to tell a worker whose answer is held:** "Your answer is being checked. Record it again in your own voice, in a quiet place, and say the three numbers shown on the screen at the end." Do not say which check found it.
the per-IP limit caps it. Watch for the same IP hash prefix.

## 3. The audit log itself looks tampered with

1. Run `SELECT * FROM verify_audit_chain();` (`valid`, `first_bad_seq`, `checked`) and the file check (`/api/moderator/audit-check`).
2. **Database and file disagree, or either is invalid: treat as an incident.** The database triggers block UPDATE/DELETE/TRUNCATE for the
   application role, so a break means someone used the **owner** account or direct file access. The file chain is keyed (HMAC with `AUDIT_HMAC_KEY`),
   so rewriting the whole file without the key is detected; truncating the tail is detected by the `.head` file.
3. Preserve evidence first: copy `data/audit.log*` and a `pg_dump` of `audit_log`; do not "repair" them.
4. Find the first bad sequence number; everything before it is still trustworthy. Check Postgres logs (`log_statement`) for who connected as the owner.
5. Rotate the database owner and application passwords and `AUDIT_HMAC_KEY` (new entries then use the new key; keep the old key with the archived log).

## 4. Rotating secrets

| Secret | Impact of rotating | Steps |
|---|---|---|
| A moderator's password | none | `./scripts/moderator-admin.sh set-password alice` (password on stdin); in Docker: `docker compose run -T --rm --no-deps -e ADMIN_DB_USER=ks_owner -e ADMIN_DB_PASSWORD=... app moderator-admin set-password alice`. Their open sessions keep working until they end (8 hours at most); disable the account first if the old password may be in someone else's hands |
| `ML_INTERNAL_TOKEN` | brief ML outage | change in `.env`, `docker compose up -d ml app` together |
| `DB_PASSWORD` | restart | `ALTER ROLE kaarigar_app PASSWORD '...'`, update `.env`, restart app |
| `OTP_PEPPER` | outstanding OTPs stop verifying (5 minutes at most) | change, restart |
| `DATA_ENC_KEYS` | none if done as below | this one CAN be rotated: put the new key first, run `./scripts/seal-at-rest.sh seal`, then `check`, then drop the old key (section "Encryption at rest"); never drop a key that sealed an audit line |
| `PHONE_HMAC_KEY` / `PHONE_ENC_KEY` | **breaks every login and decryption** | do not rotate casually; it needs a data migration (re-derive every `phone` lookup key and re-encrypt `phone_enc`) |
| Credential signing key (keystore) | old credentials no longer verify | Rotating is a deliberate re-issue: keep the old key to verify old credentials, generate a new `keyId`, ask workers to re-issue. Never overwrite the keystore: `scripts/generate-signing-key.sh` refuses to. **Back the keystore up**: losing it invalidates everything issued. |

## 5. Handling a user or an employer incident

* **Revoke a credential** (stolen phone, wrong score): the owner or a moderator calls `POST /api/credential/{ref}/revoke`; verification then says `REVOKED` immediately.
* **Remove a bad actor's endorsements:** decide the pending flags `FRAUD`; for already-approved ones erase the employer (`/api/employer/erase` as them) or delete the
  attestation row as the owner and run a trust recompute by making any decision/attestation. Record what you did.
* **A person asks for their data to be deleted (DPDP):** they can do it themselves from their dashboard ("Delete my data"). If they cannot sign in, an operator
  with the owner database role can run the same steps as `ErasureService` (see `docs/PRIVACY_DPDP.md` section 4); log it.

## 6. Dependency alerts

* CI runs `pip-audit` and `scripts/osv_scan_maven.py` on every push; a new advisory fails the `dependency-scan` job. Update the pinned version, re-run the
  tests, and re-scan. Run them by hand any time: `.venv/bin/python -m pip_audit -r requirements.txt` and `python3 scripts/osv_scan_maven.py`.
* Base-image findings (Trivy) are report-only: review the job output monthly and rebuild images (`docker compose build --pull`).

## 7. Backups

Back up: the Postgres volume (`pg_dump -Fc`), the **keystore** and `audit.log*` from the `appdata` volume, and `.env` (store separately from the data). Test
a restore: a restored database must pass `verify_audit_chain()`.

## Operating the security controls added for the information-security review

### Vetting employers (the trust anchor of the Excellent band)
Open the moderator console, tab **Employers**. The list shows each employer's name, how many approved confirmations they have given and whether anyone has vetted them (no phone numbers).
Vet an employer only after checking who they are **outside the application**: business registration, a call back to a number you found yourself, a contractor association's list. The evidence is not stored in the application;
the vetting records who did it and when (`employers.verified_by`, `verified_at`) and writes `EMPLOYER_VETTED` to the audit trail. Vetting refreshes trust scores and re-issues the credentials of the workers that employer confirmed.
Start with the employers who already have the most confirmations, because their vouching already counts. Until at least one employer who confirmed a worker is vetted, that worker's score is held at 79.99.
Vetting is **proposed** by one moderator and takes effect only when a **different** moderator approves it (section "Moderator accounts and two-person approval" below); the employer record and the audit trail name both.

### Handling disputes (the worker's say)
Open the moderator console, tab **Disputes**. Each row is a confirmation a worker says is untrue or unfair: the employer's business name, the job, the ratings and the worker's reason (no phone numbers). While it is open it does not count anywhere.
Decide with evidence from **outside** the application where you can (call the employer back on a number you found yourself; ask the worker for the job details), then press **Keep (upheld)** (it counts again) or **Remove** (it never counts). A ruling is final and writes `DISPUTE_DECIDED` to the audit trail; trust and the worker's credential are refreshed.
Do not leave disputes open: while one is open it hides a rating, and a worker may have 3 open at once. A worker who disputes many ratings and loses them (several `REMOVED`/`UPHELD` patterns, or repeated `ATTESTATION_DISPUTED` in the Security tab) deserves a closer look at the employers involved too.
Not handled here: a worker who never opens their dashboard is not protected, and the reason a worker typed is free text that is deliberately **not** written to the audit file. `DISPUTE_WINDOW_DAYS` (30) and `DISPUTE_MAX_OPEN` (3) are the settings.
Existing databases need migration 007 and a SELECT grant on `counted_attestations` for the application role (the migration does it for `kaarigar_app`; another role name needs it by hand).

### Encryption at rest
**What is encrypted** (AES-256-GCM, by the application; migration 010): transcripts, feedback and review reasons of voice answers; skill and trust scores, bands and explanation texts; employers' ratings, job role, dates, duration and would-hire-again; workers' dispute reasons; worker and employer names; the fraud model's features; the signed credential payloads; the details of every audit event; the audit FILE (every line); the credential export files. Each value is tied to its own column and row, so a ciphertext moved elsewhere does not open. **What is deliberately left plain** because the application or an operator filters or counts on it: ids, statuses, timestamps, trade, language, the anomaly score, trust-graph weights, hashes, and the event type, actor and time of audit events. Phone numbers keep their own protection (`PHONE_ENC_KEY`).
**The key:** `DATA_ENC_KEYS=id:base64,id:base64` in the environment (`.env.local`, or `.env` in Docker; 32 random bytes each, `openssl rand -base64 32`). The FIRST key encrypts new data; every key listed can still decrypt. It must never be in the database or beside the data, and must not reuse `PHONE_ENC_KEY`. **Back it up apart from the data and the database backups: without it the encrypted data is gone for good**, and a backup that holds the key beside the data is not protected. The application refuses to start without it.
**Upgrading an existing installation:** apply migration 010 (it only changes column types; values stay readable as plain text), start the application with `DATA_ENC_KEYS` set, run `./scripts/seal-at-rest.sh seal` (safe while it runs), then STOP the application and run `./scripts/seal-at-rest.sh seal --app-stopped` once to encrypt the audit file in place (its hash chain, head record and database anchors are computed over the plaintext, so they all still verify; no hash changes). Finish with `./scripts/seal-at-rest.sh check`: it counts, per column, what is plain, what is sealed, what an older key sealed and whether every value opens, and exits 1 if anything is plain. `REQUIRE_SEALED_AT_REST` (on by default when `APP_ENV=prod`) makes the application refuse a plain value instead of accepting it. Take a database backup first (`pg_dump`).
**Rotating the key:** generate a new key, put it FIRST (`DATA_ENC_KEYS=k2:NEW,k1:OLD`), restart, run `./scripts/seal-at-rest.sh seal` then `check` (it must say nothing is sealed with an older key), and only then remove `k1`. Two things cannot be re-encrypted, by design: the audit table and the audit file are append-only and hash-chained, so a line sealed with `k1` keeps `k1`: keep `k1` in the ring for as long as those lines exist. Losing a key makes everything sealed with it unreadable (`check` reports it as "unreadable").
**Reading the audit trail:** `./scripts/seal-at-rest.sh audit-read 100` prints the newest 100 file lines decrypted; `AuditVerifyCli` (offline check of a copy of the file) needs `DATA_ENC_KEYS` in its environment. A thief with only the file can neither read it nor check it.
**What it does not protect:** a person or program that controls the running application or its environment holds the key and can read everything (that is the nature of application-level encryption; keep the key out of the repository, the logs and the backups); the audit table rows written before encryption was switched on stay plain; the transcript travels in clear between the application and the ML service on the same host; the development console prints one-time codes into `data/logs` (use a real SMS sender in production).

### Sign-in codes, trusted devices and "I cannot get a code"
A phone number's code budget used to be shared by everyone who typed the number, so a stranger could keep a person from signing in. Now: a browser that has signed in before is a **trusted device** (an HttpOnly cookie, `ks_device`; the database holds only a keyed hash; at most `MAX_TRUSTED_DEVICES` = 5 per account, unused ones expire after `TRUSTED_DEVICE_DAYS` = 180) with its own request budget and its own code; every code is bound to the browser that asked for it (`ks_bind`); any other browser first solves a small proof-of-work puzzle (`OTP_POW_BITS` = 16, about 65,000 hashes, rising by 2 bits up to 20 for an address that keeps asking; `0` switches it off) and then shares a budget of 3 per 10 minutes per phone and address and 12 per hour per phone.
**A user says they get "Too many attempts":** ask whether this is a phone or browser they have signed in from before. If yes, the number's shared budget is not the cause (a trusted device has its own): check that their browser keeps cookies for the site, then their own device budget (5 per 10 minutes). If it is a new phone or a cleared browser and several people may be asking for their number, the hourly ceiling of 12 may have been used up (by anyone): it clears an hour after the first of those requests. There is no support-assisted recovery yet.
**What to look at:** `RATE_LIMITED` on the Security tab and `LOGIN_OTP_REQUESTED` with `device=unknown` in the audit trail: many for one account from different addresses is someone targeting that number. `device=trusted` is the person's own browser. Do not raise the per-phone hourly ceiling to be kind: it also bounds SMS cost and code guessing (12 codes x 5 tries per hour).
**Shared computers:** a computer that someone signs in on becomes trusted for that account. That affects rate limits only; it never signs anyone in without a code. Erasing an account removes its devices.

### Moderator accounts and two-person approval
**Who can make a moderator:** only an operator on the server with the database owner's credentials, with `./scripts/moderator-admin.sh` (in Docker: `docker compose run -T --rm --no-deps -e ADMIN_DB_USER=ks_owner -e ADMIN_DB_PASSWORD=... app moderator-admin ...`). The web application's own database role can only read the `moderators` table, so a compromised web application cannot mint a moderator, and no moderator can create another.
```
./scripts/moderator-admin.sh add alice "Alice Rao" 9876543210     # password on standard input, at least 12 characters; her sign-in codes go to HER phone
./scripts/moderator-admin.sh list
./scripts/moderator-admin.sh disable alice                        # her open sessions stop working on their next request
./scripts/moderator-admin.sh enable alice
./scripts/moderator-admin.sh set-password alice
```
Make **at least two** (the tool warns when fewer than two are active, because then nothing can be approved). Each change is written to the database audit trail as `MODERATOR_ACCOUNT_*` with the operator's operating-system user name. Give each person their own account and phone; never share one.
**Leaving the team or a lost phone:** disable the account at once, then check the audit trail for `moderator:<username>` over the period in doubt.

**Two-person approval.** `TWO_PERSON_ACTIONS` (default `VET_EMPLOYER,DISPUTE_DECISION`) lists the actions that need a second moderator; `TWO_PERSON_LIFETIME_HOURS` (72) is how long a proposal waits before it expires. In the console, **Employers** and **Disputes** only *propose*; the **Approvals** tab lists what is waiting, and a moderator who did **not** propose an item can approve it (it then takes effect at once) or reject it. The proposer can withdraw their own. Approve only what you have checked yourself, not because a colleague asked.
The rule "a different person" is enforced in three places: the service (a refused attempt is audited as `MODERATOR_SELF_APPROVAL_REFUSED` and raises a Security-tab alert), the approving SQL statement, and a CHECK constraint on `moderator_actions`. Fraud-queue decisions stay single-moderator (they are high volume and reversible only by a new review) but are attributed to the person.
**If a proposal looks wrong,** reject it. **If no second moderator is available,** it expires after the lifetime and the target stays as it was; do not switch the rule off. `TWO_PERSON_ACTIONS=none` exists for single-developer demos and the application refuses to start with it when `APP_ENV=prod`.

### Rotating the credential-signing key
`KEYSTORE_PASSWORD=... ./scripts/rotate-signing-key.sh`, then restart. Do it on a schedule (for example yearly) and immediately after any suspicion that the keystore was read. Keep the archived old keystore offline or destroy it.
Old credentials keep verifying because the old public key stays in `RETIRED_KEYS_DIR` (default `data/keys/retired`); back that folder up with the keystore. If a key was **compromised**, also revoke the credentials it signed
(a bulk revoke is not built; revoke through the database) and tell relying parties to stop trusting that key id. See [`CREDENTIAL_TRUST.md`](CREDENTIAL_TRUST.md).

### Reading the Security tab
Status OK, WARN or CRITICAL, with one line per alert. Counts of CSRF rejections, role violations and rate-limit hits are "distinct clients per minute", not requests. Thresholds are in `SecurityMonitor.RULES`.
First response: WARN for failed sign-ins or wrong attestation codes: check whether it is one client address (rate limits already contain it) or many; HIGH for rate-limit or CSRF events: look at the recent-events list for the paths involved;
**CRITICAL (audit integrity) means treat the trail as compromised**: stop writing to it, copy `data/audit.log`, `data/audit.log.head` and a database dump to a safe place, then run
`AUDIT_HMAC_KEY=... java -cp ... com.kaarigarsetu.admin.AuditVerifyCli data/audit.log --db` to see the first bad line, and compare with the database table `audit_log` (append-only, hash-chained; `SELECT * FROM verify_audit_chain()`).

### Audit anchors
Every 25 events and every 5 minutes the application records the newest file line's hash in the database (`AUDIT_FILE_ANCHOR`). This is what catches a rolled-back audit file. If the Security tab says anchoring has stalled, check the application log for
"audit anchoring failed" and that the database is reachable. Do not delete `AUDIT_FILE_ANCHOR` rows; the table is append-only anyway.

### Replay detection (operating it)
**What it is:** [`REPLAY_DETECTION.md`](REPLAY_DETECTION.md). **Upgrading an existing installation:** apply migration 011 (two new tables and four columns on `assessments`; the application role needs `SELECT, INSERT` on `answer_fingerprints` and `answer_shingles`, which a fresh `setup_roles.sql` gives and the migration grants where the role exists), then deploy the ML service BEFORE the application (the application calls `/internal/ml/audio-fingerprint`; without it every answer is held, by design). Answers given before the upgrade have no fingerprints, so a recording is only recognised against answers given after it.
**Switches:** `REPLAY_CHALLENGE` (the spoken code) may be turned off; `REPLAY_DETECTION` may not in production. **Thresholds** (`REPLAY_MIN_MATCHES` 25, `REPLAY_MIN_LANDMARKS` 100, `REPLAY_TEXT_MIN_SHINGLES` 14, `REPLAY_TEXT_MIN_COMMON` 8, `REPLAY_TEXT_MIN_SHARE` 0.5) were set on synthesised speech: if honest workers are held, look at `assessments.replay_status` and the `REPLAY_SUSPECTED` details first and re-run `kaarigarsetu-ml-service/benchmarks/replay_benchmark.py` before changing any; the application refuses values outside a safe range at start-up (for example a landmark threshold below 8, which chance could reach).
**Keys:** the fingerprints are keyed hashes under `PHONE_HMAC_KEY`. Changing that key makes every earlier fingerprint unmatchable (replays of older answers go unnoticed; nothing else breaks), another reason not to rotate it casually.
**Size:** a 10-second answer gives about 1,500 to 2,200 landmark rows (never more than 3,000) and 10 to 30 word rows; ten answers a worker is about 20,000 rows, on the order of a megabyte with the indexes. They are deleted with the answer.

### Running the attack-and-defence suite and the scanners
`BASE_URL=http://localhost:8090 JAVA_LOG=data/logs/dev.log python3 scripts/security_attack_demo.py` against a **development** stack (it creates test accounts and sends hostile input; never run it against production).
`cd kaarigarsetu-java && mvn -Psast -DskipTests compile spotbugs:check cyclonedx:makeAggregateBom` for static analysis and the SBOM. Both also run in CI.

### Settings that must be right in production
`REPLAY_DETECTION` left on (the application refuses to start with it off); `TRUST_FORWARDED_FOR=false` unless a proxy you control overwrites `X-Forwarded-For`; `APP_ENV` not `dev` (this also turns off demo sign-in and turns on secure cookies and HSTS); the hardened `docker/tomcat/server.xml`.
