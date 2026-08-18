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
    extract_advice,
)


class _Spec:
    def __init__(self, dimensions):
        self.dimensions = tuple(dimensions)


# ── 承重：对产品**真实产出**有效 ────────────────────────────────────────────

@pytest.mark.parametrize("dims,plan", [
    # `both` 非空那一支：「想看的话分开问，例如先问「按门店怎么样」。」
    (("store", "dish"), ("RESTAURANT_OPS_DAYPART_PERFORMANCE",)),
    # `supported` 非空、`both` 为空那一支：「换成问「按食材怎么样」我就能答。」
    (("store",), ("RESTAURANT_OPS_INVENTORY_WARNING",)),
])
def test_it_extracts_the_advice_the_product_actually_emits(dims, plan):
    """🔴 ⛔ 不拿我编的例句测 —— 直接调产品那个函数拿真串。

    ⚠️ 这正是本仓记过的形态：闸在断言里**自己模拟**了被测行为，
       于是它绿着而生产那条路根本没调它。
    """
    text = _dimension_gap_advice(_Spec(dims), tuple(plan))
    assert text, f"{plan} × {dims} 没产出建议 —— 用例没打中分支"
    got = extract_advice(text)
    assert got, f"抽取器对产品真实文案失效了:\n{text}"
    assert any("怎么样" in g for g in got), got


def test_the_extracted_string_is_a_question_not_a_fragment():
    """抽出来的东西要能**原样拿去问** —— 否则下一步就是拿碎片去查库。"""
    text = _dimension_gap_advice(_Spec(("store", "dish")),
                                 ("RESTAURANT_OPS_DAYPART_PERFORMANCE",))
    got = extract_advice(text)
    assert got
    for cand in got:
        assert 2 <= len(cand) <= 20, f"抽出来的不像一句问话: {cand!r}"
        assert "、" not in cand or "怎么样" in cand, cand


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
