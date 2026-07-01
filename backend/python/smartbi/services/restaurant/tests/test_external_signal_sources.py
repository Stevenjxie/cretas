from __future__ import annotations

import json

import pytest

from smartbi.services.restaurant.external_signal_sources import (
    ExternalSignalRequest,
    RestaurantExternalSignalService,
)


def test_external_signal_sources_report_key_requirements_without_secrets() -> None:
    service = RestaurantExternalSignalService(
        env={
            "QWEATHER_API_KEY": "qweather-secret",
            "DAMAI_APP_KEY": "damai-app-key",
            "DAMAI_APP_SECRET": "damai-secret",
        }
    )

    context = service.build_context(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            mall_name="第一百货商业中心",
            target_date="2026-05-01",
        )
    )

    sources = {item["source"]: item for item in context["sourceStatuses"]}
    assert sources["和风天气"]["status"] == "已配置"
    assert sources["大麦开放平台"]["status"] == "已配置"
    assert sources["中国节假日/调休"]["keyRequired"] is False
    assert sources["商场活动采集"]["status"] == "可先半自动接入"
    assert any(signal["title"] == "劳动节假期" for signal in context["signals"])
    assert "节假日" in context["plainConclusion"] or "外部原因" in context["plainConclusion"]

    serialized = json.dumps(context, ensure_ascii=False)
    assert "qweather-secret" not in serialized
    assert "damai-secret" not in serialized
    assert "damai-app-key" not in serialized


def test_external_signal_sources_are_demo_ready_without_keys() -> None:
    service = RestaurantExternalSignalService(env={})

    context = service.build_context(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            mall_name="大丸百货",
            target_date="2026-07-01",
        )
    )

    sources = {item["source"]: item for item in context["sourceStatuses"]}
    assert sources["和风天气"]["status"] == "待配置"
    assert sources["大麦开放平台"]["status"] == "待配置"
    assert any(signal["source"] == "商场活动采集" for signal in context["signals"])
    assert any("QWEATHER_API_KEY" in item for item in context["dataNeededForProduction"])
    assert any("DAMAI_APP_KEY" in item for item in context["dataNeededForProduction"])


def test_external_signal_sources_accept_month_quarter_and_invalid_periods() -> None:
    service = RestaurantExternalSignalService(env={})

    for period in ["2026-02", "2026-Q4", "current"]:
        context = service.build_context(
            ExternalSignalRequest(
                city="上海",
                business_district="人民广场",
                target_date=period,
            )
        )
        assert context["moduleName"] == "外部原因解释器"
        assert context["signals"]


def test_damai_signed_params_require_keys_and_hide_secret() -> None:
    service = RestaurantExternalSignalService(
        env={"DAMAI_APP_KEY": "app-key", "DAMAI_APP_SECRET": "app-secret"}
    )

    params = service.build_damai_signed_params(
        "alibaba.damai.maitix.project.distribution.querybypage",
        {"param": '{"cityName":"上海"}'},
        timestamp="2026-07-01 12:00:00",
    )

    assert params["app_key"] == "app-key"
    assert params["sign_method"] == "md5"
    assert len(params["sign"]) == 32
    assert "app-secret" not in json.dumps(params)


def test_damai_signed_params_fail_fast_without_keys() -> None:
    service = RestaurantExternalSignalService(env={})

    with pytest.raises(ValueError, match="DAMAI_APP_KEY"):
        service.build_damai_signed_params(
            "alibaba.damai.maitix.project.distribution.querybypage",
            {"param": "{}"},
        )
