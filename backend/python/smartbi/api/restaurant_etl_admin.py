"""Admin ETL trigger + status query endpoints (餐饮 Phase A Task 1.4).

Per spec v2 §2.1 (restaurant-phase-a-plan-2026-04-28.md):

  POST /trigger       — admin-scoped manual ETL trigger
  GET  /status        — per-factory ETL status (row counts + recent failures)
  GET  /all-status    — platform_admin only, all RESTAURANT factories summary

Auth
----
All three endpoints require admin-tier access via ``require_admin`` from
``smartbi.canonical.provenance._admin_auth``. ``/all-status`` additionally
requires ``platform_admin`` role.

In-memory job tracking
-----------------------
``_running_jobs`` is a module-level dict (Phase A simplification).  In
production this would be backed by Redis so all workers see the same state.
Keys are UUID job IDs; values are dicts with keys:
  {factory_id, status, started_at, finished_at, error}

Pool acquisition
-----------------
``_run_job`` acquires pools lazily inside the background task to avoid
importing ``main`` at module load time (which would be a circular import).
It uses ``smartbi.config.get_pg_pool`` for the SmartBI pool and creates a
fresh transient asyncpg pool for the Cretas DB connection.
"""
from __future__ import annotations

import asyncio
import logging
import os
import uuid
from datetime import date, datetime, timezone
from typing import Any, Dict, List, Optional

import httpx
from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel

from smartbi.canonical.provenance._admin_auth import require_admin, require_factory_scope

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Phase E (Sprint 12) — cache purge after ETL backfill
# ---------------------------------------------------------------------------
# Sister chat sprint12-cache-fix (PR #286, merged 2026-05-29) ships
# POST /api/admin/cache/purge with scope=INDICATOR contract. After bulk ETL
# completes successfully for a factory, we MUST invalidate the per-factory
# semantic_cache rows so the Composite Tool's next invocation sees the new
# REVENUE/COST data instead of stale "(缓存结果)" payloads.
#
# Contract (docs/sprint12-cache-fix/PHASE-B-cache-purge-readme.md):
#   POST {base}/api/admin/cache/purge?scope=INDICATOR&factoryId=...&reason=...
#   Authorization: Bearer {ADMIN_JWT}
#
# Failures here log a warning but do NOT mark the ETL job failed — purge is
# post-hoc cleanup, must not mask the underlying ETL success that operators
# need to see in the bulk endpoint response.
CACHE_PURGE_TIMEOUT_S = 10.0
JAVA_API_BASE_URL_ENV = "JAVA_API_BASE_URL"
ETL_ADMIN_JWT_ENV = "ETL_ADMIN_JWT"
DEFAULT_JAVA_API_BASE_URL = "http://localhost:10010"

router = APIRouter()

# ---------------------------------------------------------------------------
# In-memory job registry (Phase A; replace with Redis for multi-worker prod)
# ---------------------------------------------------------------------------
_running_jobs: Dict[str, Dict[str, Any]] = {}


# ---------------------------------------------------------------------------
# Request / response models
# ---------------------------------------------------------------------------

class TriggerRequest(BaseModel):
    factoryId: str


class FinanceEtlTriggerRequest(BaseModel):
    """Sprint 11.5 Phase D — finance ETL trigger payload.

    Both startDate / endDate optional — defaults to last 90 days when omitted
    (per restaurant_finance_etl.DEFAULT_BACKFILL_DAYS).
    """
    factoryId: str
    startDate: Optional[date] = None
    endDate: Optional[date] = None


class FinanceEtlBulkTriggerRequest(BaseModel):
    """Sprint 12 Phase D — bulk multi-factory finance ETL trigger payload.

    When ``factoryIds`` is None / empty, defaults to
    ``RESTAURANT_FACTORY_BACKFILL_LIST`` (F001 + RES_3101_009 + R_GML_DEMO +
    R_XMX_CHAIN per dispatch Q2). F006 excluded — no POS source data.
    """
    factoryIds: Optional[List[str]] = None
    startDate: Optional[date] = None
    endDate: Optional[date] = None


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

async def _enqueue_job(job_id: str, factory_id: str) -> None:
    """Spawn the ETL background task.

    The job entry in _running_jobs is pre-created by trigger_etl before this
    is called, so patching this as a no-op in tests still leaves the job
    visible in _running_jobs with status='queued'.

    Stores the Task in _running_jobs[job_id]["_task"] to keep a strong ref —
    asyncio.create_task() returns a Task that the GC may collect mid-run if
    no reference is held, producing "Task was destroyed but it is pending!"
    warnings under load.
    """
    task = asyncio.create_task(_run_job(job_id, factory_id))
    if job_id in _running_jobs:
        _running_jobs[job_id]["_task"] = task


async def _run_job(job_id: str, factory_id: str) -> None:
    """Execute run_full_etl_with_retry in the background.

    Pool acquisition is deferred to inside this coroutine to avoid importing
    main.py at module load time (circular import).  Both pools are app-lifetime
    singletons from smartbi.config (get_pg_pool / get_cretas_pool).
    """
    _running_jobs[job_id]["status"] = "running"
    _running_jobs[job_id]["started_at"] = datetime.now(timezone.utc).isoformat()

    try:
        from smartbi.config import get_pg_pool, get_cretas_pool
        from smartbi.gold.restaurant_ops_etl import run_full_etl_with_retry

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            raise RuntimeError("SmartBI pool unavailable — check POSTGRES_URL setting")

        cretas_pool = await get_cretas_pool()
        if cretas_pool is None:
            raise RuntimeError("Cretas pool unavailable — check FOOD_KB_DB_URL / food_kb_postgres_* settings")

        await run_full_etl_with_retry(cretas_pool, smartbi_pool, factory_id)

        _running_jobs[job_id]["status"] = "success"
        logger.info(f"[etl-admin] job {job_id} factory={factory_id} completed")
    except Exception as exc:
        _running_jobs[job_id]["status"] = "error"
        _running_jobs[job_id]["error"] = str(exc)
        logger.warning(f"[etl-admin] job {job_id} factory={factory_id} failed: {exc}")
    finally:
        _running_jobs[job_id]["finished_at"] = datetime.now(timezone.utc).isoformat()


async def _enqueue_finance_job(
    job_id: str,
    factory_id: str,
    start_date: Optional[date],
    end_date: Optional[date],
) -> None:
    """Spawn the finance ETL background task (Sprint 11.5 Phase D)."""
    task = asyncio.create_task(
        _run_finance_job(job_id, factory_id, start_date, end_date)
    )
    if job_id in _running_jobs:
        _running_jobs[job_id]["_task"] = task


async def _run_finance_job(
    job_id: str,
    factory_id: str,
    start_date: Optional[date],
    end_date: Optional[date],
) -> None:
    """Execute run_full_finance_etl_with_retry in the background.

    Sprint 11.5 Phase D — populates smart_bi_finance_data REVENUE + COST
    rows from POS / wastage / recipe-COGS sources.
    """
    _running_jobs[job_id]["status"] = "running"
    _running_jobs[job_id]["started_at"] = datetime.now(timezone.utc).isoformat()

    try:
        from smartbi.config import get_pg_pool, get_cretas_pool
        from smartbi.gold.restaurant_finance_etl import (
            run_full_finance_etl_with_retry,
        )

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            raise RuntimeError("SmartBI pool unavailable — check POSTGRES_URL setting")

        cretas_pool = await get_cretas_pool()
        if cretas_pool is None:
            raise RuntimeError(
                "Cretas pool unavailable — check FOOD_KB_DB_URL setting"
            )

        stats = await run_full_finance_etl_with_retry(
            cretas_pool, smartbi_pool, factory_id, start_date, end_date,
        )

        _running_jobs[job_id]["status"] = "success"
        _running_jobs[job_id]["stats"] = {
            "revenueUpserted": stats.revenue_upserted,
            "costWastageUpserted": stats.cost_wastage_upserted,
            "costPosRecipeUpserted": stats.cost_pos_recipe_upserted,
            "posDishResolved": stats.pos_dish_resolved,
            "posDishUnresolved": stats.pos_dish_unresolved,
            "errors": stats.errors,
        }
        logger.info(
            f"[finance-etl-admin] job {job_id} factory={factory_id} completed: {stats}"
        )
    except Exception as exc:
        _running_jobs[job_id]["status"] = "error"
        _running_jobs[job_id]["error"] = str(exc)
        logger.warning(
            f"[finance-etl-admin] job {job_id} factory={factory_id} failed: {exc}"
        )
    finally:
        _running_jobs[job_id]["finished_at"] = datetime.now(timezone.utc).isoformat()


async def _row_count(pool, table: str, factory_id: str) -> int:
    """Return COUNT(*) for *table* WHERE factory_id = factory_id.

    Some tables (agg_restaurant_daily_ops, fact_pos_item) have FORCE RLS on
    `app.factory_id` GUC — without setting it, the query silently returns 0
    even when rows exist (real-window verify caught this on F002 trigger).
    Set GUC inside conn.transaction() so RLS sees the right factory.

    Returns -1 on any error (missing table, etc.).
    """
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                return await conn.fetchval(
                    f"SELECT COUNT(*) FROM {table} WHERE factory_id = $1",
                    factory_id,
                )
    except Exception as exc:
        logger.debug(f"[etl-admin] row_count({table}, {factory_id}) failed: {exc}")
        return -1


async def _last_success_run(pool, factory_id: str) -> Optional[str]:
    """Return ISO-8601 string of MAX(computed_at) from agg_restaurant_daily_ops.

    Uses ``computed_at`` (not ``updated_at``) — Task 1.3 naming.
    agg_restaurant_daily_ops has FORCE RLS — must set app.factory_id GUC
    inside conn.transaction() else silently returns NULL even on success.
    """
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)", factory_id
                )
                val = await conn.fetchval(
                    "SELECT MAX(computed_at) FROM agg_restaurant_daily_ops"
                    " WHERE factory_id = $1",
                    factory_id,
                )
        if val is None:
            return None
        # asyncpg returns datetime objects for TIMESTAMPTZ columns
        if hasattr(val, "isoformat"):
            return val.isoformat()
        return str(val)
    except Exception as exc:
        logger.debug(f"[etl-admin] last_success_run({factory_id}) failed: {exc}")
        return None


async def _recent_failures(pool, factory_id: str) -> list:
    """Return up to 10 failure records from restaurant_etl_failures (last 7 days).

    restaurant_etl_failures (V20260501_04) has no RLS — direct query is fine.
    Returns [] on any error (table not yet created in test environments, etc.).
    """
    try:
        async with pool.acquire() as conn:
            # Column names match V20260501_04 migration:
            # error_msg / run_at / attempt (not error_message/failed_at/attempt_number).
            rows = await conn.fetch(
                """
                SELECT factory_id, error_msg, error_class, run_at,
                       attempt, status, duration_ms
                  FROM restaurant_etl_failures
                 WHERE factory_id = $1
                   AND run_at >= NOW() - INTERVAL '7 days'
                 ORDER BY run_at DESC
                 LIMIT 10
                """,
                factory_id,
            )
        return [
            {
                "factoryId": r["factory_id"],
                "errorMessage": r["error_msg"],
                "errorClass": r["error_class"],
                "runAt": r["run_at"].isoformat() if r["run_at"] is not None else None,
                "attempt": r["attempt"],
                "status": r["status"],  # 'failed' | 'retrying' | 'failed_final'
                "durationMs": r["duration_ms"],
            }
            for r in rows
        ]
    except Exception as exc:
        logger.debug(f"[etl-admin] recent_failures({factory_id}) failed: {exc}")
        return []


def _derive_last_status(factory_id: str, last_success: Optional[str]) -> str:
    """Derive a human-readable lastStatus string.

    Priority: if there is an active job in _running_jobs for this factory,
    return its current status.  Otherwise fall back on whether we have a
    recorded success run.
    """
    # Search in-progress jobs for this factory (most recent wins)
    active = [
        v for v in _running_jobs.values()
        if v["factory_id"] == factory_id and v["status"] in ("running", "queued")
    ]
    if active:
        return active[-1]["status"]
    if last_success:
        return "success"
    return "never_ran"


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/trigger")
async def trigger_etl(request: Request, body: TriggerRequest):
    """Manually trigger the restaurant ETL pipeline for one factory.

    Requires admin-tier role (factory_super_admin / platform_admin /
    permission_admin).  Returns a jobId and initial status so the caller can
    poll /status.
    """
    require_admin(request, action_name="餐饮 ETL 触发")

    factory_id = body.factoryId.strip()
    require_factory_scope(request, factory_id)
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")

    job_id = str(uuid.uuid4())

    # Register the job before spawning so /status can see it immediately
    # and so the test patch of _enqueue_job (no-op) still leaves the job visible.
    _running_jobs[job_id] = {
        "factory_id": factory_id,
        "status": "queued",
        "started_at": None,
        "finished_at": None,
        "error": None,
    }

    await _enqueue_job(job_id, factory_id)

    # Estimate ETA: full ETL typically takes 30-120 s depending on row count
    eta_seconds = 60

    return {
        "jobId": job_id,
        "status": _running_jobs[job_id]["status"],
        "factoryId": factory_id,
        "etaSeconds": eta_seconds,
    }


@router.get("/status")
async def status_etl(
    request: Request,
    factoryId: str = Query(..., description="工厂 ID"),
):
    """Query ETL status for a single factory.

    Returns row counts in fact_pos_item / agg_restaurant_daily_ops /
    dim_ingredient and the 10 most-recent failure records.

    Row counts fall back to -1 if a table is inaccessible (missing in test
    environment, or RLS-blocked without tenant GUC set).
    lastSuccessRun may be None if RLS is not satisfied (GUC unset) — the
    FE renders "—" in that case.
    """
    require_admin(request, action_name="餐饮 ETL 状态查询")

    factory_id = factoryId.strip()
    require_factory_scope(request, factory_id)
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()

    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接不可用")

    # Parallel queries — each wrapped in its own try/except inside the helper
    last_success, fact_count, agg_count, dim_count, failures = await asyncio.gather(
        _last_success_run(pool, factory_id),
        _row_count(pool, "fact_pos_item", factory_id),
        _row_count(pool, "agg_restaurant_daily_ops", factory_id),
        _row_count(pool, "dim_ingredient", factory_id),
        _recent_failures(pool, factory_id),
        return_exceptions=False,
    )

    return {
        "factoryId": factory_id,
        "lastSuccessRun": last_success,
        "lastStatus": _derive_last_status(factory_id, last_success),
        "rowCounts": {
            "factPosItem": fact_count,
            "aggRestaurantDailyOps": agg_count,
            "dimIngredient": dim_count,
        },
        "recentFailures": failures,
    }


@router.get("/all-status")
async def all_status_etl(request: Request):
    """Platform-admin view: last computed_at for every RESTAURANT factory.

    Requires platform_admin role (not just generic admin-tier).  Returns a
    list of {factoryId, lastSuccessRun} derived from agg_restaurant_daily_ops.

    Phase A simplification: factory name not yet joined (factoryName = null
    until A-2 adds the restaurant_chain_catalog lookup — see migration
    V20260511_01__t6_6_etl_chain_catalog.sql for chain metadata schema).
    """
    # First gate via generic admin check, then tighten to platform_admin
    require_admin(request, action_name="餐饮 ETL 全工厂状态")

    role = getattr(request.state, "role", None)
    if role != "platform_admin":
        raise HTTPException(
            status_code=403,
            detail="all-status 需要 platform_admin 权限",
        )

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()

    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接不可用")

    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT factory_id, MAX(computed_at) AS last_computed
                FROM agg_restaurant_daily_ops
                GROUP BY factory_id
                ORDER BY factory_id
                """
            )
        factories = [
            {
                "factoryId": r["factory_id"],
                "factoryName": None,  # Phase A: populated in A-2
                "lastSuccessRun": (
                    r["last_computed"].isoformat()
                    if r["last_computed"] and hasattr(r["last_computed"], "isoformat")
                    else (str(r["last_computed"]) if r["last_computed"] else None)
                ),
            }
            for r in rows
        ]
    except Exception as exc:
        logger.warning(f"[etl-admin] all-status query failed: {exc}")
        factories = []

    return {"factories": factories}


# ---------------------------------------------------------------------------
# Sprint 11.5 Phase D — finance ETL trigger
# ---------------------------------------------------------------------------

@router.post("/finance-etl/trigger")
async def trigger_finance_etl(request: Request, body: FinanceEtlTriggerRequest):
    """Trigger finance ETL pipeline — populate smart_bi_finance_data.

    Sprint 11.5 Phase D — populate REVENUE + COST rows from POS / wastage /
    POS×recipe COGS sources (per spec
    docs/superpowers/specs/2026-05-23-sprint11.5-etl-design.md §3.1).

    Defaults to last 90 days when startDate / endDate omitted.

    Requires admin-tier role (factory_super_admin / platform_admin /
    permission_admin).

    Returns jobId for /status polling.
    """
    require_admin(request, action_name="餐饮 Finance ETL 触发")

    factory_id = body.factoryId.strip()
    require_factory_scope(request, factory_id)
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")

    if (
        body.startDate is not None
        and body.endDate is not None
        and body.endDate < body.startDate
    ):
        raise HTTPException(
            status_code=400,
            detail=f"endDate ({body.endDate}) 早于 startDate ({body.startDate})",
        )

    job_id = str(uuid.uuid4())

    _running_jobs[job_id] = {
        "factory_id": factory_id,
        "status": "queued",
        "started_at": None,
        "finished_at": None,
        "error": None,
        "kind": "finance",
        "startDate": body.startDate.isoformat() if body.startDate else None,
        "endDate": body.endDate.isoformat() if body.endDate else None,
    }

    await _enqueue_finance_job(job_id, factory_id, body.startDate, body.endDate)

    # Estimate ETA: finance ETL is smaller than full ops ETL — ~20-60s
    eta_seconds = 30

    return {
        "jobId": job_id,
        "status": _running_jobs[job_id]["status"],
        "factoryId": factory_id,
        "startDate": body.startDate.isoformat() if body.startDate else None,
        "endDate": body.endDate.isoformat() if body.endDate else None,
        "etaSeconds": eta_seconds,
    }


# ---------------------------------------------------------------------------
# Sprint 12 Phase D — bulk multi-factory finance ETL trigger
# ---------------------------------------------------------------------------

async def _purge_indicator_cache_for_factory(
    factory_id: str, reason: str
) -> Dict[str, Any]:
    """Call Java admin cache purge after ETL backfill (Sprint 12 Phase E).

    Posts to ``{JAVA_API_BASE_URL}/api/admin/cache/purge`` with
    ``scope=INDICATOR`` per sister PR #286 contract. Uses ``ETL_ADMIN_JWT``
    env var for auth (service-account JWT with platform_admin role).

    Returns ``{success, factoryId, statusCode?, error?}`` dict for inclusion
    in bulk job stats. Errors are logged at WARN and returned in the dict —
    they MUST NOT raise, because cache purge is a post-hoc convenience that
    must not mask underlying ETL success.

    When ``ETL_ADMIN_JWT`` is unset (e.g. local dev runs, test env without
    cron config), the call is skipped with a WARN log and ``success=False``
    returned — operator can manually curl the endpoint later.
    """
    base_url = os.environ.get(JAVA_API_BASE_URL_ENV, DEFAULT_JAVA_API_BASE_URL)
    admin_jwt = os.environ.get(ETL_ADMIN_JWT_ENV)

    if not admin_jwt:
        logger.warning(
            "[cache-purge] %s env not set — skipping cache purge for factory=%s "
            "(operator can run: curl -X POST '%s/api/admin/cache/purge"
            "?scope=INDICATOR&factoryId=%s&reason=%s' -H 'Authorization: Bearer ...')",
            ETL_ADMIN_JWT_ENV, factory_id, base_url, factory_id, reason,
        )
        return {
            "success": False,
            "factoryId": factory_id,
            "error": f"{ETL_ADMIN_JWT_ENV} env not configured",
        }

    url = f"{base_url.rstrip('/')}/api/admin/cache/purge"
    params = {
        "scope": "INDICATOR",
        "factoryId": factory_id,
        "reason": reason,
    }
    headers = {"Authorization": f"Bearer {admin_jwt}"}

    try:
        async with httpx.AsyncClient(timeout=CACHE_PURGE_TIMEOUT_S) as client:
            resp = await client.post(url, params=params, headers=headers)
    except Exception as exc:
        logger.warning(
            "[cache-purge] factory=%s reason=%s network error: %s",
            factory_id, reason, exc,
        )
        return {
            "success": False,
            "factoryId": factory_id,
            "error": f"network: {exc}",
        }

    if resp.status_code == 200:
        logger.info(
            "[cache-purge] factory=%s reason=%s ok status=%d",
            factory_id, reason, resp.status_code,
        )
        return {
            "success": True,
            "factoryId": factory_id,
            "statusCode": resp.status_code,
        }

    logger.warning(
        "[cache-purge] factory=%s reason=%s failed status=%d body=%s",
        factory_id, reason, resp.status_code, resp.text[:200],
    )
    return {
        "success": False,
        "factoryId": factory_id,
        "statusCode": resp.status_code,
        "error": f"HTTP {resp.status_code}: {resp.text[:200]}",
    }


async def _enqueue_finance_bulk_job(
    job_id: str,
    factory_ids: List[str],
    start_date: Optional[date],
    end_date: Optional[date],
) -> None:
    """Spawn the bulk finance ETL background task (Sprint 12 Phase D)."""
    task = asyncio.create_task(
        _run_finance_bulk_job(job_id, factory_ids, start_date, end_date)
    )
    if job_id in _running_jobs:
        _running_jobs[job_id]["_task"] = task


async def _run_finance_bulk_job(
    job_id: str,
    factory_ids: List[str],
    start_date: Optional[date],
    end_date: Optional[date],
) -> None:
    """Execute run_full_finance_etl_for_factories in the background.

    Per-factory failures are isolated by the orchestrator — job status reports
    aggregate success/failure counts. Failed-factory list is included in
    response so admin can re-trigger selectively.
    """
    _running_jobs[job_id]["status"] = "running"
    _running_jobs[job_id]["started_at"] = datetime.now(timezone.utc).isoformat()

    try:
        from smartbi.config import get_pg_pool, get_cretas_pool
        from smartbi.gold.restaurant_finance_etl import (
            run_full_finance_etl_for_factories,
        )

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            raise RuntimeError("SmartBI pool unavailable — check POSTGRES_URL setting")

        cretas_pool = await get_cretas_pool()
        if cretas_pool is None:
            raise RuntimeError(
                "Cretas pool unavailable — check FOOD_KB_DB_URL setting"
            )

        bulk = await run_full_finance_etl_for_factories(
            cretas_pool, smartbi_pool, factory_ids, start_date, end_date,
        )

        # Mark success when at least one factory succeeded; partial-fail still
        # reports 'success' so cron-style callers don't retry the whole batch.
        # The failed list is preserved in stats for operator inspection.
        _running_jobs[job_id]["status"] = "success" if bulk.succeeded else "error"
        _running_jobs[job_id]["stats"] = {
            "succeeded": bulk.succeeded,
            "failed": bulk.failed,
            "totalRevenueUpserted": bulk.total_revenue_upserted,
            "totalCostWastageUpserted": bulk.total_cost_wastage_upserted,
            "totalCostPosRecipeUpserted": bulk.total_cost_pos_recipe_upserted,
            "perFactory": {
                fid: {
                    "revenueUpserted": s.revenue_upserted,
                    "costWastageUpserted": s.cost_wastage_upserted,
                    "costPosRecipeUpserted": s.cost_pos_recipe_upserted,
                    "posDishResolved": s.pos_dish_resolved,
                    "posDishUnresolved": s.pos_dish_unresolved,
                    "errors": s.errors,
                }
                for fid, s in bulk.per_factory.items()
            },
        }
        logger.info(
            "[finance-etl-bulk] job %s completed: succeeded=%d failed=%d",
            job_id, len(bulk.succeeded), len(bulk.failed),
        )

        # Phase E (Sprint 12) — purge stale indicator cache for each factory
        # that backfilled successfully, so the Composite Tool's next call sees
        # the fresh REVENUE/COST data instead of a cached "(缓存结果)" payload.
        # Only purge succeeded factories — failed ones still have old data, no
        # point invalidating cache they didn't refresh. Per-factory purge
        # failures are captured in stats but never flip the ETL job status.
        purge_reason = f"etl-backfill-bulk-{job_id[:8]}"
        purge_results = []
        for fid in bulk.succeeded:
            purge_results.append(
                await _purge_indicator_cache_for_factory(fid, purge_reason)
            )
        _running_jobs[job_id]["cachePurge"] = purge_results
        purged_ok = sum(1 for r in purge_results if r.get("success"))
        logger.info(
            "[finance-etl-bulk] job %s cache purge: %d/%d factories ok",
            job_id, purged_ok, len(purge_results),
        )
    except Exception as exc:
        _running_jobs[job_id]["status"] = "error"
        _running_jobs[job_id]["error"] = str(exc)
        logger.warning(
            "[finance-etl-bulk] job %s failed: %s", job_id, exc,
        )
    finally:
        _running_jobs[job_id]["finished_at"] = datetime.now(timezone.utc).isoformat()


@router.post("/finance-etl/trigger-bulk")
async def trigger_finance_etl_bulk(
    request: Request, body: FinanceEtlBulkTriggerRequest,
):
    """Trigger finance ETL for multiple restaurant factories (Sprint 12 Phase D).

    Operational nightly cron endpoint — accepts ``factoryIds`` list OR omits
    to use the default ``RESTAURANT_FACTORY_BACKFILL_LIST`` (F001 +
    RES_3101_009 + R_GML_DEMO + R_XMX_CHAIN).

    Each factory runs through the retry wrapper; per-factory failures are
    isolated. Response includes aggregate stats + per-factory results.

    Defaults to last 90 days when startDate / endDate omitted. Operational
    cron should pass yesterday's date for both bounds.

    Requires admin-tier role (factory_super_admin / platform_admin /
    permission_admin).
    """
    require_admin(request, action_name="餐饮 Finance ETL 批量触发")

    if (
        body.startDate is not None
        and body.endDate is not None
        and body.endDate < body.startDate
    ):
        raise HTTPException(
            status_code=400,
            detail=f"endDate ({body.endDate}) 早于 startDate ({body.startDate})",
        )

    # Resolve factoryIds: None / empty list → default backfill list
    from smartbi.gold.restaurant_finance_etl import RESTAURANT_FACTORY_BACKFILL_LIST
    if body.factoryIds:
        factory_ids = [fid.strip() for fid in body.factoryIds if fid and fid.strip()]
        if not factory_ids:
            raise HTTPException(
                status_code=400,
                detail="factoryIds 全部为空 — 传 null/[] 走默认列表, 或填具体 ID",
            )
    else:
        factory_ids = list(RESTAURANT_FACTORY_BACKFILL_LIST)

    for fid in factory_ids:
        require_factory_scope(request, fid, field_name="factoryIds")

    job_id = str(uuid.uuid4())

    _running_jobs[job_id] = {
        "factory_id": ",".join(factory_ids),   # display only (multi-factory job)
        "status": "queued",
        "started_at": None,
        "finished_at": None,
        "error": None,
        "kind": "finance_bulk",
        "factoryIds": factory_ids,
        "startDate": body.startDate.isoformat() if body.startDate else None,
        "endDate": body.endDate.isoformat() if body.endDate else None,
    }

    await _enqueue_finance_bulk_job(job_id, factory_ids, body.startDate, body.endDate)

    # ETA scales linearly with factory count — ~30s per factory worst case
    eta_seconds = 30 * len(factory_ids)

    return {
        "jobId": job_id,
        "status": _running_jobs[job_id]["status"],
        "factoryIds": factory_ids,
        "startDate": body.startDate.isoformat() if body.startDate else None,
        "endDate": body.endDate.isoformat() if body.endDate else None,
        "etaSeconds": eta_seconds,
    }
