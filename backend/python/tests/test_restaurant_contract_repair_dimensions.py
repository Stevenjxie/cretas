"""contract-repair must not hand a plan to a resolver that cannot serve it.

Measured on prod 2026-07-30 (MOCK_REST, restaurant_manager):

    Q: 全部门店最近30天采购花了多少钱
    [restaurant-intent] contract-repair resolver RESTAURANT_OPS_REQUISITION_TREND
        -> RESTAURANT_OPS_SALES_SUMMARY metrics=('sales_volume',) dimensions=('ingredient',)
    A: 本次没有执行分析：查询维度超出计划 resolver 的能力范围。

The LLM had it RIGHT (REQUISITION_TREND, which serves the `ingredient` grain and
answers this question correctly for other phrasings such as 「领料花了多少钱」 and
「采购金额是多少」). The repair overrode it with SALES_SUMMARY because the metric
slots compiled to that single resolver -- but SALES_SUMMARY only serves `store`,
so the request was then refused downstream for a dimension mismatch the repair
itself introduced.

`_RESOLVER_DIMENSIONS` (restaurant_intent_service) is the existing source of
truth for that check; the repair has to consult it BEFORE discarding the
planner's resolver, not after.
"""
from __future__ import annotations

from smartbi.gold.restaurant import restaurant_intent as ri
from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS


def test_repair_is_skipped_when_target_cannot_serve_the_dimensions():
    """The exact prod repro: keep REQUISITION_TREND instead of rewriting to a
    resolver that cannot serve the ingredient grain."""
    spec = ri._build_spec(
        "RESTAURANT_OPS_REQUISITION_TREND",
        "全部门店最近30天采购花了多少钱",
        confidence=0.95,
        tier="llm",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("ingredient",),
    )
    assert spec.intent == "RESTAURANT_OPS_REQUISITION_TREND", (
        f"repair rewrote the plan to {spec.intent}, which does not serve "
        f"{spec.dimensions} (supports {sorted(_RESOLVER_DIMENSIONS.get(spec.intent, ()))})"
    )


def test_a_repair_whose_target_can_serve_the_dimensions_still_applies():
    """The feature must keep working: 「卖得最好的菜」 legitimately compiles from
    SALES_SUMMARY to GROSS_MARGIN (dish grain, which GROSS_MARGIN serves) and that
    rewrite is what makes dish sales ranking answerable at all."""
    spec = ri._build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "全部门店最近30天卖得最好的几个菜是哪些",
        confidence=0.95,
        tier="llm",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("dish",),
    )
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN", (
        "the dish-grain repair regressed; dish sales ranking depends on it"
    )


def test_every_repaired_plan_can_serve_its_own_dimensions():
    """Property form of the same rule, so a future repair rule cannot reintroduce
    the class: whatever resolver a spec ends up with must cover its dimensions."""
    cases = [
        ("RESTAURANT_OPS_REQUISITION_TREND", "全部门店最近30天采购花了多少钱",
         ("sales_volume",), ("ingredient",)),
        ("RESTAURANT_OPS_SALES_SUMMARY", "全部门店最近30天卖得最好的几个菜是哪些",
         ("sales_volume",), ("dish",)),
        ("RESTAURANT_OPS_REQUISITION_TREND", "全部门店最近30天领料花了多少钱",
         ("requisition_cost",), ("ingredient",)),
    ]
    for code, query, metrics, dims in cases:
        spec = ri._build_spec(
            code, query, confidence=0.95, tier="llm",
            llm_requested_metrics=metrics, llm_dimensions=dims,
        )
        if not spec.intent:
            continue  # unresolved specs are fail-closed elsewhere
        supported = _RESOLVER_DIMENSIONS.get(spec.intent, frozenset())
        assert set(spec.dimensions).issubset(supported), (
            f"{query!r} → {spec.intent} with dimensions {spec.dimensions}, "
            f"but that resolver only serves {sorted(supported)}"
        )
