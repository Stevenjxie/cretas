"""Unit tests for supplier_price_ingest_etl.py

Coverage:
- material_batch → upsert row field mapping (all used columns)
- JOIN 不到名字时诚实兜底（ingredient_name=material_type_id，supplier_name=supplier_id）
- 幂等：已存在 source_note_id 跳过（skipped_existing++, not inserted）
- unit_price IS NULL rows filtered out by the SQL (enforced by mock returning none)
- delivery_date logic: purchase_date preferred over inbound_date; both None → skip
- factory_id None raises ValueError
- orchestrator stats accumulation
- upsert_supplier_price_batch called with correct rows (mock verify)

All DB interactions mocked — no real DB needed.
"""
from __future__ import annotations

import asyncio
import sys
import os
from datetime import date
from decimal import Decimal
from typing import Any, List, Optional, Set
from unittest.mock import AsyncMock, patch

import pytest

# ── path bootstrap ────────────────────────────────────────────────────────────
_here = os.path.dirname(os.path.abspath(__file__))
_root = os.path.dirname(os.path.dirname(_here))  # .../backend/python
if _root not in sys.path:
    sys.path.insert(0, _root)


# ── Fake asyncpg rows / pool / connection ─────────────────────────────────────

class _FakeRow(dict):
    """Dict subclass with attribute-style access + .get() for asyncpg record compat."""
    def __getattr__(self, item):
        try:
            return self[item]
        except KeyError:
            raise AttributeError(item)


def _row(**kwargs) -> _FakeRow:
    return _FakeRow(kwargs)


class _FakeConn:
    """Fake asyncpg connection.

    cretas_conn: fetch always returns fetch_rows (material_batches result).
    smartbi_conn: fetch always returns fetch_rows (existing source_note_ids result).
    Keep them as separate instances so each pool returns the right data.
    """
    def __init__(self, fetch_rows=None, execute_return="SELECT 1"):
        self._fetch_rows = fetch_rows if fetch_rows is not None else []
        self._execute_return = execute_return
        self.executed_sqls: List[str] = []

    async def execute(self, sql, *args):
        self.executed_sqls.append(sql.strip()[:100])
        return self._execute_return

    async def fetch(self, sql, *args):
        self.executed_sqls.append(sql.strip()[:100])
        return self._fetch_rows

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
        return self

    async def __aexit__(self, *args):
        pass


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *args):
        pass


# ── Helper ────────────────────────────────────────────────────────────────────

def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ── Import module under test ──────────────────────────────────────────────────

from smartbi.gold.supplier_price_ingest_etl import (
    _source_note_id,
    _to_float_safe,
    _to_upsert_row,
    run_supplier_price_ingest,
)


# ═════════════════════════════════════════════════════════════════════════════
# Tests: helpers
# ═════════════════════════════════════════════════════════════════════════════

class TestHelpers:
    def test_source_note_id_format(self):
        assert _source_note_id("abc-123") == "mb:abc-123"
        assert _source_note_id(42) == "mb:42"

    def test_to_float_safe_none(self):
        assert _to_float_safe(None) is None

    def test_to_float_safe_decimal(self):
        assert _to_float_safe(Decimal("12.50")) == pytest.approx(12.50)

    def test_to_float_safe_zero(self):
        """Decimal("0") must convert to 0.0, not None (honest zero)."""
        result = _to_float_safe(Decimal("0.00"))
        assert result is not None
        assert result == pytest.approx(0.0)


# ═════════════════════════════════════════════════════════════════════════════
# Tests: _to_upsert_row — field mapping
# ═════════════════════════════════════════════════════════════════════════════

class TestToUpsertRow:
    """Verify material_batch → upsert row transformation."""

    def _make_raw(self, **overrides) -> _FakeRow:
        defaults = {
            "id":               "batch-uuid-001",
            "factory_id":       "F006",
            "material_type_id": "rmt-uuid-pork",
            "supplier_id":      "sup-uuid-abc",
            "purchase_date":    date(2026, 6, 1),
            "inbound_date":     date(2026, 6, 2),
            "unit_price":       Decimal("25.80"),
            "quantity":         Decimal("100.00"),
            "unit":             "kg",
            "material_name":    "猪前腿",
            "supplier_name":    "鑫源肉制品",
        }
        defaults.update(overrides)
        return _FakeRow(defaults)

    def test_basic_mapping(self):
        raw = self._make_raw()
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["source_note_id"]      == "mb:batch-uuid-001"
        assert row["raw_material_type_id"] == "rmt-uuid-pork"
        assert row["supplier_id"]          == "sup-uuid-abc"
        assert row["supplier_name"]        == "鑫源肉制品"
        assert row["ingredient_name"]      == "猪前腿"
        assert row["delivery_date"]        == date(2026, 6, 1)  # purchase_date preferred
        assert row["unit_price"]           == pytest.approx(25.80)
        assert row["quantity"]             == pytest.approx(100.0)
        assert row["unit"]                 == "kg"
        assert row["line_amount"]          is None

    def test_purchase_date_preferred_over_inbound(self):
        raw = self._make_raw(purchase_date=date(2026, 5, 10), inbound_date=date(2026, 5, 15))
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["delivery_date"] == date(2026, 5, 10)

    def test_falls_back_to_inbound_date_when_no_purchase(self):
        raw = self._make_raw(purchase_date=None, inbound_date=date(2026, 5, 15))
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["delivery_date"] == date(2026, 5, 15)

    def test_no_date_returns_none(self):
        """Both purchase_date and inbound_date are None → skip (can't place on timeline)."""
        raw = self._make_raw(purchase_date=None, inbound_date=None)
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is None

    def test_existing_source_id_returns_none(self):
        """Already-ingested row must be skipped (idempotent)."""
        raw = self._make_raw()
        existing = {"mb:batch-uuid-001"}
        row = _to_upsert_row(raw, existing_ids=existing)
        assert row is None

    def test_join_miss_ingredient_name_fallback(self):
        """If raw_material_types JOIN misses (material_name=None), use material_type_id."""
        raw = self._make_raw(material_name=None)
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["ingredient_name"] == "rmt-uuid-pork"  # honest ID fallback, not fabricated

    def test_join_miss_supplier_name_fallback(self):
        """If suppliers JOIN misses (supplier_name=None), use supplier_id."""
        raw = self._make_raw(supplier_name=None)
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["supplier_name"] == "sup-uuid-abc"  # honest ID fallback

    def test_null_supplier_id_fallback(self):
        """supplier_id=None (no supplier linked) → supplier_name=None, not crash."""
        raw = self._make_raw(supplier_id=None, supplier_name=None)
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["supplier_id"] is None
        assert row["supplier_name"] is None

    def test_quantity_none_preserved(self):
        """quantity may be None for some batches — preserve honestly."""
        raw = self._make_raw(quantity=None)
        row = _to_upsert_row(raw, existing_ids=set())
        assert row is not None
        assert row["quantity"] is None


# ═════════════════════════════════════════════════════════════════════════════
# Tests: run_supplier_price_ingest — orchestrator
# ═════════════════════════════════════════════════════════════════════════════

class TestRunSupplierPriceIngest:
    """Integration-style tests with mocked pools and patched upsert."""

    def _make_batch_row(self, batch_id="b1", **overrides) -> _FakeRow:
        defaults = {
            "id":               batch_id,
            "factory_id":       "F006",
            "material_type_id": f"rmt-{batch_id}",
            "supplier_id":      f"sup-{batch_id}",
            "purchase_date":    date(2026, 6, 1),
            "inbound_date":     date(2026, 6, 2),
            "unit_price":       Decimal("20.00"),
            "quantity":         Decimal("50.00"),
            "unit":             "kg",
            "material_name":    f"原料{batch_id}",
            "supplier_name":    f"供应商{batch_id}",
        }
        defaults.update(overrides)
        return _FakeRow(defaults)

    def test_factory_id_none_raises(self):
        with pytest.raises(ValueError, match="factory_id required"):
            _run(run_supplier_price_ingest(None, None, None))

    def test_factory_id_empty_raises(self):
        with pytest.raises(ValueError, match="factory_id required"):
            _run(run_supplier_price_ingest(None, None, ""))

    def test_no_rows_returns_zero_inserted(self):
        cretas_conn = _FakeConn(fetch_rows=[])
        smartbi_conn = _FakeConn(fetch_rows=[])

        stats = _run(run_supplier_price_ingest(
            _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
        ))
        assert stats["total_read"] == 0
        assert stats["inserted"] == 0
        assert stats["errors"] == []

    def test_new_rows_are_inserted(self):
        """Two new rows → both should be passed to upsert (mocked)."""
        batch_rows = [self._make_batch_row("b1"), self._make_batch_row("b2")]
        cretas_conn = _FakeConn(fetch_rows=batch_rows)
        # No existing ids in smartbi
        smartbi_conn = _FakeConn(fetch_rows=[])

        captured_upsert_calls = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            captured_upsert_calls.append((factory_id, rows))
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            stats = _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert stats["total_read"] == 2
        assert stats["inserted"] == 2
        assert stats["skipped_existing"] == 0
        assert stats["skipped_no_date"] == 0
        assert stats["errors"] == []
        assert len(captured_upsert_calls) == 1
        factory_id, rows = captured_upsert_calls[0]
        assert factory_id == "F006"
        assert {r["source_note_id"] for r in rows} == {"mb:b1", "mb:b2"}

    def test_idempotent_skips_existing_source_ids(self):
        """Rows already in agg_supplier_price must be skipped, not double-inserted."""
        batch_rows = [self._make_batch_row("b1"), self._make_batch_row("b2")]
        # b1 already ingested
        existing_rows = [_FakeRow({"source_note_id": "mb:b1"})]
        cretas_conn = _FakeConn(fetch_rows=batch_rows)
        # smartbi_conn returns existing ids on fetch (used by _load_existing_source_ids)
        smartbi_conn = _FakeConn(fetch_rows=existing_rows)

        captured_rows = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            captured_rows.extend(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            stats = _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert stats["total_read"] == 2
        assert stats["skipped_existing"] == 1
        assert stats["inserted"] == 1
        assert stats["errors"] == []
        # Only b2 should be upserted
        assert captured_rows[0]["source_note_id"] == "mb:b2"

    def test_all_existing_nothing_inserted(self):
        """All rows already exist → upsert never called."""
        batch_rows = [self._make_batch_row("b1")]
        existing_rows = [_FakeRow({"source_note_id": "mb:b1"})]
        cretas_conn = _FakeConn(fetch_rows=batch_rows)
        smartbi_conn = _FakeConn(fetch_rows=existing_rows)

        upsert_called = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            upsert_called.append(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            stats = _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert stats["inserted"] == 0
        assert stats["skipped_existing"] == 1
        assert upsert_called == []  # upsert never triggered

    def test_rows_without_date_are_skipped(self):
        """Rows with no purchase_date and no inbound_date are skipped (cannot place on timeline)."""
        row_no_date = self._make_batch_row("b1", purchase_date=None, inbound_date=None)
        row_ok = self._make_batch_row("b2")
        cretas_conn = _FakeConn(fetch_rows=[row_no_date, row_ok])
        smartbi_conn = _FakeConn(fetch_rows=[])

        inserted_rows = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            inserted_rows.extend(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            stats = _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert stats["total_read"] == 2
        assert stats["skipped_no_date"] == 1
        assert stats["inserted"] == 1
        assert inserted_rows[0]["source_note_id"] == "mb:b2"

    def test_ingredient_name_fallback_on_join_miss(self):
        """When material_name is None (JOIN miss), ingredient_name must be the raw type ID."""
        row = self._make_batch_row("b1", material_name=None)
        cretas_conn = _FakeConn(fetch_rows=[row])
        smartbi_conn = _FakeConn(fetch_rows=[])

        captured_rows = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            captured_rows.extend(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert len(captured_rows) == 1
        # Honest fallback — raw ID string, not a fabricated name
        assert captured_rows[0]["ingredient_name"] == "rmt-b1"

    def test_supplier_name_fallback_on_join_miss(self):
        """When supplier_name is None (JOIN miss), supplier_name falls back to supplier_id."""
        row = self._make_batch_row("b1", supplier_name=None)
        cretas_conn = _FakeConn(fetch_rows=[row])
        smartbi_conn = _FakeConn(fetch_rows=[])

        captured_rows = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            captured_rows.extend(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert len(captured_rows) == 1
        assert captured_rows[0]["supplier_name"] == "sup-b1"  # supplier_id fallback

    def test_correct_upsert_row_fields(self):
        """Verify all key fields in the passed upsert row are correct."""
        row = self._make_batch_row(
            "x99",
            purchase_date=date(2026, 3, 15),
            unit_price=Decimal("18.50"),
            quantity=Decimal("200.00"),
            unit="kg",
            material_name="猪后腿",
            supplier_name="德兴肉业",
            material_type_id="rmt-xyz",
            supplier_id="sup-xyz",
        )
        cretas_conn = _FakeConn(fetch_rows=[row])
        smartbi_conn = _FakeConn(fetch_rows=[])

        captured_rows = []

        async def fake_upsert(smartbi_pool, factory_id, rows):
            captured_rows.extend(rows)
            return len(rows)

        with patch(
            "smartbi.gold.supplier_price_ingest_etl.upsert_supplier_price_batch",
            side_effect=fake_upsert,
        ):
            _run(run_supplier_price_ingest(
                _FakePool(cretas_conn), _FakePool(smartbi_conn), "F006"
            ))

        assert len(captured_rows) == 1
        r = captured_rows[0]
        assert r["source_note_id"]       == "mb:x99"
        assert r["raw_material_type_id"] == "rmt-xyz"
        assert r["supplier_id"]          == "sup-xyz"
        assert r["supplier_name"]        == "德兴肉业"
        assert r["ingredient_name"]      == "猪后腿"
        assert r["delivery_date"]        == date(2026, 3, 15)
        assert r["unit_price"]           == pytest.approx(18.50)
        assert r["quantity"]             == pytest.approx(200.0)
        assert r["unit"]                 == "kg"
        assert r["line_amount"]          is None
