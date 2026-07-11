"""Tests for HealthCheckMetricsBuilder._fetch_pos_metrics's void_rate addition.

New restaurant analytics dimension: 撤单率 (order void rate) — greenfield.
Exercises the REAL _fetch_pos_metrics SQL-routing logic (not monkeypatched
wholesale, unlike most of test_health_check_metrics.py) via a routed fake
conn supporting both `fetch` and `fetchrow`.
"""
from __future__ import annotations

import asyncio
from datetime import date
from unittest.mock import AsyncMock, MagicMock

from smartbi.services.restaurant.health_check_metrics import HealthCheckMetricsBuilder


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


class _RoutedConn:
    """Routes fetch/fetchrow by SQL substring match (first match wins)."""

    def __init__(self):
        self.executed: list = []
        self._fetchrow_router: list = []  # (substr, row_or_None)
        self._fetch_router: list = []     # (substr, rows_list)

    def add_fetchrow(self, substr: str, row):
        self._fetchrow_router.append((substr, row))

    def add_fetch(self, substr: str, rows):
        self._fetch_router.append((substr, rows))

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "OK"

    async def fetchrow(self, sql, *args):
        for substr, row in self._fetchrow_router:
            if substr in sql:
                return row
        return None

    async def fetch(self, sql, *args):
        for substr, rows in self._fetch_router:
            if substr in sql:
                return rows
        return []


def _make_pool(conn: _RoutedConn) -> MagicMock:
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


def test_void_rate_present_when_data_and_bills_sufficient():
    conn = _RoutedConn()
    # order_type mix (empty — no delivery_dependency contribution)
    conn.add_fetch("FROM agg_daily_order_type_meal", [])
    # txn totals: gross/discount/net/bills
    conn.add_fetchrow(
        "FROM fact_pos_transaction",
        {"gross": 100000, "discount": 5000, "net": 95000, "bills": 1000},
    )
    # per-channel rows (used for channel_collection_rate estimate; empty is fine)
    conn.add_fetch("SELECT order_type, channel_origin", [])
    # availability EXISTS + void COUNT
    conn.add_fetchrow("EXISTS", {"has_data": True})
    conn.add_fetchrow("COUNT(*) AS voids", {"voids": 30})

    builder = HealthCheckMetricsBuilder()
    result = _run(
        builder._fetch_pos_metrics(
            "DEMO_REST", date(2026, 1, 1), date(2026, 1, 31),
            smartbi_pool=_make_pool(conn), sub_sector="火锅",
        )
    )

    assert result["void_rate"] == 3.0  # 30/1000*100
    assert result["discount_rate"] == 5.0  # 5000/100000*100
    assert "_void_skip_reason" not in result


def test_void_rate_skipped_when_no_void_data_uploaded():
    """Tenant with ZERO fact_pos_void rows → skip '未上传撤单报表', NOT 0%."""
    conn = _RoutedConn()
    conn.add_fetch("FROM agg_daily_order_type_meal", [])
    conn.add_fetchrow(
        "FROM fact_pos_transaction",
        {"gross": 100000, "discount": 5000, "net": 95000, "bills": 1000},
    )
    conn.add_fetch("SELECT order_type, channel_origin", [])
    conn.add_fetchrow("EXISTS", {"has_data": False})
    # COUNT(*) AS voids must NOT be queried when has_data is False — no route
    # registered, so a regression that queries it unconditionally crashes loudly.

    builder = HealthCheckMetricsBuilder()
    result = _run(
        builder._fetch_pos_metrics(
            "DEMO_REST", date(2026, 1, 1), date(2026, 1, 31),
            smartbi_pool=_make_pool(conn), sub_sector="火锅",
        )
    )

    assert "void_rate" not in result  # honest skip, never fabricated 0%
    assert result["_void_skip_reason"] == "未上传撤单报表"


def test_void_rate_skipped_when_bills_below_min_denominator():
    """bills < _VOID_MIN_BILLS (50) → skip '订单样本不足' (avoids false 过高)."""
    conn = _RoutedConn()
    conn.add_fetch("FROM agg_daily_order_type_meal", [])
    conn.add_fetchrow(
        "FROM fact_pos_transaction",
        {"gross": 2000, "discount": 100, "net": 1900, "bills": 20},
    )
    conn.add_fetch("SELECT order_type, channel_origin", [])
    conn.add_fetchrow("EXISTS", {"has_data": True})
    # COUNT(*) AS voids must NOT run when bills < min — no route registered.

    builder = HealthCheckMetricsBuilder()
    result = _run(
        builder._fetch_pos_metrics(
            "DEMO_REST", date(2026, 1, 1), date(2026, 1, 31),
            smartbi_pool=_make_pool(conn), sub_sector="火锅",
        )
    )

    assert "void_rate" not in result
    assert "订单样本不足" in result["_void_skip_reason"]
