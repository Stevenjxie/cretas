"""Unit tests for the synthesis demand-signal report (read-only helper logic)."""
import importlib


mod = importlib.import_module("scripts.synthesis_family_report")


def test_build_candidates_uses_stored_family():
    rows = [
        {"query": "哪家店拖后腿", "family": "attribution", "occurrence_count": 3,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    assert cands[0]["family"] == "attribution"
    assert cands[0]["occurrence_count"] == 3


def test_build_candidates_falls_back_to_classifier_when_family_missing():
    # legacy row without a stored question_family → classify from the query text
    rows = [
        {"query": "帮我建个领料单", "family": None, "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
        {"query": "这个月营业额多少", "family": None, "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    fams = {c["query"]: c["family"] for c in cands}
    assert fams["帮我建个领料单"] == "write"
    assert fams["这个月营业额多少"] == "query"


def test_family_breakdown_reused_over_candidates():
    rows = [
        {"query": "为什么利润下降", "family": "attribution", "occurrence_count": 2,
         "business_type": "restaurant", "last_seen": None},
        {"query": "新建盘点单", "family": "write", "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    bd = mod.family_breakdown(cands)
    assert bd["attribution"] == 1
    assert bd["write"] == 1
    assert bd["query"] == 0


def test_stray_family_clamped_no_keyerror():
    # A row with a family value outside the known set (legacy / future 4th family)
    # must be clamped to "query" so family_breakdown() doesn't KeyError-crash the
    # whole report.
    rows = [
        {"query": "某个问题", "family": "sentiment", "occurrence_count": 1,
         "business_type": "restaurant", "last_seen": None},
    ]
    cands = mod._build_candidates(rows)
    assert cands[0]["family"] == "query"
    bd = mod.family_breakdown(cands)  # must not raise
    assert bd["query"] == 1
