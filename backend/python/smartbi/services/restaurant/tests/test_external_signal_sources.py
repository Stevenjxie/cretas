from __future__ import annotations

import json

import pytest

from smartbi.services.restaurant.external_signal_sources import (
    ExternalSignalRequest,
    InMemoryExternalSignalSnapshotStore,
    RestaurantExternalSignalService,
)


class FakeWeatherResponse:
    def __init__(
        self,
        payload: dict | None = None,
        text: str = "",
        status_code: int = 200,
        content: bytes | None = None,
    ) -> None:
        self.status_code = status_code
        self._payload = payload
        self.text = text
        self.content = content if content is not None else text.encode("utf-8")

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload or {}


class FakeWeatherClient:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def get(
        self,
        url: str,
        params: dict | None = None,
        headers: dict | None = None,
        timeout: float = 5.0,
    ) -> FakeWeatherResponse:
        self.calls.append(
            {
                "url": url,
                "params": params or {},
                "headers": headers or {},
                "timeout": timeout,
            }
        )
        return FakeWeatherResponse(
            payload={
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


class FakeCrawlerClient:
    def __init__(self, pages: dict[str, str]) -> None:
        self.pages = pages
        self.calls: list[dict] = []

    def get(self, url: str, headers: dict | None = None, timeout: float = 5.0) -> FakeWeatherResponse:
        self.calls.append({"url": url, "headers": headers or {}, "timeout": timeout})
        if url not in self.pages:
            return FakeWeatherResponse(text="", status_code=404)
        return FakeWeatherResponse(text=self.pages[url])


def test_external_signal_sources_report_key_requirements_without_secrets() -> None:
    service = RestaurantExternalSignalService(
        env={
            "QWEATHER_API_KEY": "qweather-secret",
            "QWEATHER_API_HOST": "abc123.qweatherapi.com",
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
        step["source"] == "和风天气" and step["productionStatus"] == "needs_key_host_and_location"
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
        env={
            "QWEATHER_API_KEY": "qweather-secret",
            "QWEATHER_API_HOST": "abc123.qweatherapi.com",
        },
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
    assert snapshot["signals"][0]["severity"] == "medium"
    assert snapshot["signals"][0]["plainImpact"]
    assert "外部原因" in snapshot["bossReadableSummary"]
    assert client.calls == [
        {
            "url": "https://abc123.qweatherapi.com/v7/weather/now",
            "params": {"location": "121.47,31.23"},
            "headers": {"X-QW-Api-Key": "qweather-secret"},
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


def test_collect_snapshot_skips_network_without_qweather_host() -> None:
    client = FakeWeatherClient()
    service = RestaurantExternalSignalService(
        env={"QWEATHER_API_KEY": "qweather-secret"},
        http_client=client,
    )

    snapshot = service.collect_snapshot(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            target_date="2026-07-01",
            lat=31.235,
            lng=121.475,
        ),
        store_id="store-001",
    )

    assert snapshot["budgetUsed"] == 0
    assert snapshot["signals"][0]["source"] == "和风天气"
    assert snapshot["signals"][0]["status"] == "skipped"
    assert "QWEATHER_API_HOST" in snapshot["signals"][0]["reason"]
    assert client.calls == []


def test_collect_for_stores_respects_daily_budget() -> None:
    client = FakeWeatherClient()
    service = RestaurantExternalSignalService(
        env={
            "QWEATHER_API_KEY": "qweather-secret",
            "QWEATHER_API_HOST": "abc123.qweatherapi.com",
        },
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


def test_collect_mall_activity_feeds_parses_allowed_public_pages() -> None:
    client = FakeCrawlerClient(
        {
            "https://mall.example/robots.txt": "User-agent: *\nAllow: /\n",
            "https://mall.example/events": """
                <html>
                  <head><title>第一百货夏日市集活动</title></head>
                  <body>
                    <h1>第一百货夏日市集活动</h1>
                    <p>7月6日-7月14日，六合路平台举办夏日市集和亲子互动。</p>
                  </body>
                </html>
            """,
        }
    )
    service = RestaurantExternalSignalService(http_client=client)

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            mall_name="第一百货商业中心",
            target_date="2026-07-01",
        ),
        feed_urls=["https://mall.example/events"],
    )

    assert result["status"] == "collected"
    assert result["fetched"] == 1
    assert result["events"][0]["title"] == "第一百货夏日市集活动"
    assert result["events"][0]["activityType"] == "市集/快闪"
    assert "7月6日-7月14日" in result["events"][0]["dateText"]
    assert result["events"][0]["targetRelevance"] == "upcoming"
    assert result["events"][0]["decisionUse"] == "即将发生：适合提前排班备货，不能解释目标日当天异常。"
    assert result["events"][0]["expectedImpact"] == "周末/晚市可能增强"
    assert client.calls[0]["url"] == "https://mall.example/robots.txt"
    assert "CretasRestaurantSignalBot" in client.calls[1]["headers"]["User-Agent"]


def test_collect_mall_activity_feeds_marks_active_event_for_target_day() -> None:
    client = FakeCrawlerClient(
        {
            "https://mall.example/robots.txt": "User-agent: *\nAllow: /\n",
            "https://mall.example/events": """
                <html><body>
                  <h1>第一百货夏日市集活动</h1>
                  <p>7月6日-7月14日，六合路平台举办夏日市集和亲子互动。</p>
                </body></html>
            """,
        }
    )
    service = RestaurantExternalSignalService(http_client=client)

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(
            city="上海",
            business_district="人民广场",
            mall_name="第一百货商业中心",
            target_date="2026-07-08",
        ),
        feed_urls=["https://mall.example/events"],
    )

    assert result["events"][0]["targetRelevance"] == "active"
    assert result["events"][0]["decisionUse"] == "目标日命中：可作为当天客流异常的重要外部解释。"


def test_collect_mall_activity_feeds_prefers_target_day_event_when_page_has_multiple_dates() -> None:
    client = FakeCrawlerClient(
        {
            "https://mall.example/robots.txt": "User-agent: *\nAllow: /\n",
            "https://mall.example/summer": """
                <html><body>
                  <h1>来南京路快乐一夏</h1>
                  <p>即日起至8月23日，南京路推出暑期主题消费活动。</p>
                  <p>7月底至8月初，电竞嘉年华重回世纪广场。</p>
                </body></html>
            """,
        }
    )
    service = RestaurantExternalSignalService(http_client=client)

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(
            city="上海",
            business_district="南京东路",
            mall_name="第一百货商业中心",
            target_date="2026-07-01",
        ),
        feed_urls=["https://mall.example/summer"],
    )

    assert result["events"][0]["dateText"] == "即日起至8月23日"
    assert result["events"][0]["targetRelevance"] == "active"
    assert result["events"][0]["decisionUse"] == "目标日命中：可作为当天客流异常的重要外部解释。"


def test_collect_mall_activity_feeds_decodes_gb18030_public_pages() -> None:
    class GbCrawlerClient:
        def __init__(self) -> None:
            self.calls: list[dict] = []

        def get(self, url: str, headers: dict | None = None, timeout: float = 5.0) -> FakeWeatherResponse:
            self.calls.append({"url": url, "headers": headers or {}, "timeout": timeout})
            if url.endswith("/robots.txt"):
                return FakeWeatherResponse(text="User-agent: *\nAllow: /\n")
            html = "<html><head><title>南京路快乐一夏主题活动</title></head><body>7月底至8月初 电竞嘉年华</body></html>"
            return FakeWeatherResponse(text="����", content=html.encode("gb18030"))

    service = RestaurantExternalSignalService(http_client=GbCrawlerClient())

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(city="上海", business_district="南京东路"),
        feed_urls=["https://mall.example/news"],
    )

    assert result["events"][0]["title"] == "南京路快乐一夏主题活动"
    assert result["events"][0]["activityType"] == "文体活动"


def test_collect_mall_activity_feeds_respects_robots_disallow() -> None:
    client = FakeCrawlerClient(
        {
            "https://mall.example/robots.txt": "User-agent: *\nDisallow: /private\n",
            "https://mall.example/private/events": "<h1>不应抓取</h1>",
        }
    )
    service = RestaurantExternalSignalService(http_client=client)

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(city="上海", business_district="人民广场"),
        feed_urls=["https://mall.example/private/events"],
    )

    assert result["status"] == "skipped"
    assert result["events"] == []
    assert result["sources"][0]["status"] == "robots_disallowed"
    assert [call["url"] for call in client.calls] == ["https://mall.example/robots.txt"]


def test_collect_mall_activity_feeds_rejects_wechat_history_crawling() -> None:
    client = FakeCrawlerClient({})
    service = RestaurantExternalSignalService(http_client=client)

    result = service.collect_mall_activity_feeds(
        ExternalSignalRequest(city="上海", business_district="人民广场"),
        feed_urls=["https://mp.weixin.qq.com/cgi-bin/appmsg?action=list_ex"],
    )

    assert result["status"] == "skipped"
    assert result["sources"][0]["status"] == "unsupported_platform_crawl"
    assert "公众号历史" in result["sources"][0]["reason"]
    assert client.calls == []
