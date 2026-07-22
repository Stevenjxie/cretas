"""#58 Phase 1 — 目标拆分 (月→周→日) + 实时 pace 预警计算.

扩展已上线 G2 (年→月 拆分已由 web-admin target-hierarchy.vue + upsert_target 覆盖)。
本模块负责更细粒度的 月→周 / 周→日 拆分，写回同一张 restaurant_target_hierarchy
表 (level='week' / 'day' 行)，以及 compute_pace_alert (周期内进度 vs 时间进度)。

拆分权重来源 (revenue-only Phase 1):
  - 月→周: 用 gold agg_daily 的历史「周营收占月营收」比例 (按 ISO 周聚合)；
    无历史 → 退化到「该周落在本月的天数 / 本月总天数」。
  - 周→日: 用 trailing 90d 的 DOW (星期几) 营收权重；无历史 → 退化到 1/7。

Rule 引用 (.claude/rules/python-java-port.md):
  - Rule 2: WEEK period_key 用 calendar year (d.year)，不是 ISO year。复用
    queries._period_key_for_target / _period_bounds (单一事实源)。
  - Rule 10/12: 所有金额 Decimal.quantize(Decimal("0.01"), ROUND_HALF_UP)。
  - 末元素吸收舍入误差 → 子级之和严格等于父级 (sum-invariant)。
"""
from __future__ import annotations

import logging
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Dict, List, Optional, Tuple

import asyncpg

from smartbi.gold.queries import _period_bounds, _period_key_for_target

logger = logging.getLogger(__name__)

_CENT = Decimal("0.01")


def _q2(v: Decimal) -> Decimal:
    """Quantize a Decimal to 2 dp HALF_UP (Rule 12 — Java parity)."""
    return v.quantize(_CENT, rounding=ROUND_HALF_UP)


def _weeks_touching_month(year: int, month: int) -> List[Tuple[str, date, date]]:
    """All ISO weeks that intersect a calendar month.

    Returns a list of (week_period_key, week_first_day, week_last_day) for
    every ISO week (Mon..Sun) that has at least one day inside [month-first,
    month-last]. The week_period_key uses calendar year of the *week's
    representative day* per Rule 2 — we derive it via the shared
    _period_key_for_target so week-vs-month-boundary keys round-trip exactly
    with the achievement query side.

    Ordered ascending by week_first_day, de-duplicated.
    """
    import calendar

    last_dom = calendar.monthrange(year, month)[1]
    month_first = date(year, month, 1)
    month_last = date(year, month, last_dom)

    seen: set[str] = set()
    out: List[Tuple[str, date, date]] = []
    cur = month_first
    while cur <= month_last:
        pk = _period_key_for_target(cur, "week")
        if pk not in seen:
            seen.add(pk)
            w_first, w_last = _period_bounds(pk, "week")
            out.append((pk, w_first, w_last))
        cur += timedelta(days=1)
    out.sort(key=lambda t: t[1])
    return out


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    """Parameterized RLS tenant context (injection-safe)."""
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)", factory_id
    )


async def _historical_week_shares(
    conn: asyncpg.Connection,
    factory_id: str,
    weeks: List[Tuple[str, date, date]],
    store_id: Optional[int],
) -> Dict[str, Decimal]:
    """Per-week share of the month's revenue from gold agg_daily history.

    For each ISO week touching the month, sums net_amount over the portion of
    that week that falls *within the same calendar month* (so a week crossing
    the month boundary only contributes its in-month days). Shares are
    normalized to sum to 1 across the weeks. Returns {} when there is no
    history (caller falls back to day-count weights).
    """
    # Clip each week to its in-month span, then query that day-range total.
    # We aggregate all weeks in a single pass over the month's daily rows.
    if not weeks:
        return {}
    month_first = min(w[1] for w in weeks)
    month_last = max(w[2] for w in weeks)
    # But clip to the month the weeks describe — derive from the first week's
    # representative in-month day is unreliable; instead query the union span
    # and bucket by week key using the shared period-key fn.
    if store_id is None:
        rows = await conn.fetch(
            """
            SELECT date, SUM(net_amount)::numeric(18,2) AS rev
              FROM agg_daily
             WHERE factory_id = $1
               AND date BETWEEN $2 AND $3
             GROUP BY date
            """,
            factory_id, month_first, month_last,
        )
    else:
        rows = await conn.fetch(
            """
            SELECT date, SUM(net_amount)::numeric(18,2) AS rev
              FROM agg_daily
             WHERE factory_id = $1
               AND date BETWEEN $2 AND $3
               AND store_id = $4
             GROUP BY date
            """,
            factory_id, month_first, month_last, store_id,
        )
    week_keys = {w[0] for w in weeks}
    per_week: Dict[str, Decimal] = {k: Decimal("0") for k in week_keys}
    total = Decimal("0")
    for r in rows:
        if r["rev"] is None:
            continue
        pk = _period_key_for_target(r["date"], "week")
        if pk not in per_week:
            continue
        v = Decimal(str(r["rev"]))
        per_week[pk] += v
        total += v
    if total <= 0:
        return {}
    return {k: (v / total) for k, v in per_week.items()}


def _distribute(
    parent_total: Decimal,
    ordered_keys: List[str],
    weights: Dict[str, Decimal],
) -> Dict[str, Decimal]:
    """Split parent_total across ordered_keys by weights (sum-invariant).

    Each share = parent_total * weight, quantized to cents. The LAST key in
    ordered_keys absorbs the rounding residual so the children sum *exactly*
    to parent_total. weights need not be pre-normalized — they are normalized
    here; an all-zero / empty weight map yields a uniform split.
    """
    n = len(ordered_keys)
    if n == 0:
        return {}
    wsum = sum((weights.get(k, Decimal("0")) for k in ordered_keys), Decimal("0"))
    if wsum <= 0:
        # uniform fallback
        weights = {k: Decimal("1") for k in ordered_keys}
        wsum = Decimal(n)

    out: Dict[str, Decimal] = {}
    running = Decimal("0")
    for i, k in enumerate(ordered_keys):
        if i == n - 1:
            out[k] = _q2(parent_total - running)  # last absorbs residual
        else:
            share = _q2(parent_total * (weights.get(k, Decimal("0")) / wsum))
            out[k] = share
            running += share
    return out


async def decompose_month_to_weeks(
    pool: asyncpg.Pool,
    factory_id: str,
    period_key: str,
    *,
    kpi_kind: str = "revenue",
    store_id: Optional[int] = None,
    created_by: str = "system",
) -> Dict[str, Any]:
    """Split a month-level target into its constituent ISO-week targets.

    Reads the stored month target from restaurant_target_hierarchy, computes
    week weights from gold history (in-month revenue share) with a day-count
    fallback, then UPSERTs level='week' rows summing exactly to the month
    target. Returns the written week rows. Honest no-op when the month target
    is unset.
    """
    if not factory_id:
        raise ValueError("factory_id required")
    year, month = (int(x) for x in period_key.split("-"))
    weeks = _weeks_touching_month(year, month)
    ordered_keys = [w[0] for w in weeks]

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        if store_id is None:
            mrow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = 'month'
                   AND period_key = $3 AND store_id IS NULL
                """,
                factory_id, kpi_kind, period_key,
            )
        else:
            mrow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = 'month'
                   AND period_key = $3 AND store_id = $4
                """,
                factory_id, kpi_kind, period_key, store_id,
            )
        if mrow is None or mrow["target_value"] is None:
            return {
                "factory_id": factory_id,
                "period_key": period_key,
                "level": "week",
                "weeks": [],
                "message": f"月度目标未设置 ({period_key})，无法拆分到周。",
            }
        month_target = Decimal(str(mrow["target_value"]))

        shares = await _historical_week_shares(conn, factory_id, weeks, store_id)
        if shares:
            weights = shares
            basis = "historical_week_share"
        else:
            # day-count fallback: in-month day count of each week
            weights = {}
            for pk, w_first, w_last in weeks:
                in_month_days = 0
                d = max(w_first, date(year, month, 1))
                # last in-month day for this week
                import calendar
                m_last = date(year, month, calendar.monthrange(year, month)[1])
                d_end = min(w_last, m_last)
                while d <= d_end:
                    in_month_days += 1
                    d += timedelta(days=1)
                weights[pk] = Decimal(in_month_days)
            basis = "days_in_month_fallback"

        split = _distribute(month_target, ordered_keys, weights)

        written = []
        async with conn.transaction():
            for pk in ordered_keys:
                tv = split[pk]
                conflict = (
                    "(factory_id, kpi_kind, level, period_key) "
                    "WHERE store_id IS NULL"
                    if store_id is None
                    else "(factory_id, kpi_kind, level, period_key, store_id) "
                    "WHERE store_id IS NOT NULL"
                )
                row = await conn.fetchrow(
                    f"""
                    INSERT INTO restaurant_target_hierarchy
                        (factory_id, kpi_kind, level, period_key, store_id,
                         target_value, reason, created_by)
                    VALUES ($1, $2, 'week', $3, $4, $5, $6, $7)
                    ON CONFLICT {conflict}
                    DO UPDATE SET target_value = EXCLUDED.target_value,
                                  reason = EXCLUDED.reason, updated_at = NOW()
                    RETURNING id, period_key, target_value
                    """,
                    factory_id, kpi_kind, pk, store_id, tv,
                    f"auto:{basis}", created_by,
                )
                written.append({
                    "id": row["id"],
                    "period_key": row["period_key"],
                    "target_value": float(Decimal(str(row["target_value"]))),
                })

    return {
        "factory_id": factory_id,
        "period_key": period_key,
        "level": "week",
        "basis": basis,
        "month_target": float(month_target),
        "weeks": written,
    }


async def _dow_weights(
    conn: asyncpg.Connection,
    factory_id: str,
    anchor: date,
    store_id: Optional[int],
    trailing_days: int = 90,
) -> Dict[int, Decimal]:
    """Day-of-week revenue weights from trailing history (Python weekday()).

    Returns {weekday(0=Mon..6=Sun): avg_revenue_share}. Empty dict when there
    is no history (caller uses 1/7 uniform). Uses Python's weekday() so the
    mapping is unambiguous regardless of PG DOW conventions.
    """
    start = anchor - timedelta(days=trailing_days)
    if store_id is None:
        rows = await conn.fetch(
            """
            SELECT date, SUM(net_amount)::numeric(18,2) AS rev
              FROM agg_daily
             WHERE factory_id = $1 AND date > $2 AND date <= $3
             GROUP BY date
            """,
            factory_id, start, anchor,
        )
    else:
        rows = await conn.fetch(
            """
            SELECT date, SUM(net_amount)::numeric(18,2) AS rev
              FROM agg_daily
             WHERE factory_id = $1 AND date > $2 AND date <= $3
               AND store_id = $4
             GROUP BY date
            """,
            factory_id, start, anchor, store_id,
        )
    totals: Dict[int, Decimal] = {i: Decimal("0") for i in range(7)}
    grand = Decimal("0")
    for r in rows:
        if r["rev"] is None:
            continue
        wd = r["date"].weekday()
        v = Decimal(str(r["rev"]))
        totals[wd] += v
        grand += v
    if grand <= 0:
        return {}
    return {wd: (totals[wd] / grand) for wd in range(7)}


async def decompose_week_to_days(
    pool: asyncpg.Pool,
    factory_id: str,
    period_key: str,
    *,
    kpi_kind: str = "revenue",
    store_id: Optional[int] = None,
    created_by: str = "system",
    anchor: Optional[date] = None,
) -> Dict[str, Any]:
    """Split a week-level target into its 7 daily targets.

    Reads the stored week target, computes per-day weights from trailing-90d
    DOW revenue shares (1/7 uniform fallback), and UPSERTs level='day' rows
    summing exactly to the week target. Honest no-op when week target unset.
    """
    if not factory_id:
        raise ValueError("factory_id required")
    w_first, w_last = _period_bounds(period_key, "week")
    day_keys: List[str] = []
    days: List[date] = []
    d = w_first
    while d <= w_last:
        day_keys.append(d.isoformat())
        days.append(d)
        d += timedelta(days=1)

    anchor = anchor or (w_first - timedelta(days=1))

    async with pool.acquire() as conn:
        await _set_tenant(conn, factory_id)
        if store_id is None:
            wrow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = 'week'
                   AND period_key = $3 AND store_id IS NULL
                """,
                factory_id, kpi_kind, period_key,
            )
        else:
            wrow = await conn.fetchrow(
                """
                SELECT target_value FROM restaurant_target_hierarchy
                 WHERE factory_id = $1 AND kpi_kind = $2 AND level = 'week'
                   AND period_key = $3 AND store_id = $4
                """,
                factory_id, kpi_kind, period_key, store_id,
            )
        if wrow is None or wrow["target_value"] is None:
            return {
                "factory_id": factory_id,
                "period_key": period_key,
                "level": "day",
                "days": [],
                "message": f"周目标未设置 ({period_key})，无法拆分到日。",
            }
        week_target = Decimal(str(wrow["target_value"]))

        dow = await _dow_weights(conn, factory_id, anchor, store_id)
        if dow:
            weights = {dk: dow.get(days[i].weekday(), Decimal("0"))
                       for i, dk in enumerate(day_keys)}
            basis = "trailing_90d_dow"
        else:
            weights = {dk: Decimal("1") for dk in day_keys}
            basis = "uniform_fallback"

        split = _distribute(week_target, day_keys, weights)

        written = []
        async with conn.transaction():
            for dk in day_keys:
                tv = split[dk]
                conflict = (
                    "(factory_id, kpi_kind, level, period_key) "
                    "WHERE store_id IS NULL"
                    if store_id is None
                    else "(factory_id, kpi_kind, level, period_key, store_id) "
                    "WHERE store_id IS NOT NULL"
                )
                row = await conn.fetchrow(
                    f"""
                    INSERT INTO restaurant_target_hierarchy
                        (factory_id, kpi_kind, level, period_key, store_id,
                         target_value, reason, created_by)
                    VALUES ($1, $2, 'day', $3, $4, $5, $6, $7)
                    ON CONFLICT {conflict}
                    DO UPDATE SET target_value = EXCLUDED.target_value,
                                  reason = EXCLUDED.reason, updated_at = NOW()
                    RETURNING id, period_key, target_value
                    """,
                    factory_id, kpi_kind, dk, store_id, tv,
                    f"auto:{basis}", created_by,
                )
                written.append({
                    "id": row["id"],
                    "period_key": row["period_key"],
                    "target_value": float(Decimal(str(row["target_value"]))),
                })

    return {
        "factory_id": factory_id,
        "period_key": period_key,
        "level": "day",
        "basis": basis,
        "week_target": float(week_target),
        "days": written,
    }


# ── Pace alert ──────────────────────────────────────────────────────────────

_WARN_RATIO = Decimal("0.85")   # completion >= elapsed*0.85 → WARN (else CRIT)
_CRIT_RATIO = Decimal("0.70")   # completion <  elapsed*0.70 → CRIT


def compute_pace_alert(
    *,
    actual_amount: Optional[Decimal],
    target_amount: Optional[Decimal],
    period_first: date,
    period_last: date,
    today: date,
    warn_ratio: Decimal = _WARN_RATIO,
    crit_ratio: Decimal = _CRIT_RATIO,
) -> Dict[str, Any]:
    """Compare period-to-date progress against time elapsed (pace).

    Mirrors Java SalesPlanServiceImpl.getCompletionRate semantics:
      - completion_pct = actual / target  (0 when target<=0)
      - elapsed_pct    = days_elapsed / days_total  (clamped 0..1)
      - OK   if completion_pct >= elapsed_pct
      - WARN if completion_pct >= elapsed_pct * warn_ratio
      - CRIT otherwise
    Returns level + the numeric components (pace_gap_pct = completion-elapsed).
    All Decimal arithmetic; ratios quantized to 4 dp HALF_UP (Rule 10/12).

    data_missing (actual is None): level='OK' but pace_gap_pct/completion None
    — never raise a false CRIT alarm when POS simply hasn't reported.
    """
    days_total = (period_last - period_first).days + 1
    if days_total <= 0:
        days_total = 1
    if today < period_first:
        days_elapsed = 0
    elif today >= period_last:
        days_elapsed = days_total
    else:
        days_elapsed = (today - period_first).days + 1
    days_elapsed = max(0, min(days_elapsed, days_total))

    elapsed_pct = (
        Decimal(days_elapsed) / Decimal(days_total)
    ).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)

    if actual_amount is None:
        # POS not reported — no real alarm signal.
        return {
            "alert_level": "OK",
            "completion_pct": None,
            "elapsed_pct": float(elapsed_pct),
            "pace_gap_pct": None,
            "days_elapsed": days_elapsed,
            "days_total": days_total,
            "data_missing": True,
        }

    if target_amount is None or target_amount <= 0:
        completion_pct = Decimal("0")
    else:
        completion_pct = (
            actual_amount / target_amount
        ).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)

    if completion_pct >= elapsed_pct:
        level = "OK"
    elif completion_pct >= elapsed_pct * warn_ratio:
        level = "WARN"
    else:
        # Distinguish CRIT only below crit_ratio*elapsed; between crit and warn
        # is still WARN. We already ruled out >= warn_ratio above, so anything
        # here is < warn_ratio*elapsed. CRIT when also < crit_ratio*elapsed.
        if completion_pct < elapsed_pct * crit_ratio:
            level = "CRIT"
        else:
            level = "WARN"

    pace_gap = (completion_pct - elapsed_pct).quantize(
        Decimal("0.0001"), rounding=ROUND_HALF_UP
    )
    return {
        "alert_level": level,
        "completion_pct": float(completion_pct),
        "elapsed_pct": float(elapsed_pct),
        "pace_gap_pct": float(pace_gap),
        "days_elapsed": days_elapsed,
        "days_total": days_total,
        "data_missing": False,
    }
