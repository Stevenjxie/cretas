from __future__ import annotations

import inspect
from contextlib import AbstractAsyncContextManager
from datetime import date
from decimal import Decimal

import pytest

from smartbi.agent.runtime.restaurant_read_tools import default_restaurant_sources


class _Context(AbstractAsyncContextManager):
    def __init__(self, value):
        self.value = value

    async def __aenter__(self):
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        return None


class ContractProbeConnection:
    async def execute(self, sql, *args):
        return "SELECT 1"

    async def fetch(self, sql, *args):
        if "FROM agg_supplier_price" in sql:
            return []
        if "FROM grouped g" in sql:
            return [
                {
                    "product_id": 7,
                    "name": "宫保鸡丁",
                    "qty": Decimal("10"),
                    "revenue": Decimal("100"),
                    "bill_count": 8,
                    "confidence": Decimal("0.9"),
                    "source": "upload",
                    "source_upload_id": 17,
                    "prov_field_name": "revenue",
                }
            ]
        if "JOIN dim_store" in sql:
            return [
                {
                    "name": "A店",
                    "revenue": Decimal("100"),
                    "order_count": 4,
                    "avg_ticket": Decimal("25"),
                    "discount_amount": Decimal("3"),
                    "gross_amount": Decimal("103"),
                }
            ]
        if "FROM agg_daily_order_type_meal" in sql:
            return [{"grp": "堂食", "revenue": Decimal("100"), "bill_count": 4}]
        if "FROM agg_discount" in sql:
            return [
                {
                    "discount_id": 1,
                    "name": "会员折扣",
                    "amount": Decimal("10"),
                    "bill_count": 2,
                }
            ]
        if "kpi_kind IN ('wastage_qty', 'wastage_cost')" in sql:
            # The per-ingredient ranking returns both axes now (money and
            # quantity) so a cost-worded question can rank by cost.
            return [
                {"name": "猪肉", "category": "肉", "unit": "kg", "qty": 2.0, "cost": 20.0}
            ]
        if "kpi_kind = 'wastage_cost_by_type'" in sql:
            return [{"type": "EXPIRED", "cost": 20.0}]
        if "kpi_kind = 'stocktaking_shortage_qty'" in sql:
            return [
                {"name": "猪肉", "category": "肉", "unit": "kg", "shortage_qty": 1.0}
            ]
        if "FROM fact_inventory_snapshot s" in sql:
            return [
                {
                    "ingredient_id": "I1",
                    "name": "猪肉",
                    "category": "肉",
                    "unit": "kg",
                    "stock_qty": 2.0,
                    "safe_stock_qty": 5.0,
                    "reorder_point": 3.0,
                }
            ]
        if "GROUP BY store" in sql:
            return [
                {
                    "store": "A店",
                    "n": 20,
                    "avg_star": Decimal("4.2"),
                    "avg_service": Decimal("4.1"),
                    "avg_env": Decimal("4.3"),
                    "avg_taste": Decimal("4.0"),
                    "low_star_count": 2,
                }
            ]
        if "GROUP BY tag" in sql:
            return [{"tag": "太咸", "n": 2}]
        # daily_trend
        if "GROUP BY date" in sql:
            return [{"date": date(2026, 1, 1), "revenue": Decimal("100"), "bill_count": 4}]
        raise AssertionError(f"unhandled fetch contract: {sql[:120]}")

    async def fetchrow(self, sql, *args):
        if "MIN(date) AS d0" in sql:
            return {"d0": date(2024, 1, 1), "d1": date(2026, 12, 31)}
        if "COUNT(c.material_cost)" in sql:
            return {
                "revenue": Decimal("100"),
                "material_cost": Decimal("40"),
                "cost_n": 1,
                "n_rows": 1,
            }
        if "SUM(est_cost)" in sql:
            return {"req_cost": Decimal("40"), "req_n": 1}
        if "SUM(discount_amount)" in sql:
            return {"discount": Decimal("10"), "revenue": Decimal("100")}
        if "MAX(snapshot_date)" in sql:
            return {"max_date": date(2026, 1, 10)}
        if "wastage_qty_total" in sql:
            return {"total_qty": 2.0, "total_cost": 20.0, "total_count": 1}
        if "stocktaking_shortage_total" in sql:
            return {"shortage": 1.0, "surplus": 0.0, "count": 1}
        if "SELECT count(*) AS n" in sql:
            return {"n": 20}
        if "low_with_tag" in sql:
            return {"low_star_count": 2, "low_with_tag": 2}
        if "avg((row_data->>'星级分')" in sql:
            return {
                "total_reviews": 20,
                "avg_star": Decimal("4.2"),
                "avg_service": Decimal("4.1"),
                "avg_env": Decimal("4.3"),
                "avg_taste": Decimal("4.0"),
                "low_star_count": 2,
                "high_star_count": 15,
                "vip_count": 5,
                "store_count": 1,
                "city_count": 1,
            }
        raise AssertionError(f"unhandled fetchrow contract: {sql[:120]}")


class ContractProbePool:
    def __init__(self):
        self.connection = ContractProbeConnection()

    def acquire(self):
        return _Context(self.connection)


def test_actual_gold_callable_signatures_match_all_ten_adapters():
    sources = default_restaurant_sources()
    expected = {
        "daily_trend": ("pool", "factory_id", "date_range"),
        "period_comparison": ("pool", "factory_id", "start", "end"),
        "store_comparison": ("pool", "factory_id", "date_range"),
        "top_products": ("pool", "factory_id", "date_range", "top_n", "order"),
        "detect_price_anomalies": (
            "pool", "factory_id", "trailing_n", "epsilon_pct", "baseline_mode", "window_days"
        ),
        "order_type_breakdown": ("pool", "factory_id", "date_range"),
        "meal_period_breakdown": ("pool", "factory_id", "date_range"),
        "discount_summary": ("pool", "factory_id", "date_range", "top_n"),
        # ``query`` is optional and trailing: the agent runtime calls this tool
        # with structured params and no free text, so it keeps the historical
        # quantity ranking. Only the NL router passes a question through.
        "resolve_wastage_top": ("smartbi_pool", "factory_id", "days", "top_n", "query"),
        "resolve_inventory_warning": ("smartbi_pool", "factory_id", "top_n"),
        "resolve_stock_shortage": ("smartbi_pool", "factory_id", "days", "top_n"),
        "review_summary": ("pool", "factory_id"),
        "review_store_ranking": (
            "pool", "factory_id", "dim", "order", "top_n", "min_reviews"
        ),
        "review_dish_issues": ("pool", "factory_id", "top_n", "star_threshold"),
    }
    assert set(expected) == set(sources.__dataclass_fields__)
    for name, parameter_names in expected.items():
        actual = tuple(inspect.signature(getattr(sources, name)).parameters)
        assert actual == parameter_names, name


@pytest.mark.asyncio
async def test_actual_sales_gold_return_keys_used_by_adapters():
    sources = default_restaurant_sources()
    pool = ContractProbePool()
    window = (date(2026, 1, 1), date(2026, 1, 1))
    trend = await sources.daily_trend(pool, "F001", window)
    products = await sources.top_products(pool, "F001", window, top_n=10)
    stores = await sources.store_comparison(pool, "F001", window)
    period = await sources.period_comparison(pool, "F001", *window)
    order_type = await sources.order_type_breakdown(pool, "F001", window)
    meal_period = await sources.meal_period_breakdown(pool, "F001", window)
    discounts = await sources.discount_summary(pool, "F001", window, top_n=10)
    anomalies = await sources.detect_price_anomalies(pool, "F001")

    assert set(trend) == {"factory_id", "start_date", "end_date", "points"}
    assert "top_products" in products and products["top_products"][0]["revenue"] == 100.0
    assert "stores" in stores and "avgTicket" in stores["stores"][0]
    assert set(period) == {"revenue", "gross_margin_pct", "cost_ratio"}
    assert period["revenue"]["available"] is True
    assert "order_types" in order_type
    assert "meal_periods" in meal_period
    assert "discounts" in discounts
    assert anomalies == []


@pytest.mark.asyncio
async def test_actual_ops_and_review_return_shapes_used_by_adapters():
    sources = default_restaurant_sources()
    pool = ContractProbePool()
    waste = await sources.resolve_wastage_top(pool, "F001", days=9, top_n=10)
    stocktaking = await sources.resolve_stock_shortage(pool, "F001", days=9, top_n=10)
    inventory = await sources.resolve_inventory_warning(pool, "F001", top_n=10)
    summary = await sources.review_summary(pool, "F001")
    stores = await sources.review_store_ranking(
        pool, "F001", dim="low_star", order="desc", top_n=10, min_reviews=20
    )
    tags = await sources.review_dish_issues(
        pool, "F001", top_n=10, star_threshold=3
    )

    assert {kpi["title"] for kpi in waste.kpis} >= {"损耗次数", "损耗金额"}
    assert {kpi["title"] for kpi in stocktaking.kpis} >= {"盘点次数"}
    assert inventory.meta["snapshot_date"] == "2026-01-10"
    assert set(summary) >= {"factory_id", "connected", "total_reviews", "avg_star"}
    assert "stores" in stores and "review_count" in stores["stores"][0]
    assert "tags" in tags and tags["tags"][0] == {"tag": "太咸", "count": 2}
