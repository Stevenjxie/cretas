"""Answer Contract: post-hoc checks that a served OpsAnswer actually covers
what the RestaurantQuerySpec asked for (design section 4).

Pure text/heuristic validation -- no LLM judge. Tier-agnostic by design.
All restaurant tiers, including deterministic keyword hits, must pass this
same contract before they are served.
"""
from __future__ import annotations

import math
from typing import Any, Dict, List, Optional

from smartbi.gold.restaurant_intent import (
    RestaurantQuerySpec,
    TRUSTED_PLANNER_AUTHORITIES,
)

# Boss-facing Chinese labels for contract element tokens. Anything that
# builds user-visible text from ContractResult.missing MUST go through
# describe_missing() -- the raw tokens are internal identifiers and must
# never be shown to the user.
_ELEMENT_LABELS_CN = {
    "window_label": "您问的时间范围",
    "profitability_verdict": "是否赚钱的判断",
    "margin_value": "毛利数据",
    "store_name": "具体门店",
    "dish_name": "具体菜品",
    "non_empty_answer": "实际分析结果",
    "comparison": "您要求的对比周期和高低结论",
    "margin_integrity": "毛利与营收的一致口径校验",
    "request_coverage": "问题中要求的全部指标和动作",
    "execution_consistency": "语义计划与实际执行范围",
    "analysis_action": "本轮要求的原因分析或优化动作",
}


def describe_missing(missing: List[str]) -> str:
    """Render missing contract elements as boss-readable Chinese."""
    return "、".join(_ELEMENT_LABELS_CN.get(m, m) for m in missing)

# Tokens that count as an explicit "yes we answered the profitability
# question" verdict (either direction).
_PROFIT_VERDICT_TOKENS = (
    "赚钱", "亏损", "亏了", "赚了", "盈利", "打平", "持平",
    "不赚", "不亏", "亏钱", "挣钱", "净赚",
)

# Tokens that count as an explicit, honest "we could not compute this"
# disclosure -- satisfies the contract just as well as a real number would
# (per spec section 4: "缺不了就明说" is itself compliant, not a failure).
_EXPLICIT_GAP_TOKENS = (
    "无法可靠计算", "无法计算", "缺少", "缺成本", "不完整", "暂时无法",
    "不能查看", "暂无", "还没有可用",
)

_MARGIN_TOKENS = ("毛利", "毛利率", "利润")

_REQUEST_TEXT_TOKENS = {
    "net_profit": ("净利润", "净利率", "费用", "税费"),
    "customer_review": ("顾客评价", "顾客评分", "差评", "评分"),
    "production_time": ("制作时长", "制作时间", "加工时长"),
    "recipe_cost": ("菜品成本", "食材成本", "配方成本", "成本"),
    "wastage": ("食材损耗", "损耗", "浪费", "报损"),
    "sales_volume": ("菜品销量", "销量", "销售量", "高销量", "低销量"),
    "gross_margin": ("毛利率", "毛利", "利润"),
    "revenue": ("营收", "营业额", "营业收入", "销售额", "流水"),
    "orders": ("订单", "单量", "客单价"),
    "staffing": ("排班", "人效", "人员", "人手", "在岗"),
    "return_rate": ("退菜", "退款"),
    "service_speed": ("出餐", "上菜", "等餐"),
    "process_bottleneck": ("工序", "流程瓶颈"),
}


def _contains_any(text: str, tokens: tuple) -> bool:
    return any(tok in text for tok in tokens)


def _window_echoed(spec: RestaurantQuerySpec, answer_text: str) -> bool:
    if spec.window_label and spec.window_label in answer_text:
        return True
    start, end = spec.date_range
    if start is not None and end is not None:
        start_text = start.isoformat() if hasattr(start, "isoformat") else str(start)
        end_text = end.isoformat() if hasattr(end, "isoformat") else str(end)
        return start_text in answer_text and end_text in answer_text
    return False


def _profitability_verdict_present(answer_text: str) -> bool:
    return _contains_any(answer_text, _PROFIT_VERDICT_TOKENS)


def _margin_value_present(answer_text: str) -> bool:
    if not _contains_any(answer_text, _MARGIN_TOKENS):
        return False
    # Either a number/percent sign near the margin word, or an explicit,
    # honest disclosure of why it's missing -- both count as "covered".
    has_number = any(ch.isdigit() for ch in answer_text) or "%" in answer_text or "¥" in answer_text
    return has_number or _contains_any(answer_text, _EXPLICIT_GAP_TOKENS)


def _collect_named_entities(kpis: Optional[List[Dict[str, Any]]], meta: Optional[Dict[str, Any]]) -> List[str]:
    """Best-effort scrape of concrete object names (store/dish names) baked
    into kpis/meta by the resolver, so we can check whether the answer_text
    actually mentions at least one of them."""
    names: List[str] = []
    for kpi in (kpis or []):
        value = kpi.get("value")
        if isinstance(value, str) and value not in ("—", "***", "暂无"):
            names.append(value)
    for meta_list_key in ("stores", "weak_stores", "low_margin_dishes"):
        entries = (meta or {}).get(meta_list_key) or []
        for entry in entries:
            if isinstance(entry, dict) and isinstance(entry.get("name"), str):
                names.append(entry["name"])
            elif isinstance(entry, str):
                names.append(entry)
    return names


def _dimension_object_named(dimension_label: str, answer_text: str, entities: List[str]) -> bool:
    if not entities:
        # No entity list available from this resolver's meta/kpis -- can't
        # verify either way. Treat as satisfied rather than false-failing
        # every resolver that doesn't populate a name list (heuristic, not
        # a hard NLP guarantee -- see module docstring).
        return True
    return any(name in answer_text for name in entities if name)


def _comparison_present(
    spec: RestaurantQuerySpec,
    answer_text: str,
    meta: Optional[Dict[str, Any]],
) -> bool:
    comparison = (meta or {}).get("comparison")
    if not isinstance(comparison, dict) or comparison.get("answered") is not True:
        return False
    required_scope = (
        "primary_start", "primary_end", "baseline_start", "baseline_end",
    )
    if not all(comparison.get(key) for key in required_scope):
        return False
    has_result = bool(
        comparison.get("primary_no_data") is True
        or comparison.get("baseline_no_data") is True
        or (
            comparison.get("primary_bills") is not None
            and comparison.get("baseline_bills") is not None
        )
    )
    baseline_label = str(comparison.get("baseline_label") or "")
    return has_result and bool(
        (baseline_label and baseline_label in answer_text)
        or "对比期" in answer_text
        or "相比" in answer_text
    )


def _margin_integrity_present(meta: Optional[Dict[str, Any]]) -> bool:
    payload = meta or {}
    if any(payload.get(key) is True for key in ("rbac_masked", "no_pos_data", "no_data")):
        return True
    margin = payload.get("margin")
    if isinstance(margin, dict):
        payload = margin
    declared_ok = bool(
        payload.get("marginInvariantPass") is True
        and payload.get("scope_matches_request", True) is not False
    )
    if not declared_ok:
        return False

    outer_start = payload.get("outer_window_start")
    outer_end = payload.get("outer_window_end")
    requested_start = payload.get("requested_window_start")
    requested_end = payload.get("requested_window_end")
    if outer_start and requested_start and outer_start != requested_start:
        return False
    if outer_end and requested_end and outer_end != requested_end:
        return False

    numeric_keys = ("totalProfit", "totalRevenueWithCost", "totalRevenue")
    if not all(payload.get(key) is not None for key in numeric_keys):
        return True
    try:
        profit = float(payload["totalProfit"])
        covered_revenue = float(payload["totalRevenueWithCost"])
        total_revenue = float(payload["totalRevenue"])
        values = [profit, covered_revenue, total_revenue]
        if payload.get("avgRate") is not None:
            values.append(float(payload["avgRate"]))
    except (TypeError, ValueError):
        return False
    if not all(math.isfinite(value) for value in values):
        return False
    if (
        covered_revenue < -0.01
        or total_revenue < -0.01
        or profit > covered_revenue + 0.01
        or covered_revenue > total_revenue + 0.01
    ):
        return False
    if payload.get("avgRate") is not None and covered_revenue > 0:
        if abs(float(payload["avgRate"]) - profit / covered_revenue) > 0.001:
            return False
    return True


def _request_coverage_present(
    spec: RestaurantQuerySpec,
    answer_text: str,
    meta: Optional[Dict[str, Any]],
) -> bool:
    if len(spec.planned_intents) > 1 and (meta or {}).get("plan_complete") is not True:
        return False
    for requirement in spec.requested_metrics:
        tokens = _REQUEST_TEXT_TOKENS.get(requirement, ())
        if tokens and not _contains_any(answer_text, tokens):
            return False
    if spec.asks_priority and not (
        "优先级" in answer_text and "依据" in answer_text
    ):
        return False
    if spec.asks_prohibited_actions and not _contains_any(
        answer_text,
        ("先不要做", "不要", "先别", "避免"),
    ):
        return False
    if spec.asks_export and not _contains_any(
        answer_text,
        ("导出字段", "可导出", "下载", "文件"),
    ):
        return False
    return True


def _execution_consistency_present(
    spec: RestaurantQuerySpec,
    meta: Optional[Dict[str, Any]],
) -> bool:
    if spec.plan_version != "restaurant-query-plan-v2":
        return True
    payload = meta or {}
    executed = payload.get("executed_resolvers")
    return bool(
        spec.plan_hash
        and payload.get("query_plan_version") == spec.plan_version
        and payload.get("query_plan_hash") == spec.plan_hash
        and payload.get("planner_authority") in TRUSTED_PLANNER_AUTHORITIES
        and isinstance(executed, list)
        and tuple(executed) == tuple(spec.planned_intents)
        and payload.get("execution_plan_match") is True
        and payload.get("scope_matches_request") is True
    )


def _analysis_action_present(spec: RestaurantQuerySpec, answer_text: str) -> bool:
    if spec.analysis_action == "diagnose":
        return _contains_any(
            answer_text,
            (
                "原因拆解",
                "计算构成",
                "驱动因素",
                "不能证明因果",
                "前提成立",
                "前提不成立",
                "不能判断“销量低”的前提",
                "不能判断“销量高”的前提",
                "还不能证明为什么低",
                "还不能证明为什么高",
                "不能判断是否合理",
            ),
        )
    if spec.analysis_action == "optimize":
        return _contains_any(
            answer_text,
            ("优化目标", "优化建议", "优化动作", "验证指标"),
        )
    return True


# 2026-07-08 audit fix A-3: resolver 能力表 —— 契约只要求 resolver 真正能满足
# 的元素。8 码里只有 SALES_SUMMARY 的 resolver 接受 query 并按解析出的时间窗
# 取数/回显; 只有下面三个能产出毛利金额与盈亏判断。对其余 resolver 提这些
# 要求 = 永远失败的契约 + 每答必挂的免责声明 (审计 A-3 实锤场景:
# "最近7天损耗最多的食材" 的改述经 T2/T3 → WASTAGE_TOP, resolver 固定 30 天
# 窗口且不收 query, 窗口回显必然缺失)。restaurant_intent_service.should_delegate
# 复用 MARGIN_CAPABLE_INTENTS, 两处判断保持一致。
WINDOW_CAPABLE_INTENTS = frozenset({
    "RESTAURANT_OPS_SALES_SUMMARY",
})
MARGIN_CAPABLE_INTENTS = frozenset({
    "RESTAURANT_OPS_SALES_SUMMARY",
    "RESTAURANT_OPS_GROSS_MARGIN",
    "RESTAURANT_OPS_STORE_MARGIN",
})


def required_elements(spec: RestaurantQuerySpec) -> List[str]:
    """Which contract elements this spec demands the answer to cover.

    Scoped by resolver capability (see WINDOW/MARGIN_CAPABLE_INTENTS above):
    the contract is a regression guard for what the resolver CAN honor, not a
    wish list — an element the resolver can never produce would turn into a
    permanent disclaimer, which trains users to ignore disclaimers.
    """
    elements: List[str] = []
    if spec.plan_version == "restaurant-query-plan-v2":
        elements.append("execution_consistency")
    if (
        spec.intent in WINDOW_CAPABLE_INTENTS
        and (spec.relative_window or spec.window_label != "全部历史")
    ):
        elements.append("window_label")
    if spec.asks_profitability and spec.intent in MARGIN_CAPABLE_INTENTS:
        elements.append("profitability_verdict")
    if spec.wants_margin and spec.intent in MARGIN_CAPABLE_INTENTS:
        elements.append("margin_value")
        elements.append("margin_integrity")
    if spec.comparison and spec.intent == "RESTAURANT_OPS_SALES_SUMMARY":
        elements.append("comparison")
    if "store" in spec.dimensions:
        elements.append("store_name")
    if "dish" in spec.dimensions:
        elements.append("dish_name")
    if (
        spec.requested_metrics
        or spec.asks_priority
        or spec.asks_prohibited_actions
        or spec.asks_export
    ):
        elements.append("request_coverage")
    if spec.analysis_action in ("diagnose", "optimize"):
        elements.append("analysis_action")
    return elements


class ContractResult:
    __slots__ = ("missing",)

    def __init__(self, missing: List[str]):
        self.missing = missing

    @property
    def passed(self) -> bool:
        return not self.missing

    def __repr__(self) -> str:  # pragma: no cover - debug convenience
        return f"ContractResult(missing={self.missing!r})"


def validate(
    spec: RestaurantQuerySpec,
    answer_text: str,
    kpis: Optional[List[Dict[str, Any]]] = None,
    meta: Optional[Dict[str, Any]] = None,
) -> ContractResult:
    """Check answer_text (+ kpis/meta) against everything `spec` demands.

    Never raises -- callers use `.passed` / `.missing` as a quality signal
    (logged for the flywheel) and, per spec section 4, get exactly one
    supplemental-fetch opportunity before falling back to an explicit
    disclaimer. A malformed/empty answer_text simply fails every required
    element rather than raising.
    """
    text = answer_text or ""
    required = required_elements(spec)
    entities = _collect_named_entities(kpis, meta)

    missing: List[str] = []
    if not text.strip():
        missing.append("non_empty_answer")
    for element in required:
        if element == "window_label":
            if not _window_echoed(spec, text):
                missing.append(element)
        elif element == "profitability_verdict":
            if not _profitability_verdict_present(text):
                missing.append(element)
        elif element == "margin_value":
            if not _margin_value_present(text):
                missing.append(element)
        elif element == "store_name":
            if not _dimension_object_named("store", text, entities):
                missing.append(element)
        elif element == "dish_name":
            if not _dimension_object_named("dish", text, entities):
                missing.append(element)
        elif element == "comparison":
            if not _comparison_present(spec, text, meta):
                missing.append(element)
        elif element == "margin_integrity":
            if not _margin_integrity_present(meta):
                missing.append(element)
        elif element == "request_coverage":
            if not _request_coverage_present(spec, text, meta):
                missing.append(element)
        elif element == "execution_consistency":
            if not _execution_consistency_present(spec, meta):
                missing.append(element)
        elif element == "analysis_action":
            if not _analysis_action_present(spec, text):
                missing.append(element)
    return ContractResult(missing=missing)
