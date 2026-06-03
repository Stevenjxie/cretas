"""Router-level tests for G7 supplier-price gold API.

Mounts the APIRouter on a minimal FastAPI app, injects request.state.factory_id
via a tiny middleware, and monkeypatches the ETL functions + get_pg_pool so no
real DB is touched. Validates envelope shape, factory-context guard, body→row
mapping, factory-mismatch rejection, and RBAC strip on money fields.

Run:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_supplier_price_router.py -v
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.supplier_price as sp


def _make_app(factory_id="F006", role="factory_super_admin"):
    app = FastAPI()

    @app.middleware("http")
    async def _inject_state(request: Request, call_next):
        request.state.factory_id = factory_id
        request.state.role = role
        return await call_next(request)

    app.include_router(sp.router, prefix="/api/smartbi")
    return app


class _FakePool:
    def acquire(self):  # never actually used (ETL is monkeypatched)
        raise AssertionError("pool.acquire should not be called when ETL is patched")


@pytest.fixture
def patch_pool(monkeypatch):
    async def fake_get_pg_pool():
        return _FakePool()
    # get_pg_pool is imported lazily inside handlers via `from smartbi.config import get_pg_pool`
    import smartbi.config
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)
    yield


def test_batch_upsert_maps_body_and_returns_count(patch_pool, monkeypatch):
    captured = {}

    async def fake_upsert(pool, factory_id, rows):
        captured["factory_id"] = factory_id
        captured["rows"] = rows
        return len(rows)

    import smartbi.gold.supplier_price_etl as etl
    monkeypatch.setattr(etl, "upsert_supplier_price_batch", fake_upsert)

    client = TestClient(_make_app())
    resp = client.post(
        "/api/smartbi/gold/supplier-price/batch-upsert",
        json={
            "noteId": "SDN-1",
            "lines": [
                {"ingredientName": "猪肉", "unitPrice": 12.5, "quantity": 10,
                 "unit": "kg", "lineAmount": 125.0, "deliveryDate": "2026-06-01"},
            ],
        },
    )
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is True
    assert j["data"]["count"] == 1
    # factory from context, not body
    assert captured["factory_id"] == "F006"
    # camelCase → snake_case mapping + date parse + source_note_id wired
    row = captured["rows"][0]
    assert row["ingredient_name"] == "猪肉"
    assert row["unit_price"] == 12.5
    assert row["source_note_id"] == "SDN-1"
    assert str(row["delivery_date"]) == "2026-06-01"


def test_batch_upsert_rejects_factory_mismatch(patch_pool, monkeypatch):
    import smartbi.gold.supplier_price_etl as etl

    async def fake_upsert(pool, factory_id, rows):  # should not be called
        raise AssertionError("must not upsert on mismatch")
    monkeypatch.setattr(etl, "upsert_supplier_price_batch", fake_upsert)

    client = TestClient(_make_app(factory_id="F006"))
    resp = client.post(
        "/api/smartbi/gold/supplier-price/batch-upsert",
        json={"factoryId": "F999", "noteId": "x", "lines": []},
    )
    assert resp.status_code == 200
    assert resp.json()["success"] is False
    assert "mismatch" in resp.json()["message"]


def test_trend_strips_money_for_non_price_role(patch_pool, monkeypatch):
    async def fake_trend(pool, factory_id, ingredient, days=90):
        return [{"deliveryDate": "2026-06-01", "unitPrice": 12.5,
                 "supplierName": "鑫农", "quantity": 10.0}]

    import smartbi.gold.supplier_price_etl as etl
    monkeypatch.setattr(etl, "get_supplier_price_trend", fake_trend)

    # waiter role is NOT in PRICE_VIEW_ROLES → unitPrice must be nulled
    client = TestClient(_make_app(role="restaurant_waiter"))
    resp = client.get("/api/smartbi/gold/supplier-price/trend?ingredient=猪肉")
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is True
    assert j["data"][0]["unitPrice"] is None       # stripped (contains 'price')
    assert j["data"][0]["supplierName"] == "鑫农"   # non-money preserved


def test_trend_visible_for_price_role(patch_pool, monkeypatch):
    async def fake_trend(pool, factory_id, ingredient, days=90):
        return [{"deliveryDate": "2026-06-01", "unitPrice": 12.5,
                 "supplierName": "鑫农", "quantity": 10.0}]

    import smartbi.gold.supplier_price_etl as etl
    monkeypatch.setattr(etl, "get_supplier_price_trend", fake_trend)

    client = TestClient(_make_app(role="factory_super_admin"))
    resp = client.get("/api/smartbi/gold/supplier-price/trend?ingredient=猪肉")
    assert resp.json()["data"][0]["unitPrice"] == 12.5  # price-view role sees it


def test_missing_factory_context_returns_error(monkeypatch):
    # No factory in state → handler returns success=false
    app = FastAPI()

    @app.middleware("http")
    async def _no_state(request, call_next):
        request.state.factory_id = None
        request.state.role = None
        return await call_next(request)

    app.include_router(sp.router, prefix="/api/smartbi")
    client = TestClient(app)
    resp = client.get("/api/smartbi/gold/supplier-price/latest")
    assert resp.json()["success"] is False
    assert "factory" in resp.json()["message"]
