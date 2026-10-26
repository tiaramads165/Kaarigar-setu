from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.services.fraud_detector import FraudDetector

router = APIRouter(tags=["fraud"])
detector = FraudDetector()


class FraudCheckRequest(BaseModel):
    ip_frequency: float = Field(ge=0)
    device_frequency: float = Field(ge=0)
    minutes_since_previous: float = Field(ge=0)
    reciprocity_ratio: float = Field(ge=0, le=1)


@router.post("/fraud-check")
def fraud_check(request: FraudCheckRequest):
    return detector.check(request.model_dump())
