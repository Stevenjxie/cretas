from __future__ import annotations

from fastapi.testclient import TestClient

from smartbi.agent.eval import AgentOpsService
from smartbi.agent.rollout.runtime_shadow import RuntimeShadowRolloutPolicy
from smartbi.api.agent_ops import get_runtime_shadow_agent_ops_service

from .helpers import context
from .test_http_api import app_and_store, eval_body


def _enabled_env(**overrides: str) -> dict[str, str]:
    values = {
        "AGENT_OPS_RUNTIME_SHADOW_ENABLED": "true",
        "AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST": "R001",
        "AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST": "platform_admin",
        "AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS": "10000",
        "AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT": "test-salt",
    }
    values.update(overrides)
    return values


def _set_rollout_env(monkeypatch, values: dict[str, str]) -> None:
    for name in (
        "AGENT_OPS_RUNTIME_SHADOW_ENABLED",
        "AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST",
        "AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST",
        "AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS",
        "AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT",
    ):
        monkeypatch.delenv(name, raising=False)
    for name, value in values.items():
        monkeypatch.setenv(name, value)


def _import_body() -> dict:
    return {
        "schemaVersion": "1.0",
        "requestId": "00000000-0000-4000-8000-000000000090",
        "name": "runtime corpus",
        "version": 1,
        "description": "trusted",
        "maxCases": 20,
    }


def _run_body() -> dict:
    return {
        "schemaVersion": "1.0",
        "requestId": "00000000-0000-4000-8000-000000000091",
        "evalSetId": "00000000-0000-4000-8000-000000000099",
        "configSnapshot": {},
        "bounds": {},
    }


def test_bucket_vectors_are_fixed_across_python_restarts():
    policy = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(
            AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST="*",
            AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST="*",
            AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT="runtime-shadow-v1",
        )
    )

    assert policy.bucket_for(context()) == 1167
    assert policy.bucket_for(
        context("DEMO_REST", user="1309", role="restaurant_owner")
    ) == 2144
    assert policy.bucket_for(
        context("F006", user="1309", role="factory_super_admin")
    ) == 2008


def test_policy_defaults_closed_and_rejects_incomplete_or_invalid_configuration():
    assert RuntimeShadowRolloutPolicy.from_environ({}).allows(context()) is False

    default_salt = _enabled_env()
    default_salt.pop("AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT")
    assert (
        RuntimeShadowRolloutPolicy.from_environ(default_salt).rollout_salt
        == "runtime-shadow-v1"
    )

    for override in (
        {"AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST": ""},
        {"AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST": ""},
        {"AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS": "0"},
        {"AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS": "10001"},
        {"AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS": "not-a-number"},
        {"AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT": ""},
    ):
        policy = RuntimeShadowRolloutPolicy.from_environ(_enabled_env(**override))
        assert policy.allows(context()) is False


def test_factory_is_exact_role_is_lowercase_and_wildcards_are_supported():
    policy = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(
            AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST="R001,R002",
            AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST="Restaurant_Manager",
        )
    )
    assert policy.allows(context(role="restaurant_manager")) is True
    assert policy.allows(context(factory="r001", role="restaurant_manager")) is False
    assert policy.allows(context(role="platform_admin")) is False

    wildcard = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(
            AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST="*",
            AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST="*",
        )
    )
    assert wildcard.allows(context("DEMO_REST", user="9", role="restaurant_owner"))


def test_basis_point_boundary_and_salt_control_stable_sampling():
    denied = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS="445")
    )
    allowed = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(AGENT_OPS_RUNTIME_SHADOW_SAMPLE_BPS="446")
    )
    salted = RuntimeShadowRolloutPolicy.from_environ(
        _enabled_env(AGENT_OPS_RUNTIME_SHADOW_ROLLOUT_SALT="other-salt")
    )

    assert denied.allows(context()) is False
    assert allowed.allows(context()) is True
    assert salted.bucket_for(context()) == 1374


def test_disabled_and_canary_denied_paths_never_construct_or_call_service(monkeypatch):
    app, _ = app_and_store()
    service_calls = []

    async def forbidden_service() -> AgentOpsService:
        service_calls.append("constructed")
        raise AssertionError("Runtime Shadow service must not be constructed")

    app.dependency_overrides[
        get_runtime_shadow_agent_ops_service
    ] = forbidden_service
    client = TestClient(app)

    _set_rollout_env(monkeypatch, {})
    disabled = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets/import-runtime-corpus",
        json=_import_body(),
    )
    assert disabled.status_code == 503
    assert disabled.json()["detail"] == "AGENT_OPS_RUNTIME_SHADOW_DISABLED"

    _set_rollout_env(
        monkeypatch,
        _enabled_env(
            AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST="*",
            AGENT_OPS_RUNTIME_SHADOW_ROLE_ALLOWLIST="*",
        ),
    )
    untrusted = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets/import-runtime-corpus",
        headers={"X-Test-Auth": "jwt"},
        json=_import_body(),
    )
    assert untrusted.status_code == 401
    assert untrusted.json()["detail"] == "INTERNAL_AUTH_REQUIRED"

    _set_rollout_env(
        monkeypatch,
        _enabled_env(AGENT_OPS_RUNTIME_SHADOW_FACTORY_ALLOWLIST="R002"),
    )
    denied_import = client.post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets/import-runtime-corpus",
        json=_import_body(),
    )
    denied_run = client.post(
        "/api/internal/smartbi/agent/runs/ops/experiments/runtime-shadow",
        json=_run_body(),
    )
    assert denied_import.status_code == 403
    assert denied_run.status_code == 403
    assert denied_import.json()["detail"] == "AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED"
    assert denied_run.json()["detail"] == "AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED"
    assert service_calls == []


def test_incomplete_shadow_canary_does_not_block_ordinary_agent_ops(monkeypatch):
    _set_rollout_env(monkeypatch, {"AGENT_OPS_RUNTIME_SHADOW_ENABLED": "true"})
    app, _ = app_and_store()

    response = TestClient(app).post(
        "/api/internal/smartbi/agent/runs/ops/eval-sets", json=eval_body()
    )

    assert response.status_code == 201
