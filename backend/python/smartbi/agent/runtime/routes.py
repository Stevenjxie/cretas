"""Deterministic, provider-free route definitions."""

from __future__ import annotations

from .run_contracts import (
    GrossMarginDeclineRequest,
    PlanStep,
    RouteCode,
    RoutePlan,
)


def gross_margin_decline_plan(request: GrossMarginDeclineRequest) -> RoutePlan:
    window = {"startDate": request.start_date, "endDate": request.end_date}
    return RoutePlan(
        route_code=RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        steps=(
            PlanStep(
                step_id="period-comparison",
                round_number=1,
                tool_name="restaurant_period_comparison_read.v1",
                parameters=window,
                purpose_code="ESTABLISH_GROSS_MARGIN_DIRECTION",
            ),
            PlanStep(
                step_id="store-evidence",
                round_number=1,
                tool_name="restaurant_store_performance_read.v1",
                parameters={**window, "topN": request.store_top_n},
                purpose_code="BOUND_STORE_LEVEL_EXPLANATIONS",
            ),
        ),
    )


def gross_margin_decline_replan(request: GrossMarginDeclineRequest) -> RoutePlan:
    """Second-round plan admitted only after round-one evidence proves a gap."""

    window = {"startDate": request.start_date, "endDate": request.end_date}
    return RoutePlan(
        route_code=RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        steps=(
            PlanStep(
                step_id="dish-evidence",
                round_number=2,
                tool_name="restaurant_dish_margin_mix_read.v1",
                parameters={
                    **window,
                    "topN": request.dish_top_n,
                    "includeMargin": True,
                },
                purpose_code="BOUND_DISH_MARGIN_AND_MIX_EXPLANATIONS",
            ),
        ),
    )
