"""排班 FactBook 必须说 Answer Contract 认的规范词。

## 为什么需要这条闸

2026-08-10 prod 实测: 「明天怎么排班」的 FactBook **算得完全正确** ——

    范围 2026-08-11   预测客流 6541 人   预订覆盖 34.1%
    峰值建议人数合计 748   现有 280   正向缺口 468   兼职人数建议 468

但 Answer Contract 判 `missing=["analysis_action"]`, **把这份答案整个扔掉**,
用户看到的是「本次结果没有可靠覆盖本轮要求的原因分析或优化动作」。

真因: 契约用一张**手写短语表**判 optimize 是否被满足 ——
`("优化目标", "优化建议", "优化动作", "验证指标")`。FactBook 明明给了动作
(峰值建议人数 / 兼职人数建议 / 缺口), 但用的是自己的词汇, 一个都没命中。

回归电池上表现为 3 题失败([50] 明天怎么排班 / [52] 下个月各店人效 /
[66] 本周营收怎么提高)。

⛔ 修法是让**生产方说契约的规范词**, 不是往契约的短语表里再加几个词 ——
   那张表每来一种新答案形态就得加一次, 而漏掉的那次表现为「能力正常但答案被
   扔掉」, 症状离原因极远(要一路查到 agg_meta 里的 rejected_answer 才看得见)。

判据: **一套规范词汇, 不是 N 种方言 + 一张不断变长的手写表。**

## 这条闸怎么写才不是恒真式

⛔ 不许把期望值写成 `"优化动作"` 这个字面量再和自己比。右边**从
   answer_contract 里读那张真表**, 左边是排班模块真正拼出来的文案模板 —— 两边
   任何一侧漂移都会红。
"""
from __future__ import annotations

import inspect
import re

import pytest


def _optimize_markers() -> tuple:
    """从 Answer Contract 的源码里读出它判 optimize 用的那组词。"""
    from smartbi.gold.restaurant import answer_contract

    src = inspect.getsource(answer_contract._analysis_action_present)
    block = re.search(
        r'analysis_action\s*==\s*"optimize".*?\(\s*(".*?")\s*,?\s*\)', src, re.S)
    assert block, "契约里找不到 optimize 分支的短语表 —— 结构变了, 先修这条解析"
    markers = tuple(re.findall(r'"([^"]+)"', block.group(1)))
    assert markers, "解析出 0 个标记词 —— 正则与源码写法脱节了"
    return markers


def _emitted_strings(module) -> str:
    """模块里**所有字符串字面量**拼起来 —— 只有它们能真正到达用户。

    ⛔ 不能用 `inspect.getsource(module)` 直接搜: 源码含注释, 而解释这条闸为什么
       存在的那段注释里**恰好写着那几个规范词** → 闸恒绿。
       2026-08-10 实测: 第一版就是这么写的, 拿掉真正的规范词做变异, **没红**。
       判据: **搜「代码里有没有某个词」时, 先问这个词会不会出现在注释/文档里** ——
       注释不会到达用户, 却会让基于源码文本的断言恒真。
    """
    import ast

    tree = ast.parse(inspect.getsource(module))
    return "\n".join(
        n.value for n in ast.walk(tree)
        if isinstance(n, ast.Constant) and isinstance(n.value, str)
    )


def test_staffing_factbook_template_contains_a_contract_marker():
    """排班 FactBook 的文案模板里, 必须至少出现一个契约认的规范词。"""
    from smartbi.services.restaurant import staffing_forecast

    src = _emitted_strings(staffing_forecast)
    markers = _optimize_markers()
    hit = [m for m in markers if m in src]
    assert hit, (
        f"排班 FactBook 的文案里一个契约规范词都没有。契约认这些: {markers}。\n"
        f"没有的话 Answer Contract 会判 missing=['analysis_action'] 并把整份答案\n"
        f"扔掉 —— 用户看到拒答, 而数据其实是对的。")


def test_contract_marker_list_is_not_empty_and_is_the_real_one():
    """阴性对照: 确认我们读到的是**真表**, 不是解析出来的空集合。

    ⚠️ 一个恒返回空元组的解析会让上面那条断言永远通过(空集合与任何东西都
    「没有交集」→ hit 为空 → 断言失败)……不, 恰恰相反: 空表会让 hit 恒为空
    从而**恒红**。真正危险的是解析出一堆无关词导致恒绿。所以这里钉两件事:
    表非空, 且包含那个最核心的词。
    """
    markers = _optimize_markers()
    assert len(markers) >= 3, f"契约的 optimize 标记词只有 {markers}, 少得可疑"
    assert any("优化" in m for m in markers), (
        f"契约的 optimize 标记词里一个「优化」都没有: {markers}")
