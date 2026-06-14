"""Unit tests for seed_chart_insight_corpus.generate_scenarios().

Tests (dry-run only — no LLM, no DB):
  1. Correct count returned.
  2. Variety: ≥3 distinct chart_type, ≥2 distinct domains.
  3. Distinct series across scenarios (input_hash differs).
  4. Every scenario has non-empty series_values + series_labels.
  5. Every scenario has data_pattern set (non-empty).
  6. Every scenario has a sentinel factory_id (SEED_R or SEED_F).
  7. series_values and series_labels have the same length.
  8. All permission_tier values are from the known set.
  9. Determinism: same seed → same first scenario.
 10. Different seeds → different first scenario.
"""
from __future__ import annotations

import os
import sys


# ---------------------------------------------------------------------------
# Path bootstrap so the test works whether run from repo root, backend/python,
# or backend/python/smartbi/tests (mirrors test_chart_insight_service.py).
# ---------------------------------------------------------------------------
_SMARTBI_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)  # → backend/python
if _SMARTBI_ROOT not in sys.path:
    sys.path.insert(0, _SMARTBI_ROOT)

# ---------------------------------------------------------------------------
# Import the generator under test
# ---------------------------------------------------------------------------
from scripts.seed_chart_insight_corpus import (  # noqa: E402,F401
    generate_scenarios,
    RANDOM_SEED,
    _SENTINEL_RESTAURANT,
    _SENTINEL_FACTORY,
    _ALL_BUCKETS,
    _DOMAINS,
)
from smartbi.services.distillation_capture import compute_input_hash  # noqa: E402
from smartbi.services.insights.chart_insight_service import _corpus_input_text  # noqa: E402

_KNOWN_TIERS = {"finance_visible", "price_hidden", "finance_hidden"}
_SENTINELS = {_SENTINEL_RESTAURANT, _SENTINEL_FACTORY}


class TestGenerateScenarios:
    """Dry-run validation of the scenario generator."""

    def test_correct_count(self):
        """generate_scenarios(50) returns exactly 50 items."""
        scenarios = generate_scenarios(50)
        assert len(scenarios) == 50

    def test_variety_chart_type(self):
        """At least 3 distinct chart_type values across 150 scenarios.

        With 120 base combos (20 buckets × 2 domains × 3 tiers) the cycle covers
        all chart types only after index 96 (when trend/LINE scenarios appear).
        150 > 120 guarantees full coverage.
        """
        scenarios = generate_scenarios(150)
        distinct = {s.chart_type for s in scenarios}
        assert len(distinct) >= 3, f"Only {len(distinct)} chart types: {distinct}"

    def test_variety_domains(self):
        """At least 2 distinct domain values across 50 scenarios."""
        scenarios = generate_scenarios(50)
        distinct = {s.domain for s in scenarios}
        assert len(distinct) >= 2, f"Only {len(distinct)} domains: {distinct}"

    def test_distinct_input_hashes(self):
        """All 50 scenarios have distinct input_hash values (no hash collision)."""
        scenarios = generate_scenarios(50)
        hashes = [compute_input_hash(_corpus_input_text(s)) for s in scenarios]
        assert len(set(hashes)) == 50, (
            f"Hash collisions detected: {50 - len(set(hashes))} duplicates"
        )

    def test_non_empty_series_values(self):
        """Every scenario has at least one series value."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            assert s.series_values, f"Scenario {i}: series_values is empty"

    def test_non_empty_series_labels(self):
        """Every scenario has at least one series label."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            assert s.series_labels, f"Scenario {i}: series_labels is empty"

    def test_data_pattern_set(self):
        """Every scenario has a non-empty data_pattern."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            assert s.data_pattern, f"Scenario {i}: data_pattern is empty"

    def test_sentinel_factory_ids(self):
        """Every scenario uses the SEED_R or SEED_F sentinel factory_id."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            assert s.factory_id in _SENTINELS, (
                f"Scenario {i}: unexpected factory_id={s.factory_id!r}"
            )

    def test_series_len_matches_labels(self):
        """series_values and series_labels have the same length in every scenario."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            assert len(s.series_values) == len(s.series_labels), (
                f"Scenario {i}: len(values)={len(s.series_values)} "
                f"!= len(labels)={len(s.series_labels)}"
            )

    def test_known_permission_tiers(self):
        """All permission_tier values are from the known set."""
        scenarios = generate_scenarios(50)
        {s.permission_tier for s in scenarios} - _KNOWN_TIERS
        # price_hidden is valid; the generator uses finance_visible + finance_hidden
        # plus any valid tier from _TIERS. Accept the full set.
        # Actually check that none are completely unknown strings.
        for i, s in enumerate(scenarios):
            assert s.permission_tier in {"finance_visible", "finance_hidden", "price_hidden"}, (
                f"Scenario {i}: unexpected permission_tier={s.permission_tier!r}"
            )

    def test_determinism_same_seed(self):
        """Same seed produces identical first scenario."""
        s1 = generate_scenarios(5, seed=RANDOM_SEED)
        s2 = generate_scenarios(5, seed=RANDOM_SEED)
        assert s1[0].series_values == s2[0].series_values
        assert s1[0].series_labels == s2[0].series_labels
        assert s1[0].data_pattern == s2[0].data_pattern

    def test_different_seeds_differ(self):
        """Different seed → different first scenario values."""
        s1 = generate_scenarios(5, seed=RANDOM_SEED)
        s2 = generate_scenarios(5, seed=RANDOM_SEED + 1)
        # Values should differ with extremely high probability
        assert s1[0].series_values != s2[0].series_values

    def test_all_families_represented(self):
        """With n=500, all 5 families appear at least once."""
        scenarios = generate_scenarios(500)
        families = {s.data_pattern.split(":")[0] for s in scenarios}
        expected = {"proportion", "ranking", "comparison", "kpi", "trend"}
        assert families == expected, f"Missing families: {expected - families}"

    def test_both_domains_represented(self):
        """With n=50, both restaurant and factory appear."""
        scenarios = generate_scenarios(50)
        domains = {s.domain for s in scenarios}
        assert "restaurant" in domains
        assert "factory" in domains

    def test_series_values_are_positive(self):
        """All generated series values must be positive (no zero/negative amounts)."""
        scenarios = generate_scenarios(50)
        for i, s in enumerate(scenarios):
            for j, v in enumerate(s.series_values):
                assert v > 0, (
                    f"Scenario {i} label={s.series_labels[j]!r}: "
                    f"non-positive value {v}"
                )
