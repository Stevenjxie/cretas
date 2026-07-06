"""Gold-backed restaurant daily-ops query router.

Sits parallel to materialized_analytics/query_router.py. This one handles
queries that should be answered from agg_restaurant_daily_* tables rather
than per-upload xlsx cache.

Trigger points:
- smartbi/api/chat.py stream handler: before the materialized-cache router
  runs, check match_restaurant_ops(query); if hit, serve from Gold and
  return early (fast path, <100ms).

Template codes (RESTAURANT_OPS_* prefix to namespace from xlsx templates):
  RESTAURANT_OPS_REQUISITION_TREND — last N days requisition cost + top 5 ingredients
  RESTAURANT_OPS_WASTAGE_TOP       — wastage ranking by ingredient or type
  RESTAURANT_OPS_RECIPE_COST       — dish food cost ranking
  RESTAURANT_OPS_STOCK_SHORTAGE    — stocktaking shortage hot spots

Sample queries for each code used both for keyword matching AND for RAG
semantic routing (Phase 3 of learned-template plan).
"""
from __future__ import annotations

import inspect
import logging
import re
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)


# Keyword patterns per ops template. Each entry: (code, [[kw_group_1], [kw_group_2], ...]).
# Query must contain at least one keyword from each group. First match wins.
_OPS_PATTERNS: List[Tuple[str, List[List[str]]]] = [
    # Wastage: "损耗/浪费" + "最多/哪/top/类型/占比"
    (
        "RESTAURANT_OPS_WASTAGE_TOP",
        [["损耗", "浪费", "报损", "腐坏", "过期"],
         ["最多", "排名", "top", "TOP", "哪个", "哪种", "类型", "占比", "分布", "原因", "多少"]],
    ),
    # Stocktaking shortage: "盘点/盘亏/亏损" + "哪/top/最多"
    (
        "RESTAURANT_OPS_STOCK_SHORTAGE",
        [["盘点", "盘亏", "盘损", "库存差异", "账实差"],
         ["哪个", "哪些", "最多", "top", "TOP", "排名", "频率", "经常"]],
    ),
    # Per-store margin (MUST come BEFORE dish-level gross_margin):
    # "哪家店赚钱" = store scope, not dish scope. Group-1 = store scope,
    # Group-2 = margin vocabulary.
    # Apr 25 2026 routing fix: removed "最好" from group-2 — it was over-broadly
    # matching unrelated queries like "哪个店服务最好" / "哪家店环境最好" /
    # "哪家店评价最好", which then routed to STORE_MARGIN (30-day POS window) and
    # returned the misleading "近 30 天无 POS 销售数据" message even when full
    # POS data was present. All legitimate margin triggers ("哪家店最赚钱",
    # "门店毛利排行", "分店利润对比", etc.) still match via the remaining
    # margin-specific vocabulary. See aiquery-OBSERVATIONS.md (Apr 24 audit C-4).
    (
        "RESTAURANT_OPS_STORE_MARGIN",
        [["门店", "店", "分店", "店铺", "哪家"],
         ["毛利", "毛利率", "赚钱", "净赚", "利润"]],
    ),
    # Cross-module gross margin — dish-level (MUST come BEFORE recipe_cost since
    # "毛利" on its own = margin not cost). Plan C's signature feature.
    # Apr 25 2026 routing fix (C-2 audit follow-up): removed bare "菜" from
    # group-2 — it created over-broad matches against 菜单/菜谱 type queries
    # (e.g. "菜单利润分析" wrongly routed dish-level margin instead of
    # menu-level). Replaced with the explicit dish-scope tokens 菜品 / 菜系 /
    # 菜价 plus existing dish-scoped pronouns. "菜单怎么改" / "菜价怎么样"
    # still fall through cleanly because they lack a group-1 margin keyword;
    # "菜系毛利率" / "菜品毛利率排行" continue to match (legitimate).
    (
        "RESTAURANT_OPS_GROSS_MARGIN",
        [["毛利", "毛利率", "净赚", "赚钱", "挣钱", "利润"],
         ["菜品", "菜系", "菜价", "哪道", "哪个", "排行", "排名", "top", "TOP", "最高", "最赚"]],
    ),
    # Recipe cost (食材成本 only — 毛利 moved to gross_margin)
    (
        "RESTAURANT_OPS_RECIPE_COST",
        [["食材成本", "配方成本", "菜品成本", "食材费用"],
         ["最高", "最低", "哪道", "哪个", "top", "TOP", "排名", "多少"]],
    ),
    # Requisition trend: "领料/领/领用" + "趋势/最多/食材"
    (
        "RESTAURANT_OPS_REQUISITION_TREND",
        [["领料", "领用", "用料", "食材用量"],
         ["趋势", "最多", "top", "TOP", "哪个食材", "哪些食材", "排名"]],
    ),
    # Revenue trend / YoY / MoM analysis (Jun 2026 WS6 routing fix):
    # "同比/环比/趋势/增长/下降/月度变化" → revenue trend served from Gold
    # trend_bundle (full 2025+2026 history), NOT the per-upload xlsx template
    # router (which fails for qhj with "缺少按时间拆分的同比环比数据" because the
    # selected upload is a partial POS part or a review xlsx).
    #
    # MUST come AFTER RESTAURANT_OPS_REQUISITION_TREND so the more specific
    # "领料趋势" (requires 领料 + 趋势) still wins; a bare "营收趋势" / "同比环比"
    # has no 领料 group-1 keyword so it falls through to here.
    #
    (
        "RESTAURANT_OPS_TREND_ANALYSIS",
        [["同比", "环比", "趋势", "增长", "下降", "月度变化", "走势"]],
    ),
    # Natural owner/report phrasing must route here too. Examples:
    # "查询本周营收", "今天查订单", "本月营业额". These are not free-form LLM
    # questions; they are direct report reads and should not fall back to the
    # per-upload template router.
    (
        "RESTAURANT_OPS_SALES_SUMMARY",
        [["营收", "营业额", "销售额", "销售", "销售情况", "客单价", "平均每单", "订单", "单量"],
         ["查", "查询", "看", "看看", "本周", "这周", "今天", "今日", "本月", "这个月", "最近", "统计", "汇总", "分析", "情况", "怎么样"]],
    ),
    (
        "RESTAURANT_OPS_SALES_SUMMARY",
        [["营收", "营业额", "销售额", "销售情况", "客单价", "平均每单", "订单", "单量"],
         ["表现", "怎么样", "多少", "情况", "总", "整体"]],
    ),
    (
        "RESTAURANT_OPS_SALES_SUMMARY",
        [["门店", "店", "分店", "店铺", "哪家"],
         ["销售", "营收", "营业额", "业绩", "订单", "单量"],
         ["对比", "比较", "排名", "最好", "最值得复制", "复制", "标杆", "表现"]],
    ),
]


# Sample queries per template for RAG semantic routing + user suggestions.
SAMPLE_QUERIES: Dict[str, List[str]] = {
    "RESTAURANT_OPS_WASTAGE_TOP": [
        "最近7天损耗最多的食材是什么",
        "哪种损耗类型最多?",
        "过期和破损哪个更严重",
        "损耗金额排名",
        "这个月损耗分布如何",
        "浪费最多的菜是哪些",
        "报损原因占比",
        "损耗食材 top 10",
    ],
    "RESTAURANT_OPS_STOCK_SHORTAGE": [
        "最近哪个食材盘亏最严重",
        "盘点差异最大的食材 top 10",
        "盘亏金额排名",
        "哪些食材经常盘亏",
        "账实差距最大的是哪些食材",
        "本月盘点情况",
    ],
    "RESTAURANT_OPS_RECIPE_COST": [
        "食材成本最高的菜是哪些",
        "配方成本 top 10",
        "毛利最低的菜品",
        "哪道菜食材费用最贵",
        "菜品成本排行",
        "食材占销售额比重最高的菜",
    ],
    "RESTAURANT_OPS_REQUISITION_TREND": [
        "最近30天领料趋势",
        "领用最多的食材是哪些",
        "本月食材用量 top 10",
        "哪些食材领料频率最高",
        "领料数量趋势",
        "食材消耗排名",
    ],
    "RESTAURANT_OPS_GROSS_MARGIN": [
        "哪道菜毛利最高",
        "菜品毛利率排行",
        "最赚钱的菜 top 10",
        # Apr 25 2026: tightened from "哪些菜净赚最多" (bare 菜 was removed
        # from group-2 to stop 菜单/菜谱 false-positives) — explicit dish
        # marker now required.
        "哪些菜品净赚最多",
        "菜品毛利对比",
        "利润最高的菜品",
        "售价减去食材成本最多的菜",
    ],
    "RESTAURANT_OPS_STORE_MARGIN": [
        "哪家店最赚钱",
        "门店毛利排行",
        "哪家门店毛利率最高",
        "分店利润对比",
        "店铺毛利分析",
        "哪家店净赚最多",
    ],
    "RESTAURANT_OPS_TREND_ANALYSIS": [
        "同比和环比分析，识别增长和下降趋势",
        "分析销售额的月度变化趋势",
        "营收趋势",
        "增长趋势",
        "销售趋势",
        "月度趋势",
        "营业额走势",
        "近期增长还是下降",
    ],
    "RESTAURANT_OPS_SALES_SUMMARY": [
        "总营收和客单价表现怎么样",
        "整体销售情况怎么样",
        "订单数和平均每单表现如何",
    ],
}


def match_restaurant_ops(query: str) -> Optional[str]:
    """Return the ops template code if query matches, else None.

    Pure keyword match for <1ms routing. RAG-based fallback can live in
    chat.py's existing fallback chain.
    """
    if not query:
        return None
    q = query.strip()
    for code, groups in _OPS_PATTERNS:
        if all(any(kw in q for kw in group) for group in groups):
            return code
    return None


@dataclass
class OpsAnswer:
    """Structured answer returned from resolve_* functions."""
    code: str
    title: str
    answer_text: str
    charts: List[Dict[str, Any]]
    kpis: List[Dict[str, Any]]
    meta: Dict[str, Any]


def _date_text(value: Any) -> str:
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


def _actual_window_text(start_date: Any, end_date: Any, requested_days: int) -> str:
    if start_date and end_date:
        return f"{_date_text(start_date)} 至 {_date_text(end_date)}"
    return f"最近 {requested_days} 天"


def _resolve_sales_date_range(
    query: Optional[str],
    *,
    today: Optional[date] = None,
) -> Tuple[Tuple[Optional[date], Optional[date]], str]:
    """Resolve common owner-facing time phrases for sales summary prompts."""
    text = (query or "").strip()
    anchor = today or date.today()

    recent_match = re.search(r"最近\s*(\d{1,3})\s*天", text)
    if recent_match:
        days = max(1, min(int(recent_match.group(1)), 365))
        return (anchor - timedelta(days=days - 1), anchor), f"最近{days}天"

    if any(token in text for token in ("今天", "今日")):
        return (anchor, anchor), "今天"

    if any(token in text for token in ("本周", "这周", "本星期", "这星期")):
        return (anchor - timedelta(days=anchor.weekday()), anchor), "本周"

    if any(token in text for token in ("本月", "这个月")):
        return (anchor.replace(day=1), anchor), "本月"

    return (None, None), "全部历史"


async def resolve_wastage_top(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
) -> OpsAnswer:
    """Top N wastage ingredients + wastage type breakdown, last N days."""
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        top_rows = await conn.fetch(
            """
            SELECT i.name, i.category, i.unit,
                   SUM(a.value_num)::float AS qty
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1 AND a.kpi_kind = 'wastage_qty'
               AND a.date >= CURRENT_DATE - ($2::int)
             GROUP BY i.name, i.category, i.unit
             ORDER BY qty DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n,
        )
        type_rows = await conn.fetch(
            """
            SELECT a.dim_value_str AS type, SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
             WHERE a.factory_id = $1 AND a.kpi_kind = 'wastage_cost_by_type'
               AND a.date >= CURRENT_DATE - ($2::int)
             GROUP BY a.dim_value_str
             ORDER BY cost DESC NULLS LAST
            """,
            factory_id, days,
        )
        total = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(wastage_qty_total), 0)::float AS total_qty,
                   COALESCE(SUM(wastage_cost_total), 0)::float AS total_cost,
                   COALESCE(SUM(wastage_count), 0)::int AS total_count
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1 AND date >= CURRENT_DATE - ($2::int)
            """,
            factory_id, days,
        )

    type_name_map = {
        "EXPIRED": "过期", "DAMAGED": "破损", "SPOILED": "变质",
        "PROCESSING": "加工损耗", "OTHER": "其他",
    }
    top_list_text = "\n".join([
        f"  {i+1}. {r['name']} ({r['category'] or '—'}): {r['qty']:.2f} {r['unit'] or ''}"
        for i, r in enumerate(top_rows)
    ]) or "  (近 %d 天无损耗记录)" % days

    type_summary = "、".join([
        f"{type_name_map.get(r['type'], r['type'])} ¥{r['cost']:.2f}"
        for r in type_rows[:5]
    ]) or "无数据"

    answer = (
        f"近 {days} 天损耗总览:\n"
        f"- 总损耗 {total['total_count']} 次, {total['total_qty']:.2f} 单位, 损失 ¥{total['total_cost']:.2f}\n"
        f"- 损耗类型分布: {type_summary}\n\n"
        f"Top {len(top_rows)} 损耗食材 (按数量):\n{top_list_text}\n\n"
        f"建议动作:\n"
        f"  1. 先把损耗金额最高的类型拆到门店和班次，确认是保存、加工还是报损登记问题。\n"
        f"  2. 对 Top 损耗食材设一周复盘线，超过日均用量或报损阈值时要求后厨说明原因。\n"
        f"  3. 对水产、肉类等高价值食材优先复核收货净重和分切标准，避免损耗被当成正常用料。"
    )

    charts = []
    if top_rows:
        charts.append({
            "chartType": "bar",
            "title": f"Top {len(top_rows)} 损耗食材 (近{days}天)",
            "xAxis": {"data": [r["name"] for r in top_rows]},
            "series": [{"name": "损耗量", "type": "bar", "data": [r["qty"] for r in top_rows]}],
        })
    if type_rows:
        charts.append({
            "chartType": "pie",
            "title": "损耗类型占比",
            "series": [{
                "name": "损耗类型", "type": "pie",
                "data": [{"name": type_name_map.get(r["type"], r["type"]), "value": r["cost"]} for r in type_rows],
            }],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_WASTAGE_TOP",
        title=f"近{days}天损耗分析",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "损耗次数", "value": total["total_count"], "rawValue": total["total_count"]},
            {"title": "损耗量", "value": f"{total['total_qty']:.1f}", "rawValue": total["total_qty"]},
            {"title": "损耗金额", "value": f"¥{total['total_cost']:.2f}", "rawValue": total["total_cost"]},
            {"title": "Top 食材", "value": top_rows[0]["name"] if top_rows else "—", "rawValue": 0},
        ],
        meta={"window_days": days, "top_n": top_n},
    )


async def resolve_stock_shortage(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
) -> OpsAnswer:
    """Top N stocktaking shortage ingredients, last N days."""
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        rows = await conn.fetch(
            """
            SELECT i.name, i.category, i.unit,
                   SUM(a.value_num)::float AS shortage_qty
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1 AND a.kpi_kind = 'stocktaking_shortage_qty'
               AND a.date >= CURRENT_DATE - ($2::int)
             GROUP BY i.name, i.category, i.unit
             HAVING SUM(a.value_num) > 0
             ORDER BY shortage_qty DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n,
        )
        total = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(stocktaking_shortage_total), 0)::float AS shortage,
                   COALESCE(SUM(stocktaking_surplus_total), 0)::float AS surplus,
                   COALESCE(SUM(stocktaking_count), 0)::int AS count
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1 AND date >= CURRENT_DATE - ($2::int)
            """,
            factory_id, days,
        )

    top_text = "\n".join([
        f"  {i+1}. {r['name']} ({r['category'] or '—'}): 盘亏 {r['shortage_qty']:.2f} {r['unit'] or ''}"
        for i, r in enumerate(rows)
    ]) or f"  (近 {days} 天无盘亏记录)"

    answer = (
        f"近 {days} 天盘点总览:\n"
        f"- 盘点 {total['count']} 次, 盘亏总量 {total['shortage']:.2f}, 盘盈总量 {total['surplus']:.2f}\n\n"
        f"Top {len(rows)} 盘亏食材:\n{top_text}\n\n"
        f"建议动作:\n"
        f"  1. 对盘亏最高的食材先核查领料单、报损单和实际库存照片，找出未登记消耗。\n"
        f"  2. 把连续盘亏食材纳入每日闭店抽盘，连续两天异常就回溯到班组和菜品。\n"
        f"  3. 对调料类盘亏优先检查称量标准和容器换算，减少账实口径不一致。"
    )
    charts = []
    if rows:
        charts.append({
            "chartType": "bar",
            "title": f"Top {len(rows)} 盘亏食材 (近{days}天)",
            "xAxis": {"data": [r["name"] for r in rows]},
            "series": [{"name": "盘亏量", "type": "bar", "data": [r["shortage_qty"] for r in rows]}],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_STOCK_SHORTAGE",
        title=f"近{days}天盘点差异分析",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "盘点次数", "value": total["count"], "rawValue": total["count"]},
            {"title": "盘亏总量", "value": f"{total['shortage']:.1f}", "rawValue": total["shortage"]},
            {"title": "盘盈总量", "value": f"{total['surplus']:.1f}", "rawValue": total["surplus"]},
            {"title": "Top 盘亏", "value": rows[0]["name"] if rows else "—", "rawValue": 0},
        ],
        meta={"window_days": days, "top_n": top_n},
    )


async def resolve_recipe_cost(
    smartbi_pool, factory_id: str, top_n: int = 10,
) -> OpsAnswer:
    """Top N dishes by food cost (standard_qty × unit_price rollup).

    Joins cretas_db.product_types for dish names at query time (no dim_product
    ETL needed yet — see 2026_04_24_recipe_product_source_pk.sql rationale).
    """
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        rows = await conn.fetch(
            """
            SELECT c.product_source_pk, c.food_cost, c.ingredient_count, c.has_price_data
              FROM agg_restaurant_product_cost c
             WHERE c.factory_id = $1 AND c.food_cost > 0
             ORDER BY c.food_cost DESC NULLS LAST
             LIMIT $2
            """,
            factory_id, top_n,
        )
    source_pks = [r["product_source_pk"] for r in rows]

    # Look up dish names from cretas_db.product_types (separate pool).
    name_map: Dict[str, str] = {}
    if source_pks:
        try:
            import asyncpg as _asyncpg
            from config import get_settings as _get_settings
            cretas_url = _get_settings().food_kb_db_url
            cretas = await _asyncpg.connect(cretas_url)
            try:
                name_rows = await cretas.fetch(
                    "SELECT id, name FROM product_types WHERE factory_id = $1 AND id = ANY($2::text[])",
                    factory_id, source_pks,
                )
                name_map = {r["id"]: r["name"] for r in name_rows}
            finally:
                await cretas.close()
        except Exception as e:
            logger.warning(f"[recipe_cost] dish name lookup failed: {e}")

    top_text = "\n".join([
        f"  {i+1}. {name_map.get(r['product_source_pk'], '#' + r['product_source_pk'])}: ¥{r['food_cost']:.2f} ({r['ingredient_count']} 种食材)"  # noqa: E501
        for i, r in enumerate(rows)
    ]) or "  (尚未录入配方数据或食材单价为空)"

    answer = (
        f"菜品食材成本 Top {len(rows)}:\n{top_text}\n\n"
        f"注: 成本 = 标准用量 × 食材单价. 售价数据可从 POS Gold (fact_pos_item) 获取后计算毛利."
    )
    charts = []
    if rows:
        charts.append({
            "chartType": "bar",
            "title": f"Top {len(rows)} 高成本菜品",
            "xAxis": {"data": [name_map.get(r["product_source_pk"], r["product_source_pk"]) for r in rows]},
            "series": [{"name": "食材成本", "type": "bar", "data": [r["food_cost"] for r in rows]}],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_RECIPE_COST",
        title="菜品食材成本排行",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "菜品数", "value": len(rows), "rawValue": len(rows)},
            {"title": "最高成本", "value": f"¥{rows[0]['food_cost']:.2f}" if rows else "—", "rawValue": rows[0]["food_cost"] if rows else 0},  # noqa: E501
            {"title": "Top 菜品", "value": name_map.get(rows[0]["product_source_pk"], "—") if rows else "—", "rawValue": 0},  # noqa: E501
        ],
        meta={"top_n": top_n},
    )


async def resolve_requisition_trend(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
) -> OpsAnswer:
    """Requisition trend + Top N ingredients by qty, last N days."""
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        trend = await conn.fetch(
            """
            SELECT date, requisition_qty_total::float AS qty,
                   requisition_cost_total::float AS cost
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1 AND date >= CURRENT_DATE - ($2::int)
             ORDER BY date
            """,
            factory_id, days,
        )
        top = await conn.fetch(
            """
            SELECT i.name, i.category, i.unit,
                   SUM(a.value_num)::float AS qty
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1 AND a.kpi_kind = 'requisition_qty'
               AND a.date >= CURRENT_DATE - ($2::int)
             GROUP BY i.name, i.category, i.unit
             ORDER BY qty DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n,
        )

    total_qty = sum(r["qty"] or 0 for r in trend)
    total_cost = sum(r["cost"] or 0 for r in trend)
    top_text = "\n".join([
        f"  {i+1}. {r['name']} ({r['category'] or '—'}): {r['qty']:.2f} {r['unit'] or ''}"
        for i, r in enumerate(top)
    ]) or "  (近 %d 天无领料记录)" % days

    answer = (
        f"近 {days} 天领料总览:\n"
        f"- 总量 {total_qty:.2f} 单位, 估算成本 ¥{total_cost:.2f}, {len(trend)} 天有活动\n\n"
        f"Top {len(top)} 领用食材:\n{top_text}\n\n"
        f"建议动作:\n"
        f"  1. 把 Top 领用食材和畅销菜、损耗榜交叉看，判断是销量驱动还是领用过量。\n"
        f"  2. 对领用量稳定但销售没有同步增长的食材，先查备料标准和退料记录。\n"
        f"  3. 对成本占比高的食材设置日领用上限，超过上限需店长复核。"
    )
    charts = [{
        "chartType": "line",
        "title": f"近{days}天领料数量趋势",
        "xAxis": {"data": [r["date"].isoformat() for r in trend]},
        "series": [{"name": "领料量", "type": "line", "data": [r["qty"] for r in trend]}],
    }]
    if top:
        charts.append({
            "chartType": "bar",
            "title": f"Top {len(top)} 领用食材",
            "xAxis": {"data": [r["name"] for r in top]},
            "series": [{"name": "领用量", "type": "bar", "data": [r["qty"] for r in top]}],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_REQUISITION_TREND",
        title=f"近{days}天领料趋势+食材 Top {top_n}",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "总领料量", "value": f"{total_qty:.1f}", "rawValue": total_qty},
            {"title": "估算成本", "value": f"¥{total_cost:.2f}", "rawValue": total_cost},
            {"title": "活动天数", "value": len(trend), "rawValue": len(trend)},
            {"title": "Top 食材", "value": top[0]["name"] if top else "—", "rawValue": 0},
        ],
        meta={"window_days": days, "top_n": top_n},
    )


async def resolve_gross_margin(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
) -> OpsAnswer:
    """Cross-module gross margin analysis: POS sold_price × recipe food_cost.

    Join logic:
      fact_pos_item (POS 卖价 + qty) →
      JOIN dim_product (POS side, name) →
      JOIN product_types (cretas side, name = dim_product.name via normalized name match) →
      JOIN agg_restaurant_product_cost (food cost per dish, keyed by product_source_pk=product_types.id) →
      compute gross_profit = sum(amount) - sum(qty × food_cost) per dish

    Plan C's signature feature — unlocks real gross margin analysis that
    was impossible before Silver/Gold restaurant ops layer.
    """
    # Need cretas connection for product_types name↔id lookup
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)

        # Step 1: POS sales aggregated per dish. Anchor the window to the
        # latest transaction that can be joined to dim_product; demo datasets
        # can have sparse tail transactions with empty product_id.
        pos_rows = await conn.fetch(
            """
            WITH anchor AS (
                SELECT MAX(t2.date) AS end_date
                  FROM fact_pos_item i2
                  JOIN fact_pos_transaction t2 ON t2.id = i2.transaction_id
                  JOIN dim_product p2
                    ON p2.product_id = i2.product_id
                   AND p2.factory_id = i2.factory_id
                 WHERE i2.factory_id = $1
                   AND t2.factory_id = $1
            )
            SELECT p.product_id, p.name AS dish_name, p.normalized_name,
                   SUM(i.qty)::float AS total_qty,
                   SUM(i.amount)::float AS total_revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills,
                   MIN(t.date) AS window_start,
                   MAX(t.date) AS window_end
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p ON p.product_id = i.product_id
              CROSS JOIN anchor
             WHERE i.factory_id = $1
               AND t.factory_id = $1
               AND p.factory_id = $1
               AND anchor.end_date IS NOT NULL
               AND t.date >= anchor.end_date - ($2::int)
               AND t.date <= anchor.end_date
             GROUP BY p.product_id, p.name, p.normalized_name
             ORDER BY total_revenue DESC NULLS LAST
            """,
            factory_id, days,
        )

    if not pos_rows:
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"菜品毛利分析 (近{days}天)",
            answer_text=(
                f"近 {days} 天无 POS 销售数据.\n"
                f"提示: 上传 POS 账单 Excel 到 SmartBI, materialize 后会写入 fact_pos_item, "
                f"本分析即可运行. 需要同时有配方+食材单价才能计算毛利."
            ),
            charts=[], kpis=[],
            meta={"window_days": days, "no_pos_data": True},
        )

    window_start = min((r["window_start"] for r in pos_rows if r["window_start"]), default=None)
    window_end = max((r["window_end"] for r in pos_rows if r["window_end"]), default=None)
    window_label = _actual_window_text(window_start, window_end, days)

    # Step 2: look up cretas product_types by name (primary) + dim_product_alias (fallback).
    # P0-2: alias lets merchants bind POS name → any product_type, handles the
    # "POS xlsx name vs recipe name" drift problem (括号/空格/[] differences).
    normalized_names = list({r["normalized_name"] for r in pos_rows})
    cretas_map: Dict[str, str] = {}  # pos_name → source_pk (product_type_id)
    try:
        import asyncpg as _asyncpg
        from config import get_settings as _get_settings
        cretas_url = _get_settings().food_kb_db_url
        cretas = await _asyncpg.connect(cretas_url)
        try:
            # Primary: exact name match
            name_rows = await cretas.fetch(
                "SELECT id, name FROM product_types WHERE factory_id = $1 AND name = ANY($2::text[])",
                factory_id, normalized_names,
            )
            for r in name_rows:
                cretas_map[r["name"]] = r["id"]
            # Fallback: alias table (table may not exist on older schemas → catch)
            unmapped = [n for n in normalized_names if n not in cretas_map]
            if unmapped:
                try:
                    alias_rows = await cretas.fetch(
                        """SELECT pos_name, product_type_id FROM dim_product_alias
                            WHERE factory_id = $1 AND pos_name = ANY($2::text[])""",
                        factory_id, unmapped,
                    )
                    for r in alias_rows:
                        cretas_map[r["pos_name"]] = r["product_type_id"]
                except Exception as e:
                    # Table doesn't exist yet — ignore, name match still works
                    if "does not exist" not in str(e):
                        logger.warning(f"[gross_margin] alias lookup failed: {e}")
        finally:
            await cretas.close()
    except Exception as e:
        logger.warning(f"[gross_margin] cretas name lookup failed: {e}")

    # Step 3: load food cost per source_pk
    cost_map: Dict[str, float] = {}
    if cretas_map:
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            cost_rows = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS food_cost
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1 AND product_source_pk = ANY($2::text[])
                """,
                factory_id, list(cretas_map.values()),
            )
            cost_map = {r["product_source_pk"]: r["food_cost"] for r in cost_rows}

    # Step 4: compute margin per dish
    enriched = []
    for r in pos_rows:
        source_pk = cretas_map.get(r["normalized_name"])
        food_cost = cost_map.get(source_pk, 0) if source_pk else 0
        total_cost = food_cost * r["total_qty"]
        gross_profit = r["total_revenue"] - total_cost
        margin_rate = gross_profit / r["total_revenue"] if r["total_revenue"] > 0 else 0
        enriched.append({
            "name": r["dish_name"],
            "qty": r["total_qty"],
            "revenue": r["total_revenue"],
            "bills": r["bills"],
            "food_cost_unit": food_cost,
            "gross_profit": gross_profit,
            "margin_rate": margin_rate,
            "has_cost": food_cost > 0,
        })
    enriched.sort(key=lambda x: x["gross_profit"], reverse=True)
    top_slice = enriched[:top_n]
    # "总营收" 用全菜营收 (users want total). "平均毛利率"/"总毛利" 用仅有
    # cost 菜, 避免无配方菜 (gross_profit=revenue) 污染真实毛利率.
    total_rev = sum(e["revenue"] for e in enriched)
    with_cost = [e for e in enriched if e["has_cost"]]
    total_rev_with_cost = sum(e["revenue"] for e in with_cost)
    total_profit = sum(e["gross_profit"] for e in with_cost)
    avg_margin = total_profit / total_rev_with_cost if total_rev_with_cost > 0 else 0
    low_margin = sorted(
        [e for e in enriched if e["has_cost"] and e["revenue"] >= 1000],
        key=lambda x: x["margin_rate"],
    )[:3]

    top_text = "\n".join([
        f"  {i+1}. {e['name']}: 营收 ¥{e['revenue']:.2f} / 成本 ¥{e['food_cost_unit'] * e['qty']:.2f} / "
        f"毛利 ¥{e['gross_profit']:.2f} ({e['margin_rate'] * 100:.1f}%)"
        + ("" if e["has_cost"] else " [无配方数据, 成本按0估]")
        for i, e in enumerate(top_slice)
    ])
    missing_cost_count = len([e for e in enriched if not e["has_cost"]])
    missing_note = (
        f"\n\n注: {missing_cost_count} 个菜品缺配方/食材单价, 毛利按无成本估算. "
        f"录入配方后 ETL 自动重算."
        if missing_cost_count > 0 else ""
    )
    answer = (
        f"近 {days} 天菜品毛利分析:\n"
        f"- 总营收 ¥{total_rev:,.2f}, 总毛利 ¥{total_profit:,.2f}, 平均毛利率 {avg_margin * 100:.1f}%\n"
        f"- {len(with_cost)}/{len(enriched)} 菜品有完整成本数据\n\n"
        f"Top {len(top_slice)} 毛利菜品 (按绝对毛利):\n{top_text}{missing_note}"
    )

    charts = [{
        "chartType": "bar",
        "title": f"Top {len(top_slice)} 毛利菜品 (近{days}天)",
        "xAxis": {"data": [e["name"] for e in top_slice]},
        "series": [
            {"name": "营收", "type": "bar", "data": [e["revenue"] for e in top_slice]},
            {"name": "毛利", "type": "bar", "data": [e["gross_profit"] for e in top_slice]},
        ],
    }]

    charts[0]["title"] = f"Top {len(top_slice)} 毛利菜品 ({window_label})"
    charts[0]["series"][0]["name"] = "营收"
    charts[0]["series"][1]["name"] = "毛利"

    low_margin_text = "\n".join([
        f"  - {e['name']}: 毛利率 {e['margin_rate'] * 100:.1f}%, 营收 ¥{e['revenue']:,.2f}"
        for e in low_margin
    ]) or "  - 暂无明显低毛利菜品。"
    answer = (
        f"菜品毛利分析（{window_label}）\n"
        f"- 总营收 ¥{total_rev:,.2f}, 总毛利 ¥{total_profit:,.2f}, 平均毛利率 {avg_margin * 100:.1f}%\n"
        f"- {len(with_cost)}/{len(enriched)} 个销售菜品有完整成本数据；缺成本菜品不纳入毛利率均值，避免虚高。\n\n"
        f"Top {len(top_slice)} 毛利菜品（按绝对毛利）:\n{top_text}\n\n"
        f"需要关注的低毛利菜品:\n{low_margin_text}\n\n"
        f"建议动作:\n"
        f"  1. 对高营收低毛利菜品先复核售价、赠品和食材规格，优先做小幅提价或份量标准化。\n"
        f"  2. 对高毛利高销量菜品加大套餐露出和门店推荐，作为拉升整体毛利率的主推款。\n"
        f"  3. 对缺成本菜品补齐配方和最近进价，否则单表排行会误判真实利润。{missing_note}"
    )

    return OpsAnswer(
        code="RESTAURANT_OPS_GROSS_MARGIN",
        title=f"菜品毛利分析 (近{days}天)",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "总营收", "value": f"¥{total_rev:,.0f}", "rawValue": total_rev},
            {"title": "总毛利", "value": f"¥{total_profit:,.0f}", "rawValue": total_profit},
            {"title": "平均毛利率", "value": f"{avg_margin * 100:.1f}%", "rawValue": avg_margin},
            {"title": "最赚菜品", "value": top_slice[0]["name"] if top_slice else "—", "rawValue": 0},
        ],
        meta={
            "window_days": days, "top_n": top_n,
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "missing_cost_count": missing_cost_count,
            "total_dishes": len(enriched),
            "low_margin_dishes": [e["name"] for e in low_margin],
        },
    )


async def resolve_store_margin(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
) -> OpsAnswer:
    """Per-store gross margin: fact_pos_item × fact_pos_transaction.store_id
    × dim_store.name × recipe food_cost. Connects POS bill → store → dish → cost
    for the chain-owner's core question "which store is most profitable".
    """
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
        # Per-store per-dish aggregation — need this granularity to compute
        # cost correctly (dish-level food_cost × qty sold at each store).
        store_dish_rows = await conn.fetch(
            """
            WITH anchor AS (
                SELECT MAX(t2.date) AS end_date
                  FROM fact_pos_item i2
                  JOIN fact_pos_transaction t2 ON t2.id = i2.transaction_id
                  JOIN dim_product p2
                    ON p2.product_id = i2.product_id
                   AND p2.factory_id = i2.factory_id
                  JOIN (
                    SELECT DISTINCT store_id
                      FROM agg_daily
                     WHERE factory_id = $1
                    UNION
                    SELECT DISTINCT store_id
                      FROM fact_pos_transaction
                     WHERE factory_id = $1
                       AND NOT EXISTS (
                         SELECT 1 FROM agg_daily WHERE factory_id = $1
                       )
                  ) scope ON scope.store_id = t2.store_id
                  JOIN dim_store s2
                    ON s2.store_id = t2.store_id
                   AND s2.factory_id = t2.factory_id
                 WHERE i2.factory_id = $1
                   AND t2.factory_id = $1
            )
            SELECT s.store_id, s.name AS store_name,
                   p.name AS dish_name, p.normalized_name,
                   SUM(i.qty)::float AS qty,
                   SUM(i.amount)::float AS revenue,
                   COUNT(DISTINCT i.transaction_id)::int AS bills,
                   MIN(t.date) AS window_start,
                   MAX(t.date) AS window_end
              FROM fact_pos_item i
              JOIN fact_pos_transaction t ON t.id = i.transaction_id
              JOIN dim_product p
                ON p.product_id = i.product_id
               AND p.factory_id = i.factory_id
              JOIN (
                SELECT DISTINCT store_id
                  FROM agg_daily
                 WHERE factory_id = $1
                UNION
                SELECT DISTINCT store_id
                  FROM fact_pos_transaction
                 WHERE factory_id = $1
                   AND NOT EXISTS (
                     SELECT 1 FROM agg_daily WHERE factory_id = $1
                   )
              ) scope ON scope.store_id = t.store_id
              JOIN dim_store s
                ON s.store_id = t.store_id
               AND s.factory_id = t.factory_id
              CROSS JOIN anchor
             WHERE i.factory_id = $1 AND t.factory_id = $1
               AND anchor.end_date IS NOT NULL
               AND t.date >= anchor.end_date - ($2::int)
               AND t.date <= anchor.end_date
             GROUP BY s.store_id, s.name, p.name, p.normalized_name
            """,
            factory_id, days,
        )

    if not store_dish_rows:
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"门店毛利分析 (近{days}天)",
            answer_text=f"近 {days} 天无 POS 销售数据, 需先上传 POS 账单.",
            charts=[], kpis=[],
            meta={"window_days": days, "no_pos_data": True},
        )

    window_start = min((r["window_start"] for r in store_dish_rows if r["window_start"]), default=None)
    window_end = max((r["window_end"] for r in store_dish_rows if r["window_end"]), default=None)
    window_label = _actual_window_text(window_start, window_end, days)

    # Lookup cretas product_types + dim_product_alias (P0-2) + agg_product_cost for food cost.
    dish_names = list({r["normalized_name"] for r in store_dish_rows})
    name_to_pk: Dict[str, str] = {}
    try:
        import asyncpg as _asyncpg
        from config import get_settings as _get_settings
        cretas = await _asyncpg.connect(_get_settings().food_kb_db_url)
        try:
            rows = await cretas.fetch(
                "SELECT id, name FROM product_types WHERE factory_id = $1 AND name = ANY($2::text[])",
                factory_id, dish_names,
            )
            for r in rows:
                name_to_pk[r["name"]] = r["id"]
            unmapped = [n for n in dish_names if n not in name_to_pk]
            if unmapped:
                try:
                    alias_rows = await cretas.fetch(
                        """SELECT pos_name, product_type_id FROM dim_product_alias
                            WHERE factory_id = $1 AND pos_name = ANY($2::text[])""",
                        factory_id, unmapped,
                    )
                    for r in alias_rows:
                        name_to_pk[r["pos_name"]] = r["product_type_id"]
                except Exception as e:
                    if "does not exist" not in str(e):
                        logger.warning(f"[store_margin] alias lookup failed: {e}")
        finally:
            await cretas.close()
    except Exception as e:
        logger.warning(f"[store_margin] cretas lookup failed: {e}")

    cost_by_pk: Dict[str, float] = {}
    if name_to_pk:
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, true)", factory_id)
            cr = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS c
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1 AND product_source_pk = ANY($2::text[])
                """,
                factory_id, list(name_to_pk.values()),
            )
            cost_by_pk = {r["product_source_pk"]: r["c"] for r in cr}

    # Aggregate per store. Track revenue twice: all-dish (for display) and
    # with-cost-only (for margin rate denominator — same fix as gross-margin).
    per_store: Dict[int, Dict[str, Any]] = {}
    for r in store_dish_rows:
        pk = name_to_pk.get(r["normalized_name"])
        dish_cost = cost_by_pk.get(pk, 0) if pk else 0
        line_cost = dish_cost * r["qty"]
        s = per_store.setdefault(r["store_id"], {
            "store_id": r["store_id"], "name": r["store_name"],
            "revenue": 0.0, "revenue_with_cost": 0.0, "cost": 0.0,
            "bills": 0, "dishes": 0, "dishes_with_cost": 0,
        })
        s["revenue"] += r["revenue"]
        s["bills"] += r["bills"]
        s["dishes"] += 1
        if dish_cost > 0:
            s["revenue_with_cost"] += r["revenue"]
            s["cost"] += line_cost
            s["dishes_with_cost"] += 1

    store_list = []
    for s in per_store.values():
        s["gross_profit"] = s["revenue_with_cost"] - s["cost"]
        # marginRate only from with-cost dishes — avoid 89-97% inflation.
        s["margin_rate"] = (
            s["gross_profit"] / s["revenue_with_cost"]
            if s["revenue_with_cost"] > 0 else 0
        )
        store_list.append(s)
    store_list.sort(key=lambda x: x["gross_profit"], reverse=True)
    top_slice = store_list[:top_n]

    total_rev = sum(s["revenue"] for s in store_list)
    total_rev_with_cost = sum(s["revenue_with_cost"] for s in store_list)
    total_profit = sum(s["gross_profit"] for s in store_list)
    avg_rate = total_profit / total_rev_with_cost if total_rev_with_cost > 0 else 0

    top_text = "\n".join([
        f"  {i+1}. {s['name']}: 营收 ¥{s['revenue']:,.2f} / 毛利 ¥{s['gross_profit']:,.2f} ({s['margin_rate'] * 100:.1f}%), {s['bills']} 单"  # noqa: E501
        for i, s in enumerate(top_slice)
    ])
    answer = (
        f"近 {days} 天门店毛利对比 ({len(store_list)} 家店):\n"
        f"- 总营收 ¥{total_rev:,.2f}, 总毛利 ¥{total_profit:,.2f}, 平均毛利率 {avg_rate * 100:.1f}%\n\n"
        f"Top {len(top_slice)} 毛利门店:\n{top_text}"
    )

    charts = [{
        "chartType": "bar",
        "title": f"Top {len(top_slice)} 毛利门店",
        "xAxis": {"data": [s["name"] for s in top_slice]},
        "series": [
            {"name": "营收", "type": "bar", "data": [s["revenue"] for s in top_slice]},
            {"name": "毛利", "type": "bar", "data": [s["gross_profit"] for s in top_slice]},
        ],
    }]

    weak_store_text = ""
    if len(store_list) > 1:
        weakest = sorted(store_list, key=lambda s: s["margin_rate"])[0]
        weak_store_text = (
            f"\n需要复盘的门店: {weakest['name']} 毛利率 {weakest['margin_rate'] * 100:.1f}%, "
            f"先查低毛利菜品占比、套餐折扣和损耗领料是否偏高。"
        )
    answer = (
        f"门店毛利对比（{window_label}，{len(store_list)} 家店）\n"
        f"- 总营收 ¥{total_rev:,.2f}, 总毛利 ¥{total_profit:,.2f}, 平均毛利率 {avg_rate * 100:.1f}%\n\n"
        f"Top {len(top_slice)} 毛利门店:\n{top_text}{weak_store_text}\n\n"
        f"建议动作:\n"
        f"  1. 把第一名门店的高毛利菜品组合、套餐结构和时段客流拆出来，作为其他门店对标模板。\n"
        f"  2. 对毛利率低但营收不低的门店，优先查折扣、赠品和后厨出品标准，避免销售越多利润越薄。\n"
        f"  3. 对菜品成本覆盖率低的门店，先补齐配方成本再做绩效排序。"
    )
    charts[0]["title"] = f"Top {len(top_slice)} 毛利门店 ({window_label})"
    charts[0]["series"][0]["name"] = "营收"
    charts[0]["series"][1]["name"] = "毛利"

    return OpsAnswer(
        code="RESTAURANT_OPS_STORE_MARGIN",
        title=f"门店毛利对比 (近{days}天)",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "门店数", "value": len(store_list), "rawValue": len(store_list)},
            {"title": "总营收", "value": f"¥{total_rev:,.0f}", "rawValue": total_rev},
            {"title": "总毛利", "value": f"¥{total_profit:,.0f}", "rawValue": total_profit},
            {"title": "最赚门店", "value": top_slice[0]["name"] if top_slice else "—", "rawValue": 0},
        ],
        meta={
            "window_days": days, "store_count": len(store_list),
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "totalRevenue": total_rev, "totalRevenueWithCost": total_rev_with_cost,
            "totalProfit": total_profit, "avgRate": avg_rate,
            "stores": [
                {"storeId": s["store_id"], "name": s["name"],
                 "revenue": s["revenue"],
                 "revenueWithCost": s["revenue_with_cost"],
                 "grossProfit": s["gross_profit"],
                 "marginRate": s["margin_rate"], "bills": s["bills"],
                 "dishesWithCost": s["dishes_with_cost"], "totalDishes": s["dishes"]}
                for s in store_list
            ],
        },
    )


async def resolve_sales_summary(
    smartbi_pool, factory_id: str, *, role: Optional[str] = None, query: Optional[str] = None,
) -> OpsAnswer:
    from smartbi.gold.queries import finance_summary, store_comparison
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    can_see_money = bool(role) and role in PRICE_VIEW_ROLES
    date_range, window_label = _resolve_sales_date_range(query)
    summary = await finance_summary(smartbi_pool, factory_id, date_range, top_n_stores=5)
    stores_data = await store_comparison(smartbi_pool, factory_id, date_range)
    stores = stores_data.get("stores") or []
    weak_stores = stores_data.get("weakStores") or []

    total_revenue = float(summary.get("total_revenue") or 0.0)
    bill_count = int(summary.get("bill_count") or 0)
    avg_bill = summary.get("avg_bill_value")
    day_count = int(summary.get("day_count") or 0)
    store_count = int(summary.get("store_count") or 0)
    top_stores = summary.get("top_stores") or []

    def _money(v: Optional[float]) -> str:
        if not can_see_money:
            return "***"
        if v is None:
            return "暂无"
        return f"¥{float(v):,.2f}"

    if bill_count <= 0:
        return OpsAnswer(
            code="RESTAURANT_OPS_SALES_SUMMARY",
            title="经营销售概览",
            answer_text=(
                f"{window_label}还没有可用的 POS 营收和订单数据。建议先确认营业流水已经进入 Gold 层，"
                "再看总营收、平均每单和门店差异。"
            ),
            charts=[],
            kpis=[],
            meta={"no_data": True},
        )

    top_line = ""
    if top_stores:
        top = top_stores[0]
        top_line = f"表现最强的是{top['store_name']}，订单数 {top['bill_count']} 单。"
        if can_see_money:
            top_line = f"表现最强的是{top['store_name']}，营收 {_money(top['revenue'])}，订单数 {top['bill_count']} 单。"

    weak_line = ""
    if weak_stores:
        weak_line = f"低于中位水平的门店有 {len(weak_stores)} 家，先看{weak_stores[0]}。"

    avg_text = _money(float(avg_bill)) if avg_bill is not None else "暂无"
    answer = (
        f"{window_label}经营能看：覆盖 {day_count} 天、{store_count} 家门店，共 {bill_count:,} 单。"
        f"总营收 {_money(total_revenue)}，平均每单 {avg_text}。"
        f"{top_line}{weak_line}"
        "建议：先把低于中位的门店拉出来，看是客流少、平均每单低，还是折扣过重；"
        "再对照高门店的菜品结构和时段，把能复制的动作做小范围试点。"
    )

    chart_stores = top_stores[:5]
    charts = [{
        "chartType": "bar",
        "title": "门店营收 Top 5" if can_see_money else "门店订单 Top 5",
        "xAxis": {"data": [s["store_name"] for s in chart_stores]},
        "series": [{
            "name": "营收" if can_see_money else "订单数",
            "type": "bar",
            "data": [
                (float(s["revenue"]) if can_see_money else int(s["bill_count"]))
                for s in chart_stores
            ],
        }],
    }] if chart_stores else []

    kpis = [
        {"title": "订单数", "value": f"{bill_count:,}", "rawValue": bill_count},
        {"title": "总营收", "value": _money(total_revenue) if can_see_money else None,
         "rawValue": total_revenue if can_see_money else None},
        {"title": "平均每单", "value": avg_text if can_see_money else None,
         "rawValue": float(avg_bill) if can_see_money and avg_bill is not None else None},
        {"title": "门店数", "value": store_count, "rawValue": store_count},
    ]

    return OpsAnswer(
        code="RESTAURANT_OPS_SALES_SUMMARY",
        title="经营销售概览",
        answer_text=answer,
        charts=charts,
        kpis=kpis,
        meta={
            "day_count": day_count,
            "store_count": store_count,
            "window_label": window_label,
            "window_start": date_range[0].isoformat() if date_range[0] else None,
            "window_end": date_range[1].isoformat() if date_range[1] else None,
            "weak_stores": weak_stores,
            "price_view": can_see_money,
            "store_comparison_count": len(stores),
        },
    )


async def resolve_trend_analysis(
    smartbi_pool, factory_id: str, *, role: Optional[str] = None,
) -> OpsAnswer:
    """Revenue trend / YoY / MoM analysis served from Gold trend_bundle.

    Answers "同比环比/营收趋势/增长下降趋势/月度变化" from the pre-aggregated
    agg_daily Gold table (FULL history — all 2025+2026 data), instead of the
    per-upload xlsx template router which fails for qhj ("缺少按时间拆分的同比
    环比数据") because the selected upload is only a partial POS part or a
    review xlsx.

    Uses trend_bundle(pool, factory_id, (None, None)) → all history. Builds a
    readable Chinese answer (monthly revenue trend with peak/trough months,
    MoM growth/decline of the latest month, weekday vs weekend split) plus a
    line chart of monthly revenue.

    RBAC: trend_bundle revenue is monetary. At the gold endpoint layer
    (gold_reads.get_trend_bundle) it's nulled for non price-view roles via
    strip_price_for_role. Here we call the query directly and bake revenue
    numbers into prose + chart, which strip_price_for_role can NOT reach. So
    only roles in PRICE_VIEW_ROLES see the actual ¥ amounts; everyone else
    (including a missing/None role) gets trend direction + structure only.
    This mirrors strip_price_for_role's own contract ("None / empty / unknown
    roles are treated as ineligible") so we never leak revenue in prose/chart.
    """
    from smartbi.gold.queries import trend_bundle
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    can_see_money = bool(role) and role in PRICE_VIEW_ROLES

    bundle = await trend_bundle(smartbi_pool, factory_id, (None, None))
    monthly: List[Dict[str, Any]] = bundle.get("monthlyTrend") or []
    ww: Dict[str, Any] = bundle.get("weekdayWeekend") or {}

    def _money(v: float) -> str:
        return f"¥{v:,.2f}" if can_see_money else "***"

    if not monthly:
        return OpsAnswer(
            code="RESTAURANT_OPS_TREND_ANALYSIS",
            title="营收趋势分析",
            answer_text=(
                "暂无按时间拆分的营收数据 (Gold 层 agg_daily 为空)。\n"
                "提示: 上传带日期的 POS 营业数据并完成 materialize 后, "
                "本分析即可展示月度趋势 + 同比环比。"
            ),
            charts=[], kpis=[],
            meta={"all_history": True, "no_data": True},
        )

    # Peak / trough months by revenue.
    peak = max(monthly, key=lambda m: m["revenue"])
    trough = min(monthly, key=lambda m: m["revenue"])
    total_rev = sum(m["revenue"] for m in monthly)
    n_months = len(monthly)

    # MoM (环比): latest month vs previous month.
    mom_line = ""
    if n_months >= 2:
        last, prev = monthly[-1], monthly[-2]
        if prev["revenue"] > 0:
            mom_rate = (last["revenue"] - prev["revenue"]) / prev["revenue"] * 100
            direction = "增长" if mom_rate >= 0 else "下降"
            mom_line = (
                f"- 环比(最新月 {last['month']} vs {prev['month']}): "
                f"{direction} {abs(mom_rate):.1f}%\n"
            )

    # YoY (同比): latest month vs same month last year, if present.
    yoy_line = ""
    if monthly:
        last = monthly[-1]
        try:
            ly, lm = last["month"].split("-")
            same_last_year = f"{int(ly) - 1}-{lm}"
        except (ValueError, AttributeError):
            same_last_year = None
        if same_last_year:
            prior = next((m for m in monthly if m["month"] == same_last_year), None)
            if prior and prior["revenue"] > 0:
                yoy_rate = (last["revenue"] - prior["revenue"]) / prior["revenue"] * 100
                direction = "增长" if yoy_rate >= 0 else "下降"
                yoy_line = (
                    f"- 同比(最新月 {last['month']} vs {same_last_year}): "
                    f"{direction} {abs(yoy_rate):.1f}%\n"
                )

    # Overall direction: first vs last month.
    overall_line = ""
    if n_months >= 2 and monthly[0]["revenue"] > 0:
        overall_rate = (
            (monthly[-1]["revenue"] - monthly[0]["revenue"])
            / monthly[0]["revenue"] * 100
        )
        direction = "上升" if overall_rate >= 0 else "下降"
        overall_line = (
            f"- 整体走势({monthly[0]['month']} → {monthly[-1]['month']}): "
            f"{direction} {abs(overall_rate):.1f}%\n"
        )

    month_list_text = "\n".join([
        f"  {m['month']}: {_money(m['revenue'])}" for m in monthly
    ])

    weekday_avg = ww.get("weekdayAvg") or 0.0
    weekend_avg = ww.get("weekendAvg") or 0.0
    ww_line = ""
    if (ww.get("weekdayDays") or 0) > 0 or (ww.get("weekendDays") or 0) > 0:
        ww_line = (
            f"- 工作日日均 {_money(weekday_avg)} / 周末日均 {_money(weekend_avg)}"
            + (
                f" (周末高 {((weekend_avg / weekday_avg - 1) * 100):.0f}%)"
                if can_see_money and weekday_avg > 0 and weekend_avg > weekday_avg
                else ""
            )
            + "\n"
        )

    answer = (
        f"营收趋势分析 (全部历史, 共 {n_months} 个月):\n"
        f"- 累计营收 {_money(total_rev)}\n"
        f"- 营收最高月: {peak['month']} ({_money(peak['revenue'])})\n"
        f"- 营收最低月: {trough['month']} ({_money(trough['revenue'])})\n"
        f"{overall_line}{yoy_line}{mom_line}{ww_line}"
        f"\n建议动作:\n"
        f"  1. 先把最高月和最低月按门店、渠道、折扣三层拆开，找出增长来自客流、客单价还是活动补贴。\n"
        f"  2. 如果最新月下滑，优先确认是否为未完结月份；若已完结，再复盘低于中位数门店和高折扣渠道。\n"
        f"  3. 周末与工作日差异不大时，重点做时段活动而不是整天打折，避免折扣侵蚀毛利。\n"
        f"\n各月营收:\n{month_list_text}"
    )

    charts = [{
        "chartType": "line",
        "title": "月度营收趋势 (全部历史)",
        "xAxis": {"data": [m["month"] for m in monthly]},
        "series": [{
            "name": "营收",
            "type": "line",
            "data": [(m["revenue"] if can_see_money else None) for m in monthly],
        }],
    }]

    kpis = [
        {"title": "数据月数", "value": n_months, "rawValue": n_months},
        {
            "title": "累计营收",
            "value": _money(total_rev) if can_see_money else None,
            "rawValue": total_rev if can_see_money else None,
        },
        {"title": "最高月", "value": peak["month"], "rawValue": 0},
        {"title": "最低月", "value": trough["month"], "rawValue": 0},
    ]

    return OpsAnswer(
        code="RESTAURANT_OPS_TREND_ANALYSIS",
        title="营收趋势分析 (全部历史)",
        answer_text=answer,
        charts=charts,
        kpis=kpis,
        meta={
            "all_history": True,
            "month_count": n_months,
            "peak_month": peak["month"],
            "trough_month": trough["month"],
            "price_view": can_see_money,
        },
    )


_RESOLVERS = {
    "RESTAURANT_OPS_WASTAGE_TOP": resolve_wastage_top,
    "RESTAURANT_OPS_STOCK_SHORTAGE": resolve_stock_shortage,
    "RESTAURANT_OPS_RECIPE_COST": resolve_recipe_cost,
    "RESTAURANT_OPS_REQUISITION_TREND": resolve_requisition_trend,
    "RESTAURANT_OPS_GROSS_MARGIN": resolve_gross_margin,
    "RESTAURANT_OPS_STORE_MARGIN": resolve_store_margin,
    "RESTAURANT_OPS_SALES_SUMMARY": resolve_sales_summary,
    "RESTAURANT_OPS_TREND_ANALYSIS": resolve_trend_analysis,
}


async def resolve_by_code(
    code: str, smartbi_pool, factory_id: str, **kwargs
) -> Optional[OpsAnswer]:
    """Dispatch to the right resolver. Returns None if code unknown.

    Callers may pass extra kwargs (e.g. ``role`` for RBAC-aware resolvers like
    trend_analysis). Resolvers that don't declare those params would raise
    TypeError, so we filter kwargs down to each resolver's accepted parameter
    names (unless it has a **kwargs catch-all). This keeps the dispatch
    backward-compatible: legacy resolvers silently ignore ``role``.
    """
    resolver = _RESOLVERS.get(code)
    if resolver is None:
        return None
    sig = inspect.signature(resolver)
    accepts_var_kw = any(
        p.kind is inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()
    )
    if accepts_var_kw:
        filtered = kwargs
    else:
        filtered = {k: v for k, v in kwargs.items() if k in sig.parameters}
    return await resolver(smartbi_pool, factory_id, **filtered)
