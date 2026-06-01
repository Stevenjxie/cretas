"""Gold layer — durable rollup tables + materialization machinery.

Week 3 of Unified Data Layer v1 spec (§4.1).

The existing `smartbi/services/materialized_analytics/` package (per-upload
chart cache writing into SmartBiPgAnalysisResult) remains unchanged. Both
it and this package live under the conceptual "Gold" layer per spec §1.1:

  Gold = durable agg_* tables (THIS package, Week 3)
       + per-upload chart cache (existing materialized_analytics/)

Downstream modules read agg_* directly via RLS-filtered SELECT. No Java-
to-Python hop for common rollups.

Public surface
--------------
- `GoldMaterializer` — reads Silver, writes agg_*. One method per target
  table. Each call upserts with version bump.
- `MaterializationTrigger` — ABC for "when should we rematerialize?";
  Day 3 implements upload_complete / field_registry_reviewed /
  api_append_incremental.
"""
from __future__ import annotations

from smartbi.gold.dual_write import run_silver_dual_write, silver_dual_write_enabled
from smartbi.gold.materializer import GoldMaterializer, MaterializeStats
from smartbi.gold.pipeline import PipelineStats, ingest_and_materialize
from smartbi.gold.queries import (
    channel_breakdown,
    daily_trend,
    data_range,
    discount_breakdown,
    finance_summary,
    kpi_summary,
    order_type_mix,
    staff_ranking,
    top_products,
)
from smartbi.gold.review_queries import (
    review_city_ranking,
    review_complaints,
    review_dish_issues,
    review_store_ranking,
    review_summary,
    review_vip,
)
from smartbi.gold.shadow_compare import DiffReport, FieldDiff, diff_results
from smartbi.gold.triggers import (
    ApiAppendIncrementalTrigger,
    FieldRegistryReviewedTrigger,
    MaterializationTrigger,
    TriggerResult,
    UploadCompleteTrigger,
)

__all__ = [
    "ApiAppendIncrementalTrigger",
    "DiffReport",
    "FieldDiff",
    "FieldRegistryReviewedTrigger",
    "GoldMaterializer",
    "MaterializationTrigger",
    "MaterializeStats",
    "PipelineStats",
    "TriggerResult",
    "UploadCompleteTrigger",
    "channel_breakdown",
    "daily_trend",
    "data_range",
    "diff_results",
    "discount_breakdown",
    "finance_summary",
    "ingest_and_materialize",
    "kpi_summary",
    "order_type_mix",
    "review_city_ranking",
    "review_complaints",
    "review_dish_issues",
    "review_store_ranking",
    "review_summary",
    "review_vip",
    "staff_ranking",
    "top_products",
    "run_silver_dual_write",
    "silver_dual_write_enabled",
]
