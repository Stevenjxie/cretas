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
import math
import os
import re
import time
from contextlib import asynccontextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant.metric_registry import (
    COST_CARD_PRESENT_SQL as _COST_CARD_PRESENT_SQL,
    MAX_SANE_DISH_UNIT_COST as _REG_MAX_SANE_DISH_UNIT_COST,
    dish_cost_is_implausible as _dish_cost_is_implausible,
)
# ⛔ 这里**不再** import `COST_UNIT_ERROR_RATIO` —— 本模块已经没有任何一处
#    自己拿它做比较了。改之前 import 它是为了「两处共用同一个阈值」, 而那正是
#    上一版的错觉: 共用了阈值, 粒度还是两套。现在整条判据都在登记表里。
#    `tests/test_margin_parity.py::test_the_cost_outlier_rule_has_exactly_one_home`
#    会在有人把它 import 回来时红。
from smartbi.gold.restaurant.provenance import (
    ESTIMATED as PROV_ESTIMATED,
    MEASURED as PROV_MEASURED,
    qualifier as provenance_qualifier,
)

logger = logging.getLogger(__name__)


# Keyword patterns per ops template. Each entry: (code, [[kw_group_1], [kw_group_2], ...]).
# Query must contain at least one keyword from each group. First match wins.
#: 「这句话在问缺成本卡」的**唯一判据** —— 关键词路由表和 resolver 选分支
#: 共用它。⛔ 两处各写各的 = 路由到了 RECIPE_COST 却走进「成本排行」分支,
#: 那时按钮点下去给的是**有卡的菜**, 恰好和他要的相反, 而且看起来完全正常。
#: 形态: 每一组至少命中一个词(与 `_OPS_PATTERNS` 同一套语义)。
_MISSING_CARD_TOKENS: Tuple[Tuple[str, ...], ...] = (
    ("成本卡",),
    ("没有", "缺", "少", "漏", "哪些", "哪道", "列出", "清单", "没录", "未录"),
)


def _asks_missing_cost_card(text: str) -> bool:
    """问句是不是在问「哪些菜没有成本卡」。判据见 `_MISSING_CARD_TOKENS`。"""
    query = text or ""
    return all(any(token in query for token in group)
               for group in _MISSING_CARD_TOKENS)


_OPS_PATTERNS: List[Tuple[str, List[List[str]]]] = [
    # Wastage: "损耗/浪费" + "最多/哪/top/类型/占比"
    (
        "RESTAURANT_OPS_WASTAGE_TOP",
        [["损耗", "浪费", "报损", "腐坏", "过期"],
         ["最多", "排名", "前", "top", "TOP", "哪个", "哪种", "类型", "占比", "分布", "原因", "多少"]],
    ),
    # Stocktaking shortage: "盘点/盘亏/亏损" + "哪/top/最多"
    (
        "RESTAURANT_OPS_STOCK_SHORTAGE",
        [["盘点", "盘亏", "盘损", "库存差异", "账实差"],
         # 🔴 "多少" 是 2026-07-31 补的, 修的是一处【与损耗规则的不对称】: 上面
         # WASTAGE_TOP 的 group-2 一直有 "多少"(「损耗了多少」确定性命中), 这里没有,
         # 于是「盘点亏了多少」两条规则都不命中 → 落到 LLM → 抖。实测抖出来的样子是:
         # planner 本来选对了 STOCK_SHORTAGE, 但 LLM 把 metrics 填成 ('wastage',),
         # contract-repair 就忠实地按那个指标把 resolver 改写成 WASTAGE_TOP,
         # 于是「盘点亏了多少」被答成损耗榜。同一句话在别的轮次是对的(每日 timer 三轮
         # 全 OK), 所以它不是稳定缺陷而是【确定性覆盖的缺口】, 补上就不再看 LLM 脸色。
         # 不会过宽: group-1 已要求出现「盘点/盘亏/盘损/库存差异/账实差」这类专有词。
         ["哪个", "哪些", "最多", "前", "top", "TOP", "排名", "频率", "经常", "多少"]],
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
    # Metric-preserving margin trend. This MUST precede the generic trend
    # matcher below; otherwise "整体毛利率趋势" silently becomes revenue trend.
    (
        "RESTAURANT_OPS_GROSS_MARGIN",
        [["毛利", "毛利率"],
         ["趋势", "走势", "曲线", "按月", "月份", "参照线", "计划线", "预警线"]],
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
    # 🔴 T2 按钮点下去落在这条上(owner 2026-08-14 裁定 2)。
    #    ⚠️ 必须排在下面那条 RECIPE_COST **之前** —— 「哪些菜没有成本卡」里
    #       没有「食材成本/配方成本」, 落不到下面那条; 但把它放后面的话,
    #       将来任何一条更宽的模式先匹配上就会把它抢走。
    #    ⛔ 改这里要同时改 `follow_up_actions.T2_FILL_QUESTION` ——
    #       两处不一致 = 按钮变哑弹(点下去拒答), 而它看起来完全正常。
    ("RESTAURANT_OPS_RECIPE_COST", [list(g) for g in _MISSING_CARD_TOKENS]),
    # Recipe cost (食材成本 only — 毛利 moved to gross_margin)
    (
        "RESTAURANT_OPS_RECIPE_COST",
        [["食材成本", "配方成本", "菜品成本", "食材费用"],
         ["最高", "最低", "前", "哪道", "哪个", "top", "TOP", "排名", "多少"]],
    ),
    # Requisition trend: "领料/领/领用" + "趋势/最多/食材"
    (
        "RESTAURANT_OPS_REQUISITION_TREND",
        [["领料", "领用", "用料", "食材用量"],
         ["趋势", "最多", "前", "top", "TOP", "哪个食材", "哪些食材", "排名"]],
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
        [["赚钱", "挣钱", "盈利", "利润", "毛利", "净赚", "亏钱", "亏不亏", "赚不赚"],
         ["最近", "近", "过去", "本周", "这周", "今天", "今日", "本月", "这个月", "情况", "怎么样", "多少", "了吗"]],
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
    # Inventory warning (2026-07-08): 食材库存水位预警——低于补货点/安全库存
    # 的食材，提示补货。placed at the END of the list (safest position — any
    # earlier pattern that legitimately matches a query keeps winning first,
    # e.g. "食材成本最高的菜" still routes to RECIPE_COST because that pattern
    # is checked before this one and matches on its own more specific phrase).
    # Distinct from STOCK_SHORTAGE (历史盘点账实差), which requires "库存差异"
    # not just "库存".
    (
        "RESTAURANT_OPS_INVENTORY_WARNING",
        [["库存", "食材", "原料", "补货", "进货"],
         ["预警", "够不够", "够吗", "快没了", "要补", "该进", "不足", "缺货", "备货"]],
    ),
    # Forecast staffing: current reservations + independent 7/30/365-day POS
    # trends produce the FactBook; skills/hours/targets produce headcount.  The
    # historical actual/target direction is evidence only.  Keep this last so
    # more specific operational intents win first.
    (
        "RESTAURANT_OPS_STAFFING_ADVICE",
        [["排班", "人手", "人力", "人效", "员工", "服务员", "兼职", "几点", "时段", "午市", "晚市", "下午茶", "夜宵"],
         ["加人", "减人", "够不够", "不够", "够吗", "忙不忙", "需要", "多少", "安排", "调配", "人效", "几个人", "排班", "建议", "人太多", "人少"]],
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
        "损耗食材前 10 名",
    ],
    "RESTAURANT_OPS_STOCK_SHORTAGE": [
        "最近哪个食材盘亏最严重",
        "盘点差异最大的食材前 10 名",
        "盘亏金额排名",
        "哪些食材经常盘亏",
        "账实差距最大的是哪些食材",
        "本月盘点情况",
    ],
    "RESTAURANT_OPS_RECIPE_COST": [
        "食材成本最高的菜是哪些",
        "配方成本前 10 名",
        # 2026-08-01: 「毛利最低的菜品」从这里移到 GROSS_MARGIN。它是本表里**唯一**
        # 一条「关键词匹配器把它判给另一个 code」的样例 (实测 62 条样例, 只此 1 条
        # 分叉; 另外 5 条不一致都是 None = 关键词无意见, 属良性回落)。
        # 它是**过期残留**而非设计选择 —— 上面 RECIPE_COST 的模式注释自己写着
        # 「食材成本 only — 毛利 moved to gross_margin」, 组1 要求出现
        # 食材成本/配方成本/菜品成本/食材费用, 「毛利最低的菜品」一个都不含,
        # 所以 RECIPE_COST 本来就永远匹配不到它, 留在这里只会污染 RAG 与推荐词。
        "哪道菜食材费用最贵",
        "菜品成本排行",
        "食材占销售额比重最高的菜",
    ],
    "RESTAURANT_OPS_REQUISITION_TREND": [
        "最近30天领料趋势",
        "领用最多的食材是哪些",
        "本月食材用量前 10 名",
        "哪些食材领料频率最高",
        "领料数量趋势",
        "食材消耗排名",
    ],
    "RESTAURANT_OPS_GROSS_MARGIN": [
        "哪道菜毛利最高",
        "菜品毛利率排行",
        "最赚钱的菜前 10 名",
        # Apr 25 2026: tightened from "哪些菜净赚最多" (bare 菜 was removed
        # from group-2 to stop 菜单/菜谱 false-positives) — explicit dish
        # marker now required.
        "哪些菜品净赚最多",
        "菜品毛利对比",
        "利润最高的菜品",
        "售价减去食材成本最多的菜",
        # 2026-08-01: 自 RECIPE_COST 移入 (见那边注释)。三个机制一致指向这里 ——
        # 关键词匹配器实测判 GROSS_MARGIN、LLM planner 的 metric=gross_margin
        # 分支也落 GROSS_MARGIN、scripts/restaurant_department_audit.py 期望
        # GROSS_MARGIN。同时补一条「最低」方向的样例, 让 RAG 见过降序以外的问法。
        "毛利最低的菜品",
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
    "RESTAURANT_OPS_INVENTORY_WARNING": [
        "哪些食材快没了",
        "库存够不够",
        "需要补货的食材",
        "该进货了吗",
        "食材库存预警",
        "哪些原料库存不足",
    ],
    "RESTAURANT_OPS_STAFFING_ADVICE": [
        "明天怎么排班",
        "下周需要多少兼职",
        "下个月各店人效安排",
        "午市要不要加人",
        "哪个时段人手不够",
        "各门店预测客流和排班建议",
    ],
}


_SALES_VALUE_TOKENS = (
    "营收", "营业额", "营业收入", "销售额", "销售收入", "流水",
    "销售", "订单", "单量", "客单价", "平均每单",
)
_COMPARISON_DIRECTION_TOKENS = (
    "对比", "比较", "相比", "比", "较", "高于", "低于", "上升", "下降",
    "增加", "减少", "高还是低", "多还是少", "旺不旺", "环比",
    "哪个高", "哪个低", "更高", "更低", "高了", "低了",
    "差额", "升降", "增减", "变化结论",
)


def _has_explicit_sales_period_pair(query: str) -> bool:
    """Whether the question names both sides of a sales comparison.

    This intentionally detects period *pairs* instead of enumerating complete
    sentences.  It therefore survives particles, formal wording and common
    owner synonyms without turning a generic "增长趋势" question into a
    point-in-time comparison.
    """
    pairs = (
        (("昨天", "昨日"), ("前天", "前日", "前一天", "前一日")),
        (("今天", "今日"), ("昨天", "昨日")),
        (
            ("本周", "这周", "本星期", "这星期", "这个星期"),
            ("上周", "上星期", "上个星期"),
        ),
        (("本月", "这个月", "当月"), ("上个月", "上月")),
        (("上个月", "上月"), ("上上个月", "上上月")),
        (("上周", "上星期", "上个星期"), ("上上周", "上上星期", "上上个星期")),
        (("今年",), ("去年",)),
    )
    return any(
        any(token in query for token in primary)
        and any(token in query for token in baseline)
        for primary, baseline in pairs
    )


def _is_explicit_sales_period_comparison(query: str) -> bool:
    return bool(
        any(token in query for token in _SALES_VALUE_TOKENS)
        and _has_explicit_sales_period_pair(query)
        and any(token in query for token in _COMPARISON_DIRECTION_TOKENS)
    )


_NEGATIVE_MARGIN_EXISTENCE_RE = re.compile(
    r"(?:有没有|有无|是否有|存不存在|哪些|哪道|哪个|哪家)[^。？?]{0,10}?"
    r"(?:毛利(?:率)?[^。？?]{0,4}?(?:负|亏)|负毛利|亏钱|亏损|亏本|赔钱)"
    r"|负毛利|毛利(?:率)?(?:是|为)负"
)
# POS 流水里的附属行 (包装/餐具/纸品/配送费) — 泛菜品销量排行里的噪音。
# 点名查询不走这个过滤器，因此「米饭本月销量」仍可按用户要求回答。
_NON_DISH_POS_ITEM_RE = re.compile(
    r"打包盒|打包袋|餐具|配送费|外送费|打包费|包装费|纸巾|餐巾纸|湿巾|一次性"
    r"|购物袋|塑料袋|筷子|勺子|牙签|吸管|手套|纸杯|杯盖|杯套"
)
_NON_PRIMARY_DISH_CATEGORY_RE = re.compile(
    r"非菜品|非餐品|包装耗材|餐具耗材|一次性用品|纸品|配送费|服务费|附加费|用品|耗材"
)
# 只排除作为加购基础项的米饭名称。必须整名匹配，避免误伤蛋炒饭、盖饭等主菜。
_RICE_STAPLE_ITEM_RE = re.compile(
    r"^(?:(?:五常香|东北|泰国香|香)?(?:白)?米饭)"
    r"(?:(?:[\[(（【][^\])）】]{0,12}[\])）】])|(?:[一二两单双\d]+人?份)|"
    r"(?:[大小]碗)|(?:加量))?$"
)
_LOW_SALES_STATE_RE = re.compile(
    r"销量[^。；，,？?]{0,8}(?:偏低|很低|太低|这么低|那么低|低|偏少|很少|太少)"
    r"|卖得(?:很|太|比较)?少|卖得不好|不好卖|没人点|很少人点|点得少"
)
_HIGH_SALES_STATE_RE = re.compile(
    r"销量[^。；，,？?]{0,8}(?:偏高|很高|太高|这么高|那么高|高|偏多|很多|太多|这么多)"
    r"|卖得(?:很|太|比较)?多|卖得(?:很|太|比较)?好|好卖|很多人点|点得多"
)
_SALES_TREND_CHANGE_RE = re.compile(r"下降|下滑|减少|降低|跌了|变少")
_SALES_TREND_INCREASE_RE = re.compile(
    r"上涨|上升|增长|增加|提高|提升|走高|拉升|变多"
)


def _primary_dish_ranking_exclusion_reason(row: Any) -> Optional[str]:
    """Return why a POS row is not a primary dish, or None when rankable."""
    category_text = " ".join(
        str(row.get(key) or "").strip()
        for key in ("category", "sub_category")
    )
    if category_text and _NON_PRIMARY_DISH_CATEGORY_RE.search(category_text):
        return "category"

    item_name = re.sub(
        r"\s+",
        "",
        str(row.get("dish_name") or row.get("name") or ""),
    )
    if _NON_DISH_POS_ITEM_RE.search(item_name):
        return "accessory"
    if _RICE_STAPLE_ITEM_RE.fullmatch(item_name):
        return "staple"
    return None


def _asks_low_sales_state(query: str) -> bool:
    """True for a cross-sectional "is sales low?" premise, not a trend."""
    text = (query or "").strip()
    return bool(
        text
        and _LOW_SALES_STATE_RE.search(text)
        and not _SALES_TREND_CHANGE_RE.search(text)
    )


def _asks_high_sales_state(query: str) -> bool:
    """True for a cross-sectional "is sales high?" premise, not a trend."""
    text = (query or "").strip()
    return bool(
        text
        and _HIGH_SALES_STATE_RE.search(text)
        and not _SALES_TREND_INCREASE_RE.search(text)
    )


_DISH_RANK_WORST_RE = re.compile(
    r"卖得最差|卖的最差|卖得不好|最难卖|卖不动|销量最低|销量垫底|最不受欢迎|最滞销"
    r"|没人点|无人点|没有人点|点得最少|没什么人点|倒数(?:第)?[一二三四五六七八九十\d]*名"
)
_DISH_RANK_BEST_RE = re.compile(
    r"卖得最好|卖的最好|最好卖|买得最好|买的最好|最畅销|最热销|最火"
    r"|畅销(?:菜品|菜|单品|产品)?|销量最高|最受欢迎|卖得好|卖的好"
    r"|点得最多|点的最多|最常点|最爱点"
)
_RANK_LIMIT_RE = re.compile(
    r"(?:前|后|倒数)?\s*([一二三四五六七八九十\d]{1,3})\s*(?:名|道菜|个菜|款菜|个单品)"
    r"|(?:最高|最低|最好|最差)的?\s*([一二三四五六七八九十\d]{1,3})\s*(?:道菜|个菜|款菜|个单品)"
)
_CHINESE_RANK_NUMBERS = {
    "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
}
_EXPLICIT_RANKING_EXCLUSION_RE = re.compile(
    r"(?:排除|剔除|去掉|不含|不要算)([^。！？?；;]{1,80})"
)
_RANKING_CLARIFICATION_SUFFIX_RE = re.compile(
    r"\s+(?:"
    r"本月|上月|上个月|本周|上周|今天|昨天|前天|"
    r"最近\d{1,3}天|近\d{1,3}天|"
    r"全部门店|所有门店|各门店|全部店|所有店|全店|"
    r"(?:[\u4e00-\u9fffA-Za-z0-9()（）·-]{2,40}店"
    r"(?:\s*(?:和|与|、)\s*[\u4e00-\u9fffA-Za-z0-9()（）·-]{2,40}店)*)"
    r")$"
)


def dish_ranking_direction(query: "Optional[str]") -> "Optional[str]":
    """「哪道菜卖得最差/最好」→ ``worst``/``best``.

    Store words are scope, not a competing metric: both "全部门店销量最高的
    5 道菜" and "东城店销量最低的菜" are dish rankings.  A real store-ranking
    question still returns ``None`` because it has no dish/product noun.
    """
    if not query:
        return None
    q = query.strip()
    if not any(tok in q for tok in ("菜", "单品", "产品")):
        return None
    if _DISH_RANK_WORST_RE.search(q):
        return "worst"
    if _DISH_RANK_BEST_RE.search(q):
        return "best"
    return None


def ranking_limit(query: "Optional[str]", default: int = 5) -> int:
    """Extract a bounded Top-N/Bottom-N request; keep legacy default at five."""
    if not query:
        return default
    match = _RANK_LIMIT_RE.search(query)
    if not match:
        return default
    token = next((value for value in match.groups() if value), "")
    try:
        value = int(token)
    except (TypeError, ValueError):
        if token in _CHINESE_RANK_NUMBERS:
            value = _CHINESE_RANK_NUMBERS[token]
        elif token.endswith("十") and len(token) == 2:
            value = _CHINESE_RANK_NUMBERS.get(token[0], 1) * 10
        elif token.startswith("十") and len(token) == 2:
            value = 10 + _CHINESE_RANK_NUMBERS.get(token[1], 0)
        elif len(token) == 3 and token[1] == "十":
            value = (
                _CHINESE_RANK_NUMBERS.get(token[0], 0) * 10
                + _CHINESE_RANK_NUMBERS.get(token[2], 0)
            )
        else:
            value = default
    return max(1, min(value, 20))


_ranking_limit_from_query = ranking_limit


def ranking_exclusions(query: "Optional[str]") -> list[str]:
    """Return explicit user exclusions without treating them as advice verbs."""
    if not query:
        return []
    match = _EXPLICIT_RANKING_EXCLUSION_RE.search(query)
    if not match:
        return []
    raw = re.sub(
        r"(?:即可|就行|的数据|进行排名|参与排名|再排名|等附属项|等基础项)$",
        "",
        match.group(1).strip("：:，, "),
    )
    # Clarification replies are reconstructed as
    # ``<original query> <time reply> <store reply>``.  Those suffixes are
    # scope slots, not part of the last exclusion.  Requiring a whitespace
    # boundary avoids stripping legitimate item names such as “便利店套餐”.
    while _RANKING_CLARIFICATION_SUFFIX_RE.search(raw):
        raw = _RANKING_CLARIFICATION_SUFFIX_RE.sub("", raw).rstrip()
    values = re.split(r"[、，,和与及]|以及", raw)
    result: list[str] = []
    for value in values:
        item = value.strip(" ：:，,")
        item = re.sub(r"^(?:和|与|以及)", "", item).strip()
        item = re.sub(r"(?:这些)?(?:项目|商品|菜品|单品)$", "", item).strip()
        if 1 <= len(item) <= 40 and item not in result:
            result.append(item)
    return result[:12]


_CAPABILITY_RE = re.compile(
    r"(?:你们?|系统|助手)(?:都|还)?(?:能|会|可以)(?:做|干|帮我做|帮忙做|回答|查|分析)(?:些)?什么"
    r"|有(?:哪些|什么)功能"
    r"|(?:怎么|如何)使用(?:你|系统|助手)"
    r"|使用帮助"
    r"|你(?:能|会|可以)帮我(?:做|干)?什么"
)

RESTAURANT_CAPABILITIES_TEXT = (
    "我可以帮您分析**门店经营数据**，直接问就行：\n\n"
    "- 营收/订单：「最近30天营业额」「今天营业额多少」「上周和上上周营收对比」\n"
    "- 门店表现：「哪家店业绩最好」「各门店营收排名」「某某店的毛利率」\n"
    "- 菜品分析：「米饭的销量」「某菜品的成本和毛利率」「哪道菜卖得最好/最差」「有没有毛利为负的菜」\n"
    "- 盈亏判断：「最近亏钱了吗」「整体毛利率是多少」\n"
    "- 经营方法：「毛利率低的行业参考做法」（菜单工程、损耗控制等）\n\n"
    "支持多轮追问：问完一道菜后可以接着问「成本如何」「那某某菜呢」。"
)


_STORE_DISH_SPLIT_RE = re.compile(
    r"(?:哪家店|哪个店|哪家门店|各门店|各店|每家店|每个店)的?(.{1,14}?)(?:卖得|销量|销售|营收|毛利)"
)
_STORE_DISH_METRIC_TOKENS = (
    "营收", "营业额", "销量", "销售", "毛利", "订单", "业绩", "生意", "利润", "客单",
)


def store_dish_split_dish(query: "Optional[str]") -> "Optional[str]":
    """「哪家店的米饭卖得最好」→ 米饭 (店×菜拆分, 暂不支持需诚实拒答);
    纯门店问 (「哪家店营收最高」) → None。"""
    if not query:
        return None
    m = _STORE_DISH_SPLIT_RE.search(query.strip())
    if not m:
        return None
    cand = m.group(1).strip("的， ,")
    if len(cand) < 2 or any(tok in cand for tok in _STORE_DISH_METRIC_TOKENS):
        return None
    return cand[:60]


def is_capability_question(query: "Optional[str]") -> bool:
    return bool(query) and bool(_CAPABILITY_RE.search(query.strip()))


_OOD_SMALLTALK_RE = re.compile(r"天气|下雨|气温|新闻|股票|彩票|星座")
_OOD_BUSINESS_TOKEN_RE = re.compile(r"生意|营收|营业额|客流|影响|备货|经营|销量|门店")

RESTAURANT_OOD_TEXT = (
    "**天气、新闻这类外部信息不在我的数据范围内，我不会编造答案。**\n\n"
    "我可以帮您分析门店经营数据，例如：「最近30天营业额」「哪家店业绩最好」"
    "「米饭的销量」「有没有店在亏损」。"
)


def is_out_of_domain_smalltalk(query: "Optional[str]") -> bool:
    """纯外部信息闲聊 (天气/新闻) — 带经营关联词的不算 (R20)。"""
    if not query:
        return False
    q = query.strip()
    return bool(_OOD_SMALLTALK_RE.search(q)) and not bool(_OOD_BUSINESS_TOKEN_RE.search(q))


async def resolve_capabilities(smartbi_pool, factory_id: str, **kwargs) -> "OpsAnswer":
    """零 DB 静态能力自述 (餐饮语境) — 修掉 SYSTEM_HELP 死胡同 (R14/G4)。"""
    return OpsAnswer(
        code="RESTAURANT_OPS_CAPABILITIES",
        title="我能帮您做什么",
        answer_text=RESTAURANT_CAPABILITIES_TEXT,
        charts=[], kpis=[],
        meta={"capabilities": True},
    )


async def resolve_out_of_domain(smartbi_pool, factory_id: str, **kwargs) -> "OpsAnswer":
    """Honest non-business boundary selected by the semantic compiler.

    ⚠️ 先分一次「域外」与「域内但没数据」。2026-08-07 prod 实测:
    「哪个供应商报价最贵」落在这里, 于是用户听到的是
    「天气、新闻这类外部信息不在我的数据范围内」—— 那是**误导**:
    供应商报价不是天气, 它是我们打算支持、只是客户还没录的东西。
    诚实的答案要点名缺的那张表(G1 的 B 类归宿)。

    🔴 `honest_gap_answer` 内部**真查表**: 客户开始录入之后它返回 None,
    这里照旧走域外拒答 —— 把「没数据」写死会变成另一种降级处理。
    """
    from smartbi.gold.restaurant.data_gaps import honest_gap_answer

    gap = await honest_gap_answer(smartbi_pool, factory_id, kwargs.get("query") or "")
    if gap is not None:
        return OpsAnswer(
            code="RESTAURANT_OPS_DATA_GAP",
            title=f"{gap['subject']}：暂无数据",
            answer_text=gap["answer_text"],
            charts=[],
            kpis=[],
            meta={"data_gap": True, "missing_table": gap["table"]},
        )

    return OpsAnswer(
        code="RESTAURANT_OPS_OUT_OF_DOMAIN",
        title="当前可用的数据范围",
        answer_text=RESTAURANT_OOD_TEXT,
        charts=[],
        kpis=[],
        meta={"out_of_domain": True},
    )


async def resolve_store_directory(
    smartbi_pool,
    factory_id: str,
    **kwargs,
) -> "OpsAnswer":
    """Return the tenant-scoped store count and names; no time range required."""
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)",
                factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT s.name
                  FROM dim_store s
                 WHERE s.factory_id = $1
                 ORDER BY s.name
                 LIMIT 50
                """,
                factory_id,
            )
    stores = [
        str(row["name"]).strip()
        for row in rows
        if row["name"] and str(row["name"]).strip()
    ]
    if not stores:
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_DIRECTORY",
            title="门店名单",
            answer_text="当前账号下还没有可用的门店资料。",
            charts=[],
            kpis=[{"label": "门店数量", "value": 0, "unit": "家"}],
            meta={"stores": [], "store_count": 0, "no_data": True},
        )
    answer = f"当前账号共有 **{len(stores)} 家门店**：\n" + "\n".join(
        f"{index}. {name}" for index, name in enumerate(stores, 1)
    )
    return OpsAnswer(
        code="RESTAURANT_OPS_STORE_DIRECTORY",
        title="门店名单",
        answer_text=answer,
        charts=[],
        kpis=[{"label": "门店数量", "value": len(stores), "unit": "家"}],
        meta={"stores": stores, "store_count": len(stores)},
    )


def match_restaurant_ops(query: str) -> Optional[str]:
    """Return the ops template code if query matches, else None.

    Pure keyword match for <1ms routing. RAG-based fallback can live in
    chat.py's existing fallback chain.
    """
    if not query:
        return None
    q = query.strip()
    # Explicit "行业参考做法" requests are the most specific intent of all —
    # they must win before any metric keyword (毛利/损耗/…) grabs the query.
    from smartbi.gold.restaurant.restaurant_playbook import PLAYBOOK_CODE, PLAYBOOK_TRIGGERS
    if any(trigger in q for trigger in PLAYBOOK_TRIGGERS):
        return PLAYBOOK_CODE
    # 能力自述 ("你们能做什么") — 此前落 SYSTEM_HELP 无执行器死胡同 (R14/G4)。
    if is_capability_question(q):
        return "RESTAURANT_OPS_CAPABILITIES"
    # 域外闲聊 ("今天天气怎么样") — 诚实拒答 + 能力指引, 不编造 (R20)。
    if is_out_of_domain_smalltalk(q):
        return "RESTAURANT_OPS_CAPABILITIES"
    # 菜品销量排名 ("哪道菜卖得最差") — POS 行直接排, 不再依赖上传报表 (R14)。
    if dish_ranking_direction(q):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    # 店×菜拆分问 ("哪家店的米饭卖得最好") — 店×菜粒度直答 (R18)。
    if store_dish_split_dish(q):
        return "RESTAURANT_OPS_STORE_MARGIN"
    # 全店聚合下的菜品毛利榜 ("全部门店…毛利最低的菜品") — 「全部门店」是范围不是
    # 门店榜。必须在下面的模式循环之前, 因为 STORE_MARGIN 的 group-1 含「门店」
    # 且排在 GROSS_MARGIN 之前, 进了循环就已经被抓走 (2026-08-01)。
    if _all_store_scope_dish_margin(q):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    # 单日营收问 ("昨天卖了多少钱") — Java DAILY_REVENUE 工具默认锚今天,
    # 把单日问偷换成 今天vs昨天 比较 (R15/G7)。
    if (
        any(tok in q for tok in ("今天", "今日", "昨天", "昨日", "前天"))
        and any(tok in q for tok in ("卖了多少", "多少钱", "营业额", "营收", "销售额", "流水", "生意"))
        and not any(tok in q for tok in ("趋势", "走势", "哪家店", "哪个店"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 具名窗生意问 ("这个月生意怎么样") — 此前无 T1 规则, 全靠 T3 分类,
    # LLM 抖动一次就落工厂仪表盘 (R22b 评测电池实测)。
    if (
        any(tok in q for tok in ("今天", "本周", "这周", "本月", "这个月",
                                 "上周", "上个月", "上月", "昨天"))
        and any(tok in q for tok in ("生意", "营业额", "营收", "销售额", "流水"))
        and any(tok in q for tok in ("怎么样", "如何", "好不好", "多少", "咋样"))
        and not any(tok in q for tok in ("哪家店", "哪个店", "门店", "趋势", "走势"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 绝对月份营收问 ("3月份的营收"/"去年12月生意怎么样") — 解析器已支持
    # 日历月 (R23), T1 确定性直连, 不再依赖 T3 或落趋势 resolver 吞窗 (R23c)。
    if (
        re.search(r"(?:20\d{2}年|去年|今年)?(?:1[0-2]|0?[1-9]|十一|十二|[一二三四五六七八九十])月份?", q)
        and "个月" not in q
        and any(tok in q for tok in ("营收", "营业额", "销售额", "流水", "生意", "卖了多少", "多少钱"))
        and not any(tok in q for tok in ("趋势", "走势", "对比", "相比", "环比", "同比", "哪家店", "哪个店"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 同比增长问 ("今年比去年增长多少") — 此前落 Java 指标查询 slot-filling,
    # 还把 UPPER_SNAKE 指标码直接问用户 (R15)。
    if (
        re.search(r"(?:今年|本年)[^。]{0,6}(?:比|较|相比|对比)[^。]{0,6}去年|(?:比|较)去年", q)
        and any(tok in q for tok in ("增长", "下降", "涨", "跌", "多少", "如何", "怎么样", "好"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 订单量/客流问 ("订单量最近如何") — 无营收词, T1 此前不接, 落 LLM 工厂
    # 报表框架回答 (R15/G4)。
    if (
        any(tok in q for tok in ("订单量", "订单数", "单量", "客流量", "客流"))
        and any(tok in q for tok in ("最近", "如何", "怎么样", "多少", "情况"))
        and not any(tok in q for tok in ("门店", "分店", "哪家店", "哪个店"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 时段生意问 ("晚上生意怎么样") — 此前落工厂仪表盘不适用提示;
    # 按时段人效诊断是现有最贴近的真实能力 (R20)。
    if (
        any(tok in q for tok in ("早上", "上午", "中午", "午市", "下午",
                                 "晚上", "晚市", "夜宵", "下午茶"))
        and any(tok in q for tok in ("生意", "营收", "客流", "人效", "情况"))
        and any(tok in q for tok in ("怎么样", "如何", "好不好", "多少"))
        and not any(tok in q for tok in ("哪家店", "哪个店", "门店"))
    ):
        return "RESTAURANT_OPS_STAFFING_ADVICE"
    # 渠道占比问 ("外卖占了几成") — Java 工具面吸收第一枪 (R31),
    # 此前两次误路由事故 (描述窃取/LLM 抖动)。
    if (
        any(tok in q for tok in ("外卖", "堂食"))
        and any(tok in q for tok in ("占比", "几成", "比例", "对比", "占了", "占多少", "多少"))
        and not any(tok in q for tok in ("报表", "导出", "下载", "文件"))
    ):
        return "RESTAURANT_OPS_CHANNEL_MIX"
    # 亏损门店存在性问 ("有没有店在亏损") → 门店毛利存在性直答 (R15/G5)。
    if (
        re.search(r"(?:有没有|有无|是否有|哪些|哪家)[^。]{0,4}(?:门店|店)", q)
        and any(tok in q for tok in ("亏损", "亏钱", "赔钱", "不赚钱", "毛利为负", "负毛利"))
    ):
        return "RESTAURANT_OPS_STORE_MARGIN"
    # Named-dish sales questions ("招牌藤椒味卖得怎么样") previously fell to
    # the LLM fallback which answered from a factory-report frame. The POS
    # dish data lives in the gross-margin resolver; dish scoping above
    # narrows the answer to the named dish.
    if (
        re.search(r"卖得(?:怎么样|如何|好不好)", q)
        and not any(tok in q for tok in ("门店", "分店", "店铺", "哪家店", "哪个店"))
    ):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    # Named-dish metric asks ("米饭的销量是多少" / "…的成本如何") — the POS
    # dish data + dish scoping live in the gross-margin resolver. Generic
    # phrasings never produce a candidate, so this cannot steal 排行/整体.
    if (
        any(tok in q for tok in ("销量", "销售额", "营业额", "营收", "成本", "毛利",
                                 "卖了", "卖出", "赚钱", "挣钱", "亏钱", "亏本", "赔钱"))
        and extract_dish_candidate(q)
    ):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    # 口语盈亏问 ("最近亏钱了吗") — 交给带盈亏判定的销售概览。
    if (
        re.search(r"(亏钱|亏损|盈利|赚钱|挣钱|挣着钱|赚着钱)(了吗|吗|没有|了没|没)", q)
        and not any(tok in q for tok in ("门店", "分店", "哪家店"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # Time comparisons are a sales-summary question, not the generic
    # all-history trend report.  This must run before the broad "环比/趋势"
    # pattern so "上个月和上上个月营收相比" keeps both requested periods.
    if _is_explicit_sales_period_comparison(q):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # 「上个月的数据和上上个月的数据对比」— 只有泛指"数据/情况"没有营收词,
    # 曾落趋势路径且残月被当完整月比较 (Sheet 行16 复测, R24b)。
    if (
        _has_explicit_sales_period_pair(q)
        and any(tok in q for tok in _COMPARISON_DIRECTION_TOKENS)
        and any(tok in q for tok in ("数据", "情况", "表现", "生意"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # Rolling-window revenue asks ("过去一个月营业额") are windowed sales
    # summaries, not trend reports. T1 previously missed them entirely, so
    # T2/T3 classification drifted and Java's all-history trend tool answered
    # with 19 months of data for a 30-day question (Sheet 7/22).
    if (
        _relative_period_match(q)
        and any(tok in q for tok in ("营业额", "营收", "销售额", "流水"))
        and not any(tok in q for tok in ("趋势", "走势", "同比", "环比", "变化", "增长", "下降"))
    ):
        return "RESTAURANT_OPS_SALES_SUMMARY"
    # Preserve an explicit overall-margin metric, but keep mixed
    # revenue-plus-margin questions on the richer sales summary resolver.
    if (
        any(token in q for token in ("毛利", "毛利率"))
        and any(token in q for token in ("整体", "总体", "总毛利", "多少", "是多少"))
        and not any(token in q for token in ("营收", "营业额", "销售额", "客单价", "订单", "单量"))
    ):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    # Owner action questions such as "提升毛利率，今天先不要做什么" are
    # already specific enough to use the dish-margin diagnosis.  Do not send
    # them to generic optimization clarification or a broad sales summary.
    if (
        any(token in q for token in ("毛利", "毛利率", "利润"))
        and any(token in q for token in (
            "提升", "提高", "改善", "优化", "怎么做", "如何做",
            "先不要做", "不要做", "先别做", "避免做",
        ))
        and not any(token in q for token in ("门店", "分店", "店铺", "哪家店"))
    ):
        return "RESTAURANT_OPS_GROSS_MARGIN"
    for code, groups in _OPS_PATTERNS:
        if all(any(kw in q for kw in group) for group in groups):
            return code
    return None


_GENERIC_RESTAURANT_OPS_CODES = frozenset({
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_TREND_ANALYSIS",
})


def reconcile_restaurant_ops_code(
    query: str,
    expected_code: Optional[str],
) -> Optional[str]:
    """Reconcile a trusted upstream hint with current-query semantics.

    A more specific current-query match beats a stale generic hint, while a
    specific trusted hint still beats a generic text match.  Explicit named
    period comparisons are the strongest case because serving an all-history
    trend there changes the user's requested dates.
    """
    matched = match_restaurant_ops(query)
    if not expected_code:
        return matched
    if not matched or matched == expected_code:
        return expected_code

    def _specificity(code: str) -> int:
        if code == "RESTAURANT_OPS_PLAYBOOK":
            return 3  # explicit phrase request — never overridden by a hint
        if code == "RESTAURANT_OPS_SALES_SUMMARY" and _is_explicit_sales_period_comparison(query):
            return 3
        return 1 if code in _GENERIC_RESTAURANT_OPS_CODES else 2

    return matched if _specificity(matched) > _specificity(expected_code) else expected_code


@dataclass
class OpsAnswer:
    """Structured answer returned from resolve_* functions."""
    code: str
    title: str
    answer_text: str
    charts: List[Dict[str, Any]]
    kpis: List[Dict[str, Any]]
    meta: Dict[str, Any]
    # 🔴 2026-08-14 二次裁定: 这里原来有个 `actions: Tuple[...]` 字段, **已撤**。
    #    实测三层都断 —— 没有消费者 / `fill_dishes` payload 没有 handler /
    #    前端读的是 `suggestedFollowups`。新建一份没人读的契约, 不如复用那份。
    #    ⇒ 按钮现在走 `meta["follow_up_actions"]`, 由
    #      `restaurant_intent_service._suggested_followups` 合进 `suggested_followups`。
    #    `meta` 本来就整份透传到 `result_meta`(见 `_execution_receipt` 的
    #    `receipt = dict(meta)`), **不新开管道**。


@dataclass(frozen=True)
class SalesQuerySpec:
    """Stable query contract for owner-facing sales/profit questions."""
    date_range: Tuple[Optional[date], Optional[date]]
    window_label: str
    comparison_range: Tuple[Optional[date], Optional[date]]
    comparison_label: Optional[str]
    comparison_kind: Optional[str]
    wants_margin: bool
    asks_profitability: bool
    relative_window: bool


def _date_text(value: Any) -> str:
    return value.isoformat() if hasattr(value, "isoformat") else str(value)


def _date_from_any(value: Any) -> Optional[date]:
    if isinstance(value, date):
        return value
    if value:
        try:
            return date.fromisoformat(str(value))
        except ValueError:
            return None
    return None


def _closing(pool_name: str, query: Optional[str]) -> str:
    """收尾建议句：同一问句**同一天**给同一句，跨天才换。

    ⛔ 不调 LLM(措辞的不确定性不值得那个延迟), 纯查表 + 取模。
    ⛔ 带免责义务的池(折扣/比价)每条变体都必须含 `REQUIRED_TOKENS` 里的标记 ——
       **说法可以变, 「说了这件事」不可以省**, 由 test_phrasing_rotation 强制。

    取不到池就返回空串(fail-open): 宁可少一句建议, 不要因为措辞打断问答。
    """
    from smartbi.gold.restaurant import phrasing

    variants = getattr(phrasing, pool_name, ())
    return phrasing.pick_variant(variants, f"{pool_name}|{query or ''}")


def window_scope_text(
    window_label: str,
    requested: Tuple[Any, Any],
    actual: Tuple[Any, Any],
) -> str:
    """把「他问的窗口」和「实际有数据的范围」拼成一句**不说谎**的话。

    🔴 2026-08-16: 原来内嵌在 resolver 里, 写的是
    ``f"{window_label}（{实际范围}）"``。实测长相:

        上个季度（2026-06-29 至 2026-06-30）经营能看：覆盖 2 天

    标签是**请求窗口**(上个季度 = 91 天), 括号里是**实际有数据的范围**(2 天)
    —— 两个口径拼成一句, 读起来是「系统认为上个季度就是这 2 天」。
    ⚠️ 更糟的是它**把真正该被看见的那件事盖住了**: 那个季度 97.8% 的日子
    没有数据, 而这句话让它看起来只是个短窗口。

    ⇒ 窄了就**明说**两个口径; 铺满时不啰嗦。
    ⛔ 只留实际范围会丢「他问的是什么」; 只留标签会丢「实际只有这些天」。

    ⚠️ **抽成具名函数是为了让闸咬得住**: 它原本内嵌在一个几百行的 resolver 里,
    测试只能**复刻**一份逻辑 —— 那样改产品测试不会红(形态 D, 实测变异两条都没红)。
    """
    a_start, a_end = actual
    r_start, r_end = requested
    if not (a_start and a_end):
        return window_label
    actual_txt = _range_text(a_start, a_end)

    # 🔴 「窄了」不等于「值得说」。第一版写的是「端点动了就说」, 实测把
    #    **正常的 ETL 末端滞后**也念了出来:
    #        本月（请求 2026-07-01 至 2026-07-26，实际有数据的只有 …07-25）
    #    今天的数据还没入库是常态, 把它写进每一句话 = 一句天天出现的噪音,
    #    而天天出现的提示等于没有提示(形态 E)。
    #    ⚠️ 仓里那两条测试(`aligns_partial_month_to_primary_actual_end` /
    #      `aligns_week_when_ingestion_trails_sunday`)守的正是「末端对齐、别啰嗦」——
    #      它们守的是**需求**不是历史。
    #
    # ⇒ 只在**实质性缺失**时说, 两种:
    #    ① 起点被截 —— 请求窗口的**前面一整段根本没数据**(上个季度 2/91 天那种)
    #    ② 覆盖不到一半 —— 即使起点没动, 少一半也不该被一个短括号盖过去
    # ⛔ 不用「端点动了」当判据, 也⛔ 不用 90% 这种会误伤一周缺一天(86%)的阈值。
    _start_truncated = bool(r_start and a_start > r_start)
    _req_days = (r_end - r_start).days + 1 if (r_start and r_end) else 0
    _act_days = (a_end - a_start).days + 1
    _mostly_missing = bool(_req_days and _act_days * 2 < _req_days)
    narrowed = _start_truncated or _mostly_missing
    if narrowed:
        return (f"{window_label}（请求 {_range_text(r_start, r_end)}，"
                f"实际有数据的只有 {actual_txt}）")
    return f"{window_label}（{actual_txt}）"


def _range_text(start_date: Any, end_date: Any) -> str:
    """Render a date range; a single-day range reads as one date, not "X 至 X"."""
    if start_date and end_date and _date_text(start_date) == _date_text(end_date):
        return f"{_date_text(start_date)} 当天"
    return f"{_date_text(start_date)} 至 {_date_text(end_date)}"


_FUTURE_WINDOW_LABEL = "未来时间"

# Explicit dish mentions ("米饭的毛利率"). The candidate is only trusted after
# it matches the tenant's own dish rows — an unmatched candidate produces a
# targeted "没有找到该菜品" decline instead of the all-dish ranking.
#: 日历/相对时间词 —— **一份定义, 两处消费**:
#:   ① `_requires_python_window_resolution`：判「要不要把窗口交给 Python 解析」
#:   ② `_DISH_GENERIC_TOKENS`：判「这个候选根本不可能是菜名」
#:
#: 🔴 2026-08-16 B-0: 抽成一份之前, 这两处各写各的, 于是
#:    「上半年营业额怎么样」-> `dish='上半年'` -> 被「菜品范围不能由全店汇总
#:    resolver 代答」拦成反问。⚠️ 而「上半年」在**别处都认识**
#:    (`restaurant_intent.py:1618`、本文件 :2731) —— 只有菜名抽取这条不认。
#:    ⇒ 典型的形态 D: 同一个概念两份, 补一处漏一处。
#: ⛔ 只放**纯时间词**。「对比/比较/环比」是比较词不是时间词, 留在调用处。
_CALENDAR_PERIOD_TOKENS = frozenset({
    "今天", "今日", "昨天", "昨日", "前天", "前日", "前一天", "前一日",
    "本周", "这周", "本星期", "这星期", "这个星期", "上周", "上星期", "上个星期",
    "本月", "这个月", "上个月", "上月", "上上个月", "上上月",
    "本季度", "这个季度", "上季度", "上个季度",
    "今年", "去年", "前年", "半年", "上半年", "下半年", "全年",
})

_DISH_GENERIC_TOKENS = frozenset({
    "整体", "总体", "全部", "所有", "各", "菜品", "菜", "什么菜", "哪道菜",
    "哪个菜", "哪些菜", "单品", "产品", "本店", "门店", "今天", "昨天", "上月",
    "这个", "这道", "那道", "它", "该菜", "这", "那个",
    # ── 指标限定词, 永远不是菜名 (2026-08-07 prod 实测) ──────────────────
    # 「最近30天**加权**毛利率是多少」被答成:
    #   「没有找到名为『加权』的菜品，不能给出该菜的销量或毛利」
    # —— 抽取器把「加权毛利率」的前缀当成了菜名, 于是一个全店指标问句被打成
    # 「查无此菜」。这些词是**指标的限定语**, 不可能是菜。
    #
    # ⚠️ 排除是**整名相等**比较(`candidate in _DISH_GENERIC_TOKENS`), 不是子串,
    # 所以「总汇三明治」「平均主义拼盘」这类真菜名不会被误伤 —— 只有候选**恰好
    # 等于**这些词时才排除。
    "加权", "平均", "人均", "客单", "总计", "合计", "综合", "整店", "全店",
})
_DISH_COST_METRIC_PATTERN = (
    r"(?:(?:单份|每份|每一份|一份|单位|单品|菜品)?"
    r"(?:食材|原料|原材料|配方|标准)?成本(?:率)?)"
)
_DISH_MULTI_METRIC_RE = re.compile(
    r"^[「\"']?(.{1,30}?)[」\"']?(?:的)?"
    r"(?:(?:毛利率|毛利|销量|营业额|销售额|营收|"
    + _DISH_COST_METRIC_PATTERN
    + r")[和与、]?){2,}"
    r"(?:分别)?(?:是多少|有多少|怎么样|如何|多少)?(?:呢|啊|呀)?[?？。!！]?$"
)
_STORE_REVENUE_THEN_DISH_SALES_RE = re.compile(
    r"^(?:营业额|营收|销售额|流水)"
    r"(?:是多少|有多少|怎么样|如何|情况)?"
    r"[和与及、，,]+[「\"']?(.{2,30}?)[」\"']?(?:的)?"
    r"(?:销量|销售量|卖了多少|卖出多少|卖出)"
    r"(?:是多少|有多少|怎么样|如何|多少|情况)?"
    r"(?:呢|啊|呀)?[?？。!！]?$"
)
_DISH_QUERY_RE = re.compile(
    r"^[「\"']?(.{1,30}?)[」\"']?(?:的)?"
    r"(?:毛利率|毛利|销量|营业额|销售额|营收|"
    + _DISH_COST_METRIC_PATTERN
    + r"|卖得|卖了|卖出)"
    r"(?:是多少|有多少|怎么样|如何|好不好|多少|几份|呢"
    r"|为什么(?:(?:是)?这样|这么高|这么低|这么多|这么少|高|低|多|少)?"
    r"|为何(?:(?:是)?这样|这么高|这么低|这么多|这么少|高|低|多|少)?|怎么回事"
    r"|原因是什么|怎么优化|如何优化|怎么改善|如何改善"
    r"|怎么提升|如何提升)?"
    r"(?:[，,；;](?:它|这个菜|这道菜)?(?:整体)?表现(?:怎么样|如何|好不好)?)?"
    r"[?？。!！]?$"
)
# 「米饭赚钱吗」— 盈亏动词形态, 实体点名 + 盈亏由毛利判定 (R20)。
_DISH_PROFIT_RE = re.compile(
    r"^[「\"']?(.{2,30}?)[」\"']?(?:的)?"
    r"(?:赚钱|挣钱|亏钱|亏本|赔钱|盈利)(?:吗|了吗|不)?[?？。!！]?$"
)
_DISH_LEADING_PRONOUN_RE = re.compile(r"^(?:这个|这道|那个|那道|它|该菜|这|那)+")
_DISH_LEADING_QUERY_VERB_RE = re.compile(
    r"^(?:请)?(?:查询|查一下|查看|看一下|看看|统计|汇总)(?:一下)?"
)
# ─── 精确日期区间 (spec 持续项「精确日期区间」, 2026-07-28 专测后补) ────────
# 专测结论: 之前「6月3号到18号」这类问法**完全不支持**, 而且失败得难看 ——
# 确定性层解析不出时间, 于是菜品抽取把「3号到18号」当成菜名, 回答
# 「没有找到名为「3号到18号」的菜品」。老板问营收, 系统答查无此菜, 会让人
# 以为自己菜名打错了。比单纯"不支持"更糟。
#
# 覆盖形态: 2026-06-03到2026-06-18 / 6月3号到18号 / 6月3日至6月18日 /
#           6月3号到7月2号 (跨月) / 从…到… 前缀
# ⛔ fail-closed: 无年份按今年推断(落在未来则取去年); 起点晚于终点、或日期
#    本身非法(2月30日) 一律**不匹配**, 让流程照常走澄清 —— 绝不猜、不交换端点。
_ABS_DATE_RANGE_RE = re.compile(
    r"(?:从)?"
    r"(?P<y1>20\d{2})?[-/年]?(?P<m1>1[0-2]|0?[1-9])[-/月](?P<d1>3[01]|[12]\d|0?[1-9])[号日]?"
    r"\s*(?:到|至|~|～|—|－|-)\s*"
    r"(?:(?P<y2>20\d{2})[-/年])?(?:(?P<m2>1[0-2]|0?[1-9])[-/月])?(?P<d2>3[01]|[12]\d|0?[1-9])[号日]?"
)


def parse_absolute_date_range(
    text: Optional[str], *, today: Optional[date] = None
) -> "Optional[Tuple[date, date, str]]":
    """从自由文本里解出显式日期区间, 返回 (start, end, 匹配到的原文片段)。

    解不出 / 非法 / 起点晚于终点 一律返回 None (fail-closed)。
    """
    if not text:
        return None
    match = _ABS_DATE_RANGE_RE.search(text)
    if not match:
        return None
    anchor = today or date.today()
    g = match.groupdict()
    m1, d1 = int(g["m1"]), int(g["d1"])
    m2 = int(g["m2"]) if g.get("m2") else m1          # 「6月3号到18号」省略了后半的月
    d2 = int(g["d2"])
    y1 = int(g["y1"]) if g.get("y1") else anchor.year
    y2 = int(g["y2"]) if g.get("y2") else y1
    try:
        start = date(y1, m1, d1)
        end = date(y2, m2, d2)
    except ValueError:                                 # 2月30日 之类
        return None
    if not g.get("y1") and start > anchor:             # 无年份且落在未来 -> 指去年
        try:
            start = start.replace(year=start.year - 1)
            end = end.replace(year=end.year - 1)
        except ValueError:
            return None
    if start > end:                                    # 端点写反: 不猜, 不交换
        return None
    return start, end, match.group(0)


#: 半年 / 季度 —— **只给 `_is_pure_time_expression` 的整体匹配用**。
#: ⛔ 故意**不**并进 `_DISH_LEADING_TIME_RE`：那个是**前缀剥离器**，
#:    往里加词会改掉剥离行为 —— 实测「半年陈花雕的销量」会从 `'半年陈花雕'`
#:    变成 `'陈花雕'`（一个查不到的半截菜名）。⚠️ 加词的地方不对，
#:    受害的是另一个功能。
#: ⚠️ 时间表达是**闭集**(枚举得完)，所以在这里用词表成立；
#:    菜名不是闭集，所以 `_DISH_GENERIC_TOKENS` 那条棘轮拒绝加词是对的。
_HALF_YEAR_QUARTER_RE = re.compile(
    r"(?:今年|去年|前年)?(?:上半年|下半年)"
    r"|(?:本|这个|上|上个)季度|第[一二三四1-4]季度"
)

#: 「今天到现在 / 今日截至目前」—— 今天在「到」之前, 是给**今天**定界,
#: ⛔ 不是累计。「到今天为止 / 截至今天」不匹配(今天在「到」之后), 那些是真累计。
_TODAY_SO_FAR_RE = re.compile(
    r"(?:今天|今日|本日)(?:一直)?(?:到|至|截至|截止)?(?:现在|目前|此刻|这会儿|这时候)"
)

_DISH_LEADING_TIME_RE = re.compile(
    r"^(?:今天|今日|昨天|昨日|前天|本周|这周|上上周|上周|本月|这个月|上上个月|上上月|上个月|上月"
    r"|今年|去年|前年|现在|如今|目前"
    r"|(?:最近|近|过去)(?:\d+|[一二三四五六七八九十半两]+)(?:小时|天|日|周|个?月|年)"
    r"|20\d{2}年(?:全年|度)?|全年"
    r"|(?:20\d{2}年)?(?:1[0-2]|0?[1-9]|十一|十二|[一二三四五六七八九十])月份?)+"
)
_DISH_INLINE_TIME_BEFORE_METRIC_RE = re.compile(
    r"(?:(?:今天|今日|昨天|昨日|前天|本周|这周|上上周|上周|本月|这个月"
    r"|上上个月|上上月|上个月|上月|今年|去年|前年|现在|如今|目前|截至目前"
    r"|到目前|到今天|截至今天|当前累计|全部历史"
    r"|(?:最近|近|过去)(?:\d+|[一二三四五六七八九十半两]+)"
    r"(?:小时|天|日|周|个?月|年)"
    r"|20\d{2}年(?:全年|度)?|全年"
    r"|(?:20\d{2}年)?(?:1[0-2]|0?[1-9]|十一|十二|[一二三四五六七八九十])月份?))"
    r"(?:的)?"
    r"(?=(?:毛利率|毛利|销量|营业额|销售额|营收|"
    r"(?:单份|每份|每一份|一份|单位|单品|菜品)?"
    r"(?:食材|原料|原材料|配方|标准)?成本(?:率)?|卖得|卖了|卖出))"
)
_DISH_LEADING_STORE_SCOPE_RE = re.compile(
    r"^(?:(?:全部|所有|各家|每家)门店|各门店|(?:全部|所有)店|全店(?:汇总)?)(?:的)?"
)


_RESOLVER_QUERY_HINT_RE = re.compile(
    r"(?:\s+(?:最近\S{0,6}|近\S{0,6}|过去\S{0,6}|今天|今日|昨天|昨日|本周|这周"
    r"|上周|本月|这个月|上个月|上月|今年|去年|半年|全部历史|毛利|赚钱了吗"
    r"|\d{4}-\d{2}-\d{2}(?:\s*至\s*\d{4}-\d{2}-\d{2})?))+\s*$"
)


# ─────────────────────────────────────────────────────────────────────
# 菜单目录 —— 「这句话里有没有菜」的唯一裁决者
# ─────────────────────────────────────────────────────────────────────
#
# 菜名抽取本来是**残差式**的: 剥掉时间/门店/指标后剩下的就当菜名。它预设了
# 「这句话里一定有个菜」, 于是「本月人力成本是多少」里的「人力」被当成菜,
# 答案变成「没有找到名为『人力』的菜品」。靠黑名单堵要穷举「所有不是菜的
# 名词」—— 无界集合, 每补一轮词下一个没列到的名词立刻把口子重新捅开。
#
# 仓库本来就写着正确原则 (restaurant_intent.py: 「真伪由下游 resolver 对
# dim_product/dim_store 验证 —— LLM 只提名, 不裁决」), 而且校验确实存在
# (dish_not_found / dish_mention_ambiguous)。问题是**裁决发生在路由已经锁定
# 之后** —— 它只能说「查无此菜」, 没法说「所以这根本不是菜品问题, 改道」。
#
# 这里把裁决权前移: 候选词先对租户真实菜单验证, 命中才算菜名。黑名单翻转成
# 白名单, 而白名单是菜单本身 —— 有限、权威、自维护(上新菜自动生效)。
#
# ⚠️ 失败一律**开放**(退回历史行为), 绝不收紧: 目录没加载 / 加载失败 / 租户
# 菜单为空(尚未同步) 时, 收紧会把该租户的菜品问答整片判死, 那比误读更糟。
_DISH_CATALOGUE: ContextVar[Optional[frozenset]] = ContextVar(
    "restaurant_dish_catalogue", default=None,
)
_DISH_CATALOGUE_CACHE: Dict[str, Tuple[float, frozenset]] = {}
_DISH_CATALOGUE_TTL_SECONDS = 300.0


def set_dish_catalogue(names: Optional[frozenset]):
    """绑定本次解析的菜单词表, 返回 reset token (务必 try/finally 复位)。"""
    return _DISH_CATALOGUE.set(names or None)


def reset_dish_catalogue(token) -> None:
    _DISH_CATALOGUE.reset(token)


def current_dish_catalogue() -> Optional[frozenset]:
    return _DISH_CATALOGUE.get()


def dish_catalogue_state() -> Dict[str, int]:
    """运维探针: {factory_id: 已缓存菜名条数}。空 dict = 闸没在生效。"""
    return {fid: len(names) for fid, (_ts, names) in _DISH_CATALOGUE_CACHE.items()}


async def load_dish_catalogue(pool, factory_id: str) -> Optional[frozenset]:
    """租户全部菜名 (name + normalized_name + POS 别名), 带 TTL 缓存。

    取 dim_product 全量而不是「窗口内有销量的菜」—— 后者会把本月没卖的真菜
    判成非菜。失败返回 None = 目录不可用 = 调用方退回历史行为。

    2026-07-30 开关史: 一度默认关闭, 因为闸第一次真正生效后 prod 的损耗路线同时
    退化成澄清, 当时怀疑是闸。**后来查清与闸无关** —— 真因是 REVIEW 链在
    Max/Plus 额度耗尽后落到 deepseek-v3.2, 它给出的计划内容完全正确却把
    confidence 填成 -1.0, 被 `confidence < 0.6 → clarification` 判死(PR#2015)。
    该根因修复后重新默认开启; `RESTAURANT_DISH_CATALOGUE_GATE=0` 仍可随时关。

    加载成功会打一条 INFO(含菜名条数) —— 这是**「闸确实在生效」的正向证据**。
    此前 dim_product_alias 缺表导致加载恒失败、闸实际从未生效, 而 fail-open 让
    它看起来一切正常, 我据此误报过「已上线并验证」。fail-open 的功能必须能拿到
    正向证据, 光看「没报错 + 症状消失」不算。
    """
    if os.environ.get("RESTAURANT_DISH_CATALOGUE_GATE", "1") != "1":
        return None
    now = time.time()
    cached = _DISH_CATALOGUE_CACHE.get(factory_id)
    if cached and now - cached[0] < _DISH_CATALOGUE_TTL_SECONDS:
        return cached[1] or None
    # dim_product 与别名表**分两条查**, 不能 UNION 在一条 SQL 里:
    # dim_product_alias 并非每个库都有(prod smartbi_prod_db 实测不存在), 合成
    # 一条时它的 UndefinedTableError 会让整条查询失败 → 目录返回 None →
    # 闸静默失效。别名只是锦上添花, 缺了不该拖垮主目录。
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id,
            )
            rows = await conn.fetch(
                "SELECT name, normalized_name FROM dim_product WHERE factory_id = $1",
                factory_id,
            )
            try:
                rows = list(rows) + list(await conn.fetch(
                    "SELECT pos_name AS name, pos_name AS normalized_name "
                    "FROM dim_product_alias WHERE factory_id = $1",
                    factory_id,
                ))
            except Exception:  # noqa: BLE001 - 别名表可选
                logger.debug(
                    "[dish-catalogue] alias table unavailable for %s", factory_id,
                )
    except Exception:  # noqa: BLE001 - 目录不可用绝不能挡住问答
        logger.warning("[dish-catalogue] load failed for %s", factory_id, exc_info=True)
        return None
    names = frozenset(
        str(value).strip()
        for row in rows
        for value in (row["name"], row["normalized_name"])
        if value and str(value).strip()
    )
    _DISH_CATALOGUE_CACHE[factory_id] = (now, names)
    # 正向证据: 没有这条 INFO 就说明闸没在生效(见 docstring)。
    logger.info(
        "[dish-catalogue] loaded %d dish names for %s", len(names), factory_id,
    )
    return names or None


@asynccontextmanager
async def dish_catalogue_scope(pool, factory_id: str):
    """在本次解析期间绑定该租户的菜单目录。

    contextvar 是 task 级的, 不复位会泄漏到同一 task 的后续代码 —— 一旦那里
    是另一个租户, 就会拿错菜单去裁决。故一律 try/finally 复位。
    """
    token = set_dish_catalogue(await load_dish_catalogue(pool, factory_id))
    try:
        yield
    finally:
        reset_dish_catalogue(token)


def catalogue_has_no_dish_mention(text: str) -> bool:
    """目录已加载, 且整句话里**没有出现任何真实菜名** → True。

    给指标检测用: 「本月人力成本是多少」既没点到菜, 裸「成本」就不该被当成
    菜品成本需求。目录不可用时恒为 False —— 行为与历史完全一致。
    """
    names = _DISH_CATALOGUE.get()
    if not names or not text:
        return False
    compact = re.sub(r"\s+", "", text)
    return not any(len(name) >= 2 and name in compact for name in names)


def _catalogue_says_not_a_dish(candidate: str) -> bool:
    """目录已加载且候选词**不是**菜 → True (据此否决)。

    匹配语义与 ``_match_dish_rows`` 一致: 精确 / 候选是菜名子串 (用户常说
    半个菜名) / 菜名是候选子串。语义不一致会造出新的错配面。
    """
    names = _DISH_CATALOGUE.get()
    if not names or not candidate:
        return False  # 目录不可用 → 不否决
    if candidate in names:
        return False
    for name in names:
        if candidate in name:
            return False
        if len(name) >= 2 and name in candidate:
            return False
    return True


# 后厨域名词 —— 它们是**别的域的对象**, 既不是菜名, 也不是菜品成本。
# 「食材损耗成本」「领料成本」里的「成本」修饰的是这些名词而不是菜品:
#   - 判成 recipe_cost 会多规划一个菜品类意图 (2026-07-30 prod 实拍噪音
#     「没有找到名为「食材损耗」的菜品」, 见 _detect_requested_metrics);
#   - 当成菜名则会把那个意图限域到一个不存在的菜。
# 下面的拒绝表原本只有**指标词**(成本/毛利/销量)和**通用菜品词**(菜/菜品/
# 单品), 而「成本」已被 _DISH_QUERY_RE 当指标后缀吃掉, 剩下的「食材损耗」
# 一个拒绝词都不含, 于是被当成菜名。
# 只收**不可能出现在菜名里**的词 ——「食材」不在表内, 因为「食材成本」是
# 真的菜品成本问法。
_KITCHEN_OPS_NOUNS = (
    "损耗", "报损", "浪费", "领料", "出库", "入库", "盘点", "库存", "进货", "采购",
)


# order_type 的落库形态按租户不同 —— DEMO_REST 是中文, MOCK_REST 是英文码。
# 归一到展示口径, 否则「堂食/外卖」两条主行取不到、占比与 KPI 全空,
# 老板看到的是裸 `dine_in` / `takeaway`(2026-07-30 prod 实拍)。
_ORDER_TYPE_BUCKETS: Dict[str, str] = {
    "堂食": "堂食", "堂吃": "堂食", "店内": "堂食",
    "dine_in": "堂食", "dinein": "堂食", "dine-in": "堂食",
    "eat_in": "堂食", "eatin": "堂食",
    "外卖": "外卖", "外送": "外卖",
    "takeaway": "外卖", "take_away": "外卖", "take-away": "外卖",
    "takeout": "外卖", "take_out": "外卖", "take-out": "外卖",
    "delivery": "外卖", "waimai": "外卖",
    # 第三类渠道: 既不是堂食也不是外卖, 保留成自己的桶, 但给中文标签 ——
    # 归到任一边都会让占比说谎。
    "团购": "团购", "groupon": "团购", "group_buy": "团购", "groupbuy": "团购",
    "自提": "自提", "pickup": "自提", "self_pickup": "自提", "selfpickup": "自提",
}


def _normalize_order_type(raw: "Optional[str]") -> "Optional[str]":
    """把 order_type 归一到展示桶; 未标注返回 None, 未知码**原样返回**。

    ⛔ 未知码不能丢: 丢了占比就会说谎(分母少了那部分单量), 而如实列出至少让人
    看得见有个没认出来的渠道。这与「未标注渠道」是两回事 —— 后者本就单独披露。
    """
    if not isinstance(raw, str):
        return None
    token = raw.strip()
    if not token:
        return None
    return _ORDER_TYPE_BUCKETS.get(token.lower(), token)


def _extract_dish_candidate_single(text: str) -> "Optional[str]":
    # build_resolver_query 会在整句尾部空格拼接窗口标签/盈亏词
    # ("…那X呢 最近30天") — 先剥掉, 否则省略句「…呢」锚定失配,
    # 实体切换静默回落父段旧菜 (R17c 实测事故)。
    text = _RESOLVER_QUERY_HINT_RE.sub("", text).strip()
    # 旧路径会在句尾追加「（窗口标签）」— 剥掉再做锚定匹配。
    text = re.sub(r"[（(][^（）()]{0,24}[）)]\s*$", "", text).strip()
    # Chinese speakers commonly place the time immediately before the metric:
    # "卤炸牛肉串本月销量为什么低".  The date parser still receives the
    # untouched original query; this normalization is only for isolating the
    # named dish, so the time phrase cannot be swallowed into the dish slot.
    # 显式日期区间不是菜名。不剥掉的话「…6月3号到18号的营收」会把
    # 「3号到18号」当成菜品实体 —— 专测实拍: "没有找到名为「3号到18号」的菜品",
    # 老板问营收却被告知查无此菜 (spec 持续项「精确日期区间」)。
    _absolute_span = parse_absolute_date_range(text)
    if _absolute_span is not None:
        text = text.replace(_absolute_span[2], "", 1).strip()
    text = _DISH_INLINE_TIME_BEFORE_METRIC_RE.sub("", text, count=1)
    # 复合指标形态更具体, 先试 — 否则单指标懒惰前缀会吞掉「营收和」等垃圾段。
    match = _DISH_MULTI_METRIC_RE.match(text)
    if not match:
        match = _DISH_QUERY_RE.match(text)
    if not match:
        match = _DISH_PROFIT_RE.match(text)
    if not match:
        # 省略句实体切换: 「那招牌藤椒味呢」— 无指标词, 实体显式点名,
        # 指标由上文继承 (垃圾候选被下方 generic/reject 表拦截)。
        match = re.match(r"^那?[「\"']?(.{2,24}?)[」\"']?呢[?？]?$", text)
    if not match:
        return None
    candidate = _DISH_LEADING_QUERY_VERB_RE.sub("", match.group(1).strip())
    candidate = _DISH_LEADING_TIME_RE.sub("", candidate)
    candidate = _DISH_LEADING_PRONOUN_RE.sub("", candidate)
    candidate = _DISH_LEADING_TIME_RE.sub("", candidate)
    # Aggregate store scope is not part of a dish name.  Without stripping it,
    # "本月全部门店招牌菜的营业额" yields "全部门店招牌菜"; the generic-token
    # guard then rejects the whole candidate and the resolver silently falls
    # back to a store ranking.  Strip only a leading, complete scope phrase —
    # never an arbitrary "店" substring inside a real dish name.
    candidate = _DISH_LEADING_STORE_SCOPE_RE.sub("", candidate)
    # Explicit time-only follow-ups are compiled as
    # ``全部门店 + 上个月 + 菜名`` while ordinary turns often arrive as
    # ``上个月 + 全部门店 + 菜名``.  Accept either trusted prefix order.
    candidate = _DISH_LEADING_TIME_RE.sub("", candidate)
    candidate = candidate.strip("的， ,")
    if len(candidate) < 2 or candidate in _DISH_GENERIC_TOKENS:
        return None
    if any(tok in candidate for tok in (
        "排行", "排名", "趋势", "对比", "分析", "整体", "全部",
        # 疑问词: 「怎么/多少/如何」本来就在, 但漏了「哪」「什么」——
        # 2026-07-30 prod 实拍「哪个菜最赚钱」被 _DISH_PROFIT_RE 捕成菜名
        # 「哪个菜最」, 用户答完澄清后收到「没有找到名为「哪个菜最」的菜品」。
        # 下面 _catalogue_says_not_a_dish 那道「菜单说了算」在这里够不到:
        # dish_catalogue_scope 只包住 parse_restaurant_query, 解析器在作用域外
        # 运行, 目录没加载就 fail-open。所以这道守卫不能依赖目录。
        "情况", "如何", "怎么", "多少", "哪", "什么", "？", "?", "营收", "营业额",
        "销售", "销量", "毛利", "成本", "盈利", "赚钱", "利润",
        "过去", "最近", "个月", "季度", "一年", "继续追问",
    ) + _KITCHEN_OPS_NOUNS):
        return None
    candidate = candidate[:60]
    # 最后一道: 菜单说了算。黑名单只能拦住有人想到过的词, 目录能拦住全部。
    if _catalogue_says_not_a_dish(candidate):
        return None
    return candidate


def extract_dish_candidate(query: "Optional[str]") -> "Optional[str]":
    """Pull an explicit dish-name candidate from a margin/sales question.

    Contextualized follow-ups arrive as "父问题；继续追问：子问题" — each
    segment is tried independently (parent first: it names the dish that a
    pronoun follow-up refers back to).
    """
    if not query:
        return None
    text = query.strip()
    if any(tok in text for tok in ("哪家店", "哪个店", "哪家门店")):
        return None
    # 店+菜混合 ("鲜行者店的米饭卖得怎么样"): 先剥离门店名再取菜名;
    # 纯门店问法剥离后剩余过短, 自然返回 None 走门店路线。
    stores_in_text = extract_store_mentions(text)
    if stores_in_text:
        for store_in_text in stores_in_text:
            text = text.replace(store_in_text, "", 1)
        text = _DISH_LEADING_TIME_RE.sub("", text)
        text = text.lstrip("和与跟及、的， ,").strip()
        if len(text) < 4:
            return None
        # Cross-grain read: "本月 A 店营业额和娃娃菜销量情况" asks for
        # the store's overall revenue plus one named dish's sales volume.  The
        # leading store metric is not part of the dish name.  Recognize only
        # this explicit ordering so ordinary "A 店娃娃菜的营收和销量" keeps
        # both metrics scoped to the dish.
        cross_grain_match = _STORE_REVENUE_THEN_DISH_SALES_RE.match(text)
        if cross_grain_match:
            candidate = cross_grain_match.group(1).strip("「」\"' 的， ,")
            if (
                len(candidate) >= 2
                and candidate not in _DISH_GENERIC_TOKENS
                and not any(token in candidate for token in (
                    "排行", "排名", "趋势", "对比", "分析", "整体", "全部",
                    "情况", "多少", "营收", "营业额", "销售", "销量",
                    "毛利", "成本", "利润",
                ))
            ):
                return candidate[:60]
    segments = [text]
    if "继续追问" in text:
        # 追问段优先: 「那招牌藤椒味呢」显式点名新菜时切换实体;
        # 追问段无菜名 (「成本如何」「这个…」) 再回落父问题段继承。
        segments = [
            seg.replace("继续追问：", "").replace("继续追问:", "").strip()
            for seg in re.split(r"[；;]", text)
        ][::-1]
    for segment in segments:
        if not segment:
            continue
        candidate = _extract_dish_candidate_single(segment)
        if candidate and not _is_pure_time_expression(candidate):
            return candidate
    return None


def _is_pure_time_expression(candidate: str) -> bool:
    """候选**整体**就是一个时间表达 ⇒ 它是时间词, ⛔ 不是菜名。

    🔴 2026-08-16 B-0: 实测「上半年营业额怎么样」-> `dish='上半年'`,
    然后被「菜品范围不能由全店汇总 resolver 代答」拦成反问。

    ⛔ **不往 `_DISH_GENERIC_TOKENS` 加词** —— 那条棘轮
    (`test_no_new_blacklist_words`) 问得对:
        「加词之前先回答: 菜单/食材目录为什么没拦住它?
         黑名单只能拦住有人想到过的词, 目录能拦住全部。」
    而这里连目录都不需要: **本模块自己就有一个权威的时间解析器**。
    候选丢给它, 能解析出窗口就说明它是时间表达 —— 覆盖它认识的全部写法,
    ⛔ 不用我去想「还有哪些时间词」(那正是黑名单的病)。

    ⚠️ 判据是**整体**匹配(`m.end() == len(candidate)`), ⛔ 不是「候选里含时间词」。
       🔴 第一版写的是「丢给 `_resolve_sales_date_range`, 解析得出窗口就算时间词」——
          而那个解析器做的是**子串**匹配, 于是「半年陈花雕」(一道真菜)当场被误伤成
          时间词, 返回 None。实测抓到的。
       ⇒ 改成对时间**语法**做整体匹配: 「半年陈花雕」只匹配掉前两个字, `end()=2 != 5`,
         照样是菜名; 「上半年」被整体吃掉, 判为时间词。
    """
    if not candidate:
        return False
    for pattern in (_DISH_LEADING_TIME_RE, _HALF_YEAR_QUARTER_RE):
        m = pattern.match(candidate)
        if m and m.end() == len(candidate):
            return True
    return False


def _asks_store_revenue_then_dish_sales(
    query: str,
    store_names: Sequence[str],
    dish_name: str,
) -> bool:
    """Whether one sentence explicitly asks two different result grains.

    The accepted shape is store name -> overall revenue metric -> named dish
    -> sales-volume metric.  Requiring this order keeps the common
    "A 店娃娃菜的营收和销量" request scoped entirely to the dish.
    """
    compact = re.sub(r"\s+", "", query or "")
    dish = re.sub(r"\s+", "", dish_name or "")
    if not compact or not dish:
        return False
    store_positions = [
        compact.find(re.sub(r"\s+", "", store_name))
        for store_name in store_names
        if store_name
    ]
    store_positions = [position for position in store_positions if position >= 0]
    if not store_positions:
        return False
    store_position = min(store_positions)
    revenue_positions = [
        compact.find(token, store_position + 1)
        for token in ("营业额", "营收", "销售额", "流水")
    ]
    revenue_positions = [position for position in revenue_positions if position >= 0]
    dish_position = compact.find(dish)
    if not revenue_positions or dish_position < 0:
        return False
    revenue_position = min(revenue_positions)
    sales_positions = [
        compact.find(token, dish_position + len(dish))
        for token in ("销量", "销售量", "卖了多少", "卖出多少", "卖出")
    ]
    sales_positions = [position for position in sales_positions if position >= 0]
    return bool(
        sales_positions
        and store_position < revenue_position < dish_position < min(sales_positions)
    )


_DISH_COMPARE_RE = re.compile(
    r"^(.{1,24}?)[和与、](.{1,24}?)哪(?:个|道)?"
    r"(?:(?:更)?(?:毛利率|毛利|销量|销售额|营收|成本)(?:更)?(?:高|低|多|少|好)?"
    r"|(?:更)?(?:高|低|多|少|好|赚钱|挣钱|划算|好卖|卖得好|卖得多))"
    r"[?？。]?$"
)


def extract_dish_candidates(query: "Optional[str]") -> list:
    """All named dishes in the question (comparatives first, else single)."""
    if not query:
        return []
    text = query.strip()
    # ``build_resolver_query`` may append canonical time/profit hints so the
    # resolver sees the sealed plan's semantics.  The single-dish extractor
    # already removes these hints; multi-dish comparison must do the same or
    # ``A和B哪个赚钱 赚钱了吗`` no longer matches the anchored comparison form
    # and silently degrades to an all-menu answer (then fails the contract).
    text = _RESOLVER_QUERY_HINT_RE.sub("", text).strip()
    text = re.sub(r"[（(][^（）()]{0,24}[）)]\s*$", "", text).strip()
    match = _DISH_COMPARE_RE.match(text)
    if match:
        out = []
        for raw in (match.group(1), match.group(2)):
            cand = _DISH_LEADING_TIME_RE.sub("", raw.strip())
            cand = re.sub(
                r"^(?:全部门店|所有门店|各门店|所有店|全部店|全店汇总|连锁整体)",
                "",
                cand,
                count=1,
            ).strip()
            cand = _DISH_LEADING_PRONOUN_RE.sub("", cand).strip("的， ,")
            if (
                len(cand) >= 2
                and cand not in _DISH_GENERIC_TOKENS
                and not _catalogue_says_not_a_dish(cand)
            ):
                out.append(cand[:60])
        if len(out) == 2:
            return out
    # 单实体优先 (含单实体多指标 "米饭的销量、毛利率和成本" — R26b 回归修:
    # 列表规则曾把指标顿号当成多实体, 抢在 multi-metric 之前把限域打掉)。
    single = extract_dish_candidate(query)
    if single and not re.search(r"[和、,，]", single):
        return [single]
    # R26: 多实体并列 ("米饭和娃娃菜和招牌藤椒味(单人份)的销量") —
    # 拆分后各自经 _match_dish_rows 验证, 命中≥2 走既有对比路径。
    list_form = re.match(
        r"^(.{4,80}?)(?:的)?"
        r"(?:销量|营收|销售额|毛利率|毛利|成本)"
        r"(?:分别是多少|是多少|多少|如何|怎么样)?[?？。!！]?$",
        text,
    )
    if list_form and re.search(r"[和、,，]", list_form.group(1)):
        parts = re.split(r"[和、,，]", list_form.group(1))
        multi = []
        for cand in parts:
            cand = _DISH_LEADING_TIME_RE.sub("", cand.strip()).strip("「」\"' 的")
            cand = re.sub(
                r"^(?:全部门店|所有门店|各门店|所有店|全部店|全店汇总|连锁整体)",
                "",
                cand,
                count=1,
            ).strip("「」\"' 的")
            if (
                len(cand) >= 2
                and cand not in _DISH_GENERIC_TOKENS
                and not any(t in cand for t in (
                    "排行", "整体", "全部",
                    "销量", "营收", "销售额", "毛利", "成本", "客单",
                ))
                and not _catalogue_says_not_a_dish(cand)
            ):
                multi.append(cand[:60])
        if len(multi) >= 2:
            return multi
    return []


def _match_dish_rows(candidate: str, rows) -> list:
    """POS rows whose dish name matches the candidate (exact first)."""
    exact = [
        r for r in rows
        if candidate in (r["dish_name"], r["normalized_name"])
    ]
    if exact:
        return exact[:1]
    hits = []
    seen = set()
    for r in rows:
        name = r["dish_name"] or ""
        norm = r["normalized_name"] or ""
        if candidate in name or candidate in norm or (norm and norm in candidate):
            key = r["product_id"]
            if key not in seen:
                seen.add(key)
                hits.append(r)
    return hits[:6]


# Follow-up phrases that reference earlier dates ("沿用刚才的日期") are only
# answerable when the caller actually restored那些日期. Without a restored
# range, silently answering with a default window substitutes dates the user
# never asked for, so those queries must be declined instead.
_DATE_BACKREF_RE = re.compile(
    r"(?:沿用|刚才|之前|先前|上面|上述|前面)[^。！？]{0,12}?(?:日期|时间|区间|范围)"
    r"|(?:同样|相同)的(?:日期|时间|区间|范围)"
)
_DATE_BACKREF_CODES = frozenset({
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
})

# Explicit "XX店" mentions in a store-margin question. Generic tokens are not
# store names; a candidate must survive dictionary lookup before use.
_STORE_MENTION_STOPWORDS = frozenset({
    "哪家店", "哪个店", "哪家门店", "这家店", "那家店", "各门店", "各家店",
    "各店", "所有门店", "全部门店", "门店", "分店", "店铺", "本店", "单店",
    "每家店", "每个店", "一家店", "旗舰店", "连锁店",
})
# ─── 繁体输入：确定性词表同时收录繁体变体 ────────────────────────────────
# 这些词表原本只有简体，繁体输入(港澳台用户 / 系统语言设为繁中 / 从繁体文档
# 复制粘贴)会整片匹配不上。实测 2026-07-28：
#   「本月全部门店营收多少」  → 正常
#   「本月全部門店營收多少」  → “门店范围不能由全店或全门店 resolver 代答”
# 因为「全部門店」不在 _ALL_STORE_SCOPE_TOKENS 里，全店范围识别不出来，
# resolver 直接拒答 —— 同一句话只因简繁不同就答不了。
#
# 修法是**扩充词表**而不是折叠输入文本：
#   * 扩词表 → 全部 10 处 `token in text` 使用点自动受益，零调用点改动；
#   * ⛔ 折叠文本则会波及**实体抽取** —— 门店/菜品名要按原文去库里比对，
#     把用户输入折叠成简体后，以繁体命名的真实门店反而匹配不上。
# 故这里只动确定性词表，实体抽取一律不碰原文。
_SIMPLIFIED_TO_TRADITIONAL = str.maketrans({
    "与": "與", "个": "個", "价": "價", "体": "體", "单": "單",
    "总": "總", "无": "無", "汇": "匯", "连": "連", "铺": "鋪",
    "锁": "鎖", "门": "門", "间": "間", "领": "領",
})


def with_traditional(tokens: Tuple[str, ...]) -> Tuple[str, ...]:
    """原词表 + 其繁体变体（同形词不重复收录）。"""
    out = list(tokens)
    for token in tokens:
        variant = token.translate(_SIMPLIFIED_TO_TRADITIONAL)
        if variant != token and variant not in out:
            out.append(variant)
    return tuple(out)


_GENERIC_STORE_SCOPE_FRAGMENTS = frozenset(with_traditional((
    "全部门店", "所有门店", "各门店", "每家店", "全部店", "所有店", "全店",
)))
_STORE_MENTION_RE = re.compile(r"[一-龥A-Za-z0-9·]{2,24}(?:门店|店)")
_STORE_REFERENCE_SUFFIX_RE = re.compile(r"(?<=店)(?:这|那|该)家?店$")
_STORE_MENTION_PREFIX_TRIM = re.compile(
    r"^(?:有没有|有无|是否有|是否|存不存在|是不是|会不会"
    r"|(?:最近|近|过去)(?:\d+|[一二三四五六七八九十半两]+)(?:小时|天|日|周|个?月|年)"
    r"|上个月|上月|本月|这个月|上周|本周|这周|今天|昨天|今年|去年|最近"
    r"|请|帮我|帮忙|查一下|查查|查询|查|看看|看一下|看|分析一下|分析"
    r"|对比|比较|了解|统计|计算|那|这|把|给|在|的|是|说说|讲讲)+"
)


# 「全部门店…毛利最低的菜品」= 全店聚合范围下的**菜品**毛利榜, 不是门店榜。
#
# 2026-08-01: STORE_MARGIN 的 group-1 含「门店」且排在 GROSS_MARGIN 之前, 于是任何
# 带「全部门店」的菜品毛利问句都先被它抓走。但「全部门店」只是**聚合范围**, 不是把
# 问题变成门店榜 —— 这正是 dish_ranking_direction 的 docstring 早就写下的原则
# (「Store words are scope, not a competing metric」), 销量侧一直如此, 毛利侧漏了。
#
# 守卫刻意保守: 只在「全店聚合 + 菜品维度 + 毛利指标」三者同时成立, 且**既没点名具体
# 门店、也没在问门店榜**时才改判。三类必须留给 STORE_MARGIN 的情形逐条排除:
#   - 门店榜        「哪家店毛利最好」「门店毛利排行」
#   - 点名具体门店  「东城店毛利最低的菜品」—— ⚠️ store_dish_split_dish 对这句返回
#                   None(实测), 不会提前拦下, 所以必须在这里自己排除
#   - 各门店拆分    「各门店毛利对比」
_ALL_STORE_AGG_DISH_MARGIN_DISH_TOKENS = ("菜品", "菜系", "菜价", "哪道菜", "道菜")
_ALL_STORE_AGG_DISH_MARGIN_METRIC_TOKENS = ("毛利", "毛利率", "净赚", "赚钱", "挣钱", "利润")
# 出现这些 = 用户要按门店看, 不是要菜品榜
_ALL_STORE_AGG_DISH_MARGIN_STORE_RANK_TOKENS = frozenset(with_traditional((
    "哪家", "各门店", "每家店", "各店", "分店", "门店对比", "门店排行", "门店排名",
)))


def _all_store_scope_dish_margin(query: "Optional[str]") -> bool:
    """全店聚合范围下的菜品毛利榜 → True(应判 GROSS_MARGIN 而非 STORE_MARGIN)。"""
    if not query:
        return False
    q = query.strip()
    if not any(frag in q for frag in _GENERIC_STORE_SCOPE_FRAGMENTS):
        return False
    if not any(tok in q for tok in _ALL_STORE_AGG_DISH_MARGIN_DISH_TOKENS):
        return False
    if not any(tok in q for tok in _ALL_STORE_AGG_DISH_MARGIN_METRIC_TOKENS):
        return False
    if any(tok in q for tok in _ALL_STORE_AGG_DISH_MARGIN_STORE_RANK_TOKENS):
        return False
    # 摘掉泛指范围词后若仍残留门店名, 说明点名了具体门店 → 店×菜粒度, 不改判。
    stripped = q
    for frag in _GENERIC_STORE_SCOPE_FRAGMENTS:
        stripped = stripped.replace(frag, "")
    if _STORE_MENTION_RE.search(stripped):
        return False
    return True


_DEMO_GOLD_TENANT = "RES_3101_009"
_DEMO_GOLD_MAPPED_CODES = frozenset({
    "RESTAURANT_OPS_STORE_DIRECTORY",
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
    "RESTAURANT_OPS_TREND_ANALYSIS",
    # 🔴 2026-08-17 补: 渠道构成当天开始**出各门店的表**, 于是它进了「门店宇宙
    #    必须一致」这一族。不补的后果当天就实测到了 ——
    #      同一个 DEMO_REST 账号:
    #        「哪家店外卖占比最高」(store_scoped=True)  -> RES_3101_009, 30 家店
    #        「外卖占了几成」      (store_scoped=False) -> DEMO_REST,    27 家店
    #    两套门店、两套营收，对不上账。**正是本函数 docstring 里说它要防的那件事**
    #    (「排行说某店第一，而销售汇总夸的是另一个数据空间里的店」)。
    # ⚠️ 这是「机制在、没接上」: `store_scoped` 这个参数存在的全部意义就是让调用方
    #    声明「我这个答案是按门店的」, 而我给 CHANNEL_MIX 加门店表时**没有人告诉
    #    租户解析这件事**。加能力要同时问一句: 它有没有进入某个「必须一致」的族。
    "RESTAURANT_OPS_CHANNEL_MIX",
})

#: 能按门店分组、但**刻意没有**进上面那张表的 resolver。
#: ⛔ 这不是豁免, 是**留痕**: 它们的门店宇宙与排行/汇总那一族可能对不上,
#:    只是还没有人核过。⚠️ 棘轮 —— 这张表只许变短。
#: (2026-08-17 建表时的存量。新写的 store-capable resolver 一律进映射表,
#:  除非有人核过并写明理由。)
_STORE_CAPABLE_BUT_NOT_DEMO_MAPPED = frozenset({
    "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
    "RESTAURANT_OPS_STAFFING_ADVICE",
})


def demo_data_factory_for_code(
    code: Optional[str], factory_id: Optional[str], *, store_scoped: bool = False,
) -> Optional[str]:
    """Data-read tenant for the demo account, unified per answer family.

    Revenue/store/trend and dish-margin answers read the seeded gold tenant so their store
    universe agrees with the Java-native ranking tools (previously the rank
    said one store was #1 while the sales summary praised a store from a
    different data space). Dish ranking and its later cost/margin follow-up
    therefore cannot switch tenants mid-conversation. Auth, session and cache
    identity always stay on the trusted tenant.
    """
    if (
        factory_id
        and factory_id.upper() == "DEMO_REST"
        and (store_scoped or (code or "") in _DEMO_GOLD_MAPPED_CODES)
    ):
        return _DEMO_GOLD_TENANT
    return factory_id


# 疑问词是**有限封闭集合**, 所以这是结构判据, 不是又一份黑名单。
#
# 2026-07-30 prod 实测(生产的两轮链路, 带 session_key + session_summary):
#   turn1 「哪个菜最赚钱」 → 澄清: 你想看哪个时间范围？
#   turn2 「最近30天」     → **没有找到名为「哪个菜最」的菜品**
# 「哪个菜最」是原文子串, 所以反幻觉守卫放行; 占位符黑名单里又没有它。
# 靠往黑名单里加词堵不住 —— 那要求穷举「所有不是菜/店的名词」, 是无界集合。
# 带疑问词的片段**永远不可能是专名**, 按这一条拒绝, 集合是封闭的。
#
# ⚠️ 与「名字不存在」是两回事: 「红烧肉卖了多少」而菜单上没有红烧肉, 仍然应该
# 答「查无此菜」—— 那是用户真的当名字用了。这里拒的是用户**根本没当名字用**的词。
#
# 🏠 定义在 router(低层)而不是 restaurant_intent —— import 方向是
#    restaurant_intent → restaurant_ops_router, 反向没有。放在这里两边都能用,
#    **只有一份**(形态 D: 同一个东西有两份, 它一定会漂)。
_INTERROGATIVE_MARKERS: Tuple[str, ...] = (
    "哪", "什么", "多少", "怎么", "为什么", "是否", "有没有",
)


def extract_store_mentions(query: Optional[str]) -> list[str]:
    """Pull one or more explicit store names from free text."""
    if not query:
        return []
    mentions: list[str] = []
    # Split connectors only after a completed store token.  A global split on
    # "和" corrupts legitimate names such as "和平店".
    normalized = re.sub(
        r"(?<=店)[、，,和与跟及](?=[\u4e00-\u9fffA-Za-z0-9·]{1,24}(?:门店|店))",
        " ",
        query,
    )
    for segment in normalized.split():
        for match in _STORE_MENTION_RE.finditer(segment):
            candidate = _STORE_MENTION_PREFIX_TRIM.sub("", match.group(0))
            # Natural speech often repeats the entity as a pronoun:
            # “鲜行者打浦桥日月光店这家店…”.  The greedy lexical matcher used
            # to treat the trailing “这家店” as part of the catalogue name.
            # Keep only the concrete name; the suffix remains discourse
            # context and is never a store identifier.
            candidate = _STORE_REFERENCE_SUFFIX_RE.sub("", candidate)
            if len(candidate) < 3 or candidate in _STORE_MENTION_STOPWORDS:
                continue
            # Time/range prefixes can be glued to an all-store scope, e.g.
            # "最近7天全部门店".  The generic scope is never a concrete store
            # entity, even when the regex captured a prefix before it.
            if any(
                fragment in candidate
                for fragment in _GENERIC_STORE_SCOPE_FRAGMENTS
            ):
                continue
            # 疑问词残留 ("上个月哪家店") 不是店名 —— 用**封闭集合**判, 不是黑名单。
            # 2026-08-17 owner 裁定「疑问词直接退役」: 原来这里列着
            # 哪家/哪个/哪些/有没有, 而「哪几家店」不在其中, 于是
            # 「哪几家店在拖后腿」被当成店名去查, 答「没有找到名为『哪几家店』的门店」。
            # 往黑名单里补「哪几家」只会等下一个变体(哪间/哪位/哪一家)。
            # 判据换成 `_INTERROGATIVE_MARKERS`: 「哪」一个字盖住全部变体。
            #
            # 📏 换之前量过误伤(prod `dim_store`, 逐租户 set_config):
            #    RES_3101_009 38 家 / DEMO_REST 27 / R_XMX_CHAIN 1 / R_GML_DEMO 132
            #    = **198 个真店名, 封闭集合命中 0 个**(现行黑名单也命中 0)。
            if any(marker in candidate for marker in _INTERROGATIVE_MARKERS):
                continue
            # ⚠️ 下面这些**不是**疑问词, 是排名/极值词和指标名, 属于另外两类:
            #    排名/极值 → 另一个封闭集合; 指标名 → 应由 METRICS 登记表推导。
            #    本轮**只退役疑问词**(去黑话只动黑话), 这两类单独登记、单独做。
            if any(tok in candidate for tok in ("最高", "最低", "最好", "最差",
                                                "排名", "排行", "客单价", "营收")):
                continue
            candidate = candidate[:160]
            if candidate not in mentions:
                mentions.append(candidate)
    return mentions[:8]


def extract_store_mention(query: Optional[str]) -> Optional[str]:
    """Compatibility wrapper returning the first explicit store name."""
    mentions = extract_store_mentions(query)
    return mentions[0] if mentions else None


def _markdown_table(headers, rows, right_align=()):
    """把数据行渲染成 GFM 表格, 返回可直接 extend 进 lines 的列表。

    RN 端 `MarkdownRenderer` 用的是 react-native-markdown-display(markdown-it),
    GFM 表格开箱即用, table/thead/th/tr/td 样式都已就位 —— 后端只要吐标准表格。

    ⛔ **全站唯一一处表格拼装**。排行/对比类答案有十几处, 各写一遍就是十几份
       格式: 改一次要改十几处, 而漏掉的那处不报错, 只是长得跟别处不一样。

    ⚠️ 单元格里的 `|` 必须转义, 否则一个菜名就能把整张表的列数冲乱 ——
       而 markdown 表格错列**不会报错**, 只是渲染成一坨。
    ⚠️ 表格前必须有空行, 否则 markdown-it 会把它并进上一段当普通文字。

    right_align: 需要右对齐的列下标(金额/数量列) —— 数字左对齐读起来对不齐。
    """
    def cell(v):
        return str(v).replace("|", "\\|").replace("\n", " ")

    sep = []
    for i in range(len(headers)):
        sep.append("---:" if i in right_align else "---")
    out = ["", "| " + " | ".join(cell(h) for h in headers) + " |",
           "| " + " | ".join(sep) + " |"]
    for row in rows:
        out.append("| " + " | ".join(cell(c) for c in row) + " |")
    return out


async def _canonicalize_store_mention(
    smartbi_pool, factory_id: str, mention: str,
) -> List[str]:
    """dim_store names matching the mention (exact first, then containment).

    The RLS GUC must be set inside an explicit transaction: asyncpg runs
    statements in their own implicit transactions, so a LOCAL ``set_config``
    issued as a standalone statement is discarded before the next query and
    row visibility silently degrades to whatever session GUC the pooled
    connection last carried.
    """
    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id,
            )
            exact = await conn.fetch(
                "SELECT name FROM dim_store WHERE factory_id = $1 AND name = $2 LIMIT 1",
                factory_id, mention,
            )
            if exact:
                return [exact[0]["name"]]
            rows = await conn.fetch(
                """
                SELECT name FROM dim_store
                 WHERE factory_id = $1
                   AND (name LIKE '%' || $2 || '%' OR $2 LIKE '%' || name || '%')
                 ORDER BY LENGTH(name) ASC
                 LIMIT 6
                """,
                factory_id, mention,
            )
            return [r["name"] for r in rows]


def _actual_window_text(start_date: Any, end_date: Any, requested_days: int) -> str:
    if start_date and end_date:
        return f"{_date_text(start_date)} 至 {_date_text(end_date)}"
    return f"最近 {requested_days} 天"


def _explicit_window(
    date_range: Optional[Tuple[Optional[date], Optional[date]]],
    window_label: Optional[str],
    days: int,
) -> Tuple[Optional[date], Optional[date], str]:
    """把 ``date_range``/``window_label`` 化成 ``(起, 止, 显示文案)``。

    唯一入口。三个 resolver(损耗/领料/盘点)都要「按请求的窗口取数并如实标注」,
    各写一份必然漂 —— 今天已经因为「同一件事有五套私有实现」栽过一次(PR #2079)。

    显示文案在有显式区间时**总是带上具体日期**:「上个月（2026-06-01 至 2026-06-30）」。
    裸标签和它取代的「近 30 天」一样不可证伪, 读者没法核对口径。
    没有区间时退回滚动窗口的说法, 与只传 ``days`` 的老调用方保持一致。
    """
    start = date_range[0] if date_range else None
    end = date_range[1] if date_range else None
    if (start is None) != (end is None):
        # 半个区间会让缺的那一侧悄悄退回滚动窗 —— 同样是「看着答了、答的是别的窗口」。
        raise ValueError("date_range must include both start and end")
    if start and end:
        text = f"{_date_text(start)} 至 {_date_text(end)}"
        if window_label and window_label not in text:
            text = f"{window_label}（{text}）"
    else:
        text = f"近 {days} 天"
    return start, end, text


def _format_sales_quantity(value: Any) -> str:
    """Format POS quantities without turning a positive sale into zero."""
    quantity = float(value or 0)
    if math.isclose(quantity, 0.0, abs_tol=1e-9):
        return "0"
    if 0 < quantity < 1:
        return "不足 1"
    if math.isclose(quantity, round(quantity), abs_tol=1e-9):
        return f"{quantity:,.0f}"
    return f"{quantity:,.2f}".rstrip("0").rstrip(".")


def _compute_margin_dragger(
    with_cost: List[Dict[str, Any]], avg_margin: float, total_rev_with_cost: float,
    *, min_revenue: float = 1000.0,
) -> Optional[Dict[str, Any]]:
    """Which dish drags the BLENDED gross margin most (拖毛利归因, deterministic).

    A dish's drag on the blended margin is the exact identity
    ``营收占比 × (毛利率 − 平均毛利率)``; summed over costed dishes it is 0, so the
    most-negative term is the single biggest dragger. Crucially this is NOT the
    lowest-rate dish — a tiny-revenue low-margin dish barely moves the blend,
    while a high-volume mildly-below-average dish drags it more. Returns the
    dragger + a cause label, or None when fewer than 2 costed dishes qualify.
    """
    cands = [e for e in (with_cost or []) if float(e.get("revenue") or 0.0) >= min_revenue]
    if len(cands) < 2 or total_rev_with_cost <= 0:
        return None
    best = None
    for e in cands:
        share = float(e["revenue"]) / total_rev_with_cost
        drag = share * (float(e["margin_rate"]) - avg_margin)
        if best is None or drag < best["drag"]:
            best = {"name": e.get("name"), "margin_rate": float(e["margin_rate"]),
                    "share": share, "drag": drag}
    rate = best["margin_rate"]
    if rate < 0:
        cause = "该菜亏本（成本高于售价），优先复核售价/份量/进价"
    elif rate < avg_margin * 0.6:
        cause = "毛利率过低，主要靠提价或降成本"
    elif best["share"] > 0.15:
        cause = "销量占比大、毛利率偏低，规模摊薄了整体毛利"
    else:
        cause = "毛利率偏低，可小幅提价或标准化份量"
    best["cause"] = cause
    return best


# 🔴 owner 2026-08-13/14: 这条判据**只能有一处定义**, 在
#    `metric_registry.dish_cost_is_implausible`。本函数是它的一层薄封装,
#    ⛔ 不在这里重写任何比较。
#
# 这里踩过**两次**同一个坑, 一次比一次隐蔽:
#   ① 阈值两份: 这里 10.0 / 执行器 5.0 —— 米饭 9.95 倍恰好卡在中间。
#      修法是共用常量, 当时以为收敛完了。
#   ② **粒度两份**: 常量确实只有一处了, 但执行器那侧按 `fact_pos_item` 逐行判、
#      这里按整道菜的聚合价判。同一张卡, 两种判决。
#      实测(RES_3101_009 / 2026-08-12): 两条路差 **19,131.37**, 残差全部来自米饭。
# ⇒ 判据 = 阈值 + **作用粒度**。只共用前者等于没共用。
_MAX_SANE_DISH_UNIT_COST = _REG_MAX_SANE_DISH_UNIT_COST


def _is_plausible_dish_unit_cost(
    unit_cost: Optional[float], qty: float, revenue: float,
) -> bool:
    """Reject corrupted cost cards before they can poison a margin answer.

    ⛔ 判定本身在登记表里, 本函数只做取反。
    """
    return not _dish_cost_is_implausible(unit_cost, qty, revenue)


#: 拿不到开价时的退路。⛔ 不留一个空的「建议动作:」标题 —— 那比泛泛之词更糟。
_GENERIC_ACTIONS = (
    "对高营收低毛利菜品先复核售价、赠品和食材规格，优先做小幅提价或份量标准化。",
    "对高毛利高销量菜品加大套餐露出和门店推荐，作为拉升整体毛利率的主推款。",
    "对缺成本菜品补齐配方和最近进价，否则利润判断会失真。",
)


def _build_qa_fill_offers(cell) -> List[Dict[str, Any]]:
    """问答那条路的「建议动作」—— 来自 `build_fill_offers`, ⛔ 不手写。

    🔴 owner 2026-08-14: 改之前是三条泛泛之词, 而日结那边早就是
       「先补这 3 道(菜名), 40.2% → 47.7%」。**同一件事一边具体一边空泛。**

    🔴🔴 2026-08-14 二次订正: 这个函数原来返回 `List[str]`(把 offer 拍平成文案),
       而 `follow_up_actions.build_actions` 要的是**结构化 offer**(它读
       `offer["kind"]` / `offer["text"]`)。于是它每次都
       `AttributeError: 'str' object has no attribute 'get'`,
       被 `_build_follow_up_actions` 的 `except` 吞掉, 日志写
       「追问按钮生成失败, 本次不带按钮」——
       **T1/T2 按钮从上线起一次都没出现过**, 而单测喂的是 dict 所以全绿。
       ⇒ 判据: **至少一条断言要跑在产品真实入口上**(见
         `test_the_buttons_are_built_from_the_real_offer_objects`)。

    ⇒ 现在返回**结构化 offer**, 文案由调用方从同一份产出渲染 ——
      一份来源, 两个消费者(正文 / 按钮)。⛔ 不为按钮再拼一份。
    """
    from smartbi.gold.restaurant.degrade_guard import degrade_on_error
    from smartbi.gold.restaurant.fill_offers import build_fill_offers

    # 🔴 降级留着, 静默去掉(owner 2026-08-14)。编程错误打 ERROR + 计数,
    #    ⛔ 不再和「数据缺」一起写成一句 WARNING。见 `degrade_guard` 顶部。
    offers = degrade_on_error(
        DEGRADE_QA_OFFERS, [],
        lambda: build_fill_offers(
            provenance=cell.provenance,
            estimation_basis=cell.estimation_basis,
            estimated_metric_labels=[],
            cost_gaps=cell.cost_gaps,
            coverage_ratio=cell.coverage_ratio,
            coverage_denominator=cell.coverage_denominator,
        ),
        what="开价")
    return [o for o in offers if isinstance(o, dict) and o.get("text")]


def _offer_texts(offers: Sequence[Dict[str, Any]]) -> List[str]:
    """正文里那几行「建议动作」。⚠️ 具体的排在前面, 泛泛的补在后面 ——
    店长先看到能立刻做的那条。
    """
    return [str(o.get("text") or "") for o in offers] + list(_GENERIC_ACTIONS)


def _build_margin_entries(
    pos_rows: List[Any],
    cretas_map: Dict[str, str],
    cost_map: Dict[str, float],
) -> List[Dict[str, Any]]:
    """Build margin rows while preserving unknown cost as unknown.

    ⚠️ 取键**必须**走 `cost_key_of` —— 它连着 `normalize_dish_name`, 是两条路
       共用的那一份规范化。直接 `cretas_map.get(name)` 会绕开它, 于是一侧对
       大小写敏感一侧不敏感, 差异表现成「日结算了这道菜, 问答没算」。
    """
    from smartbi.gold.restaurant.restaurant_cost_mapping import cost_key_of
    entries: List[Dict[str, Any]] = []
    for row in pos_rows:
        source_pk = cost_key_of(cretas_map, row["normalized_name"])
        qty = float(row["total_qty"])
        revenue = float(row["total_revenue"])
        cost_present = (
            source_pk is not None
            and source_pk in cost_map
            and cost_map[source_pk] is not None
        )
        candidate_cost = float(cost_map[source_pk]) if cost_present else None
        invalid_cost = cost_present and not _is_plausible_dish_unit_cost(
            candidate_cost, qty, revenue,
        )
        has_cost = cost_present and not invalid_cost
        unit_cost = candidate_cost if has_cost else None
        total_cost = unit_cost * qty if unit_cost is not None else None
        gross_profit = revenue - total_cost if total_cost is not None else None
        margin_rate = gross_profit / revenue if gross_profit is not None and revenue > 0 else None
        entries.append({
            "name": row["dish_name"],
            "normalized_name": row["normalized_name"],
            "qty": qty,
            "revenue": revenue,
            "bills": int(row["bills"]),
            "food_cost_unit": unit_cost,
            "total_cost": total_cost,
            "gross_profit": gross_profit,
            "margin_rate": margin_rate,
            "has_cost": has_cost,
            "invalid_cost": invalid_cost,
            # 🔴 被判无效的那张卡**要留下值**。`food_cost_unit` 只在 has_cost
            #    时才填, 于是异常项那里恒为 None —— 正文照着印会打出
            #    「成本卡 ¥0.00 一份」(2026-08-14 prod 实测), 一个既没信息量
            #    又明显是假的数字, 店长照着去核对只会更糊涂。
            # ⚠️ 它**不进任何计算**, 只用于指名道姓那句话。
            "invalid_cost_value": candidate_cost if invalid_cost else None,
        })
    return entries


def _rank_cost_complete_margin_entries(
    entries: List[Dict[str, Any]], top_n: int,
) -> List[Dict[str, Any]]:
    """Rank only cost-complete dishes by absolute gross profit."""
    return sorted(
        (item for item in entries if item["has_cost"] and item["gross_profit"] is not None),
        key=lambda item: item["gross_profit"],
        reverse=True,
    )[:top_n]


def _scoped_dish_metric_answer(
    entry: Dict[str, Any],
    *,
    window_label: str,
    query: str,
    peer_sales_quantities: Optional[Sequence[float]] = None,
) -> Optional[str]:
    """Render only the metric/action requested for one named dish.

    The gross-margin resolver owns the real joined sales+cost facts for a
    named dish, but that does not authorize it to dump the full margin report
    when the current turn merely asks "销量呢".  This projection keeps the
    resolver deterministic while respecting the current-turn QueryPlan.
    """
    text = (query or "").strip()
    asks_reasonableness = any(token in text for token in (
        "是否合理", "合理吗", "正不正常", "正常吗", "是否异常",
    ))
    asks_diagnosis = asks_reasonableness or any(token in text for token in (
        "为什么", "原因", "怎么回事", "为何",
    ))
    asks_optimization = any(token in text for token in (
        "怎么优化", "如何优化", "优化", "改善", "怎么办", "怎么做",
        "怎么提升", "如何提升", "提升", "下一步", "先做什么",
    ))
    asks_sales = (
        any(token in text for token in (
            "菜品销量", "销量", "销售量", "卖了多少", "卖出",
        ))
        or _asks_low_sales_state(text)
        or _asks_high_sales_state(text)
    )
    asks_cost = any(token in text for token in (
        "菜品成本", "食材成本", "配方成本", "单品成本", "成本",
    ))
    asks_margin = any(token in text for token in (
        "毛利率", "毛利", "利润", "盈利", "赚钱", "亏钱", "亏损",
    ))
    asks_revenue = any(token in text for token in (
        "营收", "营业额", "销售额", "销售收入", "营业收入", "流水",
    ))
    if not any((
        asks_diagnosis, asks_optimization, asks_sales, asks_cost,
        asks_margin, asks_revenue,
    )):
        return None

    name = str(entry.get("name") or "该菜品")
    qty = float(entry.get("qty") or 0.0)
    qty_text = _format_sales_quantity(qty)
    revenue = float(entry.get("revenue") or 0.0)
    bills = int(entry.get("bills") or 0)
    unit_price = revenue / qty if qty > 0 else None
    has_cost = bool(entry.get("has_cost"))
    unit_cost = (
        float(entry["food_cost_unit"])
        if has_cost and entry.get("food_cost_unit") is not None
        else None
    )
    total_cost = (
        float(entry["total_cost"])
        if has_cost and entry.get("total_cost") is not None
        else None
    )
    gross_profit = (
        float(entry["gross_profit"])
        if has_cost and entry.get("gross_profit") is not None
        else None
    )
    margin_rate = (
        float(entry["margin_rate"])
        if has_cost and entry.get("margin_rate") is not None
        else None
    )

    if asks_diagnosis:
        if asks_reasonableness:
            if asks_cost and not asks_margin:
                value_text = (
                    f"单份成本 ¥{unit_cost:,.2f}、总成本 ¥{total_cost:,.2f}"
                    if unit_cost is not None and total_cost is not None
                    else "成本数据尚未完整覆盖"
                )
                benchmark = "目标单份成本、同类菜成本或上一可比周期"
            elif asks_sales and not asks_margin:
                value_text = f"销量 {qty_text} 份、覆盖 {bills} 单"
                benchmark = "同类主菜中位数、上架天数和上一可比周期"
            elif margin_rate is not None and gross_profit is not None:
                value_text = (
                    f"毛利率 {margin_rate * 100:.1f}%、毛利 ¥{gross_profit:,.2f}"
                )
                benchmark = "门店目标毛利率、同类菜毛利率或上一可比周期"
            else:
                value_text = (
                    f"销量 {qty_text} 份、营收 ¥{revenue:,.2f}，但成本尚未完整覆盖"
                )
                benchmark = "完整成本、目标毛利率和同类菜基准"
            return (
                f"**判断：「{name}」{window_label}{value_text}；"
                "仅凭当前绝对值还不能判断是否合理。**\n\n"
                f"需要补充{benchmark}后，才能按同一时间和门店范围判断偏高、偏低或正常；"
                "这里不会用主观阈值替代真实基准。"
            )
        if asks_sales and not asks_cost and not asks_margin:
            units_per_bill = qty / bills if bills > 0 else None
            composition = (
                f"、平均每单 {units_per_bill:,.2f} 份"
                if units_per_bill is not None else ""
            )
            sales_state = (
                "low"
                if _asks_low_sales_state(text)
                else "high"
                if _asks_high_sales_state(text)
                else None
            )
            if sales_state:
                peers = [
                    float(value)
                    for value in (peer_sales_quantities or ())
                    if isinstance(value, (int, float))
                    and not isinstance(value, bool)
                    and math.isfinite(float(value))
                    and float(value) >= 0
                ]
                if peers:
                    comparable = sorted([qty, *peers])
                    count = len(comparable)
                    midpoint = count // 2
                    median_qty = (
                        comparable[midpoint]
                        if count % 2
                        else (comparable[midpoint - 1] + comparable[midpoint]) / 2.0
                    )
                    rank = 1 + sum(value > qty for value in peers)
                    if sales_state == "low":
                        premise_holds = qty < median_qty
                        premise_text = (
                            "低于中位数，**“销量低”的前提成立**"
                            if premise_holds
                            else "不低于中位数，**“销量低”的前提不成立**"
                        )
                        next_step = (
                            "现有汇总数据能确认相对位置，但还不能证明为什么低。"
                            "下一步应按同一时间和门店范围核对上架天数、售罄缺货、"
                            "平均实收价与促销、门店和时段分布；在这些数据补齐前，"
                            "不把任何一项直接说成原因。"
                            if premise_holds
                            else "因此不能按“低销量问题”直接制定动作。若目标是继续提高销量，"
                            "可以在守住单份毛利的前提下，选择单店/单时段做小范围露出或套餐测试，"
                            "再与同口径对照组比较。"
                        )
                    else:
                        premise_holds = qty > median_qty
                        premise_text = (
                            "高于中位数，**“销量高”的前提成立**"
                            if premise_holds
                            else "不高于中位数，**“销量高”的前提不成立**"
                        )
                        next_step = (
                            "现有汇总数据能确认相对位置，但还不能证明为什么高。"
                            "下一步应按同一时间和门店范围核对上架天数、曝光与点单入口、"
                            "平均实收价与促销、门店和时段分布；在这些数据补齐前，"
                            "不把任何一项直接说成原因。"
                            if premise_holds
                            else "因此不能按“高销量”解释现状。若目标是继续提高销量，"
                            "可以在守住单份毛利的前提下，选择单店/单时段做小范围露出或套餐测试，"
                            "再与同口径对照组比较。"
                        )
                    return (
                        f"**判断：「{name}」{window_label}销量 {qty_text} 份，"
                        f"在 {count} 道可比主菜中按销量从高到低排第 {rank}，"
                        f"中位数为 {_format_sales_quantity(median_qty)} 份；"
                        f"{premise_text}。**\n\n"
                        f"覆盖 {bills} 单{composition}。{next_step}"
                    )
                premise_label = "销量低" if sales_state == "low" else "销量高"
                return (
                    f"**判断：「{name}」{window_label}销量 {qty_text} 份，"
                    f"但可比主菜不足，当前不能判断“{premise_label}”的前提是否成立。**\n\n"
                    f"覆盖 {bills} 单{composition}。请先补齐同一时间、同一门店范围内"
                    "其他主菜销量，再核对上架天数、缺货、价格促销、门店和时段分布；"
                    "这里不会先假设用户的前提正确。"
                )
            return (
                f"**原因拆解：「{name}」{window_label}销量为 {qty_text} 份，"
                f"覆盖 {bills} 单{composition}；当前只能解释销量构成，不能证明业务因果。**\n\n"
                "如果你问的是销量为什么上涨或下降，需要指定对比周期，再核对上架时长、"
                "售罄缺货、价格与促销、门店和时段分布；这里不会用毛利率替代销量原因。"
            )
        if asks_cost and not asks_margin:
            if unit_cost is None or total_cost is None:
                return (
                    f"**原因拆解：「{name}」{window_label}成本数据不完整，"
                    "当前不能解释成本为什么是这个结果，也不能证明业务因果。**\n\n"
                    "请先补齐配方标准用量、最近有效采购价和单位换算，再指定对比周期"
                    "核对采购价、配方与损耗变化。"
                )
            return (
                f"**原因拆解：「{name}」{window_label}单份成本为 ¥{unit_cost:,.2f}，"
                f"按销量 {qty_text} 份计算总成本 ¥{total_cost:,.2f}；"
                "当前只能解释计算构成，不能证明业务因果。**\n\n"
                "成本口径来自配方标准用量 × 最近有效食材单价。若要解释成本涨跌，"
                "还需指定对比周期并核对采购价、配方用量、单位换算和损耗变化。"
            )
        if asks_revenue and not asks_sales and not asks_cost and not asks_margin:
            avg_price = revenue / qty if qty > 0 else None
            price_text = (
                f"、平均实收 ¥{avg_price:,.2f}/份"
                if avg_price is not None else ""
            )
            return (
                f"**原因拆解：「{name}」{window_label}营收为 ¥{revenue:,.2f}，"
                f"销量 {qty_text} 份、覆盖 {bills} 单{price_text}；"
                "当前只能解释营收构成，不能证明业务因果。**\n\n"
                "若要解释营收为什么上涨或下降，需要指定对比周期，并核对售价/折扣、"
                "销量、缺货、门店与时段分布；这里不会用毛利率替代营收原因。"
            )
        if not has_cost or unit_price is None or gross_profit is None or margin_rate is None:
            return (
                f"**原因拆解：暂时只能确认「{name}」{window_label}销量 {qty_text} 份、"
                f"营收 ¥{revenue:,.2f}，但成本不完整。**\n\n"
                "当前不能解释毛利率为什么是这个结果，也不会用销量或营收替代原因。"
                "请先补齐配方、采购价和单位换算；如要分析涨跌原因，还需指定对比周期。"
            )
        unit_margin = gross_profit / qty if qty > 0 else 0.0
        return (
            f"**原因拆解：「{name}」{window_label}毛利率为 {margin_rate * 100:.1f}%；"
            "当前数据能解释计算构成，不能证明业务因果。**\n\n"
            f"- 平均实收单价：¥{unit_price:,.2f}/份\n"
            f"- 单份食材成本：¥{unit_cost:,.2f}/份\n"
            f"- 单份毛利：¥{unit_margin:,.2f}/份\n"
            f"- 计算构成：`毛利率 = (¥{unit_price:,.2f} - ¥{unit_cost:,.2f})"
            f" ÷ ¥{unit_price:,.2f} = {margin_rate * 100:.1f}%`\n\n"
            "如果你问的是毛利率为什么上涨或下降，还需要指定对比周期，并继续核对售价/折扣、"
            "配方用量、采购价和损耗变化；这里不把相关性说成因果。"
        )

    if asks_optimization:
        target = (
            "营收" if asks_revenue and not asks_margin and not asks_cost
            else "销量" if asks_sales and not asks_margin and not asks_cost
            else "成本" if asks_cost and not asks_margin
            else "毛利率"
        )
        known = (
            f"当前销量 {qty_text} 份、营收 ¥{revenue:,.2f}"
            + (
                f"、单份成本 ¥{unit_cost:,.2f}、毛利率 {margin_rate * 100:.1f}%"
                if unit_cost is not None and margin_rate is not None
                else "；成本尚未完整覆盖"
            )
        )
        return (
            f"**优化目标：优化「{name}」{window_label}的{target}，同时避免只看一个指标行动。**\n\n"
            f"{known}。\n\n"
            "**优化动作：**\n"
            "1. 先核对该菜平均实收价、促销折扣和单份标准成本，确认问题来自售价还是成本。\n"
            "2. 若目标是销量，只做单店/单时段小范围露出测试，同时守住单份毛利；"
            "若目标是毛利率，先查配方用量、采购价和损耗，不直接全店涨价。\n"
            "3. 数据不完整时先补齐成本和促销记录，不据此下架或批量调价。\n\n"
            f"**验证指标：**同一观察周期比较销量、平均实收价、单份成本、毛利额和毛利率；"
            "只有目标指标改善且其他指标没有明显恶化，才扩大范围。"
        )

    if asks_sales and not asks_cost and not asks_margin:
        return (
            f"「{name}」{window_label}销量 **{qty_text} 份**、"
            f"营收 **¥{revenue:,.2f}**，覆盖订单 {bills} 单。"
        )
    if asks_revenue and not asks_cost and not asks_margin:
        return (
            f"「{name}」{window_label}营收 **¥{revenue:,.2f}**，"
            f"对应销量 {qty_text} 份、覆盖订单 {bills} 单。"
        )
    if asks_cost and not asks_margin:
        if unit_cost is None or total_cost is None:
            return (
                f"「{name}」{window_label}销量 {qty_text} 份，但成本数据不完整，"
                "当前无法可靠计算单份成本和总成本；不会用营收或毛利替代。"
            )
        return (
            f"「{name}」{window_label}单份食材成本 **¥{unit_cost:,.2f}**；"
            f"按销量 {qty_text} 份计算，对应总成本 **¥{total_cost:,.2f}**。"
            "成本口径来自配方标准用量 × 最近有效食材单价。"
        )
    if asks_margin and gross_profit is not None and margin_rate is not None and total_cost is not None:
        # ⚠️ 2026-08-10: 上面三条分支都带 `not asks_margin`, 所以「销量、毛利率、
        #    成本」这种**同时问三样**的问句会全部落到这一条。而这一条原先不含销量,
        #    于是 Answer Contract 判 `missing=["request_coverage"]` —— 数算出来了
        #    (qty_text 就在手边)、答案被整份扔掉, 用户看到「没有可靠覆盖问题中要求
        #    的全部指标」。
        #    飞轮 miss 台账里这条问句累计 **47 次**, 是当前被真实问到最多的答不出来。
        #    判据: **互斥 if 链表达不了「多选」** —— 每加一个可问指标, 组合数翻倍,
        #          而漏掉的组合表现为「答非所问被契约拦下」, 不是报错。
        #          这里先把销量补进最完整的那条(它本来就算好了); 根治是走登记表
        #          的多指标路径(spec_to_cells), 那是独立一件事。
        sales_part = f"销量 **{qty_text} 份**、" if asks_sales else ""
        return (
            f"「{name}」{window_label}{sales_part}营收 **¥{revenue:,.2f}**、"
            f"成本 **¥{total_cost:,.2f}**、"
            f"毛利 **¥{gross_profit:,.2f}**、毛利率 **{margin_rate * 100:.1f}%**。\n\n"
            f"计算过程：`毛利 ¥{gross_profit:,.2f} = 营收 ¥{revenue:,.2f}"
            f" − 成本 ¥{total_cost:,.2f}`。"
        )
    return None


def _aggregate_store_margin_entries(
    store_dish_rows: List[Any],
    name_to_pk: Dict[str, str],
    cost_by_pk: Dict[str, float],
    bill_count_by_store: Optional[Dict[Any, int]] = None,
) -> List[Dict[str, Any]]:
    """Aggregate store margins without turning missing dish cost into zero.

    ⚠️ 取键走 `cost_key_of` —— 与毛利问答/日结**同一份**规范化。
    """
    from smartbi.gold.restaurant.restaurant_cost_mapping import cost_key_of
    per_store: Dict[Any, Dict[str, Any]] = {}
    for row in store_dish_rows:
        source_pk = cost_key_of(name_to_pk, row["normalized_name"])
        cost_present = (
            source_pk is not None
            and source_pk in cost_by_pk
            and cost_by_pk[source_pk] is not None
        )
        candidate_cost = float(cost_by_pk[source_pk]) if cost_present else None
        revenue = float(row["revenue"] or 0.0)
        qty = float(row["qty"] or 0.0)
        invalid_cost = cost_present and not _is_plausible_dish_unit_cost(
            candidate_cost, qty, revenue,
        )
        has_cost = cost_present and not invalid_cost
        dish_cost = candidate_cost if has_cost else None
        store = per_store.setdefault(row["store_id"], {
            "store_id": row["store_id"],
            "name": row["store_name"],
            "revenue": 0.0,
            "qty": 0.0,
            "revenue_with_cost": 0.0,
            "cost": 0.0,
            "bills": 0,
            "dishes": 0,
            "dishes_with_cost": 0,
            "invalid_cost_dishes": 0,
        })
        store["revenue"] += revenue
        store["qty"] += qty
        store["dishes"] += 1
        if invalid_cost:
            store["invalid_cost_dishes"] += 1
        if has_cost and dish_cost is not None:
            store["revenue_with_cost"] += revenue
            store["cost"] += dish_cost * qty
            store["dishes_with_cost"] += 1

    for store in per_store.values():
        if bill_count_by_store is not None:
            store["bills"] = int(bill_count_by_store.get(store["store_id"], 0))
        else:
            # A safe lower bound for unit fixtures/legacy callers; production
            # passes an exact distinct bill count from a store-level query.
            store["bills"] = max(
                (int(row["bills"] or 0) for row in store_dish_rows
                 if row["store_id"] == store["store_id"]),
                default=0,
            )
        covered_revenue = store["revenue_with_cost"]
        store["cost_coverage_ratio"] = (
            covered_revenue / store["revenue"] if store["revenue"] > 0 else 0.0
        )
        if covered_revenue > 0:
            store["gross_profit"] = covered_revenue - store["cost"]
            store["margin_rate"] = store["gross_profit"] / covered_revenue
        else:
            store["gross_profit"] = None
            store["margin_rate"] = None
    return list(per_store.values())


def _parse_margin_reference_lines(query: Optional[str]) -> List[Dict[str, float]]:
    """Extract explicit target/warning percentages; never invent defaults."""
    text = query or ""

    def _find(label_pattern: str) -> Optional[float]:
        patterns = (
            rf"(\d+(?:\.\d+)?)\s*%?\s*(?:的)?(?:{label_pattern})(?:值|线|参照线)?",
            rf"(?:{label_pattern})(?:值|线|参照线)?\s*(?:为|是|设为|设置为)?\s*(\d+(?:\.\d+)?)\s*%",
        )
        for pattern in patterns:
            match = re.search(pattern, text)
            if match:
                value = float(match.group(1))
                if 0 <= value <= 100:
                    return value
        return None

    result: List[Dict[str, float]] = []
    target = _find("计划|目标")
    warning = _find("预警|警戒")
    if target is not None:
        result.append({"name": "计划值", "yAxis": target})
    if warning is not None:
        result.append({"name": "预警值", "yAxis": warning})
    return result


def _parse_revenue_reference_lines(query: Optional[str]) -> List[Dict[str, float]]:
    """Extract explicit monetary target/warning lines; never add defaults."""
    text = query or ""

    def _find(label_pattern: str) -> Optional[float]:
        patterns = (
            rf"(?:{label_pattern})(?:值|线|参照线)?\s*(?:为|是|设为|设置为)?\s*"
            rf"(?:¥|￥)?\s*(\d+(?:\.\d+)?)\s*(万|千)?元?",
            rf"(?:¥|￥)?\s*(\d+(?:\.\d+)?)\s*(万|千)?元?\s*"
            rf"(?:的)?(?:{label_pattern})(?:值|线|参照线)?",
        )
        for pattern in patterns:
            match = re.search(pattern, text)
            if not match:
                continue
            value = float(match.group(1))
            unit = match.group(2)
            if unit == "万":
                value *= 10000
            elif unit == "千":
                value *= 1000
            if 0 <= value <= 1_000_000_000:
                return value
        return None

    result: List[Dict[str, float]] = []
    target = _find("计划|目标")
    warning = _find("预警|警戒")
    if target is not None:
        result.append({"name": "计划值", "yAxis": target})
    if warning is not None:
        result.append({"name": "预警值", "yAxis": warning})
    return result


def _quadratic_least_squares(
    values: List[float],
) -> Optional[Tuple[float, float, float, float, List[float]]]:
    """Fit y=a*x²+b*x+c with a tiny dependency-free 3×3 solver."""
    if len(values) < 3 or any(not math.isfinite(value) for value in values):
        return None
    xs = [float(index) for index in range(len(values))]
    sx = sum(xs)
    sx2 = sum(x * x for x in xs)
    sx3 = sum(x * x * x for x in xs)
    sx4 = sum(x * x * x * x for x in xs)
    sy = sum(values)
    sxy = sum(x * y for x, y in zip(xs, values))
    sx2y = sum(x * x * y for x, y in zip(xs, values))
    matrix = [
        [sx4, sx3, sx2, sx2y],
        [sx3, sx2, sx, sxy],
        [sx2, sx, float(len(values)), sy],
    ]
    for column in range(3):
        pivot = max(range(column, 3), key=lambda row: abs(matrix[row][column]))
        if math.isclose(matrix[pivot][column], 0.0, abs_tol=1e-12):
            return None
        if pivot != column:
            matrix[column], matrix[pivot] = matrix[pivot], matrix[column]
        divisor = matrix[column][column]
        matrix[column] = [value / divisor for value in matrix[column]]
        for row in range(3):
            if row == column:
                continue
            factor = matrix[row][column]
            matrix[row] = [
                current - factor * reference
                for current, reference in zip(matrix[row], matrix[column])
            ]
    a, b, c = (matrix[row][3] for row in range(3))
    fitted = [a * x * x + b * x + c for x in xs]
    mean = sy / len(values)
    ss_total = sum((value - mean) ** 2 for value in values)
    ss_residual = sum(
        (value - estimate) ** 2
        for value, estimate in zip(values, fitted)
    )
    r_squared = (
        1.0 - ss_residual / ss_total
        if not math.isclose(ss_total, 0.0, abs_tol=1e-12)
        else 1.0
    )
    return a, b, c, r_squared, fitted


_CN_SMALL_NUMBERS = {
    "一": 1,
    "二": 2,
    "两": 2,
    "俩": 2,   # 口语: "俩月" (2026-07-08 时间词汇加硬)
    "三": 3,
    "仨": 3,   # 口语: "仨月"
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
    "十": 10,
}


def _parse_small_count(raw: Optional[str], default: int = 1) -> int:
    value = (raw or "").strip()
    if not value:
        return default
    if value.isdigit():
        return int(value)
    if value == "十":
        return 10
    if value.startswith("十") and len(value) == 2:
        return 10 + _CN_SMALL_NUMBERS.get(value[1], 0)
    if value.endswith("十") and len(value) == 2:
        return _CN_SMALL_NUMBERS.get(value[0], default) * 10
    if "十" in value and len(value) == 3:
        left, right = value.split("十", 1)
        return _CN_SMALL_NUMBERS.get(left, default) * 10 + _CN_SMALL_NUMBERS.get(right, 0)
    return _CN_SMALL_NUMBERS.get(value, default)


def _relative_period_match(text: str) -> Optional[Tuple[int, str]]:
    match = re.search(r"(?:最近|近|过去)\s*([0-9一二两三四五六七八九十俩仨]{0,4})\s*个?\s*(天|日|周|星期|月|年)", text)
    if match is None:
        # "这两个月" / "这3周" style: 这 + explicit numeral + unit. The numeral
        # is REQUIRED here ({1,4}, not {0,4}) so bare "这个月" / "这周" keep
        # falling through to the named-window branches in
        # _resolve_sales_date_range (本月 / 本周) instead of becoming a
        # rolling window.
        match = re.search(r"这\s*([0-9一二两三四五六七八九十俩仨]{1,4})\s*个?\s*(天|日|周|星期|月|年)", text)
    if not match:
        return None
    count = max(1, _parse_small_count(match.group(1), default=1))
    return count, match.group(2)


def _profit_intent(query: Optional[str]) -> Tuple[bool, bool]:
    text = query or ""
    asks_profitability = any(token in text for token in (
        "赚钱吗", "赚钱了吗", "赚不赚", "赚了", "挣钱吗", "挣钱了吗", "盈利吗", "盈利了吗",
        "亏钱吗", "亏钱了吗", "亏了吗", "亏损了吗", "亏损吗", "亏本吗", "赔钱吗", "赔钱了吗", "亏不亏", "是否赚钱", "是否盈利", "是否亏损",
        # Colloquial split forms ("挣着钱没" does NOT contain the substring
        # "挣钱", so the tokens above miss it — live-caught 2026-07-07):
        "挣着钱", "赚着钱", "挣到钱", "赚到钱", "挣钱没", "赚钱没",
        "有没有赚", "有没有挣", "有没有盈利",
    ))
    wants_margin = asks_profitability or any(token in text for token in (
        "毛利", "毛利润", "毛利率", "利润", "盈利", "赚钱", "挣钱", "净赚", "亏钱",
    ))
    return wants_margin, asks_profitability


def _resolve_sales_date_range(
    query: Optional[str],
    *,
    today: Optional[date] = None,
) -> Tuple[Tuple[Optional[date], Optional[date]], str]:
    """Resolve common owner-facing time phrases for sales summary prompts."""
    text = (query or "").strip()
    anchor = today or date.today()

    # 显式日期区间是最具体的时间信号, 先于一切相对短语判定。
    # (spec 持续项「精确日期区间」; 解不出/非法一律返回 None 由后续分支接管。)
    absolute = parse_absolute_date_range(text, today=anchor)
    if absolute is not None:
        start, end, _matched = absolute
        # 🔴 2026-08-15 (T6(e)): 本函数是**历史数据**解析器 —— 它的每一条相对
        #    分支的右端点都不超过 anchor(结构上不可能越界), 唯独这条绝对区间
        #    分支例外: `parse_absolute_date_range` 只拦 start>end 与「无年份且
        #    落未来」, ⛔ 不拦 end > 今天。于是「8月1号到8月31号」在 8-15 当天
        #    会带回 16 天还没发生的日期, 下游按窗口求和 ⇒ 分母里混进未来。
        #
        # ⛔ 例外(必须留): **预测类查询的窗口本来就在未来** —— 少说这半句它上线
        #    第一天就会打在预测上。本断言只作用于历史查询, 而作用域是**结构性**
        #    的: 预测排班那条路(`restaurant_ops_router` 8200-8760)根本不调本函数,
        #    未来时间词也在下面被 `_FUTURE_WINDOW_LABEL` 单独接走。
        if end > anchor:
            logger.warning(
                "[time-window] 绝对区间右端点在未来, 已截到今天: "
                "%s~%s -> %s~%s (anchor=%s, text=%r)",
                start, end, start, anchor, anchor, text,
            )
            end = anchor
        # 标签用「指定区间」而不是日期串本身: 渲染层会在标签后再补一次具体
        # 日期 (相对时间是「本月（2026-07-01 至 2026-07-27）」), 标签若也写成
        # 日期就会渲染出「X（X）」的重复。契约校验只要求答案里含该标签, 满足。
        return (start, end), "指定区间"

    # 🔴 2026-08-16 B-4: 「**今天**到现在」= 今天, ⛔ 不是开业至今。
    #
    # 客户原话:「中午也会问一次…把**今天到中午的目前的所有信息**整理出来」
    # (owner 口述转录, ⛔ 非逐字稿)。
    # 实测原来: '今天到现在的经营情况' -> (2000-01-01, 今天) 标「截至目前」
    #          = **开业至今**, 一个完全不同的问题, 而且不吭声。
    #
    # ⚠️ 判别是「今天」出现在「到/截至」**之前**:
    #      「今天到现在」  -> 今天      (今天 在前, 是在给范围定界)
    #      「到今天为止」  -> 截至目前  (今天 在后, 是累计的右端点)
    #      「截至今天」    -> 截至目前  (同上)
    #    ⛔ 不能简单地「含今天就当今天」—— 那会把真·累计问句也吞掉。
    if _TODAY_SO_FAR_RE.search(text):
        return (anchor, anchor), "今天"

    # “截至目前/到现在” is an explicit cumulative range, not a missing time
    # slot.  A concrete lower bound keeps every downstream resolver on the
    # same all-history-to-today scope instead of letting store-margin silently
    # fall back to its rolling 30-day default.
    if any(token in text for token in (
        "截至目前", "截止目前", "到目前", "截至现在", "截止现在",
        "到现在", "目前为止", "当前为止", "到今天为止", "截至今天",
        "截止今天", "开业至今", "至今累计", "当前累计", "累计到现在",
    )):
        return (date(2000, 1, 1), anchor), "截至目前"

    # Calendar-day phrases are resolved before rolling ranges.  They used to
    # fall through to "全部历史", which made questions such as "昨天营业额比前天
    # 高还是低" look successful while answering a completely different scope.
    if (
        any(token in text for token in ("昨天", "昨日"))
        and not any(token in text for token in ("今天", "今日"))
    ):
        target = anchor - timedelta(days=1)
        return (target, target), "昨天"
    if any(token in text for token in ("前天", "前日")):
        target = anchor - timedelta(days=2)
        return (target, target), "前天"

    # 绝对日历月 ("3月份"/"2026年3月"/"去年12月") — 此前落全部历史,
    # "去年12月" 还被 去年 规则吞成去年全年 (R23 实测)。年份推断: 显式年 >
    # 去年/今年前缀 > 就近过去原则 (月份大于当前月 → 去年)。
    abs_month = re.search(
        r"(?:(?P<year>20\d{2})\s*年|(?P<rel>去年|今年))?\s*"
        r"(?P<month>1[0-2]|0?[1-9]|[一二三四五六七八九十]|十一|十二)\s*月",
        text,
    )
    if abs_month and "个月" not in abs_month.group(0):
        _cn_month = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6,
                     "七": 7, "八": 8, "九": 9, "十": 10, "十一": 11, "十二": 12}
        raw_month = abs_month.group("month")
        month_num = _cn_month.get(raw_month) or int(raw_month)
        if abs_month.group("year"):
            year_num = int(abs_month.group("year"))
        elif abs_month.group("rel") == "去年":
            year_num = anchor.year - 1
        elif abs_month.group("rel") == "今年":
            year_num = anchor.year
        else:
            year_num = anchor.year if month_num <= anchor.month else anchor.year - 1
        month_start = date(year_num, month_num, 1)
        month_end = (
            date(year_num + 1, 1, 1) - timedelta(days=1)
            if month_num == 12 else date(year_num, month_num + 1, 1) - timedelta(days=1)
        )
        if month_start <= anchor:
            return (month_start, min(month_end, anchor)), f"{year_num}年{month_num}月"

    relative_match = _relative_period_match(text)
    if relative_match:
        count, unit = relative_match
        if unit in ("天", "日"):
            days = max(1, min(count, 365))
            return (anchor - timedelta(days=days - 1), anchor), f"最近{days}天"
        if unit in ("周", "星期"):
            days = max(1, min(count * 7, 365))
            return (anchor - timedelta(days=days - 1), anchor), f"最近{count}周"
        if unit == "月":
            days = max(1, min(count * 30, 365))
            label = "最近30天" if count == 1 else f"最近{count}个月"
            return (anchor - timedelta(days=days - 1), anchor), label
        if unit == "年":
            # Sheet 7/22 实体检测缺口: "过去一年" 此前落到全部历史 (19个月),
            # 属于"用其他日期替代"。滚动一年窗, 上限两年 (demo 数据边界)。
            days = max(365, min(count * 365, 730))
            label = "最近一年" if count == 1 else f"最近{count}年"
            return (anchor - timedelta(days=days - 1), anchor), label

    # 半年 = 滚动 ~183 天 (2026-07-08 时间词汇加硬)。
    if "半年" in text and "上半年" not in text and "下半年" not in text:
        return (anchor - timedelta(days=182), anchor), "最近半年"

    # 🔴 2026-08-16 B-0: 上半年/下半年 = **日历半年**, 直接算出来。
    #
    # 原来这里写的是「不在此猜测 —— 落到全部历史, 诚实回退好过错窗口」。
    # ⚠️ 但落到全部历史**不是回退, 是回答了另一个问题** —— 而且不吭声。
    #    仓里自己的规则(T6②, 06a70f9a8d)就是「静默换窗口必须披露」。
    #    日历半年没有歧义(1-1~6-30 / 7-1~12-31), ⛔ 没有什么要猜的。
    #
    # ⚠️ 实测原来的两个坏法:
    #      '上半年营业额怎么样' -> (None,None) 全部历史   ← 换成了另一个问题
    #      '今年上半年营收'     -> 1/1~8/16 标「今年」    ← **比问的更宽**, 标签也不对
    if "上半年" in text or "下半年" in text:
        year_offset = -1 if "去年" in text else (-2 if "前年" in text else 0)
        year_num = anchor.year + year_offset
        prefix = {0: "", -1: "去年", -2: "前年"}[year_offset]
        if "上半年" in text:
            start, end, label = date(year_num, 1, 1), date(year_num, 6, 30), f"{prefix}上半年"
        else:
            start, end, label = date(year_num, 7, 1), date(year_num, 12, 31), f"{prefix}下半年"
        # ⛔ 历史窗口右端点不许越今天(T6④)。整段都在未来时不认, 落回后面的分支。
        if start <= anchor:
            return (start, min(end, anchor)), label

    # 命名窗口优先级 (2026-07-08 audit fix A-1): 周/月窗口检查必须在 "今天"
    # 之前 —— 老板问句常见形态是「<周/月窗口>怎么样，今天先做什么」，行动子句
    # 里的 "今天" 不是数据窗口。实测被劫持的验收原句:
    # "这周营收比上周差在哪里，今天先做哪几个动作" → 数据窗口应为 本周。
    # 纯 "今天营业额" (无周/月 token) 仍落到最后的 今天 分支不受影响。
    # 上周/上个月 AFTER 本周/本月: "这周营收比上周差在哪里" keeps 本周 as the
    # primary window (the comparison target is the resolver's business).
    # R26: 上上周/上上个月 单独点名 → 该周期为主窗; 同时点名两个周期
    # ("上个月和上上个月对比") 仍走比较分支 (主窗=近端周期)。
    if any(token in text for token in ("上上周", "上上星期", "上上个星期")):
        stripped = text.replace("上上周", "").replace("上上星期", "").replace("上上个星期", "")
        if not any(t in stripped for t in ("上周", "上星期", "上个星期", "本周", "这周")):
            this_monday = anchor - timedelta(days=anchor.weekday())
            return (this_monday - timedelta(days=14), this_monday - timedelta(days=8)), "上上周"
    if any(token in text for token in ("上上个月", "上上月")):
        stripped = text.replace("上上个月", "").replace("上上月", "")
        if not any(t in stripped for t in ("上个月", "上月", "本月", "这个月")):
            start, end = _previous_calendar_month(anchor, 2)
            return (start, end), "上上个月"

    if any(
        token in text
        for token in ("本周", "这周", "本星期", "这星期", "这个星期")
    ):
        return (anchor - timedelta(days=anchor.weekday()), anchor), "本周"

    if any(token in text for token in ("上周", "上星期", "上个星期")):
        this_monday = anchor - timedelta(days=anchor.weekday())
        return (this_monday - timedelta(days=7), this_monday - timedelta(days=1)), "上周"

    if any(token in text for token in ("本月", "这个月")):
        return (anchor.replace(day=1), anchor), "本月"

    if any(token in text for token in ("上个月", "上月")):
        last_of_prev = anchor.replace(day=1) - timedelta(days=1)
        return (last_of_prev.replace(day=1), last_of_prev), "上个月"

    # 季度 (2026-08-15 加)。⛔ 放在 本月/上个月 **之后**: 两个周期同时出现时
    # ("上个季度和上个月对比") 主窗取**近端**周期, 与上面 R26 的既有约定一致。
    #
    # 🔴 为什么必须由代码算: 在此之前「上个季度」整条链路都表达不了 ——
    #    T3 词表只有 today/this_week/this_month, resolver 无季度分支, 于是模型
    #    只能降级成 relative/month/count=3。实测 (MOCK_REST, 今天 2026-08-15)
    #    5/5 都吐 {'type':'relative','unit':'month','count':3} → 「最近3个月」
    #    → (2026-05-18, 2026-08-15), 而正确答案是 (2026-04-01, 2026-06-30)。
    #    **代码算得没错, 错在喂给代码的那个词已经被换过了。**
    if "季度" in text:
        q_index = (anchor.month - 1) // 3            # 0..3
        if any(token in text for token in ("上个季度", "上季度", "上一季度")):
            year, prev_q = (anchor.year - 1, 3) if q_index == 0 else (anchor.year, q_index - 1)
            start = date(year, prev_q * 3 + 1, 1)
            end = date(year + 1, 1, 1) - timedelta(days=1) if prev_q == 3 \
                else date(year, prev_q * 3 + 4, 1) - timedelta(days=1)
            return (start, end), "上个季度"
        if any(token in text for token in ("本季度", "这个季度", "当季", "这季度")):
            # 与 本月/今年 同规则: 进行中的周期右端点是 anchor, ⛔ 不是季度末
            # (那会把未来日期算进窗口)。
            return (date(anchor.year, q_index * 3 + 1, 1), anchor), "本季度"

    # R26: 显式日历年 ("2025年全年营收") — 此前落全部历史,
    # "2025年全年" 还被菜名抽取当候选。带月的形态由前面的绝对月规则接走。
    year_only = re.search(r"(20\d{2})\s*年(?:全年|度)?", text)
    if year_only:
        y = int(year_only.group(1))
        if y <= anchor.year:
            y_end = anchor if y == anchor.year else date(y, 12, 31)
            return (date(y, 1, 1), y_end), f"{y}年"
    if "前年" in text:
        return (date(anchor.year - 2, 1, 1), date(anchor.year - 2, 12, 31)), "前年"
    if "今年" in text:
        return (date(anchor.year, 1, 1), anchor), "今年"
    if "去年" in text:
        return (date(anchor.year - 1, 1, 1), date(anchor.year - 1, 12, 31)), "去年"

    if any(token in text for token in ("今天", "今日")):
        return (anchor, anchor), "今天"

    # Future-only phrasing must never fall through to 全部历史 — answering a
    # question about tomorrow with historical totals is silent date
    # substitution. Checked last so action clauses alongside a real window
    # ("本周营收，下周目标怎么定") keep the historical window above.
    if any(token in text for token in (
        "明天", "明日", "后天", "下周", "下星期", "下个月", "下月", "明年", "未来",
    )):
        return (None, None), _FUTURE_WINDOW_LABEL

    return (None, None), "全部历史"


def _previous_calendar_month(anchor: date, months_back: int = 1) -> Tuple[date, date]:
    """Return a complete calendar month before ``anchor`` without dateutil."""
    first_this_month = anchor.replace(day=1)
    end = first_this_month - timedelta(days=1)
    for _ in range(max(0, months_back - 1)):
        end = end.replace(day=1) - timedelta(days=1)
    return end.replace(day=1), end


def _resolve_sales_comparison(
    query: Optional[str],
    *,
    anchor: date,
    primary_range: Tuple[Optional[date], Optional[date]],
    primary_label: str,
) -> Tuple[Tuple[Optional[date], Optional[date]], Optional[str], Optional[str]]:
    """Resolve an explicitly requested comparison period.

    The resolver only creates a baseline when the wording actually asks for a
    comparison.  It never substitutes all history or a convenient rolling
    window for an unavailable baseline.
    """
    text = (query or "").strip()
    compare_signal = any(token in text for token in (
        "对比", "比较", "相比", "比前", "比上", "和前", "和上", "与前", "与上",
        "较前", "较上", "环比",
    ))

    if (
        any(token in text for token in ("昨天", "昨日"))
        and any(token in text for token in ("前天", "前日", "前一天", "前一日"))
    ):
        target = anchor - timedelta(days=2)
        return (target, target), "前天", "previous_day"

    if (
        any(token in text for token in ("今天", "今日"))
        and any(token in text for token in ("昨天", "昨日"))
    ):
        target = anchor - timedelta(days=1)
        return (target, target), "昨天", "previous_day"

    if any(token in text for token in ("上上周", "上上星期", "上上个星期")):
        this_monday = anchor - timedelta(days=anchor.weekday())
        start = this_monday - timedelta(days=14)
        end = this_monday - timedelta(days=8)
        return (start, end), "上上周", "previous_week"

    # 双周期同时点名 ("今年比去年增长多少") 本身就是比较信号 — compare_signal
    # 词表只有 对比/相比 等双字词, 接不住「比去年」(R15c)。
    if "今年" in text and "去年" in text:
        return (
            (date(anchor.year - 1, 1, 1), date(anchor.year - 1, 12, 31)),
            "去年",
            "previous_year",
        )

    if "上上个月" in text or "上上月" in text:
        start, end = _previous_calendar_month(anchor, 2)
        return (start, end), "上上个月", "previous_month"

    if (
        any(token in text for token in ("本月", "这个月", "当月"))
        and any(token in text for token in ("上个月", "上月"))
    ):
        start, end = _previous_calendar_month(anchor, 1)
        return (start, end), "上个月", "previous_month"

    if (
        any(
            token in text
            for token in ("本周", "这周", "本星期", "这星期", "这个星期")
        )
        and any(token in text for token in ("上周", "上星期", "上个星期"))
    ):
        this_monday = anchor - timedelta(days=anchor.weekday())
        return (
            this_monday - timedelta(days=7),
            this_monday - timedelta(days=1),
        ), "上周", "previous_week"

    if compare_signal and any(token in text for token in ("上个月", "上月")):
        # "营收和上个月比" has an implicit current-month primary period.
        # Preserve an explicitly parsed primary; otherwise make that implicit
        # period explicit and auditable in _resolve_sales_query_spec below.
        start, end = _previous_calendar_month(anchor, 1)
        return (start, end), "上个月", "previous_month"

    if compare_signal and primary_range[0] is not None and primary_range[1] is not None:
        span = (primary_range[1] - primary_range[0]).days + 1
        end = primary_range[0] - timedelta(days=1)
        return (end - timedelta(days=span - 1), end), "上一同长度周期", "previous_period"

    return (None, None), None, None


def _uses_relative_sales_window(query: Optional[str]) -> bool:
    # Relative and named calendar windows must be delegated to the Python
    # resolver so the requested scope and any comparison baseline remain one
    # auditable contract instead of being split across Java and Python.
    text = (query or "").strip()
    return bool(
        _relative_period_match(text)
        # ⚠️ 时间词走**共用的** `_CALENDAR_PERIOD_TOKENS`(菜名抽取的排除表读同一份),
        #    比较词/「最近」留在这里 —— 它们不是时间词, ⛔ 不该混进那个集合。
        or any(token in text for token in _CALENDAR_PERIOD_TOKENS)
        or any(token in text for token in ("对比", "比较", "相比", "环比", "最近"))
    )


def _resolve_sales_query_spec(query: Optional[str], *, today: Optional[date] = None) -> SalesQuerySpec:
    anchor = today or date.today()
    date_range, window_label = _resolve_sales_date_range(query, today=anchor)
    comparison_range, comparison_label, comparison_kind = _resolve_sales_comparison(
        query,
        anchor=anchor,
        primary_range=date_range,
        primary_label=window_label,
    )
    # "营收和上个月比" omits the current-month wording, but comparison grammar
    # makes the primary period unambiguous.  Do not compare all history against
    # one month.
    if comparison_label == "上个月" and window_label == "上个月" and not any(
        token in (query or "") for token in ("本月", "这个月", "当月", "上上个月", "上上月")
    ):
        date_range, window_label = (anchor.replace(day=1), anchor), "本月"
    primary_start, primary_end = date_range
    baseline_start, baseline_end = comparison_range
    if (
        comparison_kind == "previous_month"
        and primary_start == anchor.replace(day=1)
        and primary_end == anchor
        and baseline_start is not None
        and baseline_end is not None
    ):
        next_month = (
            date(anchor.year + 1, 1, 1)
            if anchor.month == 12
            else date(anchor.year, anchor.month + 1, 1)
        )
        current_month_end = next_month - timedelta(days=1)
        if anchor < current_month_end:
            elapsed_days = (anchor - primary_start).days
            comparison_range = (
                baseline_start,
                min(
                    baseline_end,
                    baseline_start + timedelta(days=elapsed_days),
                ),
            )
            comparison_label = f"{comparison_label}同期"
    elif (
        comparison_kind == "previous_week"
        and primary_start == anchor - timedelta(days=anchor.weekday())
        and primary_end == anchor
        and anchor.weekday() < 6
        and baseline_start is not None
        and baseline_end is not None
    ):
        comparison_range = (
            baseline_start,
            min(
                baseline_end,
                baseline_start + timedelta(days=anchor.weekday()),
            ),
        )
        comparison_label = f"{comparison_label}同期"
    elif (
        comparison_kind == "previous_year"
        and primary_start == date(anchor.year, 1, 1)
        and primary_end == anchor
        and anchor < date(anchor.year, 12, 31)
        and baseline_start is not None
        and baseline_end is not None
    ):
        elapsed_days = (anchor - primary_start).days
        comparison_range = (
            baseline_start,
            min(
                baseline_end,
                baseline_start + timedelta(days=elapsed_days),
            ),
        )
        comparison_label = f"{comparison_label}同期"
    wants_margin, asks_profitability = _profit_intent(query)
    return SalesQuerySpec(
        date_range=date_range,
        window_label=window_label,
        comparison_range=comparison_range,
        comparison_label=comparison_label,
        comparison_kind=comparison_kind,
        wants_margin=wants_margin,
        asks_profitability=asks_profitability,
        relative_window=_uses_relative_sales_window(query),
    )


# Money wording that makes a wastage ranking a *cost* ranking rather than a
# quantity one. Both axes exist per ingredient in Gold (wastage_qty /
# wastage_cost); picking the wrong one silently names the wrong ingredient,
# because the cheap high-volume item and the expensive low-volume item are
# different rows (300kg of 土豆 vs 12kg of 三文鱼).
_WASTAGE_COST_AXIS_TOKENS = (
    "金额", "成本", "钱", "损失多少", "花了多少", "价值", "元",
)

_WASTAGE_RANK_AXES = {
    # axis -> (ORDER BY expression, label, whether the axis is money)
    "cost": ("cost", "按金额", True),
    "qty": ("qty", "按数量", False),
}


def _wastage_rank_axis(query: str) -> str:
    """Which axis the question asked to rank by. Defaults to quantity."""
    if query and any(tok in query for tok in _WASTAGE_COST_AXIS_TOKENS):
        return "cost"
    return "qty"


async def resolve_wastage_top(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
    query: str = "",
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
) -> OpsAnswer:
    """Top N wastage ingredients + wastage type breakdown for the asked window.

    Ranks by cost when the question asks about money, otherwise by quantity.
    Both numbers are always shown so the ranking is self-explanatory.

    ``date_range`` must be declared here even though the SQL could be written
    against ``days`` alone: ``resolve_by_code`` filters kwargs down to each
    resolver's signature, so a parameter that is not declared is dropped
    **silently**. That is exactly how this resolver spent its life answering a
    rolling window — the planner computed 2026-06-01..2026-06-30 for 「上个月」,
    ``_resolver_kwargs`` reduced it to ``days=30``, and the range never arrived.
    The answer then read 「近 30 天」 over July numbers while the user had asked
    about June. Callers that pass only ``days`` keep the rolling behaviour.
    """
    rank_axis = _wastage_rank_axis(query)
    window_start, window_end, window_text = _explicit_window(
        date_range, window_label, days,
    )
    order_expr, axis_label, axis_is_money = _WASTAGE_RANK_AXES[rank_axis]
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        # Both KPI kinds come from the same APPROVED wastage rows with the same
        # GROUP BY, so conditional aggregation cannot change which ingredients
        # appear -- only the order. order_expr is from _WASTAGE_RANK_AXES, never
        # from user text.
        top_rows = await conn.fetch(
            f"""
            SELECT i.name, i.category, i.unit,
                   SUM(CASE WHEN a.kpi_kind = 'wastage_qty'
                            THEN a.value_num ELSE 0 END)::float AS qty,
                   SUM(CASE WHEN a.kpi_kind = 'wastage_cost'
                            THEN a.value_num ELSE 0 END)::float AS cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1 AND a.kpi_kind IN ('wastage_qty', 'wastage_cost')
               AND a.date >= COALESCE($4::date, CURRENT_DATE - ($2::int))
               AND ($5::date IS NULL OR a.date <= $5::date)
             GROUP BY i.name, i.category, i.unit
             ORDER BY {order_expr} DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n, window_start, window_end,
        )
        type_rows = await conn.fetch(
            """
            SELECT a.dim_value_str AS type, SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
             WHERE a.factory_id = $1 AND a.kpi_kind = 'wastage_cost_by_type'
               AND a.date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
               AND ($4::date IS NULL OR a.date <= $4::date)
             GROUP BY a.dim_value_str
             ORDER BY cost DESC NULLS LAST
            """,
            factory_id, days, window_start, window_end,
        )
        total = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(wastage_qty_total), 0)::float AS total_qty,
                   COALESCE(SUM(wastage_cost_total), 0)::float AS total_cost,
                   COALESCE(SUM(wastage_count), 0)::int AS total_count
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1
               AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
               AND ($4::date IS NULL OR date <= $4::date)
            """,
            factory_id, days, window_start, window_end,
        )
        # 窗口内一条都没有时, 「最后一次录损耗是哪天」是老板唯一能据以行动的事实。
        # ⛔ 用**同一张**聚合表, 不换口径去问 `fact_restaurant_wastage` ——
        #    上面三条读数都来自 agg 层, 混着问会得出「明明有却说没有」这种自相矛盾。
        # ⚠️ **只在空窗口时才查**: 常规路径(绝大多数)不必多付一次往返,
        #    而且这样既有的假连接不会因为多一个方法名而集体失效。
        window_has_nothing = (
            not top_rows
            and not type_rows
            and int(total["total_count"] or 0) == 0
        )
        last_wastage_date = await conn.fetchval(
            """
            SELECT max(date) FROM agg_restaurant_daily_totals
             WHERE factory_id = $1 AND COALESCE(wastage_count, 0) > 0
            """,
            factory_id,
        ) if window_has_nothing else None

    type_name_map = {
        "EXPIRED": "过期", "DAMAGED": "破损", "SPOILED": "变质",
        "PROCESSING": "加工损耗", "OTHER": "其他",
    }
    # The per-ingredient money KPI only exists after materialize_gold_daily_ops
    # has run for this tenant. Before that every row reads 0.00, and a cost
    # ranking would be a confident ordering of zeros. The totals table is
    # computed straight from Silver, so real money there plus an all-zero
    # breakdown here pinpoints the gap -- say so instead of ranking zeros.
    cost_axis_unavailable = bool(
        axis_is_money
        and top_rows
        and not any(float(r["cost"] or 0.0) for r in top_rows)
        and float(total["total_cost"] or 0.0) > 0
    )

    # 2026-08-11: 编号列表改 markdown 表格 —— 排行本来就是表格数据, 列表形态
    # 让人没法竖着比数。全站唯一拼装点 `_markdown_table`。
    # ⚠️ 原 `_row_metrics` 把两个轴揉进**一个**单元格(「¥123（4.5 kg）」), 表格里
    #    必须拆成两列, 否则右对齐和竖着比数都白做。定序的那个轴排在前一列 ——
    #    原实现「leading with the one that set the order」保的就是这个, 换成表格
    #    后由列序承担。
    qty_col, cost_col = "损耗量", "损耗金额"
    top_headers = ["#", "食材", "分类"] + (
        [cost_col, qty_col] if axis_is_money else [qty_col, cost_col]
    )
    top_table_rows = []
    for i, r in enumerate(top_rows, 1):
        qty_cell = f"{r['qty']:.2f} {r['unit'] or ''}".strip()
        cost_cell = f"¥{r['cost']:,.2f}"
        top_table_rows.append(
            [i, r["name"], r["category"] or "—"]
            + ([cost_cell, qty_cell] if axis_is_money else [qty_cell, cost_cell])
        )
    top_list_lines = (
        _markdown_table(top_headers, top_table_rows, right_align={3, 4})
        if top_rows else ["", f"({window_text}无损耗记录)"]
    )

    type_summary = "、".join([
        f"{type_name_map.get(r['type'], r['type'])} ¥{r['cost']:.2f}"
        for r in type_rows[:5]
    ]) or "无数据"

    # Cost wording is what the money question was asking for; keep the quantity
    # heading untouched so the historical (and far more common) qty answer text
    # does not drift.
    top_heading = (
        f"损耗成本前 {len(top_rows)} 名（{axis_label}）"
        if axis_is_money
        else f"损耗食材前 {len(top_rows)} 名（{axis_label}）"
    )
    totals_line = (
        f"- 总损耗 {total['total_count']} 次, 损耗成本 **¥{total['total_cost']:,.2f}**, "
        f"损耗量 {total['total_qty']:.2f} 单位"
        if axis_is_money
        else f"- 总损耗 {total['total_count']} 次, {total['total_qty']:.2f} 单位, "
             f"损失 **¥{total['total_cost']:.2f}**"
    )

    if cost_axis_unavailable:
        top_block = (
            "按食材的损耗成本明细**暂无**（该指标尚未生成），"
            "因此这里不给出食材金额排名，也不用数量排名顶替。"
            "上面的损耗总成本与类型分布来自完整台账，可以直接用。"
        )
    else:
        # `_markdown_table` 首元素是空串 —— join 之后恰好在标题与表格之间留出
        # 必需的空行(没有它 markdown-it 会把表格并进上一段当普通文字)。
        top_block = "\n".join([f"{top_heading}:"] + top_list_lines)

    # 🔴 2026-08-17 prod 实测(RES_3101_009): 窗口内零损耗时, 下面那三条建议照发 ——
    #    而第 1 条是「先把**损耗金额最高的类型**拆到门店和班次」, 那个类型**不存在**。
    #    三条全都预设有数据, 读起来像分析, 其实一个字都不成立。
    #    ⚠️ 本仓 anti-goal: 一条会误发的提示烧掉的是「这东西说的话能信」这件事本身。
    #
    # ⛔ 但**零损耗是合法状态** —— 不许断言「没人录」。摆事实, 让老板自己判断:
    #    实测该租户最后一条损耗是 2026-06-08(70 天前), 而 POS 数据到昨天。
    #    这两件事放在一起他一眼就知道该问谁; 由产品替他下结论反而会错。
    #
    # 与正上方 `cost_axis_unavailable` 是同一条纪律: **数出不来就说出不来,
    # ⛔ 不用别的东西顶替**。那条守的是「不拿数量排名顶金额排名」,
    # 这条守的是「不拿通用建议顶没有的数据」。
    if window_has_nothing:
        if last_wastage_date:
            gap_days = (date.today() - last_wastage_date).days
            next_step = (
                f"{window_text}一条损耗记录都没有。你们最后一次录损耗是 "
                f"{last_wastage_date:%Y-%m-%d}，到今天 {gap_days} 天。\n"
                f"这有两种可能：真的一件损耗都没发生，或者最近没人录。"
                f"先去后厨问一句是哪一种 —— 是后者的话补录之后我才能告诉你钱漏在哪。"
            )
        else:
            next_step = (
                f"{window_text}一条损耗记录都没有，而且这个账号从来没有过损耗记录。\n"
                f"损耗台账看起来还没开始录。要先有人录，我才能告诉你钱漏在哪；"
                f"在那之前这一项我给不了任何判断，也不会拿别的数据凑。"
            )
        answer = (
            f"{window_text}损耗总览:\n"
            f"{totals_line}\n\n"
            f"{next_step}"
        )
    else:
        answer = (
            f"{window_text}损耗总览:\n"
            f"{totals_line}\n"
            f"- 损耗类型分布: {type_summary}\n\n"
            f"{top_block}\n\n"
            f"建议动作:\n"
            f"1. 先把损耗金额最高的类型拆到门店和班次，确认是保存、加工还是报损登记问题。\n"
            f"2. 对损耗靠前的食材设一周复盘线，超过日均用量或报损阈值时要求后厨说明原因。\n"
            f"3. 对水产、肉类等高价值食材优先复核收货净重和分切标准，避免损耗被当成正常用料。"
        )

    charts = []
    if top_rows and not cost_axis_unavailable:
        charts.append({
            "chartType": "bar",
            "title": f"{window_text}{top_heading}",
            "xAxis": {"data": [r["name"] for r in top_rows]},
            "series": [{
                "name": "损耗成本" if axis_is_money else "损耗量",
                "type": "bar",
                "data": [r["cost"] if axis_is_money else r["qty"] for r in top_rows],
            }],
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
        title=f"{window_text}损耗分析",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "损耗次数", "value": total["total_count"], "rawValue": total["total_count"]},
            {"title": "损耗量", "value": f"{total['total_qty']:.1f}", "rawValue": total["total_qty"]},
            {"title": "损耗金额", "value": f"¥{total['total_cost']:.2f}", "rawValue": total["total_cost"]},
            {
                "title": "损耗成本最高食材" if axis_is_money else "损耗最多食材",
                "value": top_rows[0]["name"] if top_rows else "—",
                "rawValue": 0,
            },
        ],
        meta={
            "window_days": days,
            # The window actually queried, not the one requested: the daily
            # audit compares these against the asked range, which is the only
            # way a silent reversion to a rolling window shows up as a failure
            # rather than as a plausible-looking answer.
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "window_label": window_label,
            "top_n": top_n,
            "rank_axis": rank_axis,
            "cost_axis_unavailable": cost_axis_unavailable,
            "total_qty": float(total["total_qty"] or 0.0),
            "total_cost": float(total["total_cost"] or 0.0),
            "total_count": int(total["total_count"] or 0),
        },
    )


async def resolve_stock_shortage(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
) -> OpsAnswer:
    """Top N stocktaking shortage ingredients for the window that was asked for.

    ``date_range`` must be declared: undeclared kwargs are dropped **silently**
    by ``resolve_by_code``, which is how this resolver answered 「上个月盘点差异」
    with a window ending today — see `_explicit_window` and PR #2076.
    """
    window_start, window_end, window_text = _explicit_window(
        date_range, window_label, days,
    )
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(
            """
            -- 按**金额**排名: 数量不能跨食材比较, 更不能相加 (实测 DEMO_REST
            -- 是 41.45kg + 45.00L)。数量仍逐项带单位显示, 那里它是有意义的。
            SELECT i.name, i.category, i.unit,
                   SUM(a.value_num) FILTER (
                       WHERE a.kpi_kind = 'stocktaking_shortage_qty'
                   )::float AS shortage_qty,
                   SUM(a.value_num) FILTER (
                       WHERE a.kpi_kind = 'stocktaking_shortage_cost'
                   )::float AS shortage_cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1
               AND a.kpi_kind IN ('stocktaking_shortage_qty', 'stocktaking_shortage_cost')
               AND a.date >= COALESCE($4::date, CURRENT_DATE - ($2::int))
               AND ($5::date IS NULL OR a.date <= $5::date)
             GROUP BY i.name, i.category, i.unit
             HAVING SUM(a.value_num) FILTER (
                        WHERE a.kpi_kind = 'stocktaking_shortage_qty'
                    ) > 0
             ORDER BY shortage_cost DESC NULLS LAST, shortage_qty DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n, window_start, window_end,
        )
        total = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(stocktaking_shortage_total), 0)::float AS shortage,
                   COALESCE(SUM(stocktaking_surplus_total), 0)::float AS surplus,
                   COALESCE(SUM(stocktaking_shortage_cost), 0)::float AS shortage_cost,
                   COALESCE(SUM(stocktaking_surplus_cost), 0)::float AS surplus_cost,
                   COALESCE(SUM(stocktaking_count), 0)::int AS count
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1
               AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
               AND ($4::date IS NULL OR date <= $4::date)
            """,
            factory_id, days, window_start, window_end,
        )

    # 2026-08-11: 编号列表改 markdown 表格(全站唯一拼装点 `_markdown_table`)。
    # ⚠️ 原来把金额与数量揉进一个单元格「¥123.00（4.50 kg）」, 拆成两列;
    #    定序轴(金额, 标题里写着「按金额」)排前一列。
    top_lines = _markdown_table(
        ["#", "食材", "分类", "盘亏金额", "盘亏量"],
        [[i, r["name"], r["category"] or "—",
          f"¥{r['shortage_cost'] or 0:,.2f}",
          f"{r['shortage_qty'] or 0:.2f} {r['unit'] or ''}".strip()]
         for i, r in enumerate(rows, 1)],
        right_align={3, 4},
    ) if rows else ["", f"({window_text}无盘亏记录)"]
    # `_markdown_table` 首元素是空串 —— join 后恰好在标题与表格间留出必需的空行。
    top_block = "\n".join(
        [f"盘亏食材前 {len(rows)} 名(按金额):"] + top_lines)

    # 金额是唯一能跨食材相加的维度 —— 数量总计会把 kg 和 L 加到一起(实测
    # DEMO_REST 41.45kg + 45.00L), 所以总计只给金额, 数量只逐项给且必带单位。
    answer = (
        f"{window_text}盘点总览:\n"
        f"- 盘点 {total['count']} 次, 盘亏金额 **¥{total['shortage_cost']:.2f}**, "
        f"盘盈金额 ¥{total['surplus_cost']:.2f}\n\n"
        f"{top_block}\n\n"
        f"建议动作:\n"
        f"1. 对盘亏最高的食材先核查领料单、报损单和实际库存照片，找出未登记消耗。\n"
        f"2. 把连续盘亏食材纳入每日闭店抽盘，连续两天异常就回溯到班组和菜品。\n"
        f"3. 对调料类盘亏优先检查称量标准和容器换算，减少账实口径不一致。"
    )
    charts = []
    if rows:
        charts.append({
            "chartType": "bar",
            "title": f"{window_text}盘亏食材前 {len(rows)} 名",
            "xAxis": {"data": [r["name"] for r in rows]},
            "series": [{"name": "盘亏量", "type": "bar", "data": [r["shortage_qty"] for r in rows]}],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_STOCK_SHORTAGE",
        title=f"{window_text}盘点差异分析",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "盘点次数", "value": total["count"], "rawValue": total["count"]},
            {"title": "盘亏总量", "value": f"{total['shortage']:.1f}", "rawValue": total["shortage"]},
            {"title": "盘盈总量", "value": f"{total['surplus']:.1f}", "rawValue": total["surplus"]},
            {"title": "盘亏最多食材", "value": rows[0]["name"] if rows else "—", "rawValue": 0},
        ],
        meta={
            "window_days": days, "top_n": top_n,
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "window_label": window_label,
        },
    )


async def _resolve_food_cost_ratio(
    smartbi_pool,
    factory_id: str,
    *,
    days: int,
    date_range: Optional[Tuple[Optional[date], Optional[date]]],
    window_label: Optional[str],
) -> OpsAnswer:
    """Read a period food-cost ratio without substituting recipe snapshots."""
    window_start, window_end, window_text = _explicit_window(
        date_range, window_label, days,
    )
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        row = await conn.fetchrow(
            """
            WITH revenue AS (
                SELECT COALESCE(SUM(net_amount), 0)::float AS amount,
                       COUNT(*)::bigint AS transaction_count
                  FROM fact_pos_transaction
                 WHERE factory_id = $1
                   AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int - 1))
                   AND ($4::date IS NULL OR date <= $4::date)
            ),
            food_cost AS (
                SELECT SUM(f.amount)::float AS amount,
                       COUNT(*)::bigint AS line_count,
                       ARRAY_AGG(DISTINCT f.source_type ORDER BY f.source_type) AS source_types,
                       ARRAY_AGG(DISTINCT c.name ORDER BY c.name) AS categories
                  FROM fact_cost_line f
                  JOIN dim_cost_category c
                    ON c.category_id = f.category_id
                   AND c.factory_id = f.factory_id
                 WHERE f.factory_id = $1
                   AND f.date >= COALESCE($3::date, CURRENT_DATE - ($2::int - 1))
                   AND ($4::date IS NULL OR f.date <= $4::date)
                   AND c.cost_type = 'material'
                   AND (c.name LIKE '%食材%' OR c.name LIKE '%原料%')
            )
            SELECT revenue.amount AS revenue,
                   revenue.transaction_count,
                   food_cost.amount AS food_cost,
                   food_cost.line_count,
                   food_cost.source_types,
                   food_cost.categories
              FROM revenue CROSS JOIN food_cost
            """,
            factory_id, days, window_start, window_end,
        )

    payload = dict(row or {})
    revenue = float(payload.get("revenue") or 0)
    food_cost = (
        float(payload["food_cost"])
        if payload.get("food_cost") is not None
        else None
    )
    transaction_count = int(payload.get("transaction_count") or 0)
    line_count = int(payload.get("line_count") or 0)
    source_types = list(payload.get("source_types") or [])
    categories = list(payload.get("categories") or [])
    window_meta = {
        "window_start": _date_text(window_start) if window_start else None,
        "window_end": _date_text(window_end) if window_end else None,
        "window_label": window_label,
        "scope_matches_request": True,
    }

    if revenue <= 0:
        answer = (
            f"{window_text}全店食材成本率暂时无法计算：没有可用的 POS 净营收事实。"
            "本次不会用其他期间或单菜配方成本替代。"
        )
        meta = {**window_meta, "no_pos_data": True}
        kpis: List[Dict[str, Any]] = []
    elif food_cost is None or line_count == 0:
        answer = (
            f"{window_text}全店食材成本率暂时无法计算：已有 POS 净营收事实"
            f"（¥{revenue:,.2f}，{transaction_count:,} 笔），但没有同一期间的食材成本事实。"
            "本次不会用单菜配方成本榜、理论成本快照或 0 替代。可信的期间口径应先补齐"
            "“期初库存 + 本期采购 − 期末库存”或经核验的期间食材成本事实，再除以同口径净营收。"
        )
        meta = {
            **window_meta,
            "no_data": True,
            "food_cost_fact_missing": True,
            "transaction_count": transaction_count,
        }
        kpis = [
            {"title": "POS 净营收", "value": f"¥{revenue:,.2f}", "rawValue": revenue},
            {"title": "期间食材成本", "value": "暂无事实", "rawValue": None},
        ]
    else:
        ratio = food_cost / revenue * 100
        answer = (
            f"{window_text}已登记期间食材成本为 ¥{food_cost:,.2f}，POS 净营收为 "
            f"¥{revenue:,.2f}，食材成本占净营收 **{ratio:.2f}%**。\n\n"
            "这是“已登记期间食材成本 ÷ 同期 POS 净营收”的参考比率；只有成本事实已按"
            "“期初库存 + 本期采购 − 期末库存”核验时，才能作为可信的期间实际口径。"
            "它不是单菜真实毛利率，也不由当前单菜配方成本快照推算。"
        )
        meta = {
            **window_meta,
            "food_cost_fact_line_count": line_count,
            "food_cost_source_types": source_types,
            "food_cost_categories": categories,
            "transaction_count": transaction_count,
        }
        kpis = [
            {"title": "食材成本率", "value": f"{ratio:.2f}%", "rawValue": ratio},
            {"title": "期间食材成本", "value": f"¥{food_cost:,.2f}", "rawValue": food_cost},
            {"title": "POS 净营收", "value": f"¥{revenue:,.2f}", "rawValue": revenue},
        ]

    return OpsAnswer(
        code="RESTAURANT_OPS_RECIPE_COST",
        title=f"{window_text}全店食材成本率",
        answer_text=answer,
        charts=[],
        kpis=kpis,
        meta=meta,
    )


async def _resolve_missing_cost_cards(
    smartbi_pool, factory_id: str, *,
    days: int,
    date_range: Optional[Tuple[Optional[date], Optional[date]]],
    window_label: Optional[str],
) -> OpsAnswer:
    """**T2 按钮点下去的那个答案**: 当期卖过、但没有成本卡的菜, 按营收从高到低。

    🔴 owner 2026-08-14 裁定 2: 「补」是他去别处做的事, 我们只负责**说清补什么**。
       ⇒ 按钮发的是一个我们答得出来的问句, 而不是一个没有 handler 的动作
         (第一版的 `payload.kind = "fill_dishes"` 全仓没有任何消费者)。

    ⛔ **清单来自 `generic_executor._cost_gaps`, 与毛利答案里那句开价同一处定义。**
       另写一条 SQL 就是「同一件事两个定义」—— 那时「先补这 3 道」列的菜
       和这里列的菜可以不一样, 而两处都看着挺对。

    ⚠️ 这里**不新建 intent code**: 挂在既有的 `RESTAURANT_OPS_RECIPE_COST` 上
       (它本来就是「菜 × 成本」那一格), 路由靠关键词表里新增的一条模式。
    """
    from smartbi.gold.restaurant.generic_executor import (
        _cost_gaps, _dish_cost_facts,
    )
    from smartbi.gold.restaurant.restaurant_cost_mapping import cost_bridge_pairs

    window_start, window_end, window_text = _explicit_window(
        date_range, window_label, days,
    )
    # 🔴 `_explicit_window` 在没给显式区间时返回 **(None, None, "近 30 天")** ——
    #    别的 resolver 把 None 交给 SQL 里的
    #    `COALESCE($3::date, CURRENT_DATE - ($2::int - 1))` 去兜, 而
    #    `_dish_cost_facts` 要的是**具体日期**: `date >= NULL` 一行都不返回。
    #    ⚠️ 实测长相: T2 按钮点下去答「都有成本卡，没有需要补的」, 而同一屏的
    #       抬头正说着「4 个菜品缺少完整成本」—— **答案自己跟自己打架**。
    #    ⇒ 这里把窗口落成具体日期, ⛔ 不把 None 往下传。
    if window_end is None:
        window_end = date.today()
    if window_start is None:
        window_start = window_end - timedelta(days=max(1, days) - 1)
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)",
                           factory_id)
        bridge = await cost_bridge_pairs(conn, factory_id)
        facts = await _dish_cost_facts(
            conn, factory_id, (window_start, window_end), bridge)
    gaps = _cost_gaps(facts)
    # 菜名 → 运营库菜品 id。⚠️ 就是 `bridge` 那一对数组, ⛔ 不另取一次数 ——
    #    另取一次就是两份映射, 而入口指错菜的表现是「预填了别的菜」。
    name_to_pk = dict(zip(bridge[0], bridge[1]))
    total_rev = sum(float(f["revenue"] or 0) for f in facts)

    if not gaps:
        answer = (
            f"{window_text}卖过的菜**都有成本卡**，没有需要补的。\n\n"
            f"（有卡不等于卡是对的 —— 份量录错、只录主料这两种，"
            f"看毛利那条答案里会单独指出来。）"
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_RECIPE_COST", title="缺成本卡的菜",
            answer_text=answer, charts=[], kpis=[],
            meta={"missing_cost_card_count": 0,
                  "window_start": _date_text(window_start) if window_start else None,
                  "window_end": _date_text(window_end) if window_end else None},
        )

    lines = []
    for i, gap in enumerate(gaps[:20], 1):
        rev = float(gap["revenue"] or 0)
        share = (rev / total_rev * 100) if total_rev > 0 else 0.0
        lines.append(f"{i}. {gap['name']} —— {window_text}卖了 ¥{rev:,.2f}"
                     f"（占营收 {share:.1f}%）")
    more = (f"\n\n还有 {len(gaps) - 20} 道没列出来。"
            if len(gaps) > 20 else "")
    answer = (
        f"{window_text}有 **{len(gaps)} 道菜卖过但没有成本卡**，"
        f"按营收从高到低：\n\n" + "\n".join(lines) + more +
        f"\n\n这些菜的营收算不进毛利 —— 补一道，毛利就多覆盖它那部分营收。"
        f"\n⚠️ 排在前面的先补最划算，"
        f"因为覆盖率提升等于它自己的营收占比。"
    )
    return OpsAnswer(
        code="RESTAURANT_OPS_RECIPE_COST", title="缺成本卡的菜",
        answer_text=answer, charts=[], kpis=[],
        meta={
            "missing_cost_card_count": len(gaps),
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            # 机器可读版 —— 正文那张清单的同一批数据, ⛔ 不让下游 parse 正文。
            # 🔴 层 1: 每一道后面带**补录入口**(owner 2026-08-15 设计卡)。
            #    指向**已经存在**的 `RecipeEditScreen`(它本来就收 productTypeId /
            #    dishName 并有 hasPresetDish) —— ⛔ 不新建屏、不新建菜单项。
            #    ⛔ 入口里没有单价: 那个表单里根本没有单价字段(源码级已确认)。
            "rows": [{"dish": g["name"], "revenue": float(g["revenue"] or 0),
                      "fillEntry": _entry_hint(name_to_pk.get(g["name"], ""),
                                               g["name"])}
                     for g in gaps[:_MISSING_CARD_ROWS_LIMIT]],
        },
    )


def _entry_hint(dish_pk: str, dish_name: str) -> Dict[str, str]:
    """补录入口。⛔ 逻辑在 `filled_recently`, 这里只接线。"""
    from smartbi.gold.restaurant.filled_recently import entry_hint
    return entry_hint(dish_pk, dish_name)


#: 结构化行的上限。正文最多列 20 条, 机器可读侧给到 50 —— 两者不同是刻意的:
#: 正文要人读得完, 机器可读侧要够下游用。⛔ 两边都不许静默截断(正文写「还有 N 道」)。
_MISSING_CARD_ROWS_LIMIT = 50


#: 「有没有成本卡」的判据 —— **从登记表那一处派生**, ⛔ 不在这里重写条件。
#: 两种别名各一个: `c.` 前缀的和裸列名的(那几段 SQL 的写法不同)。
_CARD_PRESENT = _COST_CARD_PRESENT_SQL
_CARD_PRESENT_BARE = _COST_CARD_PRESENT_SQL.replace("c.", "")


async def resolve_recipe_cost(
    smartbi_pool, factory_id: str, top_n: int = 10,
    days: int = 30,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
    food_cost_ratio: bool = False,
    query: str = "",
    **_ignored,
) -> OpsAnswer:
    """Top N dishes by food cost (standard_qty × unit_price rollup).

    Joins cretas_db.product_types for dish names at query time (no dim_product
    ETL needed yet — see 2026_04_24_recipe_product_source_pk.sql rationale).

    ⚠️ `query` 只用来分「问的是成本排行还是缺卡清单」, 判据在
       `_asks_missing_cost_card`(与路由表同源)。⛔ 不在这里另写一套词。
    """
    if _asks_missing_cost_card(query):
        return await _resolve_missing_cost_cards(
            smartbi_pool, factory_id,
            days=days, date_range=date_range, window_label=window_label,
        )
    if food_cost_ratio:
        return await _resolve_food_cost_ratio(
            smartbi_pool,
            factory_id,
            days=days,
            date_range=date_range,
            window_label=window_label,
        )
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(
            """
            SELECT c.product_source_pk, c.food_cost, c.ingredient_count, c.has_price_data
              FROM agg_restaurant_product_cost c
             WHERE c.factory_id = $1
               AND c.food_cost > 0
               AND c.food_cost < $3
               AND """ + _CARD_PRESENT + """
             ORDER BY c.food_cost DESC NULLS LAST
             LIMIT $2
            """,
            factory_id, top_n, _MAX_SANE_DISH_UNIT_COST,
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

    from smartbi.gold.restaurant.restaurant_cost_mapping import merge_cost_product_names
    name_map = await merge_cost_product_names(
        smartbi_pool,
        factory_id,
        source_pks,
        name_map,
    )

    # 2026-08-11: 编号列表改 markdown 表格。「(N 种食材)」原本挤在括号里,
    # 表格里它是独立一列 —— 成本高是因为用料贵还是用料多, 这两列并排才看得出来。
    top_lines = _markdown_table(
        ["#", "菜品", "食材成本", "食材种数"],
        [[i, name_map.get(r["product_source_pk"], "#" + r["product_source_pk"]),
          f"¥{r['food_cost']:,.2f}", r["ingredient_count"]]
         for i, r in enumerate(rows, 1)],
        right_align={2, 3},
    ) if rows else ["", "(尚未录入配方数据或食材单价为空)"]
    top_block = "\n".join([f"菜品食材成本前 {len(rows)} 名:"] + top_lines)

    answer = (
        f"{top_block}\n\n"
        "> 注：成本按标准用量乘以食材单价计算；补齐销售金额后即可计算毛利。"
    )
    charts = []
    if rows:
        charts.append({
            "chartType": "bar",
            "title": f"高成本菜品前 {len(rows)} 名",
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
            {"title": "成本最高菜品", "value": name_map.get(rows[0]["product_source_pk"], "—") if rows else "—", "rawValue": 0},  # noqa: E501
        ],
        meta={"top_n": top_n},
    )


async def resolve_requisition_trend(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
) -> OpsAnswer:
    """Requisition trend + Top N ingredients for the window that was asked for.

    ``date_range`` must be declared here: ``resolve_by_code`` filters kwargs down
    to each resolver's signature, so an undeclared parameter is dropped
    **silently** and the SQL quietly falls back to a window ending today. That is
    how this resolver answered 「上个月领料趋势」 with July numbers under a
    「近 30 天」 heading — the same defect PR #2076 fixed for wastage.
    """
    window_start, window_end, window_text = _explicit_window(
        date_range, window_label, days,
    )
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        trend = await conn.fetch(
            """
            SELECT date, requisition_qty_total::float AS qty,
                   requisition_cost_total::float AS cost
              FROM agg_restaurant_daily_totals
             WHERE factory_id = $1
               AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
               AND ($4::date IS NULL OR date <= $4::date)
             ORDER BY date
            """,
            factory_id, days, window_start, window_end,
        )
        top = await conn.fetch(
            """
            SELECT i.name, i.category, i.unit,
                   SUM(a.value_num)::float AS qty
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON a.dim_value_id = i.ingredient_id
             WHERE a.factory_id = $1 AND a.kpi_kind = 'requisition_qty'
               AND a.date >= COALESCE($4::date, CURRENT_DATE - ($2::int))
               AND ($5::date IS NULL OR a.date <= $5::date)
             GROUP BY i.name, i.category, i.unit
             ORDER BY qty DESC NULLS LAST
             LIMIT $3
            """,
            factory_id, days, top_n, window_start, window_end,
        )

    total_qty = sum(r["qty"] or 0 for r in trend)
    total_cost = sum(r["cost"] or 0 for r in trend)
    # 2026-08-11: 编号列表改 markdown 表格。
    # ⛔ 只给领用量一列: `top` 这几行没有逐项成本(成本只在上面的总计里, 来自 `trend`),
    #    不能为了让表好看就编一列出来 —— 那是拿相邻指标顶替。
    top_lines = _markdown_table(
        ["#", "食材", "分类", "领用量"],
        [[i, r["name"], r["category"] or "—",
          f"{r['qty']:.2f} {r['unit'] or ''}".strip()]
         for i, r in enumerate(top, 1)],
        right_align={3},
    ) if top else ["", f"({window_text}无领料记录)"]
    top_block = "\n".join([f"领用食材前 {len(top)} 名:"] + top_lines)

    answer = (
        f"{window_text}领料总览:\n"
        f"- 总量 {total_qty:.2f} 单位, 估算成本 **¥{total_cost:.2f}**, {len(trend)} 天有活动\n\n"
        f"{top_block}\n\n"
        f"建议动作:\n"
        f"1. 把领用靠前的食材和畅销菜、损耗榜交叉看，判断是销量驱动还是领用过量。\n"
        f"2. 对领用量稳定但销售没有同步增长的食材，先查备料标准和退料记录。\n"
        f"3. 对成本占比高的食材设置日领用上限，超过上限需店长复核。"
    )
    charts = [{
        "chartType": "line",
        "title": f"{window_text}领料数量趋势",
        "xAxis": {"data": [r["date"].isoformat() for r in trend]},
        "series": [{"name": "领料量", "type": "line", "data": [r["qty"] for r in trend]}],
    }]
    if top:
        charts.append({
            "chartType": "bar",
            "title": f"领用食材前 {len(top)} 名",
            "xAxis": {"data": [r["name"] for r in top]},
            "series": [{"name": "领用量", "type": "bar", "data": [r["qty"] for r in top]}],
        })

    return OpsAnswer(
        code="RESTAURANT_OPS_REQUISITION_TREND",
        title=f"{window_text}领料趋势与食材前 {top_n} 名",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "总领料量", "value": f"{total_qty:.1f}", "rawValue": total_qty},
            {"title": "估算成本", "value": f"¥{total_cost:.2f}", "rawValue": total_cost},
            {"title": "活动天数", "value": len(trend), "rawValue": len(trend)},
            {"title": "领用最多食材", "value": top[0]["name"] if top else "—", "rawValue": 0},
        ],
        meta={
            "window_days": days, "top_n": top_n,
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "window_label": window_label,
        },
    )


async def _paid_revenue_in_window(pool, factory_id: str, start, end):
    """这段时间的**实收**营收（`SUM(t.net_amount)`，扣了交易级折扣）。

    ⛔ 不能在明细 join 上直接 `SUM(t.net_amount)` —— 一张订单有多条明细, 会扇出
       (2026-08-09 实测过 57 倍)。所以单独查订单表, 不 join 明细。

    ⚠️ 与 `generic_executor` 里 `revenue` 的 txn 粒度表达式是**同一个口径**;
       两条路必须给出同一个合计毛利, 由 `scripts/cron/margin-parity-daily.sh`
       那道闸每天钉住。
    """
    if not start or not end:
        return None
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT SUM(t.net_amount)::float AS paid "
                "  FROM fact_pos_transaction t "
                " WHERE t.factory_id = $1 AND t.date >= $2 AND t.date <= $3",
                factory_id, start, end)
    except Exception:  # noqa: BLE001 — 拿不到就让调用方退回原口径并标注
        logger.warning("[gross_margin] 实收营收查询失败", exc_info=True)
        return None
    return None if row is None or row["paid"] is None else float(row["paid"])


async def resolve_gross_margin(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
    *, role: Optional[str] = None, query: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
    dish_mention: Optional[str] = None,
    dish_mentions: Sequence[str] = (),
    requested_metrics: Sequence[str] = (),
    analysis_action: Optional[str] = None,
    ranking_direction: Optional[str] = None,
    ranking_limit: Optional[int] = None,
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

    RBAC (2026-07-08 audit fix): 毛利/成本/营收金额是价格权限数据 —— 与
    resolve_sales_summary 相同的 PRICE_VIEW_ROLES 门。此前本函数没有 role
    参数, resolve_by_code 的签名过滤把 role 静默丢弃, 任何角色都能拿到未
    脱敏金额。非价格角色现在拿到诚实披露 + 非金额替代入口, 不出金额。
    """
    query_text = query or ""
    resolved_ranking_direction = (
        ranking_direction or dish_ranking_direction(query_text)
    )
    requested_metric_set = {
        value for value in requested_metrics if isinstance(value, str)
    }
    quantity_only_ranking = bool(
        requested_metric_set
        and requested_metric_set.issubset({"sales_volume"})
        and resolved_ranking_direction
    )
    # 🔴 「毛利最低的菜品有哪些」曾被答成「卖得最差的菜」。
    #
    # 下面 R14 那个按销量直排的分支 (见「不涉及成本, 不需要毛利覆盖」那段注释) 是为
    # 「哪道菜卖得最好/最差」写的, 但它的进入条件只看**有没有排序方向**。而
    # 「毛利最低」同样解析出 ranking_direction='worst', 于是毛利问题掉进了销量分支,
    # 产出一份完全不含毛利的销量榜 —— 排序口径整个换掉了, 而措辞读起来毫无破绽。
    #
    # 拦住它的是答案契约 (margin_value / margin_integrity / request_coverage 三项缺失
    # → 降级成澄清), 也就是说**用户看到的是「答不出来」而不是一个错的答案** ——
    # 契约做对了事, 但根因在这里。
    #
    # 判据只看**要的是哪个指标**, 不看方向词: 用户点名要毛利/利润, 就不能拿销量榜充数。
    margin_ranking_requested = bool(
        requested_metric_set & {"gross_margin", "net_profit"}
    )
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    can_view_prices = bool(role) and role in PRICE_VIEW_ROLES
    if not can_view_prices and not quantity_only_ranking:
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title="菜品毛利分析",
            answer_text=(
                "菜品毛利、成本和营收金额属于成本/价格权限，当前角色不能查看金额。"
                "可以先看销量视角：问「哪个菜卖得好」看菜品销量排行；"
                "如需毛利数据请联系管理员开通价格查看权限。"
            ),
            charts=[],
            kpis=[],
            meta={"rbac_masked": True},
        )
    prohibited_actions_requested = any(token in query_text for token in (
        "先不要做", "不要做", "先别做", "不该做", "避免做", "暂时别",
    ))
    trend_requested = any(token in query_text for token in (
        "趋势", "走势", "曲线", "按月", "月份", "参照线", "计划线", "预警线",
    ))
    requested_period = _relative_period_match(query_text)
    exact_start = date_range[0] if date_range else None
    exact_end = date_range[1] if date_range else None
    analysis_days = days
    if trend_requested:
        if requested_period and requested_period[1] == "月":
            analysis_days = max(days, requested_period[0] * 31)
        else:
            analysis_days = max(days, 730)

    # Need cretas connection for product_types name↔id lookup
    monthly_pos_rows: List[Any] = []
    dish_candidates: List[str] = []
    multi_dish_matches: List[Tuple[str, List[Any]]] = []
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)

        # Step 1: POS sales aggregated per dish. Anchor the window to the
        # latest transaction that can be joined to dim_product; demo datasets
        # can have sparse tail transactions with empty product_id.
        pos_rows = await conn.fetch(
            """
            WITH anchor AS (
                -- ⚡ 语义不变、耗时 1330ms -> 0ms(2026-08-08 实测)。
                -- 原式 `MAX(t2.date)` + 三表全连: 为了一个日期走完 94.7 万行明细,
                -- EXPLAIN 显示 `Rows Removed by Join Filter: 4,261,170`。
                -- 改成**索引倒序取第一条**: 走 idx_fact_pos_txn_factory_store_date 反扫,
                -- 命中第一条就停 —— 「最后一天有菜品明细的交易日」这个语义逐字保留,
                -- ⛔ 没有简化成裸 MAX(date)(那会在明细缺失时给出偏晚的锚点)。
                SELECT t2.date AS end_date
                  FROM fact_pos_transaction t2
                 WHERE t2.factory_id = $1
                   AND EXISTS (
                         SELECT 1
                           FROM fact_pos_item i2
                           JOIN dim_product p2
                             ON p2.product_id = i2.product_id
                            AND p2.factory_id = i2.factory_id
                          WHERE i2.transaction_id = t2.id
                            AND i2.factory_id = $1
                       )
                 ORDER BY t2.date DESC
                 LIMIT 1
            )
            SELECT p.product_id, p.name AS dish_name, p.normalized_name,
                   p.category, p.sub_category,
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
               AND t.date >= COALESCE($3::date, anchor.end_date - ($2::int))
               AND t.date <= COALESCE($4::date, anchor.end_date)
             GROUP BY p.product_id, p.name, p.normalized_name,
                      p.category, p.sub_category
             ORDER BY total_revenue DESC NULLS LAST
            """,
            factory_id, analysis_days, exact_start, exact_end,
        )
        benchmark_pos_rows = list(pos_rows)
        # Sheet 7/22 实体检测: 点名单菜的问题 ("米饭的毛利率") 此前返回全菜品
        # 榜 — 菜品版的全店榜退化。候选名必须命中本租户菜品行才限域; 命不中
        # 定向拒答, 多命中请求澄清。泛指问法 (整体/哪道/排行) 不受影响。
        # A demo request can be planned against DEMO_REST's menu while this
        # resolver deliberately reads the richer RES_3101_009 data tenant.
        # Re-extracting under the data tenant's catalogue used to silently
        # discard a valid DEMO dish that RES has no row for, so a three-dish
        # question rendered only two dishes.  The sealed plan's explicit
        # candidates are nominations, not trusted facts: rows below still
        # validate every name and render an honest no-data line when absent.
        planned_dish_mentions = [
            value.strip()[:60]
            for value in dish_mentions[:8]
            if isinstance(value, str) and value.strip()
        ]
        dish_candidates = planned_dish_mentions or extract_dish_candidates(query)
        if not dish_candidates and dish_mention:
            # R22b: 正则抽取器 miss 时用 T3 实体槽位兜底 — 槽位只是提名,
            # 下方 _match_dish_rows 对本租户菜品行验证, 命不中照样定向拒答。
            dish_candidates = [dish_mention]
        dish_candidate = dish_candidates[0] if len(dish_candidates) == 1 else None
        dish_scope_row = None
        if len(dish_candidates) >= 2 and pos_rows:
            compare_rows = []
            for cand in dish_candidates:
                # 多规格菜 ("招牌藤椒味"→单人份/双人份) 全部纳入对比。
                matched = list(_match_dish_rows(cand, pos_rows))
                multi_dish_matches.append((cand, matched))
                compare_rows.extend(matched)
            if len({r["product_id"] for r in compare_rows}) >= 2:
                seen_pid = set()
                pos_rows = [
                    r for r in compare_rows
                    if r["product_id"] not in seen_pid
                    and not seen_pid.add(r["product_id"])
                ]
        if dish_candidate and pos_rows:
            matched_rows = _match_dish_rows(dish_candidate, pos_rows)
            if not matched_rows:
                return OpsAnswer(
                    code="RESTAURANT_OPS_GROSS_MARGIN",
                    title=f"{dish_candidate} — 菜品查询",
                    answer_text=(
                        f"**没有找到名为「{dish_candidate}」的菜品**，"
                        "不能给出该菜的销量或毛利，也不会用全部菜品的榜单替代。\n\n"
                        "请核对菜名；可以先问「哪个菜卖得最好」查看在售菜品。"
                    ),
                    charts=[], kpis=[],
                    meta={"dish_not_found": dish_candidate},
                )
            if len(matched_rows) > 1:
                options = "、".join(r["dish_name"] for r in matched_rows[:3])
                return OpsAnswer(
                    code="RESTAURANT_OPS_GROSS_MARGIN",
                    title="请确认菜品",
                    answer_text=(
                        f"「{dish_candidate}」匹配到多道菜品：{options}。"
                        "请指定其中一道后再查询。"
                    ),
                    charts=[], kpis=[],
                    meta={"dish_mention_ambiguous": dish_candidate,
                          "candidates": [r["dish_name"] for r in matched_rows]},
                )
            pos_rows = matched_rows
            dish_scope_row = matched_rows[0]
        if trend_requested:
            monthly_pos_rows = await conn.fetch(
                """
                WITH anchor AS (
                    -- ⚡ 语义不变、耗时 1330ms -> 0ms(2026-08-08 实测)。
                    -- 原式 `MAX(t2.date)` + 三表全连: 为了一个日期走完 94.7 万行明细,
                    -- EXPLAIN 显示 `Rows Removed by Join Filter: 4,261,170`。
                    -- 改成**索引倒序取第一条**: 走 idx_fact_pos_txn_factory_store_date 反扫,
                    -- 命中第一条就停 —— 「最后一天有菜品明细的交易日」这个语义逐字保留,
                    -- ⛔ 没有简化成裸 MAX(date)(那会在明细缺失时给出偏晚的锚点)。
                    SELECT t2.date AS end_date
                      FROM fact_pos_transaction t2
                     WHERE t2.factory_id = $1
                       AND EXISTS (
                             SELECT 1
                               FROM fact_pos_item i2
                               JOIN dim_product p2
                                 ON p2.product_id = i2.product_id
                                AND p2.factory_id = i2.factory_id
                              WHERE i2.transaction_id = t2.id
                                AND i2.factory_id = $1
                           )
                     ORDER BY t2.date DESC
                     LIMIT 1
                )
                SELECT date_trunc('month', t.date)::date AS month,
                       p.name AS dish_name, p.normalized_name,
                       SUM(i.qty)::float AS total_qty,
                       SUM(i.amount)::float AS total_revenue,
                       COUNT(DISTINCT i.transaction_id)::int AS bills
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
                 GROUP BY date_trunc('month', t.date)::date,
                          p.name, p.normalized_name
                 ORDER BY month, p.name
                """,
                factory_id, analysis_days,
            )

    if not pos_rows:
        no_data_guard = (
            "今天先不要做：不要在销售明细缺失时批量提价、下架菜品或取消套餐；先完成数据同步。"
            if prohibited_actions_requested else ""
        )
        concrete_window = (
            _range_text(exact_start, exact_end)
            if exact_start and exact_end else f"近 {analysis_days} 天"
        )
        requested_window = (
            f"{window_label}（{concrete_window}）"
            if window_label and window_label not in concrete_window
            else concrete_window
        )
        if dish_candidates:
            named = "、".join(f"「{candidate}」" for candidate in dish_candidates)
            if requested_metric_set == {"sales_volume"}:
                metric_label = "销量"
            elif requested_metric_set == {"recipe_cost"}:
                metric_label = "成本"
            elif requested_metric_set == {"gross_margin"}:
                metric_label = "毛利"
            else:
                metric_label = "菜品数据"
            return OpsAnswer(
                code="RESTAURANT_OPS_GROSS_MARGIN",
                title=f"{named}{metric_label}（暂无销售数据）",
                answer_text=(
                    f"{requested_window}{named}没有可用的销售记录，"
                    f"所以本次不能给出可靠{metric_label}；没有用其他时间范围替代，"
                    "也没有用全部菜品代替。\n"
                    "请确认该时段的 POS 菜品明细已同步后再试。"
                ),
                charts=[],
                kpis=[],
                meta={
                    "no_pos_data": True,
                    "targetDishes": dish_candidates,
                    "window_days": analysis_days,
                    "window_start": _date_text(exact_start) if exact_start else None,
                    "window_end": _date_text(exact_end) if exact_end else None,
                },
            )
        if resolved_ranking_direction:
            requested_rank_limit = (
                max(1, min(int(ranking_limit), 20))
                if isinstance(ranking_limit, int) and not isinstance(ranking_limit, bool)
                else _ranking_limit_from_query(query_text)
            )
            return OpsAnswer(
                code="RESTAURANT_OPS_GROSS_MARGIN",
                title="菜品销量排行（暂无销售数据）",
                answer_text=(
                    f"{requested_window}没有可用于菜品销量排行的销售记录，"
                    "本次没有改成毛利分析，也没有用其他时间范围代替。\n"
                    "请确认 POS 菜品销售明细已经同步后再试。"
                ),
                charts=[],
                kpis=[],
                meta={
                    "dish_ranking": resolved_ranking_direction,
                    "ranking_limit": requested_rank_limit,
                    "window_days": analysis_days,
                    "no_pos_data": True,
                    "window_start": _date_text(exact_start) if exact_start else None,
                    "window_end": _date_text(exact_end) if exact_end else None,
                    "ranked_entities": [],
                    "focus_entity": None,
                },
            )
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"菜品毛利分析 ({requested_window})",
            answer_text=(
                f"{requested_window}没有可用于计算的菜品销售记录，没有用其他时间范围替代。\n"
                f"请确认销售数据已经同步，并补齐菜品配方和食材单价后再试。{no_data_guard}"
            ),
            charts=[], kpis=[],
            meta={"window_days": analysis_days, "no_pos_data": True,
                  "window_start": _date_text(exact_start) if exact_start else None,
                  "window_end": _date_text(exact_end) if exact_end else None},
        )

    window_start = min((r["window_start"] for r in pos_rows if r["window_start"]), default=None)
    window_end = max((r["window_end"] for r in pos_rows if r["window_end"]), default=None)
    concrete_window = (
        _range_text(exact_start, exact_end)
        if exact_start and exact_end
        else _actual_window_text(window_start, window_end, analysis_days)
    )
    window_label = (
        f"{window_label}（{concrete_window}）"
        if window_label and window_label not in concrete_window
        else concrete_window
    )

    if len(dish_candidates) >= 2 and requested_metric_set == {"sales_volume"}:
        match_map = dict(multi_dish_matches)
        lines = [f"**{window_label}多菜品销量对比：**", ""]
        kpis: List[Dict[str, Any]] = []
        target_entities: List[Dict[str, Any]] = []
        for candidate in dish_candidates:
            matched_rows = match_map.get(candidate, [])
            if not matched_rows:
                lines.append(f"- 「{candidate}」：没有找到该时段的销售记录")
                target_entities.append({"type": "dish", "name": candidate, "no_data": True})
                continue
            qty = sum(float(row.get("total_qty") or 0) for row in matched_rows)
            bills = sum(int(row.get("bills") or 0) for row in matched_rows)
            qty_text = _format_sales_quantity(qty)
            lines.append(f"- 「{candidate}」：销量 **{qty_text} 份**，覆盖 {bills} 单")
            kpis.append({
                "title": f"{candidate}销量",
                "value": f"{qty_text} 份",
                "rawValue": qty,
            })
            target_entities.append({
                "type": "dish",
                "name": candidate,
                "sales_volume": qty,
                "bill_count": bills,
            })
        lines.extend((
            "",
            "以上按同一时间范围、同一全部门店口径统计；"
            "本次只回答销量，没有改成毛利榜或全菜单汇总。",
        ))
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"多菜品销量对比 ({window_label})",
            answer_text="\n".join(lines),
            charts=[],
            kpis=kpis,
            meta={
                "window_days": analysis_days,
                "window_start": _date_text(window_start) if window_start else None,
                "window_end": _date_text(window_end) if window_end else None,
                "targetDishes": dish_candidates,
                "ranked_entities": target_entities,
                "focus_entity": target_entities[0] if target_entities else None,
            },
        )

    # Sheet 7/22 扫雷 R14: 「哪道菜卖得最差/最好」此前只有 Java 报表工具
    # (依赖上传的商品销量报表, DEMO 无数据 → 死胡同)。POS 行就在手上, 按
    # 销量直接排; 不涉及成本, 不需要毛利覆盖。
    resolved_ranking_direction = (
        resolved_ranking_direction
        if not dish_scope_row
        and len(dish_candidates) < 2
        # 见函数开头 margin_ranking_requested 的说明: 要毛利就不能走这条销量榜。
        and not margin_ranking_requested
        else None
    )
    if resolved_ranking_direction:
        requested_rank_limit = (
            max(1, min(int(ranking_limit), 20))
            if isinstance(ranking_limit, int) and not isinstance(ranking_limit, bool)
            else _ranking_limit_from_query(query_text)
        )
        explicit_exclusions = ranking_exclusions(query_text)
        rankable_rows = []
        exclusion_reasons: Dict[str, int] = {}
        for row in pos_rows:
            reason = _primary_dish_ranking_exclusion_reason(row)
            normalized_dish_name = re.sub(r"\s+", "", str(row.get("dish_name") or ""))
            if (
                not reason
                and any(
                    re.sub(r"\s+", "", excluded) == normalized_dish_name
                    for excluded in explicit_exclusions
                )
            ):
                reason = "user_excluded"
            if reason:
                exclusion_reasons[reason] = exclusion_reasons.get(reason, 0) + 1
            else:
                rankable_rows.append(row)

        excluded = len(pos_rows) - len(rankable_rows)
        if not rankable_rows:
            return OpsAnswer(
                code="RESTAURANT_OPS_GROSS_MARGIN",
                title="菜品销量排行（暂无主菜数据）",
                answer_text=(
                    f"{window_label}的销售记录仅包含米饭、包装、餐具或纸品等"
                    "附属/基础项，**没有可用于主菜销量排行的记录**。\n\n"
                    "我没有把这些附属项重新放回榜单；请确认主菜 POS 明细已同步后再试。"
                ),
                charts=[], kpis=[],
                meta={
                    "dish_ranking": resolved_ranking_direction,
                    "ranking_limit": requested_rank_limit,
                    "excluded_entities": explicit_exclusions,
                    "window_label": window_label,
                    "no_primary_dish_data": True,
                    "excluded_item_count": excluded,
                    "excluded_item_reasons": exclusion_reasons,
                    "ranked_entities": [],
                    "focus_entity": None,
                },
            )

        ranked = sorted(
            rankable_rows, key=lambda r: float(r["total_qty"] or 0),
            reverse=(resolved_ranking_direction == "best"),
        )
        rank_label = (
            "卖得最好" if resolved_ranking_direction == "best" else "卖得最差"
        )
        lines = [
            f"**{window_label}菜品销量排行（{rank_label}前 {requested_rank_limit}）：**",
            "",
        ]
        # 2026-08-10: 编号列表改 markdown 表格 —— 排行本来就是表格数据, 列表形态
        # 让人没法竖着比数。前端 MarkdownRenderer 早已支持 GFM 表格。
        headers = ["#", "菜品", "销量（份）"] + (["营收"] if can_view_prices else [])
        table_rows = []
        for idx, r in enumerate(ranked[:requested_rank_limit], 1):
            row = [idx, r["dish_name"], _format_sales_quantity(r["total_qty"])]
            if can_view_prices:
                row.append(f"¥{float(r['total_revenue'] or 0):,.2f}")
            table_rows.append(row)
        lines.extend(_markdown_table(
            headers, table_rows,
            right_align={2, 3} if can_view_prices else {2}))
        note = (
            f"，已剔除 {excluded} 个附属/基础项（米饭、包装、餐具、纸巾等）"
            if excluded > 0 else ""
        )
        lines.append("")
        lines.append(
            f"> 仅统计窗口内有销售记录的 {len(rankable_rows)} 道菜品{note}；未售出的菜品不在榜内。"
        )
        ranked_entities = [
            {
                "type": "dish",
                "id": row.get("product_id"),
                "name": row["dish_name"],
                "rank": index,
                "sales_volume": float(row["total_qty"] or 0),
                "bill_count": int(row["bills"] or 0),
                **(
                    {"revenue": float(row["total_revenue"] or 0)}
                    if can_view_prices else {}
                ),
            }
            for index, row in enumerate(ranked[:requested_rank_limit], 1)
        ]
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"菜品销量排行（{rank_label}）",
            answer_text="\n".join(lines),
            charts=[], kpis=[],
            meta={
                "dish_ranking": resolved_ranking_direction,
                "ranking_limit": requested_rank_limit,
                "excluded_entities": explicit_exclusions,
                "window_label": window_label,
                "excluded_item_count": excluded,
                "excluded_item_reasons": exclusion_reasons,
                "ranked_entities": ranked_entities,
                "focus_entity": ranked_entities[0] if ranked_entities else None,
            },
        )

    # Step 2: 菜名 → 成本键。
    #
    # 🔴 2026-08-13 owner 裁定条件 2: 这一步的实现**只有一份**, 在
    #    `restaurant_cost_mapping.resolve_cost_keys` —— 日结那条路读的是同一份。
    #    改之前这里是三十行内联的 cretas 查询, 日结那边是一条 SQL join,
    #    两边够得着的菜不一样(青花椒 9 道只在运营库里), 于是同一天同一家店
    #    日结毛利 103,370.22 而问答 124,071.85。
    # ⛔ 谁都不许在这里「顺手」再补一级 fallback: 补了日结就少一级。
    from smartbi.gold.restaurant.restaurant_cost_mapping import (
        CostKeySourceUnavailable,
        resolve_cost_keys,
    )
    async with smartbi_pool.acquire() as _map_conn:
        await _map_conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id)
        try:
            cretas_map = await resolve_cost_keys(_map_conn, factory_id)
        except CostKeySourceUnavailable as exc:
            # ⛔ 不降级成「这些菜没有成本卡」—— 那会把毛利算高而且不留痕迹。
            #    宁可这一问明确说取不到。
            logger.error("[gross_margin] 成本键权威来源不可用 factory=%s: %s",
                         factory_id, exc)
            return OpsAnswer(
                code="RESTAURANT_OPS_GROSS_MARGIN",
                title="毛利",
                answer_text=(
                    "现在算不了毛利 —— 菜品成本的数据源连不上。\n\n"
                    "这不是「你的菜没有成本卡」，是我这边取不到，"
                    "所以我不给你一个可能偏高的数。稍后再问一次。"
                ),
                charts=[], kpis=[],
                meta={"no_data": True, "reason": "cost_key_source_unavailable"},
            )

    # Step 3: load food cost per source_pk
    cost_map: Dict[str, float] = {}
    if cretas_map:
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            cost_rows = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS food_cost
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1
                   AND product_source_pk = ANY($2::text[])
                   AND """ + _CARD_PRESENT_BARE + """
                """,
                factory_id, list(cretas_map.values()),
            )
            cost_map = {r["product_source_pk"]: r["food_cost"] for r in cost_rows}

    # Step 4: compute only from rows whose cost is actually present.
    enriched = _build_margin_entries(pos_rows, cretas_map, cost_map)
    with_cost = [item for item in enriched if item["has_cost"]]
    # Generic dish rankings and recommendations are for sellable primary
    # dishes. Rice/staples, tissues, wet wipes and disposable tableware remain
    # in the all-sales totals, but cannot become "best dish", "low margin
    # dish" or a promotion/repair recommendation. A user who explicitly names
    # one of those items still receives its exact scoped facts.
    ranking_pool = (
        enriched
        if dish_scope_row is not None
        else [
            item for item in enriched
            if _primary_dish_ranking_exclusion_reason(item) is None
        ]
    )
    ranking_with_cost = [item for item in ranking_pool if item["has_cost"]]
    primary_excluded_count = len(enriched) - len(ranking_pool)
    top_slice = _rank_cost_complete_margin_entries(ranking_pool, top_n)
    # 🔴 owner 2026-08-13 裁定 a: **合计层用实收营收**, 逐菜明细保持 item 口径。
    #
    # 明细行的 `revenue` 是 `SUM(i.amount)` —— **原价**, 没扣交易级折扣。
    # 拿它算合计毛利等于把从未收到的钱算成收入(prod 实测虚高 31,125.59, 约 6.5%)。
    # ⛔ 逐菜那一层**不改**: 交易级折扣摊不到单道菜, 摊派规则本身有真争议。
    #
    # ⚠️ `total_rev_items` 只用于**覆盖率**(分子分母都得是 item 口径, 否则
    #    「可计算毛利的营收 ÷ 全部营收」会算出 >100%)。
    total_rev_items = sum(item["revenue"] for item in enriched)
    total_rev_with_cost = sum(item["revenue"] for item in with_cost)
    # 成本与营收口径无关 —— 它就是那批菜的食材成本。
    total_cost = total_rev_with_cost - sum(
        float(item["gross_profit"]) for item in with_cost)

    total_paid_rev = await _paid_revenue_in_window(
        smartbi_pool, factory_id, window_start, window_end)

    # 🔴 owner 2026-08-13 裁定: 毛利的**分子分母都只算有成本卡的那部分**,
    #    且交易级折扣按明细金额比例摊到覆盖部分(折扣总额实测, 只有分配是估的)。
    #    改之前「全额分子 vs 覆盖额分母」三个症状同源:
    #      DEMO_REST 日结毛利率 88.3% / 青花椒问答整个拒答 / 正文自己算不平。
    if total_paid_rev is not None and total_rev_items > 0:
        _discount = total_rev_items - total_paid_rev
        _share = total_rev_with_cost / total_rev_items
        covered_net_rev = total_rev_with_cost - _discount * _share
    else:
        covered_net_rev = total_rev_with_cost

    if total_paid_rev is None:
        # ⛔ 取不到实收就**退回原口径并如实标注**, 不拿一个猜的数顶上。
        logger.warning("[gross_margin] 拿不到实收营收(factory=%s %s~%s), "
                       "合计层退回原价口径", factory_id, window_start, window_end)
        total_rev = total_rev_items
    else:
        total_rev = total_paid_rev
    # ⚠️ 毛利只在覆盖部分上算 —— 与 generic_executor 的覆盖口径**同一个定义**,
    #    两条路必须给出同一个数(margin-parity 那道闸每天钉住)。
    total_profit = covered_net_rev - total_cost
    # 🔴 两边同口径。改之前左边是「全额营收 − 成本」右边是覆盖额 ——
    #    覆盖率 100% 时两者相等(MOCK_REST 上这道闸不可能红), 42.2% 时必炸。
    # ⛔ 不是放宽这道闸让它别响 —— 它在青花椒上开火是对的, 拦下的是个不可能的数。
    #    改的是被它拦下的那个算法。
    margin_invariant_pass = bool(
        math.isfinite(total_profit)
        and total_profit <= covered_net_rev + 0.01
    )
    if not margin_invariant_pass:
        logger.error(
            "[gross_margin] blocked impossible aggregate factory=%s profit=%s covered_revenue=%s",
            factory_id, total_profit, total_rev_with_cost,
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title="菜品毛利分析",
            answer_text=(
                "毛利口径自检未通过：已覆盖毛利不能高于对应营收。"
                "本次没有展示异常金额，请先复核食材单位、最近进价和销售范围后重试。"
            ),
            charts=[],
            kpis=[],
            meta={
                "marginInvariantPass": False,
                "marginFormula": "毛利=可计算毛利的营收-对应菜品成本",
                "cost_covered_revenue": total_rev_with_cost,
            },
        )

    profit_comparison_requested = bool(
        len(dish_candidates) >= 2
        and any(token in query_text for token in (
            "哪个赚钱", "哪个更赚钱", "哪道赚钱", "哪道更赚钱",
            "哪个毛利高", "哪个毛利率高", "哪个利润高", "哪个更划算",
        ))
    )
    if profit_comparison_requested:
        match_map = dict(multi_dish_matches)
        summaries: List[Dict[str, Any]] = []
        for candidate in dish_candidates:
            matched_names = {
                str(row.get("dish_name") or "")
                for row in match_map.get(candidate, [])
            }
            candidate_entries = [
                item for item in enriched
                if str(item.get("name") or "") in matched_names
            ]
            complete = bool(candidate_entries) and all(
                bool(item.get("has_cost")) for item in candidate_entries
            )
            revenue = sum(float(item.get("revenue") or 0) for item in candidate_entries)
            qty = sum(float(item.get("qty") or 0) for item in candidate_entries)
            bills = sum(int(item.get("bills") or 0) for item in candidate_entries)
            total_cost = (
                sum(float(item.get("total_cost") or 0) for item in candidate_entries)
                if complete else None
            )
            gross_profit = (
                revenue - total_cost if total_cost is not None else None
            )
            margin_rate = (
                gross_profit / revenue
                if gross_profit is not None and revenue > 0 else None
            )
            summaries.append({
                "name": candidate,
                "qty": qty,
                "bills": bills,
                "revenue": revenue,
                "total_cost": total_cost,
                "gross_profit": gross_profit,
                "margin_rate": margin_rate,
                "complete": complete,
            })

        comparable = [item for item in summaries if item["complete"]]
        missing = [item["name"] for item in summaries if not item["complete"]]
        if len(comparable) == len(summaries) and summaries:
            ordered = sorted(
                summaries,
                key=lambda item: float(item["gross_profit"] or 0),
                reverse=True,
            )
            winner = ordered[0]
            runner_up = ordered[1]
            profit_gap = float(winner["gross_profit"]) - float(runner_up["gross_profit"])
            if abs(profit_gap) <= 0.01:
                conclusion = (
                    f"**结论：按总毛利，「{winner['name']}」和"
                    f"「{runner_up['name']}」基本打平。**"
                )
            else:
                conclusion = (
                    f"**结论：按总毛利，「{winner['name']}」更赚钱，"
                    f"比「{runner_up['name']}」多 ¥{profit_gap:,.2f} 毛利。**"
                )
        else:
            missing_text = "、".join(f"「{name}」" for name in missing)
            conclusion = (
                "**结论：目前无法可靠判断哪个更赚钱。**"
                f"{missing_text}缺少完整成本，不能拿销量或营收替代毛利结论。"
            )

        lines = [conclusion, "", f"**{window_label}同口径对比：**"]
        kpis: List[Dict[str, Any]] = []
        target_entities: List[Dict[str, Any]] = []
        for item in summaries:
            name = item["name"]
            if item["complete"]:
                verdict = (
                    "赚钱"
                    if float(item["gross_profit"]) > 0.01
                    else "亏钱"
                    if float(item["gross_profit"]) < -0.01
                    else "打平"
                )
                lines.append(
                    f"- 「{name}」：销量 {_format_sales_quantity(item['qty'])} 份，"
                    f"营收 ¥{item['revenue']:,.2f}，成本 ¥{item['total_cost']:,.2f}，"
                    f"毛利 **¥{item['gross_profit']:,.2f}**，"
                    f"毛利率 **{item['margin_rate'] * 100:.1f}%**（{verdict}）"
                )
                kpis.append({
                    "title": f"{name}毛利",
                    "value": f"¥{item['gross_profit']:,.2f}",
                    "rawValue": item["gross_profit"],
                })
            else:
                lines.append(
                    f"- 「{name}」：销量 {_format_sales_quantity(item['qty'])} 份，"
                    "毛利暂时无法计算（缺少完整成本）"
                )
            target_entities.append({
                "type": "dish",
                "name": name,
                "sales_volume": item["qty"],
                "revenue": item["revenue"],
                "gross_profit": item["gross_profit"],
                "margin_rate": item["margin_rate"],
                "cost_complete": item["complete"],
            })
        lines.extend((
            "",
            "> 比较口径：总毛利 = 同一期间、同一门店范围内的营收 − 对应菜品成本；"
            "成本未覆盖的菜品不强行排名。",
        ))
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"菜品赚钱能力对比 ({window_label})",
            answer_text="\n".join(lines),
            charts=[],
            kpis=kpis,
            meta={
                "marginInvariantPass": True,
                "marginFormula": "毛利=同口径营收-对应菜品成本",
                "targetDishes": dish_candidates,
                "ranked_entities": target_entities,
                "focus_entity": target_entities[0] if target_entities else None,
                "cost_coverage_ratio": (
                    len(comparable) / len(summaries) if summaries else 0.0
                ),
            },
        )

    # 🔴 毛利率的分母必须和分子**同口径**。
    #    prod 实测漏了这一步: 分子已经换成实收口径(475,623.83), 分母还是
    #    item 口径(749,009) → 报出 63.5%, 而日结报 66.3% —— **毛利对上了,
    #    毛利率还是两个数**。改一半比不改更难发现: 最显眼的那个数已经一致了。
    # 毛利率 = 覆盖毛利 ÷ **覆盖净营收**(与分子同口径)。
    avg_margin = (
        total_profit / covered_net_rev
        if covered_net_rev > 0 else None
    )
    ranking_rev_with_cost = sum(item["revenue"] for item in ranking_with_cost)
    ranking_profit = sum(
        float(item["gross_profit"]) for item in ranking_with_cost
    )
    ranking_avg_margin = (
        ranking_profit / ranking_rev_with_cost
        if ranking_rev_with_cost > 0 else None
    )
    # ⚠️ 覆盖率的分子分母**都必须是 item 口径** —— 分母换成实收会算出 >100%
    #    (可计算毛利的营收 749,009 ÷ 实收 717,883 = 104.3%)。
    coverage_ratio = (total_rev_with_cost / total_rev_items
                      if total_rev_items > 0 else 0.0)
    low_margin = sorted(
        [item for item in ranking_with_cost if item["revenue"] >= 1000],
        key=lambda item: item["margin_rate"],
    )[:3]

    # Sheet 7/22 扫雷 R14 (G5): 「有没有毛利率是负的菜」是存在性问题, 此前
    # 返回全局毛利榜 — 答非所问。直接过滤负毛利行给结论, 覆盖率如实披露。
    if not dish_scope_row and _NEGATIVE_MARGIN_EXISTENCE_RE.search(query_text):
        negative = sorted(
            (
                i for i in ranking_with_cost
                if i["margin_rate"] is not None and i["margin_rate"] < 0
            ),
            key=lambda i: i["margin_rate"],
        )
        if negative:
            neg_lines = [
                f"**{window_label}有 {len(negative)} 道毛利为负的菜品（卖一份亏一份，属于亏钱菜品）：**",
                "",
            ]
            for item in negative[:5]:
                neg_lines.append(
                    f"- {item['name']} — 毛利率 {item['margin_rate'] * 100:.1f}%、"
                    f"营收 ¥{item['revenue']:,.2f}"
                )
            if len(negative) > 5:
                neg_lines.append(f"（仅列前 5，共 {len(negative)} 道）")
            neg_lines.append("")
            neg_lines.append(
                # 🔴 2026-08-12 架构收口 C: 限定语**由出处字段生成**, 不再手写。
                #    这条路的口径是「排除未覆盖成本的菜」→ 端出去的每个数都是
                #    账上的 (MEASURED), 覆盖率只是说明「结论盖住了多少营收」。
                #    另一条路 (dish_margin) 对同一批菜给的是 ESTIMATED 的数,
                #    两个数字不同是**对的**, 但必须各自带得出出处。
                provenance_qualifier(PROV_MEASURED, coverage_ratio=coverage_ratio)
            )
            neg_answer = "\n".join(neg_lines)
        else:
            # 🔴 限定语由出处字段生成(同上)。⛔ 不能写成隐式字符串拼接的一段 ——
            #    `f"..." provenance_qualifier(...)` 是语法错(第一版就这么写的)。
            neg_answer = (
                f"{window_label}可计算毛利的 {len(with_cost)} 道菜品中，"
                f"**没有毛利为负的菜**，按已覆盖成本口径没有单品在亏钱。\n\n"
                + provenance_qualifier(PROV_MEASURED, coverage_ratio=coverage_ratio)
            )
        return OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title=f"负毛利菜品排查（{window_label}）",
            answer_text=neg_answer,
            charts=[], kpis=[],
            meta={"negative_margin_check": True, "negative_count": len(negative),
                  "marginInvariantPass": True,
                  "marginFormula": "毛利=可计算毛利的营收-对应菜品成本"},
        )

    # 拖毛利归因（确定性）: which single dish drags the BLENDED margin most, and
    # whether it's a rate problem or a volume-amplified one — answers "哪个菜拖
    # 毛利，是率低还是量大" directly instead of only listing bottom-rate dishes.
    dragger = (
        _compute_margin_dragger(
            ranking_with_cost,
            ranking_avg_margin,
            ranking_rev_with_cost,
        )
        if ranking_avg_margin is not None else None
    )
    dragger_text = (
        f"\n\n最拖整体毛利的菜品（拖累 = 营收占比 × 毛利率差）:\n\n"
        f"- **{dragger['name']}**: 毛利率 {dragger['margin_rate'] * 100:.1f}% "
        f"(低于主菜平均 {ranking_avg_margin * 100:.1f}%), "
        f"占主菜已覆盖营收 {dragger['share'] * 100:.1f}%"
        f" → 主因：{dragger['cause']}"
        if dragger else ""
    )

    top_text = "\n".join([
        f"{i+1}. {'**' + e['name'] + '**' if i == 0 else e['name']}: 营收 ¥{e['revenue']:.2f} / 成本 ¥{e['total_cost']:.2f} / "
        f"毛利 ¥{e['gross_profit']:.2f} ({e['margin_rate'] * 100:.1f}%)"
        for i, e in enumerate(top_slice)
    ]) or "- 暂无成本完整、可计算毛利的菜品。"
    invalid_cost_count = len([e for e in enriched if e["invalid_cost"]])
    missing_cost_count = len([
        e for e in enriched if not e["has_cost"] and not e["invalid_cost"]
    ])
    exclusion_notes: List[str] = []
    if missing_cost_count > 0:
        exclusion_notes.append(f"{missing_cost_count} 个菜品缺少完整成本")
    if invalid_cost_count > 0:
        # 🔴 owner 2026-08-14 判据三: **指名道姓**, 不许只报个数。
        #    「1 个菜品成本值明显异常」对店长不产生任何动作 —— 他不知道是哪道菜、
        #    也不知道该改什么。日结那条路早就点名了(generic_answer), 问答这条
        #    只数了个数 —— 又一处「两条路说的不是同一件事」。
        # ⚠️ 均价与判据用的是**同一个** revenue/qty, ⛔ 不在这里另算一个。
        worst = sorted(
            (e for e in enriched if e["invalid_cost"]),
            key=lambda e: (e["invalid_cost_value"] or 0), reverse=True,
        )[:2]
        named = "、".join(
            f"{e['name']}（成本卡 ¥{(e['invalid_cost_value'] or 0):,.2f} 一份，"
            f"实际卖 ¥{(e['revenue'] / e['qty']) if e['qty'] else 0:,.2f}）"
            for e in worst
        )
        exclusion_notes.append(
            f"{invalid_cost_count} 个菜品成本值明显异常：{named}，多半是单位记错"
            f"（比如一袋当成一份），改好就会自动算回来"
            if named else f"{invalid_cost_count} 个菜品成本值明显异常")
    if primary_excluded_count > 0:
        exclusion_notes.append(
            f"{primary_excluded_count} 个米饭/附属用品仅计入总额、不参与主菜排名与建议"
        )
    missing_note = (
        f"\n\n> 数据说明：{'；'.join(exclusion_notes)}，已从毛利、毛利率、排名和图表中排除；"
        "请补齐配方，或复核食材单位和最近进价后重新计算。"
        if exclusion_notes else ""
    )

    charts: List[Dict[str, Any]] = []
    reference_lines = _parse_margin_reference_lines(query_text)
    reference_requested = any(token in query_text for token in ("参照线", "计划线", "预警线", "计划值", "预警值"))
    if trend_requested and monthly_pos_rows:
        monthly_entries: List[Dict[str, Any]] = []
        for row in monthly_pos_rows:
            entry = _build_margin_entries([row], cretas_map, cost_map)[0]
            entry["month"] = _date_text(row["month"])[:7]
            if (
                entry["has_cost"]
                and (
                    dish_scope_row is not None
                    or _primary_dish_ranking_exclusion_reason(entry) is None
                )
            ):
                monthly_entries.append(entry)
        months = sorted({entry["month"] for entry in monthly_entries})
        per_dish = any(token in query_text for token in ("菜品", "每个菜", "每道菜", "各菜"))
        trend_series: List[Dict[str, Any]] = []
        if per_dish:
            selected_names = [item["name"] for item in top_slice[:min(top_n, 10)]]
            for dish_name in selected_names:
                values = []
                for month in months:
                    rows = [item for item in monthly_entries if item["month"] == month and item["name"] == dish_name]
                    revenue = sum(item["revenue"] for item in rows)
                    profit = sum(float(item["gross_profit"]) for item in rows)
                    values.append(round(profit / revenue * 100, 2) if revenue > 0 else None)
                trend_series.append({"name": dish_name, "type": "line", "data": values, "connectNulls": False})
        else:
            values = []
            for month in months:
                rows = [item for item in monthly_entries if item["month"] == month]
                revenue = sum(item["revenue"] for item in rows)
                profit = sum(float(item["gross_profit"]) for item in rows)
                values.append(round(profit / revenue * 100, 2) if revenue > 0 else None)
            trend_series.append({"name": "加权毛利率", "type": "line", "data": values, "connectNulls": False})
        if trend_series and reference_lines:
            trend_series[0]["markLine"] = {
                "silent": True,
                "symbol": "none",
                "lineStyle": {"type": "dashed"},
                "data": reference_lines,
            }
        if months and trend_series:
            charts.append({
                "chartType": "line",
                "title": "菜品毛利率月度趋势" if per_dish else "整体毛利率月度趋势",
                "xAxis": {"data": months},
                "yAxis": {"type": "value", "name": "毛利率(%)"},
                "series": trend_series,
            })
    elif top_slice:
        charts.append({
            "chartType": "bar",
            "title": f"毛利前 {len(top_slice)} 名菜品（{window_label}）",
            "xAxis": {"data": [item["name"] for item in top_slice]},
            "series": [
                {"name": "营收", "type": "bar", "data": [item["revenue"] for item in top_slice]},
                {"name": "毛利", "type": "bar", "data": [item["gross_profit"] for item in top_slice]},
            ],
        })

    low_margin_text = "\n".join([
        f"- {e['name']}: 毛利率 {e['margin_rate'] * 100:.1f}%, 营收 ¥{e['revenue']:,.2f}"
        for e in low_margin
    ]) or "- 暂无明显低毛利菜品。"
    margin_text = f"{avg_margin * 100:.1f}%" if avg_margin is not None else "暂不可计算"
    reference_note = ""
    if reference_requested and not reference_lines:
        reference_note = "\n> 你还没有提供计划值或预警值，我没有擅自添加参照线；请直接回复具体百分比。"
    elif reference_lines:
        reference_note = "\n> 图中参照线：" + "、".join(
            f"{line['name']} {line['yAxis']:.1f}%" for line in reference_lines
        )
    trend_note = (
        f"\n> 趋势图仅展示毛利额前 {len(top_slice[:min(top_n, 10)])} 个成本完整菜品，避免图例失真。"
        if trend_requested and any(token in query_text for token in ("菜品", "每个菜", "每道菜", "各菜"))
        else ""
    )
    trend_basis_note = (
        "\n> 历史趋势按当前成本卡估算，用于观察售价和销售结构变化；若历史成本曾调整，需补充历史成本后再做精确复盘。"
        if trend_requested else ""
    )
    joint_priority_text = ""
    if (
        any(token in query_text for token in ("菜品销量", "销量", "销售量"))
        and any(token in query_text for token in ("毛利", "毛利率", "利润"))
        and ranking_with_cost
    ):
        promote = max(
            ranking_with_cost,
            key=lambda item: (item["qty"] * max(float(item["margin_rate"]), 0.0), item["gross_profit"]),
        )
        repair_pool = [
            item for item in ranking_with_cost
            if ranking_avg_margin is not None
            and float(item["margin_rate"]) < ranking_avg_margin
        ]
        repair = max(repair_pool, key=lambda item: item["qty"], default=None)
        joint_priority_text = (
            "\n\n销量与毛利联合优先级与依据：\n"
            f"1. 优先推广 {promote['name']}：销量 {_format_sales_quantity(promote['qty'])}，"
            f"毛利率 {promote['margin_rate'] * 100:.1f}%，依据是销量与毛利率的联合贡献最高。"
        )
        if repair is not None and repair["name"] != promote["name"]:
            joint_priority_text += (
                f"\n2. 优先整改 {repair['name']}：销量 {_format_sales_quantity(repair['qty'])}，"
                f"毛利率 {repair['margin_rate'] * 100:.1f}%，依据是销量不低但毛利率低于整体平均。"
            )
        joint_priority_text += "\n3. 其余菜品先小范围验证，不按单一销量或单一毛利率直接下架。"
    prohibited_actions_text = ""
    if prohibited_actions_requested:
        prohibited_actions_text = (
            "\n\n今天先不要做：\n"
            "1. 不要只看单一毛利率就批量下架菜品，必须同时看销量和绝对毛利。\n"
            "2. 不要在成本缺失或成本异常的菜品上直接调价。\n"
            "3. 不要做全店无差别打折，先在高销量低毛利菜品上小范围验证。"
        )
    # 🔴 owner 2026-08-13: 合计用实收、逐菜用原价, **拆开就加不起来**
    #    (prod 实测差 31,125.59 = 折扣额)。店长点开按菜看、一加发现比合计高,
    #    会觉得系统在骗他。⛔ 这一句不是挂账, 是本次修复的组成部分:
    #    不说明就会立刻产生一个更隐蔽的不一致 —— 数字都对, 但对不上, 而且没人说。
    # ⛔ 用已建好的机制不新建: 标 ESTIMATED, 限定语由 `provenance` 生成。
    per_dish_no_discount_note = ""
    if total_paid_rev is not None and abs(total_rev_items - total_paid_rev) > 0.01:
        # ⚠️ basis 必须是**名词短语** —— 限定语模板是「用{basis}估算，…」。
        #    prod 实测塞了一整句进去, 读成:
        #      「用这里没扣折扣 —— 折扣是整单的…会比合计高估算，这部分是估出来的」
        #    ⛔ 同一个错我在成本卡那条 basis 上already 犯过一次并记过 ——
        #       写 basis 时**把模板套一遍读出声**, 不要只看这个串本身。
        per_dish_no_discount_note = provenance_qualifier(
            PROV_ESTIMATED, "没扣折扣的单菜价",
        ).rstrip() + (
            "（折扣是整单的，摊不到单道菜；所以下面按菜加起来会比上面的合计高。）\n")

    # 🔴 owner 2026-08-14: **两条路的开头必须一致。**
    #    改之前这里第 3 行是「已覆盖部分毛利 ¥124,071.85，加权毛利率 82.5%」——
    #    **零限定**。而 82.5% 是在那 40.2% 上算的, 店长最可能的读法是
    #    「这生意真赚钱」。日结上逐条修掉的四条事实, 问答一条都没有。
    # ⛔ **不复制**日结的拼装 —— 复制就是第三份。构造一个 `CellResult` 调
    #    `generic_answer.render_headline`, 那是唯一的一处。
    # ⚠️ **合计毛利率不单独成行**: 一个在 40.2% 营收上算出来的比率, 无论旁边
    #    写什么, 单独成行就会被读成「这个店的毛利率」。单品毛利率照旧保留
    #    (每一道都有成本卡, 有依据)。挂账「覆盖率下限」仍然挂着。
    from smartbi.gold.restaurant.generic_answer import render_headline
    from smartbi.gold.restaurant.generic_executor import (
        _COST_CARD_BASIS, _DISCOUNT_ALLOC_BASIS, CellResult, _cost_gaps)
    from smartbi.gold.restaurant.metric_registry import DERIVED as _REG_DERIVED

    # 🔴 2026-08-14 订正: 我上一轮说「折扣摊派是日结那条路的估算成分, 问答不是」——
    #    **读错了**。上面 4249-4251 那三行就是按明细金额比例摊, 与 `_covered_margin`
    #    逐行相同; 抬头那个数走的是 `covered_net_rev`(已扣折扣的净营收)。
    #    owner 抓到它的判据: **两条路 diff = 0.0 —— 一边摊了一边没摊, 不可能相等。**
    #    ⇒ 那是**少了一条事实**, 不是不同的依据。basis 与日结取同两个常量。
    # ⚠️ 「折扣是整单的, 摊不到单道菜」那句说的是下面**按菜清单**, 不是抬头这个合计。
    #    我把两件事并成了一个理由。
    _headline_basis = f"{_COST_CARD_BASIS}、{_DISCOUNT_ALLOC_BASIS}"

    _gaps = _cost_gaps([
        {"name": e["normalized_name"], "qty": e["qty"], "revenue": e["revenue"],
         "unit_cost": e["food_cost_unit"] if e["has_cost"] else None}
        for e in enriched
    ])
    _headline_cell = CellResult(
        "gross_profit", _REG_DERIVED["gross_profit"].label, "all", "summary",
        "money", [{"gross_profit": total_profit}], (), "",
        PROV_ESTIMATED, _headline_basis, coverage_ratio,
        tuple(), tuple(_gaps), float(total_rev_items or 0),
    )
    # ⚠️ `_offers` 是**结构化**的(按钮读它的 `kind`), 正文那几行由
    #    `_offer_texts` 从同一份产出渲染 —— 一份来源两个消费者。
    #    ⛔ 不要把这里改回「拍平成字符串」: 那样按钮侧会静默拿到 str,
    #       `offer.get(...)` 抛异常被 except 吞掉, 表现是**按钮永远不出现**。
    _offers = _build_qa_fill_offers(_headline_cell)
    _fill_offer_lines = "".join(
        f"{i}. {o}\n" for i, o in enumerate(_offer_texts(_offers), 1))

    answer = (
        f"**菜品毛利分析（{window_label}）**\n"
        + render_headline(_headline_cell, window_label) + "\n"
        # ⚠️ 只报覆盖率百分比, ⛔ 不再把 item 口径的「可计算毛利的营收」金额摆出来:
        #    它是 749,009 而上面的实收是 717,883 —— 并排放会读成「其中」比「全部」还大。
        f"- 实收营收 **¥{total_rev:,.2f}**\n\n"
        # 🔴 2026-08-14: 这里原来印的是**全额实收** ¥373,832.93, 而结果是
        #    **覆盖部分**的毛利 —— 店长照着减一遍得 347,578.81, 与上面那行
        #    124,071.85 对不上。**答案自己跟自己打架**, 比不给过程更糟。
        # ⛔ 分子分母同源: 减数是覆盖部分的净营收, 不是全店实收。
        f"计算过程：`毛利 ¥{total_profit:,.2f} = 有成本卡那部分的净营收 "
        f"¥{covered_net_rev:,.2f} − 对应菜品成本 ¥{total_cost:,.2f}`\n\n"
        # ⛔ 不写「口径」——它在 INTERNAL_VOCAB 里, sanitize 会替换成「计算方法」,
        #    于是 prod 上打出「计算计算方法：」。⚠️ 自己敲进源码的串不许靠 sanitize 兜。
        f"> 怎么算的：毛利 = 有成本卡那部分的净营收 − 对应菜品成本；"
        f"期间与菜品范围完全一致。\n"
        f"{per_dish_no_discount_note}"
        f"> {len(with_cost)}/{len(enriched)} 个销售菜品有完整成本数据。{reference_note}{trend_note}{trend_basis_note}\n\n"
        f"毛利前 {len(top_slice)} 名菜品（按绝对毛利）:\n\n{top_text}{dragger_text}\n\n"
        f"需要关注的低毛利菜品:\n\n{low_margin_text}{joint_priority_text}{prohibited_actions_text}\n\n"
        # 🔴 owner 2026-08-14: 「建议动作」换成 `build_fill_offers` 的产出。
        #    改之前是「对缺成本菜品补齐配方和最近进价」这种泛泛之词, 而日结那边
        #    早就是「先补这 3 道(菜名), 40.2% → 47.7%」—— 同一件事一边具体一边空泛。
        # ⛔ 拿不到开价时**退回原来那三条**, 不留一个空的「建议动作:」标题。
        f"建议动作:\n{_fill_offer_lines}{missing_note}"
    )

    if prohibited_actions_requested:
        low_margin_name = (
            low_margin[0]["name"]
            if low_margin else "暂无可确认对象"
        )
        # ⚠️ 这一支同样不许让**合计毛利率**单独成行 —— 判断依据里给出毛利与
        #    覆盖率就够了。单品毛利率在下面各条里照旧保留(有成本卡, 有依据)。
        answer = (
            f"**{window_label}先不要做三件事。**判断依据："
            + render_headline(_headline_cell, window_label) + "\n\n"
            "1. 不要按单一毛利率批量下架。"
            f"适用前提：同时核对销量、绝对毛利和门店差异；当前低毛利候选是{low_margin_name}。"
            "风险：可能误删引流款或套餐关键菜。最小验证：选一家店、一个菜、观察一周再决定。\n"
            "2. 不要给全店统一涨价或打折。"
            "适用前提：先确认单菜价格、销量与促销记录。"
            "风险：会把高毛利菜的收益一并让掉，或伤害高频菜销量。"
            "最小验证：只选一类高销量低毛利菜做小幅调整并设对照。\n"
            "3. 不要对成本不完整的菜直接做利润决策。"
            f"适用前提：先补齐成本；当前缺成本 {missing_cost_count} 个、成本异常 {invalid_cost_count} 个。"
            "风险：毛利率会被高估或低估。最小验证：补齐配方和最近进价后再重算。"
            f"{missing_note}"
        )
        charts = []

    response_title = f"菜品毛利分析 ({window_label})"
    response_kpis = [
        {"title": "总营收", "value": f"¥{total_rev:,.0f}", "rawValue": total_rev},
        {"title": "总毛利", "value": f"¥{total_profit:,.0f}", "rawValue": total_profit},
        {"title": "平均毛利率", "value": margin_text, "rawValue": avg_margin},
        {"title": "最赚菜品", "value": top_slice[0]["name"] if top_slice else "—", "rawValue": 0},
    ]
    scoped_entry = (
        enriched[0]
        if dish_scope_row is not None and enriched
        else None
    )
    if dish_scope_row is not None:
        # Sheet 7/22 菜品链: 点名单菜时先给该菜的销量/营收头行, 再接毛利正文,
        # 数字全部来自同一 POS 行, 不会跨窗口混算。
        answer = (
            f"「{dish_scope_row['dish_name']}」{window_label}销量 "
            f"**{_format_sales_quantity(dish_scope_row['total_qty'])} 份**、"
            f"营收 **¥{float(dish_scope_row['total_revenue'] or 0):,.2f}**、"
            f"覆盖订单 {int(dish_scope_row['bills'] or 0)} 单。\n\n" + answer
        )
        # 「米饭赚钱吗」— 盈亏问必须给判定句, 不让用户自己从毛利率倒推 (R20)。
        if _profit_intent(query_text)[1]:
            if scoped_entry and scoped_entry.get("margin_rate") is not None:
                rate = float(scoped_entry["margin_rate"])
                verdict = "在赚钱" if rate > 0 else ("基本打平" if rate == 0 else "在亏钱")
                answer = (
                    f"**结论：按已覆盖成本口径，「{dish_scope_row['dish_name']}」"
                    f"{window_label}{verdict}（毛利率 {rate * 100:.1f}%）。**\n\n" + answer
                )
            else:
                answer = (
                    f"**结论：「{dish_scope_row['dish_name']}」成本未覆盖，"
                    "无法判断是否赚钱；请先补齐配方和最近进价。**\n\n" + answer
                )
        elif scoped_entry is not None:
            projected = _scoped_dish_metric_answer(
                scoped_entry,
                window_label=window_label,
                query=query_text,
                peer_sales_quantities=[
                    float(row.get("total_qty") or 0.0)
                    for row in benchmark_pos_rows
                    if row.get("product_id") != dish_scope_row.get("product_id")
                    and _primary_dish_ranking_exclusion_reason(row) is None
                ],
            )
            if projected:
                answer = projected
                charts = []
                asks_sales = any(token in query_text for token in (
                    "菜品销量", "销量", "销售量", "卖了多少", "卖出",
                ))
                asks_cost = any(token in query_text for token in (
                    "菜品成本", "食材成本", "配方成本", "单品成本", "成本",
                ))
                asks_margin = any(token in query_text for token in (
                    "毛利率", "毛利", "利润", "盈利", "赚钱", "亏钱", "亏损",
                ))
                asks_revenue = any(token in query_text for token in (
                    "营收", "营业额", "销售额", "销售收入", "营业收入", "流水",
                ))
                metric_kind = (
                    "营收" if asks_revenue and not asks_cost and not asks_margin
                    else "销量" if asks_sales and not asks_cost and not asks_margin
                    else "成本" if asks_cost and not asks_margin
                    else "毛利"
                )
                if any(token in query_text for token in (
                    "为什么", "原因", "怎么回事", "为何",
                )):
                    response_title = f"菜品{metric_kind}原因拆解 ({window_label})"
                elif any(token in query_text for token in (
                    "怎么优化", "如何优化", "优化", "改善", "怎么办", "怎么做",
                    "怎么提升", "如何提升", "提升", "下一步", "先做什么",
                )):
                    response_title = f"菜品{metric_kind}优化建议 ({window_label})"
                else:
                    response_title = f"菜品{metric_kind} ({window_label})"

                if metric_kind in ("销量", "营收"):
                    response_kpis = [
                        {
                            "title": "营收" if metric_kind == "营收" else "销量",
                            "value": (
                                f"¥{float(scoped_entry.get('revenue') or 0):,.2f}"
                                if metric_kind == "营收"
                                else f"{_format_sales_quantity(scoped_entry.get('qty'))} 份"
                            ),
                            "rawValue": (
                                scoped_entry.get("revenue")
                                if metric_kind == "营收"
                                else scoped_entry.get("qty")
                            ),
                        },
                        {
                            "title": "销量" if metric_kind == "营收" else "营收",
                            "value": (
                                f"{_format_sales_quantity(scoped_entry.get('qty'))} 份"
                                if metric_kind == "营收"
                                else f"¥{float(scoped_entry.get('revenue') or 0):,.2f}"
                            ),
                            "rawValue": (
                                scoped_entry.get("qty")
                                if metric_kind == "营收"
                                else scoped_entry.get("revenue")
                            ),
                        },
                        {
                            "title": "订单数",
                            "value": f"{int(scoped_entry.get('bills') or 0)} 单",
                            "rawValue": scoped_entry.get("bills"),
                        },
                    ]
                elif metric_kind == "成本":
                    unit_cost = scoped_entry.get("food_cost_unit")
                    total_cost = scoped_entry.get("total_cost")
                    response_kpis = [
                        {
                            "title": "单份成本",
                            "value": (
                                f"¥{float(unit_cost):,.2f}"
                                if unit_cost is not None else "不可计算"
                            ),
                            "rawValue": unit_cost,
                        },
                        {
                            "title": "总成本",
                            "value": (
                                f"¥{float(total_cost):,.2f}"
                                if total_cost is not None else "不可计算"
                            ),
                            "rawValue": total_cost,
                        },
                    ]
                else:
                    response_kpis = [
                        {
                            "title": "菜品营收",
                            "value": f"¥{float(scoped_entry.get('revenue') or 0):,.2f}",
                            "rawValue": scoped_entry.get("revenue"),
                        },
                        {
                            "title": "菜品毛利",
                            "value": (
                                f"¥{float(scoped_entry['gross_profit']):,.2f}"
                                if scoped_entry.get("gross_profit") is not None
                                else "不可计算"
                            ),
                            "rawValue": scoped_entry.get("gross_profit"),
                        },
                        {
                            "title": "菜品毛利率",
                            "value": (
                                f"{float(scoped_entry['margin_rate']) * 100:.1f}%"
                                if scoped_entry.get("margin_rate") is not None
                                else "不可计算"
                            ),
                            "rawValue": scoped_entry.get("margin_rate"),
                        },
                    ]
    # 🔴 「还能怎么拆 / 拆完了」写进**正文**, 不只放按钮(owner 2026-08-14 裁定 3)。
    #    ① 按钮的 label 得先在正文里出现过 —— 按钮是入口不是新内容
    #    ② **日结推送那种形态没有按钮, 只有正文** —— 只放按钮那条路上的人
    #       永远不知道还能拆, 也不知道已经拆完了
    _used_dims = ("all", "product")
    _drill_note = _drilldown_note("gross_profit", _used_dims,
                                  "RESTAURANT_OPS_GROSS_MARGIN")
    if _drill_note:
        answer = f"{answer}\n\n{_drill_note}"
    _actions = _build_follow_up_actions(
        offers=_offers, answer_text=answer,
        used_dimensions=_used_dims,
        meta_for_suppression={"rbac_masked": not can_view_prices},
        resolver_code="RESTAURANT_OPS_GROSS_MARGIN",
        # 🔴 按钮携带**它长出来那一屏**的时间窗(owner 2026-08-15)。
        #    ⛔ 不让它去 history 里猜: 实测那次串正是「一屏最近30天的数上
        #       点按钮, 得到本月」。见 `follow_up_actions.contextualize`。
        window_label=window_label,
    )
    return OpsAnswer(
        code="RESTAURANT_OPS_GROSS_MARGIN",
        title=response_title,
        answer_text=answer,
        charts=charts,
        kpis=response_kpis,
        meta={
            "window_days": analysis_days, "top_n": top_n,
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "missing_cost_count": missing_cost_count,
            "invalid_cost_count": invalid_cost_count,
            "total_dishes": len(enriched),
            "cost_covered_revenue": total_rev_with_cost,
            # 🔴 合计层的两个数**具名带出去** —— 两条路对账那道闸要读它们。
            #    不带的话闸只能去 kpis 里按标签猜, 而 kpis 没有 label,
            #    结果是闸每天报 rc=2(读不到) —— 实测第一次跑就是这样。
            #    ⛔ 闸读不到 = 闸不存在, 只是它诚实地说了「没量到」。
            "aggregate_gross_profit": total_profit,
            "aggregate_paid_revenue": total_rev,
            "covered_cost": total_rev_with_cost - total_profit,
            "marginFormula": "毛利=可计算毛利的营收-对应菜品成本",
            "targetDish": dish_scope_row["dish_name"] if dish_scope_row is not None else None,
            "marginInvariantPass": margin_invariant_pass,
            "cost_coverage_ratio": coverage_ratio,
            "trend_requested": trend_requested,
            "reference_lines": reference_lines,
            "low_margin_dishes": [e["name"] for e in low_margin],
            "focus_entity": (
                {
                    "type": "dish",
                    "id": dish_scope_row["product_id"],
                    "name": dish_scope_row["dish_name"],
                }
                if dish_scope_row is not None else None
            ),
            "target_dish_metrics": (
                {
                    "qty": scoped_entry.get("qty"),
                    "revenue": scoped_entry.get("revenue"),
                    "bills": scoped_entry.get("bills"),
                    "unit_cost": scoped_entry.get("food_cost_unit"),
                    "total_cost": scoped_entry.get("total_cost"),
                    "gross_profit": scoped_entry.get("gross_profit"),
                    "margin_rate": scoped_entry.get("margin_rate"),
                }
                if scoped_entry is not None else None
            ),
            # 🔴 T1/T2 按钮走 `meta` —— `_execution_receipt` 里 `dict(meta)`
            #    整份透传到 `result_meta`, 再由
            #    `restaurant_intent_service._suggested_followups` 合进
            #    `suggested_followups`。⛔ 不新开管道(那正是第一版三层都断的成因)。
            # ⚠️ `used_dimensions` 是**这次回答实际渲染过的**维度, 登记表不知道 ——
            #    所以它写在这里, 紧挨着渲染它的代码。候选集合仍然是反查的。
            #    本答案: 全店合计(all) + 逐菜清单(product)。
            "follow_up_actions": list(_actions),
        },
    )


def _drilldown_note(metric_key, used_dimensions, resolver_code=None) -> str:
    """正文里那句「还能怎么拆 / 已经拆完了」。⛔ 逻辑在 `follow_up_actions`。"""
    from smartbi.gold.restaurant.degrade_guard import degrade_on_error
    from smartbi.gold.restaurant.follow_up_actions import drilldown_note

    return degrade_on_error(
        DEGRADE_DRILL_NOTE, "",
        lambda: drilldown_note(metric_key, used_dimensions, resolver_code),
        what="下钻提示")


def _build_follow_up_actions(*, offers, answer_text, used_dimensions,
                             meta_for_suppression=None, resolver_code=None,
                             window_label=""):
    """毛利问答的追问按钮。⛔ 逻辑在 `follow_up_actions`, 这里只接线。"""
    from smartbi.gold.restaurant.degrade_guard import degrade_on_error
    from smartbi.gold.restaurant.follow_up_actions import build_actions

    return degrade_on_error(
        DEGRADE_FOLLOWUP_ACTIONS, (),
        lambda: build_actions(
            metric_key="gross_profit",
            used_dimensions=used_dimensions,
            offers=offers,
            answer_text=answer_text,
            meta=meta_for_suppression,
            resolver_code=resolver_code,
            window_label=window_label,
        ),
        what="追问按钮")


#: 三个降级点的名字。⚠️ 常量而不是字面量 —— 断言按名字查计数器,
#: 拼错了断言会静默变成「查一个不存在的点」, 恒绿。
DEGRADE_QA_OFFERS = "gross_margin.qa_offers"
DEGRADE_DRILL_NOTE = "gross_margin.drilldown_note"
DEGRADE_FOLLOWUP_ACTIONS = "gross_margin.follow_up_actions"


async def resolve_store_margin(
    smartbi_pool, factory_id: str, days: int = 30, top_n: int = 10,
    *, role: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    comparison_date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    query: Optional[str] = None,
    store_id: Optional[str] = None,
    store_name: Optional[str] = None,
    store_mention: Optional[str] = None,
    store_mentions: Optional[List[str]] = None,
    dish_mention: Optional[str] = None,
    requested_metrics: Sequence[str] = (),
    analysis_action: Optional[str] = None,
    ranking_direction: Optional[str] = None,
    window_label: Optional[str] = None,
    **semantic_slots: Any,
) -> OpsAnswer:
    """Per-store gross margin: fact_pos_item × fact_pos_transaction.store_id
    × dim_store.name × recipe food_cost. Connects POS bill → store → dish → cost
    for the chain-owner's core question "which store is most profitable".

    RBAC (2026-07-08 audit fix): 同 resolve_gross_margin —— PRICE_VIEW_ROLES
    门, 此前无 role 参数导致任何角色可见未脱敏门店营收/毛利金额。
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    requested_metric_set = {
        metric for metric in requested_metrics if isinstance(metric, str)
    }
    requested_ranking_limit = semantic_slots.get("ranking_limit")
    if not isinstance(requested_ranking_limit, int) or requested_ranking_limit <= 0:
        requested_ranking_limit = None
    requested_window_label = window_label
    if not (bool(role) and role in PRICE_VIEW_ROLES):
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title="门店毛利对比",
            answer_text=(
                "门店营收和毛利金额属于成本/价格权限，当前角色不能查看金额。"
                "可以先看经营量视角：问「哪家店订单最多」看门店单量对比；"
                "如需毛利数据请联系管理员开通价格查看权限。"
            ),
            charts=[],
            kpis=[],
            meta={"rbac_masked": True},
        )
    selected_store_names: Optional[List[str]] = None
    if store_mentions and not store_id and not store_name:
        selected_store_names = []
        for mention in store_mentions[:8]:
            matched_names = await _canonicalize_store_mention(
                smartbi_pool, factory_id, mention,
            )
            if len(matched_names) != 1:
                detail = (
                    f"匹配到：{'、'.join(matched_names[:3])}"
                    if matched_names
                    else "没有匹配到门店"
                )
                return OpsAnswer(
                    code="RESTAURANT_OPS_STORE_MARGIN",
                    title="请确认多家门店",
                    answer_text=(
                        f"「{mention}」{detail}。请使用门店完整名称重新选择；"
                        "多家门店只有全部名称唯一匹配后才会执行比较。"
                    ),
                    charts=[], kpis=[],
                    meta={
                        "store_mention_ambiguous": mention,
                        "candidates": matched_names,
                    },
                )
            if matched_names[0] not in selected_store_names:
                selected_store_names.append(matched_names[0])
        if len(selected_store_names) < 2:
            store_name = selected_store_names[0] if selected_store_names else None

    if store_mention and not store_id and not store_name and not selected_store_names:
        matched_names = await _canonicalize_store_mention(
            smartbi_pool, factory_id, store_mention,
        )
        if len(matched_names) == 1:
            store_name = matched_names[0]
        elif len(matched_names) > 1:
            options = "、".join(matched_names[:3])
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title="请确认门店",
                answer_text=(
                    f"「{store_mention}」匹配到多家门店：{options}。"
                    "请指定其中一家后再查询该店毛利率。"
                ),
                charts=[], kpis=[],
                meta={"store_mention_ambiguous": store_mention,
                      "candidates": matched_names},
            )
        else:
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{store_mention}毛利分析",
                answer_text=(
                    f"**没有找到名为「{store_mention}」的门店**，"
                    "不能计算该店的毛利或毛利率，也不会退化为全店榜或其他门店的数据。\n\n"
                    "请核对门店名称；可以先问「哪家店业绩最好」查看现有门店。"
                ),
                charts=[], kpis=[],
                meta={"store_not_found": store_mention},
            )

    exact_start = date_range[0] if date_range else None
    exact_end = date_range[1] if date_range else None
    if (exact_start is None) != (exact_end is None):
        raise ValueError("date_range must include both start and end")

    comparison_start = comparison_date_range[0] if comparison_date_range else None
    comparison_end = comparison_date_range[1] if comparison_date_range else None
    if (comparison_start is None) != (comparison_end is None):
        raise ValueError("comparison_date_range must include both start and end")
    if comparison_date_range and not (exact_start and exact_end):
        raise ValueError("comparison_date_range requires an explicit primary date_range")

    if comparison_start and comparison_end:
        resolved_spec = _resolve_sales_query_spec(query)
        primary = await resolve_store_margin(
            smartbi_pool,
            factory_id,
            days,
            top_n,
            role=role,
            date_range=(exact_start, exact_end),
            query=query,
            store_id=store_id,
            store_name=store_name,
            store_mentions=selected_store_names,
        )
        primary_actual_start = _date_from_any(
            primary.meta.get("window_start")
        )
        primary_actual_end = _date_from_any(primary.meta.get("window_end"))
        aligned_to_actual_progress = False
        effective_comparison_label = resolved_spec.comparison_label
        if (
            resolved_spec.comparison_label
            and resolved_spec.date_range == (exact_start, exact_end)
            and resolved_spec.comparison_range
            == (comparison_start, comparison_end)
            and primary_actual_start is not None
            and primary_actual_end is not None
            and (
                resolved_spec.comparison_label.endswith("同期")
                or (
                    exact_end == date.today()
                    and primary_actual_end < exact_end
                )
            )
        ):
            start_offset = max(
                0,
                (primary_actual_start - exact_start).days,
            )
            end_offset = max(
                start_offset,
                (primary_actual_end - exact_start).days,
            )
            aligned_start = comparison_start + timedelta(
                days=start_offset,
            )
            aligned_end = min(
                comparison_end,
                comparison_start + timedelta(days=end_offset),
            )
            if aligned_start <= aligned_end:
                comparison_start, comparison_end = (
                    aligned_start,
                    aligned_end,
                )
                aligned_to_actual_progress = True
                if not resolved_spec.comparison_label.endswith("同期"):
                    effective_comparison_label = (
                        f"{resolved_spec.comparison_label}同期"
                    )
        comparison = await resolve_store_margin(
            smartbi_pool,
            factory_id,
            days,
            top_n,
            role=role,
            date_range=(comparison_start, comparison_end),
            query=query,
            store_id=store_id,
            store_name=store_name,
            store_mentions=selected_store_names,
        )
        primary_label = _range_text(
            primary_actual_start or exact_start,
            primary_actual_end or exact_end,
        )
        comparison_label = _range_text(comparison_start, comparison_end)
        asks_store_revenue = any(
            token in (query or "")
            for token in ("营收", "营业额", "销售额", "销售收入", "流水", "收入")
        )
        asks_store_orders = any(token in (query or "") for token in ("订单", "单量"))
        asks_store_avg_ticket = "客单价" in (query or "")
        asks_store_sales_volume = any(
            token in (query or "") for token in ("销量", "销售量", "售出数量")
        )
        asks_store_margin = any(
            token in (query or "")
            for token in ("毛利", "利润", "盈利", "亏损", "赚钱", "亏钱")
        )

        if (
            asks_store_revenue
            or asks_store_orders
            or asks_store_avg_ticket
            or asks_store_sales_volume
        ) and not asks_store_margin:
            if asks_store_revenue:
                metric_key, metric_label = "revenue", "营收"
            elif asks_store_avg_ticket:
                metric_key, metric_label = "avg_ticket", "客单价"
            elif asks_store_orders:
                metric_key, metric_label = "bills", "订单数"
            else:
                metric_key, metric_label = "qty", "销量"

            def _period_store_values(answer: OpsAnswer) -> Dict[str, float]:
                values: Dict[str, float] = {}
                for store in answer.meta.get("stores", []):
                    name = str(store.get("name") or "").strip()
                    if not name:
                        continue
                    if metric_key == "avg_ticket":
                        bills = float(store.get("bills") or 0)
                        value = float(store.get("revenue") or 0) / bills if bills > 0 else 0.0
                    else:
                        value = float(store.get(metric_key) or 0)
                    values[name] = value
                return values

            primary_values = _period_store_values(primary)
            comparison_values = _period_store_values(comparison)
            if not primary_values or not comparison_values:
                missing_periods = []
                if not primary_values:
                    missing_periods.append(primary_label)
                if not comparison_values:
                    missing_periods.append(comparison_label)
                return OpsAnswer(
                    code="RESTAURANT_OPS_STORE_MARGIN",
                    title=f"门店{metric_label}跨期对比",
                    answer_text=(
                        f"{'、'.join(missing_periods)}缺少可用的门店{metric_label}数据，"
                        f"因此不能比较{primary_label}和{comparison_label}。"
                        "没有用单一月份、全部历史或其他指标替代。"
                    ),
                    charts=[],
                    kpis=[],
                    meta={
                        "store_metric_comparison": metric_key,
                        "comparisonComplete": False,
                        "primaryRange": [exact_start.isoformat(), exact_end.isoformat()],
                        "comparisonRange": [
                            comparison_start.isoformat(),
                            comparison_end.isoformat(),
                        ],
                        "scope_matches_request": True,
                    },
                )
            store_names = sorted(
                set(primary_values) | set(comparison_values),
                key=lambda name: primary_values.get(name, 0.0),
                reverse=True,
            )
            requested_limit = ranking_limit(query or "", top_n)
            if (
                any(token in (query or "") for token in ("各门店", "全部门店", "所有门店"))
                and not _RANK_LIMIT_RE.search(query or "")
            ):
                requested_limit = len(store_names)
            selected_names = store_names[:requested_limit]
            display_primary_label = (
                resolved_spec.window_label
                if resolved_spec.date_range == (exact_start, exact_end)
                else primary_label
            )
            display_comparison_label = (
                effective_comparison_label
                if (
                    resolved_spec.comparison_range
                    == (comparison_start, comparison_end)
                    or aligned_to_actual_progress
                )
                and effective_comparison_label
                else comparison_label
            )

            def _metric_text(value: float) -> str:
                if metric_key in {"revenue", "avg_ticket"}:
                    return f"¥{value:,.2f}"
                if metric_key == "bills":
                    return f"{int(value):,} 单"
                return f"{value:,.0f} 份"

            lines = [
                f"**各门店{metric_label}对比：{display_primary_label} vs {display_comparison_label}**",
                "",
            ]
            for index, name in enumerate(selected_names, 1):
                current_value = primary_values.get(name, 0.0)
                baseline_value = comparison_values.get(name, 0.0)
                delta = current_value - baseline_value
                direction = "增加" if delta > 0 else "减少" if delta < 0 else "持平"
                lines.append(
                    f"{index}. {name} — {display_primary_label} {_metric_text(current_value)}；"
                    f"{display_comparison_label} {_metric_text(baseline_value)}；"
                    f"{direction} {_metric_text(abs(delta))}"
                )
            lines.extend([
                "",
                f"> 两个时间窗分别为 {primary_label} 和 {comparison_label}；"
                "所有门店均按相同指标口径比较，没有用单一月份替代。",
            ])
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"门店{metric_label}跨期对比",
                answer_text="\n".join(lines),
                charts=[{
                    "chartType": "bar",
                    "title": f"门店{metric_label}跨期对比",
                    "xAxis": {"data": selected_names},
                    "series": [
                        {
                            "name": display_primary_label,
                            "type": "bar",
                            "data": [primary_values.get(name, 0.0) for name in selected_names],
                        },
                        {
                            "name": display_comparison_label,
                            "type": "bar",
                            "data": [comparison_values.get(name, 0.0) for name in selected_names],
                        },
                    ],
                }] if selected_names else [],
                kpis=[],
                meta={
                    "store_metric_comparison": metric_key,
                    "comparisonComplete": True,
                    "primaryRange": [exact_start.isoformat(), exact_end.isoformat()],
                    "comparisonRange": [
                        comparison_start.isoformat(),
                        comparison_end.isoformat(),
                    ],
                    "primaryLabel": display_primary_label,
                    "comparisonLabel": display_comparison_label,
                    "storeCount": len(store_names),
                    "scope_matches_request": True,
                },
            )

        def _period_margin(answer: OpsAnswer) -> Tuple[Optional[float], Optional[float], Optional[str]]:
            target = answer.meta.get("targetStore")
            if isinstance(target, dict):
                return (
                    target.get("gross_profit"),
                    target.get("margin_rate"),
                    target.get("name"),
                )
            return answer.meta.get("totalProfit"), answer.meta.get("avgRate"), None

        primary_profit, primary_rate, resolved_name = _period_margin(primary)
        comparison_profit, comparison_rate, comparison_name = _period_margin(comparison)
        target_label = resolved_name or comparison_name or store_name or (
            f"门店 {store_id}" if store_id else "全部门店"
        )
        missing_labels = []
        if primary_profit is None or primary_rate is None:
            missing_labels.append(primary_label)
        if comparison_profit is None or comparison_rate is None:
            missing_labels.append(comparison_label)
        if missing_labels:
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{target_label}毛利区间比较",
                answer_text=(
                    f"{target_label}在{'、'.join(missing_labels)}缺少可靠计算毛利所需的销售与成本数据，"
                    f"因此不能比较{primary_label}和{comparison_label}的毛利与毛利率。"
                    "没有用其他日期、营业额或其他指标替代。请先补齐对应日期的账单、配方和最近进价。"
                ),
                charts=[],
                kpis=[],
                meta={
                    "primaryRange": [exact_start.isoformat(), exact_end.isoformat()],
                    "comparisonRange": [comparison_start.isoformat(), comparison_end.isoformat()],
                    "targetStoreId": store_id,
                    "targetStoreName": store_name,
                    "comparisonComplete": False,
                },
            )
        delta = float(primary_profit) - float(comparison_profit)
        direction = "高于" if delta > 0 else "低于" if delta < 0 else "持平"
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"{target_label}毛利区间比较",
            answer_text=(
                f"**前一范围毛利{direction}后一范围 ¥{abs(delta):,.2f}。**\n\n"
                f"- {target_label}在{primary_label}的毛利为 **¥{float(primary_profit):,.2f}**，"
                f"毛利率 {float(primary_rate) * 100:.1f}%\n"
                f"- 在{comparison_label}的毛利为 **¥{float(comparison_profit):,.2f}**，"
                f"毛利率 {float(comparison_rate) * 100:.1f}%"
            ),
            charts=[{
                "chartType": "bar",
                "title": f"{target_label}毛利区间比较",
                "xAxis": {"data": [primary_label, comparison_label]},
                "series": [{"name": "毛利", "type": "bar", "data": [primary_profit, comparison_profit]}],
            }],
            kpis=[],
            meta={
                "primaryRange": [exact_start.isoformat(), exact_end.isoformat()],
                "comparisonRange": [comparison_start.isoformat(), comparison_end.isoformat()],
                "targetStoreId": store_id,
                "targetStoreName": target_label,
                "comparisonComplete": True,
            },
        )

    async with smartbi_pool.acquire() as conn:
        # Session-level GUC (is_local=false): asyncpg runs each statement in
        # its own implicit transaction, so a LOCAL set_config is discarded
        # before the next fetch and RLS visibility silently depends on
        # whatever GUC the pooled connection last carried (real incident:
        # store-scoped RES_3101_009 reads returned 0 rows).
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        # Per-store per-dish aggregation — need this granularity to compute
        # cost correctly (dish-level food_cost × qty sold at each store).
        store_dish_rows = await conn.fetch(
            """
            WITH anchor_scope AS MATERIALIZED (
                -- ⚡ 二次优化(2026-08-08): 上一版把 MAX 改成倒序取第一条后,
                -- 这一条**仍要 808ms** —— EXPLAIN 显示 `JOIN scope` 逼出嵌套循环,
                -- 扫完 236,954 行、`Rows Removed by Join Filter: 1,066,466`,
                -- 倒序短路完全失效。把 scope 提成 MATERIALIZED CTE(先算成 10 行)
                -- 再用 IN, 外层就能沿 date 索引反扫、命中即停。实测 828ms -> 0ms。
                -- ⛔ 试过 `= ANY(array_agg(...))`: 2100ms 更差, 数组比较挡住索引。
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
            ), anchor AS (
                -- Scalar-subquery form so COALESCE short-circuits: when the
                -- caller pins an exact end date the expensive scan is skipped.
                -- 「最后一天有菜品明细的交易日」这个语义逐字保留。
                SELECT COALESCE($4::date, (
                    SELECT t2.date
                      FROM fact_pos_transaction t2
                      JOIN dim_store s2
                        ON s2.store_id = t2.store_id
                       AND s2.factory_id = t2.factory_id
                     WHERE t2.store_id IN (SELECT store_id FROM anchor_scope)
                       AND t2.factory_id = $1
                       AND ($5::text IS NULL OR s2.store_id::text = $5::text)
                       AND ($6::text IS NULL OR s2.name = $6::text)
                       AND ($7::text[] IS NULL OR s2.name = ANY($7::text[]))
                       AND EXISTS (
                             SELECT 1
                               FROM fact_pos_item i2
                               JOIN dim_product p2
                                 ON p2.product_id = i2.product_id
                                AND p2.factory_id = i2.factory_id
                              WHERE i2.transaction_id = t2.id
                                AND i2.factory_id = $1
                           )
                     ORDER BY t2.date DESC
                     LIMIT 1
                )) AS end_date
            )
            -- ⚡ 先聚合再关联维表(2026-08-08 实测 3489ms -> 2346ms, 结果集逐行相同)。
            -- 原式把 dim_store / dim_product 直接 JOIN 进 94.7 万行的明细扫描里,
            -- EXPLAIN 显示 **dim_product 只有 10 行却被读了 262 万次缓冲**
            -- (嵌套循环逐行回表), fact_pos_transaction 336 万、fact_pos_item 98 万。
            -- 先按 (门店, 菜品ID) 聚合成 ~100 行, 再去关联两张小维表 —— 维表只查一遍。
            -- ⛔ 门店过滤($5/$6/$7)必须留在**外层**: 它按 dim_store.name 过滤,
            --    而内层只有 store_id, 挪进去会拿不到名字。
            , agg AS (
                SELECT t.store_id, i.product_id,
                       SUM(i.qty)::float AS qty,
                       SUM(i.amount)::float AS revenue,
                       COUNT(DISTINCT i.transaction_id)::int AS bills,
                       MIN(t.date) AS window_start,
                       MAX(t.date) AS window_end
                  FROM fact_pos_item i
                  JOIN fact_pos_transaction t ON t.id = i.transaction_id
                  CROSS JOIN anchor
                 WHERE i.factory_id = $1 AND t.factory_id = $1
                   AND anchor.end_date IS NOT NULL
                   AND t.store_id IN (SELECT store_id FROM anchor_scope)
                   AND t.date >= COALESCE($3::date, anchor.end_date - ($2::int))
                   AND t.date <= COALESCE($4::date, anchor.end_date)
                 GROUP BY t.store_id, i.product_id
            )
            SELECT s.store_id, s.name AS store_name,
                   p.name AS dish_name, p.normalized_name,
                   SUM(a.qty)::float AS qty,
                   SUM(a.revenue)::float AS revenue,
                   SUM(a.bills)::int AS bills,
                   MIN(a.window_start) AS window_start,
                   MAX(a.window_end) AS window_end
              FROM agg a
              JOIN dim_product p
                ON p.product_id = a.product_id
               AND p.factory_id = $1
              JOIN dim_store s
                ON s.store_id = a.store_id
               AND s.factory_id = $1
             WHERE ($5::text IS NULL OR s.store_id::text = $5::text)
               AND ($6::text IS NULL OR s.name = $6::text)
               AND ($7::text[] IS NULL OR s.name = ANY($7::text[]))
             GROUP BY s.store_id, s.name, p.name, p.normalized_name
            """,
            factory_id, days, exact_start, exact_end, store_id, store_name,
            selected_store_names,
        )
        store_bill_rows = await conn.fetch(
            """
            WITH anchor AS (
                -- Scalar-subquery form: the MAX scan only runs when no exact
                -- end date was supplied (see the dish query above).
                -- ⚡ 语义不变、耗时 4331ms -> 索引反扫(2026-08-08 实测)。
                -- COALESCE 短路保留(给了显式 end date 就不跑扫描), 但**没给时**
                -- 原式仍要 MAX 全扫 94.7 万行明细。改成 ORDER BY date DESC LIMIT 1:
                -- 走 idx_fact_pos_txn_factory_store_date 反扫, 命中即停。
                -- 「最后一天有菜品明细的交易日」这个语义逐字保留。
                SELECT COALESCE($4::date, (
                    SELECT t2.date
                      FROM fact_pos_transaction t2
                      JOIN dim_store s2
                        ON s2.store_id = t2.store_id
                       AND s2.factory_id = t2.factory_id
                     WHERE t2.factory_id = $1
                       AND ($5::text IS NULL OR s2.store_id::text = $5::text)
                       AND ($6::text IS NULL OR s2.name = $6::text)
                       AND ($7::text[] IS NULL OR s2.name = ANY($7::text[]))
                       AND EXISTS (
                             SELECT 1
                               FROM fact_pos_item i2
                               JOIN dim_product p2
                                 ON p2.product_id = i2.product_id
                                AND p2.factory_id = i2.factory_id
                              WHERE i2.transaction_id = t2.id
                                AND i2.factory_id = $1
                           )
                     ORDER BY t2.date DESC
                     LIMIT 1
                )) AS end_date
            ), scope AS (
                SELECT DISTINCT store_id FROM agg_daily WHERE factory_id = $1
                UNION
                SELECT DISTINCT store_id
                  FROM fact_pos_transaction
                 WHERE factory_id = $1
                   AND NOT EXISTS (SELECT 1 FROM agg_daily WHERE factory_id = $1)
            )
            SELECT t.store_id, COUNT(DISTINCT t.id)::int AS bills
              FROM fact_pos_transaction t
              JOIN dim_store s
                ON s.store_id = t.store_id
               AND s.factory_id = t.factory_id
              JOIN scope ON scope.store_id = t.store_id
              CROSS JOIN anchor
             WHERE t.factory_id = $1
               AND anchor.end_date IS NOT NULL
               AND t.date >= COALESCE($3::date, anchor.end_date - ($2::int))
               AND t.date <= COALESCE($4::date, anchor.end_date)
               AND ($5::text IS NULL OR s.store_id::text = $5::text)
               AND ($6::text IS NULL OR s.name = $6::text)
               AND ($7::text[] IS NULL OR s.name = ANY($7::text[]))
             GROUP BY t.store_id
            """,
            factory_id, days, exact_start, exact_end, store_id, store_name,
            selected_store_names,
        )

    if store_id or store_name or selected_store_names:
        store_dish_rows = [
            row for row in store_dish_rows
            if (not store_id or str(row["store_id"]) == str(store_id))
            and (not store_name or str(row["store_name"]) == store_name)
            and (
                not selected_store_names
                or str(row["store_name"]) in selected_store_names
            )
        ]
        scoped_store_ids = {str(row["store_id"]) for row in store_dish_rows}
        store_bill_rows = [
            row for row in store_bill_rows
            if str(row["store_id"]) in scoped_store_ids
        ]

    if not store_dish_rows:
        requested_label = (
            _range_text(exact_start, exact_end)
            if exact_start and exact_end else f"近 {days} 天"
        )
        target_label = store_name or (f"门店 {store_id}" if store_id else "门店")
        scoped_no_data = bool(store_id or store_name or selected_store_names)
        no_data_ranking_direction = (
            ranking_direction or dish_ranking_direction(query)
        )
        if no_data_ranking_direction:
            rank_label = (
                "销量最高"
                if no_data_ranking_direction == "best"
                else "销量最低"
            )
            selected_stores = selected_store_names or (
                [store_name] if store_name else []
            )
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{target_label}菜品销量排行（{requested_label}）",
                answer_text=(
                    f"指定的{target_label}在{requested_label}没有可用的菜品销售记录，"
                    f"因此不能生成{rank_label}榜。"
                    "本次没有改成毛利、营业额或其他日期的数据；"
                    "请选择有销售数据的门店，或调整时间范围后重试。"
                ),
                charts=[],
                kpis=[],
                meta={
                    "window_days": days,
                    "window_start": (
                        _date_text(exact_start) if exact_start else None
                    ),
                    "window_end": _date_text(exact_end) if exact_end else None,
                    "scope_matches_request": True,
                    "no_pos_data": True,
                    "dish_ranking": no_data_ranking_direction,
                    "ranking_limit": requested_ranking_limit or ranking_limit(query),
                    "selected_stores": selected_stores,
                    "targetStoreId": store_id,
                    "targetStoreName": store_name,
                    "storeScoped": scoped_no_data,
                },
            )
        sales_overview_requested = bool(
            requested_metric_set.intersection({
                "revenue", "orders", "sales_volume",
            })
            or any(
                token in (query or "")
                for token in ("销售情况", "经营情况", "生意情况")
            )
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=(
                f"{target_label}销售情况（{requested_label}）"
                if sales_overview_requested
                else f"{target_label}毛利分析（{requested_label}）"
            ),
            answer_text=((
                (
                    f"指定的{target_label}在{requested_label}没有可用的销售记录，"
                    "因此无法给出该店的营收、订单和菜品销量。"
                    "本次没有改成全部门店、其他日期或毛利数据；"
                    "请确认门店名称或编号，并检查对应日期的营业流水是否已同步。"
                )
                if sales_overview_requested
                else (
                    f"指定的{target_label}在{requested_label}没有可用的销售与成本数据，"
                    "不能计算该店毛利、毛利率或排名，也没有退化为全店榜、"
                    "其他日期或营业额指标。请确认门店名称或编号，并补齐对应日期的"
                    "营业账单、配方和最近进价。"
                )
            ) if scoped_no_data else (
                f"{requested_label}没有可用的收银销售数据，没有用其他时间范围替代。"
                "请先确认营业账单已同步。"
            )),
            charts=[], kpis=[],
            meta={
                "window_days": days,
                "window_start": _date_text(exact_start) if exact_start else None,
                "window_end": _date_text(exact_end) if exact_end else None,
                "scope_matches_request": True,
                "no_pos_data": True,
                "targetStoreId": store_id,
                "targetStoreName": store_name,
                "storeScoped": bool(store_id or store_name),
            },
        )

    window_start = min((r["window_start"] for r in store_dish_rows if r["window_start"]), default=None)
    window_end = max((r["window_end"] for r in store_dish_rows if r["window_end"]), default=None)
    window_label = (
        requested_window_label
        or (
            f"{_date_text(exact_start)} 至 {_date_text(exact_end)}"
            if exact_start and exact_end
            else _actual_window_text(window_start, window_end, days)
        )
    )

    # A store choice on a dish-ranking question must change the actual SQL
    # result scope, not merely survive as conversational text.  Rank dishes
    # independently inside each selected store; two or more selected stores
    # therefore become an explicit same-window comparison.
    query_text = (query or "").strip()
    scoped_dish_rank_direction = (
        ranking_direction or dish_ranking_direction(query_text)
    )
    if scoped_dish_rank_direction and (store_id or store_name or selected_store_names):
        requested_limit = requested_ranking_limit or ranking_limit(query_text)
        explicit_exclusions = ranking_exclusions(query_text)
        grouped_rows: Dict[str, List[Any]] = {}
        excluded_item_count = 0
        excluded_item_reasons: Dict[str, int] = {}
        for row in store_dish_rows:
            reason = _primary_dish_ranking_exclusion_reason(row)
            normalized_name = re.sub(
                r"\s+",
                "",
                str(row.get("dish_name") or ""),
            )
            if (
                not reason
                and any(
                    re.sub(r"\s+", "", excluded) == normalized_name
                    for excluded in explicit_exclusions
                )
            ):
                reason = "user_excluded"
            if reason:
                excluded_item_count += 1
                excluded_item_reasons[reason] = (
                    excluded_item_reasons.get(reason, 0) + 1
                )
                continue
            grouped_rows.setdefault(str(row["store_name"]), []).append(row)

        rank_label = (
            "销量最高"
            if scoped_dish_rank_direction == "best"
            else "销量最低"
        )
        selected_scope_names = (
            list(selected_store_names)
            if selected_store_names
            else ([store_name] if store_name else [])
        )
        single_store_scope = (
            len(grouped_rows) == 1
            and (
                len(selected_scope_names) == 1
                or (not selected_scope_names and bool(store_id))
            )
        )
        single_store_name = (
            selected_scope_names[0]
            if selected_scope_names
            else next(iter(grouped_rows))
        )
        lines = [
            (
                f"**{window_label}{single_store_name}菜品销量排行"
                f"（{rank_label}前 {requested_limit}）：**"
                if single_store_scope
                else (
                    f"**{window_label}所选门店菜品对比"
                    f"（每店{rank_label}前 {requested_limit}）：**"
                )
            ),
            "",
        ]
        ranked_entities: List[Dict[str, Any]] = []
        chart_labels: List[str] = []
        chart_values: List[float] = []
        for current_store in sorted(grouped_rows):
            ranked_rows = sorted(
                grouped_rows[current_store],
                key=lambda item: float(item["qty"] or 0),
                reverse=(scoped_dish_rank_direction == "best"),
            )[:requested_limit]
            if not ranked_rows:
                continue
            lines.append(f"**{current_store}**")
            # ⚠️ 这个循环同时在建 ranked_entities / chart_* —— 表格只接管**文本**
            #    那一半, 结构化产物原样留在循环里。把两者一起重写最容易漏掉后者,
            #    而漏了不会报错: 答案照常显示, 只是图表和结构化上下文空了。
            store_table_rows = []
            for index, row in enumerate(ranked_rows, 1):
                quantity = float(row["qty"] or 0)
                store_table_rows.append([
                    index, row["dish_name"],
                    _format_sales_quantity(quantity),
                    f"¥{float(row['revenue'] or 0):,.2f}",
                ])
                ranked_entities.append({
                    "type": "dish",
                    "id": row.get("product_id"),
                    "name": str(row["dish_name"]),
                    "rank": index,
                    "store_name": current_store,
                    "sales_volume": quantity,
                    "revenue": float(row["revenue"] or 0),
                    "bill_count": int(row["bills"] or 0),
                })
                chart_labels.append(
                    str(row["dish_name"])
                    if single_store_scope
                    else f"{current_store}·{row['dish_name']}"
                )
                chart_values.append(quantity)
            lines.extend(_markdown_table(
                ["#", "菜品", "销量（份）", "营收"],
                store_table_rows, right_align={2, 3}))
            lines.append("")

        if not ranked_entities:
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"所选门店菜品销量排行（{window_label}）",
                answer_text=(
                    f"{window_label}所选门店没有可用于主菜销量排行的记录。"
                    "米饭、餐具、纸巾等附属项及您明确排除的项目不会被重新放回榜单。"
                ),
                charts=[],
                kpis=[],
                meta={
                    "dish_ranking": scoped_dish_rank_direction,
                    "ranking_limit": requested_limit,
                    "excluded_entities": explicit_exclusions,
                    "excluded_item_count": excluded_item_count,
                    "excluded_item_reasons": excluded_item_reasons,
                    "ranked_entities": [],
                    "selected_stores": selected_store_names or (
                        [store_name] if store_name else []
                    ),
                    "scope_matches_request": True,
                    "no_primary_dish_data": True,
                    "window_label": window_label,
                },
            )

        lines.append(
            (
                "> 已按该门店和同一时间口径统计；"
                if single_store_scope
                else f"> 已按同一时间口径比较 {len(grouped_rows)} 家门店；"
            )
            + "米饭、餐具、纸巾等附属项默认不进入主菜榜。"
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=(
                f"{single_store_name}菜品销量排行（{window_label}）"
                if single_store_scope
                else f"所选门店菜品销量排行（{window_label}）"
            ),
            answer_text="\n".join(lines),
            charts=[{
                "chartType": "bar",
                "title": (
                    f"{single_store_name}菜品销量（{window_label}）"
                    if single_store_scope
                    else f"所选门店菜品销量（{window_label}）"
                ),
                "xAxis": {"data": chart_labels},
                "series": [{
                    "name": "销量",
                    "type": "bar",
                    "data": chart_values,
                }],
            }],
            kpis=[],
            meta={
                "dish_ranking": scoped_dish_rank_direction,
                "ranking_limit": requested_limit,
                "excluded_entities": explicit_exclusions,
                "excluded_item_count": excluded_item_count,
                "excluded_item_reasons": excluded_item_reasons,
                "ranked_entities": ranked_entities,
                "focus_entity": ranked_entities[0],
                "selected_stores": selected_store_names or (
                    [store_name] if store_name else []
                ),
                "compare_stores": len(grouped_rows) > 1,
                "scope_matches_request": True,
                "window_label": window_label,
            },
        )

    # 菜名 → 成本键。⛔ 与毛利问答/日结读**同一份**实现, 见
    #    `restaurant_cost_mapping.resolve_cost_keys` 顶部。
    #    改之前这里是第三份内联的 cretas 查询 —— 三份实现就有三种「这家店有哪些
    #    菜算得出成本」, 按门店的毛利加总不等于全店毛利, 而没有任何东西会报错。
    from smartbi.gold.restaurant.restaurant_cost_mapping import (
        CostKeySourceUnavailable,
        resolve_cost_keys,
    )
    async with smartbi_pool.acquire() as _map_conn:
        await _map_conn.execute(
            "SELECT set_config('app.factory_id', $1, false)", factory_id)
        try:
            name_to_pk = await resolve_cost_keys(_map_conn, factory_id)
        except CostKeySourceUnavailable as exc:
            logger.error("[store_margin] 成本键权威来源不可用 factory=%s: %s",
                         factory_id, exc)
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title="门店毛利",
                answer_text=(
                    "现在算不了各店毛利 —— 菜品成本的数据源连不上。\n\n"
                    "这不是「你的菜没有成本卡」，是我这边取不到，"
                    "所以我不给你一个可能偏高的数。稍后再问一次。"
                ),
                charts=[], kpis=[],
                meta={"no_data": True, "reason": "cost_key_source_unavailable"},
            )

    cost_by_pk: Dict[str, float] = {}
    if name_to_pk:
        async with smartbi_pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            cr = await conn.fetch(
                """
                SELECT product_source_pk, food_cost::float AS c
                  FROM agg_restaurant_product_cost
                 WHERE factory_id = $1
                   AND product_source_pk = ANY($2::text[])
                   AND """ + _CARD_PRESENT_BARE + """
                """,
                factory_id, list(name_to_pk.values()),
            )
            cost_by_pk = {r["product_source_pk"]: r["c"] for r in cr}

    # R18 店×菜下钻: 「哪家店的米饭卖得最好」/「X店的米饭卖得怎么样」—
    # store_dish_rows 本就是 店×菜 粒度, 点名菜时限域直答, 不再诚实拒答。
    if dish_mention:
        matched = [
            r for r in store_dish_rows
            if dish_mention in (r["dish_name"] or "")
            or dish_mention in (r["normalized_name"] or "")
            or ((r["normalized_name"] or "") and r["normalized_name"] in dish_mention)
        ]
        if not matched:
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{dish_mention} — 门店销量查询",
                answer_text=(
                    f"{window_label}没有找到「{dish_mention}」的门店销售记录，"
                    "不能给出该菜的门店拆分，也不会用其他菜品或榜单替代。"
                    "请核对菜名；可以先问「哪个菜卖得最好」查看在售菜品。"
                ),
                charts=[], kpis=[],
                meta={"dish_not_found": dish_mention},
            )
        spec_names = sorted({r["dish_name"] for r in matched})
        spec_note = (
            f"（含 {len(spec_names)} 个规格：{'、'.join(spec_names[:4])}）"
            if len(spec_names) > 1 else ""
        )
        per_store: Dict[Any, Dict[str, Any]] = {}
        for r in matched:
            entry = per_store.setdefault(r["store_id"], {
                "name": r["store_name"], "qty": 0.0, "revenue": 0.0,
            })
            entry["qty"] += float(r["qty"] or 0)
            entry["revenue"] += float(r["revenue"] or 0)
        asks_cost = any(token in query_text for token in (
            "菜品成本", "食材成本", "配方成本", "单品成本", "单份成本",
            "单位成本", "每份成本", "成本",
        ))
        asks_margin, asks_profitability = _profit_intent(query_text)
        asks_sales = any(token in query_text for token in (
            "菜品销量", "销量", "销售量", "卖了多少", "卖出",
        ))
        asks_revenue = any(token in query_text for token in (
            "营收", "营业额", "销售额", "销售收入", "营业收入", "流水",
        ))
        asks_diagnosis = any(token in query_text for token in (
            "为什么", "原因", "怎么回事", "为何",
        ))
        asks_optimization = any(token in query_text for token in (
            "怎么优化", "如何优化", "优化", "改善", "怎么办", "怎么做",
            "怎么提升", "如何提升", "提升", "下一步", "先做什么",
        ))
        if (
            asks_cost
            or asks_margin
            or asks_sales
            or asks_revenue
            or asks_diagnosis
            or asks_optimization
        ):
            # STORE_MARGIN owns the concrete store×dish grain. Reuse the same
            # cost-completeness and plausibility rules as store aggregation,
            # but render only the named dish requested by the current plan.
            # Sales diagnosis/optimisation belongs here too: the previous
            # cost-only gate dropped the requested action after a user selected
            # one or more concrete stores, so the Answer Contract correctly
            # rejected an otherwise valid four-turn dish chain.
            scoped_margin_entries = _aggregate_store_margin_entries(
                matched,
                name_to_pk,
                cost_by_pk,
            )
            margin_by_store = {
                str(item["name"]): item for item in scoped_margin_entries
            }
            requested_store_names = (
                list(selected_store_names)
                if selected_store_names
                else [store_name] if store_name else [
                    str(item["name"]) for item in scoped_margin_entries
                ]
            )

            def _metric_entry(item: Dict[str, Any]) -> Tuple[Dict[str, Any], bool]:
                qty = float(item.get("qty") or 0.0)
                revenue = float(item.get("revenue") or 0.0)
                coverage = float(item.get("cost_coverage_ratio") or 0.0)
                complete = bool(
                    coverage >= 0.999999
                    and item.get("gross_profit") is not None
                    and qty > 0
                )
                total_cost = float(item.get("cost") or 0.0) if complete else None
                gross_profit = (
                    revenue - total_cost
                    if total_cost is not None
                    else None
                )
                return {
                    "name": dish_mention,
                    "qty": qty,
                    "revenue": revenue,
                    "bills": int(item.get("bills") or 0),
                    "food_cost_unit": (
                        total_cost / qty
                        if total_cost is not None and qty > 0
                        else None
                    ),
                    "total_cost": total_cost,
                    "gross_profit": gross_profit,
                    "margin_rate": (
                        gross_profit / revenue
                        if gross_profit is not None and revenue > 0
                        else None
                    ),
                    "has_cost": complete,
                    "invalid_cost": bool(item.get("invalid_cost_dishes")),
                }, complete

            scoped_results: List[Tuple[str, Dict[str, Any], bool]] = []
            for requested_store in requested_store_names:
                store_item = margin_by_store.get(str(requested_store))
                if store_item is None:
                    scoped_results.append((
                        str(requested_store),
                        {
                            "name": dish_mention,
                            "qty": 0.0,
                            "revenue": 0.0,
                            "bills": 0,
                            "food_cost_unit": None,
                            "total_cost": None,
                            "gross_profit": None,
                            "margin_rate": None,
                            "has_cost": False,
                            "invalid_cost": False,
                        },
                        False,
                    ))
                    continue
                metric_entry, complete = _metric_entry(store_item)
                scoped_results.append((
                    str(store_item["name"]),
                    metric_entry,
                    complete,
                ))

            if store_id or store_name:
                target_store, metric_entry, complete = scoped_results[0]
                projected = _scoped_dish_metric_answer(
                    metric_entry,
                    window_label=window_label,
                    query=query_text,
                    peer_sales_quantities=[
                        float(row.get("qty") or 0.0)
                        for row in store_dish_rows
                        if str(row.get("store_name") or "") == target_store
                        and dish_mention not in str(row.get("dish_name") or "")
                        and _primary_dish_ranking_exclusion_reason(row) is None
                    ],
                )
                cross_grain_read = _asks_store_revenue_then_dish_sales(
                    query_text,
                    [target_store],
                    dish_mention,
                )
                store_overall_revenue = None
                if cross_grain_read:
                    store_overall_revenue = sum(
                        float(row.get("revenue") or 0.0)
                        for row in store_dish_rows
                        if str(row.get("store_name") or "") == target_store
                    )
                    projected = (
                        f"「{target_store}」{window_label}门店整体营业额 "
                        f"**¥{store_overall_revenue:,.2f}**。\n\n"
                        f"{projected}"
                    )
                if complete and asks_profitability:
                    rate = float(metric_entry["margin_rate"])
                    verdict = (
                        "在赚钱"
                        if rate > 0
                        else "基本打平"
                        if rate == 0
                        else "在亏钱"
                    )
                    projected = (
                        f"**结论：按已覆盖成本口径，"
                        f"「{target_store}」的「{dish_mention}」{window_label}"
                        f"{verdict}（毛利率 {rate * 100:.1f}%）。**\n\n"
                        f"{projected}"
                    )
                if not projected:
                    projected = (
                        f"「{dish_mention}」{window_label}在「{target_store}」"
                        f"销量 {metric_entry['qty']:,.0f} 份、"
                        f"营收 ¥{metric_entry['revenue']:,.2f}，但成本数据不完整，"
                        "当前无法可靠计算单份成本、总成本、毛利和毛利率；"
                        "不会用全部门店或其他菜品替代。"
                    )
                title_suffix = (
                    "优化建议"
                    if asks_optimization
                    else "原因拆解"
                    if asks_diagnosis
                    else "成本毛利"
                    if asks_cost or asks_margin
                    else "销量营收"
                )
                return OpsAnswer(
                    code="RESTAURANT_OPS_STORE_MARGIN",
                    title=f"{target_store} · {dish_mention}{title_suffix}",
                    answer_text=f"门店范围：**{target_store}**\n\n{projected}",
                    charts=[],
                    kpis=[],
                    meta={
                        "store_dish": dish_mention,
                        "targetStoreName": target_store,
                        "stores": [{"name": target_store}],
                        "focus_entity": {
                            "type": "dish",
                            "name": dish_mention,
                        },
                        "costCoverageRatio": (
                            1.0 if complete else float(
                                margin_by_store[target_store].get(
                                    "cost_coverage_ratio",
                                ) or 0.0
                            )
                        ),
                        "marginInvariantPass": True,
                        "marginFormula": "毛利=同一门店同一菜品营收-对应菜品成本",
                        "scope_matches_request": True,
                        "crossGrainRead": cross_grain_read,
                        "storeOverallRevenue": store_overall_revenue,
                    },
                )

            # Two or more explicitly selected stores mean a same-window
            # comparison. Keep missing cost visible per store instead of
            # dropping that store or silently aggregating all selected stores.
            comparison_label = (
                "销量优化对比"
                if asks_optimization and asks_sales and not asks_margin
                else "销量原因拆解"
                if asks_diagnosis and asks_sales and not asks_margin
                else "成本毛利对比"
                if asks_cost or asks_margin
                else "销量营收对比"
            )
            lines = [
                f"**「{dish_mention}」所选门店{comparison_label}（{window_label}）：**",
                "",
            ]
            result_stores: List[Dict[str, Any]] = []
            for target_store, metric_entry, complete in scoped_results:
                if complete and asks_margin:
                    lines.append(
                        f"- **{target_store}**：营收 ¥{metric_entry['revenue']:,.2f}、"
                        f"成本 ¥{metric_entry['total_cost']:,.2f}、"
                        f"毛利 ¥{metric_entry['gross_profit']:,.2f}、"
                        f"毛利率 {metric_entry['margin_rate'] * 100:.1f}%"
                    )
                elif complete and asks_cost:
                    lines.append(
                        f"- **{target_store}**：销量 {metric_entry['qty']:,.0f} 份、"
                        f"单份食材成本 ¥{metric_entry['food_cost_unit']:,.2f}、"
                        f"总成本 ¥{metric_entry['total_cost']:,.2f}"
                    )
                elif complete:
                    lines.append(
                        f"- **{target_store}**：销量 {metric_entry['qty']:,.0f} 份、"
                        f"营收 ¥{metric_entry['revenue']:,.2f}、"
                        f"单份食材成本 ¥{metric_entry['food_cost_unit']:,.2f}"
                    )
                else:
                    if (
                        metric_entry["qty"] <= 0
                        and metric_entry["revenue"] <= 0
                    ):
                        lines.append(
                            f"- **{target_store}**：所选时间内没有该菜的销售记录，"
                            "无法计算成本和毛利"
                        )
                    else:
                        lines.append(
                            f"- **{target_store}**：销量 {metric_entry['qty']:,.0f} 份、"
                            f"营收 ¥{metric_entry['revenue']:,.2f}；成本数据不完整，"
                            "无法可靠计算成本和毛利"
                        )
                result_stores.append({
                    "name": target_store,
                    "revenue": metric_entry["revenue"],
                    "cost": metric_entry["total_cost"],
                    "gross_profit": metric_entry["gross_profit"],
                    "margin_rate": metric_entry["margin_rate"],
                })
            lines.extend([
                "",
                "> 各门店使用同一菜品、同一时间和同一成本口径；"
                "未把所选门店合并成全店结果。",
            ])
            if asks_optimization:
                lines.extend([
                    "",
                    "**优化动作：**",
                    "1. 先把所选门店作为同口径对照，核对该菜销量、平均实收价、"
                    "促销折扣和成本覆盖差异，不把门店差异直接说成因果。",
                    "2. 只在一家店或一个时段做小范围露出测试；"
                    "成本不完整时不直接多店同步调价、下架或扩大活动。",
                    "3. 使用同一观察周期复查各店销量、营收、单份成本、毛利额和毛利率，"
                    "目标改善且其他指标没有明显恶化后再扩大范围。",
                    "",
                    "**验证指标：**所选门店同一时间口径下的销量、平均实收价、"
                    "单份成本、毛利额和毛利率。",
                ])
            elif asks_diagnosis:
                lines.extend([
                    "",
                    "**原因拆解：**当前数据只能确认所选门店在同一时间口径下的"
                    "销量、营收和成本覆盖差异，不能证明业务因果。",
                    "如需解释差异，还要继续核对各店上架时长、售罄缺货、价格与促销、"
                    "时段曝光和评价数据。",
                ])
            title_suffix = (
                "优化建议"
                if asks_optimization
                else "原因拆解"
                if asks_diagnosis
                else "成本毛利对比"
                if asks_cost or asks_margin
                else "销量营收对比"
            )
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{dish_mention}所选门店{title_suffix}",
                answer_text="\n".join(lines),
                charts=[],
                kpis=[],
                meta={
                    "store_dish": dish_mention,
                    "stores": result_stores,
                    "selected_stores": requested_store_names,
                    "compare_stores": True,
                    "focus_entity": {
                        "type": "dish",
                        "name": dish_mention,
                    },
                    "marginInvariantPass": True,
                    "marginFormula": "毛利=同一门店同一菜品营收-对应菜品成本",
                    "scope_matches_request": True,
                },
            )
        if store_id or store_name:
            target = next(iter(per_store.values()))
            target_label = store_name or target["name"]
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{target_label} — {dish_mention}",
                answer_text=(
                    f"「{target_label}」的「{dish_mention}」{spec_note}"
                    f"在{window_label}销量 **{target['qty']:,.0f} 份**、"
                    f"营收 **¥{target['revenue']:,.2f}**。\n\n"
                    f"> 如需该菜全部门店合计或毛利口径，可问「{dish_mention}的销量」。"
                ),
                charts=[], kpis=[],
                meta={"store_dish": dish_mention, "targetStoreName": target_label,
                      "scope_matches_request": True},
            )
        ranked_dish_stores = sorted(
            per_store.values(), key=lambda e: e["qty"], reverse=True,
        )
        rank_lines = [f"**「{dish_mention}」{spec_note}各门店销量排行（{window_label}）：**", ""]
        rank_lines.extend(_markdown_table(
            ["#", "门店", "销量（份）", "营收"],
            [[idx, e["name"], f"{e['qty']:,.0f}", f"¥{e['revenue']:,.2f}"]
             for idx, e in enumerate(ranked_dish_stores[:5], 1)],
            right_align={2, 3}))
        rank_lines.append("")
        rank_lines.append(
            f"> 仅统计窗口内有该菜销售记录的 {len(ranked_dish_stores)} 家门店。"
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"{dish_mention} — 门店销量排行",
            answer_text="\n".join(rank_lines),
            charts=[], kpis=[],
            meta={"store_dish": dish_mention,
                  "store_count": len(ranked_dish_stores),
                  "scope_matches_request": True},
        )

    bill_count_by_store = {row["store_id"]: row["bills"] for row in store_bill_rows}
    store_list = _aggregate_store_margin_entries(
        store_dish_rows, name_to_pk, cost_by_pk, bill_count_by_store,
    )
    ranked_store_list = sorted(
        (store for store in store_list if store["gross_profit"] is not None),
        key=lambda item: item["gross_profit"],
        reverse=True,
    )
    top_slice = ranked_store_list[:top_n]

    total_rev = sum(s["revenue"] for s in store_list)
    total_rev_with_cost = sum(s["revenue_with_cost"] for s in store_list)
    total_profit = (
        sum(float(s["gross_profit"]) for s in ranked_store_list)
        if total_rev_with_cost > 0 else None
    )
    avg_rate = total_profit / total_rev_with_cost if total_profit is not None else None
    scope_matches_request = bool(
        exact_start is None
        or (
            window_start is not None and window_end is not None
            and window_start >= exact_start and window_end <= exact_end
        )
    )
    margin_invariant_pass = bool(
        total_profit is None
        or (
            math.isfinite(float(total_profit))
            and float(total_profit) <= float(total_rev_with_cost) + 0.01
        )
    )
    covered_cost = (
        total_rev_with_cost - float(total_profit)
        if total_profit is not None and margin_invariant_pass else None
    )
    if not margin_invariant_pass:
        logger.error(
            "[store_margin] blocked impossible aggregate factory=%s profit=%s covered_revenue=%s range=%s..%s",
            factory_id, total_profit, total_rev_with_cost, exact_start, exact_end,
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"门店毛利对比（{window_label}）",
            answer_text=(
                "毛利口径自检未通过：已覆盖毛利不能高于对应营收。"
                "本次没有展示异常金额、排名或图表，请先复核食材单位、最近进价和销售范围后重试。"
            ),
            charts=[],
            kpis=[],
            meta={
                "window_days": days,
                "requested_window_start": _date_text(exact_start) if exact_start else None,
                "requested_window_end": _date_text(exact_end) if exact_end else None,
                "scope_matches_request": True,
                "totalRevenueWithCost": total_rev_with_cost,
                "marginFormula": "毛利=可计算毛利的营收-对应菜品成本",
                "marginInvariantPass": False,
            },
        )
    coverage_ratio = total_rev_with_cost / total_rev if total_rev > 0 else 0.0
    invalid_cost_count = sum(s["invalid_cost_dishes"] for s in store_list)

    generic_store_sales_overview = any(
        token in query_text
        for token in ("销售情况", "经营情况", "生意情况")
    )
    asks_store_revenue = (
        "revenue" in requested_metric_set
        or any(
        token in query_text
        for token in ("营收", "营业额", "销售额", "销售收入", "流水", "收入")
        )
    )
    asks_store_orders = (
        "orders" in requested_metric_set
        or any(
        token in query_text
        for token in ("订单", "单量")
        )
    )
    asks_store_avg_ticket = "客单价" in query_text
    asks_store_sales_volume = (
        "sales_volume" in requested_metric_set
        or any(
        token in query_text
        for token in ("销量", "销售量", "售出数量")
        )
    )
    asks_store_margin = (
        bool({"gross_margin", "recipe_cost"}.intersection(requested_metric_set))
        or any(
        token in query_text
        for token in ("毛利", "利润", "盈利", "亏损", "赚钱", "亏钱")
        )
    )
    if generic_store_sales_overview and not asks_store_margin:
        asks_store_revenue = True
        asks_store_orders = True
        asks_store_sales_volume = True
    if (
        asks_store_revenue
        or asks_store_orders
        or asks_store_avg_ticket
        or asks_store_sales_volume
    ) and not asks_store_margin:
        if asks_store_revenue:
            metric_key, metric_label = "revenue", "营收"
        elif asks_store_avg_ticket:
            metric_key, metric_label = "avg_ticket", "客单价"
        elif asks_store_orders:
            metric_key, metric_label = "bills", "订单数"
        else:
            metric_key, metric_label = "qty", "销量"

        def _store_metric_value(store: Dict[str, Any]) -> float:
            if metric_key == "avg_ticket":
                bills = float(store.get("bills") or 0)
                return float(store.get("revenue") or 0) / bills if bills > 0 else 0.0
            return float(store.get(metric_key) or 0)

        ranked_by_requested_metric = sorted(
            store_list,
            key=_store_metric_value,
            reverse=True,
        )
        requested_limit = (
            requested_ranking_limit or ranking_limit(query_text, top_n)
        )
        requested_slice = ranked_by_requested_metric[:requested_limit]
        scope_label = (
            "、".join(selected_store_names)
            if selected_store_names
            else store_name or "全部门店"
        )
        single_store_overview = bool(
            (store_id or store_name)
            and len(requested_slice) == 1
        )
        answer_title = (
            f"{scope_label}{window_label}销售情况"
            if single_store_overview or generic_store_sales_overview
            else f"{scope_label}{window_label}{metric_label}对比"
        )
        rank_lines = [f"**{answer_title}：**", ""]
        for index, store in enumerate(requested_slice, 1):
            metric_value = _store_metric_value(store)
            detail_parts: List[str] = []
            if asks_store_revenue:
                detail_parts.append(f"营收 ¥{float(store.get('revenue') or 0):,.2f}")
            if asks_store_orders:
                detail_parts.append(f"订单 {int(store.get('bills') or 0):,} 单")
            if asks_store_avg_ticket:
                detail_parts.append(
                    f"客单价 ¥{_store_metric_value(store):,.2f}"
                )
            if asks_store_sales_volume:
                detail_parts.append(
                    f"菜品销量 {float(store.get('qty') or 0):,.0f} 份"
                )
            value_text = "、".join(detail_parts) or f"{metric_label} {metric_value:,.0f}"
            prefix = "" if single_store_overview else f"{index}. "
            rank_lines.append(f"{prefix}{store['name']} — {value_text}")
        if (
            ranking_direction == "best"
            and requested_slice
            and not single_store_overview
        ):
            leader = requested_slice[0]
            rank_lines.insert(
                0,
                f"**结论：{window_label}{metric_label}最高的是"
                f"{leader['name']}。**",
            )
        if analysis_action == "diagnose":
            rank_lines.extend([
                "",
                (
                    "**原因拆解：**现有营业流水能确认营收、订单和菜品销量的"
                    "结果，但仅凭这三项不能证明变化原因。若要继续定位，应在同一"
                    "时间口径下对照客流、客单价、折扣、缺货、菜品结构和时段分布。"
                ),
            ])
        elif analysis_action == "optimize":
            rank_lines.extend([
                "",
                (
                    "**优化建议：**先从订单、客单价和主力菜销量中找出最弱的一项，"
                    "只做一个小范围动作，并用下一周期同口径数据验证；在原因未确认前"
                    "不直接全店打折。"
                ),
            ])
        ranked_entities = [
            {
                "type": "store",
                "id": store["store_id"],
                "name": store["name"],
                "rank": index,
            }
            for index, store in enumerate(requested_slice, 1)
        ]
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=(
                f"门店销售情况（{window_label}）"
                if single_store_overview or generic_store_sales_overview
                else f"门店{metric_label}对比（{window_label}）"
            ),
            answer_text="\n".join(rank_lines),
            charts=[{
                "chartType": "bar",
                "title": f"门店{metric_label}",
                "xAxis": {"data": [store["name"] for store in requested_slice]},
                "series": [{
                    "name": metric_label,
                    "type": "bar",
                    "data": [
                        _store_metric_value(store)
                        for store in requested_slice
                    ],
                }],
            }],
            kpis=[],
            meta={
                "store_ranking": metric_key,
                "ranking_limit": requested_limit,
                "ranked_entities": ranked_entities,
                "focus_entity": ranked_entities[0] if ranked_entities else None,
                "selected_stores": selected_store_names or [],
                "scope_matches_request": scope_matches_request,
                "window_label": window_label,
                "window_start": (
                    _date_text(window_start) if window_start else None
                ),
                "window_end": (
                    _date_text(window_end) if window_end else None
                ),
                "requested_window_start": (
                    _date_text(exact_start) if exact_start else None
                ),
                "requested_window_end": (
                    _date_text(exact_end) if exact_end else None
                ),
                "stores": [
                    {
                        "storeId": store["store_id"],
                        "name": store["name"],
                        "revenue": store["revenue"],
                        "qty": store["qty"],
                        "bills": store["bills"],
                    }
                    for store in store_list
                ],
            },
        )

    # R15/G5: 「有没有店在亏损」是存在性问题 — 过滤毛利为负的门店直答,
    # 覆盖率如实披露, 不甩全店榜。
    if not (store_id or store_name) and _NEGATIVE_MARGIN_EXISTENCE_RE.search(query_text):
        negative_stores = sorted(
            (st for st in store_list
             if st.get("margin_rate") is not None and st["margin_rate"] < 0),
            key=lambda st: st["margin_rate"],
        )
        rated_count = len([st for st in store_list if st.get("margin_rate") is not None])
        if negative_stores:
            neg_lines = [
                f"**{window_label}有 {len(negative_stores)} 家门店按已覆盖成本口径在亏钱（毛利为负）：**",
                "",
            ]
            for st in negative_stores[:5]:
                neg_lines.append(
                    f"- {st['name']} — 毛利率 {st['margin_rate'] * 100:.1f}%、"
                    f"营收 ¥{st['revenue']:,.2f}"
                )
            if len(negative_stores) > 5:
                neg_lines.append(f"（仅列前 5，共 {len(negative_stores)} 家）")
            neg_lines.append("")
            # 🔴 第三处手写限定语 —— 注意它的措辞和另外两处**已经不一样**了
            #    (「部分」vs「菜品」)。同一个口径长出两种说法, 这就是手写的必然结果,
            #    也正是「限定语由字段生成」要消灭的东西。
            neg_lines.append(
                provenance_qualifier(PROV_MEASURED, coverage_ratio=coverage_ratio)
            )
            neg_answer = "\n".join(neg_lines)
        else:
            neg_answer = (
                f"{window_label}可计算毛利的 {rated_count} 家门店中，"
                f"**没有毛利为负的门店**，按已覆盖成本口径没有门店在亏钱。\n\n"
                + provenance_qualifier(PROV_MEASURED, coverage_ratio=coverage_ratio)
            )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"亏损门店排查（{window_label}）",
            answer_text=neg_answer,
            charts=[], kpis=[],
            meta={"negative_margin_check": True,
                  "negative_count": len(negative_stores),
                  "marginInvariantPass": True,
                  "scope_matches_request": scope_matches_request,
                  "marginFormula": "毛利=可计算毛利的营收-对应菜品成本"},
        )
    structured_store = next(
        (
            store
            for store in store_list
            if (not store_id or str(store["store_id"]) == str(store_id))
            and (not store_name or store.get("name") == store_name)
        ),
        None,
    ) if (store_id or store_name) else None
    mentioned_store = structured_store or next(
        (
            store
            for store in store_list
            if store.get("name") and str(store["name"]) in query_text
        ),
        None,
    )
    asks_target_margin_rank = bool(
        mentioned_store and (
            bool(store_id or store_name)
            or any(token in query_text for token in ("毛利率", "毛利排名", "也是第一", "第几"))
        )
    )
    if asks_target_margin_rank:
        target_store = mentioned_store
        if target_store.get("margin_rate") is None:
            target_revenue = float(target_store["revenue"])
            target_covered_revenue = float(target_store["revenue_with_cost"])
            target_coverage = (
                target_covered_revenue / target_revenue if target_revenue > 0 else 0.0
            )
            return OpsAnswer(
                code="RESTAURANT_OPS_STORE_MARGIN",
                title=f"{target_store['name']}毛利率比较（{window_label}）",
                answer_text=(
                    f"{target_store['name']}在{window_label}有营收 ¥{target_revenue:,.2f}，"
                    f"其中可计算毛利的营收 ¥{target_covered_revenue:,.2f}，"
                    f"成本覆盖率 {target_coverage * 100:.1f}%。"
                    "该店成本数据不足，当前不能可靠计算毛利率或判断排名；"
                    "不会用营收排名替代毛利率排名。请先补齐该店菜品配方和最近进价。"
                ),
                charts=[],
                kpis=[
                    {
                        "title": "成本覆盖率",
                        "value": f"{target_coverage * 100:.1f}%",
                        "rawValue": target_coverage,
                    },
                ],
                meta={
                    "window_days": days,
                    "window_start": _date_text(window_start) if window_start else None,
                    "window_end": _date_text(window_end) if window_end else None,
                    "requested_window_start": _date_text(exact_start) if exact_start else None,
                    "requested_window_end": _date_text(exact_end) if exact_end else None,
                    "scope_matches_request": scope_matches_request,
                    "marginInvariantPass": margin_invariant_pass,
                    "targetStore": target_store,
                    "targetStoreComparable": False,
                },
            )
        rate_ranked = sorted(
            ranked_store_list,
            key=lambda store: float(store["margin_rate"]),
            reverse=True,
        )
        target_rank = next(
            index
            for index, store in enumerate(rate_ranked, start=1)
            if store["store_id"] == target_store["store_id"]
        )
        leader = rate_ranked[0]
        target_rate = float(target_store["margin_rate"])
        target_coverage = (
            float(target_store["revenue_with_cost"]) / float(target_store["revenue"])
            if float(target_store["revenue"]) > 0 else 0.0
        )
        rank_sentence = (
            "也是第一名。"
            if target_rank == 1
            else (
                f"不是第一名，在 {len(rate_ranked)} 家成本可比较门店中排第 {target_rank}；"
                f"第一名是{leader['name']}，毛利率 {float(leader['margin_rate']) * 100:.1f}%。"
            )
        )
        targeted_answer = (
            f"**{target_store['name']}在{window_label}的已覆盖销售毛利率为 {target_rate * 100:.1f}%，"
            f"{rank_sentence}**\n\n"
            f"该店全部营收 ¥{float(target_store['revenue']):,.2f}，"
            f"其中可计算毛利的营收 ¥{float(target_store['revenue_with_cost']):,.2f}，"
            f"成本覆盖率 {target_coverage * 100:.1f}%。\n\n"
            "> 排名只比较同一时间范围内成本完整的销售，不用营收排名替代毛利率排名。"
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title=f"{target_store['name']}毛利率比较（{window_label}）",
            answer_text=targeted_answer,
            charts=[],
            kpis=[
                {"title": "毛利率排名", "value": target_rank, "rawValue": target_rank},
                {"title": "已覆盖毛利率", "value": f"{target_rate * 100:.1f}%", "rawValue": target_rate},
                {"title": "成本覆盖率", "value": f"{target_coverage * 100:.1f}%", "rawValue": target_coverage},
            ],
            meta={
                "window_days": days,
                "window_start": _date_text(window_start) if window_start else None,
                "window_end": _date_text(window_end) if window_end else None,
                "requested_window_start": _date_text(exact_start) if exact_start else None,
                "requested_window_end": _date_text(exact_end) if exact_end else None,
                "scope_matches_request": scope_matches_request,
                "totalRevenue": total_rev,
                "totalRevenueWithCost": total_rev_with_cost,
                "totalProfit": total_profit,
                "avgRate": avg_rate,
                "coveredCost": covered_cost,
                "marginFormula": "毛利=可计算毛利的营收-对应菜品成本",
                "marginInvariantPass": margin_invariant_pass,
                "costCoverageRatio": coverage_ratio,
                "invalidCostCount": invalid_cost_count,
                "targetStore": target_store,
                "stores": [
                    {"storeId": store["store_id"], "name": store["name"],
                     "revenue": store["revenue"],
                     "revenueWithCost": store["revenue_with_cost"],
                     "grossProfit": store["gross_profit"],
                     "marginRate": store["margin_rate"], "bills": store["bills"]}
                    for store in store_list
                ],
            },
        )

    top_text = "\n".join([
        f"{i+1}. {'**' + s['name'] + '**' if i == 0 else s['name']}: 已覆盖营收 ¥{s['revenue_with_cost']:,.2f} / "
        f"毛利 ¥{s['gross_profit']:,.2f} ({s['margin_rate'] * 100:.1f}%), {s['bills']} 单"
        for i, s in enumerate(top_slice)
    ]) or "- 暂无成本完整、可参与毛利排名的门店。"

    charts = [{
        "chartType": "bar",
        "title": f"毛利前 {len(top_slice)} 名门店",
        "xAxis": {"data": [s["name"] for s in top_slice]},
        "series": [
            {"name": "已覆盖营收", "type": "bar", "data": [s["revenue_with_cost"] for s in top_slice]},
            {"name": "毛利", "type": "bar", "data": [s["gross_profit"] for s in top_slice]},
        ],
    }] if top_slice else []

    weak_store_text = ""
    if len(ranked_store_list) > 1:
        weakest = sorted(ranked_store_list, key=lambda s: s["margin_rate"])[0]
        weak_store_text = (
            f"\n\n需要复盘的门店: **{weakest['name']}** 毛利率 {weakest['margin_rate'] * 100:.1f}%, "
            f"先查低毛利菜品占比、套餐折扣和损耗领料是否偏高。"
        )
    margin_summary = (
        f"已覆盖部分毛利 **¥{total_profit:,.2f}**，加权毛利率 **{avg_rate * 100:.1f}%**"
        if total_profit is not None and avg_rate is not None
        else (
            "毛利口径自检未通过，已停止展示异常金额"
            if not margin_invariant_pass
            else "成本完整的销售数据不足，毛利和毛利率暂不可计算"
        )
    )
    invalid_cost_note = (
        f"另有 {invalid_cost_count} 个菜品成本值明显异常，已排除；请复核食材单位和最近进价。"
        if invalid_cost_count > 0 else ""
    )
    answer = (
        f"**门店毛利对比（{window_label}，{len(store_list)} 家店）**\n"
        f"- 全部营收 **¥{total_rev:,.2f}**；可计算毛利的营收 ¥{total_rev_with_cost:,.2f}，"
        f"覆盖率 {coverage_ratio * 100:.1f}%\n"
        f"- {margin_summary}。缺成本菜品已排除，不会按零成本计算。{invalid_cost_note}\n\n"
        f"毛利前 {len(top_slice)} 名门店:\n\n{top_text}{weak_store_text}\n\n"
        f"> 计算口径：毛利 = 可计算毛利的营收 - 对应菜品成本；"
        f"仅使用同一时间范围、同一门店范围的数据。\n\n"
        f"建议动作:\n"
        f"1. 把第一名门店的高毛利菜品组合、套餐结构和时段客流拆出来，作为其他门店对标模板。\n"
        f"2. 对毛利率低但营收不低的门店，优先查折扣、赠品和后厨出品标准，避免销售越多利润越薄。\n"
        f"3. 对菜品成本覆盖率低的门店，先补齐配方成本再做绩效排序。"
    )
    if charts:
        charts[0]["title"] = f"毛利前 {len(top_slice)} 名门店（{window_label}）"

    return OpsAnswer(
        code="RESTAURANT_OPS_STORE_MARGIN",
        title=f"门店毛利对比（{window_label}）",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "门店数", "value": len(store_list), "rawValue": len(store_list)},
            {"title": "总营收", "value": f"¥{total_rev:,.0f}", "rawValue": total_rev},
            {"title": "已覆盖毛利", "value": f"¥{total_profit:,.0f}" if total_profit is not None else "暂不可计算", "rawValue": total_profit},
            {"title": "最赚门店", "value": top_slice[0]["name"] if top_slice else "—", "rawValue": 0},
        ],
        meta={
            "window_days": days, "store_count": len(store_list),
            "window_start": _date_text(window_start) if window_start else None,
            "window_end": _date_text(window_end) if window_end else None,
            "requested_window_start": _date_text(exact_start) if exact_start else None,
            "requested_window_end": _date_text(exact_end) if exact_end else None,
            "scope_matches_request": scope_matches_request,
            "totalRevenue": total_rev, "totalRevenueWithCost": total_rev_with_cost,
            "totalProfit": total_profit, "avgRate": avg_rate,
            "coveredCost": covered_cost,
            "marginFormula": "毛利=可计算毛利的营收-对应菜品成本",
            # R32b: dish_scope_row 是 resolve_gross_margin 的局部变量, 此处
            # 从未定义 — 祖传越界引用, 路径被走到时 NameError 被 fail-open
            # 吞成 delegate:false (「挣着钱没有啊最近」实测)。店维度 meta
            # 的菜品目标固定为 None (店×菜路径有自己的 meta)。
            "targetDish": None,
            "marginInvariantPass": margin_invariant_pass,
            "costCoverageRatio": coverage_ratio,
            "invalidCostCount": invalid_cost_count,
            "stores": [
                {"storeId": s["store_id"], "name": s["name"],
                 "revenue": s["revenue"],
                 "revenueWithCost": s["revenue_with_cost"],
                 "grossProfit": s["gross_profit"],
                 "marginRate": s["margin_rate"], "bills": s["bills"],
                 "dishesWithCost": s["dishes_with_cost"], "totalDishes": s["dishes"],
                 "invalidCostDishes": s["invalid_cost_dishes"]}
                for s in store_list
            ],
        },
    )


async def resolve_sales_summary(
    smartbi_pool,
    factory_id: str,
    *,
    role: Optional[str] = None,
    query: Optional[str] = None,
    today: Optional[date] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    window_label: Optional[str] = None,
    comparison_date_range: Optional[
        Tuple[Optional[date], Optional[date]]
    ] = None,
    comparison_label: Optional[str] = None,
    comparison_kind: Optional[str] = None,
) -> OpsAnswer:
    from smartbi.gold.queries import finance_summary, store_comparison
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    can_see_money = bool(role) and role in PRICE_VIEW_ROLES
    # User calendar words must use the current business date.  Re-anchoring
    # them to MAX(agg_daily.date) silently changes "yesterday" or "last month"
    # whenever ingestion is delayed, which makes a truthful no-data answer look
    # like a result for the requested period.  ``today`` is injectable only for
    # deterministic tests; production callers use the server's current date.
    spec = _resolve_sales_query_spec(query, today=today)
    if date_range is not None and (
        len(date_range) != 2
        or date_range[0] is None
        or date_range[1] is None
    ):
        raise ValueError("date_range must include both start and end")
    if comparison_date_range is not None and (
        len(comparison_date_range) != 2
        or comparison_date_range[0] is None
        or comparison_date_range[1] is None
    ):
        raise ValueError(
            "comparison_date_range must include both start and end"
        )
    resolved_date_range = date_range or spec.date_range
    resolved_window_label = window_label or spec.window_label
    resolved_comparison_range = (
        comparison_date_range or spec.comparison_range
    )
    resolved_comparison_label = (
        comparison_label or spec.comparison_label
    )
    resolved_comparison_kind = comparison_kind or spec.comparison_kind
    query_text = (query or "").strip()
    asks_prohibited_actions = any(token in (query or "") for token in (
        "先不要做", "不要做", "先别做", "不该做", "避免做", "暂时别",
    ))
    asks_best_store_revenue = bool(
        any(token in query_text for token in ("门店", "哪家店", "哪个店", "哪一个店"))
        and any(token in query_text for token in (
            "营收", "营业额", "销售额", "营业收入", "流水", "业绩",
        ))
        and any(token in query_text for token in (
            "最好", "最高", "最多", "第一", "最强", "冠军",
        ))
    )
    date_range, window_label = resolved_date_range, resolved_window_label
    if window_label == _FUTURE_WINDOW_LABEL:
        return OpsAnswer(
            code="RESTAURANT_OPS_SALES_SUMMARY",
            title="经营销售概览",
            answer_text=(
                "您问的是尚未发生的未来时间（如明天/下周），还没有营业数据可统计，"
                "也不会用历史数据替代来作答。可以改问「今天」「昨天」或指定具体历史日期；"
                "如需展望，可以看「最近30天」或「营收趋势」作为参考。"
            ),
            charts=[], kpis=[],
            meta={"future_request": True, "window_label": window_label},
        )
    summary = await finance_summary(
        smartbi_pool,
        factory_id,
        date_range,
        top_n_stores=5,
    )

    def _coerce_summary_date(
        source: Dict[str, Any],
        key: str,
    ) -> Optional[date]:
        raw_value = source.get(key)
        if isinstance(raw_value, date):
            return raw_value
        if raw_value:
            try:
                return date.fromisoformat(str(raw_value))
            except ValueError:
                return None
        return None

    primary_actual_start = _coerce_summary_date(
        summary,
        "actual_start_date",
    )
    primary_actual_end = _coerce_summary_date(summary, "actual_end_date")
    comparison_requires_equal_coverage = bool(
        resolved_comparison_label
        and resolved_comparison_label.endswith("同期")
    )
    # QueryPlan marks an unfinished current week/month/year baseline as
    # "...同期". If ingestion itself trails today (including a calendar
    # boundary such as Sunday), align the baseline again to the primary
    # window's actual observed offsets before querying it.
    if (
        resolved_comparison_label
        and date_range[0] is not None
        and date_range[1] is not None
        and resolved_comparison_range[0] is not None
        and resolved_comparison_range[1] is not None
        and primary_actual_start is not None
        and primary_actual_end is not None
        and (
            comparison_requires_equal_coverage
            or (
                date_range[1] == (today or date.today())
                and primary_actual_end < date_range[1]
            )
        )
    ):
        start_offset = max(0, (primary_actual_start - date_range[0]).days)
        end_offset = max(
            start_offset,
            (primary_actual_end - date_range[0]).days,
        )
        baseline_start = resolved_comparison_range[0] + timedelta(
            days=start_offset,
        )
        baseline_end = min(
            resolved_comparison_range[1],
            resolved_comparison_range[0] + timedelta(days=end_offset),
        )
        if baseline_start <= baseline_end:
            resolved_comparison_range = (baseline_start, baseline_end)
            comparison_requires_equal_coverage = True
            if not resolved_comparison_label.endswith("同期"):
                resolved_comparison_label = (
                    f"{resolved_comparison_label}同期"
                )

    comparison_summary: Optional[Dict[str, Any]] = None
    if (
        resolved_comparison_label
        and resolved_comparison_range[0]
        and resolved_comparison_range[1]
    ):
        comparison_summary = await finance_summary(
            smartbi_pool,
            factory_id,
            resolved_comparison_range,
            top_n_stores=5,
        )
    stores_data = await store_comparison(smartbi_pool, factory_id, date_range)
    stores = stores_data.get("stores") or []
    weak_stores = stores_data.get("weakStores") or []

    total_revenue = float(summary.get("total_revenue") or 0.0)
    bill_count = int(summary.get("bill_count") or 0)
    avg_bill = summary.get("avg_bill_value")
    day_count = int(summary.get("day_count") or 0)
    store_count = int(summary.get("store_count") or 0)
    top_stores = summary.get("top_stores") or []

    sales_scope_start = primary_actual_start or date_range[0]
    sales_scope_end = primary_actual_end or date_range[1]
    sales_scope = (
        (sales_scope_start, sales_scope_end)
        if sales_scope_start is not None and sales_scope_end is not None
        else (None, None)
    )
    # 标签与括号里的日期是两个口径, 拼错会说谎 —— 见 `window_scope_text` 的
    # docstring(它抽成具名函数正是为了让闸咬得住, ⛔ 别再内联回来)。
    actual_window = window_scope_text(
        window_label,
        (date_range[0], date_range[1]),
        (sales_scope_start, sales_scope_end),
    )
    comparison_meta: Optional[Dict[str, Any]] = None
    if (
        resolved_comparison_label
        and resolved_comparison_range[0]
        and resolved_comparison_range[1]
    ):
        comparison_meta = {
            "answered": True,
            "kind": resolved_comparison_kind,
            "primary_label": window_label,
            "primary_start": (
                _date_text(sales_scope_start) if sales_scope_start else None
            ),
            "primary_end": (
                _date_text(sales_scope_end) if sales_scope_end else None
            ),
            "baseline_label": resolved_comparison_label,
            "baseline_start": _date_text(resolved_comparison_range[0]),
            "baseline_end": _date_text(resolved_comparison_range[1]),
        }

    def _money(v: Optional[float]) -> str:
        if not can_see_money:
            return "***"
        if v is None:
            return "暂无"
        return f"¥{float(v):,.2f}"

    if bill_count <= 0:
        comparison_note = ""
        if comparison_meta:
            comparison_window = (
                f"{comparison_meta['baseline_label']}"
                f"（{_range_text(comparison_meta['baseline_start'], comparison_meta['baseline_end'])}）"
            )
            comparison_bill_count = int((comparison_summary or {}).get("bill_count") or 0)
            comparison_meta.update({
                "primary_start": _date_text(date_range[0]),
                "primary_end": _date_text(date_range[1]),
                "primary_bills": 0,
                "primary_no_data": True,
                "baseline_bills": comparison_bill_count,
                "baseline_no_data": comparison_bill_count <= 0,
            })
            if comparison_bill_count > 0:
                comparison_note = (
                    f"{comparison_window}有可用记录，但{actual_window}没有数据，"
                    "因此不能可靠判断两个日期谁高谁低。"
                )
            else:
                comparison_note = (
                    f"{comparison_window}也没有可用的营收和订单数据，"
                    "因此不能可靠判断两个日期谁高谁低。"
                )
        no_data_guard = (
            "今天先不要做：不要依据缺失数据调整价格、下架菜品或重排人员；"
            "先确认营业流水同步完整。"
            if asks_prohibited_actions else ""
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_SALES_SUMMARY",
            title="经营销售概览",
            answer_text=(
                f"{actual_window}没有可用的营收和订单数据。{comparison_note}"
                "没有用全部历史或其他日期替代。"
                f"请先确认营业流水已经同步，再按同一时间范围重试。{no_data_guard}"
            ),
            charts=[],
            kpis=[],
            meta={
                "no_data": True,
                "window_label": window_label,
                "window_start": _date_text(date_range[0]) if date_range[0] else None,
                "window_end": _date_text(date_range[1]) if date_range[1] else None,
                "comparison": comparison_meta,
            },
        )

    top_line = ""
    if top_stores:
        top = top_stores[0]
        top_line = f"表现最强的是{top['store_name']}，订单数 {top['bill_count']} 单。"
        if can_see_money:
            top_line = f"表现最强的是{top['store_name']}，营收 {_money(top['revenue'])}，订单数 {top['bill_count']} 单。"
        if asks_best_store_revenue:
            top_line = (
                f"**结论：{actual_window}营收最高的是{top['store_name']}，"
                f"营收 {_money(top['revenue'])}，订单数 {top['bill_count']} 单。**"
                if can_see_money
                else (
                    "**当前角色不能查看门店营收排名；"
                    "下面仅展示有权限查看的订单与经营概览。**"
                )
            )

    weak_line = ""
    if weak_stores:
        weak_line = f"低于中位水平的门店有 {len(weak_stores)} 家，先看{weak_stores[0]}。"

    avg_text = _money(float(avg_bill)) if avg_bill is not None else "暂无"
    margin_line = ""
    margin_meta: Dict[str, Any] = {}
    if spec.wants_margin:
        if can_see_money:
            if sales_scope[0] is None or sales_scope[1] is None:
                margin_line = (
                    "营收的实际时间范围无法确定，已停止展示毛利，"
                    "避免把不同时间口径的数据放在一起。"
                )
            else:
                margin_days = max(1, min((sales_scope[1] - sales_scope[0]).days + 1, 365))
                margin_kwargs: Dict[str, Any] = {
                    "days": margin_days,
                    "top_n": 5,
                    "role": role,
                }
                if "date_range" in inspect.signature(resolve_store_margin).parameters:
                    margin_kwargs["date_range"] = sales_scope
                margin_result = await resolve_store_margin(
                    smartbi_pool,
                    factory_id,
                    **margin_kwargs,
                )
                margin_meta = margin_result.meta or {}
                margin_meta["outer_window_start"] = sales_scope[0].isoformat()
                margin_meta["outer_window_end"] = sales_scope[1].isoformat()
                total_profit_raw = margin_meta.get("totalProfit")
                covered_revenue_raw = margin_meta.get("totalRevenueWithCost")
                margin_revenue_raw = margin_meta.get("totalRevenue")
                avg_rate_raw = margin_meta.get("avgRate")
                try:
                    total_profit = (
                        float(total_profit_raw) if total_profit_raw is not None else None
                    )
                    covered_revenue = (
                        float(covered_revenue_raw) if covered_revenue_raw is not None else None
                    )
                    margin_revenue = (
                        float(margin_revenue_raw) if margin_revenue_raw is not None else None
                    )
                    avg_rate = float(avg_rate_raw) if avg_rate_raw is not None else None
                except (TypeError, ValueError):
                    total_profit = None
                    covered_revenue = None
                    margin_revenue = None
                    avg_rate = None
                requested_start = margin_meta.get("requested_window_start")
                requested_end = margin_meta.get("requested_window_end")
                scope_ok = bool(
                    margin_meta.get("scope_matches_request", True)
                    and (not requested_start or requested_start == sales_scope[0].isoformat())
                    and (not requested_end or requested_end == sales_scope[1].isoformat())
                )
                arithmetic_ok = bool(
                    total_profit is not None
                    and covered_revenue is not None
                    and margin_revenue is not None
                    and total_profit <= covered_revenue + 0.01
                    and covered_revenue <= margin_revenue + 0.01
                    and (
                        avg_rate is None
                        or covered_revenue <= 0
                        or abs(avg_rate - total_profit / covered_revenue) <= 0.001
                    )
                )
                invariant_ok = bool(
                    margin_meta.get("marginInvariantPass", True) and arithmetic_ok
                )
                margin_meta["scope_matches_request"] = scope_ok
                margin_meta["marginInvariantPass"] = invariant_ok
                if total_profit is not None and covered_revenue is not None and scope_ok and invariant_ok:
                    verdict = ""
                    if spec.asks_profitability:
                        if total_profit > 0:
                            verdict = "按已配置成本卡看，这段时间已覆盖的销售是赚钱的。"
                        elif total_profit < 0:
                            verdict = "按已配置成本卡看，这段时间已覆盖的销售是亏损的。"
                        else:
                            verdict = "按已配置成本卡看，这段时间已覆盖的销售基本打平。"
                    coverage = covered_revenue / margin_revenue if margin_revenue > 0 else 0.0
                    rate_text = f"，已覆盖部分毛利率 {avg_rate * 100:.1f}%" if avg_rate is not None else ""
                    verdict_md = f"**{verdict}**" if verdict else ""
                    margin_line = (
                        f"{verdict_md}同期可计算毛利的营收 {_money(covered_revenue)}，"
                        f"覆盖同期菜品营收的 {coverage * 100:.1f}%；"
                        f"对应毛利 {_money(total_profit)}{rate_text}。\n"
                        "> 毛利率以可计算毛利的营收为分母，不以全部营收为分母。"
                    )
                elif not scope_ok:
                    margin_line = "毛利与营收的时间范围不一致，已停止展示，避免把不同口径的数据放在一起比较。"
                elif not invariant_ok:
                    margin_line = "毛利口径自检未通过，已停止展示异常金额；请先复核成本单位和销售范围。"
                else:
                    margin_line = "毛利暂时无法可靠计算，原因是这段时间的菜品成本卡或收银明细还不完整。"
        else:
            margin_line = "毛利属于成本/价格权限，当前角色不能查看金额；可以先看订单、客单价和门店差异。"

    comparison_line = ""
    if comparison_meta is not None:
        baseline = comparison_summary or {}
        baseline_revenue = float(baseline.get("total_revenue") or 0.0)
        baseline_bills = int(baseline.get("bill_count") or 0)
        baseline_avg_bill = baseline.get("avg_bill_value")
        baseline_range_text = (
            f"{resolved_comparison_label}"
            f"（{_range_text(comparison_meta['baseline_start'], comparison_meta['baseline_end'])}）"
        )
        if baseline_bills <= 0:
            comparison_meta.update({"baseline_no_data": True})
            comparison_line = (
                f"对比期{baseline_range_text}没有可用数据，因此不能判断高低；"
                "没有用全部历史或其他月份代替。"
            )
        else:
            baseline_day_count = int(baseline.get("day_count") or 0)
            bill_delta = bill_count - baseline_bills
            bill_pct = (bill_delta / baseline_bills * 100.0) if baseline_bills else None
            comparison_meta.update({
                "baseline_no_data": False,
                "primary_revenue": total_revenue if can_see_money else None,
                "baseline_revenue": baseline_revenue if can_see_money else None,
                "primary_bills": bill_count,
                "baseline_bills": baseline_bills,
                "primary_day_count": day_count,
                "baseline_day_count": baseline_day_count,
                "bill_delta": bill_delta,
                "bill_change_pct": bill_pct,
            })
            if (
                comparison_requires_equal_coverage
                and day_count != baseline_day_count
            ):
                comparison_meta["coverage_mismatch"] = True
                comparison_line = (
                    f"{actual_window}有 {day_count} 个数据日，"
                    f"{baseline_range_text}有 {baseline_day_count} 个数据日；"
                    "覆盖天数不同，本次不直接判断高低，也没有用未对齐总额"
                    "替代同口径比较。"
                )
            else:
                comparison_meta["coverage_mismatch"] = False
                order_direction = (
                    "增加"
                    if bill_delta > 0
                    else "减少"
                    if bill_delta < 0
                    else "持平"
                )
                order_text = (
                    f"订单数{order_direction} {abs(bill_delta):,} 单"
                    + (
                        f"（{abs(bill_pct):.1f}%）"
                        if bill_pct is not None
                        else ""
                    )
                )
                if can_see_money:
                    revenue_delta = total_revenue - baseline_revenue
                    revenue_pct = (
                        revenue_delta / baseline_revenue * 100.0
                        if baseline_revenue
                        else None
                    )
                    comparison_meta.update({
                        "revenue_delta": revenue_delta,
                        "revenue_change_pct": revenue_pct,
                        "primary_avg_bill": (
                            float(avg_bill) if avg_bill is not None else None
                        ),
                        "baseline_avg_bill": (
                            float(baseline_avg_bill)
                            if baseline_avg_bill is not None
                            else None
                        ),
                    })
                    direction = (
                        "高"
                        if revenue_delta > 0
                        else "低"
                        if revenue_delta < 0
                        else "持平"
                    )
                    revenue_text = (
                        f"营收{direction} **{_money(abs(revenue_delta))}**"
                    )
                    if revenue_pct is not None:
                        revenue_text += f"（{abs(revenue_pct):.1f}%）"
                    comparison_line = (
                        f"与{baseline_range_text}相比，"
                        f"{revenue_text}，{order_text}。"
                    )
                else:
                    comparison_line = (
                        f"与{baseline_range_text}相比，{order_text}。"
                    )

    prohibited_actions_line = ""
    if asks_prohibited_actions:
        prohibited_actions_line = (
            "今天先不要做：不要只凭总营收立即下架菜品；不要在没有毛利依据时做全店无差别打折；"
            "不要把单一门店或单一时段的波动直接推广到全部门店。先完成菜品毛利、门店差异和时段拆分再行动。"
        )
    total_revenue_text = (
        f"**{_money(total_revenue)}**" if can_see_money else _money(total_revenue)
    )
    answer_parts = [
        top_line if asks_best_store_revenue else "",
        (
            f"{actual_window}经营能看：覆盖 {day_count} 天、{store_count} 家门店，共 **{bill_count:,} 单**。"
            f"总营收 {total_revenue_text}，平均每单 {avg_text}。"
        ),
        comparison_line,
        margin_line,
        weak_line if asks_best_store_revenue else f"{top_line}{weak_line}",
        (
            "建议：先把低于中位的门店拉出来，看是客流少、平均每单低，还是折扣过重；"
            "再对照高门店的菜品结构和时段，把能复制的动作做小范围试点。"
        ),
        prohibited_actions_line,
    ]
    answer = "\n\n".join(part for part in answer_parts if part)

    chart_stores = top_stores[:5]
    charts = [{
        "chartType": "bar",
        "title": "门店营收前 5 名" if can_see_money else "门店订单前 5 名",
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
    if (
        spec.wants_margin
        and margin_meta.get("totalProfit") is not None
        and margin_meta.get("scope_matches_request", True) is not False
        and margin_meta.get("marginInvariantPass", True) is not False
    ):
        kpis.append({
            "title": "已覆盖毛利",
            "value": _money(float(margin_meta["totalProfit"])) if can_see_money else None,
            "rawValue": float(margin_meta["totalProfit"]) if can_see_money else None,
        })
        if margin_meta.get("avgRate") is not None:
            kpis.append({
                "title": "已覆盖毛利率",
                "value": f"{float(margin_meta['avgRate']) * 100:.1f}%" if can_see_money else None,
                "rawValue": float(margin_meta["avgRate"]) if can_see_money else None,
            })

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
            "window_start": sales_scope_start.isoformat() if sales_scope_start else None,
            "window_end": sales_scope_end.isoformat() if sales_scope_end else None,
            "weak_stores": weak_stores,
            "price_view": can_see_money,
            "store_comparison_count": len(stores),
            "margin": margin_meta if spec.wants_margin else None,
            "comparison": comparison_meta,
            "asks_profitability": spec.asks_profitability,
        },
    )


async def resolve_trend_analysis(
    smartbi_pool, factory_id: str, *, role: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
    query: Optional[str] = None,
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
    query_text = query or ""
    daily_requested = any(token in query_text for token in (
        "每日", "每天", "按日", "逐日", "日度", "二次函数", "二次拟合",
    ))
    quadratic_requested = any(token in query_text for token in (
        "二次函数", "二次拟合", "二次曲线",
    ))
    revenue_reference_lines = _parse_revenue_reference_lines(query_text)
    reference_requested = any(token in query_text for token in (
        "参照线", "计划线", "预警线", "计划值", "目标值", "预警值",
    ))
    export_requested = any(token in query_text for token in (
        "导出", "下载", "Excel", "excel", "XLS", "xls", "生成文件",
    ))

    # R24: trend_bundle 本就支持时间窗, resolver 此前写死全历史 —
    # 「最近三个月营收趋势」答 19 个月属于窗口替代。带窗时如实标注。
    window = date_range if date_range and (date_range[0] or date_range[1]) else (None, None)
    window_text = (
        _range_text(window[0], window[1]) if window[0] and window[1] else "全部历史"
    )
    bundle = await trend_bundle(smartbi_pool, factory_id, window)
    monthly: List[Dict[str, Any]] = sorted(
        bundle.get("monthlyTrend") or [], key=lambda item: str(item.get("month") or "")
    )
    daily: List[Dict[str, Any]] = bundle.get("dailyTrend") or []
    ww: Dict[str, Any] = bundle.get("weekdayWeekend") or {}

    def _money(v: float) -> str:
        return f"¥{v:,.2f}" if can_see_money else "***"

    if not monthly:
        return OpsAnswer(
            code="RESTAURANT_OPS_TREND_ANALYSIS",
            title="营收趋势分析",
            answer_text=(
                "暂无按日期拆分的营业数据，暂时无法计算月度趋势和同比环比。\n"
                "请先导入包含营业日期和实收金额的完整数据。"
            ),
            charts=[], kpis=[],
            meta={"all_history": True, "no_data": True},
        )

    def _parse_daily_date(item: Dict[str, Any]) -> Optional[date]:
        raw = item.get("date") or item.get("statDate") or item.get("stat_date")
        if isinstance(raw, date):
            return raw
        try:
            return date.fromisoformat(str(raw)[:10])
        except (TypeError, ValueError):
            return None

    dated_daily = sorted([
        (parsed, float(item.get("revenue") or 0.0))
        for item in daily
        if (parsed := _parse_daily_date(item)) is not None
    ], key=lambda item: item[0])
    latest_month = str(monthly[-1].get("month") or "")
    latest_month_days = [item for item in dated_daily if item[0].strftime("%Y-%m") == latest_month]
    latest_data_date = max((item[0] for item in latest_month_days), default=None)
    latest_month_partial = False
    if latest_data_date is not None:
        next_month = (
            date(latest_data_date.year + 1, 1, 1)
            if latest_data_date.month == 12
            else date(latest_data_date.year, latest_data_date.month + 1, 1)
        )
        latest_month_partial = latest_data_date < next_month - timedelta(days=1)

    # A partial latest month cannot fairly compete with completed months for
    # the peak/trough or the overall first-to-last direction.
    comparable_monthly = monthly[:-1] if latest_month_partial and len(monthly) > 1 else monthly
    # R24b: 窗口起点截断的首月同样是残月 (R24 加窗后暴露: 5/25 起的窗把
    # 5 月剩 7 天标成"营收最低月") — 从完整月比较中剔除。
    if (
        window[0] is not None and window[0].day != 1
        and len(comparable_monthly) > 1
        and str(comparable_monthly[0].get("month") or "") == window[0].strftime("%Y-%m")
    ):
        comparable_monthly = comparable_monthly[1:]
    peak = max(comparable_monthly, key=lambda m: m["revenue"])
    trough = min(comparable_monthly, key=lambda m: m["revenue"])
    total_rev = sum(m["revenue"] for m in monthly)
    n_months = len(monthly)

    # MoM / YoY use equal-day MTD windows when the latest month is incomplete.
    mom_line = ""
    yoy_line = ""
    comparison_basis = "完整月"
    if latest_month_partial and latest_data_date is not None:
        comparison_basis = f"截至{latest_data_date.day}日的同天数口径"
        current_start = latest_data_date.replace(day=1)
        previous_month_end = current_start - timedelta(days=1)
        previous_start = previous_month_end.replace(day=1)
        previous_cutoff = min(
            previous_start + timedelta(days=latest_data_date.day - 1), previous_month_end
        )
        previous_year_start = current_start.replace(year=current_start.year - 1)
        next_previous_year_month = (
            date(previous_year_start.year + 1, 1, 1)
            if previous_year_start.month == 12
            else date(previous_year_start.year, previous_year_start.month + 1, 1)
        )
        previous_year_end = next_previous_year_month - timedelta(days=1)
        previous_year_cutoff = min(
            previous_year_start + timedelta(days=latest_data_date.day - 1), previous_year_end
        )

        def _daily_sum(start: date, end: date) -> float:
            return sum(value for day, value in dated_daily if start <= day <= end)

        current_mtd = _daily_sum(current_start, latest_data_date)
        previous_mtd = _daily_sum(previous_start, previous_cutoff)
        previous_year_mtd = _daily_sum(previous_year_start, previous_year_cutoff)
        if previous_mtd > 0:
            mom_rate = (current_mtd - previous_mtd) / previous_mtd * 100
            direction = "增长" if mom_rate >= 0 else "下降"
            mom_line = (
                f"- 同口径环比（截至{latest_data_date.day}日，{latest_month} vs "
                f"{previous_start.strftime('%Y-%m')}）：{direction} {abs(mom_rate):.1f}%\n"
            )
        else:
            mom_line = (
                f"- 最新月仅统计到{latest_data_date.day}日；上月同期数据不足，"
                "本次不计算环比。\n"
            )
        if previous_year_mtd > 0:
            yoy_rate = (current_mtd - previous_year_mtd) / previous_year_mtd * 100
            direction = "增长" if yoy_rate >= 0 else "下降"
            yoy_line = (
                f"- 同口径同比（截至{latest_data_date.day}日，{latest_month} vs "
                f"{previous_year_start.strftime('%Y-%m')}）：{direction} {abs(yoy_rate):.1f}%\n"
            )
    elif n_months >= 2:
        last, prev = monthly[-1], monthly[-2]
        if prev["revenue"] > 0:
            mom_rate = (last["revenue"] - prev["revenue"]) / prev["revenue"] * 100
            direction = "增长" if mom_rate >= 0 else "下降"
            mom_line = (
                f"- 环比(最新月 {last['month']} vs {prev['month']}): "
                f"{direction} {abs(mom_rate):.1f}%\n"
            )

    # Complete-month YoY: latest month vs same month last year, if present.
    if not latest_month_partial and monthly:
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

    # Overall direction uses completed comparable months only.
    overall_line = ""
    if len(comparable_monthly) >= 2 and comparable_monthly[0]["revenue"] > 0:
        overall_rate = (
            (comparable_monthly[-1]["revenue"] - comparable_monthly[0]["revenue"])
            / comparable_monthly[0]["revenue"] * 100
        )
        direction = "上升" if overall_rate >= 0 else "下降"
        overall_line = (
            f"- 完整月整体走势({comparable_monthly[0]['month']} → "
            f"{comparable_monthly[-1]['month']}): "
            f"{direction} {abs(overall_rate):.1f}%\n"
        )

    month_list_text = "\n".join([
        f"  {m['month']}{f'（截至{latest_data_date.day}日）' if latest_month_partial and m is monthly[-1] and latest_data_date else ''}: "
        f"{_money(m['revenue'])}" for m in monthly
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

    quadratic_fit = (
        _quadratic_least_squares([value for _, value in dated_daily])
        if quadratic_requested and can_see_money
        else None
    )
    daily_detail = ""
    if daily_requested and dated_daily:
        daily_start = dated_daily[0][0]
        daily_end = dated_daily[-1][0]
        daily_detail = (
            f"- 日级数据点：{len(dated_daily)} 个"
            f"（{daily_start.isoformat()} 至 {daily_end.isoformat()}）\n"
        )
        if quadratic_requested:
            if quadratic_fit is not None:
                a, b, c, r_squared, _ = quadratic_fit
                daily_detail += (
                    "- 二次趋势拟合："
                    f"`y = {a:,.4f}x² {b:+,.4f}x {c:+,.2f}`，"
                    f"R² = {r_squared:.4f}；x=0 对应 {daily_start.isoformat()}。"
                    "这是描述性趋势线，不代表价格、活动或其他因素造成了营收变化。\n"
                )
            else:
                daily_detail += (
                    "- 日级有效数据不足或矩阵不可解，本次没有伪造二次拟合线；"
                    "仍展示实际每日营收。\n"
                )
        if reference_requested:
            if revenue_reference_lines:
                daily_detail += "- 图中参照线：" + "、".join(
                    f"{line['name']} ¥{line['yAxis']:,.2f}"
                    for line in revenue_reference_lines
                ) + "\n"
            else:
                daily_detail += (
                    "- 你要求了参照线但没有提供可解析金额，本次没有擅自添加默认值。\n"
                )
    elif daily_requested:
        daily_detail = "- 当前窗口没有日级营收明细，本次没有把月度数据伪装成每日曲线。\n"
    if export_requested:
        if dated_daily:
            daily_detail += (
                "- 可导出字段：`date`（日期）、`revenue`（营业额）；"
                f"本次共有 {len(dated_daily)} 行，可用于 Excel/XLS 数据导出。\n"
            )
        else:
            daily_detail += (
                "- 当前窗口没有可导出的日级营业额记录，本次没有生成空文件或伪造数据。\n"
            )

    cumulative_text = f"**{_money(total_rev)}**" if can_see_money else _money(total_rev)
    answer = (
        f"**营收趋势分析 ({window_text}, 共 {n_months} 个月):**\n"
        f"- 累计营收 {cumulative_text}\n"
        f"- 营收最高月: **{peak['month']}** ({_money(peak['revenue'])})\n"
        f"- 营收最低月: {trough['month']} ({_money(trough['revenue'])})\n"
        f"{overall_line}{yoy_line}{mom_line}{ww_line}{daily_detail}"
        f"\n建议动作:\n"
        f"1. 先把最高月和最低月按门店、渠道、折扣三层拆开，找出增长来自客流、客单价还是活动补贴。\n"
        f"2. 如果最新月下滑，优先确认是否为未完结月份；若已完结，再复盘低于中位数门店和高折扣渠道。\n"
        f"3. 周末与工作日差异不大时，重点做时段活动而不是整天打折，避免折扣侵蚀毛利。\n"
        f"\n各月营收:\n\n{month_list_text}"
    )

    if daily_requested and dated_daily:
        actual_series: Dict[str, Any] = {
            "name": "每日营收",
            "type": "line",
            "data": [
                value if can_see_money else None
                for _, value in dated_daily
            ],
        }
        if revenue_reference_lines and can_see_money:
            actual_series["markLine"] = {
                "silent": True,
                "symbol": "none",
                "lineStyle": {"type": "dashed"},
                "data": revenue_reference_lines,
            }
        daily_series: List[Dict[str, Any]] = [actual_series]
        if quadratic_fit is not None:
            daily_series.append({
                "name": "二次趋势拟合",
                "type": "line",
                "symbol": "none",
                "lineStyle": {"type": "dashed"},
                "data": [round(value, 2) for value in quadratic_fit[4]],
            })
        charts = [{
            "chartType": "line",
            "title": f"每日营收趋势 ({window_text})",
            "xAxis": {"data": [day.isoformat() for day, _ in dated_daily]},
            "yAxis": {"type": "value", "name": "营收(元)"},
            "series": daily_series,
        }]
    else:
        charts = [{
            "chartType": "line",
            "title": f"月度营收趋势 ({window_text})",
            "xAxis": {"data": [
                (
                    f"{m['month']}（截至{latest_data_date.day}日）"
                    if latest_month_partial and m is monthly[-1] and latest_data_date
                    else m["month"]
                )
                for m in monthly
            ]},
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
        title=f"营收趋势分析 ({window_text})",
        answer_text=answer,
        charts=charts,
        kpis=kpis,
        meta={
            "all_history": True,
            "month_count": n_months,
            "daily_requested": daily_requested,
            "daily_point_count": len(dated_daily),
            "quadratic_requested": quadratic_requested,
            "quadratic_fit": (
                {
                    "a": quadratic_fit[0],
                    "b": quadratic_fit[1],
                    "c": quadratic_fit[2],
                    "r_squared": quadratic_fit[3],
                }
                if quadratic_fit is not None else None
            ),
            "reference_lines": revenue_reference_lines,
            "export_requested": export_requested,
            "export_rows": (
                [
                    {
                        "date": day.isoformat(),
                        "revenue": value if can_see_money else None,
                    }
                    for day, value in dated_daily
                ]
                if daily_requested else []
            ),
            "peak_month": peak["month"],
            "trough_month": trough["month"],
            "price_view": can_see_money,
            "latest_month_partial": latest_month_partial,
            "latest_data_date": latest_data_date.isoformat() if latest_data_date else None,
            "comparison_basis": comparison_basis,
        },
    )


async def resolve_inventory_warning(
    smartbi_pool, factory_id: str, *, top_n: int = 15,
) -> OpsAnswer:
    """Stock-level warning: which ingredients are below their safe-stock level.

    ⛔ 2026-08-09 起数据源改为 **Java 侧库存底账**(cretas 库的
    ``raw_material_types`` + ``material_batches``)，不再读 smartbi 侧的
    ``fact_inventory_snapshot``。原因见函数体内的大段说明：两本账曾对同一个
    租户给出**相反**的答案 —— 本函数说「还没有接入库存快照数据」，而同一次
    回答末尾的顺带提示同时在报「罗氏虾 剩 0kg，低于安全线」。

    ⛔ 「要不要补货」的判断逐字沿用 Java 侧
    ``MaterialBatchServiceImpl.getLowStockWarnings``(可用量 < min_stock)，
    两个消费者读同一个源、用同一条判断，因此不可能再打架。

    No monetary output — this is a quantity/threshold read, not a cost/price
    read, so it carries no PRICE_VIEW_ROLES gate (unlike resolve_gross_margin
    / resolve_store_margin / resolve_sales_summary).
    """
    # ── 数据源：Java 侧库存底账（唯一权威） ─────────────────────────────
    #
    # 🔴 2026-08-09 实测的口径打架：同一个租户、同一件事，两个相反的答案 ——
    #    问「哪些食材快没了」答「还没有接入库存快照数据」，而同一次回答末尾的
    #    顺带提示正在报「罗氏虾 剩 0kg，低于安全线 2288.42kg」。
    #    因为两边读的是两本账：本函数原先读 smartbi 侧 `fact_inventory_snapshot`
    #    （上传型快照，全库只有 DEMO_REST 24 行），而 Java 侧的低库存发现读
    #    cretas 侧 `raw_material_types` + `material_batches`（7 个租户都有数据）。
    #
    # ⛔ 改为**只读 Java 侧底账**，不做「两边取其一」的回落 ——
    #    回落等于留着两本账，迟早再次给出相反答案。实测确认没有任何租户
    #    只有快照没有底账（唯一有快照的 DEMO_REST 在底账里有 53 个物料），
    #    所以切换只增不减：覆盖从 1 个租户变成 7 个。
    #
    # ⚠️ 阈值口径随之统一：底账只有一个 `min_stock`（安全库存），
    #    没有快照表那种「补货点 / 安全库存」两级。这里**不再编第二个阈值** ——
    #    低于 min_stock = 需补货；低于 min_stock 的 1.2 倍 = 关注。
    #    倍数是展示分档，不参与「要不要补货」的判断，那条判断逐字沿用
    #    Java 侧 `MaterialBatchServiceImpl.getLowStockWarnings` 的口径
    #    （min_stock > 0 且 可用量 < min_stock），两边因此不可能再打架。
    from smartbi.config import get_cretas_pool

    cretas_pool = await get_cretas_pool()
    if cretas_pool is None:
        # 禁降级: 连不上底账就说清楚, 不拿空结果冒充「库存正常」。
        return OpsAnswer(
            code="RESTAURANT_OPS_INVENTORY_WARNING",
            title="库存预警",
            answer_text=(
                "库存底账暂时连不上，本次没有给出补货判断，也没有用其他数据替代。"
                "请稍后重试。"
            ),
            charts=[], kpis=[], meta={"no_data": True, "reason": "cretas_pool_unavailable"},
        )

    async with cretas_pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT t.name,
                   t.category,
                   COALESCE(t.unit, '')                                AS unit,
                   COALESCE(SUM(b.receipt_quantity - b.used_quantity)
                            FILTER (WHERE b.status = 'AVAILABLE'
                                      AND b.deleted_at IS NULL), 0)::float AS stock_qty,
                   t.min_stock::float                                   AS safe_stock_qty
              FROM raw_material_types t
              LEFT JOIN material_batches b
                     ON b.material_type_id = t.id AND b.factory_id = t.factory_id
             WHERE t.factory_id = $1
               AND t.is_active
               AND t.min_stock IS NOT NULL
               AND t.min_stock > 0
             GROUP BY t.id, t.name, t.category, t.unit, t.min_stock
             ORDER BY t.name
            """,
            factory_id,
        )

    if not rows:
        return OpsAnswer(
            code="RESTAURANT_OPS_INVENTORY_WARNING",
            title="库存预警",
            answer_text=(
                "这个门店还没有设置任何食材的安全库存，无法判断哪些需要补货。"
                "请先在物料管理里给常用食材填上安全库存，本分析即可运行。"
            ),
            charts=[], kpis=[], meta={"no_data": True},
        )

    max_date = date.today()

    high: List[Dict[str, Any]] = []
    medium: List[Dict[str, Any]] = []
    ok: List[Dict[str, Any]] = []
    # ⛔ 分档口径: 「需补货」逐字沿用 Java 侧 getLowStockWarnings 的判断
    #    (可用量 < min_stock), 两边因此不可能给出相反结论。
    #    「关注」是本函数自己的展示分档(逼近安全库存), 不参与补货判断。
    WATCH_RATIO = 1.2
    for r in rows:
        stock = r["stock_qty"] if r["stock_qty"] is not None else 0.0
        safe = r["safe_stock_qty"]
        entry = {
            "name": r["name"], "category": r["category"], "unit": r["unit"] or "",
            "stock": stock, "reorder": safe, "safe": safe,
        }
        if safe is not None and stock < safe:
            high.append(entry)
        elif safe is not None and stock < safe * WATCH_RATIO:
            medium.append(entry)
        else:
            ok.append(entry)

    high.sort(key=lambda e: (e["stock"] - e["safe"]))
    medium.sort(key=lambda e: (e["stock"] - e["safe"]))

    # 2026-08-11: 编号列表 + 顿号串改 markdown 表格。
    # ⚠️ 两个循环(需补货 / 关注)合成**一张**表, 用「状态」列区分, 缺口列对「关注」
    #    档留「—」—— 另起一张会让人以为是两批不同口径的数(同渠道构成那次的判据)。
    # ⛔ 「需立即补货」这几个字必须留在表内: 分档口径逐字沿用 Java 侧
    #    getLowStockWarnings, 措辞是用户认得的那个动作, 不是排版装饰。
    alert_rows = []
    for e in high[:top_n]:
        alert_rows.append([
            "需立即补货", e["name"], e["category"] or "—",
            f"{e['stock']:.1f} {e['unit']}".strip(),
            f"{e['safe']:.1f} {e['unit']}".strip(),
            f"{e['safe'] - e['stock']:.1f} {e['unit']}".strip(),
        ])
    for e in medium[:top_n]:
        alert_rows.append([
            "关注", e["name"], e["category"] or "—",
            f"{e['stock']:.1f} {e['unit']}".strip(),
            f"{e['safe']:.1f} {e['unit']}".strip(),
            "—",
        ])
    alert_lines = (
        _markdown_table(
            ["状态", "食材", "分类", "剩余", "安全库存", "缺口"],
            alert_rows, right_align={3, 4, 5})
        if alert_rows else ["", "(无需补货或需关注的食材)"]
    )

    answer = (
        f"**库存预警（{_date_text(max_date)}）:**\n"
        f"- 需要立即补货 **{len(high)} 项**, 关注 {len(medium)} 项, 正常 {len(ok)} 项\n"
        + "\n".join(alert_lines) + "\n\n"
        f"建议动作:\n"
        f"1. 需立即补货的食材今天下单，优先安排高频用量的食材避免断货影响出品。\n"
        f"2. 接近安全库存的食材纳入未来三天的进货计划，避免临时缺货。\n"
        f"3. 定期核对补货点和安全库存设置是否符合实际用量，避免虚高或虚低导致误判。"
    )

    charts = [{
        "chartType": "bar",
        "title": "库存预警分布",
        "xAxis": {"data": ["需补货", "关注", "正常"]},
        "series": [{
            "name": "食材数量", "type": "bar",
            "data": [len(high), len(medium), len(ok)],
        }],
    }] if rows else []

    return OpsAnswer(
        code="RESTAURANT_OPS_INVENTORY_WARNING",
        title="库存预警",
        answer_text=answer,
        charts=charts,
        kpis=[
            {"title": "需补货", "value": len(high), "rawValue": len(high)},
            {"title": "关注", "value": len(medium), "rawValue": len(medium)},
            {"title": "正常", "value": len(ok), "rawValue": len(ok)},
        ],
        meta={
            "snapshot_date": _date_text(max_date),
            "high_count": len(high), "medium_count": len(medium), "ok_count": len(ok),
            "high_ingredients": [e["name"] for e in high],
        },
    )


async def resolve_staffing_advice(
    smartbi_pool,
    factory_id: str,
    *,
    role: Optional[str] = None,
    query: Optional[str] = None,
) -> OpsAnswer:
    """Forecast-first staffing answer backed by the restaurant FactBook.

    The old resolver inferred a future shortage from historical
    ``actual_orders_per_staff < target``.  That direction is invalid: low
    historical productivity can mean weak demand, excess staffing, ramp-up, or
    many other things.  The replacement predicts future guests from bookings
    plus independent 7/30/365-day POS windows, then applies role skill/hour
    constraints.  Historical productivity remains attached as evidence only.

    A successful answer always includes an LLM narrative routed through the
    existing shared provider chain.  Numerical lines are rendered by code from
    the FactBook; the LLM validator rejects any model-authored digit.
    """
    from smartbi.services.restaurant.staffing_forecast import (
        RestaurantStaffingService,
        requests_non_forecast_staffing_window,
    )

    if requests_non_forecast_staffing_window(query or ""):
        return OpsAnswer(
            code="RESTAURANT_OPS_STAFFING_ADVICE",
            title="预测排班范围需确认",
            answer_text=(
                "这条问题问的是历史或当前时段表现，不能把它偷换成明天的预测排班。"
                "未来预测排班目前支持“明天”“下周”或“下个月”；"
                "历史人效只作为预测依据，不能单独用来判断缺人。"
            ),
            charts=[],
            kpis=[],
            meta={
                "missing_reference": "future_staffing_horizon",
                "supported_horizons": ["tomorrow", "week", "month"],
                "historical_productivity_rule": "evidence_only_not_gap_input",
            },
        )

    service = RestaurantStaffingService(smartbi_pool)
    try:
        result = await service.answer_question(
            factory_id,
            query or "明天怎么排班",
            role=role,
        )
    except Exception as exc:
        logger.exception("forecast staffing answer failed for %s", factory_id)
        return OpsAnswer(
            code="RESTAURANT_OPS_STAFFING_ADVICE",
            title="预测排班暂不可用",
            answer_text=(
                "预测 FactBook 或大模型解释链本次未完成，因此没有展示可能方向错误的排班人数。"
                "请稍后重试；系统不会退回到“历史实际人效低于目标就补人”的旧判断。"
            ),
            charts=[],
            kpis=[],
            meta={
                "no_data": True,
                "llm_required": True,
                "llm_used": False,
                "error_type": type(exc).__name__,
                "historical_productivity_rule": "evidence_only_not_gap_input",
            },
        )

    dashboard = result["dashboard"]
    rows = sorted(
        dashboard.get("summary_rows") or [],
        key=lambda row: (row.get("positive_gap") or 0, row.get("predicted_guests") or 0),
        reverse=True,
    )
    summary = dashboard.get("summary") or {}
    chart_rows = rows[:12]
    return OpsAnswer(
        code="RESTAURANT_OPS_STAFFING_ADVICE",
        title=f"{dashboard.get('horizon_label', '未来')}预测排班",
        answer_text=result["answer_text"],
        charts=[{
            "chartType": "bar",
            "title": "门店时段建议人数与现有人数",
            "xAxis": {"data": [f"{row['store_name']}-{row['daypart']}" for row in chart_rows]},
            "series": [
                {"name": "建议人数", "type": "bar", "data": [row["recommended_staff"] for row in chart_rows]},
                {"name": "现有人数", "type": "bar", "data": [row["current_staff"] for row in chart_rows]},
            ],
        }] if chart_rows else [],
        kpis=[
            {"title": "预测客流", "value": summary.get("predicted_guests", 0), "rawValue": summary.get("predicted_guests", 0)},
            {"title": "当前预订", "value": summary.get("reserved_guests", 0), "rawValue": summary.get("reserved_guests", 0)},
            {"title": "正向缺口", "value": summary.get("positive_gap", 0), "rawValue": summary.get("positive_gap", 0)},
            {"title": "置信度", "value": f"{summary.get('confidence_pct', 0)}%", "rawValue": summary.get("confidence_pct", 0)},
        ],
        meta={
            "horizon": result["horizon"],
            "llm_required": True,
            "llm_used": result["llm_used"],
            "llm_numeric_authorship": result["llm_numeric_authorship"],
            "factbook": result["factbook"],
            "reservation_sources": dashboard.get("sources") or [],
            "historical_productivity_rule": "evidence_only_not_gap_input",
            "dashboard": dashboard,
        },
    )


from smartbi.gold.restaurant.restaurant_playbook import resolve_playbook as _resolve_playbook

async def resolve_channel_mix(
    smartbi_pool, factory_id: str, days: int = 30, *,
    role: Optional[str] = None, query: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
) -> OpsAnswer:
    """堂食 vs 外卖 渠道拆分 (R31 — Java 工具面吸收第一枪)。

    ⚠️ order_type 的落库形态**按租户不同**: 本函数最初照 DEMO_REST 写(中文值),
    而 MOCK_REST 落的是 dine_in / takeaway / groupon。不归一的话
    `for name in ("堂食","外卖")` 两次都取不到 —— 2026-07-30 prod 实拍: 占比、
    KPI 一条没出, 兜底循环把裸英文码直接甩给老板。见 _normalize_order_type。

    此前「外卖占了几成」依赖 Java restaurant_order_type_mix_gold, 误路由
    事故两次 (描述窃取/LLM 抖动)。fact_pos_transaction.order_type 在
    DEMO_REST 全史覆盖, python 直算; 未标注渠道的单量如实披露不摊派。
    金额是价格权限数据 — 非价格角色只出单数与占比。
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    can_see_money = bool(role) and role in PRICE_VIEW_ROLES

    exact_start = date_range[0] if date_range else None
    exact_end = date_range[1] if date_range else None
    async with smartbi_pool.acquire() as conn:
        await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
        rows = await conn.fetch(
            """
            WITH anchor AS (
                SELECT COALESCE($4::date, (
                    SELECT MAX(date) FROM fact_pos_transaction
                     WHERE factory_id = $1 AND order_type IS NOT NULL
                )) AS end_date
            )
            SELECT t.order_type,
                   COUNT(*)::int AS bills,
                   SUM(COALESCE(t.net_amount, 0))::float AS revenue,
                   MIN(t.date) AS window_start,
                   MAX(t.date) AS window_end
              FROM fact_pos_transaction t
              CROSS JOIN anchor
             WHERE t.factory_id = $1
               AND anchor.end_date IS NOT NULL
               AND t.date >= COALESCE($3::date, anchor.end_date - ($2::int))
               AND t.date <= COALESCE($4::date, anchor.end_date)
             GROUP BY t.order_type
            """,
            factory_id, days, exact_start, exact_end,
        )
        # 按门店再拆一层。**数据撑得住**(2026-08-17 prod 实测, 逐租户 set_config):
        #   RES_3101_009 61,933 单 / store_id 空值 **0** / 30 家店 / 渠道 3 种
        #   DEMO_REST    55,376 单 / store_id 空值 **0** / 27 家店 / 渠道 3 种
        # ⛔ 补能力的依据是**它真能出**(与 SALES_SUMMARY「per-store Top-N table
        #    in addition to the chain aggregate」同一个形状), 不是「希望它能出」。
        # 🔑 「哪家店外卖占比最高」是连锁老板最自然的问题之一, 而此前它撞维度闸。
        store_rows = await conn.fetch(
            """
            WITH anchor AS (
                SELECT COALESCE($4::date, (
                    SELECT MAX(date) FROM fact_pos_transaction
                     WHERE factory_id = $1 AND order_type IS NOT NULL
                )) AS end_date
            )
            SELECT s.name AS store_name, t.order_type,
                   COUNT(*)::int AS bills,
                   SUM(COALESCE(t.net_amount, 0))::float AS revenue
              FROM fact_pos_transaction t
              CROSS JOIN anchor
              JOIN dim_store s
                ON s.store_id = t.store_id AND s.factory_id = t.factory_id
             WHERE t.factory_id = $1
               AND anchor.end_date IS NOT NULL
               AND t.date >= COALESCE($3::date, anchor.end_date - ($2::int))
               AND t.date <= COALESCE($4::date, anchor.end_date)
             GROUP BY s.name, t.order_type
            """,
            factory_id, days, exact_start, exact_end,
        )
    # order_type 先归一再聚合: 同一个渠道可能以中文或英文码落库(本函数最初照
    # DEMO_REST 的中文值写, MOCK_REST 落的是 dine_in/takeaway/groupon), 归一之后
    # 两个来源合并计数, 而不是各算各的。
    typed: Dict[str, Dict[str, Any]] = {}
    untyped_bills = 0
    for row in rows:
        bucket = _normalize_order_type(row["order_type"])
        if bucket is None:
            untyped_bills += int(row["bills"])
            continue
        existing = typed.get(bucket)
        if existing is None:
            typed[bucket] = {
                "bills": int(row["bills"]),
                "revenue": float(row["revenue"] or 0.0),
                "window_start": row["window_start"],
                "window_end": row["window_end"],
            }
        else:
            existing["bills"] += int(row["bills"])
            existing["revenue"] += float(row["revenue"] or 0.0)
            existing["window_start"] = min(existing["window_start"], row["window_start"])
            existing["window_end"] = max(existing["window_end"], row["window_end"])
    if not typed:
        requested = (
            _range_text(exact_start, exact_end)
            if exact_start and exact_end else f"近 {days} 天"
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_CHANNEL_MIX",
            title=f"堂食外卖拆分（{requested}）",
            answer_text=(
                # ⛔ 2026-08-13 去黑话:「字段」店长不说, 他说「收银台有没有把
                #    堂食/外卖分开记」。
                f"{requested}的订单里没有分堂食还是外卖，"
                "所以占比算不出来 —— 我也不会拿总单量摊一个数给你。"
                "麻烦确认一下收银台有没有把这两类分开记、数据有没有同步过来。"
            ),
            charts=[], kpis=[],
            meta={"no_data": True},
        )
    window_start = min(r["window_start"] for r in typed.values())
    window_end = max(r["window_end"] for r in typed.values())
    window_label = (
        _range_text(exact_start, exact_end)
        if exact_start and exact_end
        else _actual_window_text(window_start, window_end, days)
    )
    total_bills = sum(int(r["bills"]) for r in typed.values())
    total_rev = sum(float(r["revenue"]) for r in typed.values())
    lines = [f"**堂食 vs 外卖（{window_label}）：**", ""]
    kpis = []
    channel_rows = []
    for name in ("堂食", "外卖"):
        r = typed.get(name)
        if r is None:
            continue
        bills = int(r["bills"])
        rev = float(r["revenue"])
        bill_pct = bills / total_bills * 100 if total_bills else 0.0
        rev_pct = rev / total_rev * 100 if total_rev else 0.0
        # 渠道构成本来就是表格(渠道/营收/占比/单量/占比), 项目符号让人没法竖着比。
        # ⚠️ 两个循环合成**一张**表: 堂食/外卖有金额, 其它渠道只有单量 —— 后者的
        #    金额列留空, 而不是另起一张表。两张表会让人以为是两批不同口径的数。
        if can_see_money:
            channel_rows.append([name, f"¥{rev:,.0f}", f"{rev_pct:.1f}%",
                                 f"{bills:,}", f"{bill_pct:.1f}%"])
        else:
            channel_rows.append([name, f"{bills:,}", f"{bill_pct:.1f}%"])
        kpis.append({"title": f"{name}单量", "value": f"{bills:,}", "rawValue": bills})
    for name, r in typed.items():
        if name not in ("堂食", "外卖"):
            other_bills = int(r["bills"])
            other_pct = other_bills / total_bills * 100 if total_bills else 0.0
            if can_see_money:
                channel_rows.append([name, "—", "—", f"{other_bills:,}",
                                     f"{other_pct:.1f}%"])
            else:
                channel_rows.append([name, f"{other_bills:,}", f"{other_pct:.1f}%"])
    if channel_rows:
        lines.extend(_markdown_table(
            (["渠道", "营收", "营收占比", "单量", "单量占比"] if can_see_money
             else ["渠道", "单量", "单量占比"]),
            channel_rows,
            right_align={1, 2, 3, 4} if can_see_money else {1, 2}))
    if untyped_bills:
        lines.append("")
        lines.append(f"> 另有 {untyped_bills:,} 单未标注渠道，不在以上拆分内。")

    # ⛔ 归一**复用** `_normalize_order_type`, 不在 SQL 里再写一份 ——
    #    同一个口径两份一定会漂(形态 D), 而漂的表现是「总表和门店表对不上」,
    #    不报错, 只是两个数。
    per_store: Dict[str, Dict[str, float]] = {}
    for row in store_rows:
        bucket = _normalize_order_type(row["order_type"])
        if bucket is None:          # 未标注渠道的单不摊派到任何渠道
            continue
        slot = per_store.setdefault(
            row["store_name"], {"堂食": 0, "外卖": 0, "其它": 0, "revenue": 0.0})
        key = bucket if bucket in ("堂食", "外卖") else "其它"
        slot[key] += int(row["bills"])
        slot["revenue"] += float(row["revenue"] or 0.0)

    # 单店租户不需要这张表 —— 一行的表格只是多两条竖线。
    if len(per_store) >= 2:
        ranked = sorted(
            per_store.items(),
            key=lambda kv: (kv[1]["外卖"] / (kv[1]["堂食"] + kv[1]["外卖"] + kv[1]["其它"])
                            if (kv[1]["堂食"] + kv[1]["外卖"] + kv[1]["其它"]) else 0.0),
            reverse=True,
        )
        store_table_rows = []
        for name, v in ranked:
            store_total = v["堂食"] + v["外卖"] + v["其它"]
            takeaway_pct = v["外卖"] / store_total * 100 if store_total else 0.0
            cells = [name, f"{takeaway_pct:.1f}%", f"{int(v['外卖']):,}",
                     f"{int(v['堂食']):,}"]
            if can_see_money:       # 金额是价格权限数据, 与上面那张表同一条规则
                cells.append(f"¥{v['revenue']:,.0f}")
            store_table_rows.append(cells)
        headers = ["门店", "外卖占比", "外卖单量", "堂食单量"]
        if can_see_money:
            headers.append("营收")
        lines.append("")
        lines.append(f"**各门店渠道构成（按外卖占比排序，{len(ranked)} 家）：**")
        lines.extend(_markdown_table(
            headers, store_table_rows,
            right_align={1, 2, 3, 4} if can_see_money else {1, 2, 3}))

    lines.append("")
    lines.append(_closing("CHANNEL_MIX_CLOSING", query))
    return OpsAnswer(
        code="RESTAURANT_OPS_CHANNEL_MIX",
        title=f"堂食外卖拆分（{window_label}）",
        answer_text="\n".join(lines),
        charts=[], kpis=kpis,
        meta={"window_label": window_label, "untyped_bills": untyped_bills,
              "scope_matches_request": True},
    )


async def resolve_supplier_price(
    smartbi_pool, factory_id: str, days: int = 90, *,
    role: Optional[str] = None, query: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
) -> OpsAnswer:
    """供应商比价 —— 「哪个供应商报价最贵」。

    🔴 存在的理由: 这句话此前落到 `OUT_OF_DOMAIN`(与天气、股票同一档), 意思是
       「这不属于餐饮经营数据」—— 那是**错的**。供应商报价当然属于餐饮经营,
       只是没有终点。2026-08-08 的飞轮候选里它出现 10 次, 差点被晋升成
       OUT_OF_DOMAIN 而**对所有租户永久关门**。

    ⛔ 口径见 `gold.queries.supplier_price_spread`: 跨食材比供应商是无意义的,
       只在**同一食材同一单位内**比较。这里不重复那套判断。

    ⚠️ 2026-08-08 实测: `agg_supplier_price` **全库 0 行**(0 个租户接入过)。
       所以今天这个 resolver 对任何租户都会走「没有数据」那条路 —— 那正是它的
       价值所在: 把一个**错误答案**换成**正确答案**, 并说清缺的是哪份数据。
       ⛔ 不许因为没数据就退回 OUT_OF_DOMAIN。

    金额是价格权限数据 —— 非价格角色只出价差百分比, 不出绝对单价。
    """
    from smartbi.gold.queries import supplier_price_coverage, supplier_price_spread
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    can_see_money = bool(role) and role in PRICE_VIEW_ROLES
    end = date_range[1] if date_range else None
    start = date_range[0] if date_range else None
    if start is None or end is None:
        end = date.today()
        start = end - timedelta(days=max(int(days), 1) - 1)

    coverage = await supplier_price_coverage(smartbi_pool, factory_id, (start, end))
    if int(coverage.get("observation_count") or 0) == 0:
        return OpsAnswer(
            code="RESTAURANT_OPS_SUPPLIER_PRICE",
            title="供应商比价",
            answer_text=(
                "还没有供应商报价数据，因此比不了价。\n\n"
                "这不是「不归我管」——供应商报价属于经营数据，缺的是**采购单据里的"
                "供应商单价还没有接入**（数据表 `agg_supplier_price` 目前为空）。\n"
                "接入之后这里可以回答：同一食材各家供应商的报价差多少、"
                "哪家最贵哪家最便宜。"
            ),
            charts=[], kpis=[],
            meta={"no_data": True, "missing_source": "agg_supplier_price"},
        )

    result = await supplier_price_spread(smartbi_pool, factory_id, (start, end))
    items = result.get("items") or []
    window_label = _range_text(start, end)
    if not items:
        return OpsAnswer(
            code="RESTAURANT_OPS_SUPPLIER_PRICE",
            title=f"供应商比价（{window_label}）",
            answer_text=(
                f"{window_label}有供应商报价数据，但**没有任何一种食材同时来自两家以上"
                "供应商**，因此没有可比的对象。单一供应商的价格高低无从比较，"
                "不会拿不同食材的单价互相比。"
            ),
            charts=[], kpis=[],
            meta={"window_label": window_label, "comparable_items": 0},
        )

    lines = [f"**同一食材的供应商报价差（{window_label}）：**", ""]
    kpis = []
    for it in items[:5]:
        name, unit = it["ingredient_name"], it["unit"]
        spread = it.get("spread_pct")
        if can_see_money:
            lines.append(
                f"- {name}（{unit}）：最贵 {it['highest_supplier']} ¥{it['highest_price']:,.2f}，"
                f"最便宜 {it['lowest_supplier']} ¥{it['lowest_price']:,.2f}，"
                f"差 **{spread:.1f}%**（{it['supplier_count']} 家供应商）"
            )
        else:
            lines.append(
                f"- {name}（{unit}）：最贵 {it['highest_supplier']}，"
                f"最便宜 {it['lowest_supplier']}，差 **{spread:.1f}%**"
                f"（{it['supplier_count']} 家供应商）"
            )
    top = items[0]
    kpis.append({"title": "最大价差食材", "value": top["ingredient_name"]})
    if top.get("spread_pct") is not None:
        kpis.append({"title": "最大价差", "value": f"{top['spread_pct']:.1f}%",
                     "rawValue": top["spread_pct"]})

    lines.append("")
    lines.append(_closing("SUPPLIER_PRICE_CLOSING", query))
    return OpsAnswer(
        code="RESTAURANT_OPS_SUPPLIER_PRICE",
        title=f"供应商比价（{window_label}）",
        answer_text="\n".join(lines),
        charts=[], kpis=kpis,
        meta={"window_label": window_label, "comparable_items": len(items),
              "scope_matches_request": True},
    )


async def resolve_discount_summary(
    smartbi_pool, factory_id: str, days: int = 30, *,
    role: Optional[str] = None, query: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
) -> OpsAnswer:
    """折扣力度 —— 「这段时间让利了多少、占营收几成」。

    ⛔ 口径不自己算, 复用 `gold.queries.discount_summary`: 折扣总额与占营收比
       都取自 `agg_daily` 的**同一个日粒度窗口**。那个函数的注释记着 C1 那次事故 ——
       曾经用整月的 `agg_discount` 除以一周的营收, 比率错得离谱还被当成真值索引。
       **一个指标一处定义**, 这里只负责把它讲成人话。

    ⚠️ 构成(满减/会员折扣/团购券)来自 `agg_discount`, 与总额是**两个数据源**:
       MOCK_REST 实测有总额(¥387 万)但构成表 0 行。缺构成时如实说「只有总额」,
       ⛔ 不拿总额编一个构成出来。

    DESCRIPTIVE only —— 只报让了多少利, **绝不声称折扣「带来了」多少增量营收**:
    这个 schema 里没有任何东西能支撑那个因果说法(同 `discount_summary` 的约束)。

    金额是价格权限数据 —— 非价格角色只出**占营收比**(百分比不泄露绝对额)。
    """
    from smartbi.gold.queries import discount_summary
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    can_see_money = bool(role) and role in PRICE_VIEW_ROLES

    start = date_range[0] if date_range else None
    end = date_range[1] if date_range else None
    if start is None or end is None:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            anchor = await conn.fetchval(
                "SELECT MAX(date) FROM agg_daily WHERE factory_id = $1", factory_id
            )
        if anchor is None:
            return OpsAnswer(
                code="RESTAURANT_OPS_DISCOUNT_SUMMARY",
                title="折扣力度",
                answer_text=(
                    # ⛔ 2026-08-13 去黑话:「订单表里的折扣字段」是库表口径,
                    #    店长关心的是「那是另一本账」。
                    "还没有每天汇总好的数据，所以折扣一共让了多少、占营收多少，"
                    "这次算不出来。我也不会拿单据上的折扣临时凑一个 —— "
                    "那是另一本账，两个数对不上反而更麻烦。"
                ),
                charts=[], kpis=[], meta={"no_data": True},
            )
        end = anchor
        start = end - timedelta(days=max(int(days), 1) - 1)

    result = await discount_summary(smartbi_pool, factory_id, (start, end))
    total = float(result.get("total_discount_amount") or 0.0)
    revenue = float(result.get("total_revenue") or 0.0)
    share = result.get("revenue_share_pct")
    items = result.get("discounts") or []
    window_label = _range_text(start, end)

    if revenue <= 0:
        return OpsAnswer(
            code="RESTAURANT_OPS_DISCOUNT_SUMMARY",
            title=f"折扣力度（{window_label}）",
            answer_text=(
                f"{window_label}没有营收数据，折扣占比没有分母，因此不给出比率。"
            ),
            charts=[], kpis=[], meta={"no_data": True, "window_label": window_label},
        )

    lines = [f"**折扣力度（{window_label}）：**", ""]
    kpis = []
    if share is not None:
        lines.append(f"- 折扣占营收 **{float(share):.1f}%**")
        kpis.append({"title": "折扣占营收", "value": f"{float(share):.1f}%",
                     "rawValue": float(share)})
    if can_see_money:
        lines.append(f"- 折扣总额 ¥{total:,.0f}（营收 ¥{revenue:,.0f}，应收口径）")
        kpis.append({"title": "折扣总额", "value": f"¥{total:,.0f}", "rawValue": total})

    if items:
        lines.append("")
        lines.append("**构成：**")
        # 2026-08-11: 项目符号改 markdown 表格 —— 构成本来就是「类型 / 金额 / 占比」
        # 三列。⛔ 只转**构成**这一段: 上面「折扣占营收」「折扣总额」是两条单条事实,
        # 表格化反而更差(两行一列的表比一句话难读)。
        # 金额是价格权限数据 —— 非价格角色整列不出现, 而不是留空: 留空等于告诉他
        # 「这里有个数但不给你看」, 而契约是他**看不到金额口径**, 不是被遮挡。
        lines.extend(_markdown_table(
            ["折扣类型", "金额", "占折扣"] if can_see_money else ["折扣类型", "占折扣"],
            [
                ([it.get("discount_name") or "未命名",
                  f"¥{float(it.get('amount') or 0):,.0f}",
                  f"{float(it.get('share_pct') or 0):.1f}%"]
                 if can_see_money else
                 [it.get("discount_name") or "未命名",
                  f"{float(it.get('share_pct') or 0):.1f}%"])
                for it in items
            ],
            right_align={1, 2} if can_see_money else {1}))
    else:
        lines.append("")
        lines.append(
            "> 只有折扣总额，没有分类型的构成数据（满减/会员折扣/团购券未落库），"
            "因此不拆分构成。"
        )

    lines.append("")
    lines.append(_closing("DISCOUNT_CLOSING", query))
    return OpsAnswer(
        code="RESTAURANT_OPS_DISCOUNT_SUMMARY",
        title=f"折扣力度（{window_label}）",
        answer_text="\n".join(lines),
        charts=[], kpis=kpis,
        meta={
            "window_label": window_label,
            "revenue_share_pct": float(share) if share is not None else None,
            "composition_available": bool(items),
            "scope_matches_request": True,
        },
    )


async def resolve_daypart_performance(
    smartbi_pool, factory_id: str, days: int = 30, *,
    role: Optional[str] = None, query: Optional[str] = None,
    date_range: Optional[Tuple[Optional[date], Optional[date]]] = None,
) -> OpsAnswer:
    """历史时段表现 —— 「哪个时段生意最好」。

    🔴 2026-08-07 之前这句问句拿不到答案。链路是: T3 给
    `SALES_SUMMARY + dimensions=('time',)`, 而该 resolver 只声明 `{store}`,
    执行前被拦成「查询维度超出计划 resolver 的能力范围」; 把路由修到排班
    resolver 之后, 它又**正确地**拒绝(「不能把它偷换成明天的预测排班」) ——
    预测排班只做未来。**缺的一直是这个终点。**

    ⛔ 时段边界用 `daypart.DAYPART_CASE_SQL`, 不在这里再写一份 —— 预测排班用的
    是同一段, 两处各写会让「晚市」在两个页面上是两段不同的时间。

    ⚠️ `agg_daily_order_type_meal.meal_period` **不能用**: 实测 MOCK_REST 近 30 天
    该列全是「未分类」(ETL 没物化时段)。这里从 `fact_pos_transaction` 按时间戳现算。

    ⚠️ `time IS NOT NULL` 不能省: `EXTRACT(HOUR FROM NULL)` 返回 NULL, 会整批落进
    ELSE 被算成「夜宵」—— 那不是夜宵, 是没时间戳。没时间戳的单量如实披露, 不摊派。

    金额是价格权限数据 —— 非价格角色只出单量与占比(与 resolve_channel_mix 同规矩)。
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    from smartbi.gold.restaurant.daypart import DAYPART_CASE_SQL
    can_see_money = bool(role) and role in PRICE_VIEW_ROLES

    exact_start = date_range[0] if date_range else None
    exact_end = date_range[1] if date_range else None

    async with smartbi_pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id
            )
            rows = await conn.fetch(
                f"""
                SELECT {DAYPART_CASE_SQL} AS daypart,
                       COUNT(*)::int                              AS bills,
                       SUM(COALESCE(gross_amount,0))::float       AS revenue,
                       SUM(COALESCE(customer_count,0))::int       AS guests,
                       MIN(date) AS window_start, MAX(date) AS window_end
                  FROM fact_pos_transaction
                 WHERE factory_id = $1
                   AND time IS NOT NULL
                   AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
                   AND date <= COALESCE($4::date, CURRENT_DATE)
                 GROUP BY 1
                """,
                factory_id, days, exact_start, exact_end,
            )
            untimed = await conn.fetchrow(
                """
                SELECT COUNT(*)::int AS n FROM fact_pos_transaction
                 WHERE factory_id = $1 AND time IS NULL
                   AND date >= COALESCE($3::date, CURRENT_DATE - ($2::int))
                   AND date <= COALESCE($4::date, CURRENT_DATE)
                """,
                factory_id, days, exact_start, exact_end,
            )

    untimed_bills = int((untimed or {}).get("n") or 0)
    if not rows:
        requested = (
            _range_text(exact_start, exact_end)
            if exact_start and exact_end else f"近 {days} 天"
        )
        # ⛔ 有未带时间戳的单时必须说出来 —— 否则「没有数据」会被读成「没有生意」。
        tail = (
            f"（另有 {untimed_bills:,} 单没有下单时间，无法归入任何时段）"
            if untimed_bills else ""
        )
        return OpsAnswer(
            code="RESTAURANT_OPS_DAYPART_PERFORMANCE",
            title=f"时段表现（{requested}）",
            answer_text=(
                f"{requested}没有带下单时间的订单，无法按时段拆分，"
                f"也不会用全天合计替代。{tail}"
            ),
            charts=[], kpis=[],
            meta={"no_data": True, "untimed_bills": untimed_bills},
        )

    window_start = min(r["window_start"] for r in rows)
    window_end = max(r["window_end"] for r in rows)
    window_label = (
        _range_text(exact_start, exact_end)
        if exact_start and exact_end
        else _actual_window_text(window_start, window_end, days)
    )
    total_bills = sum(int(r["bills"]) for r in rows)
    total_rev = sum(float(r["revenue"]) for r in rows)
    # 排序按业务量: 能看金额时按营收, 否则按单量 —— 否则非价格角色看到的「最好」
    # 会是按一个他看不见的量排的。
    ordered = sorted(
        rows,
        key=(lambda r: float(r["revenue"])) if can_see_money else (lambda r: int(r["bills"])),
        reverse=True,
    )

    lines = [f"**各时段表现（{window_label}）：**", ""]
    kpis = []
    # 2026-08-11: 项目符号改 markdown 表格 —— 时段对比本来就是竖着比的数。
    # ⚠️ 这个循环同时在建 kpis, 表格只接管**文本**那一半, kpis 原样留在循环里:
    #    一起重写最容易漏掉后者, 而漏了不会报错, 只是 KPI 卡片空了。
    # ⚠️ 有金额 / 无金额两个分支合成**一张**表(列数不同, 由权限决定整列在不在),
    #    不是两张。顺带把单量占比补进有金额那一版 —— 原来只有无金额分支给它,
    #    同一张表里缺一列会被读成「这个时段没有占比」。
    daypart_rows = []
    for r in ordered:
        name = r["daypart"]
        bills = int(r["bills"])
        rev = float(r["revenue"])
        bill_pct = bills / total_bills * 100 if total_bills else 0.0
        if can_see_money:
            rev_pct = rev / total_rev * 100 if total_rev else 0.0
            avg = rev / bills if bills else 0.0
            daypart_rows.append([
                name, f"¥{rev:,.0f}", f"{rev_pct:.1f}%",
                f"{bills:,}", f"{bill_pct:.1f}%", f"¥{avg:,.1f}",
            ])
        else:
            daypart_rows.append([name, f"{bills:,}", f"{bill_pct:.1f}%"])
        kpis.append({"title": f"{name}单量", "value": f"{bills:,}", "rawValue": bills})
    lines.extend(_markdown_table(
        (["时段", "营收", "营收占比", "单量", "单量占比", "客单价"] if can_see_money
         else ["时段", "单量", "单量占比"]),
        daypart_rows,
        right_align={1, 2, 3, 4, 5} if can_see_money else {1, 2}))

    top = ordered[0]["daypart"]
    if untimed_bills:
        lines.append("")
        lines.append(f"> 另有 {untimed_bills:,} 单没有下单时间，不在以上拆分内。")
    lines.append("")
    lines.append(
        f"生意最好的是**{top}**。时段差距大时先看排班与备货是否跟着时段走；"
        "弱时段适合用套餐或时段价拉客流，不必按全天平均去配人。"
    )
    return OpsAnswer(
        code="RESTAURANT_OPS_DAYPART_PERFORMANCE",
        title=f"时段表现（{window_label}）",
        answer_text="\n".join(lines),
        charts=[], kpis=kpis,
        meta={"window_label": window_label, "untimed_bills": untimed_bills,
              "top_daypart": top, "scope_matches_request": True},
    )


# ── 涉钱答案的角色闸 (2026-08-01) ─────────────────────────────────────────
#
# 2026-08-01 prod 实拍(MOCK_REST, 同一问句只换角色):
#   哪家店毛利最好      STORE_MARGIN       老板 ¥34,959,425 / 后厨 看不到  <- 有门
#   损耗金额最高的食材  WASTAGE_TOP        老板 ¥278,254.85 / 后厨 ¥278,254.85
#   采购花了多少钱      REQUISITION_TREND  老板 ¥7,094,935  / 后厨 ¥7,094,935
#   盘点亏了多少        STOCK_SHORTAGE     老板 ¥5,836.21   / 后厨 ¥5,836.21
#
# 对照组(STORE_MARGIN)证明脱敏机制本身没坏 —— 它在声明了的地方精确生效、在没声明
# 的地方精确失效。根因是**机制**: resolve_by_code 按签名过滤 kwargs, 没声明 `role`
# 的 resolver 拿不到它(它自己的 docstring 就写着 "legacy resolvers silently
# ignore role")。与 #2076 丢 date_range 同一个机制, 那次答错时间窗, 这次是钱不脱敏。
#
# 于是 RBAC 成了**逐个 resolver 自愿加入, 而「没加入」没有任何东西会发现**:
# 9 个涉钱 resolver 里 5 个加了、4 个没加。
#
# 修法不是给那 4 个各补一个参数(下一个新 resolver 会再犯), 而是:
#   1. 把「这个 intent 的答案里有没有钱」变成**一张显式的表**;
#   2. 闸放在 resolve_by_code 里、**查库之前**短路 —— 不是查完再擦文本
#      (擦文本随时会漏掉一种新的金额写法, 而且数据已经被取出来了);
#   3. 用例 test_every_resolver_is_classified 让新增 resolver **必须**分类, 否则红。
#
# 各 resolver 内既有的 PRICE_VIEW_ROLES 判断**保留**: agent runtime 的适配器会
# 绕过 resolve_by_code 直接调它们, 那条路径仍需自我保护。两处都是「拦」, 方向一致,
# 不会分叉。
# 全部**可能吐出金额**的 intent。这张表是唯一的登记处, 完整性由
# test_every_resolver_is_classified 强制 —— 新增 resolver 不分类就红。
_MONEY_BEARING_INTENTS: frozenset = frozenset({
    "RESTAURANT_OPS_DAYPART_PERFORMANCE",
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_TREND_ANALYSIS",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
    "RESTAURANT_OPS_CHANNEL_MIX",
    "RESTAURANT_OPS_DISCOUNT_SUMMARY",
    "RESTAURANT_OPS_SUPPLIER_PRICE",
    "RESTAURANT_OPS_RECIPE_COST",
    "RESTAURANT_OPS_REQUISITION_TREND",
    "RESTAURANT_OPS_WASTAGE_TOP",
    "RESTAURANT_OPS_STOCK_SHORTAGE",
})

# 其中**答案有非金额内核**的: resolver 自己就地脱敏(打星/置 None/换量视角),
# 无权限角色仍然拿得到趋势形状、单量对比这些不涉钱的信息。这类正常分发,
# 中央闸不拦 —— 拦了就是把本来能给的能力也一起拿走。
#
# ⛔ 结构不变量(test_self_masking_resolvers_can_actually_see_the_role):
#    声明在这里的 resolver **签名里必须有 `role`**。没有 role 就根本收不到角色
#    (resolve_by_code 按签名过滤 kwargs, 见其 docstring "legacy resolvers
#    silently ignore role"), 也就不可能脱敏 —— 那正是 2026-08-01 泄露的形态:
#    声明了「我自己会处理」而实际上处理不了。
_MONEY_SELF_MASKING_INTENTS: frozenset = frozenset({
    # 签名里有 role, 非价格角色只出单量与占比(与 resolve_channel_mix 同规矩)。
    "RESTAURANT_OPS_DAYPART_PERFORMANCE",
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_TREND_ANALYSIS",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
    "RESTAURANT_OPS_CHANNEL_MIX",
    "RESTAURANT_OPS_DISCOUNT_SUMMARY",
    "RESTAURANT_OPS_SUPPLIER_PRICE",
})

# 剩下这些**整个答案就是钱**(领料总额/盘亏金额/损耗金额/菜品成本), 没有可保留的
# 非金额内核 → 中央拦截, 并给出**数量视角**的替代问法。
# 📌 记账(本次不做): 更好的答案是让这几个 resolver 在无权限时**直接答数量版**
#    而不是让用户再问一遍 —— 数据都在(损耗/领料/盘点的 qty 列一直物化着)。
#    那是能力增强, 与本次的安全修复分开做。
_MONEY_GATE_ALTERNATIVES: Dict[str, str] = {
    "RESTAURANT_OPS_RECIPE_COST": "问「哪些菜卖得最好」看销量排名",
    "RESTAURANT_OPS_REQUISITION_TREND": "问「领料最多的是哪些食材」看用量排名",
    "RESTAURANT_OPS_WASTAGE_TOP": "问「损耗量最大的食材是哪些」看数量排名",
    "RESTAURANT_OPS_STOCK_SHORTAGE": "问「盘点差异最大的食材是哪些」看数量排名",
}

_NO_MONEY_INTENTS: frozenset = frozenset({
    "RESTAURANT_OPS_PLAYBOOK",           # 方法论文本, 无数据
    "RESTAURANT_OPS_CAPABILITIES",       # 能力说明
    "RESTAURANT_OPS_OUT_OF_DOMAIN",      # 域外拒答
    "RESTAURANT_OPS_STORE_DIRECTORY",    # 门店名录/家数
    "RESTAURANT_OPS_INVENTORY_WARNING",  # 库存水位, 函数注释已写明不读价格
    "RESTAURANT_OPS_STAFFING_ADVICE",    # 排班人效, 函数注释已写明不读价格
})


def _money_masked_answer(code: str) -> "OpsAnswer":
    """无价格权限时的统一回答 —— 说清为什么, 并给出能走的路。"""
    alternative = _MONEY_GATE_ALTERNATIVES.get(code) or "找管理员开通价格查看权限"
    return OpsAnswer(
        code=code,
        title="需要价格查看权限",
        answer_text=(
            "这个问题的答案包含金额（成本/营收/毛利），属于价格查看权限，"
            "当前角色不能查看。\n"
            f"可以换个不涉及金额的问法：{alternative}；"
            "如需金额数据请联系管理员开通价格查看权限。"
        ),
        charts=[],
        kpis=[],
        meta={"rbac_masked": True, "masked_reason": "price_view_permission"},
    )


def _role_may_see_money(role: Optional[str]) -> bool:
    """缺省 role 一律按无权限处理 —— fail-closed。"""
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES

    return bool(role) and role in PRICE_VIEW_ROLES


_RESOLVERS = {
    "RESTAURANT_OPS_DAYPART_PERFORMANCE": resolve_daypart_performance,
    "RESTAURANT_OPS_PLAYBOOK": _resolve_playbook,
    "RESTAURANT_OPS_CAPABILITIES": resolve_capabilities,
    "RESTAURANT_OPS_OUT_OF_DOMAIN": resolve_out_of_domain,
    "RESTAURANT_OPS_STORE_DIRECTORY": resolve_store_directory,
    "RESTAURANT_OPS_CHANNEL_MIX": resolve_channel_mix,
    "RESTAURANT_OPS_DISCOUNT_SUMMARY": resolve_discount_summary,
    "RESTAURANT_OPS_SUPPLIER_PRICE": resolve_supplier_price,
    "RESTAURANT_OPS_WASTAGE_TOP": resolve_wastage_top,
    "RESTAURANT_OPS_STOCK_SHORTAGE": resolve_stock_shortage,
    "RESTAURANT_OPS_RECIPE_COST": resolve_recipe_cost,
    "RESTAURANT_OPS_REQUISITION_TREND": resolve_requisition_trend,
    "RESTAURANT_OPS_GROSS_MARGIN": resolve_gross_margin,
    "RESTAURANT_OPS_STORE_MARGIN": resolve_store_margin,
    "RESTAURANT_OPS_SALES_SUMMARY": resolve_sales_summary,
    "RESTAURANT_OPS_TREND_ANALYSIS": resolve_trend_analysis,
    "RESTAURANT_OPS_INVENTORY_WARNING": resolve_inventory_warning,
    "RESTAURANT_OPS_STAFFING_ADVICE": resolve_staffing_advice,
}


def is_supported_restaurant_ops_code(code: Optional[str]) -> bool:
    """Return whether an internal caller supplied a known restaurant intent."""
    return isinstance(code, str) and code in _RESOLVERS


# 不经 `_RESOLVERS` 派发、但**确实按 spec.date_range 取数**的 intent。
#
# 🔴 2026-08-10: `RESTAURANT_OPS_BUSINESS_OPTIMIZATION` 走的是服务层
#    `_resolve_business_optimization`(它把 spec.date_range 原样传给
#    ComprehensiveSynthesisEngine), 压根不在 `_RESOLVERS` 里。于是
#    `resolver_supports_explicit_window` 查不到它 → 返回 False → 「换时间范围」
#    按钮被**误扣**。回归电池 [66]「这周全部门店营收怎么提高」因此长期红在
#    「按钮缺少最近7天」, 而系统其实完全能按那个窗口取数。
#
# 🔑 判据: **这个闸的载体比它查的那张表多。** 只查一个派发表 = 对第二条派发路径
#    完全沉默, 而沉默的方向是「误拒」—— 误拒不报错, 只是少给用户一个出口。
#
# ⛔ 这个集合必须**同时**驱动派发和窗口判定, 不能两处各写一份:
#    `restaurant_intent_service._dispatch` 直接 import 它来决定走哪条路,
#    所以「派发到服务层」与「承认它支持窗口」在源头上就是同一件事, 不可能漂。
_SERVICE_DISPATCHED_WINDOW_AWARE: frozenset = frozenset({
    "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
})


def resolver_supports_explicit_window(code: Optional[str]) -> bool:
    """这个 intent 的 resolver 会不会真正按**请求的时间窗**取数。

    判据就是 ``resolve_by_code`` 过滤 kwargs 用的那一条: 它只把 resolver 签名里
    声明过的参数传下去, 没声明的**静默丢弃** —— 不报错, 只是悄悄退回
    ``CURRENT_DATE - days`` 的滚动窗口。所以「签名里有没有 date_range」既决定
    窗口能不能传到, 也就决定了「换时间范围」按钮的承诺成不成立; 两者复用同一个
    机制, 不可能漂。

    ⛔ 不要改用 ``_RESOLVER_DIMENSIONS['time']``: 那张表里的 time 是「能不能**按**
    时间拆」(把结果按时间分组), 与「能不能**换**时间窗」无关, 实测两个方向都判错
    —— WASTAGE_TOP 没有 time 却真能换窗口(误拒), STAFFING_ADVICE 有 time 却拿不到
    date_range(误放, 更危险)。

    这是**必要条件而非充分条件**: 声明了不等于用对了。充分性由各 resolver 自己的
    测试保证(见 tests/test_restaurant_wastage_window.py)。
    """
    if (code or "") in _SERVICE_DISPATCHED_WINDOW_AWARE:
        # 服务层派发的那条路: 它拿的是整个 spec, 不是 date_range kwarg,
        # 所以签名探测看不见它。见上方常量的注释。
        return True
    resolver = _RESOLVERS.get(code or "")
    if resolver is None:
        return False
    return "date_range" in inspect.signature(resolver).parameters


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
        # ── 通用执行器并行路径 (2026-08-09) ──────────────────────────────
        # ⛔ **只在没有手写 resolver 时**才走这里 —— 现有 20 个格子行为逐字不变。
        #    20 个函数 = 20 个格子, 而「指标×维度×聚合」有 200+ 种组合;
        #    落在没写过的格子上原本直接 return None(答不出来), 现在交给登记表拼。
        # ⚠️ 它自己坏了返回 None, 与改动前逐字同义 —— 并行路径不该扩大失败面。
        spec = kwargs.get("spec")
        if spec is not None:
            try:
                from smartbi.gold.restaurant.generic_answer import try_generic_answer
                generic = await try_generic_answer(
                    spec, smartbi_pool, factory_id,
                    window_label=getattr(spec, "window_label", "") or "")
            except Exception:  # noqa: BLE001
                logger.exception("[resolve_by_code] 通用执行器异常, 按未知码处理")
                generic = None
            if generic and generic.get("served"):
                return OpsAnswer(
                    code=generic["code"], title=generic["title"],
                    answer_text=generic["answer_text"], charts=[], kpis=[],
                    meta={"generic_cell": list(generic["cell"])},
                )
        return None
    # 涉钱答案的角色闸 —— 必须在**取数之前**短路。放到查完再擦文本有两个问题:
    # 数据已经被取出来了, 而且擦文本随时会漏掉一种新的金额写法。
    # 缺省 role 按无权限处理(fail-closed): 内部调用/老客户端不该因为"没传"而拿到金额。
    # 只拦「整个答案就是钱」的那几个; 声明自己就地脱敏的正常分发, 免得把非金额
    # 内核(趋势形状/单量对比)也一起拿走 —— 那是能力回退不是安全加强。
    if (
        code in _MONEY_BEARING_INTENTS
        and code not in _MONEY_SELF_MASKING_INTENTS
        and not _role_may_see_money(kwargs.get("role"))
    ):
        return _money_masked_answer(code)
    query_text = kwargs.get("query") or ""
    if (
        code in _DATE_BACKREF_CODES
        and query_text
        and _DATE_BACKREF_RE.search(query_text)
        and not kwargs.get("date_range")
        and not kwargs.get("comparison_date_range")
    ):
        return OpsAnswer(
            code=code,
            title="需要先确认比较日期",
            answer_text=(
                "这轮对话里没有找到可沿用的比较日期，因此不能按「刚才的日期」回答，"
                "也不会用默认时间范围替代。"
                "请直接给出两个具体日期或日期范围（例如 2026-07-20 和 2026-07-21）。"
            ),
            charts=[], kpis=[],
            meta={"missing_reference": "date_range"},
        )
    sig = inspect.signature(resolver)
    accepts_var_kw = any(
        p.kind is inspect.Parameter.VAR_KEYWORD for p in sig.parameters.values()
    )
    if accepts_var_kw:
        filtered = kwargs
    else:
        filtered = {k: v for k, v in kwargs.items() if k in sig.parameters}
    # Pin the tenant contextvar to the DATA factory for the resolver's whole
    # call tree: pool.setup stamps `app.factory_id` from this contextvar on
    # every acquired connection, so helpers that never set the GUC themselves
    # (finance_summary, store_comparison, trend bundles…) stay RLS-visible
    # when the data factory differs from the trusted request tenant (demo →
    # seeded gold tenant reads). Auth/session/cache stay on the caller's ctx.
    from smartbi.tenant_ctx import reset_factory_id, set_factory_id

    ctx_token = set_factory_id(factory_id)
    try:
        # 同一棵调用树里也绑菜单目录: resolver 内部还会自己抽菜名
        # (resolve_gross_margin 的 extract_dish_candidates), 没有目录就退回
        # 残差式启发法, 把「人力」这类名词当菜名。规划闸只护住走 parse 的入口;
        # 按 code 直接分发的路径(Java 传 intentCode / 内部重放 / 以后的新入口)
        # 绕开规划, 必须在这里补上。
        # 用 factory_id(=数据租户, demo 会重映射)而不是请求租户 —— 菜名就在
        # 那个租户的 dim_product 里, 与上面钉 tenant 的口径一致。
        async with dish_catalogue_scope(smartbi_pool, factory_id):
            return await resolver(smartbi_pool, factory_id, **filtered)
    finally:
        reset_factory_id(ctx_token)
