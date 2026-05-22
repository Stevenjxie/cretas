"""Sprint 11 MealClaw Response — unit tests for restaurant_llm_formatter.

Covers:
- ``to_llm_format`` strict whitelist (only 5 fields out, no metadata leaks)
- Path B fallback (30d empty → 365d, summary explicitly mentions fallback)
- ``dataAvailable`` breakdown (overall + posData / wastageData / consumptionData)
- Integration with the 3 endpoint shapes (top-ingredients / completeness / ETL)

All tests are pure (no DB, no HTTP) — they feed crafted raw dicts directly.
"""
from __future__ import annotations

from smartbi.services.restaurant_llm_formatter import (
    DEFAULT_WINDOW_DAYS,
    FALLBACK_WINDOW_DAYS,
    WHITELIST,
    _build_evidence,
    _extract_top_items,
    _has_business_data,
    to_llm_format,
)


# ── Whitelist enforcement ────────────────────────────────────────────────────

def test_whitelist_is_frozen_set_of_5_fields():
    assert WHITELIST == frozenset({
        "summary", "topItems", "recommendations", "evidence", "dataAvailable"
    })


def test_to_llm_format_strips_all_non_whitelist_fields():
    """Steve decision 2 — feed a dirty payload, assert only 5 fields survive."""
    dirty = {
        "success": True,
        "data": [{"name": "rice", "value": 100, "rank": 1}],
        # Metadata that must NEVER reach the LLM
        "_toolCount": 7,
        "_internalId": "tool-xyz-42",
        "_trace": ["sql A", "sql B"],
        "_executionMs": 1234,
        "_factoryId": "RES_3101_009",
        "factoryAgeDays": 9999,
    }
    out = to_llm_format(dirty, requested_days=30, factory_id="RES_3101_009")

    # Only the 5 whitelisted keys present
    assert set(out.keys()) == set(WHITELIST)
    assert "_toolCount" not in out
    assert "_internalId" not in out
    assert "_trace" not in out
    assert "_executionMs" not in out
    assert "_factoryId" not in out
    # And the nested structures don't accidentally smuggle metadata into evidence
    assert "_trace" not in str(out["evidence"])


def test_to_llm_format_always_returns_all_5_keys_even_on_empty_input():
    out = to_llm_format({}, requested_days=30, factory_id="F999_EMPTY")
    assert set(out.keys()) == set(WHITELIST)
    assert isinstance(out["topItems"], list)
    assert isinstance(out["recommendations"], list)
    assert isinstance(out["evidence"], dict)
    assert isinstance(out["dataAvailable"], dict)
    assert isinstance(out["summary"], str)


# ── Path B fallback semantics ────────────────────────────────────────────────

def test_path_b_fallback_summary_contains_explicit_wording():
    """Per dispatch log §3 + brief §2.5 — when fallback=True, the summary MUST
    tell the LLM the recent window was empty and a wider window is being shown.
    """
    raw = {"success": True, "data": [{"name": "猪肉", "value": 5000, "rank": 1}]}
    out = to_llm_format(
        raw,
        requested_days=30,
        actual_days=365,
        fallback=True,
        factory_id="RES_3101_009",
    )

    summary = out["summary"]
    assert "近 30 天无销售数据" in summary
    assert "365" in summary

    # evidence must record the requested/actual mismatch and fallback flag
    ev = out["evidence"]
    assert ev["dataWindow"]["requested"] == "30d"
    assert ev["dataWindow"]["actual"] == "365d"
    assert ev["dataWindow"]["fallback"] is True
    assert ev["dataWindow"]["fallbackReason"] is not None
    assert "MaterialConsumption" in ev["dataWindow"]["fallbackReason"] or \
        "audit §6.2" in ev["dataWindow"]["fallbackReason"] or \
        "hooks 未 wire" in ev["dataWindow"]["fallbackReason"]


def test_no_fallback_when_data_exists():
    raw = {"success": True, "data": [{"name": "rice", "value": 100, "rank": 1}]}
    out = to_llm_format(raw, requested_days=30, actual_days=30, fallback=False)

    assert out["evidence"]["dataWindow"]["fallback"] is False
    assert out["evidence"]["dataWindow"]["fallbackReason"] is None
    assert "近 30 天无" not in out["summary"]


def test_path_b_recommendation_added_when_fallback():
    raw = {"success": True, "data": [{"name": "猪肉", "value": 5000, "rank": 1}]}
    out = to_llm_format(
        raw, requested_days=30, actual_days=365, fallback=True
    )
    joined = " ".join(out["recommendations"])
    assert "近 30 天无新数据" in joined or "建议补 wire" in joined


# ── dataAvailable breakdown (audit §6.2) ─────────────────────────────────────

def test_data_available_overall_false_for_empty_raw():
    out = to_llm_format({}, requested_days=30)
    da = out["dataAvailable"]
    assert da["overall"] is False
    assert "posData" in da
    assert "wastageData" in da
    assert "consumptionData" in da


def test_data_available_consumption_always_false_without_modules():
    """Per audit §6.2 — MaterialConsumption hooks not wired; default to False."""
    raw = {"success": True, "data": [{"name": "rice", "value": 100, "rank": 1}]}
    out = to_llm_format(raw, requested_days=30)
    assert out["dataAvailable"]["consumptionData"] is False


def test_data_available_consumption_true_when_completeness_shows_requisition():
    raw = {
        "factoryId": "F001",
        "modules": [
            {"id": "requisition", "name": "领料记录", "recordCount": 12, "coverage": 80},
            {"id": "wastage", "name": "损耗记录", "recordCount": 5, "coverage": 60},
            {"id": "pos_sales", "name": "POS 销售数据", "recordCount": 100, "coverage": 95},
        ],
        "overallCompleteness": 78,
    }
    out = to_llm_format(raw, requested_days=30, factory_id="F001")
    da = out["dataAvailable"]
    assert da["overall"] is True
    assert da["posData"] is True
    assert da["wastageData"] is True
    assert da["consumptionData"] is True


# ── topItems extraction (3 endpoint shapes) ──────────────────────────────────

def test_extract_top_items_from_list_payload():
    raw = {
        "success": True,
        "data": [
            {"name": "猪肉", "value": 5000, "rank": 1, "category": "肉类", "unit": "kg"},
            {"name": "白菜", "value": 1200, "rank": 2, "category": "蔬菜", "unit": "kg"},
        ],
    }
    items = _extract_top_items(raw)
    assert len(items) == 2
    assert items[0]["name"] == "猪肉"
    assert items[0]["value"] == 5000.0


def test_extract_top_items_caps_at_10():
    raw = {
        "success": True,
        "data": [{"name": f"item-{i}", "value": i, "rank": i} for i in range(25)],
    }
    items = _extract_top_items(raw)
    assert len(items) == 10


def test_extract_top_items_from_completeness_shape_surfaces_low_coverage():
    raw = {
        "modules": [
            {"id": "wastage", "name": "损耗记录", "coverage": 15},
            {"id": "pos_sales", "name": "POS 销售数据", "coverage": 95},
            {"id": "requisition", "name": "领料记录", "coverage": 45},
            {"id": "menu_recipe", "name": "菜品配方", "coverage": 100},
        ]
    }
    items = _extract_top_items(raw)
    # Only the modules with coverage < 80 should surface
    names = [i["name"] for i in items]
    assert "损耗记录" in names
    assert "领料记录" in names
    assert "POS 销售数据" not in names
    assert "菜品配方" not in names
    # Sorted by ascending coverage (lowest first = highest priority)
    assert items[0]["name"] == "损耗记录"
    # value carries the coverage score
    assert items[0]["value"] == 15


def test_extract_top_items_from_etl_shape():
    raw = {
        "success": True,
        "data": {
            "dim_ingredient_upserted": 53,
            "fact_requisition_upserted": 0,
            "fact_wastage_upserted": 1,
            "agg_daily_ops_upserted": 100,
            "errors": [],
        },
    }
    items = _extract_top_items(raw)
    # All *_upserted with positive values surface, errors list stays out
    names = [i["name"] for i in items]
    assert any("agg daily ops" in n for n in names)
    assert any("dim ingredient" in n for n in names)
    # Ranked descending by value
    assert items[0]["value"] == 100  # agg_daily_ops_upserted


# ── recommendations builder ──────────────────────────────────────────────────

def test_recommendations_warn_when_consumption_data_missing():
    raw = {"success": True, "data": [{"name": "rice", "value": 1, "rank": 1}]}
    out = to_llm_format(raw, requested_days=30)
    recs_joined = " ".join(out["recommendations"])
    assert "MaterialConsumption" in recs_joined


def test_recommendations_flag_concentration_when_top1_over_40_percent():
    raw = {
        "success": True,
        "data": [
            {"name": "猪肉", "value": 8000, "rank": 1},
            {"name": "白菜", "value": 1000, "rank": 2},
            {"name": "胡萝卜", "value": 500, "rank": 3},
            {"name": "葱", "value": 500, "rank": 4},
        ],
    }
    out = to_llm_format(raw, requested_days=30)
    joined = " ".join(out["recommendations"])
    assert "猪肉" in joined
    assert "集中度过高" in joined


def test_recommendations_capped_and_deduplicated():
    raw = {
        "modules": [
            {"id": f"x{i}", "name": f"模块{i}", "coverage": 10, "missingHints": ["补数据"]}
            for i in range(20)
        ]
    }
    out = to_llm_format(raw, requested_days=30)
    # Cap is 8 in formatter
    assert len(out["recommendations"]) <= 8


# ── helper unit tests ────────────────────────────────────────────────────────

def test_has_business_data_recognizes_non_empty_list():
    assert _has_business_data({"success": True, "data": [{"x": 1}]}) is True
    assert _has_business_data({"success": True, "data": []}) is False
    assert _has_business_data({"success": False, "data": [{"x": 1}]}) is False


def test_has_business_data_recognizes_etl_upsert_counters():
    assert _has_business_data({
        "success": True,
        "data": {"fact_requisition_upserted": 5, "errors": []},
    }) is True
    assert _has_business_data({
        "success": True,
        "data": {"fact_requisition_upserted": 0, "errors": []},
    }) is False


def test_has_business_data_recognizes_completeness_overall_zero():
    assert _has_business_data({"overallCompleteness": 0, "modules": []}) is False
    assert _has_business_data({"overallCompleteness": 50, "modules": []}) is True


def test_build_evidence_default_no_fallback():
    ev = _build_evidence({}, 30, 30, False)
    assert ev["dataWindow"]["requested"] == "30d"
    assert ev["dataWindow"]["actual"] == "30d"
    assert ev["dataWindow"]["fallback"] is False
    assert ev["dataWindow"]["fallbackReason"] is None
    assert ev["source"] == "smartbi-gold"


def test_build_evidence_with_fallback():
    ev = _build_evidence({}, 30, 365, True, source_label="restaurant-llm-composite")
    assert ev["dataWindow"]["fallback"] is True
    assert ev["dataWindow"]["fallbackReason"] is not None
    assert ev["source"] == "restaurant-llm-composite"


def test_default_window_constants_match_spec():
    """Path B sticks to 30 / 365 per dispatch log §3."""
    assert DEFAULT_WINDOW_DAYS == 30
    assert FALLBACK_WINDOW_DAYS == 365


# ── summary builder integrated ───────────────────────────────────────────────

def test_summary_includes_factory_label():
    raw = {"success": True, "data": [{"name": "rice", "value": 100, "rank": 1}]}
    out = to_llm_format(raw, requested_days=30, factory_id="RES_3101_009")
    assert "RES_3101_009" in out["summary"]


def test_summary_warns_when_consumption_data_missing():
    raw = {"success": True, "data": [{"name": "rice", "value": 100, "rank": 1}]}
    out = to_llm_format(raw, requested_days=30, factory_id="F001")
    # consumptionData defaults to False (audit §6.2)
    assert "MaterialConsumption hooks 未 wire" in out["summary"]


def test_summary_includes_overall_completeness_when_present():
    raw = {
        "overallCompleteness": 72,
        "modules": [
            {"id": "pos_sales", "name": "POS", "recordCount": 10, "coverage": 80},
        ],
    }
    out = to_llm_format(raw, requested_days=30, factory_id="F001")
    assert "72/100" in out["summary"]
