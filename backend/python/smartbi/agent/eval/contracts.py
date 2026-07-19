"""Small immutable contracts for AgentOps storage and transport."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping


def utc_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class AgentOpsContext:
    factory_id: str
    user_id: str
    role: str
    correlation_id: str


@dataclass(frozen=True)
class EvalSetRecord:
    eval_set_id: str
    factory_id: str
    name: str
    version: int
    description: str
    cases: tuple[Mapping[str, Any], ...]
    content_digest: str
    request_id: str
    request_digest: str
    created_by: str
    created_at: str

    def safe_dict(
        self, *, include_cases: bool = True, offset: int = 0, limit: int | None = None
    ) -> dict[str, Any]:
        result: dict[str, Any] = {
            "evalSetId": self.eval_set_id,
            "name": self.name,
            "version": self.version,
            "description": self.description,
            "caseCount": len(self.cases),
            "contentDigest": self.content_digest,
            "createdBy": self.created_by,
            "createdAt": self.created_at,
        }
        if include_cases:
            start = min(offset, len(self.cases))
            end = len(self.cases) if limit is None else min(len(self.cases), start + limit)
            result["cases"] = [dict(case) for case in self.cases[start:end]]
            result["page"] = _page(len(self.cases), start, end - start)
        return result


@dataclass(frozen=True)
class ExperimentRecord:
    experiment_id: str
    factory_id: str
    eval_set_id: str
    eval_set_name: str
    eval_set_version: int
    eval_set_digest: str
    evaluator_version: str
    evaluator_build: str
    snapshot_digest: str
    request_id: str
    request_digest: str
    operation_kind: str
    source_experiment_id: str | None
    config_snapshot: Mapping[str, Any]
    actual_snapshots: Mapping[str, Mapping[str, Any]]
    runner_bounds: Mapping[str, Any]
    aggregate: Mapping[str, Any]
    case_results: tuple[Mapping[str, Any], ...]
    created_by: str
    created_at: str

    def safe_dict(
        self, *, include_cases: bool = True, offset: int = 0, limit: int | None = None
    ) -> dict[str, Any]:
        result: dict[str, Any] = {
            "experimentId": self.experiment_id,
            "evalSetId": self.eval_set_id,
            "evalSetName": self.eval_set_name,
            "evalSetVersion": self.eval_set_version,
            "evalSetDigest": self.eval_set_digest,
            "evaluatorVersion": self.evaluator_version,
            "evaluatorBuild": self.evaluator_build,
            "snapshotDigest": self.snapshot_digest,
            "operationKind": self.operation_kind,
            "sourceExperimentId": self.source_experiment_id,
            "configSnapshot": dict(self.config_snapshot),
            "runnerBounds": dict(self.runner_bounds),
            "aggregate": dict(self.aggregate),
            "createdBy": self.created_by,
            "createdAt": self.created_at,
        }
        if include_cases:
            start = min(offset, len(self.case_results))
            end = len(self.case_results) if limit is None else min(
                len(self.case_results), start + limit
            )
            case_results = self.case_results[start:end]
            case_ids = [str(item["caseId"]) for item in case_results]
            result["caseResults"] = [dict(case) for case in case_results]
            result["actualSnapshots"] = {
                case_id: dict(self.actual_snapshots[case_id]) for case_id in case_ids
            }
            result["page"] = _page(len(self.case_results), start, end - start)
        return result


def _page(total: int, offset: int, returned: int) -> dict[str, Any]:
    next_offset = offset + returned
    return {
        "offset": offset,
        "returned": returned,
        "total": total,
        "hasMore": next_offset < total,
        "nextOffset": next_offset if next_offset < total else None,
    }
