#!/usr/bin/env python3
"""Demo helper for the fraud-to-moderator journey (docs/DEMO_SCRIPT.md, journey 2).

Creates ONE employer and seven new workers, then has that employer attest all seven within seconds from a single address and device:
the rapid-fire, same-source pattern the Isolation Forest is trained to flag. Prints each outcome, then tells you to open the
moderator console. Uses the same helpers as scripts/e2e.py (OTPs are read from the server log).

    JAVA_LOG=data/logs/dev.log python3 scripts/demo_fraud_burst.py                      # local stack
    BASE_URL=http://localhost:8090 JAVA_LOG=data/logs/compose-app.log python3 scripts/demo_fraud_burst.py
"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import e2e  # noqa: E402  (shares the HTTP client, phone helpers and OTP reader)


def main() -> int:
    employer_phone, _ = e2e.register_employer("Demo Burst Employer")
    employer = e2e.Client("burst")      # a fresh random source address and device, used for every request below
    employer.login("EMPLOYER", employer_phone)
    print(f"Employer registered and signed in (phone ...{employer_phone[-4:]}). Attesting seven workers in a row:\n")
    outcomes = []
    for index in range(7):
        worker_phone, _ = e2e.register_worker()
        status, body = employer.request("POST", "/api/attestation/request-otp",
                                        {"workerPhone": worker_phone, "jobRole": f"Wiring job {index + 1}", "workDate": time.strftime("%Y-%m-%d"),
                                         "qualityRating": 5, "punctualityRating": 5, "conductRating": 5})
        if status != 201:
            print(f"  request {index + 1}: HTTP {status} {body}")
            return 1
        code = e2e.last_otp(employer_phone[-4:], "attestation")
        status, body = employer.request("POST", "/api/attestation/verify-otp", {"attestationId": body["attestationId"], "code": code})
        outcomes.append(body.get("status"))
        print(f"  attestation {index + 1}: {body.get('status'):15s} anomaly score {body.get('anomalyScore')}")
    flagged = outcomes.count("PENDING_REVIEW")
    print(f"\n{flagged} of 7 were sent to human review; none of those was approved automatically.")
    print("Now open the moderator console (or GET /api/moderator/flagged) and decide them.")
    return 0 if flagged else 2


if __name__ == "__main__":
    sys.exit(main())
