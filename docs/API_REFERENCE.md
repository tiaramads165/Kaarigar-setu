# API Reference

Base URL: the proxy (`https://localhost:8443` with docker compose) or Tomcat directly (`http://localhost:8090` with `scripts/dev.sh`).
All bodies are JSON (`Content-Type: application/json`) except the audio upload. Errors are always
`{"error": "<code>"}` and **never** contain exception text, SQL or stack traces (an unexpected failure also returns a random `ref`
that is written to the server log).

## Conventions

* **Session**: a `JSESSIONID` cookie (`HttpOnly`, `SameSite=Strict`, `Secure` when `COOKIE_SECURE=true`). A new session id is issued at every login. Sessions end after 30 minutes idle or 8 hours absolute.
* **CSRF**: every `POST` (including login and OTP requests) must send `X-CSRF-Token: <token>`. Get the token from `GET /api/auth/session`; it is replaced by the one returned from a successful login. A cross-origin `Origin` header is also refused. Missing/wrong token: `403 {"error":"csrf"}`.
* **Roles**: `WORKER`, `EMPLOYER`, `MODERATOR`. A route the caller's role may not use returns `403 forbidden`; no session returns `401 unauthenticated`. Any `/api/` route with no rule still requires a session (deny by default).
* **Rate limits** (per node, sliding window): OTP request 3 per phone and 10 per IP per 10 min; OTP verify 30 per IP per 10 min; moderator login 5 per user and 10 per IP per 10 min; attestation request 10 per employer and 30 per IP per hour. Exceeded: `429 rate_limited` with `Retry-After`.

| HTTP | `error` code | Meaning |
|---|---|---|
| 400 | `invalid_request` | validation failed (the body never says which field) |
| 401 | `unauthenticated` / `invalid_otp` / `invalid_credentials` | not logged in / wrong, expired, locked or unavailable code / wrong moderator login |
| 403 | `forbidden` / `csrf` / `consent_required` / `low_trust` | role not allowed / CSRF / consent not given / trust too low |
| 404 | `not_found` | also used to hide other users' resources |
| 409 | `conflict` / `duplicate` | already done, wrong order, already decided / attestation already exists for this pair |
| 422 | `unintelligible` / `code_not_heard` | no speech recognised in the audio / the spoken code was not heard in the answer (say it again; after `REPLAY_CHALLENGE_TRIES` tries the answer is stored and held for review) |
| 428 | `challenge_required` | an answer was sent without a valid spoken-code token (missing, forged, another worker's, another question's or older than `REPLAY_CHALLENGE_MINUTES`); ask for the code first |
| 429 | `rate_limited` | |
| 503 | `upstream_unavailable` | ML service down |

Throttles (`429` with `Retry-After`): registration 20 per 10 minutes per address; public verification, QR, key and revocation endpoints 120 per minute per address; live codes 30 per minute; one-time-code
requests 3 per number and 10 per address per 10 minutes. Rate-limit, CSRF and role-violation refusals are recorded in the audit trail (once per client per minute) and shown on the moderator console's Security tab.

## Public (no login)

| Method | Path | Body / notes | Success |
|---|---|---|---|
| GET | `/health` | | `200` text |
| GET | `/api/auth/session` | creates a session if none; returns the CSRF token | `{authenticated, role?, userId?, csrfToken}` |
| POST | `/api/worker/register` | `{name, phone, trade, language, consent}`. `consent` must be `true` or nothing is stored. `trade`: one of `electrician`, `plumber`, `carpenter`, `mason`, `welder`, `painter` (the list is `trades.json`; see `docs/RUBRIC_AUTHORING.md`). `language`: `hi`, `bn`, `en`. Same number in any format is the same account (`409` if registered). | `201 {registered, workerId}` |
| POST | `/api/employer/register` | `{name, phone, consent}` | `201 {registered, employerId}` |
| POST | `/api/auth/otp/request` | `{role: WORKER\|EMPLOYER, phone, pow?}`. A browser that has signed in as this account before (cookie `ks_device`) is served at once. Any other browser must first solve a proof-of-work puzzle: without a valid `pow` the answer is `428 {error:"proof_of_work_required", challenge, bits}` (the same for a registered and an unregistered number); solve it (find a counter so that SHA-256 of `challenge + ":" + counter` starts with `bits` zero bits; the page does this in `static/js/pow.js`) and ask again with `pow: {challenge, counter}`. A puzzle is good for 2 minutes, once, for the address it was issued to; the difficulty rises for an address that keeps asking. Otherwise always `200` for a well-formed number, whether or not an account exists (no enumeration). The code goes to that phone only. Sets the HttpOnly cookie `ks_bind` (10 minutes, path `/api/auth`) that ties the code to this browser. Limits: a trusted device 5 per 10 minutes of its own; an unknown device 3 per 10 minutes per phone and address, 12 per hour per phone, 10 per 10 minutes per address; `429` beyond them | `200 {ok, ttlSeconds}` |
| POST | `/api/auth/otp/verify` | `{role, phone, code}`. The code is checked against the challenge of THIS browser (its trusted device, or its `ks_bind` cookie): a browser with neither has no challenge, so guesses from a stranger are refused without counting against anyone. 5 wrong attempts lock the code. A successful sign-in sets the HttpOnly, SameSite=Strict cookie `ks_device` (180 days, path `/api/auth`, at most 5 devices per account), which only chooses the rate-limit bucket and never signs anyone in | `200 {ok, role, userId, csrfToken}` |
| POST | `/api/auth/moderator/login` | `{username, password}` of a NAMED moderator account (case-insensitive name); step 1 of 2. A wrong password, an unknown name and a disabled account all answer `401 invalid_credentials`; the code is sent to that moderator's own phone | `200 {otpRequired:true}` |
| POST | `/api/auth/moderator/verify` | `{code}`; step 2 of 2 (needs step 1 in the same session). The session then carries that moderator's own id; a moderator disabled later is refused on their next request (`401`) | `200 {ok, role:"MODERATOR", userId, csrfToken}` |
| POST | `/api/auth/logout` | | `200 {ok}` |
| POST | `/api/auth/demo-login` | **development only**: `{role: WORKER\|EMPLOYER, id}` signs in as a seeded demo account without a code. `404` unless `APP_ENV=dev` and `DEMO_LOGIN=true`; `403` for any account that is not a demo account | `200 {ok, role, userId, csrfToken}` |
| GET | `/api/credential/{ref}/verify` | `ref` is a UUID | `200 {state, valid, credentialRef, credential:{subject, trade, skillScore, skillBand?, trustScore, trustBand?, verifiedEmployerCount, issuedAt, validUntil, keyId}}`; `404` if unknown. `state` is `VALID`, `REVOKED`, `EXPIRED`, `INVALID_SIGNATURE` or `NOT_FOUND`. |
| GET | `/api/credential/{ref}/verify?p=<live code>` | as above, plus whether the optional live code is valid | adds `presentation:{state: NONE\|LIVE\|EXPIRED\|INVALID, ageSeconds}` |
| GET | `/api/credential/{ref}/qr` | | `image/png` (links to `/verify/{ref}`) |
| POST | `/api/credential/verify` | `{payload, signature}`: manual check of a downloaded document (signature checked first, then revocation/expiry looked up) | same as above; a tampered payload gives `INVALID_SIGNATURE` |
| GET | `/api/credential/public-key` | | PEM public key |
| GET | `/api/credential/keys` | every key a credential may have been signed with: the active one and retired ones (see CREDENTIAL_TRUST.md) | `200 {keys:[{keyId, status: active\|retired, algorithm, publicKeyPem}]}` |
| GET | `/api/credential/revocations` | the **signed** revocation list (newest 5,000, no reasons) | `200 {list:{type, issuedAt, keyId, revoked:[{ref, revokedAt}]}, proof:{keyId, canonicalization, signature}}` |
| GET | `/verify/{ref}` | HTML verification page | |

## Worker (`WORKER`)

| Method | Path | Body / notes | Success |
|---|---|---|---|
| GET | `/api/worker/me/passport` (or `/{ownId}/passport`) | another worker's id gives `404` | `{workerId, name, trade, language, skillScore, skillBand (PASS\|BORDERLINE\|NEEDS_REVIEW), skillAreas:{tools, materials, experience, knowledge, troubleshooting, planning, customer}, nearBoundary, trustScore, trustBand (EXCELLENT\|GOOD\|FAIR\|BUILDING\|NO_HISTORY), trustReport:{employerCount, feedbackCount, satisfaction, dimensionAverages, wouldHireAgainRate, averageEmployerCredibility, monthsSinceLastFeedback, consistency, integrityFlags, confidence, tips[]}, verifiedEmployerCount, latestCredentialRef, latestCredentialRevoked, latestAssessment:[{questionNo, score, needsReview}]}` |
| POST | `/api/assessment/start` | | `201 {attemptToken, trade, language, testOnly, questions:[{id, text, maxMarks}]}` (10 questions, easy to hard); the rubric criteria and keywords are never sent to the client |
| GET | `/api/assessment/challenge?attempt=<token>&question=<n>` | the spoken code for the NEXT unanswered question (`409` for any other question, so later codes cannot be collected in advance); at most 6 per question per hour (`429`). The code is made now from a server secret and is not stored. | `200 {required, code?, token?, minutes?}`: `code` is three digits to say at the end of the answer, `token` goes back with the recording, `required: false` when `REPLAY_CHALLENGE` is off |
| POST | `/api/assessment/submit-answer?attempt=<token>&question=<n>&challenge=<token>` | body = raw 16-bit PCM **WAV**, 0.5 to 60 s (measured from the data, not trusted from the header), max 2 MB. Questions must be answered in order, once each. The token is server-signed (HMAC), bound to the worker, valid 3 h. `challenge` is the token from the call above (required while `REPLAY_CHALLENGE` is on): the answer must contain the code spoken aloud. A recording or set of words that was used before is stored (`201`) with `needsReview: true` and score `0`; the response never says which check found it. | `201 {questionNo, transcript, score, feedback, needsReview, finished, skillScore?, skillFeedback?}` |
| GET | `/api/assessment/result?attempt=<token>` | | `{answers:[{questionNo, score, feedback, needsReview}]}` |
| POST | `/api/credential/issue` | needs a complete assessment with no answer under review and enough trust (`MIN_TRUST_FOR_CREDENTIAL`). Issuing supersedes (revokes) the worker's earlier credentials. | `201 {ref, issuedAt, expiresAt, verifyUrl}` |
| GET | `/api/credential/mine` | | `{credentials:[{ref, issuedAt, revoked, verifyUrl}]}` |
| GET | `/api/credential/{ref}/download` | owner only (`404` otherwise) | signed JSON `{credential, proof:{type, keyId, signature}}` |
| GET | `/api/credential/{ref}/live-qr` | owner only (`404` otherwise). A QR image whose link carries a **live code** (HMAC, bound to this credential, counts as live for 5 minutes); throttled to 30 a minute | `image/png`, header `X-Live-Expires-At` |
| POST | `/api/credential/{ref}/revoke` | `{reason?}`; owner, or a moderator | `{revoked}` (idempotent: the first reason is kept) |
| GET | `/api/worker/me/confirmations` | the approved confirmations about the signed-in worker, newest first; **no phone numbers** | `{confirmations:[{id, employerName, jobRole, workDate, ratingQuality, ratingPunctuality, ratingConduct, wouldHireAgain, response (NONE\|ACCEPTED\|DISPUTED), disputeReason, disputeDecision (UPHELD\|REMOVED\|null), heldOut, canRespond, daysLeftToDispute}]}` |
| POST | `/api/worker/confirmations/{id}/accept` | a record only; no score changes. Someone else's or an unknown id is `404`; answered already, approved too long ago or not approved is `409` | `{accepted:true}` |
| POST | `/api/worker/confirmations/{id}/dispute` | `{reason}`: 10 to 300 characters (control characters removed). Holds the confirmation out of every score until a moderator rules. `404` for someone else's or an unknown id; `409` if already answered, older than `DISPUTE_WINDOW_DAYS` (30) or the worker already has `DISPUTE_MAX_OPEN` (3) open; `400` for a bad reason. Refreshes trust and re-issues the credential | `{disputed:true}` |
| POST | `/api/worker/erase` | `{confirm:"ERASE MY DATA"}`: **DPDP deletion**, irreversible, ends the session | `200 {erased:true}` |

## Employer (`EMPLOYER`)

| Method | Path | Body / notes | Success |
|---|---|---|---|
| POST | `/api/attestation/request-otp` | `{workerPhone, jobRole (2-80 chars), workDate (YYYY-MM-DD, within the last year, not future), qualityRating, punctualityRating, conductRating (each 1 to 5, **required**), durationDays?, wouldHireAgain?}`. The ratings feed the trust score (`docs/TRUST_SCORE.md`). The OTP is sent to the **employer's own** phone as proof it is them. Refused: attesting yourself, unknown worker, a second live attestation for the same pair. | `201 {attestationId, status:"OTP_PENDING"}` |
| POST | `/api/attestation/verify-otp` | `{attestationId, code}`. Fraud-scored: `APPROVED` (trust edge created, trust recomputed, credential refreshed) or `PENDING_REVIEW` (needs a moderator). If the ML service is down it fails safe to `PENDING_REVIEW`. | `200 {attestationId, status, flagged, anomalyScore}` |
| GET | `/api/attestation/mine` | | `{attestations:[...]}` |
| POST | `/api/employer/erase` | `{confirm:"ERASE MY DATA"}`: anonymises the employer; endorsements already given stay | `200 {erased:true}` |

## Moderator (`MODERATOR`)

| Method | Path | Body / notes | Success |
|---|---|---|---|
| GET | `/api/moderator/flagged` | pending fraud flags with the features the model saw; no names or phone numbers | `{flagged:[{flagId, attestationId, anomalyScore, features, workerId, employerId, jobRole, reviewerDecision, createdAt}]}` |
| POST | `/api/moderator/decision` | `{flagId, decision: "VALID"\|"FRAUD"}`. A flag is decided once (`409` afterwards). `VALID` approves the attestation, creates the trust edge and refreshes trust. | `{approved}` |
| GET | `/api/moderator/audit-check` | verifies the hash chain and keyed head of the audit file **and** the anchors the application pinned in the database, and runs the database chain check | `{fileChainValid, fileLines, fileDetail, anchorsChecked, databaseChainValid, databaseRows}` |
| GET | `/api/moderator/employers[?all=1][&offset=N]` | employers for vetting (default: only those nobody has vetted), most confirmations first, then oldest first, **200 a page**: pass `offset=200`, `400`, ... for the next page (a bad offset is `400`); **no phone numbers** | `{employers:[{employerId, name, verified, verifiedBy, approvedConfirmations, registeredAt}], offset, pageSize}` |
| POST | `/api/moderator/vet-employer` | `{employerId}`: a moderator vouches for an employer after checking them outside the app. With two-person approval (`TWO_PERSON_ACTIONS`, the default) this only PROPOSES: `202 {pending:true, actionId}`, and nothing changes until a different moderator approves (below). `404` unknown employer, `409` already vetted or a proposal for this employer is already waiting. In single-moderator mode (development) it vets at once: `200 {vetted:true}`. Audited as `MODERATOR_ACTION_PROPOSED`, then `EMPLOYER_VETTED` on approval (actor = the approver, details name the proposer) | `202` or `200` |
| GET | `/api/moderator/disputes` | confirmations a worker has disputed and nobody has ruled on, oldest first, with the worker's reason, the employer's business name and ratings; **no phone numbers** | `{disputes:[{attestationId, workerId, employerId, employerName, jobRole, workDate, ratingQuality, ratingPunctuality, ratingConduct, wouldHireAgain, reason, disputedAt}]}` |
| POST | `/api/moderator/dispute-decision` | `{attestationId, decision: "UPHELD"\|"REMOVED"}`. UPHELD: it counts again. REMOVED: it never counts. With two-person approval this only proposes (`202 {pending, actionId}`); the dispute stays open and held out until a different moderator approves. `404` for one that is not open, `409` if a ruling is already waiting. Audited as `MODERATOR_ACTION_PROPOSED`, then `DISPUTE_DECIDED` on approval | `202 {pending:true, actionId}` or `200 {decided:true}` |
| GET | `/api/moderator/me` | who is signed in and which actions need a second person | `{moderatorId, username, displayName, twoPersonActions:[...], lifetimeHours}` |
| GET | `/api/moderator/pending` | proposals waiting for a second moderator (expired ones are dropped first), oldest first; no phone numbers | `{pending:[{actionId, type (VET_EMPLOYER\|DISPUTE_DECISION), targetId, target, verdict, proposedBy, proposedAt, expiresAt, canDecide}]}`; `canDecide` is false for your own proposals |
| POST | `/api/moderator/pending/{id}/approve` | `{}`: a moderator who did NOT propose it carries it out now (marking it executed and doing it are one transaction). `403` for the proposer (audited as `MODERATOR_SELF_APPROVAL_REFUSED`), `404` unknown id, `409` already decided, expired, or the target changed | `{approved:true}` |
| POST | `/api/moderator/pending/{id}/reject` | `{}`: a different moderator rejects it, or the proposer withdraws their own. `404`, `409` as above | `{closed:true}` |
| GET | `/api/moderator/security` | the Security tab: status, alerts, counts per event type (last hour / 24 hours), the 40 most recent security events and the audit-trail integrity | `{status: OK\|WARN\|CRITICAL, alerts:[{level,title,detail}], counts:{TYPE:{lastHour,last24h}}, integrity:{...}, recent:[{at,type,actor,details}]}` |

The same data is available to the JavaFX console (`kaarigarsetu-moderator-console`), which uses these endpoints.

## Internal ML service (Python, not reachable from outside)

Served at `/internal/ml/*`; every call must carry `X-Internal-Token` (shared secret from the environment; compared in constant time).
In `APP_ENV=prod` the service refuses to answer if the token is not configured. HTTP/1.1.

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/health` | | `{status, service}` (no token needed) |
| GET | `/internal/ml/trades` | | `{trades:[{key, trade, version, test_only}]}`: every trade that has a rubric file |
| GET | `/internal/ml/rubric/{trade}?language=` | | `{trade, version, test_only, questions:[{id, text, max_marks}]}` |
| POST | `/internal/ml/transcribe?language=` | multipart `audio` (WAV) | `{transcript, language}` |
| POST | `/internal/ml/audio-fingerprint` | multipart `audio` (WAV, 16-bit PCM) | `{hashes:[23-bit ints], times:[frame index], seconds, landmarks}`: the acoustic landmarks of the recording, never audio. `422` unreadable or empty, `413` too large |
| POST | `/internal/ml/evaluate` | `{trade, transcript, question_id, language}` | `{trade, score (0-100), feedback, matched_criteria, criteria_scores, needs_review, review_reason, provider}` |
| POST | `/internal/ml/fraud-check` | `{ip_frequency, device_frequency, minutes_since_previous, reciprocity_ratio}` | `{anomaly_score (0-1), flagged}` |
| POST | `/internal/ml/trust-score` | `{nodes:[id], edges:[{source, target, weight (0,1]}]}` | `{scores, scaled_scores}` (0-100, top node = 100) |

The Java client validates every field of every response (type, range, length) before using it; anything out of range is
treated as an upstream failure.
