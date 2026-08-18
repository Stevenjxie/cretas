"""老板换了话题，不该被当成在回答上一句反问。

设计卡：`docs/decisions/2026-08-18-换话题不该被当成回答反问-设计卡.md`

## 缺陷（📏 四组对照，唯一变量是前置问句；阳性对照：无前置组 answer 948）

```
① 无前置                        → answer 948
② 只有「米饭卖得怎么样」          → clarification 23
                                  「你想针对哪几家门店做优化？以及看哪个时间范围？」
③ 只有「哪家店缺货最严重」        → answer 948
④ 两条都有（全景里的真实顺序）    → clarification 14
                                  「你想针对哪家门店做优化决策？」
```

▎老板问「我要不要关掉最差的那家店」—— **他要做一个决定**，
▎而系统反问他「哪家门店」。

元凶是 ②：「米饭卖得怎么样」注册了一个**合法**的槽位反问 pending。
⛔ 不能靠「不注册」来修 —— 它真的在问槽位。问题在**消费端**。

## ⚠️ 这组用例里最重要的是阴性对照

`_maybe_register_pending` 的 docstring 明写：「缺时间 / 缺门店 / 说不清要看什么
**照旧注册**，否则等于把整个澄清续接关掉（有阴性对照钉着）」。
⇒ 「上个月」这类槽位回答**必须仍然走续接**。把那条改红的修法一律不算修好。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent import (
    _is_new_topic_not_a_reply,
)


class _Spec:
    """只带判据要读的三个字段 —— ⛔ 不桩整个 spec。"""

    def __init__(self, intent, clarification_needed, source_tier="llm"):
        self.intent = intent
        self.clarification_needed = clarification_needed
        self.source_tier = source_tier


def _patch(monkeypatch, table):
    """把 `parse_restaurant_query` 换成一张查表。

    ⚠️ 走**模块属性**打桩，而被测函数内部也按模块全局解析这个名字 ——
       本仓记过 `from x import y` 会让 `monkeypatch.setattr` 够不着。
    """
    from smartbi.gold.restaurant import restaurant_intent as ri

    async def _fake(query, pool, **kw):
        assert kw.get("session_key") is None, (
            "判定时必须**不带 session_key** —— 否则它会再去 pop 一次 pending"
        )
        return table[query]

    monkeypatch.setattr(ri, "parse_restaurant_query", _fake)


#: 📏 prod 实测：这 7 条独立编译**全部** `独立成句=False`（0 误判）。
#:    ⚠️「晚市」抽得出 intent 但 `clarification_needed=True` —— 判据的
#:    两个条件都必需，只看 intent 会把它误判成新话题。
_SLOT_REPLIES = {
    "上个月": _Spec("", True),
    "最近30天": _Spec("", True),
    "陆家嘴店": _Spec("", True),
    "全部门店": _Spec("", True),
    "全部门店 最近30天": _Spec("", True),
    "晚市": _Spec("RESTAURANT_OPS_DAYPART_PERFORMANCE", True),
    "按食材": _Spec("", True),
}

#: 📏 prod 实测：这 6 条独立编译**全部** `独立成句=True`。
_NEW_TOPICS = {
    "我要不要关掉最差的那家店": _Spec("RESTAURANT_OPS_BUSINESS_OPTIMIZATION", False),
    "最近生意怎么样": _Spec("RESTAURANT_OPS_SALES_SUMMARY", False),
    "哪家店卖得最好": _Spec("RESTAURANT_OPS_SALES_SUMMARY", False),
    "最近损耗怎么样": _Spec("RESTAURANT_OPS_WASTAGE_TOP", False),
    "折扣力度多大": _Spec("RESTAURANT_OPS_DISCOUNT_SUMMARY", False),
    "哪道菜毛利最高": _Spec("RESTAURANT_OPS_GROSS_MARGIN", False),
}

#: 缺陷场景里续接产出的 intent（📏 实测 `STORE_MARGIN`）——
#: 与新问句独立编译的 `BUSINESS_OPTIMIZATION` 不同，所以判成换话题。
_CONTINUED_INTENT = "RESTAURANT_OPS_STORE_MARGIN"


async def _judge(monkeypatch, q, table, continued_intent=_CONTINUED_INTENT):
    _patch(monkeypatch, table)
    return await _is_new_topic_not_a_reply(
        q, object(), factory_id="MOCK_REST", semantic_first=True,
        continued_intent=continued_intent)


# ── 🔴 阴性对照（最重要）：槽位回答必须仍然走续接 ──────────────────────────

@pytest.mark.asyncio
@pytest.mark.parametrize("q", sorted(_SLOT_REPLIES))
async def test_a_slot_reply_is_never_mistaken_for_a_new_topic(monkeypatch, q):
    """⛔ 把这条改红的修法一律不算修好 —— 那等于把整个澄清续接关掉。"""
    assert await _judge(monkeypatch, q, _SLOT_REPLIES) is False, q


@pytest.mark.asyncio
async def test_the_daypart_word_needs_both_conditions(monkeypatch):
    """🔴「晚市」是判据必须有**两个**条件的理由。

    它抽得出 `intent=DAYPART_PERFORMANCE`，但 `clarification_needed=True`。
    ⇒ 只看 `intent` 会把它误判成新话题，而它明明是在回答「哪个时段」。
    """
    assert await _judge(monkeypatch, "晚市", _SLOT_REPLIES) is False


# ── 承重：完整问句要被认出来 ───────────────────────────────────────────────

@pytest.mark.asyncio
@pytest.mark.parametrize("q", sorted(_NEW_TOPICS))
async def test_a_complete_question_is_a_new_topic(monkeypatch, q):
    # ⚠️ 逐条给一个**与它自己不同**的 continued_intent —— 否则「哪道菜毛利最高」
    #    会与默认的 STORE_MARGIN 撞不上，而是与自己的 GROSS_MARGIN 撞。
    other = ("RESTAURANT_OPS_STORE_MARGIN"
             if _NEW_TOPICS[q].intent != "RESTAURANT_OPS_STORE_MARGIN"
             else "RESTAURANT_OPS_WASTAGE_TOP")
    assert await _judge(monkeypatch, q, _NEW_TOPICS, other) is True, q


@pytest.mark.asyncio
async def test_the_north_star_question_specifically(monkeypatch):
    """🔴 这一问是整条改动的理由：老板要做一个决定。

    📏 实测：独立编译 `BUSINESS_OPTIMIZATION` vs 续接 `STORE_MARGIN`。
    """
    assert await _judge(
        monkeypatch, "我要不要关掉最差的那家店", _NEW_TOPICS) is True


# ── 🔴 阴性对照：同一件事，不是换话题 ──────────────────────────────────────

@pytest.mark.asyncio
async def test_the_same_intent_is_not_a_new_topic(monkeypatch):
    """语义上真正的那一条：**换话题 = 问的是另一件事。**

    续接产出的 intent 与新问句独立编译的 intent **相同** ⇒ 同一件事 ⇒ 不是换话题。
    """
    assert await _judge(
        monkeypatch, "哪道菜毛利最高", _NEW_TOPICS,
        "RESTAURANT_OPS_GROSS_MARGIN") is False


# ── 🔴 阴性对照：缓存命中不算「这句话完整」 ────────────────────────────────

@pytest.mark.asyncio
@pytest.mark.parametrize("tier", ["validated_plan_cache", "plan_cache",
                                  "promoted_exact", "reviewed_exact"])
async def test_a_cache_hit_is_not_evidence_that_the_sentence_is_complete(
        monkeypatch, tier):
    """🔴 这一条是**既有用例逼出来的**（三轮链：毛利 → 「本月」 → 门店）。

    独立编译「本月」时命中计划缓存，返回一个完整的 `GROSS_MARGIN` spec，
    于是「本月」被判成新话题、多轮链当场断掉。

    ▎判据要的是「**T3 现在看这句话，认为它自己就是一句完整问句**」，
    ▎⛔ 不是「这句话以前出现过」。缓存命中只说明后者。
    """
    table = {"本月": _Spec("RESTAURANT_OPS_GROSS_MARGIN", False, tier)}
    assert await _judge(monkeypatch, "本月", table) is False, tier


@pytest.mark.asyncio
async def test_the_same_sentence_from_t3_would_be_a_new_topic(monkeypatch):
    """阳性对照：**同一个 spec** 只把 tier 换成 `llm` 就该判 True。

    ⛔ 少了它，上面那条「缓存命中判 False」可能只是因为判据整个坏了。
    """
    table = {"本月": _Spec("RESTAURANT_OPS_GROSS_MARGIN", False, "llm")}
    assert await _judge(monkeypatch, "本月", table) is True


# ── 保守方向：判不出来时**照旧走续接** ─────────────────────────────────────

@pytest.mark.asyncio
async def test_a_compile_failure_keeps_the_continuation(monkeypatch):
    """⛔ 一次故障不许变成「所有问句都成了新话题」。

    ▎保守方向是**保住澄清续接** —— 那是用户正在进行的一次对话。
    """
    from smartbi.gold.restaurant import restaurant_intent as ri

    async def _boom(*a, **kw):
        raise RuntimeError("provider down")

    monkeypatch.setattr(ri, "parse_restaurant_query", _boom)
    assert await _is_new_topic_not_a_reply(
        "我要不要关掉最差的那家店", object(),
        factory_id="MOCK_REST", semantic_first=True) is False


@pytest.mark.asyncio
async def test_a_none_spec_keeps_the_continuation(monkeypatch):
    from smartbi.gold.restaurant import restaurant_intent as ri

    async def _none(*a, **kw):
        return None

    monkeypatch.setattr(ri, "parse_restaurant_query", _none)
    assert await _is_new_topic_not_a_reply(
        "随便什么", object(), factory_id="MOCK_REST",
        semantic_first=True) is False


@pytest.mark.asyncio
@pytest.mark.parametrize("q", ["", "   ", "\n"])
async def test_blank_input_keeps_the_continuation(monkeypatch, q):
    """空输入 ⛔ 不许触发一次编译，更不许被判成新话题。"""
    from smartbi.gold.restaurant import restaurant_intent as ri

    called = []

    async def _spy(*a, **kw):
        called.append(1)
        return _Spec("RESTAURANT_OPS_SALES_SUMMARY", False)

    monkeypatch.setattr(ri, "parse_restaurant_query", _spy)
    assert await _is_new_topic_not_a_reply(
        q, object(), factory_id="MOCK_REST", semantic_first=True) is False
    assert not called, "空输入还去编译了一次 —— 白花一次模型调用"


# ── 接线：调用点真的用上了它 ───────────────────────────────────────────────

def test_the_pending_path_actually_consults_it():
    """接线：pop 完 pending 必须问一次这个判据。

    ⚠️ 断言第一版扫的是 `parse_restaurant_query` —— 它是个**薄包装**，
       pending 分支在 `_parse_restaurant_query_impl` 里，于是断言当场红。
       ▎它红得对：扫错了函数的接线断言，等于没有接线断言。

    ⛔ 用 AST 找调用，不 grep（docstring 里就写着这个函数名）。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_intent as ri

    tree = ast.parse(inspect.getsource(ri._parse_restaurant_query_impl))
    called = {
        n.func.id
        for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
    }
    assert "_is_new_topic_not_a_reply" in called, (
        "pending 分支没问这个判据 —— 换话题仍然会被当成回答"
    )
    assert "_pending_pop" in called, sorted(called)[:12]


def test_the_check_runs_after_the_continuation_not_before():
    """🔴 位置：必须在续接**之后**。

    ⛔ 第一版放在 `_pending_pop` 之后 ⇒ **13 条既有断言当场红** ——
       那些路径的契约是 `assert llm.await_count == 0`（「T3 挂了也能续接」），
       而无条件先编译一次直接打破它。
    ▎判据红了不许加豁免，要看它逼出什么 —— 它逼出的是
    ▎**「只有在确定性续接已经失败时，才有资格多花一次编译」**。

    ⇒ 钉住：判据的调用出现在 `_parse_continuation` 之后。
    """
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_intent as ri

    src = inspect.getsource(ri._parse_restaurant_query_impl)
    tree = ast.parse(src)
    pos = {}
    for n in ast.walk(tree):
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name):
            pos.setdefault(n.func.id, n.lineno)
    assert "_parse_continuation" in pos and "_is_new_topic_not_a_reply" in pos
    assert pos["_is_new_topic_not_a_reply"] > pos["_parse_continuation"], (
        "判据跑在续接之前 —— 零 token 续接路径会白多一次 T3 调用"
    )
