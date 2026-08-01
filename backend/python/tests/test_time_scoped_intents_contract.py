"""被标为「需要显式时间范围」的 intent, 它的 resolver 必须真的用得上时间。

2026-08-01 实测: `RESTAURANT_OPS_STAFFING_ADVICE` 在 `_TIME_SCOPED_INTENTS` 里,
于是「哪个时段人手不够」被拦下来问「你想看哪个时间范围？本月/上个月/最近7天/
最近30天」。但它的 resolver 签名是 `resolve_staffing_advice(pool, factory_id)` ——
**一个日期参数都没有**, 读的是 `fact_staffing_daypart` 这张**配置表**(没有时间维度)。

后果: 用户认真回答了「最近30天」, 拿到的答案与回答「本月」**一模一样** ——
澄清问了一个答案根本不受其影响的问题。这比不问更糟: 它让用户以为自己在控制口径。

⚠️ 这个缺陷此前一直被**另一个**缺失挡着: 人效配置表是空的, resolver 先返回
「还没有配置人效数据」。配上数据之后, 时间澄清才浮出来 —— 一个缺陷被另一个
缺陷遮住, 修好前一个才看得见后一个。
"""
from __future__ import annotations

import inspect

from smartbi.gold.restaurant.restaurant_intent import _TIME_SCOPED_INTENTS
from smartbi.gold.restaurant.restaurant_ops_router import _RESOLVERS

# resolver 能接住时间范围的形参名(本仓两种写法都有)
_TIME_PARAMS = {"date_range", "days", "start", "end", "exact_start", "exact_end"}


def test_需要时间范围的intent其resolver必须真的接受时间():
    """否则澄清问的是一个答案根本不受其影响的问题。"""
    offenders = []
    for code in sorted(_TIME_SCOPED_INTENTS):
        resolver = _RESOLVERS.get(code)
        if resolver is None:
            continue                      # 没有 resolver 的 code 由别处守
        params = set(inspect.signature(resolver).parameters)
        has_varkw = any(
            p.kind is inspect.Parameter.VAR_KEYWORD
            for p in inspect.signature(resolver).parameters.values()
        )
        if not (params & _TIME_PARAMS) and not has_varkw:
            offenders.append(f"{code} → {resolver.__name__}{inspect.signature(resolver)}")
    assert not offenders, (
        "这些 intent 会因为「缺时间范围」被拦下澄清, 但它们的 resolver 收不到时间 ——\n"
        "用户回答了也不会改变答案:\n  " + "\n  ".join(offenders)
        + "\n要么从 _TIME_SCOPED_INTENTS 移除, 要么让 resolver 真的按时间取数。"
    )


def test_排班建议不在需要时间范围的名单里():
    """守住这次的具体修复 —— 它读的是配置表, 没有时间维度可言。"""
    assert "RESTAURANT_OPS_STAFFING_ADVICE" not in _TIME_SCOPED_INTENTS
