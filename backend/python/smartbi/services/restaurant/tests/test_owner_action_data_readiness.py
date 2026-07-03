from __future__ import annotations

from smartbi.api.restaurant_sections import OwnerActionChatRequest, owner_action_chat


def test_owner_action_chat_response_exposes_demo_data_readiness() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_DATA_READINESS_DEMO",
            message="这周营收同比上周怎么提高，仓管厨师长前台分别要做什么？",
        )
    )

    readiness = response["data"]["dataReadiness"]
    assert readiness["mode"] == "demo_mock_plus_seeded_external"
    assert readiness["scenario"] == "operations_dispatch"
    assert readiness["enoughForDemoDecision"] is True
    assert readiness["enoughForProductionRoiPromise"] is False
    assert {
        "pos_sales",
        "review_feedback",
        "inventory",
        "bom_cost",
        "traffic_persona",
        "external_event",
    }.issubset(set(readiness["sourceTypes"]))
    assert "客户授权的真实 POS" in " ".join(readiness["missingForProduction"])


def test_owner_action_chat_routes_boss_variant_questions_to_specific_scenarios() -> None:
    cases = [
        ("不要泛泛说人效，直接告诉我今天几段班怎么排", "", "staffing_schedule", ("排班", "前厅")),
        ("如果今晚客流比昨天多，厨房备菜怎么调？", "", "inventory_reorder", ("补货", "仓管")),
        ("商场今天有活动的话，我们门口和套餐怎么配合？", "", "external_event_response", ("商场", "活动")),
        ("酸菜鱼配什么小菜饮品更合理，别只看销量", "", "package", ("套餐", "毛利")),
        ("周末桌子翻不动，是桌型、出餐还是服务的问题？先查什么？", "", "seating_mix", ("桌", "翻台")),
        ("不要泛泛说，仓管今天具体补什么？", "inventory", "inventory_reorder", ("仓管", "补")),
        ("厨房慢和服务慢哪个先处理？", "review", "staff_training", ("厨房", "服务")),
    ]

    for message, scenario_hint, expected_scenario, expected_words in cases:
        response = owner_action_chat(
            OwnerActionChatRequest(
                factory_id="F_DATA_READINESS_DEMO",
                message=message,
                demoScenario=scenario_hint,
            )
        )
        data = response["data"]
        answer = data["answer"]

        assert data["dataReadiness"]["scenario"] == expected_scenario, message
        for word in expected_words:
            assert word in answer, f"{message}: missing {word} in {answer}"


def test_owner_action_followup_negative_style_request_still_answers_action() -> None:
    response = owner_action_chat(
        OwnerActionChatRequest(
            factory_id="F_DATA_READINESS_DEMO",
            message="不要泛泛说，仓管今天具体补什么？",
            sessionId="owner-action-style-negative",
            demoScenario="inventory",
        )
    )

    answer = response["data"]["answer"]
    assert response["data"]["dataReadiness"]["scenario"] == "inventory_reorder"
    assert "今天照这三步做" in answer
    assert "先补活鱼" in answer
    assert "今天先别做什么" not in answer
