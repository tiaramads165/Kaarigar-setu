from __future__ import annotations

from app.config import evaluator_mode
from app.services.guard import MalformedScoreError, enforce, gate_relevance, sanitize_feedback, sanitize_transcript, injection_reasons
from app.services.llm_client import LlmClient
from app.services.rubric_evaluator import RubricEvaluator, band_feedback
from app.services.rubric_store import load_rubric


class EvaluationService:
    """Chooses the evaluator, applies the guard, and returns one structured result."""

    def __init__(self, llm: LlmClient | None = None, keyword: RubricEvaluator | None = None) -> None:
        self._llm = llm
        self._keyword = keyword or RubricEvaluator()

    def llm(self) -> LlmClient:
        if self._llm is None:
            self._llm = LlmClient()
        return self._llm

    def evaluate(self, trade: str, transcript: str, question_id: int | None = None, language: str | None = None) -> dict[str, object]:
        clean = sanitize_transcript(transcript)
        if not clean:
            raise ValueError("transcript must not be empty")

        if question_id is None:  # legacy whole-transcript keyword path
            result = self._keyword.evaluate(trade, clean)
            result.update({"criteria_scores": [], "needs_review": bool(injection_reasons(clean)),
                           "review_reason": "; ".join(injection_reasons(clean)) or None, "provider": "keyword"})
            return result

        rubric = load_rubric(trade)
        question = rubric.question(question_id)
        provider, feedback, review = "keyword", "", []
        try:
            if evaluator_mode() == "llm":
                reply = self.llm().score(rubric.trade, language, question, clean)
                provider = f"llm:{self.llm().model}"
                raw = {c.id: c.marks for c in reply.criteria}
                if len(raw) != len(reply.criteria):
                    raise MalformedScoreError("scorer returned a criterion twice")
                feedback = sanitize_feedback(reply.feedback)
                if reply.suspicious:
                    review.append("scorer flagged the transcript as suspicious")
            else:
                raw = self._keyword.evaluate_question(question, clean)
            marks, reasons = enforce(raw, question, clean)
            marks = gate_relevance(marks, question)
            review += reasons
        except MalformedScoreError as error:
            marks = {c.id: 0 for c in question.criteria}
            review.append(str(error))

        total, maximum = sum(marks.values()), question.max_marks
        percent = round(100 * total / maximum, 2) if maximum else 0.0
        criteria_scores = [{"id": c.id, "marks": marks[c.id], "max_marks": c.marks, "reason": ""} for c in question.criteria]
        return {
            "trade": rubric.trade, "score": percent,
            "feedback": feedback or band_feedback(percent, language),
            "matched_criteria": [c.id for c in question.criteria if marks[c.id] > 0],
            "criteria_scores": criteria_scores, "needs_review": bool(review), "review_reason": "; ".join(review) or None,
            "provider": provider,
        }
