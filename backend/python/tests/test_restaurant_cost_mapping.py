from __future__ import annotations

import asyncio
from pathlib import Path

from smartbi.gold.restaurant.restaurant_cost_mapping import (
    merge_cost_product_mapping,
    merge_cost_product_names,
)


class _Conn:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []

    async def execute(self, sql, *args):
        self.calls.append(("execute", sql, args))

    async def fetch(self, sql, *args):
        self.calls.append(("fetch", sql, args))
        return self.rows


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, *_args):
        return None


class _Pool:
    def __init__(self, rows):
        self.conn = _Conn(rows)

    def acquire(self):
        return _Acquire(self.conn)


def test_cost_product_mapping_keeps_primary_and_fills_unique_fallback():
    pool = _Pool([
        {"normalized_name": "招牌青花椒味(单人份)", "product_source_pk": "pt_qhj_001"},
        {"normalized_name": "重复菜", "product_source_pk": "pt-2"},
        {"normalized_name": "重复菜", "product_source_pk": "pt-3"},
    ])

    result = asyncio.run(merge_cost_product_mapping(
        pool,
        "RES_3101_009",
        ["已有菜", "招牌青花椒味(单人份)", "重复菜"],
        {"已有菜": "authoritative-pk"},
    ))

    assert result == {
        "已有菜": "authoritative-pk",
        "招牌青花椒味(单人份)": "pt_qhj_001",
    }
    fetch_call = next(call for call in pool.conn.calls if call[0] == "fetch")
    assert fetch_call[2][0] == "RES_3101_009"
    assert fetch_call[2][1] == ["招牌青花椒味(单人份)", "重复菜"]


def test_cost_product_name_fallback_fills_only_missing_source_keys():
    pool = _Pool([
        {"product_source_pk": "pt_qhj_001", "product_name": "招牌青花椒味(单人份)"},
    ])

    result = asyncio.run(merge_cost_product_names(
        pool,
        "RES_3101_009",
        ["pt_qhj_001", "primary"],
        {"primary": "ERP 菜品"},
    ))

    assert result == {
        "primary": "ERP 菜品",
        "pt_qhj_001": "招牌青花椒味(单人份)",
    }


def test_cost_product_dimension_migration_is_tenant_locked_and_complete():
    migration = (
        Path(__file__).parents[1]
        / "smartbi"
        / "database"
        / "migrations"
        / "V20261029_02__restaurant_cost_product_dimension.sql"
    ).read_text(encoding="utf-8")

    assert "FORCE ROW LEVEL SECURITY" in migration
    assert "current_setting('app.factory_id', true)" in migration
    assert "GRANT SELECT, INSERT, UPDATE, DELETE" in migration
    assert migration.count("('pt_qhj_") == 136
    assert "('pt_qhj_001', '招牌青花椒味(单人份)')" in migration
    assert "'legacy_qhj_demo_seed'" in migration
