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


def _fake_request(role=None):
    return SimpleNamespace(state=SimpleNamespace(role=role))


# ─── 1. should_delegate decision matrix (design doc section 3) ────────────

def test_should_delegate_none_spec_false():
    """Rule 1: spec is None (T1/T2/T3 all missed / non-restaurant tenant /
    upstream exception) -> False, Java keeps its own flow."""
    assert should_delegate(None) is False


def test_should_delegate_clarification_needed_true():
    """Rule 2: clarification_needed -> True (Java can't ask a clarifying
    question itself)."""
    spec = _spec(clarification_needed=True, clarification_question="您想看哪方面？", intent="")
    assert should_delegate(spec) is True


def test_should_delegate_asks_profitability_true():
    """Rule 3: asks_profitability -> True (Java Gold Tool family never
    produces a profit verdict)."""
    spec = _spec(asks_profitability=True, wants_margin=True)
    assert should_delegate(spec) is True


def test_should_delegate_wants_margin_without_profitability_true():
    """Rule 3 also fires on wants_margin alone (not just asks_profitability)."""
    spec = _spec(wants_margin=True, asks_profitability=False)
    assert should_delegate(spec) is True


def test_should_delegate_sales_summary_relative_window_true():
    """Rule 4: SALES_SUMMARY + relative_window -> True ("最近N天/周/月" ops
    summary windows are only honored by the Python resolver)."""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        relative_window=True,
        window_label="最近2个月",
    )
    assert should_delegate(spec) is True


def test_should_delegate_sales_summary_absolute_window_false():
    """Rule 4 requires relative_window=True -- an absolute-month
    SALES_SUMMARY query with no margin/profit ask falls to rule 5 -> False."""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        relative_window=False,
        window_label="2025年12月",
    )
    assert should_delegate(spec) is False


def test_should_delegate_pure_trend_query_false():
    """Rule 5: pure trend query (no margin/profit ask, not a relative-window
    SALES_SUMMARY) -> False, Java's existing answer stays byte-for-byte
    unchanged."""
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS", relative_window=False)
    assert should_delegate(spec) is False


def test_should_delegate_ranking_query_false():
    """Rule 5: a plain ranking query (STORE_MARGIN intent but no explicit
    margin/profit ask flagged, non-relative) -> False."""
    spec = _spec(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        wants_margin=False,
        asks_profitability=False,
        relative_window=False,
    )
    assert should_delegate(spec) is False


def test_should_delegate_relative_window_wrong_intent_false():
    """Rule 4 is intent-scoped: relative_window=True on a NON-SALES_SUMMARY
    intent does not trigger rule 4, and (absent margin/profit) falls to
    rule 5 -> False."""
    spec = _spec(
        intent="RESTAURANT_OPS_TREND_ANALYSIS",
        relative_window=True,
        window_label="最近3个月",
    )
    assert should_delegate(spec) is False


def test_should_delegate_absolute_month_recipe_cost_false():
    """Rule 5: absolute-month dish-cost ranking -> False (Java answers this
    class of query well today)."""
    spec = _spec(
        intent="RESTAURANT_OPS_RECIPE_COST",
        window_label="2025年12月",
        relative_window=False,
    )
    assert should_delegate(spec) is False


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
    spec = _spec(asks_profitability=True)
    assert should_delegate(spec, java_tool_name) is True

    spec2 = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS", relative_window=False)
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
            charts=[], kpis=[], meta={},
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


# ─── 3. POST /api/smartbi/gold/restaurant/tiered-answer endpoint ──────────

@pytest.mark.asyncio
async def test_endpoint_delegate_false_when_should_delegate_false(monkeypatch):
    monkeypatch.setattr(gold_reads_mod, "get_factory_id", lambda: "QHJ01")
    monkeypatch.setattr(gold_reads_mod, "get_pg_pool", AsyncMock(return_value=object()))
    spec = _spec(intent="RESTAURANT_OPS_TREND_ANALYSIS", relative_window=False)
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
    }


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

    body = TieredIntentAnswerRequest(factory_id="QHJ01", query="情况怎么样")
    result = await post_restaurant_tiered_answer(_fake_request(), body)
    assert result == {"delegate": True, "kind": "clarification", "answer_text": "您想看哪方面？"}


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
