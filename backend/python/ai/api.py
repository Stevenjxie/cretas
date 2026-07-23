"""POST /api/ai/intent/match endpoint — Phase 2B-α.

Java AIIntentService.matchIntent() proxies stages 5-8 (SEMANTIC / CLASSIFIER /
FUSION / LLM) to this endpoint after its own stages 1-4 + cache miss.

Auth model (per spec §5.2):
- /api/ai/* is in auth_middleware.PUBLIC_PREFIXES → JWT skipped
- Middleware checks X-Internal-Secret header → sets request.state.auth_method
  = "internal" + request.state.factory_id from X-Factory-Id header
- This endpoint additionally verifies body.factoryId == request.state.factory_id
  to reject smuggled cross-tenant requests at the application layer.

Wire shape: ApiResponse<IntentMatchResultDto> envelope (per
.claude/rules/api-response-handling.md → { success, data, message, code? }).
Java's ApiResponse<T> wrapper deserializes this directly.
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from ai.db import filter_intents_for_request, get_current_snapshot
from ai.dto import (
    ApiResponse,
    IntentMatchRequest,
    IntentMatchResultDto,
)
from ai.embedding import _request_embedding_cache
from ai.orchestrator import Orchestrator

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/ai", tags=["AI Intent"])


# ======================================================================
# β W6: request-scoped embedding cache reset
# ======================================================================
#
# ai.embedding._request_embedding_cache is a ContextVar holding a per-request
# {text → vector} cache that lets multiple match() calls within one request
# share embedding lookups. FastAPI runs each request in its own asyncio task
# context, so ContextVar values *should* be naturally isolated — but route
# handlers reuse the same anyio worker and the default value is a mutable dict
# shared across requests. Resetting via this Depends() seam guarantees a clean
# {} per request and prevents cross-request leakage of cached vectors.


async def reset_embedding_cache() -> None:
    """β W6: reset request-scoped embedding cache before each /api/ai/* call."""
    _request_embedding_cache.set({})


# ======================================================================
# Helpers
# ======================================================================


def _err(code: str, message: str, status_code: int) -> JSONResponse:
    """Build error JSONResponse with project ApiResponse envelope shape."""
    return JSONResponse(
        status_code=status_code,
        content={
            "success": False,
            "data": None,
            "message": message,
            "code": code,
        },
    )


def _get_orchestrator(request: Request) -> Orchestrator:
    """Resolve orchestrator from app.state.

    main.py startup hook is responsible for constructing the Orchestrator
    (with its 3 matchers wired) and storing it on app.state.ai_orchestrator.
    Tests bypass this entirely by mocking _do_match.
    """
    orch = getattr(request.app.state, "ai_orchestrator", None)
    if orch is None:
        raise RuntimeError(
            "ai_orchestrator not registered on app.state — main.py startup hook required"
        )
    return orch


async def _do_match(
    request: Request,
    body: IntentMatchRequest,
) -> IntentMatchResultDto:
    """Pure match logic — separated from the route handler so tests can mock
    this seam directly without spinning up a full app + orchestrator wiring.

    Tests patch `ai.api._do_match` to short-circuit orchestrator resolution
    and DB reads. Production path:
    1. Resolve orchestrator from request.app.state (configured at startup).
    2. Read snapshot (held by db module singleton — periodically refreshed by
       main.py startup background task).
    3. Filter intents by factory_id + business_type (visibility rules).
    4. Resolve thresholds from request options or AIConfig defaults.
    5. Delegate to orchestrator.match() to run stages 5-8 short-circuit.
    """
    orchestrator = _get_orchestrator(request)

    snap = get_current_snapshot()
    visible_rows = filter_intents_for_request(
        snap.rows,
        factoryId=body.factoryId,
        businessType=body.businessType,
    )
    # P1 读写分块 (2026-07-23 spec §4.3): 咨询 tab 剔除写意图 / 操作 tab
    # 剔除无权限写意图。mode 为 None (老客户端) 时零行为变化。
    from ai.db import filter_rows_by_rw_mode
    visible_rows = filter_rows_by_rw_mode(visible_rows, body.mode, body.userPermissions)
    if (body.businessType or "").upper() == "RESTAURANT":
        # R30: RESTAURANT_OPS_* 分析家族已由 tiered-first 反转接管 —
        # LLM 候选里保留它们只会制造误匹配面 (审计 S 类: 该家族描述互相
        # 0.4-0.7 相似)。剔除后候选更小更准; delegate:false 的兜底路由
        # 走 phrase shortcut / EXACT / 关键词层, 不依赖 LLM 候选。
        visible_rows = [
            r for r in visible_rows
            if not str(r.get("intent_code") or "").startswith("RESTAURANT_OPS_")
        ]

    # options has defaults via Field(default_factory) — always non-None.
    # minConfidence default comes from the request envelope (0.70) which
    # mirrors AIConfig.min_confidence_default; honour caller's value as-is.
    options = body.options
    min_confidence = options.minConfidence
    enable_llm = options.enableLlmFallback

    return await orchestrator.match(
        query=body.query,
        factoryId=body.factoryId,
        businessType=body.businessType,
        userId=body.userId,
        role=body.role,
        visible_intents=visible_rows,
        history=body.history,
        min_confidence=min_confidence,
        enable_llm=enable_llm,
    )


# ======================================================================
# POST /api/ai/intent/match
# ======================================================================


@router.post("/intent/match", dependencies=[Depends(reset_embedding_cache)])
async def match_intent(request: Request, body: IntentMatchRequest):
    """Match user query → IntentMatchResultDto.

    Returns ApiResponse<IntentMatchResultDto>.

    Errors:
    - 401 AUTH_INTERNAL_SECRET_MISMATCH — middleware did not set
      request.state.auth_method == "internal" (wrong/missing X-Internal-Secret).
    - 403 AUTH_FACTORY_ID_MISMATCH — body factoryId disagrees with header
      X-Factory-Id (smuggled cross-tenant).
    - 500 INTERNAL_ERROR — orchestrator threw or app.state not configured.
    """
    auth_method = getattr(request.state, "auth_method", None)
    if auth_method != "internal":
        return _err(
            "AUTH_INTERNAL_SECRET_MISMATCH",
            "X-Internal-Secret header missing or invalid",
            401,
        )

    header_factory = getattr(request.state, "factory_id", None)
    if header_factory is not None and header_factory != body.factoryId:
        logger.warning(
            "Factory ID mismatch: header=%s body=%s",
            header_factory, body.factoryId,
        )
        return _err(
            "AUTH_FACTORY_ID_MISMATCH",
            f"Body factoryId={body.factoryId} does not match header X-Factory-Id={header_factory}",
            403,
        )

    try:
        # _do_match is the mockable seam — handles orchestrator resolution
        # + DB read + match. Tests patch `ai.api._do_match` directly to
        # bypass app.state wiring.
        result = await _do_match(request, body)
        return ApiResponse.ok(
            data=result.model_dump(mode="json"),
            message="OK",
        ).model_dump()
    except Exception:  # pragma: no cover — defensive top-level handler
        logger.exception("Intent match failed for query=%r factory=%s",
                         body.query[:80], body.factoryId)
        return _err("INTERNAL_ERROR", "Intent matching failed", 500)


# ======================================================================
# POST /api/ai/intent/cache/invalidate (optional admin endpoint)
# ======================================================================


@router.post("/intent/cache/invalidate")
async def invalidate_cache(request: Request):
    """Force-reload ai_intent_configs snapshot.

    Called by Java web-admin after intent CRUD so Python doesn't have to wait
    AI_CONFIG_REFRESH_S seconds for the periodic refresh.

    Auth: same internal-secret check as /intent/match. Returns ApiResponse.
    """
    auth_method = getattr(request.state, "auth_method", None)
    if auth_method != "internal":
        return _err(
            "AUTH_INTERNAL_SECRET_MISMATCH",
            "X-Internal-Secret header missing or invalid",
            401,
        )

    pool = getattr(request.app.state, "pg_pool", None)
    if pool is None:
        # No DB pool wired (e.g. tests, smoke env without DB). Treat as no-op
        # success — caller doesn't need to retry.
        return ApiResponse.ok(
            data={"reloaded": False, "reason": "no pg_pool"},
            message="No-op: no pg_pool registered",
        ).model_dump()

    try:
        from ai.db import load_snapshot
        snap = await load_snapshot(pool)
        return ApiResponse.ok(
            data={
                "reloaded": True,
                "rows": len(snap.rows),
                "max_config_version": snap.max_config_version,
            },
            message="Snapshot reloaded",
        ).model_dump()
    except Exception:  # pragma: no cover — defensive
        logger.exception("Cache invalidate failed")
        return _err("INTERNAL_ERROR", "Cache invalidate failed", 500)


__all__ = ["router"]
