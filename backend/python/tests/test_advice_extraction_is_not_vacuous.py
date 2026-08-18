"""建议抽取器本身的用例 —— ⛔ 一个抽不出东西的抽取器，读数长得像「产品不给建议」。

`restaurant_advice_is_actionable_probe.extract_advice` 是**代理判据**：
它按正则去捞 `_dimension_gap_advice` 产出的那句「按X怎么样」。

▎文案一改，它**静默失效**，而症状是「一条建议都没有」——
▎和「产品不再给建议」长得一模一样。

⇒ 这一组把两件事钉住：
  ① 抽取器对**产品真实产出的那两种句式**有效（⛔ 不是对我编的例句有效）
  ② 它不会把答案里任意引号内容当成建议（否则会造出一堆假的「建议」去问）
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _dimension_gap_advice,
)
from smartbi.scripts.restaurant_advice_is_actionable_probe import (
    _EXTRACTOR_FIXTURE,
    extract_advice,
)

# 🔴 上面那个 import 有**副作用**：探针模块在**模块顶层**就调 `bootstrap_probe()`，
#    而它会 `set_factory_id()` —— 一个**全局 ContextVar**。
#
# ⚠️ 这比 PR #2813 那次更早一步：那次污染发生在**测试运行时**，一个 autouse
#    fixture 就能兜住；这次发生在**collection（import）时**，fixture 还没上场。
#    症状是隔壁 `test_tenant_ctx_plumbing::test_contextvar_propagates_across_awaits`
#    （断言 `get_factory_id() is None`）红，而**本文件全绿** ——
#    ▎坏的是我，红的是别人。
#
# ⇒ import 之后**立刻**把它按回去。⛔ 不用 `set_factory_id(None)`：
#    那个函数把 None 翻译成 `INTERNAL_SENTINEL`，不是「未设置」。
from smartbi import tenant_ctx as _tenant_ctx  # noqa: E402

_tenant_ctx.current_factory_id.set(None)


class _Spec:
    def __init__(self, dimensions):
        self.dimensions = tuple(dimensions)


# ── 承重：对产品**真实产出**有效 ────────────────────────────────────────────

def test_the_extractor_is_alive_on_the_historical_format():
    """🔴 抽取器的**活性对照** —— 对一条已知含可照抄建议的串必须有效。

    ⚠️ 这一条原来写的是「对产品**当前**真实产出有效」。
       2026-08-18 产品**撤掉了**那句可照抄的引号问句（实测 4/4 兑现不了），
       于是那条断言当场红 —— 而它红得**理直气壮**：断言守的是一个
       我们已经不想要的行为（形态 C‴）。

    ⇒ 抽取器的活性改用**内置 fixture** 验；
      「产品现在给不给可照抄问句」由下面那条**独立**断言管。
      ▎两件事压在一个读数里，就分不清「抽取器坏了」和「产品不再承诺」。
    """
    got = extract_advice(_EXTRACTOR_FIXTURE)
    assert got, f"抽取器对历史格式失效了:\n{_EXTRACTOR_FIXTURE}"
    assert any("怎么样" in g for g in got), got
    for cand in got:
        assert 2 <= len(cand) <= 20, f"抽出来的不像一句问话: {cand!r}"


@pytest.mark.parametrize("dims,plan", [
    (("store", "dish"), ("RESTAURANT_OPS_DAYPART_PERFORMANCE",)),
    (("store",), ("RESTAURANT_OPS_INVENTORY_WARNING",)),
])
def test_the_product_no_longer_hands_out_a_copyable_question(dims, plan):
    """🔴 产品**不许**再给可照抄的引号问句 —— 📏 实测那些 4/4 兑现不了。

    ▎一句兑现不了的承诺，比不给建议更糟：他照做、失败一次，之后就不再照做，
    ▎而那时我们真正给对的建议也一起失效。

    ⛔ 逐条实测过，**没有一个构造规则能保证兑现**：
       「按食材看库存预警」2/2 ✅，但「全部门店 最近30天 按门店看营收与订单」
       补全两个槽仍然 **0/2**。⇒ 按模板发建议只是换一批兑现不了的。

    ⇒ 改成描述**动作**（「换成按食材问同一件事」）：老板照做时用的是**他自己的话**，
      话题天然在句子里 —— 📏「哪些食材最缺货」「按食材最缺货」都 2/2 ✅。
    """
    text = _dimension_gap_advice(_Spec(dims), tuple(plan))
    assert text, f"{plan} × {dims} 没产出建议 —— 用例没打中分支"
    assert not extract_advice(text), (
        f"又给出可照抄的引号问句 {extract_advice(text)} —— 实测这类 4/4 兑现不了\n{text}"
    )


@pytest.mark.parametrize("dims,plan", [
    (("store", "dish"), ("RESTAURANT_OPS_DAYPART_PERFORMANCE",)),
    (("store",), ("RESTAURANT_OPS_INVENTORY_WARNING",)),
])
def test_it_still_tells_him_what_to_do(dims, plan):
    """阴性对照：⛔ 撤掉可照抄问句**不等于**不给出路。

    交付定义⑤ 要的是「他自己要干什么」—— 撤成一句「算不出」就是把标准降了。
    """
    text = _dimension_gap_advice(_Spec(dims), tuple(plan))
    assert "问同一件事" in text, (
        "撤掉引号问句的同时把「他要干什么」也撤掉了\n" + text
    )


# ── 阴性对照：⛔ 不许把任意引号内容当成建议 ────────────────────────────────

def test_it_ignores_quoted_things_that_are_not_questions():
    """答案里到处是引号（门店名、指标名、日志术语）。

    ⛔ 把它们当成建议，会造出一批假的「建议」去问，
       然后报出一堆假的「兑现不了」—— 一个会误报的仪器比没有更糟。
    """
    noisy = (
        "「模拟·陆家嘴正大店」营收最高；口径见「毛利率」定义，"
        "另见「RESTAURANT_OPS_SALES_SUMMARY」。"
    )
    assert extract_advice(noisy) == [], extract_advice(noisy)


def test_empty_and_none_are_safe():
    assert extract_advice("") == []
    assert extract_advice(None) == []


def test_an_answer_with_no_advice_yields_nothing():
    """阴性对照：`extra` 为空时产品**不给**建议，抽取器也不许凭空造一个。"""
    text = _dimension_gap_advice(_Spec(("store",)),
                                 ("RESTAURANT_OPS_SALES_SUMMARY",))
    assert text == "", text
    assert extract_advice(text) == []


# ── 硬约束 4：三态里的 rc=2 要**主动构造一次** ─────────────────────────────

def test_the_probe_declares_all_three_exit_states():
    """⛔ 两态跑批会把「没量到」折叠进「没问题」。

    ⚠️ 三态的价值全在第三态，而第三态平时永远不会自然发生 ——
       ⇒ 至少要确认它在代码里**到得了**。这里用 AST 数 `return`/`SystemExit`
       的常量，⛔ 不 grep（注释里就写着 rc=0/1/2）。
    """
    import ast
    import inspect

    from smartbi.scripts import restaurant_advice_is_actionable_probe as probe

    tree = ast.parse(inspect.getsource(probe.main))
    returned = {
        node.value.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Return)
        and isinstance(node.value, ast.Constant)
        and isinstance(node.value.value, int)
    }
    assert returned == {0, 1, 2}, (
        f"三态没齐: 实际能返回 {sorted(returned)} —— "
        f"缺 2 就是把「没量到」折叠进了「没问题」"
    )
