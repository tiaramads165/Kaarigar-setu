#!/usr/bin/env python3
"""End-to-end test against a RUNNING stack (blueprint Phase 5 deliverable + Phase 8 integration test).

    ./scripts/dev.sh                      # in another terminal
    python3 scripts/e2e.py                # exits non-zero if anything fails

Needs only the Python standard library. It uploads synthetic tones, not speech, so start the stack with the labelled
test-fixture transcriber:  STT_PROVIDER=stub ./scripts/dev.sh   (real Whisper would, correctly, hear silence and refuse the audio). OTPs are read from the Java server's console log (the dev stub sender).
"""
from __future__ import annotations

import hashlib
import http.cookiejar
import json
import os
import random
import re
import struct
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = os.environ.get("BASE_URL", "http://localhost:8090")
LOG = Path(os.environ.get("JAVA_LOG", "/tmp/ks-team-dev.log"))
FAILED: list[str] = []


def env_file() -> dict[str, str]:
    values = {}
    path = ROOT / ".env.local"
    if path.exists():
        for line in path.read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                key, value = line.split("=", 1)
                values[key] = value.strip("'")
    return values


def solve_pow(challenge: str, bits: int) -> str:
    """The browser's proof-of-work, in Python: a counter whose SHA-256 with the puzzle starts with `bits` zero bits (see static/js/pow.js)."""
    counter = 0
    while True:
        digest = hashlib.sha256(f"{challenge}:{counter}".encode()).digest()
        zeros = 0
        for byte in digest:
            if byte == 0:
                zeros += 8
                continue
            zeros += 8 - byte.bit_length()
            break
        if zeros >= bits:
            return str(counter)
        counter += 1


class Client:
    """One browser-like session: cookie jar + CSRF token."""

    def __init__(self, name: str, ip: str | None = None):
        self.name = name
        # Distinct simulated client address and device per session (needs TRUST_FORWARDED_FOR=true on the server).
        self.ip = ip or f"10.{random.randint(1, 250)}.{random.randint(1, 250)}.{random.randint(1, 250)}"
        self.user_agent = f"e2e-{name}-{random.randint(0, 10**9)}"
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar),
                                                  _NoRedirect())
        self.csrf = ""
        self.refresh_csrf()

    def refresh_csrf(self) -> None:
        status, body = self.request("GET", "/api/auth/session")
        self.csrf = body["csrfToken"]

    def request(self, method: str, path: str, body=None, raw: bytes | None = None, content_type: str | None = None,
                csrf: bool = True):
        headers = {"X-Forwarded-For": self.ip, "User-Agent": self.user_agent}
        if csrf and self.csrf:
            headers["X-CSRF-Token"] = self.csrf
        data = None
        if raw is not None:
            data = raw
            headers["Content-Type"] = content_type or "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status, self._decode(response)
        except urllib.error.HTTPError as error:
            return error.code, self._decode(error)

    @staticmethod
    def _decode(response):
        payload = response.read()
        content_type = response.headers.get("Content-Type", "")
        if "json" in content_type:
            return json.loads(payload or b"{}")
        if content_type.startswith("image/"):
            return payload
        return payload.decode("utf-8", "replace")

    def request_code(self, role: str, phone: str):
        """Asks for a sign-in code the way the browser does: a browser the server does not know yet is given a puzzle (428); solve it and ask again."""
        body = {"role": role, "phone": phone}
        status, reply = self.request("POST", "/api/auth/otp/request", body)
        for _ in range(3):
            if status != 428:
                break
            body = {"role": role, "phone": phone, "pow": {"challenge": reply["challenge"], "counter": solve_pow(reply["challenge"], reply["bits"])}}
            status, reply = self.request("POST", "/api/auth/otp/request", body)
        return status, reply

    def login(self, role: str, phone: str) -> dict:
        status, _ = self.request_code(role, phone)
        assert status == 200, f"otp request failed: {status}"
        code = last_otp(phone[-4:], "login")
        status, body = self.request("POST", "/api/auth/otp/verify", {"role": role, "phone": phone, "code": code})
        assert status == 200, f"login failed: {status} {body}"
        self.csrf = body["csrfToken"]
        return body


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def last_otp(last4: str, purpose: str, wait: float = 3.0) -> str:
    pattern = re.compile(rf"\[CONSOLE OTP\] purpose={purpose} phone=\*{{6}}{last4} code=(\d{{6}})")
    deadline = time.time() + wait
    while time.time() < deadline:
        if LOG.exists():
            matches = pattern.findall(LOG.read_text(errors="replace"))
            if matches:
                return matches[-1]
        time.sleep(0.2)
    raise AssertionError(f"no OTP for ...{last4} ({purpose}) in {LOG}")


def wav(seconds: float = 2.0, rate: int = 16000) -> bytes:
    data = b"\x00\x00" * int(rate * seconds)
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


def speech_wav(seed: int, seconds: float = 4.0, rate: int = 16000, noise_db: float | None = None, volume: float = 0.8, trim_ms: int = 0) -> bytes:
    """A speech-LIKE recording (syllables with a gliding pitch, vowel formants and noise bursts between them; standard library only). Different seeds are different
    'speakers'; the same seed is the same recording. noise_db / volume / trim_ms make the copy a replay would make (noise, another level, a later start)."""
    import math
    rng = random.Random(seed)
    total = int(seconds * rate)
    out = [0.0] * total
    cursor = 0
    while cursor < total:
        n = min(int(rng.uniform(0.12, 0.26) * rate), total - cursor)
        f0 = rng.uniform(95, 230)
        slope = rng.uniform(-0.15, 0.15)
        formants = [rng.uniform(lo, hi) for lo, hi in ((300, 800), (800, 2300), (2200, 3300))]
        harmonics = []
        for k in range(1, 30):
            frequency = k * f0
            if frequency > 3800:
                break
            gain = sum(math.exp(-((frequency - f) / 160.0) ** 2) for f in formants)
            if gain > 0.03:
                harmonics.append((k, gain))
        loud = rng.uniform(0.5, 1.0)
        phase = 0.0
        for i in range(n):
            phase += 2 * math.pi * f0 * (1 + slope * i / (n * rate)) / rate
            envelope = math.sin(math.pi * i / n) ** 1.5
            out[cursor + i] += sum(g * math.sin(k * phase) for k, g in harmonics) * envelope * loud
        cursor += n
        pause = int(rng.uniform(0.02, 0.09) * rate)
        for i in range(min(pause, total - cursor)):
            out[cursor + i] += rng.gauss(0, 0.05)
        cursor += pause
    peak = max(abs(v) for v in out) or 1.0
    if noise_db is not None:
        power = sum(v * v for v in out) / len(out) / (peak * peak)
        sigma = math.sqrt(power / 10 ** (noise_db / 10))
        noise = random.Random(seed + 1_000_003)
        out = [v / peak + noise.gauss(0, sigma) for v in out]
        peak = max(abs(v) for v in out) or 1.0
    samples = [max(-1.0, min(1.0, v * volume / peak)) for v in out]
    samples = samples[int(trim_ms * rate / 1000):]
    data = b"".join(struct.pack("<h", int(v * 32767)) for v in samples)
    return (b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
            + b"data" + struct.pack("<I", len(data)) + data)


def with_marker(audio: bytes, marker: bytes) -> bytes:
    """Puts a test marker into the first samples of the recording (the labelled test transcriber reads it; real speech recognition never would). The WAV stays valid."""
    at = audio.find(b"data") + 8
    return audio[:at] + marker + audio[at + len(marker):]


def challenge_for(client, token: str, question: int) -> tuple[int, dict]:
    return client.request("GET", f"/api/assessment/challenge?attempt={token}&question={question}")


RUN = random.randint(1000, 90_000_000)     # every run uses recordings and words nobody has used before, so a re-run against the same database starts clean


def answer(client, token: str, question: int, audio: bytes | None = None, text: int | None = None, say: bool = True):
    """Answers one question the way the page does: ask for this question's spoken code, say it (in the test fixture: the marker), send the recording with the code."""
    status, issued = challenge_for(client, token, question)
    assert status == 200, f"challenge failed: {status} {issued}"
    marker = b""
    if text is not None:
        marker += f"KSWORDS:{text:09d}".encode()
    audio = audio if audio is not None else wav()
    if issued.get("required"):
        if say:
            marker += b"KSCODE:" + issued["code"].encode()
        audio = with_marker(audio, marker)
        return client.request("POST", f"/api/assessment/submit-answer?attempt={token}&question={question}&challenge={issued['token']}", raw=audio, content_type="audio/wav")
    return client.request("POST", f"/api/assessment/submit-answer?attempt={token}&question={question}", raw=with_marker(audio, marker) if marker else audio, content_type="audio/wav")


_used_suffixes: set[str] = set()


def new_phone() -> str:
    while True:
        suffix = f"{random.randint(0, 9999):04d}"
        if suffix not in _used_suffixes:
            _used_suffixes.add(suffix)
            return f"9{random.randint(100, 999)}{random.randint(10, 99)}{suffix}"


def check(label: str, condition: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if condition else 'FAIL'}] {label}" + (f"  ({detail})" if detail and not condition else ""))
    if not condition:
        FAILED.append(label)


def offline_verify(document: dict, keys: dict, revocations: dict | None = None) -> int:
    """Exit code of scripts/verify_credential.py, which shares no code with the application: file + public key (+ revocation list) only."""
    import subprocess
    import tempfile
    key = next(k["publicKeyPem"] for k in keys["keys"] if k["keyId"] == document["proof"]["keyId"])
    with tempfile.TemporaryDirectory() as tmp:
        Path(tmp, "doc.json").write_text(json.dumps(document), encoding="utf-8")
        Path(tmp, "key.pem").write_text(key)
        command = [sys.executable, str(ROOT / "scripts" / "verify_credential.py"), str(Path(tmp, "doc.json")), "--public-key", str(Path(tmp, "key.pem"))]
        if revocations is not None:
            Path(tmp, "rev.json").write_text(json.dumps(revocations), encoding="utf-8")
            command += ["--revocations", str(Path(tmp, "rev.json"))]
        return subprocess.run(command, capture_output=True, text=True, timeout=60).returncode


def section(title: str) -> None:
    print(f"\n== {title}")


def register_worker(trade="electrician", language="hi") -> tuple[str, int]:
    phone = new_phone()
    anon = Client("anon")
    status, body = anon.request("POST", "/api/worker/register",
                                {"name": "Test Worker", "phone": phone, "trade": trade, "language": language, "consent": True})
    assert status == 201, f"register failed {status} {body}"
    return phone, body["workerId"]


def register_employer(name="Test Employer") -> tuple[str, int]:
    phone = new_phone()
    status, body = Client("anon").request("POST", "/api/employer/register", {"name": name, "phone": phone, "consent": True})
    assert status == 201, f"employer register failed {status} {body}"
    return phone, body["employerId"]


def moderator_login(client: Client, username: str = "moderator", last4: str = "0901", password_key: str = "MODERATOR_PASSWORD_PLAIN") -> Client:
    """Sign a named moderator in (password, then the code sent to that person's own phone). The development accounts are made by scripts/moderator-admin.sh."""
    status, body = client.request("POST", "/api/auth/moderator/login", {"username": username, "password": env_file().get(password_key, "")})
    assert status == 200, f"moderator login failed: {status} {body}"
    status, body = client.request("POST", "/api/auth/moderator/verify", {"code": last_otp(last4, "moderator")})
    assert status == 200, f"moderator verify failed: {status} {body}"
    client.csrf = body["csrfToken"]
    return client


def main() -> int:
    env = env_file()

    section("1. Health, headers, public pages")
    anon = Client("anon")
    status, _ = anon.request("GET", "/health")
    check("Java service healthy", status == 200)
    status, page = anon.request("GET", "/")
    check("login page renders (JSP)", status == 200 and "Sign in" in page)
    with urllib.request.urlopen(BASE + "/") as response:
        csp = response.headers.get("Content-Security-Policy", "")
        check("CSP forbids inline script", "script-src 'self'" in csp and "'unsafe-inline'" not in csp)
        check("X-Frame-Options DENY + nosniff", response.headers.get("X-Frame-Options") == "DENY" and response.headers.get("X-Content-Type-Options") == "nosniff")
        cookie = response.headers.get("Set-Cookie", "")
        check("session cookie HttpOnly + SameSite=Strict", "HttpOnly" in cookie and "SameSite=Strict" in cookie, cookie)

    section("2. Registration needs consent; duplicate and invalid input are refused")
    status, body = anon.request("POST", "/api/worker/register", {"name": "No Consent", "phone": new_phone(), "trade": "electrician", "language": "hi", "consent": False})
    check("no consent -> 403 consent_required", status == 403 and body.get("error") == "consent_required")
    worker_phone, worker_id = register_worker()
    status, body = anon.request("POST", "/api/worker/register", {"name": "Dup", "phone": worker_phone, "trade": "electrician", "language": "hi", "consent": True})
    check("duplicate phone -> 409", status == 409)
    status, body = anon.request("POST", "/api/worker/register", {"name": "Inj", "phone": "9876543210' OR '1'='1", "trade": "electrician", "language": "hi", "consent": True})
    check("SQL-injection payload in phone rejected (400)", status == 400)
    status, body = anon.request("POST", "/api/worker/register", {"name": "'; DROP TABLE workers;--", "phone": new_phone(), "trade": "electrician", "language": "hi", "consent": True})
    check("SQL-injection payload in name is stored as plain text (201)", status == 201)

    section("3. CSRF, authentication and role boundaries")
    bare = Client("bare")
    status, body = bare.request("POST", "/api/auth/otp/request", {"role": "WORKER", "phone": worker_phone}, csrf=False)
    check("POST without CSRF token -> 403", status == 403 and body.get("error") == "csrf")
    status, body = anon.request("GET", "/api/worker/me/passport")
    check("worker API without login -> 401", status == 401)
    worker = Client("worker")
    session_before = next((c.value for c in worker.jar if c.name == "KSSID"), "")
    login_body = worker.login("WORKER", worker_phone)
    session_after = next((c.value for c in worker.jar if c.name == "KSSID"), "")
    check("worker OTP login", login_body["role"] == "WORKER")
    check("login issued a NEW session id (fixation defence)", bool(session_before) and bool(session_after) and session_before != session_after,
          f"before={session_before[:6]} after={session_after[:6]}")
    employer_phone, employer_id = register_employer()
    employer = Client("employer")
    employer.login("EMPLOYER", employer_phone)
    status, _ = employer.request("GET", "/api/worker/me/passport")
    check("employer cannot call worker API -> 403", status == 403)
    status, _ = worker.request("POST", "/api/attestation/request-otp", {"workerPhone": worker_phone})
    check("worker cannot call employer API -> 403", status == 403)
    status, _ = worker.request("GET", "/api/moderator/flagged")
    check("worker cannot call moderator API -> 403", status == 403)
    status, _ = worker.request("GET", "/api/unknown/route")
    check("unlisted /api route is deny-by-default for anonymous", Client("a").request("GET", "/api/unknown/route")[0] in (401, 403))

    # the dev-only demo sign-in must never open a real account (it answers 404 when disabled, 403 for a non-demo account when enabled)
    status, _ = Client("demo-probe").request("POST", "/api/auth/demo-login", {"role": "WORKER", "id": worker_id})
    check("demo sign-in refuses a real (non-demo) account", status in (403, 404), str(status))
    status, _ = Client("demo-probe2").request("POST", "/api/auth/demo-login", {"role": "MODERATOR", "id": 0})
    check("demo sign-in can never reach the moderator role", status in (400, 404), str(status))
    section("4. OTP lockout (5 wrong attempts) and unknown accounts")
    victim_phone, _ = register_worker()
    brute = Client("brute")
    brute.request_code("WORKER", victim_phone)
    real = last_otp(victim_phone[-4:], "login")
    wrong = "000000" if real != "000000" else "111111"
    statuses = [brute.request("POST", "/api/auth/otp/verify", {"role": "WORKER", "phone": victim_phone, "code": wrong})[0] for _ in range(5)]
    check("5 wrong codes all rejected", statuses == [401] * 5, str(statuses))
    status, body = brute.request("POST", "/api/auth/otp/verify", {"role": "WORKER", "phone": victim_phone, "code": real})
    check("correct code is refused after lockout", status == 401)
    status, body = Client("ghost").request_code("WORKER", new_phone())
    check("unregistered number gets the same generic 200 (no enumeration)", status == 200)
    status, unknown_puzzle = Client("ghost2").request("POST", "/api/auth/otp/request", {"role": "WORKER", "phone": new_phone()})
    status2, known_puzzle = Client("ghost3").request("POST", "/api/auth/otp/request", {"role": "WORKER", "phone": victim_phone})
    check("a new browser is asked for a proof-of-work puzzle (428) for a known and an unknown number alike, so the puzzle gives nothing away",
          status == status2 == 428 and unknown_puzzle.get("bits") == known_puzzle.get("bits") and unknown_puzzle.get("challenge") and known_puzzle.get("challenge"), f"{status} {status2}")

    section("5. Voice assessment: 10 answers -> ML transcribe + score -> persisted")
    status, started = worker.request("POST", "/api/assessment/start", {})
    check("assessment started with 10 questions", status == 201 and len(started["questions"]) == 10, str(status))
    check("question list does not leak grader criteria", "criteria" not in json.dumps(started) and "keywords" not in json.dumps(started))
    token = started["attemptToken"]
    status, body = worker.request("POST", f"/api/assessment/submit-answer?attempt={token}&question=3", raw=wav(), content_type="audio/wav")
    check("answering out of order -> 409", status == 409)
    status, body = worker.request("POST", f"/api/assessment/submit-answer?attempt={token}&question=1", raw=b"not a wav file at all, just text", content_type="audio/wav")
    check("non-WAV upload -> 400", status == 400)
    status, body = worker.request("POST", f"/api/assessment/submit-answer?attempt=forged.{int(time.time())}.AAAA&question=1", raw=wav(), content_type="audio/wav")
    check("forged attempt token -> 400", status == 400)
    last = {}
    for q in range(1, 11):
        status, last = answer(worker, token, q)
        if status != 201:
            check(f"answer {q} accepted", False, f"{status} {last}")
            break
    else:
        check("10 answers scored", last.get("finished") is True and last.get("skillScore") is not None, str(last))
    status, body = worker.request("POST", f"/api/assessment/submit-answer?attempt={token}&question=1", raw=wav(), content_type="audio/wav")
    check("re-answering a finished attempt -> 409", status == 409)
    status, passport = worker.request("GET", "/api/worker/me/passport")
    check("passport shows the skill score", status == 200 and passport["skillScore"] is not None and len(passport["latestAssessment"]) == 10, str(passport)[:200])
    check("passport shows a skill band and a per-area breakdown", passport.get("skillBand") in ("PASS", "BORDERLINE", "NEEDS_REVIEW") and len(passport.get("skillAreas", {})) >= 4
          and all(0 <= v <= 100 for v in passport["skillAreas"].values()), str(passport)[:300])
    skill_before = passport["skillScore"]

    section("5a. Replay-voice detection: a recording of a good answer cannot be played in again")
    def fresh_worker(label: str) -> Client:
        phone, _ = register_worker()
        client = Client(label)
        client.login("WORKER", phone)
        return client

    def begin(client: Client) -> str:
        status, started = client.request("POST", "/api/assessment/start", {})
        assert status == 201, f"start failed {status}"
        return started["attemptToken"]

    good, thief, third = fresh_worker("good"), fresh_worker("thief"), fresh_worker("third")
    t_good, t_thief, t_third = begin(good), begin(thief), begin(third)

    status, c1 = challenge_for(good, t_good, 1)
    check("the spoken code for the next question: three digits, a token, a lifetime", status == 200 and c1.get("required") is True and re.fullmatch(r"[1-9]{3}", c1.get("code", "")) and c1.get("token") and c1.get("minutes", 0) > 0, str(c1))
    status, _ = challenge_for(good, t_good, 2)
    check("the code of a later question cannot be collected in advance -> 409", status == 409)
    status, _ = challenge_for(third, t_good, 1)
    check("another worker's attempt token gets no code -> 400", status == 400)
    status, _ = Client("anon").request("GET", f"/api/assessment/challenge?attempt={t_good}&question=1")
    check("asking for a code without being signed in -> 401", status == 401)

    audio_a = speech_wav(RUN + 101)
    submit = lambda c, t, q, audio, token: c.request("POST", f"/api/assessment/submit-answer?attempt={t}&question={q}" + (f"&challenge={token}" if token is not None else ""), raw=audio, content_type="audio/wav")
    status, body = submit(good, t_good, 1, with_marker(audio_a, b"KSCODE:" + c1["code"].encode()), None)
    check("an answer without a code -> 428 challenge_required", status == 428 and body.get("error") == "challenge_required", f"{status} {body}")
    status, body = submit(good, t_good, 1, with_marker(audio_a, b"KSCODE:" + c1["code"].encode()), "1.2.forged")
    check("a forged code token -> 428", status == 428, f"{status} {body}")
    status, c_other = challenge_for(third, t_third, 1)
    status, body = submit(good, t_good, 1, with_marker(audio_a, b"KSCODE:" + c_other["code"].encode()), c_other["token"])
    check("another worker's code token does not work for me -> 428", status == 428, f"{status} {body}")

    status, body = submit(good, t_good, 1, audio_a, c1["token"])
    check("recording without the code said -> 422 code_not_heard (try 1 of 3)", status == 422 and body.get("error") == "code_not_heard", f"{status} {body}")
    status, body = submit(good, t_good, 1, with_marker(audio_a, b"KSCODE:" + c1["code"].encode()), c1["token"])
    check("saying the code on a later try is accepted and not flagged", status == 201 and body.get("needsReview") is False, f"{status} {body}")
    good_first = body

    status, body = answer(thief, t_thief, 1, audio=audio_a)
    check("the SAME recording from another worker (own fresh code) is accepted but flagged: no score, held for review", status == 201 and body.get("needsReview") is True and float(body["score"]) == 0.0, f"{status} {body}")
    status, body = answer(third, t_third, 1, audio=speech_wav(RUN + 101, noise_db=18, volume=0.45, trim_ms=40))
    check("a copy with noise, a different volume and a trimmed start is still found and flagged", status == 201 and body.get("needsReview") is True and float(body["score"]) == 0.0, f"{status} {body}")
    status, body = answer(good, t_good, 2, audio=speech_wav(RUN + 202))
    check("a different recording is clear", status == 201 and body.get("needsReview") is False, f"{status} {body}")
    status, body = answer(good, t_good, 3, audio=speech_wav(RUN + 202))
    check("the same recording used for another question of the same attempt is flagged", status == 201 and body.get("needsReview") is True, f"{status} {body}")
    status, body = answer(good, t_good, 4, audio=speech_wav(RUN + 303), text=RUN)
    check("long words answered by the good worker: clear", status == 201 and body.get("needsReview") is False, f"{status} {body}")
    status, body = answer(thief, t_thief, 2, audio=speech_wav(RUN + 404), text=RUN)
    check("the same WORDS from someone else on a different recording (a re-voiced script) are flagged by the text check", status == 201 and body.get("needsReview") is True and float(body["score"]) == 0.0, f"{status} {body}")

    status, c_end = challenge_for(third, t_third, 2)
    for tries in (1, 2):
        status, body = submit(third, t_third, 2, speech_wav(RUN + 505), c_end["token"])
        check(f"the code not heard, try {tries}: refused with 422 and nothing stored", status == 422, f"{status} {body}")
    status, body = submit(third, t_third, 2, speech_wav(RUN + 505), c_end["token"])
    check("the code not heard on the third try: stored and held for review", status == 201 and body.get("needsReview") is True, f"{status} {body}")

    # the whole attempt of the thief, then the credential: a flagged answer blocks it
    for q in range(3, 11):
        status, body = answer(thief, t_thief, q, audio=speech_wav(RUN + 600 + q))
        if status != 201:
            check(f"thief answer {q} accepted", False, f"{status} {body}")
            break
    status, body = thief.request("POST", "/api/credential/issue", {})
    check("a credential cannot be issued while an answer is held for replay review -> 409", status == 409, f"{status} {body}")

    section("6. Employer attests -> OTP -> fraud-check -> trust graph -> credential re-issued")
    # the feedback fields are required, and out-of-range ratings are refused before anything is stored
    status, _ = employer.request("POST", "/api/attestation/request-otp", {"workerPhone": worker_phone, "jobRole": "House wiring", "workDate": time.strftime("%Y-%m-%d")})
    check("an attestation without ratings -> 400", status == 400)
    status, _ = employer.request("POST", "/api/attestation/request-otp", {"workerPhone": worker_phone, "jobRole": "House wiring", "workDate": time.strftime("%Y-%m-%d"), "qualityRating": 9, "punctualityRating": 4, "conductRating": 5})
    check("a rating of 9 -> 400", status == 400)
    status, body = employer.request("POST", "/api/attestation/request-otp",
                                    {"workerPhone": worker_phone, "jobRole": "House wiring", "durationDays": 12, "wouldHireAgain": True, "workDate": time.strftime("%Y-%m-%d"),
                                     "qualityRating": 5, "punctualityRating": 4, "conductRating": 5})
    check("attestation OTP requested", status == 201 and body["status"] == "OTP_PENDING", f"{status} {body}")
    attestation_id = body.get("attestationId")
    status, body = employer.request("POST", "/api/attestation/verify-otp", {"attestationId": attestation_id, "code": "000000"})
    check("wrong attestation code rejected", status == 401)
    code = last_otp(employer_phone[-4:], "attestation")
    status, body = employer.request("POST", "/api/attestation/verify-otp", {"attestationId": attestation_id, "code": code})
    check("correct code -> APPROVED", status == 200 and body["status"] == "APPROVED", f"{status} {body}")
    status, again = employer.request("POST", "/api/attestation/verify-otp", {"attestationId": attestation_id, "code": code})
    check("OTP is single-use / attestation decided once -> 409", status == 409)
    status, dup = employer.request("POST", "/api/attestation/request-otp",
                                   {"workerPhone": worker_phone, "jobRole": "Again", "workDate": time.strftime("%Y-%m-%d"), "qualityRating": 5, "punctualityRating": 4, "conductRating": 5})
    check("same employer + worker again -> 409 duplicate", status == 409 and dup.get("error") == "duplicate")
    status, own = Client("x").request("POST", "/api/attestation/request-otp", {"workerPhone": worker_phone})
    status, passport = worker.request("GET", "/api/worker/me/passport")
    check("passport has a trust band and an explanation", passport.get("trustBand") in ("EXCELLENT", "GOOD", "FAIR", "BUILDING") and passport.get("trustReport", {}).get("employerCount") == 1
          and passport["trustReport"]["dimensionAverages"] == {"quality": 5.0, "punctuality": 4.0, "conduct": 5.0} and len(passport["trustReport"].get("tips", [])) >= 1, str(passport.get("trustReport"))[:300])
    check("trust score updated, 1 verified employer", passport["verifiedEmployerCount"] == 1 and passport["trustScore"] is not None, str(passport)[:220])
    check("credential was re-issued automatically", passport["latestCredentialRef"] is not None)

    section("7. Credential: public verification, QR, tamper, revoke")
    ref = passport["latestCredentialRef"]
    status, body = Client("public").request("GET", f"/api/credential/{ref}/verify")
    check("public verify (no login) -> VALID", status == 200 and body["state"] == "VALID", str(body)[:200])
    check("the signed credential carries the skill band", body["credential"].get("skillBand") == passport["skillBand"], str(body)[:200])
    check("credential never contains name or phone", "Test Worker" not in json.dumps(body) and worker_phone not in json.dumps(body))
    status, png = Client("public").request("GET", f"/api/credential/{ref}/qr")
    check("QR code is a PNG", status == 200 and isinstance(png, bytes) and png[:4] == b"\x89PNG")
    status, html = Client("public").request("GET", f"/verify/{ref}")
    check("verify page renders 'VALID'", status == 200 and "VALID" in html)
    status, doc = worker.request("GET", f"/api/credential/{ref}/download")
    document = doc if isinstance(doc, dict) else json.loads(doc)
    payload_text = json.dumps(document["credential"], separators=(",", ":"))
    signature = document["proof"]["signature"]
    check("downloaded signed document has a proof", bool(signature))
    _, keys = Client("public").request("GET", "/api/credential/keys")
    check("public key list names the signing key as active", any(k["keyId"] == document["proof"]["keyId"] and k["status"] == "active" for k in keys["keys"]))
    check("the credential is signed in canonical form (RFC 8785)", document["proof"].get("canonicalization") == "RFC8785-JCS", str(document["proof"])[:120])
    check("independent offline verifier accepts it (file + public key only)", offline_verify(document, keys) == 0)
    edited = json.loads(json.dumps(document))
    edited["credential"]["credentialSubject"]["skillScore"] = 99.9
    check("independent offline verifier rejects an edited score", offline_verify(edited, keys) == 1)
    rewritten = json.loads(json.dumps(document, indent=3, sort_keys=True))        # another tool re-wrote the file: whitespace, member order, 82.50 -> 82.5
    check("a re-written copy of the file (new whitespace and order) still verifies offline", offline_verify(rewritten, keys) == 0)
    status, body = Client("public").request("POST", "/api/credential/verify", {"payload": payload_text.replace('"skillScore":', '"skillScore":9'), "signature": signature})
    check("TAMPERED payload fails verification", body.get("state") == "INVALID_SIGNATURE")
    status, body = Client("public").request("POST", "/api/credential/verify", {"payload": "{}", "signature": "AAAA"})
    check("garbage signature fails verification (no exception)", status == 200 and body.get("state") == "INVALID_SIGNATURE")
    # live presentation code: only the owner's session can mint one; the verification page says whether the code is live
    status, _ = Client("public").request("GET", f"/api/credential/{ref}/live-qr")
    check("anonymous cannot mint a live code -> 401", status == 401)
    status, _ = employer.request("GET", f"/api/credential/{ref}/live-qr")
    check("employer cannot mint a live code -> 403", status == 403)
    stranger_phone, _ = register_worker()
    stranger = Client("stranger")
    stranger.login("WORKER", stranger_phone)
    status, _ = stranger.request("GET", f"/api/credential/{ref}/live-qr")
    check("another worker cannot mint a live code for my credential -> 404", status == 404)
    status, png = worker.request("GET", f"/api/credential/{ref}/live-qr")
    check("owner gets a live-code QR image", status == 200 and isinstance(png, bytes) and png[:4] == b"\x89PNG")
    status, plain = Client("public").request("GET", f"/api/credential/{ref}/verify")
    check("without a code the verification says presentation NONE", plain.get("presentation", {}).get("state") == "NONE", str(plain)[:160])
    status, page = Client("public").request("GET", f"/verify/{ref}")
    check("the verify page for the fixed link says it is a static link", "Static link" in page)
    status, page = Client("public").request("GET", f"/verify/{ref}?p=1.AAAA")
    check("a made-up live code is flagged as not trustworthy", "Do not trust this code" in page)
    status, body = employer.request("POST", f"/api/credential/{ref}/revoke", {"reason": "x"})
    check("employer cannot revoke -> 403", status == 403)
    status, body = worker.request("POST", f"/api/credential/{ref}/revoke", {"reason": "lost phone"})
    check("owner can revoke", status == 200 and body["revoked"] is True)
    status, body = Client("public").request("GET", f"/api/credential/{ref}/verify")
    check("revoked credential -> REVOKED", body.get("state") == "REVOKED")
    _, revocations = Client("public").request("GET", "/api/credential/revocations")
    check("the signed revocation list names it", ref in [r["ref"] for r in revocations["list"]["revoked"]])
    check("offline verifier rejects it once the revocation list is consulted", offline_verify(document, keys, revocations) == 1)
    status, new = worker.request("POST", "/api/credential/issue", {})
    check("a fresh credential can be issued", status == 201)
    status, old = Client("public").request("GET", f"/api/credential/{new['ref']}/verify")
    check("new credential VALID", old.get("state") == "VALID")
    other, _ = register_worker()
    other_client = Client("other")
    other_client.login("WORKER", other)
    status, _ = other_client.request("GET", f"/api/credential/{new['ref']}/download")
    check("another worker cannot download my credential -> 404", status == 404)

    section("8. Fraud screening: a burst of attestations is flagged for a moderator")
    burst_phone, burst_id = register_employer("Burst Employer")
    burst = Client("burst", ip="198.51.100.77")        # one IP and one device for all seven requests
    burst.user_agent = "e2e-burst-device"
    burst.login("EMPLOYER", burst_phone)
    decisions = []
    for index in range(7):
        w_phone, _ = register_worker()
        status, body = burst.request("POST", "/api/attestation/request-otp",
                                     {"workerPhone": w_phone, "jobRole": f"Job {index}", "workDate": time.strftime("%Y-%m-%d"), "qualityRating": 5, "punctualityRating": 4, "conductRating": 5})
        if status != 201:
            check(f"burst request {index}", False, f"{status} {body}")
            break
        code = last_otp(burst_phone[-4:], "attestation")
        status, body = burst.request("POST", "/api/attestation/verify-otp", {"attestationId": body["attestationId"], "code": code})
        decisions.append(body.get("status"))
    check("rapid-fire attestations from one employer/IP are flagged", "PENDING_REVIEW" in decisions, str(decisions))

    section("9. Moderator login (password + OTP) and decision")
    mod = Client("mod")
    status, body = mod.request("POST", "/api/auth/moderator/login", {"username": "moderator", "password": "wrong-password"})
    check("wrong moderator password -> 401", status == 401)
    status, body = mod.request("POST", "/api/auth/moderator/login", {"username": "moderator", "password": env.get("MODERATOR_PASSWORD_PLAIN", "")})
    check("moderator password accepted, OTP step required", status == 200 and body.get("otpRequired") is True, f"{status} {body}")
    status, _ = mod.request("GET", "/api/moderator/flagged")
    check("password alone does NOT grant moderator access", status in (401, 403))
    mcode = last_otp("0901", "moderator")
    status, body = mod.request("POST", "/api/auth/moderator/verify", {"code": mcode})
    check("moderator OTP -> logged in", status == 200 and body["role"] == "MODERATOR", f"{status} {body}")
    mod.csrf = body["csrfToken"]            # the token rotates on login
    status, queue = mod.request("GET", "/api/moderator/flagged")
    flagged = queue.get("flagged", [])
    check("flagged queue is not empty", status == 200 and len(flagged) >= 1, str(queue)[:200])
    if flagged:
        check("queue exposes no phone numbers or names", not re.search(r"\b[6-9]\d{9}\b", json.dumps(flagged)) and "Test" not in json.dumps(flagged))
        status, body = mod.request("POST", "/api/moderator/decision", {"flagId": flagged[0]["flagId"], "decision": "VALID"})
        check("moderator approves a flagged attestation", status == 200 and body["approved"] is True, f"{status} {body}")
        status, body = mod.request("POST", "/api/moderator/decision", {"flagId": flagged[0]["flagId"], "decision": "FRAUD"})
        check("a flag cannot be decided twice -> 409", status == 409)
        if len(flagged) > 1:
            status, body = mod.request("POST", "/api/moderator/decision", {"flagId": flagged[1]["flagId"], "decision": "FRAUD"})
            check("moderator rejects another as fraud", status == 200 and body["approved"] is False)
    status, body = mod.request("POST", "/api/moderator/decision", {"flagId": 1, "decision": "MAYBE"})
    check("invalid decision value -> 400", status == 400)
    status, audit = mod.request("GET", "/api/moderator/audit-check")
    check("audit file hash chain verifies", status == 200 and audit["fileChainValid"] is True, str(audit))
    check("audit check also compares the file with the anchors kept in the database", audit.get("anchorsChecked", 0) >= 1 and audit.get("databaseChainValid") is True, str(audit))
    status, sec = mod.request("GET", "/api/moderator/security")
    check("security monitor answers for a moderator", status == 200 and sec.get("status") in ("OK", "WARN", "CRITICAL") and sec["integrity"]["fileChainValid"] is True, str(sec)[:200])
    check("it saw the CSRF rejections from earlier in this run", sec["counts"]["CSRF_REJECTED"]["last24h"] >= 1, str(sec["counts"])[:200])
    check("it saw the role violations from earlier in this run", sec["counts"]["ACCESS_DENIED"]["last24h"] >= 1, str(sec["counts"])[:200])
    check("it lists recent security events without phone numbers", len(sec["recent"]) >= 1 and not re.search(r"\b[6-9]\d{9}\b", json.dumps(sec["recent"])))
    status, _ = worker.request("GET", "/api/moderator/security")
    check("a worker cannot read the security monitor -> 403", status == 403)

    section("9a. Named moderators: every action is a person's, and the second person is a different account")
    status, me = mod.request("GET", "/api/moderator/me")
    check("the console can ask who is signed in: a named account", status == 200 and me["username"] == "moderator" and me["moderatorId"] >= 1, str(me))
    check("and which actions need a second person", set(me["twoPersonActions"]) == {"VET_EMPLOYER", "DISPUTE_DECISION"}, str(me))
    status, _ = worker.request("GET", "/api/moderator/me")
    check("a worker cannot call the moderator identity endpoint -> 403", status == 403)
    status, _ = worker.request("GET", "/api/moderator/pending")
    check("a worker cannot read the approvals queue -> 403", status == 403)
    mod2 = moderator_login(Client("mod2"), "moderator2", "0902", "MODERATOR2_PASSWORD_PLAIN")
    status, me2 = mod2.request("GET", "/api/moderator/me")
    check("a second, different moderator account signs in", status == 200 and me2["username"] == "moderator2" and me2["moderatorId"] != me["moderatorId"], str(me2))
    status, wrong = Client("probe-a").request("POST", "/api/auth/moderator/login", {"username": "moderator2", "password": "wrong-password"})
    status2, nobody = Client("probe-b").request("POST", "/api/auth/moderator/login", {"username": "nobody-at-all", "password": "wrong-password"})
    check("an unknown username looks exactly like a wrong password", status == status2 == 401 and wrong == nobody, f"{status} {wrong} / {status2} {nobody}")
    status, swapped = Client("probe-c").request("POST", "/api/auth/moderator/login", {"username": "Moderator2", "password": env.get("MODERATOR_PASSWORD_PLAIN", "")})
    check("one moderator's password does not open another's account", status == 401, str(status))

    section("9b. A ring of fake employers: five unvetted accounts cannot give the top band, one moderator vetting can")
    ring_phone, _ = register_worker()
    ring_worker = Client("ring-worker")
    ring_worker.login("WORKER", ring_phone)
    ring_ids, ring_decisions = [], []
    for n in range(5):
        e_phone, e_id = register_employer(f"Ring Employer {n}")
        e_client = Client(f"ring-employer-{n}")
        e_client.login("EMPLOYER", e_phone)
        status, made = e_client.request("POST", "/api/attestation/request-otp",
                                        {"workerPhone": ring_phone, "jobRole": "Panel wiring", "durationDays": 30, "wouldHireAgain": True,
                                         "workDate": time.strftime("%Y-%m-%d", time.gmtime(time.time() - 86400)), "qualityRating": 5, "punctualityRating": 5, "conductRating": 5})
        status, decided = e_client.request("POST", "/api/attestation/verify-otp", {"attestationId": made["attestationId"], "code": last_otp(e_phone[-4:], "attestation")})
        ring_ids.append(e_id)
        ring_decisions.append(decided.get("status"))
    check("the five fake employers' confirmations all pass OTP and the fraud model", ring_decisions == ["APPROVED"] * 5, str(ring_decisions))
    status, ring_passport = ring_worker.request("GET", "/api/worker/me/passport")
    check("five unvetted employers with perfect ratings: trust is held at exactly 79.99 (the hold is what stops it, not the ratings)",
          ring_passport.get("trustBand") == "GOOD" and abs(float(ring_passport["trustScore"]) - 79.99) < 0.01, f"{ring_passport.get('trustBand')} {ring_passport.get('trustScore')}")
    check("the worker is told why", "verified by Kaarigar Setu" in json.dumps(ring_passport.get("trustReport", {})), str(ring_passport.get("trustReport"))[:200])
    listed, seen_text, offset = set(), "", 0
    while offset < 20000 and not set(ring_ids) <= listed:            # the list is paged (200 a page): an old database has many employers, the new ones are on a later page
        status, listing = mod.request("GET", f"/api/moderator/employers?offset={offset}")
        page = listing.get("employers", [])
        listed |= {e["employerId"] for e in page}
        seen_text += json.dumps(listing)
        if len(page) < 200:
            break
        offset += 200
    check("the moderator's vetting list (paged) shows the unvetted employers without phone numbers", set(ring_ids) <= listed and not re.search(r"\b[6-9]\d{9}\b", seen_text))
    status, _ = mod.request("GET", "/api/moderator/employers?offset=-1")
    status2, _ = mod.request("GET", "/api/moderator/employers?offset=abc")
    check("a bad page offset is refused", status == 400 and status2 == 400, f"{status} {status2}")
    status, _ = worker.request("POST", "/api/moderator/vet-employer", {"employerId": ring_ids[0]})
    check("a worker cannot vet an employer -> 403", status == 403)
    status, proposed = mod.request("POST", "/api/moderator/vet-employer", {"employerId": ring_ids[0]})
    check("a moderator PROPOSES vetting: 202, waiting for a second moderator", status == 202 and proposed.get("pending") is True and proposed.get("actionId"), f"{status} {proposed}")
    status, ring_passport = ring_worker.request("GET", "/api/worker/me/passport")
    check("proposing vetted nobody: trust is still held at 79.99", ring_passport.get("trustBand") == "GOOD" and abs(float(ring_passport["trustScore"]) - 79.99) < 0.01, f"{ring_passport.get('trustBand')} {ring_passport.get('trustScore')}")
    status, _ = mod.request("POST", f"/api/moderator/pending/{proposed['actionId']}/approve", {})
    check("the proposer cannot approve their own proposal -> 403", status == 403, str(status))
    status, ring_passport = ring_worker.request("GET", "/api/worker/me/passport")
    check("and the attempt vetted nobody", ring_passport.get("trustBand") == "GOOD" and abs(float(ring_passport["trustScore"]) - 79.99) < 0.01, f"{ring_passport.get('trustBand')} {ring_passport.get('trustScore')}")
    status, _ = mod2.request("POST", f"/api/moderator/pending/{proposed['actionId']}/approve", {})
    check("a DIFFERENT moderator approves: the employer is vetted", status == 200, str(status))
    status, ring_passport = ring_worker.request("GET", "/api/worker/me/passport")
    check("one vetted employer among the five lifts the hold: Excellent", ring_passport.get("trustBand") == "EXCELLENT" and float(ring_passport["trustScore"]) >= 80,
          f"{ring_passport.get('trustBand')} {ring_passport.get('trustScore')}")
    status, _ = mod.request("POST", "/api/moderator/vet-employer", {"employerId": 987654321})
    check("vetting a non-existent employer -> 404", status == 404)

    section("9c. The worker's say: see, accept or dispute what employers wrote; a moderator rules")
    dw_phone, _ = register_worker()
    dworker = Client("dispute-worker")
    dworker.login("WORKER", dw_phone)
    d_ids = []
    for n in range(4):
        e_phone, e_id = register_employer(f"Dispute Employer {n}")
        e_client = Client(f"dispute-employer-{n}")
        e_client.login("EMPLOYER", e_phone)
        status, made = e_client.request("POST", "/api/attestation/request-otp",
                                        {"workerPhone": dw_phone, "jobRole": "Fan fitting", "durationDays": 30, "wouldHireAgain": n != 0,
                                         "workDate": time.strftime("%Y-%m-%d", time.gmtime(time.time() - 86400)), "qualityRating": 1 if n == 0 else 5,
                                         "punctualityRating": 1 if n == 0 else 5, "conductRating": 1 if n == 0 else 5})
        e_client.request("POST", "/api/attestation/verify-otp", {"attestationId": made["attestationId"], "code": last_otp(e_phone[-4:], "attestation")})
    status, listing = dworker.request("GET", "/api/worker/me/confirmations")
    confs = listing.get("confirmations", [])
    check("the worker sees all four confirmations about them, with the employer's business name", status == 200 and len(confs) == 4 and all(c["employerName"].startswith("Dispute Employer") for c in confs), str(listing)[:200])
    check("none of it contains a phone number", not re.search(r"\b[6-9]\d{9}\b", json.dumps(listing)))
    check("each is open to a response for the dispute window", all(c["canRespond"] and c["response"] == "NONE" and c["daysLeftToDispute"] >= 29 for c in confs))
    status, before = dworker.request("GET", "/api/worker/me/passport")
    unfair = next(c for c in confs if c["ratingQuality"] == 1)
    fair = [c for c in confs if c["ratingQuality"] == 5]
    check("the unfair 1-star confirmation is dragging the trust score down", before["trustScore"] is not None)
    # --- access control
    stranger_phone, _ = register_worker()
    stranger = Client("dispute-stranger")
    stranger.login("WORKER", stranger_phone)
    status, _ = stranger.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "this is not about me at all, I am a stranger"})
    check("another worker cannot dispute it -> 404", status == 404)
    status, _ = stranger.request("POST", f"/api/worker/confirmations/{unfair['id']}/accept", {})
    check("another worker cannot accept it -> 404", status == 404)
    status, _ = Client("anon").request("GET", "/api/worker/me/confirmations")
    check("not signed in -> 401", status == 401)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "short"})
    check("a reason that is too short -> 400", status == 400)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "x" * 400})
    check("a reason that is too long -> 400", status == 400)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "This person never employed me and the rating is false"}, csrf=False)
    check("a dispute without the CSRF token -> 403", status == 403)
    # --- dispute holds it out; a moderator rules
    status, body = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "This person never employed me and the rating is false"})
    check("the worker disputes the unfair confirmation", status == 200 and body.get("disputed") is True, f"{status} {body}")
    status, after = dworker.request("GET", "/api/worker/me/passport")
    check("it no longer counts: three employers remain in the trust report", after["trustReport"].get("employerCount") == 3.0, str(after["trustReport"])[:160])
    check("and the trust score went UP, because the unfair 1-star rating is held out", float(after["trustScore"]) > float(before["trustScore"]), f"{before['trustScore']} -> {after['trustScore']}")
    status, again = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/dispute", {"reason": "disputing the same one a second time here"})
    check("a confirmation can be answered only once -> 409", status == 409)
    status, again = dworker.request("POST", f"/api/worker/confirmations/{unfair['id']}/accept", {})
    check("a disputed confirmation cannot then be accepted -> 409", status == 409)
    status, disputes = mod.request("GET", "/api/moderator/disputes")
    mine = [d for d in disputes.get("disputes", []) if d["attestationId"] == unfair["id"]]
    check("the moderator sees the dispute with the worker's reason, the employer's name and the ratings, and no phone number",
          status == 200 and len(mine) == 1 and "never employed me" in mine[0]["reason"] and mine[0]["employerName"].startswith("Dispute Employer") and not re.search(r"\b[6-9]\d{9}\b", json.dumps(mine)))
    status, _ = worker.request("GET", "/api/moderator/disputes")
    check("a worker cannot read the dispute queue -> 403", status == 403)
    status, _ = worker.request("POST", "/api/moderator/dispute-decision", {"attestationId": unfair["id"], "decision": "REMOVED"})
    check("a worker cannot rule on a dispute -> 403", status == 403)
    status, _ = mod.request("POST", "/api/moderator/dispute-decision", {"attestationId": unfair["id"], "decision": "MAYBE"})
    check("a ruling must be UPHELD or REMOVED -> 400", status == 400)
    status, _ = mod.request("POST", "/api/moderator/dispute-decision", {"attestationId": 987654321, "decision": "REMOVED"})
    check("ruling on something that is not an open dispute -> 404", status == 404)
    status, proposed = mod.request("POST", "/api/moderator/dispute-decision", {"attestationId": unfair["id"], "decision": "REMOVED"})
    check("a ruling is PROPOSED: 202, waiting for a second moderator", status == 202 and proposed.get("pending") is True, f"{status} {proposed}")
    status, still = dworker.request("GET", "/api/worker/me/confirmations")
    check("until a second moderator approves, the dispute stays open and the rating stays held out", next(c for c in still["confirmations"] if c["id"] == unfair["id"]).get("disputeDecision") is None)
    status, _ = mod.request("POST", f"/api/moderator/pending/{proposed['actionId']}/approve", {})
    check("the proposing moderator cannot approve their own ruling -> 403", status == 403)
    status, _ = mod2.request("POST", f"/api/moderator/pending/{proposed['actionId']}/approve", {})
    check("a different moderator approves: the unfair confirmation is removed", status == 200, str(status))
    status, after2 = dworker.request("GET", "/api/worker/me/passport")
    check("it stays out of the score after the ruling", after2["trustReport"].get("employerCount") == 3.0)
    status, now = dworker.request("GET", "/api/worker/me/confirmations")
    shown = next(c for c in now["confirmations"] if c["id"] == unfair["id"])
    check("the worker sees it as removed", shown["disputeDecision"] == "REMOVED" and shown["heldOut"] is True and shown["canRespond"] is False)
    status, _ = mod.request("POST", "/api/moderator/dispute-decision", {"attestationId": unfair["id"], "decision": "UPHELD"})
    check("a dispute is ruled on only once -> 404", status == 404)
    # --- upheld counts again; accept is a record; limits
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{fair[0]['id']}/dispute", {"reason": "I do not recall this job, please check it"})
    status, proposed = mod.request("POST", "/api/moderator/dispute-decision", {"attestationId": fair[0]["id"], "decision": "UPHELD"})
    status, _ = mod2.request("POST", f"/api/moderator/pending/{proposed['actionId']}/approve", {})
    status, up = dworker.request("GET", "/api/worker/me/passport")
    check("an UPHELD dispute counts again (the fair confirmation is back)", up["trustReport"].get("employerCount") == 3.0 and status == 200)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{fair[1]['id']}/accept", {})
    check("the worker can accept a confirmation", status == 200)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{fair[1]['id']}/accept", {})
    check("accepting twice -> 409", status == 409)
    status, _ = dworker.request("POST", f"/api/worker/confirmations/{fair[1]['id']}/dispute", {"reason": "accepted it already but regretting that now"})
    check("an accepted confirmation cannot be disputed later -> 409", status == 409)
    status, final = mod.request("GET", "/api/moderator/security")
    check("disputes show up on the moderator's Security tab counts", final["counts"]["ATTESTATION_DISPUTED"]["last24h"] >= 2)

    section("9d. Two-person approval in detail")
    d_phone, d_id = register_employer("Approval Test Employer")
    status, first = mod.request("POST", "/api/moderator/vet-employer", {"employerId": d_id})
    check("a proposal is queued", status == 202 and first.get("actionId"), f"{status} {first}")
    status, dup = mod2.request("POST", "/api/moderator/vet-employer", {"employerId": d_id})
    check("a second proposal for the same employer while one waits -> 409", status == 409, f"{status} {dup}")
    status, mine = mod.request("GET", "/api/moderator/pending")
    status, theirs = mod2.request("GET", "/api/moderator/pending")
    row_mine = next((r for r in mine.get("pending", []) if r["actionId"] == first["actionId"]), None)
    row_theirs = next((r for r in theirs.get("pending", []) if r["actionId"] == first["actionId"]), None)
    check("the queue shows the proposal to both, with who proposed it and what it is about", bool(row_mine and row_theirs) and row_mine["proposedBy"] == "moderator" and "Approval Test Employer" in row_mine["target"], str(row_mine))
    check("only the OTHER moderator is offered the decision", row_mine["canDecide"] is False and row_theirs["canDecide"] is True)
    check("the queue holds no phone numbers", not re.search(r"\b[6-9]\d{9}\b", json.dumps(mine) + json.dumps(theirs)))
    status, _ = worker.request("POST", f"/api/moderator/pending/{first['actionId']}/approve", {})
    check("a worker cannot approve -> 403", status == 403)
    status, _ = mod2.request("POST", "/api/moderator/pending/987654321/approve", {})
    check("approving something that does not exist -> 404", status == 404)
    status, _ = mod2.request("POST", "/api/moderator/pending/not-a-number/approve", {})
    check("a non-numeric id -> 404", status == 404)
    status, _ = mod.request("POST", f"/api/moderator/pending/{first['actionId']}/approve", {}, csrf=False)
    check("approving without the CSRF token -> 403", status == 403)
    status, _ = mod2.request("POST", f"/api/moderator/pending/{first['actionId']}/reject", {})
    check("the other moderator rejects it", status == 200)
    status, _ = mod2.request("POST", f"/api/moderator/pending/{first['actionId']}/approve", {})
    check("a rejected proposal cannot then be approved -> 409", status == 409)
    status, second = mod2.request("POST", "/api/moderator/vet-employer", {"employerId": d_id})
    check("after a rejection a new proposal can be made", status == 202)
    status, _ = mod2.request("POST", f"/api/moderator/pending/{second['actionId']}/reject", {})
    check("the proposer takes their own proposal back (withdraw)", status == 200)
    status, third = mod.request("POST", "/api/moderator/vet-employer", {"employerId": d_id})
    status, _ = mod2.request("POST", f"/api/moderator/pending/{third['actionId']}/approve", {})
    check("approved by the other moderator: employer vetted", status == 200)
    status, _ = mod.request("POST", f"/api/moderator/pending/{third['actionId']}/approve", {})
    check("a proposal is carried out once -> 409 (or 403 for the proposer)", status in (403, 409), str(status))
    status, _ = mod.request("POST", "/api/moderator/vet-employer", {"employerId": d_id})
    check("proposing to vet an employer who is already vetted -> 409", status == 409)
    import audit_decrypt                                    # the audit file is encrypted: read it with the independent decryptor
    trail = audit_decrypt.events()
    vetted_lines = [e for e in trail if e.startswith("EMPLOYER_VETTED ") and f"employer={d_id} " in e]
    check("the audit trail names BOTH people who vetted: the approver as the actor, the proposer in the details",
          any("actor=moderator:moderator2" in e and "proposed_by=moderator" in e and "approved_by=moderator2" in e for e in vetted_lines), str(vetted_lines)[:300])
    proposed_lines = [e for e in trail if e.startswith("MODERATOR_ACTION_PROPOSED ") and f"target={d_id}" in e]
    check("and the proposals are in the trail under the proposers' own names", any("actor=moderator:moderator " in e for e in proposed_lines) and any("actor=moderator:moderator2 " in e for e in proposed_lines), str(proposed_lines)[:300])
    status, sec = mod.request("GET", "/api/moderator/security")
    check("the Security tab counts proposals, approvals and the refused self-approvals", sec["counts"]["MODERATOR_ACTION_PROPOSED"]["last24h"] >= 3 and sec["counts"]["MODERATOR_ACTION_APPROVED"]["last24h"] >= 3
          and sec["counts"]["MODERATOR_SELF_APPROVAL_REFUSED"]["last24h"] >= 2, str(sec["counts"])[:300])
    check("a refused self-approval raises an alert", any("approve their own" in a["title"] for a in sec["alerts"]), str(sec["alerts"])[:300])

    section("10. DPDP erasure (right to deletion)")
    import glob
    ref_new = new["ref"]
    check("credential export file exists before erasure", bool(glob.glob(f"**/data/credentials/{ref_new}.json", recursive=True)))
    status, body = worker.request("POST", "/api/worker/erase", {"confirm": "yes please"})
    check("erase without the exact phrase -> 400", status == 400)
    status, body = employer.request("POST", "/api/worker/erase", {"confirm": "ERASE MY DATA"})
    check("an employer cannot erase a worker account -> 403", status == 403)
    status, body = worker.request("POST", "/api/employer/erase", {"confirm": "ERASE MY DATA"})
    check("a worker cannot call employer erasure -> 403", status == 403)
    status, body = worker.request("POST", "/api/worker/erase", {"confirm": "ERASE MY DATA"})
    check("worker erasure succeeds", status == 200 and body.get("erased") is True, f"{status} {body}")
    status, _ = worker.request("GET", "/api/worker/me/passport")
    check("the erased worker's session is gone", status == 401)
    status, body = Client("public").request("GET", f"/api/credential/{ref_new}/verify")
    check("their credential no longer verifies (NOT_FOUND)", body.get("state") == "NOT_FOUND", str(body)[:120])
    check("their credential export file is deleted from disk", not glob.glob(f"**/data/credentials/{ref_new}.json", recursive=True))
    again = Client("erased-login")
    again.request_code("WORKER", worker_phone)
    status, body = again.request("POST", "/api/auth/otp/verify", {"role": "WORKER", "phone": worker_phone, "code": "123456"})
    check("the erased number can no longer log in", status == 401)
    status, body = Client("anon").request("POST", "/api/worker/register",
                                          {"name": "Fresh Start", "phone": worker_phone, "trade": "electrician", "language": "hi", "consent": True})
    check("the number can be registered again from scratch", status == 201)
    status, body = employer.request("POST", "/api/employer/erase", {"confirm": "ERASE MY DATA"})
    check("employer erasure succeeds", status == 200 and body.get("erased") is True, f"{status} {body}")
    status, _ = employer.request("GET", "/api/attestation/mine")
    check("the erased employer's session is gone", status in (401, 403))
    status, audit = mod.request("GET", "/api/moderator/audit-check")
    check("audit chain still verifies after erasures (append-only)", status == 200 and audit["fileChainValid"] is True, str(audit))

    print("\n" + ("ALL CHECKS PASSED" if not FAILED else f"{len(FAILED)} CHECK(S) FAILED: " + "; ".join(FAILED)))
    return 1 if FAILED else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as failure:
        print(f"\nABORTED: {failure}")
        sys.exit(2)
