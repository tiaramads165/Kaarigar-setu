# Kaarigar Setu: the Java features

This is the companion to the **CBS 3002 Review 1** deck (*"A Java-Engineered Skill & Trust Passport"*). The deck lists the core Java topics the
project is built on. This page shows, for each one, **where it lives in the real code** (click a reference to open the file at that line), with
a short excerpt. Everything below was read from the current source; where the implementation differs from a Review 1 slide, that is said in
[section 12](#12-where-the-code-differs-from-the-review-1-slides).

**At a glance:** 150 Java source files · 64 Java test files · 498 Java tests + 18 console tests passing · Java 17 language level
(built with JDK 21) · Jakarta Servlet 6 / JSP / JSTL on Tomcat 10.1 · JDBC on PostgreSQL · JavaFX 21 desktop console.

| # | Review 1 topic | Where it is in the code |
|---|---|---|
| 1 | Clean package structure | `com.kaarigarsetu.*`, 17 packages ([§1](#1-package-structure)) |
| 2 | Abstract class, inheritance, polymorphism | [`AssessmentService.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L12) and six trade subclasses ([§2](#2-oop-core-abstract-class-inheritance-polymorphism)) |
| 3 | User-defined exceptions | `exception` package, 13 classes ([§3](#3-user-defined-exceptions)) |
| 4 | Collections Framework | [`TrustGraphService.java:13`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/trust/TrustGraphService.java#L13), [`AttestationStore.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationStore.java#L12) ([§4](#4-collections-framework)) |
| 5 | File handling (`java.io` / `java.nio`) | [`AuditLogWriter.java:37`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/util/AuditLogWriter.java#L37), [`ChainedAuditFileSink.java:55`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/ChainedAuditFileSink.java#L55), [`CredentialExporter.java:11`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/util/CredentialExporter.java#L11) ([§5](#5-file-handling)) |
| 6 | JDBC with `PreparedStatement` | [`JdbcDaoSupport.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/dao/JdbcDaoSupport.java#L12) and 9 DAOs ([§6](#6-jdbc-and-preparedstatement)) |
| 7 | Servlets | 7 API servlets + a health servlet + 6 page servlets, 3 filters ([§7](#7-servlets)) |
| 8 | JSP dashboards | 7 JSP pages ([§8](#8-jsp-dashboards)) |
| 9 | JavaFX moderator console | [`ModeratorConsoleApp.java:47`](kaarigarsetu-moderator-console/src/main/java/com/kaarigarsetu/console/ModeratorConsoleApp.java#L47) ([§9](#9-javafx-moderator-console)) |

---

## 1. Package structure

The deck asks for clean packages (worker, employer, assessment, trust, credential, exception). The source root is
`kaarigarsetu-java/src/main/java/com/kaarigarsetu/`:

| Package | Files | Responsibility |
|---|---|---|
| `assessment` | 15 | abstract `AssessmentService`, six trade subclasses, scoring dispatcher, skill bands, the assessment pipeline, `AssessmentServlet` |
| `attestation` | 7 | employer confirmation workflow, in-memory `AttestationStore`, `AttestationServlet` |
| `trust` | 7 | trust score (`ReputationScoring`), `TrustScoreService`, `TrustGraphService` (adjacency list), `Edge`, `TrustBand` |
| `credential` | 3 | signed credential issue / verify / revoke, `CredentialServlet`, QR code |
| `worker`, `employer` | 2 + 2 | registration and passport, with their servlets |
| `moderator` | 2 | fraud review queue and `ModeratorServlet` |
| `exception` | 13 | the user-defined exceptions |
| `dao` | 10 | JDBC data access (one DAO per table) |
| `model` | 13 | immutable data carriers (all 13 are Java records) |
| `security` | 19 | OTP, login, phone encryption, signatures, rate limiting, audit log, `AuthFilter` |
| `web` | 9 | `BaseServlet`, page servlets, CSRF and security-header filters, app wiring |
| `config`, `util`, `ml`, `privacy`, `admin` | 5, 4, 2, 1, 1 | settings, file/JSON helpers, the Python ML-service client, data erasure, demo seeder |

The JavaFX console is a **separate Maven module**: `kaarigarsetu-moderator-console/`.

## 2. OOP core: abstract class, inheritance, polymorphism

* [`AssessmentService.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L12) holds the rule that is the same for every trade
  (exactly 10 answers, scores kept between 0 and 100, the mean is the result) and declares what each trade must supply:
  [`supportedTrade()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L16) and
  [`feedbackFor()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L50).
* The shared algorithms [`assess()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L18) and
  [`summarize()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentService.java#L34) are `final`: a subclass **cannot** change the scoring rule
  (the template-method pattern), it can only choose its own wording.
* Six subclasses extend it: `Electrician`, `Plumber`, `Carpenter`, `Mason`, `Welder`, `Painter` `AssessmentService`, for example
  [`PlumberAssessmentService.java:5`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/PlumberAssessmentService.java#L5):

```java
@Override
protected String feedbackFor(BigDecimal score) {
    return score.compareTo(new BigDecimal("80")) >= 0
            ? "Strong plumbing diagnostics and installation responses."
            : "Review leak diagnosis, fittings, and safe installation practice.";
}
```

* **Polymorphic call.** [`resolve()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentScoringDispatcher.java#L24) picks the subclass for the
  worker's trade from a `List<AssessmentService>`, and the call through the common reference runs the correct override at runtime:
  [`resolve(trade).summarize(...)`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentScoringDispatcher.java#L21).
* Also used: **encapsulation** (private final fields, immutable records), **interfaces** (`OtpSender` with `ConsoleOtpSender` and
  `HttpSmsOtpSender`; `ModeratorApi` with `HttpModeratorApi`; `MlGateway` with `MlServiceClient`), and **dependency injection through
  constructors**, so every service can be tested with a stand-in.

Adding a trade is: one rubric file, one line in `trades.json`, and one small subclass (see `docs/RUBRIC_AUTHORING.md`).

## 3. User-defined exceptions

Thirteen exceptions in [`InvalidOTPException.java:4`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/exception/InvalidOTPException.java#L4)'s package, each one a domain event, not a
generic error. The three named in the deck:

| Exception | Thrown when | Thrown at |
|---|---|---|
| `InvalidOTPException` | a one-time code is wrong, expired or locked | [`OtpService.java:95`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/OtpService.java#L95) |
| `DuplicateAttestationException` | the same employer confirms the same worker twice | [`AttestationStore.java:29`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationStore.java#L29) |
| `LowTrustScoreException` | the trust score is below the minimum for issuing a credential | [`CredentialService.java:97`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/credential/CredentialService.java#L97) |

Others: `ConsentRequiredException`, `RateLimitExceededException`, `UnintelligibleAudioException`, `ExternalServiceException`
(ML service down), `AuthenticationFailedException`, `ForbiddenException`, `NotFoundException`, `ConflictException`, `ValidationException`,
`DataAccessException` (wraps `SQLException`).

They are unchecked (`extends RuntimeException`), and one place, [`ApiErrors.java:29`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/web/ApiErrors.java#L29), turns each into the right HTTP status and
a safe message (a wrong code is a 401, a duplicate is a 409, a missing consent is a 403, the ML service being down is a 503), so no stack trace
or internal detail reaches the client.

## 4. Collections Framework

* **Trust graph as an adjacency list:** [`TrustGraphService.java:13`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/trust/TrustGraphService.java#L13)
  with `computeIfAbsent(...).add(edge)` in [`addEdge()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/trust/TrustGraphService.java#L24). `Edge` is a record
  ([`Edge.java:6`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/trust/Edge.java#L6)). It is the structure behind the network graph that the Python service runs PageRank on.
* **Attestation store:** [`AttestationStore.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationStore.java#L12), with a
  `synchronized` [`add()`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationStore.java#L23) that rejects a duplicate worker-employer pair using a stream
  (`attestations.stream().anyMatch(...)`).
* **Maps, sets and ordered maps in the trust score:** `TrustScoreService` groups feedback with `Collectors.groupingBy(...)` into a
  `LinkedHashMap<Long, List<Feedback>>`, tracks workers in a `LinkedHashSet`, and looks up employer standing in `HashMap<Long, ...>`.
* **Streams and `Optional`:** the DAOs return `Optional<T>`, and the trade dispatcher is a `stream().filter(...).findFirst().orElseThrow(...)`.

## 5. File handling

* **Append-only audit log (`java.nio.file`):** [`AuditLogWriter.java:37`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/util/AuditLogWriter.java#L37) with
  `StandardOpenOption.CREATE, APPEND` writes one timestamped line per sensitive event.
* **Tamper-evident audit file:** [`ChainedAuditFileSink.java:55`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/ChainedAuditFileSink.java#L55) with `CREATE, WRITE, APPEND` and a file lock writes a
  hash chain (each line contains the hash of the one before), plus a keyed head file holding an HMAC. Editing, deleting or reordering any line
  breaks the chain, and `GET /api/moderator/audit-check` verifies it.
* **Credential export:** [`CredentialExporter.java:11`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/util/CredentialExporter.java#L11) writes the signed credential as JSON, called when a
  credential is issued ([`CredentialService.java:248`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/credential/CredentialService.java#L248)). `exportPdf` writes a one-page PDF of the same data.
* **Audio validation** reads WAV headers from a byte stream in [`WavValidator.java:13`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/util/WavValidator.java#L13) before anything is sent for transcription.

## 6. JDBC and PreparedStatement

* All persistence goes through JDBC (no ORM): ten classes in `dao`, built on [`JdbcDaoSupport.java:12`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/dao/JdbcDaoSupport.java#L12).
* Every statement is a **`PreparedStatement` with `?` placeholders**, never string concatenation, opened with try-with-resources so
  connections and result sets always close:

```java
String sql = "SELECT * FROM workers WHERE worker_id = ?";
try (var connection = connectionProvider.getConnection();
     PreparedStatement statement = connection.prepareStatement(sql)) {
    statement.setLong(1, workerId);
    ...
} catch (SQLException exception) {
    throw dataAccessException("find worker", exception);
}
```

  There are 80 `prepareStatement` calls in the DAOs. This is enforced, not just promised: the test
  [`NoStringBuiltSqlTest.java:20`](kaarigarsetu-java/src/test/java/com/kaarigarsetu/architecture/NoStringBuiltSqlTest.java#L20) scans the source and **fails the build** if SQL is ever built by
  concatenation, and [`DaoSqlInjectionFuzzTest.java:43`](kaarigarsetu-java/src/test/java/com/kaarigarsetu/dao/DaoSqlInjectionFuzzTest.java#L43) throws injection strings at every DAO against a real database.
* **Connection pool and transactions:** [`PooledConnectionProvider.java:15`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/config/PooledConnectionProvider.java#L15) with a thread-bound transaction:
  [`PooledConnectionProvider.java:43`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/config/PooledConnectionProvider.java#L43) makes several DAO calls commit or roll back together.
* **Generated keys, constraints and least privilege:** inserts use `Statement.RETURN_GENERATED_KEYS`; the application connects as a
  restricted database role that can read and write data but not change the schema (`database/setup_roles.sql`), and the audit table is
  append-only.
* The schema is `database/schema.sql` plus numbered migrations in `database/migrations/`.

## 7. Servlets

* Seven **API servlets** and a health servlet (Jakarta Servlet 6), each mounted with `@WebServlet` and all extending [`BaseServlet.java:21`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/web/BaseServlet.java#L21)
  (shared helpers for reading and writing JSON, splitting the URL path and finding the client address):

| Route | Servlet |
|---|---|
| `/api/auth/*` | [`AuthServlet.java:22`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/web/AuthServlet.java#L22) (login, one-time codes, moderator two-step login) |
| `/api/worker/*` | [`WorkerServlet.java:15`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/worker/WorkerServlet.java#L15) |
| `/api/employer/*` | [`EmployerServlet.java:15`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/employer/EmployerServlet.java#L15) |
| `/api/assessment/*` | [`AssessmentServlet.java:18`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/assessment/AssessmentServlet.java#L18) |
| `/api/attestation/*` | [`AttestationServlet.java:18`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationServlet.java#L18): `doPost` ([`AttestationServlet.java:22`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/attestation/AttestationServlet.java#L22)) requests and verifies the code, `doGet` lists "mine" |
| `/api/credential/*` | [`CredentialServlet.java:25`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/credential/CredentialServlet.java#L25) |
| `/api/moderator/*` | [`ModeratorServlet.java:17`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/moderator/ModeratorServlet.java#L17) |
| `/health` | [`HealthServlet.java:10`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/HealthServlet.java#L10) |

* Six **page servlets** in [`PageServlets.java:26`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/web/PageServlets.java#L26) load data and forward to the JSPs.
* Three **filters** declared in `web.xml` run in order on every request: `SecurityHeadersFilter` (CSP and friends), `CsrfFilter`
  (token and Origin check) and `AuthFilter` (deny-by-default role rules).

## 8. JSP dashboards

Seven pages in `kaarigarsetu-java/src/main/webapp/WEB-INF/jsp/`, all behind the page servlets so they are never reachable directly:
`index`, `assessment`, `worker-dashboard`, `employer-dashboard`, `verify`, `demo`, `error`. They use **JSTL and the expression language**
(`<c:out>`, `<c:forEach>`, `<c:if>`), which escapes output by default: for example the worker dashboard's trust card in
[`worker-dashboard.jsp:38`](kaarigarsetu-java/src/main/webapp/WEB-INF/jsp/worker-dashboard.jsp#L38) shows the trust score, band and the credit-report style breakdown. A strict Content-Security-Policy
means the pages carry **no inline script**: behaviour lives in `static/js/*.js`.

## 9. JavaFX moderator console

[`ModeratorConsoleApp.java:47`](kaarigarsetu-moderator-console/src/main/java/com/kaarigarsetu/console/ModeratorConsoleApp.java#L47) (a separate module, `kaarigarsetu-moderator-console`) is the
human-in-the-loop for the fraud model: confirmations the Isolation Forest flags wait here for a decision.

* **Components:** a [`ModeratorConsoleApp.java:154`](kaarigarsetu-moderator-console/src/main/java/com/kaarigarsetu/console/ModeratorConsoleApp.java#L154) with `TableColumn`s (anomaly score, evidence, ...), a
  `PasswordField` sign-in form, `Button`s, `VBox` / `HBox` / `BorderPane` layout and an `Alert` for confirmations.
* **Review actions:** [`ModeratorConsoleApp.java:174`](kaarigarsetu-moderator-console/src/main/java/com/kaarigarsetu/console/ModeratorConsoleApp.java#L174) and
  [`ModeratorConsoleApp.java:175`](kaarigarsetu-moderator-console/src/main/java/com/kaarigarsetu/console/ModeratorConsoleApp.java#L175), wired with `setOnAction` to `decide(selected, "VALID" | "FRAUD")`, plus refresh and
  an audit-chain check button.
* **Responsive UI:** network calls run on background threads through `javafx.concurrent.Task` and update the UI with
  `Platform.runLater`, so the window never freezes.
* **Same service layer:** it talks to the Java API over HTTP (`HttpModeratorApi` behind the `ModeratorApi` interface, using
  `java.net.http.HttpClient`), so the rules and the audit log are identical to the web path. Sign-in is password plus a one-time code, and
  a 401 returns you to the sign-in screen.
* **Approvals tab:** the second person's screen: what one moderator proposed (vet an employer, rule on a dispute), who proposed it and when it expires; *Approve* is disabled for your own proposals (`PendingRow.canDecide`), *Reject / withdraw* closes one. The window title shows who is signed in (`ModeratorIdentity`).
* **Disputes tab:** a worker can dispute a confirmation an employer wrote about them; the moderator reads the worker's reason and the employer's side in a `TableView` and rules with two buttons, *Keep (upheld)* or *Remove* (`DisputeRow`, `ModeratorApi.disputes()` / `decideDispute(...)`, an `Alert` to confirm a final ruling).
* Start it with `scripts/console.sh` or the double-click launcher `Kaarigar Setu Moderator Console.command`.

## 10. Modern Java used throughout (Java 17 level)

**Records** for immutable data (31 files, e.g. `Edge`, `AssessmentResult`, the models) · **switch expressions** (`case INVALID -> throw ...` in
`OtpService`) · **text blocks** for SQL · **`var`**, **generics** (`inTransaction<T>`), **functional interfaces** (`@FunctionalInterface
AuditLogRepository`) and lambdas · **streams and `Optional`** · **`java.time`** with an injected `Clock` (so time-dependent code is testable) ·
**`java.net.http.HttpClient`** for the ML service and the SMS gateway · **`System.Logger`** · **try-with-resources** everywhere resources open ·
**`synchronized`** methods and file locks where state is shared (`AttestationStore`, `InMemoryOtpChallengeStore`, the audit file).

## 11. Security features written in Java (the course is Information Security)

Password hashing with **PBKDF2** ([`PasswordHasher.java:13`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/PasswordHasher.java#L13)) · one-time codes hashed with a salt and a server-side pepper
with attempt limits and expiry ([`OtpService.java:22`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/OtpService.java#L22)) · phone numbers **AES-GCM encrypted** with an **HMAC** lookup key
([`PhoneProtector.java:21`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/PhoneProtector.java#L21)) · credentials signed with **RSA** ([`SignatureService.java:27`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/SignatureService.java#L27)) ·
sliding-window **rate limiting** ([`RateLimiter.java:13`](kaarigarsetu-java/src/main/java/com/kaarigarsetu/security/RateLimiter.java#L13)) · **CSRF** and security-header filters · hash-chained audit log ·
constant-time comparisons · **named moderators and two-person approval** (`ModeratorService`: one moderator proposes, a different one approves, the marking-as-executed and the carrying-out in one JDBC transaction; the rule is also in the approving `UPDATE` and a `CHECK` constraint; `AuthFilter` re-checks on every request that the moderator's account is still active; `ModeratorAdminCli` is the operator-only tool, and an architecture test proves nothing in the web application can call the methods that write moderator accounts) · **encryption at rest** (`DataCipher`: AES-256-GCM with a random nonce per value, a key ring whose first key encrypts, one derived key per use, and the column and row id as authenticated associated data, so a ciphertext copied elsewhere does not open; every DAO seals and opens its own columns and takes the id before the insert with `nextval`; `ChainedAuditFileSink` encrypts each audit line while the chain, head record and anchors stay over the plaintext; `SealAtRestCli` converts, checks and re-keys) · the **fix for the targeted sign-in lock-out** (`AuthService`: a returning browser is a trusted device with its own `RateLimiter` bucket and its own OTP challenge; each code is bound to the browser that asked for it; an unknown browser first solves a hashcash-style puzzle, `ProofOfWork`, an HMAC-signed stateless challenge accepted once; `TrustedDeviceDao` stores only a keyed hash of the cookie token; the browser half is `static/js/pow.js`, a hand-written SHA-256 checked against Node's) · the **worker dispute flow** (`ConfirmationResponseService`: ownership checks, a 30-day window and a cap of 3 open disputes enforced with a row lock inside a JDBC transaction, and a database view `counted_attestations` so every score reads one definition of what counts) · and the SQL-concatenation test above. Details: `docs/SECURITY_RUNBOOK.md`, `docs/STRIDE_THREAT_MODEL.md`,
`docs/PRIVACY_DPDP.md`. **Replay-voice detection** (`assessment/ReplayDetector`, `ReplayChallenge`, `SpokenCode`, `TextShingles`, `dao/ReplayDao`: a stateless HMAC token for the per-question spoken code, keyed one-way hashes of acoustic landmarks and word trigrams searched in single set-based SQL statements, a decision that fails closed; `docs/REPLAY_DETECTION.md`).

## 12. Where the code differs from the Review 1 slides

The deck's snippets are illustrations. The real code is more developed, and in a few places named differently, so you are not caught out in a
review:

| Slide | The real code |
|---|---|
| `abstract int evaluateResponse(String transcript)` | The abstract members are `supportedTrade()` and `feedbackFor(score)`; the shared algorithms `assess(...)` and `summarize(...)` are `final`. Scoring each answer is done by the ML service, and the subclass averages and words the result. |
| `InvalidOTPException extends Exception` (checked) | All user-defined exceptions extend **`RuntimeException`** (unchecked) and are translated centrally by `ApiErrors`. |
| `Map<String, TrustScore> trustScores`, `TreeMap` leaderboard | **Not implemented.** The trust score is computed per worker by `ReputationScoring` / `TrustScoreService` and stored in the database; there is no in-memory leaderboard. |
| `Files.writeString(Paths.get("audit.log"), ...)` | The simple append is `AuditLogWriter`; the audit trail actually used is the hash-chained `ChainedAuditFileSink` plus a database table with append-only triggers. |
| Servlet at `/attestation` using `req.getParameter` | Servlets live at `/api/...`, read JSON bodies and use CSRF-protected sessions. |
| JSP with scriptlets (`<%= worker.getSkillScore() %>`) | JSPs use **JSTL / EL** with output escaping and no scriptlets. |
| Electrician and Plumber only | Six trades: electrician, plumber, carpenter, mason, welder, painter. The rubrics are **TEST ONLY** placeholders until an expert supplies real ones. |
| `exportPdf` | The method exists and is tested, but only the JSON export is called during credential issue. |

## Running and testing the Java code

```bash
./scripts/dev-setup.sh                 # once: secrets, database, signing key, Python environment, WAR build
./scripts/dev.sh                       # starts the ML service and Tomcat on http://localhost:8090
cd kaarigarsetu-java && mvn test       # 498 tests (set TEST_DB_URL for the database tests, see README.md)
./scripts/console.sh                   # the JavaFX moderator console
```

See `README.md` for the full guide and `docs/API_REFERENCE.md` for every endpoint.
