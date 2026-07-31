"""Phase 2B-3 chat-2B-datasource pilot tests — ``datasource.py`` 5 endpoints.

Per `docs/qa-audits/2026-05-12-python-migration-test-coverage-audit.md`
§2.4 Tier 3 row 5 (datasource ❌×5) + §3 priority 11-12 (upload + apply) +
§5 template + §8.3 row 1.

Coverage:

* **POST /datasource/upload (8 tests, 5 boundary)** — the highest-blast-radius
  endpoint per audit. NOTE: the Python implementation is a deliberate stub-mirror
  of Java's TODO behavior — it drains the multipart file body but does NOT
  parse it (`SmartBiSchemaServiceImpl.uploadAndDetectSchema` line 57-93 is a
  TODO stub; real Excel parsing deferred to Phase 3 PR #49). So our boundary
  tests verify the STUB contract: existing-vs-new datasource branching +
  body drain robustness across 0-byte / Unicode-filename / multi-MB inputs.
* **POST /datasource/apply (4 tests)** — bookkeeping stub w/ INTENTIONAL
  divergence from Java (Python does NOT write DB per organizer directive,
  Phase 2A scope-completeness MO 2026-05-09). Tests verify error envelope
  shape on missing/invalid datasourceId + 400 envelope when datasource not found.
* **GET /datasource/{id}/preview (3 tests)** — happy path + 400 + cross-factory.
* **GET /datasource/{id}/fields (3 tests)** — happy path + empty + 400.
* **GET /datasource/{id}/history (3 tests)** — page-impl envelope + empty + 400.

Rule-compliance checklist per ``.claude/rules/python-java-port.md``:

- [x] **Rule 1**: explicit `is None` semantics — helpers return None vs []
  vs dict distinguish "not exist" vs "exists but empty"
- [x] **Rule 4**: dict-eq envelope shape verified in happy-path bodies
- [x] **Rule 6**: helpers' precondition (None datasource_id) handled by
  endpoint-level validation (returns 400 envelope, not silent zero)
- [x] **Rule 9**: Lombok field order — `_field_def_to_json` (16 fields),
  `_history_to_json` (16 fields), `_build_page_impl` (PageImpl Spring
  default) — key order asserted in happy-path tests

Gold-standard pilot: ``test_config_thresholds_pilot.py`` (Phase 2C) +
sister chat ``test_analysis_procurement_pilot.py`` (Phase 2B-2, chat-2B).
"""
from __future__ import annotations

import io
import os
from datetime import datetime, timezone

import jwt
import pytest

# ── Test JWT secret (must be set before importing datasource) ──
os.environ.setdefault("JWT_SECRET", "phase-2b3-datasource-pilot-test-secret")

from smartbi_compat.api import datasource as mod  # noqa: E402
from smartbi_compat.api.datasource import router  # noqa: E402
from smartbi_compat.auth import JWT_ALGORITHM  # noqa: E402

# Phase 2B endpoint coverage marker (see conftest.py KNOWN_ENDPOINTS).
# This file covers all 5 /datasource endpoints
# (upload/apply/preview/fields/history).
pytestmark = [
    pytest.mark.api_endpoint("datasource_upload"),
    pytest.mark.api_endpoint("datasource_apply"),
    pytest.mark.api_endpoint("datasource_preview"),
    pytest.mark.api_endpoint("datasource_fields"),
    pytest.mark.api_endpoint("datasource_history"),
]


# ============================================================
# JWT + TestClient fixtures (mirror procurement / config_thresholds pilots)
# ============================================================


JWT_SECRET = "phase-2b3-datasource-pilot-test-secret"


@pytest.fixture(autouse=True)
def _jwt_env(monkeypatch):
    """Force JWT_SECRET to this file's value for the duration of each test.

    见 test_config_thresholds_pilot.py 同名 fixture 的说明: 顶部 setdefault 只在
    本文件先跑时生效, 否则签名/验签用的是两个不同的 secret → 全部 401。
    2026-07-31 python 门禁首次真跑全量时暴露, 此前被 `pytest ... || true` 吞掉。
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


# Synthetic DB rows shaped like asyncpg.Record (dicts work transparently).


def _sample_datasource_row(**overrides) -> dict:
    base = {
        "id": 1,
        "name": "test-datasource",
        "factory_id": "F001",
        "schema_version": 3,
    }
    base.update(overrides)
    return base


def _sample_field_def_row(**overrides) -> dict:
    base = {
        "id": "field-uuid-001",
        "datasource_id": 1,
        "field_name": "revenue",
        "field_alias": "营收",
        "field_type": "NUMERIC",
        "metric_type": "AMOUNT",
        "aggregation": "SUM",
        "is_kpi": True,
        "chart_types": None,
        "description": "总营收",
        "display_order": 1,
        "is_visible": True,
        "format_pattern": None,
        "created_at": datetime(2026, 5, 11, 10, 0, 0, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 5, 11, 10, 0, 0, tzinfo=timezone.utc),
        "deleted_at": None,
    }
    base.update(overrides)
    return base


def _sample_history_row(**overrides) -> dict:
    base = {
        "id": "history-uuid-001",
        "datasource_id": 1,
        "change_type": "FIELD_ADD",
        "version_before": 2,
        "version_after": 3,
        "old_schema": "{}",
        "new_schema": "{}",
        "ddl_executed": False,
        "created_by": "alice",
        "change_description": "added revenue field",
        "is_reversible": True,
        "is_applied": True,
        "error_message": None,
        "created_at": datetime(2026, 5, 11, 10, 0, 0, tzinfo=timezone.utc),
        "updated_at": datetime(2026, 5, 11, 10, 0, 0, tzinfo=timezone.utc),
        "deleted_at": None,
    }
    base.update(overrides)
    return base


# ============================================================
# POST /datasource/upload — file I/O highest-blast-radius (8 tests)
# ============================================================


def test_upload_existing_datasource_returns_no_changes_with_detected_at(
    client, monkeypatch
):
    """Java existing-datasource branch (line 67-72):
    findByFactoryIdAndName returns Some → noChanges envelope where the inner
    SchemaChangeReport.noChanges() sets detectedAt = LocalDateTime.now()."""

    async def _existing(factory_id, name):
        return _sample_datasource_row(name=name, schema_version=5)

    monkeypatch.setattr(mod, "_query_datasource_by_name", _existing)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("schema.xlsx", b"\x50\x4b\x03\x04 fake xlsx prefix",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        data={"datasourceName": "test-datasource"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    assert body["message"] == "Schema detection complete"
    changes = body["data"]["changes"]
    assert changes["datasourceName"] == "test-datasource"
    assert changes["currentVersion"] == 5
    assert changes["newVersion"] == 5
    assert changes["hasChanges"] is False
    # Existing-branch MUST set detectedAt (non-null) per Java SchemaChangeReport.noChanges
    assert changes["detectedAt"] is not None
    assert body["data"]["requiresApproval"] is False


def test_upload_new_datasource_returns_auto_applicable_detected_at_null(
    client, monkeypatch
):
    """Java new-datasource branch (line 79-86):
    findByFactoryIdAndName returns None → SchemaChangePreview.autoApplicable
    where inner SchemaChangeReport is built WITHOUT .detectedAt() call.
    Lombok @Builder default → detectedAt=null."""

    async def _not_found(factory_id, name):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_name", _not_found)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("new.xlsx", b"PK\x03\x04 fake",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        data={"datasourceName": "brand-new-source"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    changes = body["data"]["changes"]
    assert changes["datasourceName"] == "brand-new-source"
    assert changes["currentVersion"] == 0
    assert changes["newVersion"] == 1
    # New-branch MUST have detectedAt=null per Java Lombok default
    assert changes["detectedAt"] is None


def test_upload_missing_file_returns_422(client):
    """FastAPI File(...) required → 422 when multipart `file` absent.

    Boundary #1: missing required multipart field.
    """
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        data={"datasourceName": "missing-file-test"},
        headers=_auth_header(),
    )
    assert r.status_code == 422


def test_upload_missing_datasource_name_returns_422(client):
    """Form(...) required → 422 when `datasourceName` form field absent.

    Boundary #2: missing required form field. Note the response is FastAPI
    validation-level, not application-level error envelope.
    """
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("x.xlsx", b"x", "application/octet-stream")},
        headers=_auth_header(),
    )
    assert r.status_code == 422


def test_upload_zero_byte_file_still_succeeds_stub_mirror(client, monkeypatch):
    """0-byte file body must drain cleanly and return stub envelope.

    Boundary #3: empty file. Java's TODO stub doesn't parse the file, so
    Python (which mirrors the stub) also accepts it. Real parser (Phase 3)
    will reject — but until then this is the contract.
    """

    async def _not_found(factory_id, name):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_name", _not_found)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("empty.xlsx", b"", "application/octet-stream")},
        data={"datasourceName": "zero-byte-test"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    assert r.json()["success"] is True


def test_upload_large_file_drains_correctly(client, monkeypatch):
    """A multi-MB file body must be drained correctly without hanging
    or read errors. FastAPI uses SpooledTemporaryFile under the hood —
    we test up to 2MB (above the default in-memory spool threshold of
    1MB so the spool-to-disk path is exercised at least once).

    Boundary #4: large file body. Catches any future change that
    introduces a small read-limit / OOM / blocking behavior.
    """

    async def _not_found(factory_id, name):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_name", _not_found)

    big_bytes = b"P" * (2 * 1024 * 1024)  # 2MB
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("big.xlsx", io.BytesIO(big_bytes), "application/octet-stream")},
        data={"datasourceName": "large-file-test"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    assert r.json()["success"] is True


def test_upload_unicode_filename_handled(client, monkeypatch):
    """Non-ASCII filename must round-trip without encoding errors. Common
    for customer uploads on Chinese Windows clients.

    Boundary #5: Unicode filename. Catches any future logging /
    string-formatting path that assumes ASCII filenames.
    """

    async def _not_found(factory_id, name):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_name", _not_found)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("销售数据_2026年5月.xlsx", b"data",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")},
        data={"datasourceName": "unicode-filename-test"},
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    assert r.json()["success"] is True


def test_upload_missing_jwt_returns_401(client):
    """Auth boundary — no Authorization header → 401 before reaching
    the stub-mirror body handling."""
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/upload",
        files={"file": ("x.xlsx", b"x", "application/octet-stream")},
        data={"datasourceName": "any"},
    )
    assert r.status_code == 401


# ============================================================
# POST /datasource/apply (4 tests)
# ============================================================


def test_apply_existing_datasource_succeeds_no_db_write(client, monkeypatch):
    """Happy path: existing datasource → success envelope. INTENTIONAL
    DIVERGENCE from Java — Python does NOT write to DB (per organizer
    directive). Test verifies the response shape; no DB-write side effect
    is observable from the test fixture (we didn't fake a pool)."""

    async def _exists(datasource_id):
        return _sample_datasource_row(id=datasource_id)

    monkeypatch.setattr(mod, "_query_datasource_by_id", _exists)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/apply",
        json={"datasourceId": 42},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["message"] == "Schema changes applied"
    # ApiResponse.successMessage sets data=null (Java line 71-79).
    assert body["data"] is None


def test_apply_datasource_not_found_returns_400_envelope(client, monkeypatch):
    """datasource not exist → 200 + success=false + code=400."""

    async def _not_found(datasource_id):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_id", _not_found)

    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/apply",
        json={"datasourceId": 99999},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
    assert "数据源不存在" in body["message"]


def test_apply_missing_datasource_id_returns_400_envelope(client):
    """Java line 116 @NotNull validation → DTO would reject. Python
    mirrors by checking request.get("datasourceId") is None → 400 envelope.
    """
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/apply",
        json={"changeNote": "no datasourceId provided"},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
    assert "数据源ID不能为空" in body["message"]


def test_apply_non_int_datasource_id_returns_400_envelope(client):
    """``"abc"`` as datasourceId → int(...) raises ValueError → 400 envelope.
    """
    r = client.post(
        "/api/mobile/F001/smart-bi/datasource/apply",
        json={"datasourceId": "not-an-int"},
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400


# ============================================================
# GET /datasource/{id}/preview (3 tests)
# ============================================================


def test_preview_existing_datasource_returns_no_changes_envelope(
    client, monkeypatch
):
    """Java path: previewSchemaChanges → SchemaChangePreview.noChanges with
    detectedAt timestamp."""

    async def _exists(datasource_id):
        return _sample_datasource_row(id=datasource_id, name="ds-preview", schema_version=7)

    monkeypatch.setattr(mod, "_query_datasource_by_id", _exists)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/42/preview",
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    changes = body["data"]["changes"]
    assert changes["datasourceName"] == "ds-preview"
    assert changes["currentVersion"] == 7
    assert changes["newVersion"] == 7
    assert changes["detectedAt"] is not None


def test_preview_not_found_returns_400_envelope(client, monkeypatch):
    """Datasource not exist → 200 + success=false + code=400, message echoes Java."""

    async def _not_found(datasource_id):
        return None

    monkeypatch.setattr(mod, "_query_datasource_by_id", _not_found)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/9999/preview",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400


def test_preview_cross_factory_denied_returns_403(client):
    """F001 token hitting F002 path → 403 at verify_jwt_and_factory dep.
    Note: Java does NOT scope preview by factoryId (stub limitation per
    docstring line 567), but Python's verify_jwt_and_factory wrapper still
    enforces the URL-path factory matches the JWT factoryId."""

    r = client.get(
        "/api/mobile/F002/smart-bi/datasource/1/preview",
        headers=_auth_header(factory_id="F001"),
    )
    assert r.status_code == 403


# ============================================================
# GET /datasource/{id}/fields (3 tests)
# ============================================================


def test_fields_happy_path_returns_field_definitions(client, monkeypatch):
    """Happy path: 2 rows → envelope key order mirrors Lombok @Data
    reflection (Rule 9). Asserts specific known keys are present and
    pegged to expected values."""

    async def _two_fields(datasource_id):
        return [
            _sample_field_def_row(id="f1", field_name="revenue", display_order=1),
            _sample_field_def_row(id="f2", field_name="cost", display_order=2,
                                  metric_type="AMOUNT", is_kpi=False),
        ]

    monkeypatch.setattr(mod, "_query_field_definitions", _two_fields)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/1/fields",
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    fields = body["data"]
    assert len(fields) == 2
    assert fields[0]["fieldName"] == "revenue"
    assert fields[0]["isKpi"] is True
    assert fields[1]["fieldName"] == "cost"
    assert fields[1]["isKpi"] is False
    # Rule 9 key-order spot check — `id` first, `datasourceId` second
    keys = list(fields[0].keys())
    assert keys[0] == "id"
    assert keys[1] == "datasourceId"
    assert keys[-1] == "deletedAt"  # last per Lombok reflection


def test_fields_empty_list_returns_empty_data(client, monkeypatch):
    """Datasource exists with zero fields → success=true + data=[]."""

    async def _empty(datasource_id):
        return []

    monkeypatch.setattr(mod, "_query_field_definitions", _empty)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/1/fields",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["data"] == []


def test_fields_datasource_not_exist_returns_400_envelope(client, monkeypatch):
    """Rule 1 None semantics: helper returns None (datasource not exist) vs
    [] (exists, no fields). Endpoint translates None → 400 envelope."""

    async def _none(datasource_id):
        return None

    monkeypatch.setattr(mod, "_query_field_definitions", _none)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/9999/fields",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400


# ============================================================
# GET /datasource/{id}/history (3 tests)
# ============================================================


def test_history_happy_path_returns_page_impl_envelope(client, monkeypatch):
    """Happy path: 2 rows in page 0 size 20 → PageImpl envelope shape
    (Rule 9 — Spring's getter reflection order)."""

    async def _two_history(datasource_id, page, size):
        content = [
            {
                "id": "h1",
                "datasourceId": 1,
                "changeType": "FIELD_ADD",
                "versionBefore": 2,
                "versionAfter": 3,
                "oldSchema": "{}",
                "newSchema": "{}",
                "ddlExecuted": False,
                "createdBy": "alice",
                "changeDescription": "added revenue field",
                "isReversible": True,
                "isApplied": True,
                "errorMessage": None,
                "createdAt": "2026-05-11T10:00:00Z",
                "updatedAt": "2026-05-11T10:00:00Z",
                "deletedAt": None,
            },
            {
                "id": "h2",
                "datasourceId": 1,
                "changeType": "FIELD_REMOVE",
                "versionBefore": 1,
                "versionAfter": 2,
                "oldSchema": "{}",
                "newSchema": "{}",
                "ddlExecuted": False,
                "createdBy": "bob",
                "changeDescription": "removed cost field",
                "isReversible": True,
                "isApplied": True,
                "errorMessage": None,
                "createdAt": "2026-05-10T10:00:00Z",
                "updatedAt": "2026-05-10T10:00:00Z",
                "deletedAt": None,
            },
        ]
        return {
            "content": content,
            "pageable": {
                "sort": {"empty": True, "sorted": False, "unsorted": True},
                "offset": 0,
                "pageNumber": 0,
                "pageSize": 20,
                "paged": True,
                "unpaged": False,
            },
            "totalElements": 2,
            "totalPages": 1,
            "last": True,
            "size": 20,
            "number": 0,
            "sort": {"empty": True, "sorted": False, "unsorted": True},
            "first": True,
            "numberOfElements": 2,
            "empty": False,
        }

    monkeypatch.setattr(mod, "_query_schema_history_page", _two_history)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/1/history",
        headers=_auth_header(),
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["success"] is True
    page = body["data"]
    assert page["totalElements"] == 2
    assert page["totalPages"] == 1
    assert page["first"] is True
    assert page["last"] is True
    assert len(page["content"]) == 2
    assert page["content"][0]["changeType"] == "FIELD_ADD"
    # Rule 9 key order — `content` first, `empty` last per Spring reflection
    keys = list(page.keys())
    assert keys[0] == "content"
    assert keys[-1] == "empty"


def test_history_empty_returns_empty_content(client, monkeypatch):
    """Datasource exists but no history rows → empty content + totalElements=0."""

    async def _empty(datasource_id, page, size):
        return {
            "content": [],
            "pageable": {
                "sort": {"empty": True, "sorted": False, "unsorted": True},
                "offset": 0,
                "pageNumber": page,
                "pageSize": size,
                "paged": True,
                "unpaged": False,
            },
            "totalElements": 0,
            "totalPages": 0,
            "last": True,
            "size": size,
            "number": page,
            "sort": {"empty": True, "sorted": False, "unsorted": True},
            "first": True,
            "numberOfElements": 0,
            "empty": True,
        }

    monkeypatch.setattr(mod, "_query_schema_history_page", _empty)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/1/history?page=0&size=20",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is True
    assert body["data"]["totalElements"] == 0
    assert body["data"]["empty"] is True
    assert body["data"]["content"] == []


def test_history_datasource_not_exist_returns_400_envelope(client, monkeypatch):
    """Rule 1 None semantics: helper returns None → 400 envelope."""

    async def _none(datasource_id, page, size):
        return None

    monkeypatch.setattr(mod, "_query_schema_history_page", _none)

    r = client.get(
        "/api/mobile/F001/smart-bi/datasource/9999/history",
        headers=_auth_header(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["success"] is False
    assert body["code"] == 400
