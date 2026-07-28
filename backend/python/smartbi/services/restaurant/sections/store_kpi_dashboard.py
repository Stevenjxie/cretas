from __future__ import annotations

"""Store KPI dashboard.

Two compute paths share one handler:

1. **Legacy 3-dimension health check** — when the caller feeds explicit
   ``financial`` / ``operational`` / ``external`` dicts in ``params``, the
   handler runs the original (financial+operational+external) summary. Kept for
   backward compatibility with existing callers/tests.

2. **Single-store 店长 6-KPI self-query** (2026-06-04 MVP) — when NO dicts are
   fed, the handler self-queries Gold for the six 店长 KPIs and returns a
   ``{kpis: [...], overall_health, ...}`` shape:

   | KPI            | source                                                  |
   |----------------|---------------------------------------------------------|
   | 日营收         | kpi_summary.revenue / day_count (日均)                  |
   | 客单价         | kpi_summary.avg_bill_value                              |
   | 订单数         | kpi_summary.bill_count                                  |
   | 毛利率         | resolve_gross_margin 平均毛利率 (+ has_price_data honesty)|
   | 食材成本率     | requisition_cost_total / net_amount                     |
   | 目标完成率     | daily_achievement_summary (revenue, month)              |

Single store = factory-level aggregation (no store scope gating). Money KPIs
are price-sensitive and are nulled (NOT zeroed) for roles outside
PRICE_VIEW_ROLES; rates/counts stay visible. python-java-port rules apply
(Decimal HALF_UP, `is not None` not falsy `or`).
"""

import asyncio
import time
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Optional

from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)

# ── Health thresholds (MVP fixed; 目标完成率 reads restaurant_alert_config) ──
_GROSS_MARGIN_GOOD = Decimal("0.55")     # >= 55% GOOD
_GROSS_MARGIN_WARN = Decimal("0.40")     # 40..55% WARNING; < 40% CRITICAL
_FOOD_COST_GOOD = Decimal("0.40")        # <= 40% GOOD
_FOOD_COST_WARN = Decimal("0.50")        # 40..50% WARNING; > 50% CRITICAL
# 目标完成率 defaults when no restaurant_alert_config row exists.
_TARGET_DEFAULT_WARN = Decimal("0.90")
_TARGET_DEFAULT_CRIT = Decimal("0.70")

# Store KPI is a manager/finance dashboard. Do not reuse the global
# PRICE_VIEW_ROLES verbatim: sales_manager may see sales module amounts in other
# contexts, but Dengzong's restaurant operating dashboard requires absolute
# revenue/ticket money to be hidden from sales/non-finance roles.
STORE_KPI_MONEY_VIEW_ROLES = frozenset({
    "factory_super_admin",
    "platform_admin",
    "finance_manager",
    "restaurant_manager",
    "permission_admin",
    "department_admin",
})

# Lookback window (days) handed to resolve_gross_margin for the 毛利率 KPI when
# the dashboard is "全部历史". Food costs are a snapshot ("current"), not
# time-varying, so a wide window simply captures all POS history.
_ALL_HISTORY_DAYS = 3650


# ──────────────────────────────────────────────────────────────────────────
# Indirection seams (monkeypatched in unit tests; thin wrappers in prod)
# ──────────────────────────────────────────────────────────────────────────

async def _kpi_summary(pool, factory_id, date_range):
    from smartbi.gold.queries import kpi_summary
    return await kpi_summary(pool, factory_id, date_range)


async def _resolve_gross_margin(pool, factory_id, days=30, top_n=10):
    from smartbi.gold.restaurant.restaurant_ops_router import resolve_gross_margin
    return await resolve_gross_margin(pool, factory_id, days=days, top_n=top_n)


async def _daily_achievement_summary(pool, factory_id, date_range, *, kpi_kind="revenue", level="day", store_id=None):
    from smartbi.gold.queries import daily_achievement_summary
    return await daily_achievement_summary(
        pool, factory_id, date_range, kpi_kind=kpi_kind, level=level, store_id=store_id
    )


async def _query_requisition_cost_total(pool, factory_id, date_range) -> Optional[float]:
    """SUM(agg_restaurant_daily_totals.requisition_cost_total) across the range.

    Returns None when the table isn't provisioned (graceful) — caller treats
    None as "食材成本数据不足". RLS tenant GUC is set by the caller
    (compute_store_kpi_dashboard) before this runs.
    """
    import asyncpg
    start, end = date_range
    conds = ["factory_id = $1"]
    params: list = [factory_id]
    if start is not None:
        params.append(start)
        conds.append(f"date >= ${len(params)}")
    if end is not None:
        params.append(end)
        conds.append(f"date <= ${len(params)}")
    where = " AND ".join(conds)
    async with pool.acquire() as conn:
        try:
            row = await conn.fetchrow(
                f"""
                SELECT COALESCE(SUM(requisition_cost_total), 0)::float AS total
                  FROM agg_restaurant_daily_totals
                 WHERE {where}
                """,
                *params,
            )
        except asyncpg.exceptions.UndefinedTableError:
            return None
    return float(row["total"]) if row and row["total"] is not None else 0.0


async def _query_alert_thresholds(pool, factory_id, kpi_kind="revenue", level="month") -> Optional[dict]:
    """Read warn/critical thresholds from restaurant_alert_config; None = no row."""
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT warn_threshold, critical_threshold
              FROM restaurant_alert_config
             WHERE factory_id = $1 AND kpi_kind = $2 AND level = $3
             LIMIT 1
            """,
            factory_id, kpi_kind, level,
        )
    if not rows:
        return None
    r = rows[0]
    return {"warn_threshold": float(r["warn_threshold"]), "critical_threshold": float(r["critical_threshold"])}


async def _get_pg_pool():
    from smartbi.config import get_pg_pool
    return await get_pg_pool()


# ──────────────────────────────────────────────────────────────────────────
# Small numeric helpers (python-java-port)
# ──────────────────────────────────────────────────────────────────────────

def _to_decimal(v: Any) -> Decimal:
    if v is None:
        return Decimal("0")
    if isinstance(v, Decimal):
        return v
    return Decimal(str(v))


def _rate(numerator: Decimal, denominator: Decimal) -> Optional[Decimal]:
    """Java BigDecimal divide(4, HALF_UP) semantics; None when denom == 0."""
    if denominator == Decimal("0"):
        return None
    return (numerator / denominator).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def _pct_str(rate: Optional[Decimal]) -> str:
    if rate is None:
        return "—"
    return f"{(rate * 100).quantize(Decimal('0.1'), rounding=ROUND_HALF_UP)}%"


def _money_str(v: Optional[float]) -> str:
    if v is None:
        return "—"
    return f"¥{Decimal(str(v)).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP):,.2f}"


def _f(v: Optional[Decimal]) -> Optional[float]:
    return float(v) if v is not None else None


# ──────────────────────────────────────────────────────────────────────────
# 6-KPI compute (shared by web-admin Gold endpoint + AI tool section)
# ──────────────────────────────────────────────────────────────────────────

async def compute_store_kpi_dashboard(
    pool,
    factory_id: str,
    date_range: tuple,
    role: Optional[str],
    store_name: Optional[str] = None,
) -> Optional[dict]:
    """Self-query Gold for the six 店长 KPIs (single-store / factory-level).

    Sets the RLS tenant GUC for this task (the section path runs outside the
    FastAPI middleware that normally sets it). Returns None when the factory
    has no sales data at all (caller → SKIPPED). Money KPIs are RBAC-stripped
    in place (null, never 0) for non price-view roles.
    """
    from smartbi.tenant_ctx import set_factory_id
    # RLS: pool.setup reads current_factory_id contextvar per connection checkout.
    set_factory_id(factory_id)

    # ── 日营收 / 客单价 / 订单数 ──────────────────────────────────────────
    summary = await _kpi_summary(pool, factory_id, date_range)
    revenue = _to_decimal(summary.get("revenue"))
    bill_count = int(summary.get("bill_count") or 0)
    day_count = int(summary.get("day_count") or 0)
    avg_bill_value = summary.get("avg_bill_value")  # already None when bills=0

    # Honest empty: no sales rows at all → signal SKIPPED.
    if revenue == Decimal("0") and bill_count == 0 and day_count == 0:
        return None

    daily_revenue = _rate(revenue, _to_decimal(day_count)) if day_count > 0 else None

    # ── 毛利率 (resolve_gross_margin + has_price_data honesty) ────────────
    margin_status = "INSUFFICIENT"
    margin_rate: Optional[Decimal] = None
    margin_value = "成本数据不足"
    priced_ratio: Optional[float] = None
    total_dishes = 0
    dishes_with_cost = 0
    try:
        ans = await _resolve_gross_margin(pool, factory_id, days=_ALL_HISTORY_DAYS, top_n=10)
        meta = ans.meta or {}
        total_dishes = int(meta.get("total_dishes", 0) or 0)
        missing = int(meta.get("missing_cost_count", 0) or 0)
        dishes_with_cost = max(total_dishes - missing, 0)
        if total_dishes > 0 and dishes_with_cost > 0:
            raw = None
            for kpi in (ans.kpis or []):
                if kpi.get("title") == "平均毛利率":
                    raw = kpi.get("rawValue")
                    break
            if raw is not None:
                margin_rate = _to_decimal(raw).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
                margin_value = _pct_str(margin_rate)
                priced_ratio = round(dishes_with_cost / total_dishes, 4)
                if margin_rate >= _GROSS_MARGIN_GOOD:
                    margin_status = "GOOD"
                elif margin_rate >= _GROSS_MARGIN_WARN:
                    margin_status = "WARNING"
                else:
                    margin_status = "CRITICAL"
    except Exception:  # noqa: BLE001 — gross margin is best-effort; honest INSUFFICIENT on failure
        margin_status = "INSUFFICIENT"
        margin_rate = None
        margin_value = "成本数据不足"

    # ── 食材成本率 = requisition_cost_total / revenue ─────────────────────
    food_cost_total = await _query_requisition_cost_total(pool, factory_id, date_range)
    food_rate: Optional[Decimal] = None
    food_status = "INSUFFICIENT"
    food_value = "成本数据不足"
    if revenue > Decimal("0") and food_cost_total is not None:
        food_rate = _rate(_to_decimal(food_cost_total), revenue)
        if food_rate is not None:
            food_value = _pct_str(food_rate)
            if food_rate <= _FOOD_COST_GOOD:
                food_status = "GOOD"
            elif food_rate <= _FOOD_COST_WARN:
                food_status = "WARNING"
            else:
                food_status = "CRITICAL"

    # ── 目标完成率 (revenue, month level) + alert_config thresholds ───────
    target_rate: Optional[Decimal] = None
    target_status = "NO_TARGET"
    target_value = "未设目标"
    config_exists = False
    needs_config = True
    # daily_achievement_summary requires bounded dates. For a bounded range we
    # call it directly; for all-history (open range) we resolve the factory's
    # data window from agg_daily first (_achievement_all_history).
    if date_range[0] is not None and date_range[1] is not None:
        achieved = await _daily_achievement_summary(
            pool, factory_id, date_range, kpi_kind="revenue", level="month",
        )
    else:
        achieved = await _achievement_all_history(pool, factory_id)

    points = achieved.get("points", []) if achieved else []
    sum_actual = sum((_to_decimal(p["actual"]) for p in points
                      if p.get("actual") is not None), Decimal("0"))
    sum_target = sum((_to_decimal(p["target"]) for p in points
                      if p.get("target") is not None), Decimal("0"))
    if points and sum_target > Decimal("0"):
        needs_config = False
        target_rate = _rate(sum_actual, sum_target)
        target_value = _pct_str(target_rate)
        cfg = await _query_alert_thresholds(pool, factory_id, kpi_kind="revenue", level="month")
        config_exists = cfg is not None
        warn_t = _to_decimal(cfg["warn_threshold"]) if cfg else _TARGET_DEFAULT_WARN
        crit_t = _to_decimal(cfg["critical_threshold"]) if cfg else _TARGET_DEFAULT_CRIT
        if target_rate is None:
            target_status = "NO_TARGET"
        elif target_rate < crit_t:
            target_status = "CRITICAL"
        elif target_rate < warn_t:
            target_status = "WARNING"
        else:
            target_status = "GOOD"

    # ── Assemble cards ────────────────────────────────────────────────────
    kpis = [
        {
            "key": "daily_revenue", "label": "日营收", "unit": "元",
            "value": _money_str(_f(daily_revenue)), "rawValue": _f(daily_revenue),
            "status": "GOOD", "money": True,
            "detail": {"totalRevenue": float(revenue), "dayCount": day_count},
        },
        {
            "key": "avg_ticket", "label": "客单价", "unit": "元",
            "value": _money_str(avg_bill_value), "rawValue": avg_bill_value,
            "status": "GOOD", "money": True,
        },
        {
            "key": "order_count", "label": "订单数", "unit": "单",
            "value": f"{bill_count:,}", "rawValue": bill_count,
            "status": "GOOD", "money": False,
        },
        {
            "key": "gross_margin", "label": "毛利率", "unit": "%",
            "value": margin_value, "rawValue": _f(margin_rate),
            "status": margin_status, "money": False,
            "detail": {
                "totalDishes": total_dishes, "dishesWithCost": dishes_with_cost,
                "pricedRatio": priced_ratio,
                "note": "基于有配方成本的菜品 (±15% Layer1 精度)" if margin_status != "INSUFFICIENT"
                else "暂无配方成本数据，无法计算真实毛利率，请先录入配方",
            },
        },
        {
            "key": "food_cost_rate", "label": "食材成本率", "unit": "%",
            "value": food_value, "rawValue": _f(food_rate),
            "status": food_status, "money": False,
            "detail": {
                "requisitionCost": food_cost_total,
                "note": "领料成本 ÷ 营收 (与配方理论 COGS 口径不同)",
            },
        },
        {
            "key": "target_completion", "label": "目标完成率", "unit": "%",
            "value": target_value, "rawValue": _f(target_rate),
            "status": target_status, "money": False,
            "configExists": config_exists, "needsConfig": needs_config,
        },
    ]

    # overall_health: worst non-informational, non-NO_TARGET/INSUFFICIENT status.
    severities = {"CRITICAL": 3, "WARNING": 2, "GOOD": 1}
    worst = max(
        (severities.get(k["status"], 0) for k in kpis if k["status"] in severities),
        default=1,
    )
    overall = {3: "CRITICAL", 2: "WARNING", 1: "GOOD"}[worst]

    data = {
        "store_name": store_name,
        "start_date": summary.get("start_date"),
        "end_date": summary.get("end_date"),
        "kpis": kpis,
        "overall_health": overall,
    }

    # RBAC: strip absolute-money KPIs (日营收 / 客单价) for non price-view roles.
    #
    # We do this PRECISELY rather than via the generic strip_price_for_role
    # walker: that walker flags any node whose label matches the money pattern,
    # and our rate cards have money-ish labels (毛利率 contains 毛利, 食材成本率
    # contains 成本) that would be false-positives — rates must stay visible
    # (they convey health without leaking absolute amounts). We null only the
    # cards explicitly tagged money:True, plus their money detail carriers.
    #
    # null (never 0) preserves the missing-vs-zero distinction (Rule 4).
    # fail-closed: role None / unknown → strip.
    if not (role and role in STORE_KPI_MONEY_VIEW_ROLES):
        for k in kpis:
            if k.get("money"):
                k["value"] = "—"
                k["rawValue"] = None
                if isinstance(k.get("detail"), dict):
                    if "totalRevenue" in k["detail"]:
                        k["detail"]["totalRevenue"] = None
        # 毛利率 detail carries no absolute amount (rate + dish counts) → keep.
        # 食材成本率 detail.requisitionCost IS an absolute amount → null it; the
        # rate itself stays.
        for k in kpis:
            if k.get("key") == "food_cost_rate" and isinstance(k.get("detail"), dict):
                k["detail"]["requisitionCost"] = None
    return data


def _empty_achievement() -> dict:
    return {"points": [], "period_without_target": []}


async def _achievement_all_history(pool, factory_id) -> dict:
    """目标完成率 over all history: bound the range to the factory's data window
    then defer to daily_achievement_summary (month level).

    Uses agg_daily MIN/MAX as the window. No data → empty achievement.
    """
    from datetime import date
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT MIN(date) AS lo, MAX(date) AS hi FROM agg_daily WHERE factory_id = $1",
            factory_id,
        )
    if not row or row["lo"] is None or row["hi"] is None:
        return _empty_achievement()
    lo: date = row["lo"]
    hi: date = row["hi"]
    return await _daily_achievement_summary(
        pool, factory_id, (lo, hi), kpi_kind="revenue", level="month",
    )


# ──────────────────────────────────────────────────────────────────────────
# Section handler
# ──────────────────────────────────────────────────────────────────────────

class StoreKpiDashboardHandler(AbstractSectionHandler):
    section_name = "store_kpi_dashboard"

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()
        p = request.params or {}
        fin = p.get("financial")
        ops = p.get("operational")
        ext = p.get("external")

        # Path 1: caller fed dimension dicts → legacy 3-dimension health check.
        if fin or ops or ext:
            return self._legacy_three_dimension(request, fin, ops, ext, started)

        # Path 2: self-query Gold for the 6 店长 KPIs.
        factory_id = request.factory_id
        if not factory_id:
            return self.skipped(request, "缺少 factory_id", started)

        role = p.get("role")  # injected by StoreKpiDashboardTool (X-User-Role equiv)
        date_range = self._resolve_range(p)

        try:
            data = asyncio.run(self._self_query(factory_id, date_range, role, request.store_name))
        except Exception as e:  # noqa: BLE001
            return self.skipped(request, f"读取经营 KPI 失败: {e}", started)

        if data is None:
            return self.skipped(
                request,
                "未检测到经营数据，请先上传 POS 销售/财务数据 (店长 KPI 需要 agg_daily 营收数据)",
                started,
            )
        return self.ok(request, data=data, started=started)

    async def _self_query(self, factory_id, date_range, role, store_name):
        # compute() 在 FastAPI threadpool 线程里用 asyncio.run() 跑本协程 → 新事件循环。
        # 但 _get_pg_pool() 返回的共享池是在主 uvicorn 事件循环上建的 → 其连接/Future 绑定主循环,
        # 在新循环里使用会 "got Future attached to a different loop" 崩溃 (店长 KPI 看板整块挂)。
        # 修: 在当前(新)事件循环里建一个短命池, 用完即关; 跨循环问题消除。
        # 显式 set_factory_id 保证 setup 回调把 app.factory_id GUC 设对 (RLS), 不依赖 contextvar 跨线程传播。
        import asyncpg
        from smartbi.config import get_settings
        from smartbi import tenant_ctx
        settings = get_settings()
        pg_url = settings.postgres_url
        if not pg_url:
            raise RuntimeError("smartbi_db url unavailable")
        tenant_ctx.set_factory_id(factory_id)
        pool = await asyncpg.create_pool(
            pg_url, min_size=1, max_size=2, setup=tenant_ctx.set_pg_connection_tenant,
        )
        try:
            return await compute_store_kpi_dashboard(pool, factory_id, date_range, role, store_name)
        finally:
            await pool.close()

    @staticmethod
    def _resolve_range(params: dict) -> tuple:
        """Optional [start, end] from params; missing = 全部历史 (open range)."""
        from datetime import datetime

        def _parse(s):
            if not s:
                return None
            try:
                return datetime.strptime(str(s), "%Y-%m-%d").date()
            except ValueError:
                return None
        return _parse(params.get("start_date")), _parse(params.get("end_date"))

    # ── Legacy 3-dimension health check (backward compat, unchanged) ──────
    def _legacy_three_dimension(self, request, fin, ops, ext, started) -> SectionResponse:
        dimensions = []
        alerts = []

        if fin:
            profit = float(fin.get("controllable_profit", 0))
            revenue = float(fin.get("revenue", 0))
            labor_pct = float(fin.get("labor_cost_pct", 0))
            margin = round(profit / revenue * 100, 1) if revenue > 0 else 0
            health = "GOOD" if margin >= 30 else "WARNING" if margin >= 20 else "CRITICAL"
            dimensions.append({"name": "财务", "health": health, "metrics": [
                {"label": "可控利润率", "value": f"{margin}%", "status": health},
                {"label": "人力成本占比", "value": f"{labor_pct}%",
                 "status": "GOOD" if labor_pct <= 22 else "WARNING" if labor_pct <= 28 else "CRITICAL"},
            ]})
            if margin < 20:
                alerts.append(f"可控利润率 {margin}% 低于警戒线 20%")

        if ops:
            prod = float(ops.get("labor_productivity", 0))
            turnover = float(ops.get("staff_turnover_pct", 0))
            compliance = float(ops.get("shift_compliance", 0))
            health = "GOOD" if 30000 <= prod <= 40000 else "WARNING"
            dimensions.append({"name": "营运", "health": health, "metrics": [
                {"label": "人效", "value": f"¥{prod:,.0f}/人", "status": health},
                {"label": "员工流失率", "value": f"{turnover}%",
                 "status": "GOOD" if turnover <= 10 else "WARNING" if turnover <= 20 else "CRITICAL"},
                {"label": "排班达标率", "value": f"{compliance}%",
                 "status": "GOOD" if compliance >= 90 else "WARNING"},
            ]})
            if prod < 30000:
                alerts.append(f"人效 ¥{prod:,.0f} 低于3万")
            if prod > 40000:
                alerts.append(f"人效 ¥{prod:,.0f} 超过4万, 服务跟不上风险")

        if ext:
            score = float(ext.get("review_score", 0))
            neg_pct = float(ext.get("negative_review_pct", 0))
            health = "GOOD" if score >= 4.5 else "WARNING" if score >= 4.0 else "CRITICAL"
            dimensions.append({"name": "外部评价", "health": health, "metrics": [
                {"label": "点评评分", "value": f"{score}", "status": health},
                {"label": "差评率", "value": f"{neg_pct}%",
                 "status": "GOOD" if neg_pct <= 1 else "WARNING" if neg_pct <= 3 else "CRITICAL"},
            ]})
            if score < 4.0:
                alerts.append(f"点评评分 {score} 低于4.0")

        healths = [d["health"] for d in dimensions]
        overall = "CRITICAL" if "CRITICAL" in healths else "WARNING" if "WARNING" in healths else "GOOD"

        return self.ok(request, data={
            "dimensions": dimensions, "overall_health": overall,
            "alerts": alerts, "dimension_count": len(dimensions),
        }, started=started)
