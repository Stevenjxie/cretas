# -*- coding: utf-8 -*-
"""Unit tests for the restaurant 👍/👎 feedback endpoint (飞轮断点2, 2026-07-23).

Mocked pool/conn doubles (mirrors test_restaurant_intent_promotion.py style);
the route handler is called directly with a stub Request — auth middleware
(JWT / demo-write allowlist) is covered by test_demo_tenant_guard.py.
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from smartbi.api import restaurant_feedback as rf


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _FakeConn:
    """fetchval returns queued values in order (UPDATE first, INSERT second)."""

    def __init__(self, fetchval_results):
        self.fetchval_results = list(fetchval_results)
        self.guc_calls = []
        self.fetchval_calls = []

    async def execute(self, sql, *args):
        self.guc_calls.append((sql, args))

    async def fetchval(self, sql, *args):
        self.fetchval_calls.append((sql, args))
        return self.fetchval_results.pop(0)


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


def _request(factory_id="DEMO_REST"):
    return SimpleNamespace(state=SimpleNamespace(factory_id=factory_id))


def _patch_pool(monkeypatch, conn):
    import smartbi.config as cfg

    async def _fake_pool():
        return _FakePool(conn)

    monkeypatch.setattr(cfg, "get_pg_pool", _fake_pool)


@pytest.mark.asyncio
async def test_update_hits_latest_capture_row(monkeypatch):
    conn = _FakeConn(fetchval_results=[42])  # UPDATE finds a row
    _patch_pool(monkeypatch, conn)
    body = rf.RestaurantFeedbackRequest(query=" 最近损耗怎么样 ", value=1)
    out = await rf.post_restaurant_feedback(_request(), body)
    assert out == {"success": True, "id": 42}
    assert conn.guc_calls and conn.guc_calls[0][1] == ("DEMO_REST",)
    sql, args = conn.fetchval_calls[0]
    assert "UPDATE smart_bi_llm_fallback_log" in sql
    assert args == ("DEMO_REST", "最近损耗怎么样", 1, None)


@pytest.mark.asyncio
async def test_orphan_insert_when_no_capture_row(monkeypatch):
    conn = _FakeConn(fetchval_results=[None, 77])  # UPDATE misses → INSERT
    _patch_pool(monkeypatch, conn)
    body = rf.RestaurantFeedbackRequest(query="今天的招牌菜好吃吗", value=-1, comment="答非所问")
    out = await rf.post_restaurant_feedback(_request(), body)
    assert out == {"success": True, "id": 77}
    sql, args = conn.fetchval_calls[1]
    assert "INSERT INTO smart_bi_llm_fallback_log" in sql
    assert "RESTAURANT_FEEDBACK" in sql
    assert args == ("今天的招牌菜好吃吗", "DEMO_REST", -1, "答非所问")


@pytest.mark.asyncio
async def test_rejects_invalid_value(monkeypatch):
    conn = _FakeConn(fetchval_results=[])
    _patch_pool(monkeypatch, conn)
    body = rf.RestaurantFeedbackRequest(query="q", value=0)
    with pytest.raises(HTTPException) as exc:
        await rf.post_restaurant_feedback(_request(), body)
    assert exc.value.status_code == 400
    assert conn.fetchval_calls == []


@pytest.mark.asyncio
async def test_401_without_tenant_context(monkeypatch):
    conn = _FakeConn(fetchval_results=[])
    _patch_pool(monkeypatch, conn)
    body = rf.RestaurantFeedbackRequest(query="q", value=1)
    with pytest.raises(HTTPException) as exc:
        await rf.post_restaurant_feedback(_request(factory_id=None), body)
    assert exc.value.status_code == 401


@pytest.mark.asyncio
async def test_db_error_becomes_500(monkeypatch):
    class _BoomConn(_FakeConn):
        async def fetchval(self, sql, *args):
            raise RuntimeError("db down")

    conn = _BoomConn(fetchval_results=[])
    _patch_pool(monkeypatch, conn)
    body = rf.RestaurantFeedbackRequest(query="q", value=1)
    with pytest.raises(HTTPException) as exc:
        await rf.post_restaurant_feedback(_request(), body)
    assert exc.value.status_code == 500
