"""Restaurant finance ETL — populate smart_bi_finance_data from POS + wastage sources.

Sprint 11.5 Phase D. Spec: docs/superpowers/specs/2026-05-23-sprint11.5-etl-design.md

Pipeline:
    smartbi_db.fact_pos_transaction    → smart_bi_finance_data (REVENUE rows)
    smartbi_db.fact_pos_item × cretas product_types
        × smartbi_db.agg_restaurant_product_cost → smart_bi_finance_data (COST food_cost rows)
    cretas_db.wastage_records          → smart_bi_finance_data (COST wastage rows)

Target shape (per RestaurantFinancialMetricsFetcher.java contract):
    REVENUE rows: category='营业收入', actual_amount=daily POS net_amount sum
    COST rows  : category='食材成本' (POS×recipe COGS) OR '食材损耗' (wastage cost)
                 total_cost + actual_amount = daily sum
    upload_id  : 0L (sentinel — any operator Excel upload wins via max-upload filter)

Design notes (mirror restaurant_ops_etl.py):
- Idempotent UPSERT via UNIQUE INDEX uq_smart_bi_finance_data_etl
  (factory_id, upload_id, record_date, record_type, category) — see migration
  V20260523_01__smart_bi_finance_data_etl_uniq.sql.
- Re-run on same date range overwrites actual_amount + total_cost.
- Batched UNNEST upsert (one INSERT per record_type / category bucket).
- Defaults: backfill last 90 days when start_date / end_date not supplied.
- RLS tenant set on every smartbi_pool transaction via app.factory_id GUC.
- ETL sentinel upload_id = 0 — RestaurantFinancialMetricsFetcher.filterToLatestUpload
  picks max(upload_id), so any operator Excel upload (upload_id >= 1) wins.

Caveats:
- POS×recipe COGS requires name-mapping POS dish name → cretas product_types.id
  → agg_restaurant_product_cost. Many POS items have product_id=NULL or
  unresolved names (per idx_fact_pos_item_unresolved); those contribute 0 to
  daily food cost. Phase C+ should run name-resolution backfill.
- LABOR / RENT / OTHER costs not populated for Phase D (no source data per
  spec §3.1). Fetcher emits null for those buckets, Composite Tool narrative
  handles gracefully.

Call sequence:
  pipeline = run_full_finance_etl(cretas_pool, smartbi_pool, factory_id,
                                  start_date=date(2025,1,1),
                                  end_date=date(2025,12,31))

Or via admin trigger:
  POST /api/smartbi/restaurant/etl/finance-etl/trigger
       {factoryId, startDate?, endDate?}
"""
from __future__ import annotations

import asyncio
import logging
import time
import traceback
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from typing import Dict, List, Optional, Tuple

import asyncpg

from smartbi.gold.restaurant_cost_mapping import merge_cost_product_mapping

logger = logging.getLogger(__name__)

# ETL sentinel — RestaurantFinancialMetricsFetcher.filterToLatestUpload picks
# max(upload_id), so 0 ensures any operator Excel upload (upload_id >= 1) wins.
ETL_UPLOAD_ID_SENTINEL: int = 0

# Default backfill window when caller omits start_date / end_date
DEFAULT_BACKFILL_DAYS: int = 90

# Category labels — must match RestaurantFinancialMetricsFetcher keyword sets
CATEGORY_REVENUE: str = "营业收入"          # matches sumRevenueCategory("收入")
CATEGORY_COST_WASTAGE: str = "食材损耗"     # matches FOOD_KEYWORDS = {"食材", ...}
CATEGORY_COST_POS_RECIPE: str = "食材成本"   # matches FOOD_KEYWORDS = {"食材", ...}

# Sprint 12 Phase D — factories included in nightly bulk finance ETL.
# Per dispatch Q2 decision: factories with real POS data in smartbi_prod_db
# (verified 2026-05-29 via fact_pos_transaction by-factory count).
# F006 excluded (0 POS data) — deferred to Phase F pending source data sync OR
# Sprint 13 customer-upload workflow.
RESTAURANT_FACTORY_BACKFILL_LIST: tuple = (
    "RES_3101_009",   # ~140K POS txns, full 2025 year (Phase F.1 baseline factory)
    "F001",           # ~140K POS txns (manufacturing factory with embedded POS — see Q2)
    "R_GML_DEMO",     # ~16K POS txns (demo / mid-sized restaurant)
    "R_XMX_CHAIN",    # ~141 POS txns (small chain, sparse data — sanity check)
)


@dataclass
class FinanceEtlStats:
    """Per-stage row counts for observability."""
    revenue_upserted: int = 0
    cost_wastage_upserted: int = 0
    cost_pos_recipe_upserted: int = 0
    pos_dish_resolved: int = 0
    pos_dish_unresolved: int = 0
    errors: List[str] = field(default_factory=list)


# ─────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────

async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    """Set the RLS tenant context for this connection's transaction."""
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


def _resolve_date_range(
    start_date: Optional[date],
    end_date: Optional[date],
) -> Tuple[date, date]:
    """Default to last DEFAULT_BACKFILL_DAYS days when either bound is omitted.

    Validated: end_date >= start_date, else ValueError.
    """
    today = date.today()
    if end_date is None:
        end_date = today
    if start_date is None:
        start_date = end_date - timedelta(days=DEFAULT_BACKFILL_DAYS)
    if end_date < start_date:
        raise ValueError(
            f"end_date {end_date} < start_date {start_date} — invalid range"
        )
    return start_date, end_date


# ─────────────────────────────────────────────────────────────────────
# Stage 1: REVENUE rollup from fact_pos_transaction
# ─────────────────────────────────────────────────────────────────────

# SQL constant — read by test_no_*_in_sql_constants regression tests
# (mirrors restaurant_ops_etl convention). NO Python comments inline.
_UPSERT_REVENUE_SQL = """
INSERT INTO smart_bi_finance_data (
    factory_id, upload_id, record_date, record_type, category,
    actual_amount, total_cost
)
SELECT $1::varchar AS factory_id,
       $2::bigint  AS upload_id,
       d           AS record_date,
       'REVENUE'   AS record_type,
       $3::varchar AS category,
       a::numeric(15,2) AS actual_amount,
       0::numeric(15,2) AS total_cost
  FROM UNNEST($4::date[], $5::numeric[]) AS t(d, a)
ON CONFLICT (factory_id, upload_id, record_date, record_type, category)
  WHERE deleted_at IS NULL
  DO UPDATE SET
    actual_amount = EXCLUDED.actual_amount,
    updated_at = NOW()
"""


async def sync_revenue_from_pos(
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start_date: date,
    end_date: date,
) -> int:
    """Aggregate daily POS net_amount → REVENUE rows.

    One row per (factory_id, date) with category=CATEGORY_REVENUE.

    Returns number of rows upserted.
    """
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, factory_id)
            rows = await conn.fetch(
                """
                SELECT date AS record_date,
                       COALESCE(SUM(net_amount), 0)::numeric(15,2) AS daily_revenue
                  FROM fact_pos_transaction
                 WHERE factory_id = $1::varchar
                   AND date BETWEEN $2::date AND $3::date
                 GROUP BY date
                """,
                factory_id, start_date, end_date,
            )

    if not rows:
        logger.info(
            "[finance-etl] no POS data for factory=%s range=%s..%s",
            factory_id, start_date, end_date,
        )
        return 0

    dates = [r["record_date"] for r in rows]
    revenues = [r["daily_revenue"] for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            await dst.execute(
                _UPSERT_REVENUE_SQL,
                factory_id,
                ETL_UPLOAD_ID_SENTINEL,
                CATEGORY_REVENUE,
                dates,
                revenues,
            )

    count = len(rows)
    logger.info(
        "[finance-etl] revenue: upserted %d rows for factory=%s range=%s..%s",
        count, factory_id, start_date, end_date,
    )
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 2: COST food cost — from wastage_records (cretas_db)
# ─────────────────────────────────────────────────────────────────────

_UPSERT_COST_SQL = """
INSERT INTO smart_bi_finance_data (
    factory_id, upload_id, record_date, record_type, category,
    material_cost, total_cost, actual_amount
)
SELECT $1::varchar AS factory_id,
       $2::bigint  AS upload_id,
       d           AS record_date,
       'COST'      AS record_type,
       $3::varchar AS category,
       c::numeric(15,2) AS material_cost,
       c::numeric(15,2) AS total_cost,
       c::numeric(15,2) AS actual_amount
  FROM UNNEST($4::date[], $5::numeric[]) AS t(d, c)
ON CONFLICT (factory_id, upload_id, record_date, record_type, category)
  WHERE deleted_at IS NULL
  DO UPDATE SET
    material_cost = EXCLUDED.material_cost,
    total_cost = EXCLUDED.total_cost,
    actual_amount = EXCLUDED.actual_amount,
    updated_at = NOW()
"""


async def sync_cost_from_wastage(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start_date: date,
    end_date: date,
) -> int:
    """Aggregate daily wastage estimated_cost → COST rows (category=食材损耗).

    Only APPROVED status wastage counts as real cost.
    """
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT wastage_date AS record_date,
                   COALESCE(SUM(estimated_cost), 0)::numeric(15,2) AS daily_cost
              FROM wastage_records
             WHERE factory_id = $1::varchar
               AND deleted_at IS NULL
               AND status = 'APPROVED'
               AND wastage_date BETWEEN $2::date AND $3::date
             GROUP BY wastage_date
            """,
            factory_id, start_date, end_date,
        )

    if not rows:
        logger.info(
            "[finance-etl] no wastage data for factory=%s range=%s..%s",
            factory_id, start_date, end_date,
        )
        return 0

    dates = [r["record_date"] for r in rows]
    costs = [r["daily_cost"] for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            await dst.execute(
                _UPSERT_COST_SQL,
                factory_id,
                ETL_UPLOAD_ID_SENTINEL,
                CATEGORY_COST_WASTAGE,
                dates,
                costs,
            )

    count = len(rows)
    logger.info(
        "[finance-etl] cost(wastage): upserted %d rows for factory=%s range=%s..%s",
        count, factory_id, start_date, end_date,
    )
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 3: COST food cost — from POS × recipe COGS
# ─────────────────────────────────────────────────────────────────────
# Logic mirrors restaurant_ops_router.resolve_gross_margin name-mapping:
#   1. fetch POS dish lines aggregated by (date, normalized_name)
#   2. resolve normalized_name → cretas product_types.id (with alias fallback)
#   3. fetch food_cost per product_source_pk from agg_restaurant_product_cost
#   4. compute per-date sum(qty × food_cost) and bulk-upsert as COST rows.
#
# Names that can't be resolved contribute 0 to that day's food cost.

async def sync_cost_from_pos_recipe(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start_date: date,
    end_date: date,
) -> Tuple[int, int, int]:
    """Aggregate daily (qty × food_cost) → COST rows (category=食材成本).

    Returns (upserted_count, dish_resolved, dish_unresolved).
    """
    # Step 1: POS dish lines in date range, aggregated by (date, normalized_name)
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        pos_rows = await conn.fetch(
            """
            SELECT t.date AS record_date,
                   p.normalized_name,
                   SUM(i.qty)::numeric(18,3) AS total_qty
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p ON p.product_id = i.product_id
             WHERE i.factory_id = $1::varchar
               AND t.factory_id = $1::varchar
               AND p.factory_id = $1::varchar
               AND t.date BETWEEN $2::date AND $3::date
               AND i.product_id IS NOT NULL
             GROUP BY t.date, p.normalized_name
            """,
            factory_id, start_date, end_date,
        )

    if not pos_rows:
        logger.info(
            "[finance-etl] no POS items resolved (product_id IS NULL only?) "
            "factory=%s range=%s..%s",
            factory_id, start_date, end_date,
        )
        return 0, 0, 0

    # Step 2: resolve normalized_name → cretas product_types.id (+ alias fallback)
    distinct_names = list({r["normalized_name"] for r in pos_rows})
    name_to_pk: Dict[str, str] = {}

    async with cretas_pool.acquire() as cretas:
        # Primary: exact-name match
        name_rows = await cretas.fetch(
            "SELECT id, name FROM product_types "
            "WHERE factory_id = $1 AND name = ANY($2::text[]) "
            "AND deleted_at IS NULL",
            factory_id, distinct_names,
        )
        for r in name_rows:
            name_to_pk[r["name"]] = r["id"]

        # Fallback: dim_product_alias (P0-2 — may not exist on older schemas)
        unmapped = [n for n in distinct_names if n not in name_to_pk]
        if unmapped:
            try:
                alias_rows = await cretas.fetch(
                    """
                    SELECT pos_name, product_type_id
                      FROM dim_product_alias
                     WHERE factory_id = $1 AND pos_name = ANY($2::text[])
                    """,
                    factory_id, unmapped,
                )
                for r in alias_rows:
                    name_to_pk[r["pos_name"]] = r["product_type_id"]
            except Exception as e:
                if "does not exist" not in str(e):
                    logger.warning("[finance-etl] alias lookup failed: %s", e)

    name_to_pk = await merge_cost_product_mapping(
        smartbi_pool,
        factory_id,
        distinct_names,
        name_to_pk,
    )
    resolved = sum(1 for n in distinct_names if n in name_to_pk)
    unresolved = len(distinct_names) - resolved

    if not name_to_pk:
        logger.warning(
            "[finance-etl] 0/%d POS names resolved to cretas product_types — "
            "no COST rows will be written. factory=%s",
            len(distinct_names), factory_id,
        )
        return 0, resolved, unresolved

    # Step 3: fetch food_cost per product_source_pk
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        cost_rows = await conn.fetch(
            """
            SELECT product_source_pk, food_cost::numeric(14,4) AS food_cost
              FROM agg_restaurant_product_cost
             WHERE factory_id = $1::varchar
               AND product_source_pk = ANY($2::text[])
            """,
            factory_id, list(name_to_pk.values()),
        )
    cost_by_pk: Dict[str, Decimal] = {
        r["product_source_pk"]: r["food_cost"] for r in cost_rows
    }

    # Step 4: compute per-date sum(qty × food_cost)
    daily_cost: Dict[date, Decimal] = {}
    for r in pos_rows:
        pk = name_to_pk.get(r["normalized_name"])
        if pk is None:
            continue
        food_cost = cost_by_pk.get(pk)
        if food_cost is None or food_cost <= 0:
            continue
        # Decimal × Decimal preserves precision
        line_cost = food_cost * r["total_qty"]
        daily_cost[r["record_date"]] = daily_cost.get(r["record_date"], Decimal("0")) + line_cost

    if not daily_cost:
        logger.info(
            "[finance-etl] cost(POS×recipe): 0 days had resolvable food cost "
            "factory=%s range=%s..%s",
            factory_id, start_date, end_date,
        )
        return 0, resolved, unresolved

    dates = sorted(daily_cost.keys())
    costs = [daily_cost[d] for d in dates]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            await dst.execute(
                _UPSERT_COST_SQL,
                factory_id,
                ETL_UPLOAD_ID_SENTINEL,
                CATEGORY_COST_POS_RECIPE,
                dates,
                costs,
            )

    count = len(dates)
    logger.info(
        "[finance-etl] cost(POS×recipe): upserted %d rows for factory=%s "
        "range=%s..%s (resolved=%d unresolved=%d)",
        count, factory_id, start_date, end_date, resolved, unresolved,
    )
    return count, resolved, unresolved


# ─────────────────────────────────────────────────────────────────────
# Orchestrator
# ─────────────────────────────────────────────────────────────────────

async def run_full_finance_etl(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> FinanceEtlStats:
    """Run all 3 ETL stages for one factory. Callers (cron / API) use this.

    Each stage wraps its own try/except so a single stage failing doesn't
    abort the others.

    Args:
        cretas_pool: cretas_db connection pool
        smartbi_pool: smartbi_db connection pool
        factory_id: factory ID (restaurant only — manufacturing out-of-scope)
        start_date: defaults to (end_date - DEFAULT_BACKFILL_DAYS) if None
        end_date: defaults to today if None

    Returns:
        FinanceEtlStats with per-stage row counts + errors list
    """
    stats = FinanceEtlStats()
    start_date, end_date = _resolve_date_range(start_date, end_date)

    logger.info(
        "[finance-etl] starting factory=%s range=%s..%s",
        factory_id, start_date, end_date,
    )

    try:
        stats.revenue_upserted = await sync_revenue_from_pos(
            smartbi_pool, factory_id, start_date, end_date,
        )
    except Exception as e:
        stats.errors.append(f"revenue: {e}")
        logger.exception("[finance-etl] revenue failed for %s", factory_id)

    try:
        stats.cost_wastage_upserted = await sync_cost_from_wastage(
            cretas_pool, smartbi_pool, factory_id, start_date, end_date,
        )
    except Exception as e:
        stats.errors.append(f"cost_wastage: {e}")
        logger.exception("[finance-etl] cost_wastage failed for %s", factory_id)

    try:
        cnt, resolved, unresolved = await sync_cost_from_pos_recipe(
            cretas_pool, smartbi_pool, factory_id, start_date, end_date,
        )
        stats.cost_pos_recipe_upserted = cnt
        stats.pos_dish_resolved = resolved
        stats.pos_dish_unresolved = unresolved
    except Exception as e:
        stats.errors.append(f"cost_pos_recipe: {e}")
        logger.exception("[finance-etl] cost_pos_recipe failed for %s", factory_id)

    logger.info(
        "[finance-etl] finished factory=%s revenue=%d cost_wastage=%d "
        "cost_pos_recipe=%d resolved=%d/%d errors=%d",
        factory_id, stats.revenue_upserted, stats.cost_wastage_upserted,
        stats.cost_pos_recipe_upserted, stats.pos_dish_resolved,
        stats.pos_dish_resolved + stats.pos_dish_unresolved,
        len(stats.errors),
    )

    return stats


# ─────────────────────────────────────────────────────────────────────
# Retry wrapper (mirror restaurant_ops_etl)
# ─────────────────────────────────────────────────────────────────────

_RETRY_BACKOFFS_SEC = [60, 300, 900]  # 1m, 5m, 15m
_MAX_ATTEMPTS = 3


async def run_full_finance_etl_with_retry(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> FinanceEtlStats:
    """run_full_finance_etl with 3 retries + failure persistence.

    Each attempt writes one row to restaurant_etl_failures (status:
    'retrying' or 'failed_final'). All-failures re-raise last exception.
    """
    last_exc: Optional[Exception] = None
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        start = time.monotonic()
        try:
            result = await run_full_finance_etl(
                cretas_pool, smartbi_pool, factory_id, start_date, end_date,
            )
            if attempt > 1:
                logger.info(
                    "Finance ETL succeeded on attempt %d for %s", attempt, factory_id
                )
            return result
        except Exception as exc:
            last_exc = exc
            duration_ms = int((time.monotonic() - start) * 1000)
            is_final = attempt == _MAX_ATTEMPTS
            status = "failed_final" if is_final else "retrying"

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
                logger.warning(
                    "Failed to write finance ETL failure log: %s", log_exc
                )

            if not is_final:
                backoff = _RETRY_BACKOFFS_SEC[attempt - 1]
                logger.warning(
                    "Finance ETL attempt %d failed for %s, retrying in %ds: %s",
                    attempt, factory_id, backoff, exc,
                )
                await asyncio.sleep(backoff)
            else:
                logger.error(
                    "Finance ETL attempt %d failed for %s (final): %s",
                    attempt, factory_id, exc,
                )
                raise

    # Defensive: should not be reachable
    raise last_exc  # type: ignore[misc]


# ─────────────────────────────────────────────────────────────────────
# Sprint 12 Phase D — multi-factory orchestrator
# ─────────────────────────────────────────────────────────────────────

@dataclass
class BulkFinanceEtlStats:
    """Aggregate stats across multiple factory runs (Sprint 12 Phase D)."""
    per_factory: Dict[str, FinanceEtlStats] = field(default_factory=dict)
    succeeded: List[str] = field(default_factory=list)
    failed: List[str] = field(default_factory=list)

    @property
    def total_revenue_upserted(self) -> int:
        return sum(s.revenue_upserted for s in self.per_factory.values())

    @property
    def total_cost_wastage_upserted(self) -> int:
        return sum(s.cost_wastage_upserted for s in self.per_factory.values())

    @property
    def total_cost_pos_recipe_upserted(self) -> int:
        return sum(s.cost_pos_recipe_upserted for s in self.per_factory.values())


async def run_full_finance_etl_for_factories(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_ids: Optional[List[str]] = None,
    start_date: Optional[date] = None,
    end_date: Optional[date] = None,
) -> BulkFinanceEtlStats:
    """Run finance ETL for multiple restaurant factories (Sprint 12 Phase D).

    Default factory list = :data:`RESTAURANT_FACTORY_BACKFILL_LIST` (4 factories
    with real POS data in smartbi_prod_db, F006 excluded per Q2 decision).

    Each factory runs sequentially through the retry wrapper — a single factory
    crash does NOT abort the others. Failures are persisted to
    ``restaurant_etl_failures`` table by ``run_full_finance_etl_with_retry``.

    Typical operational usage (nightly cron — scripts/ops/run-finance-etl-daily.sh):

        await run_full_finance_etl_for_factories(
            cretas_pool, smartbi_pool,
            start_date=date.today() - timedelta(days=1),
            end_date=date.today() - timedelta(days=1),
        )

    Args:
        cretas_pool: cretas_db connection pool
        smartbi_pool: smartbi_db connection pool
        factory_ids: list of factory IDs (None → use RESTAURANT_FACTORY_BACKFILL_LIST)
        start_date: defaults to (end_date - DEFAULT_BACKFILL_DAYS) if None
        end_date: defaults to today if None

    Returns:
        :class:`BulkFinanceEtlStats` aggregating per-factory stats + success/fail lists.
    """
    if factory_ids is None:
        factory_ids = list(RESTAURANT_FACTORY_BACKFILL_LIST)

    bulk = BulkFinanceEtlStats()
    logger.info(
        "[finance-etl-bulk] starting %d factories range=%s..%s factories=%s",
        len(factory_ids), start_date, end_date, factory_ids,
    )

    for factory_id in factory_ids:
        try:
            stats = await run_full_finance_etl_with_retry(
                cretas_pool, smartbi_pool, factory_id, start_date, end_date,
            )
            bulk.per_factory[factory_id] = stats
            bulk.succeeded.append(factory_id)
        except Exception as exc:
            # run_full_finance_etl_with_retry already logged + persisted failure;
            # we record the factory as failed and continue with the next.
            logger.warning(
                "[finance-etl-bulk] factory=%s final failure (after retries): %s",
                factory_id, exc,
            )
            bulk.failed.append(factory_id)

    logger.info(
        "[finance-etl-bulk] finished succeeded=%d failed=%d "
        "totalRevenueUpserted=%d totalCostWastageUpserted=%d totalCostPosRecipeUpserted=%d",
        len(bulk.succeeded), len(bulk.failed),
        bulk.total_revenue_upserted,
        bulk.total_cost_wastage_upserted,
        bulk.total_cost_pos_recipe_upserted,
    )
    return bulk
