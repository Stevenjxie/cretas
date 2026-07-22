"""餐饮 outlier API (Phase B-1).

Endpoints:
  GET    /api/restaurant/outliers
  POST   /api/restaurant/outliers/dismiss             (Task 6)
  DELETE /api/restaurant/outliers/dismiss/{id}        (Task 6)

Reviewer R6: cache 启动加 warning 提示单 worker 假设.
Reviewer R2 critical: response payload includes baselineSource + baselineN
to make the fallback (self vs global) transparent to admins.

Phase A reuse:
  - require_admin (admin-tier RBAC) from canonical.provenance._admin_auth
  - Quick-Win 3 cross-factory pattern: non-platform_admin only sees own factory
  - W0.4 finding 3: RLS GUC set_config MUST be inside conn.transaction()
"""
from __future__ import annotations

import asyncpg
import logging
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Tuple

from fastapi import APIRouter, Body, HTTPException, Path, Query, Request

from smartbi.canonical.provenance._admin_auth import require_admin
from smartbi.services.outlier_service import (
    DEFAULT_KPI_KINDS,
    DEFAULT_WINDOW_DAYS,
    KPI_LABELS,
    OutlierService,
)

_VALID_BASELINE_SOURCES = frozenset({'self', 'global'})

logger = logging.getLogger(__name__)
logger.warning(
    "[outlier_api] Module-level cache assumes single-worker uvicorn. "
    "If you switch to --workers > 1, add Redis backend (see backlog)."
)

router = APIRouter()
_service = OutlierService()

_CACHE_TTL_S = 300
_cache: Dict[str, Tuple[float, Dict[str, Any]]] = {}


def _invalidate_cache(factory_id: str) -> None:
    """Invalidate all cached outlier responses for a factory (used by POST/DELETE).

    Wipes all cache entries matching factory_id:* pattern (all windowDays variants).
    """
    keys_to_remove = [k for k in _cache if k.startswith(f"{factory_id}:")]
    for k in keys_to_remove:
        _cache.pop(k, None)


def _validate_factory_access(request: Request, factory_id: str) -> None:
    """Quick-Win 3 pattern: cross-factory check.

    Non-platform_admin can only access their own factory's outliers.
    The 403 detail must mention 'platform_admin' so the FE / docs can
    explain the rule.
    """
    role = getattr(request.state, "role", None)
    jwt_factory_id = getattr(request.state, "factory_id", None) or ""
    if role != "platform_admin" and factory_id != jwt_factory_id:
        raise HTTPException(
            status_code=403,
            detail=(
                f"非 platform_admin 仅可访问自己工厂的 outlier "
                f"(当前工厂 {jwt_factory_id!r})"
            ),
        )


def _outlier_to_json(o) -> Dict[str, Any]:
    """Convert DetectedOutlier → camelCase dict.

    Reviewer R2 critical: include baselineSource + baselineN so the FE
    can render a "基于全网基线" badge when source == 'global'.
    """
    return {
        "anomalyDate": o.anomaly_date.isoformat(),
        "kpiKind": o.kpi_kind,
        "kpiLabel": KPI_LABELS.get(o.kpi_kind, o.kpi_kind),
        "value": float(o.value),
        "q1": float(o.q1),
        "q3": float(o.q3),
        "iqr": float(o.iqr),
        "lowerFence": float(o.lower_fence),
        "upperFence": float(o.upper_fence),
        "deviationX": float(o.deviation_x),
        "severity": o.severity,
        "direction": o.direction,
        "baselineSource": o.baseline_source,
        "baselineN": o.baseline_n,
    }


async def _query_dismissed_this_month(factory_id: str) -> List[Dict[str, Any]]:
    """Query dismissals this calendar month for a factory.

    W0.4 finding 3: outlier_dismissals has RLS FORCE, so set_config MUST
    be inside an explicit conn.transaction() — set_config(..., is_local=true)
    is transaction-scoped; without an explicit txn wrapper asyncpg auto-
    commits the SELECT set_config call, the GUC is wiped, and the next
    SELECT silently returns 0 rows.

    Returns a list of camelCase dicts ready for JSON serialization.
    """
    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        return []

    async with pool.acquire() as conn:
        # W0.4 finding 3: GUC inside transaction
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            rows = await conn.fetch(
                """
                SELECT id, anomaly_date, kpi_kind, dismissed_by, dismissed_at,
                       snapshot_value, snapshot_q1, snapshot_q3,
                       snapshot_baseline_source
                  FROM outlier_dismissals
                 WHERE factory_id = $1
                   AND dismissed_at >= date_trunc('month', NOW())
                 ORDER BY dismissed_at DESC
                """,
                factory_id,
            )

    return [
        {
            "id": int(r["id"]),
            "anomalyDate": r["anomaly_date"].isoformat(),
            "kpiKind": r["kpi_kind"],
            "kpiLabel": KPI_LABELS.get(r["kpi_kind"], r["kpi_kind"]),
            "dismissedBy": r["dismissed_by"],
            "dismissedAt": r["dismissed_at"].isoformat(),
            "snapshotValue": (
                float(r["snapshot_value"]) if r["snapshot_value"] is not None else None
            ),
            "snapshotQ1": (
                float(r["snapshot_q1"]) if r["snapshot_q1"] is not None else None
            ),
            "snapshotQ3": (
                float(r["snapshot_q3"]) if r["snapshot_q3"] is not None else None
            ),
            "snapshotBaselineSource": r["snapshot_baseline_source"],
        }
        for r in rows
    ]


# ─────────────────────────────────────────────────────
# GET /api/restaurant/outliers
# ─────────────────────────────────────────────────────
@router.get("/outliers")
async def get_outliers(
    request: Request,
    factoryId: str = Query(..., description="Factory ID"),
    windowDays: int = Query(
        DEFAULT_WINDOW_DAYS,
        ge=1,
        le=365,
        description="Rolling window in days (1-365, default 30)",
    ),
) -> Dict[str, Any]:
    """List pending outliers for a factory + dismissed-this-month log.

    Auth: admin-tier (factory_super_admin / permission_admin / platform_admin).
    Cross-factory: non-platform_admin can only read their own factory.
    Cache: 5-minute TTL keyed by factory_id (single-worker assumption).

    Returns:
        {
            factoryId, windowDays, cachedAt,
            summary: {totalAnomalies, dismissedThisMonth, insufficientKpis},
            outliers: [...],   # each item has baselineSource + baselineN (R2)
            dismissed: [...],
        }
    """
    require_admin(request, action_name="餐饮 outlier 查询")

    # Validate factoryId BEFORE cross-factory check so that obviously-bad
    # input (e.g. > 50 chars) returns 400 rather than 403.
    if not factoryId or not factoryId.strip():
        raise HTTPException(status_code=400, detail="factoryId 不能为空")
    factoryId = factoryId.strip()
    if len(factoryId) > 50:
        raise HTTPException(
            status_code=400,
            detail=f"factoryId 长度不能超过 50 (收到 {len(factoryId)})",
        )

    _validate_factory_access(request, factoryId)

    # Cache check (5-minute TTL)
    now_ts = time.monotonic()
    cache_key = f"{factoryId}:{windowDays}"
    cached = _cache.get(cache_key)
    if cached:
        cached_ts, cached_body = cached
        if now_ts - cached_ts < _CACHE_TTL_S:
            return cached_body

    # Detect outliers
    try:
        outliers, insufficient = await _service.detect_totals(
            factoryId, window_days=windowDays,
        )
    except RuntimeError as exc:
        # OutlierService raises RuntimeError when SmartBI pool is unavailable
        raise HTTPException(status_code=503, detail=f"数据库连接失败: {exc}")
    except Exception:
        logger.exception("[outlier] detect failed for %s", factoryId)
        raise HTTPException(status_code=500, detail="outlier 检测内部错误")

    # Query dismissed this calendar month
    try:
        dismissed = await _query_dismissed_this_month(factoryId)
    except Exception:
        logger.exception("[outlier] dismiss query failed for %s", factoryId)
        raise HTTPException(status_code=500, detail="dismiss 查询内部错误")
    dismissed_keys = {(d["anomalyDate"], d["kpiKind"]) for d in dismissed}

    # Filter pending = outliers not in dismissed set
    pending = [
        o
        for o in outliers
        if (o.anomaly_date.isoformat(), o.kpi_kind) not in dismissed_keys
    ]

    body: Dict[str, Any] = {
        "factoryId": factoryId,
        "windowDays": windowDays,
        "cachedAt": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "totalAnomalies": len(pending),
            "dismissedThisMonth": len(dismissed),
            "insufficientKpis": insufficient,
        },
        "outliers": [_outlier_to_json(o) for o in pending],
        "dismissed": dismissed,
    }
    _cache[cache_key] = (now_ts, body)
    return body


# ─────────────────────────────────────────────────────
# POST /api/restaurant/outliers/dismiss
# ─────────────────────────────────────────────────────
@router.post("/outliers/dismiss", status_code=201)
async def dismiss_outlier(
    request: Request,
    body: Dict[str, Any] = Body(...),
) -> Dict[str, Any]:
    """Mark an outlier as 已确认 (non-anomaly) for the calendar month.

    Auth: admin-tier (factory_super_admin / permission_admin / platform_admin).
    Cross-factory: non-platform_admin can only dismiss own factory.
    Validation:
      - factoryId: non-empty + ≤ 50 chars
      - anomalyDate: required (ISO date string)
      - kpiKind: must be in DEFAULT_KPI_KINDS
      - snapshotBaselineSource: must be 'self' or 'global'
      - snapshotValue / snapshotQ1 / snapshotQ3: all required

    W0.4 finding 3: RLS GUC set_config MUST be inside conn.transaction().
    UNIQUE (factory_id, anomaly_date, kpi_kind) → 409.
    Cache invalidation triggered on success (wipes all factory_id:* keys).
    """
    require_admin(request, action_name="餐饮 outlier dismiss")

    # Validate factoryId
    factory_id = (body.get("factoryId") or "").strip()
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")
    if len(factory_id) > 50:
        raise HTTPException(
            status_code=400,
            detail=f"factoryId 长度不能超过 50 (收到 {len(factory_id)})",
        )
    _validate_factory_access(request, factory_id)

    # Validate anomalyDate
    anomaly_date = body.get("anomalyDate")
    if not anomaly_date:
        raise HTTPException(status_code=400, detail="anomalyDate 不能为空")

    # Validate ISO format upfront — convert future 500 to 400
    from datetime import date as _date
    try:
        _date.fromisoformat(anomaly_date)
    except (TypeError, ValueError):
        raise HTTPException(
            status_code=400,
            detail=f"anomalyDate 格式无效（需 YYYY-MM-DD）: {anomaly_date!r}"
        )

    # Validate kpiKind
    kpi_kind = body.get("kpiKind")
    if kpi_kind not in DEFAULT_KPI_KINDS:
        raise HTTPException(
            status_code=400,
            detail=f"无效 kpiKind: {kpi_kind!r} (必须是 {DEFAULT_KPI_KINDS})",
        )

    # Validate baseline source
    baseline_source = body.get("snapshotBaselineSource")
    if baseline_source not in _VALID_BASELINE_SOURCES:
        raise HTTPException(
            status_code=400,
            detail=(
                f"无效 snapshotBaselineSource: {baseline_source!r} "
                f"(必须是 'self' 或 'global')"
            ),
        )

    # Validate snapshot values
    snapshot_value = body.get("snapshotValue")
    snapshot_q1 = body.get("snapshotQ1")
    snapshot_q3 = body.get("snapshotQ3")
    if snapshot_value is None or snapshot_q1 is None or snapshot_q3 is None:
        raise HTTPException(
            status_code=400,
            detail="snapshotValue / snapshotQ1 / snapshotQ3 必填",
        )

    # Resolve dismissed_by from JWT state (fall back to unknown_admin)
    dismissed_by = (
        getattr(request.state, "username", None)
        or getattr(request.state, "user_name", None)
        or "unknown_admin"
    )

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接失败")

    try:
        async with pool.acquire() as conn:
            # W0.4 finding 3: GUC inside transaction
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                row = await conn.fetchrow(
                    """
                    INSERT INTO outlier_dismissals
                        (factory_id, anomaly_date, kpi_kind, dismissed_by,
                         snapshot_value, snapshot_q1, snapshot_q3,
                         snapshot_baseline_source)
                    VALUES ($1, $2::date, $3, $4, $5, $6, $7, $8)
                    RETURNING id, dismissed_at
                    """,
                    factory_id,
                    anomaly_date,
                    kpi_kind,
                    dismissed_by,
                    snapshot_value,
                    snapshot_q1,
                    snapshot_q3,
                    baseline_source,
                )
    except asyncpg.exceptions.UniqueViolationError:
        raise HTTPException(
            status_code=409,
            detail="该异常已被标记 ✓ 非异常",
        )
    except HTTPException:
        raise
    except Exception:
        logger.exception("[outlier] dismiss insert failed for %s", factory_id)
        raise HTTPException(status_code=500, detail="dismiss 内部错误")

    _invalidate_cache(factory_id)

    return {
        "id": int(row["id"]),
        "factoryId": factory_id,
        "anomalyDate": anomaly_date,
        "kpiKind": kpi_kind,
        "dismissedBy": dismissed_by,
        "dismissedAt": row["dismissed_at"].isoformat(),
    }


# ─────────────────────────────────────────────────────
# DELETE /api/restaurant/outliers/dismiss/{dismissal_id}
# ─────────────────────────────────────────────────────
@router.delete("/outliers/dismiss/{dismissal_id}", status_code=204)
async def undismiss_outlier(
    request: Request,
    dismissal_id: int = Path(..., ge=1),
):
    """Restore a previously dismissed outlier (un-dismiss).

    Auth: admin-tier.
    RLS: SELECT under jwt factory_id GUC; if row not visible → 404
    (covers both not-exist AND cross-factory cases).

    Phase B-1 first version limitation: platform_admin uses own jwt
    factory_id GUC for the lookup (B-N backlog: add ?factoryId=X param
    for cross-factory delete).

    W0.4 finding 3: RLS GUC + transaction wrapper enforced.
    """
    require_admin(request, action_name="餐饮 outlier undismiss")

    jwt_factory_id = getattr(request.state, "factory_id", None) or ""
    role = getattr(request.state, "role", None)

    # platform_admin (B-1): also constrained to own jwt_factory_id for now.
    # B-N backlog: add ?factoryId param.
    if role == "platform_admin":
        effective_fid = jwt_factory_id or "NONE"
    else:
        effective_fid = jwt_factory_id

    from smartbi.config import get_pg_pool

    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接失败")

    async with pool.acquire() as conn:
        # W0.4 finding 3: GUC inside transaction
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", effective_fid
            )
            row = await conn.fetchrow(
                "SELECT factory_id FROM outlier_dismissals WHERE id = $1",
                dismissal_id,
            )
            if row is None:
                raise HTTPException(
                    status_code=404,
                    detail="dismissal 记录不存在或无权访问",
                )

            await conn.execute(
                "DELETE FROM outlier_dismissals WHERE id = $1",
                dismissal_id,
            )

    _invalidate_cache(row["factory_id"])
    return None
