"""2026-07-08 security fix regression: the X-Internal-Secret gate in
JWTAuthMiddleware must FAIL CLOSED when INTERNAL_API_SECRET is unset.

Before this fix, `os.environ.get("INTERNAL_API_SECRET") or "cretas-internal-2026"`
meant an unset env var activated a hardcoded fallback secret that is public
(this repo is public on GitHub). Since the internal branch also bypasses
require_factory_match, an attacker knowing the constant could send arbitrary
X-Factory-Id / X-User-Role and get cross-tenant, RBAC-bypassing access.

These tests drive the middleware's ASGI __call__ directly against an
in-process echo app, asserting:
  1. env set + correct secret  -> internal branch taken (auth_method=internal)
  2. env set + the OLD hardcoded fallback -> rejected (falls through to 401)
  3. env UNSET + the old fallback -> rejected (fail-closed, no bypass)
  4. env UNSET + any secret -> rejected (fail-closed)
  5. env set + blank X-Internal-Secret -> rejected
"""
from __future__ import annotations

import asyncio
import time

import pytest
import jwt as pyjwt

from auth_middleware import JWTAuthMiddleware, PUBLIC_PREFIXES


class _Echo:
    """Downstream ASGI app: records that it was reached + the scope state the
    middleware injected, then emits a trivial 200 so __call__ completes."""

    def __init__(self):
        self.reached = False
        self.state = None

    async def __call__(self, scope, receive, send):
        self.reached = True
        self.state = dict(scope.get("state", {}))
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})


def _run(middleware, headers, path="/api/smartbi/gold/restaurant/tiered-answer"):
    """Drive one request through the middleware; return (status, downstream)."""
    downstream = middleware.app  # the _Echo instance
    scope = {
        "type": "http",
        "path": path,
        "headers": [(k.encode("latin-1"), v.encode("latin-1")) for k, v in headers.items()],
    }
    sent = []

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(msg):
        sent.append(msg)

    asyncio.run(middleware(scope, receive, send))
    status = next((m["status"] for m in sent if m["type"] == "http.response.start"), None)
    return status, downstream


def _mw(enabled=True, jwt_secret="test-secret-only-for-jwt-branch"):
    # jwt_secret is irrelevant to the internal-secret branch; any >=1 char works.
    return JWTAuthMiddleware(
        _Echo(),
        jwt_secret=jwt_secret,
        enabled=enabled,
    )


_HARDCODED_OLD_FALLBACK = "cretas-internal-2026"
_OWNER_ACTION_PATH = "/api/smartbi/restaurant/sections/owner-action-chat"
_AGENT_RUN_PATH = "/api/internal/smartbi/agent/runs"
_LABEL_QC_PATH = "/api/label-qc/analyze"
_GENERAL_ANALYSIS_PATHS = (
    "/api/chat/general-analysis",
    "/api/chat/general-analysis-stream",
)


def test_general_analysis_paths_are_not_public_prefixes():
    for path in _GENERAL_ANALYSIS_PATHS:
        assert not any(path.startswith(prefix) for prefix in PUBLIC_PREFIXES)


def test_correct_secret_when_env_set_takes_internal_branch(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": "real-prod-secret-xyz", "x-factory-id": "F001"})
    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"
    assert status == 200


def test_agent_run_prefix_requires_secret_and_injects_trusted_identity(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    missing_status, missing_echo = _run(_mw(), {}, path=_AGENT_RUN_PATH)
    wrong_status, wrong_echo = _run(
        _mw(),
        {"x-internal-secret": "wrong"},
        path=f"{_AGENT_RUN_PATH}/10101010-1010-4010-8010-101010101010/events",
    )
    status, echo = _run(
        _mw(),
        {
            "x-internal-secret": "real-prod-secret-xyz",
            "x-factory-id": "REST-1",
            "x-user-id": "user-7",
            "x-user-role": "restaurant_owner",
            "x-business-type": "RESTAURANT",
        },
        path=_AGENT_RUN_PATH,
    )

    assert (missing_status, wrong_status) == (401, 401)
    assert missing_echo.reached is False
    assert wrong_echo.reached is False
    assert status == 200
    assert echo.state == {
        "factory_id": "REST-1",
        "user_id": "user-7",
        "auth_method": "internal",
        "business_type": "RESTAURANT",
        "role": "restaurant_owner",
    }


def test_label_qc_requires_internal_secret(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")

    missing_status, missing_echo = _run(_mw(), {}, path=_LABEL_QC_PATH)
    status, echo = _run(
        _mw(),
        {
            "x-internal-secret": "real-prod-secret-xyz",
            "x-factory-id": "F001",
        },
        path=_LABEL_QC_PATH,
    )

    assert missing_status == 401
    assert missing_echo.reached is False
    assert status == 200
    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"


@pytest.mark.parametrize("path", _GENERAL_ANALYSIS_PATHS)
def test_general_analysis_paths_reject_missing_auth(monkeypatch, path):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")

    status, echo = _run(_mw(), {}, path=path)

    assert status == 401
    assert echo.reached is False


@pytest.mark.parametrize("path", _GENERAL_ANALYSIS_PATHS)
def test_general_analysis_paths_accept_internal_trusted_tenant(monkeypatch, path):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")

    status, echo = _run(
        _mw(),
        {
            "x-internal-secret": "real-prod-secret-xyz",
            "x-factory-id": "F001",
            "x-user-id": "7",
        },
        path=path,
    )

    assert status == 200
    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"


@pytest.mark.parametrize("path", _GENERAL_ANALYSIS_PATHS)
def test_general_analysis_paths_accept_jwt_trusted_tenant(monkeypatch, path):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    raw_secret = "jwt-real-secret"
    padded_secret = raw_secret.encode("utf-8") + b"\x00" * (
        32 - len(raw_secret.encode("utf-8"))
    )
    token = pyjwt.encode(
        {
            "factoryId": "F001",
            "userId": 7,
            "role": "factory_super_admin",
            "exp": int(time.time()) + 300,
        },
        padded_secret,
        algorithm="HS256",
    )

    status, echo = _run(
        _mw(jwt_secret=raw_secret),
        {"authorization": f"Bearer {token}"},
        path=path,
    )

    assert status == 200
    assert echo.reached is True
    assert echo.state.get("auth_method") == "jwt"
    assert echo.state.get("factory_id") == "F001"


def test_old_hardcoded_fallback_rejected_when_env_set(monkeypatch):
    """The public constant must NOT authenticate even while a real secret is set."""
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": _HARDCODED_OLD_FALLBACK, "x-factory-id": "F999"})
    # internal branch NOT taken -> no Bearer -> 401
    assert echo.reached is False
    assert status == 401


def test_fail_closed_when_env_unset_rejects_old_fallback(monkeypatch):
    """THE fix: env unset must NOT resurrect the public fallback secret."""
    monkeypatch.delenv("INTERNAL_API_SECRET", raising=False)
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": _HARDCODED_OLD_FALLBACK, "x-factory-id": "F999", "x-user-role": "factory_super_admin"})
    assert echo.reached is False   # no cross-tenant bypass
    assert status == 401


def test_fail_closed_when_env_unset_rejects_any_secret(monkeypatch):
    monkeypatch.delenv("INTERNAL_API_SECRET", raising=False)
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": "anything-at-all", "x-factory-id": "F999"})
    assert echo.reached is False
    assert status == 401


def test_blank_internal_secret_header_rejected_when_env_set(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": "", "x-factory-id": "F001"})
    assert echo.reached is False
    assert status == 401


def test_empty_string_env_is_fail_closed(monkeypatch):
    """INTERNAL_API_SECRET='' (blank, not unset) must also fail closed — a
    blank expected secret must never match a blank header."""
    monkeypatch.setenv("INTERNAL_API_SECRET", "")
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": "", "x-factory-id": "F001"})
    assert echo.reached is False
    assert status == 401


def test_owner_action_exact_route_rejects_missing_secret_before_public_prefix(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()

    status, echo = _run(mw, {}, path=_OWNER_ACTION_PATH)

    assert echo.reached is False
    assert status == 401


def test_owner_action_exact_route_rejects_wrong_secret_before_public_prefix(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()

    status, echo = _run(
        mw,
        {"x-internal-secret": "wrong-secret", "x-factory-id": "F001"},
        path=_OWNER_ACTION_PATH,
    )

    assert echo.reached is False
    assert status == 401


def test_owner_action_exact_route_accepts_correct_internal_identity(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()

    status, echo = _run(
        mw,
        {"x-internal-secret": "real-prod-secret-xyz", "x-factory-id": "F001"},
        path=_OWNER_ACTION_PATH,
    )

    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"
    assert status == 200


def test_non_target_restaurant_section_requires_authentication(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()

    status, echo = _run(
        mw,
        {},
        path="/api/smartbi/restaurant/sections/list",
    )

    assert echo.reached is False
    assert status == 401


def test_smartbi_excel_prefix_requires_authentication(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")

    status, echo = _run(
        _mw(),
        {},
        path="/api/smartbi/excel/auto-parse-status/42",
    )

    assert echo.reached is False
    assert status == 401


def test_blank_jwt_secret_rejects_token_signed_with_zero_key(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    zero_key_token = pyjwt.encode(
        {"factoryId": "F001", "userId": 7, "exp": int(time.time()) + 300},
        b"\x00" * 32,
        algorithm="HS256",
    )

    status, echo = _run(
        _mw(jwt_secret=""),
        {"authorization": f"Bearer {zero_key_token}"},
        path="/api/smartbi/restaurant/sections/list",
    )

    assert echo.reached is False
    assert status == 401


def test_blank_jwt_secret_does_not_disable_valid_internal_auth(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")

    status, echo = _run(
        _mw(jwt_secret="   "),
        {"x-internal-secret": "real-prod-secret-xyz", "x-factory-id": "F001"},
        path="/api/smartbi/restaurant/sections/list",
    )

    assert echo.reached is True
    assert echo.state.get("factory_id") == "F001"
    assert status == 200


def test_valid_jwt_authenticates_newly_protected_restaurant_prefix(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    raw_secret = "jwt-real-secret"
    padded_secret = raw_secret.encode("utf-8") + b"\x00" * (
        32 - len(raw_secret.encode("utf-8"))
    )
    token = pyjwt.encode(
        {
            "factoryId": "REST-1",
            "userId": 7,
            "role": "restaurant_manager",
            "exp": int(time.time()) + 300,
        },
        padded_secret,
        algorithm="HS256",
    )

    status, echo = _run(
        _mw(jwt_secret=raw_secret),
        {"authorization": f"Bearer {token}"},
        path="/api/smartbi/restaurant/sections/list",
    )

    assert echo.reached is True
    assert echo.state.get("auth_method") == "jwt"
    assert echo.state.get("factory_id") == "REST-1"
    assert status == 200


@pytest.mark.parametrize("headers", [
    {},
    {"x-internal-secret": "wrong-secret", "x-factory-id": "F001"},
])
def test_owner_action_remains_fail_closed_when_general_auth_is_disabled(
    monkeypatch,
    headers,
):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw(enabled=False)

    status, echo = _run(mw, headers, path=_OWNER_ACTION_PATH)

    assert echo.reached is False
    assert status == 401


def test_owner_action_correct_internal_identity_works_when_general_auth_is_disabled(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw(enabled=False)

    status, echo = _run(
        mw,
        {"x-internal-secret": "real-prod-secret-xyz", "x-factory-id": "F001"},
        path=_OWNER_ACTION_PATH,
    )

    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"
    assert status == 200
