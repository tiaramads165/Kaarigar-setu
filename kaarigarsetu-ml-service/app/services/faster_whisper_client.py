"""Speech-to-text with faster-whisper (CTranslate2), the low-memory alternative to openai-whisper.

Same Whisper model and the SAME audio cleaning and hallucination filters as WhisperClient (they are imported, not copied), but 8-bit weights and
no PyTorch: roughly half the disk, about a third of the memory and a lot faster on a CPU. Select it with STT_PROVIDER=faster-whisper.

WHISPER_MODEL may be a folder with a CTranslate2 model or a Hugging Face repo id. The default is a community 8-bit conversion of large-v3-turbo,
pinned to one exact revision (a moving model would silently change scores). The weights are a plain binary tensor file (no pickle, no code
execution), and the accuracy is checked against the original with benchmarks/ before it is trusted: see docs/ML_EVALUATION.md.
"""
from __future__ import annotations

import os
import threading

import numpy as np

from app.services.whisper_client import (DEFAULT_PROMPTS, MIN_RMS, decode_wav, normalise_level, plausible, trim_silence)

DEFAULT_MODEL = "Zoont/faster-whisper-large-v3-turbo-int8-ct2"
DEFAULT_REVISION = "846f74797293e5f605d509a6ab5395a1f2f2874e"


class FasterWhisperClient:
    """Lazy like WhisperClient: importing the app never loads or downloads a model; preload() warms it in the background."""

    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()

    def _load(self):
        try:
            from faster_whisper import WhisperModel
        except ImportError as error:
            raise ValueError("faster-whisper is not installed; pip install -r requirements-faster-whisper.txt") from error
        if self._model is None:
            name = os.getenv("WHISPER_MODEL", "")
            if not name or name == "large-v3-turbo":           # the openai-whisper default name means "the standard turbo", use the pinned 8-bit build
                name = DEFAULT_MODEL
            revision = os.getenv("WHISPER_REVISION", DEFAULT_REVISION) if "/" in name and not os.path.isdir(name) else None
            threads = int(os.getenv("WHISPER_THREADS", "0")) or max(1, (os.cpu_count() or 4) - 2)
            self._model = WhisperModel(name, device="cpu", compute_type=os.getenv("WHISPER_COMPUTE", "int8"), cpu_threads=threads, revision=revision)
        return self._model

    def preload(self) -> None:
        with self._lock:
            self._load()

    def transcribe(self, audio: bytes, filename: str, language: str | None) -> dict[str, str]:
        if not audio:
            raise ValueError("audio must not be empty")
        samples = decode_wav(audio)
        if not len(samples) or float(np.sqrt(np.mean(samples ** 2))) < MIN_RMS:
            return {"transcript": "", "language": language or "unknown"}
        samples = trim_silence(normalise_level(samples))
        prompt = os.getenv(f"WHISPER_PROMPT_{(language or '').upper()}", DEFAULT_PROMPTS.get(language or "", "")) or None
        with self._lock:
            model = self._load()
            segments, info = model.transcribe(samples, language=language, beam_size=int(os.getenv("WHISPER_BEAM", "5")),
                                              condition_on_previous_text=False, initial_prompt=prompt, vad_filter=False)
            spoken = [s.text.strip() for s in segments
                      if plausible({"text": s.text, "no_speech_prob": s.no_speech_prob, "avg_logprob": s.avg_logprob, "compression_ratio": s.compression_ratio}, language)]
        return {"transcript": " ".join(spoken), "language": getattr(info, "language", None) or language or "unknown"}
