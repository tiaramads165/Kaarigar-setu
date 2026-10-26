"""An acoustic fingerprint of one recording, so the SAME recording played into the assessment twice can be recognised.

Why: nothing else stops someone from playing a good recording of an answer (their own, an expert's, or one lifted from another account). The words
are right, the transcript is right, the score is right. What cannot be hidden is that it is the same recording.

How (the "landmark" idea behind audio-recognition services): the recording is turned into a spectrogram; the strongest points of it that stand out from
their surroundings are found (a spoken answer has many: the harmonics of each syllable); each such point is paired with a few points just after it, and
every pair becomes one number from (frequency of the first, frequency of the second, time between them). The set of numbers survives what a replay does
to a recording: a different volume, noise, a narrower band, another sample rate, a trimmed start or end, a speaker-to-microphone hop. It does not survive
a change of speed, and it is not a speaker check: two different people saying the same words do not share landmarks.

What leaves this module is only those numbers and when each was found (a frame index), never audio; the caller keeps keyed hashes of them.
"""
from __future__ import annotations

import io
import wave
from dataclasses import dataclass

import numpy as np
from scipy import ndimage, signal

TARGET_RATE = 8000          # speech lives below 4 kHz, and every phone microphone and codec keeps that band
N_FFT = 512                 # 64 ms window
HOP = 128                   # 16 ms between frames
BAND_HZ = (250.0, 3600.0)   # below it: rumble and hum; above it: what a telephone channel drops anyway
NEIGHBOURHOOD = (13, 21)    # a peak is the loudest point within +-6 frequency bins (+-94 Hz) and +-10 frames (+-160 ms)
RANGE_DB = 42.0             # a peak must be within this many dB of the loudest point of the recording
TARGET_FRAMES = (1, 63)     # pair a peak with later peaks 16 ms to 1 s after it
TARGET_BINS = 48            # ... and within +-750 Hz of it
FAN_OUT = 5                 # at most this many pairs per peak
MAX_LANDMARKS = 3000        # more than a minute of dense speech never needs more


class AudioError(ValueError):
    """The bytes are not audio this module can read (the route turns it into HTTP 422)."""


@dataclass(frozen=True)
class Fingerprint:
    hashes: list[int]       # one 23-bit number per landmark
    times: list[int]        # the frame (16 ms steps) of the landmark's first point
    seconds: float          # length of the recording

    @property
    def landmarks(self) -> int:
        return len(self.hashes)


def read_wav(content: bytes) -> tuple[np.ndarray, int]:
    """16-bit PCM WAV (mono or stereo) -> floats in [-1, 1], mono, and the sample rate."""
    try:
        with wave.open(io.BytesIO(content)) as reader:
            channels, width, rate, frames = reader.getnchannels(), reader.getsampwidth(), reader.getframerate(), reader.getnframes()
            if width != 2:
                raise AudioError("only 16-bit PCM WAV is supported")
            if not 1 <= channels <= 2 or not 4000 <= rate <= 48000:
                raise AudioError("unsupported WAV format")
            raw = reader.readframes(min(frames, rate * 120))
    except (wave.Error, EOFError) as error:
        raise AudioError(f"not a readable WAV file: {error}") from error
    samples = np.frombuffer(raw[: len(raw) // (2 * channels) * 2 * channels], dtype="<i2").astype(np.float64) / 32768.0
    if channels == 2:
        samples = samples.reshape(-1, 2).mean(axis=1)
    return samples, rate


def fingerprint(content: bytes) -> Fingerprint:
    samples, rate = read_wav(content)
    seconds = len(samples) / rate
    if rate != TARGET_RATE:
        divisor = np.gcd(rate, TARGET_RATE)
        samples = signal.resample_poly(samples, TARGET_RATE // divisor, rate // divisor)
    if len(samples) < N_FFT * 2 or float(np.max(np.abs(samples))) < 1e-4:
        return Fingerprint([], [], seconds)                       # silence or a blip: nothing to recognise
    _, _, spectrum = signal.stft(samples, fs=TARGET_RATE, window="hann", nperseg=N_FFT, noverlap=N_FFT - HOP, boundary=None, padded=False)
    power_db = 20.0 * np.log10(np.abs(spectrum) + 1e-10)
    low, high = int(BAND_HZ[0] * N_FFT / TARGET_RATE), int(BAND_HZ[1] * N_FFT / TARGET_RATE)
    band = power_db[low:high]
    ceiling = float(band.max())
    is_peak = (band == ndimage.maximum_filter(band, size=NEIGHBOURHOOD, mode="constant", cval=-np.inf)) & (band > ceiling - RANGE_DB)
    bins, frames = np.nonzero(is_peak)
    order = np.lexsort((bins, frames))
    bins, frames = bins[order] + low, frames[order]
    levels = power_db[bins, frames]
    return Fingerprint(*_pairs(bins, frames, levels), seconds)


def _pairs(bins: np.ndarray, frames: np.ndarray, levels: np.ndarray) -> tuple[list[int], list[int]]:
    hashes: list[int] = []
    times: list[int] = []
    count = len(frames)
    for i in range(count):
        candidates = []
        j = i + 1
        while j < count and frames[j] - frames[i] <= TARGET_FRAMES[1]:
            gap = int(frames[j] - frames[i])
            if gap >= TARGET_FRAMES[0] and abs(int(bins[j]) - int(bins[i])) <= TARGET_BINS:
                candidates.append((-levels[j], j))                 # the strongest targets are the ones noise leaves alone
            j += 1
        for _, j in sorted(candidates)[:FAN_OUT]:
            gap = int(frames[j] - frames[i])
            hashes.append((int(bins[i]) << 15) | (int(bins[j]) << 7) | gap)   # 8 + 8 + 7 bits: bins fit in 8 bits (N_FFT/2 = 256), the gap in 7 (<= 63)
            times.append(int(frames[i]))
    if len(hashes) > MAX_LANDMARKS:                                # keep the evenly spread subset, not the first seconds
        keep = np.linspace(0, len(hashes) - 1, MAX_LANDMARKS).astype(int)
        hashes = [hashes[k] for k in keep]
        times = [times[k] for k in keep]
    return hashes, times


def probes(value: int) -> list[int]:
    """The landmark itself and its two neighbours in time-gap. A copy that starts a fraction of a frame later (every real replay does) lands its peaks one
    frame off, which turns a landmark's gap into gap-1 or gap+1; looking those up as well recovers them. The caller does the same when it queries the database."""
    gap = value & 0x7F
    return [value if g == gap else (value & ~0x7F) | g for g in (gap - 1, gap, gap + 1) if 1 <= g <= TARGET_FRAMES[1] + 1]


def aligned_matches(query: Fingerprint, stored: Fingerprint) -> tuple[int, int]:
    """(how many landmarks of the QUERY also occur in STORED at one consistent time shift, that shift in frames). The same recording gives one tall spike;
    chance gives none. Shifts one frame apart are pooled, for the same reason as in probes()."""
    index: dict[int, list[int]] = {}
    for value, when in zip(stored.hashes, stored.times):
        index.setdefault(value, []).append(when)
    votes: dict[int, int] = {}
    for value, when in zip(query.hashes, query.times):
        seen: set[tuple[int, int]] = set()
        for probe in probes(value):
            for other in index.get(probe, ()):
                if (probe, other) not in seen:
                    seen.add((probe, other))
                    votes[other - when] = votes.get(other - when, 0) + 1
    if not votes:
        return 0, 0
    pooled = {shift: votes.get(shift - 1, 0) + votes[shift] + votes.get(shift + 1, 0) for shift in votes}
    best = max(pooled, key=pooled.get)
    return min(pooled[best], query.landmarks), best
