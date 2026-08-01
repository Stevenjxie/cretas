"""Pure (no-DB) tests that the Gold query functions support all-history.

WS1 Task 1: a None start/end means "no filter on that side" (= all
history). These tests monkeypatch a fake asyncpg pool that captures the
SQL string + bound params for every fetch/fetchrow, then assert the
date-filter shape + placeholder numbering.

Mock shape mirrors tests/test_analysis_finance_restaurant.py (_FakeConn /
_FakePool) but additionally RECORDS each call so we can inspect the SQL.
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import (
    channel_breakdown,
    daily_trend,
    discount_breakdown,
    finance_summary,
    kpi_summary,
    order_type_mix,
    staff_ranking,
    top_products,
)


class _RecordingConn:
    """Captures every fetch/fetchrow (sql, params). Returns empty results."""

    def __init__(self):
        self.calls = []  # list of (kind, sql, params)

    async def execute(self, *args, **kwargs):
        # gold/queries.tenant_conn 会在借到连接后 set_config 租户上下文。
        # 假连接必须支持它 —— 不支持等于建模了一条永远不设租户的连接,
        # 而那正是 2026-08-01 修掉的那个非确定性返空缺陷本身。
        return None
    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        return []

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        # Return a zero-ish row so callers that index into it don't crash.
        return {
            "total_revenue": 0,
            "bill_count": 0,
            "store_count": 0,
            "day_count": 0,
            "revenue": 0,
            "bills": 0,
            "items": 0,
            "customers": 0,
            "stores": 0,
            "days": 0,
        }


class _RecordingPool:
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


def _no_date_filter(sql: str) -> bool:
    """True iff the SQL has no `date`/`month`-bound range condition."""
    return (
        ">=" not in sql
        and "<=" not in sql
        and "BETWEEN" not in sql.upper()
    )


# ── finance_summary (the WS1 pilot) ─────────────────────────


@pytest.mark.asyncio
async def test_finance_summary_all_history_no_date_filter():
    conn = _RecordingConn()
    pool = _RecordingPool(conn)
    await finance_summary(pool, "F1", (None, None), top_n_stores=10)

    # Two queries fire: totals (fetchrow) + top_stores (fetch).
    assert len(conn.calls) == 2
    for kind, sql, params in conn.calls:
        assert _no_date_filter(sql), f"unexpected date filter in {kind}: {sql}"
        # No date objects should be bound — only factory_id (+ top_n on fetch).
        assert not any(isinstance(p, date) for p in params)

    # totals: only factory_id bound.
    totals_call = next(c for c in conn.calls if c[0] == "fetchrow")
    assert totals_call[2] == ["F1"]
    # top_stores: factory_id + top_n (limit), no dates.
    stores_call = next(c for c in conn.calls if c[0] == "fetch")
    assert stores_call[2] == ["F1", 10]
    # The LIMIT placeholder must reference $2 (not $4) now that the two
    # date params are gone — placeholder renumbering correctness.
    assert "LIMIT $2" in stores_call[1]


@pytest.mark.asyncio
async def test_finance_summary_start_only_filters_lower_bound_only():
    conn = _RecordingConn()
    pool = _RecordingPool(conn)
    await finance_summary(pool, "F1", (date(2025, 1, 1), None), top_n_stores=5)

    stores_call = next(c for c in conn.calls if c[0] == "fetch")
    sql, params = stores_call[1], stores_call[2]
    assert ">=" in sql
    assert "<=" not in sql
    # params: factory_id, start_date, top_n  →  LIMIT should be $3.
    assert params == ["F1", date(2025, 1, 1), 5]
    assert "LIMIT $3" in sql

    totals_call = next(c for c in conn.calls if c[0] == "fetchrow")
    # totals drops the trailing top_n param → only factory_id + start.
    assert totals_call[2] == ["F1", date(2025, 1, 1)]


@pytest.mark.asyncio
async def test_finance_summary_end_only_filters_upper_bound_only():
    conn = _RecordingConn()
    pool = _RecordingPool(conn)
    await finance_summary(pool, "F1", (None, date(2025, 12, 31)), top_n_stores=5)

    stores_call = next(c for c in conn.calls if c[0] == "fetch")
    sql, params = stores_call[1], stores_call[2]
    assert "<=" in sql
    assert ">=" not in sql
    assert params == ["F1", date(2025, 12, 31), 5]
    assert "LIMIT $3" in sql


@pytest.mark.asyncio
async def test_finance_summary_full_range_filters_both_bounds():
    conn = _RecordingConn()
    pool = _RecordingPool(conn)
    await finance_summary(
        pool, "F1", (date(2025, 1, 1), date(2025, 12, 31)), top_n_stores=7
    )
    stores_call = next(c for c in conn.calls if c[0] == "fetch")
    sql, params = stores_call[1], stores_call[2]
    assert ">=" in sql and "<=" in sql
    assert params == ["F1", date(2025, 1, 1), date(2025, 12, 31), 7]
    # LIMIT now $4 (factory + 2 dates + top_n).
    assert "LIMIT $4" in sql


# ── Spot-check the other range-based queries all-history-clean ───


@pytest.mark.asyncio
async def test_other_queries_all_history_no_date_filter():
    for fn, kwargs in (
        (daily_trend, {}),
        (kpi_summary, {}),
        (channel_breakdown, {"top_n": 10}),
        (discount_breakdown, {"top_n": 10}),
        (order_type_mix, {}),
        (staff_ranking, {"top_n": 5}),
        (top_products, {"top_n": 10}),
    ):
        conn = _RecordingConn()
        pool = _RecordingPool(conn)
        await fn(pool, "F1", (None, None), **kwargs)
        assert conn.calls, f"{fn.__name__} issued no query"
        for kind, sql, params in conn.calls:
            assert _no_date_filter(sql), (
                f"{fn.__name__} {kind} still has a date filter: {sql}"
            )
            assert not any(isinstance(p, date) for p in params), (
                f"{fn.__name__} bound a date param under all-history: {params}"
            )


@pytest.mark.asyncio
async def test_all_history_does_not_raise_on_inverted_guard():
    """(None, None) must NOT trip the inverted-range guard."""
    conn = _RecordingConn()
    pool = _RecordingPool(conn)
    # Should complete without ValueError.
    await daily_trend(pool, "F1", (None, None))
