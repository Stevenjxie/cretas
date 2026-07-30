"""采购/领料 + 花钱 must compile to a requisition cost metric.

Measured on prod 2026-07-30 (MOCK_REST, restaurant_manager):

    「全部门店最近30天采购花了多少钱」 → 本次没有执行分析…（拒答）
    「全部门店最近30天领料花了多少钱」 → ✅ ¥6,060,743.28
    「全部门店最近30天采购金额是多少」 → ✅ ¥6,060,743.28

The difference was NOT a keyword table — verified by experiment: feeding the same
synthetic slots (metrics=('sales_volume',)) makes the 领料 phrasing fail too, yet
it answers in prod. So the two phrasings differ only in what the LLM happened to
put in `requested_metrics`, i.e. the deterministic layer has no opinion at all:
`_REQUEST_METRIC_RULES` contains **no requisition metric**, so 领料/采购 can only
ever be classified by the model.

That is the missing half of the earlier 「领料成本 ≠ 菜品成本」 fix: the negative
lookbehind built from `_KITCHEN_OPS_NOUNS` (which already contains 采购/进货)
stops those phrases becoming `recipe_cost`, but nothing routes them TO
requisition. This adds that half, so the plan no longer depends on model whim.
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


@pytest.mark.parametrize(
    "query",
    [
        "全部门店最近30天采购花了多少钱",
        "全部门店最近30天领料花了多少钱",
        "全部门店最近30天进货花了多少钱",
        "上周采购花了多少钱",
        "最近30天采购金额是多少",
        "领用成本是多少",
        "这个月采购开销大不大",
    ],
)
def test_requisition_spend_compiles_to_requisition_cost(query):
    metrics = ri._detect_requested_metrics(query)
    assert "requisition_cost" in metrics, f"{query!r} → {metrics}"
    assert "sales_volume" not in metrics, (
        f"{query!r} → {metrics}; sales_volume routes the plan to the sales-summary "
        f"resolver, which only serves the store grain and then refuses"
    )


@pytest.mark.parametrize(
    "query, expected",
    [
        # A sales question with a currency suffix must stay revenue.
        ("米饭卖了多少钱", "revenue"),
        # Dish cost must stay dish cost.
        ("哪些菜的食材成本最高", "recipe_cost"),
        # Wastage keeps its own metric.
        ("损耗金额最高的食材", "wastage"),
    ],
)
def test_neighbouring_money_questions_are_untouched(query, expected):
    metrics = ri._detect_requested_metrics(query)
    assert expected in metrics, f"{query!r} → {metrics}"
    assert "requisition_cost" not in metrics, f"{query!r} → {metrics}"


def test_bare_requisition_noun_does_not_force_a_cost_metric():
    """「领料最多的是哪些食材」 is a quantity question and already works; the new
    rule must not staple a cost metric onto it."""
    metrics = ri._detect_requested_metrics("领料最多的是哪些食材")
    assert "requisition_cost" not in metrics, metrics


def test_call_site_plan_keeps_the_requisition_resolver():
    """Pins the effect end-to-end through _build_spec: the plan must stay on the
    resolver that serves the ingredient grain, with no clarification.

    `llm_semantics_authoritative=True` + `require_explicit_time=True` mirror the
    PRODUCTION semantic-first call site (`_semantic_spec_from_t3`) — that flag is
    what lets the deterministic metric words outrank the model's slot, which is
    exactly what this fix relies on. Dropping it here would test a path
    production never takes.
    """
    spec = ri._build_spec(
        "RESTAURANT_OPS_REQUISITION_TREND",
        "全部门店最近30天采购花了多少钱",
        confidence=0.95,
        tier="llm",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("ingredient",),
        llm_semantics_authoritative=True,
        require_explicit_time=True,
    )
    assert spec.intent == "RESTAURANT_OPS_REQUISITION_TREND", spec.intent
    assert not spec.clarification_needed, spec.clarification_question
