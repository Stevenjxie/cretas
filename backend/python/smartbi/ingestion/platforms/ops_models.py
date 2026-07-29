"""后厨供应链归一化模型（领料 / 损耗 / 盘点）。

与 `models.py` 的取向一致：
  * 金额一律「分」为单位的整数；
  * **用量一律「毫单位」整数**（实际用量 × 1000）。各平台小数位约定不一，
    浮点累加会让跨平台对账出现假性不平。到写 Silver 那一步再换算 ——
    Silver 的 numeric(14,4) 能无损接住毫单位。

食材只带名称与分类：平台不会给我们它自己的食材主键，靠名称在
`dim_ingredient` 上 get-or-create（与菜品维度同一套做法）。
"""
from __future__ import annotations

import datetime
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass(frozen=True)
class NormalizedIngredientRef:
    """一条单据里指向的食材。name 是 get-or-create 的自然键。"""
    name: str
    category: Optional[str] = None
    unit: Optional[str] = None


@dataclass(frozen=True)
class NormalizedRequisition:
    platform: str
    doc_no: str                 # 平台单号 → Silver 的 source_pk
    store_code: str
    biz_date: datetime.date
    ingredient: NormalizedIngredientRef
    qty_milli: int
    cost_cents: int
    # ⚠️ 下游 Gold 按 status 过滤，写错就**静默为 0**（Silver 有行、AI 照答，
    # 但按食材的 KPI 全空）。实测各表口径不同：
    #   领料 WHERE status IN ('APPROVED','SUBMITTED')
    #   损耗 WHERE status = 'APPROVED'
    #   盘点 WHERE status = 'COMPLETED'
    # 所以这里**不给默认值** —— 平台没给就报错，绝不替它编一个。
    status: str


@dataclass(frozen=True)
class NormalizedWastage:
    platform: str
    doc_no: str
    store_code: str
    biz_date: datetime.date
    ingredient: NormalizedIngredientRef
    wastage_type: str           # 变质 / 加工损耗 / 客诉退菜
    status: str
    qty_milli: int
    cost_cents: int


@dataclass(frozen=True)
class NormalizedStocktaking:
    platform: str
    doc_no: str
    store_code: str
    biz_date: datetime.date
    ingredient: NormalizedIngredientRef
    status: str
    system_qty_milli: int
    actual_qty_milli: int
    # 实盘 - 系统账，负数是盘亏。金额同号。
    diff_cost_cents: int

    @property
    def diff_qty_milli(self) -> int:
        return self.actual_qty_milli - self.system_qty_milli


@dataclass(frozen=True)
class OpsFetchPage:
    """一类单据的一页。游标语义与订单页完全一致（全局单调 seq）。"""
    kind: str                   # requisition / wastage / stocktaking
    items: List[object] = field(default_factory=list)
    next_cursor: str = "0"
    has_more: bool = False
