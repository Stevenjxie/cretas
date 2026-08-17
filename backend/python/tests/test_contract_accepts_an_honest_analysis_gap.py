"""诚实说明「分析这一层我做不到」，应当与真的做了分析同等合规。

## 为什么（拒答归因图，2026-08-17）

8/8 有效样本里，**契约 `analysis_action` 是最直接的拦截方（4/8）**，
而接地闸**从不单独拦人**（0/8，它总是经契约拒的）。

而契约在别处**自己就有**这条哲学（`_EXPLICIT_GAP_TOKENS` 的注释原话）：

    an explicit, honest "we could not compute this" disclosure
    **satisfies the contract just as well as a real number would**
    （「缺不了就明说」本身即合规，不是失败）

**唯独 `analysis_action` 没接上** ⇒ 一句诚实的「这次算不出原因，因为缺 X」
被判不合规、整份拒掉。而那正是 goal 定义五要的**合格回答**。

## 🔴 这个文件最重要的是那条阴性对照

2026-08-10 事故（仓里有记录）：用户问「本月营收比上月低是什么原因」，
兜底回了「本月全部门店营收合计 ¥6,490,180.61。」—— **用一个数字回答了「为什么」**。

⇒ 放宽 `analysis_action` 最大的风险就是重演它。
所以逃生口要求「做不到」和「分析域词」出现在**同一句**里，
而 `test_the_2026_08_10_incident_shape_is_still_rejected` 就是钉这一条的。
**少了它，这次修复会从 B 滑成 C（把闸关掉）。**

设计卡：`docs/decisions/2026-08-17-契约的analysis_action要认诚实说明-设计卡.md`
"""
import pytest

from smartbi.gold.restaurant.answer_contract import (
    _analysis_action_present,
    _explicit_analysis_gap,
)


class _Spec:
    """只带这条判据要看的字段。"""

    def __init__(self, analysis_action):
        self.analysis_action = analysis_action


# ── 正向：诚实说明应当合规 ──────────────────────────────────────────
@pytest.mark.parametrize("action", ["diagnose", "optimize"])
def test_an_honest_statement_that_analysis_is_impossible_passes(action):
    """🔴 承重：明说「这一层做不到」= 合格回答（定义五）。"""
    text = "这次算不出下降的原因，因为缺少分渠道的成本数据。"
    assert _analysis_action_present(_Spec(action), text) is True


@pytest.mark.parametrize("action", ["diagnose", "optimize"])
def test_an_honest_statement_about_missing_actions_passes(action):
    text = "我给不出优化动作，缺少各门店的人力排班数据。"
    assert _analysis_action_present(_Spec(action), text) is True


# ── 🔴 阴性对照：8-10 事故的形状必须仍然被拒 ────────────────────────
def test_the_2026_08_10_incident_shape_is_still_rejected():
    """🔴 **本文件最重要的一条**：用一个数字回答「为什么」必须仍然不通过。

    实测原形（2026-08-10）：用户问「本月营收比上月低是什么原因」，
    产品回「本月全部门店营收合计 ¥6,490,180.61。」

    ⚠️ 注意这段文本里**确实**出现了「缺」——但它在**另一句**里，
    不是在讲分析那一句。逃生口要求两个条件**同句**，正是为了挡住它。

    ⛔ 少了这条，本次修复会从 B（认诚实说明）滑成 C（把闸关掉）。
    """
    text = "本月全部门店营收合计 ¥6,490,180.61。另有部分菜品缺成本卡。"
    assert _explicit_analysis_gap(text) is False, (
        "「缺」和「分析域词」不在同一句里，⛔ 不该放行 —— 这正是 8-10 事故的形状")
    assert _analysis_action_present(_Spec("diagnose"), text) is False


def test_a_pure_number_answer_is_still_rejected():
    """阴性对照二：完全不提分析的纯数字答案，必须不通过。"""
    text = "本月全部门店营收合计 ¥6,490,180.61，环比下降 3.2%。"
    assert _analysis_action_present(_Spec("diagnose"), text) is False


def test_a_topic_word_without_any_inability_marker_is_rejected():
    """阴性对照三：只提「原因」而没说做不到，⛔ 不算诚实说明。

    否则「原因如下：营收 ¥X」这种也能过 —— 那就是又一个 8-10。
    """
    text = "原因如下：本月营收 ¥6,490,180.61。"
    assert _explicit_analysis_gap(text) is False
    assert _analysis_action_present(_Spec("diagnose"), text) is False


# ── 既有白名单不能被这次改动弄坏 ────────────────────────────────────
def test_the_existing_whitelist_still_works():
    """阳性对照：真的做了分析（含白名单词）照样通过。

    ⛔ 少了它，这个文件只验了新逃生口，而**旧路径坏掉也看不出来**。
    """
    assert _analysis_action_present(_Spec("diagnose"), "原因拆解如下：…") is True
    assert _analysis_action_present(_Spec("optimize"), "优化建议：…") is True


def test_no_analysis_requested_is_unaffected():
    """没要求分析的意图，行为逐字不变。"""
    assert _analysis_action_present(_Spec(None), "本月营收 ¥100。") is True
    assert _analysis_action_present(_Spec("lookup"), "本月营收 ¥100。") is True
