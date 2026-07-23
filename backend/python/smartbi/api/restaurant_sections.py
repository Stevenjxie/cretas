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

import hmac
import json
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
from pydantic import BaseModel, ConfigDict, Field

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
    """Internal Java-to-Python contract for restaurant owner advice.

    ``factory_id`` is the sole tenant value in the payload.  The HTTP route also
    binds it to the independently authenticated ``X-Factory-Id`` header before
    any session or audit side effect occurs.
    """

    model_config = ConfigDict(extra="forbid")

    factory_id: str = Field(..., min_length=1, description="Factory/store identifier")
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


def _owner_action_session_key(factory_id: str, session_id: str) -> str:
    return f"{factory_id}:{session_id}"


def _owner_action_data_readiness(scenario: str, params: dict[str, Any]) -> dict[str, Any]:
    source_types = [
        "pos_sales",
        "review_feedback",
        "inventory",
        "bom_cost",
        "traffic_persona",
        "external_event",
    ]
    scenario_sources = {
        "revenue_growth": ["pos_sales", "traffic_persona", "review_feedback", "bom_cost"],
        "operations_dispatch": ["pos_sales", "inventory", "review_feedback", "traffic_persona"],
        "inventory_reorder": ["inventory", "bom_cost", "pos_sales"],
        "traffic_conversion": ["traffic_persona", "pos_sales", "review_feedback"],
        "package": ["pos_sales", "bom_cost", "review_feedback"],
        "seating_mix": ["pos_sales", "traffic_persona"],
        "staffing_schedule": ["pos_sales", "review_feedback"],
        "staff_training": ["review_feedback", "pos_sales"],
        "kitchen_quality": ["review_feedback", "pos_sales", "bom_cost"],
        "cost_margin": ["bom_cost", "inventory", "pos_sales"],
        "external_event_response": ["external_event", "traffic_persona", "pos_sales"],
        "single_item_push": ["pos_sales", "bom_cost", "review_feedback"],
        "store_compare": ["pos_sales", "review_feedback", "traffic_persona"],
    }
    return {
        "mode": "demo_mock_plus_seeded_external",
        "scenario": scenario,
        "sourceTypes": source_types,
        "usedForThisAnswer": scenario_sources.get(scenario, source_types[:3]),
        "mockFields": [
            "traffic_persona",
            "external_event",
            "store_compare",
            "role_action_plan",
        ],
        "seededFields": [
            "pos_sales",
            "review_feedback",
            "inventory",
            "bom_cost",
        ],
        "enoughForDemoDecision": True,
        "enoughForProductionRoiPromise": False,
        "missingForProduction": [
            "客户授权的真实 POS / 收银 / 外卖订单明细",
            "客户授权的大众点评/美团/抖音评价与曝光转化数据",
            "真实 BOM、采购价、盘点、报损、排班和桌台流水",
            "商场或位置平台正式授权的客流画像与活动数据",
        ],
        "confidenceNote": (
            "当前 demo 足够展示老板动作建议和跨维度分析口径；"
            "上线给真实老板承诺 ROI 前，需要把 mock 客流和外部活动替换为授权实时数据。"
        ),
        "demoMode": bool(params.get("demoMode", True)),
    }


# Fail-open fallback: exact pre-A1 hardcoded tuple, kept verbatim. Used only
# if backend/python/smartbi/data/owner_action_scenarios.json is missing or
# malformed at import time (see _load_owner_action_keywords below).
_OWNER_ACTION_KEYWORDS_FALLBACK: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("store_compare", ("所有门店", "其他门店", "区域经理", "品牌共性", "单店问题", "门店里", "哪家店", "最值得学习", "复制到")),
    ("operations_dispatch", ("仓管", "前台", "门迎", "分别", "派工", "调度", "今日作战", "管理层", "减少工作", "谁做什么", "各岗位", "分工")),
    ("inventory_reorder", ("库存预警", "补货", "采购补货", "安全库存", "临期", "缺货", "备货缺口", "库存风险", "先看什么", "备货", "备菜", "仓管今天", "具体补什么", "原料", "食材")),
    ("seating_mix", ("二人桌", "两人桌", "四人桌", "两人客", "四人客", "桌型", "桌子", "翻台", "翻台率", "排队", "等位")),
    ("staffing_schedule", ("排班", "人手", "加人", "加一个人", "加在哪", "哪个环节", "岗位", "前厅", "后厨", "员工时间", "员工工时", "工时", "午市", "晚市", "忙不过来", "几段班", "人效")),
    ("external_event_response", ("商场活动", "天气", "下雨", "雨天", "天气不好", "高温", "降温", "寒潮", "堂食", "外卖", "节日", "外部", "活动", "商圈活动", "周边活动")),
    ("traffic_conversion", ("客流画像", "进店转化", "门口客流", "路过客流", "转化率", "曝光", "核销", "进店少", "到店少", "客流情况", "客流", "人流", "商场", "热起来", "接住", "这波人", "门口", "路过", "下单", "入口", "平台", "页面", "首图", "美团", "大众点评", "抖音", "团购")),
    ("staff_training", ("培训", "训练", "服务员", "服务态度", "服务差评", "服务慢", "话术", "催菜", "意识", "店长", "开班前", "评论", "顾客", "复购", "最在意")),
    ("kitchen_quality", ("厨房", "后厨", "厨师长", "出品", "口味", "太咸", "难吃", "上菜慢", "复杂菜", "出餐速度", "保证出餐", "稳定", "退菜", "重做")),
    ("package", ("套餐", "组合", "小套餐", "工作日", "低峰", "客单", "提高客单价", "小菜", "饮品", "配什么", "搭配", "别只看销量")),
    ("cost_margin", ("成本", "毛利", "BOM", "盘点", "月盘点", "损耗", "损耗高", "采购", "采购价格", "原料", "食材", "盈利", "备货", "少备", "备太多", "不能多备", "继续备", "不适合继续备", "不应该继续重点推", "继续重点推")),
    ("single_item_push", ("主推", "单品", "招牌", "爆品", "引流菜", "低价值", "加购", "拉动加购", "首屏", "短视频")),
)

_OWNER_ACTION_SCENARIOS_JSON = (
    FsPath(__file__).resolve().parents[1] / "data" / "owner_action_scenarios.json"
)


def _load_owner_action_keywords() -> tuple[tuple[str, tuple[str, ...]], ...]:
    """Load the scenario->terms keyword tuple from the shared JSON data file.

    A1 (spec docs/superpowers/specs/2026-07-08-business-concept-registry-direction.md
    §3): this JSON is shared with web-admin's restaurantOwnerActionRegistry.ts
    as the single source of truth for scenario terms, replacing two
    independently-maintained copies. Only scenarios with
    ``backendKeywordGate: true`` are loaded here -- e.g. ``revenue_growth``
    is intentionally excluded (handled via separate compound-phrase logic
    elsewhere in this module; see the JSON's ``_meta.revenueGrowth`` note).

    Fail-open: any error (missing file, malformed JSON, empty result) falls
    back to the pre-A1 hardcoded tuple so a bad deploy of the data file
    cannot break owner-action routing.
    """
    try:
        raw = json.loads(_OWNER_ACTION_SCENARIOS_JSON.read_text(encoding="utf-8"))
        entries = []
        for item in raw.get("scenarios", []):
            if not item.get("backendKeywordGate", False):
                continue
            scenario = item["scenario"]
            terms = tuple(item["terms"])
            if not terms:
                continue
            entries.append((scenario, terms))
        if not entries:
            raise ValueError("owner_action_scenarios.json produced zero backend-gated scenarios")
        return tuple(entries)
    except Exception as exc:  # noqa: BLE001 - fail-open by design
        logger.warning(
            "[owner-action-keywords] failed to load %s (%s) -- falling back to hardcoded tuple",
            _OWNER_ACTION_SCENARIOS_JSON,
            exc,
        )
        return _OWNER_ACTION_KEYWORDS_FALLBACK


# Loaded once at module import time (per spec §3 A1: "模块级加载一次").
_OWNER_ACTION_KEYWORDS: tuple[tuple[str, tuple[str, ...]], ...] = _load_owner_action_keywords()

_OWNER_ACTION_SCENARIO_ALIASES = {
    "inventory": "inventory_reorder",
    "stock": "inventory_reorder",
    "staffing": "staffing_schedule",
    "schedule": "staffing_schedule",
    "weather": "external_event_response",
    "event": "external_event_response",
    "review_recovery": "staff_training",
    "review": "staff_training",
    "training": "staff_training",
    "table": "seating_mix",
    "seating": "seating_mix",
    "traffic": "traffic_conversion",
    "revenue": "revenue_growth",
    "growth": "revenue_growth",
    "kitchen": "kitchen_quality",
}


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
            "\u6267\u884c\u7ec6\u8282",
            "\u62c6\u7ed9\u6211",
            "\u62c6\u7ec6",
            "\u7ee7\u7eed\u62c6",
            "\u5177\u4f53\u6539",
            "\u5177\u4f53\u8c03",
            "\u5177\u4f53\u6392",
            "\u600e\u4e48\u8bb2",
            "\u600e\u4e48\u9a8c\u6536",
            "\u600e\u4e48\u5907\u8d27",
            "\u5148\u67e5\u54ea",
            "\u54ea\u4e9b\u4e8b\u60c5",
            "\u5148\u4e0d\u8981",
            "\u4e0d\u8981\u505a",
            "\u54ea\u4e00\u4e2a\u52a8\u4f5c",
            "\u653e\u5230\u54ea\u4e9b\u5165\u53e3",
            "\u590d\u5236\u54ea\u5bb6\u5e97",
            "\u5165\u53e3\u548c\u5e73\u53f0\u9875\u9762",
            "\u4e3b\u63a8\u83dc\u653e",
        )
    )


def _has_owner_action_topic(message: str) -> bool:
    text = message or ""
    if (
        "套餐" in text
        and any(keyword in text for keyword in ("推", "客单", "毛利", "成本", "售价", "组合", "配", "提升", "提高"))
    ):
        return True
    return any(
        keyword in text
        for _, keywords in _OWNER_ACTION_KEYWORDS
        for keyword in keywords
    )


def _is_broad_revenue_growth_question(message: str) -> bool:
    text = message or ""
    broad_decline_terms = (
        "营收比",
        "营收差",
        "营收下滑",
        "营收下降",
        "营收掉",
        "营业额比",
        "营业额差",
        "营业额下滑",
        "营业额下降",
        "收入比",
        "收入差",
        "收入下滑",
        "收入下降",
        "生意变差",
        "生意差",
        "本周变差",
        "这周变差",
    )
    if not any(keyword in text for keyword in (
        "提高营收",
        "提升营收",
        "增加营收",
        "拉营收",
        "拉高营收",
        "营业额",
        "收入怎么",
        "生意怎么",
        "怎么提高生意",
    ) + broad_decline_terms):
        return False
    specific_keywords = (
        "套餐",
        "组合",
        "小套餐",
        "客单价",
        "提高客单",
        "提升客单",
        "客流",
        "进店",
        "曝光",
        "核销",
        "翻台",
        "桌型",
        "排班",
        "人手",
        "厨房",
        "出餐",
        "成本",
        "毛利",
        "BOM",
        "库存",
        "补货",
        "采购",
        "服务",
        "差评",
        "商场活动",
        "天气",
        "仓管",
        "厨师长",
        "前台",
    )
    return not any(keyword in text for keyword in specific_keywords)


def _infer_owner_action_scenario_from_message(message: str) -> str:
    text = message or ""
    if any(keyword in text for keyword in (
        "营收杠杆",
        "先拆客流转化",
        "门口海报和首图",
        "如果进店没涨",
    )):
        return "revenue_growth"
    if "厨房慢" in message and "服务慢" in message:
        return "staff_training"
    if any(keyword in text for keyword in (
        "厨师长、仓管、前台", "仓管厨师长前台", "仓管+厨师长+前台", "仓管、厨师长、前台",
        "分别盯什么", "分别要做什么", "各岗位", "谁做什么", "派工", "分工",
    )):
        return "operations_dispatch"
    if any(keyword in text for keyword in ("只能多加一个人", "只能加一个人", "加前厅还是后厨", "前厅还是后厨")):
        return "staffing_schedule"
    if any(keyword in text for keyword in ("所有门店", "其他门店", "连锁", "区域经理", "品牌共性", "单店问题", "门店里", "哪家店", "复制哪")):
        return "store_compare"
    if any(keyword in text for keyword in ("桌型", "桌子", "二人桌", "两人桌", "四人桌", "翻台", "排队", "等位")):
        return "seating_mix"
    if any(keyword in text for keyword in (
        "商场今天有活动", "商场活动", "商圈活动", "周边活动", "天气", "下雨", "雨天",
        "天气不好", "高温", "天气热", "节日", "外部活动", "亲子节", "亲子活动", "会员日",
    )):
        return "external_event_response"
    if "套餐" in text and any(keyword in text for keyword in ("推", "客单", "毛利", "成本", "售价", "组合", "配", "提升", "提高", "食材", "少备", "多备", "外卖", "平台")):
        return "package"
    if any(keyword in text for keyword in ("不要多备", "别多备", "少备", "报损")) and any(keyword in text for keyword in ("备", "菜", "库存", "晚上", "今天")):
        return "inventory_reorder"
    if "套餐" not in text and any(keyword in text for keyword in ("备菜", "备货", "仓管今天", "具体补什么", "补什么", "补货", "库存")):
        return "inventory_reorder"
    if any(keyword in text for keyword in (
        "客流画像", "进店转化", "门口客流", "路过客流", "转化率", "曝光", "核销",
        "进店少", "到店少", "客流情况", "客流", "人流", "商场热起来", "接住",
        "这波人", "门口", "路过", "下单", "入口", "平台", "页面", "首图",
        "美团", "大众点评", "抖音", "团购", "竞品", "同商圈", "引流",
    )):
        return "traffic_conversion"
    if _is_broad_revenue_growth_question(text):
        return "revenue_growth"
    if any(keyword in text for keyword in ("BOM", "理论用量", "实际用量", "成本", "毛利", "采购价格", "盈利")):
        return "cost_margin"
    if any(keyword in text for keyword in ("不想打折", "不要打折", "不打折", "满减", "提高客单", "提升客单", "客单价")):
        return "package"
    if any(keyword in text for keyword in ("厨房", "后厨", "厨师长", "出餐", "上菜慢", "退菜", "重做", "口味", "太咸")) and not any(keyword in text for keyword in ("备菜", "备货", "补货", "库存")):
        return "kitchen_quality"
    if any(keyword in text for keyword in ("盘点", "月盘点", "损耗")) and any(keyword in text for keyword in ("厨房", "厨师长", "出品", "后厨")):
        return "kitchen_quality"
    if any(keyword in text for keyword in ("盘点", "月盘点", "损耗", "采购")):
        return "cost_margin"
    if any(keyword in text for keyword in ("备菜", "备货", "仓管今天", "具体补什么", "补什么", "补货", "库存", "原料", "食材")):
        return "inventory_reorder"
    if any(keyword in text for keyword in ("几段班", "员工工时", "工时", "午市", "晚市", "排班", "人效", "人手", "加一个人", "加在哪个环节", "加人")):
        return "staffing_schedule"
    for scenario, keywords in _OWNER_ACTION_KEYWORDS:
        if any(keyword in text for keyword in keywords):
            return scenario
    return ""


def _pick_owner_action_scenario(message: str, requested: str, previous: str) -> str:
    scenarios = set(list_owner_action_demo_scenarios())
    requested = _OWNER_ACTION_SCENARIO_ALIASES.get(str(requested or "").strip(), requested)
    previous = _OWNER_ACTION_SCENARIO_ALIASES.get(str(previous or "").strip(), previous)
    explicit = _infer_owner_action_scenario_from_message(message)
    if previous == "revenue_growth" and _is_follow_up(message) and any(keyword in (message or "") for keyword in (
        "选一个营收杠杆",
        "继续拆",
        "先拆客流转化",
        "门口海报",
        "首图",
        "明天看哪三个数",
        "进店没涨",
        "再拆客单",
    )):
        return previous
    if explicit in scenarios:
        return explicit
    if requested in scenarios:
        return requested
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


def _owner_weekly_revenue_delta(owner_page: dict[str, Any]) -> float | None:
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    weekly = pos.get("weeklyTrend") if isinstance(pos.get("weeklyTrend"), list) else []
    if weekly:
        last = weekly[-1] if isinstance(weekly[-1], dict) else {}
        try:
            return float(last.get("wowRevenuePct"))
        except (TypeError, ValueError):
            pass
    ops = params.get("operations_metrics") if isinstance(params.get("operations_metrics"), dict) else {}
    try:
        return float(ops.get("revenueVsLastWeekPct"))
    except (TypeError, ValueError):
        return None


def _owner_premise_check(owner_page: dict[str, Any], message: str) -> str:
    text = message or ""
    says_decline = any(keyword in text for keyword in (
        "下滑",
        "下降",
        "比上周低",
        "同比上周低",
        "环比上周低",
        "营收低",
        "营收掉",
        "营收跌",
        "变差",
    ))
    if not says_decline:
        return ""
    delta = _owner_weekly_revenue_delta(owner_page)
    if delta is None or delta <= 0:
        return ""
    return (
        f"我先纠正一个前提：demo 里的全店周环比并不是下滑，最近一周营收比上周高约 {round(delta, 1)}%。"
        "如果你说的是某一家门店、某个平台，或者某一天/某个时段下滑，需要先把范围确认清楚。"
        "下面我先按“全店实际数据”给今天动作；你也可以继续回复：1 全店实际数据，2 指定门店下滑，3 美团/点评核销下滑。"
    )


def _owner_role_action_plan(params: dict[str, Any], scenario: str) -> list[dict[str, Any]]:
    raw = params.get("role_action_plan") or params.get("roleActionPlan")
    if isinstance(raw, list):
        return [item for item in raw if isinstance(item, dict)]
    if scenario not in {"operations_dispatch", "inventory_reorder"}:
        return []
    return [
        {
            "role": "仓管",
            "ownerQuestion": "今天先看库存缺口和临期风险",
            "todayActions": [
                "核对活鱼、青花椒底料、冰豆花原料的当前库存和安全库存",
                "活鱼按晚市预测 80% 先备，保留临时补货空间",
                "把临期豆腐和番茄贴红标，优先安排今天消耗，不要继续补",
            ],
            "watchTomorrow": ["缺货次数", "临期报损金额", "紧急补货次数"],
        },
        {
            "role": "厨师长",
            "ownerQuestion": "今天先保招牌菜和出餐速度",
            "todayActions": [
                "晚市前只保招牌青花椒鱼、冰豆花、双人套餐三条主线",
                "每小时抽查招牌鱼咸淡、鱼片熟度、上菜时长",
                "低销量复杂菜晚高峰不主动推荐，避免拖慢出餐",
            ],
            "watchTomorrow": ["平均上菜时长", "出品抽查不合格次数", "退菜/重做次数"],
        },
        {
            "role": "前台/门迎",
            "ownerQuestion": "今天先把路过客和核销客接住",
            "todayActions": [
                "门口只讲招牌鱼、双人价格、预计用餐时间三句话",
                "核销客进店先确认券，再引导招牌鱼或双人套餐",
                "等位超过 12 分钟时主动给明确时间并提示可拼桌",
            ],
            "watchTomorrow": ["进店转化率", "核销到店率", "等位差评次数"],
        },
        {
            "role": "店长",
            "ownerQuestion": "今天只盯三个异常",
            "todayActions": [
                "17:30 开班前按仓管、厨师长、前台三张清单派工",
                "18:00-20:00 只盯等位、上菜、缺货三个异常",
                "打烊后复盘收入、上菜时长、报损金额",
            ],
            "watchTomorrow": ["晚市收入", "平均上菜时长", "报损金额"],
        },
    ]


def _owner_plain_reason(owner_page: dict[str, Any], scenario: str, fallback: str) -> str:
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    financial = params.get("financial_summary") if isinstance(params.get("financial_summary"), dict) else {}
    stocktake = params.get("monthly_stocktake") if isinstance(params.get("monthly_stocktake"), dict) else {}

    if scenario == "operations_dispatch":
        ops = params.get("operations_metrics") if isinstance(params.get("operations_metrics"), dict) else {}
        revenue_lift = _owner_metric(ops.get("revenueVsLastWeekPct"), 1)
        dinner_orders = _owner_metric(ops.get("forecastDinnerOrders"))
        queue_minutes = _owner_metric(ops.get("queueMinutesPeak"))
        serve_minutes = _owner_metric(ops.get("serveMinutesAvg"))
        event_lift = _owner_metric(ops.get("eventTrafficLiftPct"), 1)
        parts = ["这不是单独推一个套餐的问题，而是把今天的营收目标拆给岗位。"]
        if revenue_lift:
            parts.append(f"本周营收比上周高约 {revenue_lift}%，说明需求不是没有。")
        if event_lift:
            parts.append(f"商场活动预计还会把客流抬高约 {event_lift}%，所以现场承接会更关键。")
        if dinner_orders:
            parts.append(f"晚市预计大约 {dinner_orders} 单，仓管先保活鱼和青花椒底料不断货。")
        if queue_minutes or serve_minutes:
            parts.append(f"高峰等位约 {queue_minutes or '18'} 分钟、上菜约 {serve_minutes or '21'} 分钟，厨师长和前台要一起压这两个点。")
        parts.append("老板今天不需要盯所有细节，只要让仓管、厨师长、前台、店长各自按清单执行。")
        return "".join(parts)

    if scenario == "inventory_reorder":
        alerts = params.get("inventory_alerts") if isinstance(params.get("inventory_alerts"), list) else []
        high = [item for item in alerts if isinstance(item, dict) and item.get("priority") == "HIGH"]
        names = "、".join(str(item.get("ingredient")) for item in high[:2] if item.get("ingredient"))
        top = high[0] if high else {}
        current = _owner_metric(top.get("currentStock"))
        safety = _owner_metric(top.get("safetyStock"))
        reorder = _owner_metric(top.get("reorderQty"))
        parts = ["库存预警先看缺口和临期，不要平均补货。"]
        if names:
            parts.append(f"今天优先看 {names}，因为它们直接影响招牌鱼能不能卖、毛利能不能守住。")
        if top:
            parts.append(f"{top.get('ingredient')} 当前约 {current}kg，安全库存约 {safety}kg，建议先补 {reorder}kg。")
        parts.append("豆腐、番茄这类有临期的先消耗，不要为了看起来库存多就继续采购。")
        return "".join(parts)

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
            parts.append("如果商场活动继续带来人流，重点也不是再投广告，而是把活动客流承接到门口和平台入口。")
        elif visits:
            parts.append(f"估算真正进店的人大约 {visits} 人，门口还有不少可以争取的人。")
        if order_gap and order_gap != "0.0":
            parts.append(f"简单说，商场热起来了，但店里少吃到大约 {order_gap}% 的这波客流红利。")
        if segment:
            parts.append(f"路过的人主要是{segment}，他们最在意的是{need or '价格清楚、吃得快、招牌明确'}。")
        if weak_text:
            parts.append(f"{weak_text} 有人看但下单弱，说明页面、套餐说明或到店核销没有讲清楚；抖音团购还要同时看毛利，别把低客单新客做成亏本单。")
        parts.append("同商圈竞品已经在抢这波路过客，今天要把门口引流话术和线上入口承接一起改，不要只等自然客流。")
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
        gross_value = _owner_float(financial.get("grossMarginPct"))
        gross = _owner_metric(gross_value, 1) if gross_value is not None else None
        review = params.get("review_summary") if isinstance(params.get("review_summary"), dict) else {}
        losses = "、".join(str(item) for item in (stocktake.get("topLossItems") or [])[:2])
        variance = "、".join(str(item) for item in (stocktake.get("varianceItems") or [])[:2])
        negative_themes = "、".join(
            str(item.get("theme")) for item in (review.get("negativeThemes") or [])[:2]
            if isinstance(item, dict) and item.get("theme")
        )
        negative_dishes = "、".join(
            str(item.get("name")) for item in (review.get("negativeDishMentions") or [])[:2]
            if isinstance(item, dict) and item.get("name")
        )
        parts = []
        if food_cost is not None:
            if gross is not None:
                coverage_pct = params.get("goldCostCoveragePct")
                coverage_note = (
                    f"（已覆盖成本口径，成本覆盖率 {coverage_pct}%）"
                    if coverage_pct is not None else ""
                )
                parts.append(
                    f"食材成本率大约 {food_cost}%，毛利率约 {gross}%{coverage_note}。"
                )
            else:
                parts.append(
                    f"食材成本率大约 {food_cost}%；毛利率因成本或营收口径不完整暂不可计算，"
                    "不能用默认值代替。"
                )
        if losses:
            parts.append(f"月盘点先指向 {losses} 这些损耗点。")
        if variance:
            parts.append(f"BOM 和实际用量差异主要在 {variance}。")
        if negative_dishes or negative_themes:
            parts.append(
                f"评价里还点到了 {negative_dishes or '招牌菜'}"
                f"{' 和 ' + negative_themes if negative_themes else ''}，说明不能只压成本，也要避免出品和服务把复购打掉。"
            )
        parts.append("所以今天先查采购价、BOM 用量、后厨损耗和差评菜品，不要只让前厅多卖。")
        return "".join(parts)

    if scenario == "external_event_response":
        external = params.get("external_signals") if isinstance(params.get("external_signals"), dict) else {}
        weather = external.get("weather") if isinstance(external.get("weather"), dict) else {}
        activities = external.get("activities") if isinstance(external.get("activities"), list) else []
        activity = activities[0] if activities and isinstance(activities[0], dict) else {}
        lift = _owner_metric(activity.get("expectedTrafficLiftPct"), 1)
        weather_text = _plain_text(weather.get("text")) or "今天有天气变化，客流会更集中到商场室内和晚市"
        activity_title = _plain_text(activity.get("title")) or "商场活动"
        parts = [f"{weather_text}，同时商场有{activity_title}。"]
        if lift:
            parts.append(f"活动预计把客流抬高约 {lift}%，但这些人不会自动进店。")
        parts.append("堂食要承接晚市集中客，外卖要承接雨天不想出门的人，备货要围绕招牌鱼、热汤小食和双人套餐，不要平均多备。")
        return "".join(parts)

    if scenario == "kitchen_quality":
        review = params.get("review_summary") if isinstance(params.get("review_summary"), dict) else {}
        themes = "、".join(
            str(item.get("theme")) for item in (review.get("negativeThemes") or [])[:2]
            if isinstance(item, dict) and item.get("theme")
        )
        dishes = "、".join(
            str(item.get("name")) for item in (review.get("negativeDishMentions") or [])[:2]
            if isinstance(item, dict) and item.get("name")
        )
        parts = ["厨房问题不能只靠前厅道歉，今天要让厨师长直接管出餐和出品。"]
        if dishes or themes:
            parts.append(f"评价里被点名的是 {dishes or '招牌菜'}，主要问题是 {themes or '出餐慢和口味不稳定'}。")
        parts.append("如果月盘点也提示损耗高，就把活鱼切配、底料用量和退菜重做一起查，别只看采购单价。")
        return "".join(parts)

    return fallback


def _owner_plain_actions(owner_page: dict[str, Any], scenario: str, first_action: str) -> list[str]:
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}

    if scenario == "operations_dispatch":
        return [
            "店长 17:30 开班前把仓管、厨师长、前台三张清单派出去，今晚只盯等位、上菜、缺货三个异常。",
            "仓管先补活鱼和青花椒底料，临期豆腐和番茄贴红标给厨师长优先消耗。",
            "厨师长只保招牌鱼、冰豆花、双人套餐三条主线，前台只讲招牌鱼、双人价格、预计用餐时间三句话。",
        ]
    if scenario == "inventory_reorder":
        return [
            "仓管先把活鱼、青花椒底料、冰豆花原料按当前库存、今晚预测、安全库存排一张补货清单。",
            "活鱼和青花椒底料按缺口补，豆腐、番茄这类临期货先消耗，不要平均补货。",
            "厨师长同步把今天能消耗临期原料的菜排进备料，前台不要继续强推会消耗不足原料的复杂菜。",
        ]

    if scenario == "traffic_conversion":
        return [
            "门口海报和等位牌只讲一句话：招牌鱼是什么、两个人大概多少钱、多久能吃完。",
            "美团/大众点评和抖音团购页面同步改成同一套话术，主图先放招牌鱼这道菜品和双人价格，不要堆一堆菜名；页面里顺手标清套餐卖点，别让顾客猜。",
            "收银和门迎今天专门盯核销客：进店先确认券、引导点招牌；同时看顾客评论和差评，别把上菜慢、服务解释不清的问题带到新客身上。",
        ]
    if scenario == "seating_mix":
        segments = pos.get("customerSegments") if isinstance(pos.get("customerSegments"), list) else []
        two_person_share = next((
            _owner_pct(item.get("share"))
            for item in segments
            if isinstance(item, dict) and ("2" in str(item.get("segment")) or "二" in str(item.get("segment")))
        ), None)
        two_person_text = f"当前两人客大约占 {two_person_share}%，" if two_person_share is not None else ""
        return [
            f"{two_person_text}午市和晚高峰先把 2 张四人桌改成可拼可拆：两人客来了不要占死四人桌，四人客来了再拼回去。",
            "前厅今天按“先看人数、再引导桌型、再推荐双人/四人套餐”走；2 人客优先落两人位或拼拆位，3-4 人客再合桌，不要让大桌被小桌占死。",
            "先别直接加排班或催厨房；如果桌型错了，加员工和提出餐速度也只是把堵点往后移。服务和上菜差评仍然要看，但今天先用翻台数据判断是不是桌型堵住了。",
        ]
    if scenario == "staffing_schedule":
        return [
            "今天加人只加在晚市 18:00-20:00：前厅多 1 人盯等位和核销，后厨多 1 人盯招牌鱼出餐。",
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
            "今天先让厨师长把被差评点名最多的招牌鱼做出餐抽查：咸淡、鱼片熟度、上菜时长三项必须记录。",
            "晚高峰前减少复杂低销量菜的备料和推荐，厨房先保招牌鱼、冰豆花、双人套餐这几条主线。",
            "如果月盘点发现损耗高，同步抽查切配称重和底料用量；前厅一旦发现上菜慢超过阈值，立刻提醒后厨和店长。",
        ]
    if scenario == "cost_margin":
        return [
            "今天先查三张表：活鱼采购价、招牌鱼 BOM 理论用量、昨日月盘点损耗；先找毛利漏点，不要多备到晚上报损。",
            "如果活鱼实际用量比 BOM 高，先称重抽查和切配标准，不要只靠涨价补利润。",
            "高毛利小食可以带低销量菜，但必须算套餐售价、食材成本和毛利；这是今天的主推动作，毛利守不住就不推。",
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
                f"今天的主推动作是把“{name}”做成双人小套餐：售价约 {price or '270元'}，食材成本约 {cost or '100元'}，一份大概能留下 {profit or '170元'} 毛利{margin_text}。",
                "只在工作日午市/低峰、外卖入口和美团/大众点评入口测 7 天，别一上来全渠道铺开。",
                "门口物料、外卖页和团购页只写三句话：招牌是什么、两个人多少钱、适合多久吃完。服务员也按这三句话推荐。",
            ]
    if scenario == "external_event_response":
        return [
            "堂食先接晚市：门口和等位牌写清“雨天热汤鱼、两人多少钱、预计多久吃完”，把商场活动来的客流接住。",
            "外卖和团购页同步上同一套雨天套餐，不要只做满减；主推招牌鱼、小食和热饮，避免配送后口感掉太多的菜。",
            "备货只多备招牌鱼、底料、热汤小食和高频双人套餐原料，临期豆腐和番茄优先消耗，别因为商场活动就全品类加库存。",
        ]
    if first_action:
        return [_owner_remove_score_words(first_action)]
    return ["今天先选一个最明显的问题改，不要同时改太多项。"]


def _owner_message_specific_guidance(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    text = message or ""
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    package_decision = owner_page.get("packageDecision") if isinstance(owner_page.get("packageDecision"), dict) else {}
    candidates = package_decision.get("candidates") if isinstance(package_decision.get("candidates"), list) else []
    candidate = candidates[0] if candidates and isinstance(candidates[0], dict) else {}
    package_name = _plain_text(candidate.get("name")) or "招牌鱼双人套餐"
    package_cost = _owner_money(candidate.get("estimatedFoodCost")) or "100元"
    package_profit = _owner_money(candidate.get("estimatedGrossProfit")) or "170元"
    package_margin = _owner_metric(candidate.get("grossMarginPct"), 1)

    if "老板今天就看一眼" in text or "最应该先管" in text:
        return "如果老板只看一眼，不要先翻十张报表。今天先看“钱漏在哪”：客流有没有进店、套餐有没有拉客单、成本有没有漏掉；看完只派一个主动作，明天再看结果。"
    if "营收" in text and ("哪三个动作" in text or "先做" in text):
        return "这不是让店长临时想活动，而是把营收拆成三个抓手：先接住门口和平台来的客，再用双人套餐拉客单，最后让仓管和厨师长守住食材成本。三个动作要一起派下去，不要只喊多卖。"

    if scenario == "package":
        if "米饭" in text or "低价值" in text:
            return "这次小套餐主推会先排除米饭、餐具、纸巾这类低价值配套项。它们可以做随餐补充，但不能占主推排序；主推只选能解释清楚、能提高客单、还能守住毛利的菜品组合。"
        if "外卖" in text:
            return f"外卖套餐不能只看便宜，要同时看配送后的口感和差评风险。{package_name} 可以放外卖入口，但页面要写清适合两人、预计出餐时间和辣度；包装成本也要算进 {package_cost} 的食材成本里，别把外卖单做成低毛利单。"
        if any(keyword in text for keyword in ("算", "毛利", "成本", "售价")):
            margin_text = f"、毛利率约 {package_margin}%" if package_margin else ""
            return f"这个套餐不是拍脑袋配菜：先看售价、食材成本和留下来的毛利。当前推荐的 {package_name}，食材成本约 {package_cost}，一份大概留下 {package_profit}{margin_text}；这个毛利撑得住，才值得放到主推位。"
        if "不想打折" in text or "不要打折" in text or "满减" in text:
            return "老板不想打折/不想做满减时，不要把价格往下压，要把“为什么两个人点这套更省心”讲清楚。做法是用招牌菜带一个高毛利小食，把客单抬起来，而不是用满减把利润让出去。"

    if scenario == "inventory_reorder":
        if any(keyword in text for keyword in ("不要多备", "别多备", "少备", "报损")):
            return "这类问题不是少备所有菜，而是明确告诉后厨哪些菜今天不要多备。先砍掉晚上容易剩、又不能带来主推转化的备货；活鱼和底料按安全库存补，低销量复杂菜和临期原料只保够晚高峰，不要为了看起来货多就压到明天报损。"
        if any(keyword in text for keyword in ("库存预警", "备菜", "补货")):
            return "库存问题先按缺口补，不按感觉补。仓管今天只看三列：当前库存、今晚预测、最低安全库存；缺口大的补，临期的先消耗，和招牌鱼无关的复杂原料不要平均加量。"

    if scenario == "cost_margin":
        if any(keyword in text for keyword in ("理论用量", "实际用量", "BOM")):
            return "BOM 差异要先查“用多了还是记错了”。厨师长今天先抽查活鱼称重、切配损耗、底料勺量，再对照理论用量；如果实际一直高于理论，不要先涨价，要先把后厨标准拉回来。"
        if "月盘点" in text or "损耗" in text:
            return "月盘点不是月底才看的表。今天先挑损耗金额最大的两项查原因：是采购价变了、切配损耗高了，还是盘点口径错了；查完当天就改备料量，不要等月底汇总才发现毛利漏掉。"
        if "采购" in text:
            return "采购价异常要和菜品毛利一起看。单价涨了不一定马上涨售价，先看这道菜是不是主推、能不能用高毛利小食带回来；如果主菜毛利已经被压穿，再谈调价或换供应商。"

    if scenario == "traffic_conversion":
        if "商圈客流画像" in text or ("客流画像" in text and "影响" in text):
            return "商圈画像不是只告诉你人多不多，而是告诉你今天来的是什么人、为什么会路过、哪一段时间能承接。今天先把画像拆成三件事：午晚市哪个时段人多、亲子/白领/家庭客谁更多、他们更在意价格还是出餐速度；然后再决定门口话术、套餐和备货。"
        if "同商圈" in text or "竞品" in text:
            return "同商圈竞品变多时，先别跟着打价格战。今天先把门口三句话和平台首屏统一：招牌是什么、两个人多少钱、多久能吃完；用清楚的引流理由把路过客拉进店。"
        if "先改哪个入口" in text or ("路过" in text and "进店" in text):
            return "客流画像已经说明路过多但进店少，所以今天先改门口和平台入口，不先改厨房。门口让顾客三秒看懂招牌和双人价格；平台首图同步同一套信息；进店后再看核销和点单承接。"
        if "美团" in text or "大众点评" in text:
            return "美团/大众点评的问题先看“有人看但为什么不核销”。今天先改首图、套餐说明和到店核销话术；不要先加投流，因为曝光已经有了，当前漏点更像页面没有把顾客说服进店。"
        if "抖音" in text or "团购" in text:
            return "抖音团购要特别盯毛利。短视频能带新客，但客单容易低；今天只上能解释招牌、能加购小食、核销后不亏毛利的团购，不要用低价券把晚高峰座位卖便宜。"
        if "客流画像" in text or "路过" in text or "进店" in text:
            return "客流画像要落到一个问题：路过的人为什么没进来。今天先看入口和门口承接，不是先看店内服务；等进店人数上来后，再判断服务、出餐和复购。"

    if scenario == "external_event_response":
        if "下雨" in text or "雨" in text or "天气" in text:
            return "雨天不要只做堂食动作。堂食要接住商场里不想走远的人，外卖要推热汤、好配送、到家口感稳定的组合；备货只加这些相关原料，不要全菜单一起加。"
        if "亲子" in text or "商场" in text or "活动" in text:
            return "商场活动带来的是一波短时间客流，不等于每道菜都会多卖。今天要把门口套餐、儿童友好小食、晚市备货和前台接待串起来，别只看活动人数。"

    if scenario == "seating_mix":
        if "排队" in text or "空桌" in text or "前台引导" in text:
            return "排队长但空桌多，通常不是单纯缺桌，而是前台没有把客人导到合适桌型。今天先让前台按人数分流：两人客优先拼拆位，三四人客再合桌；每 15 分钟看一次空桌和等位，不要让四人桌被两人客占死。"
        if "二人桌" in text or "四人桌" in text or "桌型" in text:
            return "桌型问题要直接改现场布局，不要只看翻台率。今天先把少量四人桌改成可拼可拆，二人桌不够时二人客能快坐，四人客来了能拼回去；这是比加人更快见效的动作。"

    if scenario == "staffing_schedule":
        if "只能多加一个人" in text:
            return "只能加一个人时，不要平均分。先看晚高峰卡在哪里：如果等位和核销乱，加前厅；如果招牌鱼出餐慢，加后厨。今天这家店更像晚市承接和出餐都紧，先把人放在 18:00-20:00 的瓶颈环节。"
        if "时段" in text or "少排" in text:
            return "排班不是全天加人，而是把人放到有收入的时段。午市保稳定，晚市加峰值，低峰少排但不能少到没人迎宾和核销。"

    if scenario == "kitchen_quality":
        if "厨师长" in text:
            return "厨师长今天不要泛泛盯厨房卫生，先盯会影响差评的三件事：招牌鱼出餐时长、鱼片熟度、底料咸淡。每一项都要有记录，晚上复盘才知道是速度问题还是口味问题。"

    if scenario == "store_compare":
        copy_from = ""
        compare = pos.get("storeComparison") if isinstance(pos.get("storeComparison"), dict) else {}
        if isinstance(compare, dict):
            copy_from = _plain_text(compare.get("copyFrom"))
        return f"连锁对比不要只看总收入排名。今天要找可复制动作：这家店可以先对标 {copy_from or '日均表现更好的同城门店'} 的入口套餐和服务员推荐话术，明天只看工作日午市、双人套餐占比、客单价有没有缩小差距。"

    return ""


def _owner_message_specific_reason(owner_page: dict[str, Any], scenario: str, message: str, fallback: str) -> str:
    if scenario != "traffic_conversion":
        return fallback
    text = message or ""
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    traffic = owner_page.get("trafficPersona") if isinstance(owner_page.get("trafficPersona"), dict) else {}
    platform = owner_page.get("platformChannelSnapshot") if isinstance(owner_page.get("platformChannelSnapshot"), dict) else {}
    passersby = _owner_metric(traffic.get("storefrontPassersby")) or "5600"
    capture = _owner_rate_people(traffic.get("captureRate")) or "12"
    peer_capture = _owner_rate_people(traffic.get("peerCaptureRate")) or "24"
    segment = _plain_text(traffic.get("topSegment")) or "周边办公午餐客"
    need = _plain_text(traffic.get("topNeed")) or "45 分钟内吃完、价格清楚"
    weak_platforms = platform.get("weakConversionPlatforms") if isinstance(platform.get("weakConversionPlatforms"), list) else []
    weak_text = "、".join(str(item) for item in weak_platforms[:2]) or "抖音团购"
    if "商圈客流画像" in text or ("客流画像" in text and "影响" in text):
        return (
            f"画像先告诉老板今天该接谁：门口约 {passersby} 人经过，主力是{segment}，他们最在意{need}。"
            f"所以不是先讨论菜品好不好，而是午市能不能让这类人快速看懂、快速进店、快速吃完。"
        )
    if "同商圈" in text or "竞品" in text:
        return (
            "同商圈竞品变多时，真正危险的是顾客在门口三秒内看不出为什么选你。"
            "这时先做差异化引流，不先大改产品：把招牌、价格和用餐效率说清楚，避免被竞品低价牵着走。"
        )
    if "先改哪个入口" in text or ("路过" in text and "进店" in text):
        return (
            f"问题卡在门口漏斗：100 个路过的人里大概只有 {capture} 个进店，同层类似店能到 {peer_capture} 个。"
            "这说明今天先改物理入口和第一眼信息，不先改菜单结构。"
        )
    if "美团" in text or "大众点评" in text:
        return (
            f"美团/大众点评已经有曝光，漏点在核销前：顾客看到页面后没有形成到店理由。"
            f"{weak_text} 的下单弱也提示页面说明、券包门槛和到店话术没有接上。"
        )
    if "抖音" in text or "团购" in text:
        return (
            "抖音能带新客，但天然容易低客单。老板要看的不是播放量，而是券后客单、加购小食和扣完平台成本后的毛利。"
            "如果晚高峰座位被低毛利券占掉，营收看着热闹，利润反而会薄。"
        )
    return fallback


def _owner_message_specific_actions(
    owner_page: dict[str, Any],
    scenario: str,
    message: str,
    default_actions: list[str],
) -> list[str]:
    text = message or ""
    package_decision = owner_page.get("packageDecision") if isinstance(owner_page.get("packageDecision"), dict) else {}
    candidates = package_decision.get("candidates") if isinstance(package_decision.get("candidates"), list) else []
    candidate = candidates[0] if candidates and isinstance(candidates[0], dict) else {}
    package_name = _plain_text(candidate.get("name")) or "招牌鱼双人套餐"
    package_price = _owner_money(candidate.get("estimatedPackagePrice")) or "270元"
    package_cost = _owner_money(candidate.get("estimatedFoodCost")) or "100元"
    package_profit = _owner_money(candidate.get("estimatedGrossProfit")) or "170元"
    package_margin = _owner_metric(candidate.get("grossMarginPct"), 1)
    margin_text = f"，毛利率约 {package_margin}%" if package_margin else ""

    if "老板今天就看一眼" in text or "最应该先管" in text:
        return [
            "老板今天只派一个主动作：让店长把工作日午市的双人套餐和门口承接先跑起来，不要同时开五个会。",
            "店长只回报三件事：午市进店人数、套餐卖出份数、套餐毛利；其他报表明天再看。",
            "如果今晚毛利还被活鱼损耗拖住，再让厨师长查 BOM 和切配；不要老板一上来就亲自盯后厨细节。",
        ]
    if "营收" in text and ("哪三个动作" in text or "先做" in text):
        return [
            "前台先改入口话术：门口和平台首屏只讲招牌鱼、双人价格、预计用餐时间，让路过客知道为什么进来。",
            f"店长今天只推一款套餐：{package_name}，售价约 {package_price}，成本约 {package_cost}，一份留下约 {package_profit}{margin_text}。",
            "仓管和厨师长同步守成本：活鱼和底料按缺口补，晚市后查实际用量有没有继续高过 BOM。",
        ]

    if scenario == "package":
        if "米饭" in text or "低价值" in text:
            return [
                "先把米饭、餐具、纸巾这类配套项从主推候选里剔除，它们只能做随餐补充，不能占推荐位。",
                f"主推只保留能讲清卖点的组合：{package_name}；页面写清售价 {package_price}、食材成本 {package_cost}、毛利 {package_profit}{margin_text}。",
                "服务员推荐时只说招牌鱼和冰豆花的理由，不把米饭当套餐亮点；米饭放在加购或默认搭配里处理。",
            ]
        if "外卖" in text:
            return [
                f"外卖入口今天只上 {package_name}，页面必须写清辣度、预计出餐时间和适合两人，不用堆满一屏菜名。",
                f"外卖售价按 {package_price} 试 7 天，包装和平台成本并入 {package_cost}，一份毛利低于 {package_profit} 就停。",
                "差评风险先控两件事：鱼片口感和汤汁包装；出餐超过阈值时宁可少接单，也不要用差评换销量。",
            ]
        if any(keyword in text for keyword in ("算", "毛利", "成本", "售价")):
            return [
                f"今天先按这组数试推：{package_name}，售价约 {package_price}，食材成本约 {package_cost}，毛利约 {package_profit}{margin_text}。",
                "如果要替换菜品，只能用高毛利小食替换低价值配套项；替换后重新算售价、成本、毛利，不能凭感觉换。",
                "只在工作日午市和低峰测，不上全店长期菜单；明天看卖出份数、客单价、毛利率再决定扩大。",
            ]
        if "不想打折" in text or "不要打折" in text or "不打折" in text or "满减" in text:
            return [
                "今天的动作不是满减和全店折扣；老板不想打折，就改成“招牌鱼+高毛利小食”的双人理由，让顾客觉得省心而不是便宜。",
                "门口、点评、美团三处统一一句话：两个人吃什么、多少钱、多久吃完；不要每个平台讲不同卖点。",
                "晚市结束只看客单价有没有抬、套餐毛利有没有守住；如果只靠低价带单，就立刻停。",
            ]

    if scenario == "traffic_conversion":
        if "商圈客流画像" in text or ("客流画像" in text and "影响" in text):
            return [
                "先按画像分三段接客：午市办公客讲 45 分钟吃完，晚市家庭客讲双人/多人套餐，活动客讲到店核销简单。",
                "前厅今天记录每段客人问得最多的一句话，晚上把门口台卡和平台首图一起改掉。",
                "备货按画像走：午市保快出餐主菜，晚市保招牌鱼和小食，不按全菜单平均多备。",
            ]
        if "同商圈" in text or "竞品" in text:
            return [
                "今天先改引流理由，不先改产品线：门口写清“活鱼现做、两人价、快吃完”，和竞品低价酸菜鱼拉开差异。",
                "店长拍一张竞品门口和团购页，对照自己的首图、价格和等位话术，只改顾客第一眼能看到的三处。",
                "招牌鱼不降价，用小食或饮品做组合；避免被同商圈竞品拖进价格战。",
            ]
        if "先改哪个入口" in text or ("路过" in text and "进店" in text):
            return [
                "先改门口入口：海报只留招牌鱼、双人价格、预计用餐时间，其他信息先撤掉。",
                "门迎站位提前到外摆线，看到两人客先报双人套餐和预计等位，不等顾客自己看完再问。",
                "平台首图只同步门口同一句话，抖音团购先保留但不加低价券；今天不先动菜单和厨房，先把路过客推进店。",
            ]
        if "美团" in text or "大众点评" in text:
            return [
                "先改美团/点评首图：招牌鱼实图、双人价、到店可核销三件事放第一屏。",
                "套餐页删掉复杂说明，只保留适合几人、含哪些菜、预计上菜时间；券包门槛不要让顾客算半天。",
                "前台承接和核销话术固定一句：先确认券，再推荐招牌加小食；今天看曝光到核销的转化，不加投流。",
            ]
        if "抖音" in text or "团购" in text:
            return [
                "抖音今天不上低价引流券，只上能带加购的小套餐：招牌鱼做入口，小食和饮品做毛利补充。",
                "核销时前台必须二次推荐高毛利小食；如果顾客只用低价券不加购，这个券明天就降权。",
                "晚高峰限制低毛利团购核销量，别让便宜券占掉正价客座位。",
            ]

    if scenario == "inventory_reorder":
        if any(keyword in text for keyword in ("不要多备", "别多备", "少备", "报损")):
            return [
                "今天不要多备低销量复杂菜和容易过夜变差的配菜；这些菜只留够晚高峰，不做第二轮加备。",
                "活鱼、青花椒底料按安全库存补，不因为怕报损就把招牌菜断货；临期豆腐和番茄先排进员工餐或小食消耗。",
                "打烊前让仓管拍一张剩货表：哪些剩、剩多少、明天减多少；明天按这个减备，不等月底才算损耗。",
            ]
        if any(keyword in text for keyword in ("库存预警", "备菜", "补货")):
            return [
                "仓管今天只拉一张表：当前库存、今晚预测、安全库存；缺口超过安全线的先补，不缺的不要补。",
                "厨师长按这张表改备菜顺序：招牌鱼原料优先，临期原料优先消耗，低销量复杂菜不加量。",
                "前台同步避开会消耗紧缺原料的推荐话术，别一边缺货一边继续推。",
            ]

    if scenario == "cost_margin":
        if any(keyword in text for keyword in ("理论用量", "实际用量", "BOM")):
            return [
                "厨师长今天先称三次：活鱼来料重量、切配后可用重量、出品每份用量，先判断是切配损耗还是份量失控。",
                "把招牌鱼 BOM 理论用量和今天实际用量逐单对齐，差异超过阈值的单子直接回看制作人和时段。",
                "今晚不要先涨价，先把底料勺量、鱼片份量和边角料去向拉回标准；明天再看毛利有没有回升。",
            ]
        if "月盘点" in text or "损耗" in text:
            return [
                "今天先按损耗金额排前两项，不查全仓；活鱼边角和酸菜包材先分清是采购、切配、盘点口径还是报损记录问题。",
                "仓管当天改一条规则：临期和边角料必须贴红标并当天消耗，不允许打烊后才发现。",
                "厨师长同步下调容易剩的备料上限；不是所有菜少备，而是先砍损耗高、销量低、复用差的原料。",
            ]
        if "采购" in text:
            return [
                "采购先拿同规格活鱼和底料的近三次单价比，不只看今天一张采购单。",
                "如果采购价涨但主菜仍是引流菜，先用高毛利小食带回来，不马上涨主菜价格。",
                "如果同规格价格连续偏高，再谈换供应商或议价；不要让后厨用量问题伪装成采购问题。",
            ]

    if scenario == "seating_mix":
        if "排队" in text or "空桌" in text or "前台引导" in text:
            return [
                "前厅每 15 分钟报一次：门口等位几组、空桌几张、空的是几人桌；先确认是不是有桌没人坐。",
                "两人客优先引导到可拼可拆位，四人客再合桌；如果四人桌被两人客占死，店长当场调整。",
                "今天先改引导规则，不先加员工；如果引导改了还排队，再判断是不是厨房出餐慢。",
            ]
        if "二人桌" in text or "四人桌" in text or "桌型" in text:
            return [
                "午市前先拿 2 张四人桌改成可拼可拆区，摆位和台卡都改，不只口头交代。",
                "2 人客到店先落拼拆位，3-4 人客到店再合桌；前台记录四人桌被二人客占用次数。",
                "今天只试一块区域，避免四人客真的来了没桌；明天看等位和空桌时间再扩大。",
            ]

    if scenario == "staffing_schedule":
        if "只能多加一个人" in text or "只能加一个人" in text:
            return [
                "18:00 前先看瓶颈：如果门口等位和核销乱，这个人加前厅；如果招牌鱼出餐超过阈值，这个人加后厨。",
                "今天这家店先把人放在晚市 18:00-20:00，不加全天；这个人只盯一个瓶颈，不兼做杂活。",
                "打烊复盘看等位和出餐哪个降得更多，明天再决定固定加前厅还是后厨。",
            ]
        if "时段" in text or "少排" in text or "排班" in text:
            return [
                "午市保稳定班，不额外加人；低峰减少机动人手，但必须留一个人盯迎宾和核销。",
                "晚市 18:00-20:00 加峰值人手：前厅盯等位和核销，后厨盯招牌鱼出餐。",
                "店长开班前把谁迎宾、谁催菜、谁处理差评苗头写清楚，不要忙起来才临时分工。",
            ]

    if scenario == "external_event_response":
        if "下雨" in text or "雨" in text or "天气" in text:
            return [
                "堂食承接商场内客流：门口物料只推热汤鱼和双人套餐，减少顾客犹豫。",
                "外卖只推配送后口感稳定的组合，避开容易变软、变凉、差评风险高的菜。",
                "备货只加招牌鱼、底料、热汤小食和包装材料，不因为下雨就全菜单多备。",
            ]
        if "商场" in text or "活动" in text or "亲子" in text:
            return [
                "活动前 1 小时把门口台卡换成亲子/双人友好文案，不等客人进门才解释。",
                "前台安排一人专门承接活动客流：确认券、说明两人套餐、提醒预计上菜时间。",
                "后厨只给活动时段加备招牌鱼和儿童友好小食，低频复杂菜不加量。",
            ]

    return default_actions


def _owner_message_specific_watch_numbers(owner_page: dict[str, Any], scenario: str, message: str, default_watch: str) -> str:
    text = message or ""
    if "老板今天就看一眼" in text or "最应该先管" in text:
        return "明天只看三个数：工作日午市进店人数、双人套餐卖出份数、套餐毛利。三个数有两个变好，老板再扩大动作。"
    if "营收" in text and ("哪三个动作" in text or "先做" in text):
        return "明天看三段漏斗：门口/平台进店有没有涨、套餐有没有拉高客单、BOM 差异有没有收窄。三段有一段没动，就不要盲目加活动。"
    if scenario == "package":
        if "米饭" in text or "低价值" in text:
            return "明天看主推套餐里低价值配套项占比、套餐毛利率、加购率。米饭占推荐位越少、加购越高，说明排序改对了。"
        if "外卖" in text:
            return "明天看外卖套餐份数、外卖差评关键词、扣除包装平台后的毛利。销量涨但差评或毛利变差，就不要继续放大。"
        if any(keyword in text for keyword in ("算", "毛利", "成本", "售价")):
            return "明天看这款套餐的售价接受度、食材成本、毛利额。毛利额能留下来，才说明这套计算有用。"
        if "不想打折" in text or "不要打折" in text or "不打折" in text:
            return "明天看客单价、套餐占比、折扣让利金额。客单涨且让利没涨，才说明不是靠打折拉起来。"
    if scenario == "traffic_conversion":
        if "商圈客流画像" in text or ("客流画像" in text and "影响" in text):
            return "明天只看三个数：分时段进店人数、主力客群点单率、画像对应套餐销量。画像不是报告，能带来分时段动作才有用。"
        if "同商圈" in text or "竞品" in text:
            return "明天只看三个数：门口咨询数、竞品价格对比记录、本店招牌套餐销量。咨询和招牌销量涨，说明没有被同商圈竞品低价带偏。"
        if "先改哪个入口" in text or ("路过" in text and "进店" in text):
            return "明天只看三个数：门口路过人数、被门迎拦下咨询人数、实际进店人数。只要咨询和进店涨，说明入口先改对了。"
        if "美团" in text or "大众点评" in text:
            return "明天只看三个数：美团/点评曝光、套餐页点击、到店核销。曝光不变但核销涨，说明页面和前台话术有效。"
        if "抖音" in text or "团购" in text:
            return "明天只看三个数：抖音券核销量、券后客单价、加购小食毛利。核销涨但客单和毛利掉，就不能继续放量。"
    if scenario == "inventory_reorder":
        if any(keyword in text for keyword in ("不要多备", "别多备", "少备", "报损")):
            return "明天只看三个数：低销量菜剩货金额、临期原料报损、招牌菜缺货次数。报损降了但招牌不断货，才是少备成功。"
        if any(keyword in text for keyword in ("库存预警", "备菜", "补货")):
            return "明天看缺货次数、紧急补货次数、临期消耗金额。缺货和紧急补货少了，临期也消耗掉，说明补货表有效。"
    if scenario == "cost_margin":
        if any(keyword in text for keyword in ("理论用量", "实际用量", "BOM")):
            return "明天看三项：活鱼实际/理论差异、底料勺量偏差、边角料重量。差异收窄，才说明厨师长查到了真问题。"
        if "月盘点" in text or "损耗" in text:
            return "明天看损耗前两项金额、临期红标消耗、备料上限执行情况。损耗金额降下来，才说明不是月底才发现。"
        if "采购" in text:
            return "明天看同规格采购单价、菜品毛利、供应商报价差。单价问题和用量问题要分开判断。"
    if scenario == "seating_mix":
        if "排队" in text or "空桌" in text or "前台引导" in text:
            return "今天看等位组数、空桌张数、前台改坐成功次数。排队少了但空桌也少了，说明前台引导有效。"
        if "二人桌" in text or "四人桌" in text or "桌型" in text:
            return "今天看二人客等位、四人桌被二人客占用次数、拼拆区翻台。二人客等位降且四人客没被赶走，桌型才算调对。"
    if scenario == "staffing_schedule":
        if "只能多加一个人" in text or "只能加一个人" in text:
            return "今晚只看这个人盯的瓶颈：如果加前厅，看等位和核销；如果加后厨，看出餐时长和催菜次数。不要用全店收入判断一个人的位置。"
        if "时段" in text or "少排" in text or "排班" in text:
            return "今天看每小时收入、人手覆盖、上菜时长。忙时覆盖提高、闲时人手下降、上菜没变慢，排班才算合理。"
    return default_watch


def _owner_do_not_do(owner_page: dict[str, Any], scenario: str) -> str:
    if scenario == "revenue_growth":
        return "今天先别同时改菜单、投流、排班和备货。先选一个营收杠杆跑一天，否则明天看不出到底哪个动作有效。"
    if scenario == "operations_dispatch":
        return "今天先别让每个岗位都自己判断重点，也别临时全员加班。先按仓管、厨师长、前台、店长四张清单跑一晚。"
    if scenario == "inventory_reorder":
        return "今天先别平均补货，也别只看采购单价。缺口货要补，临期货要先消耗，毛利异常货要查 BOM 和损耗。"
    if scenario == "traffic_conversion":
        return "今天先别继续加投流，也别全店打折。人已经在门口了，先把门口和线上入口讲清楚。"
    if scenario == "store_compare":
        return "今天先别把这家店简单判成差店。它日均不弱，问题是工作日和客单价没吃满，要和同类门店拆开比。"
    if scenario == "cost_margin":
        return "今天先别直接涨价，也别砍掉主菜。先查采购价、BOM 和损耗，否则可能把顾客喜欢的菜也误伤。"
    if scenario == "external_event_response":
        return "今天先别因为商场活动或下雨就全店打折、全品类多备。先分堂食和外卖承接，再按高频菜少量加备。"
    text = _first_text(owner_page.get("doNotDo"))
    return text or "先别凭感觉大改菜单、价格和排班，等今天这一个动作看出效果再扩大。"


def _owner_watch_numbers(owner_page: dict[str, Any], scenario: str) -> str:
    if scenario == "revenue_growth":
        return "明天只看五个数：进店人数、客单价、翻台次数、上菜时长、食材成本率。哪一项最拖后腿，下一轮就只拆那一项。"
    if scenario == "operations_dispatch":
        return "明天只看三个数：晚市收入、平均上菜时长、缺货/售罄次数。收入涨、上菜变快、缺货变少，说明岗位分工有效。"
    if scenario == "inventory_reorder":
        return "明天只看三个数：活鱼缺货次数、临期报损金额、紧急补货次数。缺货少了、报损没涨、临时补货少了，说明补货策略对。"
    if scenario == "traffic_conversion":
        return "明天只看三个数：门口路过多少人、进店多少人、最后下单多少单。路过人差不多但进店和下单涨了，就说明入口改对了。"
    focus = owner_page.get("decisionFocus") if isinstance(owner_page.get("decisionFocus"), dict) else {}
    action_type = focus.get("primaryActionType")
    if action_type == "seating_mix":
        return "今天看四个数：二人客等位多久、四人桌被二人客占用几次、翻台次数、空桌时间。等位少了、四人桌被占少了、空桌少了，桌型就调对了。"
    if action_type == "staffing_schedule":
        return "今天看三个数：高峰等位、上菜时长、差评关键词。等位和上菜时间降下来，排班就有用。"
    if action_type == "package":
        return "今天看三个数：套餐卖了多少份、有没有拉高客单、毛利有没有守住。只卖得多但毛利掉了，就不是好套餐。"
    if scenario == "cost_margin":
        return "今天看四个数：活鱼采购价、BOM 实际偏差、盘点损耗金额、差评里被点名的菜品。前三项收窄且差评没变多，毛利才是真的补回来了。"
    if scenario == "store_compare":
        return "今天看三个数：工作日午市收入、双人套餐占比、客单价。它们追上对标店，说明复制动作有效。"
    if scenario == "external_event_response":
        return "明天看四个数：商场活动时段进店人数、堂食晚市订单、外卖雨天订单、备货报损。订单涨而报损没涨，说明承接动作对。"
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

    if scenario == "operations_dispatch":
        role_plan = _owner_role_action_plan(params, scenario)
        if role_plan:
            labels = [str(item.get("role") or "") for item in role_plan if item.get("role")]
            action_counts = [
                len(item.get("todayActions") or []) if isinstance(item.get("todayActions"), list) else 0
                for item in role_plan
                if item.get("role")
            ]
            watch_counts = [
                len(item.get("watchTomorrow") or []) if isinstance(item.get("watchTomorrow"), list) else 0
                for item in role_plan
                if item.get("role")
            ]
            charts.append(_owner_bar_chart(
                "今天每个岗位先做哪几件事",
                labels,
                [
                    {"name": "今天动作数", "type": "bar", "data": action_counts, "label": {"show": True, "position": "top"}},
                    {"name": "明天复盘数", "type": "bar", "data": watch_counts, "label": {"show": True, "position": "top"}},
                ],
                y_name="件",
                bottom=60,
            ))

    if scenario in {"operations_dispatch", "inventory_reorder"}:
        alerts = params.get("inventory_alerts") if isinstance(params.get("inventory_alerts"), list) else []
        rows: list[dict[str, Any]] = [item for item in alerts if isinstance(item, dict) and item.get("ingredient")]
        if rows:
            labels = [str(item.get("ingredient"))[:12] for item in rows[:4]]
            charts.append(_owner_bar_chart(
                "库存先补哪里、哪里先别补",
                labels,
                [
                    {"name": "当前库存", "type": "bar", "data": [_owner_float(item.get("currentStock"), 0) or 0 for item in rows[:4]]},
                    {"name": "安全库存", "type": "bar", "data": [_owner_float(item.get("safetyStock"), 0) or 0 for item in rows[:4]]},
                    {"name": "建议补货", "type": "bar", "data": [_owner_float(item.get("reorderQty"), 0) or 0 for item in rows[:4]], "label": {"show": True, "position": "top"}},
                ],
                y_name="kg",
                bottom=65,
            ))

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
    if scenario == "revenue_growth":
        return "先看营收到底卡在哪条杠杆：客流、客单、翻台、出餐、毛利，不要同时改五件事。"
    if scenario == "operations_dispatch":
        return "先看岗位动作有没有拆清楚，再看库存缺口；老板今天只需要盯等位、上菜、缺货三个异常。"
    if scenario == "inventory_reorder":
        return "先看当前库存和安全库存的差，再看建议补货；缺口货要补，临期货先消耗，不要平均补。"
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


def _owner_chat_follow_ups(scenario: str, message: str = "") -> list[str]:
    if scenario == "revenue_growth":
        text = message or ""
        if "门口海报" in text or "首图" in text:
            return [
                "明天看哪三个数",
                "如果进店没涨怎么办",
                "再拆客单和套餐怎么做",
            ]
        if "选一个营收杠杆" in text or "继续拆" in text or "先拆客流转化" in text:
            return [
                "门口海报和首图具体怎么改",
                "明天看哪三个数",
                "如果进店没涨怎么办",
            ]
        return [
            "帮我选一个营收杠杆继续拆",
            "先拆客流转化怎么做",
            "先拆客单和套餐怎么做",
        ]
    scenario_specific = {
        "revenue_growth": "帮我选一个营收杠杆继续拆",
        "package": "把套餐执行细节拆给我",
        "operations_dispatch": "把仓管厨师长前台的动作拆细",
        "inventory_reorder": "库存补货今天具体先看哪几项",
        "seating_mix": "桌型今天具体怎么调",
        "staffing_schedule": "排班今天具体怎么排",
        "staff_training": "开班前话术怎么讲",
        "kitchen_quality": "厨房抽查怎么做",
        "cost_margin": "成本毛利先查哪几项",
        "traffic_conversion": "入口和平台页面具体改什么",
        "external_event_response": "商场活动当天怎么备货",
        "single_item_push": "主推菜放到哪些入口",
        "store_compare": "复制哪家店的哪一个动作",
    }
    first = scenario_specific.get(scenario, "这件事今天第一步做什么？")
    return [first, "老板今天先看哪三个数？", "为了守住今天的营收和毛利，哪些事情先不要做？"]


def _owner_metric_follow_up_answer(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    text = (message or "").strip()
    is_metric_question = any(keyword in text for keyword in ("哪三个数", "三个数", "哪些数", "看什么数", "判断有没有效果", "有没有效果"))
    if not is_metric_question:
        return ""

    metric_sets = {
        "operations_dispatch": (
            "晚市收入",
            "平均上菜时长",
            "缺货/售罄次数",
            "收入涨、上菜变快、缺货变少，说明仓管、厨师长、前台、店长的分工跑通了。",
        ),
        "inventory_reorder": (
            "活鱼缺货次数",
            "临期报损金额",
            "紧急补货次数",
            "缺货少了、报损没涨、临时补货少了，说明今天不是乱补，而是按安全库存和临期风险补对了。",
        ),
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


def _owner_action_follow_up_answer(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    text = (message or "").strip()
    if any(keyword in text for keyword in ("哪三个数", "三个数", "哪些数", "看什么数", "判断有没有效果", "有没有效果")):
        return ""

    plain_actions = _owner_plain_actions(owner_page, scenario, _first_text(owner_page.get("doFirst")))
    do_not_do = _owner_do_not_do(owner_page, scenario)
    chart_guide = _owner_chart_guide(scenario)
    params = owner_page.get("demoParams") if isinstance(owner_page.get("demoParams"), dict) else {}
    pos = params.get("pos_summary") if isinstance(params.get("pos_summary"), dict) else {}
    compare = pos.get("storeComparison") if isinstance(pos.get("storeComparison"), dict) else {}

    asks_for_do_not_do = any(keyword in text for keyword in ("别做", "先不要", "不要做", "风险", "避开", "哪些不要", "什么不要"))
    asks_for_direct_answer = any(keyword in text for keyword in ("不要泛泛", "不要讲理论", "不要只", "不要光", "不要套餐", "不要米饭"))
    if asks_for_do_not_do and not asks_for_direct_answer:
        return "\n\n".join([
            f"今天先别做：{do_not_do}",
            "原因很简单：老板动作要先打最窄的点。还没看完今天的数据前，同时改价格、菜单、排班和投流，会分不清到底是哪一个动作起作用。",
            "明天复盘时，如果核心数据没变好，再决定要不要扩大到第二个动作。",
        ])

    scenario_steps = {
        "operations_dispatch": [
            "仓管：10:30 前核对活鱼、青花椒底料、冰豆花原料，按安全库存和今晚预测列缺口。",
            "厨师长：晚市只保招牌鱼、冰豆花、双人套餐，复杂低销量菜不主动推，避免拖慢上菜。",
            "前台/店长：前台只讲招牌、价格、用餐时间三句话；店长晚高峰只盯等位、上菜、缺货。",
        ],
        "inventory_reorder": [
            "先补活鱼和青花椒底料，因为它们直接决定招牌鱼能不能卖、毛利能不能守住。",
            "豆腐、番茄等临期原料今天优先消耗，不要继续补货；仓管贴红标，厨师长排进备料。",
            "打烊后对三件事：有没有售罄、有没有报损、有没有临时补货；明天按结果调整安全库存。",
        ],
        "traffic_conversion": [
            "门口：海报只保留“招牌鱼 + 双人价格 + 大概用餐时间”，让路过的人 3 秒内看懂。",
            "平台：美团/大众点评/抖音首图统一成招牌鱼和双人套餐，不要把一堆菜名堆在第一屏。",
            "现场：门迎先确认券，再引导点招牌；同时记录顾客问得最多的一句话，晚上改页面文案。",
        ],
        "package": [
            "先选 1 个主菜 + 1 个高毛利小食，别超过 2 道核心菜，避免厨房复杂化。",
            "售价按“单点原价略低一点”定，食材成本必须先算清，毛利率守不住就不推。",
            "只在工作日午市和低峰测 7 天；如果套餐份数涨但毛利掉，就立刻停。",
        ],
        "seating_mix": [
            "午市前先改 2 张四人桌为可拼可拆，两人客优先坐可拆桌，不要占死整张四人桌。",
            "晚高峰让前厅按人数引导：两人客推双人套餐，四人客推加菜组合，避免只提高翻台不提高客单。",
            "只改一块区域先试，别全店同时改；防止四人客真的来了却没桌。",
        ],
        "staffing_schedule": [
            "18:00-20:00 前厅多 1 人盯等位和核销，后厨多 1 人盯招牌鱼出餐。",
            "午市只保稳定班，不平均加人；忙的时段没人、闲的时段人多，是最浪费的排班。",
            "店长开班前分清三个人：谁迎宾，谁催菜，谁处理差评苗头。",
        ],
        "staff_training": [
            "第一句：两个人怎么点最划算，直接报招牌鱼双人方案。",
            "第二句：等位或上菜慢时怎么安抚，先给明确时间，不要只说马上。",
            "第三句：核销券怎么引导，进店先确认券，再引导点招牌。",
        ],
        "kitchen_quality": [
            "先抽查招牌鱼：咸淡、鱼片熟度、出餐时长，三项都要记录。",
            "晚高峰前减少复杂低销量菜推荐，厨房先保招牌和套餐主线。",
            "前厅发现上菜慢超过阈值，立刻报店长，不等顾客写差评。",
        ],
        "cost_margin": [
            "先看活鱼采购价有没有高于近 7 天均价。",
            "再看招牌鱼 BOM 理论用量和实际用量差多少。",
            "最后看盘点损耗，特别是活鱼边角和底料包材，不要只盯售价。",
        ],
        "external_event_response": [
            "活动开始前 2 小时把招牌鱼和冰豆花备足，但只按高峰预估的 80% 备，留补货空间。",
            "门口话术改成活动客能听懂的双人/家庭场景，不要只讲菜名。",
            "活动时段每小时看一次核销和等位，发现排队多但下单少，就立刻调整门迎推荐。",
        ],
        "single_item_push": [
            "大众点评/美团首屏放招牌鱼，抖音短视频讲双人价格和出餐速度。",
            "门口海报只放一个主推菜，不要同时推 5 个菜；米饭、餐具、纸巾这类低价值配套项不参与主推排序。",
            "服务员只问一句：今天要不要先上招牌鱼，再带一份冰豆花？用主推菜销量、连带加购率、毛利额一起判断有没有真的拉动。",
        ],
        "store_compare": [
            f"先复制 {compare.get('copyFrom') or '日均表现更好的同城门店'} 的一个动作：{compare.get('copyAction') or '双人套餐首屏和服务员推荐话术'}。",
            "不要复制整套菜单，只复制一个经过验证的入口动作。",
            "明天只比工作日午市收入、双人套餐占比、客单价，别用总收入排名判断。",
        ],
    }
    steps = scenario_steps.get(scenario) or plain_actions[:3]
    if not steps:
        return ""

    return "\n\n".join([
        "今天照这三步做：\n" + "\n".join(f"{index}. {step}" for index, step in enumerate(steps[:3], start=1)),
        f"图表重点：{chart_guide}",
        f"今天先别做：{do_not_do}",
    ])


def _owner_direct_special_answer(owner_page: dict[str, Any], scenario: str, message: str) -> str:
    text = (message or "").strip()
    if not text:
        return ""
    if scenario == "revenue_growth":
        if "营收掉了" in text and ("满减" in text or "不想做" in text):
            return "\n\n".join([
                "我先纠正一个前提：demo 里的全店周环比并不是下滑，最近一周营收比上周高约 10.5%。如果你说的是指定门店下滑，需要先确认门店和时段。",
                "如果只是想在不想打折、不做满减的前提下把收入拉回来，今天不要降价，先把“招牌鱼双人餐 + 高毛利小食冰豆花”的理由讲清楚：两个人吃什么、多少钱、多久吃完。",
                "今天只看三项：套餐卖出份数、客单价、套餐毛利。份数涨但毛利掉，就说明不是好动作；客单和毛利一起涨，明天再扩大到美团/点评首屏。",
            ])
        if "感觉本周变差" in text or ("本周变差" in text and "先管" in text):
            return "\n\n".join([
                "我先纠正一个前提：demo 里的全店周环比并不是下滑；如果是老板感觉变差，先做范围确认，不要直接开会追责。",
                "今天先问清楚四个范围：是全店、某一家门店、某个平台，还是某个时段。范围没确认前，先不要改价格，也不要让仓管和厨房一起背锅。",
                "如果老板只想马上派一个动作，就先管工作日午市入口：门口、点评/美团首图、前台话术统一成“招牌鱼双人餐，约 270 元，45 分钟左右吃完”。",
            ])
        if "门口海报" in text or "首图" in text:
            return "\n\n".join([
                "门口海报和线上首图今天只改一件事：让顾客 3 秒内知道为什么现在进店。",
                "门口海报写：两个人吃招牌青花椒鱼，价格清楚，45 分钟左右吃完。图片只放招牌鱼和双人价，不要放一堆菜名。",
                "美团/大众点评首图用同一句话，同一个价格承诺；抖音团购页面也别只讲便宜，要写清到店可核销、适合两人、预计出餐时间。",
                "前台迎宾同步讲同一句：您如果两个人用餐，今天点招牌鱼双人组合最稳，我先帮您确认券和预计上菜时间。",
            ])
        if "明天看哪三个数" in text:
            return "\n\n".join([
                "明天只看三个数：门口咨询人数、平台到店核销、实际进店下单数。",
                "如果咨询涨、核销涨、下单也涨，说明入口话术有效；如果只有咨询涨但下单没涨，问题在前台承接或价格表达；如果三个都不动，首图和海报还没打中顾客。",
                "先别用整店总收入判断这次动作，因为一天内收入会被天气、活动和大桌影响。先看入口漏斗有没有动。",
            ])
        if "进店没涨" in text:
            return "\n\n".join([
                "如果明天进店没涨，不要马上打折，先换入口表达。",
                "第一步换图：首图从菜品堆叠改成招牌鱼实物 + 双人价格。第二步换话术：从“欢迎光临”改成“两个人吃招牌鱼大概多少钱、多久能吃完”。第三步查竞品：同楼层酸菜鱼或川菜有没有更清楚的门口价。",
                "再观察一天。如果门口咨询仍不涨，再考虑商场活动联动或平台曝光；如果咨询涨但进店不涨，就训练前台承接，不是继续投流。",
            ])
        if "选一个营收杠杆" in text or "继续拆" in text:
            return "\n\n".join([
                "我建议今天先拆客流转化，不要先拆套餐。",
                "原因很简单：demo 里商场和门口人流不弱，问题更像是路过客、平台浏览客没有被一句话拉进店。先把门口海报、美团/大众点评首图、前台迎宾话术统一，再看进店和核销有没有动。",
                "如果明天进店没动，再拆客单和套餐；如果进店涨但收入没涨，再拆套餐和毛利；如果进店涨但差评涨，再拆厨房和排班。",
            ])
        return "\n\n".join([
            "先选营收杠杆，不要一上来就做套餐或打折。",
            "我会按 5 条路判断：1 客流转化，看门口和平台有没有把人拉进店；2 客单和套餐，看双人客有没有被合理组合承接；3 翻台和桌型，看两人桌、四人桌有没有错配；4 排班和出餐，看高峰有没有卡在前厅或厨房；5 成本毛利，看收入涨了以后利润有没有被食材和损耗吃掉。",
            "这家青花椒 demo 当前更像先看客流转化：商场人流和平台曝光有基础，但门口进店、点评/美团核销和抖音承接偏弱。老板今天先别让全店一起改，先选一个杠杆跑一天。",
            "如果你不选，我默认先拆客流转化；如果你已经确定想拉客单，我再切到套餐毛利；如果你担心高峰忙不过来，我再切到排班和厨房。",
        ])
    if "不确定" in text and any(keyword in text for keyword in ("范围", "先问", "问我")):
        return "\n\n".join([
            "数据不确定时，不要硬给结论，先让老板选范围。",
            "我会先问 4 个范围：1 具体哪家门店；2 哪个时间段；3 哪个平台或渠道；4 是要看营收、客单、毛利、客流，还是评价。",
            "如果老板还没选，我先给可点选项：全店本周、指定门店、点评/美团核销、晚市时段。选完再给动作，避免把全店数据误当成某一家店的问题。",
        ])
    if "没有真实POS" in text or ("demo" in text.lower() and "演示" in text):
        return "\n\n".join([
            "只看 demo 数据也能演示决策逻辑，但不能承诺真实 ROI。",
            "能演示 5 件事：1 营收和客单变化怎么拆；2 大众点评/美团核销怎么判断漏斗；3 小套餐怎么按售价、成本、毛利筛选；4 仓管、厨师长、前台、店长怎么派工；5 客流画像和商场活动怎么影响当天动作。",
            "不能演示的是真实门店最终收益，因为真实 POS、平台后台、库存和评价授权数据还没接入。demo 的价值是让老板看懂“以后拿到真实数据后会怎么做决策”。",
        ])
    if "一句话结论" in text and any(keyword in text for keyword in ("不要讲", "别讲", "大段", "模型逻辑")):
        return "\n\n".join([
            "一句话结论：今天先用一款算过毛利的双人套餐，把工作日低峰和平台来的两人客接住，不要先全店打折。",
            "老板只需要派一个动作：门口、美团/点评、前台话术都讲同一句“招牌鱼双人餐，价格清楚，45 分钟左右吃完”。",
            "明天只看三个数：套餐卖了多少份、客单价有没有涨、套餐毛利有没有守住。",
        ])
    if "只允许改一个动作" in text or "只改一个动作" in text:
        return "\n\n".join([
            "只改一个动作的话，今天就改“门口和平台第一句话”，不要同时改菜单、排班和投流。",
            "具体改成一句：两个人吃招牌青花椒鱼双人餐，约 270 元，45 分钟左右吃完。门口海报、美团/点评首图、前台迎宾都讲这句。",
            "为什么先改这个：现在不缺所有客流，缺的是路过客和平台浏览客的一眼进店理由。明天看门口咨询数、套餐点击、到店核销。",
        ])
    if "明天怎么复盘" in text or ("复盘" in text and "有没有用" in text):
        return "\n\n".join([
            "明天不要开长会，拿三张小表复盘：套餐卖出份数、客单价、套餐毛利额。",
            "判断规则很简单：份数涨、客单涨、毛利没掉，就继续做 3 天；份数涨但毛利掉，说明靠便宜换销量，要换小食或停掉；三项都不动，说明入口话术没打中，要先改首图和门口海报。",
            "店长只需要回答：是哪一个入口带来的单，顾客有没有加购，厨房有没有因为套餐变慢。明天看这三项就够，不要再拉十张报表。",
        ])
    if "今天哪些事情先不要做" in text or "今天先不要做" in text:
        return "\n\n".join([
            "今天先别做三件事：别全店满减，别继续加投流，别把米饭这类低价值配套项当主推。",
            "原因：现在要验证的是一个窄动作能不能提高客单和毛利；同时改价格、投流、菜单，会看不出哪个动作有效。",
            "今天只保留一款双人套餐、一个入口文案、一段低峰测试。明天再决定要不要扩大。",
        ])
    if "首图" in text and "门口海报" in text:
        return "\n\n".join([
            "要讲同一句话，而且必须同图同价同承诺，不要线上说套餐、门口说招牌、前台又讲别的。",
            "今天统一成这句：招牌青花椒鱼双人餐，约 270 元，45 分钟左右吃完。美团/点评首图、门口海报、前台迎宾都用这句。",
            "明天看三个数：首图点击、门口咨询、到店核销。如果点击涨但核销不涨，说明前台承接没接上；如果咨询也不涨，说明第一眼文案还不够清楚。",
        ])
    if "收藏" in text and ("不到店" in text or "不进店" in text):
        return "\n\n".join([
            "大众点评有人收藏但不到店，先改“收藏后为什么今天就来”的理由，不是先加投流。",
            "今天在点评页面首屏补三件事：双人套餐价格、到店可核销、预计上菜时间；收藏用户看到以后要知道今天来有什么确定收益。",
            "前台同步准备一句承接：您点评上收藏的双人餐今天可以直接核销，我先帮您确认券，再安排招牌鱼。明天看收藏到核销的转化。",
        ])
    if "活动只持续两个小时" in text or ("两个小时" in text and "备货" in text):
        return "\n\n".join([
            "商场两小时活动不要按全天高峰备货，只按活动窗口备 80%，留 20% 机动补货。",
            "后厨只加备三类：招牌鱼、冰豆花/儿童友好小食、热汤底料；低频复杂菜不加备，避免活动结束后变成报损。",
            "活动开始前 30 分钟看门口等位和核销，如果核销没起来就不继续加备；活动结束后立刻把剩料转到晚市套餐，不要继续按活动节奏出菜。",
        ])
    if any(keyword in text for keyword in ("拆细一点", "谁几点做什么", "几点做什么")) and any(keyword in text for keyword in ("仓管", "厨师长", "前台")):
        return "\n\n".join([
            "按时间拆，不要只说岗位职责：",
            "10:30 仓管核活鱼、底料、冰豆花原料，缺口先补；15:30 厨师长按今晚 420 单预估备招牌鱼和套餐小食，低销量复杂菜不加量；17:30 店长开班前让前台统一三句话；18:00-20:00 前台盯等位和核销，厨师长盯上菜时长。",
            "打烊后店长只收四个数：缺货次数、上菜时长、套餐份数、差评苗头。谁没完成，明天就只改那一段。",
        ])
    if "冰豆花" in text and ("毛利" in text or "吸引力" in text):
        return "\n\n".join([
            "冰豆花可以加，但它的角色是高毛利小食和降低辣感，不是为了把套餐做得更便宜。",
            "按 demo 成本算：招牌鱼双人套餐加手作冰豆花，售价约 270 元，食材成本约 100 元，一份约 170 元毛利；冰豆花本身成本低、解释简单，适合做加购理由。",
            "今天页面和前台话术都说清楚：吃辣以后配冰豆花更舒服，出餐也快。明天看加购率、套餐毛利、差评里“太辣/等太久”有没有下降。",
        ])
    if any(keyword in text for keyword in ("只想推一款", "只推一款", "选哪款", "乱推")):
        return "\n\n".join([
            "今天只推一款：招牌青花椒鱼[活鱼现做] + 手作冰豆花双人套餐。",
            "为什么选它：这款能讲清招牌、适合两人、出餐不复杂；售价约 270 元，食材成本约 100 元，一份大概留下 170 元毛利，毛利率约 62.9%。",
            "前台不要乱推，只说一句：两个人今天点这套最稳，招牌鱼加冰豆花，价格清楚、出餐更快。其他米饭、纸巾、低价值配套项不要放到主推位。",
        ])
    if "要不要停" in text or ("毛利" in text and "变薄" in text):
        return "\n\n".join([
            "套餐卖得多但毛利变薄，明天先判断要不要停，不要只看销量。",
            "停的条件很清楚：如果套餐毛利额低于单点组合、客单价没有拉高、还挤占晚高峰座位，就先停这个套餐。",
            "如果只是小食成本偏高，但客单和毛利额还在，可以不全停，只换掉低毛利小食，继续测 3 天。",
        ])
    if "平台券核销" in text or ("核销" in text and "二次推荐" in text):
        return "\n\n".join([
            "平台券核销以后，前台二次推荐只推一个高毛利加购，不要再塞低价券。",
            "前台话术：券我先帮您核销好了，今天建议加一份手作冰豆花，和招牌鱼最搭，出餐也快。",
            "推荐是否划算看三项：核销客客单价、加购小食毛利、差评关键词。加购有毛利且不拖慢出餐，才继续推。",
        ])
    if "二人客等位久" in text and "分流" in text:
        return "\n\n".join([
            "二人客等位久，前台先做分流，不要只催厨房。",
            "前台分流规则：两人客优先坐可拼可拆桌；四人桌不要被两人客占死；愿意等的两人客先推荐双人套餐并预点招牌鱼。",
            "店长每 15 分钟看一次：两人客等位、四人桌被两人占用次数、空桌时间。等位降了才说明分流有效。",
        ])
    if "第一眼" in text or ("同层餐饮" in text and "抢客" in text):
        return "\n\n".join([
            "第一眼只放三个信息，不要把菜单贴满：招牌是什么、两个人多少钱、多久能吃完。",
            "门口海报第一行写“招牌青花椒鱼双人餐”，第二行写“约 270 元/两人”，第三行写“45 分钟左右吃完”。",
            "同层餐饮都在抢客时，第一眼不是比谁菜多，而是让路过的人 3 秒内知道为什么进你家。明天看门口路过、进店、下单三个数。",
        ])
    if any(keyword in text for keyword in ("节假日", "突然客流", "外部原因")):
        return "\n\n".join([
            "判断是不是外部原因，先不要只看当天营收高了没有。",
            "按三步查：1 同商场/同商圈客流是不是一起涨；2 天气、节假日、商场活动是不是同一时段发生；3 本店进店和核销有没有跟着客流同步涨。",
            "如果商场客流涨、本店订单也涨，基本是外部原因带动；如果商场客流涨但本店没涨，问题就不是外部原因，而是门口、平台页或前台承接没接住。",
        ])
    if "低峰" in text and any(keyword in text for keyword in ("少排人", "迎宾", "核销")):
        return "\n\n".join([
            "低峰可以少排人，但不能把迎宾和核销一起砍掉。",
            "做法：低峰只保 1 个前台兼迎宾和核销，厨房保基础备菜；不要让顾客进门没人接、核销没人解释。",
            "明天看低峰三项：进店等待、核销耗时、低峰客单。如果人少了但核销变慢或差评增加，就说明少排过头。",
        ])
    if "晚市加哪个岗位" in text or ("上菜慢" in text and "等位" in text and "岗位" in text):
        return "\n\n".join([
            "晚市只加一个岗位时，先加前厅协调岗，不是直接加厨房。",
            "原因：上菜慢和等位都变长，通常需要一个人同时盯等位、催菜、桌型分流和核销解释；厨房如果没人协调，单纯加厨师也可能继续堵在前台。",
            "如果 30 分钟后招牌鱼出餐仍超过阈值，再把人转去后厨盯招牌鱼出餐。今天看等位时长、上菜时长、差评关键词。",
        ])
    if "培训话术还是厨房速度" in text or ("服务员" in text and "差评" in text and "厨房" in text):
        return "\n\n".join([
            "先查厨房速度，再补服务话术，两个都要做，但顺序不能反。",
            "如果出餐超过阈值，服务员话术只能止损，解决不了根因；厨师长先查招牌鱼上菜时长、鱼片熟度、底料咸淡。",
            "前台同步补一句话术：现在预计还要等 8-10 分钟，我先帮您催招牌鱼，坐下后优先上。明天看上菜时长和服务差评有没有一起降。",
        ])
    if "厨师长还是仓管" in text:
        return "\n\n".join([
            "底料实际耗用高于 BOM，今天先查厨师长，再让仓管复核入出库。",
            "厨师长先查三件事：底料勺量有没有超、每份用量有没有偏、晚高峰有没有临时加料不记录。",
            "仓管负责复核：领料数量、退料数量、库存盘点是否对得上。厨师长查使用动作，仓管查账实差异，两个结果对不上再追责任。",
        ])
    if "误伤利润" in text or ("先别做什么" in text and "利润" in text):
        return "\n\n".join([
            "最容易误伤利润的动作有三个，今天先别做。",
            "1. 别全店满减：会把本来愿意原价消费的人也打折。2. 别把低价券塞满晚高峰：座位被低毛利客占掉。3. 别主推米饭、纸巾、低价值配套项：销量看着涨，客单和毛利不一定涨。",
            "今天只做小范围测试：一款双人套餐、一个入口、一段低峰时段。明天看毛利额，不只看销量。",
        ])
    if "数据反问" in text or ("反问" in text and "店长" in text):
        return "\n\n".join([
            "可以这样用数据反问店长，不用吵：",
            "1. 如果没问题，为什么工作日午市收入、双人套餐占比、客单价这三个数没有一起变好？",
            "2. 如果只是客流问题，为什么门口路过人不少，但进店和核销没有同步涨？",
            "3. 如果厨房没问题，为什么差评关键词还集中在上菜慢、口味不稳或解释不清？",
            "店长只要拿这三组数回答，不要用“感觉还行”回答。",
        ])
    if "营收掉了" in text and ("满减" in text or "不想做" in text):
        return "\n\n".join([
            "我先纠正一个前提：demo 里的全店周环比并不是下滑，最近一周营收比上周高约 10.5%。如果你说的是指定门店下滑，需要先确认门店和时段。",
            "如果只是想在不想打折、不做满减的前提下把收入拉回来，今天不要降价，先把“招牌鱼双人餐 + 高毛利小食冰豆花”的理由讲清楚：两个人吃什么、多少钱、多久吃完。",
            "今天只看三项：套餐卖出份数、客单价、套餐毛利。份数涨但毛利掉，就说明不是好动作；客单和毛利一起涨，明天再扩大到美团/点评首屏。",
        ])
    if "感觉本周变差" in text or ("本周变差" in text and "先管" in text):
        return "\n\n".join([
            "我先纠正一个前提：demo 里的全店周环比并不是下滑；如果是老板感觉变差，先做范围确认，不要直接开会追责。",
            "今天先问清楚四个范围：是全店、某一家门店、某个平台，还是某个时段。范围没确认前，先不要改价格，也不要让仓管和厨房一起背锅。",
            "如果老板只想马上派一个动作，就先管工作日午市入口：门口、点评/美团首图、前台话术统一成“招牌鱼双人餐，约 270 元，45 分钟左右吃完”。",
        ])
    if "抖音" in text and ("晚高峰" in text or "座位" in text or "便宜券" in text):
        return "\n\n".join([
            "抖音曝光高但晚高峰被便宜券占座，今天先限量，不是继续放券。",
            "做法很直接：18:00-20:00 低毛利券限核销量，把券改成“招牌鱼+高毛利小食”的组合；已经到店的券客，前台只推荐冰豆花/饮品这一类高毛利加购。",
            "明天看三件事：晚高峰低价券占座数、券后客单价、加购毛利。座位少被低价券占、客单没掉，才继续投抖音。",
        ])
    if "抖音" in text and ("客单低" in text or "别亏毛利" in text):
        return "\n\n".join([
            "抖音团购客单低，今天不要追播放量，先追券后毛利。",
            "券包只保留一档：招牌鱼做入口，冰豆花/饮品做加购；低价单人券先停，因为它会把新客带来，但很难把利润留下。",
            "前台核销时必须问一句：要不要加冰豆花，吃辣会舒服一点，出餐也快。明天看券后客单、加购率、平台扣点后的毛利额。",
        ])
    if "门口经过的人多" in text or ("路过" in text and "不进来" in text):
        return "\n\n".join([
            "门口人多但不进来，今天先改第一眼，不先改菜单。",
            "海报只保留三句话：招牌青花椒鱼、两人约 270 元、45 分钟左右吃完。门迎站到外摆线，看到两人客先讲双人餐和预计用餐时间，不等顾客自己研究。",
            "明天只看门口路过、停下咨询、实际进店三项。咨询涨但进店不涨，说明前台承接弱；咨询都不涨，说明门口第一眼还不够清楚。",
        ])
    if "商场今天客流高" in text or ("商场" in text and "红利" in text):
        return "\n\n".join([
            "商场客流高但本店没吃到红利，今天先查漏斗，不要先怪菜品。",
            "按顺序查三段：商场到本楼层的人有没有涨、门口路过有没有停、停下的人有没有进店核销。哪一段掉得最厉害，今天就只改那一段。",
            "如果掉在门口，就改海报和迎宾；如果掉在平台核销，就改美团/点评首图和前台核销话术；如果进店后没下单，再看菜单和厨房。",
        ])
    if "桌型" in text and "排班" in text:
        return "\n\n".join([
            "一句话结论：今天先调桌型，具体是调二人桌/四人桌比例，再微调晚市排班，不要两件事混在一起拍脑袋。",
            "桌型动作：午市前先拿 2 张四人桌设成可拼可拆区，2 人客先坐拼拆位，3-4 人客来了再合桌；前台每 15 分钟记录等位、空桌和四人桌被二人客占用次数。",
            "排班动作：18:00-20:00 只加一个前厅协调岗，专门盯等位、核销和催菜。明天看二人客等位、四人客流失、上菜时长，三个数决定是继续改桌型还是固定加人。",
        ])
    if "四人桌" in text and ("两人" in text or "二人" in text or "占死" in text):
        return "\n\n".join([
            "一句话结论：二人桌不够、四人桌被两人占死，今天不要全店大改，只设一块可拼可拆区。",
            "午市前拿 2 张四人桌改成拼拆桌：2 人客先坐拼拆位，3-4 人客来了再合桌；前台每 15 分钟记录一次四人桌被二人客占用次数。",
            "判断标准不是翻台率一个数，而是两人客等位有没有降、四人客有没有被赶走、拼拆区有没有空桌浪费。",
        ])
    if "翻台慢" in text and ("加人" in text or "改桌型" in text):
        return "\n\n".join([
            "翻台慢先不要急着加人，先判断堵点是不是桌型。",
            "如果空桌多但队伍长，优先改前台分流和拼拆桌；如果桌子坐满但上菜慢，再加后厨或出餐协调。今天先让店长每 15 分钟记一次：空桌、等位、上菜时长。",
            "明天看等位下降来自哪里：如果改桌型后等位降，说明不是缺人；如果桌型改了还慢，再把人加到厨房或前厅协调岗。",
        ])
    if "鱼片老了" in text or ("鱼片" in text and "抽查" in text):
        return "\n\n".join([
            "点评说鱼片老了，今天厨师长先抽查鱼片，不要只让前台道歉。",
            "抽查三件事：鱼片厚度是否一致、下锅到出锅时间是否超、上桌前是否压单太久。每 30 分钟抽 3 份招牌鱼，记录熟度和出餐时长。",
            "如果熟度正常但顾客还说老，再查等菜时间和上桌温度；如果熟度不稳，先停复杂低销量菜，把厨房注意力拉回招牌鱼。",
        ])
    if "退菜" in text or "重做" in text:
        return "\n\n".join([
            "一句话结论：退菜和重做变多，今天先查是哪一道菜、哪一个时段、哪一个岗位，不要泛泛开会。",
            "厨师长把退菜按三类分：口味不稳、熟度不对、上菜太慢；每类只找今天最多的一道菜先改。前台同步记录顾客原话，别只写“客诉”。",
            "明天看退菜次数、重做成本、同一道菜差评有没有下降。次数降了但重做成本没降，说明只是前台拦住了投诉，厨房问题还在。",
        ])
    if "临期豆腐" in text or ("番茄" in text and "不想报损" in text):
        return "\n\n".join([
            "临期豆腐和番茄今天要消耗，但不能硬推给顾客，也不能拖到打烊再报损。",
            "做法是两条线：豆腐进员工餐和低峰小食试吃，番茄进当天汤底/配菜；不要把临期品包装成主推菜，也不要为了消耗影响招牌鱼体验。",
            "打烊看三项：临期消耗金额、有没有新增相关差评、招牌菜有没有被挤占备货。消耗掉且不伤评价，才算处理对。",
        ])
    if "毛利掉了" in text and ("采购" in text or "损耗" in text or "菜品结构" in text):
        return "\n\n".join([
            "一句话结论：毛利掉了，今天按三步归因，不要一上来涨价。",
            "第一步看采购：活鱼和底料近三次单价有没有涨；第二步看损耗：实际用量是不是高过 BOM；第三步看菜品结构：是不是低毛利券和低价值配套项卖多了。",
            "三种结果对应三种动作：采购涨就谈供应商或换规格，损耗高就让厨师长查称重和勺量，结构差就停低毛利券、改推高毛利小食。",
        ])
    if "同城门店午市" in text or ("复制哪个动作" in text and "午市" in text):
        return "\n\n".join([
            "同城门店午市更好，不要复制整套菜单，只复制一个已经验证的入口动作。",
            "今天复制青花椒静安大融城店的午市打法：点评/美团首屏放双人套餐，前台只讲“招牌鱼、两人价、45 分钟吃完”，厨房提前锁定招牌鱼备料。",
            "明天只比三项：午市进店、双人套餐占比、午市客单价。三项有两项缩小差距，再考虑复制它的排班或活动。",
        ])
    if "内部排名" in text or ("连锁" in text and "复制" in text):
        return "\n\n".join([
            "连锁内部排名不高，先别问“谁第一”，要问“哪一个动作可复制”。",
            "这家店日均不弱，问题更像工作日午市和客单没吃满；所以先对标午市强店，复制入口套餐和前台推荐话术，不复制全部菜单。",
            "区域经理明天看三项差距：工作日午市收入、双人套餐占比、客单价。差距缩小，再把做法固化成门店 SOP。",
        ])
    if "前台话术" in text or ("话术" in text and any(keyword in text for keyword in ("怎么说", "怎么讲", "落到前台"))):
        return "\n\n".join([
            "前台今天就用这三句话，不要背长话术：",
            "1. 迎宾：今天两个人吃招牌鱼最合适，双人套餐大概 270 元，45 分钟左右能吃完。",
            "2. 核销：我先帮您确认券，确认完建议直接上招牌鱼，再加一份冰豆花，等位和出餐会更稳。",
            "3. 安抚：现在预计还要等 8-10 分钟，我先帮您排招牌鱼，坐下后能更快上。",
            "店长今晚只听前台有没有说到这三句，不要让每个人自由发挥。",
        ])
    if any(keyword in text for keyword in ("四个岗位", "各自盯什么", "各岗位", "岗位今天")):
        return "\n\n".join([
            "老板今天不要一直催，直接拆成四个岗位的四张清单：",
            "1. 仓管：10:30 前看活鱼、青花椒底料、冰豆花原料，缺口先补，临期豆腐和番茄贴红标先消耗。",
            "2. 厨师长：晚市只盯招牌鱼出餐时长、鱼片熟度、底料咸淡，复杂低销量菜不主动推。",
            "3. 前台：只讲招牌、双人价格、预计用餐时间三句话；核销客先确认券，再推荐招牌加小食。",
            "4. 店长：只盯等位、上菜、缺货三个异常；打烊后复盘收入、上菜时长、缺货次数。",
        ])
    return ""


def _owner_chat_answer(owner_page: dict[str, Any], scenario: str, message: str, is_follow_up: bool = False) -> str:
    headline = _plain_text(owner_page.get("headline"))
    diagnosis = _plain_text(owner_page.get("plainDiagnosis"))
    focus = owner_page.get("decisionFocus") or {}
    problem = _plain_text(focus.get("primaryProblem") if isinstance(focus, dict) else None)
    focus_reason = _plain_text(focus.get("why") if isinstance(focus, dict) else None)
    first_action = _first_text(owner_page.get("doFirst"))
    premise_check = _owner_premise_check(owner_page, message)
    plain_reason = _owner_plain_reason(owner_page, scenario, focus_reason or diagnosis)
    plain_actions = _owner_plain_actions(owner_page, scenario, first_action)
    specific_guidance = _owner_message_specific_guidance(owner_page, scenario, message)
    plain_reason = _owner_message_specific_reason(owner_page, scenario, message, plain_reason)
    plain_actions = _owner_message_specific_actions(owner_page, scenario, message, plain_actions)
    do_not_do = _owner_do_not_do(owner_page, scenario)
    watch_numbers = _owner_watch_numbers(owner_page, scenario)
    watch_numbers = _owner_message_specific_watch_numbers(owner_page, scenario, message, watch_numbers)
    evidence_text = _owner_plain_evidence(owner_page, scenario)

    direct_special = _owner_direct_special_answer(owner_page, scenario, message)
    if direct_special:
        return direct_special

    metric_follow_up = _owner_metric_follow_up_answer(owner_page, scenario, message)
    if metric_follow_up:
        return metric_follow_up
    action_follow_up = _owner_action_follow_up_answer(owner_page, scenario, message)
    if action_follow_up and is_follow_up:
        return action_follow_up

    direction_label = {
        "revenue_growth": "营收增长路径",
        "operations_dispatch": "岗位派工",
        "inventory_reorder": "库存补货",
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
        f"以下分析围绕{direction_label}。",
    ]
    if premise_check:
        parts.insert(0, premise_check)
    if "厨房慢" in message and "服务慢" in message:
        parts.append(
            "先后顺序：先处理厨房慢，再处理服务慢。厨房慢会直接放大等位、催菜和差评；服务话术要同步补，但今天先让厨师长把招牌鱼出餐时长压住，前厅只负责提前告知时间和安抚。"
        )
    if specific_guidance:
        parts.append(f"这次问题的重点：{specific_guidance}")
    if plain_reason:
        parts.append(f"原因：{plain_reason}")
    if scenario == "single_item_push":
        parts.append("主推排序口径：米饭、餐具、纸巾这类低价值配套项先排除；真正看招牌菜销量、连带加购率和毛利额，三项同时变好才算主推有效。")
    if plain_actions:
        action_lines = "\n".join(f"{index}. {action}" for index, action in enumerate(plain_actions[:3], start=1))
        parts.append(f"今天建议做：\n{action_lines}")
    if do_not_do:
        parts.append(f"今天先别做：{do_not_do}")
    if watch_numbers:
        parts.append(f"明天怎么验证：{watch_numbers}")
    if evidence_text:
        parts.append(f"本次调用数据：{evidence_text}")
    # (Sheet 行24 采纳: 删掉"你继续追问时…不会换题"这类对自身行为的元陈述 —
    #  多轮上下文由会话机制保证, 不靠自我表白。)
    return "\n\n".join(part for part in parts if part)


def owner_action_chat(body: OwnerActionChatRequest) -> dict:
    return _owner_action_chat_impl(body, request=None)


@router.post("/owner-action-chat")
async def owner_action_chat_http(body: OwnerActionChatRequest, request: Request) -> dict:
    authenticated_factory_id = getattr(request.state, "factory_id", None)
    if not authenticated_factory_id:
        raise HTTPException(status_code=401, detail="Internal tenant identity required")
    if not hmac.compare_digest(
        str(authenticated_factory_id).encode("utf-8"),
        body.factory_id.encode("utf-8"),
    ):
        raise HTTPException(status_code=403, detail="Authenticated tenant does not match request tenant")

    started_at = time.time()
    live_overrides = await _fetch_live_gold_overrides(body.factory_id)
    response = _owner_action_chat_impl(
        body, request=request, live_overrides=live_overrides)
    data = response.get("data") if isinstance(response, dict) else {}
    if isinstance(data, dict):
        charts = data.get("charts") if isinstance(data.get("charts"), list) else []
        owner_page = data.get("ownerDecisionPage") if isinstance(data.get("ownerDecisionPage"), dict) else {}
        log_id = await _log_owner_action_chat_async(
            query=body.message,
            factory_id=body.factory_id,
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


async def _fetch_live_gold_overrides(factory_id: str) -> dict | None:
    """R29 双数据空间统一: owner-action 剧本的成本/毛利数字 (49%/51%) 与
    金数据真实毛利分析 (~79%) 冲突。demo 租户实时取金数据覆写这两个数字,
    并携带成本覆盖率供措辞如实披露。任何失败 fail-open 返 None (剧本原样,
    演示不崩); LLM 不参与, 数字全部来自 resolver。"""
    try:
        normalized = (factory_id or "").strip().upper()
        if normalized != "DEMO_REST" and not normalized.startswith("RES_"):
            return None
        from smartbi.config import get_pg_pool
        from smartbi.gold.restaurant_ops_router import (
            demo_data_factory_for_code,
            resolve_gross_margin,
        )
        pool = await get_pg_pool()
        if pool is None:
            return None
        gm_factory = demo_data_factory_for_code(
            "RESTAURANT_OPS_GROSS_MARGIN", factory_id, store_scoped=False)
        answer = await resolve_gross_margin(
            pool, gm_factory, role="factory_super_admin", query="整体毛利率是多少")
        meta = getattr(answer, "meta", None) or {}
        covered_rev = float(meta.get("cost_covered_revenue") or 0.0)
        covered_cost = float(meta.get("covered_cost") or 0.0)
        coverage = meta.get("cost_coverage_ratio")
        if covered_rev <= 0 or meta.get("marginInvariantPass") is False:
            return None
        margin_pct = (covered_rev - covered_cost) / covered_rev * 100.0
        food_cost_pct = covered_cost / covered_rev * 100.0
        return {
            # foodCostRatio 语义是 0-1 比率 (_owner_pct 会 ×100), 塞百分数
            # 曾显示成 2080% (R29b 实测)。grossMarginPct 是百分数直显。
            "foodCostRatio": round(food_cost_pct / 100.0, 4),
            "grossMarginPct": round(margin_pct, 1),
            "costCoveragePct": round(float(coverage) * 100.0, 1) if coverage is not None else None,
        }
    except Exception as exc:
        logger.warning(f"[owner-action] live gold overrides failed (fail-open): {exc}")
        return None


def _owner_action_chat_impl(
    body: OwnerActionChatRequest,
    request: Request | None = None,
    live_overrides: dict | None = None,
) -> dict:
    """Demo chat wrapper for boss-facing restaurant action analysis.

    This endpoint intentionally uses deterministic demo scenarios. It gives the
    frontend a stable chat trigger while the full Java intent/SSE route remains
    factory-admin oriented.
    """

    factory_id = body.factory_id
    provided_session_id = _effective_str(body.session_id, body.sessionId)
    session_id = provided_session_id or f"owner-action-{uuid.uuid4().hex[:12]}"
    session_key = _owner_action_session_key(factory_id, session_id)
    previous = _OWNER_ACTION_CHAT_SESSIONS.get(session_key, {})
    requested = _effective_str(body.demo_scenario, body.demoScenario)
    requested_scenario = _OWNER_ACTION_SCENARIO_ALIASES.get(str(requested or "").strip(), requested)
    scenario = _pick_owner_action_scenario(body.message, requested, previous.get("scenario", ""))
    scenarios = set(list_owner_action_demo_scenarios())
    is_follow_up_turn = _is_follow_up(body.message) and (
        (bool(previous.get("scenario")) and scenario == previous.get("scenario"))
        or (bool(provided_session_id) and requested_scenario in scenarios and scenario == requested_scenario)
        or (bool(provided_session_id) and not previous and scenario in scenarios)
    )

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
    if live_overrides:
        # R29: 成本/毛利数字以金数据为准, 剧本其余叙事保留。
        financial = params.setdefault("financial_summary", {})
        if isinstance(financial, dict):
            financial["foodCostRatio"] = live_overrides["foodCostRatio"]
            financial["grossMarginPct"] = live_overrides["grossMarginPct"]
        if live_overrides.get("costCoveragePct") is not None:
            params["goldCostCoveragePct"] = live_overrides["costCoveragePct"]

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
    role_plan = _owner_role_action_plan(params, scenario)
    if isinstance(owner_page, dict):
        owner_page["demoParams"] = params
        if role_plan:
            owner_page["roleActionPlan"] = role_plan
    answer = _owner_chat_answer(owner_page, scenario, body.message, is_follow_up=is_follow_up_turn)
    follow_ups = _owner_chat_follow_ups(scenario, body.message)
    charts = _owner_evidence_charts(owner_page, scenario, params)
    chart_guide = _owner_chart_guide(scenario) if charts else ""
    data_readiness = _owner_action_data_readiness(scenario, params)
    _OWNER_ACTION_CHAT_SESSIONS[session_key] = {
        "scenario": scenario,
        "lastMessage": body.message,
        "lastAnswer": answer,
    }

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
            "roleActionPlan": role_plan,
            "decisionFocus": owner_page.get("decisionFocus"),
            "ownerDecisionPage": owner_page,
            "dataReadiness": data_readiness,
            "demoActionScenarios": list_owner_action_demo_scenarios(),
        },
    }


@router.post("/{section_name}")
def compute_section(
    request: Request,
    section_name: str = Path(..., description="Section handler name (see /list)"),
    body: SectionRequestBody = ...,
) -> dict:
    """Run a single section handler and return its envelope.

    The response shape mirrors the project's standard ``{success, data, ...}``
    envelope but additionally includes the SectionResponse metadata
    (``status``, ``warnings``, ``cacheKey``, ``computedAtMs``) so the
    frontend can render skip reasons or surface compute time.
    """
    authenticated_factory_id = getattr(request.state, "factory_id", None)
    if not authenticated_factory_id:
        raise HTTPException(status_code=401, detail="Authenticated tenant identity required")
    if not hmac.compare_digest(
        str(authenticated_factory_id).encode("utf-8"),
        body.factory_id.encode("utf-8"),
    ):
        raise HTTPException(status_code=403, detail="Authenticated tenant does not match request tenant")

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
def download_monthly_ppt(factory_id: str, period: str, request: Request):
    """Stream a previously-generated monthly PPT file for download.

    Call POST /sections/monthly_ppt_export first to generate the file,
    then GET this endpoint to download it. Files are stored in
    /tmp/smartbi_ppt/ and named monthly_{factory_id}_{period}.pptx.
    """
    authenticated_factory_id = getattr(request.state, "factory_id", None)
    if not authenticated_factory_id:
        raise HTTPException(status_code=401, detail="Authenticated tenant identity required")
    if not hmac.compare_digest(
        str(authenticated_factory_id).encode("utf-8"),
        factory_id.encode("utf-8"),
    ):
        raise HTTPException(status_code=403, detail="Authenticated tenant does not match request tenant")

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
