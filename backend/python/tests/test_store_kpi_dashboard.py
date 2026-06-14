"""Store KPI dashboard tests.

Two paths:
  1. Legacy 3-dimension health check (financial/operational/external dicts fed
     in by caller) — backward-compat, unchanged behavior.
  2. NEW single-store 店长 6-KPI self-query path: when no dicts are fed, the
     handler self-queries Gold for 日营收/客单价/订单数/毛利率/食材成本率/目标完成率.

The 6-KPI path is exercised through the pure compute function
``compute_store_kpi_dashboard`` with the underlying Gold helpers monkeypatched
(no DB needed) — mirrors the python-java-port test pattern.
"""
import asyncio

import pytest

from smartbi.services.restaurant.sections.base import SectionRequest
from smartbi.services.restaurant.sections.store_kpi_dashboard import (
    StoreKpiDashboardHandler,
    compute_store_kpi_dashboard,
)


def _req(params):
    return SectionRequest(factory_id="F-TEST", upload_id=None, sub_sector="火锅",
                          store_id="S-001", store_name="测试店", params=params)


# ──────────────────────────────────────────────────────────────────────────
# Legacy 3-dimension path (backward compat)
# ──────────────────────────────────────────────────────────────────────────

def test_full_dashboard_legacy():
    resp = StoreKpiDashboardHandler().compute(
        _req({
            "financial": {"controllable_profit": 180000, "revenue": 500000, "labor_cost_pct": 22.5},
            "operational": {"labor_productivity": 35000, "staff_turnover_pct": 8.0, "shift_compliance": 95.0},
            "external": {"review_score": 4.3, "negative_review_pct": 2.1},
        }), {})
    assert resp.status.value == "ok"
    d = resp.data
    assert len(d["dimensions"]) == 3
    assert d["overall_health"] in ("GOOD", "WARNING", "CRITICAL")


def test_partial_data_legacy():
    resp = StoreKpiDashboardHandler().compute(
        _req({"financial": {"revenue": 300000}}), {})
    assert resp.status.value == "ok"
    assert len(resp.data["dimensions"]) >= 1


# ──────────────────────────────────────────────────────────────────────────
# Helpers to drive the 6-KPI self-query compute
# ──────────────────────────────────────────────────────────────────────────

class _FakeOpsAnswer:
    """Minimal stand-in for restaurant_ops_router.OpsAnswer."""

    def __init__(self, kpis, meta):
        self.kpis = kpis
        self.meta = meta


def _patch_gold(
    monkeypatch,
    *,
    kpi=None,
    margin_kpis=None,
    margin_meta=None,
    food_cost_total=None,   # requisition_cost_total
    achievement=None,
    alert_config=None,
):
    """Monkeypatch every Gold dependency compute_store_kpi_dashboard uses."""
    import smartbi.services.restaurant.sections.store_kpi_dashboard as mod

    kpi = kpi if kpi is not None else {
        "revenue": 1_000_000.0, "bill_count": 5000, "avg_bill_value": 200.0,
        "day_count": 100, "store_count": 1,
    }

    async def fake_kpi_summary(pool, factory_id, date_range):
        return kpi
    monkeypatch.setattr(mod, "_kpi_summary", fake_kpi_summary)

    async def fake_resolve_gross_margin(pool, factory_id, days=30, top_n=10):
        return _FakeOpsAnswer(
            kpis=margin_kpis if margin_kpis is not None else [
                {"title": "总营收", "rawValue": 1_000_000.0},
                {"title": "总毛利", "rawValue": 600_000.0},
                {"title": "平均毛利率", "rawValue": 0.60},
            ],
            meta=margin_meta if margin_meta is not None else {
                "total_dishes": 10, "missing_cost_count": 2,
            },
        )
    monkeypatch.setattr(mod, "_resolve_gross_margin", fake_resolve_gross_margin)

    async def fake_food_cost(pool, factory_id, date_range):
        # returns total requisition cost across range
        return food_cost_total if food_cost_total is not None else 350_000.0
    monkeypatch.setattr(mod, "_query_requisition_cost_total", fake_food_cost)

    async def fake_achievement(pool, factory_id, date_range, *, kpi_kind="revenue", level="day", store_id=None):
        return achievement if achievement is not None else {
            "points": [
                {"period_key": "2025-01", "target": 900_000.0, "actual": 1_000_000.0,
                 "achievement_rate": 1.111, "data_missing": False,
                 "period_complete": True, "in_progress": False},
            ],
            "period_without_target": [],
        }
    monkeypatch.setattr(mod, "_daily_achievement_summary", fake_achievement)

    async def fake_alert_config(pool, factory_id, kpi_kind="revenue", level="month"):
        return alert_config  # None = no config row
    monkeypatch.setattr(mod, "_query_alert_thresholds", fake_alert_config)


def _run(role="factory_super_admin", **patch_kwargs):
    from datetime import date
    # Bounded range so 目标完成率 goes straight through the (patched)
    # _daily_achievement_summary seam without touching the agg_daily DB window
    # query used by the all-history fallback.
    return asyncio.run(
        compute_store_kpi_dashboard(
            pool=object(),  # never touched (helpers are patched)
            factory_id="F-TEST",
            date_range=(date(2025, 1, 1), date(2025, 12, 31)),
            role=role,
            store_name="测试店",
        )
    )


# ──────────────────────────────────────────────────────────────────────────
# 6-KPI happy path
# ──────────────────────────────────────────────────────────────────────────

def test_six_kpi_happy_path(monkeypatch):
    _patch_gold(monkeypatch)
    data = _run()
    kpis = {k["key"]: k for k in data["kpis"]}
    assert set(kpis) == {
        "daily_revenue", "avg_ticket", "order_count",
        "gross_margin", "food_cost_rate", "target_completion",
    }
    # 日营收 = revenue / day_count = 1_000_000 / 100 = 10_000
    assert kpis["daily_revenue"]["rawValue"] == pytest.approx(10_000.0)
    # 客单价
    assert kpis["avg_ticket"]["rawValue"] == pytest.approx(200.0)
    # 订单数 (count — visible, not money)
    assert kpis["order_count"]["rawValue"] == 5000
    # 毛利率 60%
    assert kpis["gross_margin"]["rawValue"] == pytest.approx(0.60)
    assert kpis["gross_margin"]["status"] == "GOOD"  # >= 0.55
    # 食材成本率 = 350_000 / 1_000_000 = 0.35
    assert kpis["food_cost_rate"]["rawValue"] == pytest.approx(0.35)
    assert kpis["food_cost_rate"]["status"] == "GOOD"  # <= 0.40
    # 目标完成率 — aggregate actual/target across points = 1_000_000 / 900_000
    assert kpis["target_completion"]["rawValue"] == pytest.approx(1_000_000 / 900_000, rel=1e-3)
    assert "overall_health" in data
    assert data["store_name"] == "测试店"


def test_gross_margin_warning_critical(monkeypatch):
    # 45% → WARNING (40..55)
    _patch_gold(monkeypatch, margin_kpis=[
        {"title": "总营收", "rawValue": 100.0},
        {"title": "总毛利", "rawValue": 45.0},
        {"title": "平均毛利率", "rawValue": 0.45},
    ])
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["gross_margin"]["status"] == "WARNING"

    # 30% → CRITICAL (< 40)
    _patch_gold(monkeypatch, margin_kpis=[
        {"title": "总营收", "rawValue": 100.0},
        {"title": "总毛利", "rawValue": 30.0},
        {"title": "平均毛利率", "rawValue": 0.30},
    ])
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["gross_margin"]["status"] == "CRITICAL"


def test_food_cost_rate_thresholds(monkeypatch):
    # 45% → WARNING (40..50)
    _patch_gold(monkeypatch, food_cost_total=450_000.0)
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["food_cost_rate"]["status"] == "WARNING"

    # 55% → CRITICAL (> 50)
    _patch_gold(monkeypatch, food_cost_total=550_000.0)
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["food_cost_rate"]["status"] == "CRITICAL"


# ──────────────────────────────────────────────────────────────────────────
# Honesty: insufficient data → NEVER fabricate
# ──────────────────────────────────────────────────────────────────────────

def test_gross_margin_insufficient_no_priced_dishes(monkeypatch):
    # all dishes missing cost → dish_count_with_cost = 0 → 成本数据不足
    _patch_gold(
        monkeypatch,
        margin_kpis=[
            {"title": "总营收", "rawValue": 100.0},
            {"title": "总毛利", "rawValue": 100.0},  # =revenue (no cost) → bogus 100% if naively used
            {"title": "平均毛利率", "rawValue": 0.0},
        ],
        margin_meta={"total_dishes": 8, "missing_cost_count": 8},
    )
    kpis = {k["key"]: k for k in _run()["kpis"]}
    gm = kpis["gross_margin"]
    assert gm["status"] == "INSUFFICIENT"
    assert gm["rawValue"] is None
    assert "成本数据不足" in gm["value"]


def test_gross_margin_zero_dishes(monkeypatch):
    _patch_gold(
        monkeypatch,
        margin_kpis=[],
        margin_meta={"total_dishes": 0, "missing_cost_count": 0},
    )
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["gross_margin"]["status"] == "INSUFFICIENT"
    assert kpis["gross_margin"]["rawValue"] is None


def test_food_cost_rate_zero_revenue_insufficient(monkeypatch):
    # Data present (day_count>0) but revenue 0 (e.g. all-refund window) → food
    # cost rate can't divide by 0 → INSUFFICIENT, not a fabricated 0%.
    _patch_gold(monkeypatch, kpi={
        "revenue": 0.0, "bill_count": 0, "avg_bill_value": None,
        "day_count": 5, "store_count": 1,
    })
    kpis = {k["key"]: k for k in _run()["kpis"]}
    assert kpis["food_cost_rate"]["status"] == "INSUFFICIENT"
    assert kpis["food_cost_rate"]["rawValue"] is None


# ──────────────────────────────────────────────────────────────────────────
# 目标完成率: NO_TARGET / alert_config thresholds
# ──────────────────────────────────────────────────────────────────────────

def test_target_no_target(monkeypatch):
    _patch_gold(monkeypatch, achievement={
        "points": [],
        "period_without_target": ["2025-01", "2025-02"],
    })
    kpis = {k["key"]: k for k in _run()["kpis"]}
    tc = kpis["target_completion"]
    assert tc["status"] == "NO_TARGET"
    assert tc["rawValue"] is None
    assert tc.get("needsConfig") is True


def test_target_default_thresholds_no_alert_config(monkeypatch):
    # achievement 0.65 (< default crit 0.70) → CRITICAL, config_exists=false
    _patch_gold(monkeypatch, achievement={
        "points": [
            {"period_key": "2025-01", "target": 1_000_000.0, "actual": 650_000.0,
             "achievement_rate": 0.65, "data_missing": False,
             "period_complete": True, "in_progress": False},
        ],
        "period_without_target": [],
    }, alert_config=None)
    kpis = {k["key"]: k for k in _run()["kpis"]}
    tc = kpis["target_completion"]
    assert tc["status"] == "CRITICAL"
    assert tc.get("configExists") is False


def test_target_all_history_open_range(monkeypatch):
    # Open range (None, None) → compute resolves the factory data window via
    # _achievement_all_history. Patch that seam to assert it's used (not the
    # bounded _daily_achievement_summary path) and produces a rate.
    _patch_gold(monkeypatch)
    import smartbi.services.restaurant.sections.store_kpi_dashboard as mod

    called = {"all_history": False}

    async def fake_all_history(pool, factory_id):
        called["all_history"] = True
        return {
            "points": [
                {"period_key": "2025-01", "target": 800_000.0, "actual": 1_000_000.0,
                 "achievement_rate": 1.25, "data_missing": False,
                 "period_complete": True, "in_progress": False},
            ],
            "period_without_target": [],
        }
    monkeypatch.setattr(mod, "_achievement_all_history", fake_all_history)

    data = asyncio.run(compute_store_kpi_dashboard(
        pool=object(), factory_id="F-TEST", date_range=(None, None),
        role="factory_super_admin", store_name="测试店",
    ))
    assert called["all_history"] is True
    kpis = {k["key"]: k for k in data["kpis"]}
    assert kpis["target_completion"]["rawValue"] == pytest.approx(1_000_000 / 800_000, rel=1e-3)


def test_target_custom_alert_config(monkeypatch):
    # warn 0.95 crit 0.85; achievement 0.90 → between → WARNING
    _patch_gold(monkeypatch, achievement={
        "points": [
            {"period_key": "2025-01", "target": 1_000_000.0, "actual": 900_000.0,
             "achievement_rate": 0.90, "data_missing": False,
             "period_complete": True, "in_progress": False},
        ],
        "period_without_target": [],
    }, alert_config={"warn_threshold": 0.95, "critical_threshold": 0.85})
    kpis = {k["key"]: k for k in _run()["kpis"]}
    tc = kpis["target_completion"]
    assert tc["status"] == "WARNING"
    assert tc.get("configExists") is True


# ──────────────────────────────────────────────────────────────────────────
# RBAC strip — money null (not 0) for non price-view; rates/counts visible
# ──────────────────────────────────────────────────────────────────────────

def test_rbac_strip_non_price_role(monkeypatch):
    _patch_gold(monkeypatch)
    data = _run(role="viewer")  # not in PRICE_VIEW_ROLES
    kpis = {k["key"]: k for k in data["kpis"]}
    # money KPIs nulled
    assert kpis["daily_revenue"]["rawValue"] is None
    assert kpis["avg_ticket"]["rawValue"] is None
    # counts + rates stay visible
    assert kpis["order_count"]["rawValue"] == 5000
    assert kpis["gross_margin"]["rawValue"] == pytest.approx(0.60)
    assert kpis["food_cost_rate"]["rawValue"] == pytest.approx(0.35)
    assert kpis["target_completion"]["rawValue"] is not None


def test_rbac_fail_closed_no_role(monkeypatch):
    _patch_gold(monkeypatch)
    data = _run(role=None)
    kpis = {k["key"]: k for k in data["kpis"]}
    assert kpis["daily_revenue"]["rawValue"] is None  # fail-closed strip
    assert kpis["order_count"]["rawValue"] == 5000     # count visible


def test_rbac_sales_manager_stripped_for_store_kpi(monkeypatch):
    _patch_gold(monkeypatch)
    data = _run(role="sales_manager")
    kpis = {k["key"]: k for k in data["kpis"]}
    assert kpis["daily_revenue"]["rawValue"] is None
    assert kpis["avg_ticket"]["rawValue"] is None
    assert kpis["order_count"]["rawValue"] == 5000
    assert kpis["gross_margin"]["rawValue"] == pytest.approx(0.60)


def test_rbac_price_role_sees_money(monkeypatch):
    _patch_gold(monkeypatch)
    data = _run(role="restaurant_manager")  # in PRICE_VIEW_ROLES
    kpis = {k["key"]: k for k in data["kpis"]}
    assert kpis["daily_revenue"]["rawValue"] == pytest.approx(10_000.0)
    assert kpis["avg_ticket"]["rawValue"] == pytest.approx(200.0)


# ──────────────────────────────────────────────────────────────────────────
# Section handler routing: empty + no gold → SKIPPED
# ──────────────────────────────────────────────────────────────────────────

def test_section_self_query_empty_gold_skipped(monkeypatch):
    import smartbi.services.restaurant.sections.store_kpi_dashboard as mod

    async def fake_compute(pool, factory_id, date_range, role, store_name=None):
        return None  # signal: no gold data
    monkeypatch.setattr(mod, "compute_store_kpi_dashboard", fake_compute)

    # pool present but compute returns None → SKIPPED
    async def fake_pool():
        return object()
    monkeypatch.setattr(mod, "_get_pg_pool", fake_pool)

    resp = StoreKpiDashboardHandler().compute(_req({}), {})
    assert resp.status.value == "skipped"


def test_section_self_query_ok(monkeypatch):
    import smartbi.services.restaurant.sections.store_kpi_dashboard as mod

    async def fake_compute(pool, factory_id, date_range, role, store_name=None):
        return {"kpis": [{"key": "daily_revenue", "rawValue": 1.0}], "overall_health": "GOOD"}
    monkeypatch.setattr(mod, "compute_store_kpi_dashboard", fake_compute)

    async def fake_pool():
        return object()
    monkeypatch.setattr(mod, "_get_pg_pool", fake_pool)

    resp = StoreKpiDashboardHandler().compute(_req({"role": "factory_super_admin"}), {})
    assert resp.status.value == "ok"
    assert resp.data["overall_health"] == "GOOD"
