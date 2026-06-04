"""Router-level tests for Wave2 price-anomaly gold API.

Mounts the APIRouter on a minimal FastAPI app, injects request.state.factory_id +
role via middleware, monkeypatches the gold functions + get_pg_pool. Validates
envelope shape, factory guard, RBAC strip on detect (money fields), reason-code
validation surfaced as success=false, and ack body→param mapping.

Run:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_price_anomaly_router.py -v
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.price_anomaly as pa


def _make_app(factory_id="F006", role="factory_super_admin", username="zhangquan"):
    app = FastAPI()

    @app.middleware("http")
    async def _inject_state(request: Request, call_next):
        request.state.factory_id = factory_id
        request.state.role = role
        request.state.username = username
        return await call_next(request)

    app.include_router(pa.router, prefix="/api/smartbi")
    return app


class _FakePool:
    def acquire(self):
        raise AssertionError("pool.acquire should not be called when gold is patched")


@pytest.fixture
def patch_pool(monkeypatch):
    async def fake_get_pg_pool():
        return _FakePool()
    import smartbi.config
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)
    yield


_SAMPLE_ANOMALY = {
    "normalizedName": "洗洁精", "ingredientName": "洗洁精",
    "supplierId": "sup-1", "supplierName": "鑫农", "unit": "瓶",
    "anomalyDeliveryDate": "2026-06-03", "oldPrice": 110.0,
    "newPrice": 150.0, "trailingAvg": 110.0, "deltaPct": 36.36,
    "direction": "UP", "consecutiveAnomalyCount": 1, "riskLevel": "MEDIUM",
}


def test_detect_returns_anomalies(patch_pool, monkeypatch):
    async def fake_detect(pool, factory_id, trailing_n=3, epsilon_pct=5.0):
        assert factory_id == "F006"
        assert trailing_n == 3
        assert epsilon_pct == 5.0
        return [dict(_SAMPLE_ANOMALY)]

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "detect_price_anomalies", fake_detect)

    client = TestClient(_make_app())
    resp = client.get("/api/smartbi/gold/price-anomaly/detect")
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is True
    assert len(j["data"]) == 1
    assert j["data"][0]["newPrice"] == 150.0
    assert j["data"][0]["riskLevel"] == "MEDIUM"


def test_detect_passes_through_params(patch_pool, monkeypatch):
    captured = {}

    async def fake_detect(pool, factory_id, trailing_n=3, epsilon_pct=5.0):
        captured["trailing_n"] = trailing_n
        captured["epsilon_pct"] = epsilon_pct
        return []

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "detect_price_anomalies", fake_detect)

    client = TestClient(_make_app())
    resp = client.get("/api/smartbi/gold/price-anomaly/detect?trailing_n=5&epsilon_pct=8.5")
    assert resp.json()["success"] is True
    assert captured["trailing_n"] == 5
    assert captured["epsilon_pct"] == 8.5


def test_detect_strips_money_for_non_price_role(patch_pool, monkeypatch):
    async def fake_detect(pool, factory_id, trailing_n=3, epsilon_pct=5.0):
        return [dict(_SAMPLE_ANOMALY)]

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "detect_price_anomalies", fake_detect)

    # waiter NOT in PRICE_VIEW_ROLES → price fields nulled
    client = TestClient(_make_app(role="restaurant_waiter"))
    resp = client.get("/api/smartbi/gold/price-anomaly/detect")
    j = resp.json()
    a = j["data"][0]
    assert a["oldPrice"] is None       # contains 'price' → stripped
    assert a["newPrice"] is None
    assert a["trailingAvg"] is None    # gold-extra money key
    # Non-money fields survive so the deterrence signal still shows.
    assert a["riskLevel"] == "MEDIUM"
    assert a["direction"] == "UP"
    assert a["supplierName"] == "鑫农"
    assert a["deltaPct"] == 36.36      # rate, not absolute amount → visible


def test_detect_visible_for_price_role(patch_pool, monkeypatch):
    async def fake_detect(pool, factory_id, trailing_n=3, epsilon_pct=5.0):
        return [dict(_SAMPLE_ANOMALY)]

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "detect_price_anomalies", fake_detect)

    client = TestClient(_make_app(role="procurement_manager"))
    resp = client.get("/api/smartbi/gold/price-anomaly/detect")
    assert resp.json()["data"][0]["newPrice"] == 150.0


def test_ack_maps_body_and_returns_id(patch_pool, monkeypatch):
    captured = {}

    async def fake_ack(pool, factory_id, **kwargs):
        captured["factory_id"] = factory_id
        captured.update(kwargs)
        return 99

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "record_anomaly_ack", fake_ack)

    client = TestClient(_make_app())
    resp = client.post(
        "/api/smartbi/gold/price-anomaly/ack",
        json={
            "normalizedName": "洗洁精", "ingredientName": "洗洁精",
            "supplierId": "sup-1", "supplierName": "鑫农",
            "anomalyDeliveryDate": "2026-06-03",
            "oldPrice": 110.0, "newPrice": 150.0, "deltaPct": 36.36,
            "reasonCode": "SEASONAL", "reasonNote": None,
        },
    )
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is True
    assert j["data"]["id"] == 99
    assert captured["factory_id"] == "F006"
    assert captured["normalized_name"] == "洗洁精"
    assert captured["reason_code"] == "SEASONAL"
    # acknowledged_by defaults to the authenticated username
    assert captured["acknowledged_by"] == "zhangquan"
    assert str(captured["anomaly_delivery_date"]) == "2026-06-03"


def test_ack_other_without_note_surfaces_validation_error(patch_pool, monkeypatch):
    # Use the REAL record_anomaly_ack to validate the ValueError → success=false path,
    # but stub the pool acquire so we never hit DB (it raises before DB).
    client = TestClient(_make_app())
    resp = client.post(
        "/api/smartbi/gold/price-anomaly/ack",
        json={
            "normalizedName": "洗洁精", "ingredientName": "洗洁精",
            "supplierId": "sup-1", "supplierName": "鑫农",
            "anomalyDeliveryDate": "2026-06-03",
            "oldPrice": 110.0, "newPrice": 150.0, "deltaPct": 36.36,
            "reasonCode": "OTHER", "reasonNote": "",
        },
    )
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is False
    assert "reason_note" in j["message"] or "OTHER" in j["message"]


def test_ack_rejects_factory_mismatch(patch_pool, monkeypatch):
    async def fake_ack(pool, factory_id, **kwargs):
        raise AssertionError("must not ack on mismatch")

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "record_anomaly_ack", fake_ack)

    client = TestClient(_make_app(factory_id="F006"))
    resp = client.post(
        "/api/smartbi/gold/price-anomaly/ack",
        json={
            "factoryId": "F999",
            "normalizedName": "洗洁精", "anomalyDeliveryDate": "2026-06-03",
            "newPrice": 150.0, "reasonCode": "SEASONAL",
        },
    )
    assert resp.json()["success"] is False
    assert "mismatch" in resp.json()["message"]


def test_acks_list_strips_money_for_non_price_role(patch_pool, monkeypatch):
    async def fake_list(pool, factory_id, normalized_name=None):
        return [{
            "id": 1, "normalizedName": "洗洁精", "ingredientName": "洗洁精",
            "supplierId": "sup-1", "supplierName": "鑫农",
            "anomalyDeliveryDate": "2026-06-03", "oldPrice": 110.0,
            "newPrice": 150.0, "deltaPct": 36.36, "reasonCode": "SEASONAL",
            "reasonNote": None, "acknowledgedBy": "zhangquan", "createdAt": None,
        }]

    import smartbi.gold.price_anomaly as mod
    monkeypatch.setattr(mod, "list_anomaly_acks", fake_list)

    client = TestClient(_make_app(role="restaurant_waiter"))
    resp = client.get("/api/smartbi/gold/price-anomaly/acks")
    j = resp.json()
    assert j["success"] is True
    a = j["data"][0]
    assert a["oldPrice"] is None
    assert a["newPrice"] is None
    assert a["reasonCode"] == "SEASONAL"  # non-money preserved


def test_missing_factory_context_returns_error(monkeypatch):
    app = FastAPI()

    @app.middleware("http")
    async def _no_state(request, call_next):
        request.state.factory_id = None
        request.state.role = None
        request.state.username = None
        return await call_next(request)

    app.include_router(pa.router, prefix="/api/smartbi")
    client = TestClient(app)
    resp = client.get("/api/smartbi/gold/price-anomaly/detect")
    assert resp.json()["success"] is False
    assert "factory" in resp.json()["message"]
