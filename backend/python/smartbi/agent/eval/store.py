"""Tenant-bound persistence for Eval Sets, experiments and existing run traces."""

from __future__ import annotations

import asyncio
import json
import re
import uuid
from dataclasses import replace
from typing import Any, Mapping, Protocol, Sequence

from .contracts import AgentOpsContext, EvalSetRecord, ExperimentRecord, utc_iso
from .validation import (
    canonical_digest,
    validate_actual_snapshots,
    validate_cases,
    validate_config_snapshot,
    validate_evaluator,
    validate_name,
    validate_request_id,
    validate_runner_bounds_snapshot,
)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class AgentOpsStoreError(RuntimeError):
    pass


class AgentOpsAccessError(AgentOpsStoreError):
    pass


class AgentOpsConflictError(AgentOpsStoreError):
    pass


class AgentOpsStore(Protocol):
    async def create_eval_set(self, context: AgentOpsContext, record: EvalSetRecord) -> EvalSetRecord: ...
    async def list_eval_sets(self, context: AgentOpsContext) -> tuple[EvalSetRecord, ...]: ...
    async def get_eval_set(self, context: AgentOpsContext, eval_set_id: str) -> EvalSetRecord: ...
    async def save_experiment(self, context: AgentOpsContext, record: ExperimentRecord) -> ExperimentRecord: ...
    async def preflight_experiment_request(
        self,
        context: AgentOpsContext,
        request_id: str,
        request_digest: str,
    ) -> ExperimentRecord | None: ...
    async def list_experiments(self, context: AgentOpsContext) -> tuple[ExperimentRecord, ...]: ...
    async def get_experiment(self, context: AgentOpsContext, experiment_id: str) -> ExperimentRecord: ...
    async def load_trace(
        self, context: AgentOpsContext, run_id: str, *, after_sequence: int, limit: int
    ) -> Mapping[str, Any]: ...


def new_eval_set_record(
    context: AgentOpsContext,
    *,
    name: str,
    version: int,
    description: str,
    cases: Sequence[Mapping[str, Any]],
    content_digest: str,
    request_id: str,
    request_digest: str,
) -> EvalSetRecord:
    return EvalSetRecord(
        eval_set_id=str(uuid.uuid4()),
        factory_id=context.factory_id,
        name=name,
        version=version,
        description=description,
        cases=tuple(dict(case) for case in cases),
        content_digest=content_digest,
        request_id=request_id,
        request_digest=request_digest,
        created_by=context.user_id,
        created_at=utc_iso(),
    )


def new_experiment_record(
    context: AgentOpsContext,
    *,
    eval_set: EvalSetRecord,
    evaluator_version: str,
    evaluator_build: str,
    snapshot_digest: str,
    request_id: str,
    request_digest: str,
    operation_kind: str,
    source_experiment_id: str | None,
    config_snapshot: Mapping[str, Any],
    actual_snapshots: Mapping[str, Mapping[str, Any]],
    runner_bounds: Mapping[str, Any],
    aggregate: Mapping[str, Any],
    case_results: Sequence[Mapping[str, Any]],
) -> ExperimentRecord:
    return ExperimentRecord(
        experiment_id=str(uuid.uuid4()),
        factory_id=context.factory_id,
        eval_set_id=eval_set.eval_set_id,
        eval_set_name=eval_set.name,
        eval_set_version=eval_set.version,
        eval_set_digest=eval_set.content_digest,
        evaluator_version=evaluator_version,
        evaluator_build=evaluator_build,
        snapshot_digest=snapshot_digest,
        request_id=request_id,
        request_digest=request_digest,
        operation_kind=operation_kind,
        source_experiment_id=source_experiment_id,
        config_snapshot=dict(config_snapshot),
        actual_snapshots={key: dict(value) for key, value in actual_snapshots.items()},
        runner_bounds=dict(runner_bounds),
        aggregate=dict(aggregate),
        case_results=tuple(dict(item) for item in case_results),
        created_by=context.user_id,
        created_at=utc_iso(),
    )


class InMemoryAgentOpsStore:
    """Concurrency-safe test store preserving production tenant semantics."""

    def __init__(self) -> None:
        self._eval_sets: dict[str, EvalSetRecord] = {}
        self._experiments: dict[str, ExperimentRecord] = {}
        self._eval_requests: dict[tuple[str, str, str], str] = {}
        self._experiment_requests: dict[tuple[str, str, str], str] = {}
        self._traces: dict[str, Mapping[str, Any]] = {}
        self._lock = asyncio.Lock()

    def seed_trace(self, factory_id: str, run_id: str, trace: Mapping[str, Any]) -> None:
        self._traces[run_id] = {**dict(trace), "factoryId": factory_id}

    async def create_eval_set(self, context, record):
        _require_context(context)
        record = _validate_eval_set_record(record)
        if record.factory_id != context.factory_id or record.created_by != context.user_id:
            raise AgentOpsAccessError("tenant mismatch")
        async with self._lock:
            request_key = (context.factory_id, context.user_id, record.request_id)
            existing_id = self._eval_requests.get(request_key)
            if existing_id is not None:
                existing = self._eval_sets[existing_id]
                if existing.request_digest != record.request_digest:
                    raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
                return existing
            if any(
                item.factory_id == context.factory_id
                and item.name.lower() == record.name.lower()
                and item.version == record.version
                for item in self._eval_sets.values()
            ):
                raise AgentOpsConflictError("EVAL_SET_VERSION_EXISTS")
            self._eval_sets[record.eval_set_id] = record
            self._eval_requests[request_key] = record.eval_set_id
            return record

    async def list_eval_sets(self, context):
        _require_context(context)
        async with self._lock:
            return tuple(sorted(
                (item for item in self._eval_sets.values() if item.factory_id == context.factory_id),
                key=lambda item: (item.name.lower(), -item.version),
            ))

    async def get_eval_set(self, context, eval_set_id):
        _require_context(context)
        async with self._lock:
            item = self._eval_sets.get(eval_set_id)
            if item is None or item.factory_id != context.factory_id:
                raise AgentOpsAccessError("eval set not found")
            return item

    async def save_experiment(self, context, record):
        _require_context(context)
        if record.factory_id != context.factory_id or record.created_by != context.user_id:
            raise AgentOpsAccessError("tenant mismatch")
        record = _validate_experiment_record(record)
        async with self._lock:
            request_key = (context.factory_id, context.user_id, record.request_id)
            existing_id = self._experiment_requests.get(request_key)
            if existing_id is not None:
                existing = self._experiments[existing_id]
                if existing.request_digest != record.request_digest:
                    raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
                return existing
            self._experiments[record.experiment_id] = record
            self._experiment_requests[request_key] = record.experiment_id
            return record

    async def preflight_experiment_request(
        self, context, request_id, request_digest
    ):
        _require_context(context)
        request_id, request_digest = _validate_request_lookup(
            request_id, request_digest
        )
        async with self._lock:
            existing_id = self._experiment_requests.get(
                (context.factory_id, context.user_id, request_id)
            )
            if existing_id is None:
                return None
            existing = _validate_experiment_record(
                self._experiments[existing_id]
            )
            if existing.request_digest != request_digest:
                raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
            return existing

    async def list_experiments(self, context):
        _require_context(context)
        async with self._lock:
            return tuple(sorted(
                (
                    _validate_experiment_record(item)
                    for item in self._experiments.values()
                    if item.factory_id == context.factory_id
                ),
                key=lambda item: item.created_at,
                reverse=True,
            ))

    async def get_experiment(self, context, experiment_id):
        _require_context(context)
        async with self._lock:
            item = self._experiments.get(experiment_id)
            if item is None or item.factory_id != context.factory_id:
                raise AgentOpsAccessError("experiment not found")
            return _validate_experiment_record(item)

    async def load_trace(self, context, run_id, *, after_sequence, limit):
        _require_admin_context(context)
        _require_after_sequence(after_sequence)
        _require_limit(limit)
        async with self._lock:
            item = self._traces.get(run_id)
            if item is None or item.get("factoryId") != context.factory_id:
                raise AgentOpsAccessError("run not found")
            result = _safe_trace_record(
                item, after_sequence=after_sequence, limit=limit
            )
            return result


class PostgresAgentOpsStore:
    """asyncpg store that binds app.factory_id inside every transaction."""

    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def create_eval_set(self, context, record):
        _require_context(context)
        record = _validate_eval_set_record(record)
        if record.factory_id != context.factory_id or record.created_by != context.user_id:
            raise AgentOpsAccessError("tenant mismatch")
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                try:
                    row = await connection.fetchrow(
                        """
                        INSERT INTO smart_bi_agent_eval_set (
                            eval_set_id, factory_id, name, version, description,
                            cases, content_digest, request_id, request_digest,
                            created_by, created_at
                        ) VALUES (
                            $1::uuid,$2,$3,$4,$5,$6::jsonb,$7,$8::uuid,$9,$10,
                            $11::text::timestamptz
                        )
                        ON CONFLICT (factory_id, created_by, request_id) DO NOTHING
                        RETURNING *
                        """,
                        record.eval_set_id, context.factory_id, record.name,
                        record.version, record.description, self._json(record.cases),
                        record.content_digest, record.request_id, record.request_digest,
                        record.created_by, record.created_at,
                    )
                except Exception as exc:
                    if getattr(exc, "sqlstate", None) == "23505":
                        raise AgentOpsConflictError("EVAL_SET_VERSION_EXISTS") from exc
                    raise
                if row is None:
                    row = await connection.fetchrow(
                        """SELECT * FROM smart_bi_agent_eval_set
                           WHERE factory_id=$1 AND created_by=$2 AND request_id=$3::uuid""",
                        context.factory_id, context.user_id, record.request_id,
                    )
                    if row is None:
                        raise AgentOpsStoreError("idempotent eval set lookup failed")
                    existing = self._eval_set(row)
                    if existing.request_digest != record.request_digest:
                        raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
                    return existing
        return self._eval_set(row)

    async def list_eval_sets(self, context):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                rows = await connection.fetch(
                    "SELECT * FROM smart_bi_agent_eval_set WHERE factory_id=$1 ORDER BY lower(name), version DESC LIMIT 200",
                    context.factory_id,
                )
        return tuple(self._eval_set(row) for row in rows)

    async def get_eval_set(self, context, eval_set_id):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    "SELECT * FROM smart_bi_agent_eval_set WHERE eval_set_id=$1::uuid AND factory_id=$2",
                    eval_set_id, context.factory_id,
                )
        if row is None:
            raise AgentOpsAccessError("eval set not found")
        return self._eval_set(row)

    async def save_experiment(self, context, record):
        _require_context(context)
        record = _validate_experiment_record(record)
        if record.factory_id != context.factory_id or record.created_by != context.user_id:
            raise AgentOpsAccessError("tenant mismatch")
        async with self._pool.acquire() as connection:
            async with connection.transaction():
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    """
                    INSERT INTO smart_bi_agent_experiment (
                        experiment_id, factory_id, eval_set_id, eval_set_name,
                        eval_set_version, eval_set_digest, evaluator_version,
                        evaluator_build, snapshot_digest, config_snapshot,
                        actual_snapshots, runner_bounds, aggregate, case_results,
                        request_id, request_digest, operation_kind,
                        source_experiment_id, created_by, created_at
                    ) VALUES (
                        $1::uuid,$2,$3::uuid,$4,$5,$6,$7,$8,$9,$10::jsonb,
                        $11::jsonb,$12::jsonb,$13::jsonb,$14::jsonb,$15::uuid,$16,$17,
                        $18::uuid,$19,$20::text::timestamptz
                    )
                    ON CONFLICT (factory_id, created_by, request_id) DO NOTHING
                    RETURNING *
                    """,
                    record.experiment_id, context.factory_id, record.eval_set_id,
                    record.eval_set_name, record.eval_set_version, record.eval_set_digest,
                    record.evaluator_version, record.evaluator_build, record.snapshot_digest,
                    self._json(record.config_snapshot), self._json(record.actual_snapshots),
                    self._json(record.runner_bounds), self._json(record.aggregate),
                    self._json(record.case_results), record.request_id,
                    record.request_digest, record.operation_kind,
                    record.source_experiment_id, record.created_by, record.created_at,
                )
                if row is None:
                    row = await connection.fetchrow(
                        """SELECT * FROM smart_bi_agent_experiment
                           WHERE factory_id=$1 AND created_by=$2 AND request_id=$3::uuid""",
                        context.factory_id, context.user_id, record.request_id,
                    )
                    if row is None:
                        raise AgentOpsStoreError("idempotent experiment lookup failed")
                    existing = self._experiment(row)
                    if existing.request_digest != record.request_digest:
                        raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
                    return existing
        return self._experiment(row)

    async def preflight_experiment_request(
        self, context, request_id, request_digest
    ):
        _require_context(context)
        request_id, request_digest = _validate_request_lookup(
            request_id, request_digest
        )
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    """SELECT * FROM smart_bi_agent_experiment
                       WHERE factory_id=$1 AND created_by=$2 AND request_id=$3::uuid""",
                    context.factory_id, context.user_id, request_id,
                )
        if row is None:
            return None
        existing = self._experiment(row)
        if existing.request_digest != request_digest:
            raise AgentOpsConflictError("IDEMPOTENCY_KEY_REUSED")
        return existing

    async def list_experiments(self, context):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                rows = await connection.fetch(
                    "SELECT * FROM smart_bi_agent_experiment WHERE factory_id=$1 ORDER BY created_at DESC LIMIT 200",
                    context.factory_id,
                )
        return tuple(self._experiment(row) for row in rows)

    async def get_experiment(self, context, experiment_id):
        _require_context(context)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind(connection, context)
                row = await connection.fetchrow(
                    "SELECT * FROM smart_bi_agent_experiment WHERE experiment_id=$1::uuid AND factory_id=$2",
                    experiment_id, context.factory_id,
                )
        if row is None:
            raise AgentOpsAccessError("experiment not found")
        return self._experiment(row)

    async def load_trace(self, context, run_id, *, after_sequence, limit):
        _require_admin_context(context)
        _require_after_sequence(after_sequence)
        _require_limit(limit)
        async with self._pool.acquire() as connection:
            async with connection.transaction(readonly=True):
                await self._bind_trace(connection, context)
                run = await connection.fetchrow(
                    """SELECT run_id,factory_id,route_code,state,correlation_id,
                              sanitized_request,outcome_summary,failure_code,
                              rounds_used,tool_calls_used,facts_used,evidence_bytes_used,
                              created_at,updated_at,completed_at
                       FROM smart_bi_agent_run WHERE run_id=$1::uuid AND factory_id=$2""",
                    run_id, context.factory_id,
                )
                if run is None:
                    raise AgentOpsAccessError("run not found")
                events = await connection.fetch(
                    """SELECT event_sequence,event_type,step_id,tool_name,payload,created_at
                       FROM smart_bi_agent_event
                       WHERE run_id=$1::uuid AND factory_id=$2
                         AND event_sequence > $3
                       ORDER BY event_sequence LIMIT $4""",
                    run_id, context.factory_id, after_sequence, limit + 1,
                )
        has_more = len(events) > limit
        events = events[:limit]
        safe = _redact_trace({
            "runId": str(run["run_id"]),
            "routeCode": run["route_code"],
            "state": run["state"],
            "correlationId": run["correlation_id"],
            "inputSummary": _safe_input_summary(self._value(run["sanitized_request"])),
            "outcome": _safe_outcome(self._value(run["outcome_summary"])),
            "failureCode": run["failure_code"],
            "counters": {
                "roundsUsed": run["rounds_used"], "toolCallsUsed": run["tool_calls_used"],
                "factsUsed": run["facts_used"], "evidenceBytesUsed": run["evidence_bytes_used"],
            },
            "createdAt": run["created_at"].isoformat(),
            "updatedAt": run["updated_at"].isoformat(),
            "completedAt": run["completed_at"].isoformat() if run["completed_at"] else None,
            "events": [
                {
                    "sequence": item["event_sequence"], "eventType": item["event_type"],
                    "stepId": item["step_id"], "toolName": item["tool_name"],
                    "payload": _safe_event_payload(self._value(item["payload"])),
                    "createdAt": item["created_at"].isoformat(),
                }
                for item in events
            ],
        })
        safe["page"] = _trace_page(
            after_sequence, limit, safe["events"], has_more=has_more
        )
        return safe

    @staticmethod
    async def _bind(connection, context):
        await connection.execute("SELECT set_config('app.factory_id',$1,true)", context.factory_id)

    @staticmethod
    async def _bind_trace(connection, context):
        await connection.execute("SELECT set_config('app.factory_id',$1,true)", context.factory_id)
        await connection.execute("SELECT set_config('app.user_id',$1,true)", context.user_id)
        await connection.execute("SELECT set_config('app.actor_role',$1,true)", context.role)
        await connection.execute("SELECT set_config('app.agent_ops_audit','true',true)")

    @staticmethod
    def _json(value):
        return json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
        )

    @staticmethod
    def _value(value):
        return json.loads(value) if isinstance(value, str) else value

    @classmethod
    def _eval_set(cls, row):
        cases = validate_cases(cls._value(row["cases"]))
        if canonical_digest(cases) != row["content_digest"]:
            raise AgentOpsStoreError("persisted eval set digest mismatch")
        return _validate_eval_set_record(EvalSetRecord(
            eval_set_id=str(row["eval_set_id"]),
            factory_id=str(row["factory_id"]),
            name=row["name"],
            version=int(row["version"]),
            description=row["description"],
            cases=tuple(cases),
            content_digest=row["content_digest"],
            request_id=str(row["request_id"]),
            request_digest=row["request_digest"],
            created_by=str(row["created_by"]),
            created_at=row["created_at"].isoformat(),
        ))

    @classmethod
    def _experiment(cls, row):
        record = ExperimentRecord(
            experiment_id=str(row["experiment_id"]),
            factory_id=str(row["factory_id"]),
            eval_set_id=str(row["eval_set_id"]),
            eval_set_name=row["eval_set_name"],
            eval_set_version=int(row["eval_set_version"]),
            eval_set_digest=row["eval_set_digest"],
            evaluator_version=row["evaluator_version"],
            evaluator_build=row["evaluator_build"],
            snapshot_digest=row["snapshot_digest"],
            request_id=str(row["request_id"]),
            request_digest=row["request_digest"],
            operation_kind=row["operation_kind"],
            source_experiment_id=(
                str(row["source_experiment_id"])
                if row["source_experiment_id"] is not None else None
            ),
            config_snapshot=cls._value(row["config_snapshot"]),
            actual_snapshots=cls._value(row["actual_snapshots"]),
            runner_bounds=cls._value(row["runner_bounds"]),
            aggregate=cls._value(row["aggregate"]),
            case_results=tuple(cls._value(row["case_results"])),
            created_by=str(row["created_by"]),
            created_at=row["created_at"].isoformat(),
        )
        return _validate_experiment_record(record)


_SENSITIVE = re.compile(
    r"(?:prompt|question|raw[_-]?request|secret|token|password|authorization|cookie|"
    r"api[_-]?key|credential|member[_-]?id|review[_-]?text)",
    re.IGNORECASE,
)


def _redact_trace(value: Mapping[str, Any]) -> dict[str, Any]:
    def clean(item: Any, key: str = "") -> Any:
        if _SENSITIVE.search(key):
            return "[REDACTED]"
        if isinstance(item, Mapping):
            return {str(k): clean(v, str(k)) for k, v in item.items()}
        if isinstance(item, list):
            return [clean(v) for v in item[:200]]
        if isinstance(item, str) and len(item) > 2048:
            return item[:2048] + "...[TRUNCATED]"
        return item
    return clean(dict(value))


def _safe_input_summary(value: Any) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        return {}
    allowed = ("startDate", "endDate", "storeTopN", "dishTopN")
    return {key: value[key] for key in allowed if key in value}


_SAFE_OUTCOME_KEYS = frozenset({
    "status", "routeCode", "claims", "blockers", "observations",
    "attributionSupported", "actionProposals",
})
_SAFE_EVENT_KEYS = frozenset({
    "routeCode", "stepCount", "maxRounds", "maxToolCalls", "round",
    "purposeCode", "evidenceId", "evidenceStatus", "factCount",
    "evidenceBytes", "warningCodes", "failureCode", "roundsUsed",
    "toolCallsUsed", "factsUsed", "evidenceBytesUsed", "status",
    "claims", "blockers", "observations", "attributionSupported",
    "actionProposals", "gapCodes", "clarificationCodes", "replanReasonCodes",
    "drilldown", "drilldownTruncated",
})


def _safe_outcome(value: Any) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, Mapping):
        return {}
    return _redact_trace({key: value[key] for key in _SAFE_OUTCOME_KEYS if key in value})


def _safe_event_payload(value: Any) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        return {}
    return _redact_trace({key: value[key] for key in _SAFE_EVENT_KEYS if key in value})


def _safe_trace_record(
    value: Mapping[str, Any], *, after_sequence: int, limit: int
) -> dict[str, Any]:
    top_keys = (
        "runId", "routeCode", "state", "correlationId", "inputSummary",
        "failureCode", "counters", "createdAt", "updatedAt", "completedAt",
    )
    result = {key: value[key] for key in top_keys if key in value}
    result["outcome"] = _safe_outcome(value.get("outcome"))
    raw_events = value.get("events", [])
    events: list[dict[str, Any]] = []
    has_more = False
    if isinstance(raw_events, list):
        eligible = [
            raw
            for raw in raw_events
            if isinstance(raw, Mapping)
            and isinstance(raw.get("sequence"), int)
            and raw["sequence"] > after_sequence
        ]
        has_more = len(eligible) > limit
        for raw in eligible[:limit]:
            if not isinstance(raw, Mapping):
                continue
            event = {
                key: raw[key]
                for key in ("sequence", "eventType", "stepId", "toolName", "createdAt")
                if key in raw
            }
            event["payload"] = _safe_event_payload(raw.get("payload"))
            events.append(event)
    result["events"] = events
    safe = _redact_trace(result)
    safe["page"] = _trace_page(
        after_sequence, limit, safe["events"], has_more=has_more
    )
    return safe


def _trace_page(
    after_sequence: int,
    limit: int,
    events: Sequence[Mapping[str, Any]],
    *,
    has_more: bool,
) -> dict[str, Any]:
    next_sequence = int(events[-1]["sequence"]) if events else after_sequence
    return {
        "afterSequence": after_sequence,
        "limit": limit,
        "returned": len(events),
        "hasMore": has_more,
        "nextAfterSequence": next_sequence if has_more else None,
    }


_ADMIN_ROLES = frozenset({
    "factory_super_admin", "platform_admin", "permission_admin",
    "restaurant_manager", "restaurant_owner",
})


def _require_context(context: AgentOpsContext) -> None:
    if not isinstance(context, AgentOpsContext) or not context.factory_id or not context.user_id:
        raise AgentOpsAccessError("trusted context required")


def _require_admin_context(context: AgentOpsContext) -> None:
    _require_context(context)
    if context.role not in _ADMIN_ROLES:
        raise AgentOpsAccessError("agent ops admin context required")


def _require_after_sequence(after_sequence: int) -> None:
    if (
        isinstance(after_sequence, bool)
        or not isinstance(after_sequence, int)
        or not 0 <= after_sequence <= 9_223_372_036_854_775_807
    ):
        raise AgentOpsStoreError("trace cursor out of bounds")


def _require_limit(limit: int) -> None:
    if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 100:
        raise AgentOpsStoreError("trace limit out of bounds")


def _validate_request_lookup(
    request_id: str, request_digest: str
) -> tuple[str, str]:
    safe_request_id = validate_request_id(request_id)
    if not isinstance(request_digest, str) or not _SHA256.fullmatch(request_digest):
        raise AgentOpsStoreError("request digest is invalid")
    return safe_request_id, request_digest


def _validate_eval_set_record(record: EvalSetRecord) -> EvalSetRecord:
    request_id = validate_request_id(record.request_id)
    if not _SHA256.fullmatch(record.content_digest):
        raise AgentOpsStoreError("persisted eval set digest is invalid")
    if not _SHA256.fullmatch(record.request_digest):
        raise AgentOpsStoreError("persisted request digest is invalid")
    name = validate_name(record.name)
    cases = validate_cases(record.cases)
    if canonical_digest(cases) != record.content_digest:
        raise AgentOpsStoreError("persisted eval set digest mismatch")
    expected_request_digest = canonical_digest({
        "schemaVersion": "1.0",
        "operationKind": "CREATE_EVAL_SET",
        "name": name,
        "version": record.version,
        "description": record.description,
        "cases": cases,
    })
    if expected_request_digest != record.request_digest:
        raise AgentOpsStoreError("persisted eval set request digest mismatch")
    return replace(record, name=name, cases=cases, request_id=request_id)


def _validate_experiment_record(record: ExperimentRecord) -> ExperimentRecord:
    if not _SHA256.fullmatch(record.eval_set_digest):
        raise AgentOpsStoreError("persisted eval set digest is invalid")
    if not _SHA256.fullmatch(record.snapshot_digest):
        raise AgentOpsStoreError("persisted snapshot digest is invalid")
    if not _SHA256.fullmatch(record.request_digest):
        raise AgentOpsStoreError("persisted request digest is invalid")
    request_id = validate_request_id(record.request_id)
    evaluator_version = validate_evaluator(record.evaluator_version)
    evaluator_build = validate_evaluator(record.evaluator_build)
    if not _SHA256.fullmatch(evaluator_build):
        raise AgentOpsStoreError("persisted evaluator build is invalid")
    config_snapshot = validate_config_snapshot(record.config_snapshot)
    actual_snapshots = validate_actual_snapshots(record.actual_snapshots)
    runner_bounds = validate_runner_bounds_snapshot(record.runner_bounds)
    expected_digest = canonical_digest({
        "evalSetDigest": record.eval_set_digest,
        "evaluatorVersion": evaluator_version,
        "evaluatorBuild": evaluator_build,
        "configSnapshot": config_snapshot,
        "actualSnapshots": actual_snapshots,
        "runnerBounds": runner_bounds,
    })
    if expected_digest != record.snapshot_digest:
        raise AgentOpsStoreError("persisted experiment snapshot digest mismatch")
    if record.operation_kind == "RUN" and record.source_experiment_id is None:
        request_payload = {
            "schemaVersion": "1.0",
            "operationKind": "RUN",
            "evalSetId": record.eval_set_id,
            "configSnapshot": config_snapshot,
            "actualSnapshots": actual_snapshots,
            "runnerBounds": runner_bounds,
        }
        source_experiment_id = None
    elif record.operation_kind == "RERUN" and record.source_experiment_id is not None:
        source_experiment_id = validate_request_id(record.source_experiment_id)
        request_payload = {
            "schemaVersion": "1.0",
            "operationKind": "RERUN",
            "sourceExperimentId": source_experiment_id,
            "sourceSnapshotDigest": record.snapshot_digest,
        }
    else:
        raise AgentOpsStoreError("persisted experiment operation is invalid")
    if canonical_digest(request_payload) != record.request_digest:
        raise AgentOpsStoreError("persisted experiment request digest mismatch")
    if set(actual_snapshots) != {
        str(item.get("caseId")) for item in record.case_results
    }:
        raise AgentOpsStoreError("persisted experiment case set mismatch")
    return replace(
        record,
        evaluator_version=evaluator_version,
        evaluator_build=evaluator_build,
        request_id=request_id,
        source_experiment_id=source_experiment_id,
        config_snapshot=config_snapshot,
        actual_snapshots=actual_snapshots,
        runner_bounds=runner_bounds,
    )
