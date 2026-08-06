"""毛利发现规则的判定测试。

⚠️ 用例数字取自 **2026-08-07 prod (MOCK_REST) 实测**的 10 道菜, 不是编的:
   /api/smartbi/restaurant-ops/gross-margin?days=30 的 dishes[]。
   这样「规则在真实数据上到底出不出得来」是被钉住的 —— 上一版低毛利规则
   在同一份数据上产出 0 条, 就是这么发现的。
"""
import pytest

from smartbi.gold.restaurant import margin_findings as M


def _dish(name, qty, revenue, unit_cost, *, has_cost=True):
    total_cost = unit_cost * qty
    return {
        "name": name,
        "qty": float(qty),
        "revenue": float(revenue),
        "foodCostUnit": float(unit_cost),
        "totalCost": total_cost,
        "grossProfit": float(revenue) - total_cost,
        "hasCost": has_cost,
        "isEstimated": not has_cost,
    }


#: 2026-08-07 prod MOCK_REST 近 30 天实测 (name, qty, revenue, unitCost)。
#: 单份毛利中位 ¥27.51 / 销量中位 159,113.5 —— 谜题象限只有罗氏虾。
_PROD_DISHES = [
    _dish("米饭",     143238,   429714.00,  0.81),
    _dish("酸梅汤",   143583,  1722996.00,  2.79),
    _dish("凉拌木耳", 143012,  2574216.00,  3.35),
    _dish("娃娃菜",   142907,  3143954.00,  3.19),
    _dish("红糖糍粑", 175392,  4560192.00,  2.78),
    _dish("干锅花菜", 174644,  6636472.00,  6.21),
    _dish("藤椒鸡",   175368, 10171344.00, 19.33),
    _dish("水煮牛肉", 175635, 11943180.00, 28.15),
    _dish("鲈鱼",     176071, 15494248.00, 32.94),
    _dish("罗氏虾",   143188, 18328064.00, 49.43),
]


def _payload(dishes, *, coverage_ratio=1.0, window_days=30):
    return {
        "windowDays": window_days,
        "dishes": dishes,
        "coverage": {
            "dishCount": sum(1 for d in dishes if d["hasCost"]),
            "totalDishCount": len(dishes),
            "revenueRatio": coverage_ratio,
        },
    }


@pytest.fixture
def patched(monkeypatch):
    """把 compute_dish_margins 换成固定 payload —— 本模块测的是**判定**, 不是取数。"""
    def _install(payload):
        async def _fake(pool, factory_id, *, days=30):
            return payload
        monkeypatch.setattr(M, "compute_dish_margins", _fake)
    return _install


@pytest.mark.asyncio
async def test_prod_data_yields_exactly_the_puzzle_dish(patched):
    """🔴 在真实 prod 读数上必须出得来 —— 且只出罗氏虾那一条。

    这条是整个规则存在的理由。上一版「低单份毛利菜」在**同一份数据**上产出
    0 条(低毛利的米饭/酸梅汤都在销量中位数以下被闸挡掉), 那种规则做了等于没做。
    """
    patched(_payload(_PROD_DISHES))
    got = await M.detect_puzzle_dishes(None, "MOCK_REST")

    assert got["applicable"] is True
    assert [f["subject_name"] for f in got["findings"]] == ["罗氏虾"]
    facts = got["findings"][0]["facts"]
    assert facts["unitMargin"] == pytest.approx(78.57, abs=0.01)
    assert facts["unitMarginMedian"] == pytest.approx(27.51, abs=0.01)
    assert facts["qty"] == 143188
    assert facts["pricedDishCount"] == 10
    assert facts["coverageRevenueRatio"] == 100.0


@pytest.mark.asyncio
async def test_estimated_dishes_never_enter_the_ranking(patched):
    """无配方的菜走行业默认成本率 —— 成本率是同一个常数, 单份毛利与售价成正比。

    放进来排出的是价格榜, 不是毛利榜。这里给一道售价极高的无配方菜, 它**不该**
    出现在结果里, 也不该把中位数拉走。
    """
    dishes = _PROD_DISHES + [_dish("天价无配方菜", 1000, 9_999_999.0, 0.0, has_cost=False)]
    patched(_payload(dishes, coverage_ratio=0.8))
    got = await M.detect_puzzle_dishes(None, "MOCK_REST")

    assert [f["subject_name"] for f in got["findings"]] == ["罗氏虾"]
    assert got["findings"][0]["facts"]["pricedDishCount"] == 10


@pytest.mark.asyncio
async def test_too_few_priced_dishes_is_skipped_not_empty(patched):
    """样本太少 → 「判不了」, **不是**「均正常」。三态的第三态。"""
    patched(_payload(_PROD_DISHES[:3]))
    got = await M.detect_puzzle_dishes(None, "MOCK_REST")

    assert got["applicable"] is False
    assert "3 道" in got["skip_reason"]
    assert got["findings"] == []


@pytest.mark.asyncio
async def test_low_cost_coverage_is_skipped_not_a_store_wide_claim(patched):
    """有配方的菜只覆盖少数营收时, 它们的中位数代表不了全店 → 诚实跳过。"""
    patched(_payload(_PROD_DISHES, coverage_ratio=0.2))
    got = await M.detect_puzzle_dishes(None, "MOCK_REST")

    assert got["applicable"] is False
    assert "20%" in got["skip_reason"]


@pytest.mark.asyncio
async def test_flat_margins_are_skipped_not_reported_as_normal(patched):
    """所有菜单份毛利相同 → 中位数分不出高低, 这是判不了而不是没有谜题菜。"""
    # 单份毛利 = revenue/qty - unitCost, 所以 revenue 必须**随 qty 等比**才真的持平。
    # (第一版写成固定 revenue + 递增 qty, 单份毛利其实在变 —— fixture 自己没做到
    #  它要测的前提, 测试当场红。)
    flat = [_dish(f"菜{i}", 100 + i, (100 + i) * 10.0, 5.0) for i in range(6)]
    patched(_payload(flat))
    got = await M.detect_puzzle_dishes(None, "MOCK_REST")

    assert got["applicable"] is False
    assert "分不出高低" in got["skip_reason"]


@pytest.mark.asyncio
async def test_upstream_failure_raises_instead_of_reporting_normal(patched, monkeypatch):
    """取数炸了必须上抛 —— 返回空列表会被渲染成「均正常」, 把故障说成健康。"""
    async def _boom(pool, factory_id, *, days=30):
        raise RuntimeError("db down")
    monkeypatch.setattr(M, "compute_dish_margins", _boom)

    with pytest.raises(RuntimeError, match="菜品毛利口径不可用"):
        await M.detect_puzzle_dishes(None, "MOCK_REST")


@pytest.mark.asyncio
async def test_no_absolute_money_or_volume_constants_in_source():
    """🔴 阈值必须全是相对量。

    唯一活跃的餐饮租户是假数据(30 天营收 ¥7500 万、销量双峰无长尾), 任何在它
    上面调出来的绝对金额/销量常数都是对小说调参, 到真客户那里必然失灵。
    直接扫源码 —— 复刻逻辑会漂, 这条钉真源码。
    """
    import io
    import re
    from pathlib import Path
    src = Path(M.__file__)
    body = "\n".join(
        line for line in io.open(src, encoding="utf-8").read().split("\n")
        if not line.strip().startswith("#")
    )
    # 模块级阈值常量里不许出现「像金额/销量」的数量级(>= 1000)。
    for name, value in re.findall(r"^(_[A-Z_]+)\s*=\s*([0-9_.]+)", body, re.M):
        assert float(value.replace("_", "")) < 1000, (
            f"{name}={value} 看起来是绝对金额或绝对销量阈值"
        )
