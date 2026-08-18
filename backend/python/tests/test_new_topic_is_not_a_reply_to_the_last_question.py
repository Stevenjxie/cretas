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

#: 🔴 缺陷场景里续接产出的 intent（📏 prod 插桩实测）——
#: 它与新问句独立编译的 intent **相同**（都是 `BUSINESS_OPTIMIZATION`）。
#:
#: ⚠️ 我第一版写的是「不同」（以为续接会给 `STORE_MARGIN`），**被 prod 读数否掉**：
#:    续接**已经认出了正确的意图**，问题不在意图，在**槽位** ——
#:    pending 的「哪组门店 / 哪个时间范围」被套到了这句话上。
#: ▎同一个意图，单独问**不需要**澄清，接在 pending 后面**却需要** ——
#: ▎那些澄清需求是**继承来的**，不是这句话自己的。
_CONTINUED_INTENT = "RESTAURANT_OPS_BUSINESS_OPTIMIZATION"


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
    # 续接与独立编译**同一个** intent —— 那正是这个场景的形状。
    assert await _judge(
        monkeypatch, q, _NEW_TOPICS, _NEW_TOPICS[q].intent) is True, q


@pytest.mark.asyncio
async def test_the_north_star_question_specifically(monkeypatch):
    """🔴 这一问是整条改动的理由：老板要做一个决定。

    📏 prod 插桩实测：独立编译 `BUSINESS_OPTIMIZATION`、`clar=False`；
    续接**同一个** intent 但 `clar=True`（槽位是从 pending 继承的）。
    """
    assert await _judge(
        monkeypatch, "我要不要关掉最差的那家店", _NEW_TOPICS) is True


# ── 🔴 阴性对照：同一件事，不是换话题 ──────────────────────────────────────

@pytest.mark.asyncio
async def test_a_different_intent_means_the_continuation_already_switched(monkeypatch):
    """阴性对照：续接的 intent 与独立编译**不同** ⇒ ⛔ 不走这条路。

    那种情况说明续接自己就改了主意（contract-repair 之类），
    ▎本判据只管一种形状：**同一个意图，槽位是从 pending 继承来的**。
    """
    assert await _judge(
        monkeypatch, "哪道菜毛利最高", _NEW_TOPICS,
        "RESTAURANT_OPS_WASTAGE_TOP") is False


# ── 🔴 缓存命中**是**证据 —— 但 plan 本身要完整 ────────────────────────────

@pytest.mark.asyncio
@pytest.mark.parametrize("tier", ["validated_plan_cache", "plan_cache",
                                  "promoted_exact", "reviewed_exact", "llm"])
async def test_a_cache_hit_counts_as_evidence(monkeypatch, tier):
    """🔴 这条断言**反过来过一次** —— 原来守的是「缓存命中不算数」。

    加那个 `source_tier == "llm"` 条件的理由是三轮链用例（毛利 →「本月」→ 门店）：
    独立编译「本月」命中缓存、返回完整 `GROSS_MARGIN` spec ⇒ 多轮链断掉。

    ⛔ 但那个理由**站不住**：计划缓存的键是
    `(factory_id, 归一化问句, version)` —— 它存的**就是「这句话单独编译的结果」**。
    ▎命中缓存恰恰是「这句话是一句完整问句」的**更强**证据，不是更弱。

    三轮链真正的问题在**桩**（`AsyncMock` 的 `side_effect` 是顺序队列，
    判据多调一次把 `third_plan` 吃掉了）；补一条孤立「本月」的真实形状之后，
    tier 条件就不再需要。

    📏 删它的直接原因（prod 全景**轮2**插桩实测）：
    `a_intent=BUSINESS_OPTIMIZATION a_clar=False cont=同一个` 三条全过，
    **只有 `a_tier=plan_cache` 挡住** —— 而那个缓存是**老板轮1 问过同一句**
    留下的，是正常状态。⇒ 老板重复问一次反而拿不到答案。

    ⚡ 副作用是好的：命中缓存时这次判定**零 LLM 调用**。
    """
    table = {"本月": _Spec("RESTAURANT_OPS_GROSS_MARGIN", False, tier)}
    assert await _judge(
        monkeypatch, "本月", table, "RESTAURANT_OPS_GROSS_MARGIN") is True, tier


@pytest.mark.asyncio
@pytest.mark.parametrize("tier", ["validated_plan_cache", "plan_cache", "llm"])
async def test_an_incomplete_plan_is_not_evidence_whatever_the_tier(
        monkeypatch, tier):
    """🔴 **这才是三轮链真正需要的那条**：plan 不完整 ⇒ 不算数，**与 tier 无关**。

    📏 真实环境实测：7 条槽位回答（上个月 / 最近30天 / 陆家嘴店 / 全部门店 /
    晚市 / 按食材 …）孤立编译**全部** `intent=None, clarification_needed=True`。
    ⇒ 挡住它们的是**plan 的形状**，⛔ 不是它从哪一层来的。
    """
    table = {"本月": _Spec("", True, tier)}
    assert await _judge(
        monkeypatch, "本月", table, "RESTAURANT_OPS_GROSS_MARGIN") is False, tier


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
