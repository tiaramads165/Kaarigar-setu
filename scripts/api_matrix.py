#!/usr/bin/env python3
"""Calls EVERY API once with a valid request (plus a few must-be-refused ones) and prints a table: method, path, expected status, got.

Needs the stack running with the scripted-test settings (STT_PROVIDER=stub for the ML service, TRUST_FORWARDED_FOR=true) and a Java log that
contains the console OTPs. Uses the same helpers as scripts/e2e.py. Creates throwaway accounts; run it against a development database only.

    BASE_URL=http://localhost:8190 JAVA_LOG=data/logs/dev.log ML_URL=http://127.0.0.1:8101 python3 scripts/api_matrix.py
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import e2e  # noqa: E402  (shared HTTP client, OTP reader and account helpers)

ML_URL = os.environ.get("ML_URL", "http://127.0.0.1:8001")
ROWS: list[tuple[str, str, str, int, bool]] = []


def hit(group, client, method, path, expect, body=None, raw=None, content_type=None, csrf=True, label=None):
    status, response = client.request(method, path, body, raw=raw, content_type=content_type, csrf=csrf)
    want = expect if isinstance(expect, (tuple, list)) else (expect,)
    ROWS.append((group, f"{method} {label or path}", "/".join(map(str, want)), status, status in want))
    return status, response


def ml(group, method, path, expect, payload=None, token=True, files=None):
    headers = {"X-Internal-Token": e2e.env_file().get("ML_INTERNAL_TOKEN", "")} if token else {}
    data = None
    if files is not None:                       # multipart: one WAV file field named "audio"
        boundary = uuid.uuid4().hex
        data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n").encode() + files + f"\r\n--{boundary}--\r\n".encode()
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(ML_URL + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            status, text = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, text = error.code, error.read()
    want = expect if isinstance(expect, (tuple, list)) else (expect,)
    ROWS.append((group, f"{method} {path}" + ("" if token else "  (no token)"), "/".join(map(str, want)), status, status in want))
    try:
        return status, json.loads(text or b"{}")
    except ValueError:
        return status, {}


def main() -> int:
    env = e2e.env_file()
    anon = e2e.Client("anon")

    G = "Public"
    hit(G, anon, "GET", "/health", 200)
    hit(G, anon, "GET", "/api/auth/session", 200)
    worker_phone = e2e.new_phone()
    hit(G, anon, "POST", "/api/worker/register", 403, {"name": "No Consent", "phone": e2e.new_phone(), "trade": "electrician", "language": "hi", "consent": False}, label="/api/worker/register (no consent is refused)")
    status, body = hit(G, anon, "POST", "/api/worker/register", 201, {"name": "Matrix Worker", "phone": worker_phone, "trade": "electrician", "language": "hi", "consent": True})
    worker_id = body.get("workerId")
    employer_phone = e2e.new_phone()
    hit(G, anon, "POST", "/api/employer/register", 201, {"name": "Matrix Employer", "phone": employer_phone, "consent": True})
    hit(G, anon, "POST", "/api/auth/logout", 200)
    for page in ("/", "/demo", "/verify/00000000-0000-0000-0000-000000000000"):
        hit("Pages", anon, "GET", page, (200, 404))

    G = "Worker login"
    worker = e2e.Client("worker")
    status, puzzle = hit(G, worker, "POST", "/api/auth/otp/request", 428, {"role": "WORKER", "phone": worker_phone}, label="/api/auth/otp/request (a new browser is given a puzzle)")
    ROWS.append((G, "   -> the 428 carries a puzzle and its difficulty", "challenge+bits", 428, bool(puzzle.get("challenge")) and puzzle.get("bits", 0) >= 1))
    status, _ = worker.request_code("WORKER", worker_phone)
    ROWS.append((G, "POST /api/auth/otp/request (puzzle solved)", "200", status, status == 200))
    hit(G, worker, "POST", "/api/auth/otp/verify", 401, {"role": "WORKER", "phone": worker_phone, "code": "000000"}, label="/api/auth/otp/verify (wrong code is refused)")
    status, body = hit(G, worker, "POST", "/api/auth/otp/verify", 200, {"role": "WORKER", "phone": worker_phone, "code": e2e.last_otp(worker_phone[-4:], "login")})
    worker.csrf = body.get("csrfToken", worker.csrf)
    hit(G, worker, "GET", "/api/auth/session", 200)
    hit(G, worker, "GET", "/api/worker/me/passport", 200)
    hit("Pages", worker, "GET", "/worker-dashboard", 200)
    hit("Pages", worker, "GET", "/assessment", 200)

    G = "Assessment"
    status, started = hit(G, worker, "POST", "/api/assessment/start", 201, {})
    token = started.get("attemptToken", "")
    questions = [q["id"] for q in started.get("questions", [])]
    status, issued = hit(G, worker, "GET", f"/api/assessment/challenge?attempt={token}&question={questions[0]}", 200, label="/api/assessment/challenge (question 1)")
    hit(G, worker, "GET", f"/api/assessment/challenge?attempt={token}&question={questions[1]}", 409, label="/api/assessment/challenge (a later question is refused)")
    hit(G, worker, "POST", f"/api/assessment/submit-answer?attempt={token}&question={questions[0]}", 428, raw=e2e.wav(), content_type="audio/wav", label="/api/assessment/submit-answer (no spoken-code token is refused)")
    hit(G, worker, "POST", f"/api/assessment/submit-answer?attempt={token}&question={questions[0]}&challenge={issued.get('token', '')}", 422, raw=e2e.wav(), content_type="audio/wav", label="/api/assessment/submit-answer (code not said is refused)")
    spoken = e2e.with_marker(e2e.wav(), b"KSCODE:" + issued.get("code", "000").encode())
    hit(G, worker, "POST", f"/api/assessment/submit-answer?attempt={token}&question={questions[0]}&challenge={issued.get('token', '')}", 201, raw=spoken, content_type="audio/wav", label="/api/assessment/submit-answer (question 1)")
    hit(G, worker, "POST", f"/api/assessment/submit-answer?attempt={token}&question={questions[0]}", 409, raw=e2e.wav(), content_type="audio/wav", label="/api/assessment/submit-answer (same question twice is refused)")
    hit(G, worker, "POST", f"/api/assessment/submit-answer?attempt={token}&question={questions[1]}", 400, raw=b"not a wav", content_type="audio/wav", label="/api/assessment/submit-answer (not a WAV is refused)")
    for q in questions[1:]:
        status, last = e2e.answer(worker, token, q)
        assert status == 201, f"question {q}: {status} {last}"
    ROWS.append((G, "POST /api/assessment/submit-answer (questions 2-10)", "201", 201, True))
    hit(G, worker, "GET", f"/api/assessment/result?attempt={token}", 200)

    G = "Employer"
    employer = e2e.Client("employer")
    employer.login("EMPLOYER", employer_phone)
    ROWS.append((G, "POST /api/auth/otp/request + otp/verify (employer)", "200", 200, True))
    hit("Pages", employer, "GET", "/employer-dashboard", 200)
    today = time.strftime("%Y-%m-%d")
    status, body = hit(G, employer, "POST", "/api/attestation/request-otp", 201, {"workerPhone": worker_phone, "jobRole": "House wiring", "workDate": today, "qualityRating": 5, "punctualityRating": 4, "conductRating": 5, "wouldHireAgain": True, "durationDays": 5})
    assert status == 201, f"attestation request: {status} {body}"
    attestation_id = body.get("attestationId")
    hit(G, employer, "POST", "/api/attestation/verify-otp", 401, {"attestationId": attestation_id, "code": "000000"}, label="/api/attestation/verify-otp (wrong code is refused)")
    status, body = hit(G, employer, "POST", "/api/attestation/verify-otp", 200, {"attestationId": attestation_id, "code": e2e.last_otp(employer_phone[-4:], "attestation")})
    ROWS.append((G, "   -> attestation status", "APPROVED", 200, body.get("status") == "APPROVED"))
    hit(G, employer, "GET", "/api/attestation/mine", 200)

    G = "Credential"
    status, issued = hit(G, worker, "POST", "/api/credential/issue", 201, {})
    ref = issued.get("ref", "")
    hit(G, worker, "GET", "/api/credential/mine", 200)
    hit(G, anon, "GET", f"/api/credential/{ref}/verify", 200, label="/api/credential/{ref}/verify")
    status, png = hit(G, anon, "GET", f"/api/credential/{ref}/qr", 200, label="/api/credential/{ref}/qr")
    ROWS.append((G, "   -> QR is a PNG image", "PNG", 200, isinstance(png, bytes) and png[:4] == b"\x89PNG"))
    status, doc = hit(G, worker, "GET", f"/api/credential/{ref}/download", 200, label="/api/credential/{ref}/download")
    hit(G, anon, "GET", "/api/credential/public-key", 200)
    payload = doc.get("credential") if isinstance(doc, dict) else None
    signature = (doc.get("proof") or {}).get("signature", "") if isinstance(doc, dict) else ""
    payload_text = json.dumps(payload, separators=(",", ":")) if payload is not None else ""
    status, genuine = hit(G, e2e.Client("public"), "POST", "/api/credential/verify", 200, {"payload": payload_text, "signature": signature}, label="/api/credential/verify (genuine document)")
    ROWS.append((G, "   -> genuine document is VALID", "VALID", 200, genuine.get("state") == "VALID"))
    status, tampered = hit(G, e2e.Client("public"), "POST", "/api/credential/verify", 200, {"payload": payload_text.replace('"skillScore":', '"skillScore":9'), "signature": signature}, label="/api/credential/verify (tampered score)")
    ROWS.append((G, "   -> tampered document is INVALID_SIGNATURE", "INVALID_SIGNATURE", 200, tampered.get("state") == "INVALID_SIGNATURE"))
    status, junk = hit(G, e2e.Client("public"), "POST", "/api/credential/verify", 200, {"payload": "{}", "signature": "AAAA"}, label="/api/credential/verify (garbage signature)")
    ROWS.append((G, "   -> garbage signature is INVALID_SIGNATURE", "INVALID_SIGNATURE", 200, junk.get("state") == "INVALID_SIGNATURE"))
    hit("Pages", anon, "GET", f"/verify/{ref}", 200, label="/verify/{ref}")
    hit(G, worker, "POST", f"/api/credential/{ref}/revoke", 200, {"reason": "api matrix"}, label="/api/credential/{ref}/revoke")

    G = "Moderator"
    # a burst of same-source confirmations, so the fraud model flags something for the moderator
    subprocess.run([sys.executable, str(Path(__file__).resolve().parent / "demo_fraud_burst.py")], env=os.environ.copy(), capture_output=True, timeout=300)
    mod = e2e.Client("moderator")
    hit(G, mod, "GET", "/api/moderator/flagged", 401, label="/api/moderator/flagged (not signed in is refused)")
    hit(G, mod, "POST", "/api/auth/moderator/login", 401, {"username": "moderator", "password": "wrong-password"}, label="/api/auth/moderator/login (wrong password)")
    hit(G, mod, "POST", "/api/auth/moderator/login", 200, {"username": "moderator", "password": env.get("MODERATOR_PASSWORD_PLAIN", "")})
    status, body = hit(G, mod, "POST", "/api/auth/moderator/verify", 200, {"code": e2e.last_otp("0901", "moderator")})
    mod.csrf = body.get("csrfToken", mod.csrf)
    status, queue = hit(G, mod, "GET", "/api/moderator/flagged", 200)
    flagged = queue.get("flagged", []) if isinstance(queue, dict) else []
    ROWS.append((G, "   -> flagged confirmations found", ">=1", 200, len(flagged) >= 1))
    if flagged:
        hit(G, mod, "POST", "/api/moderator/decision", 200, {"flagId": flagged[0]["flagId"], "decision": "VALID"})
        hit(G, mod, "POST", "/api/moderator/decision", 409, {"flagId": flagged[0]["flagId"], "decision": "FRAUD"}, label="/api/moderator/decision (already decided)")
    hit(G, mod, "POST", "/api/moderator/decision", 400, {"flagId": 1, "decision": "MAYBE"}, label="/api/moderator/decision (bad decision)")
    hit(G, mod, "GET", "/api/moderator/me", 200)
    hit(G, mod, "GET", "/api/moderator/pending", 200)
    hit(G, mod, "POST", "/api/moderator/pending/987654321/approve", 404, {}, label="/api/moderator/pending/{id}/approve (no such proposal)")
    hit(G, mod, "POST", "/api/moderator/pending/987654321/reject", 404, {}, label="/api/moderator/pending/{id}/reject (no such proposal)")
    hit(G, mod, "POST", "/api/moderator/vet-employer", 400, {}, label="/api/moderator/vet-employer (no employerId)")
    hit(G, mod, "POST", "/api/moderator/dispute-decision", 404, {"attestationId": 987654321, "decision": "REMOVED"}, label="/api/moderator/dispute-decision (no open dispute)")
    status, audit = hit(G, mod, "GET", "/api/moderator/audit-check", 200)
    ROWS.append((G, "   -> audit chain valid", "true", 200, isinstance(audit, dict) and audit.get("fileChainValid") is True))
    hit(G, mod, "POST", "/api/auth/logout", 200)

    G = "Security rules"
    hit(G, e2e.Client("nocsrf"), "POST", "/api/worker/register", 403, {"name": "x"}, csrf=False, label="/api/worker/register (no CSRF token)")
    hit(G, e2e.Client("nologin"), "GET", "/api/worker/me/passport", 401, label="/api/worker/me/passport (not signed in)")
    hit(G, employer, "GET", "/api/worker/me/passport", 403, label="/api/worker/me/passport (employer -> forbidden)")
    hit(G, worker, "POST", "/api/attestation/request-otp", 403, {"workerPhone": worker_phone}, label="/api/attestation/request-otp (worker -> forbidden)")
    hit(G, worker, "POST", "/api/worker/erase", 400, {"confirm": "yes please"}, label="/api/worker/erase (wrong phrase)")

    G = "Demo + erase"
    page = anon.request("GET", "/demo")[1]
    demo = re.search(r'data-role="WORKER" data-id="(\d+)"', page if isinstance(page, str) else "")
    if demo:
        demo_client = e2e.Client("demo")
        hit(G, demo_client, "POST", "/api/auth/demo-login", 200, {"role": "WORKER", "id": int(demo.group(1))}, label="/api/auth/demo-login (dev only)")
    hit(G, worker, "POST", "/api/worker/erase", 200, {"confirm": "ERASE MY DATA"})
    hit(G, employer, "POST", "/api/employer/erase", 200, {"confirm": "ERASE MY DATA"})
    hit(G, worker, "GET", "/api/worker/me/passport", 401, label="/api/worker/me/passport (after erase: session gone)")

    G = "ML service"
    ml(G, "GET", "/health", 200)
    ml(G, "GET", "/internal/ml/trades", 200)
    ml(G, "GET", "/internal/ml/trades", (401, 403), token=False)
    ml(G, "GET", "/internal/ml/rubric/electrician?language=hi", 200)
    ml(G, "GET", "/internal/ml/rubric/astronaut", 404)
    ml(G, "POST", "/internal/ml/transcribe?language=hi", 200, files=e2e.wav())
    ml(G, "POST", "/internal/ml/audio-fingerprint", 200, files=e2e.speech_wav(7, 3.0))
    ml(G, "POST", "/internal/ml/audio-fingerprint", (401, 403), token=False, files=e2e.speech_wav(7, 1.0))
    ml(G, "POST", "/internal/ml/audio-fingerprint", 422, files=b"not a wav at all")
    ml(G, "POST", "/internal/ml/evaluate", 200, {"trade": "electrician", "transcript": "मैं रोज़ पेचकस, प्लायर और टेस्टर का इस्तेमाल करता हूं।", "question_id": 1, "language": "hi"})
    ml(G, "POST", "/internal/ml/evaluate", 422, {"trade": "astronaut", "transcript": "hello", "question_id": 1})
    ml(G, "POST", "/internal/ml/fraud-check", 200, {"ip_frequency": 1, "device_frequency": 1, "minutes_since_previous": 600, "reciprocity_ratio": 0})
    ml(G, "POST", "/internal/ml/fraud-check", 200, {"ip_frequency": 12, "device_frequency": 12, "minutes_since_previous": 0.2, "reciprocity_ratio": 1})
    ml(G, "POST", "/internal/ml/trust-score", 200, {"nodes": ["E1", "W1", "W2"], "edges": [{"source": "E1", "target": "W1", "weight": 1.0}, {"source": "E1", "target": "W2", "weight": 0.4}]})
    ml(G, "POST", "/internal/ml/trust-score", 422, {"nodes": ["E1"], "edges": [{"source": "E1", "target": "E1", "weight": 5}]})

    width = max(len(r[1]) for r in ROWS)
    print(f"\n{'GROUP':<15} {'REQUEST':<{width}}  WANT   GOT   RESULT")
    last = ""
    for group, request, want, got, ok in ROWS:
        print(f"{(group if group != last else ''):<15} {request:<{width}}  {want:<6} {got:<5} {'PASS' if ok else 'FAIL  <<<<<<'}")
        last = group
    failed = [r for r in ROWS if not r[4]]
    print(f"\n{len(ROWS) - len(failed)} of {len(ROWS)} API checks passed" + ("" if not failed else f"; {len(failed)} FAILED"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
