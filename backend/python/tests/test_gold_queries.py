"""Tests for smartbi.gold.queries — Gold read-path primitives.

Week 4 Phase B v0 of Unified Data Layer v1 spec.
"""
from __future__ import annotations

from datetime import date
from decimal import Decimal
import os
from urllib.parse import urlsplit

import pytest
import pytest_asyncio

from smartbi.canonical import CanonicalRow, SilverNormalizer
from smartbi.gold import (
    GoldMaterializer,
    channel_breakdown,
    daily_trend,
    discount_breakdown,
    finance_summary,
    kpi_summary,
    top_products,
)
from smartbi.gold.triggers import UploadCompleteTrigger


_TENANT = "TEST_GQ_A"
_PG_DSN = os.getenv("SMARTBI_GOLD_PG_DSN")
_PG_DISPOSABLE_CONFIRMED = (
    os.getenv("SMARTBI_GOLD_PG_DISPOSABLE_CONFIRM") == "YES"
)

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not _PG_DSN or not _PG_DISPOSABLE_CONFIRMED,
        reason=(
            "set SMARTBI_GOLD_PG_DSN to a disposable loopback test database "
            "and SMARTBI_GOLD_PG_DISPOSABLE_CONFIRM=YES"
        ),
    ),
]


def _validated_test_dsn() -> str:
    """Return the explicit disposable DSN or fail closed.

    The old fixture used ``settings.postgres_url``. That property is always
    non-empty because Settings supplies localhost defaults, so a normal local
    test run accidentally attempted ``localhost:5432`` with an empty password.
    """
    if not _PG_DSN:
        pytest.skip("SMARTBI_GOLD_PG_DSN is not configured")

    parsed = urlsplit(_PG_DSN)
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        pytest.fail("SMARTBI_GOLD_PG_DSN must use a loopback host")
    database = parsed.path.lstrip("/")
    if "test" not in database.lower():
        pytest.fail("SMARTBI_GOLD_PG_DSN must target a database containing 'test'")
    return _PG_DSN


@pytest_asyncio.fixture
async def pool():
    import asyncpg
    from smartbi.tenant_ctx import set_pg_connection_tenant
    p = await asyncpg.create_pool(
        _validated_test_dsn(), min_size=1, max_size=3,
        setup=set_pg_connection_tenant,
    )
    try:
        yield p
    finally:
        await p.close()


@pytest_asyncio.fixture
async def clean_rows(pool):
    """Wipe tenant rows; no seeding. Caller seeds inside the test."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        async with pool.acquire() as conn:
            for t in ("agg_daily", "agg_product", "agg_channel"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
            await conn.execute("DELETE FROM fact_pos_transaction WHERE factory_id=$1", _TENANT)
            for t in ("dim_staff", "dim_product", "dim_payment_channel",
                      "dim_discount", "dim_store"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
        yield
    finally:
        async with pool.acquire() as conn:
            for t in ("agg_daily", "agg_product", "agg_channel"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
            await conn.execute("DELETE FROM fact_pos_transaction WHERE factory_id=$1", _TENANT)
            for t in ("dim_staff", "dim_product", "dim_payment_channel",
                      "dim_discount", "dim_store"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
        reset_factory_id(token)


@pytest_asyncio.fixture
async def seeded(pool):
    """3 bills across 2 stores + 2 days + fully materialized Gold."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        async with pool.acquire() as conn:
            for t in ("agg_daily", "agg_product", "agg_channel"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
            await conn.execute("DELETE FROM fact_pos_transaction WHERE factory_id=$1", _TENANT)
            for t in ("dim_staff", "dim_product", "dim_payment_channel",
                      "dim_discount", "dim_store"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)

        norm = SilverNormalizer(pool, _TENANT)
        await norm.write_row(CanonicalRow(
            factory_id=_TENANT, source_type="excel",
            store_name="S1", source_bill_no="Q1", date=date(2026, 4, 21),
            net_amount=Decimal("100"), combo_string="#x#_1份*100",
        ))
        await norm.write_row(CanonicalRow(
            factory_id=_TENANT, source_type="excel",
            store_name="S1", source_bill_no="Q2", date=date(2026, 4, 21),
            net_amount=Decimal("50"), combo_string="#y#_1份*50",
        ))
        await norm.write_row(CanonicalRow(
            factory_id=_TENANT, source_type="excel",
            store_name="S2", source_bill_no="Q3", date=date(2026, 4, 22),
            net_amount=Decimal("30"), combo_string="#x#_1份*30",
        ))
        mat = GoldMaterializer(pool, _TENANT)
        trig = UploadCompleteTrigger(date(2026, 4, 21), date(2026, 4, 22))
        await trig.fire(mat)
        yield
    finally:
        async with pool.acquire() as conn:
            for t in ("agg_daily", "agg_product", "agg_channel"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
            await conn.execute("DELETE FROM fact_pos_transaction WHERE factory_id=$1", _TENANT)
            for t in ("dim_staff", "dim_product", "dim_payment_channel",
                      "dim_discount", "dim_store"):
                await conn.execute(f"DELETE FROM {t} WHERE factory_id=$1", _TENANT)
        reset_factory_id(token)


@pytest.mark.asyncio
async def test_finance_summary_rollup(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await finance_summary(
            pool, _TENANT,
            (date(2026, 4, 21), date(2026, 4, 22)),
        )
    finally:
        reset_factory_id(token)
    assert out["factory_id"] == _TENANT
    assert out["start_date"] == "2026-04-21"
    assert out["end_date"] == "2026-04-22"
    # 100 + 50 + 30 = 180
    assert out["total_revenue"] == 180.0
    assert out["bill_count"] == 3
    assert out["store_count"] == 2
    assert out["day_count"] == 2
    # Avg bill = 180 / 3 = 60
    assert out["avg_bill_value"] == 60.0
    # Top stores: S1 has 150, S2 has 30
    assert len(out["top_stores"]) == 2
    assert out["top_stores"][0]["store_name"] == "S1"
    assert out["top_stores"][0]["revenue"] == 150.0
    assert out["top_stores"][0]["bill_count"] == 2
    assert out["top_stores"][1]["store_name"] == "S2"
    assert out["top_stores"][1]["revenue"] == 30.0


@pytest.mark.asyncio
async def test_finance_summary_empty_range_returns_zeros(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await finance_summary(
            pool, _TENANT,
            (date(1999, 1, 1), date(1999, 12, 31)),
        )
    finally:
        reset_factory_id(token)
    assert out["total_revenue"] == 0.0
    assert out["bill_count"] == 0
    assert out["store_count"] == 0
    assert out["day_count"] == 0
    assert out["avg_bill_value"] is None
    assert out["top_stores"] == []


@pytest.mark.asyncio
async def test_finance_summary_rejects_inverted_range(pool):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        with pytest.raises(ValueError, match="start .* > end"):
            await finance_summary(
                pool, _TENANT,
                (date(2026, 4, 22), date(2026, 4, 21)),
            )
    finally:
        reset_factory_id(token)


@pytest.mark.asyncio
async def test_finance_summary_top_n_cap(pool, seeded):
    """top_n_stores=1 returns only the top."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await finance_summary(
            pool, _TENANT,
            (date(2026, 4, 21), date(2026, 4, 22)),
            top_n_stores=1,
        )
    finally:
        reset_factory_id(token)
    assert len(out["top_stores"]) == 1
    assert out["top_stores"][0]["store_name"] == "S1"


# ── daily_trend ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_daily_trend_orders_ascending_by_date(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await daily_trend(pool, _TENANT, (date(2026, 4, 21), date(2026, 4, 22)))
    finally:
        reset_factory_id(token)
    pts = out["points"]
    assert len(pts) == 2
    assert pts[0]["date"] == "2026-04-21"
    assert pts[0]["revenue"] == 150.0  # S1: 100 + 50
    assert pts[0]["bill_count"] == 2
    assert pts[0]["avg_bill_value"] == 75.0
    assert pts[1]["date"] == "2026-04-22"
    assert pts[1]["revenue"] == 30.0
    assert pts[1]["bill_count"] == 1


@pytest.mark.asyncio
async def test_daily_trend_empty_range_returns_empty_points(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await daily_trend(pool, _TENANT, (date(1999, 1, 1), date(1999, 12, 31)))
    finally:
        reset_factory_id(token)
    assert out["points"] == []


# ── top_products ────────────────────────────────────────────

@pytest.mark.asyncio
async def test_top_products_ranked_by_revenue(pool, seeded):
    """Seeded fixture has bills with products x and y. x appears in Q1+Q3
    (100 + 30 = 130), y appears in Q2 (50). x should rank first."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await top_products(
            pool, _TENANT, (date(2026, 4, 1), date(2026, 4, 30)), top_n=5,
        )
    finally:
        reset_factory_id(token)
    products = out["top_products"]
    assert len(products) == 2
    assert products[0]["product_name"] == "x"
    assert products[0]["revenue"] == 130.0
    assert products[0]["qty_sold"] == 2.0
    assert products[1]["product_name"] == "y"
    assert products[1]["revenue"] == 50.0


@pytest.mark.asyncio
async def test_top_products_top_n_cap(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await top_products(
            pool, _TENANT, (date(2026, 4, 1), date(2026, 4, 30)), top_n=1,
        )
    finally:
        reset_factory_id(token)
    assert len(out["top_products"]) == 1


# ── channel_breakdown ───────────────────────────────────────

@pytest.mark.asyncio
async def test_channel_breakdown_empty_when_no_payments_seeded(pool, seeded):
    """Seeded fixture has no payments (EAV not exercised here) → empty
    channels list, total_amount=0."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await channel_breakdown(
            pool, _TENANT, (date(2026, 4, 21), date(2026, 4, 22)),
        )
    finally:
        reset_factory_id(token)
    assert out["channels"] == []
    assert out["total_amount"] == 0.0


# ── discount_breakdown ──────────────────────────────────────

@pytest.mark.asyncio
async def test_discount_breakdown_empty_when_no_discounts_seeded(pool, seeded):
    """Base seeded fixture has no discounts → empty list, total=0."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await discount_breakdown(
            pool, _TENANT, (date(2026, 4, 21), date(2026, 4, 22)),
        )
    finally:
        reset_factory_id(token)
    assert out["discounts"] == []
    assert out["total_amount"] == 0.0


@pytest.mark.asyncio
async def test_discount_breakdown_aggregates_and_ranks(pool, clean_rows):
    """Seed 2 bills, each with discounts; materialize agg_discount; verify
    GROUP BY + ranking from agg_discount."""
    from smartbi.canonical import CanonicalRow, SilverNormalizer
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        norm = SilverNormalizer(pool, _TENANT)
        await norm.write_row(CanonicalRow(
            factory_id=_TENANT, source_type="excel",
            store_name="S", source_bill_no="D1", date=date(2026, 4, 21),
            combo_string="#x#_1份*100",
            discounts=(
                ("点评98代100", Decimal("50"), 1),
                ("鱼羊鲜50元", Decimal("20"), 1),
            ),
        ))
        await norm.write_row(CanonicalRow(
            factory_id=_TENANT, source_type="excel",
            store_name="S", source_bill_no="D2", date=date(2026, 4, 22),
            combo_string="#x#_1份*100",
            discounts=(
                ("点评98代100", Decimal("30"), 1),  # same discount, diff bill
            ),
        ))
        # Materialize agg_discount for April 2026
        mat = GoldMaterializer(pool, _TENANT)
        await mat.materialize_discount(date(2026, 4, 1))

        out = await discount_breakdown(
            pool, _TENANT, (date(2026, 4, 21), date(2026, 4, 22)),
        )
    finally:
        reset_factory_id(token)
    # Expect 2 distinct discounts; 点评98代100 sums to 80 (50+30) across 2 bills
    assert len(out["discounts"]) == 2
    assert out["discounts"][0]["discount_name"] == "点评98代100"
    assert out["discounts"][0]["amount"] == 80.0
    assert out["discounts"][0]["bill_count"] == 2
    assert out["discounts"][1]["discount_name"] == "鱼羊鲜50元"
    assert out["discounts"][1]["amount"] == 20.0
    assert out["total_amount"] == 100.0


@pytest.mark.asyncio
async def test_discount_breakdown_rejects_inverted_range(pool):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        with pytest.raises(ValueError, match="start .* > end"):
            await discount_breakdown(
                pool, _TENANT, (date(2026, 4, 22), date(2026, 4, 21)),
            )
    finally:
        reset_factory_id(token)


# ── kpi_summary ─────────────────────────────────────────────

@pytest.mark.asyncio
async def test_kpi_summary_rollup(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await kpi_summary(pool, _TENANT, (date(2026, 4, 21), date(2026, 4, 22)))
    finally:
        reset_factory_id(token)
    assert out["revenue"] == 180.0
    assert out["bill_count"] == 3
    assert out["store_count"] == 2
    assert out["day_count"] == 2
    assert out["avg_bill_value"] == 60.0
    # 3 items total (one per bill); items_per_bill = 3/3 = 1.0
    assert out["item_count"] == 3
    assert out["items_per_bill"] == 1.0


@pytest.mark.asyncio
async def test_kpi_summary_empty_range_nones(pool, seeded):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        out = await kpi_summary(pool, _TENANT, (date(1999, 1, 1), date(1999, 12, 31)))
    finally:
        reset_factory_id(token)
    assert out["revenue"] == 0.0
    assert out["bill_count"] == 0
    assert out["avg_bill_value"] is None
    assert out["items_per_bill"] is None
    assert out["avg_per_capita"] is None


@pytest.mark.asyncio
async def test_inverted_range_raises_for_all_new_queries(pool):
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id
    token = set_factory_id(_TENANT)
    try:
        with pytest.raises(ValueError, match="start .* > end"):
            await daily_trend(pool, _TENANT, (date(2026, 4, 22), date(2026, 4, 21)))
        with pytest.raises(ValueError, match="start .* > end"):
            await top_products(pool, _TENANT, (date(2026, 4, 22), date(2026, 4, 21)))
        with pytest.raises(ValueError, match="start .* > end"):
            await channel_breakdown(pool, _TENANT, (date(2026, 4, 22), date(2026, 4, 21)))
        with pytest.raises(ValueError, match="start .* > end"):
            await kpi_summary(pool, _TENANT, (date(2026, 4, 22), date(2026, 4, 21)))
    finally:
        reset_factory_id(token)
