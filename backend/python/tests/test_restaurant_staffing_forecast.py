from __future__ import annotations

import asyncio
from datetime import date, timedelta
from unittest.mock import AsyncMock

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import match_restaurant_ops
from smartbi.services.restaurant.staffing_forecast import (
    RestaurantStaffingService,
    _numeric_free_validator,
    daily_demand_forecast,
    horizon_from_question,
    horizon_window,
    make_plan_fingerprint,
    role_recommendation,
    trend_metrics,
)


def test_future_horizons_are_calendar_bounded():
    as_of = date(2026, 8, 3)
    assert horizon_window("tomorrow", as_of) == (date(2026, 8, 4), date(2026, 8, 4))
    assert horizon_window("week", as_of) == (date(2026, 8, 10), date(2026, 8, 16))
    assert horizon_window("month", as_of) == (date(2026, 9, 1), date(2026, 9, 30))


@pytest.mark.parametrize(
    "question,horizon",
    [
        ("明天怎么排班", "tomorrow"),
        ("下周需要多少兼职", "week"),
        ("下个月各店人效安排", "month"),
    ],
)
def test_real_questions_route_to_staffing_horizon(question, horizon):
    assert match_restaurant_ops(question) == "RESTAURANT_OPS_STAFFING_ADVICE"
    assert horizon_from_question(question) == horizon


def test_trends_keep_independent_7_30_365_windows():
    as_of = date(2026, 8, 3)
    history = [(as_of - timedelta(days=offset), float(offset)) for offset in range(1, 366)]
    metrics = trend_metrics(history, as_of)
    assert metrics["avg_7"] != metrics["avg_30"]
    assert metrics["avg_30"] != metrics["avg_365"]
    assert metrics["data_days"] == 365


def test_booking_floor_and_three_window_trend_determine_demand():
    metrics = {"avg_7d": 80.0, "avg_30d": 70.0, "avg_365d": 60.0, "data_days": 365}
    result = daily_demand_forecast(metrics, 50.0, 0.5, "tomorrow")
    assert result["predicted_guests"] == 100
    assert result["reservation_implied_guests"] == 100.0
    assert result["confidence"] > 0.5


def test_staffing_comes_from_demand_skill_hours_and_target_only():
    policy = {
        "role_code": "service", "role_name": "服务", "required_skill": "table_service",
        "shift_hours": 4.0, "target_guests_per_labor_hour": 10.0,
        "minimum_staff": 2, "current_staff": 3, "available_skilled_staff": 2,
        "max_hours_per_person_week": 40.0, "source": "test", "is_simulated": True,
        "version": 4,
    }
    result = role_recommendation(130, policy)
    assert result["recommended_staff"] == 4
    assert result["gap"] == 1
    assert result["skill_gap"] == 2
    assert "historical_actual" not in result


def test_llm_cannot_author_numbers_or_unsupported_causality():
    assert _numeric_free_validator("建议先确认技能覆盖，再考虑调整班次。") is None
    assert _numeric_free_validator("建议增加2人。") == "llm_authored_number"
    assert _numeric_free_validator("一定是天气导致。") == "unsupported_causal_claim"


def test_plan_fingerprint_changes_with_fact_or_policy_version():
    args = ("MOCK_REST", 1, date(2026, 8, 4), "午市", "service", 100, 3, 1)
    first = make_plan_fingerprint(*args)
    assert first == make_plan_fingerprint(*args)
    assert first != make_plan_fingerprint(*args[:-1], 2)


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *exc):
        return False


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


class _AdjustmentConn:
    def __init__(self, policy):
        self.policy = policy
        self.inserted = None

    async def execute(self, *_args):
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        if "FROM restaurant_staffing_policy" in sql:
            return self.policy
        if "WHERE factory_id=$1 AND idempotency_key=$2" in sql:
            return None
        if "INSERT INTO restaurant_staffing_adjustment" in sql:
            self.inserted = args
            return {"id": 91, "created_at": __import__("datetime").datetime(2026, 8, 3, 10, 0)}
        raise AssertionError(sql)


def _preview(target, fingerprint, policy):
    return {
        "daily_rows": [{
            "date": target.isoformat(), "daypart": "午市", "predicted_guests": 130,
            "roles": [{
                "role_code": "service", "policy_version": policy["version"],
                "recommended_staff": 4, "current_staff": 3,
                "plan_fingerprint": fingerprint,
            }],
        }]
    }


def test_adjustment_revalidates_exact_preview_before_business_write(monkeypatch):
    from smartbi.services.restaurant import staffing_forecast as module

    today = date(2026, 8, 3)
    monkeypatch.setattr(module, "singapore_today", lambda: today)
    target = today + timedelta(days=1)
    policy = {
        "role_code": "service", "role_name": "服务", "required_skill": "table_service",
        "shift_hours": 4.0, "target_guests_per_labor_hour": 10.0,
        "minimum_staff": 2, "current_staff": 3, "available_skilled_staff": 5,
        "max_hours_per_person_week": 40.0, "source": "test", "is_simulated": True,
        "version": 4,
    }
    fingerprint = make_plan_fingerprint("MOCK_REST", 1, target, "午市", "service", 130, 4, 4)
    conn = _AdjustmentConn(policy)
    service = RestaurantStaffingService(_Pool(conn))
    service.build_dashboard = AsyncMock(return_value=_preview(target, fingerprint, policy))
    payload = {
        "store_id": 1, "target_date": target, "daypart": "午市", "role_code": "service",
        "predicted_guests": 130, "policy_version": 4, "prior_staff": 3,
        "recommended_staff": 4, "adjusted_staff": 4, "plan_fingerprint": fingerprint,
        "reason": "按预测建议调整", "idempotency_key": "preview-0001",
    }
    result = asyncio.run(service.apply_adjustment(
        "MOCK_REST", payload, actor_user_id="u1", actor_role="restaurant_manager"
    ))
    assert result["business_write"] is True
    assert conn.inserted is not None


def test_adjustment_rejects_stale_plan_without_acquiring_write_connection(monkeypatch):
    from smartbi.services.restaurant import staffing_forecast as module

    today = date(2026, 8, 3)
    monkeypatch.setattr(module, "singapore_today", lambda: today)
    target = today + timedelta(days=1)
    pool = _Pool(_AdjustmentConn({}))
    service = RestaurantStaffingService(pool)
    service.build_dashboard = AsyncMock(return_value=_preview(target, "a" * 64, {"version": 4}))
    payload = {
        "store_id": 1, "target_date": target, "daypart": "午市", "role_code": "service",
        "predicted_guests": 130, "policy_version": 4, "prior_staff": 3,
        "recommended_staff": 4, "adjusted_staff": 4, "plan_fingerprint": "b" * 64,
        "reason": "按预测建议调整", "idempotency_key": "preview-0002",
    }
    with pytest.raises(ValueError, match="forecast plan changed"):
        asyncio.run(service.apply_adjustment(
            "MOCK_REST", payload, actor_user_id="u1", actor_role="restaurant_manager"
        ))
