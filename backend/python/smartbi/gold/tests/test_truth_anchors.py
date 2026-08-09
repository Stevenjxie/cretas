"""同步闸 —— 真值锚的副本不许漂离生成器源码。

🔴 为什么需要这一层（这是五轮对抗审计里被推翻三次的那个坑）：

   `registry_truth_check._ANCHORS` 是从 `mock-platform/.../seed.py` **抄过来的**。
   抄是必要的：prod 上没有部署 mock-platform，跑对账时读不到源码。
   但抄了就会漂 —— 而漂的方向恰恰是「锚跟着被改坏的数据一起变」，
   那时对账**永远绿**，等于没有闸。

⛔ 所以这条测试在**能读到 seed.py 的地方**（本地 / CI）重算一遍逐条比。
   读不到就 skip，⛔ 但不许因为读不到就当作通过 —— skip 的理由要打印出来，
   否则「闸没跑」会伪装成「闸过了」（2026-08-06 记过这个形状）。
"""
from __future__ import annotations

import pathlib
import sys

import pytest

from smartbi.scripts.registry_truth_check import (
    _GROUP_COUNT_ANCHORS,
    _ANCHORS,
    _CHANNEL_WEIGHTS,
    _RANGE_ANCHORS,
)


def _seed_module():
    """把 mock-platform 挂上 sys.path 并 import seed。读不到就返回 None。"""
    for parent in pathlib.Path(__file__).resolve().parents:
        cand = parent / "mock-platform"
        if (cand / "mock_platform" / "world" / "seed.py").is_file():
            if str(cand) not in sys.path:
                sys.path.insert(0, str(cand))
            import importlib

            return importlib.import_module("mock_platform.world.seed")
    return None


def test_anchors_match_the_generator_source():
    """🔴 承重: 锚的副本必须与 `seed.py` 逐条相等。

    ⛔ 对不上时**不许改这里的期望值** —— 要先确认是 seed.py 改了(那就同步副本)
       还是副本被人手改了(那是错的)。锚的唯一权威是 seed.py。
    """
    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到 mock-platform/mock_platform/world/seed.py —— "
                    "这台机器上没有生成器源码（prod 只部署 backend/python）。"
                    "⚠️ 这是 skip 不是 pass：同步闸在这里没有生效。")

    unit_price = {n: p for n, _c, _u, p, _s, _st in seed._INGREDIENTS}
    expected = {
        name: (price / 100.0, seed.food_cost_cents(name, unit_price) / 100.0)
        for name, _cat, price, _deprecated, _g in seed._DISHES
    }

    assert set(expected) == set(_ANCHORS), (
        f"菜品清单漂了 —— 源码有 {sorted(set(expected) - set(_ANCHORS))} 而副本没有；"
        f"副本有 {sorted(set(_ANCHORS) - set(expected))} 而源码没有")

    for dish, (price, food_cost) in expected.items():
        got_price, got_cost = _ANCHORS[dish]
        assert abs(got_price - price) < 1e-9, (
            f"{dish} 菜单价副本 {got_price} ≠ 源码 {price} —— "
            f"⛔ 别改副本, 先查是 seed.py 改了还是副本被手改了")
        assert abs(got_cost - food_cost) < 1e-4, (
            f"{dish} 每份食材成本副本 {got_cost} ≠ 源码重算 {food_cost}")


def test_absolute_anchors_match_the_generator_source():
    """绝对量锚(门店/食材/菜品数)必须与源码的种子清单等长。

    🔴 它们补的是比值锚的盲区: 日期/租户过滤写错、采集丢行时, 分子分母一起变,
       单价照样等于 128 —— 只有绝对量抓得到。
    """
    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到生成器源码 —— ⚠️ 这是 skip 不是 pass")

    import importlib

    gen = importlib.import_module("mock_platform.world.generator")

    def _want(label):
        return _GROUP_COUNT_ANCHORS[label][2]

    for label, source in (("菜品数", seed._DISHES),
                          ("门店数", seed._STORES),
                          ("食材数", seed._INGREDIENTS)):
        assert _want(label) == len(source), (
            f"{label} 的锚 {_want(label)} ≠ 源码清单长度 {len(source)}")

    # 菜品类别数 = `_DISHES` 的 category 去重；⛔ 别硬写一个数
    assert _want("菜品类别数") == len({c for _n, c, *_ in seed._DISHES})
    # 损耗类型数 = generator 的类型码表长度
    assert _want("损耗类型数") == len(gen._WASTAGE_TYPE_CODE)
    # 日期数是**函数**不是常量 —— 它随查询区间变
    import datetime as _dt

    fn = _want("日期数")
    assert callable(fn), "日期数的期望值必须是函数 —— 它随区间变，写成常量必然假红"
    assert fn((_dt.date(2026, 7, 1), _dt.date(2026, 7, 31))) == 31
    assert fn((_dt.date(2026, 7, 1), _dt.date(2026, 7, 10))) == 10


def test_fragile_dimensions_are_deliberately_not_anchored():
    """⛔ 承重: 这几个维度**故意不锚** —— 锚上去会红在**正确的变化**上。

    · `table` / `wastage_reason`：整列 NULL，只有一个「未填写」组。
      锚在 1 上 = 锚「这列还是空的」，客户真填了台位号就红。
    · `meal_period`：采集侧映射出 4 个可能值，数据里只有 2 个（生成器只在
      两段营业）—— **两个来源的交集**，生成器改营业时段就红。
    · `staff` = 门店 × 餐段，餐段脆它也脆。
    · `hour` / `city` / `brand`：值来自生成器营业时段和采集层默认值，
      **不在 seed.py / generator.py 的常量里**。

    ⛔ 判据：锚必须钉在**一个**源码事实上。靠两个东西交集才成立的是脆锚 ——
       它红的时候，你分不清是系统错了还是生成器变了。
    """
    fragile = {"table", "wastage_reason", "meal_period", "staff",
               "hour", "city", "brand"}
    anchored_dims = {v[1] for v in _GROUP_COUNT_ANCHORS.values()}
    leaked = fragile & anchored_dims
    assert not leaked, (
        f"这些维度是**故意不锚**的，却被加进了分组数锚: {sorted(leaked)} —— "
        f"⛔ 加之前先确认它的期望值钉在**一个**源码事实上")


def test_channel_weights_match_the_generator_source():
    """分布锚必须与 `generator.py::_CHANNEL_WEIGHTS` 逐个相等。

    ⚠️ 顺带钉住口径: 权重是**每张订单**抽渠道用的, 所以对账要按**单量**比,
       ⛔ 不能按营收(各渠道客单价不同, 营收占比会系统性偏离 —— 实测差 1.7 个点)。
    """
    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到生成器源码 —— ⚠️ 这是 skip 不是 pass")
    import importlib

    gen = importlib.import_module("mock_platform.world.generator")
    src = dict(zip(gen._CHANNELS, gen._CHANNEL_WEIGHTS))
    assert src == _CHANNEL_WEIGHTS, (
        f"渠道权重副本 {_CHANNEL_WEIGHTS} ≠ 源码 {src} —— "
        f"⛔ 别改副本, 先查是 generator.py 改了还是副本被手改了")
    assert abs(sum(src.values()) - 1.0) < 1e-9, "源码里的渠道权重加起来不等于 1"


def test_truth_check_compares_channel_by_order_count_not_revenue():
    """🔴 承重: 渠道分布必须拿**单量**比。

    2026-08-10 实测: 按营收比会差 1.7 个点, 而容差是 2 个点 —— 差一点就
    天天报一个不存在的问题, 而且报得很像真的。
    """
    import io as _io
    import pathlib as _pathlib

    src = _io.open(_pathlib.Path(__file__).resolve().parents[2]
                   / "scripts" / "registry_truth_check.py", encoding="utf-8").read()
    seg = src[src.index("分布锚：渠道抽样权重"):]
    assert 'metric_key="orders"' in seg, (
        "渠道分布没有按单量比 —— 按营收比会系统性偏离")
    assert 'metric_key="revenue"' not in seg.split("missing =")[0], (
        "渠道分布用了营收口径")


def test_range_anchors_match_the_generator_source():
    """区间锚必须与 `generator.py::_PLATFORM_FEE_RATE` 逐个相等。

    🔑 其中 `dine_in` 的区间是 `(0.0, 0.0)` —— 堂食**恒等于 0**。
       它钉的是「这个 0 是真的 0」, 而不是「缺数据被算成了 0」。
    ⚠️ 区间锚天然比等值锚弱: 落在区间内不等于对, 它只能抓「离谱」。
    """
    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到生成器源码 —— ⚠️ 这是 skip 不是 pass")
    import importlib

    gen = importlib.import_module("mock_platform.world.generator")
    src = {k: tuple(v) for k, v in gen._PLATFORM_FEE_RATE.items()}
    got = {k: tuple(v) for k, v in _RANGE_ANCHORS["platform_fee_rate"].items()}
    assert src == got, (
        f"抽佣率区间副本 {got} ≠ 源码 {src} —— "
        f"⛔ 别改副本, 先查是 generator.py 改了还是副本被手改了")
    assert src.get("dine_in") == (0.0, 0.0), (
        "堂食抽佣率不再恒为 0 —— 那条锚的意义(区分「真的 0」和「缺数据算成 0」)没了, "
        "要重新想它该锚什么")


def test_anchor_does_not_use_the_deprecated_cost_column():
    """⛔ 承重: 不许拿 `_DISHES` 的第 4 列当食材成本 —— 源码注释写着它**已弃用**。

    拿它当锚会红在一个不存在的问题上（水煮牛肉：弃用列 ¥29.00，
    真实食材成本 ¥28.15，差 3%——足够让闸天天报假警）。
    """
    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到生成器源码 —— ⚠️ 这是 skip 不是 pass")

    unit_price = {n: p for n, _c, _u, p, _s, _st in seed._INGREDIENTS}
    for name, _cat, _price, deprecated_cost, _g in seed._DISHES:
        real = seed.food_cost_cents(name, unit_price) / 100.0
        anchored = _ANCHORS[name][1]
        assert abs(anchored - real) < 1e-4
        if abs(deprecated_cost / 100.0 - real) > 1e-4:
            assert abs(anchored - deprecated_cost / 100.0) > 1e-4, (
                f"{name} 的锚用了**已弃用**的 cost_cents({deprecated_cost/100:.2f}) "
                f"而不是配方推导的食材成本({real:.4f})")


def test_every_registered_dish_metric_has_an_anchor():
    """⚠️ 提醒性: 菜品维度上的可加指标, 理想情况都该有锚。

    现在只锚了营收和食材成本两条 —— 销量本身没有独立锚(它是分母)。
    这条不强制, 只是把「还有哪些没锚」显式列出来, 免得以为已经全覆盖了。
    """
    from smartbi.gold.restaurant.metric_registry import METRICS

    anchored = {"revenue", "food_cost", "sales_qty"}
    product_metrics = {k for k, m in METRICS.items() if "product" in m.dimensions}
    unanchored = sorted(product_metrics - anchored)
    # ⛔ 不 assert 空 —— 那会逼人为了让测试过而乱加锚。只是把清单摆出来。
    print(f"\n菜品维度上尚无真值锚的指标: {unanchored}")
    assert anchored <= product_metrics, "锚引用了菜品维度上不存在的指标"


def test_derived_anchors_match_the_generator_distributions():
    """派生量锚的期望值必须与 `generator.py` 里的**随机分布**对得上。

    🔴 它锚住的是 `销量×全店` —— 实测**最常被问**的格子(2298 次), 而它
       一直没有锚: 它和菜品维度用同一个表达式,「各菜品加总 == 全店」对它
       是近似恒真, 量不出问题。这两条走的是另一条路(锚在订单数上)。

    ⛔ 这里**重算期望值**而不是抄一个数 —— 抄了之后 generator 改了分布,
       锚还停在旧值上, 而那时对账会红在一个「其实是对的」的数上。
    """
    from smartbi.scripts.registry_truth_check import (
        _DERIVED_ANCHORS, _GUESTS_PER_ORDER)

    seed = _seed_module()
    if seed is None:
        pytest.skip("读不到生成器源码 —— ⚠️ 这是 skip 不是 pass")
    import importlib
    import inspect
    import re

    gen = importlib.import_module("mock_platform.world.generator")
    src = inspect.getsource(gen)

    def _randint_mean(pattern: str) -> float:
        m = re.search(pattern, src)
        assert m, f"在 generator.py 里找不到 {pattern!r} —— 生成逻辑改了, 请重推期望值"
        lo, hi = int(m.group(1)), int(m.group(2))
        return (lo + hi) / 2.0

    line_mean = _randint_mean(r"line_count = rng\.randint\((\d+), (\d+)\)")
    qty_mean = _randint_mean(r"\bqty = rng\.randint\((\d+), (\d+)\)")
    guest_mean = _randint_mean(r"guest_count = rng\.randint\((\d+), (\d+)\)")

    want_dpo = line_mean * qty_mean
    got_dpo = _DERIVED_ANCHORS["dishes_per_order"][0]
    assert abs(got_dpo - want_dpo) < 1e-9, (
        f"单均出品数的锚 {got_dpo} ≠ 源码分布重算的 {want_dpo} "
        f"(E[randint]={line_mean} × {qty_mean}) —— ⛔ 别改锚, 先看 generator 改了什么")

    dine_in_w = dict(zip(gen._CHANNELS, gen._CHANNEL_WEIGHTS))["dine_in"]
    want_gpo = dine_in_w * guest_mean + (1 - dine_in_w) * 1.0
    got_gpo = _GUESTS_PER_ORDER[0]
    assert abs(got_gpo - want_gpo) < 1e-9, (
        f"单均人数的锚 {got_gpo} ≠ 源码重算的 {want_gpo}")


def test_derived_anchor_tolerance_is_wider_than_sampling_noise():
    """⚠️ 容差必须同时容下抽样噪音**和渠道权重的实现偏差**。

    2026-08-10 实测: 单均人数按**名义**权重(0.62)算期望差 0.0146(约 2.3σ),
    按**实际**抽到的权重(0.6229)只差 0.0076。容差卡在纯抽样噪音上会周期性假红。
    """
    from smartbi.scripts.registry_truth_check import (
        _DERIVED_ANCHORS, _GUESTS_PER_ORDER)

    # 6.2 万单时, 单均出品数的标准误约 0.013、单均人数约 0.006。
    assert _DERIVED_ANCHORS["dishes_per_order"][1] >= 0.05, "容差太紧, 会被抽样噪音假红"
    assert _GUESTS_PER_ORDER[1] >= 0.04, "容差太紧, 会被抽样噪音假红"
    # ⛔ 也不能太松 —— line_count 若改成 randint(2,8), 期望会跳到 10.00,
    #    容差必须小到抓得住这种改动。
    assert _DERIVED_ANCHORS["dishes_per_order"][1] < 1.0, "容差太松, 抓不到真实改动"
