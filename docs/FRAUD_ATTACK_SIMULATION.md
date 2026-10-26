# Fake endorsements as an attack: simulation and the defence it led to

The trust score is only worth something if buying it is hard. This page treats fake employer confirmations as an attack, measures how easy the attack was,
describes the fix that was built, and states what is still possible. Every number below is produced by code in the repository (it calls the production
scoring code, not a model of it) and is guarded by tests, so the page cannot quietly drift from the system.

* Simulation: `kaarigarsetu-java/src/test/java/com/kaarigarsetu/trust/TrustAttackSimulation.java` (run its `main` to print the tables)
* Guarantees as tests: `TrustAttackSimulationTest`, `ReputationScoringTest`, `TrustScoreServiceTest`
* End to end through the real API: `scripts/e2e.py` section 9b and `scripts/security_attack_demo.py` FRAUD-01

## 1. The attacker and what they are given

A worker (or a friend of the worker) registers fake employer accounts and has each confirm the worker's "job" with perfect ratings, "would hire again", a
30-day job, and nothing that looks unusual to the fraud model. Every confirmation needs a one-time code, which goes to the **fake employer's own phone**
(see section 5), so the attacker needs one SIM per fake employer.

## 2. What the fraud model can and cannot see

The Isolation Forest looks at four things per confirmation: how often the same address and the same device appear in the last 24 hours, minutes since the
employer's previous confirmation, and how reciprocal the vouching is. Measured on the shipped model (synthetic data, 2,000 honest rows; see
`docs/ML_EVALUATION.md` for the method):

| Attacker | Flagged for human review |
|---|---|
| Honest rows (the false-alarm rate) | 2.6 % |
| Crude: rapid burst | 85 % |
| Crude: reciprocal vouching ring | 81 % |
| Crude: many confirmations from one address | 100 % |
| Crude: one device vouching for many | 100 % |
| **Adaptive:** one confirmation a day, own address and device | **0 %** |
| **Adaptive:** confirmations five hours apart | **0 %** |
| **Adaptive:** fast, but spread over many addresses and devices (mobile data, VPN) | **0 %** |
| Fast burst from one address | 100 % |

A patient attacker who spreads the activity out looks exactly like an honest user. **The fraud model alone cannot stop a determined ring.** That is not a
defect to hide: it is the reason the score also needs structural rules.

Two further limits of the features, stated so nobody over-trusts them: many Indian mobile networks put thousands of phones behind one public address, and a
"device" here is a hash of the browser's user-agent and language, which is trivially varied. Address and device frequency are useful signals, not proof.

## 3. The weakness the simulation exposed

The score is a weighted average, so each employer's credibility mostly cancels out when all employers are equally credible; what protects a worker is the
rule that confidence needs several **different** employers. Fake employers are different employers.

| What the attacker did to each fake employer | Needed for Good (65) | Needed for Excellent (80): **before** the vetting rule | Needed for Excellent (80): **now** |
|---|---|---|---|
| brand-new, unverified account | 3 employers | **3 employers** | never (30 tried) |
| has already given 5 approved confirmations (to other fake workers) | 3 employers | **3 employers** | never (30 tried) |
| warmed up AND maximum PageRank standing | 3 employers | **3 employers** | never (30 tried) |
| also vetted by a moderator (an insider, a bribe or a breach of the vetting process) | 3 employers | 3 employers | 3 employers |

So **three throwaway accounts were enough to give a worker an "Excellent" score.** The track-record and PageRank terms in employer credibility did not
help, because the verified flag, which adds 0.15 of credibility, was granted **automatically** after an employer's first approved confirmation, i.e. to
every fake account that completed one. The simulation made this visible; reading the code alone did not.

One employer confirming many times is a separate case and was already safe: 100 perfect confirmations from one employer still score 49.8, because confidence
counts distinct employers.

## 4. The fix

1. **"Verified" now means vetted by a moderator.** It is no longer granted automatically (`AttestationWorkflowService`, `ModeratorService`). Who vetted an
   employer and when are stored (`employers.verified_by`, `verified_at`, migration 005) and every vetting is in the audit trail (`EMPLOYER_VETTED`).
2. **The top band needs a vetted employer.** If none of the employers who confirmed a worker is vetted, the score is held at 79.99, just under Excellent
   (`ReputationScoring`, parameter `excellentNeedsVettedEmployer`). The worker is told why in the trust report.
3. **Moderators can vet.** `GET /api/moderator/employers` (no phone numbers) and `POST /api/moderator/vet-employer`, and an Employers tab in the JavaFX
   console. Vetting refreshes the trust scores and re-issues the credentials of the workers that employer confirmed.

Proof that it works, three ways: the simulation table above; `scripts/e2e.py` 9b (five unvetted employers with perfect ratings: held at exactly 79.99;
after one moderator vetting: Excellent); and the attack harness (FRAUD-01).

## 5. What is still possible (read this before presenting)

| Residual risk | Why it remains | What bounds it |
|---|---|---|
| An **insider or a fooled moderator** vets a fake employer | Vetting is a human check; the evidence lives outside the application | Vetting needs two different named moderators (one proposes, another approves) and the audit trail names both; three accounts are still needed; the console shows how many confirmations an employer has. Residual: two colluding moderators, or the operator who creates accounts |
| **SIM farms.** Each fake employer needs only its own phone number | A one-time code proves control of a number, not honesty | Rate limits, the fraud model for crude rings, and the vetting requirement for the top band; the Good band (up to 79.99) remains reachable by three fake employers |
| **Employers confirming without the worker's involvement** | The code goes to the employer's own phone (step-up login for the employer), not to the worker; the worker is not asked to accept | **Mitigated** (migration 006): the worker sees every confirmation about them and can dispute one; a disputed confirmation is held out of every score until a moderator rules. Not prevented, and not meant to be: an employer can still post a rating about any registered worker, and the worker has the remedy. Residual: the worker must notice and answer within the dispute window (30 days), and a worker can hide up to 3 bad ratings at a time until a moderator rules (harness FRAUD-02) |
| Honest employers who share a network address are treated as one source by the fraud features | Carrier-grade address sharing | The model only *flags for a human*; nothing is rejected automatically |
| Scores are not validated against real outcomes | No pilot data yet | All thresholds are parameters (`ReputationScoring.Params`) and should be tuned on real data |

The code and the earlier documentation disagreed on one point, which this work found: `docs/TRUST_AND_NETWORK_SCORES.md` said the worker receives the code
and so "proves the worker was present". The code (and the STRIDE model, S2) send it to the employer. The documentation has been corrected; the gap in the
table above is the honest consequence.
