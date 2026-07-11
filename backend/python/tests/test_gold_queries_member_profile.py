"""Tests for smartbi.gold.queries.member_profile — 会员储值 + 画像.

New restaurant analytics dimension (greenfield, 2026-07). Pure mock tests
(no DB) — mirrors tests/test_gold_queries_void.py's _FakeConn pattern.
Deliberately does NOT hit a real Postgres instance: the dim_member /
fact_member_recharge / agg_member_* tables only exist after the
V20261006_01/_02 migrations are applied (organizer's job, not this
session's).
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import member_profile


class _FakeConn:
    """Routes canned rows based on which query is running.

    member_profile issues 1 fetchrow (EXISTS availability) + 3 fetch queries
    (tier distribution, birth-month distribution, recharge trend).
    """

    def __init__(
        self,
        *,
        data_available=True,
        tier_rows=None,
        birth_rows=None,
        recharge_rows=None,
    ):
        self._data_available = data_available
        self._tier_rows = tier_rows or []
        self._birth_rows = birth_rows or []
        self._recharge_rows = recharge_rows or []
        self.calls = []  # (kind, sql, params)

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        if "EXISTS" in sql:
            return {"has_data": self._data_available}
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        if "FROM agg_member_tier" in sql:
            return self._tier_rows
        if "FROM agg_member_birth_month" in sql:
            return self._birth_rows
        if "FROM agg_member_recharge_daily" in sql:
            return self._recharge_rows
        raise AssertionError(f"unexpected fetch SQL: {sql}")


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


# ── data_available / honest empty-state ───────────────────────


@pytest.mark.asyncio
async def test_member_profile_no_data_uploaded_is_honest():
    """Tenant with ZERO agg_member_tier rows → 未上传会员数据, not a fabricated
    empty distribution indistinguishable from 'uploaded but zero members'."""
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert result["data_available"] is False
    assert result["note"] == "未上传会员数据"
    assert result["member_count"] == 0
    assert result["total_balance"] == 0
    assert result["tier_distribution"] == []
    assert result["birth_month_distribution"] == []
    assert result["recharge_trend"] == []


@pytest.mark.asyncio
async def test_member_profile_factory_id_and_date_echo():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (date(2026, 1, 1), date(2026, 7, 31)))

    assert result["factory_id"] == "F1"
    assert result["start_date"] == "2026-01-01"
    assert result["end_date"] == "2026-07-31"


# ── tier / balance aggregation ─────────────────────────────────


@pytest.mark.asyncio
async def test_member_profile_tier_distribution_and_totals():
    tier_rows = [
        {"tier": "花椒粉", "member_count": 18430, "total_balance": 125000.50},
        {"tier": "银卡", "member_count": 87, "total_balance": 3200.00},
        {"tier": "金花椒", "member_count": 7, "total_balance": 900.00},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert result["data_available"] is True
    assert result["member_count"] == 18430 + 87 + 7
    assert result["total_balance"] == pytest.approx(125000.50 + 3200.00 + 900.00)
    assert len(result["tier_distribution"]) == 3
    assert result["tier_distribution"][0]["tier"] == "花椒粉"
    assert result["tier_distribution"][0]["member_count"] == 18430
    assert result["tier_distribution"][0]["total_balance"] == 125000.50


# ── birth-month distribution ────────────────────────────────────


@pytest.mark.asyncio
async def test_member_profile_birth_month_distribution_excludes_unknown():
    """The SQL itself filters birth_month BETWEEN 1 AND 12 — the 0/未知
    sentinel row never appears in birth_month_distribution (it's meant for
    生日营销 targeting, where an unknown month isn't actionable)."""
    birth_rows = [
        {"birth_month": 1, "member_count": 900},
        {"birth_month": 6, "member_count": 1500},
        {"birth_month": 12, "member_count": 1100},
    ]
    conn = _FakeConn(data_available=True, birth_rows=birth_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert len(result["birth_month_distribution"]) == 3
    assert result["birth_month_distribution"][0] == {"birth_month": 1, "member_count": 900}

    fetch_call = next(
        c for c in conn.calls if c[0] == "fetch" and "agg_member_birth_month" in c[1]
    )
    assert "BETWEEN 1 AND 12" in fetch_call[1]


# ── recharge trend (date-ranged) ─────────────────────────────────


@pytest.mark.asyncio
async def test_member_profile_recharge_trend_shape():
    recharge_rows = [
        {"month": "2026-01", "principal": 0.0, "bonus": 500.0},
        {"month": "2026-03", "principal": 0.0, "bonus": 4000.0},
    ]
    conn = _FakeConn(data_available=True, recharge_rows=recharge_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (date(2026, 1, 1), date(2026, 7, 31)))

    assert result["recharge_trend"] == [
        {"month": "2026-01", "principal": 0.0, "bonus": 500.0},
        {"month": "2026-03", "principal": 0.0, "bonus": 4000.0},
    ]


@pytest.mark.asyncio
async def test_member_profile_recharge_trend_respects_date_range_params():
    conn = _FakeConn(data_available=True)
    pool = _FakePool(conn)
    await member_profile(pool, "F1", (date(2026, 1, 1), date(2026, 7, 31)))

    fetch_call = next(
        c for c in conn.calls if c[0] == "fetch" and "agg_member_recharge_daily" in c[1]
    )
    sql, params = fetch_call[1], fetch_call[2]
    assert "date >= $2" in sql
    assert "date <= $3" in sql
    assert params == ["F1", date(2026, 1, 1), date(2026, 7, 31)]


@pytest.mark.asyncio
async def test_member_profile_all_history_no_date_filter():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    await member_profile(pool, "F1", (None, None))

    recharge_fetch = next(
        c for c in conn.calls if c[0] == "fetch" and "agg_member_recharge_daily" in c[1]
    )
    assert ">=" not in recharge_fetch[1] and "<=" not in recharge_fetch[1]
    assert recharge_fetch[2] == ["F1"]


@pytest.mark.asyncio
async def test_member_profile_inverted_range_raises():
    conn = _FakeConn()
    pool = _FakePool(conn)
    with pytest.raises(ValueError):
        await member_profile(pool, "F1", (date(2026, 2, 1), date(2026, 1, 1)))


# ── caveat: honest "not full RFM" disclaimer ─────────────────────


@pytest.mark.asyncio
async def test_member_profile_caveat_mentions_not_rfm():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert "RFM" in result["caveat"]
    assert "复购" in result["caveat"] or "频次" in result["caveat"]
