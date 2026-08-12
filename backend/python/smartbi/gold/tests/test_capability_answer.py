"""「我这儿有的是…」这份清单必须是**算出来的**，不是又一张手写表。

## 为什么这道闸的写法要小心

被替换掉的缺陷是一张硬编码清单。**换一张手写表上去，缺陷原样保留、只是措辞变好听**。
而最容易配的那种断言 ——

    assert "营收" in text          # ❌ 恒真式

—— 对这件事**完全沉默**：清单是常量时它也绿。本仓已经写过一个这种闸，一次都没红过。

**能红的写法（owner 2026-08-12 指定）**：抽掉租户的覆盖来源，清单必须随之变化或变空。
不变就说明它在测常量。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.capability_answer import (
    MARGIN_NOT_PROFIT,
    computable_labels,
    partial_coverage_answer,
    missing_capability_labels,
    render_capability_refusal,
    should_use_capability_refusal,
)


class _FakeMetric:
    def __init__(self, label, requires):
        self.label, self.requires = label, requires


_METRICS = {
    "revenue": _FakeMetric("营收", ("fact_pos_transaction.net_amount",)),
    "wastage": _FakeMetric("食材损耗", ("fact_wastage.qty",)),
    "net_profit": _FakeMetric("净利润", ("fact_pos_transaction.net_amount",)),
}
_SCHEMA = {"fact_pos_transaction.net_amount", "fact_wastage.qty"}
_FULL = {"fact_pos_transaction": 100, "fact_wastage": 20}


def test_capability_list_is_computed_not_constant():
    """🔴 承重: 抽掉租户的覆盖来源，清单必须跟着变。

    这是本文件唯一测得出「它是不是常量」的写法。

    变异实测: 把 `computable_labels` 改成 `return ("营收", "食材损耗")`
      → 红:「抽掉损耗数据后清单没变 —— 它是常量，不是算出来的」
    """
    full = computable_labels(_SCHEMA, _FULL, metrics=_METRICS, unsupported=("net_profit",))
    assert "营收" in full and "食材损耗" in full

    # 租户层：损耗表这个租户一行都没有
    no_wastage = computable_labels(
        _SCHEMA, {**_FULL, "fact_wastage": 0}, metrics=_METRICS, unsupported=("net_profit",))
    assert no_wastage != full, "抽掉损耗数据后清单没变 —— 它是常量，不是算出来的"
    assert "食材损耗" not in no_wastage
    assert "营收" in no_wastage, "只该少掉没数据的那一项，不该整份塌掉"


def test_schema_layer_also_gates():
    """🔴 承重: schema 层（列在不在库里）同样要能改变清单。

    ⛔ 与上一条分开写: 只测租户层的话, 把 schema 检查整段删掉也不会红。
    """
    missing_col = computable_labels(
        {"fact_pos_transaction.net_amount"}, _FULL,
        metrics=_METRICS, unsupported=("net_profit",))
    assert "食材损耗" not in missing_col, "库里没有那一列, 它不该出现在「我这儿有的是」里"
    assert "营收" in missing_col


def test_unsupported_metric_never_appears_as_available():
    """🔴 承重: 用户问的那个算不出来的指标，绝不能出现在「我这儿有的是」里。

    那就是「顶替」——系统对用户有不顶替的承诺。
    """
    out = computable_labels(_SCHEMA, _FULL, metrics=_METRICS, unsupported=("net_profit",))
    assert "净利润" not in out


def test_two_tenants_with_different_coverage_get_different_lists():
    """🔴 承重: 覆盖度不同的两个租户，清单必须不同。

    ⚠️ 这条是 owner 那条「两句不同问句输出逐字相同 = 复发信号」的**修正版**。
       原判据会误报：「我这儿有的是…」是**租户属性不是问句属性**，
       同一个租户问两句不支持的问题，这一段本来就该一模一样（§9.9 ③
       说的是「边界的另一面」）。真正要防的是「它退回成全局常量」——
       那用**两个覆盖度不同的租户**才测得出来。
    """
    rich = computable_labels(_SCHEMA, _FULL, metrics=_METRICS)
    poor = computable_labels(_SCHEMA, {"fact_pos_transaction": 5, "fact_wastage": 0},
                             metrics=_METRICS)
    assert rich != poor, "两个覆盖度不同的租户拿到同一份清单 —— 它退回成常量了"


def test_unknowable_coverage_yields_empty_not_a_guess():
    """查不动 → 空清单。⛔ 不许猜一个出来 ——「我这儿有的是」是一句承诺。"""
    assert computable_labels(set(), {}, metrics=_METRICS) == ()


# ── 模板（§9.9 ①②③；④ 是按钮，不在正文） ──────────────────────────────


def test_refusal_body_has_no_findings_block():
    """🔴 承重: 拒答正文不许附「顺带 N 件事」。

    §9.9: 拒答带一堆发现读起来像是回答了，人会以为拿到了东西。
    那两条发现是真算出来的，所以**降级成按钮**（调用方放进 followups），不是删掉。
    """
    text = render_capability_refusal(["净利润（缺少费用、税费及其他收支）"], ["营收", "菜品销量"])
    assert "顺带" not in text
    assert "还有" not in text or "件事" not in text


def test_refusal_says_boundary_not_what_i_can_compute_for_you():
    """🔴 承重: 第 ③ 段用「我这儿有的是」，不用「现在能算的」。

    被替换掉的那句正是栽在这个口径上 —— 它读起来像「针对你这个问题我能算这些」，
    而那份清单与问句无关。
    """
    text = render_capability_refusal(["翻台率（缺少桌台…）"], ["营收"])
    assert "我这儿有的是" in text
    assert "能算的" not in text, "又用回了那个会被当成「针对你这个问题」的口径"


def test_empty_capability_list_drops_the_section_entirely():
    """🔴 承重: 查不动时整段省掉，不写「暂时什么都算不了」。

    空清单的成因是「查不动」，那时说任何一侧都是猜。

    变异实测: 让空清单时输出「我这儿有的是：。」
      → 红:「清单为空时不该出现「我这儿有的是」这一段」
    """
    text = render_capability_refusal(["净利润"], [])
    assert "我这儿有的是" not in text, "清单为空时不该出现「我这儿有的是」这一段"
    assert "算不出来" in text and "缺的是" in text


@pytest.mark.parametrize("jargon", ["可靠覆盖", "相邻指标", "维度", "可验证结果", "相邻分析"])
def test_refusal_carries_no_contract_jargon(jargon):
    """§9.9 明列的五个词，一个都不许出现在拒答里。"""
    text = render_capability_refusal(["净利润（缺少费用、税费及其他收支）"], ["营收", "食材损耗"])
    assert jargon not in text


# ── 什么时候该接管：只在能力缺口 ────────────────────────────────────────


def test_capability_refusal_only_fires_for_a_real_capability_gap():
    """🔴 承重: 缺时间/缺门店那类澄清**不许**被拒答模板顶掉。

    那些不是「我做不到」, 是「再说清楚点」—— 换成拒答模板等于把一句
    「你再说说」变成一句关门的话。

    变异实测: 把 `should_use_capability_refusal` 改成 `return True`
      → 红:「没有能力缺口时不该走拒答模板」
    """
    assert should_use_capability_refusal(("net_profit",)) is True
    assert should_use_capability_refusal(()) is False, "没有能力缺口时不该走拒答模板"
    assert should_use_capability_refusal(None) is False


def test_unknown_capability_code_is_dropped_not_leaked():
    """🔴 承重: 翻不成店长话的能力码要**丢掉**, 不能把裸码透给用户。

    变异实测: 去掉 `if item in _UNSUPPORTED_REQUIREMENT_LABELS` 过滤
      → 红: 裸码 'some_new_code' 出现在标签里
    """
    labels = missing_capability_labels(("net_profit", "some_new_code"))
    assert all("some_new_code" not in x for x in labels), f"裸码漏给用户了: {labels}"
    assert labels, "已登记的那个也被一起丢了"


def test_only_unknown_codes_means_no_refusal_template():
    """全是翻不出来的码 → 不接管(宁可走原澄清, 也不端一句没有内容的拒答)。"""
    assert should_use_capability_refusal(("some_new_code",)) is False


# ── §9.2 第二档：给能算的 + 明说另一个为什么算不出 ──────────────────────

_FACTS = [
    {"label": "毛利", "value": 680000, "unit": "元"},
    {"label": "毛利率", "value": 67.7, "unit": "%"},
]


def test_the_refusal_comes_before_any_number():
    """🔴 承重: **第一句必须是「给不了 + 为什么」**, 数字在其后。

    owner 2026-08-12:「先甩 ¥ 数再解释, 用户读到的就是『赚了这么多』——
    那是『相邻指标顶替』换个位置重演。」

    变异实测: 把两段调换顺序
      → 红:「第一个数字出现在拒答之前」
    """
    text = partial_coverage_answer(
        "是否赚钱的判断", "净利润缺少费用、税费及其他收支", _FACTS)
    assert text.startswith("**是否赚钱的判断算不出来**"), (
        f"第一句不是「给不了 + 为什么」, 而是: {text.splitlines()[0]!r}")
    first_digit = min(
        (text.index(str(d)) for d in ("680000", "67.7") if str(d) in text),
        default=len(text))
    assert text.index("算不出来") < first_digit, (
        f"第一个数字出现在拒答之前 —— 用户读到的会是「赚了这么多」: {text[:70]!r}")


def test_the_qualifier_sits_with_the_numbers_not_only_at_the_top():
    """🔴 承重: 毛利≠净利那层限定语必须**贴着数字**, 不能只写在开头。

    变异实测: 把限定语从小标题里拿掉
      → 红:「限定语没有和数字待在一起」
    """
    text = partial_coverage_answer(
        "是否赚钱的判断", "净利润缺少费用、税费及其他收支", _FACTS)
    header = [ln for ln in text.splitlines() if ln.startswith("能算的是")]
    assert header, "没有「能算的是」这一段"
    assert MARGIN_NOT_PROFIT in header[0], "限定语没有和数字待在一起: " + header[0]


def test_numbers_come_only_from_structured_facts():
    """🔴 承重: 渲染出来的每个数字都必须能在 facts 里找到出处。

    这是「LLM 不许产生自家数字」这条红线在本出口上的落地方式:
    **没有入口** —— 叙述文本根本不参与拼接。

    变异实测: 让它把被驳回的 answer_text 也拼进去
      → 红:「这些数字在结构化事实里没有出处」
    """
    import re
    text = partial_coverage_answer("是否赚钱的判断", "缺费用", _FACTS)
    allowed = {str(f["value"]) for f in _FACTS}
    found = set(re.findall(r"\d+(?:\.\d+)?", text))
    orphan = {n for n in found if n not in allowed}
    assert not orphan, f"这些数字在结构化事实里没有出处: {sorted(orphan)}"


def test_no_facts_means_no_partial_answer():
    """🔴 承重: 一个数都没有时返回 None, 让调用方走整份拒答。

    ⛔ 空着的「能算的是：」是一句看起来给了东西、实际什么都没有的话 ——
       那正是这一轮要修的缺陷本身。
    """
    assert partial_coverage_answer("是否赚钱的判断", "缺费用", []) is None
    assert partial_coverage_answer(
        "是否赚钱的判断", "缺费用", [{"label": "毛利", "value": "—"}]) is None
