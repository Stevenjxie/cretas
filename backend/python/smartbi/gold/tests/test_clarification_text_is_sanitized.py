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
from smartbi.gold import customer_text as _copy

#: 本轮收敛的那批面向店长的固定说法。⛔ 从模块现算，不手抄一份名字清单 ——
#: 手抄的会漏掉以后新加的常量，而漏掉的那个就没有「不许为空」这道保护。
_COPY_CONSTANTS = {
    name: getattr(_copy, name) for name in dir(_copy)
    if name.isupper() and isinstance(getattr(_copy, name), str) and not name.startswith("_")
}


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


def test_the_cleaning_is_actually_wired_up():
    """🔴 承重: 清洗**真的接在这条路上** —— 喂一个带内部词的串，出口必须洗掉它。

    ⚠️ 2026-08-12 重构的理由，值得留着：
       这里原本是一条阴性对照，断言**生产者确实产出「维度」**（否则上面那组
       参数化就是恒真的绿）。当天下午把源码里那批串白话化之后，生产者不再产出
       「维度」—— **那条对照当场变红，而它红得对**：它在说「你上面那组现在
       什么都没测了」。

       正确的响应不是删掉它，是把「测清洗有没有接上」与「测生产者干不干净」
       **拆成两条各自有效的断言**：
         · 本条：喂合成的脏串 → 出口必须洗掉（永远有效，与文案改不改无关）
         · 上面那组：真实生产者的输出干不干净（① 的成果，现在从源头就干净）

    变异实测: 把 `clarification_answer_text` 里的 `sanitize_customer_ai_text(text)`
      改回 `text` → 红「清洗没有接在这条路上」。
    """
    dirty = "也可以明确只分析当前已有的维度。"
    out = clarification_answer_text(dirty)
    assert "维度" not in out, f"清洗没有接在这条路上: {out!r}"
    assert "方面" in out, f"清洗接上了但没改写成店长话: {out!r}"


def test_the_producer_is_clean_at_the_source_now():
    """🔴 承重: ① 的成果 —— 生产者**从源头**就不写内部词，不靠下游清洗。

    owner 2026-08-12 裁定：「不是『写了黑话再洗掉』，是一开始就别写。
    `sanitize()` 的存在本身就是承认我们会写黑话；而清洗靠词表、词表靠人想得到，
    三层里最弱的一层决定整体。」

    ⛔ 断言的是**未经清洗的原文**（`clarification_answer_text` 会洗，
       拿洗过的去断言就测不出源头干不干净了）。
    """
    for requirement in sorted(_UNSUPPORTED_REQUIREMENT_LABELS):
        raw = _unsupported_requirement_question((requirement,))
        hits = [w for w in INTERNAL_VOCAB if w in raw]
        assert not hits, f"源码里还写着内部概念词 {hits}: {raw[:100]!r}"


def test_fallback_clarification_is_cleaned_too():
    """没有具体反问时的兜底文案也走同一个出口（别绕过去自己拼）。"""
    out = clarification_answer_text(None)
    assert out
    assert not [w for w in INTERNAL_VOCAB if w in out]


@pytest.mark.parametrize("name", sorted(_COPY_CONSTANTS))
def test_copy_constants_are_not_empty(name):
    """🔴 承重: 文案常量不许是空串。

    🔴 2026-08-12 变异实测暴露的洞，值得留着：
       把这批文案收成常量之后，断言从 `assert "不会用营业额" in text` 改成了
       `assert NO_SUBSTITUTION in text` —— 防住了「改文案时断言不跟着改」的漂移。
       但变异「`NO_SUBSTITUTION = ""`」**没有让任何一条断言变红**：
       `assert "" in text` 恒真，于是**全部 5 条引用它的断言同时静默通过**。

       引用常量把「一条断言会不会漂」换成了「一个常量会不会被清空」——
       后者影响面更大（一处改动同时废掉 5 条断言），所以必须单独钉住。

    判据: **凡是拿变量做 `in` 断言，就要有一条守住那个变量不为空。**
    """
    value = _COPY_CONSTANTS[name]
    assert value, f"{name} 是空串 —— 所有 `assert {name} in text` 会全部恒真"
    assert len(value) >= 4, f"{name} 短到没有区分力: {value!r}"


def test_action_warning_still_prepends():
    """清洗不能顺手把「未执行任何操作」这条声明弄丢 —— 它是读写分离的护栏。"""
    out = clarification_answer_text("想看哪个时间范围？", "本次未执行任何操作")
    assert out.startswith("**本次未执行任何操作**")
    assert "想看哪个时间范围？" in out
