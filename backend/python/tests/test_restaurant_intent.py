"""Unit tests for the tiered restaurant intent router + Answer Contract.

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md

Everything here is mocked (no DashScope, no pgvector, no live LLM router) --
mirrors the mocking style of test_template_rag.py (patch cosine_topk) and
test_restaurant_ops_router.py (monkeypatch + asyncio.run for the handful of
sync-style helper checks).
"""
from __future__ import annotations

import json
from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold import answer_contract as contract
from smartbi.gold.restaurant_intent import (
    RestaurantQuerySpec,
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    _apply_store_scope_guard,
    build_resolver_query,
    capability_clarification_question,
    clear_route_cache,
    clear_tenant_gate_cache,
    contextualize_restaurant_followup,
    optimization_clarification_question,
    parse_restaurant_query,
    _build_spec,
    _detect_comparison,
    _detect_dimensions,
    _detect_requested_metrics,
    _explicit_financial_overview_spec,
    _explicit_named_dish_metric_spec,
    _explicit_revenue_trend_spec,
    _explicit_sales_period_comparison_spec,
    _explicit_store_dish_ranking_spec,
    _explicit_store_operations_spec,
    _semantic_spec_from_t3,
    _trusted_context_dish_followup_spec,
    _verbatim_entity,
)
from smartbi.gold.restaurant_ops_router import (
    _resolve_sales_date_range,
    _resolve_sales_query_spec,
    extract_store_mention,
    match_restaurant_ops,
)
from smartbi.services.template_rag import hybrid_match


@pytest.fixture(autouse=True)
def _reset_caches():
    clear_route_cache()
    clear_tenant_gate_cache()
    yield
    clear_route_cache()
    clear_tenant_gate_cache()


class _FakeConn:
    """Minimal asyncpg connection double -- only `fetchrow` is needed here
    (the tenant gate's only query). Mirrors the _FakeConn/_FakePool pattern
    from test_analysis_restaurant_ops.py / test_analysis_finance_restaurant.py."""

    def __init__(self, fetchrow_result):
        self._fetchrow_result = fetchrow_result
        self.fetchrow_calls = 0
        self.in_transaction = False
        self.active_factory = None

    def transaction(self):
        conn = self

        class _Ctx:
            async def __aenter__(self):
                conn.in_transaction = True
                conn.active_factory = None
                return None

            async def __aexit__(self, *_exc):
                conn.active_factory = None
                conn.in_transaction = False
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        if "set_config('app.factory_id'" not in sql:
            raise AssertionError(f"unexpected execute SQL: {sql}")
        assert self.in_transaction is True
        self.active_factory = args[0]
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls += 1
        assert self.in_transaction is True
        assert self.active_factory == args[0]
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


class _FailingPool:
    def acquire(self):
        raise RuntimeError("tenant gate unavailable")


def _restaurant_pool() -> _FakePool:
    """A pool double whose tenant-gate fetchrow() finds a row (this factory
    has data in agg_restaurant_daily_totals) -- satisfies `_is_restaurant_tenant`."""
    return _FakePool(_FakeConn({"?column?": 1}))


def _non_restaurant_pool() -> _FakePool:
    return _FakePool(_FakeConn(None))


@pytest.mark.asyncio
async def test_tenant_gate_outage_fails_closed_without_routing_fallback():
    spec = await parse_restaurant_query(
        "哪个菜卖得好",
        _FailingPool(),
        factory_id="DEMO_REST",
    )

    assert spec is not None
    assert spec.intent == ""
    assert spec.clarification_needed is True
    assert spec.planner_authority == "tenant_gate_unavailable"
    assert spec.planned_intents == ()
    assert spec.plan_hash


# ─── 1. Time × metric × object matrix (≥30 variants, spec section 6) ──────
# Exercises the deterministic slot layer (_build_spec -> _resolve_sales_date_range
# / _profit_intent / _detect_dimensions) shared by all three tiers -- this is
# the actual regression guard for "换个说法还能拿到正确时间窗" the whole
# project protects against ("最近两个月的营收情况如何，赚钱了吗" degrading to
# full-history was exactly this class of bug).

_TIME_PHRASES = ["最近两个月", "近3周", "这个月", "上周", "今天", "过去十天"]
_METRIC_PHRASES = ["赚钱了吗", "亏不亏", "毛利多少", "营收多少"]
_OBJECT_PHRASES = ["哪家店拖后腿", "菜品毛利", "库存盘亏", "领料"]


def _time_metric_matrix():
    for t in _TIME_PHRASES:
        for m in _METRIC_PHRASES:
            yield f"{t}{m}"
    for t in _TIME_PHRASES[:2]:  # pad past 30 with object combos too
        for o in _OBJECT_PHRASES:
            yield f"{t}{o}"


_MATRIX_QUERIES = list(_time_metric_matrix())


def test_matrix_has_at_least_30_variants():
    assert len(_MATRIX_QUERIES) >= 30


@pytest.mark.parametrize("query", _MATRIX_QUERIES)
def test_deterministic_slot_layer_matches_ground_truth(query):
    """`_build_spec` must delegate to the SAME `_resolve_sales_date_range` /
    `_profit_intent` the resolvers themselves use -- never reimplement or
    diverge. Ground truth is computed by calling those functions directly."""
    expected_date_range, expected_window_label = _resolve_sales_date_range(query)
    spec = _build_spec("RESTAURANT_OPS_SALES_SUMMARY", query, confidence=1.0, tier="test")
    assert spec.date_range == expected_date_range
    assert spec.window_label == expected_window_label


@pytest.mark.parametrize("query", [f"{t}{m}" for t in _TIME_PHRASES for m in ("赚钱了吗", "亏不亏")])
def test_matrix_profitability_detected(query):
    spec = _build_spec("RESTAURANT_OPS_SALES_SUMMARY", query, confidence=1.0, tier="test")
    assert spec.asks_profitability is True
    assert spec.wants_margin is True


@pytest.mark.parametrize("query", [f"{t}毛利多少" for t in _TIME_PHRASES])
def test_matrix_margin_without_profitability_verdict(query):
    """毛利多少 asks for a margin number but is not itself a yes/no
    profitability question."""
    spec = _build_spec("RESTAURANT_OPS_SALES_SUMMARY", query, confidence=1.0, tier="test")
    assert spec.wants_margin is True


def test_matrix_object_dimensions_detected():
    assert "store" in _build_spec("RESTAURANT_OPS_STORE_MARGIN", "哪家店拖后腿", confidence=1.0, tier="test").dimensions
    assert "dish" in _build_spec("RESTAURANT_OPS_GROSS_MARGIN", "菜品毛利", confidence=1.0, tier="test").dimensions
    assert "ingredient" in _build_spec("RESTAURANT_OPS_REQUISITION_TREND", "领料", confidence=1.0, tier="test").dimensions


@pytest.mark.parametrize(
    "query,baseline_label",
    [
        ("昨天的营业额是高于前天还是低于前天？", "前天"),
        ("上个月营业额和上上个月相比怎么样", "上上个月"),
    ],
)
def test_query_plan_seals_primary_and_baseline_windows(query, baseline_label):
    expected = _resolve_sales_query_spec(query)
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        confidence=1.0,
        tier="explicit_comparison_slots",
        planner_authority="explicit_comparison_slots",
        require_explicit_time=True,
    )

    assert spec.date_range == expected.date_range
    assert spec.comparison_range == expected.comparison_range
    assert spec.comparison_label == baseline_label
    assert spec.comparison == expected.comparison_kind
    assert spec.analysis_action == "compare"
    assert spec.plan_hash


# ─── 2. Tiered routing ─────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_t1_hit_becomes_hint_and_llm_remains_semantic_authority():
    query = "哪家店最赚钱"
    assert match_restaurant_ops(query) == "RESTAURANT_OPS_STORE_MARGIN"

    pool = _restaurant_pool()
    llm = AsyncMock(return_value={
        "intent": "RESTAURANT_OPS_STORE_MARGIN",
        "confidence": 0.93,
        "clarification_needed": False,
    })
    with patch("smartbi.gold.restaurant_intent._t2_vector_match", new=AsyncMock(side_effect=AssertionError("T2 must not run"))), \
         patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=llm):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.source_tier == "llm"
    assert spec.planner_authority == "llm"
    assert spec.plan_hash
    assert llm.await_args.kwargs["hint"] == ("RESTAURANT_OPS_STORE_MARGIN", 0.95)


@pytest.mark.asyncio
@pytest.mark.parametrize(("query", "wrong_intent", "expected_dish"), [
    ("哪些菜卖得好", "RESTAURANT_OPS_SALES_SUMMARY", None),
    ("本月哪些菜卖得最好？", "RESTAURANT_OPS_SALES_SUMMARY", None),
    ("近30天畅销菜品", "RESTAURANT_OPS_REQUISITION_TREND", None),
    ("本月米饭的销量是多少", "RESTAURANT_OPS_REQUISITION_TREND", "米饭"),
])
async def test_explicit_dish_sales_contract_repairs_live_llm_failure_shape(
    query,
    wrong_intent,
    expected_dish,
):
    llm = AsyncMock(return_value={
        "intent": wrong_intent,
        "confidence": 0.95,
        "clarification_needed": True,
        "clarification_question": "请明确想查销量、营收还是毛利。",
    })
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=llm,
    ):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.clarification_needed is False
    assert spec.clarification_question is None
    assert spec.dish_slot == expected_dish
    if expected_dish:
        assert spec.planner_authority == "explicit_named_dish_slots"
        llm.assert_not_awaited()
    else:
        assert spec.planner_authority == "llm_contract_repair"
        llm.assert_awaited_once()


@pytest.mark.asyncio
async def test_t1_miss_t2_high_confidence_is_only_llm_hint():
    query = "生意最近怎么样啊能不能多赚点"
    assert match_restaurant_ops(query) is None  # confirm this really is a T1 miss

    pool = _restaurant_pool()
    llm = AsyncMock(return_value={
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "confidence": 0.91,
        "clarification_needed": False,
    })
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[("RESTAURANT_OPS_SALES_SUMMARY", 0.86, "总体销售情况怎么样")]),
    ) as mock_topk, patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse", new=llm,
    ):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.source_tier == "llm"
    assert spec.confidence == pytest.approx(0.91)
    assert llm.await_args.kwargs["hint"] == ("RESTAURANT_OPS_SALES_SUMMARY", 0.86)
    # T2 must scope the vector search to the restaurant namespace.
    _, kwargs = mock_topk.call_args
    assert kwargs.get("code_prefix") == "RESTAURANT_OPS_"


@pytest.mark.asyncio
async def test_t2_low_confidence_falls_to_t3_llm_and_ignores_llm_dates():
    """T2 returns an ambiguous (0.70-0.78) candidate -> becomes a hint for T3.
    T3's JSON gives a structured (non-date) time_range; the REAL date_range
    must come from `_resolve_sales_date_range`, not anything LLM-shaped."""
    query = "这两个月生意咋样，挣着钱没"

    pool = _restaurant_pool()
    llm_json = json.dumps({
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "month", "count": 2},
        "wants_margin": True,
        "asks_profitability": True,
        "dimensions": [],
        "comparison": None,
        "confidence": 0.9,
        "clarification_needed": False,
        "clarification_question": None,
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}

    with patch(
        "smartbi.gold.restaurant_intent.match_restaurant_ops", return_value=None,
    ), patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[("RESTAURANT_OPS_SALES_SUMMARY", 0.73, "总体经营")]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)) as mock_chain:
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.source_tier == "llm"
    assert spec.confidence == pytest.approx(0.9)
    # The real date window must equal what the deterministic resolver
    # computes for "最近2个月" -- i.e. the LLM's structured hint was
    # correctly translated into a phrase the SAME deterministic function
    # parses, not trusted for the actual dates.
    expected_range, expected_label = _resolve_sales_date_range("最近2个月")
    assert spec.window_label == expected_label
    assert spec.date_range == expected_range
    # SLOT.MAPPER (thinking off / temperature 0) must be the slot used.
    from common.llm_router import SLOT
    args, kwargs = mock_chain.call_args
    assert args[0] == SLOT.MAPPER
    assert kwargs["timeout"] == 2.5
    assert kwargs["total_timeout"] == 6.0


@pytest.mark.asyncio
async def test_semantic_first_front_door_uses_high_accuracy_review_budget():
    """Production restaurant chat must not inherit MAPPER's six-second tail.

    The live regression had two fast 403 responses followed by several
    candidates timing out on the tiny remaining budget.  Semantic-first chat
    therefore uses the billing-safe REVIEW chain and its bounded fallback
    budget, while the legacy T3 test above remains on MAPPER.
    """
    llm_json = json.dumps({
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
        "confidence": 0.98,
        "clarification_needed": True,
        "missing_fields": ["time_range", "store_scope"],
        "clarification_question": "想看哪个时间范围和哪些门店？",
        "clarification_options": ["本月", "全部门店"],
    }, ensure_ascii=False)
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}

    with patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=fake_llm_result),
    ) as mock_chain:
        spec = await parse_restaurant_query(
            "米饭的销量是多少",
            _restaurant_pool(),
            factory_id="DEMO_REST",
            semantic_first=True,
        )

    assert spec is not None
    assert spec.planner_authority == "llm"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.dish_slot == "米饭"
    assert spec.clarification_needed is True
    from common.llm_router import SLOT
    args, kwargs = mock_chain.call_args
    assert args[0] == SLOT.REVIEW
    assert kwargs["timeout"] == 5.0
    assert kwargs["total_timeout"] == 12.0


@pytest.mark.asyncio
async def test_semantic_first_inherits_typed_dish_slots_before_llm_planning():
    """A metric-only follow-up keeps the trusted object/time/store scope.

    The LLM remains the sole intent authority; it receives a complete,
    resolver-grounded utterance instead of being asked to rediscover the dish
    from prose history.
    """
    history = [{
        "q": "全部门店卤炸牛肉串本月销量为什么高",
        "a_summary": "本月卤炸牛肉串销量 251.56 份。",
        "context": {
            "focus_entity": {"type": "dish", "name": "卤炸牛肉串"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "analysis_action": "diagnose",
            "store_scope": "all",
            "store_names": [],
        },
    }]
    llm_plan = {
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "time_range": {"type": "relative", "unit": "month", "count": 1},
        "wants_margin": True,
        "asks_profitability": True,
        "requested_metrics": ["gross_margin"],
        "analysis_action": "lookup",
        "dimensions": ["dish"],
        "dish": "卤炸牛肉串",
        "store": None,
        "stores": [],
        "store_scope": "all",
        "confidence": 0.99,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }

    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=llm_plan),
    ) as mock_parse:
        spec = await parse_restaurant_query(
            "那毛利呢",
            _restaurant_pool(),
            factory_id="DEMO_REST",
            history=history,
            semantic_first=True,
        )

    effective_query = mock_parse.await_args.args[0]
    assert effective_query == "本月全部门店卤炸牛肉串的毛利呢"
    assert spec is not None
    assert spec.planner_authority == "llm"
    assert spec.dish_slot == "卤炸牛肉串"
    assert spec.window_label == "本月"
    assert spec.store_scope == "all"
    assert spec.requested_metrics == ("gross_margin",)
    assert spec.clarification_needed is False


@pytest.mark.asyncio
async def test_semantic_first_repairs_false_missing_slots_after_llm_planning():
    """A valid LLM contract cannot re-ask slots stored by the resolver."""
    history = [{
        "q": "全部门店卤炸牛肉串本月销量为什么高",
        "a_summary": "本月卤炸牛肉串销量 251.56 份。",
        "context": {
            "focus_entity": {"type": "dish", "name": "卤炸牛肉串"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "analysis_action": "diagnose",
            "store_scope": "all",
            "store_names": [],
        },
    }]
    false_clarification = {
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
        "confidence": 0.96,
        "clarification_needed": True,
        "missing_fields": ["time_range", "store_scope", "object"],
        "clarification_question": "你想看哪个时间范围的哪道菜？",
        "clarification_options": ["本月", "最近30天"],
    }

    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=false_clarification),
    ) as mock_parse:
        spec = await parse_restaurant_query(
            "那毛利呢",
            _restaurant_pool(),
            factory_id="DEMO_REST",
            # Production asyncpg returns JSONB as text unless a custom codec
            # is registered.  Exercise that real boundary shape here.
            history=json.dumps(history, ensure_ascii=False),
            semantic_first=True,
        )

    assert mock_parse.await_args.args[0] == "本月全部门店卤炸牛肉串的毛利呢"
    assert mock_parse.await_args.kwargs["history"] == tuple(history)
    assert spec is not None
    assert spec.planner_authority == "llm_trusted_context_repair"
    assert spec.source_tier == "llm"
    assert spec.clarification_needed is False
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.dish_slot == "卤炸牛肉串"
    assert spec.window_label == "本月"
    assert spec.store_scope == "all"
    assert spec.requested_metrics == ("gross_margin",)
    assert spec.analysis_action == "lookup"
    assert spec.plan_hash


@pytest.mark.asyncio
async def test_t3_adversarial_raw_date_in_time_range_is_ignored():
    """If a (malformed/adversarial) LLM response smuggles a raw date string
    into time_range instead of a structured descriptor, it must be silently
    ignored (fail-open on the parse) -- the resolver falls back to whatever
    the deterministic parser derives from the raw query alone."""
    query = "上个季度营收如何"
    assert match_restaurant_ops(query) is None

    pool = _restaurant_pool()
    llm_json = json.dumps({
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": "2026-01-01 to 2026-03-31",  # NOT the expected dict shape
        "confidence": 0.8,
        "clarification_needed": False,
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    # The malformed LLM date is ignored, and the system must ask the user
    # instead of silently substituting an arbitrary default window.
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION


@pytest.mark.asyncio
async def test_no_time_query_rejects_llm_invented_default_window():
    query = "招牌藤椒味(单人份)销量如何"
    pool = _restaurant_pool()
    llm_json = json.dumps({
        "intent": "RESTAURANT_OPS_GROSS_MARGIN",
        "dish": "招牌藤椒味(单人份)",
        # Even a structurally valid LLM supplement cannot invent user intent.
        "time_range": {"type": "relative", "unit": "day", "count": 30},
        "confidence": 0.95,
        "clarification_needed": False,
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch(
        "common.llm_router.call_chain",
        new=AsyncMock(return_value=fake_llm_result),
    ):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION


@pytest.mark.asyncio
async def test_semantic_planner_outage_fails_closed():
    query = "随便聊聊宇宙"

    pool = _restaurant_pool()
    with patch("smartbi.gold.restaurant_intent.match_restaurant_ops", return_value=None), patch(
        "smartbi.gold.restaurant_intent._t2_vector_match", new=AsyncMock(return_value=(None, 0.0, None)),
    ), patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=AsyncMock(return_value=None)):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.intent == ""
    assert spec.clarification_needed is True
    assert spec.planner_authority == "llm_unavailable"
    assert "没有执行任何相邻分析" in spec.clarification_question


@pytest.mark.asyncio
async def test_approved_exact_phrase_survives_planner_outage_without_keyword_contains():
    t3 = AsyncMock(side_effect=AssertionError("approved exact phrase must not call T3"))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            " 哪个菜卖得好？ ",
            _restaurant_pool(),
            factory_id="QHJ01",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "promoted_exact"
    assert spec.source_tier == "exact"
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_approved_exact_phrase_does_not_authorize_a_longer_contains_query():
    query = "我不是问哪个菜卖得好，我想看库存"
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=None),
    ) as t3:
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="QHJ01",
        )

    assert spec is not None
    assert spec.intent == ""
    assert spec.planner_authority == "llm_unavailable"
    assert "没有执行任何相邻分析" in spec.clarification_question
    t3.assert_awaited_once()


@pytest.mark.asyncio
async def test_approved_exact_time_and_store_composition_survives_planner_outage():
    t3 = AsyncMock(side_effect=AssertionError(
        "finite exact time/store composition must not call T3"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            "最近7天全部门店哪个菜卖得最好？",
            _restaurant_pool(),
            factory_id="QHJ01",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "promoted_exact"
    assert spec.clarification_needed is False
    assert spec.window_label == "最近7天"
    assert spec.store_scope == "all"
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_explicit_multi_store_dish_ranking_survives_planner_outage():
    query = "最近7天青花椒南方百联店和青花椒徐汇光启城店哪个菜卖得好"
    t3 = AsyncMock(side_effect=AssertionError(
        "complete explicit store ranking must not call T3"
    ))

    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.planner_authority == "explicit_slots"
    assert spec.source_tier == "explicit_slots"
    assert spec.clarification_needed is False
    assert spec.window_label == "最近7天"
    assert spec.store_scope == "multiple"
    assert spec.store_slots == (
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    )
    assert spec.dimensions == ("store", "dish")
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.ranking_direction == "best"
    assert spec.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert spec.compare_stores is True
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_explicit_single_store_dish_ranking_uses_the_same_narrow_guard():
    t3 = AsyncMock(side_effect=AssertionError(
        "complete explicit single-store ranking must not call T3"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            "最近7天青花椒南方百联店哪个菜卖得好",
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.planner_authority == "explicit_slots"
    assert spec.store_scope == "single"
    assert spec.store_slots == ("青花椒南方百联店",)
    assert spec.compare_stores is False
    assert spec.clarification_needed is False
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_explicit_multi_store_ranking_missing_time_asks_for_time_without_t3():
    query = "青花椒南方百联店和青花椒徐汇光启城店哪个菜卖得好"
    t3 = AsyncMock(side_effect=AssertionError(
        "a single missing time slot must be clarified without T3"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.planner_authority == "explicit_slots"
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION
    assert spec.store_scope == "multiple"
    assert spec.store_slots == (
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    )
    t3.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "query,direction",
    [
        ("全部门店销量最高的5道菜", "best"),
        ("全部门店销量最低的5道菜", "worst"),
    ],
)
async def test_explicit_all_store_ranking_missing_time_asks_without_t3(
    query,
    direction,
):
    t3 = AsyncMock(side_effect=AssertionError(
        "an all-store dish ranking may not invent a default time window"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "explicit_slots"
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION
    assert spec.store_scope == "all"
    assert spec.store_slots == ()
    assert spec.dimensions == ("dish",)
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.ranking_direction == direction
    assert spec.ranking_limit == 5
    t3.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "query,direction,exclusions",
    [
        ("销量最低的5道菜有哪些？", "worst", ()),
        (
            "销量最高的5道菜是什么？请排除米饭、餐巾纸、湿纸巾和餐具",
            "best",
            ("米饭", "餐巾纸", "湿纸巾", "餐具"),
        ),
    ],
)
async def test_explicit_ranking_without_store_or_time_asks_time_before_scope(
    query,
    direction,
    exclusions,
):
    t3 = AsyncMock(side_effect=AssertionError(
        "an explicit sales ranking may not ask which metric the user meant"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "explicit_slots"
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION
    assert spec.store_scope is None
    assert spec.store_slots == ()
    assert spec.dimensions == ("dish",)
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.ranking_direction == direction
    assert spec.ranking_limit == 5
    assert spec.excluded_entities == exclusions
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_explicit_ranking_asks_store_after_time_is_supplied():
    pool = _StoreScopePool(["东城店", "西城店", "南城店"])
    spec = _explicit_store_dish_ranking_spec(
        "本月销量最高的5道菜是什么",
        is_continuation=True,
    )

    guarded = await _apply_store_scope_guard(pool, "FACTORY_A", spec)

    assert guarded is not None
    assert guarded.clarification_needed is True
    assert guarded.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert guarded.store_options == ("东城店", "西城店", "南城店")
    assert guarded.requested_metrics == ("sales_volume",)
    assert guarded.ranking_direction == "best"


@pytest.mark.asyncio
async def test_all_store_limited_ranking_time_button_keeps_full_plan():
    t3 = AsyncMock(side_effect=AssertionError(
        "the fixed time answer must complete the explicit ranking plan"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        first = await parse_restaurant_query(
            "全部门店销量最低的5道菜",
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )
    second = _explicit_store_dish_ranking_spec(
        "全部门店销量最低的5道菜 最近7天",
        is_continuation=True,
    )

    assert first.clarification_question == TIME_CLARIFICATION_QUESTION
    assert second is not None
    assert second.clarification_needed is False
    assert second.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert second.window_label == "最近7天"
    assert second.store_scope == "all"
    assert second.ranking_direction == "worst"
    assert second.ranking_limit == 5
    assert second.requested_metrics == ("sales_volume",)
    assert "全部门店销量最低的5道菜" in second.resolver_query_seed
    assert "最近7天" in second.resolver_query_seed
    t3.assert_not_awaited()


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "query",
    [
        # A negated phrase must never become a deterministic execution grant.
        "最近7天青花椒南方百联店和青花椒徐汇光启城店不要看哪个菜卖得好",
        # More than the one supported metric still needs semantic authority.
        "最近7天青花椒南方百联店和青花椒徐汇光启城店哪个菜卖得好并看毛利",
    ],
)
async def test_incomplete_or_ambiguous_multi_store_ranking_still_uses_t3(query):
    t3 = AsyncMock(return_value=None)
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == ""
    assert spec.planner_authority == "llm_unavailable"
    t3.assert_awaited_once()


@pytest.mark.asyncio
async def test_trusted_dish_followup_survives_planner_outage():
    query = "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？"
    t3 = AsyncMock(side_effect=AssertionError(
        "complete trusted dish follow-up must not call T3"
    ))

    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
            trusted_followup_context=True,
        )

    assert spec is not None
    assert spec.clarification_needed is False
    assert spec.planner_authority in {
        "trusted_context",
        "trusted_context_contract_repair",
    }
    assert spec.source_tier == "trusted_context"
    assert spec.window_label == "最近7天"
    assert spec.store_scope == "all"
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.dimensions == ("dish",)
    assert spec.requested_metrics == ("recipe_cost", "gross_margin")
    # The dish-margin resolver returns sales, recipe cost, and gross margin in
    # one answer; a second recipe-cost execution would duplicate the same row.
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    t3.assert_not_awaited()


def test_all_store_scope_is_aggregate_unless_store_breakdown_is_explicit():
    aggregate = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？",
        confidence=1.0,
        tier="trusted_context",
        planner_authority="trusted_context",
        require_explicit_time=True,
    )
    breakdown = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        "最近7天各门店招牌青花椒味(单人份)的毛利分别怎么样？",
        confidence=1.0,
        tier="llm",
        planner_authority="llm",
        require_explicit_time=True,
        llm_dish="招牌青花椒味(单人份)",
    )

    assert aggregate.store_scope == "all"
    assert aggregate.dimensions == ("dish",)
    assert aggregate.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert breakdown.store_scope == "all"
    assert breakdown.dimensions == ("store", "dish")
    assert breakdown.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("scope_text", "expected_scope", "expected_stores"),
    [
        ("青花椒南方百联店", "single", ("青花椒南方百联店",)),
        (
            "青花椒南方百联店和青花椒徐汇光启城店",
            "multiple",
            ("青花椒南方百联店", "青花椒徐汇光启城店"),
        ),
    ],
)
async def test_trusted_named_store_dish_followup_survives_planner_outage(
    scope_text,
    expected_scope,
    expected_stores,
):
    query = f"最近7天{scope_text}招牌青花椒味(单人份)的成本如何"
    t3 = AsyncMock(side_effect=AssertionError(
        "complete trusted named-store follow-up must not call T3"
    ))

    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
            trusted_followup_context=True,
        )

    assert spec is not None
    assert spec.clarification_needed is False
    assert spec.planner_authority in {
        "trusted_context",
        "trusted_context_contract_repair",
    }
    assert spec.store_scope == expected_scope
    assert spec.store_slots == expected_stores
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert set(spec.dimensions) == {"store", "dish"}
    assert spec.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_fully_slotted_named_dish_executes_without_t3():
    query = "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？"
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=None),
    ) as t3:
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "explicit_named_dish_slots"
    assert spec.requested_metrics == ("recipe_cost", "gross_margin")
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_named_dish_inline_time_diagnosis_executes_without_t3():
    query = "全部门店卤炸牛肉串本月销量为什么低"
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(side_effect=AssertionError(
            "fully slotted named-dish diagnosis must not call T3"
        )),
    ) as t3:
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "explicit_named_dish_slots"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.analysis_action == "diagnose"
    assert spec.store_scope == "all"
    assert spec.dish_slot == "卤炸牛肉串"
    assert spec.clarification_needed is False
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_named_dish_without_time_asks_for_time_without_t3():
    query = "全部门店招牌青花椒味(单人份)的成本和毛利呢？"
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(return_value=None),
    ) as t3:
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
            trusted_followup_context=True,
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planner_authority == "explicit_named_dish_slots"
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.requested_metrics == ("recipe_cost", "gross_margin")
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_trusted_named_dish_optimization_executes_without_t3():
    query = "最近7天全部门店招牌青花椒味(单人份)的成本怎么优化？"
    with patch(
        "smartbi.gold.restaurant_intent._t3_llm_parse",
        new=AsyncMock(side_effect=AssertionError("typed optimization must not call T3")),
    ) as t3:
        spec = await parse_restaurant_query(
            query,
            _restaurant_pool(),
            factory_id="DEMO_REST",
            trusted_followup_context=True,
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.analysis_action == "optimize"
    assert spec.planner_authority in {
        "trusted_context",
        "trusted_context_contract_repair",
    }
    t3.assert_not_awaited()


@pytest.mark.asyncio
async def test_clarification_path_low_confidence():
    query = "情况怎么样"
    assert match_restaurant_ops(query) is None

    pool = _restaurant_pool()
    llm_json = json.dumps({
        "intent": None,
        "time_range": None,
        "confidence": 0.2,
        "clarification_needed": True,
        "clarification_question": "您想了解营收、毛利、损耗还是库存盘点的情况？",
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}

    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is not None
    assert spec.clarification_needed is True
    assert spec.clarification_question
    assert spec.intent == ""  # no code forced -- caller must not query data


@pytest.mark.asyncio
async def test_low_confidence_without_explicit_clarification_question_gets_default():
    query = "随便说点啥"
    pool = _restaurant_pool()
    llm_json = json.dumps({"intent": None, "confidence": 0.1, "clarification_needed": False})
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")
    assert spec is not None
    assert spec.clarification_needed is True
    assert spec.clarification_question  # default filled in, never empty


@pytest.mark.asyncio
async def test_generic_followup_does_not_get_hijacked_into_an_ops_code():
    """Regression guard: a generic conversational follow-up like "那明天先
    做什么" must not be force-routed into one of the 8 RESTAURANT_OPS_* codes
    by T2/T3. It must come back as None or an explicit clarification -- never
    a confident (code, tier) pair -- so chat.py's existing fallback chain
    (which may hand it to the separate owner-action registry / synthesis
    engine) is left completely untouched, exactly as it behaves today."""
    query = "那明天先做什么"
    assert match_restaurant_ops(query) is None

    pool = _restaurant_pool()
    llm_json = json.dumps({"intent": None, "confidence": 0.15, "clarification_needed": True,
                           "clarification_question": "能说说具体想看哪方面的经营数据吗？"})
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk", new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)):
        spec = await parse_restaurant_query(query, pool, factory_id="QHJ01")

    assert spec is None or spec.intent == "" or spec.clarification_needed is True


# ─── 3. Business-type gate (bidirectional isolation) ──────────────────────

@pytest.mark.asyncio
async def test_non_restaurant_tenant_skips_t2_and_t3():
    query = "生意最近怎么样啊能不能多赚点"
    assert match_restaurant_ops(query) is None

    pool = _non_restaurant_pool()
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(side_effect=AssertionError("T2 must not run for a non-restaurant tenant")),
    ):
        spec = await parse_restaurant_query(query, pool, factory_id="F001")

    assert spec is None


@pytest.mark.asyncio
async def test_factory_side_hybrid_match_excludes_restaurant_namespace():
    """Mirror check: template_rag.hybrid_match (the FACTORY-side vector path)
    must exclude RESTAURANT_OPS_* so a factory query can never vector-match a
    restaurant-only template."""
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk", new=AsyncMock(return_value=[]),
    ) as mock_topk:
        await hybrid_match(pool, "随便一个工厂查询", keyword_code=None)

    _, kwargs = mock_topk.call_args
    assert kwargs.get("exclude_prefix") == "RESTAURANT_OPS_"
    assert "code_prefix" not in kwargs or kwargs.get("code_prefix") is None


@pytest.mark.asyncio
async def test_tenant_gate_result_is_cached_per_factory():
    query = "生意最近怎么样啊能不能多赚点"
    pool = _restaurant_pool()
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[("RESTAURANT_OPS_SALES_SUMMARY", 0.9, "x")]),
    ):
        await parse_restaurant_query(query, pool, factory_id="QHJ01")
        clear_route_cache()  # keep the routing cache out of this assertion
        await parse_restaurant_query(query, pool, factory_id="QHJ01")

    # acquire() is called once for the tenant-gate lookup, and NOT called
    # again on the second parse -- proves the gate result was cached.
    assert pool.acquire_calls == 1


# ─── 4. build_resolver_query ────────────────────────────────────────────

def test_build_resolver_query_appends_window_label_when_missing():
    spec = RestaurantQuerySpec(
        intent="RESTAURANT_OPS_SALES_SUMMARY", domain="restaurant",
        date_range=(date(2026, 5, 1), date(2026, 6, 30)), window_label="最近2个月",
        relative_window=True, metrics=("revenue",), wants_margin=True,
        asks_profitability=True, dimensions=(), comparison=None,
        confidence=0.9, source_tier="llm",
    )
    resolved = build_resolver_query("这两个月生意咋样，挣着钱没", spec)
    assert "最近2个月" in resolved
    assert "这两个月生意咋样，挣着钱没" in resolved


def test_build_resolver_query_noop_when_all_history():
    spec = RestaurantQuerySpec(
        intent="RESTAURANT_OPS_SALES_SUMMARY", domain="restaurant",
        date_range=(None, None), window_label="全部历史",
        relative_window=False, metrics=("revenue",), wants_margin=False,
        asks_profitability=False, dimensions=(), comparison=None,
        confidence=0.95, source_tier="keyword",
    )
    query = "整体销售情况怎么样"
    assert build_resolver_query(query, spec) == query


def test_build_resolver_query_idempotent_when_label_already_present():
    spec = RestaurantQuerySpec(
        intent="RESTAURANT_OPS_SALES_SUMMARY", domain="restaurant",
        date_range=(date(2026, 6, 1), date(2026, 6, 30)), window_label="本月",
        relative_window=True, metrics=("revenue",), wants_margin=False,
        asks_profitability=False, dimensions=(), comparison=None,
        confidence=0.95, source_tier="keyword",
    )
    query = "本月营业额"
    assert build_resolver_query(query, spec) == query


def test_explicit_sales_metric_rejects_conflicting_llm_margin_supplement():
    query = "本月招牌藤椒味(单人份)的销量怎么优化"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=0.95,
        tier="llm",
        llm_wants_margin=True,
        llm_asks_profitability=True,
        planner_authority="llm",
    )

    assert spec.requested_metrics == ("sales_volume",)
    assert spec.wants_margin is False
    assert spec.asks_profitability is False
    assert build_resolver_query(query, spec) == query


def test_resolver_query_never_injects_margin_against_explicit_sales_contract():
    query = "本月米饭的销量怎么优化"
    malformed_spec = RestaurantQuerySpec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        domain="restaurant",
        date_range=(date(2026, 7, 1), date(2026, 7, 24)),
        window_label="本月",
        relative_window=True,
        metrics=("sales_volume", "gross_margin"),
        wants_margin=True,
        asks_profitability=True,
        dimensions=("dish",),
        comparison=None,
        confidence=0.95,
        source_tier="llm",
        requested_metrics=("sales_volume",),
        planned_intents=("RESTAURANT_OPS_GROSS_MARGIN",),
    )

    assert build_resolver_query(query, malformed_spec) == query


def test_continuation_resolver_query_uses_sealed_entity_metric_action_seed():
    combined_query = "本月招牌藤椒味(单人份)的销量怎么优化"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        combined_query,
        confidence=0.95,
        tier="llm",
        is_continuation=True,
        planner_authority="llm",
    )

    assert spec.resolver_query_seed == combined_query
    assert spec.dish_slot == "招牌藤椒味(单人份)"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.analysis_action == "optimize"
    assert build_resolver_query("本月", spec) == combined_query


def test_query_plan_hash_seals_ranking_direction_wording():
    best = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月哪个菜卖得最好",
        confidence=0.95,
        tier="llm",
    )
    worst = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月哪个菜卖得最差",
        confidence=0.95,
        tier="llm",
    )

    assert best.resolver_query_seed != worst.resolver_query_seed
    assert best.plan_hash != worst.plan_hash


# ─── 5. Comparison / dimension detectors ──────────────────────────────────

@pytest.mark.parametrize("query,expected", [
    ("同比和环比分析", "yoy"),  # 同比 checked first -- both present, yoy wins
    ("环比增长多少", "mom"),
    ("跟上周比怎么样", "wow"),
    ("整体销售情况怎么样", None),
])
def test_detect_comparison(query, expected):
    assert _detect_comparison(query) == expected


@pytest.mark.parametrize("query,expected_dim", [
    ("哪家门店毛利率最高", "store"),
    ("菜品毛利率排行", "dish"),
    ("食材领用最多的是哪些", "ingredient"),
])
def test_detect_dimensions(query, expected_dim):
    assert expected_dim in _detect_dimensions(query)


def test_ingredient_cost_metric_is_not_an_ingredient_breakdown_dimension():
    assert _detect_dimensions("这道菜的食材成本是多少") == ("dish",)
    assert "ingredient" in _detect_dimensions("按食材拆分这道菜的成本")


# ─── 6. Answer Contract ────────────────────────────────────────────────────

def _spec(**overrides) -> RestaurantQuerySpec:
    defaults = dict(
        intent="RESTAURANT_OPS_SALES_SUMMARY", domain="restaurant",
        date_range=(None, None), window_label="全部历史", relative_window=False,
        metrics=("revenue",), wants_margin=False, asks_profitability=False,
        dimensions=(), comparison=None, confidence=0.95, source_tier="keyword",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


def test_contract_missing_time_echo():
    spec = _spec(window_label="最近2个月", relative_window=True)
    result = contract.validate(spec, "总营收¥100万，平均每单¥50", kpis=[], meta={})
    assert "window_label" in result.missing
    assert not result.passed


def test_contract_time_echo_satisfied_by_date_range():
    spec = _spec(
        window_label="最近2个月", relative_window=True,
        date_range=(date(2026, 5, 1), date(2026, 6, 30)),
    )
    answer = "2026-05-01 至 2026-06-30 期间总营收¥100万"
    result = contract.validate(spec, answer, kpis=[], meta={})
    assert "window_label" not in result.missing


def test_contract_missing_profitability_verdict():
    spec = _spec(asks_profitability=True, wants_margin=True)
    answer = "总营收¥100万，毛利¥30万，毛利率30%"  # no explicit 赚/亏 verdict word
    result = contract.validate(spec, answer, kpis=[], meta={})
    assert "profitability_verdict" in result.missing


def test_contract_profitability_verdict_present_passes():
    spec = _spec(asks_profitability=True, wants_margin=True)
    answer = "这段时间是赚钱的，毛利¥30万，毛利率30%"
    result = contract.validate(
        spec, answer, kpis=[], meta={"marginInvariantPass": True},
    )
    assert result.passed


def test_contract_margin_missing_when_no_number_or_disclosure():
    spec = _spec(wants_margin=True)
    answer = "整体经营情况良好"  # doesn't even mention 毛利
    result = contract.validate(spec, answer, kpis=[], meta={})
    assert "margin_value" in result.missing


def test_contract_margin_explicit_gap_disclosure_passes():
    """Per spec section 4: an honest 'we could not compute this and why' is
    itself compliant -- not a contract failure."""
    spec = _spec(wants_margin=True)
    answer = "毛利属于成本/价格权限，当前角色不能查看金额；可以先看订单、客单价和门店差异。"
    result = contract.validate(spec, answer, kpis=[], meta={"rbac_masked": True})
    assert "margin_value" not in result.missing


def test_contract_store_dimension_satisfied_by_meta_names():
    spec = _spec(dimensions=("store",))
    answer = "表现最强的是望京店，营收¥50万"
    meta = {"weak_stores": ["国贸店"]}
    result = contract.validate(spec, answer, kpis=[{"title": "最赚门店", "value": "望京店"}], meta=meta)
    assert "store_name" not in result.missing


def test_contract_store_dimension_missing_when_no_name_mentioned():
    spec = _spec(dimensions=("store",))
    answer = "营收表现不错"
    meta = {"stores": [{"name": "望京店"}, {"name": "国贸店"}]}
    result = contract.validate(spec, answer, kpis=[], meta=meta)
    assert "store_name" in result.missing


def test_contract_all_satisfied_passes():
    spec = _spec(
        window_label="今天", relative_window=True,
        asks_profitability=True, wants_margin=True,
    )
    answer = "今天总营收¥5万，是赚钱的，毛利¥1.5万，毛利率30%"
    result = contract.validate(
        spec,
        answer,
        kpis=[],
        meta={"marginInvariantPass": True, "scope_matches_request": True},
    )
    assert result.passed
    assert result.missing == []


def test_contract_requires_explicit_margin_self_check():
    spec = _spec(wants_margin=True)
    result = contract.validate(
        spec,
        "毛利为¥30万，毛利率30%",
        kpis=[],
        meta={},
    )

    assert "margin_integrity" in result.missing


def test_contract_recomputes_margin_arithmetic_instead_of_trusting_flags():
    spec = _spec(wants_margin=True)
    result = contract.validate(
        spec,
        "已覆盖毛利为¥120，毛利率120%",
        kpis=[],
        meta={
            "marginInvariantPass": True,
            "scope_matches_request": True,
            "totalProfit": 120.0,
            "totalRevenueWithCost": 100.0,
            "totalRevenue": 1000.0,
            "avgRate": 1.2,
        },
    )

    assert "margin_integrity" in result.missing


def test_contract_rejects_outer_and_margin_window_mismatch():
    spec = _spec(wants_margin=True)
    result = contract.validate(
        spec,
        "已覆盖毛利为¥20，毛利率20%",
        kpis=[],
        meta={
            "marginInvariantPass": True,
            "scope_matches_request": True,
            "outer_window_start": "2026-01-01",
            "outer_window_end": "2026-03-01",
            "requested_window_start": "2026-02-01",
            "requested_window_end": "2026-03-01",
            "totalProfit": 20.0,
            "totalRevenueWithCost": 100.0,
            "totalRevenue": 1000.0,
            "avgRate": 0.2,
        },
    )

    assert "margin_integrity" in result.missing


def test_required_elements_empty_for_bare_query():
    spec = _spec()  # no time, no margin, no profitability, no dimensions
    assert contract.required_elements(spec) == []


def test_contract_rejects_empty_answer_even_for_bare_query():
    result = contract.validate(_spec(), "", kpis=[], meta={})
    assert result.passed is False
    assert result.missing == ["non_empty_answer"]


@pytest.mark.parametrize(
    "action,plain_answer,covered_answer",
    [
        (
            "diagnose",
            "米饭毛利率为 80%。",
            "原因拆解：当前只能解释计算构成，不能证明因果。",
        ),
        (
            "optimize",
            "米饭毛利率为 80%。",
            "优化目标：提高毛利率。优化动作：先核对成本。验证指标：毛利率。",
        ),
    ],
)
def test_contract_requires_current_turn_analysis_action(
    action,
    plain_answer,
    covered_answer,
):
    spec = _spec(analysis_action=action)

    rejected = contract.validate(spec, plain_answer, kpis=[], meta={})
    accepted = contract.validate(spec, covered_answer, kpis=[], meta={})

    assert "analysis_action" in rejected.missing
    assert accepted.passed


@pytest.mark.parametrize(
    "covered_answer",
    [
        (
            "判断：卤炸牛肉串低于中位数，“销量低”的前提成立。"
            "现有汇总数据还不能证明为什么低。"
        ),
        (
            "判断：卤炸牛肉串不低于中位数，“销量低”的前提不成立。"
            "因此不能按低销量问题直接制定动作。"
        ),
        (
            "判断：可比主菜不足，当前不能判断“销量低”的前提是否成立。"
        ),
    ],
)
def test_contract_accepts_safe_low_sales_premise_diagnosis(covered_answer):
    spec = _spec(analysis_action="diagnose")

    result = contract.validate(spec, covered_answer, kpis=[], meta={})

    assert result.passed


def test_contextualize_only_dependent_restaurant_followups():
    parent = {
        "parent_query": "本月营收趋势怎么样",
        "parent_template_code": "RESTAURANT_OPS_SALES_SUMMARY",
        "structured_context": {
            "window_label": "本月",
            "requested_metrics": ["revenue"],
            "analysis_action": "lookup",
        },
    }
    effective, inherited = contextualize_restaurant_followup("那和上个月比呢", parent)
    assert inherited is True
    assert effective == "本月营收和上个月比呢"
    assert "本月营收趋势怎么样" not in effective

    standalone, inherited = contextualize_restaurant_followup("昨天营业额是多少", parent)
    assert inherited is False
    assert standalone == "昨天营业额是多少"

    switched, inherited = contextualize_restaurant_followup("换个话题，看看库存预警", parent)
    assert inherited is False
    assert switched.startswith("换个话题")


def test_generic_optimization_requires_business_objective():
    question = optimization_clarification_question("帮我优化一下餐厅经营")
    assert question is not None
    assert "营收" in question and "毛利率" in question and "损耗" in question
    assert optimization_clarification_question("优化慢销菜品") is None


def test_followup_replans_metric_and_action_without_parent_query_injection():
    parent = {
        "parent_query": "米饭的毛利率如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "turns_history": [{
            "q": "米饭的毛利率如何",
            "a_summary": "米饭毛利率 87.2%",
            "context": {
                "focus_entity": {"type": "dish", "name": "米饭"},
                "window_label": "本月",
                "requested_metrics": ["gross_margin"],
                "analysis_action": "lookup",
            },
        }],
    }

    cases = {
        "销量呢": "本月米饭的销量呢",
        "为什么": "本月米饭的毛利率为什么是这样",
        "怎么优化": "本月米饭的毛利率怎么优化",
        "这个菜卖了多少": "本月米饭卖了多少",
    }
    for query, expected in cases.items():
        effective, inherited = contextualize_restaurant_followup(query, parent)
        assert inherited is True
        assert effective == expected
        assert "米饭的毛利率如何；继续追问" not in effective


def test_four_turn_chain_updates_metric_before_diagnosis_and_optimization():
    def parent(query, metric, action="lookup"):
        return {
            "parent_query": query,
            "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
            "turns_history": [{
                "context": {
                    "focus_entity": {"type": "dish", "name": "米饭"},
                    "requested_metrics": [metric],
                    "window_label": "本月",
                    "analysis_action": action,
                },
            }],
        }

    sales_query, inherited = contextualize_restaurant_followup(
        "销量呢",
        parent("本月米饭的毛利率如何", "gross_margin"),
    )
    assert inherited is True
    assert sales_query == "本月米饭的销量呢"

    diagnosis_query, inherited = contextualize_restaurant_followup(
        "为什么",
        parent(sales_query, "sales_volume"),
    )
    assert inherited is True
    assert diagnosis_query == "本月米饭的销量为什么是这样"

    optimization_query, inherited = contextualize_restaurant_followup(
        "怎么优化",
        parent(diagnosis_query, "sales_volume", "diagnose"),
    )
    assert inherited is True
    assert optimization_query == "本月米饭的销量怎么优化"


@pytest.mark.parametrize(
    "dish_name",
    ["米饭", "娃娃菜", "招牌青花椒味(单人份)"],
)
@pytest.mark.parametrize(
    "time_followup",
    ["改看本月", "换看本月", "只看本月", "改查本月"],
)
def test_named_dish_followup_can_replace_time_then_store_slots(
    dish_name,
    time_followup,
):
    first_parent = {
        "parent_query": f"最近30天全部门店{dish_name}的销量怎么优化",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": dish_name},
            "window_label": "最近30天",
            "requested_metrics": ["sales_volume"],
            "analysis_action": "optimize",
            "store_scope": "all",
            "store_names": [],
        },
    }

    time_query, inherited = contextualize_restaurant_followup(
        time_followup,
        first_parent,
    )
    assert inherited is True
    assert time_query == f"全部门店本月{dish_name}的销量如何"

    second_parent = {
        "parent_query": time_query,
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": dish_name},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "analysis_action": "lookup",
            "store_scope": "all",
            "store_names": [],
        },
    }
    store_query, inherited = contextualize_restaurant_followup(
        "只看青花椒南方百联店",
        second_parent,
    )
    assert inherited is True
    assert store_query == f"本月青花椒南方百联店{dish_name}的销量如何"


@pytest.mark.parametrize(
    ("scope_followup", "expected_scope", "expected_scope_kind", "expected_stores"),
    [
        ("全部门店", "全部门店", "all", ()),
        ("所有门店", "全部门店", "all", ()),
        (
            "只看青花椒南方百联店",
            "青花椒南方百联店",
            "single",
            ("青花椒南方百联店",),
        ),
        (
            "只看青花椒南方百联店和青花椒徐汇光启城店",
            "青花椒南方百联店和青花椒徐汇光启城店",
            "multiple",
            ("青花椒南方百联店", "青花椒徐汇光启城店"),
        ),
    ],
)
def test_named_dish_followup_can_replace_store_scope_without_polluting_dish(
    scope_followup,
    expected_scope,
    expected_scope_kind,
    expected_stores,
):
    parent = {
        "parent_query": "本月青花椒南方百联店娃娃菜的销量为什么是这样",
        "parent_template_code": "RESTAURANT_OPS_STORE_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": "娃娃菜"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "analysis_action": "diagnose",
            "store_scope": "single",
            "store_names": ["青花椒南方百联店"],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        scope_followup,
        parent,
    )

    assert inherited is True
    assert effective == f"本月{expected_scope}娃娃菜的销量如何"
    assert "只看和" not in effective
    spec = _trusted_context_dish_followup_spec(effective)
    assert spec is not None
    assert spec.dish_slot == "娃娃菜"
    assert spec.store_scope == expected_scope_kind
    assert spec.store_slots == expected_stores


def test_followup_with_explicit_new_entity_replaces_dish_but_inherits_time():
    parent = {
        "parent_query": "米饭的毛利率如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": "米饭"},
            "window_label": "最近30天",
            "requested_metrics": ["gross_margin"],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "招牌藤椒味的成本如何",
        parent,
    )

    assert inherited is True
    assert effective == "最近30天招牌藤椒味的成本如何"


@pytest.mark.parametrize(
    ("query", "expected_dish"),
    [
        ("本月全部门店米饭销量是多少，表现怎么样", "米饭"),
        ("本月全部门店娃娃菜销量是多少，表现怎么样", "娃娃菜"),
        (
            "最近30天全部门店享库1.8斤波龙套餐399销量是多少，表现如何",
            "享库1.8斤波龙套餐399",
        ),
    ],
)
def test_fully_scoped_named_dish_overrides_ranked_dish_context(
    query,
    expected_dish,
):
    parent = {
        "parent_query": "本月全部门店销量最高的10个菜是什么",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
            "topic_kind": "dish_ranking",
            "store_scope": "all",
        },
    }

    effective, inherited = contextualize_restaurant_followup(query, parent)
    spec = _explicit_named_dish_metric_spec(effective)

    assert inherited is False
    assert effective == query
    assert spec is not None
    assert spec.dish_slot == expected_dish
    assert spec.store_scope == "all"
    assert spec.requested_metrics == ("sales_volume",)


def test_followup_switch_entity_inherits_parent_metric_and_window():
    parent = {
        "parent_query": "本月招牌藤椒味(单人份)销量如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": "招牌藤椒味(单人份)"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "换成招牌藤椒鱼可乐单人套餐呢",
        parent,
    )

    assert inherited is True
    assert effective == "本月招牌藤椒鱼可乐单人套餐的销量如何"


def test_sales_improvement_optimization_and_next_step_are_followups():
    parent = {
        "parent_query": "本月招牌藤椒味(单人份)销量如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {"type": "dish", "name": "招牌藤椒味(单人份)"},
            "window_label": "本月",
            "requested_metrics": ["sales_volume"],
        },
    }

    improved, inherited = contextualize_restaurant_followup("销量怎么提升", parent)
    assert inherited is True
    assert improved == "本月招牌藤椒味(单人份)的销量怎么优化"

    optimized, inherited = contextualize_restaurant_followup("销量怎么优化", parent)
    assert inherited is True
    assert optimized == "本月招牌藤椒味(单人份)的销量怎么优化"

    next_step, inherited = contextualize_restaurant_followup("下一步先做什么", parent)
    assert inherited is True
    assert next_step == "本月招牌藤椒味(单人份)的销量怎么优化"


def test_whole_store_sales_optimization_does_not_inherit_ranked_dish():
    parent = {
        "parent_query": "最近7天全部门店哪个菜卖得好",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "最近7天",
            "requested_metrics": ["sales_volume"],
            "topic_kind": "dish_ranking",
            "store_scope": "all",
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "全店销量怎么优化",
        parent,
    )

    assert inherited is False
    assert effective == "全店销量怎么优化"


def test_named_dish_cost_uses_scoped_unit_economics_resolver():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "米饭的成本如何",
        confidence=1.0,
        tier="test",
    )

    assert spec.requested_metrics == ("recipe_cost",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.parametrize(
    "query,expected",
    [
        ("米饭销量是多少", "lookup"),
        ("米饭毛利率为什么是这样", "diagnose"),
        ("米饭毛利率怎么优化", "optimize"),
        ("本月米饭销量和上月比", "compare"),
    ],
)
def test_query_plan_seals_current_turn_analysis_action(query, expected):
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=1.0,
        tier="test",
    )
    assert spec.analysis_action == expected


def test_ambiguous_cost_margin_priority_requires_scope_choice():
    question = optimization_clarification_question(
        "为了降低成本、提高毛利，成本毛利先查哪几项？"
    )
    assert question is not None
    assert "菜品成本" in question
    assert "食材损耗" in question
    assert "门店毛利" in question


def test_compound_cost_margin_query_builds_a_multi_resolver_plan():
    spec = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        "为了降低成本提高毛利，最近30天把菜品成本、食材损耗和门店毛利都查一下，告诉我先查哪项",
        confidence=1.0,
        tier="test",
    )

    assert spec.requested_metrics == ("recipe_cost", "wastage", "gross_margin")
    assert spec.planned_intents == (
        "RESTAURANT_OPS_RECIPE_COST",
        "RESTAURANT_OPS_WASTAGE_TOP",
        "RESTAURANT_OPS_STORE_MARGIN",
    )
    assert spec.asks_priority is True
    assert spec.unsupported_requirements == ()
    assert spec.clarification_needed is False


def test_service_speed_root_cause_query_keeps_supported_dimensions_executable():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "最近7天晚市出餐慢，是订单集中、人员不足还是工序瓶颈？请分别用数据判断",
        confidence=1.0,
        tier="test",
    )

    assert spec.clarification_needed is False
    assert spec.planned_intents == (
        "RESTAURANT_OPS_SALES_SUMMARY",
        "RESTAURANT_OPS_STAFFING_ADVICE",
    )
    assert spec.unsupported_requirements == ("service_speed", "process_bottleneck")
    assert spec.clarification_question is None


@pytest.mark.asyncio
async def test_parse_catches_unsupported_only_capability_gap_before_tenant_gate():
    spec = await parse_restaurant_query(
        "最近7天晚市逐单出餐时长和工序瓶颈分别是什么？",
        object(),
        factory_id="DEMO_REST",
    )

    assert spec is not None
    assert spec.intent == ""
    assert spec.clarification_needed is True
    assert spec.unsupported_requirements == ("service_speed", "process_bottleneck")
    assert "不会用营业额" in (spec.clarification_question or "")


def test_margin_and_return_rate_query_runs_margin_and_leaves_return_rate_blank():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "优化菜品结构，以提高毛利率并降低退菜率为目标",
        confidence=1.0,
        tier="test",
    )

    assert spec.clarification_needed is False
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.unsupported_requirements == ("return_rate",)
    assert spec.clarification_question is None


def test_net_profit_is_not_silently_reduced_to_revenue_or_gross_margin():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "请给出昨天各门店净利润，不要用营业额或毛利替代",
        confidence=1.0,
        tier="test",
    )

    assert "net_profit" in spec.requested_metrics
    assert "gross_margin" not in spec.requested_metrics
    assert spec.unsupported_requirements == ("net_profit",)
    assert spec.clarification_needed is True
    assert "净利润" in (spec.clarification_question or "")
    assert "费用、税费及其他收支" in (spec.clarification_question or "")
    assert "不会用营业额" in (spec.clarification_question or "")


def test_dish_optimization_reports_every_missing_dimension_together():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "请按菜品销量、销售额、毛利率、退菜率、顾客评价、制作时长和食材损耗优化菜品结构",
        confidence=1.0,
        tier="test",
    )

    assert set(spec.unsupported_requirements) == {
        "return_rate", "customer_review", "production_time",
    }
    assert spec.clarification_needed is False
    assert spec.clarification_question is None
    assert spec.planned_intents == (
        "RESTAURANT_OPS_WASTAGE_TOP",
        "RESTAURANT_OPS_GROSS_MARGIN",
    )


def test_price_elasticity_discloses_required_controls_and_does_not_claim_causality():
    question = capability_clarification_question("分析提价影响和价格弹性") or ""

    assert "价格变动" in question
    assert "促销" in question
    assert "缺货" in question
    assert "对照门店或对照时段" in question
    assert "相关性还不能证明因果" in question


def test_dish_sales_and_margin_share_one_deterministic_resolver():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "请同时优化菜品销量和毛利率，给出优先级和依据",
        confidence=1.0,
        tier="test",
    )

    assert spec.requested_metrics == ("sales_volume", "gross_margin")
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.asks_priority is True
    assert spec.clarification_needed is False


def test_sheet_dish_sales_question_never_plans_store_sales_summary():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "哪个菜卖得好",
        confidence=1.0,
        tier="test",
    )

    assert spec.dimensions == ("dish",)
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.parametrize("query", [
    "本月哪个菜卖得最好？",
    "本月哪个菜最好卖？",
    "本月销量最高的菜是什么？",
    "本月最受欢迎的菜是什么？",
])
def test_dish_ranking_synonyms_compile_to_sales_volume_plan(query):
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        confidence=0.95,
        tier="llm",
    )

    assert spec.requested_metrics == ("sales_volume",)
    assert spec.dimensions == ("dish",)
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.clarification_needed is False


def test_sheet_dish_question_repairs_wrong_llm_summary_intent_before_execution():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "哪个菜卖得好",
        confidence=0.95,
        tier="llm",
        clarification_needed=True,
        clarification_question="您是指销量最高、营收最高，还是毛利最高的菜品？",
    )

    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.clarification_needed is False
    assert spec.planner_authority == "llm_contract_repair"


def test_generic_dish_sales_ranking_defaults_to_descending_without_becoming_margin():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "上个月全部门店菜品销量排名",
        confidence=0.97,
        tier="llm",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("dish",),
        llm_analysis_action="lookup",
        llm_store_scope="all",
        planner_authority="llm",
        llm_semantics_authoritative=True,
    )

    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.ranking_direction == "best"
    assert spec.window_label == "上个月"
    assert spec.store_scope == "all"


def test_explicit_revenue_optimization_repairs_adjacent_llm_report_choice():
    spec = _semantic_spec_from_t3(
        {
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
            "store_scope": "all",
            "confidence": 0.96,
            "clarification_needed": False,
            "missing_fields": [],
            "clarification_question": None,
            "clarification_options": [],
        },
        "这周全部门店营收怎么提高",
    )

    assert spec.intent == "RESTAURANT_OPS_BUSINESS_OPTIMIZATION"
    assert spec.planned_intents == ("RESTAURANT_OPS_BUSINESS_OPTIMIZATION",)
    assert spec.analysis_action == "optimize"
    assert spec.requested_metrics == ("revenue",)


def test_named_dish_low_sales_diagnosis_repairs_broad_optimization_to_dish_resolver():
    spec = _semantic_spec_from_t3(
        {
            "intent": "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
            "time_range": {"type": "named", "value": "this_month"},
            "wants_margin": False,
            "asks_profitability": False,
            "requested_metrics": ["sales_volume"],
            "analysis_action": "diagnose",
            "dimensions": ["dish", "store"],
            "dish": "卤炸牛肉串",
            "store": None,
            "stores": [],
            "store_scope": "all",
            "confidence": 0.95,
            "clarification_needed": False,
            "missing_fields": [],
            "clarification_question": None,
            "clarification_options": [],
        },
        "全部门店卤炸牛肉串本月销量为什么低",
    )

    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.analysis_action == "diagnose"
    assert spec.dish_slot == "卤炸牛肉串"


def test_explicit_daily_revenue_chart_repairs_summary_to_trend_plan():
    spec = _semantic_spec_from_t3(
        {
            "intent": "RESTAURANT_OPS_SALES_SUMMARY",
            "time_range": {"type": "named", "value": "this_month"},
            "wants_margin": False,
            "asks_profitability": False,
            "requested_metrics": ["revenue"],
            "analysis_action": "lookup",
            "dimensions": ["time"],
            "dish": None,
            "store": None,
            "stores": [],
            "store_scope": "all",
            "confidence": 0.97,
            "clarification_needed": False,
            "missing_fields": [],
            "clarification_question": None,
            "clarification_options": [],
        },
        "生成本月全部门店营业额趋势图，按天，画二次拟合和计划线",
    )

    assert spec.intent == "RESTAURANT_OPS_TREND_ANALYSIS"
    assert spec.planned_intents == ("RESTAURANT_OPS_TREND_ANALYSIS",)
    assert spec.dimensions == ("time",)
    assert spec.requested_metrics == ("revenue",)


def test_explicit_two_period_comparison_keeps_both_windows_even_if_llm_selects_trend():
    spec = _semantic_spec_from_t3(
        {
            "intent": "RESTAURANT_OPS_TREND_ANALYSIS",
            "time_range": {"type": "named", "value": "today"},
            "wants_margin": False,
            "asks_profitability": False,
            "requested_metrics": ["revenue"],
            "analysis_action": "lookup",
            "dimensions": ["time"],
            "dish": None,
            "store": None,
            "stores": [],
            "store_scope": "all",
            "confidence": 0.97,
            "clarification_needed": False,
            "missing_fields": [],
            "clarification_question": None,
            "clarification_options": [],
        },
        "昨天全部门店营业额比前天高还是低",
    )

    expected = _resolve_sales_query_spec("昨天全部门店营业额比前天高还是低")
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.planned_intents == ("RESTAURANT_OPS_SALES_SUMMARY",)
    assert spec.analysis_action == "compare"
    assert spec.date_range == expected.date_range
    assert spec.comparison_range == expected.comparison_range
    assert all(spec.comparison_range)


def test_named_dish_sales_repairs_wrong_llm_intent_and_recovers_entity_slot():
    spec = _build_spec(
        "RESTAURANT_OPS_REQUISITION_TREND",
        "本月米饭的销量是多少",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
        clarification_needed=True,
        clarification_question="请明确想查哪类餐饮数据。",
    )

    assert spec.dimensions == ("dish",)
    assert spec.dish_slot == "米饭"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.clarification_needed is False
    assert spec.planner_authority == "llm_contract_repair"


def test_named_dish_revenue_uses_scoped_unit_economics_resolver():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "最近30天招牌藤椒味(单人份)营收是多少",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )

    assert spec.requested_metrics == ("revenue",)
    assert spec.dimensions == ("dish",)
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


def test_all_store_scope_does_not_erase_named_dish_revenue_object():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "本月全部门店招牌青花椒味(单人份)的营业额是多少？",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )

    assert spec.requested_metrics == ("revenue",)
    assert spec.store_scope == "all"
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert "dish" in spec.dimensions
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


def test_conflicting_llm_intent_stays_fail_closed_when_plan_has_multiple_resolvers():
    spec = _build_spec(
        "RESTAURANT_OPS_REQUISITION_TREND",
        "请比较菜品销量和食材损耗",
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )

    assert spec.planned_intents == (
        "RESTAURANT_OPS_WASTAGE_TOP",
        "RESTAURANT_OPS_GROSS_MARGIN",
    )
    assert spec.intent == "RESTAURANT_OPS_REQUISITION_TREND"
    assert spec.clarification_needed is True
    assert "不会用相邻指标替代" in spec.clarification_question


def test_llm_entity_slots_fix_scope_before_execution_plan_is_sealed():
    dish = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "米饭的销量是多少",
        confidence=0.93,
        tier="llm",
        llm_dish="米饭",
    )
    store = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        "鲜行者打浦桥日月光店的营收是多少",
        confidence=0.93,
        tier="llm",
        llm_store="鲜行者打浦桥日月光店",
    )

    assert dish.dimensions == ("dish",)
    assert dish.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert dish.clarification_needed is False
    assert store.dimensions == ("store",)
    assert store.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert store.clarification_needed is False


def test_query_plan_contract_rejects_resolver_substitution():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "哪个菜卖得好",
        confidence=0.94,
        tier="llm",
        planner_authority="llm",
    )
    common_meta = {
        "query_plan_hash": spec.plan_hash,
        "query_plan_version": spec.plan_version,
        "planner_authority": "llm",
        "execution_plan_match": True,
        "scope_matches_request": True,
        "actual_dimensions": ["dish"],
        "low_margin_dishes": [{"name": "招牌菜"}],
    }
    passed = contract.validate(
        spec,
        "招牌菜销量 120 份",
        meta={
            **common_meta,
            "executed_resolvers": ["RESTAURANT_OPS_GROSS_MARGIN"],
        },
    )
    substituted = contract.validate(
        spec,
        "招牌菜销量 120 份",
        meta={
            **common_meta,
            "executed_resolvers": ["RESTAURANT_OPS_SALES_SUMMARY"],
        },
    )

    assert passed.passed
    assert "execution_consistency" in substituted.missing


def test_dish_ranking_followup_uses_structured_entity_not_answer_markdown():
    parent = {
        "parent_query": "哪个菜卖得好",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "turns_history": [{
            "q": "哪个菜卖得好",
            "a_summary": "历史摘要",
            "context": {
                "focus_entity": {
                    "type": "dish",
                    "id": "dish-42",
                    "name": "招牌藤椒味（单人份）",
                    "rank": 1,
                },
                "window_label": "最近30天",
                "requested_metrics": ["sales_volume"],
            },
        }],
        "parent_answer_summary": (
            "**近 30 天菜品销量排行（卖得最好前 5）：**\n\n"
            "1. **错误的旧模板菜名** — 销量 120 份、营收 ¥3,600.00\n"
            "2. 米饭 — 销量 100 份、营收 ¥200.00"
        ),
    }

    effective, inherited = contextualize_restaurant_followup("它的成本如何", parent)

    assert inherited is True
    assert effective == "最近30天招牌藤椒味（单人份）的成本如何"


def test_dish_followup_inherits_all_store_scope_with_entity_and_window():
    parent = {
        "parent_query": "最近7天全部门店哪个菜卖得好",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "turns_history": [{
            "q": "最近7天全部门店哪个菜卖得好",
            "a_summary": "已返回前五名",
            "context": {
                "focus_entity": {
                    "type": "dish",
                    "id": "dish-42",
                    "name": "招牌青花椒味(单人份)",
                    "rank": 1,
                },
                "window_label": "最近7天",
                "requested_metrics": ["sales_volume"],
                "store_scope": "all",
                "store_names": [],
            },
        }],
    }

    effective, inherited = contextualize_restaurant_followup(
        "它的成本和毛利呢？",
        parent,
    )

    assert inherited is True
    assert effective == "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        effective,
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )
    assert spec.store_scope == "all"
    assert spec.store_slots == ()
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.requested_metrics == ("recipe_cost", "gross_margin")


@pytest.mark.parametrize(
    ("query", "expected_body"),
    [
        ("这个菜的单份成本是多少？", "单份成本是多少？"),
        ("这道菜每份成本呢？", "每份成本呢？"),
        ("该菜的单位成本如何", "单位成本如何"),
        ("它的食材成本呢？", "食材成本呢？"),
    ],
)
def test_dish_cost_qualifiers_after_context_reference_do_not_become_dish_names(
    query,
    expected_body,
):
    parent = {
        "parent_query": "最近7天全部门店哪个菜卖得好",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "id": "dish-42",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "最近7天",
            "requested_metrics": ["sales_volume"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(query, parent)

    assert inherited is True
    assert effective == (
        "最近7天全部门店招牌青花椒味(单人份)的"
        f"{expected_body}"
    )
    assert "菜的单份" not in effective
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        effective,
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )
    assert spec.store_scope == "all"
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.dimensions == ("dish",)
    assert spec.requested_metrics == ("recipe_cost",)


def test_time_only_switch_preserves_dish_cost_and_all_store_scope():
    parent = {
        "parent_query": "最近7天全部门店招牌青花椒味(单人份)的食材成本呢？",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
            },
            "window_label": "最近7天",
            "requested_metrics": ["recipe_cost"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "换成上个月呢？",
        parent,
    )

    assert inherited is True
    assert effective == "全部门店上个月招牌青花椒味(单人份)的成本如何"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        effective,
        confidence=0.95,
        tier="trusted_context",
        planner_authority="trusted_context",
    )
    assert spec.window_label == "上个月"
    assert spec.store_scope == "all"
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.requested_metrics == ("recipe_cost",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.parametrize(
    ("query", "expected_window"),
    [
        ("换成最近7天", "最近7天"),
        ("最近30天呢", "最近30天"),
        ("改成2026年6月", "2026年6月"),
    ],
)
def test_time_only_switch_without_question_particle_freezes_other_slots(
    query,
    expected_window,
):
    parent = {
        "parent_query": "上个月全部门店抖音松叶蟹368套餐的营收如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "抖音松叶蟹368套餐",
            },
            "window_label": "上个月",
            "requested_metrics": ["revenue"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(query, parent)

    assert inherited is True
    assert effective == (
        f"全部门店{expected_window}抖音松叶蟹368套餐的营收如何"
    )
    spec = _trusted_context_dish_followup_spec(effective)
    assert spec is not None
    assert spec.window_label == expected_window
    assert spec.store_scope == "all"
    assert spec.dish_slot == "抖音松叶蟹368套餐"
    assert spec.requested_metrics == ("revenue",)


@pytest.mark.parametrize(
    ("parent_scope", "parent_stores", "query", "expected_scope"),
    [
        (
            "all",
            [],
            "改成青花椒南方百联店",
            "青花椒南方百联店",
        ),
        (
            "single",
            ["青花椒南方百联店"],
            "换回全部门店",
            "全部门店",
        ),
    ],
)
def test_store_only_switch_freezes_dish_metric_and_window(
    parent_scope,
    parent_stores,
    query,
    expected_scope,
):
    parent = {
        "parent_query": "上个月抖音松叶蟹368套餐的营收如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "抖音松叶蟹368套餐",
            },
            "window_label": "上个月",
            "requested_metrics": ["revenue"],
            "store_scope": parent_scope,
            "store_names": parent_stores,
        },
    }

    effective, inherited = contextualize_restaurant_followup(query, parent)

    assert inherited is True
    assert effective == (
        f"上个月{expected_scope}抖音松叶蟹368套餐的营收如何"
    )
    spec = _trusted_context_dish_followup_spec(effective)
    assert spec is not None
    assert spec.window_label == "上个月"
    assert spec.dish_slot == "抖音松叶蟹368套餐"
    assert spec.requested_metrics == ("revenue",)
    assert spec.store_scope == ("all" if expected_scope == "全部门店" else "single")


@pytest.mark.parametrize(
    "query",
    [
        "最近7天全部门店库存预警",
        "最近7天全部门店抖音松叶蟹368套餐营收和上个月对比",
        "换成最近7天看天气对销量的影响",
    ],
)
def test_trusted_context_dish_plan_still_rejects_unsafe_shapes(query):
    assert _trusted_context_dish_followup_spec(query) is None


@pytest.mark.parametrize("action", ("销量为什么是这样", "销量怎么优化"))
def test_trusted_context_dish_plan_accepts_typed_diagnosis_and_optimization(action):
    spec = _trusted_context_dish_followup_spec(
        f"最近7天全部门店抖音松叶蟹368套餐的{action}"
    )

    assert spec is not None
    assert spec.dish_slot == "抖音松叶蟹368套餐"
    assert spec.analysis_action in {"diagnose", "optimize"}
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.parametrize(
    "followup",
    (
        "第一名为什么卖得好",
        "第一名的成本和毛利呢",
        "第1名销量怎么优化",
    ),
)
def test_ordinal_followup_restores_ranked_dish_context(followup):
    parent = {
        "parent_query": "最近7天全部门店菜品销量排行",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "最近7天",
            "requested_metrics": ["sales_volume"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(followup, parent)

    assert inherited is True
    assert "招牌青花椒味(单人份)" in effective
    assert "最近7天" in effective
    assert "全部门店" in effective
    assert "第一名" not in effective and "第1名" not in effective
    if "成本和毛利" in followup:
        assert effective.rstrip("？?") == (
            "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢"
        )
        spec = _trusted_context_dish_followup_spec(effective)
        assert spec is not None
        assert spec.requested_metrics == ("recipe_cost", "gross_margin")
        assert spec.ranking_direction is None
        assert spec.ranking_limit == 5


def test_explicit_named_dish_multi_store_metrics_compile_without_llm():
    query = (
        "最近7天青花椒南方百联店和青花椒徐汇光启城店的"
        "招牌青花椒味(单人份)成本和毛利分别是多少"
    )
    spec = _explicit_named_dish_metric_spec(query)

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.store_scope == "multiple"
    assert spec.store_slots == (
        "青花椒南方百联店",
        "青花椒徐汇光启城店",
    )
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.requested_metrics == ("recipe_cost", "gross_margin")
    assert spec.planner_authority == "explicit_named_dish_slots"


def test_single_store_overall_revenue_and_named_dish_sales_compile_without_llm():
    spec = _explicit_named_dish_metric_spec(
        "本月青花椒南方百联店营业额和娃娃菜销量情况"
    )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.planner_authority == "explicit_named_dish_slots"
    assert spec.requested_metrics == ("sales_volume", "revenue")
    assert spec.store_scope == "single"
    assert spec.store_slots == ("青花椒南方百联店",)
    assert spec.dish_slot == "娃娃菜"


def test_explicit_daily_revenue_curve_keeps_chart_and_export_as_one_plan():
    query = (
        "用二次函数拟合最近30天全部门店每日营业额曲线；"
        "如果无法绘图，请提供可导出的日期和营业额字段"
    )
    spec = _explicit_revenue_trend_spec(query)

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_TREND_ANALYSIS"
    assert spec.requested_metrics == ("revenue",)
    assert spec.asks_export is True
    assert spec.planner_authority == "explicit_revenue_trend"


def test_complete_financial_overview_compiles_without_semantic_planner():
    query = "最近30天全部门店毛利和营业额分别是多少，并展示计算口径"
    spec = _explicit_financial_overview_spec(query)

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.requested_metrics == ("gross_margin", "revenue")
    assert spec.planned_intents == ("RESTAURANT_OPS_SALES_SUMMARY",)
    assert spec.store_scope == "all"
    assert spec.planner_authority == "explicit_financial_overview"


@pytest.mark.asyncio
async def test_complete_financial_overview_survives_planner_outage():
    t3 = AsyncMock(side_effect=AssertionError(
        "complete financial overview must not call T3"
    ))
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=t3):
        spec = await parse_restaurant_query(
            "最近30天全部门店毛利和营业额分别是多少，并展示计算口径",
            _restaurant_pool(),
            factory_id="QHJ01",
        )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.clarification_needed is False
    assert spec.planner_authority == "explicit_financial_overview"
    t3.assert_not_awaited()


def test_calendar_chart_word_boundary_does_not_inject_recipe_cost():
    query = "生成本月每日营业额图，并加入计划值10万元和预警值8万元参考线"

    assert _detect_requested_metrics(query) == ("revenue",)
    spec = _explicit_revenue_trend_spec(query)
    assert spec is not None
    assert spec.planned_intents == ("RESTAURANT_OPS_TREND_ANALYSIS",)


def test_difference_and_direction_wording_compiles_period_comparison():
    query = "昨天与前天全部门店营业额分别是多少？请给差额和升降结论"

    spec = _explicit_sales_period_comparison_spec(query)
    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.planned_intents == ("RESTAURANT_OPS_SALES_SUMMARY",)
    assert spec.analysis_action == "compare"
    assert spec.comparison == "previous_day"
    assert spec.comparison_label == "前天"


def test_named_dish_colloquial_diagnosis_without_time_asks_time_first():
    spec = _explicit_named_dish_metric_spec("米饭的销量为什么这样？")

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.dish_slot == "米饭"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.analysis_action == "diagnose"
    assert spec.clarification_needed is True
    assert spec.clarification_question == TIME_CLARIFICATION_QUESTION


def test_explicit_single_store_operations_uses_store_margin_plan():
    spec = _explicit_store_operations_spec(
        "最近30天青花椒南方百联店的经营情况怎么样"
    )

    assert spec is not None
    assert spec.intent == "RESTAURANT_OPS_STORE_MARGIN"
    assert spec.store_scope == "single"
    assert spec.store_slots == ("青花椒南方百联店",)
    assert spec.dimensions == ("store",)
    assert spec.planner_authority == "explicit_store_operations"


@pytest.mark.asyncio
async def test_net_profit_and_table_turnover_disclose_both_missing_dimensions():
    spec = await parse_restaurant_query(
        "最近30天全部门店的净利润和翻台率是多少？缺数据不要猜",
        _restaurant_pool(),
        factory_id="DEMO_REST",
    )

    assert spec is not None
    assert spec.clarification_needed is True
    assert spec.unsupported_requirements == ("net_profit", "table_turnover")
    assert "净利润" in (spec.clarification_question or "")
    assert "翻台率" in (spec.clarification_question or "")


def test_dish_metric_button_followup_restores_entity_window_and_store_scope():
    parent = {
        "parent_query": "上个月全部门店招牌青花椒味(单人份)的成本如何",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
            },
            "window_label": "上个月",
            "requested_metrics": ["recipe_cost"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "这个菜的销量呢？",
        parent,
    )

    assert inherited is True
    assert effective == "上个月全部门店招牌青花椒味(单人份)的销量呢？"
    spec = _trusted_context_dish_followup_spec(effective)
    assert spec is not None
    assert spec.dish_slot == "招牌青花椒味(单人份)"
    assert spec.window_label == "上个月"
    assert spec.store_scope == "all"
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.parametrize(
    ("store_scope", "store_names", "expected_scope_text"),
    [
        ("single", ["青花椒南方百联店"], "青花椒南方百联店"),
        (
            "multiple",
            ["青花椒南方百联店", "青花椒徐汇光启城店"],
            "青花椒南方百联店和青花椒徐汇光启城店",
        ),
    ],
)
def test_dish_followup_inherits_named_store_scope(
    store_scope,
    store_names,
    expected_scope_text,
):
    parent = {
        "parent_query": "最近7天菜品销量排行",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "最近7天",
            "requested_metrics": ["sales_volume"],
            "store_scope": store_scope,
            "store_names": store_names,
        },
    }

    effective, inherited = contextualize_restaurant_followup("它的成本如何", parent)

    assert inherited is True
    assert effective == (
        f"最近7天{expected_scope_text}招牌青花椒味(单人份)的成本如何"
    )
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        effective,
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )
    assert spec.store_scope == store_scope
    assert spec.store_slots == tuple(store_names)
    assert spec.dish_slot == "招牌青花椒味(单人份)"


def test_dish_followup_explicit_store_scope_overrides_parent_scope():
    parent = {
        "parent_query": "最近7天全部门店哪个菜卖得好",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "structured_context": {
            "focus_entity": {
                "type": "dish",
                "name": "招牌青花椒味(单人份)",
                "rank": 1,
            },
            "window_label": "最近7天",
            "requested_metrics": ["sales_volume"],
            "store_scope": "all",
            "store_names": [],
        },
    }

    effective, inherited = contextualize_restaurant_followup(
        "青花椒南方百联店它的成本如何",
        parent,
    )

    assert inherited is True
    assert effective == "最近7天青花椒南方百联店招牌青花椒味(单人份)的成本如何"
    assert "全部门店" not in effective
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        effective,
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )
    assert spec.store_scope == "single"
    assert spec.store_slots == ("青花椒南方百联店",)
    assert spec.dish_slot == "招牌青花椒味(单人份)"


@pytest.mark.asyncio
async def test_generic_optimization_parses_as_clarification_without_llm():
    spec = await parse_restaurant_query(
        "帮我优化一下餐厅经营",
        object(),
        factory_id="F_REST",
    )
    assert spec is not None
    assert spec.intent == ""
    assert spec.clarification_needed is True
    assert "优先优化哪一个目标" in spec.clarification_question


@pytest.mark.asyncio
async def test_regression_chart_request_returns_exportable_fields_not_false_success():
    spec = await parse_restaurant_query(
        "计算每个菜品销量与价格的线性回归曲线，并给出决定系数；如果不能绘图请给出可导出的字段",
        object(),
        factory_id="F_REST",
    )
    assert spec is not None
    assert spec.clarification_needed is True
    assert "不能可靠完成" in (spec.clarification_question or "")
    assert "菜品名称" in (spec.clarification_question or "")
    assert "决定系数" in (spec.clarification_question or "")


def test_contract_requires_executed_comparison_metadata():
    spec = _spec(comparison="previous_month")
    missing = contract.validate(
        spec,
        "本月营收比上个月高",
        kpis=[],
        meta={},
    )
    assert "comparison" in missing.missing

    passed = contract.validate(
        spec,
        "本月营收与上个月相比更高",
        kpis=[],
        meta={
            "comparison": {
                "answered": True,
                "primary_start": "2026-07-01",
                "primary_end": "2026-07-21",
                "baseline_start": "2026-06-01",
                "baseline_end": "2026-06-30",
                "baseline_label": "上个月",
                "primary_bills": 100,
                "baseline_bills": 80,
            },
        },
    )
    assert "comparison" not in passed.missing

    no_primary_data = contract.validate(
        spec,
        "本周没有数据；上周同期有记录，因此不能可靠判断相比是上升还是下降。",
        kpis=[],
        meta={
            "comparison": {
                "answered": True,
                "primary_start": "2026-07-27",
                "primary_end": "2026-07-27",
                "baseline_start": "2026-07-20",
                "baseline_end": "2026-07-20",
                "baseline_label": "上周同期",
                "primary_bills": 0,
                "baseline_bills": 80,
                "primary_no_data": True,
            },
        },
    )
    assert "comparison" not in no_primary_data.missing


def test_contract_rejects_partial_multi_objective_answer():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "请同时优化菜品销量和毛利率，给出优先级和依据",
        confidence=1.0,
        tier="test",
    )
    meta = {"marginInvariantPass": True}

    partial = contract.validate(
        spec,
        "菜品毛利率为72%，建议提高价格。",
        kpis=[],
        meta=meta,
    )
    assert "request_coverage" in partial.missing

    complete = contract.validate(
        spec,
        "优先级1：先推广高销量高毛利菜品；依据是销量和毛利率同时领先。",
        kpis=[],
        meta=meta,
    )
    assert "request_coverage" not in complete.missing


# ─── 7. 2026-07-07 live-verify follow-ups (paraphrase slot gaps + cache) ───
# Live demo caught "这两个月生意咋样，挣着钱没" losing both its time window
# and its profitability ask. These pin the three fixes:
#   (a) "这N个月/这N周" relative form (bare "这个月"/"这周" stay named windows)
#   (b) colloquial split profit forms ("挣着钱没" has no "挣钱" substring)
#   (c) T3 slot supplements (time_phrase + llm profit booleans) survive the
#       routing cache, and build_resolver_query splices a canonical profit
#       phrase for LLM-only detections.

def test_zhe_numeral_relative_window():
    (start, end), label = _resolve_sales_date_range("这两个月生意咋样", today=date(2026, 7, 7))
    assert label == "最近2个月"
    assert (end - start).days == 59

    (start3, end3), label3 = _resolve_sales_date_range("这3周表现", today=date(2026, 7, 7))
    assert label3 == "最近3周"
    assert (end3 - start3).days == 20


def test_bare_zhege_yue_and_zhezhou_stay_named_windows():
    _, label_month = _resolve_sales_date_range("这个月营收", today=date(2026, 7, 7))
    assert label_month == "本月"
    _, label_week = _resolve_sales_date_range("这周营收", today=date(2026, 7, 7))
    assert label_week == "本周"


def test_last_week_and_last_month_named_windows():
    # 2026-07-07 is a Tuesday: 上周 = Mon 6/29 .. Sun 7/5.
    (start, end), label = _resolve_sales_date_range("上周营收多少", today=date(2026, 7, 7))
    assert label == "上周"
    assert start == date(2026, 6, 29) and end == date(2026, 7, 5)

    (m_start, m_end), m_label = _resolve_sales_date_range("上个月赚钱了吗", today=date(2026, 7, 7))
    assert m_label == "上个月"
    assert m_start == date(2026, 6, 1) and m_end == date(2026, 6, 30)

    # 这周...比上周: 本周 stays the primary window.
    _, mixed_label = _resolve_sales_date_range("这周营收比上周差在哪里", today=date(2026, 7, 7))
    assert mixed_label == "本周"


def test_colloquial_split_profit_forms_detected():
    for q in ("这两个月生意咋样，挣着钱没", "赚着钱没", "有没有赚到钱", "最近挣到钱了吗"):
        spec = _build_spec("RESTAURANT_OPS_SALES_SUMMARY", q, confidence=1.0, tier="test")
        assert spec.asks_profitability is True, q
        assert spec.wants_margin is True, q


@pytest.mark.asyncio
async def test_t3_slot_supplements_survive_route_cache():
    """First call: T3 parses, supplements ride the spec. Second call: cache
    hit (LLM must NOT run again) rebuilds the SAME window + profit slots."""
    query = "生意有起色没，划算不划算"  # no deterministic time/profit token
    llm_json = json.dumps({
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": {"type": "relative", "unit": "month", "count": 2},
        "wants_margin": True, "asks_profitability": True,
        "dimensions": [], "comparison": None, "confidence": 0.88,
        "clarification_needed": False, "clarification_question": None,
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)) as mock_chain:
        spec1 = await parse_restaurant_query(query, _restaurant_pool(), factory_id="F_REST")
        spec2 = await parse_restaurant_query(query, _restaurant_pool(), factory_id="F_REST")

    assert mock_chain.await_count == 1  # second call served from route cache
    for spec in (spec1, spec2):
        assert spec is not None
        assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
        assert spec.window_label == "最近2个月"      # time_phrase supplement kept
        assert spec.asks_profitability is True       # llm profit slot kept
        assert spec.wants_margin is True
    assert spec1.window_label == spec2.window_label
    assert spec1.date_range == spec2.date_range


@pytest.mark.asyncio
async def test_contract_repaired_plan_stays_trusted_through_route_cache():
    query = "近30天畅销菜品"
    llm = AsyncMock(return_value={
        "intent": "RESTAURANT_OPS_REQUISITION_TREND",
        "confidence": 0.95,
        "clarification_needed": True,
        "clarification_question": "请明确想查哪类数据。",
    })
    with patch("smartbi.gold.restaurant_intent._t3_llm_parse", new=llm):
        spec1 = await parse_restaurant_query(
            query, _restaurant_pool(), factory_id="DEMO_REST",
        )
        spec2 = await parse_restaurant_query(
            query, _restaurant_pool(), factory_id="DEMO_REST",
        )

    assert llm.await_count == 1
    assert spec1 is not None and spec2 is not None
    assert spec1.intent == spec2.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec1.clarification_needed is spec2.clarification_needed is False
    assert spec1.planner_authority == "llm_contract_repair"
    assert spec2.planner_authority == "validated_plan_cache_contract_repair"


@pytest.mark.asyncio
async def test_build_resolver_query_splices_profit_phrase_for_llm_only_detection():
    query = "生意有起色没，划算不划算"
    llm_json = json.dumps({
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": None,
        "wants_margin": True, "asks_profitability": True,
        "dimensions": [], "comparison": None, "confidence": 0.85,
        "clarification_needed": False, "clarification_question": None,
    })
    fake_llm_result = {"choices": [{"message": {"content": llm_json}}]}
    with patch(
        "smartbi.services.template_embedding_index.cosine_topk",
        new=AsyncMock(return_value=[]),
    ), patch("common.llm_router.call_chain", new=AsyncMock(return_value=fake_llm_result)):
        spec = await parse_restaurant_query(query, _restaurant_pool(), factory_id="F_REST")

    assert spec is not None and spec.asks_profitability is True
    resolver_query = build_resolver_query(query, spec)
    # The resolver re-derives profit intent from raw text; the canonical
    # phrase makes its own _profit_intent fire for this LLM-only detection.
    assert "赚钱了吗" in resolver_query
    assert query in resolver_query


# ─── 8. 2026-07-08 audit fixes (window ordering / vocab / contract scoping) ─

@pytest.mark.parametrize("week_phrase", ["这周", "这个星期"])
def test_demo_acceptance_string_week_window_not_hijacked_by_today(week_phrase):
    """Audit fix A-1: the LITERAL Phase-1 acceptance query — the trailing
    action clause "今天先做哪几个动作" must not hijack the data window away
    from 本周. (The earlier regression test used a truncated string and
    missed this; 今天 branch now runs AFTER all week/month branches.)"""
    (start, end), label = _resolve_sales_date_range(
        f"{week_phrase}营收比上周差在哪里，今天先做哪几个动作",
        today=date(2026, 7, 7),
    )
    assert label == "本周"
    assert start == date(2026, 7, 6) and end == date(2026, 7, 7)


def test_pure_today_query_still_resolves_today():
    _, label = _resolve_sales_date_range("今天先做哪几个动作", today=date(2026, 7, 7))
    assert label == "今天"


def test_colloquial_numerals_and_half_year_windows():
    """时间词汇加硬: 俩/仨 numerals + rolling 半年 (calendar 上半年/下半年
    deliberately falls through to 全部历史 — honest fallback, not a guess)."""
    (s, e), label = _resolve_sales_date_range("这俩月生意咋样", today=date(2026, 7, 7))
    assert label == "最近2个月" and (e - s).days == 59

    _, label3 = _resolve_sales_date_range("最近仨月的营收", today=date(2026, 7, 7))
    assert label3 == "最近3个月"

    (hs, he), hlabel = _resolve_sales_date_range("近半年赚钱了吗", today=date(2026, 7, 7))
    assert hlabel == "最近半年" and (he - hs).days == 182

    _, uh_label = _resolve_sales_date_range("上半年营收多少", today=date(2026, 7, 7))
    assert uh_label == "全部历史"


def test_uses_relative_window_covers_last_week():
    """Audit fix A-2: 上周 must set relative_window so Phase 2 rule 4
    delegates (Java's resolveWindow does not understand 上周 at all)."""
    from smartbi.gold.restaurant_ops_router import _uses_relative_sales_window
    assert _uses_relative_sales_window("上周营收多少") is True
    assert _uses_relative_sales_window("上星期卖了多少") is True


def test_contract_scoping_by_resolver_capability():
    """Audit fix A-3: the contract only demands what the resolver CAN honor.
    WASTAGE_TOP's resolver ignores query/window/margin entirely — demanding
    them would mean a permanent disclaimer on every such answer."""
    wastage_spec = _spec_for_contract(
        intent="RESTAURANT_OPS_WASTAGE_TOP",
        window_label="最近7天", relative_window=True,
        wants_margin=True, asks_profitability=True,
    )
    assert contract.required_elements(wastage_spec) == []

    sales_spec = _spec_for_contract(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        window_label="最近7天", relative_window=True,
        wants_margin=True, asks_profitability=True,
    )
    assert contract.required_elements(sales_spec) == [
        "window_label", "profitability_verdict", "margin_value", "margin_integrity",
    ]

    dish_margin_spec = _spec_for_contract(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        window_label="最近7天", relative_window=True,
        wants_margin=True, dimensions=("dish",),
    )
    # window not required (resolver fixed 30d window), margin IS required,
    # dish naming still required.
    assert contract.required_elements(dish_margin_spec) == [
        "margin_value", "margin_integrity", "dish_name",
    ]


def test_dish_ranking_spec_keeps_store_scope_rank_and_exclusions():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月全部门店销量最高的5道菜是什么？请排除米饭、餐巾纸、湿纸巾和餐具",
        confidence=0.95,
        tier="llm",
    )

    assert spec.store_scope == "all"
    assert spec.store_slots == ()
    assert spec.ranking_direction == "best"
    assert spec.ranking_limit == 5
    assert spec.excluded_entities == ("米饭", "餐巾纸", "湿纸巾", "餐具")
    assert spec.requested_metrics == ("sales_volume",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
    assert spec.dimensions == ("dish",)


@pytest.mark.parametrize(
    "query",
    [
        "本月东城店销量是多少",
        "本月东城店毛利率是多少",
        "本月东城店营收是多少",
    ],
)
def test_named_store_metrics_compile_to_store_resolver(query):
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        confidence=0.95,
        tier="llm",
    )

    assert spec.store_scope == "single"
    assert spec.store_slots == ("东城店",)
    assert spec.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)


def test_ranking_followup_inherits_window_store_scope_and_exclusions():
    parent = {
        "parent_query": "本月全部门店销量最高的5道菜是什么",
        "parent_template_code": "RESTAURANT_OPS_GROSS_MARGIN",
        "turns_history": [{
            "q": "本月全部门店销量最高的5道菜是什么",
            "a_summary": "已返回前五名",
            "context": {
                "topic_kind": "dish_ranking",
                "ranking_direction": "best",
                "ranking_limit": 5,
                "excluded_entities": ["米饭", "餐巾纸", "湿纸巾", "餐具"],
                "store_scope": "all",
                "store_names": [],
                "window_label": "本月",
                "requested_metrics": ["sales_volume"],
            },
        }],
    }

    contextualized, inherited = contextualize_restaurant_followup(
        "那倒数五名呢？",
        parent,
    )

    assert inherited is True
    assert contextualized == (
        "本月全部门店销量最低的前5道菜，排除米饭、餐巾纸、湿纸巾、餐具"
    )


class _StoreScopeConn:
    def __init__(self, names):
        self.names = names
        self.rls_factory_id = None
        self.fetched_factory_id = None
        self.window_start = None
        self.window_end = None
        self.fetch_sql = ""

    def transaction(self):
        class _Ctx:
            async def __aenter__(self):
                return None

            async def __aexit__(self, *args):
                return False

        return _Ctx()

    async def execute(self, _sql, factory_id):
        self.rls_factory_id = factory_id

    async def fetch(self, sql, factory_id, window_start=None, window_end=None):
        self.fetched_factory_id = factory_id
        self.window_start = window_start
        self.window_end = window_end
        self.fetch_sql = sql
        return [{"name": name} for name in self.names]


class _StoreScopePool:
    def __init__(self, names):
        self.conn = _StoreScopeConn(names)

    def acquire(self):
        conn = self.conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *args):
                return False

        return _Ctx()


@pytest.mark.asyncio
async def test_multi_store_tenant_requires_scope_independent_of_query_window_activity():
    pool = _StoreScopePool(["东城店", "西城店", "南城店"])
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月销量最高的5道菜是什么",
        confidence=0.95,
        tier="llm",
    )

    guarded = await _apply_store_scope_guard(
        pool,
        "FACTORY_A",
        spec,
    )

    assert guarded is not None
    assert guarded.clarification_needed is True
    assert guarded.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION
    assert guarded.store_options == ("东城店", "西城店", "南城店")
    assert pool.conn.rls_factory_id == "FACTORY_A"
    assert pool.conn.fetched_factory_id == "FACTORY_A"
    assert pool.conn.window_start is None
    assert pool.conn.window_end is None
    assert "$2" not in pool.conn.fetch_sql
    assert "fact_pos_transaction" not in pool.conn.fetch_sql


@pytest.mark.parametrize(
    "generic_scope",
    (
        "全部门店",
        "所有门店",
        "各门店",
        "每家店",
        "所有店",
        "全部店",
        "全店汇总",
        "全店",
        "多家门店",
        "指定门店",
    ),
)
def test_all_store_scope_never_becomes_a_concrete_llm_entity(generic_scope):
    query = f"本月哪个菜卖得好 {generic_scope}"

    assert _verbatim_entity(generic_scope, query) is None


def test_real_store_name_remains_a_valid_verbatim_llm_entity():
    query = "本月有滋有味北外滩店哪个菜卖得好"

    assert _verbatim_entity("有滋有味北外滩店", query) == "有滋有味北外滩店"


@pytest.mark.parametrize(
    "phrase",
    (
        "截至目前",
        "到目前",
        "目前为止",
        "当前为止",
        "到今天为止",
        "截至今天",
        "开业至今",
        "至今累计",
        "当前累计",
    ),
)
def test_cumulative_time_phrases_are_explicit_all_history_to_today(phrase):
    (start, end), label = _resolve_sales_date_range(
        f"{phrase}的总销售额",
        today=date(2026, 7, 27),
    )

    assert (start, end) == (date(2000, 1, 1), date(2026, 7, 27))
    assert label == "截至目前"


def test_llm_store_total_sales_to_date_does_not_reask_time():
    query = "到目前的有滋有味北外滩店的总销售额呢"
    spec = _semantic_spec_from_t3(
        {
            "intent": "RESTAURANT_OPS_STORE_MARGIN",
            "time_range": None,
            "wants_margin": False,
            "asks_profitability": False,
            "confidence": 0.98,
            "clarification_needed": False,
            "missing_fields": [],
            "clarification_question": None,
            "clarification_options": [],
            "requested_metrics": ["revenue"],
            "dimensions": ["store"],
            "analysis_action": "lookup",
            "store_scope": "single",
            "dish": None,
            "store": "有滋有味北外滩店",
            "stores": ["有滋有味北外滩店"],
        },
        query,
        available_stores=(
            "兄弟土菜馆",
            "有滋有味北外滩店",
        ),
    )

    assert spec.clarification_needed is False
    assert spec.window_label == "截至目前"
    assert spec.date_range[0] == date(2000, 1, 1)
    assert spec.date_range[1] == date.today()
    assert spec.store_scope == "single"
    assert spec.store_slot == "有滋有味北外滩店"
    assert spec.requested_metrics == ("revenue",)


def test_llm_all_store_slot_cannot_poison_dish_ranking_execution_scope():
    query = "哪个菜卖得好 本月 全部门店"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=0.95,
        tier="llm",
        llm_store=_verbatim_entity("全部门店", query),
    )

    assert spec.store_scope == "all"
    assert spec.store_slot is None
    assert spec.store_slots == ()
    assert spec.dimensions == ("dish",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


def test_compact_time_and_all_store_scope_never_becomes_store_entity():
    query = "最近7天全部门店哪个菜卖得好"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=0.95,
        tier="llm",
    )

    assert extract_store_mention(query) is None
    assert spec.store_scope == "all"
    assert spec.store_slot is None
    assert spec.store_slots == ()
    assert spec.ranking_direction == "best"
    assert spec.dimensions == ("dish",)
    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)


@pytest.mark.asyncio
async def test_demo_multi_store_guard_scopes_rls_to_mapped_data_factory():
    pool = _StoreScopePool(["东城店", "西城店"])
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月销量最高的5道菜是什么",
        confidence=0.95,
        tier="llm",
    )

    guarded = await _apply_store_scope_guard(pool, "DEMO_REST", spec)

    assert guarded is not None
    assert guarded.clarification_needed is True
    assert pool.conn.rls_factory_id == "RES_3101_009"
    assert pool.conn.fetched_factory_id == "RES_3101_009"


@pytest.mark.asyncio
async def test_single_store_tenant_infers_scope_without_filtering_name():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月销量最高的5道菜是什么",
        confidence=0.95,
        tier="llm",
    )

    guarded = await _apply_store_scope_guard(
        _StoreScopePool(["唯一门店"]),
        "FACTORY_A",
        spec,
    )

    assert guarded is not None
    assert guarded.clarification_needed is False
    assert guarded.store_scope == "single"
    assert guarded.store_slots == ()
    assert guarded.store_options == ("唯一门店",)


@pytest.mark.asyncio
async def test_missing_store_dimension_does_not_invent_single_store_scope():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "本周营业额和上周相比是上升还是下降",
        confidence=0.95,
        tier="llm",
    )

    guarded = await _apply_store_scope_guard(
        _StoreScopePool([]),
        "FACTORY_A",
        spec,
    )

    assert guarded is spec
    assert guarded.clarification_needed is False
    assert guarded.store_scope is None


def _spec_for_contract(**overrides):
    defaults = dict(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        domain="restaurant",
        date_range=(None, None),
        window_label="全部历史",
        relative_window=False,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="keyword",
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)
