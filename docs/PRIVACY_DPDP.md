# Privacy and DPDP Act Compliance Notes

Engineering notes for the Digital Personal Data Protection Act, 2023 (India). This describes what the **software** does. It is not
legal advice: the consent wording, retention periods and the processor agreements below need review by someone qualified before a real pilot.

## 1. Personal data inventory

| Data | Whose | Where | Protection | Retention |
|---|---|---|---|---|
| Name | worker, employer | `workers.name`, `employers.name` | access control; never put in credentials or the audit log | until erased |
| Phone number | worker, employer | `phone_enc` (AES-GCM, random IV, key from `PHONE_ENC_KEY`); `phone` column holds only a keyed HMAC-SHA256 lookup key (`h1:...`), so it can be matched but not read | encrypted at rest; masked (`******3210`) in logs; never in URLs or credentials | until erased |
| Trade, language | worker | `workers` | | until erased |
| **Voice recording** | worker | **not stored**: held in memory, sent to the ML service for transcription, then overwritten with zeros (tested) | | seconds |
| **Voice fingerprint** of each answer (replay detection) | worker | `answer_fingerprints` (a keyed hash of each acoustic landmark and the frame it was found at), `answer_shingles` (a keyed hash of each three-word run of the transcript) | **not audio and not words**: one-way HMAC-SHA256 with a key held only by the application (`PHONE_HMAC_KEY` with a purpose label), so the voice cannot be rebuilt and the words cannot be read back from them; a landmark says nothing about who spoke. Used only to recognise the same recording or the same words being used again. The numbers are counts and statuses on the answer (`replay_status`, `challenge_status`) | removed with the answer (`ON DELETE CASCADE`), so erasing a worker erases them (tested on real PostgreSQL) |
| Transcript of each answer, score, feedback | worker | `assessments` | **encrypted at rest** (AES-256-GCM, `DataCipher`), plus access control | until erased |
| Skill / trust score | worker | `workers`, credential | **encrypted at rest**; pseudonymous in the credential | until erased |
| Employer feedback (job role, duration, would hire again, **quality / punctuality / conduct ratings**, work date) and the worker's **trust report** (derived from it) | employer about worker | `attestations`, `workers.trust_report` | | until the worker is erased |
| Hashed IP and device fingerprint | employer | `attestations.source_ip_hash`, `device_hash` (HMAC, not reversible without the key) | used only for fraud features | until the worker is erased |
| Credential | worker | `credentials` + exported file | subject is a random per-worker UUID (`urn:ks:subject:...`), no name or phone; the stored payload and the exported file are **encrypted at rest** | until erased |
| OTP challenges | all | `otp_challenges`: salted PBKDF2 hash only | 5 minutes of use; keyed by a phone-derived value | deleted on erasure |
| Audit log | all | `audit_log`, `audit.log` | ids only, masked phones, hashed IP prefixes; append-only and hash-chained; the file and the details column are **encrypted at rest** (audit rows written before encryption stay plain: the table is append-only) | kept (see 4) |

## 2. Consent

* No consent, no data: `POST /api/worker/register` and `/api/employer/register` return `403 consent_required` and **store nothing**
  (tested, including in the live stack) unless `consent` is `true`.
* The consent timestamp (`consent_at`) and the version of the text accepted (`consent_version`, currently `2026-10-v1`) are stored on the account.
  Changing the consent text means bumping `WorkerService.CONSENT_VERSION`.
* **Draft only:** the consent wording on the start page (English and Hindi/Bengali) is a draft. It needs a legal review and a native-speaker review before use.
* Consent is asked before recording starts: the microphone is only used on the assessment page, which a registered, consenting worker reaches.

## 3. Things the notice must say (disclosures)

1. Transcripts of answers are scored by a **language model**. With `EVALUATOR=llm` the transcript text (not the audio, not the name or phone) is sent to an
   external provider through `LLM_API_KEY`. Name that provider and have a data-processing agreement. With no key, a local keyword scorer is used and nothing leaves the deployment.
2. Audio is sent to the speech-to-text component (Whisper runs inside this deployment's ML container; nothing is sent to a third party for it).
3. An employer can only name a worker by phone number and is never shown the worker's name or scores; a worker sees the job role, date and status of attestations about them, not the employer's identity details.
4. Anyone can verify a credential via its QR code. It reveals trade, scores, the number of verified employers and dates, and a pseudonymous id, but not who the person is.
5. OTPs are sent by SMS (when a real gateway is plugged in): the gateway provider processes phone numbers.
6. Each answer is checked against all earlier answers to catch a recording being played again. For that the system keeps, with the answer, a one-way fingerprint of the sound (keyed hashes of acoustic landmarks) and of the words (keyed hashes of three-word runs). It is not the recording and not the transcript; it cannot be turned back into either. It is compared across all workers, which is the point of the check, and it is deleted with the answer. A worker whose answer is held for being a possible repeat is told it is held for review, not which check found it. At present there is no human review queue for such answers: the worker records the answer again.

## 4. The deletion path (right to erasure)

**Self-service.** Worker or employer dashboard, "Your data": type `ERASE MY DATA` and press "Delete my data". Under the hood
`POST /api/worker/erase` or `/api/employer/erase` (`ErasureService`), in one database transaction:

*Worker*: delete all trust edges into or out of them; delete login OTP challenges; delete the `workers` row, which cascades to their assessments (transcripts),
attestations about them, fraud flags on those, and credentials. Then delete the exported credential files from disk, end the session, recompute trust for everyone else, and write
`WORKER_ERASED` (ids and counts only) to the audit log.
The fingerprints of the worker's answers go with the answers (`ON DELETE CASCADE` from `assessments`); nothing from them stays in another worker's record.
*Employer*: replace the name with `Erased employer`, the phone lookup key with an unusable `erased:<uuid>`, clear `phone_enc`, set `erased_at`; close attestation requests
still waiting for a code; delete login OTP challenges. The attestations they already gave **stay**, because they are the workers' endorsements and contain no personal data of the employer once the row is anonymised.

After erasure the number can register again from scratch. Verified in `ErasureIntegrationTest` (against the real schema) and in the end-to-end script (section 10:
credential stops verifying, file deleted from disk, number cannot log in, session ended, audit chain still valid).

**By an operator** (if the person cannot sign in; keep a record of the request and of who acted). As the database **owner**:

```sql
BEGIN;
-- for a worker with id :id
DELETE FROM trust_edges WHERE target_id = :id OR (source_kind = 'WORKER' AND source_id = :id);
DELETE FROM otp_challenges WHERE challenge_key = 'login:WORKER:' || (SELECT phone FROM workers WHERE worker_id = :id);
SELECT credential_ref FROM credentials WHERE worker_id = :id;   -- then delete data/credentials/<ref>.json for each
DELETE FROM workers WHERE worker_id = :id;                      -- cascades to assessments, attestations, fraud_flags, credentials
COMMIT;
```

**What is kept, and why.** `audit_log` is append-only and hash-chained so that history cannot be silently rewritten; it contains only the numeric id and the fact of erasure (never a name,
phone or transcript), so it identifies nobody once the account is gone. Backups made before the erasure still contain the data until they age out: set a backup retention and
record it. A retention schedule (for example, delete inactive accounts after N months) is **not implemented**; decide the period and add a scheduled job.

## 5. Not implemented / for the owner to decide

* Data-principal rights other than erasure: a worker can see their own passport, scores and attestation statuses; there is no correction request flow and no export of everything held.
* Withdrawal of consent is treated as erasure.
* Retention periods and inactive-account deletion (4).
* Breach notification procedure (see `docs/SECURITY_RUNBOOK.md` for detection steps only).
* A review queue and a way to contest a held answer (replay detection): today the worker records again; a wrongly held answer has no appeal beyond that.
* Guardian consent for minors: the system does not ask for age.
* Grievance officer contact, shown in the notice.
