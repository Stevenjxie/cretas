"""Chart Auto-Insight API endpoint (U4).

POST /api/smartbi/chart-insight

🔒 RBAC (red-line — must pass Opus review):
- factoryId + role ALWAYS extracted from the verified JWT via request.state
  (set by JWTAuthMiddleware after HS256 decode).
- The request body's factory_id field is IGNORED for all authorization decisions.
- Cross-tenant: if jwt_factory_id != body.factory_id → 403 blocked at endpoint level.
- Missing JWT → 401 (missing factory context, not authenticated).

Auth chain:
  1. JWTAuthMiddleware (auth_middleware.py) verifies Bearer HS256 JWT.
  2. Extracts factoryId + role from JWT claims → stores in request.state.factory_id / .role.
  3. set_factory_id(factory_id) sets the ContextVar for asyncpg RLS (tenant_ctx.py).
  4. This endpoint reads request.state.factory_id as the ONLY trusted factory identifier.
  5. ChartInsightService receives jwt_factory_id=trusted_factory_id.
  6. Service enforces cross-tenant guard internally (ctx.factory_id != jwt_factory_id → None).

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
# Request / Response models
# ---------------------------------------------------------------------------

class ChartInsightRequest(BaseModel):
    """Request body for POST /api/smartbi/chart-insight.

    Note: factory_id in the body is used ONLY for computing the signature and
    filling slots in context — the authoritative factory_id for all DB and RBAC
    decisions comes from the JWT (request.state.factory_id), NOT this field.
    If body.factory_id != jwt_factory_id → 403.
    """
    chart_type: str = Field(..., description="e.g. BAR, LINE, PIE")
    x_dim: str = Field(..., description="time|store|product|channel|category|other")
    y_metric: str = Field(..., description="revenue|quantity|margin|cost|count|pct|other")
    aggregation: str = Field("sum", description="sum|avg|max|count")
    domain: str = Field("restaurant", description="restaurant|factory|finance")
    data_pattern: str = Field(..., description="Canonical bucket string e.g. ranking:top-share:65-80")
    permission_tier: str = Field("finance_visible", description="finance_visible|price_hidden|finance_hidden")
    factory_id: str = Field(..., description="Tenant ID (must match JWT factoryId; body value used for context only)")
    series_values: List[float] = Field(default_factory=list, description="Numeric series data")
    series_labels: List[str] = Field(default_factory=list, description="Labels for series items")


# ---------------------------------------------------------------------------
# Singleton service (lazy init)
# ---------------------------------------------------------------------------
_service = None


async def _get_service():
    """Lazily create ChartInsightService singleton with the shared pool."""
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
            budget_tracker = AgentBudgetTracker(pool) if pool else None
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
        ChartInsightService,
    )

    ctx = ChartInsightContext(
        chart_type=body.chart_type,
        x_dim=body.x_dim,
        y_metric=body.y_metric,
        aggregation=body.aggregation,
        domain=body.domain,
        data_pattern=body.data_pattern,
        permission_tier=body.permission_tier,
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
        },
    }
