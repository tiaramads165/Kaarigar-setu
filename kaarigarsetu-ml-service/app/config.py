"""Environment-driven settings. Secrets (API keys, tokens) only ever come from the environment."""
from __future__ import annotations

import os


def env(name: str, default: str = "") -> str:
    value = os.getenv(name)
    return default if value is None or not value.strip() else value.strip()


def is_production() -> bool:
    return env("APP_ENV", "dev").lower() in {"prod", "production"}


def max_audio_bytes() -> int:
    return int(env("MAX_AUDIO_BYTES", str(2 * 1024 * 1024)))


def evaluator_mode() -> str:
    """'llm' when an API key is configured, otherwise the deterministic keyword evaluator."""
    mode = env("EVALUATOR", "").lower()
    if mode in {"llm", "keyword"}:
        return mode
    return "llm" if env("LLM_API_KEY") else "keyword"


def stt_provider() -> str:
    """'whisper' (default, openai-whisper + PyTorch), 'faster-whisper' (same model in 8-bit, much lighter) or 'stub' (dev/test only; refused when APP_ENV=prod)."""
    provider = env("STT_PROVIDER", "whisper").lower()
    if provider == "stub" and is_production():
        raise RuntimeError("STT_PROVIDER=stub is not allowed when APP_ENV=prod")
    return provider
