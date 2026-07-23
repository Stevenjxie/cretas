"""Unit tests for the restaurant intent flywheel promotion module.

Design docs:
  docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md (section 5)
  docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md

Everything here is mocked (no live DB, no live embedding service) -- mirrors
the mocking style of test_restaurant_intent.py (fake asyncpg pool/conn
doubles) and test_learning_promotion.py (ledger-file monkeypatching).
"""
from __future__ import annotations

import json

import pytest

from smartbi.gold import restaurant_intent_promotion as promo


# ─── Fake asyncpg pool/conn doubles (mirrors test_restaurant_intent.py) ────

class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _FakeConn:
    def __init__(self, rows=None, exc=None):
        self.rows = rows or []
        self.exc = exc
        self.calls = []
        self.guc_calls = []  # set_config 调用 (RLS GUC — 2026-07-23 修)

    async def execute(self, sql, *args):
        self.guc_calls.append((sql, args))

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        if self.exc:
            raise self.exc
        return self.rows


class _FakePool:
    def __init__(self, conn: _FakeConn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


def _row(query, codes, occurrence_count, max_confidence, last_seen="2026-07-07"):
    return {
        "norm_query": query,
        "codes": codes,
        "occurrence_count": occurrence_count,
        "max_confidence": max_confidence,
        "last_seen": last_seen,
    }


@pytest.fixture(autouse=True)
def _isolated_ledger(tmp_path, monkeypatch):
    """Point LEDGER_FILE at a fresh (initially nonexistent) per-test path so
    no test reads or writes the real repo ledger. `merge_samples()` still
    pulls in the REAL `restaurant_ops_router.SAMPLE_QUERIES` as its base --
    none of the synthetic Chinese test queries below are exact-string matches
    for any of the 8 codes' shipped sample phrases, so this does not create
    accidental "already known" collisions; tests that need a query to be
    "already known" write it into this same tmp_path ledger file explicitly."""
    monkeypatch.setattr(promo, "LEDGER_FILE", tmp_path / "ledger.json")
    yield


# ─── aggregate_candidates: recommendation gate ─────────────────────────────

async def test_aggregate_recommends_on_count_path():
    conn = _FakeConn(rows=[
        _row("这两个月生意咋样", ["RESTAURANT_OPS_SALES_SUMMARY"] * 3, 3, 0.5),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert len(candidates) == 1
    c = candidates[0]
    assert c["recommended"] is True
    assert c["conflict"] is False
    assert c["code"] == "RESTAURANT_OPS_SALES_SUMMARY"
    assert c["occurrence_count"] == 3


async def test_aggregate_recommends_on_confidence_path():
    conn = _FakeConn(rows=[
        _row("挣着钱没", ["RESTAURANT_OPS_SALES_SUMMARY"], 1, 0.9),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates[0]["recommended"] is True
    assert candidates[0]["occurrence_count"] == 1


async def test_aggregate_not_recommended_below_threshold():
    conn = _FakeConn(rows=[
        _row("今天咋样", ["RESTAURANT_OPS_SALES_SUMMARY"], 1, 0.5),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates[0]["recommended"] is False
    assert candidates[0]["conflict"] is False


async def test_aggregate_conflict_marks_not_recommended_even_with_high_count():
    # Same normalized query resolved to two different codes across repeats --
    # even though occurrence_count clears the count-path threshold, a code
    # disagreement must never be auto-recommended.
    conn = _FakeConn(rows=[
        _row("情况怎么样", [
            "RESTAURANT_OPS_SALES_SUMMARY", "RESTAURANT_OPS_SALES_SUMMARY",
            "RESTAURANT_OPS_TREND_ANALYSIS", "RESTAURANT_OPS_TREND_ANALYSIS",
        ], 4, 0.95),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates[0]["conflict"] is True
    assert candidates[0]["recommended"] is False
    # majority code (tie broken by Counter.most_common's stable insertion order)
    assert candidates[0]["code"] == "RESTAURANT_OPS_SALES_SUMMARY"
    assert candidates[0]["codes"] == ["RESTAURANT_OPS_SALES_SUMMARY", "RESTAURANT_OPS_TREND_ANALYSIS"]


async def test_aggregate_excludes_already_known_query(tmp_path):
    # _isolated_ledger (autouse) already points promo.LEDGER_FILE at this
    # same tmp_path -- write the query into it so merge_samples()'s "known"
    # set includes it via the (real) ledger-merge path, not a mocked stand-in.
    ledger = tmp_path / "ledger.json"
    ledger.write_text(
        json.dumps({"RESTAURANT_OPS_SALES_SUMMARY": ["这两个月生意咋样"]}),
        encoding="utf-8",
    )
    conn = _FakeConn(rows=[
        _row("这两个月生意咋样", ["RESTAURANT_OPS_SALES_SUMMARY"] * 3, 3, 0.9),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates == []


async def test_aggregate_excludes_group_with_no_valid_codes():
    conn = _FakeConn(rows=[
        _row("某问题", ["NOT_A_REAL_CODE"], 5, 0.99),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates == []


async def test_aggregate_ignores_invalid_codes_within_a_mixed_group():
    # A stray non-canonical code in the array (defensive -- shouldn't happen
    # given the WHERE clause, but the aggregation must not choke on it) is
    # dropped rather than causing a spurious conflict.
    conn = _FakeConn(rows=[
        _row("库存盘点差异", ["RESTAURANT_OPS_STOCK_SHORTAGE", "NOT_A_REAL_CODE"], 2, 0.5),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert len(candidates) == 1
    assert candidates[0]["conflict"] is False
    assert candidates[0]["code"] == "RESTAURANT_OPS_STOCK_SHORTAGE"


async def test_aggregate_fail_open_on_db_error():
    conn = _FakeConn(exc=RuntimeError("db unavailable"))
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates == []


async def test_aggregate_empty_query_skipped():
    conn = _FakeConn(rows=[
        _row("   ", ["RESTAURANT_OPS_SALES_SUMMARY"], 3, 0.9),
    ])
    pool = _FakePool(conn)

    candidates = await promo.aggregate_candidates(pool)

    assert candidates == []


async def test_aggregate_passes_min_confidence_and_min_count_params():
    conn = _FakeConn(rows=[])
    pool = _FakePool(conn)

    await promo.aggregate_candidates(pool, min_confidence=0.8, min_count=3, limit=50)

    assert conn.calls
    _sql, args = conn.calls[0]
    assert args == (0.8, 3, 50)


# ─── merge_samples / load_promoted_samples ─────────────────────────────────

def test_load_promoted_samples_missing_file_returns_empty(tmp_path, monkeypatch):
    monkeypatch.setattr(promo, "LEDGER_FILE", tmp_path / "does-not-exist.json")

    assert promo.load_promoted_samples() == {}


def test_load_promoted_samples_corrupt_file_fails_open(tmp_path, monkeypatch):
    bad = tmp_path / "bad.json"
    bad.write_text("{not json", encoding="utf-8")
    monkeypatch.setattr(promo, "LEDGER_FILE", bad)

    assert promo.load_promoted_samples() == {}


def test_merge_samples_appends_ledger_only_entries(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    ledger.write_text(
        json.dumps({"RESTAURANT_OPS_SALES_SUMMARY": ["新问法一", "新问法二"]}),
        encoding="utf-8",
    )
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)

    merged = promo.merge_samples({"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]})

    assert merged["RESTAURANT_OPS_SALES_SUMMARY"][0] == "老问法"  # base order preserved first
    assert "新问法一" in merged["RESTAURANT_OPS_SALES_SUMMARY"]
    assert "新问法二" in merged["RESTAURANT_OPS_SALES_SUMMARY"]
    assert len(merged["RESTAURANT_OPS_SALES_SUMMARY"]) == 3


def test_merge_samples_dedupes_ledger_entries_already_in_base(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    ledger.write_text(
        json.dumps({"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]}),
        encoding="utf-8",
    )
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)

    merged = promo.merge_samples({"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]})

    assert merged["RESTAURANT_OPS_SALES_SUMMARY"] == ["老问法"]


def test_merge_samples_no_ledger_returns_base_unchanged(tmp_path, monkeypatch):
    monkeypatch.setattr(promo, "LEDGER_FILE", tmp_path / "missing.json")

    base = {"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]}
    merged = promo.merge_samples(base)

    assert merged == {"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]}
    assert merged is not base  # a fresh dict, not the same object


# ─── apply_promotions: the only write path ─────────────────────────────────

def test_apply_promotions_writes_new_ledger(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)

    result = promo.apply_promotions([
        {"query": "这两个月生意咋样", "code": "RESTAURANT_OPS_SALES_SUMMARY"},
    ])

    assert result["added"] == [{"query": "这两个月生意咋样", "code": "RESTAURANT_OPS_SALES_SUMMARY"}]
    assert result["skipped"] == []
    assert ledger.exists()
    on_disk = json.loads(ledger.read_text(encoding="utf-8"))
    assert on_disk == {"RESTAURANT_OPS_SALES_SUMMARY": ["这两个月生意咋样"]}


def test_apply_promotions_rejects_invalid_code(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)

    result = promo.apply_promotions([
        {"query": "某问题", "code": "NOT_A_REAL_CODE"},
    ])

    assert result["added"] == []
    assert result["skipped"][0]["reason"] == "invalid_code_or_empty_query"
    assert not ledger.exists()  # nothing added -> no gratuitous write


def test_apply_promotions_rejects_empty_query(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)

    result = promo.apply_promotions([
        {"query": "   ", "code": "RESTAURANT_OPS_SALES_SUMMARY"},
    ])

    assert result["added"] == []
    assert result["skipped"][0]["reason"] == "invalid_code_or_empty_query"


def test_apply_promotions_is_idempotent_on_repeat(tmp_path, monkeypatch):
    ledger = tmp_path / "ledger.json"
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)
    entry = {"query": "这两个月生意咋样", "code": "RESTAURANT_OPS_SALES_SUMMARY"}

    first = promo.apply_promotions([entry])
    second = promo.apply_promotions([entry])

    assert len(first["added"]) == 1
    assert second["added"] == []
    assert second["skipped"][0]["reason"] == "already_in_ledger"
    on_disk = json.loads(ledger.read_text(encoding="utf-8"))
    assert on_disk == {"RESTAURANT_OPS_SALES_SUMMARY": ["这两个月生意咋样"]}


def test_aggregate_candidates_never_calls_apply_promotions():
    # aggregate_candidates (the read-only listing path) must never itself
    # invoke apply_promotions (the only write path) -- a candidate-listing
    # call site must not be able to trigger a silent write. Structural guard:
    # look for an actual call expression, not just the name appearing in a
    # comment/docstring cross-reference.
    import inspect
    src = inspect.getsource(promo.aggregate_candidates)
    assert "apply_promotions(" not in src


# ─── populate_restaurant_ops: merges the ledger into what gets embedded ────

async def test_populate_restaurant_ops_merges_ledger(tmp_path, monkeypatch):
    from smartbi.services import template_embedding_index as tei

    ledger = tmp_path / "ledger.json"
    ledger.write_text(
        json.dumps({"RESTAURANT_OPS_SALES_SUMMARY": ["新问法"]}), encoding="utf-8",
    )
    monkeypatch.setattr(promo, "LEDGER_FILE", ledger)
    monkeypatch.setattr(
        "smartbi.gold.restaurant_ops_router.SAMPLE_QUERIES",
        {"RESTAURANT_OPS_SALES_SUMMARY": ["老问法"]},
    )

    captured = {}

    async def fake_embed_and_upsert(pool, code_to_samples, *, log_prefix="template-emb"):
        captured["code_to_samples"] = code_to_samples
        return {code: len(qs) for code, qs in code_to_samples.items()}

    monkeypatch.setattr(tei, "_embed_and_upsert_samples", fake_embed_and_upsert)

    summary = await tei.populate_restaurant_ops(pool=object())

    merged = captured["code_to_samples"]["RESTAURANT_OPS_SALES_SUMMARY"]
    assert "老问法" in merged
    assert "新问法" in merged
    assert summary["RESTAURANT_OPS_SALES_SUMMARY"] == 2


# ─── question-family classification (2026-07-08 evidence-based backlog) ─────
from smartbi.gold.restaurant_intent_promotion import (  # noqa: E402
    classify_question_family,
    family_breakdown,
)


class TestQuestionFamily:
    def test_attribution_cues(self):
        for q in ["哪家店拖后腿，是客流还是客单价", "为什么这个月毛利低了",
                  "哪个菜拖累了整体毛利", "差评变多的原因"]:
            assert classify_question_family(q) == "attribution", q

    def test_write_cues(self):
        for q in ["帮我建个领料单", "录入今天的盘点", "新建一张调拨单"]:
            assert classify_question_family(q) == "write", q

    def test_attribution_colloquial_cues(self):
        # F2 (2026-07-08 role-play): plain-speech attribution must be labeled
        # attribution so the synthesis demand report doesn't under-count it.
        for q in ["十六家店里头哪家最不行，是没人来还是客人花的钱少",
                  "有的店生意就是做不起来", "谁最差"]:
            assert classify_question_family(q) == "attribution", q

    def test_chabuduo_neutral_stays_query(self):
        # 哪家差 dropped (⊂ 哪家差不多, neutral) → must stay query (audit B#3).
        assert classify_question_family("这两家店哪家差不多能达标") == "query"

    def test_query_default(self):
        # Neutral/positive queries (incl. superlatives like 最多) stay query —
        # the new colloquial attribution cues are underperformance-specific.
        for q in ["这个月营收多少", "哪家店订单最多", "本周销量排行", ""]:
            assert classify_question_family(q) == "query", q

    def test_family_breakdown_counts(self):
        cands = [
            {"query": "哪家店拖后腿"}, {"query": "为什么亏钱"},
            {"query": "帮我建个领料单"}, {"query": "本月营收多少"},
            {"family": "query", "query": "x"},  # pre-tagged honored
        ]
        b = family_breakdown(cands)
        assert b == {"attribution": 2, "write": 1, "query": 2}


# ─── Miss capture (flywheel 盲区修补 2026-07-23) ───────────────────────────

class TestAggregateMisses:
    def _miss_row(self, query, n, reasons, spec_intents, last_seen="2026-07-23"):
        return {
            "norm_query": query, "occurrence_count": n,
            "reasons": reasons, "spec_intents": spec_intents,
            "last_seen": last_seen,
        }

    @pytest.mark.asyncio
    async def test_sets_rls_guc_before_fetch(self):
        conn = _FakeConn(rows=[])
        await promo.aggregate_misses(_FakePool(conn), factory_id="RES_3101_009")
        assert conn.guc_calls, "must set app.factory_id GUC (FORCE RLS)"
        assert conn.guc_calls[0][1] == ("RES_3101_009",)

    @pytest.mark.asyncio
    async def test_maps_rows_and_family(self):
        conn = _FakeConn(rows=[
            self._miss_row("帮我建个领料单", 3, ["prefilter"], None),
            self._miss_row("外卖利润率咋算", 2, ["should_delegate"],
                           ["RESTAURANT_OPS_SALES_SUMMARY"]),
            self._miss_row("  ", 1, ["prefilter"], None),  # blank dropped
        ])
        out = await promo.aggregate_misses(_FakePool(conn))
        assert len(out) == 2
        assert out[0]["family"] == "write"
        assert out[1]["spec_intents"] == ["RESTAURANT_OPS_SALES_SUMMARY"]

    @pytest.mark.asyncio
    async def test_fail_open_on_db_error(self):
        conn = _FakeConn(exc=RuntimeError("boom"))
        assert await promo.aggregate_misses(_FakePool(conn)) == []


class TestLogIntentMiss:
    @pytest.mark.asyncio
    async def test_writes_sentinel_code_and_served_false(self, monkeypatch):
        from smartbi.gold import restaurant_intent as ri
        calls = []

        async def _fake_hit(pool, query, factory_id, upload_id, template_code,
                            answer, wall_ms, agg_meta=None):
            calls.append((template_code, agg_meta))
            return 1

        import smartbi.services.llm_fallback_logger as fl
        monkeypatch.setattr(fl, "log_template_hit", _fake_hit)
        out = await ri.log_intent_miss(
            object(), factory_id="DEMO_REST", query="帮我导出报表",
            reason="prefilter", java_tool_name="restaurant_ops_gold_analysis",
        )
        assert out == 1
        code, meta = calls[0]
        assert code == "RESTAURANT_OPS_MISS"
        assert meta["served"] is False
        assert meta["miss_reason"] == "prefilter"
        assert meta["java_tool_name"] == "restaurant_ops_gold_analysis"

    @pytest.mark.asyncio
    async def test_never_raises(self, monkeypatch):
        from smartbi.gold import restaurant_intent as ri
        import smartbi.services.llm_fallback_logger as fl

        async def _boom(*a, **k):
            raise RuntimeError("db down")

        monkeypatch.setattr(fl, "log_template_hit", _boom)
        assert await ri.log_intent_miss(
            object(), factory_id="F", query="q", reason="should_delegate",
        ) is None
