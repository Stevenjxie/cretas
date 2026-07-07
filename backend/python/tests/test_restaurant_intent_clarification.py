"""Unit tests for the restaurant AI multi-turn clarification continuation
loop (v1, 2026-07-08).

Design: `parse_restaurant_query` in smartbi/gold/restaurant_intent.py now
accepts an optional `session_key`. When a previous call for the same
(factory_id, session_key) returned a clarification question, the NEXT call
is parsed as the user's ANSWER to that question (original question +
answer, deterministic T1/T2 first, then T3 with `history`) instead of being
re-parsed as a brand-new, context-free query.

Mirrors the mocking style of test_restaurant_intent.py (patch cosine_topk /
common.llm_router.call_chain, local _FakeConn/_FakePool doubles).
"""
from __future__ import annotations

import json
import time
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold.restaurant_intent import (
    _PENDING_CLARIFICATIONS,
    _build_t3_prompt,
    _cache_get,
    _cache_put,
    _pending_pop,
    _pending_put,
    clear_pending_clarifications,
    clear_route_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)
from smartbi.gold.restaurant_ops_router import _resolve_sales_date_range, match_restaurant_ops


@pytest.fixture(autouse=True)
def _reset_state():
    clear_route_cache()
    clear_tenant_gate_cache()
    clear_pending_clarifications()
    yield
    clear_route_cache()
    clear_tenant_gate_cache()
    clear_pending_clarifications()


class _FakeConn:
    def __init__(self, fetchrow_result):
        self._fetchrow_result = fetchrow_result

    async def fetchrow(self, sql, *args):
        return self._fetchrow_result


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn
        self.acquire_calls = 0

    def acquire(self):
        self.acquire_calls += 1
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


def _restaurant_pool() -> _FakePool:
    return _FakePool(_FakeConn({"?column?": 1}))


def _llm_result(payload: dict) -> dict:
    return {"choices": [{"message": {"content": json.dumps(payload)}}]}


_CLARIFY_JSON = {
    "intent": None, "time_range": None, "confidence": 0.2,
    "clarification_needed": True,
    "clarification_question": "您想了解营收、毛利、损耗还是库存盘点的情况？",
}


# ─── 1. Happy path: clarification -> answer resolves via T3 + history ─────

@pytest.mark.asyncio
async def test_continuation_resolves_via_t3_with_history_and_clears_pending():
    pool = _restaurant_pool()
    original_query = "情况怎么样"
    assert match_restaurant_ops(original_query) is None  # confirm T1 miss on turn 1

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(_CLARIFY_JSON)),
    ):
        spec1 = await parse_restaurant_query(
            original_query, pool, factory_id="F_CLAR", session_key="sess-1",
        )

    assert spec1 is not None
    assert spec1.clarification_needed is True
    assert spec1.is_clarification_continuation is False

    answer = "最近两个月"
    concatenated = f"{original_query} {answer}"
    assert match_restaurant_ops(concatenated) is None  # confirm T1 also misses turn 2

    answer_json = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "month", "count": 2},
        "wants_margin": False, "asks_profitability": False,
        "dimensions": [], "comparison": None, "confidence": 0.85,
        "clarification_needed": False, "clarification_question": None,
    }
    llm_mock = AsyncMock(return_value=_llm_result(answer_json))
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=llm_mock):
        spec2 = await parse_restaurant_query(
            answer, pool, factory_id="F_CLAR", session_key="sess-1",
        )

    assert spec2 is not None
    assert spec2.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec2.clarification_needed is False
    assert spec2.is_clarification_continuation is True

    expected_range, expected_label = _resolve_sales_date_range("最近2个月")
    assert spec2.window_label == expected_label
    assert spec2.date_range == expected_range

    # T3 was invoked with the two-turn history (original question + the
    # clarification question we asked), not just the bare answer.
    args, _kwargs = llm_mock.call_args
    payload = args[1]
    user_msg = payload["messages"][1]["content"]
    assert original_query in user_msg
    assert spec1.clarification_question in user_msg

    # Pending is consumed -- a repeat lookup for the same key finds nothing.
    assert _pending_pop("F_CLAR", "sess-1") is None


# ─── 2. Still ambiguous after continuation -> no re-registration ─────────

@pytest.mark.asyncio
async def test_continuation_still_ambiguous_does_not_reregister_pending():
    pool = _restaurant_pool()
    original_query = "情况怎么样"
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(_CLARIFY_JSON)),
    ):
        spec1 = await parse_restaurant_query(
            original_query, pool, factory_id="F_LOOP", session_key="sess-loop",
        )
    assert spec1.clarification_needed is True

    still_vague_json = {
        "intent": None, "confidence": 0.15, "clarification_needed": True,
        "clarification_question": "还是不太明白，能再说具体点吗？",
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(still_vague_json)),
    ):
        spec2 = await parse_restaurant_query(
            "随便啦", pool, factory_id="F_LOOP", session_key="sess-loop",
        )

    assert spec2 is not None
    assert spec2.clarification_needed is True
    assert spec2.is_clarification_continuation is True
    assert spec2.clarification_question  # never empty (default filled in)

    # No new pending was registered for this (factory, session) -- a THIRD
    # message would be treated as fresh, not another continuation hop.
    assert _pending_pop("F_LOOP", "sess-loop") is None


# ─── 3. No session_key -> continuation never attempted ────────────────────

@pytest.mark.asyncio
async def test_no_session_key_never_attempts_continuation():
    pool = _restaurant_pool()
    _pending_put(
        "F_NOKEY", "sess-untouched",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id="F_NOKEY", session_key=None,
    )
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.is_clarification_continuation is False
    # session_key wasn't passed at all -- the pending entry for a DIFFERENT
    # session under the same factory must be left completely untouched.
    assert _pending_pop("F_NOKEY", "sess-untouched") is not None


@pytest.mark.asyncio
async def test_empty_string_session_key_never_attempts_continuation():
    """Spec section 1: session_key missing (None) OR empty behaves
    identically -- an empty string must not enable continuation either."""
    pool = _restaurant_pool()
    _pending_put(
        "F_EMPTYKEY", "sess-empty-check",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id="F_EMPTYKEY", session_key="",
    )
    assert spec is not None
    assert spec.is_clarification_continuation is False
    assert _pending_pop("F_EMPTYKEY", "sess-empty-check") is not None


# ─── 4. TTL expiry -> continuation not attempted ──────────────────────────

@pytest.mark.asyncio
async def test_ttl_expired_pending_is_not_continued():
    pool = _restaurant_pool()
    factory_id, session_key = "F_TTL", "sess-ttl"
    _pending_put(
        factory_id, session_key,
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )
    # Backdate the entry past the 5-minute TTL.
    key = (factory_id, session_key)
    _PENDING_CLARIFICATIONS[key]["ts"] = time.time() - 400

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id=factory_id, session_key=session_key,
    )
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.is_clarification_continuation is False  # fresh path, not continuation

    # The stale entry is gone after being read once (whether or not it was used).
    assert _pending_pop(factory_id, session_key) is None


# ─── 5. Different sessions never cross-contaminate ────────────────────────

@pytest.mark.asyncio
async def test_different_session_keys_do_not_cross_contaminate():
    pool = _restaurant_pool()
    factory_id = "F_MULTI"
    _pending_put(
        factory_id, "sess-A",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id=factory_id, session_key="sess-B",
    )
    assert spec is not None
    assert spec.is_clarification_continuation is False
    # sess-A's pending entry must be untouched by the sess-B call.
    assert _pending_pop(factory_id, "sess-A") is not None


# ─── 6. Continuation bypasses the routing-decision cache (read + write) ──

@pytest.mark.asyncio
async def test_continuation_bypasses_route_cache_read_and_write():
    pool = _restaurant_pool()
    factory_id = "F_CACHE"
    original_query = "情况怎么样"
    answer = "最近两个月"
    concatenated = f"{original_query} {answer}"

    # Plant a decoy routing decision under the CONCATENATED text -- if
    # continuation wrongly consulted `_ROUTE_CACHE`, it would return this
    # decoy instead of doing the real T1/T2/T3 resolution.
    _cache_put(factory_id, concatenated, {
        "code": "RESTAURANT_OPS_WASTAGE_TOP", "confidence": 0.99, "tier": "vector",
        "clarification_needed": False, "clarification_question": None,
    })

    _pending_put(
        factory_id, "sess-cache",
        original_query=original_query, clarification_question="您想看哪方面？",
    )

    answer_json = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "month", "count": 2},
        "confidence": 0.9, "clarification_needed": False,
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(answer_json)),
    ):
        spec = await parse_restaurant_query(
            answer, pool, factory_id=factory_id, session_key="sess-cache",
        )

    # Real resolution won (decoy ignored) -- proves the read was skipped.
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"

    # The decoy entry is UNCHANGED -- proves continuation never wrote to
    # _ROUTE_CACHE either (it would have overwritten the decoy with the
    # real SALES_SUMMARY decision under the same concatenated-text key).
    still_cached = _cache_get(factory_id, concatenated)
    assert still_cached is not None
    assert still_cached["code"] == "RESTAURANT_OPS_WASTAGE_TOP"


# ─── 7. Deterministic fast path on continuation avoids an LLM call ────────

@pytest.mark.asyncio
async def test_concatenated_deterministic_fast_path_skips_llm_call_on_continuation():
    pool = _restaurant_pool()
    factory_id = "F_FAST"
    original_query = "情况怎么样"
    answer = "哪家店赚钱最多"
    concatenated = f"{original_query} {answer}"
    assert match_restaurant_ops(concatenated) == "RESTAURANT_OPS_STORE_MARGIN"

    _pending_put(
        factory_id, "sess-fast",
        original_query=original_query, clarification_question="您想看哪方面？",
    )

    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(side_effect=AssertionError("T3 must not run -- T1 already resolved it")),
    ):
        spec = await parse_restaurant_query(
            answer, pool, factory_id=factory_id, session_key="sess-fast",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.source_tier == "keyword"
    assert spec.confidence == 0.95
    assert spec.is_clarification_continuation is True
    # T1 hit is ungated -- the tenant lookup must not even run.
    assert pool.acquire_calls == 0


# ─── 8. Deterministic slots see the FULL concatenated (two-turn) text ─────

@pytest.mark.asyncio
async def test_continuation_deterministic_slots_use_concatenated_text():
    """The original question's dimension ("哪家店") must still be reflected
    in the final spec even though only the ANSWER ("最近一个月") is what the
    LLM sees as the "current message" -- because `_build_spec` is fed the
    concatenated text, not just the bare answer."""
    pool = _restaurant_pool()
    factory_id = "F_DIM"
    original_query = "哪家店"
    assert match_restaurant_ops(original_query) is None

    answer = "最近一个月"
    concatenated = f"{original_query} {answer}"
    assert match_restaurant_ops(concatenated) is None  # still no T1 hit (no margin word)

    _pending_put(
        factory_id, "sess-dim",
        original_query=original_query, clarification_question="您想看哪方面？",
    )

    answer_json = {
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "time_range": {"type": "relative", "unit": "month", "count": 1},
        "confidence": 0.85, "clarification_needed": False,
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(answer_json)),
    ):
        spec = await parse_restaurant_query(
            answer, pool, factory_id=factory_id, session_key="sess-dim",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert "store" in spec.dimensions
    assert spec.is_clarification_continuation is True


# ─── 9. Pending registration only on a fresh clarification with session_key ─

@pytest.mark.asyncio
async def test_pending_registered_when_fresh_parse_clarifies_with_session_key():
    pool = _restaurant_pool()
    query = "情况怎么样"
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(_CLARIFY_JSON)),
    ):
        spec = await parse_restaurant_query(
            query, pool, factory_id="F_REG", session_key="sess-reg",
        )
    assert spec.clarification_needed is True

    pending = _pending_pop("F_REG", "sess-reg")
    assert pending is not None
    assert pending["original_query"] == query
    assert pending["clarification_question"] == spec.clarification_question


@pytest.mark.asyncio
async def test_pending_not_registered_when_t1_resolves_with_session_key():
    pool = _restaurant_pool()
    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id="F_NOREG_T1", session_key="sess-noreg-t1",
    )
    assert spec.clarification_needed is False
    assert _pending_pop("F_NOREG_T1", "sess-noreg-t1") is None


@pytest.mark.asyncio
async def test_pending_not_registered_when_t3_resolves_successfully_with_session_key():
    pool = _restaurant_pool()
    resolved_json = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "month", "count": 2},
        "wants_margin": True, "asks_profitability": True,
        "confidence": 0.9, "clarification_needed": False,
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(resolved_json)),
    ):
        spec = await parse_restaurant_query(
            "这两个月生意咋样，挣着钱没", pool, factory_id="F_NOREG_T3", session_key="sess-noreg-t3",
        )
    assert spec.clarification_needed is False
    assert _pending_pop("F_NOREG_T3", "sess-noreg-t3") is None


# ─── 10. Cached-clarification replay also registers pending ──────────────

@pytest.mark.asyncio
async def test_cached_clarification_replay_also_registers_pending():
    """A repeat of the SAME ambiguous query hits `_ROUTE_CACHE` (not T3
    again) -- this replay path must still offer a continuation opportunity,
    not just the direct-T3 clarification path."""
    pool = _restaurant_pool()
    factory_id = "F_CACHEREPLAY"
    query = "情况怎么样"

    _cache_put(factory_id, query, {
        "code": "", "confidence": 0.2, "tier": "llm",
        "clarification_needed": True, "clarification_question": "问哪方面？",
    })

    spec = await parse_restaurant_query(
        query, pool, factory_id=factory_id, session_key="sess-replay",
    )
    assert spec.clarification_needed is True

    pending = _pending_pop(factory_id, "sess-replay")
    assert pending is not None
    assert pending["clarification_question"] == "问哪方面？"


# ─── 11. T3 prompt renders the two-turn history block ────────────────────

def test_t3_prompt_includes_previous_turn_history_block():
    history = [
        {"role": "user", "content": "情况怎么样"},
        {"role": "assistant", "content": "您想了解营收、毛利、损耗还是库存盘点的情况？"},
    ]
    prompt = _build_t3_prompt("最近两个月", None, history)
    assert "情况怎么样" in prompt
    assert "您想了解营收、毛利、损耗还是库存盘点的情况？" in prompt
    assert "上一轮对话" in prompt


def test_t3_prompt_omits_history_block_when_none():
    prompt = _build_t3_prompt("情况怎么样", None, None)
    assert "上一轮对话" not in prompt
