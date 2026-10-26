"""The lightweight engine (STT_PROVIDER=faster-whisper). The model itself is replaced by a stand-in so nothing is downloaded."""
import io
import sys
import types
import wave

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.routes import transcribe as transcribe_route
from app.services.faster_whisper_client import DEFAULT_MODEL, DEFAULT_REVISION, FasterWhisperClient

client = TestClient(app)


def tone_wav(seconds=1.0, rate=16_000, amplitude=0.3):
    t = np.arange(int(rate * seconds)) / rate
    pcm = (amplitude * np.sin(2 * np.pi * 220 * t) * 32767).astype("<i2").tobytes()
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1); writer.setsampwidth(2); writer.setframerate(rate); writer.writeframes(pcm)
    return buffer.getvalue()


def segment(text, no_speech=0.0, logprob=-0.2, compression=1.2):
    return types.SimpleNamespace(text=text, no_speech_prob=no_speech, avg_logprob=logprob, compression_ratio=compression)


def install_fake(monkeypatch, segments, created):
    class FakeModel:
        def __init__(self, name, **options):
            created.append((name, options))

        def transcribe(self, samples, **options):
            created.append(("transcribe", options, len(samples)))
            return iter(segments), types.SimpleNamespace(language="hi")

    monkeypatch.setitem(sys.modules, "faster_whisper", types.SimpleNamespace(WhisperModel=FakeModel))


def test_real_speech_is_returned_and_hallucinated_or_foreign_segments_are_dropped(monkeypatch):
    created = []
    install_fake(monkeypatch, [segment("मैं पेचकस इस्तेमाल करता हूं"), segment("ऊंची आवाज", no_speech=0.9), segment("字幕提供"), segment("la la la", compression=3.0)], created)
    result = FasterWhisperClient().transcribe(tone_wav(), "a.wav", "hi")
    assert result == {"transcript": "मैं पेचकस इस्तेमाल करता हूं", "language": "hi"}


def test_silence_never_reaches_the_model(monkeypatch):
    created = []
    install_fake(monkeypatch, [segment("invented text")], created)
    assert FasterWhisperClient().transcribe(tone_wav(amplitude=0.0), "a.wav", "hi")["transcript"] == ""
    assert created == []                      # not even loaded


def test_the_default_model_is_the_pinned_8_bit_build_and_decoding_uses_the_same_settings_as_the_original_engine(monkeypatch):
    created = []
    install_fake(monkeypatch, [segment("ठीक")], created)
    monkeypatch.delenv("WHISPER_MODEL", raising=False)
    FasterWhisperClient().transcribe(tone_wav(), "a.wav", "hi")
    (name, options), (_, decode, _) = created
    assert name == DEFAULT_MODEL and options["revision"] == DEFAULT_REVISION and options["compute_type"] == "int8" and options["device"] == "cpu"
    assert decode["language"] == "hi" and decode["beam_size"] == 5 and decode["condition_on_previous_text"] is False
    assert "कारीगर" in decode["initial_prompt"] and "पेचकस" not in decode["initial_prompt"]      # script hint only, no rubric words


def test_the_openai_default_name_maps_to_the_pinned_build_and_a_local_folder_is_used_as_is(monkeypatch, tmp_path):
    created = []
    install_fake(monkeypatch, [segment("ठीक")], created)
    monkeypatch.setenv("WHISPER_MODEL", "large-v3-turbo")
    FasterWhisperClient().transcribe(tone_wav(), "a.wav", "hi")
    assert created[0][0] == DEFAULT_MODEL
    created.clear()
    monkeypatch.setenv("WHISPER_MODEL", str(tmp_path))
    FasterWhisperClient().transcribe(tone_wav(), "a.wav", "hi")
    assert created[0][0] == str(tmp_path) and created[0][1]["revision"] is None


def test_a_missing_library_is_a_clear_error_not_a_crash(monkeypatch):
    monkeypatch.setitem(sys.modules, "faster_whisper", None)
    with pytest.raises(ValueError, match="faster-whisper is not installed"):
        FasterWhisperClient().transcribe(tone_wav(), "a.wav", "hi")


def test_the_route_uses_this_engine_only_when_selected(monkeypatch):
    calls = []
    monkeypatch.setattr(transcribe_route.faster_whisper_client, "transcribe", lambda audio, name, language: calls.append("fast") or {"transcript": "x", "language": "hi"})
    monkeypatch.setattr(transcribe_route.whisper_client, "transcribe", lambda audio, name, language: calls.append("openai") or {"transcript": "y", "language": "hi"})
    files = {"audio": ("a.wav", tone_wav(), "audio/wav")}
    monkeypatch.setenv("STT_PROVIDER", "faster-whisper")
    assert client.post("/internal/ml/transcribe", files=files).json()["transcript"] == "x"
    monkeypatch.setenv("STT_PROVIDER", "whisper")
    assert client.post("/internal/ml/transcribe", files=files).json()["transcript"] == "y"
    assert calls == ["fast", "openai"]
