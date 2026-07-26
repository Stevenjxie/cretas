"""Unit tests for the restaurant AI multi-turn clarification continuation
loop (v1, 2026-07-08).

Design: `parse_restaurant_query` in smartbi/gold/restaurant_intent.py now
accepts an optional `session_key`. When a previous call for the same
(factory_id, session_key) returned a clarification question, the NEXT call
is parsed as the user's ANSWER to that question (original question +
answer, deterministic T1/T2 first, then T3 with `history`) instead of being
re-parsed as a brand-new, context-free query.

Pending storage is the shared smartbi Postgres table
`restaurant_pending_clarifications` (migration V20260708_01) -- NOT process
memory, because prod runs `uvicorn --workers 2` and an in-process store made
continuation a coin flip whenever the follow-up landed on the other worker
(2026-07-08 prod bug). The `_FakeDbPool` double below carries the pending
rows itself (dispatching on SQL substrings, extending the _FakeConn pattern
from test_restaurant_intent.py), so each test's pool IS its isolated store.

Everything else is mocked in the style of test_restaurant_intent.py (patch
cosine_topk / common.llm_router.call_chain).
"""
from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold.restaurant_intent import (
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    _build_t3_prompt,
    _cache_get,
    _cache_put,
    _explicit_read_only_action_ranking_spec,
    _is_restaurant_tenant,
    _pending_pop,
    _pending_put,
    build_resolver_query,
    clear_route_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)
from smartbi.gold.restaurant_ops_router import _resolve_sales_date_range, match_restaurant_ops


@pytest.fixture(autouse=True)
def _reset_state():
    # Pending-clarification state needs no reset here: it lives in each
    # test's own _FakeDbPool instance (worker-shared Postgres in prod), so
    # test isolation is automatic. Only the per-process performance caches
    # (route / tenant-gate) are module-global and must be cleared.
    clear_route_cache()
    clear_tenant_gate_cache()
    yield
    clear_route_cache()
    clear_tenant_gate_cache()


class _FakeDbConn:
    """asyncpg connection double that carries the pending-clarification
    table semantics (UPSERT / DELETE..RETURNING / sweep) plus the tenant
    gate's fetchrow. Dispatches on SQL substrings -- extends the _FakeConn
    pattern from test_restaurant_intent.py / test_analysis_restaurant_ops.py."""

    def __init__(self, pool: "_FakeDbPool"):
        self._pool = pool

    def transaction(self):
        pool = self._pool

        class _Ctx:
            async def __aenter__(self):
                pool.in_transaction = True
                pool.active_factory = None
                return None

            async def __aexit__(self, *_exc):
                pool.active_factory = None
                pool.in_transaction = False
                return False

        return _Ctx()

    async def fetchrow(self, sql, *args):
        if self._pool.raise_on_pending and "restaurant_pending_clarifications" in sql:
            raise RuntimeError("simulated DB failure (pending store)")
        if "agg_restaurant_daily_totals" in sql:
            self._pool.tenant_gate_calls += 1
            return (
                {"?column?": 1}
                if self._pool.is_restaurant
                and self._pool.in_transaction
                and self._pool.active_factory == args[0]
                else None
            )
        if "DELETE FROM restaurant_pending_clarifications" in sql and "RETURNING" in sql:
            factory_id, session_key = args
            # dict with original_query / clarification_question / created_at, or None
            return self._pool.pending.pop((factory_id, session_key), None)
        raise AssertionError(f"unexpected fetchrow SQL in fake pool: {sql}")

    async def execute(self, sql, *args):
        if self._pool.raise_on_pending and "restaurant_pending_clarifications" in sql:
            raise RuntimeError("simulated DB failure (pending store)")
        if "set_config('app.factory_id'" in sql:
            assert self._pool.in_transaction is True
            self._pool.active_factory = args[0]
            self._pool.tenant_rls_calls += 1
            return "SELECT 1"
        if "INSERT INTO restaurant_pending_clarifications" in sql:
            factory_id, session_key, original_query, clarification_question = args
            # Mirrors ON CONFLICT ... DO UPDATE: same-key put overwrites.
            self._pool.pending[(factory_id, session_key)] = {
                "original_query": original_query,
                "clarification_question": clarification_question,
                "created_at": datetime.now(timezone.utc),
            }
            return "INSERT 0 1"
        if "DELETE FROM restaurant_pending_clarifications" in sql and "created_at <" in sql:
            cutoff = datetime.now(timezone.utc) - timedelta(hours=1)
            stale = [k for k, v in self._pool.pending.items() if v["created_at"] < cutoff]
            for k in stale:
                del self._pool.pending[k]
            self._pool.sweep_calls += 1
            return f"DELETE {len(stale)}"
        if "DELETE FROM restaurant_pending_clarifications" in sql:
            n = len(self._pool.pending)
            self._pool.pending.clear()
            return f"DELETE {n}"
        raise AssertionError(f"unexpected execute SQL in fake pool: {sql}")

    async def fetch(self, sql, *_args):
        if "FROM dim_store" in sql:
            return [{"name": name} for name in self._pool.store_names]
        raise AssertionError(f"unexpected fetch SQL in fake pool: {sql}")


class _FakeDbPool:
    def __init__(self, *, is_restaurant: bool = True, store_names=None):
        self.pending: dict = {}
        self.is_restaurant = is_restaurant
        self.store_names = list(store_names or [])
        self.acquire_calls = 0
        self.tenant_gate_calls = 0
        self.tenant_rls_calls = 0
        self.in_transaction = False
        self.active_factory = None
        self.sweep_calls = 0
        self.raise_on_pending = False  # simulate pending-store DB failure

    def acquire(self):
        self.acquire_calls += 1
        conn = _FakeDbConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


def _restaurant_pool() -> _FakeDbPool:
    return _FakeDbPool(is_restaurant=True)


def _llm_result(payload: dict) -> dict:
    return {"choices": [{"message": {"content": json.dumps(payload)}}]}


@pytest.mark.asyncio
async def test_restaurant_tenant_gate_binds_rls_context_before_caching():
    pool = _restaurant_pool()

    first = await _is_restaurant_tenant(pool, "F_RLS_RESTAURANT")
    second = await _is_restaurant_tenant(pool, "F_RLS_RESTAURANT")

    assert first is True
    assert second is True
    assert pool.tenant_rls_calls == 1
    assert pool.tenant_gate_calls == 1
    assert pool.active_factory is None
    assert pool.in_transaction is False


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
    # The pending entry landed in the SHARED store (the DB double), where a
    # different worker process would see it too.
    assert ("F_CLAR", "sess-1") in pool.pending

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

    # Pending is consumed (single atomic DELETE..RETURNING) -- gone from the
    # shared store, and a repeat pop finds nothing.
    assert pool.pending == {}
    assert await _pending_pop(pool, "F_CLAR", "sess-1") is None


@pytest.mark.asyncio
async def test_time_guard_clarification_button_resumes_original_query():
    pool = _restaurant_pool()
    original_query = "哪个菜卖得好"
    first_plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "dimensions": ["dish"],
        "comparison": None,
        "confidence": 0.95,
        "clarification_needed": False,
        "clarification_question": None,
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result(first_plan)),
    ):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="F_TIME",
            session_key="sess-time",
        )

    assert first is not None
    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert first.resolver_query_seed == original_query
    assert ("F_TIME", "sess-time") in pool.pending

    resolved_plan = {
        **first_plan,
        "time_range": {"type": "named", "value": "this_month"},
    }
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result(resolved_plan)),
    ):
        resolved = await parse_restaurant_query(
            "本月",
            pool,
            factory_id="F_TIME",
            session_key="sess-time",
        )

    assert resolved is not None
    assert resolved.is_clarification_continuation is True
    assert resolved.clarification_needed is False
    assert resolved.window_label == "本月"
    assert resolved.requested_metrics == ("sales_volume",)
    assert resolved.resolver_query_seed == f"{original_query} 本月"
    # The resolver must receive the original ranking semantics together with
    # the clicked time option. Passing only "本月" reproduces the production
    # defect: the shared gross-margin resolver falls back to a margin report.
    assert build_resolver_query("本月", resolved) == f"{original_query} 本月"
    assert pool.pending == {}


@pytest.mark.asyncio
async def test_explicit_multi_store_ranking_time_button_never_needs_t3():
    pool = _restaurant_pool()
    original_query = (
        "青花椒南方百联店和青花椒徐汇光启城店哪个菜卖得好"
    )
    t3 = AsyncMock(side_effect=AssertionError(
        "structured ranking and its time continuation must not call T3"
    ))

    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="DEMO_REST",
            session_key="sess-explicit-multi-store",
        )

        assert first is not None
        assert first.planner_authority == "explicit_slots"
        assert first.clarification_question == TIME_CLARIFICATION_QUESTION
        assert first.store_scope == "multiple"
        assert ("DEMO_REST", "sess-explicit-multi-store") in pool.pending

        resolved = await parse_restaurant_query(
            "最近7天",
            pool,
            factory_id="DEMO_REST",
            session_key="sess-explicit-multi-store",
        )

    assert resolved is not None
    assert resolved.planner_authority == "explicit_slots"
    assert resolved.source_tier == "explicit_slots"
    assert resolved.is_clarification_continuation is True
    assert resolved.clarification_needed is False
    assert resolved.window_label == "最近7天"
    assert resolved.store_slots == (
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    )
    assert resolved.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert pool.pending == {}
    t3.assert_not_awaited()


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
    assert pool.pending == {}
    assert await _pending_pop(pool, "F_LOOP", "sess-loop") is None


# ─── 3. No session_key -> continuation never attempted ────────────────────

@pytest.mark.asyncio
async def test_no_session_key_never_attempts_continuation():
    pool = _restaurant_pool()
    await _pending_put(
        pool, "F_NOKEY", "sess-untouched",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    resolved_json = {
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "confidence": 0.92,
        "clarification_needed": False,
    }
    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result(resolved_json)),
    ):
        spec = await parse_restaurant_query(
            "哪家店最赚钱", pool, factory_id="F_NOKEY", session_key=None,
        )
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.is_clarification_continuation is False
    # session_key wasn't passed at all -- the pending entry for a DIFFERENT
    # session under the same factory must be left completely untouched.
    assert ("F_NOKEY", "sess-untouched") in pool.pending
    assert await _pending_pop(pool, "F_NOKEY", "sess-untouched") is not None


@pytest.mark.asyncio
async def test_empty_string_session_key_never_attempts_continuation():
    """Spec section 1: session_key missing (None) OR empty behaves
    identically -- an empty string must not enable continuation either."""
    pool = _restaurant_pool()
    await _pending_put(
        pool, "F_EMPTYKEY", "sess-empty-check",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id="F_EMPTYKEY", session_key="",
    )
    assert spec is not None
    assert spec.is_clarification_continuation is False
    assert await _pending_pop(pool, "F_EMPTYKEY", "sess-empty-check") is not None


# ─── 4. TTL expiry -> continuation not attempted ──────────────────────────

@pytest.mark.asyncio
async def test_ttl_expired_pending_is_not_continued():
    pool = _restaurant_pool()
    factory_id, session_key = "F_TTL", "sess-ttl"
    await _pending_put(
        pool, factory_id, session_key,
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )
    # Backdate the row past the 5-minute TTL (row still in the table -- TTL
    # is judged Python-side on the created_at returned by DELETE..RETURNING).
    pool.pending[(factory_id, session_key)]["created_at"] -= timedelta(seconds=400)

    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result({
            "intent": "RESTAURANT_OPS_STORE_MARGIN",
            "confidence": 0.92,
            "clarification_needed": False,
        })),
    ):
        spec = await parse_restaurant_query(
            "本月哪家店最赚钱", pool, factory_id=factory_id, session_key=session_key,
        )
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.is_clarification_continuation is False  # fresh path, not continuation

    # The stale row was consumed by the pop even though it wasn't used.
    assert (factory_id, session_key) not in pool.pending
    assert await _pending_pop(pool, factory_id, session_key) is None


@pytest.mark.asyncio
async def test_pop_opportunistically_sweeps_hour_old_rows():
    """The anti-bloat sweep rides along on pop: rows older than 1 hour that
    nobody ever followed up on get deleted (failure-ignored side effect)."""
    pool = _restaurant_pool()
    await _pending_put(
        pool, "F_SWEEP", "sess-abandoned",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )
    pool.pending[("F_SWEEP", "sess-abandoned")]["created_at"] -= timedelta(hours=2)

    # Pop for an unrelated key -- returns None, but the sweep runs.
    assert await _pending_pop(pool, "F_SWEEP", "sess-other") is None
    assert pool.sweep_calls >= 1
    assert ("F_SWEEP", "sess-abandoned") not in pool.pending


# ─── 5. Different sessions never cross-contaminate ────────────────────────

@pytest.mark.asyncio
async def test_different_session_keys_do_not_cross_contaminate():
    pool = _restaurant_pool()
    factory_id = "F_MULTI"
    await _pending_put(
        pool, factory_id, "sess-A",
        original_query="情况怎么样", clarification_question="您想看哪方面？",
    )

    spec = await parse_restaurant_query(
        "哪家店最赚钱", pool, factory_id=factory_id, session_key="sess-B",
    )
    assert spec is not None
    assert spec.is_clarification_continuation is False
    # sess-A's pending entry must be untouched by the sess-B call.
    assert await _pending_pop(pool, factory_id, "sess-A") is not None


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

    await _pending_put(
        pool, factory_id, "sess-cache",
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
async def test_continuation_keyword_candidate_still_requires_llm_plan():
    pool = _restaurant_pool()
    factory_id = "F_FAST"
    original_query = "情况怎么样"
    answer = "哪家店赚钱最多"
    concatenated = f"{original_query} {answer}"
    assert match_restaurant_ops(concatenated) == "RESTAURANT_OPS_STORE_MARGIN"

    await _pending_put(
        pool, factory_id, "sess-fast",
        original_query=original_query, clarification_question="您想看哪方面？",
    )

    llm = AsyncMock(return_value=_llm_result({
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "confidence": 0.94,
        "clarification_needed": False,
    }))
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(side_effect=AssertionError("T2 must not run -- T1 already resolved it")),
    ), patch(
        "common.llm_router.call_chain",
        new=llm,
    ):
        spec = await parse_restaurant_query(
            answer, pool, factory_id=factory_id, session_key="sess-fast",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.source_tier == "llm"
    assert spec.planner_authority == "llm"
    assert spec.confidence == 0.94
    assert spec.is_clarification_continuation is True
    assert llm.await_count == 1
    assert pool.tenant_gate_calls == 1


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

    await _pending_put(
        pool, factory_id, "sess-dim",
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

    pending = await _pending_pop(pool, "F_REG", "sess-reg")
    assert pending is not None
    assert pending["original_query"] == query
    assert pending["clarification_question"] == spec.clarification_question


@pytest.mark.asyncio
async def test_pending_not_registered_when_llm_confirms_keyword_candidate():
    pool = _restaurant_pool()
    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result({
            "intent": "RESTAURANT_OPS_STORE_MARGIN",
            "confidence": 0.92,
            "clarification_needed": False,
        })),
    ):
        spec = await parse_restaurant_query(
            "本月哪家店最赚钱", pool, factory_id="F_NOREG_T1", session_key="sess-noreg-t1",
        )
    assert spec.clarification_needed is False
    assert pool.pending == {}
    assert await _pending_pop(pool, "F_NOREG_T1", "sess-noreg-t1") is None


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
    assert pool.pending == {}
    assert await _pending_pop(pool, "F_NOREG_T3", "sess-noreg-t3") is None


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
        "plan_version": "restaurant-query-plan-v2",
        "planner_authority": "llm",
        "clarification_needed": True, "clarification_question": "问哪方面？",
    })

    spec = await parse_restaurant_query(
        query, pool, factory_id=factory_id, session_key="sess-replay",
    )
    assert spec.clarification_needed is True

    pending = await _pending_pop(pool, factory_id, "sess-replay")
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


# ─── 12. Fail-open: pending-store DB failure never breaks the parse ──────

@pytest.mark.asyncio
async def test_pending_store_db_failure_fails_open_on_pop_and_put():
    """The 2026-07-08 fix moved pending storage to Postgres -- a DB blip on
    that table must degrade to 'no continuation this time / nothing
    registered' (module principle 6), NEVER raise into the caller's chain
    and never block a fresh parse from resolving."""
    pool = _restaurant_pool()
    pool.raise_on_pending = True

    # Pop path: T1-resolvable query with session_key -- pending pop raises,
    # parse must still resolve the query as a fresh single-turn one.
    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result({
            "intent": "RESTAURANT_OPS_STORE_MARGIN",
            "confidence": 0.92,
            "clarification_needed": False,
        })),
    ):
        spec = await parse_restaurant_query(
            "哪家店最赚钱", pool, factory_id="F_FAILOPEN", session_key="sess-fail",
        )
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.is_clarification_continuation is False

    # Put path: a fresh clarification with session_key -- registration
    # raises, the clarification must still be returned to the user.
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain", new=AsyncMock(return_value=_llm_result(_CLARIFY_JSON)),
    ):
        spec2 = await parse_restaurant_query(
            "情况怎么样", pool, factory_id="F_FAILOPEN", session_key="sess-fail",
        )
    assert spec2 is not None
    assert spec2.clarification_needed is True
    assert pool.pending == {}  # nothing got registered (put failed silently)


@pytest.mark.asyncio
async def test_time_then_store_scope_clarifications_chain_without_losing_query():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店", "南城店"],
    )
    original_query = "哪个菜卖得好"
    llm = AsyncMock(side_effect=AssertionError(
        "reviewed exact phrase and fixed buttons must survive a T3 outage"
    ))

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=llm):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="F_CHAIN",
            session_key="sess-chain",
        )
        second = await parse_restaurant_query(
            "本月",
            pool,
            factory_id="F_CHAIN",
            session_key="sess-chain",
        )
        third = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id="F_CHAIN",
            session_key="sess-chain",
        )

    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert first.planner_authority == "promoted_exact"
    assert second.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert second.planner_authority == "promoted_exact"
    assert second.store_options == ("东城店", "西城店", "南城店")
    assert third.clarification_needed is False
    assert third.planner_authority == "promoted_exact"
    assert third.store_scope == "all"
    assert third.window_label == "本月"
    assert original_query in third.resolver_query_seed
    assert "本月" in third.resolver_query_seed
    assert "全部门店" in third.resolver_query_seed
    assert pool.pending == {}
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_dependent_optimization_cannot_escape_pending_named_dish_time_scope():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "named-dish continuation must keep its deterministic pending context"
    ))

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=llm):
        first = await parse_restaurant_query(
            "米饭的销量为什么这样？",
            pool,
            factory_id="F_NAMED_DISH_PENDING",
            session_key="sess-named-dish-pending",
        )
        second = await parse_restaurant_query(
            "怎么优化它",
            pool,
            factory_id="F_NAMED_DISH_PENDING",
            session_key="sess-named-dish-pending",
        )

    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert second.is_clarification_continuation is True
    assert second.clarification_question == TIME_CLARIFICATION_QUESTION
    assert second.dish_slot == "米饭"
    assert second.requested_metrics == ("sales_volume",)
    assert second.analysis_action == "optimize"
    assert second.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_read_action_store_then_view_choice_compiles_dish_ranking():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    factory_id = "F_READ_ACTION_STORE_FIRST"
    session_key = "sess-read-action-store-first"
    original_query = "把最近7天销量最低的5道菜全部下架"
    await _pending_put(
        pool,
        factory_id,
        session_key,
        original_query=original_query,
        clarification_question="您是想查看低销量菜品排行，还是执行下架？",
    )
    second_turn_plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": {"type": "relative", "unit": "day", "count": 7},
        "wants_margin": False,
        "asks_profitability": False,
        "dimensions": ["dish"],
        "comparison": None,
        "confidence": 0.95,
        "clarification_needed": True,
        "clarification_question": "您是想查看全部门店的低销量菜品排行，还是执行下架？",
    }
    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=_llm_result(second_turn_plan)),
    ):
        second = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )

    assert second.clarification_needed is True
    assert (factory_id, session_key) in pool.pending
    assert "全部门店" in pool.pending[(factory_id, session_key)]["original_query"]

    t3 = AsyncMock(side_effect=AssertionError(
        "explicit READ choice must compile the retained ranking slots without T3"
    ))
    with patch("common.llm_router.call_chain", new=t3):
        third = await parse_restaurant_query(
            "只看低销量排行",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )

    assert third.clarification_needed is False
    assert third.planner_authority == "explicit_action_read_choice"
    assert third.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert third.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert third.requested_metrics == ("sales_volume",)
    assert third.ranking_direction == "worst"
    assert third.ranking_limit == 5
    assert third.window_label == "最近7天"
    assert third.store_scope == "all"
    assert third.dimensions == ("dish",)
    assert "下架" not in third.resolver_query_seed
    assert pool.pending == {}
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_read_action_view_then_store_choice_compiles_dish_ranking():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    factory_id = "F_READ_ACTION_VIEW_FIRST"
    session_key = "sess-read-action-view-first"
    original_query = "把最近7天销量最低的5道菜全部下架"
    await _pending_put(
        pool,
        factory_id,
        session_key,
        original_query=original_query,
        clarification_question="您是想查看低销量菜品排行，还是执行下架？",
    )
    t3 = AsyncMock(side_effect=AssertionError(
        "explicit READ/store choices must not require T3"
    ))
    with patch("common.llm_router.call_chain", new=t3):
        second = await parse_restaurant_query(
            "只看排行",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )
        third = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )

    assert second.clarification_needed is True
    assert second.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert third.clarification_needed is False
    assert third.planner_authority == "explicit_action_read_choice"
    assert third.store_scope == "all"
    assert third.window_label == "最近7天"
    assert third.ranking_direction == "worst"
    assert third.ranking_limit == 5
    assert "下架" not in third.resolver_query_seed
    assert pool.pending == {}
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_read_action_choice_allows_explicit_current_time_override():
    pool = _restaurant_pool()
    factory_id = "F_READ_ACTION_TIME_OVERRIDE"
    session_key = "sess-read-action-time-override"
    await _pending_put(
        pool,
        factory_id,
        session_key,
        original_query="把最近7天全部门店销量最低的5道菜全部下架",
        clarification_question="您是想查看排行，还是执行下架？",
    )
    t3 = AsyncMock(side_effect=AssertionError(
        "explicit current time must override retained time without T3"
    ))
    with patch("common.llm_router.call_chain", new=t3):
        spec = await parse_restaurant_query(
            "只看本月低销量排行",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )

    assert spec.clarification_needed is False
    assert spec.window_label == "本月"
    assert spec.store_scope == "all"
    assert spec.ranking_direction == "worst"
    assert "下架" not in spec.resolver_query_seed
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_read_action_view_then_time_override_then_store_keeps_new_time():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    factory_id = "F_READ_ACTION_TIME_THREE_TURNS"
    session_key = "sess-read-action-time-three-turns"
    await _pending_put(
        pool,
        factory_id,
        session_key,
        original_query="把最近7天销量最低的5道菜全部下架",
        clarification_question="您是想查看低销量菜品排行，还是执行下架？",
    )
    t3 = AsyncMock(side_effect=AssertionError(
        "explicit READ/time/store choices must not require T3"
    ))
    with patch("common.llm_router.call_chain", new=t3):
        second = await parse_restaurant_query(
            "只看本月低销量排行",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )
        third = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id=factory_id,
            session_key=session_key,
        )

    assert second.clarification_needed is True
    assert second.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert third.clarification_needed is False
    assert third.planner_authority == "explicit_action_read_choice"
    assert third.window_label == "本月"
    assert third.date_range[0].day == 1
    assert third.store_scope == "all"
    assert third.ranking_direction == "worst"
    assert third.ranking_limit == 5
    assert "最近7天" not in third.resolver_query_seed
    assert "本月" in third.resolver_query_seed
    assert "下架" not in third.resolver_query_seed
    assert pool.pending == {}
    t3.assert_not_awaited()


@pytest.mark.parametrize(
    "replacement",
    [
        "只看门店低销量排行",
        "只看食材低销量排行",
        "只看毛利最低排行",
    ],
)
def test_read_action_choice_does_not_inherit_over_explicit_new_semantics(
    replacement,
):
    assert _explicit_read_only_action_ranking_spec(
        "把最近7天全部门店销量最低的5道菜全部下架",
        replacement,
    ) is None


@pytest.mark.parametrize(
    "replacement",
    [
        "只看门店低销量排行",
        "只看食材领用量排行",
        "只看毛利最低排行",
    ],
)
def test_persisted_read_choice_semantic_replacement_cannot_revive_dish_plan(
    replacement,
):
    assert _explicit_read_only_action_ranking_spec(
        f"把最近7天销量最低的5道菜全部下架 {replacement}",
        "全部门店",
    ) is None


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "original_query,baseline_label",
    [
        ("昨天的营业额是高于前天还是低于前天？", "前天"),
        ("本周营业额和上周相比是上升还是下降？", "上周"),
        ("上个月营业额和上上个月相比怎么样", "上上个月"),
    ],
)
async def test_explicit_period_comparison_survives_store_button_without_t3(
    original_query,
    baseline_label,
):
    class _FrozenDate(date):
        @classmethod
        def today(cls):
            return cls(2026, 7, 26)

    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店", "南城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "complete period-comparison slots must not be rewritten by T3"
    ))

    # This test locks context preservation, not the calendar-dependent
    # partial-week rule. Freeze the business date so it remains deterministic
    # when CI crosses a week/month boundary.
    with (
        patch("common.llm_router.call_chain", new=llm),
        patch("smartbi.gold.restaurant_ops_router.date", new=_FrozenDate),
    ):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="F_PERIOD_COMPARE",
            session_key="sess-period-compare",
        )
        second = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id="F_PERIOD_COMPARE",
            session_key="sess-period-compare",
        )

    assert first.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert first.planner_authority == "explicit_comparison_slots"
    assert second.clarification_needed is False
    assert second.is_clarification_continuation is True
    assert second.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert second.planned_intents == ("RESTAURANT_OPS_SALES_SUMMARY",)
    assert second.planner_authority == "explicit_comparison_slots"
    assert second.store_scope == "all"
    assert second.comparison_label == baseline_label
    assert all(value is not None for value in second.date_range)
    assert all(value is not None for value in second.comparison_range)
    assert original_query in second.resolver_query_seed
    assert "全部门店" in second.resolver_query_seed
    assert pool.pending == {}
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_store_scope_reply_with_extra_time_cannot_use_comparison_fast_path():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    original_query = "昨天的营业额是高于前天还是低于前天？"
    await _pending_put(
        pool,
        "F_PERIOD_OVERRIDE",
        "sess-period-override",
        original_query=original_query,
        clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
    )
    llm = AsyncMock(side_effect=AssertionError(
        "an explicit current-turn time conflict must fail closed before T3"
    ))

    with patch("common.llm_router.call_chain", new=llm):
        spec = await parse_restaurant_query(
            "全部门店，本月",
            pool,
            factory_id="F_PERIOD_OVERRIDE",
            session_key="sess-period-override",
        )

    assert spec is not None
    assert spec.planner_authority == "explicit_time_override_requires_baseline"
    assert spec.clarification_needed is True
    assert spec.planned_intents == ()
    assert spec.window_label == "本月"
    assert spec.comparison_range == (None, None)
    assert "没有沿用" in spec.clarification_question
    assert "前天" in spec.clarification_question
    assert llm.await_count == 0


@pytest.mark.asyncio
async def test_store_scope_reply_may_repeat_same_primary_window_without_t3():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "a redundant current-turn primary window must not call T3"
    ))

    with patch("common.llm_router.call_chain", new=llm):
        first = await parse_restaurant_query(
            "昨天的营业额是高于前天还是低于前天？",
            pool,
            factory_id="F_PERIOD_REPEAT",
            session_key="sess-period-repeat",
        )
        second = await parse_restaurant_query(
            "全部门店，昨天",
            pool,
            factory_id="F_PERIOD_REPEAT",
            session_key="sess-period-repeat",
        )

    assert first.clarification_needed is True
    assert second.clarification_needed is False
    assert second.planner_authority == "explicit_comparison_slots"
    assert second.window_label == "昨天"
    assert second.comparison_label == "前天"
    assert second.store_scope == "all"
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_store_scope_reply_complete_new_comparison_replaces_old_periods():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "a complete current-turn replacement comparison must not call T3"
    ))

    with patch("common.llm_router.call_chain", new=llm):
        await parse_restaurant_query(
            "昨天的营业额是高于前天还是低于前天？",
            pool,
            factory_id="F_PERIOD_REPLACE",
            session_key="sess-period-replace",
        )
        second = await parse_restaurant_query(
            "全部门店，本月和上月比",
            pool,
            factory_id="F_PERIOD_REPLACE",
            session_key="sess-period-replace",
        )

    assert second.clarification_needed is False
    assert second.planner_authority == "explicit_comparison_slots"
    assert second.window_label == "本月"
    assert second.comparison_label == "上个月同期"
    assert second.store_scope == "all"
    assert second.comparison_range != (None, None)
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_reviewed_exact_concrete_store_button_survives_t3_outage():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "a concrete store button on an approved exact chain must not call T3"
    ))

    with patch("common.llm_router.call_chain", new=llm):
        first = await parse_restaurant_query(
            "哪个菜卖得好",
            pool,
            factory_id="F_STORE_BUTTON",
            session_key="sess-store-button",
        )
        second = await parse_restaurant_query(
            "最近7天",
            pool,
            factory_id="F_STORE_BUTTON",
            session_key="sess-store-button",
        )
        third = await parse_restaurant_query(
            "东城店",
            pool,
            factory_id="F_STORE_BUTTON",
            session_key="sess-store-button",
        )

    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert second.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert third.clarification_needed is False
    assert third.planner_authority == "promoted_exact_contract_repair"
    assert third.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert third.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert third.store_scope == "single"
    assert third.store_slots == ("东城店",)
    assert third.window_label == "最近7天"
    assert third.resolver_query_seed == "哪个菜卖得好 最近7天 东城店"
    assert pool.pending == {}
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_reviewed_exact_prefixed_time_continues_to_store_button_without_t3():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["东城店", "西城店"],
    )
    llm = AsyncMock(side_effect=AssertionError(
        "approved direct time phrase and store button must not call T3"
    ))

    with patch("common.llm_router.call_chain", new=llm):
        first = await parse_restaurant_query(
            "本月哪个菜卖得好？",
            pool,
            factory_id="F_PREFIXED_TIME",
            session_key="sess-prefixed-time",
        )
        second = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id="F_PREFIXED_TIME",
            session_key="sess-prefixed-time",
        )

    assert first.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert first.store_options == ("东城店", "西城店")
    assert second.clarification_needed is False
    assert second.planner_authority == "promoted_exact"
    assert second.store_scope == "all"
    assert second.window_label == "本月"
    assert second.resolver_query_seed == "本月哪个菜卖得好？ 全部门店"
    assert pool.pending == {}
    llm.assert_not_awaited()


@pytest.mark.asyncio
async def test_reviewed_exact_button_with_extra_instruction_falls_back_fail_closed():
    pool = _FakeDbPool(is_restaurant=True)
    t3 = AsyncMock(return_value=None)

    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        first = await parse_restaurant_query(
            "哪个菜卖得好",
            pool,
            factory_id="F_EXACT_GUARD",
            session_key="sess-exact-guard",
        )
        second = await parse_restaurant_query(
            "本月并改成库存分析",
            pool,
            factory_id="F_EXACT_GUARD",
            session_key="sess-exact-guard",
        )

    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert second.intent == ""
    assert second.planner_authority == "llm_unavailable"
    assert second.planned_intents == ()
    assert "没有执行任何相邻分析" in second.clarification_question
    t3.assert_awaited_once()
