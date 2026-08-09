"""通用执行器的行为约束（不打真库，SQL 生成层）。

⛔ 这里**不测数字对不对** —— 那要打真库，另有 prod 端到端验证。
   这里测的是三条承重约束：缺列不发 SQL、只拼登记过的东西、成本走桥接表。
"""
import datetime

import pytest

from smartbi.gold.restaurant.generic_executor import (
    UnsupportedCell,
    build_sql,
    execute_cell,
)
from smartbi.gold.restaurant.metric_registry import registry_size


def test_registry_covers_far_more_cells_than_registrations():
    """🔑 这个方案的全部理由: 登记是加法, 格子是乘法。

    差距一旦消失(格子数 ≈ 登记数), 说明有人又在按格子写东西了。
    """
    size = registry_size()
    assert size["cells"] >= size["registrations"] * 4, (
        f"登记 {size['registrations']} 条只撑起 {size['cells']} 个格子 —— "
        f"差距太小, 说明退化成了「一个问题一个登记」")


def test_missing_column_does_not_reach_sql():
    """🔴 承重: 缺列时**一行 SQL 都不发**, 而不是发出去让 COALESCE 算成 0。

    「你的平台抽佣是 ¥0」比「这项数据你还没接入」危险得多 ——
    前者是个看起来合理的错数字, 后者是句实话。
    """
    class _Conn:
        def __init__(self):
            self.fetched = []

        async def fetch(self, sql, *args):
            self.fetched.append(sql)
            return []

    import asyncio

    conn = _Conn()
    res = asyncio.run(
        execute_cell(
            conn, factory_id="MOCK_REST", metric_key="revenue",
            dimension_key="store", aggregation_key="rank",
            date_range=(datetime.date(2026, 8, 1), datetime.date(2026, 8, 9)),
            available_columns=set(),          # 什么列都没有
        )
    )
    assert res.missing_columns, "缺列没被报告"
    assert res.rows == [], "缺列时不该有行"
    assert res.ok is False
    assert conn.fetched == [], "🔴 缺列却把 SQL 发了出去"


def test_unregistered_metric_is_refused_not_guessed():
    """⛔ 只拼登记过的东西 —— 模型编不出的指标, 执行器也不许猜。"""
    with pytest.raises(UnsupportedCell):
        build_sql("翻台率", "store", "rank")
    with pytest.raises(UnsupportedCell):
        build_sql("revenue", "不存在的维度", "rank")
    with pytest.raises(UnsupportedCell):
        build_sql("revenue", "store", "不存在的聚合")


def test_metric_cannot_use_a_dimension_its_fact_table_lacks():
    """销量建在明细表上, 没有渠道这一维 —— 拼出来会是跑不通的 SQL。"""
    with pytest.raises(UnsupportedCell):
        build_sql("sales_qty", "channel", "rank")


def test_rank_requires_a_dimension():
    """「排名」按定义要有分组对象; 全店排名是无意义的。"""
    with pytest.raises(UnsupportedCell):
        build_sql("revenue", "all", "rank")


def test_cost_joins_through_the_name_bridge_not_product_id():
    """🔴 承重: 成本必须经 dim_restaurant_cost_product 桥接。

    2026-08-09 实测 agg_restaurant_product_cost.product_id **全库都是 0**,
    按 product_id 直连会静默得到 0 成本 → 毛利率 100%,
    一个看起来很棒的错数字。
    """
    sql, _req, _base = build_sql("gross_margin", "product", "rank")
    assert "dim_restaurant_cost_product" in sql, "成本没走桥接表"
    assert "c.product_source_pk = b.product_source_pk" in sql, "桥接条件不对"
    assert "c.product_id" not in sql, "🔴 按 product_id 直连了 —— 会静默算出 0 成本"


def test_derived_metrics_are_computed_in_sql_not_twice():
    """派生量在 SQL 里算完 —— 上层再算一遍就会出现两处口径。"""
    sql, _req, base = build_sql("avg_ticket", "store", "rank")
    assert "avg_ticket" in sql
    assert "NULLIF" in sql, "除法没防除零"
    assert set(base) == {"revenue", "orders"}, f"客单价的基础指标不对: {base}"


def test_all_parameters_are_placeholders_never_interpolated():
    """⛔ 租户/日期全走占位符 —— 拼接会把租户隔离变成可注入的。"""
    sql, _req, _base = build_sql("revenue", "store", "rank")
    assert "$1" in sql and "$2" in sql and "$3" in sql
    assert "MOCK_REST" not in sql and "2026-" not in sql


def test_gross_margin_reaches_through_two_levels_of_derivation():
    """毛利率 = (营收 − 成本) ÷ 营收 —— 两层派生要能摊平到基础指标。"""
    _sql, req, base = build_sql("gross_margin", "store", "summary")
    assert set(base) == {"revenue", "food_cost"}, base
    assert any("food_cost" in r for r in req), "成本列没进 requires, 缺列时不会被拦"
