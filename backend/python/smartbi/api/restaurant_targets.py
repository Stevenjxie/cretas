"""G2 餐饮目标拆分 + 达成率预警 REST API.

Routes (all under /api/smartbi prefix registered in main.py):
  POST /restaurant-targets             — upsert single target entry (idempotent)
  GET  /restaurant-targets/achievement — daily achievement summary
  GET  /restaurant-targets/alerts      — N-day alert timeline
  POST /restaurant-targets/alert-config — upsert alert thresholds

Tenant scoped via request.state.factory_id (auth middleware). RLS is enforced
by SET app.factory_id on every borrowed connection. GET endpoints return the
raw snake_case query dict; the FE pythonFetch transformKeys() converts to
camelCase client-side. POST endpoints return explicit camelCase data blocks.
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, field_validator, model_validator

logger = logging.getLogger(__name__)
router = APIRouter(tags=["RestaurantTargets"])

_VALID_KPI_KINDS = {"revenue", "bill_count"}
_VALID_LEVELS = {"year", "month", "week", "day"}


def _get_factory_id(request: Request) -> Optional[str]:
    return getattr(request.state, "factory_id", None)


def _get_role(request: Request) -> Optional[str]:
    return getattr(request.state, "role", None)


def _get_username(request: Request) -> str:
    return getattr(request.state, "username", None) or _get_factory_id(request) or "unknown"


async def _get_pool():
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


async def _set_tenant(conn, factory_id: str) -> None:
    """Parameterized RLS tenant context (injection-safe)."""
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


class TargetUpsertRequest(BaseModel):
    kpiKind: str
    level: str
    periodKey: str
    targetValue: float
    storeId: Optional[int] = None
    reason: Optional[str] = None

    @field_validator("targetValue")
    @classmethod
    def target_must_be_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError("target_value must be > 0")
        return v

    @field_validator("kpiKind")
    @classmethod
    def validate_kpi_kind(cls, v: str) -> str:
        if v not in _VALID_KPI_KINDS:
            raise ValueError(f"kpi_kind must be one of {_VALID_KPI_KINDS}")
        return v

    @field_validator("level")
    @classmethod
    def validate_level(cls, v: str) -> str:
        if v not in _VALID_LEVELS:
            raise ValueError(f"level must be one of {_VALID_LEVELS}")
        return v


class AlertConfigRequest(BaseModel):
    kpiKind: str
    level: str
    warnThreshold: float
    criticalThreshold: float
    storeId: Optional[int] = None

    @field_validator("kpiKind")
    @classmethod
    def validate_kpi_kind(cls, v: str) -> str:
        if v not in _VALID_KPI_KINDS:
            raise ValueError(f"kpi_kind must be one of {_VALID_KPI_KINDS}")
        return v

    @field_validator("level")
    @classmethod
    def validate_level(cls, v: str) -> str:
        if v not in _VALID_LEVELS:
            raise ValueError(f"level must be one of {_VALID_LEVELS}")
        return v

    @model_validator(mode="after")
    def warn_above_critical(self) -> "AlertConfigRequest":
        # review fix: 原 @field_validator('warnThreshold') 是死代码 (Pydantic v2 info.data
        # 不含后声明的 criticalThreshold) → 非法阈值绕过 422 落到 DB CHECK → 未捕获 500。
        # 改 model_validator(after) 看全字段, 非法组合返 422 而非 500。
        if self.warnThreshold <= self.criticalThreshold:
            raise ValueError("warn_threshold must be > critical_threshold")
        return self


@router.post("/restaurant-targets")
async def upsert_target(request: Request, body: TargetUpsertRequest) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")

    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    created_by = _get_username(request)

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        async with conn.transaction():
            # review fix: store_id 可空 → 按有无选对应 partial unique index 的 conflict target
            # (否则 PG NULLS DISTINCT 下 factory 级 store_id IS NULL 永不冲突 → 累加重复行不幂等)。
            # conflict_target 为静态串 (无用户输入), f-string 安全。
            conflict_target = (
                "(factory_id, kpi_kind, level, period_key) WHERE store_id IS NULL"
                if body.storeId is None
                else "(factory_id, kpi_kind, level, period_key, store_id) WHERE store_id IS NOT NULL"
            )
            row = await conn.fetchrow(
                f"""
                INSERT INTO restaurant_target_hierarchy
                    (factory_id, kpi_kind, level, period_key, store_id,
                     target_value, reason, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT {conflict_target}
                DO UPDATE SET
                    target_value = EXCLUDED.target_value,
                    reason       = EXCLUDED.reason,
                    updated_at   = NOW()
                RETURNING id, period_key, target_value, updated_at
                """,
                factory_id, body.kpiKind, body.level, body.periodKey,
                body.storeId, body.targetValue, body.reason, created_by,
            )

    updated_at = row["updated_at"]
    updated_at_str = (
        updated_at.isoformat() if isinstance(updated_at, datetime) else str(updated_at)
    )

    return {
        "success": True,
        "data": {
            "id": row["id"],
            "periodKey": row["period_key"],
            "targetValue": float(row["target_value"]),
            "updatedAt": updated_at_str,
        },
        "message": f"目标已保存 ({body.level}: {body.periodKey})",
    }


@router.get("/restaurant-targets/achievement")
async def get_achievement(
    request: Request,
    start_date: str = Query(..., description="YYYY-MM-DD"),
    end_date: str = Query(..., description="YYYY-MM-DD"),
    kpi_kind: str = Query("revenue"),
    level: str = Query("day"),
    store_id: Optional[int] = Query(None),
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")

    if kpi_kind not in _VALID_KPI_KINDS:
        raise HTTPException(status_code=422, detail=f"kpi_kind must be one of {_VALID_KPI_KINDS}")
    if level not in _VALID_LEVELS:
        raise HTTPException(status_code=422, detail=f"level must be one of {_VALID_LEVELS}")

    from datetime import datetime as _dt
    try:
        start = _dt.strptime(start_date, "%Y-%m-%d").date()
        end = _dt.strptime(end_date, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(status_code=400, detail="start_date/end_date must be YYYY-MM-DD")

    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    from smartbi.gold.queries import daily_achievement_summary
    result = await daily_achievement_summary(
        pool, factory_id, (start, end),
        kpi_kind=kpi_kind, level=level, store_id=store_id,
    )

    # RBAC strip: for revenue kpi, null monetary fields for non-price-view roles.
    # NOTE: response keys are snake_case ('target'/'actual') which the shared
    # _MONEY_PATTERN does not catch, so we null them explicitly here.
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    role = _get_role(request)
    if kpi_kind == "revenue" and not (role and role in PRICE_VIEW_ROLES):
        for pt in result.get("points", []):
            pt["target"] = None
            pt["actual"] = None

    return {"success": True, "data": result, "message": "ok"}


@router.get("/restaurant-targets/alerts")
async def get_alerts(
    request: Request,
    lookback_days: int = Query(7, ge=1, le=30),
    kpi_kind: str = Query("revenue"),
) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")

    if kpi_kind not in _VALID_KPI_KINDS:
        raise HTTPException(status_code=422, detail=f"kpi_kind must be one of {_VALID_KPI_KINDS}")

    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    from smartbi.gold.queries import alert_preview
    result = await alert_preview(pool, factory_id, lookback_days, kpi_kind=kpi_kind)

    # RBAC strip on revenue timeline monetary fields (snake_case keys).
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    role = _get_role(request)
    if kpi_kind == "revenue" and not (role and role in PRICE_VIEW_ROLES):
        for entry in result.get("timeline", []):
            entry["target"] = None
            entry["actual"] = None

    return {"success": True, "data": result, "message": "ok"}


@router.post("/restaurant-targets/alert-config")
async def upsert_alert_config(request: Request, body: AlertConfigRequest) -> Dict[str, Any]:
    factory_id = _get_factory_id(request)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")

    pool = await _get_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="smartbi_db unavailable")

    created_by = _get_username(request)

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        async with conn.transaction():
            # review fix: 同 target — store_id 可空选对应 partial unique index conflict target。
            conflict_target = (
                "(factory_id, kpi_kind, level) WHERE store_id IS NULL"
                if body.storeId is None
                else "(factory_id, kpi_kind, level, store_id) WHERE store_id IS NOT NULL"
            )
            row = await conn.fetchrow(
                f"""
                INSERT INTO restaurant_alert_config
                    (factory_id, kpi_kind, level, warn_threshold, critical_threshold,
                     store_id, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7)
                ON CONFLICT {conflict_target}
                DO UPDATE SET
                    warn_threshold     = EXCLUDED.warn_threshold,
                    critical_threshold = EXCLUDED.critical_threshold
                RETURNING id, warn_threshold, critical_threshold
                """,
                factory_id, body.kpiKind, body.level,
                body.warnThreshold, body.criticalThreshold,
                body.storeId, created_by,
            )

    return {
        "success": True,
        "data": {
            "id": row["id"],
            "warnThreshold": float(row["warn_threshold"]),
            "criticalThreshold": float(row["critical_threshold"]),
        },
        "message": "预警配置已保存",
    }
