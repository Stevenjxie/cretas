"""B-1 接线闸 —— 断言跑在**产品真实入口** `_maybe_append_dish_table` 上。

## 为什么单独有这一条

`daily_table` 那道闸只证明「渲染函数排版正确」。它对**没人调它**是盲的 ——
而这正是本轮要修的那个形态: `output_preference` 被算了出来、放进了响应,
**零消费端**, 于是「列个表」和不说话出来的东西一模一样(形态 B)。

⇒ 这里断言的是「谁在什么条件下调它」, ⛔ 不是「它排版对不对」。

## fail-open 也要断言

拼表格炸了不许让问答失败, 但**必须留痕** —— 否则「表格从来没出现过」
会长得和「这个租户没有菜品数据」一模一样。
"""
import asyncio
import logging

import pytest

from smartbi.gold.restaurant import restaurant_intent_service as svc
from smartbi.gold.restaurant.restaurant_intent import (
    OUTPUT_FORM_CHART,
    OUTPUT_FORM_TABLE,
    OUTPUT_FORM_TEXT,
)

MARGIN = "RESTAURANT_OPS_GROSS_MARGIN"
SUMMARY = "RESTAURANT_OPS_SALES_SUMMARY"
BASE = "今天罗氏虾卖得最好。"


class _Spec:
    def __init__(self, intent, date_range=(None, None)):
        self.intent = intent
        self.date_range = date_range


def _fake_data():
    return {
        "dishes": [
            {"name": "罗氏虾", "qty": 10, "revenue": 500.0, "totalCost": 200.0,
             "grossProfit": 300.0, "marginRate": 0.6, "hasCost": True, "isEstimated": False},
        ],
        "totalRevenue": 500.0, "totalProfit": 300.0, "industryDefaultCostRatio": 0.32,
    }


@pytest.fixture
def stub_margins(monkeypatch):
    """桩掉出数, ⛔ 只桩外部 IO —— 判定逻辑走真的。"""
    calls = []

    async def _fake(pool, factory_id, **kwargs):
        calls.append((factory_id, kwargs))
        return _fake_data()

    # ⚠️ 必须打在 `dish_margin` 模块上 —— 被测函数是**函数内 import**,
    #    每次调用重新取属性, 所以打得到(见 tests/test_mutation_targets_are_reachable)。
    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _fake
    )
    return calls


def _run(spec, pref):
    return asyncio.run(
        svc._maybe_append_dish_table(object(), "MOCK_REST", spec, BASE, pref)
    )


def test_table_appended_when_preference_asks_for_it(stub_margins):
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out.startswith(BASE), "⛔ 原答案不许被表格顶掉 —— 表格是追加不是替换"
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in out
    assert len(stub_margins) == 1, "应当且仅当需要时才查一次"


def test_no_table_when_preference_is_text_only(stub_margins):
    """🔴 这条正是「说『别用表格』反而锁死表格」修好之后才有意义的那一条。"""
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT,))
    assert out == BASE
    assert stub_margins == [], "⛔ 不要表格时不该白查一次库"


def test_table_appended_for_daily_close_intent(stub_margins):
    """🔴 日结主路 —— 老板打烊那句话的产出者就是 SALES_SUMMARY 的 resolver。

    我第一版把它**排除**了(理由: 门店级概览下塞菜品表是换了粒度)。
    理由本身站得住, 但它是从意图描述的字面推的 ——
    ⇒ 结果是表格在**唯一要它的那条路上永远不出现**, 接线接了个寂寞。
    这条断言就是钉住那个错误不许回来。
    """
    out = _run(_Spec(SUMMARY), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out.startswith(BASE)
    assert "| 菜品 | 营收 | 成本 | 毛利 |" in out
    assert len(stub_margins) == 1


def test_no_table_for_unrelated_intent(stub_margins):
    """⛔ 不是所有意图都配一张菜品表 —— 库存预警下塞菜品毛利表是答非所问。"""
    out = _run(_Spec("RESTAURANT_OPS_INVENTORY_WARNING"),
               (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE
    assert stub_margins == []


def test_chart_preference_alone_does_not_trigger_table(stub_margins):
    out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_CHART))
    assert out == BASE
    assert stub_margins == []


def test_spec_date_range_is_forwarded(stub_margins):
    """时间窗只是**参数** —— 它必须原样传下去, ⛔ 不许被默认 30 天顶掉。"""
    import datetime

    rng = (datetime.date(2026, 8, 15), datetime.date(2026, 8, 15))
    _run(_Spec(MARGIN, date_range=rng), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert stub_margins[0][1] == {"date_range": rng}


def test_failure_is_fail_open_but_leaves_a_trace(monkeypatch, caplog):
    async def _boom(pool, factory_id, **kwargs):
        raise RuntimeError("pg down")

    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _boom
    )
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE, "拼表格失败 ⛔ 不许让一次问答失败"
    assert any("dish table render failed" in r.message for r in caplog.records), (
        "⛔ 静默失败 —— 那样「表格从来没出现过」会长得和「没有菜品数据」一模一样"
    )


def test_the_helper_is_actually_called_and_its_result_is_kept():
    """🔴 上面七条**全都直接调** helper —— 接线被删掉它们照样绿。

    这正是本轮要修的形态本身: 机制在、测试绿、**生产上没人调它**。
    ⇒ 这一条守的是「谁调它」, 而且守到「调完之后结果有没有被留住」——
      赋给别的变量 = 算了但丢了(产出端有了 ≠ 消费端收得到)。

    ⛔ 用 AST 数真正的 `Call` 节点, ⛔ 不用字符串计数 ——
       docstring 里提到函数名的那几行会被文本 grep 数进去(本仓记过三次)。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(svc))
    assigned_to_answer = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        value = node.value
        if isinstance(value, ast.Await):
            value = value.value
        if not (isinstance(value, ast.Call) and isinstance(value.func, ast.Name)):
            continue
        if value.func.id != "_maybe_append_dish_table":
            continue
        targets = [t.id for t in node.targets if isinstance(t, ast.Name)]
        assigned_to_answer.extend(targets)

    assert assigned_to_answer, (
        "⛔ 生产路径上没有 `answer_text = await _maybe_append_dish_table(...)` —— "
        "接线断了, 上面七条断言全部沦为「守着一个没人调的函数」"
    )
    assert assigned_to_answer == ["answer_text"], (
        f"结果被赋给了 {assigned_to_answer} 而不是 answer_text —— 算了但丢了"
    )


def test_empty_result_does_not_append_an_empty_table(monkeypatch, caplog):
    async def _empty(pool, factory_id, **kwargs):
        return {"dishes": [], "totalRevenue": 0.0, "totalProfit": 0.0}

    monkeypatch.setattr(
        "smartbi.gold.restaurant.dish_margin.compute_dish_margins", _empty
    )
    with caplog.at_level(logging.WARNING):
        out = _run(_Spec(MARGIN), (OUTPUT_FORM_TEXT, OUTPUT_FORM_TABLE))
    assert out == BASE, "⛔ 不拼一张只有表头的空表"
    assert any("dish table empty" in r.message for r in caplog.records)
