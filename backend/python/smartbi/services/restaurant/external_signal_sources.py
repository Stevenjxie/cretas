from __future__ import annotations

"""External context signals for restaurant location analysis.

These sources explain one-day or one-week anomalies that internal POS data
cannot explain: weather, public holidays, nearby performances, and mall events.
Provider keys are read only from environment variables and are never returned.
"""

from dataclasses import dataclass
from datetime import date, datetime
from hashlib import md5
import os
from typing import Any

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


@dataclass(frozen=True)
class ExternalSignalRequest:
    city: str
    business_district: str
    mall_name: str | None = None
    target_date: str | None = None
    lat: float | None = None
    lng: float | None = None


class RestaurantExternalSignalService:
    """Builds source status and demo-ready anomaly context.

    Network fetch methods are intentionally explicit and are not used by the
    demo path unless the caller asks for them. This keeps tests deterministic
    and prevents accidental provider quota consumption.
    """

    qweather_now_url = "https://devapi.qweather.com/v7/weather/now"
    damai_gateway_url = "https://eco.taobao.com/router/rest"

    def __init__(self, env: dict[str, str] | None = None) -> None:
        self._env = env if env is not None else os.environ

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
                "和风天气 QWEATHER_API_KEY",
                "大麦 DAMAI_APP_KEY 和 DAMAI_APP_SECRET",
            ],
        }

    def source_statuses(self) -> list[dict[str, Any]]:
        return [
            {
                "source": "和风天气",
                "keyRequired": True,
                "envVars": ["QWEATHER_API_KEY"],
                "status": self._configured_status("QWEATHER_API_KEY"),
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

    def fetch_qweather_now(self, location_id: str) -> dict[str, Any]:
        api_key = self._env.get("QWEATHER_API_KEY")
        if not api_key:
            return {"available": False, "reason": "缺少 QWEATHER_API_KEY"}
        response = httpx.get(
            self.qweather_now_url,
            params={"location": location_id, "key": api_key},
            timeout=5.0,
        )
        response.raise_for_status()
        return response.json()

    def build_damai_signed_params(
        self,
        method: str,
        payload: dict[str, Any],
        timestamp: str | None = None,
    ) -> dict[str, str]:
        app_key = self._env.get("DAMAI_APP_KEY")
        app_secret = self._env.get("DAMAI_APP_SECRET")
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
        configured = self._env.get("QWEATHER_API_KEY")
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
        configured = self._env.get("DAMAI_APP_KEY") and self._env.get("DAMAI_APP_SECRET")
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
        return "已配置" if all(self._env.get(key) for key in env_vars) else "待配置"

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
