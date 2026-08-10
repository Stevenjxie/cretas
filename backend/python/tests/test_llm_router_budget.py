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
        lambda: datetime.date(2026, 8, 9),
    )
    # ⛔ 这里**不写死模型名**。本测试锁的是「慢链头 + 健康后备」这个形状, 与具体
    #    是哪两个模型无关; 而注册表随探针淘汰在动 —— 2026-08-09 因此换过一次,
    #    2026-08-10 又淘汰 5 条再次撞上。写死名字 = 每次淘汰都要人手改测试,
    #    改晚了测试红在「这个模型不在白名单」而不是它真正要守的预算行为上。
    #    从注册表现取两个同账号 (aliyun_c) 的条目, 淘汰谁都不影响。
    c_pairs = sorted(p for p in llm_router._SAFE_MODELS if p[0] == "aliyun_c")
    assert len(c_pairs) >= 2, f"aliyun_c 上少于 2 个可用条目, 无法构造本用例: {c_pairs}"
    slow_head, healthy = c_pairs[0], c_pairs[1]
    monkeypatch.setitem(llm_router.SLOT_MODELS, SLOT.MAPPER, [slow_head, healthy])
    good = {"choices": [{"message": {"content": '{"intent":"ok"}'}}]}

    class _BudgetClient:
        def __init__(self):
            self.call_log = []

        async def post(self, _url, headers=None, json=None, timeout=None):
            model = json["model"]
            self.call_log.append(model)
            if model == slow_head[1]:
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
    assert client.call_log == [slow_head[1], healthy[1]]


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
