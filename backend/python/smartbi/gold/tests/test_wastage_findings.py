"""Unit tests for restaurant wastage finding rules.

Fake asyncpg pool/conn — no real DB. Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_wastage_findings.py -v
"""
from __future__ import annotations

import asyncio

import pytest

from smartbi.gold.restaurant.wastage_findings import (
    ACTIONABLE_WASTAGE_TYPES,
    detect_type_concentration,
)


class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _SeqConn:
    """Returns queued responses in call order — the rules issue several
    different queries, so one fixed row-set (as in test_gold_reads_restaurant)
    would feed the wrong rows to the wrong query."""

    def __init__(self, fetch_responses, fetchrow_responses=None):
        self._fetch = list(fetch_responses)
        self._fetchrow = list(fetchrow_responses or [])
        self.sqls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        self.sqls.append(sql)
        return [_FakeRecord(r) for r in self._fetch.pop(0)]

    async def fetchrow(self, sql, *a, **k):
        self.sqls.append(sql)
        row = self._fetchrow.pop(0)
        return _FakeRecord(row) if row is not None else None


class _SeqPool:
    def __init__(self, fetch_responses, fetchrow_responses=None):
        self.conn = _SeqConn(fetch_responses, fetchrow_responses)

    def acquire(self):
        return self.conn


def _run(coro):
    return asyncio.run(coro)


# ── R2: type concentration ────────────────────────────────────────────

def test_r2_reports_only_actionable_type_over_threshold():
    """MOCK_REST 实测形状：加工损耗 52.9% 结构性不报，变质 37.2% 可行动要报。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 413206.52},
        {"wastage_type": "变质", "cost": 291112.44},
        {"wastage_type": "客诉退菜", "cost": 77403.93},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    names = [f["subject_name"] for f in out["findings"]]
    assert names == ["变质"], f"加工损耗是结构性的不该报, 客诉退菜 9.9% 未过闸: {names}"
    assert out["findings"][0]["code"] == "WASTAGE_TYPE_CONCENTRATION"


def test_r2_structural_type_never_reported_even_at_high_share():
    """加工损耗占 90% 也不报 —— 它是切配常态, 店长知道也动不了。"""
    pool = _SeqPool([[
        {"wastage_type": "加工损耗", "cost": 900.0},
        {"wastage_type": "变质", "cost": 100.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []
    assert out["applicable"] is True


def test_r2_unknown_type_defaults_to_actionable():
    """未知新类型宁多报不漏报。"""
    pool = _SeqPool([[
        {"wastage_type": "运输破损", "cost": 800.0},
        {"wastage_type": "变质", "cost": 200.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert [f["subject_name"] for f in out["findings"]] == ["运输破损"]


def test_r2_below_30pct_not_reported():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 299.0},
        {"wastage_type": "加工损耗", "cost": 701.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"] == []


def test_r2_no_rows_is_applicable_with_no_findings():
    """真的没有 —— 不是 skip, 不是失败。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 0.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is True
    assert out["skip_reason"] is None
    assert out["findings"] == []


def test_r2_skips_when_by_type_kpi_unmaterialized():
    """by_type 全零但 totals 有钱 = materialize 没跑过。不得对一堆零排序。"""
    pool = _SeqPool([[]], fetchrow_responses=[{"total_cost": 894270.0}])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["applicable"] is False
    assert "未物化" in out["skip_reason"]
    assert out["findings"] == []


def test_r2_severity_warning_above_half():
    pool = _SeqPool([[
        {"wastage_type": "变质", "cost": 600.0},
        {"wastage_type": "加工损耗", "cost": 400.0},
    ]])

    out = _run(detect_type_concentration(pool, "MOCK_REST"))

    assert out["findings"][0]["severity"] == "WARNING"


def test_r2_window_boundary_is_exclusive_on_the_old_side():
    """`>=` 会取到 8 天并与基线窗重叠 —— 断言 SQL 用的是 `>`。"""
    pool = _SeqPool([[{"wastage_type": "变质", "cost": 100.0}]])

    _run(detect_type_concentration(pool, "MOCK_REST"))

    sql = pool.conn.sqls[0]
    assert "date > CURRENT_DATE" in sql
    assert "date >= CURRENT_DATE" not in sql


def test_actionable_config_is_the_only_definition():
    """配置在这个模块里, 不许别处再写一份。"""
    assert ACTIONABLE_WASTAGE_TYPES["变质"] is True
    assert ACTIONABLE_WASTAGE_TYPES["客诉退菜"] is True
    assert ACTIONABLE_WASTAGE_TYPES["加工损耗"] is False
