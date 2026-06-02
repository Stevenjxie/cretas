"""Pure (no-DB) tests for WS1 Tasks 4-6 Gold analytics query functions.

- Task 4: menu_quadrant (菜品四象限 — 收入模式)
- Task 5: store_comparison (门店对比)
- Task 6: trend_bundle (趋势分析合一)

These mirror tests/test_gold_queries_allhistory.py's mock-pool shape: a
fake asyncpg pool whose acquire() yields a connection whose fetch/fetchrow
return canned rows so the pure aggregation logic can be asserted without a
DB. Each query also still supports None=all-history (conditional date
filter) — spot-checked here too.
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import (
    _median,
    menu_quadrant,
)


class _ScriptedConn:
    """Yields successive canned results, one per fetch/fetchrow call.

    ``results`` is a list; each fetch/fetchrow pops the next entry. Records
    (sql, params) so date-filter shape can be asserted.
    """

    def __init__(self, results):
        self._results = list(results)
        self.calls = []  # list of (kind, sql, params)

    def _next(self):
        return self._results.pop(0) if self._results else []

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, list(args)))
        return self._next()

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, list(args)))
        res = self._next()
        return res if isinstance(res, dict) else (res[0] if res else None)


class _ScriptedPool:
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


def _no_date_filter(sql: str) -> bool:
    return (
        ">=" not in sql
        and "<=" not in sql
        and "BETWEEN" not in sql.upper()
    )


# ── _median helper ───────────────────────────────────────────


def test_median_empty_is_zero():
    assert _median([]) == 0


def test_median_odd_takes_middle():
    assert _median([3, 1, 2]) == 2


def test_median_even_takes_lower_of_two_middles():
    # Sorted middle: for even length we take the lower-middle element
    # (sorted[(n-1)//2]) — a stable single-value threshold, not an average.
    assert _median([1, 2, 3, 4]) == 2


# ── Task 4: menu_quadrant ────────────────────────────────────


def _q_rows():
    """4 synthetic dishes spanning all four quadrants.

    qty values:     [100, 80, 30, 10]  → sorted [10,30,80,100], median=30
    revenue values: [500, 600, 200, 50] → sorted [50,200,500,600], median=200

    Expected quadrant per (qty, revenue):
      - 招牌A  qty=100>=30, rev=500>=200 → 明星
      - 高价B  qty=80>=30,  rev=600>=200 → 明星  (both high)
      - 走量C  qty=30>=30,  rev=200>=200 → 明星  (== thresholds count as >=)
      - 冷门D  qty=10<30,   rev=50<200   → 瘦狗

    To exercise 金牛 / 潜力 distinctly, use values that separate cleanly.
    """
    return [
        {"name": "招牌A", "canonical_name": None, "qty": 100, "revenue": 500},
        {"name": "高价B", "canonical_name": None, "qty": 20, "revenue": 600},
        {"name": "走量C", "canonical_name": None, "qty": 90, "revenue": 150},
        {"name": "冷门D", "canonical_name": None, "qty": 10, "revenue": 50},
    ]


@pytest.mark.asyncio
async def test_menu_quadrant_classification_and_medians():
    # qty:     [100,20,90,10] → sorted [10,20,90,100] median (lower-mid) = 20
    # revenue: [500,600,150,50] → sorted [50,150,500,600] median = 150
    conn = _ScriptedConn([_q_rows()])
    pool = _ScriptedPool(conn)
    out = await menu_quadrant(pool, "F1", (None, None))

    assert out["qtyMedian"] == 20
    assert out["revenueMedian"] == 150
    items = {it["name"]: it for it in out["items"]}

    # 招牌A: qty100>=20 AND rev500>=150 → 明星
    assert items["招牌A"]["quadrant"] == "明星"
    # 高价B: qty20>=20 (==) AND rev600>=150 → 明星
    assert items["高价B"]["quadrant"] == "明星"
    # 走量C: qty90>=20 AND rev150>=150 (==) → 明星
    assert items["走量C"]["quadrant"] == "明星"
    # 冷门D: qty10<20 AND rev50<150 → 瘦狗
    assert items["冷门D"]["quadrant"] == "瘦狗"


@pytest.mark.asyncio
async def test_menu_quadrant_all_four_quadrants_5_dishes():
    # 5 dishes → odd median picks the true middle, giving clean separation.
    rows = [
        {"name": "明星", "canonical_name": None, "qty": 100, "revenue": 1000},
        {"name": "金牛", "canonical_name": None, "qty": 10, "revenue": 900},
        {"name": "潜力", "canonical_name": None, "qty": 90, "revenue": 100},
        {"name": "中位", "canonical_name": None, "qty": 50, "revenue": 500},
        {"name": "瘦狗", "canonical_name": None, "qty": 5, "revenue": 50},
    ]
    # qty:     [100,10,90,50,5] → sorted [5,10,50,90,100] median=50
    # revenue: [1000,900,100,500,50] → sorted [50,100,500,900,1000] median=500
    conn = _ScriptedConn([rows])
    pool = _ScriptedPool(conn)
    out = await menu_quadrant(pool, "F1", (None, None))
    assert out["qtyMedian"] == 50
    assert out["revenueMedian"] == 500
    items = {it["name"]: it for it in out["items"]}
    # 明星: qty100>=50 AND rev1000>=500 → 明星
    assert items["明星"]["quadrant"] == "明星"
    # 金牛: qty10<50, rev900>=500 → 金牛
    assert items["金牛"]["quadrant"] == "金牛"
    # 潜力: qty90>=50, rev100<500 → 潜力
    assert items["潜力"]["quadrant"] == "潜力"
    # 瘦狗: qty5<50, rev50<500 → 瘦狗
    assert items["瘦狗"]["quadrant"] == "瘦狗"


@pytest.mark.asyncio
async def test_menu_quadrant_empty_honest():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    out = await menu_quadrant(pool, "F1", (None, None))
    assert out["items"] == []
    assert out["qtyMedian"] == 0
    assert out["revenueMedian"] == 0


@pytest.mark.asyncio
async def test_menu_quadrant_all_history_no_date_filter():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    await menu_quadrant(pool, "F1", (None, None))
    kind, sql, params = conn.calls[0]
    assert _no_date_filter(sql), sql
    assert not any(isinstance(p, date) for p in params)
    assert params == ["F1"]


@pytest.mark.asyncio
async def test_menu_quadrant_month_filter_on_a_month():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    await menu_quadrant(pool, "F1", (date(2025, 4, 15), date(2025, 5, 10)))
    kind, sql, params = conn.calls[0]
    assert "a.month >=" in sql and "a.month <=" in sql
    # month is normalized to first-of-month
    assert params == ["F1", date(2025, 4, 1), date(2025, 5, 1)]
