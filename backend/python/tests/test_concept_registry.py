"""CI validator for docs/concepts/restaurant.yaml (business concept registry, A0).

Spec: docs/superpowers/specs/2026-07-08-business-concept-registry-direction.md
(§3 推荐路径, A0 步骤).

Structure of the registry: each concept maps to up to four vocabulary
sources — java_intent (ai_intent_configs.intent_code, Flyway-seeded),
python_ops (smartbi.gold.restaurant_ops_router.SAMPLE_QUERIES key),
materialized (materialized_analytics template codes), owner_action
(web-admin restaurantOwnerActionRegistry.ts scenario). Any of these may be
``null``/``[]`` — a registered gap is a valid, useful finding (spec §3 A0).

This test file asserts three properties:

1. Structural validity — every concept has a mapping shape and at least one
   non-null binding (a concept registering nothing is dead weight).
2. Forward links resolve — a registered python_ops/materialized/owner_action
   code must actually exist in its source of truth. This catches dead links
   (e.g. a concept pointing at a renamed/removed code).
3. Reverse coverage (the actual point of A0) — every RESTAURANT_OPS_* code
   in SAMPLE_QUERIES must be registered by some concept. This is the CI gate
   that would have caught the class of drift behind the 2026-07-07 Stage-8
   LLM routing collision described in spec §1 (Java RESTAURANT_REVENUE_TREND
   and Python RESTAURANT_OPS_TREND_ANALYSIS being semantically identical but
   mutually unaware, because neither was registered anywhere shared).

java_intent (Java Flyway intent_code) is intentionally NOT existence-checked
against the SQL migrations — grepping Flyway SQL from a Python test is
fragile (migration filenames/format drift) and cross-language (per spec §3
A0 recommendation); only format is validated for that column.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[3]
YAML_PATH = REPO_ROOT / "docs" / "concepts" / "restaurant.yaml"
OWNER_ACTION_REGISTRY_TS = (
    REPO_ROOT / "web-admin" / "src" / "views" / "smart-bi" / "restaurantOwnerActionRegistry.ts"
)


def _load_registry() -> dict[str, dict[str, Any]]:
    assert YAML_PATH.exists(), f"concept registry missing: {YAML_PATH}"
    with YAML_PATH.open(encoding="utf-8") as fh:
        data = yaml.safe_load(fh)
    assert isinstance(data, dict) and data, "registry YAML must be a non-empty mapping"
    return data


def _sample_query_codes() -> set[str]:
    from smartbi.gold.restaurant_ops_router import SAMPLE_QUERIES

    return set(SAMPLE_QUERIES.keys())


def _materialized_template_codes() -> set[str]:
    from smartbi.services.materialized_analytics.templates.registry import (
        get_registry,
        load_all_templates,
    )

    load_all_templates()
    return set(get_registry().codes())


def _owner_action_scenarios() -> set[str]:
    assert OWNER_ACTION_REGISTRY_TS.exists(), (
        f"owner-action registry.ts missing: {OWNER_ACTION_REGISTRY_TS}"
    )
    text = OWNER_ACTION_REGISTRY_TS.read_text(encoding="utf-8")
    match = re.search(r"RESTAURANT_OWNER_ACTION_SCENARIOS\s*=\s*\[(.*?)\]\s*as const", text, re.S)
    assert match, "could not locate RESTAURANT_OWNER_ACTION_SCENARIOS array in registry.ts"
    return set(re.findall(r"'([a-z_]+)'", match.group(1)))


# ============================================================
# 1. Structural validity
# ============================================================


class TestConceptRegistryStructure:
    def test_registry_loads_and_has_expected_minimum_coverage(self):
        data = _load_registry()
        # spec §3 A0: "登记餐饮域 ~15 个高频概念"
        assert len(data) >= 15, (
            f"expected >=15 restaurant concepts registered per spec §3 A0, got {len(data)}"
        )

    def test_every_concept_has_at_least_one_binding(self):
        data = _load_registry()
        for concept_id, entry in data.items():
            assert isinstance(entry, dict), f"{concept_id}: entry must be a mapping"
            bindings = [
                entry.get("java_intent"),
                entry.get("python_ops"),
                entry.get("materialized") or None,
                entry.get("owner_action"),
            ]
            assert any(bindings), (
                f"{concept_id}: has zero non-null bindings across all four "
                f"vocabularies — registering nothing is not a useful entry"
            )

    def test_concept_ids_are_upper_snake_case(self):
        data = _load_registry()
        for concept_id in data:
            assert re.fullmatch(r"[A-Z][A-Z0-9_]*", concept_id), (
                f"bad concept id (must be UPPER_SNAKE_CASE): {concept_id!r}"
            )

    def test_materialized_field_is_always_a_list(self):
        data = _load_registry()
        for concept_id, entry in data.items():
            materialized = entry.get("materialized")
            assert isinstance(materialized, list), (
                f"{concept_id}: materialized must be a list (use [] for none), "
                f"got {type(materialized).__name__}"
            )


# ============================================================
# 2. Forward links resolve (registered code must really exist)
# ============================================================


class TestForwardLinksResolve:
    def test_python_ops_codes_exist_in_sample_queries(self):
        data = _load_registry()
        valid = _sample_query_codes()
        for concept_id, entry in data.items():
            code = entry.get("python_ops")
            if code is None:
                continue
            assert code in valid, (
                f"{concept_id}: python_ops={code!r} not found in "
                f"smartbi.gold.restaurant_ops_router.SAMPLE_QUERIES — dead link"
            )

    def test_materialized_codes_exist_in_template_registry(self):
        data = _load_registry()
        valid = _materialized_template_codes()
        for concept_id, entry in data.items():
            for code in entry.get("materialized") or []:
                assert code in valid, (
                    f"{concept_id}: materialized code={code!r} not found in "
                    f"materialized_analytics templates registry — dead link"
                )

    def test_owner_action_values_are_known_scenarios(self):
        data = _load_registry()
        valid = _owner_action_scenarios()
        assert len(valid) == 13, (
            f"expected 13 owner-action scenarios in registry.ts, found {len(valid)}: {sorted(valid)}"
        )
        for concept_id, entry in data.items():
            scenario = entry.get("owner_action")
            if scenario is None:
                continue
            assert scenario in valid, (
                f"{concept_id}: owner_action={scenario!r} not a known scenario "
                f"in restaurantOwnerActionRegistry.ts"
            )

    def test_java_intent_is_format_only_not_existence_checked(self):
        """Per spec §3 A0: Java intent_code lives in Flyway SQL (DB-seeded,
        cross-language). Grepping SQL from a Python test is fragile/expensive
        for low marginal value; only format-validate this column."""
        data = _load_registry()
        for concept_id, entry in data.items():
            code = entry.get("java_intent")
            if code is None:
                continue
            assert re.fullmatch(r"[A-Z][A-Z0-9_]*", code), (
                f"{concept_id}: bad java_intent format: {code!r}"
            )


# ============================================================
# 3. Reverse coverage — the actual point of A0
# ============================================================


class TestReverseCoverage:
    def test_all_sample_query_codes_are_registered(self):
        """Every RESTAURANT_OPS_* code must be registered in restaurant.yaml.

        This is the CI gate that prevents the exact drift class in spec §1:
        a new Python ops code ships without ever being cross-referenced
        against the Java intent layer, so a later LLM routing decision picks
        the Java code instead and silently drops the Python-only behavior
        (window/pnl slots) that Phase 2 delegation gate has to patch around
        at runtime. Registering here makes the gap visible before ship.
        """
        data = _load_registry()
        registered_python_ops = {
            entry.get("python_ops") for entry in data.values() if entry.get("python_ops")
        }
        actual = _sample_query_codes()
        missing = actual - registered_python_ops
        assert not missing, (
            f"RESTAURANT_OPS_* codes present in SAMPLE_QUERIES but NOT registered in "
            f"docs/concepts/restaurant.yaml: {sorted(missing)} — add an entry "
            f"(see spec 2026-07-08-business-concept-registry-direction.md §3 A0)"
        )
