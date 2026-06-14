"""Unit tests for gold_etl_daily_refresh.

Tests:
  - Tenant discovery SQL: UNION of 3 tables, distinct, sorted.
  - Error isolation: one ETL failure per tenant does not abort others;
    summary accumulates errors while remaining runs succeed.
  - dry_run: no pool acquired, no ETL called.
  - --factories CLI filter: restricts tenants to explicit list.

Run:
    cd backend/python
    python -m pytest smartbi/tests/test_gold_etl_refresh.py -p no:cacheprovider -v
"""
from __future__ import annotations
from scripts.gold_etl_daily_refresh import (
    EtlRunResult,
    RefreshSummary,
    _DISCOVERY_SQL,
    _HAS_PRODUCTION_SQL,
    _HAS_MATERIAL_BATCHES_SQL,
    discover_factories,
    _factory_subsets,
    _run_restaurant_ops,
    _run_factory_production,
    _run_supplier_price,
    run_refresh,
)

import sys
import os
import pytest
from dataclasses import dataclass
from typing import Dict, List
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Path bootstrap (mirrors gold_etl_daily_refresh.py)
# ---------------------------------------------------------------------------
_HERE = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_HERE, "..", ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)


# ---------------------------------------------------------------------------
# Import the module under test
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Helpers: fake asyncpg pool / connection
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    def __getattr__(self, item):
        return self[item]


class _FakeConn:
    def __init__(self, rows_by_keyword: Dict[str, List[dict]]):
        """rows_by_keyword: maps SQL keyword substring to the rows to return."""
        self._rows = rows_by_keyword

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        pass

    async def fetch(self, sql, *args, **kwargs):
        for key, rows in self._rows.items():
            if key in sql:
                return [_FakeRecord(r) for r in rows]
        return []

    async def execute(self, sql, *args, **kwargs):
        pass

    async def fetchval(self, sql, *args, **kwargs):
        return None


class _FakePool:
    def __init__(self, rows_by_keyword: Dict[str, List[dict]]):
        self._conn = _FakeConn(rows_by_keyword)

    def acquire(self):
        return self

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *a):
        pass


# ---------------------------------------------------------------------------
# EtlStats stubs
# ---------------------------------------------------------------------------

@dataclass
class _StubEtlStats:
    dim_ingredient_upserted: int = 5
    errors: List[str] = None

    def __post_init__(self):
        if self.errors is None:
            self.errors = []


@dataclass
class _StubFactoryEtlStats:
    batches_processed: int = 3
    errors: List[str] = None

    def __post_init__(self):
        if self.errors is None:
            self.errors = []


# ---------------------------------------------------------------------------
# 1. Tenant discovery SQL — union of 3 tables
# ---------------------------------------------------------------------------

def test_discovery_sql_contains_all_three_tables():
    """DISCOVERY_SQL must reference production_batches, recipes, material_batches."""
    sql_lower = _DISCOVERY_SQL.lower()
    assert "production_batches" in sql_lower
    assert "recipes" in sql_lower
    assert "material_batches" in sql_lower


def test_discovery_sql_is_union():
    assert "union" in _DISCOVERY_SQL.lower()


def test_discovery_sql_filters_deleted():
    assert "deleted_at is null" in _DISCOVERY_SQL.lower()


def test_has_production_sql_references_production_batches():
    assert "production_batches" in _HAS_PRODUCTION_SQL.lower()


def test_has_material_batches_sql_references_material_batches():
    assert "material_batches" in _HAS_MATERIAL_BATCHES_SQL.lower()


@pytest.mark.asyncio
async def test_discover_factories_returns_factory_ids():
    rows = [
        {"factory_id": "F001"},
        {"factory_id": "F006"},
    ]
    pool = _FakePool({"production_batches": rows})
    result = await discover_factories(pool)
    # Function just extracts factory_id column from returned rows
    assert result == ["F001", "F006"]


@pytest.mark.asyncio
async def test_factory_subsets_returns_two_sets():
    class _SubsetConn:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            pass

        async def fetch(self, sql, *a, **kw):
            if "production_batches" in sql:
                return [_FakeRecord({"factory_id": "F001"}), _FakeRecord({"factory_id": "F006"})]
            if "material_batches" in sql:
                return [_FakeRecord({"factory_id": "F001"})]
            return []

    class _SubsetPool:
        def acquire(self):
            return _SubsetConn()

    pool = _SubsetPool()
    has_prod, has_mat = await _factory_subsets(pool)
    assert has_prod == {"F001", "F006"}
    assert has_mat == {"F001"}


# ---------------------------------------------------------------------------
# 2. Error isolation — one ETL failure does not abort others
# ---------------------------------------------------------------------------

# The module uses module-level aliases:
#   _restaurant_ops_etl  (from restaurant_ops_etl.run_full_etl_with_retry)
#   _factory_production_etl  (from factory_production_etl.run_factory_etl_with_retry)
#   _supplier_price_etl  (from supplier_price_ingest_etl.run_supplier_price_ingest)
# Patch these names in the scripts.gold_etl_daily_refresh namespace.

_MOD = "scripts.gold_etl_daily_refresh"


@pytest.fixture(autouse=True)
def _reset_config_singletons():
    """Reset smartbi.config pool singletons between tests to prevent cross-test
    contamination from stale asyncpg connections."""
    import smartbi.config as cfg
    cfg._cretas_pool = None
    cfg._pg_pool = None
    cfg._cretas_pool_lock = None
    yield
    cfg._cretas_pool = None
    cfg._pg_pool = None
    cfg._cretas_pool_lock = None


@pytest.mark.asyncio
async def test_restaurant_ops_failure_is_isolated():
    """_run_restaurant_ops wraps exception and returns ok=False without raising."""
    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    with patch(f"{_MOD}._restaurant_ops_etl", new=AsyncMock(side_effect=RuntimeError("DB timeout"))):
        result = await _run_restaurant_ops(cretas_pool, smartbi_pool, "F001")

    assert result.ok is False
    assert result.factory_id == "F001"
    assert result.etl_name == "restaurant_ops"
    assert "DB timeout" in (result.error or "")
    assert result.duration_s >= 0


@pytest.mark.asyncio
async def test_factory_production_failure_is_isolated():
    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    with patch(f"{_MOD}._factory_production_etl", new=AsyncMock(side_effect=ConnectionError("Pool exhausted"))):
        result = await _run_factory_production(cretas_pool, smartbi_pool, "F001")

    assert result.ok is False
    assert result.etl_name == "factory_production"
    assert "Pool exhausted" in (result.error or "")


@pytest.mark.asyncio
async def test_supplier_price_failure_is_isolated():
    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    with patch(f"{_MOD}._supplier_price_etl", new=AsyncMock(side_effect=ValueError("Bad factory_id"))):
        result = await _run_supplier_price(cretas_pool, smartbi_pool, "F001")

    assert result.ok is False
    assert result.etl_name == "supplier_price"
    assert "Bad factory_id" in (result.error or "")


@pytest.mark.asyncio
async def test_one_etl_error_does_not_abort_others(monkeypatch):
    """If restaurant_ops ETL raises internally, factory_production still runs for F001.

    Strategy: patch the inner ETL functions (_restaurant_ops_etl etc.) so that
    _run_restaurant_ops / _run_factory_production wrappers still execute their
    try/except logic — we test that the wrapper catches the error and the
    orchestrator continues.
    """
    import scripts.gold_etl_daily_refresh as mod

    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    factory_etl_count = 0
    supplier_etl_count = 0

    async def fake_restaurant_etl(cp, sp, fid):
        raise RuntimeError("restaurant ops exploded")

    async def fake_factory_etl(cp, sp, fid):
        nonlocal factory_etl_count
        factory_etl_count += 1
        return _StubFactoryEtlStats()

    async def fake_supplier_etl(cp, sp, fid):
        nonlocal supplier_etl_count
        supplier_etl_count += 1
        return {"inserted": 2, "errors": []}

    monkeypatch.setattr(mod, "_restaurant_ops_etl", fake_restaurant_etl)
    monkeypatch.setattr(mod, "_factory_production_etl", fake_factory_etl)
    monkeypatch.setattr(mod, "_supplier_price_etl", fake_supplier_etl)
    monkeypatch.setattr(mod, "get_cretas_pool", AsyncMock(return_value=cretas_pool))
    monkeypatch.setattr(mod, "get_pg_pool", AsyncMock(return_value=smartbi_pool))
    monkeypatch.setattr(mod, "discover_factories", AsyncMock(return_value=["F001"]))
    monkeypatch.setattr(mod, "_factory_subsets", AsyncMock(return_value=({"F001"}, {"F001"})))

    summary = await run_refresh()

    # restaurant_ops wrapper caught the error (ok=False in summary)
    # factory_production and supplier_price still ran (ok=True)
    assert factory_etl_count == 1
    assert supplier_etl_count == 1

    assert summary.error_count == 1
    assert summary.ok_count == 2

    failed = summary.errors[0]
    assert failed.etl_name == "restaurant_ops"
    assert failed.factory_id == "F001"
    assert "restaurant ops exploded" in (failed.error or "")


@pytest.mark.asyncio
async def test_multiple_factories_errors_do_not_cross_abort(monkeypatch):
    """Two factories: F001 restaurant ETL fails, F006 should still run all ETLs."""
    import scripts.gold_etl_daily_refresh as mod

    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    ran: List[str] = []

    async def fake_restaurant_etl(cp, sp, fid):
        ran.append(f"restaurant:{fid}")
        if fid == "F001":
            raise RuntimeError("F001 restaurant boom")
        return _StubEtlStats()

    async def fake_factory_etl(cp, sp, fid):
        ran.append(f"factory:{fid}")
        return _StubFactoryEtlStats()

    async def fake_supplier_etl(cp, sp, fid):
        ran.append(f"supplier:{fid}")
        return {"inserted": 1, "errors": []}

    monkeypatch.setattr(mod, "_restaurant_ops_etl", fake_restaurant_etl)
    monkeypatch.setattr(mod, "_factory_production_etl", fake_factory_etl)
    monkeypatch.setattr(mod, "_supplier_price_etl", fake_supplier_etl)
    monkeypatch.setattr(mod, "get_cretas_pool", AsyncMock(return_value=cretas_pool))
    monkeypatch.setattr(mod, "get_pg_pool", AsyncMock(return_value=smartbi_pool))
    monkeypatch.setattr(mod, "discover_factories", AsyncMock(return_value=["F001", "F006"]))
    monkeypatch.setattr(mod, "_factory_subsets", AsyncMock(return_value=({"F001", "F006"}, {"F001", "F006"})))

    summary = await run_refresh()

    # All 6 inner ETL calls happened (3 ETLs × 2 factories)
    assert "restaurant:F001" in ran
    assert "factory:F001" in ran
    assert "supplier:F001" in ran
    assert "restaurant:F006" in ran
    assert "factory:F006" in ran
    assert "supplier:F006" in ran

    assert summary.error_count == 1   # only F001 restaurant
    assert summary.ok_count == 5


# ---------------------------------------------------------------------------
# 3. dry_run — no pool acquired, no ETL called
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_dry_run_does_not_acquire_pool():
    acquire_called = False

    async def mock_get_cretas():
        nonlocal acquire_called
        acquire_called = True
        return MagicMock()

    async def mock_get_pg():
        nonlocal acquire_called
        acquire_called = True
        return MagicMock()

    with (
        patch(f"{_MOD}.get_cretas_pool", new=mock_get_cretas),
        patch(f"{_MOD}.get_pg_pool", new=mock_get_pg),
    ):
        summary = await run_refresh(dry_run=True)

    assert not acquire_called, "dry_run must not acquire any DB pool"
    # dry_run returns empty summary
    assert summary.total == 0


@pytest.mark.asyncio
async def test_dry_run_with_explicit_factories_does_not_call_etl():
    etl_called = False

    async def mock_etl(*args, **kwargs):
        nonlocal etl_called
        etl_called = True
        return _StubEtlStats()

    with (
        patch(f"{_MOD}._run_restaurant_ops", new=mock_etl),
        patch(f"{_MOD}._run_factory_production", new=mock_etl),
        patch(f"{_MOD}._run_supplier_price", new=mock_etl),
        patch(f"{_MOD}.get_cretas_pool", new=AsyncMock()),
        patch(f"{_MOD}.get_pg_pool", new=AsyncMock()),
    ):
        summary = await run_refresh(factory_ids=["F001", "F006"], dry_run=True)

    assert not etl_called
    assert summary.total == 0


# ---------------------------------------------------------------------------
# 4. --factories filter restricts to explicit list only
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_explicit_factories_restricts_run(monkeypatch):
    import scripts.gold_etl_daily_refresh as mod

    cretas_pool = MagicMock()
    smartbi_pool = MagicMock()

    ran_factories: List[str] = []

    async def fake_restaurant_etl(cp, sp, fid):
        ran_factories.append(fid)
        return _StubEtlStats()

    async def fake_factory_etl(cp, sp, fid):
        return _StubFactoryEtlStats()

    async def fake_supplier_etl(cp, sp, fid):
        return {"inserted": 0, "errors": []}

    monkeypatch.setattr(mod, "_restaurant_ops_etl", fake_restaurant_etl)
    monkeypatch.setattr(mod, "_factory_production_etl", fake_factory_etl)
    monkeypatch.setattr(mod, "_supplier_price_etl", fake_supplier_etl)
    monkeypatch.setattr(mod, "get_cretas_pool", AsyncMock(return_value=cretas_pool))
    monkeypatch.setattr(mod, "get_pg_pool", AsyncMock(return_value=smartbi_pool))
    # discover_factories is NOT called when factory_ids is explicit;
    # but _factory_subsets IS called to know which subsets apply.
    monkeypatch.setattr(mod, "discover_factories", AsyncMock(return_value=["F001", "F006", "F999"]))
    monkeypatch.setattr(mod, "_factory_subsets", AsyncMock(return_value=({"F001"}, {"F001"})))

    summary = await run_refresh(factory_ids=["F001"])

    # Only F001 should have been processed — not F006 or F999
    assert ran_factories == ["F001"], f"Expected only F001 but got {ran_factories}"
    assert summary.total >= 1


# ---------------------------------------------------------------------------
# 5. RefreshSummary aggregation
# ---------------------------------------------------------------------------

def test_refresh_summary_counts():
    summary = RefreshSummary()
    summary.results.append(EtlRunResult("F001", "restaurant_ops", True, 1.2))
    summary.results.append(EtlRunResult("F001", "factory_production", False, 0.5, error="boom"))
    summary.results.append(EtlRunResult("F001", "supplier_price", True, 0.8))

    assert summary.total == 3
    assert summary.ok_count == 2
    assert summary.error_count == 1
    assert len(summary.errors) == 1
    assert summary.errors[0].etl_name == "factory_production"


def test_refresh_summary_empty():
    summary = RefreshSummary()
    assert summary.total == 0
    assert summary.ok_count == 0
    assert summary.error_count == 0
    assert summary.errors == []
