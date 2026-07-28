"""Tests for restaurant_name_resolution_admin endpoints (#61 Phase 1).

Verifies:
  - confirm writes BOTH cretas dim_product_alias AND smartbi entity_resolution_history
  - confirm validates product_type tenant ownership (400 if not owned)
  - confirm is idempotent (ON CONFLICT upserts)
  - confirm schedules a fail-soft finance ETL re-run (does not raise on failure)
  - require_admin guards (403 for non-admin role)

Endpoint functions are called directly with a mocked Request + monkeypatched pools.
"""
from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import HTTPException

from smartbi.api import restaurant_name_resolution_admin as A

FACTORY = "R_QINGHUAJIAO_REAL"


class FakeConn:
    def __init__(self, fetchval_map=None):
        self.executed: list = []
        self._fetchval_map = fetchval_map or {}

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "UPDATE 1"

    async def fetch(self, sql, *args):
        return []

    async def fetchval(self, sql, *args):
        for substr, val in self._fetchval_map.items():
            if substr in sql:
                return val
        return None


def make_pool(conn):
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


def admin_request(role="factory_super_admin", username="admin1"):
    return SimpleNamespace(
        state=SimpleNamespace(role=role, auth_method="jwt",
                              factory_id=FACTORY, username=username)
    )


@pytest.fixture(autouse=True)
def _stub_resolver_and_pools(monkeypatch):
    """Stub schema ensure, set_tenant, ETL re-run; leave _upsert_alias/_pos_dish_surrogate real."""
    monkeypatch.setattr(A.resolver, "ensure_alias_schema", AsyncMock())
    monkeypatch.setattr(A.resolver, "_set_tenant", AsyncMock())
    # Don't actually spawn the ETL task — capture it.
    scheduled = {}

    async def fake_rerun(factory_id, reason):
        scheduled["called"] = (factory_id, reason)

    monkeypatch.setattr(A, "_rerun_finance_etl", fake_rerun)
    return scheduled


@pytest.mark.asyncio
async def test_confirm_writes_alias_and_history(monkeypatch, _stub_resolver_and_pools):
    cretas = FakeConn(fetchval_map={"SELECT name FROM product_types": "宫保鸡丁"})
    smartbi = FakeConn()
    monkeypatch.setattr(A, "_pools", AsyncMock(return_value=(make_pool(cretas), make_pool(smartbi))))

    body = A.ConfirmRequest(posName="宫保鸡", productTypeId="pt-1")
    resp = await A.confirm_binding(admin_request(), body)

    assert resp["success"] is True
    assert resp["data"]["productName"] == "宫保鸡丁"

    # cretas: alias INSERT + admin attribution UPDATE
    cretas_sql = " ".join(s for s, _ in cretas.executed)
    assert "INSERT INTO dim_product_alias" in cretas_sql
    assert "admin_confirmed" in str(cretas.executed)
    assert "UPDATE dim_product_alias" in cretas_sql

    # smartbi: entity_resolution_history INSERT (pos_dish) + queue UPDATE confirmed
    smartbi_sql = " ".join(s for s, _ in smartbi.executed)
    assert "INSERT INTO entity_resolution_history" in smartbi_sql
    assert "'pos_dish'" in smartbi_sql
    assert "status = 'confirmed'" in smartbi_sql

    # ETL re-run scheduled (give the create_task a tick to run our stub)
    await asyncio.sleep(0)
    assert _stub_resolver_and_pools.get("called") is not None
    assert _stub_resolver_and_pools["called"][0] == FACTORY


@pytest.mark.asyncio
async def test_confirm_rejects_foreign_product_type(monkeypatch, _stub_resolver_and_pools):
    cretas = FakeConn(fetchval_map={})  # product_type name lookup returns None
    smartbi = FakeConn()
    monkeypatch.setattr(A, "_pools", AsyncMock(return_value=(make_pool(cretas), make_pool(smartbi))))

    body = A.ConfirmRequest(posName="宫保鸡", productTypeId="pt-foreign")
    with pytest.raises(HTTPException) as exc:
        await A.confirm_binding(admin_request(), body)
    assert exc.value.status_code == 400


@pytest.mark.asyncio
async def test_confirm_idempotent_uses_on_conflict(monkeypatch, _stub_resolver_and_pools):
    cretas = FakeConn(fetchval_map={"SELECT name FROM product_types": "宫保鸡丁"})
    smartbi = FakeConn()
    monkeypatch.setattr(A, "_pools", AsyncMock(return_value=(make_pool(cretas), make_pool(smartbi))))

    body = A.ConfirmRequest(posName="宫保鸡", productTypeId="pt-1")
    await A.confirm_binding(admin_request(), body)
    # alias INSERT must carry ON CONFLICT; history INSERT too
    cretas_sql = " ".join(s for s, _ in cretas.executed)
    smartbi_sql = " ".join(s for s, _ in smartbi.executed)
    assert "ON CONFLICT (factory_id, pos_name) DO UPDATE" in cretas_sql
    assert "ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE" in smartbi_sql


@pytest.mark.asyncio
async def test_confirm_empty_args_400(monkeypatch, _stub_resolver_and_pools):
    monkeypatch.setattr(A, "_pools", AsyncMock())
    with pytest.raises(HTTPException) as exc:
        await A.confirm_binding(admin_request(), A.ConfirmRequest(posName="  ", productTypeId="pt-1"))
    assert exc.value.status_code == 400


@pytest.mark.asyncio
async def test_non_admin_role_403(monkeypatch):
    body = A.ConfirmRequest(posName="x", productTypeId="pt-1")
    with pytest.raises(HTTPException) as exc:
        await A.confirm_binding(admin_request(role="viewer"), body)
    assert exc.value.status_code == 403


@pytest.mark.asyncio
async def test_run_backfill_returns_counts(monkeypatch):
    monkeypatch.setattr(
        A, "_pools", AsyncMock(return_value=(MagicMock(), MagicMock())))
    monkeypatch.setattr(
        A.resolver, "resolve_factory_pos_names",
        AsyncMock(return_value={"totalPosNames": 3, "alreadyResolved": 1,
                                "resolvedAuto": 1, "queued": 1}),
    )
    resp = await A.run_backfill(admin_request())
    assert resp["success"] is True
    assert resp["data"]["queued"] == 1


@pytest.mark.asyncio
async def test_rerun_finance_etl_is_fail_soft(monkeypatch):
    """_rerun_finance_etl must swallow ETL exceptions (never raise)."""
    import smartbi.config as cfg
    monkeypatch.setattr(cfg, "get_pg_pool", AsyncMock(return_value=MagicMock()), raising=False)
    monkeypatch.setattr(cfg, "get_cretas_pool", AsyncMock(return_value=MagicMock()), raising=False)

    import smartbi.gold.restaurant.restaurant_finance_etl as fe
    monkeypatch.setattr(
        fe, "run_full_finance_etl_with_retry",
        AsyncMock(side_effect=RuntimeError("boom")),
        raising=False,
    )
    # Must NOT raise.
    await A._rerun_finance_etl(FACTORY, reason="test")


@pytest.mark.asyncio
async def test_set_queue_status_reject(monkeypatch, _stub_resolver_and_pools):
    smartbi = FakeConn()
    monkeypatch.setattr(A, "_pools", AsyncMock(return_value=(MagicMock(), make_pool(smartbi))))
    resp = await A.reject_binding(admin_request(), A.RejectRequest(posName="宫保鸡"))
    assert resp["success"] is True
    assert resp["data"]["status"] == "rejected"
    sql = " ".join(s for s, _ in smartbi.executed)
    assert "status = $3" in sql and "status = 'pending'" in sql
