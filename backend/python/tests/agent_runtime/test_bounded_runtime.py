from __future__ import annotations

import asyncio
from datetime import datetime, timezone

import pytest

from smartbi.agent.runtime.bounded_runtime import BoundedRestaurantRuntime
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
from smartbi.agent.runtime.run_contracts import (
    AgentEventType,
    GrossMarginDeclineRequest,
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


class FakeGateway:
    def __init__(self, envelopes, *, exception=None):
        self.envelopes = {item.tool_name: item for item in envelopes}
        self.calls = []
        self.exception = exception

    async def execute(self, tool_name, parameters, context):
        self.calls.append((tool_name, dict(parameters), context))
        if self.exception is not None:
            raise self.exception
        return self.envelopes[tool_name]


class BlockingGateway(FakeGateway):
    def __init__(self, envelopes):
        super().__init__(envelopes)
        self.started = asyncio.Event()
        self.release = asyncio.Event()

    async def execute(self, tool_name, parameters, context):
        self.started.set()
        await self.release.wait()
        return await super().execute(tool_name, parameters, context)


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
    assert not result.outcome.attribution_supported
    assert "STORE_MARGIN_UNAVAILABLE" in result.outcome.blockers
    assert "DISH_MARGIN_UNAVAILABLE" in result.outcome.blockers
    assert all(claim.evidence_id and claim.fact_id for claim in result.outcome.claims)

    events = await store.events_for(result.run_id, context())
    assert [event.sequence for event in events] == list(range(1, len(events) + 1))
    assert events[-1].event_type is AgentEventType.RUN_COMPLETED
    assert "dimensions" not in str(events[-1].payload)


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
