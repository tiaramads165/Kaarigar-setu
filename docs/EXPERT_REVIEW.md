# Questions That Need a Domain Expert

Questions 6 to 10 of every trade (and all of questions 1 to 5) are **TEST ONLY drafts written without a domain expert**. They exist so the longer, harder
assessment can run end to end. None of them has been validated, and **no credential issued from them should be relied on**. The ML service refuses to load them in production.

## What to review (for each trade file in `kaarigarsetu-ml-service/app/models/rubrics/`)

1. **Is the question fair and clear** for a working tradesperson, in both Hindi and English?
2. **Is every keyword list right?** A criterion earns its marks when any one of its keywords appears. Check that (a) correct answers would be recognised in the words a worker really uses (local and regional terms, spoken
   forms, English mixed with Hindi), and (b) a wrong or irrelevant answer could not earn the marks by using a keyword by accident.
3. **Are the marks and levels right?** `basic` for 1 to 5, `intermediate` or `advanced` for 6 to 10; advanced criteria are 3 marks, others 2.
4. **Should the shared measures stay?** (`_common.json`: an example, care or a precaution, checking the work.) They reward mentioning those things, not being correct.

## What was deliberately left out (safety-critical: your expert must supply it)

Following the standing rule not to invent electrical-safety (or similar) content, these topics are **not covered by any question**, and the assessment therefore **cannot certify safe practice** in them:

| Trade | Not covered |
|---|---|
| Electrician | earthing; isolating or switching off before work; protective devices (fuse, MCB, RCCB) and their ratings; working near live parts; matching wire size to load; insulation and testing for safe working |
| Plumber | gas piping and fittings; hot-water systems and pressure relief; backflow and cross-connection with drinking water; materials safe for drinking water; confined-space drains |
| Welder | fume, fire and eye protection; gas cylinder handling; working at height or in confined spaces; certification standards |
| Carpenter | power-tool guarding and kickback; dust and finishes; working at height |
| Mason | scaffolding and working at height; load-bearing and structural limits; trench or excavation safety |
| Painter | solvent and fume exposure; lead or old-paint hazards; working at height and ladders |

The existing drafts only touch these areas through the shared "mentions care or a precaution" measure, which checks that the worker *says something* about care; it does not check that what they say is correct.

## Suggested adoption path

* The expert edits or replaces the JSON files (same shape; see `docs/RUBRIC_AUTHORING.md`), sets `"testOnly": false` and a new `version`, and decides on `commonMeasures`.
* Measure agreement between the automatic score and a human grader on at least 30 real transcripts before relying on it (`docs/ML_EVALUATION.md` section 4). Keyword matching especially needs this: it
  checks which words were said, and it is the LLM scorer plus an expert-written rubric that can judge whether an answer is right.
* Consider "must-pass" safety questions (a wrong answer forces human review whatever the average) once the expert has written them; the pipeline does not have that feature yet.
