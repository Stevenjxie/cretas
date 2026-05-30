"""Phase 6 P1 (Apr 26 2026): follow-up classifier — reject cache when query
intent diverges from matched template's domain.

Background: S3 270-followup audit caught case xmx-fu-29-2 "单店 vs 区域同业
差距通常多少" matching cache template "payment_method_mix" → answer was
"支付类型 Top 10:1481792.15 独占 99.2%..." which is completely unrelated. The
cache router fired without checking semantic alignment between query and
template.

afb9a5e71 fixed one symptom (top_n_by_dim with numeric-label dims). This
module is the systemic fix: at the cache-route boundary, classify both
sides and reject when mismatched. Reject → fall through to LLM, which has
the parent context (via v2 conversation memory) to do better than a
mis-routed cache.

Design:
- TEMPLATE_DOMAIN: hand-curated mapping of template_code → domain class
  ('dish' / 'store' / 'payment' / 'channel' / etc).
- classify_query_domain: re-uses query_router._QUERY_DIM_HINTS keywords
  to detect the most likely query domain.
- should_reject_cache: returns True when query_domain is detected, template
  has a known domain, and they differ.

Conservative: returns False (allow cache) when either side is unknown — we
prefer false negatives (let cache serve a marginal answer) over false
positives (force expensive LLM when cache was actually fine).
"""
from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)


# Hand-curated mapping. Domains intentionally coarse — finer granularity gives
# more false positives. Templates not listed (or with multiple plausible
# domains) get None and bypass the check entirely.
TEMPLATE_DOMAIN: dict[str, str] = {
    # dish / 菜品
    'dish_sales_top_n': 'dish',
    'dish_slow_movers': 'dish',
    'dish_category_breakdown': 'dish',
    'dish_by_table_type': 'dish',
    'combo_usage_rate': 'dish',
    'pareto_analysis': 'dish',
    # store / 门店
    'store_performance': 'store',
    'store_customer_stratification': 'store',
    # cross store↔dish — leave as None (multi-domain)
    # 'dish_store_drill': None,
    # channel
    'channel_analysis': 'channel',
    'groupon_channel_breakdown': 'channel',
    # payment
    'payment_method_mix': 'payment',
    # time
    'time_slot_revenue': 'time',
    'weekday_weekend_pattern': 'time',
    'monthly_trend': 'time',
    'monthly_anomaly': 'time',
    'period_comparison_trend': 'time',
    'kitchen_dispatch_heatmap': 'time',
    'dish_time_slot_matrix': 'time',
    # member
    'member_consumption': 'member',
    'member_deep_analytics': 'member',
    'stored_value_card_consumption': 'member',
    # finance
    'profit_loss_statement': 'finance',
    'revenue_management_report': 'finance',
    'refund_analysis': 'finance',
    # review
    'reviews_sentiment_summary': 'review',
    # ops
    'staff_performance': 'staff',
    'anomaly_detection': 'anomaly',
    'reverse_checkout_stats': 'refund',
    'promotion_impact': 'promotion',
    'business_overview_summary': 'overview',
    'category_distribution': 'category',
    'purchase_inventory_inflow': 'inventory',
    'table_type_comparison': 'store',
    # generic — top_n_by_dim is on purpose multi-domain (dim picked at runtime)
    # 'top_n_by_dim': None,
}


# Query → domain keywords. Mirrors query_router._QUERY_DIM_HINTS plus a few
# domain-specific hints for finance/review/staff that aren't in the dim hints.
QUERY_DOMAIN_HINTS: dict[str, list[str]] = {
    'dish': ['菜品', '商品', '产品', '菜', '单品', '主推', '畅销', '爆款', '滞销', '慢销',
             '卖不出去', '卖不动', '卖得不好', '不好卖', '热销', '卖得最好', '卖得最多'],
    'store': ['门店', '店', '店铺', '分店', '哪家店', '哪几家', '单店'],
    'channel': ['渠道', '美团', '饿了么', '抖音', '点评', '外卖渠道', '堂食外卖'],
    'time': ['时段', '小时', '早餐', '午餐', '晚餐', '宵夜', '午晚市', '周末', '工作日',
             '月度', '月份', '同比', '环比', '趋势', '周', '日'],
    'payment': ['支付', '付款', '微信', '支付宝', '现金'],
    'member': ['会员', '客户', '用户', 'VIP', '储值', '充值', '复购'],
    'finance': ['利润', '毛利', '净利', '营收', '收入', '成本', '费用', '财报', '损益'],
    'review': ['评价', '评论', '口碑', '差评', '好评', '星级', '评分',
               '提及', '被夸', '被赞', '被点赞', '提到', '被吐槽', '被批评',
               '夸什么', '夸赞', '抱怨', '反馈'],
    'staff': ['员工', '服务员', '收银员', '厨师', '人效', '人均产出'],
    'refund': ['退款', '退单', '撤单', '反结算', '取消'],
    'promotion': ['促销', '优惠', '券', '满减', '打折', '活动'],
    'category': ['品类', '类别'],  # narrower than 'dish'
    'inventory': ['库存', '进货', '采购', '出库', '入库'],
    'anomaly': ['异常', '突变', '波动'],
    # Apr 26 2026 A1 manufacturing 跨域: F001/F002/F003 制造业租户的领域词
    'quality': ['不良', '良品率', '合格率', '不合格', '质检', '抽检', '缺陷',
                '客诉', '投诉', '召回', '质量问题', '一次合格率', 'fpy'],
    'equipment': ['设备', 'oee', '稼动率', '故障', '停机', '产能', '机台',
                  '机器', '产线', '工位', '设备效率'],
    'production': ['工单', '产量', '良率', '在制品', '批次', '工序', '工艺',
                   '排产', '排程', '加工', '生产计划', '生产进度'],
}


def classify_query_domain(query: str) -> Optional[str]:
    """Detect the most likely domain of a query. Returns None if no hints
    match (caller should NOT reject cache — uncertain).

    First-match-wins by iteration order — finance/review/staff before more
    generic 'time' (so "上月利润" matches finance not time).
    """
    if not query:
        return None
    q = query.lower()
    # Order matters — more specific domains first. Manufacturing
    # (quality/equipment/production) put before time so "工序异常率本月" matches
    # quality/equipment first not time.
    priority_order = [
        'quality', 'equipment', 'production',  # manufacturing first
        'finance', 'review', 'staff', 'refund', 'promotion', 'inventory',
        'anomaly', 'payment', 'member', 'channel', 'category', 'dish',
        'store', 'time',
    ]
    for domain in priority_order:
        hints = QUERY_DOMAIN_HINTS.get(domain, [])
        if any(h in q for h in hints):
            return domain
    return None


# Domains that top_n_by_dim should NOT serve. top_n_by_dim does "rank items
# by a measure within a dimension" — it's a fit for dish/store/channel/etc.
# but a misfit for review/finance/staff/etc. queries (which need narrative
# analysis, not Top-N rankings).
#
# S4 audit (Apr 26 2026) caught two F4-class regressions both routing to
# top_n_by_dim from review/store-comparison queries:
#   - qhj-fu-15-1 "Top 1 提及菜被夸什么" (review domain) → top_n_by_dim sales
#   - xmx-fu-29-2 "单店 vs 区域同业差距通常多少" (store-compare domain) →
#     top_n_by_dim 订单类型 占比
TOP_N_REJECT_DOMAINS: set[str] = {
    'review',     # 评论/评价 → reviews_sentiment_summary or LLM
    'finance',    # 利润/营收 → profit_loss_statement or revenue_*
    'staff',      # 员工/人效 → staff_performance or LLM
    'refund',     # 退款/撤单 → refund_analysis or LLM
    'promotion',  # 促销/优惠 → promotion_impact or LLM
    'inventory',  # 库存/进货 → purchase_inventory_inflow or LLM
    'anomaly',    # 异常/突变 → anomaly_detection (specifically) or LLM
    # Manufacturing — top_n_by_dim is restaurant Top-N logic, not appropriate
    # for quality/equipment/production analytic style which need narrative.
    'quality',    # 不良/良品率/合格率 → LLM (no manufacturing template yet)
    'equipment',  # 设备/OEE/稼动率 → LLM
    'production',  # 工单/产量/良率 → LLM
}


# v7 #1 (Apr 26 2026): templates that produce ranking/Top-N answers.
# When user asks for cause/why-class question, ranking template gives
# "Top X 占比 Y" but doesn't explain WHY. Reject in favor of LLM.
RANKING_TEMPLATES: set[str] = {
    'top_n_by_dim',
    'dish_sales_top_n',
    'staff_performance',
    'store_performance',
    'dish_category_breakdown',
    'channel_analysis',
    'time_slot_revenue',
    'weekday_weekend_pattern',
    'payment_method_mix',
    'pareto_analysis',
    'member_consumption',
}


# Why-class intent words: user wants causal/reason analysis, not ranking.
# When query has these AND template is ranking-class, reject cache.
_WHY_INTENT_WORDS: list[str] = [
    '为什么', '为啥', '是什么原因', '原因是什么', '根因', '根本原因',
    '为何', '何故', '怎么造成', '什么导致', '哪里出问题',
    '问题出在', '差距在哪', '差在哪', '哪里有问题', '问题在哪',
]

# How-class intent words: user wants prescriptive advice, not data.
_HOWTO_INTENT_WORDS: list[str] = [
    '怎么办', '如何', '怎么做', '怎么提升', '怎么改善', '怎么优化',
    '如何提升', '如何改善', '如何优化', '怎么解决', '如何解决',
    '应该怎么', '该如何', '建议怎么',
]

# Slow-mover / worst-performer intent words. When the user asks about the
# WORST sellers ("卖不出去" / "慢销" / "末位" / "最差") but the matched template
# produces a BEST-seller / 畅销 ranking (top_n_by_dim, dish_sales_top_n), the
# cache answers the *opposite* of what the user asked. Reject → let LLM (or the
# dedicated dish_slow_movers template) give the true answer.
#
# 措施①-bug2 (May 31 2026): "哪些菜卖不出去" was matching top_n_by_dim and
# returning 畅销 Top10 — answer 答非所问. Detect the slow-mover intent and
# reject the best-seller ranking template.
_SLOW_MOVER_INTENT_WORDS: list[str] = [
    '卖不出去', '卖不动', '卖得不好', '不好卖', '滞销', '慢销', '低销',
    '末位', '末尾', '垫底', '倒数', '最差', '最少', '下架',
]

# Templates that rank items by BEST / 畅销 / Top-N. A slow-mover query against
# any of these is a semantic inversion → reject.
_BEST_SELLER_RANKING_TEMPLATES: set[str] = {
    'top_n_by_dim',
    'dish_sales_top_n',
    'dish_category_breakdown',
    'pareto_analysis',
}


def has_slow_mover_intent(query: str) -> bool:
    """True iff the query asks about the worst-selling / slow-moving items."""
    if not query:
        return False
    q = query.lower()
    return any(w in q for w in _SLOW_MOVER_INTENT_WORDS)


def query_intent_kind(query: str) -> str:
    """Detect query's analytical intent kind.

    Returns one of:
    - 'reason'  — user wants causal analysis (why X happened)
    - 'howto'   — user wants prescriptive advice (how to fix X)
    - 'ranking' — user wants Top-N / breakdown (default for analytical Q)

    Used by should_reject_cache to detect sub-intent mismatch within same
    domain (e.g. store domain "为什么末位店差" → store_performance template
    gives ranking, but user wants reason → reject).
    """
    if not query:
        return 'ranking'
    q = query.lower()
    if any(w in q for w in _WHY_INTENT_WORDS):
        return 'reason'
    if any(w in q for w in _HOWTO_INTENT_WORDS):
        return 'howto'
    return 'ranking'


def should_reject_cache(query: str, template_code: str) -> bool:
    """Return True iff the query's detected domain conflicts with the
    matched template's domain.

    Conservative — returns False (allow cache) when:
    - Query domain undetectable (no hint keywords)
    - Template not in TEMPLATE_DOMAIN AND not top_n_by_dim
    - Domains agree

    Special-case top_n_by_dim (multi-domain template): reject when query
    domain is in TOP_N_REJECT_DOMAINS (domains where Top-N rankings are
    inappropriate). S4 audit P0 fix.
    """
    if not query or not template_code:
        return False

    # 措施①-bug2 (May 31 2026): slow-mover semantic inversion. If the query
    # asks about the WORST / 卖不出去 / 滞销 items but the matched template
    # produces a BEST-seller / 畅销 Top-N ranking, the cache answers the
    # opposite of the question → reject so LLM (or dish_slow_movers) answers
    # correctly. Most specific check — runs first.
    if template_code in _BEST_SELLER_RANKING_TEMPLATES and has_slow_mover_intent(query):
        logger.info(
            f"[intent-classifier] cache REJECTED (slow-mover inversion): "
            f"query asks worst-sellers but template='{template_code}' ranks best-sellers"
        )
        return True

    # v7 #1 (Apr 26 2026): sub-intent mismatch detection. If query asks for
    # causal/prescriptive analysis but template gives ranking/Top-N, reject
    # cache → fall to LLM (which can explain WHY using the ranking data).
    # Example: "末位店营业额低的根因是什么" hits store_performance (ranking),
    # but user wants reason → cache returns "Top 10 排名" instead of analysis.
    if template_code in RANKING_TEMPLATES:
        intent_kind = query_intent_kind(query)
        if intent_kind in ('reason', 'howto'):
            logger.info(
                f"[intent-classifier] cache REJECTED (sub-intent mismatch): "
                f"query intent='{intent_kind}' vs template='{template_code}' is ranking"
            )
            return True

    query_domain = classify_query_domain(query)
    if query_domain is None:
        return False  # query domain undetectable — don't risk false positive

    # Special-case: top_n_by_dim is multi-domain but unsuitable for a fixed
    # set of analytical types. Reject those.
    if template_code == 'top_n_by_dim':
        if query_domain in TOP_N_REJECT_DOMAINS:
            logger.info(
                f"[intent-classifier] cache REJECTED (top_n_by_dim mis-route): "
                f"query_domain={query_domain} not suitable for Top-N (template=top_n_by_dim)"
            )
            return True
        return False  # other domains OK with top_n_by_dim

    template_domain = TEMPLATE_DOMAIN.get(template_code)
    if template_domain is None:
        return False  # unknown / other multi-domain template — let it through
    if query_domain == template_domain:
        return False  # aligned, allow cache
    # Genuine mismatch — reject.
    logger.info(
        f"[intent-classifier] cache REJECTED: "
        f"query_domain={query_domain} vs template_domain={template_domain} "
        f"(template={template_code})"
    )
    return True
