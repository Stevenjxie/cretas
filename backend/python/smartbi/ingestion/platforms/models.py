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
class NormalizedDiscount:
    """折扣构成的一行：这笔让利记在哪个活动头上。

    ⛔ 与 `NormalizedOrder.discount_cents`(标量总额)的关系是**归属而非重复**：
       构成各行 amount_cents 之和恒等于订单的 discount_cents。下游据此把
       「让了多少」拆成「因为哪个活动让的」——「团购券划不划算」问的就是这个。

    ⚠️ face_value / actual_price 只对**预售型团购券**有意义(卖 88 元的 128 元套餐券)。
       平台即时满减没有票面，两列为 0 表示「这个活动没有票面」，**不是**「不知道」。
    """
    name: str
    discount_type: str
    amount_cents: int
    face_value_cents: int = 0
    actual_price_cents: int = 0


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
    #: 折扣构成。空列表 = 这笔订单没有折扣(或平台不给构成), **不是**「不知道」。
    discounts: List[NormalizedDiscount] = field(default_factory=list)


@dataclass(frozen=True)
class FetchPage:
    orders: List[NormalizedOrder]
    next_cursor: str
    has_more: bool
