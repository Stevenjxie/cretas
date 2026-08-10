"""同时问多个指标时, 菜品答案必须每样都答到。

## 为什么需要这条闸

`_scoped_dish_metric_answer` 结尾是一串**互斥的 if**:

    if asks_sales   and not asks_cost and not asks_margin: → 销量+营收
    if asks_revenue and not asks_cost and not asks_margin: → 营收+销量
    if asks_cost    and not asks_margin:                   → 成本
    if asks_margin ...:                                    → 营收+成本+毛利+毛利率

问「米饭的**销量、毛利率和成本**」时, 前三条被 `not asks_margin` 全部排除,
只剩最后一条 —— 而它**原先不含销量**。结果: 数其实算出来了(qty_text 就在手边),
但 Answer Contract 判 `missing=["request_coverage"]`, **把整份答案扔掉**, 用户
看到「本次结果没有可靠覆盖问题中要求的全部指标」。

2026-08-10 实测: 飞轮 miss 台账里这条问句累计 **47 次**, 是当时被真实问到最多的
「答不出来」。

判据: **互斥 if 链表达不了「多选」** —— 每加一个可问指标, 组合数翻倍, 而漏掉的
      组合表现为「答非所问被契约拦下」, 不报错、不告警。

## 这条闸怎么写才不是恒真式

⛔ 期望词**从 answer_contract._REQUEST_TEXT_TOKENS 读**, 不在这里另写一份 ——
   那样就成了第二张手写词表, 契约改了这里不会红。

⛔ 入口用真正的 `_scoped_dish_metric_answer(entry, ..., query=<原始问句>)`, 传
   **用户原话**而不是自己拼好的 asks_* 布尔 —— 缺陷发生在「问句怎么被解析成
   asks_*」和「asks_* 怎么选分支」的**接缝**上, 绕过前半段就等于把出事的那一
   半排除在被测范围外。(本文件的第一版正是那么写的: 它调了一个根本不存在的
   `_dish_summary_answer(asks_sales=..., asks_margin=...)`, 连 import 都过不去。)
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import answer_contract as _contract
from smartbi.gold.restaurant.restaurant_ops_router import _scoped_dish_metric_answer

_WINDOW = "本月（2026-08-01 至 2026-08-10）"

# 一份成本齐全的菜品事实 —— 字段名与 _scoped_dish_metric_answer 读的完全一致。
_ENTRY = {
    "name": "米饭",
    "qty": 1234.0,
    "revenue": 40410.0,
    "bills": 900,
    "has_cost": True,
    "food_cost_unit": 8.85,
    "total_cost": 10918.0,
    "gross_profit": 29492.0,
    "margin_rate": 0.7298,
}


def _tokens(metric: str) -> tuple:
    toks = _contract._REQUEST_TEXT_TOKENS.get(metric, ())
    assert toks, (
        f"契约的词表里没有 {metric} —— 要么改名了, 要么这条断言该更新。"
        f"⛔ 不要在本文件另写一份词表来绕过。")
    return toks


@pytest.mark.parametrize(
    "query,asked",
    [
        # 飞轮台账里 47 次的那条原话
        ("本月全部门店米饭的销量、毛利率和成本分别是多少",
         ("sales_volume", "gross_margin", "recipe_cost")),
        ("米饭的销量和毛利率是多少", ("sales_volume", "gross_margin")),
        ("米饭的销量和成本分别是多少", ("sales_volume", "recipe_cost")),
    ],
)
def test_multi_metric_question_is_fully_answered(query, asked):
    """问几样就要答到几样 —— 少一样契约就会把整份答案扔掉。"""
    text = _scoped_dish_metric_answer(_ENTRY, window_label=_WINDOW, query=query)
    assert text, f"这条问句根本没产出答案: {query}"
    for metric in asked:
        toks = _tokens(metric)
        assert any(t in text for t in toks), (
            f"问了 {asked}, 但答案里没有 {metric} 的任何一个词 {toks}。\n"
            f"Answer Contract 会判 missing=['request_coverage'] 并**扔掉整份答案**,\n"
            f"用户看到的是拒答, 而数其实算出来了。\n实际答案: {text[:200]}")


def test_single_metric_questions_stay_narrow():
    """阴性对照: 只问一样时**不许**把整份毛利报告倒出来。

    没有这条, 「每样都答到」可以靠"永远全答"通过 —— 那会退回本函数上游注释
    里说的另一个毛病(问「销量呢」却收到完整毛利报告), 闸就成了摆设。
    """
    text = _scoped_dish_metric_answer(
        _ENTRY, window_label=_WINDOW, query="米饭的销量是多少")
    assert text
    for tok in _tokens("gross_margin"):
        assert tok not in text, (
            f"只问销量, 答案里却出现了毛利词「{tok}」: {text[:200]}")


def test_contract_token_table_is_the_real_one():
    """阴性对照: 词表读空的话, 上面那条会因为 _tokens 断言而红, 不会恒绿。"""
    table = _contract._REQUEST_TEXT_TOKENS
    assert len(table) >= 10, f"契约词表只有 {len(table)} 项, 读法多半坏了"
    assert "sales_volume" in table and "gross_margin" in table
