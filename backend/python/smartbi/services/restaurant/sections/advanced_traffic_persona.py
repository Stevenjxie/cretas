from __future__ import annotations

"""Premium traffic and persona demo section for restaurant SmartBI.

This section intentionally uses simulated aggregate data. Real footfall/persona
signals require merchant authorization plus a Tencent/Baidu/Amap commercial
data contract, so the UI must present this as an extra enablement module.
"""

import time
from typing import Any

from smartbi.services.restaurant.external_signal_sources import (
    ExternalSignalRequest,
    RestaurantExternalSignalService,
)
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
        estimated_visits = int(params.get("estimated_store_visits") or 828)
        competitor_overlap_index = float(params.get("competitor_overlap_index") or 0.67)
        consumption_power_index = int(params.get("consumption_power_index") or 116)
        external_signals = RestaurantExternalSignalService().build_context(
            ExternalSignalRequest(
                city=city,
                business_district=business_district,
                mall_name=str(mall_name) if mall_name else None,
                target_date=str(params.get("target_date") or request.period or "2026-07-01"),
            )
        )

        plain_language_analysis = {
            "bottomLine": (
                "这家店现在不是没人路过，而是门口的人没有被充分转成进店；"
                "如果是大丸百货这类下滑店，先别急着打折，要先判断问题在商场、楼层动线、竞品，还是门店体验。"
            ),
            "whatItMeans": [
                "先别急着打折。打折只能拉一部分已经想吃的人，但解决不了“路过的人为什么没进来”。",
                "如果商场整体人流没掉，只有本店掉，那大概率不是大盘问题，而是位置动线、同层竞品、门口展示或服务体验出了问题。",
                "如果商场整体人流也掉了，就不要只压门店经理，要和商场活动、楼层导流、外卖/团购承接一起做。",
                "第一百货这类高人流店，重点不是再拉更多人，而是把午市和周末高峰的转化、排队和备货做顺。",
            ],
            "whyWeThinkSo": [
                "内部 POS 只能看到已经买单的人，解释不了“经过但没进店”的人。",
                "点评能看到顾客进店后的抱怨，解释不了商场人流、楼层动线和竞品变化。",
                "客流画像、POI 竞品和商场楼层数据放在一起，才能判断到底是外部机会变少，还是门店没有接住机会。",
            ],
        }

        solution_plan = [
            {
                "step": "第一周：先查大丸百货店为什么掉",
                "action": "把商场总人流、餐饮楼层人流、同层竞品变化、门口等位体验放在一张表里复盘。",
                "owner": "区域运营",
                "expectedOutcome": "判断下滑到底是商场没客、楼层动线变了、竞品抢走了，还是门店服务拖累。",
            },
            {
                "step": "第二周：第一百货店做午市快转化",
                "action": "推出工作日午市套餐，门口和团购页只讲三件事：快、稳、不踩雷。",
                "owner": "门店店长",
                "expectedOutcome": "把办公客从“路过看看”变成“今天就进来吃”。",
            },
            {
                "step": "第三周：周末高峰做分流和预约",
                "action": "排队超过 35 分钟就给同品牌近店分流券，同时把预约候补提前到 17:00 前开放。",
                "owner": "区域运营",
                "expectedOutcome": "减少门口流失，把本来要走掉的人留在品牌内部。",
            },
            {
                "step": "第四周：把客流变化接到备货",
                "action": "晚市和周末备货不要只看历史销量，要叠加商场活动、天气、楼层客流和预约情况。",
                "owner": "供应链",
                "expectedOutcome": "减少高峰缺货和尾段浪费同时发生。",
            },
        ]

        data_sufficiency = {
            "isEnoughForRealDecision": False,
            "plainVerdict": "足够做 demo 和开通说明，不足够直接下真实经营结论。",
            "why": [
                "现在用的是模拟客流画像，不能证明某家门店真实的人流变化。",
                "缺少商场总客流、餐饮楼层客流、门口路过客流和竞品开闭店时间线。",
                "缺少行业白皮书或平台报告来校准：当前价格带、连锁化趋势、品类竞争强度是不是行业共性。",
            ],
            "whatCanBeDecidedNow": [
                "可以决定这个高级模块值得保留，因为它解释的是 POS 和点评解释不了的问题。",
                "可以决定试点验证路径：先选第一百货和大丸百货，一个看机会承接，一个看异常下滑。",
                "可以决定需要客户授权哪些数据，而不是盲目购买全量外部数据。",
            ],
            "whatCannotBeDecidedYet": [
                "不能直接认定大丸百货店下滑就是商场客流下滑导致。",
                "不能直接给出真实投放预算和 ROI。",
                "不能替代门店现场走访、点评原文和商场物业数据。",
            ],
        }

        needed_evidence = [
            {
                "name": "腾讯或百度商圈/商场客流画像",
                "whyNeeded": "验证商场有没有人、客从哪里来、在餐饮楼层停多久，以及是否和竞品抢同一批人。",
                "sourceType": "付费/商务数据",
                "priority": "P0",
                "publicAvailability": "通常需要商务开通，公开数据不能替代。",
                "sourceUrl": "https://cloud.tencent.com/product/mall",
            },
            {
                "name": "高德 POI、周边搜索和竞品供给数据",
                "whyNeeded": "判断门店周边到底有多少同赛道竞品、是否近期供给变密。",
                "sourceType": "开放 API + 业务清洗",
                "priority": "P0",
                "publicAvailability": "开放 API 可用，但只能做供给侧 proxy，不能当真实客流。",
                "sourceUrl": "https://lbs.amap.com/api/webservice/guide/api-advanced/search",
            },
            {
                "name": "餐饮连锁化发展白皮书/行业报告",
                "whyNeeded": "校准当前餐饮大盘、价格带、连锁化趋势和品类竞争是不是行业共性。",
                "sourceType": "行业白皮书",
                "priority": "P1",
                "publicAvailability": "公开摘要可用，完整报告按发布方口径获取。",
                "sourceUrl": "https://www.ccfa.org.cn/portal/cn/xiangxi.jsp?id=446601&sharetype=1&type=33",
            },
            {
                "name": "商场物业数据",
                "whyNeeded": "确认楼层调整、出入口动线、活动排期和停车/地铁导流是否影响本店。",
                "sourceType": "客户/物业授权数据",
                "priority": "P0",
                "publicAvailability": "非公开，需要商户或物业合作。",
            },
            {
                "name": "门店现场走访和点评原文",
                "whyNeeded": "确认问题是不是等位、门口展示、服务态度、上菜慢等现场因素。",
                "sourceType": "客户自有数据 + 人工复盘",
                "priority": "P0",
                "publicAvailability": "客户授权后可获得。",
            },
            {
                "name": "和风天气、大麦活动、节假日与商场活动源",
                "whyNeeded": "解释某天突然暴涨或暴跌是不是外部天气、演出、展会、节假日或商场活动导致。",
                "sourceType": "开放 API + 半自动采集",
                "priority": "P0",
                "publicAvailability": "和风和大麦需要注册 Key；节假日可直接结构化；商场活动通常需要配置官网/公众号/小程序活动源。",
                "sourceUrl": "https://dev.qweather.com/docs/api/",
            },
        ]

        advice_knowledge_base = [
            {
                "situation": "门店销售下滑，但商圈或商场客流没有同步下滑",
                "plainDiagnosis": "这通常不是“没人逛”，而是本店没有接住路过的人。",
                "bossAction": "先暂停大额折扣，安排 1 周现场复盘门口展示、等位体验、同层竞品和楼层动线。",
                "decisionRule": "如果商场总客流稳定、本店订单下降超过 10%，优先查门店转化问题，而不是把责任归到大盘。",
                "neededData": ["商场总客流", "餐饮楼层客流", "门口等位时长", "同层竞品变化", "大众点评原文"],
                "sourceBasis": [
                    "腾讯商场客留大数据：可做客流趋势、来源地、竞品重叠和画像判断",
                    "百度慧眼：可做商圈、商场、楼层客流和客群画像评估",
                ],
            },
            {
                "situation": "商圈整体客流下降，本店也下降",
                "plainDiagnosis": "这时单店经理很难靠门店动作独自扛住，需要和商场、团购、外卖、会员一起做承接。",
                "bossAction": "把动作从“店内整改”升级为“商圈联合运营”：商场活动导流、团购曝光、老客召回和外卖补峰同步做。",
                "decisionRule": "如果商场或楼层客流连续两周下降，本店动作要从单店转化转为外部导流和线上承接。",
                "neededData": ["商场活动排期", "楼层客流趋势", "团购曝光与转化", "外卖订单时段", "会员来源地"],
                "sourceBasis": [
                    "百度慧眼商业地产方案：支持客群来源、流向、商圈人群和竞品监测",
                    "高德 POI 搜索：可补充周边供给和竞品密度，不替代真实客流",
                ],
            },
            {
                "situation": "午市人多但收入没有明显起来",
                "plainDiagnosis": "办公客不是不消费，而是怕慢、怕排队、怕踩雷。",
                "bossAction": "做 45 分钟能吃完的工作日午市套餐，门口、团购页和点评页只强调快、稳、招牌菜。",
                "decisionRule": "如果午市客流高、停留时间短、客单价不高，优先改套餐和出餐链路，不优先加座或做晚市大促。",
                "neededData": ["分小时客流", "出餐时长", "午市客单价", "办公楼来源地", "套餐点击和核销"],
                "sourceBasis": [
                    "餐饮连锁化白皮书/平台报告：用于校准价格带、线上化和连锁门店效率趋势",
                    "内部 POS 和点评原文：用于验证顾客是否在抱怨慢、排队和不稳定",
                ],
            },
            {
                "situation": "周末排队很长，但评价和复购变差",
                "plainDiagnosis": "这不是越排队越好，排队过长会把本来愿意吃的人变成差评和流失。",
                "bossAction": "设置 35 分钟等位红线，超过就做预约候补、同品牌近店分流和排队补偿。",
                "decisionRule": "如果周末客流高、等位长、点评里出现慢和服务抱怨，优先做分流和预约，不要继续硬接客。",
                "neededData": ["等位时长", "周末客流", "点评差评主题", "近店座位余量", "预约候补转化"],
                "sourceBasis": [
                    "腾讯客流画像：可判断周末游客、家庭客和竞品重叠",
                    "内部连锁门店数据：可判断能不能把人留在品牌内部分流",
                ],
            },
            {
                "situation": "周边竞品突然变多，价格战压力变大",
                "plainDiagnosis": "同赛道变挤时，直接降价往往会先伤毛利，不一定能解决进店理由。",
                "bossAction": "先查竞品是抢同一批人还是不同场景，再决定做招牌菜稳定性、服务确定性，还是做价格带调整。",
                "decisionRule": "如果 POI 竞品变密但真实客流没有增加，优先做差异化定位和产品组合，不先做全店打折。",
                "neededData": ["高德/腾讯/百度 POI", "竞品开闭店时间线", "点评菜品关键词", "本店毛利结构", "同商圈客单价"],
                "sourceBasis": [
                    "高德 POI 搜索：用于判断供给侧竞品密度和变化",
                    "行业白皮书/平台报告：用于校准品类竞争、价格带和连锁化趋势",
                ],
            },
            {
                "situation": "某一天销售或客流突然暴涨",
                "plainDiagnosis": "这不一定是门店运营突然变好，可能是节假日、演唱会散场、商场活动、极端天气前后造成的外部流量。",
                "bossAction": "先给当天打外部原因标签，再复盘会员沉淀、点评引导、备货和排班，不要直接把当天结果外推到下周。",
                "decisionRule": "如果当天命中节假日、周边活动、商场活动或天气异常，增长先按外部机会处理，再评估门店是否接住机会。",
                "neededData": ["节假日/调休", "和风天气", "大麦周边活动", "商场活动页", "当天排队和备货记录"],
                "sourceBasis": [
                    "和风天气：小时级天气、预警和极端天气解释",
                    "大麦开放平台：周边演出、体育、亲子、展览活动解释",
                    "国务院节假日通知：节假日和调休解释",
                    "商场活动采集：IP 展、市集、快闪、会员日解释",
                ],
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
                {
                    "provider": "和风天气",
                    "accessMode": "注册 Key 后接入",
                    "bestUse": "按小时天气、空气质量、灾害预警解释堂食、外卖、排队和临时客流变化",
                    "availableFields": [
                        "weather_now",
                        "hourly_forecast",
                        "weather_warning",
                        "air_quality",
                        "precipitation",
                    ],
                },
                {
                    "provider": "大麦开放平台",
                    "accessMode": "注册 AppKey/AppSecret 后接入",
                    "bestUse": "识别门店周边演唱会、体育、展览、亲子活动带来的临时客流",
                    "availableFields": [
                        "event_category",
                        "event_time",
                        "venue_name",
                        "city_name",
                        "ticket_status",
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
            "plainLanguageAnalysis": plain_language_analysis,
            "solutionPlan": solution_plan,
            "dataSufficiency": data_sufficiency,
            "neededEvidence": needed_evidence,
            "adviceKnowledgeBase": advice_knowledge_base,
            "externalSignals": external_signals,
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
