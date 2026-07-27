from __future__ import annotations

from datetime import date, timedelta

import pytest

from smartbi.scripts import seed_demo_gold_missing_store_sales as seed


class _PlanConnection:
    def __init__(self):
        self.executed = []

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "SELECT 1"

    async def fetchrow(self, sql, *args):
        self.executed.append((sql, args))
        return {
            "active_stores": 33,
            "eligible_stores": 14,
            "seeded_stores": 0,
            "seeded_transactions": 0,
            "seeded_items": 0,
            "seeded_aggregates": 0,
            "catalog_products": len(seed._PRODUCT_NAMES),
        }


def test_future_target_is_rejected():
    with pytest.raises(ValueError, match="exceeds latest complete day"):
        seed.validate_target_end(date.today())


@pytest.mark.asyncio
async def test_build_plan_is_scoped_to_active_zero_data_demo_stores():
    conn = _PlanConnection()
    target_end = date.today() - timedelta(days=1)

    plan = await seed.build_plan(conn, target_end)

    assert plan == {
        "factory_id": "RES_3101_009",
        "start": target_end.replace(month=1, day=1).isoformat(),
        "target_end": target_end.isoformat(),
        "bills_per_day": 12,
        "active_stores": 33,
        "eligible_stores": 14,
        "seeded_stores_before": 0,
        "seeded_transactions_before": 0,
        "seeded_items_before": 0,
        "seeded_aggregates_before": 0,
        "catalog_products": len(seed._PRODUCT_NAMES),
    }
    plan_sql = conn.executed[-1][0]
    assert "s.name NOT LIKE '%闭店%'" in plan_sql
    assert "NOT EXISTS" in plan_sql
    assert "fact_pos_transaction" in plan_sql
    assert "agg_daily" in plan_sql
    assert conn.executed[0][1] == ("RES_3101_009",)


@pytest.mark.asyncio
async def test_rollback_only_deletes_reserved_demo_markers():
    class _RollbackConnection:
        def __init__(self):
            self.calls = []

        async def execute(self, sql, *args):
            self.calls.append((sql, args))
            if sql.startswith("DELETE FROM agg_daily"):
                return "DELETE 14"
            if sql.startswith("DELETE FROM fact_pos_transaction"):
                return "DELETE 120"
            return "SELECT 1"

    conn = _RollbackConnection()
    result = await seed.rollback_seed(conn)

    assert result == {
        "deleted_aggregates": 14,
        "deleted_transactions": 120,
    }
    assert conn.calls[1][1] == (
        "RES_3101_009",
        seed.SEED_VERSION,
    )
    assert conn.calls[2][1] == (
        "RES_3101_009",
        seed.SOURCE_TYPE,
    )
