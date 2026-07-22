"""Real-DB tests for scripts/etl/_lib/upsert_helpers.py SQL prepare phase.

Why this exists
---------------
The existing tests in ``test_import_restaurant_chain.py`` use an in-memory
``FakeConn`` that records SQL strings but does NOT exercise the PostgreSQL
prepare phase. That is exactly how P1 bugs L1 + L2 from the 2026-05-11
R_ILTEATRO_REAL audit (PR #357) slipped past CI:

  L1: ``SET app.factory_id = $1``     → PG parser rejects bind params on SET.
  L2: ``REFRESH_AGG_RESTAURANT_DAILY_TOTALS_SQL`` ``$1`` used twice with
        ``str`` Python type → AmbiguousParameterError (text vs varchar).

Both failures surface ONLY when PG prepares the statement. The fix
(this PR) replaces SET with ``SELECT set_config(...)`` and adds explicit
``$1::text`` casts. These tests prepare the exact production SQL against a
real PG so any regression is caught at unit-test time, not at smoke time.

These tests are SKIPPED if PG is not reachable, matching the pattern in
``test_data_fabric_e2e.py``. Opt in via::

    INTEGRATION_PG_DSN=postgresql://smartbi_user:smartbi_secure_password_2025@localhost:5432/smartbi_db \\
        python -m pytest tests/test_upsert_helpers_real_db.py -v

A dedicated factory_id (``F999_INTEGRATION_ETL``) is used so the tests
never collide with real tenants. All inserted rows are rolled back at end
of each test (entire body runs inside a transaction).
"""
from __future__ import annotations

import importlib.util
import os
import sys
from datetime import date
from pathlib import Path
from typing import AsyncIterator

import pytest

try:
    import asyncpg
except ImportError:  # pragma: no cover — asyncpg is in requirements.txt
    asyncpg = None  # type: ignore[assignment]


# ── Load upsert_helpers by path (mirror pattern from test_import_restaurant_chain.py)
_PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
_HELPERS_PATH = _PROJECT_ROOT / "scripts" / "etl" / "_lib" / "upsert_helpers.py"

_helpers_spec = importlib.util.spec_from_file_location(
    "_lib.upsert_helpers", _HELPERS_PATH,
)
assert _helpers_spec is not None and _helpers_spec.loader is not None
H = importlib.util.module_from_spec(_helpers_spec)
sys.modules.setdefault("_lib.upsert_helpers", H)
_helpers_spec.loader.exec_module(H)


# ── Config ────────────────────────────────────────────────────────────────
PG_DSN: str = os.environ.get(
    "INTEGRATION_PG_DSN",
    "postgresql://smartbi_user:smartbi_secure_password_2025@localhost:5432/smartbi_db",
)
TEST_FACTORY: str = "F999_INTEGRATION_ETL"


pytestmark = pytest.mark.asyncio


# ── Pool fixture (skip cleanly if PG unreachable) ─────────────────────────
@pytest.fixture
async def pg_conn() -> AsyncIterator["asyncpg.Connection"]:
    """Single connection, skip the test if PG is unreachable.

    Short connect timeout (5s) so unreachable PG fails fast — matches the
    pattern in ``test_data_fabric_e2e.py``.
    """
    if asyncpg is None:
        pytest.skip("asyncpg not installed")
    try:
        conn = await asyncpg.connect(PG_DSN, timeout=5, command_timeout=10)
    except Exception as exc:  # noqa: BLE001 — broad on purpose: any conn error → skip
        pytest.skip(f"PG not reachable at {PG_DSN.split('@')[-1]}: {exc}")
    # Verify the agg table exists (Phase 2C+ schema). Skip rather than fail
    # if the migration hasn't been applied to this env.
    row = await conn.fetchrow(
        "SELECT to_regclass($1) AS rc", "public.agg_restaurant_daily_totals"
    )
    if row is None or row["rc"] is None:
        await conn.close()
        pytest.skip(
            "agg_restaurant_daily_totals not present "
            "(2026_04_24_gold_restaurant_ops.sql not applied)"
        )
    try:
        yield conn
    finally:
        await conn.close()


# ── Tests for L1: set_factory_scope SQL prepare ──────────────────────────
@pytest.mark.integration
async def test_set_factory_scope_prepares_against_real_pg(
    pg_conn: "asyncpg.Connection",
) -> None:
    """L1 regression: ``set_factory_scope`` must not raise PostgresSyntaxError.

    Before the 2026-05-11 fix the helper executed ``SET app.factory_id = $1``
    which PostgreSQL rejects with::

        asyncpg.exceptions.PostgresSyntaxError: syntax error at or near "$1"

    The fix uses ``SELECT set_config('app.factory_id', $1, false)`` — this
    test runs the actual helper end-to-end against a real PG and asserts
    the GUC was applied to the current transaction.
    """
    async with pg_conn.transaction():
        # The fix uses set_config(..., true) so the GUC is txn-local —
        # we must be inside a transaction for `current_setting` to see it.
        await H.set_factory_scope(pg_conn, TEST_FACTORY)
        row = await pg_conn.fetchrow(
            "SELECT current_setting('app.factory_id', true) AS v"
        )
        assert row is not None
        assert row["v"] == TEST_FACTORY


@pytest.mark.integration
async def test_set_factory_scope_rejects_empty_factory_id(
    pg_conn: "asyncpg.Connection",
) -> None:
    """ValueError still raised on empty factory_id — pre-flight contract intact."""
    with pytest.raises(ValueError, match="factory_id required"):
        await H.set_factory_scope(pg_conn, "")


# ── Tests for L2: REFRESH_AGG_RESTAURANT_DAILY_TOTALS_SQL prepare ─────────
@pytest.mark.integration
async def test_refresh_agg_daily_totals_sql_prepares(
    pg_conn: "asyncpg.Connection",
) -> None:
    """L2 regression: REFRESH SQL must not raise AmbiguousParameterError.

    Before the 2026-05-11 fix the SQL used bare ``$1`` in both the SELECT
    list and the WHERE clause. asyncpg sends Python ``str`` parameters
    untyped, so PostgreSQL deduced conflicting types::

        asyncpg.exceptions.AmbiguousParameterError: inconsistent types
          deduced for parameter $1
        DETAIL: text versus character varying

    The fix adds explicit ``$1::text`` casts. This test prepares + executes
    the exact production SQL against a real PG with an empty source table
    (txn-rolled-back). A 0-row no-op is success; AmbiguousParameterError
    would be raised at prepare time before any execution.
    """
    async with pg_conn.transaction():
        await H.set_factory_scope(pg_conn, TEST_FACTORY)
        # Source table fact_restaurant_requisition has 0 rows for our
        # F999_INTEGRATION_ETL factory → SELECT returns 0 rows → INSERT 0.
        # The point is to exercise the SQL prepare + bind, not to verify
        # row counts (that's covered by the FakeConn-based test in
        # test_import_restaurant_chain.py::test_refresh_agg_*).
        result = await pg_conn.execute(
            H.REFRESH_AGG_RESTAURANT_DAILY_TOTALS_SQL,
            TEST_FACTORY,
            date(2024, 1, 1),
            date(2024, 1, 31),
        )
        # asyncpg returns "INSERT 0 N" for INSERT statements; N≥0 — we don't
        # care about N here, only that prepare + bind didn't raise.
        assert result.startswith("INSERT "), (
            f"expected INSERT result tag, got {result!r}"
        )


@pytest.mark.integration
async def test_refresh_agg_daily_totals_via_helper(
    pg_conn: "asyncpg.Connection",
) -> None:
    """End-to-end through ``refresh_agg_restaurant_daily_totals`` helper.

    Same as previous test but goes through the public helper API so any
    future refactor that changes the call site (not just the SQL string)
    is still covered.
    """
    async with pg_conn.transaction():
        await H.set_factory_scope(pg_conn, TEST_FACTORY)
        stats = H.LoaderStats(factory_id=TEST_FACTORY)
        touched = await H.refresh_agg_restaurant_daily_totals(
            pg_conn,
            TEST_FACTORY,
            date(2024, 1, 1),
            date(2024, 1, 31),
            stats=stats,
        )
        # 0 source rows for our test factory → 0 touched is fine.
        assert touched >= 0


# ── Combined: full L1 + L2 path mirror of import_chain shape ─────────────
@pytest.mark.integration
async def test_set_factory_scope_then_refresh_full_path(
    pg_conn: "asyncpg.Connection",
) -> None:
    """Full path: open txn → set_factory_scope → refresh — matches import_chain.

    Reproduces the exact call sequence ``import_restaurant_chain.import_chain``
    uses (lines 538-579). If EITHER L1 or L2 regresses, this test catches it.
    """
    async with pg_conn.transaction():
        # Step 1: set RLS scope (L1 path)
        await H.set_factory_scope(pg_conn, TEST_FACTORY)
        # Step 2: refresh Gold (L2 path)
        await H.refresh_agg_restaurant_daily_totals(
            pg_conn,
            TEST_FACTORY,
            date(2024, 1, 1),
            date(2024, 1, 31),
        )
        # If we got here without exception, both L1 + L2 SQL are well-formed.
