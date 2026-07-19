from __future__ import annotations

import math
from datetime import datetime, timezone

import pytest

from smartbi.agent.runtime.contracts import (
    Coverage,
    DataClassification,
    EvidenceEnvelope,
    EvidenceFact,
    EvidenceLimits,
    EvidenceStatus,
    Freshness,
    ProvenanceReference,
)
from smartbi.agent.runtime.numeric_truth import NumericTruthError, NumericTruthGuard
from smartbi.agent.runtime.routes import (
    gross_margin_decline_plan,
    gross_margin_decline_replan,
)
from smartbi.agent.runtime.run_contracts import (
    GrossMarginDeclineRequest,
    NumericClaim,
    OutcomeStatus,
    RouteCode,
    RuntimeBudgets,
    StructuredOutcome,
)


def envelope() -> EvidenceEnvelope:
    ref = ProvenanceReference("ref-1", "POSTGRES", "agg_daily", "q1", "v1")
    fact = EvidenceFact.numeric(
        fact_id="fact-1",
        metric="gross_marginMomChange",
        value="-3.25",
        unit="PERCENT",
        scale=2,
        dimensions={"comparison": "mom_pct"},
        status=EvidenceStatus.OK,
        semantics="deterministic period comparison",
        provenance_refs=(ref.ref_id,),
        freshness=Freshness.unknown("test"),
        coverage=Coverage.complete("test"),
    )
    return EvidenceEnvelope(
        schema_version="1.0",
        evidence_id="ev-1",
        tool_name="restaurant_period_comparison_read.v1",
        tool_version="v1",
        descriptor_digest="sha256:test",
        tenant_id="A",
        business_type="RESTAURANT",
        correlation_id="corr",
        run_id="run",
        step_id="step",
        query_spec={},
        status=EvidenceStatus.OK,
        facts=(fact,),
        provenance=(ref,),
        warnings=(),
        conflicts=(),
        classification=DataClassification.FINANCIAL_RESTRICTED,
        limits=EvidenceLimits(1, 0, 1, 2, 700, 1),
        generated_at=datetime.now(timezone.utc).isoformat(),
    )


def test_route_is_typed_ordered_and_bounded_to_two_rounds():
    plan = gross_margin_decline_plan(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31")
    )

    assert plan.route_code is RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION
    assert [step.round_number for step in plan.steps] == [1, 1]
    assert [step.tool_name for step in plan.steps] == [
        "restaurant_period_comparison_read.v1",
        "restaurant_store_performance_read.v1",
    ]
    replan = gross_margin_decline_replan(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31")
    )
    assert [step.round_number for step in replan.steps] == [2]
    assert [step.tool_name for step in replan.steps] == [
        "restaurant_dish_margin_mix_read.v1",
    ]
    assert all(
        "factoryId" not in step.parameters for step in (*plan.steps, *replan.steps)
    )


@pytest.mark.parametrize(
    "kwargs",
    [
        {"max_rounds": 3},
        {"max_tool_calls": 11},
        {"max_facts": 0},
        {"max_evidence_bytes": 0},
        {"wallclock_seconds": True},
        {"wallclock_seconds": math.inf},
        {"per_tool_timeout_seconds": True},
        {"per_tool_timeout_seconds": math.nan},
        {"timeout_cleanup_grace_seconds": True},
        {"timeout_cleanup_grace_seconds": 0},
        {"per_tool_timeout_seconds": 1.0, "timeout_cleanup_grace_seconds": 1.0},
        {
            "per_tool_timeout_seconds": 1.0005,
            "timeout_cleanup_grace_seconds": 1.0,
        },
    ],
)
def test_runtime_budget_hard_ceilings(kwargs):
    with pytest.raises(ValueError):
        RuntimeBudgets(**kwargs)


def test_runtime_budget_timeout_defaults_are_explicit_and_bounded():
    budgets = RuntimeBudgets()

    assert budgets.per_tool_timeout_seconds == 15.0
    assert budgets.timeout_cleanup_grace_seconds == 1.0
    assert 0 < budgets.timeout_cleanup_grace_seconds < budgets.per_tool_timeout_seconds


def test_numeric_claim_requires_exact_evidence_and_fact_match():
    evidence = envelope()
    claim = NumericClaim(
        statement_code="GROSS_MARGIN_DECLINE_OBSERVED",
        metric="gross_marginMomChange",
        value="-3.25",
        unit="PERCENT",
        evidence_id="ev-1",
        fact_id="fact-1",
        dimensions={"comparison": "mom_pct"},
    )
    outcome = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        claims=(claim,),
        blockers=("STORE_MARGIN_UNAVAILABLE",),
    )

    NumericTruthGuard.validate_outcome(outcome, (evidence,))

    wrong = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        claims=(NumericClaim(**{**claim.__dict__, "value": "-3.24"}),),
    )
    with pytest.raises(NumericTruthError, match="exactly match"):
        NumericTruthGuard.validate_outcome(wrong, (evidence,))


def test_numeric_guard_rejects_unknown_fact_and_isolated_narrative_number():
    evidence = envelope()
    bad_ref = NumericClaim(
        statement_code="GROSS_MARGIN_DECLINE_OBSERVED",
        metric="gross_marginMomChange",
        value="-3.25",
        unit="PERCENT",
        evidence_id="ev-1",
        fact_id="missing",
        dimensions={"comparison": "mom_pct"},
    )
    with pytest.raises(NumericTruthError, match="unknown"):
        NumericTruthGuard.validate_claims((bad_ref,), (evidence,))

    isolated = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        observations=("margin fell 3.25 percent",),
    )
    with pytest.raises(NumericTruthError, match="isolated numeric"):
        NumericTruthGuard.validate_outcome(isolated, (evidence,))
