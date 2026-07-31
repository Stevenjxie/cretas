"""领料趋势 / 盘点差异也必须按请求的时间窗取数。

PR #2076 修掉了 `resolve_wastage_top` 的「无视请求窗口、恒答滚动 N 天」。
2026-07-31 按部门盘点 resolver 时发现**同一个缺陷还活着**，而且就在旁边:

    resolve_requisition_trend   date >= CURRENT_DATE - ($2::int)   title: 近{days}天…
    resolve_stock_shortage      date >= CURRENT_DATE - ($2::int)   title: 近{days}天…

与 `resolve_wastage_top` 修复前**逐字节同形**。机制也一样:

  1. `_resolver_kwargs` 把 `spec.date_range` 压成 `days = (end - start).days + 1`;
  2. `resolve_by_code` 按签名过滤 kwargs, 两者都没声明 `date_range` → **静默丢弃**。

于是问「上个月领料趋势」拿到的是今天往前 30 天(即本月), 标题却写「近 30 天」——
标题是诚实的, 错的是取数。

## 为什么这两个特别要紧

它们是**运营部门**的主力 resolver。四部门驾驶舱在页头给了期间选择器, 用户把它改成
「上个月」, 运营页 3 个 resolver 里有 2 个答的还是滚动窗 —— 期间选择器做出了一个
页面兑现不了的承诺, 而且不报错。

声明 `date_range` 之后, 两者还会**自动获得换时间范围按钮**(PR #2078 的闸是
`resolver_supports_explicit_window`, 判据就是签名里有没有它)。
"""
from __future__ import annotations

from datetime import date
from typing import Any, Dict, List, Optional, Tuple

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import (
    resolve_requisition_trend,
    resolve_stock_shortage,
    resolver_supports_explicit_window,
)


class _RecordingConn:
    def __init__(self, *, fetch_map=None, fetchrow_map=None):
        self._fetch_map = fetch_map or {}
        self._fetchrow_map = fetchrow_map or {}
        self.fetch_calls: List[Tuple[str, tuple]] = []
        self.fetchrow_calls: List[Tuple[str, tuple]] = []

    async def execute(self, sql, *args):
        return "SET"

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        for key, rows in self._fetch_map.items():
            if key in sql:
                return rows
        return []

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        for key, row in self._fetchrow_map.items():
            if key in sql:
                return row
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


_JUNE = (date(2026, 6, 1), date(2026, 6, 30))

_TREND_ROWS = [
    {"date": date(2026, 6, 1), "qty": 120.0, "cost": 4200.0},
    {"date": date(2026, 6, 2), "qty": 98.0, "cost": 3100.0},
]
_ING_ROWS = [
    {"name": "三文鱼", "category": "水产", "unit": "kg", "qty": 88.0,
     "cost": 4200.0, "shortage_qty": 12.5},
    {"name": "土豆", "category": "蔬菜", "unit": "kg", "qty": 300.0,
     "cost": 180.0, "shortage_qty": 40.0},
]
# 两个 resolver 的合计查询返回不同的列名, fake 得同时供上, 否则 KeyError 会被
# 误读成产品缺陷(第一轮就栽在这里)。
_TOTALS = {
    # resolve_stock_shortage 的 totals
    "shortage": 52.5, "surplus": 8.0, "count": 12,
    # 通用/领料侧
    "total_qty": 218.0, "total_cost": 7300.0, "total_count": 25,
}


def _conn() -> _RecordingConn:
    return _RecordingConn(
        fetch_map={
            "agg_restaurant_daily_totals": _TREND_ROWS,
            "JOIN dim_ingredient": _ING_ROWS,
        },
        fetchrow_map={"agg_restaurant_daily_totals": _TOTALS},
    )


def _all(conn: _RecordingConn):
    return conn.fetch_calls + conn.fetchrow_calls


RESOLVERS = [
    pytest.param(resolve_requisition_trend, "RESTAURANT_OPS_REQUISITION_TREND",
                 id="requisition_trend"),
    pytest.param(resolve_stock_shortage, "RESTAURANT_OPS_STOCK_SHORTAGE",
                 id="stock_shortage"),
]


@pytest.mark.parametrize("resolver,code", RESOLVERS)
def test_declares_window_params_so_dispatch_cannot_drop_them(resolver, code):
    """根因: `resolve_by_code` 只传签名里声明过的 kwargs, 其余**静默丢弃**。"""
    import inspect

    params = inspect.signature(resolver).parameters
    assert "date_range" in params, "date_range 会被 resolve_by_code 过滤掉"
    assert "window_label" in params, "window_label 会被 resolve_by_code 过滤掉"


@pytest.mark.parametrize("resolver,code", RESOLVERS)
@pytest.mark.asyncio
async def test_explicit_range_reaches_sql(resolver, code):
    conn = _conn()
    await resolver(
        _RecordingPool(conn), "MOCK_REST", days=30,
        date_range=_JUNE, window_label="上个月",
    )
    calls = _all(conn)
    assert calls, "resolver 一条查询都没发"
    for sql, args in calls:
        assert _JUNE[0] in args, f"起始日期没进 SQL; args={args}\n{sql}"
        assert _JUNE[1] in args, f"结束日期没进 SQL; args={args}\n{sql}"


@pytest.mark.parametrize("resolver,code", RESOLVERS)
@pytest.mark.asyncio
async def test_explicit_range_is_not_anchored_on_today(resolver, code):
    """给了显式区间就不能再以 CURRENT_DATE 为锚 —— 那正是「问六月答七月」。"""
    conn = _conn()
    await resolver(
        _RecordingPool(conn), "MOCK_REST", days=30,
        date_range=_JUNE, window_label="上个月",
    )
    for sql, _args in _all(conn):
        if "CURRENT_DATE" not in sql:
            continue
        assert "COALESCE" in sql, (
            f"已给显式区间, SQL 仍无条件锚在 CURRENT_DATE:\n{sql}"
        )


@pytest.mark.parametrize("resolver,code", RESOLVERS)
@pytest.mark.asyncio
async def test_heading_reflects_requested_window(resolver, code):
    conn = _conn()
    answer = await resolver(
        _RecordingPool(conn), "MOCK_REST", days=30,
        date_range=_JUNE, window_label="上个月",
    )
    assert "上个月" in answer.title, answer.title
    assert "近 30 天" not in answer.title and "近30天" not in answer.title, answer.title
    # 具体日期让口径可核对, 而不是只能相信标签
    assert "2026-06-01" in answer.title or "2026-06-01" in answer.answer_text


@pytest.mark.parametrize("resolver,code", RESOLVERS)
@pytest.mark.asyncio
async def test_meta_reports_the_window_actually_queried(resolver, code):
    conn = _conn()
    answer = await resolver(
        _RecordingPool(conn), "MOCK_REST", days=30,
        date_range=_JUNE, window_label="上个月",
    )
    assert answer.meta.get("window_start") == "2026-06-01"
    assert answer.meta.get("window_end") == "2026-06-30"


@pytest.mark.parametrize("resolver,code", RESOLVERS)
@pytest.mark.asyncio
async def test_no_range_keeps_rolling_behaviour(resolver, code):
    """只传 days 的调用方行为必须不变。"""
    conn = _conn()
    answer = await resolver(_RecordingPool(conn), "MOCK_REST", days=7)
    assert "7" in answer.title, answer.title
    for sql, _args in _all(conn):
        assert "CURRENT_DATE" in sql, f"滚动回退丢了:\n{sql}"


@pytest.mark.parametrize("resolver,code", RESOLVERS)
def test_gains_the_time_switch_button(resolver, code):
    """#2078 的换时间按钮闸就是签名判据 —— 修完这两个自动获得按钮。

    运营部门的期间选择器要兑现, 靠的正是这一条。
    """
    assert resolver_supports_explicit_window(code)


def test_operations_department_is_now_fully_window_aware():
    """把「运营页的期间选择器能不能兑现」钉成一条断言。

    改动前是 1/3 (只有 WASTAGE_TOP)。库存预警是当前库存快照, 不需要窗口。
    """
    ops_codes = [
        "RESTAURANT_OPS_WASTAGE_TOP",
        "RESTAURANT_OPS_REQUISITION_TREND",
        "RESTAURANT_OPS_STOCK_SHORTAGE",
    ]
    missing = [c for c in ops_codes if not resolver_supports_explicit_window(c)]
    assert not missing, f"运营部门仍有 resolver 拿不到请求的时间窗: {missing}"
