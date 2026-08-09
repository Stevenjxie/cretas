"""数据源字段映射登记表 —— 「这个品牌的字段叫什么」不是一段代码。

🔴 存在的理由（Steve 2026-08-09 指出的第三个膨胀点）：
   「客户原始数据 → 标准事实表」这一段现在对每个来源**各写一遍**：
   `keruyun.py` 里 13 处硬编字段名、`twodfire_adapter.py` 里 9 处。
   上线要对接多个 POS 品牌时，会变成「每来一家写一个适配器」——
   和 597 个 Java 工具、20 个 resolver 是**同一个毛病换了个位置**。

⛔ 适配器里真正**该**每家一份的只有三样：鉴权签名、分页游标、HTTP 细节。
   字段映射不在其中 —— 它是一张表，不是一段逻辑。

⚠️ 两层要分清（今天讨论清楚的）：
   · 本模块 = 品牌字段 → **标准事实表列**（一次性、几十个字段）
   · `metric_registry` = 标准列 → **业务语义**（与数据源无关）
   两层解耦后，客户接上一个字段 → 所有依赖它的格子同时点亮，零改动。

⚠️ 缺字段的语义必须显式声明，⛔ 不许静默给 0：
   `platform_fee` 缺失时是「这个渠道没有抽佣」(真值 0) 还是
   「这个品牌不提供这项数据」(未知)？两者的下游行为完全不同 ——
   前者可以入账，后者必须让指标层报「缺列」。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Dict, Optional, Tuple


class FieldMappingError(ValueError):
    """报文里缺了**必填**字段。⛔ 不静默补默认值 —— 那会把「没接入」写成「是 0」。"""


@dataclass(frozen=True)
class FieldSpec:
    """一个标准字段怎么从这个品牌的报文里取出来。

    `source` 是品牌报文里的键名。`required=True` 时缺了就报错（宁可整页失败
    也不写半条记录）；`required=False` 时用 `default`，而 `default` 的语义
    必须在 `default_means` 里写清楚 —— 「0 表示真的没有」还是「0 表示不知道」。
    """
    target: str
    source: str
    required: bool = True
    default: Any = None
    #: ⛔ 非必填字段必须写清默认值的语义。空着会让下游把「未知」当成「0」。
    default_means: str = ""
    #: 取出来之后的转换（如金额转整数分）。None = 原样。
    coerce: Optional[str] = None


@dataclass(frozen=True)
class SourceFieldMap:
    """一个品牌 / 一种来源的完整字段映射。"""
    source_key: str
    label: str
    order_fields: Tuple[FieldSpec, ...]
    item_fields: Tuple[FieldSpec, ...] = ()
    payment_fields: Tuple[FieldSpec, ...] = ()
    discount_fields: Tuple[FieldSpec, ...] = ()


#: 客如云 —— 逐字对齐 `keruyun.KeruyunAdapter._to_order` 现有行为。
#: ⛔ 改这里等于改 prod 的取数口径，改之前先看 `test_field_registry_matches_keruyun`：
#:    那条测试拿同一份报文比对两条路径，逐字段相等才算数。
KERUYUN = SourceFieldMap(
    source_key="keruyun",
    label="客如云",
    order_fields=(
        FieldSpec("platform_order_no", "orderNo"),
        FieldSpec("store_code", "shopCode"),
        FieldSpec("channel", "channel"),
        FieldSpec("placed_at", "placedAt", coerce="datetime"),
        FieldSpec("biz_date", "bizDate", coerce="date"),
        FieldSpec("gross_cents", "grossAmount", coerce="int"),
        FieldSpec("discount_cents", "discountAmount", coerce="int"),
        FieldSpec("net_cents", "netAmount", coerce="int"),
        # 抽佣: 老报文没有这个字段时按 0 —— 那是「这个渠道没有抽佣」的**真值**
        # (堂食恒为 0)，不是「不知道」。这一条今天刚被逐段验证过。
        FieldSpec("platform_fee_cents", "platformFee", required=False, default=0,
                  default_means="缺失 = 该渠道没有平台抽佣(真值 0)，不是未知",
                  coerce="int"),
        FieldSpec("guest_count", "guestCount", required=False, default=1,
                  default_means="缺失 = 按 1 人计(平台不给人数时的既有口径)",
                  coerce="int"),
    ),
    item_fields=(
        FieldSpec("dish_name", "dishName"),
        FieldSpec("qty", "qty", coerce="int"),
        FieldSpec("price_cents", "price", coerce="int"),
        FieldSpec("amount_cents", "amount", coerce="int"),
        FieldSpec("category", "dishCategory", required=False, default=None,
                  default_means="缺失 = 平台不给分类，菜品维度分组粒度变粗，不影响金额"),
    ),
    payment_fields=(
        FieldSpec("method", "method"),
        FieldSpec("amount_cents", "amount", coerce="int"),
    ),
    discount_fields=(
        FieldSpec("name", "name"),
        FieldSpec("discount_type", "type", required=False, default="",
                  default_means="缺失 = 平台不给活动类型，不影响金额归属"),
        FieldSpec("amount_cents", "amount", coerce="int"),
        FieldSpec("face_value_cents", "faceValue", required=False, default=0,
                  default_means="缺失/0 = 该活动没有票面价(即时立减)，不是未知",
                  coerce="int"),
        FieldSpec("actual_price_cents", "actualPrice", required=False, default=0,
                  default_means="缺失/0 = 该活动没有实售价，不是未知", coerce="int"),
    ),
)


REGISTRY: Dict[str, SourceFieldMap] = {KERUYUN.source_key: KERUYUN}


def extract(raw: Dict[str, Any], specs: Tuple[FieldSpec, ...],
            coercers: Dict[str, Callable[[Any, str], Any]]) -> Dict[str, Any]:
    """按登记表把一条原始报文抽成标准字段。

    ⛔ 必填字段缺失直接抛 —— 宁可整页失败也不写半条记录。半条记录会以
       「数字看着正常但少了一块」的形式活下去，比整页失败难发现得多。
    """
    out: Dict[str, Any] = {}
    for spec in specs:
        if spec.source in raw:
            value = raw[spec.source]
        elif spec.required:
            raise FieldMappingError(
                f"报文缺必填字段 {spec.source!r}（要落到 {spec.target}）")
        else:
            value = spec.default
        if value is not None and spec.coerce:
            fn = coercers.get(spec.coerce)
            if fn is None:
                raise FieldMappingError(f"未登记的转换 {spec.coerce!r}")
            value = fn(value, spec.source)
        out[spec.target] = value
    return out


def assert_registry_self_consistent() -> None:
    """登记表自洽 —— 非必填字段必须写清默认值的语义。

    ⛔ 这条不是形式主义: 「platform_fee 缺失按 0」到底是「真的没有抽佣」
       还是「这个品牌不给这项数据」, 决定了指标层该入账还是该报「缺列」。
       不写清楚, 下一个人只能靠猜, 而猜错的方向是把「未知」当成「0」。
    """
    for fm in REGISTRY.values():
        groups = (fm.order_fields, fm.item_fields,
                  fm.payment_fields, fm.discount_fields)
        for specs in groups:
            targets = [s.target for s in specs]
            assert len(targets) == len(set(targets)), (
                f"{fm.source_key} 有重复的目标字段: {targets}")
            for s in specs:
                if not s.required:
                    assert s.default_means, (
                        f"{fm.source_key}.{s.target} 是非必填却没写清默认值的语义 —— "
                        f"下游无法区分「真的是这个值」和「不知道」")


assert_registry_self_consistent()
