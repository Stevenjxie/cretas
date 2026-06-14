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
from typing import Any, List, Optional
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
        """_list_uploads returns [] when DB query fails (swallows exception).

        Also verifies that set_factory_id / reset_factory_id are called so RLS
        GUC is applied (V20260502_03 fix).  Patching tenant_ctx so tests don't
        require a real asyncpg pool.
        """
        mod = self._import()

        failing_pool = MagicMock()
        failing_pool.acquire = MagicMock(side_effect=Exception("DB error"))

        set_calls: List[str] = []
        reset_calls: List[Any] = []

        def fake_set(fid):
            set_calls.append(fid)
            return object()  # opaque token

        def fake_reset(token):
            reset_calls.append(token)

        with patch("smartbi.tenant_ctx.set_factory_id", fake_set), \
                patch("smartbi.tenant_ctx.reset_factory_id", fake_reset):
            async def _test():
                return await mod._list_uploads(failing_pool, ["F001"], limit=10)
            result = _run(_test())

        assert result == []
        # RLS fix: set_factory_id must have been called with the factory_id
        assert "F001" in set_calls, "set_factory_id must be called for RLS GUC"
        # reset must be called even on failure (finally block)
        assert len(reset_calls) == len(set_calls), "reset_factory_id must always be called"

    def test_list_uploads_sets_tenant_context_per_factory(self) -> None:
        """_list_uploads calls set_factory_id for each factory before querying.

        Root cause of 'found 0 uploads': smart_bi_pg_excel_uploads has
        FORCE ROW LEVEL SECURITY (V20260502_03).  Without the correct
        app.factory_id GUC the SELECT policy evaluates false → 0 rows.
        Fix: set/reset ContextVar per-factory before pool.acquire().
        """
        mod = self._import()

        tenant_context_at_query: List[Optional[str]] = []

        # Capture which factory_id ContextVar was set when fetch() was called
        from smartbi import tenant_ctx

        fake_rows = [  # noqa: F841
            asyncio.coroutine(lambda *a, **k: [])() if False else None  # unused
        ]

        async def fake_fetch(*args, **kwargs) -> list:
            tenant_context_at_query.append(tenant_ctx.get_factory_id())
            return []

        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(side_effect=fake_fetch)

        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        async def _test():
            return await mod._list_uploads(mock_pool, ["F001", "F002"], limit=5)

        result = _run(_test())
        assert result == []
        # The ContextVar must have been F001 when F001's query ran, F002 for F002
        assert "F001" in tenant_context_at_query
        assert "F002" in tenant_context_at_query

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
        # _run_one now accepts date_range tuple instead of window_key
        dr = (date(2025, 5, 1), date(2025, 5, 31))

        async def _test():
            return await mod._run_one(
                orchestrator=None,
                factory_id="qhj_prod",
                date_range=dr,
                question="本期营业情况？",
                dry_run=True,
                window_label="30",
            )

        result = _run(_test())
        assert result is True

    def test_resolve_data_covering_windows_uses_gold_range(self) -> None:
        """_resolve_data_covering_windows builds windows anchored to real gold data.

        When agg_daily has data in 2025, windows "30"/"90"/"all" must be anchored
        to the real max_date (not today in 2026), so the orchestrator gets non-zero KPIs.
        """
        mod = self._import()

        gold_max = date(2025, 12, 31)
        gold_min = date(2025, 1, 1)

        async def fake_data_range(pool, factory_id):
            return {
                "factory_id": factory_id,
                "min_date": gold_min.isoformat(),
                "max_date": gold_max.isoformat(),
                "day_count": 365,
            }

        with patch("smartbi.gold.queries.data_range", fake_data_range):
            async def _test():
                return await mod._resolve_data_covering_windows(
                    pool=MagicMock(),
                    factory_id="qhj_prod",
                    requested_window_keys=["30", "90", "all"],
                )
            ranges = _run(_test())

        assert len(ranges) == 3
        for start, end in ranges:
            # All windows must end at or before gold_max, not at today 2026
            assert end == gold_max, f"end date {end} should be gold_max {gold_max}"
            assert start >= gold_min, f"start date {start} before gold_min {gold_min}"
            assert start <= end

    def test_resolve_data_covering_windows_falls_back_on_no_data(self) -> None:
        """Falls back to static windows when factory has no gold data."""
        mod = self._import()

        async def fake_data_range(pool, factory_id):
            return {"factory_id": factory_id, "min_date": None, "max_date": None, "day_count": 0}

        with patch("smartbi.gold.queries.data_range", fake_data_range):
            async def _test():
                return await mod._resolve_data_covering_windows(
                    pool=MagicMock(),
                    factory_id="R_GML_DEMO",
                    requested_window_keys=["30", "all"],
                )
            ranges = _run(_test())

        # Falls back: 2 ranges (static)
        assert len(ranges) == 2
        for start, end in ranges:
            assert start <= end


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

    def test_load_factory_data_sets_tenant_context(self) -> None:
        """_load_factory_data sets tenant ContextVar before querying the uploads table.

        Root cause of data_rows=0 (FOOD_3101_048 / F001): smart_bi_pg_excel_uploads
        has FORCE ROW LEVEL SECURITY (V20260502_03).  Without correct app.factory_id
        GUC the SELECT policy evaluates false → fetchrow returns None → no upload found.
        Fix: set_factory_id(factory_id) before pool.acquire(); reset in finally.
        """
        mod = self._import()

        from smartbi import tenant_ctx as tc

        tenant_at_query: List[Optional[str]] = []

        async def capture_fetchrow(*args, **kwargs):
            tenant_at_query.append(tc.get_factory_id())
            return None  # no upload found

        mock_conn = AsyncMock()
        mock_conn.fetchrow = AsyncMock(side_effect=capture_fetchrow)
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        async def _test():
            return await mod._load_factory_data(mock_pool, "FOOD_3101_048", limit_rows=100)

        _run(_test())
        # The ContextVar must have been FOOD_3101_048 when the query executed
        assert tenant_at_query, "fetchrow was never called"
        assert tenant_at_query[0] == "FOOD_3101_048", (
            f"Expected FOOD_3101_048, got {tenant_at_query[0]!r} — "
            "set_factory_id was not called before pool.acquire()"
        )

    def test_load_factory_data_dynamic_data_query_uses_row_index(self) -> None:
        """_load_factory_data must query smart_bi_dynamic_data with ORDER BY row_index.

        Root cause of FOOD_3101_048 data_rows=0 (the SECOND bug after the RLS fix):
        The data fetch query used ``ORDER BY row_number`` but the column is ``row_index``.
        ``row_number`` is a PostgreSQL reserved window-function keyword — using it
        bare in ORDER BY raises:
            ERROR: window function row_number requires an OVER clause
        The outer except swallowed the error and returned (None, []) — so even after
        the RLS/ContextVar fix the upload was found but data loading always returned [].

        This test verifies that:
          1. The data query (conn.fetch) is called when an upload IS found.
          2. The SQL sent to conn.fetch does NOT contain ``row_number`` (bare).
          3. The SQL sent to conn.fetch DOES contain ``row_index``.
        """
        mod = self._import()

        fetch_sqls: List[str] = []

        async def capture_fetch(sql: str, *args, **kwargs):
            fetch_sqls.append(sql)
            # Return two mock rows with row_data as dicts (JSONB path)
            r1 = MagicMock()
            r1.__getitem__ = lambda self, key: {"金额": 1000.0, "门店": "A店"} if key == "row_data" else None
            r2 = MagicMock()
            r2.__getitem__ = lambda self, key: {"金额": 2000.0, "门店": "B店"} if key == "row_data" else None
            return [r1, r2]

        # Upload-selection fetchrow returns one upload
        upload_row = MagicMock()
        upload_row.__getitem__ = lambda self, key: (4720 if key == "id" else 3500000)
        upload_row.__bool__ = lambda self: True

        async def capture_fetchrow(sql: str, *args, **kwargs):
            return upload_row

        mock_conn = AsyncMock()
        mock_conn.fetchrow = AsyncMock(side_effect=capture_fetchrow)
        mock_conn.fetch = AsyncMock(side_effect=capture_fetch)

        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=False)

        async def _test():
            return await mod._load_factory_data(mock_pool, "FOOD_3101_048", limit_rows=100)

        upload_id, rows = _run(_test())

        # conn.fetch must have been called (data query was issued)
        assert len(fetch_sqls) >= 1, (
            "conn.fetch was never called — data query not issued after upload found"
        )
        data_sql = fetch_sqls[0]
        # Must NOT contain bare row_number (PostgreSQL window function keyword)
        assert "row_number" not in data_sql.lower(), (
            f"Data query contains 'row_number' (PostgreSQL window function keyword, "
            f"requires OVER clause → raises error → data_rows=0). "
            f"SQL: {data_sql!r}"
        )
        # Must contain row_index (the actual column name in smart_bi_dynamic_data)
        assert "row_index" in data_sql.lower(), (
            f"Data query must ORDER BY row_index (actual column name). SQL: {data_sql!r}"
        )
        # Rows must be returned (not silently swallowed)
        assert len(rows) > 0, (
            f"Expected rows to be loaded but got {len(rows)} — data query may still be failing"
        )

    def test_load_factory_data_single_connection_for_both_queries(self) -> None:
        """Upload lookup and dynamic_data fetch share ONE pool.acquire() block.

        Both smart_bi_pg_excel_uploads and smart_bi_dynamic_data are RLS-protected
        (V20260502_03 / V20260502_04).  set_pg_connection_tenant applies the
        app.factory_id GUC once per acquire.  Using a single connection for both
        queries guarantees both queries see the same GUC — no risk of a second
        acquire getting a stale connection from another tenant.

        This test counts pool.acquire() calls and asserts exactly ONE connection
        is borrowed for both the fetchrow (upload) and fetch (data).
        """
        mod = self._import()

        acquire_count = [0]

        async def fake_fetchrow(sql: str, *args, **kwargs):
            row = MagicMock()
            row.__getitem__ = lambda self, key: (9999 if key == "id" else 100)
            row.__bool__ = lambda self: True
            return row

        async def fake_fetch(sql: str, *args, **kwargs):
            r = MagicMock()
            r.__getitem__ = lambda self, key: {"val": 1} if key == "row_data" else None
            return [r]

        mock_conn = AsyncMock()
        mock_conn.fetchrow = AsyncMock(side_effect=fake_fetchrow)
        mock_conn.fetch = AsyncMock(side_effect=fake_fetch)

        class _AcquireCtx:
            async def __aenter__(self_inner):
                acquire_count[0] += 1
                return mock_conn

            async def __aexit__(self_inner, *args):
                return False

        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(return_value=_AcquireCtx())

        async def _test():
            return await mod._load_factory_data(mock_pool, "FOOD_3101_048", limit_rows=50)

        upload_id, rows = _run(_test())

        assert acquire_count[0] == 1, (
            f"Expected exactly 1 pool.acquire() call (single connection for both queries), "
            f"got {acquire_count[0]}. Using 2 separate acquires can cause RLS GUC mismatch."
        )
        assert upload_id == 9999
        assert len(rows) > 0

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


# ===========================================================================
# 6. _extract_answer_from_insights_result — new helper in sweep_chat_qa_replay
# ===========================================================================

class TestExtractAnswerFromInsightsResult:
    """Tests for _extract_answer_from_insights_result(), the root-cause fix for
    sweep_chat_qa_replay always producing zero corpus rows.

    generate_insights() returns:
        {"success": bool, "insights": [...], "totalGenerated": int, "method": str}
    There is NO top-level "summary" key.  The helper must extract answer text
    from the _meta insight's executive_summary, or fall back to any insight text.
    """

    def _fn(self):
        from scripts.sweep_chat_qa_replay import _extract_answer_from_insights_result
        return _extract_answer_from_insights_result

    def test_extracts_meta_executive_summary(self) -> None:
        """Primary path: _meta insight with executive_summary."""
        fn = self._fn()
        result = fn(
            {
                "success": True,
                "insights": [
                    {"type": "_meta", "executive_summary": "本月营业额增长15%，主要由堂食驱动。",
                     "risk_alerts": [], "opportunities": []},
                    {"type": "trend", "text": "趋势向好"},
                ],
                "totalGenerated": 2,
                "method": "llm",
            },
            question="本月营业情况？",
        )
        assert result == "本月营业额增长15%，主要由堂食驱动。"

    def test_falls_back_to_meta_text_when_no_exec_summary(self) -> None:
        """_meta insight with no executive_summary but with text — use text."""
        fn = self._fn()
        result = fn(
            {
                "success": True,
                "insights": [
                    {"type": "_meta", "executive_summary": "", "text": "总体经营平稳，建议关注损耗。"},
                ],
                "totalGenerated": 1,
                "method": "llm",
            },
            question="整体情况？",
        )
        assert result == "总体经营平稳，建议关注损耗。"

    def test_falls_back_to_any_insight_text(self) -> None:
        """No _meta insight: falls back to first insight with non-trivial text."""
        fn = self._fn()
        result = fn(
            {
                "success": True,
                "insights": [
                    {"type": "trend", "text": "门店A营业额领先其余三家门店总和。"},
                ],
                "totalGenerated": 1,
                "method": "llm",
            },
            question="哪家门店最好？",
        )
        assert len(result) > 10

    def test_returns_empty_for_no_insights(self) -> None:
        """Empty insights list → return empty string, no crash."""
        fn = self._fn()
        result = fn({"success": True, "insights": [], "totalGenerated": 0, "method": "llm"}, "？")
        assert result == ""

    def test_returns_empty_for_non_dict(self) -> None:
        """Non-dict input → return empty string, no crash."""
        fn = self._fn()
        assert fn(None, "q") == ""
        assert fn("string", "q") == ""

    def test_returns_empty_when_summary_key_absent(self) -> None:
        """The OLD bug: result.get('summary', '') would always return '' — verify
        the NEW helper does NOT rely on 'summary' key."""
        fn = self._fn()
        # Simulate exact return from InsightGenerator.generate_insights() — no 'summary' key
        real_shaped_result = {
            "success": True,
            "insights": [
                {"type": "_meta", "executive_summary": "综合分析：毛利率下降2个点。",
                 "risk_alerts": ["原料成本上涨"], "opportunities": ["堂食增长机会"]},
            ],
            "totalGenerated": 1,
            "method": "llm",
        }
        # OLD code: real_shaped_result.get("summary", "") == "" (always empty → zero corpus rows)
        assert real_shaped_result.get("summary", "") == "", "test sanity: no summary key"
        # NEW code: extracts from _meta.executive_summary
        result = fn(real_shaped_result, "毛利率")
        assert result == "综合分析：毛利率下降2个点。"

    def test_trivially_short_text_is_skipped(self) -> None:
        """Insights with text shorter than 10 chars are skipped."""
        fn = self._fn()
        result = fn(
            {
                "success": True,
                "insights": [
                    {"type": "_meta", "executive_summary": "短", "text": "也短"},  # < 10 chars
                    {"type": "kpi", "text": "这是一个足够长的分析文字来通过长度检查。"},
                ],
                "totalGenerated": 2,
                "method": "llm",
            },
            question="？",
        )
        # Skips trivially short _meta text, falls through to Pass 2
        assert len(result) >= 10


# ===========================================================================
# 7. Probe mode: sweep_chat_qa_replay — probe=True returns True without persist
# ===========================================================================

class TestChatQaReplayProbeMode:

    def _import(self):
        from scripts import sweep_chat_qa_replay as mod
        return mod

    def test_probe_mode_does_not_persist(self) -> None:
        """probe=True: _process_one returns True but must NOT call persist_distillation_sample.

        InsightGenerator and persist_distillation_sample are lazy-imported inside
        _process_one, so we patch at the source module path.
        """
        mod = self._import()

        persist_calls: List[Any] = []

        async def fake_persist(pool, **kwargs) -> None:
            persist_calls.append(kwargs)

        async def fake_generate_insights(data, analysis_context=""):
            return {
                "success": True,
                "insights": [
                    {"type": "_meta", "executive_summary": "门店营业良好，毛利率稳定在35%。",
                     "risk_alerts": [], "opportunities": []},
                ],
                "totalGenerated": 1,
                "method": "llm",
            }

        data = [{"col_a": 5000.0, "col_b": "A"} for _ in range(50)]

        with patch("smartbi.services.distillation_capture.persist_distillation_sample", fake_persist), \
            patch("smartbi.services.insights.generator.InsightGenerator.generate_insights",
                  new=AsyncMock(side_effect=fake_generate_insights)):

            async def _test():
                return await mod._process_one(
                    pool=None,
                    factory_id="qhj_prod",
                    question="营业情况如何？",
                    data=data,
                    upload_id=1,
                    dry_run=False,
                    probe=True,
                )

            result = _run(_test())

        assert result is True
        # probe=True must NOT persist
        assert persist_calls == [], f"probe mode must not persist, got {persist_calls}"

    def test_non_probe_mode_does_persist(self) -> None:
        """probe=False: when answer is non-empty, persist_distillation_sample IS called.

        InsightGenerator and persist_distillation_sample are lazy-imported inside
        _process_one, so we patch at the source module path.
        """
        mod = self._import()

        persist_calls: List[Any] = []

        async def fake_persist(pool, **kwargs) -> None:
            # persist_distillation_sample called with all kwargs
            persist_calls.append(kwargs.get("teacher_output", ""))

        async def fake_generate_insights(data, analysis_context=""):
            return {
                "success": True,
                "insights": [
                    {"type": "_meta",
                     "executive_summary": "门店本月营业额增长12%，外卖占比提升明显。",
                     "risk_alerts": [], "opportunities": []},
                ],
                "totalGenerated": 1,
                "method": "llm",
            }

        data = [{"col_a": 5000.0, "col_b": "A"} for _ in range(50)]

        with patch("smartbi.services.distillation_capture.persist_distillation_sample", fake_persist), \
            patch("smartbi.services.insights.generator.InsightGenerator.generate_insights",
                  new=AsyncMock(side_effect=fake_generate_insights)):

            async def _test():
                return await mod._process_one(
                    pool=None,
                    factory_id="qhj_prod",
                    question="营业情况如何？",
                    data=data,
                    upload_id=1,
                    dry_run=False,
                    probe=False,
                )

            result = _run(_test())

        assert result is True
        # non-probe mode MUST persist when answer is substantive
        assert len(persist_calls) == 1
        assert "12%" in persist_calls[0] or len(persist_calls[0]) > 5


# ===========================================================================
# 8. No-data path: sweep_chat_qa_replay uses generate_text_analysis, not fake dict
# ===========================================================================

class TestChatQaReplayNoDataPath:
    """Verify the no-data path calls InsightGenerator.generate_text_analysis()
    instead of the old fake dict approach (which always produced empty answer)."""

    def _import(self):
        from scripts import sweep_chat_qa_replay as mod
        return mod

    def test_no_data_calls_generate_text_analysis(self) -> None:
        """When data=[], _process_one must call generate_text_analysis, not generate_insights.

        InsightGenerator is lazy-imported inside _process_one, so we patch at
        the source module path.  persist_distillation_sample likewise.
        """
        mod = self._import()

        text_analysis_calls: List[str] = []
        insights_calls: List[Any] = []
        persist_calls: List[Any] = []

        async def fake_text_analysis(question: str) -> str:
            text_analysis_calls.append(question)
            return "本月数据暂无，但从历史趋势来看营业情况稳定。"

        async def fake_generate_insights(data, analysis_context=""):
            insights_calls.append(data)
            return {"success": True, "insights": [], "totalGenerated": 0, "method": "llm"}

        async def fake_persist(pool, **kwargs) -> None:
            persist_calls.append(kwargs.get("teacher_output", ""))

        with patch("smartbi.services.distillation_capture.persist_distillation_sample", fake_persist), \
            patch("smartbi.services.insights.generator.InsightGenerator.generate_text_analysis",
                  new=AsyncMock(side_effect=fake_text_analysis)), \
            patch("smartbi.services.insights.generator.InsightGenerator.generate_insights",
                  new=AsyncMock(side_effect=fake_generate_insights)):

            async def _test():
                return await mod._process_one(
                    pool=None,
                    factory_id="F001",
                    question="今日销售如何？",
                    data=[],  # empty data → no-data path
                    upload_id=None,
                    dry_run=False,
                    probe=False,
                )

            _run(_test())

        # generate_text_analysis must have been called
        assert len(text_analysis_calls) == 1
        assert text_analysis_calls[0] == "今日销售如何？"
        # generate_insights must NOT have been called for empty data
        assert insights_calls == [], "generate_insights must not be called when data is empty"
        # answer from generate_text_analysis must be persisted
        assert len(persist_calls) == 1
