# Rubric Authoring Guide

How assessments are defined, how an answer becomes a score, and how to add or replace a trade. Rubrics are **data** (JSON files in
`kaarigarsetu-ml-service/app/models/rubrics/`), never code.

> **Status of the content.** Every rubric shipped today (electrician, plumber, carpenter, mason, welder, painter) and the shared
> measures are **TEST ONLY placeholders** (`"testOnly": true`). They exist so the whole pipeline can run for every trade. They are not an authoritative
> assessment and contain no safety instruction. The ML service refuses to load any TEST ONLY rubric when `APP_ENV=prod`, so the real rubrics from the
> domain expert must replace them before a production start.

## 1. How a score is made

Each assessment is **10 questions**, always the same ten for a trade, in this order: questions 1 to 5 are **basic** (tools, materials, background, a basic concept, a customer question) and 6 to 10 are **intermediate and advanced** (why something is done, troubleshooting a symptom, planning and estimating, handling a complaint). Harder questions carry more marks per criterion (advanced criteria are 3 marks, the rest 2). Each question has **marking criteria**, each worth some marks and defined by a keyword list (Hindi and English).

* The scorer (the LLM when `LLM_API_KEY` is set, otherwise keyword matching) decides which criteria the transcript earns.
* A question's score is `marks earned / maximum marks`, as a percentage. The assessment's skill score is the mean of the 10 question scores.
* Decision bands (agreed): **pass >= 70, borderline 50 to 69, needs review < 50**.
* Keyword matching ignores Devanagari spelling variants (nukta, chandrabindu, a stray virama) and forgives one wrong, missing or extra letter in keywords of six to ten letters
  (two for eleven to fifteen). Keywords of five letters or fewer must match exactly.

### Two kinds of criteria on every question

| Kind | Where defined | Marks | Counts when |
|---|---|---|---|
| **Topic criteria** (2 or 3 per question) | in the trade's own file | 2 or 3 each | the answer contains one of the criterion's keywords |
| **Shared measures** (3 per question) | `_common.json`, added to every question of every trade | 1 each | the answer ALSO earned marks on a topic criterion (`requiresTopic`) |

The shared measures are: **gives an example or steps from own work** (for example, first, then, I did); **mentions care or a precaution** (careful, safety, gloves, helmet);
**mentions checking the finished work** (check, inspect, verify). A question's maximum is its topic marks plus 3 (for example 6 + 3 = 9 for three 2-mark criteria, or 4 + 3 = 7 for two).

What the shared measures are, deliberately: they check *whether* the worker talks about an example, care or checking, never *what is correct*. They hold no trade
facts and no safety content, so they cannot invent rules for a trade. They are gated: an off-topic answer scores 0 on them too ("I checked the cricket match carefully" gets nothing).
A rubric file can opt out with `"commonMeasures": false`. The real rubric should decide whether to keep them.

Consequence to be aware of: a correct answer that only names the three tools on an electrician or carpenter question 1 earns 6 of 9 (66.7 %), which is *borderline*; adding an example, a precaution and a check reaches 100 %.

## 2. File format of a trade rubric

`<key>.json`, where `<key>` is lowercase letters, digits or hyphens, starting with a letter (e.g. `carpenter.json`):

```json
{
  "trade": "Carpenter",
  "criteria": ["wood", "saw", "joint", "chisel", "hammer"],
  "testOnly": true,
  "version": "TEST-ONLY-0.1",
  "questions": [
    {
      "id": 1,
      "text": { "hi": "आप बढ़ई के रूप में रोज़ किन औज़ारों का इस्तेमाल करते हैं? ...", "en": "Which tools do you use every day as a carpenter? ..." },
      "ideal_notes": "Grader-only note. Never shown to workers.",
      "criteria": [
        { "id": "q1c1", "description": "Names a saw", "marks": 2, "keywords": ["आरी", "saw"] },
        { "id": "q1c2", "description": "Names a hammer", "marks": 2, "keywords": ["हथौड़ा", "hammer"] }
      ]
    }
  ]
}
```

Rules: exactly **10** questions with ids 1 to 10 (the number is `AssessmentService.QUESTIONS_PER_ASSESSMENT`); each has `"level"`: `basic`, `intermediate` or `advanced`, and `"area"`: one of `tools`, `materials`, `experience`, `knowledge`, `troubleshooting`, `planning`, `customer` (which skill the question measures; the worker's score is also reported per area, and both fields are visible to the client because they reveal nothing about the answers); `text` must have `hi` and `en`; criterion ids unique within the question (`q<id>c<n>`; ids starting `q<id>cm` are reserved for the shared measures);
`marks` a positive whole number; `keywords` non-empty, both scripts where a worker might use either. `criteria` at the top (legacy whole-transcript keyword list) is used only when no question id is sent.
Keywords, `ideal_notes` and criterion text are **grader-only**: the worker-facing rubric view returns only question text and maximum marks (tested for every trade).

**Keyword design tips (each learned the hard way here):**
* Do not use a keyword that is also part of another word the worker might say. A measure keyword `test` would be triggered by the tool name "tester"; `price` could match "rice".
  `tests/test_rubrics.py` fails if any shared-measure keyword can match inside any topic keyword of any trade.
* Prefer whole distinctive words (screwdriver, पेचकस) over fragments. Short keywords (five letters or fewer) match exactly and are the most prone to accidental hits inside longer words.
* Do not paste answer words into the speech-recognition hint; that would make the recogniser hear them.

## 3. Adding a trade (three places, all checked)

1. **Rubric file** `kaarigarsetu-ml-service/app/models/rubrics/<key>.json` (format above).
2. **Catalog line** in `kaarigarsetu-java/src/main/resources/trades.json`: `{"key": "<key>", "name": "Carpenter", "nameHi": "बढ़ई"}`. This drives the registration dropdown and validation.
3. **Scorer class** `kaarigarsetu-java/.../assessment/<Name>AssessmentService.java` extending `AssessmentService` (trade name plus its feedback wording), and add it to the list in `AppContext`.
4. Add the key to the `TRADES` list in `kaarigarsetu-ml-service/tests/test_rubrics.py`, and give the legacy keyword map `RubricEvaluator.RUBRICS` five keywords.

These cannot drift apart silently: the Java app refuses to **start** if a catalog trade has no scorer class (`TradeCatalog.requireScorersFor`), a Java test compares the catalog with the rubric files
(`TradeCatalogTest`), and the ML tests check every rubric's structure, that unrelated answers score 0 for every trade and question, and that the shared measures cannot collect accidental marks.

To **replace** a placeholder with the real rubric, overwrite its file (same shape, `"testOnly": false`, a new `version`), decide on `commonMeasures`, and re-run the ML tests. Versions are stored with scores, so bump the version for any change.
For production the shared file `_common.json` must also be real (or unused via `"commonMeasures": false`), because a TEST ONLY shared file is refused in production even by a real rubric.

## 4. Checking a rubric

```bash
cd kaarigarsetu-ml-service && .venv/bin/python -m pytest tests/test_rubrics.py tests/test_evaluate.py
cd ../kaarigarsetu-java && TEST_DB_URL=jdbc:postgresql://localhost:5432/<name>_test mvn test -Dtest=TradeCatalogTest
```

To see how a specific answer scores: `curl -s localhost:8001/internal/ml/evaluate -H 'Content-Type: application/json' -d '{"trade":"welder","transcript":"...","question_id":1,"language":"en"}'`
(add `-H "X-Internal-Token: ..."` if a token is configured) returns the score, the marks per criterion and which criteria matched.
Once real human grades exist, measure agreement with `python -m app.training.rubric_agreement` (see `docs/ML_EVALUATION.md` section 4).
