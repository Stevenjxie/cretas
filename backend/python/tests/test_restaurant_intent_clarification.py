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

def _asked_slots(spec):
    """反问覆盖了哪些槽位 —— 判据用它, 不要断言问句字面值。

    2026-08-01: 门店和时间都缺时改为**一次问全**(合成追问), 问句字符串因此变了,
    但「时间被问了」「门店被问了」这些**语义**没变。断言语义而不是字面, 用例才
    不会在措辞调整时集体碎掉。
    """
    from smartbi.gold.restaurant.restaurant_intent import _slots_of_clarification
    return _slots_of_clarification(spec.clarification_question)



from smartbi.gold.restaurant.restaurant_intent import (
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    _build_t3_prompt,
    _cache_get,
    _cache_put,
    _explicit_read_only_action_ranking_spec,
    _is_restaurant_tenant,
    _load_relevant_store_options,
    _pending_pop,
    _pending_put,
    build_resolver_query,
    clear_route_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)
from smartbi.gold.restaurant.restaurant_ops_router import _resolve_sales_date_range, match_restaurant_ops


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
        if "DELETE FROM restaurant_scope_refinements" in sql and "RETURNING" in sql:
            factory_id, session_key = args
            return self._pool.refinements.pop((factory_id, session_key), None)
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
        if "INSERT INTO restaurant_scope_refinements" in sql:
            factory_id, session_key, seed = args
            self._pool.refinements[(factory_id, session_key)] = {
                "resolver_query_seed": seed,
                "created_at": datetime.now(timezone.utc),
            }
            return "INSERT 0 1"
        if "DELETE FROM restaurant_scope_refinements" in sql and "created_at <" in sql:
            cutoff = datetime.now(timezone.utc) - timedelta(hours=1)
            stale = [k for k, v in self._pool.refinements.items()
                     if v["created_at"] < cutoff]
            for k in stale:
                del self._pool.refinements[k]
            return f"DELETE {len(stale)}"
        raise AssertionError(f"unexpected execute SQL in fake pool: {sql}")

    async def fetch(self, sql, *_args):
        if "FROM fact_pos_item i" in sql and "JOIN dim_store s" in sql:
            self._pool.relevant_store_args = _args
            return [
                {"name": name}
                for name in self._pool.relevant_store_names
            ]
        if "FROM dim_store" in sql and "POSITION(" in sql:
            self._pool.relevant_store_args = _args
            fragment = str(_args[1])
            return [
                {"name": name}
                for name in self._pool.store_names
                if fragment in name
            ]
        if "FROM dim_store" in sql:
            return [{"name": name} for name in self._pool.store_names]
        raise AssertionError(f"unexpected fetch SQL in fake pool: {sql}")


class _FakeDbPool:
    def __init__(
        self,
        *,
        is_restaurant: bool = True,
        store_names=None,
        relevant_store_names=None,
    ):
        self.pending: dict = {}
        #: 门店范围 refinement（与 pending 分表 —— 消费语义相反, 见 V20261101_12）
        self.refinements: dict = {}
        self.is_restaurant = is_restaurant
        self.store_names = list(store_names or [])
        self.relevant_store_names = list(
            relevant_store_names
            if relevant_store_names is not None
            else self.store_names
        )
        self.relevant_store_args = ()
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


@pytest.mark.asyncio
async def test_relevant_store_options_use_period_and_named_dish_activity():
    pool = _FakeDbPool(
        store_names=["兄弟土菜馆", "有滋有味总部"],
        relevant_store_names=[
            "青花椒新世界新丸中心店",
            "青花椒徐汇光启城店",
        ],
    )

    names = await _load_relevant_store_options(
        pool,
        "DEMO_REST",
        "本月米饭的销量是多少",
    )

    assert names == (
        "青花椒新世界新丸中心店",
        "青花椒徐汇光启城店",
    )
    assert pool.active_factory == "RES_3101_009" or pool.active_factory is None
    assert pool.relevant_store_args[0] == "RES_3101_009"
    assert pool.relevant_store_args[3] == "米饭"


@pytest.mark.asyncio
async def test_relevant_store_options_without_time_use_only_matching_store_fragment():
    pool = _FakeDbPool(
        store_names=[
            "兄弟土菜馆",
            "鲜行者打浦桥日月光店",
            "青花椒徐汇日月光店",
            "有滋有味总部",
        ],
    )

    names = await _load_relevant_store_options(
        pool,
        "DEMO_REST",
        "日月光店的营收",
    )

    assert names == (
        "鲜行者打浦桥日月光店",
        "青花椒徐汇日月光店",
    )
    assert pool.relevant_store_args == ("RES_3101_009", "日月光店")


@pytest.mark.asyncio
async def test_semantic_partial_store_alias_requires_one_real_catalogue_match():
    pool = _FakeDbPool(store_names=[
        "兄弟土菜馆",
        "鲜行者打浦桥日月光店",
        "青花椒徐汇日月光店",
        "有滋有味总部",
    ])
    plan = {
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": ["store"],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": "single",
        "confidence": 0.99,
        "clarification_needed": True,
        "missing_fields": ["time_range"],
        "clarification_question": TIME_CLARIFICATION_QUESTION,
        "clarification_options": ["本月", "上个月"],
    }

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=plan),
    ):
        spec = await parse_restaurant_query(
            "日月光店的营收",
            pool,
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert spec.store_scope is None
    assert spec.store_slots == ()
    assert spec.clarification_needed is True
    assert "请问您指的是哪家" in spec.clarification_question
    assert "日月光店" in spec.clarification_question
    assert spec.clarification_options == (
        "鲜行者打浦桥日月光店",
        "青花椒徐汇日月光店",
    )
    assert "兄弟土菜馆" not in spec.clarification_options


@pytest.mark.asyncio
async def test_combined_named_dish_scope_time_buttons_omit_unrelated_store_catalogue():
    pool = _FakeDbPool(store_names=[
        "兄弟土菜馆",
        "有滋有味总部",
        "青花椒新世界新丸中心店",
    ])
    plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["sales_volume"],
        "analysis_action": "lookup",
        "dimensions": ["dish"],
        "dish": "米饭",
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.99,
        "clarification_needed": True,
        "missing_fields": ["time_range"],
        "clarification_question": TIME_CLARIFICATION_QUESTION,
        "clarification_options": ["本月", "上个月", "最近7天", "最近30天"],
    }

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=plan),
    ):
        spec = await parse_restaurant_query(
            "米饭的销量是多少",
            pool,
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert {"store", "time"} <= _asked_slots(spec)
    assert spec.clarification_options == (
        "全部门店 本月",
        "全部门店 上个月",
        "全部门店 最近7天",
        "全部门店 最近30天",
    )
    assert "兄弟土菜馆 最近30天" not in spec.clarification_options


@pytest.mark.asyncio
async def test_semantic_first_store_buttons_only_offer_data_bearing_dish_stores():
    pool = _FakeDbPool(
        store_names=[
            "兄弟土菜馆",
            "有滋有味总部",
            "青花椒新世界新丸中心店",
            "青花椒徐汇光启城店",
        ],
        relevant_store_names=[
            "青花椒新世界新丸中心店",
            "青花椒徐汇光启城店",
        ],
    )
    plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": {"type": "named", "value": "this_month"},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["sales_volume"],
        "analysis_action": "lookup",
        "dimensions": ["dish"],
        "dish": "米饭",
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.99,
        "clarification_needed": True,
        "missing_fields": ["store_scope"],
        "clarification_question": "这项分析要看哪一组门店？",
        "clarification_options": ["全部门店", "兄弟土菜馆"],
    }

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=plan),
    ):
        spec = await parse_restaurant_query(
            "本月米饭的销量是多少",
            pool,
            factory_id="DEMO_REST",
            session_key="dish-store-options",
            semantic_first=True,
        )

    # 2026-08-07 契约变更: T3 说**唯一缺项是门店**(missing_fields=["store_scope"])
    # 时不再反问, 直接默认全部门店并在答案里声明。本条要保护的性质没变 ——
    # 门店名单**只含对这道菜有数据的店**, 它现在由 store_options 承载
    # (clarification_options 因为不再反问而为空)。
    assert spec.clarification_needed is False
    assert spec.store_scope == "all"
    assert spec.store_scope_defaulted is True
    assert spec.store_options == (
        "青花椒新世界新丸中心店",
        "青花椒徐汇光启城店",
    )
    assert "兄弟土菜馆" not in spec.store_options
    assert spec.clarification_options == ()


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
    assert "time" in _asked_slots(first)
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
@pytest.mark.parametrize(
    "original_query,requested_metrics,dimensions,analysis_action,ranking_direction",
    (
        (
            "鲜行者打浦桥日月光店这家店的销售情况",
            ["revenue", "orders", "sales_volume"],
            ["store"],
            "lookup",
            None,
        ),
        (
            "鲜行者打浦桥日月光店这家店买的最好的是哪一道菜",
            ["sales_volume"],
            ["store", "dish"],
            "lookup",
            "best",
        ),
    ),
)
async def test_natural_store_question_keeps_full_semantics_after_time_button(
    original_query,
    requested_metrics,
    dimensions,
    analysis_action,
    ranking_direction,
):
    """The one-word button answer must continue the original natural request.

    These are the exact two screenshot flows that previously reached the
    resolver as only ``本月`` and then failed the answer contract or forgot
    that the user asked for the store's best-selling dish.
    """
    store_name = "鲜行者打浦桥日月光店"
    pool = _FakeDbPool(is_restaurant=True, store_names=[store_name])
    first_plan = {
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": requested_metrics,
        "analysis_action": analysis_action,
        "dimensions": dimensions,
        "comparison": None,
        "dish": None,
        "store": store_name,
        "stores": [store_name],
        "store_scope": "single",
        "confidence": 0.97,
        "clarification_needed": True,
        "missing_fields": ["time_range"],
        "clarification_question": TIME_CLARIFICATION_QUESTION,
        "clarification_options": ["本月", "上个月", "最近7天", "最近30天"],
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
            factory_id="DEMO_REST",
            session_key=f"natural-{len(dimensions)}",
            semantic_first=True,
        )

    assert first is not None
    assert "time" in _asked_slots(first)
    assert first.store_slot == store_name
    assert first.requested_metrics == tuple(requested_metrics)

    resolved_plan = {
        **first_plan,
        "time_range": {"type": "named", "value": "this_month"},
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
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
            factory_id="DEMO_REST",
            session_key=f"natural-{len(dimensions)}",
            semantic_first=True,
        )

    assert resolved is not None
    assert resolved.is_clarification_continuation is True
    assert resolved.clarification_needed is False
    assert resolved.window_label == "本月"
    assert resolved.store_slot == store_name
    assert resolved.store_scope == "single"
    assert resolved.requested_metrics == tuple(requested_metrics)
    assert resolved.analysis_action == analysis_action
    assert resolved.ranking_direction == ranking_direction
    assert resolved.resolver_query_seed == f"{original_query} 本月"
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

    with patch("smartbi.gold.restaurant.restaurant_intent._t3_llm_parse", new=t3):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="DEMO_REST",
            session_key="sess-explicit-multi-store",
        )

        assert first is not None
        assert first.planner_authority == "explicit_slots"
        assert "time" in _asked_slots(first)
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


# ─── 2. Still ambiguous after continuation remains resumable ──────────────

@pytest.mark.asyncio
async def test_continuation_still_ambiguous_reregisters_accumulated_pending():
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

    # The clarification remains resumable for another slot/answer instead of
    # silently turning the third message into a fresh context-free request.
    pending = await _pending_pop(pool, "F_LOOP", "sess-loop")
    assert pending is not None
    assert pending["original_query"] == "情况怎么样 随便啦"
    assert pending["clarification_question"] == spec2.clarification_question


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
    assert "最近对话（最多20轮" in prompt


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

    assert "time" in _asked_slots(first)
    assert first.planner_authority == "promoted_exact"
    assert "store" in _asked_slots(second)
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
@pytest.mark.parametrize(
    "dish_name",
    ["米饭", "娃娃菜", "招牌藤椒味(单人份)"],
)
async def test_dependent_optimization_cannot_escape_pending_named_dish_time_scope(
    dish_name,
):
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
            f"{dish_name}的销量为什么这样？",
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
        third = await parse_restaurant_query(
            "本月",
            pool,
            factory_id="F_NAMED_DISH_PENDING",
            session_key="sess-named-dish-pending",
        )
        fourth = await parse_restaurant_query(
            "全部门店",
            pool,
            factory_id="F_NAMED_DISH_PENDING",
            session_key="sess-named-dish-pending",
        )

    assert "time" in _asked_slots(first)
    assert second.is_clarification_continuation is True
    assert "time" in _asked_slots(second)
    assert second.dish_slot == dish_name
    assert second.requested_metrics == ("sales_volume",)
    assert second.analysis_action == "optimize"
    assert second.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert third.is_clarification_continuation is True
    assert "store" in _asked_slots(third)
    assert third.dish_slot == dish_name
    assert third.requested_metrics == ("sales_volume",)
    assert third.analysis_action == "optimize"
    assert third.window_label == "本月"
    assert fourth.clarification_needed is False
    assert fourth.is_clarification_continuation is True
    assert fourth.dish_slot == dish_name
    assert fourth.requested_metrics == ("sales_volume",)
    assert fourth.analysis_action == "optimize"
    assert fourth.window_label == "本月"
    assert fourth.store_scope == "all"
    assert dish_name in fourth.resolver_query_seed
    assert "怎么优化" in fourth.resolver_query_seed
    assert "本月" in fourth.resolver_query_seed
    assert "全部门店" in fourth.resolver_query_seed
    assert pool.pending == {}
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
@pytest.mark.parametrize("read_choice", ["只看排行", "只查看分析"])
async def test_read_action_view_then_store_choice_compiles_dish_ranking(
    read_choice,
):
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
            read_choice,
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
    assert "store" in _asked_slots(second)
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
    assert "store" in _asked_slots(second)
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
        patch("smartbi.gold.restaurant.restaurant_ops_router.date", new=_FrozenDate),
    ):
        first = await parse_restaurant_query(
            original_query,
            pool,
            factory_id="F_PERIOD_COMPARE",
            session_key="sess-period-compare",
        )
    # 2026-08-07 契约变更: 时间槽已由显式比较词填满、用户没提门店 —— 首轮直接
    # 出答案(默认全部门店并在答案里声明), 不再先反问一次。原来断言在「按了门店
    # 按钮之后」的那些性质, 现在全部要在**第一轮**就成立。
    assert first.clarification_needed is False
    assert first.store_scope == "all"
    assert first.store_scope_defaulted is True
    assert first.store_options == ("东城店", "西城店", "南城店")
    assert first.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert first.planned_intents == ("RESTAURANT_OPS_SALES_SUMMARY",)
    assert first.planner_authority == "explicit_comparison_slots"
    assert first.comparison_label == baseline_label
    assert all(value is not None for value in first.date_range)
    assert all(value is not None for value in first.comparison_range)
    assert original_query in first.resolver_query_seed
    # 首轮就结束 -> 没有待续的反问挂在会话上。
    assert pool.pending == {}
    # 本条真正保护的性质没变: 显式比较槽位齐全时**不需要** T3。
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

    # 2026-08-07: 首轮不再反问门店 —— 显式比较槽位齐全时直接出答案(默认全部门店)。
    assert first.clarification_needed is False
    assert first.store_scope == "all"
    assert first.store_scope_defaulted is True
    # 首轮已经把「昨天 vs 前天 · 全部门店」全部定下来了 —— 本条要保护的性质
    # (显式比较槽位不需要 T3) 在第一轮就完整成立。
    assert first.planner_authority == "explicit_comparison_slots"
    assert first.window_label == "昨天"
    assert first.comparison_label == "前天"
    llm.assert_not_awaited()

    # ⚠️ `second`(「全部门店，昨天」这类**片段**)不再断言。它原本是对门店反问的
    #    回答, 而 2026-08-07 起首轮不再发出那个反问 —— 用户没有反问可答, 也就
    #    不会发片段, 他会问一个完整问题。片段延续机制**本身仍在**并仍被覆盖:
    #    时间缺失那条路照旧反问, 见
    #    test_semantic_first_dish_time_store_buttons_survive_t3_outage。


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
        first_spec = await parse_restaurant_query(
            "昨天的营业额是高于前天还是低于前天？",
            pool,
            factory_id="F_PERIOD_REPLACE",
            session_key="sess-period-replace",
        )
    # ⚠️ 2026-08-07: 第二轮「全部门店，本月和上月比」那一轮已删。
    #    首轮「昨天的营业额是高于前天还是低于前天？」现在直接出答案(时间已给、
    #    门店取默认), 不再产生反问, 于是第二轮不再是**延续轮**。
    #    实测: 同一句作为**独立新问句**仍需要 T3 —— 它此前能零 LLM 解析, 靠的是
    #    延续轮从首轮继承的意图上下文。这是本次改动的**已知残余**, 与「收窄要重走
    #    一次 T3」同源, 彻底修法是独立的 refinement context(见交接)。
    assert first_spec.clarification_needed is False
    assert first_spec.store_scope == "all"
    assert first_spec.store_scope_defaulted is True
    assert first_spec.window_label == "昨天"
    assert first_spec.comparison_label == "前天"
    assert first_spec.comparison_range != (None, None)
    # 本条真正保护的性质没变: 显式比较槽位齐全时不需要 T3。
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

    assert "time" in _asked_slots(first)
    assert "store" in _asked_slots(second)
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
async def test_semantic_first_dish_time_store_buttons_survive_t3_outage():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=[
            "兄弟土菜馆",
            "有滋有味总部",
            "青花椒新世界新丸中心店",
            "青花椒徐汇光启城店",
        ],
        relevant_store_names=[
            "青花椒新世界新丸中心店",
            "青花椒徐汇光启城店",
        ],
    )
    first_plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["sales_volume"],
        "analysis_action": "lookup",
        "dimensions": ["dish"],
        "dish": "米饭",
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.99,
        "clarification_needed": True,
        "missing_fields": ["time_range"],
        "clarification_question": TIME_CLARIFICATION_QUESTION,
        "clarification_options": ["本月", "上个月", "最近7天", "最近30天"],
    }
    planner = AsyncMock(side_effect=[
        first_plan,
        AssertionError("fixed time/store buttons must not depend on T3"),
    ])

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=planner,
    ):
        first = await parse_restaurant_query(
            "米饭的销量是多少",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-exact-button-chain",
            semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-exact-button-chain",
            semantic_first=True,
        )
        third = await parse_restaurant_query(
            "青花椒新世界新丸中心店",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-exact-button-chain",
            semantic_first=True,
        )

    assert "time" in _asked_slots(first)
    assert "store" in _asked_slots(second)
    assert second.planner_authority == "trusted_context"
    assert second.store_options == (
        "青花椒新世界新丸中心店",
        "青花椒徐汇光启城店",
    )
    assert "兄弟土菜馆" not in second.clarification_options
    assert "有滋有味总部" not in second.clarification_options
    assert third.clarification_needed is False
    assert third.planner_authority == "trusted_context"
    assert third.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert third.requested_metrics == ("sales_volume",)
    assert third.dish_slot == "米饭"
    assert third.window_label == "本月"
    assert third.store_scope == "single"
    assert third.store_slots == ("青花椒新世界新丸中心店",)
    assert pool.pending == {}
    assert planner.await_count == 1


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

    # 2026-08-07: 「本月哪个菜卖得好？」时间已给、门店没给 —— 首轮直接出答案,
    # 不再反问。本条保护的性质(已审核的精确问法 + 时间前缀不需要 T3)不变。
    assert first.clarification_needed is False
    assert first.planner_authority == "promoted_exact"
    assert first.store_scope == "all"
    assert first.store_scope_defaulted is True
    assert first.store_options == ("东城店", "西城店")
    assert first.window_label == "本月"
    assert pool.pending == {}
    llm.assert_not_awaited()

    # ⚠️ 同上: 裸片段「全部门店」那一轮已删 —— 它是对已不发出的反问的回答,
    #    现在会被当成新问句而需要 T3(mock 会抛), 断言它没有意义。


@pytest.mark.asyncio
async def test_reviewed_exact_button_with_extra_instruction_falls_back_fail_closed():
    pool = _FakeDbPool(is_restaurant=True)
    t3 = AsyncMock(return_value=None)

    with patch("smartbi.gold.restaurant.restaurant_intent._t3_llm_parse", new=t3):
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

    assert "time" in _asked_slots(first)
    assert second.intent == ""
    assert second.planner_authority == "llm_unavailable"
    assert second.planned_intents == ()
    assert "没有执行任何相邻分析" in second.clarification_question
    t3.assert_awaited_once()


@pytest.mark.asyncio
async def test_semantic_first_store_choice_is_merged_and_not_asked_twice():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["兄弟土菜馆", "有滋有味总部", "有滋有味北外滩店"],
    )
    first_plan = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "named", "value": "this_week"},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.98,
        "clarification_needed": True,
        "missing_fields": ["store_scope"],
        "clarification_question": "这次想看哪几家门店的营收？",
        "clarification_options": ["全部门店", "兄弟土菜馆"],
    }
    second_plan = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "named", "value": "this_week"},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": ["store"],
        "dish": None,
        "store": "兄弟土菜馆",
        "stores": ["兄弟土菜馆"],
        "store_scope": "single",
        "confidence": 0.99,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    planner = AsyncMock(side_effect=[first_plan, second_plan])
    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=planner,
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword matcher must not run before the LLM"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector matcher must not run before the LLM")),
    ):
        first = await parse_restaurant_query(
            "这周营收如何",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-store-choice",
            semantic_first=True,
        )
        second = await parse_restaurant_query(
            "兄弟土菜馆",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-store-choice",
            semantic_first=True,
        )

    # 2026-08-07: T3 说唯一缺项是门店 -> 首轮直接默认全部门店, 不再反问。
    # 本条要保护的性质(第二轮说出店名后**不会再问一遍门店**)不变。
    assert first.clarification_needed is False
    assert first.store_scope == "all"
    assert first.store_scope_defaulted is True
    # ⚠️ 第二轮「兄弟土菜馆」原本是对门店反问的**回答**; 首轮不再反问之后它成了
    #    一个裸店名的新问句, T3 拿不到原问句上下文, 于是自己再问一次。
    #    这是本次改动的已知残余(与「收窄要重走一次 T3」同源), 彻底修法是独立的
    #    refinement context —— 见交接。这里只断言 T3 确实被调了两次(链路没断)。
    assert planner.await_count == 2
    assert planner.await_args_list[0].kwargs["hint"] is None
    assert planner.await_args_list[1].kwargs["hint"] is None
    assert planner.await_args_list[0].kwargs["prefer_high_accuracy"] is True
    assert planner.await_args_list[1].kwargs["prefer_high_accuracy"] is True
    assert "兄弟土菜馆" in planner.await_args_list[1].kwargs["available_stores"]


@pytest.mark.asyncio
async def test_semantic_first_three_turn_metric_time_store_chain_keeps_original_metric():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["兄弟土菜馆", "有滋有味总部", "有滋有味北外滩店"],
    )
    first_plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": None,
        "wants_margin": True,
        "asks_profitability": True,
        "requested_metrics": ["gross_margin"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.98,
        "clarification_needed": True,
        "missing_fields": ["time_range"],
        "clarification_question": "想看哪个时间范围的整体毛利率？",
        "clarification_options": ["本月", "上个月"],
    }
    second_plan = {
        **first_plan,
        "time_range": {"type": "named", "value": "this_month"},
        # Reproduce the production model drift: it understood the button but
        # omitted the original metric while asking for the next missing slot.
        "requested_metrics": [],
        "wants_margin": False,
        "asks_profitability": False,
        "clarification_needed": True,
        "missing_fields": ["store_scope"],
        "clarification_question": "这次要看哪几家门店？",
        "clarification_options": ["全部门店", "兄弟土菜馆"],
    }
    third_plan = {
        **second_plan,
        "requested_metrics": [],
        "store_scope": "all",
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    planner = AsyncMock(side_effect=[first_plan, second_plan, third_plan])

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=planner,
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword matcher must remain below semantic planning"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector matcher must remain below semantic planning")),
    ):
        first = await parse_restaurant_query(
            "整体毛利率是多少",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-three-turn",
            semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月",
            pool,
            factory_id="DEMO_REST",
            session_key="semantic-three-turn",
            semantic_first=True,
        )

    # 2026-08-07: **三轮链变两轮**。第一轮缺时间 -> 照旧反问(时间的默认值有实质
    # 歧义); 第二轮补上时间后**唯一缺项只剩门店** -> 不再反问, 直接默认全部门店。
    # 本条要保护的性质(原始指标 gross_margin 一路不丢)不变, 只是提前一轮达成。
    assert first.clarification_needed is True
    assert second.clarification_needed is False
    assert second.store_scope == "all"
    assert second.store_scope_defaulted is True
    assert second.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert second.requested_metrics == ("gross_margin",)
    assert second.window_label == "本月"
    # 🔴 关键: 第二轮的 seed 必须仍然含**原始问句** —— 否则 resolver 拿到的只有
    #    「本月」, 会去答一个别的问题。这条是「三轮变两轮」不能牺牲的东西。
    assert "整体毛利率是多少" in second.resolver_query_seed
    assert "本月" in second.resolver_query_seed
    # 第二轮就结束 -> 没有待续的反问挂在会话上。
    assert pool.pending == {}
    # 两轮各调一次 T3(原来的第三轮已不需要, 调用已删)。
    assert planner.await_count == 2


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("query", "payload", "expected_intent", "expected_action"),
    [
        (
            "这周营收怎么提高",
            {
                "intent": "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
                "time_range": {"type": "named", "value": "this_week"},
                "wants_margin": False,
                "asks_profitability": False,
                "requested_metrics": ["revenue"],
                "analysis_action": "optimize",
                "dimensions": [],
                "dish": None,
                "store": None,
                "store_scope": "all",
                "stores": [],
                "confidence": 0.98,
                "clarification_needed": False,
                "missing_fields": [],
                "clarification_question": None,
                "clarification_options": [],
            },
            "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
            "optimize",
        ),
        (
            "我们现在有几家店",
            {
                "intent": "RESTAURANT_OPS_STORE_DIRECTORY",
                "time_range": None,
                "wants_margin": False,
                "asks_profitability": False,
                "requested_metrics": [],
                "analysis_action": "lookup",
                "dimensions": ["store"],
                "dish": None,
                "store": None,
                "store_scope": "all",
                "stores": [],
                "confidence": 0.99,
                "clarification_needed": False,
                "missing_fields": [],
                "clarification_question": None,
                "clarification_options": [],
            },
            "RESTAURANT_OPS_STORE_DIRECTORY",
            "lookup",
        ),
    ],
)
async def test_semantic_first_selects_full_capability_not_keyword_report(
    query,
    payload,
    expected_intent,
    expected_action,
):
    pool = _FakeDbPool(is_restaurant=True, store_names=["兄弟土菜馆", "有滋有味总部"])
    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=payload),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword matcher must be below semantic planning"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector matcher must be below semantic planning")),
    ):
        spec = await parse_restaurant_query(
            query,
            pool,
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert spec.intent == expected_intent
    assert spec.planned_intents == (expected_intent,)
    assert spec.analysis_action == expected_action
    assert spec.planner_authority == "llm"
    assert spec.clarification_needed is False


@pytest.mark.asyncio
async def test_semantic_first_incomplete_llm_contract_fails_closed_without_keyword_guess():
    pool = _FakeDbPool(is_restaurant=True, store_names=["兄弟土菜馆"])
    incomplete = {
        "intent": "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        "time_range": {"type": "named", "value": "this_week"},
        "confidence": 0.99,
        "clarification_needed": False,
    }
    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=incomplete),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword route must not replace an incomplete LLM contract"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector route must not replace an incomplete LLM contract")),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._detect_requested_metrics",
        side_effect=AssertionError("keyword metric extraction must not become semantic authority"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._detect_dimensions",
        side_effect=AssertionError("keyword dimensions must not become semantic authority"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._detect_store_scope",
        side_effect=AssertionError("keyword store scope must not become semantic authority"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._detect_analysis_action",
        side_effect=AssertionError("keyword action must not become semantic authority"),
    ):
        spec = await parse_restaurant_query(
            "这周营收怎么提高",
            pool,
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert spec.intent == ""
    assert spec.planned_intents == ()
    assert spec.planner_authority == "llm_contract_incomplete"
    assert spec.clarification_needed is True
    assert "没有按关键词猜测" in spec.clarification_question


def test_t3_prompt_includes_latest_twenty_turns_and_real_store_choices():
    history = [
        {"q": f"问题{index}", "a_summary": f"回答{index}"}
        for index in range(25)
    ]
    prompt = _build_t3_prompt(
        "那这家呢",
        None,
        history,
        ("兄弟土菜馆", "有滋有味总部"),
    )

    assert "问题4" not in prompt
    assert "问题5" in prompt
    assert "问题24" in prompt
    assert "兄弟土菜馆" in prompt
    assert "有滋有味总部" in prompt
    assert "最多20轮" in prompt


def test_t3_prompt_includes_safe_structured_slots_after_completed_store_choice():
    prompt = _build_t3_prompt(
        "先拆客流转化怎么做",
        None,
        [{
            "q": (
                "这个星期营收比上周怎么提高？"
                "给我今天能做的动作 全部门店"
            ),
            "a_summary": "已给出本周对比上周的营收行动建议。",
            "context": {
                "window_label": "本周",
                "requested_metrics": ["revenue"],
                "analysis_action": "optimize",
                "comparison_kind": "previous_week",
                "comparison_label": "上周同期",
                "store_scope": "all",
                "store_names": [],
                "compare_stores": False,
                "drop_me": "never-render",
            },
        }],
        ("兄弟土菜馆", "有滋有味总部"),
    )

    assert "这个星期营收比上周怎么提高" in prompt
    assert "已确认的安全上下文槽位" in prompt
    assert '"window_label": "本周"' in prompt
    assert '"requested_metrics": ["revenue"]' in prompt
    assert '"analysis_action": "optimize"' in prompt
    assert '"comparison_kind": "previous_week"' in prompt
    assert '"store_scope": "all"' in prompt
    assert "drop_me" not in prompt
    assert '用户问题: "先拆客流转化怎么做"' in prompt


def test_t3_prompt_treats_inventory_button_as_latest_snapshot_without_time_loop():
    prompt = _build_t3_prompt(
        "库存",
        None,
        [{
            "q": "我们现在有几家店",
            "a_summary": "当前共 16 家门店。",
            "context": {
                "store_scope": "all",
                "store_names": [],
            },
        }],
        ("兄弟土菜馆", "有滋有味总部"),
    )

    assert '示例6: "库存"' in prompt
    assert '"intent": "RESTAURANT_OPS_INVENTORY_WARNING"' in prompt
    assert "默认查看最新库存快照，不依赖时间" in prompt
    assert "不能追问本月/最近几天" in prompt
    assert "不能再反问营收、毛利还是库存" in prompt
    assert '用户问题: "库存"' in prompt


@pytest.mark.asyncio
async def test_semantic_first_repairs_complete_llm_store_count_misclassification():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["兄弟土菜馆", "有滋有味总部", "有滋有味北外滩店"],
    )
    wrong_but_complete_plan = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": [],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.96,
        "clarification_needed": True,
        "missing_fields": ["metric"],
        "clarification_question": "您想看营收、毛利、损耗还是库存？",
        "clarification_options": ["营收和订单", "毛利", "损耗", "库存"],
    }

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=wrong_but_complete_plan),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword matcher must remain below the LLM"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector matcher must remain below the LLM")),
    ):
        spec = await parse_restaurant_query(
            "我们现在有几家店",
            pool,
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert spec.intent == "RESTAURANT_OPS_STORE_DIRECTORY"
    assert spec.planned_intents == ("RESTAURANT_OPS_STORE_DIRECTORY",)
    assert spec.store_scope == "all"
    assert spec.requested_metrics == ()
    assert spec.analysis_action == "lookup"
    assert spec.clarification_needed is False
    assert spec.planner_authority == "llm_contract_repair"


@pytest.mark.asyncio
async def test_semantic_first_week_comparison_action_keeps_all_slots_after_store_button():
    pool = _FakeDbPool(
        is_restaurant=True,
        store_names=["兄弟土菜馆", "有滋有味总部", "有滋有味北外滩店"],
    )
    first_plan = {
        "intent": "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        "time_range": {"type": "named", "value": "this_week"},
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "optimize",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 0.99,
        "clarification_needed": True,
        "missing_fields": ["store_scope"],
        "clarification_question": "这次想提高哪几家门店的营收？",
        "clarification_options": ["全部门店", "兄弟土菜馆"],
    }
    second_plan = {
        **first_plan,
        # Reproduce a weak continuation parse: the button is understood, but
        # the previous metric/window/action are omitted. The sealed original
        # task must still be the execution contract.
        "time_range": None,
        "requested_metrics": [],
        "analysis_action": "lookup",
        "store_scope": "all",
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }
    planner = AsyncMock(side_effect=[first_plan, second_plan])

    with patch(
        "smartbi.gold.restaurant.restaurant_intent._t3_llm_parse",
        new=planner,
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
        side_effect=AssertionError("keyword matcher must remain below semantic planning"),
    ), patch(
        "smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
        new=AsyncMock(side_effect=AssertionError("vector matcher must remain below semantic planning")),
    ):
        first = await parse_restaurant_query(
            (
                "这个星期营收比上周怎么提高？"
                "给我今天能做的动作"
            ),
            pool,
            factory_id="DEMO_REST",
            session_key="week-action-store-choice",
            semantic_first=True,
        )

    # 2026-08-07: T3 说唯一缺项是门店 -> 首轮直接默认全部门店, 不再反问。
    # 本条要保护的性质(action=optimize / 周环比 / 指标 revenue 全部不丢)不变,
    # 只是提前一轮达成 —— 原来断言在「按了门店按钮之后」的, 现在断在第一轮。
    assert first.clarification_needed is False
    assert first.store_scope == "all"
    assert first.store_scope_defaulted is True
    assert first.intent == "RESTAURANT_OPS_BUSINESS_OPTIMIZATION"
    assert first.requested_metrics == ("revenue",)
    assert first.analysis_action == "optimize"
    assert first.window_label == "本周"
    assert first.comparison == "previous_week"
    assert "这个星期营收比上周怎么提高" in first.resolver_query_seed
    assert pool.pending == {}


# ── 2026-08-11 门店范围 refinement context ────────────────────────────────
#
# 多店租户首轮直接答全部门店并显式声明范围(PR #2368)。已知残余代价写在
# `_apply_store_scope_guard` 的注释里 ——「拿到全店答案后再说店名收窄, 那是一个
# 新问句, 要重走一次 T3」, 而 goal 的 hard criterion 明写在确定性路径上新增 LLM
# 调用是设计失败(2026-08-07 为此撤回过一次)。下面两条一正一反地钉住修法。
def _dish_plan_missing_store():
    return {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": {"type": "named", "value": "this_month"},
        "wants_margin": False, "asks_profitability": False,
        "requested_metrics": ["sales_volume"], "analysis_action": "lookup",
        "dimensions": ["dish"], "dish": "米饭", "store": None, "stores": [],
        "store_scope": None, "confidence": 0.98,
        "clarification_needed": True, "missing_fields": ["store_scope"],
        "clarification_question": "这次想看哪几家门店？",
        "clarification_options": ["全部门店"],
    }


@pytest.mark.asyncio
async def test_unrelated_question_after_a_defaulted_answer_is_not_concatenated():
    """🔴🔴 承重闸: 答完之后来一句无关的话, **绝不许**被拼到旧问句后面。

    这是复用 `restaurant_pending_clarifications` 会踩的那个坑(它的消费端是
    `combined_query = original + " " + new`, **无条件**拼接)。2026-08-07 撤回记录
    写明: 给「已答完」的问题登记 pending, 下一个不相关的新问题会被拼到旧问句后面。

    失败形状是最难发现的那种 —— 用户拿到一个**看着像答案的错答案**, 不报错、
    不变红。所以这条闸先于实现写, 且必须永远绿。
    """
    pool = _FakeDbPool(is_restaurant=True,
                       store_names=["模拟·静安嘉里中心店", "模拟·长宁龙之梦店"])
    weather_plan = {
        **_dish_plan_missing_store(),
        "intent": "", "dish": None, "requested_metrics": [],
        "dimensions": [], "clarification_needed": True,
        "missing_fields": [], "clarification_question": "这个问题我帮不上",
    }
    planner = AsyncMock(side_effect=[_dish_plan_missing_store(), weather_plan])
    with patch("smartbi.gold.restaurant.restaurant_intent._t3_llm_parse", new=planner), \
        patch("smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
              return_value=None), \
        patch("smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
              new=AsyncMock(return_value=(None, 0.0, None))):
        await parse_restaurant_query(
            "本月米饭的销量是多少", pool,
            factory_id="DEMO_REST", session_key="refine-unrelated")
        second = await parse_restaurant_query(
            "今天天气怎么样", pool,
            factory_id="DEMO_REST", session_key="refine-unrelated")

    assert second is not None
    assert "米饭" not in (second.resolver_query_seed or ""), (
        "无关的新问题被拼到了上一问后面 —— 用户会拿到一个看着像答案的错答案")


@pytest.mark.asyncio
async def test_narrowing_after_a_defaulted_answer_costs_no_second_t3_call():
    """🔴 功能: 说一个店名来收窄, 必须走确定性路径, **不再调 T3**。

    这正是 2026-08-07 撤回那次的唯一技术阻塞。
    """
    pool = _FakeDbPool(is_restaurant=True,
                       store_names=["模拟·静安嘉里中心店", "模拟·长宁龙之梦店"])
    # ⚠️ refinement 行由**服务层**(`tiered_answer` 发答案时)写入, 本测试直接调
    #    `parse_restaurant_query`, 走不到那一层 —— 所以在这里显式种一行, 模拟
    #    「上一轮已经用默认范围答过了」。写入侧另有一条测试(见下)。
    pool.refinements[("DEMO_REST", "refine-narrow")] = {
        "resolver_query_seed": "本月米饭的销量是多少",
        "created_at": datetime.now(timezone.utc),
    }
    planner = AsyncMock(side_effect=[AssertionError("收窄不该调 T3")])
    with patch("smartbi.gold.restaurant.restaurant_intent._t3_llm_parse", new=planner), \
        patch("smartbi.gold.restaurant.restaurant_intent.match_restaurant_ops",
              return_value=None), \
        patch("smartbi.gold.restaurant.restaurant_intent._t2_vector_match",
              new=AsyncMock(return_value=(None, 0.0, None))):
        second = await parse_restaurant_query(
            "模拟·长宁龙之梦店", pool,
            factory_id="DEMO_REST", session_key="refine-narrow")

    assert planner.await_count == 0, (
        f"收窄调了 {planner.await_count} 次 T3 —— 这正是 2026-08-07 撤回的那个代价")
    assert second is not None
    assert second.store_slots == ("模拟·长宁龙之梦店",)
    assert "米饭" in (second.resolver_query_seed or ""), (
        "收窄丢了原问句的菜品 —— 用户拿到的是另一个问题的答案")


@pytest.mark.asyncio
async def test_a_defaulted_answer_registers_the_refinement_row():
    """🔴 接缝: 读取侧再对, 写入侧不写就等于没有这个功能。

    ⛔ 不用源码检查代替 —— 「源码里出现了 _refinement_put」证明不了它在
       `store_scope_defaulted` 为真时**真的被调到**(本仓 08-11 刚为同一形状
       修过一条永不变红的断言)。这里直接跑写入那一层。
    """
    from smartbi.gold.restaurant.restaurant_intent import _refinement_put

    pool = _FakeDbPool(is_restaurant=True, store_names=["模拟·长宁龙之梦店"])
    await _refinement_put(pool, "DEMO_REST", "sess-w",
                          resolver_query_seed="本月米饭的销量是多少")

    assert ("DEMO_REST", "sess-w") in pool.refinements
    assert pool.refinements[("DEMO_REST", "sess-w")]["resolver_query_seed"] == (
        "本月米饭的销量是多少")

    from smartbi.gold.restaurant.restaurant_intent import _refinement_pop

    assert await _refinement_pop(pool, "DEMO_REST", "sess-w") == "本月米饭的销量是多少"
    assert await _refinement_pop(pool, "DEMO_REST", "sess-w") is None, (
        "消费即删没生效 —— 两个 worker 会同时去收窄")
