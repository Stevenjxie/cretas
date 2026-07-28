"""Zero-token flywheel exits for the production (semantic-first) parse path.

Card 2 of docs/superpowers/specs/2026-07-28-restaurant-ai-flywheel-reconnect-plan.md.

Before this change `parse_restaurant_query(semantic_first=True)` -- the only
branch Web/Java restaurant chat reaches -- called the REVIEW-tier planner for
EVERY question. The validated-plan cache and the reviewed exact-phrase
registry existed but were wired only into the legacy branch. These tests pin
the reconnected behaviour AND the three invariants that make it safe:

  * what is stored is the RAW planner plan, so dates are recomputed per day;
  * conversation state (a consumed clarification, an inherited context)
    disqualifies both zero-token exits;
  * a planner outage may replay a reviewed/cached plan instead of refusing,
    but never guesses.

Everything is mocked: no DashScope, no Postgres. Fake pool style mirrors
test_restaurant_intent.py / test_restaurant_intent_clarification.py.
"""
from __future__ import annotations

import json
from dataclasses import asdict
from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from smartbi.gold import restaurant_intent as ri
from smartbi.gold import restaurant_ops_router as ops_router
from smartbi.gold.restaurant_intent import (
    TRUSTED_PLANNER_AUTHORITIES,
    _build_spec,
    clear_promoted_routes_cache,
    clear_route_cache,
    clear_semantic_plan_cache,
    clear_tenant_gate_cache,
    parse_restaurant_query,
)

FACTORY = "DEMO_REST"

# The exact plan seeded by migration V20261030_01 for the 3 reviewed phrases.
SEED_MARGIN_PLAN = {
    "intent": "RESTAURANT_OPS_GROSS_MARGIN",
    "time_range": None,
    "wants_margin": False,
    "asks_profitability": False,
    "requested_metrics": ["sales_volume"],
    "analysis_action": "lookup",
    "dimensions": ["dish"],
    "dish": None,
    "store": None,
    "stores": [],
    "store_scope": None,
    "confidence": 1.0,
    "clarification_needed": False,
    "missing_fields": [],
    "clarification_question": None,
    "clarification_options": [],
}


# All three phrases carry the same reviewed plan, exactly as the migration
# seeds them.
SEED_ROWS = [
    ("哪个菜卖得好", SEED_MARGIN_PLAN),
    ("哪个菜卖得最好", SEED_MARGIN_PLAN),
    ("哪个菜最好卖", SEED_MARGIN_PLAN),
]


def _revenue_plan() -> dict:
    """A complete all-store revenue plan. `store_scope: all` keeps the
    store-scope guard from turning it into a clarification, so these tests
    assert on the zero-token path itself rather than on scope enrichment."""
    return {
        "intent": "RESTAURANT_OPS_SALES_SUMMARY",
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": ["revenue"],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": "all",
        "confidence": 0.95,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }


@pytest.fixture(autouse=True)
def _reset_caches():
    clear_route_cache()
    clear_tenant_gate_cache()
    clear_semantic_plan_cache()
    clear_promoted_routes_cache()
    yield
    clear_route_cache()
    clear_tenant_gate_cache()
    clear_semantic_plan_cache()
    clear_promoted_routes_cache()


# ─── Fake asyncpg pool ────────────────────────────────────────────────────

class _Row(dict):
    """asyncpg Record stand-in: only __getitem__ is used by the code."""


class _FakeConn:
    def __init__(self, owner: "_FakePool"):
        self.owner = owner
        self.in_transaction = False
        self.active_factory = None

    def transaction(self):
        conn = self

        class _Ctx:
            async def __aenter__(self):
                conn.in_transaction = True
                return None

            async def __aexit__(self, *_exc):
                conn.in_transaction = False
                conn.active_factory = None
                return False

        return _Ctx()

    async def execute(self, sql, *args):
        if "set_config('app.factory_id'" in sql:
            assert self.in_transaction, "RLS GUC must be transaction-local"
            self.active_factory = args[0]
            return "SELECT 1"
        if "restaurant_pending_clarifications" in sql:
            if sql.strip().upper().startswith("INSERT"):
                self.owner.pending[(args[0], args[1])] = {
                    "original_query": args[2],
                    "clarification_question": args[3],
                }
            return "OK"
        raise AssertionError(f"unexpected execute SQL: {sql}")

    async def fetchrow(self, sql, *args):
        if "agg_restaurant_daily_totals" in sql:
            self.owner.tenant_gate_calls += 1
            return {"?column?": 1}
        if "restaurant_pending_clarifications" in sql:
            self.owner.pending_pop_calls += 1
            row = self.owner.pending.pop((args[0], args[1]), None)
            if row is None:
                return None
            return {
                "original_query": row["original_query"],
                "clarification_question": row["clarification_question"],
                "created_at": None,
            }
        raise AssertionError(f"unexpected fetchrow SQL: {sql}")

    async def fetch(self, sql, *args):
        if "ai_promoted_routes" in sql:
            self.owner.promoted_route_reads += 1
            assert self.active_factory == FACTORY, (
                "promoted-route read must pin the questioner's tenant"
            )
            return [
                _Row(normalized_phrase=phrase, plan_json=json.dumps(plan))
                for phrase, plan in self.owner.promoted_rows
            ]
        if "FROM dim_store" in sql or "fact_pos_item" in sql:
            return [_Row(name=name) for name in self.owner.stores]
        raise AssertionError(f"unexpected fetch SQL: {sql}")


class _FakePool:
    def __init__(self, *, promoted_rows=(), stores=("A店", "B店")):
        self.promoted_rows = list(promoted_rows)
        self.stores = list(stores)
        self.pending = {}
        self.tenant_gate_calls = 0
        self.pending_pop_calls = 0
        self.promoted_route_reads = 0

    def acquire(self):
        conn = _FakeConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Ctx()


class _FrozenDate(date):
    """`date` subclass so `date(...)` construction and isinstance keep working
    while `date.today()` is pinned. The window resolver reads today from the
    `date` name in restaurant_ops_router's module globals."""

    frozen = date(2026, 7, 7)

    @classmethod
    def today(cls):
        return cls.frozen


# ─── 1. Repeat question costs zero planner calls ──────────────────────────

async def test_same_question_twice_calls_the_planner_only_once():
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        first = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 1, "second identical question must not call the LLM"

    assert first.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert first.planner_authority == "llm"
    assert first.source_tier == "llm"

    # The replayed plan is the same executable contract, tagged so the capture
    # ledger (agg_meta.tier / agg_meta.planner_authority) shows it was served
    # without the planner.
    assert second.intent == first.intent
    assert second.date_range == first.date_range
    assert second.requested_metrics == first.requested_metrics
    assert second.plan_hash == first.plan_hash
    assert second.source_tier == "plan_cache"
    assert second.planner_authority == "validated_plan_cache"
    assert second.planner_authority in TRUSTED_PLANNER_AUTHORITIES
    assert second.plan_version == "restaurant-query-plan-v2"


async def test_plan_cache_is_scoped_per_tenant():
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
        other = await parse_restaurant_query(
            "本月营收多少", pool, factory_id="OTHER_REST", semantic_first=True,
        )
    assert planner.call_count == 2, "a plan must never cross a tenant boundary"
    assert other.source_tier == "llm"


# ─── 2. A cached plan is recompiled against TODAY ─────────────────────────

async def test_cached_plan_recomputes_dates_on_the_next_day(monkeypatch):
    """The stored payload is the RAW plan, not the sealed spec: replaying it
    after midnight must move the window, not repeat yesterday's dates."""
    monkeypatch.setattr(ops_router, "date", _FrozenDate)
    pool = _FakePool()

    _FrozenDate.frozen = date(2026, 7, 7)
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        day1 = await parse_restaurant_query(
            "最近7天营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

        _FrozenDate.frozen = date(2026, 7, 8)
        day2 = await parse_restaurant_query(
            "最近7天营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 1, "the second day must still be a cache hit"
    assert day2.source_tier == "plan_cache"

    assert day1.date_range == (date(2026, 7, 1), date(2026, 7, 7))
    assert day2.date_range == (date(2026, 7, 2), date(2026, 7, 8)), (
        "a replayed plan served yesterday's window -- the sealed spec was "
        "cached instead of the raw plan"
    )
    # A moved window is different semantics, so the sealed digest must differ.
    assert day1.plan_hash != day2.plan_hash

    # And what actually sits in the cache carries no resolved date at all.
    (stored,) = list(ri._SEMANTIC_PLAN_CACHE.values())
    assert "2026-07-07" not in stored[1]
    assert "2026-07-01" not in stored[1]


# ─── 3. Reviewed promotion replays through the execution contract ─────────

async def test_promoted_route_replays_the_reviewed_plan_without_the_planner():
    pool = _FakePool(promoted_rows=SEED_ROWS)
    query = "本月全部门店哪个菜卖得最好"

    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=None)
    ) as planner:
        spec = await parse_restaurant_query(
            query, pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 0, "a reviewed promotion must not call the LLM"
    assert pool.promoted_route_reads == 1

    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.clarification_needed is False
    assert spec.source_tier == "exact"
    assert spec.planner_authority == "promoted_exact"
    assert spec.planner_authority in TRUSTED_PLANNER_AUTHORITIES
    assert spec.plan_version == "restaurant-query-plan-v2"
    assert spec.plan_hash, "a replayed plan must still be sealed"

    # Byte-for-byte the same executable contract the in-code registry produced
    # before this table existed (only the provenance fields may differ).
    legacy = _build_spec(
        "RESTAURANT_OPS_GROSS_MARGIN", query, confidence=1.0, tier="exact",
        planner_authority="promoted_exact", require_explicit_time=True,
    )
    ignored = {"plan_hash", "source_tier", "planner_authority", "store_options"}
    replayed_fields = asdict(spec)
    legacy_fields = asdict(legacy)
    assert {
        k: v for k, v in replayed_fields.items() if k not in ignored
    } == {
        k: v for k, v in legacy_fields.items() if k not in ignored
    }


async def test_promoted_route_still_enforces_the_deterministic_time_gate():
    """The bare reviewed phrase carries no window. The promotion is an intent
    grant, not permission to invent a default month."""
    pool = _FakePool(promoted_rows=SEED_ROWS)
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=None)
    ) as planner:
        spec = await parse_restaurant_query(
            "哪个菜卖得好", pool, factory_id=FACTORY, semantic_first=True,
        )
    assert planner.call_count == 0
    assert spec.clarification_needed is True
    assert spec.window_label == "全部历史"
    assert "本月" in spec.clarification_options


async def test_promoted_route_never_matches_a_substring():
    """Whole-sentence equality only: a longer sentence that merely CONTAINS a
    reviewed phrase goes to the planner, as it always has."""
    pool = _FakePool(promoted_rows=SEED_ROWS)
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        spec = await parse_restaurant_query(
            "帮我看看上个月哪个菜卖得好，另外损耗怎么样",
            pool, factory_id=FACTORY, semantic_first=True,
        )
    assert planner.call_count == 1
    assert spec.planner_authority == "llm"


async def test_promoted_route_with_retired_resolver_is_ignored():
    pool = _FakePool(promoted_rows=[
        ("哪个菜卖得好", {**SEED_MARGIN_PLAN, "intent": "RESTAURANT_OPS_RETIRED"}),
    ])
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        spec = await parse_restaurant_query(
            "哪个菜卖得好", pool, factory_id=FACTORY, semantic_first=True,
        )
    assert planner.call_count == 1, "an unknown resolver must not authorize anything"
    # Whatever the planner then decides is fine; what matters is that the
    # retired row granted nothing.
    assert spec.planner_authority in {"llm", "llm_contract_repair"}


async def test_missing_promotion_table_fails_open_to_the_planner():
    class _DeadPool(_FakePool):
        def acquire(self):
            outer = self

            class _Conn(_FakeConn):
                async def fetch(self, sql, *args):
                    if "ai_promoted_routes" in sql:
                        raise RuntimeError(
                            'permission denied for table ai_promoted_routes'
                        )
                    return await super().fetch(sql, *args)

            conn = _Conn(outer)

            class _Ctx:
                async def __aenter__(self):
                    return conn

                async def __aexit__(self, *_exc):
                    return False

            return _Ctx()

    pool = _DeadPool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        spec = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
    assert planner.call_count == 1
    assert spec.planner_authority == "llm"


# ─── 4. Conversation state disqualifies both zero-token exits ─────────────

async def test_clarification_continuation_skips_the_zero_token_layer():
    """A pending clarification means this turn is an ANSWER to an earlier
    question, not the self-contained sentence either store is keyed on."""
    pool = _FakePool(promoted_rows=SEED_ROWS)
    pool.pending[(FACTORY, "sess-1")] = {
        "original_query": "上个月营收多少",
        "clarification_question": "要看哪一组门店？",
    }

    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        spec = await parse_restaurant_query(
            "哪个菜卖得好", pool, factory_id=FACTORY,
            session_key="sess-1", semantic_first=True,
        )

    assert pool.pending_pop_calls == 1
    assert planner.call_count >= 1, (
        "a continuation must be understood by the LLM, never replayed from "
        "the promotion table keyed on the bare sentence"
    )
    assert spec.planner_authority != "promoted_exact"
    assert pool.promoted_route_reads == 0


async def test_context_inheritance_skips_the_zero_token_layer(monkeypatch):
    """When the follow-up inherits slots from the session, the utterance sent
    to the planner is no longer the sentence the caches are keyed on."""
    pool = _FakePool(promoted_rows=SEED_ROWS)

    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        # Warm the plan cache with the context-free turn (this phrase is NOT
        # in the promotion table, so the planner is what fills the cache).
        await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
        assert planner.call_count == 1
        reads_after_warmup = pool.promoted_route_reads

        monkeypatch.setattr(
            ri,
            "contextualize_restaurant_followup",
            lambda query, ctx: (f"A店 {query}", True),
        )
        spec = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY,
            history=[{"q": "A店上个月营收多少", "a": "..."}],
            semantic_first=True,
        )
        # Even a reviewed promoted phrase is not replayed once context was
        # inherited.
        promoted = await parse_restaurant_query(
            "哪个菜卖得好", pool, factory_id=FACTORY,
            history=[{"q": "A店上个月营收多少", "a": "..."}],
            semantic_first=True,
        )

    assert planner.call_count == 3, (
        "an inherited-context turn must reach the LLM, not replay a plan "
        "compiled for the bare sentence"
    )
    assert spec.planner_authority not in {"promoted_exact", "validated_plan_cache"}
    assert promoted.planner_authority != "promoted_exact"
    assert pool.promoted_route_reads == reads_after_warmup, (
        "the promotion table must not even be consulted for an inherited turn"
    )


async def test_incomplete_llm_contract_is_never_cached():
    """`llm_contract_incomplete` means the model's answer failed the execution
    contract. Caching it would make one bad response permanent."""
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value={"intent": "garbage"})
    ) as planner:
        first = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
    assert first.planner_authority == "llm_contract_incomplete"
    assert planner.call_count == 2, "a rejected plan must not be replayed"
    assert second.planner_authority == "llm_contract_incomplete"
    assert not ri._SEMANTIC_PLAN_CACHE


# ─── 5. Planner outage lifeboat ───────────────────────────────────────────

async def test_planner_outage_is_answered_from_the_promotion_table():
    """The reviewed table is a human decision that does not expire: while the
    planner is down it answers instead of the "稍后重试" refusal."""
    pool = _FakePool(promoted_rows=SEED_ROWS)
    with patch.object(ri, "_t3_llm_parse", new=AsyncMock(return_value=None)):
        spec = await parse_restaurant_query(
            "本月全部门店哪个菜卖得好", pool, factory_id=FACTORY,
            semantic_first=True,
        )
    assert spec.planner_authority == "promoted_exact"
    assert spec.intent == "RESTAURANT_OPS_GROSS_MARGIN"
    assert spec.clarification_needed is False
    assert "稍后重试" not in (spec.clarification_question or "")


async def test_planner_outage_may_serve_an_expired_cached_plan():
    """The post-outage branch: an aged plan is skipped on the normal path but
    is still date-correct once recompiled, so it beats refusing to answer."""
    pool = _FakePool()
    planner = AsyncMock(return_value=_revenue_plan())
    with patch.object(ri, "_t3_llm_parse", new=planner):
        await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )
    assert planner.call_count == 1

    # Age every cached plan past its TTL.
    for key, (_expires, payload) in list(ri._SEMANTIC_PLAN_CACHE.items()):
        ri._SEMANTIC_PLAN_CACHE[key] = (0.0, payload)

    with patch.object(ri, "_t3_llm_parse", new=AsyncMock(return_value=None)):
        spec = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert spec.planner_authority == "validated_plan_cache"
    assert spec.source_tier == "plan_cache"
    assert spec.intent == "RESTAURANT_OPS_SALES_SUMMARY"
    assert spec.clarification_needed is False


async def test_planner_outage_without_any_replay_still_fails_closed():
    pool = _FakePool()
    with patch.object(ri, "_t3_llm_parse", new=AsyncMock(return_value=None)):
        spec = await parse_restaurant_query(
            "上个月西红柿的采购价怎么样", pool, factory_id=FACTORY,
            semantic_first=True,
        )
    assert spec.planner_authority == "llm_unavailable"
    assert spec.clarification_needed is True
    assert spec.intent == ""
    assert "稍后重试" in spec.clarification_question


# ─── Capture ledger carries the right tier ────────────────────────────────

async def test_capture_records_the_zero_token_tier_and_authority():
    pool = _FakePool(promoted_rows=SEED_ROWS)
    with patch.object(ri, "_t3_llm_parse", new=AsyncMock(return_value=None)):
        spec = await parse_restaurant_query(
            "本月全部门店哪个菜卖得好", pool, factory_id=FACTORY,
            semantic_first=True,
        )

    captured = {}

    async def _fake_log_template_hit(pool_, query, factory_id, _none, code,
                                     answer, wall_ms, agg_meta=None):
        captured.update(agg_meta or {})
        return 1

    with patch(
        "smartbi.services.llm_fallback_logger.log_template_hit",
        new=_fake_log_template_hit,
    ):
        await ri.log_intent_capture(
            pool, spec, factory_id=FACTORY, query="本月全部门店哪个菜卖得好",
            answer="...", contract_pass=True, served=True,
        )

    assert captured["tier"] == "exact"
    assert captured["planner_authority"] == "promoted_exact"
    assert captured["plan_version"] == "restaurant-query-plan-v2"
    assert captured["plan_hash"] == spec.plan_hash


# ─── Promotion CLI write path (`--apply` -> ai_promoted_routes) ───────────

class _WriterConn(_FakeConn):
    async def execute(self, sql, *args):
        if "ai_promoted_routes" in sql:
            self.owner.writes.append({
                "guc": self.active_factory,
                "domain": args[0],
                "normalized_phrase": args[1],
                "plan_json": args[2],
                "plan_version": args[3],
                "source": args[4],
                "scope": args[5],
                "reviewed_by": args[6],
            })
            return "INSERT 0 1"
        return await super().execute(sql, *args)


class _WriterPool(_FakePool):
    def __init__(self):
        super().__init__()
        self.writes = []

    def acquire(self):
        conn = _WriterConn(self)

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *_exc):
                return False

        return _Ctx()


async def test_apply_route_promotions_writes_a_global_row_as_internal():
    from smartbi.gold.restaurant_intent_promotion import apply_route_promotions

    pool = _WriterPool()
    result = await apply_route_promotions(
        pool,
        [{"query": "  哪个菜卖得好。 ", "code": "RESTAURANT_OPS_GROSS_MARGIN"}],
        reviewed_by="steve",
    )

    assert [w["normalized_phrase"] for w in pool.writes] == ["哪个菜卖得好"], (
        "the stored phrase must be the normalized form the matcher compares"
    )
    write = pool.writes[0]
    assert write["guc"] == "__internal__", (
        "RLS refuses a global-scope write from a tenant session"
    )
    assert write["scope"] == "global"
    assert write["source"] == "manual_seed"
    assert write["reviewed_by"] == "steve"
    assert write["plan_version"] == "restaurant-query-plan-v2"
    assert json.loads(write["plan_json"])["time_range"] is None
    assert result["skipped"] == []


async def test_apply_route_promotions_rejects_a_plan_that_cannot_be_replayed():
    from smartbi.gold.restaurant_intent_promotion import apply_route_promotions

    pool = _WriterPool()
    result = await apply_route_promotions(pool, [
        {"query": "", "code": "RESTAURANT_OPS_GROSS_MARGIN"},
        {"query": "随便问问", "code": "NOT_A_REAL_CODE"},
        {"query": "这句话没有计划"},
        # Contract-incomplete plan: the compiler would refuse it at read time,
        # so it must never reach the table.
        {"query": "半个计划", "plan": {"intent": "RESTAURANT_OPS_GROSS_MARGIN"}},
        # A plan carrying resolved dates would be replayed verbatim tomorrow.
        {"query": "带死日期的计划", "plan": {
            **SEED_MARGIN_PLAN, "date_range": ["2026-07-01", "2026-07-07"],
        }},
    ])

    assert pool.writes == []
    reasons = {e["query"]: e["reason"] for e in result["skipped"]}
    assert reasons[""] == "empty_query"
    assert reasons["随便问问"].startswith("unknown_intent")
    assert reasons["这句话没有计划"] == "missing_plan_and_code"
    assert reasons["半个计划"].startswith("plan_not_replayable")
    assert reasons["带死日期的计划"] == "plan_contains_resolved_dates"


async def test_apply_route_promotions_tenant_scope_uses_the_tenant_guc():
    from smartbi.gold.restaurant_intent_promotion import apply_route_promotions

    pool = _WriterPool()
    await apply_route_promotions(
        pool,
        [{"query": "哪个菜卖得好", "code": "RESTAURANT_OPS_GROSS_MARGIN"}],
        scope="FAC_X", source="flywheel",
    )
    assert pool.writes[0]["guc"] == "FAC_X"
    assert pool.writes[0]["scope"] == "FAC_X"
    assert pool.writes[0]["source"] == "flywheel"


# ─────────────────────────────────────────────────────────────────────────────
# 计划缓存: 历史来源的时间窗不入缓存 (2026-07-28 spec 歧义收口, 方案 C)
#
# spec §3 的准入三条只要求"上下文继承未改写问句", 但 history (最多 20 轮) 照样
# 喂给 planner。实体有 _verbatim_entity 兜底, 时间没有 —— 见
# `_plan_time_slot_came_from_history` 的注释。
# ─────────────────────────────────────────────────────────────────────────────

def _revenue_plan_with_llm_time() -> dict:
    """A complete revenue plan whose time window came from the planner
    (`time_range` set), not from the sentence."""
    plan = _revenue_plan()
    plan["time_range"] = {"type": "relative", "unit": "month", "count": 1}
    return plan


async def test_history_supplied_time_window_is_not_cached():
    """B must not inherit the window A's history produced for a bare sentence."""
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan_with_llm_time())
    ) as planner:
        # A: bare sentence (no time word), but the session carries history, so
        # the planner fills the window from earlier turns.
        first = await parse_restaurant_query(
            "营收多少", pool, factory_id=FACTORY,
            history=[{"q": "上个月营收多少", "a": "..."}],
            semantic_first=True,
        )
        # B: brand-new session, same bare sentence, no history at all.
        second = await parse_restaurant_query(
            "营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 2, (
        "a history-derived time window must NOT be cached under the bare "
        "sentence -- B would silently inherit A's window"
    )
    assert first.source_tier == "llm"
    assert second.source_tier == "llm"
    assert second.planner_authority != "validated_plan_cache"


async def test_sentence_with_its_own_time_word_still_caches_with_history():
    """The narrow guard must not cost hit rate: when the sentence carries its
    own time word the deterministic layer parses it, so history is irrelevant
    and the plan is still cached."""
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan_with_llm_time())
    ) as planner:
        await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY,
            history=[{"q": "上个月营收多少", "a": "..."}],
            semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 1, (
        "an explicit-time sentence must still cache even on a turn with history"
    )
    assert second.source_tier == "plan_cache"
    assert second.planner_authority == "validated_plan_cache"


async def test_history_without_an_llm_time_slot_still_caches():
    """History alone does not block caching -- only a history-derived TIME slot
    does. (Guards against anyone "simplifying" this to `not history`.)"""
    pool = _FakePool()
    with patch.object(
        ri, "_t3_llm_parse", new=AsyncMock(return_value=_revenue_plan())
    ) as planner:
        await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY,
            history=[{"q": "先看看门店列表", "a": "..."}],
            semantic_first=True,
        )
        second = await parse_restaurant_query(
            "本月营收多少", pool, factory_id=FACTORY, semantic_first=True,
        )

    assert planner.call_count == 1, "plain history must not disable the plan cache"
    assert second.source_tier == "plan_cache"


def test_history_time_slot_predicate_is_narrow():
    """Unit-level truth table for the guard itself."""
    hist = [{"q": "上个月营收多少", "a": "..."}]
    llm_time = {"time_range": {"type": "relative", "unit": "month", "count": 1}}
    no_time = {"time_range": None}

    # blocked: bare sentence + LLM-supplied window + history
    assert ri._plan_time_slot_came_from_history(llm_time, "营收多少", hist) is True
    # allowed: no history
    assert ri._plan_time_slot_came_from_history(llm_time, "营收多少", None) is False
    # allowed: sentence carries its own time word
    assert ri._plan_time_slot_came_from_history(llm_time, "本月营收多少", hist) is False
    # allowed: planner supplied no window
    assert ri._plan_time_slot_came_from_history(no_time, "营收多少", hist) is False
