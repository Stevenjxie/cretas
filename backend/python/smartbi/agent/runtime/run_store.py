"""Tenant-bound persistence for restaurant agent runs and append-only events."""

from __future__ import annotations

import asyncio
import json
import re
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Callable, Mapping, Optional, Protocol

from .contracts import TrustedExecutionContext
from .run_contracts import (
    AgentEvent,
    AgentEventType,
    OutcomeStatus,
    RouteCode,
    RunRecord,
    RunState,
    RuntimeCounters,
    StructuredOutcome,
)


class RunStoreError(RuntimeError):
    pass


class RunAccessError(RunStoreError):
    pass


class UnsafeRunPayloadError(RunStoreError):
    pass


_MAX_PAYLOAD_BYTES = 32_768
_CODE = re.compile(r"^[A-Z][A-Z0-9_]{0,95}$")
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_METRIC = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,95}$")
_DECIMAL = re.compile(r"^-?(?:0|[1-9]\d*)(?:\.\d+)?$")
_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TERMINAL_EVENT_TYPES = {
    AgentEventType.RUN_COMPLETED,
    AgentEventType.RUN_FAILED,
    AgentEventType.RUN_CANCELLED,
    AgentEventType.BUDGET_EXCEEDED,
}

STALE_AFTER_SECONDS = 120
STALE_RUN_FAILURE_CODE = "PROCESS_INTERRUPTED_STALE_RUN"


class StaleRunReconcileResult(str, Enum):
    RECONCILED = "RECONCILED"
    NOT_STALE = "NOT_STALE"
    ALREADY_TERMINAL = "ALREADY_TERMINAL"


@dataclass(frozen=True)
class StaleRunReconciliation:
    result: StaleRunReconcileResult
    record: RunRecord


def _exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    if not isinstance(value, Mapping) or set(value) != expected:
        raise UnsafeRunPayloadError(
            f"{label} must contain exact keys {sorted(expected)}"
        )


def _bounded_json(value: Mapping[str, Any]) -> dict[str, Any]:
    normalized = dict(value)
    try:
        encoded = json.dumps(normalized, sort_keys=True, separators=(",", ":")).encode(
            "utf-8"
        )
    except (TypeError, ValueError) as exc:
        raise UnsafeRunPayloadError("payload is not JSON-safe") from exc
    if len(encoded) > _MAX_PAYLOAD_BYTES:
        raise UnsafeRunPayloadError("run payload exceeds safe byte limit")
    return normalized


def _code(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _CODE.fullmatch(value):
        raise UnsafeRunPayloadError(f"{label} must be a controlled code")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or not _IDENTIFIER.fullmatch(value):
        raise UnsafeRunPayloadError(f"{label} must be a bounded identifier")
    return value


def _bounded_int(value: Any, minimum: int, maximum: int, label: str) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or not minimum <= value <= maximum
    ):
        raise UnsafeRunPayloadError(f"{label} is outside its integer contract")
    return value


def safe_request_payload(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {"routeCode", "startDate", "endDate", "storeTopN", "dishTopN"}
    _exact_keys(value, expected, "create-run request")
    if value["routeCode"] != RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION.value:
        raise UnsafeRunPayloadError("unsupported route code")
    for key in ("startDate", "endDate"):
        if not isinstance(value[key], str) or not _ISO_DATE.fullmatch(value[key]):
            raise UnsafeRunPayloadError(f"{key} must be an ISO date")
    return _bounded_json(
        {
            "routeCode": value["routeCode"],
            "startDate": value["startDate"],
            "endDate": value["endDate"],
            "storeTopN": _bounded_int(value["storeTopN"], 1, 50, "storeTopN"),
            "dishTopN": _bounded_int(value["dishTopN"], 1, 20, "dishTopN"),
        }
    )


def safe_payload(value: Mapping[str, Any]) -> dict[str, Any]:
    """Compatibility alias with fail-closed create-run semantics."""

    return safe_request_payload(value)


_EVENT_KEYS = {
    AgentEventType.RUN_STARTED: {"routeCode"},
    AgentEventType.ROUTE_SELECTED: {"routeCode"},
    AgentEventType.PLAN_CREATED: {
        "routeCode",
        "stepCount",
        "maxRounds",
        "maxToolCalls",
    },
    AgentEventType.STEP_STARTED: {"round", "purposeCode"},
    AgentEventType.STEP_COMPLETED: {
        "round",
        "evidenceId",
        "evidenceStatus",
        "factCount",
        "evidenceBytes",
        "warningCodes",
    },
    AgentEventType.STEP_FAILED: {"failureCode"},
    AgentEventType.BUDGET_EXCEEDED: {
        "failureCode",
        "roundsUsed",
        "toolCallsUsed",
        "factsUsed",
        "evidenceBytesUsed",
    },
    AgentEventType.RUN_CANCELLED: {"failureCode"},
    AgentEventType.RUN_COMPLETED: {
        "status",
        "routeCode",
        "claims",
        "blockers",
        "observations",
        "attributionSupported",
    },
    AgentEventType.RUN_FAILED: {"failureCode"},
}


def safe_event_payload(
    event_type: AgentEventType, value: Mapping[str, Any]
) -> dict[str, Any]:
    _exact_keys(value, _EVENT_KEYS[event_type], f"{event_type.value} payload")
    if event_type is AgentEventType.RUN_COMPLETED:
        return safe_outcome_payload(value)
    result: dict[str, Any] = {}
    for key, item in value.items():
        if key in {"routeCode", "purposeCode", "failureCode", "evidenceStatus"}:
            result[key] = _code(item, key)
        elif key == "evidenceId":
            result[key] = _identifier(item, key)
        elif key == "warningCodes":
            if not isinstance(item, list) or len(item) > 100:
                raise UnsafeRunPayloadError("warningCodes must be a bounded code list")
            result[key] = [_code(code, "warningCode") for code in item]
        elif key in {"round", "maxRounds", "roundsUsed"}:
            result[key] = _bounded_int(item, 0, 2, key)
        elif key in {"stepCount", "maxToolCalls", "toolCallsUsed"}:
            result[key] = _bounded_int(item, 0, 10, key)
        elif key in {"factCount", "factsUsed"}:
            result[key] = _bounded_int(item, 0, 1_000_000, key)
        elif key in {"evidenceBytes", "evidenceBytesUsed"}:
            result[key] = _bounded_int(item, 0, 100_000_000, key)
        else:
            raise UnsafeRunPayloadError(f"unhandled event field: {key}")
    return _bounded_json(result)


def safe_outcome_payload(value: Mapping[str, Any]) -> dict[str, Any]:
    expected = {
        "status",
        "routeCode",
        "claims",
        "blockers",
        "observations",
        "attributionSupported",
    }
    _exact_keys(value, expected, "outcome")
    result: dict[str, Any] = {
        "status": _code(value["status"], "status"),
        "routeCode": _code(value["routeCode"], "routeCode"),
        "attributionSupported": value["attributionSupported"],
    }
    if not isinstance(result["attributionSupported"], bool):
        raise UnsafeRunPayloadError("attributionSupported must be boolean")
    for key in ("blockers", "observations"):
        item = value[key]
        if not isinstance(item, list) or len(item) > 100:
            raise UnsafeRunPayloadError(f"{key} must be a bounded code list")
        result[key] = [_code(code, key) for code in item]
    claims = value["claims"]
    if not isinstance(claims, list) or len(claims) > 100:
        raise UnsafeRunPayloadError("claims must be a bounded list")
    safe_claims = []
    claim_keys = {"statementCode", "metric", "value", "unit", "evidenceId", "factId"}
    for claim in claims:
        _exact_keys(claim, claim_keys, "persisted numeric claim")
        if not isinstance(claim["metric"], str) or not _METRIC.fullmatch(
            claim["metric"]
        ):
            raise UnsafeRunPayloadError("claim metric must be an identifier")
        if not isinstance(claim["value"], str) or not _DECIMAL.fullmatch(
            claim["value"]
        ):
            raise UnsafeRunPayloadError("claim value must be a decimal string")
        unit = claim["unit"]
        if unit is not None:
            unit = _code(unit, "claim unit")
        safe_claims.append(
            {
                "statementCode": _code(claim["statementCode"], "statementCode"),
                "metric": claim["metric"],
                "value": claim["value"],
                "unit": unit,
                "evidenceId": _identifier(claim["evidenceId"], "evidenceId"),
                "factId": _identifier(claim["factId"], "factId"),
            }
        )
    result["claims"] = safe_claims
    return _bounded_json(result)


def _terminal_event_payload(
    event_type: AgentEventType,
    summary: Mapping[str, Any],
    counters: RuntimeCounters,
    failure_code: Optional[str],
) -> dict[str, Any]:
    if event_type is AgentEventType.RUN_COMPLETED:
        return safe_event_payload(event_type, summary)
    if not failure_code:
        raise UnsafeRunPayloadError(
            "failure terminal requires a controlled failure code"
        )
    payload = {"failureCode": failure_code}
    if event_type is AgentEventType.BUDGET_EXCEEDED:
        payload.update(counters.safe_dict())
    return safe_event_payload(event_type, payload)


def _require_terminal_contract(
    terminal_state: RunState,
    terminal_event_type: AgentEventType,
    outcome: StructuredOutcome,
    failure_code: Optional[str],
) -> None:
    expected = {
        RunState.COMPLETED: (AgentEventType.RUN_COMPLETED, {"COMPLETE"}),
        RunState.PARTIAL: (
            AgentEventType.RUN_COMPLETED,
            {"PARTIAL", "NOT_COMPUTABLE"},
        ),
        RunState.FAILED: (AgentEventType.RUN_FAILED, {"FAILED"}),
        RunState.CANCELLED: (AgentEventType.RUN_CANCELLED, {"CANCELLED"}),
        RunState.BUDGET_EXCEEDED: (
            AgentEventType.BUDGET_EXCEEDED,
            {"BUDGET_EXCEEDED"},
        ),
    }
    if terminal_state not in expected:
        raise ValueError("CAS target must be terminal")
    event_type, statuses = expected[terminal_state]
    if terminal_event_type is not event_type or outcome.status.value not in statuses:
        raise RunStoreError("terminal state, outcome and event type must match")
    if terminal_state in {RunState.COMPLETED, RunState.PARTIAL}:
        if failure_code is not None:
            raise RunStoreError(
                "successful/partial terminal may not carry failure code"
            )
    elif not failure_code or not _CODE.fullmatch(failure_code):
        raise RunStoreError("failure terminal requires a controlled failure code")


def _require_context(context: TrustedExecutionContext) -> None:
    if not isinstance(context, TrustedExecutionContext):
        raise TypeError("trusted execution context is required")
    if context.business_type != "RESTAURANT":
        raise RunAccessError("restaurant run store requires business_type=RESTAURANT")


def _require_monotonic_counters(
    previous: RuntimeCounters, current: RuntimeCounters
) -> None:
    if (
        current.rounds_used < previous.rounds_used
        or current.tool_calls_used < previous.tool_calls_used
        or current.facts_used < previous.facts_used
        or current.evidence_bytes_used < previous.evidence_bytes_used
    ):
        raise RunStoreError("run counters may not decrease")


class RunStore(Protocol):
    async def create_run(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        route_code: RouteCode,
        safe_request: Mapping[str, Any],
    ) -> RunRecord:
        ...

    async def load_run(
        self, run_id: str, context: TrustedExecutionContext
    ) -> RunRecord:
        ...

    async def events_for(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        *,
        after_sequence: int = 0,
    ) -> tuple[AgentEvent, ...]:
        ...

    async def append_event(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        event_type: AgentEventType,
        payload: Mapping[str, Any],
        *,
        counters: RuntimeCounters,
        step_id: Optional[str] = None,
        tool_name: Optional[str] = None,
    ) -> AgentEvent:
        ...

    async def compare_and_set_terminal(
        self,
        run_id: str,
        context: TrustedExecutionContext,
        *,
        expected_state: RunState,
        terminal_state: RunState,
        outcome: StructuredOutcome,
        counters: RuntimeCounters,
        terminal_event_type: AgentEventType,
        failure_code: Optional[str] = None,
    ) -> bool:
        ...

    async def reconcile_stale_run(
        self, run_id: str, context: TrustedExecutionContext
    ) -> StaleRunReconciliation:
        ...


class InMemoryRunStore:
    """Concurrency-safe fake with the same tenant and terminal invariants."""

    def __init__(self, *, clock: Callable[[], datetime] | None = None) -> None:
        self._runs: dict[str, RunRecord] = {}
        self._events: dict[str, list[AgentEvent]] = {}
        self._updated_at: dict[str, datetime] = {}
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._lock = asyncio.Lock()

    def _now(self) -> datetime:
        value = self._clock()
        if value.tzinfo is None or value.utcoffset() is None:
            raise RunStoreError("run store clock must return timezone-aware datetime")
        return value.astimezone(timezone.utc)

    async def create_run(self, run_id, context, route_code, safe_request):
        _require_context(context)
        request = safe_request_payload(safe_request)
        async with self._lock:
            if run_id in self._runs:
                raise RunStoreError("run id already exists")
            now = self._now()
            record = RunRecord(
                run_id=run_id,
                factory_id=context.factory_id,
                state=RunState.RUNNING,
                route_code=route_code,
                safe_request=request,
                counters=RuntimeCounters(),
            )
            self._runs[run_id] = record
            self._events[run_id] = []
            self._updated_at[run_id] = now
            return record

    async def load_run(self, run_id, context):
        _require_context(context)
        async with self._lock:
            return self._owned(run_id, context)

    async def append_event(
        self,
        run_id,
        context,
        event_type,
        payload,
        *,
        counters,
        step_id=None,
        tool_name=None,
    ):
        _require_context(context)
        if event_type in _TERMINAL_EVENT_TYPES:
            raise RunStoreError("terminal events require atomic terminal CAS")
        body = safe_event_payload(event_type, payload)
        async with self._lock:
            record = self._owned(run_id, context)
            if record.state.terminal:
                raise RunStoreError("events may not be appended after terminal state")
            _require_monotonic_counters(record.counters, counters)
            now = self._now()
            sequence = record.next_event_sequence + 1
            event = AgentEvent(
                run_id,
                context.factory_id,
                sequence,
                event_type,
                body,
                step_id,
                tool_name,
            )
            self._events[run_id].append(event)
            self._runs[run_id] = replace(
                record, next_event_sequence=sequence, counters=counters
            )
            self._updated_at[run_id] = now
            return event

    async def compare_and_set_terminal(
        self,
        run_id,
        context,
        *,
        expected_state,
        terminal_state,
        outcome,
        counters,
        terminal_event_type,
        failure_code=None,
    ):
        _require_context(context)
        _require_terminal_contract(
            terminal_state, terminal_event_type, outcome, failure_code
        )
        summary = safe_outcome_payload(outcome.persistence_dict())
        event_body = _terminal_event_payload(
            terminal_event_type, summary, counters, failure_code
        )
        async with self._lock:
            record = self._owned(run_id, context)
            if record.state is not expected_state:
                return False
            _require_monotonic_counters(record.counters, counters)
            now = self._now()
            sequence = record.next_event_sequence + 1
            self._events[run_id].append(
                AgentEvent(
                    run_id,
                    context.factory_id,
                    sequence,
                    terminal_event_type,
                    event_body,
                )
            )
            self._runs[run_id] = replace(
                record,
                state=terminal_state,
                outcome_summary=summary,
                counters=counters,
                failure_code=failure_code,
                next_event_sequence=sequence,
            )
            self._updated_at[run_id] = now
            return True

    async def reconcile_stale_run(self, run_id, context):
        _require_context(context)
        async with self._lock:
            record = self._owned(run_id, context)
            if record.state.terminal:
                return StaleRunReconciliation(
                    StaleRunReconcileResult.ALREADY_TERMINAL, record
                )
            now = self._now()
            cutoff = now - timedelta(seconds=STALE_AFTER_SECONDS)
            if self._updated_at[run_id] > cutoff:
                return StaleRunReconciliation(StaleRunReconcileResult.NOT_STALE, record)

            outcome = StructuredOutcome(
                status=OutcomeStatus.FAILED,
                route_code=record.route_code,
                blockers=(STALE_RUN_FAILURE_CODE,),
            )
            summary = safe_outcome_payload(outcome.persistence_dict())
            event_body = safe_event_payload(
                AgentEventType.RUN_FAILED,
                {"failureCode": STALE_RUN_FAILURE_CODE},
            )
            sequence = record.next_event_sequence + 1
            event = AgentEvent(
                run_id,
                context.factory_id,
                sequence,
                AgentEventType.RUN_FAILED,
                event_body,
            )
            reconciled = replace(
                record,
                state=RunState.FAILED,
                outcome_summary=summary,
                failure_code=STALE_RUN_FAILURE_CODE,
                next_event_sequence=sequence,
            )
            self._events[run_id].append(event)
            self._runs[run_id] = reconciled
            self._updated_at[run_id] = now
            return StaleRunReconciliation(
                StaleRunReconcileResult.RECONCILED, reconciled
            )

    async def events_for(self, run_id, context, *, after_sequence=0):
        _require_context(context)
        _require_after_sequence(after_sequence)
        async with self._lock:
            self._owned(run_id, context)
            return tuple(
                event
                for event in self._events[run_id]
                if event.sequence > after_sequence
            )

    def _owned(self, run_id, context):
        record = self._runs.get(run_id)
        if record is None or record.factory_id != context.factory_id:
            raise RunAccessError("run does not exist in trusted tenant")
        return record


class PostgresRunStore:
    """asyncpg store that binds RLS and mutation to the same transaction."""

    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def create_run(self, run_id, context, route_code, safe_request):
        _require_context(context)
        request = safe_request_payload(safe_request)
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    """
                    WITH statement_time AS (
                        SELECT clock_timestamp() AS observed_at
                    )
                    INSERT INTO smart_bi_agent_run (
                        run_id, factory_id, business_type, correlation_id,
                        route_code, state, sanitized_request,
                        created_at, updated_at
                    )
                    SELECT $1::uuid, $2, 'RESTAURANT', $3, $4, 'RUNNING',
                           $5::jsonb, observed_at, observed_at
                    FROM statement_time
                    RETURNING *
                    """,
                    run_id,
                    context.factory_id,
                    context.correlation_id,
                    route_code.value,
                    self._json(request),
                )
        return self._record(row)

    async def load_run(self, run_id, context):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    "SELECT * FROM smart_bi_agent_run WHERE run_id = $1::uuid AND factory_id = $2",
                    run_id,
                    context.factory_id,
                )
        if row is None:
            raise RunAccessError("run does not exist in trusted tenant")
        return self._record(row)

    async def append_event(
        self,
        run_id,
        context,
        event_type,
        payload,
        *,
        counters,
        step_id=None,
        tool_name=None,
    ):
        _require_context(context)
        if event_type in _TERMINAL_EVENT_TYPES:
            raise RunStoreError("terminal events require atomic terminal CAS")
        body = safe_event_payload(event_type, payload)
        if step_id is not None:
            _identifier(step_id, "step_id")
        if tool_name is not None:
            _identifier(tool_name, "tool_name")
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                observed = await connection.fetchrow(
                    """
                    SELECT version
                    FROM smart_bi_agent_run
                    WHERE run_id = $1::uuid AND factory_id = $2
                      AND state = 'RUNNING'
                    FOR UPDATE
                    """,
                    run_id,
                    context.factory_id,
                )
                if observed is None:
                    raise RunAccessError("run is absent, cross-tenant, or terminal")
                observed_at = await connection.fetchval("SELECT clock_timestamp()")
                row = await connection.fetchrow(
                    """
                    UPDATE smart_bi_agent_run
                    SET next_event_sequence = next_event_sequence + 1,
                        rounds_used = $3, tool_calls_used = $4,
                        facts_used = $5, evidence_bytes_used = $6,
                        updated_at = $8::timestamptz,
                        version = version + 1
                    WHERE run_id = $1::uuid AND factory_id = $2
                      AND state = 'RUNNING' AND version = $7
                    RETURNING next_event_sequence
                    """,
                    run_id,
                    context.factory_id,
                    counters.rounds_used,
                    counters.tool_calls_used,
                    counters.facts_used,
                    counters.evidence_bytes_used,
                    int(observed["version"]),
                    observed_at,
                )
                if row is None:
                    raise RunStoreError("run changed while appending event")
                sequence = int(row["next_event_sequence"])
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id, factory_id, event_sequence, event_type,
                        step_id, tool_name, payload
                    ) VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::jsonb)
                    """,
                    run_id,
                    context.factory_id,
                    sequence,
                    event_type.value,
                    step_id,
                    tool_name,
                    self._json(body),
                )
        return AgentEvent(
            run_id, context.factory_id, sequence, event_type, body, step_id, tool_name
        )

    async def compare_and_set_terminal(
        self,
        run_id,
        context,
        *,
        expected_state,
        terminal_state,
        outcome,
        counters,
        terminal_event_type,
        failure_code=None,
    ):
        _require_context(context)
        _require_terminal_contract(
            terminal_state, terminal_event_type, outcome, failure_code
        )
        summary = safe_outcome_payload(outcome.persistence_dict())
        event_body = _terminal_event_payload(
            terminal_event_type, summary, counters, failure_code
        )
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                observed = await connection.fetchrow(
                    """
                    SELECT state, version
                    FROM smart_bi_agent_run
                    WHERE run_id = $1::uuid AND factory_id = $2
                    FOR UPDATE
                    """,
                    run_id,
                    context.factory_id,
                )
                if observed is None or observed["state"] != expected_state.value:
                    return False
                observed_at = await connection.fetchval("SELECT clock_timestamp()")
                row = await connection.fetchrow(
                    """
                    UPDATE smart_bi_agent_run
                    SET state = $4, outcome_summary = $5::jsonb,
                        failure_code = $6, rounds_used = $7,
                        tool_calls_used = $8, facts_used = $9,
                        evidence_bytes_used = $10,
                        next_event_sequence = next_event_sequence + 1,
                        completed_at = $12::timestamptz,
                        updated_at = $12::timestamptz,
                        version = version + 1
                    WHERE run_id = $1::uuid AND factory_id = $2
                      AND state = $3 AND version = $11
                    RETURNING next_event_sequence
                    """,
                    run_id,
                    context.factory_id,
                    expected_state.value,
                    terminal_state.value,
                    self._json(summary),
                    failure_code,
                    counters.rounds_used,
                    counters.tool_calls_used,
                    counters.facts_used,
                    counters.evidence_bytes_used,
                    int(observed["version"]),
                    observed_at,
                )
                if row is None:
                    raise RunStoreError("run changed during terminal transition")
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id, factory_id, event_sequence, event_type, payload
                    ) VALUES ($1::uuid, $2, $3, $4, $5::jsonb)
                    """,
                    run_id,
                    context.factory_id,
                    int(row["next_event_sequence"]),
                    terminal_event_type.value,
                    self._json(event_body),
                )
        return True

    async def reconcile_stale_run(self, run_id, context):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                observed = await connection.fetchrow(
                    """
                    SELECT run.*
                    FROM smart_bi_agent_run AS run
                    WHERE run.run_id = $1::uuid AND run.factory_id = $2
                    FOR UPDATE OF run
                    """,
                    run_id,
                    context.factory_id,
                )
                if observed is None:
                    raise RunAccessError("run does not exist in trusted tenant")
                server_now = await connection.fetchval("SELECT clock_timestamp()")
                record = self._record(observed)
                if record.state.terminal:
                    return StaleRunReconciliation(
                        StaleRunReconcileResult.ALREADY_TERMINAL, record
                    )

                cutoff = server_now - timedelta(seconds=STALE_AFTER_SECONDS)
                if observed["updated_at"] > cutoff:
                    return StaleRunReconciliation(
                        StaleRunReconcileResult.NOT_STALE, record
                    )

                outcome = StructuredOutcome(
                    status=OutcomeStatus.FAILED,
                    route_code=record.route_code,
                    blockers=(STALE_RUN_FAILURE_CODE,),
                )
                summary = safe_outcome_payload(outcome.persistence_dict())
                event_body = safe_event_payload(
                    AgentEventType.RUN_FAILED,
                    {"failureCode": STALE_RUN_FAILURE_CODE},
                )
                updated = await connection.fetchrow(
                    """
                    UPDATE smart_bi_agent_run
                    SET state = 'FAILED', outcome_summary = $6::jsonb,
                        failure_code = $7,
                        next_event_sequence = next_event_sequence + 1,
                        completed_at = $5::timestamptz,
                        updated_at = $5::timestamptz, version = version + 1
                    WHERE run_id = $1::uuid AND factory_id = $2
                      AND state = 'RUNNING' AND version = $3
                      AND updated_at = $4
                      AND updated_at <= $5::timestamptz
                          - make_interval(secs => $8::double precision)
                    RETURNING *
                    """,
                    run_id,
                    context.factory_id,
                    int(observed["version"]),
                    observed["updated_at"],
                    server_now,
                    self._json(summary),
                    STALE_RUN_FAILURE_CODE,
                    STALE_AFTER_SECONDS,
                )
                if updated is None:
                    raise RunStoreError("stale run changed during reconciliation")
                sequence = int(updated["next_event_sequence"])
                await connection.execute(
                    """
                    INSERT INTO smart_bi_agent_event (
                        run_id, factory_id, event_sequence, event_type, payload
                    ) VALUES ($1::uuid, $2, $3, 'RUN_FAILED', $4::jsonb)
                    """,
                    run_id,
                    context.factory_id,
                    sequence,
                    self._json(event_body),
                )
                reconciled = self._record(updated)
                return StaleRunReconciliation(
                    StaleRunReconcileResult.RECONCILED, reconciled
                )

    async def events_for(self, run_id, context, *, after_sequence=0):
        _require_context(context)
        _require_after_sequence(after_sequence)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                owned = await connection.fetchrow(
                    """
                    SELECT run_id FROM smart_bi_agent_run
                    WHERE run_id = $1::uuid AND factory_id = $2
                    """,
                    run_id,
                    context.factory_id,
                )
                if owned is None:
                    raise RunAccessError("run does not exist in trusted tenant")
                rows = await connection.fetch(
                    """
                    SELECT run_id, factory_id, event_sequence, event_type,
                           payload, step_id, tool_name
                    FROM smart_bi_agent_event
                    WHERE run_id = $1::uuid AND factory_id = $2
                      AND event_sequence > $3
                    ORDER BY event_sequence ASC
                    """,
                    run_id,
                    context.factory_id,
                    after_sequence,
                )
        return tuple(self._event(row) for row in rows)

    @staticmethod
    async def _bind(connection, context):
        await connection.execute(
            "SELECT set_config('app.factory_id', $1, true)", context.factory_id
        )

    @staticmethod
    def _json(value):
        return json.dumps(value, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def _record(row):
        request = row["sanitized_request"]
        summary = row["outcome_summary"]
        if isinstance(request, str):
            request = json.loads(request)
        if isinstance(summary, str):
            summary = json.loads(summary)
        request = safe_request_payload(request)
        if summary is not None:
            summary = safe_outcome_payload(summary)
        failure_code = row["failure_code"]
        if failure_code is not None:
            failure_code = _code(failure_code, "failure_code")
        return RunRecord(
            run_id=str(row["run_id"]),
            factory_id=str(row["factory_id"]),
            state=RunState(row["state"]),
            route_code=RouteCode(row["route_code"]),
            safe_request=request,
            counters=RuntimeCounters(
                int(row["rounds_used"]),
                int(row["tool_calls_used"]),
                int(row["facts_used"]),
                int(row["evidence_bytes_used"]),
            ),
            next_event_sequence=int(row["next_event_sequence"]),
            outcome_summary=summary,
            failure_code=failure_code,
        )

    @staticmethod
    def _event(row):
        payload = row["payload"]
        if isinstance(payload, str):
            payload = json.loads(payload)
        event_type = AgentEventType(row["event_type"])
        payload = safe_event_payload(event_type, payload)
        step_id = row["step_id"]
        tool_name = row["tool_name"]
        if step_id is not None:
            step_id = _identifier(step_id, "step_id")
        if tool_name is not None:
            tool_name = _identifier(tool_name, "tool_name")
        return AgentEvent(
            run_id=str(row["run_id"]),
            factory_id=str(row["factory_id"]),
            sequence=int(row["event_sequence"]),
            event_type=event_type,
            payload=payload,
            step_id=step_id,
            tool_name=tool_name,
        )


def _require_after_sequence(value: int) -> None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("after_sequence must be a non-negative integer")
