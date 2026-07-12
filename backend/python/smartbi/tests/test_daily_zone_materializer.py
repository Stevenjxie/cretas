"""Tests for services.materialized_analytics.daily_zone aggregator.

New restaurant analytics dimension: 区域坪效 (greenfield). Mirrors
test_daily_void_materializer.py's mock pattern — verifies:
  - UPSERT SQL is correctly parametrized
  - Function returns affected row count from execute() result
  - app.factory_id GUC is set on conn before INSERT (RLS requirement)
  - date_min / date_max passed as positional params
"""
from datetime import date
from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.services.materialized_analytics.daily_zone import (
    materialize_daily_zone,
    _AGG_DAILY_ZONE_UPSERT_SQL,
)


def _make_pool(execute_return: str = "INSERT 0 12"):
    """Build a mocked asyncpg pool whose conn.execute returns the given tag."""
    pool = MagicMock()
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=execute_return)
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool, conn


@pytest.mark.asyncio
async def test_returns_affected_row_count_from_pg_status():
    """Postgres returns 'INSERT 0 N' as the command tag; N is rows affected."""
    pool, _ = _make_pool(execute_return="INSERT 0 42")
    affected = await materialize_daily_zone(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 7, 31),
    )
    assert affected == 42


@pytest.mark.asyncio
async def test_upsert_sql_executed_with_correct_params():
    pool, conn = _make_pool()
    await materialize_daily_zone(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 7, 31),
    )
    upsert_call = None
    for call in conn.execute.call_args_list:
        args = call[0]
        if args and _AGG_DAILY_ZONE_UPSERT_SQL in args[0]:
            upsert_call = call
            break
    assert upsert_call is not None, "UPSERT SQL was not executed"
    # Positional args: factory_id, date_min, date_max
    assert upsert_call[0][1] == "DEMO_REST"
    assert upsert_call[0][2] == date(2026, 1, 1)
    assert upsert_call[0][3] == date(2026, 7, 31)


@pytest.mark.asyncio
async def test_factory_id_context_is_set():
    """RLS requires app.factory_id GUC before INSERT can target the row."""
    pool, conn = _make_pool()
    await materialize_daily_zone(
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
async def test_pg_no_match_returns_zero():
    """When fact_zone_sales has zero matching rows, UPSERT inserts 0."""
    pool, _ = _make_pool(execute_return="INSERT 0 0")
    affected = await materialize_daily_zone(
        pool, "DEMO_REST",
        date_min=date(2026, 1, 1), date_max=date(2026, 1, 1),
    )
    assert affected == 0
