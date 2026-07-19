from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

from smartbi.agent.eval import (
    AgentOpsService,
    EvaluatorRegistry,
    InMemoryAgentOpsStore,
    OfflineBatchRunner,
)
from smartbi.api.agent_ops import get_agent_ops_service, router

from .helpers import case, config_snapshot


def app_and_store():
    app = FastAPI()
    store = InMemoryAgentOpsStore()

    @app.middleware("http")
    async def trusted(request: Request, call_next):
        request.state.auth_method = request.headers.get("X-Test-Auth", "internal")
        request.state.factory_id = request.headers.get("X-Test-Factory", "R001")
        request.state.user_id = "42"
        request.state.role = request.headers.get("X-Test-Role", "platform_admin")
        request.state.business_type = "RESTAURANT"
        return await call_next(request)

    app.include_router(router)
    app.dependency_overrides[get_agent_ops_service] = lambda: AgentOpsService(store)
    return app, store


def eval_body():
    return {
        "schemaVersion": "1.0",
        "requestId": "00000000-0000-4000-8000-000000000001",
        "name": "API eval",
        "version": 1,
        "description": "fixture",
        "cases": [case()],
    }


def test_api_rejects_non_internal_and_tenant_query_injection():
    app, _ = app_and_store()
    client = TestClient(app)
    assert client.get(
        "/api/internal/smartbi/agent/runs/ops/eval-sets",
        headers={"X-Test-Auth": "jwt"},
    ).status_code == 401
    response = client.get(
        "/api/internal/smartbi/agent/runs/ops/eval-sets?tenantId=R002"
    )
    assert response.status_code == 422
    assert response.json()["detail"] == "TENANT_PARAMETER_FORBIDDEN"


def test_all_writes_require_request_id_and_total_request_budget_stays_bounded():
    app, _ = app_and_store()
    client = TestClient(app)
    missing = eval_body()
    missing.pop("requestId")
    assert client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=missing
    ).status_code == 422
    assert client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments",
        json={
            "schemaVersion": "1.0",
            "evalSetId": "00000000-0000-4000-8000-000000000099",
            "configSnapshot": config_snapshot(),
            "actualSnapshots": {},
            "bounds": {},
        },
    ).status_code == 422
    assert client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments/00000000-0000-4000-8000-000000000099/rerun",
        json={"schemaVersion": "1.0"},
    ).status_code == 422

    oversized = {
        "schemaVersion": "1.0",
        "requestId": "00000000-0000-4000-8000-000000000098",
        "evalSetId": "00000000-0000-4000-8000-000000000099",
        "configSnapshot": config_snapshot(),
        "actualSnapshots": {"margin-1": {"oversized": "x" * (4 * 1024 * 1024)}},
        "bounds": {},
    }
    response = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments", json=oversized
    )
    assert response.status_code == 422
    assert "AGENT_OPS_REQUEST_PAYLOAD_TOO_LARGE" in response.text


def test_api_create_duplicate_and_cross_tenant_idor_are_fail_closed():
    app, _ = app_and_store()
    client = TestClient(app)
    created = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=eval_body()
    )
    assert created.status_code == 201
    assert "cases" not in created.json()
    eval_set_id = created.json()["evalSetId"]
    retry = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=eval_body()
    )
    assert retry.status_code == 201
    assert retry.json()["evalSetId"] == eval_set_id
    reused = eval_body()
    reused["description"] = "different"
    response = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=reused
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "IDEMPOTENCY_KEY_REUSED"
    version_conflict = eval_body()
    version_conflict["requestId"] = "00000000-0000-4000-8000-000000000002"
    response = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=version_conflict
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "EVAL_SET_VERSION_EXISTS"
    foreign = client.get(
        f"/api/internal/smartbi/agent/runs/ops/eval-sets/{eval_set_id}",
        headers={"X-Test-Factory": "R002"},
    )
    assert foreign.status_code == 404
    assert foreign.json()["detail"] == "EVAL_SET_NOT_FOUND"


def test_api_experiment_and_compare_contract():
    app, _ = app_and_store()
    client = TestClient(app)
    eval_set_id = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=eval_body()
    ).json()["evalSetId"]
    body = {
        "schemaVersion": "1.0",
        "requestId": "00000000-0000-4000-8000-000000000003",
        "evalSetId": eval_set_id,
        "configSnapshot": config_snapshot(),
        "actualSnapshots": {
            "margin-1": {
                "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                "tools": ["restaurant_margin_read", "restaurant_cost_read"],
                "numericTruthRefs": {"ev-1:fact-1": "12.5"},
                "roundsUsed": 1,
                "toolCallsUsed": 2,
            }
        },
        "bounds": {"maxCases": 100, "maxConcurrency": 2, "perCaseTimeoutMs": 1000},
    }
    first = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments", json=body
    )
    assert first.status_code == 201
    assert first.json()["evaluatorVersion"] == "restaurant-offline-v1"
    assert "caseResults" not in first.json()
    retried = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments", json=body
    )
    assert retried.json()["experimentId"] == first.json()["experimentId"]
    changed = {
        **body,
        "actualSnapshots": {
            "margin-1": {
                **body["actualSnapshots"]["margin-1"],
                "numericTruthRefs": {"ev-1:fact-1": "11"},
            }
        },
    }
    reused = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments", json=changed
    )
    assert reused.status_code == 409
    assert reused.json()["detail"] == "IDEMPOTENCY_KEY_REUSED"
    second_body = {**body, "requestId": "00000000-0000-4000-8000-000000000004"}
    second = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments", json=second_body
    )
    comparison = client.get(
        f"/api/internal/smartbi/agent/runs/ops/experiments/{second.json()['experimentId']}/compare",
        params={"baselineId": first.json()["experimentId"]},
    )
    assert comparison.status_code == 200
    assert comparison.json()["passRateDelta"] == "0.000000"
    assert comparison.json()["promptSnapshotChanged"] is False

    detail = client.get(
        f"/api/internal/smartbi/agent/runs/ops/experiments/{first.json()['experimentId']}",
        params={"offset": 0, "limit": 1},
    )
    assert detail.status_code == 200
    assert detail.json()["page"]["returned"] == 1
    assert set(detail.json()["actualSnapshots"]) == {"margin-1"}

    rerun = client.post(
        f"/api/internal/smartbi/agent/runs/ops/experiments/{first.json()['experimentId']}/rerun",
        json={
            "schemaVersion": "1.0",
            "requestId": "00000000-0000-4000-8000-000000000005",
        },
    )
    assert rerun.status_code == 201
    assert rerun.json()["snapshotDigest"] == first.json()["snapshotDigest"]
    assert rerun.json()["operationKind"] == "RERUN"
    assert rerun.json()["sourceExperimentId"] == first.json()["experimentId"]


def test_api_rerun_fails_closed_when_source_evaluator_build_is_unavailable():
    app, store = app_and_store()
    client = TestClient(app)
    eval_set_id = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=eval_body()
    ).json()["evalSetId"]
    run = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments",
        json={
            "schemaVersion": "1.0",
            "requestId": "00000000-0000-4000-8000-000000000020",
            "evalSetId": eval_set_id,
            "configSnapshot": config_snapshot(),
            "actualSnapshots": {
                "margin-1": {
                    "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                    "tools": ["restaurant_margin_read", "restaurant_cost_read"],
                    "numericTruthRefs": {"ev-1:fact-1": "12.5"},
                    "roundsUsed": 1,
                    "toolCallsUsed": 2,
                }
            },
            "bounds": {
                "maxCases": 100,
                "maxConcurrency": 2,
                "perCaseTimeoutMs": 1000,
            },
        },
    )
    assert run.status_code == 201
    unavailable = OfflineBatchRunner()
    unavailable.evaluator_build = "f" * 64
    registry = EvaluatorRegistry((unavailable,))
    app.dependency_overrides[get_agent_ops_service] = lambda: AgentOpsService(
        store, registry=registry
    )
    response = client.post(
        f"/api/internal/smartbi/agent/runs/ops/experiments/{run.json()['experimentId']}/rerun",
        json={
            "schemaVersion": "1.0",
            "requestId": "00000000-0000-4000-8000-000000000021",
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "EVALUATOR_BUILD_UNAVAILABLE"


def test_api_requires_restaurant_admin_and_keeps_store_unavailable_as_503():
    app, _ = app_and_store()
    client = TestClient(app)
    assert client.get(
        "/api/internal/smartbi/agent/runs/ops/eval-sets",
        headers={"X-Test-Role": "operator"},
    ).status_code == 403

    unavailable = FastAPI()

    @unavailable.middleware("http")
    async def trusted_without_store(request: Request, call_next):
        request.state.auth_method = "internal"
        request.state.factory_id = "R001"
        request.state.user_id = "42"
        request.state.role = "platform_admin"
        request.state.business_type = "RESTAURANT"
        return await call_next(request)

    unavailable.include_router(router)
    async def unavailable_service():
        raise HTTPException(status_code=503, detail="AGENT_OPS_STORE_UNAVAILABLE")

    unavailable.dependency_overrides[get_agent_ops_service] = unavailable_service
    response = TestClient(unavailable).get(
        "/api/internal/smartbi/agent/runs/ops/eval-sets"
    )
    assert response.status_code == 503
    assert response.json()["detail"] == "AGENT_OPS_STORE_UNAVAILABLE"
