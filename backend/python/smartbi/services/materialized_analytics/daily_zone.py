"""区域坪效 — agg_daily_zone Gold aggregator.

New restaurant analytics dimension: in-store dining-zone revenue/efficiency.

Source: fact_zone_sales (Silver line grain, V20261006_01)
Target: agg_daily_zone (Gold, PK (factory_id, date, store_id, zone_name))

Mirrors smartbi/services/materialized_analytics/daily_void.py's structure.
zone_name NULL/blank -> '未分区' sentinel — see
V20261006_02__agg_daily_zone.sql's module comment for why (PK columns can't
be NULL).

⛔ CLOBBER-SAFETY: this materializer touches ONLY agg_daily_zone. It must
NEVER be called as a side effect of another aggregate's materialization
(and the zone demo-data loader, load_zone_demo_rest.py, deliberately does
NOT call this — see that script's module docstring).
"""
from __future__ import annotations

from datetime import date

import asyncpg


_AGG_DAILY_ZONE_UPSERT_SQL = """
INSERT INTO agg_daily_zone AS a (
    factory_id, date, store_id, zone_name, revenue, item_qty, line_count,
    version, computed_at
)
SELECT
    z.factory_id,
    z.date,
    z.store_id,
    COALESCE(NULLIF(TRIM(z.zone_name), ''), '未分区')       AS zone_name,
    COALESCE(SUM(z.amount_after_discount), 0)                AS revenue,
    COALESCE(SUM(z.quantity), 0)                             AS item_qty,
    COUNT(*)                                                 AS line_count,
    1, NOW()
FROM fact_zone_sales z
WHERE z.factory_id = $1
  AND z.date BETWEEN $2 AND $3
GROUP BY z.factory_id, z.date, z.store_id,
         COALESCE(NULLIF(TRIM(z.zone_name), ''), '未分区')
ON CONFLICT (factory_id, date, store_id, zone_name)
DO UPDATE SET
    revenue     = EXCLUDED.revenue,
    item_qty    = EXCLUDED.item_qty,
    line_count  = EXCLUDED.line_count,
    version     = a.version + 1,
    computed_at = NOW();
"""


async def materialize_daily_zone(
    pool: asyncpg.Pool,
    factory_id: str,
    date_min: date,
    date_max: date,
) -> int:
    """Upsert agg_daily_zone from fact_zone_sales.

    Args:
        pool: asyncpg pool. Caller is responsible for any worktree-level locking.
        factory_id: tenant ID; sets app.factory_id GUC so RLS allows the INSERT.
        date_min: inclusive lower bound of the source-data window (by sales date).
        date_max: inclusive upper bound.

    Returns:
        Number of rows affected by the UPSERT (parsed from PG command tag).
    """
    async with pool.acquire() as conn:
        # RLS requires app.factory_id set; same pattern as backfill_silver and
        # other Gold aggregators. Session-scoped (false) so it survives in this
        # acquired connection but doesn't leak to other pool users.
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        status = await conn.execute(
            _AGG_DAILY_ZONE_UPSERT_SQL,
            factory_id, date_min, date_max,
        )
    # PG returns 'INSERT 0 N' where N is rows affected (incl. ON CONFLICT updates).
    parts = status.split()
    return int(parts[-1]) if parts and parts[-1].isdigit() else 0
