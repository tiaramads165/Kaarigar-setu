import io
import sys
import types
import wave

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.config import stt_provider
from app.main import app

client = TestClient(app)


def upload(content=b"RIFFxxxx", **params):
    return client.post("/transcribe", files={"audio": ("a.wav", content, "audio/wav")}, params=params)


def test_empty_audio_is_422():
    assert upload(b"").status_code == 422


def test_oversized_audio_is_413(monkeypatch):
    monkeypatch.setenv("STT_PROVIDER", "stub")
    monkeypatch.setenv("MAX_AUDIO_BYTES", "1000")
    assert upload(b"x" * 1001).status_code == 413


def wav_bytes(seconds=1.0, rate=16_000, channels=1, width=2):
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(channels)
        writer.setsampwidth(width)
        writer.setframerate(rate)
        writer.writeframes((np.sin(np.linspace(0, 440, int(rate * seconds))) * 8000).astype("<i2").tobytes() * channels if width == 2 else b"\x80" * int(rate * seconds))
    return buffer.getvalue()


def test_stub_provider_is_labelled_so_it_cannot_pass_for_what_a_person_said(monkeypatch):
    from app.services.stub_transcriber import LABEL
    monkeypatch.setenv("STT_PROVIDER", "stub")
    body = upload(language="hi").json()
    assert body["language"] == "hi" and body["transcript"].startswith(LABEL)


def test_the_stub_provider_is_refused_in_production(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    monkeypatch.setenv("STT_PROVIDER", "stub")
    with pytest.raises(RuntimeError):
        stt_provider()


def test_without_whisper_installed_transcription_fails_cleanly(monkeypatch):
    monkeypatch.setitem(sys.modules, "whisper", None)           # makes "import whisper" raise ImportError
    from app.services.whisper_client import WhisperClient
    monkeypatch.setattr("app.routes.transcribe.whisper_client", WhisperClient())
    response = upload(wav_bytes())
    assert response.status_code == 422
    assert "Whisper is not installed" in response.json()["detail"]


def test_a_non_wav_upload_is_a_clean_422_not_a_crash(monkeypatch):
    monkeypatch.setitem(sys.modules, "whisper", types.SimpleNamespace(load_model=lambda name: pytest.fail("must not load a model for bad audio")))
    for junk in (b"RIFFxxxx", b"not audio at all", wav_bytes(width=1)):
        response = upload(junk)
        assert response.status_code == 422, junk[:10]


# ---- the real engine, with a fake model so no download is needed ------------------------------------------------------

class FakeModel:
    def __init__(self, segments):
        self.segments, self.calls = segments, []

    def transcribe(self, samples, **options):
        self.calls.append((samples, options))
        return {"segments": self.segments, "language": options.get("language")}


def install_fake_whisper(monkeypatch, segments):
    model, loads = FakeModel(segments), []
    monkeypatch.setitem(sys.modules, "whisper", types.SimpleNamespace(load_model=lambda name: loads.append(name) or model))
    from app.services.whisper_client import WhisperClient
    return WhisperClient(), model, loads


def test_whisper_engine_returns_what_was_heard_and_loads_the_model_once(monkeypatch):
    client_, model, loads = install_fake_whisper(monkeypatch, [{"text": " मैं पेचकस इस्तेमाल करता हूं ", "no_speech_prob": 0.01},
                                                               {"text": " और टेस्टर भी।", "no_speech_prob": 0.02}])
    first = client_.transcribe(wav_bytes(2.0), "a.wav", "hi")
    client_.transcribe(wav_bytes(1.0), "a.wav", "hi")
    assert first == {"transcript": "मैं पेचकस इस्तेमाल करता हूं और टेस्टर भी।", "language": "hi"}
    assert loads == ["large-v3-turbo"]                                     # default model, loaded once
    samples, options = model.calls[0]
    assert samples.dtype == np.float32 and len(samples) == 32_000 and options["language"] == "hi" and options["fp16"] is False


def test_whisper_engine_drops_segments_the_model_thinks_are_silence(monkeypatch):
    client_, _, _ = install_fake_whisper(monkeypatch, [{"text": "Thank you.", "no_speech_prob": 0.97}])
    assert client_.transcribe(wav_bytes(), "a.wav", "en")["transcript"] == ""        # Java turns an empty transcript into "unintelligible"


def test_whisper_model_size_is_configurable(monkeypatch):
    monkeypatch.setenv("WHISPER_MODEL", "medium")
    client_, _, loads = install_fake_whisper(monkeypatch, [])
    client_.transcribe(wav_bytes(), "a.wav", "hi")
    assert loads == ["medium"]


def test_wav_decoding_handles_stereo_and_other_sample_rates_without_ffmpeg():
    from app.services.whisper_client import decode_wav
    assert len(decode_wav(wav_bytes(1.0, rate=16_000))) == 16_000
    assert len(decode_wav(wav_bytes(1.0, rate=48_000))) == 16_000                      # resampled to 16 kHz
    assert len(decode_wav(wav_bytes(1.0, rate=16_000, channels=2))) == 16_000          # mixed down to mono
    assert decode_wav(wav_bytes()).max() <= 1.0
    for bad in (b"", b"x" * 100, wav_bytes(width=1)):
        with pytest.raises(ValueError):
            decode_wav(bad)


# ---- internal API token --------------------------------------------------------------------------------------

def test_internal_routes_require_the_token_when_one_is_configured(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "s3cret-token")
    body = {"trade": "Electrician", "transcript": "voltage breaker"}
    assert client.get("/health").status_code == 200                                   # liveness stays open
    assert client.post("/internal/ml/evaluate", json=body).status_code == 401
    assert client.post("/internal/ml/evaluate", json=body, headers={"X-Internal-Token": "wrong"}).status_code == 401
    assert client.post("/evaluate", json=body).status_code == 401                      # the alias is guarded too
    ok = client.post("/internal/ml/evaluate", json=body, headers={"X-Internal-Token": "s3cret-token"})
    assert ok.status_code == 200


def test_production_without_a_token_fails_closed(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    assert client.post("/evaluate", json={"trade": "Electrician", "transcript": "x"}).status_code == 503


def test_openapi_documents_only_the_internal_prefix():
    paths = client.get("/openapi.json").json()["paths"]
    assert "/internal/ml/evaluate" in paths and "/evaluate" not in paths


# ---- guards against Whisper's known failure modes ---------------------------------------------------------------------

def test_a_silent_recording_never_reaches_the_model(monkeypatch):
    client_, model, loads = install_fake_whisper(monkeypatch, [{"text": "Thank you for watching.", "no_speech_prob": 0.1}])
    quiet = wav_bytes(1.0).replace(wav_bytes(1.0)[44:], b"\x00\x00" * 16_000)           # header + digital silence
    assert client_.transcribe(quiet, "a.wav", "hi")["transcript"] == ""
    assert model.calls == []


def test_hallucinated_foreign_script_and_low_confidence_segments_are_dropped(monkeypatch):
    client_, _, _ = install_fake_whisper(monkeypatch, [
        {"text": "remedies criterion 重新切換", "no_speech_prob": 0.1, "avg_logprob": -0.4},        # the junk seen with a real microphone
        {"text": "अरमानalis अस musun", "no_speech_prob": 0.1, "avg_logprob": -1.9},               # the model itself is unsure
        {"text": "la la la la la la", "no_speech_prob": 0.1, "avg_logprob": -0.3, "compression_ratio": 3.5},
        {"text": "मैंने सीनियर से काम सीखा", "no_speech_prob": 0.05, "avg_logprob": -0.3, "compression_ratio": 1.2}])
    assert client_.transcribe(wav_bytes(), "a.wav", "hi")["transcript"] == "मैंने सीनियर से काम सीखा"


def test_quiet_audio_is_levelled_and_decoding_is_made_more_careful(monkeypatch):
    client_, model, _ = install_fake_whisper(monkeypatch, [{"text": "ठीक है", "no_speech_prob": 0.0}])
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1); writer.setsampwidth(2); writer.setframerate(16_000)
        writer.writeframes((np.sin(np.linspace(0, 300, 16_000)) * 2000).astype("<i2").tobytes())      # peak ~ 0.06: a quiet phone
    client_.transcribe(buffer.getvalue(), "a.wav", "hi")
    samples, options = model.calls[0]
    assert 0.85 <= float(np.abs(samples).max()) <= 0.95                                               # raised to ~0.9
    assert options["beam_size"] == 5 and options["condition_on_previous_text"] is False


def test_the_hindi_hint_sets_the_script_but_contains_no_trade_or_rubric_words(monkeypatch):
    from app.services.whisper_client import DEFAULT_PROMPTS
    client_, model, _ = install_fake_whisper(monkeypatch, [{"text": "ठीक है", "no_speech_prob": 0.0}])
    client_.transcribe(wav_bytes(), "a.wav", "hi")
    client_.transcribe(wav_bytes(), "a.wav", "en")
    assert model.calls[0][1]["initial_prompt"] == DEFAULT_PROMPTS["hi"] and model.calls[1][1]["initial_prompt"] is None
    from app.services.rubric_store import load_rubric
    import json
    hint = DEFAULT_PROMPTS["hi"].lower()
    for trade in ("electrician", "plumber"):
        for question in json.loads(json.dumps([c.keywords for q in load_rubric(trade).questions for c in q.criteria])):
            assert not any(word.lower() in hint for word in question), "the hint must not prime the model with answer words"


def test_the_hint_can_be_overridden_from_the_environment(monkeypatch):
    monkeypatch.setenv("WHISPER_PROMPT_HI", "कस्टम संकेत")
    client_, model, _ = install_fake_whisper(monkeypatch, [{"text": "ठीक है", "no_speech_prob": 0.0}])
    client_.transcribe(wav_bytes(), "a.wav", "hi")
    assert model.calls[0][1]["initial_prompt"] == "कस्टम संकेत"


def test_leading_and_trailing_silence_is_trimmed_but_speech_is_kept():
    from app.services.whisper_client import trim_silence
    rate = 16_000
    speech = (np.sin(np.linspace(0, 2000, rate)) * 0.5).astype(np.float32)
    padded = np.concatenate([np.zeros(rate * 3, np.float32), speech, np.zeros(rate * 3, np.float32)])
    trimmed = trim_silence(padded)
    assert len(speech) <= len(trimmed) <= len(speech) + int(rate * 0.8)      # speech plus at most ~0.3 s of padding each side
    assert float(np.abs(trimmed).max()) > 0.4
    short = np.zeros(100, np.float32)
    assert len(trim_silence(short)) == 100                                    # too short to analyse: untouched
