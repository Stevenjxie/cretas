"""Tests for Sprint 11.5 Phase D — restaurant_finance_etl.

Spec: docs/superpowers/specs/2026-05-23-sprint11.5-etl-design.md

Mocks asyncpg pool / connection to verify:
- REVENUE rows upserted from POS aggregate with category='营业收入'
- COST wastage rows upserted with category='食材损耗'
- COST POS×recipe rows upserted with category='食材成本'
- upload_id=0L (ETL_UPLOAD_ID_SENTINEL) on all writes
- Idempotent ON CONFLICT clause includes (factory_id, upload_id, record_date,
  record_type, category) tuple
- Date-range default = last 90 days when omitted
- Name resolution counts resolved vs unresolved POS dishes
- Empty source data returns 0 upserted without crash
- Stage failure isolated — other stages still run
"""
from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ─── Shared fixtures / helpers ─────────────────────────────────────────


def _make_pool_with_conn(conn):
    """Mimic asyncpg.Pool.acquire() → async context manager → connection.

    Real asyncpg.pool.acquire() returns a PoolAcquireContext (sync return,
    async context manager). AsyncMock makes the call awaitable which breaks
    `async with pool.acquire() as conn:`. Use plain MagicMock for acquire.
    """
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


def _make_conn_with_transaction():
    """Build an asyncpg.Connection mock supporting `async with conn.transaction()`."""
    conn = AsyncMock()
    txn_ctx = AsyncMock()
    txn_ctx.__aenter__ = AsyncMock(return_value=None)
    txn_ctx.__aexit__ = AsyncMock(return_value=None)
    conn.transaction = MagicMock(return_value=txn_ctx)
    return conn


# ─── Constants tests ───────────────────────────────────────────────────


def test_etl_sentinel_is_zero():
    """ETL sentinel must be 0 so any operator Excel upload (>= 1) wins per
    RestaurantFinancialMetricsFetcher.filterToLatestUpload max-upload semantics.
    """
    from smartbi.gold.restaurant_finance_etl import ETL_UPLOAD_ID_SENTINEL
    assert ETL_UPLOAD_ID_SENTINEL == 0


def test_category_labels_match_fetcher_contract():
    """Category strings must match the Java fetcher keyword sets.

    RestaurantFinancialMetricsFetcher.java:
      - sumRevenueCategory matches contains("收入")
      - FOOD_KEYWORDS = {"食材", "原材料", "食品", "饮料", "酒水", "菜品"}
    """
    from smartbi.gold.restaurant_finance_etl import (
        CATEGORY_COST_POS_RECIPE,
        CATEGORY_COST_WASTAGE,
        CATEGORY_REVENUE,
    )
    assert "收入" in CATEGORY_REVENUE
    assert "食材" in CATEGORY_COST_WASTAGE
    assert "食材" in CATEGORY_COST_POS_RECIPE


def test_default_backfill_days_is_90():
    """Spec §3.3: ongoing cron re-processes last 30 days; one-time backfill
    spans the POS data range. 90 days is a reasonable default for the
    on-demand admin trigger (covers a full quarter)."""
    from smartbi.gold.restaurant_finance_etl import DEFAULT_BACKFILL_DAYS
    assert DEFAULT_BACKFILL_DAYS == 90


# ─── _resolve_date_range tests ─────────────────────────────────────────


def test_resolve_date_range_both_provided_returns_as_is():
    from smartbi.gold.restaurant_finance_etl import _resolve_date_range
    s = date(2025, 1, 1)
    e = date(2025, 12, 31)
    rs, re = _resolve_date_range(s, e)
    assert rs == s
    assert re == e


def test_resolve_date_range_defaults_to_last_90_days_when_both_none():
    from smartbi.gold.restaurant_finance_etl import (
        DEFAULT_BACKFILL_DAYS,
        _resolve_date_range,
    )
    rs, re = _resolve_date_range(None, None)
    today = date.today()
    assert re == today
    assert rs == today - timedelta(days=DEFAULT_BACKFILL_DAYS)


def test_resolve_date_range_inverted_raises_value_error():
    from smartbi.gold.restaurant_finance_etl import _resolve_date_range
    with pytest.raises(ValueError, match="end_date.*invalid"):
        _resolve_date_range(date(2025, 12, 1), date(2025, 1, 1))


# ─── sync_revenue_from_pos tests ───────────────────────────────────────


@pytest.mark.asyncio
async def test_sync_revenue_upserts_daily_pos_aggregate():
    """3 days of POS data → 3 REVENUE rows upserted with category=营业收入."""
    from smartbi.gold.restaurant_finance_etl import sync_revenue_from_pos

    conn = _make_conn_with_transaction()
    conn.fetch = AsyncMock(return_value=[
        {"record_date": date(2025, 12, 1), "daily_revenue": Decimal("12345.67")},
        {"record_date": date(2025, 12, 2), "daily_revenue": Decimal("9876.54")},
        {"record_date": date(2025, 12, 3), "daily_revenue": Decimal("21111.11")},
    ])
    pool = _make_pool_with_conn(conn)

    count = await sync_revenue_from_pos(
        pool, "RES_3101_009", date(2025, 12, 1), date(2025, 12, 3),
    )

    assert count == 3

    # First call = SELECT (the fetch), second-onward = the UPSERT execute(s)
    execute_calls = conn.execute.call_args_list
    # Filter to non-tenant-GUC calls
    upsert_calls = [
        c for c in execute_calls
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    assert len(upsert_calls) == 1
    upsert_call = upsert_calls[0]
    sql = upsert_call.args[0]
    assert "INSERT INTO smart_bi_finance_data" in sql
    assert "'REVENUE'" in sql
    assert "ON CONFLICT" in sql
    # Verify args: factory_id, upload_id sentinel, category, dates, revenues
    args = upsert_call.args[1:]
    assert args[0] == "RES_3101_009"
    assert args[1] == 0  # ETL_UPLOAD_ID_SENTINEL
    assert args[2] == "营业收入"
    assert args[3] == [date(2025, 12, 1), date(2025, 12, 2), date(2025, 12, 3)]
    assert args[4] == [Decimal("12345.67"), Decimal("9876.54"), Decimal("21111.11")]


@pytest.mark.asyncio
async def test_sync_revenue_zero_rows_when_no_pos_data():
    """Empty POS → return 0, no UPSERT executed."""
    from smartbi.gold.restaurant_finance_etl import sync_revenue_from_pos

    conn = _make_conn_with_transaction()
    conn.fetch = AsyncMock(return_value=[])
    pool = _make_pool_with_conn(conn)

    count = await sync_revenue_from_pos(
        pool, "F999", date(2025, 12, 1), date(2025, 12, 31),
    )

    assert count == 0
    # No INSERT executed
    upsert_calls = [
        c for c in conn.execute.call_args_list
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    assert len(upsert_calls) == 0


# ─── sync_cost_from_wastage tests ──────────────────────────────────────


@pytest.mark.asyncio
async def test_sync_cost_from_wastage_filters_approved_status():
    """Wastage SQL must filter status='APPROVED'."""
    from smartbi.gold.restaurant_finance_etl import sync_cost_from_wastage

    cretas_conn = AsyncMock()
    cretas_conn.fetch = AsyncMock(return_value=[
        {"record_date": date(2026, 4, 1), "daily_cost": Decimal("123.45")},
        {"record_date": date(2026, 4, 2), "daily_cost": Decimal("89.10")},
    ])
    cretas_pool = _make_pool_with_conn(cretas_conn)

    smartbi_conn = _make_conn_with_transaction()
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    count = await sync_cost_from_wastage(
        cretas_pool, smartbi_pool, "RES_3101_009",
        date(2026, 4, 1), date(2026, 4, 23),
    )

    assert count == 2

    # Verify SQL queried the source
    src_query = cretas_conn.fetch.call_args.args[0]
    assert "wastage_records" in src_query
    assert "status = 'APPROVED'" in src_query
    assert "deleted_at IS NULL" in src_query

    # Verify upsert SQL
    upsert_calls = [
        c for c in smartbi_conn.execute.call_args_list
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    assert len(upsert_calls) == 1
    args = upsert_calls[0].args[1:]
    assert args[0] == "RES_3101_009"
    assert args[1] == 0  # ETL_UPLOAD_ID_SENTINEL
    assert args[2] == "食材损耗"


@pytest.mark.asyncio
async def test_sync_cost_from_wastage_empty_returns_zero():
    """No wastage rows → return 0, no UPSERT."""
    from smartbi.gold.restaurant_finance_etl import sync_cost_from_wastage

    cretas_conn = AsyncMock()
    cretas_conn.fetch = AsyncMock(return_value=[])
    cretas_pool = _make_pool_with_conn(cretas_conn)
    smartbi_conn = _make_conn_with_transaction()
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    count = await sync_cost_from_wastage(
        cretas_pool, smartbi_pool, "F999",
        date(2025, 1, 1), date(2025, 12, 31),
    )
    assert count == 0
    upsert_calls = [
        c for c in smartbi_conn.execute.call_args_list
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    assert len(upsert_calls) == 0


# ─── sync_cost_from_pos_recipe tests ───────────────────────────────────


@pytest.mark.asyncio
async def test_sync_cost_from_pos_recipe_resolves_names_and_computes_daily_cost():
    """Full happy path: 2 dishes → 2 product_types → 2 food_costs → 2 daily aggregates.

    宫保鸡丁 has qty=10 × food_cost=8 = 80, sold across 2 days (6 + 4 qty)
    红烧肉 has qty=5 × food_cost=15 = 75, sold day 1 only (5 qty)
    Expected daily cost: day1 = 6*8 + 5*15 = 48 + 75 = 123
                         day2 = 4*8 = 32
    """
    from smartbi.gold.restaurant_finance_etl import sync_cost_from_pos_recipe

    smartbi_conn = _make_conn_with_transaction()
    # Multi-call fetch: pos_rows then cost_rows
    smartbi_conn.fetch = AsyncMock(side_effect=[
        # pos_rows
        [
            {"record_date": date(2025, 12, 1), "normalized_name": "宫保鸡丁",
             "total_qty": Decimal("6")},
            {"record_date": date(2025, 12, 1), "normalized_name": "红烧肉",
             "total_qty": Decimal("5")},
            {"record_date": date(2025, 12, 2), "normalized_name": "宫保鸡丁",
             "total_qty": Decimal("4")},
        ],
        # cost_rows
        [
            {"product_source_pk": "PT-001", "food_cost": Decimal("8.0000")},
            {"product_source_pk": "PT-002", "food_cost": Decimal("15.0000")},
        ],
    ])
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    cretas_conn = AsyncMock()
    # Multi-call fetch: primary name match then alias (we'll resolve all primary)
    cretas_conn.fetch = AsyncMock(side_effect=[
        # Primary product_types lookup
        [
            {"id": "PT-001", "name": "宫保鸡丁"},
            {"id": "PT-002", "name": "红烧肉"},
        ],
        # Alias lookup — won't be called because all primary names resolved
    ])
    cretas_pool = _make_pool_with_conn(cretas_conn)

    count, resolved, unresolved = await sync_cost_from_pos_recipe(
        cretas_pool, smartbi_pool, "RES_3101_009",
        date(2025, 12, 1), date(2025, 12, 31),
    )

    assert count == 2  # 2 distinct dates with food cost
    assert resolved == 2
    assert unresolved == 0

    upsert_calls = [
        c for c in smartbi_conn.execute.call_args_list
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    assert len(upsert_calls) == 1
    args = upsert_calls[0].args[1:]
    assert args[0] == "RES_3101_009"
    assert args[1] == 0  # sentinel
    assert args[2] == "食材成本"
    upsert_dates = args[3]
    upsert_costs = args[4]
    assert upsert_dates == [date(2025, 12, 1), date(2025, 12, 2)]
    # Day 1: 6 * 8 + 5 * 15 = 48 + 75 = 123
    # Day 2: 4 * 8 = 32
    assert upsert_costs[0] == Decimal("123.0000")
    assert upsert_costs[1] == Decimal("32.0000")


@pytest.mark.asyncio
async def test_sync_cost_from_pos_recipe_unresolved_names_contribute_zero():
    """POS dish without product_types match → contributes 0 to daily cost.

    Of 2 dishes (A, B), only A resolves → only A's qty * food_cost counted.
    """
    from smartbi.gold.restaurant_finance_etl import sync_cost_from_pos_recipe

    smartbi_conn = _make_conn_with_transaction()
    smartbi_conn.fetch = AsyncMock(side_effect=[
        [
            {"record_date": date(2025, 12, 1), "normalized_name": "宫保鸡丁",
             "total_qty": Decimal("10")},
            {"record_date": date(2025, 12, 1), "normalized_name": "未登录菜",
             "total_qty": Decimal("99")},
        ],
        [
            {"product_source_pk": "PT-001", "food_cost": Decimal("8.0000")},
        ],
    ])
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    cretas_conn = AsyncMock()
    cretas_conn.fetch = AsyncMock(side_effect=[
        # Primary: only 宫保鸡丁 matches
        [{"id": "PT-001", "name": "宫保鸡丁"}],
        # Alias: empty (or table missing)
        [],
    ])
    cretas_pool = _make_pool_with_conn(cretas_conn)

    count, resolved, unresolved = await sync_cost_from_pos_recipe(
        cretas_pool, smartbi_pool, "RES_3101_009",
        date(2025, 12, 1), date(2025, 12, 1),
    )

    assert count == 1
    assert resolved == 1
    assert unresolved == 1

    upsert_calls = [
        c for c in smartbi_conn.execute.call_args_list
        if c.args and "INSERT INTO smart_bi_finance_data" in c.args[0]
    ]
    args = upsert_calls[0].args[1:]
    # Only 10 * 8 = 80, the unresolved 未登录菜 dropped
    assert args[4][0] == Decimal("80.0000")


@pytest.mark.asyncio
async def test_sync_cost_from_pos_recipe_no_pos_items_returns_zeros():
    """All POS items have product_id IS NULL → return 0 count, 0 resolved."""
    from smartbi.gold.restaurant_finance_etl import sync_cost_from_pos_recipe

    smartbi_conn = _make_conn_with_transaction()
    smartbi_conn.fetch = AsyncMock(return_value=[])
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    cretas_pool = _make_pool_with_conn(AsyncMock())

    count, resolved, unresolved = await sync_cost_from_pos_recipe(
        cretas_pool, smartbi_pool, "F999",
        date(2025, 1, 1), date(2025, 12, 31),
    )
    assert count == 0
    assert resolved == 0
    assert unresolved == 0


# ─── run_full_finance_etl orchestration tests ──────────────────────────


@pytest.mark.asyncio
async def test_run_full_finance_etl_runs_all_three_stages_on_success():
    """Happy path — all 3 stages run, stats counts populated."""
    from smartbi.gold.restaurant_finance_etl import (
        FinanceEtlStats,
        run_full_finance_etl,
    )

    cretas_pool = AsyncMock()
    smartbi_pool = AsyncMock()

    with patch(
        "smartbi.gold.restaurant_finance_etl.sync_revenue_from_pos",
        new=AsyncMock(return_value=365),
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_wastage",
        new=AsyncMock(return_value=6),
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_pos_recipe",
        new=AsyncMock(return_value=(300, 120, 30)),
    ):
        stats = await run_full_finance_etl(
            cretas_pool, smartbi_pool, "RES_3101_009",
            date(2025, 1, 1), date(2025, 12, 31),
        )

    assert isinstance(stats, FinanceEtlStats)
    assert stats.revenue_upserted == 365
    assert stats.cost_wastage_upserted == 6
    assert stats.cost_pos_recipe_upserted == 300
    assert stats.pos_dish_resolved == 120
    assert stats.pos_dish_unresolved == 30
    assert stats.errors == []


@pytest.mark.asyncio
async def test_run_full_finance_etl_stage_failure_isolated():
    """If revenue stage fails, wastage + POS×recipe still run."""
    from smartbi.gold.restaurant_finance_etl import run_full_finance_etl

    cretas_pool = AsyncMock()
    smartbi_pool = AsyncMock()

    with patch(
        "smartbi.gold.restaurant_finance_etl.sync_revenue_from_pos",
        new=AsyncMock(side_effect=RuntimeError("boom revenue")),
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_wastage",
        new=AsyncMock(return_value=5),
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_pos_recipe",
        new=AsyncMock(return_value=(10, 5, 0)),
    ):
        stats = await run_full_finance_etl(
            cretas_pool, smartbi_pool, "RES_3101_009",
            date(2025, 1, 1), date(2025, 12, 31),
        )

    assert stats.revenue_upserted == 0
    assert stats.cost_wastage_upserted == 5
    assert stats.cost_pos_recipe_upserted == 10
    assert len(stats.errors) == 1
    assert "revenue" in stats.errors[0]


@pytest.mark.asyncio
async def test_run_full_finance_etl_defaults_date_range_when_none():
    """When start/end dates omitted, default = last 90 days."""
    from smartbi.gold.restaurant_finance_etl import run_full_finance_etl

    cretas_pool = AsyncMock()
    smartbi_pool = AsyncMock()
    captured_dates = {}

    async def cap_revenue(pool, fid, s, e):
        captured_dates["revenue"] = (s, e)
        return 0

    with patch(
        "smartbi.gold.restaurant_finance_etl.sync_revenue_from_pos",
        new=cap_revenue,
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_wastage",
        new=AsyncMock(return_value=0),
    ), patch(
        "smartbi.gold.restaurant_finance_etl.sync_cost_from_pos_recipe",
        new=AsyncMock(return_value=(0, 0, 0)),
    ):
        await run_full_finance_etl(cretas_pool, smartbi_pool, "F001")

    s, e = captured_dates["revenue"]
    assert e == date.today()
    assert (e - s).days == 90


# ─── Retry wrapper tests ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_finance_etl_retry_succeeds_first_try():
    """First attempt succeeds → no failure log, no sleep."""
    from smartbi.gold.restaurant_finance_etl import (
        FinanceEtlStats,
        run_full_finance_etl_with_retry,
    )

    cretas_pool = AsyncMock()
    smartbi_pool = AsyncMock()

    with patch(
        "smartbi.gold.restaurant_finance_etl.run_full_finance_etl",
        new=AsyncMock(return_value=FinanceEtlStats(revenue_upserted=1)),
    ) as mock_etl:
        stats = await run_full_finance_etl_with_retry(
            cretas_pool, smartbi_pool, "F001",
        )
    assert stats.revenue_upserted == 1
    assert mock_etl.call_count == 1


@pytest.mark.asyncio
async def test_finance_etl_retry_fails_all_three():
    """Persistent failure → 3 attempts, exception re-raised."""
    from smartbi.gold.restaurant_finance_etl import (
        run_full_finance_etl_with_retry,
    )

    cretas_pool = AsyncMock()
    smartbi_conn = AsyncMock()
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    with patch(
        "smartbi.gold.restaurant_finance_etl.run_full_finance_etl",
        new=AsyncMock(side_effect=RuntimeError("persistent")),
    ):
        with patch("asyncio.sleep", new=AsyncMock()):
            with pytest.raises(RuntimeError, match="persistent"):
                await run_full_finance_etl_with_retry(
                    cretas_pool, smartbi_pool, "F001",
                )

    # 3 attempts → at least 3 failure-log inserts (retrying x2 + failed_final)
    assert smartbi_conn.execute.call_count >= 3


# ─── SQL constant regression tests (mirror restaurant_ops_etl pattern) ─


def test_no_python_lint_markers_in_finance_etl_sql():
    """Regression for #539-style bug — Python lint annotation leaked into SQL."""
    import re

    import smartbi.gold.restaurant_finance_etl as etl

    lint_marker = re.compile(r"#\s*(noqa|type\s*:|pyright|TODO|FIXME|XXX|HACK)\b")
    for name, value in vars(etl).items():
        if name.endswith("_SQL") and isinstance(value, str):
            assert not lint_marker.search(value), (
                f"Python lint marker leaked into SQL constant {name}: {value!r}"
            )


def test_no_pound_chars_in_finance_etl_sql():
    """'#' is not a SQL comment marker. Any '#' in raw SQL is suspicious."""
    import smartbi.gold.restaurant_finance_etl as etl

    for name, value in vars(etl).items():
        if name.endswith("_SQL") and isinstance(value, str):
            assert "#" not in value, (
                f"'#' char in SQL constant {name} — PG doesn't treat '#' as comment"
            )


def test_upsert_sql_contains_required_columns():
    """Sanity check — UPSERT SQL must reference all keys of the unique index."""
    from smartbi.gold.restaurant_finance_etl import (
        _UPSERT_COST_SQL,
        _UPSERT_REVENUE_SQL,
    )

    for name, sql in [
        ("revenue", _UPSERT_REVENUE_SQL),
        ("cost", _UPSERT_COST_SQL),
    ]:
        assert "ON CONFLICT" in sql, f"{name}: missing ON CONFLICT"
        # All 5 cols of the partial unique index uq_smart_bi_finance_data_etl
        for col in ("factory_id", "upload_id", "record_date", "record_type", "category"):
            assert col in sql, f"{name}: missing column {col} in upsert"
        # Partial index predicate
        assert "WHERE deleted_at IS NULL" in sql, (
            f"{name}: missing partial-index predicate WHERE deleted_at IS NULL"
        )


# ─── Admin endpoint test ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_finance_etl_trigger_endpoint_returns_job_id_for_admin():
    """Sprint 11.5 Phase D — POST /finance-etl/trigger returns jobId for admin."""
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient

    from smartbi.api.restaurant_etl_admin import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = "platform_admin"
        request.state.factory_id = "RES_3101_009"
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi/restaurant/etl")
    client = TestClient(app, raise_server_exceptions=False)

    with patch(
        "smartbi.api.restaurant_etl_admin._enqueue_finance_job",
        new=AsyncMock(return_value=None),
    ):
        resp = client.post(
            "/api/smartbi/restaurant/etl/finance-etl/trigger",
            json={
                "factoryId": "RES_3101_009",
                "startDate": "2025-01-01",
                "endDate": "2025-12-31",
            },
        )

    assert resp.status_code == 200
    body = resp.json()
    assert "jobId" in body
    assert body["factoryId"] == "RES_3101_009"
    assert body["startDate"] == "2025-01-01"
    assert body["endDate"] == "2025-12-31"
    assert body.get("status") in ("queued", "running")


@pytest.mark.asyncio
async def test_finance_etl_trigger_rejects_inverted_date_range():
    """endDate before startDate → 400."""
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient

    from smartbi.api.restaurant_etl_admin import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = "platform_admin"
        request.state.factory_id = "F001"
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi/restaurant/etl")
    client = TestClient(app, raise_server_exceptions=False)

    resp = client.post(
        "/api/smartbi/restaurant/etl/finance-etl/trigger",
        json={
            "factoryId": "F001",
            "startDate": "2025-12-31",
            "endDate": "2025-01-01",
        },
    )

    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_finance_etl_trigger_rejects_non_admin():
    """role='operator' → 403."""
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient

    from smartbi.api.restaurant_etl_admin import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = "operator"
        request.state.factory_id = "F001"
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi/restaurant/etl")
    client = TestClient(app, raise_server_exceptions=False)

    resp = client.post(
        "/api/smartbi/restaurant/etl/finance-etl/trigger",
        json={"factoryId": "RES_3101_009"},
    )

    assert resp.status_code == 403
