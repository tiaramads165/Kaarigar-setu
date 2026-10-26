"""CER of Whisper on the Hindi benchmark. usage: run.py <model> ; compares the OLD decoding (greedy, no hint, no levelling,
no filtering) with the NEW pipeline (WhisperClient)."""
import sys, re, time, unicodedata, wave, numpy as np
sys.path.insert(0, ".")
import whisper
from app.services.whisper_client import WhisperClient, decode_wav
from app.services.rubric_evaluator import fold
name = sys.argv[1]
refs = open("/tmp/ks-speech/bench/refs.txt", encoding="utf-8").read().split("\n")
def norm(t): return re.sub(r"[\s।,.?!'\"-]", "", fold(t))
def cer(r, h):
    r, h = norm(r), norm(h); d = list(range(len(h) + 1))
    for i, a in enumerate(r, 1):
        p, d[0] = d[0], i
        for j, b in enumerate(h, 1): p, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, p + (a != b))
    return d[len(h)] / max(1, len(r))
model = whisper.load_model(name)
client = WhisperClient(); client._model = model
res = {}
for cond in ("clean", "noisy"):
    for mode in ("old", "new"):
        errs, secs = [], 0
        for i, ref in enumerate(refs):
            audio = open(f"/tmp/ks-speech/bench/{cond}{i}.wav", "rb").read()
            t = time.time()
            if mode == "old": hyp = model.transcribe(decode_wav(audio), language="hi", fp16=False, condition_on_previous_text=False)["text"]
            else: hyp = client.transcribe(audio, "a.wav", "hi")["transcript"]
            secs += time.time() - t; errs.append(cer(ref, hyp))
            if cond == "noisy" and i == 0: print(f"   [{name} {mode} noisy#0] {hyp}")
        res[(cond, mode)] = (100 * np.mean(errs), secs / len(refs))
print(f"\nmodel={name}   character error rate (lower is better), mean seconds per answer")
for (cond, mode), (e, s) in res.items(): print(f"  {cond:6s} {mode:4s}  CER {e:5.1f}%   {s:4.1f}s")
