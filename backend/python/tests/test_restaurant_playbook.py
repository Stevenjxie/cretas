"""L1 playbook — routing, content safety, resolver contract."""
from __future__ import annotations

import asyncio

import pytest

import smartbi.gold.restaurant.restaurant_playbook as pb
from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec
from smartbi.gold.restaurant.restaurant_ops_router import (
    match_restaurant_ops,
    reconcile_restaurant_ops_code,
    resolve_by_code,
)


@pytest.mark.parametrize("query,topic", [
    ("毛利率偏低的行业参考做法", "margin"),
    ("食材损耗偏高的行业参考做法", "wastage"),
    ("慢销菜品的行业参考做法", "slow_dish"),
    ("门店业绩差距的行业参考做法", "store_gap"),
    ("出餐慢的行业参考做法", "slow_serving"),
    ("客单价偏低的行业参考做法", "ticket_size"),
    ("差评偏多的行业参考做法", "review"),
    ("盘点差异的行业参考做法", "stocktake"),
    ("排班人效的行业参考做法", "staffing"),
    ("营收下滑的行业参考做法", "revenue_drop"),
])
def test_topic_matching(query, topic):
    assert pb.match_playbook_topic(query) == topic


def test_no_trigger_phrase_means_no_playbook():
    assert pb.match_playbook_topic("毛利率是多少") is None
    assert match_restaurant_ops("整体毛利率是多少") == "RESTAURANT_OPS_GROSS_MARGIN"


def test_trigger_without_topic_returns_menu():
    assert pb.match_playbook_topic("有哪些行业参考做法") == "__menu__"


@pytest.mark.parametrize("query", [
    "毛利率偏低的行业参考做法",
    "慢销菜品的行业参考做法",
    "出餐慢的行业参考做法",
])
def test_playbook_phrase_wins_routing(query):
    assert match_restaurant_ops(query) == pb.PLAYBOOK_CODE


def test_playbook_beats_stale_upstream_hint():
    assert reconcile_restaurant_ops_code(
        "毛利率偏低的行业参考做法", "RESTAURANT_OPS_GROSS_MARGIN",
    ) == pb.PLAYBOOK_CODE


def test_resolver_returns_structured_answer_without_db():
    answer = asyncio.run(resolve_by_code(
        pb.PLAYBOOK_CODE, None, "DEMO_REST",
        role="restaurant_manager", query="食材损耗偏高的行业参考做法",
    ))
    assert answer is not None
    text = answer.answer_text
    for section in ("适用场景", "适用前提", "主要风险", "如何验证有效"):
        assert section in text
    assert "1." in text and "2." in text
    assert answer.meta.get("playbook") == "wastage"
    assert answer.charts == []


def test_resolver_menu_when_topic_unclear():
    answer = asyncio.run(resolve_by_code(
        pb.PLAYBOOK_CODE, None, "DEMO_REST", query="行业参考做法",
    ))
    assert "可以查看以下主题" in answer.answer_text
    assert answer.meta.get("playbook") == "menu"


def test_content_has_no_internal_identifiers_or_fabricated_citations():
    import re
    for slug in pb._PLAYBOOKS:
        text = pb.render_playbook(slug)
        assert text
        assert not re.search(r"[A-Z]{2,}_[A-Z_]+", text)          # intent codes
        assert not re.search(r"\b(tool|SQL|JSON|sessionId)\b", text, re.I)
        for banned in ("研究表明", "专家指出", "据统计", "%的门店"):
            assert banned not in text, f"{slug} 含不可溯源断言: {banned}"


@pytest.mark.asyncio
async def test_tiered_playbook_uses_llm_plan_then_serves_without_db(monkeypatch):
    """LLM-first still executes the curated playbook without touching tenant data."""
    import smartbi.gold.restaurant.restaurant_intent_service as svc

    planner_calls = []

    async def _llm_playbook_plan(query, *args, **kwargs):
        planner_calls.append((query, kwargs.get("semantic_first")))
        return RestaurantQuerySpec(
            intent=pb.PLAYBOOK_CODE,
            domain="restaurant",
            date_range=(None, None),
            window_label="不适用",
            relative_window=False,
            metrics=(),
            wants_margin=False,
            asks_profitability=False,
            dimensions=(),
            comparison=None,
            confidence=0.99,
            source_tier="llm",
            planned_intents=(pb.PLAYBOOK_CODE,),
            plan_version="restaurant-query-plan-v2",
            planner_authority="llm",
            plan_hash="playbook-plan",
            resolver_query_seed=query,
        )

    monkeypatch.setattr(svc, "parse_restaurant_query", _llm_playbook_plan)
    result = await svc.tiered_answer(
        "毛利率偏低的行业参考做法", object(), "DEMO_REST", "restaurant_manager",
    )
    assert planner_calls == [("毛利率偏低的行业参考做法", True)]
    assert result is not None and result["kind"] == "answer"
    assert result["code"] == pb.PLAYBOOK_CODE
    assert result["spec"].planner_authority == "llm"
    assert "菜单工程" in result["answer_text"] or "行业参考做法" in result["answer_text"]
