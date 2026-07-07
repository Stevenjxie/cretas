from __future__ import annotations

from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.boss_decision_brief import (
    BossDecisionBriefHandler,
)
from smartbi.api.restaurant_sections import (
    _OWNER_ACTION_CHAT_SESSIONS,
    _owner_action_session_key,
    OwnerActionChatRequest,
    owner_action_chat,
)


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


def test_package_recommendation_excludes_low_value_fillers() -> None:
    handler = BossDecisionBriefHandler()
    fish = "\u62db\u724c\u9752\u82b1\u6912\u9c7c"
    dessert = "\u624b\u4f5c\u51b0\u8c46\u82b1"
    rice = "\u7c73\u996d"

    response = handler.compute(
        SectionRequest(
            factory_id="F_PACKAGE_FILLER_TEST",
            upload_id=None,
            sub_sector="\u9c7c\u7c7b\u9910\u996e",
            store_name="\u5957\u9910\u8fc7\u6ee4\u6d4b\u8bd5\u5e97",
            params={
                "pos_summary": {
                    "aov": 150,
                    "weekdayWeekend": {"weekdayAvgDailyRevenue": 10000, "weekendAvgDailyRevenue": 17000, "gapPct": 70},
                    "customerSegments": [{"segment": "2\u4eba\u684c", "share": 0.55}],
                },
                "menu_summary": {
                    "topProducts": [
                        {"name": fish, "revenue": 120000, "soldQty": 1000, "foodCost": 46000},
                        {"name": dessert, "revenue": 32000, "soldQty": 800, "foodCost": 6400},
                    ],
                    "products": [
                        {"name": rice, "revenue": 20000, "soldQty": 2000, "foodCost": 4000},
                    ],
                    "basketPairs": [
                        {"left": fish, "right": rice, "orders": 900},
                        {"left": fish, "right": dessert, "orders": 600},
                    ],
                },
                "review_summary": {
                    "positiveDishMentions": [{"name": fish, "count": 80}, {"name": dessert, "count": 45}],
                    "negativeDishMentions": [],
                },
            },
        ),
        context={},
    )

    candidates = response.data["ownerDecisionPage"]["packageRecommendations"]["candidates"]
    assert candidates
    assert all(rice not in candidate["items"] for candidate in candidates)
    assert candidates[0]["items"] == [fish, dessert]


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

    follow_up_with_session = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_DEMO",
            session_id=data["sessionId"],
            message="老板今天先看哪三个数？",
        )
    )
    assert follow_up_with_session["data"]["scenario"] == "traffic_conversion"
    assert "只看这三个数" in follow_up_with_session["data"]["answer"]
    assert "门口路过人数" in follow_up_with_session["data"]["answer"]
    assert "一句话结论" not in follow_up_with_session["data"]["answer"]


def test_owner_action_demo_mode_marks_mock_sources_ready() -> None:
    handler = BossDecisionBriefHandler()
    response = handler.compute(
        SectionRequest(
            factory_id="F_DEMO_READY",
            upload_id=None,
            sub_sector="鱼类餐饮",
            period="2026-07-demo",
            params={"demo_scenario": "external_event_response"},
        ),
        context={},
    )

    data = response.data
    readiness = data["dataReadiness"]
    assert all(item["available"] for item in readiness["sources"])
    assert readiness["enoughForBossDirection"] is True
    assert readiness["enoughForHardRoiPromise"] is True
    assert "缺" not in data["finalAnswer"]
    assert "无法判断" not in data["finalAnswer"]
    assert all("缺" not in str(card.get("why", "")) for card in data["decisionCards"])


def test_owner_action_chat_prioritizes_external_event_when_weather_and_activity_are_named() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_EXTERNAL_EVENT_DEMO",
            message="结合今天的天气、商圈活动和客流画像，今天适合怎么做生意？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "external_event_response"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]
    assert "无法判断" not in data["answer"]


def test_owner_action_chat_routes_rain_dine_in_takeout_questions_to_external_event() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_EXTERNAL_RAIN_TAKEOUT",
            message="如果今天下雨，堂食和外卖动作应该怎么调？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "external_event_response"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]


def test_owner_action_chat_routes_single_extra_worker_question_to_staffing() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_STAFFING_ONE_EXTRA_WORKER",
            message="今天只加一个人，应该加在哪个环节？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "staffing_schedule"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]


def test_owner_action_chat_routes_waiter_script_training_question() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_STAFF_TRAINING_SCRIPT",
            message="开班前服务员今天先训练哪三句话术？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "staff_training"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]


def test_owner_action_chat_routes_overstock_question_to_cost_margin() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_COST_OVERSTOCK",
            message="哪些菜不适合继续备太多？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "cost_margin"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]


def test_owner_action_chat_cost_margin_mentions_review_and_quality_risk() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_COST_REVIEW_FUSION",
            message="这个星期营收比上周有什么问题，结合评论和菜品毛利给我直接建议",
            demo_scenario="cost_margin",
        )
    )

    answer = response["data"]["answer"]
    assert response["data"]["scenario"] == "cost_margin"
    assert "差评" in answer or "评价" in answer
    assert "BOM" in answer
    assert "毛利" in answer


def test_owner_action_chat_routes_bom_variance_question_to_cost_margin() -> None:
    cases = [
        ("BOM理论用量和实际用量不一致，先查什么？", "cost_margin"),
        ("成本毛利先查哪几项，今天怎么查？", "cost_margin"),
        ("如果毛利掉了，是采购、损耗还是菜品结构问题？", "cost_margin"),
        ("哪些菜卖得多但不应该继续重点推？", "cost_margin"),
        ("今天推套餐，哪些食材要提前准备，哪些不能多备？", "package"),
        ("月盘点发现损耗高，今天厨房和采购怎么改？", "kitchen_quality"),
    ]

    for message, expected_scenario in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_COST_BOM_{len(message)}",
                message=message,
            )
        )

        data = response["data"]
        assert data["scenario"] == expected_scenario
        assert "一句话结论" in data["answer"]
        assert "缺" not in data["answer"]


def test_owner_action_chat_seating_mix_gives_table_level_action() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_SEATING_TABLE_DETAIL",
            message="今天桌型和排班怎么调，二人桌四人桌怎么安排？",
        )
    )

    answer = response["data"]["answer"]
    assert response["data"]["scenario"] == "seating_mix"
    assert "2 张四人桌" in answer
    assert "2 人客" in answer
    assert "3-4 人客" in answer
    assert "四人桌被二人客占用" in answer


def test_owner_action_chat_routes_returned_dish_rework_question_to_kitchen() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_KITCHEN_REWORK",
            message="退菜和重做变多，今天厨师长先查什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "kitchen_quality"
    assert "一句话结论" in data["answer"]
    assert "缺" not in data["answer"]


def test_owner_action_chat_routes_platform_and_door_conversion_questions() -> None:
    cases = [
        "美团大众点评有人看但没下单，老板今天先改什么？",
        "门口路过客很多，为什么没有转成订单，怎么解决？",
        "商场热起来了但青花椒没有涨，今天怎么接住这波人？",
    ]

    for message in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_TRAFFIC_WORDING_{len(message)}",
                message=message,
            )
        )

        data = response["data"]
        assert data["scenario"] == "traffic_conversion"
        assert "一句话结论" in data["answer"]
        assert "缺" not in data["answer"]


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


def test_owner_action_chat_common_follow_up_chips_do_not_repeat_first_answer() -> None:
    cases = [
        ("F_PACKAGE_FOLLOWUP_UX", "要不要推小套餐，推什么组合？", "package", "把套餐执行细节拆给我"),
        ("F_KITCHEN_FOLLOWUP_UX", "厨房出餐慢应该怎么处理？", "kitchen_quality", "厨房抽查怎么做"),
        ("F_OPS_FOLLOWUP_UX", "前台员工今天应该重点提醒什么？", "operations_dispatch", "把仓管厨师长前台的动作拆细"),
    ]

    for factory_id, message, expected_scenario, expected_follow_up in cases:
        first = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=factory_id,
                message=message,
            )
        )["data"]

        assert first["scenario"] == expected_scenario
        assert first["followUpSuggestions"][0] == expected_follow_up

        follow_up = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=factory_id,
                session_id=first["sessionId"],
                message=first["followUpSuggestions"][0],
            )
        )["data"]

        assert follow_up["scenario"] == expected_scenario
        assert follow_up["answer"] != first["answer"]
        assert "这个追问我只拆执行细节，不重复前面的结论" in follow_up["answer"]
        assert "一句话结论" not in follow_up["answer"]


def test_owner_action_follow_up_uses_explicit_session_and_scenario_when_memory_misses() -> None:
    _OWNER_ACTION_CHAT_SESSIONS.pop(
        _owner_action_session_key("F_TRAFFIC_CROSS_WORKER", "owner-action-missing-worker"),
        None,
    )

    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_TRAFFIC_CROSS_WORKER",
            session_id="owner-action-missing-worker",
            demoScenario="traffic_conversion",
            message="哪些事情今天先不要做？",
        )
    )

    data = response["data"]
    assert data["sessionId"] == "owner-action-missing-worker"
    assert data["scenario"] == "traffic_conversion"
    assert "今天先别做" in data["answer"]
    assert "一句话结论" not in data["answer"]


def test_owner_action_follow_up_chips_survive_missing_worker_memory() -> None:
    cases = [
        (
            "F_CROSS_WORKER_REVENUE",
            "老板就想知道这周怎么提高营收，应该先做什么？",
            "revenue_growth",
            "我建议今天先拆客流转化",
        ),
        (
            "F_CROSS_WORKER_OPS",
            "前台员工今天应该重点提醒什么？",
            "operations_dispatch",
            "这个追问我只拆执行细节",
        ),
        (
            "F_CROSS_WORKER_KITCHEN",
            "厨房出餐慢应该怎么处理？",
            "kitchen_quality",
            "这个追问我只拆执行细节",
        ),
    ]

    for factory_id, message, expected_scenario, expected_answer in cases:
        first = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=factory_id,
                message=message,
            )
        )["data"]
        session_id = first["sessionId"]
        _OWNER_ACTION_CHAT_SESSIONS.pop(_owner_action_session_key(factory_id, session_id), None)

        follow_up = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=factory_id,
                session_id=session_id,
                message=first["followUpSuggestions"][0],
            )
        )["data"]

        assert follow_up["scenario"] == expected_scenario
        assert expected_answer in follow_up["answer"]
        assert follow_up["answer"] != first["answer"]
        assert "一句话结论" not in follow_up["answer"]


def test_owner_action_package_question_with_two_person_context_prefers_package() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_PACKAGE_PRIORITY",
            message="两人客多的时候，应该推什么小套餐提升客单？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "package"
    assert "套餐" in data["answer"]
    assert "一句话结论" in data["answer"]


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


def test_owner_action_chat_does_not_reuse_factory_last_scenario_without_session() -> None:
    _OWNER_ACTION_CHAT_SESSIONS.pop(
        _owner_action_session_key("F_NO_FACTORY_FALLBACK", "owner-action-external-seeded"),
        None,
    )

    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_NO_FACTORY_FALLBACK",
            session_id="owner-action-external-seeded",
            message="今天下雨加上商场活动，堂食和外卖要怎么调？",
        )
    )
    assert first["data"]["scenario"] == "external_event_response"

    follow_up_without_session = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_NO_FACTORY_FALLBACK",
            message="具体怎么执行？",
        )
    )

    assert follow_up_without_session["data"]["sessionId"] != "owner-action-external-seeded"
    assert follow_up_without_session["data"]["scenario"] == "package"


def test_owner_action_chat_sessions_are_scoped_by_factory() -> None:
    shared_session = "owner-action-shared-session"
    _OWNER_ACTION_CHAT_SESSIONS.pop(_owner_action_session_key("F_SESSION_A", shared_session), None)
    _OWNER_ACTION_CHAT_SESSIONS.pop(_owner_action_session_key("F_SESSION_B", shared_session), None)

    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_SESSION_A",
            session_id=shared_session,
            demoScenario="traffic_conversion",
            message="瀹㈡祦鐢诲儚鏄剧ず璺繃浜哄浣嗚繘搴楀皯锛屼粖澶╁厛鏀瑰摢涓叆鍙ｏ紵",
        )
    )
    assert first["data"]["scenario"] == "traffic_conversion"

    same_factory_follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_SESSION_A",
            session_id=shared_session,
            message="鍏蜂綋鎬庝箞鎵ц锛?",
        )
    )
    assert same_factory_follow_up["data"]["scenario"] == "traffic_conversion"

    other_factory_same_session = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_SESSION_B",
            session_id=shared_session,
            message="鍏蜂綋鎬庝箞鎵ц锛?",
        )
    )
    assert other_factory_same_session["data"]["sessionId"] == shared_session
    assert other_factory_same_session["data"]["scenario"] == "package"


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


def test_owner_action_chat_corrects_decline_premise_when_demo_data_is_up() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_PREMISE_CONFLICT",
            message="这个星期营收同比上周下滑，不想打折，今天怎么提高客单？",
        )
    )

    answer = response["data"]["answer"]
    assert response["data"]["scenario"] == "package"
    assert "我先纠正一个前提" in answer
    assert "并不是下滑" in answer
    assert "指定门店下滑" in answer
    assert "美团/点评核销下滑" in answer
    assert "套餐" in answer


def test_owner_action_chat_matrix_questions_route_to_plain_boss_decisions() -> None:
    cases = [
        (
            "这个星期营收同比上周下滑，不想打折，今天怎么提高客单？",
            "package",
            ("套餐", "客单", "毛利"),
        ),
        (
            "如果今天下雨，堂食和外卖动作应该怎么调？",
            "external_event_response",
            ("天气", "堂食", "外卖"),
        ),
        (
            "商场今天有活动，我们怎么备货和接客？",
            "external_event_response",
            ("商场", "活动", "备货"),
        ),
        (
            "美团大众点评有人看但核销低，老板今天先改什么？",
            "traffic_conversion",
            ("美团", "大众点评", "核销"),
        ),
        (
            "抖音团购曝光高但到店少，今天怎么改？",
            "traffic_conversion",
            ("抖音", "团购", "核销"),
        ),
        (
            "厨房出餐慢导致差评，厨师长今天先盯什么？",
            "kitchen_quality",
            ("厨房", "厨师长", "出餐"),
        ),
        (
            "BOM理论用量和实际用量不一致，先查什么？",
            "cost_margin",
            ("BOM", "理论用量", "实际用量"),
        ),
        (
            "同商圈竞品都在引流，青花椒门口路过客怎么转进店？",
            "traffic_conversion",
            ("同商圈", "竞品", "引流"),
        ),
        (
            "仓管+厨师长+前台今天分别盯什么，老板不用一直催？",
            "operations_dispatch",
            ("仓管", "厨师长", "前台"),
        ),
        (
            "月盘点发现损耗高，厨房和采购今天怎么改？",
            "kitchen_quality",
            ("月盘点", "损耗", "厨房"),
        ),
    ]

    for message, expected_scenario, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_MATRIX_{expected_scenario}_{len(message)}",
                message=message,
                demoScenario="package",
            )
        )

        data = response["data"]
        answer = data["answer"]
        assert data["scenario"] == expected_scenario, message
        assert "一句话结论" in answer
        assert "缺少数据" not in answer
        assert "无法判断" not in answer
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"


def test_owner_action_chat_package_variants_answer_different_business_constraints() -> None:
    cases = [
        (
            "根据菜品毛利和成本，帮我算一个适合今天推的小套餐",
            ("售价", "食材成本", "毛利"),
        ),
        (
            "给我推荐小套餐，但不要把米饭这种低价值单品排进主推",
            ("米饭", "低价值", "排除"),
        ),
        (
            "外卖平台今天适合推什么双人套餐？要考虑成本和差评风险",
            ("外卖", "配送", "差评风险"),
        ),
    ]

    answers = []
    for message, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_PACKAGE_VARIANT_{len(message)}",
                message=message,
                demoScenario="package",
            )
        )

        data = response["data"]
        answer = data["answer"]
        assert data["scenario"] == "package"
        answers.append(answer)
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"

    assert len(set(answers)) == len(answers)


def test_owner_action_chat_inventory_and_cost_variants_keep_distinct_focus() -> None:
    cases = [
        (
            "哪些菜今天不要多备？我不想晚上又报损",
            "inventory_reorder",
            ("不要多备", "报损", "低销量"),
        ),
        (
            "活鱼理论用量和实际用量差太多，厨师长今天要查什么？",
            "cost_margin",
            ("理论用量", "实际用量", "称重"),
        ),
        (
            "月盘点发现损耗高，今天不用等月底先查哪几项？",
            "cost_margin",
            ("月盘点", "月底", "损耗"),
        ),
    ]

    answers = []
    for message, expected_scenario, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_COST_INVENTORY_VARIANT_{len(message)}",
                message=message,
                demoScenario="package",
            )
        )

        data = response["data"]
        answer = data["answer"]
        assert data["scenario"] == expected_scenario
        answers.append(answer)
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"

    assert len(set(answers)) == len(answers)


def test_owner_action_chat_platform_and_external_variants_keep_distinct_focus() -> None:
    cases = [
        (
            "美团曝光有了但核销少，今天该改页面、套餐还是门口承接？",
            ("美团", "核销", "曝光"),
        ),
        (
            "抖音团购带来的人客单低，怎么别亏毛利？",
            ("抖音", "团购", "毛利"),
        ),
        (
            "同商圈酸菜鱼竞品变多，我今天先改产品还是先改引流？",
            ("同商圈", "竞品", "引流"),
        ),
        (
            "客流画像显示路过人多但进店少，今天先改哪个入口？",
            ("客流画像", "路过", "进店"),
        ),
    ]

    answers = []
    for message, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_TRAFFIC_VARIANT_{len(message)}",
                message=message,
                demoScenario="traffic_conversion",
            )
        )

        data = response["data"]
        answer = data["answer"]
        assert data["scenario"] == "traffic_conversion"
        answers.append(answer)
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"

    assert len(set(answers)) == len(answers)


def test_owner_action_chat_seating_and_staffing_variants_keep_distinct_focus() -> None:
    cases = [
        (
            "今天桌型和排班怎么调，二人桌四人桌怎么安排？",
            "seating_mix",
            ("二人桌", "四人桌", "可拼可拆"),
        ),
        (
            "周末排队长但空桌也多，是桌型问题还是前台引导问题？",
            "seating_mix",
            ("排队", "空桌", "前台"),
        ),
        (
            "今天只能多加一个人，是加前厅还是后厨？",
            "staffing_schedule",
            ("只能加一个人", "前厅", "后厨"),
        ),
        (
            "厨房出餐慢和差评变多，今天先改哪三个动作？",
            "kitchen_quality",
            ("厨房", "出餐", "差评"),
        ),
    ]

    answers = []
    for message, expected_scenario, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_SEATING_STAFFING_VARIANT_{len(message)}",
                message=message,
                demoScenario="package",
            )
        )

        data = response["data"]
        answer = data["answer"]
        assert data["scenario"] == expected_scenario
        answers.append(answer)
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"

    assert len(set(answers)) == len(answers)


def test_owner_action_chat_new_explicit_question_does_not_follow_previous_session() -> None:
    first = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_NEW_EXPLICIT_NO_FOLLOWUP",
            session_id="owner-action-no-sticky-session",
            message="客流画像显示路过人多但进店少，今天先改哪个入口？",
        )
    )
    assert first["data"]["scenario"] == "traffic_conversion"

    next_turn = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_NEW_EXPLICIT_NO_FOLLOWUP",
            session_id=first["data"]["sessionId"],
            demoScenario="traffic_conversion",
            message="商场今天有活动，我们怎么备货和接客？",
        )
    )

    assert next_turn["data"]["scenario"] == "external_event_response"
    assert "一句话结论" in next_turn["data"]["answer"]
    assert "这个追问我只拆执行细节" not in next_turn["data"]["answer"]
    assert "商场" in next_turn["data"]["answer"]


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
        ("哪些菜值得主推，哪些低价值菜要排除？", "single_item_push", "低价值"),
        ("主推单品怎么判断有没有拉动加购？", "single_item_push", "加购率"),
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


def test_owner_action_chat_routes_cross_role_operations_dispatch() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_ROLE_ACTION_DEMO",
            message="这周营收同比上周怎么提高，仓管厨师长前台分别要做什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "operations_dispatch"
    assert "仓管" in data["answer"]
    assert "厨师长" in data["answer"]
    assert "前台" in data["answer"]
    assert "店长" in data["answer"]

    role_plan = data["ownerDecisionPage"]["roleActionPlan"]
    roles = {item["role"] for item in role_plan}
    assert {"仓管", "厨师长", "前台/门迎", "店长"}.issubset(roles)
    assert any("活鱼" in action for item in role_plan for action in item["todayActions"])
    assert any("上菜" in metric for item in role_plan for metric in item["watchTomorrow"])
    assert data["charts"]


def test_owner_action_chat_direct_special_answers_for_scope_role_and_script() -> None:
    cases = [
        (
            "如果数据不确定，应该先问我什么范围？",
            ("具体哪家门店", "哪个时间段", "哪个平台或渠道", "选范围"),
        ),
        (
            "如果店长说没问题，我怎么用数据反问？",
            ("反问店长", "工作日午市收入", "感觉还行"),
        ),
        (
            "这个建议要落到前台话术，怎么说？",
            ("前台今天就用这三句话", "迎宾", "核销", "安抚"),
        ),
        (
            "老板不要一直催，四个岗位今天各自盯什么？",
            ("仓管", "厨师长", "前台", "店长"),
        ),
        (
            "如果我没有真实POS，只看demo数据能演示什么？",
            ("demo 数据", "真实 POS", "决策逻辑"),
        ),
        (
            "我只想推一款，不想前台乱推，今天选哪款？",
            ("只推一款", "招牌青花椒鱼", "前台不要乱推"),
        ),
        (
            "如果套餐卖得多但毛利变薄，明天怎么判断要不要停？",
            ("要不要停", "先停", "继续测"),
        ),
        (
            "营收掉了但我不想做满减，怎么拉回来？",
            ("不想打折", "满减", "高毛利"),
        ),
        (
            "平台券核销以后前台怎么二次推荐才不亏？",
            ("平台券核销", "前台", "二次推荐"),
        ),
        (
            "如果同层餐饮都在抢客，我的第一眼信息怎么做？",
            ("第一眼", "招牌是什么", "两个人多少钱", "多久能吃完"),
        ),
        (
            "二人客等位久，前台要怎么分流？",
            ("前台", "分流", "两人客"),
        ),
        (
            "节假日突然客流大了，怎么判断是不是外部原因？",
            ("外部原因", "商场客流", "本店订单"),
        ),
        (
            "低峰要不要少排人，会不会影响迎宾和核销？",
            ("低峰", "迎宾", "核销", "少排过头"),
        ),
        (
            "如果上菜慢和等位都变长，晚市加哪个岗位？",
            ("晚市", "前厅协调岗", "等位", "上菜"),
        ),
        (
            "服务员被差评点名，是培训话术还是厨房速度问题？",
            ("厨房速度", "服务话术", "厨师长"),
        ),
        (
            "底料实际耗用高于BOM，今天先查厨师长还是仓管？",
            ("先查厨师长", "仓管", "账实差异"),
        ),
        (
            "先别做什么，哪些动作会误伤利润？",
            ("误伤利润", "别全店满减", "毛利"),
        ),
    ]

    for message, expected_terms in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id="F_DIRECT_SPECIAL_DEMO",
                message=message,
            )
        )

        answer = response["data"]["answer"]
        for term in expected_terms:
            assert term in answer


def test_owner_action_chat_duplicate_risk_variants_get_direct_answers() -> None:
    cases = [
        (
            "如果只允许改一个动作，今天改哪个？",
            ("只改一个动作", "门口和平台第一句话", "明天看门口咨询数"),
        ),
        (
            "明天怎么复盘这个动作有没有用？",
            ("三张小表", "份数涨", "店长只需要回答"),
        ),
        (
            "给我一句话结论，不要讲大段模型逻辑",
            ("一句话结论", "不要先全店打折", "明天只看三个数"),
        ),
        (
            "今天哪些事情先不要做？",
            ("别全店满减", "别继续加投流", "明天再决定"),
        ),
        (
            "美团点评首图和门口海报要不要讲同一句话？",
            ("同图同价同承诺", "美团/点评首图", "门口海报"),
        ),
        (
            "大众点评有人收藏但不到店，老板先改什么？",
            ("收藏后为什么今天就来", "到店可核销", "收藏到核销"),
        ),
        (
            "商场活动只持续两个小时，后厨备货别浪费怎么做？",
            ("两小时活动", "活动窗口备 80%", "低频复杂菜不加备"),
        ),
        (
            "仓管厨师长前台的动作拆细一点，谁几点做什么？",
            ("10:30 仓管", "15:30 厨师长", "17:30 店长"),
        ),
        (
            "小套餐里能不能加冰豆花，毛利和吸引力怎么算？",
            ("冰豆花可以加", "高毛利小食", "约 170 元毛利"),
        ),
        (
            "抖音曝光高但晚高峰座位被便宜券占了怎么办？",
            ("晚高峰被便宜券占座", "低毛利券限核销量", "券后客单价"),
        ),
        (
            "门口经过的人多，为什么就是不进来，今天怎么改？",
            ("门口人多但不进来", "海报只保留三句话", "实际进店"),
        ),
        (
            "四人桌老被两人占死，今天怎么改不影响四人客？",
            ("四人桌被两人占死", "可拼可拆区", "四人客有没有被赶走"),
        ),
        (
            "点评说鱼片老了，后厨今天怎么抽查？",
            ("鱼片老了", "鱼片厚度", "下锅到出锅时间"),
        ),
        (
            "临期豆腐和番茄怎么处理，不想报损又不想硬推给客人",
            ("临期豆腐和番茄", "不能硬推给顾客", "新增相关差评"),
        ),
        (
            "如果毛利掉了，是采购、损耗还是菜品结构问题？",
            ("按三步归因", "采购", "损耗", "菜品结构"),
        ),
        (
            "同城门店午市做得更好，我们该复制哪个动作？",
            ("不要复制整套菜单", "复制青花椒静安大融城店", "午市客单价"),
        ),
        (
            "营收掉了但我不想做满减，怎么拉回来？",
            ("我先纠正一个前提", "不想打折", "套餐毛利"),
        ),
        (
            "如果我感觉本周变差了，今天先管什么？",
            ("我先纠正一个前提", "范围确认", "先不要改价格"),
        ),
        (
            "今天桌型和排班怎么调，二人桌四人桌怎么安排？",
            ("先调桌型", "晚市排班", "前厅协调岗"),
        ),
    ]

    answers = []
    for message, expected_terms in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id=f"F_DUPLICATE_RISK_VARIANT_{len(message)}",
                message=message,
            )
        )
        answer = response["data"]["answer"]
        answers.append(answer)
        assert "一句话结论：工作日/低峰转化不足" not in answer
        for term in expected_terms:
            assert term in answer, f"{message}: missing {term} in {answer}"

    assert len(set(answers)) == len(answers)


def test_owner_action_chat_routes_inventory_reorder_and_seeds_role_plan() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_INVENTORY_REORDER_DEMO",
            message="库存预警和采购补货今天先看什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "inventory_reorder"
    assert "不要平均补货" in data["answer"]
    assert "活鱼" in data["answer"]
    assert "青花椒底料" in data["answer"]

    role_plan = data["ownerDecisionPage"]["roleActionPlan"]
    warehouse = next(item for item in role_plan if item["role"] == "仓管")
    assert any("安全库存" in action for action in warehouse["todayActions"])
    assert any("临期" in action for action in warehouse["todayActions"])


def test_owner_action_chat_broad_revenue_question_offers_decision_paths() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_BROAD_REVENUE_DEMO",
            message="老板就想知道这周怎么提高营收，应该先做什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "revenue_growth"
    assert "先选营收杠杆" in data["answer"]
    assert "客流转化" in data["answer"]
    assert "客单和套餐" in data["answer"]
    assert "翻台和桌型" in data["answer"]
    assert "排班和出餐" in data["answer"]
    assert "成本毛利" in data["answer"]
    assert "缺数据" not in data["answer"]
    assert "无法判断" not in data["answer"]
    assert "帮我选一个营收杠杆继续拆" in data["followUpSuggestions"]

    follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_BROAD_REVENUE_DEMO",
            session_id=data["sessionId"],
            message="帮我选一个营收杠杆继续拆",
        )
    )
    follow_up_data = follow_up["data"]
    assert follow_up_data["scenario"] == "revenue_growth"
    assert follow_up_data["answer"] != data["answer"]
    assert "我建议今天先拆客流转化" in follow_up_data["answer"]
    assert "如果明天进店没动" in follow_up_data["answer"]
    assert follow_up_data["followUpSuggestions"][0] == "门口海报和首图具体怎么改"
    assert "帮我选一个营收杠杆继续拆" not in follow_up_data["followUpSuggestions"]

    next_follow_up = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_BROAD_REVENUE_DEMO",
            session_id=data["sessionId"],
            message=follow_up_data["followUpSuggestions"][0],
        )
    )
    next_follow_up_data = next_follow_up["data"]
    assert next_follow_up_data["scenario"] == "revenue_growth"
    assert next_follow_up_data["answer"] != follow_up_data["answer"]
    assert "门口海报" in next_follow_up_data["answer"]
    assert "美团/大众点评首图" in next_follow_up_data["answer"]


def test_owner_action_chat_routes_weekly_revenue_decline_to_revenue_growth() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_WEEKLY_REVENUE_DECLINE",
            message="这周营收比上周差，老板应该先做什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "revenue_growth"
    assert "先选营收杠杆" in data["answer"]
    assert "套餐" in data["answer"]
    assert "缺数据" not in data["answer"]


def test_owner_action_chat_routes_felt_decline_to_revenue_growth_with_scope_check() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_FELT_REVENUE_DECLINE",
            message="如果我感觉本周变差了，今天先管什么？",
        )
    )

    data = response["data"]
    assert data["scenario"] == "revenue_growth"
    assert "我先纠正一个前提" in data["answer"]
    assert "范围确认" in data["answer"]
    assert "先不要改价格" in data["answer"]
