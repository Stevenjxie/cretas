"""Stable contracts for bounded restaurant Read Tools.

The contracts are intentionally independent from FastAPI and from any LLM.
Every numeric business fact is represented as a decimal string and carries its
own provenance, freshness and coverage.  Missing information is explicit; it
is never converted to zero.
"""

from __future__ import annotations

import dataclasses
import json
import math
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from enum import Enum
from typing import Any, Mapping, Optional, Sequence


class EvidenceStatus(str, Enum):
    OK = "OK"
    EMPTY = "EMPTY"
    PARTIAL = "PARTIAL"
    NOT_COMPUTABLE = "NOT_COMPUTABLE"
    CONFLICT = "CONFLICT"
    DENIED = "DENIED"
    ERROR = "ERROR"


class FreshnessStatus(str, Enum):
    FRESH = "FRESH"
    STALE = "STALE"
    UNKNOWN = "UNKNOWN"


class CoverageStatus(str, Enum):
    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"
    UNKNOWN = "UNKNOWN"


class DataClassification(str, Enum):
    FINANCIAL_RESTRICTED = "FINANCIAL_RESTRICTED"
    OPERATIONAL_INTERNAL = "OPERATIONAL_INTERNAL"
    CUSTOMER_SENSITIVE_AGGREGATED = "CUSTOMER_SENSITIVE_AGGREGATED"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_date(value: date | datetime | str | None) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, datetime):
        value = value.date()
    if isinstance(value, date):
        return value.isoformat()
    parsed = date.fromisoformat(str(value))
    return parsed.isoformat()


def decimal_string(value: Any) -> Optional[str]:
    """Return a deterministic, finite decimal string for a business number."""

    if value is None:
        return None
    if isinstance(value, bool):
        raise TypeError("boolean is not a business numeric value")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("non-finite business numeric value")
    try:
        number = value if isinstance(value, Decimal) else Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise TypeError(f"unsupported business numeric value: {value!r}") from exc
    if not number.is_finite():
        raise ValueError("non-finite business numeric value")
    rendered = format(number, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return rendered or "0"


@dataclass(frozen=True)
class Freshness:
    data_through: Optional[str]
    status: FreshnessStatus
    materialized_at: Optional[str] = None
    sla_seconds: Optional[int] = None
    basis: str = "source maximum date"

    def __post_init__(self) -> None:
        if self.data_through is not None:
            object.__setattr__(self, "data_through", iso_date(self.data_through))
        if self.status is not FreshnessStatus.UNKNOWN and self.data_through is None:
            raise ValueError("known freshness requires source data_through")

    @classmethod
    def unknown(cls, basis: str) -> "Freshness":
        return cls(data_through=None, status=FreshnessStatus.UNKNOWN, basis=basis)


@dataclass(frozen=True)
class Coverage:
    status: CoverageStatus
    basis: str
    numerator: Optional[int] = None
    denominator: Optional[int] = None
    pct: Optional[str] = None
    missing_reasons: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.numerator is not None and self.numerator < 0:
            raise ValueError("coverage numerator must be non-negative")
        if self.denominator is not None and self.denominator < 0:
            raise ValueError("coverage denominator must be non-negative")
        if self.numerator is not None and self.denominator is not None:
            if self.numerator > self.denominator:
                raise ValueError("coverage numerator cannot exceed denominator")
            expected = (
                Decimal("100")
                if self.denominator == 0 and self.numerator == 0
                else Decimal(self.numerator) / Decimal(self.denominator) * Decimal("100")
            )
            object.__setattr__(self, "pct", decimal_string(expected.quantize(Decimal("0.01"))))

    @classmethod
    def complete(cls, basis: str, count: Optional[int] = None) -> "Coverage":
        return cls(
            status=CoverageStatus.COMPLETE,
            basis=basis,
            numerator=count,
            denominator=count,
        )

    @classmethod
    def unknown(cls, basis: str, *reasons: str) -> "Coverage":
        return cls(
            status=CoverageStatus.UNKNOWN,
            basis=basis,
            missing_reasons=tuple(reasons),
        )


@dataclass(frozen=True)
class ProvenanceReference:
    ref_id: str
    source_type: str
    asset: str
    query_id: str
    source_version: str
    source_upload_id: Optional[str] = None
    field_name: Optional[str] = None


@dataclass(frozen=True)
class EvidenceFact:
    fact_id: str
    metric: str
    value: Optional[str]
    unit: Optional[str]
    scale: Optional[int]
    dimensions: Mapping[str, str]
    status: EvidenceStatus
    semantics: str
    provenance_refs: tuple[str, ...]
    freshness: Freshness
    coverage: Coverage
    quality_flags: tuple[str, ...] = ()

    @classmethod
    def numeric(
        cls,
        *,
        fact_id: str,
        metric: str,
        value: Any,
        unit: Optional[str],
        scale: Optional[int],
        dimensions: Optional[Mapping[str, Any]],
        status: EvidenceStatus,
        semantics: str,
        provenance_refs: Sequence[str],
        freshness: Freshness,
        coverage: Coverage,
        quality_flags: Sequence[str] = (),
    ) -> "EvidenceFact":
        if value is None and status not in {
            EvidenceStatus.NOT_COMPUTABLE,
            EvidenceStatus.PARTIAL,
        }:
            raise ValueError("null fact must be NOT_COMPUTABLE or PARTIAL")
        if value is not None and not provenance_refs:
            raise ValueError("numeric fact requires at least one provenance reference")
        return cls(
            fact_id=fact_id,
            metric=metric,
            value=decimal_string(value),
            unit=unit,
            scale=scale,
            dimensions={str(k): str(v) for k, v in (dimensions or {}).items()},
            status=status,
            semantics=semantics,
            provenance_refs=tuple(provenance_refs),
            freshness=freshness,
            coverage=coverage,
            quality_flags=tuple(quality_flags),
        )


@dataclass(frozen=True)
class EvidenceWarning:
    code: str
    severity: str
    message: str
    blocks_conclusions: tuple[str, ...] = ()


@dataclass(frozen=True)
class EvidenceConflict:
    metric: str
    candidate_fact_ids: tuple[str, ...]
    resolution: str = "UNRESOLVED"


@dataclass(frozen=True)
class EvidenceLimits:
    rows_returned: int
    rows_truncated: int
    facts_returned: int
    cells_returned: int
    bytes_returned: int
    provenance_refs_returned: int


@dataclass(frozen=True)
class EvidenceDraft:
    status: EvidenceStatus
    requested_window: Optional[Mapping[str, str]]
    effective_window: Optional[Mapping[str, str]]
    grain: str
    normalized_parameters: Mapping[str, Any]
    facts: tuple[EvidenceFact, ...]
    provenance: tuple[ProvenanceReference, ...]
    warnings: tuple[EvidenceWarning, ...] = ()
    conflicts: tuple[EvidenceConflict, ...] = ()
    rows_truncated: int = 0


@dataclass(frozen=True)
class EvidenceEnvelope:
    schema_version: str
    evidence_id: str
    tool_name: str
    tool_version: str
    descriptor_digest: str
    tenant_id: str
    business_type: str
    correlation_id: str
    run_id: Optional[str]
    step_id: Optional[str]
    query_spec: Mapping[str, Any]
    status: EvidenceStatus
    facts: tuple[EvidenceFact, ...]
    provenance: tuple[ProvenanceReference, ...]
    warnings: tuple[EvidenceWarning, ...]
    conflicts: tuple[EvidenceConflict, ...]
    classification: DataClassification
    limits: EvidenceLimits
    generated_at: str

    def to_dict(self) -> dict[str, Any]:
        return _json_ready(self)

    def to_json(self) -> str:
        return json.dumps(
            self.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )


@dataclass(frozen=True)
class TrustedExecutionContext:
    """Tenant identity created by authenticated server code, never by the model."""

    factory_id: str
    business_type: str
    user_id: Optional[str]
    correlation_id: str
    authorized_classifications: frozenset[DataClassification]
    run_id: Optional[str] = None
    step_id: Optional[str] = None

    def __post_init__(self) -> None:
        if not self.factory_id or not self.factory_id.strip():
            raise ValueError("trusted factory_id is required")
        if not self.correlation_id or not self.correlation_id.strip():
            raise ValueError("correlation_id is required")
        normalized = self.business_type.strip().upper() if self.business_type else ""
        if not normalized:
            raise ValueError("trusted business_type is required")
        object.__setattr__(self, "business_type", normalized)


def _json_ready(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        return {
            _camel_case(field_.name): _json_ready(getattr(value, field_.name))
            for field_ in dataclasses.fields(value)
        }
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {str(k): _json_ready(v) for k, v in value.items()}
    if isinstance(value, (tuple, list, frozenset, set)):
        return [_json_ready(v) for v in value]
    if isinstance(value, (date, datetime)):
        return value.isoformat()
    if isinstance(value, Decimal):
        return decimal_string(value)
    return value


def _camel_case(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def serialized_size(value: Any) -> int:
    return len(
        json.dumps(
            _json_ready(value), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    )
