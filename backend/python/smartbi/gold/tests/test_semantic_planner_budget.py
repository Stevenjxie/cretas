"""Regression tests for the production REVIEW planner budget."""

import pytest

from common import llm_router
from smartbi.gold.restaurant import restaurant_intent


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("prefer_high_accuracy", "expected_slot", "timeout", "total_timeout", "max_tokens"),
    [
        (False, llm_router.SLOT.MAPPER, 2.5, 6.0, 500),
        (True, llm_router.SLOT.REVIEW, 10.0, 25.0, 1000),
    ],
)
async def test_t3_planner_uses_separate_legacy_and_semantic_budgets(
    monkeypatch,
    prefer_high_accuracy,
    expected_slot,
    timeout,
    total_timeout,
    max_tokens,
):
    seen = {}

    async def fake_call_chain(slot, payload, **kwargs):
        seen.update(slot=slot, payload=payload, kwargs=kwargs)
        return {
            "choices": [
                {
                    "message": {
                        "content": (
                            '{"intent":"RESTAURANT_OPS_DAYPART_PERFORMANCE",'
                            '"confidence":0.95}'
                        )
                    }
                }
            ]
        }

    monkeypatch.setattr(llm_router, "call_chain", fake_call_chain)

    parsed = await restaurant_intent._t3_llm_parse(
        "过去30天午市、下午茶、晚市和夜宵哪个时段营业额最高",
        hint=None,
        history=None,
        prefer_high_accuracy=prefer_high_accuracy,
    )

    assert parsed["intent"] == "RESTAURANT_OPS_DAYPART_PERFORMANCE"
    assert seen["slot"] == expected_slot
    assert seen["kwargs"]["timeout"] == timeout
    assert seen["kwargs"]["total_timeout"] == total_timeout
    assert seen["payload"]["max_tokens"] == max_tokens
