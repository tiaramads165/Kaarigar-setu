from fastapi import APIRouter, File, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from app.config import max_audio_bytes
from app.services.audio_fingerprint import AudioError, fingerprint

router = APIRouter(tags=["speech"])


@router.post("/audio-fingerprint")
async def audio_fingerprint(audio: UploadFile = File(...)):
    """The landmarks of one recording (see app/services/audio_fingerprint.py): numbers and frame indexes only, never audio.
    The Java backend keeps keyed hashes of them to recognise the same recording played again; nothing here is stored."""
    limit = max_audio_bytes()
    content = await audio.read(limit + 1)
    if not content:
        raise HTTPException(status_code=422, detail="audio must not be empty")
    if len(content) > limit:
        raise HTTPException(status_code=413, detail="audio too large")
    try:
        result = await run_in_threadpool(fingerprint, content)
    except AudioError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return {"hashes": result.hashes, "times": result.times, "seconds": round(result.seconds, 3), "landmarks": result.landmarks}
