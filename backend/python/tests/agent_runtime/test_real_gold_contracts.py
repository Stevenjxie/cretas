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
        if "FROM agg_restaurant_daily_ops" in sql:
            # resolve_stock_shortage 的「盘亏 top N」。⚠️ 探针缺列会**看起来像生产
            # 代码崩了**(2026-08-10 实测: KeyError 'shortage_cost' 指向
            # restaurant_ops_router.py:3106, 实为这里没给这个键)。排查顺序:
            # 先看探针给了哪些键, 再怀疑被测代码。
            return [
                {
                    "name": "猪肉", "category": "肉", "unit": "kg",
                    "shortage_qty": 1.0, "shortage_cost": 12.5,
                }
            ]
        if "kpi_kind = 'stocktaking_shortage_qty'" in sql:
            # ⚠️ 2026-08-10: 生产的盘点查询后来加了 shortage_cost(金额是唯一能跨
            #    食材相加的维度), 这条探针分支没跟上 → resolve_stock_shortage 在
            #    契约测试里 KeyError。探针缺列会**看起来像生产代码崩了**, 排查时
            #    先看探针给了哪些键。
            return [
                {
                    "name": "猪肉", "category": "肉", "unit": "kg",
                    "shortage_qty": 1.0, "shortage_cost": 12.5,
                }
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
        if "FROM agg_restaurant_daily_totals" in sql:
            # ⚠️ 损耗(resolve_wastage_top)与盘点(resolve_stock_shortage)查的是
            #    **同一张表的不同列**。按表名分支只能有一条, 所以给并集 —— 被测
            #    代码各取所需, 探针不必猜是谁在问。
            return {
                # 损耗侧
                "total_qty": 3.0, "total_cost": 30.0, "total_count": 4,
                # 盘点侧
                "shortage": 1.0, "surplus": 0.5,
                "shortage_cost": 12.5, "surplus_cost": 6.0, "count": 3,
            }
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
        # ``store_names`` 是**尾部可选**(default=None) —— 只按门店名过滤时才传,
        # 适配器仍按 (pool, factory_id, date_range) 调用, 向后兼容。契约钉精确
        # 签名是刻意的: 任何新增都必须来这里过一眼, 顺便确认它确实是尾部可选。
        "daily_trend": ("pool", "factory_id", "date_range", "store_names"),
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
        # ``date_range`` / ``window_label`` 同样是尾部可选(自然语言路径传自定义
        # 窗口时才用), agent runtime 仍按前四个位置参数调用。
        # ``dimensions`` 2026-08-18 新增, 同样是**尾部可选**(default=()):
        # 规划器算出的维度此前从来没到过执行器, 于是「最近损耗怎么样」(ingredient)
        # 与追问「哪家店最多」(store) 返回逐字相同的答案
        # (📏 MOCK_REST prod, 两轮正文 md5[:8] 同为 bd1d6675, 同为 1249 字)。
        # agent runtime 仍按前四个位置参数调用; 不传它时产出逐字不变 —— 有阴性
        # 对照钉着(tests/test_wastage_answers_the_asked_dimension.py::
        # test_ingredient_dimension_is_byte_identical_to_no_dimension)。
        # ⚠️ 这道闸为此红过一次, 而它红得对: 它要的就是「新增形参来这里过一眼」。
        "resolve_wastage_top": (
            "smartbi_pool", "factory_id", "days", "top_n", "query",
            "date_range", "window_label", "dimensions",
        ),
        "resolve_inventory_warning": ("smartbi_pool", "factory_id", "top_n"),
        "resolve_stock_shortage": (
            "smartbi_pool", "factory_id", "days", "top_n",
            "date_range", "window_label",
        ),
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

    # store_names 随入参一起回显(未按门店过滤时为 None), 适配器只读它需要的键,
    # 多一个回显键不影响 —— 但仍钉精确集合, 逼每次新增来这里说明一次。
    assert set(trend) == {
        "factory_id", "start_date", "end_date", "points", "store_names"}
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
    summary = await sources.review_summary(pool, "F001")
    stores = await sources.review_store_ranking(
        pool, "F001", dim="low_star", order="desc", top_n=10, min_reviews=20
    )
    tags = await sources.review_dish_issues(
        pool, "F001", top_n=10, star_threshold=3
    )

    assert {kpi["title"] for kpi in waste.kpis} >= {"损耗次数", "损耗金额"}
    assert {kpi["title"] for kpi in stocktaking.kpis} >= {"盘点次数"}
    assert set(summary) >= {"factory_id", "connected", "total_reviews", "avg_star"}
    assert "stores" in stores and "review_count" in stores["stores"][0]
    assert "tags" in tags and tags["tags"][0] == {"tag": "太咸", "count": 2}


@pytest.mark.integration
@pytest.mark.asyncio
async def test_actual_inventory_warning_shape_used_by_adapters():
    """⚠️ 这条**必须**打 integration marker, 不能并进上面那个用例。

    2026-08-09 起 `resolve_inventory_warning` 的数据源改为 **cretas 库**(Java 侧
    库存底账)。它会自己开另一个库的连接, 假 pool 拦不住 —— 于是这一个调用会把
    整个用例拖进真库依赖, 连带 5 个本可以纯离线跑的契约断言一起失守。

    判据: **一个用例里只要有一个调用够不到假桩, 整条用例就变成集成测试。**
    拆开比给整条打 marker 便宜得多。
    """
    sources = default_restaurant_sources()
    pool = ContractProbePool()
    inventory = await sources.resolve_inventory_warning(pool, "F001", top_n=10)
    assert inventory.meta["snapshot_date"] == "2026-01-10"
