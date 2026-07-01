from __future__ import annotations

from smartbi.services.restaurant.sections.base import SectionRequest, SectionStatus
from smartbi.services.restaurant.sections.boss_decision_brief import (
    BossDecisionBriefHandler,
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
    assert data["ownerDecisionPage"]["title"] == "老板决策页"
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
    assert data["ownerDecisionPage"]["expectedImpact"]["plainText"].startswith("当前缺少可用客单")


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
                        {"name": "特色青花椒鱼[活鱼现做]", "revenue": 237060},
                        {"name": "特色青花椒鱼[活鱼手工去刺]", "revenue": 154368},
                        {"name": "乌蒙山干锅牛肉2-3人餐", "revenue": 132861},
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

    assert "工作日承接" in page["headline"]
    assert "80.6%" in page["headline"]
    assert "最近一周营收环比 10.5%" in page["plainDiagnosis"]
    assert "晚市贡献约 60.0%" in page["plainDiagnosis"]
    assert "2人桌" in page["plainDiagnosis"]
    assert "美团/大众点评" in page["plainDiagnosis"]
    assert any("周一到周四" in action for action in page["doFirst"])
    assert any("手作冰豆花" in action for action in page["doFirst"])
    assert any("不要盲目加推" in action for action in page["doNotDo"])
    assert "一周可多约 11478 元营收" in page["expectedImpact"]["plainText"]
    assert any("工作日日均 15801.98" in evidence for evidence in page["keyEvidence"])
    assert any(item["dimension"] == "渠道结构" and "已接渠道贡献" in item["currentFinding"] for item in page["analysisDimensions"])
    assert any(item["dimension"] == "利润闭环" and "缺 BOM" in item["currentFinding"] for item in page["analysisDimensions"])


def test_boss_decision_brief_registered_in_section_router() -> None:
    from smartbi.api.restaurant_sections import HANDLERS, SECTION_DATA_KIND

    assert "boss_decision_brief" in HANDLERS
    assert SECTION_DATA_KIND["boss_decision_brief"] == "none"
