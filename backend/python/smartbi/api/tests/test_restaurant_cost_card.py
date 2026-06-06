"""#57 — Endpoint tests for GET /restaurant/dish-cost-card/{product_id}.

Uses FastAPI TestClient with a fake auth middleware (sets request.state.factory_id
and request.state.role) + a fake asyncpg pool monkeypatched onto
smartbi.config.get_pg_pool, so no real DB is touched.

Covers: missing factory (success False), pool unavailable, no cached row
(success True + data None, NOT error), happy path, staleness flag, and RBAC
strip (non-price role → foodCost null; price role → foodCost preserved).
"""
from __future__ import annotations

import datetime as dt
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.restaurant_cost_card as cc


def make_client(role: str | None = None):
    app = FastAPI()

    @app.middleware("http")
    async def _fake_auth(request: Request, call_next):
        request.state.factory_id = request.headers.get("x-test-factory")
        request.state.role = request.headers.get("x-test-role")
        return await call_next(request)

    app.include_router(cc.router, prefix="/api/smartbi")
    return TestClient(app)


class _FakeConn:
    def __init__(self, row):
        self._row = row
        self.set_config_called_with = None

    async def execute(self, sql, *args):
        if "set_config" in sql:
            self.set_config_called_with = args[0] if args else None
        return None

    async def fetchrow(self, sql, *args):
        return self._row


class _FakePool:
    def __init__(self, row):
        self._conn = _FakeConn(row)

    @asynccontextmanager
    async def acquire(self):
        yield self._conn


def _patch_pool(monkeypatch, pool):
    import smartbi.config as cfg

    async def _get():
        return pool

    monkeypatch.setattr(cfg, "get_pg_pool", _get)


def _row(food_cost=12.94, ingredient_count=2, has_price_data=True,
         computed_at=None, last_recipe_updated_at=None):
    computed_at = computed_at or dt.datetime(2026, 6, 1, 10, 0, 0)
    return {
        "product_source_pk": "dish-1",
        "food_cost": food_cost,
        "ingredient_count": ingredient_count,
        "has_price_data": has_price_data,
        "computed_at": computed_at,
        "last_recipe_updated_at": last_recipe_updated_at,
    }


def test_missing_factory_returns_success_false():
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1")  # no x-test-factory
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is False
    assert "factory" in body["message"].lower()


def test_pool_unavailable(monkeypatch):
    import smartbi.config as cfg

    async def _no_pool():
        return None

    monkeypatch.setattr(cfg, "get_pg_pool", _no_pool)
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1",
                      headers={"x-test-factory": "RES_3101_009"})
    body = resp.json()
    assert body["success"] is False
    assert "pool" in body["message"].lower()


def test_no_cached_row_returns_success_true_data_none(monkeypatch):
    _patch_pool(monkeypatch, _FakePool(None))
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1",
                      headers={"x-test-factory": "RES_3101_009"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["success"] is True   # NOT an error
    assert body["data"] is None
    assert "暂无" in body["message"]


def test_happy_path_price_role(monkeypatch):
    _patch_pool(monkeypatch, _FakePool(_row()))
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1",
                      headers={"x-test-factory": "RES_3101_009",
                               "x-test-role": "factory_super_admin"})
    body = resp.json()
    assert body["success"] is True
    data = body["data"]
    assert data["productSourcePk"] == "dish-1"
    assert data["foodCost"] == 12.94            # price role → preserved
    assert data["ingredientCount"] == 2
    assert data["dataComplete"] is True
    assert data["stale"] is False


def test_staleness_flag(monkeypatch):
    # recipe updated AFTER cache computed → stale True
    row = _row(
        computed_at=dt.datetime(2026, 6, 1, 10, 0, 0),
        last_recipe_updated_at=dt.datetime(2026, 6, 2, 9, 0, 0),
    )
    _patch_pool(monkeypatch, _FakePool(row))
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1",
                      headers={"x-test-factory": "RES_3101_009",
                               "x-test-role": "factory_super_admin"})
    assert resp.json()["data"]["stale"] is True


def test_rbac_non_price_role_strips_foodcost(monkeypatch):
    _patch_pool(monkeypatch, _FakePool(_row()))
    client = make_client()
    resp = client.get("/api/smartbi/restaurant/dish-cost-card/dish-1",
                      headers={"x-test-factory": "RES_3101_009",
                               "x-test-role": "warehouse_manager"})
    data = resp.json()["data"]
    assert data["foodCost"] is None             # non-price role → stripped
    # non-money fields survive
    assert data["ingredientCount"] == 2
    assert data["dataComplete"] is True
