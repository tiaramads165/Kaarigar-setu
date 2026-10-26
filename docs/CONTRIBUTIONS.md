# Kaarigar Setu: contributions and who presents what

Team: **Madhuja**, **Ayushi**, **Ritika**.

## 1. Statement of authorship

The system was **designed and built by Madhuja**, working with Claude Code (an AI coding assistant): the Java application, the ML service, the database, the web
pages, the moderator console, the scripts, the tests and the Docker set-up. Ayushi and Ritika's contributions are listed in section 4. The map in section 3 says who
**presents and answers questions on** each part of the project in the review; it is a presentation responsibility, not a claim about who wrote the files.

## 2. What is in each folder

| Folder | What it contains | What it does |
|---|---|---|
| `kaarigarsetu-java/src/main/java/.../model`, `exception`, `util`, `config` | 18 record classes, 15 exception classes, helpers (JSON, WAV check, file export), settings and the connection pool | the data shapes, the error types, the supporting code every other part uses |
| `kaarigarsetu-java/src/main/java/.../dao` | 13 data-access classes plus a base class | all SQL, one class per table, always parameterised |
| `kaarigarsetu-java/src/main/java/.../assessment`, `worker`, `employer`, `attestation`, `trust`, `credential` | the business rules | the voice test and scoring, registration and the passport, employer confirmation, the trust score, the signed credential |
| `kaarigarsetu-java/src/main/java/.../web`, `moderator`, `privacy`, `admin` | servlets, filters, sessions, the application wiring, staff services, erasure, operator tools | how requests enter and leave, staff review, data deletion |
| `kaarigarsetu-java/src/main/java/.../security`, `ml` | login codes, signing, encryption, audit, rate limits; the client for the ML service | protection and the link to the Python service |
| `kaarigarsetu-java/src/main/webapp` | 7 JSP pages, 8 scripts, 1 stylesheet, `web.xml` | everything the user sees and clicks |
| `kaarigarsetu-java/src/test` | 64 test files, 498 tests | checks for every part |
| `kaarigarsetu-moderator-console` | the JavaFX desktop console, 18 tests | the staff screens |
| `kaarigarsetu-ml-service` | the Python service, rubrics, the fraud model, 398 tests | speech to text, answer scoring, fraud check, PageRank, audio fingerprint |
| `database` | `schema.sql`, 10 migrations, `setup_roles.sql` | the 14 tables and the restricted database role |
| `docker`, `docker-compose.yml`, `.github/workflows` | container files, proxy config, the pipeline | running the whole system and testing it automatically |
| `scripts` | set-up, demo data, end-to-end and API tests, the attack run, verifier, key tools | running, seeding and testing |
| `docs` | design, API, privacy, security, test reports, review guides | the written documentation |

## 3. Who presents and explains each part in the review

| Presenter | Parts she presents (and must be able to answer questions on) | Folders |
|---|---|---|
| **Ayushi** | the Java structure: packages and layering, records, exceptions, collections, the data-access pattern, the trade-scoring classes (`AssessmentService` and its six subclasses), the unit tests | `.../model`, `.../exception`, `.../util`, `.../dao`, `.../assessment`, `.../trust` (formula classes), `kaarigarsetu-java/src/test` |
| **Ritika** | the user interface and usability: the pages, scripts and stylesheet, the bilingual voice-first screens, the error messages, the moderator console screens | `kaarigarsetu-java/src/main/webapp`, `kaarigarsetu-moderator-console`, the screenshots in `docs/review-screenshots` |
| **Madhuja** | the backend and the connections: servlets and filters, services, the ML service and its client, the database, security, Docker and the pipeline, the scripts, and how Claude Code was used | `.../web`, `.../worker`, `.../employer`, `.../attestation`, `.../credential`, `.../moderator`, `.../security`, `.../ml`, `.../privacy`, `.../admin`, `kaarigarsetu-ml-service`, `database`, `docker`, `scripts`, `.github` |

Each presenter should read the matching chapters of `docs/IMPLEMENTATION_WALKTHROUGH.md` and the guide `docs/PROJECT_REVIEW_ROLES.md` before the review.

## 4. Ayushi's and Ritika's actual contributions

Fill in only what is true. Examples of things that count when they really happened:

| Person | What she did | When / evidence |
|---|---|---|
| Ayushi | | |
| Ritika | | |

Suggested real tasks if there is time before the review (each is small, checkable and can be done with help):

* **Ayushi:** write unit tests for `SkillScoring` and `AttestationRules`; add a seventh trade end to end using the "add a trade" steps in chapter 7.5 of the walkthrough; write the Javadoc for one package.
* **Ritika:** rewrite the Hindi wording on the screens; test the pages on two real phones and write down the problems; add a notification banner to the worker's passport page; add a few browser tests.

## 5. How to describe this in the review

Say it plainly: "Madhuja designed and built the system with Claude Code. Ayushi [what she actually did] and presents the Java structure. Ritika [what she actually did] and presents the user interface." Reviewers can accept an unequal split when it is stated honestly; a claim they can disprove with one question is the real risk.
