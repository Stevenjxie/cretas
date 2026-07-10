# -*- coding: utf-8 -*-
"""P1+P2.1 底盘接入改造 unit tests (spec 2026-07-10):
- 中餐单品毛利停用 → 加权毛利率 (标签焊死, 无单品承诺)
- restaurant-gold 同比环比 math + 诚实降级 (禁伪造 0)
- 供应商价格异常 render + honest-null
All pure / mocked-pool — no live DB.
"""
import datetime

import pytest

from smartbi.agent.factbook import (
    FactBook,
    NOTE_DISH_MARGIN_ABSENT,
    NOTE_SUPPLIER_ANOMALY_ABSENT,
)
from smartbi.gold.queries import period_comparison


def _fin(gross_profit=300000.0, revenue=1000000.0):
    return {
        "start_date": "2026-05-01", "end_date": "2026-06-30",
        "total_revenue": revenue, "bill_count": 5000, "avg_bill_value": 200.0,
        "store_count": 3, "day_count": 60,
        "material_cost": revenue - gross_profit, "labor_cost": 100000.0,
        "overhead_cost": 50000.0, "total_cost": revenue - gross_profit + 150000.0,
        "net_profit": gross_profit - 150000.0, "gross_profit": gross_profit,
        "net_margin_pct": 15.0,
    }


def test_finance_weighted_margin_label_not_perdish():
    fb = FactBook(period="2026-05~06", finance=_fin())
    text = fb.to_prompt_text()
    assert "加权毛利率" in text
    assert "非单品毛利" in text          # 中餐口径 disclaimer present
    assert "## 单品毛利" not in text     # old per-dish section gone
    assert "restaurant_sku_forms" not in text
    idx = fb.to_facts_index()
    assert idx["加权毛利率"] == 30.0     # 300000/1000000*100
    assert idx["加权毛利"] == 300000.0
    assert not any("单品最高" in k for k in idx)   # no per-dish grounded facts
    assert "毛利率" not in idx           # only the qualified "加权毛利率" key, never bare


def test_dish_margin_note_is_honest_degradation():
    assert "单品毛利算不准" in NOTE_DISH_MARGIN_ABSENT
    assert "加权毛利率" in NOTE_DISH_MARGIN_ABSENT


def test_period_comparison_render_and_degradation():
    pc = {
        "revenue": {"current": 1000000.0, "yoy_pct": None, "mom_pct": -3.2,
                    "yoy_available": False, "mom_available": True},
        "gross_margin_pct": {"current": 30.0, "yoy_pct": None, "mom_pct": 1.5,
                             "yoy_available": False, "mom_available": True},
    }
    fb = FactBook(period="x", period_comparison=pc)
    text = fb.to_prompt_text()
    assert "同比环比" in text
    assert "环比 -3.2%" in text
    assert "同比 去年同期无数据" in text      # honest degradation
    assert "个百分点" in text                 # margin uses points not %
    idx = fb.to_facts_index()
    assert idx["营收环比增长率"] == -3.2
    assert "营收同比增长率" not in idx        # unavailable → not indexed (no fabricated 0)
    assert idx["加权毛利率环比"] == 1.5


def test_supplier_anomaly_render_and_grounding():
    sa = {"anomalies": [
        {"ingredientName": "青菜", "supplierName": "老王蔬菜", "deltaPct": 25.0,
         "direction": "UP", "riskLevel": "HIGH", "oldPrice": 2.0, "newPrice": 2.5},
    ]}
    fb = FactBook(period="x", supplier_anomaly=sa)
    text = fb.to_prompt_text()
    assert "供应商价格异常" in text
    assert "威慑非处罚" in text
    assert "青菜" in text and "老王蔬菜" in text
    idx = fb.to_facts_index()
    assert idx["青菜采购价涨幅"] == 25.0
    # empty anomalies → section omitted (honest; engine attaches NOTE separately)
    assert "供应商价格异常" not in FactBook(
        period="x", supplier_anomaly={"anomalies": []}).to_prompt_text()
    assert "本期未发现供应商采购价格异常" in NOTE_SUPPLIER_ANOMALY_ABSENT


# ---------------- period_comparison (mocked pool) ----------------
class _FakeConn:
    def __init__(self, wd):
        self._wd = wd

    async def execute(self, *a, **k):
        return None

    async def fetchrow(self, sql, factory_id, s, e):
        return self._wd.get((s, e), {"revenue": 0, "material_cost": None, "n_rows": 0})


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self_):
                return conn

            async def __aexit__(self_, *a):
                return False

        return _Ctx()


@pytest.mark.asyncio
async def test_period_comparison_yoy_mom_math():
    start = datetime.date(2026, 6, 1)
    end = datetime.date(2026, 6, 30)
    span = end - start
    mom_end = start - datetime.timedelta(days=1)
    mom_start = mom_end - span
    wd = {
        (start, end): {"revenue": 1000, "material_cost": 300, "n_rows": 30},        # gm 70%
        (mom_start, mom_end): {"revenue": 800, "material_cost": 280, "n_rows": 30},  # gm 65%
        (datetime.date(2025, 6, 1), datetime.date(2025, 6, 30)):
            {"revenue": 500, "material_cost": None, "n_rows": 30},                   # no cost
    }
    out = await period_comparison(_FakePool(_FakeConn(wd)), "DEMO_REST", start, end)
    assert out["revenue"]["mom_pct"] == 25.0      # (1000-800)/800*100
    assert out["revenue"]["yoy_pct"] == 100.0     # (1000-500)/500*100
    assert out["gross_margin_pct"]["current"] == 70.0
    assert out["gross_margin_pct"]["mom_pct"] == 5.0          # 70-65 points
    assert out["gross_margin_pct"]["yoy_available"] is False  # yoy has no cost
    assert out["gross_margin_pct"]["yoy_pct"] is None         # honest, no fabrication


@pytest.mark.asyncio
async def test_period_comparison_empty_comparison_degrades():
    start = datetime.date(2026, 6, 1)
    end = datetime.date(2026, 6, 30)
    wd = {(start, end): {"revenue": 1000, "material_cost": 300, "n_rows": 30}}
    out = await period_comparison(_FakePool(_FakeConn(wd)), "DEMO_REST", start, end)
    assert out["revenue"]["mom_available"] is False
    assert out["revenue"]["yoy_available"] is False
    assert out["revenue"]["mom_pct"] is None      # no fabricated 0
    assert out["revenue"]["current"] == 1000.0


@pytest.mark.asyncio
async def test_period_comparison_rejects_none_factory():
    with pytest.raises(ValueError):
        await period_comparison(None, None, datetime.date(2026, 6, 1), datetime.date(2026, 6, 2))
