"""Tests for /api/smartbi/factory-config/provenance (Sub-Project C Day 27).

Verifies the GET + PUT admin endpoints that back the
``/system/data-fabric/provenance-config`` Vue page:

  - GET no row exists → globals only (factoryOverride/override null,
    diffThreshold = 0.30 default).
  - GET with row → globals + saved overrides merged.
  - PUT happy path → 200, row UPSERTed, GET reflects new values,
    invalidate_factory_config_cache was called.
  - PUT 400 on out-of-range diffThreshold / priority / cost-rate.
  - PUT 403 on factoryId ≠ JWT.
  - PUT/GET 403 on non-admin.
  - PUT then GET roundtrip preserves shape.

Real-PG tests use the same ``pool`` + ``clean_rows`` style as
``test_provenance_audit.py``; they skip via ``pytest.skip("No Postgres
configured")`` when ``settings.postgres_url`` is unset.
"""
from __future__ import annotations

from typing import Any, Dict

import pytest
import pytest_asyncio


_TENANT = "TEST_FPC_C"


# P2-7: pool fixture extracted to conftest.py as c_provenance_pool. Local
# alias keeps existing call sites stable.
@pytest_asyncio.fixture
async def pool(c_provenance_pool):
    yield c_provenance_pool


async def _clean(conn, tenant: str) -> None:
    """Wipe the tenant's row from factory_provenance_config."""
    await conn.execute(
        "DELETE FROM factory_provenance_config WHERE factory_id=$1", tenant
    )


@pytest_asyncio.fixture
async def clean_rows(pool):
    """Pre/post wipe of tenant row. Caller seeds inside the test."""
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    token = set_factory_id(_TENANT)
    try:
        async with pool.acquire() as conn:
            await _clean(conn, _TENANT)
        yield
    finally:
        async with pool.acquire() as conn:
            await _clean(conn, _TENANT)
        reset_factory_id(token)


def _make_admin_request(factory_id: str = _TENANT, *,
                        user_id: int = 1, username: str = "admin:test"):
    """Build a fake Request with admin auth state populated."""

    class _S:
        role = "factory_super_admin"
        auth_method = "jwt"

    s = _S()
    s.factory_id = factory_id  # type: ignore[attr-defined]
    s.user_id = user_id  # type: ignore[attr-defined]
    s.username = username  # type: ignore[attr-defined]

    class _Req:
        state = s

    return _Req()


def _make_non_admin_request(factory_id: str = _TENANT):
    class _S:
        role = "operator"
        auth_method = "jwt"

    s = _S()
    s.factory_id = factory_id  # type: ignore[attr-defined]

    class _Req:
        state = s

    return _Req()


# ── Body builder ──────────────────────────────────────────────────────


def _make_body(**overrides: Any) -> Dict[str, Any]:
    base = {
        "factoryId": _TENANT,
        "diffThreshold": 0.30,
        "sourcePriorities": {},
        "industryDefaults": {},
    }
    base.update(overrides)
    return base


# ── 1. GET no row → globals only ──────────────────────────────────────


@pytest.mark.asyncio
async def test_get_returns_globals_when_no_row_exists(pool, clean_rows):
    from smartbi.api.factory_provenance_config import (
        get_factory_provenance_config,
    )
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    token = set_factory_id(_TENANT)
    try:
        out = await get_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            factory_id=_TENANT,
        )
    finally:
        reset_factory_id(token)

    assert out["factoryId"] == _TENANT
    assert out["diffThreshold"] == pytest.approx(0.30, abs=1e-6)
    assert out["updatedAt"] is None
    assert out["updatedBy"] is None
    # 6 canonical source types, all with factoryOverride=None.
    assert len(out["sourcePriorities"]) == 6
    types = {row["sourceType"] for row in out["sourcePriorities"]}
    assert types == {
        "manual", "bill_flow", "product_summary",
        "review", "inferred", "industry_default",
    }
    for row in out["sourcePriorities"]:
        assert row["factoryOverride"] is None
        assert isinstance(row["globalDefault"], int)
    # Industry defaults: N rows, all override=None, every globalDefault > 0.
    assert len(out["industryDefaults"]) >= 5
    for row in out["industryDefaults"]:
        assert row["override"] is None
        assert 0 < row["globalDefault"] <= 1


# ── 2. GET with row → merged ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_get_returns_merged_when_row_exists(pool, clean_rows):
    import json as _json
    from smartbi.api.factory_provenance_config import (
        get_factory_provenance_config,
    )
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    token = set_factory_id(_TENANT)
    try:
        async with pool.acquire() as conn:
            await conn.execute(
                """
                INSERT INTO factory_provenance_config
                  (factory_id, diff_threshold, priority_overrides,
                   industry_default_overrides, updated_by)
                VALUES ($1, $2, $3::jsonb, $4::jsonb, $5)
                """,
                _TENANT,
                0.20,
                _json.dumps({"manual": 2, "bill_flow": 1}),
                _json.dumps({"cost_rate.川菜": 0.32}),
                "test_user",
            )

        out = await get_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            factory_id=_TENANT,
        )
    finally:
        reset_factory_id(token)

    assert out["diffThreshold"] == pytest.approx(0.20, abs=1e-6)
    assert out["updatedBy"] == "test_user"
    assert out["updatedAt"] is not None

    overrides_by_type = {
        row["sourceType"]: row["factoryOverride"]
        for row in out["sourcePriorities"]
    }
    assert overrides_by_type["manual"] == 2
    assert overrides_by_type["bill_flow"] == 1
    # The other 4 stay None.
    assert overrides_by_type["product_summary"] is None
    assert overrides_by_type["review"] is None

    sichuan = next(
        row for row in out["industryDefaults"] if row["category"] == "川菜"
    )
    assert sichuan["override"] == pytest.approx(0.32, abs=1e-6)
    # Untouched categories keep override=None.
    cantonese = next(
        row for row in out["industryDefaults"] if row["category"] == "粤菜"
    )
    assert cantonese["override"] is None


# ── 3. PUT happy path → cache invalidated, GET reflects ────────────────


@pytest.mark.asyncio
async def test_put_upserts_and_invalidates_cache(pool, clean_rows, monkeypatch):
    from smartbi.api import factory_provenance_config as fpc_module
    from smartbi.api.factory_provenance_config import (
        get_factory_provenance_config,
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    invalidate_calls: list[str] = []

    def _spy(factory_id: str = "") -> None:
        invalidate_calls.append(factory_id)

    # Patch the symbol AS IMPORTED by the module under test
    # (provenance_audit.py-style: import-then-patch the local reference).
    monkeypatch.setattr(
        fpc_module, "invalidate_factory_config_cache", _spy
    )

    token = set_factory_id(_TENANT)
    try:
        body = ProvenanceConfigBody(
            factoryId=_TENANT,
            diffThreshold=0.25,
            sourcePriorities={"manual": 1, "review": 3},
            industryDefaults={"川菜": 0.33, "粤菜": 0.41},
        )
        out_put = await put_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            body=body,
        )

        # Cache invalidation MUST have fired with the right tenant.
        assert invalidate_calls == [_TENANT]

        # Saved row reflects request.
        assert out_put["diffThreshold"] == pytest.approx(0.25, abs=1e-6)
        assert out_put["updatedBy"] is not None
        manual_row = next(
            r for r in out_put["sourcePriorities"] if r["sourceType"] == "manual"
        )
        assert manual_row["factoryOverride"] == 1

        # GET roundtrip matches PUT response.
        out_get = await get_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            factory_id=_TENANT,
        )
    finally:
        reset_factory_id(token)

    assert out_get["diffThreshold"] == pytest.approx(0.25, abs=1e-6)
    assert {
        r["sourceType"]: r["factoryOverride"]
        for r in out_get["sourcePriorities"]
        if r["factoryOverride"] is not None
    } == {"manual": 1, "review": 3}
    assert {
        r["category"]: r["override"]
        for r in out_get["industryDefaults"]
        if r["override"] is not None
    } == pytest.approx({"川菜": 0.33, "粤菜": 0.41})


# ── 4-6. PUT validation (no PG needed — body validation runs first) ────


@pytest.mark.asyncio
async def test_put_rejects_diff_threshold_out_of_range():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )

    body = ProvenanceConfigBody(
        factoryId=_TENANT,
        diffThreshold=0.99,  # > 0.50
        sourcePriorities={},
        industryDefaults={},
    )
    with pytest.raises(HTTPException) as exc:
        await put_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            body=body,
        )
    assert exc.value.status_code == 400
    assert "差异阈值" in exc.value.detail


@pytest.mark.asyncio
async def test_put_rejects_priority_out_of_range():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )

    body = ProvenanceConfigBody(
        factoryId=_TENANT,
        diffThreshold=0.30,
        sourcePriorities={"manual": 99},  # > 6
        industryDefaults={},
    )
    with pytest.raises(HTTPException) as exc:
        await put_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            body=body,
        )
    assert exc.value.status_code == 400
    assert "manual" in exc.value.detail


@pytest.mark.asyncio
async def test_put_rejects_cost_rate_out_of_range():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )

    body = ProvenanceConfigBody(
        factoryId=_TENANT,
        diffThreshold=0.30,
        sourcePriorities={},
        industryDefaults={"川菜": 1.5},  # > 1.0
    )
    with pytest.raises(HTTPException) as exc:
        await put_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            body=body,
        )
    assert exc.value.status_code == 400
    assert "川菜" in exc.value.detail


# ── 7. PUT factory_id mismatch → 403 ────────────────────────────────────


@pytest.mark.asyncio
async def test_put_rejects_factory_id_mismatch_with_jwt():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )

    body = ProvenanceConfigBody(
        factoryId="F999",  # different from JWT
        diffThreshold=0.30,
        sourcePriorities={},
        industryDefaults={},
    )
    with pytest.raises(HTTPException) as exc:
        await put_factory_provenance_config(
            request=_make_admin_request(factory_id="F001"),  # type: ignore[arg-type]
            body=body,
        )
    assert exc.value.status_code == 403


# ── 8. RBAC — non-admin → 403 (GET + PUT) ───────────────────────────────


@pytest.mark.asyncio
async def test_get_rejects_non_admin_role():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        get_factory_provenance_config,
    )

    with pytest.raises(HTTPException) as exc:
        await get_factory_provenance_config(
            request=_make_non_admin_request(),  # type: ignore[arg-type]
            factory_id=_TENANT,
        )
    assert exc.value.status_code == 403
    assert "管理员" in exc.value.detail


@pytest.mark.asyncio
async def test_put_rejects_non_admin_role():
    from fastapi import HTTPException
    from smartbi.api.factory_provenance_config import (
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )

    body = ProvenanceConfigBody(
        factoryId=_TENANT,
        diffThreshold=0.30,
        sourcePriorities={},
        industryDefaults={},
    )
    with pytest.raises(HTTPException) as exc:
        await put_factory_provenance_config(
            request=_make_non_admin_request(),  # type: ignore[arg-type]
            body=body,
        )
    assert exc.value.status_code == 403


# ── 9. PUT → GET roundtrip preserves shape ─────────────────────────────


@pytest.mark.asyncio
async def test_put_then_get_roundtrip_preserves_shape(pool, clean_rows, monkeypatch):
    from smartbi.api import factory_provenance_config as fpc_module
    from smartbi.api.factory_provenance_config import (
        get_factory_provenance_config,
        put_factory_provenance_config,
        ProvenanceConfigBody,
    )
    from smartbi.tenant_ctx import set_factory_id, reset_factory_id

    monkeypatch.setattr(
        fpc_module, "invalidate_factory_config_cache", lambda *_a, **_k: None
    )

    token = set_factory_id(_TENANT)
    try:
        body = ProvenanceConfigBody(
            factoryId=_TENANT,
            diffThreshold=0.40,
            sourcePriorities={"product_summary": 4, "industry_default": 5},
            industryDefaults={"火锅": 0.27, "海鲜": 0.48},
        )
        put_resp = await put_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            body=body,
        )
        get_resp = await get_factory_provenance_config(
            request=_make_admin_request(),  # type: ignore[arg-type]
            factory_id=_TENANT,
        )
    finally:
        reset_factory_id(token)

    # Same top-level keys.
    assert set(put_resp.keys()) == set(get_resp.keys())
    # Same number of source-priority rows + industry-default rows.
    assert len(put_resp["sourcePriorities"]) == len(get_resp["sourcePriorities"]) == 6
    assert len(put_resp["industryDefaults"]) == len(get_resp["industryDefaults"])
    # Same overrides.
    assert {
        r["sourceType"]: r["factoryOverride"]
        for r in put_resp["sourcePriorities"]
    } == {
        r["sourceType"]: r["factoryOverride"]
        for r in get_resp["sourcePriorities"]
    }
    assert {
        r["category"]: r["override"]
        for r in put_resp["industryDefaults"]
    } == {
        r["category"]: r["override"]
        for r in get_resp["industryDefaults"]
    }
