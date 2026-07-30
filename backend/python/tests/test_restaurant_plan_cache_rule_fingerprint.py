"""改了路由规则, 计划缓存必须自动失效 —— 不能靠人记得改版本号。

2026-07-31 实测: #2043 改了指标编译规则(「采购花了多少钱」→ requisition_cost),
**部署几小时后** RES_3101_009 上这句仍被路由成 RECIPE_COST, 且
`source_tier=plan_cache` —— 重放的是**修复前编译的计划**。

`_semantic_plan_cache_key` 本来就把 `_PLAN_VERSION` 拼进键, 注释也写着「未来
契约修订不会重放旧计划」。问题在于那要**有人记得手改** —— 而改规则的人(我)
没改。一个靠纪律维持的失效机制, 在最需要它的那次就没生效。

所以把版本改成「手写版本号 + 规则表指纹」: 规则一变, 键就变, 旧计划自然够不到。
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


def test_cache_key_carries_the_rule_fingerprint():
    key = ri._semantic_plan_cache_key("MOCK_REST", "最近30天采购花了多少钱")
    assert key[0] == "MOCK_REST"
    assert ri._PLAN_VERSION in key[2]
    assert ri._routing_rules_fingerprint() in key[2], (
        "缓存键没带规则指纹 —— 改了路由规则也不会失效"
    )


def test_changing_the_metric_rules_changes_the_key(monkeypatch):
    """这是本测试的核心: 改指标编译规则 → 键必须变。

    #2043 改的正是 `_REQUEST_METRIC_RULES`; 当时键没变, 于是旧计划继续被重放。
    """
    query = "最近30天采购花了多少钱"
    before = ri._semantic_plan_cache_key("MOCK_REST", query)
    patched = ri._REQUEST_METRIC_RULES + (("some_new_metric", ("某个新词",)),)
    monkeypatch.setattr(ri, "_REQUEST_METRIC_RULES", patched)
    after = ri._semantic_plan_cache_key("MOCK_REST", query)
    assert before != after, "改了指标规则表, 缓存键却没变 —— 旧计划会继续被重放"


def test_changing_the_intent_catalogue_changes_the_key(monkeypatch):
    """新增/删除一个 resolver 也是路由语义变更。"""
    query = "最近30天采购花了多少钱"
    before = ri._semantic_plan_cache_key("MOCK_REST", query)
    patched = dict(ri._INTENT_DESCRIPTIONS)
    patched["RESTAURANT_OPS_SOMETHING_NEW"] = "新增能力"
    monkeypatch.setattr(ri, "_INTENT_DESCRIPTIONS", patched)
    assert ri._semantic_plan_cache_key("MOCK_REST", query) != before


def test_unrelated_state_does_not_churn_the_key():
    """指纹只跟规则走 —— 否则每次进程重启都清空缓存, 零 token 出口就废了。"""
    query = "最近30天采购花了多少钱"
    assert (
        ri._semantic_plan_cache_key("MOCK_REST", query)
        == ri._semantic_plan_cache_key("MOCK_REST", query)
    )
    assert len(ri._routing_rules_fingerprint()) == 8


@pytest.mark.parametrize(
    "a, b",
    [("MOCK_REST", "RES_3101_009"), ("MOCK_REST", "R_GML_DEMO")],
)
def test_tenants_never_share_a_cached_plan(a, b):
    query = "最近30天采购花了多少钱"
    assert ri._semantic_plan_cache_key(a, query) != ri._semantic_plan_cache_key(b, query)
