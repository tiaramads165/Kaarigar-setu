from __future__ import annotations

import io
import os
import re
import threading
import wave

import numpy as np

WHISPER_RATE = 16_000
SILENCE_THRESHOLD = 0.6      # segments the model itself thinks are not speech are dropped (Whisper otherwise invents text for silence)
MIN_AVG_LOGPROB = -1.3       # segments the model itself is very unsure about are junk more often than speech
MAX_COMPRESSION_RATIO = 2.4  # highly repetitive output ("la la la la") is a classic Whisper hallucination
MIN_RMS = 0.002              # a recording this quiet has no speech in it (below ~ -54 dBFS)

# Style/script hint ONLY: it makes Whisper write Hindi in Devanagari. It deliberately contains no trade words and no rubric
# vocabulary: priming the model with answer words would make it "hear" them and inflate scores. Override with WHISPER_PROMPT_HI.
DEFAULT_PROMPTS = {"hi": "यह एक कारीगर का बोला हुआ जवाब है।"}

# characters that never belong in a Hindi/English answer: their presence means the model hallucinated
_FOREIGN = re.compile(r"[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af\u0400-\u04ff\u0600-\u06ff]")


def decode_wav(audio: bytes) -> np.ndarray:
    """16-bit PCM WAV -> mono float32 at 16 kHz, without ffmpeg. The Java side has already validated the header; this
    re-checks it because this service is also reachable on its own."""
    try:
        with wave.open(io.BytesIO(audio), "rb") as reader:
            channels, width, rate, frames = reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getnframes()
            if width != 2 or not 1 <= channels <= 2 or rate < 8_000:
                raise ValueError("audio must be 16-bit PCM WAV")
            raw = reader.readframes(frames)
    except (wave.Error, EOFError) as error:
        raise ValueError("audio is not a valid WAV file") from error
    samples = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
    if channels == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    if rate != WHISPER_RATE and len(samples):
        target = int(len(samples) * WHISPER_RATE / rate)
        samples = np.interp(np.linspace(0, len(samples) - 1, target), np.arange(len(samples)), samples).astype(np.float32)
    return samples


def normalise_level(samples: np.ndarray) -> np.ndarray:
    """Phone microphones are often quiet: remove DC offset and raise the peak to ~0.9 (gain capped at 30x so noise is not blown up)."""
    if not len(samples):
        return samples
    samples = samples - float(samples.mean())
    peak = float(np.abs(samples).max())
    if peak < 1e-4:
        return samples
    return np.clip(samples * min(0.9 / peak, 30.0), -1.0, 1.0).astype(np.float32)


def trim_silence(samples: np.ndarray, rate: int = WHISPER_RATE, pad_seconds: float = 0.3) -> np.ndarray:
    """Cut leading and trailing silence (keeping a short pad). Whisper invents text most often in long silent stretches."""
    frame = int(rate * 0.03)
    if len(samples) < frame * 4:
        return samples
    energy = np.sqrt(np.mean(samples[: len(samples) // frame * frame].reshape(-1, frame) ** 2, axis=1))
    voiced = np.where(energy > max(0.01, 0.1 * float(energy.max())))[0]
    if not len(voiced):
        return samples
    pad = int(pad_seconds * rate)
    return samples[max(0, voiced[0] * frame - pad): min(len(samples), (voiced[-1] + 1) * frame + pad)]


def plausible(segment: dict, language: str | None) -> bool:
    """Keep only segments that look like real speech in the expected language."""
    text = segment.get("text", "").strip()
    return bool(text
                and segment.get("no_speech_prob", 0.0) <= SILENCE_THRESHOLD
                and segment.get("avg_logprob", 0.0) >= MIN_AVG_LOGPROB
                and segment.get("compression_ratio", 0.0) <= MAX_COMPRESSION_RATIO
                and not (language in ("hi", "en", None) and _FOREIGN.search(text)))


class WhisperClient:
    """Lazy wrapper around openai-whisper so importing the app never downloads a model. preload() warms it in the background."""

    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()      # the model is not thread-safe, and two threads must not both load it

    def _load(self):
        try:
            import whisper
        except ImportError as error:
            raise ValueError("Whisper is not installed; install openai-whisper to enable audio transcription") from error
        if self._model is None:
            self._model = whisper.load_model(os.getenv("WHISPER_MODEL", "large-v3-turbo"))
        return self._model

    def preload(self) -> None:
        with self._lock:
            self._load()

    def transcribe(self, audio: bytes, filename: str, language: str | None) -> dict[str, str]:
        if not audio:
            raise ValueError("audio must not be empty")
        samples = decode_wav(audio)
        if not len(samples) or float(np.sqrt(np.mean(samples ** 2))) < MIN_RMS:
            return {"transcript": "", "language": language or "unknown"}      # silence: do not give the model a chance to invent text
        samples = trim_silence(normalise_level(samples))
        prompt = os.getenv(f"WHISPER_PROMPT_{(language or '').upper()}", DEFAULT_PROMPTS.get(language or "", "")) or None
        with self._lock:
            model = self._load()
            result = model.transcribe(samples, language=language, fp16=False, condition_on_previous_text=False,
                                      beam_size=int(os.getenv("WHISPER_BEAM", "5")), initial_prompt=prompt)
        spoken = [s["text"].strip() for s in result.get("segments", []) if plausible(s, language)]
        return {"transcript": " ".join(spoken), "language": result.get("language") or language or "unknown"}
