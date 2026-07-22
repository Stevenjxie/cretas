"""#58 Phase 2 — 餐饮毛利 (margin) 计算 / 预测 / pace 预警.

margin = 营收 (POS net_amount) − 食材成本 (COGS).
COGS = Σ (POS 售出数量 × agg_restaurant_product_cost.food_cost), 按工厂/门店/期间聚合,
       复用 #61 已生产验证的 restaurant_finance_etl.sync_cost_from_pos_recipe (Stage 3)
       name-resolution join 链路 (POS dim_product.normalized_name → cretas product_types.id
       / dim_product_alias → product_source_pk → food_cost)。

诚实降级 (#57 ↔ #61 依赖): 若某租户 #61 菜名解析未跑 / 配方未配价 → COGS 严重偏低 →
毛利虚高。两个判据都满足才认为成本数据足够:
  - priced_dish_count >= MIN_PRICED_DISH_COUNT (3)
  - revenue_coverage  >= MIN_REVENUE_COVERAGE (0.80)
不足 → 毛利/COGS 金额全部 None (绝不返回假 0 / 虚高毛利) + honest message 带覆盖数字。

复用 P1:
  - target_forecast.compute_rolling_forecast (线性趋势 + 80% CI)。
  - target_decomposition.compute_pace_alert (周期进度 vs 时间进度)。

Rule 引用 (.claude/rules/python-java-port.md):
  - Rule 1: None 判空用 `is not None`, 不用 Python falsy `or`。
  - Rule 4/10/12: 金额 Decimal, 中间步 scale 4 HALF_UP, 输出 scale 2。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Dict, List, Optional, Tuple

import asyncpg

from smartbi.gold.queries import _period_bounds, _period_key_for_target
from smartbi.services.target_decomposition import compute_pace_alert
from smartbi.services.target_forecast import (
    _fetch_daily_revenue,
    compute_rolling_forecast,
)

logger = logging.getLogger(__name__)

# Default target margin rate (55%) — mirrored in V20260925_01 DEFAULT 0.5500.
DEFAULT_TARGET_MARGIN_RATE = Decimal("0.55")

# Graceful-degradation thresholds (#61 dependency). BOTH must hold.
# 0.80 (not 0.50): below this too much period revenue is uncosted (COGS=0 for unpriced
# dishes) → the pace margin is materially overstated. 0.80 bounds the uncovered-revenue
# (overstate) fraction to <=20% (review #58-P2 IMPORTANT-1). Forecast path is additionally
# IQR-cleaned; pace path sums COGS directly, so the gate is what protects it.
MIN_PRICED_DISH_COUNT = 3
MIN_REVENUE_COVERAGE = 0.80

_Q4 = Decimal("0.0001")


def _q4(v: Decimal) -> Decimal:
    return v.quantize(_Q4, rounding=ROUND_HALF_UP)


# ── Coverage diagnostic ───────────────────────────────────────────────────────
@dataclass
class CogsCoverage:
    """How completely the period's POS revenue maps to a priced cost row.

    - total_dish_count    : distinct POS dish names sold in the period.
    - resolved_dish_count : names that resolved to a cretas product_types.id.
    - priced_dish_count   : resolved names that also have a food_cost > 0
                            (has_price_data, ingredient unit prices configured).
    - period_revenue      : total POS net_amount in the period.
    - priced_revenue      : POS item revenue attributable to a priced dish.
    """

    total_dish_count: int
    resolved_dish_count: int
    priced_dish_count: int
    period_revenue: Decimal
    priced_revenue: Decimal

    @property
    def revenue_coverage(self) -> float:
        if self.period_revenue is None or self.period_revenue <= 0:
            return 0.0
        return float(self.priced_revenue / self.period_revenue)


def assess_cost_coverage(coverage: CogsCoverage) -> bool:
    """True iff cost data is dense enough to trust the computed margin.

    BOTH thresholds must hold (priced-dish count AND revenue coverage). Either
    falling short → margin is unreliable → caller returns honest null margin.
    """
    if coverage.priced_dish_count < MIN_PRICED_DISH_COUNT:
        return False
    if coverage.revenue_coverage < MIN_REVENUE_COVERAGE:
        return False
    return True


# ── COGS rollup (pure) ────────────────────────────────────────────────────────
def compute_cogs_from_rows(
    pos_rows: List[Dict[str, Any]],
    name_to_pk: Dict[str, str],
    cost_by_pk: Dict[str, Decimal],
) -> Decimal:
    """Σ(qty × food_cost) over resolvable, priced dishes (mirrors ETL Stage 4).

    pos_rows: [{normalized_name, total_qty}, ...] (already grouped by name).
    Unresolved names (no pk), or pk with no/zero food_cost, contribute nothing
    (NOT a fake 0 row — they are simply excluded; the caller's coverage
    diagnostic flags whether enough was covered).
    """
    total = Decimal("0")
    for r in pos_rows:
        pk = name_to_pk.get(r["normalized_name"])
        if pk is None:
            continue
        food_cost = cost_by_pk.get(pk)
        if food_cost is None or food_cost <= 0:
            continue
        total += food_cost * r["total_qty"]
    return total


# ── DB: per-day COGS + coverage ───────────────────────────────────────────────
async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)", factory_id
    )


async def _fetch_pos_dish_daily(
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    start: date,
    end: date,
    store_id: Optional[int],
) -> List[Dict[str, Any]]:
    """POS dish lines in range, aggregated by (date, normalized_name).

    Mirrors restaurant_finance_etl.sync_cost_from_pos_recipe Step 1, with an
    optional store filter.
    """
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        if store_id is None:
            rows = await conn.fetch(
                """
                SELECT t.date AS record_date,
                       p.normalized_name,
                       SUM(i.qty)::numeric(18,3)    AS total_qty,
                       SUM(i.amount)::numeric(18,2) AS total_amount
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
                factory_id, start, end,
            )
        else:
            rows = await conn.fetch(
                """
                SELECT t.date AS record_date,
                       p.normalized_name,
                       SUM(i.qty)::numeric(18,3)    AS total_qty,
                       SUM(i.amount)::numeric(18,2) AS total_amount
                  FROM fact_pos_item i
                  JOIN fact_pos_transaction t ON t.id = i.transaction_id
                  JOIN dim_product p ON p.product_id = i.product_id
                 WHERE i.factory_id = $1::varchar
                   AND t.factory_id = $1::varchar
                   AND p.factory_id = $1::varchar
                   AND t.date BETWEEN $2::date AND $3::date
                   AND t.store_id = $4
                   AND i.product_id IS NOT NULL
                 GROUP BY t.date, p.normalized_name
                """,
                factory_id, start, end, store_id,
            )
    return [dict(r) for r in rows]


async def _resolve_names_to_pk(
    cretas_pool: asyncpg.Pool,
    factory_id: str,
    distinct_names: List[str],
) -> Dict[str, str]:
    """normalized_name → cretas product_types.id (+ dim_product_alias fallback).

    Mirrors restaurant_finance_etl.sync_cost_from_pos_recipe Step 2 verbatim.
    """
    name_to_pk: Dict[str, str] = {}
    if not distinct_names:
        return name_to_pk
    async with cretas_pool.acquire() as cretas:
        name_rows = await cretas.fetch(
            "SELECT id, name FROM product_types "
            "WHERE factory_id = $1 AND name = ANY($2::text[]) "
            "AND deleted_at IS NULL",
            factory_id, distinct_names,
        )
        for r in name_rows:
            name_to_pk[r["name"]] = r["id"]

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
            except Exception as e:  # alias table may not exist on older schemas
                if "does not exist" not in str(e):
                    logger.warning("[margin] alias lookup failed: %s", e)
    return name_to_pk


async def _fetch_food_cost(
    smartbi_pool: asyncpg.Pool,
    factory_id: str,
    pks: List[str],
) -> Tuple[Dict[str, Decimal], set]:
    """food_cost per product_source_pk (only has_price_data rows count as priced).

    Returns (cost_by_pk, priced_pk_set). cost_by_pk holds every cost row's
    food_cost; priced_pk_set is the subset with has_price_data = TRUE AND
    food_cost > 0 (the dishes we can actually trust a COGS contribution from).
    """
    cost_by_pk: Dict[str, Decimal] = {}
    priced: set = set()
    if not pks:
        return cost_by_pk, priced
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        rows = await conn.fetch(
            """
            SELECT product_source_pk,
                   food_cost::numeric(14,4) AS food_cost,
                   has_price_data
              FROM agg_restaurant_product_cost
             WHERE factory_id = $1::varchar
               AND product_source_pk = ANY($2::text[])
            """,
            factory_id, pks,
        )
    for r in rows:
        fc = r["food_cost"]
        cost_by_pk[r["product_source_pk"]] = fc
        if r["has_price_data"] and fc is not None and fc > 0:
            priced.add(r["product_source_pk"])
    return cost_by_pk, priced


async def fetch_period_cogs(
    smartbi_pool: asyncpg.Pool,
    cretas_pool: asyncpg.Pool,
    factory_id: str,
    start: date,
    end: date,
    store_id: Optional[int] = None,
) -> Tuple[Dict[date, Decimal], CogsCoverage]:
    """Per-day COGS over a span + a coverage diagnostic.

    Returns ({date: cogs_decimal}, CogsCoverage). COGS only counts dishes that
    resolve to a priced cost row; the coverage diagnostic reports how much of the
    period's POS revenue that covers (the #61-dependency honesty signal).
    """
    pos_rows = await _fetch_pos_dish_daily(
        smartbi_pool, factory_id, start, end, store_id
    )
    distinct_names = list({r["normalized_name"] for r in pos_rows})
    period_revenue = sum(
        (r["total_amount"] for r in pos_rows if r["total_amount"] is not None),
        Decimal("0"),
    )

    if not pos_rows:
        return {}, CogsCoverage(0, 0, 0, Decimal("0"), Decimal("0"))

    name_to_pk = await _resolve_names_to_pk(cretas_pool, factory_id, distinct_names)
    cost_by_pk, priced_pks = await _fetch_food_cost(
        smartbi_pool, factory_id, list(name_to_pk.values())
    )

    # priced names = resolved names whose pk is in priced_pks.
    priced_names = {
        n for n in distinct_names
        if name_to_pk.get(n) in priced_pks
    }
    priced_revenue = sum(
        (r["total_amount"] for r in pos_rows
         if r["normalized_name"] in priced_names and r["total_amount"] is not None),
        Decimal("0"),
    )

    coverage = CogsCoverage(
        total_dish_count=len(distinct_names),
        resolved_dish_count=sum(1 for n in distinct_names if n in name_to_pk),
        priced_dish_count=len(priced_names),
        period_revenue=period_revenue,
        priced_revenue=priced_revenue,
    )

    # per-day COGS: only priced dishes contribute (compute_cogs_from_rows skips
    # unresolved + zero/None food_cost; here we also restrict cost_by_pk to priced).
    priced_cost_by_pk = {pk: cost_by_pk[pk] for pk in priced_pks}
    by_day: Dict[date, List[Dict[str, Any]]] = {}
    for r in pos_rows:
        by_day.setdefault(r["record_date"], []).append(r)
    daily: Dict[date, Decimal] = {}
    for d, rows in by_day.items():
        c = compute_cogs_from_rows(rows, name_to_pk, priced_cost_by_pk)
        if c > 0:
            daily[d] = c
    return daily, coverage


def _insufficient_message(coverage: CogsCoverage) -> str:
    pct = round(coverage.revenue_coverage * 100)
    return (
        f"成本数据不足：仅 {coverage.priced_dish_count} 道菜已配价，"
        f"覆盖 {pct}% 营收。请在「配方管理」补全配方单价，"
        f"或在「菜品名称匹配」裁决未解析菜名后重试。"
    )


def _coverage_block(coverage: CogsCoverage) -> Dict[str, Any]:
    return {
        "total_dish_count": coverage.total_dish_count,
        "resolved_dish_count": coverage.resolved_dish_count,
        "priced_dish_count": coverage.priced_dish_count,
        "revenue_coverage": round(coverage.revenue_coverage, 4),
    }


# ── Margin forecast ───────────────────────────────────────────────────────────
def compute_margin_forecast_core(
    revenue_series: List[Tuple[date, float]],
    daily_cogs: Dict[date, Decimal],
    *,
    anchor: date,
    horizon_days: int = 30,
    window_days: int = 90,
) -> Dict[str, Any]:
    """Build a per-day margin series (revenue − cogs) → compute_rolling_forecast.

    A day with revenue but no COGS entry contributes margin == revenue (cogs 0),
    which is the honest interpretation when that day's dishes were all
    unresolved/unpriced — the upstream coverage gate decides whether the whole
    period's margin is trustworthy; this core is the pure series math.
    """
    margin_series: List[Tuple[date, float]] = []
    for d, rev in revenue_series:
        cogs = daily_cogs.get(d, Decimal("0"))
        margin = Decimal(str(rev)) - cogs
        margin_series.append((d, float(margin)))
    return compute_rolling_forecast(
        margin_series, anchor=anchor, horizon_days=horizon_days,
        window_days=window_days,
    )


async def compute_margin_forecast(
    smartbi_pool: asyncpg.Pool,
    cretas_pool: asyncpg.Pool,
    factory_id: str,
    *,
    horizon_days: int = 30,
    window_days: int = 90,
    store_id: Optional[int] = None,
    anchor: Optional[date] = None,
) -> Dict[str, Any]:
    """Rolling margin forecast with honest cost-coverage gating.

    Resolves anchor to the latest agg_daily date (mirrors P1 forecast_revenue).
    Computes COGS over the fit window, gates on coverage; insufficient →
    model_type='cost_data_insufficient', points=[], honest message + coverage.
    """
    if not factory_id:
        raise ValueError("factory_id required")
    from datetime import timedelta

    # Resolve anchor to latest data date (parity with P1).
    if anchor is None:
        async with smartbi_pool.acquire() as conn:
            await _set_tenant(conn, factory_id)
            row = await conn.fetchrow(
                "SELECT MAX(date) AS mx FROM agg_daily WHERE factory_id = $1",
                factory_id,
            )
        anchor = row["mx"] if row and row["mx"] is not None else None

    base = {
        "factory_id": factory_id,
        "store_id": store_id,
        "window_days": window_days,
        "horizon_days": horizon_days,
        "anchor_date": anchor.isoformat() if anchor is not None else None,
    }

    if anchor is None:
        return {
            **base, "model_type": "no_data", "points": [],
            "cost_data_sufficient": False,
            "message": "该工厂暂无 gold 营收数据，无法预测毛利。",
        }

    start = anchor - timedelta(days=window_days)
    revenue_series = await _fetch_daily_revenue(
        smartbi_pool, factory_id, anchor, window_days, store_id
    )
    daily_cogs, coverage = await fetch_period_cogs(
        smartbi_pool, cretas_pool, factory_id, start, anchor, store_id
    )

    if not assess_cost_coverage(coverage):
        return {
            **base, "model_type": "cost_data_insufficient", "points": [],
            "cost_data_sufficient": False,
            "coverage": _coverage_block(coverage),
            "message": _insufficient_message(coverage),
        }

    result = compute_margin_forecast_core(
        revenue_series, daily_cogs, anchor=anchor,
        horizon_days=horizon_days, window_days=window_days,
    )
    result.update(base)
    result["cost_data_sufficient"] = True
    result["coverage"] = _coverage_block(coverage)
    return result


# ── Margin pace alert ─────────────────────────────────────────────────────────
def compute_margin_pace_core(
    *,
    margin_actual: Optional[Decimal],
    revenue_target: Optional[Decimal],
    margin_rate: Decimal,
    period_first: date,
    period_last: date,
    today: date,
) -> Dict[str, Any]:
    """Pace alert against a target margin = revenue_target × margin_rate.

    Reuses compute_pace_alert (revenue dimension) with the derived margin target.
    Adds margin_amount / target_margin / cogs handled by caller. NO_TARGET when
    the revenue target is unset (can't derive a margin target).
    """
    if revenue_target is None:
        return {
            "alert_level": "NO_TARGET",
            "margin_amount": (float(margin_actual) if margin_actual is not None else None),
            "target_margin": None,
            "completion_pct": None,
            "elapsed_pct": None,
            "pace_gap_pct": None,
            "margin_rate": float(margin_rate),
        }

    target_margin = _q4(revenue_target * margin_rate)
    pace = compute_pace_alert(
        actual_amount=margin_actual, target_amount=target_margin,
        period_first=period_first, period_last=period_last, today=today,
    )
    return {
        "alert_level": pace["alert_level"],
        "margin_amount": (float(margin_actual) if margin_actual is not None else None),
        "target_margin": float(target_margin),
        "completion_pct": pace["completion_pct"],
        "elapsed_pct": pace["elapsed_pct"],
        "pace_gap_pct": pace["pace_gap_pct"],
        "days_elapsed": pace["days_elapsed"],
        "days_total": pace["days_total"],
        "data_missing": pace["data_missing"],
        "margin_rate": float(margin_rate),
    }


async def compute_margin_pace_alert(
    smartbi_pool: asyncpg.Pool,
    cretas_pool: asyncpg.Pool,
    factory_id: str,
    *,
    store_id: Optional[int] = None,
    period_type: str = "month",
    today: Optional[date] = None,
) -> Dict[str, Any]:
    """Current-period margin pace alert with honest cost-coverage gating.

    target margin = revenue target (restaurant_target_hierarchy) × margin_rate.
    margin actual = period-to-date revenue (agg_daily) − period-to-date COGS.
    Insufficient cost coverage → alert_level='COST_DATA_INSUFFICIENT', amounts None.
    """
    if not factory_id:
        raise ValueError("factory_id required")
    today = today or date.today()
    period_key = _period_key_for_target(today, period_type)
    p_first, p_last = _period_bounds(period_key, period_type)
    actual_end = min(today, p_last)

    base = {
        "factory_id": factory_id,
        "store_id": store_id,
        "period_type": period_type,
        "period_key": period_key,
    }

    # revenue target (kpi_kind='revenue' only — margin derives from revenue target)
    async with smartbi_pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        if store_id is None:
            trow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = 'revenue' AND level = $2
                   AND period_key = $3 AND store_id IS NULL
                """,
                factory_id, period_type, period_key,
            )
            arow = await conn.fetchrow(
                """
                SELECT SUM(net_amount)::numeric(18,2) AS rev
                  FROM agg_daily
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                """,
                factory_id, p_first, actual_end,
            )
        else:
            trow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = 'revenue' AND level = $2
                   AND period_key = $3 AND store_id = $4
                """,
                factory_id, period_type, period_key, store_id,
            )
            arow = await conn.fetchrow(
                """
                SELECT SUM(net_amount)::numeric(18,2) AS rev
                  FROM agg_daily
                 WHERE factory_id = $1 AND date BETWEEN $2 AND $3
                   AND store_id = $4
                """,
                factory_id, p_first, actual_end, store_id,
            )

    revenue_target = (
        Decimal(str(trow["target_value"]))
        if trow and trow["target_value"] is not None else None
    )
    period_revenue = (
        Decimal(str(arow["rev"])) if arow and arow["rev"] is not None else None
    )

    margin_rate = await get_margin_rate(smartbi_pool, factory_id, store_id=store_id)

    # COGS for the elapsed period span.
    daily_cogs, coverage = await fetch_period_cogs(
        smartbi_pool, cretas_pool, factory_id, p_first, actual_end, store_id
    )

    if not assess_cost_coverage(coverage):
        return {
            **base,
            "alert_level": "COST_DATA_INSUFFICIENT",
            "margin_amount": None,
            "cogs_amount": None,
            "target_margin": None,
            "completion_pct": None,
            "elapsed_pct": None,
            "pace_gap_pct": None,
            "margin_rate": float(margin_rate),
            "cost_data_sufficient": False,
            "coverage": _coverage_block(coverage),
            "message": _insufficient_message(coverage),
        }

    period_cogs = sum(daily_cogs.values(), Decimal("0"))
    margin_actual = (
        period_revenue - period_cogs if period_revenue is not None else None
    )

    pace = compute_margin_pace_core(
        margin_actual=margin_actual,
        revenue_target=revenue_target,
        margin_rate=margin_rate,
        period_first=p_first, period_last=p_last, today=today,
    )
    pace.update(base)
    pace["cogs_amount"] = float(period_cogs)
    pace["cost_data_sufficient"] = True
    pace["coverage"] = _coverage_block(coverage)
    return pace


# ── Margin-rate config ────────────────────────────────────────────────────────
async def get_margin_rate(
    pool: asyncpg.Pool,
    factory_id: str,
    *,
    store_id: Optional[int] = None,
) -> Decimal:
    """Read the configured target margin rate (default 0.55 when unset)."""
    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        if store_id is None:
            row = await conn.fetchrow(
                """
                SELECT target_margin_rate FROM restaurant_target_margin_config
                 WHERE factory_id = $1 AND store_id IS NULL
                """,
                factory_id,
            )
        else:
            row = await conn.fetchrow(
                """
                SELECT target_margin_rate FROM restaurant_target_margin_config
                 WHERE factory_id = $1 AND store_id = $2
                """,
                factory_id, store_id,
            )
    if row is None or row["target_margin_rate"] is None:
        return DEFAULT_TARGET_MARGIN_RATE
    return Decimal(str(row["target_margin_rate"]))


async def set_margin_rate(
    pool: asyncpg.Pool,
    factory_id: str,
    rate: Decimal,
    *,
    store_id: Optional[int] = None,
    updated_by: str = "system",
) -> Decimal:
    """Idempotent upsert of the target margin rate. Returns the stored value."""
    if rate < 0 or rate > 1:
        raise ValueError("target_margin_rate must be within [0, 1]")
    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        conflict = (
            "(factory_id) WHERE store_id IS NULL"
            if store_id is None
            else "(factory_id, store_id) WHERE store_id IS NOT NULL"
        )
        async with conn.transaction():
            await conn.execute(
                f"""
                INSERT INTO restaurant_target_margin_config
                    (factory_id, store_id, target_margin_rate, updated_by)
                VALUES ($1, $2, $3, $4)
                ON CONFLICT {conflict}
                DO UPDATE SET target_margin_rate = EXCLUDED.target_margin_rate,
                              updated_by = EXCLUDED.updated_by,
                              updated_at = NOW()
                """,
                factory_id, store_id, rate, updated_by,
            )
    return rate
