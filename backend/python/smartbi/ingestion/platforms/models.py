"""平台归一化模型。

金额一律「分」为单位的整数: 各平台小数位约定不一, 浮点累加会让跨平台对账
出现假性不平。到写 Silver 那一步再换算成元。
"""
from __future__ import annotations

import datetime
from dataclasses import dataclass, field
from typing import List


@dataclass(frozen=True)
class NormalizedItem:
    dish_name: str
    qty: int
    price_cents: int
    amount_cents: int


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
    guest_count: int
    items: List[NormalizedItem] = field(default_factory=list)
    payments: List[NormalizedPayment] = field(default_factory=list)


@dataclass(frozen=True)
class FetchPage:
    orders: List[NormalizedOrder]
    next_cursor: str
    has_more: bool
