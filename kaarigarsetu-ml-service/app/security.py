"""Shared-secret guard for the internal API: only the Java backend should be able to call these routes."""
from __future__ import annotations

import hmac

from fastapi import Header, HTTPException

from app.config import env, is_production


def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    expected = env("INTERNAL_API_TOKEN")
    if not expected:
        if is_production():
            raise HTTPException(status_code=503, detail="internal API token is not configured")
        return  # local development without a token
    if x_internal_token is None or not hmac.compare_digest(x_internal_token.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="invalid internal token")
