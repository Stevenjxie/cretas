"""Tests for smartbi.agent.orchestrator.AgentOrchestrator.

Uses real asyncpg pool against smartbi_db plus httpx MockTransport for
the LLM call so tests are deterministic and don't burn quota.

Covers the 4 control-flow branches:
1. Cache hit → short-circuit, zero tokens, source=cache
2. Corpus cache hit → short-circuit, zero tokens, source=corpus (no LLM)
3. Budget exhausted → degraded response, source=degraded
4. Cache miss + under budget → Gold data → LLM → cache write + consume,
   source=llm, elapsed ms non-zero
"""
from __future__ import annotations

import json
from datetime import date

import httpx
import pytest
import pytest_asyncio

from smartbi.agent import (
    AgentBudgetTracker,
    AgentOrchestrator,
    NarrativeCacheService,
    RESULT_SOURCE_CACHE,
    RESULT_SOURCE_CORPUS,
    RESULT_SOURCE_DEGRADED,
    RESULT_SOURCE_LLM,
    compute_question_hash,
)
_TENANT = "TEST_ORCH_A"
_START = date(2025, 1, 1)
_END = date(2025, 12, 31)
_RANGE = (_START, _END)


@pytest_asyncio.fixture
async def pool():
    import asyncpg
    from smartbi.config import get_settings
    from smartbi.tenant_ctx import set_pg_connection_tenant
    settings = get_settings()
    if not settings.postgres_url:
        pytest.skip("No Postgres configured")
    p = await asyncpg.create_pool(
        settings.postgres_url, min_size=1, max_size=3,
        setup=set_pg_connection_tenant,
    )
    try:
        yield p
    finally:
        await p.close()


async def _reset_tenant(pool, tenant):
    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(tenant)
    async with pool.acquire() as conn:
        await conn.execute("DELETE FROM narrative_cache WHERE factory_id=$1", tenant)
        await conn.execute("DELETE FROM agent_budget_daily WHERE factory_id=$1", tenant)
        await conn.execute("DELETE FROM agent_tenant_config WHERE factory_id=$1", tenant)


async def _delete_corpus_rows_for_tenant(pool, tenant):
    """Clean up any agent_insight corpus rows seeded by these tests."""
    async with pool.acquire() as conn:
        await conn.execute(
            "DELETE FROM smart_bi_distillation_samples"
            " WHERE factory_id=$1 AND source='agent_insight'",
            tenant,
        )


def _make_mock_llm_client(fake_answer: str, total_tokens: int = 450):
    """httpx.AsyncClient with MockTransport returning a canned chat completion.

    Apr 25 2026 (E2a): Orchestrator now calls through common.llm_router which
    uses the shared common.llm_client singleton — not the http_client passed
    to the constructor. Tests that need to mock LLM responses must patch
    common.llm_client._client (see _patch_llm_client_singleton helper).
    """
    def handler(request: httpx.Request) -> httpx.Response:
        # Sanity: ensure our payload is well-formed
        body = json.loads(request.content)
        assert body["model"]
        assert body["messages"][0]["role"] == "system"
        assert body["messages"][1]["role"] == "user"
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": fake_answer}}],
                "usage": {"total_tokens": total_tokens},
            },
        )
    return httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=10.0)


_LLM_KEY_VARS = (
    "LLM_ALIYUN_B_API_KEY",
    "LLM_ALIYUN_A_API_KEY",
    "LLM_API_KEY",
    "LLM_ZHIPU_API_KEY",
    "LLM_DEEPSEEK_API_KEY",
    "DEEPSEEK_API_KEY",
)


def _patch_llm_client_singleton(mock_client):
    """Swap the module-level llm_client singleton with our mock. Returns
    a tuple (original_client, original_env) so tests can restore.

    Sets LLM_ALIYUN_B_API_KEY=test-mock-key (so call_chain tries aliyun_b
    first via the mocked transport) and clears all other provider keys
    (so the chain stops cleanly after aliyun_b's response).
    """
    import os
    from common import llm_client as _lc
    original_env = {k: os.environ.get(k) for k in _LLM_KEY_VARS}
    for k in _LLM_KEY_VARS:
        os.environ.pop(k, None)
    os.environ["LLM_ALIYUN_B_API_KEY"] = "test-mock-key"
    original_client = _lc._client
    _lc._client = mock_client
    return (original_client, original_env)


def _restore_llm_client_singleton(state):
    import os
    from common import llm_client as _lc
    original_client, original_env = state
    _lc._client = original_client
    for k, v in original_env.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v


def _orchestrator(pool, http_client):
    return AgentOrchestrator(
        pool=pool,
        llm_base_url="https://llm.test.invalid/v1",
        llm_api_key="test-key",
        llm_model="qwen-turbo-test",
        http_client=http_client,
    )


async def test_cache_hit_short_circuits_without_llm_call(pool):
    """Second call with same question returns from cache, zero tokens."""
    await _reset_tenant(pool, _TENANT)

    # Seed cache with a canned answer
    cache = NarrativeCacheService(pool)
    q = "本月营业额怎么样"
    h = compute_question_hash(q, _START.isoformat(), _END.isoformat(), _TENANT)
    await cache.put(_TENANT, h, "【缓存答案】青花椒大丸百货店表现最佳", None, tokens=300)

    # MockTransport raises if LLM actually called
    called = {"n": 0}

    def fail_on_call(req):
        called["n"] += 1
        return httpx.Response(500, json={})
    http = httpx.AsyncClient(transport=httpx.MockTransport(fail_on_call))
    original = _patch_llm_client_singleton(http)

    try:
        orch = _orchestrator(pool, http)
        result = await orch.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()

    assert result.source == RESULT_SOURCE_CACHE
    assert result.answer == "【缓存答案】青花椒大丸百货店表现最佳"
    assert result.tokens == 0
    assert called["n"] == 0  # LLM never called


async def test_budget_exhausted_returns_degraded_without_llm_call(pool):
    """When budget.blocked=true, return degraded message, no LLM call."""
    await _reset_tenant(pool, _TENANT)

    # Pin a tiny cap + prefill to exhaust
    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(_TENANT)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO agent_tenant_config (factory_id, tier, custom_cap_override)
            VALUES ($1, 'basic', 10)
            """,
            _TENANT,
        )
    tracker = AgentBudgetTracker(pool)
    await tracker.consume(_TENANT, 20)  # over cap=10 → blocked

    called = {"n": 0}

    def fail_on_call(req):
        called["n"] += 1
        return httpx.Response(500, json={})
    http = httpx.AsyncClient(transport=httpx.MockTransport(fail_on_call))
    original = _patch_llm_client_singleton(http)

    try:
        orch = _orchestrator(pool, http)
        result = await orch.answer_insight(_TENANT, "哪家店最差", _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()

    assert result.source == RESULT_SOURCE_DEGRADED
    assert "预算已用完" in result.answer
    assert result.tokens == 0
    assert called["n"] == 0


async def test_llm_success_path_consumes_tokens_and_caches(pool):
    """Happy path: Gold → LLM → consume + cache. Second call hits cache."""
    await _reset_tenant(pool, _TENANT)
    http = _make_mock_llm_client(
        fake_answer="青花椒大丸百货店营收 ¥7.43M 占比最高。建议：...",
        total_tokens=512,
    )
    original = _patch_llm_client_singleton(http)
    try:
        orch = _orchestrator(pool, http)
        q = "给我本年 Top 门店分析"
        r1 = await orch.answer_insight(_TENANT, q, _RANGE)
        assert r1.source == RESULT_SOURCE_LLM
        assert r1.tokens == 512
        assert r1.tokens_used_today == 512
        assert r1.elapsed_ms >= 0

        # Second call same Q = cache hit
        r2 = await orch.answer_insight(_TENANT, q, _RANGE)
        assert r2.source == RESULT_SOURCE_CACHE
        assert r2.tokens == 0
        # Cached answer must match what we returned
        assert r2.answer == r1.answer
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()


async def test_llm_failure_returns_degraded(pool):
    """Upstream HTTP 500 → degraded response, no token consumption.

    Note (Apr 25 E2a, updated #580): call_chain treats non-200 / non-403/429
    as falling through to next provider, then RuntimeError if all exhaust.
    With only aliyun_b mock-keyed, the chain runs aliyun_b once → falls to
    aliyun_a (no key, skip) → zhipu (no key, skip) →
    aliyun_a_deepseek (no key, skip) → RuntimeError. Orchestrator catches
    and returns degraded.
    """
    await _reset_tenant(pool, _TENANT)

    def always_500(req):
        return httpx.Response(500, json={"error": "upstream down"})
    http = httpx.AsyncClient(transport=httpx.MockTransport(always_500))
    original = _patch_llm_client_singleton(http)

    try:
        orch = _orchestrator(pool, http)
        result = await orch.answer_insight(_TENANT, "some question", _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()

    assert result.source == RESULT_SOURCE_DEGRADED
    assert "AI" in result.answer
    assert result.tokens == 0
    # Budget row exists (check_budget ran) but tokens_used stays 0
    assert result.tokens_used_today == 0


# ---------------------------------------------------------------------------
# Corpus read-back (serve-from-corpus flywheel) tests
#
# Design: the corpus key is sha256(user_prompt) where user_prompt is the
# full Gold-data-enriched prompt built internally by the orchestrator.
# Because we cannot predict user_prompt from outside, tests use a
# ROUNDTRIP approach:
#   call-1: LLM mock returns a known answer → orchestrator captures it
#           into smart_bi_distillation_samples (await _capture_insight_distillation)
#   call-2: same factory + same question → orchestrator rebuilds identical
#           user_prompt (identical Gold data) → identical hash → corpus HIT
# ---------------------------------------------------------------------------


async def test_corpus_cache_hit_skips_llm(pool):
    """Corpus hit (today's agent_insight row) short-circuits before LLM.

    Source must be RESULT_SOURCE_CORPUS, tokens=0, and the LLM mock must
    never be called on the second identical request.
    """
    await _reset_tenant(pool, _TENANT)
    await _delete_corpus_rows_for_tenant(pool, _TENANT)

    q = "本月门店表现如何"
    fake_answer = "【语料命中】青花椒大丸百货店表现最佳，建议加大推广。"

    # Call-1: LLM returns fake_answer → orchestrator captures corpus row
    http1 = _make_mock_llm_client(fake_answer, total_tokens=300)
    original = _patch_llm_client_singleton(http1)
    try:
        orch = _orchestrator(pool, http1)
        r1 = await orch.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http1.aclose()

    assert r1.source == RESULT_SOURCE_LLM, (
        f"Call-1 should hit LLM (seeding corpus), got {r1.source!r}"
    )

    # Call-2: same factory + same question, LLM must NOT be called
    llm_called = {"n": 0}

    def fail_on_call(req):
        llm_called["n"] += 1
        return httpx.Response(500, json={})

    http2 = httpx.AsyncClient(transport=httpx.MockTransport(fail_on_call))
    original2 = _patch_llm_client_singleton(http2)
    try:
        orch2 = _orchestrator(pool, http2)
        r2 = await orch2.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original2)
        await http2.aclose()
        await _delete_corpus_rows_for_tenant(pool, _TENANT)

    assert r2.source == RESULT_SOURCE_CORPUS, (
        f"Expected 'corpus' on call-2 but got '{r2.source}' — "
        "corpus read-back may not be wired or capture did not persist"
    )
    assert r2.answer == fake_answer
    assert r2.tokens == 0
    assert llm_called["n"] == 0, "LLM must not be called on corpus hit"


async def test_corpus_cache_miss_falls_through_to_llm(pool):
    """When there's no corpus row, the flow reaches LLM as normal."""
    await _reset_tenant(pool, _TENANT)
    await _delete_corpus_rows_for_tenant(pool, _TENANT)
    q = "折扣活动效果如何"
    fake_answer = "折扣活动带来约 15% 营业额增长，建议持续。"
    http = _make_mock_llm_client(fake_answer, total_tokens=300)
    original = _patch_llm_client_singleton(http)
    try:
        orch = _orchestrator(pool, http)
        result = await orch.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()
        await _delete_corpus_rows_for_tenant(pool, _TENANT)

    assert result.source == RESULT_SOURCE_LLM
    assert result.tokens == 300


async def test_corpus_cache_hit_takes_priority_over_budget_check(pool):
    """Corpus hit fires before the budget check — so even an exhausted budget
    tenant gets served from corpus (no additional token consumption).

    Roundtrip strategy:
      step-1: call with normal budget → LLM → captures corpus row
      step-2: exhaust budget
      step-3: call again → corpus hit (before budget check) → source=corpus
    """
    await _reset_tenant(pool, _TENANT)
    await _delete_corpus_rows_for_tenant(pool, _TENANT)

    q = "客单价走势"
    fake_answer = "客单价上周平均 ¥68，本周 ¥71，建议继续推高价值套餐。"

    # Step-1: seed the corpus via a normal LLM call
    http1 = _make_mock_llm_client(fake_answer, total_tokens=100)
    original1 = _patch_llm_client_singleton(http1)
    try:
        orch1 = _orchestrator(pool, http1)
        r1 = await orch1.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original1)
        await http1.aclose()

    assert r1.source == RESULT_SOURCE_LLM, (
        f"Step-1 (corpus seed) should be RESULT_SOURCE_LLM, got {r1.source!r}"
    )

    # Step-2: exhaust budget (after seeding, so step-1 is not blocked)
    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(_TENANT)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO agent_tenant_config (factory_id, tier, custom_cap_override)
            VALUES ($1, 'basic', 5)
            ON CONFLICT (factory_id) DO UPDATE SET custom_cap_override = EXCLUDED.custom_cap_override
            """,
            _TENANT,
        )
    tracker = AgentBudgetTracker(pool)
    await tracker.consume(_TENANT, 20)  # over cap=5 → blocked

    # Step-3: call with exhausted budget — corpus should fire before budget gate
    llm_called = {"n": 0}

    def fail_on_call(req):
        llm_called["n"] += 1
        return httpx.Response(500, json={})

    http2 = httpx.AsyncClient(transport=httpx.MockTransport(fail_on_call))
    original2 = _patch_llm_client_singleton(http2)
    try:
        orch2 = _orchestrator(pool, http2)
        result = await orch2.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original2)
        await http2.aclose()
        await _delete_corpus_rows_for_tenant(pool, _TENANT)

    assert result.source == RESULT_SOURCE_CORPUS
    assert result.tokens == 0
    assert llm_called["n"] == 0


async def test_corpus_feedback_down_demoted_row_falls_through_to_llm(pool):
    """A corpus row with feedback_down >= threshold must be skipped (G4 serve-skip).
    The flow should fall through to LLM and generate a fresh answer.

    Roundtrip strategy:
      step-1: normal LLM call → captures corpus row (quality=4, no feedback_down)
      step-2: manually UPDATE that row to inject feedback_down=2 into metadata
      step-3: call again → corpus read-back sees feedback_down >= threshold →
              serve-skip → LLM called → RESULT_SOURCE_LLM
    """
    await _reset_tenant(pool, _TENANT)
    await _delete_corpus_rows_for_tenant(pool, _TENANT)

    q = "折扣对客单价的影响"
    first_answer = "首次生成答案，稍后会被踩。"
    fresh_answer = "折扣降低了客单价但提升了订单量，净效应为正。"

    # Step-1: seed a corpus row
    http1 = _make_mock_llm_client(first_answer, total_tokens=200)
    original1 = _patch_llm_client_singleton(http1)
    try:
        orch1 = _orchestrator(pool, http1)
        r1 = await orch1.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original1)
        await http1.aclose()

    assert r1.source == RESULT_SOURCE_LLM, (
        f"Step-1 should be RESULT_SOURCE_LLM, got {r1.source!r}"
    )

    # Step-2: fetch the corpus row's input_hash and inject feedback_down=2
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT input_hash FROM smart_bi_distillation_samples"
            " WHERE factory_id=$1 AND source='agent_insight'"
            " AND created_at::date = CURRENT_DATE"
            " ORDER BY created_at DESC LIMIT 1",
            _TENANT,
        )
    assert row is not None, "corpus row from step-1 was not persisted"
    corpus_hash = row["input_hash"]

    async with pool.acquire() as conn:
        await conn.execute(
            "UPDATE smart_bi_distillation_samples"
            " SET metadata = '{\"feedback_down\": 2}'::jsonb"
            " WHERE input_hash = $1",
            corpus_hash,
        )

    # Step-3: call again — demoted row must be skipped, LLM re-called
    http2 = _make_mock_llm_client(fresh_answer, total_tokens=280)
    original2 = _patch_llm_client_singleton(http2)
    try:
        orch2 = _orchestrator(pool, http2)
        result = await orch2.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original2)
        await http2.aclose()
        await _delete_corpus_rows_for_tenant(pool, _TENANT)

    # Must NOT have served the demoted corpus row
    assert result.source == RESULT_SOURCE_LLM, (
        f"Demoted corpus row should be skipped, got source={result.source!r}"
    )
    assert result.answer == fresh_answer
