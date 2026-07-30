"""堂食/外卖 拆分必须认得英文 order_type 码, 不能只认中文。

Measured on prod 2026-07-30 (MOCK_REST):

    Q: 全部门店最近30天外卖占比多少
    A: **堂食 vs 外卖（2026-07-01 至 2026-07-30）：**
       -：47,560 单
       - groupon：7,796 单
       - takeaway：21,688 单

`fact_pos_transaction.order_type` on this tenant is `dine_in` / `takeaway` /
`groupon` (verified in Gold too), while `_resolve_channel_mix` keys its dict on
the RAW value and then renders `for name in ("堂食", "外卖")`. Both lookups miss,
so the two headline lines — the占比 the question actually asked for — are never
emitted, the KPIs are empty, and the fallback loop leaks raw English codes to
the owner.

The resolver's own docstring says it was built against DEMO_REST, whose
order_type is Chinese. Nothing normalized the codes, so the answer silently
degrades on any tenant that stores them in English.
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant.restaurant_ops_router import _normalize_order_type


@pytest.mark.parametrize(
    "raw",
    ["dine_in", "dinein", "DINE_IN", "eat_in", "堂食", "堂吃", " dine_in "],
)
def test_dine_in_codes_map_to_the_dine_in_bucket(raw):
    assert _normalize_order_type(raw) == "堂食"


@pytest.mark.parametrize(
    "raw",
    ["takeaway", "take_away", "takeout", "take_out", "delivery", "waimai", "外卖"],
)
def test_takeaway_codes_map_to_the_takeaway_bucket(raw):
    assert _normalize_order_type(raw) == "外卖"


def test_known_third_channels_get_a_chinese_label():
    """团购 is neither 堂食 nor 外卖; it must stay its own bucket but stop showing
    the owner a raw English code."""
    assert _normalize_order_type("groupon") == "团购"
    assert _normalize_order_type("pickup") == "自提"
    assert _normalize_order_type("self_pickup") == "自提"


def test_unknown_codes_are_passed_through_not_dropped():
    """An unrecognised channel must still be disclosed — silently dropping it
    would make the percentages lie."""
    assert _normalize_order_type("scan_order") == "scan_order"


@pytest.mark.parametrize("empty", [None, "", "   "])
def test_empty_order_type_is_not_a_bucket(empty):
    """Unlabelled rows are counted separately as 未标注渠道; they must not become
    an empty-named line like the one seen in prod."""
    assert _normalize_order_type(empty) is None
