"""Answer Contract: post-hoc checks that a served OpsAnswer actually covers
what the RestaurantQuerySpec asked for (design section 4).

Pure text/heuristic validation -- no LLM judge. Tier-agnostic by design
(validate() accepts any spec+answer), but in v1 the chat.py call sites only
run it on answers served through the tiered helper (T2 vector / T3 LLM):
T1 keyword hits keep their legacy byte-identical serve path per the spec's
zero-regression principle (section 1.3 rule 5 beats section 4 where they
conflict). Extending enforcement to T1 answers is a deliberate follow-up
once the contract has soaked on the new tiers.
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from smartbi.gold.restaurant_intent import RestaurantQuerySpec

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
    if (
        spec.intent in WINDOW_CAPABLE_INTENTS
        and (spec.relative_window or spec.window_label != "全部历史")
    ):
        elements.append("window_label")
    if spec.asks_profitability and spec.intent in MARGIN_CAPABLE_INTENTS:
        elements.append("profitability_verdict")
    if spec.wants_margin and spec.intent in MARGIN_CAPABLE_INTENTS:
        elements.append("margin_value")
    if "store" in spec.dimensions:
        elements.append("store_name")
    if "dish" in spec.dimensions:
        elements.append("dish_name")
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
    return ContractResult(missing=missing)
