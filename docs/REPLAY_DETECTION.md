# Replay-voice detection

**The attack:** someone plays a recording of a good answer into the voice test. The words are right, the transcript is right, the score is right. Nothing in the
scoring path looks at whether the recording is new.

**What was built** (migration 011, ML route `/internal/ml/audio-fingerprint`, `assessment/ReplayDetector`, `ReplayChallenge`, `SpokenCode`, `TextShingles`):
three layers that do different jobs. None of them is speaker verification; the honest list of what they do not stop is in section 6.

| Layer | What it stops | What it does not stop |
|---|---|---|
| 1. A **spoken code** made when the question is shown | a recording made earlier (it cannot contain a code that did not exist yet) | a recording made after the code was shown, a live helper |
| 2. An **acoustic fingerprint** of every recording, compared with every earlier answer of every worker | the SAME recording played again, through noise, a different volume, a narrower band, another sample rate, a trimmed start or end, a room, a speaker-to-microphone hop | a different recording; a change of speed |
| 3. A **word fingerprint** of every transcript, compared the same way | the same words said again on different audio: a time-stretched copy, a synthetic voice reading a stolen transcript, a shared script | an answer given in new words |

## 1. The flow

```
question n is shown ──► GET /api/assessment/challenge?attempt=..&question=n
                         ◄── { code: "472", token: "n.<expiry>.<mac>", minutes: 15 }       (the page shows "say 4 7 2 at the end")

worker records the answer, the page uploads it ──► POST /api/assessment/submit-answer?attempt=..&question=n&challenge=<token>
   1. attempt token, order of questions, WAV validation                    (as before; invalid audio never reaches the steps below)
   2. the challenge token is verified (HMAC over worker, attempt, question and expiry): missing, forged, another worker's,
      another question's or older than 15 minutes -> 428 challenge_required, before any model is called
   3. the recording goes to the ML service twice: transcribe, and fingerprint (the audio is zeroed in memory as soon as both are back)
   4. is the code in the transcript?   no -> 422 code_not_heard (tries 1 and 2), the answer is NOT stored and the worker says it again
                                       no, third try -> stored, score kept, held for review (CHALLENGE_NOT_HEARD)
   5. score the transcript as before
   6. ReplayDetector: search every stored answer for the same recording and the same words
        match -> status REPLAY_AUDIO / REPLAY_TEXT / REPLAY_BOTH, score 0, held for review, audit event REPLAY_SUSPECTED
        fingerprint could not be taken -> CHECK_FAILED, held for review (a security check that cannot run does not pass)
        too little audio or too few words -> TOO_SHORT, decides nothing
   7. steps 6 and the storing happen in ONE transaction that first takes a database lock (pg_advisory_xact_lock), so answers are compared and stored one at a time
```

The code is not stored anywhere: it is `HMAC(server key, worker, attempt, question, expiry)` reduced to three digits 1 to 9, so the server can recompute it from the token the
page sends back. Only the NEXT unanswered question can be asked about (`409` otherwise), and at most six times per question per hour (`429`), so the codes of later questions cannot be
collected in advance or by trying again and again. A code is accepted when its three digits appear one after another in the transcript, with at most one other word between neighbours (a recogniser may insert a filler), written as digits (`472`, `4 7 2`, Devanagari numerals), as English words, as Hindi words or as romanised Hindi (`SpokenCode`); digits scattered through an answer do not add up to a code. Three digits from 1 to 9 are only 729 codes: the code is there to defeat a recording made BEFORE the question, not to be unguessable, and an attacker who splices a live-said code into a recording they reuse is caught by the fingerprint, because the rest of the recording is the same.

## 2. The acoustic fingerprint

The method behind audio-recognition services, tuned for speech (`kaarigarsetu-ml-service/app/services/audio_fingerprint.py`):

1. Mono, 8 kHz (every phone microphone and codec keeps that band), 64 ms windows every 16 ms, 250 to 3,600 Hz.
2. A **peak** is a spectrogram point that is the loudest within about +-94 Hz and +-160 ms and within 42 dB of the loudest point of the recording. Speech has many: the harmonics of each syllable.
3. Each peak is paired with up to 5 of the strongest peaks 16 ms to 1 s after it and within +-750 Hz; each pair becomes one 23-bit number: (frequency of the first, frequency of the second, time between them).
4. The ML service returns those numbers and the frame each came from. **Never audio.** The application keeps `HMAC-SHA256(key, number)` truncated to 63 bits, with the frame.
5. To compare: every landmark of the new recording is looked up (with its time gap one frame shorter and one longer, see below), the stored hits are grouped by (earlier answer, time shift), and the
   biggest group, pooled with the groups one frame either side, is the **score**: how many landmarks agree on one consistent shift. The same recording gives a tall spike; chance gives none.

Two details found by measuring, not by design: a copy that starts a fraction of a frame later (every real replay does) puts every peak one frame off, which changes each landmark's time gap by one and
originally dropped matches to 14 % of landmarks; looking each one up as gap-1, gap and gap+1 and pooling neighbouring shifts brought it to 47 % for an arbitrary trim, and that is why the search
is written as one set-based statement (`ReplayDao.findAudioVotes`).

### Measured (`kaarigarsetu-ml-service/benchmarks/replay_benchmark.py`, macOS voices Lekha and Samantha, 8 clips of 8 to 11 seconds)

Landmarks of a copy found in the original at one consistent time shift (mean and minimum over the 8 clips), threshold `REPLAY_MIN_MATCHES` = 25:

| What was done to the recording | matches (mean) | min | share of landmarks | caught |
|---|---|---|---|---|
| nothing (identical) | 1,803 | 1,525 | 1.00 | 8/8 |
| volume x 0.2 | 1,801 | 1,524 | 1.00 | 8/8 |
| noise, 20 dB | 1,417 | 1,155 | 0.78 | 8/8 |
| noise, 10 dB | 987 | 685 | 0.54 | 8/8 |
| noise, 5 dB | 738 | 521 | 0.41 | 8/8 |
| band-pass 300 to 3,400 Hz | 1,580 | 1,365 | 0.89 | 8/8 |
| 16 kHz to 8 kHz and back | 1,790 | 1,517 | 1.00 | 8/8 |
| start 0.6 s and end 0.5 s cut | 776 | 662 | 0.49 | 8/8 |
| hard clipping | 445 | 297 | 0.25 | 8/8 |
| room reverb, RT60 0.4 s | 197 | 165 | 0.12 | 8/8 |
| speaker to microphone (band-pass, clipping, reverb, noise) | 199 | 134 | 0.11 | 8/8 |
| **speed +3 %** | 6 | 3 | 0.00 | **0/8** |
| **speed +8 %** | 3 | 1 | 0.00 | **0/8** |
| **speed -5 %** | 2.8 | 1 | 0.00 | **0/8** |

**Different recordings:** across all 28 pairs the highest was 18 (two clips that share a phrase, which a synthetic voice renders identically; a person never does) and the median 2; on clips with no shared
phrase the most was 4. The threshold of 25 sits between "4 to 18" and "100 or more" (the worst replay, a speaker played into a microphone, kept 134). `REPLAY_MIN_LANDMARKS` = 100: a recording with fewer
landmarks (silence, a blip) is "too short" and decides nothing.

## 3. The word fingerprint

Speed is the one thing the audio layer cannot survive, and it is the cheapest thing for an attacker to change. The words do survive it. Each transcript is reduced (`TextShingles`) to its **word trigrams**
(lower-cased, punctuation and numbers dropped, `[annotations]` such as `[Music]` dropped, so the spoken code differs every time and never matters), stored as keyed hashes, and searched across every earlier answer.

A match needs all of: the new answer has at least `REPLAY_TEXT_MIN_SHINGLES` = 14 trigrams; at least `REPLAY_TEXT_MIN_COMMON` = 8 are shared; the shared ones are at least `REPLAY_TEXT_MIN_SHARE` = 0.5 of the
SMALLER of the two answers. A worker's own earlier ATTEMPT is not a match (people repeat themselves when they retake); the same words from another worker, or on another question of the same attempt, are.

### Measured (same clips, real Whisper `large-v3-turbo` transcripts of each changed copy)

Share of the original's word trigrams that the transcript of the changed copy still has (mean and minimum over the 8 clips), and how many copies the text check flags (all of: at least 14 trigrams, at least 8 shared, share at least 0.5):

| What was done to the recording | share (mean) | min | flagged by the word check |
|---|---|---|---|
| nothing (identical) | 1.00 | 1.00 | 8/8 |
| volume x 0.2 | 0.97 | 0.89 | 8/8 |
| noise, 20 dB | 0.82 | 0.66 | 8/8 |
| noise, 10 dB | 0.75 | 0.44 | 7/8 |
| noise, 5 dB | 0.62 | 0.33 | 4/8 |
| band-pass 300 to 3,400 Hz | 0.89 | 0.79 | 8/8 |
| 16 kHz to 8 kHz and back | 0.97 | 0.89 | 8/8 |
| start 0.6 s and end 0.5 s cut | 0.82 | 0.55 | 8/8 |
| hard clipping | 0.81 | 0.41 | 7/8 |
| room reverb, RT60 0.4 s | 0.75 | 0.43 | 7/8 |
| speaker to microphone | 0.59 | 0.22 | 4/8 |
| **speed +3 %** | 0.94 | 0.88 | **8/8** |
| **speed +8 %** | 0.92 | 0.79 | **8/8** |
| **speed -5 %** | 0.95 | 0.89 | **8/8** |

**Different answers:** the most word trigrams shared by any two of the 8 clips was 0.

The two checks fail in opposite places, which is why there are two: the audio check catches every noisy, clipped or speaker-to-microphone copy and none of the speed changes; the word check catches every
speed change and misses about half of the harshest noise and speaker-to-microphone copies (the recogniser mishears them). Every change in these tables was caught by at least one of them. That is a statement
about these 8 synthesised clips and these changes, not a guarantee against a copy made to defeat both at once.

## 4. What is stored, and who can see it

| Table | Holds | Not |
|---|---|---|
| `answer_fingerprints` | `fp` (a 63-bit keyed hash of one landmark), `t` (frame), `assessment_id` | audio, spectrogram, anything reversible |
| `answer_shingles` | `sh` (a 63-bit keyed hash of one word trigram), `assessment_id` | words |
| `assessments` | `replay_status` (`NOT_CHECKED, CLEAR, TOO_SHORT, REPLAY_AUDIO, REPLAY_TEXT, REPLAY_BOTH, CHECK_FAILED`), `challenge_status` (`NOT_ASKED, HEARD, NOT_HEARD`), two counts | |

The key is `PHONE_HMAC_KEY` with a purpose label, so the hashes are useless without it. Both tables `ON DELETE CASCADE` from the answer, so erasing a worker erases their fingerprints (tested on real PostgreSQL in
`ErasureIntegrationTest`). The raw recording is still never stored. The application role may only `SELECT` and `INSERT` on them (`setup_roles.sql` revokes the `UPDATE` that its general grant would give). The audit event `REPLAY_SUSPECTED` carries the status, the matched answer's id, the numbers and
whether it was the same worker: no audio, no words, no phone.

## 5. Settings

| Variable | Default | Allowed | Meaning |
|---|---|---|---|
| `REPLAY_CHALLENGE` | true | | the spoken code; may be switched off |
| `REPLAY_CHALLENGE_TRIES` | 3 | 1 to 5 | tries before the answer is stored and held |
| `REPLAY_CHALLENGE_MINUTES` | 15 | 1 to 120 | life of a code |
| `REPLAY_DETECTION` | true | | the fingerprint search; **refused to be false when `APP_ENV=prod`** |
| `REPLAY_MIN_MATCHES` | 25 | 8 to 500 | landmarks at one shift to call a recording the same (below 8 chance could reach it) |
| `REPLAY_MIN_LANDMARKS` | 100 | 20 to 3,000 | fewest landmarks that make a recording checkable |
| `REPLAY_TEXT_MIN_SHINGLES` | 14 | 8 to 400 | fewest trigrams that make an answer checkable |
| `REPLAY_TEXT_MIN_COMMON` | 8 | 4 to 200 | trigrams in common |
| `REPLAY_TEXT_MIN_SHARE` | 0.5 | 0.5 to 1.0 | share of the smaller answer |

Values outside the allowed range stop the application at start-up (`AppSettings.validate`, `ReplaySettingsTest`).

## 6. What this does not stop (say it out loud)

1. **A fresh, original recording with the live code said (REPLAY-06).** Detection finds RE-USE. A person who records a new answer, or reads a prepared script aloud for the first time, and says the code, is accepted; so is a bespoke recording with the code spliced in live, the FIRST time it is used (the second use is found by the fingerprint).
   Nothing here can tell that person from a tradesperson who knows the work.
2. **A live helper**, or live text-to-speech of an original script with the code typed into it. There is no speaker verification and no anti-spoofing (synthetic or converted voice) model.
3. **A speed change defeats the audio layer** (the table above: 0 of 8 at +-3 %). The word layer covers it, but only for answers of 14 or more trigrams, and only when the recogniser hears the same words.
4. **Thresholds were set on synthesised speech** (two macOS voices) and Whisper's reading of it, not on real tradespeople on real phones in real workshops. Real noise, clipping and codecs are harsher on
   the fingerprint; real people never repeat themselves identically, which lowers false matches. Neither is measured. The margin is wide (4 to 18 against 100 or more) but it is a margin on a stand-in.
5. **The spoken code depends on the recogniser hearing digits.** A worker with an accent it misses is held after three tries (stored with their score, not zeroed). Whisper reads digits well in the tests,
   but this is not measured on real speakers either.
6. **No human review queue** for held answers yet: the worker is told the answer is held and records again. A wrongly held answer has no appeal beyond that.
7. **Fingerprints persist with the answer**, and are compared across all workers. A key change (`PHONE_HMAC_KEY`) makes older fingerprints unmatchable.
8. **Answers given before the upgrade have no fingerprints**, so the first recordings can only be recognised against answers given after it.
9. **Scale.** Every answer is compared with every stored one, under one global lock. Measured: fingerprinting a 60-second recording takes 0.07 s in the ML service; the database search with 6,000 probes over 4 million stored landmarks (about 2,000 answers) took 0.43 s, and its cost grows with the number of stored rows. That is comfortable for a pilot of some hundreds of workers; at tens of thousands the search under the lock (several seconds) becomes the limit on how many answers can be stored per second, and the work should then move to a purpose-built index (and the lock to a per-bucket one). The 23-bit landmark hash space makes chance matches more frequent as the table grows; the aligned-shift vote keeps them spread thin, but the threshold of 25 should be re-measured on a table of realistic size.
10. **Found by testing the live stack, then fixed:** two identical uploads arriving at the same instant each searched before the other was stored and both passed (6 of 6 pairs). The check and the store now run under one lock (`ReplayDao.lockAnswerChecks`); after the fix exactly one of each pair passes (`ReplayDaoIntegrationTest`, harness REPLAY-07).

The controls that remain for all of these are outside this feature: the employer-confirmed trust score, the vetted-employer rule, and the credential showing the skill band and the number of employers who confirmed it.

## 7. Tests

| Test | What it proves |
|---|---|
| ML `test_audio_fingerprint.py` (25) | the landmarks survive noise, reverb, trim, resample, a level change; different signals and a constant tone do not match; bad and oversized input is refused |
| `SpokenCodeTest`, `ReplayChallengeTest` (10) | the code is heard in digits, words and Hindi; a token is tied to worker, attempt, question and time; forged, foreign and stale tokens fail |
| `TextShinglesTest`, `ReplayDetectorTest` (21) | trigrams ignore numbers and annotations; audio and text verdicts, own-attempt exclusion, thresholds, fail-closed |
| `ReplayDefencesPipelineTest` (15) | the pipeline: challenge only for the next question, 428 before any model call, the tries, stored-held on the third, a replay scores 0 and is audited without naming anyone, CHECK_FAILED, off-switches, the audio is zeroed even when the code is refused |
| `ReplayDaoIntegrationTest` (7), `ErasureIntegrationTest` | real PostgreSQL: statuses and counts, constraint on the status values, the shift grouping, duplicate trigrams, cascade on answer and on worker erasure, and two simultaneous uploads being compared one after the other |
| `ReplaySettingsTest`, `MlServiceClientTest` | start-up validation; the fingerprint response is accepted only if it is a consistent, bounded set of numbers |
| e2e section 5a (19 checks), `api_matrix.py` | the real stack: the code endpoint, the missing / forged / foreign token, the retries, the same recording from another account, a noisy / quieter / trimmed copy, the same words on new audio, a flagged answer blocking the credential |
| harness REPLAY-01 to REPLAY-07 | the attacks above, ending in BLOCKED, and one RESIDUAL (the fresh recording) |

**Test fixture note.** The labelled test transcriber (`STT_PROVIDER=stub`) ignores the audio; it reads two markers a test puts in the recording (`KSCODE:ddd` is "the code said", `KSWORDS:n` is "this long text"). Its canned
sentences are kept below 14 trigrams, and a test (`test_stub_fixture_vs_replay_check.py`) fails if they grow, because the first live run flagged the fixture itself as copied words.

## 8. Reproduce

```bash
cd kaarigarsetu-ml-service && .venv/bin/python benchmarks/replay_benchmark.py            # the audio table (macOS: needs `say` and `afconvert`)
cd kaarigarsetu-ml-service && .venv/bin/python benchmarks/replay_benchmark.py --stt      # plus the word table (loads Whisper; slow)
STT_PROVIDER=stub ./scripts/dev.sh &  python3 scripts/e2e.py                             # section 5a
python3 scripts/security_attack_demo.py --only REPLAY                                    # REPLAY-01 to REPLAY-07
```
