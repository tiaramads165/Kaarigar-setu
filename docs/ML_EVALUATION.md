# ML Evaluation (Phase 8, Domain 2)

Three ML components sit behind the Java service (`/internal/ml/*`, reachable only with the shared internal token):

| Component | What it does | How it is evaluated here |
|---|---|---|
| Isolation Forest (scikit-learn) | Scores each employer attestation for fraud-like behaviour | Held-out precision/recall on **synthetic** data, 20 seeds, per fraud pattern (section 1-3) |
| Rubric scorer (LLM, with a keyword fallback) | Scores one transcribed answer against the rubric | Security/robustness tests with a fake model; **agreement with a human grader has NOT been measured** (section 4) |
| PageRank trust graph (NetworkX) | Turns attestations into a 0-100 trust score | Property tests, and the Java-side weighting rules (section 5) |

> **Read this first.** The fraud data is synthetic and the rubric is a TEST ONLY placeholder. These numbers show that the
> methods work and how they trade off. They are **not** evidence of accuracy on real tradespeople, real employers or real
> fraud, and must not be quoted as such.

## 1. Isolation Forest: data and method

Per attestation the Java backend computes four features from its own tables and sends only those numbers:

| Feature | Meaning |
|---|---|
| `ip_frequency` | attestations from the same (hashed) IP in the previous 24 h, including this one |
| `device_frequency` | the same for the hashed device fingerprint |
| `minutes_since_previous` | minutes since this employer's previous attestation (a first attestation is sent as 1 440, "an ordinary day"; gaps are capped at 10 080) |
| `reciprocity_ratio` | share of this employer's attestations that are mutual (the attested worker also vouched for the employer) |

`app/training/synthetic_data.py` builds a reproducible set (seed 42): 2 000 normal rows and 200 fraud rows, the fraud being
four hand-written patterns: **burst** (many attestations within minutes), **vouching ring** (high reciprocity),
**same IP** (many from one address) and **shared device** (one device vouching for many). The model is trained
**without labels**, on the normal training rows only, which mirrors reality (fraud is rare and unlabelled). Labels are used
only to score the held-out 30 %.

`contamination` is the one knob: the share of rows the model is told to treat as anomalous, i.e. how aggressively it flags.
The committed model uses 0.03.

## 2. Results (held-out, mean ± std over 20 seeds)

Reproduce: `python -m app.training.evaluate_isolation_forest --seeds 20` (raw numbers in `docs/evidence/ml_tradeoff.json`).

| contamination | precision | recall | false-positive rate | recall: burst | recall: vouching ring | recall: same IP | recall: shared device |
|---|---|---|---|---|---|---|---|
| 0.005 | 0.89 ± 0.06 | 0.35 ± 0.09 | 0.005 | 0.13 | 0.16 | 0.13 | 1.00 |
| 0.01 | 0.82 ± 0.06 | 0.45 ± 0.11 | 0.010 | 0.24 | 0.27 | 0.30 | 1.00 |
| 0.02 | 0.79 ± 0.06 | 0.76 ± 0.12 | 0.021 | 0.67 | 0.56 | 0.81 | 1.00 |
| **0.03 (shipped)** | **0.74 ± 0.05** | **0.88 ± 0.07** | 0.032 | 0.80 | 0.79 | 0.96 | 1.00 |
| 0.05 | 0.64 ± 0.04 | 0.96 ± 0.02 | 0.054 | 0.86 | 0.99 | 1.00 | 1.00 |
| 0.08 | 0.53 ± 0.04 | 0.98 ± 0.02 | 0.086 | 0.90 | 1.00 | 1.00 | 1.00 |

The committed model on its own single split (seed 42, 660 held-out rows of which 60 are fraud), from
`kaarigarsetu-ml-service/app/models/isolation_forest.metrics.json`: 48 true positives, 18 false positives, 12 false negatives,
**precision 0.727, recall 0.800, F1 0.762**. That is one draw; the 20-seed row above (0.74 / 0.88) shows the spread.

## 3. Reading the trade-off

* **Recall versus review workload.** Raising `contamination` catches more fraud but sends more genuine attestations to a human.
  At 0.03, about 3 % of normal attestations are flagged (false-positive rate 0.032) and roughly 9 in 10 fraud cases are caught.
  At 0.01 the queue is tiny but more than half of the fraud gets through. At 0.08 almost everything is caught but about 1 in 2 flags is a false alarm.
* **Why 0.03.** Flagged attestations are not rejected: they go to a human moderator (`PENDING_REVIEW`), so a false positive costs a
  moderator a minute while a false negative lets a fake endorsement raise someone's trust score. The expensive error is the miss,
  so the chosen point favours recall while keeping the queue manageable. A different pilot may choose differently; it is one
  constant in `train_isolation_forest.py`.
* **Per-pattern weakness.** "Shared device" is always caught (its feature values are extreme). Bursts and vouching rings are the
  hard cases at low thresholds (recall 0.13 to 0.27 at 0.005 to 0.01), because a few mild examples look like busy honest employers.
  A model trained only on these four patterns says nothing about patterns nobody thought of.
* **Fail-safe.** If the ML service is down, the Java side does **not** approve: the attestation goes to human review with
  the note `ml_unavailable` (tested in `AttestationWorkflowServiceTest`).
* **Model integrity.** The pickled model is only loaded if its SHA-256 matches the recorded digest (a tampered file is
  refused; tested), because loading an untrusted pickle can execute code. In production a missing model or digest is an error.

## 4. Rubric agreement with a human grader: not done

The blueprint asks for a comparison of the LLM's scores with a human grader on a sample of transcripts. That **cannot be
produced honestly yet**: it needs (a) the real electrician rubric, which is being supplied separately, (b) real spoken answers
transcribed, (c) a human who marks them against that rubric *without seeing the automatic score*, and (d) a real LLM key. None
exists. Inventing the numbers would be fabrication, so this section reports the status instead.

What exists and is tested:

* `python -m app.training.rubric_agreement graded.csv` takes a CSV `trade, question_id, language, transcript, human_score`,
  runs the configured scorer, and reports n, Pearson r, Spearman rho, mean absolute error, mean bias, **agreement on the three
  decision bands** (pass >= 70, borderline 50-69, needs review < 50) and Cohen's kappa on those bands. It prints which provider
  scored (`keyword` or `llm:<model>`) so a keyword result is never mistaken for an LLM result, and it refuses fewer than 30 rows.
* Its arithmetic is unit-tested with made-up numbers (`tests/test_rubric_agreement.py`: perfect agreement, reversed scores,
  bias sign, tied ranks, chance-level kappa, refusal of tiny samples). Those tests say nothing about the real scorer.

How to complete it: collect at least 30 to 50 real transcripts, have two graders mark them independently (also report grader-vs-grader
agreement as the ceiling), run the tool with `EVALUATOR=llm`, and put the output here. Suggested acceptance rule to agree with the
domain expert in advance: band agreement and kappa, not just correlation, because the pass/borderline/review decision is what matters.

## 5. LLM scorer: what *is* tested (robustness, not accuracy)

The transcript is untrusted. Defences, each with a test in `tests/test_evaluate.py`:

* the transcript is sanitised (Unicode normalised, invisible and bidi characters stripped, length capped) and placed inside a prompt
  fenced with a **random nonce**, with placeholders never re-expanded;
* the model must return strict JSON that validates against a schema; unknown, missing, duplicated or negative criteria make the
  answer **zero marks plus human review**, never a guess;
* marks are clamped to each criterion's maximum and **the total is recomputed by us**, so a fooled model cannot award more than the rubric allows;
* a model that sets `suspicious`, a transcript that matches injection patterns (English and Hindi), or full marks on a very short answer (under 15 characters), is flagged `needs_review`;
* feedback containing links is dropped; the rubric endpoint never exposes grader-only content (keywords, ideal notes);
* a provider outage is HTTP 503, never a silent wrong score.

Speech-to-text: real Whisper is wired in and was run through the full stack on synthetic speech (macOS Hindi and English voices): complete sentences come back, silence is dropped, and unrelated answers score 0.

**Model choice, measured.** Five Hindi sentences about electrical work, spoken by the macOS Hindi voice, each as a clean clip and as a degraded clip (quiet, band-limited to 4 kHz, room noise at 15 dB SNR). Character error rate (lower is better) and seconds per answer on an Apple-silicon CPU; reproduce with `kaarigarsetu-ml-service/benchmarks/` (macOS only):

| Model | Clean | Noisy | Seconds per answer |
|---|---|---|---|
| small (first default; it garbled real speech) | 12.0 % | 18.7 % | 3 to 10 |
| medium | 10.4 % | 13.3 % | 7 to 29 |
| **large-v3-turbo (now the default)** | **4.6 %** | **9.3 %** | 5 to 10 |

Real Hindi from a person's microphone is harder than this synthetic voice, so absolute numbers will be worse; the ranking is what the test is for. A real answer recorded earlier on `small` came back partly as junk (for example `remedies criterion 重新切換`).

**Decoding and scoring changes made for Hindi:** beam search (5) for accuracy (halves speed; `WHISPER_BEAM` to change); a script-only Hindi hint (`यह एक कारीगर का बोला हुआ जवाब है।`) so output stays in Devanagari, deliberately with no trade or rubric words so it cannot make the model hear answers that were not said (a test enforces this); recordings are levelled (quiet phones), leading and trailing silence is trimmed, and silent recordings never reach the model; segments with foreign scripts (CJK, Arabic, Cyrillic), low confidence or repetitive output are dropped, so a bad recording becomes "we could not understand that, please try again" instead of a junk score; the browser resamples with an averaging filter instead of plain interpolation (4x less aliasing noise on a test tone). The keyword scorer ignores Devanagari spelling variants (nukta, chandrabindu, a stray virama) and forgives one wrong letter per five letters of a keyword (never for keywords under five letters). **Not measured:** accuracy on real workers' speech (accents, noise, phone microphones) and the real LLM's behaviour. `STT_PROVIDER=stub` is a labelled test fixture that ignores the audio and must never be used for hands-on testing. Whisper is opt-in in the Docker image (`WITH_WHISPER=1`).

The keyword fallback scorer only checks which words appear; it cannot tell a wrong answer that uses the right words from a right answer. The assessment screen says so after each answer when the keyword scorer produced the score.

## 6. PageRank trust score

NetworkX PageRank over a directed graph of employers and workers; parallel edges add up; scores sum to 1 and are rescaled so the
most trusted node is 100. Tests: sums to one, isolated nodes included, a vouch from a high-trust node moves the score more than one from a
low-trust node, empty graph and bad weights rejected.

The Java side (`TrustScoreService`) applies the rules that make the score hard to farm, each unit-tested:

* **edge weight** = employer's work-quality answer (1.0 would hire again, 0.8 unknown, 0.4 would not) x recency decay (`e^(-age/365 days)`)
  x `(1 - 0.5 x anomaly score)`, kept inside (0.05, 1];
* **reverse edge** at half weight from worker to employer, so a well-connected employer's vouch counts for more;
* **confidence factor** `min(1, distinct verified employers / 3)`: one possibly colluding vouch can never produce a top score;
* the same employer vouching three times counts as one employer.

## 7. Speech-to-text: synthetic voice versus real speakers

### 7.1 Correction (2026-10-13)
Earlier figures in this section (CER 3.4 % / 7.5 % for openai-whisper and 3.8 % / 8.6 % for faster-whisper) came from a quick script that kept only
characters Python counts as letters. Devanagari vowel signs (matras) are *not* letters to Python, so they were dropped from both the reference and
the transcript, which hides most vowel mistakes. The numbers were too good. `benchmarks/accent_benchmark.py` keeps vowel signs and reports two metrics:
STRICT (spelling must match) and FOLDED (spelling variants like हूँ/हूं treated as equal, how the keyword scorer reads text).
The openai-whisper figures cannot be recomputed (that engine is no longer installed); treat them as relative only.

### 7.2 Synthetic voice (one macOS voice, 5 sentences), faster-whisper 8-bit large-v3-turbo, corrected metric

| Condition | STRICT WER / CER | FOLDED WER / CER |
|---|---|---|
| clean | 13.6 % / 5.9 % | 11.1 % / 4.8 % |
| noisy phone-like | 23.5 % / 10.1 % | 22.2 % / 9.5 % |

### 7.3 Real speakers: Google FLEURS Hindi, held-out test split (60 clips sampled evenly from 418; 209 male + 209 female speakers)
Same code path as production (level normalisation, silence trim, beam 5, script prompt, hallucination filters). FLEURS is read speech in good
conditions with no accent labels: it means "many real voices", not "accents". Real workers on noisy phones will do worse.

| Setup | STRICT WER / CER | FOLDED WER / CER | Empty transcripts | Time per clip (median) |
|---|---|---|---|---|
| **Current default** (turbo 8-bit, filters on, script prompt) | 34.5 % / 18.0 % | **32.3 % / 16.8 %** (male 26.4, female 36.4) | 3 of 60 | 8.7 s |
| same, hallucination filters OFF (experiment) | 32.5 % / 15.8 % | 30.2 % / 14.5 % | 2 of 60 | 10.8 s |
| same, no script prompt (experiment) | n/a | 31.4 % / 16.1 % | 3 of 60 | 12.0 s |

Reading this: on real voices the current model is wrong on about a third of words, far from what the synthetic voice suggested. The filters cost
about two points on clean read speech; they exist to stop Whisper inventing text on silence and noise, which this clean data cannot show, so they stay
on until real pilot audio says otherwise. Differences of one point between rows are within the noise of a 60-clip sample.

The model is a community 8-bit conversion (`Zoont/faster-whisper-large-v3-turbo-int8-ct2`, MIT) pinned to revision `846f7479...`; its weights are a plain
binary tensor file (no pickle, no code execution).
