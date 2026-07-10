"""Unit tests for NarrativeCacheService.get_semantic (2026-07-10 semantic
cache fallback layer).

These are pure Python-mock tests (no live Postgres/pgvector needed) — they
exercise the grounding guards that matter regardless of the actual cosine
math: same-window + same-plan hard filters, and the 0.90 similarity floor.
The fake connection mimics the SQL WHERE clause (factory_id, window_key,
plan_key, expires_at, question_embedding IS NOT NULL) so a test asserting
"returns None when window differs" is actually exercising the same
filtering the real SQL performs, not just the Python-side threshold check.

See tests/test_agent_narrative_cache.py for the DB-backed exact-hash tests
(get/put/invalidate/prune) — those are unaffected by this change.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

import pytest

from smartbi.agent.narrative_cache import NarrativeCacheService

_FACTORY = "F001"
_WINDOW = "2026-06-01|2026-06-30"
_PLAN = "channel,finance"


class _FakeRow(dict):
    """asyncpg Record supports `row["field"]` — a plain dict is enough for
    every access pattern get_semantic uses."""


class _FakeConn:
    """Mimics the subset of asyncpg.Connection used by get_semantic: a
    no-op `execute` (SET app.factory_id), a `transaction()` async context
    manager, and a `fetchrow` that filters an in-memory row list exactly
    like the real SQL WHERE clause (factory_id, window_key, plan_key,
    expires_at > now, question_embedding IS NOT NULL) before picking the
    single highest-similarity row (mirrors ORDER BY ... <=> ... LIMIT 1).
    """

    def __init__(self, rows: List[Dict[str, Any]]):
        self._rows = rows

    async def execute(self, *args, **kwargs):
        return None

    def transaction(self):
        return _NullAsyncCtx()

    async def fetchrow(self, sql: str, factory_id, emb_literal, window_key, plan_key):
        now = datetime.now(timezone.utc)
        candidates = [
            r for r in self._rows
            if r["factory_id"] == factory_id
            and r["window_key"] == window_key
            and r["plan_key"] == plan_key
            and r["expires_at"] > now
            and r.get("question_embedding") is not None
        ]
        if not candidates:
            return None
        best = max(candidates, key=lambda r: r["sim"])
        return _FakeRow(
            answer=best["answer"],
            chart_config=best.get("chart_config"),
            tokens=best.get("tokens", 0),
            sim=best["sim"],
        )


class _NullAsyncCtx:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False


class _FakePool:
    def __init__(self, rows: List[Dict[str, Any]]):
        self._conn = _FakeConn(rows)

    def acquire(self):
        return _AcquireCtx(self._conn)


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *exc):
        return False


def _row(
    *, sim: float, window_key: str = _WINDOW, plan_key: str = _PLAN,
    factory_id: str = _FACTORY, answer: str = "答案",
    chart_config: Optional[Dict[str, Any]] = None, tokens: int = 42,
    expires_in_hours: float = 24.0,
) -> Dict[str, Any]:
    return {
        "factory_id": factory_id,
        "window_key": window_key,
        "plan_key": plan_key,
        "sim": sim,
        "answer": answer,
        "chart_config": chart_config,
        "tokens": tokens,
        "question_embedding": [0.1, 0.2, 0.3],  # non-None marker only
        "expires_at": datetime.now(timezone.utc) + timedelta(hours=expires_in_hours),
    }


def _dummy_emb() -> List[float]:
    return [0.1] * 768


async def test_get_semantic_hits_when_similarity_at_or_above_threshold():
    rows = [_row(sim=0.93, answer="外卖比堂食赚钱")]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is not None
    assert hit["answer"] == "外卖比堂食赚钱"
    assert hit["similarity"] == pytest.approx(0.93)


async def test_get_semantic_exact_threshold_boundary_hits():
    """sim == 0.90 exactly must still hit (>= not >)."""
    rows = [_row(sim=0.90)]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is not None


async def test_get_semantic_misses_below_threshold():
    rows = [_row(sim=0.89)]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_misses_on_different_window_even_if_high_similarity():
    """⛔ grounding guard: a paraphrase about "上周" must never serve a row
    cached for "这两月" — window_key is a hard filter, not part of the score."""
    rows = [_row(sim=0.99, window_key="2026-05-01|2026-05-07")]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_misses_on_different_plan_even_if_high_similarity():
    """⛔ grounding guard: "堂食赚钱吗" (finance+channel) must never serve
    for "堂食忙吗" (channel only) — plan_key is a hard filter."""
    rows = [_row(sim=0.99, plan_key="channel")]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_misses_on_different_factory():
    rows = [_row(sim=0.99, factory_id="F002")]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_misses_when_no_rows():
    svc = NarrativeCacheService(_FakePool([]))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_ignores_expired_rows():
    rows = [_row(sim=0.95, expires_in_hours=-1.0)]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_picks_highest_similarity_among_matches():
    rows = [
        _row(sim=0.91, answer="lower"),
        _row(sim=0.97, answer="higher"),
    ]
    svc = NarrativeCacheService(_FakePool(rows))
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is not None
    assert hit["answer"] == "higher"


async def test_get_semantic_never_raises_on_db_error():
    class _ExplodingConn(_FakeConn):
        async def fetchrow(self, *a, **kw):
            raise RuntimeError("boom")

    class _ExplodingPool(_FakePool):
        def __init__(self):
            self._conn = _ExplodingConn([])

    svc = NarrativeCacheService(_ExplodingPool())
    hit = await svc.get_semantic(_FACTORY, _dummy_emb(), _WINDOW, _PLAN)
    assert hit is None


async def test_get_semantic_custom_min_similarity_override():
    rows = [_row(sim=0.80)]
    svc = NarrativeCacheService(_FakePool(rows))
    assert await svc.get_semantic(
        _FACTORY, _dummy_emb(), _WINDOW, _PLAN, min_similarity=0.90,
    ) is None
    hit = await svc.get_semantic(
        _FACTORY, _dummy_emb(), _WINDOW, _PLAN, min_similarity=0.75,
    )
    assert hit is not None
