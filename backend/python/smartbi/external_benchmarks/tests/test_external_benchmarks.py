from __future__ import annotations

import pytest

from smartbi.external_benchmarks.collectors import (
    AmapPoiCollector,
    AmapWeatherCollector,
    IndustryReportSeedCollector,
    TencentPlaceCollector,
    TencentWeatherCollector,
    parse_moa_wholesale_text,
    parse_nbs_catering_text,
    redact_sensitive_url,
)
from smartbi.external_benchmarks.service import ExternalBenchmarkService
from smartbi.external_benchmarks.sources import assert_source_allowed_for_collection
from smartbi.external_benchmarks.taxonomy import CATEGORY_PROFILES, CHANNEL_PROFILES, TRADE_AREA_PROFILES, profile_rows


def test_nbs_parser_extracts_catering_income_and_yoy():
    text = "2026年1-5月，餐饮收入23488亿元，增长3.1%。5月份，餐饮收入4605亿元，增长0.6%。"

    observations = parse_nbs_catering_text(
        text,
        source_url="https://www.stats.gov.cn/example.html",
        source_title="2026 release",
        fallback_year=2026,
    )

    values = {(o.metric_code, o.period_start.isoformat(), o.period_end.isoformat()): o.metric_value for o in observations}
    assert values[("catering_revenue", "2026-01-01", "2026-05-31")] == 23488.0
    assert values[("catering_revenue_yoy", "2026-01-01", "2026-05-31")] == 3.1
    assert values[("catering_revenue", "2026-05-01", "2026-05-31")] == 4605.0
    assert values[("catering_revenue_yoy", "2026-05-01", "2026-05-31")] == 0.6


def test_platform_raw_review_collection_requires_authorized_source():
    with pytest.raises(ValueError):
        assert_source_allowed_for_collection(
            "nbs_catering_retail",
            "https://www.dianping.com/shop/123/review_all",
            raw_reviews=True,
        )


@pytest.mark.asyncio
async def test_amap_collector_skips_without_api_key():
    result = await AmapPoiCollector(api_key="").collect_density(
        location="121.4737,31.2304",
        keywords="火锅",
    )

    assert result.status == "skipped"
    assert result.observations == []
    assert "AMAP_API_KEY" in result.error_message


def test_amap_url_uses_official_pagination_params_and_can_be_redacted():
    url = AmapPoiCollector.build_around_url(
        api_key="secret-key",
        location="121.4737,31.2304",
        keywords="hotpot",
    )

    assert "offset=25" in url
    assert "page=1" in url
    assert "page_size" not in url
    assert "page_num" not in url
    assert "secret-key" not in redact_sensitive_url(url)
    assert "key=<redacted>" in redact_sensitive_url(url)


def test_amap_weather_url_can_be_redacted():
    url = AmapWeatherCollector.build_weather_url(
        api_key="secret-key",
        city_adcode="310000",
    )

    assert "weatherInfo" in url
    assert "city=310000" in url
    assert "secret-key" not in redact_sensitive_url(url)
    assert "key=<redacted>" in redact_sensitive_url(url)


def test_tencent_place_url_uses_nearby_boundary_and_can_be_redacted():
    url = TencentPlaceCollector.build_nearby_url(
        api_key="secret-key",
        location="121.4737,31.2304",
        keywords="hotpot",
    )

    assert "place/v1/search" in url
    assert "boundary=nearby%2831.230400%2C121.473700%2C3000%29" in url
    assert "page_size=20" in url
    assert "page_index=1" in url
    assert "secret-key" not in redact_sensitive_url(url)
    assert "key=<redacted>" in redact_sensitive_url(url)


def test_tencent_weather_url_can_be_redacted():
    url = TencentWeatherCollector.build_weather_url(
        api_key="secret-key",
        city_adcode="310000",
    )

    assert "weather/v1" in url
    assert "adcode=310000" in url
    assert "secret-key" not in redact_sensitive_url(url)
    assert "key=<redacted>" in redact_sensitive_url(url)


def test_tencent_daily_budget_defaults_to_place_search_headroom(monkeypatch):
    monkeypatch.delenv("TENCENT_MAP_DAILY_QUERY_BUDGET", raising=False)
    assert ExternalBenchmarkService().tencent_map_daily_budget() == 1600


def test_moa_wholesale_parser_extracts_daily_indexes_and_ingredient_prices():
    text = (
        "日期：2026-06-30 据农业农村部监测，6月30日“农产品批发价格200指数”为110.50，"
        "比昨天下降0.10个点，“菜篮子”产品批发价格指数为110.10，比昨天下降0.12个点。"
        "截至今日14:00时，全国农产品批发市场猪肉平均价格为14.31元/公斤，比昨天上升0.2%；"
        "牛肉66.53元/公斤，比昨天上升0.1%；羊肉64.18元/公斤，比昨天上升0.2%；"
        "鸡蛋9.51元/公斤，与昨天持平；白条鸡17.44元/公斤，与昨天持平。"
        "重点监测的28种蔬菜平均价格为4.18元/公斤，比昨天下降0.2%；"
        "重点监测的6种水果平均价格为7.28元/公斤，比昨天上升1.3%。"
        "鲫鱼20.12元/公斤，比昨天上升0.3%；鲤鱼14.67元/公斤，比昨天上升0.4%；"
        "白鲢鱼10.25元/公斤，比昨天下降1.0%；大带鱼42.37元/公斤，比昨天上升7.2%。"
    )

    observations = parse_moa_wholesale_text(
        text,
        source_url="https://scs.moa.gov.cn/jcyj/202606/t20260630_6485405.htm",
        source_title="6月30日：“农产品批发价格200指数”比昨天下降0.10个点",
        fallback_year=2026,
    )

    by_code = {obs.metric_code: obs for obs in observations if obs.metric_code != "ingredient_wholesale_price"}
    ingredient_prices = {
        obs.dimension["ingredient_code"]: obs.metric_value
        for obs in observations
        if obs.metric_code == "ingredient_wholesale_price"
    }
    assert by_code["agri_wholesale_200_index"].metric_value == 110.50
    assert by_code["basket_product_index"].raw_payload["daily_change_points"] == -0.12
    assert ingredient_prices["pork"] == 14.31
    assert ingredient_prices["beef"] == 66.53
    assert ingredient_prices["large_hairtail"] == 42.37


def test_industry_seed_has_official_and_report_benchmarks():
    result = IndustryReportSeedCollector().collect()
    metric_codes = {obs.metric_code for obs in result.observations}
    labels = {obs.confidence_label for obs in result.observations}

    assert result.status == "success"
    assert "catering_revenue" in metric_codes
    assert "restaurant_chain_rate" in metric_codes
    assert "official" in labels
    assert "report_excerpt" in labels


def test_amap_daily_budget_defaults_to_safe_headroom(monkeypatch):
    monkeypatch.delenv("AMAP_DAILY_QUERY_BUDGET", raising=False)
    assert ExternalBenchmarkService().amap_daily_budget() == 800


def test_amap_daily_budget_invalid_env_falls_back(monkeypatch):
    monkeypatch.setenv("AMAP_DAILY_QUERY_BUDGET", "not-a-number")
    assert ExternalBenchmarkService().amap_daily_budget() == 800


def test_segment_profiles_cover_core_categories_and_channels():
    category_codes = {profile.profile_code for profile in CATEGORY_PROFILES}
    channel_codes = {profile.profile_code for profile in CHANNEL_PROFILES}
    trade_area_codes = {profile.profile_code for profile in TRADE_AREA_PROFILES}

    assert {"hotpot", "qsr_fast_food", "tea_drinks", "coffee", "fish_seafood"} <= category_codes
    assert {"dine_in", "delivery", "group_buy", "mall_store", "street_store"} <= channel_codes
    assert {"office_district", "community", "shopping_mall", "nightlife"} <= trade_area_codes


def test_segment_profile_weights_are_actionable():
    rows = list(profile_rows())
    assert len(rows) >= 12
    for row in rows:
        total_weight = sum(row["dimension_weights"].values())
        assert 0.99 <= total_weight <= 1.01
        assert len(row["external_signal_plan"]) >= 2
        assert len(row["analysis_questions"]) >= 2
        assert len(row["action_templates"]) >= 2
