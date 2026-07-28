"""Test run_full_etl_with_retry — 重试 3 次 + 失败日志写入."""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch


def _make_pool_with_conn(conn):
    """Build a Mock that mimics asyncpg's `async with pool.acquire() as conn:` pattern.

    Real asyncpg.pool.acquire() returns a PoolAcquireContext (sync return,
    async context manager) — NOT a coroutine. AsyncMock's default makes
    method calls awaitable, which breaks `async with pool.acquire()`.
    """
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


@pytest.mark.asyncio
async def test_etl_retry_succeeds_first_try():
    """First attempt succeeds — no retry, no failure log."""
    from smartbi.gold.restaurant.restaurant_ops_etl import run_full_etl_with_retry

    cretas_pool = AsyncMock()
    smartbi_pool = AsyncMock()

    with patch('smartbi.gold.restaurant.restaurant_ops_etl.run_full_etl', new=AsyncMock(return_value={"ok": True})) as mock_etl:
        result = await run_full_etl_with_retry(cretas_pool, smartbi_pool, "F001")

    assert result["ok"] is True
    assert mock_etl.call_count == 1


@pytest.mark.asyncio
async def test_etl_retry_succeeds_after_one_failure():
    """First attempt fails, second succeeds — 1 retry, 1 failure log row written."""
    from smartbi.gold.restaurant.restaurant_ops_etl import run_full_etl_with_retry

    cretas_pool = AsyncMock()
    smartbi_conn = AsyncMock()
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    call_count = 0

    async def flaky(*args, **kwargs):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            raise RuntimeError("simulated transient")
        return {"ok": True}

    with patch('smartbi.gold.restaurant.restaurant_ops_etl.run_full_etl', side_effect=flaky):
        with patch('asyncio.sleep', new=AsyncMock()):  # skip backoff sleep
            result = await run_full_etl_with_retry(cretas_pool, smartbi_pool, "F001")

    assert result["ok"] is True
    assert call_count == 2
    assert smartbi_conn.execute.called  # 1 'retrying' status row written


@pytest.mark.asyncio
async def test_etl_retry_fails_all_three():
    """All 3 attempts fail — exception raised + failed_final row written."""
    from smartbi.gold.restaurant.restaurant_ops_etl import run_full_etl_with_retry

    cretas_pool = AsyncMock()
    smartbi_conn = AsyncMock()
    smartbi_pool = _make_pool_with_conn(smartbi_conn)

    with patch('smartbi.gold.restaurant.restaurant_ops_etl.run_full_etl',
               new=AsyncMock(side_effect=RuntimeError("persistent failure"))):
        with patch('asyncio.sleep', new=AsyncMock()):
            with pytest.raises(RuntimeError, match="persistent failure"):
                await run_full_etl_with_retry(cretas_pool, smartbi_pool, "F001")

    # 3 attempts means at least 3 execute calls (retrying x2 + failed_final x1)
    assert smartbi_conn.execute.call_count >= 3
