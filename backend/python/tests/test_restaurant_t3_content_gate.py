"""The router content gate that the restaurant T3 caller supplies (2026-07-30).

The distinction these lock down is the whole point of the gate: a NEGATIVE or
MISSING confidence means the provider does not implement the plan contract (so
try the next provider), while a genuinely LOW confidence means the model is
honestly unsure (honor it as a clarification, do NOT shop for a friendlier
model).

Measured behavior that forced this: aliyun_c/deepseek-v3.2 returns a fully
correct plan with confidence=-1.0 / -0.95. That is HTTP 200 with parseable JSON,
so neither the router's slot-generic validation nor the caller could tell it
apart from success -- the cascade stopped there and every healthy model behind
it became unreachable, degrading restaurant Q&A every afternoon once the Aliyun
free quota ran out.
"""
from __future__ import annotations

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


def test_t3_gate_rejects_negative_confidence():
    assert ri._t3_contract_violation(
        '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":-1.0}'
    ) == "t3_confidence_negative"
    assert ri._t3_contract_violation(
        '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":-0.95}'
    ) == "t3_confidence_negative"


def test_t3_gate_rejects_missing_or_non_numeric_confidence():
    assert ri._t3_contract_violation(
        '{"intent":"RESTAURANT_OPS_WASTAGE_TOP"}'
    ) == "t3_confidence_missing"
    assert ri._t3_contract_violation(
        '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":"high"}'
    ) == "t3_confidence_not_numeric"


def test_t3_gate_accepts_a_healthy_plan():
    assert ri._t3_contract_violation(
        '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":0.95}'
    ) is None


def test_t3_gate_accepts_markdown_fenced_json():
    assert ri._t3_contract_violation(
        '```json\n{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":0.98}\n```'
    ) is None


def test_t3_gate_does_not_retry_an_honestly_low_confidence():
    """0 <= conf < gate is the model saying "I am unsure", which becomes a
    clarification question. Treating that as a provider failure would burn the
    whole chain laundering an answer the model correctly flagged."""
    assert 0.59 < ri._T3_MIN_CONFIDENCE, "premise: 0.59 sits below the clarification gate"
    for conf in ("0.0", "0.1", "0.59"):
        assert ri._t3_contract_violation(
            '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":%s}' % conf
        ) is None, f"conf={conf} must not trigger a provider retry"


@pytest.mark.parametrize(
    "junk", ["", "   ", "not json", "[1,2,3]", '{"confidence":0.9}']
)
def test_t3_gate_leaves_shape_problems_to_the_router(junk):
    """Empty / unparseable / intent-less output is already handled by the router's
    `_validate_output` and the caller's fail-closed path. Double-rejecting here
    would only make the reason string misleading; raising would be worse."""
    assert ri._t3_contract_violation(junk) is None


@pytest.mark.asyncio
async def test_t3_parse_hands_the_gate_to_the_router(monkeypatch):
    """Wiring test -- pins the CALL SITE, not just the predicate.

    A green predicate test only proves the function computes the right answer; it
    does not prove anything calls it. Revert the `content_validator=` argument and
    every other test here still passes, while production regresses.
    """
    captured = {}

    async def fake_call_chain(slot, payload, **kwargs):
        captured.update(kwargs)
        return {
            "choices": [{"message": {
                "content": '{"intent":"RESTAURANT_OPS_WASTAGE_TOP","confidence":0.95}'
            }}]
        }

    from common import llm_router as lr
    monkeypatch.setattr(lr, "call_chain", fake_call_chain)

    parsed = await ri._t3_llm_parse(
        "上个月损耗金额最高的食材",
        hint=None,
        history=None,
        available_stores=("旗舰店",),
        prefer_high_accuracy=True,
    )

    assert parsed is not None and parsed["intent"] == "RESTAURANT_OPS_WASTAGE_TOP"
    assert captured.get("content_validator") is ri._t3_contract_violation, (
        "T3 must pass its contract gate to call_chain, otherwise a negative-confidence "
        "provider still terminates the cascade"
    )
