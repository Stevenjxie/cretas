"""Unit tests for P3 store_review_vs_revenue (fake pool, no DB).

The analysis function issues 3 fetch() calls (review agg / alias / revenue)
with distinct SQL. The fake conn routes by a substring of the SQL so each
returns the right synthetic rows.

Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_store_review_revenue.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.store_review_revenue import (
    _pearson,
    store_review_vs_revenue,
)


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _RoutingConn:
    """Routes fetch() to the right row set by matching a marker in the SQL."""

    def __init__(self, review_rows, alias_rows, revenue_rows):
        self._review = [_FakeRecord(r) for r in review_rows]
        self._alias = [_FakeRecord(r) for r in alias_rows]
        self._revenue = [_FakeRecord(r) for r in revenue_rows]

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        if "评价门店" in sql and "avg_rating" in sql:
            return self._review
        if "dim_store_review_alias" in sql:
            return self._alias
        if "agg_daily" in sql:
            return self._revenue
        raise AssertionError(f"unexpected SQL: {sql[:80]}")


class _FakePool:
    def __init__(self, review_rows, alias_rows, revenue_rows):
        self._conn = _RoutingConn(review_rows, alias_rows, revenue_rows)

    def acquire(self):
        return self._conn


_RANGE = (date(2025, 1, 1), date(2025, 12, 31))


def _run(pool, **kw):
    return asyncio.run(store_review_vs_revenue(pool, "RES_3101_009", _RANGE, **kw))


# ---------------------------------------------------------------------------
# pearson helper
# ---------------------------------------------------------------------------

def test_pearson_perfect_positive():
    assert _pearson([1, 2, 3, 4], [10, 20, 30, 40]) == pytest.approx(1.0)


def test_pearson_zero_variance_returns_none():
    # constant ratings → undefined correlation.
    assert _pearson([5, 5, 5, 5], [1, 2, 3, 4]) is None


# ---------------------------------------------------------------------------
# join + honest labeling
# ---------------------------------------------------------------------------

def _review(name, n, star):
    return {"review_store_name": name, "review_count": n, "avg_rating": Decimal(str(star))}


def _alias(name, sid, conf, decided="auto"):
    return {"review_store_name": name, "store_id": sid,
            "confidence": Decimal(str(conf)), "decided_by": decided}


def _rev(sid, name, revenue, bills):
    return {"store_id": sid, "gold_store_name": name,
            "revenue": Decimal(str(revenue)), "bill_count": bills}


def test_linked_and_unlinked_counts():
    review = [
        _review("青花椒(日月光店)", 100, 4.8),
        _review("青花椒(五角场店)", 50, 4.5),
        _review("青花椒·外卖卫星店(浦东店)", 30, 4.2),  # no alias → unlinked
    ]
    alias = [
        _alias("青花椒(日月光店)", 101, 0.92),
        _alias("青花椒(五角场店)", 102, 1.0, "admin"),
    ]
    revenue = [
        _rev(101, "青花椒徐汇日月光店", 2_000_000, 30000),
        _rev(102, "青花椒五角场万达店", 1_500_000, 25000),
    ]
    res = _run(_FakePool(review, alias, revenue))
    assert res["linked_count"] == 2
    assert res["unlinked_count"] == 1
    assert res["unlinked_review_stores"] == ["青花椒·外卖卫星店(浦东店)"]
    assert res["total_review_stores"] == 3
    assert res["total_gold_stores"] == 2
    # revenue-desc ordering
    assert res["linked_stores"][0]["revenue"] == 2_000_000.0
    assert "已关联 2/3" in res["honest_note"]


def test_low_confidence_alias_excluded_from_join():
    # 注: _ALIAS_SQL 已在 SQL 层用 confidence>=$min 过滤; 单测模拟"SQL 只返达标行",
    # 这里验证 min_confidence 传参生效 + 低置信不出现在 linked。
    review = [_review("青花椒(日月光店)", 100, 4.8)]
    # 模拟 0.60 行被 SQL 过滤掉 → alias 返回空。
    alias = []
    revenue = [_rev(101, "青花椒徐汇日月光店", 2_000_000, 30000)]
    res = _run(_FakePool(review, alias, revenue), min_confidence=0.90)
    assert res["linked_count"] == 0
    assert res["unlinked_count"] == 1
    assert res["correlation"] is None
    assert "0 家已确认映射" in res["honest_note"]
    assert "next_action" in res


def test_n_below_4_correlation_null():
    review = [_review(f"店{i}", 50, 4.0 + i * 0.1) for i in range(3)]
    alias = [_alias(f"店{i}", 100 + i, 0.95) for i in range(3)]
    revenue = [_rev(100 + i, f"POS店{i}", 1_000_000 + i * 100000, 10000) for i in range(3)]
    res = _run(_FakePool(review, alias, revenue))
    assert res["linked_count"] == 3
    assert res["correlation"] is None  # n<4


def test_n_at_least_4_correlation_computed():
    review = [_review(f"店{i}", 50, 4.0 + i * 0.1) for i in range(5)]
    alias = [_alias(f"店{i}", 100 + i, 0.95) for i in range(5)]
    revenue = [_rev(100 + i, f"POS店{i}", 1_000_000 + i * 200000, 10000) for i in range(5)]
    res = _run(_FakePool(review, alias, revenue))
    assert res["linked_count"] == 5
    corr = res["correlation"]
    assert corr is not None
    assert corr["metric"] == "pearson_rating_vs_revenue"
    assert corr["n"] == 5
    # rating ↑ with revenue ↑ → strong positive
    assert corr["value"] == pytest.approx(1.0, abs=0.01)
    assert "样本量小" in corr["note"]  # n<8


def test_unlinked_list_always_returned_with_names():
    review = [_review("孤店A", 40, 4.1), _review("孤店B", 20, 3.9)]
    res = _run(_FakePool(review, [], []))
    assert res["unlinked_count"] == 2
    assert sorted(res["unlinked_review_stores"]) == ["孤店A", "孤店B"]
    assert res["honest_note"]  # honest_note always present


def test_empty_review_data_structured_state():
    res = _run(_FakePool([], [], []))
    assert res["linked_count"] == 0
    assert res["correlation"] is None
    assert "暂无大众点评评价数据" in res["honest_note"]
    assert "next_action" in res


def test_alias_pointing_to_store_without_revenue_is_unlinked():
    # alias 指向 store 999, 但该期间 999 无营收 → 计未关联 (不当 0 营收混入)。
    review = [_review("青花椒(冷门店)", 30, 4.0)]
    alias = [_alias("青花椒(冷门店)", 999, 1.0, "admin")]
    revenue = []  # 999 在此期间无营收
    res = _run(_FakePool(review, alias, revenue))
    assert res["linked_count"] == 0
    assert res["unlinked_review_stores"] == ["青花椒(冷门店)"]
