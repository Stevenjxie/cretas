"""撤单率 / 撤单稽核 — agg_daily_void Gold aggregator.

New restaurant analytics dimension: order void rate + staff audit.

Source: fact_pos_void (Silver void-event grain, V20261005_01)
Target: agg_daily_void (Gold, PK (factory_id, date, store_id, staff_id, void_reason))

Mirrors smartbi/services/materialized_analytics/daily_order_type_meal.py's
structure. staff_id NULL -> 0 sentinel, void_reason NULL/blank -> '未标注' —
see V20261005_02__agg_daily_void.sql's module comment for why (PK columns
can't be NULL; no dim_staff FK on this Gold table by design).

⛔ CLOBBER-SAFETY: this materializer touches ONLY agg_daily_void. It must
NEVER be called as a side effect of another aggregate's materialization
(and the void demo-data loader, load_void_demo_rest.py, deliberately does
NOT call this — see that script's module docstring).
"""
from __future__ import annotations

from datetime import date

import asyncpg


_AGG_DAILY_VOID_UPSERT_SQL = """
INSERT INTO agg_daily_void AS a (
    factory_id, date, store_id, staff_id, void_reason, void_count,
    version, computed_at
)
SELECT
    v.factory_id,
    v.date,
    v.store_id,
    COALESCE(v.staff_id, 0)                                AS staff_id,
    COALESCE(NULLIF(TRIM(v.void_reason), ''), '未标注')     AS void_reason,
    COUNT(*)                                                AS void_count,
    1, NOW()
FROM fact_pos_void v
WHERE v.factory_id = $1
  AND v.date BETWEEN $2 AND $3
GROUP BY v.factory_id, v.date, v.store_id,
         COALESCE(v.staff_id, 0),
         COALESCE(NULLIF(TRIM(v.void_reason), ''), '未标注')
ON CONFLICT (factory_id, date, store_id, staff_id, void_reason)
DO UPDATE SET
    void_count  = EXCLUDED.void_count,
    version     = a.version + 1,
    computed_at = NOW();
"""


async def materialize_daily_void(
    pool: asyncpg.Pool,
    factory_id: str,
    date_min: date,
    date_max: date,
) -> int:
    """Upsert agg_daily_void from fact_pos_void.

    Args:
        pool: asyncpg pool. Caller is responsible for any worktree-level locking.
        factory_id: tenant ID; sets app.factory_id GUC so RLS allows the INSERT.
        date_min: inclusive lower bound of the source-data window (by void date).
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
            _AGG_DAILY_VOID_UPSERT_SQL,
            factory_id, date_min, date_max,
        )
    # PG returns 'INSERT 0 N' where N is rows affected (incl. ON CONFLICT updates).
    parts = status.split()
    return int(parts[-1]) if parts and parts[-1].isdigit() else 0
