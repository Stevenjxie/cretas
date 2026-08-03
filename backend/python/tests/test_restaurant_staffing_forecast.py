from __future__ import annotations

import asyncio
from datetime import date, datetime, timedelta
from unittest.mock import AsyncMock

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import match_restaurant_ops
from smartbi.services.restaurant.staffing_forecast import (
    RestaurantStaffingService,
    _candidate_narrative_validator,
    _numeric_free_validator,
    _strip_structural_numbering,
    daily_demand_forecast,
    horizon_from_question,
    horizon_window,
    make_plan_fingerprint,
    requests_non_forecast_staffing_window,
    role_recommendation,
    trend_direction_label,
    trend_metrics,
    work_hour_capacity_plan,
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


@pytest.mark.parametrize(
    "question",
    [
        "全部门店最近30天晚市人手够不够",
        "各岗位这个月的人效怎么样",
        "本月全部门店晚上生意怎么样",
        "今天怎么排班",
    ],
)
def test_historical_or_current_staffing_window_requires_future_horizon(question):
    assert requests_non_forecast_staffing_window(question) is True


@pytest.mark.parametrize(
    "question",
    [
        "明天怎么排班",
        "下周需要多少兼职",
        "下个月各店人效安排",
        "晚市怎么安排",
    ],
)
def test_future_or_timeless_staffing_question_keeps_forecast_flow(question):
    assert requests_non_forecast_staffing_window(question) is False


def test_trends_keep_independent_7_30_365_windows():
    as_of = date(2026, 8, 3)
    history = [(as_of - timedelta(days=offset), float(offset)) for offset in range(1, 366)]
    metrics = trend_metrics(history, as_of)
    assert metrics["avg_7"] != metrics["avg_30"]
    assert metrics["avg_30"] != metrics["avg_365"]
    assert metrics["data_days"] == 365


def test_trend_direction_is_deterministic_factbook_evidence():
    assert trend_direction_label(
        {"avg_7": 120.0, "avg_30": 100.0, "avg_365": 80.0}, "客流"
    ) == "客流短期高于中期、客流中期高于长期"
    assert trend_direction_label(
        {"avg_7": None, "avg_30": None, "avg_365": None}, "历史人效"
    ) == "历史人效证据不足"


def test_booking_floor_and_three_window_trend_determine_demand():
    metrics = {"avg_7": 80.0, "avg_30": 70.0, "avg_365": 60.0, "data_days": 365}
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


def test_weekly_hours_and_skills_are_enforced_per_role():
    role = {
        "role_code": "service", "shift_hours": 8.0, "recommended_staff": 2,
        "current_staff": 2, "gap": 0, "skill_gap": 0,
        "max_hours_per_person_week": 40.0,
    }
    plan = work_hour_capacity_plan([[role] for _ in range(7)], 7, "week")
    assert plan["weekly_capacity_gap_hours"] == 32.0
    assert plan["part_time_people"] == 2


def test_month_part_time_capacity_scales_by_calendar_weeks_not_days():
    role = {
        "role_code": "kitchen", "shift_hours": 4.0, "recommended_staff": 2,
        "current_staff": 2, "gap": 0, "skill_gap": 0,
        "max_hours_per_person_week": 20.0,
    }
    plan = work_hour_capacity_plan([[role] for _ in range(30)], 30, "month")
    assert plan["week_units"] == 5
    assert plan["workload_gap_hours"] == 40.0
    assert plan["part_time_people"] == 1


def test_llm_cannot_author_numbers_or_unsupported_causality():
    assert _numeric_free_validator("建议先确认技能覆盖，再考虑调整班次。") is None
    assert _numeric_free_validator("建议增加2人。") == "llm_authored_number"
    assert _numeric_free_validator("建议增加两名服务员。") == "llm_authored_number"
    assert _numeric_free_validator("先处理第一家门店。") == "llm_authored_number"
    assert _numeric_free_validator("一定是天气导致。") == "unsupported_causal_claim"


def test_llm_structural_numbering_is_removed_without_allowing_business_numbers():
    content = "1. 建议先确认技能覆盖。\n2、再预览班次调整。"
    assert _candidate_narrative_validator(content) is None
    stripped = _strip_structural_numbering(content)
    assert "1" not in stripped
    assert "2" not in stripped
    assert _candidate_narrative_validator("建议增加2人。") == "llm_authored_number"


def test_plan_fingerprint_changes_with_fact_or_policy_version():
    args = ("MOCK_REST", 1, date(2026, 8, 4), "午市", "service", 100, 3, 1)
    first = make_plan_fingerprint(*args)
    assert first == make_plan_fingerprint(*args)
    assert first != make_plan_fingerprint(*args[:-1], 2)
    assert first != make_plan_fingerprint(*args, fact_context='{"reserved": 20}')


def _answer_dashboard():
    return {
        "horizon_label": "明天", "window_start": "2026-08-04", "window_end": "2026-08-04",
        "summary": {
            "predicted_guests": 130, "reserved_guests": 50,
            "reservation_coverage_pct": 38.5, "recommended_staff": 4,
            "current_staff": 3, "positive_gap": 1, "part_time_people": 1,
            "confidence_pct": 80.0,
        },
        "summary_rows": [{
            "store_name": "测试门店", "daypart": "午市", "predicted_guests": 130,
            "reserved_guests": 50, "reservation_coverage_pct": 38.5,
            "recommended_staff": 4, "current_staff": 3, "gap": 1,
            "positive_gap": 1, "confidence_pct": 80.0,
            "evidence_label": "当前预订已覆盖；客流短期高于中期；历史人效只作证据",
        }],
        "daily_rows": [], "sources": [],
    }


def test_answer_question_must_call_existing_llm_slot_with_factbook(monkeypatch):
    from smartbi.services.restaurant import staffing_forecast as module

    llm_call = AsyncMock(return_value={
        "choices": [{"message": {"content": "建议先确认技能覆盖，再预览班次调整。"}}],
    })
    monkeypatch.setattr(module, "call_chain", llm_call)
    service = RestaurantStaffingService(None)
    service.build_dashboard = AsyncMock(return_value=_answer_dashboard())
    result = asyncio.run(service.answer_question(
        "MOCK_REST", "明天怎么排班", role="restaurant_manager", as_of=date(2026, 8, 3)
    ))
    assert result["llm_used"] is True
    assert result["llm_numeric_authorship"] is False
    assert "130" in result["factbook"]
    args = llm_call.await_args.args
    assert args[0] == module.SLOT.INSIGHTS
    assert "用户问题：明天怎么排班" in args[1]["messages"][1]["content"]
    assert "130" not in args[1]["messages"][1]["content"]
    assert "当前预订已覆盖；客流短期高于中期" in args[1]["messages"][1]["content"]
    assert _numeric_free_validator(args[1]["messages"][1]["content"]) is None
    assert llm_call.await_args.kwargs["content_validator"](
        "1. 建议先确认技能覆盖。"
    ) is None


def test_answer_question_strips_provider_list_ordinals(monkeypatch):
    from smartbi.services.restaurant import staffing_forecast as module

    monkeypatch.setattr(module, "call_chain", AsyncMock(return_value={
        "choices": [{"message": {"content": "1. 建议先确认技能覆盖。\n2、再预览班次调整。"}}],
    }))
    service = RestaurantStaffingService(None)
    service.build_dashboard = AsyncMock(return_value=_answer_dashboard())
    result = asyncio.run(service.answer_question("MOCK_REST", "明天怎么排班"))
    narrative = result["answer_text"].split("**大模型解读（只解释 FactBook，不生成数字）**", 1)[1]
    assert "1." not in narrative
    assert "2、" not in narrative
    assert "建议先确认技能覆盖" in narrative


def test_answer_question_rejects_model_number_even_after_provider_validation(monkeypatch):
    from smartbi.services.restaurant import staffing_forecast as module

    monkeypatch.setattr(module, "call_chain", AsyncMock(return_value={
        "choices": [{"message": {"content": "建议增加两名服务员。"}}],
    }))
    service = RestaurantStaffingService(None)
    service.build_dashboard = AsyncMock(return_value=_answer_dashboard())
    with pytest.raises(RuntimeError, match="LLM staffing narrative failed grounding"):
        asyncio.run(service.answer_question("MOCK_REST", "明天怎么排班"))


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

    def transaction(self):
        return _Acquire(self)

    async def fetchrow(self, sql, *args):
        if "FROM restaurant_staffing_policy" in sql:
            return self.policy
        if "WHERE factory_id=$1 AND idempotency_key=$2" in sql:
            return None
        if "INSERT INTO restaurant_staffing_adjustment" in sql:
            self.inserted = args
            return {"id": 91, "created_at": __import__("datetime").datetime(2026, 8, 3, 10, 0)}
        raise AssertionError(sql)


class _DashboardConn:
    async def execute(self, *_args):
        return "SELECT 1"

    def transaction(self):
        return _Acquire(self)

    async def fetch(self, sql, *args):
        if "FROM dim_store" in sql:
            return [{"store_id": 1, "name": "测试门店"}]
        if "FROM agg_daily_order_type_meal" in sql:
            return [{
                "store_id": 1, "date": date(2026, 8, 3), "daypart": "午市",
                "guests": 80.0, "orders": 40,
            }]
        if "GROUP BY date_trunc('minute', source_updated_at)" in sql:
            return [{
                "minute": datetime(2026, 8, 3, 9, 0),
                "event_count": 6,
                "guest_count": 24,
            }]
        if "ORDER BY reservation.source_updated_at DESC, reservation.id DESC" in sql:
            return [{
                "external_ref": "stream-1", "store_id": 1,
                "store_name": "测试门店",
                "reservation_date": date(2026, 8, 4), "daypart": "午市",
                "table_count": 1, "guest_count": 4, "status": "CONFIRMED",
                "source": "test-platform", "is_simulated": True,
                "source_updated_at": datetime(2026, 8, 3, 9, 0),
            }]
        if "FROM fact_restaurant_reservation" in sql:
            return [{
                "store_id": 1, "reservation_date": date(2026, 8, 4),
                "daypart": "午市", "status": "CONFIRMED", "source": "test-platform",
                "is_simulated": False, "table_count": 8, "guest_count": 32,
                "reservation_order_count": 6,
                "source_updated_at": __import__("datetime").datetime(2026, 8, 3, 9, 0),
            }]
        if "FROM restaurant_staffing_policy" in sql:
            return [{
                "store_id": 1, "daypart": "午市", "role_code": "service",
                "role_name": "服务员", "required_skill": "table_service",
                "shift_hours": 4.0, "target_guests_per_labor_hour": 10.0,
                "minimum_staff": 2, "current_staff": 3,
                "available_skilled_staff": 4, "max_hours_per_person_week": 40.0,
                "expected_reservation_share": 0.35, "source": "test-policy",
                "is_simulated": False, "version": 2,
            }]
        if "FROM fact_staffing_daypart" in sql:
            return [{
                "store_id": 1, "daypart": "午市", "weekday_type": "weekday",
                "avg_orders": 40.0, "staff_on_duty": 4, "target": 12.0,
            }]
        if "FROM restaurant_staffing_adjustment" in sql:
            return []
        raise AssertionError(sql)


class _ImportConn:
    def __init__(self):
        self.payloads = []

    async def execute(self, *_args):
        return "SELECT 1"

    def transaction(self):
        return _Acquire(self)

    async def fetch(self, sql, *args):
        if "FROM dim_store" in sql:
            return [{"store_id": 1}]
        if "SELECT source,external_ref,source_updated_at" in sql:
            return [{
                "source": "platform-a", "external_ref": "stale",
                "source_updated_at": datetime(2026, 8, 3, 12, 0),
            }, {
                "source": "platform-a", "external_ref": "same",
                "source_updated_at": datetime(2026, 8, 3, 11, 0),
            }]
        raise AssertionError(sql)

    async def executemany(self, _sql, payloads):
        self.payloads = list(payloads)


def test_platform_import_deduplicates_and_reports_exact_business_writes():
    conn = _ImportConn()
    service = RestaurantStaffingService(_Pool(conn))
    base = {
        "source": "platform-a", "store_id": 1,
        "reservation_date": date(2026, 8, 4), "daypart": "午市",
        "table_count": 1, "guest_count": 4, "status": "CONFIRMED",
        "is_simulated": False,
    }
    result = asyncio.run(service.import_reservations("MOCK_REST", [
        {**base, "external_ref": "new", "source_updated_at": datetime(2026, 8, 3, 9, 0)},
        {**base, "external_ref": "new", "guest_count": 6,
         "source_updated_at": datetime(2026, 8, 3, 10, 0)},
        {**base, "external_ref": "stale", "source_updated_at": datetime(2026, 8, 3, 8, 0)},
        {**base, "external_ref": "same", "source_updated_at": datetime(2026, 8, 3, 11, 0)},
    ]))
    assert result == {
        "received": 4, "deduplicated": 3, "inserted_rows": 1,
        "updated_rows": 0, "stale_ignored_rows": 1, "replay_ignored_rows": 1,
        "business_write_rows": 1,
    }
    assert len(conn.payloads) == 3
    assert next(row for row in conn.payloads if row[2] == "new")[7] == 6


def test_dashboard_contract_matches_web_dto_and_adjustment_preview():
    service = RestaurantStaffingService(_Pool(_DashboardConn()))
    dashboard = asyncio.run(service.build_dashboard(
        "MOCK_REST", "tomorrow", as_of=date(2026, 8, 3)
    ))
    assert set((
        "summary_rows", "daily_rows", "sources", "generated_at", "as_of",
        "numeric_source", "historical_productivity_rule", "live_stream",
    )) <= dashboard.keys()
    assert dashboard["numeric_source"] == "forecast_factbook_only"
    assert dashboard["historical_productivity_rule"] == "evidence_only_not_gap_input"
    assert "rows" not in dashboard
    assert "daily_plan" not in dashboard
    assert "reservation_sources" not in dashboard
    assert dashboard["summary_rows"][0]["store_name"] == "测试门店"
    trends = dashboard["summary_rows"][0]["trends"]
    assert trends["guest_traffic"]["avg_7"] == 80.0
    assert trends["pos_orders"]["avg_7"] == 40.0
    assert trends["historical_productivity"]["avg_7"] == 10.0
    assert trends["historical_productivity"]["direction_rule"] == "evidence_only_not_gap_input"
    assert dashboard["daily_rows"][0]["roles"][0]["plan_fingerprint"]
    assert dashboard["daily_rows"][0]["reservation_orders"] == 6
    assert dashboard["summary"]["reservation_orders"] == 6
    assert dashboard["sources"][0]["source"] == "test-platform"
    assert dashboard["sources"][0]["event_count"] == 6
    assert dashboard["live_stream"]["event_count"] == 6
    assert dashboard["live_stream"]["guest_count"] == 24
    assert dashboard["live_stream"]["recent_events"][0]["is_simulated"] is True


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
