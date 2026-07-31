"""Phase 2B-3 endpoint backfill — ``/data-date-range`` (chat-2B-dashboard).

Covers ``backend/python/smartbi_compat/api/dashboard.py``:

* ``_infer_granularity`` — 5-bucket day-span mapping (DAY / WEEK / MONTH /
  QUARTER / YEAR) mirrored from Java ``DateRange.inferGranularity``.
* ``_query_date_range`` — postgres-disabled short-circuit + None-row path.
* ``GET /api/mobile/{factory_id}/smart-bi/data-date-range`` — JWT auth
  boundary (401 / 403), has-data / no-data envelope shape, granularity
  pass-through, ``wrap_response`` integration.

Mirrors ``test_config_thresholds_pilot.py`` auth pattern (gold standard).
Per audit doc ``docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md``
§2.4 row "dashboard.py" — endpoint had zero direct tests pre-backfill.
"""
from __future__ import annotations

import os
from datetime import date

import jwt as _pyjwt
import pytest

os.environ.setdefault("JWT_SECRET", "phase-2b-3-dashboard-test-secret")

from fastapi import FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from smartbi_compat.api import dashboard as dm  # noqa: E402

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
pytestmark = [pytest.mark.api_endpoint("dashboard_data_date_range")]


_JWT_SECRET_FOR_TESTS = "phase-2b-3-dashboard-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force JWT_SECRET to this file's value for the duration of each test.

    见 test_config_thresholds_pilot.py 同名 fixture 的说明: 顶部 setdefault 只在
    本文件先跑时生效, 否则签名/验签用的是两个不同的 secret → 全部 401。
    2026-07-31 python 门禁首次真跑全量时暴露, 此前被 `pytest ... || true` 吞掉。
    """
    monkeypatch.setenv("JWT_SECRET", _JWT_SECRET_FOR_TESTS)
    yield
_JWT_ALGORITHM = "HS256"


def _make_token(
    *,
    user_id: int = 22,
    username: str = "alice",
    factory_id: str | None = "F001",
    role: str = "factory_super_admin",
    exp_offset: int = 3600,
) -> str:
    from time import time

    payload: dict = {
        "userId": user_id,
        "username": username,
        "role": role,
        "exp": int(time()) + exp_offset,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return _pyjwt.encode(payload, _JWT_SECRET_FOR_TESTS, algorithm=_JWT_ALGORITHM)


def _auth_header(token: str | None = None, **token_kwargs) -> dict:
    if token is None:
        token = _make_token(**token_kwargs)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client(monkeypatch):
    """TestClient with ``_query_date_range`` mocked to a happy path return.

    Individual tests override the mock as needed via
    ``monkeypatch.setattr(dm, "_query_date_range", ...)``.
    """

    def fake_query(factory_id):
        return (date(2026, 1, 1), date(2026, 5, 31))

    monkeypatch.setattr(dm, "_query_date_range", fake_query)

    app = FastAPI()
    app.include_router(dm.router)
    return TestClient(app)


# ============================================================
# _infer_granularity — day-span boundary tests
# ============================================================


def test_infer_granularity_single_day():
    """end == start → 1-day span → DAY."""
    assert dm._infer_granularity(date(2026, 5, 1), date(2026, 5, 1)) == "DAY"


def test_infer_granularity_week_boundary():
    """7-day span (inclusive) → WEEK; 8-day → MONTH."""
    assert dm._infer_granularity(date(2026, 5, 1), date(2026, 5, 7)) == "WEEK"
    assert dm._infer_granularity(date(2026, 5, 1), date(2026, 5, 8)) == "MONTH"


def test_infer_granularity_month_boundary():
    """31-day span → MONTH; 32-day → QUARTER."""
    assert dm._infer_granularity(date(2026, 5, 1), date(2026, 5, 31)) == "MONTH"
    assert dm._infer_granularity(date(2026, 5, 1), date(2026, 6, 1)) == "QUARTER"


def test_infer_granularity_quarter_boundary():
    """93-day span → QUARTER; 94-day → YEAR."""
    assert dm._infer_granularity(date(2026, 1, 1), date(2026, 4, 3)) == "QUARTER"
    assert dm._infer_granularity(date(2026, 1, 1), date(2026, 4, 4)) == "YEAR"


def test_infer_granularity_full_year():
    """A full calendar year falls in YEAR bucket."""
    assert dm._infer_granularity(date(2026, 1, 1), date(2026, 12, 31)) == "YEAR"


# ============================================================
# _query_date_range — postgres-disabled short-circuit
# ============================================================


def test_query_date_range_returns_none_when_postgres_disabled(monkeypatch, caplog):
    """When ``is_postgres_enabled()`` is False the helper logs a warning
    and short-circuits to None — protects unit tests from needing PG.
    """
    import smartbi.database.connection as _conn

    monkeypatch.setattr(_conn, "is_postgres_enabled", lambda: False)
    import logging
    with caplog.at_level(logging.WARNING, logger=dm.logger.name):
        result = dm._query_date_range("F001")
    assert result is None
    assert any("postgres not enabled" in rec.message for rec in caplog.records)


# ============================================================
# Endpoint — happy path
# ============================================================


def test_data_date_range_happy_path_returns_has_data_envelope(client):
    """Valid JWT + fixture returns (2026-01-01, 2026-05-31) →
    ``hasData=true`` + granularity inferred from span."""
    r = client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    data = body["data"]
    assert data["hasData"] is True
    assert data["startDate"] == "2026-01-01"
    assert data["endDate"] == "2026-05-31"
    # Jan 1 → May 31 = 151 days → YEAR bucket.
    assert data["granularity"] == "YEAR"
    assert "数据范围 2026-01-01 至 2026-05-31" in data["description"]


def test_data_date_range_granularity_pass_through_for_short_span(monkeypatch):
    """Re-stub with a 5-day span → granularity should be WEEK."""

    def fake_short(factory_id):
        return (date(2026, 5, 10), date(2026, 5, 14))

    monkeypatch.setattr(dm, "_query_date_range", fake_short)

    app = FastAPI()
    app.include_router(dm.router)
    short_client = TestClient(app)

    r = short_client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    assert r.json()["data"]["granularity"] == "WEEK"


# ============================================================
# Endpoint — no-data path
# ============================================================


def test_data_date_range_no_data_returns_empty_envelope(monkeypatch):
    """``_query_date_range`` returns None (factory has no rows) →
    ``hasData=false`` + diagnostic message."""

    def fake_none(factory_id):
        return None

    monkeypatch.setattr(dm, "_query_date_range", fake_none)

    app = FastAPI()
    app.include_router(dm.router)
    no_data_client = TestClient(app)

    r = no_data_client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["data"]["hasData"] is False
    assert body["data"]["message"] == "No sales data detected"


# ============================================================
# Endpoint — auth boundary
# ============================================================


def test_data_date_range_requires_jwt_returns_401(client):
    """Missing Authorization header → 401 from ``verify_jwt_and_factory``."""
    r = client.get("/api/mobile/F001/smart-bi/data-date-range")
    assert r.status_code == 401


def test_data_date_range_cross_factory_returns_403(client):
    """Token's factoryId=F001 against URL /F002/... → 403 cross-factory."""
    r = client.get(
        "/api/mobile/F002/smart-bi/data-date-range",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 403


def test_data_date_range_platform_admin_no_factoryid_succeeds(client):
    """Privileged role can call any factory URL without a factoryId claim."""
    r = client.get(
        "/api/mobile/F001/smart-bi/data-date-range",
        headers=_auth_header(factory_id=None, role="platform_admin"),
    )
    assert r.status_code == 200


# ============================================================
# Router structure
# ============================================================


def test_router_declares_single_endpoint():
    paths = {route.path for route in dm.router.routes}
    assert "/api/mobile/{factory_id}/smart-bi/data-date-range" in paths


def test_router_endpoint_is_get_only():
    target = "/api/mobile/{factory_id}/smart-bi/data-date-range"
    for route in dm.router.routes:
        if route.path == target:
            assert set(route.methods) == {"GET"}, f"{route.path} accepts non-GET"
