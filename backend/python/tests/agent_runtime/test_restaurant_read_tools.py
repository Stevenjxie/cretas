from __future__ import annotations

from contextlib import AbstractAsyncContextManager
from datetime import date, datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from smartbi.agent.runtime.contracts import (
    CoverageStatus,
    DataClassification,
    EvidenceStatus,
    TrustedExecutionContext,
)
from smartbi.agent.runtime.gateway import ReadToolContractError, ReadToolGateway
from smartbi.agent.runtime.restaurant_read_tools import (
    RestaurantReadSources,
    build_restaurant_read_registry,
)


EXPECTED_TOOLS = {
    "restaurant_revenue_trend_read.v1",
    "restaurant_period_comparison_read.v1",
    "restaurant_store_performance_read.v1",
    "restaurant_dish_margin_mix_read.v1",
    "restaurant_cost_movement_read.v1",
    "restaurant_channel_discount_mix_read.v1",
    "restaurant_waste_anomaly_read.v1",
    "restaurant_inventory_risk_read.v1",
    "restaurant_stocktaking_variance_read.v1",
    "restaurant_review_signal_read.v1",
}


class _AsyncContext(AbstractAsyncContextManager):
    def __init__(self, value):
        self.value = value

    async def __aenter__(self):
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        return None


class FakeConnection:
    def __init__(self):
        self.rls = []

    def transaction(self, *, readonly=False):
        assert readonly is True
        return _AsyncContext(self)

    async def execute(self, sql, *args):
        self.rls.append((sql, args))


class FakePool:
    def __init__(self):
        self.connection = FakeConnection()

    def acquire(self):
        return _AsyncContext(self.connection)


class FakeSources:
    def __init__(self):
        self.calls = []
        self.cost_basis = {}
        self.top_products_key = "top_products"

    async def _enter(self, pool, name, factory_id):
        async with pool.acquire() as connection:
            self.calls.append((name, factory_id, connection))

    async def daily_trend(self, pool, factory_id, date_range):
        await self._enter(pool, "daily_trend", factory_id)
        start, end = date_range
        points = []
        current = start
        while current <= end:
            points.append(
                {
                    "date": current.isoformat(),
                    "revenue": 100,
                    "bill_count": 4,
                    "avg_bill_value": 25,
                }
            )
            current += timedelta(days=1)
        return {
            "factory_id": factory_id,
            "start_date": start.isoformat(),
            "end_date": end.isoformat(),
            "points": points,
        }

    async def period_comparison(self, pool, factory_id, start, end):
        await self._enter(pool, "period_comparison", factory_id)
        return {
            "revenue": {
                "current": 1000,
                "available": True,
                "mom_pct": 5,
                "yoy_pct": None,
                "mom_available": True,
                "yoy_available": False,
            },
            "gross_margin_pct": {
                "current": None,
                "mom_pct": None,
                "yoy_pct": None,
                "mom_available": False,
                "yoy_available": False,
            },
            "cost_ratio": {
                "current": None,
                "mom_pct": None,
                "yoy_pct": None,
                "mom_available": False,
                "yoy_available": False,
            },
        }

    async def store_comparison(self, pool, factory_id, date_range):
        await self._enter(pool, "store_comparison", factory_id)
        return {
            "factory_id": factory_id,
            "stores": [
                {
                    "name": "A店",
                    "revenue": 1000,
                    "orderCount": 40,
                    "avgTicket": 25,
                    "discountPct": 3,
                }
            ],
        }

    async def top_products(self, pool, factory_id, date_range, *, top_n):
        await self._enter(pool, "top_products", factory_id)
        row = {
            "product_id": 7,
            "product_name": "宫保鸡丁",
            "qty_sold": 10,
            "revenue": 100,
            "bill_count": 8,
            "source_upload_id": 17,
            "field_name": "revenue",
        }
        return {
            "factory_id": factory_id,
            self.top_products_key: [row],
            "cost_basis": self.cost_basis,
        }

    async def detect_price_anomalies(self, pool, factory_id, **kwargs):
        await self._enter(pool, "detect_price_anomalies", factory_id)
        return [
            {
                "normalizedName": "pork",
                "ingredientName": "猪肉",
                "supplierId": "S1",
                "supplierName": "供应商A",
                "unit": "kg",
                "anomalyDeliveryDate": "2026-01-09",
                "oldPrice": 20,
                "newPrice": 25,
                "trailingAvg": 20,
                "deltaPct": 25,
                "direction": "UP",
                "consecutiveAnomalyCount": 2,
                "riskLevel": "MEDIUM",
            }
        ]

    async def order_type_breakdown(self, pool, factory_id, date_range):
        await self._enter(pool, "order_type_breakdown", factory_id)
        return {
            "factory_id": factory_id,
            "order_types": [
                {
                    "order_type": "堂食",
                    "revenue": 100,
                    "bill_count": 4,
                    "avg_ticket": 25,
                    "revenue_pct": 100,
                    "revenue_estimated": False,
                }
            ],
            "revenue_estimated": False,
        }

    async def meal_period_breakdown(self, pool, factory_id, date_range):
        await self._enter(pool, "meal_period_breakdown", factory_id)
        return {
            "factory_id": factory_id,
            "meal_periods": [
                {
                    "meal_period": "午市",
                    "revenue": 100,
                    "bill_count": 4,
                    "avg_ticket": 25,
                    "revenue_pct": 100,
                    "revenue_estimated": False,
                }
            ],
            "revenue_estimated": False,
        }

    async def discount_summary(self, pool, factory_id, date_range, *, top_n):
        await self._enter(pool, "discount_summary", factory_id)
        return {
            "factory_id": factory_id,
            "discounts": [
                {
                    "discount_name": "会员折扣",
                    "amount": 10,
                    "bill_count": 2,
                    "share_pct": 100,
                }
            ],
        }

    async def resolve_wastage_top(self, pool, factory_id, *, days, top_n):
        await self._enter(pool, "resolve_wastage_top", factory_id)
        return SimpleNamespace(
            kpis=[
                {"title": "损耗次数", "rawValue": 3},
                {"title": "损耗量", "rawValue": 999},
                {"title": "损耗金额", "rawValue": 88},
            ],
            meta={"window_days": days},
        )

    async def resolve_inventory_warning(self, pool, factory_id, *, top_n):
        await self._enter(pool, "resolve_inventory_warning", factory_id)
        return SimpleNamespace(
            kpis=[
                {"title": "需补货", "rawValue": 2},
                {"title": "关注", "rawValue": 3},
                {"title": "正常", "rawValue": 4},
            ],
            meta={"snapshot_date": "2026-01-10"},
        )

    async def resolve_stock_shortage(self, pool, factory_id, *, days, top_n):
        await self._enter(pool, "resolve_stock_shortage", factory_id)
        return SimpleNamespace(
            kpis=[
                {"title": "盘点次数", "rawValue": 5},
                {"title": "盘亏总量", "rawValue": 1000},
                {"title": "盘盈总量", "rawValue": 800},
            ],
            meta={"window_days": days},
        )

    async def review_summary(self, pool, factory_id):
        await self._enter(pool, "review_summary", factory_id)
        return {
            "factory_id": factory_id,
            "connected": True,
            "total_reviews": 100,
            "avg_star": 4.2,
            "avg_service": 4.1,
            "avg_env": 4.3,
            "avg_taste": 4.0,
            "low_star_count": 7,
            "high_star_count": 70,
            "vip_count": 20,
            "store_count": 1,
            "city_count": 1,
            "raw_review_text": "must-not-leak",
            "member_phone": "13800000000",
        }

    async def review_store_ranking(self, pool, factory_id, **kwargs):
        await self._enter(pool, "review_store_ranking", factory_id)
        return {
            "factory_id": factory_id,
            "connected": True,
            "stores": [
                {
                    "store": "A店",
                    "review_count": 100,
                    "avg_star": 4.2,
                    "avg_service": 4.1,
                    "avg_env": 4.3,
                    "avg_taste": 4.0,
                    "low_star_count": 7,
                }
            ],
        }

    async def review_dish_issues(self, pool, factory_id, **kwargs):
        await self._enter(pool, "review_dish_issues", factory_id)
        return {
            "factory_id": factory_id,
            "connected": True,
            "tags": [{"tag": "太咸", "count": 4}],
        }

    def bundle(self):
        return RestaurantReadSources(
            daily_trend=self.daily_trend,
            period_comparison=self.period_comparison,
            store_comparison=self.store_comparison,
            top_products=self.top_products,
            detect_price_anomalies=self.detect_price_anomalies,
            order_type_breakdown=self.order_type_breakdown,
            meal_period_breakdown=self.meal_period_breakdown,
            discount_summary=self.discount_summary,
            resolve_wastage_top=self.resolve_wastage_top,
            resolve_inventory_warning=self.resolve_inventory_warning,
            resolve_stock_shortage=self.resolve_stock_shortage,
            review_summary=self.review_summary,
            review_store_ranking=self.review_store_ranking,
            review_dish_issues=self.review_dish_issues,
        )


def runtime(fake_sources=None):
    sources = fake_sources or FakeSources()
    registry = build_restaurant_read_registry(
        sources.bundle(), today_factory=lambda: date(2026, 1, 10)
    )
    gateway = ReadToolGateway(
        FakePool(),
        registry,
        id_factory=lambda: "e-1",
        clock=lambda: datetime(2026, 1, 11, 12, tzinfo=timezone.utc),
    )
    trusted = TrustedExecutionContext(
        factory_id="F001",
        business_type="RESTAURANT",
        user_id="U1",
        correlation_id="corr-1",
        authorized_classifications=frozenset(DataClassification),
    )
    return sources, registry, gateway, trusted


def window(start="2026-01-01", end="2026-01-10", **extra):
    return {"startDate": start, "endDate": end, **extra}


def test_registry_contains_exactly_ten_explicit_readonly_tools():
    _, registry, _, _ = runtime()
    assert set(registry.names()) == EXPECTED_TOOLS
    assert len(registry.descriptors()) == 10
    assert all(d.access_mode == "READ_ONLY" for d in registry.descriptors())
    assert all(d.limits.max_llm_tokens == 0 for d in registry.descriptors())
    assert all(d.limits.max_bytes <= 100_000 for d in registry.descriptors())
    assert all(
        {name for name, _ in d.input_schema} == set(d.allowed_parameters)
        for d in registry.descriptors()
    )
    assert all("factoryId" not in d.allowed_parameters for d in registry.descriptors())


@pytest.mark.asyncio
@pytest.mark.parametrize("grain,max_facts", [("WEEK", 108), ("MONTH", 26)])
async def test_366_day_week_and_month_trends_fit_fact_budget(grain, max_facts):
    _, _, gateway, trusted = runtime()
    envelope = await gateway.execute(
        "restaurant_revenue_trend_read.v1",
        window("2025-01-01", "2026-01-01", grain=grain),
        trusted,
    )
    assert envelope.status is EvidenceStatus.OK
    assert len(envelope.facts) <= max_facts <= 200
    assert envelope.limits.rows_truncated == 0
    assert envelope.limits.bytes_returned <= 100_000
    assert envelope.limits.bytes_returned == len(envelope.to_json().encode("utf-8"))
    assert envelope.query_spec["grain"] == grain


@pytest.mark.asyncio
async def test_oversize_day_trend_fails_before_source_query_instead_of_truncating():
    sources, _, gateway, trusted = runtime()
    with pytest.raises(ReadToolContractError, match="use WEEK or MONTH"):
        await gateway.execute(
            "restaurant_revenue_trend_read.v1",
            window("2026-01-01", "2026-03-02", grain="DAY"),
            trusted,
        )
    assert not any(call[0] == "daily_trend" for call in sources.calls)


@pytest.mark.asyncio
@pytest.mark.parametrize("return_key", ["top_products", "items", "products"])
async def test_dish_adapter_explicitly_handles_current_and_legacy_result_keys(return_key):
    sources = FakeSources()
    sources.top_products_key = return_key
    _, _, gateway, trusted = runtime(sources)
    envelope = await gateway.execute(
        "restaurant_dish_margin_mix_read.v1",
        window(includeMargin=False),
        trusted,
    )
    assert any(f.metric == "revenue" and f.value == "100" for f in envelope.facts)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "basis,expected_numerator,expected_status",
    [
        ({}, 0, CoverageStatus.PARTIAL),
        (
            {
                "7": {
                    "coveredLines": 1,
                    "totalLines": 2,
                    "recipeApproved": True,
                    "purchasePriceTimeValid": True,
                    "unitConversionComplete": False,
                    "lineCoverageComplete": False,
                    "totalMaterialCost": 40,
                }
            },
            1,
            CoverageStatus.PARTIAL,
        ),
        (
            {
                "7": {
                    "coveredLines": 2,
                    "totalLines": 2,
                    "recipeApproved": True,
                    "purchasePriceTimeValid": True,
                    "unitConversionComplete": True,
                    "lineCoverageComplete": True,
                    "totalMaterialCost": 40,
                    "dataThrough": "2026-01-09",
                }
            },
            2,
            CoverageStatus.COMPLETE,
        ),
    ],
)
async def test_dish_material_cost_coverage_zero_partial_and_complete(
    basis, expected_numerator, expected_status
):
    sources = FakeSources()
    sources.cost_basis = basis
    _, _, gateway, trusted = runtime(sources)
    envelope = await gateway.execute(
        "restaurant_dish_margin_mix_read.v1",
        window(includeMargin=True),
        trusted,
    )
    gross = next(f for f in envelope.facts if f.metric == "dishGrossMargin")
    contribution = next(
        f for f in envelope.facts if f.metric == "dishContributionMargin"
    )
    assert gross.coverage.numerator == expected_numerator
    assert gross.coverage.status is expected_status
    if expected_status is CoverageStatus.COMPLETE:
        assert gross.value == "60"
        assert gross.status is EvidenceStatus.OK
    else:
        assert gross.value is None
        assert gross.status is EvidenceStatus.NOT_COMPUTABLE
    # A complete material basis is still not a complete contribution-cost basis.
    assert contribution.value is None
    assert contribution.status is EvidenceStatus.NOT_COMPUTABLE
    assert envelope.status is EvidenceStatus.PARTIAL


@pytest.mark.asyncio
async def test_mixed_unit_waste_and_stocktaking_totals_are_never_emitted():
    _, _, gateway, trusted = runtime()
    waste = await gateway.execute(
        "restaurant_waste_anomaly_read.v1", window(), trusted
    )
    stocktaking = await gateway.execute(
        "restaurant_stocktaking_variance_read.v1", window(), trusted
    )
    assert {fact.metric for fact in waste.facts} == {"wasteEventCount", "wasteCost"}
    assert {fact.metric for fact in stocktaking.facts} == {"stocktakingEventCount"}
    assert "999" not in waste.to_json()
    assert "1000" not in stocktaking.to_json()
    assert any(w.code == "MIXED_UNIT_QUANTITY_BLOCKED" for w in waste.warnings)
    assert any(w.code == "MIXED_UNIT_VARIANCE_BLOCKED" for w in stocktaking.warnings)


@pytest.mark.asyncio
async def test_historical_ops_window_fails_closed_without_querying_current_date_source():
    sources, _, gateway, trusted = runtime()
    envelope = await gateway.execute(
        "restaurant_waste_anomaly_read.v1",
        window("2025-12-01", "2025-12-31"),
        trusted,
    )
    assert envelope.status is EvidenceStatus.NOT_COMPUTABLE
    assert not any(call[0] == "resolve_wastage_top" for call in sources.calls)
    assert envelope.warnings[0].code == "EXPLICIT_HISTORICAL_WINDOW_UNSUPPORTED"


@pytest.mark.asyncio
async def test_review_tool_returns_aggregates_only_and_never_raw_text_or_member_data():
    _, _, gateway, trusted = runtime()
    envelope = await gateway.execute(
        "restaurant_review_signal_read.v1",
        {"topN": 10, "minReviews": 20, "starThreshold": 3},
        trusted,
    )
    rendered = envelope.to_json()
    assert envelope.status is EvidenceStatus.PARTIAL
    assert "must-not-leak" not in rendered
    assert "13800000000" not in rendered
    assert "太咸" in rendered
    assert "TAG_IS_NOT_DISH_NAME" in rendered
    assert all(f.freshness.data_through is None for f in envelope.facts)


@pytest.mark.asyncio
async def test_data_through_comes_from_source_not_generated_at():
    _, _, gateway, trusted = runtime()
    envelope = await gateway.execute(
        "restaurant_revenue_trend_read.v1", window(grain="DAY"), trusted
    )
    assert {fact.freshness.data_through for fact in envelope.facts} == {"2026-01-10"}
    assert envelope.generated_at.startswith("2026-01-11T12:00:00")
    assert all(fact.freshness.data_through not in envelope.generated_at for fact in envelope.facts)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "tool_name,parameters,expected_metric",
    [
        (
            "restaurant_period_comparison_read.v1",
            window(),
            "revenue",
        ),
        (
            "restaurant_store_performance_read.v1",
            window(topN=10),
            "averageTicket",
        ),
        (
            "restaurant_cost_movement_read.v1",
            {"baselineMode": "days", "windowDays": 90, "topN": 10},
            "unitPriceChange",
        ),
        (
            "restaurant_channel_discount_mix_read.v1",
            window(dimension="ORDER_TYPE", topN=10),
            "billCount",
        ),
        (
            "restaurant_inventory_risk_read.v1",
            {"asOf": "2026-01-10", "topN": 10},
            "reorderNowCount",
        ),
    ],
)
async def test_remaining_read_adapters_return_bounded_numeric_evidence(
    tool_name, parameters, expected_metric
):
    _, _, gateway, trusted = runtime()
    envelope = await gateway.execute(tool_name, parameters, trusted)
    assert expected_metric in {fact.metric for fact in envelope.facts}
    assert envelope.limits.facts_returned <= 200
    assert envelope.limits.rows_truncated == 0
    assert envelope.limits.bytes_returned == len(envelope.to_json().encode("utf-8"))
    assert all(
        fact.provenance_refs and fact.freshness and fact.coverage
        for fact in envelope.facts
        if fact.value is not None
    )
