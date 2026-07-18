"""Real PostgreSQL security/concurrency gate for the agent run ledger.

This test is intentionally opt-in and must only target a disposable local DB::

    AGENT_RUNTIME_PG_DSN=postgresql://postgres:<password>@127.0.0.1:55432/postgres \
      python -m pytest tests/agent_runtime/test_postgres_run_store_integration.py -q

The fixture bootstraps ``smartbi_user``, applies the exact migration and then
connects as the non-owner application role so FORCE RLS is genuinely exercised.
"""

from __future__ import annotations

import asyncio
import json
import os
import secrets
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit, urlunsplit

import pytest
import pytest_asyncio

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
    PostgresRunStore,
    RunAccessError,
    RunStoreError,
)


asyncpg = pytest.importorskip("asyncpg")


PG_DSN = os.environ.get("AGENT_RUNTIME_PG_DSN")
pytestmark = pytest.mark.skipif(
    not PG_DSN,
    reason="AGENT_RUNTIME_PG_DSN unset; real disposable PostgreSQL gate skipped",
)
MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_01__smart_bi_agent_run_event.sql"
)
APP_PASSWORD = secrets.token_urlsafe(24)


def context(factory_id: str) -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id=None,
        correlation_id="integration-corr",
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


def app_dsn(admin_dsn: str) -> str:
    parsed = urlsplit(admin_dsn)
    host = parsed.hostname or "127.0.0.1"
    port = f":{parsed.port}" if parsed.port else ""
    netloc = f"smartbi_user:{quote(APP_PASSWORD)}@{host}{port}"
    return urlunsplit(
        (parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment)
    )


@pytest_asyncio.fixture
async def pg_runtime():
    parsed = urlsplit(PG_DSN)
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        pytest.fail("AGENT_RUNTIME_PG_DSN must point to a disposable local PostgreSQL")
    admin = await asyncpg.connect(PG_DSN)
    await admin.execute(
        f"""
        DO $$
        BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartbi_user') THEN
                CREATE ROLE smartbi_user LOGIN PASSWORD '{APP_PASSWORD}';
            ELSE
                ALTER ROLE smartbi_user LOGIN PASSWORD '{APP_PASSWORD}';
            END IF;
        END
        $$;
        GRANT USAGE ON SCHEMA public TO smartbi_user;
        """
    )
    await admin.execute(MIGRATION.read_text(encoding="utf-8"))
    await admin.execute(
        "TRUNCATE smart_bi_agent_event, smart_bi_agent_run RESTART IDENTITY CASCADE"
    )
    pool = await asyncpg.create_pool(app_dsn(PG_DSN), min_size=2, max_size=20)
    try:
        yield admin, pool, PostgresRunStore(pool)
    finally:
        await pool.close()
        await admin.close()


@pytest.mark.asyncio
async def test_force_rls_no_tenant_and_cross_tenant_read_write(pg_runtime):
    admin, pool, store = pg_runtime
    flags = await admin.fetch(
        """
        SELECT relname, relrowsecurity, relforcerowsecurity
        FROM pg_class
        WHERE relname IN ('smart_bi_agent_run', 'smart_bi_agent_event')
        ORDER BY relname
        """
    )
    assert len(flags) == 2
    assert all(row["relrowsecurity"] and row["relforcerowsecurity"] for row in flags)

    async with pool.acquire() as connection:
        assert await connection.fetchval("SELECT COUNT(*) FROM smart_bi_agent_run") == 0
        with pytest.raises(asyncpg.InsufficientPrivilegeError):
            await connection.execute(
                """
                INSERT INTO smart_bi_agent_run (
                    run_id, factory_id, business_type, correlation_id,
                    route_code, sanitized_request
                ) VALUES (
                    'a1000000-0000-0000-0000-000000000001', 'A', 'RESTAURANT',
                    'corr', 'GROSS_MARGIN_DECLINE_ATTRIBUTION',
                    '{"routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION","startDate":"2026-01-01","endDate":"2026-01-31","storeTopN":20,"dishTopN":10}'::jsonb
                )
                """
            )

    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, context("A"), RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    with pytest.raises(RunAccessError):
        await store.load_run(run_id, context("B"))

    async with pool.acquire() as connection:
        async with connection.transaction():
            await connection.execute("SELECT set_config('app.factory_id', 'B', true)")
            assert (
                await connection.fetchval(
                    "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid",
                    run_id,
                )
                == 0
            )
            assert (
                await connection.execute(
                    "UPDATE smart_bi_agent_run SET updated_at=NOW(), version=version+1 WHERE run_id=$1::uuid",
                    run_id,
                )
                == "UPDATE 0"
            )
            with pytest.raises(
                (asyncpg.InsufficientPrivilegeError, asyncpg.RaiseError)
            ):
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id, factory_id, event_sequence, event_type, payload
                    ) VALUES ($1::uuid, 'A', 1, 'RUN_STARTED', '{}'::jsonb)
                    """,
                    run_id,
                )


@pytest.mark.asyncio
async def test_append_only_terminal_cas_and_terminal_event_are_atomic(pg_runtime):
    admin, pool, store = pg_runtime
    run_id = str(uuid.uuid4())
    tenant = context("A")
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    with pytest.raises(RunStoreError, match="atomic terminal CAS"):
        await store.append_event(
            run_id,
            tenant,
            AgentEventType.RUN_FAILED,
            {"failureCode": "FAKE_TERMINAL"},
            counters=RuntimeCounters(),
        )
    outcome = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        blockers=("STORE_MARGIN_UNAVAILABLE",),
    )
    assert await store.compare_and_set_terminal(
        run_id,
        tenant,
        expected_state=RunState.RUNNING,
        terminal_state=RunState.PARTIAL,
        outcome=outcome,
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_COMPLETED,
    )
    assert not await store.compare_and_set_terminal(
        run_id,
        tenant,
        expected_state=RunState.RUNNING,
        terminal_state=RunState.FAILED,
        outcome=StructuredOutcome(
            OutcomeStatus.FAILED,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            blockers=("LATE_FAILURE",),
        ),
        counters=RuntimeCounters(),
        terminal_event_type=AgentEventType.RUN_FAILED,
        failure_code="LATE_FAILURE",
    )
    record = await store.load_run(run_id, tenant)
    assert record.state is RunState.PARTIAL
    terminal_events = await admin.fetch(
        """
        SELECT event_type FROM smart_bi_agent_event
        WHERE run_id=$1::uuid AND event_type IN (
            'RUN_COMPLETED','RUN_FAILED','RUN_CANCELLED','BUDGET_EXCEEDED'
        )
        """,
        run_id,
    )
    assert [row["event_type"] for row in terminal_events] == ["RUN_COMPLETED"]

    with pytest.raises(asyncpg.RaiseError, match="append-only"):
        await admin.execute(
            "UPDATE smart_bi_agent_event SET payload='{}'::jsonb WHERE run_id=$1::uuid",
            run_id,
        )
    with pytest.raises(asyncpg.RaiseError, match="append-only"):
        await admin.execute(
            "DELETE FROM smart_bi_agent_event WHERE run_id=$1::uuid", run_id
        )

    bypass_run = str(uuid.uuid4())
    await store.create_run(
        bypass_run, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    fake_terminal_payload = json.dumps(
        {
            "status": "PARTIAL",
            "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
            "claims": [],
            "blockers": ["FAKE_TERMINAL"],
            "observations": [],
            "attributionSupported": False,
        }
    )
    async with pool.acquire() as connection:
        with pytest.raises(
            asyncpg.RaiseError, match="current tenant-bound run sequence"
        ):
            async with connection.transaction():
                await connection.execute(
                    "SELECT set_config('app.factory_id', 'A', true)"
                )
                await connection.execute(
                    """
                    UPDATE smart_bi_agent_run
                    SET next_event_sequence=next_event_sequence+1,
                        version=version+1, updated_at=NOW()
                    WHERE run_id=$1::uuid
                    """,
                    bypass_run,
                )
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id,factory_id,event_sequence,event_type,payload
                    ) VALUES ($1::uuid,'A',1,'RUN_COMPLETED',$2::jsonb)
                    """,
                    bypass_run,
                    fake_terminal_payload,
                )
    bypass_record = await store.load_run(bypass_run, tenant)
    assert bypass_record.state is RunState.RUNNING
    assert bypass_record.next_event_sequence == 0


@pytest.mark.asyncio
async def test_concurrent_sequence_and_competing_terminal_transition(pg_runtime):
    admin, _pool, store = pg_runtime
    tenant = context("A")
    sequence_run = str(uuid.uuid4())
    await store.create_run(
        sequence_run, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    events = await asyncio.gather(
        *(
            store.append_event(
                sequence_run,
                tenant,
                AgentEventType.STEP_STARTED,
                {"round": 1, "purposeCode": "CONCURRENCY_TEST"},
                counters=RuntimeCounters(),
            )
            for _ in range(20)
        )
    )
    assert sorted(event.sequence for event in events) == list(range(1, 21))
    persisted = await admin.fetch(
        "SELECT event_sequence FROM smart_bi_agent_event WHERE run_id=$1::uuid ORDER BY event_sequence",
        sequence_run,
    )
    assert [row["event_sequence"] for row in persisted] == list(range(1, 21))

    terminal_run = str(uuid.uuid4())
    await store.create_run(
        terminal_run, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    partial = StructuredOutcome(
        OutcomeStatus.PARTIAL,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        blockers=("PARTIAL_EVIDENCE",),
    )
    failed = StructuredOutcome(
        OutcomeStatus.FAILED,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        blockers=("CONTROLLED_FAILURE",),
    )
    results = await asyncio.gather(
        store.compare_and_set_terminal(
            terminal_run,
            tenant,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.PARTIAL,
            outcome=partial,
            counters=RuntimeCounters(),
            terminal_event_type=AgentEventType.RUN_COMPLETED,
        ),
        store.compare_and_set_terminal(
            terminal_run,
            tenant,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.FAILED,
            outcome=failed,
            counters=RuntimeCounters(),
            terminal_event_type=AgentEventType.RUN_FAILED,
            failure_code="CONTROLLED_FAILURE",
        ),
    )
    assert sorted(results) == [False, True]
    row = await admin.fetchrow(
        """
        SELECT run.state, event.event_type, event.event_sequence
        FROM smart_bi_agent_run run
        JOIN smart_bi_agent_event event USING (run_id)
        WHERE run.run_id=$1::uuid
          AND event.event_type IN ('RUN_COMPLETED','RUN_FAILED')
        """,
        terminal_run,
    )
    assert row is not None
    assert (row["state"], row["event_type"]) in {
        ("PARTIAL", "RUN_COMPLETED"),
        ("FAILED", "RUN_FAILED"),
    }
    assert row["event_sequence"] == 1
    assert (
        await admin.fetchval(
            """
        SELECT COUNT(*) FROM smart_bi_agent_event
        WHERE run_id=$1::uuid AND event_type IN ('RUN_COMPLETED','RUN_FAILED')
        """,
            terminal_run,
        )
        == 1
    )


@pytest.mark.asyncio
async def test_event_replay_after_sequence_is_ordered_tenant_bound_and_decoded(
    pg_runtime,
):
    _admin, _pool, store = pg_runtime
    tenant = context("A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.STEP_STARTED,
        {"round": 1, "purposeCode": "PERIOD_BASELINE"},
        counters=RuntimeCounters(),
        step_id="round1-period",
        tool_name="restaurant_period_comparison_read.v1",
    )
    expected_payload = {
        "round": 1,
        "evidenceId": "evidence-1",
        "evidenceStatus": "OK",
        "factCount": 2,
        "evidenceBytes": 300,
        "warningCodes": [],
    }
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.STEP_COMPLETED,
        expected_payload,
        counters=RuntimeCounters(1, 1, 2, 300),
        step_id="round1-period",
        tool_name="restaurant_period_comparison_read.v1",
    )

    replay = await store.events_for(run_id, tenant, after_sequence=1)

    assert [event.sequence for event in replay] == [2, 3]
    assert [event.event_type for event in replay] == [
        AgentEventType.STEP_STARTED,
        AgentEventType.STEP_COMPLETED,
    ]
    assert replay[1].payload == expected_payload
    assert replay[1].step_id == "round1-period"
    assert replay[1].tool_name == "restaurant_period_comparison_read.v1"
    assert await store.events_for(run_id, tenant, after_sequence=3) == ()
    with pytest.raises(RunAccessError):
        await store.events_for(run_id, context("B"), after_sequence=0)
    with pytest.raises(RunAccessError):
        await store.events_for(str(uuid.uuid4()), tenant, after_sequence=0)
