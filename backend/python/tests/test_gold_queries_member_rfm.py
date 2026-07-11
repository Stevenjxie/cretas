"""Tests for smartbi.gold.queries.member_rfm — 会员 RFM 分析 (CRM P0).

New restaurant analytics dimension (greenfield, 2026-07, 有滋有味 cohort).
Pure mock tests (no DB) — mirrors test_gold_queries_member_profile.py's
_FakeConn pattern. Deliberately does NOT hit a real Postgres instance: the
fact_member_consumption / agg_member_rfm_* / agg_member_lifecycle tables
only exist after the V20261008_01/02 migrations are applied (organizer's
job, not this session's).

Covers:
  - honest empty state (data_available=False)
  - k-anonymity (k=5) merge on rfm_tier_distribution / lifecycle_distribution
  - k-anonymity (k=5) SUPPRESSION (drop, not merge) on rfm_scatter
  - weighted-average avg_spend_interval when merging sub-5 tiers into 其他
  - "full RFM" caveat (contrast with member_profile()'s "NOT full RFM")
"""
from __future__ import annotations

import pytest

from smartbi.gold.queries import member_rfm, _MEMBER_K_ANON


class _FakeConn:
    """Routes canned rows based on which query is running.

    member_rfm issues 1 fetchrow (EXISTS availability) + 3 fetch queries
    (tier / lifecycle / scatter).
    """

    def __init__(
        self,
        *,
        data_available=True,
        tier_rows=None,
        lifecycle_rows=None,
        scatter_rows=None,
    ):
        self._data_available = data_available
        self._tier_rows = tier_rows or []
        self._lifecycle_rows = lifecycle_rows or []
        self._scatter_rows = scatter_rows or []
        self.calls = []  # (kind, sql, params)

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        if "EXISTS" in sql:
            return {"has_data": self._data_available}
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        if "FROM agg_member_rfm_tier" in sql:
            return self._tier_rows
        if "FROM agg_member_lifecycle" in sql:
            return self._lifecycle_rows
        if "FROM agg_member_rfm_segment" in sql:
            return self._scatter_rows
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
async def test_member_rfm_no_data_uploaded_is_honest():
    """Tenant with ZERO agg_member_rfm_tier rows → 未上传会员消费(RFM)数据,
    not a fabricated empty distribution."""
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    assert result["data_available"] is False
    assert result["note"] == "未上传会员消费(RFM)数据"
    assert result["member_count"] == 0
    assert result["rfm_tier_distribution"] == []
    assert result["lifecycle_distribution"] == []
    assert result["rfm_scatter"] == []
    assert result["rfm_scatter_suppressed_count"] == 0


@pytest.mark.asyncio
async def test_member_rfm_factory_id_echo():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")
    assert result["factory_id"] == "F1"


# ── tier distribution + totals ─────────────────────────────────


@pytest.mark.asyncio
async def test_member_rfm_tier_distribution_and_totals():
    tier_rows = [
        {"rfm_tier": "Champions", "member_count": 320, "total_cum_spend": 1_250_000.0, "avg_spend_interval": 12.5},
        {"rfm_tier": "Loyal", "member_count": 1500, "total_cum_spend": 3_000_000.0, "avg_spend_interval": 30.0},
        {"rfm_tier": "Lost", "member_count": 8, "total_cum_spend": 5000.0, "avg_spend_interval": 250.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    assert result["data_available"] is True
    assert result["member_count"] == 320 + 1500 + 8
    # All three tiers are >= 5, so no 其他 merge.
    tiers = {t["rfm_tier"]: t for t in result["rfm_tier_distribution"]}
    assert set(tiers) == {"Champions", "Loyal", "Lost"}
    assert tiers["Champions"]["total_cum_spend"] == 1_250_000.0
    assert tiers["Champions"]["avg_spend_interval"] == 12.5


# ── k-anonymity (k=5): rfm_tier_distribution ────────────────────


@pytest.mark.asyncio
async def test_member_rfm_kanon_merges_sub5_tiers_into_other():
    """A sub-5 exclusive tier must NOT surface its own spend total — merged
    into 其他 with the other small tiers."""
    tier_rows = [
        {"rfm_tier": "Loyal", "member_count": 1000, "total_cum_spend": 500_000.0, "avg_spend_interval": 20.0},
        {"rfm_tier": "Champions", "member_count": 1, "total_cum_spend": 888_888.0, "avg_spend_interval": 2.0},  # exclusive
        {"rfm_tier": "Lost", "member_count": 3, "total_cum_spend": 120.0, "avg_spend_interval": 300.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    tiers = {t["rfm_tier"]: t for t in result["rfm_tier_distribution"]}
    assert "Champions" not in tiers
    assert "Lost" not in tiers
    assert "其他" in tiers
    assert tiers["其他"]["member_count"] == 4  # 1 + 3, still < 5
    assert tiers["其他"]["total_cum_spend"] is None  # still < 5 → suppressed
    assert tiers["Loyal"]["total_cum_spend"] == 500_000.0  # untouched
    assert result["member_count"] == 1004


@pytest.mark.asyncio
async def test_member_rfm_kanon_other_bucket_over5_keeps_spend_and_weighted_avg():
    """If the merged 其他 bucket reaches >= 5, its summed spend AND
    member_count-weighted average interval are safe to show."""
    tier_rows = [
        {"rfm_tier": "Loyal", "member_count": 1000, "total_cum_spend": 500_000.0, "avg_spend_interval": 20.0},
        {"rfm_tier": "At Risk", "member_count": 3, "total_cum_spend": 300.0, "avg_spend_interval": 100.0},
        {"rfm_tier": "Lost", "member_count": 4, "total_cum_spend": 400.0, "avg_spend_interval": 300.0},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    other = next(t for t in result["rfm_tier_distribution"] if t["rfm_tier"] == "其他")
    assert other["member_count"] == 7  # 3 + 4 >= 5
    assert other["total_cum_spend"] == pytest.approx(700.0)
    # weighted avg = (100*3 + 300*4) / 7 = (300 + 1200) / 7 = 214.285... -> 214.3
    assert other["avg_spend_interval"] == pytest.approx(214.3, abs=0.1)


@pytest.mark.asyncio
async def test_member_rfm_kanon_null_avg_spend_interval_tolerated():
    """A tier whose avg_spend_interval is NULL (e.g. every member's
    interval_days came back NULL — shouldn't happen in practice, but the
    merge logic must not crash on it) is treated as contributing 0 to the
    weighted sum."""
    tier_rows = [
        {"rfm_tier": "Loyal", "member_count": 1000, "total_cum_spend": 500_000.0, "avg_spend_interval": 20.0},
        {"rfm_tier": "Lost", "member_count": 6, "total_cum_spend": 60.0, "avg_spend_interval": None},
    ]
    conn = _FakeConn(data_available=True, tier_rows=tier_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    tiers = {t["rfm_tier"]: t for t in result["rfm_tier_distribution"]}
    assert "Lost" in tiers  # 6 >= 5, not merged
    assert tiers["Lost"]["avg_spend_interval"] is None


# ── k-anonymity (k=5): lifecycle_distribution ───────────────────


@pytest.mark.asyncio
async def test_member_rfm_lifecycle_kanon_merges_sub5():
    lifecycle_rows = [
        {"lifecycle_stage": "活跃", "member_count": 5000, "total_balance": 300_000.0},
        {"lifecycle_stage": "新客", "member_count": 2, "total_balance": 1000.0},  # sub-5
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"rfm_tier": "Loyal", "member_count": 5002, "total_cum_spend": 0.0, "avg_spend_interval": 0.0},
    ], lifecycle_rows=lifecycle_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    stages = {s["lifecycle_stage"]: s for s in result["lifecycle_distribution"]}
    assert "新客" not in stages
    assert stages["其他"]["member_count"] == 2
    assert stages["其他"]["total_balance"] is None


# ── k-anonymity (k=5): rfm_scatter (drop, not merge) ────────────


@pytest.mark.asyncio
async def test_member_rfm_scatter_drops_sub5_buckets_and_counts_suppressed():
    scatter_rows = [
        {"r_score": 5, "f_score": 5, "m_score": 5, "member_count": 100, "avg_cum_spend": 8000.0},
        {"r_score": 1, "f_score": 1, "m_score": 1, "member_count": 3, "avg_cum_spend": 10.0},  # sub-5 -> dropped
    ]
    conn = _FakeConn(data_available=True, tier_rows=[
        {"rfm_tier": "Loyal", "member_count": 103, "total_cum_spend": 0.0, "avg_spend_interval": 0.0},
    ], scatter_rows=scatter_rows)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    buckets = {(b["r_score"], b["f_score"], b["m_score"]) for b in result["rfm_scatter"]}
    assert (5, 5, 5) in buckets
    assert (1, 1, 1) not in buckets  # dropped, not merged into any "其他" position
    assert result["rfm_scatter_suppressed_count"] == 3


def test_member_kanon_threshold_is_five():
    """Guard the documented k value so a future tweak is a conscious change."""
    assert _MEMBER_K_ANON == 5


# ── caveat: full RFM (contrast with member_profile's NOT-RFM caveat) ────


@pytest.mark.asyncio
async def test_member_rfm_caveat_asserts_full_rfm_and_kanon():
    conn = _FakeConn(data_available=False)
    pool = _FakePool(conn)
    result = await member_rfm(pool, "F1")

    assert "RFM" in result["caveat"]
    assert "Recency" in result["caveat"] or "recency" in result["caveat"].lower()
    assert "其他" in result["caveat"] or "少于 5" in result["caveat"]
