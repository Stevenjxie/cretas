"""同一个会话里, 门店范围只问一次 —— 之后串钩上一轮的选择。

Measured on prod 2026-07-30 (MOCK_REST, 10 家门店): 多店租户几乎每问都要先答
「哪几家门店」。实测它**会收敛**(一轮问一个缺失槽位), 所以不是 bug, 但每个问题
都重问一遍是最伤体感的一环。

Steve 2026-07-30 拍板: 同一个 chat 串钩之前的选择; 想换范围就在回复末尾给按钮。

不建新表 —— 生产已经把上一轮的 `structured_context` 传进 planner(gold_reads.py
`conversation_history[].context`), 而它**本来就带 `store_scope` / `store_names`**。
所以「记住」= 从会话历史里读, 不是另起一份状态。

⛔ 边界(都在下面钉住):
  * 本轮**明确说了**范围 → 以本轮为准, 不许被历史覆盖
  * 历史里没有范围 → 照旧发问, 不许瞎猜
  * 单店租户走既有的 `store_scope="single"` 分支, 与本特性无关
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


def _history(store_scope=None, store_names=()):
    context = {}
    if store_scope:
        context["store_scope"] = store_scope
    if store_names:
        context["store_names"] = list(store_names)
    return [{"q": "上个月营收多少", "a_summary": "…", "context": context}]


def test_extracts_all_store_scope_from_history():
    got = ri._inherited_store_scope(_history(store_scope="all"))
    assert got == ("all", ())


def test_extracts_named_stores_from_history():
    got = ri._inherited_store_scope(
        _history(store_scope="multiple", store_names=("模拟·徐汇美罗城店",))
    )
    assert got == ("multiple", ("模拟·徐汇美罗城店",))


@pytest.mark.parametrize(
    "history",
    [None, [], [{"q": "x", "a_summary": "y"}], _history(), [{"context": None}]],
)
def test_no_scope_in_history_means_no_inheritance(history):
    """没有就是没有 —— 不许猜一个范围出来, 那会让答案悄悄换口径。"""
    assert ri._inherited_store_scope(history) is None


def test_malformed_history_never_raises():
    """历史是外部传进来的(JSONB/前端), 结构不对时必须退化成"不继承",
    不能把整条问答打挂。"""
    for junk in ("not a list", [42], [{"context": "not a dict"}], [{"context": []}]):
        assert ri._inherited_store_scope(junk) is None


def test_latest_turn_wins():
    """会话里换过范围时, 串钩的是最近一次, 不是第一次。"""
    history = [
        {"q": "a", "context": {"store_scope": "all"}},
        {"q": "b", "context": {"store_scope": "multiple",
                               "store_names": ["模拟·杨浦五角场店"]}},
    ]
    assert ri._inherited_store_scope(history) == (
        "multiple", ("模拟·杨浦五角场店",),
    )


# ── 换范围按钮: 范围隐式串钩之后, 必须留一个显式出口 ──────────────────────

from smartbi.gold.restaurant.restaurant_intent_service import _suggested_followups


def test_named_scope_offers_all_stores_and_other_stores():
    got = _suggested_followups({
        "store_scope": "multiple",
        "store_names": ["A店"],
        "store_options": ["A店", "B店", "C店"],
    })
    questions = [item["question"] for item in got]
    assert "全部门店" in questions
    assert "B店" in questions
    assert "A店" not in questions, "当前已选的门店不该再作为切换项"


def test_all_scope_offers_drilling_into_single_stores():
    got = _suggested_followups({
        "store_scope": "all",
        "store_names": [],
        "store_options": ["A店", "B店", "C店", "D店"],
    })
    questions = [item["question"] for item in got]
    assert "全部门店" not in questions, "已经是全部门店了, 再给这个按钮是噪音"
    assert questions[:3] == ["A店", "B店", "C店"]


def test_single_store_tenant_gets_no_scope_buttons():
    """单店租户没有第二个选择 —— 给按钮纯属噪音。"""
    assert _suggested_followups({
        "store_scope": "single", "store_options": ["唯一门店"],
    }) == []


def test_no_scope_means_no_scope_buttons():
    assert _suggested_followups({"store_options": ["A店", "B店"]}) == []


def test_followups_are_capped_and_deduped():
    got = _suggested_followups({
        "store_scope": "multiple",
        "store_names": [],
        "store_options": [f"店{i}" for i in range(10)],
    })
    questions = [item["question"] for item in got]
    assert len(got) <= 4
    assert len(questions) == len(set(questions))
