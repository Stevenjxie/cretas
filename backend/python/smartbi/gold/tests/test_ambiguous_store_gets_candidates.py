"""门店名说了但匹配到多家时，要给候选，不要给死胡同。

## 缺陷（2026-08-10 prod，回归电池 [45]「本月社区店的营收」）

用户**说了**门店（「社区店」），只是它匹配到两家（模拟·宝山大场社区店 /
模拟·普陀真如社区店）没消解成功。prod 日志三步说全了：

    缺项都有安全默认值 -> 补默认而不反问: missing=('store_scope',)
    zero-token plan-cache hit: intent=RESTAURANT_OPS_SALES_SUMMARY
    执行前拦截(维度/口径不匹配): reason=门店范围不能由全店或全门店 resolver 代答

规划层把 `store_scope` 当成「用户没提」补了全店默认，随后这道闸**正确地**判口径
不符——于是用户拿到一句死胡同拒答，**连按钮都没有**。

🔑 判据：**「没解析出 X」不等于「用户没提 X」。** 把前者当后者，就是拿缺席当证据。

⛔ 修法复用 `_canonicalize_store_mention`——「匹配到多家门店」这套消解在
   `STORE_MARGIN` 的 resolver 里早就有了。另写一份就是第二个载体。

📌 方向上与 2026-08-07 那次撤回（`各门店对比如何`）相反且不冲突：那次撤回的是
   **压掉**澄清（拿 5 条 UX 契约换一次点击，不划算）；这里是把死胡同**换成**澄清。
   「按钮链是产品的一部分」这条正好支持本改动。
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest


def _spec():
    """用**真实的** RestaurantQuerySpec 造替身。

    ⛔ 不手写字段清单 —— 那是又一张会漂的手抄表: spec 加一个必填槽位, 这里
       就静默地少一个属性, 而失败长成 AttributeError, 看不出是替身过期。
       直接实例化真类, 让 dataclass 的默认值负责其余字段。
    """
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    required = {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY", "domain": "restaurant",
        "date_range": (None, None), "window_label": "本月",
        "relative_window": None, "metrics": (), "wants_margin": False,
        "asks_profitability": False, "dimensions": (), "comparison": None,
        "confidence": 1.0, "source_tier": "test",
    }
    return RestaurantQuerySpec(**required, requested_metrics=("revenue",),
                               clarification_needed=True)


def _run(coro):
    return asyncio.get_event_loop_policy().new_event_loop().run_until_complete(coro)


def _call(monkeypatch, matched, mention="社区店"):
    """打桩 `_canonicalize_store_mention`，只测消解分支自身的行为。"""
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    import smartbi.gold.restaurant.restaurant_ops_router as router

    async def _fake(_pool, _fid, _mention):
        return list(matched)

    monkeypatch.setattr(router, "_canonicalize_store_mention", _fake)
    return _run(svc._store_disambiguation(
        None, "MOCK_REST", mention, "", _spec()))


def test_multiple_matches_returns_candidates(monkeypatch):
    """匹配到多家 → 正文里必须逐个列出候选。

    ⚠️ 候选必须落在**正文**，不是按钮 —— 电池断言查的是正文（它自己的注释里
    写着：写成 followup_contains 会红，而红的原因是「查错了地方」不是行为错）。
    """
    got = _call(monkeypatch, ["模拟·宝山大场社区店", "模拟·普陀真如社区店"])
    assert got is not None, "匹配到多家却没给候选 —— 用户仍然拿到死胡同"
    text = got["answer_text"]
    assert "匹配到多家门店" in text
    for name in ("模拟·宝山大场社区店", "模拟·普陀真如社区店"):
        assert name in text, f"候选 {name} 没出现在正文里: {text}"
    labels = [f["label"] for f in got.get("followups", [])]
    assert len(labels) >= 2, f"没给可点的候选按钮: {labels}"


def test_single_match_is_left_alone(monkeypatch):
    """阴性对照: 恰好 1 家**不**在这里处理。

    那说明规划层本该用它却没用 —— 是另一个缺陷。在这里"顺手修好"会把它藏起来，
    让它继续走原拒答，好歹留下日志和一条会红的用例。
    """
    assert _call(monkeypatch, ["模拟·普陀真如社区店"]) is None


def test_no_match_is_left_alone(monkeypatch):
    """阴性对照: 一家都没匹配上也交回原路（那是「没有这家店」，另一套文案）。"""
    assert _call(monkeypatch, []) is None


def test_canonicalizer_failure_falls_back_to_the_original_refusal(monkeypatch):
    """消解本身抛异常时，必须退回原拒答，而不是把整个回答弄丢。"""
    from smartbi.gold.restaurant import restaurant_intent_service as svc
    import smartbi.gold.restaurant.restaurant_ops_router as router

    async def _boom(_pool, _fid, _mention):
        raise RuntimeError("库连不上")

    monkeypatch.setattr(router, "_canonicalize_store_mention", _boom)
    got = _run(svc._store_disambiguation(
        None, "MOCK_REST", "社区店", "", _spec()))
    assert got is None, "消解异常时应交回原路，而不是抛出去"


def test_guard_reason_is_a_shared_constant():
    """拒答理由与「按理由决定要不要消解」必须读同一个常量。

    两处各写一份字面量，改一处就静默失联 —— 症状是「歧义消解不再出现」，
    不报错、不告警，和缺陷原样。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    assert svc._STORE_SCOPE_MISMATCH
    guard_src = inspect.getsource(svc._execution_mismatch)
    assert "_STORE_SCOPE_MISMATCH" in guard_src, "闸不再返回那个常量了"
    assert f'"{svc._STORE_SCOPE_MISMATCH}"' not in guard_src, (
        "闸里又出现了理由的字面量 —— 必须只有常量一处定义")
    caller_src = inspect.getsource(svc.tiered_answer)
    assert "_STORE_SCOPE_MISMATCH" in caller_src, "调用方不再按常量判断了"


def test_the_guard_actually_calls_the_disambiguation():
    """接线本身要有断言 —— 上面那些测的是函数, 不是「它被接上了」。

    🔴 2026-08-10 变异验证当场暴露: 把调用点整段删掉, 上面 4 条行为断言**全绿**
       (它们直接调 `_store_disambiguation`, 绕过了接缝), 只有常量那条偶然红了。
       判据: **测了一个函数不等于测了它被调用** —— 缺陷往往就在接缝上,
       而绕过接缝的测试对它完全沉默。

    ⚠️ 这是源码级断言, 承重有限(改成 `if False:` 它照样过)。真正的端到端要打
       真库, 由回归电池 [45]「本月社区店的营收」承担 —— 这条只保证"调用还在"。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    src = inspect.getsource(svc.tiered_answer)
    assert "_store_disambiguation(" in src, (
        "tiered_answer 不再调用 _store_disambiguation —— 门店歧义又变回死胡同拒答")
    assert "_STORE_SCOPE_MISMATCH" in src, "不再按那条拒答理由触发消解"
