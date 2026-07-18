"""Trusted execution boundary for restaurant Read Tools."""

from __future__ import annotations

import hashlib
import json
import uuid
from contextlib import AbstractAsyncContextManager
from datetime import datetime, timezone
from typing import Any, Callable, Mapping

from .contracts import (
    EvidenceDraft,
    EvidenceEnvelope,
    EvidenceLimits,
    EvidenceStatus,
    EvidenceWarning,
    TrustedExecutionContext,
    serialized_size,
)
from .registry import ReadonlyToolRegistry


class ReadToolContractError(ValueError):
    pass


class TenantParameterError(ReadToolContractError):
    pass


class ReadToolLimitError(ReadToolContractError):
    pass


_FORBIDDEN_TENANT_KEYS = {
    "factoryid",
    "factory_id",
    "tenantid",
    "tenant_id",
}


class _BoundAcquire(AbstractAsyncContextManager):
    def __init__(self, owner: "_BoundConnectionPool") -> None:
        self._owner = owner

    async def __aenter__(self) -> Any:
        if self._owner._leased:
            raise RuntimeError("concurrent or nested acquire on bound tenant connection")
        self._owner._leased = True
        return self._owner.connection

    async def __aexit__(self, exc_type, exc, tb) -> None:
        self._owner._leased = False


class _BoundConnectionPool:
    """Pool-shaped facade that forces Gold queries onto the tenant-bound conn."""

    def __init__(self, connection: Any) -> None:
        self.connection = connection
        self._leased = False

    def acquire(self) -> _BoundAcquire:
        return _BoundAcquire(self)


class ReadToolGateway:
    def __init__(
        self,
        pool: Any,
        registry: ReadonlyToolRegistry,
        *,
        id_factory: Callable[[], str] | None = None,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._pool = pool
        self._registry = registry
        self._id_factory = id_factory or (lambda: str(uuid.uuid4()))
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    async def execute(
        self,
        tool_name: str,
        parameters: Mapping[str, Any],
        context: TrustedExecutionContext,
    ) -> EvidenceEnvelope:
        if not isinstance(context, TrustedExecutionContext):
            raise TypeError("trusted execution context is required")
        if context.business_type != "RESTAURANT":
            raise TenantParameterError(
                "restaurant Read Tools require trusted business_type=RESTAURANT"
            )
        if not isinstance(parameters, Mapping):
            raise ReadToolContractError("parameters must be an object")
        self._reject_tenant_parameters(parameters)

        registered = self._registry.require(tool_name)
        descriptor = registered.descriptor
        if descriptor.classification not in context.authorized_classifications:
            return self._terminal_envelope(
                descriptor=descriptor,
                context=context,
                parameters=parameters,
                status=EvidenceStatus.DENIED,
                warning=EvidenceWarning(
                    code="CLASSIFICATION_ACCESS_DENIED",
                    severity="BLOCKING",
                    message=f"Not authorized for {descriptor.classification.value}",
                    blocks_conclusions=descriptor.conclusions_allowed,
                ),
            )

        unknown = set(parameters) - set(descriptor.allowed_parameters)
        missing = set(descriptor.required_parameters) - set(parameters)
        if unknown:
            raise ReadToolContractError(f"unknown parameters: {sorted(unknown)}")
        if missing:
            raise ReadToolContractError(f"missing parameters: {sorted(missing)}")

        try:
            async with self._pool.acquire() as connection:
                # Read-only DB transaction and RLS tenant context are inseparable.
                # Existing Gold queries still receive factory_id explicitly, so
                # every query has two tenant boundaries rather than relying on one.
                async with connection.transaction(readonly=True):
                    await connection.execute(
                        "SELECT set_config('app.factory_id', $1, true)",
                        context.factory_id,
                    )
                    bound_pool = _BoundConnectionPool(connection)
                    draft = await registered.adapter(
                        bound_pool, context, dict(parameters), descriptor
                    )
        except ReadToolContractError:
            raise
        except Exception:
            return self._terminal_envelope(
                descriptor=descriptor,
                context=context,
                parameters=parameters,
                status=EvidenceStatus.ERROR,
                warning=EvidenceWarning(
                    code="READ_SOURCE_FAILED",
                    severity="BLOCKING",
                    message="Read source failed; no business facts were returned",
                    blocks_conclusions=descriptor.conclusions_allowed,
                ),
            )

        return self._finalize(descriptor, context, parameters, draft)

    def _finalize(self, descriptor, context, parameters, draft: EvidenceDraft) -> EvidenceEnvelope:
        provenance_ids = {ref.ref_id for ref in draft.provenance}
        fact_ids = [fact.fact_id for fact in draft.facts]
        if len(fact_ids) != len(set(fact_ids)):
            raise ReadToolContractError("fact ids must be unique within an envelope")
        for fact in draft.facts:
            unknown_refs = set(fact.provenance_refs) - provenance_ids
            if unknown_refs:
                raise ReadToolContractError(
                    f"fact {fact.fact_id} has unknown provenance refs: {sorted(unknown_refs)}"
                )

        unique_rows = {
            json.dumps(dict(f.dimensions), sort_keys=True, separators=(",", ":"))
            for f in draft.facts
        }
        rows = len(unique_rows)
        cells = sum(1 + len(f.dimensions) for f in draft.facts)
        limits = descriptor.limits
        if rows > limits.max_rows:
            raise ReadToolLimitError(f"rows {rows} exceed {limits.max_rows}")
        if len(draft.facts) > limits.max_facts:
            raise ReadToolLimitError("fact limit exceeded")
        if cells > limits.max_cells:
            raise ReadToolLimitError("cell limit exceeded")
        if len(draft.provenance) > limits.max_provenance_refs:
            raise ReadToolLimitError("provenance limit exceeded")

        query_spec = {
            "queryDigest": self._query_digest(descriptor.name, parameters),
            "requestedWindow": draft.requested_window,
            "effectiveWindow": draft.effective_window,
            "grain": draft.grain,
            "parameters": dict(draft.normalized_parameters),
        }
        generated_at = self._clock().astimezone(timezone.utc).isoformat()
        evidence_id = self._id_factory()
        provisional = EvidenceEnvelope(
            schema_version="1.0",
            evidence_id=evidence_id,
            tool_name=descriptor.name,
            tool_version=descriptor.version,
            descriptor_digest=descriptor.digest,
            tenant_id=context.factory_id,
            business_type=context.business_type,
            correlation_id=context.correlation_id,
            run_id=context.run_id,
            step_id=context.step_id,
            query_spec=query_spec,
            status=draft.status,
            facts=draft.facts,
            provenance=draft.provenance,
            warnings=draft.warnings,
            conflicts=draft.conflicts,
            classification=descriptor.classification,
            limits=EvidenceLimits(
                rows_returned=rows,
                rows_truncated=draft.rows_truncated,
                facts_returned=len(draft.facts),
                cells_returned=cells,
                bytes_returned=0,
                provenance_refs_returned=len(draft.provenance),
            ),
            generated_at=generated_at,
        )
        # Account for the byte-count field itself with a bounded fixed point.
        final = provisional
        for _ in range(4):
            size = serialized_size(final)
            if final.limits.bytes_returned == size:
                break
            final = self._with_bytes(final, size)
        if serialized_size(final) > limits.max_bytes:
            raise ReadToolLimitError("serialized EvidenceEnvelope exceeds byte limit")
        return final

    @staticmethod
    def _with_bytes(envelope: EvidenceEnvelope, size: int) -> EvidenceEnvelope:
        new_limits = EvidenceLimits(
            rows_returned=envelope.limits.rows_returned,
            rows_truncated=envelope.limits.rows_truncated,
            facts_returned=envelope.limits.facts_returned,
            cells_returned=envelope.limits.cells_returned,
            bytes_returned=size,
            provenance_refs_returned=envelope.limits.provenance_refs_returned,
        )
        return EvidenceEnvelope(**{**envelope.__dict__, "limits": new_limits})

    def _terminal_envelope(
        self, *, descriptor, context, parameters, status, warning
    ) -> EvidenceEnvelope:
        draft = EvidenceDraft(
            status=status,
            requested_window=None,
            effective_window=None,
            grain=descriptor.time_grain,
            normalized_parameters=dict(parameters),
            facts=(),
            provenance=(),
            warnings=(warning,),
        )
        return self._finalize(descriptor, context, parameters, draft)

    @staticmethod
    def _query_digest(tool_name: str, parameters: Mapping[str, Any]) -> str:
        canonical = json.dumps(
            {"tool": tool_name, "parameters": parameters},
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        ).encode()
        return "sha256:" + hashlib.sha256(canonical).hexdigest()

    @classmethod
    def _reject_tenant_parameters(cls, value: Any, path: str = "parameters") -> None:
        if isinstance(value, Mapping):
            for key, nested in value.items():
                normalized = str(key).replace("-", "_").lower()
                compact = normalized.replace("_", "")
                if normalized in _FORBIDDEN_TENANT_KEYS or compact in {
                    "factoryid",
                    "tenantid",
                }:
                    raise TenantParameterError(
                        f"tenant identity is forbidden in model parameters: {path}.{key}"
                    )
                cls._reject_tenant_parameters(nested, f"{path}.{key}")
        elif isinstance(value, (list, tuple)):
            for index, nested in enumerate(value):
                cls._reject_tenant_parameters(nested, f"{path}[{index}]")
