from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.services.evaluation_service import EvaluationService
from app.services.llm_client import LlmUnavailable

router = APIRouter(tags=["evaluation"])
service = EvaluationService()


class EvaluateRequest(BaseModel):
    trade: str = Field(min_length=1, max_length=80)
    transcript: str = Field(min_length=1, max_length=4000)
    question_id: int | None = Field(default=None, ge=1)
    language: str | None = Field(default=None, max_length=8)


class CriterionScore(BaseModel):
    id: str
    marks: int
    max_marks: int
    reason: str = ""


class EvaluateResponse(BaseModel):
    trade: str
    score: float
    feedback: str
    matched_criteria: list[str]
    criteria_scores: list[CriterionScore] = []
    needs_review: bool = False
    review_reason: str | None = None
    provider: str


@router.post("/evaluate", response_model=EvaluateResponse)
def evaluate(request: EvaluateRequest):
    try:
        return service.evaluate(request.trade, request.transcript, request.question_id, request.language)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except LlmUnavailable as error:
        raise HTTPException(status_code=503, detail="scoring provider unavailable") from error
