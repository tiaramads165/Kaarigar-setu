"""LLM rubric scoring over the Anthropic Messages API, with strict schema validation of the reply."""
from __future__ import annotations

import json
import re
import secrets
from pathlib import Path

import httpx
from pydantic import BaseModel, StrictBool, StrictInt, StrictStr, ValidationError

from app.config import env
from app.services.guard import MalformedScoreError
from app.services.rubric_store import Question

PROMPT_DIR = Path(env("PROMPT_DIR", str(Path(__file__).resolve().parent.parent / "models" / "prompts")))
LANGUAGE_NAMES = {"hi": "Hindi", "bn": "Bengali", "en": "English"}
_PLACEHOLDER = re.compile(r"\{\{([A-Z_]+)\}\}")


class LlmUnavailable(RuntimeError):
    """The provider could not be reached or answered with an error status."""


class LlmCriterion(BaseModel):
    id: StrictStr
    marks: StrictInt          # JSON integers only: "2" and 2.5 are rejected
    reason: StrictStr = ""


class LlmReply(BaseModel):
    criteria: list[LlmCriterion]
    suspicious: StrictBool = False
    feedback: StrictStr = ""


def render(template: str, values: dict[str, str]) -> str:
    """Single-pass substitution: a value containing '{{X}}' (e.g. a hostile transcript) is never re-expanded."""
    return _PLACEHOLDER.sub(lambda m: values[m.group(1)], template)


def parse_reply(raw: str | None) -> LlmReply:
    if not raw:
        raise MalformedScoreError("empty scorer output")
    start, end = raw.find("{"), raw.rfind("}")
    if start < 0 or end <= start:
        raise MalformedScoreError("scorer output is not JSON")
    try:
        data = json.loads(raw[start:end + 1])
        return LlmReply.model_validate(data)
    except (json.JSONDecodeError, ValidationError) as error:
        raise MalformedScoreError("scorer output failed schema validation") from error


class LlmClient:
    def __init__(self, transport: httpx.BaseTransport | None = None) -> None:
        self.api_key = env("LLM_API_KEY")
        self.model = env("LLM_MODEL", "claude-haiku-4-5-20251001")
        self.base_url = env("LLM_BASE_URL", "https://api.anthropic.com").rstrip("/")
        self._transport = transport
        self._system = (PROMPT_DIR / "rubric_scoring_v1.system.txt").read_text(encoding="utf-8")
        self._user = (PROMPT_DIR / "rubric_scoring_v1.user.txt").read_text(encoding="utf-8")

    def build_prompts(self, trade: str, language: str | None, question: Question, transcript: str) -> tuple[str, str]:
        language_name = LANGUAGE_NAMES.get((language or "hi").lower(), language or "Hindi")
        criteria = "\n".join(f"- id={c.id} | max={c.marks} | {c.description}" for c in question.criteria)
        user = render(self._user, {
            "TRADE": trade, "LANGUAGE": language_name, "QUESTION": question.text_for(language), "CRITERIA": criteria,
            "IDEAL_NOTES": question.ideal_notes or "(none)", "NONCE": secrets.token_hex(8), "TRANSCRIPT": transcript,
        })
        return self._system, user

    def score(self, trade: str, language: str | None, question: Question, transcript: str) -> LlmReply:
        system, user = self.build_prompts(trade, language, question, transcript)
        body = {"model": self.model, "max_tokens": 700, "temperature": 0, "system": system,
                "messages": [{"role": "user", "content": user}]}
        headers = {"x-api-key": self.api_key, "anthropic-version": "2023-06-01", "content-type": "application/json"}
        try:
            with httpx.Client(transport=self._transport, timeout=25.0) as client:
                response = client.post(f"{self.base_url}/v1/messages", json=body, headers=headers)
            response.raise_for_status()
            text = response.json()["content"][0]["text"]
        except (httpx.HTTPError, KeyError, IndexError, ValueError) as error:
            raise LlmUnavailable("LLM request failed") from error
        return parse_reply(text)
