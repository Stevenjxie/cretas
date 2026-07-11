"""Tests for services.materialized_analytics.member_profile aggregators.

New restaurant analytics dimension: 会员储值 + 画像 (greenfield). Mirrors
test_daily_void_materializer.py's mock pattern — verifies:
  - UPSERT SQL is correctly parametrized
  - Function returns affected row count from execute() result
  - app.factory_id GUC is set on conn before INSERT (RLS requirement)
  - date_min / date_max passed as positional params (recharge only — the
    tier/birth-month materializer is a snapshot with no date range)
"""
from datetime import date
from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.services.materialized_analytics.member_profile import (
    materialize_member_recharge_daily,
    materialize_member_tier_profile,
    _AGG_MEMBER_BIRTH_MONTH_UPSERT_SQL,
    _AGG_MEMBER_GENDER_UPSERT_SQL,
    _AGG_MEMBER_RECHARGE_DAILY_UPSERT_SQL,
    _AGG_MEMBER_TIER_UPSERT_SQL,
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


# ── materialize_member_tier_profile (snapshot, no date range) ────────


@pytest.mark.asyncio
async def test_tier_profile_returns_sum_of_three_upserts():
    # set_config + tier + gender + birth_month upserts = 4 execute() calls
    pool, _ = _make_pool(["SET", "INSERT 0 5", "INSERT 0 3", "INSERT 0 4"])
    affected = await materialize_member_tier_profile(pool, "DEMO_REST")
    assert affected == 12


@pytest.mark.asyncio
async def test_tier_profile_executes_all_three_upsert_sqls_with_factory_id():
    pool, conn = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    await materialize_member_tier_profile(pool, "DEMO_REST")

    tier_call = None
    gender_call = None
    birth_call = None
    for call in conn.execute.call_args_list:
        args = call[0]
        if args and _AGG_MEMBER_TIER_UPSERT_SQL in args[0]:
            tier_call = call
        elif args and _AGG_MEMBER_GENDER_UPSERT_SQL in args[0]:
            gender_call = call
        elif args and _AGG_MEMBER_BIRTH_MONTH_UPSERT_SQL in args[0]:
            birth_call = call
    assert tier_call is not None, "tier UPSERT SQL was not executed"
    assert gender_call is not None, "gender UPSERT SQL was not executed"
    assert birth_call is not None, "birth_month UPSERT SQL was not executed"
    assert tier_call[0][1] == "DEMO_REST"
    assert gender_call[0][1] == "DEMO_REST"
    assert birth_call[0][1] == "DEMO_REST"


@pytest.mark.asyncio
async def test_tier_profile_factory_id_context_is_set():
    """RLS requires app.factory_id GUC before INSERT can target the row."""
    pool, conn = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    await materialize_member_tier_profile(pool, "DEMO_REST")

    found = False
    for call in conn.execute.call_args_list:
        sql = call[0][0] if call[0] else ""
        if "set_config" in sql and "app.factory_id" in sql:
            found = True
            assert call[0][1] == "DEMO_REST"
            break
    assert found, "app.factory_id must be set via set_config before UPSERT"


@pytest.mark.asyncio
async def test_tier_profile_pg_no_match_returns_zero():
    pool, _ = _make_pool(["SET", "INSERT 0 0", "INSERT 0 0", "INSERT 0 0"])
    affected = await materialize_member_tier_profile(pool, "DEMO_REST")
    assert affected == 0


# ── materialize_member_recharge_daily (date-ranged, mirrors void) ─────


@pytest.mark.asyncio
async def test_recharge_daily_returns_affected_row_count_from_pg_status():
    pool, _ = _make_pool(execute_return="INSERT 0 42")
    affected = await materialize_member_recharge_daily(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 7, 31),
    )
    assert affected == 42


@pytest.mark.asyncio
async def test_recharge_daily_upsert_sql_executed_with_correct_params():
    pool, conn = _make_pool()
    await materialize_member_recharge_daily(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 7, 31),
    )
    upsert_call = None
    for call in conn.execute.call_args_list:
        args = call[0]
        if args and _AGG_MEMBER_RECHARGE_DAILY_UPSERT_SQL in args[0]:
            upsert_call = call
            break
    assert upsert_call is not None, "UPSERT SQL was not executed"
    # Positional args: factory_id, date_min, date_max
    assert upsert_call[0][1] == "DEMO_REST"
    assert upsert_call[0][2] == date(2026, 1, 1)
    assert upsert_call[0][3] == date(2026, 7, 31)


@pytest.mark.asyncio
async def test_recharge_daily_factory_id_context_is_set():
    pool, conn = _make_pool()
    await materialize_member_recharge_daily(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 7, 31),
    )
    found = False
    for call in conn.execute.call_args_list:
        sql = call[0][0] if call[0] else ""
        if "set_config" in sql and "app.factory_id" in sql:
            found = True
            assert call[0][1] == "DEMO_REST"
            break
    assert found, "app.factory_id must be set via set_config before UPSERT"


@pytest.mark.asyncio
async def test_recharge_daily_pg_no_match_returns_zero():
    pool, _ = _make_pool(execute_return="INSERT 0 0")
    affected = await materialize_member_recharge_daily(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 1, 1),
    )
    assert affected == 0
