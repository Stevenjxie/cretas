"""
/api/llm/* — FastAPI endpoints that wrap common.llm_router.

All endpoints delegate to call_chain (or call_chain_stream for SSE) so
Java callers get the full 4-provider fallback + circuit-breaker behaviour
without needing their own DashScope client.

Slots:
    chat        — general chat (default)
    insights    — data insights
    chart       — chart recommendation
    mapper      — field mapping
    reasoning   — deep reasoning
    vl          — vision/image (used by /vision endpoint)
    review      — code / content review

Routes:
    POST /chat           — synchronous chat passthrough
    POST /chat-stream    — SSE streaming chat
    POST /tool-call      — function-calling passthrough
    POST /intent-classify — intent classification (temperature forced 0.1)
    POST /vision         — multipart image + prompt → SLOT.VL
"""
from __future__ import annotations

import base64
import json
import logging
from typing import Any, AsyncIterator, Dict, List, Optional, Union

from fastapi import APIRouter, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from common.llm_router import SLOT, call_chain, call_chain_stream
from common.llm_metrics import llm_caller_context

logger = logging.getLogger(__name__)

router = APIRouter(tags=["LLM Router"])

# ── Request model ─────────────────────────────────────────────────────────────


class LLMRequest(BaseModel):
    messages: List[Dict[str, Any]]
    slot: str = "chat"
    temperature: Optional[float] = None
    max_tokens: Optional[int] = None
    enable_thinking: Optional[bool] = None
    tools: Optional[List[Dict[str, Any]]] = None
    tool_choice: Optional[Union[str, Dict[str, Any]]] = None
    extra_body: Optional[Dict[str, Any]] = None
    # Upstream (Java/RN) may pass who is calling → attributed in smart_bi_llm_usage.
    # Falls back to the endpoint name so this generic proxy is never "unknown"
    # (was an 8.7M-token/30d attribution black hole — 2026-07-01 rules-gap heatmap).
    caller: Optional[str] = None


# ── Helpers ───────────────────────────────────────────────────────────────────

_VALID_SLOTS = {s.value: s for s in SLOT}


def _parse_slot(slot_str: str) -> SLOT:
    """Convert slot name string to SLOT enum; raise 400 on unknown value."""
    s = _VALID_SLOTS.get(slot_str)
    if s is None:
        valid = sorted(_VALID_SLOTS.keys())
        raise HTTPException(
            status_code=400,
            detail=f"Invalid slot '{slot_str}'. Must be one of: {valid}",
        )
    return s


def _build_payload(req: LLMRequest) -> Dict[str, Any]:
    """Build the payload dict, omitting fields that are None."""
    payload: Dict[str, Any] = {"messages": req.messages}
    if req.temperature is not None:
        payload["temperature"] = req.temperature
    if req.max_tokens is not None:
        payload["max_tokens"] = req.max_tokens
    if req.enable_thinking is not None:
        payload["enable_thinking"] = req.enable_thinking
    if req.tools is not None:
        payload["tools"] = req.tools
    if req.tool_choice is not None:
        payload["tool_choice"] = req.tool_choice
    if req.extra_body is not None:
        payload["extra_body"] = req.extra_body
    return payload


MAX_IMAGE_BYTES = 10 * 1024 * 1024  # 10 MB

# ── Endpoints ─────────────────────────────────────────────────────────────────


@router.post("/chat")
async def chat(req: LLMRequest) -> Dict[str, Any]:
    """Synchronous chat passthrough — returns complete call_chain response."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    with llm_caller_context(req.caller or "llm_api.chat"):
        return await call_chain(slot, payload)


@router.post("/chat-stream")
async def chat_stream(req: LLMRequest) -> StreamingResponse:
    """SSE streaming chat. Each event: 'data: <json>\\n\\n'. Ends with 'data: [DONE]\\n\\n'."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    _caller = req.caller or "llm_api.chat_stream"

    async def _generate() -> AsyncIterator[str]:
        try:
            with llm_caller_context(_caller):
                async for event in call_chain_stream(slot, payload):
                    yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
        except RuntimeError as e:
            yield f"data: {json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(
        _generate(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",  # disable nginx buffering for SSE
        },
    )


@router.post("/tool-call")
async def tool_call(req: LLMRequest) -> Dict[str, Any]:
    """Function-calling passthrough — transparently forwards tools / tool_choice."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    with llm_caller_context(req.caller or "llm_api.tool_call"):
        return await call_chain(slot, payload)


@router.post("/intent-classify")
async def intent_classify(req: LLMRequest) -> Dict[str, Any]:
    """Intent classification — always uses temperature=0.1 for determinism."""
    slot = _parse_slot(req.slot)
    payload = _build_payload(req)
    # Override temperature regardless of what caller sent
    payload["temperature"] = 0.1
    with llm_caller_context(req.caller or "llm_api.intent_classify"):
        return await call_chain(slot, payload)


@router.post("/vision")
async def vision(
    image: UploadFile,
    prompt: str = Form(...),
    slot: str = Form(default="vl"),
) -> Dict[str, Any]:
    """Multipart vision endpoint — encodes image to base64, calls SLOT.VL."""
    parsed_slot = _parse_slot(slot)

    # Read image bytes and encode to base64 data-URI
    image_bytes = await image.read(MAX_IMAGE_BYTES + 1)
    if len(image_bytes) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image exceeds 10 MB limit")
    mime_type = image.content_type or "image/jpeg"
    b64_data = base64.b64encode(image_bytes).decode("ascii")
    data_uri = f"data:{mime_type};base64,{b64_data}"

    # Build OpenAI-style multimodal message
    messages: List[Dict[str, Any]] = [
        {
            "role": "user",
            "content": [
                {
                    "type": "image_url",
                    "image_url": {"url": data_uri},
                },
                {
                    "type": "text",
                    "text": prompt,
                },
            ],
        }
    ]

    payload: Dict[str, Any] = {"messages": messages}
    with llm_caller_context("llm_api.vision"):
        return await call_chain(parsed_slot, payload)
