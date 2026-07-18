from __future__ import annotations

import asyncio
import json
import uuid
from datetime import datetime, timedelta, timezone

import httpx
import jwt
import pytest
from fastapi import FastAPI

from auth_middleware import JWTAuthMiddleware
from common.middleware.correlation import CorrelationIdMiddleware
from smartbi.agent.runtime.contracts import TrustedExecutionContext
from smartbi.agent.runtime.http_api import (
    _POLL_SECONDS,
    RuntimeComponents,
    _persisted_event_stream,
    get_runtime_components,
    router,
)
from smartbi.agent.runtime.run_contracts import (
    AgentEventType,
    GrossMarginDeclineRequest,
    OutcomeStatus,
    RouteCode,
    RunState,
    RuntimeCounters,
    RuntimeResult,
    StructuredOutcome,
)
from smartbi.agent.runtime.routes import gross_margin_decline_plan
from smartbi.agent.runtime.run_store import (
    STALE_AFTER_SECONDS,
    STALE_RUN_FAILURE_CODE,
    InMemoryRunStore,
    UnsafeRunPayloadError,
)


SECRET = "agent-runtime-internal-secret"
JWT_SECRET = "jwt-test-secret"
BASE_HEADERS = {
    "X-Internal-Secret": SECRET,
    "X-Factory-Id": "REST-A",
    "X-User-Id": "user-1",
    "X-User-Role": "restaurant_owner",
    "X-Business-Type": "RESTAURANT",
}
VALID_BODY = {
    "schemaVersion": "1.0",
    "routeCode": "GROSS_MARGIN_DECLINE_ATTRIBUTION",
    "startDate": "2026-01-01",
    "endDate": "2026-01-31",
    "storeTopN": 20,
    "dishTopN": 10,
}


class MutableClock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 19, 12, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += timedelta(seconds=seconds)


def test_sse_persistence_poll_is_not_a_busy_spin():
    assert _POLL_SECONDS >= 0.2


class PersistingRuntime:
    def __init__(self, store: InMemoryRunStore) -> None:
        self.store = store
        self.received_run_id = None
        self.received_context = None

    async def execute(
        self,
        request: GrossMarginDeclineRequest,
        context: TrustedExecutionContext,
        *,
        run_id: str,
        cancelled,
    ) -> RuntimeResult:
        self.received_run_id = run_id
        self.received_context = context
        plan = gross_margin_decline_plan(request)
        await self.store.create_run(
            run_id, context, plan.route_code, request.safe_dict()
        )
        counters = RuntimeCounters()
        await self.store.append_event(
            run_id,
            context,
            AgentEventType.RUN_STARTED,
            {"routeCode": plan.route_code.value},
            counters=counters,
        )
        outcome = StructuredOutcome(
            OutcomeStatus.PARTIAL,
            RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
            blockers=("CAUSAL_ATTRIBUTION_UNSUPPORTED_BY_READ_CONTRACTS",),
        )
        await self.store.compare_and_set_terminal(
            run_id,
            context,
            expected_state=RunState.RUNNING,
            terminal_state=RunState.PARTIAL,
            outcome=outcome,
            counters=counters,
            terminal_event_type=AgentEventType.RUN_COMPLETED,
        )
        return RuntimeResult(
            run_id,
            RunState.PARTIAL,
            plan,
            outcome,
            (),
            counters,
        )


@pytest.fixture
def app(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", SECRET)
    clock = MutableClock()
    store = InMemoryRunStore(clock=clock)
    runtime = PersistingRuntime(store)
    application = FastAPI()
    application.include_router(router)
    application.add_middleware(JWTAuthMiddleware, jwt_secret=JWT_SECRET, enabled=True)
    application.add_middleware(CorrelationIdMiddleware)
    application.dependency_overrides[
        get_runtime_components
    ] = lambda: RuntimeComponents(runtime, store)
    application.state.test_store = store
    application.state.test_runtime = runtime
    application.state.test_clock = clock
    return application


@pytest.fixture
async def client(app):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as value:
        yield value


@pytest.mark.asyncio
async def test_post_streams_only_persisted_event_v1_and_get_replays_after_sequence(
    client, app
):
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=BASE_HEADERS
    )

    assert response.status_code == 200
    run_id = response.headers["X-Agent-Run-Id"]
    assert str(uuid.UUID(run_id)) == run_id
    assert app.state.test_runtime.received_run_id == run_id
    frames = _parse_sse(response.text)
    assert [frame["id"] for frame in frames] == ["1", "2"]
    assert all(frame["event"] == "agent.event.v1" for frame in frames)
    assert [frame["data"]["sequence"] for frame in frames] == [1, 2]
    assert [frame["data"]["eventType"] for frame in frames] == [
        "RUN_STARTED",
        "RUN_COMPLETED",
    ]
    assert all(frame["data"]["schemaVersion"] == "1.0" for frame in frames)

    replay = await client.get(
        f"/api/internal/smartbi/agent/runs/{run_id}/events",
        params={"afterSequence": 1},
        headers=BASE_HEADERS,
    )
    assert replay.status_code == 200
    body = replay.json()
    assert body["state"] == "PARTIAL"
    assert body["nextEventSequence"] == 2
    assert [event["sequence"] for event in body["events"]] == [2]
    assert body["terminalOutcome"]["status"] == "PARTIAL"
    assert "evidence" not in body


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("headers", "expected"),
    [
        ({}, 401),
        ({"X-Internal-Secret": "wrong"}, 401),
    ],
)
async def test_missing_or_wrong_internal_auth_rejected(client, headers, expected):
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )
    assert response.status_code == expected


@pytest.mark.asyncio
async def test_valid_jwt_alone_is_rejected_on_internal_prefix(client):
    padded = JWT_SECRET.encode() + b"\x00" * (32 - len(JWT_SECRET.encode()))
    token = jwt.encode(
        {
            "userId": "user-1",
            "factoryId": "REST-A",
            "role": "restaurant_owner",
        },
        padded,
        algorithm="HS256",
    )
    response = await client.post(
        "/api/internal/smartbi/agent/runs",
        json=VALID_BODY,
        headers={"Authorization": f"Bearer {token}"},
    )
    assert response.status_code == 401


@pytest.mark.asyncio
async def test_correlation_id_is_validated_or_generated(client, app):
    headers = dict(BASE_HEADERS)
    headers["X-Correlation-ID"] = "java-run.corr-123"
    supplied = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )

    assert supplied.status_code == 200
    assert app.state.test_runtime.received_context.correlation_id == "java-run.corr-123"

    generated = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=BASE_HEADERS
    )

    assert generated.status_code == 200
    generated_id = app.state.test_runtime.received_context.correlation_id
    assert str(uuid.UUID(generated_id)) == generated_id


@pytest.mark.asyncio
@pytest.mark.parametrize("value", ["bad correlation", "x" * 129])
async def test_invalid_correlation_id_is_stable_422_without_echo(client, value):
    headers = dict(BASE_HEADERS)
    headers["X-Correlation-ID"] = value
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )

    assert response.status_code == 422
    assert response.json() == {"detail": "INVALID_CORRELATION_ID"}
    assert value not in response.text


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "missing_header",
    ["X-Factory-Id", "X-User-Id"],
)
async def test_missing_trusted_tenant_or_actor_rejected(client, missing_header):
    headers = dict(BASE_HEADERS)
    headers.pop(missing_header)
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )
    assert response.status_code == 403


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("header", "value"),
    [
        ("X-User-Role", "restaurant_staff"),
        ("X-Business-Type", "FACTORY"),
    ],
)
async def test_non_financial_role_or_non_restaurant_business_rejected(
    client, header, value
):
    headers = dict(BASE_HEADERS)
    headers[header] = value
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )
    assert response.status_code == 403


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("header", "value"),
    [
        ("X-Factory-Id", "bad tenant/escape"),
        ("X-User-Id", "x" * 129),
        ("X-User-Role", "restaurant_owner\nspoof"),
        ("X-Business-Type", "RESTAURANT/OTHER"),
    ],
)
async def test_internal_identity_headers_are_bounded_identifiers(client, header, value):
    headers = dict(BASE_HEADERS)
    headers[header] = value
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=headers
    )
    assert response.status_code == 403


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "mutation",
    [
        {"factoryId": "OTHER"},
        {"tenant_id": "OTHER"},
        {"start_date": "2026-01-01"},
        {"schemaVersion": "2.0"},
        {"routeCode": "FREE_FORM_CHAT"},
        {"startDate": "not-a-date"},
        {"endDate": "2027-01-02"},
        {"storeTopN": 51},
        {"dishTopN": 21},
    ],
)
async def test_body_tenant_route_window_and_bounds_fail_closed(client, mutation):
    body = dict(VALID_BODY)
    body.update(mutation)
    response = await client.post(
        "/api/internal/smartbi/agent/runs", json=body, headers=BASE_HEADERS
    )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_query_tenant_identity_is_rejected_not_used_for_routing(client):
    response = await client.post(
        "/api/internal/smartbi/agent/runs?factoryId=REST-B",
        json=VALID_BODY,
        headers=BASE_HEADERS,
    )
    assert response.status_code == 422


@pytest.mark.asyncio
async def test_cross_tenant_get_is_indistinguishable_from_missing(client):
    created = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=BASE_HEADERS
    )
    run_id = created.headers["X-Agent-Run-Id"]
    other = dict(BASE_HEADERS)
    other["X-Factory-Id"] = "REST-B"

    cross = await client.get(
        f"/api/internal/smartbi/agent/runs/{run_id}/events", headers=other
    )
    missing = await client.get(
        "/api/internal/smartbi/agent/runs/20202020-2020-4020-8020-202020202020/events",
        headers=BASE_HEADERS,
    )
    assert cross.status_code == missing.status_code == 404
    assert cross.json() == missing.json()


def _trusted_context(factory_id: str = "REST-A") -> TrustedExecutionContext:
    return TrustedExecutionContext(
        factory_id=factory_id,
        business_type="RESTAURANT",
        user_id="user-1",
        correlation_id="http-reconcile",
        authorized_classifications=frozenset(),
    )


async def _create_running_run(app, factory_id: str = "REST-A") -> str:
    run_id = str(uuid.uuid4())
    await app.state.test_store.create_run(
        run_id,
        _trusted_context(factory_id),
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        {key: value for key, value in VALID_BODY.items() if key != "schemaVersion"},
    )
    return run_id


@pytest.mark.asyncio
async def test_reconcile_stale_http_exact_fresh_stale_and_terminal_contract(
    client, app
):
    run_id = await _create_running_run(app)

    fresh = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert fresh.status_code == 200
    assert fresh.json() == {
        "schemaVersion": "1.0",
        "runId": run_id,
        "result": "NOT_STALE",
        "state": "RUNNING",
        "failureCode": None,
    }

    app.state.test_clock.advance(STALE_AFTER_SECONDS)
    stale = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert stale.status_code == 200
    assert stale.json() == {
        "schemaVersion": "1.0",
        "runId": run_id,
        "result": "RECONCILED",
        "state": "FAILED",
        "failureCode": STALE_RUN_FAILURE_CODE,
    }

    terminal = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert terminal.status_code == 200
    assert terminal.json() == {
        **stale.json(),
        "result": "ALREADY_TERMINAL",
    }
    replay = await client.get(
        f"/api/internal/smartbi/agent/runs/{run_id}/events",
        headers=BASE_HEADERS,
    )
    assert [event["eventType"] for event in replay.json()["events"]] == ["RUN_FAILED"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("suffix", "content"),
    [
        ("?afterSeconds=120", None),
        ("", b"{}"),
        ("", b"null"),
        ("", b" "),
    ],
)
async def test_reconcile_rejects_any_query_or_nonempty_body(
    client, app, suffix, content
):
    run_id = await _create_running_run(app)
    response = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale{suffix}",
        content=content,
        headers=BASE_HEADERS,
    )
    assert response.status_code == 422
    assert response.json() == {"detail": "RECONCILE_INPUT_FORBIDDEN"}


@pytest.mark.asyncio
async def test_reconcile_auth_role_business_and_tenant_fail_closed(client, app):
    run_id = await _create_running_run(app)
    cases = [
        ({}, 401),
        ({**BASE_HEADERS, "X-User-Role": "restaurant_staff"}, 403),
        ({**BASE_HEADERS, "X-Business-Type": "FACTORY"}, 403),
    ]
    for headers, expected_status in cases:
        response = await client.post(
            f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
            headers=headers,
        )
        assert response.status_code == expected_status

    other_headers = {**BASE_HEADERS, "X-Factory-Id": "REST-B"}
    cross = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=other_headers,
    )
    missing = await client.post(
        "/api/internal/smartbi/agent/runs/40404040-4040-4040-8040-404040404040/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert cross.status_code == missing.status_code == 404
    assert cross.json() == missing.json() == {"detail": "RUN_NOT_FOUND"}


@pytest.mark.asyncio
async def test_reconcile_has_no_get_mutation_and_store_errors_are_controlled(
    client, app
):
    run_id = await _create_running_run(app)
    get_response = await client.get(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert get_response.status_code == 405
    assert (
        await app.state.test_store.load_run(run_id, _trusted_context())
    ).state is RunState.RUNNING

    class BrokenStore:
        async def reconcile_stale_run(self, run_id, context):
            raise RuntimeError("raw database secret")

    app.dependency_overrides[get_runtime_components] = lambda: RuntimeComponents(
        app.state.test_runtime, BrokenStore()
    )
    response = await client.post(
        f"/api/internal/smartbi/agent/runs/{run_id}/reconcile-stale",
        headers=BASE_HEADERS,
    )
    assert response.status_code == 503
    assert response.json() == {"detail": "AGENT_RUN_STORE_UNAVAILABLE"}
    assert "secret" not in response.text


class TamperedReplayStore:
    def __init__(self, delegate: InMemoryRunStore) -> None:
        self.delegate = delegate

    async def load_run(self, run_id, context):
        return await self.delegate.load_run(run_id, context)

    async def events_for(self, run_id, context, *, after_sequence=0):
        raise UnsafeRunPayloadError("raw persisted secret must not escape")


@pytest.mark.asyncio
async def test_tampered_persisted_replay_returns_controlled_503_without_echo(
    client, app
):
    created = await client.post(
        "/api/internal/smartbi/agent/runs", json=VALID_BODY, headers=BASE_HEADERS
    )
    run_id = created.headers["X-Agent-Run-Id"]
    app.dependency_overrides[get_runtime_components] = lambda: RuntimeComponents(
        app.state.test_runtime, TamperedReplayStore(app.state.test_store)
    )

    response = await client.get(
        f"/api/internal/smartbi/agent/runs/{run_id}/events", headers=BASE_HEADERS
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "AGENT_RUN_STORE_UNAVAILABLE"}
    assert "secret" not in response.text


class DisconnectedRequest:
    async def is_disconnected(self):
        return True


@pytest.mark.asyncio
async def test_disconnect_requests_best_effort_cancel_without_force_cancelling_task():
    store = InMemoryRunStore()
    context = TrustedExecutionContext(
        factory_id="REST-A",
        business_type="RESTAURANT",
        user_id="user-1",
        correlation_id="corr",
        authorized_classifications=frozenset(),
    )
    run_id = "30303030-3030-4030-8030-303030303030"
    await store.create_run(
        run_id,
        context,
        RouteCode.GROSS_MARGIN_DECLINE_ATTRIBUTION,
        {key: value for key, value in VALID_BODY.items() if key != "schemaVersion"},
    )
    blocker = asyncio.Event()
    task = asyncio.create_task(blocker.wait())
    cancellation_requested = asyncio.Event()

    streamed = [
        item
        async for item in _persisted_event_stream(
            DisconnectedRequest(),
            store,
            run_id,
            context,
            task,
            cancellation_requested,
        )
    ]

    assert streamed == []
    assert cancellation_requested.is_set()
    assert not task.cancelled()
    assert (await store.load_run(run_id, context)).state is RunState.RUNNING
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


def _parse_sse(value: str):
    frames = []
    for block in value.strip().split("\n\n"):
        fields = {}
        for line in block.splitlines():
            key, item = line.split(": ", 1)
            fields[key] = json.loads(item) if key == "data" else item
        frames.append(fields)
    return frames
