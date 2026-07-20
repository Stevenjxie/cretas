from smartbi.agent.orchestrator import SYSTEM_PROMPT


def test_action_prompt_forbids_unsupported_uplift_ranges():
    assert "没有依据时禁止" in SYSTEM_PROMPT
    assert "历史对照、实验结果或明确因果依据" in SYSTEM_PROMPT
    assert "达到什么条件再推广" in SYSTEM_PROMPT
