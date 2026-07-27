"""Tests for smartbi.canonical.dish_alias_matcher — 卡3 语义辅助初匹配 (三态).

三态: 同名 (exact rule-normalized key) / 相似名 (difflib >= 阈值) / 无匹配。
propose_dish_alias_candidates 只 PROPOSE pending 候选, 绝不自动 confirm; 已有行
(任意 status) 不重复提议, 不覆盖既有人工判断。
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from smartbi.canonical.dish_alias_matcher import (
    REVIEW_SOURCE_LEVENSHTEIN,
    REVIEW_SOURCE_RULE,
    SIMILAR_THRESHOLD,
    match_candidate,
    propose_dish_alias_candidates,
)

FACTORY = "RES_3101_009"


def _canon(cid, name, key=None):
    return {
        "canonical_dish_id": cid,
        "canonical_name": name,
        "normalized_key": key if key is not None else name,
    }


# ── match_candidate 三态 (pure, no DB) ──────────────────────────────────────

def test_match_candidate_exact_same_normalized_key():
    """同名态: 规则层归一 key 完全一致 → confidence 1.0, rule_layer."""
    canon = [_canon(1, "招牌青花椒鱼", key="招牌青花椒鱼")]
    result = match_candidate("#招牌青花椒鱼(微麻微辣)(一吃)#", canon)
    assert result is not None
    assert result.canonical_dish_id == 1
    assert result.confidence == 1.0
    assert result.review_source == REVIEW_SOURCE_RULE


def test_match_candidate_similar_name_above_threshold():
    """相似名态: 非精确 key 匹配, 但 difflib ratio >= 阈值 → 提议, levenshtein.

    "麻婆豆腐盖浇饭" vs "麻婆豆腐盖饭" ratio = 0.923 (>= 0.85), 与
    pos_name_resolver 同款 L3 fixture 一致。
    """
    canon = [_canon(2, "麻婆豆腐盖饭", key="麻婆豆腐盖饭")]
    result = match_candidate("麻婆豆腐盖浇饭", canon)
    assert result is not None
    assert result.canonical_dish_id == 2
    assert result.review_source == REVIEW_SOURCE_LEVENSHTEIN
    assert result.confidence >= SIMILAR_THRESHOLD
    assert result.confidence < 1.0


def test_match_candidate_no_match_returns_none():
    """无匹配态: 字面不像任何既有 canonical → None (不是低置信也提议)."""
    canon = [_canon(3, "宫保鸡丁", key="宫保鸡丁")]
    result = match_candidate("完全不相关的菜", canon)
    assert result is None


def test_match_candidate_never_conflates_similar_but_different_dishes():
    """红烧牛肉 vs 红烧牛腩 字面相似 (ratio=0.75) 但低于阈值 → 无匹配态, 不误判为候选.

    字面相似 ≠ 同菜 (per MEMORY #364 / dish_rule_normalize 头注 举例)。
    """
    canon = [_canon(4, "红烧牛腩", key="红烧牛腩")]
    result = match_candidate("红烧牛肉", canon)
    assert result is None


def test_match_candidate_empty_canonical_list_no_match():
    assert match_candidate("宫保鸡丁", []) is None


def test_match_candidate_empty_name_no_match():
    assert match_candidate("", [_canon(1, "宫保鸡丁")]) is None
    assert match_candidate("   ", [_canon(1, "宫保鸡丁")]) is None


# ── propose_dish_alias_candidates (async, DB-backed) ────────────────────────

def _mock_pool(fetch_router, insert_returns=None):
    """conn.fetch 按 SQL 子串路由; conn.fetchrow 用于 INSERT...RETURNING, 按调用顺序出队."""
    conn = AsyncMock()

    async def _fetch(sql, *args):
        for substr, rows in fetch_router:
            if substr in sql:
                return rows
        return []
    conn.fetch = AsyncMock(side_effect=_fetch)

    insert_queue = list(insert_returns or [])

    async def _fetchrow(sql, *args):
        if insert_queue:
            return insert_queue.pop(0)
        return {"id": 1}
    conn.fetchrow = AsyncMock(side_effect=_fetchrow)
    conn.execute = AsyncMock(return_value=None)

    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool, conn


async def test_propose_dry_run_writes_nothing():
    pool, conn = _mock_pool(
        fetch_router=[
            ("FROM dim_canonical_dish", [_canon(1, "招牌青花椒鱼", key="招牌青花椒鱼")]),
            ("FROM restaurant_dish_alias", []),
        ],
    )
    candidates = await propose_dish_alias_candidates(
        pool, FACTORY, ["#招牌青花椒鱼(一吃)#"], dry_run=True,
    )
    assert len(candidates) == 1
    assert candidates[0].canonical_dish_id == 1
    conn.fetchrow.assert_not_awaited()  # no INSERT attempted in dry_run


async def test_propose_skips_names_that_already_have_any_row():
    """已有行 (任意 status) 的 original_name 不重复提议, 不覆盖既有判断."""
    pool, conn = _mock_pool(
        fetch_router=[
            ("FROM dim_canonical_dish", [_canon(1, "招牌青花椒鱼", key="招牌青花椒鱼")]),
            ("FROM restaurant_dish_alias", [{"original_name": "招牌青花椒鱼(单人份)"}]),
        ],
    )
    candidates = await propose_dish_alias_candidates(
        pool, FACTORY, ["招牌青花椒鱼(单人份)"], dry_run=True,
    )
    assert candidates == []


async def test_propose_no_candidates_when_no_canonical_dishes():
    pool, conn = _mock_pool(
        fetch_router=[
            ("FROM dim_canonical_dish", []),
            ("FROM restaurant_dish_alias", []),
        ],
    )
    candidates = await propose_dish_alias_candidates(
        pool, FACTORY, ["宫保鸡丁"], dry_run=True,
    )
    assert candidates == []


async def test_propose_writes_pending_status_via_insert_on_conflict_do_nothing():
    """apply (dry_run=False): candidate 写库, INSERT 语句带 pending + ON CONFLICT DO NOTHING."""
    pool, conn = _mock_pool(
        fetch_router=[
            ("FROM dim_canonical_dish", [_canon(1, "招牌青花椒鱼", key="招牌青花椒鱼")]),
            ("FROM restaurant_dish_alias", []),
        ],
        insert_returns=[{"id": 501}],
    )
    candidates = await propose_dish_alias_candidates(
        pool, FACTORY, ["招牌青花椒鱼(两吃)"], dry_run=False,
    )
    assert len(candidates) == 1
    conn.fetchrow.assert_awaited_once()
    insert_call = conn.fetchrow.await_args_list[0]
    insert_sql = insert_call.args[0]
    insert_args = insert_call.args[1:]
    assert "ON CONFLICT (factory_id, original_name) DO NOTHING" in insert_sql
    assert "'pending'" in insert_sql
    assert "'confirmed'" not in insert_sql
    assert FACTORY in insert_args
    assert "招牌青花椒鱼(两吃)" in insert_args
    assert 1 in insert_args  # canonical_dish_id


async def test_propose_never_writes_confirmed_status():
    """回归防呆: INSERT 模板本身不可能产出 confirmed — 断言语句字面量."""
    from smartbi.canonical.dish_alias_matcher import _INSERT_PENDING_SQL
    assert "'confirmed'" not in _INSERT_PENDING_SQL
    assert "'pending'" in _INSERT_PENDING_SQL


async def test_propose_empty_names_short_circuits_no_db_call():
    pool, conn = _mock_pool(fetch_router=[])
    candidates = await propose_dish_alias_candidates(pool, FACTORY, [], dry_run=True)
    assert candidates == []
    conn.fetch.assert_not_awaited()
