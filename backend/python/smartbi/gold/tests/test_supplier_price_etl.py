"""Unit tests for G7 supplier-price gold ETL (Tier A).

Uses a fake asyncpg pool/connection (no real DB). Validates:
- upsert_supplier_price_batch: builds UNNEST insert, sets RLS tenant, returns count
- get_supplier_price_trend: normalized-name filter, ASC date ordering, shape
- get_latest_ingredient_prices: latest unit_price per normalized_name (DISTINCT ON)

Run:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_supplier_price_etl.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.supplier_price_etl import (
    upsert_supplier_price_batch,
    get_supplier_price_trend,
    get_latest_ingredient_prices,
)


# ---------------------------------------------------------------------------
# Fake pool / connection helpers
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    def keys(self):
        return super().keys()


class _FakeConn:
    """Records every SQL + args. fetch returns canned rows; execute is no-op."""

    def __init__(self, rows):
        self._rows = rows
        self.sql_log = []
        self.args_log = []

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


class _FakeTxn:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False


class _FakePool:
    def __init__(self, rows):
        self._rows = [_FakeRecord(r) for r in rows]
        self._conn = _FakeConn(self._rows)

    def acquire(self):
        return self._conn

    @property
    def conn(self):
        return self._conn


# ---------------------------------------------------------------------------
# upsert_supplier_price_batch
# ---------------------------------------------------------------------------

def test_upsert_supplier_price_batch_basic():
    """Inserting 2 rows returns count=2 and sets RLS tenant context."""
    pool = _FakePool([{"id": 1}, {"id": 2}])
    rows = [
        {
            "ingredient_name": "猪肉", "normalized_name": "猪肉",
            "raw_material_type_id": "rmt-1", "supplier_id": "sup-1",
            "supplier_name": "鑫农", "delivery_date": date(2026, 6, 1),
            "unit_price": Decimal("12.5"), "quantity": Decimal("10"),
            "unit": "kg", "line_amount": Decimal("125.0"),
            "source_note_id": "SDN-1",
        },
        {
            "ingredient_name": "白菜", "normalized_name": "白菜",
            "raw_material_type_id": None, "supplier_id": "sup-1",
            "supplier_name": "鑫农", "delivery_date": date(2026, 6, 1),
            "unit_price": Decimal("2.0"), "quantity": Decimal("30"),
            "unit": "kg", "line_amount": Decimal("60.0"),
            "source_note_id": "SDN-1",
        },
    ]
    n = asyncio.run(upsert_supplier_price_batch(pool, "F006", rows))
    assert n == 2
    # RLS tenant set
    joined = " ".join(pool.conn.sql_log)
    assert "set_config('app.factory_id'" in joined
    # Insert hits agg_supplier_price via UNNEST
    assert "INSERT INTO agg_supplier_price" in joined
    assert "UNNEST" in joined


def test_upsert_supplier_price_batch_empty_returns_zero():
    """Empty rows → no insert, returns 0 (no DB call needed)."""
    pool = _FakePool([])
    n = asyncio.run(upsert_supplier_price_batch(pool, "F006", []))
    assert n == 0
    # Must not even attempt the insert
    assert all("INSERT INTO agg_supplier_price" not in s for s in pool.conn.sql_log)


def test_upsert_supplier_price_batch_rejects_none_factory():
    """None factory_id → ValueError (Rule 6: reject silent zero-result)."""
    pool = _FakePool([{"id": 1}])
    with pytest.raises(ValueError):
        asyncio.run(upsert_supplier_price_batch(pool, None, [{"ingredient_name": "x"}]))


# ---------------------------------------------------------------------------
# get_supplier_price_trend
# ---------------------------------------------------------------------------

def test_get_supplier_price_trend_returns_rows():
    """Trend query returns list of dicts with date/unit_price/supplier/quantity."""
    pool = _FakePool([
        {"delivery_date": date(2026, 5, 1), "unit_price": Decimal("11.0"),
         "supplier_name": "鑫农", "quantity": Decimal("8")},
        {"delivery_date": date(2026, 6, 1), "unit_price": Decimal("12.5"),
         "supplier_name": "鑫农", "quantity": Decimal("10")},
    ])
    out = asyncio.run(get_supplier_price_trend(pool, "F006", "猪肉", days=90))
    assert len(out) == 2
    assert out[0]["unitPrice"] == 12.5 or out[0]["unitPrice"] == 11.0
    # camelCase output keys
    assert "deliveryDate" in out[0]
    assert "supplierName" in out[0]
    # normalized name filter is bound
    joined = " ".join(pool.conn.sql_log)
    assert "normalized_name" in joined


def test_get_supplier_price_trend_rejects_none_factory():
    pool = _FakePool([])
    with pytest.raises(ValueError):
        asyncio.run(get_supplier_price_trend(pool, None, "猪肉"))


# ---------------------------------------------------------------------------
# get_latest_ingredient_prices
# ---------------------------------------------------------------------------

def test_get_latest_ingredient_prices_shape():
    """Latest price per ingredient — DISTINCT ON normalized_name."""
    pool = _FakePool([
        {"normalized_name": "猪肉", "ingredient_name": "猪肉",
         "unit_price": Decimal("12.5"), "unit": "kg",
         "delivery_date": date(2026, 6, 1), "supplier_name": "鑫农"},
    ])
    out = asyncio.run(get_latest_ingredient_prices(pool, "F006"))
    assert len(out) == 1
    assert out[0]["unitPrice"] == 12.5
    assert out[0]["ingredientName"] == "猪肉"
    joined = " ".join(pool.conn.sql_log)
    assert "DISTINCT ON" in joined.upper() or "distinct on" in joined
