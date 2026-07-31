"""Phase 2B-3 chat-2B-write-ops pilot tests — ``query_templates_write.py`` 3 endpoints.

Per `docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md`
§2.4 Tier 3 row 2 (query_templates_write ❌×3) + §3 priority 13 + §8.3 row 2.

Endpoints covered:

* **POST  /api/mobile/{factory_id}/smart-bi/query-templates** — create / MERGE.
* **PUT   /api/mobile/{factory_id}/smart-bi/query-templates/{id}** — update.
* **DELETE /api/mobile/{factory_id}/smart-bi/query-templates/{id}** — HARD delete.

The endpoints are thin wrappers over three module-level helpers
(``_create_template`` / ``_update_template`` / ``_delete_template``); we
monkeypatch the helpers so the tests stay independent of Postgres and
focus on the HTTP contract: auth, envelope shape, error branches, and
the Java-mirror quirks (e.g. merge response carries ``createdAt=None``).

Rule-compliance per ``.claude/rules/python-java-port.md``:

- [x] **Rule 1**: ``is None`` semantics — helpers return None to signal
  "not found / cross-factory / DB disabled" vs returning a dict.
- [x] **Rule 4**: dict-eq envelope shape — both ``_envelope_success`` and
  ``_envelope_error`` emit 8 stable fields in fixed key order; verified.
- [x] **Rule 6**: helper preconditions handled at endpoint layer — None
  return → error envelope (no silent zero / empty response).
- [x] **Rule 9**: Lombok ``ApiResponse`` (no ``@JsonInclude``) emits null
  fields explicitly; envelope test asserts all 8 keys present including
  the three trailing ``None``-valued fields.

Gold standard: ``test_config_thresholds_pilot.py`` (Phase 2C) and
sister ``test_datasource_pilot.py`` (Phase 2B-3 row 1).
"""
from __future__ import annotations

import os

import jwt
import pytest

# JWT secret must be set before importing the module under test.
os.environ.setdefault("JWT_SECRET", "phase-2b3-write-ops-pilot-test-secret")

from smartbi_compat.api import query_templates_write as mod  # noqa: E402
from smartbi_compat.api.query_templates_write import router  # noqa: E402
from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
# POST/PUT/DELETE — 3 endpoints in one file (mirrors KNOWN_ENDPOINTS canonical names).
pytestmark = [
    pytest.mark.api_endpoint("query_templates_create"),
    pytest.mark.api_endpoint("query_templates_update"),
    pytest.mark.api_endpoint("query_templates_delete"),
]


# ============================================================
# JWT + TestClient fixtures
# ============================================================


JWT_SECRET = "phase-2b3-write-ops-pilot-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force JWT_SECRET to our value for the duration of each test —
    survives import-order collisions with sister pilot files that set their
    own secret at module load.

    没有这个 fixture 时, 模块顶部的 ``os.environ.setdefault`` 只在**本文件先跑**
    的情况下生效: 只要 test_analysis_department_pilot / test_analysis_drilldown_pilot
    之一先被导入, JWT_SECRET 已经是它们的值, setdefault 什么也不做, 而下面的
    ``_make_token`` 仍用本文件的字面量签名 → 应用侧用另一个 secret 验签 → 全部 401。
    两个兄弟文件本来就有这个 fixture, 只有本文件漏了。

    表现: 单独跑 22 passed, 跟着别的文件跑 17 failed —— 结果取决于选了哪些文件。
    """
    monkeypatch.setenv("JWT_SECRET", JWT_SECRET)
    yield


def _make_token(
    *,
    user_id: int = 22,
    username: str = "alice",
    factory_id: str | None = "F001",
    role: str = "factory_super_admin",
    exp_offset: int = 3600,
) -> str:
    from time import time

    payload: dict = {
        "userId": user_id,
        "username": username,
        "role": role,
        "exp": int(time()) + exp_offset,
    }
    if factory_id is not None:
        payload["factoryId"] = factory_id
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def _auth_header(token: str | None = None, **token_kwargs) -> dict:
    if token is None:
        token = _make_token(**token_kwargs)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def _sample_entity(**overrides) -> dict:
    """Synthetic helper return value matching ``_query_template_row_to_dict``
    output — 11 fields in Lombok @Data declaration order
    (BaseEntity audit fields first, then SmartBiQueryTemplate fields, then
    the @Where-derived ``deleted`` boolean).
    """
    base = {
        "createdAt": "2026-05-11T10:30:00.123",
        "updatedAt": "2026-05-11T10:30:00.123",
        "deletedAt": None,
        "id": 1,
        "factoryId": "F001",
        "name": "monthly-revenue",
        "category": "FINANCE",
        "description": "月度营收报表",
        "queryTemplate": "SELECT * FROM sales WHERE month = :month",
        "parameters": '{"month": "2026-05"}',
        "deleted": False,
    }
    base.update(overrides)
    return base


# ============================================================
# Auth boundary — applies to all 3 endpoints
# ============================================================


def test_post_missing_jwt_returns_401(client):
    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={"name": "x", "category": "y"},
    )
    assert r.status_code == 401


def test_put_missing_jwt_returns_401(client):
    r = client.put(
        "/api/mobile/F001/smart-bi/query-templates/1",
        json={"name": "x"},
    )
    assert r.status_code == 401


def test_delete_missing_jwt_returns_401(client):
    r = client.delete("/api/mobile/F001/smart-bi/query-templates/1")
    assert r.status_code == 401


def test_post_cross_factory_returns_403(client):
    """Token factoryId=F002 ≠ URL factoryId=F001 → 403."""
    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={"name": "x", "category": "y"},
        headers=_auth_header(factory_id="F002"),
    )
    assert r.status_code == 403


def test_put_cross_factory_returns_403(client):
    r = client.put(
        "/api/mobile/F001/smart-bi/query-templates/1",
        json={"name": "x"},
        headers=_auth_header(factory_id="F002"),
    )
    assert r.status_code == 403


def test_delete_cross_factory_returns_403(client):
    r = client.delete(
        "/api/mobile/F001/smart-bi/query-templates/1",
        headers=_auth_header(factory_id="F002"),
    )
    assert r.status_code == 403


# ============================================================
# POST /query-templates — create (3 tests)
# ============================================================


def test_post_create_happy_path_no_id_returns_full_entity(client, monkeypatch):
    """INSERT branch (body.id is None) — helper returns full entity dict,
    endpoint wraps in success envelope with the entity in ``data``."""

    captured: dict = {}

    def _fake_create(factory_id, body):
        captured["factory_id"] = factory_id
        captured["body"] = body
        return _sample_entity(id=42, name=body.get("name"))

    monkeypatch.setattr(mod, "_create_template", _fake_create)

    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={
            "name": "new-report",
            "category": "FINANCE",
            "description": "desc",
            "queryTemplate": "SELECT 1",
            "parameters": "{}",
        },
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["code"] == 200
    assert body["message"] == "操作成功"
    assert body["data"]["id"] == 42
    assert body["data"]["name"] == "new-report"
    # Helper invoked with URL factory_id + raw JSON body
    assert captured["factory_id"] == "F001"
    assert captured["body"]["name"] == "new-report"
    assert captured["body"]["queryTemplate"] == "SELECT 1"


def test_post_create_merge_branch_carries_created_at_none_quirk(
    client, monkeypatch
):
    """MERGE branch (body.id non-null) — Java JPA quirk:
    ``response.createdAt = None`` even when DB stores the original value.
    Helper enforces this; endpoint forwards verbatim. Lock the behavior
    so future refactors can't drop the quirk.
    """

    def _fake_create(factory_id, body):
        # Helper returns merged entity with createdAt=None per quirk
        return _sample_entity(id=body["id"], createdAt=None)

    monkeypatch.setattr(mod, "_create_template", _fake_create)

    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={"id": 99, "name": "merged"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["data"]["id"] == 99
    assert body["data"]["createdAt"] is None
    # ``updatedAt`` survives (only createdAt is wiped)
    assert body["data"]["updatedAt"] is not None


def test_post_create_db_unavailable_returns_500_envelope(client, monkeypatch):
    """Helper returns None when ``is_postgres_enabled() is False`` →
    endpoint emits 500 envelope (HTTP 200 + envelope.success=False, code=500).
    """

    def _fake_create(factory_id, body):
        return None

    monkeypatch.setattr(mod, "_create_template", _fake_create)

    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={"name": "x"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 500
    assert body["message"] == "Database unavailable"
    assert body["data"] is None


@pytest.mark.parametrize("missing_body", [{}, {"name": ""}, {"name": "   "}, {"name": None}])
def test_post_create_missing_name_returns_400_envelope(client, monkeypatch, missing_body):
    """R1 P2-2 fix: missing/blank ``name`` previously surfaced as a 500
    (DB NOT NULL constraint violation). Now caught at the endpoint with an
    explicit 400 envelope before any DB call.
    """
    called: dict = {"called": False}

    def _fake_create(factory_id, body):
        called["called"] = True
        return _sample_entity(id=1, name="ignored")

    monkeypatch.setattr(mod, "_create_template", _fake_create)

    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json=missing_body,
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
    assert body["message"] == "name is required"
    assert body["data"] is None
    # Validation rejects BEFORE the DB helper is invoked
    assert called["called"] is False


# ============================================================
# PUT /query-templates/{id} — update (3 tests)
# ============================================================


def test_put_update_happy_path_returns_updated_entity(client, monkeypatch):
    captured: dict = {}

    def _fake_update(factory_id, template_id, body):
        captured["factory_id"] = factory_id
        captured["template_id"] = template_id
        captured["body"] = body
        return _sample_entity(id=template_id, name=body.get("name"))

    monkeypatch.setattr(mod, "_update_template", _fake_update)

    r = client.put(
        "/api/mobile/F001/smart-bi/query-templates/7",
        json={"name": "renamed", "category": "OPS"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["code"] == 200
    assert body["data"]["id"] == 7
    assert body["data"]["name"] == "renamed"
    # Endpoint passes URL template_id (path) + URL factory_id + raw body
    assert captured["factory_id"] == "F001"
    assert captured["template_id"] == 7
    # Endpoint does NOT pre-strip body.id / body.factoryId — that's the
    # helper's responsibility (mirrors Java line 985-989). Verify body
    # reached helper as-is.
    assert captured["body"]["name"] == "renamed"


def test_put_update_not_found_returns_error_envelope(client, monkeypatch):
    """Helper returns None for missing template OR cross-factory row →
    endpoint emits error envelope (HTTP 200 + envelope.success=False,
    code=400, message='Template not found').
    """

    def _fake_update(factory_id, template_id, body):
        return None

    monkeypatch.setattr(mod, "_update_template", _fake_update)

    r = client.put(
        "/api/mobile/F001/smart-bi/query-templates/9999",
        json={"name": "ghost"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
    assert body["message"] == "Template not found"
    assert body["data"] is None


def test_put_update_body_id_and_factory_id_forwarded_unchanged(
    client, monkeypatch
):
    """Body's ``id`` and ``factoryId`` MUST be forwarded to the helper
    without endpoint-level sanitisation. The helper (mirroring Java line
    985-989) is the layer that ignores them. This test guards against a
    refactor that accidentally moves the strip-logic to the endpoint and
    drifts from the Java byte-shape.
    """

    captured: dict = {}

    def _fake_update(factory_id, template_id, body):
        captured["body"] = body
        return _sample_entity(id=template_id)

    monkeypatch.setattr(mod, "_update_template", _fake_update)

    client.put(
        "/api/mobile/F001/smart-bi/query-templates/7",
        # Body contains BOTH id (mismatched) and factoryId (cross-factory)
        json={"id": 999, "factoryId": "F999", "name": "spoofed"},
        headers=_auth_header(),
    )
    assert captured["body"]["id"] == 999
    assert captured["body"]["factoryId"] == "F999"


# ============================================================
# DELETE /query-templates/{id} — HARD delete (2 tests)
# ============================================================


def test_delete_happy_path_returns_success_envelope_with_none_data(
    client, monkeypatch
):
    captured: dict = {}

    def _fake_delete(factory_id, template_id):
        captured["factory_id"] = factory_id
        captured["template_id"] = template_id
        return True

    monkeypatch.setattr(mod, "_delete_template", _fake_delete)

    r = client.delete(
        "/api/mobile/F001/smart-bi/query-templates/5",
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["code"] == 200
    # Java mirror: success envelope carries data=None for delete
    assert body["data"] is None
    assert captured == {"factory_id": "F001", "template_id": 5}


def test_delete_not_found_returns_error_envelope(client, monkeypatch):
    """Helper returns False for missing-or-cross-factory row → error envelope."""

    def _fake_delete(factory_id, template_id):
        return False

    monkeypatch.setattr(mod, "_delete_template", _fake_delete)

    r = client.delete(
        "/api/mobile/F001/smart-bi/query-templates/9999",
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
    assert body["message"] == "Template not found"
    assert body["data"] is None


# ============================================================
# Envelope shape (Rule 9 — Lombok @Data, no @JsonInclude, emit nulls)
# ============================================================


def test_envelope_success_emits_eight_keys_in_fixed_order(client, monkeypatch):
    """Rule 9 — ApiResponse Lombok @Data has no @JsonInclude(NON_NULL),
    so Jackson emits null fields. Python dict literal preserves insertion
    order (Rule 8); the order matches Java declaration.
    """
    monkeypatch.setattr(mod, "_create_template", lambda *_: _sample_entity())

    r = client.post(
        "/api/mobile/F001/smart-bi/query-templates",
        json={"name": "x"},
        headers=_auth_header(),
    )
    body = r.json()
    assert list(body.keys()) == [
        "code",
        "message",
        "data",
        "timestamp",
        "success",
        "actionHint",
        "severity",
        "hintTarget",
    ]
    # The three trailing null-valued fields MUST be present (Rule 9)
    assert body["actionHint"] is None
    assert body["severity"] is None
    assert body["hintTarget"] is None


def test_envelope_error_emits_same_eight_keys(client, monkeypatch):
    monkeypatch.setattr(mod, "_update_template", lambda *_: None)

    r = client.put(
        "/api/mobile/F001/smart-bi/query-templates/1",
        json={"name": "x"},
        headers=_auth_header(),
    )
    body = r.json()
    assert list(body.keys()) == [
        "code",
        "message",
        "data",
        "timestamp",
        "success",
        "actionHint",
        "severity",
        "hintTarget",
    ]


# ============================================================
# Router contract — paths + methods
# ============================================================


def test_router_declares_three_write_endpoints():
    """All 3 spec endpoints registered at exact paths + methods."""
    paths_methods = {(r.path, frozenset(r.methods)) for r in router.routes}
    assert (
        "/api/mobile/{factory_id}/smart-bi/query-templates",
        frozenset({"POST"}),
    ) in paths_methods
    assert (
        "/api/mobile/{factory_id}/smart-bi/query-templates/{template_id}",
        frozenset({"PUT"}),
    ) in paths_methods
    assert (
        "/api/mobile/{factory_id}/smart-bi/query-templates/{template_id}",
        frozenset({"DELETE"}),
    ) in paths_methods


def test_router_does_not_expose_get_route():
    """Sanity — write router owns POST/PUT/DELETE only; GET lives in
    ``analysis.py`` (T5b, Apr 28). A drift here would silently
    double-register the route.
    """
    for r in router.routes:
        assert "GET" not in r.methods
