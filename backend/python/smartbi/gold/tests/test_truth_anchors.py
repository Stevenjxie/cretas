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

from smartbi.scripts.registry_truth_check import _ANCHORS


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
