"""客流曲线：把当日单量摊到 1440 分钟。

午市 11:00-14:00、晚市 17:00-21:00 双峰。三种业态曲线形状不同——
商场店晚市更陡（下班人流），社区店午市更平缓（居民就近）。
"""
from __future__ import annotations

import math

_LUNCH_PEAK = 12 * 60 + 30
_DINNER_PEAK = 19 * 60
# 营业时段是连续的（11:00-21:00），午市/晚市只是同一连续窗口里的两个高峰——
# 14:00-17:00 之间客流走低但不为 0（还有散客），只有整段营业时段之外
# （凌晨/清晨）才是真正的 0 客流。用两个独立的 in-window 判断会在
# 14:00-17:00 制造一个人为的死区（该分钟不落在任一 window 内 -> 强制 0），
# 与"下午还有稀疏客流"的事实不符，所以改成单一连续营业窗口 + 双峰权重叠加。
_BUSINESS_WINDOW = (11 * 60, 21 * 60)

# format -> (午市权重, 晚市权重, 峰宽分钟)
_SHAPE = {
    "mall": (1.0, 1.8, 55),
    "flagship": (1.0, 1.5, 65),
    "community": (1.0, 1.15, 75),
}


def _bell(minute: int, peak: int, width: int) -> float:
    return math.exp(-((minute - peak) ** 2) / (2.0 * width * width))


def minute_weight(store_format: str, minute_of_day: int) -> float:
    """该分钟的相对客流权重。营业时段外恒为 0（不产生订单）。"""
    if store_format not in _SHAPE:
        raise ValueError(f"未知业态: {store_format}")
    if not (_BUSINESS_WINDOW[0] <= minute_of_day < _BUSINESS_WINDOW[1]):
        return 0.0
    lunch_w, dinner_w, width = _SHAPE[store_format]
    total = lunch_w * _bell(minute_of_day, _LUNCH_PEAK, width)
    total += dinner_w * _bell(minute_of_day, _DINNER_PEAK, width)
    return total


def daily_minute_quota(store_format: str, daily_orders: int) -> list[int]:
    """按曲线把 daily_orders 摊到 1440 分钟。求和精确等于 daily_orders。

    用最大余数法分配，避免逐分钟四舍五入导致总数漂移。
    """
    weights = [minute_weight(store_format, m) for m in range(1440)]
    total_w = sum(weights)
    if total_w <= 0:
        raise ValueError(f"业态 {store_format} 的曲线权重全为 0")
    exact = [w / total_w * daily_orders for w in weights]
    quota = [int(x) for x in exact]
    remainder = daily_orders - sum(quota)
    # 余数按小数部分从大到小补齐
    order = sorted(range(1440), key=lambda i: exact[i] - quota[i], reverse=True)
    for i in order[:remainder]:
        quota[i] += 1
    return quota
