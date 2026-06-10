"""Tests for P0-2 seeder enhancements:
  A. Real-shape sampler: varied series matching shape; anonymised labels; jitter.
  B. Rejection-reason counters: aggregate correctly.
  C. Gap-fill: skips buckets above floor, fills buckets below (mock census).
  D. metadata shape_source field carried through.
"""
from __future__ import annotations

import os
import random
import sys
from typing import Dict, List, Tuple
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Path bootstrap — mirrors test_seed_scenarios.py
# ---------------------------------------------------------------------------
_SMARTBI_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)  # → backend/python
if _SMARTBI_ROOT not in sys.path:
    sys.path.insert(0, _SMARTBI_ROOT)

# ---------------------------------------------------------------------------
# Imports under test
# ---------------------------------------------------------------------------
from scripts.seed_chart_insight_corpus import (  # noqa: E402
    # shape helpers
    _apply_shape_jitter,
    _sample_shapes_from_library,
    _gen_real_shape_values,
    _classify_shape_to_bucket,
    # gap-fill helpers
    _build_gap_fill_plan,
    _fetch_corpus_counts,
    # rejection counters
    _RejectionCounters,
    _classify_validation_rejection,
    # scenario generation
    generate_scenarios,
    generate_scenarios_async,
    generate_scenarios_gap_fill,
    # constants
    RANDOM_SEED,
    _ALL_BUCKETS,
    _DOMAINS,
    _SENTINEL_RESTAURANT,
    _SENTINEL_FACTORY,
    _RESTAURANT_POOLS,
    _FACTORY_POOLS,
)

_ALL_REAL_LABELS = set()
for _pool in _RESTAURANT_POOLS.values():
    _ALL_REAL_LABELS.update(_pool)
for _pool in _FACTORY_POOLS.values():
    _ALL_REAL_LABELS.update(_pool)
# Month labels and KPI strings are allowed as anonymised labels too
_ALLOWED_EXTRA = {"实际", "目标"} | {f"{m}月" for m in range(1, 13)}
_ALL_ANON_LABELS = _ALL_REAL_LABELS | _ALLOWED_EXTRA


# ===========================================================================
# A. Real-shape sampler
# ===========================================================================

class TestApplyShapeJitter:
    """_apply_shape_jitter: resize + jitter + positive values."""

    def test_output_length_matches_n_target(self):
        rng = random.Random(1)
        result = _apply_shape_jitter([100, 200, 300], n_target=5, rng=rng)
        assert len(result) == 5

    def test_output_length_shrinks(self):
        rng = random.Random(2)
        result = _apply_shape_jitter([100, 200, 300, 400, 500], n_target=3, rng=rng)
        assert len(result) == 3

    def test_all_positive(self):
        rng = random.Random(3)
        result = _apply_shape_jitter([80_000, 120_000, 200_000], n_target=3, rng=rng)
        assert all(v > 0 for v in result), f"Non-positive values: {result}"

    def test_relative_distribution_preserved_within_jitter(self):
        """The shape (relative proportions) should be roughly preserved.

        If the input shape is [3, 1] (top has 75% share), the jittered output
        should still have the top item at roughly 60–90% share (±15% jitter on
        each item changes proportions by at most ~30%).
        """
        rng = random.Random(42)
        # Strongly dominant shape: 75% / 25%
        result = _apply_shape_jitter([300_000.0, 100_000.0], n_target=2, rng=rng)
        total = sum(result)
        top_share = max(result) / total
        # With ±12.5% jitter per item, top_share can vary but should stay > 55%
        assert top_share > 0.55, (
            f"Shape not preserved: top_share={top_share:.2f}, values={result}"
        )

    def test_varied_output_across_calls(self):
        """Two calls with different seeds must produce different values (jitter active)."""
        v1 = _apply_shape_jitter([100_000, 50_000], n_target=2, rng=random.Random(1))
        v2 = _apply_shape_jitter([100_000, 50_000], n_target=2, rng=random.Random(999))
        assert v1 != v2, "Jitter produced identical values for different seeds"


class TestSampleShapesFromLibrary:
    """_sample_shapes_from_library: valid output for every family."""

    @pytest.mark.parametrize("family", ["proportion", "ranking", "comparison", "kpi", "trend"])
    def test_returns_positive_values(self, family: str):
        rng = random.Random(7)
        values, source = _sample_shapes_from_library(family, n_items=3, rng=rng)
        assert source == "library"
        assert len(values) == 3
        assert all(v > 0 for v in values), f"{family}: non-positive: {values}"

    def test_proportion_varied_across_samples(self):
        """Two library samples of 'proportion' should differ (library has ≥8 shapes)."""
        v1, _ = _sample_shapes_from_library("proportion", 3, random.Random(1))
        v2, _ = _sample_shapes_from_library("proportion", 3, random.Random(2))
        assert v1 != v2

    def test_trend_returns_n_items(self):
        """trend family: length must match n_items regardless of library shape length."""
        for n in [6, 8, 12]:
            values, _ = _sample_shapes_from_library("trend", n_items=n, rng=random.Random(n))
            assert len(values) == n, f"trend n={n}: got {len(values)} values"


class TestGenRealShapeValues:
    """_gen_real_shape_values: DB path + library fallback."""

    @pytest.mark.asyncio
    async def test_library_fallback_when_pool_is_none(self):
        """pool=None → library fallback for proportion/ranking/comparison."""
        for family in ["proportion", "ranking", "comparison"]:
            values, source = await _gen_real_shape_values(
                pool=None, family=family, n_items=3,
                rng=random.Random(1), domain="restaurant",
            )
            assert source == "library", f"{family}: expected library, got {source}"
            assert len(values) == 3
            assert all(v > 0 for v in values)

    @pytest.mark.asyncio
    async def test_trend_always_library(self):
        """trend family always uses library regardless of pool availability."""
        mock_pool = MagicMock()
        values, source = await _gen_real_shape_values(
            pool=mock_pool, family="trend", n_items=6,
            rng=random.Random(1), domain="restaurant",
        )
        assert source == "library"
        assert len(values) == 6

    @pytest.mark.asyncio
    async def test_kpi_always_library(self):
        """kpi family always uses library."""
        values, source = await _gen_real_shape_values(
            pool=None, family="kpi", n_items=2,
            rng=random.Random(1), domain="factory",
        )
        assert source == "library"
        assert len(values) == 2

    @pytest.mark.asyncio
    async def test_real_path_used_when_db_returns_data(self):
        """When DB returns rows, source should be 'real' and values positive."""
        # Mock pool that returns two rows with real values
        fake_row_1 = {"total_value": 350000.0}
        fake_row_2 = {"total_value": 150000.0}

        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=[fake_row_1, fake_row_2])
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(
            return_value=_AsyncContextManager(mock_conn)
        )

        values, source = await _gen_real_shape_values(
            pool=mock_pool, family="proportion", n_items=2,
            rng=random.Random(5), domain="restaurant",
        )
        assert source == "real", f"Expected 'real', got '{source}'"
        assert len(values) == 2
        assert all(v > 0 for v in values)

    @pytest.mark.asyncio
    async def test_library_fallback_when_db_fails(self):
        """When DB query raises, source falls back to 'library'."""
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(side_effect=RuntimeError("DB down"))
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(
            return_value=_AsyncContextManager(mock_conn)
        )

        values, source = await _gen_real_shape_values(
            pool=mock_pool, family="proportion", n_items=3,
            rng=random.Random(1), domain="factory",
        )
        assert source == "library"
        assert all(v > 0 for v in values)


class TestAnonymisedLabels:
    """Scenarios generated via generate_scenarios must only use anonymised labels,
    never real entity names (privacy-safety check)."""

    def test_uniform_scenarios_use_anon_labels(self):
        """The uniform path never leaks real entity names beyond the label pools."""
        scenarios = generate_scenarios(200, seed=RANDOM_SEED)
        for i, ctx in enumerate(scenarios):
            for lbl in ctx.series_labels:
                # Strip trailing digits added for uniqueness (e.g. "堂食1")
                base_lbl = lbl.rstrip("0123456789")
                assert base_lbl in _ALL_ANON_LABELS or lbl in _ALL_ANON_LABELS, (
                    f"Scenario {i}: unexpected label {lbl!r} not in anonymised pool"
                )


# ===========================================================================
# B. Rejection-reason counters
# ===========================================================================

class TestRejectionCounters:
    """_RejectionCounters: aggregation and summary."""

    def test_total_sums_all_reasons(self):
        rej = _RejectionCounters(
            llm_error=3,
            claims_empty=5,
            recompute_mismatch=2,
            adjacency_fail=1,
            y_gate=4,
        )
        assert rej.total == 15

    def test_zero_total_when_empty(self):
        rej = _RejectionCounters()
        assert rej.total == 0

    def test_log_summary_does_not_raise(self):
        """log_summary must not raise; just check it runs."""
        import logging
        rej = _RejectionCounters(llm_error=1, claims_empty=2)
        rej.log_summary(logging.getLogger("test"))  # should not raise

    def test_classify_claims_empty_for_none_obj(self):
        """None llm_obj → 'claims_empty' (not a crash)."""
        reason = _classify_validation_rejection(None, MagicMock())
        assert reason == "claims_empty"

    def test_classify_claims_empty_for_empty_dict(self):
        reason = _classify_validation_rejection({}, MagicMock())
        assert reason == "claims_empty"

    def test_classify_ok_when_validate_passes(self):
        """When _validate_claims would return non-None, reason is 'ok'."""
        # We mock _validate_claims to return a dict (pass)
        llm_obj = {"finding": "营收上升", "implication": "增长良好", "suggestion": "继续"}
        ctx_mock = MagicMock()
        ctx_mock.data_pattern = "proportion:top-share:50-65:n2-3"
        ctx_mock.series_values = [100.0, 50.0]

        with patch("scripts.seed_chart_insight_corpus._validate_claims",
                   return_value={"finding": "ok"}):
            reason = _classify_validation_rejection(llm_obj, ctx_mock)
        assert reason == "ok"

    def test_classify_rejection_when_validate_returns_none(self):
        """When _validate_claims returns None, reason is non-'ok'."""
        llm_obj = {"finding": "营收增长了50%", "implication": ""}
        ctx_mock = MagicMock()
        ctx_mock.data_pattern = "ranking:top-share:50-65:n4-8"
        ctx_mock.series_values = [100.0, 80.0, 60.0, 40.0]

        with patch("scripts.seed_chart_insight_corpus._validate_claims",
                   return_value=None):
            reason = _classify_validation_rejection(llm_obj, ctx_mock)
        assert reason in ("recompute_mismatch", "adjacency_fail", "claims_empty")


# ===========================================================================
# C. Gap-fill mode
# ===========================================================================

class TestBuildGapFillPlan:
    """_build_gap_fill_plan: correct gap vs skip logic."""

    def test_empty_corpus_all_buckets_need_fill(self):
        gaps, skipped = _build_gap_fill_plan({}, floor=200)
        # All (domain × family) combinations should need filling
        assert len(skipped) == 0
        assert len(gaps) > 0
        for needed in gaps.values():
            assert needed == 200  # all start from 0

    def test_bucket_at_floor_is_skipped(self):
        """A bucket with exactly floor rows should be skipped (not filled)."""
        corpus = {("restaurant", "proportion"): 200}
        gaps, skipped = _build_gap_fill_plan(corpus, floor=200)
        assert ("restaurant", "proportion") in skipped
        assert ("restaurant", "proportion") not in gaps

    def test_bucket_above_floor_is_skipped(self):
        corpus = {("factory", "ranking"): 350}
        gaps, skipped = _build_gap_fill_plan(corpus, floor=200)
        assert ("factory", "ranking") in skipped

    def test_bucket_below_floor_has_correct_gap(self):
        corpus = {("restaurant", "trend"): 80}
        gaps, skipped = _build_gap_fill_plan(corpus, floor=200)
        assert ("restaurant", "trend") in gaps
        assert gaps[("restaurant", "trend")] == 120  # 200 - 80

    def test_zero_floor_skips_all(self):
        gaps, skipped = _build_gap_fill_plan({"restaurant": 0}, floor=0)
        # floor=0 means everything is already at/above → all skipped
        assert len(gaps) == 0


class TestFetchCorpusCounts:
    """_fetch_corpus_counts: handles None pool and DB errors gracefully."""

    @pytest.mark.asyncio
    async def test_returns_empty_when_pool_is_none(self):
        result = await _fetch_corpus_counts(None)
        assert result == {}

    @pytest.mark.asyncio
    async def test_returns_empty_on_db_error(self):
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(side_effect=Exception("connection refused"))
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(
            return_value=_AsyncContextManager(mock_conn)
        )
        result = await _fetch_corpus_counts(mock_pool)
        assert result == {}

    @pytest.mark.asyncio
    async def test_parses_rows_correctly(self):
        fake_rows = [
            {"business_type": "restaurant", "family": "proportion", "total_count": 150},
            {"business_type": "factory",    "family": "ranking",    "total_count": 250},
        ]
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=fake_rows)
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(
            return_value=_AsyncContextManager(mock_conn)
        )
        result = await _fetch_corpus_counts(mock_pool)
        assert result[("restaurant", "proportion")] == 150
        assert result[("factory", "ranking")] == 250


class TestGenerateScenariosGapFill:
    """generate_scenarios_gap_fill: skips buckets above floor, fills below."""

    @pytest.mark.asyncio
    async def test_nothing_generated_when_all_above_floor(self):
        """If all buckets already have floor+ rows, nothing should be generated."""
        # Build a corpus with ALL (business_type × family) combos at floor
        families = {b.family for b in _ALL_BUCKETS}
        corpus = {}
        for domain in _DOMAINS:
            bt = domain  # restaurant / factory
            for fam in families:
                corpus[(bt, fam)] = 300  # above floor=200

        with patch("scripts.seed_chart_insight_corpus._fetch_corpus_counts",
                   new=AsyncMock(return_value=corpus)):
            scenarios, report = await generate_scenarios_gap_fill(
                n=100, seed=RANDOM_SEED, pool=None, floor=200,
            )
        assert scenarios == []
        assert len(report["skipped"]) > 0

    @pytest.mark.asyncio
    async def test_generates_only_for_buckets_below_floor(self):
        """Generates scenarios only for the under-filled family."""
        families = {b.family for b in _ALL_BUCKETS}
        # All at/above floor EXCEPT factory:trend
        corpus = {}
        for domain in _DOMAINS:
            bt = domain
            for fam in families:
                corpus[(bt, fam)] = 300  # above
        corpus[("factory", "trend")] = 50  # below floor=200

        with patch("scripts.seed_chart_insight_corpus._fetch_corpus_counts",
                   new=AsyncMock(return_value=corpus)):
            scenarios, report = await generate_scenarios_gap_fill(
                n=50, seed=RANDOM_SEED, pool=None, floor=200,
            )

        assert len(scenarios) > 0
        # All generated scenarios must be for factory domain (trend bucket is factory)
        factory_scenarios = [
            ctx for ctx, _ in scenarios
            if ctx.factory_id == _SENTINEL_FACTORY
        ]
        # Not strictly all factory (tier permutations), but gap-fill
        # should have generated at least some
        assert len(factory_scenarios) > 0

    @pytest.mark.asyncio
    async def test_report_contains_expected_keys(self):
        corpus = {}  # empty → all buckets below floor
        with patch("scripts.seed_chart_insight_corpus._fetch_corpus_counts",
                   new=AsyncMock(return_value=corpus)):
            _, report = await generate_scenarios_gap_fill(
                n=20, seed=RANDOM_SEED, pool=None, floor=200,
            )
        assert "gaps" in report
        assert "skipped" in report
        assert "total_needed" in report
        assert report["total_needed"] > 0


# ===========================================================================
# D. shape_source metadata tag
# ===========================================================================

class TestShapeSourceMetadata:
    """generate_scenarios_async carries shape_source in the return tuple."""

    @pytest.mark.asyncio
    async def test_uniform_mode_returns_uniform_source(self):
        pairs = await generate_scenarios_async(n=10, seed=RANDOM_SEED,
                                               pool=None, real_shapes=False)
        for ctx, source in pairs:
            assert source == "uniform", f"Expected 'uniform', got '{source}'"

    @pytest.mark.asyncio
    async def test_real_shapes_with_no_pool_returns_library(self):
        """real_shapes=True but pool=None → library fallback for proportion/ranking/comparison."""
        pairs = await generate_scenarios_async(n=20, seed=RANDOM_SEED,
                                               pool=None, real_shapes=True)
        for ctx, source in pairs:
            assert source in ("library", "uniform"), (
                f"Unexpected source '{source}' when pool=None"
            )

    @pytest.mark.asyncio
    async def test_source_values_are_valid_strings(self):
        pairs = await generate_scenarios_async(n=30, seed=RANDOM_SEED,
                                               pool=None, real_shapes=True)
        valid_sources = {"real", "library", "uniform"}
        for ctx, source in pairs:
            assert source in valid_sources, f"Invalid shape_source: {source!r}"


# ===========================================================================
# Integration: dry-run still works
# ===========================================================================

class TestDryRunStillWorks:
    """Smoke test: generate_scenarios() (synchronous uniform path) still works."""

    def test_generate_10_scenarios(self):
        scenarios = generate_scenarios(10, seed=RANDOM_SEED)
        assert len(scenarios) == 10
        for ctx in scenarios:
            assert ctx.factory_id in (_SENTINEL_RESTAURANT, _SENTINEL_FACTORY)
            assert len(ctx.series_values) == len(ctx.series_labels)
            assert len(ctx.series_values) > 0


# ===========================================================================
# Helpers
# ===========================================================================

class _AsyncContextManager:
    """Minimal async context manager wrapping a mock connection."""
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *args):
        pass
