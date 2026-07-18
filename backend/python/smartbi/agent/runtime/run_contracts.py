"""Typed contracts for the bounded restaurant evidence runtime.

These contracts intentionally contain no provider, HTTP or prompt concepts.
The runtime accepts a small, server-created request and produces structured
claims whose numeric values can be traced to an EvidenceEnvelope fact.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum
from typing import Any, Mapping, Optional

from .contracts import EvidenceEnvelope


class RunState(str, Enum):
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"

    @property
    def terminal(self) -> bool:
        return self is not RunState.RUNNING


class OutcomeStatus(str, Enum):
    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"
    NOT_COMPUTABLE = "NOT_COMPUTABLE"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"


class AgentEventType(str, Enum):
    RUN_STARTED = "RUN_STARTED"
    ROUTE_SELECTED = "ROUTE_SELECTED"
    PLAN_CREATED = "PLAN_CREATED"
    STEP_STARTED = "STEP_STARTED"
    STEP_COMPLETED = "STEP_COMPLETED"
    STEP_FAILED = "STEP_FAILED"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    RUN_CANCELLED = "RUN_CANCELLED"
    RUN_COMPLETED = "RUN_COMPLETED"
    RUN_FAILED = "RUN_FAILED"


class RouteCode(str, Enum):
    GROSS_MARGIN_DECLINE_ATTRIBUTION = "GROSS_MARGIN_DECLINE_ATTRIBUTION"


@dataclass(frozen=True)
class RuntimeBudgets:
    max_rounds: int = 2
    max_tool_calls: int = 10
    wallclock_seconds: float = 60.0
    max_facts: int = 450
    max_evidence_bytes: int = 300_000

    def __post_init__(self) -> None:
        if not 1 <= self.max_rounds <= 2:
            raise ValueError("runtime supports at most two rounds")
        if not 1 <= self.max_tool_calls <= 10:
            raise ValueError("runtime supports at most ten tool calls")
        if self.wallclock_seconds <= 0:
            raise ValueError("wallclock budget must be positive")
        if self.max_facts <= 0 or self.max_evidence_bytes <= 0:
            raise ValueError("fact and evidence-byte budgets must be positive")


@dataclass(frozen=True)
class GrossMarginDeclineRequest:
    start_date: str
    end_date: str
    store_top_n: int = 20
    dish_top_n: int = 10

    def __post_init__(self) -> None:
        start = date.fromisoformat(self.start_date)
        end = date.fromisoformat(self.end_date)
        if end < start:
            raise ValueError("end_date must not precede start_date")
        if (end - start).days + 1 > 366:
            raise ValueError("requested window may not exceed 366 days")
        if not 1 <= self.store_top_n <= 50:
            raise ValueError("store_top_n must be between 1 and 50")
        if not 1 <= self.dish_top_n <= 20:
            raise ValueError("dish_top_n must be between 1 and 20")

    def safe_dict(self) -> dict[str, Any]:
        return {
            "routeCode": RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION.value,
            "startDate": self.start_date,
            "endDate": self.end_date,
            "storeTopN": self.store_top_n,
            "dishTopN": self.dish_top_n,
        }


@dataclass(frozen=True)
class PlanStep:
    step_id: str
    round_number: int
    tool_name: str
    parameters: Mapping[str, Any]
    purpose_code: str


@dataclass(frozen=True)
class RoutePlan:
    route_code: RouteCode
    steps: tuple[PlanStep, ...]

    def __post_init__(self) -> None:
        if not self.steps:
            raise ValueError("route plan requires at least one step")
        if max(step.round_number for step in self.steps) > 2:
            raise ValueError("route plan exceeds two rounds")
        if len(self.steps) > 10:
            raise ValueError("route plan exceeds ten tool calls")
        ids = [step.step_id for step in self.steps]
        if len(ids) != len(set(ids)):
            raise ValueError("route step ids must be unique")


@dataclass(frozen=True)
class RuntimeCounters:
    rounds_used: int = 0
    tool_calls_used: int = 0
    facts_used: int = 0
    evidence_bytes_used: int = 0

    def after(
        self, *, round_number: int, facts: int, evidence_bytes: int
    ) -> "RuntimeCounters":
        return RuntimeCounters(
            rounds_used=max(self.rounds_used, round_number),
            tool_calls_used=self.tool_calls_used + 1,
            facts_used=self.facts_used + facts,
            evidence_bytes_used=self.evidence_bytes_used + evidence_bytes,
        )

    def safe_dict(self) -> dict[str, int]:
        return {
            "roundsUsed": self.rounds_used,
            "toolCallsUsed": self.tool_calls_used,
            "factsUsed": self.facts_used,
            "evidenceBytesUsed": self.evidence_bytes_used,
        }


@dataclass(frozen=True)
class NumericClaim:
    statement_code: str
    metric: str
    value: str
    unit: Optional[str]
    evidence_id: str
    fact_id: str
    dimensions: Mapping[str, str] = field(default_factory=dict)

    def safe_dict(self) -> dict[str, Any]:
        return {
            "statementCode": self.statement_code,
            "metric": self.metric,
            "value": self.value,
            "unit": self.unit,
            "evidenceId": self.evidence_id,
            "factId": self.fact_id,
            "dimensions": dict(self.dimensions),
        }


@dataclass(frozen=True)
class StructuredOutcome:
    status: OutcomeStatus
    route_code: RouteCode
    claims: tuple[NumericClaim, ...] = ()
    blockers: tuple[str, ...] = ()
    observations: tuple[str, ...] = ()
    attribution_supported: bool = False

    def safe_dict(self) -> dict[str, Any]:
        return {
            "status": self.status.value,
            "routeCode": self.route_code.value,
            "claims": [claim.safe_dict() for claim in self.claims],
            "blockers": list(self.blockers),
            "observations": list(self.observations),
            "attributionSupported": self.attribution_supported,
        }

    def persistence_dict(self) -> dict[str, Any]:
        """Compact allowlist form; free-form evidence dimensions stay out of DB."""

        return {
            "status": self.status.value,
            "routeCode": self.route_code.value,
            "claims": [
                {
                    "statementCode": claim.statement_code,
                    "metric": claim.metric,
                    "value": claim.value,
                    "unit": claim.unit,
                    "evidenceId": claim.evidence_id,
                    "factId": claim.fact_id,
                }
                for claim in self.claims
            ],
            "blockers": list(self.blockers),
            "observations": list(self.observations),
            "attributionSupported": self.attribution_supported,
        }


@dataclass(frozen=True)
class RuntimeResult:
    run_id: str
    state: RunState
    route_plan: RoutePlan
    outcome: StructuredOutcome
    evidence: tuple[EvidenceEnvelope, ...]
    counters: RuntimeCounters
    failure_code: Optional[str] = None


@dataclass(frozen=True)
class RunRecord:
    run_id: str
    factory_id: str
    state: RunState
    route_code: RouteCode
    safe_request: Mapping[str, Any]
    counters: RuntimeCounters
    next_event_sequence: int = 0
    outcome_summary: Optional[Mapping[str, Any]] = None
    failure_code: Optional[str] = None


@dataclass(frozen=True)
class AgentEvent:
    run_id: str
    factory_id: str
    sequence: int
    event_type: AgentEventType
    payload: Mapping[str, Any]
    step_id: Optional[str] = None
    tool_name: Optional[str] = None
