from __future__ import annotations

"""Procurement forecast: predict next N days using day-of-week history + holiday multiplier."""

import time
from typing import Any
from smartbi.services.restaurant.sections.base import AbstractSectionHandler, SectionRequest, SectionResponse


class ProcurementForecastHandler(AbstractSectionHandler):
    section_name = "procurement_forecast"

    def compute(self, request: SectionRequest, context: dict[str, Any]) -> SectionResponse:
        started = time.time()
        p = request.params or {}
        historical = p.get("historical_daily")
        if not historical or not isinstance(historical, list):
            return self.skipped(request, "未提供 historical_daily", started)

        next_days = int(p.get("next_days", 3))
        day_names = p.get("next_day_names", [])
        holiday_mult = float(p.get("holiday_multiplier", 1.0))
        yoy = float(p.get("yoy_adjustment", 1.0))

        lookup = {h.get("day", ""): {"avg_revenue": float(h.get("avg_revenue", 0)),
                                     "avg_covers": float(h.get("avg_covers", 0))} for h in historical}

        daily_plan, total_rev, total_cov = [], 0, 0
        for i in range(min(next_days, len(day_names) if day_names else next_days)):
            day = day_names[i] if i < len(day_names) else f"Day{i+1}"
            base = lookup.get(day, {"avg_revenue": 0, "avg_covers": 0})
            rev = round(base["avg_revenue"] * holiday_mult * yoy)
            cov = round(base["avg_covers"] * holiday_mult * yoy)
            daily_plan.append({"day": day, "forecast_revenue": rev, "forecast_covers": cov,
                               "holiday_multiplier": holiday_mult, "yoy_adjustment": yoy, "base_revenue": base["avg_revenue"]})  # noqa: E501
            total_rev += rev
            total_cov += cov  # noqa: E702

        return self.ok(request, data={"daily_plan": daily_plan, "total_forecast_revenue": total_rev,
                                      "total_forecast_covers": total_cov, "days_planned": len(daily_plan),
                                      "note": "节假日/特殊活动请手动调整 holiday_multiplier"}, started=started)
