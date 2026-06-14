"""Unit tests for sync_fact_production_report (A2 — 工序级成本 Silver ETL).

Validates:
1.  Field mapping: all columns wired correctly from source dict to INSERT args
2.  NULL cost honesty: labor_cost / material_cost stay NULL; NOT coerced to 0
3.  cost_source labelling:
      - 'reported' when EITHER labor_cost OR material_cost is non-null
      - None when BOTH are None
4.  yield_rate computation (output / input * 100), including:
      - Normal case
      - input_qty = None → None
      - input_qty = 0   → None (division guard)
      - output_qty = None → None
5.  Empty source → returns 0 without hitting INSERT
6.  None factory_id → ValueError (Rule 6: no silent zero-result)
7.  RLS tenant set (set_config app.factory_id called)
8.  UNNEST INSERT targets fact_production_report

Run:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_factory_production_report_etl.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal
from typing import Any, List, Optional

import pytest

from smartbi.gold.factory_production_etl import sync_fact_production_report


# ---------------------------------------------------------------------------
# Fake pool / connection helpers (mirrors test_supplier_price_etl.py pattern)
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    """Dict that quacks like asyncpg Record."""


class _FakeTxn:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakeConn:
    """Records every SQL + args. fetch returns canned rows; execute is no-op."""

    def __init__(self, rows):
        self._rows = rows
        self.sql_log: List[str] = []
        self.args_log: List[Any] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        return None

    async def fetch(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        return self._rows

    async def fetchval(self, sql, *a, **k):
        self.sql_log.append(sql)
        self.args_log.append(a)
        return len(self._rows)

    def transaction(self):
        return _FakeTxn()


class _FakePool:
    def __init__(self, rows):
        self._rows = [_FakeRecord(r) for r in rows]
        self._conn = _FakeConn(self._rows)

    def acquire(self):
        return self._conn

    @property
    def conn(self):
        return self._conn


def _make_cretas_row(
    id_: int = 1,
    batch_id: Optional[int] = 100,
    process_order: Optional[int] = 1,
    process_category: str = "分切",
    report_date=date(2026, 6, 1),
    report_kind: Optional[str] = "OUTPUT",
    input_quantity: Optional[Decimal] = Decimal("100.00"),
    output_quantity: Optional[Decimal] = Decimal("85.00"),
    good_quantity: Optional[Decimal] = Decimal("83.00"),
    total_work_minutes: Optional[int] = 120,
    total_workers: Optional[int] = 3,
    labor_cost: Optional[Decimal] = None,
    material_cost: Optional[Decimal] = None,
) -> dict:
    return {
        "id": id_,
        "batch_id": batch_id,
        "process_order": process_order,
        "process_category": process_category,
        "report_date": report_date,
        "report_kind": report_kind,
        "input_quantity": input_quantity,
        "output_quantity": output_quantity,
        "good_quantity": good_quantity,
        "total_work_minutes": total_work_minutes,
        "total_workers": total_workers,
        "labor_cost": labor_cost,
        "material_cost": material_cost,
    }


# ---------------------------------------------------------------------------
# Helper: extract the positional args passed to the INSERT FROM UNNEST call
# ---------------------------------------------------------------------------

def _get_insert_args(pool: _FakePool):
    """Return the (factory_id, *arrays) args from the first INSERT call."""
    for i, sql in enumerate(pool.conn.sql_log):
        if "INSERT INTO fact_production_report" in sql:
            return pool.conn.args_log[i]
    return None


# ---------------------------------------------------------------------------
# Test 1: basic field mapping — one row
# ---------------------------------------------------------------------------

def test_basic_single_row_mapping():
    """One YIELD report → upserted count=1; key arrays wired correctly."""
    src_pool = _FakePool([_make_cretas_row(
        id_=42, batch_id=7, process_order=2, process_category="焯水",
        input_quantity=Decimal("90.00"), output_quantity=Decimal("72.00"),
        good_quantity=Decimal("70.00"), total_work_minutes=60, total_workers=2,
        report_kind="OUTPUT",
    )])
    dst_pool = _FakePool([{"id": 1}])  # simulate RETURNING id

    n = asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    assert n == 1

    args = _get_insert_args(dst_pool)
    assert args is not None

    # args layout: (factory_id, source_pks, batch_ids, process_orders,
    #               process_categories, report_dates, report_kinds,
    #               input_qtys, output_qtys, good_qtys, yield_rates,
    #               total_work_minutes, total_workers, labor_costs, material_costs, cost_sources)
    factory_id = args[0]
    source_pks = args[1]
    batch_ids = args[2]
    proc_orders = args[3]
    proc_cats = args[4]
    input_qtys = args[7]
    output_qtys = args[8]
    good_qtys = args[9]
    yield_rates = args[10]

    assert factory_id == "F006"
    assert source_pks == ["42"]
    assert batch_ids == ["7"]
    assert proc_orders == [2]
    assert proc_cats == ["焯水"]
    assert input_qtys == [90.0]
    assert output_qtys == [72.0]
    assert good_qtys == [70.0]
    # yield_rate = 72 / 90 * 100 = 80.0
    assert yield_rates[0] is not None
    assert abs(yield_rates[0] - 80.0) < 0.001


# ---------------------------------------------------------------------------
# Test 2: NULL cost honesty — cost_source = None when both costs are None
# ---------------------------------------------------------------------------

def test_null_costs_not_coerced_to_zero():
    """labor_cost=None, material_cost=None → cost arrays are None, cost_source=None."""
    row = _make_cretas_row(labor_cost=None, material_cost=None)
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    args = _get_insert_args(dst_pool)
    labor_costs = args[13]
    material_costs = args[14]
    cost_sources = args[15]

    assert labor_costs == [None], "labor_cost must stay NULL — not coerced to 0"
    assert material_costs == [None], "material_cost must stay NULL — not coerced to 0"
    assert cost_sources == [None], "cost_source must be None when both costs are None"


# ---------------------------------------------------------------------------
# Test 3a: cost_source = 'reported' when labor_cost is non-null
# ---------------------------------------------------------------------------

def test_cost_source_reported_labor_only():
    """labor_cost set, material_cost=None → cost_source='reported'."""
    row = _make_cretas_row(labor_cost=Decimal("150.00"), material_cost=None)
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    args = _get_insert_args(dst_pool)
    cost_sources = args[15]
    assert cost_sources == ["reported"]


# ---------------------------------------------------------------------------
# Test 3b: cost_source = 'reported' when material_cost is non-null
# ---------------------------------------------------------------------------

def test_cost_source_reported_material_only():
    """material_cost set, labor_cost=None → cost_source='reported'."""
    row = _make_cretas_row(labor_cost=None, material_cost=Decimal("200.00"))
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    args = _get_insert_args(dst_pool)
    cost_sources = args[15]
    assert cost_sources == ["reported"]


# ---------------------------------------------------------------------------
# Test 3c: cost_source = 'reported' when BOTH costs are non-null
# ---------------------------------------------------------------------------

def test_cost_source_reported_both_costs():
    """Both labor_cost and material_cost set → cost_source='reported'."""
    row = _make_cretas_row(labor_cost=Decimal("150.00"), material_cost=Decimal("200.00"))
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    args = _get_insert_args(dst_pool)
    cost_sources = args[15]
    assert cost_sources == ["reported"]


# ---------------------------------------------------------------------------
# Test 4: yield_rate computation variants
# ---------------------------------------------------------------------------

def test_yield_rate_normal():
    """output=72, input=90 → yield_rate ≈ 80.0."""
    row = _make_cretas_row(input_quantity=Decimal("90"), output_quantity=Decimal("72"))
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    args = _get_insert_args(dst_pool)
    yr = args[10][0]
    assert yr is not None
    assert abs(yr - 80.0) < 0.01


def test_yield_rate_none_when_input_is_none():
    """input_quantity=None → yield_rate must be None (no division by zero)."""
    row = _make_cretas_row(input_quantity=None, output_quantity=Decimal("72"))
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    args = _get_insert_args(dst_pool)
    assert args[10][0] is None


def test_yield_rate_none_when_input_is_zero():
    """input_quantity=0 → yield_rate must be None (division guard)."""
    row = _make_cretas_row(input_quantity=Decimal("0"), output_quantity=Decimal("72"))
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    args = _get_insert_args(dst_pool)
    assert args[10][0] is None


def test_yield_rate_none_when_output_is_none():
    """output_quantity=None → yield_rate must be None."""
    row = _make_cretas_row(input_quantity=Decimal("90"), output_quantity=None)
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    args = _get_insert_args(dst_pool)
    assert args[10][0] is None


# ---------------------------------------------------------------------------
# Test 5: empty source → returns 0, no INSERT attempted
# ---------------------------------------------------------------------------

def test_empty_source_returns_zero():
    """No YIELD reports → n=0 and INSERT not attempted."""
    src_pool = _FakePool([])
    dst_pool = _FakePool([])

    n = asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    assert n == 0
    assert all("INSERT INTO fact_production_report" not in s for s in dst_pool.conn.sql_log)


# ---------------------------------------------------------------------------
# Test 6: None factory_id → ValueError
# ---------------------------------------------------------------------------

def test_none_factory_id_raises():
    """factory_id=None → ValueError (Rule 6: no silent zero-result)."""
    src_pool = _FakePool([_make_cretas_row()])
    dst_pool = _FakePool([{"id": 1}])

    with pytest.raises(ValueError, match="factory_id is required"):
        asyncio.run(sync_fact_production_report(src_pool, dst_pool, None))


# ---------------------------------------------------------------------------
# Test 7: RLS tenant set
# ---------------------------------------------------------------------------

def test_rls_tenant_set():
    """set_config('app.factory_id', ...) called inside transaction."""
    src_pool = _FakePool([_make_cretas_row()])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    joined = " ".join(dst_pool.conn.sql_log)
    assert "set_config('app.factory_id'" in joined


# ---------------------------------------------------------------------------
# Test 8: INSERT targets fact_production_report with UNNEST
# ---------------------------------------------------------------------------

def test_insert_targets_correct_table():
    """INSERT statement targets fact_production_report and uses UNNEST."""
    src_pool = _FakePool([_make_cretas_row()])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    joined = " ".join(dst_pool.conn.sql_log)
    assert "INSERT INTO fact_production_report" in joined
    assert "UNNEST" in joined
    assert "ON CONFLICT" in joined


# ---------------------------------------------------------------------------
# Test 9: multi-row batch — mixed cost coverage, correct per-row cost_source
# ---------------------------------------------------------------------------

def test_multi_row_mixed_cost_sources():
    """3 rows: no cost / labor only / both costs → cost_source=[None,'reported','reported']."""
    rows = [
        _make_cretas_row(id_=1, labor_cost=None, material_cost=None),
        _make_cretas_row(id_=2, labor_cost=Decimal("100"), material_cost=None),
        _make_cretas_row(id_=3, labor_cost=Decimal("50"), material_cost=Decimal("200")),
    ]
    src_pool = _FakePool(rows)
    dst_pool = _FakePool([{"id": 1}, {"id": 2}, {"id": 3}])

    n = asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))
    assert n == 3

    args = _get_insert_args(dst_pool)
    cost_sources = args[15]
    assert cost_sources == [None, "reported", "reported"]


# ---------------------------------------------------------------------------
# Test 10: batch_id=None is preserved (HOURS-type reports have no batch)
# ---------------------------------------------------------------------------

def test_null_batch_id_preserved():
    """batch_id=None stays None in the INSERT args."""
    row = _make_cretas_row(id_=5, batch_id=None)
    src_pool = _FakePool([row])
    dst_pool = _FakePool([{"id": 1}])

    asyncio.run(sync_fact_production_report(src_pool, dst_pool, "F006"))

    args = _get_insert_args(dst_pool)
    batch_ids = args[2]
    assert batch_ids == [None]
