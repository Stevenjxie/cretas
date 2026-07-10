"""Tests for the 3 restaurant-analytics synthesis dimensions added 2026-07-10:
渠道(点餐方式 堂食/外卖/自提) / 时段(午市/晚市/夜宵) / 折扣(总额/占营收比/构成).

Mirrors the dish_margin test pattern in test_synthesis_dish_margin_plan.py /
test_synthesis_cost_profit.py: plan-trigger tests (no DB), query tests against
a fake asyncpg pool/connection, factbook render+index tests, and a migration
content assertion test (no live DB required).
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from smartbi.agent.factbook import FactBook
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine
from smartbi.gold.queries import (
    discount_summary,
    meal_period_breakdown,
    order_type_breakdown,
)


def _migrations_dir() -> Path:
    return Path(__file__).resolve().parents[1] / "smartbi" / "database" / "migrations"


# ---------------------------------------------------------------------------
# Plan-dimension triggers (rules-first, no LLM, no DB)
# ---------------------------------------------------------------------------

def test_channel_keyword_enables_channel_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("各渠道营收占比怎么样？")
    assert plan["channel"] is True


def test_bare_order_type_word_enables_channel_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("外卖单子最近多不多？")
    assert plan["channel"] is True


def test_meal_period_keyword_enables_meal_period_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("午市和晚市哪个时段营收更高？")
    assert plan["meal_period"] is True


def test_meal_period_bare_shichang_word_enables_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("夜宵时段的客流怎么样？")
    assert plan["meal_period"] is True


def test_discount_keyword_enables_discount_dimension():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("这个月满减折扣花了多少钱？占营收多少？")
    assert plan["discount"] is True


def test_plain_finance_question_does_not_trigger_new_dimensions():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("这个月营收多少？")
    assert plan["channel"] is False
    assert plan["meal_period"] is False
    assert plan["discount"] is False


def test_bare_time_word_without_business_context_does_not_trigger_meal_period():
    engine = ComprehensiveSynthesisEngine(pool=object())
    plan = engine.plan_dimensions("几点开门营业？")
    assert plan["meal_period"] is False


# ---------------------------------------------------------------------------
# Deterministic queries — fake asyncpg pool/connection
# ---------------------------------------------------------------------------

class _Acquire:
    def __init__(self, conn) -> None:
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        return None


class _Pool:
    def __init__(self, conn) -> None:
        self._conn = conn

    def acquire(self) -> _Acquire:
        return _Acquire(self._conn)


class _OrderTypeConn:
    async def fetch(self, sql: str, *params):
        assert "agg_daily_order_type_meal" in sql
        assert "GROUP BY order_type" in sql
        assert params[0] == "DEMO_REST"
        return [
            {"order_type": "堂食", "revenue": Decimal("5500.00"), "bill_count": 55},
            {"order_type": "外卖", "revenue": Decimal("3500.00"), "bill_count": 35},
            {"order_type": "自提", "revenue": Decimal("1000.00"), "bill_count": 10},
        ]


class _MealPeriodConn:
    async def fetch(self, sql: str, *params):
        assert "agg_daily_order_type_meal" in sql
        assert "GROUP BY meal_period" in sql
        return [
            {"meal_period": "午市", "revenue": Decimal("4000.00"), "bill_count": 40},
            {"meal_period": "晚市", "revenue": Decimal("5000.00"), "bill_count": 50},
            {"meal_period": "夜宵", "revenue": Decimal("1000.00"), "bill_count": 10},
        ]


class _DiscountConn:
    async def fetch(self, sql: str, *params):
        assert "agg_discount" in sql
        assert "dim_discount" in sql
        return [
            {"discount_id": 1, "name": "满减", "amount": Decimal("240.00"), "bill_count": 30},
            {"discount_id": 2, "name": "会员折扣", "amount": Decimal("210.00"), "bill_count": 25},
            {"discount_id": 3, "name": "团购券", "amount": Decimal("150.00"), "bill_count": 18},
        ]

    async def fetchrow(self, sql: str, *params):
        assert "agg_daily" in sql
        assert "SUM(net_amount)" in sql
        return {"revenue": Decimal("10000.00")}


class _DiscountConnNoRevenue:
    """Discounts exist but agg_daily has no rows in range → honest None share."""

    async def fetch(self, sql: str, *params):
        return [{"discount_id": 1, "name": "满减", "amount": Decimal("100.00"), "bill_count": 10}]

    async def fetchrow(self, sql: str, *params):
        return {"revenue": None}


@pytest.mark.asyncio
async def test_order_type_breakdown_computes_ticket_and_share():
    out = await order_type_breakdown(
        _Pool(_OrderTypeConn()), "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 1)),
    )
    assert out["total_revenue"] == 10000.0
    assert len(out["order_types"]) == 3
    dine_in = out["order_types"][0]
    assert dine_in["order_type"] == "堂食"
    assert dine_in["revenue"] == 5500.0
    assert dine_in["bill_count"] == 55
    assert dine_in["avg_ticket"] == 100.0
    assert dine_in["revenue_pct"] == 55.0
    takeout = out["order_types"][1]
    assert takeout["order_type"] == "外卖"
    assert takeout["revenue_pct"] == 35.0
    pickup = out["order_types"][2]
    assert pickup["revenue_pct"] == 10.0
    # Splits reconcile to the total (consistency contract).
    assert sum(i["revenue"] for i in out["order_types"]) == out["total_revenue"]


@pytest.mark.asyncio
async def test_meal_period_breakdown_computes_ticket_and_share():
    out = await meal_period_breakdown(
        _Pool(_MealPeriodConn()), "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 1)),
    )
    assert out["total_revenue"] == 10000.0
    lunch = out["meal_periods"][0]
    assert lunch["meal_period"] in ("午市", "晚市")  # ordered by revenue desc
    dinner = next(p for p in out["meal_periods"] if p["meal_period"] == "晚市")
    assert dinner["revenue"] == 5000.0
    assert dinner["avg_ticket"] == 100.0
    assert dinner["revenue_pct"] == 50.0
    late_night = next(p for p in out["meal_periods"] if p["meal_period"] == "夜宵")
    assert late_night["revenue_pct"] == 10.0


@pytest.mark.asyncio
async def test_discount_summary_computes_share_and_revenue_pct():
    out = await discount_summary(
        _Pool(_DiscountConn()), "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 31)),
    )
    assert out["total_discount_amount"] == 600.0
    assert out["total_revenue"] == 10000.0
    assert out["revenue_share_pct"] == 6.0
    top = out["discounts"][0]
    assert top["discount_name"] == "满减"
    assert top["amount"] == 240.0
    assert top["share_pct"] == 40.0


@pytest.mark.asyncio
async def test_discount_summary_handles_zero_revenue_honestly():
    out = await discount_summary(
        _Pool(_DiscountConnNoRevenue()), "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 31)),
    )
    assert out["total_revenue"] == 0.0
    assert out["revenue_share_pct"] is None
    # per-discount share-of-discount-total still computes (100% of 1 item).
    assert out["discounts"][0]["share_pct"] == 100.0


# ---------------------------------------------------------------------------
# FactBook rendering + facts_index (FactReconciler grounding)
# ---------------------------------------------------------------------------

def test_factbook_renders_channel_and_indexes_facts():
    fb = FactBook(channel={
        "total_revenue": 10000.0,
        "order_types": [
            {"order_type": "堂食", "revenue": 5500.0, "bill_count": 55,
             "avg_ticket": 100.0, "revenue_pct": 55.0},
            {"order_type": "外卖", "revenue": 3500.0, "bill_count": 35,
             "avg_ticket": 100.0, "revenue_pct": 35.0},
        ],
    })
    text = fb.to_prompt_text()
    idx = fb.to_facts_index()

    assert "渠道" in text
    assert "堂食" in text
    assert idx["堂食营收"] == 5500.0
    assert idx["堂食客单价"] == 100.0
    assert idx["堂食占比"] == 55.0
    assert idx["堂食订单数"] == 55.0


def test_factbook_renders_meal_period_and_indexes_facts():
    fb = FactBook(meal_period={
        "total_revenue": 10000.0,
        "meal_periods": [
            {"meal_period": "午市", "revenue": 4000.0, "bill_count": 40,
             "avg_ticket": 100.0, "revenue_pct": 40.0},
        ],
    })
    text = fb.to_prompt_text()
    idx = fb.to_facts_index()

    assert "时段" in text
    assert "午市" in text
    assert idx["午市营收"] == 4000.0
    assert idx["午市占比"] == 40.0


def test_factbook_renders_discount_and_indexes_facts():
    fb = FactBook(discount={
        "total_discount_amount": 600.0,
        "total_revenue": 10000.0,
        "revenue_share_pct": 6.0,
        "discounts": [
            {"discount_id": 1, "discount_name": "满减", "amount": 240.0,
             "bill_count": 30, "share_pct": 40.0},
        ],
    })
    text = fb.to_prompt_text()
    idx = fb.to_facts_index()

    assert "折扣" in text
    assert "满减" in text
    assert "非因果" in text  # honest label: descriptive, not causal
    assert idx["折扣总额"] == 600.0
    assert idx["折扣占营收比"] == 6.0
    assert idx["满减折扣额"] == 240.0
    assert idx["满减折扣占比"] == 40.0


def test_factbook_empty_dims_render_nothing():
    fb = FactBook()
    text = fb.to_prompt_text()
    idx = fb.to_facts_index()
    assert "渠道" not in text
    assert "时段" not in text
    assert "折扣" not in text
    assert idx == {}


# ---------------------------------------------------------------------------
# Migration content (no live DB required)
# ---------------------------------------------------------------------------

def test_channel_meal_discount_seed_migration_is_demo_only_and_idempotent():
    sql = (_migrations_dir() / "V20260710_01__demo_rest_channel_meal_discount_seed.sql").read_text(
        encoding="utf-8",
    )
    assert "SELECT set_config('app.factory_id', 'DEMO_REST', false)" in sql
    assert "INSERT INTO agg_daily_order_type_meal" in sql
    assert "WHERE a.factory_id = 'DEMO_REST'" in sql
    assert "ON CONFLICT (factory_id, date, store_id, order_type, meal_period) DO UPDATE SET" in sql
    assert "INSERT INTO dim_discount" in sql
    assert "ON CONFLICT (factory_id, name) DO NOTHING" in sql
    assert "INSERT INTO agg_discount" in sql
    assert "ON CONFLICT (factory_id, discount_id, month) DO UPDATE SET" in sql
    # Ratios sum to 1.0 within each dimension (spot-check the literal values).
    assert "0.55::numeric" in sql and "0.35::numeric" in sql and "0.10::numeric" in sql
    assert "0.40::numeric" in sql and "0.50::numeric" in sql
