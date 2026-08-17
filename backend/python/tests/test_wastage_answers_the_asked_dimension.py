"""追问要按**问的那一层**重新组织答案 —— 规划器算出的维度必须到达执行器。

## 缺陷（📏 MOCK_REST prod 实测，2026-08-18，非从代码推断）

同一个 session 连问两句：

    第1问 最近损耗怎么样   spec.dimensions=('ingredient',)  正文 md5[:8]=bd1d6675  1249 字
    第2问 哪家店最多       spec.dimensions=('store',)       正文 md5[:8]=bd1d6675  1249 字
                                                            ↑ **逐字相同**

规划器把维度算对了（两轮确实不同），执行器**收不到**：

  1. `_resolver_kwargs` 的出口字典里根本没有 `dimensions` 这个键
     （📏 AST 数出口键 = role/query/requested_metrics/analysis_action/
       ranking_direction/ranking_limit/days/date_range/window_label/
       comparison_* —— 12 个，没有 dimensions）；
  2. 就算传了，`resolve_by_code` 也会按签名过滤，而 18 个 resolver 里
     `resolve_wastage_top` 没有声明它 ⇒ **静默丢掉**，不报错。

这与 `test_restaurant_wastage_window.py` 记的是**同一个形状**（那次丢的是
`date_range`/`window_label`）：签名不声明就静默丢，症状是「答案对某个输入
完全不敏感」而不是异常。

## 这些用例守什么

- 承重：维度真的到达 resolver，并且**改变了产出**（不是「传到了但没人用」）。
- 阴性：没问门店时逐字不变；问了门店时食材表**仍在**（换顺序 ⛔ 不是删信息）。
- 定义五：门店这一层没有数据时，明说缺什么、他要干什么，⛔ 不静默回落成
  食材表（那会让他以为自己问的问题被回答了）。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    resolve_by_code,
    resolve_wastage_top,
)


class _RecordingConn:
    def __init__(
        self,
        *,
        fetch_map: Optional[Dict[str, List[Any]]] = None,
        fetchrow_map: Optional[Dict[str, Any]] = None,
    ):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self.fetch_calls: List[Tuple[str, tuple]] = []

    async def execute(self, sql, *args):
        return "SET"

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        for key, rows in self._fetch_map.items():
            if key in sql:
                return rows
        return []

    async def fetchrow(self, sql, *args):
        for key, row in self._fetchrow_map.items():
            if key in sql:
                return row
        return None

    async def fetchval(self, sql, *args):
        return None


class _RecordingPool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


# ⚠️ 桩的形状必须是**真实 SQL 会产出**的形状（形态 B‴）：
#   - 食材行来自 `JOIN dim_ingredient`，列名 name/category/unit/qty/cost
#   - 门店行来自 `wastage_cost_by_store` + `JOIN dim_store`，列名 store_name/cost
#   - 门店金额之和 < 总额，正是 prod 上的真实形状（存量行 store_id 为 NULL）
_TOP_ROWS = [
    {"name": "大米", "category": "米面", "unit": "kg", "qty": 2024.35, "cost": 12550.91},
    {"name": "鲈鱼", "category": "水产", "unit": "kg", "qty": 1991.25, "cost": 71684.94},
]
_TYPE_ROWS = [
    {"type": "PROCESSING", "cost": 197274.56},
    {"type": "SPOILED", "cost": 88115.39},
]
_TOTALS = {"total_qty": 11784.28, "total_cost": 303351.97, "total_count": 12306}
_STORE_ROWS = [
    {"store_name": "模拟·长宁龙之梦店", "cost": 31436.37},
    {"store_name": "模拟·陆家嘴正大店", "cost": 31320.99},
    {"store_name": "模拟·打浦桥日月光店", "cost": 21985.44},
]


def _pool_conn(*, store_rows=None) -> _RecordingConn:
    return _RecordingConn(
        fetch_map={
            "JOIN dim_ingredient": _TOP_ROWS,
            "wastage_cost_by_type": _TYPE_ROWS,
            "wastage_cost_by_store": _STORE_ROWS if store_rows is None else store_rows,
        },
        fetchrow_map={"agg_restaurant_daily_totals": _TOTALS},
    )


async def _answer(dimensions, *, store_rows=None, query="最近损耗怎么样"):
    conn = _pool_conn(store_rows=store_rows)
    return await resolve_wastage_top(
        _RecordingPool(conn), "MOCK_REST", days=30,
        query=query, dimensions=dimensions,
    )


def _pos(text: str, needle: str) -> int:
    idx = text.find(needle)
    assert idx >= 0, f"正文里找不到 {needle!r}\n---\n{text}"
    return idx


# ── 接线的两半：产出端发了 / 消费端收得到 ────────────────────────────────

def test_resolver_kwargs_forward_dimensions():
    """产出端。⛔ 出口没有这个键时，下面所有断言都只是在测一个不会发生的输入。"""
    from smartbi.gold.restaurant import restaurant_intent_service as svc

    spec = _spec(dimensions=("store",))
    kwargs = svc._resolver_kwargs(spec, "restaurant_manager", "哪家店最多")
    assert "dimensions" in kwargs, (
        "`_resolver_kwargs` 不发 dimensions —— resolver 声明得再对也收不到"
    )
    assert kwargs["dimensions"] == ("store",)


def test_wastage_resolver_declares_dimensions_so_dispatch_cannot_drop_it():
    """消费端。`resolve_by_code` 按签名过滤 kwargs，没声明的**静默**丢掉。"""
    import inspect

    params = inspect.signature(resolve_wastage_top).parameters
    assert "dimensions" in params, (
        "resolve_wastage_top 没声明 dimensions —— resolve_by_code 会把它过滤掉，"
        "而且不报错"
    )


@pytest.mark.asyncio
async def test_dispatch_delivers_dimensions_and_it_changes_the_answer():
    """端到端走真实分发器 —— 缺陷就住在这两段之间的那道过滤器上。

    ⚠️ 只断言「参数到达了」不够：到达而没人用与没到达在用户侧是同一个症状。
       所以这里断言的是**产出变了**。
    """
    by_store = await resolve_by_code(
        "RESTAURANT_OPS_WASTAGE_TOP", _RecordingPool(_pool_conn()), "MOCK_REST",
        days=30, query="哪家店最多", dimensions=("store",),
        role="restaurant_manager",
    )
    by_ingredient = await resolve_by_code(
        "RESTAURANT_OPS_WASTAGE_TOP", _RecordingPool(_pool_conn()), "MOCK_REST",
        days=30, query="最近损耗怎么样", dimensions=("ingredient",),
        role="restaurant_manager",
    )
    assert by_store is not None and by_ingredient is not None
    assert by_store.answer_text != by_ingredient.answer_text, (
        "两轮逐字相同 —— 这正是 prod 实测的 bd1d6675，维度没到执行器"
    )


# ── 问了门店：答案要按门店组织 ────────────────────────────────────────────

@pytest.mark.asyncio
async def test_store_dimension_names_the_top_store_in_the_first_lines():
    answer = await _answer(("store",), query="哪家店最多")
    text = answer.answer_text
    assert "模拟·长宁龙之梦店" in text
    # 点名要出现在**开头那段**，⛔ 不是埋在第 60% 处的一张表里
    head = text[:260]
    assert "模拟·长宁龙之梦店" in head, f"没在开头点名最多的那家店:\n{head}"


@pytest.mark.asyncio
async def test_store_dimension_puts_the_store_table_before_the_ingredient_table():
    answer = await _answer(("store",), query="哪家店最多")
    text = answer.answer_text
    assert _pos(text, "各门店损耗金额") < _pos(text, "损耗食材前"), (
        "问的是门店，食材表却排在门店表前面"
    )


@pytest.mark.asyncio
async def test_store_dimension_still_keeps_the_ingredient_table():
    """阴性对照：换顺序 ⛔ 不是删信息。"""
    answer = await _answer(("store",), query="哪家店最多")
    assert "损耗食材前" in answer.answer_text
    assert "大米" in answer.answer_text


@pytest.mark.asyncio
async def test_store_dimension_states_the_spread_so_he_can_decide_whether_to_chase():
    """定义二：说一件他没想到的事 —— 最高比最低差多少，值不值得单独去查。"""
    answer = await _answer(("store",), query="哪家店最多")
    assert "%" in answer.answer_text
    assert "模拟·打浦桥日月光店" in answer.answer_text, "没说最少的那家是谁"


# ── 没问门店：逐字不变 ───────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_ingredient_dimension_is_byte_identical_to_no_dimension():
    """阴性对照：这次改动 ⛔ 不许动默认路径。"""
    asked = await _answer(("ingredient",))
    unasked = await _answer(())
    assert asked.answer_text == unasked.answer_text
    assert _pos(asked.answer_text, "损耗食材前") < _pos(asked.answer_text, "各门店损耗金额")


# ── 门店这一层没有数据：说清楚，⛔ 不静默回落 ────────────────────────────

@pytest.mark.asyncio
async def test_store_dimension_without_store_rows_says_what_is_missing():
    """定义五：缺什么 / 怎么拿到 / 他自己要干什么。

    📏 真实上游确实会给出空集：存量损耗行 `store_id` 为 NULL，聚合侧
    `WHERE store_id IS NOT NULL` 把它们排除掉 —— 于是这个租户按门店一行都没有。
    ⛔ 这时候把食材表原样发回去，等于让他以为「哪家店最多」被回答了。
    """
    answer = await _answer(("store",), store_rows=[], query="哪家店最多")
    text = answer.answer_text
    assert "没有记门店" in text or "分不到店" in text, text[:400]
    assert "各门店损耗金额" not in text, "没有门店数据却出了门店表"
    # 仍然要给他这一轮能看的东西
    assert "损耗食材前" in text


# ── helper ───────────────────────────────────────────────────────────────

def _spec(**overrides):
    from smartbi.gold.restaurant.restaurant_intent import RestaurantQuerySpec

    base: Dict[str, Any] = dict(
        intent="RESTAURANT_OPS_WASTAGE_TOP",
        domain="restaurant",
        date_range=(None, None),
        window_label="最近30天",
        relative_window=True,
        metrics=("wastage",),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
    )
    base.update(overrides)
    return RestaurantQuerySpec(**base)
