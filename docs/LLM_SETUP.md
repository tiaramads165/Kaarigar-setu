# The language-model scorer (optional)

Without a key the app scores answers with the deterministic keyword scorer. It works, but it only recognises the keywords written in the
rubric. With a key the answer is scored by a language model against the same rubric criteria, still inside the same guards: the model sees
the transcript as untrusted data, its answer is validated field by field, marks cannot exceed the rubric, relevance is gated, and anything
doubtful goes to a human.

1. Create an Anthropic API key in your own account. Add `LLM_API_KEY=...` to `.env.local` yourself (never paste a key into chat or commit it).
   Optional: `LLM_MODEL` (default `claude-haiku-4-5-20251001`), `LLM_BASE_URL`, `EVALUATOR=llm|keyword` to force one.
2. Check it with the real API: `kaarigarsetu-ml-service/.venv/bin/python scripts/llm_check.py` (three answers, a few cents at most).
3. Restart `scripts/dev.sh`. The ML service picks the LLM automatically when a key is set; the response `provider` field shows `llm:<model>`.

Privacy: the transcript text (not the audio, name or phone) is sent to the provider. Name the provider in your consent text and have the data
processing terms in place (docs/PRIVACY_DPDP.md). A provider outage returns 503, never a silent wrong score.
The rubric is still TEST ONLY; the model's marks are only as good as the rubric an expert supplies (docs/ML_EVALUATION.md has the agreement tool).
