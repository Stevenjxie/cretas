"""接地闸不许把「诚实声明缺数据」判成「把缺失维度当作事实」。

## 📏 缺陷（prod 实测，MOCK_REST，绕过叙述缓存 **3/3 稳定复现**，n=1811 逐次相同）

「我要不要关掉最差的那家店」整份答案被这道闸丢弃，换成兜底：

```
synthesis rejected by narrative grounding gate: violations=[
  '缺失维度被当作事实（review）：评价：评价数据还没接入',
  '缺失维度被当作事实（review）：没法判断模拟·打浦桥日月光店口碑好不好',
  '缺失维度被当作事实（review）：3. 补评价数据：把大众点评/美团评价导出传上来',
  '缺失维度被当作事实（review）：看模拟·打浦桥日月光店差评是不是集中在服务或菜品']
```

**4 条里 3 条是误报** —— 那三句恰恰在说「这一维没有数据」，是闸要抓的东西的**反面**。
根因是豁免词表漏词：

| 实际写的 | 表里要的 |
|---|---|
| 「还没接入」 | `没有(?:接入)` |
| 「没法判断」 | `不能判断` / `无法` |
| 「补评价数据」 | `待补` / `需补` / `需要补充` |

⇒ 老板看到的第一句变成「**刚才生成的说明和系统数据对不上**」，
而且整篇**没有回答**他问的那件事。

## ⛔ 补豁免最容易滑成把闸关掉

所以本文件的**承重**不是那三条豁免，是最后那组**阴性对照**：
真的把缺失维度当作事实（直接报一个评价分 / 拿它下结论）**仍然必须被拦**。

设计卡: `docs/decisions/2026-08-18-接地闸误报把整份答案换成兜底-设计卡.md`
"""
from __future__ import annotations

import pytest

from smartbi.agent.synthesis_engine import _MISSING_DISCLOSURE_RE


# ── 🔴 承重：实测撞出来的那三句必须被豁免 ──────────────────────────────────

@pytest.mark.parametrize("clause,why", [
    ("评价：评价数据还没接入", "「还没接入」—— 表里原来只有「没有接入」"),
    ("没法判断模拟·打浦桥日月光店口碑好不好", "「没法」—— 表里原来只有「不能判断」/「无法」"),
    ("3. 补评价数据：把大众点评/美团评价导出传上来", "「补X数据」—— 表里原来只有「待补」/「需补」"),
])
def test_the_three_measured_phrases_are_exempt(clause, why):
    """📏 这三句**全部来自 prod 实测**，⛔ 不是我想象中「可能还有哪些说法」。"""
    assert _MISSING_DISCLOSURE_RE.search(clause), (
        f"这句在声明缺数据，却没被豁免（{why}）：{clause}"
    )


def test_the_old_phrasings_still_work():
    """阳性对照：原来就豁免的说法⛔ 不许被我改坏。"""
    for clause in ("评价数据没有接入", "无法判断口碑", "需要补充评价数据",
                   "缺少评价标签", "数据不足", "尚无评价数据"):
        assert _MISSING_DISCLOSURE_RE.search(clause), clause


# ── 🔴 阴性对照：真违规仍然必须被抓到（承重中的承重） ─────────────────────

@pytest.mark.parametrize("clause", [
    "评价分 4.5，口碑很好",
    "大众点评评分 4.8 分说明服务没问题",
    "差评集中在出餐慢",
    "口碑好是因为菜品评价高",
])
def test_real_fabrication_is_still_caught(clause):
    """⛔ 补豁免最容易滑成把闸关掉。

    这些句子**直接给出了缺失维度的数值或结论** —— 那正是闸存在的理由。
    它们**不含**任何缺数据声明，所以必须落不到豁免上。
    """
    assert not _MISSING_DISCLOSURE_RE.search(clause), (
        f"真的在编缺失维度的数，却被豁免了：{clause}"
    )


def test_the_exemption_is_not_a_blanket():
    """🔴 判据能红吗：拿一句**什么都不含**的白话，它不该被豁免。

    ⚠️ 这条守的是「正则没有变成 `.*`」—— 一个恒真的豁免等于闸被关掉，
       而那种失效**不报错**，只是从此再也不拦任何东西。
    """
    assert not _MISSING_DISCLOSURE_RE.search("今天营业额两百万")


# ── 兜底文案：⛔ 内部诊断串不给老板 ───────────────────────────────────────

def test_only_the_gate_reason_is_replaced():
    """🔴 只换**门禁**那一种，⛔ 不是一刀切全删。

    ⚠️ 我第一版就是一刀切 —— 三条既有断言当场红，因为另外两句
       （「今天的智能分析次数已经用完」「智能分析暂时有点忙」）
       **对老板有用**：他据此知道该等一会儿还是明天再来。

    形态 C‴ 的判据是「这条断言守的是需求，还是历史」：
       前两条守的是**需求**（改方案）；第三条守的是**历史**（改断言）。
    """
    from smartbi.agent.synthesis_engine import _FALLBACK_OPENING_OVERRIDES as OV

    assert OV.get("叙述未通过数据因果门禁") == "这一轮我没有直接下结论"
    # ⛔ 阴性对照：另外两种**不许**被覆盖
    for keep in ("叙述模型预算已用完", "叙述模型暂时不可用"):
        assert keep not in OV, f"把一句对老板有用的话也换掉了: {keep}"
    assert len(OV) == 1, f"覆盖表长出了新条目，逐条读过再加: {sorted(OV)}"


def test_the_replacement_is_wired_at_the_call_site():
    """🔴 覆盖表要**真的被用上** —— ⛔ 不是定义了一张没人查的表。

    本仓头号缺陷形态是「机制在、没接上」。这条问的是
    「生产上谁保证它被调用」。
    """
    import ast
    import inspect

    from smartbi.agent import synthesis_engine as se

    src = inspect.cleandoc(inspect.getsource(
        se.ComprehensiveSynthesisEngine._deterministic_fallback_response))
    tree = ast.parse(src)
    used = {
        n.value.id
        for n in ast.walk(tree)
        if isinstance(n, ast.Attribute) and isinstance(n.value, ast.Name)
    }
    assert "_FALLBACK_OPENING_OVERRIDES" in used, (
        "开场白覆盖表没有在兜底里被查 —— 定义了一张没人用的表"
    )
    assert "不会当成 0" in src, "「缺的不当 0」这条纪律被顺手删掉了"
