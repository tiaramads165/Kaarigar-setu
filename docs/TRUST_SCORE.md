# The Trust Score (the worker's "credit score")

A worker's **trust score** (0 to 100) says how much employers who have actually worked with them vouch for them, and how far those
employers can themselves be believed. It is separate from the **skill score**, which comes from the voice assessment. A worker can be
highly skilled and still have a low trust score, because nobody has confirmed the work yet.

Where it lives: `ReputationScoring` (the pure formula, easy to test), `TrustScoreService` (loads the data, runs PageRank, stores the result), and
the **Trust report** card on the worker dashboard. All numbers below are the defaults in `ReputationScoring.Params`.

## 1. What employers are asked

When an employer confirms a worker's job they give, on top of the job role, dates and duration:

| Question | Answer | Weight in the satisfaction value |
|---|---|---|
| Quality of work | 1 to 5 | 40 % |
| Punctuality and reliability | 1 to 5 | 25 % |
| Conduct and honesty | 1 to 5 | 20 % |
| Would you hire again? | yes / no / prefer not to say | 15 % |

The three ratings are required by the API (`qualityRating`, `punctualityRating`, `conductRating`) and the form. Only the answers actually given count (the
weights are re-balanced), and confirmations made before ratings existed count as a neutral 0.55 at half weight, so older data is not lost and not over-trusted.
There is no free-text comment on purpose: it would be unscorable personal data and a place for abuse or prompt injection.

## 2. How one piece of feedback is weighed

`weight = employer credibility x recency x engagement x (1 - half the fraud-anomaly score)`

| Part | Rule |
|---|---|
| **Employer credibility** (0.1 to 1) | base 0.40; +0.15 if vetted by a moderator (never automatic; see FRAUD_ATTACK_SIMULATION.md); +0.20 for track record (full at 5 approved confirmations); +0.25 for network standing (PageRank over the employer-worker graph, mapped to 0.3 to 1); then reduced by 30 % for every confirmation of theirs a moderator ruled fraudulent (never below 0.1) |
| **Recency** | `e^(-age in days / 365)`, never below 0.15: last month counts fully, three years ago barely |
| **Engagement** | 0.6 for a one-day job rising to 1.0 at 30 days or more; 0.8 when the length is unknown |
| **Fraud anomaly** | the Isolation Forest score of that attestation (0 to 1): a suspicious-looking confirmation counts up to half as much |

**Rater-leniency calibration.** An employer who rates everyone 5 stars tells us little. For every employer with at least 3 rated confirmations, half of the amount by which their
average satisfaction differs from everyone's average (at most 0.10) is taken off (lenient) or added (harsh) to their ratings. A generous or harsh habit therefore does not decide
a worker's score. In the demo data, Khan Contractors (always 5) is discounted and Raju Fabricators (marks hard) is lifted.

## 3. Combining into the score

1. **Reputation** (Bayesian average): `rep = (1.5 x 0.60 + sum(weight x satisfaction)) / (1.5 + sum(weight))`. With little or weak evidence it stays near the prior 0.60 instead of jumping to the extremes.
2. **Confidence** `C = min(1, distinct employers / 3)`. Confirmations from the same employer do not add confidence.
3. **Score** `= 100 x (C x rep + (1 - C) x 0.25)`, then **minus 5 for each confirmation about the worker that a moderator ruled fraudulent** (at most 15), kept between 0 and 100.

Worked examples (a well-established employer, fresh feedback, all 5 stars): one employer gives about 42; three different employers give about 86; the same single employer repeated five times is still
about 42 (one source). Three employers who all give 2 stars give about 30. Each is checked by a unit test.

## 4. Bands and the report

| Score | Band |
|---|---|
| 80 and above | **Excellent** |
| 65 to 79.99 | **Good** |
| 50 to 64.99 | **Fair** |
| below 50 | **Building** |
| no approved confirmation yet | **No history yet** (not a bad score) |

The dashboard shows the score, the band and a credit-report style breakdown: employers who confirmed (and how many confirmations), overall satisfaction, the three rating averages, the
would-hire-again rate, the average credibility of those employers, how recent the latest confirmation is, consistency of the feedback, and any confirmations ruled fraudulent. Under "How to improve it"
it gives up to four plain tips generated from the data (for example "Ask 2 more different employers to confirm your work", "Your latest confirmation is about 11 months old", "Employers rated your punctuality lowest").
The band is also part of the **signed credential** (`trustBand`), so a verifier sees it and it cannot be edited.

## 5. When it is recalculated

After every approved attestation, every moderator approval, and every erasure. If the ML service (needed only for the PageRank standing of employers) cannot be reached, scores are still computed with a neutral standing of 0.5 for
every employer and the gap is written to the audit log (`TRUST_PAGERANK_UNAVAILABLE`); a confirmation is never lost because PageRank was down.

## 6. What it resists, and what it does not

| Attack or flaw | Defence |
|---|---|
| A friend or relative gives glowing feedback | needs 3 different employers for full confidence; their own credibility is low until they have a record; PageRank standing rewards well-connected employers |
| One employer repeating confirmations | counts as one employer |
| "Always five stars" employers | leniency calibration |
| Fake confirmations in a ring | fraud model lowers their weight; a moderator ruling removes them and costs the worker 5 points and the employer 30 % credibility |
| Stale reputation | recency decay; the dashboard asks for fresh confirmations |
| Score jumps with one data point | Bayesian shrinkage towards the prior |

Not solved: the OTP only proves the employer controls a phone, not that the work happened; a patient fraudster who builds several genuine-looking employer identities can still climb slowly; a moderator ruling of
"fraud" also penalises a worker who was a victim of a fake confirmation (the 5 points are configurable in code, `Params.integrityPenalty`); nothing yet lets a worker dispute feedback or an employer correct it after the fact; the
weights and thresholds are reasoned defaults, not fitted to real outcomes, and need review once real data exists.

## 6a. Network standing (the plain PageRank view, shown beside the score)

Next to the trust score the worker's report shows a second number, **Network standing (PageRank), 0 to 100**. It answers a different question: "how strongly does the endorsement graph point at this worker, compared with everyone else?" It does **not** change the trust score and the trust score does not change it.

1. **Edge weight** for each approved confirmation, employer to worker: `w = h x e^(-age/365) x (1 - 0.5 x anomaly)`, kept between 0.05 and 1. `h` is 1.0 if the employer would hire again, 0.8 if that was not stated, 0.4 if they would not. `age` is the age of the confirmation in days; `anomaly` is the fraud model's 0-1 score. (`ReputationScoring.endorsementWeight`)
2. **PageRank** over the whole worker-employer graph: `PR(v) = (1 - d)/N + d x sum over u->v of PR(u) x w(u,v) / sum of w(u,z)`, damping `d = 0.85` (networkx, `graph_engine.py`).
3. **Rescale**: `100 x PR(v) / max PR`, so the best-connected node is 100.

It is relative (it shifts when others join), workers with no confirmation have no value (not zero), and if the ML service is unavailable the value is left out and `NETWORK_STANDING_UNAVAILABLE` is audited. PageRank also feeds the trust score in a separate way: it gives each *employer* a standing, which scales how much that employer's ratings count (section 2). Stored inside `workers.trust_report` as `networkStanding`.

## 7. Demo data

`./scripts/demo-seed.sh` creates nine made-up workers and seven employers with assessments, dated employer feedback, credentials and these computed scores (see `docs/DEMO_SCRIPT.md`):
Pooja Sharma (painter, 6 employers) and Ramesh Kumar (electrician, 4) are Excellent; Sunita Devi (plumber) is Good; Suresh Yadav (mason, mixed feedback) is Fair; Mohammed Irfan and Lakshmi Naidu (a moderator-ruled fraud) and
Anil Verma (skill 76, but only one employer) are Building; Imran Ansari (assessed, nobody has confirmed him) and Deepak Rathore (brand new) have no history yet.
