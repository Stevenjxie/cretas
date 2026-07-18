from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from starlette.requests import Request

from smartbi.api import restaurant_sections
from smartbi.api.restaurant_sections import OwnerActionChatRequest


def _request(factory_id: str | None) -> Request:
    state = {}
    if factory_id is not None:
        state["factory_id"] = factory_id
    return Request({
        "type": "http",
        "method": "POST",
        "path": "/api/smartbi/restaurant/sections/owner-action-chat",
        "headers": [],
        "state": state,
    })


def _body(factory_id: str = "F001") -> OwnerActionChatRequest:
    return OwnerActionChatRequest(
        factory_id=factory_id,
        message="今天老板先做什么？",
        session_id="owner-session-001",
        demo_scenario="revenue_growth",
        store_name="测试门店",
        sub_sector="中餐",
        period="this_week",
    )


def test_owner_action_http_rejects_missing_authenticated_tenant(monkeypatch):
    called = False

    def fake_impl(*_args, **_kwargs):
        nonlocal called
        called = True
        return {"success": True, "data": {}}

    monkeypatch.setattr(restaurant_sections, "_owner_action_chat_impl", fake_impl)

    with pytest.raises(HTTPException) as exc:
        asyncio.run(restaurant_sections.owner_action_chat_http(_body(), _request(None)))

    assert exc.value.status_code == 401
    assert called is False


def test_owner_action_http_rejects_header_body_tenant_mismatch_before_side_effects(monkeypatch):
    called = False

    def fake_impl(*_args, **_kwargs):
        nonlocal called
        called = True
        return {"success": True, "data": {}}

    monkeypatch.setattr(restaurant_sections, "_owner_action_chat_impl", fake_impl)

    with pytest.raises(HTTPException) as exc:
        asyncio.run(restaurant_sections.owner_action_chat_http(_body("F002"), _request("F001")))

    assert exc.value.status_code == 403
    assert called is False


def test_owner_action_http_accepts_matching_internal_tenant_and_logs_same_tenant(monkeypatch):
    observed = {}

    def fake_impl(body, request):
        observed["impl_factory"] = body.factory_id
        observed["request_factory"] = request.state.factory_id
        return {
            "success": True,
            "data": {
                "answer": "先检查客流转化。",
                "scenario": "revenue_growth",
                "sessionId": "owner-session-001",
                "charts": [],
                "ownerDecisionPage": {},
            },
        }

    async def fake_log(**kwargs):
        observed["log_factory"] = kwargs["factory_id"]
        return 42

    monkeypatch.setattr(restaurant_sections, "_owner_action_chat_impl", fake_impl)
    monkeypatch.setattr(restaurant_sections, "_log_owner_action_chat_async", fake_log)

    response = asyncio.run(
        restaurant_sections.owner_action_chat_http(_body("F001"), _request("F001"))
    )

    assert response["success"] is True
    assert response["data"]["logId"] == 42
    assert observed == {
        "impl_factory": "F001",
        "request_factory": "F001",
        "log_factory": "F001",
    }


def test_owner_action_contract_rejects_second_factory_alias():
    with pytest.raises(ValidationError):
        OwnerActionChatRequest(
            factory_id="F001",
            factoryId="F002",
            message="今天老板先做什么？",
        )
