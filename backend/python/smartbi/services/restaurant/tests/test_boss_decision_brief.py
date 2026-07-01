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


def test_boss_decision_brief_registered_in_section_router() -> None:
    from smartbi.api.restaurant_sections import HANDLERS, SECTION_DATA_KIND

    assert "boss_decision_brief" in HANDLERS
    assert SECTION_DATA_KIND["boss_decision_brief"] == "none"
