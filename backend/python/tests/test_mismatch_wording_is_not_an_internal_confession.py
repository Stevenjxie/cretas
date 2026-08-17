"""`dims=∅` 时那句拒答不该是「我准备算的东西跟你问的对不上」。

## 缺陷（📏 MOCK_REST prod 2026-08-18，14 条追问链 × 3 轮，稳定）

`code ∉ planned_intents` 的 12 条里有 **9 条**是 `dims=∅` 这个形状，
全部拿到同一句 60 字：

```
我准备算的东西跟你问的对不上，所以这次我没敢算。你是想看某道菜、某家门店，
还是全店合计？说一个就行，我不会拿别的数据凑。
```

前半句是**产品内部两个推导打架**的自我描述 —— 老板不需要知道，
而且它与上下文矛盾：他上一轮问的就是门店。

▎`_execution_mismatch` 有 **6 个 return**，而拼接模板只有两个分支
▎（有没有 `gap_advice`）。于是这句内部矛盾的自白被拼进了一个
▎本该是「我不知道你要看哪一层」的反问里。
▎`dims=∅` 时 `asked - supported` 恒空 ⇒ `gap_advice` 恒空 ⇒ 这 9 条全落在 else。

## 🔑 修法选的是**收敛**，不是加分支

`_execution_mismatch` 的返回值**同时**当日志 reason 和用户文案，
而且已经有一处把它当类型系统用（`if mismatch == _STORE_SCOPE_MISMATCH`）——
与 spec 里 `clarification_question ==` 那 10 处身份比较同形，
注释里已经点名批评过。⇒ ⛔ **不加第二个身份比较。**

其中两条对**老板**是同一件事：

```
609  spec.intent not in plan          「我准备算的东西跟你问的对不上」  ← dims=∅ 时是错的
656  asked_dimensions ⊄ supported     「我不确定你要看的是哪一层的数」  ← 这句是对的
```

⇒ 抽常量 `_LAYER_UNCLEAR`（**用 656 已有的措辞**，⛔ 不新造第三句），
609 在 `not spec.dimensions` 时返回它。

⚠️ `spec.dimensions` **非空**时保持原串 —— 那时老板说清了层，
说「不知道哪一层」就是撒谎。

⚠️ 日志侧不会因此分不清：那行 warning 已经带
`intent=%s planned=%s dimensions=%s`。
"""
from __future__ import annotations

import ast
import inspect

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc


def _spec(**overrides):
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    base = dict(
        intent="RESTAURANT_OPS_STORE_MARGIN",
        domain="restaurant",
        date_range=(None, None),
        window_label="最近30天",
        relative_window=True,
        metrics=("revenue",),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
        planner_authority="llm",
        plan_version="restaurant-query-plan-v2",
        plan_hash="deadbeef",
        planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",),
        requested_metrics=("revenue",),
    )
    base.update(overrides)
    return RestaurantQuerySpec(**base)


def _mismatch(spec, plan=("RESTAURANT_OPS_SALES_SUMMARY",)):
    return svc._execution_mismatch(
        spec, plan, dish_mention=None, store_mention=None, store_dish=None)


# ── 承重 ────────────────────────────────────────────────────────────────

def test_no_dimensions_gets_the_layer_question_not_an_internal_confession():
    """📏 prod 那 9 条的形状：`code ∉ plan` 且 `dims=∅`。"""
    reason = _mismatch(_spec(dimensions=()))
    assert reason == svc._LAYER_UNCLEAR, reason
    assert "我准备算的东西跟你问的对不上" not in (reason or ""), (
        "还在把内部两个推导打架的事说给老板听"
    )


def test_the_two_paths_share_one_string():
    """⛔ 同一句用户可见的话只此一处定义（这是**收敛**，不是加分支）。"""
    src = inspect.getsource(svc._execution_mismatch)
    assert src.count('"我不确定你要看的是哪一层的数"') == 0, (
        "字面量还在函数里 —— 应该只剩常量引用"
    )
    assert src.count("_LAYER_UNCLEAR") == 2, (
        f"引用了 {src.count('_LAYER_UNCLEAR')} 次，期望 2 次"
        f"（`code∉plan` 且 dims 空 / 维度超出能力）"
    )


# ── 阴性对照 ────────────────────────────────────────────────────────────

def test_with_dimensions_the_original_reason_stays():
    """⚠️ 老板说清了层时说「不知道哪一层」就是撒谎，⛔ 不许一起换掉。"""
    reason = _mismatch(_spec(dimensions=("dish",)))
    assert reason == "我准备算的东西跟你问的对不上", reason


def test_dimension_capability_gap_is_unchanged():
    """656 那条（维度超出能力）行为逐字不变。"""
    spec = _spec(
        intent="RESTAURANT_OPS_INVENTORY_WARNING",
        planned_intents=("RESTAURANT_OPS_INVENTORY_WARNING",),
        dimensions=("store", "ingredient"),
    )
    reason = _mismatch(spec, plan=("RESTAURANT_OPS_INVENTORY_WARNING",))
    assert reason == svc._LAYER_UNCLEAR, reason


def test_store_scope_identity_comparison_still_fires():
    """⚠️ 下游按**这个串**决定能不能把死胡同换成歧义消解 —— 不许失联。"""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",),
    )
    reason = svc._execution_mismatch(
        spec, ("RESTAURANT_OPS_SALES_SUMMARY",),
        dish_mention=None, store_mention="徐汇美罗城店", store_dish=None)
    assert reason == svc._STORE_SCOPE_MISMATCH


def test_a_consistent_spec_is_not_blocked():
    """阴性对照：本来就自洽的 spec ⛔ 不许被拦。"""
    spec = _spec(
        intent="RESTAURANT_OPS_SALES_SUMMARY",
        planned_intents=("RESTAURANT_OPS_SALES_SUMMARY",),
    )
    assert _mismatch(spec) is None


# ── ⛔ 不许悄悄多出第三个身份比较 ────────────────────────────────────────

def test_no_new_identity_comparison_on_the_reason_string():
    """`_execution_mismatch` 的返回值已经被当类型系统用了一处
    （`mismatch == _STORE_SCOPE_MISMATCH`）。⛔ 不许再多一处。

    ⚠️ 正解是把返回值结构化成 `(log_reason, user_text)`，一次改 6 个 return
    并换掉那处身份比较。**本轮没做，显式登记** —— 它需要独立一轮。
    这条断言守住「在那之前别把坏形态做大」。
    """
    src = inspect.getsource(svc)
    tree = ast.parse(src)
    identity_cmps = [
        node.lineno
        for node in ast.walk(tree)
        if isinstance(node, ast.Compare)
        and isinstance(node.left, ast.Name)
        and node.left.id == "mismatch"
        and any(isinstance(op, ast.Eq) for op in node.ops)
    ]
    assert len(identity_cmps) == 1, (
        f"拿 mismatch 串做身份比较的地方有 {len(identity_cmps)} 处"
        f"（行 {identity_cmps}）—— 期望 1 处。多一处就是把这个坏形态做大"
    )
