from __future__ import annotations

import asyncio
import inspect
import json
from contextlib import AbstractAsyncContextManager
from datetime import datetime, timedelta, timezone

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
    CancelRequestResult,
    InMemoryRunStore,
    PostgresRunStore,
    RunAccessError,
    STALE_AFTER_SECONDS,
    STALE_RUN_FAILURE_CODE,
    StaleRunReconcileResult,
    UnsafeRunPayloadError,
    safe_payload,
    RunStoreError,
)


class MutableClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 19, 12, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += timedelta(seconds=seconds)


class FailingClock(MutableClock):
    def __init__(self) -> None:
        super().__init__()
        self.fail_next = False

    def __call__(self) -> datetime:
        if self.fail_next:
            self.fail_next = False
            raise RuntimeError("clock unavailable")
        return self.value


def ctx(factory_id: str, user_id: str = "actor") -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id=user_id,
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
        await store.load_run(run_id, ctx("A", "other-actor"))
    with pytest.raises(RunAccessError):
        await store.request_cancel(run_id, ctx("A", "other-actor"))
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
async def test_cancel_request_is_durable_idempotent_tenant_bound_and_blocks_completion():
    store = InMemoryRunStore()
    run_id = "12121212-1212-4212-8212-121212121212"
    tenant = ctx("A")
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )

    requested = await store.request_cancel(run_id, tenant)
    repeated = await store.request_cancel(run_id, tenant)

    assert requested.result is CancelRequestResult.REQUESTED
    assert repeated.result is CancelRequestResult.ALREADY_REQUESTED
    assert await store.is_cancellation_requested(run_id, tenant)
    with pytest.raises(RunAccessError):
        await store.request_cancel(run_id, ctx("B"))
    completed = await store.compare_and_set_terminal(
        run_id,
        tenant,
        expected_state=RunState.RUNNING,
        terminal_state=RunState.PARTIAL,
        outcome=StructuredOutcome(
            OutcomeStatus.PARTIAL,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        ),
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_COMPLETED,
    )
    assert completed is False
    cancelled = await store.compare_and_set_terminal(
        run_id,
        tenant,
        expected_state=RunState.RUNNING,
        terminal_state=RunState.CANCELLED,
        outcome=StructuredOutcome(
            OutcomeStatus.CANCELLED,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            blockers=("RUN_CANCELLED_BY_TRUSTED_CALLER",),
        ),
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_CANCELLED,
        failure_code="RUN_CANCELLED",
    )
    assert cancelled is True
    assert (await store.request_cancel(run_id, tenant)).result is CancelRequestResult.ALREADY_TERMINAL
    assert [event.event_type for event in await store.events_for(run_id, tenant)] == [
        AgentEventType.CANCEL_REQUESTED,
        AgentEventType.RUN_CANCELLED,
    ]


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
    replay = await store.events_for(run_id, context, after_sequence=20)
    assert [event.sequence for event in replay] == [21, 22, 23, 24, 25]

    with pytest.raises(ValueError, match="non-negative"):
        await store.events_for(run_id, context, after_sequence=-1)


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


@pytest.mark.asyncio
async def test_in_memory_stale_reconciliation_boundary_preserves_ledger_and_is_unique():
    clock = MutableClock()
    store = InMemoryRunStore(clock=clock)
    run_id = "24242424-2424-4242-8242-242424242424"
    tenant = ctx("A")
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    counters = RuntimeCounters(1, 2, 17, 4096)
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.STEP_COMPLETED,
        {
            "round": 1,
            "evidenceId": "ev-stale",
            "evidenceStatus": "OK",
            "factCount": 17,
            "evidenceBytes": 4096,
            "warningCodes": [],
        },
        counters=counters,
    )
    clock.advance(STALE_AFTER_SECONDS)

    reconciled = await store.reconcile_stale_run(run_id, tenant)

    assert reconciled.result is StaleRunReconcileResult.RECONCILED
    assert reconciled.record.state is RunState.FAILED
    assert reconciled.record.failure_code == STALE_RUN_FAILURE_CODE
    assert reconciled.record.counters == counters
    assert reconciled.record.outcome_summary == {
        "status": "FAILED",
        "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "claims": [],
            "blockers": [STALE_RUN_FAILURE_CODE],
            "observations": [],
            "actionProposals": [],
            "attributionSupported": False,
    }
    events = await store.events_for(run_id, tenant)
    assert [event.event_type for event in events] == [
        AgentEventType.STEP_COMPLETED,
        AgentEventType.RUN_FAILED,
    ]
    assert events[-1].payload == {"failureCode": STALE_RUN_FAILURE_CODE}
    repeated = await store.reconcile_stale_run(run_id, tenant)
    assert repeated.result is StaleRunReconcileResult.ALREADY_TERMINAL
    assert len(await store.events_for(run_id, tenant)) == 2


@pytest.mark.asyncio
async def test_in_memory_reconciliation_fresh_refresh_and_access_are_fail_closed():
    clock = MutableClock()
    store = InMemoryRunStore(clock=clock)
    run_id = "25252525-2525-4252-8252-252525252525"
    tenant = ctx("A")
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    clock.advance(STALE_AFTER_SECONDS - 1)
    fresh = await store.reconcile_stale_run(run_id, tenant)
    assert fresh.result is StaleRunReconcileResult.NOT_STALE
    assert await store.events_for(run_id, tenant) == ()

    await store.append_event(
        run_id,
        tenant,
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    clock.advance(STALE_AFTER_SECONDS - 1)
    assert (
        await store.reconcile_stale_run(run_id, tenant)
    ).result is StaleRunReconcileResult.NOT_STALE

    for inaccessible_id, inaccessible_context in (
        (run_id, ctx("B")),
        ("26262626-2626-4262-8262-262626262626", tenant),
    ):
        with pytest.raises(RunAccessError, match="trusted tenant"):
            await store.reconcile_stale_run(inaccessible_id, inaccessible_context)


@pytest.mark.asyncio
async def test_in_memory_two_reconcilers_have_exactly_one_winner():
    clock = MutableClock()
    store = InMemoryRunStore(clock=clock)
    run_id = "27272727-2727-4272-8272-272727272727"
    tenant = ctx("A")
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    clock.advance(STALE_AFTER_SECONDS)

    results = await asyncio.gather(
        store.reconcile_stale_run(run_id, tenant),
        store.reconcile_stale_run(run_id, tenant),
    )

    assert sorted(result.result.value for result in results) == [
        "ALREADY_TERMINAL",
        "RECONCILED",
    ]
    assert len(await store.events_for(run_id, tenant)) == 1


@pytest.mark.asyncio
async def test_in_memory_append_or_terminal_race_with_reconcile_stays_atomic():
    for race_kind in ("append", "terminal"):
        clock = MutableClock()
        store = InMemoryRunStore(clock=clock)
        run_id = str(
            "28282828-2828-4282-8282-282828282828"
            if race_kind == "append"
            else "29292929-2929-4292-8292-292929292929"
        )
        tenant = ctx("A")
        await store.create_run(
            run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
        )
        clock.advance(STALE_AFTER_SECONDS)
        if race_kind == "append":
            competitor = store.append_event(
                run_id,
                tenant,
                AgentEventType.RUN_STARTED,
                {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
                counters=RuntimeCounters(),
            )
        else:
            competitor = store.compare_and_set_terminal(
                run_id,
                tenant,
                expected_state=RunState.RUNNING,
                terminal_state=RunState.PARTIAL,
                outcome=StructuredOutcome(
                    OutcomeStatus.PARTIAL,
                    RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
                ),
                counters=RuntimeCounters(),
                terminal_event_type=AgentEventType.RUN_COMPLETED,
            )
        results = await asyncio.gather(
            store.reconcile_stale_run(run_id, tenant),
            competitor,
            return_exceptions=True,
        )
        record = await store.load_run(run_id, tenant)
        terminal_events = [
            event
            for event in await store.events_for(run_id, tenant)
            if event.event_type
            in {AgentEventType.RUN_FAILED, AgentEventType.RUN_COMPLETED}
        ]
        assert len(terminal_events) <= 1
        if record.state is RunState.RUNNING:
            assert race_kind == "append"
            assert results[0].result is StaleRunReconcileResult.NOT_STALE
        else:
            assert len(terminal_events) == 1


@pytest.mark.asyncio
async def test_in_memory_clock_failure_never_partially_mutates_state():
    clock = FailingClock()
    store = InMemoryRunStore(clock=clock)
    tenant = ctx("A")
    create_run_id = "30303030-3030-4303-8303-303030303030"
    clock.fail_next = True
    with pytest.raises(RuntimeError, match="clock unavailable"):
        await store.create_run(
            create_run_id,
            tenant,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            request(),
        )
    # The same id remains creatable, proving no orphan run/event map was left.
    await store.create_run(
        create_run_id,
        tenant,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        request(),
    )

    clock.fail_next = True
    with pytest.raises(RuntimeError, match="clock unavailable"):
        await store.append_event(
            create_run_id,
            tenant,
            AgentEventType.RUN_STARTED,
            {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
            counters=RuntimeCounters(),
        )
    assert await store.events_for(create_run_id, tenant) == ()
    assert (await store.load_run(create_run_id, tenant)).next_event_sequence == 0

    clock.fail_next = True
    with pytest.raises(RuntimeError, match="clock unavailable"):
        await store.compare_and_set_terminal(
            create_run_id,
            tenant,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.FAILED,
            outcome=StructuredOutcome(
                OutcomeStatus.FAILED,
                RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
                blockers=("CONTROLLED_FAILURE",),
            ),
            counters=RuntimeCounters(),
            terminal_event_type=AgentEventType.RUN_FAILED,
            failure_code="CONTROLLED_FAILURE",
        )
    assert (await store.load_run(create_run_id, tenant)).state is RunState.RUNNING
    assert await store.events_for(create_run_id, tenant) == ()

    clock.advance(STALE_AFTER_SECONDS)
    clock.fail_next = True
    with pytest.raises(RuntimeError, match="clock unavailable"):
        await store.reconcile_stale_run(create_run_id, tenant)
    assert (await store.load_run(create_run_id, tenant)).state is RunState.RUNNING
    assert await store.events_for(create_run_id, tenant) == ()


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
        self.fetchvals = []
        self.fetches = []

    def transaction(self, *, readonly=False):
        self.log.append(("transaction", "created", readonly))
        return _Ctx(self, self.log, "transaction")

    async def execute(self, sql, *args):
        self.executes.append((sql, args))
        return "INSERT 0 1"

    async def fetchrow(self, sql, *args):
        self.fetchrows.append((sql, args))
        if "SELECT version" in sql and "FOR UPDATE" in sql:
            return {"version": 0}
        if "UPDATE smart_bi_agent_run" in sql:
            return {"next_event_sequence": 1}
        if "SELECT run_id FROM smart_bi_agent_run" in sql:
            return {"run_id": args[0]}
        raise AssertionError("unexpected fetchrow")

    async def fetchval(self, sql, *args):
        self.fetchvals.append((sql, args))
        if "clock_timestamp()" in sql:
            return datetime(2026, 7, 19, 12, 0, tzinfo=timezone.utc)
        raise AssertionError("unexpected fetchval")

    async def fetch(self, sql, *args):
        self.fetches.append((sql, args))
        return [
            {
                "run_id": args[0],
                "factory_id": args[1],
                "event_sequence": 3,
                "event_type": "STEP_COMPLETED",
                "payload": '{"evidenceBytes":300,"evidenceId":"ev","evidenceStatus":"OK","factCount":2,"round":1,"warningCodes":[]}',
                "step_id": "step-1",
                "tool_name": "restaurant_period_comparison_read.v1",
            }
        ]


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
    assert pool.connection.executes[1] == (
        "SELECT set_config('app.user_id', $1, true)",
        ("actor",),
    )
    assert "INSERT INTO smart_bi_agent_event" in pool.connection.executes[2][0]
    assert "factory_id = $2" in pool.connection.fetchrows[0][0]
    assert "state = 'RUNNING'" in pool.connection.fetchrows[0][0]
    assert pool.connection.fetchvals == [("SELECT clock_timestamp()", ())]


@pytest.mark.asyncio
async def test_postgres_events_replay_proves_owner_and_binds_after_sequence():
    pool = FakePool()
    store = PostgresRunStore(pool)

    events = await store.events_for(
        "33333333-3333-3333-3333-333333333333",
        ctx("A"),
        after_sequence=2,
    )

    assert [event.sequence for event in events] == [3]
    assert events[0].event_type is AgentEventType.STEP_COMPLETED
    assert events[0].payload["evidenceId"] == "ev"
    assert pool.connection.executes[0] == (
        "SELECT set_config('app.factory_id', $1, true)",
        ("A",),
    )
    assert pool.connection.fetchrows[0][1] == (
        "33333333-3333-3333-3333-333333333333",
        "A",
    )
    assert pool.connection.fetches[0][1] == (
        "33333333-3333-3333-3333-333333333333",
        "A",
        2,
    )


def _persisted_run_row():
    return {
        "run_id": "33333333-3333-3333-3333-333333333333",
        "factory_id": "A",
        "owner_user_id": "actor",
        "state": "RUNNING",
        "route_code": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "sanitized_request": json.dumps(request()),
        "rounds_used": 0,
        "tool_calls_used": 0,
        "facts_used": 0,
        "evidence_bytes_used": 0,
        "next_event_sequence": 0,
        "outcome_summary": None,
        "failure_code": None,
    }


def _persisted_event_row():
    return {
        "run_id": "33333333-3333-3333-3333-333333333333",
        "factory_id": "A",
        "event_sequence": 1,
        "event_type": "RUN_STARTED",
        "payload": json.dumps({"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"}),
        "step_id": None,
        "tool_name": None,
    }


def test_postgres_record_revalidates_decoded_request_outcome_and_failure_code():
    valid = _persisted_run_row()
    assert PostgresRunStore._record(valid).outcome_summary is None

    legacy = _persisted_run_row()
    legacy["state"] = "PARTIAL"
    legacy["outcome_summary"] = json.dumps(
        {
            "status": "PARTIAL",
            "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            "claims": [],
            "blockers": ["LEGACY_EVIDENCE_GAP"],
            "observations": [],
            "attributionSupported": False,
        }
    )
    assert PostgresRunStore._record(legacy).outcome_summary["actionProposals"] == []

    tampered_request = _persisted_run_row()
    tampered_request["sanitized_request"] = json.dumps(
        {**request(), "rawPrompt": "must-not-return"}
    )
    with pytest.raises(UnsafeRunPayloadError, match="exact keys"):
        PostgresRunStore._record(tampered_request)

    tampered_outcome = _persisted_run_row()
    tampered_outcome["state"] = "PARTIAL"
    tampered_outcome["outcome_summary"] = json.dumps(
        {
            **StructuredOutcome(
                OutcomeStatus.PARTIAL,
                RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            ).persistence_dict(),
            "rawNarrative": "must-not-return",
        }
    )
    with pytest.raises(UnsafeRunPayloadError, match="exact keys"):
        PostgresRunStore._record(tampered_outcome)

    tampered_failure = _persisted_run_row()
    tampered_failure["failure_code"] = "bad failure code"
    with pytest.raises(UnsafeRunPayloadError, match="controlled code"):
        PostgresRunStore._record(tampered_failure)


def test_postgres_event_revalidates_decoded_payload():
    row = _persisted_event_row()
    row["payload"] = json.dumps(
        {
            "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            "rawPrompt": "must-not-return",
        }
    )

    with pytest.raises(UnsafeRunPayloadError, match="exact keys"):
        PostgresRunStore._event(row)


def test_postgres_liveness_writes_never_use_transaction_start_time_now():
    source = inspect.getsource(PostgresRunStore)
    assert "NOW()" not in source.upper()
    assert source.count("clock_timestamp()") >= 4


@pytest.mark.parametrize(
    ("field", "value"),
    [("step_id", "bad step"), ("tool_name", "bad/tool")],
)
def test_postgres_event_revalidates_step_and_tool_identifiers(field, value):
    row = _persisted_event_row()
    row[field] = value

    with pytest.raises(UnsafeRunPayloadError, match="bounded identifier"):
        PostgresRunStore._event(row)
