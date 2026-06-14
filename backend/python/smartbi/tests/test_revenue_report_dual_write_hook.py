"""Test that run_silver_dual_write also fires the QHJ revenue Gold aggregator.

Spec §6.7 / Task D2: after backfill_upload writes bills to fact_pos_transaction,
materialize_daily_order_type_meal() refreshes the agg_daily_order_type_meal
Gold table for the just-inserted date range.
"""
from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


@pytest.mark.asyncio
async def test_dual_write_calls_aggregator_after_backfill():
    """After backfill_upload succeeds, materialize_daily_order_type_meal must be
    called with the upload's actual date range queried from fact_pos_transaction."""

    fake_stats = MagicMock(
        rows_read=10, rows_skipped_missing_required=0, rows_queued=10, pipeline=None
    )

    fake_backfill = AsyncMock(return_value=fake_stats)
    fake_aggregator = AsyncMock(return_value=5)

    # Mock pool + conn for the date-range query.
    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value={
        "min_date": date(2025, 10, 1),
        "max_date": date(2025, 10, 7),
    })
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool = MagicMock()
    pool.acquire = MagicMock(return_value=ctx)
    pool.close = AsyncMock()

    fake_create_pool = AsyncMock(return_value=pool)

    settings = MagicMock(postgres_url="postgresql://x")

    with patch("smartbi.gold.dual_write.silver_dual_write_enabled", return_value=True), \
        patch.object(__import__("asyncpg"), "create_pool", new=fake_create_pool), \
        patch("smartbi.gold.dual_write._ensure_scripts_on_path"), \
        patch("smartbi.tenant_ctx.set_pg_connection_tenant", return_value=None), \
        patch("smartbi.tenant_ctx.set_factory_id", return_value="token"), \
        patch("smartbi.tenant_ctx.reset_factory_id", return_value=None), \
        patch("smartbi.config.get_settings", return_value=settings), \
        patch("scripts.backfill_silver.backfill_upload", new=fake_backfill), \
        patch(
        "smartbi.services.materialized_analytics.daily_order_type_meal."
            "materialize_daily_order_type_meal", new=fake_aggregator):
        # Ensure `import backfill_silver` (the bare name used inside the source)
        # resolves to the same patched module.
        import sys
        sys.modules.setdefault(
            "backfill_silver",
            __import__("scripts.backfill_silver", fromlist=["backfill_upload"]),
        )

        from smartbi.gold.dual_write import run_silver_dual_write
        await run_silver_dual_write(
            factory_id="R_QINGHUAJIAO_REAL", upload_id=99,
        )

    # backfill ran first
    fake_backfill.assert_awaited_once()
    # then aggregator called with the date range from fact_pos_transaction
    fake_aggregator.assert_awaited_once()
    args = fake_aggregator.call_args
    assert args[0][1] == "R_QINGHUAJIAO_REAL"  # factory_id
    assert args[0][2] == date(2025, 10, 1)      # date_min
    assert args[0][3] == date(2025, 10, 7)      # date_max


@pytest.mark.asyncio
async def test_aggregator_skipped_when_no_rows_to_materialize():
    """If the upload had zero bill rows (rows_queued=0), the date-range query
    yields NULL → aggregator must be skipped (no work to do)."""

    fake_stats = MagicMock(
        rows_read=0, rows_skipped_missing_required=0, rows_queued=0, pipeline=None
    )

    fake_backfill = AsyncMock(return_value=fake_stats)
    fake_aggregator = AsyncMock()

    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value={"min_date": None, "max_date": None})
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool = MagicMock()
    pool.acquire = MagicMock(return_value=ctx)
    pool.close = AsyncMock()

    fake_create_pool = AsyncMock(return_value=pool)
    settings = MagicMock(postgres_url="postgresql://x")

    with patch("smartbi.gold.dual_write.silver_dual_write_enabled", return_value=True), \
        patch.object(__import__("asyncpg"), "create_pool", new=fake_create_pool), \
        patch("smartbi.gold.dual_write._ensure_scripts_on_path"), \
        patch("smartbi.tenant_ctx.set_pg_connection_tenant", return_value=None), \
        patch("smartbi.tenant_ctx.set_factory_id", return_value="token"), \
        patch("smartbi.tenant_ctx.reset_factory_id", return_value=None), \
        patch("smartbi.config.get_settings", return_value=settings), \
        patch("scripts.backfill_silver.backfill_upload", new=fake_backfill), \
        patch(
        "smartbi.services.materialized_analytics.daily_order_type_meal."
            "materialize_daily_order_type_meal", new=fake_aggregator):
        import sys
        sys.modules.setdefault(
            "backfill_silver",
            __import__("scripts.backfill_silver", fromlist=["backfill_upload"]),
        )
        from smartbi.gold.dual_write import run_silver_dual_write
        await run_silver_dual_write(factory_id="R_QINGHUAJIAO_REAL", upload_id=99)

    # No date range → aggregator not called (zero useful work).
    fake_aggregator.assert_not_called()
