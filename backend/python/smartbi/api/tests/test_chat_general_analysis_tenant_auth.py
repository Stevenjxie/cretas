from __future__ import annotations

import asyncio
from datetime import date
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

import smartbi.api.chat as chat
import smartbi.config as smartbi_config
import smartbi.gold.restaurant_ops_router as restaurant_router
import smartbi.services.chat_session_service as chat_session_service
from smartbi.gold.restaurant_ops_router import OpsAnswer


def _http_request(factory_id="DEMO_REST"):
    return SimpleNamespace(state=SimpleNamespace(
        factory_id=factory_id,
        role="restaurant_manager",
        user_id=9,
    ))


def _install_structured_resolver(monkeypatch, captured):
    async def _get_pool():
        return object()

    async def _resolve(code, pool, factory_id, **kwargs):
        captured["code"] = code
        captured["factory_id"] = factory_id
        captured["kwargs"] = kwargs
        return OpsAnswer(
            code=code,
            title="门店毛利",
            answer_text="人民路店的毛利分析已按指定日期完成。",
            charts=[],
            kpis=[],
            meta={},
        )

    monkeypatch.setattr(smartbi_config, "get_pg_pool", _get_pool)
    monkeypatch.setattr(restaurant_router, "resolve_by_code", _resolve)
    monkeypatch.setattr(chat, "_chat_cache_get", lambda _key: None)
    monkeypatch.setattr(chat, "_chat_cache_set", lambda *_args: None)


def test_general_analysis_store_context_maps_only_data_factory(monkeypatch):
    captured = {}
    _install_structured_resolver(monkeypatch, captured)

    def _session_key(factory_id, trusted_user_id, session_id):
        captured["session_factory_id"] = factory_id
        captured["session_user_id"] = trusted_user_id
        return None

    monkeypatch.setattr(chat, "_trusted_restaurant_session_key", _session_key)

    class _ChatSessionService:
        def __init__(self, _pool):
            pass

        async def lookup(self, session_id, factory_id, *, user_id):
            captured["lookup_factory_id"] = factory_id
            return None

        async def upsert(self, **kwargs):
            captured["upsert_factory_id"] = kwargs["factory_id"]
            captured["upsert_user_id"] = kwargs["user_id"]

    monkeypatch.setattr(chat_session_service, "ChatSessionService", _ChatSessionService)
    request = chat.GeneralAnalysisRequest(
        query="比较人民路店两个日期范围的毛利",
        table_type="restaurant_ops",
        expected_intent="RESTAURANT_OPS_STORE_MARGIN",
        allow_tenant_data_fallback=False,
        session_id="restaurant-session-1",
        context={
            "store_id": "S-1",
            "store_name": "人民路店",
            "start_date": "2026-07-20",
            "end_date": "2026-07-21",
            "comparison_start_date": "2026-07-18",
            "comparison_end_date": "2026-07-19",
            "time_anchor_date": "2026-07-21",
        },
    )

    response = asyncio.run(chat.general_analysis(request, _http_request()))

    assert response.success is True
    assert captured["factory_id"] == "RES_3101_009"
    assert captured["session_factory_id"] == "DEMO_REST"
    assert captured["session_user_id"] == 9
    assert captured["lookup_factory_id"] == "DEMO_REST"
    assert captured["upsert_factory_id"] == "DEMO_REST"
    assert captured["upsert_user_id"] == 9
    assert captured["kwargs"]["store_id"] == "S-1"
    assert captured["kwargs"]["store_name"] == "人民路店"
    assert captured["kwargs"]["date_range"] == (
        date(2026, 7, 20), date(2026, 7, 21),
    )
    assert captured["kwargs"]["comparison_date_range"] == (
        date(2026, 7, 18), date(2026, 7, 19),
    )
    assert captured["kwargs"]["today"] == date(2026, 7, 21)


def test_general_analysis_time_anchor_is_passed_without_factory_mapping(monkeypatch):
    captured = {}
    _install_structured_resolver(monkeypatch, captured)
    request = chat.GeneralAnalysisRequest(
        query="昨天营业额是多少",
        table_type="restaurant_ops",
        expected_intent="RESTAURANT_OPS_SALES_SUMMARY",
        allow_tenant_data_fallback=False,
        context={"time_anchor_date": "2026-07-21"},
    )

    response = asyncio.run(chat.general_analysis(request, _http_request()))

    assert response.success is True
    assert captured["factory_id"] == "DEMO_REST"
    assert captured["code"] == "RESTAURANT_OPS_SALES_SUMMARY"
    assert captured["kwargs"]["today"] == date(2026, 7, 21)


def test_general_analysis_context_cannot_override_authenticated_tenant():
    request = chat.GeneralAnalysisRequest(
        query="人民路店毛利",
        table_type="restaurant_ops",
        expected_intent="RESTAURANT_OPS_STORE_MARGIN",
        context={"factory_id": "RES_OTHER", "store_name": "人民路店"},
    )

    with pytest.raises(HTTPException) as exc_info:
        asyncio.run(chat.general_analysis(request, _http_request("DEMO_REST")))

    assert exc_info.value.status_code == 422
    assert "上下文字段不受支持" in str(exc_info.value.detail)


@pytest.mark.parametrize("context", [
    {"start_date": "2026/07/20", "end_date": "2026-07-21"},
    {"start_date": "2026-07-22"},
    {"start_date": "2026-07-22", "end_date": "2026-07-21"},
    {"comparison_start_date": "2026-07-20", "comparison_end_date": "2026-07-21"},
    {"store_id": "S" * 65},
    {"store_name": "门店" * 81},
    {
        "start_date": "2025-12-30", "end_date": "2026-12-31",
        "time_anchor_date": "2026-12-31",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-21",
        "comparison_start_date": "2025-01-01", "comparison_end_date": "2026-01-02",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2026-07-10", "end_date": "2026-07-20",
        "comparison_start_date": "2026-07-20", "comparison_end_date": "2026-07-21",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-21",
        "comparison_start_date": "2026-07-18", "comparison_end_date": "2026-07-19",
    },
    {
        "start_date": "2026-07-20", "end_date": "2026-07-23",
        "time_anchor_date": "2026-07-22",
    },
    {
        "start_date": "2024-07-21", "end_date": "2024-07-22",
        "time_anchor_date": "2026-07-22",
    },
])
def test_restaurant_context_rejects_invalid_or_unbounded_values(context):
    with pytest.raises(HTTPException) as exc_info:
        chat._validated_restaurant_analysis_context(context, "restaurant_ops")
    assert exc_info.value.status_code == 422


def test_restaurant_context_accepts_disjoint_ranges_at_two_year_boundary():
    validated = chat._validated_restaurant_analysis_context({
        "start_date": "2025-07-23",
        "end_date": "2026-07-22",
        "comparison_start_date": "2024-07-22",
        "comparison_end_date": "2025-07-22",
        "time_anchor_date": "2026-07-22",
    }, "restaurant_ops")

    assert validated["start_date"] == date(2025, 7, 23)
    assert validated["comparison_start_date"] == date(2024, 7, 22)
    assert validated["time_anchor_date"] == date(2026, 7, 22)


def test_restaurant_context_allows_time_anchor_without_date_ranges():
    validated = chat._validated_restaurant_analysis_context(
        {"time_anchor_date": "2026-07-22"},
        "restaurant_ops",
    )

    assert validated == {"time_anchor_date": date(2026, 7, 22)}


def test_non_restaurant_context_does_not_enable_demo_mapping():
    assert chat._validated_restaurant_analysis_context(
        {"store_name": "人民路店"},
        "factory_ops",
    ) == {}
    assert chat._restaurant_analysis_data_factory_id("DEMO_REST", {}) == "DEMO_REST"
