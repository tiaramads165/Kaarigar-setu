import os

import pytest

os.environ.setdefault("APP_ENV", "test")


@pytest.fixture(autouse=True)
def clean_env(monkeypatch):
    for name in ("INTERNAL_API_TOKEN", "LLM_API_KEY", "EVALUATOR", "STT_PROVIDER", "MAX_AUDIO_BYTES"):
        monkeypatch.delenv(name, raising=False)
