from __future__ import annotations

"""External context signals for restaurant location analysis.

These sources explain one-day or one-week anomalies that internal POS data
cannot explain: weather, public holidays, nearby performances, and mall events.
Provider keys are read only from environment variables and are never returned.
"""

from dataclasses import dataclass
from datetime import date, datetime, timezone
from hashlib import md5
from html import unescape
import os
from pathlib import Path
import re
from typing import Any
from urllib.parse import urlparse

import httpx


CN_2026_HOLIDAYS = {
    "2026-01-01": "元旦假期",
    "2026-01-02": "元旦假期",
    "2026-01-03": "元旦假期",
    "2026-02-15": "春节假期",
    "2026-02-16": "春节假期",
    "2026-02-17": "春节假期",
    "2026-02-18": "春节假期",
    "2026-02-19": "春节假期",
    "2026-02-20": "春节假期",
    "2026-02-21": "春节假期",
    "2026-02-22": "春节假期",
    "2026-02-23": "春节假期",
    "2026-04-04": "清明节假期",
    "2026-04-05": "清明节假期",
    "2026-04-06": "清明节假期",
    "2026-05-01": "劳动节假期",
    "2026-05-02": "劳动节假期",
    "2026-05-03": "劳动节假期",
    "2026-05-04": "劳动节假期",
    "2026-05-05": "劳动节假期",
    "2026-06-19": "端午节假期",
    "2026-06-20": "端午节假期",
    "2026-06-21": "端午节假期",
    "2026-09-25": "中秋节假期",
    "2026-09-26": "中秋节假期",
    "2026-09-27": "中秋节假期",
    "2026-10-01": "国庆节假期",
    "2026-10-02": "国庆节假期",
    "2026-10-03": "国庆节假期",
    "2026-10-04": "国庆节假期",
    "2026-10-05": "国庆节假期",
    "2026-10-06": "国庆节假期",
    "2026-10-07": "国庆节假期",
}

CN_2026_ADJUSTED_WORKDAYS = {
    "2026-01-04",
    "2026-02-14",
    "2026-02-28",
    "2026-05-09",
    "2026-09-20",
    "2026-10-10",
}

PUBLIC_MALL_ACTIVITY_FEEDS = {
    "shanghai_nanjing_road": [
        "https://www.sh.chinanews.com.cn/yule/2026-06-27/147398.shtml",
        "https://www.gzw.sh.gov.cn/shgzw_zxzx_gqdt/20260129/01fb8dde5cad41998849c33f7a65298b.html",
    ]
}


@dataclass(frozen=True)
class ExternalSignalRequest:
    city: str
    business_district: str
    mall_name: str | None = None
    target_date: str | None = None
    lat: float | None = None
    lng: float | None = None


class InMemoryExternalSignalSnapshotStore:
    """Small test/demo store for external signal snapshots.

    Production callers can replace this with a DB-backed store. The service
    depends only on ``save(snapshot)`` and ``latest(store_id, target_date)``.
    """

    def __init__(self) -> None:
        self._items: list[dict[str, Any]] = []

    def save(self, snapshot: dict[str, Any]) -> dict[str, Any]:
        self._items.append(snapshot)
        return snapshot

    def latest(self, store_id: str, target_date: str) -> dict[str, Any] | None:
        for item in reversed(self._items):
            if item.get("storeId") == store_id and item.get("targetDate") == target_date:
                return item
        return None


class RestaurantExternalSignalService:
    """Builds source status and demo-ready anomaly context.

    Network fetch methods are intentionally explicit and are not used by the
    demo path unless the caller asks for them. This keeps tests deterministic
    and prevents accidental provider quota consumption.
    """

    qweather_legacy_now_url = "https://devapi.qweather.com/v7/weather/now"
    damai_gateway_url = "https://eco.taobao.com/router/rest"
    mall_activity_user_agent = "CretasRestaurantSignalBot/1.0 (+https://cretas.local/bot-policy)"

    def __init__(
        self,
        env: dict[str, str] | None = None,
        http_client: Any | None = None,
        snapshot_store: Any | None = None,
    ) -> None:
        self._env = env
        self._http_client = http_client or httpx
        self._snapshot_store = snapshot_store

    def build_context(self, request: ExternalSignalRequest) -> dict[str, Any]:
        target_day = self._target_day(request.target_date)
        holiday_signal = self._holiday_signal(target_day)
        weather_signal = self._weather_signal(request)
        damai_signal = self._damai_signal(request)
        mall_signal = self._mall_activity_signal(request)
        signals = [holiday_signal, weather_signal, damai_signal, mall_signal]

        return {
            "moduleName": "外部原因解释器",
            "purpose": "解释某天或某周销售/客流异常是不是天气、节假日、周边活动或商场活动导致。",
            "sourceStatuses": self.source_statuses(),
            "signals": signals,
            "collectionPipeline": self.collection_pipeline(request),
            "plainConclusion": self._plain_conclusion(signals),
            "bossActions": [
                "不要把节假日、演出散场、商场活动带来的短期增长直接外推到下周。",
                "如果外部活动带来新增客，重点做会员沉淀、点评引导和高峰备货复盘。",
                "如果天气或调休导致下滑，先调整排班和外卖承接，不要马上判断门店经营变差。",
                "商场活动缺统一 API，先用官网/公众号/小程序活动源做半自动采集，再逐步接物业后台。",
            ],
            "dataNeededForProduction": [
                "门店经纬度和所属商圈",
                "商场官网/公众号/小程序活动页 URL",
                "周边 1-3km 重点场馆列表",
                "和风天气 QWEATHER_API_KEY 和 QWEATHER_API_HOST",
                "大麦 DAMAI_APP_KEY 和 DAMAI_APP_SECRET",
            ],
        }

    def source_statuses(self) -> list[dict[str, Any]]:
        return [
            {
                "source": "和风天气",
                "keyRequired": True,
                "envVars": ["QWEATHER_API_KEY", "QWEATHER_API_HOST"],
                "status": self._configured_status("QWEATHER_API_KEY", "QWEATHER_API_HOST"),
                "refreshCadence": "小时级",
                "bestUse": "解释雨天、高温、寒潮、台风、空气质量和预警对堂食/外卖的影响。",
                "officialUrl": "https://dev.qweather.com/docs/api/",
            },
            {
                "source": "大麦开放平台",
                "keyRequired": True,
                "envVars": ["DAMAI_APP_KEY", "DAMAI_APP_SECRET"],
                "status": self._configured_status("DAMAI_APP_KEY", "DAMAI_APP_SECRET"),
                "refreshCadence": "日更/小时级",
                "bestUse": "识别附近演唱会、体育比赛、亲子演出、展览带来的临时客流。",
                "officialUrl": "https://developer.alibaba.com/docs/api.htm?apiId=71773",
            },
            {
                "source": "中国节假日/调休",
                "keyRequired": False,
                "envVars": [],
                "status": "可直接使用",
                "refreshCadence": "年度更新",
                "bestUse": "解释节假日、补班日和长假前后造成的异常客流。",
                "officialUrl": "https://www.gov.cn/zhengce/zhengceku/",
            },
            {
                "source": "商场活动采集",
                "keyRequired": False,
                "envVars": ["MALL_ACTIVITY_FEED_URLS"],
                "status": "可先半自动接入",
                "refreshCadence": "日更",
                "bestUse": "采集商场官网、公众号、小程序活动页，解释 IP 展、市集、会员日、品牌快闪带来的客流波动。",
                "officialUrl": None,
            },
        ]

    def collection_pipeline(self, request: ExternalSignalRequest) -> dict[str, Any]:
        has_location = request.lat is not None and request.lng is not None
        has_qweather = bool(self._env_value("QWEATHER_API_KEY"))
        has_qweather_host = bool(self._env_value("QWEATHER_API_HOST"))
        has_damai = bool(
            self._env_value("DAMAI_APP_KEY") and self._env_value("DAMAI_APP_SECRET")
        )
        has_mall_feeds = bool(self._mall_feed_urls())
        return {
            "defaultMode": "manual_or_cron",
            "whyNotOnPageLoad": "外部接口有每日额度，demo 页面打开不应自动消耗配额；由后台定时任务或管理员手动触发采集。",
            "dailyBudgetEnv": {
                "weather": "QWEATHER_DAILY_QUERY_BUDGET",
                "mapPoi": "AMAP_DAILY_QUERY_BUDGET / TENCENT_MAP_DAILY_QUERY_BUDGET / BAIDU_MAP_DAILY_QUERY_BUDGET",
            },
            "steps": [
                {
                    "source": "和风天气",
                    "productionStatus": (
                        "ready_to_collect"
                        if has_qweather and has_qweather_host and has_location
                        else "needs_key_host_and_location"
                    ),
                    "refreshCadence": "小时级/日更均可",
                    "storesOneApiCall": True,
                    "whatItWrites": ["天气现况", "体感温度", "湿度", "风力", "供应商更新时间"],
                },
                {
                    "source": "中国节假日/调休",
                    "productionStatus": "ready_without_key",
                    "refreshCadence": "年度更新",
                    "storesOneApiCall": False,
                    "whatItWrites": ["节假日标签", "补班标签"],
                },
                {
                    "source": "大麦开放平台",
                    "productionStatus": "ready_to_sign" if has_damai else "needs_key_and_api_scope",
                    "refreshCadence": "日更",
                    "storesOneApiCall": False,
                    "whatItWrites": ["活动类型", "场馆", "活动日期", "售票状态"],
                },
                {
                    "source": "商场活动采集",
                    "productionStatus": "ready_with_feed_urls" if has_mall_feeds else "needs_feed_urls",
                    "refreshCadence": "日更",
                    "storesOneApiCall": False,
                    "whatItWrites": ["活动标题", "活动日期", "楼层/场地", "品牌或活动类型"],
                },
            ],
        }

    def fetch_qweather_now(self, location_id: str) -> dict[str, Any]:
        api_key = self._env_value("QWEATHER_API_KEY")
        if not api_key:
            return {"available": False, "reason": "缺少 QWEATHER_API_KEY"}
        api_host = self._env_value("QWEATHER_API_HOST")
        if not api_host:
            return {"available": False, "reason": "缺少 QWEATHER_API_HOST"}
        response = self._http_client.get(
            self._qweather_now_url(api_host),
            params={"location": location_id},
            headers={"X-QW-Api-Key": api_key},
            timeout=5.0,
        )
        response.raise_for_status()
        return response.json()

    def collect_snapshot(
        self,
        request: ExternalSignalRequest,
        store_id: str,
        *,
        now: datetime | None = None,
        persist: bool = True,
    ) -> dict[str, Any]:
        """Collect one store/day external-signal snapshot.

        This is the production-facing pull boundary. It can consume provider
        quota, so the demo path keeps using ``build_context`` unless a cron or
        admin endpoint explicitly calls this method.
        """

        target_day = self._target_day(request.target_date)
        collected_at = (now or datetime.now(timezone.utc)).isoformat()
        weather_signal = self._collect_qweather_signal(request)
        signals = [
            weather_signal,
            self._holiday_signal(target_day),
            self._damai_collection_signal(request),
            self._mall_collection_signal(request),
        ]
        budget_used = sum(1 for signal in signals if signal.get("budgetCost"))
        snapshot = {
            "storeId": store_id,
            "targetDate": target_day.isoformat(),
            "city": request.city,
            "businessDistrict": request.business_district,
            "mallName": request.mall_name,
            "lat": request.lat,
            "lng": request.lng,
            "collectedAt": collected_at,
            "status": "collected" if any(signal.get("status") == "collected" for signal in signals) else "partial",
            "budgetUsed": budget_used,
            "signals": signals,
            "sourceStatuses": self.source_statuses(),
            "bossReadableSummary": self._plain_conclusion(signals),
        }
        if persist and self._snapshot_store is not None:
            self._snapshot_store.save(snapshot)
        return snapshot

    def collect_for_stores(
        self,
        stores: list[dict[str, Any]],
        *,
        target_date: str | None = None,
        daily_budget: int = 800,
    ) -> dict[str, Any]:
        """Collect snapshots for many stores with a hard daily quota cap."""

        snapshots: list[dict[str, Any]] = []
        budget_used = 0
        collected = 0
        skipped = 0
        for store in stores:
            store_id = str(store.get("storeId") or store.get("store_id") or "")
            expected_cost = 1 if self._can_collect_qweather(store) else 0
            if expected_cost and budget_used + expected_cost > daily_budget:
                snapshots.append(
                    {
                        "storeId": store_id,
                        "targetDate": self._target_day(target_date).isoformat(),
                        "status": "budget_skipped",
                        "budgetUsed": 0,
                        "reason": "已达到外部数据每日采集预算，留到下一批或明天采集。",
                    }
                )
                skipped += 1
                continue

            snapshot = self.collect_snapshot(
                ExternalSignalRequest(
                    city=str(store.get("city") or ""),
                    business_district=str(
                        store.get("businessDistrict")
                        or store.get("business_district")
                        or ""
                    ),
                    mall_name=store.get("mallName") or store.get("mall_name"),
                    target_date=target_date,
                    lat=store.get("lat"),
                    lng=store.get("lng"),
                ),
                store_id=store_id,
            )
            snapshots.append(snapshot)
            budget_used += int(snapshot.get("budgetUsed") or 0)
            collected += 1

        return {
            "targetDate": self._target_day(target_date).isoformat(),
            "budgetLimit": daily_budget,
            "budgetUsed": budget_used,
            "collected": collected,
            "skipped": skipped,
            "snapshots": snapshots,
        }

    def collect_mall_activity_feeds(
        self,
        request: ExternalSignalRequest,
        *,
        feed_urls: list[str] | None = None,
        max_pages: int = 5,
    ) -> dict[str, Any]:
        """Collect public mall activity pages without anti-bot bypass.

        The crawler is intentionally conservative: configured URLs only,
        explicit bot UA, robots check, no login/cookies/captcha bypass, and
        no WeChat public-account history crawling.
        """

        urls = feed_urls if feed_urls is not None else self._mall_feed_urls_for_request(request)
        events: list[dict[str, Any]] = []
        sources: list[dict[str, Any]] = []
        fetched = 0

        for raw_url in [url.strip() for url in urls if url and url.strip()][:max_pages]:
            blocked_reason = self._unsupported_platform_reason(raw_url)
            if blocked_reason:
                sources.append(
                    {
                        "url": raw_url,
                        "status": "unsupported_platform_crawl",
                        "reason": blocked_reason,
                    }
                )
                continue

            parsed = urlparse(raw_url)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                sources.append(
                    {
                        "url": raw_url,
                        "status": "invalid_url",
                        "reason": "只支持 http/https 公开 URL。",
                    }
                )
                continue

            if not self._robots_allowed(raw_url):
                sources.append(
                    {
                        "url": raw_url,
                        "status": "robots_disallowed",
                        "reason": "robots.txt 禁止抓取该路径。",
                    }
                )
                continue

            response = self._http_client.get(
                raw_url,
                headers={
                    "User-Agent": self.mall_activity_user_agent,
                    "Accept": "text/html,application/xhtml+xml",
                },
                timeout=8.0,
            )
            status_code = int(getattr(response, "status_code", 200) or 200)
            if status_code >= 400:
                sources.append(
                    {
                        "url": raw_url,
                        "status": "http_error",
                        "reason": f"HTTP {status_code}",
                    }
                )
                continue

            fetched += 1
            html = self._response_text(response)
            event = self._parse_mall_activity_html(raw_url, html, request)
            sources.append(
                {
                    "url": raw_url,
                    "status": "parsed" if event else "empty",
                    "reason": None if event else "页面里没有识别到活动标题。",
                }
            )
            if event:
                events.append(event)

        return {
            "type": "mall_activity_feed",
            "source": "商场活动采集",
            "status": "collected" if events else "skipped",
            "mallName": request.mall_name,
            "targetDate": self._target_day(request.target_date).isoformat(),
            "fetched": fetched,
            "events": events,
            "sources": sources,
            "policy": {
                "mode": "public_url_only",
                "userAgent": self.mall_activity_user_agent,
                "noBypass": True,
                "noLogin": True,
                "robotsRespected": True,
            },
        }

    def build_damai_signed_params(
        self,
        method: str,
        payload: dict[str, Any],
        timestamp: str | None = None,
    ) -> dict[str, str]:
        app_key = self._env_value("DAMAI_APP_KEY")
        app_secret = self._env_value("DAMAI_APP_SECRET")
        if not app_key or not app_secret:
            raise ValueError("缺少 DAMAI_APP_KEY 或 DAMAI_APP_SECRET")

        params: dict[str, str] = {
            "method": method,
            "app_key": app_key,
            "timestamp": timestamp or datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "format": "json",
            "v": "2.0",
            "sign_method": "md5",
        }
        params.update({key: str(value) for key, value in payload.items()})
        params["sign"] = self._taobao_md5_sign(params, app_secret)
        return params

    def _holiday_signal(self, target_day: date) -> dict[str, Any]:
        day_key = target_day.isoformat()
        holiday_name = CN_2026_HOLIDAYS.get(day_key)
        if holiday_name:
            return {
                "type": "holiday",
                "source": "中国节假日/调休",
                "severity": "high",
                "title": holiday_name,
                "plainImpact": "当天客流变化很可能受节假日影响，不能直接当作门店经营能力变化。",
                "actionHint": "按假期客流单独复盘备货、排班和点评沉淀，不要直接外推到普通工作日。",
            }
        if day_key in CN_2026_ADJUSTED_WORKDAYS:
            return {
                "type": "holiday",
                "source": "中国节假日/调休",
                "severity": "medium",
                "title": "调休补班日",
                "plainImpact": "补班日容易让午市像工作日、晚市像周末前后错位，排班和备货要单独看。",
                "actionHint": "把调休补班日从普通周末里剔除，避免误判门店周末表现。",
            }
        return {
            "type": "holiday",
            "source": "中国节假日/调休",
            "severity": "low",
            "title": "普通日期",
            "plainImpact": "没有明显官方节假日因素，需要继续看天气、活动、交通和商场活动。",
            "actionHint": "按普通工作日/周末逻辑分析，但保留其他外部信号校验。",
        }

    def _weather_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        configured = self._env_value("QWEATHER_API_KEY")
        return {
            "type": "weather",
            "source": "和风天气",
            "severity": "medium",
            "title": "天气影响待接入" if not configured else "天气影响可实时接入",
            "plainImpact": (
                f"{request.city}{request.business_district} 的雨天、高温、寒潮或预警会影响堂食、外卖和排队意愿。"
            ),
            "actionHint": "配置 QWEATHER_API_KEY 后，按小时天气和预警自动解释当天客流异常。",
        }

    def _damai_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        configured = self._env_value("DAMAI_APP_KEY") and self._env_value("DAMAI_APP_SECRET")
        return {
            "type": "nearby_event",
            "source": "大麦开放平台",
            "severity": "medium",
            "title": "周边演出活动待接入" if not configured else "周边演出活动可实时接入",
            "plainImpact": (
                f"{request.business_district} 周边演唱会、体育、亲子、展览活动可能造成晚市或周末突增客流。"
            ),
            "actionHint": "配置大麦 AppKey/Secret 后，每天拉取 1-3km 重点场馆活动，给异常日打活动标签。",
        }

    def _mall_activity_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        mall = request.mall_name or "目标商场"
        return {
            "type": "mall_activity",
            "source": "商场活动采集",
            "severity": "medium",
            "title": f"{mall} 活动源待配置",
            "plainImpact": "商场 IP 展、市集、品牌快闪、会员日会显著影响客流，且通常没有统一开放 API。",
            "actionHint": "先配置商场官网/公众号/小程序活动页，做日更采集；有物业合作后再接内部活动表。",
        }

    def _plain_conclusion(self, signals: list[dict[str, Any]]) -> str:
        strong = [signal for signal in signals if signal.get("severity") in {"high", "medium"}]
        if not strong:
            return "当天暂时没有明显外部解释，销售变化更需要回到门店自身、竞品和点评去查。"
        return "当天销售或客流异常不能只看门店内部，要先排查节假日、天气、周边活动和商场活动这些外部原因。"

    def _configured_status(self, *env_vars: str) -> str:
        return "已配置" if all(self._env_value(key) for key in env_vars) else "待配置"

    @property
    def qweather_now_url(self) -> str:
        api_host = self._env_value("QWEATHER_API_HOST")
        return self._qweather_now_url(api_host) if api_host else self.qweather_legacy_now_url

    def _qweather_now_url(self, api_host: str) -> str:
        host = api_host.strip().rstrip("/")
        if not host:
            return self.qweather_legacy_now_url
        if not host.startswith(("http://", "https://")):
            host = f"https://{host}"
        return f"{host}/v7/weather/now"

    def _env_value(self, key: str) -> str:
        if self._env is not None:
            return str(self._env.get(key) or "")
        value = os.environ.get(key)
        if value:
            return value
        settings_key = {
            "QWEATHER_API_KEY": "qweather_api_key",
            "QWEATHER_API_HOST": "qweather_api_host",
            "DAMAI_APP_KEY": "damai_app_key",
            "DAMAI_APP_SECRET": "damai_app_secret",
            "MALL_ACTIVITY_FEED_URLS": "mall_activity_feed_urls",
        }.get(key)
        if not settings_key:
            return ""
        try:
            from smartbi.config import get_settings

            value = str(getattr(get_settings(), settings_key, "") or "")
            if value:
                return value
        except Exception:
            pass
        return self._env_file_value(key)

    def _env_file_value(self, key: str) -> str:
        for env_path in self._env_file_candidates():
            if not env_path.exists() or not env_path.is_file():
                continue
            try:
                lines = env_path.read_text(encoding="utf-8-sig").splitlines()
            except OSError:
                continue
            for raw_line in lines:
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                name, value = line.split("=", 1)
                if name.strip() == key:
                    return value.strip().strip('"').strip("'")
        return ""

    def _env_file_candidates(self) -> list[Path]:
        module_path = Path(__file__).resolve()
        return [
            Path.cwd() / ".env",
            Path.cwd() / "smartbi" / ".env",
            module_path.parents[2] / ".env",
            module_path.parents[3] / ".env",
        ]

    def _can_collect_qweather(self, store: dict[str, Any]) -> bool:
        return bool(
            self._env_value("QWEATHER_API_KEY")
            and self._env_value("QWEATHER_API_HOST")
            and store.get("lat") is not None
            and store.get("lng") is not None
        )

    def _collect_qweather_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        if not self._env_value("QWEATHER_API_KEY"):
            return {
                "type": "weather",
                "source": "和风天气",
                "status": "skipped",
                "reason": "缺少 QWEATHER_API_KEY，未消耗接口额度。",
                "budgetCost": 0,
                "plainImpact": "天气源未接入时，只能提示需要校验天气，不能确认当天是否受雨天、高温或预警影响。",
                "actionHint": "把和风天气 Key 放到服务器环境变量或 .env 后，再由定时任务采集。",
            }
        if not self._env_value("QWEATHER_API_HOST"):
            return {
                "type": "weather",
                "source": "和风天气",
                "status": "skipped",
                "reason": "缺少 QWEATHER_API_HOST，未消耗接口额度。",
                "budgetCost": 0,
                "plainImpact": "和风新版 API 需要项目里的专属 API Host；只有 Key 时不能确认当天是否受天气影响。",
                "actionHint": "在和风控制台找到 API Host，填入 QWEATHER_API_HOST 后再采集。",
            }
        if request.lat is None or request.lng is None:
            return {
                "type": "weather",
                "source": "和风天气",
                "status": "skipped",
                "reason": "缺少门店经纬度，未消耗接口额度。",
                "budgetCost": 0,
                "plainImpact": "没有经纬度就无法按门店拉天气，只能按城市做粗略判断。",
                "actionHint": "先补门店 lat/lng，再做小时级天气解释。",
            }

        location = f"{float(request.lng):.2f},{float(request.lat):.2f}"
        payload = self.fetch_qweather_now(location)
        now_weather = payload.get("now") if isinstance(payload, dict) else None
        if not isinstance(now_weather, dict):
            return {
                "type": "weather",
                "source": "和风天气",
                "status": "error",
                "reason": "和风天气返回格式不符合预期。",
                "budgetCost": 1,
                "plainImpact": "天气接口已有调用，但本次结果不能用于经营判断。",
                "actionHint": "检查 provider 响应和门店 location 参数。",
            }

        text = str(now_weather.get("text") or "未知天气")
        temp = str(now_weather.get("temp") or "--")
        feels_like = now_weather.get("feelsLike")
        humidity = now_weather.get("humidity")
        return {
            "type": "weather",
            "source": "和风天气",
            "status": "collected",
            "severity": self._weather_severity(text, temp, feels_like),
            "title": f"实时天气：{text}，{temp}℃",
            "budgetCost": 1,
            "providerUpdatedAt": payload.get("updateTime"),
            "safePayload": {
                "text": text,
                "temp": temp,
                "feelsLike": feels_like,
                "humidity": humidity,
                "windDir": now_weather.get("windDir"),
                "windScale": now_weather.get("windScale"),
            },
            "plainImpact": self._weather_plain_impact(text, temp, feels_like),
            "actionHint": self._weather_action_hint(text, temp),
        }

    def _damai_collection_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        if not (self._env_value("DAMAI_APP_KEY") and self._env_value("DAMAI_APP_SECRET")):
            return {
                "type": "nearby_event",
                "source": "大麦开放平台",
                "status": "skipped",
                "reason": "缺少 DAMAI_APP_KEY 或 DAMAI_APP_SECRET，暂不拉取。",
                "budgetCost": 0,
                "plainImpact": "周边演出、展会、赛事可能解释晚市和周末异常，但当前只能作为待核验因素。",
                "actionHint": "等大麦权限确认后，再按城市、场馆和日期拉取活动。",
            }
        return {
            "type": "nearby_event",
            "source": "大麦开放平台",
            "status": "ready",
            "reason": "签名能力已就绪；正式拉取需要确认开放平台可用 API 和授权范围。",
            "budgetCost": 0,
            "plainImpact": f"{request.business_district} 周边演出活动可作为异常日外部解释源。",
            "actionHint": "先维护 1-3km 重点场馆清单，再按场馆和日期拉取活动。",
        }

    def _mall_collection_signal(self, request: ExternalSignalRequest) -> dict[str, Any]:
        feed_urls = self._mall_feed_urls()
        mall = request.mall_name or "目标商场"
        if not feed_urls:
            return {
                "type": "mall_activity",
                "source": "商场活动采集",
                "status": "skipped",
                "reason": "未配置 MALL_ACTIVITY_FEED_URLS。",
                "budgetCost": 0,
                "plainImpact": f"{mall} 的活动源还没配置，商场市集、IP 展、会员日只能人工核验。",
                "actionHint": "先把商场官网活动页、公众号文章列表或小程序活动页 URL 放入配置。",
            }
        crawl_result = self.collect_mall_activity_feeds(request, feed_urls=feed_urls)
        return {
            "type": "mall_activity",
            "source": "商场活动采集",
            "status": "collected" if crawl_result["events"] else "ready",
            "reason": "活动源 URL 已配置，已按公开页面采集。" if crawl_result["events"] else "活动源 URL 已配置，但本次未识别到活动。",
            "budgetCost": 0,
            "plainImpact": f"{mall} 的活动源可用于解释非节假日客流异常。",
            "actionHint": "每天采集标题、活动日期、楼层/场地和品牌，再与门店异常日匹配。",
            "events": crawl_result["events"],
            "sources": crawl_result["sources"],
        }

    def _mall_feed_urls(self) -> list[str]:
        raw = self._env_value("MALL_ACTIVITY_FEED_URLS")
        return [item.strip() for item in re.split(r"[\n,;]+", raw) if item.strip()]

    def _mall_feed_urls_for_request(self, request: ExternalSignalRequest) -> list[str]:
        configured_urls = self._mall_feed_urls()
        if configured_urls:
            return configured_urls
        context = f"{request.city} {request.business_district} {request.mall_name}"
        if any(keyword in context for keyword in ("南京东路", "人民广场", "第一百货", "百联ZX")):
            return PUBLIC_MALL_ACTIVITY_FEEDS["shanghai_nanjing_road"].copy()
        return []

    def _unsupported_platform_reason(self, raw_url: str) -> str | None:
        parsed = urlparse(raw_url)
        host = parsed.netloc.lower()
        query = parsed.query.lower()
        path = parsed.path.lower()
        if host == "mp.weixin.qq.com" and (
            "/cgi-bin/" in path or "appmsg" in path or "action=list" in query
        ):
            return "不抓取微信公众号历史/搜索接口；只支持人工投喂的单篇公开文章或商场官网活动页。"
        if any(host.endswith(domain) for domain in ("dianping.com", "xiaohongshu.com")):
            return "不抓取强平台内容；需要走官方授权、公开 API 或人工摘要。"
        return None

    def _robots_allowed(self, raw_url: str) -> bool:
        parsed = urlparse(raw_url)
        robots_url = f"{parsed.scheme}://{parsed.netloc}/robots.txt"
        response = self._http_client.get(
            robots_url,
            headers={"User-Agent": self.mall_activity_user_agent},
            timeout=5.0,
        )
        status_code = int(getattr(response, "status_code", 200) or 200)
        if status_code >= 400:
            return True
        return self._robots_text_allows(self._response_text(response), parsed.path or "/")

    def _response_text(self, response: Any) -> str:
        text = str(getattr(response, "text", "") or "")
        if text and "\ufffd" not in text:
            return text
        content = getattr(response, "content", None)
        if not content:
            return text
        candidates: list[str] = []
        for encoding in ("utf-8", "gb18030", "gbk"):
            try:
                candidates.append(bytes(content).decode(encoding))
            except UnicodeDecodeError:
                continue
        if not candidates:
            return text
        return min(candidates, key=lambda value: value.count("\ufffd"))

    def _robots_text_allows(self, robots_text: str, path: str) -> bool:
        applies = False
        for raw_line in robots_text.splitlines():
            line = raw_line.split("#", 1)[0].strip()
            if not line or ":" not in line:
                continue
            key, value = [part.strip() for part in line.split(":", 1)]
            key_lower = key.lower()
            value_lower = value.lower()
            if key_lower == "user-agent":
                applies = value_lower in {"*", "cretasrestaurantsignalbot"}
            elif applies and key_lower == "disallow":
                if value and path.startswith(value):
                    return False
        return True

    def _parse_mall_activity_html(
        self,
        raw_url: str,
        html: str,
        request: ExternalSignalRequest,
    ) -> dict[str, Any] | None:
        title = self._first_html_text(html, "h1") or self._first_html_text(html, "title")
        if not title:
            return None
        text = self._html_to_text(html)
        date_text = self._extract_activity_date(text, request)
        activity_type = self._classify_activity_type(f"{title} {text}")
        relevance = self._activity_target_relevance(date_text, request)
        return {
            "title": title,
            "dateText": date_text,
            "activityType": activity_type,
            "targetRelevance": relevance["status"],
            "decisionUse": relevance["decisionUse"],
            "mallName": request.mall_name,
            "city": request.city,
            "businessDistrict": request.business_district,
            "sourceUrl": raw_url,
            "expectedImpact": self._activity_expected_impact(activity_type),
            "bossAction": self._activity_boss_action(activity_type),
            "rawExcerpt": text[:180],
        }

    def _first_html_text(self, html: str, tag: str) -> str:
        match = re.search(rf"<{tag}[^>]*>(.*?)</{tag}>", html, flags=re.IGNORECASE | re.DOTALL)
        if not match:
            return ""
        return self._clean_text(match.group(1))

    def _html_to_text(self, html: str) -> str:
        without_script = re.sub(
            r"<(script|style)[^>]*>.*?</\1>",
            " ",
            html,
            flags=re.IGNORECASE | re.DOTALL,
        )
        return self._clean_text(re.sub(r"<[^>]+>", " ", without_script))

    def _clean_text(self, text: str) -> str:
        return re.sub(r"\s+", " ", unescape(re.sub(r"<[^>]+>", " ", text))).strip()

    def _extract_activity_date(self, text: str, request: ExternalSignalRequest) -> str:
        target_day = self._target_day(request.target_date)
        candidates = self._activity_date_candidates(text, target_day)
        if not candidates:
            return "待人工确认"
        return min(candidates, key=lambda candidate: candidate["rank"])["dateText"]

    def _activity_date_candidates(self, text: str, target_day: date) -> list[dict[str, Any]]:
        year = target_day.year
        candidates: list[dict] = []
        occupied_spans: list[tuple[int, int]] = []

        for marker in ("快乐一夏", "今夏", "暑期", "夏季"):
            position = text.find(marker)
            if position >= 0:
                candidates.append(
                    self._activity_candidate(
                        "今夏",
                        position,
                        date(year, 6, 1),
                        date(year, 8, 31),
                        target_day,
                        specificity=3,
                    )
                )
                occupied_spans.append((position, position + len(marker)))
                break

        today_range_pattern = re.compile(
            r"即日起\s*[-—~至到]\s*(?:(?P<em>\d{1,2})月)?(?P<ed>\d{1,2})日"
        )
        for match in today_range_pattern.finditer(text):
            end_month = int(match.group("em") or target_day.month)
            end_day = int(match.group("ed"))
            candidates.append(
                self._activity_candidate(
                    match.group(0),
                    match.start(),
                    date(year, 1, 1),
                    date(year, end_month, end_day),
                    target_day,
                )
            )
            occupied_spans.append(match.span())

        exact_range_pattern = re.compile(
            r"(?P<sm>\d{1,2})月(?P<sd>\d{1,2})日\s*[-—~至到]\s*(?:(?P<em>\d{1,2})月)?(?P<ed>\d{1,2})日"
        )
        for match in exact_range_pattern.finditer(text):
            start_month = int(match.group("sm"))
            start_day = int(match.group("sd"))
            end_month = int(match.group("em") or start_month)
            end_day = int(match.group("ed"))
            candidates.append(
                self._activity_candidate(
                    match.group(0),
                    match.start(),
                    date(year, start_month, start_day),
                    date(year, end_month, end_day),
                    target_day,
                )
            )
            occupied_spans.append(match.span())

        fuzzy_range_pattern = re.compile(
            r"(?P<sm>\d{1,2})月(?P<sp>上旬|中旬|下旬|初|底|末)\s*[-—~至到]\s*(?P<em>\d{1,2})月(?P<ep>上旬|中旬|下旬|初|底|末)"
        )
        for match in fuzzy_range_pattern.finditer(text):
            start = self._fuzzy_month_window(year, int(match.group("sm")), match.group("sp"))[0]
            end = self._fuzzy_month_window(year, int(match.group("em")), match.group("ep"))[1]
            candidates.append(
                self._activity_candidate(match.group(0), match.start(), start, end, target_day)
            )
            occupied_spans.append(match.span())

        exact_pattern = re.compile(r"(?P<m>\d{1,2})月(?P<d>\d{1,2})日")
        for match in exact_pattern.finditer(text):
            if self._is_span_inside(match.span(), occupied_spans):
                continue
            event_day = date(year, int(match.group("m")), int(match.group("d")))
            candidates.append(
                self._activity_candidate(match.group(0), match.start(), event_day, event_day, target_day)
            )

        fuzzy_pattern = re.compile(r"(?P<m>\d{1,2})月(?P<p>上旬|中旬|下旬|初|底|末)")
        for match in fuzzy_pattern.finditer(text):
            if self._is_span_inside(match.span(), occupied_spans):
                continue
            start, end = self._fuzzy_month_window(year, int(match.group("m")), match.group("p"))
            candidates.append(
                self._activity_candidate(match.group(0), match.start(), start, end, target_day)
            )

        return candidates

    def _activity_candidate(
        self,
        date_text: str,
        position: int,
        start: date,
        end: date,
        target_day: date,
        *,
        specificity: int = 0,
    ) -> dict[str, Any]:
        status_order = 0
        distance = 0
        if target_day < start:
            status_order = 1
            distance = (start - target_day).days
        elif target_day > end:
            status_order = 2
            distance = (target_day - end).days
        return {
            "dateText": date_text,
            "window": (start, end),
            "rank": (status_order, specificity, distance, position),
        }

    def _is_span_inside(self, span: tuple[int, int], occupied_spans: list[tuple[int, int]]) -> bool:
        return any(start <= span[0] and span[1] <= end for start, end in occupied_spans)

    def _activity_target_relevance(
        self,
        date_text: str,
        request: ExternalSignalRequest,
    ) -> dict[str, str]:
        target_day = self._target_day(request.target_date)
        window = self._activity_date_window(date_text, target_day.year)
        if not window:
            return {
                "status": "unknown",
                "decisionUse": "活动日期待人工确认：只能作为背景信号，不能直接解释目标日异常。",
            }
        start, end = window
        if start <= target_day <= end:
            return {
                "status": "active",
                "decisionUse": "目标日命中：可作为当天客流异常的重要外部解释。",
            }
        if target_day < start:
            return {
                "status": "upcoming",
                "decisionUse": "即将发生：适合提前排班备货，不能解释目标日当天异常。",
            }
        return {
            "status": "expired",
            "decisionUse": "活动已过：只能用于历史复盘，不能解释目标日当天异常。",
        }

    def _activity_date_window(self, date_text: str, year: int) -> tuple[date, date] | None:
        if date_text == "今夏":
            return date(year, 6, 1), date(year, 8, 31)

        today_range = re.search(
            r"即日起\s*[-—~至到]\s*(?:(?P<em>\d{1,2})月)?(?P<ed>\d{1,2})日",
            date_text,
        )
        if today_range:
            end_month = int(today_range.group("em") or 12)
            end_day = int(today_range.group("ed"))
            return date(year, 1, 1), date(year, end_month, end_day)

        exact_range = re.search(
            r"(?P<sm>\d{1,2})月(?P<sd>\d{1,2})日\s*[-—~至到]\s*(?:(?P<em>\d{1,2})月)?(?P<ed>\d{1,2})日",
            date_text,
        )
        if exact_range:
            sm = int(exact_range.group("sm"))
            sd = int(exact_range.group("sd"))
            em = int(exact_range.group("em") or sm)
            ed = int(exact_range.group("ed"))
            return date(year, sm, sd), date(year, em, ed)

        exact = re.search(r"(?P<m>\d{1,2})月(?P<d>\d{1,2})日", date_text)
        if exact:
            event_day = date(year, int(exact.group("m")), int(exact.group("d")))
            return event_day, event_day

        fuzzy_range = re.search(
            r"(?P<sm>\d{1,2})月(?P<sp>上旬|中旬|下旬|初|底|末)\s*[-—~至到]\s*(?P<em>\d{1,2})月(?P<ep>上旬|中旬|下旬|初|底|末)",
            date_text,
        )
        if fuzzy_range:
            start = self._fuzzy_month_window(year, int(fuzzy_range.group("sm")), fuzzy_range.group("sp"))[0]
            end = self._fuzzy_month_window(year, int(fuzzy_range.group("em")), fuzzy_range.group("ep"))[1]
            return start, end

        fuzzy = re.search(r"(?P<m>\d{1,2})月(?P<p>上旬|中旬|下旬|初|底|末)", date_text)
        if fuzzy:
            return self._fuzzy_month_window(year, int(fuzzy.group("m")), fuzzy.group("p"))
        return None

    def _fuzzy_month_window(self, year: int, month: int, period: str) -> tuple[date, date]:
        if period in {"上旬", "初"}:
            return date(year, month, 1), date(year, month, 10)
        if period == "中旬":
            return date(year, month, 11), date(year, month, 20)
        if period in {"下旬", "底", "末"}:
            last_day = 31 if month in {1, 3, 5, 7, 8, 10, 12} else 30
            if month == 2:
                last_day = 29 if year % 4 == 0 else 28
            return date(year, month, 21), date(year, month, last_day)
        return date(year, month, 1), date(year, month, 1)

    def _classify_activity_type(self, text: str) -> str:
        if any(keyword in text for keyword in ("演出", "音乐", "赛事", "体育", "电竞", "嘉年华", "篮球", "足球")):
            return "文体活动"
        if any(keyword in text for keyword in ("市集", "快闪", "IP展", "IP 展")):
            return "市集/快闪"
        if any(keyword in text for keyword in ("亲子", "儿童", "家庭")):
            return "亲子活动"
        if any(keyword in text for keyword in ("会员日", "会员")):
            return "会员日"
        if any(keyword in text for keyword in ("展览", "艺术展", "装置")):
            return "展览活动"
        return "综合活动"

    def _activity_expected_impact(self, activity_type: str) -> str:
        return {
            "市集/快闪": "周末/晚市可能增强",
            "亲子活动": "周末亲子客可能增强",
            "会员日": "老客/会员到店可能增强",
            "文体活动": "散场后晚市可能增强",
            "展览活动": "游客和拍照客可能增强",
        }.get(activity_type, "需结合活动时间人工判断")

    def _activity_boss_action(self, activity_type: str) -> str:
        return {
            "市集/快闪": "提前准备高峰排队和小食饮品加购，活动后复盘新增客是否沉淀。",
            "亲子活动": "周末优先保证儿童座椅、家庭套餐和等位体验。",
            "会员日": "把会员权益和复购券放到当天结账链路里。",
            "文体活动": "关注散场后一小时，提前安排晚市补位和外带承接。",
            "展览活动": "门口展示和点评页要强调招牌菜与不踩雷。",
        }.get(activity_type, "先给异常日打活动标签，再和销售、排队、点评一起复盘。")

    def _weather_plain_impact(self, text: str, temp: str, feels_like: Any) -> str:
        weather_text = text.lower()
        if "雨" in text or "雪" in text or "雷" in text:
            return "雨雪天气通常会压低自然到店和排队意愿，但可能把需求转到外卖；堂食下滑不能直接判定门店变差。"
        try:
            temp_value = float(temp)
            feels_value = float(feels_like) if feels_like is not None else temp_value
        except (TypeError, ValueError):
            return "天气已采集，需要结合堂食、外卖和排队数据判断影响。"
        if max(temp_value, feels_value) >= 34:
            return "高温会降低远距离到店和排队意愿，冷饮、小食和外卖承接更重要。"
        if temp_value <= 3:
            return "低温会影响逛街和排队，热菜、热饮和外卖承接要提前准备。"
        if "晴" in weather_text:
            return "天气本身不是负面因素，如果客流仍下滑，应优先查竞品、商场动线和门店体验。"
        return "天气已采集，可作为当天客流异常的外部解释之一。"

    def _weather_severity(self, text: str, temp: str, feels_like: Any) -> str:
        if any(marker in text for marker in ("暴雨", "大雨", "雷暴", "台风", "暴雪", "冰雹")):
            return "high"
        try:
            temp_value = float(temp)
            feels_value = float(feels_like) if feels_like is not None else temp_value
        except (TypeError, ValueError):
            temp_value = None
            feels_value = None
        if temp_value is not None and (max(temp_value, feels_value or temp_value) >= 35 or temp_value <= 0):
            return "high"
        if any(marker in text for marker in ("雨", "雪", "雷", "雾", "霾")):
            return "medium"
        if temp_value is not None and (max(temp_value, feels_value or temp_value) >= 32 or temp_value <= 3):
            return "medium"
        return "low"

    def _weather_action_hint(self, text: str, temp: str) -> str:
        if "雨" in text or "雪" in text or "雷" in text:
            return "当天复盘时把堂食和外卖拆开看；雨天不要只用堂食下滑评价店长。"
        try:
            if float(temp) >= 34:
                return "高温日减少门口硬排队，提前推预约、外卖和高毛利饮品组合。"
        except ValueError:
            pass
        return "把天气标签写入异常日复盘，和销售、排队、差评一起看。"

    def _target_day(self, value: str | None) -> date:
        if not value:
            return date(2026, 7, 1)
        raw = value[:10]
        try:
            return date.fromisoformat(raw)
        except ValueError:
            pass
        try:
            if len(value) >= 7 and value[4] == "-" and value[5:7].isdigit():
                return date.fromisoformat(f"{value[:7]}-01")
        except ValueError:
            pass
        if len(value) >= 7 and value[4:6] == "-Q" and value[6].isdigit():
            quarter = max(1, min(4, int(value[6])))
            return date(int(value[:4]), ((quarter - 1) * 3) + 1, 1)
        return date(2026, 7, 1)

    def _taobao_md5_sign(self, params: dict[str, str], app_secret: str) -> str:
        raw = app_secret + "".join(f"{key}{params[key]}" for key in sorted(params)) + app_secret
        return md5(raw.encode("utf-8")).hexdigest().upper()
