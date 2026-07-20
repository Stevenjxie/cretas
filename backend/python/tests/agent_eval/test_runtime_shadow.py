import pytest

from smartbi.agent.eval import AgentOpsService, InMemoryAgentOpsStore
from smartbi.agent.eval.runner import OfflineBatchRunner, aggregate_case_results
from smartbi.agent.eval.runtime_shadow import RuntimeShadowBounds
from smartbi.agent.eval.validation import canonical_digest

from .helpers import config_snapshot, context, request_id


def runtime_case(case_id="runtime-00000000-0000-4000-8000-000000000010"):
    refs = {"ref:" + "1" * 64: "12.5"}
    tools = ["restaurant_period_comparison_read.v1"]
    input_snapshot = {
        "startDate": "2026-01-01",
        "endDate": "2026-01-31",
        "storeTopN": 20,
        "dishTopN": 10,
    }
    return {
        "caseId": case_id,
        "inputSnapshot": input_snapshot,
        "expectedRoute": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
        "requiredTools": tools,
        "numericTruthRefs": refs,
        "maxRounds": 1,
        "maxToolCalls": 1,
        "sourceRunId": "00000000-0000-4000-8000-000000000010",
        "evidenceDigests": {
            "inputDigest": canonical_digest(input_snapshot),
            "trajectoryDigest": canonical_digest(tools),
            "numericTruthDigest": canonical_digest(refs),
            "evidenceDigest": "4" * 64,
            "sourceRunDigest": "5" * 64,
        },
    }


class FakeRuntimeShadowRunner:
    evaluator_version = "restaurant-runtime-shadow-v1"
    evaluator_build = "a" * 64

    def __init__(self):
        self.calls = 0

    async def run(self, eval_set, trusted_context, *, bounds):
        self.calls += 1
        assert trusted_context.factory_id == "R001"
        assert bounds.max_cases <= 20
        assert bounds.max_concurrency <= 2
        actuals = {}
        results = []
        for case in eval_set.cases:
            actual = {
                "routeCode": case["expectedRoute"],
                "tools": list(case["requiredTools"]),
                "numericTruthRefs": dict(case["numericTruthRefs"]),
                "roundsUsed": 1,
                "toolCallsUsed": 1,
            }
            actuals[case["caseId"]] = actual
            results.append(OfflineBatchRunner._evaluate(case, actual))
        return aggregate_case_results(results), tuple(results), actuals


@pytest.mark.asyncio
async def test_import_and_runtime_shadow_are_tenant_bound_and_server_generate_actuals():
    store = InMemoryAgentOpsStore()
    store.seed_runtime_corpus("R001", [runtime_case()])
    runner = FakeRuntimeShadowRunner()
    service = AgentOpsService(store, runtime_shadow_runner=runner)

    eval_set = await service.import_runtime_corpus(
        context(),
        request_id=request_id(200),
        name="Runtime truth",
        version=1,
        description="durable corpus",
        max_cases=20,
    )
    assert eval_set.cases[0]["sourceRunId"].endswith("0010")
    assert eval_set.cases[0]["evidenceDigests"]["sourceRunDigest"] == "5" * 64

    experiment = await service.run_runtime_shadow(
        context(),
        request_id=request_id(201),
        eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        bounds=RuntimeShadowBounds(),
    )
    assert experiment.operation_kind == "RUNTIME_SHADOW"
    assert experiment.evaluator_version == "restaurant-runtime-shadow-v1"
    assert experiment.actual_snapshots[eval_set.cases[0]["caseId"]][
        "numericTruthRefs"
    ] == eval_set.cases[0]["numericTruthRefs"]
    assert experiment.aggregate["passRate"] == "1.000000"
    assert runner.calls == 1

    retried = await service.run_runtime_shadow(
        context(),
        request_id=request_id(201),
        eval_set_id=eval_set.eval_set_id,
        config_snapshot=config_snapshot(),
        bounds=RuntimeShadowBounds(),
    )
    assert retried.experiment_id == experiment.experiment_id
    assert runner.calls == 1

    with pytest.raises(ValueError, match="TRUSTED_RUNTIME_CORPUS_EMPTY"):
        await service.import_runtime_corpus(
            context("R002"),
            request_id=request_id(202),
            name="Foreign",
            version=1,
            description="",
            max_cases=20,
        )


def test_runtime_shadow_bounds_are_hard_capped():
    with pytest.raises(ValueError, match="INVALID_MAX_CASES"):
        RuntimeShadowBounds(max_cases=21)
    with pytest.raises(ValueError, match="INVALID_MAX_CONCURRENCY"):
        RuntimeShadowBounds(max_concurrency=3)
    with pytest.raises(ValueError, match="INVALID_CASE_TIMEOUT"):
        RuntimeShadowBounds(per_case_timeout_seconds=75.001)
