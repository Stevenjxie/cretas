"""Restaurant semantic query planner.

For natural-language restaurant analysis, keyword and vector matches are
candidate hints only.  The LLM (or a cache of a previously validated LLM
plan) is the normal semantic authority and emits one immutable QuerySpec with
a stable plan hash.  A very small reviewed exact-phrase registry is the only
deterministic exception: normalized whole-sentence equality may select its
single approved resolver, while substring/keyword hits never authorize
execution.  Deterministic code resolves dates and executes SQL; it may not
replace the selected resolver at execution time.

Planner, tenant-gate, resolver, and answer-contract failures are fail-closed:
the caller receives an explicit non-executing clarification instead of an
adjacent metric.  ``None`` is reserved for empty input or a confirmed
non-restaurant tenant.

Clarification continuations are stored in shared Postgres and consumed once.
The original question, the user's answer, and structured conversation context
are sent back through the same semantic planner.  Candidate retrieval still
helps the planner, but never authorizes execution.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from collections import OrderedDict
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant_ops_router import (
    _is_explicit_sales_period_comparison,
    _profit_intent,
    _resolve_sales_query_spec,
    _resolve_sales_date_range,
    _uses_relative_sales_window,
    demo_data_factory_for_code,
    dish_ranking_direction,
    extract_dish_candidate,
    extract_dish_candidates,
    extract_store_mention,
    extract_store_mentions,
    match_restaurant_ops,
    ranking_exclusions,
    ranking_limit,
    store_dish_split_dish,
)

logger = logging.getLogger(__name__)

STORE_SCOPE_CLARIFICATION_QUESTION = (
    "这项分析要看哪一组门店？请选择“全部门店”，或直接输入一家/多家门店名称；"
    "多家门店会按同一时间和指标自动比较。"
)

TRUSTED_PLANNER_AUTHORITIES = frozenset({
    "llm",
    "validated_plan_cache",
    "llm_contract_repair",
    "validated_plan_cache_contract_repair",
    "promoted_exact",
    "promoted_exact_contract_repair",
    "explicit_slots",
    "explicit_comparison_slots",
    "explicit_comparison_slots_contract_repair",
    "explicit_action_read_choice",
    "explicit_named_dish_slots",
    "explicit_financial_overview",
    "explicit_revenue_trend",
    "explicit_store_operations",
    "trusted_context",
    "trusted_context_contract_repair",
})

# Human-reviewed, zero-ambiguity whole-sentence promotions. These are not
# keyword rules: `_approved_exact_route` only accepts equality after a
# conservative whitespace/case/trailing-punctuation normalization. Keeping
# the registry explicit makes every deterministic execution grant visible in
# code review and prevents the historical `contains`-match hijacking class.
_APPROVED_EXACT_ROUTES: Dict[str, str] = {
    "哪个菜卖得好": "RESTAURANT_OPS_GROSS_MARGIN",
    "哪个菜卖得最好": "RESTAURANT_OPS_GROSS_MARGIN",
    "哪个菜最好卖": "RESTAURANT_OPS_GROSS_MARGIN",
}
_APPROVED_TIME_ANSWERS = (
    "本月",
    "上个月",
    "最近7天",
    "最近30天",
)
_APPROVED_DIRECT_TIME_PHRASES = _APPROVED_TIME_ANSWERS + (
    "本季度",
    "今年",
)
_APPROVED_ALL_STORE_ANSWERS = ("全部门店",)


# ─── QuerySpec ────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class RestaurantQuerySpec:
    """Stable output contract of the tiered router (spec section 3.1)."""

    intent: str                                   # RESTAURANT_OPS_* code, or "" when unresolved
    domain: str
    date_range: Tuple[Optional[Any], Optional[Any]]
    window_label: str
    relative_window: bool
    metrics: Tuple[str, ...]
    wants_margin: bool
    asks_profitability: bool
    dimensions: Tuple[str, ...]
    comparison: Optional[str]
    confidence: float
    # keyword | vector | llm | plan_cache | exact | explicit_slots | trusted_context
    source_tier: str
    clarification_needed: bool = False
    clarification_question: Optional[str] = None
    # Explicit primary/baseline windows are first-class immutable plan slots.
    # Resolver execution must not have to rediscover them from a later
    # clarification answer such as "全部门店".
    comparison_range: Tuple[Optional[Any], Optional[Any]] = (None, None)
    comparison_label: Optional[str] = None
    # 2026-07-08 clarification-loop v1: True when this spec was produced by
    # CONTINUING a previous clarification (see module docstring) rather than
    # a fresh single-turn parse. Additive-only (default False preserves every
    # existing construction of this dataclass).
    is_clarification_continuation: bool = False
    # Semantic coverage plan derived from the original user wording.  The
    # selected ``intent`` remains backward compatible, while these fields stop
    # a compound question from being silently reduced to that one intent.
    requested_metrics: Tuple[str, ...] = ()
    planned_intents: Tuple[str, ...] = ()
    unsupported_requirements: Tuple[str, ...] = ()
    asks_priority: bool = False
    asks_prohibited_actions: bool = False
    asks_export: bool = False
    # The requested conversational operation is separate from the metric and
    # resolver.  A follow-up may keep the same dish while changing from a
    # lookup to a diagnosis or an optimisation request; carrying the previous
    # resolver across that boundary is the exact "记得对象但复读旧答案" failure.
    analysis_action: str = "lookup"  # lookup | compare | diagnose | optimize
    ranking_direction: Optional[str] = None  # best | worst
    ranking_limit: int = 5
    excluded_entities: Tuple[str, ...] = ()
    store_scope: Optional[str] = None  # all | single | multiple
    store_slots: Tuple[str, ...] = ()
    compare_stores: bool = False
    store_options: Tuple[str, ...] = ()
    # R22 T3 结构化规格: LLM 抽取的实体槽位 (必须是问句原文子串, 代码校验;
    # 真伪由下游 resolver 对 dim_product/dim_store 验证 — LLM 只提名, 不裁决)。
    dish_slot: Optional[str] = None
    store_slot: Optional[str] = None
    # v2 execution contract. Natural-language requests are executable only
    # when the LLM planner, its validated cache, or the reviewed exact-phrase
    # registry produced the plan. T1/T2 are candidate-retrieval hints and
    # never become an authority themselves.
    plan_version: str = "legacy"
    planner_authority: str = "legacy"
    plan_hash: str = ""
    # Exact deterministic seed used to compile resolver-facing semantics.
    # A clarification continuation is built from ``original_query + answer``;
    # keeping that combined text in the sealed plan prevents a button answer
    # such as "本月" from reaching the resolver as an isolated new question.
    resolver_query_seed: str = ""


# ─── Intent catalogue (used by the T3 prompt) ──────────────────────────────
# One-line description per code so the LLM has enough signal to classify
# without re-deriving it from SAMPLE_QUERIES on every call. Kept in sync with
# the 8 RESTAURANT_OPS_* codes in restaurant_ops_router. No new codes are
# introduced in this round (spec section 7 defers new domains).
_INTENT_DESCRIPTIONS: Dict[str, str] = {
    "RESTAURANT_OPS_WASTAGE_TOP": "损耗/浪费/报损排行，按食材或类型统计损耗量和金额",
    "RESTAURANT_OPS_STOCK_SHORTAGE": "库存盘点差异（盘亏/盘盈）排行",
    "RESTAURANT_OPS_RECIPE_COST": "菜品食材成本排行（不含毛利/售价）",
    "RESTAURANT_OPS_REQUISITION_TREND": "食材领料趋势和领用量排行",
    "RESTAURANT_OPS_GROSS_MARGIN": "菜品级别的毛利/毛利率分析",
    "RESTAURANT_OPS_STORE_MARGIN": "门店级别的毛利/毛利率对比",
    "RESTAURANT_OPS_SALES_SUMMARY": "总体经营概览：营收、订单、客单价、是否盈利",
    "RESTAURANT_OPS_TREND_ANALYSIS": "营收的同比/环比/月度趋势分析",
    "RESTAURANT_OPS_INVENTORY_WARNING": "食材库存水位预警：低于补货点/安全库存的食材，提示补货（区别于盘点差异/盘亏的历史账实差）",
    "RESTAURANT_OPS_STAFFING_ADVICE": "按时段(午市/晚市/下午茶/夜宵)的人效比诊断，建议哪个时段加人/减人",
}

_VALID_CODES = frozenset(_INTENT_DESCRIPTIONS)
TIME_CLARIFICATION_QUESTION = (
    "你想看哪个时间范围？请选择本月、上个月、最近7天或最近30天。"
)
_TIME_SCOPED_INTENTS = frozenset({
    "RESTAURANT_OPS_WASTAGE_TOP",
    "RESTAURANT_OPS_REQUISITION_TREND",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_TREND_ANALYSIS",
    "RESTAURANT_OPS_STAFFING_ADVICE",
})


# ─── Deterministic slot detectors (shared across all 3 tiers) ─────────────

_STORE_TOKENS = ("门店", "分店", "店铺", "哪家店", "哪个店", "各店")
_DISH_TOKENS = ("菜品", "菜系", "菜价", "哪道菜", "哪个菜", "单品")
_INGREDIENT_TOKENS = ("食材", "原料", "配料", "领料", "领用")
_INGREDIENT_COST_METRIC_RE = re.compile(
    r"(?:食材|原材料|原料|配料)(?:的)?"
    r"(?:单份|每份|每一份|一份|单位)?成本(?:率)?"
)


def _detect_dimensions(text: str) -> Tuple[str, ...]:
    """Which real-world objects the question is asking to break down by.

    Order is deterministic (store, dish, ingredient) so tests/logs are stable.
    """
    dims: List[str] = []
    if any(tok in text for tok in _STORE_TOKENS) or ("店" in text and "点" not in text):
        dims.append("store")
    if any(tok in text for tok in _DISH_TOKENS) or "菜" in text:
        dims.append("dish")
    # ``食材成本`` is a metric label, not automatically a request to group by
    # ingredient.  Keep real grain requests such as ``按食材拆分`` or
    # ``哪些原料影响毛利`` intact while removing only the compound metric
    # phrase before the dimension scan.
    ingredient_dimension_text = _INGREDIENT_COST_METRIC_RE.sub("", text)
    if any(tok in ingredient_dimension_text for tok in _INGREDIENT_TOKENS):
        dims.append("ingredient")
    return tuple(dims)


def _detect_comparison(text: str) -> Optional[str]:
    if (
        any(tok in text for tok in ("昨天", "昨日"))
        and any(tok in text for tok in ("前天", "前日", "前一天", "前一日"))
    ):
        return "previous_day"
    if "上上个月" in text or "上上月" in text:
        return "previous_month"
    if (
        any(tok in text for tok in ("本月", "这个月", "当月"))
        and any(tok in text for tok in ("上个月", "上月"))
    ):
        return "previous_month"
    if "同比" in text:
        return "yoy"
    if "今年" in text and "去年" in text:
        return "yoy"
    if "环比" in text:
        return "mom"
    if any(tok in text for tok in ("比上周", "周比", "跟上周比")):
        return "wow"
    return None


_ALL_STORE_SCOPE_TOKENS = (
    "全部门店", "所有门店", "各门店", "各家店", "每家店",
    "所有店", "全部店", "全店汇总", "连锁整体",
)
_STORE_RANK_SCOPE_TOKENS = (
    "哪家店", "哪个店", "哪家门店", "门店排名", "门店排行", "各店排名",
)
_STORE_BREAKDOWN_SCOPE_TOKENS = (
    *_STORE_RANK_SCOPE_TOKENS,
    "各门店", "各家店", "每家店", "各店", "每个店",
    "按门店", "分门店", "逐店", "逐家", "门店之间", "店与店",
)


def _asks_store_breakdown(text: str) -> bool:
    """Whether an all-store scope also asks for store-grain output.

    “全部门店” by itself is a filter/aggregation scope.  Phrases such as
    “各门店”“哪家店”“按门店” explicitly request a store breakdown and therefore
    keep the store dimension.
    """
    return any(token in text for token in _STORE_BREAKDOWN_SCOPE_TOKENS)


def _detect_store_scope(text: str) -> Tuple[Optional[str], Tuple[str, ...]]:
    mentions = tuple(extract_store_mentions(text))
    if len(mentions) >= 2:
        return "multiple", mentions
    if len(mentions) == 1:
        return "single", mentions
    if any(token in text for token in _ALL_STORE_SCOPE_TOKENS):
        return "all", ()
    if any(token in text for token in _STORE_RANK_SCOPE_TOKENS):
        return "all", ()
    return None, ()


def _detect_ranking_direction(text: str) -> Optional[str]:
    direct = dish_ranking_direction(text)
    if direct:
        return direct
    if re.search(r"倒数|后\s*[一二三四五六七八九十\d]+\s*名|最低|最差|垫底", text):
        return "worst"
    if re.search(r"前\s*[一二三四五六七八九十\d]+\s*名|最高|最好|第一", text):
        return "best"
    return None


_OPTIMIZATION_OBJECTIVE_TOKENS = (
    "营收", "营业额", "销售额", "销量", "订单", "单量", "客单价", "转化率",
    "毛利", "利润", "成本", "损耗", "浪费", "库存", "缺货", "周转", "排班",
    "人效", "评分", "评价", "差评", "退菜", "退款", "出餐", "等位", "复购",
    "慢销", "滞销", "菜品结构", "套餐", "连带率", "替代率", "采购",
)


def optimization_clarification_question(query: str) -> Optional[str]:
    """Ask for the business objective instead of guessing what to optimise."""
    text = (query or "").strip()
    if (
        "成本" in text
        and any(token in text for token in ("毛利", "利润"))
        and any(token in text for token in ("先查", "优先", "哪几项", "哪项"))
        and not any(token in text for token in (
            "菜品成本", "食材成本", "食材损耗", "门店毛利", "菜品毛利",
        ))
    ):
        return (
            "你想先查哪一类问题：菜品成本是否异常、食材损耗是否偏高，还是门店毛利是否偏低？"
            "这三类需要不同数据；也可以直接说“最近30天三项都查，并给出优先级”。"
        )
    if not any(token in text for token in ("优化", "改善", "提升经营", "经营建议")):
        return None
    if any(token in text for token in _OPTIMIZATION_OBJECTIVE_TOKENS):
        return None
    return (
        "你想优先优化哪一个目标？可以直接选：营收、毛利率、订单与客单价、"
        "慢销菜品、损耗与库存、排班人效，或顾客评价。不同目标的做法和判断指标不一样。"
    )


_REQUEST_METRIC_RULES: Tuple[Tuple[str, Tuple[str, ...]], ...] = (
    ("net_profit", ("净利润", "净利率", "净利润率", "经营利润", "实际利润", "净赚")),
    ("table_turnover", ("翻台率", "翻台")),
    ("recipe_cost", ("菜品成本", "食材成本", "配方成本", "单品成本", "成本")),
    ("wastage", ("食材损耗", "损耗", "浪费", "报损", "腐坏", "过期")),
    ("sales_volume", (
        "菜品销量", "销量", "销售量", "卖得好", "卖得最好", "最好卖",
        "销量最高", "最受欢迎", "卖得慢", "卖了多少", "卖出",
        "畅销", "慢销", "滞销",
    )),
    ("gross_margin", ("毛利率", "毛利", "利润", "盈利", "赚钱", "亏钱", "亏损", "亏本", "赔钱")),
    ("revenue", ("营业收入", "销售收入", "营业额", "销售额", "营收", "流水")),
    ("orders", ("订单集中", "订单数", "订单", "单量", "客单价")),
    ("staffing", ("人员不足", "人手不足", "人手", "人员", "排班", "人效", "在岗人数")),
    ("return_rate", ("退菜率", "退菜", "退款率", "退款")),
    ("customer_review", ("顾客评价", "顾客评分", "差评", "好评", "评分", "口碑")),
    ("production_time", ("制作时长", "制作时间", "加工时长", "烹饪时长")),
    ("service_speed", ("出餐慢", "出餐速度", "出餐时长", "上菜慢", "等餐")),
    ("process_bottleneck", ("工序瓶颈", "流程瓶颈", "工序耗时", "工序")),
)

_UNSUPPORTED_REQUIREMENTS = frozenset({
    "net_profit", "table_turnover", "return_rate", "customer_review", "production_time",
    "service_speed", "process_bottleneck",
})

_UNSUPPORTED_REQUIREMENT_LABELS = {
    "net_profit": "净利润（缺少费用、税费及其他收支）",
    "table_turnover": "翻台率（缺少桌台、开台/结账时间、就餐轮次和可用桌数）",
    "return_rate": "退菜率（缺少退菜时间、菜品、数量、原因和责任门店）",
    "customer_review": "顾客评价（缺少评分、评价文本、时间、菜品与门店）",
    "production_time": "菜品制作时长（缺少开始制作和完成时间）",
    "service_speed": "逐单出餐时长（缺少下单、开始制作和出餐完成时间）",
    "process_bottleneck": "工序瓶颈（缺少各工序节点及耗时）",
}


def _detect_requested_metrics(text: str) -> Tuple[str, ...]:
    detected_items: List[str] = []
    for metric, tokens in _REQUEST_METRIC_RULES:
        if metric == "recipe_cost":
            # A raw substring search sees the character boundary in
            # ``生成本月`` / ``完成本周`` as the word ``成本`` and injects an
            # unrelated recipe-cost resolver into revenue chart requests.
            # Longer cost phrases remain exact; the generic word is accepted
            # unless ``本`` is immediately starting a calendar-period word.
            has_metric = any(token in text for token in tokens if token != "成本")
            has_metric = has_metric or bool(
                re.search(r"成本(?![日周月季年])", text)
                or re.search(r"成本(?:日|周|月|季|年)报", text)
            )
        else:
            has_metric = any(token in text for token in tokens)
        if has_metric:
            detected_items.append(metric)
    detected = tuple(detected_items)
    rejects_gross_substitution = any(token in text for token in (
        "不要用毛利", "不能用毛利", "不用毛利", "不拿毛利", "毛利替代",
    ))
    if "net_profit" in detected and (
        "毛利" not in text or rejects_gross_substitution
    ):
        detected = tuple(metric for metric in detected if metric != "gross_margin")
    rejects_revenue_substitution = any(token in text for token in (
        "不要用营业额", "不能用营业额", "不用营业额", "不拿营业额",
        "不要用营收", "不能用营收", "不用营收", "不拿营收",
        "不要用销售额", "不能用销售额", "不用销售额",
    ))
    if "net_profit" in detected and rejects_revenue_substitution:
        detected = tuple(metric for metric in detected if metric != "revenue")
    return detected


def _detect_analysis_action(text: str) -> str:
    """Classify what the current turn asks us to *do* with the metric.

    This is a deterministic slot, like the date window.  It deliberately does
    not inherit the previous turn's action or resolver.
    """
    current = (text or "").strip()
    if any(token in current for token in ("为什么", "原因", "怎么回事", "为何")):
        return "diagnose"
    if any(token in current for token in (
        "怎么优化", "如何优化", "优化", "改善", "怎么办", "怎么做",
        "怎么提升", "如何提升", "提升", "下一步", "先做什么",
    )):
        return "optimize"
    if _detect_comparison(current) or any(token in current for token in (
        "相比", "对比", "比较", "高还是低", "多还是少",
    )):
        return "compare"
    return "lookup"


def _plan_requested_intents(
    text: str,
    selected_code: str,
    requested_metrics: Tuple[str, ...],
    dimensions: Tuple[str, ...],
    store_scope: Optional[str],
) -> Tuple[str, ...]:
    """Build a deterministic, deduplicated multi-resolver plan.

    A resolver can satisfy more than one requested metric.  For example, the
    dish-margin resolver already reads both dish sales volume and margin, and
    the sales-summary resolver can satisfy a revenue + margin owner question.
    """
    planned: List[str] = []
    has_revenue_scope = any(metric in requested_metrics for metric in ("revenue", "orders"))
    explicit_store_margin = any(token in text for token in (
        "门店毛利", "分店毛利", "店铺毛利", "门店利润", "分店利润", "店铺利润",
    ))

    for metric in requested_metrics:
        code: Optional[str] = None
        if metric == "recipe_cost":
            # The recipe-cost ranking resolver has no named-dish scope.  A
            # question such as "米饭的成本" must use the scoped unit-economics
            # resolver; otherwise it silently returns the all-dish cost榜.
            # A concrete single/multi-store slice belongs to STORE_MARGIN
            # because it owns the store×dish grain. Routing that shape to the
            # all-store GROSS_MARGIN resolver caused a live immutable-plan
            # rejection after a single-store dish ranking.
            has_dish = bool(extract_dish_candidate(text))
            code = (
                "RESTAURANT_OPS_STORE_MARGIN"
                if has_dish
                and "store" in dimensions
                and store_scope in {"single", "multiple"}
                else "RESTAURANT_OPS_GROSS_MARGIN"
                if has_dish
                else "RESTAURANT_OPS_RECIPE_COST"
            )
        elif metric == "wastage":
            code = "RESTAURANT_OPS_WASTAGE_TOP"
        elif metric == "sales_volume":
            if "store" in dimensions and "dish" in dimensions:
                code = (
                    "RESTAURANT_OPS_STORE_MARGIN"
                    if store_scope in {"single", "multiple"}
                    or store_dish_split_dish(text)
                    else "RESTAURANT_OPS_GROSS_MARGIN"
                )
            elif "store" in dimensions and store_scope in {"single", "multiple"}:
                code = "RESTAURANT_OPS_STORE_MARGIN"
            elif "dish" in dimensions:
                code = "RESTAURANT_OPS_GROSS_MARGIN"
            else:
                code = "RESTAURANT_OPS_SALES_SUMMARY"
        elif metric == "gross_margin":
            if selected_code == "RESTAURANT_OPS_SALES_SUMMARY" and has_revenue_scope:
                code = "RESTAURANT_OPS_SALES_SUMMARY"
            elif (
                "store" in dimensions
                and store_scope in {"single", "multiple"}
            ) or explicit_store_margin or (
                selected_code == "RESTAURANT_OPS_STORE_MARGIN" and "store" in dimensions
            ):
                code = "RESTAURANT_OPS_STORE_MARGIN"
            else:
                code = "RESTAURANT_OPS_GROSS_MARGIN"
        elif metric in ("revenue", "orders"):
            if "dish" in dimensions:
                # Named-dish revenue/orders live in the joined POS + recipe
                # unit-economics resolver.  The all-store sales summary cannot
                # truthfully answer a dish-scoped question.  A concrete
                # single/multi-store slice still uses STORE_MARGIN because it
                # owns the store×dish grain; "全部门店" is only an aggregation
                # scope and must not erase the named dish.
                code = (
                    "RESTAURANT_OPS_STORE_MARGIN"
                    if "store" in dimensions and store_scope in {"single", "multiple"}
                    else "RESTAURANT_OPS_GROSS_MARGIN"
                )
            elif "store" in dimensions:
                code = "RESTAURANT_OPS_STORE_MARGIN"
            elif selected_code == "RESTAURANT_OPS_TREND_ANALYSIS":
                code = "RESTAURANT_OPS_TREND_ANALYSIS"
            else:
                code = "RESTAURANT_OPS_SALES_SUMMARY"
        elif metric == "staffing":
            code = "RESTAURANT_OPS_STAFFING_ADVICE"

        if code and code not in planned:
            planned.append(code)

    if not planned and selected_code:
        planned.append(selected_code)
    return tuple(planned)


def _unsupported_requirement_question(
    requirements: Tuple[str, ...],
    requested_metrics: Tuple[str, ...] = (),
) -> str:
    available_labels = {
        "recipe_cost": "菜品成本",
        "wastage": "食材损耗",
        "sales_volume": "菜品销量",
        "gross_margin": "已覆盖销售的毛利",
        "revenue": "营业收入",
        "orders": "订单与客单价",
        "staffing": "排班人效",
    }
    available = [
        label for metric, label in available_labels.items()
        if metric in requested_metrics
    ]
    if not available:
        available = ["订单集中程度", "排班人效", "菜品销量", "已覆盖销售的毛利"]
    missing = [
        _UNSUPPORTED_REQUIREMENT_LABELS[item]
        for item in requirements
        if item in _UNSUPPORTED_REQUIREMENT_LABELS
    ]
    return (
        f"当前可以可靠分析：{'、'.join(available)}。"
        f"当前不能可靠分析：{'；'.join(missing)}。"
        "不会用营业额、毛利或其他相近指标替代这些缺失指标，也不会把部分完成说成全部完成。"
        "补齐括号内明细后可以继续；也可以明确只分析当前已有的维度。"
    )


def unsupported_requirements_disclosure(requirements: Tuple[str, ...]) -> str:
    """Explain which requested dimensions were left blank after partial analysis."""
    missing = [
        _UNSUPPORTED_REQUIREMENT_LABELS[item]
        for item in requirements
        if item in _UNSUPPORTED_REQUIREMENT_LABELS
    ]
    if not missing:
        return ""
    bullets = "\n".join(f"- {item}" for item in missing)
    return (
        "\n\n### 本次缺少数据、暂时留空的维度\n"
        f"{bullets}\n"
        "这些维度本次没有参与结论，也没有用营业额、毛利或其他相邻指标替代。"
        "补齐上述明细后，可以在同一分析中继续合并判断。"
    )


def capability_clarification_question(query: str) -> Optional[str]:
    """Honest fallback for analyses that are not implemented in chat yet."""
    text = (query or "").strip()
    if any(token in text for token in (
        "线性回归", "回归曲线", "决定系数", "R²", "R2", "价格弹性", "提价影响", "降价影响",
    )):
        return (
            "当前对话还不能可靠完成价格弹性、因果影响或回归图。"
            "需要可导出的菜品名称、日期、门店、价格变动、销量、销售额、订单数、促销、渠道、"
            "营业时段、缺货情况，以及可比较的对照门店或对照时段；只有相关性还不能证明因果。"
            "字段完整后才能计算弹性、置信区间和决定系数。这里不会用简单涨跌替代因果结论，"
            "也不会把没有生成的图表描述为成功。"
        )
    return None


_FOLLOWUP_PREFIXES = (
    "那", "这个", "那个", "这道菜", "那道菜", "它", "该菜", "该店", "刚才", "继续", "再", "为什么", "怎么做", "怎么办", "怎么",
    "如何", "下一步", "先做", "换成", "改成", "换回", "改回",
    "换看", "改看", "换查", "改查", "只看", "就看", "只查", "就查",
    "哪些动作", "先别", "明天看",
    "和上", "与上", "跟上", "比上", "呢",
)
_ORDINAL_FOLLOWUP_RE = re.compile(
    r"^第(?:[一二三四五六七八九十百千万两\d]{1,8})名(?:的)?"
)
_NEW_TOPIC_TOKENS = ("换个话题", "换一个问题", "另一个问题", "另外问", "新话题")
_CONTEXT_METRIC_LABELS = {
    "sales_volume": "销量",
    "recipe_cost": "成本",
    "gross_margin": "毛利率",
    "revenue": "营收",
    "orders": "订单与客单价",
    "wastage": "损耗",
    "staffing": "排班人效",
}
_SAFE_CONTEXT_METRICS = frozenset(_CONTEXT_METRIC_LABELS)


def _structured_followup_context(parent: Dict[str, Any]) -> Dict[str, Any]:
    """Read the newest trusted semantic slots written by a resolver."""
    history = parent.get("turns_history")
    if isinstance(history, str):
        try:
            history = json.loads(history)
        except (TypeError, ValueError):
            history = None
    contexts: List[Any] = []
    if isinstance(history, list):
        contexts.extend(
            turn.get("context")
            for turn in reversed(history)
            if isinstance(turn, dict)
        )
    contexts.append(parent.get("structured_context"))
    for context in contexts:
        if not isinstance(context, dict):
            continue
        entity = context.get("focus_entity")
        safe_entity = None
        if isinstance(entity, dict):
            entity_type = entity.get("type")
            name = entity.get("name")
            if (
                entity_type in ("dish", "store")
                and isinstance(name, str)
                and name.strip()
            ):
                safe_entity = {
                    "type": entity_type,
                    "name": name.strip()[:80],
                }
        raw_metrics = context.get("requested_metrics")
        metrics = tuple(
            metric
            for metric in (raw_metrics if isinstance(raw_metrics, list) else [])
            if metric in _SAFE_CONTEXT_METRICS
        )
        window_label = context.get("window_label")
        safe_window = (
            window_label.strip()[:40]
            if isinstance(window_label, str) and window_label.strip()
            else None
        )
        ranking_direction = context.get("ranking_direction")
        if ranking_direction not in {"best", "worst"}:
            ranking_direction = None
        ranking_count = context.get("ranking_limit")
        if not isinstance(ranking_count, int) or not 1 <= ranking_count <= 20:
            ranking_count = None
        excluded_entities = tuple(
            str(item).strip()
            for item in (
                context.get("excluded_entities")
                if isinstance(context.get("excluded_entities"), list)
                else []
            )
            if isinstance(item, str) and 1 <= len(item.strip()) <= 40
        )[:12]
        store_scope = context.get("store_scope")
        if store_scope not in {"all", "single", "multiple"}:
            store_scope = None
        store_names = tuple(
            str(item).strip()
            for item in (
                context.get("store_names")
                if isinstance(context.get("store_names"), list)
                else []
            )
            if isinstance(item, str) and 1 <= len(item.strip()) <= 80
        )[:8]
        topic_kind = context.get("topic_kind")
        if topic_kind not in {"dish_ranking", "store_ranking"}:
            topic_kind = None
        if (
            safe_entity or metrics or safe_window or ranking_direction
            or store_scope or topic_kind
        ):
            return {
                "focus_entity": safe_entity,
                "requested_metrics": metrics,
                "window_label": safe_window,
                "analysis_action": (
                    context.get("analysis_action")
                    if context.get("analysis_action") in {
                        "lookup", "compare", "diagnose", "optimize",
                    }
                    else "lookup"
                ),
                "topic_kind": topic_kind,
                "ranking_direction": ranking_direction,
                "ranking_limit": ranking_count,
                "excluded_entities": excluded_entities,
                "store_scope": store_scope,
                "store_names": store_names,
                "compare_stores": bool(context.get("compare_stores")),
            }
    return {}


def _structured_focus_entity(parent: Dict[str, Any]) -> Optional[Dict[str, str]]:
    """Compatibility wrapper used by older callers/tests."""
    context = _structured_followup_context(parent)
    entity = context.get("focus_entity")
    return entity if isinstance(entity, dict) else None


def _context_metric_label(metrics: Sequence[str]) -> Optional[str]:
    labels: List[str] = []
    for metric in metrics:
        label = _CONTEXT_METRIC_LABELS.get(metric)
        if label and label not in labels:
            labels.append(label)
    return "和".join(labels) if labels else None


def _strip_followup_reference(text: str) -> str:
    """Remove only discourse/pronoun scaffolding, never metric words."""
    body = re.sub(
        r"^(?:那|这个|那个)?(?:它|这道菜|那道菜|那个菜|这个菜|"
        r"该菜|这家店|那家店|该店|第[一二三四五六七八九十百千万两\d]{1,8}名)(?:的)?[，, ]*",
        "",
        text,
        count=1,
    )
    if body == text:
        body = re.sub(
            r"^(?:那|刚才|继续|再)[，, ]*",
            "",
            text,
            count=1,
        )
    return body.strip() or text.strip()


_TIME_SLOT_ONLY_PATTERN = re.compile(
    r"^(?:"
    r"今天|今日|昨天|昨日|前天|前日|前一天|前一日|"
    r"本周|这周|本星期|这星期|上周|上星期|上个星期|"
    r"本月|这个月|上个月|上月|上上个月|上上月|"
    r"今年|去年|上半年|下半年|半年|"
    r"(?:最近|近|过去)\s*[0-9一二两三四五六七八九十俩仨]{0,4}\s*"
    r"个?\s*(?:天|日|周|星期|月|年)|"
    r"这\s*[0-9一二两三四五六七八九十俩仨]{1,4}\s*"
    r"个?\s*(?:天|日|周|星期|月|年)|"
    r"(?:(?:20\d{2})\s*年|去年|今年)?\s*"
    r"(?:1[0-2]|0?[1-9]|[一二三四五六七八九十]|十一|十二)\s*月"
    r")$"
)
_SLOT_UPDATE_PREFIX_PATTERN = re.compile(
    r"^(?:换回|改回|换成|改成|换到|改到|改为|换看|改看|换查|改查|只看|就看|只查|就查|看|查)\s*"
)


def _strip_slot_update_scaffolding(text: str) -> str:
    """Remove only verbs/particles surrounding a single-slot replacement."""
    normalized = (text or "").strip()
    normalized = _SLOT_UPDATE_PREFIX_PATTERN.sub("", normalized, count=1)
    normalized = re.sub(
        r"^(?:和|与|跟|、|，|,)+",
        "",
        normalized,
        count=1,
    ).strip()
    normalized = re.sub(
        r"\s*(?:呢|吧|可以吗|行吗|怎么样)?[？?。！!]*$",
        "",
        normalized,
        count=1,
    )
    return normalized.strip(" ，,、")


def _is_pure_time_slot_update(text: str) -> bool:
    """True only when the whole turn is a supported time replacement."""
    candidate = _strip_slot_update_scaffolding(text)
    return bool(
        candidate
        and _TIME_SLOT_ONLY_PATTERN.fullmatch(candidate)
        and _resolve_sales_date_range(candidate)[1] != "全部历史"
    )


def _is_pure_store_slot_update(
    text_without_store_scope: str,
    store_scope: Optional[str],
) -> bool:
    """True only when removing the parsed store leaves replacement scaffolding."""
    if store_scope not in {"all", "single", "multiple"}:
        return False
    return not _strip_slot_update_scaffolding(text_without_store_scope)


def _parse_followup_store_scope(
    text: str,
) -> Tuple[Optional[str], Tuple[str, ...], str]:
    """Parse a scope update and return normalized stores plus the remainder.

    Store extraction intentionally keeps leading words such as ``只看`` in the
    raw mention so independently named stores remain verbatim.  For a dependent
    slot update those words are conversational scaffolding, not part of the
    store name.  Remove the raw mention from the utterance first, normalize only
    the returned store slot, then discard conjunctions left between multiple
    stores.  This prevents ``只看 A 和 B`` from leaking ``只看和`` into the dish
    extractor.
    """
    scope, raw_names = _detect_store_scope(text)
    remainder = (text or "").strip()
    if scope not in {"all", "single", "multiple"}:
        return scope, (), remainder
    normalized_names: List[str] = []
    if scope in {"single", "multiple"}:
        for raw_name in raw_names:
            normalized_name = _SLOT_UPDATE_PREFIX_PATTERN.sub(
                "",
                raw_name,
                count=1,
            ).strip()
            if normalized_name:
                normalized_names.append(normalized_name)
            if raw_name and raw_name in remainder:
                remainder = remainder.replace(raw_name, "", 1)
            elif normalized_name and normalized_name in remainder:
                remainder = remainder.replace(normalized_name, "", 1)
    elif scope == "all":
        for token in sorted(_ALL_STORE_SCOPE_TOKENS, key=len, reverse=True):
            if token in remainder:
                remainder = remainder.replace(token, "", 1)
                break
    remainder = re.sub(
        r"^(?:和|与|跟|、|，|,)+",
        "",
        remainder,
        count=1,
    ).strip()
    return scope, tuple(normalized_names), remainder


def contextualize_restaurant_followup(
    query: str,
    parent: Optional[Dict[str, Any]],
) -> Tuple[str, bool]:
    """Carry restaurant context only for an explicit dependent follow-up.

    This is deliberately conservative: a standalone question with its own
    metric and time range starts a fresh topic even when a session exists.
    """
    current = (query or "").strip()
    if not current or not parent or any(token in current for token in _NEW_TOPIC_TOKENS):
        return current, False
    parent_query = str(parent.get("parent_query") or "").strip()
    parent_code = str(parent.get("parent_template_code") or "").strip()
    context = _structured_followup_context(parent)
    if (
        not parent_query
        or (
            not parent_code.startswith("RESTAURANT_OPS_")
            and not context
        )
    ):
        return current, False

    preview_store_scope, _, preview_store_remainder = _parse_followup_store_scope(
        current,
    )
    pure_store_scope_signal = bool(
        preview_store_scope in {"all", "single", "multiple"}
        and _is_pure_store_slot_update(
            preview_store_remainder,
            preview_store_scope,
        )
    )
    has_followup_signal = (
        len(current) <= 32
        and (
            current.startswith(_FOLLOWUP_PREFIXES)
            or _ORDINAL_FOLLOWUP_RE.match(current)
            or pure_store_scope_signal
            or current.endswith(("呢", "吗", "怎么办", "为什么", "如何", "怎么样", "合理"))
            or any(
                token in current
                for token in (
                    "相比", "对比", "比呢", "高还是低", "是否",
                    "怎么提升", "如何提升", "怎么优化", "如何优化",
                    "下一步", "先做什么",
                )
            )
        )
    )
    if not has_followup_signal:
        return current, False

    # A fully specified new metric + time phrase is self-contained.  Leading
    # pronouns such as "那毛利呢" remain dependent and intentionally inherit.
    standalone_code = match_restaurant_ops(current)
    leading_dependent = current.startswith((
        "那", "这个", "那个", "它", "刚才", "继续", "再",
        "换成", "改成", "换回", "改回", "换看", "改看", "换查", "改查",
        "只看", "就看", "只查", "就查",
    ))
    if standalone_code and _uses_relative_sales_window(current) and not leading_dependent:
        return current, False

    # Resolver-produced slots are the only inheritance source.  Crucially, we
    # never concatenate ``parent_query``: doing so re-injected the old metric
    # and resolver into every later turn ("销量呢" kept answering 毛利).
    focus_entity = context.get("focus_entity")
    context_metrics = context.get("requested_metrics") or ()
    explicit_metrics = _detect_requested_metrics(current)
    metric_label = _context_metric_label(explicit_metrics or context_metrics)
    (
        current_store_scope,
        current_store_names,
        current_without_store_scope,
    ) = _parse_followup_store_scope(current)
    body = _strip_followup_reference(current_without_store_scope)
    action = _detect_analysis_action(current)
    # A short metric-action turn normally continues the focused entity
    # ("销量怎么优化" after a dish ranking).  An explicit whole-business
    # scope is different: it starts a store/menu-wide question and must not
    # silently inherit the previous top dish.
    explicit_whole_business_scope = any(
        token in current
        for token in (
            "全店", "整个门店", "门店整体", "整体经营",
            "全部菜品", "所有菜品",
        )
    )
    explicit_entity_reference = any(
        token in current
        for token in (
            "这个菜", "那个菜", "这道菜", "那道菜", "它",
            "第一名", "第二名", "第三名",
        )
    )
    if (
        explicit_whole_business_scope
        and explicit_metrics
        and action in {"diagnose", "optimize"}
        and not explicit_entity_reference
    ):
        return current, False
    current_window = _resolve_sales_date_range(current)[1]
    pure_time_slot_update = bool(
        not explicit_metrics
        and current_store_scope is None
        and _is_pure_time_slot_update(current)
    )
    pure_store_slot_update = bool(
        not explicit_metrics
        and current_window == "全部历史"
        and _is_pure_store_slot_update(
            current_without_store_scope,
            current_store_scope,
        )
    )

    # Ranking follow-ups are changes to ranking slots, not dish names.  Without
    # this branch "那倒数五名呢" was fed to extract_dish_candidate and became a
    # fictitious dish called "倒数五名".
    explicit_ranking_direction = _detect_ranking_direction(current)
    if (
        context.get("topic_kind") == "dish_ranking"
        and explicit_ranking_direction is not None
        and not explicit_metrics
    ):
        inherited_limit = context.get("ranking_limit")
        effective_limit = ranking_limit(
            current,
            inherited_limit if isinstance(inherited_limit, int) else 5,
        )
        direction_text = (
            "销量最高" if explicit_ranking_direction == "best" else "销量最低"
        )
        scope = context.get("store_scope")
        store_names = context.get("store_names") or ()
        if scope == "all":
            scope_text = "全部门店"
        elif scope in {"single", "multiple"} and store_names:
            scope_text = "和".join(store_names)
        else:
            scope_text = ""
        exclusions = tuple(context.get("excluded_entities") or ())
        exclusion_text = (
            f"，排除{'、'.join(exclusions)}"
            if exclusions
            else ""
        )
        parent_window = context.get("window_label")
        window_text = (
            str(parent_window)
            if isinstance(parent_window, str) and parent_window != "全部历史"
            else ""
        )
        resolved = (
            f"{window_text}{scope_text}{direction_text}的前{effective_limit}道菜"
            f"{exclusion_text}"
        )
        return resolved, True

    if isinstance(focus_entity, dict):
        entity_name = focus_entity["name"]
        entity_type = focus_entity["type"]
        entity_source = re.sub(
            r"^(?:换成|改成)",
            "",
            current_without_store_scope,
        ).strip()
        # A generic reference is not a newly named entity.  In particular,
        # ``这个菜的单份成本`` used to be parsed as a dish called ``菜的单份``
        # after the leading pronoun was stripped by the dish-name extractor.
        # Keep explicit switches such as ``那招牌藤椒味呢`` working, but let
        # trusted context resolve pronoun-led dish/store follow-ups first.
        context_reference = (
            re.match(
                r"^(?:它|这个菜|这道菜|那个菜|那道菜|该菜|"
                r"第[一二三四五六七八九十百千万两\d]{1,8}名)(?:的)?",
                entity_source,
            )
            if entity_type == "dish"
            else re.match(
                r"^(?:它|这个店|那个店|这家店|那家店|该店|"
                r"第[一二三四五六七八九十百千万两\d]{1,8}名)(?:的)?",
                entity_source,
            )
        )
        explicit_entity = (
            None
            if context_reference
            else (
                extract_dish_candidate(entity_source)
                if entity_type == "dish"
                else extract_store_mention(entity_source)
            )
        )
        # An explicitly named object replaces the entity slot while retaining
        # the trusted metric/window slots when this utterance is dependent
        # ("换成 X 呢", "X 的营收呢").  This must not re-prefix the old entity.
        if pure_time_slot_update and metric_label:
            resolved = f"{current_window}{entity_name}的{metric_label}如何"
        elif pure_store_slot_update and metric_label:
            resolved = f"{entity_name}的{metric_label}如何"
        elif explicit_entity:
            explicit_window = _resolve_sales_date_range(current)[1]
            resolved_metric = metric_label
            if action == "diagnose":
                resolved = (
                    f"{explicit_entity}的{resolved_metric}为什么是这样"
                    if resolved_metric else f"{explicit_entity}为什么会这样"
                )
            elif action == "optimize":
                resolved = (
                    f"{explicit_entity}的{resolved_metric}怎么优化"
                    if resolved_metric else f"{explicit_entity}怎么优化"
                )
            else:
                resolved = (
                    f"{explicit_entity}的{resolved_metric}如何"
                    if resolved_metric else f"{explicit_entity}表现如何"
                )
            if explicit_window != "全部历史" and explicit_window not in resolved:
                resolved = f"{explicit_window}{resolved}"
            entity_name = explicit_entity
            entity_type = focus_entity["type"]

        elif action == "diagnose":
            resolved = (
                f"{entity_name}的{metric_label}为什么是这样"
                if metric_label else f"{entity_name}为什么会这样"
            )
        elif action == "optimize":
            resolved = (
                f"{entity_name}的{metric_label}怎么优化"
                if metric_label else f"{entity_name}怎么优化"
            )
        elif (
            not explicit_metrics
            and _resolve_sales_date_range(body)[1] != "全部历史"
            and body.rstrip("呢？?") != body
        ):
            # ``换成/改成 + 时间`` changes only the time slot.  Leaving the
            # switch verb in front of the date prevents the downstream dish
            # extractor from reaching the trusted entity and silently falls
            # back to an all-dish resolver.
            time_text = re.sub(
                r"^(?:换成|改成)",
                "",
                body.rstrip("呢？?").strip(),
            ).strip()
            resolved = (
                f"{time_text}{entity_name}的{metric_label}如何"
                if metric_label else f"{time_text}{entity_name}表现如何"
            )
        elif body.startswith(("卖", "赚", "亏")):
            resolved = f"{entity_name}{body}"
        elif body.startswith(("和", "与", "跟", "比")) and metric_label:
            resolved = f"{entity_name}的{metric_label}{body}"
        else:
            resolved = f"{entity_name}的{body}"
    elif metric_label:
        if action == "diagnose":
            resolved = f"{metric_label}为什么是这样"
        elif action == "optimize":
            resolved = f"{metric_label}怎么优化"
        elif body.startswith(("和", "与", "跟", "比")):
            resolved = f"{metric_label}{body}"
        else:
            resolved = current
    else:
        resolved = current

    # Store scope is a first-class trusted slot, just like time and entity.
    # A dependent follow-up such as "它的成本和毛利呢？" must not forget that
    # the user already selected "全部门店" (or a named store set) on the
    # ranking turn.  Explicit store scope in the current utterance wins.
    if current_store_scope == "all":
        current_scope_text = "全部门店"
    elif current_store_scope in {"single", "multiple"} and current_store_names:
        current_scope_text = "和".join(current_store_names)
    else:
        current_scope_text = ""
    if current_scope_text:
        if current_scope_text not in resolved:
            resolved = f"{current_scope_text}{resolved}"
    else:
        parent_store_scope = context.get("store_scope")
        parent_store_names = tuple(context.get("store_names") or ())
        if parent_store_scope == "all":
            store_scope_text = "全部门店"
        elif parent_store_scope in {"single", "multiple"} and parent_store_names:
            store_scope_text = "和".join(parent_store_names)
        else:
            store_scope_text = ""
        if store_scope_text and store_scope_text not in resolved:
            resolved = f"{store_scope_text}{resolved}"

    parent_window = context.get("window_label")
    if (
        isinstance(parent_window, str)
        and parent_window != "全部历史"
        and parent_window not in resolved
        and (
            current_window == "全部历史"
            or body.startswith(("和", "与", "跟", "比"))
        )
    ):
        resolved = f"{parent_window}{resolved}"
    if resolved != current:
        return resolved, True

    # Legacy sessions written before query-plan v2 have no typed context.
    # Keep a narrow compatibility fallback while all new turns write context.
    if parent_code == "RESTAURANT_OPS_GROSS_MARGIN":
        parent_answer = str(parent.get("parent_answer_summary") or "")
        top_dish_match = re.search(r"(?m)^1\.\s+\*\*([^*\r\n]{1,80})\*\*", parent_answer)
        if top_dish_match:
            top_dish = top_dish_match.group(1).strip()
            resolved = re.sub(
                r"^(?:那|这个|那个)?(?:它|这道菜|那个菜|这个菜|"
                r"第[一二三四五六七八九十百千万两\d]{1,8}名)(?:的)?",
                f"{top_dish}的",
                current,
                count=1,
            )
            if resolved != current:
                return resolved, True
    # Without trusted slots there is nothing safe to inherit.  Let the current
    # turn be planned on its own; ambiguity becomes a clarification instead of
    # silently carrying the previous resolver.
    return current, False


_DEFAULT_METRICS_BY_CODE: Dict[str, Tuple[str, ...]] = {
    "RESTAURANT_OPS_WASTAGE_TOP": ("wastage_qty", "wastage_cost"),
    "RESTAURANT_OPS_STOCK_SHORTAGE": ("shortage_qty",),
    "RESTAURANT_OPS_RECIPE_COST": ("food_cost",),
    "RESTAURANT_OPS_REQUISITION_TREND": ("requisition_qty", "requisition_cost"),
    "RESTAURANT_OPS_GROSS_MARGIN": ("gross_profit", "gross_margin"),
    "RESTAURANT_OPS_STORE_MARGIN": ("gross_profit", "gross_margin"),
    "RESTAURANT_OPS_SALES_SUMMARY": ("revenue", "orders", "avg_ticket"),
    "RESTAURANT_OPS_TREND_ANALYSIS": ("revenue",),
    "RESTAURANT_OPS_INVENTORY_WARNING": ("stock_qty", "shortage_count"),
    "RESTAURANT_OPS_STAFFING_ADVICE": ("staff_efficiency",),
}

_CONTRACT_REPAIRABLE_METRICS = frozenset({
    "recipe_cost",
    "wastage",
    "sales_volume",
    "gross_margin",
    "revenue",
    "orders",
    "staffing",
})


def _default_metrics_for_code(code: str, wants_margin: bool) -> Tuple[str, ...]:
    metrics = list(_DEFAULT_METRICS_BY_CODE.get(code, ()))
    if wants_margin and "gross_margin" not in metrics:
        metrics.append("gross_margin")
    return tuple(metrics)


def _seal_query_plan(spec: RestaurantQuerySpec) -> RestaurantQuerySpec:
    """Attach a stable digest to the exact semantics resolvers must execute."""
    start, end = spec.date_range
    comparison_start, comparison_end = spec.comparison_range
    payload = {
        "version": spec.plan_version,
        "intent": spec.intent,
        "domain": spec.domain,
        "window": [
            start.isoformat() if hasattr(start, "isoformat") else start,
            end.isoformat() if hasattr(end, "isoformat") else end,
        ],
        "window_label": spec.window_label,
        "metrics": list(spec.metrics),
        "requested_metrics": list(spec.requested_metrics),
        "dimensions": list(spec.dimensions),
        "comparison": spec.comparison,
        "comparison_window": [
            (
                comparison_start.isoformat()
                if hasattr(comparison_start, "isoformat")
                else comparison_start
            ),
            (
                comparison_end.isoformat()
                if hasattr(comparison_end, "isoformat")
                else comparison_end
            ),
        ],
        "comparison_label": spec.comparison_label,
        "analysis_action": spec.analysis_action,
        "ranking_direction": spec.ranking_direction,
        "ranking_limit": spec.ranking_limit,
        "excluded_entities": list(spec.excluded_entities),
        "store_scope": spec.store_scope,
        "store_slots": list(spec.store_slots),
        "compare_stores": spec.compare_stores,
        "store_options": list(spec.store_options),
        "planned_intents": list(spec.planned_intents),
        "dish_slot": spec.dish_slot,
        "store_slot": spec.store_slot,
        "resolver_query_seed": spec.resolver_query_seed,
    }
    digest = hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        ).encode("utf-8")
    ).hexdigest()
    return replace(spec, plan_hash=digest)


def _build_spec(
    code: str,
    query: str,
    *,
    confidence: float,
    tier: str,
    clarification_needed: bool = False,
    clarification_question: Optional[str] = None,
    time_phrase: str = "",
    llm_wants_margin: bool = False,
    llm_asks_profitability: bool = False,
    is_continuation: bool = False,
    llm_dish: Optional[str] = None,
    llm_store: Optional[str] = None,
    planner_authority: Optional[str] = None,
    require_explicit_time: bool = False,
) -> RestaurantQuerySpec:
    """Compose the final QuerySpec: deterministic slots ALWAYS recomputed
    fresh against `query` + today's date, regardless of which tier picked
    `code` or whether that tier's decision came from cache. This is what
    keeps a cached "最近两个月" routing decision from serving yesterday's
    date window (principle 1 in the module docstring).

    `time_phrase` / `llm_wants_margin` / `llm_asks_profitability` are the T3
    slot SUPPLEMENTS (spec section 1.3 principle 2: the LLM fills only what
    the deterministic layer could not parse). They are additive-only — the
    deterministic detectors stay authoritative when they fire, and the
    supplements ride the routing cache so a cache hit rebuilds the exact
    same spec as the original T3 parse (dates still recomputed fresh)."""
    # T3 time text only supplements wording the deterministic parser cannot
    # already resolve.  Continuations such as "原问题 本月" already contain
    # the clicked option; appending the same label again would make the sealed
    # resolver seed drift from the actual two-turn conversation.
    effective_query = query
    if time_phrase and _resolve_sales_date_range(query)[1] == "全部历史":
        effective_query = f"{query} {time_phrase}".strip()
    sales_spec = _resolve_sales_query_spec(effective_query)
    date_range, window_label = sales_spec.date_range, sales_spec.window_label
    requested_metrics = _detect_requested_metrics(effective_query)
    wants_margin, asks_profitability = _profit_intent(effective_query)
    # T3 profit booleans are supplements only when deterministic metric parsing
    # found no objective. Letting them survive an explicit sales/cost/etc.
    # metric creates a contradictory sealed plan: requested_metrics says
    # sales_volume while build_resolver_query appends "毛利", so the resolver
    # formats a margin recommendation for a sales follow-up.
    allow_llm_profit_supplement = not requested_metrics
    asks_profitability = asks_profitability or (
        allow_llm_profit_supplement and llm_asks_profitability
    )
    wants_margin = (
        wants_margin
        or asks_profitability
        or (allow_llm_profit_supplement and llm_wants_margin)
    )
    relative_window = _uses_relative_sales_window(effective_query)
    deterministic_dish = extract_dish_candidate(effective_query)
    deterministic_store = extract_store_mention(effective_query)
    store_scope, store_slots = _detect_store_scope(effective_query)
    ranking_direction = _detect_ranking_direction(effective_query)
    dimension_list = list(_detect_dimensions(effective_query))
    if (llm_store or deterministic_store) and "store" not in dimension_list:
        dimension_list.append("store")
    if (llm_dish or deterministic_dish) and "dish" not in dimension_list:
        dimension_list.append("dish")
    # "全部门店" is an aggregation scope, not a request to group the answer by
    # store.  Keeping it as a dimension makes otherwise-correct all-store
    # sales/margin plans fail the immutable resolver-capability check.  Only
    # explicit store-grain wording ("各门店"/"哪家店"/"按门店"...) keeps it.
    if (
        store_scope == "all"
        and "store" in dimension_list
        and not _asks_store_breakdown(effective_query)
    ):
        dimension_list.remove("store")
    dimensions = tuple(dimension_list)
    comparison = sales_spec.comparison_kind or _detect_comparison(effective_query)
    requested_ranking_limit = ranking_limit(effective_query)
    excluded_entities = tuple(ranking_exclusions(effective_query))
    planned_intents = _plan_requested_intents(
        effective_query,
        code,
        requested_metrics,
        dimensions,
        store_scope,
    )
    unsupported_requirements = tuple(
        requirement
        for requirement in requested_metrics
        if requirement in _UNSUPPORTED_REQUIREMENTS
    )
    supported_requested_metrics = tuple(
        requirement
        for requirement in requested_metrics
        if requirement not in _UNSUPPORTED_REQUIREMENTS
    )
    analysis_action = _detect_analysis_action(effective_query)
    effective_planner_authority = (
        planner_authority
        or ("llm" if tier == "llm" else "deterministic_guard")
    )
    if (
        code
        and len(planned_intents) == 1
        and code not in planned_intents
        and supported_requested_metrics
        and all(
            metric in _CONTRACT_REPAIRABLE_METRICS
            for metric in supported_requested_metrics
        )
    ):
        # The LLM remains the semantic entry point, but its raw resolver label
        # is not executable when it contradicts the metric/object slots in the
        # user's own wording.  A single compatible resolver is a deterministic
        # compilation result, not a neighbouring guess.  Multiple compatible
        # resolvers remain fail-closed below.
        repaired_code = planned_intents[0]
        logger.warning(
            "[restaurant-intent] contract-repair resolver %s -> %s "
            "metrics=%s dimensions=%s",
            code,
            repaired_code,
            requested_metrics,
            dimensions,
        )
        code = repaired_code
        # The same contradictory LLM response can also set a generic
        # clarification flag. Once explicit metric/object slots compile to
        # exactly one supported resolver, that clarification is no longer
        # truthful and must not keep the valid plan from executing.
        clarification_needed = False
        clarification_question = None
        effective_planner_authority = (
            f"{effective_planner_authority}_contract_repair"
        )
    metrics = _default_metrics_for_code(code, wants_margin) if code else ()
    asks_priority = any(token in effective_query for token in (
        "优先级", "优先", "先查", "先做", "首先", "哪项先", "先看哪",
    ))
    asks_prohibited_actions = any(token in effective_query for token in (
        "先不要做", "不要做", "先别做", "不该做", "避免做", "暂时别",
    ))
    asks_export = any(token in effective_query for token in (
        "导出", "下载", "生成文件", "可导出的字段",
    ))
    if (
        unsupported_requirements
        and not supported_requested_metrics
        and not clarification_needed
    ):
        clarification_needed = True
        clarification_question = _unsupported_requirement_question(
            unsupported_requirements,
            requested_metrics,
        )
    if (
        code
        and planned_intents
        and code not in planned_intents
        and not clarification_needed
    ):
        clarification_needed = True
        clarification_question = (
            "我识别到的问题对象与准备执行的分析范围不一致。"
            "请明确要看菜品、门店还是全店汇总，我不会用相邻指标替代。"
        )
    if (
        require_explicit_time
        and code in _TIME_SCOPED_INTENTS
        and _resolve_sales_date_range(query)[1] == "全部历史"
        and not clarification_needed
    ):
        # LLM time_range is an extraction supplement, never permission to
        # invent a default window.  Only a time phrase in the user's effective
        # query (including a trusted inherited window) authorizes execution.
        clarification_needed = True
        clarification_question = TIME_CLARIFICATION_QUESTION
    if planner_authority in {
        "tenant_gate_unavailable",
        "llm_unavailable",
        "explicit_time_override_requires_baseline",
        "explicit_current_time_conflict",
    }:
        # Infrastructure failure and explicit-slot conflict are both sealed
        # decisions not to execute. Keeping a resolver in either plan would
        # make a clarification look executable to downstream code.
        planned_intents = ()

    spec = RestaurantQuerySpec(
        intent=code or "",
        domain="restaurant",
        date_range=date_range,
        window_label=window_label,
        relative_window=relative_window,
        metrics=metrics,
        wants_margin=wants_margin,
        asks_profitability=asks_profitability,
        dimensions=dimensions,
        comparison=comparison,
        confidence=confidence,
        source_tier=tier,
        clarification_needed=clarification_needed,
        clarification_question=clarification_question,
        comparison_range=sales_spec.comparison_range,
        comparison_label=sales_spec.comparison_label,
        is_clarification_continuation=is_continuation,
        requested_metrics=requested_metrics,
        planned_intents=planned_intents,
        unsupported_requirements=unsupported_requirements,
        asks_priority=asks_priority,
        asks_prohibited_actions=asks_prohibited_actions,
        asks_export=asks_export,
        analysis_action=analysis_action,
        ranking_direction=ranking_direction,
        ranking_limit=requested_ranking_limit,
        excluded_entities=excluded_entities,
        store_scope=store_scope,
        store_slots=store_slots,
        compare_stores=(store_scope == "multiple"),
        dish_slot=llm_dish or deterministic_dish,
        store_slot=llm_store or deterministic_store,
        plan_version="restaurant-query-plan-v2",
        planner_authority=effective_planner_authority,
        resolver_query_seed=effective_query,
    )
    return _seal_query_plan(spec)


# ─── Routing-decision cache ───────────────────────────────────────────────
# NOT the full spec -- date_range/window_label are always recomputed fresh in
# `_build_spec` above. The cached payload is the routing DECISION plus the T3
# slot supplements (time_phrase / llm profit booleans): all date-agnostic
# (time_phrase is a relative phrase like "最近2个月", never concrete dates),
# so a cache hit rebuilds the exact same spec the original parse produced,
# resolved against *today*. Caching the resolved date window would NOT be
# safe (see docstring principle 1). Simple size-capped OrderedDict (manual
# LRU) -- no external dependency, no TTL needed.
_ROUTE_CACHE: "OrderedDict[Tuple[str, str], Dict[str, Any]]" = OrderedDict()
_ROUTE_CACHE_MAX = 500


def _cache_get(factory_id: str, norm_query: str):
    key = (factory_id, norm_query)
    hit = _ROUTE_CACHE.get(key)
    if hit is not None:
        _ROUTE_CACHE.move_to_end(key)
    return hit


def _cache_put(factory_id: str, norm_query: str, value) -> None:
    key = (factory_id, norm_query)
    _ROUTE_CACHE[key] = value
    _ROUTE_CACHE.move_to_end(key)
    while len(_ROUTE_CACHE) > _ROUTE_CACHE_MAX:
        _ROUTE_CACHE.popitem(last=False)


def clear_route_cache() -> None:
    """Test-only helper: reset the in-process routing cache."""
    _ROUTE_CACHE.clear()


def _normalize_query(query: str) -> str:
    return (query or "").strip()


def _normalize_exact_phrase(query: str) -> str:
    """Conservative canonical form used only for whole-sentence equality."""
    compact = re.sub(r"\s+", "", (query or "").strip()).lower()
    return compact.rstrip("。.!！?？")


def _approved_exact_shape(
    query: str,
) -> Optional[Tuple[str, bool, bool]]:
    """Match a finite approved sentence shape, never a substring.

    Besides the bare reviewed phrase, this permits only an approved time slot
    and/or the exact all-store slot around that phrase. The entire normalized
    query must equal one of those finite compositions.
    """
    normalized = _normalize_exact_phrase(query)
    matches = set()
    for phrase, code in _APPROVED_EXACT_ROUTES.items():
        if code not in _VALID_CODES:
            continue
        base = _normalize_exact_phrase(phrase)
        if normalized == base:
            matches.add((code, False, False))
        for store_answer in _APPROVED_ALL_STORE_ANSWERS:
            store = _normalize_exact_phrase(store_answer)
            if normalized in {store + base, base + store}:
                matches.add((code, False, True))
        for time_phrase in _APPROVED_DIRECT_TIME_PHRASES:
            window = _normalize_exact_phrase(time_phrase)
            if normalized in {window + base, base + window}:
                matches.add((code, True, False))
            for store_answer in _APPROVED_ALL_STORE_ANSWERS:
                store = _normalize_exact_phrase(store_answer)
                if normalized in {
                    window + store + base,
                    window + base + store,
                    base + window + store,
                    base + store + window,
                    store + window + base,
                    store + base + window,
                }:
                    matches.add((code, True, True))
    return next(iter(matches)) if len(matches) == 1 else None


def _approved_exact_route(query: str) -> Optional[str]:
    """Return the resolver for one finite approved whole-sentence shape."""
    matched = _approved_exact_shape(query)
    return matched[0] if matched is not None else None


_EXPLICIT_RANKING_NEGATION_TOKENS = (
    "不是", "并非", "不想", "不要", "别查", "别看", "不查", "不看",
    "不问", "取消", "而是", "改问", "换个问题",
)
_READ_ONLY_RANKING_MUTATION_TOKENS = (
    "下架", "停售",
)


def _is_read_only_ranking_action_seed(text: str) -> bool:
    """Recognize a fully slotted dish-ranking request that asked for a write."""
    candidate = (text or "").strip()
    metrics = _detect_requested_metrics(candidate)
    start_date, end_date = _resolve_sales_date_range(candidate)[0]
    return bool(
        any(token in candidate for token in _READ_ONLY_RANKING_MUTATION_TOKENS)
        and any(token in candidate for token in ("道菜", "菜品", "哪个菜"))
        and metrics == ("sales_volume",)
        and _detect_ranking_direction(candidate) in {"best", "worst"}
        and start_date is not None
        and end_date is not None
    )


def _requests_read_only_ranking(text: str) -> bool:
    """Allow only an explicit choice to view/query the ranking, never execute."""
    normalized = _normalize_exact_phrase(text)
    if (
        not normalized
        or any(
            token in normalized
            for token in _READ_ONLY_RANKING_MUTATION_TOKENS
        )
    ):
        return False
    if normalized in {
        "查看排行",
        "只看排行",
        "仅看排行",
        "查询排行",
        "只查看排行",
    }:
        return True
    return bool(
        re.search(r"(?:查看|只看|仅看|查询)", normalized)
        and re.search(r"(?:低销量|销量最低|倒数|排行|排名|榜)", normalized)
    )


def _contains_read_only_ranking_choice(text: str) -> bool:
    """Find a prior explicit choice in the space-delimited continuation log."""
    if _requests_read_only_ranking(text):
        return True
    return any(
        _requests_read_only_ranking(turn)
        for turn in re.split(r"\s+", (text or "").strip())
        if turn
    )


def _latest_read_only_ranking_choice(text: str) -> str:
    """Return the newest explicit READ-ranking clause from a turn log.

    Pending clarification rows join turns with one space. Resolving the
    whole joined string gives old windows priority (for example ``最近7天``
    before a later ``只看本月``), so the newest READ clause is parsed first.
    """
    matches = list(re.finditer(r"(?:查看|只看|仅看|查询)", text or ""))
    return (text or "")[matches[-1].start():].strip() if matches else ""


def _read_choice_replaces_dish_sales(text: str) -> bool:
    """Whether a READ choice explicitly replaces the retained object/metric."""
    if not _contains_read_only_ranking_choice(text):
        return False
    metrics = _detect_requested_metrics(text)
    if metrics and metrics != ("sales_volume",):
        return True
    return bool(
        (
            (
                _asks_store_breakdown(text)
                or "门店" in text
            )
            and _detect_store_scope(text)[0] != "all"
            and not any(token in text for token in ("菜", "菜品"))
        )
        or any(token in text for token in ("食材", "原料", "员工", "人员"))
    )


def _explicit_read_only_action_ranking_spec(
    original_query: str,
    answer: str,
) -> Optional[RestaurantQuerySpec]:
    """Turn an explicit READ choice into a sealed dish-ranking QueryPlan.

    The write verb is used only to prove why the system asked the user to
    choose between viewing and executing.  It is never copied into the
    resolver seed. Every read-side slot is reconstructed independently and
    checked again before this deterministic continuation may execute.
    """
    history_text = f"{original_query} {answer}".strip()
    if (
        not _is_read_only_ranking_action_seed(history_text)
        or not (
            _contains_read_only_ranking_choice(original_query)
            or _contains_read_only_ranking_choice(answer)
        )
    ):
        return None

    latest_choice = _latest_read_only_ranking_choice(original_query)
    if any(
        _read_choice_replaces_dish_sales(choice)
        for choice in (answer, latest_choice)
        if choice
    ):
        # A newly named object is a semantic replacement, not a harmless
        # confirmation of the retained dish slot. Let the normal planner
        # decide that new request instead of silently forcing it back to dishes.
        return None

    current_range, current_window = _resolve_sales_date_range(answer)
    choice_range, choice_window = _resolve_sales_date_range(
        latest_choice
    )
    history_range, history_window = _resolve_sales_date_range(history_text)
    if current_window != "全部历史":
        date_range, window_label = current_range, current_window
    elif choice_window != "全部历史":
        date_range, window_label = choice_range, choice_window
    else:
        date_range, window_label = history_range, history_window
    if any(value is None for value in date_range) or window_label == "全部历史":
        return None

    current_scope, current_stores = _detect_store_scope(answer)
    history_scope, history_stores = _detect_store_scope(history_text)
    store_scope = current_scope or history_scope
    store_slots = current_stores if current_scope else history_stores
    if store_scope == "all":
        scope_text = "全部门店"
    elif store_scope in {"single", "multiple"} and store_slots:
        scope_text = "和".join(store_slots)
    else:
        scope_text = ""

    direction = (
        _detect_ranking_direction(answer)
        or _detect_ranking_direction(history_text)
    )
    if direction not in {"best", "worst"}:
        return None
    direction_text = "销量最高" if direction == "best" else "销量最低"
    limit = ranking_limit(answer, ranking_limit(history_text, 5))
    exclusions = tuple(ranking_exclusions(history_text))
    exclusion_text = (
        f"，排除{'、'.join(exclusions)}"
        if exclusions
        else ""
    )
    resolver_seed = (
        f"{window_label}{scope_text}{direction_text}的{limit}道菜"
        f"{exclusion_text}"
    )
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        resolver_seed,
        confidence=1.0,
        tier="explicit_action_read_choice",
        planner_authority="explicit_action_read_choice",
        is_continuation=True,
        require_explicit_time=True,
    )
    if (
        spec.clarification_needed
        or spec.requested_metrics != ("sales_volume",)
        or spec.ranking_direction != direction
        or spec.ranking_limit != limit
        or "dish" not in spec.dimensions
        or spec.store_scope != store_scope
        or spec.store_slots != store_slots
        or not spec.planned_intents
        or not set(spec.planned_intents).issubset({
            "RESTAURANT_OPS_GROSS_MARGIN",
            "RESTAURANT_OPS_STORE_MARGIN",
        })
        or spec.unsupported_requirements
        or spec.analysis_action != "lookup"
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
        or any(
            token in spec.resolver_query_seed
            for token in _READ_ONLY_RANKING_MUTATION_TOKENS
        )
    ):
        return None
    return spec


def _is_pure_store_scope_answer(answer: str) -> bool:
    """Whether a clarification reply contains only one approved store scope."""
    text = (answer or "").strip()
    normalized = _normalize_exact_phrase(text)
    if not normalized:
        return False
    if any(
        normalized == _normalize_exact_phrase(token)
        for token in _ALL_STORE_SCOPE_TOKENS
    ):
        return True
    mentions = extract_store_mentions(text)
    if not mentions:
        return False
    remainder = text
    for mention in mentions:
        remainder = remainder.replace(mention, "", 1)
    remainder = re.sub(r"[\s和与跟、，,及以及]+", "", remainder)
    return not remainder


def _sales_metric_phrase(query: str) -> str:
    """Render only supported sales metrics back into a clarification seed."""
    labels = {
        "revenue": "营业额",
        "orders": "订单量",
    }
    return "和".join(
        labels[metric]
        for metric in _detect_requested_metrics(query)
        if metric in labels
    )


def _explicit_sales_period_comparison_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile a fully specified, read-only sales-period comparison.

    This is a slot compiler, not a keyword router.  It grants execution only
    when the user explicitly names a supported sales metric, both sides of a
    known non-overlapping period pair, and a comparison direction.  Store scope
    may be absent here because the existing multi-store guard will ask for it;
    once the user selects a store scope, the original question plus that answer
    is compiled again without allowing T3 to replace the two-period contract.
    """
    text = (query or "").strip()
    if (
        not text
        or any(token in text for token in _EXPLICIT_RANKING_NEGATION_TOKENS)
        or not _is_explicit_sales_period_comparison(text)
    ):
        return None

    sales_spec = _resolve_sales_query_spec(text)
    primary_start, primary_end = sales_spec.date_range
    baseline_start, baseline_end = sales_spec.comparison_range
    requested_metrics = _detect_requested_metrics(text)
    store_scope, _ = _detect_store_scope(text)
    if (
        primary_start is None
        or primary_end is None
        or baseline_start is None
        or baseline_end is None
        or not sales_spec.comparison_label
        or not requested_metrics
        or not set(requested_metrics).issubset({"revenue", "orders"})
        or extract_dish_candidate(text)
        or _detect_analysis_action(text) != "compare"
    ):
        return None

    selected_code = (
        "RESTAURANT_OPS_STORE_MARGIN"
        if store_scope in {"single", "multiple"}
        else "RESTAURANT_OPS_SALES_SUMMARY"
    )
    spec = _build_spec(
        selected_code,
        text,
        confidence=1.0,
        tier="explicit_comparison_slots",
        planner_authority="explicit_comparison_slots",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    expected_planned = (selected_code,)
    if (
        spec.clarification_needed
        or spec.analysis_action != "compare"
        or spec.date_range != sales_spec.date_range
        or spec.comparison_range != sales_spec.comparison_range
        or spec.comparison_label != sales_spec.comparison_label
        or spec.comparison != sales_spec.comparison_kind
        or spec.requested_metrics != requested_metrics
        or spec.planned_intents != expected_planned
        or spec.intent != selected_code
        or not set(spec.dimensions).issubset({"store"})
        or spec.dish_slot
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


def _explicit_store_dish_ranking_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile or clarify a structured, read-only scoped dish ranking.

    This is deliberately narrower than a keyword route.  It grants execution
    only when every resolver-facing semantic slot is independently present:
    the dish-sales metric and a best/worst ranking direction. Store scope may
    be an explicit all-store scope, one or more concrete store names, or the
    one remaining slot to clarify after time. When time is missing it
    deterministically returns the existing time clarification first instead
    of allowing T3 to invent a default 30-day window; the normal tenant-aware
    store-scope guard then asks for all/single/multiple stores. Any extra
    metric, named dish, comparison, diagnosis, optimisation, export, or
    negated ranking phrase remains LLM-authorised and fail-closed.

    The live failure that motivated this guard supplied two exact store names,
    ``最近7天`` and ``哪个菜卖得好``. Sending that already-complete plan to T3
    made a model-pool outage block a deterministic read-only query.
    """
    text = (query or "").strip()
    if not text or any(token in text for token in _EXPLICIT_RANKING_NEGATION_TOKENS):
        return None

    store_scope, store_slots = _detect_store_scope(text)
    start_date, end_date = _resolve_sales_date_range(text)[0]
    missing_time = start_date is None and end_date is None
    requested_metrics = _detect_requested_metrics(text)
    if (
        store_scope not in {None, "all", "single", "multiple"}
        or (store_scope is None and store_slots)
        or (
            store_scope is None
            and not any(token in text for token in ("销量", "销售量"))
        )
        or (store_scope == "all" and store_slots)
        or (store_scope == "single" and len(store_slots) != 1)
        or (store_scope == "multiple" and len(store_slots) < 2)
        or ((start_date is None) != (end_date is None))
        or _detect_ranking_direction(text) not in {"best", "worst"}
        or requested_metrics != ("sales_volume",)
        or extract_dish_candidate(text)
        or _detect_analysis_action(text) != "lookup"
        or _detect_comparison(text) is not None
    ):
        return None

    selected_code = (
        "RESTAURANT_OPS_STORE_MARGIN"
        if store_scope in {"single", "multiple"}
        else "RESTAURANT_OPS_GROSS_MARGIN"
    )
    expected_dimensions = (
        {"store", "dish"}
        if store_scope in {"single", "multiple"}
        else {"dish"}
    )
    spec = _build_spec(
        selected_code,
        text,
        confidence=1.0,
        tier="explicit_slots",
        planner_authority="explicit_slots",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    if (
        (
            missing_time
            and (
                not spec.clarification_needed
                or spec.clarification_question != TIME_CLARIFICATION_QUESTION
            )
        )
        or (not missing_time and spec.clarification_needed)
        or spec.store_scope != store_scope
        or spec.store_slots != store_slots
        or set(spec.dimensions) != expected_dimensions
        or spec.requested_metrics != ("sales_volume",)
        or spec.planned_intents != (selected_code,)
        or spec.dish_slot
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


_EXPLICIT_READ_MUTATION_TOKENS = (
    "下架", "上架", "停售", "删除", "停用", "启用",
    "调价", "改价", "涨价", "降价", "创建活动", "发券",
)
_EXPLICIT_NAMED_DISH_METRICS = frozenset({
    "sales_volume",
    "revenue",
    "recipe_cost",
    "gross_margin",
})


def _explicit_named_dish_metric_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile a fully slotted, read-only single-dish metric request.

    A named dish, supported metric set and explicit time are semantic facts,
    not keyword guesses. Concrete stores select the store×dish resolver;
    all-store or not-yet-selected scope selects the all-store dish resolver
    and the normal store-scope guard may still ask the user to choose.
    """
    text = (query or "").strip()
    if (
        not text
        or any(token in text for token in _EXPLICIT_RANKING_NEGATION_TOKENS)
        or any(token in text for token in _EXPLICIT_READ_MUTATION_TOKENS)
        or _detect_comparison(text) is not None
        or _detect_ranking_direction(text) is not None
    ):
        return None

    dish_candidates = extract_dish_candidates(text)
    requested_metrics = _detect_requested_metrics(text)
    start_date, end_date = _resolve_sales_date_range(text)[0]
    missing_time = start_date is None and end_date is None
    store_scope, store_slots = _detect_store_scope(text)
    if (
        len(dish_candidates) != 1
        or not requested_metrics
        or not set(requested_metrics).issubset(_EXPLICIT_NAMED_DISH_METRICS)
        or ((start_date is None) != (end_date is None))
        or store_scope not in {None, "all", "single", "multiple"}
        or (store_scope == "single" and len(store_slots) != 1)
        or (store_scope == "multiple" and len(store_slots) < 2)
        or _detect_analysis_action(text) not in {"lookup", "diagnose", "optimize"}
    ):
        return None

    selected_code = (
        "RESTAURANT_OPS_STORE_MARGIN"
        if store_scope in {"single", "multiple"}
        else "RESTAURANT_OPS_GROSS_MARGIN"
    )
    spec = _build_spec(
        selected_code,
        text,
        confidence=1.0,
        tier="explicit_slots",
        planner_authority="explicit_named_dish_slots",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    if (
        (
            missing_time
            and (
                not spec.clarification_needed
                or spec.clarification_question != TIME_CLARIFICATION_QUESTION
            )
        )
        or (not missing_time and spec.clarification_needed)
        or spec.intent != selected_code
        or spec.dish_slot != dish_candidates[0]
        or spec.requested_metrics != requested_metrics
        or spec.planned_intents != (selected_code,)
        or "dish" not in spec.dimensions
        or not set(spec.dimensions).issubset({"store", "dish"})
        or spec.store_scope != store_scope
        or spec.store_slots != store_slots
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


def _explicit_financial_overview_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile a complete revenue + gross-margin overview without T3.

    A concrete time range, an aggregate store scope, and the two compatible
    financial metrics form a closed read-only request.  The sales-summary
    resolver already owns both values and their shared calculation basis, so
    sending this shape through T3 only adds an avoidable outage path.
    """
    text = (query or "").strip()
    requested_metrics = _detect_requested_metrics(text)
    requested_set = set(requested_metrics)
    start_date, end_date = _resolve_sales_date_range(text)[0]
    store_scope, store_slots = _detect_store_scope(text)
    if (
        not {"gross_margin", "revenue"}.issubset(requested_set)
        or not requested_set.issubset({"gross_margin", "revenue", "orders"})
        or start_date is None
        or end_date is None
        or extract_dish_candidate(text)
        or _detect_analysis_action(text) != "lookup"
        or _detect_comparison(text) is not None
        or _detect_ranking_direction(text) is not None
        or any(token in text for token in _EXPLICIT_READ_MUTATION_TOKENS)
        or store_scope not in {None, "all"}
        or store_slots
    ):
        return None

    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        text,
        confidence=1.0,
        tier="explicit_slots",
        planner_authority="explicit_financial_overview",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    if (
        spec.clarification_needed
        or spec.intent != "RESTAURANT_OPS_SALES_SUMMARY"
        or spec.requested_metrics != requested_metrics
        or spec.planned_intents != ("RESTAURANT_OPS_SALES_SUMMARY",)
        or spec.date_range != (start_date, end_date)
        or spec.store_scope != store_scope
        or spec.store_slots
        or spec.dish_slot
        or not set(spec.dimensions).issubset({"store"})
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


def _explicit_revenue_trend_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile a time-scoped revenue chart/trend request without T3 drift."""
    text = (query or "").strip()
    visual_signal = any(token in text for token in (
        "趋势", "走势", "曲线", "图表", "绘图", "画图", "按日", "每日", "每天",
        "逐日", "二次函数", "二次拟合", "参照线", "计划线", "预警线",
    ))
    requested_metrics = _detect_requested_metrics(text)
    start_date, end_date = _resolve_sales_date_range(text)[0]
    store_scope, store_slots = _detect_store_scope(text)
    if (
        not visual_signal
        or requested_metrics != ("revenue",)
        or start_date is None
        or end_date is None
        or extract_dish_candidate(text)
        or _detect_analysis_action(text) != "lookup"
        or _detect_comparison(text) is not None
        or any(token in text for token in _EXPLICIT_READ_MUTATION_TOKENS)
        or store_scope not in {None, "all"}
        or store_slots
    ):
        return None

    spec = _build_spec(
        "RESTAURANT_OPS_TREND_ANALYSIS",
        text,
        confidence=1.0,
        tier="explicit_slots",
        planner_authority="explicit_revenue_trend",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    if (
        spec.clarification_needed
        or spec.intent != "RESTAURANT_OPS_TREND_ANALYSIS"
        or spec.requested_metrics != ("revenue",)
        or spec.planned_intents != ("RESTAURANT_OPS_TREND_ANALYSIS",)
        or spec.dish_slot
        or not set(spec.dimensions).issubset({"store"})
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
    ):
        return None
    return spec


def _explicit_store_operations_spec(
    query: str,
    *,
    is_continuation: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Compile one concrete store's time-scoped operating overview."""
    text = (query or "").strip()
    asks_overview = any(token in text for token in (
        "经营情况", "经营表现", "经营概况", "经营数据", "生意怎么样",
    ))
    store_scope, store_slots = _detect_store_scope(text)
    start_date, end_date = _resolve_sales_date_range(text)[0]
    if (
        not asks_overview
        or store_scope != "single"
        or len(store_slots) != 1
        or start_date is None
        or end_date is None
        or extract_dish_candidate(text)
        or _detect_analysis_action(text) != "lookup"
        or _detect_comparison(text) is not None
        or any(token in text for token in _EXPLICIT_READ_MUTATION_TOKENS)
    ):
        return None

    spec = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        text,
        confidence=1.0,
        tier="explicit_slots",
        planner_authority="explicit_store_operations",
        is_continuation=is_continuation,
        require_explicit_time=True,
    )
    if (
        spec.clarification_needed
        or spec.intent != "RESTAURANT_OPS_STORE_MARGIN"
        or spec.planned_intents != ("RESTAURANT_OPS_STORE_MARGIN",)
        or spec.store_scope != "single"
        or spec.store_slots != store_slots
        or set(spec.dimensions) != {"store"}
        or spec.dish_slot
        or spec.unsupported_requirements
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


_TRUSTED_CONTEXT_DISH_METRICS = frozenset({
    "sales_volume",
    "revenue",
    "recipe_cost",
    "gross_margin",
})
_TRUSTED_CONTEXT_DISH_INTENTS = frozenset({
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_RECIPE_COST",
    "RESTAURANT_OPS_STORE_MARGIN",
})


def _trusted_context_dish_followup_spec(
    query: str,
) -> Optional[RestaurantQuerySpec]:
    """Compile a narrow read-only plan from server-restored typed context.

    This is not a keyword fast path. The caller must prove the current turn was
    reconstructed from the authenticated chat session. Execution is granted
    only when the rebuilt sentence contains every deterministic slot required
    for a single-dish lookup: explicit date window, explicit store scope,
    explicit dish, and a supported metric set. A diagnosis or optimisation
    may execute only after those typed slots were restored from the trusted
    server-side session; comparison, export, write/action requests,
    unsupported metrics, and any incomplete/ambiguous shape still go to T3
    and fail closed if T3 is down.
    """
    try:
        candidate_code = match_restaurant_ops(query)
    except Exception:
        return None
    # The initial keyword code is only a candidate. A diagnosis such as
    # "为什么销量低" may have no ranking/report keyword at all; typed
    # server-restored dish+metric+time+store slots still compile through the
    # scoped unit-economics resolver. The strict checks below authorize only
    # the final sealed dish plan, never this raw hint.
    if candidate_code is None:
        candidate_code = "RESTAURANT_OPS_GROSS_MARGIN"

    spec = _build_spec(
        candidate_code,
        query,
        confidence=1.0,
        tier="trusted_context",
        planner_authority="trusted_context",
        require_explicit_time=True,
    )
    start_date, end_date = spec.date_range
    metrics = frozenset(spec.requested_metrics)
    if (
        spec.clarification_needed
        or start_date is None
        or end_date is None
        or spec.window_label == "全部历史"
        or spec.store_scope not in {"all", "single", "multiple"}
        or not spec.dish_slot
        or not metrics
        or not metrics.issubset(_TRUSTED_CONTEXT_DISH_METRICS)
        or not spec.planned_intents
        or not set(spec.planned_intents).issubset(_TRUSTED_CONTEXT_DISH_INTENTS)
        or spec.unsupported_requirements
        or spec.analysis_action not in {"lookup", "diagnose", "optimize"}
        or spec.comparison is not None
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        return None
    return spec


def _approved_exact_continuation_route(
    original_query: str,
    answer: str,
    clarification_question: Optional[str],
) -> Optional[str]:
    """Authorize only the fixed buttons attached to an approved exact route.

    The first continuation must be one of the four time buttons. The optional
    second continuation may be the all-store button or exactly one concrete
    store name. Any extra wording, changed metric, or arbitrary instruction
    falls back to the LLM/fail-closed path.
    """
    matched = _approved_exact_shape(original_query)
    if matched is None:
        return None
    matched_code, inherited_time, inherited_store = matched

    answer_normalized = _normalize_exact_phrase(answer)
    if clarification_question == TIME_CLARIFICATION_QUESTION:
        if inherited_time:
            return None
        if answer_normalized in {
            _normalize_exact_phrase(window)
            for window in _APPROVED_TIME_ANSWERS
        }:
            return matched_code
        return None

    if (
        clarification_question != STORE_SCOPE_CLARIFICATION_QUESTION
        or not inherited_time
        or inherited_store
    ):
        return None
    if answer_normalized == _normalize_exact_phrase("全部门店"):
        return matched_code
    store_mentions = extract_store_mentions(answer)
    if (
        len(store_mentions) == 1
        and answer_normalized == _normalize_exact_phrase(store_mentions[0])
    ):
        return matched_code
    return None


# ─── Pending-clarification store (2026-07-08 clarification-loop v1) ──────
# Separate from `_ROUTE_CACHE` above -- this is NOT a routing-decision cache,
# it is a short-lived "what did we just ask this session, and what was the
# original question" memo so the NEXT message from that (factory_id,
# session_key) can be interpreted as an ANSWER instead of a fresh query. See
# module docstring "Clarification continuation" section for the full flow.
#
# STORAGE IS POSTGRES, NOT PROCESS MEMORY (2026-07-08 prod bug fix): prod
# cretas-python runs `uvicorn --workers 2`, so the first version's
# in-process OrderedDict registered the pending entry in worker A while the
# user's follow-up answer landed on worker B -- continuation became a coin
# flip (live-verified: a 3-message conversation re-clarified the answer as a
# brand-new query). The `restaurant_pending_clarifications` table (migration
# V20260708_01__restaurant_pending_clarifications.sql) lives in the same
# smartbi Postgres the `pool` argument already points at (home of
# agg_restaurant_daily_*), shared by every worker.
#
# Consume-once semantics come from a single atomic DELETE ... RETURNING; the
# ~5-minute TTL is judged Python-side on the returned created_at (the row is
# deleted either way, preserving the one-hop cap). No background sweeper: an
# opportunistic bulk delete of rows older than 1 hour rides along on each
# pop (failure ignored) so abandoned entries cannot bloat the table.
#
# The per-worker `_ROUTE_CACHE` / `_RESTAURANT_TENANT_CACHE` above stay
# in-process ON PURPOSE: they are pure performance caches (each worker
# re-warming them independently costs latency, never correctness), whereas
# pending clarifications are conversation STATE.
#
# Fail-open (module principle 6): ANY DB error in put/pop logs a warning and
# degrades to "nothing registered" / "no continuation this time" -- never
# raises into the caller's chain.
_PENDING_TTL_SECONDS = 5 * 60  # ~5 minutes, per clarification-loop v1 design


async def _pending_put(
    pool, factory_id: str, session_key: str, *,
    original_query: str, clarification_question: Optional[str],
) -> None:
    """UPSERT the pending clarification for (factory_id, session_key). A
    newer clarification for the same session overwrites the older one
    (ON CONFLICT), same as the previous in-process dict assignment did."""
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO restaurant_pending_clarifications
                    (factory_id, session_key, original_query, clarification_question, created_at)
                VALUES ($1, $2, $3, $4, now())
                ON CONFLICT (factory_id, session_key) DO UPDATE
                   SET original_query = EXCLUDED.original_query,
                       clarification_question = EXCLUDED.clarification_question,
                       created_at = now()
                """,
                factory_id, session_key, original_query, clarification_question,
            )
    except Exception as exc:
        logger.warning(
            f"[restaurant-intent] pending-clarification put failed (fail-open, not registered): {exc}"
        )


async def _pending_pop(pool, factory_id: str, session_key: str) -> Optional[Dict[str, Any]]:
    """Read-and-remove: a pending entry is consumed the moment it is looked
    at, whether or not the continuation attempt built from it actually
    resolves -- this is what caps continuation at exactly one hop (module
    docstring). The DELETE ... RETURNING is a single atomic statement, so
    two workers racing on the same follow-up can never both continue.

    Returns None (not just "not found") when the entry has aged past
    `_PENDING_TTL_SECONDS`; either way it is gone from the store after this
    call. Any DB error also returns None (fail-open: no continuation this
    time)."""
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                """
                DELETE FROM restaurant_pending_clarifications
                 WHERE factory_id = $1 AND session_key = $2
                 RETURNING original_query, clarification_question, created_at
                """,
                factory_id, session_key,
            )
            # Opportunistic anti-bloat sweep (failure ignored): entries the
            # user never followed up on have no other deletion path.
            try:
                await conn.execute(
                    "DELETE FROM restaurant_pending_clarifications"
                    " WHERE created_at < now() - interval '1 hour'"
                )
            except Exception:
                pass
    except Exception as exc:
        logger.warning(
            f"[restaurant-intent] pending-clarification pop failed (fail-open, no continuation): {exc}"
        )
        return None

    if row is None:
        return None
    created_at = row["created_at"]
    if created_at is not None:
        if created_at.tzinfo is None:
            created_at = created_at.replace(tzinfo=timezone.utc)
        age_seconds = (datetime.now(timezone.utc) - created_at).total_seconds()
        if age_seconds > _PENDING_TTL_SECONDS:
            return None
    return {
        "original_query": row["original_query"],
        "clarification_question": row["clarification_question"],
    }


async def clear_pending_clarifications(pool) -> None:
    """Test/ops helper: remove ALL pending-clarification rows. Fail-open."""
    try:
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM restaurant_pending_clarifications")
    except Exception as exc:
        logger.warning(f"[restaurant-intent] pending-clarification clear failed: {exc}")


async def _maybe_register_pending(
    pool, query: str, spec: Optional[RestaurantQuerySpec], factory_id: str,
    session_key: Optional[str],
) -> None:
    """Register a pending clarification for (factory_id, session_key) when
    `spec` asked one AND the caller opted in with a session_key. No-op
    (including for a falsy/empty session_key -- spec section 1 of the
    2026-07-08 design: "session_key 缺失 → 完全不启用续接") on every other
    path, so this is safe to call unconditionally after any fresh (non-
    continuation) parse outcome."""
    if session_key and spec is not None and spec.clarification_needed:
        await _pending_put(
            pool, factory_id, session_key,
            original_query=query, clarification_question=spec.clarification_question,
        )


_STORE_SCOPE_REQUIRED_METRICS = frozenset({
    "sales_volume", "gross_margin", "revenue", "orders",
})
_STORE_SCOPE_REQUIRED_INTENTS = frozenset({
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
    "RESTAURANT_OPS_SALES_SUMMARY",
})


async def _apply_store_scope_guard(
    pool,
    factory_id: str,
    spec: Optional[RestaurantQuerySpec],
) -> Optional[RestaurantQuerySpec]:
    """Ask multi-store tenants for scope; infer the sole store when unambiguous."""
    if (
        spec is None
        or spec.clarification_needed
        or spec.store_scope
        or not spec.intent
        or (
            spec.intent not in _STORE_SCOPE_REQUIRED_INTENTS
            and not set(spec.requested_metrics).intersection(_STORE_SCOPE_REQUIRED_METRICS)
        )
    ):
        return spec

    data_factory = demo_data_factory_for_code(
        spec.intent,
        factory_id,
        store_scoped=True,
    )
    try:
        async with pool.acquire() as conn:
            # Demo tenants can read from a deterministic data tenant.  The
            # pool setup callback scoped this connection to the login tenant,
            # so querying the mapped factory id without overriding the RLS GUC
            # silently returns zero rows.  Keep the override transaction-local
            # so it cannot leak into the next borrower of this connection.
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)",
                    data_factory,
                )
                rows = await conn.fetch(
                    """
                    SELECT s.name
                      FROM dim_store s
                     WHERE s.factory_id = $1
                     ORDER BY s.name
                     LIMIT 9
                    """,
                    data_factory,
                )
    except Exception as exc:
        logger.warning("[restaurant-intent] store-scope gate unavailable: %s", exc)
        # Scope enrichment must not invalidate an otherwise sealed query plan
        # in test/minimal deployments where dim_store is unavailable. Resolver
        # contracts still fail closed on an explicit unknown store.
        return spec

    names = tuple(
        str(row["name"]).strip()[:80]
        for row in rows
        if row["name"] and str(row["name"]).strip()
    )
    if len(names) == 1:
        return _seal_query_plan(replace(
            spec,
            store_scope="single",
            # This is an inferred tenant fact, not an explicit store filter.
            # Keeping store_slots empty lets all-store SQL read the sole store
            # without changing the immutable resolver dimension.
            store_slots=(),
            store_options=names,
        ))
    if not names:
        # No dimension rows means the tenant cardinality is unknown, not one.
        # Preserve the sealed plan without inventing a single-store fact.
        return spec
    return _seal_query_plan(replace(
        spec,
        clarification_needed=True,
        clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
        store_options=names,
    ))


def build_resolver_query(query: str, spec: RestaurantQuerySpec) -> str:
    """The string chat.py should pass as `query=` into `resolve_by_code`.

    Resolvers (e.g. resolve_sales_summary) re-derive date_range/margin/
    profitability from the raw query text themselves -- they don't accept a
    precomputed RestaurantQuerySpec. For T1 that's fine (the resolver's own
    parse of the raw query IS how `spec` was built). For T2/T3, `spec` may
    have been built from a query augmented with the LLM's structured time
    hint (see `_build_spec` in the T3 branch of `parse_restaurant_query`) --
    so the resolver must see that same augmentation, or it will silently
    re-derive a wider/emptier window than the one already shown to the user.

    The sealed ``resolver_query_seed`` is authoritative when present.  A
    clarification continuation stores ``original_query + button_answer`` in
    that field, so the resolver never receives a context-free token such as
    just "本月".  Legacy specs without a seed retain the caller's raw query.

    Appending `spec.window_label` (itself always valid `_resolve_sales_date_range`
    vocabulary, e.g. "最近2个月"/"今天"/"本周"/"本月") to the original query is
    idempotent for T1/T2 (the window was already derivable from the original
    text) and closes the gap for T3 paraphrases. Original wording is kept
    intact so profit/margin keyword detection (`_profit_intent`) still sees
    the user's actual words.

    Same trick for the T3 profit-slot supplement: when the spec says the user
    asked about profitability/margin but the raw text carries no token the
    resolver's own `_profit_intent` would recognize (LLM-only detection),
    splice a canonical phrase in so the resolver actually produces the
    margin/verdict section instead of relying on the Answer Contract
    disclaimer after the fact.
    """
    resolver_query_seed = spec.resolver_query_seed or query
    parts = [resolver_query_seed]
    if (
        spec.window_label != "全部历史"
        and spec.window_label not in resolver_query_seed
    ):
        parts.append(spec.window_label)
    raw_wants_margin, raw_asks_profit = _profit_intent(resolver_query_seed)
    margin_is_requested = (
        not spec.requested_metrics
        or "gross_margin" in spec.requested_metrics
    )
    if margin_is_requested and spec.asks_profitability and not raw_asks_profit:
        parts.append("赚钱了吗")
    elif margin_is_requested and spec.wants_margin and not raw_wants_margin:
        parts.append("毛利")
    return " ".join(parts)


# ─── Business-type gate (spec section 3.4) ────────────────────────────────
# T2/T3 must only run for restaurant tenants -- there is no shared, public,
# per-factory-id business-type helper today (chat.py's _derive_business_type
# is private/module-local and importing it back into this module would set
# up a circular import since chat.py imports restaurant_intent). Per spec's
# explicit fallback, gate on "this factory has data in agg_restaurant_daily_*"
# and cache the (cheap, stable-for-a-tenant's-lifetime) result in-process.
_RESTAURANT_TENANT_CACHE: Dict[str, bool] = {}


async def _is_restaurant_tenant(pool, factory_id: str) -> bool:
    if not factory_id:
        return False
    cached = _RESTAURANT_TENANT_CACHE.get(factory_id)
    if cached is not None:
        return cached
    try:
        async with pool.acquire() as conn:
            # ``agg_restaurant_daily_totals`` is protected by factory RLS.
            # A newly-created asyncpg connection has no tenant context, so
            # querying it directly can return no rows even when this factory
            # has restaurant data.  Caching that false result made the gate
            # worker-dependent in multi-worker Uvicorn: whichever worker saw
            # the context-free lookup first rejected every later request.
            #
            # Bind the trusted factory id locally to this transaction before
            # reading.  ``is_local=true`` guarantees the context cannot leak
            # back into the pool after the transaction ends.
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)",
                    factory_id,
                )
                row = await conn.fetchrow(
                    "SELECT 1 FROM agg_restaurant_daily_totals"
                    " WHERE factory_id = $1 LIMIT 1",
                    factory_id,
                )
    except Exception as exc:
        logger.warning(
            f"[restaurant-intent] tenant gate lookup failed for {factory_id} (not cached): {exc}"
        )
        # An unavailable tenant gate is not evidence that this is a
        # non-restaurant tenant.  Propagate the error so the caller can return
        # an explicit non-executing clarification instead of falling through
        # to another router.
        raise
    is_restaurant = row is not None
    _RESTAURANT_TENANT_CACHE[factory_id] = is_restaurant
    return is_restaurant


def clear_tenant_gate_cache() -> None:
    """Test-only helper: reset the in-process tenant gate cache."""
    _RESTAURANT_TENANT_CACHE.clear()


# ─── T2 vector tier ─────────────────────────────────────────────────────

_T2_HIGH_CONFIDENCE = 0.78   # mirrors template_rag.HIGH_CONFIDENCE
_T2_MIN_USEFUL = 0.70        # mirrors template_rag.MIN_USEFUL


async def _t2_vector_match(pool, query: str) -> Tuple[Optional[str], float, Optional[Tuple[str, float]]]:
    """Return (code_if_high_confidence, similarity, hint_for_t3).

    hint_for_t3 is (code, similarity) for a 0.70-0.78 candidate to pass into
    the T3 prompt, or None. code is None when nothing clears
    _T2_HIGH_CONFIDENCE (either because it's a hint-only match or no match at
    all).
    """
    try:
        from smartbi.services.template_embedding_index import cosine_topk
        candidates = await cosine_topk(
            pool, query, k=3, min_similarity=_T2_MIN_USEFUL,
            code_prefix="RESTAURANT_OPS_",
        )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T2 vector match failed: {exc}")
        return None, 0.0, None

    if not candidates:
        return None, 0.0, None

    top_code, top_sim, _sample = candidates[0]
    if top_code not in _VALID_CODES:
        return None, 0.0, None
    if top_sim >= _T2_HIGH_CONFIDENCE:
        return top_code, top_sim, None
    return None, top_sim, (top_code, top_sim)


# ─── T3 LLM tier ────────────────────────────────────────────────────────

# Java's tiered endpoint has a 10 s wall-clock deadline. Keep the complete
# provider cascade below that deadline, including vector/DB/serialization
# overhead. Six seconds leave room for at most two full 2.5 s slow candidates
# plus fast quota/refusal fall-throughs; a long mapper tail must not consume
# the entire interactive request after deterministic tiers have missed.
_T3_PROVIDER_TIMEOUT_SECONDS = 2.5
_T3_TOTAL_TIMEOUT_SECONDS = 6.0
_T3_MIN_CONFIDENCE = 0.6


def _build_t3_prompt(query: str, hint: Optional[Tuple[str, float]], history: Optional[Sequence[Dict[str, str]]]) -> str:
    intent_lines = "\n".join(
        f'  - "{code}": {desc}' for code, desc in _INTENT_DESCRIPTIONS.items()
    )
    hint_line = ""
    if hint:
        hint_code, hint_sim = hint
        hint_line = (
            f"\n候选召回提示: \"{hint_code}\" (候选分 {hint_sim:.2f})。"
            "这不是路由结论；你必须结合问题原文独立判断，冲突时以原文为准。\n"
        )
    # 2026-07-08 clarification-loop v1: when a continuation attempt (see
    # module docstring) passes the ORIGINAL question + the clarification
    # question we asked, render it as a two-turn block so the LLM combines
    # both instead of parsing the (often incomplete on its own, e.g. just
    # "最近两个月") current message in isolation. `history` is None for every
    # ordinary (non-continuation) T3 call -- history_line stays "" and the
    # rest of the prompt is byte-identical to before this feature existed.
    history_line = ""
    if history:
        turns = []
        for turn in history:
            role = turn.get("role") if isinstance(turn, dict) else None
            content = (turn.get("content") if isinstance(turn, dict) else None) or ""
            label = "你(追问)" if role == "assistant" else "用户"
            turns.append(f"{label}: {content}")
        history_line = (
            "\n上一轮对话 (用户当前这条消息是在回答你上一轮提出的澄清问题，"
            "请结合上一轮的原始问题和这次的回答来判断完整意图，不要只看当前这一句):\n"
            + "\n".join(turns) + "\n"
        )
    few_shot = (
        '示例1: "这两个月生意咋样，挣着钱没" -> '
        '{"intent": "RESTAURANT_OPS_SALES_SUMMARY", "time_range": {"type": "relative", '
        '"unit": "month", "count": 2}, "wants_margin": true, "asks_profitability": true, '
        '"dimensions": [], "comparison": null, "dish": null, "store": null, '
        '"confidence": 0.9, '
        '"clarification_needed": false, "clarification_question": null}\n'
        '示例3: "帮我看看水煮鱼这道菜最近表现咋样" -> '
        '{"intent": "RESTAURANT_OPS_GROSS_MARGIN", "time_range": {"type": "relative", '
        '"unit": "day", "count": 30}, "wants_margin": true, "asks_profitability": false, '
        '"dimensions": ["dish"], "comparison": null, "dish": "水煮鱼", "store": null, '
        '"confidence": 0.85, '
        '"clarification_needed": false, "clarification_question": null}\n'
        '示例2: "情况怎么样" (完全没有可判断的对象/指标) -> '
        '{"intent": null, "time_range": null, "wants_margin": false, '
        '"asks_profitability": false, "dimensions": [], "comparison": null, '
        '"confidence": 0.2, "clarification_needed": true, '
        '"clarification_question": "您想了解营收、毛利、损耗还是库存盘点的情况？"}\n'
    )
    # 段落顺序是 DashScope 隐式前缀缓存的契约 (2026-07-23 重排): 指令+意图
    # 目录+严格规则+few-shot 全部是静态块, 必须排在最前 — 每次 T3 调用共享
    # ~1.2k token 前缀, 缓存命中部分按 2 折计费。hint/history/query 随请求
    # 变化, 只能出现在静态块之后。不要往静态块之间插任何 per-query 内容。
    return (
        "你是餐饮老板问答系统的意图解析器。将用户问题解析为一个 JSON 对象，不要输出任何其他文字。\n"
        "可选 intent 取值（必须从下面列表中选择一个，或者在无法判断时输出 null）：\n"
        f"{intent_lines}\n"
        "严格规则:\n"
        "1. 你绝对不能计算或输出具体日期！time_range 只能是结构化描述，例如: "
        '{"type": "relative", "unit": "month", "count": 2} (最近2个月), '
        '{"type": "relative", "unit": "day", "count": 10} (最近10天), '
        '{"type": "named", "value": "today"|"this_week"|"this_month"}, '
        '{"type": "all_history"}, 或 null (未提及时间)。真实日期由确定性代码计算，不是你的工作。\n'
        "2. confidence 是你对 intent 判断的把握程度 (0.0-1.0)。\n"
        "2b. dish/store: 如果问题点名了具体菜品或门店，原样摘抄那个名字 "
        "(必须是问题原文里连续出现的子串，绝不改写、翻译或补全)；没点名就输出 null。"
        "泛指词 (这道菜/哪家店/门店) 不是名字，输出 null。\n"
        "3. 如果问题太模糊无法判断 intent，输出 intent:null 且 clarification_needed:true，"
        "并给出一个具体的澄清问题(clarification_question)。\n"
        "4. 只输出 JSON，不要 markdown 代码块，不要解释。\n\n"
        f"{few_shot}"
        f"{hint_line}"
        f"{history_line}\n"
        f'用户问题: "{query}"\n'
        "JSON:"
    )


def _parse_t3_time_range(time_range: Any) -> str:
    """Turn the LLM's structured time_range object back into a phrase that
    `_resolve_sales_date_range` (the SAME deterministic parser T1/T2 use) can
    understand -- so real date computation never touches the LLM's output
    directly. Returns "" (no time phrase) on anything unexpected (fail-open)."""
    if not isinstance(time_range, dict):
        return ""
    kind = time_range.get("type")
    if kind == "relative":
        unit = time_range.get("unit")
        count = time_range.get("count")
        unit_cn = {"day": "天", "week": "周", "month": "个月"}.get(unit)
        if unit_cn and isinstance(count, (int, float)) and count > 0:
            return f"最近{int(count)}{unit_cn}"
        return ""
    if kind == "named":
        value = time_range.get("value")
        return {
            "today": "今天",
            "this_week": "本周",
            "this_month": "本月",
        }.get(value, "")
    if kind == "all_history":
        return ""
    return ""


def _verbatim_entity(value: Any, query: str) -> Optional[str]:
    """Accept an LLM entity slot ONLY when it is a verbatim substring of the
    user's question (anti-hallucination: the LLM nominates, it never invents).
    Generic placeholders are rejected — they are not names."""
    if not isinstance(value, str):
        return None
    cand = value.strip().strip("「」\"'")
    if not (2 <= len(cand) <= 40) or cand not in query:
        return None
    if cand in (
        "这道菜", "那道菜", "这个菜", "菜品",
        "哪家店", "哪个店", "哪家门店", "这家店", "那家店",
        "门店", "分店", "店铺", "本店", "单店", "全店",
        "各店", "多家门店", "多家店", "指定门店",
        *_ALL_STORE_SCOPE_TOKENS,
        *_STORE_RANK_SCOPE_TOKENS,
    ):
        return None
    return cand


async def _t3_llm_parse(
    query: str,
    *,
    hint: Optional[Tuple[str, float]],
    history: Optional[Sequence[Dict[str, str]]],
) -> Optional[Dict[str, Any]]:
    """Call the SmartBI LLM router (SLOT.MAPPER: thinking off, json_object,
    temperature 0) to structurally parse `query`. Returns the parsed dict, or
    None on any failure/timeout. The caller converts that into an explicit
    fail-closed, non-executing clarification."""
    try:
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        prompt = _build_t3_prompt(query, hint, history)
        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。",
                },
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": 500,
        }
        with llm_caller_context("restaurant_intent"):
            result = await call_chain(
                SLOT.MAPPER,
                payload,
                timeout=_T3_PROVIDER_TIMEOUT_SECONDS,
                total_timeout=_T3_TOTAL_TIMEOUT_SECONDS,
            )
        content = (result["choices"][0]["message"]["content"] or "").strip()
        if content.startswith("```"):
            content = content.strip("`")
            if content[:4].lower() == "json":
                content = content[4:]
            content = content.strip()
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return None
        return parsed
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T3 LLM parse failed/timed out: {exc}")
        return None


# ─── Public entry point ────────────────────────────────────────────────

async def parse_restaurant_query(
    query: str,
    pool,
    *,
    factory_id: str,
    history: Optional[Sequence[Dict[str, str]]] = None,
    session_key: Optional[str] = None,
    trusted_followup_context: bool = False,
) -> Optional[RestaurantQuerySpec]:
    """Resolve `query` to one immutable RestaurantQuerySpec.

    T1/T2 retrieve candidate hints; only T3, a validated T3-plan cache, a
    reviewed whole-sentence exact promotion, or the narrow typed-context dish
    continuation guard may authorize execution. A planner outage returns a
    fail-closed clarification rather than ``None``, preventing the caller from
    trying an adjacent route. ``None`` is reserved for an empty request or a
    non-restaurant tenant.

    `session_key` (2026-07-08 clarification-loop v1, additive/optional):
    when truthy AND a pending clarification is on record for
    (factory_id, session_key), this call is treated as the user's ANSWER to
    that clarification (see module docstring "Clarification continuation")
    instead of a fresh, context-free query. Falsy/omitted `session_key`, or
    no pending entry, is byte-identical to this function's behavior before
    the feature existed.
    """
    norm_query = _normalize_query(query)
    if not norm_query or not factory_id:
        return None

    if session_key:
        pending = await _pending_pop(pool, factory_id, session_key)
        if pending is not None:
            continued = await _parse_continuation(
                norm_query,
                pool,
                factory_id=factory_id,
                pending=pending,
            )
            already_clarifying = bool(
                continued is not None and continued.clarification_needed
            )
            continued = await _apply_store_scope_guard(pool, factory_id, continued)
            combined_query = (
                f"{pending.get('original_query') or ''} {norm_query}".strip()
            )
            # A dependent named-dish action can consume the original pending
            # clarification while still legitimately needing the same time
            # slot.  Persist the enriched deterministic seed (for example
            # "米饭的销量怎么优化") so the next button answer continues that
            # exact dish/metric/action contract instead of becoming a fresh
            # generic query.  Keep this deliberately narrow: only the trusted
            # named-dish compiler may re-arm a time clarification here.
            if (
                continued is not None
                and continued.clarification_needed
                and continued.is_clarification_continuation
                and continued.planner_authority == "explicit_named_dish_slots"
                and continued.clarification_question == TIME_CLARIFICATION_QUESTION
                and continued.resolver_query_seed
            ):
                await _maybe_register_pending(
                    pool,
                    continued.resolver_query_seed,
                    continued,
                    factory_id,
                    session_key,
                )
            if (
                continued is not None
                and continued.clarification_needed
                and (
                    (
                        not already_clarifying
                        and continued.clarification_question
                        == STORE_SCOPE_CLARIFICATION_QUESTION
                    )
                    or (
                        already_clarifying
                        and _is_read_only_ranking_action_seed(combined_query)
                        and (
                            _is_pure_store_scope_answer(norm_query)
                            or _requests_read_only_ranking(norm_query)
                        )
                    )
                )
            ):
                await _maybe_register_pending(
                    pool,
                    combined_query,
                    continued,
                    factory_id,
                    session_key,
                )
            return continued

    capability_question = capability_clarification_question(norm_query)
    if capability_question:
        spec = _build_spec(
            "",
            norm_query,
            confidence=1.0,
            tier="keyword",
            clarification_needed=True,
            clarification_question=capability_question,
        )
        await _maybe_register_pending(pool, norm_query, spec, factory_id, session_key)
        return spec

    # Capability gaps are query semantics, not an intent-classification task.
    # Detect them before the tenant gate/T2/T3 so a fully scoped question about
    # service time, process nodes, or returns cannot be silently reduced to a
    # nearby sales/margin intent when those source facts are unavailable.
    early_requirements = _detect_requested_metrics(norm_query)
    early_unsupported = tuple(
        requirement
        for requirement in early_requirements
        if requirement in _UNSUPPORTED_REQUIREMENTS
    )
    early_supported = tuple(
        requirement
        for requirement in early_requirements
        if requirement not in _UNSUPPORTED_REQUIREMENTS
    )
    if early_unsupported and not early_supported:
        return _build_spec(
            "",
            norm_query,
            confidence=1.0,
            tier="keyword",
            clarification_needed=True,
            clarification_question=_unsupported_requirement_question(
                early_unsupported,
                early_requirements,
            ),
        )

    optimization_question = optimization_clarification_question(norm_query)
    if optimization_question:
        spec = _build_spec(
            "",
            norm_query,
            confidence=1.0,
            tier="keyword",
            clarification_needed=True,
            clarification_question=optimization_question,
        )
        await _maybe_register_pending(pool, norm_query, spec, factory_id, session_key)
        return spec

    # Natural-language restaurant queries normally require an LLM-produced
    # structured plan. Keyword/vector stages retrieve a candidate hint;
    # neither stage may decide the route. The sole exception is the reviewed
    # whole-sentence exact registry below.
    try:
        if not await _is_restaurant_tenant(pool, factory_id):
            return None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] tenant gate unavailable: {exc}")
        return _build_spec(
            "",
            norm_query,
            confidence=0.0,
            tier="llm",
            planner_authority="tenant_gate_unavailable",
            clarification_needed=True,
            clarification_question=(
                "暂时无法确认餐饮数据范围，本次没有执行任何分析。请稍后重试。"
            ),
        )

    promoted_code = _approved_exact_route(norm_query)
    if promoted_code:
        promoted_spec = _build_spec(
            promoted_code,
            norm_query,
            confidence=1.0,
            tier="exact",
            planner_authority="promoted_exact",
            require_explicit_time=True,
        )
        promoted_spec = await _apply_store_scope_guard(
            pool,
            factory_id,
            promoted_spec,
        )
        await _maybe_register_pending(
            pool,
            norm_query,
            promoted_spec,
            factory_id,
            session_key,
        )
        return promoted_spec

    explicit_ranking_spec = _explicit_store_dish_ranking_spec(norm_query)
    if explicit_ranking_spec is not None:
        explicit_ranking_spec = await _apply_store_scope_guard(
            pool,
            factory_id,
            explicit_ranking_spec,
        )
        await _maybe_register_pending(
            pool,
            norm_query,
            explicit_ranking_spec,
            factory_id,
            session_key,
        )
        return explicit_ranking_spec

    explicit_comparison_spec = _explicit_sales_period_comparison_spec(norm_query)
    if explicit_comparison_spec is not None:
        explicit_comparison_spec = await _apply_store_scope_guard(
            pool,
            factory_id,
            explicit_comparison_spec,
        )
        await _maybe_register_pending(
            pool,
            norm_query,
            explicit_comparison_spec,
            factory_id,
            session_key,
        )
        return explicit_comparison_spec

    if trusted_followup_context:
        trusted_spec = _trusted_context_dish_followup_spec(norm_query)
        if trusted_spec is not None:
            trusted_spec = await _apply_store_scope_guard(
                pool,
                factory_id,
                trusted_spec,
            )
            if not trusted_spec.clarification_needed:
                return trusted_spec

    for explicit_compiler in (
        _explicit_named_dish_metric_spec,
        _explicit_financial_overview_spec,
        _explicit_revenue_trend_spec,
        _explicit_store_operations_spec,
    ):
        explicit_spec = explicit_compiler(norm_query)
        if explicit_spec is None:
            continue
        explicit_spec = await _apply_store_scope_guard(
            pool,
            factory_id,
            explicit_spec,
        )
        await _maybe_register_pending(
            pool,
            norm_query,
            explicit_spec,
            factory_id,
            session_key,
        )
        return explicit_spec

    cached = _cache_get(factory_id, norm_query)
    if (
        cached is not None
        and cached.get("plan_version") == "restaurant-query-plan-v2"
        and cached.get("planner_authority") == "llm"
    ):
        cached_spec = _build_spec(
            cached["code"] or None, norm_query,
            confidence=cached["confidence"], tier="plan_cache",
            clarification_needed=cached["clarification_needed"],
            clarification_question=cached["clarification_question"],
            time_phrase=cached.get("time_phrase", ""),
            llm_wants_margin=cached.get("llm_wants_margin", False),
            llm_asks_profitability=cached.get("llm_asks_profitability", False),
            llm_dish=cached.get("llm_dish"),
            llm_store=cached.get("llm_store"),
            planner_authority="validated_plan_cache",
            require_explicit_time=True,
        )
        cached_spec = await _apply_store_scope_guard(pool, factory_id, cached_spec)
        await _maybe_register_pending(pool, norm_query, cached_spec, factory_id, session_key)
        return cached_spec

    candidate_hint: Optional[Tuple[str, float]] = None
    try:
        t1_code = match_restaurant_ops(norm_query)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] keyword candidate match raised: {exc}")
        t1_code = None
    if t1_code:
        candidate_hint = (t1_code, 0.95)

    # Vector retrieval is consulted only when the exact candidate layer has
    # no opinion. A high similarity remains a hint, not an execution permit.
    try:
        if candidate_hint is None:
            t2_code, t2_sim, t2_hint = await _t2_vector_match(pool, norm_query)
            candidate_hint = (
                (t2_code, t2_sim)
                if t2_code
                else t2_hint
            )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] vector candidate match raised: {exc}")

    parsed = await _t3_llm_parse(norm_query, hint=candidate_hint, history=history)
    if parsed is None:
        # Do not fall through to a neighbouring generic intent. The semantic
        # authority was unavailable, so the only safe response is fail-closed.
        return _build_spec(
            "",
            norm_query,
            confidence=0.0,
            tier="llm",
            planner_authority="llm_unavailable",
            clarification_needed=True,
            clarification_question=(
                "餐饮语义规划暂时不可用，本次没有执行任何相邻分析。"
                "请稍后重试。"
            ),
        )

    t3_code = parsed.get("intent")
    if t3_code not in _VALID_CODES:
        t3_code = None
    try:
        t3_confidence = float(parsed.get("confidence") or 0.0)
    except (TypeError, ValueError):
        t3_confidence = 0.0
    clarification_needed = bool(parsed.get("clarification_needed"))
    clarification_question = parsed.get("clarification_question")
    if not isinstance(clarification_question, str):
        clarification_question = None

    if t3_code and t3_confidence >= _T3_MIN_CONFIDENCE and not clarification_needed:
        # T3 slot supplements (spec principle 2: LLM fills only what the
        # deterministic layer could not parse; additive-only):
        #   - time_phrase: the LLM's structured (non-date) time_range hint,
        #     rendered back into plain Chinese so `_resolve_sales_date_range`
        #     (T1/T2's SAME deterministic parser) computes the actual dates.
        #   - llm_wants_margin / llm_asks_profitability: colloquial profit
        #     phrasings the token detectors miss ("挣着钱没" etc.) — OR'd in,
        #     never removing a deterministic detection.
        # All three ride the cache so a repeat query rebuilds the same spec.
        time_phrase = _parse_t3_time_range(parsed.get("time_range"))
        llm_wants_margin = bool(parsed.get("wants_margin"))
        llm_asks_profitability = bool(parsed.get("asks_profitability"))
        llm_dish = _verbatim_entity(parsed.get("dish"), norm_query)
        llm_store = _verbatim_entity(parsed.get("store"), norm_query)
        _cache_put(factory_id, norm_query, {
            "code": t3_code, "confidence": t3_confidence, "tier": "llm",
            "plan_version": "restaurant-query-plan-v2",
            "planner_authority": "llm",
            "clarification_needed": False, "clarification_question": None,
            "time_phrase": time_phrase,
            "llm_wants_margin": llm_wants_margin,
            "llm_asks_profitability": llm_asks_profitability,
            "llm_dish": llm_dish,
            "llm_store": llm_store,
        })
        successful_spec = _build_spec(
            t3_code, norm_query, confidence=t3_confidence, tier="llm",
            time_phrase=time_phrase,
            llm_wants_margin=llm_wants_margin,
            llm_asks_profitability=llm_asks_profitability,
            llm_dish=llm_dish,
            llm_store=llm_store,
            require_explicit_time=True,
        )
        # Deterministic contract guards may turn an otherwise successful T3
        # plan into a clarification (notably: a time-scoped question with no
        # user-supplied window). Register that pending turn before returning,
        # so clicking "本月" or another offered option resumes the original
        # question instead of being parsed as a new standalone utterance.
        successful_spec = await _apply_store_scope_guard(
            pool,
            factory_id,
            successful_spec,
        )
        await _maybe_register_pending(
            pool, norm_query, successful_spec, factory_id, session_key,
        )
        return successful_spec

    # Low confidence or explicit clarification request -> surface a
    # clarification instead of querying data with a guess.
    if not clarification_question:
        clarification_question = "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
    _cache_put(factory_id, norm_query, {
        "code": t3_code or "", "confidence": t3_confidence, "tier": "llm",
        "plan_version": "restaurant-query-plan-v2",
        "planner_authority": "llm",
        "clarification_needed": True, "clarification_question": clarification_question,
    })
    spec = _build_spec(
        t3_code, norm_query, confidence=t3_confidence, tier="llm",
        clarification_needed=True, clarification_question=clarification_question,
    )
    await _maybe_register_pending(pool, norm_query, spec, factory_id, session_key)
    return spec


# ─── Clarification continuation (2026-07-08 clarification-loop v1) ───────

async def _parse_continuation(
    query: str,
    pool,
    *,
    factory_id: str,
    pending: Dict[str, Any],
) -> Optional[RestaurantQuerySpec]:
    """Resolve a follow-up answer to a previously-asked clarification
    question (module docstring "Clarification continuation"). `query` here
    is already normalized (caller: `parse_restaurant_query`) and is the
    user's ANSWER, not the original question.

    Keyword and vector retrieval run against the original question plus the
    new answer and supply a candidate hint. The LLM normally authorizes the
    resulting plan, with ``history`` carrying both turns. The only exception
    is a fixed time/store button continuing a reviewed exact phrase.

    Never touches `_ROUTE_CACHE` (a routing decision cached under a single
    utterance would not reflect this two-turn context) and never registers
    a NEW pending entry regardless of outcome (continuation is capped at one
    hop -- the caller already popped/consumed the entry that got us here).
    """
    original_query = pending.get("original_query") or ""
    clarification_question = pending.get("clarification_question")
    concatenated = f"{original_query} {query}".strip()

    try:
        if not await _is_restaurant_tenant(pool, factory_id):
            return None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] continuation tenant gate unavailable: {exc}")
        return _build_spec(
            "",
            concatenated,
            confidence=0.0,
            tier="llm",
            planner_authority="tenant_gate_unavailable",
            clarification_needed=True,
            clarification_question=(
                "暂时无法确认餐饮数据范围，本次没有执行任何分析。请稍后重试。"
            ),
            is_continuation=True,
        )

    promoted_code = _approved_exact_continuation_route(
        original_query,
        query,
        clarification_question,
    )
    if promoted_code:
        return _build_spec(
            promoted_code,
            concatenated,
            confidence=1.0,
            tier="exact",
            planner_authority="promoted_exact",
            is_continuation=True,
            require_explicit_time=True,
        )

    explicit_action_read_spec = _explicit_read_only_action_ranking_spec(
        original_query,
        query,
    )
    if explicit_action_read_spec is not None:
        return explicit_action_read_spec

    # A pending named-dish clarification is already a typed context even
    # though no resolver answer has been produced yet. Reuse the same
    # conservative follow-up contextualizer used for completed answers so a
    # dependent turn such as "怎么优化它" cannot discard "米饭 + 销量" and
    # escape into the generic owner-action route. Explicit new topics/entities
    # still make contextualize_restaurant_followup decline inheritance.
    pending_named_dish_spec = _explicit_named_dish_metric_spec(original_query)
    if (
        pending_named_dish_spec is not None
        and pending_named_dish_spec.dish_slot
        and pending_named_dish_spec.requested_metrics
    ):
        if (
            clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
            and _is_pure_store_scope_answer(query)
        ):
            # Store-scope buttons are syntactically trailing answers, while
            # the dish extractor's trusted grammar accepts scope prefixes.
            # Reorder only this already-validated pure scope answer ahead of
            # the sealed named-dish seed; no user semantics are invented.
            scoped_named_dish_spec = _explicit_named_dish_metric_spec(
                f"{query} {original_query}".strip(),
                is_continuation=True,
            )
            if scoped_named_dish_spec is not None:
                return scoped_named_dish_spec
        contextualized_query, inherited_pending = contextualize_restaurant_followup(
            query,
            {
                "parent_query": original_query,
                "parent_template_code": pending_named_dish_spec.intent,
                "structured_context": {
                    "focus_entity": {
                        "type": "dish",
                        "name": pending_named_dish_spec.dish_slot,
                    },
                    "requested_metrics": list(
                        pending_named_dish_spec.requested_metrics
                    ),
                    "analysis_action": pending_named_dish_spec.analysis_action,
                    "window_label": pending_named_dish_spec.window_label,
                    "store_scope": pending_named_dish_spec.store_scope,
                    "store_names": list(pending_named_dish_spec.store_slots),
                },
            },
        )
        if inherited_pending:
            contextualized_spec = _explicit_named_dish_metric_spec(
                contextualized_query,
                is_continuation=True,
            )
            if contextualized_spec is not None:
                return contextualized_spec

    # A time/store-only answer may fill one ranking slot, but it never answers
    # the separate READ-vs-WRITE question raised by an earlier "下架/停售"
    # request.  Keep the clarification alive until the current/history turns
    # contain an explicit view/query choice.  Otherwise the generic ranking
    # compiler would silently erase the write verb and convert the request to
    # READ merely because a store button was clicked.
    unresolved_action_choice = bool(
        _is_read_only_ranking_action_seed(concatenated)
        and not _contains_read_only_ranking_choice(concatenated)
    )
    if not unresolved_action_choice:
        explicit_ranking_spec = _explicit_store_dish_ranking_spec(
            concatenated,
            is_continuation=True,
        )
        if explicit_ranking_spec is not None:
            return explicit_ranking_spec

    for explicit_compiler in (
        _explicit_named_dish_metric_spec,
        _explicit_financial_overview_spec,
        _explicit_revenue_trend_spec,
        _explicit_store_operations_spec,
    ):
        explicit_spec = explicit_compiler(
            concatenated,
            is_continuation=True,
        )
        if explicit_spec is not None:
            return explicit_spec

    if (
        clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
        and _is_explicit_sales_period_comparison(original_query)
    ):
        original_sales_spec = _resolve_sales_query_spec(original_query)
        current_sales_spec = _resolve_sales_query_spec(query)
        if current_sales_spec.window_label != "全部历史":
            metric_phrase = _sales_metric_phrase(original_query)
            current_seed = (
                f"{metric_phrase} {query}".strip()
                if metric_phrase
                else query
            )
            # A fully restated replacement comparison belongs entirely to
            # the current turn. Do not concatenate the old day/month pair.
            fresh_comparison_spec = _explicit_sales_period_comparison_spec(
                current_seed,
                is_continuation=True,
            )
            if fresh_comparison_spec is not None:
                return fresh_comparison_spec

            # Repeating the original primary window alongside a store choice
            # is harmless ("全部门店，昨天"). It may retain the sealed original
            # baseline. A genuinely different primary window may not.
            if current_sales_spec.date_range == original_sales_spec.date_range:
                repeated_window_spec = _explicit_sales_period_comparison_spec(
                    concatenated,
                    is_continuation=True,
                )
                if repeated_window_spec is not None:
                    return repeated_window_spec

            return _build_spec(
                "",
                current_seed,
                confidence=1.0,
                tier="exact",
                planner_authority="explicit_time_override_requires_baseline",
                clarification_needed=True,
                clarification_question=(
                    f"你把时间改成了“{current_sales_spec.window_label}”，"
                    "但没有说明新的对比期。本次没有沿用"
                    f"“{original_sales_spec.comparison_label or '原对比期'}”。"
                    f"请完整说明，例如“{current_sales_spec.window_label}与上一个同期比较”，"
                    f"或说“只看{current_sales_spec.window_label}”。"
                ),
                is_continuation=True,
            )

    if (
        clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
        and _is_pure_store_scope_answer(query)
    ):
        explicit_comparison_spec = _explicit_sales_period_comparison_spec(
            concatenated,
            is_continuation=True,
        )
        if explicit_comparison_spec is not None:
            return explicit_comparison_spec

    candidate_hint: Optional[Tuple[str, float]] = None
    try:
        t1_code = match_restaurant_ops(concatenated)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] continuation keyword candidate raised: {exc}")
        t1_code = None
    if t1_code:
        candidate_hint = (t1_code, 0.95)
    else:
        try:
            t2_code, t2_sim, t2_hint = await _t2_vector_match(pool, concatenated)
            candidate_hint = (t2_code, t2_sim) if t2_code else t2_hint
        except Exception as exc:
            logger.warning(f"[restaurant-intent] continuation vector candidate raised: {exc}")

    # ── T3 with the two-turn history the caller was asked to answer ──
    history = [
        {"role": "user", "content": original_query},
        {"role": "assistant", "content": clarification_question or ""},
    ]
    parsed = await _t3_llm_parse(query, hint=candidate_hint, history=history)
    if parsed is None:
        return _build_spec(
            "",
            concatenated,
            confidence=0.0,
            tier="llm",
            planner_authority="llm_unavailable",
            clarification_needed=True,
            clarification_question=(
                "餐饮语义规划暂时不可用，本次没有执行任何相邻分析。"
                "请稍后重试。"
            ),
            is_continuation=True,
        )

    t3_code = parsed.get("intent")
    if t3_code not in _VALID_CODES:
        t3_code = None
    try:
        t3_confidence = float(parsed.get("confidence") or 0.0)
    except (TypeError, ValueError):
        t3_confidence = 0.0
    clarification_needed = bool(parsed.get("clarification_needed"))
    next_clarification_question = parsed.get("clarification_question")
    if not isinstance(next_clarification_question, str):
        next_clarification_question = None

    if t3_code and t3_confidence >= _T3_MIN_CONFIDENCE and not clarification_needed:
        time_phrase = _parse_t3_time_range(parsed.get("time_range"))
        llm_wants_margin = bool(parsed.get("wants_margin"))
        llm_asks_profitability = bool(parsed.get("asks_profitability"))
        llm_dish = _verbatim_entity(parsed.get("dish"), concatenated)
        llm_store = _verbatim_entity(parsed.get("store"), concatenated)
        t3_spec = _build_spec(
            t3_code, concatenated, confidence=t3_confidence, tier="llm",
            time_phrase=time_phrase,
            llm_wants_margin=llm_wants_margin,
            llm_asks_profitability=llm_asks_profitability,
            llm_dish=llm_dish,
            llm_store=llm_store,
            is_continuation=True,
            require_explicit_time=True,
        )
        current_sales_spec = _resolve_sales_query_spec(query)
        if (
            current_sales_spec.window_label != "全部历史"
            and t3_spec.date_range != current_sales_spec.date_range
        ):
            metric_phrase = _sales_metric_phrase(concatenated)
            current_seed = (
                f"{metric_phrase} {query}".strip()
                if metric_phrase
                else query
            )
            return _build_spec(
                "",
                current_seed,
                confidence=1.0,
                tier="exact",
                planner_authority="explicit_current_time_conflict",
                clarification_needed=True,
                clarification_question=(
                    f"你当前明确指定了“{current_sales_spec.window_label}”，"
                    "但它与上一轮时间条件冲突。本次没有沿用旧时间，也没有执行查询。"
                    "请把新的时间范围和要比较的对象完整说一次。"
                ),
                is_continuation=True,
            )
        return t3_spec

    # Still unresolved after combining both turns -- surface a (final)
    # clarification question, but per the module docstring do NOT register a
    # new pending entry: continuation is capped at exactly one hop.
    if not next_clarification_question:
        next_clarification_question = "还是没太明白，能换个说法说说想看哪方面的数据吗？"
    return _build_spec(
        t3_code, concatenated, confidence=t3_confidence, tier="llm",
        clarification_needed=True, clarification_question=next_clarification_question,
        is_continuation=True,
    )


# ─── Flywheel v1: capture + candidate listing (spec section 5) ────────────

async def log_intent_capture(
    pool,
    spec: RestaurantQuerySpec,
    *,
    factory_id: str,
    query: str,
    answer: str,
    contract_pass: Optional[bool],
    served: bool,
    total_wall_ms: int = 0,
    source: Optional[str] = None,
) -> Optional[int]:
    """Fire-and-forget capture of a parse+serve outcome, reusing the existing
    llm_fallback_logger table (no new schema). tier/confidence/contract_pass
    ride in agg_meta (JSONB) -- this is what a human reviewer / promotion
    script reads to find T3 (LLM-tier) queries worth promoting into
    SAMPLE_QUERIES / the vector index (per spec section 5, promotion stays
    manual --apply in this v1).

    `source` (Phase 2, 2026-07-07 design section 3): an optional free-form
    tag distinguishing WHERE the capture came from -- e.g.
    "java_entry_delegate" when this parse+serve was triggered by the Java
    GoldBackedRestaurantTool delegate gate rather than a direct chat.py SSE
    call. Additive-only: omitted (None) preserves the exact agg_meta shape
    every existing caller/test already relies on.
    """
    try:
        from smartbi.services.llm_fallback_logger import log_template_hit
        agg_meta = {
            "tier": spec.source_tier,
            "confidence": spec.confidence,
            "contract_pass": contract_pass,
            "served": served,
            "window_label": spec.window_label,
            "clarification_needed": spec.clarification_needed,
            "requested_metrics": list(spec.requested_metrics),
            "planned_intents": list(spec.planned_intents),
            "plan_version": spec.plan_version,
            "planner_authority": spec.planner_authority,
            "plan_hash": spec.plan_hash,
            "unsupported_requirements": list(spec.unsupported_requirements),
            "asks_priority": spec.asks_priority,
            "asks_prohibited_actions": spec.asks_prohibited_actions,
            "asks_export": spec.asks_export,
        }
        if source:
            agg_meta["source"] = source
        return await log_template_hit(
            pool, query, factory_id, None,
            spec.intent or "RESTAURANT_OPS_CLARIFICATION",
            answer, total_wall_ms, agg_meta=agg_meta,
        )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] capture logging failed (non-fatal): {exc}")
        return None


async def log_intent_miss(
    pool,
    *,
    factory_id: str,
    query: str,
    reason: str,
    spec: Optional[RestaurantQuerySpec] = None,
    java_tool_name: Optional[str] = None,
) -> Optional[int]:
    """Fire-and-forget capture of a delegate:false outcome (flywheel 盲区修补
    2026-07-23): 此前只记录答成功的查询, tiered 接不住退回 Java 的 miss 一条
    不留 -- 而飞轮最该学的恰恰是没接住的问法。

    写同一张 smart_bi_llm_fallback_log, template_code 用哨兵
    'RESTAURANT_OPS_MISS' + agg_meta.served=false, 晋升聚合器的
    tier='llm' AND contract_pass='true' AND served='true' 过滤天然不会
    把 miss 吞进晋升候选; miss 复盘走 aggregate_misses / CLI --misses。

    reason: "prefilter" (前置滤直接拒, 无 spec) | "should_delegate"
    (spec 已解析但规格路由判 False)。pool 可传 None -- 自取 (prefilter
    出口在 pool 获取之前)。
    """
    try:
        if pool is None:
            from smartbi.config import get_pg_pool
            pool = await get_pg_pool()
        if pool is None:
            return None
        from smartbi.services.llm_fallback_logger import log_template_hit
        agg_meta: Dict[str, Any] = {
            "served": False,
            "miss_reason": reason,
            "source": "delegate_gate",
        }
        if spec is not None:
            agg_meta["tier"] = spec.source_tier
            agg_meta["confidence"] = spec.confidence
            agg_meta["spec_intent"] = spec.intent
            agg_meta["clarification_needed"] = spec.clarification_needed
        if java_tool_name:
            agg_meta["java_tool_name"] = java_tool_name
        return await log_template_hit(
            pool, query, factory_id, None,
            "RESTAURANT_OPS_MISS",
            "", 0, agg_meta=agg_meta,
        )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] miss logging failed (non-fatal): {exc}")
        return None


async def list_promotion_candidates(
    pool, *, min_confidence: float = 0.6, limit: int = 50,
) -> List[Dict[str, Any]]:
    """List T3 (LLM-tier) queries that passed the Answer Contract -- v1
    promotion candidates for a human to review and, if approved, append to
    SAMPLE_QUERIES / upsert into the vector index (manual --apply, per spec
    section 5; automatic two-gate promotion is deferred to Phase 2)."""
    sql = """
        SELECT query, template_code, agg_meta, created_at
          FROM smart_bi_llm_fallback_log
         WHERE source = 'template'
           AND template_code LIKE 'RESTAURANT_OPS_%%'
           AND (agg_meta->>'tier') = 'llm'
           AND (agg_meta->>'contract_pass') = 'true'
           AND COALESCE((agg_meta->>'confidence')::float, 0) >= $1
         ORDER BY created_at DESC
         LIMIT $2
    """
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, min_confidence, limit)
        return [dict(r) for r in rows]
    except Exception as exc:
        logger.warning(f"[restaurant-intent] list_promotion_candidates failed: {exc}")
        return []
