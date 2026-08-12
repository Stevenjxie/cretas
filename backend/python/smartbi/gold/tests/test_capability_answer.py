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
    computable_labels,
    render_capability_refusal,
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
