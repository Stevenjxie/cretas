"""回放等价性回归闸 —— 路由规则一变就红, 逼你去重跑等价检查。

## 为什么需要它（owner 2026-08-13 裁定的前置）

指纹分层要判「哪些规则影响计划语义 / 哪些不影响」。如果那个划分做成手写清单，
**它错了是静默的**：某条规则改了却被归进「不影响」那层，晋升计划不作废，
然后开始答错，而没有任何东西会响。

⇒ 不要求分层分得完美，要求**分错了能被抓到**。这就是那个抓手。

## 闸的形态

⛔ 它**不**在 CI 里跑执行等价 —— 那需要 prod 数据，CI 够不着。
   它做的是把「有人改了路由规则」这件事**变成一次必须停下来的红**：

   路由规则改动 → `_routing_rules_fingerprint()` 变 → 本条红
   → 作者必须去服务器跑 `replay_equivalence_probe.py` → 贴读数 → 更新钉住的值

这是一道**程序性**闸：它不判断等价与否，它保证「没人能在不看等价读数的情况下
悄悄改掉路由规则」。判断留给探针 + 人。

## ⛔ 这道闸自己不许长成恒真式

钉住的是指纹的**值**，不是「指纹函数存在」。变异对照在
`test_gate_would_actually_fire_on_a_rule_change` 里：改任一条规则表 → 本条必红。
"""
import pytest

from smartbi.gold.restaurant import restaurant_intent as ri

#: 🔴 当前路由规则的指纹。**改动路由规则时不要直接改这个值** ——
#:    先按下面的流程跑等价检查，把读数贴进 PR，再更新它。
#:
#:    覆盖范围（见 `_routing_rules_fingerprint` 的 docstring）:
#:      _REQUEST_METRIC_RULES / _INTENT_DESCRIPTIONS /
#:      _REQUISITION_SPEND_RE / _render_aggregation_vocabulary()
PINNED_ROUTING_FINGERPRINT = "fd5508a3"

_HOWTO = """
🔴 路由规则变了 —— 停下来, 先量回放等价性, 再更新钉住的指纹。

  1) 在服务器上跑等价探针（需要 prod 数据, CI 够不着）:
       scp backend/python/smartbi/scripts/replay_equivalence_probe.py \\
           root@<host>:/tmp/cretas-probe/
       ssh <host> 'cd /tmp/cretas-probe && ./run.sh -u replay_equivalence_probe.py'

  2) 把三分类读数贴进 PR 描述（①执行等价 / ②执行不等价 / ③执行失败,
     并带阳性对照「A 遍命中晋升的条数」）。
     ⛔ 阳性对照为 0 时整轮读数作废 —— 那说明 A/B 两遍跑的是同一条路。

  3) ②/③ 非零时**不要**直接改指纹: 那意味着这次规则改动会让人审过的晋升
     产出不同的答案。先逐条读, 决定是重审那几条还是收回规则改动。

  4) 都确认过了, 再把 PINNED_ROUTING_FINGERPRINT 更新成新值。

⛔ 直接改这个常量让闸变绿, 等于把「晋升计划可能已经错了」这件事静音。
"""


def test_routing_fingerprint_matches_the_pinned_value():
    """路由规则一变就红。红了不是让你改这个值, 是让你去跑等价检查。"""
    actual = ri._routing_rules_fingerprint()
    assert actual == PINNED_ROUTING_FINGERPRINT, (
        f"路由规则指纹变了: 钉住 {PINNED_ROUTING_FINGERPRINT!r} → 实际 {actual!r}\n{_HOWTO}"
    )


def test_gate_would_actually_fire_on_a_rule_change(monkeypatch):
    """变异对照: 改一条规则 → 指纹变 → 上面那条必红。

    ⛔ 没有这条, `test_routing_fingerprint_matches_the_pinned_value` 可能只是
       「两边都从同一个函数取值」的恒真式 —— 本轮已经在别处栽过一次同形状的。
    """
    before = ri._routing_rules_fingerprint()

    # 往指标编译规则里加一条 —— 这正是 #2043 事故改动的那张表。
    patched = tuple(ri._REQUEST_METRIC_RULES) + (("__gate_probe__", ("__gate_probe__",)),)
    monkeypatch.setattr(ri, "_REQUEST_METRIC_RULES", patched)

    after = ri._routing_rules_fingerprint()
    assert after != before, (
        "改了 _REQUEST_METRIC_RULES 指纹却没变 —— 这道闸守不住任何东西")
    assert after != PINNED_ROUTING_FINGERPRINT


def test_intent_table_is_also_covered():
    """阴性对照的第二面: resolver 表也在指纹覆盖内。

    只覆盖指标规则的话, 「加/删一个 resolver」这类改动会静默通过。
    """
    before = ri._routing_rules_fingerprint()
    original = dict(ri._INTENT_DESCRIPTIONS)
    try:
        ri._INTENT_DESCRIPTIONS["__GATE_PROBE__"] = "闸的探针"
        assert ri._routing_rules_fingerprint() != before, (
            "加了一个 resolver 指纹却没变")
    finally:
        ri._INTENT_DESCRIPTIONS.clear()
        ri._INTENT_DESCRIPTIONS.update(original)
    assert ri._routing_rules_fingerprint() == before, "恢复后指纹没回到原值"


@pytest.mark.parametrize("attr", [
    "_REQUEST_METRIC_RULES",
    "_INTENT_DESCRIPTIONS",
    "_REQUISITION_SPEND_RE",
])
def test_the_three_rule_tables_still_exist(attr):
    """指纹的原料表被改名/删掉时, 上面那些断言会以 AttributeError 的形式挂 ——
    那不如在这里直接说清楚缺了哪张表。"""
    assert hasattr(ri, attr), f"指纹原料 {attr} 不见了 —— 指纹覆盖范围被悄悄改小了"
