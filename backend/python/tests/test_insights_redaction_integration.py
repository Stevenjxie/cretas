"""P0 — insights generator 集成: 验证生成期脱敏 scope 激活 + 输出真名还原。

脱敏本身发生在共享客户端包装层 (见 test_llm_redacting_client); 本测验证 generator:
  (1) 进入请求时建立 RedactionScope 并注册了 df 的客户/门店真名 (→ 出境时占位),
  (2) LLM 用占位输出 → generator 把占位还原成真名再返回 (UX 不回归)。
"""
from __future__ import annotations

import pytest

from smartbi.services.insights.generator import InsightGenerator


@pytest.mark.asyncio
async def test_generator_scope_active_and_restores_realnames(monkeypatch):
    gen = InsightGenerator()
    # 强制走 LLM 分支
    monkeypatch.setattr(gen.settings, "llm_api_key", "test-key", raising=False)

    captured = {}

    async def fake_call_llm(prompt, system_role=None, **kw):
        from common.llm_redactor import current_redaction_scope
        scope = current_redaction_scope()
        captured["scope_active"] = scope is not None
        captured["known"] = dict(scope.known_values) if scope else {}
        # 模拟 LLM 看到的是占位 (真名由客户端包装层在出境时替换), 输出引用占位
        ph = scope.known_values.get("青花椒大融城店", "门店A") if scope else "门店A"
        return '{"insights":[{"type":"summary","text":"' + ph + ' 营收最高","importance":8}]}'

    monkeypatch.setattr(
        "smartbi.services.insights.llm_client.call_llm", fake_call_llm,
    )

    data = [
        {"门店名称": "青花椒大融城店", "营业额": 12000},
        {"门店名称": "青花椒春熙店", "营业额": 9000},
    ]
    result = await gen.generate_insights(data)

    # (1) scope 激活 + df 真名注册
    assert captured.get("scope_active") is True
    assert "青花椒大融城店" in captured.get("known", {})

    # (2) 输出还原成真名, 无占位残留
    texts = " ".join(str(i.get("text", "")) for i in result.get("insights", []))
    assert "青花椒大融城店" in texts
    assert "门店A" not in texts


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
