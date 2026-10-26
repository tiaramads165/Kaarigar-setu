"""The recording fingerprint, on speech-like signals made here (no model, no recordings, no macOS tools), so it runs anywhere.
A matching calibration on real synthesized speech is reported in docs/REPLAY_DETECTION.md (benchmarks/replay_benchmark.py)."""
import io
import wave

import numpy as np
import pytest
from fastapi.testclient import TestClient
from scipy import signal

from app.main import app
from app.services.audio_fingerprint import AudioError, aligned_matches, fingerprint, probes, read_wav

client = TestClient(app)
RATE = 16000
THRESHOLD = 25          # the matching threshold the backend uses (REPLAY_MIN_MATCHES)


def speech_like(seconds: float, seed: int) -> np.ndarray:
    """Syllables of 120-260 ms: a gliding pitch, random vowel formants, an envelope, short noise bursts between them. Different seeds give different 'speech'."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(seconds * RATE))
    t_cursor = 0
    while t_cursor < len(out):
        n = int(rng.uniform(0.12, 0.26) * RATE)
        n = min(n, len(out) - t_cursor)
        t = np.arange(n) / RATE
        f0 = rng.uniform(95, 230) * (1 + rng.uniform(-0.15, 0.15) * t / max(t[-1], 1e-3))
        phase = 2 * np.pi * np.cumsum(f0) / RATE
        formants = rng.uniform([300, 800, 2200], [800, 2300, 3300])
        syllable = np.zeros(n)
        for k in range(1, 30):
            frequency = k * f0.mean()
            if frequency > 3800:
                break
            gain = sum(np.exp(-((frequency - f) / 160.0) ** 2) for f in formants)
            syllable += gain * np.sin(k * phase)
        envelope = np.sin(np.pi * np.arange(n) / n) ** 1.5
        out[t_cursor: t_cursor + n] += syllable * envelope * rng.uniform(0.5, 1.0)
        t_cursor += n
        pause = int(rng.uniform(0.02, 0.09) * RATE)
        burst = rng.standard_normal(min(pause, len(out) - t_cursor)) * 0.05
        out[t_cursor: t_cursor + len(burst)] += burst
        t_cursor += pause
    return out / (np.max(np.abs(out)) + 1e-9) * 0.8


def to_wav(samples: np.ndarray, rate: int = RATE) -> bytes:
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2").tobytes()
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes(pcm)
    return buffer.getvalue()


def noisy(x, snr_db, seed=5):
    rng = np.random.default_rng(seed)
    return x + rng.standard_normal(len(x)) * np.sqrt(np.mean(x ** 2) / 10 ** (snr_db / 10))


def room(x, rt60=0.35, seed=7):
    rng = np.random.default_rng(seed)
    n = int(RATE * rt60)
    impulse = rng.standard_normal(n) * np.exp(-6.9 * np.arange(n) / n)
    impulse[0] = 1.0
    y = signal.fftconvolve(x, impulse / np.sqrt(np.sum(impulse ** 2)))[: len(x)]
    return y * np.max(np.abs(x)) / (np.max(np.abs(y)) + 1e-9)


def matches(copy: np.ndarray, original: np.ndarray) -> int:
    return aligned_matches(fingerprint(to_wav(copy)), fingerprint(to_wav(original)))[0]


@pytest.fixture(scope="module")
def clips():
    return [speech_like(5.0, seed) for seed in range(1, 7)]


def test_a_recording_matches_itself_completely_and_has_many_landmarks(clips):
    fp = fingerprint(to_wav(clips[0]))
    assert fp.landmarks > 300
    assert abs(fp.seconds - 5.0) < 0.01
    assert aligned_matches(fp, fp)[0] == fp.landmarks


def test_different_recordings_share_almost_nothing(clips):
    worst = max(matches(a, b) for i, a in enumerate(clips) for j, b in enumerate(clips) if i < j)
    assert worst < THRESHOLD / 3, f"the most any two different recordings shared was {worst}"


@pytest.mark.parametrize("name,transform", [
    ("quieter", lambda x: x * 0.15),
    ("noise at 15 dB", lambda x: noisy(x, 15)),
    ("band-limited like a phone speaker", lambda x: signal.sosfilt(signal.butter(4, [350, 3300], btype="band", fs=RATE, output="sos"), x)),
    ("resampled through 8 kHz", lambda x: signal.resample_poly(signal.resample_poly(x, 1, 2), 2, 1)),
    ("clipped", lambda x: np.clip(x * 3, -0.4, 0.4)),
    ("start and end trimmed by an arbitrary amount", lambda x: x[int(0.437 * RATE): -int(0.31 * RATE)]),
    ("played in a room", room),
    ("speaker to microphone: band-limit, clipping, room and noise", lambda x: noisy(room(np.tanh(2.5 * signal.sosfilt(signal.butter(4, [350, 3300], btype="band", fs=RATE, output="sos"), x))), 18)),
])
def test_a_replayed_copy_is_still_recognised(clips, name, transform):
    for clip in clips[:3]:
        found = matches(transform(clip), clip)
        assert found >= THRESHOLD * 2, f"{name}: only {found} landmarks matched (threshold {THRESHOLD})"


def test_the_copy_is_recognised_at_any_start_offset_within_a_frame(clips):
    """A real replay starts at an arbitrary instant, so its frames fall between the original's: that is what probes() and the pooled shifts are for."""
    counts = [matches(clips[0][offset:], clips[0]) for offset in range(0, 512, 37)]
    assert min(counts) >= THRESHOLD * 4, counts


def test_a_change_of_speed_is_NOT_recognised_which_is_why_the_transcript_is_compared_too(clips):
    sped_up = signal.resample_poly(clips[0], 100, 106)
    assert matches(sped_up, clips[0]) < THRESHOLD, "documented limit of the audio fingerprint"


def test_silence_and_a_blip_have_nothing_to_recognise():
    assert fingerprint(to_wav(np.zeros(RATE * 2))).landmarks == 0
    assert fingerprint(to_wav(np.zeros(100) + 0.5)).landmarks == 0


def test_a_constant_tone_does_not_match_speech(clips):
    tone = 0.5 * np.sin(2 * np.pi * 440 * np.arange(5 * RATE) / RATE)
    assert matches(tone, clips[0]) < THRESHOLD / 3
    assert matches(clips[0], tone) < THRESHOLD / 3


def test_stereo_and_other_sample_rates_are_read(clips):
    mono8k = signal.resample_poly(clips[0], 1, 2)
    assert fingerprint(to_wav(mono8k, 8000)).landmarks > 300
    stereo = io.BytesIO()
    pcm = (np.stack([clips[0], clips[0]], axis=1) * 32767).astype("<i2")
    with wave.open(stereo, "wb") as writer:
        writer.setnchannels(2)
        writer.setsampwidth(2)
        writer.setframerate(RATE)
        writer.writeframes(pcm.tobytes())
    assert aligned_matches(fingerprint(stereo.getvalue()), fingerprint(to_wav(clips[0])))[0] > 300


def test_landmarks_fit_in_23_bits_and_times_are_frame_indexes(clips):
    fp = fingerprint(to_wav(clips[1]))
    assert all(0 <= h < (1 << 23) for h in fp.hashes)
    assert all(0 <= t <= int(5.2 * RATE / 2 / 128) for t in fp.times)
    assert len(fp.hashes) == len(fp.times)
    assert all(1 <= (h & 0x7F) <= 63 for h in fp.hashes)


def test_probes_include_the_neighbouring_time_gaps_and_keep_the_frequencies():
    value = (100 << 15) | (150 << 7) | 20
    assert sorted(probes(value)) == [(100 << 15) | (150 << 7) | g for g in (19, 20, 21)]
    assert all((p >> 7) == (value >> 7) for p in probes(value))
    assert [p & 0x7F for p in probes((5 << 15) | (6 << 7) | 1)] == [1, 2], "a gap of 0 does not exist"


@pytest.mark.parametrize("junk", [b"not audio at all", b"RIFF\x00\x00\x00\x00WAVE", b"\x00" * 100])
def test_unreadable_audio_is_an_error_not_a_crash(junk):
    with pytest.raises(AudioError):
        read_wav(junk)


def test_8_bit_audio_is_refused():
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(1)
        writer.setframerate(8000)
        writer.writeframes(b"\x80" * 8000)
    with pytest.raises(AudioError):
        read_wav(buffer.getvalue())


def test_the_route_returns_numbers_only(clips):
    response = client.post("/audio-fingerprint", files={"audio": ("answer.wav", to_wav(clips[0]), "audio/wav")})
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"hashes", "times", "seconds", "landmarks"}
    assert body["landmarks"] == len(body["hashes"]) == len(body["times"]) > 300


def test_the_route_rejects_empty_oversized_and_unreadable_audio(monkeypatch):
    assert client.post("/audio-fingerprint", files={"audio": ("a.wav", b"", "audio/wav")}).status_code == 422
    assert client.post("/audio-fingerprint", files={"audio": ("a.wav", b"garbage bytes", "audio/wav")}).status_code == 422
    monkeypatch.setenv("MAX_AUDIO_BYTES", "100")
    assert client.post("/audio-fingerprint", files={"audio": ("a.wav", b"x" * 500, "audio/wav")}).status_code == 413


def test_the_route_needs_the_internal_token_when_one_is_configured(monkeypatch, clips):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "secret")
    files = {"audio": ("a.wav", to_wav(clips[0]), "audio/wav")}
    assert client.post("/audio-fingerprint", files=files).status_code == 401
    assert client.post("/audio-fingerprint", files=files, headers={"X-Internal-Token": "secret"}).status_code == 200


def test_the_stub_hears_a_code_a_test_recording_carries_and_nothing_else():
    from app.services.stub_transcriber import StubTranscriber
    stub = StubTranscriber()
    plain = stub.transcribe(b"RIFF....WAVE", "a.wav", "hi")["transcript"]
    coded = stub.transcribe(b"RIFF....WAVE KSCODE:472", "a.wav", "hi")["transcript"]
    assert not any(ch.isdigit() for ch in plain)
    assert coded.rstrip().endswith("4 7 2")
    assert not any(ch.isdigit() for ch in stub.transcribe(b"KSCODE:ab9", "a.wav", "hi")["transcript"])
