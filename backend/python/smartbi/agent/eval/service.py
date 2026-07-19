"""Application service for the bounded AgentOps surface."""

from __future__ import annotations

from typing import Any, Mapping, Sequence

from .contracts import AgentOpsContext, EvalSetRecord, ExperimentRecord
from .runner import EvaluatorRegistry, OfflineBatchRunner, RunnerBounds
from .store import AgentOpsStore, new_eval_set_record, new_experiment_record
from .validation import (
    AgentOpsValidationError,
    canonical_digest,
    validate_cases,
    validate_config_snapshot,
    validate_actual_snapshots,
    validate_name,
    validate_request_id,
)


class AgentOpsService:
    def __init__(
        self,
        store: AgentOpsStore,
        runner: OfflineBatchRunner | None = None,
        *,
        registry: EvaluatorRegistry | None = None,
    ) -> None:
        if runner is not None and registry is not None:
            raise ValueError("provide runner or registry, not both")
        self._store = store
        self._registry = registry or EvaluatorRegistry((runner or OfflineBatchRunner(),))

    async def create_eval_set(
        self,
        context: AgentOpsContext,
        *,
        request_id: str,
        name: str,
        version: int,
        description: str,
        cases: Sequence[Mapping[str, Any]],
    ) -> EvalSetRecord:
        normalized = validate_cases(cases)
        safe_name = validate_name(name)
        safe_request_id = validate_request_id(request_id)
        safe_description = description.strip()[:500]
        request_digest = canonical_digest({
            "schemaVersion": "1.0",
            "operationKind": "CREATE_EVAL_SET",
            "name": safe_name,
            "version": version,
            "description": safe_description,
            "cases": normalized,
        })
        record = new_eval_set_record(
            context,
            name=safe_name,
            version=version,
            description=safe_description,
            cases=normalized,
            content_digest=canonical_digest(normalized),
            request_id=safe_request_id,
            request_digest=request_digest,
        )
        return await self._store.create_eval_set(context, record)

    async def list_eval_sets(self, context):
        return await self._store.list_eval_sets(context)

    async def get_eval_set(self, context, eval_set_id):
        return await self._store.get_eval_set(context, eval_set_id)

    async def run_experiment(
        self,
        context: AgentOpsContext,
        *,
        request_id: str,
        eval_set_id: str,
        config_snapshot: Mapping[str, Any],
        actual_by_case: Mapping[str, Mapping[str, Any]],
        bounds: RunnerBounds,
    ) -> ExperimentRecord:
        eval_set = await self._store.get_eval_set(context, eval_set_id)
        config = validate_config_snapshot(config_snapshot)
        if set(actual_by_case) != {case["caseId"] for case in eval_set.cases}:
            raise AgentOpsValidationError("ACTUAL_CASE_SET_MISMATCH")
        normalized_actual = validate_actual_snapshots(actual_by_case)
        bounds_snapshot = bounds.snapshot()
        safe_request_id = validate_request_id(request_id)
        request_digest = canonical_digest({
            "schemaVersion": "1.0",
            "operationKind": "RUN",
            "evalSetId": eval_set.eval_set_id,
            "configSnapshot": config,
            "actualSnapshots": normalized_actual,
            "runnerBounds": bounds_snapshot,
        })
        existing = await self._store.preflight_experiment_request(
            context, safe_request_id, request_digest
        )
        if existing is not None:
            return existing
        return await self._run_with(
            context,
            runner=self._registry.current(),
            eval_set=eval_set,
            config=config,
            normalized_actual=normalized_actual,
            bounds=bounds,
            bounds_snapshot=bounds_snapshot,
            request_id=safe_request_id,
            request_digest=request_digest,
            operation_kind="RUN",
            source_experiment_id=None,
        )

    async def _run_with(
        self,
        context: AgentOpsContext,
        *,
        runner: OfflineBatchRunner,
        eval_set: EvalSetRecord,
        config: Mapping[str, Any],
        normalized_actual: Mapping[str, Mapping[str, Any]],
        bounds: RunnerBounds,
        bounds_snapshot: Mapping[str, Any],
        request_id: str,
        request_digest: str,
        operation_kind: str,
        source_experiment_id: str | None,
    ) -> ExperimentRecord:
        aggregate, case_results = await runner.run(
            eval_set, normalized_actual, bounds=bounds
        )
        snapshot_digest = canonical_digest({
            "evalSetDigest": eval_set.content_digest,
            "evaluatorVersion": runner.evaluator_version,
            "evaluatorBuild": runner.evaluator_build,
            "configSnapshot": config,
            "actualSnapshots": normalized_actual,
            "runnerBounds": bounds_snapshot,
        })
        record = new_experiment_record(
            context,
            eval_set=eval_set,
            evaluator_version=runner.evaluator_version,
            evaluator_build=runner.evaluator_build,
            snapshot_digest=snapshot_digest,
            request_id=request_id,
            request_digest=request_digest,
            operation_kind=operation_kind,
            source_experiment_id=source_experiment_id,
            config_snapshot=config,
            actual_snapshots=normalized_actual,
            runner_bounds=bounds_snapshot,
            aggregate=aggregate,
            case_results=case_results,
        )
        return await self._store.save_experiment(context, record)

    async def rerun_experiment(
        self, context: AgentOpsContext, experiment_id: str, *, request_id: str
    ) -> ExperimentRecord:
        source = await self._store.get_experiment(context, experiment_id)
        safe_request_id = validate_request_id(request_id)
        request_digest = canonical_digest({
            "schemaVersion": "1.0",
            "operationKind": "RERUN",
            "sourceExperimentId": source.experiment_id,
            "sourceSnapshotDigest": source.snapshot_digest,
        })
        existing = await self._store.preflight_experiment_request(
            context, safe_request_id, request_digest
        )
        if existing is not None:
            return existing
        runner = self._registry.resolve(
            source.evaluator_version, source.evaluator_build
        )
        eval_set = await self._store.get_eval_set(context, source.eval_set_id)
        config = validate_config_snapshot(source.config_snapshot)
        normalized_actual = validate_actual_snapshots(source.actual_snapshots)
        bounds = RunnerBounds.from_snapshot(source.runner_bounds)
        bounds_snapshot = bounds.snapshot()
        return await self._run_with(
            context,
            runner=runner,
            eval_set=eval_set,
            config=config,
            normalized_actual=normalized_actual,
            bounds=bounds,
            bounds_snapshot=bounds_snapshot,
            request_id=safe_request_id,
            request_digest=request_digest,
            operation_kind="RERUN",
            source_experiment_id=source.experiment_id,
        )

    async def list_experiments(self, context):
        return await self._store.list_experiments(context)

    async def get_experiment(self, context, experiment_id):
        return await self._store.get_experiment(context, experiment_id)

    async def compare_experiments(self, context, experiment_id, baseline_id):
        current = await self._store.get_experiment(context, experiment_id)
        baseline = await self._store.get_experiment(context, baseline_id)
        current_cases = {item["caseId"]: item for item in current.case_results}
        baseline_cases = {item["caseId"]: item for item in baseline.case_results}
        shared = sorted(set(current_cases) & set(baseline_cases))
        improved = [case_id for case_id in shared if current_cases[case_id]["passed"] and not baseline_cases[case_id]["passed"]]
        regressed = [case_id for case_id in shared if baseline_cases[case_id]["passed"] and not current_cases[case_id]["passed"]]
        return {
            "experimentId": current.experiment_id,
            "baselineExperimentId": baseline.experiment_id,
            "sameEvalSetVersion": (
                current.eval_set_id == baseline.eval_set_id
                and current.eval_set_version == baseline.eval_set_version
            ),
            "currentEvaluatorVersion": current.evaluator_version,
            "baselineEvaluatorVersion": baseline.evaluator_version,
            "evaluatorChanged": current.evaluator_version != baseline.evaluator_version,
            "currentEvaluatorBuild": current.evaluator_build,
            "baselineEvaluatorBuild": baseline.evaluator_build,
            "evaluatorBuildChanged": current.evaluator_build != baseline.evaluator_build,
            "currentEvalSetVersion": current.eval_set_version,
            "baselineEvalSetVersion": baseline.eval_set_version,
            "passRateDelta": format(
                float(current.aggregate["passRate"]) - float(baseline.aggregate["passRate"]),
                ".6f",
            ),
            "improvedCaseIds": improved,
            "regressedCaseIds": regressed,
            "sharedCaseCount": len(shared),
            "promptSnapshotChanged": _digest_changed(
                current, baseline, "promptSnapshotDigest"
            ),
            "modelSnapshotChanged": _digest_changed(
                current, baseline, "modelSnapshotDigest"
            ),
            "toolSnapshotChanged": _digest_changed(
                current, baseline, "toolSnapshotDigest"
            ),
        }

    async def trace(self, context, run_id, *, after_sequence, limit):
        return await self._store.load_trace(
            context, run_id, after_sequence=after_sequence, limit=limit
        )


def _digest_changed(
    current: ExperimentRecord, baseline: ExperimentRecord, key: str
) -> bool:
    return current.config_snapshot[key] != baseline.config_snapshot[key]
