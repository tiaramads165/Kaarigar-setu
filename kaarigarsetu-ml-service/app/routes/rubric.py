from fastapi import APIRouter, HTTPException

from app.services.rubric_store import available_trades, load_rubric

router = APIRouter(tags=["rubric"])


@router.get("/rubric/{trade}")
def rubric(trade: str, language: str = "hi"):
    """The worker-facing view of a rubric: question ids and text only. Criteria, keywords and ideal-answer
    notes are grader-only and are never returned."""
    try:
        loaded = load_rubric(trade)
    except ValueError as error:
        raise HTTPException(status_code=404, detail=str(error)) from error
    return {"trade": loaded.trade, "version": loaded.version, "test_only": loaded.test_only, "language": language,
            "questions": [{"id": q.id, "text": q.text_for(language), "max_marks": q.max_marks, "level": q.level, "area": q.area}
                          for q in loaded.questions]}


@router.get("/trades")
def trades():
    """The trades that have a rubric. A trade is added by adding one rubric file (plus a line in the Java trade catalog)."""
    return {"trades": available_trades()}
