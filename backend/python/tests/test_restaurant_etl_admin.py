"""TDD tests for /api/smartbi/restaurant/etl admin endpoints (餐饮 Phase A Task 1.4).

Three focused unit tests that do NOT require a live DB or running app:
- test_trigger_rejects_non_admin  → 403 when role is 'operator'
- test_trigger_rejects_invalid_factory → 400 when factoryId is ''
- test_trigger_returns_job_id_for_valid_admin → queued/running jobId for platform_admin

Pattern mirrors test_provenance_audit.py: mount only the target router inside
a minimal FastAPI test app, inject request.state fields via tiny middleware.
No circular import from main.py because we never import main here.
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient


# ── Minimal auth-state injector ──────────────────────────────────────────────

def _make_test_app(role: str, factory_id: str = "F001") -> FastAPI:
    """Build a tiny FastAPI app that mounts the ETL admin router.

    A lightweight ASGI middleware injects ``request.state.role`` and
    ``request.state.factory_id`` to simulate JWT auth without spinning up
    the full auth_middleware stack.
    """
    from smartbi.api.restaurant_etl_admin import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi/restaurant/etl")
    return app


# ── Tests ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_trigger_rejects_non_admin():
    """role='operator' → HTTPException 403 with '管理员' in detail."""
    app = _make_test_app(role="operator", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    resp = client.post("/api/smartbi/restaurant/etl/trigger", json={"factoryId": "F001"})

    assert resp.status_code == 403
    body = resp.json()
    # Project format: {success: false, message: "...", code: "..."}
    # The detail from require_admin contains "管理员"
    assert "管理员" in (body.get("message") or body.get("detail") or "")


@pytest.mark.asyncio
async def test_trigger_rejects_invalid_factory():
    """role='factory_super_admin', factoryId='' → HTTPException 400."""
    app = _make_test_app(role="factory_super_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    resp = client.post("/api/smartbi/restaurant/etl/trigger", json={"factoryId": ""})

    assert resp.status_code == 400
    body = resp.json()
    assert body.get("success") is False or "factoryId" in str(body)


@pytest.mark.asyncio
async def test_trigger_returns_job_id_for_valid_admin():
    """role='platform_admin' + valid factoryId → result has jobId + status='queued'/'running'."""
    from unittest.mock import patch, AsyncMock

    app = _make_test_app(role="platform_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    # Patch _enqueue_job so _run_job (which needs live DB/pools) is never called.
    with patch(
        "smartbi.api.restaurant_etl_admin._enqueue_job",
        new=AsyncMock(return_value=None),
    ):
        resp = client.post(
            "/api/smartbi/restaurant/etl/trigger",
            json={"factoryId": "F001"},
        )

    assert resp.status_code == 200
    body = resp.json()
    assert "jobId" in body
    assert body.get("status") in ("queued", "running")


@pytest.mark.asyncio
async def test_trigger_rejects_cross_factory_for_non_platform_admin():
    """factory_super_admin must not trigger ETL for another tenant."""
    from unittest.mock import AsyncMock, patch

    app = _make_test_app(role="factory_super_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    with patch(
        "smartbi.api.restaurant_etl_admin._enqueue_job",
        new=AsyncMock(return_value=None),
    ):
        resp = client.post(
            "/api/smartbi/restaurant/etl/trigger",
            json={"factoryId": "F002"},
        )

    assert resp.status_code == 403
