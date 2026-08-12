"""澄清分支发给店长的话，必须过 sanitize。

## 这道闸补的是什么（2026-08-12 prod 实测）

打真接口把答案存下来再扫，抓到店长真的收到了这句：

    …补齐括号内明细后可以继续；也可以明确只分析当前已有的**维度**。

`维度` 是内部概念词。而扫这个词的源码闸
`test_no_internal_jargon_in_customer_text` **一直是绿的**。

**为什么它绿**：它的判据是「源码里的串**经 sanitize 之后**不含内部词」，
而 `sanitize_customer_ai_text("…已有的维度。")` 确实会改写成「…已有的方面。」。

**闸、清洗函数、词表三样各自都是对的，坏在没有装在同一条路上** ——
澄清分支把 `spec.clarification_question` 直接当 `answer_text`，
从来没调用过 sanitize（同一个文件里另一条分支调了）。

> 判据：**静态闸只要在断言里重放了一个运行时步骤，它就在假设那个步骤真的会跑。**
> 断言里出现「先跑一遍某个处理函数再检查」时，要回头问一句：
> 生产上谁保证这个函数被调用了？答「按约定」= 这道闸测的是约定，不是事实。

## 与源码闸的分工

- 源码闸：扫**所有**面向用户的串，判它们「清洗之后」干不干净 —— 面广，但假设清洗发生了。
- 本闸：拿**真正的生产者**（`_unsupported_requirement_question`）的输出，
  过**真正的出口函数**（`clarification_answer_text`），检查最终结果 —— 面窄，但不假设。

两道都要：只有源码闸会漏掉「没接上」，只有本闸会漏掉「别的串」。
"""
from __future__ import annotations

import pytest

#: ⚠️ 从源码闸那边借词表，**不在这里再写一份字面量** —— 两份必漂，
#:    而这一整轮拆掉的正是这种腐烂。
#:    (它在 PR#2527 里已经搬去 `smartbi.gold.customer_text`；那个 PR 合并后
#:     这行可以改成从 customer_text 导入。本分支 off origin/main，还够不着。)
from smartbi.gold.tests.test_no_internal_jargon_in_customer_text import (
    _INTERNAL_VOCAB as INTERNAL_VOCAB,
)
from smartbi.gold.restaurant.restaurant_intent import (
    _UNSUPPORTED_REQUIREMENT_LABELS,
    _unsupported_requirement_question,
)
from smartbi.gold.restaurant.restaurant_intent_service import clarification_answer_text


@pytest.mark.parametrize("requirement", sorted(_UNSUPPORTED_REQUIREMENT_LABELS))
def test_unsupported_requirement_reaches_the_manager_cleaned(requirement):
    """🔴 承重: 每一种「这项分析不了」的反问，到店长手里都不含内部概念词。

    ⛔ 参数化跑**全部** requirement，不是抽一个 —— 08-12 那次是 `table_turnover`
       和 `net_profit` 两种同时中招，抽样会让人以为只是某一条文案的问题。

    变异实测: 把 `clarification_answer_text` 里的 `sanitize_customer_ai_text(text)`
      改回 `text`
      → 红:「发给店长的反问里有内部概念词 ['维度']」—— 红在「清洗没接上」这个行为上。
    """
    raw = _unsupported_requirement_question((requirement,))
    out = clarification_answer_text(raw)
    hits = [w for w in INTERNAL_VOCAB if w in out]
    assert not hits, f"发给店长的反问里有内部概念词 {hits}: {out[:120]!r}"


def test_the_raw_producer_really_does_emit_the_word():
    """阴性对照: 生产者**确实**产出了 `维度` —— 否则上一条全绿说明不了任何事。

    ⛔ 没有这一条，上面那组可能只是因为「反正也没有那个词」而恒真。
       （本仓判据：闸绿最常见的原因是它测的东西根本不存在。）
    """
    raw = _unsupported_requirement_question(("table_turnover",))
    assert "维度" in raw, (
        "生产者不再输出「维度」了 —— 那上面那组参数化断言已经失去意义，"
        "该重新找一个真实的内部概念词做锚点，而不是留着一组恒真的绿")


def test_fallback_clarification_is_cleaned_too():
    """没有具体反问时的兜底文案也走同一个出口（别绕过去自己拼）。"""
    out = clarification_answer_text(None)
    assert out
    assert not [w for w in INTERNAL_VOCAB if w in out]


def test_action_warning_still_prepends():
    """清洗不能顺手把「未执行任何操作」这条声明弄丢 —— 它是读写分离的护栏。"""
    out = clarification_answer_text("想看哪个时间范围？", "本次未执行任何操作")
    assert out.startswith("**本次未执行任何操作**")
    assert "想看哪个时间范围？" in out
