"""在册账号的 key 为空时，**必须留痕** —— 这个洞活了三周就是因为它完全静默。

## 实测（2026-08-16）

`LLM_DEEPSEEK_API_KEY` 在生产进程里**存在但值长度 0**，而 `call_chain` 里：

    if not api_key:
        logger.debug(...)   # ① debug 级，生产看不见
        continue            # ② 不进 errors

于是最终那句 `All providers exhausted for chat: ` 后面**什么都没有** ——
连「因为没 key」都不说。而 deepseek 是 9-13 悬崖的唯一接班人。

▎**静默是这个洞能活下来的唯一原因。**

## 三条断言

1. 空 key ⇒ `errors` 里出现 `empty_api_key`（异常消息里看得见）
2. 空 key ⇒ 打 **warning**（⛔ 不是 debug —— 生产日志级别看不见 debug）
3. **每账号只喊一次** ⇒ ⛔ 不刷屏（刷屏的告警最终会被静音，形态 E）

## 阳性对照

key 正常时**不许**出现这些痕迹 —— 否则断言 1/2 在任何情况下都成立，不区分好坏。
"""
import asyncio
import logging

import pytest

import common.llm_router as R


@pytest.fixture(autouse=True)
def _reset_warned():
    """⚠️ 「只喊一次」是**进程级**状态 —— 每条用例前必须清，
    否则前一条用例喊过之后，后一条永远看不到 warning（假绿）。"""
    R._EMPTY_KEY_WARNED.clear()
    yield
    R._EMPTY_KEY_WARNED.clear()


#: 🔴 用 `setenv` 把**环境变量置空**, ⛔ 不打桩 `_provider_config`。
#:    两个理由:
#:    ① 这才是**生产上真实的故障形态** ——「变量存在但值为空」,
#:       打桩那个函数等于绕过了正要验的那一层
#:    ② `monkeypatch.setattr(R, "_provider_config", ...)` 会让
#:       `test_mutation_targets_are_reachable` 的棘轮从 25 涨到 26(实测)
def _call_with_empty_key(monkeypatch, account="deepseek"):
    monkeypatch.setenv("LLM_DEEPSEEK_API_KEY", "")     # 存在, 但值为空
    monkeypatch.setenv("LLM_DEEPSEEK_BASE_URL", "https://example.invalid/v1")
    # 链钉死到那个账号 ⇒ 一定走到「空 key」那一支
    payload = {"messages": [{"role": "user", "content": "x"}], "max_tokens": 8}
    with pytest.raises(RuntimeError) as ei:
        asyncio.run(R.call_chain(R.SLOT.CHAT, payload, chain=[account]))
    return str(ei.value)


def test_empty_key_shows_up_in_the_exhausted_message(monkeypatch):
    msg = _call_with_empty_key(monkeypatch)
    assert "empty_api_key" in msg, (
        f"⛔ 耗尽消息里没提空 key —— 那正是让它活三周的静默:\n{msg}")


def test_empty_key_logs_at_warning_not_debug(monkeypatch, caplog):
    with caplog.at_level(logging.WARNING):
        _call_with_empty_key(monkeypatch)
    hits = [r for r in caplog.records if "API key 为空" in r.message]
    assert hits, "⛔ 没有 warning —— debug 级在生产日志里看不见"
    assert all(r.levelno >= logging.WARNING for r in hits)


def test_warns_once_per_account_not_every_call(monkeypatch, caplog):
    """⛔ 不刷屏：链上有一个空 key 时，每次问答都会走到这里。

    ⚠️ 刷屏的告警最终会被静音，那时它的覆盖率归零（形态 E）。
    """
    with caplog.at_level(logging.WARNING):
        for _ in range(3):
            _call_with_empty_key(monkeypatch)
    hits = [r for r in caplog.records if "API key 为空" in r.message]
    assert len(hits) == 1, f"喊了 {len(hits)} 次，应当只喊 1 次"


def test_healthy_key_leaves_no_such_trace(monkeypatch, caplog):
    """阳性对照：key 正常时**不许**出现这些痕迹。

    出现了的话，上面两条断言在任何情况下都成立 ⇒ 不区分好坏。
    """
    # 阳性对照也走 setenv: 给一个**非空**的假 key ⇒ 走到真发请求那一步再失败
    monkeypatch.setenv("LLM_DEEPSEEK_API_KEY", "sk-" + "k" * 20)
    monkeypatch.setenv("LLM_DEEPSEEK_BASE_URL", "https://example.invalid/v1")
    payload = {"messages": [{"role": "user", "content": "x"}], "max_tokens": 8}
    with caplog.at_level(logging.WARNING):
        with pytest.raises(Exception) as ei:   # 连不上 example.invalid，必然失败
            asyncio.run(R.call_chain(R.SLOT.CHAT, payload, chain=["deepseek"]))
    assert "empty_api_key" not in str(ei.value)
    assert not [r for r in caplog.records if "API key 为空" in r.message]
