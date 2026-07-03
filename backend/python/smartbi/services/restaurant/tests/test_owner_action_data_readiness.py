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
