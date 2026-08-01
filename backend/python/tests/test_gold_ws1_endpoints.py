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
    store_comparison,
    trend_bundle,
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

    async def execute(self, *args, **kwargs):
        # gold/queries.tenant_conn 会在借到连接后 set_config 租户上下文。
        # 假连接必须支持它 —— 不支持等于建模了一条永远不设租户的连接,
        # 而那正是 2026-08-01 修掉的那个非确定性返空缺陷本身。
        return None
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


# ── Task 5: store_comparison ─────────────────────────────────


@pytest.mark.asyncio
async def test_store_comparison_avg_ticket_and_weak_stores():
    rows = [
        {"name": "旗舰店", "revenue": 10000, "order_count": 500, "avg_ticket": 20.0,
         "discount_amount": 1000, "gross_amount": 11000},
        {"name": "中位店", "revenue": 5000, "order_count": 250, "avg_ticket": 20.0,
         "discount_amount": 500, "gross_amount": 5500},
        {"name": "弱店", "revenue": 2000, "order_count": 200, "avg_ticket": 10.0,
         "discount_amount": 200, "gross_amount": 2200},
    ]
    # revenue sorted [2000,5000,10000] median = 5000
    # weakStores = those strictly below median revenue → 弱店 (2000)
    conn = _ScriptedConn([rows])
    pool = _ScriptedPool(conn)
    out = await store_comparison(pool, "F1", (None, None))
    assert out["medianRevenue"] == 5000
    stores = {s["name"]: s for s in out["stores"]}
    assert stores["旗舰店"]["avgTicket"] == pytest.approx(20.0)
    assert stores["弱店"]["avgTicket"] == pytest.approx(10.0)
    assert out["weakStores"] == ["弱店"]


@pytest.mark.asyncio
async def test_store_comparison_discount_pct_per_store():
    """折扣率 = discount_amount / gross_amount * 100 per store (rounded 1 dp)."""
    rows = [
        # 1000 / 11000 = 9.0909... → 9.1
        {"name": "旗舰店", "revenue": 10000, "order_count": 500, "avg_ticket": 20.0,
         "discount_amount": 1000, "gross_amount": 11000},
        # 825 / 5500 = 15.0 → 15.0
        {"name": "折扣店", "revenue": 4675, "order_count": 250, "avg_ticket": 18.7,
         "discount_amount": 825, "gross_amount": 5500},
        # 0 / 2000 = 0.0 (no discount)
        {"name": "无折扣店", "revenue": 2000, "order_count": 200, "avg_ticket": 10.0,
         "discount_amount": 0, "gross_amount": 2000},
    ]
    conn = _ScriptedConn([rows])
    pool = _ScriptedPool(conn)
    out = await store_comparison(pool, "F1", (None, None))
    stores = {s["name"]: s for s in out["stores"]}
    assert stores["旗舰店"]["discountPct"] == pytest.approx(9.1)
    assert stores["折扣店"]["discountPct"] == pytest.approx(15.0)
    assert stores["无折扣店"]["discountPct"] == 0.0


@pytest.mark.asyncio
async def test_store_comparison_discount_pct_zero_gross_is_zero():
    """gross_amount = 0 (or NULL) → discountPct falls back to 0.0 (no /0)."""
    rows = [
        {"name": "零营业额店", "revenue": 0, "order_count": 0, "avg_ticket": None,
         "discount_amount": 0, "gross_amount": 0},
        {"name": "空折扣字段店", "revenue": 1000, "order_count": 50, "avg_ticket": 20.0,
         "discount_amount": None, "gross_amount": None},
    ]
    conn = _ScriptedConn([rows])
    pool = _ScriptedPool(conn)
    out = await store_comparison(pool, "F1", (None, None))
    stores = {s["name"]: s for s in out["stores"]}
    assert stores["零营业额店"]["discountPct"] == 0.0
    assert stores["空折扣字段店"]["discountPct"] == 0.0


@pytest.mark.asyncio
async def test_store_comparison_avg_ticket_zero_orders_is_zero():
    rows = [
        {"name": "无单店", "revenue": 0, "order_count": 0, "avg_ticket": None,
         "discount_amount": 0, "gross_amount": 0},
        {"name": "有单店", "revenue": 1000, "order_count": 50, "avg_ticket": 20.0,
         "discount_amount": 0, "gross_amount": 1000},
    ]
    conn = _ScriptedConn([rows])
    pool = _ScriptedPool(conn)
    out = await store_comparison(pool, "F1", (None, None))
    stores = {s["name"]: s for s in out["stores"]}
    # NULLIF(order_count,0) → NULL → avgTicket falls back to 0.0
    assert stores["无单店"]["avgTicket"] == 0.0
    assert stores["有单店"]["avgTicket"] == pytest.approx(20.0)


@pytest.mark.asyncio
async def test_store_comparison_empty_honest():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    out = await store_comparison(pool, "F1", (None, None))
    assert out["stores"] == []
    assert out["medianRevenue"] == 0
    assert out["weakStores"] == []


@pytest.mark.asyncio
async def test_store_comparison_all_history_no_date_filter():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    await store_comparison(pool, "F1", (None, None))
    kind, sql, params = conn.calls[0]
    assert _no_date_filter(sql), sql
    assert not any(isinstance(p, date) for p in params)
    assert params == ["F1"]


@pytest.mark.asyncio
async def test_store_comparison_date_filter_both_bounds():
    conn = _ScriptedConn([[]])
    pool = _ScriptedPool(conn)
    await store_comparison(pool, "F1", (date(2025, 1, 1), date(2025, 12, 31)))
    kind, sql, params = conn.calls[0]
    assert "a.date >=" in sql and "a.date <=" in sql
    assert params == ["F1", date(2025, 1, 1), date(2025, 12, 31)]


# ── Task 6: trend_bundle ─────────────────────────────────────


@pytest.mark.asyncio
async def test_trend_bundle_three_blocks_and_weekday_weekend():
    # Call order inside trend_bundle:
    #   1) daily_trend rows (date, revenue, bill_count)
    #   2) weekday/weekend rows (is_weekend, total_revenue, day_count)
    #   3) monthly rows (month, revenue)
    daily_rows = [
        {"date": date(2025, 1, 1), "revenue": 100, "bill_count": 10},
        {"date": date(2025, 1, 2), "revenue": 200, "bill_count": 20},
    ]
    # weekday: 2 days totaling 1000 → avg 500; weekend: 1 day totaling 300 → avg 300
    ww_rows = [
        {"is_weekend": False, "total_revenue": 1000, "day_count": 2},
        {"is_weekend": True, "total_revenue": 300, "day_count": 1},
    ]
    monthly_rows = [
        {"month": date(2025, 1, 1), "revenue": 1300},
    ]
    conn = _ScriptedConn([daily_rows, ww_rows, monthly_rows])
    pool = _ScriptedPool(conn)
    out = await trend_bundle(pool, "F1", (None, None))

    assert "dailyTrend" in out
    assert "weekdayWeekend" in out
    assert "monthlyTrend" in out

    assert len(out["dailyTrend"]) == 2
    assert out["dailyTrend"][0]["date"] == "2025-01-01"
    assert out["dailyTrend"][0]["revenue"] == pytest.approx(100.0)

    ww = out["weekdayWeekend"]
    assert ww["weekdayDays"] == 2
    assert ww["weekendDays"] == 1
    assert ww["weekdayAvg"] == pytest.approx(500.0)  # 1000/2
    assert ww["weekendAvg"] == pytest.approx(300.0)  # 300/1

    assert out["monthlyTrend"][0]["month"] == "2025-01"
    assert out["monthlyTrend"][0]["revenue"] == pytest.approx(1300.0)


@pytest.mark.asyncio
async def test_trend_bundle_weekday_weekend_zero_days_safe():
    daily_rows = []
    ww_rows = [
        {"is_weekend": False, "total_revenue": 0, "day_count": 0},
    ]
    monthly_rows = []
    conn = _ScriptedConn([daily_rows, ww_rows, monthly_rows])
    pool = _ScriptedPool(conn)
    out = await trend_bundle(pool, "F1", (None, None))
    ww = out["weekdayWeekend"]
    assert ww["weekdayAvg"] == 0.0
    assert ww["weekendAvg"] == 0.0
    assert ww["weekdayDays"] == 0
    assert ww["weekendDays"] == 0
    assert out["dailyTrend"] == []
    assert out["monthlyTrend"] == []


@pytest.mark.asyncio
async def test_trend_bundle_all_history_no_date_filter():
    conn = _ScriptedConn([[], [], []])
    pool = _ScriptedPool(conn)
    await trend_bundle(pool, "F1", (None, None))
    assert len(conn.calls) == 3
    for kind, sql, params in conn.calls:
        assert _no_date_filter(sql), f"{sql}"
        assert not any(isinstance(p, date) for p in params)


@pytest.mark.asyncio
async def test_trend_bundle_date_filter_both_bounds():
    conn = _ScriptedConn([[], [], []])
    pool = _ScriptedPool(conn)
    await trend_bundle(pool, "F1", (date(2025, 1, 1), date(2025, 12, 31)))
    for kind, sql, params in conn.calls:
        assert ">=" in sql and "<=" in sql
        assert params[0] == "F1"
        assert date(2025, 1, 1) in params and date(2025, 12, 31) in params
