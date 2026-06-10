"""Unit tests for P0-1 corpus quality foundation.

Covers:
  1. persist_distillation_sample forwards quality into INSERT params
  2. _build_qa_input_text embeds data context (not bare query)
  3. _build_qa_data_context builds substantive context from data+insights
  4. _derive_business_type heuristic
  5. _qa_lint_has_data_context gate
  6. corpus_census.py BucketStats + _aggregate (no DB required)
  7. export_distillation_dataset: NULL filter is gone (quality >= N only)
  8. scripts parse OK (ast.parse smoke)

Hash function MUST NOT be changed:
  sha256(input_text) — verified in test_hash_unchanged.
"""
from __future__ import annotations

import ast
import asyncio
import hashlib
import json
import os
import sys
from typing import Any, Dict, Optional
from unittest.mock import AsyncMock, MagicMock, patch, call

# ---------------------------------------------------------------------------
# Path setup
# ---------------------------------------------------------------------------
TESTS_DIR = os.path.dirname(__file__)
# backend/python
PYTHON_ROOT = os.path.normpath(os.path.join(TESTS_DIR, "..", ".."))
# backend (3 levels up from tests/) — kept for backward compat with existing tests
SMARTBI_ROOT = os.path.normpath(os.path.join(TESTS_DIR, "..", "..", ".."))
if PYTHON_ROOT not in sys.path:
    sys.path.insert(0, PYTHON_ROOT)

import pytest

# ---------------------------------------------------------------------------
# Imports under test
# ---------------------------------------------------------------------------
from smartbi.services.distillation_capture import (
    compute_input_hash,
    persist_distillation_sample,
)
from smartbi.api.chat import (
    _build_qa_input_text,
    _build_qa_data_context,
    _derive_business_type,
    _qa_lint_has_data_context,
)


# ===========================================================================
# 1. Hash function must remain sha256(input_text) — read-back cache depends on it
# ===========================================================================

class TestHashUnchanged:
    """🔒 The hash must stay sha256(input_text). The chart-insight read-back
    cache looks up rows by sha256(prompt) == sha256(input_text). Changing the
    hash function would silently break the cache for every existing row."""

    def test_hash_is_sha256(self):
        text = "chart_insight|BAR|month|revenue|SUM|restaurant|NORMAL|manager|factory:F001|labels:[1月,2月]|values:[100,200]"
        expected = hashlib.sha256(text.encode("utf-8")).hexdigest()
        assert compute_input_hash(text) == expected

    def test_hash_empty_string(self):
        assert compute_input_hash("") == hashlib.sha256(b"").hexdigest()

    def test_hash_none_treated_as_empty(self):
        # compute_input_hash guards against None
        assert compute_input_hash(None) == hashlib.sha256(b"").hexdigest()

    def test_hash_deterministic(self):
        t = "hello world 中文"
        assert compute_input_hash(t) == compute_input_hash(t)


# ===========================================================================
# 2. persist_distillation_sample forwards quality into the INSERT
# ===========================================================================

class TestPersistQuality:
    """quality= param must be passed as the 11th positional arg to conn.execute."""

    @pytest.mark.asyncio
    async def test_quality_5_forwarded(self):
        mock_conn = AsyncMock()
        mock_pool = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        await persist_distillation_sample(
            mock_pool,
            source="chart_insight",
            task_type="insights",
            input_text="chart_insight|BAR|month|revenue|SUM|restaurant|NORMAL|manager|factory:F001|labels:[1月]|values:[100]",
            teacher_output='{"finding":"test","implication":null,"suggestion":null}',
            quality=5,
        )

        mock_conn.execute.assert_awaited_once()
        call_args = mock_conn.execute.call_args
        positional = call_args[0]
        # SQL is first arg; params follow. quality is the 11th param ($11)
        # Params order: source, business_type, factory_id, task_type, codes_str,
        #               system_prompt, input_text, teacher_model, teacher_output,
        #               input_hash, quality, metadata_json
        # index 0 = SQL, indices 1-12 = params
        assert positional[11] == 5, f"quality param not 5, got: {positional[11]!r}"

    @pytest.mark.asyncio
    async def test_quality_4_forwarded(self):
        mock_conn = AsyncMock()
        mock_pool = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        await persist_distillation_sample(
            mock_pool,
            source="materialization",
            task_type="insights",
            input_text="some prompt text here",
            teacher_output="some teacher output",
            quality=4,
        )

        call_args = mock_conn.execute.call_args[0]
        assert call_args[11] == 4

    @pytest.mark.asyncio
    async def test_quality_none_forwarded(self):
        """None quality must also be forwarded (not silently dropped)."""
        mock_conn = AsyncMock()
        mock_pool = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        await persist_distillation_sample(
            mock_pool,
            source="chat_qa",
            task_type="qa",
            input_text="user query",
            teacher_output="answer",
            # quality not passed → default None
        )

        call_args = mock_conn.execute.call_args[0]
        assert call_args[11] is None

    @pytest.mark.asyncio
    async def test_pool_none_returns_silently(self):
        """If pool is None, must return without error (fire-and-forget contract)."""
        # Should not raise
        await persist_distillation_sample(
            None,
            source="chat_qa",
            task_type="qa",
            input_text="query",
            teacher_output="answer",
            quality=3,
        )

    @pytest.mark.asyncio
    async def test_db_error_swallowed(self):
        """DB errors must be swallowed (best-effort, NEVER raises)."""
        mock_conn = AsyncMock()
        mock_conn.execute.side_effect = Exception("DB connection lost")
        mock_pool = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        # Must not raise
        await persist_distillation_sample(
            mock_pool,
            source="chat_qa",
            task_type="qa",
            input_text="query",
            teacher_output="answer",
            quality=3,
        )


# ===========================================================================
# 3. _build_qa_input_text — data context embedded
# ===========================================================================

class TestBuildQaInputText:
    """The input_text must contain both the query and the data context so the
    corpus pair is self-contained (not a bare query that teaches hallucination)."""

    def test_with_data_context_contains_both(self):
        text = _build_qa_input_text("上月营收多少？", "rows=500; revenue_sum=1234567.89")
        assert "上月营收多少？" in text
        assert "rows=500" in text
        assert "1234567.89" in text

    def test_without_data_context_is_still_tagged(self):
        text = _build_qa_input_text("翻台率是多少？", None)
        assert "翻台率是多少？" in text
        # Must still have the chat_qa prefix
        assert "chat_qa" in text

    def test_prefix_is_chat_qa(self):
        text = _build_qa_input_text("query", "some context")
        assert text.startswith("chat_qa|")

    def test_bare_query_shorter_than_with_context(self):
        bare = _build_qa_input_text("test?", None)
        rich = _build_qa_input_text("test?", "rows=10; total_sum=99.50")
        assert len(rich) > len(bare)


# ===========================================================================
# 4. _build_qa_data_context — builds substantive context from data+insights
# ===========================================================================

class TestBuildQaDataContext:
    def test_returns_rows_and_cols(self):
        data = [{"month": "1月", "revenue": 100000.0}, {"month": "2月", "revenue": 120000.0}]
        ctx = _build_qa_data_context(data, None)
        assert ctx is not None
        assert "rows=2" in ctx
        assert "month" in ctx or "revenue" in ctx

    def test_includes_numeric_summary(self):
        data = [{"revenue": 500000.0, "cost": 200000.0}] * 5
        ctx = _build_qa_data_context(data, None)
        assert ctx is not None
        # Should contain a numeric figure
        import re
        assert re.search(r"\d+\.\d+", ctx), f"No numeric in: {ctx}"

    def test_includes_insight_summary(self):
        data = [{"x": 1}]
        insights_result = {"summary": "本月营收增长15%，成本压缩明显。"}
        ctx = _build_qa_data_context(data, insights_result)
        assert ctx is not None
        assert "本月营收增长" in ctx

    def test_empty_data_returns_none(self):
        ctx = _build_qa_data_context(None, None)
        assert ctx is None

    def test_empty_list_returns_none(self):
        ctx = _build_qa_data_context([], None)
        assert ctx is None

    def test_never_raises(self):
        # Garbage input — must not raise
        result = _build_qa_data_context(object(), {"bad": object()})  # type: ignore
        # May return None or a partial string; must not raise


# ===========================================================================
# 5. _derive_business_type
# ===========================================================================

class TestDeriveBusinessType:
    def test_qhj_prefix_is_restaurant(self):
        assert _derive_business_type("qhj_prod", None) == "restaurant"

    def test_qhj_upper_prefix_is_restaurant(self):
        assert _derive_business_type("QHJ001", None) == "restaurant"

    def test_factory_f_prefix_is_factory(self):
        assert _derive_business_type("F001", None) == "factory"
        assert _derive_business_type("F006", None) == "factory"

    def test_table_type_restaurant_hint(self):
        assert _derive_business_type(None, "restaurant_sales") == "restaurant"

    def test_table_type_factory_hint(self):
        assert _derive_business_type(None, "factory_production") == "factory"

    def test_unknown_factory_id_is_unknown(self):
        assert _derive_business_type("UNKNOWN999", None) == "unknown"

    def test_none_none_is_unknown(self):
        assert _derive_business_type(None, None) == "unknown"


# ===========================================================================
# 6. _qa_lint_has_data_context
# ===========================================================================

class TestQaLintHasDataContext:
    def test_numeric_context_passes(self):
        assert _qa_lint_has_data_context("rows=500; revenue_sum=1234567.89") is True

    def test_yuan_sign_passes(self):
        assert _qa_lint_has_data_context("total=¥850000，环比+12%") is True

    def test_none_fails(self):
        assert _qa_lint_has_data_context(None) is False

    def test_empty_fails(self):
        assert _qa_lint_has_data_context("") is False

    def test_too_short_fails(self):
        assert _qa_lint_has_data_context("rows=5") is False  # <20 chars

    def test_no_numerics_fails(self):
        assert _qa_lint_has_data_context("summary: 这是一段没有数字的描述性文本，应该不通过。") is False

    def test_percentage_passes(self):
        assert _qa_lint_has_data_context("growth_rate=15%, rows=100, cols=[month]") is True


# ===========================================================================
# 7. BucketStats and _aggregate (no DB)
# ===========================================================================

class TestBucketStats:
    def test_green_bucket(self):
        from scripts.corpus_census import BucketStats, GREEN_THRESHOLD
        s = BucketStats()
        for _ in range(GREEN_THRESHOLD):
            s.add(quality=4, is_syn=False)
        assert s.status() == "GREEN"
        assert s.gap_to_green == 0
        assert s.quality_ge4 == GREEN_THRESHOLD

    def test_red_bucket(self):
        from scripts.corpus_census import BucketStats
        s = BucketStats()
        for _ in range(50):
            s.add(quality=3, is_syn=False)
        assert s.status() == "RED"
        # 50 rows with quality=3 → quality_ge4=0 → gap = 1000 - 0 = 1000
        assert s.gap_to_green == 1000

    def test_null_quality_not_counted_in_ge4(self):
        from scripts.corpus_census import BucketStats
        s = BucketStats()
        for _ in range(500):
            s.add(quality=None, is_syn=False)
        assert s.quality_ge4 == 0
        assert s.quality_null == 500

    def test_synthetic_too_high_not_green(self):
        from scripts.corpus_census import BucketStats, GREEN_THRESHOLD
        s = BucketStats()
        # 1000 quality=4 but 90% synthetic
        for _ in range(GREEN_THRESHOLD):
            s.add(quality=4, is_syn=True)
        assert s.status() != "GREEN"

    def test_aggregate(self):
        from scripts.corpus_census import _aggregate, _is_synthetic
        rows = [
            {"source": "chart_insight", "business_type": "restaurant", "task_type": "insights", "quality": 5, "metadata": None},
            {"source": "chart_insight", "business_type": "restaurant", "task_type": "insights", "quality": 5, "metadata": None},
            {"source": "chat_qa", "business_type": "unknown", "task_type": "qa", "quality": None, "metadata": None},
        ]
        buckets = _aggregate(rows)
        assert len(buckets) == 2
        ci_key = ("chart_insight", "restaurant", "insights")
        assert buckets[ci_key].total == 2
        assert buckets[ci_key].quality_dist[5] == 2
        qa_key = ("chat_qa", "unknown", "qa")
        assert buckets[qa_key].quality_null == 1

    def test_is_synthetic_seeded_flag(self):
        from scripts.corpus_census import _is_synthetic
        assert _is_synthetic({"seeded": True}) is True
        assert _is_synthetic({"seeded": False}) is False
        assert _is_synthetic({"source_kind": "synthetic"}) is True
        assert _is_synthetic({"source_kind": "organic"}) is False
        assert _is_synthetic(None) is False


# ===========================================================================
# 8. Export filter: quality IS NULL must NOT be in the SQL
# ===========================================================================

class TestExportFilter:
    """The P0-1 fix removes 'quality IS NULL OR' from the export filter so
    unscored rows cannot leak into training data."""

    def test_null_not_in_filter(self):
        import pathlib, re
        script_path = pathlib.Path(PYTHON_ROOT) / "scripts" / "export_distillation_dataset.py"
        src = script_path.read_text(encoding="utf-8")
        # Strip comments to check only actual code lines
        code_lines = [
            line for line in src.splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        code_only = "\n".join(code_lines)
        # Old broken filter pattern in actual code (not comments)
        assert "quality IS NULL OR" not in code_only, (
            "Old broken filter found in code: 'quality IS NULL OR' leaks unscored "
            "rows into training. Should be 'quality >= $N' only."
        )
        # New correct filter should still be present in actual code
        assert "quality >=" in code_only, "Expected 'quality >= $N' filter in exporter"


# ===========================================================================
# 9. Scripts parse OK (smoke test — no import errors)
# ===========================================================================

class TestScriptsParse:
    def _parse(self, rel_path: str) -> None:
        full = os.path.join(PYTHON_ROOT, rel_path)
        with open(full, encoding="utf-8") as f:
            src = f.read()
        ast.parse(src)  # raises SyntaxError if broken

    def test_corpus_census_parses(self):
        self._parse("scripts/corpus_census.py")

    def test_export_distillation_parses(self):
        self._parse("scripts/export_distillation_dataset.py")

    def test_distillation_capture_parses(self):
        self._parse("smartbi/services/distillation_capture.py")
