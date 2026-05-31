"""rules-first 字段映射: 规则引擎准确(高置信)时跳过 LLM, 不确定才 fallback。"""
from __future__ import annotations

import json

import pytest

from smartbi.services.llm_mapper import LLMMapper


def _mapper(monkeypatch):
    m = LLMMapper()
    monkeypatch.setattr(m.settings, "llm_api_key", "test-key", raising=False)
    return m


def _field(name, semantic="amount"):
    return {"fieldName": name, "dataType": "number", "semanticType": semantic,
            "sampleValues": [1, 2, 3]}


@pytest.mark.asyncio
async def test_all_exact_alias_skips_llm(monkeypatch):
    m = _mapper(monkeypatch)
    called = {"llm": False}

    async def _no_llm(prompt):
        called["llm"] = True
        raise AssertionError("LLM should NOT be called when all fields are exact-alias")

    monkeypatch.setattr(m, "_call_llm", _no_llm)
    # 实收金额→revenue, 销量→quantity_sold 都是 exact alias (0.85)
    res = await m.map_fields([_field("实收金额"), _field("销量", "quantity")])
    assert called["llm"] is False
    assert res["method"] == "rules"
    targets = {x["sourceField"]: x["targetField"] for x in res["mappings"]}
    assert targets["实收金额"] == "revenue"
    assert targets["销量"] == "quantity_sold"


@pytest.mark.asyncio
async def test_uncertain_fields_go_to_llm_only(monkeypatch):
    m = _mapper(monkeypatch)
    seen = {"fields": None}

    def _capture_prompt(fields, context=None):
        seen["fields"] = [f["fieldName"] for f in fields]
        return "PROMPT"

    async def _fake_llm(prompt):
        return json.dumps({
            "mappings": [{"sourceField": "神秘列X", "targetField": "category",
                          "targetCategory": "category_dimensions", "confidence": 0.9, "reason": "llm"}],
            "unmapped": [],
        })

    monkeypatch.setattr(m, "_build_mapping_prompt", _capture_prompt)
    monkeypatch.setattr(m, "_call_llm", _fake_llm)

    res = await m.map_fields([_field("实收金额"), _field("神秘列X", "unknown")])
    # 只有不确定的"神秘列X"进了 LLM, 实收金额没进
    assert seen["fields"] == ["神秘列X"]
    assert res["method"] == "rules+llm"
    targets = {x["sourceField"]: x["targetField"] for x in res["mappings"]}
    assert targets["实收金额"] == "revenue"   # 规则保留
    assert targets["神秘列X"] == "category"   # LLM 补


@pytest.mark.asyncio
async def test_llm_error_falls_back_to_full_rules(monkeypatch):
    m = _mapper(monkeypatch)

    async def _boom(prompt):
        raise RuntimeError("LLM down")

    monkeypatch.setattr(m, "_call_llm", _boom)
    res = await m.map_fields([_field("实收金额"), _field("神秘列X", "unknown")])
    # LLM 挂 → 全量规则兜底, 不抛错
    assert res["method"] == "rule_based"
    assert any(x["sourceField"] == "实收金额" for x in res["mappings"])


@pytest.mark.asyncio
async def test_no_api_key_uses_rules_only(monkeypatch):
    m = LLMMapper()
    monkeypatch.setattr(m.settings, "llm_api_key", "", raising=False)
    res = await m.map_fields([_field("实收金额")])
    assert res["method"] == "rule_based"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
