from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient


def _make_app(role: str = "factory_super_admin", factory_id: str = "F001") -> FastAPI:
    from smartbi.api.restaurant_analytics import router

    app = FastAPI()

    @app.middleware("http")
    async def inject_state(request: Request, call_next):
        request.state.role = role
        request.state.factory_id = factory_id
        request.state.auth_method = "jwt"
        return await call_next(request)

    app.include_router(router, prefix="/api/smartbi")
    return app


def test_restaurant_analytics_rejects_cross_factory_query_param(monkeypatch):
    from smartbi.api import restaurant_analytics as ra

    monkeypatch.setattr(ra, "is_postgres_enabled", lambda: True)

    def should_not_open_db():
        raise AssertionError("cross-tenant request should be rejected before DB access")

    monkeypatch.setattr(ra, "get_db_context", should_not_open_db)

    client = TestClient(_make_app(factory_id="F001"), raise_server_exceptions=False)
    resp = client.get("/api/smartbi/restaurant-sku-forms?factory_id=F002")

    assert resp.status_code == 403


def test_restaurant_analytics_allows_own_factory_query_param(monkeypatch):
    from smartbi.api import restaurant_analytics as ra

    monkeypatch.setattr(ra, "is_postgres_enabled", lambda: True)

    class _Manager:
        def __init__(self, db_session):
            self.db_session = db_session

        def list_all(self, factory_id):
            assert factory_id == "F001"
            return []

        def count_by_category(self, factory_id):
            assert factory_id == "F001"
            return {}

    class _Ctx:
        def __enter__(self):
            return object()

        def __exit__(self, exc_type, exc, tb):
            return False

    monkeypatch.setattr(ra, "get_db_context", lambda: _Ctx())
    monkeypatch.setattr(
        "services.restaurant.sku_form_manager.SkuFormManager",
        _Manager,
    )

    client = TestClient(_make_app(factory_id="F001"), raise_server_exceptions=False)
    resp = client.get("/api/smartbi/restaurant-sku-forms?factory_id=F001")

    assert resp.status_code == 200
    assert resp.json()["success"] is True
