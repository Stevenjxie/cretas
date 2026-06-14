"""Chart Auto-Insight API endpoint (U4 + U1.4 permission_tier server push).

POST /api/smartbi/chart-insight

🔒 RBAC (red-line — must pass Opus review):
- factoryId + role ALWAYS extracted from the verified JWT via request.state
  (set by JWTAuthMiddleware after HS256 decode).
- The request body's factory_id field is IGNORED for all authorization decisions.
- Cross-tenant: if jwt_factory_id != body.factory_id → 403 blocked at endpoint level.
- Missing JWT → 401 (missing factory context, not authenticated).
- U1.4: permission_tier is ALWAYS derived server-side from caller_role.
  body.permission_tier is IGNORED (attacker cannot self-elevate to finance_visible).
  Finance roles → finance_visible; everyone else → finance_hidden.

Auth chain:
  1. JWTAuthMiddleware (auth_middleware.py) verifies Bearer HS256 JWT.
  2. Extracts factoryId + role from JWT claims → stores in request.state.factory_id / .role.
  3. set_factory_id(factory_id) sets the ContextVar for asyncpg RLS (tenant_ctx.py).
  4. This endpoint reads request.state.factory_id as the ONLY trusted factory identifier.
  5. U1.4: endpoint derives permission_tier from request.state.role (NOT body.permission_tier).
  6. ChartInsightService receives jwt_factory_id=trusted_factory_id + server-derived permission_tier.
  7. Service enforces cross-tenant guard internally (ctx.factory_id != jwt_factory_id → None).

No /api/smartbi/chart/ prefix bypass — this endpoint REQUIRES auth (not in PUBLIC_PREFIXES).
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Chart Auto-Insight"])

# ---------------------------------------------------------------------------
# U1.4: FINANCE_ROLES — roles allowed to receive finance_visible permission tier.
# Mirrors FINANCE_ROLES in chart_insight_service.py (single source of truth is the
# service; this local copy is used for the API-layer tier derivation).
# Any role NOT in this set receives finance_hidden (fail-safe default).
# ---------------------------------------------------------------------------
FINANCE_ROLES: frozenset[str] = frozenset({
    "factory_super_admin",
    "platform_admin",
    "procurement_manager",
    "finance_manager",
    "sales_manager",
    "dispatcher",
    "production_manager",
    "restaurant_manager",
    "restaurant_owner",
    "restaurant_purchaser",
    "permission_admin",
    "department_admin",
})


def _derive_permission_tier(caller_role: Optional[str]) -> str:
    """U1.4: Derive permission_tier from JWT role — server-side, never from body.

    Returns:
        'finance_visible' if caller_role is in FINANCE_ROLES
        'finance_hidden'  otherwise (fail-safe — non-finance or unknown role)
    """
    if caller_role and caller_role in FINANCE_ROLES:
        return "finance_visible"
    return "finance_hidden"


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------

class ChartInsightRequest(BaseModel):
    """Request body for POST /api/smartbi/chart-insight.

    Note: factory_id in the body is used ONLY for the cross-tenant guard check —
    the authoritative factory_id for all DB and RBAC decisions comes from the JWT
    (request.state.factory_id), NOT this field. If body.factory_id != jwt_factory_id → 403.

    U1.4: permission_tier is NOT accepted from the body — it is always derived
    server-side from the caller's JWT role. This prevents a non-finance user from
    sending permission_tier='finance_visible' to receive finance-gated templates.
    """
    chart_type: str = Field(..., description="e.g. BAR, LINE, PIE")
    x_dim: str = Field(..., description="time|store|product|channel|category|other")
    y_metric: str = Field(..., description="revenue|quantity|margin|cost|count|pct|other")
    aggregation: str = Field("sum", description="sum|avg|max|count")
    domain: str = Field("restaurant", description="restaurant|factory|finance")
    data_pattern: str = Field(..., description="Canonical bucket string e.g. ranking:top-share:65-80:n4-8")
    factory_id: str = Field(..., description="Tenant ID (must match JWT factoryId; body value used for context only)")
    series_values: List[float] = Field(default_factory=list, description="Numeric series data")
    series_labels: List[str] = Field(default_factory=list, description="Labels for series items")


# ---------------------------------------------------------------------------
# Singleton service (lazy init)
# ---------------------------------------------------------------------------
_service = None


async def _get_service():
    """Lazily create ChartInsightService singleton with the shared pool.

    U1.3 eager init: if pool is None (DB not available), we do NOT cache a
    ChartInsightService with None budget_tracker in _service.  Instead, we
    return None so the endpoint returns 503 and a subsequent call (after DB
    comes up) will re-attempt init.  This prevents caching a broken service
    singleton that would then fail with AttributeError on every call.
    """
    global _service
    if _service is None:
        try:
            from smartbi.config import get_pg_pool
            from smartbi.agent.budget_tracker import AgentBudgetTracker
            from smartbi.services.insights.chart_insight_service import (
                ChartInsightService,
                DEFAULT_PROMOTE_THRESHOLD,
            )
            import os

            pool = await get_pg_pool()
            if pool is None:
                # U1.3: pool not ready — do NOT cache a broken service; caller gets 503
                logger.warning(
                    "[chart-insight] _get_service: pool is None — service not initialized, "
                    "will retry on next request"
                )
                return None

            budget_tracker = AgentBudgetTracker(pool)
            # Allow per-deployment threshold override via env
            threshold = int(os.environ.get("CHART_INSIGHT_PROMOTE_THRESHOLD", DEFAULT_PROMOTE_THRESHOLD))
            _service = ChartInsightService(
                pool=pool,
                budget_tracker=budget_tracker,
                promote_threshold=threshold,
            )
        except Exception as exc:
            logger.warning("[chart-insight] service init failed: %s", exc)
            return None
    return _service


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------

@router.post("/chart-insight")
async def chart_insight(request: Request, body: ChartInsightRequest) -> Dict[str, Any]:
    """Generate or retrieve a structured chart insight (Tier 2).

    🔒 Auth: requires valid JWT Bearer token. factoryId + role are read from
    request.state (injected by JWTAuthMiddleware), NOT from the request body.

    Returns:
        {success: true, data: {finding, implication?, suggestion?, source, tier}}
        {success: false, message: "..."} on error
    """
    # ------------------------------------------------------------------
    # 🔒 Step 1: Extract trusted identity from JWT via request.state
    # ------------------------------------------------------------------
    jwt_factory_id: Optional[str] = getattr(request.state, "factory_id", None)
    caller_role: Optional[str] = getattr(request.state, "role", None)
    auth_method: Optional[str] = getattr(request.state, "auth_method", None)

    # Internal Java→Python calls bypass JWT; they carry trusted X-Factory-Id
    if auth_method == "internal":
        # Internal calls: trust X-Factory-Id (already set in state by middleware)
        jwt_factory_id = jwt_factory_id  # keep as-is (set from header)

    if not jwt_factory_id:
        return JSONResponse(
            status_code=401,
            content={
                "success": False,
                "message": "Missing authentication: no factory context found. "
                           "Please include a valid Bearer JWT token.",
                "code": "UNAUTHORIZED",
            },
        )

    # ------------------------------------------------------------------
    # 🔒 Step 2: Cross-tenant guard — body.factory_id must match JWT
    # ------------------------------------------------------------------
    if body.factory_id != jwt_factory_id:
        logger.warning(
            "[chart-insight] cross-tenant attempt: jwt=%s body=%s role=%s",
            jwt_factory_id, body.factory_id, caller_role,
        )
        return JSONResponse(
            status_code=403,
            content={
                "success": False,
                "message": (
                    f"Access denied: your JWT identifies factory '{jwt_factory_id}', "
                    f"but the request body specifies '{body.factory_id}'. "
                    "These must match."
                ),
                "code": "FACTORY_MISMATCH",
            },
        )

    # ------------------------------------------------------------------
    # Step 3: Build context and call service
    # ------------------------------------------------------------------
    from smartbi.services.insights.chart_insight_service import (
        ChartInsightContext,
    )

    # U1.4: derive permission_tier server-side from JWT role — NEVER trust body.permission_tier
    # Internal calls (auth_method=='internal') also derive from X-User-Role header (set in state)
    server_permission_tier = _derive_permission_tier(caller_role)

    ctx = ChartInsightContext(
        chart_type=body.chart_type,
        x_dim=body.x_dim,
        y_metric=body.y_metric,
        aggregation=body.aggregation,
        domain=body.domain,
        data_pattern=body.data_pattern,
        permission_tier=server_permission_tier,  # U1.4: server-derived, body ignored
        factory_id=jwt_factory_id,  # Use JWT factoryId, NOT body.factory_id
        series_values=body.series_values,
        series_labels=body.series_labels,
    )

    svc = await _get_service()
    if svc is None:
        return JSONResponse(
            status_code=503,
            content={
                "success": False,
                "message": "ChartInsightService unavailable (pool not initialized)",
                "code": "SERVICE_UNAVAILABLE",
            },
        )

    try:
        result = await svc.get_insight(
            ctx,
            caller_role=caller_role,
            jwt_factory_id=jwt_factory_id,  # Pass explicitly for internal cross-tenant guard
        )
    except Exception as exc:
        logger.exception("[chart-insight] unexpected error for factory=%s", jwt_factory_id)
        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "message": f"Internal error: {exc}",
                "code": "INTERNAL_ERROR",
            },
        )

    if result is None:
        # Data insufficient, budget blocked, or RBAC blocked — do not fabricate
        return {
            "success": True,
            "data": None,
            "message": "No insight available (data insufficient, budget blocked, or permission denied)",
        }

    return {
        "success": True,
        "data": {
            "finding": result.finding,
            "implication": result.implication,
            "suggestion": result.suggestion,
            "source": result.source,
            "tier": result.tier,
            "input_hash": result.input_hash,  # G4: corpus row key for feedback endpoint
        },
    }


# ---------------------------------------------------------------------------
# G4 — User feedback endpoint
# POST /api/smartbi/insight/feedback
# ---------------------------------------------------------------------------

class InsightFeedbackRequest(BaseModel):
    """Request body for POST /api/smartbi/insight/feedback.

    The frontend sends the input_hash it received in the chart-insight response
    data (or re-computes it from the same context). The vote is "up" or "down".
    factory_id must match the JWT (cross-tenant guard).
    """
    input_hash: str = Field(..., min_length=1, description="sha256 hex of corpus row input_text")
    vote: str = Field(..., description='"up" or "down"')
    factory_id: str = Field(..., description="Tenant ID — must match JWT factoryId")


# SQL: fetch the row to verify existence + read current metadata
_FEEDBACK_FETCH_SQL = """
    SELECT id, quality, metadata
    FROM smart_bi_distillation_samples
    WHERE input_hash = $1
    LIMIT 1
"""

# SQL: update metadata + optionally demote quality
_FEEDBACK_UPDATE_SQL = """
    UPDATE smart_bi_distillation_samples
    SET metadata = $1::jsonb,
        quality  = $2
    WHERE input_hash = $3
"""


@router.post("/insight/feedback")
async def insight_feedback(request: Request, body: InsightFeedbackRequest) -> Dict[str, Any]:
    """Record 👍/👎 user feedback for a served corpus insight (G4).

    🔒 Auth: requires valid JWT Bearer token. factory_id cross-check enforced.

    Vote logic:
      👎 down: increment metadata.feedback_down; if feedback_down >= 2 → demote
               quality to min(current_quality, 2) so the row is excluded from
               training export AND skipped by the serve-path.
      👍 up:   increment metadata.feedback_up (positive signal; does NOT inflate
               quality above the row's gate value — feedback records signal only).

    Returns:
        {success: true,  data: {feedback_recorded: true, new_quality: int, vote: str}}
        {success: false, message: "..."} — explicit on row-not-found or bad vote
    """
    # ------------------------------------------------------------------
    # 🔒 Auth: extract JWT identity
    # ------------------------------------------------------------------
    jwt_factory_id: Optional[str] = getattr(request.state, "factory_id", None)
    if not jwt_factory_id:
        return JSONResponse(
            status_code=401,
            content={
                "success": False,
                "message": "Missing authentication: no factory context found.",
                "code": "UNAUTHORIZED",
            },
        )

    # Cross-tenant guard
    if body.factory_id != jwt_factory_id:
        logger.warning(
            "[insight-feedback] cross-tenant attempt: jwt=%s body=%s",
            jwt_factory_id, body.factory_id,
        )
        return JSONResponse(
            status_code=403,
            content={
                "success": False,
                "message": (
                    f"Access denied: your JWT identifies factory '{jwt_factory_id}', "
                    f"but the request body specifies '{body.factory_id}'."
                ),
                "code": "FACTORY_MISMATCH",
            },
        )

    # Validate vote
    vote = body.vote.lower().strip()
    if vote not in ("up", "down"):
        return JSONResponse(
            status_code=400,
            content={
                "success": False,
                "message": f"Invalid vote '{body.vote}': must be 'up' or 'down'.",
                "code": "INVALID_VOTE",
            },
        )

    # ------------------------------------------------------------------
    # DB update
    # ------------------------------------------------------------------
    svc = await _get_service()
    if svc is None or svc._pool is None:
        return JSONResponse(
            status_code=503,
            content={
                "success": False,
                "message": "Feedback service unavailable (pool not initialized)",
                "code": "SERVICE_UNAVAILABLE",
            },
        )

    pool = svc._pool
    try:
        import json as _json

        async with pool.acquire() as conn:
            row = await conn.fetchrow(_FEEDBACK_FETCH_SQL, body.input_hash)
            if row is None:
                # No-fake-data: explicit not-found, never silently swallow
                return {
                    "success": False,
                    "message": (
                        f"Corpus row not found for input_hash '{body.input_hash[:16]}…'. "
                        "The insight may have expired (corpus rows are date-partitioned) "
                        "or the hash is incorrect."
                    ),
                    "code": "ROW_NOT_FOUND",
                }

            current_quality: int = int(row["quality"] or 5)
            raw_meta = row["metadata"]
            try:
                meta_dict: Dict[str, Any] = (
                    raw_meta if isinstance(raw_meta, dict) else (
                        _json.loads(raw_meta) if raw_meta else {}
                    )
                )
            except Exception:
                meta_dict = {}

            # Apply vote: increment counter, enforce idempotency via simple integer add
            if vote == "down":
                meta_dict["feedback_down"] = int(meta_dict.get("feedback_down", 0)) + 1
            else:
                meta_dict["feedback_up"] = int(meta_dict.get("feedback_up", 0)) + 1

            # Demote logic (G4):
            # If 👎 count reaches the threshold → set quality = min(quality, 2)
            # quality 2 is below the training export gate (q>=4) AND below the
            # census quality threshold, so this row stops polluting future training.
            new_quality = current_quality
            feedback_down = int(meta_dict.get("feedback_down", 0))
            if vote == "down" and feedback_down >= 2:
                new_quality = min(current_quality, 2)
                meta_dict["demoted_by_feedback"] = True
                logger.info(
                    "[insight-feedback] demoting corpus row input_hash=%s... "
                    "feedback_down=%d → quality %d→%d",
                    body.input_hash[:12], feedback_down, current_quality, new_quality,
                )

            meta_json = _json.dumps(meta_dict, ensure_ascii=False)
            await conn.execute(_FEEDBACK_UPDATE_SQL, meta_json, new_quality, body.input_hash)

        logger.info(
            "[insight-feedback] recorded vote=%s input_hash=%s... factory=%s "
            "new_quality=%d feedback_down=%d feedback_up=%d",
            vote, body.input_hash[:12], jwt_factory_id, new_quality,
            int(meta_dict.get("feedback_down", 0)),
            int(meta_dict.get("feedback_up", 0)),
        )
        return {
            "success": True,
            "data": {
                "feedback_recorded": True,
                "vote": vote,
                "new_quality": new_quality,
            },
            "message": "感谢反馈",
        }

    except Exception as exc:
        logger.exception("[insight-feedback] unexpected error for input_hash=%s", body.input_hash[:16])
        return JSONResponse(
            status_code=500,
            content={
                "success": False,
                "message": f"Internal error recording feedback: {exc}",
                "code": "INTERNAL_ERROR",
            },
        )
