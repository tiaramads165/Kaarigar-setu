"""Everything between an (untrusted) transcript / (untrusted) model reply and a stored score."""
from __future__ import annotations

import re
import unicodedata

from app.services.rubric_evaluator import fold
from app.services.rubric_store import Question

MAX_TRANSCRIPT_CODE_POINTS = 1500
SHORT_ANSWER_CODE_POINTS = 15


class MalformedScoreError(ValueError):
    """The scorer's output cannot be trusted structurally (unknown, duplicate, missing or negative marks)."""


_STRIPPED = {0x200B, 0x200E, 0x200F, 0x2060, 0xFEFF} | set(range(0x202A, 0x202F)) | set(range(0x2066, 0x206A))


def sanitize_transcript(raw: str | None) -> str:
    """Normalise, strip invisible/bidi characters (keeping ZWJ/ZWNJ for Devanagari/Bengali), cap the length."""
    if not raw:
        return ""
    out: list[str] = []
    last_space = True
    for ch in unicodedata.normalize("NFC", raw):
        cp = ord(ch)
        if cp in _STRIPPED:
            continue
        if ch.isspace() or unicodedata.category(ch).startswith("C") and cp not in (0x200C, 0x200D):
            if not last_space:
                out.append(" ")
            last_space = True
            continue
        out.append(ch)
        last_space = False
        if len(out) >= MAX_TRANSCRIPT_CODE_POINTS:
            break
    return "".join(out).strip().replace("<<<", "").replace(">>>", "")


_INJECTION_PATTERNS = [re.compile(p, re.IGNORECASE) for p in (
    r"(ignore|forget|disregard|override)\W+(all\W+|the\W+|any\W+|previous\W+|above\W+|your\W+)*(rubric|instruction|instructions|rules|prompt)",
    r"(system|developer)\s+(prompt|message)",
    r"\b10\s*/\s*10\b|\b100\s*/\s*100\b|full\s+marks|maximum\s+(marks|score)|give\s+(me\s+)?(full|max|10|100)",
    r"you\s+are\s+(now|a|an)\b",
    r"\bjson\b|\bscore\s*[:=]",
    r"रुब्रिक|निर्देश(ों)?\s*(को)?\s*(भूल|अनदेखा)|पूरे\s*(अंक|नंबर)|सारे\s*(अंक|नंबर)|दस\s*में\s*से\s*दस",
)]


def injection_reasons(transcript: str) -> list[str]:
    """Heuristic tripwire only (adds a human-review flag). The real defences are the fenced prompt,
    schema validation, clamping and recomputed totals."""
    return ["possible prompt injection in transcript"] if any(p.search(transcript) for p in _INJECTION_PATTERNS) else []


STUFFING_MIN_FORMS = 7          # within one group of rubric words an answer must contain at least this many distinct ones ...
STUFFING_MIN_SHARE = 0.8        # ... AND at least this share of the group (naming every idea in two languages stays well below it)


def _distinct_forms(words) -> list[str]:
    """Folded, de-duplicated keyword forms, dropping a form that is only a piece of a longer one ('plier' inside 'pliers')."""
    ordered = sorted({fold(w) for w in words if w}, key=len, reverse=True)
    kept: list[str] = []
    for form in ordered:
        if not any(form in longer for longer in kept):
            kept.append(form)
    return kept


def stuffing_groups(question: Question) -> list[list[str]]:
    """The rubric words of a question in two groups: the question's own topic words, and the shared cross-cutting words (an example, care, checking).
    They are judged separately because the shared group is large and would otherwise dilute the share of a recited topic list."""
    topic = _distinct_forms(k for c in question.criteria if not c.requires_topic for k in c.keywords)
    shared = _distinct_forms(k for c in question.criteria if c.requires_topic for k in c.keywords)
    return [group for group in (topic, shared) if len(group) >= STUFFING_MIN_FORMS]


def stuffing_reasons(transcript: str, question: Question) -> list[str]:
    """Keyword scoring cannot tell a real answer from a recited word list, so make the recitation visible. An honest answer names one word per idea and so
    contains a small share of a group's words; reciting the rubric (several languages and spellings of everything) contains almost all of them.
    This only asks a person to look: it never lowers a score, and it cannot fire on a group with fewer than STUFFING_MIN_FORMS words."""
    text = fold(transcript)
    for group in stuffing_groups(question):
        present = [form for form in group if form in text]
        if len(present) >= STUFFING_MIN_FORMS and len(present) / len(group) >= STUFFING_MIN_SHARE:
            return ["answer contains most of the rubric's words in several forms (possible keyword stuffing)"]
    return []


_URL_LIKE = re.compile(r"(?i)https?:|www\.|\.com\b|\.in\b")


def sanitize_feedback(text: str | None, limit: int = 240) -> str:
    """Feedback is displayed and read aloud: short, plain, link-free."""
    if not text:
        return ""
    cleaned = re.sub(r"\s+", " ", re.sub(r"[\x00-\x1f\x7f]", " ", text)).strip()
    if not cleaned or _URL_LIKE.search(cleaned):
        return ""
    return cleaned[:limit]


def enforce(raw_marks: dict[str, int], question: Question, transcript: str) -> tuple[dict[str, int], list[str]]:
    """Return (clamped marks per criterion, review reasons). Raises MalformedScoreError for untrustworthy output."""
    known = {c.id: c for c in question.criteria}
    if set(raw_marks) != set(known):
        raise MalformedScoreError("scorer returned unknown or missing criteria")
    reasons: list[str] = []
    safe: dict[str, int] = {}
    for criterion_id, marks in raw_marks.items():
        if marks < 0:
            raise MalformedScoreError("scorer returned negative marks")
        ceiling = known[criterion_id].marks
        if marks > ceiling:
            marks = ceiling
            if "scorer awarded marks above a criterion maximum (clamped)" not in reasons:
                reasons.append("scorer awarded marks above a criterion maximum (clamped)")
        safe[criterion_id] = marks
    reasons += injection_reasons(transcript)
    reasons += stuffing_reasons(transcript, question)
    if sum(safe.values()) == question.max_marks and len(transcript) < SHORT_ANSWER_CODE_POINTS:
        reasons.append("full marks on a very short answer")
    return safe, reasons


def gate_relevance(marks: dict[str, int], question: Question) -> dict[str, int]:
    """Cross-cutting measures (an example, care, checking) only count when the answer earns something on the question's own topic.
    Without this, any off-topic sentence that happens to contain 'check' or 'first' would collect marks."""
    topic = [c for c in question.criteria if not c.requires_topic]
    if topic and not any(marks.get(c.id, 0) > 0 for c in topic):
        return {cid: (0 if next(c for c in question.criteria if c.id == cid).requires_topic else m) for cid, m in marks.items()}
    return marks
