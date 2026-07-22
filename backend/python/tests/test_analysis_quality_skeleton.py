"""Skeleton tests for ``/analysis/quality`` shared contracts.

chat-B1 Wave 1 (PR #354) shipped this set. chat-B2 Wave 2 ships the
restaurant branch impl (see ``test_analysis_quality_restaurant.py``).
Phase 2D Subagent B rewires the factory branch from a raising stub into
an empty-envelope placeholder marked ``FACTORY_SILVER_PHASE_2D_PENDING``;
the assertions below now lock the new envelope contract instead of a
``NotImplementedError`` raise.

Phase 2B-1 (chat-2B-qual-upgrade, 2026-05-12) appends 3 direct
endpoint-contract tests at the bottom of this file to graduate
``analysis_quality`` API coverage from ⚠️ partial (transitive-only) to
✅ full per audit doc §2.2 + §4.2. These exercise the FastAPI router
end-to-end with a mocked DB pool, asserting envelope shape + auth
boundaries + cross-factory denial.

Surviving contracts (chat-B2 + Phase 2D must preserve):

* Factory dispatcher returns the Phase 2D empty envelope tagged with
  the canonical ``FACTORY_SILVER_PHASE_2D_PENDING`` marker on every
  analysisType branch (future Silver-layer impl must keep the marker).
* ``_FACTORY_BRANCH_DEFERRED_MSG`` documentation constant still keeps
  every Silver-table name + ``"Phase 2D"`` substring for grep-readiness.
* Dispatcher signatures ``(factory_id, start_date, end_date,
  analysis_type)`` stable for chat-B2 + Phase 2D follow-ups.
* Router declares the polymorphic endpoint path
  ``/api/mobile/{factory_id}/smart-bi/analysis/quality`` (GET only).
* Module re-uses chat-A1's shared ``smartbi_compat.tenant`` (no
  duplicate enum).
* ``_RESTAURANT_DATA_AVAILABILITY_VOCAB`` controlled vocabulary
  exported with exactly 5 markers (Sub-B spec §4.2) — frontend
  chip-badge rendering depends on these exact strings.

Spec: docs/superpowers/specs/2026-05-12-t6-6-sub-b-quality-impl-spec.md
Audit:  docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md §4.2
Sibling: backend/python/tests/test_analysis_production_skeleton.py
"""
from __future__ import annotations

import inspect
import os
from datetime import date
from decimal import Decimal
from time import time

# JWT_SECRET must be set before ``smartbi_compat.auth`` import — the
# auth module reads it at request time via ``os.environ.get`` so a
# setdefault here is sufficient for the TestClient round-trips below.
os.environ.setdefault("JWT_SECRET", "phase-2b-qual-upgrade-test-secret")

import jwt as jwt_lib  # noqa: E402
import pytest  # noqa: E402

from smartbi_compat import tenant as tenant_module  # noqa: E402
from smartbi_compat.api import analysis_quality  # noqa: E402
from smartbi_compat.api.analysis_quality import (  # noqa: E402
    FACTORY_PHASE_2D_PENDING_MARKER,
    _factory_quality_dispatch,
    _restaurant_quality_dispatch,
    _FACTORY_BRANCH_DEFERRED_MSG,
    _RESTAURANT_DATA_AVAILABILITY_VOCAB,
    router,
)

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
pytestmark = [pytest.mark.api_endpoint("analysis_quality")]


# ============================================================
# Factory deferred-message content (Phase 2D handoff grep-ables)
# ============================================================


def test_factory_msg_contains_phase_2d_grep_substring():
    """``_FACTORY_BRANCH_DEFERRED_MSG`` still references Phase 2D.

    Subagent B rewrote the message body ("raises" → "empty envelope
    marked …") but kept the canonical Phase-2D marker token
    ``PHASE_2D`` (carried by ``FACTORY_SILVER_PHASE_2D_PENDING``) +
    every Silver table name (asserted by the parametrized sibling test)
    so grep audits and log searches keep working.
    """
    assert "PHASE_2D" in _FACTORY_BRANCH_DEFERRED_MSG
    assert "FACTORY_SILVER_PHASE_2D_PENDING" in _FACTORY_BRANCH_DEFERRED_MSG


@pytest.mark.parametrize(
    "table",
    [
        "fact_quality_inspection",
        "fact_quality_defect",
        "fact_rework_record",
        "fact_disposal_record",
        "fact_customer_complaint",
    ],
)
def test_factory_msg_lists_silver_table(table):
    """All 5 missing Silver tables (spec §2.3) grep-able for Phase 2D dispatch."""
    assert table in _FACTORY_BRANCH_DEFERRED_MSG


# ============================================================
# Factory dispatcher Phase 2D empty-envelope contracts
# ============================================================


@pytest.mark.asyncio
async def test_factory_dispatch_returns_phase_2d_envelope():
    """Factory branch must return the Phase 2D empty-envelope marker.

    Subagent B rewired the factory dispatcher from a raising stub into an
    empty-envelope response. The top-level
    ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` is the
    canonical marker frontends key off to render a "data pending" chip
    instead of a 500 error.
    """
    result = await _factory_quality_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 31), "fpy"
    )
    assert isinstance(result, dict)
    assert result["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER
    assert result["dataAvailability"] == "FACTORY_SILVER_PHASE_2D_PENDING"


@pytest.mark.parametrize("analysis_type", ["fpy", "defect", "rework", None])
@pytest.mark.asyncio
async def test_factory_dispatch_returns_envelope_for_every_analysis_type(
    analysis_type,
):
    """All 4 analysisType branches (fpy/defect/rework/overview=None) emit marker.

    Per Sub-B spec §1.1 + §3.6 the 4 valid analysisType values are
    fpy / defect / rework / None (None → overview). Phase 2D Subagent B
    rewired each branch to return an empty envelope with the canonical
    ``FACTORY_SILVER_PHASE_2D_PENDING`` marker. The Silver-layer impl
    must preserve the marker key.
    """
    result = await _factory_quality_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 31), analysis_type
    )
    assert isinstance(result, dict)
    assert result["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER


# ============================================================
# Dispatcher signature stability for chat-B2 + Phase 2D
# ============================================================


def test_factory_dispatch_signature_stable():
    """4-arg async signature — Phase 2D MUST preserve."""
    sig = inspect.signature(_factory_quality_dispatch)
    assert list(sig.parameters.keys()) == [
        "factory_id",
        "start_date",
        "end_date",
        "analysis_type",
    ]
    assert inspect.iscoroutinefunction(_factory_quality_dispatch)


def test_restaurant_dispatch_signature_stable():
    """4-arg async signature — chat-B3 + Phase 2D MUST preserve.

    chat-B2 (this PR) implements the restaurant body but the dispatcher
    signature was the chat-B1 contract — locking it here so chat-B3
    envelope wiring + future restaurant additions (e.g. trend chart)
    don't introduce kwargs that break the router contract.
    """
    sig = inspect.signature(_restaurant_quality_dispatch)
    assert list(sig.parameters.keys()) == [
        "factory_id",
        "start_date",
        "end_date",
        "analysis_type",
    ]
    assert inspect.iscoroutinefunction(_restaurant_quality_dispatch)


# ============================================================
# Router contract — endpoint path registered + GET-only
# ============================================================


def test_router_declares_quality_analysis_endpoint():
    """Path must match Sub-B spec §1.1 verbatim."""
    paths = [route.path for route in router.routes]
    assert "/api/mobile/{factory_id}/smart-bi/analysis/quality" in paths


def test_router_endpoint_methods_are_get_only():
    target = "/api/mobile/{factory_id}/smart-bi/analysis/quality"
    matching = [r for r in router.routes if r.path == target]
    assert len(matching) == 1
    assert set(matching[0].methods) == {"GET"}


# ============================================================
# Module-level advertisement (stable boundaries for chat-B3)
# ============================================================


def test_module_advertises_dispatch_helpers():
    """Stable function names for chat-B3 wiring + Phase 2D (factory)."""
    assert hasattr(analysis_quality, "_factory_quality_dispatch")
    assert hasattr(analysis_quality, "_restaurant_quality_dispatch")
    assert hasattr(analysis_quality, "get_quality_analysis")


def test_module_advertises_router_and_factory_deferred_constant():
    """Router + factory-deferred constant for chat-B3 envelope wiring."""
    assert hasattr(analysis_quality, "router")
    assert analysis_quality.router is router
    assert hasattr(analysis_quality, "_FACTORY_BRANCH_DEFERRED_MSG")


# ============================================================
# Tenant import contract — chat-B1 MUST re-use chat-A1's shared module
# ============================================================


def test_module_reuses_shared_tenant_type():
    """No duplicate enum — chat-B1 imports chat-A1's TenantType."""
    assert analysis_quality.TenantType is tenant_module.TenantType


def test_module_reuses_shared_get_tenant_type():
    """No duplicate query — chat-B1 imports chat-A1's get_tenant_type."""
    assert analysis_quality.get_tenant_type is tenant_module.get_tenant_type


# ============================================================
# Controlled dataAvailability vocabulary (chat-B2 handoff)
# ============================================================


def test_data_availability_vocab_is_immutable_tuple():
    """Tuple, not list — prevent accidental mutation in chat-B2/B3."""
    assert isinstance(_RESTAURANT_DATA_AVAILABILITY_VOCAB, tuple)


def test_data_availability_vocab_has_exactly_five_markers():
    """Sub-B spec §4 defines exactly 5 quality-side markers."""
    assert len(_RESTAURANT_DATA_AVAILABILITY_VOCAB) == 5


@pytest.mark.parametrize(
    "marker",
    [
        "MISSING_FOOD_SAFETY_INCIDENT_LOG",  # N1 Q-DEC-4
        "NO_REVIEW_DATA_FOR_CHAIN",           # N2 Q-DEC-5
        "RETURN_QTY_NOT_INGESTED",            # N3 Q-DEC-6 F3 fallback
        "NO_POS_DATA_FOR_PERIOD",             # N3 zero-row
        "WASTAGE_NOT_TRACKED",                # N4 Q-DEC-7
    ],
)
def test_data_availability_vocab_contains_marker(marker):
    """All 5 controlled-vocab markers (spec §4) exposed for chat-B2 import."""
    assert marker in _RESTAURANT_DATA_AVAILABILITY_VOCAB


# ============================================================
# Phase 2B-1 — Direct endpoint contract tests (⚠️ → ✅ upgrade)
# ============================================================
#
# Mirrors the §4.1 production 3-test pattern from
# ``docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md``:
#
#   1. happy path → 200 + wrapped envelope + Rule 4 numeric N3
#   2. missing JWT → 401
#   3. cross-factory token vs URL → 403
#
# The happy-path test wires a mocked asyncpg pool through both
# ``get_cretas_pool`` (used by the router for tenant detection) and
# ``get_pg_pool`` (used by the restaurant dispatcher for the N2/N3/N4
# queries). N3 ``DISH_RETURN_RATE`` carries a numeric value derived from
# ``Decimal("34.5") / Decimal("1000") * 100`` which exercises Rule 4
# ``_decimal_to_number`` end-to-end on the wire.

_JWT_SECRET = "phase-2b-qual-upgrade-test-secret"


def _make_test_token(
    *, factory_id="R_QINGHUAJIAO_REAL", role="factory_super_admin", exp_offset=3600
) -> str:
    payload: dict = {
        "userId": 22,
        "username": "alice",
        "role": role,
        "exp": int(time()) + exp_offset,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return jwt_lib.encode(payload, _JWT_SECRET, algorithm="HS256")


def _auth_header_endpoint(**kwargs) -> dict:
    return {"Authorization": f"Bearer {_make_test_token(**kwargs)}"}


class _EndpointFakeConn:
    """asyncpg.Connection stub dispatching by SQL substring.

    Single conn services both tenant detection (cretas_db ``factories``
    row) and the restaurant N2/N3/N4 query helpers — the router holds
    one conn for the tenant lookup and the restaurant dispatcher
    re-acquires from the same pool fixture for the metric queries.
    """

    def __init__(
        self,
        *,
        factory_type: str = "RESTAURANT",
        complaint: dict | None = None,
        return_rate: dict | None = None,
        wastage: dict | None = None,
    ):
        self._factory_type = factory_type
        self._complaint = complaint
        self._return_rate = return_rate
        self._wastage = wastage

    async def fetchrow(self, sql: str, *args):
        sql_lower = sql.lower()
        if "from factories" in sql_lower:
            return {"type": self._factory_type}
        if "restaurant_reviews" in sql_lower:
            return self._complaint
        if "fact_pos_item" in sql_lower:
            return self._return_rate
        if "fact_restaurant_wastage" in sql_lower:
            return self._wastage
        return None


class _EndpointFakePool:
    """asyncpg.Pool stub yielding a shared conn from ``acquire()``."""

    def __init__(self, conn: _EndpointFakeConn):
        self._conn = conn

    def acquire(self):
        pool_self = self

        class _Ctx:
            async def __aenter__(_self):
                return pool_self._conn

            async def __aexit__(_self, *_):
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
async def test_factory_quality_dispatch_reads_gold_with_rls_and_returns_fpy_metrics(
    factory_gold_pool,
):
    rows = [
        {
            "stat_date": date(2026, 5, 1),
            "product_type_id": "P001",
            "batch_count": 2,
            "total_planned_qty": Decimal("100"),
            "total_actual_qty": Decimal("90"),
            "total_good_qty": Decimal("81"),
            "avg_yield_rate": Decimal("90"),
            "total_cost": Decimal("1000"),
        },
        {
            "stat_date": date(2026, 5, 1),
            "product_type_id": "P002",
            "batch_count": 1,
            "total_planned_qty": Decimal("100"),
            "total_actual_qty": Decimal("80"),
            "total_good_qty": Decimal("72"),
            "avg_yield_rate": Decimal("90"),
            "total_cost": Decimal("900"),
        },
    ]
    pool = factory_gold_pool(rows)

    result = await _factory_quality_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 10), "fpy"
    )

    assert "dataAvailability" not in result
    assert [m["metricCode"] for m in result["metrics"]] == [
        "FPY",
        "TOTAL_INSPECTIONS",
        "DEFECT_COUNT",
        "DEFECT_RATE",
    ]
    assert result["metrics"][0]["value"] == 90
    assert result["metrics"][1]["value"] == 170
    assert result["metrics"][2]["value"] == 17
    assert result["metrics"][3]["value"] == 10
    assert result["trendChart"]["data"][0]["fpy"] == 90
    assert result["trendChart"]["data"][0]["defectRate"] == 10
    assert "agg_factory_batch_daily" in pool.conn.fetch_sql
    assert pool.conn.executed == [
        ("SELECT set_config('app.factory_id', $1, false)", ("F001",))
    ]


@pytest.mark.asyncio
async def test_factory_quality_dispatch_no_gold_keeps_phase_2d_placeholder(
    factory_gold_pool,
):
    factory_gold_pool([])
    result = await _factory_quality_dispatch(
        "F001", date(2026, 5, 1), date(2026, 5, 10), "fpy"
    )

    assert result["dataAvailability"] == FACTORY_PHASE_2D_PENDING_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


@pytest.fixture
def client_endpoint_restaurant(monkeypatch):
    """TestClient wired with mocks so the RESTAURANT branch emits numeric N3.

    The synthetic data shape mirrors the R_QINGHUAJIAO_REAL pilot in
    ``test_analysis_quality_restaurant.py`` (100 reviews / 15 complaints
    / 1000 sales-qty / 34.5 returns / no wastage rows).
    """
    import smartbi.config

    conn = _EndpointFakeConn(
        factory_type="RESTAURANT",
        complaint={"total_reviews": 100, "complaint_count": 15},
        return_rate={
            "total_sales_qty": Decimal("1000"),
            "total_return_qty": Decimal("34.5"),
        },
        wastage={
            "total_wastage_cost": Decimal("0"),
            "total_requisition_cost": Decimal("0"),
            "wastage_row_count": 0,
        },
    )
    pool = _EndpointFakePool(conn)

    async def fake_get_cretas_pool():
        return pool

    async def fake_get_pg_pool():
        return pool

    monkeypatch.setattr(smartbi.config, "get_cretas_pool", fake_get_cretas_pool)
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


@pytest.fixture
def client_endpoint_factory(monkeypatch):
    """TestClient for auth-only assertions — cretas pool acquisition fails
    so the router falls back to the FACTORY branch's Phase 2D envelope.

    Used by the 401 / 403 tests where the assertion fires before any DB
    work (the auth Depend runs first); the fallback keeps any reachable
    handler from raising on a missing pool.
    """
    import smartbi.config

    async def fake_get_cretas_pool():
        raise RuntimeError("pool unavailable in auth-only test")

    async def fake_get_pg_pool():
        return None

    monkeypatch.setattr(smartbi.config, "get_cretas_pool", fake_get_cretas_pool)
    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def test_quality_endpoint_returns_full_envelope(client_endpoint_restaurant):
    """Happy path — RESTAURANT branch returns wrapped envelope + Rule 4 numeric.

    Per audit §4.2 mirroring §4.1 production: GET with valid factory →
    200 + wrap_response envelope (``data``/``success``/``code``/...) +
    restaurant 4-metric shape. Additionally asserts Rule 4
    ``_decimal_to_number`` emits a numeric (int|float) — not str — for
    ``DISH_RETURN_RATE.value`` (34.5/1000 × 100 = 3.45%), exercising the
    BigDecimal → number serialization end-to-end through the FastAPI
    JSON encoder.
    """
    r = client_endpoint_restaurant.get(
        "/api/mobile/R_QINGHUAJIAO_REAL/smart-bi/analysis/quality",
        params={"startDate": "2026-05-01", "endDate": "2026-05-31"},
        headers=_auth_header_endpoint(factory_id="R_QINGHUAJIAO_REAL"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert "data" in body
    data = body["data"]
    assert data["tenantType"] == "RESTAURANT"
    assert data["startDate"] == "2026-05-01"
    assert data["endDate"] == "2026-05-31"
    assert "metrics" in data and len(data["metrics"]) == 4
    metric_codes = [m["metricCode"] for m in data["metrics"]]
    assert metric_codes == [
        "FOOD_SAFETY_INCIDENT_RATE",
        "COMPLAINT_RATE",
        "DISH_RETURN_RATE",
        "WASTAGE_RATE",
    ]
    # Rule 4 — _decimal_to_number must serialize as numeric, not str
    n3 = data["metrics"][2]
    assert isinstance(n3["value"], (int, float)), (
        "Rule 4 _decimal_to_number must emit numeric for DISH_RETURN_RATE.value, "
        f"got {type(n3['value']).__name__}: {n3['value']!r}"
    )
    # 34.5 / 1000 * 100 = 3.45% (float branch — non-integral Decimal)
    assert n3["value"] == 3.45


def test_factory_endpoint_returns_wrapped_envelope(client_endpoint_factory):
    """R1 P2-3 regression lock: factory branch must emit the same outer
    ApiResponse envelope as the restaurant branch.

    The ``client_endpoint_factory`` fixture raises on pool acquisition so
    the router's ``pool is None`` defensive path drops to
    ``TenantType.FACTORY`` and dispatches to ``_factory_quality_dispatch``.
    Before the fix the router returned that dispatcher's raw inner dict
    while the restaurant branch returned a wrapped envelope; after the fix
    both branches return the canonical 8-key envelope.
    """
    r = client_endpoint_factory.get(
        "/api/mobile/F001/smart-bi/analysis/quality",
        params={"startDate": "2026-05-01", "endDate": "2026-05-31", "analysisType": "fpy"},
        headers=_auth_header_endpoint(factory_id="F001"),
    )
    assert r.status_code == 200, r.text
    body = r.json()
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
    assert data["endDate"] == "2026-05-31"
    # fpy branch shape per Sub-B spec.
    assert data["metrics"] == []
    assert data["trendChart"] == {}


def test_quality_endpoint_requires_jwt(client_endpoint_factory):
    """No Authorization header → 401 (verify_jwt_and_factory Depend)."""
    r = client_endpoint_factory.get(
        "/api/mobile/F001/smart-bi/analysis/quality",
        params={"startDate": "2026-05-01", "endDate": "2026-05-31"},
    )
    assert r.status_code == 401, r.text


def test_quality_endpoint_cross_factory_denied(client_endpoint_factory):
    """Token factoryId=F001 calling F002 URL → 403."""
    r = client_endpoint_factory.get(
        "/api/mobile/F002/smart-bi/analysis/quality",
        params={"startDate": "2026-05-01", "endDate": "2026-05-31"},
        headers=_auth_header_endpoint(factory_id="F001"),
    )
    assert r.status_code == 403, r.text
