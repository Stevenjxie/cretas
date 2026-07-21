"""Unit tests for the 2026-07-08 restaurant intent tiered-routing follow-up:
two new RESTAURANT_OPS_* domains -- 库存预警 (RESTAURANT_OPS_INVENTORY_WARNING)
and 排班建议 (RESTAURANT_OPS_STAFFING_ADVICE).

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md

Covers:
  1. resolve_inventory_warning / resolve_staffing_advice -- three-tier
     classification, empty-data honest disclosure, no monetary output.
  2. match_restaurant_ops keyword routing for both new codes (>=6 phrasings
     each) + a boundary check that STOCK_SHORTAGE ("盘点差异") still wins
     over the new INVENTORY_WARNING pattern.
  3. gold_reads.py C-2 pre-filter fix: a T1-keyword-hit query for either new
     domain must reach parse_restaurant_query (not be blocked by the
     "no profit token + no relative window -> delegate:false" pre-filter).

Fake asyncpg harness mirrors test_analysis_restaurant_ops.py / test_restaurant_margin_p2.py
(``_FakeConn`` / ``_FakePool``, SQL-fragment-keyed mocks, no real DB).
"""
from __future__ import annotations

import asyncio
from datetime import date
from typing import Any, Optional
from unittest.mock import AsyncMock

import pytest

from smartbi.gold.restaurant_ops_router import (
    OpsAnswer,
    SAMPLE_QUERIES,
    match_restaurant_ops,
    resolve_inventory_warning,
    resolve_staffing_advice,
)


# ============================================================
# Fake asyncpg infrastructure (reused pattern)
# ============================================================


class _FakeConn:
    """SQL-fragment-keyed mock conn. Match queries via substring in SQL
    (longest matching key wins, so a more specific key overrides a shorter
    generic one -- mirrors test_restaurant_margin_p2.py)."""

    def __init__(self, *, fetch_map=None, fetchrow_map=None):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self.executed: list = []

    async def execute(self, sql, *args):
        self.executed.append((sql, args))

    async def fetch(self, sql, *args):
        best, best_len = [], -1
        for key, rows in self._fetch_map.items():
            if key in sql and len(key) > best_len:
                best, best_len = rows, len(key)
        return best

    async def fetchrow(self, sql, *args):
        best, best_len = None, -1
        for key, row in self._fetchrow_map.items():
            if key in sql and len(key) > best_len:
                best, best_len = row, len(key)
        return best


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


# ============================================================
# 1a. resolve_inventory_warning
# ============================================================


def _inventory_rows():
    return [
        {
            "ingredient_id": 1, "name": "活鱼", "category": "水产", "unit": "斤",
            "stock_qty": 8.0, "safe_stock_qty": 50.0, "reorder_point": 20.0,
        },  # HIGH: 8 < 20
        {
            "ingredient_id": 2, "name": "青花椒底料", "category": "调料", "unit": "kg",
            "stock_qty": 45.0, "safe_stock_qty": 30.0, "reorder_point": 10.0,
        },  # OK: 45 >= 30
        {
            "ingredient_id": 3, "name": "毛肚", "category": "肉类", "unit": "kg",
            "stock_qty": 20.0, "safe_stock_qty": 35.0, "reorder_point": 15.0,
        },  # MEDIUM: 15 <= 20 < 35
    ]


def test_resolve_inventory_warning_three_tier_classification():
    conn = _FakeConn(
        fetchrow_map={"MAX(snapshot_date)": {"max_date": date(2026, 7, 7)}},
        fetch_map={"FROM fact_inventory_snapshot s": _inventory_rows()},
    )
    pool = _FakePool(conn)

    result = asyncio.run(resolve_inventory_warning(pool, "DEMO_REST"))

    assert isinstance(result, OpsAnswer)
    assert result.code == "RESTAURANT_OPS_INVENTORY_WARNING"
    assert result.meta["high_count"] == 1
    assert result.meta["medium_count"] == 1
    assert result.meta["ok_count"] == 1
    assert result.meta["high_ingredients"] == ["活鱼"]
    assert "活鱼" in result.answer_text
    assert "毛肚" in result.answer_text
    # HIGH item must appear first / be prioritized in the disclosure text.
    assert result.answer_text.index("活鱼") < result.answer_text.index("毛肚")


def test_resolve_inventory_warning_no_money_output():
    conn = _FakeConn(
        fetchrow_map={"MAX(snapshot_date)": {"max_date": date(2026, 7, 7)}},
        fetch_map={"FROM fact_inventory_snapshot s": _inventory_rows()},
    )
    pool = _FakePool(conn)

    result = asyncio.run(resolve_inventory_warning(pool, "DEMO_REST"))
    assert "¥" not in result.answer_text
    for kpi in result.kpis:
        assert "¥" not in str(kpi.get("value"))


def test_resolve_inventory_warning_empty_data_honest_disclosure():
    conn = _FakeConn(fetchrow_map={"MAX(snapshot_date)": {"max_date": None}})
    pool = _FakePool(conn)

    result = asyncio.run(resolve_inventory_warning(pool, "NO_DATA_FACTORY"))
    assert result.meta.get("no_data") is True
    assert result.charts == []
    assert result.kpis == []
    assert "上传" in result.answer_text or "库存管理" in result.answer_text


# ============================================================
# 1b. resolve_staffing_advice
# ============================================================


def _staffing_rows():
    return [
        {"daypart": "午市", "weekday_type": "weekday", "avg_orders": 180.0, "staff_on_duty": 6, "target": 25.0},
        {"daypart": "下午茶", "weekday_type": "weekday", "avg_orders": 40.0, "staff_on_duty": 5, "target": 25.0},
        {"daypart": "晚市", "weekday_type": "weekday", "avg_orders": 200.0, "staff_on_duty": 8, "target": 25.0},
    ]


def test_resolve_staffing_advice_flags_over_and_under_staffed():
    conn = _FakeConn(fetch_map={"FROM fact_staffing_daypart": _staffing_rows()})
    pool = _FakePool(conn)

    result = asyncio.run(resolve_staffing_advice(pool, "DEMO_REST"))

    assert isinstance(result, OpsAnswer)
    assert result.code == "RESTAURANT_OPS_STAFFING_ADVICE"
    # 180/6 = 30/人 > 25*1.15 -> understaffed (needs more headcount)
    assert "weekday-午市" in result.meta["understaffed"]
    # 40/5 = 8/人 < 25*0.7 -> overstaffed (redundant headcount)
    assert "weekday-下午茶" in result.meta["overstaffed"]
    # 200/8 = 25/人 == target -> balanced, appears in neither list
    assert "weekday-晚市" not in result.meta["understaffed"]
    assert "weekday-晚市" not in result.meta["overstaffed"]

    understaffed_kpi = next(k for k in result.kpis if k["title"] == "最缺人时段")
    overstaffed_kpi = next(k for k in result.kpis if k["title"] == "最冗余时段")
    assert understaffed_kpi["value"] == "weekday-午市"
    assert overstaffed_kpi["value"] == "weekday-下午茶"


def test_resolve_staffing_advice_no_money_output():
    conn = _FakeConn(fetch_map={"FROM fact_staffing_daypart": _staffing_rows()})
    pool = _FakePool(conn)

    result = asyncio.run(resolve_staffing_advice(pool, "DEMO_REST"))
    assert "¥" not in result.answer_text
    for kpi in result.kpis:
        assert "¥" not in str(kpi.get("value"))


def test_resolve_staffing_advice_empty_data_honest_disclosure():
    conn = _FakeConn(fetch_map={"FROM fact_staffing_daypart": []})
    pool = _FakePool(conn)

    result = asyncio.run(resolve_staffing_advice(pool, "NO_DATA_FACTORY"))
    assert result.meta.get("no_data") is True
    assert result.charts == []
    assert result.kpis == []


def test_resolve_staffing_advice_zero_staff_on_duty_does_not_crash():
    """staff_on_duty = 0 (or NULL) must not raise ZeroDivisionError -- honest
    'cannot compute' advice instead (mirrors the module's fail-open style)."""
    rows = [{"daypart": "夜宵", "weekday_type": "weekday", "avg_orders": 50.0, "staff_on_duty": 0, "target": 20.0}]
    conn = _FakeConn(fetch_map={"FROM fact_staffing_daypart": rows})
    pool = _FakePool(conn)

    result = asyncio.run(resolve_staffing_advice(pool, "DEMO_REST"))
    assert "无法计算" in result.answer_text


# ============================================================
# 2. Keyword routing (match_restaurant_ops)
# ============================================================


@pytest.mark.parametrize("query,expected_code", [
    (sq, code)
    for code, samples in SAMPLE_QUERIES.items()
    if code in ("RESTAURANT_OPS_INVENTORY_WARNING", "RESTAURANT_OPS_STAFFING_ADVICE")
    for sq in samples
])
def test_new_domain_sample_queries_route_correctly(query: str, expected_code: str):
    assert match_restaurant_ops(query) == expected_code


@pytest.mark.parametrize("query", [
    "盘点差异最大的食材 top 10",
    "库存差异排名",
    "哪些食材经常盘亏",
])
def test_stock_shortage_boundary_not_hijacked_by_inventory_warning(query: str):
    """库存差异/盘点差异 must keep routing to the pre-existing
    RESTAURANT_OPS_STOCK_SHORTAGE (historical count/actual reconciliation),
    NOT the new RESTAURANT_OPS_INVENTORY_WARNING (current stock-level
    threshold read) -- these are different questions that happen to share
    the substring "库存"/"食材"."""
    assert match_restaurant_ops(query) == "RESTAURANT_OPS_STOCK_SHORTAGE"


def test_inventory_warning_does_not_hijack_recipe_cost():
    """食材成本 (RECIPE_COST) shares the "食材" substring with
    INVENTORY_WARNING's group-1 but must keep routing to RECIPE_COST."""
    assert match_restaurant_ops("食材成本最高的菜是哪些") == "RESTAURANT_OPS_RECIPE_COST"


# ============================================================
# 3. gold_reads.py C-2 pre-filter fix
# ============================================================


def _fake_request(role=None):
    from types import SimpleNamespace
    return SimpleNamespace(state=SimpleNamespace(role=role))


@pytest.mark.asyncio
@pytest.mark.parametrize("query", ["哪些食材快没了", "今晚怎么排班"])
async def test_prefilter_allows_t1_hit_query_through(monkeypatch, query: str):
    """2026-07-08 follow-up to C-2: a query with no profit token and no
    relative/named time window (e.g. the new inventory/staffing domains)
    must still reach parse_restaurant_query when match_restaurant_ops hits
    it deterministically -- the C-2 pre-filter must not treat T1 hits as
    signal-free."""
    import smartbi.api.gold_reads as gold_reads_mod
    from smartbi.api.gold_reads import TieredIntentAnswerRequest, post_restaurant_tiered_answer

    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "DEMO_REST")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    parse_mock = AsyncMock(return_value=None)
    monkeypatch.setattr("smartbi.gold.restaurant_intent.parse_restaurant_query", parse_mock)

    body = TieredIntentAnswerRequest(factory_id="DEMO_REST", query=query)
    await post_restaurant_tiered_answer(_fake_request(), body)

    assert parse_mock.await_count == 1, (
        f"parse_restaurant_query must run for T1-hit query {query!r} -- "
        "the C-2 pre-filter incorrectly treated it as signal-free"
    )


@pytest.mark.asyncio
async def test_prefilter_allows_regression_export_capability_clarification(monkeypatch):
    """A chart/regression request must reach the deterministic capability
    response even though it has no profit token, relative time window, or T1
    resolver match.  Otherwise the Java delegate gate silently discards the
    export fallback before ``parse_restaurant_query`` can return it.
    """
    import smartbi.api.gold_reads as gold_reads_mod
    from smartbi.api.gold_reads import TieredIntentAnswerRequest, post_restaurant_tiered_answer

    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "DEMO_REST")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    parse_mock = AsyncMock(return_value=None)
    monkeypatch.setattr("smartbi.gold.restaurant_intent.parse_restaurant_query", parse_mock)

    body = TieredIntentAnswerRequest(
        factory_id="DEMO_REST",
        query="帮我画销量和价格的回归曲线并给出R²，不能画就导出数据",
    )
    await post_restaurant_tiered_answer(_fake_request(), body)

    assert parse_mock.await_count == 1


@pytest.mark.asyncio
async def test_prefilter_still_blocks_truly_signal_free_query(monkeypatch):
    """Regression guard: a query with no profit token, no relative window,
    AND no T1 keyword hit must still be blocked before parse_restaurant_query
    (this is the ORIGINAL C-2 behavior -- must not regress when adding the
    T1-hit passthrough)."""
    import smartbi.api.gold_reads as gold_reads_mod
    from smartbi.api.gold_reads import TieredIntentAnswerRequest, post_restaurant_tiered_answer

    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "DEMO_REST")
    parse_mock = AsyncMock(side_effect=AssertionError("parse must not run for signal-free query"))
    monkeypatch.setattr("smartbi.gold.restaurant_intent.parse_restaurant_query", parse_mock)

    body = TieredIntentAnswerRequest(factory_id="DEMO_REST", query="哪个菜卖得好")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}
    assert parse_mock.await_count == 0
