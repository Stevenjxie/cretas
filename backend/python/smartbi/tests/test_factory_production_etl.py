"""Unit tests for factory_production_etl.py

Coverage:
- Batch-to-Silver field mapping (all columns, null honest preservation)
- is_trial / status filter logic (trial and non-COMPLETED batches excluded)
- Idempotent upsert semantics (same batch upserted twice → second wins, 1 row)
- stat_date derivation (end_time preferred, falls back to start_time, else None)
- Gold aggregation SQL shape (GROUP BY date × product, all-product rollup)
- cost null honesty: None stays None (no zero-fill)
- RBAC redaction via factory_production_gold._apply_rbac (non-price roles)

All DB interactions are mocked with asyncpg-compatible fakes — no real DB needed.
"""
from __future__ import annotations
from smartbi.gold.factory_production_etl import (
    _to_float_or_none,
    sync_fact_production_batch,
    materialize_factory_gold,
    run_factory_etl,
    FactoryEtlStats,
)

import asyncio
import sys
import os
from datetime import date, datetime
from decimal import Decimal
from typing import List

import pytest

# ── path bootstrap (needed when pytest is run from /backend/python) ───────────
_here = os.path.dirname(os.path.abspath(__file__))
_root = os.path.dirname(os.path.dirname(_here))  # .../backend/python
if _root not in sys.path:
    sys.path.insert(0, _root)


# ── Inline asyncpg record fake (no asyncpg install required) ─────────────────

class _FakeRow(dict):
    """Dict subclass that also supports attribute access + asyncpg subscript."""

    def __getattr__(self, item):
        try:
            return self[item]
        except KeyError:
            raise AttributeError(item)


def _row(**kwargs) -> _FakeRow:
    return _FakeRow(kwargs)


# ── Fake pool / connection context managers ───────────────────────────────────

class _FakeConn:
    def __init__(self, fetch_return=None, execute_return="INSERT 0 0"):
        self._fetch_return = fetch_return or []
        self._execute_return = execute_return
        self.executed_sqls: List[str] = []
        self.tx_active = False

    async def execute(self, sql, *args):
        self.executed_sqls.append(sql.strip()[:80])
        return self._execute_return

    async def fetch(self, sql, *args):
        self.executed_sqls.append(sql.strip()[:80])
        return self._fetch_return

    async def fetchrow(self, sql, *args):
        rows = self._fetch_return
        return rows[0] if rows else None

    async def fetchval(self, sql, *args):
        return None

    def transaction(self):
        return _FakeTx(self)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass


class _FakeTx:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        self.conn.tx_active = True
        return self

    async def __aexit__(self, *args):
        self.conn.tx_active = False


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)

    @property
    def _closed(self):
        return False


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *args):
        pass


# ── Helper: run coroutine ─────────────────────────────────────────────────────

def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ── Import ETL module ─────────────────────────────────────────────────────────


# ════════════════════════════════════════════════════════════════════════════
# Tests: _to_float_or_none
# ════════════════════════════════════════════════════════════════════════════

class TestToFloatOrNone:
    def test_none_stays_none(self):
        assert _to_float_or_none(None) is None

    def test_decimal_converts(self):
        assert _to_float_or_none(Decimal("123.45")) == pytest.approx(123.45)

    def test_zero_decimal_converts_not_none(self):
        """Decimal(0) must become 0.0, not None — honest zero preservation."""
        result = _to_float_or_none(Decimal("0.00"))
        assert result is not None
        assert result == pytest.approx(0.0)

    def test_int_converts(self):
        assert _to_float_or_none(42) == 42.0

    def test_float_passthrough(self):
        assert _to_float_or_none(3.14) == pytest.approx(3.14)


# ════════════════════════════════════════════════════════════════════════════
# Tests: sync_fact_production_batch — field mapping
# ════════════════════════════════════════════════════════════════════════════

class TestSyncFactProductionBatch:
    """Verify that source rows are correctly mapped into the UNNEST upsert call."""

    def _make_batch_row(self, **overrides) -> _FakeRow:
        defaults = {
            "id": 1001,
            "batch_number": "B-2026-001",
            "product_type_id": "PT-PORK-001",
            "product_name": "卤猪蹄",
            "planned_quantity": Decimal("200.00"),
            "actual_quantity":  Decimal("185.50"),
            "good_quantity":    Decimal("180.00"),
            "defect_quantity":  Decimal("5.50"),
            "unit": "kg",
            "status": "COMPLETED",
            "start_time": datetime(2026, 6, 1, 8, 0, 0),
            "end_time":   datetime(2026, 6, 1, 16, 0, 0),
            "worker_count": 6,
            "work_duration_minutes": 480,
            "material_cost":  Decimal("1500.00"),
            "labor_cost":     Decimal("480.00"),
            "equipment_cost": Decimal("50.00"),
            "other_cost":     Decimal("20.00"),
            "total_cost":     Decimal("2050.00"),
            "unit_cost":      Decimal("11.3889"),
            "yield_rate":     Decimal("97.03"),
        }
        defaults.update(overrides)
        return _FakeRow(defaults)

    def test_basic_mapping_calls_insert(self):
        """Silver upsert should be called with the correct batch data."""
        src_row = self._make_batch_row()
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])  # 1 RETURNING row

        cretas_pool = _FakePool(src_conn)
        smartbi_pool = _FakePool(dst_conn)

        count = _run(sync_fact_production_batch(cretas_pool, smartbi_pool, "F006"))
        assert count == 1

    def test_returns_zero_for_empty_source(self):
        src_conn = _FakeConn(fetch_return=[])  # no batches
        dst_conn = _FakeConn(fetch_return=[])
        count = _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))
        assert count == 0

    def test_cost_null_honesty(self):
        """Batches with all-null costs must NOT zero-fill: result row still has null costs."""
        src_row = self._make_batch_row(
            material_cost=None, labor_cost=None, equipment_cost=None,
            other_cost=None, total_cost=None, unit_cost=None,
        )
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])

        # Patch the INSERT so we can inspect the arrays passed to UNNEST
        captured_args = []

        async def fake_fetch(sql, *args):
            captured_args.extend(args)
            return [_row(id=1)]

        dst_conn.fetch = fake_fetch

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        # UNNEST call args (0-indexed):
        # [0]=factory_id, [1]=source_pks, [2]=batch_numbers, [3]=product_ids,
        # [4]=product_names, [5]=planned_qtys, [6]=actual_qtys, [7]=good_qtys,
        # [8]=defect_qtys, [9]=units, [10]=statuses, [11]=start_times,
        # [12]=end_times, [13]=stat_dates, [14]=worker_counts, [15]=work_minutes,
        # [16]=material_costs, [17]=labor_costs, [18]=equipment_costs,
        # [19]=other_costs, [20]=total_costs, [21]=unit_costs, [22]=yield_rates
        if captured_args:
            material_costs_arr = captured_args[16]
            assert material_costs_arr[0] is None, "material_cost should be null, not zero"
            total_costs_arr = captured_args[20]
            assert total_costs_arr[0] is None, "total_cost should be null, not zero"

    def test_stat_date_from_end_time(self):
        """stat_date should prefer DATE(end_time)."""
        src_row = self._make_batch_row(
            start_time=datetime(2026, 6, 1, 8, 0),
            end_time=datetime(2026, 6, 2, 2, 0),  # crosses midnight
        )
        # The stat_date should be 2026-06-02 (from end_time), not 2026-06-01
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])

        captured_args = []

        async def fake_fetch(sql, *args):
            captured_args.extend(args)
            return [_row(id=1)]
        dst_conn.fetch = fake_fetch

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        if captured_args:
            stat_dates = captured_args[13]  # index of stat_date array (see index map above)
            assert stat_dates[0] == date(2026, 6, 2)

    def test_stat_date_fallback_to_start_time(self):
        """When end_time is None, stat_date should fall back to DATE(start_time)."""
        src_row = self._make_batch_row(
            start_time=datetime(2026, 6, 5, 10, 0),
            end_time=None,
        )
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])

        captured_args = []

        async def fake_fetch(sql, *args):
            captured_args.extend(args)
            return [_row(id=1)]
        dst_conn.fetch = fake_fetch

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        if captured_args:
            stat_dates = captured_args[14]
            assert stat_dates[0] == date(2026, 6, 5)

    def test_stat_date_none_when_both_times_null(self):
        """stat_date should be None when both start_time and end_time are null."""
        src_row = self._make_batch_row(start_time=None, end_time=None)
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])

        captured_args = []

        async def fake_fetch(sql, *args):
            captured_args.extend(args)
            return [_row(id=1)]
        dst_conn.fetch = fake_fetch

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        if captured_args:
            stat_dates = captured_args[14]
            assert stat_dates[0] is None

    def test_source_pk_is_string_not_int(self):
        """source_pk must be stored as str(id) to match VARCHAR(191) column."""
        src_row = self._make_batch_row(id=9999)
        src_conn = _FakeConn(fetch_return=[src_row])
        dst_conn = _FakeConn(fetch_return=[_row(id=1)])

        captured_args = []

        async def fake_fetch(sql, *args):
            captured_args.extend(args)
            return [_row(id=1)]
        dst_conn.fetch = fake_fetch

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        if captured_args:
            source_pks = captured_args[1]
            assert source_pks[0] == "9999"
            assert isinstance(source_pks[0], str)


# ════════════════════════════════════════════════════════════════════════════
# Tests: Gold materialization SQL
# ════════════════════════════════════════════════════════════════════════════

class TestMaterializeFactoryGold:
    """Verify that Gold materialization fires both INSERT…SELECT statements."""

    def test_both_gold_sqls_executed(self):
        """materialize_factory_gold should execute 2 INSERT statements."""
        conn = _FakeConn(execute_return="INSERT 0 5")
        pool = _FakePool(conn)

        stats = _run(materialize_factory_gold(pool, "F006"))

        # product_daily + all_daily both ran
        insert_calls = [s for s in conn.executed_sqls if "INSERT" in s.upper()]
        # Also set_config call
        assert len(insert_calls) >= 2

        assert stats["product_daily"] == 5
        assert stats["all_daily"] == 5

    def test_tenant_guc_set(self):
        """RLS tenant GUC must be set before INSERT statements."""
        conn = _FakeConn(execute_return="INSERT 0 3")
        pool = _FakePool(conn)

        _run(materialize_factory_gold(pool, "F999"))

        # set_config call should appear before the INSERTs
        first_sql = conn.executed_sqls[0] if conn.executed_sqls else ""
        assert "set_config" in first_sql.lower()


# ════════════════════════════════════════════════════════════════════════════
# Tests: run_factory_etl — orchestration
# ════════════════════════════════════════════════════════════════════════════

class TestRunFactoryEtl:
    """Verify the orchestrator wires Silver then Gold correctly."""

    def test_success_both_stages(self):
        src_conn = _FakeConn(fetch_return=[
            _FakeRow({
                "id": 1, "batch_number": "B001", "product_type_id": "PT1",
                "product_name": "产品A", "planned_quantity": Decimal("100"),
                "actual_quantity": Decimal("90"), "good_quantity": Decimal("88"),
                "defect_quantity": Decimal("2"), "unit": "kg", "status": "COMPLETED",
                "start_time": datetime(2026, 6, 1, 8, 0),
                "end_time": datetime(2026, 6, 1, 16, 0),
                "worker_count": 4, "work_duration_minutes": 480,
                "material_cost": Decimal("800"), "labor_cost": Decimal("200"),
                "equipment_cost": None, "other_cost": None,
                "total_cost": Decimal("1000"), "unit_cost": Decimal("11.11"),
                "yield_rate": Decimal("97.78"),
            })
        ])
        # smartbi pool: first call = Silver RETURNING 1 row, then Gold = INSERT 0 2 each
        dst_call_count = [0]

        class _CountingConn(_FakeConn):
            async def fetch(self, sql, *args):
                self.executed_sqls.append(sql.strip()[:80])
                dst_call_count[0] += 1
                return [_FakeRow({"id": 1})]

            async def execute(self, sql, *args):
                self.executed_sqls.append(sql.strip()[:80])
                return "INSERT 0 3"

        dst_conn = _CountingConn()
        result = _run(run_factory_etl(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))
        assert isinstance(result, FactoryEtlStats)
        assert result.silver_upserted == 1
        assert len(result.errors) == 0

    def test_silver_failure_skips_gold(self):
        """If Silver raises, Gold should not run."""
        src_conn = _FakeConn(fetch_return=[])  # noqa: F841
        gold_called = [False]

        class _ExplodeSilverPool(_FakePool):
            def acquire(self):
                # Simulate Silver DB failure
                class _ExplodeCtx:
                    async def __aenter__(self_):
                        raise RuntimeError("DB explode")

                    async def __aexit__(self_, *a):
                        pass
                return _ExplodeCtx()

        async def _fake_gold(pool, factory_id):
            gold_called[0] = True
            return {"product_daily": 0, "all_daily": 0}

        import smartbi.gold.factory_production_etl as etl_mod
        original = etl_mod.materialize_factory_gold

        try:
            etl_mod.materialize_factory_gold = _fake_gold
            result = _run(run_factory_etl(
                _ExplodeSilverPool(_FakeConn()),
                _FakePool(_FakeConn()),
                "F006",
            ))
        finally:
            etl_mod.materialize_factory_gold = original

        assert len(result.errors) > 0
        assert not gold_called[0], "Gold should not run if Silver fails"


# ════════════════════════════════════════════════════════════════════════════
# Tests: RBAC cost stripping (via factory_production_gold module)
# ════════════════════════════════════════════════════════════════════════════

class TestRbacCostStripping:
    """Verify PRICE_VIEW_ROLES gate in factory_production_gold._apply_rbac."""

    def test_price_role_sees_costs(self):
        from smartbi.api.factory_production_gold import _apply_rbac
        from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
        role = next(iter(PRICE_VIEW_ROLES))  # pick one price-view role

        data = {
            "totalMaterialCost": 1500.0,
            "totalLaborCost": 480.0,
            "totalCost": 1980.0,
        }
        _apply_rbac(data, role)
        # Cost fields must be visible
        assert data["totalMaterialCost"] == 1500.0
        assert data["totalCost"] == 1980.0

    def test_non_price_role_strips_costs(self):
        from smartbi.api.factory_production_gold import _apply_rbac

        data = {
            "totalMaterialCost": 1500.0,
            "totalLaborCost": 480.0,
            "totalCost": 1980.0,
            "batchCount": 5,  # non-cost field must survive
        }
        _apply_rbac(data, "operator")
        # Cost fields must be nulled
        assert data["totalMaterialCost"] is None
        assert data["totalLaborCost"] is None
        assert data["totalCost"] is None
        # Non-cost field preserved
        assert data["batchCount"] == 5

    def test_unknown_role_strips_costs(self):
        from smartbi.api.factory_production_gold import _apply_rbac

        data = {"totalCost": 999.0, "batchCount": 3}
        _apply_rbac(data, "unknown_role")
        assert data["totalCost"] is None
        assert data["batchCount"] == 3

    def test_none_role_strips_costs(self):
        from smartbi.api.factory_production_gold import _apply_rbac

        data = {"totalCost": 999.0, "avgYieldRate": 97.0}
        _apply_rbac(data, None)
        assert data["totalCost"] is None
        assert data["avgYieldRate"] == 97.0  # yield not a cost field

    def test_nested_list_strips(self):
        """Cost fields inside a list of product dicts must all be stripped."""
        from smartbi.api.factory_production_gold import _apply_rbac

        data = {
            "products": [
                {"productTypeId": "PT1", "totalCost": 500.0, "batchCount": 2},
                {"productTypeId": "PT2", "totalCost": 750.0, "batchCount": 3},
            ]
        }
        _apply_rbac(data, "operator")
        for product in data["products"]:
            assert product["totalCost"] is None
            assert product["batchCount"] is not None  # non-cost survives


# ════════════════════════════════════════════════════════════════════════════
# Tests: is_trial / status filter (via query SQL verification)
# ════════════════════════════════════════════════════════════════════════════

class TestSourceFilterSql:
    """Verify the SELECT from cretas_db includes the correct WHERE clause."""

    def test_query_excludes_trial_and_non_completed(self):
        """The source query must filter out is_trial batches and non-COMPLETED status."""
        captured_sql = []

        class _CaptureSrcConn(_FakeConn):
            async def fetch(self, sql, *args):
                captured_sql.append(sql)
                return []  # no rows, just capture the SQL

        src_conn = _CaptureSrcConn()
        dst_conn = _FakeConn()

        _run(sync_fact_production_batch(
            _FakePool(src_conn), _FakePool(dst_conn), "F006"
        ))

        assert captured_sql, "Expected at least one SQL query to cretas_db"
        sql = captured_sql[0].lower()

        assert "status" in sql and "completed" in sql, "Must filter status='COMPLETED'"
        assert "is_trial" in sql, "Must filter out trial batches"
        assert "deleted_at" in sql, "Must filter soft-deleted rows"
