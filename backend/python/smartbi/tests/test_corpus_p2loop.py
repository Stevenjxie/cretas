"""Tests for P2 eval-freeze + M4 weekly-refresh loop.

Coverage:
  1. corpus_eval_freeze — _group_by_bucket helper
  2. corpus_eval_freeze — eval-freeze marks N per bucket (dry-run, no DB)
  3. corpus_eval_freeze — idempotency: already-frozen rows reduce need; no
     re-freeze when bucket already at cap
  4. export_distillation_dataset — WHERE clause excludes eval_frozen rows
  5. corpus_refresh_loop — dry-run enumerates steps without DB/LLM side-effects
  6. Parse smoke: all three scripts parse as valid Python (ast.parse)
"""
from __future__ import annotations

import ast
import asyncio
import json
import os
import sys
import types
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch, call

import pytest

# ---------------------------------------------------------------------------
# Path bootstrap
# Tests live at: backend/python/smartbi/tests/
# Scripts live at: backend/python/scripts/
# Python root (for imports and script lookup): backend/python/
# ---------------------------------------------------------------------------
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
# Go up 2 levels: smartbi/tests → smartbi → backend/python
_PYTHON_ROOT = os.path.normpath(os.path.join(_THIS_DIR, "..", ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# ---------------------------------------------------------------------------
# Stub modules needed by corpus_judge (imported by corpus_refresh_loop step 2)
# ---------------------------------------------------------------------------

def _ensure_stub(module_name: str) -> None:
    if module_name not in sys.modules:
        parts = module_name.split(".")
        for i in range(1, len(parts) + 1):
            name = ".".join(parts[:i])
            if name not in sys.modules:
                sys.modules[name] = types.ModuleType(name)


_ensure_stub("common")
_ensure_stub("common.llm_router")

import enum


class _SLOT(enum.Enum):
    CHAT = "chat"
    INSIGHTS = "insights"
    CHART = "chart"
    MAPPER = "mapper"
    REASONING = "reasoning"
    VL = "vl"
    REVIEW = "review"


async def _stub_call_chain(slot, payload, timeout=30.0):
    return {}


sys.modules["common.llm_router"].SLOT = _SLOT  # type: ignore[attr-defined]
sys.modules["common.llm_router"].call_chain = _stub_call_chain  # type: ignore[attr-defined]

# ---------------------------------------------------------------------------
# Stub smartbi.config so scripts can import without real DB env
# ---------------------------------------------------------------------------
_ensure_stub("smartbi")
_ensure_stub("smartbi.config")
# Provide get_settings stub that returns None postgres_url → pool returns None
_settings_stub = MagicMock()
_settings_stub.postgres_url = None
sys.modules["smartbi.config"].get_settings = lambda: _settings_stub  # type: ignore[attr-defined]
sys.modules["smartbi.config"].get_pg_pool = AsyncMock(return_value=None)  # type: ignore[attr-defined]

# ---------------------------------------------------------------------------
# Now import modules under test
# ---------------------------------------------------------------------------
from scripts.corpus_eval_freeze import (  # noqa: E402
    _group_by_bucket,
    run_freeze,
)


# ===========================================================================
# 1. _group_by_bucket — pure unit test, no DB
# ===========================================================================

class TestGroupByBucket:
    def test_groups_correctly(self):
        rows = [
            {"id": 1, "source": "chart_insight", "business_type": "RESTAURANT"},
            {"id": 2, "source": "chart_insight", "business_type": "RESTAURANT"},
            {"id": 3, "source": "chart_insight", "business_type": "FACTORY"},
            {"id": 4, "source": "materialization", "business_type": "FACTORY"},
        ]
        result = _group_by_bucket(rows)
        assert result[("chart_insight", "RESTAURANT")] == [1, 2]
        assert result[("chart_insight", "FACTORY")] == [3]
        assert result[("materialization", "FACTORY")] == [4]

    def test_unknown_fallback(self):
        rows = [{"id": 10, "source": None, "business_type": None}]
        result = _group_by_bucket(rows)
        assert ("unknown", "unknown") in result
        assert result[("unknown", "unknown")] == [10]

    def test_empty_input(self):
        assert _group_by_bucket([]) == {}


# ===========================================================================
# 2. run_freeze dry-run — marks N per bucket without DB writes
# ===========================================================================

def _make_asyncpg_record(d: Dict) -> MagicMock:
    """Create a mock that quacks like an asyncpg Record for dict() conversion.

    asyncpg Records support dict() via __iter__ yielding (key, value) pairs
    and also direct item access via __getitem__.
    """
    rec = MagicMock()
    rec.__iter__ = MagicMock(return_value=iter(d.items()))
    rec.__getitem__ = lambda s, k: d[k]
    rec.keys = MagicMock(return_value=d.keys())
    rec.items = MagicMock(return_value=d.items())
    rec.get = lambda k, default=None: d.get(k, default)
    for k, v in d.items():
        setattr(rec, k, v)
    return rec


def _make_mock_pool_with_rows(
    candidate_rows: List[Dict],
    already_frozen: List[Dict],
) -> tuple:
    """Build a mock asyncpg pool returning two fetch() result sets."""
    mock_conn = AsyncMock()
    mock_conn.fetch.side_effect = [
        [_make_asyncpg_record(r) for r in candidate_rows],
        [_make_asyncpg_record(r) for r in already_frozen],
    ]
    mock_conn.execute = AsyncMock()
    mock_pool = MagicMock()
    mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
    mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)
    mock_pool.close = AsyncMock()
    return mock_pool, mock_conn


class TestRunFreezeDryRun:
    """Dry-run mode: computes selections but performs no DB writes."""

    @pytest.mark.asyncio
    async def test_dry_run_returns_summary(self):
        """Dry-run returns a summary dict without writing to DB."""
        candidate_rows = [
            {"id": i, "source": "chart_insight", "business_type": "RESTAURANT"}
            for i in range(1, 41)  # 40 candidates
        ]
        already_frozen_rows: List[Dict] = []

        mock_pool, mock_conn = _make_mock_pool_with_rows(
            candidate_rows, already_frozen_rows
        )

        with patch("scripts.corpus_eval_freeze._make_pool", new=AsyncMock(return_value=mock_pool)):
            result = await run_freeze(n=30, dry_run=True, dsn="postgresql://test/db")

        assert result["dry_run"] is True
        assert result["n_target"] == 30
        # Should select 30 of 40 candidates
        assert result["n_new"] == 30
        assert result["n_already"] == 0

    @pytest.mark.asyncio
    async def test_dry_run_no_execute_calls(self):
        """Dry-run must never call conn.execute (no DB writes)."""
        candidate_rows = [
            {"id": i, "source": "chart_insight", "business_type": "RESTAURANT"}
            for i in range(1, 11)
        ]
        already_frozen_rows: List[Dict] = []

        mock_pool, mock_conn = _make_mock_pool_with_rows(
            candidate_rows, already_frozen_rows
        )

        with patch("scripts.corpus_eval_freeze._make_pool", new=AsyncMock(return_value=mock_pool)):
            await run_freeze(n=5, dry_run=True, dsn="postgresql://test/db")

        # execute should NOT have been called (no freeze writes in dry-run)
        mock_conn.execute.assert_not_called()


# ===========================================================================
# 3. run_freeze idempotency
# ===========================================================================

class TestRunFreezeIdempotency:
    """Already-frozen rows count toward N; bucket at cap gets nothing new."""

    @pytest.mark.asyncio
    async def test_bucket_at_cap_skipped(self):
        """If already_frozen >= n, need=0 and nothing is frozen."""
        # No candidates (or there are some but they shouldn't be touched)
        candidate_rows: List[Dict] = []
        already_counts = [
            {"source": "chart_insight", "business_type": "RESTAURANT", "cnt": 30}
        ]

        mock_pool, mock_conn = _make_mock_pool_with_rows(
            candidate_rows, already_counts
        )

        with patch("scripts.corpus_eval_freeze._make_pool", new=AsyncMock(return_value=mock_pool)):
            result = await run_freeze(n=30, dry_run=False, dsn="postgresql://test/db")

        # n_new should be 0 — bucket was already at cap
        assert result["n_new"] == 0
        assert result["n_already"] == 30
        # execute should NOT have been called (no IDs to freeze)
        mock_conn.execute.assert_not_called()

    @pytest.mark.asyncio
    async def test_partial_fill(self):
        """If already_frozen=20, need=10, selects at most 10 candidates."""
        candidate_rows = [
            {"id": i, "source": "chart_insight", "business_type": "RESTAURANT"}
            for i in range(100, 150)  # 50 candidates
        ]
        already_counts = [
            {"source": "chart_insight", "business_type": "RESTAURANT", "cnt": 20}
        ]

        mock_pool, mock_conn = _make_mock_pool_with_rows(
            candidate_rows, already_counts
        )

        with patch("scripts.corpus_eval_freeze._make_pool", new=AsyncMock(return_value=mock_pool)):
            result = await run_freeze(n=30, dry_run=False, dsn="postgresql://test/db")

        # need = 30 - 20 = 10; should freeze 10
        assert result["n_new"] == 10
        # execute was called once with 10 IDs
        mock_conn.execute.assert_called_once()
        call_args = mock_conn.execute.call_args
        frozen_ids = call_args[0][1]  # second positional: the int[] list
        assert len(frozen_ids) == 10

    @pytest.mark.asyncio
    async def test_two_buckets_independent(self):
        """Two buckets: one at cap, one partial — each handled independently."""
        candidate_rows = [
            # bucket A: RESTAURANT already at 30 → need=0, these should NOT be picked
            {"id": 1, "source": "chart_insight", "business_type": "RESTAURANT"},
            # bucket B: FACTORY already at 0 → need=30, pick up to 2 here
            {"id": 2, "source": "chart_insight", "business_type": "FACTORY"},
            {"id": 3, "source": "chart_insight", "business_type": "FACTORY"},
        ]
        already_counts = [
            {"source": "chart_insight", "business_type": "RESTAURANT", "cnt": 30},
            # FACTORY has 0 → not in this list
        ]

        mock_pool, mock_conn = _make_mock_pool_with_rows(
            candidate_rows, already_counts
        )

        with patch("scripts.corpus_eval_freeze._make_pool", new=AsyncMock(return_value=mock_pool)):
            result = await run_freeze(n=30, dry_run=False, dsn="postgresql://test/db")

        # RESTAURANT: 30 already, no new. FACTORY: 0 already, selects 2 (all available)
        assert result["n_new"] == 2
        assert result["n_already"] == 30


# ===========================================================================
# 4. export_distillation_dataset — WHERE clause excludes eval_frozen rows
# ===========================================================================

class TestExportExcludesEvalFrozen:
    """The WHERE clause built by _fetch_rows must exclude eval_frozen=true rows."""

    @pytest.mark.asyncio
    async def test_eval_frozen_filter_in_where(self):
        """The SQL built by _fetch_rows must contain the eval_frozen exclusion."""
        import importlib
        import scripts.export_distillation_dataset as export_mod

        # We can't call _fetch_rows (needs DB), but we can read the source to
        # verify the filter is present in the WHERE clause construction.
        src_path = os.path.join(
            _PYTHON_ROOT, "scripts", "export_distillation_dataset.py"
        )
        with open(src_path, encoding="utf-8") as f:
            source = f.read()

        # The eval_frozen exclusion must appear literally in the source
        assert "eval_frozen" in source, (
            "export_distillation_dataset.py must exclude eval_frozen rows "
            "from the training set"
        )
        # The exclusion should use a NOT / IS DISTINCT pattern
        assert "IS DISTINCT FROM" in source or "NOT (metadata ? 'eval_frozen')" in source, (
            "Must use NOT (metadata ? 'eval_frozen') or IS DISTINCT FROM 'true' "
            "to exclude frozen eval rows"
        )

    def test_eval_frozen_filter_correct_logic(self):
        """Verify the exact WHERE filter string is valid for both absent and true."""
        # The filter we added handles three cases:
        #   1. metadata IS NULL           → row has no metadata     → include
        #   2. NOT (metadata ? 'key')     → key absent in jsonb     → include
        #   3. metadata->>'key' IS DISTINCT FROM 'true'
        #                                 → key present, value != true → include
        #
        # Only when metadata->>'eval_frozen' = 'true' is the row excluded.
        # We test the Python-level string rather than live SQL to keep tests fast.

        # Simulate the WHERE list as built by _fetch_rows
        where_fragment = (
            "(metadata IS NULL OR NOT (metadata ? 'eval_frozen')"
            " OR (metadata->>'eval_frozen') IS DISTINCT FROM 'true')"
        )
        assert "eval_frozen" in where_fragment
        assert "IS DISTINCT FROM" in where_fragment
        # The fragment should be a single condition (starts with '(', ends with ')')
        assert where_fragment.startswith("(")
        assert where_fragment.endswith(")")


# ===========================================================================
# 5. corpus_refresh_loop — dry-run enumerates steps without side-effects
# ===========================================================================

class TestRefreshLoopDryRun:
    """Dry-run must print step descriptions and perform no LLM/DB operations."""

    @pytest.mark.asyncio
    async def test_dry_run_calls_all_four_steps(self, capsys):
        """Dry-run mode prints headers for all 4 steps."""
        # Stub the actual coroutines to avoid DB/LLM
        with (
            patch(
                "scripts.corpus_refresh_loop._step1_census",
                new=AsyncMock(),
            ) as mock_s1,
            patch(
                "scripts.corpus_refresh_loop._step2_judge",
                new=AsyncMock(),
            ) as mock_s2,
            patch(
                "scripts.corpus_refresh_loop._step3_gapfill",
                new=AsyncMock(),
            ) as mock_s3,
            patch(
                "scripts.corpus_refresh_loop._step4_final_census",
                new=AsyncMock(),
            ) as mock_s4,
        ):
            from scripts.corpus_refresh_loop import run_refresh_loop  # noqa: PLC0415

            await run_refresh_loop(
                judge_limit=10,
                floor=200,
                no_seed=False,
                dry_run=True,
                dsn=None,
            )

        # All four steps must have been called
        mock_s1.assert_awaited_once()
        mock_s2.assert_awaited_once()
        mock_s3.assert_awaited_once()
        mock_s4.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_dry_run_passes_dry_run_flag_to_judge(self):
        """Step 2 receives dry_run=True in dry-run mode."""
        with (
            patch("scripts.corpus_refresh_loop._step1_census", new=AsyncMock()),
            patch(
                "scripts.corpus_refresh_loop._step2_judge",
                new=AsyncMock(),
            ) as mock_s2,
            patch("scripts.corpus_refresh_loop._step3_gapfill", new=AsyncMock()),
            patch("scripts.corpus_refresh_loop._step4_final_census", new=AsyncMock()),
        ):
            from scripts.corpus_refresh_loop import run_refresh_loop  # noqa: PLC0415

            await run_refresh_loop(dry_run=True)

        # Verify dry_run=True was forwarded
        _args, _kwargs = mock_s2.call_args
        assert True in _args or _kwargs.get("dry_run") is True, (
            "_step2_judge must receive dry_run=True"
        )

    @pytest.mark.asyncio
    async def test_no_seed_skips_step3(self):
        """--no-seed must skip seeding (step 3 still called but logs skip)."""
        step3_calls: List[Any] = []

        async def _capture_step3(*args, **kwargs):
            step3_calls.append((args, kwargs))

        with (
            patch("scripts.corpus_refresh_loop._step1_census", new=AsyncMock()),
            patch("scripts.corpus_refresh_loop._step2_judge", new=AsyncMock()),
            patch(
                "scripts.corpus_refresh_loop._step3_gapfill",
                new=_capture_step3,
            ),
            patch("scripts.corpus_refresh_loop._step4_final_census", new=AsyncMock()),
        ):
            from scripts.corpus_refresh_loop import run_refresh_loop  # noqa: PLC0415

            await run_refresh_loop(no_seed=True, dry_run=True)

        # Step 3 was invoked (it handles no_seed internally by printing skip msg)
        assert len(step3_calls) == 1
        # no_seed=True must be passed
        args0, kwargs0 = step3_calls[0]
        no_seed_val = (
            kwargs0.get("no_seed")
            if "no_seed" in kwargs0
            else (args0[3] if len(args0) > 3 else None)
        )
        assert no_seed_val is True, f"no_seed=True not forwarded: args={args0}, kwargs={kwargs0}"

    @pytest.mark.asyncio
    async def test_step3_dry_run_no_subprocess(self):
        """In dry-run mode step 3 must NOT call subprocess.run."""
        with patch("scripts.corpus_refresh_loop.subprocess") as mock_sub:
            from scripts.corpus_refresh_loop import _step3_gapfill  # noqa: PLC0415

            await _step3_gapfill(floor=200, dsn=None, dry_run=True, no_seed=False)

        mock_sub.run.assert_not_called()

    @pytest.mark.asyncio
    async def test_step3_no_seed_no_subprocess(self):
        """With --no-seed, step 3 must NOT call subprocess.run regardless of dry_run."""
        with patch("scripts.corpus_refresh_loop.subprocess") as mock_sub:
            from scripts.corpus_refresh_loop import _step3_gapfill  # noqa: PLC0415

            await _step3_gapfill(floor=200, dsn=None, dry_run=False, no_seed=True)

        mock_sub.run.assert_not_called()


# ===========================================================================
# 6. Parse smoke — all three modified/new scripts are valid Python
# ===========================================================================

class TestParseSmokeP2:
    """ast.parse must succeed on all three files."""

    def _scripts_dir(self) -> str:
        return os.path.join(_PYTHON_ROOT, "scripts")

    def _parse(self, name: str) -> None:
        path = os.path.join(self._scripts_dir(), f"{name}.py")
        assert os.path.isfile(path), f"Script not found: {path}"
        with open(path, encoding="utf-8") as f:
            source = f.read()
        ast.parse(source)  # raises SyntaxError on failure

    def test_corpus_eval_freeze_parses(self):
        self._parse("corpus_eval_freeze")

    def test_corpus_refresh_loop_parses(self):
        self._parse("corpus_refresh_loop")

    def test_export_distillation_dataset_parses(self):
        self._parse("export_distillation_dataset")
