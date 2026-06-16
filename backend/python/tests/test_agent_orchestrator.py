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
from smartbi.agent.orchestrator import (
    _build_corpus_input_text,
    RESULT_SOURCE_CORPUS as _RS_CORPUS,
)
from smartbi.services.distillation_capture import compute_input_hash


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
# ---------------------------------------------------------------------------

async def _seed_corpus_row(pool, factory_id: str, question: str, start: date, end: date, answer: str):
    """Insert a today's agent_insight corpus row directly so tests can pre-seed."""
    from smartbi.services.distillation_capture import compute_input_hash as _cih
    corpus_key = _build_corpus_input_text(question, start.isoformat(), end.isoformat(), factory_id)
    h = _cih(corpus_key)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO smart_bi_distillation_samples
                (source, business_type, factory_id, task_type,
                 input_text, input_hash, teacher_output, quality, metadata)
            VALUES ('agent_insight', 'restaurant', $1, 'insight',
                    $2, $3, $4, 5, NULL)
            ON CONFLICT (input_hash) DO UPDATE
                SET teacher_output = EXCLUDED.teacher_output,
                    created_at     = NOW()
            """,
            factory_id, corpus_key, h, answer,
        )
    return h


async def _delete_corpus_row(pool, input_hash: str):
    async with pool.acquire() as conn:
        await conn.execute(
            "DELETE FROM smart_bi_distillation_samples WHERE input_hash = $1",
            input_hash,
        )


async def test_corpus_cache_hit_skips_llm(pool):
    """Corpus hit (today's agent_insight row) short-circuits before LLM.

    Source must be RESULT_SOURCE_CORPUS, tokens=0, and the LLM mock must
    never be called.
    """
    await _reset_tenant(pool, _TENANT)
    q = "本月门店表现如何"
    corpus_answer = "【语料命中】青花椒大丸百货店表现最佳，建议加大推广。"
    row_hash = await _seed_corpus_row(pool, _TENANT, q, _START, _END, corpus_answer)

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
        await _delete_corpus_row(pool, row_hash)

    assert result.source == RESULT_SOURCE_CORPUS, (
        f"Expected 'corpus' but got '{result.source}' — corpus read-back may not be wired"
    )
    assert result.answer == corpus_answer
    assert result.tokens == 0
    assert called["n"] == 0, "LLM must not be called on corpus hit"


async def test_corpus_cache_miss_falls_through_to_llm(pool):
    """When there's no corpus row, the flow reaches LLM as normal."""
    await _reset_tenant(pool, _TENANT)
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

    assert result.source == RESULT_SOURCE_LLM
    assert result.tokens == 300


async def test_corpus_cache_hit_takes_priority_over_budget_check(pool):
    """Corpus hit fires before the budget check — so even an exhausted budget
    tenant gets served from corpus (no additional token consumption)."""
    await _reset_tenant(pool, _TENANT)

    # Exhaust budget first
    from smartbi.tenant_ctx import set_factory_id
    set_factory_id(_TENANT)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO agent_tenant_config (factory_id, tier, custom_cap_override)
            VALUES ($1, 'basic', 5)
            """,
            _TENANT,
        )
    tracker = AgentBudgetTracker(pool)
    await tracker.consume(_TENANT, 20)  # over cap=5 → blocked

    q = "客单价走势"
    corpus_answer = "客单价上周平均 ¥68，本周 ¥71，建议继续推高价值套餐。"
    row_hash = await _seed_corpus_row(pool, _TENANT, q, _START, _END, corpus_answer)

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
        await _delete_corpus_row(pool, row_hash)

    assert result.source == RESULT_SOURCE_CORPUS
    assert result.tokens == 0
    assert called["n"] == 0


async def test_corpus_feedback_down_demoted_row_falls_through_to_llm(pool):
    """A corpus row with feedback_down >= threshold must be skipped (G4 serve-skip).
    The flow should fall through to LLM and generate a fresh answer."""
    await _reset_tenant(pool, _TENANT)
    q = "折扣对客单价的影响"
    fake_answer = "折扣降低了客单价但提升了订单量，净效应为正。"

    # Seed a demoted corpus row (feedback_down=2)
    from smartbi.services.distillation_capture import compute_input_hash as _cih
    corpus_key = _build_corpus_input_text(q, _START.isoformat(), _END.isoformat(), _TENANT)
    h = _cih(corpus_key)
    async with pool.acquire() as conn:
        await conn.execute(
            """
            INSERT INTO smart_bi_distillation_samples
                (source, business_type, factory_id, task_type,
                 input_text, input_hash, teacher_output, quality,
                 metadata)
            VALUES ('agent_insight', 'restaurant', $1, 'insight',
                    $2, $3, '【已踩】错误答案', 2,
                    '{"feedback_down": 2}'::jsonb)
            ON CONFLICT (input_hash) DO UPDATE
                SET teacher_output = EXCLUDED.teacher_output,
                    metadata       = EXCLUDED.metadata,
                    created_at     = NOW()
            """,
            _TENANT, corpus_key, h,
        )

    http = _make_mock_llm_client(fake_answer, total_tokens=280)
    original = _patch_llm_client_singleton(http)
    try:
        orch = _orchestrator(pool, http)
        result = await orch.answer_insight(_TENANT, q, _RANGE)
    finally:
        _restore_llm_client_singleton(original)
        await http.aclose()
        await _delete_corpus_row(pool, h)

    # Must NOT have served the demoted corpus row
    assert result.source == RESULT_SOURCE_LLM, (
        f"Demoted corpus row should be skipped, got source={result.source!r}"
    )
    assert result.answer == fake_answer


async def test_build_corpus_input_text_bakes_factory_id():
    """Different factory_ids produce different corpus keys (cross-tenant isolation)."""
    key_a = _build_corpus_input_text("本月营业额", "2025-01-01", "2025-12-31", "FACTORY_A")
    key_b = _build_corpus_input_text("本月营业额", "2025-01-01", "2025-12-31", "FACTORY_B")
    assert key_a != key_b

    h_a = compute_input_hash(key_a)
    h_b = compute_input_hash(key_b)
    assert h_a != h_b


async def test_build_corpus_input_text_normalizes_question():
    """Question normalization (whitespace/case) makes the key stable."""
    key1 = _build_corpus_input_text("本月  营业额如何", "2025-01-01", "2025-12-31", "F001")
    key2 = _build_corpus_input_text("本月 营业额如何", "2025-01-01", "2025-12-31", "F001")
    assert key1 == key2
