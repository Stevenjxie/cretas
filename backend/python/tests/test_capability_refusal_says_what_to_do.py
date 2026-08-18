"""能力拒答要说清**三件事** —— 第三件此前没有。

交付定义⑤：答不了的时候，说清**缺什么 · 怎么拿到 · 他自己要干什么**。

## 📏 缺陷（prod 逐条读，MOCK_REST，24 问句 1 轮）

「翻台率怎么样」→ 129 字：

```
**翻台率（缺少桌台、开台/结账时间、就餐轮次和可用桌数）现在算不出来。**
缺的是：翻台率（缺少桌台、开台/结账时间、就餐轮次和可用桌数）
我这儿有的是：营收和折扣（比如营收）、成本和毛利（比如食材成本）、
              损耗（比如损耗成本）、客流和销量（比如销量）。
```

前两件齐了，**第三件没有** —— 老板知道我们有什么，
却**不知道下一步该打什么字**。

## ⛔ 不给可照抄的引号问句

本轮刚撤掉那一类（📏 实测 4/4 兑现不了，见 `_dimension_gap_advice` / PR #2818）。
这里只描述**动作**，且动作指向的是 `available_groups` ——
它是**查库算出来的**（每一列的非空值），⛔ 不是手写表。
"""
from __future__ import annotations

from smartbi.gold.restaurant.capability_answer import render_capability_refusal

_GROUPS = (("营收和折扣", "营收"), ("成本和毛利", "食材成本"), ("损耗", "损耗成本"))


def test_it_tells_him_what_to_do():
    """🔴 承重：第三件事必须在。"""
    text = render_capability_refusal(["翻台率"], _GROUPS)
    assert "直接说它的名字" in text, "没说他要干什么\n" + text


def test_the_example_comes_from_the_computed_list():
    """⛔ 例子必须取自**查库算出来的**那份清单，不是写死的。

    ⚠️ 与「建议词写死成『门店或菜品』」同型 —— 那次写死之后，
       当 `extra` 恰好是门店时它建议老板「把门店换成门店」。
    """
    a = render_capability_refusal(["翻台率"], _GROUPS)
    b = render_capability_refusal(["翻台率"], (("客流和销量", "销量"),))
    assert "营收" in a and "销量" in b, (a, b)
    assert a != b


def test_it_does_not_hand_out_a_copyable_question():
    """⛔ 不许回到「可照抄的引号问句」—— 📏 那一类实测 4/4 兑现不了。

    判据：引号里的东西必须是**一个名字**（短），⛔ 不是一句问话。
    """
    text = render_capability_refusal(["翻台率"], _GROUPS)
    import re

    quoted = re.findall(r"[「『]([^」』]*)[」』]", text)
    assert quoted, "连例子都没有\n" + text
    for q in quoted:
        assert len(q) <= 8, f"引号里是一句问话而不是一个名字: {q!r}"
        assert "怎么样" not in q and "?" not in q and "？" not in q, q


def test_前两件事没被挤掉():
    """阴性对照：加第三件 ⛔ 不许把前两件挤掉。"""
    text = render_capability_refusal(["翻台率"], _GROUPS)
    assert "现在算不出来" in text
    assert "缺的是：翻台率" in text
    assert "我这儿有的是" in text


def test_no_available_groups_says_nothing_extra():
    """🔴 阴性对照：清单为空时**整段省掉** —— ⛔ 不许凭空给一个动作。

    ⚠️ 空清单的成因是「查不动」，那时说任何一侧都是猜
    （模块 docstring 里那条纪律）。
    """
    text = render_capability_refusal(["翻台率"], ())
    assert "直接说它的名字" not in text, "清单为空却给了动作 —— 那是凭空的\n" + text
    assert "我这儿有的是" not in text
    # 阳性对照：前两段还在，说明不是整个函数坏了
    assert "现在算不出来" in text and "缺的是" in text
