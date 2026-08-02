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


# ── 门店范围: 同一族的第二条 ────────────────────────────────────────

def test_接不住任何范围的resolver不得被门店澄清拦下():
    """签名只有 (pool, factory_id) 的 resolver, 任何澄清它都接不住。

    2026-08-01 实测: 「上个月人效怎么样」被 `_apply_store_scope_guard` 拦下来问
    「你想查看哪家门店的人效情况？」, 而 `resolve_staffing_advice(pool, factory_id)`
    **一个门店参数都没有** —— 用户选了哪家店都不会改变答案。

    ⚠️ 它**时灵时不灵**: 守卫是靠 `requested_metrics ∩ {sales_volume, gross_margin,
    revenue, orders}` 触发的, 而「人效」的指标由 LLM 填 —— 填成 orders 那轮才拦。
    所以这不是稳定缺陷而是**确定性覆盖的缺口**, 只能靠静态判据守。

    ⛔ 判据刻意**只管「只有两个参数」这一类**, 不推广到「签名里没有 store_id」——
    `GROSS_MARGIN` / `SALES_SUMMARY` 也没有 store_id 形参, 但它们收 `query` /
    `date_range`, 门店范围经别的机制生效, 是**有意**被门店澄清拦下的。
    把判据写宽会把这两个正当的也报出来。
    """
    import inspect

    from smartbi.gold.restaurant.restaurant_intent import (
        _STORE_SCOPE_FREE_INTENTS,
        _TIME_SCOPED_INTENTS,
    )
    from smartbi.gold.restaurant.restaurant_ops_router import _RESOLVERS

    offenders = []
    for code, resolver in sorted(_RESOLVERS.items()):
        params = list(inspect.signature(resolver).parameters)
        if len(params) > 2:
            continue                       # 收得到别的东西, 不在本判据范围
        if code not in _STORE_SCOPE_FREE_INTENTS:
            offenders.append(f"{code} → {resolver.__name__}{tuple(params)} 不在 FREE 名单")
        if code in _TIME_SCOPED_INTENTS:
            offenders.append(f"{code} → {resolver.__name__}{tuple(params)} 却在 TIME_SCOPED")
    assert not offenders, (
        "这些 resolver 只收 (pool, factory_id), 接不住任何澄清, 却会被拦下来问范围 ——\n"
        "用户答了也不会改变答案:\n  " + "\n  ".join(offenders)
    )


def test_排班建议不被门店澄清拦下():
    """守住这次的具体修复。"""
    from smartbi.gold.restaurant.restaurant_intent import _STORE_SCOPE_FREE_INTENTS

    assert "RESTAURANT_OPS_STAFFING_ADVICE" in _STORE_SCOPE_FREE_INTENTS


# ── 第三条: LLM 自由发挥的澄清同样要被挡住 ──────────────────────────

def test_接不住范围的intent不得携带任何澄清():
    """resolver 只收 (pool, factory_id) 时, **任何**澄清都该被丢弃。

    2026-08-01 实测: 修掉 `_TIME_SCOPED_INTENTS` 与 `_STORE_SCOPE_FREE_INTENTS`
    两条路径之后, 「上个月人效怎么样」仍有约 1/8 的概率答不出, 正文是

        你想查看哪家门店的人效情况？

    ⚠️ 这句**不是** `STORE_SCOPE_CLARIFICATION_QUESTION` 的措辞 —— 是 **LLM 自己**
    决定要问门店。而 `_slots_of_clarification` 只认三个已知常量, LLM 自由发挥的
    问句返回空集, 于是前两条守卫都拦不住它。

    ⚠️ 复现必须**一个进程只调一次**: 计划缓存是进程内的, 同进程第二次就命中缓存
    (auth=validated_plan_cache), 冷路径行为被完全掩盖。我第一版探针多调了一次
    `parse_restaurant_query`, 把缓存写热, 于是 12/12 全过 —— 探针自己藏了缺陷。

    判据: 凡 resolver 签名只有 (pool, factory_id) 的 intent, `_NO_SCOPE_INTENTS`
    必须收录它 —— 那是 `_build_spec` 用来丢弃无用澄清的白名单。
    """
    import inspect

    from smartbi.gold.restaurant.restaurant_intent import _NO_SCOPE_INTENTS
    from smartbi.gold.restaurant.restaurant_ops_router import _RESOLVERS

    expected = {
        code for code, fn in _RESOLVERS.items()
        if len(inspect.signature(fn).parameters) <= 2
    }
    missing = sorted(expected - set(_NO_SCOPE_INTENTS))
    assert not missing, (
        f"这些 resolver 只收 (pool, factory_id), 接不住任何澄清, 却不在 "
        f"_NO_SCOPE_INTENTS 里 —— LLM 一旦自由发挥出一句澄清就会把它们卡死: {missing}"
    )
