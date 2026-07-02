from __future__ import annotations

"""FastAPI router exposing each restaurant analysis section as an independent endpoint.

Each section handler from ``smartbi.services.restaurant.sections`` is exposed
at ``POST /api/smartbi/restaurant/sections/{section_name}`` so frontends can
fetch one section at a time (cheap, cacheable, parallelizable).

Use ``GET /api/smartbi/restaurant/sections/list`` to discover available
section names. The list includes ``cost_rigidity`` (which the batch
``analyze()`` does NOT emit as a standalone section because it lives inside
``diagnostics``); the standalone endpoint is useful for direct UI/AI tool
calls.
"""

import logging
import re
import tempfile
import time
import uuid
from pathlib import Path as FsPath
from typing import Any, Dict, Optional

import pandas as pd
from fastapi import APIRouter, HTTPException, Path, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.cache import SectionCache
from smartbi.services.restaurant.sections.benchmark_alerts import BenchmarkAlertsHandler
from smartbi.services.restaurant.sections.bom_layer_status import BomLayerStatusHandler
from smartbi.services.restaurant.sections.calibration_history import (
    CalibrationHistoryHandler,
)
from smartbi.services.restaurant.sections.channel_margin import ChannelMarginHandler
from smartbi.services.restaurant.sections.cost_rigidity import CostRigidityHandler
from smartbi.services.restaurant.sections.diagnostics import DiagnosticsHandler
from smartbi.services.restaurant.sections.expense_breakdown import ExpenseBreakdownHandler
from smartbi.services.restaurant.sections.dining_heatmap import DiningHeatmapHandler
from smartbi.services.restaurant.sections.long_tail_sku import LongTailSkuHandler
from smartbi.services.restaurant.sections.member_rfm import MemberRfmHandler
from smartbi.services.restaurant.sections.menu_normalization import (
    MenuNormalizationHandler,
)
from smartbi.services.restaurant.sections.multi_store_comparison import (
    MultiStoreComparisonHandler,
)
from smartbi.services.restaurant.sections.review_analysis import ReviewAnalysisHandler
from smartbi.services.restaurant.sections.store_pnl_one_pager import (
    StorePnlOnePagerHandler,
)
from smartbi.services.restaurant.sections.department_pnl import DepartmentPnlHandler
from smartbi.services.restaurant.sections.cross_chain_benchmark import (
    CrossChainBenchmarkHandler,
)
from smartbi.services.restaurant.sections.forecast import RestaurantForecastHandler
from smartbi.services.restaurant.sections.menu_engineering import MenuEngineeringHandler
from smartbi.services.restaurant.sections.monthly_ppt_export import MonthlyPptExportHandler
from smartbi.services.restaurant.sections.shrinkage_analysis import (
    ShrinkageAnalysisHandler,
)
from smartbi.services.restaurant.sections.stored_value import StoredValueHandler
from smartbi.services.restaurant.sections.temporal_comparison import (
    TemporalComparisonHandler,
)
from smartbi.services.restaurant.sections.bom_variance import BomVarianceHandler
from smartbi.services.restaurant.sections.labor_productivity import LaborProductivityHandler
from smartbi.services.restaurant.sections.sales_plan_tracking import SalesPlanTrackingHandler
from smartbi.services.restaurant.sections.seat_occupancy import SeatOccupancyHandler
from smartbi.services.restaurant.sections.combo_split import ComboSplitHandler
from smartbi.services.restaurant.sections.return_anomaly import ReturnAnomalyHandler
from smartbi.services.restaurant.sections.review_competitive import ReviewCompetitiveHandler
from smartbi.services.restaurant.sections.smart_reorder import SmartReorderHandler
from smartbi.services.restaurant.sections.daily_reconciliation import DailyReconciliationHandler
from smartbi.services.restaurant.sections.procurement_forecast import ProcurementForecastHandler
from smartbi.services.restaurant.sections.shift_analysis import ShiftAnalysisHandler
from smartbi.services.restaurant.sections.piecework_calc import PieceworkCalcHandler
from smartbi.services.restaurant.sections.performance_eval import PerformanceEvalHandler
from smartbi.services.restaurant.sections.store_kpi_dashboard import StoreKpiDashboardHandler
from smartbi.services.restaurant.sections.value_summary import ValueSummaryHandler
from smartbi.services.restaurant.sections.advanced_traffic_persona import (
    AdvancedTrafficPersonaHandler,
)
from smartbi.services.restaurant.sections.boss_decision_brief import (
    BossDecisionBriefHandler,
)
from smartbi.services.restaurant.demo_owner_action_scenarios import (
    get_owner_action_demo_scenario,
    list_owner_action_demo_scenarios,
)

logger = logging.getLogger(__name__)
_cache = SectionCache(ttl_seconds=300)

router = APIRouter(
    prefix="/api/smartbi/restaurant/sections",
    tags=["Restaurant Sections"],
)


# Singleton handler instances. Each handler caches its own per-(factory_id,
# sub_sector) analyzer / engine state — instantiating once at module load
# preserves those caches across HTTP requests.
# BUG #1 FIX (Apr 15 2026): per-section data_kind hint for autoResolve.
# When multiple uploads of different dataTypes co-exist (e.g. QHJ uploads pos+finance+reviews+sales),
# autoResolve must pick the right kind, not just MAX(created_at). Default "pos" matches the
# pre-existing handler-implicit assumption (most handlers expect POS data).
SECTION_DATA_KIND = {
    "cost_rigidity": "finance",
    "expense_breakdown": "finance",
    "department_pnl": "finance",
    "store_pnl_one_pager": "finance",
    "review_analysis": "reviews",
    "review_competitive": "reviews",
    "sales_plan_tracking": "sales_summary",
    "advanced_traffic_persona": "none",
    "boss_decision_brief": "none",
    # Everything else defaults to "pos" (set in compute_section)
}


HANDLERS = {
    "cost_rigidity": CostRigidityHandler(),
    "diagnostics": DiagnosticsHandler(),
    "expense_breakdown": ExpenseBreakdownHandler(),
    "benchmark_alerts": BenchmarkAlertsHandler(),
    "channel_margin": ChannelMarginHandler(),
    "dining_heatmap": DiningHeatmapHandler(),
    "long_tail_sku": LongTailSkuHandler(),
    "menu_normalization": MenuNormalizationHandler(),
    "temporal_comparison": TemporalComparisonHandler(),
    "review_analysis": ReviewAnalysisHandler(),
    "member_rfm": MemberRfmHandler(),
    "stored_value": StoredValueHandler(),
    "multi_store_comparison": MultiStoreComparisonHandler(),
    "calibration_history": CalibrationHistoryHandler(),
    "store_pnl_one_pager": StorePnlOnePagerHandler(),
    "bom_layer_status": BomLayerStatusHandler(),
    "shrinkage_analysis": ShrinkageAnalysisHandler(),
    "department_pnl": DepartmentPnlHandler(),
    "menu_engineering": MenuEngineeringHandler(),
    "monthly_ppt_export": MonthlyPptExportHandler(),
    "cross_chain_benchmark": CrossChainBenchmarkHandler(),
    "restaurant_forecast": RestaurantForecastHandler(),
    "bom_variance": BomVarianceHandler(),
    "labor_productivity": LaborProductivityHandler(),
    "sales_plan_tracking": SalesPlanTrackingHandler(),
    "seat_occupancy": SeatOccupancyHandler(),
    "combo_split": ComboSplitHandler(),
    "return_anomaly": ReturnAnomalyHandler(),
    "review_competitive": ReviewCompetitiveHandler(),
    "smart_reorder": SmartReorderHandler(),
    "daily_reconciliation": DailyReconciliationHandler(),
    "procurement_forecast": ProcurementForecastHandler(),
    "shift_analysis": ShiftAnalysisHandler(),
    "piecework_calc": PieceworkCalcHandler(),
    "performance_eval": PerformanceEvalHandler(),
    "store_kpi_dashboard": StoreKpiDashboardHandler(),
    "value_summary": ValueSummaryHandler(),
    "advanced_traffic_persona": AdvancedTrafficPersonaHandler(),
    "boss_decision_brief": BossDecisionBriefHandler(),
}


class SectionRequestBody(BaseModel):
    """Request body for ``POST /api/smartbi/restaurant/sections/{section_name}``.

    ``params`` carries the section-specific inputs (POS DataFrame, financial
    data, etc). Each section handler documents what keys it consumes; this
    layer just forwards them.
    """
    factory_id: str = Field(..., description="Factory identifier")
    upload_id: Optional[str] = Field(None, description="Upload identifier")
    sub_sector: str = Field("火锅", description="Restaurant sub-sector (火锅 / 川菜 / ...)")
    store_id: Optional[str] = Field(None, description="Store identifier")
    store_name: Optional[str] = Field(None, description="Store display name")
    period: str = Field("current", description="Period label")
    params: Dict[str, Any] = Field(default_factory=dict, description="Section-specific inputs")


class OwnerActionChatRequest(BaseModel):
    """Boss-facing restaurant demo chat request.

    Accepts both snake_case and frontend camelCase names so it can be called
    directly from Python tests, web-admin, or the RN API client.
    """

    factory_id: Optional[str] = Field(None, description="Factory/store identifier")
    factoryId: Optional[str] = Field(None, description="Frontend alias for factory_id")
    message: str = Field(..., description="Owner question")
    session_id: Optional[str] = Field(None, description="Conversation session id")
    sessionId: Optional[str] = Field(None, description="Frontend alias for session_id")
    demo_scenario: Optional[str] = Field(None, description="Forced demo scenario")
    demoScenario: Optional[str] = Field(None, description="Frontend alias for demo_scenario")
    store_name: Optional[str] = Field(None, description="Store display name")
    storeName: Optional[str] = Field(None, description="Frontend alias for store_name")
    sub_sector: Optional[str] = Field(None, description="Restaurant sub-sector")
    subSector: Optional[str] = Field(None, description="Frontend alias for sub_sector")
    period: Optional[str] = Field(None, description="Period label")


_OWNER_ACTION_CHAT_SESSIONS: dict[str, dict[str, Any]] = {}
_OWNER_ACTION_FACTORY_LAST_SCENARIOS: dict[str, str] = {}

_OWNER_ACTION_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("store_compare", ("所有门店", "其他门店", "区域经理", "品牌共性", "单店问题", "门店里", "哪家店", "最值得学习", "复制到")),
    ("seating_mix", ("二人桌", "两人桌", "四人桌", "两人客", "四人客", "桌型", "桌子", "翻台", "翻台率", "排队", "等位")),
    ("staffing_schedule", ("排班", "人手", "加人", "前厅", "后厨", "员工时间", "工时", "午市", "晚市", "忙不过来")),
    ("traffic_conversion", ("客流画像", "进店转化", "门口客流", "路过客流", "转化率", "曝光", "核销", "进店少", "客流情况")),
    ("staff_training", ("培训", "服务员", "服务态度", "服务差评", "话术", "催菜", "意识", "店长", "开班前", "评论", "顾客", "复购", "最在意")),
    ("kitchen_quality", ("厨房", "后厨", "出品", "口味", "太咸", "难吃", "上菜慢", "复杂菜", "出餐速度", "保证出餐", "稳定")),
    ("package", ("套餐", "组合", "小套餐", "工作日", "低峰", "客单", "提高客单价")),
    ("cost_margin", ("成本", "毛利", "BOM", "盘点", "损耗", "采购", "采购价格", "原料", "食材", "盈利")),
    ("external_event_response", ("商场活动", "天气", "节日", "客流", "外部", "活动")),
    ("single_item_push", ("主推", "单品", "招牌", "爆品", "引流菜")),
)


def _effective_str(*values: Optional[str], default: str = "") -> str:
    for value in values:
        if isinstance(value, str) and value.strip():
            return value.strip()
    return default


async def _log_owner_action_chat_async(
    *,
    query: str,
    factory_id: str,
    answer: str,
    scenario: str,
    session_id: str,
    owner_page: dict[str, Any],
    charts: list[dict[str, Any]],
    total_wall_ms: int,
    is_follow_up: bool,
) -> Optional[int]:
    try:
        from smartbi.config import get_pg_pool
        from smartbi.services.llm_fallback_logger import log_template_hit
        from smartbi.tenant_ctx import reset_factory_id, set_factory_id

        token = set_factory_id(factory_id)
        try:
            pool = await get_pg_pool()
            if pool is None:
                return None
            focus = owner_page.get("decisionFocus") if isinstance(owner_page.get("decisionFocus"), dict) else {}
            return await log_template_hit(
                pool,
                query=query,
                factory_id=factory_id,
                upload_id=None,
                template_code=f"restaurant_owner_action:{scenario}",
                answer=answer,
                total_wall_ms=total_wall_ms,
                agg_meta={
                    "source": "restaurant_owner_action",
                    "scenario": scenario,
                    "session_id": session_id,
                    "is_follow_up": is_follow_up,
                    "decision_action_type": focus.get("primaryActionType"),
                    "chart_titles": [str(chart.get("title") or "") for chart in charts],
                    "chart_count": len(charts),
                    "learning_policy": "capture_for_feedback_and_review_only",
                },
            )
        finally:
            reset_factory_id(token)
    except Exception as exc:
        logger.warning("[owner-action-log] failed: %s", exc)
        return None


def _is_follow_up(message: str) -> bool:
    text = (message or "").strip().lower()
    return any(
        keyword in text
        for keyword in (
            "\u7ee7\u7eed",
            "\u5177\u4f53",
            "\u8be6\u7ec6",
            "\u4e3a\u4ec0\u4e48",
            "\u600e\u4e48\u505a",
            "\u54ea\u4e9b\u6570",
            "\u54ea\u4e09\u4e2a\u6570",
            "\u4e09\u4e2a\u6570",
            "\u5148\u770b\u54ea",
            "\u770b\u4ec0\u4e48",
            "\u9a8c\u8bc1",
            "\u843d\u5730",
            "\u7136\u540e\u5462",
        )
    )
def _pick_owner_action_scenario(message: str, requested: str, previous: str) -> str:
    scenarios = set(list_owner_action_demo_scenarios())
    if requested in scenarios:
        return requested
    for scenario, keywords in _OWNER_ACTION_KEYWORDS:
        if any(keyword in message for keyword in keywords):
            return scenario
    if previous and _is_follow_up(message):
        return previous
    return previous or "package"


def _plain_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("text", "action", "decision", "title", "label", "name", "reason"):
            text = value.get(key)
            if isinstance(text, str) and text.strip():
                return text.strip()
    return str(value)


def _first_text(items: Any) -> str:
    if isinstance(items, list) and items:
        return _plain_text(items[0])
    return _plain_text(items)


def _owner_metric(value: Any, digits: int = 0) -> str:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return ""
    if digits <= 0:
        return str(int(round(number)))
    return str(round(number, digits))


def _owner_cn_number(value: Any, digits: int = 1) -> str:
    number = _owner_float(value)
    if number is None:
        return ""
    rounded = round(number / 10000, digits)
    rounded_text = str(int(rounded)) if rounded == int(rounded) else str(rounded)
    if abs(number) >= 10000:
        return f"{rounded_text}万"
    if number == int(number):
        return str(int(number))
    return str(round(number, digits))


def _owner_money(value: Any) -> str:
    number = _owner_float(value)
    if number is None:
        return ""
    if abs(number) >= 10000:
        text = _owner_cn_number(number)
        return f"{text}元"
    return f"{int(round(number))}元"


def _owner_remove_score_words(text: str) -> str:
    if not text:
        return ""
    text = re.sub(r"，?综合分\s*\d+(?:\.\d+)?，?", "，", text)
    text = re.sub(r"综合分\s*\d+(?:\.\d+)?[，。]?", "", text)
    return text.replace("，，", "，").replace("，。", "。").strip()


def _owner_rate_people(rate: Any) -> str:
    try:
        value = float(rate)
    except (TypeError, ValueError):
        return ""
    return str(round(value * 100))


def _owner_plain_reason(owner_page: dict[str, Any], scenario: str, fallback: str) -> str:
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    financial = params.get("financial_summary") if isinstance(params.get("financial_summary"), dict) else {}
    stocktake = params.get("monthly_stocktake") if isinstance(params.get("monthly_stocktake"), dict) else {}

    if scenario == "traffic_conversion":
        traffic = owner_page.get("trafficPersona") if isinstance(owner_page.get("trafficPersona"), dict) else {}
        platform = owner_page.get("platformChannelSnapshot") if isinstance(owner_page.get("platformChannelSnapshot"), dict) else {}

        passersby = _owner_metric(traffic.get("storefrontPassersby"))
        visits = _owner_metric(traffic.get("estimatedStoreVisits"))
        capture = _owner_rate_people(traffic.get("captureRate"))
        peer_capture = _owner_rate_people(traffic.get("peerCaptureRate"))
        mall_lift = _owner_metric(traffic.get("mallVsLastWeekPct"), 1)
        raw_order_gap = traffic.get("storeOrdersVsTrafficPct")
        order_gap = ""
        try:
            order_gap = _owner_metric(abs(float(raw_order_gap)), 1)
        except (TypeError, ValueError):
            order_gap = ""
        segment = _plain_text(traffic.get("topSegment"))
        need = _plain_text(traffic.get("topNeed"))
        weak_platforms = platform.get("weakConversionPlatforms") if isinstance(platform, dict) else []
        weak_text = "、".join(str(item) for item in weak_platforms[:2]) if isinstance(weak_platforms, list) else ""

        parts = ["今天不是商场没人逛，而是很多人从门口经过后没有进来。"]
        if passersby and capture:
            parts.append(f"门口大约有 {passersby} 人经过，100 个路过的人里大概只有 {capture} 个进店。")
        if peer_capture:
            parts.append(f"同一楼层类似店大概能做到 100 个里进 {peer_capture} 个，所以我们差的不是客流，是入口吸引力。")
        if mall_lift:
            parts.append(f"商场人流比上周多约 {mall_lift}%，但本店订单没有跟着涨。")
        elif visits:
            parts.append(f"估算真正进店的人大约 {visits} 人，门口还有不少可以争取的人。")
        if order_gap and order_gap != "0.0":
            parts.append(f"简单说，商场热起来了，但店里少吃到大约 {order_gap}% 的这波客流红利。")
        if segment:
            parts.append(f"路过的人主要是{segment}，他们最在意的是{need or '价格清楚、吃得快、招牌明确'}。")
        if weak_text:
            parts.append(f"{weak_text} 有人看但下单弱，说明页面、套餐说明或到店核销没有讲清楚。")
        return "".join(parts)

    if scenario == "store_compare":
        chain = pos.get("chainRank") if isinstance(pos.get("chainRank"), dict) else {}
        compare = pos.get("storeComparison") if isinstance(pos.get("storeComparison"), dict) else {}
        store_count = chain.get("storeCount") or 8
        revenue_rank = chain.get("revenueRank") or 6
        daily_rank = chain.get("dailyRank") or 2
        aov_rank = chain.get("aovRank") or 5
        copy_from = compare.get("copyFrom") or "日均表现更好的同城门店"
        weak = "、".join(compare.get("weakerThanPeers") or ["工作日午市收入", "双人套餐承接", "客单价"])
        strong = "、".join(compare.get("strongerThanPeers") or ["日均单量", "大众点评评分", "商场自然客流"])
        return (
            f"这家店不是完全差店：连锁 {store_count} 家里，总收入大概第 {revenue_rank}，但日均能做到第 {daily_rank}，说明客流和基本盘不弱。"
            f"真正拖后腿的是{weak}；强项是{strong}。"
            f"所以区域经理今天不要只盯销售额排名，要把它和 {copy_from} 对比，看能复制哪一个动作。"
        )

    if scenario == "cost_margin":
        food_cost = _owner_pct(financial.get("foodCostRatio"))
        gross = financial.get("grossMarginPct")
        losses = "、".join(str(item) for item in (stocktake.get("topLossItems") or [])[:2])
        variance = "、".join(str(item) for item in (stocktake.get("varianceItems") or [])[:2])
        parts = []
        if food_cost is not None:
            parts.append(f"食材成本率大约 {food_cost}%，毛利率约 {gross or 51}%，比健康状态更紧。")
        if losses:
            parts.append(f"盘点先指向 {losses} 这些损耗点。")
        if variance:
            parts.append(f"BOM 和实际用量差异主要在 {variance}。")
        parts.append("所以今天先查采购价、BOM 用量和后厨损耗，不要只让前厅多卖。")
        return "".join(parts)

    return fallback


def _owner_plain_actions(owner_page: dict[str, Any], scenario: str, first_action: str) -> list[str]:
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}

    if scenario == "traffic_conversion":
        return [
            "门口海报和等位牌只讲一句话：招牌鱼是什么、两个人大概多少钱、多久能吃完。",
            "美团/大众点评和抖音团购页面同步改成同一套话术，主图先放招牌鱼和双人价格，不要堆一堆菜名。",
            "收银和门迎今天专门盯核销客：进店先确认券、引导点招牌，别让线上来的客人现场又犹豫。",
        ]
    if scenario == "seating_mix":
        return [
            "午市和晚高峰先把 2 张四人桌改成可拼可拆：两人客来了不要占死四人桌，四人客来了再拼回去。",
            "前厅今天按“先看人数、再引导桌型、再推荐双人/四人套餐”走，避免排队多但客单价没起来。",
            "先别直接加排班或催厨房；如果桌型错了，加员工和提出餐速度也只是把堵点往后移。",
        ]
    if scenario == "staffing_schedule":
        return [
            "今天把人手压到晚市 18:00-20:00：前厅多 1 人盯等位和核销，后厨多 1 人盯招牌鱼出餐。",
            "午市只保留稳定班，不要平均撒人；忙的时段没人，闲的时段人多，是最浪费的排班。",
            "店长开班前把分工讲清：谁迎宾、谁催菜、谁处理差评苗头，别等顾客催了才临时补位。",
        ]
    if scenario == "staff_training":
        return [
            "开班前只训练三句话：招牌鱼怎么介绍、双人套餐多少钱、等位/上菜慢怎么安抚。",
            "服务员今天不要背长话术，只要每桌主动说一次“招牌怎么点更划算”，把犹豫客变成下单客。",
            "店长晚市后复盘差评关键词：服务态度、没人催菜、核销不清楚，哪个最多明天就继续训哪个。",
        ]
    if scenario == "kitchen_quality":
        return [
            "今天先把被差评点名最多的招牌鱼做出餐抽查：咸淡、鱼片熟度、上菜时长三项必须记录。",
            "晚高峰前减少复杂低销量菜的备料和推荐，厨房先保招牌鱼、冰豆花、双人套餐这几条主线。",
            "前厅一旦发现上菜慢超过阈值，立刻提醒后厨和店长，不要等顾客写差评才处理。",
        ]
    if scenario == "cost_margin":
        return [
            "今天先查三张表：活鱼采购价、招牌鱼 BOM 理论用量、昨日盘点损耗；先找毛利漏点。",
            "如果活鱼实际用量比 BOM 高，先称重抽查和切配标准，不要只靠涨价补利润。",
            "高毛利小食可以带低销量菜，但必须算套餐售价、食材成本和毛利，毛利守不住就不推。",
        ]
    if scenario == "store_compare":
        compare = pos.get("storeComparison") if isinstance(pos.get("storeComparison"), dict) else {}
        copy_from = compare.get("copyFrom") or "日均表现更好的同城门店"
        copy_action = compare.get("copyAction") or "复制它的双人套餐首屏和服务员推荐话术"
        return [
            f"区域经理今天先盯青花椒这家店的工作日午市和客单价，不要只看总收入第几名。",
            f"直接复制 {copy_from} 的一个动作：{copy_action}。",
            "明天复盘只看这家店和对标店的三项差距：工作日午市收入、双人套餐占比、客单价。",
        ]
    if scenario == "package":
        package_decision = owner_page.get("packageDecision") if isinstance(owner_page.get("packageDecision"), dict) else {}
        candidates = package_decision.get("candidates") if isinstance(package_decision.get("candidates"), list) else []
        candidate = candidates[0] if candidates and isinstance(candidates[0], dict) else {}
        if candidate:
            name = _plain_text(candidate.get("name")) or "招牌鱼双人套餐"
            price = _owner_money(candidate.get("estimatedPackagePrice"))
            cost = _owner_money(candidate.get("estimatedFoodCost"))
            profit = _owner_money(candidate.get("estimatedGrossProfit"))
            margin = _owner_metric(candidate.get("grossMarginPct"), 1)
            margin_text = f"，毛利率约 {margin}%" if margin else ""
            return [
                f"今天先把“{name}”做成双人小套餐：售价约 {price or '270元'}，食材成本约 {cost or '100元'}，一份大概能留下 {profit or '170元'} 毛利{margin_text}。",
                "只在工作日午市/低峰和美团、大众点评入口测 7 天，别一上来全渠道铺开。",
                "门口物料和团购页只写三句话：招牌是什么、两个人多少钱、适合多久吃完。服务员也按这三句话推荐。",
            ]
    if first_action:
        return [_owner_remove_score_words(first_action)]
    return ["今天先选一个最明显的问题改，不要同时改太多项。"]


def _owner_do_not_do(owner_page: dict[str, Any], scenario: str) -> str:
    if scenario == "traffic_conversion":
        return "今天先别继续加投流，也别全店打折。人已经在门口了，先把门口和线上入口讲清楚。"
    if scenario == "store_compare":
        return "今天先别把这家店简单判成差店。它日均不弱，问题是工作日和客单价没吃满，要和同类门店拆开比。"
    if scenario == "cost_margin":
        return "今天先别直接涨价，也别砍掉主菜。先查采购价、BOM 和损耗，否则可能把顾客喜欢的菜也误伤。"
    text = _first_text(owner_page.get("doNotDo"))
    return text or "先别凭感觉大改菜单、价格和排班，等今天这一个动作看出效果再扩大。"


def _owner_watch_numbers(owner_page: dict[str, Any], scenario: str) -> str:
    if scenario == "traffic_conversion":
        return "明天只看三个数：门口路过多少人、进店多少人、最后下单多少单。路过人差不多但进店和下单涨了，就说明入口改对了。"
    focus = owner_page.get("decisionFocus") if isinstance(owner_page.get("decisionFocus"), dict) else {}
    action_type = focus.get("primaryActionType")
    if action_type == "seating_mix":
        return "今天看三个数：二人客等位多久、翻台次数、空桌时间。等位少了、空桌少了，桌型就调对了。"
    if action_type == "staffing_schedule":
        return "今天看三个数：高峰等位、上菜时长、差评关键词。等位和上菜时间降下来，排班就有用。"
    if action_type == "package":
        return "今天看三个数：套餐卖了多少份、有没有拉高客单、毛利有没有守住。只卖得多但毛利掉了，就不是好套餐。"
    if scenario == "cost_margin":
        return "今天看三个数：活鱼采购价、BOM 实际偏差、盘点损耗金额。三项收窄，毛利才是真的补回来了。"
    if scenario == "store_compare":
        return "今天看三个数：工作日午市收入、双人套餐占比、客单价。它们追上对标店，说明复制动作有效。"
    return "今天看三个数：订单数、客单价、差评关键词。先看动作有没有让生意变好，再决定要不要扩大。"


def _owner_plain_evidence(owner_page: dict[str, Any], scenario: str) -> str:
    evidence = owner_page.get("keyEvidence") or []
    texts = [_plain_text(item) for item in evidence if _plain_text(item)]
    if not texts:
        return ""

    if scenario == "package":
        first = texts[0] if texts else ""
        second = texts[1] if len(texts) > 1 else ""
        order_match = re.search(r"订单:\s*([\d.]+)\s*单.*收入约\s*([\d.]+).*人数\s*([\d.]+)", first)
        day_match = re.search(r"工作日每天约\s*([\d.]+).*周末每天约\s*([\d.]+).*高\s*([\d.]+)%", second)
        parts: list[str] = []
        if order_match:
            orders, revenue, guests = order_match.groups()
            parts.append(
                f"本周大约 {int(float(orders))} 单，收入约 {_owner_money(revenue)}，到店/用餐约 {_owner_cn_number(guests)}人"
            )
        if day_match:
            weekday, weekend, lift = day_match.groups()
            parts.append(
                f"工作日每天约 {_owner_money(weekday)}，周末每天约 {_owner_money(weekend)}，周末高出约 {round(float(lift), 1)}%，所以套餐先补工作日低峰"
            )
        if parts:
            return "；".join(parts)

    cleaned = [_owner_remove_score_words(text) for text in texts[:2]]
    return "；".join(text for text in cleaned if text)


def _owner_float(value: Any, default: float | None = None) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _owner_pct(value: Any) -> float | None:
    number = _owner_float(value)
    if number is None:
        return None
    return round(number * 100, 1)


def _owner_dimension_rows(items: Any, label_keys: tuple[str, ...], value_key: str = "share") -> list[tuple[str, float]]:
    rows: list[tuple[str, float]] = []
    if not isinstance(items, list):
        return rows
    for item in items:
        if not isinstance(item, dict):
            continue
        label = ""
        for key in label_keys:
            raw = item.get(key)
            if isinstance(raw, str) and raw.strip():
                label = raw.strip()
                break
        value = _owner_pct(item.get(value_key))
        if label and value is not None:
            rows.append((label, value))
    return rows


def _owner_bar_chart(
    title: str,
    labels: list[str],
    series: list[dict[str, Any]],
    *,
    y_name: str = "",
    bottom: int = 48,
) -> dict[str, Any]:
    return {
        "type": "bar",
        "title": title,
        "option": {
            "title": {"text": title, "left": "center"},
            "tooltip": {"trigger": "axis"},
            "legend": {"top": 28, "data": [item.get("name") for item in series]},
            "grid": {"left": 45, "right": 20, "top": 78, "bottom": bottom, "containLabel": True},
            "xAxis": {"type": "category", "data": labels, "axisLabel": {"rotate": 18}},
            "yAxis": {"type": "value", "name": y_name},
            "series": series,
        },
    }


def _owner_evidence_charts(owner_page: dict[str, Any], scenario: str, params: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    charts: list[dict[str, Any]] = []
    params = params or {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    review = params.get("review_summary") if isinstance(params.get("review_summary"), dict) else {}
    menu = params.get("menu_summary") if isinstance(params.get("menu_summary"), dict) else {}
    financial = params.get("financial_summary") if isinstance(params.get("financial_summary"), dict) else {}
    stocktake = params.get("monthly_stocktake") if isinstance(params.get("monthly_stocktake"), dict) else {}
    external = params.get("external_signals") if isinstance(params.get("external_signals"), dict) else {}
    traffic = owner_page.get("trafficPersona") if isinstance(owner_page.get("trafficPersona"), dict) else {}
    platform = owner_page.get("platformChannelSnapshot") if isinstance(owner_page.get("platformChannelSnapshot"), dict) else {}

    if scenario in {"traffic_conversion", "external_event_response"} and traffic.get("available"):
        capture = traffic.get("captureRate")
        peer_capture = traffic.get("peerCaptureRate")
        capture_pct = _owner_pct(capture)
        peer_pct = _owner_pct(peer_capture)
        if capture_pct is not None:
            labels = ["本店进店率"]
            values = [capture_pct]
            if peer_pct is not None:
                labels.append("同层类似店参考")
                values.append(peer_pct)
            charts.append({
                "type": "bar",
                "title": "门口路过客有没有被拉进店",
                "option": {
                    "title": {"text": "门口路过客有没有被拉进店", "left": "center"},
                    "tooltip": {"trigger": "axis"},
                    "grid": {"left": 40, "right": 20, "top": 58, "bottom": 35, "containLabel": True},
                    "xAxis": {"type": "category", "data": labels},
                    "yAxis": {"type": "value", "name": "每100个路过的人进店几个", "axisLabel": {"formatter": "{value}%"}},
                    "series": [{
                        "name": "进店率",
                        "type": "bar",
                        "barWidth": "38%",
                        "data": values,
                        "itemStyle": {"color": {"type": "linear", "x": 0, "y": 0, "x2": 0, "y2": 1, "colorStops": [
                            {"offset": 0, "color": "#5CB87A"},
                            {"offset": 1, "color": "#2D8B57"},
                        ]}},
                        "label": {"show": True, "position": "top", "formatter": "{c}%"},
                    }],
                },
            })

    channels = platform.get("channels") if isinstance(platform, dict) else None
    if scenario in {"traffic_conversion", "external_event_response", "single_item_push"} and isinstance(channels, list) and channels:
        channel_rows: list[tuple[str, float]] = []
        for item in channels:
            if not isinstance(item, dict):
                continue
            name = str(item.get("platform") or "").strip()
            rate = item.get("conversionRate")
            if not name or rate is None:
                continue
            try:
                channel_rows.append((name, round(float(rate) * 100, 1)))
            except (TypeError, ValueError):
                continue
        if channel_rows:
            charts.append({
                "type": "bar",
                "title": "各平台入口谁看了但没下单",
                "option": {
                    "title": {"text": "各平台入口谁看了但没下单", "left": "center"},
                    "tooltip": {"trigger": "axis"},
                    "grid": {"left": 45, "right": 20, "top": 58, "bottom": 55, "containLabel": True},
                    "xAxis": {"type": "category", "data": [row[0] for row in channel_rows], "axisLabel": {"rotate": 18}},
                    "yAxis": {"type": "value", "name": "访问到下单", "axisLabel": {"formatter": "{value}%"}},
                    "series": [{
                        "name": "下单转化",
                        "type": "bar",
                        "barWidth": "42%",
                        "data": [row[1] for row in channel_rows],
                        "itemStyle": {"color": "#409EFF"},
                        "label": {"show": True, "position": "top", "formatter": "{c}%"},
                    }],
                },
            })

    if scenario == "seating_mix":
        guest_rows = _owner_dimension_rows(pos.get("customerSegments") or pos.get("topGuestSegments"), ("segment", "name"))
        table_rows = _owner_dimension_rows(pos.get("tableMix") or pos.get("table_mix") or pos.get("seatMix") or pos.get("seatingMix"), ("tableType", "name", "segment"))
        labels = list(dict.fromkeys([row[0] for row in guest_rows] + [row[0] for row in table_rows]))
        if labels:
            guest_map = dict(guest_rows)
            table_map = dict(table_rows)
            charts.append(_owner_bar_chart(
                "来的客人和桌型是不是匹配",
                labels,
                [
                    {"name": "客人占比", "type": "bar", "data": [guest_map.get(label, 0) for label in labels], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                    {"name": "桌型占比", "type": "bar", "data": [table_map.get(label, 0) for label in labels], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                ],
                y_name="占比",
            ))

    if scenario == "staffing_schedule":
        daypart_rows = _owner_dimension_rows(pos.get("daypartRevenue"), ("name", "daypart"))
        staffing_rows = _owner_dimension_rows(pos.get("staffingByDaypart") or pos.get("laborByDaypart") or pos.get("laborCoverage"), ("name", "daypart"))
        labels = list(dict.fromkeys([row[0] for row in daypart_rows] + [row[0] for row in staffing_rows]))
        if labels:
            daypart_map = dict(daypart_rows)
            staffing_map = dict(staffing_rows)
            charts.append(_owner_bar_chart(
                "忙的时段和人手是不是对得上",
                labels,
                [
                    {"name": "生意集中", "type": "bar", "data": [daypart_map.get(label, 0) for label in labels], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                    {"name": "人手安排", "type": "bar", "data": [staffing_map.get(label, 0) for label in labels], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                ],
                y_name="占比",
            ))

    if scenario in {"kitchen_quality", "staff_training", "staffing_schedule"}:
        theme_rows: list[tuple[str, float]] = []
        for item in review.get("negativeThemes") or []:
            if not isinstance(item, dict):
                continue
            label = str(item.get("theme") or item.get("name") or "").strip()
            count = _owner_float(item.get("count"), 0)
            if label and count is not None:
                theme_rows.append((label, round(count, 1)))
        dish_rows: list[tuple[str, float]] = []
        for item in review.get("negativeDishMentions") or review.get("complaintDishes") or []:
            if not isinstance(item, dict):
                continue
            label = str(item.get("name") or item.get("dish") or "").strip()
            count = _owner_float(item.get("count"), 0)
            if label and count is not None:
                dish_rows.append((label[:12], round(count, 1)))
        if theme_rows:
            charts.append(_owner_bar_chart(
                "差评先集中在哪个问题",
                [row[0] for row in theme_rows[:4]],
                [{"name": "差评提到次数", "type": "bar", "data": [row[1] for row in theme_rows[:4]], "label": {"show": True, "position": "top"}}],
                y_name="次数",
            ))
        if scenario == "kitchen_quality" and dish_rows:
            charts.append(_owner_bar_chart(
                "哪道菜最需要厨房抽查",
                [row[0] for row in dish_rows[:4]],
                [{"name": "被差评点名", "type": "bar", "data": [row[1] for row in dish_rows[:4]], "label": {"show": True, "position": "top"}}],
                y_name="次数",
            ))

    if scenario == "external_event_response":
        activities = external.get("activities") if isinstance(external.get("activities"), list) else []
        activity_rows: list[tuple[str, float]] = []
        for item in activities:
            if not isinstance(item, dict):
                continue
            title = str(item.get("title") or "商场活动").strip()
            lift = _owner_float(item.get("expectedTrafficLiftPct"))
            if title and lift is not None:
                activity_rows.append((title[:12], round(lift, 1)))
        if activity_rows:
            charts.append(_owner_bar_chart(
                "商场活动预计会把客流抬高多少",
                [row[0] for row in activity_rows[:4]],
                [{"name": "预计客流提升", "type": "bar", "data": [row[1] for row in activity_rows[:4]], "label": {"show": True, "position": "top", "formatter": "{c}%"}}],
                y_name="提升幅度",
            ))

    package_info = owner_page.get("packageRecommendations") if isinstance(owner_page.get("packageRecommendations"), dict) else {}
    candidates = package_info.get("candidates") if isinstance(package_info, dict) else []
    if scenario in {"package", "single_item_push"} and isinstance(candidates, list) and candidates:
        rows: list[tuple[str, float, float, float]] = []
        for item in candidates[:3]:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "套餐候选")
            price = item.get("estimatedPackagePrice")
            cost = item.get("estimatedFoodCost")
            margin = item.get("estimatedGrossProfit")
            try:
                rows.append((name[:12], round(float(price), 1), round(float(cost), 1), round(float(margin), 1)))
            except (TypeError, ValueError):
                continue
        if rows:
            charts.append({
                "type": "bar",
                "title": "套餐价格、成本、毛利能不能撑住",
                "option": {
                    "title": {"text": "套餐价格、成本、毛利能不能撑住", "left": "center"},
                    "tooltip": {"trigger": "axis"},
                    "legend": {"top": 28, "data": ["售价", "食材成本", "毛利"]},
                    "grid": {"left": 45, "right": 20, "top": 78, "bottom": 58, "containLabel": True},
                    "xAxis": {"type": "category", "data": [row[0] for row in rows], "axisLabel": {"rotate": 18}},
                    "yAxis": {"type": "value", "name": "元"},
                    "series": [
                        {"name": "售价", "type": "bar", "data": [row[1] for row in rows]},
                        {"name": "食材成本", "type": "bar", "data": [row[2] for row in rows]},
                        {"name": "毛利", "type": "bar", "data": [row[3] for row in rows]},
                    ],
                },
            })

    if scenario == "cost_margin":
        product_rows: list[tuple[str, float, float, float]] = []
        for item in menu.get("topProducts") or []:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "菜品").strip()
            revenue = _owner_float(item.get("revenue"))
            cost = _owner_float(item.get("foodCost"))
            if not name or revenue is None or cost is None:
                continue
            profit = max(revenue - cost, 0)
            product_rows.append((name[:12], round(revenue, 1), round(cost, 1), round(profit, 1)))
        if product_rows:
            charts.append(_owner_bar_chart(
                "菜品收入、食材成本、毛利差多少",
                [row[0] for row in product_rows[:4]],
                [
                    {"name": "收入", "type": "bar", "data": [row[1] for row in product_rows[:4]]},
                    {"name": "食材成本", "type": "bar", "data": [row[2] for row in product_rows[:4]]},
                    {"name": "毛利", "type": "bar", "data": [row[3] for row in product_rows[:4]]},
                ],
                y_name="元",
                bottom=60,
            ))
        food_cost_ratio = _owner_pct(financial.get("foodCostRatio"))
        gross_margin = _owner_float(financial.get("grossMarginPct"))
        peer_food_cost_ratio = _owner_pct(
            financial.get("peerFoodCostRatio")
            or financial.get("categoryFoodCostRatio")
            or financial.get("sameCategoryFoodCostRatio")
            or 0.42
        )
        peer_gross_margin = _owner_float(
            financial.get("peerGrossMarginPct")
            or financial.get("categoryGrossMarginPct")
            or financial.get("sameCategoryGrossMarginPct")
            or 58
        )
        if food_cost_ratio is not None and gross_margin is not None:
            charts.append(_owner_bar_chart(
                "整店成本和同类店差多少",
                ["食材成本率", "毛利率"],
                [
                    {"name": "本店", "type": "bar", "data": [food_cost_ratio, round(gross_margin, 1)], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                    {"name": "同类参考", "type": "bar", "data": [peer_food_cost_ratio, round(peer_gross_margin, 1)], "label": {"show": True, "position": "top", "formatter": "{c}%"}},
                ],
                y_name="占比",
            ))
        if stocktake.get("topLossItems") and len(charts) < 3:
            charts.append({
                "type": "bar",
                "title": "盘点先查哪些损耗",
                "option": {
                    "title": {"text": "盘点先查哪些损耗", "left": "center"},
                    "tooltip": {"trigger": "axis"},
                    "grid": {"left": 45, "right": 20, "top": 58, "bottom": 58, "containLabel": True},
                    "xAxis": {"type": "category", "data": [str(item)[:12] for item in stocktake.get("topLossItems", [])[:4]], "axisLabel": {"rotate": 18}},
                    "yAxis": {"type": "value", "name": "优先级"},
                    "series": [{"name": "先查顺序", "type": "bar", "data": list(range(len(stocktake.get("topLossItems", [])[:4]), 0, -1)), "label": {"show": True, "position": "top"}}],
                },
            })

    if scenario == "store_compare":
        chain = pos.get("chainRank") if isinstance(pos.get("chainRank"), dict) else {}
        store_count = _owner_float(chain.get("storeCount"), 8) or 8
        rank_rows = [
            ("总收入排名", _owner_float(chain.get("revenueRank"), 6) or 6),
            ("日均排名", _owner_float(chain.get("dailyRank"), 2) or 2),
            ("客单价排名", _owner_float(chain.get("aovRank"), 5) or 5),
            ("评价排名", _owner_float(chain.get("reviewRank"), 3) or 3),
        ]
        charts.append(_owner_bar_chart(
            "这家店在连锁里到底强在哪弱在哪",
            [row[0] for row in rank_rows],
            [{"name": f"{int(store_count)}家店内排名", "type": "bar", "data": [row[1] for row in rank_rows], "label": {"show": True, "position": "top"}}],
            y_name="排名，越小越好",
        ))

        peer_rows: list[tuple[str, float]] = []
        for item in pos.get("peerStores") or []:
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "对标店").replace("青花椒", "")[:8]
            aov = _owner_float(item.get("aov"))
            if aov is not None:
                peer_rows.append((name, round(aov, 1)))
        if peer_rows:
            charts.append(_owner_bar_chart(
                "客单价和对标门店差多少",
                [row[0] for row in peer_rows],
                [{"name": "客单价", "type": "bar", "data": [row[1] for row in peer_rows], "label": {"show": True, "position": "top", "formatter": "{c}元"}}],
                y_name="元",
            ))

    return charts[:3]


def _owner_chart_guide(scenario: str) -> str:
    if scenario == "traffic_conversion":
        return "先看第一张：本店每100个路过的人进店几个，和同层类似店差多少；再看第二张：哪个平台有人看但没下单。"
    if scenario == "package":
        return "看售价、食材成本和毛利三根柱子：能卖、毛利不塌，才值得推套餐。"
    if scenario == "seating_mix":
        return "先看客人占比和桌型占比是不是错位：两人客多但两人桌少，就先改拼桌和桌型。"
    if scenario == "staffing_schedule":
        return "先看生意集中在哪个时段，再看人手有没有跟上；差评主题用来验证是不是排班造成的。"
    if scenario == "kitchen_quality":
        return "先看差评集中在哪个问题和哪道菜；厨房今天只抽查被点名最多的菜。"
    if scenario == "staff_training":
        return "先看顾客骂得最多的服务问题，再把培训话术对准这个问题，不要泛泛培训。"
    if scenario == "cost_margin":
        return "先看哪道菜收入高但成本吃掉太多，再看整店成本率和盘点损耗。"
    if scenario == "external_event_response":
        return "先看活动和天气会不会抬高客流，再看门口能不能把这波人接住。"
    if scenario == "single_item_push":
        return "先看平台入口能不能承接，再看主推菜或套餐毛利是否撑得住。"
    if scenario == "store_compare":
        return "先看这家店在连锁里的排名结构：日均不弱但客单价和工作日弱，再看应该复制哪家店的做法。"
    return "图表是给建议做证据用的：先看差距最大的柱子，再决定今天先改哪一个动作。"


def _owner_chat_follow_ups(scenario: str) -> list[str]:
    scenario_specific = {
        "package": "这个小套餐按毛利和成本怎么配？",
        "seating_mix": "要看哪些数据决定二人桌和四人桌比例？",
        "staffing_schedule": "今天排班应该怎么调？",
        "staff_training": "服务员这周先训练哪三句话术？",
        "kitchen_quality": "厨房先改哪道菜、怎么验收？",
        "cost_margin": "哪些菜要先查BOM和盘点损耗？",
        "traffic_conversion": "客流多但进店少，今天先改哪个入口？",
        "external_event_response": "今天商场活动和天气怎么影响备货？",
        "single_item_push": "主推单品放在哪些入口最合适？",
        "store_compare": "这家店应该复制哪家门店的哪一个动作？",
    }
    first = scenario_specific.get(scenario, "这件事今天第一步做什么？")
    return [first, "老板今天先看哪三个数？", "如果只做一天，怎么判断有没有效果？"]


def _owner_metric_follow_up_answer(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    text = (message or "").strip()
    is_metric_question = any(keyword in text for keyword in ("哪三个数", "三个数", "哪些数", "看什么数", "判断有没有效果", "有没有效果"))
    if not is_metric_question:
        return ""

    metric_sets = {
        "traffic_conversion": (
            "门口路过人数",
            "进店人数",
            "最后下单数",
            "路过人差不多、进店和下单一起涨，说明入口话术和线上承接改对了。",
        ),
        "seating_mix": (
            "二人客平均等位时间",
            "二人桌翻台次数",
            "空桌时间",
            "等位少了、翻台没掉、空桌也少了，说明桌型和拼桌规则改对了。",
        ),
        "staffing_schedule": (
            "高峰等位时间",
            "平均上菜时长",
            "差评里上菜慢/排队久的次数",
            "等位和上菜时间降下来，同时差评关键词减少，说明排班和传菜动线有效。",
        ),
        "staff_training": (
            "催菜投诉次数",
            "服务态度差评次数",
            "核销/引导成功单数",
            "投诉少了、核销更顺、顾客少犹豫，说明培训话术有效。",
        ),
        "kitchen_quality": (
            "被点名菜的差评次数",
            "出餐抽查不合格次数",
            "退菜/重做次数",
            "差评、抽查不合格、退菜一起降，才说明厨房真的稳了。",
        ),
        "package": (
            "套餐卖出份数",
            "套餐客单价",
            "套餐毛利率",
            "卖得多、客单没被拉低、毛利守住，才是值得继续推的套餐。",
        ),
        "cost_margin": (
            "食材成本率",
            "BOM理论用量和实际用量差异",
            "盘点损耗金额",
            "成本率降了、BOM差异收窄、盘点损耗减少，才说明利润漏点被堵住。",
        ),
        "external_event_response": (
            "商场活动带来的门口路过人数",
            "活动时段进店率",
            "备货售罄/浪费情况",
            "客流来了能进店、备货不卖空也不浪费，说明活动应对有效。",
        ),
        "single_item_push": (
            "主推菜销量",
            "主推菜连带加购率",
            "主推菜毛利额",
            "卖得动、能带加购、毛利额变高，说明主推单品选对了。",
        ),
        "store_compare": (
            "工作日午市收入",
            "双人套餐占比",
            "客单价",
            "这三个数追近对标店，说明复制动作有效；如果只有订单涨但客单不涨，说明还是只吃到了自然客流。",
        ),
    }
    first, second, third, judgement = metric_sets.get(
        scenario,
        ("订单数", "客单价", "差评关键词", "订单和客单变好、差评没有变多，说明今天动作有效。"),
    )
    focus = owner_page.get("decisionFocus") if isinstance(owner_page.get("decisionFocus"), dict) else {}
    problem = _plain_text(focus.get("primaryProblem"))
    return "\n\n".join([
        f"只看这三个数就够了：{first}、{second}、{third}。",
        f"怎么看：{judgement}",
        f"今天的判断口径：先围绕“{problem or '当前最主要的问题'}”看，不要临时换成别的问题。",
        "今天先别加新动作。先把这三个数记下来，明天同一时段再比一次。",
    ])


def _owner_chat_answer(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    headline = _plain_text(owner_page.get("headline"))
    diagnosis = _plain_text(owner_page.get("plainDiagnosis"))
    focus = owner_page.get("decisionFocus") or {}
    problem = _plain_text(focus.get("primaryProblem") if isinstance(focus, dict) else None)
    focus_reason = _plain_text(focus.get("why") if isinstance(focus, dict) else None)
    first_action = _first_text(owner_page.get("doFirst"))
    plain_reason = _owner_plain_reason(owner_page, scenario, focus_reason or diagnosis)
    plain_actions = _owner_plain_actions(owner_page, scenario, first_action)
    do_not_do = _owner_do_not_do(owner_page, scenario)
    watch_numbers = _owner_watch_numbers(owner_page, scenario)
    evidence_text = _owner_plain_evidence(owner_page, scenario)

    metric_follow_up = _owner_metric_follow_up_answer(owner_page, scenario, message)
    if metric_follow_up:
        return metric_follow_up

    direction_label = {
        "traffic_conversion": "客流转化",
        "package": "套餐和毛利",
        "seating_mix": "桌型和翻台",
        "staffing_schedule": "排班和服务",
        "staff_training": "员工训练",
        "kitchen_quality": "厨房出品",
        "cost_margin": "成本毛利",
        "external_event_response": "商圈活动",
        "single_item_push": "主推单品",
        "store_compare": "门店对比",
    }.get(scenario, "今天动作")

    parts = [
        f"一句话结论：{problem or headline or '今天先抓一个最影响营收的问题。'}",
        f"我按“{direction_label}”来判断，不是先让你打折或凭感觉改。",
    ]
    if plain_reason:
        parts.append(f"为什么这么说：{plain_reason}")
    if plain_actions:
        action_lines = "\n".join(f"{index}. {action}" for index, action in enumerate(plain_actions[:3], start=1))
        parts.append(f"今天就做这几件事：\n{action_lines}")
    if do_not_do:
        parts.append(f"今天先别做：{do_not_do}")
    if watch_numbers:
        parts.append(f"明天怎么判断有没有用：{watch_numbers}")
    if evidence_text:
        parts.append(f"背后的数据我看过了：{evidence_text}")
    parts.append("你继续追问时，我会围绕同一个问题往下拆，不会换题。")
    return "\n\n".join(part for part in parts if part)


def owner_action_chat(body: OwnerActionChatRequest) -> dict:
    return _owner_action_chat_impl(body, request=None)


@router.post("/owner-action-chat")
async def owner_action_chat_http(body: OwnerActionChatRequest, request: Request) -> dict:
    started_at = time.time()
    response = _owner_action_chat_impl(body, request=request)
    data = response.get("data") if isinstance(response, dict) else {}
    if isinstance(data, dict):
        factory_id = _effective_str(body.factory_id, body.factoryId, default="RES_DEMO_QHJ")
        charts = data.get("charts") if isinstance(data.get("charts"), list) else []
        owner_page = data.get("ownerDecisionPage") if isinstance(data.get("ownerDecisionPage"), dict) else {}
        log_id = await _log_owner_action_chat_async(
            query=body.message,
            factory_id=factory_id,
            answer=str(data.get("answer") or data.get("responseText") or ""),
            scenario=str(data.get("scenario") or ""),
            session_id=str(data.get("sessionId") or ""),
            owner_page=owner_page,
            charts=charts,
            total_wall_ms=int((time.time() - started_at) * 1000),
            is_follow_up=_is_follow_up(body.message),
        )
        data["log_id"] = log_id
        data["logId"] = log_id
    return response


def _owner_action_chat_impl(body: OwnerActionChatRequest, request: Request | None = None) -> dict:
    """Demo chat wrapper for boss-facing restaurant action analysis.

    This endpoint intentionally uses deterministic demo scenarios. It gives the
    frontend a stable chat trigger while the full Java intent/SSE route remains
    factory-admin oriented.
    """

    factory_id = _effective_str(body.factory_id, body.factoryId, default="RES_DEMO_QHJ")
    session_id = _effective_str(body.session_id, body.sessionId) or f"owner-action-{uuid.uuid4().hex[:12]}"
    previous = _OWNER_ACTION_CHAT_SESSIONS.get(session_id, {})
    if not previous and _is_follow_up(body.message):
        previous = {"scenario": _OWNER_ACTION_FACTORY_LAST_SCENARIOS.get(factory_id, "")}
    requested = _effective_str(body.demo_scenario, body.demoScenario)
    scenario = _pick_owner_action_scenario(body.message, requested, previous.get("scenario", ""))

    try:
        params = get_owner_action_demo_scenario(scenario)
    except KeyError as exc:
        raise HTTPException(status_code=400, detail=f"Unknown demo scenario: {scenario}") from exc

    store_name = _effective_str(body.store_name, body.storeName, params.get("store_name"))
    sub_sector = _effective_str(body.sub_sector, body.subSector, params.get("sub_sector"), default="餐饮")
    period = _effective_str(body.period, default="2026-07-demo")
    params.update({
        "demoMode": True,
        "demoScenario": scenario,
        "store_name": store_name,
        "ownerQuestion": body.message,
    })

    req = SectionRequest(
        factory_id=factory_id,
        upload_id=None,
        sub_sector=sub_sector,
        store_id=None,
        store_name=store_name,
        period=period,
        params=params,
    )

    try:
        response = HANDLERS["boss_decision_brief"].compute(req, context={})
    except Exception as exc:
        logger.exception("Owner action chat failed")
        raise HTTPException(status_code=500, detail=f"Owner action chat failed: {exc}") from exc

    if response.status != SectionStatus.OK:
        raise HTTPException(status_code=500, detail="Owner action chat section did not return OK")

    data = response.data or {}
    owner_page = data.get("ownerDecisionPage") or {}
    if isinstance(owner_page, dict):
        owner_page["demoParams"] = params
    answer = _owner_chat_answer(owner_page, scenario, body.message)
    follow_ups = _owner_chat_follow_ups(scenario)
    charts = _owner_evidence_charts(owner_page, scenario, params)
    chart_guide = _owner_chart_guide(scenario) if charts else ""
    _OWNER_ACTION_CHAT_SESSIONS[session_id] = {
        "scenario": scenario,
        "lastMessage": body.message,
        "lastAnswer": answer,
    }
    _OWNER_ACTION_FACTORY_LAST_SCENARIOS[factory_id] = scenario

    return {
        "success": True,
        "data": {
            "sessionId": session_id,
            "scenario": scenario,
            "answer": answer,
            "responseText": answer,
            "log_id": None,
            "logId": None,
            "followUpSuggestions": follow_ups,
            "charts": charts,
            "chartGuide": chart_guide,
            "decisionFocus": owner_page.get("decisionFocus"),
            "ownerDecisionPage": owner_page,
            "demoActionScenarios": list_owner_action_demo_scenarios(),
        },
    }


@router.post("/{section_name}")
def compute_section(
    section_name: str = Path(..., description="Section handler name (see /list)"),
    body: SectionRequestBody = ...,
) -> dict:
    """Run a single section handler and return its envelope.

    The response shape mirrors the project's standard ``{success, data, ...}``
    envelope but additionally includes the SectionResponse metadata
    (``status``, ``warnings``, ``cacheKey``, ``computedAtMs``) so the
    frontend can render skip reasons or surface compute time.
    """
    handler = HANDLERS.get(section_name)
    if handler is None:
        raise HTTPException(
            status_code=404,
            detail=f"Unknown section: {section_name!r}. Use GET /list to discover available sections.",
        )

    req = SectionRequest(
        factory_id=body.factory_id,
        upload_id=body.upload_id,
        sub_sector=body.sub_sector,
        store_id=body.store_id,
        store_name=body.store_name,
        period=body.period,
        params=body.params,
    )

    cache_key = handler.cache_key(req)
    cached = _cache.get(cache_key)
    if cached is not None:
        return {**cached, "fromCache": True}

    # ── Auto-resolve upload data when called from Tool-Skill pipeline ──
    # If no upload_id and no POS data in params, try loading from latest upload.
    # FIX BUG #1 (Apr 15 2026): pick upload by section's data_kind, not just MAX(created_at).
    # Multi-file scenario (5 dataTypes co-exist) was breaking 34/36 sections.
    context: dict[str, Any] = {}
    data_kind = SECTION_DATA_KIND.get(section_name, "pos")
    auto_resolve_meta: dict[str, Any] = {"triggered": False, "reason": "not_attempted", "dataKind": data_kind}
    if data_kind != "none" and not body.upload_id and not body.params.get("pos_df"):
        auto_resolve_meta["triggered"] = True
        try:
            from smartbi.database import get_db
            from smartbi.database.repository import UploadRepository, DynamicDataRepository

            db = next(get_db())
            try:
                upload_repo = UploadRepository(db)
                # PRIMARY: dataKind-aware pick
                latest = upload_repo.get_latest_for_data_kind(body.factory_id, data_kind)
                pick_strategy = f"data_kind={data_kind}"
                # FALLBACK: any latest if dataKind has no match (preserves prior demo behaviour)
                if latest is None:
                    latest = upload_repo.get_latest_for_data_kind(body.factory_id, "any")
                    pick_strategy = "any (fallback)"

                if latest:
                    req = SectionRequest(
                        factory_id=body.factory_id,
                        upload_id=str(latest.id),
                        sub_sector=body.sub_sector,
                        store_id=body.store_id,
                        store_name=body.store_name,
                        period=body.period,
                        params=body.params,
                    )
                    # Load rows as DataFrame
                    data_repo = DynamicDataRepository(db)
                    row_dicts = data_repo.get_by_upload_id(body.factory_id, latest.id)
                    if row_dicts:
                        df = pd.DataFrame(row_dicts)
                        # Place in both pos_df (legacy) and the data_kind-specific slot so
                        # finance/reviews handlers can opt into reading from their own slot.
                        context["pos_df"] = df
                        context[f"{data_kind}_df"] = df
                        context["upload_id"] = latest.id
                        context["file_name"] = latest.file_name
                        auto_resolve_meta.update({
                            "reason": "loaded",
                            "uploadId": latest.id,
                            "fileName": latest.file_name,
                            "rows": len(df),
                            "pickStrategy": pick_strategy,
                        })
                        logger.info(
                            "Auto-resolved upload %d (%s) for factory %s: %d rows [data_kind=%s, %s]",
                            latest.id, latest.file_name, body.factory_id, len(df), data_kind, pick_strategy,
                        )
                    else:
                        auto_resolve_meta["reason"] = "upload_found_but_no_rows"
                        auto_resolve_meta["uploadId"] = latest.id
                else:
                    auto_resolve_meta["reason"] = "no_uploads_for_factory_or_kind"
            finally:
                db.close()
        except Exception as e:
            auto_resolve_meta["reason"] = f"error: {e}"
            logger.warning("Auto-resolve upload failed: %s", e)

    try:
        response = handler.compute(req, context=context)
    except Exception as exc:
        # Section handlers should normally return SKIPPED instead of raising,
        # but if one slips through we surface it as 500 rather than crashing
        # the worker.
        logger.exception("Section %s crashed during compute", section_name)
        raise HTTPException(status_code=500, detail=f"Section {section_name} crashed: {exc}") from exc

    result = {
        "success": response.status == SectionStatus.OK,
        "sectionName": response.section_name,
        "status": response.status.value,
        "data": response.data,
        "warnings": response.warnings,
        "cacheKey": response.cache_key,
        "computedAtMs": response.computed_at_ms,
        "fromCache": False,
        # Echo auto-resolve evidence so E2E tests can verify the code path ran
        # (not just "endpoint returned 200 but skipped without loading data").
        "autoResolve": auto_resolve_meta,
    }
    # Only cache OK responses — SKIPPED/FAILED may resolve on next request
    # (e.g. after the caller uploads more data).
    if response.status == SectionStatus.OK:
        _cache.set(cache_key, result)
    return result


@router.get("/list")
def list_sections() -> dict:
    """Return the list of available section handler names."""
    return {"sections": sorted(HANDLERS.keys())}


@router.get("/ppt-export/download/{factory_id}/{period}")
def download_monthly_ppt(factory_id: str, period: str):
    """Stream a previously-generated monthly PPT file for download.

    Call POST /sections/monthly_ppt_export first to generate the file,
    then GET this endpoint to download it. Files are stored in
    /tmp/smartbi_ppt/ and named monthly_{factory_id}_{period}.pptx.
    """
    output_file = (
        FsPath(tempfile.gettempdir())
        / "smartbi_ppt"
        / f"monthly_{factory_id}_{period}.pptx"
    )
    if not output_file.exists():
        raise HTTPException(
            status_code=404,
            detail=(
                f"PPT not found for factory_id={factory_id!r} period={period!r}. "
                f"Call POST /sections/monthly_ppt_export first."
            ),
        )
    return FileResponse(
        path=str(output_file),
        filename=f"月度经营分析_{factory_id}_{period}.pptx",
        media_type=(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        ),
    )
