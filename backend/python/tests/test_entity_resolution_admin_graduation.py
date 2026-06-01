"""TDD tests: graduate admin-confirmed resolutions into entity_resolution_history.

Gap being closed: the automated orchestrator records every machine decision into
entity_resolution_history so the transitive read path resolves the same name at
0 cost next upload, but the admin's manual confirmation previously wrote ONLY the
queue's admin_resolved_to_entity_id. The human gold-standard correction was
discarded and the same name re-ran the whole agent chain + re-queued forever.

These tests assert (no live DB, mocked pool/conn):
  - a successful confirm with a resolved entity issues an upsert into
    entity_resolution_history with a_name=raw_name, b_entity_id=resolved id,
    confidence=1.0, decided_by_agent='admin'
  - reject / null-entity / create_new does NOT write history
  - a non-graduatable entity_type (e.g. 'ingredient') does NOT write history
  - if the history write raises, the confirm still returns success (fail-open)

Style mirrors test_data_quality_queue_admin.py (require_admin path, AsyncMock
patches) + test_learning_promotion.py (pytest-asyncio asyncio_mode=auto).
"""
from __future__ import annotations

import pytest
from fastapi import HTTPException
from unittest.mock import AsyncMock, patch


# ---------------------------------------------------------------------------
# Fake asyncpg pool / connection that records execute()/fetchrow() calls so we
# can assert the entity_resolution_history upsert was issued with right values.
# ---------------------------------------------------------------------------
class _FakeConn:
    def __init__(self, dim_name=None):
        self.calls = []  # list of (sql, args)
        self._dim_name = dim_name

    async def execute(self, sql, *args):
        self.calls.append((sql, args))
        return "EXECUTE"

    async def fetchrow(self, sql, *args):
        self.calls.append((sql, args))
        # dim_* lookup → return a canonical name row (or None to test fallback)
        if "FROM dim_" in sql and "SELECT name" in sql:
            if self._dim_name is None:
                return None
            return {"name": self._dim_name}
        return None

    async def fetchval(self, sql, *args):
        # Reject path uses fetchval(... RETURNING id) — return a truthy id so the
        # UPDATE counts as applied (1 row).
        self.calls.append((sql, args))
        return 1

    def transaction(self):
        conn = self

        class _Txn:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Txn()


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Acq:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Acq()


def _history_inserts(conn):
    """All recorded INSERT-into-history calls (sql, args)."""
    return [
        (sql, args)
        for (sql, args) in conn.calls
        if "INSERT INTO entity_resolution_history" in sql
    ]


class _State:
    role = "platform_admin"
    auth_method = "jwt"
    factory_id = "F001"
    user_id = 7
    username = "admin7"


class _Req:
    state = _State()


# ---------------------------------------------------------------------------
# 1. Successful confirm with a resolved entity → history upsert with right values
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_confirm_graduates_into_history():
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    conn = _FakeConn(dim_name="青花椒大融城店")
    pool = _FakePool(conn)

    item = {"id": 5, "factoryId": "F001", "status": "PENDING", "submitter": "999"}
    # _update_queue_resolved returns the updated row (graduatable type 'store').
    updated_row = {"id": 5, "raw_name": "青花椒(大融城)", "entity_type": "store"}

    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=item)):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   new=AsyncMock(return_value=updated_row)):
            with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                       new=AsyncMock(return_value=pool)):
                result = await resolve_queue(
                    request=_Req(), id=5,
                    body=ResolveBody(action="confirm", resolvedToEntityId=42),
                )

    assert result["resolved"] is True

    inserts = _history_inserts(conn)
    assert len(inserts) == 1, "exactly one history upsert expected"
    _sql, args = inserts[0]
    # args: factory_id, entity_type, a_name, b_name, b_entity_id,
    #       confidence, decided_by_agent, reasoning
    assert args[0] == "F001"
    assert args[1] == "store"
    assert args[2] == "青花椒(大融城)"           # a_name = raw_name
    assert args[3] == "青花椒大融城店"           # b_name = canonical dim name
    assert args[4] == 42                          # b_entity_id = resolved id
    assert args[5] == 1.0                         # confidence = human gold
    assert args[6] == "admin"                     # decided_by_agent
    assert args[7] == "human-confirmed via admin queue"


# ---------------------------------------------------------------------------
# 2a. reject does NOT write history
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_reject_does_not_write_history():
    from smartbi.api.data_quality_queue_admin import reject_queue, RejectBody

    conn = _FakeConn()
    pool = _FakePool(conn)

    item = {"id": 6, "factoryId": "F001", "status": "PENDING", "submitter": "999"}

    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=item)):
        with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                   new=AsyncMock(return_value=pool)):
            result = await reject_queue(
                request=_Req(), id=6, body=RejectBody(reason="不是同一个实体"),
            )

    assert result["rejected"] is True
    assert _history_inserts(conn) == []


# ---------------------------------------------------------------------------
# 2b. confirm with null resolved entity (e.g. create_new w/o id) → no history
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_null_entity_confirm_does_not_write_history():
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    conn = _FakeConn(dim_name="X")
    pool = _FakePool(conn)

    item = {"id": 7, "factoryId": "F001", "status": "PENDING", "submitter": "999"}
    updated_row = {"id": 7, "raw_name": "某新店", "entity_type": "store"}

    rec = AsyncMock()
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=item)):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   new=AsyncMock(return_value=updated_row)):
            with patch("smartbi.api.data_quality_queue_admin._record_admin_confirm_history",
                       new=rec):
                with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                           new=AsyncMock(return_value=pool)):
                    result = await resolve_queue(
                        request=_Req(), id=7,
                        body=ResolveBody(action="create_new", resolvedToEntityId=None),
                    )

    assert result["resolved"] is True
    rec.assert_not_awaited()
    assert _history_inserts(conn) == []


# ---------------------------------------------------------------------------
# 2c. non-graduatable entity_type (no dim_* table) → no history, confirm OK
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_non_graduatable_entity_type_does_not_write_history():
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    conn = _FakeConn(dim_name="X")
    pool = _FakePool(conn)

    item = {"id": 8, "factoryId": "F001", "status": "PENDING", "submitter": "999"}
    # 'ingredient' is a valid queue type but NOT graduatable (no dim_ingredient,
    # history CHECK only allows store/product/staff).
    updated_row = {"id": 8, "raw_name": "猪舌", "entity_type": "ingredient"}

    rec = AsyncMock()
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=item)):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   new=AsyncMock(return_value=updated_row)):
            with patch("smartbi.api.data_quality_queue_admin._record_admin_confirm_history",
                       new=rec):
                with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                           new=AsyncMock(return_value=pool)):
                    result = await resolve_queue(
                        request=_Req(), id=8,
                        body=ResolveBody(action="confirm", resolvedToEntityId=11),
                    )

    assert result["resolved"] is True
    rec.assert_not_awaited()
    assert _history_inserts(conn) == []


# ---------------------------------------------------------------------------
# 3. fail-open: history write raises → confirm STILL returns success
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_history_write_failure_does_not_block_confirm():
    from smartbi.api.data_quality_queue_admin import resolve_queue, ResolveBody

    pool = _FakePool(_FakeConn())

    item = {"id": 9, "factoryId": "F001", "status": "PENDING", "submitter": "999"}
    updated_row = {"id": 9, "raw_name": "某店", "entity_type": "store"}

    boom = AsyncMock(side_effect=RuntimeError("history table on fire"))
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               new=AsyncMock(return_value=item)):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   new=AsyncMock(return_value=updated_row)):
            with patch("smartbi.api.data_quality_queue_admin._record_admin_confirm_history",
                       new=boom):
                with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                           new=AsyncMock(return_value=pool)):
                    result = await resolve_queue(
                        request=_Req(), id=9,
                        body=ResolveBody(action="confirm", resolvedToEntityId=99),
                    )

    # The learning side-effect threw, but the user-facing confirm still succeeded.
    boom.assert_awaited_once()
    assert result["resolved"] is True


# ---------------------------------------------------------------------------
# 4. batch confirm graduates each confirmed item (reuses _update_queue_resolved)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_batch_confirm_graduates_each_item():
    from smartbi.api.data_quality_queue_admin import (
        batch_resolve_queue, BatchResolveBody,
    )

    pool = _FakePool(_FakeConn())

    items = {
        1: {"id": 1, "factoryId": "F001", "status": "PENDING", "submitter": "999"},
        2: {"id": 2, "factoryId": "F001", "status": "PENDING", "submitter": "999"},
    }
    updated_rows = {
        1: {"id": 1, "raw_name": "店A", "entity_type": "store"},
        2: {"id": 2, "raw_name": "店B", "entity_type": "store"},
    }

    async def fake_get_item(_pool, item_id):
        return items.get(item_id)

    async def fake_update(_pool, item_id, *a, **kw):
        return updated_rows.get(item_id)

    rec = AsyncMock()
    with patch("smartbi.api.data_quality_queue_admin._get_queue_item",
               side_effect=fake_get_item):
        with patch("smartbi.api.data_quality_queue_admin._update_queue_resolved",
                   side_effect=fake_update):
            with patch("smartbi.api.data_quality_queue_admin._record_admin_confirm_history",
                       new=rec):
                with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
                           new=AsyncMock(return_value=pool)):
                    result = await batch_resolve_queue(
                        request=_Req(),
                        body=BatchResolveBody(
                            ids=[1, 2], action="confirm", resolvedToEntityId=55,
                        ),
                    )

    assert result["successCount"] == 2
    assert rec.await_count == 2
    # each graduation called with (pool, factory, entity_type, raw_name, resolved)
    called_raw_names = {c.args[3] for c in rec.await_args_list}
    assert called_raw_names == {"店A", "店B"}
    for c in rec.await_args_list:
        assert c.args[1] == "F001"
        assert c.args[2] == "store"
        assert c.args[4] == 55


# ---------------------------------------------------------------------------
# 5. unit: _record_admin_confirm_history sets GUC + b_name fallback to raw_name
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_record_history_sets_guc_and_falls_back_b_name():
    from smartbi.api.data_quality_queue_admin import _record_admin_confirm_history

    # dim lookup returns None → b_name must fall back to raw_name (like orchestrator)
    conn = _FakeConn(dim_name=None)
    pool = _FakePool(conn)

    await _record_admin_confirm_history(
        pool,
        factory_id="F001",
        entity_type="product",
        raw_name="牛肉面(招牌)",
        resolved_to_entity_id=88,
    )

    # GUC set inside the transaction
    assert any(
        "set_config('app.factory_id'" in sql and args == ("F001",)
        for sql, args in conn.calls
    ), "app.factory_id GUC must be set for RLS FORCE"

    inserts = _history_inserts(conn)
    assert len(inserts) == 1
    _sql, args = inserts[0]
    assert args[1] == "product"
    assert args[3] == "牛肉面(招牌)"   # b_name fell back to raw_name (no dim row)
    assert args[4] == 88
    assert args[5] == 1.0
    assert args[6] == "admin"


# ---------------------------------------------------------------------------
# 6. unit: non-graduatable type never interpolates a dim_* table / no insert
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_record_history_skips_non_graduatable_type():
    from smartbi.api.data_quality_queue_admin import _record_admin_confirm_history

    conn = _FakeConn(dim_name="X")
    pool = _FakePool(conn)

    await _record_admin_confirm_history(
        pool,
        factory_id="F001",
        entity_type="shape_detection",  # not graduatable
        raw_name="whatever",
        resolved_to_entity_id=1,
    )

    # no dim_* lookup, no history insert, no GUC needed
    assert _history_inserts(conn) == []
    assert not any("dim_shape_detection" in sql for sql, _ in conn.calls)


# ---------------------------------------------------------------------------
# 7. reject with empty reason still 400 (sanity: reject path untouched by change)
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_reject_empty_reason_400():
    from smartbi.api.data_quality_queue_admin import reject_queue, RejectBody

    with patch("smartbi.api.data_quality_queue_admin.get_pg_pool",
               new=AsyncMock(return_value=_FakePool(_FakeConn()))):
        with pytest.raises(HTTPException) as exc:
            await reject_queue(request=_Req(), id=1, body=RejectBody(reason="  "))
    assert exc.value.status_code == 400
