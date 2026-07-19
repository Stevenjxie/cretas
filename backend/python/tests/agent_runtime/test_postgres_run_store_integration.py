"""Real PostgreSQL security/concurrency gate for the agent run ledger.

This test is intentionally opt-in and must only target a disposable local DB::

    AGENT_RUNTIME_PG_DSN=postgresql://postgres:<password>@127.0.0.1:55432/postgres \
      AGENT_RUNTIME_PG_DISPOSABLE_CONFIRM=YES \
      python -m pytest tests/agent_runtime/test_postgres_run_store_integration.py -q

The fixture creates a random schema and random application role, applies the exact
migrations only inside that schema, and then connects as the non-owner application
role so FORCE RLS is genuinely exercised. Cleanup targets only those random names.
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
    CancelRequestResult,
    PostgresRunStore,
    RunAccessError,
    RunStoreError,
    STALE_AFTER_SECONDS,
    STALE_RUN_FAILURE_CODE,
    StaleRunReconcileResult,
)


asyncpg = pytest.importorskip("asyncpg")


PG_DSN = os.environ.get("AGENT_RUNTIME_PG_DSN")
DISPOSABLE_CONFIRMED = os.environ.get("AGENT_RUNTIME_PG_DISPOSABLE_CONFIRM") == "YES"
pytestmark = pytest.mark.skipif(
    not PG_DSN or not DISPOSABLE_CONFIRMED,
    reason="explicit disposable PostgreSQL DSN/confirmation unset; real gate skipped",
)
MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_01__smart_bi_agent_run_event.sql"
)
ADAPTIVE_MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_03__restaurant_agent_adaptive_events.sql"
)
OWNER_CONTRACT_MIGRATION = (
    Path(__file__).parents[2]
    / "smartbi/database/migrations/V20261028_05__restaurant_agent_owner_enforcement.sql"
)
APP_PASSWORD = secrets.token_urlsafe(24)
APP_ROLE = ""
TEST_SCHEMA = ""


def context(factory_id: str, user_id: str = "user-A") -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id=user_id,
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


async def bind_app_context(
    connection,
    *,
    factory_id: str,
    user_id: str = "",
    actor_role: str = "",
    audit: bool = False,
) -> None:
    await connection.execute(
        "SELECT set_config('app.factory_id', $1, true)", factory_id
    )
    await connection.execute("SELECT set_config('app.user_id', $1, true)", user_id)
    await connection.execute(
        "SELECT set_config('app.actor_role', $1, true)", actor_role
    )
    await connection.execute(
        "SELECT set_config('app.agent_ops_audit', $1, true)",
        "true" if audit else "false",
    )


async def backdate_running_run(admin, run_id: str) -> None:
    updated = await admin.execute(
        """
        UPDATE smart_bi_agent_run
        SET updated_at = clock_timestamp()
                - make_interval(secs => $2::double precision),
            version = version + 1
        WHERE run_id = $1::uuid AND state = 'RUNNING'
        """,
        run_id,
        STALE_AFTER_SECONDS,
    )
    assert updated == "UPDATE 1"


async def wait_for_smartbi_lock(admin):
    for _ in range(200):
        await admin.execute("SELECT pg_stat_clear_snapshot()")
        row = await admin.fetchrow(
            """
            SELECT xact_start
            FROM pg_stat_activity
            WHERE usename = $1 AND wait_event_type = 'Lock'
              AND state = 'active' AND xact_start IS NOT NULL
            ORDER BY xact_start
            LIMIT 1
            """,
            APP_ROLE,
        )
        if row is not None:
            return row["xact_start"]
        await asyncio.sleep(0.01)
    pytest.fail("smartbi statement did not enter the expected lock wait")


def app_dsn(admin_dsn: str) -> str:
    parsed = urlsplit(admin_dsn)
    host = parsed.hostname or "127.0.0.1"
    port = f":{parsed.port}" if parsed.port else ""
    netloc = f"{APP_ROLE}:{quote(APP_PASSWORD)}@{host}{port}"
    return urlunsplit(
        (parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment)
    )


@pytest_asyncio.fixture
async def pg_runtime():
    global APP_ROLE, TEST_SCHEMA
    parsed = urlsplit(PG_DSN)
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        pytest.fail("AGENT_RUNTIME_PG_DSN must point to a disposable local PostgreSQL")
    if not DISPOSABLE_CONFIRMED:
        pytest.fail("AGENT_RUNTIME_PG_DISPOSABLE_CONFIRM=YES is required")
    admin = await asyncpg.connect(PG_DSN)
    pool = None
    schema_created = False
    role_created = False
    try:
        for _ in range(10):
            isolation_suffix = uuid.uuid4().hex[:16]
            candidate_role = f"smartbi_test_{isolation_suffix}"
            candidate_schema = f"agent_runtime_test_{isolation_suffix}"
            collision = await admin.fetchrow(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_namespace WHERE nspname = $1
                ) AS schema_exists,
                EXISTS (
                    SELECT 1 FROM pg_roles WHERE rolname = $2
                ) AS role_exists
                """,
                candidate_schema,
                candidate_role,
            )
            if collision["schema_exists"] or collision["role_exists"]:
                continue
            TEST_SCHEMA = candidate_schema
            APP_ROLE = candidate_role
            try:
                await admin.execute(f'CREATE SCHEMA "{TEST_SCHEMA}"')
                schema_created = True
                await admin.execute(
                    f'CREATE ROLE "{APP_ROLE}" LOGIN PASSWORD \'{APP_PASSWORD}\''
                )
                role_created = True
                break
            except asyncpg.PostgresError as exc:
                if exc.sqlstate not in {"42710", "42P06"}:
                    raise
                if schema_created:
                    await admin.execute(f'DROP SCHEMA "{TEST_SCHEMA}" CASCADE')
                if role_created:
                    await admin.execute(f'DROP ROLE "{APP_ROLE}"')
                schema_created = False
                role_created = False
        else:
            pytest.fail("could not allocate a collision-free PostgreSQL test namespace")

        await admin.execute(f'GRANT USAGE ON SCHEMA "{TEST_SCHEMA}" TO "{APP_ROLE}"')
        await admin.execute(f'SET search_path TO "{TEST_SCHEMA}"')
        migration = MIGRATION.read_text(encoding="utf-8").replace(
            "smartbi_user", APP_ROLE
        )
        adaptive = ADAPTIVE_MIGRATION.read_text(encoding="utf-8")
        await admin.execute(migration)
        await admin.execute(adaptive)
        pool = await asyncpg.create_pool(
            app_dsn(PG_DSN),
            min_size=2,
            max_size=20,
            server_settings={"search_path": TEST_SCHEMA},
        )
        yield admin, pool, PostgresRunStore(pool)
    finally:
        try:
            if pool is not None:
                await pool.close()
        finally:
            try:
                await admin.execute("RESET search_path")
            finally:
                try:
                    if schema_created:
                        await admin.execute(f'DROP SCHEMA "{TEST_SCHEMA}" CASCADE')
                finally:
                    try:
                        if role_created:
                            await admin.execute(f'DROP ROLE "{APP_ROLE}"')
                    finally:
                        await admin.close()


@pytest.mark.asyncio
async def test_force_rls_no_tenant_and_cross_tenant_read_write(pg_runtime):
    admin, pool, store = pg_runtime
    flags = await admin.fetch(
        """
        SELECT relname, relrowsecurity, relforcerowsecurity
        FROM pg_class relation
        JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
        WHERE namespace.nspname = $1
          AND relname IN ('smart_bi_agent_run', 'smart_bi_agent_event')
        ORDER BY relname
        """,
        TEST_SCHEMA,
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
    other_owner = context("A", "user-B")
    with pytest.raises(RunAccessError):
        await store.load_run(run_id, other_owner)
    with pytest.raises(RunAccessError):
        await store.events_for(run_id, other_owner)
    with pytest.raises(RunAccessError):
        await store.request_cancel(run_id, other_owner)
    with pytest.raises(RunAccessError):
        await store.reconcile_stale_run(run_id, other_owner)

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
async def test_expand_then_contract_preserves_legacy_without_guessing_owner(pg_runtime):
    admin, pool, store = pg_runtime
    legacy_id = str(uuid.uuid4())
    await admin.execute(
        """
        INSERT INTO smart_bi_agent_run (
            run_id, factory_id, business_type, correlation_id,
            route_code, sanitized_request
        ) VALUES ($1::uuid, 'A', 'RESTAURANT', 'legacy-corr',
                  'GROSS_MARGIN_DECLINE_ATTRIBUTION', $2::jsonb)
        """,
        legacy_id,
        json.dumps(request()),
    )

    async with pool.acquire() as connection:
        async with connection.transaction():
            await bind_app_context(connection, factory_id="A")
            assert await connection.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid",
                legacy_id,
            ) == 1
            assert await connection.execute(
                """
                UPDATE smart_bi_agent_run
                SET updated_at=clock_timestamp(), version=version+1
                WHERE run_id=$1::uuid
                """,
                legacy_id,
            ) == "UPDATE 1"
            with pytest.raises(asyncpg.InsufficientPrivilegeError):
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_run (
                        run_id, factory_id, business_type, correlation_id,
                        route_code, sanitized_request
                    ) VALUES ($1::uuid, 'A', 'RESTAURANT', 'blocked-null',
                              'GROSS_MARGIN_DECLINE_ATTRIBUTION', $2::jsonb)
                    """,
                    str(uuid.uuid4()),
                    json.dumps(request()),
                )
    with pytest.raises(RunAccessError):
        await store.load_run(legacy_id, context("A", "user-A"))

    await admin.execute(OWNER_CONTRACT_MIGRATION.read_text(encoding="utf-8"))

    async with pool.acquire() as connection:
        async with connection.transaction():
            await bind_app_context(connection, factory_id="A")
            assert await connection.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid",
                legacy_id,
            ) == 0
            assert await connection.execute(
                """
                UPDATE smart_bi_agent_run
                SET updated_at=clock_timestamp(), version=version+1
                WHERE run_id=$1::uuid
                """,
                legacy_id,
            ) == "UPDATE 0"
    assert await admin.fetchval(
        "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid", legacy_id
    ) == 1
    with pytest.raises(asyncpg.CheckViolationError):
        await admin.execute(
            """
            INSERT INTO smart_bi_agent_run (
                run_id, factory_id, business_type, correlation_id,
                route_code, sanitized_request
            ) VALUES ($1::uuid, 'A', 'RESTAURANT', 'contract-null',
                      'GROSS_MARGIN_DECLINE_ATTRIBUTION', $2::jsonb)
            """,
            str(uuid.uuid4()),
            json.dumps(request()),
        )

    owned_id = str(uuid.uuid4())
    await store.create_run(
        owned_id, context("A", "user-A"), RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    assert (await store.load_run(owned_id, context("A", "user-A"))).owner_user_id == "user-A"


@pytest.mark.asyncio
async def test_contract_tenant_admin_audit_is_select_only_and_tenant_bound(pg_runtime):
    admin, pool, store = pg_runtime
    owner = context("A", "user-A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, owner, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await store.append_event(
        run_id,
        owner,
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    audit_insert_run_id = str(uuid.uuid4())
    await store.create_run(
        audit_insert_run_id,
        owner,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        request(),
    )
    assert await admin.execute(
        """
        UPDATE smart_bi_agent_run
        SET next_event_sequence=1, version=version+1, updated_at=clock_timestamp()
        WHERE run_id=$1::uuid
        """,
        audit_insert_run_id,
    ) == "UPDATE 1"
    assert await admin.fetchval(
        "SELECT COUNT(*) FROM smart_bi_agent_event WHERE run_id=$1::uuid",
        audit_insert_run_id,
    ) == 0
    audit_insert_run = await admin.fetchrow(
        """
        SELECT owner_user_id, next_event_sequence
        FROM smart_bi_agent_run WHERE run_id=$1::uuid
        """,
        audit_insert_run_id,
    )
    assert dict(audit_insert_run) == {
        "owner_user_id": "user-A",
        "next_event_sequence": 1,
    }
    await admin.execute(OWNER_CONTRACT_MIGRATION.read_text(encoding="utf-8"))

    async with pool.acquire() as connection:
        async with connection.transaction():
            await bind_app_context(
                connection,
                factory_id="A",
                user_id="admin-B",
                actor_role="restaurant_manager",
                audit=True,
            )
            assert await connection.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid", run_id
            ) == 1
            assert await connection.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_event WHERE run_id=$1::uuid", run_id
            ) == 1
            assert await connection.fetchval(
                """
                SELECT next_event_sequence FROM smart_bi_agent_run
                WHERE run_id=$1::uuid
                """,
                audit_insert_run_id,
            ) == 1
            assert await connection.fetchval(
                "SELECT COUNT(*) FROM smart_bi_agent_event WHERE run_id=$1::uuid",
                audit_insert_run_id,
            ) == 0
            assert await connection.execute(
                """
                UPDATE smart_bi_agent_run
                SET updated_at=clock_timestamp(), version=version+1
                WHERE run_id=$1::uuid
                """,
                run_id,
            ) == "UPDATE 0"
            with pytest.raises(asyncpg.InsufficientPrivilegeError):
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id, factory_id, event_sequence, event_type, payload
                    ) VALUES ($1::uuid, 'A', 1, 'RUN_STARTED',
                              '{"routeCode":"GROSS_MARGIN_DECLINE_ATTRIBUTION"}'::jsonb)
                    """,
                    audit_insert_run_id,
                )

        denied_contexts = (
            ("A", "viewer-B", "viewer", True),
            ("A", "admin-B", "restaurant_manager", False),
            ("A", "", "restaurant_manager", True),
            ("B", "admin-B", "restaurant_manager", True),
        )
        for factory_id, user_id, role, audit in denied_contexts:
            async with connection.transaction():
                await bind_app_context(
                    connection,
                    factory_id=factory_id,
                    user_id=user_id,
                    actor_role=role,
                    audit=audit,
                )
                assert await connection.fetchval(
                    "SELECT COUNT(*) FROM smart_bi_agent_run WHERE run_id=$1::uuid",
                    run_id,
                ) == 0
                assert await connection.fetchval(
                    "SELECT COUNT(*) FROM smart_bi_agent_event WHERE run_id=$1::uuid",
                    run_id,
                ) == 0

    other_owner = context("A", "admin-B")
    with pytest.raises(RunAccessError):
        await store.request_cancel(run_id, other_owner)
    with pytest.raises(RunAccessError):
        await store.reconcile_stale_run(run_id, other_owner)


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


@pytest.mark.asyncio
async def test_stale_reconcile_fresh_boundary_preserves_counters_and_is_idempotent(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")

    fresh_id = str(uuid.uuid4())
    await store.create_run(
        fresh_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    fresh = await store.reconcile_stale_run(fresh_id, tenant)
    assert fresh.result is StaleRunReconcileResult.NOT_STALE
    assert fresh.record.state is RunState.RUNNING
    assert await store.events_for(fresh_id, tenant) == ()

    stale_id = str(uuid.uuid4())
    await store.create_run(
        stale_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    counters = RuntimeCounters(1, 2, 17, 4096)
    await store.append_event(
        stale_id,
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
    await backdate_running_run(admin, stale_id)

    stale = await store.reconcile_stale_run(stale_id, tenant)

    assert stale.result is StaleRunReconcileResult.RECONCILED
    assert stale.record.state is RunState.FAILED
    assert stale.record.failure_code == STALE_RUN_FAILURE_CODE
    assert stale.record.counters == counters
    assert stale.record.outcome_summary == {
        "status": "FAILED",
        "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "claims": [],
        "blockers": [STALE_RUN_FAILURE_CODE],
        "observations": [],
        "actionProposals": [],
        "attributionSupported": False,
    }
    events = await store.events_for(stale_id, tenant)
    assert [event.event_type for event in events] == [
        AgentEventType.STEP_COMPLETED,
        AgentEventType.RUN_FAILED,
    ]
    assert events[-1].payload == {"failureCode": STALE_RUN_FAILURE_CODE}
    repeated = await store.reconcile_stale_run(stale_id, tenant)
    assert repeated.result is StaleRunReconcileResult.ALREADY_TERMINAL
    assert len(await store.events_for(stale_id, tenant)) == 2


@pytest.mark.asyncio
async def test_stale_reconcile_two_callers_and_normal_terminal_race_have_one_winner(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")

    two_id = str(uuid.uuid4())
    await store.create_run(
        two_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await backdate_running_run(admin, two_id)
    reconciliations = await asyncio.gather(
        store.reconcile_stale_run(two_id, tenant),
        store.reconcile_stale_run(two_id, tenant),
    )
    assert sorted(item.result.value for item in reconciliations) == [
        "ALREADY_TERMINAL",
        "RECONCILED",
    ]
    assert len(await store.events_for(two_id, tenant)) == 1

    race_id = str(uuid.uuid4())
    await store.create_run(
        race_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await backdate_running_run(admin, race_id)
    race = await asyncio.gather(
        store.reconcile_stale_run(race_id, tenant),
        store.compare_and_set_terminal(
            race_id,
            tenant,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.PARTIAL,
            outcome=StructuredOutcome(
                OutcomeStatus.PARTIAL,
                RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            ),
            counters=RuntimeCounters(),
            terminal_event_type=AgentEventType.RUN_COMPLETED,
        ),
    )
    record = await store.load_run(race_id, tenant)
    terminal_events = [
        event
        for event in await store.events_for(race_id, tenant)
        if event.event_type in {AgentEventType.RUN_FAILED, AgentEventType.RUN_COMPLETED}
    ]
    assert len(terminal_events) == 1
    assert record.state in {RunState.FAILED, RunState.PARTIAL}
    if record.state is RunState.FAILED:
        assert race[0].result is StaleRunReconcileResult.RECONCILED
        assert race[1] is False
    else:
        assert race[0].result is StaleRunReconcileResult.ALREADY_TERMINAL
        assert race[1] is True


@pytest.mark.asyncio
async def test_append_refreshes_pg_updated_at_before_reconcile_and_access_is_hidden(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await backdate_running_run(admin, run_id)
    await store.append_event(
        run_id,
        tenant,
        AgentEventType.RUN_STARTED,
        {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
        counters=RuntimeCounters(),
    )
    refreshed = await store.reconcile_stale_run(run_id, tenant)
    assert refreshed.result is StaleRunReconcileResult.NOT_STALE
    assert [event.event_type for event in await store.events_for(run_id, tenant)] == [
        AgentEventType.RUN_STARTED
    ]

    for inaccessible_id, inaccessible_tenant in (
        (run_id, context("B")),
        (str(uuid.uuid4()), tenant),
    ):
        with pytest.raises(RunAccessError, match="trusted tenant"):
            await store.reconcile_stale_run(inaccessible_id, inaccessible_tenant)


@pytest.mark.asyncio
async def test_concurrent_append_and_stale_reconcile_produce_one_legal_ledger(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await backdate_running_run(admin, run_id)

    reconciliation, append = await asyncio.gather(
        store.reconcile_stale_run(run_id, tenant),
        store.append_event(
            run_id,
            tenant,
            AgentEventType.RUN_STARTED,
            {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
            counters=RuntimeCounters(),
        ),
        return_exceptions=True,
    )

    record = await store.load_run(run_id, tenant)
    events = await store.events_for(run_id, tenant)
    if record.state is RunState.RUNNING:
        assert reconciliation.result is StaleRunReconcileResult.NOT_STALE
        assert not isinstance(append, Exception)
        assert [event.event_type for event in events] == [AgentEventType.RUN_STARTED]
    else:
        assert record.state is RunState.FAILED
        assert reconciliation.result is StaleRunReconcileResult.RECONCILED
        assert isinstance(append, RunAccessError)
        assert [event.event_type for event in events] == [AgentEventType.RUN_FAILED]


@pytest.mark.asyncio
async def test_stale_reconcile_event_insert_failure_rolls_back_terminal_update(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    await backdate_running_run(admin, run_id)
    before = await admin.fetchrow(
        """
        SELECT state, version, next_event_sequence, updated_at
        FROM smart_bi_agent_run WHERE run_id=$1::uuid
        """,
        run_id,
    )
    await admin.execute(
        f"""
        CREATE OR REPLACE FUNCTION test_reject_stale_event()
        RETURNS TRIGGER LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.run_id = '{run_id}'::uuid
               AND NEW.event_type = 'RUN_FAILED' THEN
                RAISE EXCEPTION 'injected stale event failure';
            END IF;
            RETURN NEW;
        END;
        $$;
        DROP TRIGGER IF EXISTS trg_test_reject_stale_event ON smart_bi_agent_event;
        CREATE TRIGGER trg_test_reject_stale_event
        BEFORE INSERT ON smart_bi_agent_event
        FOR EACH ROW EXECUTE FUNCTION test_reject_stale_event();
        """
    )
    try:
        with pytest.raises(asyncpg.RaiseError, match="injected stale event failure"):
            await store.reconcile_stale_run(run_id, tenant)
    finally:
        await admin.execute(
            """
            DROP TRIGGER IF EXISTS trg_test_reject_stale_event ON smart_bi_agent_event;
            DROP FUNCTION IF EXISTS test_reject_stale_event();
            """
        )
    after = await admin.fetchrow(
        """
        SELECT state, version, next_event_sequence, updated_at
        FROM smart_bi_agent_run WHERE run_id=$1::uuid
        """,
        run_id,
    )
    assert dict(after) == dict(before)
    assert await store.events_for(run_id, tenant) == ()


@pytest.mark.asyncio
async def test_liveness_timestamps_are_sampled_after_create_append_and_terminal_locks(
    pg_runtime,
):
    admin, _pool, store = pg_runtime
    tenant = context("A")

    create_id = str(uuid.uuid4())
    create_lock = admin.transaction()
    await create_lock.start()
    await admin.execute("LOCK TABLE smart_bi_agent_run IN ACCESS EXCLUSIVE MODE")
    create_task = asyncio.create_task(
        store.create_run(
            create_id,
            tenant,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            request(),
        )
    )
    create_xact_start = await wait_for_smartbi_lock(admin)
    await asyncio.sleep(0.05)
    create_release_time = await admin.fetchval("SELECT clock_timestamp()")
    await create_lock.commit()
    await create_task
    created = await admin.fetchrow(
        """
        SELECT created_at, updated_at
        FROM smart_bi_agent_run WHERE run_id=$1::uuid
        """,
        create_id,
    )
    assert create_xact_start < create_release_time
    assert created["created_at"] == created["updated_at"]
    assert created["updated_at"] >= create_release_time
    assert (
        await store.reconcile_stale_run(create_id, tenant)
    ).result is StaleRunReconcileResult.NOT_STALE

    append_lock = admin.transaction()
    await append_lock.start()
    await admin.fetchrow(
        "SELECT run_id FROM smart_bi_agent_run WHERE run_id=$1::uuid FOR UPDATE",
        create_id,
    )
    append_task = asyncio.create_task(
        store.append_event(
            create_id,
            tenant,
            AgentEventType.RUN_STARTED,
            {"routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION"},
            counters=RuntimeCounters(),
        )
    )
    append_xact_start = await wait_for_smartbi_lock(admin)
    await asyncio.sleep(0.05)
    append_release_time = await admin.fetchval("SELECT clock_timestamp()")
    await append_lock.commit()
    await append_task
    appended_at = await admin.fetchval(
        "SELECT updated_at FROM smart_bi_agent_run WHERE run_id=$1::uuid", create_id
    )
    assert append_xact_start < append_release_time
    assert appended_at >= append_release_time
    assert (
        await store.reconcile_stale_run(create_id, tenant)
    ).result is StaleRunReconcileResult.NOT_STALE

    terminal_id = str(uuid.uuid4())
    await store.create_run(
        terminal_id,
        tenant,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        request(),
    )
    terminal_lock = admin.transaction()
    await terminal_lock.start()
    await admin.fetchrow(
        "SELECT run_id FROM smart_bi_agent_run WHERE run_id=$1::uuid FOR UPDATE",
        terminal_id,
    )
    terminal_task = asyncio.create_task(
        store.compare_and_set_terminal(
            terminal_id,
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
    )
    terminal_xact_start = await wait_for_smartbi_lock(admin)
    await asyncio.sleep(0.05)
    terminal_release_time = await admin.fetchval("SELECT clock_timestamp()")
    await terminal_lock.commit()
    assert await terminal_task
    terminal = await admin.fetchrow(
        """
        SELECT updated_at, completed_at
        FROM smart_bi_agent_run WHERE run_id=$1::uuid
        """,
        terminal_id,
    )
    assert terminal_xact_start < terminal_release_time
    assert terminal["updated_at"] == terminal["completed_at"]
    assert terminal["updated_at"] >= terminal_release_time
    assert (
        await store.reconcile_stale_run(terminal_id, tenant)
    ).result is StaleRunReconcileResult.ALREADY_TERMINAL


@pytest.mark.asyncio
async def test_cross_process_cancel_visibility_idempotency_and_terminal_race(pg_runtime):
    _admin, pool, store = pg_runtime
    other_process = PostgresRunStore(pool)
    tenant = context("A")
    run_id = str(uuid.uuid4())
    await store.create_run(
        run_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )

    cancellation = await other_process.request_cancel(run_id, tenant)
    repeated = await store.request_cancel(run_id, tenant)

    assert cancellation.result is CancelRequestResult.REQUESTED
    assert repeated.result is CancelRequestResult.ALREADY_REQUESTED
    assert await store.is_cancellation_requested(run_id, tenant)
    assert await other_process.is_cancellation_requested(run_id, tenant)
    with pytest.raises(RunAccessError):
        await other_process.request_cancel(run_id, context("B"))

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
    cancelled = await other_process.compare_and_set_terminal(
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
    terminal_events = [
        event.event_type
        for event in await store.events_for(run_id, tenant)
        if event.event_type in {
            AgentEventType.RUN_COMPLETED,
            AgentEventType.RUN_CANCELLED,
        }
    ]
    assert terminal_events == [AgentEventType.RUN_CANCELLED]

    race_id = str(uuid.uuid4())
    await store.create_run(
        race_id, tenant, RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION, request()
    )
    race_cancel, race_complete = await asyncio.gather(
        other_process.request_cancel(race_id, tenant),
        store.compare_and_set_terminal(
            race_id,
            tenant,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.PARTIAL,
            outcome=StructuredOutcome(
                OutcomeStatus.PARTIAL,
                RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            ),
            counters=RuntimeCounters(),
            terminal_event_type=AgentEventType.RUN_COMPLETED,
        ),
    )
    if race_cancel.result is CancelRequestResult.REQUESTED:
        assert race_complete is False
        assert not any(
            event.event_type is AgentEventType.RUN_COMPLETED
            for event in await store.events_for(race_id, tenant)
        )
    else:
        assert race_cancel.result is CancelRequestResult.ALREADY_TERMINAL
        assert race_complete is True
