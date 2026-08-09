"""字段映射登记表 —— 与现有 keruyun 适配器的**等价性**证明。

⛔ 这份测试存在的唯一理由：登记表要替代的是**跑在 prod 上、今天刚验证过**
   的取数路径。「看起来一样」不构成证据 —— 必须拿同一份报文跑两条路，
   逐字段相等才算数。不然换过去就是拿 prod 的金额口径冒险。
"""
import datetime

import pytest

from smartbi.ingestion.platforms.field_registry import (
    KERUYUN,
    FieldMappingError,
    assert_registry_self_consistent,
    extract,
)
from smartbi.ingestion.platforms.keruyun import KeruyunAdapter, _strict_int


#: 与生产报文同形（含今天新加的 platformFee / discounts）
_RAW = {
    "orderNo": "MK202608010100000001",
    "shopCode": "S01",
    "channel": "takeaway",
    "placedAt": "2026-08-01T12:34:56",
    "bizDate": "2026-08-01",
    "grossAmount": 35600,
    "discountAmount": 3200,
    "netAmount": 32400,
    "platformFee": 6642,
    "guestCount": 2,
    "items": [
        {"dishName": "水煮牛肉", "dishCategory": "热菜",
         "qty": 2, "price": 6800, "amount": 13600},
    ],
    "payments": [{"method": "wechat", "amount": 32400}],
    "discounts": [
        {"name": "外卖满50减8", "type": "platform_promo", "amount": 3200,
         "faceValue": 0, "actualPrice": 0},
    ],
}

_COERCERS = {
    "int": _strict_int,
    "date": lambda v, _f: datetime.date.fromisoformat(v),
    "datetime": lambda v, _f: datetime.datetime.fromisoformat(v),
}


def test_registry_is_self_consistent():
    assert_registry_self_consistent()


def test_order_fields_match_the_live_keruyun_adapter():
    """🔴 承重: 同一份报文, 两条路逐字段相等。

    ⛔ 只比几个「重要字段」不算 —— 漏比的那个恰恰是会出事的那个
       (今天 has_discount / meal_period / staff_id 三列一起漏写过)。
    """
    live = KeruyunAdapter._to_order(_RAW)
    got = extract(_RAW, KERUYUN.order_fields, _COERCERS)

    for field, value in got.items():
        assert getattr(live, field) == value, (
            f"字段 {field} 两条路不一致: 登记表={value!r} 现有适配器={getattr(live, field)!r}")

    # 反向: 现有适配器产出的每个**标量**字段, 登记表都要覆盖到。
    # ⛔ 少覆盖一个 = 换过去之后那个字段静默变成默认值。
    scalar_fields = {
        f for f in live.__dataclass_fields__
        if f not in ("items", "payments", "discounts", "platform")
    }
    assert scalar_fields <= set(got), (
        f"登记表漏了这些字段, 换过去会静默丢失: {scalar_fields - set(got)}")


def test_item_and_payment_and_discount_rows_match():
    live = KeruyunAdapter._to_order(_RAW)

    item = extract(_RAW["items"][0], KERUYUN.item_fields, _COERCERS)
    assert live.items[0].dish_name == item["dish_name"]
    assert live.items[0].qty == item["qty"]
    assert live.items[0].amount_cents == item["amount_cents"]
    assert live.items[0].category == item["category"]

    pay = extract(_RAW["payments"][0], KERUYUN.payment_fields, _COERCERS)
    assert live.payments[0].method == pay["method"]
    assert live.payments[0].amount_cents == pay["amount_cents"]

    disc = extract(_RAW["discounts"][0], KERUYUN.discount_fields, _COERCERS)
    assert live.discounts[0].name == disc["name"]
    assert live.discounts[0].amount_cents == disc["amount_cents"]
    assert live.discounts[0].face_value_cents == disc["face_value_cents"]


def test_missing_optional_field_uses_the_declared_default():
    """老报文没有 platformFee 时按 0 —— 与现有适配器同口径。"""
    raw = {k: v for k, v in _RAW.items() if k != "platformFee"}
    live = KeruyunAdapter._to_order(raw)
    got = extract(raw, KERUYUN.order_fields, _COERCERS)
    assert got["platform_fee_cents"] == 0
    assert live.platform_fee_cents == 0


def test_missing_required_field_raises_instead_of_defaulting():
    """🔴 承重: 必填字段缺了要**抛**, 不许补默认值。

    补默认值会写出「数字看着正常但少了一块」的半条记录 ——
    比整页失败难发现得多。
    """
    raw = {k: v for k, v in _RAW.items() if k != "netAmount"}
    with pytest.raises(FieldMappingError):
        extract(raw, KERUYUN.order_fields, _COERCERS)


def test_optional_fields_must_declare_what_their_default_means():
    """⛔ 「缺失按 0」到底是「真的没有」还是「不知道」——
    决定了指标层该入账还是该报缺列。不写清楚下一个人只能靠猜。"""
    for specs in (KERUYUN.order_fields, KERUYUN.item_fields,
                  KERUYUN.payment_fields, KERUYUN.discount_fields):
        for s in specs:
            if not s.required:
                assert s.default_means, f"{s.target} 没写清默认值语义"


def test_adding_a_brand_is_a_table_not_a_code_path():
    """新接一家 POS = 加一份映射, 不是写一个适配器。

    这条钉的是**结构**: 登记表里的每一项都只是数据(字段名/是否必填/转换名),
    没有任何一项是可执行代码。一旦有人往里塞 lambda, 这条会红 ——
    那就是「每家一段逻辑」重新长回来的第一步。
    """
    for specs in (KERUYUN.order_fields, KERUYUN.item_fields,
                  KERUYUN.payment_fields, KERUYUN.discount_fields):
        for s in specs:
            assert isinstance(s.source, str)
            assert s.coerce is None or isinstance(s.coerce, str), (
                f"{s.target} 的 coerce 不是登记名而是代码 —— "
                f"每家一段逻辑会从这里长回来")
