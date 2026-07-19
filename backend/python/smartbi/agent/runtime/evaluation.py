"""Offline route, trajectory and legacy-shadow evaluation contracts.

The snapshot evaluator is deliberately data-only.  AgentOps can therefore
replay sanitized fixtures without a model, network connection or Tool grant.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Mapping

from .numeric_truth import NumericTruthError, NumericTruthGuard
from .run_contracts import NumericClaim, RouteCode, RuntimeResult


@dataclass(frozen=True)
class TrajectoryExpectation:
    route_code: RouteCode
    required_tools_in_order: tuple[str, ...]
    max_rounds: int = 2
    max_tool_calls: int = 10


@dataclass(frozen=True)
class OfflineEvaluation:
    route_ok: bool
    trajectory_ok: bool
    numeric_truth_ok: bool
    failures: tuple[str, ...]

    @property
    def passed(self) -> bool:
        return not self.failures


def evaluate_runtime(
    result: RuntimeResult, expectation: TrajectoryExpectation
) -> OfflineEvaluation:
    failures: list[str] = []
    route_ok = result.route_plan.route_code is expectation.route_code
    if not route_ok:
        failures.append("ROUTE_MISMATCH")

    actual_tools = tuple(envelope.tool_name for envelope in result.evidence)
    trajectory_ok = (
        _is_subsequence(expectation.required_tools_in_order, actual_tools)
        and result.counters.rounds_used <= expectation.max_rounds
        and result.counters.tool_calls_used <= expectation.max_tool_calls
    )
    if not trajectory_ok:
        failures.append("TRAJECTORY_MISMATCH")

    try:
        NumericTruthGuard.validate_outcome(result.outcome, result.evidence)
        numeric_ok = True
    except NumericTruthError:
        numeric_ok = False
        failures.append("NUMERIC_TRUTH_FAILED")
    return OfflineEvaluation(route_ok, trajectory_ok, numeric_ok, tuple(failures))


@dataclass(frozen=True)
class OfflineCaseExpectation:
    """Immutable expectations stored in a versioned Eval Set."""

    route_code: str
    required_tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    max_rounds: int = 2
    max_tool_calls: int = 10


@dataclass(frozen=True)
class OfflineCaseSnapshot:
    """Sanitized observed result supplied to the offline batch harness."""

    route_code: str
    tools_in_order: tuple[str, ...]
    numeric_truth_refs: Mapping[str, str]
    rounds_used: int
    tool_calls_used: int


def evaluate_offline_case(
    actual: OfflineCaseSnapshot, expectation: OfflineCaseExpectation
) -> OfflineEvaluation:
    """Evaluate one data-only fixture with the same route/trajectory semantics.

    Numeric truth references are evidence-bound identifiers mapped to canonical
    finite decimal strings.  This is the persisted-fixture equivalent of
    :class:`NumericTruthGuard`: every expected reference must exist and carry
    the expected value; missing references are never interpreted as zero.
    """

    failures: list[str] = []
    route_ok = actual.route_code == expectation.route_code
    if not route_ok:
        failures.append("ROUTE_MISMATCH")

    trajectory_ok = (
        _is_subsequence(expectation.required_tools_in_order, actual.tools_in_order)
        and actual.rounds_used <= expectation.max_rounds
        and actual.tool_calls_used <= expectation.max_tool_calls
    )
    if not trajectory_ok:
        failures.append("TRAJECTORY_MISMATCH")

    numeric_ok = _numeric_refs_match(
        actual.numeric_truth_refs, expectation.numeric_truth_refs
    )
    if not numeric_ok:
        failures.append("NUMERIC_TRUTH_FAILED")
    return OfflineEvaluation(route_ok, trajectory_ok, numeric_ok, tuple(failures))


def _numeric_refs_match(actual: Mapping[str, str], expected: Mapping[str, str]) -> bool:
    if not expected:
        return True
    for ref, expected_value in expected.items():
        observed = actual.get(ref)
        if observed is None:
            return False
        try:
            if Decimal(observed) != Decimal(expected_value):
                return False
            if not Decimal(observed).is_finite() or not Decimal(expected_value).is_finite():
                return False
        except (InvalidOperation, TypeError, ValueError):
            return False
    return True


@dataclass(frozen=True)
class LegacyShadowSnapshot:
    """Sanitized result supplied by an offline harness; nothing is executed."""

    case_digest: str
    status_code: str
    claims: tuple[NumericClaim, ...]


@dataclass(frozen=True)
class LegacyShadowComparison:
    case_digest: str
    runtime_status: str
    legacy_status: str
    shared_metrics: tuple[str, ...]
    runtime_only_metrics: tuple[str, ...]
    legacy_only_metrics: tuple[str, ...]


def compare_legacy_shadow(
    result: RuntimeResult, legacy: LegacyShadowSnapshot
) -> LegacyShadowComparison:
    runtime_metrics = {claim.metric for claim in result.outcome.claims}
    legacy_metrics = {claim.metric for claim in legacy.claims}
    return LegacyShadowComparison(
        case_digest=legacy.case_digest,
        runtime_status=result.outcome.status.value,
        legacy_status=legacy.status_code,
        shared_metrics=tuple(sorted(runtime_metrics & legacy_metrics)),
        runtime_only_metrics=tuple(sorted(runtime_metrics - legacy_metrics)),
        legacy_only_metrics=tuple(sorted(legacy_metrics - runtime_metrics)),
    )


def _is_subsequence(required: tuple[str, ...], actual: tuple[str, ...]) -> bool:
    iterator = iter(actual)
    return all(any(candidate == item for candidate in iterator) for item in required)
