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


# 每个**出现在链上**的 aistore 模型都必须在这里有一条实测结论。
# ⛔ 往任何池子里加 aistore 模型而不在这里登记 ⇒ 下面那道闸变红。
#
# 为什么闸要钉这张表而不是钉三个写死的名字: 关思考与不关思考在这个端点上
# 差 1.4s vs 8.5s(见下), 而**悬崖正好落在单跳预算 6.0s 的两侧**。它靠
# `_AISTORE_THINKING_OBJECT_MODELS` 的**字符串精确匹配**守着 —— 型号改一个
# 字母、或新模型漏登记, `model in frozenset` 静默为 False, 那个模型每次调用
# 都是 8.5s + 空 content, 而只认三个字面量的闸照绿。
#
# ⛔ 不能写成「所有 aistore 模型都必须拿到开关」—— 那是错的, 见 DeepSeek-V4-Flash。
_AISTORE_THINKING_VERDICT: dict[str, tuple[bool, str]] = {
    # 模型: (是否必须拿到关闭开关, 依据)
    "DeepSeek-V4-Flash-A": (
        True,
        "2026-08-15 生产端点实测: 带 thinking:disabled 1.43/1.43/1.56s、"
        "reasoning_tokens=None; 去掉该字段 8.03/8.13/8.95s、reasoning_tokens=800 "
        "(把 max_tokens 全烧在思考上)、content_len=0、finish_reason=length。"
        "⇒ 没有这个开关它会思考, 思考量随请求波动: 实测 4 次落在 5.69~8.95s, "
        "其中 3 次超过 6.0s 单跳预算; 最坏情况把 max_tokens 全烧在 reasoning 上、"
        "content 为空 (finish_reason=length)。"
        "⛔ 不要写「必然」—— 有一次 5.69s / rt=402 / content=539 字符, 低于预算且非空。",
    ),
    "DeepSeek-V4-Flash": (
        False,
        "2026-08-15 同批实测: 生产路径**不**注入该字段, 而它 2.17/2.24s、"
        "reasoning_tokens=None、finish_reason=stop ⇒ 这个变体默认就不思考, "
        "不需要开关。⚠️ 它当前不在任何池里; 若要启用需先过 _SAFE_MODELS "
        "(owner 控制台确认), 本条只是把实测结论留痕。",
    ),
    "Qwen3-235B-A22B": (
        True,
        "随 _AISTORE_THINKING_OBJECT_MODELS 一并登记(2026-08-13 上线时的实测结论)。"
        "⚠️ 未经 2026-08-15 那轮 with/without 对照复测。",
    ),
    "Qwen3-32B": (
        True,
        "同 Qwen3-235B-A22B。⚠️ 未经 2026-08-15 那轮 with/without 对照复测。",
    ),
}


def test_every_aistore_model_on_a_chain_has_a_recorded_thinking_verdict():
    """闸钉的是**耦合**, 不是三个写死的名字。

    ⛔ 判据来自 SLOT_MODELS(真正会上线的链), 不是我手写的名单 ——
       「一个都没找到」最像「一切正常」, 所以最后 assert 总数 > 0。
    """
    base = {"messages": [{"role": "user", "content": "return json"}]}
    checked: list[tuple[str, str]] = []
    missing: list[tuple[str, str]] = []

    for slot, chain in llm_router.SLOT_MODELS.items():
        for account, model in chain:
            if account != "aistore":
                continue
            checked.append((slot.value, model))
            verdict = _AISTORE_THINKING_VERDICT.get(model)
            if verdict is None:
                missing.append((slot.value, model))
                continue
            needs_switch, why = verdict
            # 🔴 2026-08-15 (A1): 拿不拿开关**还取决于槽的 profile** ——
            #    REASONING 的 profile 是 `{}`(思考故意开着, 预算 30s), 那里
            #    **不该**注入。原来只比登记表, 编码了「aistore 只出现在关思考的
            #    槽上」这个 A1 之前才成立的前提。
            wants_off = (llm_router._SLOT_PARAMS.get(slot) or {}).get(
                "enable_thinking") is False
            expected = needs_switch and wants_off
            out = llm_router._apply_slot_params(slot, account, model, base)
            got = out.get("thinking") == {"type": "disabled"}
            assert got == expected, (
                f"{slot.value}/{model}: 登记 needs_switch={needs_switch} × "
                f"槽 profile 要求关思考={wants_off} ⇒ 应注入={expected}, "
                f"实际={got}。依据: {why}"
            )
            # enable_thinking 是 DashScope 的参数, 不该出现在 aistore 的 payload 里
            assert "enable_thinking" not in out, (
                f"{slot.value}/{model}: 混进了 DashScope 的 enable_thinking"
            )

    assert not missing, (
        "这些 aistore 模型已经在链上, 但没有 thinking 结论登记 —— "
        "先实测 with/without 再补进 _AISTORE_THINKING_VERDICT: "
        f"{sorted(set(missing))}"
    )
    assert checked, (
        "一个 aistore 条目都没扫到 —— 闸空转了。"
        "要么链变了, 要么 SLOT_MODELS 的形状变了, 先查闸本身。"
    )


def test_aistore_thinking_switch_is_keyed_by_the_exact_model_string():
    """阳性对照: 型号名差一个字母, 开关就静默失效。

    这条不是重复上面那条 —— 它证明**那个失效是静默的**, 也就是为什么
    上面那道闸必须存在。
    """
    base = {"messages": [{"role": "user", "content": "return json"}]}
    real = llm_router._apply_slot_params(
        SLOT.REVIEW, "aistore", "DeepSeek-V4-Flash-A", base)
    assert real["thinking"] == {"type": "disabled"}

    typo = llm_router._apply_slot_params(
        SLOT.REVIEW, "aistore", "DeepSeek-V4-Flash-B", base)
    assert "thinking" not in typo, (
        "如果这条红了, 说明注入不再依赖精确型号名 —— 那是好事, "
        "上面那道闸可以放宽; 但要先确认新的判定依据是什么。"
    )


def test_qwen32_is_confined_to_the_simple_text_slot():
    pair = ("aistore", "Qwen3-32B")
    assert pair in llm_router._SLOT_POOLS[SLOT.SIMPLE_TEXT]
    for slot, pool in llm_router._SLOT_POOLS.items():
        if slot is SLOT.SIMPLE_TEXT:
            continue
        assert pair not in pool, f"Qwen3-32B leaked into complex slot {slot.value}"


def test_restaurant_slots_prefer_aistore_deepseek_and_confine_qwen235():
    """2026-08-16: 链头换成 owner 置顶的 zhipu, aistore 顺位第二。

    ⛔ 改的只有「谁是第 0 位」这一句。这条闸真正承重的是**下半段** ——
       Qwen3-235B 只许出现在 CHART, 且必须排在 DeepSeek-V4-Flash-A 之后。
       那部分一个字没动。
    """
    deepseek = ("aistore", "DeepSeek-V4-Flash-A")
    qwen = ("aistore", "Qwen3-235B-A22B")
    for slot in (SLOT.CHAT, SLOT.INSIGHTS, SLOT.CHART, SLOT.MAPPER, SLOT.REVIEW):
        chain = llm_router.SLOT_MODELS[slot]
        assert chain[0] == ("zhipu", "glm-4.5-air"), (
            f"{slot.value}: 链头不是 zhipu, 实际 {chain[0]}")
        assert chain[1] == deepseek, (
            f"{slot.value}: zhipu 之后不是 aistore/DeepSeek-V4-Flash-A, 实际 {chain[1]}")
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
    monkeypatch.setenv("LLM_DEEPSEEK_API_KEY", "fake-deepseek")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 13))
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [
            ("aistore", "DeepSeek-V4-Flash-A"),
            ("deepseek", "deepseek-v4-flash"),
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
    monkeypatch.setenv("LLM_DEEPSEEK_API_KEY", "fake-deepseek")
    monkeypatch.setattr(llm_router, "_today", lambda: datetime.date(2026, 8, 13))
    monkeypatch.setitem(
        llm_router.SLOT_MODELS,
        SLOT.CHAT,
        [
            ("aistore", "DeepSeek-V4-Flash-A"),
            ("deepseek", "deepseek-v4-flash"),
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
