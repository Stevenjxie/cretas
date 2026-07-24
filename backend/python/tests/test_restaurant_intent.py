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
)
from smartbi.gold.restaurant_ops_router import _resolve_sales_date_range, match_restaurant_ops
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

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls += 1
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
    # No usable time phrase was spliced in (malformed time_range ignored) and
    # the raw query itself has no recognized time phrase -> falls back to the
    # deterministic parser's default for the untouched raw query.
    expected_range, expected_label = _resolve_sales_date_range(query)
    assert spec.window_label == expected_label
    assert spec.date_range == expected_range


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


def test_followup_with_explicit_new_entity_does_not_inherit_old_dish():
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

    assert inherited is False
    assert effective == "招牌藤椒味的成本如何"


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


def test_service_speed_root_cause_query_is_not_silently_reduced_to_sales():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "最近7天晚市出餐慢，是订单集中、人员不足还是工序瓶颈？请分别用数据判断",
        confidence=1.0,
        tier="test",
    )

    assert spec.clarification_needed is True
    assert spec.unsupported_requirements == ("service_speed", "process_bottleneck")
    assert "工序" in (spec.clarification_question or "")
    assert "订单" in (spec.clarification_question or "")


@pytest.mark.asyncio
async def test_parse_catches_service_speed_capability_gap_before_tenant_gate():
    spec = await parse_restaurant_query(
        "最近7天晚市出餐慢，是订单集中、人员不足还是工序瓶颈？请分别用数据判断",
        object(),
        factory_id="DEMO_REST",
    )

    assert spec is not None
    assert spec.intent == ""
    assert spec.clarification_needed is True
    assert spec.unsupported_requirements == ("service_speed", "process_bottleneck")
    assert "不会用营业额" in (spec.clarification_question or "")


def test_margin_and_return_rate_query_discloses_missing_capability():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "优化菜品结构，以提高毛利率并降低退菜率为目标",
        confidence=1.0,
        tier="test",
    )

    assert spec.clarification_needed is True
    assert spec.unsupported_requirements == ("return_rate",)
    assert "退菜" in (spec.clarification_question or "")


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
    question = spec.clarification_question or ""
    assert "退菜率" in question
    assert "顾客评价" in question
    assert "菜品制作时长" in question
    assert "菜品销量" in question
    assert "营业收入" in question
    assert "已覆盖销售的毛利" in question
    assert "食材损耗" in question
    assert "部分完成说成全部完成" in question


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


def test_sheet_dish_question_rejects_wrong_llm_summary_intent_before_execution():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "哪个菜卖得好",
        confidence=0.95,
        tier="llm",
    )

    assert spec.planned_intents == ("RESTAURANT_OPS_GROSS_MARGIN",)
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

def test_demo_acceptance_string_week_window_not_hijacked_by_today():
    """Audit fix A-1: the LITERAL Phase-1 acceptance query — the trailing
    action clause "今天先做哪几个动作" must not hijack the data window away
    from 本周. (The earlier regression test used a truncated string and
    missed this; 今天 branch now runs AFTER all week/month branches.)"""
    (start, end), label = _resolve_sales_date_range(
        "这周营收比上周差在哪里，今天先做哪几个动作", today=date(2026, 7, 7),
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
