"""物化内容哈希缓存: prompt 没变(模板KPI一致)→复用上次LLM输出跳过调用; 变了→重新调。"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

import smartbi.services.materialized_analytics.llm_materializer as lm


def _result(code):
    return SimpleNamespace(code=code, applies=True, error=None, llm_insight=None)


def _patch_common(monkeypatch):
    # 固定 prompt 构建, 隔离缓存逻辑
    monkeypatch.setattr(lm, "_summarize_result_for_prompt", lambda r: {"code": r.code})
    monkeypatch.setattr(lm, "_build_prompt", lambda payload, domain: "FIXED_PROMPT")
    # llm_api_key 存在
    monkeypatch.setattr("config.get_settings", lambda: SimpleNamespace(llm_api_key="k"))


@pytest.mark.asyncio
async def test_cache_hit_skips_llm_and_reuses(monkeypatch):
    _patch_common(monkeypatch)
    called = {"llm": False}

    async def _boom(*a, **k):
        called["llm"] = True
        raise AssertionError("LLM 不应被调用 (内容未变, 应命中缓存)")

    # 缓存命中: 返回上次的 LLM 原始输出
    async def _cached(input_hash):
        return '{"insights":{"X":"复用的洞察X"},"executive_summary":"复用摘要"}'

    monkeypatch.setattr(lm, "_get_cached_teacher_output", _cached)
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", _boom)

    r = _result("X")
    summary = await lm.generate_llm_insights([r], "restaurant")
    assert called["llm"] is False
    assert summary == "复用摘要"
    assert r.llm_insight == "复用的洞察X"   # 从缓存解析, 结果一致


@pytest.mark.asyncio
async def test_cache_miss_calls_llm(monkeypatch):
    _patch_common(monkeypatch)
    called = {"llm": False}

    async def _real(*a, **k):
        called["llm"] = True
        return '{"insights":{"X":"新洞察"},"executive_summary":"新摘要"}'

    async def _no_cache(input_hash):
        return None

    monkeypatch.setattr(lm, "_get_cached_teacher_output", _no_cache)
    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", _real)

    # 缓存 miss 时会尝试写 distillation; mock 掉避免 DB
    async def _noop(**k):
        return None

    monkeypatch.setattr(lm, "_persist_distillation_sample", _noop)

    r = _result("X")
    summary = await lm.generate_llm_insights([r], "restaurant")
    assert called["llm"] is True
    assert summary == "新摘要" and r.llm_insight == "新洞察"


@pytest.mark.asyncio
async def test_no_applicable_returns_none(monkeypatch):
    _patch_common(monkeypatch)
    r = SimpleNamespace(code="X", applies=False, error=None, llm_insight=None)
    assert await lm.generate_llm_insights([r], "restaurant") is None


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
