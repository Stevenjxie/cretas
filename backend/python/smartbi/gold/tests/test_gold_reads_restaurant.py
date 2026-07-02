"""Unit tests for restaurant-specific gold read queries (A1/A2/A3).

These tests use a fake asyncpg pool/connection and do NOT require a real DB.
They test the query functions from smartbi.gold.queries directly, avoiding
the FastAPI router layer so they can run with minimal dependencies.

Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_gold_reads_restaurant.py -v
"""
from __future__ import annotations

import asyncio
from datetime import date
from decimal import Decimal

import pytest

from smartbi.gold.queries import order_type_mix, staff_ranking, top_products


# ---------------------------------------------------------------------------
# Fake pool / connection helpers
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    """A dict that also supports attribute-style access and .keys() — mimics asyncpg Record."""

    def keys(self):
        return super().keys()


class _FakeConn:
    """Minimal asyncpg connection stub. Records the SQL for inspection."""

    def __init__(self, rows, row=None):
        self._rows = rows
        self._row = row
        self.last_sql = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        self.last_sql = sql
        return self._rows

    async def fetchrow(self, sql, *a, **k):
        self.last_sql = sql
        return self._row if self._row is not None else (self._rows[0] if self._rows else None)


class _FakePool:
    """Fake asyncpg Pool whose acquire() context manager yields a _FakeConn."""

    def __init__(self, rows, row=None):
        self._rows = [_FakeRecord(r) for r in rows]
        self._row = _FakeRecord(row) if row is not None else None
        self._conn = _FakeConn(self._rows, self._row)

    def acquire(self):
        return self._conn

    @property
    def conn(self):
        """Expose the underlying conn so tests can inspect last_sql."""
        return self._conn


# ---------------------------------------------------------------------------
# A1: order_type_mix
# ---------------------------------------------------------------------------

class TestOrderTypeMix:

    def _run(self, rows):
        pool = _FakePool(rows)
        return asyncio.get_event_loop().run_until_complete(
            order_type_mix(pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31)))
        )

    def test_basic_split_totals(self):
        rows = [
            {"amt": Decimal("57798326.00"), "bills": 214717, "order_type": "堂食"},
            {"amt": Decimal("20974125.00"), "bills": 230045, "order_type": "外卖"},
        ]
        result = self._run(rows)

        assert result["total_revenue"] == pytest.approx(78772451.0, rel=1e-6)
        assert len(result["order_types"]) == 2

        # First entry is 堂食 (higher revenue — sorted by amt DESC)
        dine_in = result["order_types"][0]
        assert dine_in["order_type"] == "堂食"
        assert dine_in["revenue"] == pytest.approx(57798326.0, rel=1e-6)
        assert dine_in["bill_count"] == 214717

    def test_takeout_pct_in_range(self):
        """外卖 should be ~26.6% of total — between 26.0 and 27.5."""
        rows = [
            {"amt": Decimal("57798326.00"), "bills": 214717, "order_type": "堂食"},
            {"amt": Decimal("20974125.00"), "bills": 230045, "order_type": "外卖"},
        ]
        result = self._run(rows)

        takeout = next(t for t in result["order_types"] if t["order_type"] == "外卖")
        assert 26.0 <= takeout["revenue_pct"] <= 27.5, (
            f"外卖 revenue_pct {takeout['revenue_pct']} not in [26.0, 27.5]"
        )

    def test_pct_sums_to_100(self):
        rows = [
            {"amt": Decimal("57798326.00"), "bills": 214717, "order_type": "堂食"},
            {"amt": Decimal("20974125.00"), "bills": 230045, "order_type": "外卖"},
        ]
        result = self._run(rows)
        total_pct = sum(t["revenue_pct"] for t in result["order_types"])
        # With ROUND_HALF_UP on 0.1 decimals the sum may be 99.9 or 100.0 or 100.1
        assert abs(total_pct - 100.0) <= 0.2, f"Percentages sum to {total_pct}, expected ~100"

    def test_empty_rows_returns_zero_total(self):
        result = self._run([])
        assert result["total_revenue"] == 0.0
        assert result["order_types"] == []

    def test_zero_amount_with_bills_estimates_from_overall_avg_ticket(self):
        rows = [
            {"amt": Decimal("0.00"), "bills": 100, "order_type": "鍫傞"},
            {"amt": Decimal("0.00"), "bills": 50, "order_type": "澶栧崠"},
        ]
        pool = _FakePool(rows, row={"revenue": Decimal("30000.00"), "bills": 150})
        result = asyncio.get_event_loop().run_until_complete(
            order_type_mix(pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31)))
        )

        assert result["revenue_estimated"] is True
        assert result["total_revenue"] == pytest.approx(30000.0, rel=1e-6)
        assert result["estimation_note"]

        dine_in = next(t for t in result["order_types"] if t["order_type"] == "鍫傞")
        takeout = next(t for t in result["order_types"] if t["order_type"] == "澶栧崠")
        assert dine_in["revenue"] == pytest.approx(20000.0, rel=1e-6)
        assert takeout["revenue"] == pytest.approx(10000.0, rel=1e-6)
        assert dine_in["revenue_pct"] == pytest.approx(66.7, abs=0.1)
        assert takeout["revenue_pct"] == pytest.approx(33.3, abs=0.1)
        assert dine_in["revenue_estimated"] is True

    def test_single_type_is_100_pct(self):
        rows = [{"amt": Decimal("1000.00"), "bills": 50, "order_type": "堂食"}]
        result = self._run(rows)
        assert result["order_types"][0]["revenue_pct"] == pytest.approx(100.0, abs=0.1)

    def test_response_shape(self):
        rows = [{"amt": Decimal("100.00"), "bills": 5, "order_type": "堂食"}]
        result = self._run(rows)
        assert "factory_id" in result
        assert "start_date" in result
        assert "end_date" in result
        assert "total_revenue" in result
        assert "order_types" in result
        item = result["order_types"][0]
        assert "order_type" in item
        assert "revenue" in item
        assert "bill_count" in item
        assert "revenue_pct" in item


# ---------------------------------------------------------------------------
# A2: staff_ranking
# ---------------------------------------------------------------------------

class TestStaffRanking:

    def _run(self, rows, top_n=5):
        pool = _FakePool(rows)
        return asyncio.get_event_loop().run_until_complete(
            staff_ranking(pool, "RES_3101_009", (date(2025, 1, 1), date(2025, 12, 31)), top_n=top_n)
        )

    def test_top_staff_order(self):
        rows = [
            {"name": "收银", "net": Decimal("31538423.00"), "bills": 185000},
            {"name": "杨生", "net": Decimal("3689233.00"), "bills": 24500},
        ]
        result = self._run(rows)

        staff = result["staff"]
        assert len(staff) == 2
        assert staff[0]["name"] == "收银"
        assert staff[0]["net_amount"] == pytest.approx(31538423.0, rel=1e-6)
        assert staff[0]["bill_count"] == 185000

        assert staff[1]["name"] == "杨生"
        assert staff[1]["net_amount"] == pytest.approx(3689233.0, rel=1e-6)

    def test_caveat_is_present_and_non_empty(self):
        rows = [{"name": "收银", "net": Decimal("1000.00"), "bills": 10}]
        result = self._run(rows)

        assert "caveat" in result
        assert isinstance(result["caveat"], str)
        assert len(result["caveat"]) > 10, "caveat should be a meaningful string, not a placeholder"

    def test_caveat_mentions_pos_or_operator(self):
        """Caveat must acknowledge this is POS/operator data, not waiter attribution."""
        rows = [{"name": "收银", "net": Decimal("1000.00"), "bills": 10}]
        result = self._run(rows)
        caveat = result["caveat"]
        # Check for some indication that this is POS/operator data
        assert any(word in caveat for word in ["POS", "操作员", "收银", "点菜", "归因"]), (
            f"caveat should mention POS/operator context, got: {caveat!r}"
        )

    def test_empty_rows(self):
        result = self._run([])
        assert result["staff"] == []
        assert "caveat" in result  # caveat always present regardless of data

    def test_response_shape(self):
        rows = [{"name": "张三", "net": Decimal("5000.00"), "bills": 30}]
        result = self._run(rows)
        assert "factory_id" in result
        assert "start_date" in result
        assert "end_date" in result
        assert "staff" in result
        assert "caveat" in result
        item = result["staff"][0]
        assert "name" in item
        assert "net_amount" in item
        assert "bill_count" in item


# ---------------------------------------------------------------------------
# A3: top_products order param
# ---------------------------------------------------------------------------

class TestTopProductsOrderParam:
    """Tests that the order param correctly flips the ORDER BY direction."""

    _BASE_ROWS = [
        {
            "product_id": 1, "name": "叮咚好食光卤猪蹄200g",
            "qty": Decimal("100.000"), "revenue": Decimal("5000.00"),
            "bill_count": 100,
            "confidence": None, "source": None, "source_upload_id": None, "prov_field_name": None,
        },
        {
            "product_id": 2, "name": "低销菜品",
            "qty": Decimal("10.000"), "revenue": Decimal("200.00"),
            "bill_count": 10,
            "confidence": None, "source": None, "source_upload_id": None, "prov_field_name": None,
        },
    ]

    def _run(self, order="desc"):
        pool = _FakePool(self._BASE_ROWS)
        asyncio.get_event_loop().run_until_complete(
            top_products(
                pool, "F001",
                (date(2025, 1, 1), date(2025, 12, 31)),
                top_n=10, order=order,
            )
        )
        return pool.conn.last_sql

    def test_default_order_is_desc(self):
        sql = self._run(order="desc")
        assert "DESC" in sql.upper()
        assert "ASC" not in sql.upper().replace("ESCAPE", "")  # ESCAPE is not ASC

    def test_asc_order_produces_asc_sql(self):
        sql = self._run(order="asc")
        assert "ASC" in sql.upper()

    def test_asc_order_does_not_contain_desc_in_order_clause(self):
        """When order=asc the ORDER BY clause should not say DESC."""
        sql = self._run(order="asc")
        # The ORDER BY line should contain ASC not DESC
        # Find the last ORDER BY ... line
        lines = [line.strip() for line in sql.splitlines() if "ORDER BY" in line.upper()]
        # The last ORDER BY is the one we care about (there may be one inside LATERAL)
        outer_order_lines = [line for line in lines if "G.REVENUE" in line.upper()]
        assert len(outer_order_lines) == 1
        assert "ASC" in outer_order_lines[0].upper()
        assert "DESC" not in outer_order_lines[0].upper()

    def test_invalid_order_defaults_to_desc(self):
        """Unknown order values (e.g. injection attempts) must fall back to DESC."""
        sql = self._run(order="DROP TABLE products; --")
        assert "DESC" in sql.upper()
        assert "DROP" not in sql

    def test_case_insensitive_asc(self):
        """order='ASC' (uppercase) should be treated same as 'asc'."""
        sql = self._run(order="ASC")
        assert "ASC" in sql.upper()

    def test_top_products_result_shape_unchanged(self):
        """Adding order param should not break existing response shape."""
        pool = _FakePool(self._BASE_ROWS)
        result = asyncio.get_event_loop().run_until_complete(
            top_products(pool, "F001", (date(2025, 1, 1), date(2025, 12, 31)), top_n=10)
        )
        assert "top_products" in result
        assert "factory_id" in result
        item = result["top_products"][0]
        assert "product_id" in item
        assert "product_name" in item
        assert "revenue" in item
        assert "bill_count" in item
