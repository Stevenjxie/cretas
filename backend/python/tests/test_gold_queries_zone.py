"""Tests for smartbi.gold.queries.zone_efficiency — 区域坪效.

New restaurant analytics dimension (greenfield, 2026-07). Pure mock tests
(no DB) — mirrors tests/test_gold_queries_void.py's _RecordingConn pattern.
Deliberately does NOT hit a real Postgres instance: the fact_zone_sales /
agg_daily_zone tables only exist after the V20261006_01/_02 migrations are
applied (organizer's job, not this session's).
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import zone_efficiency


class _FakeConn:
    """Routes canned rows off which query is running.

    zone_efficiency issues 2 fetchrow queries (an EXISTS availability check,
    a total SUM(revenue)/SUM(item_qty)) + 1 fetch (per-zone breakdown).
    """

    def __init__(
        self,
        *,
        total_revenue=0,
        total_item_qty=0,
        breakdown_rows=None,
        data_available=True,
    ):
        self._total_revenue = total_revenue
        self._total_item_qty = total_item_qty
        self._breakdown_rows = breakdown_rows or []
        self._data_available = data_available
        self.calls = []  # (kind, sql, params)

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        if "EXISTS" in sql:
            return {"has_data": self._data_available}
        if "FROM agg_daily_zone" in sql and "GROUP BY" not in sql:
            return {
                "total_revenue": self._total_revenue,
                "total_item_qty": self._total_item_qty,
            }
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        return self._breakdown_rows


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


@pytest.mark.asyncio
async def test_zone_efficiency_ranks_by_revenue_desc():
    rows = [
        {"zone_name": "大厅", "revenue": 5000, "item_qty": 800},
        {"zone_name": "小桌", "revenue": 3000, "item_qty": 400},
    ]
    conn = _FakeConn(total_revenue=8000, total_item_qty=1200, breakdown_rows=rows, data_available=True)
    pool = _FakePool(conn)
    result = await zone_efficiency(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)), top_n=8)

    assert result["data_available"] is True
    assert result["note"] is None
    assert result["total_revenue"] == 8000.0
    assert result["total_item_qty"] == 1200.0
    assert len(result["zones"]) == 2
    assert result["zones"][0]["zone_name"] == "大厅"
    assert result["zones"][0]["revenue"] == 5000.0
    assert result["zones"][0]["item_qty"] == 800.0
    # 5000/8000*100 = 62.5
    assert result["zones"][0]["revenue_pct"] == 62.5
    # 3000/8000*100 = 37.5
    assert result["zones"][1]["revenue_pct"] == 37.5


@pytest.mark.asyncio
async def test_zone_efficiency_no_data_uploaded_is_honest_not_zero():
    """Tenant with ZERO zone rows → 未上传区域销售数据, NOT a fabricated 0."""
    conn = _FakeConn(total_revenue=0, total_item_qty=0, breakdown_rows=[], data_available=False)
    pool = _FakePool(conn)
    result = await zone_efficiency(pool, "F1", (None, None))

    assert result["data_available"] is False
    assert result["note"] == "未上传区域销售数据"
    assert result["total_revenue"] == 0.0
    assert result["zones"] == []


@pytest.mark.asyncio
async def test_zone_efficiency_genuine_zero_revenue_reported_honestly():
    """Tenant HAS zone data but 0 revenue in window → honest 0, not fabricated note."""
    conn = _FakeConn(total_revenue=0, total_item_qty=0, breakdown_rows=[], data_available=True)
    pool = _FakePool(conn)
    result = await zone_efficiency(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)))

    assert result["data_available"] is True
    assert result["note"] is None
    assert result["total_revenue"] == 0.0


@pytest.mark.asyncio
async def test_zone_efficiency_revenue_pct_null_when_total_zero():
    """A zone with revenue but total_revenue=0 (shouldn't normally happen, but
    guards against a divide-by-zero fabricated pct if it does)."""
    rows = [{"zone_name": "大厅", "revenue": 0, "item_qty": 10}]
    conn = _FakeConn(total_revenue=0, total_item_qty=10, breakdown_rows=rows, data_available=True)
    pool = _FakePool(conn)
    result = await zone_efficiency(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)))

    assert result["zones"][0]["revenue_pct"] is None


@pytest.mark.asyncio
async def test_zone_efficiency_all_history_no_date_filter_on_range_queries():
    conn = _FakeConn(total_revenue=0, total_item_qty=0, data_available=False)
    pool = _FakePool(conn)
    await zone_efficiency(pool, "F1", (None, None))

    for kind, sql, params in conn.calls:
        # The EXISTS availability query is intentionally all-history (only
        # binds factory_id). The range SUM/breakdown queries must also have
        # no date filter under all-history.
        assert ">=" not in sql and "<=" not in sql, f"unexpected date filter: {sql}"
        assert not any(isinstance(p, date) for p in params)


@pytest.mark.asyncio
async def test_zone_efficiency_inverted_range_raises():
    conn = _FakeConn()
    pool = _FakePool(conn)
    with pytest.raises(ValueError):
        await zone_efficiency(pool, "F1", (date(2026, 2, 1), date(2026, 1, 1)))


@pytest.mark.asyncio
async def test_zone_efficiency_limit_param_bound_correctly():
    conn = _FakeConn(breakdown_rows=[])
    pool = _FakePool(conn)
    await zone_efficiency(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)), top_n=5)

    fetch_call = next(c for c in conn.calls if c[0] == "fetch")
    sql, params = fetch_call[1], fetch_call[2]
    # factory_id, start, end, top_n
    assert params == ["F1", date(2026, 1, 1), date(2026, 1, 31), 5]
    assert "LIMIT $4" in sql


@pytest.mark.asyncio
async def test_zone_efficiency_caveat_mentions_proxy_and_delivery_zones():
    conn = _FakeConn(breakdown_rows=[])
    pool = _FakePool(conn)
    result = await zone_efficiency(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)))
    assert "坪效" in result["caveat"]
    assert "外卖" in result["caveat"]
