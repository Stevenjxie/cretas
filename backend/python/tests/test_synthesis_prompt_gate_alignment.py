"""生成侧 prompt 必须和叙事接地闸说同一套话。

2026-07-31 prod 实测(`/www/wwwroot/cretas/python-prod.log`): 经营诊断被调用
**94 次**, 叙事接地闸拒绝 **115 次** —— 这条最有价值的问题类型在线上大概率
拿不到答案。按违规类型拆:

    缺失维度被当作事实        109   ← prompt 第 7 条已覆盖
    未标注为假设的预算或目标   90   ← **prompt 里根本没有这条**
    无保留因果断言             64   ← prompt 第 6 条已覆盖

也就是说其中一大类**不是模型不听话, 是从没人告诉它规则**。补上之后, 真正要防的
是这两边再次漂开 —— 闸改了词表而 prompt 没跟(或反过来), 表现就是「答案一直被
拦而没人知道为什么」, 和这次一模一样。

所以本测试钉的不是措辞, 是**两边引用同一组词**这件事。
"""
from __future__ import annotations

import pytest

from smartbi.agent.synthesis_engine import (
    HONEST_LABEL_CLAUSE,
    _CAUSAL_HEDGE_RE,
    _MISSING_DISCLOSURE_RE,
    _PRESCRIBED_NUMBER_ASSUMPTION_RE,
)


def _mentioned(word: str) -> bool:
    return word in HONEST_LABEL_CLAUSE


@pytest.mark.parametrize("word", ["假设", "建议值", "暂定", "试点参数", "可调整"])
def test_prompt_offers_wording_the_assumption_gate_accepts(word):
    """prompt 教的词必须真的能过闸 —— 否则模型照做了还是被拦。"""
    assert _mentioned(word), f"prompt 没提供 {word!r} 这个说法"
    assert _PRESCRIBED_NUMBER_ASSUMPTION_RE.search(f"{word}：14天"), (
        f"闸不接受 prompt 教的 {word!r}"
    )


@pytest.mark.parametrize("word", ["可能", "推测", "待验证", "相关不等于因果"])
def test_prompt_offers_wording_the_causal_gate_accepts(word):
    assert _mentioned(word), f"prompt 没提供 {word!r}"
    assert _CAUSAL_HEDGE_RE.search(f"主要靠堂食拉动，{word}")


@pytest.mark.parametrize("word", ["未提供", "无法判断", "需要补充"])
def test_prompt_offers_wording_the_missing_dimension_gate_accepts(word):
    assert _mentioned(word), f"prompt 没提供 {word!r}"
    assert _MISSING_DISCLOSURE_RE.search(f"损耗{word}")


def test_every_gate_rule_is_represented_in_the_prompt():
    """三条闸都必须在 prompt 里有对应条款。

    这是本测试的核心断言: 闸有三条规则, prompt 就必须有三条对应说明。
    2026-07-31 之前只有两条 —— 第三条(预算/目标)缺失, 于是线上 90 次拒绝里的
    模型**没有任何办法**通过。
    """
    gates = {
        "因果": ("相关不等于因果", "推测"),
        "缺失维度": ("未提供", "无法判断"),
        "预算或目标": ("建议值", "假设"),
    }
    missing = [
        name for name, words in gates.items()
        if not any(_mentioned(word) for word in words)
    ]
    assert not missing, (
        f"闸有规则但 prompt 没教怎么满足: {missing} —— "
        f"模型无从遵守, 答案会被静默拦掉"
    )


def test_prompt_shows_a_concrete_rewrite_not_just_a_ban():
    """光说「禁止」没用, 得给出照做的样子。

    实测被拦的正是「试点14天」这种写法, 所以 prompt 里要有它的正确形态。
    """
    assert "试点参数" in HONEST_LABEL_CLAUSE
    assert "14天" in HONEST_LABEL_CLAUSE, "缺少可照抄的正例"
