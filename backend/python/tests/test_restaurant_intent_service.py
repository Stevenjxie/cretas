"""Unit tests for the Phase 2 Java-entry delegate gate.

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md

Covers:
  1. ``should_delegate`` decision matrix (section 3, >=10 cases) -- pure,
     no I/O.
  2. ``tiered_answer`` (the extracted shared implementation) -- mocked
     resolver/parse, mirrors the mocking style of test_restaurant_intent.py
     / test_restaurant_ops_router.py.
  3. ``POST /api/smartbi/gold/restaurant/tiered-answer`` endpoint function
     (``smartbi.api.gold_reads.post_restaurant_tiered_answer``) -- role
     passthrough (X-User-Role -> resolve_by_code) and fail-open on any
     internal exception.
"""
from __future__ import annotations

import asyncio
from dataclasses import replace
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import smartbi.api.gold_reads as gold_reads_mod
import smartbi.gold.restaurant_intent_service as svc
from smartbi.api.gold_reads import TieredIntentAnswerRequest, post_restaurant_tiered_answer
from smartbi.gold.restaurant_intent import (
    RestaurantQuerySpec,
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    _build_spec,
)
from smartbi.gold.restaurant_intent_service import should_delegate, tiered_answer
from smartbi.gold.restaurant_ops_router import OpsAnswer


def _spec(**overrides) -> RestaurantQuerySpec:
    defaults = dict(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
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
        clarification_needed=False,
        clarification_question=None,
    )
    defaults.update(overrides)
    return RestaurantQuerySpec(**defaults)


def _fake_request(role=None, user_id=None):
    return SimpleNamespace(state=SimpleNamespace(role=role, user_id=user_id))


# ─── 1. should_delegate decision matrix (design doc section 3) ────────────

def test_should_delegate_none_spec_false():
    """Rule 1: spec is None (T1/T2/T3 all missed / non-restaurant tenant /
    upstream exception) -> False, Java keeps its own flow."""
    assert should_delegate(None) is False


def test_resolver_kwargs_preserve_store_comparison_baseline():
    query = "本月和上月各门店营业额对比"
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        confidence=0.95,
        tier="llm",
        planner_authority="llm",
    )

    kwargs = svc._resolver_kwargs(spec, "restaurant_manager", query)

    assert kwargs["date_range"] == spec.date_range
    assert kwargs["comparison_date_range"][0] is not None
    assert kwargs["comparison_date_range"][1] is not None


def test_resolver_kwargs_use_sealed_comparison_when_current_text_is_only_store_scope():
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        "昨天的营业额是高于前天还是低于前天？ 全部门店",
        confidence=1.0,
        tier="explicit_comparison_slots",
        planner_authority="explicit_comparison_slots",
    )

    kwargs = svc._resolver_kwargs(
        spec,
        "restaurant_manager",
        "全部门店",
    )

    assert kwargs["date_range"] == spec.date_range
    assert kwargs["comparison_date_range"] == spec.comparison_range
    assert kwargs["window_label"] == spec.window_label
    assert kwargs["comparison_label"] == spec.comparison_label
    assert kwargs["comparison_kind"] == spec.comparison


def test_resolver_kwargs_forward_immutable_metric_action_and_ranking_slots():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "上个月全部门店菜品销量排名",
        confidence=0.98,
        tier="llm",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("dish",),
        llm_analysis_action="lookup",
        llm_store_scope="all",
        planner_authority="llm",
        llm_semantics_authoritative=True,
    )

    kwargs = svc._resolver_kwargs(spec, "restaurant_manager", "全部门店")

    assert kwargs["requested_metrics"] == ("sales_volume",)
    assert kwargs["analysis_action"] == "lookup"
    assert kwargs["ranking_direction"] == "best"
    assert kwargs["ranking_limit"] == 5


@pytest.mark.parametrize(
    ("code", "query"),
    [
        (
            "RESTAURANT_OPS_TREND_ANALYSIS",
            "生成本月全部门店营业额趋势图，按天，画二次拟合和计划线",
        ),
        (
            "RESTAURANT_OPS_GROSS_MARGIN",
            "生成本月全部门店菜品毛利趋势图",
        ),
    ],
)
def test_time_dimension_is_supported_by_time_aware_resolvers(code, query):
    spec = _build_spec(
        code,
        query,
        confidence=0.98,
        tier="llm",
        llm_requested_metrics=(
            ("revenue",) if code == "RESTAURANT_OPS_TREND_ANALYSIS"
            else ("gross_margin",)
        ),
        llm_dimensions=("time",),
        llm_analysis_action="lookup",
        llm_store_scope="all",
        planner_authority="llm",
        llm_semantics_authoritative=True,
    )

    mismatch = svc._execution_mismatch(
        spec,
        spec.planned_intents,
        dish_mention=None,
        store_mention=None,
        store_dish=None,
    )

    assert mismatch is None


def test_daypart_time_dimension_matches_staffing_resolver_capability():
    spec = _build_spec(
        "RESTAURANT_OPS_STAFFING_ADVICE",
        "本月全部门店晚上生意怎么样",
        confidence=0.99,
        tier="llm",
        llm_dimensions=("time",),
        llm_analysis_action="lookup",
        llm_store_scope="all",
        planner_authority="llm_contract_repair",
        require_explicit_time=True,
    )

    mismatch = svc._execution_mismatch(
        spec,
        spec.planned_intents,
        dish_mention=None,
        store_mention=None,
        store_dish=None,
    )

    assert spec.planned_intents == ("RESTAURANT_OPS_STAFFING_ADVICE",)
    assert mismatch is None


def test_should_delegate_validated_plan_cache_without_java_reinterpretation():
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        source_tier="plan_cache",
        planned_intents=("RESTAURANT_OPS_TREND_ANALYSIS",),
        plan_version="restaurant-query-plan-v2",
        planner_authority="validated_plan_cache",
        plan_hash="cached-plan",
    )

    assert should_delegate(spec) is True


def test_should_delegate_reviewed_exact_plan_without_java_reinterpretation():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "哪个菜卖得好 本月 全部门店",
        confidence=1.0,
        tier="exact",
        planner_authority="promoted_exact",
        require_explicit_time=True,
    )

    assert spec.clarification_needed is False
    assert should_delegate(spec) is True


@pytest.mark.asyncio
async def test_reviewed_exact_missing_time_returns_the_four_expected_buttons():
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "哪个菜卖得好",
        confidence=1.0,
        tier="exact",
        planner_authority="promoted_exact",
        require_explicit_time=True,
    )

    result = await tiered_answer(
        "哪个菜卖得好",
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "clarification"
    assert result["answer_text"] == TIME_CLARIFICATION_QUESTION
    assert result["suggested_followups"] == [
        {"label": "本月", "question": "本月"},
        {"label": "上个月", "question": "上个月"},
        {"label": "最近7天", "question": "最近7天"},
        {"label": "最近30天", "question": "最近30天"},
    ]


@pytest.mark.parametrize("authority", [
    "llm_contract_repair",
    "validated_plan_cache_contract_repair",
    "promoted_exact_contract_repair",
    "trusted_context",
    "trusted_context_contract_repair",
])
def test_should_delegate_contract_repair_without_java_reinterpretation(authority):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        source_tier="llm",
        dimensions=("dish",),
        requested_metrics=("sales_volume",),
        planned_intents=("RESTAURANT_OPS_GROSS_MARGIN",),
        plan_version="restaurant-query-plan-v2",
        planner_authority=authority,
        plan_hash="repaired-plan",
    )

    assert should_delegate(spec) is True


def test_all_store_dish_followup_matches_aggregate_resolver_capability():
    query = "最近7天全部门店招牌青花椒味(单人份)的成本和毛利呢？"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=1.0,
        tier="trusted_context",
        planner_authority="trusted_context",
        require_explicit_time=True,
    )

    mismatch = svc._execution_mismatch(
        spec,
        spec.planned_intents,
        dish_mention="招牌青花椒味(单人份)",
        store_mention=None,
        store_dish=None,
    )

    assert spec.dimensions == ("dish",)
    assert mismatch is None


@pytest.mark.parametrize(
    "query",
    [
        "最近7天青花椒南方百联店招牌青花椒味(单人份)的成本和毛利呢？",
        (
            "最近7天青花椒南方百联店和青花椒徐汇光启城店"
            "招牌青花椒味(单人份)的成本和毛利呢？"
        ),
    ],
)
def test_named_store_dish_followup_matches_store_resolver_capability(query):
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=1.0,
        tier="trusted_context",
        planner_authority="trusted_context",
        require_explicit_time=True,
    )

    mismatch = svc._execution_mismatch(
        spec,
        spec.planned_intents,
        dish_mention="招牌青花椒味(单人份)",
        store_mention=spec.store_slot,
        store_dish="招牌青花椒味(单人份)",
    )

    assert set(spec.dimensions) == {"store", "dish"}
    assert spec.planned_intents == ("RESTAURANT_OPS_STORE_MARGIN",)
    assert mismatch is None


def test_should_delegate_clarification_needed_true():
    """Rule 2: clarification_needed -> True (Java can't ask a clarifying
    question itself)."""
    spec = _spec(clarification_needed=True, clarification_question="您想看哪方面？", intent="")
    assert should_delegate(spec) is True


def test_should_delegate_asks_profitability_true():
    """Rule 3: asks_profitability + margin-capable intent -> True (Java Gold
    Tool family never produces a profit verdict; 2026-07-08 audit fix A-3
    scoped the rule to intents whose Python resolver CAN produce one)."""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        asks_profitability=True, wants_margin=True,
    )
    assert should_delegate(spec) is True


def test_should_delegate_wants_margin_without_profitability_true():
    """Rule 3 also fires on wants_margin alone (not just asks_profitability),
    on a margin-capable intent."""
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        wants_margin=True, asks_profitability=False,
    )
    assert should_delegate(spec) is True


@pytest.mark.parametrize("incapable_intent", [
    "RESTAURANT_OPS_WASTAGE_TOP",
    "RESTAURANT_OPS_STOCK_SHORTAGE",
    "RESTAURANT_OPS_RECIPE_COST",
    "RESTAURANT_OPS_REQUISITION_TREND",
    "RESTAURANT_OPS_TREND_ANALYSIS",
])
def test_should_delegate_margin_ask_on_incapable_intent_false(incapable_intent):
    """2026-07-08 audit fix A-3: a profit/margin mention on an intent whose
    resolver ignores it (fixed 30-day window, no role/query params) must NOT
    delegate -- the Python answer would ignore the ask and the contract would
    append a permanent disclaimer; Java's own answer is strictly better."""
    spec = _spec(
        intent=incapable_intent,
        wants_margin=True, asks_profitability=True,
    )
    assert should_delegate(spec) is False


def test_should_delegate_sales_summary_relative_window_true():
    """Rule 4: SALES_SUMMARY + relative_window -> True ("最近N天/周/月" ops
    summary windows are only honored by the Python resolver)."""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        relative_window=True,
        window_label="最近2个月",
    )
    assert should_delegate(spec) is True


def test_should_delegate_multi_resolver_plan_true():
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        planned_intents=(
            "RESTAURANT_OPS_RECIPE_COST",
            "RESTAURANT_OPS_WASTAGE_TOP",
            "RESTAURANT_OPS_STORE_MARGIN",
        ),
    )
    assert should_delegate(spec) is True


def test_should_delegate_sales_summary_absolute_window_now_true():
    """R23 规格即路由: 绝对月份窗现在由 python 日期解析器支持
    (「3月份」→日历月), 确定性层的 SALES_SUMMARY 规格直接委托。"""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        relative_window=False,
        window_label="2025年12月",
    )
    assert should_delegate(spec) is True


def test_should_delegate_pure_trend_query_now_true():
    """R23 规格即路由: 趋势 resolver 存在即委托 (旧规则 5 保守性退役)。"""
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        relative_window=False,
    )
    assert should_delegate(spec) is True


def test_should_delegate_ranking_query_now_true():
    """R23 规格即路由: STORE_MARGIN resolver 存在即委托。"""
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        wants_margin=False,
        asks_profitability=False,
        relative_window=False,
    )
    assert should_delegate(spec) is True


def test_should_delegate_relative_window_any_resolver_intent_true():
    """R23 规格即路由: 相对窗+任意 resolver 支持的意图均委托。"""
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        relative_window=True,
        window_label="最近3个月",
    )
    assert should_delegate(spec) is True


def test_should_delegate_absolute_month_recipe_cost_now_true():
    """R23 规格即路由: RECIPE_COST resolver 存在即委托 (无盈亏问 →
    A-3 例外不触发)。"""
    spec = _spec(
        intent="RESTAURANT_OPS_RECIPE_COST",
        window_label="2025年12月",
        relative_window=False,
    )
    assert should_delegate(spec) is True


def test_should_delegate_multiple_true_rules_still_true():
    """Rule ordering doesn't matter when multiple rules would independently
    fire -- clarification_needed AND asks_profitability both true."""
    spec = _spec(clarification_needed=True, asks_profitability=True, wants_margin=True)
    assert should_delegate(spec) is True


@pytest.mark.parametrize("java_tool_name", [None, "restaurant_revenue_trend_gold", "restaurant_store_revenue_rank_gold"])
def test_should_delegate_java_tool_name_does_not_change_decision(java_tool_name):
    """java_tool_name is accepted for future per-tool exceptions (design
    section 3) but is NOT currently branched on -- same spec always yields
    the same decision regardless of which Java tool is asking."""
    spec = _spec(intent="RESTAURANT_OPS_SALES_SUMMARY", asks_profitability=True)
    assert should_delegate(spec, java_tool_name) is True

    # R23 后确定性层普遍委托; 用 A-3 例外组合 (盈亏问+不懂盈亏的 resolver)
    # 保住「决策与 java_tool_name 无关」这条不变量的 False 侧。
    spec2 = _spec(intent="RESTAURANT_OPS_WASTAGE_TOP", asks_profitability=True)
    assert should_delegate(spec2, java_tool_name) is False


# ─── 2. tiered_answer (extracted shared implementation) ───────────────────

@pytest.mark.asyncio
async def test_tiered_answer_clarification_skips_resolver(monkeypatch):
    spec = _spec(clarification_needed=True, clarification_question="您想看哪方面？", intent="")

    async def _fake_parse(*a, **kw):
        return spec

    async def _boom(*a, **kw):
        raise AssertionError("resolver must not run on the clarification path")

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _boom)

    result = await tiered_answer("情况怎么样", object(), "QHJ01", None)
    assert result["kind"] == "clarification"
    assert result["answer_text"] == "您想看哪方面？"
    assert result["structured_context"]["analysis_action"] == "lookup"
    assert result["spec"] is spec


@pytest.mark.asyncio
async def test_time_clarification_returns_four_continuation_buttons(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        clarification_needed=True,
        clarification_question=TIME_CLARIFICATION_QUESTION,
        # A stale LLM proposal for the next store slot must never override the
        # deterministic time question displayed to the user.
        clarification_options=("全部门店", "东城店"),
        dimensions=("dish",),
        requested_metrics=("sales_volume",),
        ranking_direction="best",
        dish_slot="招牌藤椒味(单人份)",
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )

    result = await tiered_answer(
        "招牌藤椒味(单人份)销量如何",
        object(),
        "QHJ01",
        "restaurant_manager",
    )

    assert result["kind"] == "clarification"
    assert result["answer_text"] == TIME_CLARIFICATION_QUESTION
    assert result["suggested_followups"] == [
        {"label": "本月", "question": "本月"},
        {"label": "上个月", "question": "上个月"},
        {"label": "最近7天", "question": "最近7天"},
        {"label": "最近30天", "question": "最近30天"},
    ]
    assert result["structured_context"]["topic_kind"] == "dish_ranking"
    assert result["structured_context"]["focus_entity"]["name"] == "招牌藤椒味(单人份)"


@pytest.mark.asyncio
async def test_tiered_answer_executes_the_sealed_dish_slot_before_context_regex(monkeypatch):
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "本月全部门店米饭的成本如何",
        confidence=0.99,
        tier="llm",
        llm_requested_metrics=("recipe_cost",),
        llm_dimensions=("dish",),
        llm_analysis_action="lookup",
        llm_store_scope="all",
        planner_authority="llm_contract_repair",
        require_explicit_time=True,
    )
    spec = replace(
        spec,
        # Simulate a stale surface string from an older context turn.  The
        # immutable dish slot above must remain the execution object.
        resolver_query_seed="招牌藤椒味(单人份)的成本 本月 全部门店",
    )
    captured = {}

    async def _resolve(code, _pool, _factory_id, **kwargs):
        captured.update(kwargs)
        return OpsAnswer(
            code=code,
            title="米饭成本",
            answer_text="「米饭」本月单份成本 ¥2.26，总成本 ¥69,501.17。",
            charts=[],
            kpis=[],
            meta={
                "targetDish": "米饭",
                "focus_entity": {"type": "dish", "name": "米饭"},
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=None))

    result = await tiered_answer(
        "那成本呢",
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert captured["dish_mention"] == "米饭"
    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert "「米饭」" in result["answer_text"]


@pytest.mark.asyncio
async def test_tiered_answer_fail_open_on_parse_exception(monkeypatch):
    async def _boom(*a, **kw):
        raise RuntimeError("boom")

    monkeypatch.setattr(svc, "parse_restaurant_query", _boom)
    result = await tiered_answer("随便问问", object(), "QHJ01", None)
    assert result is None


@pytest.mark.asyncio
async def test_tiered_answer_none_when_resolver_returns_nothing(monkeypatch):
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS")

    async def _fake_parse(*a, **kw):
        return spec

    async def _fake_resolve(*a, **kw):
        return None

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)

    result = await tiered_answer("营收趋势", object(), "QHJ01", None)
    assert result is None


@pytest.mark.asyncio
async def test_tiered_answer_forwards_role_to_resolver(monkeypatch):
    spec = _spec(intent="RESTAURANT_OPS_STORE_MARGIN", wants_margin=True)

    async def _fake_parse(*a, **kw):
        return spec

    captured: dict = {}

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        captured.update(kwargs)
        return OpsAnswer(
            code=code, title="门店毛利", answer_text="哪家店最赚钱：A店 毛利率12%",
            charts=[], kpis=[], meta={
                "marginInvariantPass": True,
                "scope_matches_request": True,
            },
        )

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=None))

    result = await tiered_answer("哪家店最赚钱", object(), "QHJ01", "finance_manager")
    assert captured.get("role") == "finance_manager"
    assert result["kind"] == "answer"


@pytest.mark.asyncio
async def test_store_sales_overview_contract_passes_with_all_llm_metrics(monkeypatch):
    query = "鲜行者打浦桥日月光店这家店的销售情况 本月"
    spec = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        query,
        confidence=0.98,
        tier="llm",
        llm_store="鲜行者打浦桥日月光店",
        llm_requested_metrics=("revenue", "orders", "sales_volume"),
        llm_dimensions=("store",),
        llm_analysis_action="diagnose",
        llm_store_scope="single",
        planner_authority="llm",
        require_explicit_time=True,
        llm_semantics_authoritative=True,
    )
    captured = {}

    async def _resolve(code, pool, factory_id, **kwargs):
        captured.update(kwargs)
        return OpsAnswer(
            code=code,
            title="门店销售情况",
            answer_text=(
                "**本月鲜行者打浦桥日月光店销售情况：**"
                "营收 ¥12,680.00、订单 8 单、菜品销量 236 份。"
                "**原因拆解：**现有数据能确认结果，但不能单独证明原因。"
            ),
            charts=[],
            kpis=[],
            meta={
                "scope_matches_request": True,
                "focus_entity": {
                    "type": "store",
                    "name": "鲜行者打浦桥日月光店",
                },
                "stores": [{"name": "鲜行者打浦桥日月光店"}],
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert captured["requested_metrics"] == (
        "revenue",
        "orders",
        "sales_volume",
    )
    assert captured["analysis_action"] == "diagnose"
    assert captured["window_label"] == "本月"


@pytest.mark.asyncio
async def test_low_sales_premise_verification_survives_answer_contract(monkeypatch):
    query = "全部门店卤炸牛肉串本月销量为什么低"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=0.98,
        tier="llm",
        llm_dish="卤炸牛肉串",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("dish",),
        llm_analysis_action="diagnose",
        llm_store_scope="all",
        planner_authority="llm_contract_repair",
        require_explicit_time=True,
        llm_semantics_authoritative=True,
    )

    async def _resolve(code, pool, factory_id, **kwargs):
        return OpsAnswer(
            code=code,
            title="菜品销量诊断",
            answer_text=(
                "判断：「卤炸牛肉串」2026-07-01 至 2026-07-27销量 251.56 份，"
                "在 476 道可比主菜中按销量从高到低排第 117，中位数 51.48 份；"
                "不低于中位数，“销量低”的前提不成立。覆盖321单，平均每单0.78份。"
                "因此不能按“低销量问题”直接制定动作。"
            ),
            charts=[],
            kpis=[],
            meta={
                "scope_matches_request": True,
                "focus_entity": {
                    "type": "dish",
                    "name": "卤炸牛肉串",
                },
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=None))

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert "前提不成立" in result["answer_text"]
    assert "没有可靠覆盖" not in result["answer_text"]


@pytest.mark.asyncio
async def test_high_sales_premise_verification_survives_answer_contract(monkeypatch):
    query = "全部门店卤炸牛肉串本月销量为什么高"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=0.98,
        tier="llm",
        llm_dish="卤炸牛肉串",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("dish",),
        llm_analysis_action="diagnose",
        llm_store_scope="all",
        planner_authority="llm_contract_repair",
        require_explicit_time=True,
        llm_semantics_authoritative=True,
    )

    async def _resolve(code, pool, factory_id, **kwargs):
        return OpsAnswer(
            code=code,
            title="菜品销量诊断",
            answer_text=(
                "判断：「卤炸牛肉串」2026-07-01 至 2026-07-27销量 251.56 份，"
                "在 476 道可比主菜中按销量从高到低排第 117，中位数 51.48 份；"
                "高于中位数，“销量高”的前提成立。覆盖321单，平均每单0.78份。"
                "现有汇总数据还不能证明为什么高。"
            ),
            charts=[],
            kpis=[],
            meta={
                "scope_matches_request": True,
                "focus_entity": {
                    "type": "dish",
                    "name": "卤炸牛肉串",
                },
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=None))

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert "“销量高”的前提成立" in result["answer_text"]
    assert "没有可靠覆盖" not in result["answer_text"]


@pytest.mark.asyncio
async def test_colloquial_store_best_dish_uses_llm_ranking_slot(monkeypatch):
    query = "鲜行者打浦桥日月光店这家店买的最好的是哪一道菜 本月"
    spec = _build_spec(
        "RESTAURANT_OPS_STORE_MARGIN",
        query,
        confidence=0.98,
        tier="llm",
        llm_store="鲜行者打浦桥日月光店",
        llm_requested_metrics=("sales_volume",),
        llm_dimensions=("store", "dish"),
        llm_analysis_action="lookup",
        llm_store_scope="single",
        planner_authority="llm",
        require_explicit_time=True,
        llm_semantics_authoritative=True,
    )
    assert spec.ranking_direction == "best"
    captured = {}

    async def _resolve(code, pool, factory_id, **kwargs):
        captured.update(kwargs)
        return OpsAnswer(
            code=code,
            title="门店菜品销量排行",
            answer_text=(
                "**本月鲜行者打浦桥日月光店菜品销量排行：**"
                "1. 招牌藤椒鸡 — 销量 120 份。"
            ),
            charts=[],
            kpis=[],
            meta={
                "scope_matches_request": True,
                "dish_ranking": "best",
                "ranking_limit": 5,
                "focus_entity": {
                    "type": "dish",
                    "name": "招牌藤椒鸡",
                    "rank": 1,
                },
                "ranked_entities": [{
                    "type": "dish",
                    "name": "招牌藤椒鸡",
                    "rank": 1,
                }],
                "stores": [{"name": "鲜行者打浦桥日月光店"}],
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert captured["ranking_direction"] == "best"
    assert captured["requested_metrics"] == ("sales_volume",)
    assert "dish_mention" not in captured


@pytest.mark.asyncio
async def test_tiered_answer_capture_tags_java_entry_delegate_source(monkeypatch):
    """When called with java_tool_name (Phase 2 delegate-gate path), the
    fire-and-forget capture log must tag agg_meta.source =
    "java_entry_delegate" (design section 2) so it's distinguishable from a
    direct chat.py SSE capture."""
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS")

    async def _fake_parse(*a, **kw):
        return spec

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        return OpsAnswer(
            code=code, title="营收趋势", answer_text="营收趋势: 2026-01 上涨",
            charts=[], kpis=[], meta={},
        )

    capture_mock = AsyncMock(return_value=1)
    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)
    monkeypatch.setattr(svc, "log_intent_capture", capture_mock)

    await tiered_answer(
        "营收趋势", object(), "QHJ01", "restaurant_manager",
        java_tool_name="restaurant_revenue_trend_gold",
    )
    # log_intent_capture is scheduled via asyncio.create_task -- the call
    # itself (and therefore its recorded call_args) happens synchronously
    # inside create_task(...); let the loop tick once so the task finishes
    # cleanly (avoids "task was destroyed but it is pending" noise).
    await asyncio.sleep(0)

    assert capture_mock.call_args.kwargs["source"] == "java_entry_delegate"


@pytest.mark.asyncio
async def test_tiered_answer_capture_omits_source_without_java_tool_name(monkeypatch):
    """The 3 existing chat.py call sites never pass java_tool_name -- the
    capture log must NOT gain a source tag for them (byte-identical
    agg_meta shape, zero regression)."""
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS")

    async def _fake_parse(*a, **kw):
        return spec

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        return OpsAnswer(
            code=code, title="营收趋势", answer_text="营收趋势: 2026-01 上涨",
            charts=[], kpis=[], meta={},
        )

    capture_mock = AsyncMock(return_value=1)
    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)
    monkeypatch.setattr(svc, "log_intent_capture", capture_mock)

    await tiered_answer("营收趋势", object(), "QHJ01", "restaurant_manager")
    await asyncio.sleep(0)

    assert capture_mock.call_args.kwargs["source"] is None


@pytest.mark.asyncio
async def test_tiered_answer_internal_only_text_is_not_a_success(monkeypatch):
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS")

    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="经营分析",
            answer_text="通过调用 income_statement_query 工具获取数据表。",
            charts=[],
            kpis=[],
            meta={},
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer("营收趋势", object(), "QHJ01", "restaurant_manager")
    await asyncio.sleep(0)

    assert result is not None
    assert result["kind"] == "clarification"
    assert result["contract_pass"] is False
    assert "没有向您展示可能答非所问的数据" in result["answer_text"]
    assert "已完成" not in result["answer_text"]


@pytest.mark.asyncio
async def test_contract_failure_keeps_read_action_non_execution_notice(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        resolver_query_seed="把最近7天销量最低的5道菜全部下架",
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="低销量菜品",
            answer_text="通过调用 income_statement_query 工具获取数据表。",
            charts=[],
            kpis=[],
            meta={},
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "全部门店",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )
    await asyncio.sleep(0)

    assert result["kind"] == "clarification"
    assert result["contract_pass"] is False
    assert "当前未执行任何下架" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING


@pytest.mark.asyncio
async def test_tiered_answer_executes_and_combines_multi_resolver_plan(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        requested_metrics=("recipe_cost", "wastage", "gross_margin"),
        planned_intents=(
            "RESTAURANT_OPS_RECIPE_COST",
            "RESTAURANT_OPS_WASTAGE_TOP",
            "RESTAURANT_OPS_STORE_MARGIN",
        ),
        asks_priority=True,
    )
    calls = []

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        calls.append((code, kwargs))
        answers = {
            "RESTAURANT_OPS_RECIPE_COST": "菜品成本：招牌菜 ¥18。",
            "RESTAURANT_OPS_WASTAGE_TOP": "食材损耗：近30天 ¥300。",
            "RESTAURANT_OPS_STORE_MARGIN": "门店毛利：A店毛利率68%。",
        }
        return OpsAnswer(
            code=code,
            title=code,
            answer_text=answers[code],
            charts=[],
            kpis=[],
            meta={"marginInvariantPass": True} if code.endswith("STORE_MARGIN") else {},
        )

    monkeypatch.setattr(svc, "parse_restaurant_query", AsyncMock(return_value=spec))
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "最近30天把菜品成本、食材损耗和门店毛利都查一下，告诉我先查哪项",
        object(),
        "QHJ01",
        "restaurant_owner",
    )
    await asyncio.sleep(0)

    assert [code for code, _ in calls] == list(spec.planned_intents)
    assert "菜品成本" in result["answer_text"]
    assert "食材损耗" in result["answer_text"]
    assert "门店毛利" in result["answer_text"]
    assert "优先级" in result["answer_text"]
    assert result["contract_pass"] is True


@pytest.mark.asyncio
async def test_tiered_answer_analyzes_supported_metrics_and_lists_missing_dimensions(
    monkeypatch,
):
    query = "本月优化菜品结构，提高毛利率并降低退菜率"
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        query,
        confidence=1.0,
        tier="llm",
        planner_authority="llm",
    )
    assert spec.clarification_needed is False

    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code="RESTAURANT_OPS_GROSS_MARGIN",
            title="菜品优化",
            answer_text="本月菜品毛利率为 62%。优化建议：先复核低毛利菜品。",
            charts=[],
            kpis=[],
            meta={
                "marginInvariantPass": True,
                "scope_matches_request": True,
            },
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        query,
        object(),
        "QHJ01",
        "restaurant_owner",
        precomputed_spec=spec,
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert result["contract_pass"] is True
    assert "本次缺少数据、暂时留空的维度" in result["answer_text"]
    assert "退菜率" in result["answer_text"]
    assert "没有参与结论" in result["answer_text"]
    assert "相邻指标替代" in result["answer_text"]


@pytest.mark.asyncio
async def test_tiered_answer_marks_operation_words_as_read_only_without_blocking(
    monkeypatch,
):
    spec = _spec(intent="RESTAURANT_OPS_GROSS_MARGIN")
    monkeypatch.setattr(svc, "parse_restaurant_query", AsyncMock(return_value=spec))
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="低销量菜品",
            answer_text="最近7天销量最低的5道菜已经列出。",
            charts=[],
            kpis=[],
            meta={},
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "把最近7天销量最低的5道菜全部下架",
        object(),
        "QHJ01",
        "restaurant_owner",
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert "当前未执行任何下架" in result["warning"]
    assert "当前未执行任何下架" in result["answer_text"]
    assert "切换到操作模式" in result["warning"]


def test_priority_section_uses_actual_non_cost_plan():
    results = [
        ("RESTAURANT_OPS_SALES_SUMMARY", SimpleNamespace(meta={})),
        ("RESTAURANT_OPS_STAFFING_ADVICE", SimpleNamespace(meta={})),
    ]

    section = svc._priority_section(results)

    assert "营收与订单" in section
    assert "排班人效" in section
    assert "订单峰谷" in section
    assert "菜品成本" not in section
    assert "食材损耗" not in section


def test_combined_margin_integrity_is_false_when_any_sub_result_fails():
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        planned_intents=(
            "RESTAURANT_OPS_GROSS_MARGIN",
            "RESTAURANT_OPS_STORE_MARGIN",
        ),
    )
    results = [
        (
            "RESTAURANT_OPS_GROSS_MARGIN",
            OpsAnswer("a", "菜品毛利", "菜品毛利", [], [], {
                "marginInvariantPass": False,
                "scope_matches_request": True,
            }),
        ),
        (
            "RESTAURANT_OPS_STORE_MARGIN",
            OpsAnswer("b", "门店毛利", "门店毛利", [], [], {
                "marginInvariantPass": True,
                "scope_matches_request": True,
            }),
        ),
    ]

    combined = svc._combine_planned_answers(spec, results)

    assert combined.meta["marginInvariantPass"] is False
    assert combined.meta["scope_matches_request"] is True


# ─── 3. POST /api/smartbi/gold/restaurant/tiered-answer endpoint ──────────

@pytest.mark.asyncio
async def test_endpoint_delegate_false_when_should_delegate_false(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        relative_window=False,
        source_tier="vector",
        confidence=0.70,
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )

    body = TieredIntentAnswerRequest(
        factory_id="QHJ01", query="营收趋势", java_tool_name="restaurant_revenue_trend_gold",
    )
    result = await post_restaurant_tiered_answer(_fake_request("restaurant_manager"), body)
    assert result == {"delegate": False}


@pytest.mark.asyncio
async def test_endpoint_delegate_true_answer_shape(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        wants_margin=True, asks_profitability=True,
        relative_window=True, window_label="最近2个月",
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )

    tiered_result = {
        "kind": "answer",
        "answer_text": "最近2个月营收..是赚钱的，毛利约1700",
        "charts": [{"type": "line"}],
        "kpis": [{"title": "毛利"}],
        "title": "经营概览",
        "code": spec.intent,
        "contract_pass": True,
        "spec": spec,
    }
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value=tiered_result),
    )

    body = TieredIntentAnswerRequest(
        factory_id="QHJ01", query="这两个月挣钱没", java_tool_name="restaurant_revenue_trend_gold",
    )
    result = await post_restaurant_tiered_answer(_fake_request("restaurant_manager"), body)
    assert result == {
        "delegate": True,
        "answer_text": tiered_result["answer_text"],
        "charts": tiered_result["charts"],
        "kpis": tiered_result["kpis"],
        "code": spec.intent,
        "contract_pass": True,
        "query_plan_hash": None,
        "executed_resolvers": [],
        "suggested_followups": [],
    }


@pytest.mark.asyncio
async def test_endpoint_delegates_sealed_read_action_choice(monkeypatch):
    """The final READ/store reply must preserve its sealed v2 authority."""
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "DEMO_REST")
    monkeypatch.setattr(
        gold_reads_mod,
        "get_pg_pool",
        AsyncMock(return_value=object()),
    )
    from smartbi.services.chat_session_service import ChatSessionService
    monkeypatch.setattr(
        ChatSessionService,
        "lookup",
        AsyncMock(return_value=None),
    )
    monkeypatch.setattr(
        ChatSessionService,
        "upsert",
        AsyncMock(return_value=None),
    )
    spec = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN",
        "最近7天全部门店销量最低的5道菜",
        confidence=1.0,
        tier="explicit_action_read_choice",
        planner_authority="explicit_action_read_choice",
        is_continuation=True,
        require_explicit_time=True,
    )
    assert spec.clarification_needed is False
    assert "下架" not in spec.resolver_query_seed
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    tiered_result = {
        "kind": "answer",
        "answer_text": (
            "**当前未执行任何下架、调价或其他业务操作。**\n\n"
            "最近7天全部门店卖得最差前 5 道菜。"
        ),
        "charts": [],
        "kpis": [],
        "title": "低销量菜品",
        "code": spec.intent,
        "contract_pass": True,
        "spec": spec,
    }
    tiered_mock = AsyncMock(return_value=tiered_result)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        tiered_mock,
    )

    result = await post_restaurant_tiered_answer(
        _fake_request("restaurant_manager", user_id="88"),
        TieredIntentAnswerRequest(
            factory_id="DEMO_REST",
            query="全部门店",
            java_tool_name="orchestrator_null_intent",
            session_id="read-action-view-first",
        ),
    )

    assert result["delegate"] is True
    assert result["contract_pass"] is True
    assert "当前未执行任何下架" in result["answer_text"]
    tiered_mock.assert_awaited_once()
    assert tiered_mock.await_args.kwargs["precomputed_spec"] is spec


@pytest.mark.asyncio
async def test_endpoint_dependent_followup_sends_raw_query_and_history_to_llm(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    pool = object()
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=pool))

    from smartbi.services.chat_session_service import ChatSessionService

    parent = {
        "parent_query": "上个月营收怎么样",
        "parent_answer_summary": "上个月营收已完成分析",
        "parent_template_code": "RESTAURANT_OPS_SALES_SUMMARY",
        "structured_context": {
            "window_label": "上个月",
            "requested_metrics": ["revenue"],
            "analysis_action": "lookup",
        },
    }
    lookup = AsyncMock(return_value=parent)
    upsert = AsyncMock(return_value=None)
    monkeypatch.setattr(ChatSessionService, "lookup", lookup)
    monkeypatch.setattr(ChatSessionService, "upsert", upsert)

    parse_calls = []
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        relative_window=False,
        resolver_query_seed="上个月营收怎么样 那为什么呢",
    )

    async def _fake_parse(query, pool_arg, **kwargs):
        parse_calls.append((query, pool_arg, kwargs))
        return spec

    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        _fake_parse,
    )

    tiered_calls = []

    async def _fake_tiered(query, pool_arg, factory_id, role, **kwargs):
        tiered_calls.append((query, pool_arg, factory_id, role, kwargs))
        return {
            "kind": "answer",
            "answer_text": "上个月营收的原因分析",
            "charts": [],
            "kpis": [],
            "title": "经营分析",
            "code": spec.intent,
            "contract_pass": True,
            "spec": spec,
        }

    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        _fake_tiered,
    )

    body = TieredIntentAnswerRequest(
        factory_id="QHJ01",
        query="那为什么呢",
        java_tool_name="restaurant_revenue_trend_gold",
        session_id="shared-device-session",
    )
    result = await post_restaurant_tiered_answer(
        _fake_request("restaurant_manager", user_id="88"),
        body,
    )

    assert result["delegate"] is True
    assert parse_calls[0][0] == "那为什么呢"
    assert parse_calls[0][2]["semantic_first"] is True
    assert parse_calls[0][2]["history"] == [{
        "q": "上个月营收怎么样",
        "a_summary": "上个月营收已完成分析",
        "context": parent["structured_context"],
    }]
    session_key = parse_calls[0][2]["session_key"]
    assert session_key.startswith("trusted-v1:")
    assert "shared-device-session" not in session_key
    assert tiered_calls[0][4]["session_key"] == session_key
    assert tiered_calls[0][4]["precomputed_spec"] is spec
    assert tiered_calls[0][4]["history"] == parse_calls[0][2]["history"]
    lookup.assert_awaited_once_with("shared-device-session", "QHJ01", user_id=88)
    upsert.assert_awaited_once()
    assert (
        upsert.await_args.kwargs["parent_query"]
        == "上个月营收怎么样 那为什么呢"
    )


@pytest.mark.asyncio
async def test_endpoint_clarification_shape(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(clarification_needed=True, clarification_question="您想看哪方面？", intent="")
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    followups = [
        {"label": "本月", "question": "本月"},
        {"label": "上个月", "question": "上个月"},
    ]
    tiered_result = {
        "kind": "clarification",
        "answer_text": "您想看哪方面？",
        "suggested_followups": followups,
        "spec": spec,
    }
    tiered_mock = AsyncMock(return_value=tiered_result)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        tiered_mock,
    )

    # query 必须带确定性利润/时间窗信号 (2026-07-08 audit fix C-2 端点前置滤:
    # 无信号问句直接 delegate:false 不烧 T2/T3) —— "赚钱情况怎么样" 带利润词
    # 但仍模糊, 走 mock 的 clarification 路径。
    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="赚钱情况怎么样")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {
        "delegate": True,
        "kind": "clarification",
        "answer_text": "您想看哪方面？",
        "suggested_followups": followups,
    }
    assert tiered_mock.await_args.kwargs["precomputed_spec"] is spec


@pytest.mark.asyncio
async def test_llm_clarification_cannot_be_bypassed_by_compound_split(monkeypatch):
    spec = _spec(
        intent="",
        clarification_needed=True,
        clarification_question="这次想分析哪些门店？",
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )

    import smartbi.gold.restaurant_agent as restaurant_agent

    monkeypatch.setattr(
        restaurant_agent,
        "is_compound_question",
        lambda _query: pytest.fail(
            "compound splitting must not bypass the LLM clarification"
        ),
    )

    result = await tiered_answer(
        "看看营收，另外告诉我怎么提高",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )

    assert result["kind"] == "clarification"
    assert result["answer_text"] == "这次想分析哪些门店？"


@pytest.mark.asyncio
async def test_endpoint_marks_consumed_pending_clarification_continuation(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "DEMO_REST")
    monkeypatch.setattr(
        gold_reads_mod,
        "get_pg_pool",
        AsyncMock(return_value=object()),
    )
    from smartbi.services.chat_session_service import ChatSessionService

    monkeypatch.setattr(ChatSessionService, "lookup", AsyncMock(return_value=None))
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        clarification_needed=True,
        clarification_question="你想看哪个时间范围？",
        is_clarification_continuation=True,
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value={
            "kind": "clarification",
            "answer_text": "你想看哪个时间范围？",
            "spec": spec,
        }),
    )

    result = await post_restaurant_tiered_answer(
        _fake_request("restaurant_manager", user_id="88"),
        TieredIntentAnswerRequest(
            factory_id="DEMO_REST",
            query="怎么优化它",
            java_tool_name="orchestrator_null_intent",
            session_id="named-dish-pending-time",
        ),
    )

    assert result == {
        "delegate": True,
        "kind": "clarification",
        "answer_text": "你想看哪个时间范围？",
        "clarification_continuation": True,
    }


@pytest.mark.asyncio
async def test_endpoint_signal_free_query_reaches_semantic_planner(monkeypatch):
    """Keyword absence must never bypass the restaurant semantic planner."""
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    parse_mock = AsyncMock(return_value=None)
    monkeypatch.setattr("smartbi.gold.restaurant_intent.parse_restaurant_query", parse_mock)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value=None),
    )

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="哪个菜卖得好")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}
    assert parse_mock.await_count == 1


@pytest.mark.asyncio
async def test_endpoint_forwards_role_and_java_tool_name_to_tiered_answer(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(intent="RESTAURANT_OPS_STORE_MARGIN", wants_margin=True)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )

    captured: dict = {}

    async def _fake_tiered_answer(
        query,
        pool,
        factory_id,
        role,
        *,
        java_tool_name=None,
        precomputed_spec=None,
    ):
        captured["role"] = role
        captured["java_tool_name"] = java_tool_name
        captured["precomputed_spec"] = precomputed_spec
        return {
            "kind": "answer", "answer_text": "毛利 12%", "charts": [], "kpis": [],
            "title": "t", "code": spec.intent, "contract_pass": True, "spec": spec,
        }

    monkeypatch.setattr("smartbi.gold.restaurant_intent_service.tiered_answer", _fake_tiered_answer)

    body = TieredIntentAnswerRequest(
        factory_id="QHJ01", query="哪家店最赚钱", java_tool_name="restaurant_store_revenue_rank_gold",
    )
    await post_restaurant_tiered_answer(_fake_request(role="finance_manager"), body)
    assert captured["role"] == "finance_manager"
    assert captured["java_tool_name"] == "restaurant_store_revenue_rank_gold"
    assert captured["precomputed_spec"] is spec


@pytest.mark.asyncio
async def test_endpoint_resolver_miss_falls_back_to_delegate_false(monkeypatch):
    """should_delegate said yes but tiered_answer produced nothing (resolver
    miss) -- must NOT surface an empty/broken answer, fall through instead."""
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(asks_profitability=True, wants_margin=True)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value=None),
    )

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="这两个月挣钱没")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}


@pytest.mark.asyncio
async def test_endpoint_fail_open_on_internal_exception(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))

    async def _boom(*a, **kw):
        raise RuntimeError("boom")

    monkeypatch.setattr("smartbi.gold.restaurant_intent.parse_restaurant_query", _boom)

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="随便问问")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}


@pytest.mark.asyncio
async def test_endpoint_tenant_mismatch_fails_open(monkeypatch):
    """A body factory_id that doesn't match the internal-secret-derived
    tenant is a caller bug, not a security bypass to surface as a 403 --
    the endpoint's fail-open contract swallows it same as any other
    exception (design doc: Python side "never raises")."""
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")

    body = TieredIntentAnswerRequest(factory_id="OTHER_FACTORY", query="营收趋势")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}


@pytest.mark.asyncio
async def test_endpoint_empty_query_delegate_false(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="   ")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}


@pytest.mark.asyncio
async def test_endpoint_no_pool_delegate_false(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=None))

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="营收趋势")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": False}


# ─── R7b: store-mention forwarding, demo mapping, guard clarification ────


@pytest.mark.asyncio
async def test_tiered_answer_store_mention_maps_demo_and_declines_unknown(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        resolver_query_seed="把最近7天销量最低的5道菜全部下架 月球一号幻想店",
    )
    captured = {}

    async def _fake_parse(*a, **kw):
        return spec

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        captured["code"] = code
        captured["factory_id"] = factory_id
        captured["kwargs"] = kwargs
        return OpsAnswer(
            code=code, title="月球一号幻想店毛利分析",
            answer_text="没有找到名为「月球一号幻想店」的门店，不能计算该店的毛利或毛利率。",
            charts=[], kpis=[], meta={"store_not_found": "月球一号幻想店"},
        )

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)

    result = await tiered_answer(
        "月球一号幻想店的毛利率是多少？", object(), "DEMO_REST", "restaurant_manager",
    )
    assert captured["factory_id"] == "RES_3101_009"
    assert captured["kwargs"]["store_mention"] == "月球一号幻想店"
    assert result["kind"] == "clarification"
    assert "没有找到名为「月球一号幻想店」的门店" in result["answer_text"]
    assert "当前未执行任何下架" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING


@pytest.mark.asyncio
async def test_demo_dish_fallback_does_not_replace_truth_with_missing_store(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        resolver_query_seed="米饭的销量是多少 本月 兄弟土菜馆",
        requested_metrics=("sales_volume",),
        dimensions=("dish", "store"),
        dish_slot="米饭",
        store_scope="single",
        store_slots=("兄弟土菜馆",),
    )
    calls = []

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        calls.append(factory_id)
        if factory_id == "RES_3101_009":
            return OpsAnswer(
                code=code,
                title="米饭门店销量",
                answer_text=(
                    "本月没有找到「米饭」的门店销售记录，"
                    "没有用其他菜品或榜单替代。"
                ),
                charts=[],
                kpis=[],
                meta={"dish_not_found": "米饭"},
            )
        return OpsAnswer(
            code=code,
            title="兄弟土菜馆毛利分析",
            answer_text=(
                "没有找到名为「兄弟土菜馆」的门店，"
                "不能计算该店的毛利或毛利率。"
            ),
            charts=[],
            kpis=[],
            meta={"store_not_found": "兄弟土菜馆"},
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)

    result = await tiered_answer(
        "兄弟土菜馆",
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert calls == ["RES_3101_009", "DEMO_REST"]
    assert result["kind"] == "clarification"
    assert "米饭" in result["answer_text"]
    assert "门店销售记录" in result["answer_text"]
    assert "毛利或毛利率" not in result["answer_text"]


@pytest.mark.asyncio
async def test_tiered_answer_missing_reference_guard_is_clarification(monkeypatch):
    spec = _spec(intent="RESTAURANT_OPS_GROSS_MARGIN")

    async def _fake_parse(*a, **kw):
        return spec

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        return OpsAnswer(
            code=code, title="需要先确认比较日期",
            answer_text="这轮对话里没有找到可沿用的比较日期。",
            charts=[], kpis=[], meta={"missing_reference": "date_range"},
        )

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)

    result = await tiered_answer(
        "那毛利呢？请沿用刚才比较的两个日期。", object(), "DEMO_REST", None,
    )
    assert result["kind"] == "clarification"
    assert "没有找到可沿用的比较日期" in result["answer_text"]


@pytest.mark.asyncio
async def test_read_only_action_warning_survives_store_scope_clarification(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        clarification_needed=True,
        clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
        store_options=("人民路店", "湖滨路店"),
    )

    async def _fake_parse(*_args, **_kwargs):
        return spec

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)

    result = await tiered_answer(
        "把本月销量最差的菜直接下架",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )

    assert result["kind"] == "clarification"
    assert STORE_SCOPE_CLARIFICATION_QUESTION in result["answer_text"]
    assert "当前未执行任何下架" in result["answer_text"]
    assert "咨询模式" in result["warning"]
    assert "当前未执行任何下架" in result["warning"]


@pytest.mark.asyncio
async def test_read_only_action_warning_survives_pure_store_scope_reply(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        clarification_needed=True,
        clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
        store_options=("人民路店", "湖滨路店"),
        resolver_query_seed="把最近7天销量最低的5道菜全部下架 全部门店",
    )

    async def _fake_parse(*_args, **_kwargs):
        return spec

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)

    result = await tiered_answer(
        "全部门店",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )

    assert result["kind"] == "clarification"
    assert STORE_SCOPE_CLARIFICATION_QUESTION in result["answer_text"]
    assert "当前未执行任何下架" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING


@pytest.mark.asyncio
async def test_read_only_action_warning_survives_resolved_store_scope_answer(
    monkeypatch,
):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        resolver_query_seed="把最近7天销量最低的5道菜全部下架 全部门店",
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="低销量菜品",
            answer_text="最近7天销量最低的5道菜已经列出。",
            charts=[],
            kpis=[],
            meta={},
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "全部门店",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert "当前未执行任何下架" in result["answer_text"]
    assert "最近7天销量最低的5道菜已经列出" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING


@pytest.mark.asyncio
async def test_explicit_read_choice_answer_keeps_non_execution_notice(
    monkeypatch,
):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        planner_authority="explicit_action_read_choice",
        resolver_query_seed="最近7天全部门店销量最低的5道菜",
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="低销量菜品",
            answer_text="最近7天销量最低的5道菜已经列出。",
            charts=[],
            kpis=[],
            meta={},
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "只看低销量排行",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )
    await asyncio.sleep(0)

    assert result["kind"] == "answer"
    assert "当前未执行任何下架" in result["answer_text"]
    assert "最近7天销量最低的5道菜已经列出" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING


@pytest.mark.asyncio
async def test_tiered_answer_sales_summary_reads_demo_gold_ops_stays_trusted(monkeypatch):
    """R8 contract: revenue/store/trend answers for the demo tenant read the
    seeded gold tenant (consistent store universe with the Java rank tools);
    ops KPIs keep the trusted tenant's own seed."""
    captured = {}

    async def _fake_resolve(code, pool, factory_id, **kwargs):
        captured[code] = factory_id
        return OpsAnswer(
            code=code, title="t",
            answer_text="昨天总营收 ¥73,365.44，共 537 单。",
            charts=[], kpis=[], meta={},
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _fake_resolve)

    async def _parse_sales(*a, **kw):
        return _spec(intent="RESTAURANT_OPS_SALES_SUMMARY")

    monkeypatch.setattr(svc, "parse_restaurant_query", _parse_sales)
    result = await tiered_answer("昨天营业额比前天高还是低？", object(), "DEMO_REST", None)
    assert captured["RESTAURANT_OPS_SALES_SUMMARY"] == "RES_3101_009"
    assert result["kind"] == "answer"

    async def _parse_wastage(*a, **kw):
        return _spec(intent="RESTAURANT_OPS_WASTAGE_TOP")

    monkeypatch.setattr(svc, "parse_restaurant_query", _parse_wastage)
    await tiered_answer("损耗最多的食材", object(), "DEMO_REST", None)
    assert captured["RESTAURANT_OPS_WASTAGE_TOP"] == "DEMO_REST"


@pytest.mark.asyncio
async def test_tiered_answer_store_scope_mismatch_fails_closed_without_reroute(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        planned_intents=("RESTAURANT_OPS_GROSS_MARGIN",),
        plan_version="restaurant-query-plan-v2",
        planner_authority="llm",
        plan_hash="plan-store-mismatch",
        resolver_query_seed="把最近7天销量最低的5道菜全部下架 只看食材领用量排行 全部门店",
    )
    resolver = AsyncMock()

    async def _fake_parse(*a, **kw):
        return spec

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", resolver)

    result = await tiered_answer(
        "鲜行者打浦桥日月光店的毛利率是多少？", object(), "DEMO_REST", "restaurant_manager",
    )
    assert result["kind"] == "clarification"
    assert result["contract_pass"] is False
    assert "门店范围" in result["answer_text"]
    assert "当前未执行任何下架" in result["answer_text"]
    assert result["warning"] == svc._READ_ONLY_ACTION_WARNING
    resolver.assert_not_awaited()


@pytest.mark.asyncio
async def test_tiered_dish_scope_mismatch_fails_closed_without_reroute(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",),
        plan_version="restaurant-query-plan-v2",
        planner_authority="llm",
        plan_hash="plan-dish-mismatch",
    )
    resolver = AsyncMock()

    async def _fake_parse(*a, **kw):
        return spec

    monkeypatch.setattr(svc, "parse_restaurant_query", _fake_parse)
    monkeypatch.setattr(svc, "_resolve_tiered", resolver)
    result = await tiered_answer("米饭的销量是多少", object(), "DEMO_REST", "restaurant_manager")
    assert result["kind"] == "clarification"
    assert result["contract_pass"] is False
    assert "菜品范围" in result["answer_text"]
    resolver.assert_not_awaited()


@pytest.mark.asyncio
async def test_tiered_answer_returns_typed_focus_entity_and_followups(monkeypatch):
    spec = _spec(
        intent="RESTAURANT_OPS_GROSS_MARGIN",
        dimensions=("dish",),
        requested_metrics=("sales_volume",),
        planned_intents=("RESTAURANT_OPS_GROSS_MARGIN",),
        window_label="最近30天",
        relative_window=True,
        plan_version="restaurant-query-plan-v2",
        planner_authority="llm_contract_repair",
        plan_hash="dish-ranking-plan",
    )
    monkeypatch.setattr(svc, "parse_restaurant_query", AsyncMock(return_value=spec))
    monkeypatch.setattr(
        svc,
        "_resolve_tiered",
        AsyncMock(return_value=OpsAnswer(
            code=spec.intent,
            title="菜品销量排行",
            answer_text="1. 招牌藤椒味（单人份）— 销量 120 份",
            charts=[],
            kpis=[],
            meta={
                "ranked_entities": [{
                    "type": "dish",
                    "id": "dish-42",
                    "name": "招牌藤椒味（单人份）",
                    "rank": 1,
                }],
                "focus_entity": {
                    "type": "dish",
                    "id": "dish-42",
                    "name": "招牌藤椒味（单人份）",
                    "rank": 1,
                },
                "dish_ranking": "best",
            },
        )),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        "哪个菜卖得好",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )

    assert result["kind"] == "answer"
    assert result["executed_resolvers"] == ["RESTAURANT_OPS_GROSS_MARGIN"]
    assert result["structured_context"]["focus_entity"]["id"] == "dish-42"
    assert result["structured_context"]["topic_kind"] == "dish_ranking"
    assert result["suggested_followups"] == [
        {"label": "看本月", "question": "本月哪个菜卖得最好？"},
        {"label": "看上个月", "question": "上个月哪个菜卖得最好？"},
    ]


@pytest.mark.asyncio
async def test_tiered_answer_keeps_sales_objective_when_llm_margin_slot_conflicts(
    monkeypatch,
):
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
    captured = {}

    async def _resolve(code, pool, factory_id, **kwargs):
        captured.update(kwargs)
        return OpsAnswer(
            code=code,
            title="菜品销量优化建议",
            answer_text="优化目标：提升菜品销量。验证指标：销量与订单数。",
            charts=[],
            kpis=[],
            meta={
                "focus_entity": {
                    "type": "dish",
                    "id": "dish-42",
                    "name": "招牌藤椒味(单人份)",
                },
                "scope_matches_request": True,
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)
    monkeypatch.setattr(
        svc._contract,
        "validate",
        lambda *args, **kwargs: SimpleNamespace(passed=True, missing=[]),
    )
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=1))

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "answer"
    assert captured["query"] == query
    assert "毛利" not in captured["query"]


def test_named_entity_followups_use_server_restored_context():
    followups = svc._suggested_followups({
        "focus_entity": {"type": "dish", "name": "米饭"},
        "window_label": "上个月",
        "requested_metrics": ["gross_margin"],
        "topic_kind": None,
    })

    assert followups == [
        {
            "label": "看菜品销量",
            "question": "这个菜的销量呢？",
        },
        {
            "label": "看菜品成本",
            "question": "这个菜的成本呢？",
        },
    ]


def test_r24_vector_tier_threshold_gate():
    high = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS",
                 source_tier="vector", confidence=0.9)
    low = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS",
                source_tier="vector", confidence=0.7)
    assert should_delegate(high) is True
    assert should_delegate(low) is False


def test_store_scope_clarification_returns_buttons_with_real_store_names():
    spec = _spec(
        clarification_needed=True,
        clarification_question=STORE_SCOPE_CLARIFICATION_QUESTION,
        store_options=("东城店", "西城店", "南城店", "北城店"),
    )

    assert svc._clarification_followups(spec) == [
        {"label": "全部门店", "question": "全部门店"},
        {"label": "东城店", "question": "东城店"},
        {"label": "西城店", "question": "西城店"},
        {"label": "南城店", "question": "南城店"},
    ]


@pytest.mark.asyncio
async def test_store_dish_ranking_no_data_is_shown_as_non_blocking_clarification(
    monkeypatch,
):
    query = "哪个菜卖得好 最近7天 无数据店"
    spec = _build_spec(
        "RESTAURANT_OPS_SALES_SUMMARY",
        query,
        confidence=0.95,
        tier="llm",
    )
    no_data_text = (
        "指定的无数据店在最近7天没有可用的菜品销售记录，"
        "因此不能生成销量最高榜。请选择有销售数据的门店，或调整时间范围后重试。"
    )

    async def _resolve(*_args, **_kwargs):
        return OpsAnswer(
            code="RESTAURANT_OPS_STORE_MARGIN",
            title="无数据店菜品销量排行",
            answer_text=no_data_text,
            charts=[],
            kpis=[],
            meta={
                "no_pos_data": True,
                "dish_ranking": "best",
                "ranking_limit": 5,
                "scope_matches_request": True,
            },
        )

    monkeypatch.setattr(svc, "_resolve_tiered", _resolve)

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "clarification"
    assert result["answer_text"] == no_data_text
    assert "没有可靠覆盖" not in result["answer_text"]


@pytest.mark.asyncio
async def test_revenue_improvement_executes_grounded_optimization_not_sales_only(monkeypatch):
    spec = _build_spec(
        "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        "这周全部门店营收怎么提高",
        confidence=0.99,
        tier="llm",
        planner_authority="llm",
        time_phrase="本周",
        llm_requested_metrics=("revenue",),
        llm_dimensions=(),
        llm_analysis_action="optimize",
        llm_store_scope="all",
        require_explicit_time=True,
    )
    monkeypatch.setattr(
        svc,
        "parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    optimization = AsyncMock(return_value=OpsAnswer(
        code="RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        title="经营诊断与提升方案",
        answer_text=(
            "本周营收为 ¥100,000。优化建议：先核对低表现门店和低毛利菜品；"
            "验证指标是下周营收、订单和毛利率。"
        ),
        charts=[],
        kpis=[],
        meta={"scope_matches_request": True},
    ))
    monkeypatch.setattr(svc, "_resolve_business_optimization", optimization)
    neighbouring = AsyncMock(side_effect=AssertionError("must not degrade to sales-only resolver"))
    monkeypatch.setattr(svc, "_resolve_tiered", neighbouring)
    monkeypatch.setattr(svc, "log_intent_capture", AsyncMock(return_value=None))

    result = await tiered_answer(
        "这周全部门店营收怎么提高",
        object(),
        "DEMO_REST",
        "restaurant_manager",
    )

    assert result["kind"] == "answer"
    assert result["code"] == "RESTAURANT_OPS_BUSINESS_OPTIMIZATION"
    assert "优化建议" in result["answer_text"]
    assert result["executed_resolvers"] == ["RESTAURANT_OPS_BUSINESS_OPTIMIZATION"]
    optimization.assert_awaited_once()
    neighbouring.assert_not_awaited()


@pytest.mark.asyncio
async def test_current_week_optimization_without_data_offers_usable_period_buttons(
    monkeypatch,
):
    import smartbi.agent.synthesis_engine as synthesis_mod

    response = SimpleNamespace(
        answer="系统当前能查到的数据还不够，请先补齐数据。",
        source="deterministic",
        plan={"intent": "business_optimization"},
        fact_check=None,
        dimension_coverage={
            "data_mode": "DEMO",
            "available_dimensions": [],
            "missing_dimensions": ["finance"],
        },
        charts=[],
    )
    engine = SimpleNamespace(synthesize=AsyncMock(return_value=response))
    monkeypatch.setattr(
        synthesis_mod,
        "ComprehensiveSynthesisEngine",
        lambda _pool: engine,
    )
    spec = _spec(
        intent="RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        date_range=(date(2026, 7, 27), date(2026, 7, 27)),
    )

    answer = await svc._resolve_business_optimization(
        object(),
        "DEMO_REST",
        "这周全部门店营收怎么提高，给我今天能做的动作",
        spec,
        [],
    )

    assert "本周截至今天还没有可用的经营数据" in answer.answer_text
    assert "请先把缺少的数据补齐" not in answer.answer_text
    assert answer.meta["no_pos_data"] is True
    assert answer.meta["period_data_unavailable"] is True
    assert [item["label"] for item in answer.meta["suggested_followups"]] == [
        "最近7天",
        "上周",
        "最近30天",
    ]


@pytest.mark.asyncio
async def test_period_unavailable_guard_forwards_actionable_followups(monkeypatch):
    query = "这周全部门店营收怎么提高，给我今天能做的动作"
    spec = _build_spec(
        "RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        query,
        confidence=0.99,
        tier="llm",
        planner_authority="llm",
        time_phrase="本周",
        llm_requested_metrics=("revenue",),
        llm_analysis_action="optimize",
        llm_store_scope="all",
        require_explicit_time=True,
    )
    followups = [
        {
            "label": "最近7天",
            "question": "最近7天全部门店营收怎么提高，给我今天能做的动作",
        },
        {
            "label": "上周",
            "question": "上周全部门店营收怎么提高，给我今天能做的动作",
        },
    ]
    monkeypatch.setattr(
        svc,
        "_resolve_business_optimization",
        AsyncMock(return_value=OpsAnswer(
            code="RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
            title="经营诊断与提升方案",
            answer_text="本周截至今天还没有可用的经营数据。",
            charts=[],
            kpis=[],
            meta={
                "scope_matches_request": True,
                "no_pos_data": True,
                "period_data_unavailable": True,
                "suggested_followups": followups,
            },
        )),
    )

    result = await tiered_answer(
        query,
        object(),
        "DEMO_REST",
        "restaurant_manager",
        precomputed_spec=spec,
    )

    assert result["kind"] == "clarification"
    assert result["suggested_followups"] == followups
