"""「换时间范围」按钮的承诺，对服务层派发的那条路也必须成立。

## 缺陷（2026-08-10 prod）

`resolver_supports_explicit_window(code)` 的判据是「`_RESOLVERS[code]` 的签名里
有没有 `date_range`」。而 `RESTAURANT_OPS_BUSINESS_OPTIMIZATION` **压根不在
`_RESOLVERS` 里** —— 它走服务层另一条硬编码派发
（`if code == "RESTAURANT_OPS_BUSINESS_OPTIMIZATION": await
_resolve_business_optimization(...)`），而那个函数**确实**把 `spec.date_range`
原样传给 `ComprehensiveSynthesisEngine`。

于是闸返回 False → 「换时间范围」按钮被**误扣**。回归电池 [66]
「这周全部门店营收怎么提高，给我今天能做的动作」因此长期红在
「按钮缺少『最近7天』」，而系统其实完全能按那个窗口取数。

🔑 判据：**这个闸的载体比它查的那张表多。** 只查一个派发表 = 对第二条派发路径
   完全沉默，而沉默的方向是「误拒」—— 误拒不报错，只是少给用户一个出口，
   所以能躺很久。

⚠️ 我在定位过程中一度把结论说反了（「断言在要求系统做一件它正确拒绝的事」）。
   那是只读了闸的注释、没读被闸判定的那个函数。**闸说「不支持」时，要去看
   被判定的对象到底支不支持，而不是接受闸的说法。**

## 这个文件为什么要有行为断言，而不只是查那个常量

`resolver_supports_explicit_window` 的 docstring 自己写着它是**必要条件而非充分
条件**（声明了不等于用对了）。把 intent 加进
`_SERVICE_DISPATCHED_WINDOW_AWARE` 只是让闸**声称**它支持窗口 —— 如果哪天
`_resolve_business_optimization` 改成忽略 `spec.date_range`，那个常量不会变，
按钮会继续承诺一件做不到的事。所以这里直接断言**那个窗口真的传到了引擎**。
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    _SERVICE_DISPATCHED_WINDOW_AWARE,
    resolver_supports_explicit_window,
)


class _Spec(SimpleNamespace):
    pass


class _StubResponse(SimpleNamespace):
    pass


def _run(coro):
    return asyncio.get_event_loop_policy().new_event_loop().run_until_complete(coro)


@pytest.mark.parametrize("code", sorted(_SERVICE_DISPATCHED_WINDOW_AWARE))
def test_service_dispatched_intents_are_reported_as_window_aware(code):
    """集合里的每一个 intent，闸都必须说它支持显式窗口。"""
    assert resolver_supports_explicit_window(code) is True, (
        f"{code} 在服务层派发集合里, 闸却说它不支持显式时间窗 —— "
        f"「换时间范围」按钮会被误扣")


def test_explicit_window_actually_reaches_the_synthesis_engine(monkeypatch):
    """承重断言: spec.date_range 原样到达引擎, 不被兜底窗口顶掉。

    ⛔ 不断言「函数里出现了 spec.date_range 这几个字」—— 那是读源码, 改成
       `_ = spec.date_range` 也照样通过。这里断言引擎**收到的值**。
    """
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    import smartbi.agent.synthesis_engine as engine_mod
    import smartbi.api.synthesis as synthesis_mod

    seen = {}

    class _StubEngine:
        def __init__(self, _pool):
            pass

        async def synthesize(self, factory_id, query, date_range, **_kw):
            seen["date_range"] = date_range
            return _StubResponse(
                answer="优化建议：先补峰值时段人力。",
                source="stub", plan=None, fact_check=None, charts=[],
                dimension_coverage={"available_dimensions": ["sales"]},
            )

    async def _never_called(*_a, **_kw):
        raise AssertionError(
            "spec 已经给了显式窗口, 不该再走 _resolve_window 兜底")

    monkeypatch.setattr(engine_mod, "ComprehensiveSynthesisEngine", _StubEngine)
    monkeypatch.setattr(synthesis_mod, "_resolve_window", _never_called)

    explicit = ("2026-08-01", "2026-08-07")
    _run(svc._resolve_business_optimization(
        None, "MOCK_REST", "这周全部门店营收怎么提高，给我今天能做的动作",
        _Spec(date_range=explicit), None))

    assert seen["date_range"] == explicit, (
        f"引擎收到的窗口是 {seen.get('date_range')!r}, 不是 spec 给的 {explicit!r} —— "
        f"「换时间范围」按钮承诺的事没兑现")


def test_missing_window_still_falls_back(monkeypatch):
    """阴性对照: spec 没给窗口时**必须**走兜底。

    没有这条, 把 `_resolve_business_optimization` 改成「永远用 spec.date_range」
    也能让上面那条通过 —— 而那会让「本月营收怎么提高」这种没写死窗口的问句
    拿到 (None, None)。
    """
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    import smartbi.agent.synthesis_engine as engine_mod
    import smartbi.api.synthesis as synthesis_mod

    seen = {}
    fallback = ("2026-07-01", "2026-07-31")

    class _StubEngine:
        def __init__(self, _pool):
            pass

        async def synthesize(self, factory_id, query, date_range, **_kw):
            seen["date_range"] = date_range
            return _StubResponse(
                answer="优化建议：略。", source="stub", plan=None, fact_check=None,
                charts=[], dimension_coverage={"available_dimensions": ["sales"]},
            )

    async def _fallback(*_a, **_kw):
        return fallback

    monkeypatch.setattr(engine_mod, "ComprehensiveSynthesisEngine", _StubEngine)
    monkeypatch.setattr(synthesis_mod, "_resolve_window", _fallback)

    _run(svc._resolve_business_optimization(
        None, "MOCK_REST", "全部门店营收怎么提高",
        _Spec(date_range=(None, None)), None))

    assert seen["date_range"] == fallback, "没给窗口时没有走兜底"


def test_dispatch_and_window_answer_share_one_definition():
    """派发与窗口判定必须来自同一个常量 —— 两处各写一份字面量就是缺陷本身。"""
    import inspect
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    src = inspect.getsource(svc.tiered_answer)
    for code in _SERVICE_DISPATCHED_WINDOW_AWARE:
        assert f'"{code}"' not in src and f"'{code}'" not in src, (
            f"tiered_answer 里又出现了 {code} 的字面量 —— 派发条件必须读"
            f"_SERVICE_DISPATCHED_WINDOW_AWARE, 否则它和窗口判定会各走各的")
    assert "_SERVICE_DISPATCHED_WINDOW_AWARE" in src, (
        "tiered_answer 不再引用那个集合 —— 派发换回硬编码了")
