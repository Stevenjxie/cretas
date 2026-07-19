import asyncio

import pytest

from smartbi.agent.eval import (
    AgentOpsService,
    EvaluatorBuildUnavailableError,
    EvaluatorRegistry,
    InMemoryAgentOpsStore,
    OfflineBatchRunner,
    RunnerBounds,
)
from smartbi.agent.eval.store import AgentOpsAccessError, AgentOpsConflictError
from smartbi.agent.eval.validation import canonical_digest, validate_cases

from .helpers import actual, case, config_snapshot, context, request_id


class FailingRunner(OfflineBatchRunner):
    def __init__(self) -> None:
        self.calls = 0

    async def run(self, *args, **kwargs):
        self.calls += 1
        raise TimeoutError("runner must not execute for a committed request")


@pytest.mark.asyncio
async def test_eval_set_idempotency_is_concurrent_actor_scoped_and_keeps_version_conflicts():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)

    async def create_one(ctx=context(), *, name="Concurrent", description="same"):
        return await service.create_eval_set(
            ctx,
            request_id=request_id(100),
            name=name,
            version=1,
            description=description,
            cases=[case()],
        )

    first, retried = await asyncio.gather(create_one(), create_one())
    assert retried.eval_set_id == first.eval_set_id
    assert retried.request_digest == first.request_digest
    assert first.request_digest == canonical_digest({
        "schemaVersion": "1.0",
        "operationKind": "CREATE_EVAL_SET",
        "name": "Concurrent",
        "version": 1,
        "description": "same",
        "cases": validate_cases([case()]),
    })

    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await create_one(description="changed")
    with pytest.raises(AgentOpsConflictError, match="EVAL_SET_VERSION_EXISTS"):
        await service.create_eval_set(
            context(), request_id=request_id(101), name="concurrent", version=1,
            description="same", cases=[case()],
        )

    other_actor = await create_one(context(user="99"), name="Other actor")
    other_tenant = await create_one(context("R002"), name="Concurrent")
    assert len({first.eval_set_id, other_actor.eval_set_id, other_tenant.eval_set_id}) == 3


@pytest.mark.asyncio
async def test_run_and_rerun_idempotency_are_deterministic_and_operation_bound():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)
    eval_set = await service.create_eval_set(
        context(), request_id=request_id(110), name="Runs", version=1,
        description="", cases=[case()],
    )

    async def run_one(snapshot=actual()):
        return await service.run_experiment(
            context(), request_id=request_id(111), eval_set_id=eval_set.eval_set_id,
            config_snapshot=config_snapshot(), actual_by_case={"margin-1": snapshot},
            bounds=RunnerBounds(),
        )

    first, retried = await asyncio.gather(run_one(), run_one())
    assert retried.experiment_id == first.experiment_id
    assert first.operation_kind == "RUN"
    assert first.source_experiment_id is None
    assert first.request_digest == canonical_digest({
        "schemaVersion": "1.0",
        "operationKind": "RUN",
        "evalSetId": eval_set.eval_set_id,
        "configSnapshot": config_snapshot(),
        "actualSnapshots": {"margin-1": actual()},
        "runnerBounds": RunnerBounds().snapshot(),
    })

    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await run_one(actual(value="11"))

    other_actor_run = await service.run_experiment(
        context(user="99"), request_id=request_id(111),
        eval_set_id=eval_set.eval_set_id, config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual()}, bounds=RunnerBounds(),
    )
    assert other_actor_run.experiment_id != first.experiment_id
    with pytest.raises(AgentOpsAccessError):
        await service.run_experiment(
            context("R002"), request_id=request_id(111),
            eval_set_id=eval_set.eval_set_id, config_snapshot=config_snapshot(),
            actual_by_case={"margin-1": actual()}, bounds=RunnerBounds(),
        )
    tenant_eval_set = await service.create_eval_set(
        context("R002"), request_id=request_id(110), name="Runs", version=1,
        description="", cases=[case()],
    )
    tenant_run = await service.run_experiment(
        context("R002"), request_id=request_id(111),
        eval_set_id=tenant_eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual()}, bounds=RunnerBounds(),
    )
    assert tenant_run.experiment_id != first.experiment_id
    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await service.rerun_experiment(
            context(), first.experiment_id, request_id=request_id(111)
        )

    async def rerun_one():
        return await service.rerun_experiment(
            context(), first.experiment_id, request_id=request_id(112)
        )

    rerun, rerun_retry = await asyncio.gather(rerun_one(), rerun_one())
    assert rerun_retry.experiment_id == rerun.experiment_id
    assert rerun.snapshot_digest == first.snapshot_digest
    assert rerun.operation_kind == "RERUN"
    assert rerun.source_experiment_id == first.experiment_id
    assert rerun.request_digest == canonical_digest({
        "schemaVersion": "1.0",
        "operationKind": "RERUN",
        "sourceExperimentId": first.experiment_id,
        "sourceSnapshotDigest": first.snapshot_digest,
    })

    second_source = await service.run_experiment(
        context(), request_id=request_id(113), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual(value="11")},
        bounds=RunnerBounds(),
    )
    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await service.rerun_experiment(
            context(), second_source.experiment_id, request_id=request_id(112)
        )


@pytest.mark.asyncio
async def test_committed_run_preflight_skips_a_now_failing_runner():
    store = InMemoryAgentOpsStore()
    current = AgentOpsService(store)
    eval_set = await current.create_eval_set(
        context(), request_id=request_id(114), name="Preflight", version=1,
        description="", cases=[case()],
    )
    committed = await current.run_experiment(
        context(), request_id=request_id(115), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(), actual_by_case={"margin-1": actual()},
        bounds=RunnerBounds(),
    )

    failing_runner = FailingRunner()
    retrying = AgentOpsService(store, runner=failing_runner)
    retry = await retrying.run_experiment(
        context(), request_id=request_id(115), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(), actual_by_case={"margin-1": actual()},
        bounds=RunnerBounds(),
    )
    assert retry.experiment_id == committed.experiment_id
    assert failing_runner.calls == 0

    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await retrying.run_experiment(
            context(), request_id=request_id(115), eval_set_id=eval_set.eval_set_id,
            config_snapshot=config_snapshot(),
            actual_by_case={"margin-1": actual(value="11")},
            bounds=RunnerBounds(),
        )
    assert failing_runner.calls == 0


@pytest.mark.asyncio
async def test_committed_rerun_preflight_survives_removed_source_build():
    store = InMemoryAgentOpsStore()
    current = AgentOpsService(store)
    eval_set = await current.create_eval_set(
        context(), request_id=request_id(120), name="Registry", version=1,
        description="", cases=[case()],
    )
    source = await current.run_experiment(
        context(), request_id=request_id(121), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(), actual_by_case={"margin-1": actual()},
        bounds=RunnerBounds(),
    )
    committed_rerun = await current.rerun_experiment(
        context(), source.experiment_id, request_id=request_id(122)
    )
    second_source = await current.run_experiment(
        context(), request_id=request_id(123), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual(value="11")},
        bounds=RunnerBounds(),
    )

    other_build = OfflineBatchRunner()
    other_build.evaluator_build = "f" * 64
    service = AgentOpsService(
        store, registry=EvaluatorRegistry((other_build,))
    )
    retry = await service.rerun_experiment(
        context(), source.experiment_id, request_id=request_id(122)
    )
    assert retry.experiment_id == committed_rerun.experiment_id

    with pytest.raises(AgentOpsConflictError, match="IDEMPOTENCY_KEY_REUSED"):
        await service.rerun_experiment(
            context(), second_source.experiment_id, request_id=request_id(122)
        )

    with pytest.raises(
        EvaluatorBuildUnavailableError, match="EVALUATOR_BUILD_UNAVAILABLE"
    ):
        await service.rerun_experiment(
            context(), source.experiment_id, request_id=request_id(124)
        )
