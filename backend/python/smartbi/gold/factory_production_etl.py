"""Factory Production Gold ETL — cretas_db (Bronze) → smartbi_db (Silver + Gold).

Phase 1 of Factory BI (2026-06-11).

Pipeline:
    cretas_db.production_batches  →  smartbi_db.fact_production_batch  (Silver)
                                  →  smartbi_db.agg_factory_batch_daily (Gold)

Source filter: status = 'COMPLETED' AND (is_trial IS NOT TRUE) AND deleted_at IS NULL
Grain: batch level (production_batches.id)

Call `run_factory_etl_with_retry(cretas_pool, smartbi_pool, factory_id)` from
the admin HTTP endpoint or a nightly cron.

Design notes (mirrors restaurant_ops_etl.py):
- Upsert keyed on (factory_id, source_pk) where source_pk = production_batches.id.
  Every run is fully idempotent — safe to re-run at any time.
- Cross-pool: reads cretas_db via `cretas_pool`, writes smartbi_db via `smartbi_pool`.
  No dblink, no pg_fdw — Python bridges the two databases.
- RLS: every smartbi_db write sets `app.factory_id` GUC inside the transaction so
  FORCE ROW LEVEL SECURITY policies are satisfied.
- UNNEST bulk upsert: builds parallel arrays in Python, passes them as a single
  INSERT … FROM UNNEST(…) — same pattern as restaurant_ops_etl.
- Cost fields left NULL when the source is NULL (honest zero avoidance).
- Gold materialization runs in the same call after Silver sync completes.
- Retry wrapper: 3 attempts at 1m / 5m / 15m intervals; failures persisted to
  restaurant_etl_failures (re-uses existing table — same tenant isolation semantics).
"""
from __future__ import annotations

import asyncio
import logging
import sys
import time
import traceback
from dataclasses import dataclass, field
from typing import Dict, List, Optional

import asyncpg

# ── Path bootstrap (required when called as a standalone script) ──────────────
# Without this, `import smartbi.config` fails when the CWD is not the python
# service root.  Mirror pattern from chart_insight ETL bootstrapper.
import os as _os
_here = _os.path.dirname(_os.path.abspath(__file__))
_service_root = _os.path.dirname(_os.path.dirname(_here))  # .../backend/python
if _service_root not in sys.path:
    sys.path.insert(0, _service_root)

logger = logging.getLogger(__name__)

# ── Batch size for paginated reads from cretas_db ────────────────────────────
_BATCH_SIZE = 500

# ── Retry configuration ───────────────────────────────────────────────────────
_RETRY_BACKOFFS_SEC = [60, 300, 900]  # 1m, 5m, 15m
_MAX_ATTEMPTS = 3


@dataclass
class FactoryEtlStats:
    """Per-stage row counts for observability."""
    silver_upserted: int = 0
    gold_product_rows: int = 0
    gold_daily_rollup_rows: int = 0
    errors: List[str] = field(default_factory=list)


# ── Helpers ───────────────────────────────────────────────────────────────────

async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    """Set the RLS tenant context for this connection's transaction."""
    await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)


def _to_float_or_none(v) -> Optional[float]:
    """Convert a Decimal/numeric DB value to float, preserving None (no zero-fill)."""
    if v is None:
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# ── Stage 1: Silver — sync_fact_production_batch ──────────────────────────────

async def sync_fact_production_batch(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert completed production_batches rows into fact_production_batch (Silver).

    Fetches in one query (no pagination needed for Phase 1 — batch counts are in
    the thousands, not millions).  Returns number of rows upserted.

    Source filter:
        status = 'COMPLETED'
        AND (is_trial IS NOT TRUE)
        AND deleted_at IS NULL
    """
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT
                id,
                batch_number,
                product_type_id,
                product_name,
                planned_quantity,
                actual_quantity,
                good_quantity,
                defect_quantity,
                unit,
                status,
                start_time,
                end_time,
                worker_count,
                work_duration_minutes,
                material_cost,
                labor_cost,
                equipment_cost,
                other_cost,
                total_cost,
                unit_cost,
                yield_rate
            FROM production_batches
            WHERE factory_id   = $1::varchar
              AND status       = 'COMPLETED'
              AND (is_trial    IS NOT TRUE)
              AND deleted_at   IS NULL
            ORDER BY id
            """,
            factory_id,
        )

    if not rows:
        logger.info("[factory-etl] no completed batches for factory=%s", factory_id)
        return 0

    # Build parallel arrays for UNNEST bulk upsert
    source_pks     = [str(r["id"])             for r in rows]
    batch_numbers  = [r["batch_number"]         for r in rows]
    product_ids    = [r["product_type_id"]      for r in rows]
    product_names  = [r["product_name"]         for r in rows]
    planned_qtys   = [_to_float_or_none(r["planned_quantity"])  for r in rows]
    actual_qtys    = [_to_float_or_none(r["actual_quantity"])   for r in rows]
    good_qtys      = [_to_float_or_none(r["good_quantity"])     for r in rows]
    defect_qtys    = [_to_float_or_none(r["defect_quantity"])   for r in rows]
    units          = [r["unit"]                 for r in rows]
    statuses       = [r["status"]               for r in rows]
    start_times    = [r["start_time"]           for r in rows]
    end_times      = [r["end_time"]             for r in rows]
    # stat_date = DATE(end_time) preferred; fall back to DATE(start_time); else NULL
    stat_dates     = [
        r["end_time"].date() if r["end_time"] is not None
        else (r["start_time"].date() if r["start_time"] is not None else None)
        for r in rows
    ]
    worker_counts  = [r["worker_count"]              for r in rows]
    work_minutes   = [r["work_duration_minutes"]     for r in rows]
    # Cost fields: honest null preservation — do NOT coerce None to 0
    material_costs  = [_to_float_or_none(r["material_cost"])   for r in rows]
    labor_costs     = [_to_float_or_none(r["labor_cost"])      for r in rows]
    equipment_costs = [_to_float_or_none(r["equipment_cost"])  for r in rows]
    other_costs     = [_to_float_or_none(r["other_cost"])      for r in rows]
    total_costs     = [_to_float_or_none(r["total_cost"])      for r in rows]
    unit_costs      = [_to_float_or_none(r["unit_cost"])       for r in rows]
    yield_rates     = [_to_float_or_none(r["yield_rate"])      for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            result = await dst.fetch(
                """
                INSERT INTO fact_production_batch (
                    factory_id, source_pk,
                    batch_number, product_type_id, product_name,
                    planned_qty, actual_qty, good_qty, defect_qty, unit,
                    status, start_time, end_time, stat_date,
                    worker_count, work_minutes,
                    material_cost, labor_cost, equipment_cost, other_cost,
                    total_cost, unit_cost, yield_rate
                )
                SELECT
                    $1,   -- factory_id
                    spk, bn, pid, pnm,
                    plq, aq, gq, dq, u,
                    st, stm, etm, sd,
                    wc, wm,
                    mc, lc, ec, oc, tc, uc, yr
                FROM UNNEST(
                    $2::text[],       -- source_pk
                    $3::text[],       -- batch_number
                    $4::text[],       -- product_type_id
                    $5::text[],       -- product_name
                    $6::numeric[],    -- planned_qty
                    $7::numeric[],    -- actual_qty
                    $8::numeric[],    -- good_qty
                    $9::numeric[],    -- defect_qty
                    $10::text[],      -- unit
                    $11::text[],      -- status
                    $12::timestamp[], -- start_time
                    $13::timestamp[], -- end_time
                    $14::date[],      -- stat_date
                    $15::int[],       -- worker_count
                    $16::int[],       -- work_minutes
                    $17::numeric[],   -- material_cost
                    $18::numeric[],   -- labor_cost
                    $19::numeric[],   -- equipment_cost
                    $20::numeric[],   -- other_cost
                    $21::numeric[],   -- total_cost
                    $22::numeric[],   -- unit_cost
                    $23::numeric[]    -- yield_rate
                ) AS t(
                    spk, bn, pid, pnm,
                    plq, aq, gq, dq, u,
                    st, stm, etm, sd,
                    wc, wm,
                    mc, lc, ec, oc, tc, uc, yr
                )
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    batch_number     = EXCLUDED.batch_number,
                    product_type_id  = EXCLUDED.product_type_id,
                    product_name     = EXCLUDED.product_name,
                    planned_qty      = EXCLUDED.planned_qty,
                    actual_qty       = EXCLUDED.actual_qty,
                    good_qty         = EXCLUDED.good_qty,
                    defect_qty       = EXCLUDED.defect_qty,
                    unit             = EXCLUDED.unit,
                    status           = EXCLUDED.status,
                    start_time       = EXCLUDED.start_time,
                    end_time         = EXCLUDED.end_time,
                    stat_date        = EXCLUDED.stat_date,
                    worker_count     = EXCLUDED.worker_count,
                    work_minutes     = EXCLUDED.work_minutes,
                    material_cost    = EXCLUDED.material_cost,
                    labor_cost       = EXCLUDED.labor_cost,
                    equipment_cost   = EXCLUDED.equipment_cost,
                    other_cost       = EXCLUDED.other_cost,
                    total_cost       = EXCLUDED.total_cost,
                    unit_cost        = EXCLUDED.unit_cost,
                    yield_rate       = EXCLUDED.yield_rate,
                    updated_at       = NOW()
                RETURNING id
                """,
                factory_id,
                source_pks, batch_numbers, product_ids, product_names,
                planned_qtys, actual_qtys, good_qtys, defect_qtys, units,
                statuses, start_times, end_times, stat_dates,
                worker_counts, work_minutes,
                material_costs, labor_costs, equipment_costs, other_costs,
                total_costs, unit_costs, yield_rates,
            )

    count = len(result)
    logger.info("[factory-etl] Silver: upserted %d rows for factory=%s", count, factory_id)
    return count


# ── Stage 2: Gold materialization — agg_factory_batch_daily ──────────────────

# Per-product per-day aggregation (product_type_id IS NOT NULL)
_AGG_PRODUCT_DAILY_SQL = """
INSERT INTO agg_factory_batch_daily (
    factory_id, stat_date, product_type_id,
    batch_count,
    total_planned_qty, total_actual_qty, total_good_qty,
    avg_yield_rate,
    total_material_cost, total_labor_cost, total_equipment_cost,
    total_other_cost, total_cost,
    version, updated_at
)
SELECT
    factory_id,
    stat_date,
    product_type_id,
    COUNT(*)::int                             AS batch_count,
    SUM(planned_qty)::NUMERIC(18,2)           AS total_planned_qty,
    SUM(actual_qty)::NUMERIC(18,2)            AS total_actual_qty,
    SUM(good_qty)::NUMERIC(18,2)              AS total_good_qty,
    AVG(yield_rate)::NUMERIC(5,2)             AS avg_yield_rate,
    -- Cost: SUM NULL-safe; if every row is NULL stays NULL (honest)
    SUM(material_cost)::NUMERIC(18,2)         AS total_material_cost,
    SUM(labor_cost)::NUMERIC(18,2)            AS total_labor_cost,
    SUM(equipment_cost)::NUMERIC(18,2)        AS total_equipment_cost,
    SUM(other_cost)::NUMERIC(18,2)            AS total_other_cost,
    SUM(total_cost)::NUMERIC(18,2)            AS total_cost,
    1,
    NOW()
FROM fact_production_batch
WHERE factory_id     = $1::varchar
  AND stat_date      IS NOT NULL
  AND product_type_id IS NOT NULL
GROUP BY factory_id, stat_date, product_type_id
ON CONFLICT (factory_id, stat_date, COALESCE(product_type_id, '__ALL__')) DO UPDATE SET
    batch_count          = EXCLUDED.batch_count,
    total_planned_qty    = EXCLUDED.total_planned_qty,
    total_actual_qty     = EXCLUDED.total_actual_qty,
    total_good_qty       = EXCLUDED.total_good_qty,
    avg_yield_rate       = EXCLUDED.avg_yield_rate,
    total_material_cost  = EXCLUDED.total_material_cost,
    total_labor_cost     = EXCLUDED.total_labor_cost,
    total_equipment_cost = EXCLUDED.total_equipment_cost,
    total_other_cost     = EXCLUDED.total_other_cost,
    total_cost           = EXCLUDED.total_cost,
    version              = agg_factory_batch_daily.version + 1,
    updated_at           = NOW()
"""

# All-product daily rollup (product_type_id = NULL → COALESCE 索引里映射成 __ALL__)
_AGG_ALL_DAILY_SQL = """
INSERT INTO agg_factory_batch_daily (
    factory_id, stat_date, product_type_id,
    batch_count,
    total_planned_qty, total_actual_qty, total_good_qty,
    avg_yield_rate,
    total_material_cost, total_labor_cost, total_equipment_cost,
    total_other_cost, total_cost,
    version, updated_at
)
SELECT
    factory_id,
    stat_date,
    NULL AS product_type_id,
    COUNT(*)::int                             AS batch_count,
    SUM(planned_qty)::NUMERIC(18,2)           AS total_planned_qty,
    SUM(actual_qty)::NUMERIC(18,2)            AS total_actual_qty,
    SUM(good_qty)::NUMERIC(18,2)              AS total_good_qty,
    AVG(yield_rate)::NUMERIC(5,2)             AS avg_yield_rate,
    SUM(material_cost)::NUMERIC(18,2)         AS total_material_cost,
    SUM(labor_cost)::NUMERIC(18,2)            AS total_labor_cost,
    SUM(equipment_cost)::NUMERIC(18,2)        AS total_equipment_cost,
    SUM(other_cost)::NUMERIC(18,2)            AS total_other_cost,
    SUM(total_cost)::NUMERIC(18,2)            AS total_cost,
    1,
    NOW()
FROM fact_production_batch
WHERE factory_id = $1::varchar
  AND stat_date  IS NOT NULL
GROUP BY factory_id, stat_date
ON CONFLICT (factory_id, stat_date, COALESCE(product_type_id, '__ALL__')) DO UPDATE SET
    batch_count          = EXCLUDED.batch_count,
    total_planned_qty    = EXCLUDED.total_planned_qty,
    total_actual_qty     = EXCLUDED.total_actual_qty,
    total_good_qty       = EXCLUDED.total_good_qty,
    avg_yield_rate       = EXCLUDED.avg_yield_rate,
    total_material_cost  = EXCLUDED.total_material_cost,
    total_labor_cost     = EXCLUDED.total_labor_cost,
    total_equipment_cost = EXCLUDED.total_equipment_cost,
    total_other_cost     = EXCLUDED.total_other_cost,
    total_cost           = EXCLUDED.total_cost,
    version              = agg_factory_batch_daily.version + 1,
    updated_at           = NOW()
"""


async def materialize_factory_gold(
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> Dict[str, int]:
    """Re-compute Gold agg tables from current Silver state.

    Runs both per-product and all-product daily rollups in a single transaction.
    Returns dict of {stage: rows_affected}.
    """
    stats: Dict[str, int] = {}
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, factory_id)
            r1 = await conn.execute(_AGG_PRODUCT_DAILY_SQL, factory_id)
            r2 = await conn.execute(_AGG_ALL_DAILY_SQL, factory_id)
            # asyncpg returns "INSERT 0 N" or "UPDATE N" — parse last token
            stats["product_daily"] = int(r1.split()[-1]) if r1 else 0
            stats["all_daily"]     = int(r2.split()[-1]) if r2 else 0

    logger.info("[factory-etl] Gold materialized for %s: %s", factory_id, stats)
    return stats


# ── Orchestrator ──────────────────────────────────────────────────────────────

async def run_factory_etl(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> FactoryEtlStats:
    """Run Silver + Gold sync for one factory.

    Stage isolation: each stage is individually try/excepted so a failure in
    Gold materialization does not rollback successfully synced Silver rows.
    """
    stats = FactoryEtlStats()

    try:
        stats.silver_upserted = await sync_fact_production_batch(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as exc:
        stats.errors.append(f"silver: {exc}")
        logger.exception("[factory-etl] Silver sync failed for %s", factory_id)
        # If Silver fails, skip Gold (nothing new to materialize)
        return stats

    try:
        gold = await materialize_factory_gold(smartbi_pool, factory_id)
        stats.gold_product_rows    = gold.get("product_daily", 0)
        stats.gold_daily_rollup_rows = gold.get("all_daily", 0)
    except Exception as exc:
        stats.errors.append(f"gold: {exc}")
        logger.exception("[factory-etl] Gold materialize failed for %s", factory_id)

    return stats


# ── Retry wrapper ─────────────────────────────────────────────────────────────

async def run_factory_etl_with_retry(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> FactoryEtlStats:
    """run_factory_etl with 3 retries + failure persistence.

    Mirrors run_full_etl_with_retry in restaurant_ops_etl.py.
    Failure logs written to restaurant_etl_failures (shared table — compatible
    schema, factory_id scopes rows correctly).

    Retry intervals: 1m / 5m / 15m. All-attempts-failed → re-raises last exception.
    """
    last_exc: Optional[Exception] = None

    for attempt in range(1, _MAX_ATTEMPTS + 1):
        start = time.monotonic()
        try:
            result = await run_factory_etl(cretas_pool, smartbi_pool, factory_id)
            if attempt > 1:
                logger.info(
                    "[factory-etl] succeeded on attempt %d for %s", attempt, factory_id
                )
            return result
        except Exception as exc:
            last_exc = exc
            duration_ms = int((time.monotonic() - start) * 1000)
            is_final = attempt == _MAX_ATTEMPTS
            status   = "failed_final" if is_final else "retrying"

            # Persist failure (best-effort; do not mask original exception)
            try:
                async with smartbi_pool.acquire() as conn:
                    await conn.execute(
                        """
                        INSERT INTO restaurant_etl_failures
                          (factory_id, run_at, status, attempt, error_msg,
                           error_class, duration_ms, trace)
                        VALUES ($1, NOW(), $2, $3, $4, $5, $6, $7)
                        """,
                        factory_id, status, attempt,
                        str(exc)[:1000],
                        exc.__class__.__name__,
                        duration_ms,
                        traceback.format_exc()[:4096],
                    )
            except Exception as log_exc:
                logger.warning("[factory-etl] failed to write failure log: %s", log_exc)

            if not is_final:
                backoff = _RETRY_BACKOFFS_SEC[attempt - 1]
                logger.warning(
                    "[factory-etl] attempt %d failed for %s, retrying in %ds: %s",
                    attempt, factory_id, backoff, exc,
                )
                await asyncio.sleep(backoff)
            else:
                logger.error(
                    "[factory-etl] attempt %d failed for %s (final): %s",
                    attempt, factory_id, exc,
                )
                raise

    # Defensive: unreachable (final attempt re-raises above)
    raise last_exc  # type: ignore[misc]
