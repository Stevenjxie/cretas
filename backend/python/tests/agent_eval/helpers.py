from smartbi.agent.eval.contracts import AgentOpsContext


def request_id(value: int = 1) -> str:
    return f"00000000-0000-4000-8000-{value:012d}"


def context(
    factory: str = "R001", user: str = "42", role: str = "platform_admin"
) -> AgentOpsContext:
    return AgentOpsContext(factory, user, role, "corr-001")


def config_snapshot() -> dict:
    return {
        "promptSnapshotDigest": "1" * 64,
        "modelSnapshotDigest": "2" * 64,
        "toolSnapshotDigest": "3" * 64,
    }


def case(case_id: str = "margin-1") -> dict:
    return {
        "caseId": case_id,
        "expectedRoute": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "requiredTools": ["restaurant_margin_read", "restaurant_cost_read"],
        "numericTruthRefs": {"ev-1:fact-1": "12.50"},
        "maxRounds": 2,
        "maxToolCalls": 4,
    }


def actual(*, value: str = "12.5", tools=None, route=None) -> dict:
    return {
        "routeCode": route or "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "tools": tools if tools is not None else ["restaurant_margin_read", "restaurant_cost_read"],
        "numericTruthRefs": {"ev-1:fact-1": value},
        "roundsUsed": 1,
        "toolCallsUsed": 2,
    }
