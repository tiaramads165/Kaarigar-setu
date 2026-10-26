# Ten-minute security demonstration

Run it on a development stack (`./scripts/dev.sh`, demo data seeded, `DEMO_LOGIN=true`). Commands are from the repository root. For the audit and QR steps the Java tools are built on first use
(`mvn` and a JDK are needed). OTP codes appear with `./scripts/otp.sh -f`.

Moderator sign-in allows 5 attempts per 10 minutes (kept in memory, shared by every run of the test scripts). If a script or the console reports `rate_limited` for the moderator, wait ten minutes or restart the app.

Set up once in a second terminal:

```bash
export BASE_URL=http://localhost:8090 JAVA_LOG=data/logs/dev.log
set -a; . ./.env.local; set +a
CP="kaarigarsetu-java/target/classes:$(cat kaarigarsetu-java/target/demo.classpath)"      # after: cd kaarigarsetu-java && mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/demo.classpath
```

| Min | Show | Say |
|---|---|---|
| 0-1 | [`SECURITY_ARCHITECTURE.md`](SECURITY_ARCHITECTURE.md) section 2 and the risk register | What is protected, where the trust boundaries are, and that every control has a test or script behind it |
| 1-3 | `python3 scripts/security_attack_demo.py --only AUTH,CSRF,ACL` | Real attacks against the running app: code guessing and replay, session fixation, stolen cookie after logout, role escalation, cross-site requests. Each prints BLOCKED with the evidence. Point at AUTH-08: a known, accepted weakness, shown honestly |
| 3-5 | Download a credential from the worker dashboard. `python3 scripts/verify_credential.py credential.json --server $BASE_URL` then change `skillScore` in the file and run it again | A stranger can verify with only the file and the public key: passes, then FAILS after one edited digit. This is RFC 8785 canonical signing; the first version could not be checked by anyone else, and the harness found that |
| 5-6 | Dashboard: press **Show live code**, scan or open it; open the plain QR link too | The fixed QR cannot tell who is showing it; the live code (5 minutes, HMAC) turns "static link" into "shown live". What it does not prove is also on the page |
| 6-7 | `cp data/audit.log* /tmp/` then edit one line of the copy and run `java -cp "$CP" com.kaarigarsetu.admin.AuditVerifyCli /tmp/audit.log`. Then `--only AUD` in the harness | Edits and deletions are caught by the chain. A rollback to an older matching log+head passes the chain alone; the anchors pinned in the database catch it (AUD-02). That gap was found by attacking our own design |
| 7-9 | `java -cp "target/classes:target/test-classes:$(cat target/demo.classpath)" com.kaarigarsetu.trust.TrustAttackSimulation` (from `kaarigarsetu-java`), then [`FRAUD_ATTACK_SIMULATION.md`](FRAUD_ATTACK_SIMULATION.md) section 2 and 3 | The fraud model catches crude rings (81-100%) and misses patient ones (0%). Three throwaway accounts used to reach Excellent. The fix: the top band needs an employer a moderator has vetted. Show the console Employers tab (`./scripts/console.sh`) and e2e section 9b |
| 9-10 | Console **Security** tab, then [`SECURITY_SCAN_REPORT.md`](SECURITY_SCAN_REPORT.md) | Detection (alerts, counts, integrity) and the pipeline (SpotBugs + find-sec-bugs, Semgrep, Bandit, gitleaks, dependency scans, SBOM) with every finding triaged |

Close with the honest list: [`PENTEST_REPORT.md`](PENTEST_REPORT.md) section 4 (what is still open): a worker can hide up to 3 bad ratings until a moderator rules, SIM farms reach the Good band, two colluding moderators or the operator, and
nothing here has been tested with real users or a real phone over HTTPS.
