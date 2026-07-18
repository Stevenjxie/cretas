"""Offline route, trajectory and legacy-shadow evaluation contracts."""

from __future__ import annotations

from dataclasses import dataclass

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
