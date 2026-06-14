"""Tests for corpus_judge.py — P2 quality layer (G2 LLM-judge).

Test coverage:
  - build_judge_prompt: context + output both appear in the messages
  - parse_judge_scores: valid JSON, markdown-fenced JSON, invalid/missing
  - decide_quality: promote (overall>=4 & factuality>=4), downgrade (factuality<3), keep (else)
  - idempotency: rows with metadata['judge'] should be excluded by the DB query filter
  - dry_run: no DB writes happen in dry-run mode
"""
from __future__ import annotations
import enum
import types

import json
import sys
import os
from typing import Dict
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Path bootstrap — mirrors the standalone script pattern.
# Needed because tests can be run from backend/python/ with:
#   python -m pytest smartbi/tests/test_corpus_judge.py
# or from the root.
# ---------------------------------------------------------------------------
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
# backend/python/smartbi/tests/ → go up 3 levels to reach backend/python/
_PYTHON_ROOT = os.path.normpath(os.path.join(_THIS_DIR, "..", "..", ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# Stub out the modules that corpus_judge imports at module level so we can
# import corpus_judge without needing real DB or LLM API keys.


def _ensure_stub(module_name: str) -> None:
    """Register an empty module stub if not already present."""
    if module_name not in sys.modules:
        parts = module_name.split(".")
        for i in range(1, len(parts) + 1):
            name = ".".join(parts[:i])
            if name not in sys.modules:
                sys.modules[name] = types.ModuleType(name)


# Stubs needed before importing the script
_ensure_stub("common")
_ensure_stub("common.llm_router")

# Provide a minimal SLOT enum + call_chain stub so corpus_judge can import them


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

# Now safe to import the module under test
from scripts.corpus_judge import (  # noqa: E402,F401
    build_judge_prompt,
    parse_judge_scores,
    decide_quality,
    fetch_unjudged_rows,
    apply_judge_result,
    judge_row,
    run_judge,
    _FACTUAL_QA_SOURCES,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SAMPLE_INPUT_TEXT = (
    "工厂F001本月生产报告：猪舌共处理1200kg，"
    "入库成品900kg，损耗率25%，主要集中在焯水和修剪工序。"
)

SAMPLE_TEACHER_OUTPUT = (
    "本月猪舌生产损耗率为25%，主要发生在焯水（12%）和修剪（13%）工序。"
    "建议优化修剪工序的操作标准，参考行业基准损耗率18%，"
    "可减少约7%的损耗，约合84kg/月的产出提升。"
)

GOOD_SCORES: Dict[str, int] = {
    "factuality": 5,
    "actionability": 4,
    "fluency": 5,
    "overall": 5,
}

BORDERLINE_SCORES: Dict[str, int] = {
    "factuality": 4,
    "actionability": 3,
    "fluency": 4,
    "overall": 4,
}

BAD_FACTUALITY_SCORES: Dict[str, int] = {
    "factuality": 2,
    "actionability": 3,
    "fluency": 4,
    "overall": 3,
}

MEDIUM_SCORES: Dict[str, int] = {
    "factuality": 3,
    "actionability": 3,
    "fluency": 3,
    "overall": 3,
}


# ---------------------------------------------------------------------------
# TestBuildJudgePrompt
# ---------------------------------------------------------------------------

class TestBuildJudgePrompt:
    """Verify prompt construction includes both context and teacher output."""

    def test_system_prompt_not_empty(self):
        system_prompt, user_prompt = build_judge_prompt(
            SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT
        )
        assert len(system_prompt) > 50, "System prompt should be non-trivial"

    def test_user_prompt_contains_input_text(self):
        _, user_prompt = build_judge_prompt(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)
        # The input_text (context) must appear verbatim in the user message
        assert SAMPLE_INPUT_TEXT in user_prompt, (
            "User prompt must embed the input_text (context) for the judge "
            "to verify factuality against it"
        )

    def test_user_prompt_contains_teacher_output(self):
        _, user_prompt = build_judge_prompt(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)
        # The teacher_output must appear verbatim so the judge can score it
        assert SAMPLE_TEACHER_OUTPUT in user_prompt, (
            "User prompt must embed the teacher_output for scoring"
        )

    def test_both_context_and_output_present(self):
        """Core test: judge can only score factuality if it sees both sides."""
        system_prompt, user_prompt = build_judge_prompt(
            SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT
        )
        assert SAMPLE_INPUT_TEXT in user_prompt
        assert SAMPLE_TEACHER_OUTPUT in user_prompt
        assert len(system_prompt) > 0

    def test_prompt_mentions_factuality_dimension(self):
        """Prompt must ask the judge to evaluate factuality specifically."""
        _, user_prompt = build_judge_prompt(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)
        # The prompt should mention the factuality/事实性 dimension
        assert "factuality" in user_prompt or "事实性" in user_prompt, (
            "Prompt must request factuality scoring to support the q4 promotion rule"
        )

    def test_truncation_of_very_long_inputs(self):
        """Very long inputs should be truncated, not cause errors."""
        long_input = "A" * 10_000
        long_output = "B" * 5_000
        system_prompt, user_prompt = build_judge_prompt(long_input, long_output)
        # Should not raise; prompt should be bounded
        assert len(user_prompt) < 15_000, "Prompt should be truncated to a safe length"

    def test_empty_inputs_handled(self):
        """Empty inputs should not raise."""
        system_prompt, user_prompt = build_judge_prompt("", "")
        assert isinstance(system_prompt, str)
        assert isinstance(user_prompt, str)

    def test_returns_tuple_of_two_strings(self):
        result = build_judge_prompt(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)
        assert isinstance(result, tuple)
        assert len(result) == 2
        assert all(isinstance(s, str) for s in result)


# ---------------------------------------------------------------------------
# TestParseAndDecide
# ---------------------------------------------------------------------------

class TestParseJudgeScores:
    """Verify JSON score extraction from various raw LLM outputs."""

    def test_valid_clean_json(self):
        raw = '{"factuality": 5, "actionability": 4, "fluency": 5, "overall": 5}'
        scores = parse_judge_scores(raw)
        assert scores == GOOD_SCORES

    def test_valid_json_with_surrounding_text(self):
        raw = (
            '根据评审标准，以下是评分：\n'
            '{"factuality": 5, "actionability": 4, "fluency": 5, "overall": 5}\n'
            '以上是我的评分结果。'
        )
        scores = parse_judge_scores(raw)
        assert scores == GOOD_SCORES

    def test_markdown_fenced_json(self):
        raw = (
            '```json\n'
            '{"factuality": 5, "actionability": 4, "fluency": 5, "overall": 5}\n'
            '```'
        )
        scores = parse_judge_scores(raw)
        assert scores == GOOD_SCORES

    def test_markdown_fenced_json_no_lang_tag(self):
        raw = '```\n{"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}\n```'
        scores = parse_judge_scores(raw)
        assert scores is not None
        assert scores["factuality"] == 4
        assert scores["overall"] == 4

    def test_missing_required_key_returns_none(self):
        raw = '{"factuality": 5, "actionability": 4, "fluency": 5}'  # missing "overall"
        scores = parse_judge_scores(raw)
        assert scores is None

    def test_out_of_range_score_returns_none(self):
        raw = '{"factuality": 6, "actionability": 4, "fluency": 5, "overall": 5}'
        scores = parse_judge_scores(raw)
        assert scores is None

    def test_zero_score_returns_none(self):
        raw = '{"factuality": 0, "actionability": 4, "fluency": 5, "overall": 4}'
        scores = parse_judge_scores(raw)
        assert scores is None

    def test_non_integer_score_returns_none(self):
        raw = '{"factuality": "high", "actionability": 4, "fluency": 5, "overall": 4}'
        scores = parse_judge_scores(raw)
        assert scores is None

    def test_empty_string_returns_none(self):
        assert parse_judge_scores("") is None

    def test_none_input_returns_none(self):
        assert parse_judge_scores(None) is None  # type: ignore[arg-type]

    def test_no_json_in_text_returns_none(self):
        raw = "对不起，我无法评审这个内容，因为格式不符合要求。"
        scores = parse_judge_scores(raw)
        assert scores is None

    def test_all_scores_in_valid_range(self):
        for val in range(1, 6):
            raw = json.dumps({
                "factuality": val,
                "actionability": val,
                "fluency": val,
                "overall": val,
            })
            scores = parse_judge_scores(raw)
            assert scores is not None, f"Score {val} should be valid"
            assert all(v == val for v in scores.values())


class TestDecideQuality:
    """Verify the quality promotion/demotion decision from scores."""

    def test_promote_high_overall_and_factuality(self):
        """overall>=4 AND factuality>=4 → quality=4 (training-ready)."""
        assert decide_quality(GOOD_SCORES) == 4

    def test_promote_borderline(self):
        """overall=4 AND factuality=4 → quality=4 (borderline should still promote)."""
        assert decide_quality(BORDERLINE_SCORES) == 4

    def test_keep_medium_scores(self):
        """overall=3, factuality=3 → quality=3 (stay, excluded from export)."""
        assert decide_quality(MEDIUM_SCORES) == 3

    def test_downgrade_bad_factuality(self):
        """factuality<3 → quality=2 (clearly bad answer)."""
        assert decide_quality(BAD_FACTUALITY_SCORES) == 2

    def test_factuality_2_overrides_decent_overall(self):
        """Even if overall looks OK, factuality<3 must force downgrade."""
        scores = {"factuality": 2, "actionability": 5, "fluency": 5, "overall": 4}
        assert decide_quality(scores) == 2

    def test_factuality_1_downgrades(self):
        scores = {"factuality": 1, "actionability": 3, "fluency": 3, "overall": 2}
        assert decide_quality(scores) == 2

    def test_overall_4_but_factuality_3_keeps(self):
        """overall=4 but factuality=3 (not >=4) → should stay at quality=3."""
        scores = {"factuality": 3, "actionability": 4, "fluency": 4, "overall": 4}
        assert decide_quality(scores) == 3

    def test_factuality_4_but_overall_3_keeps(self):
        """factuality=4 but overall=3 (not >=4) → should stay at quality=3."""
        scores = {"factuality": 4, "actionability": 3, "fluency": 3, "overall": 3}
        assert decide_quality(scores) == 3

    def test_all_fives_promotes(self):
        scores = {"factuality": 5, "actionability": 5, "fluency": 5, "overall": 5}
        assert decide_quality(scores) == 4

    def test_all_ones_downgrades(self):
        scores = {"factuality": 1, "actionability": 1, "fluency": 1, "overall": 1}
        assert decide_quality(scores) == 2


class TestDecideQualityPerTaskRubric:
    """Per-task-type rubric: factual Q&A sources gate on factuality+fluency only.

    Core regression: a perfect factual answer like "1月客单价¥152.3，2月降至¥143.7，
    环比下降5.6%" gets factuality=5, fluency=5, but actionability=2 (it's a data
    retrieval, not advice).  With the old analytical rubric this scored overall≈3
    and was wrongly demoted.  With the per-task rubric it correctly promotes.
    """

    # -- factual Q&A sources set --

    def test_factual_qa_sources_contains_chat_qa(self):
        assert "chat_qa" in _FACTUAL_QA_SOURCES

    def test_factual_qa_sources_contains_intent_llm(self):
        assert "intent_llm" in _FACTUAL_QA_SOURCES

    # -- THE KEY REGRESSION TEST: correct factual answer not wrongly demoted --

    def test_factual_qa_high_factuality_low_actionability_PROMOTES(self):
        """chat_qa row: factuality=5, fluency=5, actionability=2, overall=4 → PROMOTED.

        This was the bug: actionability=2 dragged overall to ≤3 causing demotion.
        Under the per-task rubric, actionability is ignored for factual Q&A.
        """
        scores = {"factuality": 5, "actionability": 2, "fluency": 5, "overall": 4}
        assert decide_quality(scores, source="chat_qa") == 4, (
            "Factual Q&A with factuality=5+fluency=5 must PROMOTE even if actionability=2"
        )

    def test_intent_llm_high_factuality_low_actionability_PROMOTES(self):
        """intent_llm row with same pattern must also promote."""
        scores = {"factuality": 5, "actionability": 1, "fluency": 4, "overall": 3}
        assert decide_quality(scores, source="intent_llm") == 4

    def test_factual_qa_borderline_fact4_fluency4_PROMOTES(self):
        """Exactly fact=4 + fluency=4 → promote (borderline)."""
        scores = {"factuality": 4, "actionability": 2, "fluency": 4, "overall": 3}
        assert decide_quality(scores, source="chat_qa") == 4

    def test_factual_qa_fact4_fluency3_STAYS(self):
        """fact=4 but fluency=3 (below threshold) → stays at quality=3."""
        scores = {"factuality": 4, "actionability": 5, "fluency": 3, "overall": 4}
        assert decide_quality(scores, source="chat_qa") == 3

    def test_factual_qa_fact3_fluency5_STAYS(self):
        """fact=3 (below threshold) → stays at quality=3, not promoted."""
        scores = {"factuality": 3, "actionability": 5, "fluency": 5, "overall": 4}
        assert decide_quality(scores, source="chat_qa") == 3

    def test_factual_qa_bad_factuality_DEMOTES(self):
        """Demotion rule is universal: fact<3 → quality=2 even for chat_qa."""
        scores = {"factuality": 2, "actionability": 5, "fluency": 5, "overall": 4}
        assert decide_quality(scores, source="chat_qa") == 2

    # -- analytical sources still use old three-axis gate --

    def test_analytical_high_overall_and_fact_PROMOTES(self):
        """Non-factual-QA source keeps overall+factuality gate."""
        scores = {"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}
        assert decide_quality(scores, source="chart_insight") == 4

    def test_analytical_low_actionability_low_overall_STAYS(self):
        """Non-factual-QA source: low actionability dragging overall → stays at 3."""
        scores = {"factuality": 5, "actionability": 2, "fluency": 5, "overall": 3}
        assert decide_quality(scores, source="agent_insight") == 3

    def test_analytical_no_source_uses_three_axis_gate(self):
        """No source (None) falls through to analytical rubric."""
        scores = {"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}
        assert decide_quality(scores, source=None) == 4

    def test_analytical_unknown_source_uses_three_axis_gate(self):
        """Unknown source falls through to analytical rubric."""
        scores = {"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}
        assert decide_quality(scores, source="materialization") == 4

    # -- build_judge_prompt carries per-task note for factual sources --

    def test_build_judge_prompt_factual_qa_contains_na_note(self):
        """For chat_qa source, prompt must contain the actionability N/A note."""
        _, user_prompt = build_judge_prompt("Q: 客单价?", "¥152.3", source="chat_qa")
        assert "事实性问答" in user_prompt or "N/A" in user_prompt or "不适用" in user_prompt, (
            "Prompt for factual Q&A sources must include a note that actionability is N/A"
        )

    def test_build_judge_prompt_analytical_no_na_note(self):
        """For analytical sources, the N/A note must not appear."""
        _, user_prompt = build_judge_prompt("context", "answer", source="chart_insight")
        # Should not have the special factual Q&A override note
        assert "事实性问答" not in user_prompt

    def test_build_judge_prompt_no_source_no_na_note(self):
        """Without a source, no N/A note is added."""
        _, user_prompt = build_judge_prompt("context", "answer")
        assert "事实性问答" not in user_prompt


# ---------------------------------------------------------------------------
# TestIdempotency
# ---------------------------------------------------------------------------

class TestIdempotency:
    """Verify that already-judged rows (with metadata.judge) are excluded.

    The idempotency is enforced at the DB query level via:
        WHERE quality = 3 AND (metadata IS NULL OR NOT (metadata ? 'judge'))

    We test by mocking the pool.acquire() context manager and verifying
    that the SQL sent to the DB contains the NOT (metadata ? 'judge') guard.
    """

    @pytest.mark.asyncio
    async def test_judged_rows_excluded_by_sql(self):
        """The fetch query must include the idempotency guard in SQL."""
        from scripts.corpus_judge import _FETCH_SQL
        assert "metadata ? 'judge'" in _FETCH_SQL or "NOT (metadata ? 'judge')" in _FETCH_SQL, (
            "Fetch SQL must include NOT (metadata ? 'judge') to skip already-judged rows"
        )

    @pytest.mark.asyncio
    async def test_fetch_passes_limit_to_db(self):
        """fetch_unjudged_rows should pass the limit as $1 parameter."""
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=[])

        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)

        rows = await fetch_unjudged_rows(mock_pool, limit=42)  # noqa: F841

        mock_conn.fetch.assert_called_once()
        call_args = mock_conn.fetch.call_args
        # First positional arg is the SQL, second is the limit
        assert 42 in call_args[0] or 42 in call_args.args, (
            "Limit must be passed as parameter to the DB query"
        )

    @pytest.mark.asyncio
    async def test_fetch_with_source_filter(self):
        """When source is given, it should appear in the SQL query."""
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=[])

        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()
        mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
        mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)

        await fetch_unjudged_rows(mock_pool, limit=10, source="intent_llm")

        mock_conn.fetch.assert_called_once()
        call_args = mock_conn.fetch.call_args
        # "intent_llm" should be passed as a bind parameter
        all_args = list(call_args.args) + list(call_args.kwargs.values())
        assert "intent_llm" in all_args, (
            "Source filter 'intent_llm' must be passed as a DB bind parameter"
        )

    @pytest.mark.asyncio
    async def test_none_pool_returns_empty(self):
        """If pool is None (e.g. no DSN), fetch_unjudged_rows returns []."""
        rows = await fetch_unjudged_rows(None, limit=100)
        assert rows == []


# ---------------------------------------------------------------------------
# TestDryRun
# ---------------------------------------------------------------------------

class TestDryRun:
    """Verify that dry-run mode prints counts but makes no LLM calls or DB writes."""

    @pytest.mark.asyncio
    async def test_dry_run_no_llm_calls(self, capsys):
        """In dry-run mode, the LLM judge (call_chain) must never be called."""
        # Patch fetch_unjudged_rows to return fake rows
        fake_rows = [
            {
                "id": 1,
                "input_text": SAMPLE_INPUT_TEXT,
                "teacher_output": SAMPLE_TEACHER_OUTPUT,
                "metadata": None,
            },
            {
                "id": 2,
                "input_text": "工厂库存查询",
                "teacher_output": "库存充足",
                "metadata": None,
            },
        ]

        with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
            with patch("scripts.corpus_judge.call_chain", new=AsyncMock()) as mock_llm:
                with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=None)):
                    await run_judge(limit=100, dry_run=True)

        # LLM must not be called in dry-run
        mock_llm.assert_not_called()

    @pytest.mark.asyncio
    async def test_dry_run_no_db_writes(self, capsys):
        """In dry-run mode, apply_judge_result must never be called."""
        fake_rows = [
            {
                "id": 1,
                "input_text": SAMPLE_INPUT_TEXT,
                "teacher_output": SAMPLE_TEACHER_OUTPUT,
                "metadata": None,
            }
        ]

        with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
            with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_write:
                with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=None)):
                    await run_judge(limit=100, dry_run=True)

        mock_write.assert_not_called()

    @pytest.mark.asyncio
    async def test_dry_run_prints_row_count(self, capsys):
        """Dry-run should print how many rows it found."""
        fake_rows = [
            {
                "id": i,
                "input_text": f"input {i}",
                "teacher_output": f"output {i}",
                "metadata": None,
            }
            for i in range(5)
        ]

        with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
            with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=None)):
                await run_judge(limit=50, dry_run=True)

        captured = capsys.readouterr()
        assert "5" in captured.out, "Dry-run output should mention the number of rows found"
        assert "DRY-RUN" in captured.out.upper() or "dry-run" in captured.out.lower()

    @pytest.mark.asyncio
    async def test_dry_run_pool_never_created(self):
        """In dry-run mode, no DB pool should be created."""
        with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=[])):
            with patch("scripts.corpus_judge._make_pool", new=AsyncMock()) as mock_pool_fn:
                await run_judge(limit=50, dry_run=True)

        mock_pool_fn.assert_not_called()


# ---------------------------------------------------------------------------
# TestJudgeRow (integration of judge_row function)
# ---------------------------------------------------------------------------

class TestJudgeRow:
    """Test the judge_row async function that calls the LLM and parses scores."""

    @pytest.mark.asyncio
    async def test_successful_judge_returns_scores(self):
        """When LLM returns valid JSON, judge_row should return parsed scores."""
        mock_response = {
            "choices": [
                {
                    "message": {
                        "content": '{"factuality": 5, "actionability": 4, "fluency": 5, "overall": 5}'
                    }
                }
            ]
        }

        with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=mock_response)):
            scores = await judge_row(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)

        assert scores is not None
        assert scores == GOOD_SCORES

    @pytest.mark.asyncio
    async def test_llm_error_returns_none(self):
        """When LLM raises RuntimeError (all providers exhausted), return None."""
        with patch(
            "scripts.corpus_judge.call_chain",
            new=AsyncMock(side_effect=RuntimeError("All providers exhausted")),
        ):
            scores = await judge_row(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)

        assert scores is None

    @pytest.mark.asyncio
    async def test_invalid_json_response_returns_none(self):
        """When LLM returns garbage, parse_judge_scores returns None, judge_row returns None."""
        mock_response = {
            "choices": [{"message": {"content": "这个问题我无法评分，请提供更多信息。"}}]
        }

        with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=mock_response)):
            scores = await judge_row(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)

        assert scores is None

    @pytest.mark.asyncio
    async def test_empty_choices_returns_none(self):
        """When LLM response has no choices, return None gracefully."""
        mock_response = {"choices": []}

        with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=mock_response)):
            scores = await judge_row(SAMPLE_INPUT_TEXT, SAMPLE_TEACHER_OUTPUT)

        assert scores is None


# ---------------------------------------------------------------------------
# TestFullRunFlow (end-to-end with mocks)
# ---------------------------------------------------------------------------

class TestFullRunFlow:
    """Test the run_judge async function with fully mocked DB + LLM."""

    @pytest.mark.asyncio
    async def test_promoted_rows_get_quality_4(self):
        """Rows scoring overall>=4 & factuality>=4 should be updated to quality=4."""
        fake_rows = [
            {
                "id": 101,
                "input_text": SAMPLE_INPUT_TEXT,
                "teacher_output": SAMPLE_TEACHER_OUTPUT,
                "metadata": None,
            }
        ]
        good_llm_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}]
        }

        mock_pool = MagicMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=good_llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        mock_apply.assert_called_once()
        call_args = mock_apply.call_args
        # apply_judge_result(pool, row_id, new_quality, scores)
        # positional args: [0]=pool, [1]=row_id, [2]=new_quality, [3]=scores
        new_quality = call_args.args[2]
        assert new_quality == 4

    @pytest.mark.asyncio
    async def test_bad_factuality_rows_get_quality_2(self):
        """Rows with factuality<3 should be updated to quality=2."""
        fake_rows = [
            {
                "id": 202,
                "input_text": "输入上下文",
                "teacher_output": "严重偏离事实的回答",
                "metadata": None,
            }
        ]
        bad_llm_response = {
            "choices": [{"message": {"content": json.dumps(BAD_FACTUALITY_SCORES)}}]
        }

        mock_pool = MagicMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=bad_llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        mock_apply.assert_called_once()
        # apply_judge_result(pool, row_id, new_quality, scores)
        new_quality = mock_apply.call_args.args[2]
        assert new_quality == 2

    @pytest.mark.asyncio
    async def test_medium_score_rows_stay_quality_3(self):
        """Rows with medium scores (not promote, not downgrade) stay at quality=3."""
        fake_rows = [
            {
                "id": 303,
                "input_text": "输入",
                "teacher_output": "中等回答",
                "metadata": None,
            }
        ]
        medium_llm_response = {
            "choices": [{"message": {"content": json.dumps(MEDIUM_SCORES)}}]
        }

        mock_pool = MagicMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=medium_llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        mock_apply.assert_called_once()
        # apply_judge_result(pool, row_id, new_quality, scores)
        new_quality = mock_apply.call_args.args[2]
        assert new_quality == 3

    @pytest.mark.asyncio
    async def test_scores_stored_in_metadata(self):
        """Judge scores should be passed to apply_judge_result as the scores dict."""
        fake_rows = [
            {
                "id": 404,
                "input_text": SAMPLE_INPUT_TEXT,
                "teacher_output": SAMPLE_TEACHER_OUTPUT,
                "metadata": None,
            }
        ]
        llm_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}]
        }

        mock_pool = MagicMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain", new=AsyncMock(return_value=llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        # apply_judge_result(pool, row_id, new_quality, scores)
        # positional args: [0]=pool, [1]=row_id, [2]=new_quality, [3]=scores
        scores_arg = mock_apply.call_args.args[3]
        assert scores_arg == GOOD_SCORES

    @pytest.mark.asyncio
    async def test_failed_llm_calls_are_skipped(self, capsys):
        """Rows where LLM fails should be counted as 'failed' and not written."""
        fake_rows = [
            {
                "id": 505,
                "input_text": "上下文",
                "teacher_output": "输出",
                "metadata": None,
            }
        ]

        mock_pool = MagicMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=fake_rows)):
                with patch(
                    "scripts.corpus_judge.call_chain",
                    new=AsyncMock(side_effect=RuntimeError("provider exhausted")),
                ):
                    with patch("scripts.corpus_judge.apply_judge_result", new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        # No DB writes should happen for failed LLM calls
        mock_apply.assert_not_called()

    @pytest.mark.asyncio
    async def test_pool_closed_after_run(self):
        """Pool should be closed after the run completes."""
        mock_pool = AsyncMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows", new=AsyncMock(return_value=[])):
                await run_judge(limit=10, dry_run=False)

        mock_pool.close.assert_called_once()


# ---------------------------------------------------------------------------
# TestRunJudgePassesSourceToDecideQuality (per-task rubric integration)
# ---------------------------------------------------------------------------

class TestRunJudgePerTaskRubricIntegration:
    """Verify run_judge threads source from the DB row into decide_quality.

    The key case: a chat_qa row with factuality=5/fluency=5/actionability=2
    must be PROMOTED (quality=4), not wrongly demoted to quality=3.
    """

    @pytest.mark.asyncio
    async def test_chat_qa_row_with_low_actionability_is_promoted(self):
        """chat_qa: fact=5, action=2, fluency=5 → quality=4 (actionability ignored)."""
        factual_qa_scores = {
            "factuality": 5,
            "actionability": 2,
            "fluency": 5,
            "overall": 4,
        }
        fake_rows = [
            {
                "id": 601,
                "input_text": "1月客单价是多少?",
                "teacher_output": "1月客单价¥152.3，2月降至¥143.7，环比下降5.6%",
                "metadata": None,
                "source": "chat_qa",  # ← factual Q&A source
            }
        ]
        llm_response = {
            "choices": [{"message": {"content": json.dumps(factual_qa_scores)}}]
        }

        mock_pool = MagicMock()
        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result",
                               new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        mock_apply.assert_called_once()
        new_quality = mock_apply.call_args.args[2]
        assert new_quality == 4, (
            f"chat_qa with factuality=5/fluency=5 must promote to quality=4, got {new_quality}. "
            "This is the key regression test for the per-task rubric fix."
        )

    @pytest.mark.asyncio
    async def test_analytical_row_with_low_actionability_stays_at_3(self):
        """Non-factual-QA: fact=5, action=2, fluency=5, overall=3 → quality=3."""
        analytical_scores = {
            "factuality": 5,
            "actionability": 2,
            "fluency": 5,
            "overall": 3,  # overall dragged down by actionability
        }
        fake_rows = [
            {
                "id": 602,
                "input_text": "分析生产损耗原因",
                "teacher_output": "损耗率25%",
                "metadata": None,
                "source": "agent_insight",  # ← analytical source
            }
        ]
        llm_response = {
            "choices": [{"message": {"content": json.dumps(analytical_scores)}}]
        }

        mock_pool = MagicMock()
        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_unjudged_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=llm_response)):
                    with patch("scripts.corpus_judge.apply_judge_result",
                               new=AsyncMock()) as mock_apply:
                        await run_judge(limit=10, dry_run=False)

        mock_apply.assert_called_once()
        new_quality = mock_apply.call_args.args[2]
        assert new_quality == 3, (
            f"Analytical row with overall=3 must stay at quality=3, got {new_quality}"
        )
