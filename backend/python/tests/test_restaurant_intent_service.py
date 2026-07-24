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
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

import smartbi.api.gold_reads as gold_reads_mod
import smartbi.gold.restaurant_intent_service as svc
from smartbi.api.gold_reads import TieredIntentAnswerRequest, post_restaurant_tiered_answer
from smartbi.gold.restaurant_intent import RestaurantQuerySpec
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
    assert result == {"kind": "clarification", "answer_text": "您想看哪方面？", "spec": spec}


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
async def test_endpoint_dependent_followup_uses_trusted_context_and_session_key(monkeypatch):
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
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS", relative_window=False)

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
    assert parse_calls[0][0] == "上个月营收为什么是这样"
    assert "继续追问" not in parse_calls[0][0]
    session_key = parse_calls[0][2]["session_key"]
    assert session_key.startswith("trusted-v1:")
    assert "shared-device-session" not in session_key
    assert tiered_calls[0][4]["session_key"] == session_key
    assert tiered_calls[0][4]["precomputed_spec"] is spec
    lookup.assert_awaited_once_with("shared-device-session", "QHJ01", user_id=88)
    upsert.assert_awaited_once()


@pytest.mark.asyncio
async def test_endpoint_clarification_shape(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(clarification_needed=True, clarification_question="您想看哪方面？", intent="")
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent.parse_restaurant_query",
        AsyncMock(return_value=spec),
    )
    tiered_result = {"kind": "clarification", "answer_text": "您想看哪方面？", "spec": spec}
    monkeypatch.setattr(
        "smartbi.gold.restaurant_intent_service.tiered_answer",
        AsyncMock(return_value=tiered_result),
    )

    # query 必须带确定性利润/时间窗信号 (2026-07-08 audit fix C-2 端点前置滤:
    # 无信号问句直接 delegate:false 不烧 T2/T3) —— "赚钱情况怎么样" 带利润词
    # 但仍模糊, 走 mock 的 clarification 路径。
    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="赚钱情况怎么样")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": True, "kind": "clarification", "answer_text": "您想看哪方面？"}


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

    async def _fake_tiered_answer(query, pool, factory_id, role, *, java_tool_name=None):
        captured["role"] = role
        captured["java_tool_name"] = java_tool_name
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
    spec = _spec(intent="RESTAURANT_OPS_STORE_MARGIN")
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
        planner_authority="llm",
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


def test_named_entity_followups_are_self_contained_and_do_not_repeat_metric():
    followups = svc._suggested_followups({
        "focus_entity": {"type": "dish", "name": "米饭"},
        "window_label": "上个月",
        "requested_metrics": ["gross_margin"],
        "topic_kind": None,
    })

    assert followups == [
        {
            "label": "看菜品销量",
            "question": "上个月「米饭」的销量是多少？",
        },
        {
            "label": "看菜品成本",
            "question": "上个月「米饭」的成本如何？",
        },
    ]


def test_r24_vector_tier_threshold_gate():
    high = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS",
                 source_tier="vector", confidence=0.9)
    low = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS",
                source_tier="vector", confidence=0.7)
    assert should_delegate(high) is True
    assert should_delegate(low) is False
