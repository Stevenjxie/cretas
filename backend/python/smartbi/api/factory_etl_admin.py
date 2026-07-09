"""Admin ETL trigger + status query endpoints for Factory Production Gold.

Mirrors restaurant_etl_admin.py pattern.

Endpoints (all under /api/smartbi/factory/etl prefix registered in main.py):
    POST /trigger   — manually trigger factory production ETL for one factory
    GET  /status    — per-factory ETL status (row counts + recent failures)

Auth:
    Requires admin-tier access via ``require_admin`` from
    ``smartbi.canonical.provenance._admin_auth``.

In-memory job tracking (Phase 1 simplification; replace with Redis for
multi-worker prod).  Keys are UUID job IDs; values are dicts with:
    {factory_id, status, started_at, finished_at, error, stats?}
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, Optional

from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel

from smartbi.canonical.provenance._admin_auth import require_admin, require_factory_scope

logger = logging.getLogger(__name__)

router = APIRouter()

# ---------------------------------------------------------------------------
# In-memory job registry (Phase 1; replace with Redis for multi-worker prod)
# ---------------------------------------------------------------------------
_running_jobs: Dict[str, Dict[str, Any]] = {}


# ---------------------------------------------------------------------------
# Request / response models
# ---------------------------------------------------------------------------

class FactoryEtlTriggerRequest(BaseModel):
    factoryId: str


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

async def _enqueue_job(job_id: str, factory_id: str) -> None:
    """Spawn ETL background task; keep strong ref so GC doesn't collect it."""
    task = asyncio.create_task(_run_job(job_id, factory_id))
    if job_id in _running_jobs:
        _running_jobs[job_id]["_task"] = task


async def _run_job(job_id: str, factory_id: str) -> None:
    """Execute run_factory_etl_with_retry in the background.

    Acquires pools lazily to avoid circular imports from main.py at module
    load time (same technique as restaurant_etl_admin._run_job).
    """
    _running_jobs[job_id]["status"] = "running"
    _running_jobs[job_id]["started_at"] = datetime.now(timezone.utc).isoformat()

    try:
        from smartbi.config import get_cretas_pool, get_pg_pool
        from smartbi.gold.factory_production_etl import run_factory_etl_with_retry

        smartbi_pool = await get_pg_pool()
        if smartbi_pool is None:
            raise RuntimeError("SmartBI pool unavailable — check POSTGRES_URL setting")

        cretas_pool = await get_cretas_pool()
        if cretas_pool is None:
            raise RuntimeError(
                "Cretas pool unavailable — check FOOD_KB_DB_URL / food_kb_postgres_* settings"
            )

        etl_stats = await run_factory_etl_with_retry(cretas_pool, smartbi_pool, factory_id)

        _running_jobs[job_id]["status"] = "success"
        _running_jobs[job_id]["stats"] = {
            "silverUpserted":       etl_stats.silver_upserted,
            "goldProductRows":      etl_stats.gold_product_rows,
            "goldDailyRollupRows":  etl_stats.gold_daily_rollup_rows,
            "errors":               etl_stats.errors,
        }
        logger.info("[factory-etl-admin] job %s factory=%s completed: %s",
                    job_id, factory_id, etl_stats)
    except Exception as exc:
        _running_jobs[job_id]["status"] = "error"
        _running_jobs[job_id]["error"] = str(exc)
        logger.warning("[factory-etl-admin] job %s factory=%s failed: %s",
                       job_id, factory_id, exc)
    finally:
        _running_jobs[job_id]["finished_at"] = datetime.now(timezone.utc).isoformat()


async def _row_count_with_rls(pool: Any, table: str, factory_id: str) -> int:
    """COUNT(*) for *table* WHERE factory_id = factory_id, with RLS GUC set.

    Returns -1 on any error (table missing, RLS block, etc.).
    """
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                return await conn.fetchval(
                    f"SELECT COUNT(*) FROM {table} WHERE factory_id = $1",
                    factory_id,
                )
    except Exception as exc:
        logger.debug("[factory-etl-admin] row_count(%s, %s) failed: %s",
                     table, factory_id, exc)
        return -1


async def _last_gold_run(pool: Any, factory_id: str) -> Optional[str]:
    """Return ISO-8601 string of MAX(updated_at) from agg_factory_batch_daily."""
    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                val = await conn.fetchval(
                    "SELECT MAX(updated_at) FROM agg_factory_batch_daily"
                    " WHERE factory_id = $1",
                    factory_id,
                )
        if val is None:
            return None
        return val.isoformat() if hasattr(val, "isoformat") else str(val)
    except Exception as exc:
        logger.debug("[factory-etl-admin] last_gold_run(%s) failed: %s",
                     factory_id, exc)
        return None


def _derive_last_status(factory_id: str, last_success: Optional[str]) -> str:
    """Derive human-readable status string from in-memory job registry."""
    active = [
        v for v in _running_jobs.values()
        if v["factory_id"] == factory_id and v["status"] in ("running", "queued")
    ]
    if active:
        return active[-1]["status"]
    return "success" if last_success else "never_ran"


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/trigger")
async def trigger_factory_etl(request: Request, body: FactoryEtlTriggerRequest):
    """Manually trigger the factory production ETL pipeline for one factory.

    Requires admin-tier role (factory_super_admin / platform_admin /
    permission_admin).  Returns jobId + initial status; poll /status for result.

    ETL pipeline:
        cretas_db.production_batches (COMPLETED, non-trial)
            → smartbi_db.fact_production_batch (Silver)
            → smartbi_db.agg_factory_batch_daily (Gold)
    """
    require_admin(request, action_name="工厂生产 ETL 触发")

    factory_id = body.factoryId.strip()
    require_factory_scope(request, factory_id)
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")

    job_id = str(uuid.uuid4())

    _running_jobs[job_id] = {
        "factory_id":  factory_id,
        "status":      "queued",
        "started_at":  None,
        "finished_at": None,
        "error":       None,
    }

    await _enqueue_job(job_id, factory_id)

    return {
        "jobId":      job_id,
        "status":     _running_jobs[job_id]["status"],
        "factoryId":  factory_id,
        "etaSeconds": 60,
    }


@router.get("/status")
async def status_factory_etl(
    request: Request,
    factoryId: str = Query(..., description="工厂 ID"),
):
    """Query factory production ETL status for a single factory.

    Returns:
    - lastSuccessRun: ISO timestamp of last Gold agg update (null if never ran)
    - lastStatus: 'running' | 'queued' | 'success' | 'never_ran'
    - rowCounts: Silver/Gold table row counts (RLS-safe; -1 = error/unavailable)
    - recentFailures: from restaurant_etl_failures (shared failure log table)
    """
    require_admin(request, action_name="工厂生产 ETL 状态查询")

    factory_id = factoryId.strip()
    require_factory_scope(request, factory_id)
    if not factory_id:
        raise HTTPException(status_code=400, detail="factoryId 不能为空")

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()
    if pool is None:
        raise HTTPException(status_code=503, detail="数据库连接不可用")

    last_success, silver_count, gold_count = await asyncio.gather(
        _last_gold_run(pool, factory_id),
        _row_count_with_rls(pool, "fact_production_batch", factory_id),
        _row_count_with_rls(pool, "agg_factory_batch_daily", factory_id),
        return_exceptions=False,
    )

    return {
        "factoryId":     factory_id,
        "lastSuccessRun": last_success,
        "lastStatus":    _derive_last_status(factory_id, last_success),
        "rowCounts": {
            "factProductionBatch":  silver_count,
            "aggFactoryBatchDaily": gold_count,
        },
    }
