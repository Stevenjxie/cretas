from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

import smartbi.agent.synthesis_engine as se
from smartbi.agent.dimension_catalog import DIMENSIONS, missing_status
from smartbi.agent.factbook import FactBook
from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine
from smartbi.gold.queries import (
    restaurant_dimension_signals,
    supplier_price_coverage,
)


def _migrations_dir() -> Path:
    return Path(__file__).resolve().parents[1] / "smartbi" / "database" / "migrations"


def test_comprehensive_or_decision_questions_auto_expand_but_lookup_stays_narrow():
    engine = ComprehensiveSynthesisEngine(pool=object())

    comprehensive = engine.plan_dimensions("请做一次综合经营分析并给出优化方案")
    assert comprehensive["analysis_mode"] == "decision"
    assert comprehensive["auto_expand"] is True
    for key in (
        "review", "finance", "sales", "dish_margin", "traffic", "operations",
        "staffing", "external_signals", "holiday", "weather", "channel",
        "meal_period", "discount", "period_comparison", "supplier_anomaly",
    ):
        assert comprehensive[key] is True

    lookup = engine.plan_dimensions("这个月营收多少")
    assert lookup["analysis_mode"] == "lookup"
    assert lookup["auto_expand"] is False
    assert lookup["finance"] is True
    assert lookup["external_signals"] is False
    assert lookup["operations"] is False

    targeted = engine.plan_dimensions("分析一下本月菜品销量")
    assert targeted["analysis_mode"] == "lookup"
    assert targeted["sales"] is True
    assert targeted["auto_expand"] is False

    generic = engine.plan_dimensions("帮我分析一下")
    assert generic["auto_expand"] is True


def test_missing_dimension_guidance_is_deterministic_and_never_turns_missing_into_zero():
    fb = FactBook(missing_dimensions=[
        missing_status("physical_traffic", reason="没有物理客流"),
        missing_status("competitor", reason="没有竞品数据"),
    ])

    answer = ComprehensiveSynthesisEngine._append_dimension_guidance("当前营收下降。", fb)

    assert "还可补充的分析维度" in answer
    assert "商场及门前物理客流" in answer
    assert "竞品与商圈" in answer
    assert "保持为空，不按 0 处理" in answer


def test_demo_disclaimer_is_appended_even_when_no_dimension_is_missing():
    fb = FactBook(data_mode="DEMO")

    answer = ComprehensiveSynthesisEngine._append_dimension_guidance("经营正常。", fb)

    assert "Demo 展示数据" in answer
    assert "SIMULATED" in answer
    assert "还可补充的分析维度" not in answer


def test_single_store_and_missing_comparison_remain_missing_but_stable_supplier_data_is_available():
    engine = ComprehensiveSynthesisEngine(pool=object())
    fb = FactBook(
        finance={
            "total_revenue": 1000,
            "bill_count": 10,
            "customer_count": 18,
            "store_count": 1,
            "day_count": 1,
            "top_stores": [{"store_id": 1, "store_name": "单店", "revenue": 1000}],
        },
        period_comparison={
            "revenue": {
                "available": True,
                "yoy_available": False,
                "mom_available": False,
            },
        },
        supplier_anomaly={
            "anomalies": [],
            "coverage": {
                "observation_count": 12,
                "ingredient_count": 3,
                "supplier_count": 2,
            },
        },
    )
    requested_plan = {"auto_expand": True}

    engine._populate_dimension_coverage(
        fb,
        requested_plan=requested_plan,
        plan=dict(requested_plan),
        results={},
        factory_id="REAL_RESTAURANT",
    )

    available = {item["code"] for item in fb.available_dimensions}
    missing = {item["code"] for item in fb.missing_dimensions}
    assert {"revenue", "guest_traffic", "supplier_cost"} <= available
    assert {"store_comparison", "period_comparison"} <= missing
    assert "未检出达到规则阈值" in fb.to_prompt_text()


class _Acquire:
    def __init__(self, conn):
        self.conn = conn

    async def __aenter__(self):
        return self.conn

    async def __aexit__(self, exc_type, exc, tb):
        return None


class _SignalConn:
    def __init__(self, rows):
        self.rows = rows
        self.calls = []

    async def fetch(self, sql, *params):
        self.calls.append((sql, params))
        return self.rows


class _CoverageConn:
    def __init__(self):
        self.calls = []

    async def fetchrow(self, sql, *params):
        self.calls.append((sql, params))
        return {
            "observation_count": 12,
            "ingredient_count": 3,
            "supplier_count": 2,
            "first_date": date(2026, 7, 1),
            "last_date": date(2026, 7, 20),
        }


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        return _Acquire(self.conn)


@pytest.mark.asyncio
async def test_optional_dimension_signal_reader_preserves_simulated_evidence_and_sparse_semantics():
    conn = _SignalConn([
        {
            "period_start": date(2026, 7, 1),
            "period_end": date(2026, 7, 1),
            "source_code": "internal_seed_traffic",
            "source_name": "Demo模拟客流",
            "compliance_level": "internal_seed",
            "metric_code": "mall_footfall",
            "metric_name": "商场客流",
            "metric_value": 46000,
            "metric_unit": "人次",
            "dimension": {"dimension_code": "physical_traffic"},
        },
        {
            "period_start": date(2026, 7, 2),
            "period_end": date(2026, 7, 2),
            "source_code": "internal_seed_traffic",
            "source_name": "Demo模拟客流",
            "compliance_level": "internal_seed",
            "metric_code": "mall_footfall",
            "metric_name": "商场客流",
            "metric_value": 50000,
            "metric_unit": "人次",
            "dimension": {"dimension_code": "physical_traffic"},
        },
    ])

    result = await restaurant_dimension_signals(
        _Pool(conn), "DEMO_REST", (date(2026, 7, 1), date(2026, 7, 2))
    )

    traffic = result["dimensions"]["physical_traffic"]
    assert traffic["evidence_level"] == "SIMULATED"
    assert traffic["metrics"][0]["average"] == 48000.0
    assert len(traffic["series"]) == 2
    sql, params = conn.calls[0]
    assert "o.factory_id = $1" in sql
    assert params[0] == "DEMO_REST"
    assert "DEMO_REST" not in sql  # no hard-coded demo fallback in the real reader


@pytest.mark.asyncio
async def test_supplier_price_coverage_distinguishes_stable_data_from_no_data():
    conn = _CoverageConn()
    result = await supplier_price_coverage(
        _Pool(conn), "REAL_RESTAURANT", (date(2026, 7, 1), date(2026, 7, 31))
    )

    assert result == {
        "observation_count": 12,
        "ingredient_count": 3,
        "supplier_count": 2,
        "first_date": "2026-07-01",
        "last_date": "2026-07-20",
    }
    sql, params = conn.calls[0]
    assert "factory_id = $1" in sql
    assert params == ("REAL_RESTAURANT", date(2026, 7, 1), date(2026, 7, 31))


@pytest.mark.asyncio
async def test_auto_expand_builds_all_available_dimensions_when_sources_exist(monkeypatch):
    async def value(result):
        return result

    monkeypatch.setattr(se, "finance_summary", lambda *args, **kwargs: value({
        "total_revenue": 10000.0,
        "bill_count": 100,
        "customer_count": 180,
        "avg_bill_value": 100.0,
        "avg_per_capita": 55.56,
        "store_count": 2,
        "day_count": 2,
        "actual_start_date": "2026-07-01",
        "actual_end_date": "2026-07-02",
        "top_stores": [
            {"store_id": 1, "store_name": "A店", "revenue": 6000, "bill_count": 55},
            {"store_id": 2, "store_name": "B店", "revenue": 4000, "bill_count": 45},
        ],
        "gross_profit": 6500.0,
    }))
    monkeypatch.setattr(se, "period_comparison", lambda *args, **kwargs: value({
        "revenue": {"available": True, "mom_available": True, "mom_pct": 5.0},
    }))
    monkeypatch.setattr(se, "store_comparison", lambda *args, **kwargs: value({
        "stores": [
            {"store_id": 1, "store_name": "A店", "revenue": 6000, "bill_count": 55, "avg_ticket": 109.09},
            {"store_id": 2, "store_name": "B店", "revenue": 4000, "bill_count": 45, "avg_ticket": 88.89},
        ],
    }))
    monkeypatch.setattr(se, "top_products", lambda *args, **kwargs: value({
        "top_products": [{"product_name": "招牌鱼", "revenue": 3000, "qty_sold": 40}],
    }))
    monkeypatch.setattr(se, "channel_breakdown", lambda *args, **kwargs: value({"channels": []}))
    monkeypatch.setattr(se, "discount_breakdown", lambda *args, **kwargs: value({"discounts": []}))
    monkeypatch.setattr(se, "dish_margin", lambda *args, **kwargs: value({
        "dish_count": 1,
        "cost_basis_complete": True,
        "cost_basis": "restaurant_sku_forms",
        "top_margin": [{"dish_name": "招牌鱼", "selling_price": 88, "unit_cost": 28, "gross_profit": 60, "gross_margin_pct": 68.18}],
        "low_margin": [],
    }))
    monkeypatch.setattr(se, "order_type_breakdown", lambda *args, **kwargs: value({
        "order_types": [{"order_type": "堂食", "revenue": 7000, "bill_count": 70, "avg_ticket": 100, "revenue_pct": 70}],
    }))
    monkeypatch.setattr(se, "meal_period_breakdown", lambda *args, **kwargs: value({
        "meal_periods": [{"meal_period": "晚市", "revenue": 6000, "bill_count": 60, "avg_ticket": 100, "revenue_pct": 60}],
    }))
    monkeypatch.setattr(se, "discount_summary", lambda *args, **kwargs: value({
        "total_discount_amount": 500,
        "total_revenue": 10000,
        "revenue_share_pct": 5,
        "discounts": [{"discount_name": "满减", "amount": 500, "share_pct": 100}],
    }))
    monkeypatch.setattr(se, "detect_price_anomalies", lambda *args, **kwargs: value([
        {"ingredientName": "青花椒", "supplierName": "供应商", "deltaPct": 12, "direction": "UP", "oldPrice": 100, "newPrice": 112, "riskLevel": "MEDIUM", "anomalyDeliveryDate": "2026-07-02"},
    ]))
    monkeypatch.setattr(se, "supplier_price_coverage", lambda *args, **kwargs: value({
        "observation_count": 20,
        "ingredient_count": 4,
        "supplier_count": 3,
        "first_date": "2026-07-01",
        "last_date": "2026-07-02",
    }))
    monkeypatch.setattr(se, "daily_trend", lambda *args, **kwargs: value({
        "points": [
            {"date": "2026-07-01", "revenue": 4000, "bill_count": 40},
            {"date": "2026-07-02", "revenue": 6000, "bill_count": 60},
        ],
    }))
    monkeypatch.setattr(se, "weather_daily", lambda *args, **kwargs: value({
        "days": [
            {"date": "2026-07-01", "rain_mm": 0, "source_code": "seed", "source_name": "模拟天气", "evidence_level": "SIMULATED"},
            {"date": "2026-07-02", "rain_mm": 10, "source_code": "seed", "source_name": "模拟天气", "evidence_level": "SIMULATED"},
        ],
    }))
    monkeypatch.setattr(se, "restaurant_operations_summary", lambda *args, **kwargs: value({
        "waste": {"available": True, "count": 2, "cost": 100},
        "stocktaking": {"available": True, "count": 1},
        "inventory": {"available": True, "snapshot_date": "2026-07-02", "high_count": 1, "medium_count": 2, "items": []},
        "staffing": {"available": True, "items": [{"daypart": "晚市", "weekday_type": "weekday", "avg_orders": 100, "staff_on_duty": 4, "target_orders_per_staff": 22, "actual_orders_per_staff": 25}]},
    }))
    external_dimensions = {
        code: {
            "dimension_code": code,
            "evidence_level": "SIMULATED",
            "sources": [{"source_code": "seed", "source_name": "Demo信号"}],
            "metrics": [{"metric_code": f"{code}_metric", "metric_name": code, "unit": "index", "days": 1, "average": 1, "minimum": 1, "maximum": 1, "sum": 1, "latest": 1, "latest_date": "2026-07-01"}],
            "series": [{"date": "2026-07-01", "value": 1}],
        }
        for code in ("physical_traffic", "holiday", "mall_activity", "nearby_event", "competitor")
    }
    external_dimensions["promotion"] = {
        "dimension_code": "promotion",
        "evidence_level": "SIMULATED",
        "sources": [{"source_code": "seed", "source_name": "Demo活动"}],
        "metrics": [
            {"metric_code": "campaign_exposure", "metric_name": "活动曝光", "unit": "人次", "days": 1, "average": 1000, "minimum": 1000, "maximum": 1000, "sum": 1000, "latest": 1000, "latest_date": "2026-07-01"},
            {"metric_code": "campaign_redemption", "metric_name": "活动核销", "unit": "单", "days": 1, "average": 100, "minimum": 100, "maximum": 100, "sum": 100, "latest": 100, "latest_date": "2026-07-01"},
        ],
        "series": [],
    }
    monkeypatch.setattr(se, "restaurant_dimension_signals", lambda *args, **kwargs: value({
        "dimensions": external_dimensions,
    }))

    monkeypatch.setattr(se, "review_summary", lambda *args, **kwargs: value({
        "total_reviews": 100, "avg_star": 4.3, "low_star_count": 5,
    }))
    for name in (
        "review_vip", "review_platform", "review_time_period", "review_good_tags",
        "review_dish_issues", "review_store_ranking",
    ):
        monkeypatch.setattr(se, name, lambda *args, **kwargs: value({}))

    engine = ComprehensiveSynthesisEngine(pool=object(), budget_tracker=object(), cache=object())
    plan = engine.plan_dimensions("请综合分析经营情况并给出优化方案")
    fb = await engine._build_factbook(
        "DEMO_REST",
        (date(2026, 7, 1), date(2026, 7, 2)),
        plan,
        period="2026-07-01 至 2026-07-02",
    )

    assert {item["code"] for item in fb.available_dimensions} == {
        item.code for item in DIMENSIONS
    }
    assert fb.missing_dimensions == []
    assert fb.data_mode == "DEMO"
    text = fb.to_prompt_text()
    assert "SIMULATED" in text
    assert "商场及门前物理客流" in text
    assert "库存、损耗与人效" in text


def test_demo_seed_has_fixed_long_window_all_dimension_sources_and_demo_isolation():
    path = _migrations_dir() / "V20261029_01__demo_rest_comprehensive_dimensions.sql"
    sql = path.read_text(encoding="utf-8")

    assert "DATE '2025-07-01'" in sql
    assert "DATE '2026-07-31'" in sql
    assert "generate_series" in sql
    assert "WHERE a.factory_id = 'DEMO_REST'" in sql
    assert "internal_seed" in sql
    for metric in (
        "mall_footfall", "storefront_passersby", "store_visits",
        "mall_activity_intensity", "nearby_event_attendance",
        "competitor_count", "campaign_exposure", "campaign_redemption",
    ):
        assert metric in sql
    assert "INSERT INTO agg_daily_cost" in sql
    assert "INSERT INTO agg_product" in sql
    assert "INSERT INTO agg_supplier_price" in sql
    assert "INSERT INTO agg_restaurant_daily_totals" in sql
    assert "INSERT INTO fact_inventory_snapshot" in sql
    assert "INSERT INTO fact_staffing_daypart" in sql
    assert "米饭" not in sql
    assert "餐巾纸" not in sql
