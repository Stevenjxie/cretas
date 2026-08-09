"""Production regressions for daypart synonyms and period food-cost ratio."""
from __future__ import annotations

import asyncio
from datetime import date

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _detect_requested_metrics,
    _is_daypart_business_query,
    _is_food_cost_ratio_query,
    _semantic_spec_from_t3,
)
from smartbi.gold.restaurant.restaurant_ops_router import resolve_recipe_cost


def _t3_payload(**overrides):
    payload = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["recipe_cost", "revenue"],
        "analysis_action": "lookup",
        "dimensions": ["ingredient"],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": "all",
        "confidence": 0.96,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    payload.update(overrides)
    return payload


@pytest.mark.parametrize("query", [
    "全部门店最近30天食材成本占营收多少",
    "过去30天全店食材成本率是多少",
    "本月原料成本占营业额的比例",
])
def test_all_store_food_cost_ratio_is_one_atomic_metric(query):
    assert _is_food_cost_ratio_query(query) is True
    assert _detect_requested_metrics(query) == ("recipe_cost",)


@pytest.mark.parametrize("query", [
    "罗氏虾的食材成本占营收多少",
    "哪个菜的食材成本最高",
    "最近30天总营收是多少",
])
def test_named_or_unrelated_questions_do_not_enter_period_ratio(query):
    assert _is_food_cost_ratio_query(query) is False


def test_t3_food_cost_ratio_repairs_adjacent_composite_plan():
    spec = _semantic_spec_from_t3(
        _t3_payload(),
        "全部门店最近30天食材成本占营收多少",
    )

    assert spec.intent == "RESTAURANT_OPS_RECIPE_COST"
    assert spec.planned_intents == ("RESTAURANT_OPS_RECIPE_COST",)
    assert spec.requested_metrics == ("recipe_cost",)
    assert spec.dimensions == ()
    assert spec.planner_authority == "llm_contract_repair"
    assert spec.clarification_needed is False


def test_business_revenue_synonym_routes_to_daypart_performance():
    query = "过去30天午市、下午茶、晚市和夜宵哪个时段营业额最高"
    assert _is_daypart_business_query(query) is True
    spec = _semantic_spec_from_t3(
        _t3_payload(
            intent="RESTAURANT_OPS_TREND_ANALYSIS",
            requested_metrics=["revenue"],
            dimensions=["time"],
        ),
        query,
    )
    assert spec.intent == "RESTAURANT_OPS_DAYPART_PERFORMANCE"
    assert spec.planned_intents == ("RESTAURANT_OPS_DAYPART_PERFORMANCE",)
    assert spec.planner_authority == "llm_contract_repair"


class _Conn:
    def __init__(self, row):
        self.row = row
        self.execute_calls = []
        self.fetchrow_calls = []

    async def execute(self, sql, *args):
        self.execute_calls.append((sql, args))
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        return self.row


class _Pool:
    def __init__(self, row):
        self.conn = _Conn(row)

    def acquire(self):
        conn = self.conn

        class _Context:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Context()


def test_missing_period_food_cost_facts_fail_closed_without_recipe_ranking():
    answer = asyncio.run(resolve_recipe_cost(
        _Pool({
            "revenue": 21_497_651.2,
            "transaction_count": 60_000,
            "food_cost": None,
            "line_count": 0,
            "source_types": None,
            "categories": None,
        }),
        "MOCK_REST",
        date_range=(date(2026, 7, 10), date(2026, 8, 8)),
        window_label="最近30天",
        food_cost_ratio=True,
    ))

    assert answer.meta["food_cost_fact_missing"] is True
    assert "暂时无法计算" in answer.answer_text
    assert "期初库存 + 本期采购 − 期末库存" in answer.answer_text
    assert "单菜配方成本榜" in answer.answer_text
    assert "菜品食材成本前" not in answer.answer_text
    assert "0.00%" not in answer.answer_text


def test_registered_period_facts_are_reported_as_reference_not_true_dish_margin():
    answer = asyncio.run(resolve_recipe_cost(
        _Pool({
            "revenue": 1_000_000.0,
            "transaction_count": 2_500,
            "food_cost": 250_000.0,
            "line_count": 30,
            "source_types": ["accounting_import"],
            "categories": ["食材成本"],
        }),
        "RESTAURANT_TEST",
        date_range=(date(2026, 7, 1), date(2026, 7, 30)),
        window_label="最近30天",
        food_cost_ratio=True,
    ))

    assert "25.00%" in answer.answer_text
    assert "参考比率" in answer.answer_text
    assert "不是单菜真实毛利率" in answer.answer_text
    assert answer.meta["food_cost_source_types"] == ["accounting_import"]
