"""Regression tests for the P1–P3 adversarial-audit fixes (2026-07-09)."""
from smartbi.agent.synthesis_engine import (
    ComprehensiveSynthesisEngine,
    _build_history_block,
)
from smartbi.agent.factbook import FactBook


# ── P2 #1: numeric-redaction of injected history (grounding hole) ──────────
def test_history_numbers_redacted_entities_survive():
    block = _build_history_block([
        {"q": "乙店营收多少", "a_summary": "乙店上月营收200万，客单价93.7元，比全链低30%"},
    ])
    assert "[数值]" in block
    for leak in ("200", "93.7", "30"):
        assert leak not in block          # no history number can reach the answer
    assert "乙店" in block                 # entity survives for 指代 resolution


def test_history_keeps_entity_ids_and_ordinals():
    # A store id / ordinal is an entity identifier, NOT a data figure — it must
    # survive redaction so a follow-up "它…/第3点…" resolves (live role-play:
    # "示范门店01" was wrongly stripped to "示范门店" and stopped resolving).
    block = _build_history_block([
        {"q": "哪家店拖后腿",
         "a_summary": "示范门店01最拖后腿，营收15,018,722元，客单价228.80，第3点是建议"},
    ])
    assert "示范门店01" in block          # entity id survives
    assert "第3" in block                  # ordinal survives
    # figures still stripped (grounding intact)
    assert "15,018,722" not in block
    assert "228.80" not in block


def test_history_non_string_fields_do_not_crash():
    # non-string q/a_summary must not raise (str() coerce) — a direct/Java caller
    # could pass a non-string; comprehensive() has no try around synthesize.
    block = _build_history_block([{"q": 123, "a_summary": {"x": 1}}])
    assert isinstance(block, str)


# ── P3 F1: "哪天" no longer over-triggers the weather dimension ─────────────
def test_naerdian_ranking_does_not_trip_weather():
    eng = ComprehensiveSynthesisEngine(pool=object())
    assert eng.plan_dimensions("哪天营收最高")["weather"] is False
    # a genuine weather question still triggers
    assert eng.plan_dimensions("下雨对营收影响大吗")["weather"] is True


# ── P3 F2: all per-bucket weather numbers are grounded in facts_index ──────
def test_weather_facts_index_grounds_all_buckets():
    fb = FactBook(weather={
        "buckets": [
            {"cond": "晴天", "n_days": 10, "avg_rev": 150.0, "avg_ticket": 30.0, "avg_bills": 5.0},
            {"cond": "雨天", "n_days": 5, "avg_rev": 90.0, "avg_ticket": 28.0, "avg_bills": 3.2},
            {"cond": "暴雨", "n_days": 2, "avg_rev": 80.0, "avg_ticket": 27.0, "avg_bills": 3.0,
             "small_sample": True},
        ],
        "rain_vs_sunny_pct": -40.0,
    })
    idx = fb.to_facts_index()
    assert idx["暴雨日均营收"] == 80.0
    assert idx["暴雨客单价"] == 27.0
    assert idx["晴天日均订单"] == 5.0


# ── P3 F3: pct=None must not render the literal "None%" ────────────────────
def test_weather_render_pct_none_no_literal_none():
    fb = FactBook(weather={
        "buckets": [
            {"cond": "晴天", "n_days": 3, "avg_rev": 0.0, "avg_ticket": None, "avg_bills": 0.0},
            {"cond": "雨天", "n_days": 2, "avg_rev": 90.0, "avg_ticket": 28.0, "avg_bills": 3.0},
        ],
        "rain_vs_sunny_pct": None,
    })
    assert "None%" not in fb.to_prompt_text()
