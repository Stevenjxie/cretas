from __future__ import annotations

"""Premium traffic and persona demo section for restaurant SmartBI.

This section intentionally uses simulated aggregate data. Real footfall/persona
signals require merchant authorization plus a Tencent/Baidu/Amap commercial
data contract, so the UI must present this as an extra enablement module.
"""

import time
from typing import Any

from smartbi.services.restaurant.sections.base import (
    AbstractSectionHandler,
    SectionRequest,
    SectionResponse,
)


class AdvancedTrafficPersonaHandler(AbstractSectionHandler):
    section_name = "advanced_traffic_persona"

    def compute(
        self,
        request: SectionRequest,
        context: dict[str, Any],
    ) -> SectionResponse:
        started = time.time()
        params = request.params or {}
        city = str(params.get("city") or context.get("city") or "上海")
        business_district = str(
            params.get("business_district")
            or context.get("business_district")
            or "人民广场"
        )
        mall_name = params.get("mall_name") or context.get("mall_name")
        store_name = request.store_name or str(
            params.get("store_name") or "示例餐饮门店"
        )
        sub_sector = request.sub_sector or "中餐"

        daily_footfall = int(params.get("daily_footfall") or 21800)
        capture_rate = float(params.get("capture_rate") or 0.038)
        estimated_visits = int(daily_footfall * capture_rate)
        competitor_overlap_index = float(params.get("competitor_overlap_index") or 0.67)
        consumption_power_index = int(params.get("consumption_power_index") or 116)
        capture_lift_pp = 0.004
        incremental_daily_visits = int(daily_footfall * capture_lift_pp)
        weekly_incremental_visits = incremental_daily_visits * 7
        weekend_queue_retention = int(estimated_visits * 0.36 * 0.1)

        decision_scores = [
            {
                "key": "capture_gap",
                "label": "捕获率缺口",
                "name": "捕获率缺口",
                "score": 82,
                "maxScore": 100,
                "level": "high",
                "evidence": f"日均路过客流 {daily_footfall:,}，当前估算捕获率 {capture_rate * 100:.1f}%。",
                "recommendation": "先优化午市入口套餐、等位透明和门口导流，而不是直接加座。",
            },
            {
                "key": "competitor_pressure",
                "label": "竞品压力",
                "name": "竞品压力",
                "score": 78,
                "maxScore": 100,
                "level": "high",
                "evidence": f"竞品客群重叠指数 {competitor_overlap_index:.2f}，说明同商圈在抢同一批人。",
                "recommendation": "投放主张应避开价格战，强调服务确定性、出餐稳定和招牌菜。",
            },
            {
                "key": "dwell_fit",
                "label": "停留适配",
                "name": "停留适配",
                "score": 64,
                "maxScore": 100,
                "level": "medium",
                "evidence": "餐饮层平均停留 74 分钟，适合正餐，但午市办公客停留窗口更短。",
                "recommendation": "午市做快决策套餐，晚市保留家庭/游客套餐。",
            },
            {
                "key": "supply_readiness",
                "label": "供应链联动",
                "name": "供应链联动",
                "score": 59,
                "maxScore": 100,
                "level": "medium",
                "evidence": "20:00 后外部客流仍高但堂食转化下降，历史销量补货会低估潜在需求。",
                "recommendation": "把客流峰谷作为备货阈值的外部修正项。",
            },
        ]

        scenario_simulations = [
            {
                "name": "捕获率 +0.4pp",
                "assumption": "午市入口套餐、等位透明和点评信任线索同步优化。",
                "metricDelta": f"约 +{incremental_daily_visits} 到店机会/日，+{weekly_incremental_visits} 到店机会/周。",
                "operatingImplication": "先压缩点单和出餐链路，再看是否需要增加午市人手。",
                "nextAction": "选择第一百货店做 2 周 A/B 验证。",
            },
            {
                "name": "周末排队流失 -10%",
                "assumption": "17:00 前开放候补预约，超过 35 分钟等待导流到同品牌近店。",
                "metricDelta": f"约保留 {weekend_queue_retention} 个高峰到店机会/日。",
                "operatingImplication": "减少门口流失，同时把拥堵门店需求留在连锁内部。",
                "nextAction": "用排队时长、点评等待关键词和跨店券核验。",
            },
            {
                "name": "竞品重叠下降 0.07",
                "assumption": "投放从通用折扣改成招牌菜稳定性、服务确定性和不踩雷心智。",
                "metricDelta": "降低同质化价格竞争，优先改善转化质量而非单纯拉客流。",
                "operatingImplication": "预算从全城曝光转向来源地 Top3 和地图/点评决策入口。",
                "nextAction": "按黄浦、静安、浦东三个来源地分组看券核销。",
            },
        ]

        validation_plan = [
            {
                "question": "商场总客流是否同步下降？",
                "requiredFields": ["mall_daily_footfall", "floor_flow_index", "storefront_passby_flow"],
                "decisionRule": "如果商场不降而本店下降，优先查楼层动线、竞品新开和等位体验。",
                "owner": "区域运营",
            },
            {
                "question": "午市客流是办公客还是游客？",
                "requiredFields": ["hourly_flow_index", "source_area_top3", "weekday_weekend_lift"],
                "decisionRule": "办公客占比高则推快出餐套餐；游客占比高则强化点评信任和招牌菜。",
                "owner": "门店运营",
            },
            {
                "question": "客流峰谷是否已经影响备货？",
                "requiredFields": ["hourly_flow_index", "sku_stockout_rate", "late_day_waste_rate"],
                "decisionRule": "如果晚市潜在客流高但缺货/尾段浪费并存，调整备货阈值而非只改菜单。",
                "owner": "供应链",
            },
        ]

        recommendations = [
            {
                "priority": "P0",
                "action": "把午市做成 45 分钟可完成的工作日套餐，并把高毛利小食/饮品设为加价购。",
                "why": "11:30-13:30 的办公客流占全天 31%，但停留时间偏短；缩短决策和出餐链路比单纯降价更直接。",
                "metricTrigger": "办公客群 46%，午市峰值指数 1.42，平均停留 48 分钟。",
                "expectedImpact": "提升午市翻台与加购率，优先验证 2 周。",
            },
            {
                "priority": "P0",
                "action": "周末 17:00 前开放预约候补和跨店分流，把排队超过 35 分钟的顾客导向同品牌近店。",
                "why": "周末客流是工作日 1.36 倍，游客/家庭客群更怕等待；排队体验会直接影响评分和转化。",
                "metricTrigger": "周末提升 36%，竞品重叠指数 0.67，晚市峰值指数 1.58。",
                "expectedImpact": "减少门口流失，并把拥堵转成连锁内部留存。",
            },
            {
                "priority": "P1",
                "action": "在人民广场 1km 商圈内做差异化投放，主打服务确定性、等位透明和招牌菜稳定性。",
                "why": "火锅竞品密度高，公开 POI 只能说明供给侧拥挤；客流画像补足了客群来源和重叠度。",
                "metricTrigger": "近场餐饮供给高、竞品客群重叠高、消费力指数 116。",
                "expectedImpact": "减少同质化价格战，把预算投向更容易转化的人群。",
            },
            {
                "priority": "P1",
                "action": "把游客客群的套餐文案和点评内容分开运营，强化“第一次来上海也不踩雷”的确定性。",
                "why": "游客/短停客群占比高，决策依赖地图、点评和商场导流，信任线索比会员权益更有效。",
                "metricTrigger": "游客/商务短停 18%，外区来源 Top3 合计 37%。",
                "expectedImpact": "提升异地客进店率和高峰前置预订。",
            },
            {
                "priority": "P2",
                "action": "将门店、商场和供应链指标联动，按客流峰谷调整备货阈值，而不是只按历史销量补货。",
                "why": "内部 POS 只能看到已成交，客流画像能解释没有成交的外部机会和缺口。",
                "metricTrigger": "20:00 后客流仍高但堂食转化下降，原料备货应区分到店潜力和实际成交。",
                "expectedImpact": "降低缺货和晚市尾段浪费并存的问题。",
            },
        ]

        data = {
            "moduleName": "高级客流画像分析",
            "requiresEnablement": True,
            "demoMode": True,
            "dataNote": (
                "当前为 demo 模拟数据，仅用于展示分析形态。真实启用需要客户门店授权、"
                "供应商商务开通和聚合数据合规审查；不采集或展示个人级轨迹。"
            ),
            "storeContext": {
                "storeName": store_name,
                "city": city,
                "businessDistrict": business_district,
                "mallName": mall_name or "商圈街区门店",
                "subSector": sub_sector,
                "period": request.period,
            },
            "enablement": {
                "status": "需额外开通",
                "scope": "按客户/项目/商圈授权，不属于 MVP 免费公开数据能力。",
                "requirements": [
                    "客户确认门店、商圈和竞品分析范围",
                    "腾讯/百度/高德等供应商完成商务授权或数据服务合同",
                    "仅接入聚合统计字段，避免个人画像和明细轨迹",
                    "与内部 POS、会员、供应链数据做字段映射",
                ],
                "priceHint": {
                    "basis": "通常按项目、区域、门店包或年度服务计费",
                    "demoAssumption": "MVP 阶段先用模拟字段展示价值，不把付费客流数据作为前置依赖",
                },
            },
            "providers": [
                {
                    "provider": "腾讯位置大数据",
                    "accessMode": "商务授权/项目制",
                    "bestUse": "商圈客流、来源地、竞品重叠、停留时长、消费力分层",
                    "availableFields": [
                        "daily_footfall",
                        "hourly_flow_index",
                        "source_area_top3",
                        "dwell_time_distribution",
                        "competitor_overlap_index",
                        "consumption_power_index",
                    ],
                },
                {
                    "provider": "百度慧眼/百度地图",
                    "accessMode": "商务授权/行业解决方案",
                    "bestUse": "区域热力、客群来源、常驻/游客拆分、商圈趋势",
                    "availableFields": [
                        "trade_area_heat",
                        "resident_visitor_split",
                        "age_band_distribution",
                        "gender_distribution",
                        "origin_city_rank",
                        "weekday_weekend_lift",
                    ],
                },
                {
                    "provider": "高德/商业位置数据",
                    "accessMode": "公开地图 API + 商务数据能力需另行确认",
                    "bestUse": "POI 供给密度、可达性、路况天气 proxy，以及商务授权后的区域客流能力",
                    "availableFields": [
                        "poi_density",
                        "transit_accessibility",
                        "parking_pressure_index",
                        "weather_flow_factor",
                        "nearby_competitor_count",
                        "route_arrival_time",
                    ],
                },
            ],
            "fieldCatalog": [
                {
                    "key": "daily_footfall",
                    "label": "日均路过客流",
                    "providerCoverage": ["腾讯", "百度"],
                    "demoValue": daily_footfall,
                    "businessQuestion": "商圈有没有足够的外部机会，而不是只看已成交订单。",
                },
                {
                    "key": "hourly_flow_index",
                    "label": "分小时客流指数",
                    "providerCoverage": ["腾讯", "百度"],
                    "demoValue": {"lunch": 1.42, "dinner": 1.58, "lateNight": 0.74},
                    "businessQuestion": "排班、备货、预约和套餐窗口应该放在哪些时段。",
                },
                {
                    "key": "source_area_top3",
                    "label": "来源地 Top3",
                    "providerCoverage": ["腾讯", "百度"],
                    "demoValue": [
                        {"area": "黄浦区", "share": 0.31},
                        {"area": "静安区", "share": 0.22},
                        {"area": "浦东新区", "share": 0.14},
                    ],
                    "businessQuestion": "投放和会员召回应该覆盖哪些外部区域。",
                },
                {
                    "key": "dwell_time_distribution",
                    "label": "停留时长分布",
                    "providerCoverage": ["腾讯", "百度"],
                    "demoValue": {"0-30m": 0.18, "30-60m": 0.37, "60-120m": 0.34, "120m+": 0.11},
                    "businessQuestion": "门店是快决策客流还是深度用餐客流，影响产品组合和翻台。",
                },
                {
                    "key": "consumption_power_index",
                    "label": "消费力指数",
                    "providerCoverage": ["腾讯", "百度"],
                    "demoValue": consumption_power_index,
                    "businessQuestion": "当前客单价偏高/偏低是否符合商圈人群支付能力。",
                },
                {
                    "key": "competitor_overlap_index",
                    "label": "竞品客群重叠指数",
                    "providerCoverage": ["腾讯"],
                    "demoValue": competitor_overlap_index,
                    "businessQuestion": "同赛道竞品是在抢同一批人，还是服务不同场景。",
                },
                {
                    "key": "nearby_competitor_count",
                    "label": "近场竞品供给数",
                    "providerCoverage": ["高德", "腾讯", "百度"],
                    "demoValue": 61,
                    "businessQuestion": "公开 POI 密度与付费客流画像一起判断供需压力。",
                },
            ],
            "simulatedMetrics": {
                "dailyFootfall": daily_footfall,
                "estimatedStoreVisits": estimated_visits,
                "captureRate": round(capture_rate, 3),
                "weekdayWeekendLift": 1.36,
                "lunchPeakIndex": 1.42,
                "dinnerPeakIndex": 1.58,
                "officeWorkerShare": 0.46,
                "residentShare": 0.28,
                "touristBusinessShare": 0.18,
                "studentShare": 0.08,
                "consumptionPowerIndex": consumption_power_index,
                "competitorOverlapIndex": competitor_overlap_index,
                "parkingPressureIndex": 0.72,
                "transitAccessibilityIndex": 0.91,
                "avgDwellMinutes": 74,
            },
            "decisionScores": decision_scores,
            "scenarioSimulations": scenario_simulations,
            "validationPlan": validation_plan,
            "analysis": {
                "headline": (
                    f"{business_district}{'/' + str(mall_name) if mall_name else ''}"
                    f"客流强，但{sub_sector}竞品重叠高；机会在分时段转化和连锁内部分流。"
                ),
                "comparisonNarrative": [
                    "内部 POS 解释已成交结果，客流画像解释未成交机会和商圈天花板。",
                    "公开 POI/天气数据只能做供给和环境 proxy，不能替代真实客流、来源地和停留时长。",
                    "连锁店要把本店放进同城门店网络看，拥堵门店应向近店分流而不是只扩大等位。",
                ],
                "risks": [
                    "同商圈火锅供给密集，评分和服务确定性比价格更容易决定转化。",
                    "周末和晚市峰值容易让排队体验拖累平台评分。",
                    "游客客群占比高时，会员沉淀弱，需要把一次性客流转成评价和复购线索。",
                ],
                "opportunities": [
                    "午市办公客可用快餐化套餐承接，和晚市家庭/游客套餐分开设计。",
                    "用来源地 Top3 做定向投放，比全城投放更容易测算 ROI。",
                    "把客流峰谷接入备货阈值，可同时降低缺货和尾段浪费。",
                ],
                "recommendations": recommendations,
            },
        }
        return self.ok(request, data=data, started=started)
