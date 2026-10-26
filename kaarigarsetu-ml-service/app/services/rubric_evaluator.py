from __future__ import annotations

import unicodedata
from collections.abc import Mapping

from app.services.rubric_store import Question


# Speech recognition spells the same Hindi word several ways (nukta, chandrabindu vs anusvara, a stray virama). For keyword
# matching those differences carry no meaning, so both sides are reduced to the same skeleton before comparing.
_IGNORED = {"\u093c", "\u094d", "\u200c", "\u200d"}          # nukta, virama, ZWNJ, ZWJ
_FOLDED = {"\u0901": "\u0902"}                                 # chandrabindu -> anusvara


def fold(text: str) -> str:
    text = unicodedata.normalize("NFD", text.lower())
    text = "".join(_FOLDED.get(ch, ch) for ch in text if ch not in _IGNORED)
    return unicodedata.normalize("NFC", text)


def contains_keyword(text: str, keyword: str) -> bool:
    """True if the keyword appears in the text, allowing small transcription slips: one wrong, missing or extra letter for
    keywords of six to ten letters, two for eleven to fifteen (keywords of five letters or fewer must match exactly, otherwise
    'rice' would match 'price'). Both are folded first.
    A transcriber that writes 'इलिक्ष्ट्शियन' for 'इलेक्ट्रीशियन' should not cost the worker marks."""
    if keyword in text:
        return True
    allowed = (len(keyword) - 1) // 5
    if allowed == 0 or len(text) < len(keyword) - allowed:
        return False
    previous = [0] * (len(text) + 1)               # Sellers' algorithm: a match may start anywhere in the text, so row 0 is all zeros
    for i, k in enumerate(keyword, 1):
        current = [i] + [0] * len(text)
        for j, t in enumerate(text, 1):
            current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (k != t))
        previous = current
    return min(previous) <= allowed


class RubricEvaluator:
    """Deterministic evaluator: the legacy trade-keyword scorer plus a per-criterion keyword scorer.
    Used when no LLM is configured; an LLM adapter replaces it without changing the route."""

    RUBRICS: Mapping[str, tuple[str, ...]] = {
        "electrician": ("safety", "circuit", "voltage", "wire", "breaker"),
        "plumber": ("leak", "pipe", "valve", "drain", "fitting"),
        "carpenter": ("wood", "saw", "joint", "chisel", "hammer"),
        "mason": ("brick", "cement", "sand", "wall", "level"),
        "welder": ("weld", "metal", "electrode", "arc", "joint"),
        "painter": ("paint", "brush", "wall", "primer", "colour"),
    }

    def evaluate(self, trade: str, transcript: str) -> dict[str, object]:
        normalized_trade = trade.strip().lower()
        keywords = self.RUBRICS.get(normalized_trade)
        if not keywords:
            raise ValueError(f"Unsupported trade: {trade}")
        normalized_text = transcript.lower()
        matched = [keyword for keyword in keywords if keyword in normalized_text]
        score = round(100 * len(matched) / len(keywords), 2)
        feedback = (
            f"Covered {', '.join(matched)}."
            if matched
            else "No trade-specific rubric concepts were detected."
        )
        return {"trade": trade.strip(), "score": score, "feedback": feedback, "matched_criteria": matched}

    def evaluate_question(self, question: Question, transcript: str) -> dict[str, int]:
        """All-or-nothing marks per criterion: full marks if any of its keywords appears in the transcript."""
        text = fold(transcript)
        return {c.id: (c.marks if any(contains_keyword(text, fold(k)) for k in c.keywords) else 0) for c in question.criteria}


FEEDBACK = {
    "hi": {"high": "बहुत अच्छा जवाब था।", "mid": "ठीक जवाब था, कुछ और बातें जोड़कर बेहतर बना सकते हैं।", "low": "इस सवाल पर और अभ्यास की ज़रूरत है।"},
    "en": {"high": "Very good answer.", "mid": "A fair answer; adding a few more points would improve it.", "low": "This question needs more practice."},
}


def band_feedback(percent: float, language: str | None) -> str:
    table = FEEDBACK.get((language or "en").lower(), FEEDBACK["en"])
    return table["high"] if percent >= 70 else table["mid"] if percent >= 50 else table["low"]
