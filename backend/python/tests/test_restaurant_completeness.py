"""TDD tests for /api/smartbi/restaurant/completeness (餐饮 Phase A Task 2.1).

Three focused unit tests that do NOT require a live DB or running app:
- test_completeness_rejects_missing_factory_id  → 400 when factoryId=""
- test_completeness_returns_6_modules           → response has 6 modules
- test_factory_age_formula_for_new_factory      → _coverage_window_days formula

Pattern mirrors test_restaurant_etl_admin.py: mount only the target router inside
a minimal FastAPI test app, inject request.state fields via tiny middleware.
No circular import from main.py because we never import main here.
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient


# ── Minimal auth-state injector ──────────────────────────────────────────────

def _make_test_app(role: str, factory_id: str = "F001") -> FastAPI:
    """Build a tiny FastAPI app that mounts the completeness router."""
    from smartbi.api.restaurant_completeness import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi/restaurant")
    return app


# ── Tests ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_completeness_rejects_missing_factory_id():
    """factoryId="" → HTTPException 400."""
    app = _make_test_app(role="factory_super_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    resp = client.get("/api/smartbi/restaurant/completeness", params={"factoryId": ""})

    assert resp.status_code == 400
    body = resp.json()
    # Detail should mention factoryId or factory
    assert "factoryId" in str(body) or "factory" in str(body).lower()


@pytest.mark.asyncio
async def test_completeness_returns_6_modules():
    """Mock _fetch_module_stats so no real DB needed → response has exactly 6 modules."""
    from unittest.mock import patch, AsyncMock
    from smartbi.api.restaurant_completeness import _EXPECTED_MODULE_IDS

    # Build a fake stats dict that _fetch_module_stats would return
    fake_stats: dict = {}
    for mod_id in _EXPECTED_MODULE_IDS:
        fake_stats[mod_id] = {
            "count": 5,
            "last_updated": "2026-01-01T00:00:00+00:00",
        }

    app = _make_test_app(role="factory_super_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    with patch(
        "smartbi.config.get_pg_pool",
        new_callable=AsyncMock,
        return_value=AsyncMock(),
    ), patch(
        "smartbi.config.get_cretas_pool",
        new_callable=AsyncMock,
        return_value=AsyncMock(),
    ), patch(
        "smartbi.api.restaurant_completeness._fetch_module_stats",
        new_callable=AsyncMock,
        return_value=fake_stats,
    ), patch(
        "smartbi.api.restaurant_completeness._factory_age_days",
        new_callable=AsyncMock,
        return_value=30,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/completeness", params={"factoryId": "F001"}
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert "modules" in body, body
    assert len(body["modules"]) == 6


@pytest.mark.asyncio
async def test_factory_age_formula_for_new_factory():
    """_coverage_window_days should clamp to min(30, max(1, age_days))."""
    from smartbi.api.restaurant_completeness import _coverage_window_days

    assert _coverage_window_days(5) == 5
    assert _coverage_window_days(30) == 30
    assert _coverage_window_days(100) == 30
    # Edge cases
    assert _coverage_window_days(0) == 1
    assert _coverage_window_days(1) == 1


@pytest.mark.asyncio
async def test_fetch_module_stats_uses_gold_sales_and_dynamic_reviews():
    """DEMO-style data can live in gold/raw tables, not only upload names.

    POS completeness should count agg_daily rows. Review completeness should
    fall back from empty restaurant_reviews to smart_bi_dynamic_data upload rows.
    """
    from smartbi.api.restaurant_completeness import _fetch_module_stats

    class _Tx:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *_args):
            return False

    class _Acquire:
        def __init__(self, conn):
            self.conn = conn

        async def __aenter__(self):
            return self.conn

        async def __aexit__(self, *_args):
            return False

    class _Conn:
        def __init__(self, source: str):
            self.source = source

        def transaction(self):
            return _Tx()

        async def execute(self, *_args, **_kwargs):
            return None

        async def fetchrow(self, sql: str, *_args):
            if "FROM agg_daily" in sql:
                return {"cnt": 42, "last_upd": "2026-06-16"}
            if "FROM restaurant_reviews" in sql:
                return {"cnt": 0, "last_upd": None}
            if "FROM smart_bi_dynamic_data" in sql:
                return {"cnt": 19845, "last_upd": "2026-06-30 12:27:50"}
            return {"cnt": 0, "last_upd": None}

    class _Pool:
        def __init__(self, source: str):
            self.conn = _Conn(source)

        def acquire(self):
            return _Acquire(self.conn)

    stats = await _fetch_module_stats(
        "DEMO_REST",
        30,
        cretas_pool=_Pool("cretas"),
        smartbi_pool=_Pool("smartbi"),
    )

    assert stats["pos_sales"]["count"] == 42
    assert stats["pos_sales"]["last_updated"] == "2026-06-16"
    assert stats["review"]["count"] == 19845
    assert stats["review"]["last_updated"] == "2026-06-30 12:27:50"
