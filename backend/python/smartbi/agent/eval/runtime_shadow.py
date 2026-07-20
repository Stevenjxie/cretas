"""Server-side runtime shadow evaluator with zero durable run/event writes."""

from __future__ import annotations

import asyncio
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping

from smartbi.agent.runtime import bounded_runtime as bounded_runtime_module
from smartbi.agent.runtime import evaluation as runtime_evaluation
from smartbi.agent.runtime import gateway as gateway_module
from smartbi.agent.runtime.bounded_runtime import BoundedRestaurantRuntime
from smartbi.agent.runtime.contracts import DataClassification, TrustedExecutionContext
from smartbi.agent.runtime.gateway import ReadToolGateway
from smartbi.agent.runtime.restaurant_read_tools import build_restaurant_read_registry
from smartbi.agent.runtime.run_contracts import (
    GrossMarginDeclineRequest,
    RuntimeBudgets,
    RuntimeResult,
)
from smartbi.agent.runtime.run_store import InMemoryRunStore

from .contracts import AgentOpsContext, EvalSetRecord
from .runner import OfflineBatchRunner, aggregate_case_results
from .validation import (
    AgentOpsValidationError,
    MAX_RUNTIME_SHADOW_CASES,
    validate_actual_snapshot,
    validate_runtime_shadow_bounds_snapshot,
    validate_runtime_shadow_cases,
)

RUNTIME_SHADOW_EVALUATOR_VERSION = "restaurant-runtime-shadow-v1"


def compute_runtime_shadow_evaluator_build(
    artifacts: Mapping[str, bytes] | None = None,
) -> str:
    sources = artifacts or {
        "runtime_shadow.py": Path(__file__).read_bytes(),
        "runtime/bounded_runtime.py": Path(bounded_runtime_module.__file__).read_bytes(),
        "runtime/evaluation.py": Path(runtime_evaluation.__file__).read_bytes(),
        "runtime/gateway.py": Path(gateway_module.__file__).read_bytes(),
    }
    digest = hashlib.sha256()
    for name in sorted(sources):
        payload = sources[name]
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


RUNTIME_SHADOW_EVALUATOR_BUILD = compute_runtime_shadow_evaluator_build()


@dataclass(frozen=True)
class RuntimeShadowBounds:
    max_cases: int = MAX_RUNTIME_SHADOW_CASES
    max_concurrency: int = 2
    per_case_timeout_seconds: float = 75.0

    def __post_init__(self) -> None:
        validate_runtime_shadow_bounds_snapshot(self.snapshot())

    def snapshot(self) -> dict[str, int]:
        return {
            "maxCases": self.max_cases,
            "maxConcurrency": self.max_concurrency,
            "perCaseTimeoutMs": round(self.per_case_timeout_seconds * 1000),
        }

    @classmethod
    def from_snapshot(cls, value: Mapping[str, Any]) -> "RuntimeShadowBounds":
        safe = validate_runtime_shadow_bounds_snapshot(value)
        return cls(
            max_cases=safe["maxCases"],
            max_concurrency=safe["maxConcurrency"],
            per_case_timeout_seconds=safe["perCaseTimeoutMs"] / 1000.0,
        )


class RuntimeShadowBatchRunner:
    """Replay frozen inputs through the real read-only runtime, in isolation."""

    evaluator_version = RUNTIME_SHADOW_EVALUATOR_VERSION
    evaluator_build = RUNTIME_SHADOW_EVALUATOR_BUILD

    def __init__(
        self,
        pool: Any | None = None,
        *,
        runtime_factory: Callable[[RuntimeShadowBounds], Any] | None = None,
    ) -> None:
        if runtime_factory is None:
            if pool is None:
                raise ValueError("pool is required when runtime_factory is not supplied")
            gateway = ReadToolGateway(pool, build_restaurant_read_registry())

            def build(bounds: RuntimeShadowBounds) -> BoundedRestaurantRuntime:
                # The durable runtime's own 60 second wallclock remains the inner
                # bound. wait_for below adds a hard per-case ceiling and cleanup.
                wallclock = min(60.0, max(0.25, bounds.per_case_timeout_seconds - 0.25))
                per_tool = min(15.0, max(0.1, wallclock - 0.05))
                cleanup = min(1.0, max(0.01, per_tool / 5))
                return BoundedRestaurantRuntime(
                    gateway,
                    InMemoryRunStore(),
                    budgets=RuntimeBudgets(
                        wallclock_seconds=wallclock,
                        per_tool_timeout_seconds=per_tool,
                        timeout_cleanup_grace_seconds=cleanup,
                    ),
                )

            self._runtime_factory = build
        else:
            self._runtime_factory = runtime_factory

    async def run(
        self,
        eval_set: EvalSetRecord,
        context: AgentOpsContext,
        *,
        bounds: RuntimeShadowBounds,
    ) -> tuple[
        dict[str, Any],
        tuple[dict[str, Any], ...],
        dict[str, dict[str, Any]],
    ]:
        cases = validate_runtime_shadow_cases(eval_set.cases)
        if len(cases) > bounds.max_cases:
            raise AgentOpsValidationError("RUNNER_CASE_LIMIT_EXCEEDED")
        semaphore = asyncio.Semaphore(bounds.max_concurrency)

        trusted_context = TrustedExecutionContext(
            factory_id=context.factory_id,
            business_type="RESTAURANT",
            user_id=context.user_id,
            correlation_id=context.correlation_id,
            authorized_classifications=frozenset({
                DataClassification.FINANCIAL_RESTRICTED,
            }),
        )

        async def one(case: Mapping[str, Any]):
            async with semaphore:
                input_snapshot = case["inputSnapshot"]
                request = GrossMarginDeclineRequest(
                    start_date=input_snapshot["startDate"],
                    end_date=input_snapshot["endDate"],
                    store_top_n=input_snapshot["storeTopN"],
                    dish_top_n=input_snapshot["dishTopN"],
                )
                runtime = self._runtime_factory(bounds)
                result = await asyncio.wait_for(
                    runtime.execute(request, trusted_context),
                    timeout=bounds.per_case_timeout_seconds,
                )
                actual = runtime_result_actual_snapshot(result)
                evaluated = OfflineBatchRunner._evaluate(case, actual)
                return str(case["caseId"]), actual, evaluated

        completed = await asyncio.gather(*(one(case) for case in cases))
        actual_by_case = {case_id: actual for case_id, actual, _ in completed}
        case_results = tuple(evaluated for _, _, evaluated in completed)
        return aggregate_case_results(case_results), case_results, actual_by_case


def runtime_result_actual_snapshot(result: RuntimeResult) -> dict[str, Any]:
    actual = {
        "routeCode": result.route_plan.route_code.value,
        "tools": [envelope.tool_name for envelope in result.evidence],
        "numericTruthRefs": runtime_numeric_truth_refs(result),
        "roundsUsed": result.counters.rounds_used,
        "toolCallsUsed": result.counters.tool_calls_used,
    }
    return validate_actual_snapshot(actual)


def runtime_numeric_truth_refs(result: RuntimeResult) -> dict[str, str]:
    evidence_by_id = {envelope.evidence_id: envelope for envelope in result.evidence}
    refs: dict[str, str] = {}
    for claim in result.outcome.claims:
        envelope = evidence_by_id.get(claim.evidence_id)
        if envelope is None:
            raise AgentOpsValidationError("RUNTIME_RESULT_EVIDENCE_MISSING")
        key = numeric_truth_ref_key(
            tool_name=envelope.tool_name,
            statement_code=claim.statement_code,
            metric=claim.metric,
            dimensions=claim.dimensions,
        )
        existing = refs.get(key)
        if existing is not None and existing != claim.value:
            raise AgentOpsValidationError("RUNTIME_NUMERIC_REF_COLLISION")
        refs[key] = claim.value
    return refs


def numeric_truth_ref_key(
    *,
    tool_name: str,
    statement_code: str,
    metric: str,
    dimensions: Mapping[str, Any],
) -> str:
    payload = json.dumps(
        {
            "toolName": tool_name,
            "statementCode": statement_code,
            "metric": metric,
            "dimensions": {str(key): str(value) for key, value in sorted(dimensions.items())},
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return f"ref:{hashlib.sha256(payload).hexdigest()}"
