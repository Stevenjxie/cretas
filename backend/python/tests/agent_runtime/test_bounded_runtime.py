from __future__ import annotations

import asyncio
import json
from dataclasses import replace
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from smartbi.agent.runtime.bounded_runtime import BoundedRestaurantRuntime, _bounded_drilldown
from smartbi.agent.runtime.contracts import (
    Coverage,
    DataClassification,
    EvidenceEnvelope,
    EvidenceFact,
    EvidenceLimits,
    EvidenceStatus,
    Freshness,
    ProvenanceReference,
    TrustedExecutionContext,
)
from smartbi.agent.runtime.evaluation import (
    TrajectoryExpectation,
    compare_legacy_shadow,
    evaluate_runtime,
    LegacyShadowSnapshot,
)
from smartbi.agent.runtime.gateway import ReadToolTimeout
from smartbi.agent.runtime.run_contracts import (
    AgentEventType,
    GrossMarginDeclineRequest,
    OutcomeStatus,
    RouteCode,
    RunState,
    RuntimeBudgets,
)
from smartbi.agent.runtime.run_store import InMemoryRunStore, RunStoreError


def context() -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id="A",
        business_type="RESTAURANT",
        user_id="actor",
        correlation_id="corr",
        authorized_classifications=frozenset({DataClassification.FINANCIAL_RESTRICTED}),
    )


def fact(metric, value, *, fact_id, dimensions=None, status=EvidenceStatus.OK):
    return EvidenceFact.numeric(
        fact_id=fact_id,
        metric=metric,
        value=value,
        unit="PERCENT" if "Margin" in metric or "margin" in metric else "CNY",
        scale=2,
        dimensions=dimensions or {},
        status=status,
        semantics="test evidence",
        provenance_refs=("ref",),
        freshness=Freshness.unknown("test"),
        coverage=Coverage.complete("test"),
    )


def envelope(tool_name, evidence_id, facts, *, status=EvidenceStatus.OK, byte_size=800):
    return EvidenceEnvelope(
        schema_version="1.0",
        evidence_id=evidence_id,
        tool_name=tool_name,
        tool_version="v1",
        descriptor_digest="sha256:test",
        tenant_id="A",
        business_type="RESTAURANT",
        correlation_id="corr",
        run_id="run",
        step_id="step",
        query_spec={},
        status=status,
        facts=tuple(facts),
        provenance=(ProvenanceReference("ref", "POSTGRES", "gold", "q", "v1"),),
        warnings=(),
        conflicts=(),
        classification=DataClassification.FINANCIAL_RESTRICTED,
        limits=EvidenceLimits(len(facts), 0, len(facts), len(facts), byte_size, 1),
        generated_at=datetime.now(timezone.utc).isoformat(),
    )


def route_evidence(*, error_first=False, dish_margin=False):
    period = envelope(
        "restaurant_period_comparison_read.v1",
        "period-ev",
        [
            fact(
                "gross_marginMomChange",
                "-4.5",
                fact_id="period-margin",
                dimensions={"comparison": "mom_pct"},
            ),
            fact(
                "revenue",
                "2000",
                fact_id="period-revenue",
                dimensions={"comparison": "current"},
            ),
        ],
        status=EvidenceStatus.ERROR if error_first else EvidenceStatus.OK,
    )
    store = envelope(
        "restaurant_store_performance_read.v1",
        "store-ev",
        [
            fact(
                "revenue",
                "1200",
                fact_id="store-revenue",
                dimensions={"store": "North"},
            )
        ],
        status=EvidenceStatus.PARTIAL,
    )
    margin_value = "22.5" if dish_margin else None
    dish = envelope(
        "restaurant_dish_margin_mix_read.v1",
        "dish-ev",
        [
            fact(
                "revenue",
                "600",
                fact_id="dish-revenue",
                dimensions={"dishName": "Noodles"},
            ),
            fact(
                "dishGrossMargin",
                margin_value,
                fact_id="dish-margin",
                dimensions={"dishName": "Noodles"},
                status=EvidenceStatus.OK
                if dish_margin
                else EvidenceStatus.NOT_COMPUTABLE,
            ),
        ],
        status=EvidenceStatus.PARTIAL,
    )
    return period, store, dish


def persisted_fact_references(events) -> set[tuple[str, str]]:
    return {
        (event.payload["evidenceId"], fact["factId"])
        for event in events
        if event.event_type is AgentEventType.EVIDENCE_RECORDED
        for fact in event.payload["factReferences"]
    }


class FakeGateway:
    def __init__(self, envelopes, *, exception=None):
        self.envelopes = {item.tool_name: item for item in envelopes}
        self.calls = []
        self.exception = exception

    async def execute(self, tool_name, parameters, context, **trusted_timeout):
        self.calls.append((tool_name, dict(parameters), context, trusted_timeout))
        if self.exception is not None:
            raise self.exception
        return self.envelopes[tool_name]


class BlockingGateway(FakeGateway):
    def __init__(self, envelopes):
        super().__init__(envelopes)
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def execute(self, tool_name, parameters, context, **trusted_timeout):
        self.started.set()
        await self.release.wait()
        return await super().execute(tool_name, parameters, context, **trusted_timeout)


class TimeoutGateway(FakeGateway):
    def __init__(self, envelopes, *, cancellation_requested=None):
        super().__init__(envelopes)
        self.cancellation_requested = cancellation_requested

    async def execute(self, tool_name, parameters, context, **trusted_timeout):
        self.calls.append((tool_name, dict(parameters), context, trusted_timeout))
        if self.cancellation_requested is not None:
            self.cancellation_requested.set()
        raise ReadToolTimeout("READ_TOOL_TIMEOUT")


class StepStartedClockStore(InMemoryRunStore):
    def __init__(self, clock, *, after_step_time=159.5, cancellation_requested=None):
        super().__init__()
        self.clock = clock
        self.after_step_time = after_step_time
        self.cancellation_requested = cancellation_requested

    async def append_event(self, *args, **kwargs):
        result = await super().append_event(*args, **kwargs)
        if args[2] is AgentEventType.STEP_STARTED:
            self.clock["value"] = self.after_step_time
            if self.cancellation_requested is not None:
                self.cancellation_requested.set()
        return result


class PeerCancelledStore(InMemoryRunStore):
    """Simulate another reconciler winning the final cancellation CAS."""

    async def compare_and_set_terminal(self, *args, **kwargs):
        if kwargs.get("terminal_state") is RunState.CANCELLED:
            changed = await super().compare_and_set_terminal(*args, **kwargs)
            assert changed
            return False
        return await super().compare_and_set_terminal(*args, **kwargs)


class CreateCountingStore(InMemoryRunStore):
    def __init__(self):
        super().__init__()
        self.create_calls = 0

    async def create_run(self, *args, **kwargs):
        self.create_calls += 1
        return await super().create_run(*args, **kwargs)


@pytest.mark.asyncio
async def test_runtime_claimed_run_skips_second_create_and_default_still_creates():
    route = RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION
    request = GrossMarginDeclineRequest("2026-01-01", "2026-01-31")
    claimed_store = CreateCountingStore()
    claimed = await claimed_store.claim_active_run(
        "41414141-4141-4141-8141-414141414141",
        context(),
        route,
        request.safe_dict(),
    )
    claimed_runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()), claimed_store
    )

    claimed_result = await claimed_runtime.execute(
        request,
        context(),
        run_id=claimed.record.run_id,
        already_created=True,
    )

    assert claimed_store.create_calls == 0
    assert claimed_result.run_id == claimed.record.run_id
    assert claimed_result.state.terminal

    default_store = CreateCountingStore()
    default_runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        default_store,
        id_factory=lambda: "42424242-4242-4242-8242-424242424242",
    )
    await default_runtime.execute(request, context())
    assert default_store.create_calls == 1


@pytest.mark.asyncio
async def test_runtime_claimed_run_validation_fails_without_mutating_wrong_run():
    store = InMemoryRunStore()
    route = RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION
    original = GrossMarginDeclineRequest("2026-01-01", "2026-01-31")
    claimed = await store.claim_active_run(
        "43434343-4343-4343-8343-434343434343",
        context(),
        route,
        original.safe_dict(),
    )
    runtime = BoundedRestaurantRuntime(FakeGateway(route_evidence()), store)

    with pytest.raises(RunStoreError, match="does not match"):
        await runtime.execute(
            GrossMarginDeclineRequest("2026-02-01", "2026-02-28"),
            context(),
            run_id=claimed.record.run_id,
            already_created=True,
        )
    with pytest.raises(ValueError, match="explicit run_id"):
        await runtime.execute(original, context(), already_created=True)

    record = await store.load_run(claimed.record.run_id, context())
    assert record.state is RunState.RUNNING
    assert await store.events_for(claimed.record.run_id, context()) == ()


@pytest.mark.asyncio
async def test_runtime_executes_period_store_dish_and_refuses_unsupported_attribution():
    store = InMemoryRunStore()
    gateway = FakeGateway(route_evidence())
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        id_factory=lambda: "44444444-4444-4444-4444-444444444444",
    )
    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )

    assert result.state is RunState.PARTIAL
    assert result.counters.rounds_used == 2
    assert result.counters.tool_calls_used == 3
    assert [call[0] for call in gateway.calls] == [
        "restaurant_period_comparison_read.v1",
        "restaurant_store_performance_read.v1",
        "restaurant_dish_margin_mix_read.v1",
    ]
    assert all(
        call[3] == {"timeout_seconds": 15.0, "cleanup_grace_seconds": 1.0}
        for call in gateway.calls
    )
    assert not result.outcome.attribution_supported
    assert "STORE_MARGIN_UNAVAILABLE" in result.outcome.blockers
    assert "DISH_MARGIN_UNAVAILABLE" in result.outcome.blockers
    assert all(claim.evidence_id and claim.fact_id for claim in result.outcome.claims)

    events = await store.events_for(result.run_id, context())
    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert events[-1].event_type is AgentEventType.RUN_COMPLETED
    assert "dimensions" not in str(events[-1].payload)
    assert AgentEventType.EVIDENCE_GAP in [event.event_type for event in events]
    assert AgentEventType.REPLAN in [event.event_type for event in events]
    assert AgentEventType.CLARIFICATION in [event.event_type for event in events]
    drilldowns = [
        event for event in events
        if event.event_type is AgentEventType.EVIDENCE_RECORDED
    ]
    assert drilldowns
    assert all(item.payload["factReferences"] for item in drilldowns)
    assert all(
        proposal.execution_mode == "READ_ONLY_PROPOSAL"
        for proposal in result.outcome.action_proposals
    )
    assert len(result.outcome.action_proposals) == 2
    durable_refs = persisted_fact_references(events)
    assert {
        (claim.evidence_id, claim.fact_id) for claim in result.outcome.claims
    }.issubset(durable_refs)
    assert all(
        (reference.evidence_id, reference.fact_id) in durable_refs
        for proposal in result.outcome.action_proposals
        for reference in proposal.evidence_references
    )


@pytest.mark.asyncio
async def test_evidence_drilldown_is_bounded_and_marks_deterministic_truncation():
    period, store_evidence, dish = route_evidence()
    oversized_decline = fact(
        "gross_marginMomChange",
        "-4.5",
        fact_id="period-margin",
        dimensions={f"dimension{i:02d}": "\u503c" * 400 for i in range(30)},
    )
    period = EvidenceEnvelope(
        **{
            **period.__dict__,
            "facts": (oversized_decline, period.facts[1]),
        }
    )
    store = InMemoryRunStore()
    runtime = BoundedRestaurantRuntime(
        FakeGateway((period, store_evidence, dish)),
        store,
        id_factory=lambda: "45454545-4545-4545-8545-454545454545",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )

    assert result.state is RunState.PARTIAL
    recorded = [
        event for event in await store.events_for(result.run_id, context())
        if event.event_type is AgentEventType.EVIDENCE_RECORDED
        and event.payload["evidenceId"] == "period-ev"
    ][0]
    assert recorded.payload["drilldownTruncated"] is True
    assert "DRILLDOWN_REFERENCE_TRUNCATED" in recorded.payload["warningCodes"]
    assert len(recorded.payload["factReferences"][0]["dimensions"]) == 10
    assert len(str(recorded.payload).encode("utf-8")) < 32_768
    durable_refs = persisted_fact_references(
        await store.events_for(result.run_id, context())
    )
    assert all(
        (claim.evidence_id, claim.fact_id) in durable_refs
        for claim in result.outcome.claims
    )


def test_drilldown_byte_budget_keeps_only_resolvable_provenance_refs():
    facts = []
    provenance = []
    for fact_index in range(12):
        refs = []
        for ref_index in range(10):
            ref_id = f"ref-{fact_index}-{ref_index}"
            refs.append(ref_id)
            provenance.append(SimpleNamespace(
                ref_id=ref_id,
                source_type="POSTGRES",
                asset="\u6765\u6e90" * 128,
                query_id=f"query-{fact_index}-{ref_index}",
                source_version="v" * 128,
            ))
        facts.append(SimpleNamespace(
            fact_id=f"fact-{fact_index}",
            metric="dishGrossMargin",
            value="12.34",
            unit="PERCENT",
            dimensions={f"dimension-{i}-" + "k" * 110: "值" * 256 for i in range(10)},
            provenance_refs=tuple(refs),
        ))

    bounded_facts, bounded_provenance, truncated = _bounded_drilldown(
        facts,
        provenance,
        evidence_id="dish-evidence",
        evidence_status="OK",
        warning_codes=[],
    )
    provenance_ids = {item["refId"] for item in bounded_provenance}
    assert truncated is True
    assert all(
        set(fact["provenanceRefs"]).issubset(provenance_ids)
        for fact in bounded_facts
    )
    payload = {
        "evidenceId": "dish-evidence",
        "evidenceStatus": "OK",
        "factReferences": bounded_facts,
        "provenance": bounded_provenance,
        "warningCodes": ["DRILLDOWN_REFERENCE_TRUNCATED"],
        "drilldownTruncated": True,
    }
    assert len(json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")) <= 32_768


@pytest.mark.asyncio
async def test_runtime_byte_truncation_prunes_terminal_claims_and_proposal_refs():
    period, store_evidence, dish = route_evidence(dish_margin=True)
    large_facts = []
    provenance = []
    fact_specs = (
        ("dish-margin-large", "dishGrossMargin", "22.5"),
        ("dish-revenue-large-1", "revenue", "600"),
        ("dish-revenue-large-2", "revenue", "500"),
        ("dish-revenue-large-3", "revenue", "400"),
    )
    for fact_index, (fact_id, metric, value) in enumerate(fact_specs):
        reference_ids = tuple(
            f"dish-large-{fact_index}-{reference_index}"
            for reference_index in range(10)
        )
        large_facts.append(replace(
            fact(metric, value, fact_id=fact_id),
            dimensions={
                f"dimension-{dimension_index:02d}": "\u6570\u636e" * 128
                for dimension_index in range(10)
            },
            provenance_refs=reference_ids,
        ))
        provenance.extend(
            ProvenanceReference(
                reference_id,
                "POSTGRES",
                "\u6765\u6e90" * 128,
                f"query{fact_index}{reference_id[-1]}" + "q" * 110,
                f"version{fact_index}{reference_id[-1]}" + "v" * 108,
            )
            for reference_id in reference_ids
        )
    dish = replace(dish, facts=tuple(large_facts), provenance=tuple(provenance))
    store = InMemoryRunStore()
    runtime = BoundedRestaurantRuntime(
        FakeGateway((period, store_evidence, dish)),
        store,
        id_factory=lambda: "48484848-4848-4848-8848-484848484848",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )
    events = await store.events_for(result.run_id, context())
    durable_refs = persisted_fact_references(events)
    evidence_events = [
        event for event in events
        if event.event_type is AgentEventType.EVIDENCE_RECORDED
    ]

    assert len(result.outcome.claims) < 7
    assert "PERSISTED_EVIDENCE_REFERENCE_TRUNCATED" in result.outcome.blockers
    assert {
        (claim.evidence_id, claim.fact_id) for claim in result.outcome.claims
    }.issubset(durable_refs)
    assert all(
        (reference.evidence_id, reference.fact_id) in durable_refs
        for proposal in result.outcome.action_proposals
        for reference in proposal.evidence_references
    )
    assert all(
        len(json.dumps(
            event.payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")) <= 32_768
        for event in evidence_events
    )


@pytest.mark.asyncio
async def test_missing_provenance_prunes_terminal_references_and_degrades_safely():
    evidence = tuple(
        EvidenceEnvelope(**{**item.__dict__, "provenance": ()})
        for item in route_evidence()
    )
    store = InMemoryRunStore()
    runtime = BoundedRestaurantRuntime(
        FakeGateway(evidence),
        store,
        id_factory=lambda: "47474747-4747-4747-8747-474747474747",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )
    events = await store.events_for(result.run_id, context())

    assert result.state is RunState.PARTIAL
    assert result.outcome.status is OutcomeStatus.NOT_COMPUTABLE
    assert result.outcome.claims == ()
    assert "PERSISTED_EVIDENCE_REFERENCE_TRUNCATED" in result.outcome.blockers
    assert persisted_fact_references(events) == set()
    assert all(
        proposal.evidence_references == ()
        for proposal in result.outcome.action_proposals
    )


@pytest.mark.asyncio
async def test_durable_cancel_request_from_another_caller_wins_after_inflight_read():
    store = PeerCancelledStore()
    gateway = BlockingGateway(route_evidence())
    run_id = "46464646-4646-4646-8646-464646464646"
    runtime = BoundedRestaurantRuntime(gateway, store)
    task = asyncio.create_task(
        runtime.execute(
            GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
            context(),
            run_id=run_id,
        )
    )
    await gateway.started.wait()
    cancellation = await store.request_cancel(run_id, context())
    assert cancellation.result.value == "REQUESTED"
    gateway.release.set()

    result = await task

    assert result.state is RunState.CANCELLED
    events = await store.events_for(run_id, context())
    assert [event.event_type for event in events if event.event_type in {
        AgentEventType.CANCEL_REQUESTED,
        AgentEventType.RUN_CANCELLED,
        AgentEventType.RUN_COMPLETED,
    }] == [AgentEventType.CANCEL_REQUESTED, AgentEventType.RUN_CANCELLED]


@pytest.mark.asyncio
async def test_runtime_uses_server_preallocated_uuid_without_calling_id_factory():
    store = InMemoryRunStore()
    expected = "10101010-1010-4010-8010-101010101010"
    runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        store,
        id_factory=lambda: (_ for _ in ()).throw(AssertionError("must not allocate")),
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
        context(),
        run_id=expected,
    )

    assert result.run_id == expected
    assert (await store.load_run(expected, context())).run_id == expected


@pytest.mark.asyncio
async def test_runtime_rejects_non_uuid_injected_run_id_before_persistence():
    store = InMemoryRunStore()
    runtime = BoundedRestaurantRuntime(FakeGateway(route_evidence()), store)

    with pytest.raises(ValueError, match="valid UUID"):
        await runtime.execute(
            GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
            context(),
            run_id="caller-selected-run-id",
        )

    assert store._runs == {}


@pytest.mark.asyncio
async def test_budget_and_cancel_have_durable_terminal_semantics():
    request = GrossMarginDeclineRequest("2026-01-01", "2026-01-31")

    budget_store = InMemoryRunStore()
    budget_runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        budget_store,
        budgets=RuntimeBudgets(max_tool_calls=2),
        id_factory=lambda: "55555555-5555-5555-5555-555555555555",
    )
    budget_result = await budget_runtime.execute(request, context())
    assert budget_result.state is RunState.BUDGET_EXCEEDED
    assert budget_result.failure_code == "TOOL_CALL_BUDGET_EXCEEDED"
    assert (await budget_store.events_for(budget_result.run_id, context()))[
        -1
    ].event_type is AgentEventType.BUDGET_EXCEEDED

    cancel_store = InMemoryRunStore()
    cancel_runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        cancel_store,
        id_factory=lambda: "66666666-6666-6666-6666-666666666666",
    )
    cancel_result = await cancel_runtime.execute(
        request, context(), cancelled=lambda: True
    )
    assert cancel_result.state is RunState.CANCELLED
    assert (await cancel_store.events_for(cancel_result.run_id, context()))[
        -1
    ].event_type is AgentEventType.RUN_CANCELLED


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("budgets", "expected_code", "expected_timeout"),
    [
        (RuntimeBudgets(), "READ_TOOL_TIMEOUT", 15.0),
        (
            RuntimeBudgets(
                wallclock_seconds=2.0,
                per_tool_timeout_seconds=15.0,
                timeout_cleanup_grace_seconds=0.5,
            ),
            "WALLCLOCK_BUDGET_EXCEEDED",
            2.0,
        ),
        (
            RuntimeBudgets(
                wallclock_seconds=15.0,
                per_tool_timeout_seconds=15.0,
                timeout_cleanup_grace_seconds=1.0,
            ),
            "WALLCLOCK_BUDGET_EXCEEDED",
            15.0,
        ),
    ],
)
async def test_read_timeout_is_durable_budget_terminal_and_counts_started_call_once(
    budgets, expected_code, expected_timeout
):
    store = InMemoryRunStore()
    gateway = TimeoutGateway(route_evidence())
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        budgets=budgets,
        monotonic=lambda: 100.0,
        id_factory=lambda: "90909090-9090-4090-8090-909090909090",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )

    assert result.state is RunState.BUDGET_EXCEEDED
    assert result.failure_code == expected_code
    assert result.evidence == ()
    assert result.counters.tool_calls_used == 1
    assert result.counters.rounds_used == 1
    assert gateway.calls[0][3] == {
        "timeout_seconds": expected_timeout,
        "cleanup_grace_seconds": budgets.timeout_cleanup_grace_seconds,
    }
    events = await store.events_for(result.run_id, context())
    assert [
        event.event_type
        for event in events
        if event.event_type is AgentEventType.STEP_FAILED
    ] == [AgentEventType.STEP_FAILED]
    assert not any(
        event.event_type is AgentEventType.STEP_COMPLETED for event in events
    )
    assert events[-2].payload == {"failureCode": expected_code}
    assert events[-1].event_type is AgentEventType.BUDGET_EXCEEDED
    assert events[-1].payload["failureCode"] == expected_code
    assert events[-1].payload["toolCallsUsed"] == 1
    assert (
        await store.load_run(result.run_id, context())
    ).counters.tool_calls_used == 1


@pytest.mark.asyncio
async def test_trusted_cancel_request_wins_when_read_timeout_cleanup_completes():
    store = InMemoryRunStore()
    requested = asyncio.Event()
    runtime = BoundedRestaurantRuntime(
        TimeoutGateway(route_evidence(), cancellation_requested=requested),
        store,
        id_factory=lambda: "91919191-9191-4191-8191-919191919191",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
        context(),
        cancelled=requested.is_set,
    )

    assert result.state is RunState.CANCELLED
    assert result.failure_code == "RUN_CANCELLED"
    assert result.evidence == ()
    assert result.counters.tool_calls_used == 1
    events = await store.events_for(result.run_id, context())
    assert not any(
        event.event_type in {AgentEventType.STEP_FAILED, AgentEventType.STEP_COMPLETED}
        for event in events
    )
    assert events[-1].event_type is AgentEventType.RUN_CANCELLED


@pytest.mark.asyncio
@pytest.mark.parametrize("after_step_time", [159.5, 158.9995])
async def test_step_started_persistence_time_is_deducted_before_gateway_admission(
    after_step_time,
):
    clock = {"value": 100.0}
    store = StepStartedClockStore(clock, after_step_time=after_step_time)
    gateway = FakeGateway(route_evidence())
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        budgets=RuntimeBudgets(
            wallclock_seconds=60.0,
            per_tool_timeout_seconds=15.0,
            timeout_cleanup_grace_seconds=1.0,
        ),
        monotonic=lambda: clock["value"],
        id_factory=lambda: "92929292-9292-4292-8292-929292929292",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )

    assert result.state is RunState.BUDGET_EXCEEDED
    assert result.failure_code == "WALLCLOCK_BUDGET_EXCEEDED"
    assert result.counters.tool_calls_used == 1
    assert gateway.calls == []
    events = await store.events_for(result.run_id, context())
    assert events[-2].event_type is AgentEventType.STEP_FAILED
    assert events[-2].payload == {"failureCode": "WALLCLOCK_BUDGET_EXCEEDED"}
    assert events[-1].event_type is AgentEventType.BUDGET_EXCEEDED


@pytest.mark.asyncio
async def test_cancel_wins_if_requested_while_step_started_event_is_persisted():
    clock = {"value": 100.0}
    requested = asyncio.Event()
    store = StepStartedClockStore(clock, cancellation_requested=requested)
    gateway = FakeGateway(route_evidence())
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        monotonic=lambda: clock["value"],
        id_factory=lambda: "93939393-9393-4393-8393-939393939393",
    )

    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
        context(),
        cancelled=requested.is_set,
    )

    assert result.state is RunState.CANCELLED
    assert result.counters.tool_calls_used == 1
    assert gateway.calls == []
    events = await store.events_for(result.run_id, context())
    assert not any(event.event_type is AgentEventType.STEP_FAILED for event in events)
    assert events[-1].event_type is AgentEventType.RUN_CANCELLED


@pytest.mark.asyncio
async def test_best_effort_cancel_waits_for_inflight_read_then_persists_terminal():
    store = InMemoryRunStore()
    gateway = BlockingGateway(route_evidence())
    cancellation_requested = asyncio.Event()
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        id_factory=lambda: "60606060-6060-4060-8060-606060606060",
    )
    task = asyncio.create_task(
        runtime.execute(
            GrossMarginDeclineRequest("2026-01-01", "2026-01-31"),
            context(),
            cancelled=cancellation_requested.is_set,
        )
    )

    await gateway.started.wait()
    cancellation_requested.set()
    assert not task.done()
    gateway.release.set()
    result = await task

    assert result.state is RunState.CANCELLED
    record = await store.load_run(result.run_id, context())
    assert record.state is RunState.CANCELLED
    assert (await store.events_for(result.run_id, context()))[
        -1
    ].event_type is AgentEventType.RUN_CANCELLED


@pytest.mark.asyncio
async def test_unexpected_gateway_exception_becomes_failed_without_exception_text_in_event():
    store = InMemoryRunStore()
    gateway = FakeGateway(
        route_evidence(), exception=RuntimeError("raw secret member review prompt")
    )
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        id_factory=lambda: "77777777-7777-7777-7777-777777777777",
    )
    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )

    assert result.state is RunState.FAILED
    assert result.failure_code == "READ_TOOL_EXECUTION_FAILED"
    record = await store.load_run(result.run_id, context())
    assert record.state is RunState.FAILED
    events = await store.events_for(result.run_id, context())
    assert events[-2].event_type is AgentEventType.STEP_FAILED
    assert events[-2].payload == {"failureCode": "READ_TOOL_EXECUTION_FAILED"}
    assert events[-1].event_type is AgentEventType.RUN_FAILED
    assert events[-1].payload == {"failureCode": "READ_TOOL_EXECUTION_FAILED"}
    assert "secret" not in str(events).lower()
    assert "prompt" not in str(events).lower()


class UnavailableStore(InMemoryRunStore):
    async def append_event(self, *args, **kwargs):
        raise RunStoreError("storage unavailable")

    async def compare_and_set_terminal(self, *args, **kwargs):
        raise RunStoreError("storage unavailable")


class StepFailureEventUnavailableStore(InMemoryRunStore):
    async def append_event(self, *args, **kwargs):
        if args[2] is AgentEventType.STEP_FAILED:
            raise RunStoreError("step event unavailable")
        return await super().append_event(*args, **kwargs)


class StepAndTerminalUnavailableStore(StepFailureEventUnavailableStore):
    async def compare_and_set_terminal(self, *args, **kwargs):
        raise RunStoreError("terminal store unavailable")


class EvidenceRecordedUnavailableStore(InMemoryRunStore):
    async def append_event(self, *args, **kwargs):
        if args[2] is AgentEventType.EVIDENCE_RECORDED:
            raise RunStoreError("raw secret member review prompt")
        return await super().append_event(*args, **kwargs)


@pytest.mark.asyncio
async def test_unexpected_runtime_logging_keeps_exception_text_out_of_log(caplog):
    store = EvidenceRecordedUnavailableStore()
    runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        store,
        id_factory=lambda: "78787878-7878-4878-8878-787878787878",
    )

    with caplog.at_level("ERROR"):
        result = await runtime.execute(
            GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
        )

    assert result.state is RunState.FAILED
    assert result.failure_code == "UNEXPECTED_RUNTIME_FAILURE"
    assert "restaurant agent runtime failed unexpectedly" in caplog.text
    assert "exception_class=RunStoreError" in caplog.text
    assert "secret" not in caplog.text.lower()
    assert "prompt" not in caplog.text.lower()
    runtime_records = [
        record
        for record in caplog.records
        if record.getMessage().startswith("restaurant agent runtime failed unexpectedly")
    ]
    assert len(runtime_records) == 1
    runtime_record = runtime_records[0]
    assert runtime_record.args == (
        "78787878-7878-4878-8878-787878787878",
        "A",
        "RunStoreError",
    )
    assert runtime_record.exc_info is None
    assert runtime_record.stack_info is None
    assert not any(
        key in runtime_record.__dict__
        for key in ("exception", "payload", "request", "outcome")
    )


@pytest.mark.asyncio
async def test_store_failure_is_raised_and_runtime_does_not_claim_terminal():
    store = UnavailableStore()
    runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence()),
        store,
        id_factory=lambda: "88888888-8888-8888-8888-888888888888",
    )
    with pytest.raises(RunStoreError, match="storage unavailable"):
        await runtime.execute(
            GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
        )
    record = await store.load_run("88888888-8888-8888-8888-888888888888", context())
    assert record.state is RunState.RUNNING


@pytest.mark.asyncio
async def test_step_failure_event_gap_is_explicit_or_raised_if_terminal_store_fails():
    request = GrossMarginDeclineRequest("2026-01-01", "2026-01-31")
    gateway = FakeGateway(route_evidence(), exception=RuntimeError("raw private text"))
    store = StepFailureEventUnavailableStore()
    runtime = BoundedRestaurantRuntime(
        gateway,
        store,
        id_factory=lambda: "89898989-8989-8989-8989-898989898989",
    )
    result = await runtime.execute(request, context())
    assert result.state is RunState.FAILED
    assert result.failure_code == "STEP_FAILURE_EVENT_PERSIST_FAILED"
    assert (await store.events_for(result.run_id, context()))[
        -1
    ].event_type is AgentEventType.RUN_FAILED

    unavailable = StepAndTerminalUnavailableStore()
    runtime = BoundedRestaurantRuntime(
        gateway,
        unavailable,
        id_factory=lambda: "89898989-8989-8989-8989-898989898990",
    )
    with pytest.raises(RunStoreError, match="terminal store unavailable"):
        await runtime.execute(request, context())
    assert (
        await unavailable.load_run("89898989-8989-8989-8989-898989898990", context())
    ).state is RunState.RUNNING


@pytest.mark.asyncio
async def test_offline_route_trajectory_numeric_truth_and_shadow_contract():
    runtime = BoundedRestaurantRuntime(
        FakeGateway(route_evidence(dish_margin=True)),
        InMemoryRunStore(),
        id_factory=lambda: "99999999-9999-9999-9999-999999999999",
    )
    result = await runtime.execute(
        GrossMarginDeclineRequest("2026-01-01", "2026-01-31"), context()
    )
    evaluation = evaluate_runtime(
        result,
        TrajectoryExpectation(
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            (
                "restaurant_period_comparison_read.v1",
                "restaurant_store_performance_read.v1",
                "restaurant_dish_margin_mix_read.v1",
            ),
        ),
    )
    assert evaluation.passed

    shadow = compare_legacy_shadow(
        result,
        LegacyShadowSnapshot("sha256:case", "LEGACY_OK", result.outcome.claims[:1]),
    )
    assert "gross_marginMomChange" in shadow.shared_metrics
    assert shadow.case_digest == "sha256:case"
