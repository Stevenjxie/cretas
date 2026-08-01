"""菜单主数据的归一化模型(菜品 / 食材 / 配方)。

与 `models.py` / `ops_models.py` 同一取向: 金额一律「分」、数量一律「千分之一」
的整数, 到写库那一步再换算成元 —— 各平台小数位约定不一, 浮点累加会让跨平台
对账出现假性不平。

与那两个模块的**区别**: 这里是主数据不是流水。它没有 bizDate、没有门店、也不
按天增量 —— 同一批菜品可以被反复拉取, 幂等由 (factory_id, source_pk) 保证。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Tuple


@dataclass(frozen=True)
class NormalizedDish:
    """一道菜。`cost_cents` 是本切片存在的理由 —— 没有它就算不出毛利。"""

    platform: str
    dish_code: str
    name: str
    category: Optional[str]
    price_cents: int
    cost_cents: int


@dataclass(frozen=True)
class NormalizedIngredient:
    platform: str
    ingredient_code: str
    name: str
    category: Optional[str]
    unit: Optional[str]
    unit_price_cents: int


@dataclass(frozen=True)
class NormalizedRecipeLine:
    platform: str
    dish_code: str
    ingredient_code: str
    qty_milli: int


@dataclass(frozen=True)
class MenuFetchPage:
    """一页主数据。游标语义与订单/供应链一致, 只是走主键而非 seq。"""

    items: Tuple[object, ...]
    next_cursor: str
    has_more: bool


__all__ = [
    "NormalizedDish",
    "NormalizedIngredient",
    "NormalizedRecipeLine",
    "MenuFetchPage",
]
