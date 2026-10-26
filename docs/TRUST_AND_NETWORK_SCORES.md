# Kaarigar Setu: Trust Score and Network Standing

*How the two reputation numbers are calculated, with the formulas and the technology behind them.*

Both numbers describe a worker from what **employers** said about the worker's confirmed jobs. They answer different questions and are shown side by side on the worker dashboard.

| | **Trust score** (the worker's "credit score") | **Network standing** (PageRank) |
|---|---|---|
| Question it answers | How reliable is this worker, given what employers said? | How strongly does the endorsement graph point at this worker, compared with others? |
| Range | 0 to 100, with a band (Excellent, Good, Fair, Building, No history) | 0 to 100, best-connected node = 100 |
| Absolute or relative | Absolute: 82 means the same thing tomorrow | Relative: moves when other workers join |
| Uses the three ratings (quality, punctuality, conduct) | Yes | No |
| Needs several employers | Yes (confidence rule) | No |
| Goes into the signed credential | Yes (the band) | No |
| Changes the other number | Takes PageRank only as a small input to each *employer's* credibility | Never feeds the trust score |

---

## 1. Where the data comes from

1. A worker finishes a job. The employer opens the dashboard and starts a **confirmation**: job role, work date, duration, would hire again (yes / no / not stated), and three ratings from 1 to 5 (quality of work, punctuality and reliability, conduct and honesty).
2. A one-time code is sent to the **employer's own registered phone** and the employer enters it to confirm. This is a second factor for the employer (it proves the person confirming controls that phone number), **not** proof that the worker was present or agrees. So the worker gets a say afterwards (section 1a below): they see every confirmation about them and may accept or dispute it.
3. The **fraud model** (Isolation Forest) scores each confirmation on four features: how often the same address and the same device appear, minutes since the employer's previous confirmation, and a reciprocity ratio. Output: an **anomaly score** *a* between 0 and 1. A high score sends the confirmation to a human moderator and it does not count until approved.
4. Only **approved** confirmations feed the two scores, and only those not held out by a dispute (section 1a). A moderator ruling of "fraud" removes the confirmation and penalises its sender.

Scores are recalculated (all workers, in one pass) whenever a confirmation is approved, a moderator rules on a flagged one, or an account is erased.

### 1a. The worker's say: accept or dispute

The worker's dashboard lists every approved confirmation about them: employer's business name, job, work date and the ratings (never a phone number).

* **Accept** records that the worker agrees. It changes no score. It is optional on purpose: if acceptance were required, a worker could refuse every bad rating and own their trust score.
* **Dispute** (a reason of 10 to 300 characters) holds the confirmation out of the trust score, the credential's employer count, the passport and the employer's track record **until a moderator rules**:
  *UPHELD* (the confirmation counts again) or *REMOVED* (it never counts). Disputing is the only way to take a rating out, and a moderator decides.
* Limits, so disputing cannot be used to hide ratings: a confirmation can be answered **once**, only within **30 days** of approval (`DISPUTE_WINDOW_DAYS`), and a worker may have at most **3 open** disputes (`DISPUTE_MAX_OPEN`). The cap is enforced under a row lock; without it, simultaneous requests could slip past it.
* All four places that count confirmations read the one database view `counted_attestations` (migration 007), so the scores, the credential and the passport cannot disagree about what counts.
* A trust score can go up after a dispute (the held-out rating was dragging it down) and back down after an UPHELD ruling. The credential is re-issued after every dispute and every ruling.

---

## 2. The trust score

### 2.1 Satisfaction of one confirmation (0 to 1)

Each rating `r` from 1 to 5 is converted to `(r - 1) / 4`. The satisfaction `s` is the weighted average of the answers that were actually given:

```
s = ( 0.40*q + 0.25*p + 0.20*c + 0.15*h ) / (sum of the weights used)

q = (quality - 1)/4        p = (punctuality - 1)/4        c = (conduct - 1)/4
h = 1 if would hire again, 0 if not   (left out if not stated)
```

A confirmation with no ratings at all counts as a neutral 0.55 at half weight.

### 2.2 Leniency calibration

An employer who rates everyone far above the crowd has a little taken off; one who marks far below has a little added. Only employers with at least 3 rated confirmations are calibrated:

```
offset(employer) = clamp( 0.5 * (employer average - average of all rated feedback), -0.10, +0.10 )
s_calibrated     = clamp( s - offset, 0, 1 )
```

### 2.3 Employer credibility (0.1 to 1)

How much an employer can be believed:

```
credibility = ( 0.40
              + 0.15 * [employer is VETTED by a moderator]
              + 0.20 * min(1, approved confirmations given / 5)
              + 0.25 * network standing of the employer ) * max(0.2, 1 - 0.3 * fraud rulings)
              clamped to [0.1, 1.0]
```

The employer's **network standing** (0.3 to 1) comes from PageRank, see section 3.4.

### 2.4 Weight of one confirmation

```
w = credibility * recency * engagement * (1 - 0.5 * a)

recency    = max(0.15, e^(-age in days / 365))
engagement = 0.8 if duration unknown, else 0.6 + 0.4 * min(1, days / 30)
a          = anomaly score 0..1 from the fraud model
```

### 2.5 Combining into the score

```
reputation = ( k*0.60 + sum(w * s) ) / ( k + sum(w) )       k = 1.5   (Bayesian average)
confidence = min(1, number of distinct employers / 3)
raw        = 100 * ( confidence * reputation + (1 - confidence) * 0.25 )
score      = clamp( raw - min(15, 5 * fraud rulings about this worker), 0, 100 )
```

- The **prior 0.60** keeps a worker with little evidence near the middle instead of at an extreme.
- **Confidence** means one employer, however glowing, can never produce a top score. The same employer confirming five times is still one source.
- Worked examples (well-established employer, fresh feedback, all 5 stars): one employer gives about 42; three different employers give about 86; five confirmations from the same employer still give about 42; three employers who all give 2 stars give about 30. Each is a unit test.

### 2.5a The top band needs a vetted employer

```
if no confirming employer has been vetted by a moderator:   score = min(score, 79.99)      (just under Excellent)
```

Why: the score is a weighted average, so a ring of fake employer accounts looked like "three different employers" and three brand-new accounts were enough for
Excellent (measured in `docs/FRAUD_ATTACK_SIMULATION.md`). *Vetted* means a moderator checked who the employer is outside the application; it is **not**
granted automatically (an earlier version marked an employer verified after its first approved confirmation, which every fake account also got). The worker's
trust report says when this rule is holding the score back. The rule is one parameter, `excellentNeedsVettedEmployer`.

### 2.6 Bands

| Score | Band |
|---|---|
| 80 and above | Excellent |
| 65 to 79.99 | Good |
| 50 to 64.99 | Fair |
| below 50 | Building |
| no approved confirmation yet | No history (not a bad score) |

The band is part of the **signed credential** (`trustBand`), so a verifier sees it and it cannot be edited.

---

## 3. Network standing (PageRank)

This is the plain graph view, and it follows the project's design note.

### 3.1 The graph

- **Nodes:** every employer (`E<id>`) and every worker (`W<id>`) that appears in an approved confirmation.
- **Edges:** one directed edge **employer to worker** per approved confirmation (an "endorsement").

### 3.2 Edge weight

```
w = h * e^(-age in days / 365) * (1 - 0.5 * a)          kept between 0.05 and 1

h = 1.0  if the employer would hire the worker again
    0.8  if that was not stated
    0.4  if the employer would not
```

Newer, happier, trustworthy confirmations count more.

### 3.3 PageRank

```
PR(v) = (1 - d) / N  +  d * SUM over edges u -> v of  PR(u) * w(u,v) / SUM over z of w(u,z)

d = 0.85 (damping factor)       N = number of nodes
```

PageRank is a relative score: a node is important if important nodes point to it. Because it works on the whole graph, a worker's standing depends on everyone else.

### 3.4 Scale to 0-100

```
standing(v) = 100 * PR(v) / max over all nodes of PR
```

A worker with no confirmation has **no value** (it is not shown as 0).

### 3.5 How PageRank also reaches the trust score

A second, separate PageRank run uses the same endorsement edges with a weaker **reverse** edge (worker to employer, half the weight, minimum 0.01), so well-connected employers earn standing. An employer's scaled PageRank `S` (0 to 100) becomes `0.3 + 0.7 * S / 100`, which is the 0.25-weighted term inside employer credibility (2.3). The worker's *own* network standing never enters the trust score.

---

## 4. Technology used

| Part | Technology | Where |
|---|---|---|
| Trust score formula | Pure Java 17 (JDK 21 build), no database or network in the formula so every rule is unit-testable | `trust/ReputationScoring.java` (all tunable numbers in `Params`) |
| Orchestration | Java service: loads approved feedback, runs calibration, asks for PageRank, scores every worker, stores results, audits | `trust/TrustScoreService.java` |
| Edge weight for network standing | Java static method | `ReputationScoring.endorsementWeight` |
| Bands | Java enum | `trust/TrustBand.java` |
| PageRank | Python 3.11, **networkx** `nx.pagerank(graph, weight="weight")` (damping 0.85), **FastAPI** endpoint `POST /trust-score` with Pydantic validation (weight 0 to 1, up to 50,000 edges) | `kaarigarsetu-ml-service/app/services/graph_engine.py`, `app/routes/trust.py` |
| Java to Python call | HTTP + JSON with an internal token, Gson | `ml/MlServiceClient.java` (interface `MlGateway`) |
| Fraud anomaly score | **scikit-learn** Isolation Forest on four features (`ip_frequency`, `device_frequency`, `minutes_since_previous`, `reciprocity_ratio`), trained on synthetic data | `kaarigarsetu-ml-service/app/services/fraud_detector.py`, `app/training/synthetic_data.py` |
| Employer to worker adjacency list | Java Collections (in-memory graph) | `trust/TrustGraphService.java` |
| Storage | PostgreSQL 16: `attestations` (ratings 1 to 5 as `SMALLINT`), `trust_edges` (weights), `workers.trust_score`, `workers.trust_band`, `workers.trust_report` (JSON text, includes `networkStanding`) | `database/migrations/004_*.sql`, DAO classes with `PreparedStatement` only |
| Display | JSP + JSTL dashboard, `<progress>` bars; the signed credential payload carries the band | `worker-dashboard.jsp`, `credential/CredentialService.java` |
| Tamper evidence | Hash-chained audit log (database triggers plus an HMAC file chain); credentials signed with RSA-3072 | `security/AuditLogger`, `credential/` |
| Verification | JUnit 5 and Mockito (260 Java tests), pytest (ML service), an 80-check end-to-end script | `src/test/...`, `scripts/e2e.py` |

### Failure behaviour

- **PageRank service unreachable:** trust scores are still computed, with a neutral employer standing of 0.5 for everyone, and the gap is audited (`TRUST_PAGERANK_UNAVAILABLE`). The network standing value is left out and audited (`NETWORK_STANDING_UNAVAILABLE`). A confirmation is never lost because the ML service was down.
- **Malformed data:** the ML endpoint rejects weights outside (0, 1] and oversize graphs; the Java client validates the response.

---

## 5. What these numbers resist, and what they do not

- **Resists:** one employer inflating a worker (confidence rule); an "always 5 stars" employer (leniency calibration); bursts of confirmations from one source (fraud model, human review, anomaly discount); old feedback dominating (recency); employers caught in fraud (credibility multiplied down, a penalty to the worker for confirmations ruled fraudulent).
- **Does not resist:** a ring of colluding people who each hold real, verified accounts and confirm each other slowly over a long time. The fraud model looks for bursts and same-source patterns, not slow collusion.
- **Network standing caveats:** it is relative (the top worker is always 100), shifts as others join, and ignores the ratings, so it must be read together with the trust score.
- **Fraud model trained on synthetic data only:** the Isolation Forest has never seen real fraud, so its anomaly scores are a reasoned first version.
- **Not yet validated on real data:** the weights (40/25/20/15, 0.60 prior, 3-employer confidence, and the rest) are reasoned defaults that live in one `Params` record for easy tuning. They have not been calibrated against real pilot data.

## 6. Demo data (made-up accounts)

| Worker | Trust score | Trust band | Network standing |
|---|---|---|---|
| Pooja Sharma | 82.58 | Excellent | 100.0 |
| Ramesh Kumar | 81.45 | Excellent | 77.59 |
| Sunita Devi | 76.21 | Good | 69.76 |
| Suresh Yadav | 57.55 | Fair | 52.20 |
| Mohammed Irfan | 49.81 | Building | 52.77 |
| Lakshmi Naidu | 48.91 | Building | 61.48 |
| Anil Verma | 39.68 | Building | 43.24 |
| Imran Ansari, Deepak Rathore | 0.00 | No history | none |

Lakshmi Naidu shows the difference: her network standing is fairly high because PageRank only sees her approved confirmations, but her trust score is held down by a rejected confirmation and by having only two distinct employers (confidence 0.67).
