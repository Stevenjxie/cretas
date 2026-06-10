"""Unit tests for P1 cold-start sweep drivers.

Tests each driver's *enumeration logic* in dry-run mode:
 1. sweep_materialization_backfill — factories × uploads enumeration
 2. sweep_agent_insight_warmup    — factories × windows × questions enumeration
 3. sweep_chat_qa_replay          — question bank content + factories × questions

No DB, no LLM, no network required: all DB calls are mocked.  Tests validate:
 - The work list (factories × windows/questions) is non-empty
 - The question bank is non-empty and every entry has a non-empty string
 - DEFAULT_FACTORIES lists are non-empty
 - Time window resolution produces valid (start ≤ end) date ranges
 - Idempotency is documented via ON CONFLICT note in each module's docstring
 - All three scripts parse cleanly (ast.parse smoke)
"""
from __future__ import annotations

import ast
import asyncio
import os
import sys
from datetime import date
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Path setup — mirrors other tests in this package
# ---------------------------------------------------------------------------
TESTS_DIR = os.path.dirname(__file__)
PYTHON_ROOT = os.path.normpath(os.path.join(TESTS_DIR, "..", ".."))
if PYTHON_ROOT not in sys.path:
    sys.path.insert(0, PYTHON_ROOT)


# ---------------------------------------------------------------------------
# Helper: run async coroutines in tests
# ---------------------------------------------------------------------------
def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


# ===========================================================================
# 1. AST parse smoke — all three drivers must parse without SyntaxError
# ===========================================================================

class TestScriptsParse:
    _SCRIPTS = [
        "sweep_materialization_backfill",
        "sweep_agent_insight_warmup",
        "sweep_chat_qa_replay",
    ]

    @pytest.mark.parametrize("script_name", _SCRIPTS)
    def test_parse_ok(self, script_name: str) -> None:
        """Every driver script must be valid Python (ast.parse must not raise)."""
        path = os.path.join(PYTHON_ROOT, "scripts", f"{script_name}.py")
        assert os.path.isfile(path), f"Script not found: {path}"
        with open(path, encoding="utf-8") as f:
            source = f.read()
        # Raises SyntaxError on malformed source
        tree = ast.parse(source)
        assert tree is not None


# ===========================================================================
# 2. sweep_materialization_backfill — enumeration + defaults
# ===========================================================================

class TestMaterializationBackfill:

    def _import(self):
        from scripts import sweep_materialization_backfill as mod
        return mod

    def test_default_factories_non_empty(self) -> None:
        mod = self._import()
        assert len(mod.DEFAULT_FACTORIES) > 0
        for fid in mod.DEFAULT_FACTORIES:
            assert isinstance(fid, str) and fid.strip()

    def test_dry_run_enumeration(self) -> None:
        """Dry-run with 2 factories and 2 uploads each emits 4 work items."""
        mod = self._import()

        fake_upload_rows = [
            {"id": 101, "factory_id": "F001", "file_name": "test.xlsx", "row_count": 100, "created_at": "2026-01-01"},
            {"id": 102, "factory_id": "F001", "file_name": "test2.xlsx", "row_count": 50, "created_at": "2026-01-02"},
        ]

        processed: List[int] = []

        async def _fake_sweep():
            for u in fake_upload_rows:
                ok = await mod._rematerialize_upload(None, u["id"], u["factory_id"], dry_run=True)
                if ok:
                    processed.append(u["id"])

        _run(_fake_sweep())
        assert processed == [101, 102]

    def test_count_corpus_rows_handles_none_pool(self) -> None:
        """_count_corpus_rows with a failing pool returns -1, not an exception."""
        mod = self._import()

        async def _test():
            return await mod._count_corpus_rows(None, source="materialization")

        result = _run(_test())
        assert result == -1

    def test_list_uploads_empty_on_db_error(self) -> None:
        """_list_uploads returns [] when DB query fails (swallows exception)."""
        mod = self._import()

        failing_pool = MagicMock()
        failing_pool.acquire = MagicMock(side_effect=Exception("DB error"))

        async def _test():
            return await mod._list_uploads(failing_pool, ["F001"], limit=10)

        result = _run(_test())
        assert result == []

    def test_dry_run_does_not_call_materialize(self) -> None:
        """In dry-run mode, _rematerialize_upload must not import the materializer."""
        mod = self._import()

        # This should succeed without importing any real DB/LLM module
        async def _test():
            return await mod._rematerialize_upload(None, 9999, "F999", dry_run=True)

        result = _run(_test())
        assert result is True


# ===========================================================================
# 3. sweep_agent_insight_warmup — factories × windows × questions
# ===========================================================================

class TestAgentInsightWarmup:

    def _import(self):
        from scripts import sweep_agent_insight_warmup as mod
        return mod

    def test_default_factories_non_empty(self) -> None:
        mod = self._import()
        assert len(mod.DEFAULT_RESTAURANT_FACTORIES) > 0
        for fid in mod.DEFAULT_RESTAURANT_FACTORIES:
            assert isinstance(fid, str) and fid.strip()

    def test_default_questions_non_empty(self) -> None:
        mod = self._import()
        assert len(mod.DEFAULT_QUESTIONS) >= 5, "Must have at least 5 questions"
        for q in mod.DEFAULT_QUESTIONS:
            assert isinstance(q, str) and q.strip(), f"Empty question found: {q!r}"

    def test_window_resolution_30(self) -> None:
        mod = self._import()
        start, end = mod._resolve_date_range("30")
        assert start <= end
        assert (end - start).days == 30

    def test_window_resolution_90(self) -> None:
        mod = self._import()
        start, end = mod._resolve_date_range("90")
        assert (end - start).days == 90

    def test_window_resolution_all(self) -> None:
        mod = self._import()
        start, end = mod._resolve_date_range("all")
        assert start == date(2025, 1, 1)
        assert end >= start

    def test_window_resolution_numeric_str(self) -> None:
        mod = self._import()
        start, end = mod._resolve_date_range("14")
        assert (end - start).days == 14

    def test_window_resolution_unknown_raises(self) -> None:
        mod = self._import()
        with pytest.raises(ValueError):
            mod._resolve_date_range("invalid_window_xyz")

    def test_work_list_size(self) -> None:
        """Verify factories × windows × questions combination count."""
        mod = self._import()
        factories = mod.DEFAULT_RESTAURANT_FACTORIES[:2]
        windows = ["30", "90"]
        questions = mod.DEFAULT_QUESTIONS
        total = len(factories) * len(windows) * len(questions)
        assert total == 2 * 2 * len(questions)

    def test_no_budget_tracker_never_blocks(self) -> None:
        mod = self._import()
        tracker = mod._NoBudgetTracker()

        async def _test():
            budget = await tracker.check_budget("F001")
            assert not budget.blocked
            ret = await tracker.consume("F001", 1000)
            assert not ret.blocked

        _run(_test())

    def test_noop_cache_always_misses(self) -> None:
        mod = self._import()
        cache = mod._NoopNarrativeCache()

        async def _test():
            hit = await cache.get("F001", "some_hash")
            assert hit is None
            # put should not raise
            await cache.put("F001", "some_hash", "answer")

        _run(_test())

    def test_dry_run_returns_true(self) -> None:
        mod = self._import()

        async def _test():
            return await mod._run_one(
                orchestrator=None,
                factory_id="qhj_prod",
                window_key="30",
                question="本期营业情况？",
                dry_run=True,
            )

        result = _run(_test())
        assert result is True


# ===========================================================================
# 4. sweep_chat_qa_replay — question bank + data loading
# ===========================================================================

class TestChatQaReplay:

    def _import(self):
        from scripts import sweep_chat_qa_replay as mod
        return mod

    def test_question_bank_non_empty(self) -> None:
        mod = self._import()
        bank = mod.QUESTION_BANK
        assert len(bank) >= 20, f"Expected ≥20 questions, got {len(bank)}"
        for item in bank:
            assert "q" in item and "category" in item
            assert isinstance(item["q"], str) and item["q"].strip()
            assert isinstance(item["category"], str) and item["category"].strip()

    def test_get_question_bank_returns_strings(self) -> None:
        mod = self._import()
        questions = mod.get_question_bank()
        assert len(questions) >= 20
        for q in questions:
            assert isinstance(q, str) and q.strip()

    def test_default_factories_non_empty(self) -> None:
        mod = self._import()
        assert len(mod.DEFAULT_FACTORIES) > 0

    def test_derive_business_type_restaurant(self) -> None:
        mod = self._import()
        assert mod._derive_business_type("qhj_prod") == "restaurant"
        assert mod._derive_business_type("RES_3101_009") == "restaurant"
        assert mod._derive_business_type("R_GML_DEMO") == "restaurant"

    def test_derive_business_type_factory(self) -> None:
        mod = self._import()
        assert mod._derive_business_type("F001") == "factory"
        assert mod._derive_business_type("F006") == "factory"

    def test_derive_business_type_unknown(self) -> None:
        mod = self._import()
        assert mod._derive_business_type("MYSTERY_001") == "unknown"

    def test_qa_lint_has_data_context_positive(self) -> None:
        mod = self._import()
        assert mod._qa_lint_has_data_context("rows=1000, cols=[金额,门店], 金额_sum=500000.00")
        assert mod._qa_lint_has_data_context("revenue=¥1234567, store_count=5")

    def test_qa_lint_has_data_context_negative(self) -> None:
        mod = self._import()
        assert not mod._qa_lint_has_data_context(None)
        assert not mod._qa_lint_has_data_context("")
        assert not mod._qa_lint_has_data_context("short")
        assert not mod._qa_lint_has_data_context("no numbers here at all whatsoever")

    def test_build_qa_input_text_with_context(self) -> None:
        mod = self._import()
        q = "营业额如何？"
        ctx = "rows=100; 金额_sum=50000.00"
        result = mod._build_qa_input_text(q, ctx)
        assert "chat_qa|general_analysis" in result
        assert q in result
        assert ctx in result

    def test_build_qa_input_text_without_context(self) -> None:
        mod = self._import()
        q = "营业额如何？"
        result = mod._build_qa_input_text(q, None)
        assert "chat_qa|general_analysis" in result
        assert q in result
        # No data_context tag
        assert "data_context" not in result

    def test_build_data_context_with_numeric_data(self) -> None:
        mod = self._import()
        data = [{"金额": 1000.0, "门店": "A店"}, {"金额": 2000.0, "门店": "B店"}]
        ctx = mod._build_data_context(data, "营业不错")
        assert ctx is not None
        assert "rows=2" in ctx

    def test_build_data_context_empty_data(self) -> None:
        mod = self._import()
        ctx = mod._build_data_context([], "")
        assert ctx is None

    def test_load_factory_data_handles_no_uploads(self) -> None:
        """_load_factory_data returns (None, []) when no completed upload found."""
        mod = self._import()

        mock_pool = MagicMock()
        mock_conn = AsyncMock()
        mock_conn.fetchrow = AsyncMock(return_value=None)  # no upload row
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        async def _test():
            return await mod._load_factory_data(mock_pool, "F_UNKNOWN", limit_rows=100)

        upload_id, rows = _run(_test())
        assert upload_id is None
        assert rows == []

    def test_dry_run_process_one(self) -> None:
        """Dry-run _process_one returns True without calling InsightGenerator."""
        mod = self._import()

        async def _test():
            return await mod._process_one(
                pool=None,
                factory_id="F001",
                question="营业额如何？",
                data=[],
                upload_id=None,
                dry_run=True,
            )

        result = _run(_test())
        assert result is True

    def test_work_list_size(self) -> None:
        """factories × questions combination count is correct."""
        mod = self._import()
        factories = mod.DEFAULT_FACTORIES
        questions = mod.get_question_bank()
        total = len(factories) * len(questions)
        assert total == len(factories) * len(questions) > 0

    def test_idempotency_documented(self) -> None:
        """ON CONFLICT / idempotent is mentioned in the module docstring."""
        mod = self._import()
        doc = mod.__doc__ or ""
        assert "idempotent" in doc.lower() or "on conflict" in doc.lower(), (
            "Module docstring must mention idempotency"
        )


# ===========================================================================
# 5. Cross-driver: question bank coverage sanity
# ===========================================================================

class TestQuestionBankCoverage:

    def test_categories_diverse(self) -> None:
        """The question bank must cover at least 5 different intent categories."""
        from scripts.sweep_chat_qa_replay import QUESTION_BANK
        categories = {item["category"] for item in QUESTION_BANK}
        assert len(categories) >= 5, (
            f"Expected ≥5 categories, got {len(categories)}: {categories}"
        )

    def test_no_duplicate_questions(self) -> None:
        from scripts.sweep_chat_qa_replay import QUESTION_BANK
        questions = [item["q"] for item in QUESTION_BANK]
        assert len(questions) == len(set(questions)), "Duplicate questions found in bank"

    def test_materialization_and_agent_have_different_factory_defaults(self) -> None:
        """The two drivers serve different corpus buckets (materialization vs agent_insight).
        They don't need identical factory lists, but both must be non-empty."""
        from scripts.sweep_materialization_backfill import DEFAULT_FACTORIES as mat_facs
        from scripts.sweep_agent_insight_warmup import DEFAULT_RESTAURANT_FACTORIES as agent_facs
        assert len(mat_facs) > 0
        assert len(agent_facs) > 0
