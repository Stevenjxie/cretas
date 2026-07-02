from __future__ import annotations

"""Demo scenarios for boss-facing restaurant action routing.

These fixtures are intentionally synthetic and deterministic. They let the
web admin demo show why different owner actions trigger without depending on
live customer authorization or paid traffic/persona feeds.
"""

from copy import deepcopy
from typing import Any


_BASE_MENU = {
    "topProducts": [
        {"name": "招牌青花椒鱼[活鱼现做]", "revenue": 237060, "soldQty": 1030, "foodCost": 92800},
        {"name": "手作冰豆花", "revenue": 27880, "soldQty": 697, "foodCost": 6970},
        {"name": "小炒现切吊龙", "revenue": 24480, "soldQty": 360, "foodCost": 9043},
    ],
    "products": [
        {"name": "米饭", "revenue": 13000, "soldQty": 1300, "foodCost": 3120},
    ],
    "basketPairs": [
        {"left": "招牌青花椒鱼[活鱼现做]", "right": "手作冰豆花", "orders": 697},
    ],
}


_BASE_TRAFFIC_PERSONA = {
    "demoMode": True,
    "providerBlend": ["腾讯位置大数据模拟", "百度慧眼模拟", "高德POI模拟"],
    "mallFootfall": 46800,
    "restaurantFloorFootfall": 12800,
    "storefrontPassersby": 4200,
    "estimatedStoreVisits": 828,
    "captureRate": 0.214,
    "weekdayWeekendLift": 1.36,
    "avgDwellMinutes": 74,
    "competitorOverlapIndex": 0.67,
    "trafficTrend": {
        "mallVsLastWeekPct": 12.4,
        "restaurantFloorVsLastWeekPct": 9.8,
        "storefrontVsLastWeekPct": 6.5,
        "storeOrdersVsTrafficPct": -2.6,
    },
    "customerProfiles": [
        {"segment": "周边办公午餐客", "share": 0.38, "need": "快、稳定、不踩雷"},
        {"segment": "亲子/家庭周末客", "share": 0.27, "need": "等位可控、菜品稳定"},
        {"segment": "商场逛街随机客", "share": 0.21, "need": "门口一眼知道招牌和价格带"},
    ],
    "sourceAreas": [
        {"area": "颛桥地铁站/办公楼", "share": 0.34},
        {"area": "龙湖天街周边社区", "share": 0.29},
        {"area": "莘庄/春申商圈外溢", "share": 0.16},
    ],
    "hourlyHotspots": [
        {"hour": "11:30-13:00", "trafficShare": 0.31, "conversionNote": "午市人流高但停留短"},
        {"hour": "18:00-20:00", "trafficShare": 0.42, "conversionNote": "晚市客流集中，等位和出餐会放大差评"},
    ],
    "mallBenchmarks": {
        "sameFloorPeerCaptureRate": 0.22,
        "categoryMedianCaptureRate": 0.21,
        "sameFloorPeerAvgDwellMinutes": 68,
    },
    "competitors": [
        {"name": "同层酸菜鱼竞品A", "distanceMeters": 85, "estimatedQueueLiftPct": 18},
        {"name": "同层川菜小馆B", "distanceMeters": 120, "estimatedQueueLiftPct": 9},
    ],
}


_BASE_PLATFORM_CHANNELS = [
    {
        "platform": "美团/大众点评",
        "orders": 3518,
        "revenue": 807560,
        "exposure": 186000,
        "storePageVisits": 21800,
        "couponRedemptions": 1820,
        "conversionRate": 0.161,
        "rating": 4.73,
        "mainProblem": "曝光足够，但双人套餐和招牌菜承接还可以更直接",
    },
    {
        "platform": "抖音团购",
        "orders": 826,
        "revenue": 164780,
        "exposure": 142000,
        "storePageVisits": 9600,
        "couponRedemptions": 510,
        "conversionRate": 0.118,
        "rating": 4.62,
        "mainProblem": "曝光高，核销弱，需要更短视频化的套餐名和门店引导",
    },
    {
        "platform": "微信/小程序/POS堂食",
        "orders": 2857,
        "revenue": 676420,
        "exposure": 0,
        "storePageVisits": 0,
        "couponRedemptions": 0,
        "conversionRate": None,
        "rating": None,
        "mainProblem": "老客和现场客为主，适合沉淀会员券和复购提醒",
    },
    {
        "platform": "外卖平台",
        "orders": 574,
        "revenue": 136072,
        "exposure": 48000,
        "storePageVisits": 6200,
        "couponRedemptions": 0,
        "conversionRate": 0.112,
        "rating": 4.55,
        "mainProblem": "雨天可承接，但要避开出餐慢和包装差评",
    },
]


_BASE_PARAMS: dict[str, Any] = {
    "store_name": "青花椒川食山语（颛桥龙湖店）",
    "sub_sector": "鱼类餐饮/砂锅鱼",
    "city": "上海",
    "business_district": "颛桥龙湖天街",
    "mall_name": "闵行龙湖天街",
    "pos_summary": {
        "orders": 7775,
        "revenue": 1784831.9,
        "customers": 19713,
        "aov": 229.56,
        "weeklyTrend": [
            {"week": "2026-W25", "revenue": 127143.68, "orders": 538, "aov": 236.33},
            {"week": "2026-W26", "revenue": 140457.98, "orders": 616, "aov": 228.02, "wowRevenuePct": 10.5, "wowOrdersPct": 14.5},
        ],
        "weekdayWeekend": {"weekdayAvgDailyRevenue": 15801.98, "weekendAvgDailyRevenue": 28534.66, "gapPct": 80.6},
        "daypartRevenue": [
            {"name": "午市", "share": 0.39},
            {"name": "晚市", "share": 0.60},
        ],
        "customerSegments": [
            {"segment": "2人桌", "share": 0.49},
            {"segment": "3人桌", "share": 0.25},
        ],
        "channelGroups": [
            {"channel": "美团/大众点评", "share": 0.4524},
            {"channel": "微信", "share": 0.4257},
        ],
        "chainRank": {"revenueRank": 6, "dailyRank": 2, "storeCount": 8},
        "platformChannels": _BASE_PLATFORM_CHANNELS,
        "posCompleteness": {
            "hasHourlyOrders": True,
            "hasSkuCost": True,
            "hasTableSize": True,
            "hasQueueMinutes": True,
        },
    },
    "menu_summary": _BASE_MENU,
    "review_summary": {
        "rating": 4.73,
        "reviewCount": 688,
        "lowRatingCount": 41,
        "positiveDishMentions": [
            {"name": "招牌青花椒鱼", "count": 171},
            {"name": "手作冰豆花", "count": 65},
        ],
        "negativeDishMentions": [{"name": "小炒现切吊龙", "count": 18}],
        "negativeThemes": [{"theme": "等位略久", "count": 16}],
    },
    "monthly_stocktake": {
        "topLossItems": ["活鱼边角损耗", "青花椒底料"],
        "varianceItems": ["活鱼实际耗用略高于理论值"],
    },
    "financial_summary": {
        "revenue": 1784831.9,
        "foodCostRatio": 0.38,
        "grossMarginPct": 62,
    },
    "external_signals": {
        "weather": {"text": "阵雨，晚市到店略受影响"},
        "activities": [{"title": "龙湖天街周末会员日", "expectedTrafficLiftPct": 18}],
    },
    "traffic_persona": _BASE_TRAFFIC_PERSONA,
}


_SCENARIO_PATCHES: dict[str, dict[str, Any]] = {
    "package": {},
    "traffic_conversion": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 17200, "weekendAvgDailyRevenue": 19800, "gapPct": 15.1},
            "dailyAnomaly": {"date": "2026-07-01", "revenueVsBaselinePct": -9.5, "ordersVsBaselinePct": -7.8},
            "daypartRevenue": [{"name": "午市", "share": 0.26}, {"name": "晚市", "share": 0.64}],
            "platformChannels": [
                {
                    "platform": "美团/大众点评",
                    "orders": 2980,
                    "revenue": 671500,
                    "exposure": 208000,
                    "storePageVisits": 24500,
                    "couponRedemptions": 1280,
                    "conversionRate": 0.122,
                    "rating": 4.71,
                    "mainProblem": "页面访问不少，但双人套餐和招牌鱼表达不够直接",
                },
                {
                    "platform": "抖音团购",
                    "orders": 610,
                    "revenue": 121300,
                    "exposure": 166000,
                    "storePageVisits": 11800,
                    "couponRedemptions": 360,
                    "conversionRate": 0.052,
                    "rating": 4.58,
                    "mainProblem": "曝光高、核销低，短视频引流没有被套餐承接",
                },
                {
                    "platform": "微信/小程序/POS堂食",
                    "orders": 2470,
                    "revenue": 579200,
                    "mainProblem": "现场客多，但门口转化和会员沉淀不足",
                },
            ],
        },
        "traffic_persona": {
            "storefrontPassersby": 5600,
            "estimatedStoreVisits": 690,
            "captureRate": 0.123,
            "trafficTrend": {
                "mallVsLastWeekPct": 14.8,
                "restaurantFloorVsLastWeekPct": 11.5,
                "storefrontVsLastWeekPct": 10.2,
                "storeOrdersVsTrafficPct": -18.4,
            },
            "customerProfiles": [
                {"segment": "周边办公午餐客", "share": 0.44, "need": "45分钟内吃完、价格清楚"},
                {"segment": "逛街随机客", "share": 0.24, "need": "门口能马上看懂招牌和双人价"},
            ],
            "mallBenchmarks": {
                "sameFloorPeerCaptureRate": 0.24,
                "categoryMedianCaptureRate": 0.21,
            },
        },
        "review_summary": {"rating": 4.68, "reviewCount": 640, "lowRatingCount": 39, "negativeThemes": []},
    },
    "seating_mix": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 18000, "weekendAvgDailyRevenue": 21000, "gapPct": 16.7},
            "customerSegments": [{"segment": "2人桌", "share": 0.58}, {"segment": "4人桌", "share": 0.22}],
            "tableMix": [{"tableType": "2人桌", "share": 0.18}, {"tableType": "4人桌", "share": 0.62}],
        },
        "review_summary": {"rating": 4.7, "reviewCount": 580, "lowRatingCount": 24, "negativeThemes": []},
    },
    "staffing_schedule": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 16000, "weekendAvgDailyRevenue": 19000, "gapPct": 18.8},
            "daypartRevenue": [{"name": "午市", "share": 0.28}, {"name": "晚市", "share": 0.66}],
            "staffingByDaypart": [{"name": "午市", "share": 0.34}, {"name": "晚市", "share": 0.38}],
        },
        "review_summary": {
            "rating": 4.15,
            "reviewCount": 560,
            "lowRatingCount": 116,
            "negativeThemes": [{"theme": "上菜慢", "count": 78}, {"theme": "排队久", "count": 61}],
            "negativeDishMentions": [{"name": "招牌青花椒鱼", "count": 35}],
        },
    },
    "staff_training": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 17000, "weekendAvgDailyRevenue": 20500, "gapPct": 20.6},
        },
        "review_summary": {
            "rating": 4.18,
            "reviewCount": 520,
            "lowRatingCount": 95,
            "negativeThemes": [{"theme": "服务态度", "count": 92}, {"theme": "催菜没人管", "count": 44}],
            "negativeDishMentions": [{"name": "招牌青花椒鱼", "count": 28}],
        },
    },
    "kitchen_quality": {
        "review_summary": {
            "rating": 4.2,
            "reviewCount": 600,
            "lowRatingCount": 130,
            "negativeThemes": [{"theme": "味道不稳定", "count": 95}, {"theme": "太咸", "count": 42}],
            "negativeDishMentions": [{"name": "招牌青花椒鱼[活鱼现做]", "count": 160}],
        },
    },
    "cost_margin": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 18500, "weekendAvgDailyRevenue": 21400, "gapPct": 15.7},
        },
        "financial_summary": {"revenue": 1784831.9, "foodCostRatio": 0.49, "grossMarginPct": 51},
        "monthly_stocktake": {
            "topLossItems": ["活鱼边角损耗 18.6kg", "酸菜包材盘亏 7.2kg"],
            "varianceItems": ["活鱼实际耗用高于理论 8.4%", "底料实际耗用高于理论 6.1%"],
        },
        "menu_summary": {
            "topProducts": [
                {"name": "招牌青花椒鱼[活鱼现做]", "revenue": 237060, "soldQty": 1030, "foodCost": 132800},
                {"name": "手作冰豆花", "revenue": 27880, "soldQty": 697, "foodCost": 6970},
            ],
            "basketPairs": [{"left": "招牌青花椒鱼[活鱼现做]", "right": "手作冰豆花", "orders": 697}],
        },
        "review_summary": {"rating": 4.65, "reviewCount": 580, "lowRatingCount": 36, "negativeThemes": []},
    },
    "external_event_response": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 19000, "weekendAvgDailyRevenue": 22300, "gapPct": 17.4},
            "dailyAnomaly": {"date": "2026-07-01", "revenueVsBaselinePct": 42.0, "ordersVsBaselinePct": 38.5},
        },
        "external_signals": {
            "weather": {"text": "暴雨转多云，商场客流集中到晚市"},
            "activities": [
                {"title": "龙湖天街暑期亲子节", "date": "2026-07-01", "expectedTrafficLiftPct": 35},
                {"title": "商场会员满减券发放", "date": "2026-07-01"},
            ],
        },
        "review_summary": {"rating": 4.7, "reviewCount": 620, "lowRatingCount": 34, "negativeThemes": []},
    },
    "single_item_push": {
        "pos_summary": {
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 20500, "weekendAvgDailyRevenue": 23000, "gapPct": 12.2},
            "customerSegments": [{"segment": "2人桌", "share": 0.31}, {"segment": "4人桌", "share": 0.36}],
        },
        "review_summary": {
            "rating": 4.78,
            "reviewCount": 700,
            "lowRatingCount": 22,
            "positiveDishMentions": [{"name": "招牌青花椒鱼", "count": 188}],
            "negativeThemes": [],
        },
    },
    "store_compare": {
        "pos_summary": {
            "orders": 7775,
            "revenue": 1784831.9,
            "aov": 229.56,
            "weekdayWeekend": {"weekdayAvgDailyRevenue": 15801.98, "weekendAvgDailyRevenue": 28534.66, "gapPct": 80.6},
            "chainRank": {"revenueRank": 6, "dailyRank": 2, "storeCount": 8, "aovRank": 5, "reviewRank": 3},
            "peerStores": [
                {"name": "青花椒静安大融城店", "revenueRank": 1, "dailyRank": 1, "aov": 246, "bestPractice": "工作日午市双人鱼套餐承接好，出餐稳定"},
                {"name": "青花椒浦东前滩店", "revenueRank": 2, "dailyRank": 3, "aov": 238, "bestPractice": "商场活动日提前备鱼和冰豆花，晚市不缺货"},
                {"name": "青花椒闵行龙湖店", "revenueRank": 6, "dailyRank": 2, "aov": 230, "bestPractice": "自然客流强，但工作日和客单价还没吃满"},
            ],
            "storeComparison": {
                "strongerThanPeers": ["日均单量", "大众点评评分", "商场自然客流"],
                "weakerThanPeers": ["工作日午市收入", "双人套餐承接", "客单价"],
                "copyFrom": "青花椒静安大融城店",
                "copyAction": "把招牌鱼+冰豆花双人套餐放到点评/美团首屏，并要求服务员按两句话推荐",
            },
        },
        "review_summary": {
            "rating": 4.73,
            "reviewCount": 688,
            "lowRatingCount": 41,
            "negativeThemes": [{"theme": "工作日午市等位和推荐不清楚", "count": 22}],
        },
    },
}


def list_owner_action_demo_scenarios() -> list[str]:
    return sorted(_SCENARIO_PATCHES)


def get_owner_action_demo_scenario(name: str) -> dict[str, Any]:
    key = str(name or "package").strip()
    if key not in _SCENARIO_PATCHES:
        raise KeyError(key)
    params = deepcopy(_BASE_PARAMS)
    _deep_merge(params, deepcopy(_SCENARIO_PATCHES[key]))
    params["demoScenario"] = key
    params["demoMode"] = True
    return params


def _deep_merge(target: dict[str, Any], patch: dict[str, Any]) -> None:
    for key, value in patch.items():
        current = target.get(key)
        if isinstance(current, dict) and isinstance(value, dict):
            _deep_merge(current, value)
        else:
            target[key] = value
