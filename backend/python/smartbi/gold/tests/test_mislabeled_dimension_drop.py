"""去掉「误标且无人能答」的维度。

🔴 2026-08-07 prod 实测:「最近30天**食材成本**占营收多少」被 T3 标成
   `dimensions=('ingredient',)`，而它问的是**全店比率**（食材成本/营收），
   没有任何按食材的拆分。计划里的 resolver 都不支持 ingredient 粒度，
   于是被执行前的维度闸拦成反问。

判据与「『**加权**毛利率』的『加权』被当成菜名」同源：
**维度由「问的是哪个粒度」决定，不是由句子里出现了哪个名词决定。**
"""
from dataclasses import dataclass
from typing import Tuple

import pytest

from smartbi.gold.restaurant.restaurant_intent_service import (
    _drop_unanswerable_mislabeled_dimensions as drop,
)


@dataclass
class _Spec:
    dimensions: Tuple[str, ...]


def test_mislabeled_ingredient_is_dropped_when_nobody_can_serve_it():
    """两个条件都成立 -> 去掉。这是 prod 上那条 D 的形状。"""
    spec = _Spec(dimensions=("ingredient",))
    got = drop(spec, ("RESTAURANT_OPS_RECIPE_COST", "RESTAURANT_OPS_SALES_SUMMARY"),
               "最近30天食材成本占营收多少")
    assert got.dimensions == ()


def test_supported_dimension_is_never_touched():
    """🔴 计划里有 resolver 支持该粒度 -> **绝不能动**。

    只满足「没点名实体」这一个条件是不够的: 实测 `extract_dish_candidate`
    对「鲈鱼的损耗多少」也返回 None（它是按菜品-指标句式调的），只凭它会
    **无条件**剥掉维度，把「损耗最高的食材是哪个」这类真·食材粒度问题弄坏。
    这条就是那个陷阱的哨兵。
    """
    spec = _Spec(dimensions=("ingredient",))
    # WASTAGE_TOP 声明 {"ingredient"} —— 支持, 所以不管有没有点名都不动。
    got = drop(spec, ("RESTAURANT_OPS_WASTAGE_TOP",), "损耗最高的食材是哪个")
    assert got.dimensions == ("ingredient",)
    assert got is spec, "没有改动时应原样返回, 不要白造一个新对象"


def test_named_ingredient_keeps_the_dimension():
    """点名了具体食材 -> 那就是真按食材粒度问的, 保留。"""
    spec = _Spec(dimensions=("ingredient",))
    got = drop(spec, ("RESTAURANT_OPS_RECIPE_COST",), "罗氏虾的食材成本是多少")
    assert got.dimensions == ("ingredient",)


def test_unknown_dimension_kinds_are_left_alone():
    """只登记了「判得了」的粒度; 判不了的一律不动 —— 猜错的代价是答非所问。"""
    spec = _Spec(dimensions=("channel", "customer"))
    got = drop(spec, ("RESTAURANT_OPS_RECIPE_COST",), "随便问点什么")
    assert got.dimensions == ("channel", "customer")
    assert got is spec


def test_only_the_mislabeled_one_is_dropped():
    spec = _Spec(dimensions=("time", "ingredient"))
    got = drop(spec, ("RESTAURANT_OPS_RECIPE_COST",), "最近30天食材成本占营收多少")
    assert got.dimensions == ("time",)


@pytest.mark.parametrize("plan", [(), None])
def test_empty_plan_does_not_crash(plan):
    spec = _Spec(dimensions=("ingredient",))
    got = drop(spec, plan or (), "最近30天食材成本占营收多少")
    # 没有计划就无从判「谁支持」—— 保守起见按「无人支持」处理, 但不能抛。
    assert got.dimensions in ((), ("ingredient",))
