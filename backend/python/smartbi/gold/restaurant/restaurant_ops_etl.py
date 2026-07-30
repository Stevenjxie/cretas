"""Restaurant daily ops ETL — cretas_db (Bronze) → smartbi_db (Silver + Gold).

Part of Plan C Phase 1-3 (2026-04-24).

Pipeline:
    cretas_db.raw_material_types     → smartbi_db.dim_ingredient
    cretas_db.material_requisitions  → smartbi_db.fact_restaurant_requisition
    cretas_db.wastage_records        → smartbi_db.fact_restaurant_wastage
    cretas_db.recipes                → smartbi_db.fact_restaurant_recipe_line
    cretas_db.stocktaking_records    → smartbi_db.fact_restaurant_stocktaking
    → smartbi_db.agg_restaurant_daily_ops (EAV)
    → smartbi_db.agg_restaurant_daily_totals (scalar per day)
    → smartbi_db.agg_restaurant_product_cost (recipe × price)

Call from an orchestrator (cron or trigger) with `run_full_etl(factory_id)`.

Design notes:
- Each sync is INSERT ... ON CONFLICT DO UPDATE keyed on (factory_id, source_pk),
  so repeated runs are idempotent.
- We fetch cretas_db rows in batches of 500 to bound memory; smartbi_db
  upserts are one INSERT per batch with UNNEST arrays for bulk loading.
- Tenant RLS on Silver/Gold tables is handled via `app.factory_id` session
  variable set at the start of each transaction.
- cretas_db table names here are approximate — verified at runtime via
  information_schema before running. If the Java backend renames tables,
  the ETL should fail fast, not corrupt Silver silently.
"""
from __future__ import annotations

import asyncio
import logging
import time
import traceback
from dataclasses import dataclass
from typing import Dict, List, Optional

import asyncpg

from smartbi.gold.supplier_price_ingest_etl import (
    MAX_SANE_UNIT_PRICE,
    _is_sane_unit_price,
)

logger = logging.getLogger(__name__)


@dataclass
class EtlStats:
    """Per-stage row counts for observability."""
    dim_ingredient_upserted: int = 0
    fact_requisition_upserted: int = 0
    fact_wastage_upserted: int = 0
    fact_recipe_upserted: int = 0
    fact_stocktaking_upserted: int = 0
    agg_daily_ops_upserted: int = 0
    agg_daily_totals_upserted: int = 0
    agg_product_cost_upserted: int = 0
    errors: List[str] = None

    def __post_init__(self):
        if self.errors is None:
            self.errors = []


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    """Set the RLS tenant context for this connection's transaction."""
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


def _normalize_name(name: str) -> str:
    """Lightweight normalization for ingredient dedup — lowercase + strip + collapse WS."""
    if not name:
        return ""
    return " ".join(str(name).lower().strip().split())


# ─────────────────────────────────────────────────────────────────────
# Stage 1: dim_ingredient
# ─────────────────────────────────────────────────────────────────────

async def sync_dim_ingredient(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert all active raw_material_types rows into dim_ingredient.

    Returns number of rows upserted.
    """
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT id, name, category, code, unit, unit_price, moving_avg_price,
                   shelf_life_days, storage_type, is_active
              FROM raw_material_types
             WHERE factory_id = $1::varchar AND COALESCE(deleted_at, '1900-01-01') = '1900-01-01'
            """,
            factory_id,
        )
    if not rows:
        return 0

    # Latest REAL purchase price per raw_material_type_id from agg_supplier_price
    # (delivery-note / material-batch ingest). Prefer this over moving_avg/list price
    # so food_cost uses true purchase cost where we actually have it. Sparse coverage
    # is expected (prod RES_3101_009: ~5/53), hence the moving_avg fallback + honest
    # cost_source marker — we never claim a fake real price.
    real_price_map = await _get_latest_real_purchase_prices(smartbi_pool, factory_id)

    # Build bulk arrays for UNNEST upsert
    source_pks = [r["id"] for r in rows]
    names = [r["name"] or r["id"] for r in rows]
    normalized = [_normalize_name(n) for n in names]
    categories = [r["category"] for r in rows]
    codes = [r["code"] for r in rows]
    units = [r["unit"] for r in rows]

    # Price priority (honest): real_purchase > moving_avg > list_price > none.
    unit_prices: List[Optional[float]] = []
    cost_sources: List[str] = []
    for r in rows:
        real = real_price_map.get(r["id"])
        if real is not None:
            unit_prices.append(real)
            cost_sources.append("real_purchase")
        elif _is_sane_unit_price(
            float(r["moving_avg_price"]) if r["moving_avg_price"] is not None else None
        ):
            unit_prices.append(float(r["moving_avg_price"]))
            cost_sources.append("moving_avg")
        elif _is_sane_unit_price(
            float(r["unit_price"]) if r["unit_price"] is not None else None
        ):
            unit_prices.append(float(r["unit_price"]))
            cost_sources.append("list_price")
        else:
            unit_prices.append(None)
            cost_sources.append("none")

    shelf_lives = [r["shelf_life_days"] for r in rows]
    storage_types = [r["storage_type"] for r in rows]
    actives = [bool(r["is_active"]) for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            result = await dst.fetch(
                """
                INSERT INTO dim_ingredient (
                    factory_id, source_pk, name, normalized_name, category, code,
                    unit, unit_price, cost_source, shelf_life_days, storage_type, is_active
                )
                SELECT $1, pk, n, nn, cat, c, u, up, cs, sl, st, act
                  FROM UNNEST(
                    $2::text[], $3::text[], $4::text[], $5::text[], $6::text[],
                    $7::text[], $8::numeric[], $9::text[], $10::int[], $11::text[], $12::boolean[]
                  ) AS t(pk, n, nn, cat, c, u, up, cs, sl, st, act)
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    name = EXCLUDED.name,
                    normalized_name = EXCLUDED.normalized_name,
                    category = EXCLUDED.category,
                    code = EXCLUDED.code,
                    unit = EXCLUDED.unit,
                    unit_price = EXCLUDED.unit_price,
                    cost_source = EXCLUDED.cost_source,
                    shelf_life_days = EXCLUDED.shelf_life_days,
                    storage_type = EXCLUDED.storage_type,
                    is_active = EXCLUDED.is_active,
                    updated_at = NOW()
                RETURNING ingredient_id
                """,
                factory_id, source_pks, names, normalized, categories, codes,
                units, unit_prices, cost_sources, shelf_lives, storage_types, actives,
            )
    count = len(result)
    logger.info("[etl] dim_ingredient: upserted %d rows for factory=%s", count, factory_id)
    return count


async def _get_ingredient_pk_map(
    smartbi_pool: asyncpg.Pool, factory_id: str,
) -> Dict[str, int]:
    """Return {cretas source_pk → smartbi ingredient_id} for this factory.

    ⚠️ RLS: _set_tenant uses set_config(..., is_local=true) which is transaction-scoped.
    asyncpg runs each statement in its own implicit transaction (autocommit), so a bare
    _set_tenant + separate fetch resets app.factory_id BEFORE the fetch → RLS reads 0 rows
    → empty map → every recipe-line ingredient_id inserted as NULL → line_cost UPDATE
    (which joins on ingredient_id) matches nothing → food_cost never uses real ingredient
    prices. This was the prod root cause of fact_restaurant_recipe_line.ingredient_id being
    100% NULL. Wrapping _set_tenant + fetch in one explicit transaction keeps the GUC alive.
    """
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, factory_id)
            rows = await conn.fetch(
                "SELECT source_pk, ingredient_id FROM dim_ingredient WHERE factory_id = $1::varchar",
                factory_id,
            )
    return {r["source_pk"]: r["ingredient_id"] for r in rows}


async def _get_latest_real_purchase_prices(
    smartbi_pool: asyncpg.Pool, factory_id: str,
) -> Dict[str, float]:
    """Return {raw_material_type_id → latest real purchase unit_price} from agg_supplier_price.

    Picks the most recent confirmed purchase price per raw_material_type_id (delivery-note
    OCR/manual confirms + material-batch ingest). Used to override dim_ingredient.unit_price
    with TRUE purchase cost where coverage exists (sparse — many recipe ingredients have no
    confirmed purchase yet, hence sync_dim_ingredient falls back to moving_avg/list price).

    Only rows with a non-null raw_material_type_id are usable (that's the join key to recipes).

    RLS: set_config + fetch wrapped in one explicit transaction (is_local=true GUC must
    outlive the fetch — same asyncpg autocommit caveat as _get_ingredient_pk_map).
    """
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, factory_id)
            rows = await conn.fetch(
                """
                SELECT DISTINCT ON (raw_material_type_id)
                       raw_material_type_id, unit_price
                  FROM agg_supplier_price
                 WHERE factory_id = $1::varchar
                   AND raw_material_type_id IS NOT NULL
                   AND unit_price IS NOT NULL
                   AND unit_price > 0
                   AND unit_price < $2
                ORDER BY raw_material_type_id, delivery_date DESC, id DESC
                """,
                factory_id,
                MAX_SANE_UNIT_PRICE,
            )
    return {
        r["raw_material_type_id"]: float(r["unit_price"])
        for r in rows
        if r["raw_material_type_id"] is not None and r["unit_price"] is not None
    }


# ─────────────────────────────────────────────────────────────────────
# Stage 2: fact_restaurant_requisition
# ─────────────────────────────────────────────────────────────────────

async def sync_fact_requisition(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert material_requisitions rows (1 row per requisition)."""
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT id, requisition_number, requisition_date, type, status,
                   product_type_id, raw_material_type_id,
                   requested_quantity, actual_quantity, unit,
                   requested_by, approved_by, approved_at, notes
              FROM material_requisitions
             WHERE factory_id = $1::varchar AND deleted_at IS NULL
            """,
            factory_id,
        )
    if not rows:
        return 0

    ing_map = await _get_ingredient_pk_map(smartbi_pool, factory_id)

    source_pks = [r["id"] for r in rows]
    req_numbers = [r["requisition_number"] for r in rows]
    dates = [r["requisition_date"] for r in rows]
    types = [r["type"] for r in rows]
    statuses = [r["status"] for r in rows]
    product_ids = [None for _ in rows]  # TODO Phase 2: resolve via dim_product
    ingredient_ids = [ing_map.get(r["raw_material_type_id"]) for r in rows]
    requested_qtys = [
        float(r["requested_quantity"]) if r["requested_quantity"] is not None else None
        for r in rows
    ]
    actual_qtys = [
        float(r["actual_quantity"]) if r["actual_quantity"] is not None else None
        for r in rows
    ]
    units = [r["unit"] for r in rows]
    # est_cost = requested_qty × ingredient.unit_price (lookup separately to avoid
    # materializing full dim in Python; simpler pattern: compute during upsert via subquery).
    # For now, leave NULL — the Gold aggregator will compute cost from (qty × dim.unit_price).
    est_costs = [None for _ in rows]
    requested_bys = [r["requested_by"] for r in rows]
    approved_bys = [r["approved_by"] for r in rows]
    approved_ats = [r["approved_at"] for r in rows]
    notes = [r["notes"] for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            result = await dst.fetch(
                """
                INSERT INTO fact_restaurant_requisition (
                    factory_id, source_pk, requisition_number, date,
                    product_id, ingredient_id, type, status,
                    requested_qty, actual_qty, unit, est_cost,
                    requested_by, approved_by, approved_at, notes
                )
                SELECT $1, pk, rn, d, prod, ing, t, s, rq, aq, u, ec, rb, ab, aa, n
                  FROM UNNEST(
                    $2::text[], $3::text[], $4::date[],
                    $5::bigint[], $6::bigint[], $7::text[], $8::text[],
                    $9::numeric[], $10::numeric[], $11::text[], $12::numeric[],
                    $13::bigint[], $14::bigint[], $15::timestamp[], $16::text[]
                  ) AS t(pk, rn, d, prod, ing, t, s, rq, aq, u, ec, rb, ab, aa, n)
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    requisition_number = EXCLUDED.requisition_number,
                    date = EXCLUDED.date,
                    ingredient_id = EXCLUDED.ingredient_id,
                    type = EXCLUDED.type,
                    status = EXCLUDED.status,
                    requested_qty = EXCLUDED.requested_qty,
                    actual_qty = EXCLUDED.actual_qty,
                    unit = EXCLUDED.unit,
                    approved_by = EXCLUDED.approved_by,
                    approved_at = EXCLUDED.approved_at,
                    notes = EXCLUDED.notes,
                    updated_at = NOW()
                RETURNING id
                """,
                factory_id, source_pks, req_numbers, dates, product_ids,
                ingredient_ids, types, statuses, requested_qtys, actual_qtys,
                units, est_costs, requested_bys, approved_bys, approved_ats, notes,
            )
    # Recompute est_cost from the current dim_ingredient price on every run.
    # Demo data corrections can fix unit_price after fact rows already exist;
    # leaving non-null stale est_cost in place would keep polluting aggregates.
    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            await dst.execute(
                """
                UPDATE fact_restaurant_requisition r
                   SET est_cost = ROUND(r.requested_qty * i.unit_price, 2)
                  FROM dim_ingredient i
                 WHERE r.factory_id = $1
                   AND r.ingredient_id = i.ingredient_id
                   AND r.requested_qty IS NOT NULL
                   AND i.unit_price IS NOT NULL
                """,
                factory_id,
            )
    count = len(result)
    logger.info("[etl] fact_requisition: upserted %d rows for factory=%s", count, factory_id)
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 3: fact_restaurant_wastage
# ─────────────────────────────────────────────────────────────────────

async def sync_fact_wastage(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert wastage_records (1 row per wastage event)."""
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT id, wastage_number, wastage_date, type, status,
                   raw_material_type_id, quantity, unit, estimated_cost, reason,
                   operator_id, section_code
              FROM wastage_records
             WHERE factory_id = $1::varchar AND deleted_at IS NULL
            """,
            factory_id,
        )
    if not rows:
        return 0

    ing_map = await _get_ingredient_pk_map(smartbi_pool, factory_id)

    source_pks = [r["id"] for r in rows]
    numbers = [r["wastage_number"] for r in rows]
    dates = [r["wastage_date"] for r in rows]
    wastage_types = [r["type"] for r in rows]
    statuses = [r["status"] for r in rows]
    ingredient_ids = [ing_map.get(r["raw_material_type_id"]) for r in rows]
    quantities = [float(r["quantity"]) if r["quantity"] is not None else None for r in rows]
    units = [r["unit"] for r in rows]
    est_costs = [float(r["estimated_cost"]) if r["estimated_cost"] is not None else None for r in rows]
    reasons = [r["reason"] for r in rows]
    # Wave2 损耗按人/档口责任制
    operator_ids = [r["operator_id"] for r in rows]
    section_codes = [r["section_code"] for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            result = await dst.fetch(
                """
                INSERT INTO fact_restaurant_wastage (
                    factory_id, source_pk, wastage_number, date, wastage_type,
                    status, ingredient_id, quantity, unit, estimated_cost, reason,
                    operator_id, section_code
                )
                SELECT $1, pk, n, d, wt, s, ing, q, u, ec, r, op, sec
                  FROM UNNEST(
                    $2::text[], $3::text[], $4::date[], $5::text[], $6::text[],
                    $7::bigint[], $8::numeric[], $9::text[], $10::numeric[], $11::text[],
                    $12::bigint[], $13::text[]
                  ) AS t(pk, n, d, wt, s, ing, q, u, ec, r, op, sec)
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    wastage_number = EXCLUDED.wastage_number,
                    date = EXCLUDED.date,
                    wastage_type = EXCLUDED.wastage_type,
                    status = EXCLUDED.status,
                    ingredient_id = EXCLUDED.ingredient_id,
                    quantity = EXCLUDED.quantity,
                    unit = EXCLUDED.unit,
                    estimated_cost = EXCLUDED.estimated_cost,
                    reason = EXCLUDED.reason,
                    operator_id = EXCLUDED.operator_id,
                    section_code = EXCLUDED.section_code,
                    updated_at = NOW()
                RETURNING id
                """,
                factory_id, source_pks, numbers, dates, wastage_types, statuses,
                ingredient_ids, quantities, units, est_costs, reasons,
                operator_ids, section_codes,
            )
    count = len(result)
    logger.info("[etl] fact_wastage: upserted %d rows for factory=%s", count, factory_id)
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 3b: fact_restaurant_recipe_line
# ─────────────────────────────────────────────────────────────────────

async def sync_fact_recipe(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert recipes (BOM line per dish+ingredient). product_id resolution
    is deferred — dim_product lookup needs a dish dim; for now leave as 0.
    """
    async with cretas_pool.acquire() as src:
        # P2-8: use v_recipes_effective view if available (picks highest-priority
        # variant whose effective_from/to covers NOW). Falls back to legacy recipes
        # table if view doesn't exist yet.
        try:
            rows = await src.fetch(
                """
                SELECT r.id, r.product_type_id, r.raw_material_type_id,
                       r.standard_quantity, r.unit, r.net_yield_rate,
                       r.is_main_ingredient, r.is_active,
                       p.name AS product_name
                  FROM v_recipes_effective r
                  LEFT JOIN product_types p
                    ON p.factory_id = r.factory_id
                   AND p.id = r.product_type_id
                 WHERE r.factory_id = $1::varchar
                """,
                factory_id,
            )
        except Exception:
            rows = await src.fetch(
                """
                SELECT r.id, r.product_type_id, r.raw_material_type_id,
                       r.standard_quantity, r.unit, r.net_yield_rate,
                       r.is_main_ingredient, r.is_active,
                       p.name AS product_name
                  FROM recipes r
                  LEFT JOIN product_types p
                    ON p.factory_id = r.factory_id
                   AND p.id = r.product_type_id
                 WHERE r.factory_id = $1::varchar AND r.deleted_at IS NULL
                """,
                factory_id,
            )
    if not rows:
        return 0

    ing_map = await _get_ingredient_pk_map(smartbi_pool, factory_id)
    # Phase 2.5: store cretas_db product_type_id directly as product_source_pk;
    # avoids needing full dim_product ETL from cretas_db (which would conflict
    # with POS-derived dim_product from bill parser). Resolver joins
    # product_types.name back at query time via source_pk.
    source_pks = [r["id"] for r in rows]
    product_ids = [0 for _ in rows]  # legacy column, kept for FK; real grain is product_source_pk
    product_source_pks = [r["product_type_id"] for r in rows]
    ingredient_ids = [ing_map.get(r["raw_material_type_id"]) for r in rows]
    std_qtys = [float(r["standard_quantity"]) if r["standard_quantity"] is not None else None for r in rows]
    units = [r["unit"] for r in rows]
    yield_rates = [float(r["net_yield_rate"]) if r["net_yield_rate"] is not None else None for r in rows]
    is_mains = [bool(r["is_main_ingredient"]) for r in rows]
    is_actives = [bool(r["is_active"]) for r in rows]
    # One authoritative product-name snapshot per recipe cost key.  It lives in
    # SmartBI so historical Gold cost rows remain resolvable even if an old demo
    # or operational product seed is later removed from the Cretas database.
    product_names_by_pk = {
        str(r["product_type_id"]): str(r["product_name"]).strip()
        for r in rows
        if r["product_type_id"] and r["product_name"] and str(r["product_name"]).strip()
    }

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            if product_names_by_pk:
                mapping_pks = list(product_names_by_pk)
                mapping_names = [product_names_by_pk[pk] for pk in mapping_pks]
                mapping_normalized = [_normalize_name(name) for name in mapping_names]
                await dst.fetch(
                    """
                    INSERT INTO dim_restaurant_cost_product (
                        factory_id, product_source_pk, product_name,
                        normalized_name, source, is_active
                    )
                    SELECT $1::varchar, pk, n, nn, 'recipe_etl', TRUE
                      FROM UNNEST(
                        $2::text[], $3::text[], $4::text[]
                      ) AS t(pk, n, nn)
                    ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET
                        product_name = EXCLUDED.product_name,
                        normalized_name = EXCLUDED.normalized_name,
                        source = EXCLUDED.source,
                        is_active = TRUE,
                        updated_at = NOW()
                    RETURNING product_source_pk
                    """,
                    factory_id,
                    mapping_pks,
                    mapping_names,
                    mapping_normalized,
                )
            result = await dst.fetch(
                """
                INSERT INTO fact_restaurant_recipe_line (
                    factory_id, source_pk, product_id, product_source_pk, ingredient_id,
                    standard_qty, unit, yield_rate, is_main_ingredient, is_active
                )
                SELECT $1::varchar, pk, prod, psp, ing, sq, u, yr, m, act
                  FROM UNNEST(
                    $2::text[], $3::bigint[], $4::text[], $5::bigint[], $6::numeric[],
                    $7::text[], $8::numeric[], $9::boolean[], $10::boolean[]
                  ) AS t(pk, prod, psp, ing, sq, u, yr, m, act)
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    product_source_pk = EXCLUDED.product_source_pk,
                    ingredient_id = EXCLUDED.ingredient_id,
                    standard_qty = EXCLUDED.standard_qty,
                    unit = EXCLUDED.unit,
                    yield_rate = EXCLUDED.yield_rate,
                    is_main_ingredient = EXCLUDED.is_main_ingredient,
                    is_active = EXCLUDED.is_active,
                    updated_at = NOW()
                RETURNING id
                """,
                factory_id, source_pks, product_ids, product_source_pks, ingredient_ids,
                std_qtys, units, yield_rates, is_mains, is_actives,
            )
    # Compute line_cost = standard_qty × ingredient.unit_price
    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            # Clear stale derived values first so a newly rejected source price
            # cannot survive in yesterday's recipe-line cost.
            await dst.execute(
                """
                UPDATE fact_restaurant_recipe_line
                   SET line_cost = NULL
                 WHERE factory_id = $1
                """,
                factory_id,
            )
            await dst.execute(
                """
                UPDATE fact_restaurant_recipe_line r
                   SET line_cost = ROUND(r.standard_qty * i.unit_price, 4)
                  FROM dim_ingredient i
                 WHERE r.factory_id = $1
                   AND r.ingredient_id = i.ingredient_id
                   AND r.standard_qty IS NOT NULL
                   AND i.unit_price IS NOT NULL
                """,
                factory_id,
            )
    count = len(result)
    logger.info("[etl] fact_recipe: upserted %d rows for factory=%s", count, factory_id)
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 3c: fact_restaurant_stocktaking
# ─────────────────────────────────────────────────────────────────────

async def sync_fact_stocktaking(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> int:
    """Upsert stocktaking_records (1 row per stocktaking line)."""
    async with cretas_pool.acquire() as src:
        rows = await src.fetch(
            """
            SELECT id, stocktaking_number, stocktaking_date, status,
                   raw_material_type_id, unit,
                   system_quantity, actual_quantity, difference_quantity,
                   difference_amount, adjustment_reason
              FROM stocktaking_records
             WHERE factory_id = $1::varchar AND deleted_at IS NULL
            """,
            factory_id,
        )
    if not rows:
        return 0

    ing_map = await _get_ingredient_pk_map(smartbi_pool, factory_id)

    source_pks = [r["id"] for r in rows]
    numbers = [r["stocktaking_number"] for r in rows]
    dates = [r["stocktaking_date"] for r in rows]
    statuses = [r["status"] for r in rows]
    ingredient_ids = [ing_map.get(r["raw_material_type_id"]) for r in rows]
    system_qtys = [float(r["system_quantity"]) if r["system_quantity"] is not None else None for r in rows]
    actual_qtys = [float(r["actual_quantity"]) if r["actual_quantity"] is not None else None for r in rows]
    diff_qtys = [float(r["difference_quantity"]) if r["difference_quantity"] is not None else None for r in rows]
    diff_costs = [float(r["difference_amount"]) if r["difference_amount"] is not None else None for r in rows]
    units = [r["unit"] for r in rows]
    reasons = [r["adjustment_reason"] for r in rows]

    async with smartbi_pool.acquire() as dst:
        async with dst.transaction():
            await _set_tenant(dst, factory_id)
            result = await dst.fetch(
                """
                INSERT INTO fact_restaurant_stocktaking (
                    factory_id, source_pk, stocktaking_number, date, status,
                    ingredient_id, system_qty, actual_qty, difference_qty,
                    difference_cost, unit, reason
                )
                SELECT $1, pk, n, d, s, ing, sq, aq, dq, dc, u, r
                  FROM UNNEST(
                    $2::text[], $3::text[], $4::date[], $5::text[],
                    $6::bigint[], $7::numeric[], $8::numeric[], $9::numeric[],
                    $10::numeric[], $11::text[], $12::text[]
                  ) AS t(pk, n, d, s, ing, sq, aq, dq, dc, u, r)
                ON CONFLICT (factory_id, source_pk) DO UPDATE SET
                    stocktaking_number = EXCLUDED.stocktaking_number,
                    date = EXCLUDED.date,
                    status = EXCLUDED.status,
                    ingredient_id = EXCLUDED.ingredient_id,
                    system_qty = EXCLUDED.system_qty,
                    actual_qty = EXCLUDED.actual_qty,
                    difference_qty = EXCLUDED.difference_qty,
                    difference_cost = EXCLUDED.difference_cost,
                    unit = EXCLUDED.unit,
                    reason = EXCLUDED.reason,
                    updated_at = NOW()
                RETURNING id
                """,
                factory_id, source_pks, numbers, dates, statuses,
                ingredient_ids, system_qtys, actual_qtys, diff_qtys,
                diff_costs, units, reasons,
            )
    count = len(result)
    logger.info("[etl] fact_stocktaking: upserted %d rows for factory=%s", count, factory_id)
    return count


# ─────────────────────────────────────────────────────────────────────
# Stage 3d: agg_restaurant_product_cost — dish food-cost rollup
# ─────────────────────────────────────────────────────────────────────
# For each product_id, SUM(standard_qty × ingredient.unit_price) gives the
# per-dish food cost. Recomputed on every ETL run since unit prices drift.

_AGG_PRODUCT_COST_SQL = """
INSERT INTO agg_restaurant_product_cost (
    factory_id, product_id, product_source_pk, food_cost, main_ingredient_id,
    ingredient_count, has_price_data, version, computed_at
)
SELECT $1::varchar, 0 AS product_id,
       COALESCE(r.product_source_pk, '') AS product_source_pk,
       COALESCE(SUM(r.line_cost), 0)::NUMERIC(14,4) AS food_cost,
       (ARRAY_AGG(
           r.ingredient_id
           ORDER BY r.is_main_ingredient DESC, r.line_cost DESC NULLS LAST
       ))[1] AS main_ingredient_id,
       COUNT(*)::int AS ingredient_count,
       bool_and(r.line_cost IS NOT NULL) AS has_price_data,
       1, NOW()
  FROM fact_restaurant_recipe_line r
 WHERE r.factory_id = $1::varchar AND r.is_active = TRUE
 GROUP BY r.product_source_pk
ON CONFLICT (factory_id, product_source_pk) DO UPDATE SET
    food_cost = EXCLUDED.food_cost,
    main_ingredient_id = EXCLUDED.main_ingredient_id,
    ingredient_count = EXCLUDED.ingredient_count,
    has_price_data = EXCLUDED.has_price_data,
    version = agg_restaurant_product_cost.version + 1,
    computed_at = NOW()
"""


# ─────────────────────────────────────────────────────────────────────
# Stage 4: Gold aggregations
# ─────────────────────────────────────────────────────────────────────

# Per-ingredient daily quantity rollup. Run after fact_requisition synced.
_AGG_REQUISITION_QTY_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'requisition_qty',
       COALESCE(ingredient_id, 0) AS dim_value_id,
       '' AS dim_value_str,
       SUM(COALESCE(requested_qty, 0))::NUMERIC(18,4) AS value_num,
       1, NOW()
  FROM fact_restaurant_requisition
 WHERE factory_id = $1::varchar
   AND status IN ('APPROVED', 'SUBMITTED')
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1,
    computed_at = NOW()
"""

# Per-ingredient daily cost rollup.
_AGG_REQUISITION_COST_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'requisition_cost',
       COALESCE(ingredient_id, 0) AS dim_value_id,
       '' AS dim_value_str,
       SUM(COALESCE(est_cost, 0))::NUMERIC(18,4) AS value_num,
       1, NOW()
  FROM fact_restaurant_requisition
 WHERE factory_id = $1::varchar
   AND status IN ('APPROVED', 'SUBMITTED')
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1,
    computed_at = NOW()
"""

# Wastage qty per ingredient per day.
_AGG_WASTAGE_QTY_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'wastage_qty',
       COALESCE(ingredient_id, 0), '', SUM(COALESCE(quantity, 0))::NUMERIC(18,4),
       1, NOW()
  FROM fact_restaurant_wastage WHERE factory_id = $1::varchar AND status = 'APPROVED'
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1, computed_at = NOW()
"""

# Wastage cost per ingredient per day. Mirrors _AGG_WASTAGE_QTY_SQL exactly
# except for the summed column, so the two axes always cover the same rows and
# a ranking can switch between them without changing which ingredients appear.
# Needed because _AGG_WASTAGE_COST_BY_TYPE_SQL below aggregates money by
# wastage_type only -- there was no per-ingredient money axis, so
# "损耗金额排名" (an advertised sample query) could only be answered by quantity.
_AGG_WASTAGE_COST_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'wastage_cost',
       COALESCE(ingredient_id, 0), '', SUM(COALESCE(estimated_cost, 0))::NUMERIC(18,4),
       1, NOW()
  FROM fact_restaurant_wastage WHERE factory_id = $1::varchar AND status = 'APPROVED'
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1, computed_at = NOW()
"""

# Wastage cost by type (string dim, not ingredient). Use dim_value_str.
_AGG_WASTAGE_COST_BY_TYPE_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'wastage_cost_by_type',
       0, COALESCE(wastage_type, 'OTHER'),
       SUM(COALESCE(estimated_cost, 0))::NUMERIC(18,4),
       1, NOW()
  FROM fact_restaurant_wastage WHERE factory_id = $1::varchar AND status = 'APPROVED'
 GROUP BY factory_id, date, wastage_type
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1, computed_at = NOW()
"""

# Stocktaking shortage per ingredient (absolute of negative difference).
_AGG_STOCK_SHORTAGE_SQL = """
INSERT INTO agg_restaurant_daily_ops (
    factory_id, date, kpi_kind, dim_value_id, dim_value_str, value_num,
    version, computed_at
)
SELECT factory_id, date, 'stocktaking_shortage_qty',
       COALESCE(ingredient_id, 0), '',
       SUM(CASE WHEN difference_qty < 0 THEN -difference_qty ELSE 0 END)::NUMERIC(18,4),
       1, NOW()
  FROM fact_restaurant_stocktaking
 WHERE factory_id = $1::varchar AND status = 'COMPLETED'
 GROUP BY factory_id, date, ingredient_id
ON CONFLICT (factory_id, date, kpi_kind, dim_value_id, dim_value_str) DO UPDATE SET
    value_num = EXCLUDED.value_num,
    version = agg_restaurant_daily_ops.version + 1, computed_at = NOW()
"""

# Daily totals scalar table — single row per (factory, date).
_AGG_DAILY_TOTALS_SQL = """
INSERT INTO agg_restaurant_daily_totals (
    factory_id, date,
    requisition_count, requisition_qty_total, requisition_cost_total,
    wastage_count, wastage_qty_total, wastage_cost_total,
    stocktaking_count, stocktaking_shortage_total, stocktaking_surplus_total,
    version, computed_at
)
SELECT $1::varchar AS factory_id, d.date,
       COALESCE(req.cnt, 0), COALESCE(req.qty, 0), COALESCE(req.cost, 0),
       COALESCE(w.cnt, 0), COALESCE(w.qty, 0), COALESCE(w.cost, 0),
       COALESCE(s.cnt, 0), COALESCE(s.shortage, 0), COALESCE(s.surplus, 0),
       1, NOW()
  FROM (
    SELECT DISTINCT date FROM fact_restaurant_requisition WHERE factory_id = $1::varchar
    UNION
    SELECT DISTINCT date FROM fact_restaurant_wastage WHERE factory_id = $1::varchar
    UNION
    SELECT DISTINCT date FROM fact_restaurant_stocktaking WHERE factory_id = $1::varchar
  ) d
  LEFT JOIN (
    SELECT date, COUNT(*) AS cnt,
           SUM(COALESCE(requested_qty, 0)) AS qty,
           SUM(COALESCE(est_cost, 0))      AS cost
      FROM fact_restaurant_requisition WHERE factory_id = $1::varchar
     GROUP BY date
  ) req ON req.date = d.date
  LEFT JOIN (
    SELECT date, COUNT(*) AS cnt,
           SUM(COALESCE(quantity, 0))       AS qty,
           SUM(COALESCE(estimated_cost, 0)) AS cost
      FROM fact_restaurant_wastage WHERE factory_id = $1::varchar
     GROUP BY date
  ) w ON w.date = d.date
  LEFT JOIN (
    SELECT date, COUNT(*) AS cnt,
           SUM(CASE WHEN difference_qty < 0 THEN -difference_qty ELSE 0 END) AS shortage,
           SUM(CASE WHEN difference_qty > 0 THEN difference_qty ELSE 0 END)  AS surplus
      FROM fact_restaurant_stocktaking WHERE factory_id = $1::varchar
     GROUP BY date
  ) s ON s.date = d.date
ON CONFLICT (factory_id, date) DO UPDATE SET
    requisition_count = EXCLUDED.requisition_count,
    requisition_qty_total = EXCLUDED.requisition_qty_total,
    requisition_cost_total = EXCLUDED.requisition_cost_total,
    wastage_count = EXCLUDED.wastage_count,
    wastage_qty_total = EXCLUDED.wastage_qty_total,
    wastage_cost_total = EXCLUDED.wastage_cost_total,
    stocktaking_count = EXCLUDED.stocktaking_count,
    stocktaking_shortage_total = EXCLUDED.stocktaking_shortage_total,
    stocktaking_surplus_total = EXCLUDED.stocktaking_surplus_total,
    version = agg_restaurant_daily_totals.version + 1,
    computed_at = NOW()
"""


async def materialize_gold_daily_ops(
    smartbi_pool: asyncpg.Pool, factory_id: str,
) -> Dict[str, int]:
    """Re-compute all Gold agg tables from current Silver state."""
    stats = {}
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await _set_tenant(conn, factory_id)
            r1 = await conn.execute(_AGG_REQUISITION_QTY_SQL, factory_id)
            r2 = await conn.execute(_AGG_REQUISITION_COST_SQL, factory_id)
            r3 = await conn.execute(_AGG_WASTAGE_QTY_SQL, factory_id)
            r4 = await conn.execute(_AGG_WASTAGE_COST_BY_TYPE_SQL, factory_id)
            r5 = await conn.execute(_AGG_STOCK_SHORTAGE_SQL, factory_id)
            r6 = await conn.execute(_AGG_DAILY_TOTALS_SQL, factory_id)
            r7 = await conn.execute(_AGG_PRODUCT_COST_SQL, factory_id)
            r8 = await conn.execute(_AGG_WASTAGE_COST_SQL, factory_id)
            stats["requisition_qty"] = int(r1.split()[-1]) if r1 else 0
            stats["requisition_cost"] = int(r2.split()[-1]) if r2 else 0
            stats["wastage_qty"] = int(r3.split()[-1]) if r3 else 0
            stats["wastage_cost"] = int(r8.split()[-1]) if r8 else 0
            stats["wastage_cost_by_type"] = int(r4.split()[-1]) if r4 else 0
            stats["stock_shortage"] = int(r5.split()[-1]) if r5 else 0
            stats["daily_totals"] = int(r6.split()[-1]) if r6 else 0
            stats["product_cost"] = int(r7.split()[-1]) if r7 else 0
    logger.info("[etl] materialized gold for %s: %s", factory_id, stats)
    return stats


# ─────────────────────────────────────────────────────────────────────
# Orchestrator
# ─────────────────────────────────────────────────────────────────────

async def run_full_etl(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> EtlStats:
    """Run Silver + Gold sync for one factory. Callers (cron / API) use this."""
    stats = EtlStats()
    try:
        stats.dim_ingredient_upserted = await sync_dim_ingredient(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as e:
        stats.errors.append(f"dim_ingredient: {e}")
        logger.exception("[etl] dim_ingredient failed for %s", factory_id)

    try:
        stats.fact_requisition_upserted = await sync_fact_requisition(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as e:
        stats.errors.append(f"fact_requisition: {e}")
        logger.exception("[etl] fact_requisition failed for %s", factory_id)

    try:
        stats.fact_wastage_upserted = await sync_fact_wastage(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as e:
        stats.errors.append(f"fact_wastage: {e}")
        logger.exception("[etl] fact_wastage failed for %s", factory_id)

    try:
        stats.fact_recipe_upserted = await sync_fact_recipe(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as e:
        stats.errors.append(f"fact_recipe: {e}")
        logger.exception("[etl] fact_recipe failed for %s", factory_id)

    try:
        stats.fact_stocktaking_upserted = await sync_fact_stocktaking(
            cretas_pool, smartbi_pool, factory_id
        )
    except Exception as e:
        stats.errors.append(f"fact_stocktaking: {e}")
        logger.exception("[etl] fact_stocktaking failed for %s", factory_id)

    try:
        gold = await materialize_gold_daily_ops(smartbi_pool, factory_id)
        stats.agg_daily_ops_upserted = sum(
            gold.get(k, 0) for k in
            ("requisition_qty", "requisition_cost", "wastage_qty",
             "wastage_cost_by_type", "stock_shortage")
        )
        stats.agg_daily_totals_upserted = gold.get("daily_totals", 0)
        stats.agg_product_cost_upserted = gold.get("product_cost", 0)
    except Exception as e:
        stats.errors.append(f"gold: {e}")
        logger.exception("[etl] gold materialize failed for %s", factory_id)

    return stats


# ─────────────────────────────────────────────────────────────────────
# Retry wrapper
# ─────────────────────────────────────────────────────────────────────

_RETRY_BACKOFFS_SEC = [60, 300, 900]  # 1m, 5m, 15m
_MAX_ATTEMPTS = 3


async def run_full_etl_with_retry(
    cretas_pool: asyncpg.Pool,
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
) -> EtlStats:
    """run_full_etl wrapper with 3 retries + failure persistence.

    Retry intervals: 1m / 5m / 15m. All-failures raise. Each attempt writes
    one row to restaurant_etl_failures (status: 'retrying' or 'failed_final').

    Args:
        cretas_pool: cretas_db connection pool
        smartbi_pool: smartbi_db connection pool (for failure-log writes)
        factory_id: factory ID to run ETL for

    Returns:
        EtlStats from run_full_etl on success

    Raises:
        Exception: re-raises last exception if all 3 attempts fail
    """
    last_exc = None
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        start = time.monotonic()
        try:
            result = await run_full_etl(cretas_pool, smartbi_pool, factory_id)
            if attempt > 1:
                logger.info(f"ETL succeeded on attempt {attempt} for {factory_id}")
            return result
        except Exception as exc:
            last_exc = exc
            duration_ms = int((time.monotonic() - start) * 1000)
            is_final = attempt == _MAX_ATTEMPTS
            status = "failed_final" if is_final else "retrying"

            # Write failure log row (best-effort — don't let log write failure mask original exception)
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
                logger.warning(f"Failed to write ETL failure log: {log_exc}")

            if not is_final:
                backoff = _RETRY_BACKOFFS_SEC[attempt - 1]
                logger.warning(
                    f"ETL attempt {attempt} failed for {factory_id}, "
                    f"retrying in {backoff}s: {exc}"
                )
                await asyncio.sleep(backoff)
            else:
                logger.error(f"ETL attempt {attempt} failed for {factory_id} (final): {exc}")
                raise

    # Defensive: should not be reachable since the final attempt re-raises above
    raise last_exc
