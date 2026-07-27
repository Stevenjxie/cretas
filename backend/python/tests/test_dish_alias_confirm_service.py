"""Tests for smartbi.canonical.dish_alias_confirm_service — pending 候选人审确认.

confirm/reject 是 restaurant_dish_alias.status 三态的唯一人工闸门 (per MEMORY #364)。
非 pending 前置检查失败返回 1 (幂等, 不抛); 并发 race (RETURNING 空) fail-loud 抛异常。
"""
from __future__ import annotations

import pytest

from smartbi.canonical.dish_alias_confirm_service import (
    confirm_alias_candidate,
    reject_alias_candidate,
)

FACTORY = "RES_3101_009"


class _FakeConn:
    """Routes fetchrow by SQL shape; records calls for assertion."""

    def __init__(self, item, confirm_row=None, reject_row=None):
        self._item = item
        self._confirm_row = confirm_row
        self._reject_row = reject_row
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append((sql, args))
        if "SELECT id, factory_id" in sql:
            return self._item
        if "SET status = 'confirmed'" in sql:
            return self._confirm_row
        if "SET status = 'rejected'" in sql:
            return self._reject_row
        return None


def _pending_item(cid=10, canonical_name="招牌青花椒鱼"):
    return {
        "id": 1,
        "factory_id": FACTORY,
        "original_name": "招牌青花椒鱼(两吃)",
        "canonical_name": canonical_name,
        "canonical_dish_id": cid,
        "status": "pending",
    }


# ── confirm ──────────────────────────────────────────────────────────────

async def test_confirm_pending_candidate_succeeds():
    conn = _FakeConn(item=_pending_item(), confirm_row={"id": 1})
    result = await confirm_alias_candidate(conn, FACTORY, 1, reviewer="谢总")
    assert result == 0


async def test_confirm_passes_override_canonical_name_through():
    conn = _FakeConn(item=_pending_item(), confirm_row={"id": 1})
    await confirm_alias_candidate(
        conn, FACTORY, 1, reviewer="谢总", canonical_name="招牌青花椒鱼(人工改名)"
    )
    confirm_call = [c for c in conn.calls if "SET status = 'confirmed'" in c[0]][0]
    assert "招牌青花椒鱼(人工改名)" in confirm_call[1]


async def test_confirm_non_pending_returns_1_no_write_attempted():
    item = _pending_item()
    item["status"] = "confirmed"
    conn = _FakeConn(item=item)
    result = await confirm_alias_candidate(conn, FACTORY, 1, reviewer="谢总")
    assert result == 1
    assert not any("SET status = 'confirmed'" in c[0] for c in conn.calls)


async def test_confirm_missing_row_returns_1():
    conn = _FakeConn(item=None)
    result = await confirm_alias_candidate(conn, FACTORY, 999, reviewer="谢总")
    assert result == 1


async def test_confirm_race_condition_raises_fail_loud():
    """标记 confirmed 时行已被并发处理抢先 → RETURNING 空 → RuntimeError (per #390)."""
    conn = _FakeConn(item=_pending_item(), confirm_row=None)
    with pytest.raises(RuntimeError):
        await confirm_alias_candidate(conn, FACTORY, 1, reviewer="谢总")


# ── reject ───────────────────────────────────────────────────────────────

async def test_reject_pending_candidate_succeeds():
    conn = _FakeConn(item=_pending_item(), reject_row={"id": 1})
    result = await reject_alias_candidate(conn, FACTORY, 1, reviewer="谢总")
    assert result == 0


async def test_reject_non_pending_returns_1():
    item = _pending_item()
    item["status"] = "rejected"
    conn = _FakeConn(item=item)
    result = await reject_alias_candidate(conn, FACTORY, 1, reviewer="谢总")
    assert result == 1


async def test_reject_missing_row_returns_1():
    conn = _FakeConn(item=None)
    result = await reject_alias_candidate(conn, FACTORY, 999, reviewer="谢总")
    assert result == 1


async def test_reject_race_condition_raises_fail_loud():
    conn = _FakeConn(item=_pending_item(), reject_row=None)
    with pytest.raises(RuntimeError):
        await reject_alias_candidate(conn, FACTORY, 1, reviewer="谢总")
