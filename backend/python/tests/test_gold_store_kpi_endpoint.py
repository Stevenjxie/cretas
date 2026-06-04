"""Wiring tests for GET /api/smartbi/gold/store-kpi-dashboard.

The compute function (compute_store_kpi_dashboard) is exhaustively unit-tested
in test_store_kpi_dashboard.py. Here we only assert the endpoint:
  * resolves tenant + role from the request,
  * forwards them to compute,
  * does NOT re-apply the generic money-strip (which would over-strip the
    rate cards 毛利率 / 食材成本率),
  * returns an honest empty payload when compute signals None.
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

import smartbi.api.gold_reads as gr


class _FakeRequest:
    def __init__(self, role):
        self.state = SimpleNamespace(role=role, factory_id="F-TEST")


@pytest.mark.asyncio
async def test_endpoint_forwards_role_and_does_not_double_strip(monkeypatch):
    captured = {}

    async def fake_compute(pool, factory_id, date_range, role, store_name=None):
        captured["factory_id"] = factory_id
        captured["role"] = role
        captured["date_range"] = date_range
        # Return a payload where rate cards keep rawValue (compute already did
        # its own precise strip). If the endpoint re-applied _apply_rbac_strip,
        # the 毛利率 card (label 含 毛利) would get nulled — assert it doesn't.
        return {
            "store_name": None,
            "kpis": [
                {"key": "daily_revenue", "label": "日营收", "unit": "元",
                 "rawValue": None, "value": "—", "money": True, "status": "GOOD"},
                {"key": "gross_margin", "label": "毛利率", "unit": "%",
                 "rawValue": 0.60, "value": "60.0%", "money": False, "status": "GOOD"},
                {"key": "food_cost_rate", "label": "食材成本率", "unit": "%",
                 "rawValue": 0.35, "value": "35.0%", "money": False, "status": "GOOD"},
            ],
            "overall_health": "GOOD",
        }

    monkeypatch.setattr(
        "smartbi.services.restaurant.sections.store_kpi_dashboard.compute_store_kpi_dashboard",
        fake_compute,
    )

    async def fake_pool():
        return object()
    monkeypatch.setattr(gr, "get_pg_pool", fake_pool)
    monkeypatch.setattr(gr, "_resolve_tenant", lambda fid: "F-TEST")

    req = _FakeRequest(role="warehouse_mgr")  # not price-view
    data = await gr.get_store_kpi_dashboard(req, start_date=None, end_date=None, factory_id="F-TEST")

    assert captured["role"] == "warehouse_mgr"
    assert captured["factory_id"] == "F-TEST"
    kpis = {k["key"]: k for k in data["kpis"]}
    # money KPI already nulled by compute — endpoint leaves it
    assert kpis["daily_revenue"]["rawValue"] is None
    # rate cards survive (no double strip on 毛利率/食材成本率)
    assert kpis["gross_margin"]["rawValue"] == pytest.approx(0.60)
    assert kpis["food_cost_rate"]["rawValue"] == pytest.approx(0.35)


@pytest.mark.asyncio
async def test_endpoint_honest_empty_when_compute_none(monkeypatch):
    async def fake_compute(pool, factory_id, date_range, role, store_name=None):
        return None
    monkeypatch.setattr(
        "smartbi.services.restaurant.sections.store_kpi_dashboard.compute_store_kpi_dashboard",
        fake_compute,
    )

    async def fake_pool():
        return object()
    monkeypatch.setattr(gr, "get_pg_pool", fake_pool)
    monkeypatch.setattr(gr, "_resolve_tenant", lambda fid: "F-TEST")

    req = _FakeRequest(role="factory_super_admin")
    data = await gr.get_store_kpi_dashboard(req, start_date=None, end_date=None, factory_id="F-TEST")
    assert data["empty"] is True
    assert data["kpis"] == []
    assert "未检测到经营数据" in data["message"]
