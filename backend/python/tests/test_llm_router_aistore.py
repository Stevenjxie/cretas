"""Shanghai Telecom AI Store integration guards for the shared LLM router."""

from __future__ import annotations

import datetime
import json

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain, call_chain_stream


@pytest.fixture(autouse=True)
def _reset_router_state():
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


def test_aistore_models_are_explicitly_allowlisted_and_expire():
    expiry = datetime.date(2026, 9, 13)
    assert llm_router._SAFE_MODELS[("aistore", "DeepSeek-V4-Flash-A")] == expiry
    assert llm_router._SAFE_MODELS[("aistore", "Qwen3-235B-A22B")] == expiry
    assert llm_router._SAFE_MODELS[("aistore", "Qwen3-32B")] == expiry
    assert llm_router._refuse_reason(
        "aistore", "DeepSeek-V4-Flash-A", expiry,
    ) == "expired"


def test_aistore_key_never_falls_back_to_an_unrelated_secret(monkeypatch):
    monkeypatch.delenv("LLM_AISTORE_API_KEY", raising=False)
    monkeypatch.setenv("LLM_API_KEY", "must-not-be-reused")
    base_url, api_key = llm_router._provider_config("aistore")
    assert base_url == "https://ai.api.coregpu.cn/v1"
    assert api_key == ""


def test_aistore_models_receive_the_measured_thinking_switch():
    for model in ("DeepSeek-V4-Flash-A", "Qwen3-235B-A22B", "Qwen3-32B"):
        out = llm_router._apply_slot_params(
            SLOT.REVIEW,
            "aistore",
            model,
            {"messages": [{"role": "user", "content": "return json"}]},
        )
        assert out["thinking"] == {"type": "disabled"}
        assert "enable_thinking" not in out


def test_qwen32_is_confined_to_the_simple_text_slot():
    pair = ("aistore", "Qwen3-32B")
    assert pair in llm_router._SLOT_POOLS[SLOT.SIMPLE_TEXT]
    for slot, pool in llm_router._SLOT_POOLS.items():
        if slot is SLOT.SIMPLE_TEXT:
            continue
        assert pair not in pool, f"Qwen3-32B leaked into complex slot {slot.value}"


def test_restaurant_slots_prefer_aistore_deepseek_and_confine_qwen235():
    deepseek = ("aistore", "DeepSeek-V4-Flash-A")
    qwen = ("aistore", "Qwen3-235B-A22B")
    for slot in (SLOT.CHAT, SLOT.INSIGHTS, SLOT.CHART, SLOT.MAPPER, SLOT.REVIEW):
        assert llm_router.SLOT_MODELS[slot][0] == deepseek
    chart_chain = llm_router.SLOT_MODELS[SLOT.CHART]
    assert chart_chain.index(qwen) > chart_chain.index(deepseek)
    for slot in (SLOT.CHAT, SLOT.INSIGHTS, SLOT.MAPPER, SLOT.REVIEW):
        assert qwen not in llm_router._SLOT_POOLS[slot]


class _Response:
    status_code = 200

    def __init__(self, body):
        self._body = body
        self.text = json.dumps(body, ensure_ascii=False)

    def json(self):
        return self._body


@pytest.mark.asyncio
async def test_http_200_error_body_falls_back(monkeypatch):
    monkeypatch.setenv("LLM_AISTORE_API_KEY", "fake-aistore")
    monkeypatch.setenv("LLM_TENCENT_API_KEY", "fake-tencent")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 13))
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [
            ("aistore", "DeepSeek-V4-Flash-A"),
            ("tencent", "deepseek-v4-flash-202605"),
        ],
    )
    good = {"choices": [{"message": {"content": "后备模型正常返回"}}]}

    class _Client:
        def __init__(self):
            self.calls = 0

        async def post(self, *_args, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                return _Response({"error": {"code": "model_not_found"}})
            return _Response(good)

    client = _Client()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    result = await call_chain(
        SLOT.CHAT,
        {"messages": [{"role": "user", "content": "测试"}]},
    )
    assert result == good
    assert client.calls == 2


@pytest.mark.asyncio
async def test_stream_error_event_before_content_falls_back(monkeypatch):
    monkeypatch.setenv("LLM_AISTORE_API_KEY", "fake-aistore")
    monkeypatch.setenv("LLM_TENCENT_API_KEY", "fake-tencent")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 13))
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [
            ("aistore", "DeepSeek-V4-Flash-A"),
            ("tencent", "deepseek-v4-flash-202605"),
        ],
    )

    class _StreamResponse:
        status_code = 200

        def __init__(self, lines):
            self._lines = lines

        async def aiter_lines(self):
            for line in self._lines:
                yield line

    class _StreamContext:
        def __init__(self, response):
            self.response = response

        async def __aenter__(self):
            return self.response

        async def __aexit__(self, *_args):
            return False

    class _Client:
        def __init__(self):
            self.calls = 0

        def stream(self, *_args, **_kwargs):
            self.calls += 1
            if self.calls == 1:
                lines = ['data: {"error":{"code":"model_not_found"}}', "data: [DONE]"]
            else:
                lines = [
                    'data: {"choices":[{"delta":{"content":"后备流正常"}}]}',
                    "data: [DONE]",
                ]
            return _StreamContext(_StreamResponse(lines))

    client = _Client()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)
    events = [
        event
        async for event in call_chain_stream(
            SLOT.CHAT,
            {"messages": [{"role": "user", "content": "测试"}]},
        )
    ]
    assert events == [{"type": "delta", "text": "后备流正常"}]
    assert client.calls == 2
