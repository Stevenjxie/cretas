from __future__ import annotations

from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.advanced_traffic_persona import (
    AdvancedTrafficPersonaHandler,
)


def test_advanced_traffic_persona_demo_contract() -> None:
    handler = AdvancedTrafficPersonaHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_DEMO",
            upload_id=None,
            sub_sector="火锅",
            store_name="海底捞火锅(人民广场店)",
            period="2026-06",
            params={
                "city": "上海",
                "business_district": "人民广场",
                "mall_name": "第一百货商业中心",
            },
        ),
        context={},
    )

    assert response.status == SectionStatus.OK
    assert response.section_name == "advanced_traffic_persona"

    data = response.data
    assert data["requiresEnablement"] is True
    assert data["demoMode"] is True
    assert data["enablement"]["status"] == "需额外开通"
    assert data["storeContext"]["businessDistrict"] == "人民广场"

    provider_names = {provider["provider"] for provider in data["providers"]}
    assert {"腾讯位置大数据", "百度慧眼/百度地图", "高德/商业位置数据"}.issubset(provider_names)

    field_keys = {field["key"] for field in data["fieldCatalog"]}
    assert {
        "daily_footfall",
        "source_area_top3",
        "dwell_time_distribution",
        "consumption_power_index",
        "competitor_overlap_index",
    }.issubset(field_keys)

    assert data["simulatedMetrics"]["dailyFootfall"] > 10000
    assert data["simulatedMetrics"]["weekdayWeekendLift"] > 1
    assert len(data["analysis"]["recommendations"]) >= 5
    assert any(
        "午市" in item["action"] or "排队" in item["action"]
        for item in data["analysis"]["recommendations"]
    )
    assert "模拟" in data["dataNote"]


def test_advanced_traffic_persona_registered_in_section_router() -> None:
    from smartbi.api.restaurant_sections import HANDLERS

    assert "advanced_traffic_persona" in HANDLERS
