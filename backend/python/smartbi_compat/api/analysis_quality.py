"""Phase 2B T6.6 ``/analysis/quality`` endpoint port.

chat-B1 Wave 1 shipped the skeleton + factory deferral (PR #354). chat-B2
Wave 2 (this commit) implements the restaurant branch per T6.6 Phase B
Sub-B spec §3 + Q4/Q5 impl spec §4 + Q-DEC-8 Option A envelope.

Per organizer 2026-05-12 Option B decision the **factory branch remains
deferred to Phase 2D** (factory Silver schema migration pending — same
rationale as chat-A1 PR #350 production precedent).

Restaurant branch returns a 4-metric envelope (N1 FOOD_SAFETY_INCIDENT_RATE,
N2 COMPLAINT_RATE, N3 DISH_RETURN_RATE, N4 WASTAGE_RATE) per
``2026-05-12-t6-6-restaurant-semantics-decision.md`` §2.

* N1 (Q-DEC-4 = D1): always-null + ``MISSING_FOOD_SAFETY_INCIDENT_LOG``
  — no food-safety incident log schema exists.
* N2 (Q-DEC-5 = E1): rating-based proxy from ``restaurant_reviews``
  (count of rating < 3.0 / total reviews × 100). Real value for
  ``R_QINGHUAJIAO_REAL`` (青花椒, has 大众点评 data); null for the other
  13 chains. Marker ``NO_REVIEW_DATA_FOR_CHAIN`` when ``total_reviews == 0``.
* N3 (Q-DEC-6 = F1, ✅ LIVE prod V20260511_03): real value from
  ``fact_pos_item.return_qty`` aggregate. All 14 REAL chains have data
  post-Sub-ETL-2c ship. Marker ``NO_POS_DATA_FOR_PERIOD`` when zero rows.
* N4 (Q-DEC-7 = G1): wastage-cost / requisition-cost × 100. Null + marker
  ``WASTAGE_NOT_TRACKED`` for the 14 REAL chains (Excel source lacks
  wastage column); demo chain ``RES_3101_009`` may emit real value.

Java reference (kept for Phase 2D factory port):

* Controller: ``SmartBIAnalysisController.getQualityAnalysis`` lines 119-152
* Service (factory branch only — mock; KEEP forever per Dashboard composite binds):
  ``QualityAnalysisServiceImpl``
* Tenant predicate: ``SmartBIServiceImpl.isRestaurantTenant`` lines 432-441

Specs:

* Sub-B impl: docs/superpowers/specs/2026-05-12-t6-6-sub-b-quality-impl-spec.md §3
* Q5 decisions: docs/superpowers/specs/2026-05-12-t6-6-restaurant-semantics-decision.md (PR #330)
* Q5 module shape: docs/superpowers/specs/2026-05-11-q4-q5-restaurant-analysis-impl-spec.md §4
* Factory port detail (Phase 2D): docs/superpowers/specs/2026-05-09-t6-6-quality-port-detail.md
* Sibling shipped (chat-A2 restaurant production):
  backend/python/smartbi_compat/api/analysis_production.py
"""
from __future__ import annotations

import logging
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Optional

from fastapi import APIRouter, Depends, Query

from smartbi_compat._rbac_role import require_analytics_read
from smartbi_compat._rbac_strip import strip_price_for_role
from smartbi_compat.api import analysis_production as factory_gold
from smartbi_compat.api.analysis_finance import _decimal_to_number
from smartbi_compat.auth import AuthContext
from smartbi_compat.schema_compat import _java_isoformat, wrap_response
from smartbi_compat.tenant import TenantType, get_tenant_type

logger = logging.getLogger(__name__)
router = APIRouter()


# ============================================================
# Stub markers (chat-B2 + Phase 2D consume these strings in messages)
# ============================================================
#
# AGGRESSIVE-REVISED scope (Phase 2D Sub-B impl spec
# docs/superpowers/specs/2026-05-11-phase-2d-silver-migration-and-factory-impl-spec.md §3.2):
# ``_factory_quality_dispatch`` no longer raises NotImplementedError — it
# now returns an empty envelope mirroring the Java factory shape with the
# top-level ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` marker
# so the frontend can render a "data pending" state instead of hitting a
# 500. The Silver-layer port (real-DB implementation) is the next chain
# Phase 2D PR-A/B/C/D.
#
# ``_FACTORY_BRANCH_DEFERRED_MSG`` is kept as a documentation constant —
# other modules / log messages still reference the string. The dispatch
# function no longer raises with it.

_FACTORY_BRANCH_DEFERRED_MSG = (
    "Factory quality analysis returns an empty envelope marked "
    "FACTORY_SILVER_PHASE_2D_PENDING pending the factory Silver schema "
    "migration (fact_quality_inspection / fact_quality_defect / "
    "fact_rework_record / fact_disposal_record / fact_customer_complaint). "
    "chat-B1 dispatch 2026-05-12 Option B: _JavaRandom mock-mirror fallback "
    "rejected (Q1 amendment §1; mirrors chat-A1 PR #350 production precedent)."
)


# Top-level envelope marker emitted by ``_factory_quality_dispatch`` so the
# frontend can distinguish "Phase 2D placeholder, no real data" from a normal
# data-bearing factory response. Tests import this constant to assert every
# stub response carries the exact marker string.
FACTORY_PHASE_2D_PENDING_MARKER = "FACTORY_SILVER_PHASE_2D_PENDING"


# ============================================================
# Controlled ``dataAvailability`` vocabulary (Sub-B spec §4 / PR #330 §3.4)
# ============================================================
#
# NO additional markers may be introduced without organizer sign-off
# (would require spec §4.2 amendment). Grep-target for chat-B3 wiring audit
# + frontend chip-badge rendering depends on these exact strings.
#
# Tuple kept as the chat-B1 skeleton contract — vocabulary discipline test
# imports this constant and asserts every emitted dataAvailability ∈ tuple.

_AVAILABILITY_MISSING_FOOD_SAFETY = "MISSING_FOOD_SAFETY_INCIDENT_LOG"  # N1 — Q-DEC-4 D1
_AVAILABILITY_NO_REVIEW_DATA = "NO_REVIEW_DATA_FOR_CHAIN"               # N2 — Q-DEC-5 E1
_AVAILABILITY_RETURN_QTY_NOT_INGESTED = "RETURN_QTY_NOT_INGESTED"       # N3 fallback Q-DEC-6 F3
_AVAILABILITY_NO_POS_DATA = "NO_POS_DATA_FOR_PERIOD"                    # N3 — zero rows
_AVAILABILITY_WASTAGE_NOT_TRACKED = "WASTAGE_NOT_TRACKED"               # N4 — Q-DEC-7 G1

_RESTAURANT_DATA_AVAILABILITY_VOCAB = (
    _AVAILABILITY_MISSING_FOOD_SAFETY,
    _AVAILABILITY_NO_REVIEW_DATA,
    _AVAILABILITY_RETURN_QTY_NOT_INGESTED,
    _AVAILABILITY_NO_POS_DATA,
    _AVAILABILITY_WASTAGE_NOT_TRACKED,
)


def _factory_quality_placeholder(
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> dict:
    now_iso = _java_isoformat(datetime.now())
    base: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "dataAvailability": FACTORY_PHASE_2D_PENDING_MARKER,
    }

    if analysis_type == "fpy":
        return {**base, "metrics": [], "trendChart": {}}

    if analysis_type == "defect":
        return {**base, "ranking": [], "paretoChart": {}}

    if analysis_type == "rework":
        return {**base, "metrics": [], "costChart": {}}

    return {
        "period": "CUSTOM",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "kpiCards": [],
        "rankings": {},
        "charts": {},
        "aiInsights": [],
        "recommendations": [],
        "suggestions": [],
        "generatedAt": now_iso,
        "lastUpdated": now_iso,
        "fromCache": False,
        "cacheExpireAt": None,
        "dataAvailability": FACTORY_PHASE_2D_PENDING_MARKER,
    }


def _calculate_factory_quality_summary(rows: list) -> dict:
    production_summary = factory_gold._calculate_factory_production_rates(rows)
    total_actual = production_summary["totalActualQty"]
    total_good = production_summary["totalGoodQty"]
    defect_count = total_actual - total_good
    if defect_count < 0:
        defect_count = Decimal("0")
    return {
        **production_summary,
        "fpy": factory_gold._rate_pct(total_good, total_actual),
        "defectCount": defect_count,
        "defectRate": factory_gold._rate_pct(defect_count, total_actual),
        "qualityCost": production_summary["totalCost"],
    }


def _build_factory_quality_fpy_metrics(summary: dict) -> list:
    return [
        factory_gold._factory_metric(
            "FPY",
            "首次通过率 (FPY)",
            summary["fpy"],
            "%",
            factory_gold._alert_low(summary["fpy"], Decimal("95"), Decimal("98")),
        ),
        factory_gold._factory_metric(
            "TOTAL_INSPECTIONS",
            "总检验数",
            summary["totalActualQty"],
            "件",
            "GREEN",
        ),
        factory_gold._factory_metric(
            "DEFECT_COUNT",
            "不良品数",
            summary["defectCount"],
            "件",
            "GREEN",
        ),
        factory_gold._factory_metric(
            "DEFECT_RATE",
            "不良率",
            summary["defectRate"],
            "%",
            factory_gold._alert_high(summary["defectRate"], Decimal("5"), Decimal("2")),
        ),
    ]


def _build_factory_quality_rework_metrics(summary: dict) -> list:
    return [
        factory_gold._factory_metric("REWORK_COUNT", "返工数", None, "件"),
        factory_gold._factory_metric("REWORK_COST", "返工成本", None, "元"),
        factory_gold._factory_metric("SCRAP_COUNT", "报废数", None, "件"),
        factory_gold._factory_metric("SCRAP_COST", "报废成本", None, "元"),
        factory_gold._factory_metric(
            "TOTAL_QUALITY_COST",
            "质量成本总计",
            summary["qualityCost"],
            "元",
            "GREEN",
            "Gold only exposes total production cost; rework/scrap split is unavailable.",
        ),
        factory_gold._factory_metric("REWORK_RATE", "返工率", None, "%"),
        factory_gold._factory_metric("SCRAP_RATE", "报废率", None, "%"),
    ]


def _build_factory_quality_trend_chart(rows: list) -> dict:
    data = []
    for day_rows in factory_gold._group_rows_by_date(rows):
        summary = _calculate_factory_quality_summary(day_rows)
        stat_date = day_rows[0]["stat_date"]
        data.append({
            "date": stat_date.isoformat() if stat_date is not None else None,
            "fpy": factory_gold._decimal_metric_value(summary["fpy"]),
            "defectRate": factory_gold._decimal_metric_value(summary["defectRate"]),
            "totalInspections": factory_gold._decimal_metric_value(
                summary["totalActualQty"]
            ),
        })
    return {
        "chartType": "LINE",
        "title": "质量趋势",
        "xAxisField": "date",
        "yAxisField": "fpy",
        "seriesField": "metric",
        "data": data,
        "options": {"showLegend": True, "multiLine": True, "yAxisMax": 100},
    }


def _build_factory_quality_defect_ranking(rows: list) -> list:
    grouped: dict[Any, list] = {}
    for row in rows:
        grouped.setdefault(row["product_type_id"], []).append(row)

    ranking = []
    for product_type_id, product_rows in grouped.items():
        if product_type_id is None:
            continue
        summary = _calculate_factory_quality_summary(product_rows)
        ranking.append({
            "name": product_type_id,
            "value": factory_gold._decimal_metric_value(summary["defectCount"]),
            "target": None,
            "completionRate": factory_gold._decimal_metric_value(summary["defectRate"]),
            "alertLevel": factory_gold._alert_high(
                summary["defectRate"], Decimal("5"), Decimal("2")
            ),
        })
    ranking.sort(key=lambda item: item["value"] if item["value"] is not None else -1, reverse=True)
    for idx, item in enumerate(ranking, start=1):
        item["rank"] = idx
    return ranking


def _build_factory_quality_pareto_chart(rows: list) -> dict:
    ranking = _build_factory_quality_defect_ranking(rows)
    total_defects = sum(
        Decimal(str(item["value"])) for item in ranking if item["value"] is not None
    )
    cumulative = Decimal("0")
    data = []
    for item in ranking:
        count = Decimal(str(item["value"] or 0))
        pct = factory_gold._rate_pct(count, total_defects)
        cumulative += pct if pct is not None else Decimal("0")
        data.append({
            "defectType": item["name"],
            "count": item["value"],
            "percentage": factory_gold._decimal_metric_value(pct),
            "cumulative": factory_gold._decimal_metric_value(cumulative),
        })
    return {
        "chartType": "BAR",
        "title": "不良产品帕累托分析",
        "xAxisField": "defectType",
        "yAxisField": "count",
        "seriesField": "metric",
        "data": data,
        "options": {"showCumulativeLine": True, "showPercentage": True},
    }


def _build_factory_quality_cost_chart(summary: dict) -> dict:
    return {
        "chartType": "PIE",
        "title": "质量成本分布",
        "xAxisField": "category",
        "yAxisField": "cost",
        "data": [
            {"category": "返工成本", "cost": None},
            {"category": "报废成本", "cost": None},
            {
                "category": "生产总成本",
                "cost": factory_gold._decimal_metric_value(summary["qualityCost"]),
            },
        ],
        "options": {"showPercentage": True, "showLegend": True},
    }


# ============================================================
# Restaurant metric builders (N1-N4)
# ============================================================


def _build_food_safety_incident_metric() -> dict:
    """N1 — Food Safety Incident Rate. Always null per Q-DEC-4 = D1.

    Spec §3.1: no food-safety incident log table or schema exists in the
    Silver layer for any of the 14 REAL chains. Honest null emission with
    controlled vocabulary marker.
    """
    return {
        "metricCode": "FOOD_SAFETY_INCIDENT_RATE",
        "value": None,
        "unit": "incidents_per_period",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_MISSING_FOOD_SAFETY,
    }


def _empty_complaint_rate_metric() -> dict:
    """N2 empty envelope — emitted for chains without review data."""
    return {
        "metricCode": "COMPLAINT_RATE",
        "value": None,
        "unit": "%",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_NO_REVIEW_DATA,
    }


def _empty_dish_return_rate_metric(
    *, availability: str = _AVAILABILITY_NO_POS_DATA
) -> dict:
    """N3 empty envelope — zero POS rows OR pool unavailable.

    Per Q-DEC-9 default: omit ``dataAvailability`` when ``value`` is present
    (the happy-path builder does so). When value is null we must emit a
    marker explaining the absence — default to ``NO_POS_DATA_FOR_PERIOD``
    since V20260511_03 is LIVE prod and the column should always be
    ingested (the ``RETURN_QTY_NOT_INGESTED`` fallback exists only for
    defensive pre-migration paths).
    """
    return {
        "metricCode": "DISH_RETURN_RATE",
        "value": None,
        "unit": "%",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": availability,
    }


def _empty_wastage_rate_metric() -> dict:
    """N4 empty envelope — wastage not tracked (14 REAL chains)."""
    return {
        "metricCode": "WASTAGE_RATE",
        "value": None,
        "unit": "%",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_WASTAGE_NOT_TRACKED,
    }


# ============================================================
# SQL helpers — Rule 6 preconditions on every entry point
# ============================================================


async def _query_complaint_rate(
    factory_id: str, start_date: date, end_date: date, conn
) -> Optional[dict]:
    """N2 — aggregate total_reviews + complaint_count from ``restaurant_reviews``.

    Rule 6 precondition: start_date / end_date required. asyncpg would
    silently bind None → NULL → ``BETWEEN NULL AND NULL`` always false
    → zero rows → caller sees "no data" when the real bug is a null param.
    """
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_complaint_rate: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    return await conn.fetchrow(
        """
        SELECT
            COUNT(*) AS total_reviews,
            SUM(CASE WHEN rating < 3.0 THEN 1 ELSE 0 END) AS complaint_count
        FROM restaurant_reviews
        WHERE factory_id = $1
          AND review_time::date BETWEEN $2 AND $3
        """,
        factory_id,
        start_date,
        end_date,
    )


async def _query_dish_return_rate(
    factory_id: str, start_date: date, end_date: date, conn
) -> Optional[dict]:
    """N3 — aggregate total_sales_qty + total_return_qty from ``fact_pos_item``.

    Rule 6 precondition + ``COALESCE(... , 0)`` semantics: legacy pre-ETL
    rows have ``return_qty IS NULL`` which we treat as "did not contribute
    a return" (the rows still contribute ``qty`` to the denominator). This
    is the documented Sub-B spec §3.3 narrower-scope semantic per PR #335
    §5.2 amendment.
    """
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_dish_return_rate: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    return await conn.fetchrow(
        """
        SELECT
            COALESCE(SUM(qty), 0)        AS total_sales_qty,
            COALESCE(SUM(return_qty), 0) AS total_return_qty
        FROM fact_pos_item fpi
        INNER JOIN fact_pos_transaction fpt
            ON fpi.transaction_id = fpt.id
        WHERE fpi.factory_id = $1
          AND fpt.date BETWEEN $2 AND $3
        """,
        factory_id,
        start_date,
        end_date,
    )


async def _query_wastage_rate(
    factory_id: str, start_date: date, end_date: date, conn
) -> Optional[dict]:
    """N4 — 3-aggregate single-row from wastage + requisition fact tables.

    Per spec §3.4. Subquery composite returns three columns:

    * ``total_wastage_cost`` — SUM(estimated_cost) FROM fact_restaurant_wastage
    * ``total_requisition_cost`` — SUM(est_cost) FROM fact_restaurant_requisition
    * ``wastage_row_count`` — COUNT(*) FROM fact_restaurant_wastage

    Rule 6 precondition + ``COALESCE(... , 0)`` semantics consistent with N3.
    """
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_wastage_rate: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    return await conn.fetchrow(
        """
        SELECT
            (SELECT COALESCE(SUM(estimated_cost), 0)
               FROM fact_restaurant_wastage
              WHERE factory_id = $1
                AND date BETWEEN $2 AND $3) AS total_wastage_cost,
            (SELECT COALESCE(SUM(est_cost), 0)
               FROM fact_restaurant_requisition
              WHERE factory_id = $1
                AND date BETWEEN $2 AND $3) AS total_requisition_cost,
            (SELECT COUNT(*)
               FROM fact_restaurant_wastage
              WHERE factory_id = $1
                AND date BETWEEN $2 AND $3) AS wastage_row_count
        """,
        factory_id,
        start_date,
        end_date,
    )


async def _query_return_rate_ranking(
    factory_id: str, start_date: date, end_date: date, conn
) -> list:
    """Per-product return rate ranking TOP 10 desc (analysisType=defect).

    Per spec §3.5. ``HAVING SUM(qty) > 0`` excludes products with zero
    sales (a return without a sale is ETL noise). ``NULLIF(..., 0)`` in the
    sort guards against division-by-zero in the same edge.
    """
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_return_rate_ranking: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    return await conn.fetch(
        """
        SELECT
            dp.product_id,
            dp.name AS product_name,
            COALESCE(SUM(fpi.qty), 0)        AS total_sales_qty,
            COALESCE(SUM(fpi.return_qty), 0) AS total_return_qty
        FROM fact_pos_item fpi
        INNER JOIN fact_pos_transaction fpt ON fpi.transaction_id = fpt.id
        INNER JOIN dim_product dp ON fpi.product_id = dp.product_id
        WHERE fpi.factory_id = $1
          AND fpt.date BETWEEN $2 AND $3
        GROUP BY dp.product_id, dp.name
        HAVING SUM(fpi.qty) > 0
        ORDER BY (SUM(fpi.return_qty)::numeric / NULLIF(SUM(fpi.qty), 0)) DESC NULLS LAST
        LIMIT 10
        """,
        factory_id,
        start_date,
        end_date,
    )


# ============================================================
# Rate computation — Rule 10 intermediate-quantize discipline
# ============================================================


def _compute_rate_pct(numerator: Decimal, denominator: Decimal) -> Decimal:
    """Compute ``numerator / denominator * 100`` mirroring Java
    ``BigDecimal.divide(divisor, 4, HALF_UP).multiply(100)`` chain.

    Rule 10 — intermediate quantize at scale 4 BEFORE multiply, then final
    scale-2 HALF_UP. Caller is responsible for the ``denominator > 0`` guard
    (we still defensively return ``Decimal("0")`` to avoid raising).
    """
    if denominator is None or denominator == 0:
        return Decimal("0")
    intermediate = (numerator / denominator).quantize(
        Decimal("0.0001"), rounding=ROUND_HALF_UP
    )
    result = intermediate * Decimal("100")
    return result.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _to_decimal_or_zero(v: Any) -> Decimal:
    """Coerce asyncpg numeric / None to Decimal, defaulting None → zero.

    Distinct from Rule 1 ``is not None`` discipline: here ``None`` is the
    asyncpg ``COALESCE`` fallback for an aggregate with zero matching rows,
    which we treat as zero. ``Decimal(0)`` is also acceptable upstream.
    """
    if v is None:
        return Decimal("0")
    if isinstance(v, Decimal):
        return v
    return Decimal(str(v))


# ============================================================
# Metric builders — wrap SQL + Rule 10 compute into envelope dicts
# ============================================================


async def _build_complaint_rate_metric(
    factory_id: str, start_date: date, end_date: date, conn
) -> dict:
    """N2 — restaurant complaint rate from ``restaurant_reviews`` rating proxy.

    * Real value emitted when ``total_reviews > 0`` (currently
      ``R_QINGHUAJIAO_REAL`` 青花椒 only).
    * Null + ``NO_REVIEW_DATA_FOR_CHAIN`` marker otherwise.
    * Per Q-DEC-9 default: omit ``dataAvailability`` field when value present.
    """
    row = await _query_complaint_rate(factory_id, start_date, end_date, conn)
    if row is None:
        return _empty_complaint_rate_metric()

    total_reviews = _to_decimal_or_zero(row["total_reviews"])
    if total_reviews == 0:
        return _empty_complaint_rate_metric()

    complaint_count = _to_decimal_or_zero(row["complaint_count"])
    rate_pct = _compute_rate_pct(complaint_count, total_reviews)
    return {
        "metricCode": "COMPLAINT_RATE",
        "value": _decimal_to_number(rate_pct),
        "unit": "%",
        "trend": None,
        "alertLevel": None,
    }


async def _build_dish_return_rate_metric(
    factory_id: str, start_date: date, end_date: date, conn
) -> dict:
    """N3 — dish return rate from ``fact_pos_item.return_qty`` aggregate.

    Per Q-DEC-6 F1 (✅ V20260511_03 LIVE prod): all 14 REAL chains have
    real return data post-Sub-ETL-2c. Empty branch handles "no POS rows
    for this period" — distinct from "column missing entirely" which is
    a pre-migration drift path that should not occur post-PR #331.
    """
    row = await _query_dish_return_rate(factory_id, start_date, end_date, conn)
    if row is None:
        return _empty_dish_return_rate_metric()

    total_sales_qty = _to_decimal_or_zero(row["total_sales_qty"])
    if total_sales_qty == 0:
        return _empty_dish_return_rate_metric()

    total_return_qty = _to_decimal_or_zero(row["total_return_qty"])
    rate_pct = _compute_rate_pct(total_return_qty, total_sales_qty)
    return {
        "metricCode": "DISH_RETURN_RATE",
        "value": _decimal_to_number(rate_pct),
        "unit": "%",
        "trend": None,
        "alertLevel": None,
    }


async def _build_wastage_rate_metric(
    factory_id: str, start_date: date, end_date: date, conn
) -> dict:
    """N4 — wastage cost / requisition cost rate.

    Per Q-DEC-7 G1: null + ``WASTAGE_NOT_TRACKED`` for the 14 REAL chains
    (Excel source lacks wastage column → ``wastage_row_count == 0``).

    Rule 1 explicit ``is not None`` for ``total_requisition_cost``:
    ``COALESCE(SUM(est_cost), 0)`` collapses missing rows to literal 0,
    so we additionally guard ``total_requisition_cost == 0`` to avoid
    emitting a meaningless ``0%`` when there's no denominator activity.
    """
    row = await _query_wastage_rate(factory_id, start_date, end_date, conn)
    if row is None:
        return _empty_wastage_rate_metric()

    wastage_row_count = row["wastage_row_count"]
    if wastage_row_count is None or wastage_row_count == 0:
        return _empty_wastage_rate_metric()

    total_requisition_cost = _to_decimal_or_zero(row["total_requisition_cost"])
    if total_requisition_cost == 0:
        return _empty_wastage_rate_metric()

    total_wastage_cost = _to_decimal_or_zero(row["total_wastage_cost"])
    rate_pct = _compute_rate_pct(total_wastage_cost, total_requisition_cost)
    return {
        "metricCode": "WASTAGE_RATE",
        "value": _decimal_to_number(rate_pct),
        "unit": "%",
        "trend": None,
        "alertLevel": None,
    }


async def _build_return_rate_ranking(
    factory_id: str, start_date: date, end_date: date, conn
) -> list:
    """Per-product return-rate ranking envelope (analysisType=defect).

    Each row contains ``rank`` (1-based), ``productId``, ``productName``,
    ``returnRate`` (Rule 10 quantize), ``totalSalesQty``, ``totalReturnQty``.
    Empty list when no products qualify.
    """
    rows = await _query_return_rate_ranking(
        factory_id, start_date, end_date, conn
    )
    ranking = []
    for idx, row in enumerate(rows, start=1):
        total_sales = _to_decimal_or_zero(row["total_sales_qty"])
        total_returns = _to_decimal_or_zero(row["total_return_qty"])
        rate_pct = _compute_rate_pct(total_returns, total_sales)
        ranking.append({
            "rank": idx,
            "productId": row["product_id"],
            "productName": row["product_name"],
            "returnRate": _decimal_to_number(rate_pct),
            "totalSalesQty": _decimal_to_number(total_sales),
            "totalReturnQty": _decimal_to_number(total_returns),
        })
    return ranking


def _restaurant_quality_overview(
    factory_id: str,
    start_date: date,
    end_date: date,
    metrics: list,
) -> dict:
    """analysisType=overview body per spec §1.4 — {summary, kpis, recentChanges}.

    ``recentChanges`` stays empty: trend computation requires a prior
    period comparison and is explicitly deferred. N1/N4 are always null
    for the 14 REAL chains so trend is irrelevant there; N2/N3 trend
    computation is a Phase 2D scope extension.
    """
    return {
        "summary": f"Restaurant quality analytics ({factory_id})",
        "kpis": metrics,
        "recentChanges": [],
    }


# ============================================================
# Dispatch shells — factory branch still raises; restaurant branch live.
# ============================================================


async def _factory_quality_dispatch(
    factory_id: str,
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> dict:
    """Factory-tenant quality analysis dispatcher (Phase 2D placeholder).

    AGGRESSIVE-REVISED scope (Phase 2D Sub-B impl spec §3.2): returns a
    per-``analysisType`` empty envelope mirroring the Java factory response
    shape, with the top-level ``dataAvailability`` marker
    ``FACTORY_SILVER_PHASE_2D_PENDING`` to communicate "Phase 2D
    placeholder, not real data". This unblocks frontend rendering while
    the Silver-layer real-DB impl is pending the Phase 2D PR-A/B/C/D
    chain (factory ``fact_quality_inspection`` / ``fact_quality_defect`` /
    ``fact_rework_record`` / ``fact_disposal_record`` /
    ``fact_customer_complaint`` migrations).

    Phase 2D scope (per Sub-B spec §2): 1:1 port of Java
    ``QualityAnalysisServiceImpl`` mock — 4-metric quality family
    (FPY / DEFECT_RATE / REWORK_RATE / QUALITY_COST_RATE) + 7 method
    entry points + LinkedHashMap charts/rankings. The signature
    ``(factory_id, start_date, end_date, analysis_type)`` is locked so
    the Silver-layer port can drop in without router-level churn.

    Envelope shape per Subagent A audit of Java
    ``SmartBIAnalysisController.getQualityAnalysis`` lines 119-152:

    * ``overview`` (or None): ``DashboardResponse`` empty envelope
      (period/dates/kpiCards/rankings/charts/aiInsights/recommendations/
      suggestions/generatedAt/lastUpdated/fromCache/cacheExpireAt).
    * ``fpy``: ``{startDate, endDate, metrics: [], trendChart: {}}``.
    * ``defect``: ``{startDate, endDate, ranking: [], paretoChart: {}}``.
    * ``rework``: ``{startDate, endDate, metrics: [], costChart: {}}``.

    Phase-2D-specific deviation from Java parity: a top-level
    ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` is added to
    every variant. The Java factory branch today emits no such field —
    this stub explicitly signals placeholder state. The empty
    ``aiInsights`` / ``suggestions`` lists in the overview variant also
    deviate from Java's empty-data path (which populates a "暂无质量数据"
    warning); the ``dataAvailability`` marker is the unambiguous signal
    here.
    """
    rows = await factory_gold._query_factory_batch_daily_gold(
        factory_id, start_date, end_date
    )
    if not rows:
        return _factory_quality_placeholder(start_date, end_date, analysis_type)

    now_iso = _java_isoformat(datetime.now())
    summary = _calculate_factory_quality_summary(rows)
    fpy_metrics = _build_factory_quality_fpy_metrics(summary)
    trend_chart = _build_factory_quality_trend_chart(rows)
    defect_ranking = _build_factory_quality_defect_ranking(rows)
    pareto_chart = _build_factory_quality_pareto_chart(rows)
    cost_chart = _build_factory_quality_cost_chart(summary)

    base: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
    }

    if analysis_type == "fpy":
        return {**base, "metrics": fpy_metrics, "trendChart": trend_chart}

    if analysis_type == "defect":
        return {**base, "ranking": defect_ranking, "paretoChart": pareto_chart}

    if analysis_type == "rework":
        return {
            **base,
            "metrics": _build_factory_quality_rework_metrics(summary),
            "costChart": cost_chart,
        }

    kpi_cards = [
        factory_gold._factory_kpi_card(
            m["metricCode"],
            m["metricName"],
            factory_gold._to_decimal_or_none(m["value"]),
            m["unit"],
        )
        for m in fpy_metrics
    ]
    return {
        "period": "CUSTOM",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "kpiCards": kpi_cards,
        "rankings": {"defect_type": defect_ranking, "product_line": []},
        "charts": {
            "quality_trend": trend_chart,
            "defect_pareto": pareto_chart,
            "quality_cost_distribution": cost_chart,
            "product_line_quality": {},
        },
        "aiInsights": [],
        "recommendations": [],
        "suggestions": [],
        "generatedAt": now_iso,
        "lastUpdated": now_iso,
        "fromCache": False,
        "cacheExpireAt": None,
    }


async def _restaurant_quality_dispatch(
    factory_id: str,
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> Any:
    """Restaurant-tenant quality analysis (N1+N2+N3+N4 per spec §3).

    analysisType branches (spec §3.6 — mirror Java controller line 119-152
    dispatch but reduced to restaurant 4-metric envelope per PR #330 §2.2):

    * ``fpy`` / omitted (None): metrics + ``trendChart: None`` (per-metric
      trend deferred to Phase 2D — N1/N4 always null anyway).
    * ``defect``: per-product return-rate ranking + ``paretoChart: None``
      (restaurants have no defect Pareto chart).
    * ``rework``: metrics + ``costChart: None`` (wastage distribution
      chart deferred to Phase 2D).
    * Anything else (e.g. ``overview``): ``{summary, kpis, recentChanges}``.

    Pool acquisition is internal: the router contract is the 4-arg
    signature ``(factory_id, start_date, end_date, analysis_type)``. The
    SmartBI pool is acquired via ``get_pg_pool`` because the source
    tables live in ``smartbi_db``. The cretas_db pool was already used by
    the router for tenant detection and is unrelated to these queries.
    """
    n1 = _build_food_safety_incident_metric()

    pool = None
    try:
        from smartbi.config import get_pg_pool  # type: ignore

        pool = await get_pg_pool()
    except Exception as e:
        logger.warning(
            "[analysis_quality] smartbi pool acquisition failed factory=%s: %s",
            factory_id,
            e,
        )

    if pool is None:
        n2 = _empty_complaint_rate_metric()
        n3 = _empty_dish_return_rate_metric()
        n4 = _empty_wastage_rate_metric()
        ranking: list = []
    else:
        async with pool.acquire() as conn:
            n2 = await _build_complaint_rate_metric(
                factory_id, start_date, end_date, conn
            )
            n3 = await _build_dish_return_rate_metric(
                factory_id, start_date, end_date, conn
            )
            n4 = await _build_wastage_rate_metric(
                factory_id, start_date, end_date, conn
            )
            if analysis_type == "defect":
                ranking = await _build_return_rate_ranking(
                    factory_id, start_date, end_date, conn
                )
            else:
                ranking = []

    metrics = [n1, n2, n3, n4]

    result: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "tenantType": "RESTAURANT",
    }

    if analysis_type == "fpy" or analysis_type is None:
        result["metrics"] = metrics
        result["trendChart"] = None
    elif analysis_type == "defect":
        result["ranking"] = ranking
        result["paretoChart"] = None
    elif analysis_type == "rework":
        result["metrics"] = metrics
        result["costChart"] = None
    else:
        result["overview"] = _restaurant_quality_overview(
            factory_id, start_date, end_date, metrics
        )

    return wrap_response(result)


# ============================================================
# Router entry — Option A polymorphic envelope dispatch (Q-DEC-8).
# Mirrors Java SmartBIAnalysisController.getQualityAnalysis 119-152.
# ============================================================


@router.get("/api/mobile/{factory_id}/smart-bi/analysis/quality")
async def get_quality_analysis(
    factory_id: str,
    startDate: date = Query(...),
    endDate: date = Query(...),
    analysisType: Optional[str] = Query(
        None,
        description="fpy / defect / rework (omit for overview) — Sub-B spec §1.1",
    ),
    auth: AuthContext = Depends(require_analytics_read),
) -> Any:
    """Quality analysis polymorphic endpoint (Q-DEC-8 Option A).

    Single URL serves both factory and restaurant tenants. Tenant-type
    discrimination happens server-side via ``cretas_db.factories.type``
    lookup mirroring Java ``SmartBIServiceImpl.isRestaurantTenant``.

    Per chat-B1 + chat-B2 ship 2026-05-12: restaurant branch is live;
    factory branch now returns an empty envelope marked
    ``FACTORY_SILVER_PHASE_2D_PENDING`` pending the Phase 2D Silver-layer
    real-DB impl (PR-A/B/C/D chain).
    """
    pool = None
    try:
        from smartbi.config import get_cretas_pool  # type: ignore

        pool = await get_cretas_pool()
    except Exception as e:
        logger.warning(
            "[analysis_quality] cretas_db pool acquisition failed factory=%s: %s",
            factory_id,
            e,
        )

    if pool is None:
        # Defensive: when pool is missing, mirror Java predicate's
        # repository-failure path (return false → factory branch). The
        # factory dispatcher now returns the Phase 2D empty envelope
        # rather than raising.
        tenant = TenantType.FACTORY
    else:
        async with pool.acquire() as conn:
            tenant = await get_tenant_type(factory_id, conn)

    if tenant.is_restaurant_tenant:
        # Restaurant dispatch already returns a wrap_response() envelope.
        envelope = await _restaurant_quality_dispatch(
            factory_id, startDate, endDate, analysisType
        )
        if isinstance(envelope, dict):
            strip_price_for_role(envelope.get("data"), auth.role)
        return envelope
    # Factory dispatch returns the raw inner dict per its Phase 2D contract
    # (test_factory_stub.py locks the shape). Wrap at the router boundary so
    # HTTP responses mirror Java ``ResponseEntity.ok(ApiResponse.success(result))``
    # — fixes R1 P2 finding 3 (asymmetric envelope between factory/restaurant
    # branches; factory previously emitted raw dict, restaurant emitted envelope).
    raw = await _factory_quality_dispatch(
        factory_id, startDate, endDate, analysisType
    )
    return wrap_response(strip_price_for_role(raw, auth.role))
