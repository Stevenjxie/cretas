"""Unit tests for template_rag.hybrid_match.

Tests mock `cosine_topk` so they don't require DashScope or pgvector —
we're testing the SCORING rules, not the vector search itself.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from smartbi.services.template_rag import (
    hybrid_match, HIGH_CONFIDENCE, MIN_USEFUL,
)


@pytest.mark.asyncio
async def test_keyword_and_vector_agree_returns_keyword():
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[("dish_sales_top_n", 0.92, "菜品销量 Top 10")]),
    ):
        r = await hybrid_match(pool, "菜品销量排行", keyword_code="dish_sales_top_n")
    assert r is not None
    assert r.template_code == "dish_sales_top_n"
    assert r.via == "keyword+vector"
    assert r.similarity == 0.92


@pytest.mark.asyncio
async def test_keyword_wins_when_vector_disagrees():
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[("monthly_anomaly", 0.88, "哪个月异常")]),
    ):
        r = await hybrid_match(pool, "菜品销量 Top", keyword_code="dish_sales_top_n")
    assert r is not None
    assert r.template_code == "dish_sales_top_n"
    assert r.via == "keyword_only"


@pytest.mark.asyncio
async def test_vector_only_high_confidence_returns_vector():
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[("staff_performance", 0.91, "服务员排名")]),
    ):
        r = await hybrid_match(pool, "谁是销售冠军", keyword_code=None)
    assert r is not None
    assert r.template_code == "staff_performance"
    assert r.via == "vector_only"
    assert r.similarity == 0.91


@pytest.mark.asyncio
async def test_vector_only_ambiguous_returns_none():
    """MIN_USEFUL ≤ sim < HIGH_CONFIDENCE with no keyword match → None (LLM fallback).

    ⚠️ 用例原本硬编码 0.78 当「模糊区」样本, 因为写的时候 HIGH_CONFIDENCE 是 0.85。
    Apr 26 2026 (v4 B2-A) 按 923 条 prod 模糊未命中日志把阈值降到 **0.78**,
    于是 0.78 变成「命中」而不是「模糊」—— 用例从那天起一直红着, 而 python 套件
    既不在 push 门禁里 (`python-lint-test` 挂 `if: inputs.full_audit`),
    那一步又以 `|| true` 结尾, 所以没人看见。

    改成从常量本身取样本, 阈值再调这条用例也不会假红。
    """
    ambiguous_sim = (MIN_USEFUL + HIGH_CONFIDENCE) / 2
    assert MIN_USEFUL <= ambiguous_sim < HIGH_CONFIDENCE
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[("dish_sales_top_n", ambiguous_sim, "菜品销量")]),
    ):
        r = await hybrid_match(pool, "卖得咋样", keyword_code=None)
    assert r is None


@pytest.mark.asyncio
async def test_vector_only_at_high_confidence_boundary_serves_template():
    """恰好等于 HIGH_CONFIDENCE 要服务模板 —— 边界是闭区间 (`>=`)。

    上面那条用例正是因为「模糊区样本」悄悄变成了边界值才红的, 这里把边界本身钉死。
    """
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[("dish_sales_top_n", HIGH_CONFIDENCE, "菜品销量")]),
    ):
        r = await hybrid_match(pool, "卖得咋样", keyword_code=None)
    assert r is not None
    assert r.via == "vector_only"
    assert r.template_code == "dish_sales_top_n"


@pytest.mark.asyncio
async def test_empty_candidates_with_keyword_falls_back_to_keyword_only():
    """Vector returned nothing (all below MIN_USEFUL) — trust keyword."""
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[]),
    ):
        r = await hybrid_match(pool, "xyz", keyword_code="top_n_by_dim")
    assert r is not None
    assert r.template_code == "top_n_by_dim"
    assert r.via == "keyword_only"


@pytest.mark.asyncio
async def test_empty_candidates_no_keyword_returns_none():
    pool = AsyncMock()
    with patch(
        "smartbi.services.template_rag.cosine_topk",
        new=AsyncMock(return_value=[]),
    ):
        r = await hybrid_match(pool, "totally novel query", keyword_code=None)
    assert r is None


@pytest.mark.asyncio
async def test_constants_sanity():
    """HIGH_CONFIDENCE > MIN_USEFUL (otherwise the logic is inconsistent)."""
    assert HIGH_CONFIDENCE > MIN_USEFUL
    assert 0 < MIN_USEFUL < HIGH_CONFIDENCE <= 1.0
