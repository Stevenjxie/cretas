"""P4a — dish branches in deterministic / embedding / llm_arbitrator agents.

Candidate set for DISH = dim_canonical_dish (NOT dim_product). LLM prompt must carry
the "字面相似 ≠ 同菜" rule + reject hallucinated ids.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.canonical.entity_resolution.agents.deterministic import (
    DeterministicAgent,
)
from smartbi.canonical.entity_resolution.agents.embedding import EmbeddingAgent
from smartbi.canonical.entity_resolution.agents.llm_arbitrator import (
    LLMArbitrator,
)
from smartbi.canonical.entity_resolution.orchestrator import (
    EntityType,
    ResolutionInput,
)


def _make_mock_pool(fetchrow_ret=None, fetch_ret=None):
    conn = AsyncMock()
    conn.fetchrow = AsyncMock(return_value=fetchrow_ret)
    conn.fetch = AsyncMock(return_value=fetch_ret or [])
    pool = MagicMock()
    acquire_ctx = MagicMock()
    acquire_ctx.__aenter__ = AsyncMock(return_value=conn)
    acquire_ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=acquire_ctx)
    return pool, conn


def _dish_input(raw="青花椒味鱼"):
    return ResolutionInput(
        raw_name=raw, entity_type=EntityType.DISH, factory_id="RES_3101_009"
    )


async def test_deterministic_dish_queries_canonical_table():
    """DISH deterministic exact-matches dim_canonical_dish.normalized_key."""
    pool, conn = _make_mock_pool(fetchrow_ret={"canonical_dish_id": 5})
    agent = DeterministicAgent()

    res = await agent.resolve(_dish_input("招牌青花椒鱼(单人份)"), pool)

    assert res.matched_entity_id == 5
    assert res.confidence == 1.0
    sql = conn.fetchrow.await_args_list[0].args[0]
    assert "dim_canonical_dish" in sql
    assert "normalized_key" in sql
    assert "dim_dish" not in sql
    assert "dim_product" not in sql


async def test_deterministic_dish_no_match():
    pool, _ = _make_mock_pool(fetchrow_ret=None)
    agent = DeterministicAgent()
    res = await agent.resolve(_dish_input("全新没见过的菜"), pool)
    assert res.matched_entity_id is None
    assert res.confidence == 0.0


async def test_embedding_dish_candidate_set_is_canonical():
    """DISH embedding pulls candidates from dim_canonical_dish, not dim_product."""
    pool, conn = _make_mock_pool(
        fetch_ret=[{"eid": 9, "name": "招牌青花椒鱼"}]
    )

    async def fake_embed(text):
        # identical fixed vector → cosine 1.0 (ship)
        return [1.0, 0.0, 0.0]

    agent = EmbeddingAgent(embed_fn=fake_embed)
    res = await agent.resolve(_dish_input("青花椒味鱼"), pool)

    sql = conn.fetch.await_args_list[0].args[0]
    assert "dim_canonical_dish" in sql
    assert "canonical_dish_id" in sql
    assert "dim_product" not in sql
    assert res.matched_entity_id == 9


def test_llm_dish_prompt_has_literal_similarity_rule():
    """dish prompt must state 字面相似 ≠ 同菜 + 红烧牛肉/红烧牛腩 example."""
    candidates = [{"entity_id": 1, "name": "招牌青花椒鱼", "sim": 0.88}]
    prompt = LLMArbitrator._build_prompt(
        "青花椒味鱼", "dish", candidates, {"category": "鱼类", "price_range": "60-90"}
    )
    assert "字面相似" in prompt
    assert "同一道菜" in prompt
    assert "红烧牛肉" in prompt and "红烧牛腩" in prompt
    assert "null" in prompt


def test_llm_store_prompt_unchanged():
    """non-dish prompt keeps the generic template (no dish rule leak)."""
    candidates = [{"entity_id": 1, "name": "门店A", "sim": 0.8}]
    prompt = LLMArbitrator._build_prompt(
        "门店甲", "store", candidates, {"city": "上海"}
    )
    assert "字面相似" not in prompt
    assert "实体识别专家" in prompt


@pytest.mark.parametrize("matched_raw", [999, "999", "招牌青花椒鱼"])
async def test_llm_dish_hallucinated_id_rejected(matched_raw):
    """LLM returns an id not in candidates → matched None, confidence 0."""
    pool, _ = _make_mock_pool()
    candidates = [{"entity_id": 1, "name": "招牌青花椒鱼", "sim": 0.88}]

    async def fake_llm(prompt, system_role=None, max_tokens=None):
        import json
        return json.dumps(
            {"matched_id": matched_raw, "confidence": 0.95, "reasoning": "x"}
        )

    agent = LLMArbitrator(llm_fn=fake_llm)
    inp = _dish_input("青花椒味鱼")
    inp.context["top_k_candidates"] = candidates

    res = await agent.resolve(inp, pool)

    assert res.matched_entity_id is None
    assert res.confidence == 0.0
