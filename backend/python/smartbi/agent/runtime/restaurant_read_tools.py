"""Adapters from existing deterministic Gold reads to EvidenceEnvelope drafts.

No SQL is copied here.  Every adapter calls a repository-owned read query through
the gateway's tenant-bound connection facade and normalizes the existing return
contract.  Where an existing contract loses unit, time or coverage information,
the adapter returns only the safe subset and marks the result partial.
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal
from typing import Any, Callable, Mapping, Optional

from .contracts import (
    Coverage,
    CoverageStatus,
    EvidenceDraft,
    EvidenceFact,
    EvidenceStatus,
    EvidenceWarning,
    Freshness,
    FreshnessStatus,
    ProvenanceReference,
    TrustedExecutionContext,
)
from .descriptors import ReadToolDescriptor, restaurant_descriptors
from .gateway import ReadToolContractError
from .registry import ReadonlyToolRegistry


@dataclass(frozen=True)
class RestaurantReadSources:
    daily_trend: Callable[..., Any]
    period_comparison: Callable[..., Any]
    store_comparison: Callable[..., Any]
    top_products: Callable[..., Any]
    detect_price_anomalies: Callable[..., Any]
    order_type_breakdown: Callable[..., Any]
    meal_period_breakdown: Callable[..., Any]
    discount_summary: Callable[..., Any]
    resolve_wastage_top: Callable[..., Any]
    resolve_inventory_warning: Callable[..., Any]
    resolve_stock_shortage: Callable[..., Any]
    review_summary: Callable[..., Any]
    review_store_ranking: Callable[..., Any]
    review_dish_issues: Callable[..., Any]


def default_restaurant_sources() -> RestaurantReadSources:
    # Imports stay lazy so the contract package can be inspected/tested without
    # starting the SmartBI service or importing unrelated write clients.
    from smartbi.gold.price_anomaly import detect_price_anomalies
    from smartbi.gold.queries import (
        daily_trend,
        discount_summary,
        meal_period_breakdown,
        order_type_breakdown,
        period_comparison,
        store_comparison,
        top_products,
    )
    from smartbi.gold.restaurant_ops_router import (
        resolve_inventory_warning,
        resolve_stock_shortage,
        resolve_wastage_top,
    )
    from smartbi.gold.review_queries import (
        review_dish_issues,
        review_store_ranking,
        review_summary,
    )

    return RestaurantReadSources(
        daily_trend=daily_trend,
        period_comparison=period_comparison,
        store_comparison=store_comparison,
        top_products=top_products,
        detect_price_anomalies=detect_price_anomalies,
        order_type_breakdown=order_type_breakdown,
        meal_period_breakdown=meal_period_breakdown,
        discount_summary=discount_summary,
        resolve_wastage_top=resolve_wastage_top,
        resolve_inventory_warning=resolve_inventory_warning,
        resolve_stock_shortage=resolve_stock_shortage,
        review_summary=review_summary,
        review_store_ranking=review_store_ranking,
        review_dish_issues=review_dish_issues,
    )


class _DraftBuilder:
    def __init__(self, descriptor: ReadToolDescriptor) -> None:
        self.descriptor = descriptor
        self.facts: list[EvidenceFact] = []
        self.provenance: list[ProvenanceReference] = []
        self.warnings: list[EvidenceWarning] = []
        self._provenance_keys: dict[tuple[Any, ...], str] = {}

    def source(
        self,
        asset: str,
        *,
        source_type: str = "GOLD",
        source_upload_id: Any = None,
        field_name: Optional[str] = None,
    ) -> str:
        key = (asset, source_type, source_upload_id, field_name)
        existing = self._provenance_keys.get(key)
        if existing:
            return existing
        ref_id = f"p-{len(self.provenance) + 1}"
        self._provenance_keys[key] = ref_id
        self.provenance.append(
            ProvenanceReference(
                ref_id=ref_id,
                source_type=source_type,
                asset=asset,
                query_id=self.descriptor.name,
                source_version=self.descriptor.digest,
                source_upload_id=(
                    str(source_upload_id) if source_upload_id is not None else None
                ),
                field_name=field_name,
            )
        )
        return ref_id

    def fact(
        self,
        metric: str,
        value: Any,
        *,
        unit: Optional[str],
        dimensions: Optional[Mapping[str, Any]],
        provenance_refs: tuple[str, ...],
        freshness: Freshness,
        coverage: Coverage,
        semantics: str,
        status: EvidenceStatus = EvidenceStatus.OK,
        scale: Optional[int] = None,
        quality_flags: tuple[str, ...] = (),
    ) -> None:
        self.facts.append(
            EvidenceFact.numeric(
                fact_id=f"f-{len(self.facts) + 1}",
                metric=metric,
                value=value,
                unit=unit,
                scale=scale,
                dimensions=dimensions,
                status=status,
                semantics=semantics,
                provenance_refs=provenance_refs,
                freshness=freshness,
                coverage=coverage,
                quality_flags=quality_flags,
            )
        )

    def warning(
        self,
        code: str,
        message: str,
        *,
        severity: str = "WARNING",
        blocks: tuple[str, ...] = (),
    ) -> None:
        self.warnings.append(
            EvidenceWarning(
                code=code,
                severity=severity,
                message=message,
                blocks_conclusions=blocks,
            )
        )

    def draft(
        self,
        *,
        status: EvidenceStatus,
        requested_window: Optional[Mapping[str, str]],
        effective_window: Optional[Mapping[str, str]],
        grain: Optional[str] = None,
        normalized_parameters: Optional[Mapping[str, Any]] = None,
        rows_truncated: int = 0,
    ) -> EvidenceDraft:
        return EvidenceDraft(
            status=status,
            requested_window=requested_window,
            effective_window=effective_window,
            grain=grain or self.descriptor.time_grain,
            normalized_parameters=normalized_parameters or {},
            facts=tuple(self.facts),
            provenance=tuple(self.provenance),
            warnings=tuple(self.warnings),
            rows_truncated=rows_truncated,
        )


class RestaurantReadToolAdapter:
    def __init__(
        self,
        sources: RestaurantReadSources,
        *,
        today_factory: Callable[[], date] = date.today,
    ) -> None:
        self.sources = sources
        self.today_factory = today_factory
        self._handlers = {
            "restaurant_revenue_trend_read.v1": self._revenue_trend,
            "restaurant_period_comparison_read.v1": self._period_comparison,
            "restaurant_store_performance_read.v1": self._store_performance,
            "restaurant_dish_margin_mix_read.v1": self._dish_margin_mix,
            "restaurant_cost_movement_read.v1": self._cost_movement,
            "restaurant_channel_discount_mix_read.v1": self._channel_discount_mix,
            "restaurant_waste_anomaly_read.v1": self._waste_anomaly,
            "restaurant_inventory_risk_read.v1": self._inventory_risk,
            "restaurant_stocktaking_variance_read.v1": self._stocktaking_variance,
            "restaurant_review_signal_read.v1": self._review_signal,
        }

    async def execute(
        self,
        pool: Any,
        context: TrustedExecutionContext,
        parameters: Mapping[str, Any],
        descriptor: ReadToolDescriptor,
    ) -> EvidenceDraft:
        try:
            handler = self._handlers[descriptor.name]
        except KeyError as exc:
            raise ReadToolContractError(f"no adapter for {descriptor.name}") from exc
        return await handler(pool, context, parameters, descriptor)

    async def _revenue_trend(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        grain = str(params.get("grain", "DAY")).upper()
        if grain not in {"DAY", "WEEK", "MONTH"}:
            raise ReadToolContractError("grain must be DAY, WEEK or MONTH")
        requested_days = (end - start).days + 1
        if grain == "DAY" and requested_days > 60:
            raise ReadToolContractError(
                "DAY grain supports at most 60 requested days; use WEEK or MONTH"
            )
        raw = await self.sources.daily_trend(pool, ctx.factory_id, (start, end))
        _assert_tenant_echo(raw, ctx)
        builder = _DraftBuilder(descriptor)
        source = builder.source("agg_daily")
        raw_points = list(raw.get("points") or [])
        points = _aggregate_trend_points(raw_points, grain)
        point_budget = {"DAY": 60, "WEEK": 54, "MONTH": 13}[grain]
        if len(points) > point_budget or len(points) > descriptor.limits.max_rows:
            raise ReadToolContractError(
                f"{grain} aggregation exceeds its {point_budget}-point budget"
            )
        data_through = max((p.get("date") for p in raw_points if p.get("date")), default=None)
        freshness = _known_date_or_unknown(
            data_through, "maximum returned agg_daily date inside the requested window"
        )
        coverage = Coverage(
            status=(
                CoverageStatus.COMPLETE
                if len(raw_points) == requested_days
                else CoverageStatus.UNKNOWN
            ),
            numerator=min(len(raw_points), requested_days),
            denominator=requested_days,
            basis="days with returned activity; omitted dates may be closed days or missing data",
            missing_reasons=(
                () if len(raw_points) == requested_days else ("MISSING_DATE_SEMANTICS_UNKNOWN",)
            ),
        )
        for point in points:
            dims = {
                "periodStart": point["observedStart"],
                "periodEnd": point["observedEnd"],
            }
            builder.fact(
                "revenue",
                point.get("revenue"),
                unit="CNY",
                scale=2,
                dimensions=dims,
                provenance_refs=(source,),
                freshness=freshness,
                coverage=coverage,
                semantics="SUM(agg_daily.net_amount) by date",
            )
            builder.fact(
                "billCount",
                point.get("bill_count"),
                unit="COUNT",
                scale=0,
                dimensions=dims,
                provenance_refs=(source,),
                freshness=freshness,
                coverage=coverage,
                semantics="SUM(agg_daily.bill_count) by date",
            )
        status = EvidenceStatus.OK
        if not raw_points:
            status = EvidenceStatus.EMPTY
            builder.warning(
                "EMPTY_IS_NOT_ZERO",
                "No activity rows were returned; this is not evidence of zero revenue",
            )
        elif coverage.status is not CoverageStatus.COMPLETE:
            status = EvidenceStatus.PARTIAL
        if coverage.status is CoverageStatus.UNKNOWN:
            builder.warning(
                "MISSING_DATES_AMBIGUOUS",
                "Gold omits dates without activity and cannot distinguish closure from missing data",
            )
        return builder.draft(
            status=status,
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            grain=grain,
            normalized_parameters={**_window_dict(start, end), "grain": grain},
        )

    async def _period_comparison(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        raw = await self.sources.period_comparison(pool, ctx.factory_id, start, end)
        _assert_tenant_echo(raw, ctx, optional=True)
        builder = _DraftBuilder(descriptor)
        revenue_ref = builder.source("agg_daily")
        cost_ref = builder.source("agg_daily_cost")
        req_ref = builder.source("fact_restaurant_requisition")
        unknown = Freshness.unknown("period_comparison does not expose source maximum date")
        revenue_cov = Coverage.unknown("Gold output does not expose agg_daily row coverage")
        gm_cov = Coverage.complete("Gold emits margin only when cost_n equals revenue row count")
        req_cov = Coverage.unknown(
            "window lies inside global requisition span; per-day completeness is not exposed",
            "REQUISITION_DAILY_COVERAGE_UNKNOWN",
        )
        availability_gap = False
        metric_specs = (
            ("revenue", "CNY", (revenue_ref,), revenue_cov),
            ("gross_margin_pct", "PERCENT", (revenue_ref, cost_ref), gm_cov),
            ("cost_ratio", "PERCENT", (revenue_ref, req_ref), req_cov),
        )
        for raw_key, unit, refs, coverage in metric_specs:
            block = raw.get(raw_key) or {}
            for field, out_metric in (
                ("current", raw_key.replace("_pct", "")),
                ("mom_pct", raw_key.replace("_pct", "") + "MomChange"),
                ("yoy_pct", raw_key.replace("_pct", "") + "YoyChange"),
            ):
                value = block.get(field)
                available_key = {
                    "current": "available",
                    "mom_pct": "mom_available",
                    "yoy_pct": "yoy_available",
                }[field]
                available = block.get(available_key, value is not None)
                if value is None:
                    availability_gap = True
                    missing_coverage = Coverage(
                        status=CoverageStatus.PARTIAL,
                        basis=f"{raw_key} is unavailable for {field}",
                        numerator=0,
                        denominator=1,
                        missing_reasons=("SOURCE_COVERAGE_INSUFFICIENT",),
                    )
                    builder.fact(
                        out_metric,
                        None,
                        unit=unit,
                        scale=2,
                        dimensions={"comparison": field},
                        provenance_refs=refs,
                        freshness=unknown,
                        coverage=missing_coverage,
                        semantics="deterministic period comparison",
                        status=EvidenceStatus.NOT_COMPUTABLE,
                        quality_flags=("SOURCE_COVERAGE_INSUFFICIENT",),
                    )
                elif available:
                    builder.fact(
                        out_metric,
                        value,
                        unit=unit,
                        scale=2,
                        dimensions={"comparison": field},
                        provenance_refs=refs,
                        freshness=unknown,
                        coverage=coverage,
                        semantics="deterministic period comparison",
                    )
        revenue_available = bool((raw.get("revenue") or {}).get("available"))
        if not revenue_available:
            status = EvidenceStatus.EMPTY
        elif availability_gap:
            status = EvidenceStatus.PARTIAL
            builder.warning(
                "COMPARISON_OR_COST_COVERAGE_MISSING",
                "Unavailable comparison or cost metrics remain null",
                blocks=("gross margin comparison", "requisition cost comparison"),
            )
        else:
            status = EvidenceStatus.OK
        builder.warning(
            "NOT_DISH_OR_CONTRIBUTION_MARGIN",
            "Aggregate material gross margin is not dish margin or contribution margin",
            severity="BLOCKING",
            blocks=("dish gross margin", "contribution margin"),
        )
        return builder.draft(
            status=status,
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            normalized_parameters=_window_dict(start, end),
        )

    async def _store_performance(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        top_n = _bounded_int(params.get("topN", descriptor.limits.max_rows), 1, descriptor.limits.max_rows, "topN")
        raw = await self.sources.store_comparison(pool, ctx.factory_id, (start, end))
        _assert_tenant_echo(raw, ctx)
        builder = _DraftBuilder(descriptor)
        source = builder.source("agg_daily JOIN dim_store")
        stores = list(raw.get("stores") or [])
        rows = stores[:top_n]
        freshness = Freshness.unknown("store_comparison does not expose source maximum date")
        coverage = Coverage.unknown("store comparison does not expose source row coverage")
        for store in rows:
            dims = {"store": store.get("name") or "UNKNOWN"}
            for key, metric, unit, scale in (
                ("revenue", "revenue", "CNY", 2),
                ("orderCount", "billCount", "COUNT", 0),
                ("avgTicket", "averageTicket", "CNY_PER_BILL", 2),
                ("discountPct", "discountRate", "PERCENT", 1),
            ):
                builder.fact(
                    metric,
                    store.get(key),
                    unit=unit,
                    scale=scale,
                    dimensions=dims,
                    provenance_refs=(source,),
                    freshness=freshness,
                    coverage=coverage,
                    semantics=f"store-level {metric} from agg_daily",
                )
        builder.warning(
            "STORE_PROFIT_NOT_AVAILABLE",
            "This contract compares sales operations only; store profit is blocked",
            severity="BLOCKING",
            blocks=("store profit", "store contribution margin"),
        )
        if rows:
            builder.warning(
                "SOURCE_FRESHNESS_UNKNOWN",
                "The existing store query does not expose the source maximum date",
            )
        return builder.draft(
            status=(
                EvidenceStatus.EMPTY
                if not rows
                else EvidenceStatus.PARTIAL
                if len(stores) > len(rows) or freshness.status is FreshnessStatus.UNKNOWN
                else EvidenceStatus.OK
            ),
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            normalized_parameters={**_window_dict(start, end), "topN": top_n},
            rows_truncated=max(0, len(stores) - len(rows)),
        )

    async def _dish_margin_mix(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        top_n = _bounded_int(params.get("topN", 10), 1, descriptor.limits.max_rows, "topN")
        include_margin = _bool_param(params.get("includeMargin", True), "includeMargin")
        raw = await self.sources.top_products(
            pool, ctx.factory_id, (start, end), top_n=top_n
        )
        _assert_tenant_echo(raw, ctx)
        builder = _DraftBuilder(descriptor)
        # Explicitly accept the known current key and two legacy consumers' keys.
        rows = _first_list(raw, "top_products", "items", "products")[:top_n]
        freshness = Freshness.unknown("monthly product query does not expose actual source maximum month")
        coverage = Coverage.unknown(
            "top-N mix has no total dish denominator", "TOP_N_DENOMINATOR_UNKNOWN"
        )
        cost_basis_by_dish = raw.get("cost_basis") if isinstance(raw.get("cost_basis"), Mapping) else {}
        margin_incomplete = False
        for row in rows:
            source = builder.source(
                "agg_product JOIN dim_product",
                source_upload_id=row.get("source_upload_id"),
                field_name=row.get("field_name") or "revenue",
            )
            dims = {
                "dishId": row.get("product_id") or row.get("entity_id") or "UNKNOWN",
                "dishName": row.get("product_name") or "UNKNOWN",
            }
            for key, metric, unit, scale in (
                ("qty_sold", "quantitySold", "SOURCE_UNIT", 3),
                ("revenue", "revenue", "CNY", 2),
                ("bill_count", "billCount", "COUNT", 0),
            ):
                if row.get(key) is not None:
                    builder.fact(
                        metric,
                        row[key],
                        unit=unit,
                        scale=scale,
                        dimensions=dims,
                        provenance_refs=(source,),
                        freshness=freshness,
                        coverage=coverage,
                        semantics=f"monthly top-dish {metric}",
                    )
            if include_margin:
                dish_key = str(row.get("product_id") or row.get("entity_id") or "")
                basis = cost_basis_by_dish.get(dish_key) or cost_basis_by_dish.get(
                    row.get("product_id")
                ) or {}
                if not isinstance(basis, Mapping):
                    basis = {}
                numerator = _safe_nonnegative_int(basis.get("coveredLines"), 0)
                denominator = _safe_nonnegative_int(basis.get("totalLines"), 1)
                if denominator == 0:
                    denominator = 1
                if numerator > denominator:
                    numerator = denominator
                material_complete = all(
                    bool(basis.get(flag))
                    for flag in (
                        "recipeApproved",
                        "purchasePriceTimeValid",
                        "unitConversionComplete",
                        "lineCoverageComplete",
                    )
                ) and numerator == denominator and basis.get("totalMaterialCost") is not None
                material_coverage = Coverage(
                    status=(CoverageStatus.COMPLETE if material_complete else CoverageStatus.PARTIAL),
                    basis="approved recipe, time-valid purchase price, unit conversion and line coverage",
                    numerator=numerator,
                    denominator=denominator,
                    missing_reasons=(
                        ()
                        if material_complete
                        else (
                            "RECIPE_OR_PURCHASE_BASIS_INCOMPLETE",
                            "UNIT_CONVERSION_OR_LINE_COVERAGE_INCOMPLETE",
                        )
                    ),
                )
                cost_freshness = _known_date_or_unknown(
                    basis.get("dataThrough"),
                    "maximum time-valid purchase date in the approved dish cost basis",
                )
                cost_refs: tuple[str, ...] = ()
                if material_complete:
                    cost_ref = builder.source(
                        str(basis.get("sourceAsset") or "approved dish material cost basis"),
                        source_type=str(basis.get("sourceType") or "GOLD"),
                        source_upload_id=basis.get("sourceUploadId"),
                    )
                    cost_refs = (source, cost_ref)
                    revenue_value = row.get("revenue")
                    gross_margin = None
                    if revenue_value is not None and Decimal(str(revenue_value)) != 0:
                        revenue_decimal = Decimal(str(revenue_value))
                        gross_margin = (
                            (revenue_decimal - Decimal(str(basis["totalMaterialCost"])))
                            / revenue_decimal
                            * Decimal("100")
                        )
                    if gross_margin is None:
                        margin_incomplete = True
                    builder.fact(
                        "dishGrossMargin",
                        gross_margin,
                        unit="PERCENT",
                        scale=2,
                        dimensions=dims,
                        provenance_refs=cost_refs,
                        freshness=cost_freshness,
                        coverage=material_coverage,
                        semantics="(dish revenue - complete material cost) / dish revenue",
                        status=(
                            EvidenceStatus.OK
                            if gross_margin is not None
                            else EvidenceStatus.NOT_COMPUTABLE
                        ),
                        quality_flags=("ZERO_REVENUE",) if gross_margin is None else (),
                    )
                else:
                    margin_incomplete = True
                    builder.fact(
                        "dishGrossMargin",
                        None,
                        unit="PERCENT",
                        scale=2,
                        dimensions=dims,
                        provenance_refs=(),
                        freshness=cost_freshness,
                        coverage=material_coverage,
                        semantics="null until the complete dish material-cost basis is approved",
                        status=EvidenceStatus.NOT_COMPUTABLE,
                        quality_flags=("COST_BASIS_INCOMPLETE",),
                    )

                variable_complete = (
                    bool(basis.get("variableCostComponentsComplete"))
                    and basis.get("totalVariableCost") is not None
                    and material_complete
                )
                variable_coverage = Coverage(
                    status=(CoverageStatus.COMPLETE if variable_complete else CoverageStatus.PARTIAL),
                    basis="ingredient, commission, refund, packaging, delivery and promotion variable costs",
                    numerator=1 if variable_complete else 0,
                    denominator=1,
                    missing_reasons=(
                        () if variable_complete else ("VARIABLE_COST_COMPONENTS_INCOMPLETE",)
                    ),
                )
                revenue_for_contribution = row.get("revenue")
                if (
                    variable_complete
                    and revenue_for_contribution is not None
                    and Decimal(str(revenue_for_contribution)) != 0
                ):
                    revenue_decimal = Decimal(str(revenue_for_contribution))
                    contribution_margin = (
                        (revenue_decimal - Decimal(str(basis["totalVariableCost"])))
                        / revenue_decimal
                        * Decimal("100")
                    )
                    builder.fact(
                        "dishContributionMargin",
                        contribution_margin,
                        unit="PERCENT",
                        scale=2,
                        dimensions=dims,
                        provenance_refs=cost_refs,
                        freshness=cost_freshness,
                        coverage=variable_coverage,
                        semantics="(dish revenue - complete enumerated variable cost) / dish revenue",
                    )
                else:
                    margin_incomplete = True
                    builder.fact(
                        "dishContributionMargin",
                        None,
                        unit="PERCENT",
                        scale=2,
                        dimensions=dims,
                        provenance_refs=(),
                        freshness=cost_freshness,
                        coverage=variable_coverage,
                        semantics="null until all variable cost components are complete",
                        status=EvidenceStatus.NOT_COMPUTABLE,
                        quality_flags=("VARIABLE_COST_BASIS_INCOMPLETE",),
                    )
        if include_margin and margin_incomplete:
            builder.warning(
                "COST_BASIS_INCOMPLETE",
                "Incomplete dish gross or contribution margins remain null",
                severity="BLOCKING",
                blocks=("dish gross margin", "dish contribution margin"),
            )
        if start.day != 1 or end.day != calendar.monthrange(end.year, end.month)[1]:
            builder.warning(
                "MONTH_GRAIN_INTERSECTION",
                "agg_product includes whole intersecting months for a partial-month request",
                blocks=("exact partial-month dish mix",),
            )
        return builder.draft(
            status=(
                EvidenceStatus.EMPTY if not rows else EvidenceStatus.PARTIAL
            ),
            requested_window=_window_dict(start, end),
            effective_window={
                "start": start.replace(day=1).isoformat(),
                "end": end.replace(day=calendar.monthrange(end.year, end.month)[1]).isoformat(),
            },
            grain="DISH_MONTH",
            normalized_parameters={
                **_window_dict(start, end),
                "topN": top_n,
                "includeMargin": include_margin,
            },
        )

    async def _cost_movement(self, pool, ctx, params, descriptor):
        trailing_n = _bounded_int(params.get("trailingN", 3), 1, 50, "trailingN")
        window_days = _bounded_int(
            params.get("windowDays", 90), 1, descriptor.limits.max_window_days, "windowDays"
        )
        top_n = _bounded_int(params.get("topN", 20), 1, descriptor.limits.max_rows, "topN")
        baseline = str(params.get("baselineMode", "count")).lower()
        if baseline not in {"count", "days"}:
            raise ReadToolContractError("baselineMode must be count or days")
        epsilon = _bounded_float(params.get("epsilonPct", 10), 0.01, 1_000, "epsilonPct")
        raw_rows = await self.sources.detect_price_anomalies(
            pool,
            ctx.factory_id,
            trailing_n=trailing_n,
            epsilon_pct=epsilon,
            baseline_mode=baseline,
            window_days=window_days,
        )
        builder = _DraftBuilder(descriptor)
        source = builder.source("agg_supplier_price")
        freshness = Freshness.unknown(
            "detector returns anomaly dates but not the source maximum delivery date"
        )
        coverage = Coverage.complete("detector scans all available supplier price rows")
        rows = list(raw_rows or [])[:top_n]
        for row in rows:
            dims = {
                "ingredient": row.get("ingredientName") or row.get("normalizedName") or "UNKNOWN",
                "supplierId": row.get("supplierId") or "UNKNOWN",
                "supplier": row.get("supplierName") or "UNKNOWN",
                "unit": row.get("unit") or "UNKNOWN",
                "direction": row.get("direction") or "UNKNOWN",
                "riskLevel": row.get("riskLevel") or "UNKNOWN",
                "anomalyDate": row.get("anomalyDeliveryDate") or "UNKNOWN",
            }
            for key, metric, unit, scale in (
                ("oldPrice", "priorUnitPrice", "CNY_PER_SOURCE_UNIT", 4),
                ("newPrice", "latestUnitPrice", "CNY_PER_SOURCE_UNIT", 4),
                ("trailingAvg", "baselineUnitPrice", "CNY_PER_SOURCE_UNIT", 4),
                ("deltaPct", "unitPriceChange", "PERCENT", 4),
                ("consecutiveAnomalyCount", "consecutiveAnomalyCount", "COUNT", 0),
            ):
                if row.get(key) is not None:
                    builder.fact(
                        metric,
                        row[key],
                        unit=unit,
                        scale=scale,
                        dimensions=dims,
                        provenance_refs=(source,),
                        freshness=freshness,
                        coverage=coverage,
                        semantics="supplier price anomaly detector output",
                    )
        builder.warning(
            "SIGNAL_NOT_CAUSAL_FINDING",
            "A price anomaly is a review signal, not proof of fraud or culpability",
            blocks=("fraud", "supplier culpability"),
        )
        if not rows:
            builder.warning(
                "EMPTY_IS_NO_ANOMALY_NOT_NO_SOURCE",
                "No anomaly was returned; source connectivity is not exposed by this contract",
            )
        return builder.draft(
            status=EvidenceStatus.EMPTY if not rows else EvidenceStatus.PARTIAL,
            requested_window=None,
            effective_window=None,
            normalized_parameters={
                "trailingN": trailing_n,
                "epsilonPct": epsilon,
                "baselineMode": baseline,
                "windowDays": window_days,
                "topN": top_n,
            },
            rows_truncated=max(0, len(raw_rows or []) - len(rows)),
        )

    async def _channel_discount_mix(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        top_n = _bounded_int(params.get("topN", 10), 1, descriptor.limits.max_rows, "topN")
        dimension = str(params.get("dimension", "")).upper()
        if dimension == "ORDER_TYPE":
            raw = await self.sources.order_type_breakdown(pool, ctx.factory_id, (start, end))
            row_key, dim_key, asset = "order_types", "order_type", "agg_daily_order_type_meal"
        elif dimension == "MEAL_PERIOD":
            raw = await self.sources.meal_period_breakdown(pool, ctx.factory_id, (start, end))
            row_key, dim_key, asset = "meal_periods", "meal_period", "agg_daily_order_type_meal"
        elif dimension == "DISCOUNT":
            raw = await self.sources.discount_summary(pool, ctx.factory_id, (start, end), top_n=top_n)
            row_key, dim_key, asset = "discounts", "discount_name", "agg_daily + agg_discount"
        else:
            raise ReadToolContractError("dimension must be ORDER_TYPE, MEAL_PERIOD or DISCOUNT")
        _assert_tenant_echo(raw, ctx)
        builder = _DraftBuilder(descriptor)
        source = builder.source(asset)
        freshness = Freshness.unknown("breakdown query does not expose source maximum date")
        coverage = Coverage.unknown("breakdown source row coverage is not exposed")
        rows = list(raw.get(row_key) or [])[:top_n]
        estimated = bool(raw.get("revenue_estimated"))
        for row in rows:
            dims = {"dimension": dimension, "value": row.get(dim_key) or "UNKNOWN"}
            if dimension == "DISCOUNT":
                specs = (
                    ("amount", "discountAmount", "CNY", 2),
                    ("bill_count", "discountedBillCount", "COUNT", 0),
                    ("share_pct", "discountMixShare", "PERCENT", 1),
                )
            else:
                specs = (
                    ("revenue", "revenue", "CNY", 2),
                    ("bill_count", "billCount", "COUNT", 0),
                    ("avg_ticket", "averageTicket", "CNY_PER_BILL", 2),
                    ("revenue_pct", "revenueShare", "PERCENT", 1),
                )
            for key, metric, unit, scale in specs:
                if row.get(key) is not None:
                    fact_estimated = estimated and metric in {"revenue", "averageTicket", "revenueShare"}
                    builder.fact(
                        metric,
                        row[key],
                        unit=unit,
                        scale=scale,
                        dimensions=dims,
                        provenance_refs=(source,),
                        freshness=freshness,
                        coverage=coverage,
                        semantics=f"descriptive {dimension.lower()} breakdown",
                        status=EvidenceStatus.PARTIAL if fact_estimated else EvidenceStatus.OK,
                        quality_flags=("ESTIMATED_FROM_AVERAGE_TICKET",) if fact_estimated else (),
                    )
        if estimated:
            builder.warning(
                "MONEY_ESTIMATED_FROM_AVERAGE_TICKET",
                "Channel money is estimated; bill structure is the only observed channel fact",
                blocks=("exact channel revenue",),
            )
        if dimension == "DISCOUNT" and (
            start.day != 1 or end.day != calendar.monthrange(end.year, end.month)[1]
        ):
            builder.warning(
                "DISCOUNT_COMPOSITION_MONTH_GRAIN",
                "Discount total is exact-day, but type composition comes from intersecting months",
                blocks=("exact partial-month discount type mix",),
            )
        builder.warning(
            "DESCRIPTIVE_ONLY",
            "This mix cannot establish promotion lift, incrementality or ROI",
            blocks=("causal lift", "promotion ROI"),
        )
        return builder.draft(
            status=EvidenceStatus.EMPTY if not rows else EvidenceStatus.PARTIAL,
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            normalized_parameters={
                **_window_dict(start, end), "dimension": dimension, "topN": top_n
            },
            rows_truncated=max(0, len(raw.get(row_key) or []) - len(rows)),
        )

    async def _waste_anomaly(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        unsupported = self._require_current_anchored_window(start, end, descriptor)
        if unsupported:
            return unsupported
        top_n = _bounded_int(params.get("topN", 10), 1, descriptor.limits.max_rows, "topN")
        days = (end - start).days
        answer = await self.sources.resolve_wastage_top(pool, ctx.factory_id, days=days, top_n=top_n)
        builder = _DraftBuilder(descriptor)
        source = builder.source("agg_restaurant_daily_totals")
        freshness = Freshness.unknown("existing waste contract does not expose source maximum date")
        coverage = Coverage.unknown("existing waste contract does not expose daily row coverage")
        count = _kpi_value(answer, "损耗次数")
        cost = _kpi_value(answer, "损耗金额")
        if count is not None:
            builder.fact(
                "wasteEventCount", count, unit="COUNT", scale=0, dimensions={},
                provenance_refs=(source,), freshness=freshness, coverage=coverage,
                semantics="waste event count from restaurant daily totals",
            )
        if cost is not None:
            builder.fact(
                "wasteCost", cost, unit="CNY", scale=2, dimensions={},
                provenance_refs=(source,), freshness=freshness, coverage=coverage,
                semantics="waste cost from restaurant daily totals",
            )
        builder.warning(
            "MIXED_UNIT_QUANTITY_BLOCKED",
            "The legacy contract loses row-level units; total waste quantity and top quantities are omitted",
            severity="BLOCKING",
            blocks=("total waste quantity", "cross-unit ranking"),
        )
        return builder.draft(
            status=EvidenceStatus.EMPTY if not builder.facts else EvidenceStatus.PARTIAL,
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            normalized_parameters={**_window_dict(start, end), "topN": top_n},
        )

    async def _inventory_risk(self, pool, ctx, params, descriptor):
        top_n = _bounded_int(params.get("topN", 15), 1, descriptor.limits.max_rows, "topN")
        as_of = _optional_date(params.get("asOf"), "asOf") or self.today_factory()
        if as_of > self.today_factory():
            raise ReadToolContractError("asOf may not be in the future")
        answer = await self.sources.resolve_inventory_warning(pool, ctx.factory_id, top_n=top_n)
        builder = _DraftBuilder(descriptor)
        source = builder.source("fact_inventory_snapshot JOIN dim_ingredient_threshold")
        if getattr(answer, "meta", {}).get("no_data"):
            builder.warning(
                "INVENTORY_SOURCE_NOT_CONNECTED",
                "No inventory snapshot is available",
                severity="BLOCKING",
                blocks=("inventory risk",),
            )
            return builder.draft(
                status=EvidenceStatus.EMPTY,
                requested_window={"asOf": as_of.isoformat()},
                effective_window=None,
                normalized_parameters={"asOf": as_of.isoformat(), "topN": top_n},
            )
        snapshot_raw = getattr(answer, "meta", {}).get("snapshot_date")
        snapshot_date = _optional_date(snapshot_raw, "snapshot_date")
        if snapshot_date is None:
            freshness = Freshness.unknown("legacy inventory contract omitted snapshot_date")
        else:
            freshness = Freshness(
                data_through=snapshot_date.isoformat(),
                status=(
                    FreshnessStatus.FRESH
                    if snapshot_date == self.today_factory()
                    else FreshnessStatus.STALE
                ),
                sla_seconds=86_400,
                basis="MAX(fact_inventory_snapshot.snapshot_date)",
            )
        if snapshot_date is not None and snapshot_date > as_of:
            builder.warning(
                "HISTORICAL_AS_OF_UNSUPPORTED",
                "The existing source returns only the latest snapshot, which is after requested asOf",
                severity="BLOCKING",
                blocks=("inventory risk as of requested date",),
            )
            return builder.draft(
                status=EvidenceStatus.NOT_COMPUTABLE,
                requested_window={"asOf": as_of.isoformat()},
                effective_window={"asOf": snapshot_date.isoformat()},
                normalized_parameters={"asOf": as_of.isoformat(), "topN": top_n},
            )
        coverage = Coverage.complete("all ingredients in the selected latest snapshot are classified")
        for title, metric in (("需补货", "reorderNowCount"), ("关注", "watchCount"), ("正常", "normalCount")):
            value = _kpi_value(answer, title)
            if value is not None:
                builder.fact(
                    metric, value, unit="COUNT", scale=0, dimensions={},
                    provenance_refs=(source,), freshness=freshness, coverage=coverage,
                    semantics="ingredient count by configured stock threshold status",
                )
        if freshness.status is FreshnessStatus.STALE:
            builder.warning(
                "STALE_INVENTORY_SNAPSHOT",
                "Snapshot is older than the one-day freshness SLA; do not call it current stock",
                severity="BLOCKING",
                blocks=("current inventory",),
            )
        return builder.draft(
            status=(
                EvidenceStatus.EMPTY
                if not builder.facts
                else EvidenceStatus.PARTIAL
                if freshness.status is not FreshnessStatus.FRESH
                else EvidenceStatus.OK
            ),
            requested_window={"asOf": as_of.isoformat()},
            effective_window={"asOf": snapshot_date.isoformat()} if snapshot_date else None,
            normalized_parameters={"asOf": as_of.isoformat(), "topN": top_n},
        )

    async def _stocktaking_variance(self, pool, ctx, params, descriptor):
        start, end = _window(params, descriptor)
        unsupported = self._require_current_anchored_window(start, end, descriptor)
        if unsupported:
            return unsupported
        top_n = _bounded_int(params.get("topN", 10), 1, descriptor.limits.max_rows, "topN")
        answer = await self.sources.resolve_stock_shortage(
            pool, ctx.factory_id, days=(end - start).days, top_n=top_n
        )
        builder = _DraftBuilder(descriptor)
        source = builder.source("agg_restaurant_daily_totals")
        freshness = Freshness.unknown("legacy stocktaking contract does not expose source maximum date")
        coverage = Coverage.unknown("legacy stocktaking contract does not expose event-day coverage")
        count = _kpi_value(answer, "盘点次数")
        if count is not None:
            builder.fact(
                "stocktakingEventCount", count, unit="COUNT", scale=0, dimensions={},
                provenance_refs=(source,), freshness=freshness, coverage=coverage,
                semantics="stocktaking event count",
            )
        builder.warning(
            "MIXED_UNIT_VARIANCE_BLOCKED",
            "Legacy totals mix ingredient units; shortage/surplus quantities are omitted",
            severity="BLOCKING",
            blocks=("total shortage quantity", "total surplus quantity", "theft inference"),
        )
        return builder.draft(
            status=EvidenceStatus.EMPTY if not builder.facts else EvidenceStatus.PARTIAL,
            requested_window=_window_dict(start, end),
            effective_window=_window_dict(start, end),
            normalized_parameters={**_window_dict(start, end), "topN": top_n},
        )

    async def _review_signal(self, pool, ctx, params, descriptor):
        top_n = _bounded_int(params.get("topN", 10), 1, 20, "topN")
        min_reviews = _bounded_int(params.get("minReviews", 20), 20, 10_000, "minReviews")
        threshold = _bounded_int(params.get("starThreshold", 3), 1, 3, "starThreshold")
        summary = await self.sources.review_summary(pool, ctx.factory_id)
        _assert_tenant_echo(summary, ctx)
        builder = _DraftBuilder(descriptor)
        source = builder.source("smart_bi_dynamic_data (deduplicated review CTE)", source_type="SILVER")
        if not summary.get("connected"):
            builder.warning(
                "REVIEW_SOURCE_NOT_CONNECTED",
                "No review source rows exist for this tenant",
                severity="BLOCKING",
                blocks=("review signal",),
            )
            return builder.draft(
                status=EvidenceStatus.EMPTY,
                requested_window=None,
                effective_window=None,
                normalized_parameters={
                    "topN": top_n, "minReviews": min_reviews, "starThreshold": threshold
                },
            )
        stores = await self.sources.review_store_ranking(
            pool, ctx.factory_id, dim="low_star", order="desc", top_n=top_n,
            min_reviews=min_reviews,
        )
        tags = await self.sources.review_dish_issues(
            pool, ctx.factory_id, top_n=top_n, star_threshold=threshold
        )
        _assert_tenant_echo(stores, ctx)
        _assert_tenant_echo(tags, ctx)
        freshness = Freshness.unknown(
            "review source does not expose a reliable maximum review or import date"
        )
        coverage = Coverage.unknown(
            "deduplicated all-available corpus; upload completeness is unknown",
            "IMPORT_COMPLETENESS_UNKNOWN",
        )
        for key, metric, unit, scale in (
            ("total_reviews", "reviewCount", "COUNT", 0),
            ("avg_star", "averageStar", "SCORE_5", 3),
            ("avg_service", "averageServiceScore", "SCORE_5", 3),
            ("avg_env", "averageEnvironmentScore", "SCORE_5", 3),
            ("avg_taste", "averageTasteScore", "SCORE_5", 3),
            ("low_star_count", "lowStarReviewCount", "COUNT", 0),
            ("high_star_count", "highStarReviewCount", "COUNT", 0),
            ("vip_count", "vipReviewCount", "COUNT", 0),
            ("store_count", "reviewedStoreCount", "COUNT", 0),
            ("city_count", "reviewedCityCount", "COUNT", 0),
        ):
            if summary.get(key) is not None:
                builder.fact(
                    metric, summary[key], unit=unit, scale=scale, dimensions={"scope": "ALL_AVAILABLE"},
                    provenance_refs=(source,), freshness=freshness, coverage=coverage,
                    semantics="deduplicated aggregated review metric",
                )
        for row in stores.get("stores") or []:
            dims = {"store": row.get("store") or "UNKNOWN"}
            for key, metric, unit, scale in (
                ("review_count", "reviewCount", "COUNT", 0),
                ("avg_star", "averageStar", "SCORE_5", 3),
                ("avg_service", "averageServiceScore", "SCORE_5", 3),
                ("avg_env", "averageEnvironmentScore", "SCORE_5", 3),
                ("avg_taste", "averageTasteScore", "SCORE_5", 3),
                ("low_star_count", "lowStarReviewCount", "COUNT", 0),
            ):
                if row.get(key) is not None:
                    builder.fact(
                        metric, row[key], unit=unit, scale=scale, dimensions=dims,
                        provenance_refs=(source,), freshness=freshness, coverage=coverage,
                        semantics=f"store aggregate with minimum {min_reviews} reviews",
                    )
        for row in tags.get("tags") or []:
            builder.fact(
                "lowStarFlavorQualityTagCount", row.get("count"), unit="COUNT", scale=0,
                dimensions={"flavorQualityTag": row.get("tag") or "UNKNOWN"},
                provenance_refs=(source,), freshness=freshness, coverage=coverage,
                semantics="flavor or quality tag frequency in low-star reviews; not a dish name",
                quality_flags=("TAG_IS_NOT_DISH_NAME",),
            )
        builder.warning(
            "ALL_AVAILABLE_TIME_SCOPE",
            "Reliable review dates are not exposed; this evidence covers all available imports",
            blocks=("dated review trend",),
        )
        builder.warning(
            "AGGREGATED_NO_RAW_TEXT",
            "Raw review text and member identity are intentionally excluded",
        )
        return builder.draft(
            status=EvidenceStatus.PARTIAL,
            requested_window=None,
            effective_window=None,
            grain="ALL_AVAILABLE",
            normalized_parameters={
                "topN": top_n, "minReviews": min_reviews, "starThreshold": threshold
            },
        )

    def _require_current_anchored_window(self, start, end, descriptor):
        if end == self.today_factory():
            return None
        builder = _DraftBuilder(descriptor)
        builder.warning(
            "EXPLICIT_HISTORICAL_WINDOW_UNSUPPORTED",
            "The existing Gold function is anchored to CURRENT_DATE; requested endDate was not executed",
            severity="BLOCKING",
            blocks=descriptor.conclusions_allowed,
        )
        return builder.draft(
            status=EvidenceStatus.NOT_COMPUTABLE,
            requested_window=_window_dict(start, end),
            effective_window=None,
            normalized_parameters=_window_dict(start, end),
        )


def build_restaurant_read_registry(
    sources: Optional[RestaurantReadSources] = None,
    *,
    today_factory: Callable[[], date] = date.today,
) -> ReadonlyToolRegistry:
    registry = ReadonlyToolRegistry()
    adapter = RestaurantReadToolAdapter(
        sources or default_restaurant_sources(), today_factory=today_factory
    )
    for descriptor in restaurant_descriptors():
        registry.register(descriptor, adapter.execute)
    return registry


def _window(params: Mapping[str, Any], descriptor: ReadToolDescriptor) -> tuple[date, date]:
    start = _required_date(params.get("startDate"), "startDate")
    end = _required_date(params.get("endDate"), "endDate")
    if end < start:
        raise ReadToolContractError("endDate must not be before startDate")
    if (end - start).days + 1 > descriptor.limits.max_window_days:
        raise ReadToolContractError(
            f"window exceeds {descriptor.limits.max_window_days} days"
        )
    return start, end


def _required_date(value: Any, name: str) -> date:
    parsed = _optional_date(value, name)
    if parsed is None:
        raise ReadToolContractError(f"{name} is required")
    return parsed


def _optional_date(value: Any, name: str) -> Optional[date]:
    if value is None:
        return None
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value))
    except ValueError as exc:
        raise ReadToolContractError(f"{name} must be ISO date YYYY-MM-DD") from exc


def _window_dict(start: date, end: date) -> dict[str, str]:
    return {"start": start.isoformat(), "end": end.isoformat()}


def _aggregate_trend_points(
    points: list[Mapping[str, Any]], grain: str
) -> list[dict[str, Any]]:
    buckets: dict[date, dict[str, Any]] = {}
    for point in points:
        point_date = _required_date(point.get("date"), "source point date")
        if grain == "DAY":
            bucket_start = point_date
        elif grain == "WEEK":
            bucket_start = point_date - timedelta(days=point_date.weekday())
        else:
            bucket_start = point_date.replace(day=1)
        bucket = buckets.setdefault(
            bucket_start,
            {
                "revenue": Decimal("0"),
                "bill_count": 0,
                "observed_start": point_date,
                "observed_end": point_date,
            },
        )
        bucket["revenue"] += Decimal(str(point.get("revenue") or 0))
        bucket["bill_count"] += int(point.get("bill_count") or 0)
        bucket["observed_start"] = min(bucket["observed_start"], point_date)
        bucket["observed_end"] = max(bucket["observed_end"], point_date)
    result: list[dict[str, Any]] = []
    for bucket_start in sorted(buckets):
        bucket = buckets[bucket_start]
        bills = int(bucket["bill_count"])
        revenue = bucket["revenue"]
        result.append(
            {
                "observedStart": bucket["observed_start"].isoformat(),
                "observedEnd": bucket["observed_end"].isoformat(),
                "revenue": revenue,
                "bill_count": bills,
                "avg_bill_value": (revenue / Decimal(bills)) if bills > 0 else None,
            }
        )
    return result


def _bounded_int(value: Any, minimum: int, maximum: int, name: str) -> int:
    if isinstance(value, bool):
        raise ReadToolContractError(f"{name} must be an integer")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ReadToolContractError(f"{name} must be an integer") from exc
    if parsed < minimum or parsed > maximum:
        raise ReadToolContractError(f"{name} must be between {minimum} and {maximum}")
    return parsed


def _safe_nonnegative_int(value: Any, default: int) -> int:
    if value is None or isinstance(value, bool):
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return max(0, parsed)


def _bounded_float(value: Any, minimum: float, maximum: float, name: str) -> float:
    if isinstance(value, bool):
        raise ReadToolContractError(f"{name} must be numeric")
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ReadToolContractError(f"{name} must be numeric") from exc
    if parsed < minimum or parsed > maximum:
        raise ReadToolContractError(f"{name} must be between {minimum} and {maximum}")
    return parsed


def _bool_param(value: Any, name: str) -> bool:
    if isinstance(value, bool):
        return value
    raise ReadToolContractError(f"{name} must be boolean")


def _assert_tenant_echo(
    raw: Any, context: TrustedExecutionContext, *, optional: bool = False
) -> None:
    if not isinstance(raw, Mapping):
        return
    echoed = raw.get("factory_id")
    if echoed is None and optional:
        return
    if echoed != context.factory_id:
        raise ReadToolContractError("source tenant echo conflicts with trusted context")


def _known_date_or_unknown(value: Any, basis: str) -> Freshness:
    if value is None:
        return Freshness.unknown(basis)
    return Freshness(
        data_through=str(value),
        status=FreshnessStatus.UNKNOWN,
        basis=basis + "; ingestion SLA is not exposed",
    )


def _first_list(raw: Mapping[str, Any], *keys: str) -> list[dict[str, Any]]:
    for key in keys:
        value = raw.get(key)
        if isinstance(value, list):
            return value
    return []


def _kpi_value(answer: Any, title: str) -> Any:
    for item in getattr(answer, "kpis", ()) or ():
        if item.get("title") == title:
            return item.get("rawValue")
    return None
