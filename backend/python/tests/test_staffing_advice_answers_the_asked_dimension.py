"""缺口 #9 剩余部分：STAFFING_ADVICE 收不到 `dimensions`，问"哪家店"时
拿不到"哪家店"这句直接的答案。

## 缺陷（📏 MOCK_REST prod 实测，2026-08-18，非从代码推断）

同一个 session 问两句（"明天"视界）：

    「哪家店明天最缺人」        spec.dimensions=('store',)
    「明天怎么排班」            spec.dimensions=∅

两句拿到的答案**结构完全相同**：一份按缺口从大到小排序的 39 行
"门店 / 时段"全量列表，**没有一句话点名**缺口最大的是哪家店、哪个
时段、缺几个人 —— 只能靠"排在第一行"这件事自己推。

这与 `_store_lead_sentence` 文档记的 WASTAGE_TOP 修复前同一个形状：
数据在（`rows` 本来就按 `positive_gap` 降序排好了），答案不在（没有一句
话把它说出来）。判据是"他问的那件事有没有被直接说出来"，⛔ 不是
"那个数在不在正文里"。

`resolve_staffing_advice` 此前没有声明 `dimensions` 形参，
`resolve_by_code` 按签名过滤 kwargs，静默丢弃 —— 判断不了"是不是按
门店问"（判据见 `smartbi/scripts/_dims_gap.py` 的 `_prove_silent_drop`）。

## 这些用例守什么

- 承重：`dimensions` 真的到达 resolver，并且改变了产出。
- 阴性：不问门店时（`dimensions=()` / `("meal_period",)` / `("time",)`）
  逐字不变 —— meal_period 与 time 是这个 resolver 的基础轴 / 已由
  `horizon_from_question` 独立解析，与 DAYPART_PERFORMANCE 先例一致，
  不单独处理（见 docs/decisions/2026-08-18-缺口9剩余三个-设计卡.md）。
- 判据只走 `asked_by_store`，⛔ 不手写 `'store' in dimensions` 的变体。
- 零缺口时不编一个不存在的缺口。
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock

from smartbi.gold.restaurant.restaurant_intent_service import _RESOLVER_DIMENSIONS
from smartbi.gold.restaurant.restaurant_ops_router import (
    resolve_by_code,
    resolve_staffing_advice,
)


class _FakePool:
    """resolve_staffing_advice never touches the pool directly -- it only
    forwards it to RestaurantStaffingService, which is monkeypatched below."""


# ⚠️ 桩的形状必须是真实 SQL 会产出的形状（形态 B‴）：字段名与
# staffing_forecast.py 里 `summary_rows.append({...})` 的键逐个对齐
# （store_id/store_name/daypart/predicted_guests/recommended_staff/
#  current_staff/gap/positive_gap/confidence_pct 都在，不只挑测试需要的）。
_ROWS = [
    {
        "store_id": 1, "store_name": "模拟·静安嘉里中心店", "daypart": "晚市",
        "predicted_guests": 351, "recommended_staff": 35, "current_staff": 8,
        "gap": 27, "positive_gap": 27, "confidence_pct": 72.5,
    },
    {
        "store_id": 2, "store_name": "模拟·长宁龙之梦店", "daypart": "午市",
        "predicted_guests": 166, "recommended_staff": 18, "current_staff": 8,
        "gap": 10, "positive_gap": 10, "confidence_pct": 72.8,
    },
]

_ZERO_GAP_ROWS = [
    {
        "store_id": 1, "store_name": "模拟·静安嘉里中心店", "daypart": "晚市",
        "predicted_guests": 40, "recommended_staff": 6, "current_staff": 8,
        "gap": -2, "positive_gap": 0, "confidence_pct": 60.0,
    },
]


def _forecast_answer(rows=None):
    rows = _ROWS if rows is None else rows
    return {
        "answer_text": "**明天预测排班 FactBook**\n大模型解读已完成",
        "dashboard": {
            "summary": {
                "predicted_guests": 517, "recommended_staff": 53,
                "current_staff": 16, "positive_gap": 37, "confidence_pct": 72.6,
            },
            "summary_rows": rows,
        },
        "factbook": "grounded",
        "llm_used": True,
        "llm_numeric_authorship": False,
        "horizon": "tomorrow",
    }


def _patch_service(monkeypatch, rows=None):
    from smartbi.services.restaurant.staffing_forecast import RestaurantStaffingService

    monkeypatch.setattr(
        RestaurantStaffingService, "answer_question",
        AsyncMock(return_value=_forecast_answer(rows)),
    )


def _pos(text: str, needle: str) -> int:
    idx = text.find(needle)
    assert idx >= 0, f"正文里找不到 {needle!r}\n---\n{text}"
    return idx


# ── 登记表不变：本轮只补形参，不改能力承诺 ──────────────────────────────

def test_registered_dimensions_unchanged():
    assert _RESOLVER_DIMENSIONS["RESTAURANT_OPS_STAFFING_ADVICE"] == frozenset(
        {"store", "time", "meal_period"}
    )


# ── 接线的两半：产出端已发（既有测试守），消费端现在收得到 ─────────────────

def test_staffing_resolver_declares_dimensions_so_dispatch_cannot_drop_it():
    """消费端。`resolve_by_code` 按签名过滤 kwargs，没声明的静默丢掉。"""
    import inspect

    params = inspect.signature(resolve_staffing_advice).parameters
    assert "dimensions" in params, (
        "resolve_staffing_advice 没声明 dimensions —— resolve_by_code 会把它"
        "过滤掉，而且不报错"
    )


def test_dispatch_delivers_dimensions_and_it_changes_the_answer(monkeypatch):
    """端到端走真实分发器 —— 缺陷就住在这两段之间的那道过滤器上。"""
    _patch_service(monkeypatch)
    by_store = asyncio.run(resolve_by_code(
        "RESTAURANT_OPS_STAFFING_ADVICE", _FakePool(), "MOCK_REST",
        query="哪家店明天最缺人", dimensions=("store",), role="restaurant_manager",
    ))
    unasked = asyncio.run(resolve_by_code(
        "RESTAURANT_OPS_STAFFING_ADVICE", _FakePool(), "MOCK_REST",
        query="明天怎么排班", dimensions=(), role="restaurant_manager",
    ))
    assert by_store is not None and unasked is not None
    assert by_store.answer_text != unasked.answer_text, (
        "两轮逐字相同 —— 维度没到执行器"
    )


# ── 问了门店：点名缺口最大的那家店 + 时段 ────────────────────────────────

def test_store_dimension_names_the_top_gap_store_in_the_first_lines(monkeypatch):
    _patch_service(monkeypatch)
    answer = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", query="哪家店明天最缺人", dimensions=("store",),
    ))
    text = answer.answer_text
    assert "模拟·静安嘉里中心店" in text
    # 点名要出现在**开头那段**，⛔ 不是要老板自己数第几行
    head = text[:200]
    assert "模拟·静安嘉里中心店" in head, f"没在开头点名缺口最大的那家店:\n{head}"
    assert "晚市" in head
    assert "27" in head, "没把缺口数说出来"


def test_store_dimension_still_keeps_the_full_factbook(monkeypatch):
    """阴性对照：加一句话 ⛔ 不是删信息 —— FactBook 正文原样还在。"""
    _patch_service(monkeypatch)
    answer = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", query="哪家店明天最缺人", dimensions=("store",),
    ))
    assert "明天预测排班 FactBook" in answer.answer_text
    assert "大模型解读已完成" in answer.answer_text


# ── 没问门店：逐字不变（meal_period / time 是基础轴，不单独处理） ─────────

# ⚠️ 只跟 baseline **比相等**不够 —— 如果 gate 被变异成无条件触发, baseline
#    自己也会被同样"污染", 两边仍然相等, 断言看不出来(2026-08-18 实测: 变异
#    `if True or asked_by_store(...)` 时, 三条只比 baseline 的断言全绿放过)。
#    ⇒ 额外钉一个**绝对值**: 不问门店时原文必须**逐字等于** service 层
#    返回的那段原始文本, 不许多出任何前导句。
_UNMODIFIED_ANSWER_TEXT = _forecast_answer()["answer_text"]


def test_no_dimensions_is_byte_identical_to_before(monkeypatch):
    _patch_service(monkeypatch)
    baseline = asyncio.run(resolve_staffing_advice(_FakePool(), "MOCK_REST"))
    explicit_empty = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", dimensions=(),
    ))
    assert baseline.answer_text == explicit_empty.answer_text
    assert explicit_empty.answer_text == _UNMODIFIED_ANSWER_TEXT, (
        "不问门店却多出了内容 —— 那句判断被改成了无条件触发"
    )


def test_meal_period_dimension_alone_does_not_trigger_the_lead(monkeypatch):
    """⛔ 判据只走 asked_by_store —— meal_period 是基础轴, 与 DAYPART_
    PERFORMANCE 先例一致(该 resolver 登记维度相同, 也只特殊处理 store)。"""
    _patch_service(monkeypatch)
    meal_period_asked = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", dimensions=("meal_period",),
    ))
    assert meal_period_asked.answer_text == _UNMODIFIED_ANSWER_TEXT, (
        "问了 meal_period 却触发了店铺点名句 —— gate 判据不该走这一维"
    )


def test_time_dimension_alone_does_not_trigger_the_lead(monkeypatch):
    _patch_service(monkeypatch)
    time_asked = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", dimensions=("time",),
    ))
    assert time_asked.answer_text == _UNMODIFIED_ANSWER_TEXT, (
        "问了 time 却触发了店铺点名句 —— gate 判据不该走这一维"
    )


# ── 零缺口：不编一个不存在的缺口 ────────────────────────────────────────

def test_store_dimension_with_zero_gap_does_not_invent_a_shortage(monkeypatch):
    _patch_service(monkeypatch, rows=_ZERO_GAP_ROWS)
    answer = asyncio.run(resolve_staffing_advice(
        _FakePool(), "MOCK_REST", query="哪家店明天最缺人", dimensions=("store",),
    ))
    text = answer.answer_text
    assert "没有哪个门店" in text or "已经够" in text, text[:300]
    # ⛔ 不倒着把负缺口读成"缺 -2 人"这种荒谬数字
    assert "缺 -2 人" not in text


# ── 判据只走 asked_by_store, ⛔ 不手写变体 ───────────────────────────────

def test_the_gate_is_asked_by_store_not_a_handwritten_variant():
    import ast
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router as rr

    tree = ast.parse(inspect.getsource(rr))
    node = next(
        n for n in ast.walk(tree)
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))
        and n.name == "resolve_staffing_advice"
    )
    calls = {
        c.func.id for c in ast.walk(node)
        if isinstance(c, ast.Call) and isinstance(c.func, ast.Name)
    }
    assert "asked_by_store" in calls, (
        "resolve_staffing_advice 没走 asked_by_store —— 手写变体会在另一套"
        "维度写法上失效"
    )
