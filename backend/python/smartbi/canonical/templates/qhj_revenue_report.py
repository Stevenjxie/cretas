"""QHJ 收入管理报表 — compute template (4 data blocks).

Spec: docs/qa-specs/2026-05-12-qhj-revenue-report-design.md §6
Plan: docs/superpowers/plans/2026-05-12-qhj-revenue-report.md Tasks E1-E5

Blocks:
  1. 可比同比 (YoY, deferred Phase 2 — returns NULL prev/ratio columns)
  2. 环比     (period over the immediately-preceding same-length range)
  3. 堂食外卖占比 (current period dine-in/takeout revenue + bill split)
  4. 客单人数分析  (per-store distribution by customer_count)

Source tables:
  Block 1/2/3: agg_daily_order_type_meal (Phase A Gold, Phase D materializer)
  Block 4:     fact_pos_transaction (Silver) JOIN fact_pos_item (Silver)
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Optional

from smartbi.canonical.templates.base import TemplateResult


# Shared SQL skeleton for Block 1/2: sum revenue per store, split by order_type.
# LEFT JOIN dim_store so zero-revenue stores still appear (UI renders 0/—).
_PERIOD_AGG_SQL = """
SELECT s.store_id, s.name AS store_name,
  COALESCE(SUM(a.actual_receive), 0)                                                       AS total,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '堂食' THEN a.actual_receive END), 0)         AS dine_in,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '外卖' THEN a.actual_receive END), 0)         AS takeout
FROM dim_store s
LEFT JOIN agg_daily_order_type_meal a
  ON a.factory_id = $1 AND a.store_id = s.store_id
 AND a.date BETWEEN $2 AND $3
 AND (CARDINALITY($4::text[]) = 0 OR a.meal_period = ANY($4))
WHERE s.factory_id = $1 AND s.store_id = ANY($5)
GROUP BY s.store_id, s.name
ORDER BY s.name
"""


# Block 3 SQL: same source/grain but separate revenue + bill_count for dine-in/takeout.
_BLOCK3_SQL = """
SELECT s.store_id, s.name AS store_name,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '堂食' THEN a.actual_receive ELSE 0 END), 0) AS dine_in_revenue,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '外卖' THEN a.actual_receive ELSE 0 END), 0) AS takeout_revenue,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '堂食' THEN a.bill_count    ELSE 0 END), 0) AS dine_in_bills,
  COALESCE(SUM(CASE WHEN TRIM(a.order_type) = '外卖' THEN a.bill_count    ELSE 0 END), 0) AS takeout_bills
FROM dim_store s
LEFT JOIN agg_daily_order_type_meal a
  ON a.factory_id = $1 AND a.store_id = s.store_id
 AND a.date BETWEEN $2 AND $3
 AND (CARDINALITY($4::text[]) = 0 OR a.meal_period = ANY($4))
WHERE s.factory_id = $1 AND s.store_id = ANY($5)
GROUP BY s.store_id, s.name
ORDER BY s.name
"""


def _pct_ratio(curr, prev) -> Optional[float]:
    """(curr - prev) / prev * 100 — None when prev is 0/NULL (UI '—')."""
    if prev is None or prev == 0:
        return None
    return round((float(curr) - float(prev)) * 100 / float(prev), 2)


@dataclass
class RevenueReportParams:
    """Inputs for compute_qhj_revenue_report.

    Spec §6.1. include_yoy was hardcoded False first phase (§11.2) because
    only one year of data existed. As of 2026-05-30 both 2025 (full) and
    2026 data are loaded, so YoY (报表区间的前一年同期) is enabled by default.
    Block 1 compares against the SAME calendar range shifted back one year:
      2026-Q1 report → prev = 2025-Q1; 2025-Q1 report → prev = 2024-Q1.
    When the prev-year range has no data (e.g. 2024 not loaded), the LEFT
    JOIN yields total=0 → _pct_ratio returns None → UI renders '—'.
    """
    factory_id: str
    store_ids: list[int]
    date_from: date
    date_to: date
    meal_periods: Optional[list[str]] = None  # None / [] = all shifts
    include_yoy: bool = True


async def compute_qhj_revenue_report(
    pool, params: RevenueReportParams,
) -> TemplateResult:
    """Compute all 4 blocks concurrently; returns single TemplateResult.

    Caller is responsible for setting tenant_ctx.set_factory_id() before
    calling — Block 1/2/3 SQL relies on app.factory_id GUC for RLS, and
    Block 4 sets it on its per-store connection acquisition.
    """
    # Block 4 fires N per-store queries concurrently; semaphore caps pool
    # contention. pool max_size is 5 in prod (per spec §11.x audit S notes),
    # so 3 leaves 2 connections for Blocks 1/2/3.
    block4_sem = asyncio.Semaphore(3)

    block1, block2, block3, block4 = await asyncio.gather(
        _compute_block1_yoy(pool, params),
        _compute_block2_mom(pool, params),
        _compute_block3_meal_split(pool, params),
        _compute_block4_diner_dist(pool, params, block4_sem),
    )

    # YoY availability: include_yoy enabled AND the prev-year same period
    # actually has data (≥1 store with a non-null prev_total > 0). When the
    # prev year (e.g. 2024) was never loaded, every prev_total is 0 → ratios
    # all None → report '—' with an honest note rather than a false claim.
    prev_year = params.date_from.year - 1
    has_prev_data = params.include_yoy and any(
        (r.get("prev_total") or 0) > 0 for r in block1
    )
    if not params.include_yoy:
        yoy_note = "同比未启用"
    elif has_prev_data:
        yoy_note = f"同比基准: {prev_year}年同期"
    else:
        yoy_note = f"缺少 {prev_year} 年同期数据,同比列显示 —"

    return TemplateResult(
        code="qhj_revenue_report",
        title="收入管理报表",
        data={
            "block1_yoy": block1,
            "block2_mom": block2,
            "block3_meal_split": block3,
            "block4_diner_dist": block4,
            "meta": {
                "date_from": params.date_from.isoformat(),
                "date_to": params.date_to.isoformat(),
                "yoy_available": has_prev_data,
                "yoy_note": yoy_note,
            },
        },
        applies=True,
    )


# ─── Block compute functions (E1: stubs; E2-E5 fill the SQL) ──────────────

async def _compute_block1_yoy(pool, params: RevenueReportParams) -> list[dict]:
    """Block 1: 可比同比 (year-over-year).

    prev period = the SAME calendar range shifted back exactly one year
    (报表区间的前一年同期). When the prev-year range has no rows the LEFT
    JOIN yields total=0, _pct_ratio returns None, and the UI renders '—'.

    include_yoy=False skips the prev-period query entirely and emits
    prev_*/ratio_* = None.
    """
    if params.include_yoy:
        prev_from = params.date_from.replace(year=params.date_from.year - 1)
        prev_to = params.date_to.replace(year=params.date_to.year - 1)
    else:
        prev_from = prev_to = None

    return await _period_compare(pool, params, prev_from, prev_to)


async def _compute_block2_mom(pool, params: RevenueReportParams) -> list[dict]:
    """Block 2: 环比 (period over immediately preceding same-length range)."""
    span_days = (params.date_to - params.date_from).days + 1
    prev_to = params.date_from - timedelta(days=1)
    prev_from = prev_to - timedelta(days=span_days - 1)
    return await _period_compare(pool, params, prev_from, prev_to)


async def _period_compare(
    pool,
    params: RevenueReportParams,
    prev_from: Optional[date],
    prev_to: Optional[date],
) -> list[dict]:
    """Shared engine for Block 1 + Block 2.

    prev_from/prev_to None → skip prev query (Block 1 first-phase YoY-disabled).
    """
    meal = list(params.meal_periods or [])
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", params.factory_id
        )
        current = await conn.fetch(
            _PERIOD_AGG_SQL,
            params.factory_id, params.date_from, params.date_to,
            meal, params.store_ids,
        )
        prev = None
        if prev_from is not None and prev_to is not None:
            prev = await conn.fetch(
                _PERIOD_AGG_SQL,
                params.factory_id, prev_from, prev_to,
                meal, params.store_ids,
            )

    if prev is None:
        return [
            {
                **dict(r),
                "prev_total": None,
                "prev_dine_in": None,
                "prev_takeout": None,
                "total_ratio": None,
                "dine_in_ratio": None,
                "takeout_ratio": None,
            }
            for r in current
        ]

    prev_map = {r["store_id"]: r for r in prev}
    result = []
    for r in current:
        p = prev_map.get(r["store_id"])
        result.append({
            **dict(r),
            "prev_total":   p["total"] if p else None,
            "prev_dine_in": p["dine_in"] if p else None,
            "prev_takeout": p["takeout"] if p else None,
            "total_ratio":   _pct_ratio(r["total"], p["total"]) if p else None,
            "dine_in_ratio": _pct_ratio(r["dine_in"], p["dine_in"]) if p else None,
            "takeout_ratio": _pct_ratio(r["takeout"], p["takeout"]) if p else None,
        })
    return result


async def _compute_block3_meal_split(pool, params: RevenueReportParams) -> list[dict]:
    """Block 3: 堂食外卖占比 — current-period snapshot, derived ratios in Python."""
    meal = list(params.meal_periods or [])
    async with pool.acquire() as conn:
        await conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", params.factory_id
        )
        rows = await conn.fetch(
            _BLOCK3_SQL,
            params.factory_id, params.date_from, params.date_to,
            meal, params.store_ids,
        )

    out = []
    for r in rows:
        total_rev = float(r["dine_in_revenue"]) + float(r["takeout_revenue"])
        total_bills = int(r["dine_in_bills"]) + int(r["takeout_bills"])
        out.append({
            **dict(r),
            "revenue_ratio": (
                round(float(r["dine_in_revenue"]) / total_rev, 4)
                if total_rev > 0 else None
            ),
            "bill_ratio": (
                round(int(r["dine_in_bills"]) / total_bills, 4)
                if total_bills > 0 else None
            ),
        })
    return out


_BLOCK4_SQL = """
WITH bill_items AS (
  SELECT t.id AS txn_id,
         t.customer_count,
         t.actual_receive,
         (SELECT COALESCE(SUM(i.qty), 0)
          FROM fact_pos_item i
          WHERE i.transaction_id = t.id) AS items_per_bill
  FROM fact_pos_transaction t
  WHERE t.factory_id = $1 AND t.store_id = $2
    AND t.date BETWEEN $3 AND $4
    AND t.customer_count IS NOT NULL AND t.customer_count > 0
    AND (CARDINALITY($5::text[]) = 0 OR TRIM(t.meal_period) = ANY($5))
),
totals AS (
  SELECT COUNT(*) AS total_bills,
         SUM(actual_receive) AS total_revenue
  FROM bill_items
)
SELECT
  bi.customer_count                                            AS diner_count,
  COUNT(*)                                                     AS bill_count,
  ROUND(COUNT(*)::numeric / NULLIF(t.total_bills, 0), 3)       AS bill_ratio,
  SUM(bi.items_per_bill)                                       AS total_items,
  ROUND(SUM(bi.items_per_bill) / NULLIF(COUNT(*), 0), 1)       AS avg_items_per_bill,
  SUM(bi.actual_receive)                                       AS revenue,
  -- 实际人均 v1: 实收额 / 客流量 (= 实收 / (customer_count × bill_count))
  ROUND(SUM(bi.actual_receive) /
        NULLIF(bi.customer_count * COUNT(*), 0), 0)            AS revenue_per_diner,
  -- 实际人均 v2: 实收额 / 点单份数
  ROUND(SUM(bi.actual_receive) /
        NULLIF(SUM(bi.items_per_bill), 0), 0)                  AS revenue_per_item,
  ROUND(SUM(bi.actual_receive) /
        NULLIF(t.total_revenue, 0), 3)                         AS revenue_ratio
FROM bill_items bi
CROSS JOIN totals t
GROUP BY bi.customer_count, t.total_bills, t.total_revenue
ORDER BY bi.customer_count
"""


async def _compute_block4_diner_dist(
    pool, params: RevenueReportParams, sem: asyncio.Semaphore,
) -> list[dict]:
    """Block 4: 客单人数分析 (per-store distribution by customer_count).

    Fires N concurrent per-store queries under the shared semaphore to
    cap pool contention. Each query joins fact_pos_transaction with the
    per-bill item subquery from fact_pos_item.
    """
    meal = list(params.meal_periods or [])

    async def one_store(store_id: int) -> dict:
        async with sem:
            async with pool.acquire() as conn:
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, false)",
                    params.factory_id,
                )
                store_name = await conn.fetchval(
                    "SELECT name FROM dim_store "
                    "WHERE factory_id = $1 AND store_id = $2",
                    params.factory_id, store_id,
                )
                rows = await conn.fetch(
                    _BLOCK4_SQL,
                    params.factory_id, store_id,
                    params.date_from, params.date_to, meal,
                )
        return {
            "store_id": store_id,
            "store_name": store_name or f"store_{store_id}",
            "date_range": f"{params.date_from} ~ {params.date_to}",
            "distribution": [dict(r) for r in rows],
        }

    return await asyncio.gather(*(one_store(sid) for sid in params.store_ids))
