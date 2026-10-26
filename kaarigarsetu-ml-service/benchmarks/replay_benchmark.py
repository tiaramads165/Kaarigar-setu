"""Calibration of the replay-voice checks on SYNTHESISED speech (macOS only: uses the built-in `say` voices and afconvert). It measures what the thresholds in
docs/REPLAY_DETECTION.md rest on:

  1. how many acoustic landmarks two DIFFERENT recordings share by chance (REPLAY_MIN_MATCHES must sit far above this);
  2. how many a COPY of one recording keeps after what a replay does to it (volume, noise, band, sample rate, trim, clipping, room, speaker-to-microphone, speed);
  3. with --stt (needs the faster-whisper model): how many word trigrams a copy keeps after speech recognition, i.e. what the text check (REPLAY_TEXT_MIN_SHARE) sees.

    cd kaarigarsetu-ml-service && .venv/bin/python benchmarks/replay_benchmark.py [--stt] [--keep DIR]

SYNTHETIC speech from two voices: it ranks the checks and sets the margins, it does not predict the rates on real workers' phone recordings (docs/REPLAY_DETECTION.md, limits)."""
from __future__ import annotations

import argparse
import io
import itertools
import re
import subprocess
import sys
import tempfile
import unicodedata
import wave
from pathlib import Path

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from app.services.audio_fingerprint import aligned_matches, fingerprint, read_wav  # noqa: E402

RATE = 16000
MIN_MATCHES, MIN_LANDMARKS = 25, 100            # REPLAY_MIN_MATCHES, REPLAY_MIN_LANDMARKS
MIN_SHINGLES, MIN_COMMON, MIN_SHARE = 14, 8, 0.5  # REPLAY_TEXT_MIN_SHINGLES, _MIN_COMMON, _MIN_SHARE

CLIPS = [
    ("h1", "Lekha", "मैंने इलेक्ट्रीशियन का काम अपने सीनियर से सीखा है और उन्होंने दो साल तक मुझे ट्रेनिंग दी है। मैं हर काम से पहले मुख्य स्विच बंद करता हूं और टेस्टर से जांच करता हूं।"),
    ("h2", "Lekha", "बिजली मीटर घर में आने वाली वोल्टेज और करंट का मापन करके कुल खर्च हुई बिजली की खपत बताता है और उसी के आधार पर महीने का बिल बनता है।"),
    ("h3", "Lekha", "घर की वायरिंग में ताँबे का तार इस्तेमाल होता है और एल्यूमिनियम का तार बाहर की लाइन में, क्योंकि ताँबा बिजली को बेहतर चलाता है और कम गर्म होता है।"),
    ("h4", "Lekha", "सर्किट ब्रेकर तब ट्रिप करता है जब लोड बहुत ज्यादा हो जाता है और इस तरह वह वायरिंग को आग लगने से बचाता है इसलिए सही क्षमता का ब्रेकर लगाना जरूरी है।"),
    ("h5", "Lekha", "एसी करंट अपनी दिशा बदलता रहता है और डीसी एक ही दिशा में बहता है, जैसे बैटरी में, और घरों में हमेशा एसी करंट ही आता है।"),
    ("e1", "Samantha", "I always switch off the main supply first, then test the wire with a tester before I start any wiring work, so that nobody gets hurt on the job."),
    ("e2", "Samantha", "Copper wire carries current well and does not heat up quickly, so for house wiring I use copper wire and I always fit a proper earthing connection."),
    ("e3", "Samantha", "A circuit breaker trips when the load becomes too high and that protects the wiring from catching fire, so choosing the right rating of breaker matters."),
]


def make_clips(folder: Path) -> dict[str, np.ndarray]:
    out = {}
    for name, voice, text in CLIPS:
        aiff, wav = folder / f"{name}.aiff", folder / f"{name}.wav"
        subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", f"LEI16@{RATE}", "-c", "1", str(aiff), str(wav)], check=True)
        out[name] = read_wav(wav.read_bytes())[0]
    return out


def to_wav(x: np.ndarray, rate: int = RATE) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    return buffer.getvalue()


rng = np.random.default_rng(1)


def noise(x, snr_db):
    return x + rng.standard_normal(len(x)) * np.sqrt(np.mean(x ** 2) / 10 ** (snr_db / 10))


def bandpass(x, lo=300, hi=3400):
    return signal.sosfilt(signal.butter(4, [lo, hi], btype="band", fs=RATE, output="sos"), x)


def room(x, rt60=0.35):
    n = int(RATE * rt60)
    impulse = rng.standard_normal(n) * np.exp(-6.9 * np.arange(n) / n)
    impulse[0] = 1.0
    y = signal.fftconvolve(x, impulse / np.sqrt(np.sum(impulse ** 2)))[: len(x)]
    return y * np.max(np.abs(x)) / (np.max(np.abs(y)) + 1e-9)


def speaker_to_mic(x):
    return noise(room(np.tanh(2.5 * bandpass(x, 350, 3300))), 18)


VARIANTS = {
    "identical": lambda x: x,
    "volume x0.2": lambda x: x * 0.2,
    "noise 20 dB": lambda x: noise(x, 20),
    "noise 10 dB": lambda x: noise(x, 10),
    "noise 5 dB": lambda x: noise(x, 5),
    "band-pass 300-3400 Hz": bandpass,
    "16k -> 8k -> 16k": lambda x: signal.resample_poly(signal.resample_poly(x, 1, 2), 2, 1),
    "trim first 0.6 s + last 0.5 s": lambda x: x[int(0.6 * RATE): -int(0.5 * RATE)],
    "hard clipping": lambda x: np.clip(x * 4, -0.5, 0.5),
    "room reverb RT60 0.4 s": lambda x: room(x, 0.4),
    "speaker -> microphone": speaker_to_mic,
    "speed +3 %": lambda x: signal.resample_poly(x, 100, 103),
    "speed +8 %": lambda x: signal.resample_poly(x, 100, 108),
    "speed -5 %": lambda x: signal.resample_poly(x, 100, 95),
}


def trigrams(text: str) -> set[tuple[str, ...]]:
    """The same reduction as TextShingles.java: annotations and numbers dropped, letters (with marks) kept, runs of three words."""
    text = re.sub(r"\[[^\]]{0,80}]", " ", unicodedata.normalize("NFC", text).lower())
    words = [w for w in re.findall(r"[^\W\d_]+(?:[ऀ-ॿ]*)|[ऀ-ॣ०-ॿ]+", text) if not w.isdigit()]
    return {tuple(words[i:i + 3]) for i in range(len(words) - 2)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stt", action="store_true", help="also run the text check through faster-whisper (large-v3-turbo; slow)")
    parser.add_argument("--keep", help="folder to keep the synthesised clips in (otherwise a temporary one)")
    arguments = parser.parse_args()
    folder = Path(arguments.keep) if arguments.keep else Path(tempfile.mkdtemp(prefix="ks-replay-"))
    folder.mkdir(parents=True, exist_ok=True)
    clips = make_clips(folder)
    prints = {name: fingerprint(to_wav(x)) for name, x in clips.items()}
    print(f"{len(clips)} synthesised clips: " + ", ".join(f"{n} {p.seconds:.1f}s/{p.landmarks} landmarks" for n, p in prints.items()))

    pairs = sorted(((aligned_matches(prints[a], prints[b])[0], a, b) for a, b in itertools.combinations(prints, 2)), reverse=True)
    print(f"\nDifferent recordings (all {len(pairs)} pairs): most landmarks shared at one time shift = {pairs[0][0]}, median {pairs[len(pairs) // 2][0]}   (threshold {MIN_MATCHES})")
    print("  highest pairs: " + ", ".join(f"{a}/{b} {m}" for m, a, b in pairs[:4]))
    print("  (a synthetic voice renders a repeated phrase identically, so clips that share a phrase share its landmarks; a person never says anything identically twice,"
          " so real pairs sit lower: on clips with no shared phrases the most was 4)")

    print("\nThe same recording after a replay's changes (landmarks of the copy found in the original at one time shift; mean / min over the clips):")
    print(f"  {'change':<32}{'matches mean':>13}{'min':>6}{'share mean':>12}{'min':>6}   caught at {MIN_MATCHES}")
    for label, change in VARIANTS.items():
        rows = []
        for name, x in clips.items():
            copy = fingerprint(to_wav(change(x)))
            matches, _ = aligned_matches(copy, prints[name])
            rows.append((matches, matches / max(1, min(copy.landmarks, prints[name].landmarks)), copy.landmarks >= MIN_LANDMARKS))
        caught = sum(1 for m, _, usable in rows if usable and m >= MIN_MATCHES)
        print(f"  {label:<32}{np.mean([r[0] for r in rows]):>13.1f}{min(r[0] for r in rows):>6}{np.mean([r[1] for r in rows]):>12.2f}{min(r[1] for r in rows):>6.2f}   {caught}/{len(rows)}")

    if arguments.stt:
        from app.services.faster_whisper_client import FasterWhisperClient
        client = FasterWhisperClient()
        languages = {name: ("hi" if name.startswith("h") else "en") for name in clips}
        heard = {(name, label): client.transcribe(to_wav(change(x)), "a.wav", languages[name])["transcript"] for name, x in clips.items() for label, change in VARIANTS.items()}
        print(f"\nWords: share of the original's word trigrams that the transcript of the changed copy still has (REPLAY_TEXT_MIN_SHARE {MIN_SHARE}, min common {MIN_COMMON}, min trigrams {MIN_SHINGLES})")
        print(f"  {'change':<32}{'share mean':>11}{'min':>6}   flagged by the text check")
        for label in VARIANTS:
            shares, flagged = [], 0
            for name in clips:
                a, b = trigrams(heard[(name, "identical")]), trigrams(heard[(name, label)])
                smaller = min(len(a), len(b))
                share = len(a & b) / smaller if smaller else 0.0
                shares.append(share)
                flagged += smaller >= MIN_SHINGLES and len(a & b) >= MIN_COMMON and share >= MIN_SHARE
            print(f"  {label:<32}{np.mean(shares):>11.2f}{min(shares):>6.2f}   {flagged}/{len(clips)}")
        cross = [len(trigrams(heard[(a, 'identical')]) & trigrams(heard[(b, 'identical')])) for a, b in itertools.combinations(clips, 2)]
        print(f"  different answers: most trigrams shared by any two of the {len(clips)} clips = {max(cross)}")


if __name__ == "__main__":
    main()
