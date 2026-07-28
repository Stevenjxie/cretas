"""Tests for smartbi.gold.restaurant.pos_name_resolver — POS dish-name resolution backfill.

餐饮 #61 Phase 1. 5-layer resolver: L0 alias-exact, L1 product_types exact,
L2 normalized match, L3 difflib SequenceMatcher (auto/queue/no-match),
L4 transitive-over-alias, L5 write unresolved_queue sorted revenue_at_risk DESC.

Spec: docs/superpowers/specs/2026-06-04-restaurant-pos-name-resolution-design.md

Design note on the data contract (load-bearing):
  The finance ETL Stage 3 (_resolve_pos_to_product_types) keys on
  dim_product.normalized_name, matching against product_types.name AND
  dim_product_alias.pos_name. So the resolver MUST key alias writes + queue rows
  on normalized_name (not the raw display name), or the ETL fallback never hits.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from smartbi.gold.restaurant import pos_name_resolver as R


# ─────────────────────────────────────────────────────────────────────
# Fake asyncpg pool/conn harness
# ─────────────────────────────────────────────────────────────────────

class FakeConn:
    """Records execute calls; serves scripted fetch/fetchval responses by SQL substring."""

    def __init__(self):
        self.executed: list = []          # (sql, args)
        self.fetch_router: list = []       # (substr, rows)
        self.fetchval_router: list = []    # (substr, value)
        self.fetched: list = []            # (sql, args)

    def add_fetch(self, substr: str, rows: list):
        self.fetch_router.append((substr, rows))

    def add_fetchval(self, substr: str, value):
        self.fetchval_router.append((substr, value))

    async def fetch(self, sql, *args):
        self.fetched.append((sql, args))
        for substr, rows in self.fetch_router:
            if substr in sql:
                return rows
        return []

    async def fetchval(self, sql, *args):
        for substr, value in self.fetchval_router:
            if substr in sql:
                return value
        return None

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "OK"

    def transaction(self):
        tx = AsyncMock()
        tx.__aenter__ = AsyncMock(return_value=None)
        tx.__aexit__ = AsyncMock(return_value=None)
        return tx


def make_pool(conn: FakeConn) -> MagicMock:
    pool = MagicMock()
    ctx = AsyncMock()
    ctx.__aenter__ = AsyncMock(return_value=conn)
    ctx.__aexit__ = AsyncMock(return_value=None)
    pool.acquire = MagicMock(return_value=ctx)
    return pool


FACTORY = "R_QINGHUAJIAO_REAL"


def _queue_upserts(conn: FakeConn) -> list:
    """Extract (args) of INSERT INTO restaurant_pos_unresolved_queue execute calls."""
    return [
        args for sql, args in conn.executed
        if "restaurant_pos_unresolved_queue" in sql and "INSERT" in sql.upper()
    ]


def _alias_upserts(conn: FakeConn) -> list:
    return [
        args for sql, args in conn.executed
        if "dim_product_alias" in sql and "INSERT" in sql.upper()
    ]


# ─────────────────────────────────────────────────────────────────────
# _normalize_name reuse + surrogate
# ─────────────────────────────────────────────────────────────────────

def test_normalize_name_reused():
    assert R._normalize_name("  Kung Pao  Chicken ") == "kung pao chicken"
    assert R._normalize_name("") == ""


def test_pos_dish_surrogate_is_positive_bigint_and_deterministic():
    a = R._pos_dish_surrogate_bigint(FACTORY, "宫保鸡丁", "pt-uuid-1")
    b = R._pos_dish_surrogate_bigint(FACTORY, "宫保鸡丁", "pt-uuid-1")
    c = R._pos_dish_surrogate_bigint(FACTORY, "宫保鸡丁", "pt-uuid-2")
    assert a == b, "deterministic"
    assert a != c, "different product_type_id → different surrogate"
    assert 0 < a < 2 ** 63, "must fit in signed BIGINT and be positive"


# ─────────────────────────────────────────────────────────────────────
# 5-layer resolution
# ─────────────────────────────────────────────────────────────────────

def _wire(cretas_conn, smartbi_conn, *, pos_rows, product_types, existing_aliases):
    """Helper: wire fake DB responses for resolve_factory_pos_names.

    pos_rows: list of dicts {normalized_name, display_name, revenue, qty, bills}
    product_types: list of dicts {id, name}
    existing_aliases: list of dicts {pos_name, product_type_id}
    """
    smartbi_conn.add_fetch("fact_pos_item", pos_rows)
    cretas_conn.add_fetch("FROM product_types", product_types)
    cretas_conn.add_fetch("FROM dim_product_alias", existing_aliases)


@pytest.mark.asyncio
async def test_L0_alias_exact_skips(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        pos_rows=[{"normalized_name": "宫保鸡丁", "display_name": "宫保鸡丁",
                   "revenue": 500.0, "qty": 10.0, "bills": 8}],
        product_types=[],
        existing_aliases=[{"pos_name": "宫保鸡丁", "product_type_id": "pt-1"}],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["totalPosNames"] == 1
    assert result["alreadyResolved"] == 1
    assert result["queued"] == 0
    assert _queue_upserts(smartbi) == []


@pytest.mark.asyncio
async def test_L1_product_types_exact(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        pos_rows=[{"normalized_name": "宫保鸡丁", "display_name": "宫保鸡丁",
                   "revenue": 500.0, "qty": 10.0, "bills": 8}],
        # product_types.name compared against normalized_name (ETL semantics)
        product_types=[{"id": "pt-1", "name": "宫保鸡丁"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["alreadyResolved"] == 1
    assert result["queued"] == 0
    # L1 exact does NOT write an alias (ETL primary path already hits exact name)
    assert _alias_upserts(cretas) == []


@pytest.mark.asyncio
async def test_L2_normalized_match_persists_RAW_verbatim_key(monkeypatch):
    """REGRESSION (review BLOCKING): the persisted alias key MUST be the VERBATIM
    dim_product.normalized_name (the exact key the finance ETL Stage 3 alias fallback
    looks up: WHERE pos_name = ANY(raw normalized_name)). If the resolver lowercases it
    via _normalize_name, the ETL lookup misses and the cost card stays ¥0 while the
    resolver falsely reports success. _normalize_name is for fuzzy comparison ONLY.

    Scenario: POS normalized_name is verbatim-cased 'Kung Pao Chicken'; the product name
    differs only by case ('KUNG PAO CHICKEN') so it matches via L2 normalized comparison
    (NOT L1 raw-exact) — exactly the path where the old code lowercased the key.
    """
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        pos_rows=[{"normalized_name": "Kung Pao Chicken", "display_name": "Kung Pao Chicken",
                   "revenue": 300.0, "qty": 5.0, "bills": 4}],
        product_types=[{"id": "pt-2", "name": "KUNG PAO CHICKEN"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["resolvedAuto"] == 1
    assert result["queued"] == 0
    aliases = _alias_upserts(cretas)
    assert len(aliases) == 1
    # alias key MUST be the RAW verbatim normalized_name, NOT the _normalize_name'd form
    assert "Kung Pao Chicken" in aliases[0]
    assert "kung pao chicken" not in aliases[0]  # must NOT persist the lowercased key
    assert "pt-2" in aliases[0]


@pytest.mark.asyncio
async def test_L3_high_overlap_auto_accept(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        # "麻婆豆腐盖浇饭" vs "麻婆豆腐盖饭" → difflib ratio 0.923 (>=0.85)
        pos_rows=[{"normalized_name": "麻婆豆腐盖浇饭", "display_name": "麻婆豆腐盖浇饭",
                   "revenue": 400.0, "qty": 6.0, "bills": 5}],
        product_types=[{"id": "pt-3", "name": "麻婆豆腐盖饭"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    # ratio of "麻婆豆腐盖浇饭" vs "麻婆豆腐盖饭" = 0.923 >= 0.85 → auto-accept
    assert result["resolvedAuto"] == 1, f"expected auto-accept, got {result}"
    assert len(_alias_upserts(cretas)) == 1


@pytest.mark.asyncio
async def test_L3_mid_overlap_queues_with_candidate(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        # mid overlap (0.60-0.85): partial match
        pos_rows=[{"normalized_name": "红烧牛肉面套餐", "display_name": "红烧牛肉面套餐",
                   "revenue": 1200.0, "qty": 20.0, "bills": 18}],
        product_types=[{"id": "pt-4", "name": "红烧牛肉面"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["queued"] == 1
    assert result["resolvedAuto"] == 0
    assert _alias_upserts(cretas) == [], "mid-overlap must NOT auto-write alias"
    q = _queue_upserts(smartbi)
    assert len(q) == 1
    # best_candidate_id present
    assert "pt-4" in q[0]
    assert "红烧牛肉面套餐" in q[0]  # keyed on normalized_name


@pytest.mark.asyncio
async def test_L3_no_match_queues_without_candidate_and_revenue_at_risk(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        pos_rows=[{"normalized_name": "完全不相关的菜", "display_name": "完全不相关的菜",
                   "revenue": 999.5, "qty": 3.0, "bills": 3}],
        product_types=[{"id": "pt-5", "name": "宫保鸡丁"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["queued"] == 1
    q = _queue_upserts(smartbi)
    assert len(q) == 1
    # revenue_at_risk = 999.5 carried into the queue row
    assert any(abs(float(a) - 999.5) < 0.001 for a in q[0] if isinstance(a, (int, float)))


@pytest.mark.asyncio
async def test_L4_transitive_over_alias_inherits(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    # A confirmed alias "宫保鸡" → pt-9 exists. New POS normalized_name "宫保鸡 " (trailing
    # space) is a DISTINCT RAW key — the finance ETL keys verbatim, so it would MISS the
    # "宫保鸡" alias and stay unresolved. It normalizes to the same fuzzy form, so L4
    # transitive SUGGESTS pt-9 and QUEUES it for admin confirm — it is NOT auto-resolved
    # (the raw keys differ; only an explicit confirm writes the "宫保鸡 " alias the ETL needs).
    _wire(
        cretas, smartbi,
        pos_rows=[{"normalized_name": "宫保鸡 ", "display_name": "宫保 鸡",
                   "revenue": 200.0, "qty": 2.0, "bills": 2}],
        product_types=[],
        existing_aliases=[{"pos_name": "宫保鸡", "product_type_id": "pt-9"}],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    # Distinct raw key → not L0/L1; L4 transitive over the near alias → queued with pt-9 hint.
    assert result["alreadyResolved"] == 0
    assert result["queued"] == 1
    queued = _queue_upserts(smartbi)
    assert len(queued) == 1
    assert "pt-9" in queued[0]   # transitive candidate inherited from the near alias


@pytest.mark.asyncio
async def test_queue_sorted_by_revenue_at_risk_desc(monkeypatch):
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(
        cretas, smartbi,
        pos_rows=[
            {"normalized_name": "低风险无关菜", "display_name": "低风险无关菜",
             "revenue": 10.0, "qty": 1.0, "bills": 1},
            {"normalized_name": "高风险无关菜", "display_name": "高风险无关菜",
             "revenue": 5000.0, "qty": 1.0, "bills": 1},
        ],
        product_types=[{"id": "pt-x", "name": "宫保鸡丁"}],
        existing_aliases=[],
    )
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["queued"] == 2
    q = _queue_upserts(smartbi)
    # First upsert should be the higher revenue_at_risk (sorted DESC before write)
    first_revenue = [a for a in q[0] if isinstance(a, (int, float)) and a in (10.0, 5000.0)]
    assert 5000.0 in first_revenue


@pytest.mark.asyncio
async def test_ensure_alias_schema_idempotent_ddl():
    cretas = FakeConn()
    await R.ensure_alias_schema(make_pool(cretas), FACTORY)
    ddl = " ".join(sql for sql, _ in cretas.executed)
    assert "CREATE TABLE IF NOT EXISTS dim_product_alias" in ddl
    assert "ADD COLUMN IF NOT EXISTS confidence" in ddl
    assert "ADD COLUMN IF NOT EXISTS source" in ddl
    assert "ADD COLUMN IF NOT EXISTS decided_by_agent" in ddl


@pytest.mark.asyncio
async def test_empty_pos_rows_returns_zero():
    cretas, smartbi = FakeConn(), FakeConn()
    _wire(cretas, smartbi, pos_rows=[], product_types=[], existing_aliases=[])
    result = await R.resolve_factory_pos_names(make_pool(cretas), make_pool(smartbi), FACTORY)
    assert result["totalPosNames"] == 0
    assert result["queued"] == 0
    assert result["resolvedAuto"] == 0
