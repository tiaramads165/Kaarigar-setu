# Demo Script (about 12 minutes)

Two journeys: (1) a worker earns and shares a signed credential, (2) a fraud pattern is caught and a moderator decides.

> **Say up front:** the assessment questions are a TEST ONLY placeholder, scoring is a simple keyword match (the LLM scorer is not connected) and OTP
> codes are printed to the server log. Speech-to-text is real Whisper; the stack must be started with `STT_PROVIDER=whisper` (the default), not `stub`.
> The security machinery is real; the real rubric, the SMS provider and the LLM are not yet.

## Setup (before the audience arrives)

**Option A, docker compose** (only if Docker is available; never run on the build machine, see `docs/TEST_REPORT.md` section 5):
```bash
./scripts/compose-setup.sh            # once; prints the moderator password
docker compose up -d
docker compose logs -f app > data/logs/compose-app.log &     # OTP codes land here
```
Open `https://localhost:8443` (accept the local-CA warning).

**Option B, local development stack** (this is what has been run end to end):
```bash
./scripts/dev-setup.sh                # once
mkdir -p data/logs && ./scripts/dev.sh 2>&1 | tee data/logs/dev.log     # leave running; OTP codes appear here and in the file
```
Open `http://localhost:8090`. In a second terminal: `grep "CONSOLE OTP" data/logs/dev.log | tail -3` shows the latest codes.

Use two browser windows (or one normal and one private) so the worker and the employer can be signed in at once.
Use phone numbers like `9876500001` (worker) and `9876500002` (employer); the OTP is the last `[CONSOLE OTP]` line for that number.

## Shortcut: demo accounts with scores already in place

`./scripts/demo-seed.sh` then open <http://localhost:8090/demo> (dev only). One click signs in as, for example, **Ramesh Kumar** (electrician: skill 81 Pass, trust 81 Excellent, four employers), **Anil Verma** (skill 76 but trust 40 Building: only one employer has confirmed him, so a glowing review alone is not enough), **Imran Ansari** (assessed, but no employer history yet) or an employer such as **Khan Contractors** (rates everyone five stars, so the system discounts that habit). The worker dashboard shows the skill band, the skill-by-area bars and the trust report with tips.

## Journey 1: a worker earns a credential (6 min)

| # | Action | What to point out |
|---|---|---|
| 1 | **Register the worker**: Name, `9876500001`, trade Electrician, language Hindi. Try to submit **without** ticking the consent box. | Nothing is stored without consent (`403 consent_required`); the consent text and its version are recorded when it is ticked. Tick it and register. |
| 2 | **Sign in** as Worker with the number; read the 6-digit code from the log. | The code is single use, expires in 5 minutes, locks after 5 wrong tries; only a salted hash is stored. Try a wrong code once. |
| 3 | **Take the voice assessment**: Start, record and answer the 10 questions (simple first, then harder scenario and troubleshooting questions; about 10 to 15 minutes, so for a short demo answer the first three and mention the rest) by speaking clearly (the transcript shown is what Whisper heard (the first answer after a restart waits a few seconds while the 1.6 GB model loads); an unrelated answer scores 0, an answer naming the tools scores full marks). | Audio is checked (real WAV header, 0.5 to 60 s), transcribed by the ML service, scored per criterion; the raw audio is zeroed after transcription and never stored. Scores appear per question and a skill score at the end. The page says the questions are test-only. |
| 4 | Back on the worker dashboard, note **Skill score** filled, **Trust score** still empty. | A credential needs trust that only employers can give. |
| 5 | **Employer window**: register employer `9876500002` (consent), sign in with its OTP. Fill "Attest a worker's completed work": worker `9876500001`, role "House wiring", date today, would hire again = Yes. Press **Send me a code**; read the code (it goes to the *employer's own* phone, not the worker's); enter it, **Confirm attestation**. | Step-up proof that the person at the keyboard is the employer. The result is `APPROVED` with a low anomaly score: it passed the fraud check. Self-attestation and a second attestation for the same pair are refused. |
| 6 | Worker window: reload the dashboard. **Trust score** and "1 verified employer" appear; press **Issue / refresh my credential**. | The trust score is PageRank over the employer-worker graph; one employer cannot give a top score (it needs three independent ones). The credential appears with a QR code, status `VALID`. |
| 7 | **Verify it as a stranger**: open the credential's verification page from a private window (no login), or scan the QR with a phone on the same network (the dashboard shows the address the QR opens; it must not say `localhost`, see the README section on QR codes). | Public verification: it shows trade, scores, number of verified employers, validity dates and a pseudonymous id, with **no name or phone number**. |
| 8 | **Tamper check**: employer window, "Verify a worker's credential": click "Download signed JSON" in the worker window, paste the `credential` payload and `signature`, press *Verify pasted credential* (`VALID`). Change any digit of the score in the payload and verify again. | `INVALID_SIGNATURE`: RSA-3072 signature over the exact signed text. |
| 9 | **Supersede**: in the worker window press **Issue / refresh my credential** again, then reload the *old* verification page. | The old credential is now `REVOKED` (a new issuance revokes earlier ones, so a stale higher score cannot be replayed). There is no revoke button in the UI; explicit revocation by the owner or a moderator is the API call `POST /api/credential/{ref}/revoke`, shown in `scripts/e2e.py` section 7. |

Optional 30 seconds: the worker's **"Delete my data"** card: type `ERASE MY DATA`. The account, scores, credential and the exported
file are gone and the verification page now says `NOT_FOUND` (`docs/PRIVACY_DPDP.md` section 4). Do this last, it ends the worker's session.

## Journey 2: fraud pattern to moderator decision (5 min)

1. In a terminal run the burst helper. It registers one employer and seven workers and has the employer attest all seven within seconds from one address:
   ```bash
   JAVA_LOG=data/logs/dev.log python3 scripts/demo_fraud_burst.py        # compose: JAVA_LOG=data/logs/compose-app.log
   ```
   Example output from one run: the first few look normal and are `APPROVED`; as the burst builds the anomaly score climbs (0.43, 0.49, 0.60, ... 0.76) and the rest turn
   `PENDING_REVIEW`. **Point out:** the model does not reject anything; it holds suspicious items for a human, and the ones it let through early are why a
   threshold is a trade-off (`docs/ML_EVALUATION.md`).
2. **Start the JavaFX moderator console** (built jar; JavaFX needs a desktop):
   ```bash
   java -jar kaarigarsetu-moderator-console/target/kaarigarsetu-moderator-console.jar            # defaults to http://localhost:8090
   java -jar kaarigarsetu-moderator-console/target/kaarigarsetu-moderator-console.jar https://localhost:8443   # compose: see the note below
   ```
   Sign in with a named moderator: `moderator` with `MODERATOR_PASSWORD_PLAIN` from `.env.local` (the development accounts `moderator` and `moderator2` are made by `dev-setup.sh`; in Docker you create them yourself with `moderator-admin`), then the 6-digit code
   from the log (`scripts/otp.sh`): a stolen password alone is not enough, and each moderator's code goes to their own phone. The window title shows who is signed in.
3. The **queue** lists the flagged attestations, colour-coded by anomaly score; select one: the evidence panel shows **why** (same address count, minutes since the previous one,
   reciprocity) in words, with **no names or phone numbers**. Press **Approve (genuine)** on one and **Reject (fraud)** on another (each asks for confirmation).
4. **Show the consequences**: an approved attestation now counts toward the worker's trust; a rejected one never does; deciding the same flag again is refused (`409`).
5. **Audit trail**: in the console press **Verify audit log** (or `GET /api/moderator/audit-check`). The log is a hash chain in the database (append-only triggers) and in a keyed file;
   show `MODERATOR_DECISION` lines with the moderator id and flag, no personal data. If time permits, in `psql` try `UPDATE audit_log ...` as the application role: it is refused.

> Compose note: the proxy's certificate comes from Caddy's own CA. For the console, export it and trust it:
> `docker compose cp proxy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt`, then
> `keytool -importcert -noprompt -alias caddy -file caddy-root.crt -keystore data/console-trust.jks -storepass changeit` and start the console with
> `java -Djavax.net.ssl.trustStore=data/console-trust.jks -Djavax.net.ssl.trustStorePassword=changeit -jar ...`.

## Closing slide: what is real and what is not yet

Real and tested: consent-gated registration, OTP login with lockout, role and CSRF enforcement, encrypted phone numbers, per-criterion scoring pipeline with prompt-injection
defences, employer attestation with step-up OTP, fraud model, trust graph, RSA-signed credentials with revoke/expiry/supersede, moderator console, hash-chained audit log, deletion path.
Not yet: the real rubric, a real SMS provider, the LLM scorer, Whisper measured on real workers' speech, rubric-vs-human agreement, Docker images run for real.
