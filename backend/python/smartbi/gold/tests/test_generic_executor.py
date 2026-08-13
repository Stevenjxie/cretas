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
    """指标不能按它所在事实表没有的维度分组 —— 拼出来会是跑不通的 SQL。

    ⚠️ 这条原本断言的是 `sales_qty × channel`。扩表(22 指标/16 维度/9 聚合)之后
       那个组合**真的成立了**: 渠道列在订单表上, 销量在明细表上, 按 `t.order_type`
       分组求 `SUM(i.qty)` 是「各渠道卖了多少份菜」—— 没有扇出, 口径正确。
       旧登记表只是没给 sales_qty 声明这一维, 不是这个组合不可算。
       ⛔ 所以这里换成**真的不可能**的组合, 不是放宽断言。
    """
    # 损耗链与 POS 链不共表: 销量在明细表, 食材维度在损耗表上。
    with pytest.raises(UnsupportedCell):
        build_sql("sales_qty", "ingredient", "rank")
    # 反向: 损耗成本按门店 —— 损耗表上没有门店。
    with pytest.raises(UnsupportedCell):
        build_sql("wastage_cost", "store", "rank")


def test_order_grain_metric_refuses_item_dimension_instead_of_fanning_out():
    """🔴 承重: 订单级指标按菜品分组必须**拒绝**, ⛔ 不许硬凑。

    客流(customer_count)在订单表上。按菜品分组要 join 明细, 那时同一张单的
    人数会被每条明细各加一遍 —— 一桌 4 人点 6 道菜会变成 24 人。
    SQL 跑得通、数字看着像那么回事、结论完全错, 与 08-09 实测的营收 57 倍扇出同型。
    """
    with pytest.raises(UnsupportedCell):
        build_sql("guests", "product", "rank")
    # 派生量同理: 人均消费 = 营收 ÷ 客流, 客流只有订单粒度 ⇒ 按菜品问要被拒。
    with pytest.raises(UnsupportedCell):
        build_sql("avg_per_capita", "product", "rank")


def test_rank_requires_a_dimension():
    """「排名」按定义要有分组对象; 全店排名是无意义的。"""
    with pytest.raises(UnsupportedCell):
        build_sql("revenue", "all", "rank")


def test_cost_joins_through_the_name_bridge_not_product_id():
    """🔴 承重: 成本必须按**名字桥接**到 product_source_pk, 绝不按 product_id 直连。

    2026-08-09 实测 agg_restaurant_product_cost.product_id **全库都是 0**,
    按 product_id 直连会静默得到 0 成本 → 毛利率 100%,
    一个看起来很棒的错数字。

    ⚠️ 2026-08-13 起桥接的**来源**从「直接 join SmartBI 的
       dim_restaurant_cost_product」换成「Python 解析好的两个数组」——
       那张表降级为三层来源里的第三层(存量兜底), 权威层在运营库。
       本测守的行为**没变**: 成本经名字桥接、不按 product_id。
    """
    sql, _req, _base = build_sql("gross_margin", "product", "rank")
    assert "c.product_source_pk = b.product_source_pk" in sql, "桥接条件不对"
    assert "b.normalized_name = dp.normalized_name" in sql, "没按名字桥接"
    assert "c.product_id" not in sql, "🔴 按 product_id 直连了 —— 会静默算出 0 成本"


def test_cost_bridge_join_has_no_sql_side_normalisation():
    """⛔ join 两边都不许出现 lower()/btrim()。

    规范化只有一处, 在 `normalize_dish_name`(Python)。SQL 再做一次会与它
    对全角空格/Unicode 折叠的处理不一致 —— 表现是「有几道菜只在一条路上
    匹配得上」, 正是 2026-08-13 那 20,701.63 元差额的成因形态。
    """
    sql, _req, _base = build_sql("gross_margin", "product", "rank")
    bridge_line = [ln for ln in sql.splitlines()
                   if "b.normalized_name" in ln][0]
    assert "lower(" not in bridge_line, f"SQL 侧又做了一次规范化: {bridge_line}"
    assert "btrim(" not in bridge_line, f"SQL 侧又做了一次规范化: {bridge_line}"


def test_cost_bridge_predicate_is_read_by_both_sql_and_args():
    """🔴 `uses_cost_bridge` 是**唯一定义** —— 拼 SQL 与备实参必须同源。

    不同源的后果不是「少一层兜底」, 是 `$N` 与实参**错位**: asyncpg 报
    「参数类型不匹配」, 读起来完全不像「桥接没接上」。
    ⚠️ 阳性对照: 不含成本的指标必须判 False, 否则这条断言恒真。
    """
    from smartbi.gold.restaurant.generic_executor import uses_cost_bridge

    for key in ("food_cost", "gross_profit", "gross_margin"):
        sql, _r, _b = build_sql(key, "product", "rank")
        assert uses_cost_bridge(key) is True, key
        assert "unnest($4::text[], $5::text[])" in sql, key
    for key in ("revenue", "orders"):
        sql, _r, _b = build_sql(key, "product", "rank")
        assert uses_cost_bridge(key) is False, key
        assert "unnest(" not in sql, key


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


# ═══════════════════════════════════════════════════════════════════════════
# 扩表后新增的 4 种行处理形态 —— 它们不改 SQL, 全在行上做
# ═══════════════════════════════════════════════════════════════════════════
from smartbi.gold.restaurant.generic_executor import _post_process  # noqa: E402
from smartbi.gold.restaurant.metric_registry import (  # noqa: E402
    AGGREGATIONS, DERIVED, DIMENSIONS, METRICS, registry_size,
)


def _rows(*values):
    return [{"dim_key": f"d{i}", "dim_label": f"D{i}", "revenue": v}
            for i, v in enumerate(values)]


def test_share_percentages_sum_to_one_hundred():
    out = _post_process(_rows(50, 30, 20), AGGREGATIONS["share"], "revenue")
    assert [round(r["share"], 6) for r in out] == [50.0, 30.0, 20.0]
    assert round(sum(r["share"] for r in out), 6) == 100.0


def test_concentration_stops_once_it_covers_eighty_percent():
    """帕累托: 累计到 80% 就停 —— 多列一项就不是「几个贡献了八成」了。"""
    out = _post_process(_rows(50, 30, 15, 5), AGGREGATIONS["concentration"], "revenue")
    assert len(out) == 2, "80% 由前两项达成, 不该继续往下列"
    assert round(out[-1]["cum_share"], 6) == 80.0


def test_concentration_covers_everything_when_it_is_flat():
    """完全均匀时 80% 需要更多项 —— ⛔ 不许固定取前 N。"""
    out = _post_process(_rows(*([10] * 10)), AGGREGATIONS["concentration"], "revenue")
    assert len(out) == 8, f"均匀分布下要 8 项才到 80%, 实际 {len(out)}"


def test_extremes_keeps_both_ends_not_just_the_top():
    out = _post_process(_rows(100, 60, 40, 10), AGGREGATIONS["extremes"], "revenue")
    assert [r["revenue"] for r in out] == [100, 10]


def test_extremes_needs_two_rows_to_have_two_ends():
    out = _post_process(_rows(100), AGGREGATIONS["extremes"], "revenue")
    assert len(out) == 1, "只有一行时不该伪造出「两端」"


def test_above_average_threshold_is_computed_not_hardcoded():
    """🔴 承重: 阈值必须来自数据。写死一个数会让它在别的租户上毫无意义。"""
    out = _post_process(_rows(100, 50, 30, 20), AGGREGATIONS["above_avg"], "revenue")
    assert [r["revenue"] for r in out] == [100], "均值 50, 只有 100 严格高于它"
    assert out[0]["_threshold"] == 50.0


def test_share_refuses_when_total_is_not_positive():
    """⛔ 总额 ≤0 时不算占比 —— 「占比 -340%」比没有占比更能误导。"""
    rows = _rows(10, -20)
    out = _post_process(rows, AGGREGATIONS["share"], "revenue")
    assert all("share" not in r for r in out)


def test_null_values_do_not_crash_or_become_zero():
    """列在但全 NULL(实测 tax_amount/table_no 就是这样) —— 不许当 0 参与占比。"""
    rows = [{"dim_key": "a", "dim_label": "A", "revenue": None},
            {"dim_key": "b", "dim_label": "B", "revenue": 100}]
    out = _post_process(rows, AGGREGATIONS["share"], "revenue")
    assert out[0]["share"] is None, "NULL 被当成 0 算进占比了"
    assert round(out[1]["share"], 6) == 100.0


def test_trend_orders_by_dimension_not_by_value():
    """🔴 承重: 趋势按维度自身排。按值排会把时间序列打乱成排行榜 ——
    图还画得出来, 但它表达的不是用户问的那件事。"""
    sql, _req, _base = build_sql("revenue", "date", "trend")
    order_line = [ln for ln in sql.splitlines() if "ORDER BY" in ln][0]
    assert "dim_key" in order_line, f"趋势没有按维度排: {order_line}"
    sql_rank, _r2, _b2 = build_sql("revenue", "date", "rank")
    assert "dim_key" not in [ln for ln in sql_rank.splitlines()
                             if "ORDER BY" in ln][0], "排名却按维度排了"


def test_registry_reached_the_agreed_size():
    """🔴 冻结表 vs 量出来的数 —— ⛔ 两边不许算自同一份数据。

    2026-08-09 的教训: 我写过一个「自洽」断言, 左右两侧都从同一个 dict 算出来,
    它是个恒真式, 一次都红不了。这里左边是**人写死的目标**(22/16/9, 判据原文),
    右边是**从登记表量出来的**。改小登记表 → 这条红。
    """
    size = registry_size()
    assert len(METRICS) + len(DERIVED) == 22, (
        f"指标应为 22(基础+派生), 实际 {len(METRICS)}+{len(DERIVED)}")
    assert size["dimensions"] == 16, f"维度应为 16, 实际 {size['dimensions']}"
    assert size["aggregations"] == 9, f"聚合应为 9, 实际 {size['aggregations']}"
    # 加法 vs 乘法: 47 条登记撑起 2000+ 个格子, 这个差距就是方案的全部理由。
    assert size["cells"] > 20 * size["registrations"], (
        f"格子数({size['cells']})相对登记数({size['registrations']})没有拉开 —— "
        f"说明登记项之间没有真正正交, 退化成了「一个登记一个格子」")


def test_every_registered_dimension_is_reachable_by_some_metric():
    """⛔ 登记了却没有任何指标能用的维度 = 一个永远拼不出 SQL 的死条目。"""
    reachable = set()
    for m in METRICS.values():
        reachable.update(m.dimensions)
    orphans = set(DIMENSIONS) - reachable
    assert not orphans, f"这些维度没有任何指标声明可用: {sorted(orphans)}"
