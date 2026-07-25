"""Deadline-aware fallback tests for the shared LLM router."""

from __future__ import annotations

import asyncio
import datetime

import pytest

from common import llm_router
from common.llm_router import SLOT, call_chain


@pytest.fixture(autouse=True)
def _reset_router_caches():
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    yield
    llm_router._CB_FAILURES.clear()
    llm_router._CB_LAST_FAIL.clear()
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()


class _GoodResponse:
    status_code = 200
    text = '{"ok":true}'

    def __init__(self, payload):
        self._payload = payload

    def json(self):
        return self._payload


@pytest.mark.asyncio
async def test_mapper_reserves_total_budget_for_later_healthy_candidate(monkeypatch):
    monkeypatch.setenv("LLM_ALIYUN_C_API_KEY", "key_c_fake")
    monkeypatch.setattr(
        llm_router,
        "_today",
        lambda: datetime.date(2026, 7, 23),
    )
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.MAPPER,
        [
            ("aliyun_c", "qwen3.6-flash-2026-04-16"),
            ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ],
    )
    good = {"choices": [{"message": {"content": '{"intent":"ok"}'}}]}

    class _BudgetClient:
        def __init__(self):
            self.call_log = []

        async def post(self, _url, headers=None, json=None, timeout=None):
            model = json["model"]
            self.call_log.append(model)
            if model == "qwen3.6-flash-2026-04-16":
                await asyncio.sleep(1)
                raise AssertionError("wait_for must time out the slow head")
            await asyncio.sleep(0.06)
            return _GoodResponse(good)

    client = _BudgetClient()
    monkeypatch.setattr(llm_router, "get_llm_http_client", lambda: client)

    result = await call_chain(
        SLOT.MAPPER,
        {"messages": [{"role": "user", "content": "return json"}]},
        timeout=0.25,
        total_timeout=0.30,
    )

    assert result == good
    assert client.call_log == [
        "qwen3.6-flash-2026-04-16",
        "qwen3.7-max-2026-06-08",
    ]


def test_budget_reservation_does_not_change_unbounded_or_reasoning_calls():
    assert llm_router._budgeted_attempt_timeout(
        SLOT.MAPPER,
        2.5,
        None,
        has_callable_fallback=True,
    ) == 2.5
    assert llm_router._budgeted_attempt_timeout(
        SLOT.REASONING,
        2.5,
        1.5,
        has_callable_fallback=True,
    ) == 1.5
