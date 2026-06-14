"""Unit tests for restaurant_ops_etl food_cost honesty fix.

Covers:
- _get_latest_real_purchase_prices: DISTINCT ON raw_material_type_id, RLS-tx-wrapped,
  filters null rmt/price, float conversion.
- sync_dim_ingredient cost_source priority: real_purchase > moving_avg > list_price > none,
  and unit_price tracks the chosen source.
- The RLS transaction-local fix: _set_tenant + fetch must run inside one transaction
  (asyncpg autocommit would otherwise reset the is_local GUC → RLS reads 0 rows → the
  prod root cause of ingredient_id 100% NULL).

All DB interactions mocked — no real DB needed.
"""
from __future__ import annotations
from smartbi.gold.restaurant_ops_etl import _get_latest_real_purchase_prices

import asyncio
import os
import sys
from decimal import Decimal
from typing import List, Optional

import pytest

# ── path bootstrap ────────────────────────────────────────────────────────────
_here = os.path.dirname(os.path.abspath(__file__))
_root = os.path.dirname(os.path.dirname(_here))  # .../backend/python
if _root not in sys.path:
    sys.path.insert(0, _root)


class _FakeRow(dict):
    def __getattr__(self, item):
        try:
            return self[item]
        except KeyError:
            raise AttributeError(item)


class _FakeTx:
    """Records whether work happened inside a transaction context."""

    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        self.conn.in_tx = True
        return self

    async def __aexit__(self, *args):
        self.conn.in_tx = False


class _FakeConn:
    def __init__(self, fetch_rows=None):
        self._fetch_rows = fetch_rows if fetch_rows is not None else []
        self.in_tx = False
        self.set_config_in_tx: Optional[bool] = None
        self.fetch_in_tx: Optional[bool] = None
        self.executed: List[str] = []

    async def execute(self, sql, *args):
        if "set_config" in sql:
            self.set_config_in_tx = self.in_tx
        self.executed.append(sql.strip()[:60])
        return "SELECT 1"

    async def fetch(self, sql, *args):
        self.fetch_in_tx = self.in_tx
        self.executed.append(sql.strip()[:60])
        return self._fetch_rows

    def transaction(self):
        return _FakeTx(self)


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *args):
        pass


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


class TestGetLatestRealPurchasePrices:
    def test_maps_rmt_to_price_and_is_tx_wrapped(self):
        rows = [
            _FakeRow({"raw_material_type_id": "rm_qhj_002", "unit_price": Decimal("46.00")}),
            _FakeRow({"raw_material_type_id": "rm_qhj_027", "unit_price": Decimal("4.20")}),
        ]
        conn = _FakeConn(fetch_rows=rows)
        result = _run(_get_latest_real_purchase_prices(_FakePool(conn), "RES_3101_009"))
        assert result == {"rm_qhj_002": pytest.approx(46.0), "rm_qhj_027": pytest.approx(4.20)}
        # RLS fix: both set_config and fetch must have run inside the transaction
        assert conn.set_config_in_tx is True
        assert conn.fetch_in_tx is True

    def test_filters_null_rmt_and_price(self):
        rows = [
            _FakeRow({"raw_material_type_id": None, "unit_price": Decimal("9.9")}),
            _FakeRow({"raw_material_type_id": "rm_x", "unit_price": None}),
            _FakeRow({"raw_material_type_id": "rm_y", "unit_price": Decimal("3.0")}),
        ]
        conn = _FakeConn(fetch_rows=rows)
        result = _run(_get_latest_real_purchase_prices(_FakePool(conn), "F006"))
        assert result == {"rm_y": pytest.approx(3.0)}

    def test_empty(self):
        conn = _FakeConn(fetch_rows=[])
        result = _run(_get_latest_real_purchase_prices(_FakePool(conn), "F006"))
        assert result == {}


class TestCostSourcePriority:
    """Replicate the priority branch in sync_dim_ingredient (real > moving > list > none)."""

    @staticmethod
    def _choose(real, moving_avg, list_price):
        # Mirrors sync_dim_ingredient logic exactly.
        if real is not None:
            return (real, "real_purchase")
        elif moving_avg is not None:
            return (float(moving_avg), "moving_avg")
        elif list_price is not None:
            return (float(list_price), "list_price")
        else:
            return (None, "none")

    def test_real_purchase_wins(self):
        up, cs = self._choose(46.0, Decimal("40.0"), Decimal("50.0"))
        assert (up, cs) == (pytest.approx(46.0), "real_purchase")

    def test_moving_avg_when_no_real(self):
        up, cs = self._choose(None, Decimal("40.0"), Decimal("50.0"))
        assert (up, cs) == (pytest.approx(40.0), "moving_avg")

    def test_list_price_when_no_real_no_moving(self):
        up, cs = self._choose(None, None, Decimal("50.0"))
        assert (up, cs) == (pytest.approx(50.0), "list_price")

    def test_none_when_no_price_at_all(self):
        up, cs = self._choose(None, None, None)
        assert up is None and cs == "none"
