"""规格别名表必须指向登记表里真实存在的 key。

## 为什么需要这条闸

`generic_answer._SPEC_METRIC_ALIASES / _SPEC_DIMENSION_ALIASES` 把「管线旧写法」
翻译成登记表的 key(如 sales_volume → sales_qty, dish → product)。它们的设计是
对的 —— **只登别名, 同名直通**, 所以不会变成第二份完整清单。

但残留一个**静默失效**的方向, 而且原注释自己点出来了:

    别名的**目标**若在登记表里被改名或删除, `_metric_key()` 会返回 None
    → 走原路径「如实说没有」→ **能力悄悄消失, 不报错、不告警**。

2026-08-10 普查时实测: 5 条别名的目标当时都在, 但**没有任何东西盯着它们**。
一次登记表重命名就能让某个问法从「能答」变成「答不出来」, 而现象是
「AI 好像变笨了」—— 离原因非常远。

判据: **翻译表的价值等于它的目标存在**; 目标没了, 翻译表就成了一张指向空地的
      地图, 而地图本身不会报错。

## 第二个方向: 别名不许劫持直通

若某天登记表新增一个 key 恰好叫 `dish`, 而别名表里 `dish → product` 还在, 那么
「dish」会被翻译成 product, **新增的 dish 指标永远指不到** —— 同样零报错。
所以还要断言: 别名的**来源名**不能同时是登记表里已有的 key。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.generic_answer import (
    _SPEC_DIMENSION_ALIASES,
    _SPEC_METRIC_ALIASES,
)
from smartbi.gold.restaurant.metric_registry import DERIVED, DIMENSIONS, METRICS


def test_metric_alias_targets_exist_in_registry():
    missing = {k: v for k, v in _SPEC_METRIC_ALIASES.items()
               if v not in METRICS and v not in DERIVED}
    assert not missing, (
        f"指标别名指向了登记表里不存在的 key: {missing}\n"
        f"后果是 _metric_key() 返回 None → 该问法静默退回「答不出来」, 不报错。\n"
        f"登记表现有: {sorted(set(METRICS) | set(DERIVED))}")


def test_dimension_alias_targets_exist_in_registry():
    missing = {k: v for k, v in _SPEC_DIMENSION_ALIASES.items() if v not in DIMENSIONS}
    assert not missing, (
        f"维度别名指向了登记表里不存在的 key: {missing}\n"
        f"登记表现有: {sorted(DIMENSIONS)}")


@pytest.mark.parametrize(
    "aliases,registry,label",
    [
        (_SPEC_METRIC_ALIASES, dict(METRICS) | dict(DERIVED), "指标"),
        (_SPEC_DIMENSION_ALIASES, DIMENSIONS, "维度"),
    ],
)
def test_alias_source_names_do_not_shadow_registry_keys(aliases, registry, label):
    """别名的**来源名**不能同时是登记表已有的 key —— 那会劫持直通。

    例: 登记表某天新增一个叫 `dish` 的维度, 而别名 `dish → product` 还在,
    则「dish」永远被翻译成 product, **新维度指不到**, 且零报错。
    """
    shadowed = sorted(set(aliases) & set(registry))
    assert not shadowed, (
        f"{label}别名的来源名与登记表 key 重名: {shadowed}\n"
        f"这会让别名劫持同名直通 —— 登记表里那个 key 永远指不到, 且不报错。\n"
        f"要么把别名删掉(直通即可), 要么给登记表那个 key 改名。")


def test_registry_is_not_empty():
    """阴性对照: 登记表空了的话, 上面三条会**恒绿**(空集合与任何东西都不相交)。"""
    assert len(METRICS) >= 10 and len(DIMENSIONS) >= 5, (
        f"登记表看起来是空的或残缺(METRICS={len(METRICS)}, DIMENSIONS={len(DIMENSIONS)})"
        f" —— 上面几条断言会因此恒绿, 先修这个")


# ── 能力表里的 intent 名必须真实存在 ────────────────────────────────────────
# answer_contract 与 restaurant_ops_router 里有三张「哪些 intent 具备某能力」的表,
# 元素是**硬编的 intent 名字符串**。打错一个字、或某个 intent 后来改了名, 表现是:
#   · WINDOW_CAPABLE_INTENTS 少一个 → 那个 intent 的时间窗回显契约永远不被要求
#   · MARGIN_CAPABLE_INTENTS 少一个 → should_delegate 不再把毛利问句交给它
#   · _MONEY_BEARING_INTENTS 少一个 → **金额不再被 RBAC 剥零**(权限面!)
# 三种都是**静默放宽**, 一条日志都不会有。
# 判据: **硬编的标识符集合, 必须有闸证明每个标识符还活着。**

def test_capability_intent_sets_reference_real_intents():
    from smartbi.gold.restaurant import answer_contract as _c
    from smartbi.gold.restaurant.restaurant_intent import _INTENT_DESCRIPTIONS

    known = set(_INTENT_DESCRIPTIONS)
    assert len(known) >= 10, f"已知 intent 只有 {len(known)} 个, 上游取值坏了 —— 下面会恒绿"

    tables = {
        "WINDOW_CAPABLE_INTENTS": _c.WINDOW_CAPABLE_INTENTS,
        "MARGIN_CAPABLE_INTENTS": _c.MARGIN_CAPABLE_INTENTS,
    }
    try:
        from smartbi.gold.restaurant.restaurant_ops_router import _MONEY_BEARING_INTENTS
        tables["_MONEY_BEARING_INTENTS"] = _MONEY_BEARING_INTENTS
    except ImportError:                     # 该模块很重, 取不到就说清楚而不是静默跳过
        pytest.fail("restaurant_ops_router 导入失败 —— _MONEY_BEARING_INTENTS 无法校验")

    for name, table in tables.items():
        ghosts = sorted(set(table) - known)
        assert not ghosts, (
            f"{name} 里有不存在的 intent: {ghosts}\n"
            f"后果是**静默放宽**: 那条 intent 不再受该能力表约束, 且不报错。\n"
            f"(_MONEY_BEARING_INTENTS 尤其严重 —— 它决定金额要不要按角色剥零。)")
