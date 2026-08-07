"""额度耗尽 → 整体封闭 AI 入口，任何层都不产出答案。

🔴 Steve 2026-08-07 拍板：「如果 LLM 的额度没有了，那整个 AI 的，不管是第一层
还是第二层，全部封闭掉」。

判据：**部分可用比完全不可用更危险。** 确定性层（晋升表回放 / 已知缺口 / 域外
拒答）压根不调 LLM —— 链已经死透时它们照样能产出答案，而用户无法判断此刻这个
答案为什么这么简单、可不可信。

⚠️ 我 2026-08-07 早些时候把「LLM 全线熔断时确定性路径仍在 43ms 内作答」当成
   优点写进了验收证据。**那是反的**，本文件把正确的方向钉住。
"""
import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc


class _BoomPool:
    """任何数据库访问都炸 —— 用来证明封闭发生在**碰库之前**。"""

    def acquire(self):
        raise AssertionError("额度耗尽时不该碰库：封闭必须发生在所有层之前")


@pytest.mark.asyncio
async def test_all_layers_are_closed_when_capacity_is_gone(monkeypatch):
    monkeypatch.setattr(svc, "_llm_capacity_available", lambda: False)

    res = await svc.tiered_answer("最近损耗情况怎么样", _BoomPool(), "MOCK_REST", "factory_super_admin")

    assert res["kind"] == "unavailable"
    assert res["code"] == "RESTAURANT_AI_UNAVAILABLE"
    assert res["contract_pass"] is False
    assert "不可用" in res["answer_text"]
    # 🔴 不许给出任何业务数字 —— 那正是「部分可用」的形态。
    assert not res["kpis"]
    assert not res["charts"]


@pytest.mark.asyncio
async def test_promoted_route_does_not_answer_either(monkeypatch):
    """阴性对照：晋升表回放是零 token 的，最容易「偷偷还能答」。

    没有这条，上面那条可能只证明了「反问被关掉了」，而不是「所有层都被关掉了」。
    """
    monkeypatch.setattr(svc, "_llm_capacity_available", lambda: False)

    # 这句在 prod 晋升表里，正常情况下 24ms 零 token 直接作答。
    res = await svc.tiered_answer("哪个菜卖得最好", _BoomPool(), "MOCK_REST", "factory_super_admin")

    assert res["kind"] == "unavailable", "晋升表回放也必须被关掉"


@pytest.mark.asyncio
async def test_normal_path_untouched_when_capacity_is_fine(monkeypatch):
    """可用时这道闸必须完全透明 —— 否则它会变成一个新的故障源。

    ⚠️ 断言写法换过一次：`tiered_answer` 内部会吞掉下游异常并转成 clarification，
    所以 `pytest.raises` 恒不成立。改成**直接断言「结果不是被这道闸拦下的」** ——
    量的是这道闸的行为，而不是下游怎么失败。
    """
    monkeypatch.setattr(svc, "_llm_capacity_available", lambda: True)

    res = await svc.tiered_answer(
        "随便问点什么", _BoomPool(), "MOCK_REST", "factory_super_admin"
    )

    # 可用时不该出现「AI 暂不可用」——不论下游最终成功还是失败。
    assert (res or {}).get("code") != "RESTAURANT_AI_UNAVAILABLE"
    assert (res or {}).get("kind") != "unavailable"


def test_capacity_probe_fails_open():
    """⛔ 探测器自己坏了 → 放行，不是封闭。

    关闭 AI 是重手段，只能在**确知**链路不可用时才用。
    「因为一个探测器抛异常就把整个 AI 关掉」是把可用性判断做成了新的故障源。
    """
    import common.llm_router as router

    original = router.slot_has_usable_provider
    try:
        def _explode(_slot):
            raise RuntimeError("router 变了 / 探测器坏了")

        router.slot_has_usable_provider = _explode
        assert svc._llm_capacity_available() is True
    finally:
        router.slot_has_usable_provider = original


def test_probe_is_read_only():
    """预检每次问答都跑，不能顺手消耗熔断器的「再探一次」额度。"""
    import time

    from common.llm_router import (
        CB_THRESHOLD,
        SLOT,
        SLOT_MODELS,
        _CB_FAILURES,
        _CB_LAST_FAIL,
        slot_has_usable_provider,
    )

    chain = SLOT_MODELS[SLOT.REVIEW]
    assert chain, "REVIEW 链是空的, 这条测试就量不到东西了"
    key = f"{chain[0][0]}/{chain[0][1]}"
    _CB_FAILURES[key] = CB_THRESHOLD
    # 冷却期**已过** —— 带副作用的 `_cb_should_skip` 会在这里把计数器清零。
    _CB_LAST_FAIL[key] = time.time() - 10_000

    try:
        slot_has_usable_provider(SLOT.REVIEW)
        assert _CB_FAILURES[key] == CB_THRESHOLD, (
            "预检把熔断计数器重置了 —— 那会消耗掉真正调用的重试机会"
        )
    finally:
        _CB_FAILURES.pop(key, None)
        _CB_LAST_FAIL.pop(key, None)


def test_missing_keys_is_config_not_exhaustion():
    """🔴「一个 key 都没配」不算额度耗尽 —— 这道闸只判运行期耗尽。

    第一版把两者混为一谈：测试环境本来就不配 key（LLM 在更上层被 mock），
    结果整套餐饮问答被封闭，**全仓新增 54 个失败**。

    判据：这道闸的职责是「链路跑着跑着用完了」，不是「有没有装好」。
    真的漏配 key 时，router 在被调用时照旧抛 exhausted —— 那条路仍然安全，
    只是没有早退这么干净。
    """
    import common.llm_router as router

    original = router._provider_config
    try:
        router._provider_config = lambda _account: ("http://x", "")  # 全无 key
        assert router.slot_has_usable_provider(router.SLOT.REVIEW) is True
    finally:
        router._provider_config = original


def test_configured_but_all_expired_is_exhaustion():
    """阴性对照：配了 key 但全链拒用 -> **这才是**耗尽，必须封闭。

    没有这条，上面那条可能把闸整个变成恒真。
    """
    import common.llm_router as router

    orig_cfg = router._provider_config
    orig_refuse = router._refuse_reason
    try:
        router._provider_config = lambda _account: ("http://x", "sk-fake")
        router._refuse_reason = lambda _a, _m, _t=None: "expired"
        assert router.slot_has_usable_provider(router.SLOT.REVIEW) is False
    finally:
        router._provider_config = orig_cfg
        router._refuse_reason = orig_refuse
