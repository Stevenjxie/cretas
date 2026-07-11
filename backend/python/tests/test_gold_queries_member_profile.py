"""Tests for smartbi.gold.queries.member_profile — 会员储值 + 画像.

New restaurant analytics dimension (greenfield, 2026-07). Pure mock tests
(no DB) — mirrors tests/test_gold_queries_void.py's _FakeConn pattern.
Deliberately does NOT hit a real Postgres instance: the dim_member /
fact_member_recharge / agg_member_* tables only exist after the
V20261007_01/_02 migrations are applied (organizer's job, not this
session's).

Covers the Fable PII-gate SHOULD-FIX items:
  - F2: k-anonymity (k=5) suppression on tier / gender / birth-month
  - F3: gender_distribution (性别画像)
  - F4: birth_month coverage disclosure + recharge store count
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import member_profile, _MEMBER_K_ANON


class _FakeConn:
    """Routes canned rows based on which query is running.

    member_profile issues 2 fetchrow (EXISTS availability + recharge store
    count) + 4 fetch queries (tier / gender / birth-month / recharge trend).
    """

    def __init__(
        self,
        *,
        data_available=True,
        tier_rows=None,
        gender_rows=None,
        birth_rows=None,
        recharge_rows=None,
        recharge_store_count=0,
    ):
        self._data_available = data_available
        self._tier_rows = tier_rows or []
        self._gender_rows = gender_rows or []
        self._birth_rows = birth_rows or []
        self._recharge_rows = recharge_rows or []
        self._recharge_store_count = recharge_store_count
        self.calls = []  # (kind, sql, params)

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        if "EXISTS" in sql:
            return {"has_data": self._data_available}
        if "COUNT(DISTINCT store_id)" in sql:
            return {"n": self._recharge_store_count}
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        if "FROM agg_member_tier" in sql:
            return self._tier_rows
        if "FROM agg_member_gender" in sql:
            return self._gender_rows
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
    assert result["total_balance"] is None  # k-anon: 0 members < 5 → null balance
    assert result["tier_distribution"] == []
    assert result["gender_distribution"] == []
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
    # All three tiers are >= 5, so no 其他 merge.
    assert len(result["tier_distribution"]) == 3
    assert result["tier_distribution"][0]["tier"] == "花椒粉"
    assert result["tier_distribution"][0]["member_count"] == 18430
    assert result["tier_distribution"][0]["total_balance"] == 125000.50


# ── F2: k-anonymity (k=5) ───────────────────────────────────────


@pytest.mark.asyncio
async def test_member_profile_kanon_merges_sub5_tiers_into_other():
    """A sub-5 exclusive VIP tier must NOT surface its own balance — it's
    merged into 其他 with the other small tiers (Fable F2)."""
    tier_rows = [
        {"tier": "花椒粉", "member_count": 1000, "total_balance": 50000.0},
        {"tier": "钻石VIP", "member_count": 1, "total_balance": 88888.0},  # exclusive VIP
        {"tier": "黑金", "member_count": 3, "total_balance": 12000.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    tiers = {t["tier"]: t for t in result["tier_distribution"]}
    # The 1-member VIP tier name never appears — merged into 其他.
    assert "钻石VIP" not in tiers
    assert "黑金" not in tiers
    assert "其他" in tiers
    # 其他 = 1 + 3 = 4 members (still < 5) → balance MUST be nulled.
    assert tiers["其他"]["member_count"] == 4
    assert tiers["其他"]["total_balance"] is None
    # The big tier is untouched.
    assert tiers["花椒粉"]["total_balance"] == 50000.0
    # Grand total still counts everyone.
    assert result["member_count"] == 1004


@pytest.mark.asyncio
async def test_member_profile_kanon_other_bucket_over5_keeps_balance():
    """If the merged 其他 bucket reaches >= 5, its summed balance is safe to
    show (no single member is attributable)."""
    tier_rows = [
        {"tier": "花椒粉", "member_count": 1000, "total_balance": 50000.0},
        {"tier": "银卡", "member_count": 3, "total_balance": 300.0},
        {"tier": "金卡", "member_count": 4, "total_balance": 700.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    other = next(t for t in result["tier_distribution"] if t["tier"] == "其他")
    assert other["member_count"] == 7  # 3 + 4 >= 5
    assert other["total_balance"] == pytest.approx(1000.0)


@pytest.mark.asyncio
async def test_member_profile_kanon_total_balance_nulled_when_tenant_tiny():
    """A tenant with < 5 total members exposes no aggregate balance."""
    tier_rows = [
        {"tier": "花椒粉", "member_count": 3, "total_balance": 999.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert result["member_count"] == 3
    assert result["total_balance"] is None
    # The sole sub-5 tier is merged into 其他 with a nulled balance too.
    other = next(t for t in result["tier_distribution"] if t["tier"] == "其他")
    assert other["member_count"] == 3
    assert other["total_balance"] is None


@pytest.mark.asyncio
async def test_member_profile_kanon_drops_sub5_birth_months():
    """Birth-month buckets with < 5 members are dropped from the histogram and
    counted in birth_month_suppressed_count (Fable F2)."""
    birth_rows = [
        {"birth_month": 0, "member_count": 100},   # unknown sentinel
        {"birth_month": 1, "member_count": 900},
        {"birth_month": 2, "member_count": 3},      # sub-5 → suppressed
        {"birth_month": 6, "member_count": 1500},
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"tier": "花椒粉", "member_count": 2503, "total_balance": 0.0},
    ], birth_rows=birth_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    months = {b["birth_month"] for b in result["birth_month_distribution"]}
    assert months == {1, 6}       # month 2 (count 3) dropped
    assert 0 not in months        # unknown never in the histogram
    assert result["birth_month_suppressed_count"] == 3
    assert result["birth_month_unknown_count"] == 100


def test_member_kanon_threshold_is_five():
    """Guard the documented k value so a future tweak is a conscious change."""
    assert _MEMBER_K_ANON == 5


# ── F3: gender_distribution (性别画像) ──────────────────────────


@pytest.mark.asyncio
async def test_member_profile_gender_distribution():
    gender_rows = [
        {"gender": "女", "member_count": 8028},
        {"gender": "男", "member_count": 7639},
        {"gender": "未知", "member_count": 2857},
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"tier": "花椒粉", "member_count": 18524, "total_balance": 0.0},
    ], gender_rows=gender_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    genders = {g["gender"]: g["member_count"] for g in result["gender_distribution"]}
    assert genders == {"女": 8028, "男": 7639, "未知": 2857}
    # gender entries carry NO balance field (性别画像 has no money dimension).
    assert all("total_balance" not in g for g in result["gender_distribution"])


@pytest.mark.asyncio
async def test_member_profile_gender_kanon_merges_sub5():
    gender_rows = [
        {"gender": "女", "member_count": 800},
        {"gender": "男", "member_count": 700},
        {"gender": "其他性别", "member_count": 2},  # sub-5 → merged into 其他
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"tier": "花椒粉", "member_count": 1502, "total_balance": 0.0},
    ], gender_rows=gender_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    genders = {g["gender"]: g["member_count"] for g in result["gender_distribution"]}
    assert "其他性别" not in genders
    assert genders.get("其他") == 2


# ── F4: coverage / honesty disclosures ──────────────────────────


@pytest.mark.asyncio
async def test_member_profile_birth_coverage_pct():
    """43.3% of demo members have no 生日 → coverage must be disclosed, not
    hidden by the WHERE filter (Fable F4)."""
    birth_rows = [
        {"birth_month": 0, "member_count": 4000},   # unknown
        {"birth_month": 1, "member_count": 3000},
        {"birth_month": 2, "member_count": 3000},
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"tier": "花椒粉", "member_count": 10000, "total_balance": 0.0},
    ], birth_rows=birth_rows)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    # known = 6000, total = 10000 → 60.0%
    assert result["birth_month_coverage_pct"] == 60.0
    assert result["birth_month_unknown_count"] == 4000


@pytest.mark.asyncio
async def test_member_profile_birth_coverage_none_when_no_members():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))
    assert result["birth_month_coverage_pct"] is None


@pytest.mark.asyncio
async def test_member_profile_recharge_store_count():
    conn = _FakeConn(
        data_available=True,
        tier_rows=[{"tier": "花椒粉", "member_count": 100, "total_balance": 0.0}],
        recharge_rows=[{"month": "2026-01", "principal": 0.0, "bonus": 500.0}],
        recharge_store_count=1,
    )
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))
    assert result["recharge_store_count"] == 1


# ── recharge trend (date-ranged) ─────────────────────────────────


@pytest.mark.asyncio
async def test_member_profile_recharge_trend_shape():
    recharge_rows = [
        {"month": "2026-01", "principal": 0.0, "bonus": 500.0},
        {"month": "2026-03", "principal": 0.0, "bonus": 4000.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"tier": "花椒粉", "member_count": 100, "total_balance": 0.0},
    ], recharge_rows=recharge_rows, recharge_store_count=1)
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
async def test_member_profile_birth_query_not_month_filtered():
    """The birth query must NOT filter BETWEEN 1 AND 12 — we need the 0
    (unknown) bucket for the coverage disclosure (F4)."""
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    await member_profile(pool, "F1", (None, None))

    birth_fetch = next(
        c for c in conn.calls if c[0] == "fetch" and "agg_member_birth_month" in c[1]
    )
    assert "BETWEEN 1 AND 12" not in birth_fetch[1]


@pytest.mark.asyncio
async def test_member_profile_inverted_range_raises():
    conn = _FakeConn()
    pool = _FakePool(conn)
    with pytest.raises(ValueError):
        await member_profile(pool, "F1", (date(2026, 2, 1), date(2026, 1, 1)))


# ── caveat: honest "not full RFM" + k-anon disclaimer ────────────


@pytest.mark.asyncio
async def test_member_profile_caveat_mentions_not_rfm_and_kanon():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_profile(pool, "F1", (None, None))

    assert "RFM" in result["caveat"]
    assert "复购" in result["caveat"] or "频次" in result["caveat"]
    # k-anon disclosure present
    assert "其他" in result["caveat"] or "少于 5" in result["caveat"]
