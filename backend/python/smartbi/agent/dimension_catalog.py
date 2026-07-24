"""Restaurant comprehensive-analysis dimension catalog.

This module is deliberately deterministic.  It tells the synthesis layer what
data a dimension requires and what additional decision it can support; it does
not infer facts and it never substitutes demo values for a real tenant.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Iterable


EVIDENCE_REAL = "REAL"
EVIDENCE_PROXY = "PROXY"
EVIDENCE_SIMULATED = "SIMULATED"
EVIDENCE_MISSING = "MISSING"


@dataclass(frozen=True)
class DimensionDefinition:
    code: str
    label: str
    category: str
    required_data: str
    enables: str


DIMENSIONS: tuple[DimensionDefinition, ...] = (
    DimensionDefinition(
        "revenue", "营收与订单", "内部经营",
        "POS营业额、实收额、订单数和营业日期",
        "判断整体生意规模、趋势和客单价变化",
    ),
    DimensionDefinition(
        "period_comparison", "同比环比", "内部经营",
        "至少两个可比较周期的POS和成本数据",
        "区分短期波动、季节性变化和持续下滑",
    ),
    DimensionDefinition(
        "store_comparison", "多门店比较", "内部经营",
        "门店标识以及各店同期POS数据",
        "识别领先店、落后店及客流/客单价差异",
    ),
    DimensionDefinition(
        "guest_traffic", "真实就餐人数", "内部经营",
        "POS中的就餐人数/客流量字段",
        "区分订单数、就餐人数、桌均人数和人均消费",
    ),
    DimensionDefinition(
        "physical_traffic", "商场及门前物理客流", "外部环境",
        "商场客流、楼层客流、门前经过人数和进店人数",
        "计算进店捕获率并判断是没人经过还是经过但没进店",
    ),
    DimensionDefinition(
        "dish_sales", "菜品销售结构", "内部经营",
        "菜品销量、销售额、订单关联和菜品类别",
        "识别主力菜、潜力菜、拖累菜及套餐机会",
    ),
    DimensionDefinition(
        "dish_margin", "菜品毛利", "内部经营",
        "完整BOM、实际采购价、标准份量、售价和销量",
        "判断高销量低毛利、低销量高毛利及菜品组合优化",
    ),
    DimensionDefinition(
        "channel", "堂食/外卖/自提", "内部经营",
        "订单类型及对应实收额、订单数",
        "判断渠道结构和履约压力",
    ),
    DimensionDefinition(
        "meal_period", "午晚市与时段", "内部经营",
        "开单时间、餐段、实收额和订单数",
        "判断高峰、低谷、排班与备货时段",
    ),
    DimensionDefinition(
        "promotion", "优惠与营销活动", "内部经营",
        "优惠类型、优惠金额、核销、曝光、活动成本和对照基线",
        "描述优惠结构；数据充分时再评估活动增量和ROI",
    ),
    DimensionDefinition(
        "review", "评价与口碑", "客户反馈",
        "聚合评分、评价标签、平台、门店和菜品关联",
        "判断体验问题是否集中在菜品、服务、门店或时段",
    ),
    DimensionDefinition(
        "supplier_cost", "供应商与采购价格", "供应链",
        "物料、供应商、历史采购价和到货日期",
        "识别成本上涨、连续异常和需要询价复核的物料",
    ),
    DimensionDefinition(
        "inventory", "库存风险", "供应链",
        "库存快照、安全库存和补货点",
        "识别缺货、积压和活动前备货风险",
    ),
    DimensionDefinition(
        "waste", "损耗与报损", "内部运营",
        "报损日期、食材、数量、单位、类型和金额",
        "识别高损耗食材与保存、加工或登记问题",
    ),
    DimensionDefinition(
        "stocktaking", "盘点差异", "内部运营",
        "盘点日期、账面数、实盘数、单位和物料",
        "识别持续盘亏及账实不一致",
    ),
    DimensionDefinition(
        "staffing", "排班与人效", "内部运营",
        "各时段订单、在岗人数和目标人效",
        "判断高峰缺人、低谷冗余和跨时段调配",
    ),
    DimensionDefinition(
        "weather", "天气", "外部环境",
        "门店位置、逐日天气和同期经营数据",
        "判断雨雪、高温等与堂食/外卖波动的相关性",
    ),
    DimensionDefinition(
        "holiday", "节假日与调休", "外部环境",
        "官方节假日/调休日历和同期经营数据",
        "避免把假期波动误判为门店经营能力变化",
    ),
    DimensionDefinition(
        "mall_activity", "商场活动", "外部环境",
        "商场活动日期、类型、场地及同期客流/经营数据",
        "判断会员日、市集、快闪等是否解释异常客流",
    ),
    DimensionDefinition(
        "nearby_event", "周边演出与赛事", "外部环境",
        "场馆活动、距离、日期、预计人数和同期经营数据",
        "判断演出散场、比赛和展览带来的临时客流",
    ),
    DimensionDefinition(
        "competitor", "竞品与商圈", "外部环境",
        "周边竞品数量、价格带、评分、距离和变化时间",
        "判断客流分流、价格竞争和品类拥挤度",
    ),
)

_BY_CODE = {item.code: item for item in DIMENSIONS}


def dimension_definition(code: str) -> DimensionDefinition:
    try:
        return _BY_CODE[code]
    except KeyError as exc:
        raise ValueError(f"unknown restaurant analysis dimension: {code}") from exc


def dimension_status(
    code: str,
    *,
    status: str,
    evidence_level: str,
    source: str | None = None,
    reason: str | None = None,
    coverage: dict[str, Any] | None = None,
) -> dict[str, Any]:
    item = asdict(dimension_definition(code))
    item.update({
        "status": status,
        "evidence_level": evidence_level,
        "source": source,
        "reason": reason,
        "coverage": coverage or {},
    })
    return item


def missing_status(
    code: str,
    *,
    reason: str,
    source: str | None = None,
) -> dict[str, Any]:
    return dimension_status(
        code,
        status="missing",
        evidence_level=EVIDENCE_MISSING,
        source=source,
        reason=reason,
    )


def ordered_statuses(items: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    order = {item.code: index for index, item in enumerate(DIMENSIONS)}
    return sorted(items, key=lambda item: order.get(str(item.get("code")), len(order)))
