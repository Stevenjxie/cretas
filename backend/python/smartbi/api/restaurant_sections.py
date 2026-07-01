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
import tempfile
from pathlib import Path as FsPath
from typing import Any, Dict, Optional

import pandas as pd
from fastapi import APIRouter, HTTPException, Path
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
