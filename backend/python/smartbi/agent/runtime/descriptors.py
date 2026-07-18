"""Explicit, immutable descriptors for model-visible restaurant Read Tools."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from .contracts import DataClassification


@dataclass(frozen=True)
class ToolLimits:
    max_rows: int
    max_facts: int = 200
    max_cells: int = 2_000
    max_bytes: int = 100_000
    max_provenance_refs: int = 100
    max_window_days: int = 366
    max_llm_tokens: int = 0

    def __post_init__(self) -> None:
        for name, value in self.__dict__.items():
            if name == "max_llm_tokens":
                if value != 0:
                    raise ValueError("Read Tools may not consume LLM tokens")
            elif value <= 0:
                raise ValueError(f"{name} must be positive")


@dataclass(frozen=True)
class ReadToolDescriptor:
    name: str
    version: str
    business_question: str
    source_assets: tuple[str, ...]
    time_grain: str
    input_schema: tuple[tuple[str, str], ...]
    allowed_parameters: frozenset[str]
    required_parameters: frozenset[str]
    classification: DataClassification
    conclusions_allowed: tuple[str, ...]
    conclusions_blocked: tuple[str, ...]
    limits: ToolLimits
    access_mode: str = "READ_ONLY"
    output_schema: str = "EvidenceEnvelope@1.0"

    def __post_init__(self) -> None:
        if self.access_mode != "READ_ONLY":
            raise ValueError("restaurant registry accepts only READ_ONLY descriptors")
        if not self.name.endswith(f".{self.version}"):
            raise ValueError("tool name must be versioned, for example name.v1")
        if not self.required_parameters.issubset(self.allowed_parameters):
            raise ValueError("required parameters must be allowed")
        if {name for name, _ in self.input_schema} != set(self.allowed_parameters):
            raise ValueError("input schema must describe every allowed parameter exactly once")
        forbidden = {"factoryid", "factory_id", "tenantid", "tenant_id"}
        if {p.lower() for p in self.allowed_parameters} & forbidden:
            raise ValueError("tenant identity must not be model-visible")

    @property
    def digest(self) -> str:
        body = {
            "name": self.name,
            "version": self.version,
            "businessQuestion": self.business_question,
            "sourceAssets": self.source_assets,
            "timeGrain": self.time_grain,
            "inputSchema": dict(self.input_schema),
            "allowedParameters": sorted(self.allowed_parameters),
            "requiredParameters": sorted(self.required_parameters),
            "classification": self.classification.value,
            "conclusionsAllowed": self.conclusions_allowed,
            "conclusionsBlocked": self.conclusions_blocked,
            "limits": self.limits.__dict__,
            "accessMode": self.access_mode,
            "outputSchema": self.output_schema,
        }
        encoded = json.dumps(body, sort_keys=True, separators=(",", ":")).encode()
        return "sha256:" + hashlib.sha256(encoded).hexdigest()


def restaurant_descriptors() -> tuple[ReadToolDescriptor, ...]:
    financial = DataClassification.FINANCIAL_RESTRICTED
    operational = DataClassification.OPERATIONAL_INTERNAL
    customer = DataClassification.CUSTOMER_SENSITIVE_AGGREGATED
    window = frozenset({"startDate", "endDate"})
    required_window = window

    def schema(**fields: str) -> tuple[tuple[str, str], ...]:
        return tuple(fields.items())

    return (
        ReadToolDescriptor(
            name="restaurant_revenue_trend_read.v1",
            version="v1",
            business_question="How did restaurant revenue and bill count change?",
            source_assets=("agg_daily", "smartbi.gold.queries.daily_trend"),
            time_grain="DAY",
            input_schema=schema(
                startDate="ISO_DATE",
                endDate="ISO_DATE",
                grain="ENUM[DAY,WEEK,MONTH] default DAY",
            ),
            allowed_parameters=window | {"grain"},
            required_parameters=required_window,
            classification=financial,
            conclusions_allowed=("revenue and bill-count trend", "missing-date disclosure"),
            conclusions_blocked=(
                "causal attribution",
                "filled zero for an unobserved day",
                "model-derived average ticket; a later deterministic contract may derive it",
            ),
            # DAY is capped at 60 requested days; WEEK/MONTH deterministically
            # aggregate the daily Gold result and stay below 200 facts.
            limits=ToolLimits(max_rows=60),
        ),
        ReadToolDescriptor(
            name="restaurant_period_comparison_read.v1",
            version="v1",
            business_question="How did revenue and covered aggregate material-cost ratios compare?",
            source_assets=(
                "agg_daily",
                "agg_daily_cost",
                "fact_restaurant_requisition",
                "smartbi.gold.queries.period_comparison",
            ),
            time_grain="WINDOW",
            input_schema=schema(startDate="ISO_DATE", endDate="ISO_DATE"),
            allowed_parameters=window,
            required_parameters=required_window,
            classification=financial,
            conclusions_allowed=("period direction", "percentage or percentage-point change"),
            conclusions_blocked=("dish margin", "contribution margin", "causal attribution"),
            limits=ToolLimits(max_rows=3),
        ),
        ReadToolDescriptor(
            name="restaurant_store_performance_read.v1",
            version="v1",
            business_question="Which stores lead or lag on sales, bills, ticket and discount rate?",
            source_assets=("agg_daily", "dim_store", "smartbi.gold.queries.store_comparison"),
            time_grain="STORE_WINDOW",
            input_schema=schema(
                startDate="ISO_DATE", endDate="ISO_DATE", topN="INT[1,50] default 50"
            ),
            allowed_parameters=window | {"topN"},
            required_parameters=required_window,
            classification=financial,
            conclusions_allowed=("store sales comparison",),
            conclusions_blocked=("store profit", "store contribution margin"),
            limits=ToolLimits(max_rows=50),
        ),
        ReadToolDescriptor(
            name="restaurant_dish_margin_mix_read.v1",
            version="v1",
            business_question="Which dishes drive sales mix, and is dish margin computable?",
            source_assets=(
                "agg_product",
                "dim_product",
                "field_provenance",
                "smartbi.gold.queries.top_products",
            ),
            time_grain="DISH_MONTH",
            input_schema=schema(
                startDate="ISO_DATE",
                endDate="ISO_DATE",
                topN="INT[1,20] default 10",
                includeMargin="BOOLEAN default true",
            ),
            allowed_parameters=window | {"topN", "includeMargin"},
            required_parameters=required_window,
            classification=financial,
            conclusions_allowed=("top-dish sales mix",),
            conclusions_blocked=("dish gross margin without complete cost basis", "dish contribution margin"),
            # Per-dish mix plus fail-closed gross/contribution facts stay under
            # the global fact/cell/byte envelope limits.
            limits=ToolLimits(max_rows=20),
        ),
        ReadToolDescriptor(
            name="restaurant_cost_movement_read.v1",
            version="v1",
            business_question="Which ingredient-supplier purchase prices moved abnormally?",
            source_assets=("agg_supplier_price", "smartbi.gold.price_anomaly.detect_price_anomalies"),
            time_grain="INGREDIENT_SUPPLIER_DELIVERY",
            input_schema=schema(
                trailingN="INT[1,50] default 3",
                epsilonPct="DECIMAL[0.01,1000] default 10",
                baselineMode="ENUM[count,days] default count",
                windowDays="INT[1,366] default 90",
                topN="INT[1,50] default 20",
            ),
            allowed_parameters=frozenset(
                {"trailingN", "epsilonPct", "baselineMode", "windowDays", "topN"}
            ),
            required_parameters=frozenset(),
            classification=financial,
            conclusions_allowed=("price movement signal",),
            conclusions_blocked=("fraud", "supplier culpability", "dish cost without recipe lineage"),
            limits=ToolLimits(max_rows=50),
        ),
        ReadToolDescriptor(
            name="restaurant_channel_discount_mix_read.v1",
            version="v1",
            business_question="How did order type, meal period or discount mix distribute?",
            source_assets=(
                "agg_daily_order_type_meal",
                "agg_daily",
                "agg_discount",
                "dim_discount",
            ),
            time_grain="CHANNEL_WINDOW",
            input_schema=schema(
                startDate="ISO_DATE",
                endDate="ISO_DATE",
                dimension="ENUM[ORDER_TYPE,MEAL_PERIOD,DISCOUNT]",
                topN="INT[1,30] default 10",
            ),
            allowed_parameters=window | {"dimension", "topN"},
            required_parameters=required_window | {"dimension"},
            classification=financial,
            conclusions_allowed=("descriptive channel or discount mix",),
            conclusions_blocked=("promotion incrementality", "promotion ROI", "causal lift"),
            limits=ToolLimits(max_rows=30),
        ),
        ReadToolDescriptor(
            name="restaurant_waste_anomaly_read.v1",
            version="v1",
            business_question="What waste activity is visible without mixing units?",
            source_assets=(
                "agg_restaurant_daily_ops",
                "agg_restaurant_daily_totals",
                "smartbi.gold.restaurant_ops_router.resolve_wastage_top",
            ),
            time_grain="DAY_WINDOW",
            input_schema=schema(
                startDate="ISO_DATE", endDate="ISO_DATE", topN="INT[1,20] default 10"
            ),
            allowed_parameters=window | {"topN"},
            required_parameters=required_window,
            classification=operational,
            conclusions_allowed=("waste event and cost signal",),
            conclusions_blocked=("mixed-unit total quantity", "responsibility attribution"),
            limits=ToolLimits(max_rows=20),
        ),
        ReadToolDescriptor(
            name="restaurant_inventory_risk_read.v1",
            version="v1",
            business_question="Which inventory snapshot counts are below configured thresholds?",
            source_assets=(
                "fact_inventory_snapshot",
                "dim_ingredient_threshold",
                "smartbi.gold.restaurant_ops_router.resolve_inventory_warning",
            ),
            time_grain="LATEST_SNAPSHOT",
            input_schema=schema(
                asOf="ISO_DATE optional", topN="INT[1,100] default 15"
            ),
            allowed_parameters=frozenset({"asOf", "topN"}),
            required_parameters=frozenset(),
            classification=operational,
            conclusions_allowed=("snapshot threshold risk",),
            conclusions_blocked=("current stock when snapshot is stale", "demand forecast"),
            limits=ToolLimits(max_rows=100),
        ),
        ReadToolDescriptor(
            name="restaurant_stocktaking_variance_read.v1",
            version="v1",
            business_question="What stocktaking activity and variance evidence exists?",
            source_assets=(
                "agg_restaurant_daily_ops",
                "agg_restaurant_daily_totals",
                "smartbi.gold.restaurant_ops_router.resolve_stock_shortage",
            ),
            time_grain="DAY_WINDOW",
            input_schema=schema(
                startDate="ISO_DATE", endDate="ISO_DATE", topN="INT[1,100] default 10"
            ),
            allowed_parameters=window | {"topN"},
            required_parameters=required_window,
            classification=operational,
            conclusions_allowed=("stocktaking event signal",),
            conclusions_blocked=("mixed-unit variance total", "theft inference"),
            limits=ToolLimits(max_rows=100),
        ),
        ReadToolDescriptor(
            name="restaurant_review_signal_read.v1",
            version="v1",
            business_question="What aggregated rating, store and flavor-quality signals exist?",
            source_assets=("smart_bi_dynamic_data", "smartbi.gold.review_queries"),
            time_grain="ALL_AVAILABLE",
            input_schema=schema(
                topN="INT[1,20] default 10",
                minReviews="INT[20,10000] default 20",
                starThreshold="INT[1,3] default 3",
            ),
            allowed_parameters=frozenset({"topN", "minReviews", "starThreshold"}),
            required_parameters=frozenset(),
            classification=customer,
            conclusions_allowed=("aggregated review signal",),
            conclusions_blocked=("raw review text", "member identity", "revenue causality", "tag as dish name"),
            limits=ToolLimits(max_rows=50),
        ),
    )
