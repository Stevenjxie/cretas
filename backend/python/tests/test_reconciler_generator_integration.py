"""阶段2 — generator + reconciler 集成: metrics 事实表纠正 LLM misquote (端到端 mock LLM)。"""
from __future__ import annotations

import pytest

from smartbi.services.insights.generator import InsightGenerator


@pytest.mark.asyncio
async def test_generator_reconciles_llm_misquote_against_metrics(monkeypatch):
    gen = InsightGenerator()
    monkeypatch.setattr(gen.settings, "llm_api_key", "test-key", raising=False)

    async def fake_call_llm(prompt, system_role=None, **kw):
        # LLM misquote: 报毛利率38%, 但确定值是35%
        return '{"insights":[{"type":"kpi","text":"毛利率38%，高于行业","importance":7}]}'

    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", fake_call_llm)

    data = [{"月份": "1月", "毛利率": 35.0}, {"月份": "2月", "毛利率": 34.8}]
    metrics = [{"name": "毛利率", "value": 35, "unit": "%", "success": True}]
    result = await gen.generate_insights(data, metrics=metrics)

    texts = " ".join(str(i.get("text", "")) for i in result.get("insights", []))
    assert "毛利率35%" in texts   # 回填确定值
    assert "38%" not in texts     # LLM 错值被纠正


@pytest.mark.asyncio
async def test_generator_noop_reconcile_without_metrics(monkeypatch):
    gen = InsightGenerator()
    monkeypatch.setattr(gen.settings, "llm_api_key", "test-key", raising=False)

    async def fake_call_llm(prompt, system_role=None, **kw):
        return '{"insights":[{"type":"kpi","text":"毛利率38%，高于行业","importance":7}]}'

    monkeypatch.setattr("smartbi.services.insights.llm_client.call_llm", fake_call_llm)

    data = [{"月份": "1月", "毛利率": 35.0}]
    result = await gen.generate_insights(data)  # 无 metrics → reconcile no-op
    texts = " ".join(str(i.get("text", "")) for i in result.get("insights", []))
    assert "38%" in texts  # 不动


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
