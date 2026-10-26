from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from app.config import max_audio_bytes, stt_provider
from app.services.faster_whisper_client import FasterWhisperClient
from app.services.stub_transcriber import StubTranscriber
from app.services.whisper_client import WhisperClient

router = APIRouter(tags=["speech"])
whisper_client = WhisperClient()
faster_whisper_client = FasterWhisperClient()
stub_transcriber = StubTranscriber()


@router.post("/transcribe")
async def transcribe(audio: UploadFile = File(...), language: str | None = None):
    limit = max_audio_bytes()
    content = await audio.read(limit + 1)
    if not content:
        raise HTTPException(status_code=422, detail="audio must not be empty")
    if len(content) > limit:
        raise HTTPException(status_code=413, detail="audio too large")
    provider = stt_provider()
    engine = stub_transcriber if provider == "stub" else faster_whisper_client if provider == "faster-whisper" else whisper_client
    try:
        # blocking model inference runs in a worker thread so /health and other requests stay responsive
        return await run_in_threadpool(engine.transcribe, content, audio.filename or "audio", language)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
