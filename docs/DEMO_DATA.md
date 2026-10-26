# Kaarigar Setu: demo data already stored in the database

Generated 2026-10-12 from the local dev database `ks_blueprint`. **All names and phone numbers are made up** (the demo seeder, `scripts/demo-seed.sh`, uses 90000 00101-00299, which are never contacted). Phones are stored encrypted in the database; the numbers below come from the seed file. Sign in as any of them without a code at `http://localhost:8090/demo`.

## Workers (9)

| ID | Name | Phone | Trade | Lang | Skill score | Skill band | Trust score | Trust band | Network standing (PageRank) | Consent date |
|---|---|---|---|---|---|---|---|---|---|---|
| 260 | Ramesh Kumar | 9000000101 | Electrician | hi | 81.30 | PASS | 81.45 | EXCELLENT | 77.59 | 2026-10-09 |
| 261 | Sunita Devi | 9000000102 | Plumber | hi | 74.10 | PASS | 76.21 | GOOD | 69.76 | 2026-10-09 |
| 262 | Mohammed Irfan | 9000000103 | Carpenter | hi | 64.50 | BORDERLINE | 49.81 | BUILDING | 52.77 | 2026-10-09 |
| 263 | Suresh Yadav | 9000000104 | Mason | hi | 55.50 | BORDERLINE | 57.55 | FAIR | 52.2 | 2026-10-09 |
| 264 | Anil Verma | 9000000105 | Welder | en | 76.50 | PASS | 39.68 | BUILDING | 43.24 | 2026-10-09 |
| 265 | Pooja Sharma | 9000000106 | Painter | hi | 92.00 | PASS | 82.58 | EXCELLENT | 100.0 | 2026-10-09 |
| 266 | Imran Ansari | 9000000107 | Electrician | en | 40.00 | NEEDS_REVIEW | 0.00 | NO_HISTORY | - | 2026-10-09 |
| 267 | Lakshmi Naidu | 9000000108 | Plumber | hi | 69.40 | BORDERLINE | 48.91 | BUILDING | 61.48 | 2026-10-09 |
| 268 | Deepak Rathore | 9000000109 | Carpenter | hi | 0.00 | - | 0.00 | NO_HISTORY | - | 2026-10-09 |

Skill bands: Pass is 70 or more, Borderline 50-69, Needs review below 50. Trust bands: Excellent 80 or more, Good 65+, Fair 50+, Building below 50 with some history, No history. Network standing is the plain PageRank view (relative, best node = 100, "-" when nobody has confirmed the worker); it is shown beside the trust score and does not change it (see `docs/TRUST_SCORE.md`, section 6a).

## Employers (7)

| ID | Name | Phone | Verified |
|---|---|---|---|
| 56 | Sharma Constructions | 9000000201 | yes |
| 57 | Gupta Electricals | 9000000202 | yes |
| 58 | Verma & Sons Builders | 9000000203 | yes |
| 59 | Singh Interiors | 9000000204 | yes |
| 60 | Patel Hardware and Repairs | 9000000205 | yes |
| 61 | Khan Contractors | 9000000206 | yes |
| 62 | Raju Fabricators | 9000000207 | yes |

Khan Contractors rates everyone 5 stars (the trust formula discounts that habit); Raju Fabricators marks hard.

## Employer confirmations of work (23)

| ID | Worker | Employer | Job | Work date | Quality/Punctuality/Conduct | Would hire again | Status |
|---|---|---|---|---|---|---|---|
| 201 | Ramesh Kumar | Sharma Constructions | House wiring | 2026-09-19 | 5/5/5 | yes | APPROVED |
| 202 | Ramesh Kumar | Gupta Electricals | Shop rewiring | 2026-08-10 | 5/4/5 | yes | APPROVED |
| 203 | Ramesh Kumar | Verma & Sons Builders | Panel board work | 2026-06-11 | 4/5/5 | yes | APPROVED |
| 204 | Ramesh Kumar | Khan Contractors | Fan and light fitting | 2026-08-30 | 5/5/5 | yes | APPROVED |
| 205 | Sunita Devi | Verma & Sons Builders | Bathroom plumbing | 2026-09-09 | 4/4/5 | yes | APPROVED |
| 206 | Sunita Devi | Singh Interiors | Kitchen pipework | 2026-07-26 | 5/4/4 | yes | APPROVED |
| 207 | Sunita Devi | Patel Hardware and Repairs | Tap and valve repair | 2026-05-12 | 4/5/4 | yes | APPROVED |
| 208 | Sunita Devi | Khan Contractors | Tank connection | 2026-07-11 | 5/5/5 | yes | APPROVED |
| 209 | Mohammed Irfan | Singh Interiors | Wardrobe and shelves | 2026-08-25 | 4/3/4 | yes | APPROVED |
| 210 | Mohammed Irfan | Raju Fabricators | Door fitting | 2026-03-23 | 3/3/3 | no | APPROVED |
| 211 | Suresh Yadav | Sharma Constructions | Boundary wall | 2026-07-01 | 3/4/4 | yes | APPROVED |
| 212 | Suresh Yadav | Verma & Sons Builders | Plastering | 2026-05-02 | 2/3/3 | no | APPROVED |
| 213 | Suresh Yadav | Raju Fabricators | Brick work | 2025-12-13 | 3/2/3 | no | APPROVED |
| 214 | Anil Verma | Khan Contractors | Gate fabrication | 2026-09-14 | 5/5/5 | yes | APPROVED |
| 215 | Pooja Sharma | Singh Interiors | Full flat repaint | 2026-09-24 | 5/5/5 | yes | APPROVED |
| 216 | Pooja Sharma | Sharma Constructions | Office painting | 2026-08-20 | 5/5/4 | yes | APPROVED |
| 217 | Pooja Sharma | Verma & Sons Builders | Exterior walls | 2026-07-21 | 5/4/5 | yes | APPROVED |
| 218 | Pooja Sharma | Patel Hardware and Repairs | Shop painting | 2026-06-21 | 4/5/5 | yes | APPROVED |
| 219 | Pooja Sharma | Raju Fabricators | Grill painting | 2026-03-23 | 3/4/4 | yes | APPROVED |
| 220 | Pooja Sharma | Khan Contractors | Touch-up work | 2026-09-09 | 5/5/5 | yes | APPROVED |
| 221 | Lakshmi Naidu | Patel Hardware and Repairs | Water line repair | 2026-08-30 | 4/4/4 | yes | APPROVED |
| 222 | Lakshmi Naidu | Gupta Electricals | Drain clearing | 2026-07-31 | 4/4/5 | yes | APPROVED |
| 223 | Lakshmi Naidu | Raju Fabricators | Pipe fitting | 2026-09-27 | 5/5/5 | yes | REJECTED |

## Signed credentials (8)

| Worker | Reference | Issued | Expires | State |
|---|---|---|---|---|
| Ramesh Kumar | b611e129… | 2026-10-09 | 2027-04-07 | valid |
| Sunita Devi | 590745a1… | 2026-10-09 | 2027-04-07 | valid |
| Mohammed Irfan | 83410d2f… | 2026-10-09 | 2027-04-07 | valid |
| Suresh Yadav | ed2f5212… | 2026-10-09 | 2027-04-07 | valid |
| Anil Verma | 0152481d… | 2026-10-09 | 2027-04-07 | valid |
| Pooja Sharma | 745432a9… | 2026-10-09 | 2027-04-07 | valid |
| Imran Ansari | 41c91bf7… | 2026-10-09 | 2027-04-07 | valid |
| Lakshmi Naidu | a9aee4c4… | 2026-10-09 | 2027-04-07 | valid |
