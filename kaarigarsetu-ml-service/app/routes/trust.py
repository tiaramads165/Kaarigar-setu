from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.services.graph_engine import GraphEngine

router = APIRouter(tags=["trust"])
graph_engine = GraphEngine()


class TrustEdgeInput(BaseModel):
    source: str = Field(min_length=1, max_length=64)
    target: str = Field(min_length=1, max_length=64)
    weight: float = Field(gt=0, le=1)


class TrustScoreRequest(BaseModel):
    edges: list[TrustEdgeInput] = Field(default_factory=list, max_length=50_000)
    nodes: list[str] = Field(default_factory=list, max_length=50_000)


@router.post("/trust-score")
def trust_score(request: TrustScoreRequest):
    raw = graph_engine.scores(request.nodes, request.edges)
    return {"scores": raw, "scaled_scores": graph_engine.scaled(raw)}
