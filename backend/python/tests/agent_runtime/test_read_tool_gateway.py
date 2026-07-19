from __future__ import annotations

import asyncio
import time
from contextlib import AbstractAsyncContextManager
from datetime import datetime, timezone
import math

import pytest

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
from smartbi.agent.runtime.gateway import (
    _CompatDeadlineExpired,
    _await_with_deadline,
    ReadToolContractError,
    ReadToolGateway,
    ReadToolTimeout,
    TenantParameterError,
)
from smartbi.agent.runtime.registry import ReadonlyToolRegistry


class _AsyncContext(AbstractAsyncContextManager):
    def __init__(self, value, events, name):
        self.value = value
        self.events = events
        self.name = name

    async def __aenter__(self):
        self.events.append((self.name, "enter"))
        return self.value

    async def __aexit__(self, exc_type, exc, tb):
        self.events.append((self.name, "exit", exc_type))


class FakeConnection:
    def __init__(self):
        self.events = []
        self.executes = []

    def transaction(self, *, readonly=False):
        self.events.append(("transaction", "created", readonly))
        return _AsyncContext(self, self.events, "transaction")

    async def execute(self, sql, *args):
        self.executes.append((sql, args))
        return "SELECT 1"


class FakePool:
    def __init__(self):
        self.connection = FakeConnection()
        self.events = []
        self.acquire_count = 0

    def acquire(self):
        self.acquire_count += 1
        return _AsyncContext(self.connection, self.events, "pool")


def context(*, business_type="restaurant", classifications=None):
    return TrustedExecutionContext(
        factory_id="F001",
        business_type=business_type,
        user_id="42",
        correlation_id="corr-1",
        run_id="run-1",
        step_id="step-1",
        authorized_classifications=frozenset(
            classifications
            or {
                DataClassification.FINANCIAL_RESTRICTED,
                DataClassification.OPERATIONAL_INTERNAL,
                DataClassification.CUSTOMER_SENSITIVE_AGGREGATED,
            }
        ),
    )


def draft(descriptor):
    freshness = Freshness.unknown("test source does not expose max date")
    coverage = Coverage.complete("one test row", 1)
    ref = ProvenanceReference(
        ref_id="p-1",
        source_type="GOLD",
        asset="agg_daily",
        query_id=descriptor.name,
        source_version=descriptor.digest,
    )
    fact = EvidenceFact.numeric(
        fact_id="f-1",
        metric="revenue",
        value="12.30",
        unit="CNY",
        scale=2,
        dimensions={"date": "2026-01-01"},
        status=EvidenceStatus.OK,
        semantics="test revenue",
        provenance_refs=(ref.ref_id,),
        freshness=freshness,
        coverage=coverage,
    )
    return EvidenceDraft(
        status=EvidenceStatus.OK,
        requested_window={"start": "2026-01-01", "end": "2026-01-01"},
        effective_window={"start": "2026-01-01", "end": "2026-01-01"},
        grain="DAY",
        normalized_parameters={"startDate": "2026-01-01", "endDate": "2026-01-01"},
        facts=(fact,),
        provenance=(ref,),
    )


def gateway_with_adapter(adapter):
    descriptor = restaurant_descriptors()[0]
    registry = ReadonlyToolRegistry()
    registry.register(descriptor, adapter)
    pool = FakePool()
    gateway = ReadToolGateway(
        pool,
        registry,
        id_factory=lambda: "evidence-1",
        clock=lambda: datetime(2026, 1, 2, tzinfo=timezone.utc),
    )
    return descriptor, pool, gateway


@pytest.mark.asyncio
async def test_gateway_binds_rls_and_adapter_to_same_readonly_connection():
    seen = {}

    async def adapter(bound_pool, trusted, parameters, descriptor):
        async with bound_pool.acquire() as connection:
            seen["connection"] = connection
            seen["factory_id"] = trusted.factory_id
        return draft(descriptor)

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    envelope = await gateway.execute(
        descriptor.name,
        {"startDate": "2026-01-01", "endDate": "2026-01-01"},
        context(),
    )

    assert pool.acquire_count == 1
    assert seen == {"connection": pool.connection, "factory_id": "F001"}
    assert ("transaction", "created", True) in pool.connection.events
    assert pool.connection.executes == [
        ("SELECT set_config('statement_timeout', $1, true)", ("14000ms",)),
        ("SELECT set_config('app.factory_id', $1, true)", ("F001",)),
    ]
    assert pool.connection.events[-1] == ("transaction", "exit", None)
    assert envelope.status is EvidenceStatus.OK
    assert envelope.facts[0].value == "12.3"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("timeout", "grace"),
    [
        (True, 0.1),
        (math.inf, 0.1),
        (1.0, True),
        (1.0, math.nan),
        (1.0, 1.0),
        (0.0005, 0.0001),
    ],
)
async def test_trusted_timeout_contract_rejects_invalid_values_before_pool_access(
    timeout, grace
):
    async def adapter(*args):
        raise AssertionError("adapter must not run")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(ReadToolContractError):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=timeout,
            cleanup_grace_seconds=grace,
        )
    assert pool.acquire_count == 0


@pytest.mark.asyncio
async def test_coroutine_timeout_wraps_pool_acquire_and_does_not_enter_transaction():
    acquire_started = asyncio.Event()
    acquire_cleaned = asyncio.Event()

    class SlowAcquire(AbstractAsyncContextManager):
        async def __aenter__(self):
            acquire_started.set()
            try:
                await asyncio.Future()
            finally:
                acquire_cleaned.set()

        async def __aexit__(self, exc_type, exc, tb):
            raise AssertionError("unentered acquire must not exit")

    class SlowPool:
        def acquire(self):
            return SlowAcquire()

    async def adapter(*args):
        raise AssertionError("adapter must not run")

    descriptor = restaurant_descriptors()[0]
    registry = ReadonlyToolRegistry()
    registry.register(descriptor, adapter)
    gateway = ReadToolGateway(SlowPool(), registry)

    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.04,
            cleanup_grace_seconds=0.01,
        )
    assert acquire_started.is_set()
    assert acquire_cleaned.is_set()


@pytest.mark.asyncio
async def test_adapter_cancellation_finally_and_transaction_cleanup_finish_before_timeout_returns():
    adapter_finally = asyncio.Event()

    async def adapter(*args):
        try:
            await asyncio.Future()
        finally:
            await asyncio.sleep(0)
            adapter_finally.set()

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.04,
            cleanup_grace_seconds=0.01,
        )

    assert adapter_finally.is_set()
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_adapter_that_suppresses_cancellation_cannot_return_late_evidence():
    cancellation_seen = asyncio.Event()

    async def adapter(bound_pool, trusted, parameters, descriptor):
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            cancellation_seen.set()
            await asyncio.sleep(0)
            return draft(descriptor)

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.04,
            cleanup_grace_seconds=0.01,
        )

    assert cancellation_seen.is_set()
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_sync_finalize_runs_after_pool_release_and_rejects_cpu_deadline_overrun():
    async def adapter(bound_pool, trusted, parameters, descriptor):
        async with bound_pool.acquire():
            pass
        return draft(descriptor)

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    original_finalize = gateway._finalize

    def slow_finalize(*args):
        assert pool.events[-1][0:2] == ("pool", "exit")
        assert pool.connection.events[-1][0:2] == ("transaction", "exit")
        time.sleep(0.05)
        return original_finalize(*args)

    gateway._finalize = slow_finalize
    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.04,
            cleanup_grace_seconds=0.01,
        )


@pytest.mark.asyncio
async def test_sqlstate_query_cancel_is_typed_only_after_transaction_and_pool_cleanup():
    class QueryCancelled(Exception):
        sqlstate = "57014"

    async def adapter(*args):
        await asyncio.sleep(0.04)
        raise QueryCancelled("raw SQL text")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(ReadToolTimeout) as captured:
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.08,
            cleanup_grace_seconds=0.05,
        )

    assert str(captured.value) == "READ_TOOL_TIMEOUT"
    assert "SQL" not in str(captured.value)
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_early_sqlstate_query_cancel_is_source_failure_not_our_timeout():
    class QueryCancelled(Exception):
        sqlstate = "57014"

    async def adapter(*args):
        raise QueryCancelled("raw admin cancellation detail")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    envelope = await gateway.execute(
        descriptor.name,
        {"startDate": "2026-01-01", "endDate": "2026-01-01"},
        context(),
        timeout_seconds=2.0,
        cleanup_grace_seconds=1.0,
    )

    assert envelope.status is EvidenceStatus.ERROR
    assert envelope.warnings[0].code == "READ_SOURCE_FAILED"
    assert "admin" not in str(envelope.to_dict()).lower()
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_adapter_timeout_error_is_not_misclassified_as_orchestrator_deadline():
    async def adapter(*args):
        raise TimeoutError("raw adapter timeout detail")

    descriptor, _, gateway = gateway_with_adapter(adapter)
    envelope = await gateway.execute(
        descriptor.name,
        {"startDate": "2026-01-01", "endDate": "2026-01-01"},
        context(),
    )

    assert envelope.status is EvidenceStatus.ERROR
    assert envelope.warnings[0].code == "READ_SOURCE_FAILED"
    assert "adapter" not in str(envelope.to_dict()).lower()


@pytest.mark.asyncio
async def test_external_task_cancellation_propagates_without_timeout_reclassification():
    started = asyncio.Event()

    async def adapter(*args):
        started.set()
        await asyncio.Future()

    descriptor, _, gateway = gateway_with_adapter(adapter)
    task = asyncio.create_task(
        gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
        )
    )
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


@pytest.mark.asyncio
async def test_python38_timeout_fallback_preserves_success(monkeypatch):
    monkeypatch.delattr(asyncio, "timeout_at", raising=False)

    async def adapter(bound_pool, trusted, parameters, descriptor):
        await asyncio.sleep(0)
        return draft(descriptor)

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    envelope = await gateway.execute(
        descriptor.name,
        {"startDate": "2026-01-01", "endDate": "2026-01-01"},
        context(),
        timeout_seconds=0.1,
        cleanup_grace_seconds=0.01,
    )

    assert envelope.status is EvidenceStatus.OK
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_python38_timeout_fallback_cleans_up_before_typed_timeout(monkeypatch):
    monkeypatch.delattr(asyncio, "timeout_at", raising=False)
    adapter_finally = asyncio.Event()

    async def adapter(*args):
        try:
            await asyncio.Future()
        finally:
            await asyncio.sleep(0)
            adapter_finally.set()

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(ReadToolTimeout, match="READ_TOOL_TIMEOUT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
            timeout_seconds=0.04,
            cleanup_grace_seconds=0.01,
        )

    assert adapter_finally.is_set()
    assert pool.events[-1][0:2] == ("pool", "exit")
    assert pool.connection.events[-1][0:2] == ("transaction", "exit")


@pytest.mark.asyncio
async def test_python38_timeout_fallback_preserves_external_cancellation(monkeypatch):
    monkeypatch.delattr(asyncio, "timeout_at", raising=False)
    started = asyncio.Event()

    async def adapter(*args):
        started.set()
        await asyncio.Future()

    descriptor, _, gateway = gateway_with_adapter(adapter)
    task = asyncio.create_task(
        gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
        )
    )
    await started.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


@pytest.mark.asyncio
async def test_python38_timeout_fallback_preserves_same_tick_external_cancel():
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 0.03

    async def operation():
        await asyncio.Future()

    task = asyncio.create_task(_await_with_deadline(operation(), deadline))
    # Register the caller cancellation at the exact deadline. It is scheduled
    # before the helper gets its first event-loop turn and registers expiry.
    loop.call_at(deadline, task.cancel)

    with pytest.raises(asyncio.CancelledError):
        await task


@pytest.mark.asyncio
async def test_python38_timeout_fallback_rejects_suppressed_child_cancel():
    loop = asyncio.get_running_loop()
    deadline = loop.time() + 0.03

    async def operation():
        try:
            await asyncio.Future()
        except asyncio.CancelledError:
            await asyncio.sleep(0)
            return "late-result"

    with pytest.raises(_CompatDeadlineExpired):
        await _await_with_deadline(operation(), deadline)


@pytest.mark.asyncio
@pytest.mark.parametrize("key", ["factoryId", "factory_id", "tenantId", "tenant_id"])
async def test_model_parameters_cannot_supply_tenant_even_when_nested(key):
    async def adapter(*args):
        raise AssertionError("adapter must not run")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(TenantParameterError):
        await gateway.execute(
            descriptor.name,
            {
                "startDate": "2026-01-01",
                "endDate": "2026-01-01",
                "nested": {key: "F999"},
            },
            context(),
        )
    assert pool.acquire_count == 0


@pytest.mark.asyncio
async def test_non_restaurant_business_type_is_rejected_before_db_access():
    async def adapter(*args):
        raise AssertionError("adapter must not run")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    with pytest.raises(TenantParameterError, match="business_type=RESTAURANT"):
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(business_type="FACTORY"),
        )
    assert pool.acquire_count == 0


@pytest.mark.asyncio
async def test_classification_denial_is_fail_closed_without_db_access():
    async def adapter(*args):
        raise AssertionError("adapter must not run")

    descriptor, pool, gateway = gateway_with_adapter(adapter)
    envelope = await gateway.execute(
        descriptor.name,
        {"startDate": "2026-01-01", "endDate": "2026-01-01"},
        context(classifications={DataClassification.OPERATIONAL_INTERNAL}),
    )
    assert envelope.status is EvidenceStatus.DENIED
    assert envelope.warnings[0].code == "CLASSIFICATION_ACCESS_DENIED"
    assert pool.acquire_count == 0


@pytest.mark.asyncio
async def test_external_envelope_json_golden_field_names_are_camel_case():
    async def adapter(bound_pool, trusted, parameters, descriptor):
        async with bound_pool.acquire():
            pass
        return draft(descriptor)

    descriptor, _, gateway = gateway_with_adapter(adapter)
    external = (
        await gateway.execute(
            descriptor.name,
            {"startDate": "2026-01-01", "endDate": "2026-01-01"},
            context(),
        )
    ).to_dict()

    assert set(external) == {
        "schemaVersion",
        "evidenceId",
        "toolName",
        "toolVersion",
        "descriptorDigest",
        "tenantId",
        "businessType",
        "correlationId",
        "runId",
        "stepId",
        "querySpec",
        "status",
        "facts",
        "provenance",
        "warnings",
        "conflicts",
        "classification",
        "limits",
        "generatedAt",
    }
    assert set(external["facts"][0]) == {
        "factId",
        "metric",
        "value",
        "unit",
        "scale",
        "dimensions",
        "status",
        "semantics",
        "provenanceRefs",
        "freshness",
        "coverage",
        "qualityFlags",
    }
    assert set(external["facts"][0]["freshness"]) == {
        "dataThrough",
        "status",
        "materializedAt",
        "slaSeconds",
        "basis",
    }
    assert set(external["limits"]) == {
        "rowsReturned",
        "rowsTruncated",
        "factsReturned",
        "cellsReturned",
        "bytesReturned",
        "provenanceRefsReturned",
    }
    assert not any("_" in key for key in external)


def test_evidence_status_vocabulary_is_frozen():
    assert {status.value for status in EvidenceStatus} == {
        "OK",
        "EMPTY",
        "PARTIAL",
        "NOT_COMPUTABLE",
        "CONFLICT",
        "DENIED",
        "ERROR",
    }
