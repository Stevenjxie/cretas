from dataclasses import replace

import pytest

from smartbi.agent.eval import AgentOpsService, InMemoryAgentOpsStore, RunnerBounds
from smartbi.agent.eval.store import AgentOpsAccessError, AgentOpsConflictError, AgentOpsStoreError
from smartbi.agent.eval.validation import AgentOpsValidationError
from smartbi.agent.eval.validation import validate_config_snapshot

from .helpers import actual, case, config_snapshot, context, request_id


@pytest.mark.asyncio
async def test_eval_set_version_is_immutable_and_duplicate_name_version_conflicts():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)
    first = await service.create_eval_set(
        context(), request_id=request_id(1), name="Margin regression", version=1,
        description="baseline", cases=[case()]
    )
    with pytest.raises(AgentOpsConflictError):
        await service.create_eval_set(
            context(), request_id=request_id(2), name="margin regression", version=1,
            description="changed", cases=[case("two")]
        )
    loaded = await service.get_eval_set(context(), first.eval_set_id)
    assert loaded.content_digest == first.content_digest
    assert loaded.cases[0]["caseId"] == "margin-1"


@pytest.mark.asyncio
async def test_eval_set_and_experiment_ids_are_tenant_fail_closed():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)
    eval_set = await service.create_eval_set(
        context(), request_id=request_id(3), name="Tenant eval", version=1,
        description="", cases=[case()]
    )
    experiment = await service.run_experiment(
        context(), request_id=request_id(4), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual()}, bounds=RunnerBounds(),
    )
    with pytest.raises(AgentOpsAccessError):
        await service.get_eval_set(context("R002"), eval_set.eval_set_id)
    with pytest.raises(AgentOpsAccessError):
        await service.get_experiment(context("R002"), experiment.experiment_id)


@pytest.mark.asyncio
async def test_runner_bounds_and_exact_case_set_are_enforced():
    service = AgentOpsService(InMemoryAgentOpsStore())
    eval_set = await service.create_eval_set(
        context(), request_id=request_id(5), name="Bounds", version=1,
        description="", cases=[case()]
    )
    with pytest.raises(AgentOpsValidationError, match="ACTUAL_CASE_SET_MISMATCH"):
        await service.run_experiment(
            context(), request_id=request_id(6), eval_set_id=eval_set.eval_set_id,
            config_snapshot=config_snapshot(), actual_by_case={}, bounds=RunnerBounds(),
        )
    with pytest.raises(ValueError):
        RunnerBounds(max_concurrency=5)


@pytest.mark.asyncio
async def test_experiment_compare_reports_regression_and_is_reproducible():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)
    eval_set = await service.create_eval_set(
        context(), request_id=request_id(7), name="Compare", version=3,
        description="", cases=[case()]
    )
    baseline = await service.run_experiment(
        context(), request_id=request_id(8), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual()}, bounds=RunnerBounds(),
    )
    current = await service.run_experiment(
        context(), request_id=request_id(9), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual(value="11")}, bounds=RunnerBounds(),
    )
    same_again = await service.run_experiment(
        context(), request_id=request_id(10), eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        actual_by_case={"margin-1": actual(value="11")}, bounds=RunnerBounds(),
    )
    comparison = await service.compare_experiments(
        context(), current.experiment_id, baseline.experiment_id
    )
    assert comparison["regressedCaseIds"] == ["margin-1"]
    assert comparison["passRateDelta"] == "-1.000000"
    assert current.snapshot_digest == same_again.snapshot_digest
    assert current.request_digest == same_again.request_digest
    assert comparison["promptSnapshotChanged"] is False
    assert current.evaluator_version == "restaurant-offline-v1"
    assert current.actual_snapshots["margin-1"]["numericTruthRefs"] == {
        "ev-1:fact-1": "11"
    }
    rerun = await service.rerun_experiment(
        context(), current.experiment_id, request_id=request_id(11)
    )
    assert rerun.experiment_id != current.experiment_id
    assert rerun.snapshot_digest == current.snapshot_digest
    with pytest.raises(AgentOpsStoreError, match="snapshot digest mismatch"):
        await store.save_experiment(
            context(), replace(current, snapshot_digest="0" * 64)
        )


@pytest.mark.asyncio
async def test_trace_reuses_seeded_truth_redacts_and_is_tenant_scoped():
    store = InMemoryAgentOpsStore()
    service = AgentOpsService(store)
    run_id = "00000000-0000-0000-0000-000000000001"
    store.seed_trace("R001", run_id, {
        "runId": run_id,
        "state": "COMPLETED",
        "rawPrompt": "must not escape",
        "events": [{"sequence": 1, "eventType": "RUN_STARTED", "payload": {"token": "x"}}],
    })
    trace = await service.trace(context(), run_id, after_sequence=0, limit=1)
    assert "rawPrompt" not in trace
    assert "token" not in trace["events"][0]["payload"]
    assert "factoryId" not in trace
    with pytest.raises(AgentOpsAccessError):
        await service.trace(context("R002"), run_id, after_sequence=0, limit=1)
    with pytest.raises(AgentOpsAccessError):
        await service.trace(
            context("R001", "99", "operator"),
            run_id,
            after_sequence=0,
            limit=1,
        )


def test_config_allows_only_snapshot_digests_not_raw_prompt_or_identity():
    assert validate_config_snapshot(config_snapshot())["promptSnapshotDigest"] == "1" * 64
    with pytest.raises(AgentOpsValidationError, match="INVALID_CONFIG_SNAPSHOT"):
        validate_config_snapshot({"model": "fixture"})
    with pytest.raises(AgentOpsValidationError, match="INVALID_SNAPSHOT_DIGEST"):
        validate_config_snapshot({**config_snapshot(), "toolSnapshotDigest": "not-a-sha256"})
    with pytest.raises(AgentOpsValidationError, match="INVALID_CONFIG_SNAPSHOT"):
        validate_config_snapshot({"rawPrompt": "show secrets"})
    with pytest.raises(AgentOpsValidationError, match="INVALID_CONFIG_SNAPSHOT"):
        validate_config_snapshot({"tenantId": "R002"})
    with pytest.raises(AgentOpsValidationError, match="INVALID_CONFIG_SNAPSHOT"):
        validate_config_snapshot({**config_snapshot(), "temperature": float("nan")})


def test_actual_snapshot_rejects_inconsistent_execution_counters():
    broken = actual()
    broken["roundsUsed"] = 0
    with pytest.raises(AgentOpsValidationError, match="INCONSISTENT_ACTUAL_COUNTERS"):
        from smartbi.agent.eval.validation import validate_actual_snapshot

        validate_actual_snapshot(broken)
