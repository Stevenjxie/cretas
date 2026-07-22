"""Tiered restaurant intent router: keyword (T1) -> vector (T2) -> LLM (T3).

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md
Clarification-loop v1 (2026-07-08): the ``history`` parameter reserved (but
unused) in the design above is now wired up so a user's answer to a
clarification question gets parsed IN CONTEXT of the original question,
instead of being re-parsed as a brand-new, context-free query. See the
"Clarification continuation (v1, 2026-07-08)" section further down.

Architecture (spec section references below are to that doc):

  parse_restaurant_query(query, pool, factory_id=..., history=..., session_key=...)
      T1 keyword match_restaurant_ops()               (<1ms,  confidence=0.95)
      T2 vector cosine_topk(code_prefix=RESTAURANT_OPS_) (~30ms, confidence=similarity)
      T3 LLM structured QuerySpec parse (SLOT.MAPPER)  (thinking off, confidence=0.0-1.0)
      miss -> None (caller falls through to its existing chain, unchanged)

Six architecture principles (spec section 1.3), enforced here:
  1. LLM never computes numbers or dates -- only a structured time descriptor;
     real dates come from `_resolve_sales_date_range` (imported from
     restaurant_ops_router, unmodified) and are always recomputed on every
     call (never cached), so a cached routing decision from yesterday still
     resolves "最近两个月" against *today*.
  2. Intent selection is tiered; slot extraction (time/margin/profitability/
     dimension/comparison) is a single deterministic layer shared by all
     three tiers.
  3. `_OPS_PATTERNS` (T1) is frozen -- new phrasing is absorbed by T2/T3, not
     by editing the keyword table.
  4. Business-type gate: T2/T3 only run for restaurant tenants (see
     `_is_restaurant_tenant`). T1 is ungated (existing behavior).
  5. T1 is byte-for-byte unchanged: callers get confidence=0.95,
     source_tier="keyword", and can keep passing `query=` straight through to
     `resolve_by_code` exactly as before.
  6. Fail-open: any exception at any tier is swallowed and logged; the
     function returns None so the caller's existing fallback chain runs
     (mirrors template_rag.hybrid_match's "never raises" contract).

Clarification continuation (v1, 2026-07-08):

  When `parse_restaurant_query` returns a clarification AND the caller passed
  a non-empty `session_key`, the (factory_id, session_key) -> {original
  question, clarification question} pair is registered in the shared smartbi
  Postgres table `restaurant_pending_clarifications` (~5 minute TTL, judged
  Python-side on pop; migration V20260708_01). Storage MUST be the shared DB,
  not process memory: prod runs `uvicorn --workers 2`, and an in-process
  store made continuation a coin flip whenever the follow-up landed on the
  other worker (2026-07-08 prod bug -- see the "Pending-clarification store"
  section comment below). The NEXT call for that same (factory_id,
  session_key) is then treated as the user's ANSWER to that clarification,
  not a fresh standalone query:

    1. Deterministic fast path FIRST: T1 keyword, then T2 vector, both run
       against the ORIGINAL question concatenated with the new answer (no LLM
       token spent if that combination is already resolvable).
    2. Only on a deterministic miss does this escalate to T3, this time with
       `history=[{"role":"user","content":<original question>},
       {"role":"assistant","content":<clarification question>}]` so the LLM
       combines both turns (spec principle 2: the LLM only fills what the
       deterministic layer could not).

  This whole continuation path bypasses `_ROUTE_CACHE` (a routing decision
  cached under a single utterance would not reflect the two-turn context,
  and would poison a later STANDALONE ask of the same follow-up text -- see
  principle 6 point 6 in the 2026-07-08 clarification-loop design brief).

  Continuation is capped at ONE hop: the pending entry is consumed (removed)
  the moment it is read, regardless of whether the continuation attempt
  resolves or produces yet another clarification -- so a second, still-vague
  answer surfaces a (final) clarification question but does NOT register a
  new pending entry (no infinite clarification loops). The returned spec's
  `is_clarification_continuation` flag distinguishes a continuation-produced
  spec from a fresh single-turn one (for logging / tests).

  A missing/empty `session_key`, or no pending entry for the given key (never
  registered, already consumed, or past the TTL), simply skips all of the
  above -- `parse_restaurant_query` behaves exactly as it did before this
  feature existed (pure fail-open addition, zero behavior change for callers
  that don't pass `session_key`).
"""
from __future__ import annotations

import json
import logging
from collections import OrderedDict
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant_ops_router import (
    SAMPLE_QUERIES,
    _profit_intent,
    _resolve_sales_date_range,
    _uses_relative_sales_window,
    match_restaurant_ops,
)

logger = logging.getLogger(__name__)


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
    source_tier: str                              # "keyword" | "vector" | "llm"
    clarification_needed: bool = False
    clarification_question: Optional[str] = None
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


# ─── Deterministic slot detectors (shared across all 3 tiers) ─────────────

_STORE_TOKENS = ("门店", "分店", "店铺", "哪家店", "哪个店", "各店")
_DISH_TOKENS = ("菜品", "菜系", "菜价", "哪道菜", "哪个菜", "单品")
_INGREDIENT_TOKENS = ("食材", "原料", "配料", "领料", "领用")


def _detect_dimensions(text: str) -> Tuple[str, ...]:
    """Which real-world objects the question is asking to break down by.

    Order is deterministic (store, dish, ingredient) so tests/logs are stable.
    """
    dims: List[str] = []
    if any(tok in text for tok in _STORE_TOKENS) or ("店" in text and "点" not in text):
        dims.append("store")
    if any(tok in text for tok in _DISH_TOKENS) or "菜" in text:
        dims.append("dish")
    if any(tok in text for tok in _INGREDIENT_TOKENS):
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
    if "环比" in text:
        return "mom"
    if any(tok in text for tok in ("比上周", "周比", "跟上周比")):
        return "wow"
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
    ("recipe_cost", ("菜品成本", "食材成本", "配方成本", "单品成本")),
    ("wastage", ("食材损耗", "损耗", "浪费", "报损", "腐坏", "过期")),
    ("sales_volume", ("菜品销量", "销量", "销售量", "卖得好", "卖得慢", "慢销", "滞销")),
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
    "net_profit", "return_rate", "customer_review", "production_time",
    "service_speed", "process_bottleneck",
})


def _detect_requested_metrics(text: str) -> Tuple[str, ...]:
    detected = tuple(
        metric
        for metric, tokens in _REQUEST_METRIC_RULES
        if any(token in text for token in tokens)
    )
    rejects_gross_substitution = any(token in text for token in (
        "不要用毛利", "不能用毛利", "不用毛利", "不拿毛利", "毛利替代",
    ))
    if "net_profit" in detected and (
        "毛利" not in text or rejects_gross_substitution
    ):
        detected = tuple(metric for metric in detected if metric != "gross_margin")
    return detected


def _plan_requested_intents(
    text: str,
    selected_code: str,
    requested_metrics: Tuple[str, ...],
    dimensions: Tuple[str, ...],
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
            code = "RESTAURANT_OPS_RECIPE_COST"
        elif metric == "wastage":
            code = "RESTAURANT_OPS_WASTAGE_TOP"
        elif metric == "sales_volume":
            code = (
                "RESTAURANT_OPS_GROSS_MARGIN"
                if "gross_margin" in requested_metrics and "dish" in dimensions
                else "RESTAURANT_OPS_SALES_SUMMARY"
            )
        elif metric == "gross_margin":
            if selected_code == "RESTAURANT_OPS_SALES_SUMMARY" and has_revenue_scope:
                code = "RESTAURANT_OPS_SALES_SUMMARY"
            elif explicit_store_margin or (
                selected_code == "RESTAURANT_OPS_STORE_MARGIN" and "store" in dimensions
            ):
                code = "RESTAURANT_OPS_STORE_MARGIN"
            else:
                code = "RESTAURANT_OPS_GROSS_MARGIN"
        elif metric in ("revenue", "orders"):
            code = (
                "RESTAURANT_OPS_TREND_ANALYSIS"
                if selected_code == "RESTAURANT_OPS_TREND_ANALYSIS"
                else "RESTAURANT_OPS_SALES_SUMMARY"
            )
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
    missing_labels = {
        "net_profit": "净利润（还缺费用、税费及其他收支）",
        "return_rate": "退菜率（还缺退菜时间、菜品、数量、原因和责任门店）",
        "customer_review": "顾客评价（还缺评分、评价文本、时间、菜品与门店）",
        "production_time": "菜品制作时长（还缺开始制作和完成时间）",
        "service_speed": "逐单出餐时长（还缺下单、开始制作和出餐完成时间）",
        "process_bottleneck": "工序瓶颈（还缺各工序节点及耗时）",
    }
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
    missing = [missing_labels[item] for item in requirements if item in missing_labels]
    return (
        f"当前可以可靠分析：{'、'.join(available)}。"
        f"当前不能可靠分析：{'；'.join(missing)}。"
        "不会用营业额、毛利或其他相近指标替代这些缺失指标，也不会把部分完成说成全部完成。"
        "补齐括号内明细后可以继续；也可以明确只分析当前已有的维度。"
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
    "那", "这个", "那个", "它", "刚才", "继续", "再", "为什么", "怎么做", "怎么办", "怎么",
    "哪些动作", "先别", "明天看", "和上", "与上", "跟上", "比上", "呢",
)
_NEW_TOPIC_TOKENS = ("换个话题", "换一个问题", "另一个问题", "另外问", "新话题")


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
    if not parent_query or not parent_code.startswith("RESTAURANT_OPS_"):
        return current, False

    has_followup_signal = (
        len(current) <= 32
        and (
            current.startswith(_FOLLOWUP_PREFIXES)
            or current.endswith(("呢", "吗", "怎么办", "为什么", "如何", "怎么样", "合理"))
            or any(token in current for token in ("相比", "对比", "比呢", "高还是低", "是否"))
        )
    )
    if not has_followup_signal:
        return current, False

    # A fully specified new metric + time phrase is self-contained.  Leading
    # pronouns such as "那毛利呢" remain dependent and intentionally inherit.
    standalone_code = match_restaurant_ops(current)
    leading_dependent = current.startswith(("那", "这个", "那个", "它", "刚才", "继续", "再"))
    if standalone_code and _uses_relative_sales_window(current) and not leading_dependent:
        return current, False
    return f"{parent_query}；继续追问：{current}", True


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


def _default_metrics_for_code(code: str, wants_margin: bool) -> Tuple[str, ...]:
    metrics = list(_DEFAULT_METRICS_BY_CODE.get(code, ()))
    if wants_margin and "gross_margin" not in metrics:
        metrics.append("gross_margin")
    return tuple(metrics)


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
    effective_query = f"{query} {time_phrase}".strip() if time_phrase else query
    date_range, window_label = _resolve_sales_date_range(effective_query)
    wants_margin, asks_profitability = _profit_intent(effective_query)
    asks_profitability = asks_profitability or llm_asks_profitability
    wants_margin = wants_margin or llm_wants_margin or asks_profitability
    relative_window = _uses_relative_sales_window(effective_query)
    dimensions = _detect_dimensions(effective_query)
    comparison = _detect_comparison(effective_query)
    metrics = _default_metrics_for_code(code, wants_margin) if code else ()
    requested_metrics = _detect_requested_metrics(effective_query)
    planned_intents = _plan_requested_intents(
        effective_query,
        code,
        requested_metrics,
        dimensions,
    )
    unsupported_requirements = tuple(
        requirement
        for requirement in requested_metrics
        if requirement in _UNSUPPORTED_REQUIREMENTS
    )
    asks_priority = any(token in effective_query for token in (
        "优先级", "优先", "先查", "先做", "首先", "哪项先", "先看哪",
    ))
    asks_prohibited_actions = any(token in effective_query for token in (
        "先不要做", "不要做", "先别做", "不该做", "避免做", "暂时别",
    ))
    asks_export = any(token in effective_query for token in (
        "导出", "下载", "生成文件", "可导出的字段",
    ))
    if unsupported_requirements and not clarification_needed:
        clarification_needed = True
        clarification_question = _unsupported_requirement_question(
            unsupported_requirements,
            requested_metrics,
        )

    return RestaurantQuerySpec(
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
        is_clarification_continuation=is_continuation,
        requested_metrics=requested_metrics,
        planned_intents=planned_intents,
        unsupported_requirements=unsupported_requirements,
        asks_priority=asks_priority,
        asks_prohibited_actions=asks_prohibited_actions,
        asks_export=asks_export,
    )


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
    parts = [query]
    if spec.window_label != "全部历史" and spec.window_label not in query:
        parts.append(spec.window_label)
    raw_wants_margin, raw_asks_profit = _profit_intent(query)
    if spec.asks_profitability and not raw_asks_profit:
        parts.append("赚钱了吗")
    elif spec.wants_margin and not raw_wants_margin:
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
            row = await conn.fetchrow(
                "SELECT 1 FROM agg_restaurant_daily_totals WHERE factory_id = $1 LIMIT 1",
                factory_id,
            )
    except Exception as exc:
        # 2026-07-08 audit fix B-3: 查询异常 (DB 抖动) 只本次按 False 处理,
        # 不写缓存 —— 否则一个瞬时错误会把合法餐饮租户永久锁出 T2/T3
        # (进程不重启不恢复)。只有拿到明确的 DB 答案才缓存。
        logger.warning(
            f"[restaurant-intent] tenant gate lookup failed for {factory_id} (not cached): {exc}"
        )
        return False
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

_T3_TIMEOUT_SECONDS = 5.0
_T3_MIN_CONFIDENCE = 0.6


def _build_t3_prompt(query: str, hint: Optional[Tuple[str, float]], history: Optional[Sequence[Dict[str, str]]]) -> str:
    intent_lines = "\n".join(
        f'  - "{code}": {desc}' for code, desc in _INTENT_DESCRIPTIONS.items()
    )
    hint_line = ""
    if hint:
        hint_code, hint_sim = hint
        hint_line = (
            f"\n提示: 向量检索认为最可能是 \"{hint_code}\" (相似度 {hint_sim:.2f})，"
            f"仅供参考，请结合问题原文自行判断。\n"
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
        '"dimensions": [], "comparison": null, "confidence": 0.9, '
        '"clarification_needed": false, "clarification_question": null}\n'
        '示例2: "情况怎么样" (完全没有可判断的对象/指标) -> '
        '{"intent": null, "time_range": null, "wants_margin": false, '
        '"asks_profitability": false, "dimensions": [], "comparison": null, '
        '"confidence": 0.2, "clarification_needed": true, '
        '"clarification_question": "您想了解营收、毛利、损耗还是库存盘点的情况？"}\n'
    )
    return (
        "你是餐饮老板问答系统的意图解析器。将用户问题解析为一个 JSON 对象，不要输出任何其他文字。\n"
        "可选 intent 取值（必须从下面列表中选择一个，或者在无法判断时输出 null）：\n"
        f"{intent_lines}\n"
        f"{hint_line}\n"
        f"{history_line}"
        "严格规则:\n"
        "1. 你绝对不能计算或输出具体日期！time_range 只能是结构化描述，例如: "
        '{"type": "relative", "unit": "month", "count": 2} (最近2个月), '
        '{"type": "relative", "unit": "day", "count": 10} (最近10天), '
        '{"type": "named", "value": "today"|"this_week"|"this_month"}, '
        '{"type": "all_history"}, 或 null (未提及时间)。真实日期由确定性代码计算，不是你的工作。\n'
        "2. confidence 是你对 intent 判断的把握程度 (0.0-1.0)。\n"
        "3. 如果问题太模糊无法判断 intent，输出 intent:null 且 clarification_needed:true，"
        "并给出一个具体的澄清问题(clarification_question)。\n"
        "4. 只输出 JSON，不要 markdown 代码块，不要解释。\n\n"
        f"{few_shot}\n"
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


async def _t3_llm_parse(
    query: str,
    *,
    hint: Optional[Tuple[str, float]],
    history: Optional[Sequence[Dict[str, str]]],
) -> Optional[Dict[str, Any]]:
    """Call the SmartBI LLM router (SLOT.MAPPER: thinking off, json_object,
    temperature 0) to structurally parse `query`. Returns the parsed dict, or
    None on any failure/timeout (fail-open -- caller treats None as "T3 had
    nothing to offer")."""
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
            result = await call_chain(SLOT.MAPPER, payload, timeout=_T3_TIMEOUT_SECONDS)
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
) -> Optional[RestaurantQuerySpec]:
    """Resolve `query` to a RestaurantQuerySpec via T1 -> T2 -> T3, or None.

    Fail-open at every tier: any exception is logged and swallowed, and the
    function degrades to the next tier (or to None), never raising into the
    caller's chat.py SSE stream.

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
            return await _parse_continuation(norm_query, pool, factory_id=factory_id, pending=pending)

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
    if early_unsupported:
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

    # ── T1: keyword (ungated, unchanged, <1ms) ──
    try:
        t1_code = match_restaurant_ops(norm_query)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T1 keyword match raised (fail-open): {exc}")
        t1_code = None
    if t1_code:
        return _build_spec(t1_code, norm_query, confidence=0.95, tier="keyword")

    # ── Business-type gate: T2/T3 only for restaurant tenants ──
    try:
        if not await _is_restaurant_tenant(pool, factory_id):
            return None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] tenant gate raised (fail-open, treat as non-restaurant): {exc}")
        return None

    cached = _cache_get(factory_id, norm_query)
    if cached is not None:
        cached_spec = _build_spec(
            cached["code"] or None, norm_query,
            confidence=cached["confidence"], tier=cached["tier"],
            clarification_needed=cached["clarification_needed"],
            clarification_question=cached["clarification_question"],
            time_phrase=cached.get("time_phrase", ""),
            llm_wants_margin=cached.get("llm_wants_margin", False),
            llm_asks_profitability=cached.get("llm_asks_profitability", False),
        )
        await _maybe_register_pending(pool, norm_query, cached_spec, factory_id, session_key)
        return cached_spec

    # ── T2: vector (code_prefix=RESTAURANT_OPS_, ~30ms, 0 LLM tokens) ──
    try:
        t2_code, t2_sim, t2_hint = await _t2_vector_match(pool, norm_query)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T2 vector match raised (fail-open): {exc}")
        t2_code, t2_sim, t2_hint = None, 0.0, None
    if t2_code:
        _cache_put(factory_id, norm_query, {
            "code": t2_code, "confidence": t2_sim, "tier": "vector",
            "clarification_needed": False, "clarification_question": None,
        })
        return _build_spec(t2_code, norm_query, confidence=t2_sim, tier="vector")

    # ── T3: LLM structured parse (thinking off, temperature 0, 5s timeout) ──
    parsed = await _t3_llm_parse(norm_query, hint=t2_hint, history=history)
    if parsed is None:
        return None  # miss at every tier -> caller falls through, unchanged

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
        _cache_put(factory_id, norm_query, {
            "code": t3_code, "confidence": t3_confidence, "tier": "llm",
            "clarification_needed": False, "clarification_question": None,
            "time_phrase": time_phrase,
            "llm_wants_margin": llm_wants_margin,
            "llm_asks_profitability": llm_asks_profitability,
        })
        return _build_spec(
            t3_code, norm_query, confidence=t3_confidence, tier="llm",
            time_phrase=time_phrase,
            llm_wants_margin=llm_wants_margin,
            llm_asks_profitability=llm_asks_profitability,
        )

    # Low confidence or explicit clarification request -> surface a
    # clarification instead of querying data with a guess.
    if not clarification_question:
        clarification_question = "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
    _cache_put(factory_id, norm_query, {
        "code": t3_code or "", "confidence": t3_confidence, "tier": "llm",
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

    Order of attempts (spec section 3 of the 2026-07-08 clarification-loop
    design): deterministic T1 keyword, then T2 vector -- both against the
    ORIGINAL question concatenated with this answer (so slot detectors see
    the full two-turn context, e.g. a "哪家店" dimension mentioned only in
    the original question) -- and only on a miss there, T3 LLM with
    `history` carrying both turns.

    Never touches `_ROUTE_CACHE` (a routing decision cached under a single
    utterance would not reflect this two-turn context) and never registers
    a NEW pending entry regardless of outcome (continuation is capped at one
    hop -- the caller already popped/consumed the entry that got us here).
    """
    original_query = pending.get("original_query") or ""
    clarification_question = pending.get("clarification_question")
    concatenated = f"{original_query} {query}".strip()

    # ── deterministic fast path: T1 then T2 on the concatenated text ──
    try:
        t1_code = match_restaurant_ops(concatenated)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] continuation T1 match raised (fail-open): {exc}")
        t1_code = None
    if t1_code:
        return _build_spec(t1_code, concatenated, confidence=0.95, tier="keyword", is_continuation=True)

    try:
        if not await _is_restaurant_tenant(pool, factory_id):
            return None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] continuation tenant gate raised (fail-open): {exc}")
        return None

    try:
        t2_code, _t2_sim, t2_hint = await _t2_vector_match(pool, concatenated)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] continuation T2 match raised (fail-open): {exc}")
        t2_code, t2_hint = None, None
    if t2_code:
        return _build_spec(t2_code, concatenated, confidence=_t2_sim, tier="vector", is_continuation=True)

    # ── T3 with the two-turn history the caller was asked to answer ──
    history = [
        {"role": "user", "content": original_query},
        {"role": "assistant", "content": clarification_question or ""},
    ]
    parsed = await _t3_llm_parse(query, hint=t2_hint, history=history)
    if parsed is None:
        return None  # miss at every tier -> caller falls through, unchanged

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
        return _build_spec(
            t3_code, concatenated, confidence=t3_confidence, tier="llm",
            time_phrase=time_phrase,
            llm_wants_margin=llm_wants_margin,
            llm_asks_profitability=llm_asks_profitability,
            is_continuation=True,
        )

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
