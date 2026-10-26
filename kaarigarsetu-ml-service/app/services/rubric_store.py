"""Rubric files are data (app/models/rubrics/*.json), never code. Questions come from the rubric author."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from app.config import env, is_production

RUBRIC_DIR = Path(env("RUBRIC_DIR", str(Path(__file__).resolve().parent.parent / "models" / "rubrics")))


@dataclass(frozen=True)
class Criterion:
    id: str
    description: str
    marks: int
    keywords: tuple[str, ...]
    requires_topic: bool = False     # cross-cutting measure: only counts if the answer also earns marks on the question's own criteria


@dataclass(frozen=True)
class Question:
    id: int
    text: dict[str, str]
    ideal_notes: str
    criteria: tuple[Criterion, ...]
    level: str = "basic"            # basic | intermediate | advanced
    area: str = "knowledge"         # which skill area the question measures (see AREAS); drives the per-area breakdown

    @property
    def max_marks(self) -> int:
        return sum(c.marks for c in self.criteria)

    def text_for(self, language: str | None) -> str:
        return self.text.get((language or "").lower()) or self.text.get("en") or next(iter(self.text.values()))


@dataclass(frozen=True)
class Rubric:
    trade: str
    version: str
    test_only: bool
    keywords: tuple[str, ...]      # legacy keyword list used when no question id is supplied
    questions: tuple[Question, ...]

    def question(self, question_id: int) -> Question:
        for question in self.questions:
            if question.id == question_id:
                return question
        raise ValueError(f"Unknown question {question_id} for trade {self.trade}")


_TRADE_NAME = re.compile(r"^[a-z][a-z0-9-]{0,40}$")     # also keeps "_common" and path tricks out
COMMON_FILE = "_common.json"
AREAS = ("tools", "materials", "experience", "knowledge", "troubleshooting", "planning", "customer")


def _common_measures() -> tuple[Criterion, ...]:
    path = RUBRIC_DIR / COMMON_FILE
    if not path.is_file():
        return ()
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("testOnly", False) and is_production():
        raise RuntimeError(f"TEST ONLY rubric {COMMON_FILE} refused when APP_ENV=prod")
    return tuple(Criterion(m["id"], m["description"], int(m["marks"]), tuple(m.get("keywords", ())), bool(m.get("requiresTopic", True)))
                 for m in data.get("measures", ()))


def available_trades() -> list[dict[str, object]]:
    """Every rubric file present (adding a trade is adding a file). Used by the catalog consistency checks and /trades."""
    trades = []
    for path in sorted(RUBRIC_DIR.glob("*.json")):
        if _TRADE_NAME.match(path.stem):
            data = json.loads(path.read_text(encoding="utf-8"))
            trades.append({"key": path.stem, "trade": data["trade"], "version": str(data.get("version", "1")), "test_only": bool(data.get("testOnly", False))})
    return trades


@lru_cache(maxsize=16)
def load_rubric(trade: str) -> Rubric:
    name = trade.strip().lower()
    if not _TRADE_NAME.match(name):
        raise ValueError(f"Unsupported trade: {trade}")
    path = RUBRIC_DIR / f"{name}.json"
    if not path.is_file():
        raise ValueError(f"Unsupported trade: {trade}")
    data = json.loads(path.read_text(encoding="utf-8"))
    test_only = bool(data.get("testOnly", False))
    if test_only and is_production():
        raise RuntimeError(f"TEST ONLY rubric {path.name} refused when APP_ENV=prod")
    common = _common_measures() if data.get("commonMeasures", True) else ()
    questions = tuple(
        Question(
            id=int(q["id"]), text=dict(q["text"]), ideal_notes=q.get("ideal_notes", ""), level=q.get("level", "basic"), area=q.get("area", "knowledge"),
            criteria=tuple(Criterion(c["id"], c["description"], int(c["marks"]), tuple(c.get("keywords", ()))) for c in q["criteria"])
            + tuple(Criterion(f"q{q['id']}{m.id}", m.description, m.marks, m.keywords, m.requires_topic) for m in common),
        )
        for q in data.get("questions", ())
    )
    return Rubric(trade=data["trade"], version=str(data.get("version", "1")), test_only=test_only,
                  keywords=tuple(data.get("criteria", ())), questions=questions)
