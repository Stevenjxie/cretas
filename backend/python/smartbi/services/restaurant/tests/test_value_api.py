"""#56 价值可视化回馈回路 — restaurant_value API 路由测试 (fake pool, no DB)。

覆盖:
  - GET /value-summary 命中 → {success, data:{month, annual}}。
  - GET /value-summary 未命中 → success:true, data:null, message "暂无价值快照" (正常空态)。
  - 缺 factory context → success:false。
  - RBAC: 非金额角色 (kiosk_lead) → 金额 null, count 保留。
  - POST /refresh 触发重算。
"""
from __future__ import annotations

import pytest
from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

import smartbi.api.restaurant_value as rv


def _make_app(factory_id="F-DENG", role="factory_super_admin"):
    app = FastAPI()

    @app.middleware("http")
    async def _inject_state(request: Request, call_next):
        if factory_id is not None:
            request.state.factory_id = factory_id
        request.state.role = role
        return await call_next(request)

    app.include_router(rv.router, prefix="/api/smartbi")
    return app


class _FakePool:
    def acquire(self):
        raise AssertionError("pool.acquire should not be called (service patched)")


@pytest.fixture
def patch_pool(monkeypatch):
    async def fake_get_pg_pool():
        return _FakePool()
    import smartbi.config
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)
    yield


def _summary_hit():
    return {
        "periodMonth": "2026-02",
        "storeId": None,
        "month": {"total": 50849.0, "shrinkageVariance": 12500.0,
                  "foodCostSavings": 20000.0, "discountSavings": None},
        "annual": {"total": 472688.0, "laborRigidity": 220188.0},
        "diagnosisCount": 3, "criticalCount": 1, "rxActionCount": 2,
        "signalSources": [], "confidenceNote": "预估口径", "computedAt": None,
    }


def test_value_summary_hit(patch_pool, monkeypatch):
    async def fake_get(pool, factory_id, period_month=None, store_id=None, **kw):
        return _summary_hit()
    monkeypatch.setattr(rv, "get_value_summary", fake_get)

    client = TestClient(_make_app())
    resp = client.get("/api/smartbi/restaurant-value/value-summary")
    assert resp.status_code == 200
    j = resp.json()
    assert j["success"] is True
    assert j["data"]["month"]["total"] == 50849.0
    assert j["data"]["annual"]["total"] == 472688.0


def test_value_summary_miss_empty_state(patch_pool, monkeypatch):
    async def fake_get(pool, factory_id, period_month=None, store_id=None, **kw):
        return None
    monkeypatch.setattr(rv, "get_value_summary", fake_get)

    client = TestClient(_make_app())
    resp = client.get("/api/smartbi/restaurant-value/value-summary")
    j = resp.json()
    assert j["success"] is True       # 正常空态, 不是 500
    assert j["data"] is None
    assert "暂无" in j["message"]


def test_value_summary_missing_factory(monkeypatch):
    client = TestClient(_make_app(factory_id=None))
    resp = client.get("/api/smartbi/restaurant-value/value-summary")
    j = resp.json()
    assert j["success"] is False
    assert "factory" in j["message"].lower() or "工厂" in j["message"]


def test_rbac_strips_amounts_for_non_price_role(patch_pool, monkeypatch):
    """非金额角色 (kiosk_lead) → 金额 null, count 保留。"""
    async def fake_get(pool, factory_id, period_month=None, store_id=None, **kw):
        return _summary_hit()
    monkeypatch.setattr(rv, "get_value_summary", fake_get)

    client = TestClient(_make_app(role="kiosk_lead"))
    resp = client.get("/api/smartbi/restaurant-value/value-summary")
    j = resp.json()
    assert j["success"] is True
    # 金额字段被 null
    assert j["data"]["month"]["total"] is None
    assert j["data"]["annual"]["total"] is None
    assert j["data"]["month"]["foodCostSavings"] is None
    # count 保留
    assert j["data"]["criticalCount"] == 1
    assert j["data"]["diagnosisCount"] == 3


def test_price_role_sees_amounts(patch_pool, monkeypatch):
    async def fake_get(pool, factory_id, period_month=None, store_id=None, **kw):
        return _summary_hit()
    monkeypatch.setattr(rv, "get_value_summary", fake_get)

    client = TestClient(_make_app(role="restaurant_manager"))
    resp = client.get("/api/smartbi/restaurant-value/value-summary")
    j = resp.json()
    assert j["data"]["month"]["total"] == 50849.0  # restaurant_manager in PRICE_VIEW_ROLES


def test_refresh_triggers_recompute(patch_pool, monkeypatch):
    called = {}

    async def fake_refresh(factory_id, period_month=None, store_id=None):
        called["factory_id"] = factory_id
        called["period_month"] = period_month
        return {"success": True, "message": "ok", "totalMonth": 50849.0}
    monkeypatch.setattr(rv, "_recompute_snapshot", fake_refresh)

    client = TestClient(_make_app())
    resp = client.post("/api/smartbi/restaurant-value/refresh", json={"periodMonth": "2026-02"})
    j = resp.json()
    assert j["success"] is True
    assert called["factory_id"] == "F-DENG"
    assert called["period_month"] == "2026-02"
