"""Regression tests for the production REVIEW planner budget."""

import pytest

from common import llm_router
from smartbi.gold.restaurant import restaurant_intent


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("prefer_high_accuracy", "expected_slot", "timeout", "total_timeout", "max_tokens"),
    [
        (False, llm_router.SLOT.MAPPER,
         restaurant_intent._T3_PROVIDER_TIMEOUT_SECONDS,
         restaurant_intent._T3_TOTAL_TIMEOUT_SECONDS, 500),
        (True, llm_router.SLOT.REVIEW,
         restaurant_intent._SEMANTIC_PROVIDER_TIMEOUT_SECONDS,
         restaurant_intent._SEMANTIC_TOTAL_TIMEOUT_SECONDS, 1000),
    ],
)
async def test_t3_planner_uses_separate_legacy_and_semantic_budgets(
    monkeypatch,
    prefer_high_accuracy,
    expected_slot,
    timeout,
    total_timeout,
    max_tokens,
):
    seen = {}

    async def fake_call_chain(slot, payload, **kwargs):
        seen.update(slot=slot, payload=payload, kwargs=kwargs)
        return {
            "choices": [
                {
                    "message": {
                        "content": (
                            '{"intent":"RESTAURANT_OPS_DAYPART_PERFORMANCE",'
                            '"confidence":0.95}'
                        )
                    }
                }
            ]
        }

    monkeypatch.setattr(llm_router, "call_chain", fake_call_chain)

    parsed = await restaurant_intent._t3_llm_parse(
        "过去30天午市、下午茶、晚市和夜宵哪个时段营业额最高",
        hint=None,
        history=None,
        prefer_high_accuracy=prefer_high_accuracy,
    )

    assert parsed["intent"] == "RESTAURANT_OPS_DAYPART_PERFORMANCE"
    assert seen["slot"] == expected_slot
    assert seen["kwargs"]["timeout"] == timeout
    assert seen["kwargs"]["total_timeout"] == total_timeout
    assert seen["payload"]["max_tokens"] == max_tokens


# ⚠️ 上面那条参数化现在从常量读取预期值, 也就是**左右同源** —— 它只还能证明
#    「call_chain 收到的确实是这两个常量」(接线没断), 证明不了「这两个常量本身
#    合理」。合理性由下面这两条不变式承担, 它们不引用任何字面数值。
#
# 🔴 为什么不再写死 10.0/25.0: 2026-08-10 owner 把单跳预算从 10s 放宽到 25s
#    (让实测零越界但慢的 kimi-k2.7-code 能进链)。写死的那两个数当场变红 ——
#    红得完全正确却与「预算接线坏了」无关, 而这正是今天第三次交同一笔维护税
#    (另两次见 test_llm_router_budget / test_refuse_hard_drops_expired)。

def test_semantic_budget_is_strictly_larger_than_legacy():
    """语义高精度路径的预算必须严格大于旧 T3 路径 —— 否则「高精度」名不副实。"""
    assert (restaurant_intent._SEMANTIC_PROVIDER_TIMEOUT_SECONDS
            > restaurant_intent._T3_PROVIDER_TIMEOUT_SECONDS)
    assert (restaurant_intent._SEMANTIC_TOTAL_TIMEOUT_SECONDS
            > restaurant_intent._T3_TOTAL_TIMEOUT_SECONDS)


def test_total_budget_leaves_room_for_a_second_attempt():
    """总预算至少要放得下「一个候选耗尽单跳预算后, 还能再试一个」。

    总预算 == 单跳预算时, 链在事实上只有一跳 —— 后面所有候选都是死代码,
    而这件事不会有任何症状: 日志里只是「超时」, 看不出来是预算把链掐断了。
    """
    for hop, total, name in (
        (restaurant_intent._SEMANTIC_PROVIDER_TIMEOUT_SECONDS,
         restaurant_intent._SEMANTIC_TOTAL_TIMEOUT_SECONDS, "semantic"),
        (restaurant_intent._T3_PROVIDER_TIMEOUT_SECONDS,
         restaurant_intent._T3_TOTAL_TIMEOUT_SECONDS, "legacy"),
    ):
        assert total >= hop * 2, (
            f"{name}: 总预算 {total}s 放不下两次 {hop}s 的尝试 —— "
            f"链实际只有一跳, 后面的候选是死代码")


def test_ordering_uses_the_same_hop_budget_as_the_caller():
    """排序用的单跳预算与调用方真正传下去的必须是**同一个对象**。

    两处各写一份必漂, 漂了就是「排序以为某候选在预算内、实际每次都超时」——
    那一跳白等, 而且没有任何症状。
    """
    assert (restaurant_intent._SEMANTIC_PROVIDER_TIMEOUT_SECONDS
            is llm_router._SLOT_HOP_BUDGET_SECONDS)
