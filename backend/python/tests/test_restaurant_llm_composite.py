"""Sprint 11 MealClaw Response — unit tests for the composite LLM endpoint.

Tests cover:
- Auth gate (401 / 403)
- Strict whitelist response (5 fields only)
- Path B fallback: 30d empty → 365d widened, fallback flag in evidence
- Graceful degradation when underlying fetchers fail

Pattern mirrors test_restaurant_completeness.py — mount the router on a tiny
FastAPI app, inject ``request.state.role`` + ``request.state.factory_id`` via a
middleware, and patch the upstream coroutines to return fixture-only data.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, patch

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from smartbi.services.restaurant_llm_formatter import WHITELIST


# ── Test-app factory ────────────────────────────────────────────────────────

def _make_test_app(
    role: str | None = "factory_super_admin",
    factory_id: str = "RES_3101_009",
) -> FastAPI:
    from smartbi.api.restaurant_llm_composite import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi")
    return app


# ── Helpers ─────────────────────────────────────────────────────────────────

class _FakeRow(dict):
    """asyncpg Record-ish dict that supports both [key] and .get()."""


def _make_fake_conn(fetch_result):
    """Build an async context-manager that yields a conn with .fetch / .execute."""
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(return_value=fetch_result)

    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    return cm, conn


def _make_fake_pool(cm):
    pool = AsyncMock()
    pool.acquire = lambda: cm
    return pool


# ── Tests ───────────────────────────────────────────────────────────────────

def test_composite_requires_auth():
    app = _make_test_app(role=None)
    client = TestClient(app, raise_server_exceptions=False)
    resp = client.get(
        "/api/smartbi/restaurant/llm-composite",
        params={"factory_id": "RES_3101_009"},
    )
    assert resp.status_code == 401


def test_composite_rejects_cross_factory_query_for_non_admin():
    app = _make_test_app(role="department_manager", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)
    resp = client.get(
        "/api/smartbi/restaurant/llm-composite",
        params={"factory_id": "OTHER_FACTORY"},
    )
    assert resp.status_code == 403


def test_composite_rejects_empty_factory_id():
    app = _make_test_app()
    client = TestClient(app, raise_server_exceptions=False)
    resp = client.get(
        "/api/smartbi/restaurant/llm-composite",
        params={"factory_id": "   "},
    )
    assert resp.status_code == 400


def test_composite_strict_whitelist_response_shape():
    """Happy path: with data present, returns the 5 whitelisted fields."""
    app = _make_test_app()
    client = TestClient(app, raise_server_exceptions=False)

    # Fake top-ingredients rows
    fake_rows = [
        _FakeRow({
            "ingredient_id": "ing-1", "name": "猪肉", "category": "肉类", "unit": "kg",
            "total_value": 5000,
        }),
    ]
    cm, _ = _make_fake_conn(fake_rows)
    fake_pool = _make_fake_pool(cm)

    def _mod(mid, name, count, cov):
        return {
            "id": mid, "name": name, "recordCount": count,
            "coverage": cov, "windowDays": 30, "hasData": True,
            "lastUpdated": None, "missingHints": [],
        }

    fake_completeness = {
        "factoryId": "RES_3101_009",
        "factoryAgeDays": 365,
        "modules": [
            _mod("pos_sales", "POS 销售数据", 100, 90),
            _mod("menu_recipe", "菜品配方", 20, 80),
            _mod("requisition", "领料记录", 30, 100),
            _mod("wastage", "损耗记录", 10, 70),
            _mod("stocktaking", "盘点记录", 4, 60),
            _mod("review", "顾客评价", 5, 70),
        ],
        "overallCompleteness": 78,
    }

    def _fake_module_response(mid, stat, w):
        fallback = {
            "id": mid, "name": mid, "recordCount": 0, "coverage": 0,
            "windowDays": w, "hasData": False, "lastUpdated": None,
            "missingHints": [],
        }
        return next(
            (m for m in fake_completeness["modules"] if m["id"] == mid),
            fallback,
        )

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=fake_pool,
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock,
        return_value=AsyncMock(),
    ), patch(
        "smartbi.api.restaurant_completeness._factory_age_days",
        new_callable=AsyncMock, return_value=365,
    ), patch(
        "smartbi.api.restaurant_completeness._fetch_module_stats",
        new_callable=AsyncMock, return_value={},
    ), patch(
        "smartbi.api.restaurant_completeness._build_module_response",
        side_effect=_fake_module_response,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/llm-composite",
            params={"factory_id": "RES_3101_009", "month": "2026-04"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()

    # ── Whitelist enforcement (strict) ─────────────────────────────────────
    assert set(body.keys()) == set(WHITELIST), \
        f"Composite endpoint leaked keys: {set(body.keys()) - set(WHITELIST)}"
    # Defensive: no metadata-style keys
    body_str = str(body)
    assert "_toolCount" not in body_str
    assert "_trace" not in body_str
    assert "_internalId" not in body_str

    # ── 5 fields each present ──────────────────────────────────────────────
    assert isinstance(body["summary"], str)
    assert isinstance(body["topItems"], list)
    assert isinstance(body["recommendations"], list)
    assert isinstance(body["evidence"], dict)
    assert isinstance(body["dataAvailable"], dict)

    # summary includes month + factory + AI-overview marker
    assert "RES_3101_009" in body["summary"]
    assert "2026-04" in body["summary"]

    # evidence carries window + source
    assert body["evidence"]["dataWindow"]["requested"] == "30d"
    assert "source" in body["evidence"]

    # dataAvailable breakdown present
    da = body["dataAvailable"]
    assert "overall" in da
    assert "posData" in da
    assert "wastageData" in da
    assert "consumptionData" in da


def test_composite_path_b_fallback_when_30d_empty():
    """Verifies Path B: requested 30d returns 0 rows → endpoint widens to 365d.

    Tests this by making the fake `conn.fetch` return [] on first call and a
    populated row list on the second call, then asserting:
    - evidence.dataWindow.fallback is True
    - actual == 365d
    - summary mentions "近 30 天无销售数据"
    """
    app = _make_test_app()
    client = TestClient(app, raise_server_exceptions=False)

    populated_rows = [
        _FakeRow({
            "ingredient_id": "ing-7", "name": "白菜", "category": "蔬菜",
            "unit": "kg", "total_value": 1200,
        }),
    ]

    # Mock conn.fetch to return [] first, then populated_rows for the 365d retry
    call_count = {"n": 0}

    async def fetch_with_path_b(*args, **kwargs):
        call_count["n"] += 1
        if call_count["n"] == 1:
            return []  # 30d empty triggers Path B
        return populated_rows

    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(side_effect=fetch_with_path_b)
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    fake_pool = _make_fake_pool(cm)

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=fake_pool,
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=None,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/llm-composite",
            params={"factory_id": "RES_3101_009", "days": 30},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    # ── Whitelist still enforced under fallback path ──────────────────────
    assert set(body.keys()) == set(WHITELIST)

    # ── Path B fingerprints ────────────────────────────────────────────────
    ev = body["evidence"]
    assert ev["dataWindow"]["fallback"] is True
    assert ev["dataWindow"]["requested"] == "30d"
    assert ev["dataWindow"]["actual"] == "365d"
    assert ev["dataWindow"]["fallbackReason"] is not None

    # summary explicitly tells the LLM about the fallback
    assert "近 30 天无销售数据" in body["summary"]
    assert "365" in body["summary"]

    # Confirm the fallback actually happened (fetch called twice: 30d then 365d)
    assert call_count["n"] == 2


def test_composite_admin_can_query_any_factory():
    """factory_super_admin / platform_admin / internal / admin can query cross-factory."""
    app = _make_test_app(role="platform_admin", factory_id="F001")
    client = TestClient(app, raise_server_exceptions=False)

    fake_rows = [
        _FakeRow({
            "ingredient_id": "ing-1", "name": "rice", "category": "main",
            "unit": "kg", "total_value": 100,
        }),
    ]
    cm, _ = _make_fake_conn(fake_rows)
    fake_pool = _make_fake_pool(cm)

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=fake_pool,
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=None,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/llm-composite",
            params={"factory_id": "RES_3101_009"},  # different from JWT factory
        )

    assert resp.status_code == 200, resp.text


def test_composite_graceful_when_top_items_fetch_raises():
    """Underlying fetcher exception must NOT 500 the endpoint — degrade to empty
    topItems + dataAvailable.overall=False with a useful summary.
    """
    app = _make_test_app()
    client = TestClient(app, raise_server_exceptions=False)

    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(side_effect=RuntimeError("db down"))
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    fake_pool = _make_fake_pool(cm)

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=fake_pool,
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=None,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/llm-composite",
            params={"factory_id": "RES_3101_009"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body.keys()) == set(WHITELIST)
    assert body["dataAvailable"]["overall"] is False
    assert body["topItems"] == []
