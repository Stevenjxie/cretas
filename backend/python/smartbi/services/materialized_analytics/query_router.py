"""Map natural-language user query to a materialized template code.

Uses keyword patterns per template. First high-confidence match wins.
No LLM — deterministic, fast (<1ms).

Future: swap in semantic embedding match via pgvector (W3).
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)


# Each pattern: tuple of (must_contain_any_of_these) — query must contain AT LEAST ONE keyword from EACH group.
# Order matters: first match wins.
# Format: [(template_code, [[keywords_group_1], [keywords_group_2], ...]), ...]
_PATTERNS: List[Tuple[str, List[List[str]]]] = [
    # === Restaurant-specific (higher priority, more specific) ===
    (
        "dish_slow_movers",
        [["菜品", "商品", "产品"], ["滞销", "慢销", "卖得不好", "不好卖", "卖不出去", "末位", "末尾", "垫底", "倒数", "下架", "最差", "最少", "低销"]],
    ),
    # dish_category_breakdown must precede dish_sales_top_n — when a query
    # carries a category keyword (类别/品类/分类) it's asking about composition
    # across categories, not individual dish ranking. Otherwise dish_sales_top_n
    # would claim it via the broader "菜品 + 卖" pattern (R13 regression).
    (
        "dish_category_breakdown",
        # Apr 24 2026 R13 fix: widened group-2 with "卖"/"多"/"最多" and moved
        # this block above dish_sales_top_n so category queries route correctly.
        [["分类", "品类", "类别", "饮品", "主食", "小吃", "啤酒", "套餐"],
         ["销量", "销售", "占比", "份额", "结构", "卖", "多", "最多"]],
    ),
    (
        # Apr 26 2026 Bug G phase 2 follow-up: was "dish_sales_top_n" (template
        # never materialized — latent registry bug). Real materialized template
        # is `top_n_by_dim` which handles dish rankings. qhj-01 "卖得最好的菜
        # Top 10" failed phase 2 because match returned non-existent template.
        # Also extend group-1 with bare "菜" so conversational forms match.
        # Group-2 still requires sales/ranking semantics so noise queries
        # (e.g. "菜单怎么改") don't false-positive.
        "top_n_by_dim",
        [["菜品", "商品", "产品", "菜"],
         ["销量", "销售", "卖", "热销", "畅销", "排名", "Top", "最多", "最高"]],
    ),
    # store_performance must precede the generic top_n_by_dim (further below) —
    # single-store / drill-down performance queries ("哪个门店营收最高" /
    # "哪家店业绩最好" / "单店表现") belong to the dedicated store_performance
    # template (rich sample_queries served via pgvector). Without this keyword
    # entry, "哪个门店营收最高" fell through to top_n_by_dim while its synonym
    # "哪家店业绩最好" hit store_performance via pgvector — same intent, one
    # right one wrong (措施①-bug1, May 31 2026).
    #
    # Risk#1 mitigation: group-1 REQUIRES a specific-store / drill-down marker
    # (单店/该门店/这家店/这家/哪家/哪个门店/门店业绩/门店排名). Generic multi-store
    # comparison ("门店对比" / "各门店") and channel queries do NOT carry these,
    # so they still route to table_type_comparison / top_n_by_dim as before.
    # Placed AFTER the dish blocks so dish-specific queries win, but BEFORE the
    # generic top_n_by_dim so store-performance drill-downs aren't swallowed.
    (
        "store_performance",
        [["单店", "该门店", "这家店", "这家", "哪家", "哪个门店", "门店业绩",
          "门店排名", "门店销售排行", "业绩冠军", "单店表现"],
         ["营收", "营业额", "销售额", "业绩", "表现", "排名", "排行",
          "最高", "最好", "最多", "最差", "客单价", "卖得最", "畅销"]],
    ),
    (
        "dish_time_slot_matrix",
        [["菜品", "商品"], ["时段", "早餐", "午餐", "晚餐", "宵夜", "时间", "小时", "早晚"]],
    ),
    # dish_by_table_type must precede table_type_comparison — both match 包厢/大厅/外卖
    # keywords, but when the user also mentions 菜品/点单/点菜/偏好, dish drill-down is
    # the right answer. Router takes the first pattern that matches.
    (
        "dish_by_table_type",
        # "包厢客人点什么" / "大厅点单菜品" / "外卖菜品和堂食差什么"
        [["包厢", "大厅", "堂食", "外卖", "桌位", "散客"],
         ["菜品", "商品", "点单", "点菜", "点什么", "偏好", "爱点", "点的菜", "点什么菜", "吃什么"]],
    ),
    # revenue_management_report must precede table_type_comparison — 堂食外卖 +
    # 午晚市/时段 context means the user wants the composite report, not just
    # the channel comparison.
    (
        "revenue_management_report",
        # "收入管理报表" / "堂食外卖午晚市" / "渠道时段营收" / "营收结构报表"
        # Apr 24 2026 R17 fix: "营收结构报表" previously routed to profit_loss
        # because profit_loss had "营收结构" in its group-1. Semantically
        # "营收结构" = revenue breakdown, which this template owns (channel ×
        # time decomposition). profit_loss is narrowed to P&L-specific terms.
        [["收入管理", "堂食外卖", "堂食午晚", "外卖午晚", "渠道时段", "复合报表",
          "堂食和外卖", "外卖和堂食", "营收结构", "收入结构"],
         ["报表", "分析", "占比", "分布", "对比", "午晚市", "午市", "晚市"]],
    ),
    # channel_analysis must precede table_type_comparison. Both can match "外卖
    # + 分析" queries, but when user says 渠道/来源 or a platform name (美团/饿了
    # 么/京东/抖音) they mean channel breakdown, not dine-in table comparison.
    (
        "channel_analysis",
        # Higher-priority channel match — fires first when user explicitly
        # mentions 渠道/来源 or a platform name. Prevents table_type_comparison
        # from stealing the query when user meant channel breakdown.
        [["渠道", "来源", "美团", "饿了么", "京东", "抖音"],
         ["分析", "占比", "对比", "销售", "订单", "营收"]],
    ),
    (
        "table_type_comparison",
        # Require at least one true dine-in keyword (包厢/大厅/堂食/桌位/就餐/散客).
        # Removed 外卖 from group 1 — it ambiguously matched channel queries.
        # Pure 外卖 questions still route via channel_analysis below.
        [["包厢", "大厅", "堂食", "桌位", "就餐", "散客"],
         ["对比", "比较", "分析", "销售", "订单", "客单价", "人均", "数量"]],
    ),
    (
        "staff_performance",
        [["服务员", "销售员", "收银员", "员工", "店员"], ["业绩", "排名", "销售", "Top", "绩效", "表现"]],
    ),
    (
        "time_slot_revenue",
        [["时段", "早晨", "上午", "下午", "晚上", "早餐", "午餐", "晚餐", "宵夜", "小时"], ["营业额", "营收", "销售额", "收入", "门店", "区域", "地区"]],
    ),
    # groupon_channel_breakdown must precede channel_analysis — when user mentions
    # multiple groupon-specific platforms (点评 + 美团 + 抖音) together, they want
    # groupon breakdown not generic channel analysis.
    (
        "groupon_channel_breakdown",
        [["团购", "团购券", "券使用", "点评", "美团"],
         ["三端", "点评", "美团", "抖音", "占比", "对比", "分析"]],
    ),
    (
        "channel_analysis",
        [["外卖", "堂食", "美团", "饿了么", "京东", "抖音", "渠道", "来源"], ["订单", "销售", "营收", "占比", "对比", "分析"]],
    ),
    # Apr 26 2026 phase 4 P1.c: member_deep_analytics moved ABOVE
    # stored_value_card_consumption. Previously stored_value matched qhj-20
    # "储值卡余额总额多少" first (储值卡 + 多少) but qhj had no upload with
    # stored_value_card_consumption materialized → fall back to LLM.
    # member_deep_analytics IS materialized for member-level exports (qhj
    # 4216) and answers 余额/总额/Top持有者/召回 queries via 等级分布/
    # 余额分层/Top 充值 outputs. Promoted + extended keywords below.
    (
        "member_deep_analytics",
        # group-1: member/card-level entities. "客户" added — combined with
        # group-2 keywords like 召回/沉睡/流失 it's restrictive enough.
        [["会员卡", "会员数据", "会员等级", "卡余额", "储值会员", "会员充值",
          "储值卡", "储值", "持有者", "持有", "赠品", "赠送金", "赠送", "客户"],
         # group-2: include 多大 for "赠品损失多大"-type phrasing.
         ["分布", "数据", "等级", "余额", "多少", "多大", "统计", "分析",
          "总额", "Top", "哪类", "哪些", "召回", "持有", "大户",
          "流失", "沉睡", "复购", "唤醒", "值得"]],
    ),
    # stored_value_card_consumption: now narrower — requires
    # transaction-level keywords (消费/流水/使用) instead of generic 多少
    # so it doesn't poach 储值卡余额 type queries that member_deep handles.
    (
        "stored_value_card_consumption",
        [["储值卡", "预付卡"], ["消费", "流水", "使用", "报表"]],
    ),
    (
        "member_consumption",
        [["会员", "会员卡"], ["消费", "金额", "频次", "次数", "支付"]],
    ),
    (
        "refund_analysis",
        [["退菜", "退单", "撤单", "损耗", "损失"], ["分析", "统计", "次数", "多少"]],
    ),
    (
        "promotion_impact",
        [["代金券", "优惠券", "折扣", "促销", "优惠"], ["使用", "占比", "金额", "影响", "分析"]],
    ),
    (
        "weekday_weekend_pattern",
        [["周末", "工作日", "周一", "周日", "平日"], ["对比", "差异", "差别", "分析", "营业额", "销售"]],
    ),
    # === W3 餐饮常见 Q-A 补充 (Apr 22 2026) ===
    # Note: dish_by_table_type is placed above, ahead of table_type_comparison.
    (
        "combo_usage_rate",
        # "套餐使用率" / "有多少客人点套餐" / "套餐销量"
        [["套餐"], ["使用率", "占比", "多少", "分析", "销量", "使用", "点"]],
    ),
    (
        "reverse_checkout_stats",
        # "反结账统计" / "反结账次数" / "哪些单反结了"
        [["反结账", "反结", "已反结"], ["统计", "次数", "多少", "分析", "门店", "哪些"]],
    ),
    (
        "store_customer_stratification",
        # "门店客单人数分层" / "几人桌占比" / "1 人桌多少"
        [["门店", "客单", "几人", "人数"], ["分层", "占比", "分布", "分析", "桌"]],
    ),
    # W3 财务端补充: profit_loss_statement (not in conflict with earlier patterns)
    (
        "profit_loss_statement",
        # "利润表" / "毛利" / "净利" / "损益"
        # Apr 24 2026 R17 fix: removed "营收结构" from group-1 — that phrase is
        # semantically broader and is now claimed by revenue_management_report.
        # profit_loss stays on unambiguous P&L-specific terminology.
        [["利润", "毛利", "净利", "损益", "应收", "实收"],
         ["报表", "结构", "分析", "多少", "合计", "对比"]],
    ),
    # W3 进销存 (采购入库侧)
    (
        "purchase_inventory_inflow",
        # "采购入库" / "进销存" / "供应商分析"
        [["采购", "进销存", "入库", "供应商", "原料"], ["分析", "多少", "报表", "金额", "Top", "占比"]],
    ),
    # W3 营业概况报表
    (
        "business_overview_summary",
        # "营业概况" / "月度汇总" / "门店日报"
        [["营业概况", "营业汇总", "月度", "月报", "汇总", "概况", "日报"],
         ["报表", "分析", "多少", "总", "合计", "峰值", "最高", "最低"]],
    ),
    # W3 会员深度 — promoted to top of pattern list above
    # stored_value_card_consumption (Apr 26 2026 phase 4 P1.c). See
    # earlier entry for full keyword set.
    # W4 专项报表 (Apr 22 2026)
    (
        "reviews_sentiment_summary",
        # "评价/星级/投诉/怎么样" queries. Apr 24 2026: widened group-2 so
        # "客户评价怎么样" / "评价情况如何" / "口碑排名" all route correctly.
        [["评价", "大众点评", "美团点评", "口碑", "星级", "评分", "差评", "好评", "投诉", "点评"],
         ["分析", "分布", "多少", "统计", "平均", "率", "比例", "怎么样", "如何", "情况", "排名", "最多"]],
    ),
    (
        "payment_method_mix",
        # "付款方式/支付方式占比"
        [["付款方式", "支付方式", "付款", "收款", "现金", "移动支付"],
         ["占比", "分布", "分析", "多少", "对比"]],
    ),
    (
        "kitchen_dispatch_heatmap",
        # "传菜/档口/厨房"
        [["传菜", "档口", "厨房", "上什", "后厨"],
         ["分析", "多少", "分布", "Top", "报表", "份"]],
    ),
    (
        "period_comparison_trend",
        # "月度对比/环比/同比"
        [["环比", "同比", "月度", "上个月", "本月", "上月", "近月", "增长", "涨跌", "趋势"],
         ["多少", "分析", "对比", "变化", "涨", "跌", "增长", "营业额"]],
    ),
    # W4 quality-audit new templates
    (
        "monthly_anomaly",
        # "哪个月营业额掉了/暴跌/异常"
        [["月", "异常", "暴跌", "暴涨", "突降", "突增", "掉了", "大跌", "大涨"],
         ["分析", "多少", "检测", "哪个", "哪几个", "波动", "异常"]],
    ),
    (
        "dish_store_drill",
        # W4-Q10: match "X哪家店卖得最多" where X is a dish name. Group 1 accepts
        # either an explicit 菜/菜品 keyword OR a "卖/点得最多" phrase (implies user
        # named a specific dish). Group 2 requires store reference. Placed before
        # top_n_by_dim so "米饭哪家门店卖得最多" routes here, not to generic top-N.
        [["菜品", "哪个菜", "什么菜", "菜",
          "卖得最多", "卖得最好", "卖最多", "卖最好", "点最多", "点得最多"],
         ["门店", "店铺", "哪家店", "哪家分店", "店"]],
    ),
    # Note: revenue_management_report / stored_value_card_consumption /
    # groupon_channel_breakdown are placed above, ahead of their respective
    # broader siblings (table_type_comparison / member_consumption / channel_analysis).
    # === Generic W1 templates (lower priority, broader match) ===
    (
        "monthly_trend",
        [["趋势", "走势", "变化", "增长"], ["月度", "日", "周", "时间", "趋势"]],
    ),
    (
        "pareto_analysis",
        [["80", "20", "帕累托", "Pareto", "80/20", "贡献", "头部"], ["分析", "占比", "集中"]],
    ),
    (
        "anomaly_detection",
        [["异常", "异常值", "离群", "偏离", "突变"], ["检测", "发现", "找出", "分析"]],
    ),
    (
        # Apr 26 2026 phase 4 P1: extend group-1 with "末位"/"差距" (qhj-27
        # "末位店和头部差距多大") and group-2 with "店"/"菜品"/"商品" so
        # store/dish ranking queries match the broader pattern. Phase 3's
        # dim-aware override picks the right dim from all_dims when matched.
        "top_n_by_dim",
        [["Top", "排名", "头部", "最高", "最多", "前", "第一", "最大", "末位", "差距"],
         ["门店", "区域", "分类", "店", "菜品", "商品"]],
    ),
    (
        "category_distribution",
        [["占比", "份额", "结构", "构成", "分布"], ["营业额", "销售额", "收入"]],
    ),
    # Apr 26 2026 Bug G phase 2 follow-up: "哪类菜品贡献最大" / "哪种产品贡献"
    # didn't match — dish_category_breakdown line above requires 分类/品类/类别
    # (substring miss for "哪类") and previous category_distribution requires
    # 占比/份额/结构. This entry catches the conversational form via 哪类 +
    # 贡献/最大 paired with 菜品/商品/产品. Routes to category_distribution
    # (materialized template) since dish_category_breakdown was never
    # materialized as a real template (latent registry bug fixed).
    (
        "category_distribution",
        [["哪类", "哪种", "哪个类", "哪一类"],
         ["贡献", "最大", "最多", "贡献最大", "贡献最多",
          "菜品", "商品", "产品", "类目", "品类"]],
    ),
]


# W4-Q4 / W4-Q10: two modifier classes with different severity.
#
# HARD modifiers: cached template is guaranteed wrong. Pre-empt the router
#   → immediately LLM fallback. User said "exclude", "why", "next" — no
#   cached aggregation can honor that.
# SOFT modifiers: no template match after all. Log LLM fallback reason.
#   User said "which store" — if dish_store_drill matched, serve it; else LLM.
_HARD_MODIFIERS = (
    # Exclusion / filter — no template can re-filter cache results
    "排除", "除了", "不算", "去掉", "过滤", "剔除", "忽略",
    # Narrowing — too specific for pre-computed cache
    "只看", "只要", "只统计", "单独", "特定",
    # Why / cause — no causal-inference template
    "为什么", "为啥", "原因", "归因", "解释",
    # Pronominal reference / nth — template returns Top N but can't jump
    "第二", "第三", "下一个", "接下来", "还有哪些",
    # Year-over-year — no template has historical year data
    "对比去年", "vs去年", "比去年",
    # Pronominal reference — resolved only with conversation history (Fix 2)
    "这个月", "那个月", "上个月", "这家", "那家", "这个", "那个",
)

_SOFT_MODIFIERS = (
    # Drill-down by extra dim — dish_store_drill handles some cases
    "哪家店", "哪个门店", "哪家分店", "哪家铺子",
)


# Apr 23 2026: explicit time limiters. When the user mentions a specific
# time window ("8月", "2025-08", "昨天", "近7天"), pre-computed templates
# that return upload-total aggregates give the wrong answer — the answer
# needs to be filtered to that window. Route to LLM fallback so the
# C1/C2/C3 time-scoped aggregate path in chat.py fires.
_TIME_LIMITER_KEYWORDS = (
    # Single-char month form: 1月, 2月 ... 12月 matched via regex below,
    # not this tuple. Keep explicit phrases here:
    "本月", "本周", "今日", "今天", "昨天", "前天", "当天",
    "上月", "上周", "近一周", "近一月", "近30天", "近7天", "近3天",
    "最近一周", "最近一月", "最近30天", "最近7天", "最近3天",
    "最后两天", "最后一天", "月末", "月初",
    # NOTE: "周末" intentionally excluded — it's a dimension for
    # weekday_weekend_pattern template, not a time filter. "本周末"
    # pattern for time-scoped query uses "本周" (already listed).
)

# Regex: 年份(2024/2025) 或 月份(1月-12月) 或 日期(YYYY-MM-DD)
import re as _re  # noqa: E402
_TIME_LIMITER_RE = _re.compile(
    r"(?:"
    r"20\d{2}[-年]\d{1,2}|"          # 2025-08 / 2025年8
    r"(?:^|\s|、|，|,)"       # boundary
    r"(?:1[0-2]|[1-9])"               # 1-12
    r"月"                             # 月字
    r"|"
    r"\d{1,2}月\d{1,2}日"             # 8月15日
    r")"
)


def _has_time_limiter(query: str) -> bool:
    if any(kw in query for kw in _TIME_LIMITER_KEYWORDS):
        return True
    if _TIME_LIMITER_RE.search(query):
        return True
    return False


# Fix 4 (Apr 23 2026): relational drill-down queries. User references a
# ranked entity from a previous template ("第一名菜品在哪家店卖得最好",
# "Top 1 服务员主要卖什么菜", "外卖占比最低的门店是哪家") and asks for
# cross-dimensional analysis. No single template covers this — need LLM
# with conversation history (Fix 2) to resolve the anaphor and do the
# drill. Without this routing, template patterns greedily match the dim
# keyword (菜品/服务员/门店) and return full-upload Top-N, ignoring the
# "which store/dish/etc." part of the query.
_RELATIONAL_DRILL_RE = _re.compile(
    r"(?:"
    # Anaphoric rank prefix at start of query + cross-dim interrogative
    r"^(?:第[一二三四五六七八九十]名|Top\s*\d+)"
    r".*?(?:哪家|哪款|哪种|哪个|什么菜|主要卖|在哪|是哪|卖.*得最)"
    r"|"
    # "最X的{dim} 是哪家/在哪家" — per-entity min/max that no template stores
    r"最(?:高|低|多|少|好|差)的(?:门店|店|员工|服务员|分店|铺子|区域)"
    r".*?(?:是哪家|在哪家|哪家|是哪个|是哪)"
    r")"
)


def _has_relational_drill(query: str) -> bool:
    return bool(_RELATIONAL_DRILL_RE.search(query))


def _has_hard_modifier(query: str) -> bool:
    q = query.lower()
    if any(kw.lower() in q for kw in _HARD_MODIFIERS):
        return True
    if _has_time_limiter(query):
        return True
    return _has_relational_drill(query)


def _has_soft_modifier(query: str) -> bool:
    q = query.lower()
    return any(kw.lower() in q for kw in _SOFT_MODIFIERS)


def match_template(query: str) -> Optional[str]:
    """Try to match user query to a template code.

    Returns template_code if matched, None otherwise.

    Order:
      1. Check HARD modifiers (排除/为什么/第二) → return None immediately.
         Even if a template appears to match, the user's modifier guarantees
         the cached result is wrong.
      2. Check patterns in priority order. First match wins.
      3. After loop, if no match AND SOFT modifier present, also return None
         with explicit log.
    """
    if not query or not isinstance(query, str):
        return None
    if _has_hard_modifier(query):
        logger.info(f"[query-router] hard modifier in '{query[:50]}' → LLM fallback")
        return None
    q = query.lower()
    for code, groups in _PATTERNS:
        hit = True
        for group in groups:
            if not any(kw.lower() in q for kw in group):
                hit = False
                break
        if hit:
            logger.info(f"[query-router] matched '{query[:50]}' → {code}")
            return code
    if _has_soft_modifier(query):
        logger.info(f"[query-router] no template + soft modifier in '{query[:50]}' → LLM")
        return None
    logger.debug(f"[query-router] no match for '{query[:50]}'")
    return None


async def match_template_hybrid(query: str, pool) -> Optional[str]:
    """Phase 3 (Apr 23 2026): keyword + vector RAG router.

    Runs the existing keyword matcher first (sync, <1ms), then if no
    keyword match, queries the pgvector template embedding index. Returns
    template_code if either layer confidently matches, else None → LLM
    fallback.

    This is a SUPERSET of match_template(): any query that match_template
    would have matched, this also matches. Plus it catches queries whose
    wording doesn't fit the keyword patterns but is semantically close to
    a template's sample_queries.

    pool is optional — passing None falls back to pure-keyword behavior
    (useful for tests / when DB is down).
    """
    # Layer 0: pre-empt checks (hard modifiers, etc.)
    if not query or not isinstance(query, str):
        return None
    if _has_hard_modifier(query):
        logger.info(f"[query-router] hard modifier in '{query[:50]}' → LLM fallback")
        return None

    # Apr 26 2026 UX-1: synonym normalization. Cache hit was 13% in S4 audit
    # because users say "走势" but template patterns use "趋势", etc. Normalize
    # before matching to lift cache hit toward 30%+.
    try:
        from smartbi.services.query_normalizer import normalize_for_match
        normalized = normalize_for_match(query)
    except Exception as e:
        logger.warning(f"[query-router] normalizer failed (non-fatal): {e}")
        normalized = query

    # Layer 1: keyword match (existing logic, run on normalized query)
    keyword_code: Optional[str] = None
    q_lower = normalized.lower()
    for code, groups in _PATTERNS:
        hit = True
        for group in groups:
            if not any(kw.lower() in q_lower for kw in group):
                hit = False
                break
        if hit:
            keyword_code = code
            break

    # Layer 2: vector RAG (if pool available). Pass NORMALIZED query so
    # embedding lookup also benefits from synonym substitution. Fails safe.
    if pool is not None:
        try:
            from smartbi.services.template_rag import hybrid_match
            result = await hybrid_match(pool, normalized, keyword_code=keyword_code)
            if result is not None:
                logger.info(
                    f"[query-router] hybrid matched '{query[:50]}' → "
                    f"{result.template_code} (sim={result.similarity:.3f}, via={result.via})"
                )
                return result.template_code
        except Exception as e:
            logger.warning(f"[query-router] hybrid_match failed, falling to keyword-only: {e}")

    # Fallback: use keyword result if we have one; else None.
    if keyword_code:
        logger.info(f"[query-router] matched '{query[:50]}' → {keyword_code} (keyword)")
        return keyword_code

    # Soft modifiers
    if _has_soft_modifier(query):
        logger.info(f"[query-router] no template + soft modifier in '{query[:50]}' → LLM")
        return None
    logger.debug(f"[query-router] no match for '{query[:50]}'")
    return None


# Apr 23 2026 — KPI key → Chinese label map. Templates emit technical keys
# (peak_month, worst_mom_delta_pct, combo_orders) that confuse non-technical
# users. This map translates them to readable labels at display time.
# Unknown keys fall back to humanized-snake-case. Unit hints are appended
# when present.
_KPI_LABEL_MAP: Dict[str, str] = {
    # Monthly anomaly
    "peak_month": "峰值月份",
    "trough_month": "谷值月份",
    "worst_month": "最大波动月份",
    "strategy": "策略",
    "total_labels": "维度总数",
    "labels_for_80pct": "贡献 80% 的维度数",
    "labels_for_80pct_share": "贡献 80% 的维度占比",
    "best_month": "最佳月份",
    "anomaly_count": "异常月数",
    "worst_mom_delta_pct": "最大波动率",
    "best_mom_delta_pct": "最大涨幅",
    "avg_month_revenue": "月均营业额",
    "peak_revenue": "峰值营业额",
    "trough_revenue": "谷值营业额",
    # Dish sales Top N
    "top_dish_name": "冠军菜品",
    "top_dish_qty": "冠军销量",
    "top_dish_revenue": "冠军营业额",
    "distinct_dishes": "菜品总数",
    "second_dish_name": "亚军菜品",
    "second_dish_qty": "亚军销量",
    # Staff performance
    "role_type": "角色类型",
    "top_staff": "Top1 员工",
    "top_revenue": "Top1 销售额",
    "staff_count": "员工人数",
    "avg_per_staff": "人均销售额",
    # Combo usage
    "combo_orders": "含套餐订单",
    "total_orders": "总订单数",
    "combo_rate": "套餐使用率",
    "combo_usage_rate": "套餐使用率",
    "usage_rate_pct": "套餐使用率",
    "top_combo_name": "Top1 套餐",
    "top_combo_qty": "Top1 套餐销量",
    "top_combo_revenue": "Top1 套餐营收",
    # Reverse checkout
    "reverse_count": "反结账数",
    "reverse_share_pct": "反结账占比",
    "top_store_reverse": "高发门店",
    # Channel analysis
    "top_channel": "主导渠道",
    "dine_in_share": "堂食占比",
    "takeaway_share": "外卖占比",
    "takeaway_total": "外卖订单数",
    "dine_in_total": "堂食订单数",
    "channel_count": "渠道数量",
    "avg_dine_in": "堂食客单价",
    "avg_takeaway": "外卖客单价",
    "dine_in_avg_price": "堂食客单价",
    "takeaway_avg_price": "外卖客单价",
    # Dish×store drill
    "top_dish": "头名菜品",
    "dominant_store": "主导门店",
    "top_dish_top_store": "冠军菜主销门店",
    "top_dish_top_store_pct": "主销集中度",
    # Period comparison
    "month_count": "覆盖月数",
    "latest_month": "最新月",
    "latest_revenue": "最新月营业额",
    "latest_mom_delta_pct": "最新环比",
    "dod_delta_pct": "日环比",
    # Table type comparison
    "avg_hall_spend": "大厅客单",
    "avg_vip_spend": "包厢客单",
    "avg_takeaway_spend": "外卖客单",
    "dominant_type": "主导类型",
    # Time slot revenue
    "peak_slot": "营业高峰时段",
    "peak_area": "高峰区域",
    # Weekday/weekend
    "weekday_avg_order": "工作日客单",
    "weekend_avg_order": "周末客单",
    "delta_pct": "差异百分比",
    "weekend_share_pct": "周末占比",
    # Payment method
    "top_payment": "Top1 付款方式",
    "top_payment_share": "Top1 占比",
    "total_payment": "付款合计",
    "payment_method_count": "付款方式数",
    # Stored value card
    "card_orders": "储值卡订单",
    "card_revenue": "储值卡收入",
    "card_share_pct": "储值卡占比",
    "card_total": "储值卡总额",
    "card_orders": "储值卡订单",
    "card_usage_rate_pct": "储值卡使用率",
    "net_share_pct": "到账率",
    "total_deductions": "扣减合计",
    "has_cost_data": "含成本数据",
    # Member consumption
    "member_revenue": "会员营收",
    "member_orders": "会员订单",
    "member_share_pct": "会员占比",
    "avg_spend": "会员人均",
    # Groupon / promotion
    "groupon_revenue": "团购营收",
    "groupon_orders": "团购订单",
    "promotion_total": "促销总额",
    "coupon_total": "代金券总额",
    # Reviews sentiment
    "avg_rating": "平均评分",
    "positive_share_pct": "好评率",
    "negative_share_pct": "差评率",
    "complaint_count": "投诉数",
    # Kitchen dispatch
    "top_kitchen_station": "主档口",
    # Business overview
    "total_revenue": "总营业额",
    "total_orders_all": "总订单量",
    "avg_daily_revenue": "日均营业额",
    # Domain-agnostic generic
    "count": "数量",
    "sum": "合计",
    "avg": "均值",
    "max": "最大",
    "min": "最小",
    # Comprehensive coverage from template inventory scan (Apr 23 2026)
    "grand_revenue": "总营业额",
    "grand_total": "总计",
    "grand_orders": "总订单",
    "grand_amount": "总金额",
    "gross_revenue": "毛营收",
    "net_revenue": "实收营收",
    "gross_profit_estimate": "毛利估算",
    "net_margin_pct": "净利率",
    "peak_value": "峰值",
    "trough_value": "谷值",
    "peak_dish": "冠军菜品",
    "bottom_dish": "末位菜品",
    "bottom_qty": "末位销量",
    "peak_day": "高峰日",
    "low_day": "低谷日",
    "peak_date": "峰值日期",
    "peak_qty": "峰值销量",
    "peak_period": "峰值时段",
    "trough_period": "谷值时段",
    "top_material": "Top1 原料",
    "top_methods": "Top 付款方式",
    "top_slot": "Top 时段",
    "top_store": "Top1 门店",
    "top_stores": "Top 门店",
    "bottom_store": "末位门店",
    "top_avg_per_order": "最高客单价",
    "top_avg_per_order_store": "客单价最高门店",
    "top_supplier": "Top1 供应商",
    "top_suppliers": "Top 供应商",
    "top_table_type": "Top 桌位类型",
    "table_type_count": "桌位类型数",
    "top_dish_by_type": "头名类型菜品",
    "top_platform": "Top 平台",
    "top_platform_amount": "Top平台金额",
    "top_platform_share_pct": "Top平台占比",
    "top_category": "Top 品类",
    "top_category_share": "Top品类占比",
    "top_dishes": "Top 菜品",
    "top_combos": "Top 套餐",
    "top_combinations": "Top 组合",
    "top_channel_share": "Top渠道占比",
    "top_label": "Top 标签",
    "top_level": "Top 等级",
    "top_plan": "Top 方案",
    "top_value": "Top 值",
    "top_share_pct": "Top占比",
    "top_bucket": "Top 区段",
    "top_bucket_share_pct": "Top区段占比",
    "top_cell_share_pct": "Top单元占比",
    "top_store_concentration_pct": "门店集中度",
    "top_store_quantity": "Top店销量",
    "top_dish_in_top_type": "头名类型菜品",
    "turn_rate": "翻台率",
    "avg_turn_rate": "平均翻台率",
    "must_eat_candidate": "必吃榜候选",
    "must_eat_candidate_count": "必吃榜候选数",
    "black_pearl_candidate": "黑珍珠候选",
    "black_pearl_candidate_count": "黑珍珠候选数",
    "popular_ranking_eligible": "人气榜候选",
    "popular_ranking_eligible_count": "人气榜候选数",
    "quality_ranking_eligible": "品质榜候选",
    "quality_ranking_eligible_count": "品质榜候选数",
    "outlier_count": "异常值数",
    "refund_count": "退款数",
    "refund_share_pct": "退款占比",
    "complaint_rate_pct": "投诉率",
    "cancel_rate_pct": "取消率",
    "consumed_rate_pct": "已消费率",
    "vip_rate_pct": "包厢率",
    "revenue_delta_pct": "营收变化",
    "revenue_share_pct": "营收占比",
    "share_of_revenue_pct": "营收占比",
    "share_pct": "占比",
    "coupon_share_pct": "优惠券占比",
    "heavy_discount_pct": "高折扣占比",
    "ordered_share_pct": "下单占比",
    "mom_delta_pct": "环比",
    "dominant_share_pct": "主导占比",
    "members_share_pct": "会员占比",
    "avg_order": "均单",
    "avg_per_customer": "人均消费",
    "avg_member_spend": "会员人均",
    "avg_spend_per_customer": "客人均消费",
    "avg_dishes_per_order": "单均菜品数",
    "avg_coupon": "均优惠",
    "avg_coupon_per_order": "单均优惠",
    "category_count": "品类数",
    "store_count": "门店数",
    "method_count": "付款方式数",
    "platform_count": "平台数",
    "platforms": "平台",
    "slot_count": "时段数",
    "dim_count": "维度数",
    "total_channels": "渠道总数",
    "dish_count": "菜品数",
    "dish_count_tracked": "跟踪菜品数",
    "distinct_materials": "原料种数",
    "total_members": "会员总数",
    "active_members": "活跃会员",
    "total_reviews": "评价总数",
    "avg_star": "平均星级",
    "avg_taste": "口味分",
    "avg_service": "服务分",
    "avg_env": "环境分",
    "worst_store": "最差门店",
    "worst_store_avg_star": "最差门店星级",
    "total_loss_amount": "总损失额",
    "total_discount": "总折扣",
    "total_coupon": "总券额",
    "total_coupon_amount": "券总金额",
    "total_gift": "赠送总额",
    "total_recharge": "充值总额",
    "total_balance": "余额总计",
    "total_actual": "实际合计",
    "total_deductions": "扣减合计",
    "total_item_count": "商品总项",
    "total_ordered": "下单总数",
    "total_qty": "总销量",
    "total_quantity": "总数量",
    "total_rows": "总行数",
    "total_staff": "员工总数",
    "total_tracked": "跟踪总数",
    "total_checked": "核销总数",
    "total_orders_analyzed": "分析订单数",
    "reviews": "评价数",
    "coupon_order_count": "券订单数",
    "order_appearances": "出现订单数",
    "sample_remarks": "样本备注",
    "dine_in_avg_per_customer": "堂食人均",
    "takeaway_avg_per_customer": "外卖人均",
    "weekend_order_share": "周末订单占比",
    "weekend_revenue_share": "周末营收占比",
    "weekday_days": "工作日天数",
    "weekend_days": "周末天数",
    "is_anomaly": "是否异常",
    "z_score": "Z 分数",
    "mean_revenue": "平均营业额",
    "stdev_revenue": "营业额标准差",
    "anomalies": "异常点",
    "dominant_bin": "主导区段",
    "near_zero": "近零值",
    "near_zero_count": "近零值数",
    "low_day": "低谷日",
    "strategy_used": "使用策略",
    "channel": "渠道",
    "channels": "渠道列表",
    "store": "门店",
    "stores": "门店列表",
    "staff": "员工",
    "dim": "维度",
    "area": "区域",
    "areas": "区域列表",
    "period": "周期",
    "period_count": "周期数",
    "months": "月份",
    "has_cost_data": "含成本数据",
    "has_customer_data": "含客流数据",
    "calculable": "可计算",
    "checked": "已核对",
}

# Unit suffix per key. Empty string means no unit. Percentage keys (ending
# in _pct / _rate / _share) automatically get "%" appended.
_KPI_UNIT_MAP: Dict[str, str] = {
    "peak_revenue": " 元",
    "trough_revenue": " 元",
    "avg_month_revenue": " 元",
    "top_dish_qty": " 份",
    "top_dish_revenue": " 元",
    "distinct_dishes": " 个",
    "top_revenue": " 元",
    "staff_count": " 位",
    "avg_per_staff": " 元",
    "combo_orders": " 单",
    "total_orders": " 单",
    "top_combo_qty": " 份",
    "top_combo_revenue": " 元",
    "reverse_count": " 笔",
    "avg_dine_in": " 元",
    "avg_takeaway": " 元",
    "dine_in_avg_price": " 元",
    "takeaway_avg_price": " 元",
    "takeaway_total": " 单",
    "dine_in_total": " 单",
    "channel_count": " 个",
    "dine_in_share": "%",
    "takeaway_share": "%",
    "top_channel_share": "%",
    "dine_in_avg_per_customer": " 元",
    "takeaway_avg_per_customer": " 元",
    "total_channels": " 个",
    "top_avg_per_order": " 元",
    "bottom_avg_per_order": " 元",
    "weekend_order_share": "%",
    "weekend_revenue_share": "%",
    "labels_for_80pct_share": "%",
    "card_total": " 元",
    "latest_revenue": " 元",
    "avg_hall_spend": " 元",
    "avg_vip_spend": " 元",
    "avg_takeaway_spend": " 元",
    "weekday_avg_order": " 元",
    "weekend_avg_order": " 元",
    "total_payment": " 元",
    "payment_method_count": " 种",
    "card_orders": " 单",
    "card_revenue": " 元",
    "member_revenue": " 元",
    "member_orders": " 单",
    "avg_spend": " 元",
    "groupon_revenue": " 元",
    "groupon_orders": " 单",
    "promotion_total": " 元",
    "coupon_total": " 元",
    "complaint_count": " 条",
    "total_revenue": " 元",
    "total_orders_all": " 单",
    "avg_daily_revenue": " 元",
    "anomaly_count": " 个",
    "month_count": " 个",
    # Extended unit suffixes
    "grand_revenue": " 元", "grand_total": " 元", "grand_orders": " 单",
    "grand_amount": " 元", "gross_revenue": " 元", "net_revenue": " 元",
    "gross_profit_estimate": " 元", "peak_value": " 元", "trough_value": " 元",
    "peak_qty": " 份", "bottom_qty": " 份",
    "top_platform_amount": " 元", "top_total": " 元", "top_value": " 元",
    "top_store_quantity": " 份",
    "refund_count": " 笔", "outlier_count": " 个",
    "must_eat_candidate_count": " 家", "black_pearl_candidate_count": " 家",
    "popular_ranking_eligible_count": " 家", "quality_ranking_eligible_count": " 家",
    "category_count": " 个", "store_count": " 家", "method_count": " 种",
    "platform_count": " 个", "slot_count": " 个", "dim_count": " 个",
    "total_channels": " 个", "dish_count": " 个", "dish_count_tracked": " 个",
    "distinct_materials": " 种",
    "total_members": " 位", "active_members": " 位", "total_reviews": " 条",
    "total_loss_amount": " 元", "total_discount": " 元", "total_coupon": " 元",
    "total_coupon_amount": " 元", "total_gift": " 元", "total_recharge": " 元",
    "total_balance": " 元", "total_actual": " 元", "total_deductions": " 元",
    "total_item_count": " 项", "total_ordered": " 单", "total_qty": " 份",
    "total_quantity": " 份", "total_rows": " 行", "total_staff": " 位",
    "total_tracked": " 个", "total_checked": " 笔", "total_orders_analyzed": " 单",
    "reviews": " 条", "coupon_order_count": " 笔",
    "avg_order": " 元", "avg_per_customer": " 元", "avg_member_spend": " 元",
    "avg_spend_per_customer": " 元", "avg_coupon": " 元", "avg_coupon_per_order": " 元",
    "avg_dishes_per_order": " 个",
    "weekday_days": " 天", "weekend_days": " 天",
    "mean_revenue": " 元", "stdev_revenue": " 元",
    "near_zero_count": " 个", "period_count": " 个",
    "avg_star": " 星", "avg_taste": "", "avg_service": "", "avg_env": "",
    "worst_store_avg_star": " 星", "turn_rate": " 次", "avg_turn_rate": " 次",
}


def _humanize_kpi_key(key: str) -> str:
    """Fallback humanization when key isn't in _KPI_LABEL_MAP.
    Snake_case → Space Separated, with a couple common word swaps."""
    parts = key.replace("_", " ").split()
    replacements = {
        "pct": "占比", "rate": "率", "share": "份额", "count": "数",
        "avg": "均值", "total": "合计", "top": "Top",
    }
    return " ".join(replacements.get(p.lower(), p) for p in parts)


def _fmt_kpi_value(key: str, value: Any) -> str:
    """Format a single KPI value with unit + label.

    Rules:
      - Floats render as comma-separated with 2 decimals. If the key looks
        like a percentage (_pct / _rate / _share), append '%'.
      - Integers render as comma-separated.
      - Strings pass through.
      - Explicit unit in _KPI_UNIT_MAP wins over auto-percent.
    """
    if value is None:
        return "—"
    is_pct = key.endswith("_pct") or key.endswith("_rate") or key.endswith("_share_pct")
    unit = _KPI_UNIT_MAP.get(key, "")
    if not unit and is_pct:
        unit = "%"
    if isinstance(value, bool):
        return "是" if value else "否"
    if isinstance(value, float):
        # Percentages usually come as 0-100 range from templates
        return f"{value:,.2f}{unit}"
    if isinstance(value, int):
        return f"{value:,}{unit}"
    return f"{value}{unit}" if unit else str(value)


# Phase 3 (Apr 26 2026): query-intent → dim hint mapping.
# When a query implies a specific dim (e.g. "卖得最好的菜" implies dish dim),
# we want to pick the matching dim from a top_n_by_dim template's all_dims
# rather than always using primary_dim (which is just "biggest spread").
_QUERY_DIM_HINTS: Dict[str, List[str]] = {
    'dish': ['菜品', '商品', '产品', '菜', '单品', '主推菜', '畅销品'],
    'store': ['门店', '店', '店铺', '分店', '哪家店', '哪几家'],
    'channel': ['渠道', '美团', '饿了么', '抖音', '点评', '堂食外卖', '外卖渠道'],
    'time_slot': ['时段', '小时', '早餐', '午餐', '晚餐', '宵夜', '午晚市'],
    'province': ['省份', '城市', '地区'],
    'payment': ['支付', '付款', '微信', '支付宝', '现金'],
    'category': ['分类', '类别', '品类'],
    'member': ['会员', '客户', '用户', 'VIP'],
}

# Maps semantic class → keywords that may appear in dim NAMES (in all_dims keys)
_DIM_NAME_HINTS: Dict[str, List[str]] = {
    'dish': ['菜品', '商品', '产品', '商品分类', '商品编码', '菜', '单品'],
    'store': ['门店', '店', '店铺', '分店'],
    'channel': ['渠道', '来源', '订单来源'],
    'time_slot': ['时段', '小时', '午晚', '早晚', '早餐', '午餐', '晚餐'],
    'province': ['省份', '城市', '区域', '地区'],
    'payment': ['付款', '支付', '订单付款'],
    'category': ['分类', '类别', '品类'],
    'member': ['会员', '客户', '用户', 'VIP'],
}


def pick_dim_from_query(query: str, available_dims: List[str]) -> Optional[str]:
    """Phase 3: pick the dim from `available_dims` that best matches user intent.

    First tries direct substring (e.g. user says "门店" and dim "门店" exists).
    Falls back to semantic class match (e.g. user says "菜", dim "商品分类").
    Returns None if no useful match — caller should keep primary_dim.
    """
    if not query or not available_dims:
        return None
    q = query.lower()

    # Step 1: direct substring match (highest confidence)
    for dim in available_dims:
        if not dim or len(dim) < 2:
            continue
        if dim.lower() in q:
            return dim

    # Step 2: semantic class via query hints → matching dim name keywords
    for sem_class, q_hints in _QUERY_DIM_HINTS.items():
        if any(h in q for h in q_hints):
            d_hints = _DIM_NAME_HINTS.get(sem_class, [])
            for dim in available_dims:
                if any(h in dim for h in d_hints):
                    return dim
    return None


def regen_top_n_for_dim(data: Dict, target_dim: str, top_n: int = 10) -> Optional[Dict]:
    """Phase 3: re-render top_n_by_dim insight + KPIs for a different dim from all_dims.

    Mirrors the formatting in templates/top_n_by_dim.py compute() but uses
    a non-primary dim. Returns dict with 'insight_text', 'kpis', 'top_rows'
    if successful, or None if target_dim missing or has < 2 rows.
    """
    all_dims = data.get('all_dims', {}) if isinstance(data, dict) else {}
    if not isinstance(all_dims, dict) or target_dim not in all_dims:
        return None
    rows = all_dims[target_dim]
    if not isinstance(rows, list) or len(rows) < 2:
        return None
    rows = rows[:top_n]
    measure = data.get('measure', '总计')
    top_label = rows[0].get('label') if isinstance(rows[0], dict) else None
    if top_label is None:
        return None
    total_of_top = sum(r.get('total', 0) or 0 for r in rows)
    top_total = rows[0].get('total', 0) or 0
    top_share_pct = (top_total / total_of_top * 100) if total_of_top > 0 else 0
    dominance = "独占" if top_share_pct >= 30 else "占比"

    if len(rows) >= 3:
        bottom_label = rows[-1].get('label') if isinstance(rows[-1], dict) else None
        bot_total = rows[-1].get('total', 0) or 0
        gap_pct = round((top_total - bot_total) / top_total * 100, 0) if top_total > 0 else 0
        # Apr 26 phase 5 UX: dim-aware action rec (was hardcoded "复制 SOP")
        try:
            from .restaurant.action_rec_formatter import dim_aware_top_n_action_rec
            action = dim_aware_top_n_action_rec(
                primary_dim=str(target_dim),
                measure=str(measure),
                top_label=str(top_label),
                bottom_label=str(bottom_label),
                gap_pct=float(gap_pct),
            )
        except Exception:
            # Fallback to original simpler phrasing if helper unavailable
            action = (
                f"建议: 针对【末位「{bottom_label}」 (与 Top「{top_label}」差距 {gap_pct:.0f}%)】"
                f"复制 Top {target_dim} 运营 SOP 可拉高末位 {measure} 10-20% "
                f"(前置: 对标走访「{top_label}」 + 末位 {target_dim} 负责人 KPI 重设, 本季度内完成)"
            )
    else:
        action = (
            f"建议: 针对【Top「{top_label}」 ({dominance} {top_share_pct:.1f}%)】"
            f"复盘 Top 成功要素并输出到其他 {target_dim} 可拉升 {measure} 5-10% "
            f"(前置: Top {target_dim} 案例总结 + 培训 + 跨域复制, 本月内完成)"
        )
    return {
        'insight_text': (
            f"{target_dim} Top {len(rows)}:{top_label} {dominance} "
            f"{top_share_pct:.1f}%,余下梯队收敛明显。 {action}"
        ),
        'kpis': {
            'top_label': top_label,
            'top_value': top_total,
            'top_share_pct': round(top_share_pct, 2),
            'dim_count': len(all_dims),
        },
        'top_rows': rows,
    }


# Apr 26 2026 phase 5 (UX): query-cache intent disambiguation.
# When cache fast-path serves multiple distinct queries with the same
# template result, user sees identical answer for different intents
# (qhj-19/20/22/30 all got "32,997 会员 / 余额清零 100%" 4 times).
# Detect specific intent patterns and prepend a disambiguation note
# so user understands what was/wasn't covered.

# Patterns that expect a LIST or specific entity, not just summary.
_LIST_INTENT_PATTERNS = (
    'top 10', 'top10', '都是谁', '都有谁', '哪些', '哪几', '前 10', '前10',
    '排行', '排名', '名单', '列出', '列表',
)

# Specific entity-type intents — user wants concrete numbers for these.
_SPECIFIC_METRIC_INTENTS = {
    '赠品损失': ['赠送金', '赠品', '赠送'],
    '赠品': ['赠送金', '赠品', '赠送'],
    '余额总额': ['余额'],
    '充值总额': ['充值', '本金'],
    '客单价': ['客单价', '人均'],
    '退单率': ['退单', '撤单'],
    '投诉率': ['投诉'],
}


def _disambiguate_for_query(query: str, code: str, data: Dict, kpis: Dict) -> Optional[str]:
    """Detect query-cache mismatch and return a clarifying note.

    Returns short Chinese note (~50-80 chars) to prepend to answer, or
    None if cache content actually addresses the query well.
    """
    if not query:
        return None
    q = query.lower()

    # member_deep_analytics — common multi-query template (qhj-19/20/22/30)
    if code == 'member_deep_analytics':
        # User asks for Top N list — surface real list if data has signal
        if any(p in q for p in _LIST_INTENT_PATTERNS):
            top_recharge = data.get('top_recharge_members') if isinstance(data, dict) else None
            top_balance = data.get('top_balance_members') if isinstance(data, dict) else None
            if top_recharge or top_balance:
                # Build a Top N list inline so user sees the actual ranking.
                target = top_recharge or top_balance
                target_label = '充值' if top_recharge else '余额'
                lines = [
                    f"针对你问的「Top N 持有者/排行」: 以下是按{target_label}排序的 Top "
                    f"{min(len(target), 10)} 会员 (姓名已脱敏):"
                ]
                for r in target[:10]:
                    lvl = f" / {r['level']}" if r.get('level') else ''
                    lines.append(
                        f"  {r.get('rank')}. {r.get('name')}{lvl} — "
                        f"¥{r.get('amount', 0):,.2f}"
                    )
                return '\n'.join(lines)
            # No signal data — fall back to honest note
            return (
                "针对你问的「Top N 持有者/排行」: "
                "当前会员数据无个体明细排序 (余额/充值字段全为 0, 无可排序数据), "
                "以下是会员整体汇总."
            )
        # User asks for specific 赠品/赠送 metric
        if '赠品' in q or '赠送' in q:
            total_gift = (kpis or {}).get('total_gift') if isinstance(kpis, dict) else None
            if total_gift is None or float(total_gift or 0) == 0:
                return (
                    "针对你问的「赠品损失」: 当前赠送金合计=0 元 (无赠品发放数据), "
                    "若需评估滥发现象请补充赠品发放记录表."
                )

    # top_n_by_dim — when user query implies different scope than served dim
    if code == 'top_n_by_dim':
        primary_dim = data.get('primary_dim') if isinstance(data, dict) else None
        # User asks specifically about 菜品 but dim is not 菜品-related at all
        if any(k in q for k in ('卖得最好的菜', 'Top 10 菜', 'Top10菜')):
            if primary_dim and not any(
                k in primary_dim for k in ('菜', '商品', '产品', '单品')
            ):
                return (
                    f"针对你问的「卖得最好的菜」: 当前数据按【{primary_dim}】聚合, "
                    f"非菜品级明细. 若需具体菜名 Top N 请上传带菜品名称的销量明细."
                )
            # Granularity note: dim is 商品分类/品类 (category-level) not SKU
            if primary_dim and any(
                k in primary_dim for k in ('分类', '品类', '类别', '类目')
            ):
                return (
                    f"针对你问的「卖得最好的菜」: 当前按【{primary_dim}】(类别级) 排名, "
                    f"非具体菜品名 (单品级). 若需 SKU/菜名 Top N 请上传含明细菜名的销量数据."
                )

    return None


def format_cached_as_sse(
    template_result: Dict,
    query: str,
    intent_signals: Optional[Dict] = None,
) -> Dict:
    """Format a cached template result as a response dict suitable for SSE streaming.

    Args:
        template_result: Cached template row from DB.
        query: Original user query (for context).
        intent_signals: Optional dict with optional ``n``, ``frequency``,
            ``role`` keys (see ``smartbi.services.intent.query_intent_extractor``).
            When ``n`` is supplied and the template's data carries a
            ``ranking`` list, the list is re-sliced to honor the user's
            requested top-N. When ``role`` is supplied and the template's
            ``role_type`` differs (e.g. user asked 收银员 but template
            materialized 服务员), an explanatory note is appended.

    Returns dict with keys:
      - answer: str (insight_text + context)
      - charts: list (chart_config wrapped as [{type, title, option}])
      - kpis: dict
      - source: 'materialized_cache'
      - template_code: str
    """
    code = template_result.get("code", "")
    title = template_result.get("title", "")
    insight = template_result.get("insight_text") or ""
    chart_config = template_result.get("chart_config")
    kpis = template_result.get("kpis") or {}
    data = template_result.get("data") or {}

    # ── Honor extracted intent ─────────────────────────────────────
    intent_signals = intent_signals or {}
    requested_n = intent_signals.get('n')
    requested_role = intent_signals.get('role')

    # Phase 3 (Apr 26 2026): top_n_by_dim dim override.
    # When query implies a specific dim (e.g. "卖得最好的菜" implies dish, not
    # the cached primary_dim "门店"), regenerate insight from data.all_dims
    # using the user's intended dim. all_dims is pre-computed at materialize
    # time so this is fast (no LLM) and always correct dim semantics.
    if code == 'top_n_by_dim' and isinstance(data, dict):
        all_dim_keys = list((data.get('all_dims') or {}).keys())
        primary = data.get('primary_dim')
        intended = pick_dim_from_query(query, all_dim_keys)
        if intended and intended != primary:
            regen = regen_top_n_for_dim(data, intended, top_n=requested_n or 10)
            if regen:
                insight = regen['insight_text']
                # Merge regenerated KPIs (keeps any non-overridden ones)
                kpis = {**kpis, **regen['kpis']}
                # Mutate data shallowly so chart re-render picks the new dim.
                data = {**data, 'top_rows': regen['top_rows'], 'primary_dim': intended}
                template_result = {
                    **template_result, 'data': data,
                    'insight_text': insight, 'kpis': kpis,
                }
                # Replace chart with new dim's bars (use top_rows as labels/values).
                if isinstance(chart_config, dict):
                    measure = data.get('measure', '总计')
                    chart_config = {
                        **chart_config,
                        'title': {
                            'text': f"Top {len(regen['top_rows'])} {intended} (按 {measure})",
                            'left': 'center',
                        },
                        'xAxis': {
                            'type': 'category',
                            'data': [str(r.get('label')) for r in regen['top_rows']],
                            'axisLabel': {'rotate': 30},
                        },
                        'series': [{
                            'name': measure, 'type': 'bar',
                            'data': [r.get('total') for r in regen['top_rows']],
                            'label': {'show': True, 'position': 'top', 'formatter': '{c}'},
                        }],
                    }
                logger.info(
                    f"[format_cached] dim override: {primary!r} → {intended!r} "
                    f"for query={query[:30]!r}"
                )

    # Re-slice ranking by requested N (cheap; cached list already has top-N
    # capped at template default, usually 10 — narrow further if user asked
    # less). We do NOT widen beyond the cache.
    if (
        requested_n
        and isinstance(data, dict)
        and isinstance(data.get('ranking'), list)
        and len(data['ranking']) > requested_n
    ):
        try:
            sliced = data['ranking'][:requested_n]
            # Mutate a shallow copy so we don't taint the upstream cache.
            data = {**data, 'ranking': sliced}
            template_result = {**template_result, 'data': data}
            # Adjust chart series to match (top-N rendering uses same list).
            if chart_config and isinstance(chart_config, dict):
                chart_config = _reslice_chart_topn(chart_config, requested_n)
            # Refresh chart title to reflect new N (e.g. "Top 10" → "Top 5")
            if chart_config and isinstance(chart_config.get('title'), dict):
                tt = chart_config['title'].get('text') or ''
                if 'Top' in tt:
                    chart_config = {
                        **chart_config,
                        'title': {
                            **chart_config['title'],
                            'text': _re.sub(r'Top\s*\d+', f'Top {requested_n}', tt),
                        },
                    }
        except Exception as e:
            logger.warning(f"[format_cached] N reslice failed for {code}: {e}")

    # Phase 5 (UX): query-cache intent disambiguation. Prepend clarifying
    # note when cache content doesn't directly address the user's specific
    # query intent (multi-query reuse of same cache, granularity mismatch).
    disambig = _disambiguate_for_query(query, code, data, kpis)

    # Build a rich answer that references the template + data
    answer = f"## {title}\n\n"
    if disambig:
        answer += f"> ℹ️ {disambig}\n\n"
    answer += f"{insight}\n\n"

    # Role mismatch annotation: template materialized one role column at
    # upload time (preference 服务员 > 销售员 > 收银员), so a query for a
    # different role can't be re-answered from cache — explain it.
    if requested_role:
        from smartbi.services.intent.query_intent_extractor import role_to_column
        requested_col = role_to_column(requested_role)
        actual_col = (data.get('role_type') if isinstance(data, dict) else None) or kpis.get('role_type')
        if requested_col and actual_col and requested_col != actual_col:
            answer += (
                f"> **提示**:你询问的是「{requested_col}」,但当前数据集已按「{actual_col}」"
                f"维度预聚合。如需精确按「{requested_col}」拆分,请上传含该列的数据。\n\n"
            )

    if kpis:
        answer += "**关键指标:**\n"
        for k, v in list(kpis.items())[:8]:
            label = _KPI_LABEL_MAP.get(k) or _humanize_kpi_key(k)
            answer += f"- **{label}**: {_fmt_kpi_value(k, v)}\n"

    charts = []
    if chart_config and isinstance(chart_config, dict):
        charts.append({
            "type": chart_config.get("type") or "bar",
            "title": title,
            "option": chart_config,
        })

    return {
        "answer": answer,
        "charts": charts,
        "kpis": kpis,
        "source": "materialized_cache",
        "template_code": code,
    }


def _reslice_chart_topn(chart_config: Dict, n: int) -> Dict:
    """Re-slice an ECharts top-N bar chart's xAxis/yAxis category list and
    series data to the first ``n`` items.

    Templates emit horizontal bars with `yAxis.data` reversed (highest at
    top). To honor a smaller N, we take the LAST n entries (which were the
    top-N before reversal) and apply the same reversal again.

    No-op if structure isn't recognized.
    """
    try:
        cfg = {**chart_config}
        # yAxis.data + series[].data both reversed: take last n, no need to re-reverse
        yaxis = cfg.get('yAxis')
        if isinstance(yaxis, dict) and isinstance(yaxis.get('data'), list):
            cfg['yAxis'] = {**yaxis, 'data': yaxis['data'][-n:]}
        elif isinstance(yaxis, dict) and isinstance(yaxis.get('data'), list) is False:
            pass  # nothing to slice
        series = cfg.get('series')
        if isinstance(series, list):
            new_series = []
            for s in series:
                if isinstance(s, dict) and isinstance(s.get('data'), list):
                    new_series.append({**s, 'data': s['data'][-n:]})
                else:
                    new_series.append(s)
            cfg['series'] = new_series
        # xAxis with category (vertical bar) — slice from start (top-N first)
        xaxis = cfg.get('xAxis')
        if (
            isinstance(xaxis, dict)
            and xaxis.get('type') == 'category'
            and isinstance(xaxis.get('data'), list)
        ):
            cfg['xAxis'] = {**xaxis, 'data': xaxis['data'][:n]}
            if isinstance(series, list):
                # For vertical bar, also re-slice from start
                cfg['series'] = [
                    {**s, 'data': s['data'][:n]}
                    if isinstance(s, dict) and isinstance(s.get('data'), list)
                    else s
                    for s in series
                ]
        return cfg
    except Exception:
        return chart_config
