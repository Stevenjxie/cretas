"""P2 综合分析 (multi-dim synthesis) HTTP endpoints.

Path A entrypoint: Java COMPREHENSIVE_SYNTHESIS intent → Java tool →
GoldFinanceClient.fetchComprehensiveSynthesis → POST /api/smartbi/synthesis/comprehensive.

Both a non-streaming (POST /comprehensive) and an SSE streaming
(POST /comprehensive-stream) variant are provided. The stream re-uses the
general-analysis-stream event vocabulary (status / chunk / charts / done) so the
existing FE AIQuery renderer handles it without changes.

Window resolution
-----------------
Reviews aggregate over the full corpus (no date range). Finance/sales need a
(start, end). When the caller doesn't pin a range we default to the factory's
actual Gold data span (data_range) — never "last 7 days from today" (mirror
GoldBackedRestaurantTool.resolveWindow rationale). Falls back to a trailing
12-month window if the factory has no Gold rows yet.

Tenant scope: request.state.factory_id (auth_middleware sets it from JWT or the
X-Factory-Id internal header). RBAC money-strip is NOT re-applied here because the
synthesis answer is narrative text built from gold queries that already ran under
the tenant's role context (the FactBook prompt is redacted at egress, not stripped
of numbers — the user asking IS the tenant). The X-User-Role forwarded by Java is
used by the gold finance pulls' own RBAC path if needed.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from datetime import date, datetime, timedelta
from typing import Optional, Tuple

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine
from smartbi.config import get_pg_pool
from smartbi.gold import data_range
from smartbi.gold.restaurant_ops_router import _resolve_sales_date_range
from smartbi.services.chat_session_service import parse_trusted_user_id

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/synthesis", tags=["Comprehensive Synthesis"])

DEFAULT_QUESTION = "综合分析本店的评价与经营情况"


class SynthesisRequest(BaseModel):
    question: Optional[str] = Field(None, description="自由问题; 默认综合分析")
    message: Optional[str] = Field(None, description="question 的别名 (Java compat)")
    start_date: Optional[str] = Field(None, description="YYYY-MM-DD; 不传按 Gold 数据范围")
    end_date: Optional[str] = Field(None, description="YYYY-MM-DD; 不传按 Gold 数据范围")
    factory_id: Optional[str] = Field(None, description="belt-and-suspenders; 默认 JWT 租户")
    # P2 multi-turn memory (2026-07-09): OPTIONAL v2 conv-memory session_id
    # (same mechanism as chat.py's stream handler — see
    # smartbi.services.chat_session_service.ChatSessionService). When present,
    # the last few turns are passed to synthesize() purely to resolve
    # indirect follow-up references ("展开第三点"/"那家店呢"/"它呢"); numbers
    # still come solely from this call's own FactBook. Omitted (None, the
    # default) → no history lookup → behavior identical to before this field
    # existed (backward compat).
    session_id: Optional[str] = Field(None, description="v2 conv memory session_id (optional)")

    @property
    def effective_question(self) -> str:
        return (self.question or self.message or DEFAULT_QUESTION).strip()


def _parse_date(s: str, field: str) -> date:
    try:
        return datetime.strptime(s, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(status_code=400, detail=f"{field} must be YYYY-MM-DD, got {s!r}")


async def _resolve_window(
    pool, factory_id: str, start_date: Optional[str], end_date: Optional[str],
    *, question: Optional[str] = None,
) -> Tuple[date, date]:
    """Resolve the analysis window.

    Priority: pinned range (both dates) > relative-time phrase in the question
    ("这两个月" / "上个月" / "最近30天", anchored to the data's max date, not today —
    demo/historical data lives in the past) > full factory Gold data span >
    trailing 12mo. No phrase → full data span (unchanged legacy behaviour).
    """
    if start_date and end_date:
        s = _parse_date(start_date, "start_date")
        e = _parse_date(end_date, "end_date")
        if s > e:
            raise HTTPException(status_code=400, detail="start_date > end_date")
        return s, e
    try:
        dr = await data_range(pool, factory_id)
        mn, mx = dr.get("min_date"), dr.get("max_date")
        if mn and mx:
            data_min, data_max = date.fromisoformat(mn), date.fromisoformat(mx)
            # F1: honor an owner's relative-time phrase if present. Anchor to
            # data_max (reuse the ops router's battle-tested parser incl. 俩月/仨月),
            # then clamp to the available span. Phrase absent → (None,None) → full span.
            if question:
                try:
                    (rs, re_), _lbl = _resolve_sales_date_range(question, today=data_max)
                    # A bare "今天"/"今日" (single-day) is almost always an action
                    # clause ("综合看看经营，今天先做哪几家店"), NOT a request to
                    # analyze one day — the parser is tuned for sales prompts where
                    # 1 day is valid. Ignore it for multi-dim synthesis (audit A#2).
                    if rs and re_ and _lbl not in ("今天",):
                        s = max(rs, data_min)
                        e = min(re_, data_max)
                        if s <= e:
                            return s, e
                        # Parsed window has NO overlap with available data (owner
                        # asked "上个月" but no data there). Return the honest empty
                        # window — never silently substitute full history and label
                        # all-time numbers as if answering the phrase (audit A#1,
                        # 禁止降级/no-fake-data). Empty factbook → honest "no data".
                        return rs, re_
                except Exception as _rex:  # parser must never break window resolution
                    logger.warning("[synthesis] relative-window parse failed: %s", _rex)
            return data_min, data_max
    except Exception as e:
        logger.warning("[synthesis] data_range failed for %s: %s", factory_id, e)
    end = date.today()
    return end - timedelta(days=365), end


def _trusted_user_id(request: Request) -> Optional[int]:
    """Return the authenticated positive user id attached by auth middleware.

    Conversation memory must never infer identity from the request body or
    headers. A missing or malformed middleware principal disables memory for
    the request while leaving the stateless synthesis response available.
    """
    return parse_trusted_user_id(getattr(request.state, "user_id", None))


async def _lookup_conversation_history(
    pool,
    session_id: Optional[str],
    factory_id: str,
    user_id: Optional[int],
):
    """Optional read-side of P2 multi-turn memory for the HTTP endpoints below.

    Returns the bounded turns_history (list of {q, a_summary, ts} dicts, see
    ChatSessionService.upsert) if session_id is present and the session hits;
    None otherwise (no session_id, miss, or lookup error). Never raises —
    mirrors the other non-fatal chat_session_service call sites (chat.py).
    Only a READ — this endpoint has no write-back; the FE conversational flow
    (chat.py's stream handler) already looks up + writes back for its own
    session_id, and body.session_id here is for direct/Java callers that want
    the same reference-resolution benefit without owning their own writeback.
    """
    if not session_id or user_id is None or user_id <= 0:
        return None
    try:
        from smartbi.services.chat_session_service import ChatSessionService
        parent = await ChatSessionService(pool).lookup(
            session_id,
            factory_id,
            user_id=user_id,
        )
        return parent.get("turns_history") if parent else None
    except Exception as e:
        logger.warning(f"[synthesis] conversation_history lookup failed (non-fatal): {e}")
        return None


async def _write_conversation_turn(
    pool,
    session_id: Optional[str],
    factory_id: str,
    user_id: Optional[int],
    query: str,
    answer: str,
    source: Optional[str] = None,
):
    if not session_id or user_id is None or user_id <= 0:
        return
    # Don't record a degraded (budget-exhausted / LLM-unavailable) turn - its
    # placeholder answer would occupy a slot in the bounded last-3-turn window and
    # evict a real answered turn, silently breaking the next pronoun follow-up.
    if source == "degraded":  # RESULT_SOURCE_DEGRADED
        return
    try:
        from smartbi.services.chat_session_service import ChatSessionService
        await ChatSessionService(pool).upsert(
            session_id=session_id,
            factory_id=factory_id,
            parent_query=(query or "")[:500],
            parent_answer_summary=answer or "",
            parent_template_code="COMPREHENSIVE_SYNTHESIS",
            user_id=user_id,
        )
    except Exception as e:
        logger.warning("[synthesis] session writeback skipped: %s", e)


def _factory_id(request: Request, override: Optional[str]) -> str:
    fid = getattr(request.state, "factory_id", None)
    if not fid:
        raise HTTPException(status_code=401, detail="tenant context not set")
    if override and override != fid:
        raise HTTPException(
            status_code=403,
            detail=f"factory_id {override!r} doesn't match JWT tenant {fid!r}",
        )
    return fid


@router.post("/comprehensive")
async def comprehensive(request: Request, body: SynthesisRequest):
    """Non-streaming multi-dim synthesis. Returns answer + charts + plan + fact_check."""
    fid = _factory_id(request, body.factory_id)
    q = body.effective_question
    if len(q) > 500:
        raise HTTPException(status_code=400, detail="question too long (max 500 chars)")
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="database not available")
    window = await _resolve_window(pool, fid, body.start_date, body.end_date, question=q)
    engine = ComprehensiveSynthesisEngine(pool)
    user_id = _trusted_user_id(request)
    conversation_history = await _lookup_conversation_history(
        pool,
        body.session_id,
        fid,
        user_id,
    )
    resp = await engine.synthesize(fid, q, window, conversation_history=conversation_history)
    await _write_conversation_turn(
        pool,
        body.session_id,
        fid,
        user_id,
        (body.question or body.message or ""),
        resp.answer or "",
        resp.source,
    )
    return resp.to_dict()


@router.post("/comprehensive-stream")
async def comprehensive_stream(request: Request, body: SynthesisRequest):
    """SSE streaming multi-dim synthesis.

    Events (reuse general-analysis-stream vocabulary):
      event: status  — progress
      event: chunk   — answer text (chunked; synthesis builds the full answer
                       then streams it in fixed-size slices, same as the
                       restaurant_ops gold path)
      event: charts  — chart configs
      event: alerts  — 🔒 反回扣(anti-kickback) alerts (see
                       ComprehensiveSynthesisEngine.collect_alerts); also
                       included in the final `done` frame for a client that
                       only reads `done` (mirrors the `charts` dual-emit).
      event: done    — final summary
      event: error   — on failure
    """
    fid = _factory_id(request, body.factory_id)
    user_id = _trusted_user_id(request)
    q = body.effective_question
    if len(q) > 500:
        raise HTTPException(status_code=400, detail="question too long (max 500 chars)")

    def _sse(event: str, data) -> str:
        return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False, default=str)}\n\n"

    async def gen():
        t0 = time.time()
        try:
            pool = await get_pg_pool()
            if pool is None:
                yield _sse("error", "数据库暂时不可用，请稍后重试")
                return
            window = await _resolve_window(pool, fid, body.start_date, body.end_date, question=q)
            yield _sse("status", "正在汇总评价与经营多维数据…")
            engine = ComprehensiveSynthesisEngine(pool)
            conversation_history = await _lookup_conversation_history(
                pool,
                body.session_id,
                fid,
                user_id,
            )
            resp = await engine.synthesize(fid, q, window, conversation_history=conversation_history)
            # Stream the (already redaction-restored) answer in fixed slices.
            answer = resp.answer or ""
            chunk_size = 40
            for i in range(0, len(answer), chunk_size):
                yield _sse("chunk", answer[i:i + chunk_size])
                # Pace the slices so the (already-computed, reconciled) answer
                # visibly types out on the client instead of arriving in one blob.
                await asyncio.sleep(0.04)
            if resp.charts:
                yield _sse("charts", resp.charts)
            if resp.alerts:
                yield _sse("alerts", resp.alerts)
            yield _sse("done", {
                "success": True,
                "answer": answer,
                "charts": resp.charts,
                "alerts": resp.alerts,
                "source": resp.source,
                "plan": resp.plan,
                "tokens": resp.tokens,
                "fact_check": resp.fact_check,
                "processingTimeMs": int((time.time() - t0) * 1000),
            })
            await _write_conversation_turn(
                pool,
                body.session_id,
                fid,
                user_id,
                (body.question or body.message or ""),
                answer,
                resp.source,
            )
        except Exception as e:
            logger.exception("[synthesis] stream failed: %s", e)
            yield _sse("error", "综合分析处理失败，请稍后重试")

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
