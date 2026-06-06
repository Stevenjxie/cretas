"""#56 价值可视化回馈回路 — value_refresh_pipeline 纯函数测试 + fake-pool 集成。"""
from __future__ import annotations

import asyncio

from smartbi.services.restaurant import value_refresh_pipeline as p


# ── 纯函数 ────────────────────────────────────────────────


def test_prev_period_normal():
    assert p._prev_period("2026-03") == "2026-02"


def test_prev_period_january_wraps_year():
    assert p._prev_period("2026-01") == "2025-12"


def test_classify_amounts_revenue_food_labor():
    rows = [
        {"record_type": "REVENUE", "category": "营业收入", "amount": 731048},
        {"record_type": "COST", "category": "食材成本", "amount": 336000},
        {"record_type": "COST", "category": "人工成本", "amount": 237660},
        {"record_type": "COST", "category": "房租", "amount": 50000},  # unclassified → ignored
    ]
    out = p._classify_amounts(rows)
    assert out["revenue"] == 731048.0
    assert out["food_cost"] == 336000.0
    assert out["labor_cost"] == 237660.0
    assert "rent" not in out


def test_classify_amounts_abs_negative():
    """成本可能以负数存储 → 取绝对值。"""
    rows = [{"record_type": "COST", "category": "食材采购", "amount": -1000}]
    out = p._classify_amounts(rows)
    assert out["food_cost"] == 1000.0


def test_classify_amounts_revenue_by_keyword():
    rows = [{"record_type": "OTHER", "category": "营业额", "amount": 5000}]
    out = p._classify_amounts(rows)
    assert out["revenue"] == 5000.0


# ── fake-pool 集成 ───────────────────────────────────────


class _PipelineConn:
    def __init__(self, cur_rows, prev_rows):
        self._cur = cur_rows
        self._prev = prev_rows
        self._call = 0
        self.upserted = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, sql, *args, **kwargs):
        if "INSERT INTO restaurant_value_snapshots" in sql:
            self.upserted = True
        return None

    async def fetch(self, sql, *args, **kwargs):
        # First period queried = current, second = previous
        self._call += 1
        if self._call == 1:
            return [_Rec(r) for r in self._cur]
        return [_Rec(r) for r in self._prev]


class _Rec(dict):
    def keys(self):
        return super().keys()


class _PipelinePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return self._conn


def test_refresh_skips_when_no_revenue():
    conn = _PipelineConn(cur_rows=[], prev_rows=[])
    pool = _PipelinePool(conn)
    result = asyncio.run(p.refresh_snapshot_for_factory(
        "F-X", period_month="2026-02", pool=pool,
    ))
    assert result["success"] is False
    assert result.get("reason") == "no_data"
    assert conn.upserted is False


def test_refresh_upserts_when_revenue_present():
    cur = [
        {"record_type": "REVENUE", "category": "营业收入", "amount": 731048},
        {"record_type": "COST", "category": "人工成本", "amount": 237660},
        {"record_type": "COST", "category": "食材成本", "amount": 336000},
    ]
    prev = [
        {"record_type": "REVENUE", "category": "营业收入", "amount": 1390503},
        {"record_type": "COST", "category": "人工成本", "amount": 323805},
        {"record_type": "COST", "category": "食材成本", "amount": 560000},
    ]
    conn = _PipelineConn(cur_rows=cur, prev_rows=prev)
    pool = _PipelinePool(conn)
    result = asyncio.run(p.refresh_snapshot_for_factory(
        "F-DENG", period_month="2026-02", pool=pool,
    ))
    assert result["success"] is True
    assert conn.upserted is True
    # labor_rigidity 信号应被算进 totalMonth (非 None)
    assert result["totalMonth"] is not None
