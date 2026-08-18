"""Unit tests for llm_fallback_logger.

Tests exercise the public API in isolation with a mock asyncpg pool and
a mock embedding function, so they don't require DashScope or a real DB.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from smartbi.services.llm_fallback_logger import (
    LlmFallbackLogPayload, log_fallback, log_template_hit, run_capture_with_history,
    update_feedback, update_history,
)


def _make_mock_pool():
    """Return a mock asyncpg pool whose acquire().execute() records calls."""
    conn = AsyncMock()
    # update_feedback checks result.upper().startswith("UPDATE"); log_fallback
    # uses fetchval, not execute. So "UPDATE 1" is correct here even though
    # postgres sometimes returns "INSERT 0 1" — those aren't the code paths
    # under test.
    conn.execute = AsyncMock(return_value="UPDATE 1")
    conn.fetchval = AsyncMock(return_value=12345)  # fake row id
    pool = AsyncMock()
    # pool.acquire() is a context manager; make it return our mock conn
    acquire_ctx = AsyncMock()
    acquire_ctx.__aenter__ = AsyncMock(return_value=conn)
    acquire_ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = lambda: acquire_ctx
    return pool, conn


@pytest.mark.asyncio
async def test_log_fallback_writes_row_with_embedding():
    pool, conn = _make_mock_pool()
    fake_embedding = [0.1] * 768

    with patch("smartbi.services.llm_fallback_logger.get_embedding",
               new=AsyncMock(return_value=fake_embedding)):
        payload = LlmFallbackLogPayload(
            query="为什么外卖订单下降了",
            factory_id="F001",
            upload_id=4169,
            answer="9 月外卖下滑 15.8%...",
            agg_meta={"time_filter": "9月"},
            history=[{"role": "user", "content": "哪个月营业额掉了"}],
            total_wall_ms=15000,
            llm_wall_ms=14000,
        )
        row_id = await log_fallback(pool, payload)

    assert row_id == 12345
    conn.fetchval.assert_called_once()
    # The SQL should include an INSERT and RETURNING id clause
    sql = conn.fetchval.call_args[0][0]
    assert "INSERT INTO smart_bi_llm_fallback_log" in sql
    assert "RETURNING id" in sql


@pytest.mark.asyncio
async def test_log_fallback_tolerates_embedding_failure():
    """If DashScope fails, we still write the row (embedding = NULL)."""
    pool, conn = _make_mock_pool()

    with patch("smartbi.services.llm_fallback_logger.get_embedding",
               new=AsyncMock(return_value=None)):
        payload = LlmFallbackLogPayload(
            query="test", factory_id="F001", upload_id=1,
            answer="abc", agg_meta={}, history=None,
            total_wall_ms=100, llm_wall_ms=50,
        )
        row_id = await log_fallback(pool, payload)

    assert row_id == 12345
    # Embedding argument passed to SQL should be None
    args = conn.fetchval.call_args[0]
    # positional args after SQL: query, factory_id, upload_id, answer,
    # agg_meta_json, query_embedding, history_json, total_wall_ms, llm_wall_ms
    assert args[6] is None  # query_embedding position


@pytest.mark.asyncio
async def test_log_template_hit_writes_agg_meta_for_learning_context():
    pool, conn = _make_mock_pool()

    # get_embedding is mocked (returns None) so this test doesn't depend on
    # a real embedding model being loadable in the unit-test process --
    # embedding behaviour itself is covered by the dedicated tests below.
    with patch("smartbi.services.llm_fallback_logger.get_embedding",
               new=AsyncMock(return_value=None)):
        row_id = await log_template_hit(
            pool,
            query="哪些菜要先查BOM和盘点损耗？",
            factory_id="F001",
            upload_id=None,
            template_code="restaurant_owner_action:cost_margin",
            answer="一句话结论：成本/毛利异常",
            total_wall_ms=44,
            agg_meta={
                "source": "restaurant_owner_action",
                "scenario": "cost_margin",
                "chart_titles": ["菜品收入、食材成本、毛利差多少"],
                "learning_policy": "capture_for_feedback_and_review_only",
            },
        )

    assert row_id == 12345
    args = conn.fetchval.call_args[0]
    assert "agg_meta" in args[0]
    assert args[5] is not None
    assert "cost_margin" in args[5]
    assert args[6] == "restaurant_owner_action:cost_margin"


# ─── 飞轮 A 第 10 项 (2026-08-18): log_template_hit 补 embedding ───────────

@pytest.mark.asyncio
async def test_log_template_hit_writes_embedding_when_available():
    """This is the exact gap the prod probe found: `smart_bi_llm_fallback_log`
    had query_embedding=0/11958 because ALL restaurant production traffic
    goes through log_template_hit (never log_fallback), and log_template_hit
    never computed one. Mutation target: delete the `emb = await
    get_embedding(query)` line / the `query_embedding` column from the INSERT
    and this test must fail."""
    pool, conn = _make_mock_pool()
    fake_embedding = [0.2] * 768

    with patch("smartbi.services.llm_fallback_logger.get_embedding",
               new=AsyncMock(return_value=fake_embedding)):
        row_id = await log_template_hit(
            pool, query="哪个时段生意最好", factory_id="MOCK_REST", upload_id=None,
            template_code="RESTAURANT_OPS_DAYPART_PERFORMANCE",
            answer="午市最好", total_wall_ms=10,
        )

    assert row_id == 12345
    sql = conn.fetchval.call_args[0][0]
    assert "query_embedding" in sql
    args = conn.fetchval.call_args[0]
    # last positional arg is the embedding literal (appended after
    # total_wall_ms, keeping every existing positional-index assertion in
    # this file valid)
    embedding_arg = args[-1]
    assert embedding_arg is not None
    assert embedding_arg.startswith("[0.2,")


@pytest.mark.asyncio
async def test_log_template_hit_tolerates_embedding_failure():
    """Fail-open: embedding service down must not block the write (same
    contract as log_fallback's own embedding failure test above)."""
    pool, conn = _make_mock_pool()

    with patch("smartbi.services.llm_fallback_logger.get_embedding",
               new=AsyncMock(return_value=None)):
        row_id = await log_template_hit(
            pool, query="q", factory_id="F001", upload_id=None,
            template_code="RESTAURANT_OPS_SALES_SUMMARY", answer="a",
            total_wall_ms=1,
        )

    assert row_id == 12345
    args = conn.fetchval.call_args[0]
    assert args[-1] is None


# ─── 飞轮 A 第 10 项: history 列 (update_history / run_capture_with_history) ─

@pytest.mark.asyncio
async def test_update_history_writes_capped_jsonb():
    pool, conn = _make_mock_pool()
    history = [{"role": "user", "content": f"turn {i}"} for i in range(30)]

    ok = await update_history(pool, log_id=999, history=history)

    assert ok is True
    conn.execute.assert_called_once()
    sql = conn.execute.call_args[0][0]
    assert "UPDATE smart_bi_llm_fallback_log" in sql
    assert "history" in sql
    args = conn.execute.call_args[0]
    # capped to last 20 -- same window `restaurant_intent_service` feeds the
    # T3 prompt, not a separately-invented number (see module docstring)
    import json as _json
    written = _json.loads(args[1])
    assert len(written) == 20
    assert written[0]["content"] == "turn 10"
    assert written[-1]["content"] == "turn 29"
    assert args[2] == 999


@pytest.mark.asyncio
async def test_update_history_is_a_noop_on_empty_history():
    """Mirrors log_fallback's own contract: no prior turns -> NULL column,
    never an empty-array write that would misleadingly read as "server saw
    history but it was blank"."""
    pool, conn = _make_mock_pool()

    ok = await update_history(pool, log_id=1, history=None)

    assert ok is False
    conn.execute.assert_not_called()

    ok2 = await update_history(pool, log_id=1, history=[])
    assert ok2 is False
    conn.execute.assert_not_called()


@pytest.mark.asyncio
async def test_run_capture_with_history_sequences_update_after_insert():
    """The whole reason this helper exists instead of two independent
    `asyncio.create_task` calls: the history UPDATE must target the EXACT
    row id the capture just wrote, not a `(factory_id, query)` guess that
    could race the INSERT. Assert the capture is awaited BEFORE the history
    UPDATE fires, using a call-order-recording pool."""
    calls = []

    async def fake_capture():
        calls.append("capture")
        return 555

    pool, conn = _make_mock_pool()

    async def recording_execute(*args, **kwargs):
        calls.append("history_update")
        return "UPDATE 1"

    conn.execute = recording_execute

    log_id = await run_capture_with_history(
        fake_capture(), pool, [{"role": "user", "content": "hi"}],
    )

    assert log_id == 555
    assert calls == ["capture", "history_update"]


@pytest.mark.asyncio
async def test_run_capture_with_history_skips_update_when_capture_returned_none():
    """Capture itself failed (DB down / caught exception inside
    log_intent_capture) -- there is no row id to attach history to, and
    this must not raise or attempt an UPDATE with id=None."""
    pool, conn = _make_mock_pool()

    async def fake_capture():
        return None

    log_id = await run_capture_with_history(fake_capture(), pool, [{"role": "user", "content": "hi"}])

    assert log_id is None
    conn.execute.assert_not_called()


@pytest.mark.asyncio
async def test_run_capture_with_history_swallows_update_failure():
    """History attach is best-effort: if the UPDATE itself raises, the
    capture's return value (the row id) must still come back to the caller
    -- a logging enhancement must never turn into a new failure mode for
    the fire-and-forget capture task."""
    pool, conn = _make_mock_pool()
    conn.execute = AsyncMock(side_effect=RuntimeError("connection lost"))

    async def fake_capture():
        return 777

    log_id = await run_capture_with_history(fake_capture(), pool, [{"role": "user", "content": "hi"}])

    assert log_id == 777


@pytest.mark.asyncio
async def test_update_feedback_sets_value_and_comment():
    pool, conn = _make_mock_pool()

    updated = await update_feedback(pool, log_id=12345, value=-1, comment="数字不对")

    assert updated is True
    conn.execute.assert_called_once()
    sql = conn.execute.call_args[0][0]
    assert "UPDATE smart_bi_llm_fallback_log" in sql
    assert "user_feedback" in sql
    assert "feedback_comment" in sql


@pytest.mark.asyncio
async def test_update_feedback_rejects_bad_values():
    pool, _ = _make_mock_pool()

    with pytest.raises(ValueError):
        await update_feedback(pool, log_id=1, value=5, comment=None)  # out of range


@pytest.mark.asyncio
async def test_update_feedback_scopes_by_factory_when_provided():
    """When factory_id is passed, SQL must include factory_id filter."""
    pool, conn = _make_mock_pool()
    await update_feedback(pool, log_id=42, value=1, comment=None, factory_id="F001")
    sql = conn.execute.call_args[0][0]
    assert "factory_id = $4" in sql
    # positional args: value, comment, log_id, factory_id
    args = conn.execute.call_args[0]
    assert args[1] == 1
    assert args[3] == 42
    assert args[4] == "F001"


@pytest.mark.asyncio
async def test_update_feedback_returns_false_on_zero_rows():
    """UPDATE 0 → row didn't match (wrong id or wrong factory) → return False."""
    pool, conn = _make_mock_pool()
    conn.execute = AsyncMock(return_value="UPDATE 0")
    ok = await update_feedback(pool, log_id=42, value=1, comment=None)
    assert ok is False


@pytest.mark.asyncio
async def test_update_feedback_raises_on_db_error():
    """DB errors should propagate so the admin endpoint can return 500."""
    pool, conn = _make_mock_pool()
    # Simulate asyncpg raising
    conn.execute = AsyncMock(side_effect=RuntimeError("connection lost"))

    with pytest.raises(RuntimeError, match="connection lost"):
        await update_feedback(pool, log_id=1, value=1, comment=None)
