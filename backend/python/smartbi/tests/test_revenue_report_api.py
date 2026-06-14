"""Tests for api.revenue_report — 6 endpoints contract + factory_id guard.

Spec: docs/qa-specs/2026-05-12-qhj-revenue-report-design.md §8 + §10.7
Plan: docs/superpowers/plans/2026-05-12-qhj-revenue-report.md Task G2

These tests exercise the routing logic, request/response shape, and JWT/
internal-secret factory_id guard. Heavy mocking of the helper +
asyncpg pool to avoid touching real DB.
"""
import io
from datetime import datetime
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from smartbi.api.revenue_report import router  # ensure import works


# Sanity import — fail fast if module structure is broken.
def test_router_imports_and_has_routes():
    paths = sorted(r.path for r in router.routes)
    # All 6 endpoints present at the {factory_id} mount.
    assert any("upload" in p for p in paths)
    assert any("prepare" in p for p in paths)
    assert any("generate" in p for p in paths)
    assert any("download" in p for p in paths)
    assert any("stores" in p for p in paths)
    assert any("audit-log" in p for p in paths)


def test_prefix_is_factory_scoped():
    """All routes mounted under /api/smartbi/{factory_id}/revenue-report/*."""
    assert all(
        r.path.startswith("/api/smartbi/{factory_id}/revenue-report")
        for r in router.routes
    )


# ─── Functional tests ───────────────────────────────────────────────────

def _mk_app_with_router():
    """Build a FastAPI app with our router for TestClient."""
    from fastapi import FastAPI
    from smartbi_compat._rbac_role import (
        RbacForbiddenException,
        rbac_forbidden_handler,
    )
    app = FastAPI()

    @app.middleware("http")
    async def inject_test_role(request, call_next):
        request.state.role = request.headers.get("x-test-role", "factory_super_admin")
        return await call_next(request)

    app.add_exception_handler(RbacForbiddenException, rbac_forbidden_handler)
    app.include_router(router)
    return app


@pytest.fixture
def fake_pool():
    """Mock asyncpg pool. Returns store list / audit log rows / etc per test."""
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value="SET")
    conn.fetch = AsyncMock(return_value=[])
    conn.fetchval = AsyncMock(return_value=None)
    conn.fetchrow = AsyncMock(return_value=None)
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool = MagicMock()
    pool.acquire = MagicMock(return_value=ctx)
    return pool, conn


@pytest.mark.asyncio
async def test_stores_endpoint_returns_list_excluding_closed(fake_pool, monkeypatch):
    """GET /stores — returns dim_store rows filtered by exclude_closed."""
    pool, conn = fake_pool
    conn.fetch = AsyncMock(return_value=[
        {"store_id": 1, "name": "青花椒南方百联店"},
        {"store_id": 2, "name": "青花椒徐汇店"},
    ])

    with patch("smartbi.api.revenue_report._get_pool", new=AsyncMock(return_value=pool)), \
        patch(
        "smartbi.api.revenue_report._enforce_factory_match",
        return_value="R_QINGHUAJIAO_REAL",
    ):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.get(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/stores"
            )
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True
    assert len(body["data"]) == 2
    assert {s["name"] for s in body["data"]} == {
        "青花椒南方百联店", "青花椒徐汇店",
    }


@pytest.mark.asyncio
async def test_audit_log_returns_recent_rows(fake_pool):
    pool, conn = fake_pool
    conn.fetch = AsyncMock(return_value=[
        {
            "id": 1, "generated_by": "user_x",
            "generated_at": datetime(2025, 10, 7, 18, 0),
            "params_snapshot": {"store_ids": [1]},
            "file_size_bytes": 12345, "status": "ok",
            "cache_hit": False, "duration_ms": 420,
            "gold_materialized_at": datetime(2025, 10, 7, 18, 0),
        },
    ])

    with patch("smartbi.api.revenue_report._get_pool", new=AsyncMock(return_value=pool)), \
        patch(
        "smartbi.api.revenue_report._enforce_factory_match",
        return_value="R_QINGHUAJIAO_REAL",
    ):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.get(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/audit-log?limit=5"
            )
    assert resp.status_code == 200
    rows = resp.json()["data"]
    assert len(rows) == 1
    assert rows[0]["status"] == "ok"


@pytest.mark.asyncio
async def test_prepare_returns_download_url_and_summary(fake_pool):
    pool, _ = fake_pool

    fake_helper = AsyncMock(return_value=(
        "revenue_report:R_QINGHUAJIAO_REAL:HASH:2025-10-07T18:00:00",
        {"store_count": 2, "date_range": "2025-10-01 - 2025-10-07",
         "gold_materialized_at": "2025-10-07T18:00:00",
         "file_size_bytes": 1024, "cache_hit": False, "is_stale": False},
        io.BytesIO(b"PKfakexlsx"),
        {"block1_yoy": [], "block2_mom": [], "block3_meal_split": [], "meta": {}},
    ))
    fake_resolve = AsyncMock(return_value=[1, 2])

    with patch("smartbi.api.revenue_report._get_pool", new=AsyncMock(return_value=pool)), \
            patch(
                "smartbi.api.revenue_report._enforce_factory_match",
                return_value="R_QINGHUAJIAO_REAL",
    ), \
            patch("smartbi.api.revenue_report._resolve_store_ids", new=fake_resolve), \
            patch("smartbi.api.revenue_report._generate_with_cache", new=fake_helper):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.post(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/prepare",
                json={
                    "store_names": [], "date_from": "2025-10-01",
                    "date_to": "2025-10-07", "meal_periods": [],
                },
            )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert "download_url" in data
    assert "/revenue-report/download/" in data["download_url"]
    assert data["summary"]["store_count"] == 2


@pytest.mark.asyncio
async def test_generate_streams_xlsx_with_response_headers(fake_pool):
    pool, _ = fake_pool

    fake_helper = AsyncMock(return_value=(
        "revenue_report:F:HASH:T",
        {"store_count": 1, "date_range": "x",
         "gold_materialized_at": "2025-10-07T18:00:00",
         "file_size_bytes": 1024, "cache_hit": False, "is_stale": False},
        io.BytesIO(b"PKfakexlsx"),
        {"block1_yoy": [], "block2_mom": [], "block3_meal_split": [], "meta": {}},
    ))
    fake_resolve = AsyncMock(return_value=[1])

    with patch("smartbi.api.revenue_report._get_pool", new=AsyncMock(return_value=pool)), \
            patch(
                "smartbi.api.revenue_report._enforce_factory_match",
                return_value="R_QINGHUAJIAO_REAL",
    ), \
            patch("smartbi.api.revenue_report._resolve_store_ids", new=fake_resolve), \
            patch("smartbi.api.revenue_report._generate_with_cache", new=fake_helper):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.post(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/generate",
                json={
                    "store_names": [], "date_from": "2025-10-01",
                    "date_to": "2025-10-07", "meal_periods": [],
                },
            )
    assert resp.status_code == 200
    # Required custom headers (CORS expose_headers must be set in main.py for browser).
    assert resp.headers.get("x-cache-hit") == "false"
    assert resp.headers.get("x-gold-materialized-at") == "2025-10-07T18:00:00"
    assert resp.headers.get("x-store-count") == "1"
    assert resp.content.startswith(b"PK")


@pytest.mark.asyncio
async def test_factory_mismatch_returns_403():
    """_enforce_factory_match raises HTTPException(403); endpoint surfaces it."""
    from fastapi import HTTPException
    with patch(
        "smartbi.api.revenue_report._enforce_factory_match",
        side_effect=HTTPException(status_code=403, detail="factory mismatch"),
    ):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.get(
                "/api/smartbi/R_OTHER_FACTORY/revenue-report/stores"
            )
    assert resp.status_code == 403


@pytest.mark.asyncio
async def test_sales_manager_is_forbidden_from_revenue_report_upload(fake_pool):
    """Revenue report exposes full POS revenue Excel; sales_manager is not allowed."""
    pool, _ = fake_pool

    with patch("smartbi.api.revenue_report._get_pool", new=AsyncMock(return_value=pool)), \
        patch(
        "smartbi.api.revenue_report._enforce_factory_match",
        return_value="R_QINGHUAJIAO_REAL",
    ):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.post(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/upload",
                headers={"x-test-role": "sales_manager"},
                files={"files": ("详细日报表.csv", b"a,b\n1,2", "text/csv")},
            )

    assert resp.status_code == 403
    body = resp.json()
    assert body["success"] is False
    assert body["meta"]["role"] == "sales_manager"
    assert body["meta"]["action"] == "write"


@pytest.mark.asyncio
async def test_download_endpoint_rejects_cross_factory_cache_key():
    """cache_key must start with revenue_report:{factory_id}: to be served."""
    with patch(
        "smartbi.api.revenue_report._enforce_factory_match",
        return_value="R_QINGHUAJIAO_REAL",
    ):
        from httpx import ASGITransport, AsyncClient
        app = _mk_app_with_router()
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test",
        ) as ac:
            resp = await ac.get(
                "/api/smartbi/R_QINGHUAJIAO_REAL/revenue-report/"
                "download/revenue_report:OTHER_FACTORY:HASH:TS"
            )
    assert resp.status_code == 403
