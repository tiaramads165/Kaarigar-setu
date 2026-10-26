"""Reproducible synthetic attestation feature data (blueprint Phase 3: seed dataset + feature extraction).

Features, per attestation (computed by the Java backend from its own tables):
  ip_frequency            attestations that came from the same (hashed) IP in the previous 24 hours
  device_frequency        attestations that came from the same (hashed) device in the previous 24 hours
  minutes_since_previous  minutes since the previous attestation by the same employer
  reciprocity_ratio       share of this employer's attestations that are mutual (worker also vouched for them)

Label 1 = fraudulent pattern, 0 = normal behaviour. The model itself is trained WITHOUT labels, on normal
data only; labels exist so the held-out evaluation can report real precision and recall.
"""
from __future__ import annotations

import numpy as np

FEATURES = ("ip_frequency", "device_frequency", "minutes_since_previous", "reciprocity_ratio")


def _normal(rng: np.random.Generator, n: int) -> np.ndarray:
    ip = 1 + rng.poisson(0.7, n)
    device = 1 + rng.poisson(0.5, n)
    minutes = np.clip(rng.lognormal(mean=7.2, sigma=0.9, size=n), 30, 60_000)   # median ~ 1,300 min (about a day)
    reciprocity = np.clip(rng.beta(1.2, 14, n), 0, 1)
    return np.column_stack([ip, device, minutes, reciprocity])


FRAUD_KINDS = ("burst", "vouching_ring", "same_ip", "shared_device")


def _fraud(rng: np.random.Generator, n: int) -> tuple[np.ndarray, np.ndarray]:
    kind = rng.integers(0, 4, n)
    rows = _normal(rng, n)
    burst, ring, same_ip, shared_device = (kind == 0), (kind == 1), (kind == 2), (kind == 3)
    # a) rapid-fire burst: many attestations within minutes
    rows[burst, 2] = rng.uniform(0.2, 8, burst.sum())
    rows[burst, 0] = rng.integers(3, 12, burst.sum())
    # b) reciprocal vouching ring
    rows[ring, 3] = rng.uniform(0.6, 1.0, ring.sum())
    # c) many attestations from one IP
    rows[same_ip, 0] = rng.integers(6, 25, same_ip.sum())
    rows[same_ip, 2] = rng.uniform(1, 60, same_ip.sum())
    # d) one device vouching for many employers/workers
    rows[shared_device, 1] = rng.integers(5, 20, shared_device.sum())
    rows[shared_device, 3] = rng.uniform(0.3, 0.9, shared_device.sum())
    return rows, kind


def generate_with_kinds(n_normal: int = 2000, n_fraud: int = 200, seed: int = 42) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Like generate(), plus the fraud pattern of each row (index into FRAUD_KINDS, -1 for normal) for per-pattern analysis."""
    rng = np.random.default_rng(seed)
    normal = _normal(rng, n_normal)
    fraud, kind = _fraud(rng, n_fraud)
    x = np.vstack([normal, fraud])
    y = np.concatenate([np.zeros(n_normal, dtype=int), np.ones(n_fraud, dtype=int)])
    kinds = np.concatenate([np.full(n_normal, -1), kind])
    order = rng.permutation(len(y))
    return x[order], y[order], kinds[order]


def generate(n_normal: int = 2000, n_fraud: int = 200, seed: int = 42) -> tuple[np.ndarray, np.ndarray]:
    x, y, _ = generate_with_kinds(n_normal, n_fraud, seed)
    return x, y
