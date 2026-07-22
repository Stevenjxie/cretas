from __future__ import annotations

"""#56 价值可视化回馈回路 — 快照刷新 pipeline (hook / cron / API refresh 共用)。

refresh_snapshot_for_factory(factory_id, period_month, store_id):
  1. 从 smart_bi_finance_data 聚合当前期间 + 上期 financial_data。
  2. 跑 value_orchestrator.compute_signals_from_inputs → signals + counts。
  3. compute_and_upsert_snapshot 幂等 upsert。

数据不足 (无 financial 数据) → 仍 upsert 一行 (signals 空, 金额全 None) — 这样
前端 GET 命中行后显"暂无数据"而非"暂无快照", 区分"算过但没数据" vs "从没算过"。
但若连营收都查不到 → 不 upsert (避免造空行), 返回 success:False reason=no_data。

LABOR/RENT 成本来源说明: gold ETL 当前不填 LABOR 行 (无源数据, per
restaurant_finance_etl L33); labor_cost 来自手动上传的财务 Excel (按关键词分类的
COST 行)。无 labor 行 → labor_rigidity 信号 amount=None (诚实, 不编)。
"""

import logging
from datetime import date
from typing import Any, Optional

logger = logging.getLogger(__name__)


# 成本分类关键词 (镜像 Java RestaurantFinancialMetricsFetcher FOOD/LABOR_KEYWORDS)。
_FOOD_KEYWORDS = ("食材", "原料", "采购", "食品")
_LABOR_KEYWORDS = ("人工", "人力", "工资", "薪", "劳务")
_REVENUE_KEYWORDS = ("收入", "营收", "营业额")


def _prev_period(period_month: str) -> str:
    """'YYYY-MM' → 上一个月 'YYYY-MM'。"""
    y, m = period_month.split("-")
    yi, mi = int(y), int(m)
    if mi == 1:
        return f"{yi - 1}-12"
    return f"{yi}-{mi - 1:02d}"


def _default_last_month() -> str:
    """当前日期的上一个完整月 'YYYY-MM'。"""
    today = date.today()
    y, m = today.year, today.month
    if m == 1:
        return f"{y - 1}-12"
    return f"{y}-{m - 1:02d}"


_FINANCE_SQL = """
SELECT record_type, category,
       COALESCE(SUM(actual_amount), 0)::numeric(18,2) AS amount
  FROM smart_bi_finance_data
 WHERE factory_id = $1
   AND to_char(record_date, 'YYYY-MM') = $2
 GROUP BY record_type, category
"""


def _classify_amounts(rows: list[dict[str, Any]]) -> dict[str, float]:
    """把 (record_type, category, amount) 行归类成 {revenue, food_cost, labor_cost}。"""
    out: dict[str, float] = {}
    for r in rows:
        rtype = (r.get("record_type") or "").upper()
        category = r.get("category") or ""
        amount_raw = r.get("amount")
        try:
            amount = abs(float(amount_raw)) if amount_raw is not None else 0.0
        except (TypeError, ValueError):
            continue
        if rtype == "REVENUE" or any(k in category for k in _REVENUE_KEYWORDS):
            out["revenue"] = out.get("revenue", 0.0) + amount
        elif any(k in category for k in _FOOD_KEYWORDS):
            out["food_cost"] = out.get("food_cost", 0.0) + amount
        elif any(k in category for k in _LABOR_KEYWORDS):
            out["labor_cost"] = out.get("labor_cost", 0.0) + amount
    return out


async def _fetch_financial_data(
    pool: Any, factory_id: str, period_month: str
) -> dict[str, Any]:
    """聚合 current + previous 期间的 financial_data。"""
    prev = _prev_period(period_month)
    async with pool.acquire() as conn:
        try:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        except Exception:  # noqa: BLE001
            pass
        cur_rows = await conn.fetch(_FINANCE_SQL, factory_id, period_month)
        prev_rows = await conn.fetch(_FINANCE_SQL, factory_id, prev)

    current = _classify_amounts([dict(r) for r in cur_rows])
    previous = _classify_amounts([dict(r) for r in prev_rows])
    return {"current": current, "previous": previous}


async def refresh_snapshot_for_factory(
    factory_id: str,
    period_month: Optional[str] = None,
    store_id: Optional[str] = None,
    sub_sector: str = "火锅",
    pool: Any = None,
) -> dict[str, Any]:
    """重算并 upsert 一个工厂/期间的价值快照。

    Args:
        period_month: 'YYYY-MM'; None → 上一个完整月。
        pool: 可注入 (测试/调用方已有 pool); None → get_pg_pool()。

    Returns:
        {success, message, totalMonth, totalAnnual}。永不抛 (fire-and-forget 安全)。
    """
    from smartbi.services.restaurant.value_orchestrator import compute_signals_from_inputs
    from smartbi.services.restaurant.value_snapshot_service import compute_and_upsert_snapshot

    period = period_month or _default_last_month()

    try:
        if pool is None:
            from smartbi.config import get_pg_pool
            pool = await get_pg_pool()
        if pool is None:
            return {"success": False, "message": "smartbi_db pool 不可用"}

        financial_data = await _fetch_financial_data(pool, factory_id, period)
        revenue = (financial_data.get("current") or {}).get("revenue")
        if not revenue or revenue <= 0:
            # 连营收都没有 → 不造空行 (避免误导"算过了但无数据")。
            logger.info(
                "[value-refresh] factory=%s period=%s no revenue data → skip upsert",
                factory_id, period,
            )
            return {"success": False, "message": f"{period} 无营收数据, 跳过快照", "reason": "no_data"}

        # TODO(#54): 损溢 shrinkage_report 当前无 Python 端聚合源 (Java fetcher 算);
        # 价值回馈 MVP 先不带损溢 (留 None), 待 shrinkage Python 化或经 Java 透传后补。
        result = compute_signals_from_inputs(
            financial_data=financial_data, sub_sector=sub_sector, shrinkage_report=None,
        )

        upsert = await compute_and_upsert_snapshot(
            pool, factory_id, period, store_id,
            signals=result["signals"],
            diagnosis_count=result["diagnosisCount"],
            critical_count=result["criticalCount"],
            rx_action_count=result["rxActionCount"],
        )
        return upsert
    except Exception as e:  # noqa: BLE001 — fire-and-forget safe
        logger.error(
            "[value-refresh] factory=%s period=%s failed: %s",
            factory_id, period, e, exc_info=True,
        )
        return {"success": False, "message": f"刷新失败: {e}"}
