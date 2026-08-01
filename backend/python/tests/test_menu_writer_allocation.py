"""菜品成本分摊口径的判据。

这条口径同时被 `smartbi/scripts/seed_mock_rest_menu.py`(首次 seed) 与
`main.py` 的常驻同步(connector) 使用 —— 两处共用 `allocate_line_costs` 一份实现,
本文件守住它。各写一份的后果不是"数字略有出入", 而是**同一批菜存在两套配方行**,
食材成本直接翻倍, 且两套行看起来都合法(2026-08-01 写 connector 时实际抓到)。
"""
from __future__ import annotations

from decimal import Decimal

import pytest

from smartbi.ingestion.platforms.menu_writer import (
    allocate_line_costs,
    recipe_source_pk,
)


def test_逐菜分摊后恰好等于菜品自报成本():
    """毛利是拿 line_cost 逐行减出来的, 分摊合计必须**精确**等于菜品成本。

    舍入残差补到金额最大的一行 —— 不补的话十几道菜累计能漂出几分钱。
    """
    dish_cost = {"d1": 2100, "d2": 80}          # 分
    unit_price = {"i1": 2400, "i2": 8600, "i3": 1500, "i4": 620}
    recipe = [
        ("d1", "i1", 220), ("d1", "i2", 8), ("d1", "i3", 25),
        ("d2", "i4", 110),
    ]
    lines = allocate_line_costs(dish_cost, unit_price, recipe)

    per = {}
    for ln in lines:
        per[ln.dish_code] = per.get(ln.dish_code, Decimal(0)) + ln.line_cost
    assert per["d1"] == Decimal("21.0000")
    assert per["d2"] == Decimal("0.8000")


def test_分摊保留各行原始占比():
    """分摊只是**等比放大**, 不能改变主料之间的相对关系。"""
    lines = allocate_line_costs(
        {"d1": 10000},
        {"i1": 1000, "i2": 1000},
        [("d1", "i1", 300), ("d1", "i2", 100)],
    )
    by_ing = {ln.ingredient_code: ln.line_cost for ln in lines}
    # 原始占比 3:1, 分摊后仍应是 3:1
    assert by_ing["i1"] == Decimal("75.0000")
    assert by_ing["i2"] == Decimal("25.0000")


def test_每道菜恰好一个主料且是金额最大的那行():
    lines = allocate_line_costs(
        {"d1": 5000},
        {"i1": 100, "i2": 9000},
        [("d1", "i1", 500), ("d1", "i2", 500)],
    )
    mains = [ln for ln in lines if ln.is_main_ingredient]
    assert len(mains) == 1
    assert mains[0].ingredient_code == "i2"     # 单价高的那个


def test_悬空配方行必须硬失败而不是被跳过():
    """禁降级: 配方指向不存在的菜/食材, 写进去就是永远算不出成本的行。

    静默跳过的表现是「毛利榜少了一道菜」—— 没人会察觉。
    """
    with pytest.raises(ValueError, match="未知菜品"):
        allocate_line_costs({"d1": 100}, {"i1": 100}, [("d_missing", "i1", 10)])
    with pytest.raises(ValueError, match="未知食材"):
        allocate_line_costs({"d1": 100}, {"i1": 100}, [("d1", "i_missing", 10)])


def test_原始成本为零的菜硬失败():
    """分母为 0 分摊不出来。返回 0 成本会让这道菜显示 100% 毛利。"""
    with pytest.raises(ValueError, match="原始成本为 0"):
        allocate_line_costs({"d1": 100}, {"i1": 0}, [("d1", "i1", 10)])


def test_幂等键由两个code拼成且稳定():
    """seed 与 connector 必须产出**同一个** source_pk, 否则同一批菜会有两套行。"""
    assert recipe_source_pk("mp_dish_001", "mp_ingr_001") == "mp_dish_001__mp_ingr_001"
    assert (recipe_source_pk("mp_dish_001", "mp_ingr_001")
            == recipe_source_pk("mp_dish_001", "mp_ingr_001"))


def test_seed脚本与connector用同一份口径():
    """seed 脚本不得自带第二份分摊实现 —— 它必须委托本模块。"""
    from smartbi.scripts import seed_mock_rest_menu as seed

    dishes, ingredients, recipe = seed.snapshot_as_normalized()
    direct = allocate_line_costs(
        {d.dish_code: d.cost_cents for d in dishes},
        {i.ingredient_code: i.unit_price_cents for i in ingredients},
        [(r.dish_code, r.ingredient_code, r.qty_milli) for r in recipe],
    )
    via_seed = seed.compute_recipe_lines()
    assert direct == via_seed, "seed 与 connector 的分摊结果分叉了"
