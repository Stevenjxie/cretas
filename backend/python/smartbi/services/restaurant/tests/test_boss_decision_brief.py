from __future__ import annotations

from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.boss_decision_brief import (
    BossDecisionBriefHandler,
)
from smartbi.api.restaurant_sections import OwnerActionChatRequest, owner_action_chat


def test_boss_decision_brief_turns_sources_into_owner_actions() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_DEMO",
            upload_id=None,
            sub_sector="火锅",
            store_id="haidilao-diyi-shopping-mall",
            store_name="海底捞火锅(第一百货店)",
            period="2026-07-01",
            params={
                "city": "上海",
                "business_district": "南京东路/人民广场",
                "mall_name": "第一百货商业中心",
                "review_summary": {
                    "rating": 4.6,
                    "riskAlerts": ["排队时间偏长，周末晚市影响进店转化"],
                    "negativeThemes": ["排队久", "上菜慢", "价格偏贵"],
                    "positiveDishMentions": [{"name": "番茄锅底", "count": 38}],
                    "negativeDishMentions": [{"name": "牛肉卷", "count": 7}],
                },
                "monthly_stocktake": {
                    "topLossItems": ["牛肉卷", "虾滑"],
                    "varianceItems": ["锅底包材耗用高于理论值"],
                },
                "menu_summary": {
                    "stars": ["捞派滑牛肉", "番茄锅底"],
                    "dogs": ["低动销甜品"],
                    "recommendations": ["午市主推高毛利快出套餐"],
                },
                "external_signals": {
                    "weather": {"text": "阵雨，堂食到店有扰动"},
                    "activities": [{"title": "南京路商圈夏季消费活动"}],
                },
                "sections": {
                    "channelMargin": {"adviceZh": ["堂食毛利高于外卖"]},
                    "financialMetrics": {"revenue": 731048, "foodCostRatio": 0.42},
                    "advancedTrafficPersona": {
                        "externalSignals": {
                            "weather": {"text": "阵雨"},
                            "activities": [{"title": "商圈活动"}],
                        }
                    },
                },
            },
        ),
        context={},
    )

    assert response.status == SectionStatus.OK
    assert response.section_name == "boss_decision_brief"
    data = response.data

    assert data["moduleName"] == "老板最终决策简报"
    assert "先不要" in data["finalAnswer"]
    assert "不是看分数" in data["finalAnswer"]
    assert data["ownerDecisionPage"]["title"] == "老板今天先看这个"
    assert data["ownerDecisionNow"]["today"].startswith("今天只做异常归因")
    assert data["ownerDecisionNow"]["thisMonth"].startswith("月底用月盘点")

    source_names = {item["source"] for item in data["sourceDecisionMap"]}
    assert {
        "POS/订单",
        "月盘点/库存/BOM",
        "大众点评/顾客评价",
        "菜品/POS SKU",
        "外部活动/天气/商圈",
    }.issubset(source_names)

    cards = data["decisionCards"]
    assert any("直接打折" in card["decision"] for card in cards)
    assert any("月盘点" in card["decision"] or "BOM" in card["recommendation"] for card in cards)
    assert any("大众点评" in source for card in cards for source in card["sourceInputs"])
    assert any("主推" in card["recommendation"] and "下架" in card["recommendation"] for card in cards)
    assert any("番茄锅底" in card["recommendation"] for card in cards)
    assert any(item["platform"] == "大众点评/美团评论" for item in data["crossPlatformComparison"])

    answers = {item["source"]: item["bossQuestion"] for item in data["whatEachSourceAnswers"]}
    assert "钱是不是漏在食材" in answers["月盘点/库存/BOM"]
    assert "顾客为什么不满意" in answers["大众点评/顾客评价"]
    assert "哪些菜该主推" in answers["菜品/POS SKU"]


def test_boss_decision_brief_is_honest_when_data_is_missing() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_DEMO",
            upload_id=None,
            sub_sector="中餐",
            store_name="示例餐饮店",
        ),
        context={},
    )

    data = response.data
    assert response.status == SectionStatus.OK
    assert data["dataReadiness"]["enoughForBossDirection"] is False
    assert data["dataReadiness"]["enoughForHardRoiPromise"] is False
    assert any("缺 月盘点" in gap for gap in data["dataGapForRealOperation"])
    assert any(item["source"] == "POS/订单" for item in data["nextDataToAskCustomer"])
    assert data["ownerDecisionPage"]["expectedImpact"]["plainText"].startswith("现在缺少客单数据")


def test_boss_decision_brief_names_actual_dishes_from_menu_summary() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_QHJ_DEMO",
            upload_id=None,
            sub_sector="川菜/砂锅鱼",
            store_name="青花椒川食山语（颛桥龙湖店）",
            params={
                "pos_summary": {"periodRevenue": 73762, "orders": 328},
                "review_summary": {
                    "rating": 4.73,
                    "reviewCount": 688,
                    "lowRatingCount": 41,
                    "positiveDishMentions": [
                        {"name": "特色青花椒鱼", "count": 120},
                        {"name": "双人餐", "count": 35},
                    ],
                    "negativeDishMentions": [
                        {"name": "乌蒙山干锅牛肉", "count": 12},
                    ],
                    "negativeThemes": [
                        {"theme": "味道差", "count": 32},
                        {"theme": "油", "count": 49},
                    ],
                },
                "menu_summary": {
                    "topCategories": [
                        {"category": "招牌必点", "revenue": 156386.23},
                        {"category": "热卖推荐", "revenue": 82357.59},
                    ],
                    "topProducts": [
                        {"name": "特色青花椒鱼(活鱼现做)", "revenue": 57777.55},
                        {"name": "特色青花椒鱼(活鱼手工去刺)", "revenue": 42458.77},
                        {"name": "双人餐", "revenue": 21529.03},
                    ],
                    "lowSalesProducts": [
                        {"name": "打包盒", "qty": 1, "revenue": 10.34},
                    ],
                },
            },
        ),
        context={},
    )

    menu_card = next(
        card for card in response.data["decisionCards"]
        if card["decision"].startswith("菜单动作")
    )
    assert "特色青花椒鱼(活鱼现做)" in menu_card["recommendation"]
    assert "双人餐" in menu_card["recommendation"]
    assert "招牌必点" in menu_card["recommendation"]
    assert "点评里也认可" in menu_card["recommendation"]
    assert "点评点名不稳定" in menu_card["recommendation"]
    assert "全店打折" in menu_card["recommendation"]

    review_card = next(
        card for card in response.data["decisionCards"]
        if card["decision"].startswith("本周先解决顾客体验")
    )
    assert "低分 41 条" in review_card["recommendation"]
    assert "特色青花椒鱼" in review_card["recommendation"]
    assert "乌蒙山干锅牛肉" in review_card["recommendation"]
    assert "厨师长抽查出品稳定性" in review_card["recommendation"]

    comparisons = response.data["crossPlatformComparison"]
    review_comparison = next(item for item in comparisons if item["platform"] == "大众点评/美团评论")
    assert "好评菜 特色青花椒鱼" in review_comparison["whatItSays"]
    assert "风险菜/主题 乌蒙山干锅牛肉" in review_comparison["whatItSays"]
    assert {item["platform"] for item in comparisons}.issuperset({
        "POS/销量",
        "大众点评/美团评论",
        "商圈/活动/天气",
        "月盘点/BOM/采购",
    })


def test_boss_decision_page_turns_rich_qhj_signals_into_plain_actions() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_QHJ_REAL",
            upload_id=None,
            sub_sector="鱼类餐饮/砂锅鱼",
            store_name="青花椒川食山语（颛桥龙湖店）",
            params={
                "pos_summary": {
                    "orders": 7775,
                    "revenue": 1784831.9,
                    "customers": 19713,
                    "aov": 229.56,
                    "weeklyTrend": [
                        {"week": "2025-W51", "revenue": 127143.68, "orders": 538, "aov": 236.33},
                        {
                            "week": "2025-W52",
                            "revenue": 140457.98,
                            "orders": 616,
                            "aov": 228.02,
                            "wowRevenuePct": 10.5,
                            "wowOrdersPct": 14.5,
                        },
                    ],
                    "weekdayWeekend": {
                        "weekdayAvgDailyRevenue": 15801.98,
                        "weekendAvgDailyRevenue": 28534.66,
                        "gapPct": 80.6,
                    },
                    "daypartRevenue": [
                        {"name": "晚市", "share": 0.60},
                        {"name": "午市", "share": 0.39},
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
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": "特色青花椒鱼[活鱼现做]", "revenue": 237060, "soldQty": 1030, "foodCost": 92800},
                        {"name": "特色青花椒鱼[活鱼手工去刺]", "revenue": 154368, "soldQty": 612, "foodCost": 64835},
                        {"name": "乌蒙山干锅牛肉2-3人餐", "revenue": 132861, "soldQty": 481, "foodCost": 70416},
                    ],
                    "products": [
                        {"name": "手作冰豆花", "revenue": 27880, "soldQty": 697, "foodCost": 6970},
                    ],
                    "basketPairs": [
                        {"left": "特色青花椒鱼[活鱼现做]", "right": "手作冰豆花", "orders": 697},
                    ],
                },
                "review_summary": {
                    "rating": 4.73,
                    "reviewCount": 688,
                    "lowRatingCount": 74,
                    "positiveDishMentions": [
                        {"name": "特色青花椒鱼", "count": 171},
                        {"name": "小炒现切吊龙", "count": 47},
                    ],
                    "negativeDishMentions": [
                        {"name": "特色青花椒鱼", "count": 80},
                        {"name": "牛骨髓麻婆豆腐", "count": 35},
                    ],
                    "negativeThemes": [
                        {"theme": "服务", "count": 25},
                        {"theme": "环境", "count": 23},
                        {"theme": "味道差/不好吃", "count": 20},
                    ],
                },
                "external_signals": {"weather": {"text": "未接当天实时天气"}},
            },
        ),
        context={},
    )

    page = response.data["ownerDecisionPage"]

    assert "先别急着全店打折" in page["headline"]
    assert "差在周一到周四" in page["headline"]
    assert "80.6%" in page["headline"]
    assert "最近一周营收比上周多 10.5%" in page["plainDiagnosis"]
    assert "主要靠晚市" in page["plainDiagnosis"]
    assert "2人桌" in page["plainDiagnosis"]
    assert "美团/大众点评" in page["plainDiagnosis"]
    assert page["decisionFocus"]["primaryActionType"] == "package"
    assert page["decisionFocus"]["shouldRecommendPackage"] is True
    assert page["packageDecision"]["status"] == "active"
    assert page["packageDecision"]["candidates"][0]["name"] == "特色青花椒鱼[活鱼现做] + 手作冰豆花"
    assert page["packageRecommendations"]["status"] == "ready"
    top_package = page["packageRecommendations"]["candidates"][0]
    assert top_package["name"] == "特色青花椒鱼[活鱼现做] + 手作冰豆花"
    assert top_package["estimatedPackagePrice"] > top_package["estimatedFoodCost"]
    assert top_package["grossMarginPct"] > 50
    assert top_package["scoreBreakdown"]["margin"] is not None
    assert any("周一到周四" in action for action in page["doFirst"])
    assert any("手作冰豆花" in action for action in page["doFirst"])
    assert any("估算毛利率" in action for action in page["doFirst"])
    assert any("不要先猛推" in action for action in page["doNotDo"])
    assert any("每天只盯 4 个数" in action for action in page["decisionPlan"]["thisWeek"])
    assert "一周大约多卖 11478 元" in page["expectedImpact"]["plainText"]
    assert any("工作日每天约 15801.98" in evidence for evidence in page["keyEvidence"])
    assert any(item["dimension"] == "渠道结构" and "已经能看渠道贡献" in item["currentFinding"] for item in page["analysisDimensions"])
    assert any(item["dimension"] == "利润闭环" and "还缺 BOM" in item["currentFinding"] for item in page["analysisDimensions"])
    assert any("不能承诺利润能省多少" in gap for gap in page["missingDataInPlainWords"])


def test_owner_decision_does_not_make_package_primary_when_review_risk_dominates() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_REVIEW_RISK",
            upload_id=None,
            sub_sector="鱼类餐饮",
            store_name="点评风险测试店",
            params={
                "pos_summary": {
                    "aov": 160,
                    "weekdayWeekend": {"weekdayAvgDailyRevenue": 10000, "weekendAvgDailyRevenue": 18000, "gapPct": 80},
                    "customerSegments": [{"segment": "2人桌", "share": 0.50}],
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": "招牌鱼锅", "revenue": 100000, "soldQty": 800, "foodCost": 35000},
                        {"name": "手作豆花", "revenue": 24000, "soldQty": 600, "foodCost": 4800},
                    ],
                    "basketPairs": [
                        {"left": "招牌鱼锅", "right": "手作豆花", "orders": 480},
                    ],
                },
                "review_summary": {
                    "rating": 4.1,
                    "reviewCount": 500,
                    "lowRatingCount": 120,
                    "positiveDishMentions": [{"name": "招牌鱼锅", "count": 60}],
                    "negativeDishMentions": [{"name": "招牌鱼锅", "count": 95}],
                    "negativeThemes": [
                        {"theme": "上菜慢", "count": 72},
                        {"theme": "服务差", "count": 58},
                        {"theme": "味道不稳定", "count": 43},
                    ],
                },
            },
        ),
        context={},
    )

    page = response.data["ownerDecisionPage"]

    assert page["decisionFocus"]["primaryActionType"] == "staffing_schedule"
    assert page["decisionFocus"]["shouldRecommendPackage"] is False
    assert page["packageDecision"]["status"] == "available_but_not_primary"
    assert page["packageDecision"]["candidates"]
    assert "套餐" not in page["doFirst"][0]
    assert "排班" in page["decisionFocus"]["why"]


def test_owner_decision_recommends_table_mix_when_two_person_demand_exceeds_two_seat_supply() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_TABLE_MIX",
            upload_id=None,
            sub_sector="商场餐饮",
            store_name="桌型测试店",
            params={
                "pos_summary": {
                    "aov": 120,
                    "customerSegments": [
                        {"segment": "2人桌", "share": 0.58},
                        {"segment": "4人桌", "share": 0.22},
                    ],
                    "tableMix": [
                        {"tableType": "2人桌", "share": 0.18},
                        {"tableType": "4人桌", "share": 0.62},
                    ],
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": "招牌牛肉饭", "revenue": 80000, "soldQty": 1000, "foodCost": 28000},
                    ],
                },
                "review_summary": {"rating": 4.7, "negativeThemes": []},
            },
        ),
        context={},
    )

    page = response.data["ownerDecisionPage"]

    assert page["decisionFocus"]["primaryActionType"] == "seating_mix"
    assert page["decisionFocus"]["shouldRecommendPackage"] is False
    assert page["packageDecision"]["status"] == "not_enough_data"
    assert "2 人客" in page["decisionFocus"]["why"]
    assert "桌型" in page["doFirst"][0]


def test_owner_decision_recommends_kitchen_quality_when_dish_reviews_dominate() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_KITCHEN_QUALITY",
            upload_id=None,
            sub_sector="川菜",
            store_name="厨房测试店",
            params={
                "pos_summary": {
                    "aov": 180,
                    "weekdayWeekend": {"weekdayAvgDailyRevenue": 15000, "weekendAvgDailyRevenue": 22000, "gapPct": 46.7},
                    "customerSegments": [{"segment": "2人桌", "share": 0.45}],
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": "水煮鱼", "revenue": 120000, "soldQty": 900, "foodCost": 48000},
                        {"name": "冰粉", "revenue": 20000, "soldQty": 700, "foodCost": 5000},
                    ],
                    "basketPairs": [{"left": "水煮鱼", "right": "冰粉", "orders": 500}],
                },
                "review_summary": {
                    "rating": 4.2,
                    "reviewCount": 600,
                    "lowRatingCount": 130,
                    "negativeDishMentions": [{"name": "水煮鱼", "count": 160}],
                    "negativeThemes": [
                        {"theme": "味道不稳定", "count": 95},
                        {"theme": "太咸", "count": 42},
                    ],
                },
            },
        ),
        context={},
    )

    page = response.data["ownerDecisionPage"]

    assert page["decisionFocus"]["primaryActionType"] == "kitchen_quality"
    assert page["decisionFocus"]["shouldRecommendPackage"] is False
    assert page["packageDecision"]["status"] == "available_but_not_primary"
    assert "厨房" in page["decisionFocus"]["why"]
    assert "水煮鱼" in page["doFirst"][0]


def test_package_recommendation_can_infer_better_combo_than_existing_pair() -> None:
    handler = BossDecisionBriefHandler()

    response = handler.compute(
        SectionRequest(
            factory_id="F_PACKAGE_TEST",
            upload_id=None,
            sub_sector="鱼类餐饮",
            store_name="套餐测试店",
            params={
                "pos_summary": {
                    "aov": 140,
                    "weekdayWeekend": {"weekdayAvgDailyRevenue": 10000, "weekendAvgDailyRevenue": 17000, "gapPct": 70},
                    "customerSegments": [{"segment": "2人桌", "share": 0.52}],
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": "招牌鱼锅", "revenue": 100000, "soldQty": 1000, "foodCost": 40000},
                        {"name": "高价牛肉", "revenue": 90000, "soldQty": 1000, "foodCost": 80000},
                        {"name": "手作豆花", "revenue": 30000, "soldQty": 1000, "foodCost": 3000},
                    ],
                    "basketPairs": [
                        {"left": "招牌鱼锅", "right": "高价牛肉", "orders": 900},
                    ],
                },
                "review_summary": {
                    "positiveDishMentions": [{"name": "招牌鱼锅", "count": 80}],
                    "negativeDishMentions": [],
                },
            },
        ),
        context={},
    )

    page = response.data["ownerDecisionPage"]
    top_package = page["packageRecommendations"]["candidates"][0]

    assert top_package["name"] == "招牌鱼锅 + 手作豆花"
    assert top_package["source"] == "computed_combo"
    assert top_package["grossMarginPct"] > 60
    assert top_package["score"] > 80
    assert any("招牌鱼锅 + 手作豆花" in action for action in page["doFirst"])


def test_boss_decision_brief_registered_in_section_router() -> None:
    from smartbi.api.restaurant_sections import HANDLERS, SECTION_DATA_KIND

    assert "boss_decision_brief" in HANDLERS
    assert SECTION_DATA_KIND["boss_decision_brief"] == "none"


def test_owner_action_demo_scenarios_trigger_distinct_actions() -> None:
    expected = {
        "package": "package",
        "seating_mix": "seating_mix",
        "staffing_schedule": "staffing_schedule",
        "staff_training": "staff_training",
        "kitchen_quality": "kitchen_quality",
        "cost_margin": "cost_margin",
        "external_event_response": "external_event_response",
        "single_item_push": "single_item_push",
        "traffic_conversion": "traffic_conversion",
    }
    handler = BossDecisionBriefHandler()

    for scenario, action_type in expected.items():
        response = handler.compute(
            SectionRequest(
                factory_id="RES_3101_009",
                upload_id=None,
                sub_sector="鱼类餐饮",
                period="2026-07-demo",
                params={"demo_scenario": scenario},
            ),
            context={},
        )

        page = response.data["ownerDecisionPage"]
        assert page["decisionFocus"]["primaryActionType"] == action_type, scenario
        assert response.data["storeContext"]["storeName"] == "青花椒川食山语（颛桥龙湖店）"
        assert page["decisionFocus"]["signals"], scenario


def test_owner_action_chat_uses_traffic_persona_and_platform_mock_data() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_DEMO",
            message="客流画像显示路过人多但进店少，今天先改哪个入口？",
        )
    )

    data = response["data"]
    page = data["ownerDecisionPage"]

    assert data["scenario"] == "traffic_conversion"
    assert len(data["charts"]) >= 2
    assert any(chart["title"] == "门口路过客有没有被拉进店" for chart in data["charts"])
    assert any(chart["title"] == "各平台入口谁看了但没下单" for chart in data["charts"])
    assert data["chartGuide"]
    assert page["decisionFocus"]["primaryActionType"] == "traffic_conversion"
    assert "100 个路过的人里大概只有" in data["answer"]
    assert "今天先别继续加投流" in data["answer"]
    assert "明天只看三个数" in data["answer"]
    assert "抖音团购" in data["answer"]
    assert page["trafficPersona"]["available"] is True
    assert page["platformChannelSnapshot"]["available"] is True
    assert any(item["dimension"] == "外部客流画像" for item in page["analysisDimensions"])

    follow_up_without_session = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_DEMO",
            message="老板今天先看哪三个数？",
        )
    )
    assert follow_up_without_session["data"]["scenario"] == "traffic_conversion"
    assert "只看这三个数" in follow_up_without_session["data"]["answer"]
    assert "门口路过人数" in follow_up_without_session["data"]["answer"]
    assert "一句话结论" not in follow_up_without_session["data"]["answer"]


def test_owner_action_chat_follow_up_chips_return_distinct_next_step_answers() -> None:
    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_FOLLOWUP_UX",
            message="客流画像显示路过人多但进店少，今天先改哪个入口？",
        )
    )

    first_data = first["data"]
    assert first_data["scenario"] == "traffic_conversion"
    assert "一句话结论" in first_data["answer"]

    execution_follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_FOLLOWUP_UX",
            session_id=first_data["sessionId"],
            message="入口和平台页面具体改什么",
        )
    )

    execution_data = execution_follow_up["data"]
    assert execution_data["sessionId"] == first_data["sessionId"]
    assert execution_data["scenario"] == "traffic_conversion"
    assert "这个追问我只拆执行细节，不重复前面的结论" in execution_data["answer"]
    assert "一句话结论" not in execution_data["answer"]
    assert "门口" in execution_data["answer"]
    assert "平台" in execution_data["answer"]
    assert execution_data["answer"] != first_data["answer"]

    risk_follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_FOLLOWUP_UX",
            session_id=first_data["sessionId"],
            message="哪些事情今天先不要做？",
        )
    )

    risk_data = risk_follow_up["data"]
    assert risk_data["scenario"] == "traffic_conversion"
    assert "今天先别做" in risk_data["answer"]
    assert "一句话结论" not in risk_data["answer"]


def test_owner_action_chat_routes_and_keeps_follow_up_session() -> None:
    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_DEMO",
            message="二人桌不够，周末翻台怎么调整？",
        )
    )

    first_data = first["data"]
    assert first["success"] is True
    assert first_data["scenario"] == "seating_mix"
    assert first_data["sessionId"]
    assert first_data["answer"]
    assert first_data["followUpSuggestions"]

    follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_DEMO",
            session_id=first_data["sessionId"],
            message="具体要看哪些数？",
        )
    )

    follow_up_data = follow_up["data"]
    assert follow_up_data["sessionId"] == first_data["sessionId"]
    assert follow_up_data["scenario"] == "seating_mix"
    assert "sessionId" not in follow_up_data["answer"]
    assert "seating_mix" not in follow_up_data["answer"]
    assert "只看这三个数" in follow_up_data["answer"]
    assert "一句话结论" not in follow_up_data["answer"]


def test_owner_action_chat_explicit_keywords_override_previous_follow_up_topic() -> None:
    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_EXPLICIT_OVERRIDE",
            message="商圈活动和天气会影响今天客流吗？要怎么备货和推品？",
        )
    )
    assert first["data"]["scenario"] == "external_event_response"

    next_turn = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_EXPLICIT_OVERRIDE",
            message="今天应该主推哪个单品，为什么？",
        )
    )

    assert next_turn["data"]["scenario"] == "single_item_push"
    assert "主推单品" in next_turn["data"]["answer"]


def test_owner_action_chat_returns_scenario_specific_charts() -> None:
    cases = [
        ("seating_mix", "二人桌不够，周末翻台怎么调？", "来的客人和桌型是不是匹配"),
        ("staffing_schedule", "今天排班应该怎么调？", "忙的时段和人手是不是对得上"),
        ("kitchen_quality", "厨房先改哪道菜、怎么验收？", "哪道菜最需要厨房抽查"),
        ("cost_margin", "哪些菜要先查BOM和盘点损耗？", "菜品收入、食材成本、毛利差多少"),
        ("store_compare", "这家店在所有门店里表现算好还是差？差在哪里？", "这家店在连锁里到底强在哪弱在哪"),
    ]
    for scenario, message, expected_title in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_{scenario}",
                message=message,
                demoScenario=scenario,
            )
        )

        data = response["data"]
        titles = [chart["title"] for chart in data["charts"]]
        assert data["scenario"] == scenario
        assert expected_title in titles
        assert data["chartGuide"]
        if scenario == "cost_margin":
            assert "整店成本和同类店差多少" in titles
            assert "套餐价格、成本、毛利能不能撑住" not in titles
        if scenario == "store_compare":
            assert "门店对比" in data["answer"]
            assert "区域经理" in data["answer"]
            assert "工作日午市" in data["answer"]


def test_owner_action_chat_routes_extended_boss_decision_questions() -> None:
    cases = [
        ("今天排班怎么调最合理？", "staffing_schedule", "排班"),
        ("如果服务差评多，店长今天应该怎么培训员工？", "staff_training", "开班前"),
        ("如果差评集中在上菜慢，今天厨房应该怎么改？", "kitchen_quality", "厨房"),
        ("哪些原料可能影响毛利？采购价格是否正常？", "cost_margin", "采购价"),
        ("哪家店最值得学习？它的做法能不能复制到青花椒？", "store_compare", "复制"),
    ]

    for message, scenario, expected_text in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_ROUTE_{scenario}",
                message=message,
            )
        )

        data = response["data"]
        assert data["scenario"] == scenario
        assert expected_text in data["answer"]
        assert data["charts"], message
