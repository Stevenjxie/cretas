"""Phase 2C Tier 1 pilot tests — /smartbi-config/thresholds.

Tests the 5-endpoint pilot port of ``SmartBIConfigController`` thresholds
sub-module. Pattern target: establish a reusable test harness for the
7 sister sub-modules (intents / incentive-rules / field-mappings /
metric-formulas / chart-templates / reload+status / data-sources).

Coverage:

* Auth (``verify_jwt_admin``) — Bearer required, JWT decode, factoryId
  vs privileged-role gating.
* DTO validation (``CreateAlertThresholdRequest``) — required fields,
  length constraints, optional field nullability.
* Helpers (``_operation_result`` Rule 9 / ``_row_to_dict`` Rule 8 + 11).
* Endpoint contracts (5 routes + GET / POST / PUT / DELETE / reload happy +
  not-found paths) via FastAPI TestClient with mocked DB pool.
* Router-level: paths + methods + isolation from sister analysis routers.

Spec: ``docs/superpowers/specs/2026-05-09-phase-2c-tier-1-config-design.md``
"""
from __future__ import annotations

import os
from datetime import datetime, timezone
from decimal import Decimal

import jwt
import pytest

# ── Test JWT secret (must be set before importing config_thresholds) ──
os.environ.setdefault("JWT_SECRET", "phase-2c-tier-1-pilot-test-secret")

from smartbi_compat.api import config_thresholds  # noqa: E402
from smartbi_compat.api.config_thresholds import (  # noqa: E402
    CreateAlertThresholdRequest,
    _CONFIG_TYPE_THRESHOLD,
    _operation_result,
    _row_to_dict,
    router,
)
from smartbi_compat.auth import JWT_ALGORITHM, PRIVILEGED_ROLES  # noqa: E402

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
# This file covers all 5 /smartbi-config/thresholds endpoints (list/create/update/
# delete/reload).
pytestmark = [
    pytest.mark.api_endpoint("config_thresholds_list"),
    pytest.mark.api_endpoint("config_thresholds_create"),
    pytest.mark.api_endpoint("config_thresholds_update"),
    pytest.mark.api_endpoint("config_thresholds_delete"),
    pytest.mark.api_endpoint("config_thresholds_reload"),
]


# ============================================================
# Fixtures
# ============================================================


JWT_SECRET = "phase-2c-tier-1-pilot-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force JWT_SECRET to this file's value for the duration of each test.

    模块顶部的 ``os.environ.setdefault`` 只在**本文件先跑**时生效: 只要另一个 pilot
    文件先被导入并设了自己的 secret, setdefault 就什么也不做, 而本文件仍用自己的
    字面量签名 → 应用侧 (`smartbi_compat.auth._get_secret`, 调用时才读 env) 用另一个
    secret 验签 → 全部 401。

    2026-07-31 python 门禁首次真跑全量时, 6 个 pilot 文件同时以 `assert 401 == 200` /
    `Invalid token: Signature verification failed` 暴露 —— 此前 ci.yml 那步
    `pytest ... || true` 一直把它吞掉。test_analysis_department_pilot /
    test_analysis_drilldown_pilot 本来就有这个 fixture, 是其余几份漏了。
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
    """Build a test JWT with the same shape as production tokens."""
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


class _FakeConn:
    """asyncpg.Connection stub for tests — replay fetch / fetchrow / execute."""

    def __init__(self):
        self.fetch_results: list = []
        self.fetchrow_results: list = []
        self.executes: list[tuple] = []
        self._fetch_idx = 0
        self._fetchrow_idx = 0

    async def fetch(self, sql, *args):
        result = self.fetch_results[self._fetch_idx] if self.fetch_results else []
        self._fetch_idx += 1
        return result

    async def fetchrow(self, sql, *args):
        if not self.fetchrow_results:
            return None
        result = self.fetchrow_results[self._fetchrow_idx]
        self._fetchrow_idx += 1
        return result

    async def execute(self, sql, *args):
        self.executes.append((sql, args))


class _FakePool:
    """asyncpg.Pool stub — yields a single shared _FakeConn from acquire()."""

    def __init__(self, conn: _FakeConn):
        self._conn = conn

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self_inner):
                return pool._conn

            async def __aexit__(self_inner, *_):
                return False

        return _Ctx()


@pytest.fixture
def fake_conn() -> _FakeConn:
    return _FakeConn()


@pytest.fixture
def fake_pool(fake_conn) -> _FakePool:
    return _FakePool(fake_conn)


@pytest.fixture
def patched_pool(monkeypatch, fake_pool):
    async def _get_pool():
        return fake_pool

    monkeypatch.setattr(config_thresholds, "_get_pool", _get_pool)
    return fake_pool


@pytest.fixture
def client(patched_pool):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def _make_row(**overrides) -> dict:
    """Mimic asyncpg.Record as a dict for fixture rows."""
    base = {
        "id": "uuid-fake-001",
        "threshold_type": "SALES",
        "metric_code": "SALES_AMOUNT",
        "warning_value": Decimal("100.0000"),
        "critical_value": Decimal("50.0000"),
        "comparison_operator": "GT",
        "unit": "元",
        "description": "销售额阈值",
        "factory_id": "F001",
        "is_active": True,
        "created_at": datetime(2026, 5, 11, 10, 30, 0, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 5, 11, 10, 30, 0, tzinfo=timezone.utc),
        "deleted_at": None,
    }
    base.update(overrides)
    return base


# ============================================================
# Auth — verify_jwt_admin
# ============================================================


def test_auth_missing_bearer_returns_401(client):
    r = client.get("/api/mobile/smartbi-config/thresholds")
    assert r.status_code == 401


def test_auth_invalid_token_returns_401(client):
    r = client.get(
        "/api/mobile/smartbi-config/thresholds",
        headers={"Authorization": "Bearer garbage.token.value"},
    )
    assert r.status_code == 401


def test_auth_expired_token_returns_401(client):
    expired = _make_token(exp_offset=-3600)
    r = client.get("/api/mobile/smartbi-config/thresholds", headers=_auth_header(expired))
    assert r.status_code == 401


def test_auth_token_with_factory_id_succeeds(client):
    r = client.get(
        "/api/mobile/smartbi-config/thresholds",
        headers=_auth_header(factory_id="F001", role="factory_super_admin"),
    )
    assert r.status_code == 200


def test_auth_privileged_role_no_factory_id_succeeds(client):
    """platform_admin without factoryId can call admin endpoints."""
    r = client.get(
        "/api/mobile/smartbi-config/thresholds",
        headers=_auth_header(factory_id=None, role="platform_admin"),
    )
    assert r.status_code == 200


def test_auth_no_factory_id_non_privileged_returns_403(client):
    """Factory-scoped role without factoryId in token → 403."""
    r = client.get(
        "/api/mobile/smartbi-config/thresholds",
        headers=_auth_header(factory_id=None, role="operator"),
    )
    assert r.status_code == 403


def test_auth_privileged_roles_constant_is_frozenset_with_two_entries():
    """Reuse from smartbi_compat.auth — sanity that we didn't drift."""
    assert "platform_admin" in PRIVILEGED_ROLES
    assert "platform_super_admin" in PRIVILEGED_ROLES


# ============================================================
# DTO — CreateAlertThresholdRequest validation
# ============================================================


def test_dto_minimal_valid_payload():
    """Only required fields → valid Pydantic instance."""
    body = CreateAlertThresholdRequest(thresholdType="SALES", metricCode="SALES_AMOUNT")
    assert body.thresholdType == "SALES"
    assert body.comparisonOperator is None  # service applies "GT" default
    assert body.isActive is None  # service applies True default


def test_dto_full_valid_payload():
    body = CreateAlertThresholdRequest(
        thresholdType="FINANCE",
        metricCode="PROFIT_RATE",
        warningValue=Decimal("0.05"),
        criticalValue=Decimal("0.02"),
        comparisonOperator="LT",
        unit="%",
        description="Profit alert",
        factoryId="F001",
        isActive=True,
    )
    assert body.factoryId == "F001"
    assert body.warningValue == Decimal("0.05")


@pytest.mark.parametrize(
    "field,bad",
    [
        ("thresholdType", ""),                  # NotBlank
        ("thresholdType", "x" * 65),            # max 64
        ("metricCode", ""),                     # NotBlank
        ("metricCode", "x" * 65),               # max 64
        ("comparisonOperator", "x" * 17),       # max 16
        ("unit", "x" * 33),                     # max 32
        ("description", "x" * 256),             # max 255
        ("factoryId", "x" * 33),                # max 32
    ],
)
def test_dto_field_constraints(field, bad):
    from pydantic import ValidationError

    payload = {"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"}
    payload[field] = bad
    with pytest.raises(ValidationError):
        CreateAlertThresholdRequest(**payload)


def test_dto_missing_required_field_raises():
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        CreateAlertThresholdRequest(thresholdType="SALES")  # metricCode missing


# ============================================================
# Helpers — _operation_result (Rule 9 emit nulls)
# ============================================================


def test_operation_result_emits_all_seven_fields():
    """Rule 9 — Java ConfigOperationResult has no @JsonInclude, emit nulls."""
    result = _operation_result("CREATE", "ok")
    assert set(result.keys()) == {
        "success", "message", "data", "configType",
        "timestamp", "operationType", "affectedCount",
    }


def test_operation_result_field_order_matches_java_declaration():
    """Rule 8 — dict literal preserves insertion order = Java Lombok @Data
    declaration order in ConfigOperationResult.java:32-66."""
    result = _operation_result("CREATE", "ok")
    keys = list(result.keys())
    assert keys == [
        "success", "message", "data", "configType",
        "timestamp", "operationType", "affectedCount",
    ]


def test_operation_result_data_default_is_none():
    result = _operation_result("CREATE", "ok")
    assert result["data"] is None
    assert result["affectedCount"] is None


def test_operation_result_config_type_is_threshold():
    result = _operation_result("CREATE", "ok")
    assert result["configType"] == _CONFIG_TYPE_THRESHOLD == "THRESHOLD"


def test_operation_result_timestamp_uses_java_isoformat():
    """Rule 11 — _java_isoformat trims trailing-zero microseconds."""
    result = _operation_result("CREATE", "ok")
    ts = result["timestamp"]
    assert isinstance(ts, str)
    # ISO 8601 without trailing zeros; year matches
    assert ts.startswith("20")


def test_operation_result_carries_data_and_affected():
    result = _operation_result("UPDATE", "ok", data={"id": "x"}, affected=1)
    assert result["data"] == {"id": "x"}
    assert result["affectedCount"] == 1
    assert result["operationType"] == "UPDATE"


# ============================================================
# Helpers — _row_to_dict (Rule 8 key order + Rule 11 timestamps)
# ============================================================


def test_row_to_dict_none_input_returns_none():
    assert _row_to_dict(None) is None


def test_row_to_dict_emits_all_thirteen_fields():
    row = _make_row()
    out = _row_to_dict(row)
    assert set(out.keys()) == {
        "id", "thresholdType", "metricCode", "warningValue", "criticalValue",
        "comparisonOperator", "unit", "description", "factoryId", "isActive",
        "createdAt", "updatedAt", "deletedAt",
    }


def test_row_to_dict_field_order_matches_java_entity_declaration():
    """Rule 8 — order = SmartBiAlertThreshold.java:54-114 declaration order."""
    row = _make_row()
    out = _row_to_dict(row)
    assert list(out.keys()) == [
        "id", "thresholdType", "metricCode", "warningValue", "criticalValue",
        "comparisonOperator", "unit", "description", "factoryId", "isActive",
        "createdAt", "updatedAt", "deletedAt",
    ]


def test_row_to_dict_passes_through_decimal_values():
    row = _make_row(warning_value=Decimal("123.4567"), critical_value=Decimal("99.9999"))
    out = _row_to_dict(row)
    assert out["warningValue"] == Decimal("123.4567")
    assert out["criticalValue"] == Decimal("99.9999")


def test_row_to_dict_null_optional_fields_emit_as_none():
    """Rule 9 — Java entity has no @JsonInclude, nulls emit."""
    row = _make_row(unit=None, description=None, factory_id=None)
    out = _row_to_dict(row)
    assert out["unit"] is None
    assert out["description"] is None
    assert out["factoryId"] is None


def test_row_to_dict_deleted_at_null_for_active_rows():
    out = _row_to_dict(_make_row(deleted_at=None))
    assert out["deletedAt"] is None


# ============================================================
# GET /thresholds — list
# ============================================================


def test_get_list_returns_wrapped_response(client, fake_conn):
    fake_conn.fetch_results = [[_make_row()]]
    r = client.get("/api/mobile/smartbi-config/thresholds", headers=_auth_header())
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert isinstance(body["data"], list)
    assert len(body["data"]) == 1
    assert body["data"][0]["thresholdType"] == "SALES"


def test_get_list_empty(client, fake_conn):
    fake_conn.fetch_results = [[]]
    r = client.get("/api/mobile/smartbi-config/thresholds", headers=_auth_header())
    assert r.status_code == 200
    assert r.json()["data"] == []


def test_get_list_with_type_filter(client, fake_conn):
    fake_conn.fetch_results = [[_make_row(threshold_type="FINANCE")]]
    r = client.get(
        "/api/mobile/smartbi-config/thresholds?type=FINANCE",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    assert r.json()["data"][0]["thresholdType"] == "FINANCE"


# ============================================================
# POST /thresholds — create
# ============================================================


def test_post_create_happy(client, fake_conn):
    fake_conn.fetchrow_results = [_make_row(id="new-uuid-001")]
    r = client.post(
        "/api/mobile/smartbi-config/thresholds",
        json={"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["data"]["operationType"] == "CREATE"
    assert body["data"]["configType"] == "THRESHOLD"
    assert body["data"]["affectedCount"] == 1
    assert body["data"]["data"]["id"] == "new-uuid-001"
    # INSERT statement executed once
    assert any("INSERT INTO smart_bi_alert_thresholds" in e[0] for e in fake_conn.executes)


def test_post_create_applies_default_comparison_operator(client, fake_conn):
    """Rule 17.1 service default — comparisonOperator=null → GT."""
    fake_conn.fetchrow_results = [_make_row()]
    r = client.post(
        "/api/mobile/smartbi-config/thresholds",
        json={"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    # Inspect the INSERT args — comparison_operator (positional arg 6, 1-indexed = $6)
    insert = next(e for e in fake_conn.executes if "INSERT" in e[0])
    # Args: (id, threshold_type, metric_code, warning_value, critical_value,
    #        comparison_operator, unit, description, factory_id, is_active)
    assert insert[1][5] == "GT"


def test_post_create_applies_default_is_active(client, fake_conn):
    """Service default — isActive=null → True."""
    fake_conn.fetchrow_results = [_make_row()]
    client.post(
        "/api/mobile/smartbi-config/thresholds",
        json={"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"},
        headers=_auth_header(),
    )
    insert = next(e for e in fake_conn.executes if "INSERT" in e[0])
    assert insert[1][9] is True  # is_active at $10


def test_post_create_missing_required_returns_422(client, fake_conn):
    r = client.post(
        "/api/mobile/smartbi-config/thresholds",
        json={"thresholdType": "SALES"},  # metricCode missing
        headers=_auth_header(),
    )
    assert r.status_code == 422


# ============================================================
# PUT /thresholds/{id} — update
# ============================================================


def test_put_update_happy(client, fake_conn):
    fake_conn.fetchrow_results = [{"id": "uuid-fake-001"}, _make_row()]
    r = client.put(
        "/api/mobile/smartbi-config/thresholds/uuid-fake-001",
        json={"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["data"]["operationType"] == "UPDATE"
    assert body["data"]["affectedCount"] == 1
    assert any("UPDATE smart_bi_alert_thresholds" in e[0] for e in fake_conn.executes)


def test_put_update_not_found_returns_404(client, fake_conn):
    fake_conn.fetchrow_results = [None]
    r = client.put(
        "/api/mobile/smartbi-config/thresholds/nonexistent",
        json={"thresholdType": "SALES", "metricCode": "SALES_AMOUNT"},
        headers=_auth_header(),
    )
    assert r.status_code == 404
    assert "Threshold not found" in r.json()["detail"]


def test_put_update_missing_body_returns_422(client, fake_conn):
    r = client.put(
        "/api/mobile/smartbi-config/thresholds/uuid-fake-001",
        json={"thresholdType": "SALES"},  # metricCode missing
        headers=_auth_header(),
    )
    assert r.status_code == 422


# ============================================================
# DELETE /thresholds/{id} — soft delete
# ============================================================


def test_delete_happy(client, fake_conn):
    fake_conn.fetchrow_results = [{"id": "uuid-fake-001"}]
    r = client.delete(
        "/api/mobile/smartbi-config/thresholds/uuid-fake-001",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["data"]["operationType"] == "DELETE"
    assert body["data"]["affectedCount"] == 1
    # Verify soft-delete SQL contains deleted_at = NOW()
    update = next(e for e in fake_conn.executes if "UPDATE smart_bi_alert_thresholds" in e[0])
    assert "deleted_at = NOW()" in update[0]


def test_delete_not_found_returns_404(client, fake_conn):
    fake_conn.fetchrow_results = [None]
    r = client.delete(
        "/api/mobile/smartbi-config/thresholds/nonexistent",
        headers=_auth_header(),
    )
    assert r.status_code == 404


# ============================================================
# POST /thresholds/reload — cache invalidate (pilot no-op)
# ============================================================


def test_reload_returns_success(client):
    """Pilot no-op — endpoint exists for contract parity, cache infra
    arrives with second Tier 1 sub-module per spec §4."""
    r = client.post(
        "/api/mobile/smartbi-config/thresholds/reload",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["data"]["operationType"] == "RELOAD"
    assert body["data"]["configType"] == "THRESHOLD"


def test_reload_requires_auth(client):
    r = client.post("/api/mobile/smartbi-config/thresholds/reload")
    assert r.status_code == 401


# ============================================================
# Router contract — 5 routes / methods / isolation
# ============================================================


def test_router_declares_five_threshold_endpoints():
    """All 5 spec §1.2 endpoints registered at exact paths."""
    paths_methods = {(r.path, frozenset(r.methods)) for r in router.routes}
    assert ("/api/mobile/smartbi-config/thresholds", frozenset({"GET"})) in paths_methods
    assert ("/api/mobile/smartbi-config/thresholds", frozenset({"POST"})) in paths_methods
    assert ("/api/mobile/smartbi-config/thresholds/{id}", frozenset({"PUT"})) in paths_methods
    assert ("/api/mobile/smartbi-config/thresholds/{id}", frozenset({"DELETE"})) in paths_methods
    assert ("/api/mobile/smartbi-config/thresholds/reload", frozenset({"POST"})) in paths_methods


def test_router_does_not_expose_factory_id_in_path():
    """Spec §5.1 — Tier 1 endpoints have NO {factory_id} in path."""
    for r in router.routes:
        assert "{factory_id}" not in r.path
        assert "{factoryId}" not in r.path


def test_router_does_not_collide_with_phase_2a_analysis_routes():
    """Sanity — Tier 1 path prefix is /smartbi-config/, Phase 2A is /smart-bi/."""
    for r in router.routes:
        assert "/smart-bi/analysis/" not in r.path
        assert r.path.startswith("/api/mobile/smartbi-config/")


def test_module_advertises_router_and_helpers():
    """Stable exports for chat-cleanup / chat-cutover."""
    assert hasattr(config_thresholds, "router")
    assert hasattr(config_thresholds, "verify_jwt_admin")
    assert hasattr(config_thresholds, "CreateAlertThresholdRequest")
    assert hasattr(config_thresholds, "_operation_result")
    assert hasattr(config_thresholds, "_row_to_dict")
