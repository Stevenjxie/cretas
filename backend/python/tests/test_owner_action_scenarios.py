"""Tests for the A1 owner-action scenario-terms unification.

Spec: docs/superpowers/specs/2026-07-08-business-concept-registry-direction.md
(§3, A1 step) — collapses the fifth drift point identified in §1: owner-action
scenario terms were maintained twice, once in
web-admin/src/views/smart-bi/restaurantOwnerActionRegistry.ts (frontend
inference) and once in backend/python/smartbi/api/restaurant_sections.py's
``_OWNER_ACTION_KEYWORDS`` tuple (backend topic-gate + fallback inference),
each drifting independently.

A1 moves the backend's copy to load from a shared JSON file
(backend/python/smartbi/data/owner_action_scenarios.json), seeded from a
conservative, order-safety-verified union of the two prior term sets. The
frontend's runtime (registry.ts) is intentionally left unchanged this round
(see the top-of-file comment added there); this file's
``test_frontend_terms_are_subset_of_shared_json`` is the guard against it
drifting further ahead of the shared JSON.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
JSON_PATH = (
    REPO_ROOT / "backend" / "python" / "smartbi" / "data" / "owner_action_scenarios.json"
)
FRONTEND_TS_PATH = (
    REPO_ROOT / "web-admin" / "src" / "views" / "smart-bi" / "restaurantOwnerActionRegistry.ts"
)

# Terms the frontend assigns to one scenario but which the shared JSON
# deliberately keeps under a DIFFERENT scenario, to avoid changing the
# backend's fallback-loop winner for a term that already resolved correctly
# pre-A1 (see JSON _meta.excludedConflicts for the full reasoning). These are
# the only two frontend (scenario, term) pairs allowed to be "missing" from
# the JSON's same-named scenario in the subset check below.
KNOWN_FRONTEND_ONLY_EXCEPTIONS = {
    ("staffing_schedule", "服务员"),  # JSON keeps this under staff_training instead
    ("package", "加购"),  # JSON keeps this under single_item_push instead
}


def _load_json() -> dict:
    assert JSON_PATH.exists(), f"shared owner-action data file missing: {JSON_PATH}"
    with JSON_PATH.open(encoding="utf-8") as fh:
        return json.load(fh)


def _frontend_scenario_terms() -> dict[str, list[str]]:
    assert FRONTEND_TS_PATH.exists(), f"frontend registry.ts missing: {FRONTEND_TS_PATH}"
    text = FRONTEND_TS_PATH.read_text(encoding="utf-8")
    match = re.search(
        r"OWNER_ACTION_SCENARIO_TERMS[^=]*=\s*\[(.*?)\n\];", text, re.S
    )
    assert match, "could not locate OWNER_ACTION_SCENARIO_TERMS array in registry.ts"
    body = match.group(1)
    result: dict[str, list[str]] = {}
    for block in re.finditer(
        r"scenario:\s*'([a-z_]+)'\s*,\s*terms:\s*\[(.*?)\]", body, re.S
    ):
        scenario = block.group(1)
        terms = re.findall(r"'([^']+)'", block.group(2))
        result[scenario] = terms
    assert result, "parsed zero scenarios from registry.ts — regex likely stale"
    return result


# ============================================================
# 1. JSON structure
# ============================================================


class TestSharedJsonStructure:
    def test_json_loads_and_has_13_scenarios(self):
        data = _load_json()
        scenarios = data["scenarios"]
        assert len(scenarios) == 13, f"expected 13 scenarios, got {len(scenarios)}"

    def test_every_scenario_has_nonempty_terms(self):
        data = _load_json()
        for entry in data["scenarios"]:
            assert entry["terms"], f"{entry['scenario']}: terms must not be empty"

    def test_revenue_growth_is_present_but_not_backend_gated(self):
        data = _load_json()
        by_name = {e["scenario"]: e for e in data["scenarios"]}
        assert "revenue_growth" in by_name
        assert by_name["revenue_growth"]["backendKeywordGate"] is False, (
            "revenue_growth must stay backendKeywordGate=false — it is handled "
            "by separate compound-phrase logic in _infer_owner_action_scenario_"
            "from_message, not the flat keyword-tuple mechanism (see _meta note)"
        )

    def test_twelve_scenarios_are_backend_gated(self):
        data = _load_json()
        gated = [e for e in data["scenarios"] if e["backendKeywordGate"]]
        assert len(gated) == 12


# ============================================================
# 2. Backend loader: module-level load + fail-open
# ============================================================


class TestBackendLoader:
    def test_module_loads_keywords_from_json_not_empty(self):
        from smartbi.api.restaurant_sections import _OWNER_ACTION_KEYWORDS

        assert len(_OWNER_ACTION_KEYWORDS) == 12, (
            "expected the 12 backend-gated scenarios to be loaded at import time"
        )
        scenario_names = {s for s, _ in _OWNER_ACTION_KEYWORDS}
        assert "revenue_growth" not in scenario_names, (
            "revenue_growth must NOT be loaded into the runtime keyword tuple"
        )

    def test_fail_open_falls_back_to_hardcoded_tuple_on_missing_file(self, monkeypatch):
        from smartbi.api import restaurant_sections as mod

        monkeypatch.setattr(
            mod, "_OWNER_ACTION_SCENARIOS_JSON", Path("/nonexistent/does-not-exist.json")
        )
        result = mod._load_owner_action_keywords()
        assert result == mod._OWNER_ACTION_KEYWORDS_FALLBACK

    def test_fail_open_falls_back_on_malformed_json(self, monkeypatch, tmp_path):
        from smartbi.api import restaurant_sections as mod

        bad_file = tmp_path / "bad.json"
        bad_file.write_text("{not valid json", encoding="utf-8")
        monkeypatch.setattr(mod, "_OWNER_ACTION_SCENARIOS_JSON", bad_file)
        result = mod._load_owner_action_keywords()
        assert result == mod._OWNER_ACTION_KEYWORDS_FALLBACK

    def test_fail_open_falls_back_when_zero_gated_scenarios(self, monkeypatch, tmp_path):
        from smartbi.api import restaurant_sections as mod

        empty_file = tmp_path / "empty.json"
        empty_file.write_text(
            json.dumps({"scenarios": [{"scenario": "x", "terms": [], "backendKeywordGate": True}]}),
            encoding="utf-8",
        )
        monkeypatch.setattr(mod, "_OWNER_ACTION_SCENARIOS_JSON", empty_file)
        result = mod._load_owner_action_keywords()
        assert result == mod._OWNER_ACTION_KEYWORDS_FALLBACK


# ============================================================
# 3. Behavior regression — representative queries must route the same as
#    before A1. Empirically verified (2026-07-08) by diffing the live
#    _infer_owner_action_scenario_from_message against the pre-A1 function
#    (extracted from origin/main) over 61 queries: only 3 differed, and all
#    3 were "" (no match) -> a scenario (pure coverage gain, never a changed
#    winner). These assertions lock in that outcome.
# ============================================================


class TestInferenceRegression:
    @pytest.mark.parametrize(
        "message,expected",
        [
            # unchanged pre-existing matches (single dominant keyword)
            ("库存预警要不要马上补货", "inventory_reorder"),
            ("仓管厨师长前台分别要做什么", "operations_dispatch"),
            ("所有门店里哪家店最值得学习复制到别的店", "store_compare"),
            ("套餐怎么搭配能提高客单价", "package"),
            ("营收杠杆有哪些", "revenue_growth"),
            ("如果进店没涨怎么办", "revenue_growth"),
            ("厨房慢，服务慢，怎么办", "staff_training"),
            ("只能加一个人，加前厅还是后厨", "staffing_schedule"),
            ("今天天气不好要不要多备菜", "external_event_response"),
            ("月盘点损耗率太高怎么控制", "cost_margin"),
            # the two known cross-source conflicts: MUST keep resolving to
            # their pre-A1 backend winner, not the frontend's preferred
            # scenario (this is the crux of the zero-regression guarantee)
            ("服务员", "staff_training"),
            ("加购", "single_item_push"),
        ],
    )
    def test_representative_query_scenario_unchanged(self, message, expected):
        from smartbi.api.restaurant_sections import _infer_owner_action_scenario_from_message

        assert _infer_owner_action_scenario_from_message(message) == expected

    @pytest.mark.parametrize(
        "message,expected",
        [
            # pure coverage gains: previously matched nothing ("" / no
            # owner-action topic at all), now correctly resolve via the
            # widened union. Not a regression — nothing that worked before
            # is disturbed; these are net-new correct matches.
            ("报损", "inventory_reorder"),
            ("小红书投诉差评体验口碑", "staff_training"),
            ("拼桌座位桌数", "seating_mix"),
        ],
    )
    def test_new_coverage_from_union_is_gained_not_regressed(self, message, expected):
        from smartbi.api.restaurant_sections import _infer_owner_action_scenario_from_message

        assert _infer_owner_action_scenario_from_message(message) == expected


# ============================================================
# 4. Frontend/backend convergence guard
# ============================================================


class TestFrontendJsonConvergence:
    def test_frontend_terms_are_subset_of_shared_json(self):
        """Every (scenario, term) pair in registry.ts must also appear in the
        shared JSON under the same scenario, OR be one of the two documented
        exceptions (KNOWN_FRONTEND_ONLY_EXCEPTIONS). If this fails, someone
        added a new term to the frontend without updating the shared JSON —
        exactly the drift A1 exists to prevent.
        """
        frontend = _frontend_scenario_terms()
        data = _load_json()
        by_scenario = {e["scenario"]: set(e["terms"]) for e in data["scenarios"]}

        missing = []
        for scenario, terms in frontend.items():
            json_terms = by_scenario.get(scenario, set())
            for term in terms:
                if term in json_terms:
                    continue
                if (scenario, term) in KNOWN_FRONTEND_ONLY_EXCEPTIONS:
                    continue
                missing.append((scenario, term))

        assert not missing, (
            f"frontend registry.ts has terms not present in the shared JSON "
            f"(and not a documented exception): {missing} — add them to "
            f"backend/python/smartbi/data/owner_action_scenarios.json"
        )

    def test_known_exceptions_are_still_actually_exceptions(self):
        """Guard against the exception list going stale: if the JSON is ever
        updated to include one of these terms under the frontend's scenario,
        the exception entry should be removed (not silently kept)."""
        data = _load_json()
        by_scenario = {e["scenario"]: set(e["terms"]) for e in data["scenarios"]}
        for scenario, term in KNOWN_FRONTEND_ONLY_EXCEPTIONS:
            assert term not in by_scenario.get(scenario, set()), (
                f"{(scenario, term)} is listed as a known exception but is now "
                f"present in the JSON under that scenario — remove it from "
                f"KNOWN_FRONTEND_ONLY_EXCEPTIONS"
            )
