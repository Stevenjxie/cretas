"""Phase 2B T6.6 ``/analysis/production`` endpoint port.

chat-A1 Wave 1 shipped the skeleton + factory deferral (PR #350). chat-A2
Wave 2 (this commit) implements the restaurant branch per T6.6 Phase B
Sub-A spec §3 + Q4/Q5 impl spec §3 + Q-DEC-8 Option A envelope.

Per organizer 2026-05-12 Option B decision the **factory branch remains
deferred to Phase 2D** (factory Silver schema migration pending).

Restaurant branch returns a 3-metric envelope (M1 KITCHEN_STATION_UTILIZATION,
M2 AVG_PREP_TIME, M3 TABLE_TURNOVER_RATE proxy) per
``2026-05-12-t6-6-restaurant-semantics-decision.md`` §1.

* M1 (Q-DEC-1 = A1): always-null + ``MISSING_KITCHEN_STATION_DATA``
  — no kitchen-station schema exists.
* M2 (Q-DEC-2 = B1): always-null + ``MISSING_ORDER_TIMESTAMP_SPLIT``
  — ``fact_pos_transaction`` has a single ``time`` column, no order/serve split.
* M3 (Q-DEC-3 = C1): emits ``value=null`` (true turnover requires
  ``dim_store.table_count`` which is not populated) + nested
  ``proxyMetric`` (BILLS_PER_STORE_PER_DAY) computed from
  ``fact_pos_transaction`` aggregates. Marker ``PROXY_AS_BILLS_PER_STORE``.

Java reference (kept for Phase 2D factory port):

* Controller: ``SmartBIAnalysisController.getProductionAnalysis`` lines 80-115
* Service (factory branch only — mock; KEEP per PR #178):
  ``ProductionAnalysisServiceImpl``
* Tenant predicate: ``SmartBIServiceImpl.isRestaurantTenant`` lines 432-441

Specs:

* Sub-A impl: docs/superpowers/specs/2026-05-12-t6-6-sub-a-production-impl-spec.md §3
* Q4 decisions: docs/superpowers/specs/2026-05-12-t6-6-restaurant-semantics-decision.md (PR #330)
* Q4 module shape: docs/superpowers/specs/2026-05-11-q4-q5-restaurant-analysis-impl-spec.md
* Factory port detail (Phase 2D): docs/superpowers/specs/2026-05-09-t6-6-production-port-detail.md
"""
from __future__ import annotations

import logging
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Optional

from fastapi import APIRouter, Depends, Query

from smartbi_compat._rbac_role import require_analytics_read
from smartbi_compat._rbac_strip import strip_price_for_role
from smartbi_compat.api.analysis_finance import _decimal_to_number
from smartbi_compat.auth import AuthContext
from smartbi_compat.schema_compat import _java_isoformat, wrap_response
from smartbi_compat.tenant import TenantType, get_tenant_type

logger = logging.getLogger(__name__)
router = APIRouter()


# ============================================================
# Controlled ``dataAvailability`` vocabulary (PR #330 §3.4)
# ============================================================
#
# NO additional markers may be introduced without organizer sign-off
# (would require PR #330 §3.4 amendment). chat-A3 wiring audit enforces
# this rule by grepping for any unknown string literal in this module's
# ``dataAvailability`` positions.

_AVAILABILITY_MISSING_KITCHEN_STATION = "MISSING_KITCHEN_STATION_DATA"
_AVAILABILITY_MISSING_ORDER_TIMESTAMP = "MISSING_ORDER_TIMESTAMP_SPLIT"
_AVAILABILITY_PROXY_BILLS = "PROXY_AS_BILLS_PER_STORE"


# ============================================================
# Stub marker (Phase 2D consumes these strings in messages)
# ============================================================
#
# AGGRESSIVE-REVISED scope (Phase 2D Sub-A impl spec
# docs/superpowers/specs/2026-05-11-phase-2d-silver-migration-and-factory-impl-spec.md §3.1):
# ``_factory_production_dispatch`` no longer raises NotImplementedError — it
# now returns an empty envelope mirroring the Java factory shape with the
# top-level ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` marker so
# the frontend can render a "data pending" state instead of hitting a 500.
# The Silver-layer port (real-DB implementation) is the next chain
# Phase 2D PR-A/B/C/D.
#
# ``_FACTORY_BRANCH_DEFERRED_MSG`` is kept as a documentation constant —
# other modules / log messages still reference the string. The dispatch
# function no longer raises with it.

_FACTORY_BRANCH_DEFERRED_MSG = (
    "Factory production analysis returns an empty envelope marked "
    "FACTORY_SILVER_PHASE_2D_PENDING pending the factory Silver schema "
    "migration (fact_production_batch / fact_equipment_event / "
    "fact_quality_inspection). chat-A1 dispatch 2026-05-12 Option B: "
    "_JavaRandom mock-mirror fallback rejected (Q1 amendment §1)."
)


# Top-level envelope marker emitted by ``_factory_production_dispatch`` so the
# frontend can distinguish "Phase 2D placeholder, no real data" from a normal
# data-bearing factory response. Tests import this constant to assert every
# stub response carries the exact marker string.
FACTORY_PHASE_2D_PENDING_MARKER = "FACTORY_SILVER_PHASE_2D_PENDING"


_DECIMAL_SCALE_4 = Decimal("0.0001")
_DECIMAL_SCALE_2 = Decimal("0.01")


def _to_decimal_or_zero(value: Any) -> Decimal:
    if value is None:
        return Decimal("0")
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def _to_decimal_or_none(value: Any) -> Optional[Decimal]:
    if value is None:
        return None
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def _rate_pct(numerator: Decimal, denominator: Decimal) -> Optional[Decimal]:
    if denominator is None or denominator == 0:
        return None
    intermediate = (numerator / denominator).quantize(
        _DECIMAL_SCALE_4, rounding=ROUND_HALF_UP
    )
    return (intermediate * Decimal("100")).quantize(
        _DECIMAL_SCALE_2, rounding=ROUND_HALF_UP
    )


def _weighted_avg_pct(rows: list, value_key: str, weight_key: str) -> Optional[Decimal]:
    weighted_sum = Decimal("0")
    total_weight = Decimal("0")
    for row in rows:
        value = _to_decimal_or_none(row[value_key])
        if value is None:
            continue
        weight = _to_decimal_or_zero(row[weight_key])
        if weight == 0:
            continue
        weighted_sum += value * weight
        total_weight += weight
    if total_weight == 0:
        return None
    return (weighted_sum / total_weight).quantize(
        _DECIMAL_SCALE_2, rounding=ROUND_HALF_UP
    )


def _decimal_metric_value(value: Optional[Decimal]) -> Any:
    if value is None:
        return None
    return _decimal_to_number(value.quantize(_DECIMAL_SCALE_2, rounding=ROUND_HALF_UP))


def _format_metric_value(value: Optional[Decimal], unit: str) -> Optional[str]:
    if value is None:
        return None
    value = value.quantize(_DECIMAL_SCALE_2, rounding=ROUND_HALF_UP)
    if unit == "%":
        return f"{value}%"
    if unit == "元":
        return f"{value}"
    return str(value)


def _factory_metric(
    code: str,
    name: str,
    value: Optional[Decimal],
    unit: str,
    alert_level: Optional[str] = None,
    description: Optional[str] = None,
) -> dict:
    return {
        "metricCode": code,
        "metricName": name,
        "value": _decimal_metric_value(value),
        "formattedValue": _format_metric_value(value, unit),
        "unit": unit,
        "alertLevel": alert_level,
        "description": description,
    }


def _factory_kpi_card(
    key: str,
    title: str,
    value: Optional[Decimal],
    unit: str,
    status: str = "green",
) -> dict:
    return {
        "key": key,
        "title": title,
        "value": _format_metric_value(value, unit),
        "rawValue": _decimal_metric_value(value),
        "unit": unit,
        "status": status,
    }


def _alert_low(value: Optional[Decimal], red: Decimal, yellow: Decimal) -> Optional[str]:
    if value is None:
        return None
    if value < red:
        return "RED"
    if value < yellow:
        return "YELLOW"
    return "GREEN"


def _alert_high(value: Optional[Decimal], red: Decimal, yellow: Decimal) -> Optional[str]:
    if value is None:
        return None
    if value > red:
        return "RED"
    if value > yellow:
        return "YELLOW"
    return "GREEN"


def _calculate_factory_production_rates(rows: list) -> dict:
    total_planned = sum(_to_decimal_or_zero(r["total_planned_qty"]) for r in rows)
    total_actual = sum(_to_decimal_or_zero(r["total_actual_qty"]) for r in rows)
    total_good = sum(_to_decimal_or_zero(r["total_good_qty"]) for r in rows)
    total_cost = sum(_to_decimal_or_zero(r["total_cost"]) for r in rows)
    batch_count = sum(_to_decimal_or_zero(r["batch_count"]) for r in rows)

    availability = _rate_pct(total_actual, total_planned)
    performance = _weighted_avg_pct(rows, "avg_yield_rate", "batch_count")
    quality_rate = _rate_pct(total_good, total_actual)
    if availability is not None and performance is not None and quality_rate is not None:
        oee = ((availability * performance * quality_rate) / Decimal("10000")).quantize(
            _DECIMAL_SCALE_2, rounding=ROUND_HALF_UP
        )
    else:
        oee = None

    return {
        "rowCount": len(rows),
        "productTypeCount": len({r["product_type_id"] for r in rows if r["product_type_id"] is not None}),
        "dayCount": len({r["stat_date"] for r in rows if r["stat_date"] is not None}),
        "batchCount": batch_count,
        "totalPlannedQty": total_planned,
        "totalActualQty": total_actual,
        "totalGoodQty": total_good,
        "totalCost": total_cost,
        "availability": availability,
        "performance": performance,
        "qualityRate": quality_rate,
        "oee": oee,
    }


def _build_factory_oee_metrics(summary: dict) -> list:
    return [
        _factory_metric(
            "OEE",
            "综合设备效率 (OEE)",
            summary["oee"],
            "%",
            _alert_low(summary["oee"], Decimal("65"), Decimal("85")),
            "OEE = 可用性 x 性能 x 质量",
        ),
        _factory_metric(
            "AVAILABILITY",
            "可用性",
            summary["availability"],
            "%",
            _alert_low(summary["availability"], Decimal("80"), Decimal("90")),
        ),
        _factory_metric(
            "PERFORMANCE",
            "性能",
            summary["performance"],
            "%",
            _alert_low(summary["performance"], Decimal("75"), Decimal("90")),
        ),
        _factory_metric(
            "QUALITY_RATE",
            "质量率",
            summary["qualityRate"],
            "%",
            _alert_low(summary["qualityRate"], Decimal("95"), Decimal("98")),
        ),
    ]


def _group_rows_by_date(rows: list) -> list:
    grouped: dict[Any, list] = {}
    for row in rows:
        grouped.setdefault(row["stat_date"], []).append(row)
    return [grouped[k] for k in sorted(grouped.keys())]


def _build_factory_oee_trend_chart(rows: list) -> dict:
    data = []
    for day_rows in _group_rows_by_date(rows):
        summary = _calculate_factory_production_rates(day_rows)
        stat_date = day_rows[0]["stat_date"]
        data.append({
            "date": stat_date.isoformat() if stat_date is not None else None,
            "oee": _decimal_metric_value(summary["oee"]),
            "availability": _decimal_metric_value(summary["availability"]),
            "performance": _decimal_metric_value(summary["performance"]),
            "quality": _decimal_metric_value(summary["qualityRate"]),
        })
    return {
        "chartType": "LINE",
        "title": "OEE 趋势",
        "xAxisField": "date",
        "yAxisField": "oee",
        "seriesField": "metric",
        "data": data,
        "options": {"showLegend": True, "multiLine": True, "yAxisMax": 100},
    }


def _build_factory_product_ranking(rows: list) -> list:
    grouped: dict[Any, list] = {}
    for row in rows:
        grouped.setdefault(row["product_type_id"], []).append(row)
    ranking = []
    for product_type_id, product_rows in grouped.items():
        if product_type_id is None:
            continue
        summary = _calculate_factory_production_rates(product_rows)
        value = summary["availability"]
        ranking.append({
            "name": product_type_id,
            "value": _decimal_metric_value(value),
            "target": 100,
            "completionRate": _decimal_metric_value(value),
            "alertLevel": _alert_low(value, Decimal("80"), Decimal("90")),
        })
    ranking.sort(key=lambda item: item["value"] if item["value"] is not None else -1, reverse=True)
    for idx, item in enumerate(ranking, start=1):
        item["rank"] = idx
    return ranking


def _build_factory_production_line_comparison(rows: list) -> dict:
    data = []
    for item in _build_factory_product_ranking(rows):
        data.append({
            "productionLine": item["name"],
            "oee": item["value"],
            "availability": item["value"],
            "performance": None,
            "quality": None,
        })
    return {
        "chartType": "BAR",
        "title": "产品 OEE 对比",
        "xAxisField": "productionLine",
        "yAxisField": "oee",
        "seriesField": "metric",
        "data": data,
        "options": {"showLegend": True, "grouped": True},
    }


async def _query_factory_batch_daily_gold(
    factory_id: str,
    start_date: date,
    end_date: date,
) -> Optional[list]:
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_factory_batch_daily_gold: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    try:
        from smartbi.config import get_pg_pool  # type: ignore

        pool = await get_pg_pool()
    except Exception as e:
        logger.warning(
            "[analysis_production] smartbi pool acquisition failed factory=%s: %s",
            factory_id,
            e,
        )
        return None

    if pool is None:
        return None

    try:
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", factory_id
                )
                return await conn.fetch(
                    """
                    SELECT
                        stat_date,
                        product_type_id,
                        batch_count,
                        total_planned_qty,
                        total_actual_qty,
                        total_good_qty,
                        avg_yield_rate,
                        total_cost
                    FROM agg_factory_batch_daily
                    WHERE factory_id = $1
                      AND stat_date BETWEEN $2 AND $3
                      AND product_type_id IS NOT NULL
                    ORDER BY stat_date, product_type_id
                    """,
                    factory_id,
                    start_date,
                    end_date,
                )
    except Exception as e:
        logger.warning(
            "[analysis_production] factory gold query failed factory=%s: %s",
            factory_id,
            e,
        )
        return None


def _factory_production_placeholder(
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

    if analysis_type == "oee":
        return {**base, "metrics": [], "trendChart": {}}

    if analysis_type == "efficiency":
        return {**base, "metrics": [], "ranking": []}

    if analysis_type == "equipment":
        return {
            **base,
            "metrics": [],
            "ranking": [],
            "downtimeChart": {},
        }

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


# ============================================================
# Restaurant metric builders
# ============================================================


def _build_kitchen_station_utilization_metric() -> dict:
    """M1 — Kitchen Station Utilization. Always null per Q-DEC-1 = A1.

    Spec §3.1: no ``dim_kitchen_station`` table or order-station mapping
    exists in the Silver layer. Honest null emission with controlled
    vocabulary marker.
    """
    return {
        "metricCode": "KITCHEN_STATION_UTILIZATION",
        "value": None,
        "unit": "%",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_MISSING_KITCHEN_STATION,
    }


def _build_avg_prep_time_metric() -> dict:
    """M2 — Average Prep Time per Order. Always null per Q-DEC-2 = B1.

    Spec §3.2: ``fact_pos_transaction`` has only a single ``time``
    column — no 下单/上菜 split. Honest null emission with controlled
    vocabulary marker.
    """
    return {
        "metricCode": "AVG_PREP_TIME",
        "value": None,
        "unit": "minutes",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_MISSING_ORDER_TIMESTAMP,
    }


def _empty_table_turnover_proxy() -> dict:
    """M3 envelope with null proxyMetric (no data available).

    Emitted when the SmartBI pool is unavailable OR the period contains
    zero bills / stores / days. The outer ``value`` stays null (true
    turnover requires ``dim_store.table_count`` which is unpopulated);
    only the nested ``proxyMetric`` distinguishes "have data" vs "no data".
    """
    return {
        "metricCode": "TABLE_TURNOVER_RATE",
        "value": None,
        "unit": "turns_per_day",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_PROXY_BILLS,
        "proxyMetric": {
            "metricCode": "BILLS_PER_STORE_PER_DAY",
            "value": None,
            "unit": "bills_per_store_per_day",
            "trend": None,
            "alertLevel": None,
        },
    }


async def _query_pos_transaction_aggregate(
    factory_id: str, start_date: date, end_date: date, conn
) -> Optional[dict]:
    """Aggregate bill_count / store_count / day_count for M3 proxy.

    Rule 6 precondition: start_date / end_date required. asyncpg would
    silently convert ``None → NULL`` and ``BETWEEN NULL AND NULL`` returns
    zero rows — caller would see "no data" when the real bug is a null
    parameter.
    """
    if start_date is None or end_date is None:
        raise ValueError(
            "_query_pos_transaction_aggregate: start_date/end_date required "
            f"(got start_date={start_date}, end_date={end_date})"
        )
    return await conn.fetchrow(
        """
        SELECT
            COUNT(*) AS bill_count,
            COUNT(DISTINCT store_id) AS store_count,
            COUNT(DISTINCT date) AS day_count
        FROM fact_pos_transaction
        WHERE factory_id = $1
          AND date BETWEEN $2 AND $3
        """,
        factory_id,
        start_date,
        end_date,
    )


async def _compute_table_turnover_proxy(
    factory_id: str, start_date: date, end_date: date, conn
) -> dict:
    """M3 — Table Turnover Rate proxy (Q-DEC-3 = C1).

    Proxy: ``bills_per_store_per_day = bill_count / (store_count * day_count)``.
    True M3 stays null because ``dim_store.table_count`` is not populated.

    Rule 10 — mirror Java ``divide(divisor, 4, HALF_UP).multiply(K)``:
    intermediate quantize at scale 4 before final scale-2 quantize.
    """
    row = await _query_pos_transaction_aggregate(
        factory_id, start_date, end_date, conn
    )

    if row is None:
        return _empty_table_turnover_proxy()

    bill_count = row["bill_count"]
    store_count = row["store_count"]
    day_count = row["day_count"]

    if (
        bill_count is None
        or store_count is None
        or day_count is None
        or bill_count == 0
        or store_count == 0
        or day_count == 0
    ):
        return _empty_table_turnover_proxy()

    proxy_value = Decimal(bill_count) / (Decimal(store_count) * Decimal(day_count))
    proxy_q4 = proxy_value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    proxy_q2 = proxy_q4.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    return {
        "metricCode": "TABLE_TURNOVER_RATE",
        "value": None,
        "unit": "turns_per_day",
        "trend": None,
        "alertLevel": None,
        "dataAvailability": _AVAILABILITY_PROXY_BILLS,
        "proxyMetric": {
            "metricCode": "BILLS_PER_STORE_PER_DAY",
            "value": _decimal_to_number(proxy_q2),
            "unit": "bills_per_store_per_day",
            "trend": None,
            "alertLevel": None,
        },
    }


def _restaurant_production_overview(
    factory_id: str,
    start_date: date,
    end_date: date,
    metrics: list,
) -> dict:
    """analysisType=overview body per spec §1.4 — {summary, kpis, recentChanges}.

    ``recentChanges`` stays empty: trend computation requires a prior
    period and is explicitly deferred per Q-DEC-1/2 (both metrics null →
    trend irrelevant) and the M3 proxy is value-stable across periods
    pending Phase 2D.
    """
    return {
        "summary": f"Restaurant production analytics ({factory_id})",
        "kpis": metrics,
        "recentChanges": [],
    }


# ============================================================
# Dispatch shells — factory branch still raises; restaurant branch live.
# ============================================================


async def _factory_production_dispatch(
    factory_id: str,
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> dict:
    """Factory-tenant production analysis dispatcher (Phase 2D placeholder).

    AGGRESSIVE-REVISED scope (Phase 2D Sub-A impl spec §3.1): returns a
    per-``analysisType`` empty envelope mirroring the Java factory response
    shape, with the top-level ``dataAvailability`` marker
    ``FACTORY_SILVER_PHASE_2D_PENDING`` to communicate "Phase 2D
    placeholder, not real data". This unblocks frontend rendering while
    the Silver-layer real-DB impl is pending the Phase 2D PR-A/B/C/D
    chain (factory ``fact_production_batch`` / ``fact_equipment_event`` /
    ``fact_quality_inspection`` migrations).

    Phase 2D scope (per Sub-A spec §2): 1:1 port of Java
    ``ProductionAnalysisServiceImpl`` mock — 4-metric OEE family
    (OEE/AVAILABILITY/PERFORMANCE/QUALITY) + 8 method entry points +
    LinkedHashMap charts/rankings. The signature
    ``(factory_id, start_date, end_date, analysis_type)`` is locked so
    the Silver-layer port can drop in without router-level churn.

    Envelope shape per Subagent A audit of Java
    ``SmartBIAnalysisController.getProductionAnalysis`` lines 80-115:

    * ``overview`` (or None): ``DashboardResponse`` empty envelope
      (period/dates/kpiCards/rankings/charts/aiInsights/recommendations/
      suggestions/generatedAt/lastUpdated/fromCache/cacheExpireAt).
    * ``oee``: ``{startDate, endDate, metrics: [], trendChart: {}}``.
    * ``efficiency``: ``{startDate, endDate, metrics: [], ranking: []}``.
    * ``equipment``: ``{startDate, endDate, metrics: [], ranking: [],
      downtimeChart: {}}``.

    Phase-2D-specific deviation from Java parity: a top-level
    ``dataAvailability = FACTORY_SILVER_PHASE_2D_PENDING`` is added to
    every variant. The Java factory branch today emits no such field —
    this stub explicitly signals placeholder state. The empty
    ``aiInsights`` / ``suggestions`` lists in the overview variant also
    deviate from Java's empty-data path (which populates a "暂无生产数据"
    warning); the ``dataAvailability`` marker is the unambiguous signal
    here.
    """
    rows = await _query_factory_batch_daily_gold(factory_id, start_date, end_date)
    if not rows:
        return _factory_production_placeholder(start_date, end_date, analysis_type)

    now_iso = _java_isoformat(datetime.now())
    summary = _calculate_factory_production_rates(rows)
    oee_metrics = _build_factory_oee_metrics(summary)
    trend_chart = _build_factory_oee_trend_chart(rows)
    ranking = _build_factory_product_ranking(rows)

    base: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
    }

    if analysis_type == "oee":
        return {**base, "metrics": oee_metrics, "trendChart": trend_chart}

    if analysis_type == "efficiency":
        efficiency_metrics = [
            _factory_metric(
                "CAPACITY_UTILIZATION",
                "产能利用率",
                summary["availability"],
                "%",
                _alert_low(summary["availability"], Decimal("80"), Decimal("90")),
            ),
            _factory_metric("CYCLE_TIME", "平均节拍时间", None, "分钟/件"),
            _factory_metric(
                "ACHIEVEMENT_RATE",
                "计划达成率",
                summary["availability"],
                "%",
                _alert_low(summary["availability"], Decimal("65"), Decimal("85")),
            ),
        ]
        return {**base, "metrics": efficiency_metrics, "ranking": ranking}

    if analysis_type == "equipment":
        return {
            **base,
            "metrics": [
                _factory_metric("EQUIPMENT_UTILIZATION", "设备利用率", None, "%"),
                _factory_metric("DOWNTIME", "总停机时间", None, "小时"),
                _factory_metric("FAILURE_COUNT", "故障次数", None, "次"),
                _factory_metric("MTBF", "平均故障间隔 (MTBF)", None, "小时"),
                _factory_metric("MTTR", "平均修复时间 (MTTR)", None, "小时"),
            ],
            "ranking": [],
            "downtimeChart": {},
        }

    kpi_cards = [
        _factory_kpi_card(m["metricCode"], m["metricName"], _to_decimal_or_none(m["value"]), m["unit"])
        for m in oee_metrics
    ]
    return {
        "period": "CUSTOM",
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "kpiCards": kpi_cards,
        "rankings": {"production_line": ranking, "equipment": []},
        "charts": {
            "oee_trend": trend_chart,
            "production_line_comparison": _build_factory_production_line_comparison(rows),
            "downtime_distribution": {},
        },
        "aiInsights": [],
        "recommendations": [],
        "suggestions": [],
        "generatedAt": now_iso,
        "lastUpdated": now_iso,
        "fromCache": False,
        "cacheExpireAt": None,
    }


async def _restaurant_production_dispatch(
    factory_id: str,
    start_date: date,
    end_date: date,
    analysis_type: Optional[str],
) -> Any:
    """Restaurant-tenant production analysis (M1+M2+M3 per spec §3).

    analysisType branches (mirror Java controller line 80-115 dispatch
    but reduced to restaurant 3-metric envelope per PR #330 §1.2):

    * ``oee`` / omitted (None): metrics + ``trendChart: None``
      (M1/M2 are always-null so trend is irrelevant; M3 proxy trend
      deferred to Phase 2D).
    * ``efficiency``: metrics + empty ``ranking`` (restaurants have no
      production-line ranking).
    * ``equipment``: empty metrics + empty ranking + ``downtimeChart: None``
      (M4 quality subcomponent omitted for restaurant per PR #330 §1.2).
    * Anything else (e.g. ``overview``): ``{summary, kpis, recentChanges}``.

    Pool acquisition is internal: the router contract is the 4-arg
    signature ``(factory_id, start_date, end_date, analysis_type)``. The
    SmartBI pool is acquired via ``get_pg_pool`` because
    ``fact_pos_transaction`` lives in ``smartbi_db`` (the cretas_db pool
    was already used by the router for tenant detection and is unrelated
    to the M3 query target).
    """
    m1 = _build_kitchen_station_utilization_metric()
    m2 = _build_avg_prep_time_metric()

    pool = None
    try:
        from smartbi.config import get_pg_pool  # type: ignore

        pool = await get_pg_pool()
    except Exception as e:
        logger.warning(
            "[analysis_production] smartbi pool acquisition failed factory=%s: %s",
            factory_id,
            e,
        )

    if pool is None:
        m3 = _empty_table_turnover_proxy()
    else:
        async with pool.acquire() as conn:
            m3 = await _compute_table_turnover_proxy(
                factory_id, start_date, end_date, conn
            )

    metrics = [m1, m2, m3]

    result: dict[str, Any] = {
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "tenantType": "RESTAURANT",
    }

    if analysis_type == "oee" or analysis_type is None:
        result["metrics"] = metrics
        result["trendChart"] = None
    elif analysis_type == "efficiency":
        result["metrics"] = metrics
        result["ranking"] = []
    elif analysis_type == "equipment":
        result["metrics"] = []
        result["ranking"] = []
        result["downtimeChart"] = None
    else:
        result["overview"] = _restaurant_production_overview(
            factory_id, start_date, end_date, metrics
        )

    return wrap_response(result)


# ============================================================
# Router entry — Option A polymorphic envelope dispatch (Q-DEC-8).
# Mirrors Java SmartBIAnalysisController.getProductionAnalysis 80-115.
# ============================================================


@router.get("/api/mobile/{factory_id}/smart-bi/analysis/production")
async def get_production_analysis(
    factory_id: str,
    startDate: date = Query(...),
    endDate: date = Query(...),
    analysisType: Optional[str] = Query(
        None,
        description="oee / efficiency / equipment / overview (omit for overview)",
    ),
    auth: AuthContext = Depends(require_analytics_read),
) -> Any:
    """Production analysis polymorphic endpoint (Q-DEC-8 Option A).

    Single URL serves both factory and restaurant tenants. Tenant-type
    discrimination happens server-side via ``cretas_db.factories.type``
    lookup mirroring Java ``SmartBIServiceImpl.isRestaurantTenant``.

    Per chat-A1 + chat-A2 ship 2026-05-12: restaurant branch is live;
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
            "[analysis_production] cretas_db pool acquisition failed factory=%s: %s",
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
        envelope = await _restaurant_production_dispatch(
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
    raw = await _factory_production_dispatch(
        factory_id, startDate, endDate, analysisType
    )
    return wrap_response(strip_price_for_role(raw, auth.role))
