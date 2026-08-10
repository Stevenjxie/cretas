"""回归电池的不变式断言必须**有办法变红**。

## 为什么需要这条闸

2026-08-10 把 [66]「这周全部门店营收怎么提高」从「按今天是不是周一分支断言」
改写成「断言行为自洽」。改写方向是对的, 但落地时踩了另一个洞:

    "空周期必须给相邻周期": (["没有可用的经营数据"], []),

当时 invariant 的结构只支持 `(need → forbid)` 一个方向, 也就是**只能表达
「说了 A 就不许说 B」**。「必须给相邻周期」是个「必须出现」, 表达不了, 于是
forbid 填了空表 —— 内层循环一次都不执行, 这条断言**永远不会红**, 而它旁边的
注释写着「不能撒手」, 读起来像已经守住了。

判据: **写完一条断言先问「它靠什么变红」。** 答不上来就不是断言, 是注释。
      这与「闸没跑」「左右同源恒真式」「被测数据里没有可失败的东西」是同一族,
      本条是第四种形状: **规则表达不了这条判据, 于是被填成了空操作**。

⛔ 本文件断言的是**判定逻辑本身**(`invariant_problems`), 不需要网络 ——
   原来它内联在一个要发 HTTP 的函数里, 那正是它没人测得到的原因。
"""
from __future__ import annotations

from smartbi.scripts.restaurant_ai_eval import (
    _THIS_WEEK_EMPTY_PERIOD_CASE,
    invariant_problems,
)


def test_require_any_direction_can_fail():
    """说了没数据、又没给任何相邻周期 → 必须判违反。"""
    inv = {"空周期必须给相邻周期": (["没有可用的经营数据"], [], ["最近7天", "上周"])}
    bad = invariant_problems("本周没有可用的经营数据。", inv)
    assert bad, "「说了没数据却没给相邻周期」没有被判违反 —— require_any 没生效"

    ok = invariant_problems("本周没有可用的经营数据，已切到最近7天为你分析。", inv)
    assert ok == [], f"给了相邻周期却被判违反: {ok}"


def test_rule_not_triggered_is_not_a_pass_nor_a_failure():
    """need 没全部命中 → 这条规则不适用, 不该产出问题。

    阴性对照: 没有这条, 把 `if not all(...)` 写成 `if any(...)` 之类的错误会
    让不适用的规则也开始报问题, 表现为一堆莫名其妙的失败。
    """
    inv = {"空周期必须给相邻周期": (["没有可用的经营数据"], [], ["最近7天"])}
    assert invariant_problems("本月营收 ¥123 万，同比增长 8%。", inv) == []


def test_forbid_direction_still_works():
    """两位写法(不带 require_any)必须继续生效 —— 改结构别把旧的一半改没了。"""
    inv = {"有分析就不许谎称没数据": (["优化建议"], ["没有可用的经营数据"])}
    bad = invariant_problems("优化建议：…… 但本周没有可用的经营数据。", inv)
    assert bad, "自相矛盾的答案没有被判违反"
    assert invariant_problems("优化建议：先补齐峰值时段人力。", inv) == []


def test_the_real_case_declares_a_failable_requirement():
    """电池里真正在用的那条, 必须**带得起**一次失败。

    ⛔ 不重写一份期望的规则内容来比对(那是第二张手写表, 会漂移), 而是拿真用例
       的规则去判一个"说了没数据、什么相邻周期都没给"的答案 —— 判不出问题,
       就说明它又退化成空操作了。
    """
    inv = _THIS_WEEK_EMPTY_PERIOD_CASE["invariant"]
    assert invariant_problems("本周没有可用的经营数据。", inv), (
        "[66] 的「空周期必须给相邻周期」对一个明显违规的答案沉默 —— "
        "它退回成了永远不会红的空操作"
    )
