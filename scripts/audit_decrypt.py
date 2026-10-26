#!/usr/bin/env python3
"""Reads the encrypted audit file WITHOUT any of the Java code: an independent implementation of the stored format (see security/DataCipher.java).

    python3 scripts/audit_decrypt.py [data/audit.log] [--tail N]

A sealed line is  enc:v1:<keyId>:<base64url(nonce12 + ciphertext + tag)>  (AES-256-GCM). The key is derived from the ring key by
HMAC-SHA256(ringKey, "kaarigar-setu/at-rest/audit"); the associated data is "audit.file|<line number, starting at 1>". Lines written before encryption was
switched on are plain JSON and are read as they are. The ring comes from DATA_ENC_KEYS ("id:base64,id:base64") or DATA_ENC_KEY, in the environment or .env.local.
Needs the `cryptography` package.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parent.parent
PREFIX = "enc:v1:"


def ring_from(env: dict) -> dict[str, bytes]:
    text = env.get("DATA_ENC_KEYS") or (("k1:" + env["DATA_ENC_KEY"]) if env.get("DATA_ENC_KEY") else "")
    if not text:
        raise SystemExit("no data key: set DATA_ENC_KEYS (or DATA_ENC_KEY)")
    ring = {}
    for entry in text.split(","):
        key_id, _, material = entry.strip().partition(":")
        ring[key_id] = base64.b64decode(material)
    return ring


def env_file(path: Path = ROOT / ".env.local") -> dict:
    values = dict(os.environ)
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                name, _, value = line.partition("=")
                values.setdefault(name.strip(), value.split(" #")[0].strip().strip("'\""))
    return values


def domain_key(master: bytes, domain: str) -> bytes:
    return hmac.new(master, f"kaarigar-setu/at-rest/{domain}".encode(), hashlib.sha256).digest()


def open_sealed(stored: str, purpose: str, context, ring: dict[str, bytes], domain: str) -> str:
    key_id, _, blob = stored[len(PREFIX):].partition(":")
    raw = base64.urlsafe_b64decode(blob + "=" * (-len(blob) % 4))
    nonce, body = raw[:12], raw[12:]
    return AESGCM(domain_key(ring[key_id], domain)).decrypt(nonce, body, f"{purpose}|{context}".encode()).decode("utf-8")


def read_audit(path: Path, ring: dict[str, bytes]) -> list[dict]:
    """Every line of the audit file as the JSON object it holds ({seq, ts, event, prev}), oldest first."""
    out = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line:
            continue
        out.append(json.loads(open_sealed(line, "audit.file", number, ring, "audit") if line.startswith(PREFIX) else line))
    return out


def events(path: Path | None = None, env: dict | None = None) -> list[str]:
    """The event text of every line ("TYPE actor=A details=D"), oldest first. Empty if there is no audit file."""
    path = path or Path(os.environ.get("AUDIT_LOG_PATH", ROOT / "data" / "audit.log"))
    if not path.exists():
        return []
    return [entry["event"] for entry in read_audit(path, ring_from(env or env_file()))]


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    tail = int(sys.argv[sys.argv.index("--tail") + 1]) if "--tail" in sys.argv else 50
    if "--tail" in sys.argv and args and args[-1] == str(tail):
        args.pop()
    target = Path(args[0]) if args else None
    for entry in (read_audit(target or ROOT / "data" / "audit.log", ring_from(env_file())))[-tail:]:
        print(entry["seq"], entry["ts"], entry["event"])
