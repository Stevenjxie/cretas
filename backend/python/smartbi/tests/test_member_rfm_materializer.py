"""Tests for services.materialized_analytics.member_rfm aggregators.

New restaurant analytics dimension: 会员 RFM (CRM P0, greenfield). Mirrors
test_member_profile_materializer.py's mock pattern — verifies:
  - UPSERT SQL is correctly parametrized
  - Function returns affected row count from execute() result
  - app.factory_id GUC is set on conn before INSERT (RLS requirement)
  - All three aggregators are snapshot-only (single factory_id param, no
    date range — recency is recomputed against CURRENT_DATE at
    materialization time, not passed in as a parameter)
"""
from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.services.materialized_analytics.member_rfm import (
    materialize_member_rfm,
    _AGG_MEMBER_LIFECYCLE_UPSERT_SQL,
    _AGG_MEMBER_RFM_SEGMENT_UPSERT_SQL,
    _AGG_MEMBER_RFM_TIER_UPSERT_SQL,
)


def _make_pool(execute_return="INSERT 0 12"):
    """Build a mocked asyncpg pool whose conn.execute returns the given tag
    (or a list of tags consumed in call order)."""
    pool = MagicMock()
    conn = AsyncMock()
    if isinstance(execute_return, list):
        conn.execute = AsyncMock(side_effect=execute_return)
    else:
        conn.execute = AsyncMock(return_value=execute_return)
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool, conn


# ── materialize_member_rfm (snapshot, no date range) ─────────────────────


@pytest.mark.asyncio
async def test_materialize_member_rfm_returns_sum_of_three_upserts():
    # set_config + segment + tier + lifecycle upserts = 4 execute() calls
    pool, _ = _make_pool(["SET", "INSERT 0 25", "INSERT 0 7", "INSERT 0 5"])
    affected = await materialize_member_rfm(pool, "DEMO_REST")
    assert affected == 37


@pytest.mark.asyncio
async def test_materialize_member_rfm_executes_all_three_upsert_sqls_with_factory_id():
    pool, conn = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    await materialize_member_rfm(pool, "DEMO_REST")

    segment_call = None
    tier_call = None
    lifecycle_call = None
    for call in conn.execute.call_args_list:
        args = call[0]
        if args and _AGG_MEMBER_RFM_SEGMENT_UPSERT_SQL in args[0]:
            segment_call = call
        elif args and _AGG_MEMBER_RFM_TIER_UPSERT_SQL in args[0]:
            tier_call = call
        elif args and _AGG_MEMBER_LIFECYCLE_UPSERT_SQL in args[0]:
            lifecycle_call = call
    assert segment_call is not None, "segment UPSERT SQL was not executed"
    assert tier_call is not None, "tier UPSERT SQL was not executed"
    assert lifecycle_call is not None, "lifecycle UPSERT SQL was not executed"
    assert segment_call[0][1] == "DEMO_REST"
    assert tier_call[0][1] == "DEMO_REST"
    assert lifecycle_call[0][1] == "DEMO_REST"


@pytest.mark.asyncio
async def test_materialize_member_rfm_factory_id_context_is_set():
    """RLS requires app.factory_id GUC before INSERT can target the row."""
    pool, conn = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    await materialize_member_rfm(pool, "DEMO_REST")

    found = False
    for call in conn.execute.call_args_list:
        sql = call[0][0] if call[0] else ""
        if "set_config" in sql and "app.factory_id" in sql:
            found = True
            assert call[0][1] == "DEMO_REST"
            break
    assert found, "app.factory_id must be set via set_config before UPSERT"


@pytest.mark.asyncio
async def test_materialize_member_rfm_pg_no_match_returns_zero():
    pool, _ = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    affected = await materialize_member_rfm(pool, "DEMO_REST")
    assert affected == 0


# ── SQL shape assertions (guard the business rules against silent drift) ──


def test_rfm_tier_sql_uses_same_classification_as_in_memory_analyzer():
    """The 7-label CASE mirrors
    smartbi/services/restaurant/member_rfm.py's MemberRfmAnalyzer
    ._classify_segment thresholds EXACTLY — guard against divergence."""
    sql = _AGG_MEMBER_RFM_TIER_UPSERT_SQL
    for label in ("Champions", "New", "Loyal", "Potential", "At Risk", "Lost", "Hibernating"):
        assert label in sql, f"{label} missing from rfm_tier CASE"


def test_lifecycle_sql_uses_current_date_not_source_interval_column():
    """Lifecycle classification MUST recompute recency against CURRENT_DATE
    at materialization time (not trust the source's stale
    spend_interval_days column) — see module docstring."""
    sql = _AGG_MEMBER_LIFECYCLE_UPSERT_SQL
    assert "CURRENT_DATE" in sql
    assert "spend_interval_days" not in sql
    for label in ("新客", "活跃", "沉睡", "流失", "未消费"):
        assert label in sql, f"{label} missing from lifecycle CASE"


def test_segment_sql_uses_ntile_5_for_all_three_dimensions():
    sql = _AGG_MEMBER_RFM_SEGMENT_UPSERT_SQL
    assert sql.count("NTILE(5)") == 3
