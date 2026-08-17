"""能力拒答 ⛔ 不该挂到会话上 —— 挂上去会让**整个会话**复读同一份文案。

## 缺陷（📏 MOCK_REST prod 2026-08-18，对照实验，⛔ 不是读代码推的）

唯一变量是 `session_key`（生产入口对同一个 sessionId 全程共用一个）：

    A 共用 key   翻台率怎么样 → clar 129 字 316dc92b
                 营收趋势怎么样 → clar 129 字 316dc92b   ← 原样复读上一题
                 毛利最低的菜品有哪些 → clar 129 字 316dc92b
    B 独立 key   翻台率怎么样 → clar 129 字 316dc92b
                 营收趋势怎么样 → answer 511 字 db9d5b1b
                 毛利最低的菜品有哪些 → answer 2417 字 7138e00f

逐轮读数显示 **LLM 每一轮都判对了**（第 2 轮 intent=TREND_ANALYSIS、
clarification_question='你想看哪个时间范围的营收趋势？'），错的是消费端：
`_parse_continuation` 把 `original_query + 新问句` 强制拼接后当 effective_query，
`requested_metrics` 从这个串里抽，于是 `table_turnover` 永远在，
`should_use_capability_refusal` 每轮都命中，能力拒答模板每轮都接管。

## 判据的来源：一个本来就存在的不一致

    注册进 pending 的问题:  '你这次最想先看哪件事？'
    老板实际看到的正文:      「翻台率…算不出来 / 缺的是… / 我这儿有的是…」

`_maybe_register_pending` 的语义是「我问了你一个问题，等你回答」，
而能力拒答**没有在问问题**。⇒ 不注册它，不是新加一条规则去猜，
是把一个本来就不该注册的东西不注册。

## ⛔ 显式登记：这一轮**没有**解决的

真反问（缺时间 / 缺门店，老板确实看到了那个问题）之后换话题，仍然会拼接；
`resolver_query_seed` 无条件累积增长也没解决。两者归架构点 25
（上下文存 spec 栈，⛔ 不存对话文字），⛔ 不在这一轮用启发式凑。

设计卡：docs/decisions/2026-08-18-能力拒答不该挂到会话上-设计卡.md
"""
from __future__ import annotations

import ast
import inspect
from typing import Any, Dict, List

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


def _spec(**overrides):
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    base: Dict[str, Any] = dict(
        intent="",
        domain="restaurant",
        date_range=(None, None),
        window_label="最近30天",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.4,
        source_tier="llm",
        clarification_needed=True,
        clarification_question="你这次最想先看哪件事？",
    )
    base.update(overrides)
    return RestaurantQuerySpec(**base)


@pytest.fixture
def recorded_puts(monkeypatch):
    """记下 `_pending_put` 的每一次调用。

    ⚠️ 钩的是 `restaurant_intent` 自己的模块全局 —— `_maybe_register_pending`
       就在同一个模块里按全局名调用它，所以这一份就是**被执行的那一份**。
    """
    calls: List[Dict[str, Any]] = []

    async def _spy(pool, factory_id, session_key, *, original_query,
                   clarification_question):
        calls.append({
            "factory_id": factory_id, "session_key": session_key,
            "original_query": original_query,
            "clarification_question": clarification_question,
        })

    monkeypatch.setattr(ri, "_pending_put", _spy)
    # 变异可达性: 确认钩子真的替换成功, 否则下面「没被调用」永远成立(恒真式)
    assert ri._pending_put is _spy, "钩子没挂上 —— 下面所有断言都没有意义"
    return calls


# ── 承重 ────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_capability_refusal_is_not_registered_as_a_pending_question(
    recorded_puts,
):
    """翻台率这类「我算不了」⛔ 不注册 —— 它没有在问问题。"""
    spec = _spec(unsupported_requirements=("table_turnover",),
                 requested_metrics=("table_turnover",))
    await ri._maybe_register_pending(
        None, "翻台率怎么样", spec, "MOCK_REST", "sess-1")
    assert recorded_puts == [], (
        "能力拒答被挂到会话上了 —— 下一句不管问什么都会被当成对它的回答，"
        "prod 实测连续 3 句拿到同一份 129 字（md5 316dc92b）"
    )


# ── 阴性对照：⛔ 不许把延续整个关掉 ──────────────────────────────────────

@pytest.mark.asyncio
async def test_a_real_missing_slot_question_is_still_registered(recorded_puts):
    """缺门店/缺时间是**真的在问问题**，照旧注册。

    这条守的是「变异 M2（无条件不注册）会红」——
    只有它红，才证明上面那条断言红在「只拦能力拒答」而不是「拦一切」。
    """
    spec = _spec(
        unsupported_requirements=(),
        requested_metrics=("sales_volume",),
        clarification_question="这项分析要看哪一组门店、哪个时间范围？",
        missing_slot="store+time",
    )
    await ri._maybe_register_pending(
        None, "米饭卖得怎么样", spec, "MOCK_REST", "sess-2")
    assert len(recorded_puts) == 1, "普通反问也不注册了 —— 延续被整个关掉"
    assert recorded_puts[0]["original_query"] == "米饭卖得怎么样"
    assert recorded_puts[0]["clarification_question"] == (
        "这项分析要看哪一组门店、哪个时间范围？")


@pytest.mark.asyncio
async def test_no_clarification_still_registers_nothing(recorded_puts):
    """阴性对照：不问问题的 spec 本来就不注册，行为与改动前逐字相同。"""
    await ri._maybe_register_pending(
        None, "哪家店卖得最好", _spec(clarification_needed=False),
        "MOCK_REST", "sess-3")
    assert recorded_puts == []


@pytest.mark.asyncio
async def test_no_session_key_still_registers_nothing(recorded_puts):
    """阴性对照：没有 session_key 时完全不启用续接（2026-07-08 设计 §1）。"""
    await ri._maybe_register_pending(
        None, "翻台率怎么样", _spec(unsupported_requirements=("table_turnover",)),
        "MOCK_REST", None)
    assert recorded_puts == []


# ── 同源：⛔ 不许写第二份判据 ────────────────────────────────────────────

def test_both_layers_read_the_same_capability_predicate():
    """注册侧与拒答侧必须读**同一个** `should_use_capability_refusal`。

    ⛔ 两份判据一定会漂（形态 D），而漂的表现是「注册侧说不拦、拒答侧说拦」——
       症状是会话又开始复读，而且不报错。
    """
    from smartbi.gold.restaurant import capability_answer
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    assert (ri.should_use_capability_refusal
            is capability_answer.should_use_capability_refusal), (
        "restaurant_intent 拿到的不是 capability_answer 那一份")
    assert (svc.should_use_capability_refusal
            is capability_answer.should_use_capability_refusal), (
        "restaurant_intent_service 拿到的不是 capability_answer 那一份")

    # AST: 注册函数里确实**调用**了它（⛔ 不用 grep 数文本, docstring 里提到不算）
    tree = ast.parse(inspect.getsource(ri._maybe_register_pending))
    called = {
        node.func.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    assert "should_use_capability_refusal" in called, (
        "`_maybe_register_pending` 没有调用那个判据 —— 守卫没接上"
    )
