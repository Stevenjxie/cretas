from __future__ import annotations

import asyncio
from contextlib import AbstractAsyncContextManager

import pytest

from smartbi.agent.runtime.contracts import DataClassification, TrustedExecutionContext
from smartbi.agent.runtime.run_contracts import (
    AgentEventType,
    OutcomeStatus,
    RouteCode,
    RunState,
    RuntimeCounters,
    StructuredOutcome,
)
from smartbi.agent.runtime.run_store import (
    InMemoryRunStore,
    PostgresRunStore,
    RunAccessError,
    UnsafeRunPayloadError,
    safe_payload,
    RunStoreError,
)


def ctx(factory_id: str) -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id="actor",
        correlation_id="corr",
        authorized_classifications=frozenset({DataClassification.FINANCIAL_RESTRICTED}),
    )


def request():
    return {
        "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "startDate": "2026-01-01",
        "endDate": "2026-01-31",
        "storeTopN": 20,
        "dishTopN": 10,
    }


@pytest.mark.asyncio
async def test_in_memory_store_refuses_cross_tenant_and_terminal_mutation():
    store = InMemoryRunStore()
    run_id = "11111111-1111-1111-1111-111111111111"
    await store.create_run(
        run_id, ctx("A"), RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )

    with pytest.raises(RunAccessError):
        await store.load_run(run_id, ctx("B"))
    with pytest.raises(RunAccessError):
        await store.append_event(
            run_id,
            ctx("B"),
            AgentEventType.RUN_STARTED,
            {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
            counters=RuntimeCounters(),
        )

    event = await store.append_event(
        run_id,
        ctx("A"),
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    outcome = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        blockers=("EVIDENCE_GAP",),
    )
    assert event.sequence == 1
    assert await store.compare_and_set_terminal(
        run_id,
        ctx("A"),
        expected_state=RunState.RUNNING,
        terminal_state=RunState.PARTIAL,
        outcome=outcome,
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_COMPLETED,
    )
    assert not await store.compare_and_set_terminal(
        run_id,
        ctx("A"),
        expected_state=RunState.RUNNING,
        terminal_state=RunState.FAILED,
        outcome=StructuredOutcome(
            OutcomeStatus.FAILED,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            blockers=("SECOND_TERMINAL_ATTEMPT",),
        ),
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_FAILED,
        failure_code="SECOND_TERMINAL_ATTEMPT",
    )
    with pytest.raises(Exception, match="terminal"):
        await store.append_event(
            run_id,
            ctx("A"),
            AgentEventType.RUN_FAILED,
            {"failureCode": "LATE_EVENT"},
            counters=RuntimeCounters(),
        )


@pytest.mark.asyncio
async def test_in_memory_sequence_is_unique_and_monotonic_under_concurrency():
    store = InMemoryRunStore()
    run_id = "22222222-2222-2222-2222-222222222222"
    context = ctx("A")
    await store.create_run(
        run_id, context, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )

    events = await asyncio.gather(
        *(
            store.append_event(
                run_id,
                context,
                AgentEventType.STEP_STARTED,
                {"round": 1, "purposeCode": "CONCURRENCY_TEST"},
                counters=RuntimeCounters(),
            )
            for ordinal in range(25)
        )
    )
    assert sorted(event.sequence for event in events) == list(range(1, 26))


@pytest.mark.asyncio
async def test_generic_append_rejects_terminal_events_and_counter_regression():
    store = InMemoryRunStore()
    run_id = "23232323-2323-2323-2323-232323232323"
    context = ctx("A")
    await store.create_run(
        run_id, context, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await store.append_event(
        run_id,
        context,
        AgentEventType.STEP_STARTED,
        {"round": 1, "purposeCode": "COUNTER_TEST"},
        counters=RuntimeCounters(1, 1, 4, 500),
    )
    with pytest.raises(RunStoreError, match="may not decrease"):
        await store.append_event(
            run_id,
            context,
            AgentEventType.STEP_STARTED,
            {"round": 1, "purposeCode": "COUNTER_TEST"},
            counters=RuntimeCounters(1, 1, 3, 500),
        )
    with pytest.raises(RunStoreError, match="atomic terminal CAS"):
        await store.append_event(
            run_id,
            context,
            AgentEventType.RUN_COMPLETED,
            {
                "status": "PARTIAL",
                "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                "claims": [],
                "blockers": [],
                "observations": [],
                "attributionSupported": False,
            },
            counters=RuntimeCounters(1, 1, 4, 500),
        )
    mismatch = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
    )
    with pytest.raises(RunStoreError, match="must match"):
        await store.compare_and_set_terminal(
            run_id,
            context,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.FAILED,
            outcome=mismatch,
            counters=RuntimeCounters(1, 1, 4, 500),
            terminal_event_type=AgentEventType.RUN_COMPLETED,
            failure_code="CONTROLLED_FAILURE",
        )


@pytest.mark.parametrize(
    "payload",
    [
        {"message": "raw prompt hidden under a generic field"},
        {"rawPrompt": "private"},
        {"memberId": "private"},
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION", "startDate": "2026-01-01"},
    ],
)
def test_safe_payload_rejects_sensitive_keys_recursively(payload):
    with pytest.raises(UnsafeRunPayloadError):
        safe_payload(payload)


class _Ctx(AbstractAsyncContextManager):
    def __init__(self, value, log, label):
        self.value = value
        self.log = log
        self.label = label

    async def __aenter__(self):
        self.log.append((self.label, "enter"))
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        self.log.append((self.label, "exit"))


class FakeConnection:
    def __init__(self):
        self.log = []
        self.executes = []
        self.fetchrows = []

    def transaction(self, *, readonly=False):
        self.log.append(("transaction", "created", readonly))
        return _Ctx(self, self.log, "transaction")

    async def execute(self, sql, *args):
        self.executes.append((sql, args))
        return "INSERT 0 1"

    async def fetchrow(self, sql, *args):
        self.fetchrows.append((sql, args))
        if "UPDATE smart_bi_agent_run" in sql:
            return {"next_event_sequence": 1}
        raise AssertionError("unexpected fetchrow")


class FakePool:
    def __init__(self):
        self.connection = FakeConnection()
        self.log = []

    def acquire(self):
        return _Ctx(self.connection, self.log, "pool")


@pytest.mark.asyncio
async def test_postgres_append_binds_rls_update_and_insert_on_one_connection_transaction():
    pool = FakePool()
    store = PostgresRunStore(pool)
    event = await store.append_event(
        "33333333-3333-3333-3333-333333333333",
        ctx("A"),
        AgentEventType.STEP_COMPLETED,
        {
            "round": 1,
            "evidenceId": "ev",
            "evidenceStatus": "OK",
            "factCount": 2,
            "evidenceBytes": 300,
            "warningCodes": [],
        },
        counters=RuntimeCounters(1, 1, 2, 300),
    )

    assert event.sequence == 1
    assert pool.log == [("pool", "enter"), ("pool", "exit")]
    assert pool.connection.log[0] == ("transaction", "created", False)
    assert pool.connection.executes[0] == (
        "SELECT set_config('app.factory_id', $1, true)",
        ("A",),
    )
    assert "INSERT INTO smart_bi_agent_event" in pool.connection.executes[1][0]
    assert "factory_id = $2 AND state = 'RUNNING'" in pool.connection.fetchrows[0][0]
