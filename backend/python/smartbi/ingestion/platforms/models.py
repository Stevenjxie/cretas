"""平台归一化模型。

金额一律「分」为单位的整数: 各平台小数位约定不一, 浮点累加会让跨平台对账
出现假性不平。到写 Silver 那一步再换算成元。
"""
from __future__ import annotations

import datetime
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass(frozen=True)
class NormalizedItem:
    dish_name: str
    qty: int
    price_cents: int
    amount_cents: int
    # 菜品分类(热菜/凉菜/主食...)。可空: 不是每个平台都给, 给了就落到
    # dim_product.category 供菜品维度分组用。
    category: Optional[str] = None


@dataclass(frozen=True)
class NormalizedPayment:
    method: str
    amount_cents: int


@dataclass(frozen=True)
class NormalizedOrder:
    platform: str
    platform_order_no: str
    store_code: str
    channel: str
    placed_at: datetime.datetime
    biz_date: datetime.date
    gross_cents: int
    discount_cents: int
    net_cents: int
    #: 渠道侧成本(外卖平台抽佣 / 团购券核销费), 按实付净额抽。堂食为 0。
    #: ⛔ 与 discount_cents 分开: 折扣是让给顾客的, 抽佣是付给平台的,
    #:    两者的处置动作完全不同(调价格策略 vs 谈费率/引流私域)。
    #: 默认 0 —— 老适配器不给这个字段时行为逐字不变。
    platform_fee_cents: int = 0
    guest_count: int = 1
    items: List[NormalizedItem] = field(default_factory=list)
    payments: List[NormalizedPayment] = field(default_factory=list)


@dataclass(frozen=True)
class FetchPage:
    orders: List[NormalizedOrder]
    next_cursor: str
    has_more: bool
