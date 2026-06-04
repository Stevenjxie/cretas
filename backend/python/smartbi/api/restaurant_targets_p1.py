"""#58 Phase 1 (revenue-only) REST API — 周/日拆分 + 滚动预测 + pace 预警.

扩展 G2 (restaurant_targets.py)。新增 4 个端点 (all under /api/smartbi prefix):
  PUT  /restaurant-targets/decompose-weeks   — 月→周 拆分 (写 level='week' 行)
  GET  /restaurant-targets/forecast          — 滚动营收预测 (?horizon_days=30)
  GET  /restaurant-targets/pace-alerts       — 实时 pace 预警 (当前周期进度 vs 时间进度)
  POST /restaurant-targets/alerts/{id}/suppress — 管理员静默某条预警

Tenant 经 request.state.factory_id (auth middleware) + RLS。
RBAC: 营收/目标/预测金额对非 price-view 角色剥零 (_strip_forecast_money);
非金额方向性字段 (alert_level / pace_gap_pct / completion_pct) 仍可见。
"""
from __future__ import annotations

import logging
from datetime import date, datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, field_validator

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantTargetsP1"])

_VALID_KPI_KINDS = {"revenue", "bill_count"}

# Money carrier keys in the P1 payloads NOT caught by the shared _MONEY_PATTERN
# (it matches 'amount'/'revenue'/'target' but not 'bound'/'pct'-free numbers).
# Mirrors gold_reads._strip_extras for the bounds.
_FORECAST_MONEY_KEYS = frozenset({
    "forecast_amount", "lower_bound", "upper_bound",
    "actual_amount", "target_amount",
})

# Decompose-response revenue keys — monetary ONLY when kpiKind == 'revenue'
# (bill_count targets under these keys are counts, not money → NOT stripped).
_DECOMPOSE_REVENUE_KEYS = frozenset({"target_value", "month_target", "week_target"})


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _get_username(request: Request) -> str:
    return (
        getattr(request.state, "username", None)
        or _get_factory_id(request)
        or "unknown"
    )


async def _get_pool():
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


def _strip_forecast_money(
    node: Any, role: Optional[str], extra_money_keys: frozenset = frozenset()
) -> Any:
    """Depth-first null monetary fields for non-price-view roles.

    Price-view roles → passthrough. Others → every (_FORECAST_MONEY_KEYS |
    extra_money_keys) leaf set to None (Rule 4: None, never 0). alert_level /
    pace_gap_pct / completion_pct / elapsed_pct survive (directional, no amount).
    extra_money_keys lets callers add context-dependent money keys — e.g. the
    decompose response's target_value/month_target/week_target, which are money
    only when kpiKind == 'revenue'.
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    if role and role in PRICE_VIEW_ROLES:
        return node

    money_keys = _FORECAST_MONEY_KEYS | extra_money_keys

    def _walk(n: Any) -> None:
        if isinstance(n, dict):
            for k, v in list(n.items()):
                if k in money_keys and not isinstance(v, (dict, list)):
                    n[k] = None
                else:
                    _walk(v)
        elif isinstance(n, list):
            for item in n:
                _walk(item)

    _walk(node)
    return node


# ── PUT /decompose-weeks ──────────────────────────────────────────────────────
class DecomposeWeeksRequest(BaseModel):
    kpiKind: str = "revenue"
    periodKey: str  # month key '2026-06'
    storeId: Optional[int] = None
    cascadeToDays: bool = False  # also split each resulting week into days

    @field_validator("kpiKind")
    @classmethod
    def _vk(cls, v: str) -> str:
        if v not in _VALID_KPI_KINDS:
            raise ValueError(f"kpi_kind must be one of {_VALID_KPI_KINDS}")
        return v

    @field_validator("periodKey")
    @classmethod
    def _vp(cls, v: str) -> str:
        # month key 'YYYY-MM'
        parts = v.split("-")
        if len(parts) != 2 or not (parts[0].isdigit() and parts[1].isdigit()):
            raise ValueError("periodKey must be a month key 'YYYY-MM'")
        return v


@router.put("/restaurant-targets/decompose-weeks")
async def decompose_weeks(
    request: Request, body: DecomposeWeeksRequest
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")
    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    from smartbi.services.target_decomposition import (
        decompose_month_to_weeks,
        decompose_week_to_days,
    )

    created_by = _get_username(request)
    result = await decompose_month_to_weeks(
        pool, factory_id, body.periodKey,
        kpi_kind=body.kpiKind, store_id=body.storeId, created_by=created_by,
    )

    if body.cascadeToDays and result.get("weeks"):
        day_results = []
        for wk in result["weeks"]:
            dr = await decompose_week_to_days(
                pool, factory_id, wk["period_key"],
                kpi_kind=body.kpiKind, store_id=body.storeId,
                created_by=created_by,
            )
            day_results.append(dr)
        result["dayCascade"] = day_results

    # target_value/month_target/week_target are revenue amounts ONLY for kpiKind=='revenue';
    # bill_count targets are counts (not stripped). Gate accordingly.
    extra = _DECOMPOSE_REVENUE_KEYS if body.kpiKind == "revenue" else frozenset()
    result = _strip_forecast_money(result, _get_role(request), extra)
    return {"success": True, "data": result, "message": "ok"}


# ── GET /forecast ─────────────────────────────────────────────────────────────
@router.get("/restaurant-targets/forecast")
async def get_forecast(
    request: Request,
    horizon_days: int = Query(30, ge=1, le=90),
    window_days: int = Query(90, ge=14, le=365),
    store_id: Optional[int] = Query(None),
    persist: bool = Query(False, description="写回 restaurant_target_forecast"),
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")
    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    from smartbi.services.target_forecast import forecast_revenue

    try:
        result = await forecast_revenue(
            pool, factory_id,
            horizon_days=horizon_days, window_days=window_days,
            store_id=store_id, persist=persist,
        )
    except Exception as e:
        logger.exception("forecast failed: %s", e)
        raise HTTPException(status_code=500, detail=f"forecast failed: {e}")

    result = _strip_forecast_money(result, _get_role(request))
    return {"success": True, "data": result, "message": "ok"}


# ── GET /pace-alerts ──────────────────────────────────────────────────────────
@router.get("/restaurant-targets/pace-alerts")
async def get_pace_alerts(
    request: Request,
    kpi_kind: str = Query("revenue"),
    store_id: Optional[int] = Query(None),
    period_type: str = Query("month", description="month|week|day|year"),
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")
    if kpi_kind not in _VALID_KPI_KINDS:
        raise HTTPException(
            status_code=422, detail=f"kpi_kind must be one of {_VALID_KPI_KINDS}"
        )
    if period_type not in {"month", "week", "day", "year"}:
        raise HTTPException(
            status_code=422, detail="period_type must be month|week|day|year"
        )
    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    from smartbi.services.target_alert_scheduler import (
        compute_current_period_alert,
    )

    try:
        result = await compute_current_period_alert(
            pool, factory_id,
            kpi_kind=kpi_kind, store_id=store_id, period_type=period_type,
        )
    except Exception as e:
        logger.exception("pace-alerts failed: %s", e)
        raise HTTPException(status_code=500, detail=f"pace-alerts failed: {e}")

    result = _strip_forecast_money(result, _get_role(request))
    return {"success": True, "data": result, "message": "ok"}


# ── POST /alerts/{id}/suppress ────────────────────────────────────────────────
class SuppressRequest(BaseModel):
    suppressUntil: str  # YYYY-MM-DD

    @field_validator("suppressUntil")
    @classmethod
    def _vd(cls, v: str) -> str:
        try:
            datetime.strptime(v, "%Y-%m-%d")
        except ValueError:
            raise ValueError("suppressUntil must be YYYY-MM-DD")
        return v


@router.post("/restaurant-targets/alerts/{alert_id}/suppress")
async def suppress_alert(
    request: Request, alert_id: int, body: SuppressRequest
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")

    # Admin gate: only price-view (manager-class) roles may suppress.
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    role = _get_role(request)
    if not (role and role in PRICE_VIEW_ROLES):
        raise HTTPException(
            status_code=403, detail="需要管理员权限才能静默预警"
        )

    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    suppress_until = date.fromisoformat(body.suppressUntil)
    acknowledged_by = _get_username(request)

    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, true)", factory_id
        )
        async with conn.transaction():
            row = await conn.fetchrow(
                """
                UPDATE restaurant_target_alerts
                   SET suppressed_until = $1, acknowledged_by = $2
                 WHERE id = $3 AND factory_id = $4
                RETURNING id, period_type, period_key, suppressed_until
                """,
                suppress_until, acknowledged_by, alert_id, factory_id,
            )
    if row is None:
        raise HTTPException(status_code=404, detail=f"预警 {alert_id} 不存在")

    return {
        "success": True,
        "data": {
            "id": row["id"],
            "periodType": row["period_type"],
            "periodKey": row["period_key"],
            "suppressedUntil": row["suppressed_until"].isoformat(),
        },
        "message": "预警已静默",
    }
