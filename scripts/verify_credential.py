#!/usr/bin/env python3
"""Verify a Kaarigar Setu credential WITHOUT trusting (or even contacting) the issuing application.

An employer, a bank or an auditor who is handed a credential file needs only three things: the file, the issuer's public key, and (optionally) the
issuer's signed revocation list. This script checks all of them with its own code: it shares nothing with the Java application.

    python3 scripts/verify_credential.py credential.json --public-key issuer.pem
    python3 scripts/verify_credential.py credential.json --public-key issuer.pem --revocations revocations.json
    python3 scripts/verify_credential.py credential.json --server https://ks.example        # fetches keys + revocations (see the trust note below)

Exit status: 0 = every check passed, 1 = a check failed (do not rely on the credential), 2 = could not run (bad file, no network, no openssl).

What it checks
  1. Signature. The signed bytes are rebuilt from the document with RFC 8785 canonical JSON (proof.canonicalization = "RFC8785-JCS"), or taken from
     proof.signedPayload for credentials issued before canonical signing, and checked with RSA PKCS#1 v1.5 / SHA-256 (via the openssl command).
  2. Key. The public key's fingerprint (first 16 hex digits of SHA-256 over its DER encoding) must equal proof.keyId and issuer.keyId inside the signed data.
  3. Dates. validFrom <= now <= validUntil.
  4. Revocation (with --revocations or --server). The revocation list must itself carry a valid signature by the same issuer and be recent; the credential must not be on it.

Trust note: with --server the key comes from the same server whose output you are checking. That detects tampering in transit and by anyone without the
signing key, but if the server itself is hostile it can serve its own key. For anything that matters, obtain the public key once through a channel you trust
(the issuer's website over https, a printed fingerprint, a colleague) and pin it with --public-key.

Profile of canonical JSON implemented here: sorted members (UTF-16 code unit order), no whitespace, minimal string escapes, numbers in shortest decimal form
with at most 15 significant digits in [1e-6, 1e21). Anything outside the profile is refused rather than guessed.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

LOWER, UPPER = Decimal("1e-6"), Decimal("1e21")


# ----------------------------------------------------------------------------------------------------------- canonical JSON
def canonical(value) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, Decimal):
        return _number(value)
    if isinstance(value, str):
        return _string(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical(v) for v in value) + "]"
    if isinstance(value, dict):
        keys = sorted(value, key=lambda k: k.encode("utf-16-be"))          # UTF-16 code unit order, not Python's code point order
        return "{" + ",".join(_string(k) + ":" + canonical(value[k]) for k in keys) + "}"
    raise ValueError(f"unsupported JSON value {type(value).__name__}")


def _number(d: Decimal) -> str:
    if d == 0:
        return "0"
    normal = d.normalize()
    digits = len(normal.as_tuple().digits)
    if digits > 15 or abs(normal) >= UPPER or abs(normal) < LOWER:
        raise ValueError(f"number outside the canonical profile: {d}")
    return format(normal, "f")


_SHORT = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\t": "\\t", "\n": "\\n", "\f": "\\f", "\r": "\\r"}


def _string(text: str) -> str:
    out = ['"']
    for ch in text:
        if ch in _SHORT:
            out.append(_SHORT[ch])
        elif ord(ch) < 0x20:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def parse(text: str):
    """Numbers become Decimal so 81.30 and 81.3 are the same number and nothing is rounded through a float."""
    return json.loads(text, parse_float=Decimal, parse_int=Decimal)


# --------------------------------------------------------------------------------------------------------------- crypto (openssl)
def need_openssl() -> str:
    path = shutil.which("openssl")
    if not path:
        print("openssl was not found; it is needed to check RSA signatures", file=sys.stderr)
        sys.exit(2)
    return path


def key_id(pem: str) -> str:
    der = subprocess.run([need_openssl(), "pkey", "-pubin", "-outform", "DER"], input=pem.encode(), capture_output=True, check=True).stdout
    return hashlib.sha256(der).hexdigest()[:16]


def signature_ok(pem: str, message: str, signature_b64: str) -> bool:
    try:
        signature = base64.b64decode(signature_b64, validate=True)
    except (ValueError, TypeError):
        return False
    with tempfile.TemporaryDirectory() as tmp:
        key, msg, sig = Path(tmp, "k.pem"), Path(tmp, "m"), Path(tmp, "s")
        key.write_text(pem)
        msg.write_bytes(message.encode("utf-8"))
        sig.write_bytes(signature)
        done = subprocess.run([need_openssl(), "dgst", "-sha256", "-verify", str(key), "-signature", str(sig), str(msg)], capture_output=True)
        return done.returncode == 0 and b"Verified OK" in done.stdout


# --------------------------------------------------------------------------------------------------------------------- checks
class Report:
    def __init__(self) -> None:
        self.failed = False

    def line(self, ok: bool, label: str, detail: str = "") -> None:
        if not ok:
            self.failed = True
        print(f"  [{' OK ' if ok else 'FAIL'}] {label}" + (f": {detail}" if detail else ""))


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=20) as response:        # noqa: S310 - the caller chose the URL
        return response.read()


def when(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def pick_key(keys: list[dict], wanted: str) -> str | None:
    return next((k["publicKeyPem"] for k in keys if k.get("keyId") == wanted), None)


def verify(document: dict, pem: str | None, server: str | None, revocation_text: str | None, max_list_age_hours: float) -> int:
    report = Report()
    try:
        credential, proof = document["credential"], document["proof"]
        signed_key_id = proof["keyId"]
    except (KeyError, TypeError):
        print("not a Kaarigar Setu credential document (needs 'credential' and 'proof')")
        return 1
    ref = str(credential.get("id", "?"))
    print(f"credential {ref}")

    keys: list[dict] = []
    if server:
        keys = json.loads(fetch(server.rstrip("/") + "/api/credential/keys"))["keys"]
        if pem is None:
            pem = pick_key(keys, signed_key_id)
    if pem is None:
        report.line(False, "public key", f"no key with id {signed_key_id}: pass --public-key or --server")
        return 1

    # 1. which bytes were signed
    method = proof.get("canonicalization")
    try:
        if method == "RFC8785-JCS":
            message = canonical(credential)
        elif method == "none" and isinstance(proof.get("signedPayload"), str):
            message = proof["signedPayload"]
            same = canonical(parse(message)) == canonical(credential)
            report.line(same, "document matches the signed text", "" if same else "the shown credential differs from the text that was signed")
        else:
            report.line(False, "signing format", f"unknown canonicalization {method!r}")
            return 1
    except ValueError as error:
        report.line(False, "canonical form", str(error))
        return 1

    # 2. signature and key identity
    report.line(signature_ok(pem, message, proof.get("signature", "")), "signature (RSA PKCS#1 v1.5, SHA-256)")
    actual = key_id(pem)
    report.line(actual == signed_key_id, "key id", f"public key {actual}, proof says {signed_key_id}")
    inside = credential.get("issuer", {}).get("keyId") if isinstance(credential.get("issuer"), dict) else None
    report.line(inside == signed_key_id, "key id inside the signed data agrees", f"{inside}")

    # 3. dates
    now = datetime.now(timezone.utc)
    try:
        start, end = when(credential["validFrom"]), when(credential["validUntil"])
        report.line(start <= now <= end, "validity period", f"{credential['validFrom']} .. {credential['validUntil']}")
    except (KeyError, ValueError):
        report.line(False, "validity period", "missing or unreadable dates")

    # 4. revocation
    if revocation_text is None and server:
        revocation_text = fetch(server.rstrip("/") + "/api/credential/revocations").decode("utf-8")
    if revocation_text is not None:
        try:
            lst = parse(revocation_text)
            body, list_proof = lst["list"], lst["proof"]
            list_pem = pem if list_proof["keyId"] == signed_key_id else pick_key(keys, list_proof["keyId"])
            if list_pem is None:
                report.line(False, "revocation list signature", f"list signed by unknown key {list_proof['keyId']}")
            else:
                report.line(signature_ok(list_pem, canonical(body), list_proof["signature"]), "revocation list signature")
                age = now - when(body["issuedAt"])
                report.line(timedelta(minutes=-5) <= age <= timedelta(hours=max_list_age_hours), "revocation list is recent", f"{max(age.total_seconds(), 0) / 3600:.1f} h old (up to 5 minutes of clock difference is allowed)")
                revoked = {entry["ref"] for entry in body["revoked"]}
                this_ref = ref.replace("urn:uuid:", "")
                report.line(this_ref not in revoked, "not revoked", "the issuer has revoked or superseded this credential" if this_ref in revoked else "")
        except (KeyError, ValueError, TypeError) as error:
            report.line(False, "revocation list", f"unreadable: {error}")
    else:
        print("  [ -- ] revocation not checked (give --revocations or --server); a revoked credential still passes the checks above")

    print("RESULT:", "FAILED" if report.failed else "all checks passed")
    return 1 if report.failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify a Kaarigar Setu credential offline.")
    parser.add_argument("document", help="credential JSON file ('-' for standard input)")
    parser.add_argument("--public-key", help="issuer's public key (PEM file); preferred, pin it from a trusted source")
    parser.add_argument("--server", help="base URL of the issuing app, to fetch its keys and revocation list (trusts that server for the key)")
    parser.add_argument("--revocations", help="a downloaded revocation list file")
    parser.add_argument("--max-list-age-hours", type=float, default=24.0)
    args = parser.parse_args()
    try:
        text = sys.stdin.read() if args.document == "-" else Path(args.document).read_text(encoding="utf-8")
        document = parse(text)
        pem = Path(args.public_key).read_text() if args.public_key else None
        revocations = Path(args.revocations).read_text(encoding="utf-8") if args.revocations else None
        return verify(document, pem, args.server, revocations, args.max_list_age_hours)
    except (OSError, ValueError) as error:
        print(f"could not run: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
