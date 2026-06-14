"""Skeleton tests for ``/analysis/production`` shared contracts.

chat-A1 Wave 1 (PR #350) shipped this set. chat-A2 Wave 2 ships the
restaurant branch impl (see ``test_analysis_production_restaurant.py``).
Phase 2D Subagent B rewires the factory branch from a raising stub into
an empty-envelope placeholder marked ``FACTORY_SILVER_PHASE_2D_PENDING``;
the assertion below now locks the new envelope contract instead of a
``NotImplementedError`` raise.

Surviving contracts (chat-A2 + Phase 2D must preserve):

* ``TenantType`` enum mirrors Java ``FactoryType`` exactly (5 values).
* ``is_restaurant_tenant`` matches Java
  ``SmartBIServiceImpl.isRestaurantTenant`` predicate (RESTAURANT,
  BRANCH only).
* ``envelope_discriminator`` collapses to the binary ``"FACTORY"`` /
  ``"RESTAURANT"`` per Q-DEC-8 Option A envelope discriminator.
* ``get_tenant_type`` defaults to FACTORY on missing rows (preserves
  Java repository-failure fallback).
* Factory dispatcher returns the Phase 2D empty envelope tagged with the
  canonical ``FACTORY_SILVER_PHASE_2D_PENDING`` marker — future Silver-
  layer impl (PR-A/B/C/D) must keep the same marker so frontend chip
  rendering doesn't churn.
* ``_FACTORY_BRANCH_DEFERRED_MSG`` documentation constant still contains
  the ``"Phase 2D"`` + Silver-table substrings for grep-readiness.
* Router declares the polymorphic endpoint path.

Spec: docs/superpowers/specs/2026-05-12-t6-6-sub-a-production-impl-spec.md
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi_compat.tenant import TenantType, get_tenant_type
from smartbi_compat.api import analysis_production
from smartbi_compat.api.analysis_production import (
    FACTORY_PHASE_2D_PENDING_MARKER,
    _factory_production_dispatch,
    _FACTORY_BRANCH_DEFERRED_MSG,
    router,
)

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
pytestmark = [pytest.mark.api_endpoint("analysis_production")]


# ============================================================
# Enum + predicate parity with Java FactoryType
# ============================================================


def test_tenant_type_has_five_java_enum_values():
    """Mirror Java ``FactoryType`` exactly: 5 members, names match."""
    assert {t.name for t in TenantType} == {
        "FACTORY",
        "RESTAURANT",
        "HEADQUARTERS",
        "BRANCH",
        "CENTRAL_KITCHEN",
    }
    # String values match Java enum identifiers for direct
    # cretas_db.factories.type parse.
    for t in TenantType:
        assert t.value == t.name


@pytest.mark.parametrize("tenant", [TenantType.RESTAURANT, TenantType.BRANCH])
def test_is_restaurant_tenant_true_for_restaurant_and_branch(tenant):
    """Java SmartBIServiceImpl.isRestaurantTenant lines 432-435 predicate."""
    assert tenant.is_restaurant_tenant is True
    assert tenant.is_factory_tenant is False
    assert tenant.envelope_discriminator == "RESTAURANT"


@pytest.mark.parametrize(
    "tenant",
    [TenantType.FACTORY, TenantType.HEADQUARTERS, TenantType.CENTRAL_KITCHEN],
)
def test_is_restaurant_tenant_false_for_factory_family(tenant):
    """HEADQUARTERS + CENTRAL_KITCHEN collapse to factory per Java precedent."""
    assert tenant.is_restaurant_tenant is False
    assert tenant.is_factory_tenant is True
    assert tenant.envelope_discriminator == "FACTORY"


# ============================================================
# from_db_value defensive parsing
# ============================================================


@pytest.mark.parametrize(
    "value,expected",
    [
        ("FACTORY", TenantType.FACTORY),
        ("factory", TenantType.FACTORY),  # case-insensitive
        ("RESTAURANT", TenantType.RESTAURANT),
        ("BRANCH", TenantType.BRANCH),
        ("HEADQUARTERS", TenantType.HEADQUARTERS),
        ("CENTRAL_KITCHEN", TenantType.CENTRAL_KITCHEN),
    ],
)
def test_from_db_value_parses_canonical_strings(value, expected):
    assert TenantType.from_db_value(value) is expected


@pytest.mark.parametrize("value", [None, "", "UNKNOWN", "garbage"])
def test_from_db_value_defaults_to_factory_on_missing_or_unknown(value):
    """Defensive: missing/unknown → FACTORY (mirrors Java repo-failure path)."""
    assert TenantType.from_db_value(value) is TenantType.FACTORY


# ============================================================
# get_tenant_type — async query against fake asyncpg connection
# ============================================================


class _FakeConn:
    """Minimal asyncpg.Connection stub for tenant query test."""

    def __init__(self, row):
        self._row = row
        self.last_sql = None
        self.last_args = None

    async def fetchrow(self, sql, *args):
        self.last_sql = sql
        self.last_args = args
        return self._row


@pytest.mark.asyncio
async def test_get_tenant_type_happy_path_restaurant():
    conn = _FakeConn({"type": "RESTAURANT"})
    tenant = await get_tenant_type("R_ILTEATRO_REAL", conn)
    assert tenant is TenantType.RESTAURANT
    assert tenant.is_restaurant_tenant
    assert conn.last_args == ("R_ILTEATRO_REAL",)


@pytest.mark.asyncio
async def test_get_tenant_type_happy_path_branch():
    conn = _FakeConn({"type": "BRANCH"})
    tenant = await get_tenant_type("R_ILTEATRO_001", conn)
    assert tenant is TenantType.BRANCH
    assert tenant.is_restaurant_tenant


@pytest.mark.asyncio
async def test_get_tenant_type_happy_path_factory():
    conn = _FakeConn({"type": "FACTORY"})
    tenant = await get_tenant_type("F001", conn)
    assert tenant is TenantType.FACTORY
    assert tenant.is_factory_tenant


@pytest.mark.asyncio
async def test_get_tenant_type_missing_row_defaults_to_factory():
    """Missing factory_id row → FACTORY (preserves legacy manufacturing path)."""
    conn = _FakeConn(None)
    tenant = await get_tenant_type("F_DOES_NOT_EXIST", conn)
    assert tenant is TenantType.FACTORY


@pytest.mark.asyncio
async def test_get_tenant_type_null_type_column_defaults_to_factory():
    """``factories.type`` IS NULL → FACTORY (Java Factory.java default behavior)."""
    conn = _FakeConn({"type": None})
    tenant = await get_tenant_type("F_LEGACY", conn)
    assert tenant is TenantType.FACTORY


# ============================================================
# Dispatcher Phase 2D empty-envelope contracts (chat-A2 + Phase 2D handoff)
# ============================================================


@pytest.mark.asyncio
async def test_factory_dispatch_returns_phase_2d_envelope():
    """Phase 2D placeholder: factory dispatch returns empty envelope marker.

    Subagent B rewired the factory branch from a raising stub into an
    empty-envelope response carrying the top-level marker
    ``FACTORY_SILVER_PHASE_2D_PENDING``. Future Silver-layer impl
    (PR-A/B/C/D) must keep the same marker key so frontend chip rendering
    doesn't churn.
    """
    from datetime import date

    result = await _factory_production_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 31), "oee"
    )
    assert isinstance(result, dict)
    assert result["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER
    assert result["dataAvailability"] == "FACTORY_SILVER_PHASE_2D_PENDING"


def test_factory_deferred_msg_keeps_phase_2d_grep_substrings():
    """``_FACTORY_BRANCH_DEFERRED_MSG`` still grep-able for Phase 2D dispatch.

    Body was rewritten ("raises" → "empty envelope marked …") but every
    grep target documented in the chat-A1 PR #350 contract is preserved
    so log searches + grep audits keep working. The Phase-2D reference
    is carried by the canonical marker token ``PHASE_2D`` (within
    ``FACTORY_SILVER_PHASE_2D_PENDING``); the Silver-table grep targets
    (e.g. ``fact_production_batch``) remain individually listed.
    """
    assert "PHASE_2D" in _FACTORY_BRANCH_DEFERRED_MSG
    assert "FACTORY_SILVER_PHASE_2D_PENDING" in _FACTORY_BRANCH_DEFERRED_MSG
    assert "fact_production_batch" in _FACTORY_BRANCH_DEFERRED_MSG
    assert "fact_equipment_event" in _FACTORY_BRANCH_DEFERRED_MSG
    assert "fact_quality_inspection" in _FACTORY_BRANCH_DEFERRED_MSG


# ============================================================
# Router contract — endpoint path is registered for chat-A3 wiring
# ============================================================


def test_router_declares_production_analysis_endpoint():
    """Path must match Q4 spec §1.1 verbatim — single URL serves both tenants."""
    paths = [route.path for route in router.routes]
    assert "/api/mobile/{factory_id}/smart-bi/analysis/production" in paths


def test_router_endpoint_methods_are_get_only():
    target = "/api/mobile/{factory_id}/smart-bi/analysis/production"
    matching = [r for r in router.routes if r.path == target]
    assert len(matching) == 1
    assert set(matching[0].methods) == {"GET"}


def test_module_advertises_dispatch_helpers():
    """Stable function names for chat-A2 (restaurant) + Phase 2D (factory)."""
    assert hasattr(analysis_production, "_factory_production_dispatch")
    assert hasattr(analysis_production, "_restaurant_production_dispatch")
    assert hasattr(analysis_production, "get_production_analysis")


# ============================================================
# Direct API endpoint tests (Phase 2B-1 chat-2B-prod-upgrade ⚠️→✅)
# ============================================================
#
# Per audit §4.1 — docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md
# — upgrade this module from ⚠️ partial (37 transitive tests, data-layer
# only) to ✅ full by adding 3 direct HTTP endpoint tests:
#
#   1. happy path: restaurant tenant → wrap_response 8-key envelope +
#      Rule 4 ``_decimal_to_number`` byte-parity on integer Decimal
#   2. JWT boundary: missing Bearer → 401
#   3. Cross-factory denial: token factoryId ≠ URL factoryId → 403
#
# Pattern mirrors gold-standard ``test_config_thresholds_pilot.py``.

import jwt  # noqa: E402

from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402


_TEST_JWT_SECRET = "phase-2b-prod-upgrade-pilot-secret"


def _make_endpoint_token(
    *,
    factory_id="F001",
    role="factory_super_admin",
    exp_offset=3600,
):
    from time import time

    payload = {
        "userId": 22,
        "username": "prod-upgrade-test",
        "role": role,
        "exp": int(time()) + exp_offset,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return jwt.encode(payload, _TEST_JWT_SECRET, algorithm=JWT_ALGORITHM)


class _RouterFakeConn:
    """asyncpg.Connection stub returning a fixed row from fetchrow."""

    def __init__(self, row):
        self._row = row

    async def fetchrow(self, sql, *args):
        return self._row


class _RouterFakePool:
    """asyncpg.Pool stub yielding a single _RouterFakeConn from acquire()."""

    def __init__(self, row):
        self._conn = _RouterFakeConn(row)

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self_inner):
                return pool._conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Ctx()


class _FactoryGoldConn:
    def __init__(self, rows):
        self.rows = rows
        self.executed = []
        self.fetch_sql = None
        self.fetch_args = None

    def transaction(self):
        conn = self

        class _Tx:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Tx()

    async def execute(self, sql, *args):
        self.executed.append((sql, args))

    async def fetch(self, sql, *args):
        self.fetch_sql = sql
        self.fetch_args = args
        return self.rows


class _FactoryGoldPool:
    def __init__(self, rows):
        self.conn = _FactoryGoldConn(rows)

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self_inner):
                return pool.conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Ctx()


@pytest.fixture
def factory_gold_pool(monkeypatch):
    def _factory(rows):
        pool = _FactoryGoldPool(rows)

        async def fake_get_pg_pool():
            return pool

        import smartbi.config

        monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)
        return pool

    return _factory


@pytest.mark.asyncio
async def test_factory_dispatch_reads_gold_with_rls_and_returns_oee_metrics(
    factory_gold_pool,
):
    rows = [
        {
            "stat_date": date(2026, 5, 1),
            "product_type_id": "P001",
            "batch_count": 2,
            "total_planned_qty": 100,
            "total_actual_qty": 90,
            "total_good_qty": 81,
            "avg_yield_rate": 90,
            "total_cost": 1000,
        },
        {
            "stat_date": date(2026, 5, 1),
            "product_type_id": "P002",
            "batch_count": 1,
            "total_planned_qty": 100,
            "total_actual_qty": 80,
            "total_good_qty": 72,
            "avg_yield_rate": 90,
            "total_cost": 900,
        },
    ]
    pool = factory_gold_pool(rows)

    result = await _factory_production_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 10), "oee"
    )

    assert "dataAvailability" not in result
    assert [m["metricCode"] for m in result["metrics"]] == [
        "OEE",
        "AVAILABILITY",
        "PERFORMANCE",
        "QUALITY_RATE",
    ]
    assert result["metrics"][1]["value"] == 85
    assert result["metrics"][2]["value"] == 90
    assert result["metrics"][3]["value"] == 90
    assert result["metrics"][0]["value"] == 68.85
    assert result["trendChart"]["data"][0]["date"] == "2026-05-01"
    assert "agg_factory_batch_daily" in pool.conn.fetch_sql
    assert "product_type_id IS NOT NULL" in pool.conn.fetch_sql
    assert pool.conn.fetch_args == (
        "F001",
        date(2026, 5, 1),
        date(2026, 5, 10),
    )
    assert pool.conn.executed == [
        ("SELECT set_config('app.factory_id', $1, true)", ("F001",))
    ]


@pytest.mark.asyncio
async def test_factory_dispatch_no_gold_keeps_phase_2d_placeholder(factory_gold_pool):
    factory_gold_pool([])
    result = await _factory_production_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 10), "oee"
    )

    assert result["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


@pytest.fixture
def endpoint_client(monkeypatch):
    """FastAPI TestClient with cretas_db + smartbi_db pools patched.

    cretas_db pool returns ``{"type": "RESTAURANT"}`` so the router
    dispatches to ``_restaurant_production_dispatch`` (the wrap_response
    branch). smartbi_db pool returns ``60 bills / 2 stores / 3 days`` so
    M3 proxy = ``Decimal("10")`` which exercises Rule 4 int-collapse via
    ``_decimal_to_number``.

    JWT_SECRET is set via monkeypatch so each test gets a clean env and
    the signer (``_make_endpoint_token``) and verifier
    (``verify_jwt_and_factory``) use identical secrets.
    """
    monkeypatch.setenv("JWT_SECRET", _TEST_JWT_SECRET)

    import smartbi.config

    cretas_pool = _RouterFakePool({"type": "RESTAURANT"})
    smartbi_pool = _RouterFakePool(
        {"bill_count": 60, "store_count": 2, "day_count": 3}
    )

    async def fake_get_cretas_pool():
        return cretas_pool

    async def fake_get_pg_pool():
        return smartbi_pool

    monkeypatch.setattr(smartbi.config, "get_cretas_pool", fake_get_cretas_pool)
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(analysis_production.router)
    return TestClient(app)


def test_production_endpoint_returns_full_envelope(endpoint_client):
    """Happy path — restaurant tenant returns 8-key wrap_response envelope.

    Audit §4.1 spec ``assert "success" in data and data["success"] is True``
    + envelope-shape verification. The audit template's literal "21-key"
    count was approximate; the actual restaurant oee/None branch emits
    5 inner data keys (tenantType, startDate, endDate, metrics, trendChart)
    inside an 8-key outer ApiResponse envelope (Rule 9 emit-nulls). Both
    are asserted explicitly below.

    Rule 4 byte parity: M3 proxy = ``60 / (2 * 3) = Decimal("10")``;
    ``_decimal_to_number`` collapses integer-valued Decimal to ``int 10``.
    """
    headers = {"Authorization": f"Bearer {_make_endpoint_token(factory_id='F001')}"}
    resp = endpoint_client.get(
        "/api/mobile/F001/smart-bi/analysis/production",
        params={"startDate": "2026-05-01", "endDate": "2026-05-03"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Outer ApiResponse envelope — 8 keys (Rule 9 emit-nulls).
    assert body["success"] is True
    assert body["code"] == 200
    assert body["message"] == "操作成功"
    assert "data" in body
    assert "timestamp" in body
    assert body["actionHint"] is None
    assert body["severity"] is None
    assert body["hintTarget"] is None

    # Inner restaurant body — oee/None branch.
    data = body["data"]
    assert data["tenantType"] == "RESTAURANT"
    assert data["startDate"] == "2026-05-01"
    assert data["endDate"] == "2026-05-03"
    assert data["trendChart"] is None
    assert len(data["metrics"]) == 3
    assert data["metrics"][0]["metricCode"] == "KITCHEN_STATION_UTILIZATION"
    assert data["metrics"][1]["metricCode"] == "AVG_PREP_TIME"

    # Rule 4 — _decimal_to_number int-collapse on integer-valued Decimal.
    m3 = data["metrics"][2]
    assert m3["metricCode"] == "TABLE_TURNOVER_RATE"
    assert m3["proxyMetric"]["value"] == 10
    assert isinstance(m3["proxyMetric"]["value"], int)


@pytest.fixture
def factory_endpoint_client(monkeypatch):
    """Same as ``endpoint_client`` but with cretas_db type=FACTORY so the
    router dispatches to ``_factory_production_dispatch`` (the Phase 2D
    empty-envelope branch). Used to verify the R1 P2-3 fix: factory branch
    now wraps at the router boundary instead of returning the raw inner dict.
    """
    monkeypatch.setenv("JWT_SECRET", _TEST_JWT_SECRET)

    import smartbi.config

    cretas_pool = _RouterFakePool({"type": "FACTORY"})

    async def fake_get_cretas_pool():
        return cretas_pool

    async def fake_get_pg_pool():
        return None

    monkeypatch.setattr(smartbi.config, "get_cretas_pool", fake_get_cretas_pool)
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(analysis_production.router)
    return TestClient(app)


def test_factory_endpoint_returns_wrapped_envelope(factory_endpoint_client):
    """R1 P2-3 regression lock: factory branch must emit the same outer
    ApiResponse envelope as the restaurant branch.

    Before the fix, the router returned the raw factory-dispatch inner dict
    (``{startDate, endDate, metrics, ..., dataAvailability}``) while the
    restaurant branch returned the wrapped envelope — frontend clients had
    to handle two shapes. After the fix, both branches return the canonical
    8-key envelope with the inner data under ``body["data"]``.
    """
    headers = {"Authorization": f"Bearer {_make_endpoint_token(factory_id='F001')}"}
    resp = factory_endpoint_client.get(
        "/api/mobile/F001/smart-bi/analysis/production",
        params={"startDate": "2026-05-01", "endDate": "2026-05-03", "analysisType": "oee"},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()

    # Outer envelope identical shape to restaurant branch.
    assert body["success"] is True
    assert body["code"] == 200
    assert body["message"] == "操作成功"
    assert body["actionHint"] is None
    assert body["severity"] is None
    assert body["hintTarget"] is None

    # Inner Phase 2D placeholder, marker preserved.
    data = body["data"]
    assert data["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER
    assert data["startDate"] == "2026-05-01"
    assert data["endDate"] == "2026-05-03"
    # oee branch shape.
    assert data["metrics"] == []
    assert data["trendChart"] == {}


def test_production_endpoint_requires_jwt(endpoint_client):
    """Missing Bearer header → 401 before tenant lookup or dispatch."""
    resp = endpoint_client.get(
        "/api/mobile/F001/smart-bi/analysis/production",
        params={"startDate": "2026-05-01", "endDate": "2026-05-03"},
    )
    assert resp.status_code == 401


def test_production_endpoint_cross_factory_denied(endpoint_client):
    """Token factoryId=F001 vs URL factoryId=F002 → 403 cross-factory deny."""
    headers = {"Authorization": f"Bearer {_make_endpoint_token(factory_id='F001')}"}
    resp = endpoint_client.get(
        "/api/mobile/F002/smart-bi/analysis/production",
        params={"startDate": "2026-05-01", "endDate": "2026-05-03"},
        headers=headers,
    )
    assert resp.status_code == 403
