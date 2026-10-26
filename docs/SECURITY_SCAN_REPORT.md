# Security scan report

What automated analysis was run on this code, what it found, and what was done about each finding. Everything here was run in the development environment on the
final code; the CI workflow (`.github/workflows/ci.yml`, jobs `sast`, `dependency-scan`, `e2e`, `images`) runs the same checks on every push so the result does not depend on one laptop.

| Check | Tool and version | Scope | Result | Gate in CI |
|---|---|---|---|---|
| Java static analysis | SpotBugs 4.8.6 with find-sec-bugs 1.13.0 (`mvn -Psast spotbugs:check`) | all Java under `kaarigarsetu-java/src/main` | 94 raw findings (85, plus 9 from the later encryption-at-rest and replay work), **0 after fixes and documented exclusions** | fails the build on any new finding |
| Python static analysis | Bandit 1.9.4 | `kaarigarsetu-ml-service/app` (production code, 1,994 lines) | **0 findings** at any severity | fails at medium and above |
| Multi-language rules | Semgrep 1.181 with `p/java`, `p/python`, `p/javascript`, `p/owasp-top-ten`, `p/secrets` | 213 files: Java, Python, JavaScript, Docker, SQL, scripts | **0 findings** (two shell scripts only partly parsed, no code risk) | fails on any finding |
| Secret scan | gitleaks 8 with `.gitleaks.toml` | the tree that ships in the zip / repository | **0 findings** (two dummy test keys are allow-listed by value) | fails on any finding |
| Python dependencies | pip-audit 2.10 against the PyPI advisory database | `requirements.txt`, `requirements-faster-whisper.txt` | no known vulnerabilities | fails on any finding |
| Java dependencies | OSV (`scripts/osv_scan_maven.py`) | 15 runtime dependencies | 0 problems | fails on any finding or failed lookup |
| Software bill of materials | CycloneDX 1.6 | Java: 11 components; ML service: 11 components | generated (`kaarigarsetu-java/target/bom.json`, `ml-bom.json`) | uploaded as a CI artifact |
| Container images | Trivy | the two images | **runs in CI only** (Docker is not installed on the development machine) | report-only |
| Dynamic scan of the running app | OWASP ZAP baseline (passive) | the e2e job's running app | **runs in CI only**; the project's own attack harness is the dynamic test run here ([`PENTEST_REPORT.md`](PENTEST_REPORT.md)) | report-only |

## Findings and how each was handled

### SpotBugs with find-sec-bugs: 85 raw findings

| Finding | Count | Verdict | Action |
|---|---|---|---|
| `EI_EXPOSE_REP`, `EI_EXPOSE_REP2` ("may expose internal representation") | 73 | Not a security issue here: services and DAOs are passed to each other by constructor and immutable records are returned; the rule is about defensive copies of mutable arrays and collections | excluded in `spotbugs-exclude.xml`, with the reason written next to the exclusion |
| `NP_BOOLEAN_RETURN_NULL` | 2 | Intentional: an optional request field (and an optional sealed flag) is true, false or absent | excluded with reason, for those two places |
| `SQL_INJECTION_JDBC` in the operator tool `SealAtRestCli` | 2 | The statement text is passed to a helper from the fixed literals of `SealedColumns`; nothing from input is ever put into SQL (`NoStringBuiltSqlTest`) | excluded with reason, for that one class |
| `OBL_UNSATISFIED_OBLIGATION` / `ODR_OPEN_DATABASE_RESOURCE` in `AuditVerifyCli.readAnchors` | 4 | The method called `System.exit` inside the open connection | **fixed:** it throws, and the caller exits after the connection is closed |
| `WMI_WRONG_MAP_ITERATOR` in `ReplayDetector` | 1 | Small inefficiency | **fixed:** iterates entries |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` in `SealAtRestCli` | 1 | `Path.getFileName()` for a root path | **fixed:** explicit non-null requirement |
| `REDOS` on the phone-number mask in `AuditLogger` | 1 | Heuristic hit: every quantifier is bounded. Still, the pattern ran over attacker-controlled text (usernames, headers) before the length cap | **fixed:** input is truncated to 2,000 characters before any regex runs; the pattern exclusion is documented |
| `DM_DEFAULT_ENCODING` (priority 1) in `PasswordHasher.main` | 1 | Real, small: the password-hash command-line tool read standard input in the platform's default encoding, so a non-ASCII password could hash differently on another system | **fixed:** read as UTF-8 |
| `UNSAFE_HASH_EQUALS` in the audit anchor check | 1 | The compared value is not secret, but inconsistent with the rest of the code | **fixed:** `MessageDigest.isEqual` |
| `CT_CONSTRUCTOR_THROW` in `SignatureService` | 2 | A constructor that can throw in a non-final class permits a finalizer attack | **fixed:** class made `final` |
| `DCN_NULLPOINTER_EXCEPTION` (catching `NullPointerException`) | 2 | Poor practice that hid missing fields | **fixed:** explicit checks in credential verification and the trade catalogue loader |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | 2 | `Path.getParent()` and `getFileName()` can be null only for a root path | **fixed:** explicit non-null requirement |
| `URF_UNREAD_FIELD` | 1 | Dead field | **fixed:** removed |
| `REC_CATCH_EXCEPTION` in signature verification | 1 | Caught `Exception` too broadly | **fixed:** catches the specific security and argument exceptions |

### Bandit and Semgrep

Nothing in production code. Bandit reports 49 notes on `scripts/` (development tooling): asserts, `subprocess` calls with fixed arguments, `random` used for throw-away test data, an HTTP client for fixed local URLs, and
one deliberate bind to all interfaces in `dev_https_proxy.py` (it exists so a phone can reach the development server; development only). None run in production, so they are not gated.

### gitleaks

Over the whole working folder it reports 44 hits, all in files that are **git-ignored and excluded from the zip**: `.env.local`, `data/` (the development keystore, the development TLS key, the audit log whose lines contain keyed hashes).
In the shippable tree the only hits were two obviously dummy 32-byte keys in unit tests, now allow-listed by exact value. One more hit appeared during this work (ordinary prose in a document that matched the generic-key pattern);
the sentence was reworded rather than weakening the scanner.

### What these tools cannot tell you

They find known bad patterns and known-vulnerable versions. They do not find logic flaws. The flaws that mattered most in this project (three fake accounts reaching the top trust band; credentials only the
original software could verify; an audit file that could be rolled back) were found by the attack harness and the simulation, not by any scanner.
