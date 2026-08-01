"""食材成本口径的判据。

同时被 `main.py` 的常驻 connector 与 `smartbi/scripts/seed_mock_rest_menu.py`
(手动扳机) 使用 —— 两者共用 `compute_line_costs` 一份实现, 本文件守住它。
各写一份的后果不是「数字略有出入」, 而是**同一批菜存在两套配方行**, 食材成本
直接翻倍, 且两套行看起来都合法(2026-08-01 写 connector 时实际抓到)。

🔴 口径本身也换过一次, 值得记下来:
早期平台给的配方每道菜只列 2-4 种**主料**且用量偏小, 逐行相加只有 19% 食材成本率
(毛利率 81%)。当时的补偿是「按占比摊到 dish.cost_cents」—— 那是**语义错的**:
`dish.cost_cents` 是**全成本**(含人工水电), 摊过去等于把人工水电算进了 `food_cost`,
成本率变成 42%, 那是全成本率不是食材成本率。
根治在上游(平台把配方补完整, 22→72 行), 现在逐行相加本身就落在 32.3%,
所以这里直接 `line_cost = qty × unit_price`, 三个事实完全自洽。
"""
from __future__ import annotations

from decimal import Decimal

import pytest

from smartbi.ingestion.platforms.menu_writer import (
    compute_line_costs,
    recipe_source_pk,
)


def test_逐行成本就是用量乘单价():
    """三个事实(用量/单价/line_cost)必须自洽 —— 不再有任何分摊或缩放。"""
    lines = compute_line_costs(
        {"i1": 2400, "i2": 8600},                 # 24.00 / 86.00 元每 kg
        [("d1", "i1", 600), ("d1", "i2", 18)],    # 0.6kg / 0.018kg
    )
    by = {ln.ingredient_code: ln.line_cost for ln in lines}
    assert by["i1"] == Decimal("14.4000")         # 0.6 × 24.00
    assert by["i2"] == Decimal("1.5480")          # 0.018 × 86.00


def test_主料是金额最大的那行且每菜恰好一个():
    lines = compute_line_costs(
        {"i1": 100, "i2": 9000},
        [("d1", "i1", 500), ("d1", "i2", 500)],
    )
    mains = [ln for ln in lines if ln.is_main_ingredient]
    assert len(mains) == 1
    assert mains[0].ingredient_code == "i2"


def test_悬空配方行必须硬失败而不是被跳过():
    """禁降级: 配方指向不存在的菜/食材, 写进去就是永远算不出成本的行。

    静默跳过的表现是「毛利榜少了一道菜」—— 没人会察觉。
    """
    with pytest.raises(ValueError, match="未知菜品"):
        compute_line_costs({"i1": 100}, [("d_missing", "i1", 10)], known_dishes=["d1"])
    with pytest.raises(ValueError, match="未知食材"):
        compute_line_costs({"i1": 100}, [("d1", "i_missing", 10)])


def test_成本全为零的配方硬失败():
    """返回 0 成本会让这道菜显示 100% 毛利, 比没有配方更糟。"""
    with pytest.raises(ValueError, match="食材成本为 0"):
        compute_line_costs({"i1": 0}, [("d1", "i1", 10)])


def test_幂等键由两个code拼成且稳定():
    """connector 与手动扳机必须产出**同一个** source_pk, 否则同一批菜会有两套行。"""
    assert recipe_source_pk("mp_dish_001", "mp_ingr_001") == "mp_dish_001__mp_ingr_001"


def test_模拟端配方算出的食材成本率落在餐饮正常区间():
    """守住上游: 平台若把配方改回「只有主料」, 成本率会掉出区间, 这条会红。

    判据用**菜单口径**(逐菜售价与成本直接相加)而非销量加权 —— 后者依赖 POS 数据,
    在单测里拿不到。菜单口径 32.3%, 销量加权 32.3%(实测巧合接近), 都在区间内。
    """
    import sys
    from pathlib import Path
    mp = Path(__file__).resolve().parents[3] / "mock-platform"
    if not mp.exists():                      # 仓库布局变了就跳过, 不是本模块的责任
        pytest.skip("mock-platform 不在预期位置")
    sys.path.insert(0, str(mp))
    try:
        from mock_platform.world import seed as mp_seed
    finally:
        sys.path.remove(str(mp))

    price = {n: p for n, _c, p, _cost, _g in mp_seed._DISHES}
    unit_price = {n: up for n, _c, _u, up, _s, _st in mp_seed._INGREDIENTS}

    tot_cost = tot_price = Decimal(0)
    for dish, rec in mp_seed._RECIPES.items():
        cost = sum(
            (Decimal(q) / 1000 * Decimal(unit_price[ing]) / 100) for ing, q in rec
        )
        tot_cost += cost
        tot_price += Decimal(price[dish]) / 100
        # 逐菜: 允许品类差异(素菜/饮品天然低), 但不能低到荒谬
        assert cost > 0, f"{dish} 配方算不出成本"

    ratio = float(tot_cost / tot_price * 100)
    assert 25 <= ratio <= 45, (
        f"菜单口径食材成本率 {ratio:.1f}% 掉出餐饮正常区间 —— "
        "多半是平台配方又退回「只列主料」了(那会算出 19%)"
    )
