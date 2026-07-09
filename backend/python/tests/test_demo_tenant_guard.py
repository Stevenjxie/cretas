from __future__ import annotations

import asyncio
import json
import time

import jwt

from auth_middleware import JWTAuthMiddleware


JWT_SECRET = "demo-tenant-guard-test-secret-at-least-32-bytes"


class _Echo:
    def __init__(self):
        self.reached = False
        self.state = None

    async def __call__(self, scope, receive, send):
        self.reached = True
        self.state = dict(scope.get("state", {}))
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})


def _token(factory_id: str) -> str:
    return jwt.encode(
        {
            "factoryId": factory_id,
            "userId": 1,
            "sub": "demo",
            "role": "factory_super_admin",
            "exp": int(time.time()) + 3600,
        },
        JWT_SECRET,
        algorithm="HS256",
    )


def _run(path: str, *, method: str, factory_id: str, query_string: bytes = b""):
    app = _Echo()
    middleware = JWTAuthMiddleware(app, jwt_secret=JWT_SECRET)
    sent = []
    scope = {
        "type": "http",
        "method": method,
        "path": path,
        "query_string": query_string,
        "headers": [
            (b"authorization", f"Bearer {_token(factory_id)}".encode("latin-1")),
        ],
    }

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message):
        sent.append(message)

    asyncio.run(middleware(scope, receive, send))
    status = next(m["status"] for m in sent if m["type"] == "http.response.start")
    body_msg = next((m for m in sent if m["type"] == "http.response.body"), None)
    raw_body = body_msg.get("body", b"") if body_msg else b""
    try:
        body = json.loads(raw_body.decode("utf-8")) if raw_body else {}
    except json.JSONDecodeError:
        body = {}
    return status, body, app


def test_demo_token_allows_synthesis_without_client_factory_query():
    status, _body, app = _run(
        "/api/smartbi/synthesis/comprehensive",
        method="GET",
        factory_id="DEMO_REST",
    )

    assert status == 200
    assert app.reached is True
    assert app.state["factory_id"] == "DEMO_REST"


def test_demo_token_rejects_foreign_query_factory_id():
    status, body, app = _run(
        "/api/smartbi/synthesis/comprehensive",
        method="GET",
        factory_id="DEMO_REST",
        query_string=b"factory_id=F001",
    )

    assert app.reached is False
    assert status == 403
    assert body["code"] == "DEMO_TENANT_SCOPE"


def test_demo_token_rejects_non_allowlisted_post():
    status, body, app = _run(
        "/api/smartbi/restaurant/etl/trigger",
        method="POST",
        factory_id="DEMO_REST",
    )

    assert app.reached is False
    assert status == 403
    assert body["code"] == "DEMO_READ_ONLY"


def test_demo_token_allows_synthesis_post():
    status, _body, app = _run(
        "/api/smartbi/synthesis/comprehensive",
        method="POST",
        factory_id="DEMO_REST",
    )

    assert status == 200
    assert app.reached is True


def test_non_demo_token_does_not_enter_demo_guard():
    status, _body, app = _run(
        "/api/smartbi/restaurant/etl/trigger",
        method="POST",
        factory_id="F001",
        query_string=b"factory_id=F002",
    )

    assert status == 200
    assert app.reached is True
