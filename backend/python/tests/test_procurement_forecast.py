"""Procurement forecast: holiday-aware purchase planning."""
from smartbi.services.restaurant.sections.base import SectionRequest
from smartbi.services.restaurant.sections.procurement_forecast import ProcurementForecastHandler


def _req(params):
    return SectionRequest(factory_id="F-TEST", upload_id=None, sub_sector="火锅",
                          store_id=None, store_name=None, params=params)


def test_weekday_vs_weekend():
    resp = ProcurementForecastHandler().compute(
        _req({"historical_daily": [
            {"day": "周一", "avg_revenue": 25000, "avg_covers": 80},
            {"day": "周五", "avg_revenue": 40000, "avg_covers": 130},
            {"day": "周六", "avg_revenue": 50000, "avg_covers": 160},
            {"day": "周日", "avg_revenue": 45000, "avg_covers": 145}],
            "next_days": 3, "next_day_names": ["周五", "周六", "周日"]}), {})
    assert resp.status.value == "ok"
    plan = resp.data["daily_plan"]
    assert len(plan) == 3
    assert plan[0]["day"] == "周五"
    assert plan[0]["forecast_revenue"] == 40000


def test_holiday_multiplier():
    resp = ProcurementForecastHandler().compute(
        _req({"historical_daily": [{"day": "周六", "avg_revenue": 50000, "avg_covers": 160}],
              "next_days": 1, "next_day_names": ["周六"], "holiday_multiplier": 1.5}), {})
    assert resp.data["daily_plan"][0]["forecast_revenue"] == 75000


def test_skipped_no_history():
    resp = ProcurementForecastHandler().compute(_req({}), {})
    assert resp.status.value == "skipped"
