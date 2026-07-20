"""Deterministic, bounded, offline-only AgentOps batch runner."""

from __future__ import annotations

import asyncio
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from smartbi.agent.runtime import evaluation as runtime_evaluation

from .contracts import EvalSetRecord
from .validation import (
    AgentOpsValidationError,
    MAX_CASES,
    validate_actual_snapshot,
    validate_runner_bounds_snapshot,
)

EVALUATOR_VERSION = "restaurant-offline-v1"


def compute_evaluator_build(
    artifacts: Mapping[str, bytes] | None = None,
) -> str:
    """Hash the exact source artifacts that implement the offline evaluator."""

    sources = artifacts or {
        "runner.py": Path(__file__).read_bytes(),
        "runtime/evaluation.py": Path(runtime_evaluation.__file__).read_bytes(),
    }
    digest = hashlib.sha256()
    for name in sorted(sources):
        payload = sources[name]
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


EVALUATOR_BUILD = compute_evaluator_build()


class EvaluatorBuildUnavailableError(RuntimeError):
    def __init__(self) -> None:
        super().__init__("EVALUATOR_BUILD_UNAVAILABLE")


@dataclass(frozen=True)
class RunnerBounds:
    max_cases: int = MAX_CASES
    max_concurrency: int = 4
    per_case_timeout_seconds: float = 1.0

    def __post_init__(self) -> None:
        if not 1 <= self.max_cases <= MAX_CASES:
            raise ValueError("max_cases out of bounds")
        if not 1 <= self.max_concurrency <= 4:
            raise ValueError("max_concurrency out of bounds")
        if not 0.05 <= self.per_case_timeout_seconds <= 5.0:
            raise ValueError("per_case_timeout_seconds out of bounds")

    def snapshot(self) -> dict[str, int]:
        return {
            "maxCases": self.max_cases,
            "maxConcurrency": self.max_concurrency,
            "perCaseTimeoutMs": round(self.per_case_timeout_seconds * 1000),
        }

    @classmethod
    def from_snapshot(cls, value: Mapping[str, Any]) -> "RunnerBounds":
        safe = validate_runner_bounds_snapshot(value)
        return cls(
            max_cases=safe["maxCases"],
            max_concurrency=safe["maxConcurrency"],
            per_case_timeout_seconds=safe["perCaseTimeoutMs"] / 1000.0,
        )


class OfflineBatchRunner:
    """Consumes only supplied snapshots; it has no Tool or network dependency."""

    evaluator_version = EVALUATOR_VERSION
    evaluator_build = EVALUATOR_BUILD

    async def run(
        self,
        eval_set: EvalSetRecord,
        actual_by_case: Mapping[str, Mapping[str, Any]],
        *,
        bounds: RunnerBounds,
    ) -> tuple[dict[str, Any], tuple[dict[str, Any], ...]]:
        if len(eval_set.cases) > bounds.max_cases:
            raise AgentOpsValidationError("RUNNER_CASE_LIMIT_EXCEEDED")
        if set(actual_by_case) != {case["caseId"] for case in eval_set.cases}:
            raise AgentOpsValidationError("ACTUAL_CASE_SET_MISMATCH")
        semaphore = asyncio.Semaphore(bounds.max_concurrency)

        async def one(case: Mapping[str, Any]) -> dict[str, Any]:
            async with semaphore:
                return await asyncio.wait_for(
                    asyncio.to_thread(self._evaluate, case, actual_by_case[case["caseId"]]),
                    timeout=bounds.per_case_timeout_seconds,
                )

        results = tuple(await asyncio.gather(*(one(case) for case in eval_set.cases)))
        return aggregate_case_results(results), results

    @staticmethod
    def _evaluate(case: Mapping[str, Any], actual_raw: Mapping[str, Any]) -> dict[str, Any]:
        actual = validate_actual_snapshot(actual_raw)
        result = runtime_evaluation.evaluate_offline_case(
            runtime_evaluation.OfflineCaseSnapshot(
                route_code=actual["routeCode"],
                tools_in_order=tuple(actual["tools"]),
                numeric_truth_refs=actual["numericTruthRefs"],
                rounds_used=actual["roundsUsed"],
                tool_calls_used=actual["toolCallsUsed"],
            ),
            runtime_evaluation.OfflineCaseExpectation(
                route_code=case["expectedRoute"],
                required_tools_in_order=tuple(case["requiredTools"]),
                numeric_truth_refs=case["numericTruthRefs"],
                max_rounds=case["maxRounds"],
                max_tool_calls=case["maxToolCalls"],
            ),
        )
        return {
            "caseId": case["caseId"],
            "passed": result.passed,
            "routeOk": result.route_ok,
            "trajectoryOk": result.trajectory_ok,
            "numericTruthOk": result.numeric_truth_ok,
            "failureCodes": list(result.failures),
        }


class EvaluatorRegistry:
    """Resolve an evaluator by immutable identity; reruns never fall forward."""

    def __init__(
        self,
        runners: Sequence[OfflineBatchRunner],
        *,
        current: tuple[str, str] | None = None,
    ) -> None:
        if not runners:
            raise ValueError("at least one evaluator runner is required")
        self._runners: dict[tuple[str, str], OfflineBatchRunner] = {}
        for runner in runners:
            identity = (runner.evaluator_version, runner.evaluator_build)
            if identity in self._runners:
                raise ValueError("duplicate evaluator identity")
            self._runners[identity] = runner
        self._current = current or (
            runners[0].evaluator_version,
            runners[0].evaluator_build,
        )
        if self._current not in self._runners:
            raise ValueError("current evaluator identity is not registered")

    def current(self) -> OfflineBatchRunner:
        return self._runners[self._current]

    def resolve(self, version: str, build: str) -> OfflineBatchRunner:
        try:
            return self._runners[(version, build)]
        except KeyError as exc:
            raise EvaluatorBuildUnavailableError() from exc


def aggregate_case_results(
    results: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    total = len(results)
    if total == 0:
        raise AgentOpsValidationError("RUNNER_REQUIRES_CASES")
    passed = sum(1 for item in results if item["passed"])
    return {
        "caseCount": total,
        "passedCount": passed,
        "failedCount": total - passed,
        "passRate": format(passed / total, ".6f"),
        "routePassCount": sum(1 for item in results if item["routeOk"]),
        "trajectoryPassCount": sum(1 for item in results if item["trajectoryOk"]),
        "numericTruthPassCount": sum(
            1 for item in results if item["numericTruthOk"]
        ),
    }
