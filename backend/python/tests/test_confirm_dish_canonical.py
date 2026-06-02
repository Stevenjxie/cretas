"""P4a — confirm_dish_canonical CLI (spec §5 Task 7 + §R1 P0 human gate).

confirm writes canonical + dim_product link + best-effort history; history failure
NEVER blocks the confirm (fail-open + WARNING). Reject marks REJECTED.
"""
from __future__ import annotations

import logging
from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.scripts import confirm_dish_canonical as cdc


class _FakeConn:
    """Mock asyncpg connection with a transaction() async-CM and queued returns.

    fetchrow / fetch / fetchval each pop from per-method queues so we control the
    exact sequence the confirm() flow expects:
      confirm(create_new):
        fetchrow #1 -> _GET_ITEM_SQL  (queue item)
        fetchrow #2 -> _CREATE_CANONICAL_SQL  (new canonical id)
        fetch   #1 -> _LINK_PRODUCT_SQL  (linked product ids)
        execute (recount, set_config, history...) -> recorded
        fetchval #1 -> _MARK_CONFIRMED_SQL  (queue id)
    """

    def __init__(self, fetchrow_rets, fetch_rets, fetchval_rets,
                 history_raises=False):
        self._fetchrow = list(fetchrow_rets)
        self._fetch = list(fetch_rets)
        self._fetchval = list(fetchval_rets)
        self._history_raises = history_raises
        self.execute_calls = []
        self.fetchrow_calls = []
        self.fetch_calls = []

    def transaction(self):
        cm = MagicMock()
        cm.__aenter__ = AsyncMock(return_value=None)
        cm.__aexit__ = AsyncMock(return_value=None)
        return cm

    async def execute(self, sql, *args):
        self.execute_calls.append((sql, args))
        if self._history_raises and "entity_resolution_history" in sql:
            raise RuntimeError("simulated history grant gap")
        return None

    async def fetchrow(self, sql, *args):
        self.fetchrow_calls.append((sql, args))
        return self._fetchrow.pop(0) if self._fetchrow else None

    async def fetch(self, sql, *args):
        self.fetch_calls.append((sql, args))
        return self._fetch.pop(0) if self._fetch else []

    async def fetchval(self, sql, *args):
        return self._fetchval.pop(0) if self._fetchval else None


def _queue_item(status="PENDING", extra=None):
    return {
        "id": 100,
        "factory_id": "RES_3101_009",
        "status": status,
        "raw_name": "招牌青花椒鱼",
        "candidate_entity_id": None,
        "extra": extra
        or {
            "proposal_kind": "rule_cluster",
            "normalized_key": "招牌青花椒鱼",
            "member_product_ids": [1, 2, 3],
            "member_names": ["招牌青花椒鱼(单人份)", "#招牌青花椒鱼#", "招牌青花椒鱼(两吃)"],
            "category": "鱼类",
        },
    }


async def test_confirm_create_new_writes_canonical_link_and_history():
    conn = _FakeConn(
        fetchrow_rets=[_queue_item(), {"canonical_dish_id": 42}],
        fetch_rets=[[{"product_id": 1}, {"product_id": 2}, {"product_id": 3}]],
        fetchval_rets=[100],  # mark confirmed
    )

    rc = await cdc.confirm(conn, "RES_3101_009", 100, "招牌青花椒鱼", None)

    assert rc == 0
    # canonical created
    assert any("dim_canonical_dish" in c[0] for c in conn.fetchrow_calls)
    # products linked (only place canonical_dish_id is written)
    assert any(
        "UPDATE dim_product" in c[0] and "canonical_dish_id" in c[0]
        for c in conn.fetch_calls
    )
    # history mirrored: 3 member rows, entity_type='dish'
    hist_calls = [c for c in conn.execute_calls if "entity_resolution_history" in c[0]]
    assert len(hist_calls) == 3
    assert all(c[1][0] == "RES_3101_009" for c in hist_calls)  # factory_id bind


async def test_confirm_attach_to_existing_canonical():
    conn = _FakeConn(
        fetchrow_rets=[
            _queue_item(),
            {"canonical_name": "招牌青花椒鱼"},  # existing canonical lookup
        ],
        fetch_rets=[[{"product_id": 1}, {"product_id": 2}, {"product_id": 3}]],
        fetchval_rets=[100],
    )

    rc = await cdc.confirm(conn, "RES_3101_009", 100, None, 7)

    assert rc == 0
    # no CREATE canonical (we attach to id 7); products still linked to 7.
    link_calls = [c for c in conn.fetch_calls if "UPDATE dim_product" in c[0]]
    assert link_calls and link_calls[0][1][0] == 7  # canonical id bind


async def test_history_upsert_failure_does_not_block_confirm(caplog):
    """history insert raises → confirm STILL succeeds (rc 0) + WARNING logged."""
    conn = _FakeConn(
        fetchrow_rets=[_queue_item(), {"canonical_dish_id": 42}],
        fetch_rets=[[{"product_id": 1}, {"product_id": 2}, {"product_id": 3}]],
        fetchval_rets=[100],
        history_raises=True,
    )

    with caplog.at_level(logging.WARNING):
        rc = await cdc.confirm(conn, "RES_3101_009", 100, "招牌青花椒鱼", None)

    assert rc == 0  # confirm succeeded despite history failure
    assert any(
        "entity_resolution_history" in r.message and "fail-open" in r.message
        for r in caplog.records
    ), "expected a WARNING about fail-open history failure"


async def test_confirm_rejects_non_pending():
    conn = _FakeConn(
        fetchrow_rets=[_queue_item(status="CONFIRMED")],
        fetch_rets=[],
        fetchval_rets=[],
    )
    rc = await cdc.confirm(conn, "RES_3101_009", 100, "x", None)
    assert rc == 1  # already resolved


async def test_confirm_missing_item():
    conn = _FakeConn(fetchrow_rets=[None], fetch_rets=[], fetchval_rets=[])
    rc = await cdc.confirm(conn, "RES_3101_009", 999, "x", None)
    assert rc == 1


async def test_reject_marks_rejected():
    conn = _FakeConn(
        fetchrow_rets=[_queue_item()],
        fetch_rets=[],
        fetchval_rets=[100],  # mark rejected RETURNING id
    )
    rc = await cdc.reject(conn, "RES_3101_009", 100, "青花椒鱼 与 青花椒虾 主料不同")
    assert rc == 0


@pytest.mark.parametrize("extra_override", [{"member_product_ids": []}])
async def test_confirm_no_members_fails(extra_override):
    item = _queue_item()
    item["extra"] = {**item["extra"], **extra_override}
    conn = _FakeConn(fetchrow_rets=[item], fetch_rets=[], fetchval_rets=[])
    rc = await cdc.confirm(conn, "RES_3101_009", 100, "x", None)
    assert rc == 1  # no members → cannot confirm
