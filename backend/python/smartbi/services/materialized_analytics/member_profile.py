"""会员储值 + 画像 — agg_member_tier / agg_member_birth_month /
agg_member_recharge_daily Gold aggregators.

New restaurant analytics dimension: member stored-value + demographic
profile. NOT full RFM — see gold/queries.py member_profile()'s docstring
for why (this data source has no per-member consumption event).

Sources:
  - dim_member (Silver, snapshot grain, V20261006_01)
  - fact_member_recharge (Silver, daily event grain, V20261006_01)
Targets:
  - agg_member_tier (Gold, PK (factory_id, store_id, tier))
  - agg_member_birth_month (Gold, PK (factory_id, birth_month))
  - agg_member_recharge_daily (Gold, PK (factory_id, date, store_id, channel))

Mirrors smartbi/services/materialized_analytics/daily_void.py's structure.

⛔ CLOBBER-SAFETY: these materializers touch ONLY the three member_* Gold
tables listed above. They must NEVER be called as a side effect of another
aggregate's materialization (and the member demo-data loader,
load_member_demo_rest.py, deliberately does NOT call them — see that
script's module docstring).
"""
from __future__ import annotations

from datetime import date

import asyncpg


_AGG_MEMBER_TIER_UPSERT_SQL = """
INSERT INTO agg_member_tier AS a (
    factory_id, store_id, tier, member_count, total_balance,
    version, computed_at
)
SELECT
    m.factory_id,
    m.issue_store_id AS store_id,
    m.tier,
    COUNT(*)          AS member_count,
    SUM(m.balance)    AS total_balance,
    1, NOW()
FROM dim_member m
WHERE m.factory_id = $1
GROUP BY m.factory_id, m.issue_store_id, m.tier
ON CONFLICT (factory_id, store_id, tier)
DO UPDATE SET
    member_count  = EXCLUDED.member_count,
    total_balance = EXCLUDED.total_balance,
    version       = a.version + 1,
    computed_at   = NOW();
"""

_AGG_MEMBER_BIRTH_MONTH_UPSERT_SQL = """
INSERT INTO agg_member_birth_month AS a (
    factory_id, birth_month, member_count, version, computed_at
)
SELECT
    m.factory_id,
    COALESCE(m.birth_month, 0) AS birth_month,
    COUNT(*)                    AS member_count,
    1, NOW()
FROM dim_member m
WHERE m.factory_id = $1
GROUP BY m.factory_id, COALESCE(m.birth_month, 0)
ON CONFLICT (factory_id, birth_month)
DO UPDATE SET
    member_count = EXCLUDED.member_count,
    version      = a.version + 1,
    computed_at  = NOW();
"""

_AGG_MEMBER_RECHARGE_DAILY_UPSERT_SQL = """
INSERT INTO agg_member_recharge_daily AS a (
    factory_id, date, store_id, channel, recharge_count, principal, bonus,
    version, computed_at
)
SELECT
    r.factory_id,
    r.recharge_date AS date,
    r.store_id,
    COALESCE(NULLIF(TRIM(r.channel), ''), '未标注') AS channel,
    SUM(r.recharge_count)                            AS recharge_count,
    SUM(r.principal)                                 AS principal,
    SUM(r.bonus)                                      AS bonus,
    1, NOW()
FROM fact_member_recharge r
WHERE r.factory_id = $1
  AND r.recharge_date BETWEEN $2 AND $3
GROUP BY r.factory_id, r.recharge_date, r.store_id,
         COALESCE(NULLIF(TRIM(r.channel), ''), '未标注')
ON CONFLICT (factory_id, date, store_id, channel)
DO UPDATE SET
    recharge_count = EXCLUDED.recharge_count,
    principal      = EXCLUDED.principal,
    bonus          = EXCLUDED.bonus,
    version        = a.version + 1,
    computed_at    = NOW();
"""


def _rows_affected(status: str) -> int:
    """PG returns 'INSERT 0 N' where N is rows affected (incl. ON CONFLICT updates)."""
    parts = status.split()
    return int(parts[-1]) if parts and parts[-1].isdigit() else 0


async def materialize_member_tier_profile(pool: asyncpg.Pool, factory_id: str) -> int:
    """Upsert agg_member_tier + agg_member_birth_month from dim_member.

    Snapshot aggregator — no date range. dim_member is a current-state
    snapshot per card (mirrors the source 卡详情一览 report, which has no
    per-row event date), so re-running this always recomputes the FULL
    tenant snapshot (unlike the date-windowed void/recharge materializers).

    Args:
        pool: asyncpg pool. Caller is responsible for any worktree-level locking.
        factory_id: tenant ID; sets app.factory_id GUC so RLS allows the INSERT.

    Returns:
        Total rows affected across both UPSERTs (parsed from PG command tags).
    """
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        tier_status = await conn.execute(_AGG_MEMBER_TIER_UPSERT_SQL, factory_id)
        birth_status = await conn.execute(_AGG_MEMBER_BIRTH_MONTH_UPSERT_SQL, factory_id)
    return _rows_affected(tier_status) + _rows_affected(birth_status)


async def materialize_member_recharge_daily(
    pool: asyncpg.Pool,
    factory_id: str,
    date_min: date,
    date_max: date,
) -> int:
    """Upsert agg_member_recharge_daily from fact_member_recharge.

    Args:
        pool: asyncpg pool. Caller is responsible for any worktree-level locking.
        factory_id: tenant ID; sets app.factory_id GUC so RLS allows the INSERT.
        date_min: inclusive lower bound of the source-data window (by recharge date).
        date_max: inclusive upper bound.

    Returns:
        Number of rows affected by the UPSERT (parsed from PG command tag).
    """
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        status = await conn.execute(
            _AGG_MEMBER_RECHARGE_DAILY_UPSERT_SQL,
            factory_id, date_min, date_max,
        )
    return _rows_affected(status)
