"""Opt-in hard-timeout gate against a disposable local PostgreSQL.

Run with::

    AGENT_RUNTIME_PG_DSN=postgresql://postgres:<password>@127.0.0.1:55432/postgres \
      python -m pytest \
      tests/agent_runtime/test_read_tool_gateway_postgres_integration.py -q

No schema is created. The tests use only ``pg_sleep`` and transaction-local
settings, and reject every non-loopback host before connecting.
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import datetime, timezone
from urllib.parse import urlsplit

import pytest
import pytest_asyncio

from smartbi.agent.runtime.contracts import (
    Coverage,
    DataClassification,
    EvidenceDraft,
    EvidenceFact,
    EvidenceStatus,
    Freshness,
    ProvenanceReference,
    TrustedExecutionContext,
)
from smartbi.agent.runtime.descriptors import restaurant_descriptors
from smartbi.agent.runtime.gateway import ReadToolGateway, ReadToolTimeout
from smartbi.agent.runtime.registry import ReadonlyToolRegistry


asyncpg = pytest.importorskip("asyncpg")
PG_DSN = os.environ.get("AGENT_RUNTIME_PG_DSN")
pytestmark = pytest.mark.skipif(
    not PG_DSN,
    reason="AGENT_RUNTIME_PG_DSN unset; real disposable PostgreSQL gate skipped",
)


def trusted(factory_id: str) -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id="timeout-test",
        correlation_id="timeout-integration",
        run_id=str(uuid.uuid4()),
        step_id="step-1",
        authorized_classifications=frozenset(
            {
                DataClassification.FINANCIAL_RESTRICTED,
                DataClassification.OPERATIONAL_INTERNAL,
                DataClassification.CUSTOMER_SENSITIVE_AGGREGATED,
            }
        ),
    )


def parameters() -> dict[str, str]:
    return {"startDate": "2026-01-01", "endDate": "2026-01-01"}


def evidence_draft(descriptor, factory_id: str) -> EvidenceDraft:
    ref = ProvenanceReference(
        ref_id="pg-timeout-ref",
        source_type="POSTGRES",
        asset="current_setting",
        query_id=descriptor.name,
        source_version=descriptor.digest,
    )
    fact = EvidenceFact.numeric(
        fact_id="tenant-marker",
        metric="tenantMarker",
        value="2" if factory_id == "F002" else "1",
        unit=None,
        scale=0,
        dimensions={"factoryId": factory_id},
        status=EvidenceStatus.OK,
        semantics="transaction-local tenant marker",
        provenance_refs=(ref.ref_id,),
        freshness=Freshness.unknown("transaction-local integration probe"),
        coverage=Coverage.complete("one transaction-local setting", 1),
    )
    return EvidenceDraft(
        status=EvidenceStatus.OK,
        requested_window={"start": "2026-01-01", "end": "2026-01-01"},
        effective_window={"start": "2026-01-01", "end": "2026-01-01"},
        grain="DAY",
        normalized_parameters=parameters(),
        facts=(fact,),
        provenance=(ref,),
    )


def gateway(pool, adapter) -> tuple[object, ReadToolGateway]:
    descriptor = restaurant_descriptors()[0]
    registry = ReadonlyToolRegistry()
    registry.register(descriptor, adapter)
    return descriptor, ReadToolGateway(
        pool,
        registry,
        id_factory=lambda: str(uuid.uuid4()),
        clock=lambda: datetime(2026, 1, 2, tzinfo=timezone.utc),
    )


@pytest_asyncio.fixture
async def pg_timeout_pool():
    parsed = urlsplit(PG_DSN)
    if parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
        pytest.fail("AGENT_RUNTIME_PG_DSN must point to a disposable local PostgreSQL")
    application_name = f"cretas_timeout_gate_{uuid.uuid4().hex}"
    admin = await asyncpg.connect(PG_DSN)
    pool = await asyncpg.create_pool(
        PG_DSN,
        min_size=1,
        max_size=1,
        server_settings={"application_name": application_name},
    )
    try:
        yield admin, pool, application_name
    finally:
        await pool.close()
        await admin.close()


async def assert_pool_reusable(pool) -> None:
    async def probe() -> int:
        async with pool.acquire() as connection:
            return await connection.fetchval("SELECT 1")

    assert await asyncio.wait_for(probe(), timeout=1.0) == 1


async def assert_backend_no_longer_running_probe(admin, backend_pid: int) -> None:
    row = await admin.fetchrow(
        """
        SELECT state, query
        FROM pg_stat_activity
        WHERE pid = $1
        """,
        backend_pid,
    )
    assert row is not None
    assert not (row["state"] == "active" and "pg_sleep" in row["query"])


async def wait_until_backend_is_running_probe(admin, backend_pid: int) -> None:
    deadline = asyncio.get_running_loop().time() + 1.0
    while True:
        row = await admin.fetchrow(
            """
            SELECT state, query
            FROM pg_stat_activity
            WHERE pid = $1
            """,
            backend_pid,
        )
        if row is not None and row["state"] == "active" and "pg_sleep" in row["query"]:
            return
        if asyncio.get_running_loop().time() >= deadline:
            pytest.fail("backend did not enter active pg_sleep within 1 second")
        await asyncio.sleep(0.01)


@pytest.mark.asyncio
async def test_pg_sleep_hard_stops_before_return_and_pool_settings_tenant_are_reset(
    pg_timeout_pool,
):
    admin, pool, _ = pg_timeout_pool
    async with pool.acquire() as connection:
        baseline_statement_timeout = await connection.fetchval("SHOW statement_timeout")

    backend = {}

    async def sleeping_adapter(bound_pool, trusted_context, *_):
        async with bound_pool.acquire() as connection:
            backend["pid"] = await connection.fetchval("SELECT pg_backend_pid()")
            await connection.fetchval("SELECT pg_sleep(10)")
        raise AssertionError("statement timeout must stop pg_sleep")

    descriptor, timeout_gateway = gateway(pool, sleeping_adapter)
    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await timeout_gateway.execute(
            descriptor.name,
            parameters(),
            trusted("F001"),
            timeout_seconds=0.5,
            cleanup_grace_seconds=0.2,
        )

    await assert_backend_no_longer_running_probe(admin, backend["pid"])
    await assert_pool_reusable(pool)
    async with pool.acquire() as connection:
        assert await connection.fetchval("SHOW statement_timeout") == (
            baseline_statement_timeout
        )
        assert await connection.fetchval(
            "SELECT current_setting('app.factory_id', true)"
        ) in (None, "")

    observed = {}

    async def tenant_adapter(bound_pool, trusted_context, _, registered_descriptor):
        async with bound_pool.acquire() as connection:
            observed["factory_id"] = await connection.fetchval(
                "SELECT current_setting('app.factory_id', true)"
            )
        return evidence_draft(registered_descriptor, observed["factory_id"])

    descriptor, tenant_gateway = gateway(pool, tenant_adapter)
    envelope = await tenant_gateway.execute(
        descriptor.name,
        parameters(),
        trusted("F002"),
        timeout_seconds=1.0,
        cleanup_grace_seconds=0.2,
    )
    assert observed == {"factory_id": "F002"}
    assert envelope.tenant_id == "F002"
    assert envelope.facts[0].dimensions == {"factoryId": "F002"}
    assert asyncpg.__version__


@pytest.mark.asyncio
async def test_coroutine_deadline_covers_waiting_for_the_only_pool_connection(
    pg_timeout_pool,
):
    _, pool, _ = pg_timeout_pool

    async def adapter(*args):
        raise AssertionError("adapter must not run without a pool lease")

    descriptor, timeout_gateway = gateway(pool, adapter)
    async with pool.acquire():
        with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
            await timeout_gateway.execute(
                descriptor.name,
                parameters(),
                trusted("F001"),
                timeout_seconds=0.2,
                cleanup_grace_seconds=0.1,
            )
    await assert_pool_reusable(pool)


@pytest.mark.asyncio
async def test_external_cancellation_propagates_after_real_pg_query_cleanup(
    pg_timeout_pool,
):
    admin, pool, _ = pg_timeout_pool
    query_started = asyncio.Event()
    backend = {}

    async def sleeping_adapter(bound_pool, trusted_context, *_):
        async with bound_pool.acquire() as connection:
            backend["pid"] = await connection.fetchval("SELECT pg_backend_pid()")
            query_started.set()
            await connection.fetchval("SELECT pg_sleep(10)")
        raise AssertionError("external cancellation must stop pg_sleep")

    descriptor, cancel_gateway = gateway(pool, sleeping_adapter)
    task = asyncio.create_task(
        cancel_gateway.execute(
            descriptor.name,
            parameters(),
            trusted("F001"),
            timeout_seconds=5.0,
            cleanup_grace_seconds=1.0,
        )
    )
    await asyncio.wait_for(query_started.wait(), timeout=1.0)
    await wait_until_backend_is_running_probe(admin, backend["pid"])
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    await assert_backend_no_longer_running_probe(admin, backend["pid"])
    await assert_pool_reusable(pool)


@pytest.mark.asyncio
async def test_admin_pg_cancel_backend_is_source_failure_not_statement_timeout(
    pg_timeout_pool,
):
    admin, pool, _ = pg_timeout_pool
    query_started = asyncio.Event()
    backend = {}

    async def sleeping_adapter(bound_pool, trusted_context, *_):
        async with bound_pool.acquire() as connection:
            backend["pid"] = await connection.fetchval("SELECT pg_backend_pid()")
            query_started.set()
            await connection.fetchval("SELECT pg_sleep(10)")
        raise AssertionError("admin cancellation must stop pg_sleep")

    descriptor, cancel_gateway = gateway(pool, sleeping_adapter)
    task = asyncio.create_task(
        cancel_gateway.execute(
            descriptor.name,
            parameters(),
            trusted("F001"),
            timeout_seconds=5.0,
            cleanup_grace_seconds=1.0,
        )
    )
    await asyncio.wait_for(query_started.wait(), timeout=1.0)
    await wait_until_backend_is_running_probe(admin, backend["pid"])
    assert await admin.fetchval("SELECT pg_cancel_backend($1)", backend["pid"])
    envelope = await asyncio.wait_for(task, timeout=1.0)

    assert envelope.status is EvidenceStatus.ERROR
    assert envelope.warnings[0].code == "READ_SOURCE_FAILED"
    await assert_backend_no_longer_running_probe(admin, backend["pid"])
    await assert_pool_reusable(pool)
