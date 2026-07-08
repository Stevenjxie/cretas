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

import pytest

from auth_middleware import JWTAuthMiddleware


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


def _mw():
    # jwt_secret is irrelevant to the internal-secret branch; any >=1 char works.
    return JWTAuthMiddleware(_Echo(), jwt_secret="test-secret-only-for-jwt-branch")


_HARDCODED_OLD_FALLBACK = "cretas-internal-2026"


def test_correct_secret_when_env_set_takes_internal_branch(monkeypatch):
    monkeypatch.setenv("INTERNAL_API_SECRET", "real-prod-secret-xyz")
    mw = _mw()
    status, echo = _run(mw, {"x-internal-secret": "real-prod-secret-xyz", "x-factory-id": "F001"})
    assert echo.reached is True
    assert echo.state.get("auth_method") == "internal"
    assert echo.state.get("factory_id") == "F001"
    assert status == 200


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
