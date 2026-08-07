"""菜单工程四象限的分类规则 (Kasavana-Smith)。

轴二是**单份毛利贡献**(元/份)而不是毛利率 —— 90% 毛利但每份只赚 1 块的菜
(米饭)不该被叫成明星。这条是本文件存在的主要理由。

⚠️ 与 gold/queries.py 的 `menu_quadrant` 是**两个不同指标**:
那个是「销量 × 营收」(自称收入模式), 会把高价低毛利的菜叫成金牛。
两者都保留, 各自标明轴, 不要合并。
"""
from __future__ import annotations


def classify(dishes):
    """复刻 restaurant_ops_gold.gross_margin 里的分类, 用于纯逻辑验证。

    ⚠️ 这是**复刻**不是被测源码本身 —— 复刻会漂。所以下面另有一条
    test_source_uses_unit_margin_not_rate 直接扫源码, 钉住轴的选择。
    """
    priced = [d for d in dishes if d["hasCost"] and d["qty"] > 0]
    if not priced:
        return {d["name"]: None for d in dishes}
    qty_sorted = sorted(x["qty"] for x in priced)
    um_sorted = sorted(x["grossProfit"] / x["qty"] for x in priced)
    mid = len(priced) // 2
    qm = qty_sorted[mid] if len(priced) % 2 else (qty_sorted[mid - 1] + qty_sorted[mid]) / 2
    um = um_sorted[mid] if len(priced) % 2 else (um_sorted[mid - 1] + um_sorted[mid]) / 2
    out = {}
    for d in dishes:
        if not (d["hasCost"] and d["qty"] > 0):
            out[d["name"]] = None
            continue
        u = d["grossProfit"] / d["qty"]
        out[d["name"]] = (
            "明星" if d["qty"] >= qm and u >= um
            else "主力" if d["qty"] >= qm
            else "谜题" if u >= um
            else "瘦狗"
        )
    return out


def _d(name, qty, unit_margin, has_cost=True):
    return {"name": name, "qty": qty, "grossProfit": unit_margin * qty, "hasCost": has_cost}


def test_high_rate_but_tiny_unit_margin_is_not_a_star():
    """🔴 米饭: 毛利率 73% 但每份只赚 2.19 元 —— 用毛利率当轴会把它算成明星。"""
    got = classify([
        # 米饭设成**最好卖**的: 这才是「好卖但单份不赚钱」该落主力的典型。
        # (毛利率 73% 看着很高, 但每份只赚 2.19 元)
        _d("米饭", 200000, 2.19),
        _d("罗氏虾", 144573, 78.57),   # 单份毛利最高, 但销量低于中位数
        _d("鲈鱼", 177849, 55.06),
        _d("藤椒鸡", 177122, 38.67),
    ])
    assert got["米饭"] != "明星", "单份只赚 2 块的菜不该是明星 —— 轴用错了(毛利率而非贡献额)"
    assert got["米饭"] == "主力", got   # 好卖不赚钱 → 降本或微调价
    assert got["罗氏虾"] == "谜题", got  # 赚钱不好卖 → 推荐位/服务员话术


def test_star_is_both_popular_and_profitable():
    got = classify([
        _d("鲈鱼", 177849, 55.06),
        _d("藤椒鸡", 177122, 38.67),
        _d("娃娃菜", 71312, 18.80),
        _d("米饭", 71480, 2.19),
    ])
    assert got["鲈鱼"] == "明星", got


def test_no_cost_dish_is_not_classified():
    """没配方的菜用行业默认成本率估算, 对每道菜是同一比率 —— 拿它排序等于按营收排。
    必须排除在四象限外, 而不是给个看着像结论的格子。"""
    got = classify([
        _d("鲈鱼", 100, 50.0),
        _d("新菜", 100, 30.0, has_cost=False),
    ])
    assert got["新菜"] is None


def test_all_dishes_unpriced_yields_no_quadrant():
    got = classify([_d("A", 10, 5.0, has_cost=False), _d("B", 20, 5.0, has_cost=False)])
    assert set(got.values()) == {None}


def test_source_uses_unit_margin_not_rate():
    """🔴 直接扫源码: 轴二必须是 grossProfit/qty, 不能是 marginRate。

    上面那些用的是复刻逻辑, 复刻会漂; 这条钉住真源码。
    """
    import io
    from pathlib import Path
    # 2026-08-07: 这段口径从 api/restaurant_ops_gold.py 搬到了
    # gold/restaurant/dish_margin.py —— 端点原来是**第二份** per-dish 成本实现
    # (它自己的注释承认了), 发现层的毛利规则要用同一份, 所以抽出来共用。
    # 本条断言的性质没变, 只是跟着代码换了位置。
    src = Path(__file__).resolve().parents[2] / "gold" / "restaurant" / "dish_margin.py"
    text = io.open(src, encoding="utf-8", newline="").read()
    assert "菜单工程四象限" in text, (
        f"{src.name} 里找不到四象限口径 —— 它又被搬走了? 本条要跟着更新, "
        "否则会变成一条永远绿的空断言"
    )
    block = text[text.index("菜单工程四象限"):text.index("avgRate 用")]
    assert 'x["grossProfit"] / x["qty"]' in block, "轴二不是单份毛利贡献了"
    assert '"marginRate"' not in block, "轴二被改成毛利率 —— 米饭那类菜会被误判成明星"
