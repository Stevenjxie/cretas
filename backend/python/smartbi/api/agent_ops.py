"""Internal-only AgentOps API; tenant identity is never accepted from clients."""

from __future__ import annotations

import re
import uuid
from typing import Any, Literal, Mapping

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator

from common.middleware.correlation import get_correlation_id
from smartbi.agent.eval import (
    AgentOpsContext,
    AgentOpsService,
    OfflineBatchRunner,
    PostgresAgentOpsStore,
    RunnerBounds,
    RuntimeShadowBatchRunner,
    RuntimeShadowBounds,
)
from smartbi.agent.eval.store import AgentOpsAccessError, AgentOpsConflictError
from smartbi.agent.eval.runner import EvaluatorBuildUnavailableError
from smartbi.agent.eval.validation import AgentOpsValidationError, ensure_payload_budget
from smartbi.agent.rollout import RuntimeShadowRolloutPolicy
from smartbi.config import get_pg_pool

router = APIRouter(prefix="/api/internal/smartbi/agent/runs/ops")

_ADMIN_ROLES = frozenset({
    "factory_super_admin", "platform_admin", "permission_admin",
    "restaurant_manager", "restaurant_owner",
})
_SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


class EvalCaseBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    case_id: str = Field(alias="caseId", min_length=1, max_length=128)
    expected_route: Literal["GROSS_MARGIN_DECLINE_ATTRIBUTION"] = Field(alias="expectedRoute")
    required_tools: list[str] = Field(alias="requiredTools", max_length=10)
    numeric_truth_refs: dict[str, str] = Field(alias="numericTruthRefs", max_length=100)
    max_rounds: int = Field(default=2, alias="maxRounds", ge=1, le=2)
    max_tool_calls: int = Field(default=10, alias="maxToolCalls", ge=1, le=10)


class CreateEvalSetBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    request_id: uuid.UUID = Field(alias="requestId")
    name: str = Field(min_length=1, max_length=96)
    version: int = Field(ge=1, le=1_000_000)
    description: str = Field(default="", max_length=500)
    cases: list[EvalCaseBody] = Field(min_length=1, max_length=100)


class ImportRuntimeCorpusBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    request_id: uuid.UUID = Field(alias="requestId")
    name: str = Field(min_length=1, max_length=96)
    version: int = Field(ge=1, le=1_000_000)
    description: str = Field(default="", max_length=500)
    max_cases: int = Field(default=20, alias="maxCases", ge=1, le=20)


class RunnerBoundsBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_cases: int = Field(default=100, alias="maxCases", ge=1, le=100)
    max_concurrency: int = Field(default=4, alias="maxConcurrency", ge=1, le=4)
    per_case_timeout_ms: int = Field(default=1000, alias="perCaseTimeoutMs", ge=50, le=5000)


class RunExperimentBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    request_id: uuid.UUID = Field(alias="requestId")
    eval_set_id: uuid.UUID = Field(alias="evalSetId")
    config_snapshot: dict[str, Any] = Field(alias="configSnapshot")
    actual_snapshots: dict[str, dict[str, Any]] = Field(alias="actualSnapshots", max_length=100)
    bounds: RunnerBoundsBody = Field(default_factory=RunnerBoundsBody)

    @model_validator(mode="after")
    def enforce_total_budget(self):
        ensure_payload_budget(
            self.model_dump(by_alias=True, mode="json"),
            "AGENT_OPS_REQUEST_PAYLOAD_TOO_LARGE",
        )
        return self


class RuntimeShadowBoundsBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    max_cases: int = Field(default=20, alias="maxCases", ge=1, le=20)
    max_concurrency: int = Field(default=2, alias="maxConcurrency", ge=1, le=2)
    per_case_timeout_ms: int = Field(
        default=75_000, alias="perCaseTimeoutMs", ge=1_000, le=75_000
    )


class RunRuntimeShadowBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    request_id: uuid.UUID = Field(alias="requestId")
    eval_set_id: uuid.UUID = Field(alias="evalSetId")
    config_snapshot: dict[str, Any] = Field(alias="configSnapshot")
    bounds: RuntimeShadowBoundsBody = Field(default_factory=RuntimeShadowBoundsBody)

    @model_validator(mode="after")
    def enforce_total_budget(self):
        ensure_payload_budget(
            self.model_dump(by_alias=True, mode="json"),
            "AGENT_OPS_REQUEST_PAYLOAD_TOO_LARGE",
        )
        return self


class RerunExperimentBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schema_version: Literal["1.0"] = Field(alias="schemaVersion")
    request_id: uuid.UUID = Field(alias="requestId")


async def get_agent_ops_service() -> AgentOpsService:
    pool = await _require_pg_pool()
    return AgentOpsService(PostgresAgentOpsStore(pool), OfflineBatchRunner())


async def _require_pg_pool():
    try:
        pool = await get_pg_pool()
    except Exception as exc:
        raise HTTPException(status_code=503, detail="AGENT_OPS_STORE_UNAVAILABLE") from exc
    if pool is None:
        raise HTTPException(status_code=503, detail="AGENT_OPS_STORE_UNAVAILABLE")
    return pool


def require_runtime_shadow_context(request: Request) -> AgentOpsContext:
    """Apply trusted identity validation and canary policy before service wiring."""

    context = require_agent_ops_context(request)
    policy = RuntimeShadowRolloutPolicy.from_environ()
    if not policy.master_enabled:
        raise HTTPException(status_code=503, detail="AGENT_OPS_RUNTIME_SHADOW_DISABLED")
    if not policy.allows(context):
        raise HTTPException(
            status_code=403, detail="AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED"
        )
    return context


def require_agent_ops_context(request: Request) -> AgentOpsContext:
    if getattr(request.state, "auth_method", None) != "internal":
        raise HTTPException(status_code=401, detail="INTERNAL_AUTH_REQUIRED")
    if any(key in request.query_params for key in ("factoryId", "factory_id", "tenantId", "tenant_id")):
        raise HTTPException(status_code=422, detail="TENANT_PARAMETER_FORBIDDEN")
    factory_id = getattr(request.state, "factory_id", None)
    user_id = getattr(request.state, "user_id", None)
    role = getattr(request.state, "role", None)
    business_type = getattr(request.state, "business_type", None)
    if not isinstance(factory_id, str) or not _SAFE_ID.fullmatch(factory_id):
        raise HTTPException(status_code=403, detail="TRUSTED_TENANT_REQUIRED")
    if not isinstance(user_id, str) or not _SAFE_ID.fullmatch(user_id):
        raise HTTPException(status_code=403, detail="TRUSTED_ACTOR_REQUIRED")
    if business_type != "RESTAURANT":
        raise HTTPException(status_code=403, detail="RESTAURANT_BUSINESS_REQUIRED")
    if not isinstance(role, str) or role.lower() not in _ADMIN_ROLES:
        raise HTTPException(status_code=403, detail="AGENT_OPS_ADMIN_REQUIRED")
    correlation_id = get_correlation_id()
    if correlation_id == "-":
        correlation_id = str(uuid.uuid4())
    if not _SAFE_ID.fullmatch(correlation_id):
        raise HTTPException(status_code=422, detail="INVALID_CORRELATION_ID")
    return AgentOpsContext(factory_id, user_id, role.lower(), correlation_id)


async def get_runtime_shadow_agent_ops_service(
    _context: AgentOpsContext = Depends(require_runtime_shadow_context),
) -> AgentOpsService:
    """Build the Runtime Shadow runner only after its request gate succeeds."""

    pool = await _require_pg_pool()
    return AgentOpsService(
        PostgresAgentOpsStore(pool),
        OfflineBatchRunner(),
        runtime_shadow_runner=RuntimeShadowBatchRunner(pool),
    )


@router.post("/eval-sets", status_code=201)
async def create_eval_set(
    body: CreateEvalSetBody,
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        record = await service.create_eval_set(
            context,
            request_id=str(body.request_id),
            name=body.name,
            version=body.version,
            description=body.description,
            cases=[case.model_dump(by_alias=True) for case in body.cases],
        )
        return _bounded_response(record.safe_dict(include_cases=False))
    except AgentOpsConflictError as exc:
        raise HTTPException(status_code=409, detail=_conflict_code(exc)) from exc
    except AgentOpsValidationError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.post("/eval-sets/import-runtime-corpus", status_code=201)
async def import_runtime_corpus(
    body: ImportRuntimeCorpusBody,
    context: AgentOpsContext = Depends(require_runtime_shadow_context),
    service: AgentOpsService = Depends(get_runtime_shadow_agent_ops_service),
):
    try:
        record = await service.import_runtime_corpus(
            context,
            request_id=str(body.request_id),
            name=body.name,
            version=body.version,
            description=body.description,
            max_cases=body.max_cases,
        )
        return _bounded_response(record.safe_dict(include_cases=False))
    except AgentOpsConflictError as exc:
        raise HTTPException(status_code=409, detail=_conflict_code(exc)) from exc
    except AgentOpsValidationError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.get("/eval-sets")
async def list_eval_sets(
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    return _bounded_response({
        "items": [
            item.safe_dict(include_cases=False)
            for item in await service.list_eval_sets(context)
        ]
    })


@router.get("/eval-sets/{eval_set_id}")
async def get_eval_set(
    eval_set_id: uuid.UUID,
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=25, ge=1, le=50),
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        return _bounded_response(
            (await service.get_eval_set(context, str(eval_set_id))).safe_dict(
                offset=offset, limit=limit
            )
        )
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EVAL_SET_NOT_FOUND") from exc


@router.post("/experiments", status_code=201)
async def run_experiment(
    body: RunExperimentBody,
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        record = await service.run_experiment(
            context,
            request_id=str(body.request_id),
            eval_set_id=str(body.eval_set_id),
            config_snapshot=body.config_snapshot,
            actual_by_case=body.actual_snapshots,
            bounds=RunnerBounds(
                max_cases=body.bounds.max_cases,
                max_concurrency=body.bounds.max_concurrency,
                per_case_timeout_seconds=body.bounds.per_case_timeout_ms / 1000.0,
            ),
        )
        return _bounded_response(record.safe_dict(include_cases=False))
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EVAL_SET_NOT_FOUND") from exc
    except AgentOpsConflictError as exc:
        raise HTTPException(status_code=409, detail=_conflict_code(exc)) from exc
    except (AgentOpsValidationError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except TimeoutError as exc:
        raise HTTPException(status_code=422, detail="RUNNER_CASE_TIMEOUT") from exc


@router.post("/experiments/runtime-shadow", status_code=201)
async def run_runtime_shadow(
    body: RunRuntimeShadowBody,
    context: AgentOpsContext = Depends(require_runtime_shadow_context),
    service: AgentOpsService = Depends(get_runtime_shadow_agent_ops_service),
):
    try:
        record = await service.run_runtime_shadow(
            context,
            request_id=str(body.request_id),
            eval_set_id=str(body.eval_set_id),
            config_snapshot=body.config_snapshot,
            bounds=RuntimeShadowBounds(
                max_cases=body.bounds.max_cases,
                max_concurrency=body.bounds.max_concurrency,
                per_case_timeout_seconds=body.bounds.per_case_timeout_ms / 1000.0,
            ),
        )
        return _bounded_response(record.safe_dict(include_cases=False))
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EVAL_SET_NOT_FOUND") from exc
    except AgentOpsConflictError as exc:
        raise HTTPException(status_code=409, detail=_conflict_code(exc)) from exc
    except (AgentOpsValidationError, ValueError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except TimeoutError as exc:
        raise HTTPException(status_code=504, detail="RUNTIME_SHADOW_CASE_TIMEOUT") from exc


@router.get("/experiments")
async def list_experiments(
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    return _bounded_response({
        "items": [
            item.safe_dict(include_cases=False)
            for item in await service.list_experiments(context)
        ]
    })


@router.get("/experiments/{experiment_id}")
async def get_experiment(
    experiment_id: uuid.UUID,
    offset: int = Query(default=0, ge=0),
    limit: int = Query(default=25, ge=1, le=50),
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        return _bounded_response(
            (await service.get_experiment(context, str(experiment_id))).safe_dict(
                offset=offset, limit=limit
            )
        )
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EXPERIMENT_NOT_FOUND") from exc


@router.get("/experiments/{experiment_id}/compare")
async def compare_experiments(
    experiment_id: uuid.UUID,
    baseline_id: uuid.UUID = Query(alias="baselineId"),
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        return _bounded_response(
            await service.compare_experiments(
                context, str(experiment_id), str(baseline_id)
            )
        )
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EXPERIMENT_NOT_FOUND") from exc


@router.post("/experiments/{experiment_id}/rerun", status_code=201)
async def rerun_experiment(
    experiment_id: uuid.UUID,
    body: RerunExperimentBody,
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        record = await service.rerun_experiment(
            context, str(experiment_id), request_id=str(body.request_id)
        )
        return _bounded_response(record.safe_dict(include_cases=False))
    except AgentOpsAccessError as exc:
        raise HTTPException(status_code=404, detail="EXPERIMENT_NOT_FOUND") from exc
    except AgentOpsConflictError as exc:
        raise HTTPException(status_code=409, detail=_conflict_code(exc)) from exc
    except EvaluatorBuildUnavailableError as exc:
        raise HTTPException(
            status_code=409, detail="EVALUATOR_BUILD_UNAVAILABLE"
        ) from exc
    except AgentOpsValidationError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.get("/traces/{run_id}")
async def get_trace(
    run_id: uuid.UUID,
    after_sequence: int = Query(default=0, alias="afterSequence", ge=0),
    limit: int = Query(default=100, ge=1, le=100),
    context: AgentOpsContext = Depends(require_agent_ops_context),
    service: AgentOpsService = Depends(get_agent_ops_service),
):
    try:
        return _bounded_response(
            await service.trace(
                context,
                str(run_id),
                after_sequence=after_sequence,
                limit=limit,
            )
        )
    except AgentOpsAccessError as exc:
        # Missing and cross-tenant traces intentionally have the same response.
        raise HTTPException(status_code=404, detail="RUN_NOT_FOUND") from exc


def _bounded_response(value: Mapping[str, Any]) -> Mapping[str, Any]:
    try:
        ensure_payload_budget(value, "AGENT_OPS_RESPONSE_PAYLOAD_TOO_LARGE")
    except AgentOpsValidationError as exc:
        raise HTTPException(
            status_code=500, detail="AGENT_OPS_RESPONSE_BUDGET_EXCEEDED"
        ) from exc
    return value


def _conflict_code(exc: AgentOpsConflictError) -> str:
    code = str(exc)
    if code in {"EVAL_SET_VERSION_EXISTS", "IDEMPOTENCY_KEY_REUSED"}:
        return code
    return "AGENT_OPS_CONFLICT"
