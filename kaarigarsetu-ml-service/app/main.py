import logging
import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

from app.config import stt_provider
from app.routes import audio, evaluate, fraud, rubric, transcribe, trust
from app.security import require_internal_token

@asynccontextmanager
async def lifespan(_: FastAPI):
    """Load Whisper in the background so the first worker does not wait for a model download."""
    if stt_provider() in ("whisper", "faster-whisper"):
        threading.Thread(target=_preload, name="whisper-preload", daemon=True).start()
    yield


app = FastAPI(title="Kaarigar Setu ML Service", version="1.0.0", lifespan=lifespan,
              description="Internal AI/ML microservice: speech-to-text, rubric scoring, fraud and trust scoring.")


def _preload() -> None:
    try:
        (transcribe.faster_whisper_client if stt_provider() == "faster-whisper" else transcribe.whisper_client).preload()
        logging.getLogger("uvicorn.error").info("Whisper model ready")
    except Exception as error:  # noqa: BLE001 - reported on first use instead
        logging.getLogger("uvicorn.error").warning("Whisper model could not be preloaded: %s", error)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "kaarigar-setu-ml"}


_ROUTERS = (transcribe.router, audio.router, evaluate.router, rubric.router, fraud.router, trust.router)

# Documented, authenticated internal API (blueprint section 6): /internal/ml/*.
for _router in _ROUTERS:
    app.include_router(_router, prefix="/internal/ml", dependencies=[Depends(require_internal_token)])
# Short aliases for local tooling and the original Phase 3 tests; hidden from the OpenAPI docs, same auth.
for _router in _ROUTERS:
    app.include_router(_router, include_in_schema=False, dependencies=[Depends(require_internal_token)])
