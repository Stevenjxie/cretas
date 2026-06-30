"""Regression tests for issue #530 — Java-mirrored 401 envelope for 3 endpoints.

Per https://github.com/j4xie/my-prototype-logistics/issues/530, the new Python
``/api/mobile/{factory_id}/smart-bi/analysis/{production,quality,finance}``
endpoints emit a 96-byte Pydantic-style 401 envelope from the global
``JWTAuthMiddleware``::

    {"success": false, "message": "Missing or invalid Authorization header", "code": "UNAUTHORIZED"}

The customer frontend's axios interceptor expects the 188-byte Java-mirrored
shape emitted by ``backend/java/.../config/JwtAuthInterceptor.java:259-273``::

    {"success":false,"code":401,"message":"未授权，请先登录","severity":"error",
     "actionHint":"会话已过期或未登录, 请重新登录","timestamp":"..."}

These tests lock the Java-mirrored shape for the 3 in-scope endpoints AND
explicitly assert that one out-of-scope analysis endpoint (region) still
emits the old 96-byte shape, so the fix's blast radius is exactly what the
PR claims.

NB: the 401 fires at ASGI-middleware level, NOT at the FastAPI
``Depends(require_analytics_read)`` chain — the middleware short-circuits
before the route is even resolved. That makes the fix a 1-file change in
``backend/python/auth_middleware.py`` plus the new ``_auth_envelope`` helper
under ``smartbi_compat/``.
"""
from __future__ import annotations

import re

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from auth_middleware import JWTAuthMiddleware
from smartbi_compat._auth_envelope import (
    build_unauthorized_body,
    is_smartbi_java_envelope_path,
)


# ============================================================
# Unit — build_unauthorized_body()
# ============================================================


def test_body_has_six_fields_in_java_order():
    """Field order must mirror Java JwtAuthInterceptor LinkedHashMap insertion."""
    body = build_unauthorized_body()
    assert list(body.keys()) == [
        "success",
        "code",
        "message",
        "severity",
        "actionHint",
        "timestamp",
    ]


def test_body_code_is_integer_401():
    """code must be int 401, NOT string 'UNAUTHORIZED' (root mismatch with Java)."""
    body = build_unauthorized_body()
    assert body["code"] == 401
    assert isinstance(body["code"], int)
    assert not isinstance(body["code"], bool)  # bool is subclass of int; guard.


def test_body_default_message_matches_java_canonical():
    """Default message mirrors Java JwtAuthInterceptor calls at lines 126/138/174."""
    body = build_unauthorized_body()
    assert body["message"] == "未授权，请先登录"


def test_body_custom_message_passthrough():
    body = build_unauthorized_body("会话已过期, 请重新登录")
    assert body["message"] == "会话已过期, 请重新登录"


def test_body_severity_action_hint_match_java():
    body = build_unauthorized_body()
    assert body["severity"] == "error"
    assert body["actionHint"] == "会话已过期或未登录, 请重新登录"


def test_body_timestamp_is_iso_string():
    """ISO-8601 LocalDateTime-shaped string, no zone marker (matches Java)."""
    body = build_unauthorized_body()
    ts = body["timestamp"]
    assert isinstance(ts, str)
    # 2026-05-13T18:30:00.123456 or similar — date + 'T' + time
    assert re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}", ts), ts
    # No timezone suffix (Java LocalDateTime.toString omits zone).
    assert not ts.endswith("Z")
    assert "+" not in ts[10:]


def test_body_success_is_false():
    body = build_unauthorized_body()
    assert body["success"] is False


# ============================================================
# Unit — is_smartbi_java_envelope_path()
# ============================================================


@pytest.mark.parametrize(
    "path",
    [
        "/api/mobile/F001/smart-bi/analysis/production",
        "/api/mobile/F001/smart-bi/analysis/production?startDate=2026-01-01",
        "/api/mobile/F001/smart-bi/analysis/quality",
        "/api/mobile/F001/smart-bi/analysis/finance",
        "/api/mobile/F001/smart-bi/analysis/finance/budget-achievement",
        "/api/mobile/F001/smart-bi/analysis/finance/yoy-mom",
        "/api/mobile/F001/smart-bi/analysis/finance/category-comparison",
        "/api/mobile/R_ILTEATRO_REAL/smart-bi/analysis/production",
    ],
)
def test_in_scope_paths_match(path: str):
    assert is_smartbi_java_envelope_path(path) is True


@pytest.mark.parametrize(
    "path",
    [
        # Sister analysis endpoints — out of scope per issue #530.
        "/api/mobile/F001/smart-bi/analysis/region",
        "/api/mobile/F001/smart-bi/analysis/sales",
        "/api/mobile/F001/smart-bi/analysis/procurement",
        "/api/mobile/F001/smart-bi/analysis/inventory",
        "/api/mobile/F001/smart-bi/analysis/department",
        # Other surfaces.
        "/api/mobile/F001/smart-bi/dashboard",
        "/api/health",
        "/api/ota/health",
    ],
)
def test_out_of_scope_paths_dont_match(path: str):
    assert is_smartbi_java_envelope_path(path) is False


# ============================================================
# Integration — JWTAuthMiddleware emits the new shape for 3 endpoints
# ============================================================


@pytest.fixture(scope="module")
def client():
    """FastAPI app with JWTAuthMiddleware wrapping the analysis routers.

    The test does NOT include real routers — the middleware short-circuits
    BEFORE route resolution when the Bearer header is missing/invalid, so
    the 401 envelope is observable from any path even with no router
    mounted at it. We declare a single catch-all so 404s don't mask the
    401 we want to assert.
    """
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret="test-secret-32-bytes-padding-here", enabled=True)

    @app.get("/api/mobile/{factory_id}/smart-bi/analysis/{kind}")
    async def _catchall(factory_id: str, kind: str):
        return {"ok": True, "factory_id": factory_id, "kind": kind}

    @app.get("/api/mobile/{factory_id}/smart-bi/analysis/finance/{sub}")
    async def _catchall_finance(factory_id: str, sub: str):
        return {"ok": True, "factory_id": factory_id, "sub": sub}

    return TestClient(app)


_IN_SCOPE_PATHS = [
    "/api/mobile/F001/smart-bi/analysis/production?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/quality?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/finance?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/finance/budget-achievement?year=2026",
    "/api/mobile/F001/smart-bi/analysis/finance/yoy-mom?periodType=MONTH&startPeriod=2026-01",
    "/api/mobile/F001/smart-bi/analysis/finance/category-comparison?year=2026&compareYear=2025",
]


@pytest.mark.parametrize("path", _IN_SCOPE_PATHS)
def test_in_scope_no_bearer_returns_java_mirrored_envelope(client, path):
    """No Authorization header → middleware emits the 6-field Java-mirrored 401."""
    response = client.get(path)
    assert response.status_code == 401, response.text
    body = response.json()

    # 6 fields in Java order.
    assert list(body.keys()) == [
        "success",
        "code",
        "message",
        "severity",
        "actionHint",
        "timestamp",
    ], body

    assert body["success"] is False
    assert body["code"] == 401 and isinstance(body["code"], int)
    assert body["message"] == "未授权，请先登录"
    assert body["severity"] == "error"
    assert body["actionHint"] == "会话已过期或未登录, 请重新登录"
    assert isinstance(body["timestamp"], str)
    assert re.match(r"^\d{4}-\d{2}-\d{2}T", body["timestamp"])


@pytest.mark.parametrize("path", _IN_SCOPE_PATHS)
def test_in_scope_malformed_bearer_returns_java_mirrored_envelope(client, path):
    """Garbage Bearer → middleware still emits the Java-mirrored 401."""
    response = client.get(path, headers={"Authorization": "Bearer not-a-real-jwt"})
    assert response.status_code == 401, response.text
    body = response.json()
    assert body["code"] == 401 and isinstance(body["code"], int)
    assert body["message"] == "未授权，请先登录"


def test_in_scope_response_is_compact_utf8_not_pretty(client):
    """Raw bytes must be compact JSON AND raw UTF-8 Chinese.

    Java Jackson default emits compact JSON with raw UTF-8 multi-byte chars;
    Python's json.dumps default does the opposite (spaces between tokens +
    \\uXXXX escapes for non-ASCII). Matching the Java byte-shape requires
    explicit ``separators=(',', ':')`` + ``ensure_ascii=False``.
    """
    response = client.get(_IN_SCOPE_PATHS[0])
    text = response.content.decode("utf-8")
    # Compact: ': ' / ', ' between TOKENS would mean pretty mode. We probe
    # via known key-name boundaries (string VALUES may legitimately contain
    # ', ' — e.g. our actionHint — so a blanket `', ' not in text` is wrong).
    for compact_marker, pretty_marker in [
        ('"success":', '"success": '),
        ('"code":', '"code": '),
        ('"message":', '"message": '),
        ('"severity":', '"severity": '),
    ]:
        assert compact_marker in text, f"missing {compact_marker!r} in {text!r}"
        assert pretty_marker not in text, f"found pretty {pretty_marker!r} in {text!r}"
    # Raw UTF-8 Chinese — bytes appear literally, not as \\uXXXX escapes.
    assert "未授权" in text
    assert "\\u672a" not in text


# ============================================================
# Regression — out-of-scope paths keep the old 96B shape
# ============================================================


_OUT_OF_SCOPE_PATHS = [
    "/api/mobile/F001/smart-bi/analysis/region?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/sales?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/procurement?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/inventory?startDate=2026-01-01&endDate=2026-01-31",
    "/api/mobile/F001/smart-bi/analysis/department?startDate=2026-01-01&endDate=2026-01-31",
]


@pytest.mark.parametrize("path", _OUT_OF_SCOPE_PATHS)
def test_out_of_scope_keeps_old_envelope(client, path):
    """Sister endpoints stay on the 96B legacy shape — issue #530 scope is narrow."""
    response = client.get(path)
    assert response.status_code == 401, response.text
    body = response.json()
    # Old 3-field shape with string code 'UNAUTHORIZED'.
    assert body == {
        "success": False,
        "message": "Missing or invalid Authorization header",
        "code": "UNAUTHORIZED",
    }


def test_default_internal_secret_allows_java_to_python_call_when_env_missing(monkeypatch):
    """Java PythonSmartBIClient sends the historical default when env is absent."""
    monkeypatch.delenv("INTERNAL_API_SECRET", raising=False)
    app = FastAPI()
    app.add_middleware(JWTAuthMiddleware, jwt_secret="test-secret-32-bytes-padding-here", enabled=True)

    @app.post("/api/smartbi/{factory_id}/revenue-report/prepare")
    async def _internal_probe(factory_id: str):
        return {"ok": True, "factory_id": factory_id}

    local_client = TestClient(app)
    response = local_client.post(
        "/api/smartbi/DEMO_REST/revenue-report/prepare",
        headers={"X-Internal-Secret": "cretas-internal-2026"},
        json={},
    )

    assert response.status_code == 200, response.text
    assert response.json() == {"ok": True, "factory_id": "DEMO_REST"}
