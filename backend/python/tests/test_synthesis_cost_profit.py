from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from smartbi.agent.factbook import FactBook
from smartbi.gold.queries import finance_summary


class _Acquire:
    def __init__(self, conn: "_Conn") -> None:
        self._conn = conn

    async def __aenter__(self) -> "_Conn":
        return self._conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        return None


class _Pool:
    def __init__(self, totals: dict) -> None:
        self.conn = _Conn(totals)

    def acquire(self) -> _Acquire:
        return _Acquire(self.conn)


class _Conn:
    def __init__(self, totals: dict) -> None:
        self._totals = totals

    async def fetchrow(self, sql: str, *params):
        assert "LEFT JOIN agg_daily_cost c USING (factory_id, date, store_id)" in sql
        assert "SUM(c.material_cost)" in sql
        assert "SUM(c.labor_cost)" in sql
        assert "SUM(c.overhead_cost)" in sql
        return self._totals

    async def fetch(self, sql: str, *params):
        return []


def _migrations_dir() -> Path:
    return Path(__file__).resolve().parents[1] / "smartbi" / "database" / "migrations"


def test_cost_migrations_keep_locked_rls_grant_and_seed_formula():
    table_sql = (_migrations_dir() / "V20260709_01__agg_daily_cost.sql").read_text(encoding="utf-8")
    seed_sql = (_migrations_dir() / "V20260709_02__demo_rest_cost_seed.sql").read_text(encoding="utf-8")

    assert "CREATE TABLE IF NOT EXISTS agg_daily_cost" in table_sql
    assert "PRIMARY KEY (factory_id, date, store_id)" in table_sql
    assert "ALTER TABLE agg_daily_cost ENABLE ROW LEVEL SECURITY;" in table_sql
    assert "ALTER TABLE agg_daily_cost FORCE  ROW LEVEL SECURITY;" in table_sql
    assert "CREATE POLICY tenant_isolation ON agg_daily_cost FOR ALL" in table_sql
    assert "USING (factory_id = current_setting('app.factory_id', true))" in table_sql
    assert "WITH CHECK (factory_id = current_setting('app.factory_id', true))" in table_sql
    assert "GRANT SELECT, INSERT, UPDATE, DELETE ON agg_daily_cost TO smartbi_user;" in table_sql

    assert "SET app.factory_id = 'DEMO_REST';" in seed_sql
    assert "WHERE a.factory_id = 'DEMO_REST' AND a.net_amount > 0" in seed_sql
    assert "ROUND(a.net_amount * (0.30 + (a.store_id % 9) * 0.01), 2)" in seed_sql
    assert "ROUND(a.net_amount * (0.22 + (a.store_id % 9) * 0.01), 2)" in seed_sql
    assert "ROUND(a.net_amount * (0.14 + (a.store_id % 7) * 0.01), 2)" in seed_sql
    assert "ON CONFLICT (factory_id, date, store_id) DO NOTHING;" in seed_sql


@pytest.mark.asyncio
async def test_finance_summary_adds_profit_only_when_cost_exists():
    out = await finance_summary(
        _Pool({
            "total_revenue": Decimal("1000.00"),
            "bill_count": 10,
            "store_count": 2,
            "day_count": 1,
            "material_cost": Decimal("330.00"),
            "labor_cost": Decimal("240.00"),
            "overhead_cost": Decimal("160.00"),
        }),
        "DEMO_REST",
        (date(2026, 7, 1), date(2026, 7, 1)),
    )

    assert out["material_cost"] == 330.0
    assert out["labor_cost"] == 240.0
    assert out["overhead_cost"] == 160.0
    assert out["total_cost"] == 730.0
    assert out["net_profit"] == 270.0
    assert out["gross_profit"] == 670.0
    assert out["net_margin_pct"] == 27.0
    assert Decimal(str(out["total_revenue"])) - Decimal(str(out["total_cost"])) == Decimal(str(out["net_profit"]))


@pytest.mark.asyncio
async def test_finance_summary_keeps_profit_keys_absent_without_cost():
    out = await finance_summary(
        _Pool({
            "total_revenue": Decimal("1000.00"),
            "bill_count": 10,
            "store_count": 2,
            "day_count": 1,
            "material_cost": None,
            "labor_cost": None,
            "overhead_cost": None,
        }),
        "OTHER_TENANT",
        (date(2026, 7, 1), date(2026, 7, 1)),
    )

    for key in (
        "material_cost", "labor_cost", "overhead_cost", "total_cost",
        "net_profit", "gross_profit", "net_margin_pct",
    ):
        assert key not in out


def test_factbook_renders_profit_and_indexes_profit_facts():
    fb = FactBook(finance={
        "start_date": "2026-07-01",
        "end_date": "2026-07-01",
        "total_revenue": 1000.0,
        "bill_count": 10,
        "avg_bill_value": 100.0,
        "store_count": 2,
        "day_count": 1,
        "material_cost": 330.0,
        "labor_cost": 240.0,
        "overhead_cost": 160.0,
        "total_cost": 730.0,
        "net_profit": 270.0,
        "gross_profit": 670.0,
        "net_margin_pct": 27.0,
        "top_stores": [],
    })

    text = fb.to_prompt_text()
    idx = fb.to_facts_index()

    assert "总成本" in text
    assert "净利润" in text
    assert "净利率" in text
    assert idx["总成本"] == 730.0
    assert idx["净利润"] == 270.0
    assert idx["净利率"] == 27.0
    assert idx["食材成本"] == 330.0
