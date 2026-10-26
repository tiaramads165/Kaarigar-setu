# Making speech recognition work for many accents: what is possible, what was measured, and how to train

Honest summary: **no one can promise "any Hindi accent".** What can be done is to measure on real voices, choose the best available model, and
improve it with recordings from your own workers. This page says how, and what needs resources the development Mac does not have.

## 1. Where the current model stands (measured)

| Test | Result |
|---|---|
| One synthetic voice, clean (what we used first) | 11 % word error (misleadingly good) |
| **60 real, different speakers (Google FLEURS, held-out test)** | **32 % word error, 17 % character error**; 3 of 60 clips came back empty |
| Same, hallucination filters off / script prompt off | 30 % / 31 % (differences this small are within noise) |

Details and the metric correction are in `docs/ML_EVALUATION.md` section 7. FLEURS is read speech in good conditions with no accent labels,
so real workers (phone microphones, noise, dialect, code-mixed English words) will be worse. **Measuring on your own pilot recordings is the only
number that matters.**

## 2. What "training for accents" really means

| Approach | What it needs | Feasible on this Mac? | Expected value |
|---|---|---|---|
| **A. Pick a better pre-trained model** | A Hindi-tuned model and a test set | Yes (slow downloads, 3 GB) | Often large: models tuned on thousands of hours of varied Indian speech beat the general one |
| **B. Fine-tune Whisper on public Indian speech** | A GPU (about 24 GB), 10 to 100+ hours of transcribed audio, a day of compute | **No** (no GPU, 8 GB free disk, memory already tight) | Good, but model A candidates were already trained this way |
| **C. Fine-tune on your own pilot recordings** | Consented recordings from real workers with human-written transcripts, a GPU for some hours | Data collection: yes. Training: needs a rented GPU | The best fit for *your* workers' accents and vocabulary |
| **D. Adapt without training** | nothing heavy | Yes | Small: a vocabulary hint (initial prompt) and better audio handling. Must never contain rubric answer words (it would "hear" them and inflate scores) |

**Recommended order:** A now (this repo has the benchmark), then C once the pilot has real recordings. B only if A is not good enough.

## 3. Approach A: choosing a model (done with `benchmarks/accent_benchmark.py`)

```bash
# one-time: the real-speaker test data (about 300 MB) and a parquet reader for the benchmark
python -c "from huggingface_hub import hf_hub_download; hf_hub_download('google/fleurs','hi_in/test/0000.parquet',repo_type='dataset',revision='refs/convert/parquet',local_dir='/tmp/accent')"
pip install --target /tmp/pa pyarrow
PYARROW_PATH=/tmp/pa python benchmarks/accent_benchmark.py <model folder or Hugging Face id> --revision <exact commit> --n 60
```

Candidates that already exist as CTranslate2 (faster-whisper) models, with their licences, are listed in the results section of
`docs/ML_EVALUATION.md`. A model is adopted only if (1) it beats the current one clearly on real speakers, (2) it is no worse on the synthetic
sentences and noise, (3) its size and memory fit the machine that will run it, (4) its licence allows the use, and (5) it is **pinned to an exact
revision** with its checksum recorded (a moving model silently changes scores). Community conversions are a supply-chain risk; prefer the original
authors' weights and verify checksums.

Warning on leakage: some Hindi models were trained on FLEURS, Common Voice or Kathbath. Test only on data the model has not seen, ideally your own
recordings.

## 4. Approach C: improve it with your own workers' voices

1. **Consent first (DPDP).** Recordings of workers' voices are personal data. A separate, explicit consent for "use my recordings to improve speech
   recognition", withdrawable, with the purpose and retention stated (`docs/PRIVACY_DPDP.md`). The assessment consent does **not** cover training.
   Store the consent record per recording. Never train on audio from workers who declined or who erased their data (`ErasureService`).
2. **Collect with variety on purpose.** Region and mother tongue, age, gender, phone model, quiet and noisy places, speaking style. Record the
   region/accent label in `labels.csv` so results can be broken down by group.
3. **Transcribe correctly by humans.** Two people per clip for a sample, written in Devanagari, spelling convention agreed in advance (the folded
   metric ignores spelling variants, the strict one does not). Mark unintelligible audio instead of guessing.
4. **Split by speaker, not by clip.** Hold out whole speakers (and whole regions) for testing, otherwise the test flatters the model. Keep a test
   set that is never used for training or tuning.
5. **Measure the starting point** on the held-out set: `python benchmarks/accent_benchmark.py <model> --dir <folder>` where the folder has `NAME.wav`,
   `NAME.txt` and an optional `labels.csv` (`NAME,group`). It prints word and character error per group.
6. **Fine-tune on a rented GPU** with parameter-efficient LoRA (small adapter, lower forgetting risk than full fine-tuning), mixing your
   recordings with public Hindi speech so the model does not forget general Hindi. Typical recipe: Hugging Face `transformers` + `peft`, Whisper
   large-v3(-turbo), learning rate around 1e-5 to 1e-4 for LoRA, a few epochs, evaluate every few hundred steps on the held-out speakers. Merge the
   adapter, convert to CTranslate2 (`ct2-transformers-converter --quantization int8`), record the checksum, and add it behind a setting.
7. **Accept only on evidence.** Suggested rule: the new model must reduce word error on the held-out speakers by a clear margin (for example 20 %
   relative) **in every region group**, not only on average, and must not raise the empty-transcript rate or the hallucination rate on silence.
8. **Keep a human path.** Whatever the model, answers it is unsure about already go to review (low confidence segments are dropped and the answer is
   flagged). Do not auto-reject workers on a transcript alone.

How much data: a few hours of correct transcripts per accent group gives a measurable gain with LoRA; tens of hours is better. Fewer than about
an hour per group is only enough to test, not to train.

## 5. Safety rules that stay on regardless of the model

* The script-hint prompt never contains rubric words (`WHISPER_PROMPT_HI` must stay generic).
* The hallucination filters (silence, low confidence, repetitive output, foreign scripts) stay on unless pilot audio proves they cost more than they
  protect: on clean FLEURS speech they cost about two points of word error but they stop invented text on silence and noise.
* Scoring never depends on one transcript alone: low-confidence answers go to a human, and the keyword scorer tolerates spelling variants.
* Every model change is pinned, benchmarked and recorded in `docs/ML_EVALUATION.md`.
