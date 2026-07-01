from __future__ import annotations

import json

import pytest

from smartbi.services.restaurant.external_signal_sources import (
    ExternalSignalRequest,
    InMemoryExternalSignalSnapshotStore,
    RestaurantExternalSignalService,
)


class FakeWeatherResponse:
    def __init__(self, payload: dict) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


class FakeWeatherClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def get(self, url: str, params: dict, timeout: float) -> FakeWeatherResponse:
        self.calls.append({"url": url, "params": params, "timeout": timeout})
        return FakeWeatherResponse(
            {
                "code": "200",
                "now": {
                    "text": "小雨",
                    "temp": "31",
                    "feelsLike": "34",
                    "windDir": "东南风",
                    "windScale": "3",
                    "humidity": "80",
                },
                "updateTime": "2026-07-01T13:00+08:00",
            }
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
    assert context["collectionPipeline"]["defaultMode"] == "manual_or_cron"
    assert any(
        step["source"] == "和风天气" and step["productionStatus"] == "needs_key_and_location"
        for step in context["collectionPipeline"]["steps"]
    )


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


def test_collect_snapshot_fetches_weather_and_persists_safe_payload() -> None:
    client = FakeWeatherClient()
    store = InMemoryExternalSignalSnapshotStore()
    service = RestaurantExternalSignalService(
        env={"QWEATHER_API_KEY": "qweather-secret"},
        http_client=client,
        snapshot_store=store,
    )

    snapshot = service.collect_snapshot(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            mall_name="第一百货商业中心",
            target_date="2026-07-01",
            lat=31.235,
            lng=121.475,
        ),
        store_id="store-001",
    )

    assert snapshot["storeId"] == "store-001"
    assert snapshot["budgetUsed"] == 1
    assert snapshot["signals"][0]["source"] == "和风天气"
    assert snapshot["signals"][0]["title"] == "实时天气：小雨，31℃"
    assert snapshot["signals"][0]["plainImpact"]
    assert client.calls == [
        {
            "url": service.qweather_now_url,
            "params": {"location": "121.475000,31.235000", "key": "qweather-secret"},
            "timeout": 5.0,
        }
    ]

    persisted = store.latest("store-001", "2026-07-01")
    assert persisted is not None
    serialized = json.dumps(persisted, ensure_ascii=False)
    assert "qweather-secret" not in serialized


def test_collect_snapshot_skips_network_without_key_or_location() -> None:
    client = FakeWeatherClient()
    service = RestaurantExternalSignalService(env={}, http_client=client)

    snapshot = service.collect_snapshot(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            target_date="2026-07-01",
        ),
        store_id="store-001",
    )

    assert snapshot["budgetUsed"] == 0
    assert snapshot["signals"][0]["source"] == "和风天气"
    assert snapshot["signals"][0]["status"] == "skipped"
    assert "QWEATHER_API_KEY" in snapshot["signals"][0]["reason"]
    assert client.calls == []


def test_collect_for_stores_respects_daily_budget() -> None:
    client = FakeWeatherClient()
    service = RestaurantExternalSignalService(
        env={"QWEATHER_API_KEY": "qweather-secret"},
        http_client=client,
    )

    result = service.collect_for_stores(
        [
            {
                "storeId": "store-001",
                "city": "上海",
                "businessDistrict": "人民广场",
                "lat": 31.235,
                "lng": 121.475,
            },
            {
                "storeId": "store-002",
                "city": "上海",
                "businessDistrict": "陆家嘴",
                "lat": 31.24,
                "lng": 121.50,
            },
        ],
        target_date="2026-07-01",
        daily_budget=1,
    )

    assert result["budgetLimit"] == 1
    assert result["budgetUsed"] == 1
    assert result["collected"] == 1
    assert result["skipped"] == 1
    assert result["snapshots"][1]["status"] == "budget_skipped"
    assert len(client.calls) == 1
