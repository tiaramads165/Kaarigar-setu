"""Real-speaker Hindi speech-to-text benchmark: how well does a model handle many different real voices?

    python benchmarks/accent_benchmark.py <model folder or Hugging Face id> [--revision SHA] [--n 60] [--data /tmp/accent/hi_in/test/0000.parquet]

Data: Google FLEURS, Hindi, the held-out TEST split (418 read sentences, 209 male and 209 female speakers), downloaded once:
    from huggingface_hub import hf_hub_download
    hf_hub_download("google/fleurs", "hi_in/test/0000.parquet", repo_type="dataset", revision="refs/convert/parquet", local_dir="/tmp/accent")
Reading the file needs pyarrow (pip install pyarrow; it is a benchmark tool, not a runtime dependency).

It runs the SAME path as production: the same level normalisation, silence trimming, beam size, script prompt and hallucination filters as
FasterWhisperClient, so the result is what the app would produce. Clips are sampled evenly across the file (deterministic), so a re-run
compares models on identical audio.

Reports word and character error rates two ways: STRICT (spelling must match; only punctuation and case are ignored) and FOLDED (spelling
variants that do not change the word, like हूँ/हूं and the nukta, are treated as equal, which is how the app's keyword scorer reads text).
FLEURS has no accent labels: this measures "many real, different speakers", not specific accents or dialects, and the speakers read prepared
sentences in good conditions, so real workers on a noisy phone will do worse. Do not read the numbers as pilot accuracy.
"""
from __future__ import annotations

import argparse
import os
import re
import statistics
import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
if os.getenv("PYARROW_PATH"):
    sys.path.insert(0, os.environ["PYARROW_PATH"])

import numpy as np  # noqa: E402

from app.services.rubric_evaluator import fold  # noqa: E402
from app.services.whisper_client import DEFAULT_PROMPTS, MIN_RMS, decode_wav, normalise_level, plausible, trim_silence  # noqa: E402


def load_audio(data: bytes) -> np.ndarray:
    """16-bit PCM WAV the way the app reads it; anything else (the FLEURS files are 32-bit float) through PyAV, to mono float32 at 16 kHz."""
    try:
        return decode_wav(data)
    except ValueError:
        import io
        import av
        with av.open(io.BytesIO(data)) as container:
            resampler = av.AudioResampler(format="flt", layout="mono", rate=16_000)
            chunks = []
            for frame in container.decode(audio=0):
                for out in resampler.resample(frame):
                    chunks.append(out.to_ndarray().reshape(-1))
        return np.concatenate(chunks).astype(np.float32) if chunks else np.zeros(0, dtype=np.float32)


def strict_norm(text: str) -> str:
    """NFC, lower case, every punctuation or symbol character (including the danda) becomes a space. Vowel signs are KEPT."""
    text = unicodedata.normalize("NFC", text).lower()
    text = "".join(" " if unicodedata.category(ch)[0] in "PS" else ch for ch in text)
    return re.sub(r"\s+", " ", text).strip()


def edit_distance(a: list, b: list) -> int:
    previous = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        row = [i]
        for j, y in enumerate(b, 1):
            row.append(min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (x != y)))
        previous = row
    return previous[-1]


def rates(pairs: list[tuple[str, str]]) -> tuple[float, float]:
    """(word error rate, character error rate) in percent over all pairs."""
    word_errors = word_total = char_errors = char_total = 0
    for ref, hyp in pairs:
        r, h = ref.split(), hyp.split()
        word_errors += edit_distance(r, h); word_total += len(r)
        rc, hc = list(ref.replace(" ", "")), list(hyp.replace(" ", ""))
        char_errors += edit_distance(rc, hc); char_total += len(rc)
    return 100 * word_errors / max(1, word_total), 100 * char_errors / max(1, char_total)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("model")
    parser.add_argument("--revision", default=None)
    parser.add_argument("--n", type=int, default=60)
    parser.add_argument("--data", default="/tmp/accent/hi_in/test/0000.parquet")
    parser.add_argument("--dir", default=None, help="your own recordings instead of FLEURS: a folder of NAME.wav + NAME.txt (the correct transcript) pairs, "
                        "and an optional labels.csv with lines 'NAME,group' (accent, region, device...) for a per-group breakdown")
    parser.add_argument("--compute", default="int8")
    parser.add_argument("--beam", type=int, default=5)
    parser.add_argument("--no-prompt", action="store_true", help="experiment: drop the Devanagari script hint")
    parser.add_argument("--no-filter", action="store_true", help="experiment: keep every segment the model produces (no hallucination filters)")
    parser.add_argument("--show-empty", action="store_true", help="print the clips that came back empty and why")
    args = parser.parse_args()

    from faster_whisper import WhisperModel

    if args.dir:
        folder = Path(args.dir)
        labels = {}
        if (folder / "labels.csv").exists():
            labels = dict(line.strip().split(",", 1) for line in (folder / "labels.csv").read_text(encoding="utf-8").splitlines() if "," in line)
        rows = [{"audio": {"bytes": wav.read_bytes()}, "transcription": wav.with_suffix(".txt").read_text(encoding="utf-8"), "gender": 0,
                 "group": labels.get(wav.stem, "all")} for wav in sorted(folder.glob("*.wav")) if wav.with_suffix(".txt").exists()]
        total = len(rows)
    else:
        import pyarrow.parquet as pq
        table = pq.read_table(args.data)
        total = table.num_rows
        indices = sorted({int(i * total / args.n) for i in range(args.n)})
        rows = table.take(indices).to_pylist()
        for row in rows:
            row["group"] = "female" if int(row["gender"]) else "male"
    started = time.time()
    model = WhisperModel(args.model, device="cpu", compute_type=args.compute, cpu_threads=max(1, (os.cpu_count() or 4) - 2), revision=args.revision)
    print(f"model loaded in {time.time() - started:.0f}s; {len(rows)} clips (every {total / len(rows):.1f}th of {total})", flush=True)

    prompt = None if args.no_prompt else DEFAULT_PROMPTS["hi"]
    strict_pairs, folded_pairs, by_group, seconds, empties = [], [], {}, [], 0
    for k, row in enumerate(rows, 1):
        samples = load_audio(row["audio"]["bytes"])
        t0 = time.time()
        if float(np.sqrt(np.mean(samples ** 2))) < MIN_RMS:
            hyp = ""
        else:
            segments, _ = model.transcribe(trim_silence(normalise_level(samples)), language="hi", beam_size=args.beam,
                                           condition_on_previous_text=False, initial_prompt=prompt, vad_filter=False)
            segments = list(segments)
            keep = lambda s: args.no_filter or plausible({"text": s.text, "no_speech_prob": s.no_speech_prob, "avg_logprob": s.avg_logprob, "compression_ratio": s.compression_ratio}, "hi")
            hyp = " ".join(s.text.strip() for s in segments if keep(s))
            if args.show_empty and not hyp.strip():
                print(f"  EMPTY clip {k}: model produced {len(segments)} segment(s): " + "; ".join(f"[no_speech {s.no_speech_prob:.2f} logprob {s.avg_logprob:.2f} compr {s.compression_ratio:.2f}] {s.text.strip()[:40]}" for s in segments), flush=True)
        seconds.append(time.time() - t0)
        empties += not hyp.strip()
        ref_s, hyp_s = strict_norm(row["transcription"]), strict_norm(hyp)
        strict_pairs.append((ref_s, hyp_s))
        folded_pairs.append((fold(ref_s), fold(hyp_s)))
        by_group.setdefault(row["group"], []).append((fold(ref_s), fold(hyp_s)))
        if k % 10 == 0:
            print(f"  {k}/{len(rows)} clips, running folded WER {rates(folded_pairs)[0]:.1f}%", flush=True)

    sw, sc = rates(strict_pairs)
    fw, fc = rates(folded_pairs)
    print(f"\nMODEL {args.model} ({args.compute})" + (" [no prompt]" if args.no_prompt else "") + (" [no filter]" if args.no_filter else ""))
    print(f"STRICT : WER {sw:5.1f}%   CER {sc:5.1f}%")
    print(f"FOLDED : WER {fw:5.1f}%   CER {fc:5.1f}%")
    if len(by_group) > 1:
        print("by group (folded WER / CER, clips): " + "; ".join(f"{g} {rates(p)[0]:.1f}% / {rates(p)[1]:.1f}% ({len(p)})" for g, p in sorted(by_group.items())))
    print(f"empty transcripts {empties}/{len(rows)} | time per clip median {statistics.median(seconds):.1f}s, mean {statistics.mean(seconds):.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
