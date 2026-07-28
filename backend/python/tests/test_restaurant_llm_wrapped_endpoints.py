"""Sprint 11 MealClaw Response — tests for `?format=llm` on the 3 existing endpoints.

Endpoints under test:
- POST /api/smartbi/restaurant-ops/etl?format=llm
- GET  /api/smartbi/restaurant-ops/top-ingredients?format=llm
- GET  /api/smartbi/restaurant/completeness?format=llm

These tests ensure:
1. backward compatibility — without ?format=llm, original response shape is preserved
2. whitelist output when ?format=llm — only 5 fields
3. Path B fallback wiring on /top-ingredients?format=llm (30d empty → 365d)
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from smartbi.services.restaurant_llm_formatter import WHITELIST


def _make_app_with_router(
    router,
    role: str = "factory_super_admin",
    factory_id: str = "RES_3101_009",
    prefix: str = "/api/smartbi",
):
    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix=prefix)
    return app


class _FakeRow(dict):
    pass


# ── /top-ingredients backward-compat ─────────────────────────────────────────

def test_top_ingredients_default_format_unchanged():
    """Without ?format=llm, original shape MUST be {success, data: [...]} — not wrapped."""
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    fake_rows = [
        _FakeRow({
            "ingredient_id": "ing-1", "name": "猪肉", "category": "肉类",
            "unit": "kg", "total_value": 5000,
        }),
    ]
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(return_value=fake_rows)
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    pool = AsyncMock()
    pool.acquire = lambda: cm

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=pool,
    ):
        resp = client.get(
            "/api/smartbi/restaurant-ops/top-ingredients",
            params={"kpi_kind": "requisition_cost", "days": 30},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Original shape preserved
    assert "success" in body
    assert "data" in body
    assert isinstance(body["data"], list)
    assert body["data"][0]["name"] == "猪肉"


def test_top_ingredients_llm_format_returns_whitelist():
    """With ?format=llm, response is the 5-field whitelist."""
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    fake_rows = [
        _FakeRow({
            "ingredient_id": "ing-1", "name": "猪肉", "category": "肉类",
            "unit": "kg", "total_value": 5000,
        }),
    ]
    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(return_value=fake_rows)
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    pool = AsyncMock()
    pool.acquire = lambda: cm

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=pool,
    ):
        resp = client.get(
            "/api/smartbi/restaurant-ops/top-ingredients",
            params={"kpi_kind": "requisition_cost", "days": 30, "format": "llm"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Strict whitelist
    assert set(body.keys()) == set(WHITELIST)
    # topItems populated from the fake row
    assert len(body["topItems"]) == 1
    assert body["topItems"][0]["name"] == "猪肉"
    # No fallback because rows present
    assert body["evidence"]["dataWindow"]["fallback"] is False


def test_top_ingredients_llm_format_triggers_path_b_when_30d_empty():
    """Path B: empty 30d window → endpoint widens to 365d on its own."""
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    call_count = {"n": 0}
    populated = [
        _FakeRow({
            "ingredient_id": "ing-7", "name": "白菜",
            "category": "蔬菜", "unit": "kg", "total_value": 1200,
        }),
    ]

    async def fetch_side_effect(*args, **kwargs):
        call_count["n"] += 1
        if call_count["n"] == 1:
            return []  # 30d empty
        return populated

    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(side_effect=fetch_side_effect)
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    pool = AsyncMock()
    pool.acquire = lambda: cm

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=pool,
    ):
        resp = client.get(
            "/api/smartbi/restaurant-ops/top-ingredients",
            params={"kpi_kind": "requisition_cost", "days": 30, "format": "llm"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body.keys()) == set(WHITELIST)
    # Fallback fingerprint
    assert body["evidence"]["dataWindow"]["fallback"] is True
    assert body["evidence"]["dataWindow"]["actual"] == "365d"
    assert "近 30 天无销售数据" in body["summary"]
    # Underlying query was retried
    assert call_count["n"] == 2


def test_top_ingredients_default_format_does_not_trigger_path_b():
    """Without ?format=llm, an empty result MUST NOT silently widen the window.

    Dashboards depend on getting exactly the window they asked for.
    """
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    conn = AsyncMock()
    conn.execute = AsyncMock(return_value=None)
    conn.fetch = AsyncMock(return_value=[])  # always empty
    cm = AsyncMock()
    cm.__aenter__ = AsyncMock(return_value=conn)
    cm.__aexit__ = AsyncMock(return_value=None)
    pool = AsyncMock()
    pool.acquire = lambda: cm

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=pool,
    ):
        resp = client.get(
            "/api/smartbi/restaurant-ops/top-ingredients",
            params={"kpi_kind": "requisition_cost", "days": 30},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body == {"success": True, "data": []}
    # Only one fetch call — no Path B retry
    assert conn.fetch.await_count == 1


# ── /etl backward-compat + LLM mode ──────────────────────────────────────────

def test_etl_default_format_unchanged():
    """Without ?format=llm, original {success, data: {...upserted counts}} shape kept."""
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    # The /etl handler does a *lot* of imports inside — easiest mock is to patch
    # ``run_full_etl`` to return a stats-like object.
    fake_stats = MagicMock(
        dim_ingredient_upserted=53,
        fact_requisition_upserted=10,
        fact_wastage_upserted=5,
        fact_recipe_upserted=20,
        fact_stocktaking_upserted=2,
        agg_daily_ops_upserted=100,
        agg_daily_totals_upserted=30,
        agg_product_cost_upserted=15,
        errors=[],
    )
    fake_pool = AsyncMock()
    fake_pool.close = AsyncMock(return_value=None)

    with patch(
        "smartbi.gold.restaurant.restaurant_ops_etl.run_full_etl",
        new_callable=AsyncMock, return_value=fake_stats,
    ), patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "asyncpg.create_pool", new_callable=AsyncMock, return_value=fake_pool,
    ):
        resp = client.post("/api/smartbi/restaurant-ops/etl")

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["success"] is True
    assert body["data"]["dim_ingredient_upserted"] == 53


def test_etl_llm_format_returns_whitelist():
    from smartbi.api.restaurant_ops_gold import router
    app = _make_app_with_router(router)
    client = TestClient(app, raise_server_exceptions=False)

    fake_stats = MagicMock(
        dim_ingredient_upserted=53,
        fact_requisition_upserted=10,
        fact_wastage_upserted=5,
        fact_recipe_upserted=20,
        fact_stocktaking_upserted=2,
        agg_daily_ops_upserted=100,
        agg_daily_totals_upserted=30,
        agg_product_cost_upserted=15,
        errors=[],
    )
    fake_pool = AsyncMock()
    fake_pool.close = AsyncMock(return_value=None)

    with patch(
        "smartbi.gold.restaurant.restaurant_ops_etl.run_full_etl",
        new_callable=AsyncMock, return_value=fake_stats,
    ), patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "asyncpg.create_pool", new_callable=AsyncMock, return_value=fake_pool,
    ):
        resp = client.post("/api/smartbi/restaurant-ops/etl", params={"format": "llm"})

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert set(body.keys()) == set(WHITELIST)
    # topItems should include the largest upsert (agg_daily_ops_upserted=100)
    names = [it["name"] for it in body["topItems"]]
    assert any("agg daily ops" in n for n in names)


# ── /completeness backward-compat + LLM mode ─────────────────────────────────

def test_completeness_default_format_unchanged():
    """Without ?format=llm, /completeness returns its original 6-module body."""
    from smartbi.api.restaurant_completeness import (
        router, _EXPECTED_MODULE_IDS, _cache,
    )
    _cache.clear()  # avoid pollution from sibling tests
    app = _make_app_with_router(
        router, role="factory_super_admin", factory_id="F001",
        prefix="/api/smartbi/restaurant",
    )
    client = TestClient(app, raise_server_exceptions=False)

    fake_stats = {
        mid: {"count": 5, "last_updated": "2026-01-01T00:00:00+00:00"}
        for mid in _EXPECTED_MODULE_IDS
    }

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.api.restaurant_completeness._fetch_module_stats",
        new_callable=AsyncMock, return_value=fake_stats,
    ), patch(
        "smartbi.api.restaurant_completeness._factory_age_days",
        new_callable=AsyncMock, return_value=30,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/completeness", params={"factoryId": "F001"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Original shape
    assert "modules" in body
    assert len(body["modules"]) == 6
    assert "overallCompleteness" in body


def test_completeness_llm_format_returns_whitelist():
    from smartbi.api.restaurant_completeness import (
        router, _EXPECTED_MODULE_IDS, _cache,
    )
    _cache.clear()
    app = _make_app_with_router(
        router, role="factory_super_admin", factory_id="F002",
        prefix="/api/smartbi/restaurant",
    )
    client = TestClient(app, raise_server_exceptions=False)

    fake_stats = {
        mid: {"count": 5, "last_updated": "2026-01-01T00:00:00+00:00"}
        for mid in _EXPECTED_MODULE_IDS
    }

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.api.restaurant_completeness._fetch_module_stats",
        new_callable=AsyncMock, return_value=fake_stats,
    ), patch(
        "smartbi.api.restaurant_completeness._factory_age_days",
        new_callable=AsyncMock, return_value=30,
    ):
        resp = client.get(
            "/api/smartbi/restaurant/completeness",
            params={"factoryId": "F002", "format": "llm"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Strict whitelist
    assert set(body.keys()) == set(WHITELIST)
    # dataAvailable picks up that requisition+wastage have data (count=5 each)
    assert body["dataAvailable"]["consumptionData"] is True
    assert body["dataAvailable"]["wastageData"] is True


def test_completeness_llm_format_respects_cache():
    """LLM-wrap is applied AFTER cache hit, so cache invalidation is unaffected."""
    from smartbi.api.restaurant_completeness import (
        router, _EXPECTED_MODULE_IDS, _cache,
    )
    _cache.clear()
    app = _make_app_with_router(
        router, role="factory_super_admin", factory_id="F003",
        prefix="/api/smartbi/restaurant",
    )
    client = TestClient(app, raise_server_exceptions=False)

    fake_stats = {
        mid: {"count": 5, "last_updated": "2026-01-01T00:00:00+00:00"}
        for mid in _EXPECTED_MODULE_IDS
    }
    fetcher = AsyncMock(return_value=fake_stats)

    with patch(
        "smartbi.config.get_pg_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.config.get_cretas_pool", new_callable=AsyncMock, return_value=AsyncMock(),
    ), patch(
        "smartbi.api.restaurant_completeness._fetch_module_stats", fetcher,
    ), patch(
        "smartbi.api.restaurant_completeness._factory_age_days",
        new_callable=AsyncMock, return_value=30,
    ):
        # First call — default mode
        r1 = client.get(
            "/api/smartbi/restaurant/completeness", params={"factoryId": "F003"},
        )
        # Second call — llm mode, should hit cache (no extra _fetch_module_stats call)
        r2 = client.get(
            "/api/smartbi/restaurant/completeness",
            params={"factoryId": "F003", "format": "llm"},
        )

    assert r1.status_code == 200
    assert r2.status_code == 200
    # Cache hit: fetcher invoked exactly once across both requests
    assert fetcher.await_count == 1
    # r2 is whitelisted
    assert set(r2.json().keys()) == set(WHITELIST)
