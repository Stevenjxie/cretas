"""Phase 2D factory-dispatch empty-envelope stub contract tests.

Phase 2D Subagent B rewired ``_factory_production_dispatch`` and
``_factory_quality_dispatch`` from raising stubs into pure-Python empty
envelopes carrying the canonical marker ``FACTORY_SILVER_PHASE_2D_PENDING``.
This test file locks the envelope contract so the Silver-layer real-DB
impl (Phase 2D PR-A/B/C/D chain) doesn't accidentally drop the marker
or churn the per-``analysisType`` shape that the frontend renders against.

Coverage map:

1. ``FACTORY_PHASE_2D_PENDING_MARKER`` constant exported from both modules
   and equals the canonical string ``"FACTORY_SILVER_PHASE_2D_PENDING"``.
2. ``_factory_production_dispatch`` returns the documented empty envelope
   for every ``analysisType`` (oee / efficiency / equipment / overview / None).
3. ``_factory_quality_dispatch`` returns the documented empty envelope
   for every ``analysisType`` (fpy / defect / rework / overview / None).
4. ``factory_id`` is a pass-through string — different tenants (incl. real
   restaurant factory_ids that *would* have routed to the restaurant
   dispatcher at the router layer) get structurally identical envelopes
   because the dispatch helper is router-internal and doesn't query tenant.
5. Idempotency: calling the dispatch twice produces dicts with the same
   shape (timestamp fields excluded from equality compare).

Sibling tests: ``test_analysis_production_skeleton.py`` /
``test_analysis_quality_skeleton.py`` (constant-binding + signature) +
``test_analysis_production_restaurant.py`` /
``test_analysis_quality_restaurant.py`` (restaurant impl behavior).
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi_compat.api import analysis_production, analysis_quality
from smartbi_compat.api.analysis_production import (
    FACTORY_PHASE_2D_PENDING_MARKER as PROD_MARKER,
    _factory_production_dispatch,
)
from smartbi_compat.api.analysis_quality import (
    FACTORY_PHASE_2D_PENDING_MARKER as QUAL_MARKER,
    _factory_quality_dispatch,
)


# ============================================================
# Test fixtures
# ============================================================

START_DATE = date(2026, 1, 1)
END_DATE = date(2026, 1, 31)

# Representative factory tenants: covers F-prefix factory codes,
# the F999 fixture tenant, and an R-prefix restaurant code to prove
# the dispatch helper is pass-through (router-layer gates tenant type).
FACTORY_ID_MATRIX = ["F001", "F002", "F006", "F999", "FOOD_3101_001"]

# Per-variant required keys for production dispatch (per task spec table).
PROD_OEE_KEYS = {"startDate", "endDate", "dataAvailability", "metrics", "trendChart"}
PROD_EFFICIENCY_KEYS = {"startDate", "endDate", "dataAvailability", "metrics", "ranking"}
PROD_EQUIPMENT_KEYS = {
    "startDate",
    "endDate",
    "dataAvailability",
    "metrics",
    "ranking",
    "downtimeChart",
}
PROD_OVERVIEW_KEYS = {
    "period",
    "startDate",
    "endDate",
    "dataAvailability",
    "kpiCards",
    "rankings",
    "charts",
    "aiInsights",
    "recommendations",
    "suggestions",
    "generatedAt",
    "lastUpdated",
    "fromCache",
    "cacheExpireAt",
}

# Per-variant required keys for quality dispatch (per task spec table).
QUAL_FPY_KEYS = {"startDate", "endDate", "dataAvailability", "metrics", "trendChart"}
QUAL_DEFECT_KEYS = {
    "startDate",
    "endDate",
    "dataAvailability",
    "ranking",
    "paretoChart",
}
QUAL_REWORK_KEYS = {"startDate", "endDate", "dataAvailability", "metrics", "costChart"}
QUAL_OVERVIEW_KEYS = PROD_OVERVIEW_KEYS  # Same DashboardResponse shape.


@pytest.fixture(autouse=True)
def no_factory_gold_pool(monkeypatch):
    """Keep legacy placeholder assertions in the explicit no-Gold path."""
    import smartbi.config

    async def fake_get_pg_pool():
        return None

    monkeypatch.setattr(smartbi.config, "get_pg_pool", fake_get_pg_pool)


# ============================================================
# 1. Marker constant exported from both modules
# ============================================================


def test_production_module_exports_marker_constant():
    """``FACTORY_PHASE_2D_PENDING_MARKER`` is importable from the prod module."""
    assert hasattr(analysis_production, "FACTORY_PHASE_2D_PENDING_MARKER")
    assert PROD_MARKER == "FACTORY_SILVER_PHASE_2D_PENDING"


def test_quality_module_exports_marker_constant():
    """``FACTORY_PHASE_2D_PENDING_MARKER`` is importable from the quality module."""
    assert hasattr(analysis_quality, "FACTORY_PHASE_2D_PENDING_MARKER")
    assert QUAL_MARKER == "FACTORY_SILVER_PHASE_2D_PENDING"


def test_production_and_quality_markers_are_identical_string():
    """Both modules use the exact same marker string — frontend keys off one value."""
    assert PROD_MARKER == QUAL_MARKER


# ============================================================
# 2. Production dispatch returns documented empty envelope
# ============================================================


@pytest.mark.parametrize(
    "analysis_type,required_keys",
    [
        ("oee", PROD_OEE_KEYS),
        ("efficiency", PROD_EFFICIENCY_KEYS),
        ("equipment", PROD_EQUIPMENT_KEYS),
        ("overview", PROD_OVERVIEW_KEYS),
        (None, PROD_OVERVIEW_KEYS),
    ],
)
async def test_production_dispatch_returns_phase_2d_envelope(
    analysis_type, required_keys
):
    """Every analysisType branch returns a dict with the documented shape.

    Phase 2D Subagent B contract: every branch carries the canonical
    ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` marker plus
    the per-variant required keys (per task spec table).
    """
    result = await _factory_production_dispatch(
        "F001", START_DATE, END_DATE, analysis_type
    )

    # Top-level: must be a dict (NOT a raise)
    assert isinstance(result, dict)

    # Marker check
    assert result["dataAvailability"] == PROD_MARKER

    # Required keys present
    assert required_keys.issubset(set(result.keys())), (
        f"missing keys for analysis_type={analysis_type!r}: "
        f"expected {required_keys - set(result.keys())}"
    )

    # ISO date pass-through
    assert result["startDate"] == "2026-01-01"
    assert result["endDate"] == "2026-01-31"


async def test_production_dispatch_oee_empty_containers():
    result = await _factory_production_dispatch("F001", START_DATE, END_DATE, "oee")
    assert result["metrics"] == []
    assert result["trendChart"] == {}


async def test_production_dispatch_efficiency_empty_containers():
    result = await _factory_production_dispatch(
        "F001", START_DATE, END_DATE, "efficiency"
    )
    assert result["metrics"] == []
    assert result["ranking"] == []


async def test_production_dispatch_equipment_empty_containers():
    result = await _factory_production_dispatch(
        "F001", START_DATE, END_DATE, "equipment"
    )
    assert result["metrics"] == []
    assert result["ranking"] == []
    assert result["downtimeChart"] == {}


@pytest.mark.parametrize("analysis_type", ["overview", None, "unknown_value"])
async def test_production_dispatch_overview_dashboard_shape(analysis_type):
    """overview / None / unknown all fall through to DashboardResponse shape."""
    result = await _factory_production_dispatch(
        "F001", START_DATE, END_DATE, analysis_type
    )
    assert result["period"] == "CUSTOM"
    assert result["kpiCards"] == []
    assert result["rankings"] == {}
    assert result["charts"] == {}
    assert result["aiInsights"] == []
    assert result["recommendations"] == []
    assert result["suggestions"] == []
    assert result["fromCache"] is False
    assert result["cacheExpireAt"] is None
    # generatedAt / lastUpdated are present + non-null (timestamp values not
    # asserted because they're produced at call time).
    assert result["generatedAt"] is not None
    assert result["lastUpdated"] is not None


# ============================================================
# 3. Quality dispatch returns documented empty envelope
# ============================================================


@pytest.mark.parametrize(
    "analysis_type,required_keys",
    [
        ("fpy", QUAL_FPY_KEYS),
        ("defect", QUAL_DEFECT_KEYS),
        ("rework", QUAL_REWORK_KEYS),
        ("overview", QUAL_OVERVIEW_KEYS),
        (None, QUAL_OVERVIEW_KEYS),
    ],
)
async def test_quality_dispatch_returns_phase_2d_envelope(
    analysis_type, required_keys
):
    """Every analysisType branch returns a dict with the documented shape."""
    result = await _factory_quality_dispatch(
        "F001", START_DATE, END_DATE, analysis_type
    )

    assert isinstance(result, dict)
    assert result["dataAvailability"] == QUAL_MARKER
    assert required_keys.issubset(set(result.keys())), (
        f"missing keys for analysis_type={analysis_type!r}: "
        f"expected {required_keys - set(result.keys())}"
    )
    assert result["startDate"] == "2026-01-01"
    assert result["endDate"] == "2026-01-31"


async def test_quality_dispatch_fpy_empty_containers():
    result = await _factory_quality_dispatch("F001", START_DATE, END_DATE, "fpy")
    assert result["metrics"] == []
    assert result["trendChart"] == {}


async def test_quality_dispatch_defect_empty_containers():
    """Per impl: defect variant emits ranking + paretoChart (no metrics field)."""
    result = await _factory_quality_dispatch("F001", START_DATE, END_DATE, "defect")
    assert result["ranking"] == []
    assert result["paretoChart"] == {}


async def test_quality_dispatch_rework_empty_containers():
    result = await _factory_quality_dispatch("F001", START_DATE, END_DATE, "rework")
    assert result["metrics"] == []
    assert result["costChart"] == {}


@pytest.mark.parametrize("analysis_type", ["overview", None, "unknown_value"])
async def test_quality_dispatch_overview_dashboard_shape(analysis_type):
    """overview / None / unknown all fall through to DashboardResponse shape."""
    result = await _factory_quality_dispatch(
        "F001", START_DATE, END_DATE, analysis_type
    )
    assert result["period"] == "CUSTOM"
    assert result["kpiCards"] == []
    assert result["rankings"] == {}
    assert result["charts"] == {}
    assert result["aiInsights"] == []
    assert result["recommendations"] == []
    assert result["suggestions"] == []
    assert result["fromCache"] is False
    assert result["cacheExpireAt"] is None
    assert result["generatedAt"] is not None
    assert result["lastUpdated"] is not None


# ============================================================
# 4. factory_id pass-through across representative tenants
# ============================================================


@pytest.mark.parametrize("factory_id", FACTORY_ID_MATRIX)
async def test_production_dispatch_factory_id_passthrough(factory_id):
    """Dispatch envelope shape is independent of factory_id.

    The dispatcher is router-internal — the router resolves tenant via
    ``factories.type`` before calling either factory or restaurant branch.
    So the factory dispatcher itself is a pure-Python helper that does
    NOT discriminate on factory_id beyond echoing it (or in the wrapped
    response, not echoing it at all in this Phase 2D stub).
    """
    result = await _factory_production_dispatch(
        factory_id, START_DATE, END_DATE, "oee"
    )
    assert result["dataAvailability"] == PROD_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


@pytest.mark.parametrize("factory_id", FACTORY_ID_MATRIX)
async def test_quality_dispatch_factory_id_passthrough(factory_id):
    """Dispatch envelope shape is independent of factory_id."""
    result = await _factory_quality_dispatch(
        factory_id, START_DATE, END_DATE, "fpy"
    )
    assert result["dataAvailability"] == QUAL_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


# ============================================================
# 5. Cross-tenant negative — restaurant factory_id still gets factory envelope
# ============================================================


async def test_production_dispatch_with_restaurant_factory_id_still_factory_envelope():
    """A restaurant ``factory_id`` passed directly to the factory dispatcher
    returns the factory envelope (the dispatch helper itself doesn't query
    tenant.py — routing is the gatekeeper). This proves the helper is a
    pure structural function, not a tenant-aware dispatch."""
    result = await _factory_production_dispatch(
        "R_ILTEATRO_REAL", START_DATE, END_DATE, "oee"
    )
    assert result["dataAvailability"] == PROD_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


async def test_quality_dispatch_with_restaurant_factory_id_still_factory_envelope():
    """Symmetric cross-tenant guard for the quality dispatcher."""
    result = await _factory_quality_dispatch(
        "R_ILTEATRO_REAL", START_DATE, END_DATE, "fpy"
    )
    assert result["dataAvailability"] == QUAL_MARKER
    assert result["metrics"] == []
    assert result["trendChart"] == {}


# ============================================================
# 6. Idempotency — repeated calls return equal shape (excluding timestamps)
# ============================================================


def _strip_timestamp_fields(envelope: dict) -> dict:
    """Drop generated-at-call-time fields so two envelopes can be compared.

    ``generatedAt`` / ``lastUpdated`` use ``datetime.now()`` so two
    back-to-back calls will diverge by microseconds. Strip them before
    structural equality compare.
    """
    return {k: v for k, v in envelope.items() if k not in {"generatedAt", "lastUpdated"}}


async def test_production_dispatch_is_idempotent_in_shape():
    """Two calls with identical args produce equal envelopes (sans timestamps)."""
    a = await _factory_production_dispatch("F001", START_DATE, END_DATE, "oee")
    b = await _factory_production_dispatch("F001", START_DATE, END_DATE, "oee")
    # oee envelope has no timestamp fields, so direct equality.
    assert a == b


async def test_production_dispatch_overview_is_idempotent_modulo_timestamps():
    """overview envelope contains generatedAt/lastUpdated — compare modulo those."""
    a = await _factory_production_dispatch("F001", START_DATE, END_DATE, None)
    b = await _factory_production_dispatch("F001", START_DATE, END_DATE, None)
    assert _strip_timestamp_fields(a) == _strip_timestamp_fields(b)
    # Timestamps are present on both calls
    assert a["generatedAt"] is not None and b["generatedAt"] is not None
    assert a["lastUpdated"] is not None and b["lastUpdated"] is not None


async def test_quality_dispatch_is_idempotent_in_shape():
    a = await _factory_quality_dispatch("F001", START_DATE, END_DATE, "fpy")
    b = await _factory_quality_dispatch("F001", START_DATE, END_DATE, "fpy")
    assert a == b


async def test_quality_dispatch_overview_is_idempotent_modulo_timestamps():
    a = await _factory_quality_dispatch("F001", START_DATE, END_DATE, None)
    b = await _factory_quality_dispatch("F001", START_DATE, END_DATE, None)
    assert _strip_timestamp_fields(a) == _strip_timestamp_fields(b)
    assert a["generatedAt"] is not None and b["generatedAt"] is not None
    assert a["lastUpdated"] is not None and b["lastUpdated"] is not None
