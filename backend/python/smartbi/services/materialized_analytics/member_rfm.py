"""会员 RFM 分析 — agg_member_rfm_segment / agg_member_rfm_tier /
agg_member_lifecycle Gold aggregators. CRM P0.

Full RFM (Recency/Frequency/Monetary) — unlike member_profile.py's member
stored-value + demographic dimension, this one's source (卡消费排行) IS
per-card cumulative-consumption data, so R/F/M can be computed directly.

Source:
  - fact_member_consumption (Silver, snapshot grain, V20261008_01)
Targets:
  - agg_member_rfm_segment (Gold, PK (factory_id, r_score, f_score, m_score))
  - agg_member_rfm_tier    (Gold, PK (factory_id, rfm_tier))
  - agg_member_lifecycle   (Gold, PK (factory_id, lifecycle_stage))

Mirrors smartbi/services/materialized_analytics/member_profile.py's
structure (module-level SQL UPSERT constants + one async materialize_*
entrypoint that sets the RLS GUC then executes each UPSERT via conn.execute,
summing PG's "INSERT 0 N" row-count tags).

⛔ CLOBBER-SAFETY: this materializer touches ONLY the three agg_member_rfm_*
/ agg_member_lifecycle Gold tables listed above. It must NEVER be called as
a side effect of another aggregate's materialization, and the RFM demo-data
loader (load_member_rfm_demo_rest.py) deliberately does NOT call it — see
that script's module docstring.

⚠️ RECENCY IS RECOMPUTED AT MATERIALIZATION TIME, NOT READ FROM THE SOURCE.
fact_member_consumption.spend_interval_days is the POS's own "days since
last consumption" computed AS OF EXPORT TIME (~2026-04-22 for the 有滋有味
export this pipeline was built against) — that number goes stale the moment
"today" moves on. Every SQL statement below instead computes
`(CURRENT_DATE - last_spend_date::date)` fresh, so re-running this
materializer (even with zero new Silver rows) correctly ages members
forward from 活跃 -> 沉睡 -> 流失 as real time passes — this is what makes
the 有滋有味 cohort's genuinely-old (2025-11..2026-04) 最近消费日期 values
produce an honest, non-fabricated dormant/churn distribution instead of a
frozen snapshot that slowly becomes wrong.
"""
from __future__ import annotations

import asyncpg


# ── agg_member_rfm_segment: raw (r,f,m) quintile-bucket grid ──────────────
#
# NTILE(5) OVER (...) assigns each card a 1-5 bucket per dimension:
#   r_score: bucket 1 = oldest last_spend_date (least recent) ... 5 = most recent
#   f_score: bucket 1 = fewest spend_count ... 5 = most
#   m_score: bucket 1 = smallest cum_spend ... 5 = largest
# `card_no` is a secondary ORDER BY key purely for deterministic bucket
# assignment on ties (doesn't change the bucket *counts*, only which
# specific tied cards land in adjoining buckets).
_AGG_MEMBER_RFM_SEGMENT_UPSERT_SQL = """
WITH scored AS (
    SELECT
        card_no,
        cum_spend,
        NTILE(5) OVER (ORDER BY last_spend_date ASC, card_no) AS r_score,
        NTILE(5) OVER (ORDER BY spend_count ASC, card_no)     AS f_score,
        NTILE(5) OVER (ORDER BY cum_spend ASC, card_no)       AS m_score
    FROM fact_member_consumption
    WHERE factory_id = $1
)
INSERT INTO agg_member_rfm_segment AS a (
    factory_id, r_score, f_score, m_score, member_count, avg_cum_spend,
    version, computed_at
)
SELECT
    $1, r_score, f_score, m_score,
    COUNT(*)       AS member_count,
    AVG(cum_spend) AS avg_cum_spend,
    1, NOW()
FROM scored
GROUP BY r_score, f_score, m_score
ON CONFLICT (factory_id, r_score, f_score, m_score)
DO UPDATE SET
    member_count  = EXCLUDED.member_count,
    avg_cum_spend = EXCLUDED.avg_cum_spend,
    version       = a.version + 1,
    computed_at   = NOW();
"""

# ── agg_member_rfm_tier: (r,f,m) -> 7-label business tier ─────────────────
#
# Classification rule mirrors smartbi/services/restaurant/member_rfm.py's
# MemberRfmAnalyzer._classify_segment EXACTLY (same thresholds, same
# precedence order) for naming consistency across the two independent RFM
# implementations in this codebase (that one operates on ad-hoc in-memory
# POS-order lists; this one operates on the durable Gold snapshot).
_AGG_MEMBER_RFM_TIER_UPSERT_SQL = """
WITH scored AS (
    SELECT
        card_no,
        cum_spend,
        (CURRENT_DATE - last_spend_date::date) AS interval_days,
        NTILE(5) OVER (ORDER BY last_spend_date ASC, card_no) AS r_score,
        NTILE(5) OVER (ORDER BY spend_count ASC, card_no)     AS f_score,
        NTILE(5) OVER (ORDER BY cum_spend ASC, card_no)       AS m_score
    FROM fact_member_consumption
    WHERE factory_id = $1
),
tiered AS (
    SELECT
        *,
        CASE
            WHEN r_score >= 4 AND f_score >= 4 AND m_score >= 4 THEN 'Champions'
            WHEN r_score >= 4 AND f_score <= 2                   THEN 'New'
            WHEN f_score >= 4 AND r_score >= 3                   THEN 'Loyal'
            WHEN r_score >= 3 AND f_score >= 3                   THEN 'Potential'
            WHEN r_score <= 2 AND (f_score >= 3 OR m_score >= 3)  THEN 'At Risk'
            WHEN r_score <= 1 AND f_score <= 2 AND m_score <= 2   THEN 'Lost'
            ELSE 'Hibernating'
        END AS rfm_tier
    FROM scored
)
INSERT INTO agg_member_rfm_tier AS a (
    factory_id, rfm_tier, member_count, total_cum_spend, avg_spend_interval,
    version, computed_at
)
SELECT
    $1, rfm_tier,
    COUNT(*)           AS member_count,
    SUM(cum_spend)     AS total_cum_spend,
    AVG(interval_days) AS avg_spend_interval,
    1, NOW()
FROM tiered
GROUP BY rfm_tier
ON CONFLICT (factory_id, rfm_tier)
DO UPDATE SET
    member_count       = EXCLUDED.member_count,
    total_cum_spend    = EXCLUDED.total_cum_spend,
    avg_spend_interval = EXCLUDED.avg_spend_interval,
    version            = a.version + 1,
    computed_at        = NOW();
"""

# ── agg_member_lifecycle: recency-only bucketing ───────────────────────────
#
# Priority order (first match wins, mirrors the CASE below):
#   1. 未消费 — spend_count = 0 (defensive; 卡消费排行 is a "consumption
#      ranking" report so this is expected empty in practice, not assumed
#      impossible)
#   2. 新客   — card issued within the last 30 days (regardless of interval —
#      a brand-new member shouldn't be labeled 沉睡/流失 just because they
#      haven't had time to build up frequency yet)
#   3. 流失   — > 180 days since last spend
#   4. 沉睡   — 90-180 days since last spend
#   5. 活跃   — everything else (<= 90 days, not new)
_AGG_MEMBER_LIFECYCLE_UPSERT_SQL = """
WITH staged AS (
    SELECT
        card_no,
        total_balance,
        spend_count,
        issue_date,
        (CURRENT_DATE - last_spend_date::date) AS interval_days
    FROM fact_member_consumption
    WHERE factory_id = $1
),
classified AS (
    SELECT
        *,
        CASE
            WHEN spend_count = 0                   THEN '未消费'
            WHEN issue_date >= (CURRENT_DATE - 30)  THEN '新客'
            WHEN interval_days > 180                THEN '流失'
            WHEN interval_days > 90                 THEN '沉睡'
            ELSE '活跃'
        END AS lifecycle_stage
    FROM staged
)
INSERT INTO agg_member_lifecycle AS a (
    factory_id, lifecycle_stage, member_count, total_balance,
    version, computed_at
)
SELECT
    $1, lifecycle_stage,
    COUNT(*)          AS member_count,
    SUM(total_balance) AS total_balance,
    1, NOW()
FROM classified
GROUP BY lifecycle_stage
ON CONFLICT (factory_id, lifecycle_stage)
DO UPDATE SET
    member_count  = EXCLUDED.member_count,
    total_balance = EXCLUDED.total_balance,
    version       = a.version + 1,
    computed_at   = NOW();
"""


def _rows_affected(status: str) -> int:
    """PG returns 'INSERT 0 N' where N is rows affected (incl. ON CONFLICT updates)."""
    parts = status.split()
    return int(parts[-1]) if parts and parts[-1].isdigit() else 0


async def materialize_member_rfm(pool: asyncpg.Pool, factory_id: str) -> int:
    """Upsert agg_member_rfm_segment + agg_member_rfm_tier +
    agg_member_lifecycle from fact_member_consumption.

    Snapshot aggregator — no date range (fact_member_consumption is a
    current-state snapshot per card, mirroring the source 卡消费排行 report,
    which has no per-row event date to bucket by; only the cumulative
    R/F/M fields as of export time). Re-running this always recomputes the
    FULL tenant snapshot, and recency-derived fields (r_score,
    avg_spend_interval, lifecycle_stage) are computed fresh against
    CURRENT_DATE every run — see module docstring.

    Args:
        pool: asyncpg pool. Caller is responsible for any worktree-level locking.
        factory_id: tenant ID; sets app.factory_id GUC so RLS allows the INSERT.

    Returns:
        Total rows affected across all three UPSERTs (parsed from PG command tags).
    """
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id
        )
        segment_status = await conn.execute(_AGG_MEMBER_RFM_SEGMENT_UPSERT_SQL, factory_id)
        tier_status = await conn.execute(_AGG_MEMBER_RFM_TIER_UPSERT_SQL, factory_id)
        lifecycle_status = await conn.execute(_AGG_MEMBER_LIFECYCLE_UPSERT_SQL, factory_id)
    return (
        _rows_affected(segment_status)
        + _rows_affected(tier_status)
        + _rows_affected(lifecycle_status)
    )
