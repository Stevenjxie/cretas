"""Tests for smartbi.canonical.dish_alias_resolver — 别名→ID resolver (行为兼容).

验收要求 (卡3): 有映射走 ID, 无映射回落原文名 (行为兼容断言); pending 候选不影响
线上答案 (resolver 查询只认 status='confirmed', SQL 层面可断言)。
"""
from __future__ import annotations

from smartbi.canonical.dish_alias_resolver import (
    resolve_dish_alias,
    resolve_dish_reference,
)

FACTORY = "RES_3101_009"


class _FakeConn:
    def __init__(self, store_row=None, tenant_row=None):
        self._store_row = store_row
        self._tenant_row = tenant_row
        self.calls = []

    async def fetchrow(self, sql, *args):
        self.calls.append((sql, args))
        if "store_id = $2" in sql:
            return self._store_row
        return self._tenant_row


# ── resolve_dish_reference: 行为兼容契约 ────────────────────────────────────

async def test_resolve_confirmed_mapping_returns_canonical_id():
    """有 confirmed 映射 → 走 canonical_dish_id (核心新增能力)."""
    conn = _FakeConn(tenant_row={"canonical_dish_id": 7, "canonical_name": "招牌青花椒鱼"})
    result = await resolve_dish_reference(conn, FACTORY, "招牌青花椒鱼(两吃)")
    assert result == {
        "kind": "canonical_id",
        "canonical_dish_id": 7,
        "canonical_name": "招牌青花椒鱼",
        "store_scoped": False,
    }


async def test_resolve_no_mapping_falls_back_to_original_name():
    """无映射 → 行为兼容: 回落原文名, 与今天查询行为完全一致 (零回归断言)."""
    conn = _FakeConn(tenant_row=None)
    result = await resolve_dish_reference(conn, FACTORY, "无人归一的怪名字")
    assert result == {"kind": "original_name", "name": "无人归一的怪名字"}


async def test_resolve_empty_original_name_falls_back():
    conn = _FakeConn(tenant_row=None)
    result = await resolve_dish_reference(conn, FACTORY, "")
    assert result == {"kind": "original_name", "name": ""}


# ── resolve_dish_alias: pending 候选零影响 (fail-closed) ────────────────────

async def test_query_only_selects_confirmed_status():
    """SQL 层面断言: 查询语句带 status = 'confirmed' 过滤 — pending 候选查不到."""
    conn = _FakeConn(tenant_row=None)
    await resolve_dish_alias(conn, FACTORY, "某菜")
    assert conn.calls
    sql = conn.calls[-1][0]
    assert "status = 'confirmed'" in sql
    assert "'pending'" not in sql


async def test_pending_only_candidate_returns_none_falls_back():
    """FakeConn 模拟"只有 pending 候选, 无 confirmed 行" —— DB 层不会返回该行
    (因为真实 SQL 带 status='confirmed' 过滤), resolver 因而拿到 None → 回落原文名。
    """
    conn = _FakeConn(tenant_row=None)  # 模拟 DB 对 status='confirmed' 过滤后 0 行
    result = await resolve_dish_reference(conn, FACTORY, "招牌青花椒鱼(两吃)")
    assert result["kind"] == "original_name"


# ── 两段匹配: store 优先, 回落租户级 ─────────────────────────────────────────

async def test_store_scoped_alias_preferred_over_tenant_level():
    conn = _FakeConn(
        store_row={"canonical_dish_id": 3, "canonical_name": "特色鱼(该店)"},
        tenant_row={"canonical_dish_id": 9, "canonical_name": "特色鱼(租户级)"},
    )
    result = await resolve_dish_alias(conn, FACTORY, "特色鱼", store_id="STORE_A")
    assert result["canonical_dish_id"] == 3
    assert result["store_scoped"] is True


async def test_store_scoped_miss_falls_back_to_tenant_level():
    conn = _FakeConn(store_row=None, tenant_row={"canonical_dish_id": 9, "canonical_name": "特色鱼"})
    result = await resolve_dish_alias(conn, FACTORY, "特色鱼", store_id="STORE_A")
    assert result["canonical_dish_id"] == 9
    assert result["store_scoped"] is False


async def test_no_store_id_skips_store_scoped_query_entirely():
    conn = _FakeConn(tenant_row={"canonical_dish_id": 9, "canonical_name": "特色鱼"})
    result = await resolve_dish_alias(conn, FACTORY, "特色鱼")
    assert result["canonical_dish_id"] == 9
    assert len(conn.calls) == 1  # 只查了租户级, 没有 store_id 时不发起店级查询


# ── 防呆输入 ─────────────────────────────────────────────────────────────

async def test_empty_factory_returns_none_no_query():
    conn = _FakeConn()
    result = await resolve_dish_alias(conn, "", "菜")
    assert result is None
    assert conn.calls == []


async def test_empty_name_returns_none_no_query():
    conn = _FakeConn()
    result = await resolve_dish_alias(conn, FACTORY, "")
    assert result is None
    assert conn.calls == []
