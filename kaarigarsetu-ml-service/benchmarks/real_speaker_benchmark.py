"""Accuracy of speech-to-text engines on REAL Hindi speakers (Google FLEURS, hi_in), not synthetic voices.

    python benchmarks/real_speaker_benchmark.py <fleurs_parquet> <engine> [--n 30]
    engines:  openai:<model>      e.g. openai:large-v3-turbo   (the service's WhisperClient: levelling, trimming, filters, hint)
              hf:<local_dir>      a Hindi fine-tuned Whisper checkpoint downloaded to a folder (transformers)

FLEURS is read speech: many different speakers and accents, but Wikipedia-style sentences, not trade talk. It ranks engines on accent
robustness; it does not predict accuracy on workers talking about their trade. Prints WER and CER (lower is better) and seconds per clip.
"""
import argparse, io, re, sys, time
import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

sys.path.insert(0, ".")
from app.services.rubric_evaluator import fold

SEED = 7


def norm(text: str) -> str:
    text = re.sub(r"[।॥.,?!;:\"'()\[\]‘’“”-]", " ", fold(text))
    return re.sub(r"\s+", " ", text).strip()


def edit_distance(a, b):
    d = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, d[0] = d[0], i
        for j, y in enumerate(b, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (x != y))
    return d[len(b)]


def load_clips(path, n):
    table = pq.read_table(path).to_pandas() if hasattr(pq.read_table(path), "to_pandas") else None
    rows = table.sample(n=min(n, len(table)), random_state=SEED)
    clips = []
    for _, r in rows.iterrows():
        audio, rate = sf.read(io.BytesIO(r["audio"]["bytes"]), dtype="float32")
        if audio.ndim > 1: audio = audio.mean(axis=1)
        if rate != 16000: audio = np.interp(np.linspace(0, len(audio) - 1, int(len(audio) * 16000 / rate)), np.arange(len(audio)), audio).astype(np.float32)
        clips.append((audio, r.get("raw_transcription") or r["transcription"], r.get("gender", "?")))
    return clips


def engine(spec):
    kind, _, arg = spec.partition(":")
    if kind == "openai":
        import os, wave
        from app.services.whisper_client import WhisperClient
        os.environ["WHISPER_MODEL"] = arg
        client = WhisperClient()
        def run(audio):
            buf = io.BytesIO()
            with wave.open(buf, "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes((np.clip(audio, -1, 1) * 32767).astype("<i2").tobytes())
            return client.transcribe(buf.getvalue(), "a.wav", "hi")["transcript"]
        return run
    if kind == "hf":
        import torch
        from transformers import WhisperForConditionalGeneration, WhisperProcessor
        processor = WhisperProcessor.from_pretrained(arg)
        model = WhisperForConditionalGeneration.from_pretrained(arg, torch_dtype=torch.float32).eval()
        def run(audio):
            features = processor(audio, sampling_rate=16000, return_tensors="pt").input_features
            with torch.no_grad():
                ids = model.generate(features, language="hi", task="transcribe", num_beams=5, max_new_tokens=220)
            return processor.batch_decode(ids, skip_special_tokens=True)[0]
        return run
    raise SystemExit("engine must be openai:<model> or hf:<dir>")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("parquet"); ap.add_argument("engine"); ap.add_argument("--n", type=int, default=30)
    a = ap.parse_args()
    clips, run = load_clips(a.parquet, a.n), engine(a.engine)
    run(clips[0][0][:16000 * 3])                                   # warm-up (model load) outside the timing
    werr = wtot = cerr = ctot = 0; secs = 0.0; worst = []
    for audio, ref, _ in clips:
        t = time.time(); hyp = run(audio); secs += time.time() - t
        r, h = norm(ref), norm(hyp)
        we, ce = edit_distance(r.split(), h.split()), edit_distance(list(r.replace(" ", "")), list(h.replace(" ", "")))
        werr += we; wtot += len(r.split()); cerr += ce; ctot += len(r.replace(" ", ""))
        worst.append((ce / max(1, len(r.replace(" ", ""))), ref, hyp))
    print(f"{a.engine:38s} clips={len(clips)}  WER {100*werr/wtot:5.1f}%  CER {100*cerr/ctot:5.1f}%  {secs/len(clips):5.1f}s/clip")
    if "--show" in sys.argv:
        for c, ref, hyp in sorted(worst, reverse=True)[:3]: print(f"  CER {c:.2f}\n   ref: {ref}\n   hyp: {hyp}")
