"""Tests for smartbi.gold.queries.void_rate / void_audit — 撤单率 / 撤单稽核.

New restaurant analytics dimension (greenfield, 2026-07). Pure mock tests
(no DB) — mirrors tests/test_gold_queries_allhistory.py's _RecordingConn
pattern. Deliberately does NOT hit a real Postgres instance: the
fact_pos_void / agg_daily_void tables only exist after the V20261005_01/_02
migrations are applied (organizer's job, not this session's).
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import void_audit, void_rate


class _FakeConn:
    """Routes canned rows off which query is running.

    void_rate issues 3 fetchrow queries: an EXISTS availability check, a void
    SUM, and a bill SUM. void_audit issues a total SUM (fetchrow) + a CTE
    breakdown (fetch).
    """

    def __init__(
        self,
        *,
        void_count=0,
        bill_count=0,
        total_void=0,
        breakdown_rows=None,
        data_available=True,
    ):
        self._void_count = void_count
        self._bill_count = bill_count
        self._total_void = total_void
        self._breakdown_rows = breakdown_rows or []
        self._data_available = data_available
        self.calls = []  # (kind, sql, params)

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        if "EXISTS" in sql:
            return {"has_data": self._data_available}
        if "FROM agg_daily_void" in sql and "GROUP BY" not in sql and "WITH" not in sql:
            # Both void_rate's void_count SUM and void_audit's total SUM are
            # simple no-GROUP-BY SUM(void_count) over agg_daily_void.
            return {"void_count": self._void_count, "total": self._total_void}
        if "FROM agg_daily" in sql and "agg_daily_void" not in sql:
            return {"bill_count": self._bill_count}
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


# ── void_rate ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_void_rate_computes_percentage():
    conn = _FakeConn(void_count=30, bill_count=1000, data_available=True)
    pool = _FakePool(conn)
    result = await void_rate(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)))

    assert result["void_count"] == 30
    assert result["bill_count"] == 1000
    assert result["void_rate"] == 3.0
    assert result["data_available"] is True
    assert result["note"] is None
    assert result["factory_id"] == "F1"
    assert result["start_date"] == "2026-01-01"
    assert result["end_date"] == "2026-01-31"


@pytest.mark.asyncio
async def test_void_rate_no_data_uploaded_is_honest_not_zero():
    """Tenant with ZERO void rows → 未上传撤单数据, NOT a fabricated 0%."""
    conn = _FakeConn(void_count=0, bill_count=100000, data_available=False)
    pool = _FakePool(conn)
    result = await void_rate(pool, "F1", (None, None))

    assert result["void_rate"] is None
    assert result["data_available"] is False
    assert result["note"] == "未上传撤单数据"


@pytest.mark.asyncio
async def test_void_rate_genuine_zero_in_window_is_reported():
    """Tenant HAS void data but 0 voids in a >=50-bill window → honest 0.0%."""
    conn = _FakeConn(void_count=0, bill_count=500, data_available=True)
    pool = _FakePool(conn)
    result = await void_rate(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)))

    assert result["void_rate"] == 0.0
    assert result["data_available"] is True
    assert result["note"] is None


@pytest.mark.asyncio
async def test_void_rate_min_denominator_guard_skips():
    """bills < _VOID_MIN_BILLS (50) → skip with 样本不足 note (avoids false 过高)."""
    conn = _FakeConn(void_count=5, bill_count=20, data_available=True)
    pool = _FakePool(conn)
    result = await void_rate(pool, "F1", (None, None))

    assert result["void_rate"] is None
    assert result["data_available"] is True
    assert "样本不足" in result["note"]


@pytest.mark.asyncio
async def test_void_rate_rounds_half_up():
    # 200/600*100 = 33.333... -> HALF_UP quantize(0.01) -> 33.33 (bills >= 50)
    conn = _FakeConn(void_count=200, bill_count=600, data_available=True)
    pool = _FakePool(conn)
    result = await void_rate(pool, "F1", (None, None))
    assert result["void_rate"] == 33.33


@pytest.mark.asyncio
async def test_void_rate_all_history_no_date_filter_on_range_queries():
    conn = _FakeConn(void_count=0, bill_count=0, data_available=False)
    pool = _FakePool(conn)
    await void_rate(pool, "F1", (None, None))

    for kind, sql, params in conn.calls:
        # The EXISTS availability query is intentionally all-history (only
        # binds factory_id). The range SUM queries must also have no date
        # filter under all-history.
        assert ">=" not in sql and "<=" not in sql, f"unexpected date filter: {sql}"
        assert not any(isinstance(p, date) for p in params)


@pytest.mark.asyncio
async def test_void_rate_inverted_range_raises():
    conn = _FakeConn()
    pool = _FakePool(conn)
    with pytest.raises(ValueError):
        await void_rate(pool, "F1", (date(2026, 2, 1), date(2026, 1, 1)))


# ── void_audit ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_void_audit_breakdown_shape_rate_and_reason_null_translation():
    rows = [
        {"staff_name": "张媛媛", "store_name": "青花椒南方百联店",
         "void_count": 12, "top_reason": "客人有事走了", "bills": 300},
        {"staff_name": "收银", "store_name": "青花椒徐汇日月光店",
         "void_count": 8, "top_reason": "未标注", "bills": 400},  # sentinel -> None
        {"staff_name": "未知", "store_name": "青花椒松江地中海店",
         "void_count": 3, "top_reason": "开重桌", "bills": 0},   # unattributed -> rate None
    ]
    conn = _FakeConn(total_void=23, breakdown_rows=rows)
    pool = _FakePool(conn)
    result = await void_audit(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)), top_n=8)

    assert result["total_void_count"] == 23
    assert len(result["breakdown"]) == 3

    r0 = result["breakdown"][0]
    assert r0["staff_name"] == "张媛媛"
    assert r0["store_name"] == "青花椒南方百联店"
    assert r0["void_count"] == 12
    assert r0["bills_handled"] == 300
    assert r0["voids_per_100_bills"] == 4.0  # 12/300*100
    assert r0["top_reason"] == "客人有事走了"

    # '未标注' sentinel translated to None (never fabricated as a real reason)
    assert result["breakdown"][1]["top_reason"] is None
    assert result["breakdown"][1]["voids_per_100_bills"] == 2.0  # 8/400*100

    # Unattributed (bills_handled=0) → rate None, not a divide-by-zero / fake 0
    assert result["breakdown"][2]["voids_per_100_bills"] is None
    assert result["breakdown"][2]["staff_name"] == "未知"

    assert "每百单撤单" in result["caveat"]


@pytest.mark.asyncio
async def test_void_audit_groups_by_staff_id_not_display_name():
    """The SQL must GROUP BY staff_id (surrogate key), not the display name —
    else two 张伟 at two stores merge into one falsely-inflated row."""
    conn = _FakeConn(total_void=0, breakdown_rows=[])
    pool = _FakePool(conn)
    await void_audit(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)), top_n=5)

    fetch_call = next(c for c in conn.calls if c[0] == "fetch")
    sql = fetch_call[1]
    assert "GROUP BY staff_id, store_id" in sql
    # Must NOT group by the post-CASE staff_name alias
    assert "GROUP BY staff_name" not in sql
    # Rate denominator comes from fact_pos_transaction (bills handled per staff)
    assert "fact_pos_transaction" in sql


@pytest.mark.asyncio
async def test_void_audit_limit_param_bound_correctly():
    conn = _FakeConn(total_void=0, breakdown_rows=[])
    pool = _FakePool(conn)
    await void_audit(pool, "F1", (date(2026, 1, 1), date(2026, 1, 31)), top_n=5)

    fetch_call = next(c for c in conn.calls if c[0] == "fetch")
    sql, params = fetch_call[1], fetch_call[2]
    # factory_id, start, end, top_n
    assert params == ["F1", date(2026, 1, 1), date(2026, 1, 31), 5]
    assert "LIMIT $4" in sql
