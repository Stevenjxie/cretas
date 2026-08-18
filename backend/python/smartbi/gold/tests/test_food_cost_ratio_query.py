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


def test_no_revenue_at_all_still_says_the_three_things():
    """🔴 第三个分支此前一条断言都没有 —— 它照样要说清三件事。

    ⚠️ 这一支的「他要干什么」不是盘点（连营业额都没有，盘点也算不出来），
       是**去确认收银数据同步**。⛔ 三件事的内容按分支变，不是同一句话。
    """
    answer = asyncio.run(resolve_recipe_cost(
        _Pool({
            "revenue": 0.0,
            "transaction_count": 0,
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
    text = answer.answer_text
    assert answer.meta["no_pos_data"] is True
    assert "算不出来" in text, text
    assert "收银" in text, f"没说清是哪一侧没有\n{text}"
    assert "同步" in text, f"没说他要干什么\n{text}"
    assert "凑一个数" in text, f"没说明不会降级\n{text}"
    # ⛔ 阴性对照：这一支不该谈盘点 —— 连营业额都没有，盘了也算不出来
    assert "各盘一次库" not in text, f"没有营业额却让他去盘库\n{text}"
    for jargon in ("净营收事实", "同口径", "用同一种算法"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"


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

    # 🔴 2026-08-18 这几条断言守的是**字面**，而那几个字面正是要消灭的黑话
    #    （「暂时无法计算」「期初库存 + 本期采购 − 期末库存」「单菜配方成本榜」）。
    #    形态 C‴：断言好好的，是**需求变了** —— 症状是**红得理直气壮**，
    #    最容易被读成「我改错了」然后把改动回退掉。
    #    ⛔ 改断言最容易滑成删断言。正确改法是把守的东西从字面抬到**性质**，
    #       并补一条阴性对照钉住黑话不许回来。
    text = answer.answer_text
    assert answer.meta["food_cost_fact_missing"] is True
    # ① 明确说了算不出来（⛔ 不是给个 0 或拿别的数凑）
    assert "算不出来" in text, text
    assert "0.00%" not in text
    assert "菜品食材成本前" not in text
    # ② 说清缺的是什么，且点明配方成本 ≠ 实际耗用
    assert "实际用掉" in text, text
    assert "照配方应该用多少" in text, text
    # ③ 说清怎么拿到 —— 那条公式必须在，措辞可以变，三个量不能少
    for part in ("库存", "进货", "盘"):
        assert part in text, f"缺了「{part}」这一环\n{text}"
    # ④ 🔴 说清他自己要干什么 —— 交付定义⑤ 的第三件事，原来没有
    assert "各盘一次库" in text, f"没说他要干什么\n{text}"
    # 🔴 阴性对照：数仓黑话不许回来（「事实」在这里是 fact table 的意思）
    for jargon in ("净营收事实", "食材成本事实", "理论成本快照", "同口径",
                   "用同一种算法"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"


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

    # 同上（形态 C‴）：「参考比率」「单菜真实毛利率」是黑话字面，
    # 守的**性质**是「这个数只能当参考」和「它不是单道菜的毛利率」。
    text = answer.answer_text
    assert "25.00%" in text
    assert "只能当参考看" in text, text
    assert "不是单道菜的毛利率" in text, text
    assert "不是拿配方成本推出来的" in text, text
    for jargon in ("参考比率", "净营收事实", "同口径", "用同一种算法", "快照"):
        assert jargon not in text, f"黑话漏给了店长: {jargon}\n{text}"
    assert answer.meta["food_cost_source_types"] == ["accounting_import"]
