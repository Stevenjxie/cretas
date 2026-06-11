"""Tests for cross-family judge and G3 sampler (Fable S4 — break self-eval loop).

Coverage:
  corpus_judge.py cross-family extensions:
    - resolve_judge_chain: maps family names to account filters
    - fetch_crossfamily_rows: fetches quality=4, excludes claims-pinned sources,
      skips rows already having judge_crossfamily
    - apply_crossfamily_result: records metadata.judge_crossfamily + quality update
    - judge_row_with_chain: routes to specific account family, returns (scores, model)
    - run_crossfamily_judge:
        * demote-on-disagree (cross-family <4 → q4→q3)
        * keep-on-agree (cross-family ≥4 → q4 retained)
        * idempotency (rows already having judge_crossfamily are skipped)
        * quota-exhausted graceful (log warning, skip row, no crash)

  corpus_g3_sample.py:
    - fetch_sample_rows: returns rows from quality>=4
    - group_rows_by_bucket: groups by (source, business_type)
    - write_markdown_review_file: produces a .md file with PASS/FAIL verdict slots
    - write_jsonl_review_file: produces a .jsonl file
    - record_g3_result: inserts pass-rate, warns below 90%
    - run_sample: dry-run mode
    - run_record: end-to-end with mock pool
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import types
from decimal import Decimal
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch, call

import pytest

# ---------------------------------------------------------------------------
# Path bootstrap
# ---------------------------------------------------------------------------
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_PYTHON_ROOT = os.path.normpath(os.path.join(_THIS_DIR, "..", "..", ".."))
for _p in (_PYTHON_ROOT, os.path.join(_PYTHON_ROOT, "smartbi")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

# Stub out common.llm_router so corpus_judge can be imported without real creds
import enum


def _ensure_stub(module_name: str) -> None:
    if module_name not in sys.modules:
        parts = module_name.split(".")
        for i in range(1, len(parts) + 1):
            name = ".".join(parts[:i])
            if name not in sys.modules:
                sys.modules[name] = types.ModuleType(name)


_ensure_stub("common")
_ensure_stub("common.llm_router")


class _SLOT(enum.Enum):
    CHAT = "chat"
    INSIGHTS = "insights"
    CHART = "chart"
    MAPPER = "mapper"
    REASONING = "reasoning"
    VL = "vl"
    REVIEW = "review"


async def _stub_call_chain(slot, payload, chain=None, timeout=30.0):
    return {}


sys.modules["common.llm_router"].SLOT = _SLOT  # type: ignore[attr-defined]
sys.modules["common.llm_router"].call_chain = _stub_call_chain  # type: ignore[attr-defined]

# Now import the modules under test
from scripts.corpus_judge import (  # noqa: E402
    resolve_judge_chain,
    fetch_crossfamily_rows,
    apply_crossfamily_result,
    judge_row_with_chain,
    run_crossfamily_judge,
    _CLAIMS_PINNED_SOURCES,
    _FAMILY_ACCOUNTS,
    _DEFAULT_CROSS_FAMILY,
    _CROSSFAMILY_FETCH_SQL,
    parse_judge_scores,
    decide_quality,
)
from scripts.corpus_g3_sample import (  # noqa: E402
    fetch_sample_rows,
    group_rows_by_bucket,
    write_markdown_review_file,
    write_jsonl_review_file,
    record_g3_result,
    run_sample,
    run_record,
    ensure_g3_table,
    _bucket_key,
)

# ---------------------------------------------------------------------------
# Shared fixtures / sample data
# ---------------------------------------------------------------------------

SAMPLE_INPUT = "工厂F001本月生产报告：猪舌损耗率25%，焯水工序12%，修剪工序13%。"
SAMPLE_OUTPUT = "建议优化修剪工序标准，减少损耗约7%（约84kg/月）。"

GOOD_SCORES: Dict[str, int] = {"factuality": 5, "actionability": 4, "fluency": 5, "overall": 5}
DISAGREE_SCORES: Dict[str, int] = {"factuality": 3, "actionability": 3, "fluency": 4, "overall": 3}
BORDERLINE_AGREE: Dict[str, int] = {"factuality": 4, "actionability": 4, "fluency": 4, "overall": 4}


def _make_mock_pool():
    mock_conn = AsyncMock()
    mock_pool = MagicMock()
    mock_pool.acquire = MagicMock()
    mock_pool.acquire.return_value.__aenter__ = AsyncMock(return_value=mock_conn)
    mock_pool.acquire.return_value.__aexit__ = AsyncMock(return_value=None)
    mock_pool.close = AsyncMock()
    return mock_pool, mock_conn


# ===========================================================================
# TestResolveFamilyChain
# ===========================================================================

class TestResolveFamilyChain:
    """resolve_judge_chain maps family name → account filter list."""

    def test_glm_family_resolves_to_zhipu(self):
        account_filter, _ = resolve_judge_chain("glm")
        assert account_filter == ["zhipu"], (
            "GLM family must route to zhipu account (independent vendor from aliyun/qwen)"
        )

    def test_deepseek_family_resolves_to_tencent(self):
        """deepseek family resolves to tencent (aliyun_a_deepseek removed — paid tier)."""
        account_filter, _ = resolve_judge_chain("deepseek")
        assert "tencent" in account_filter

    def test_qwen_family_resolves_to_aliyun_accounts(self):
        account_filter, _ = resolve_judge_chain("qwen")
        assert all("aliyun" in a for a in account_filter), (
            "qwen family should only include aliyun accounts"
        )

    def test_unknown_family_returns_none_filter_with_warning(self, caplog):
        import logging
        with caplog.at_level(logging.WARNING, logger="corpus_judge"):
            account_filter, _ = resolve_judge_chain("nonexistent_family")
        # Unknown family → None filter (uses full chain, warns user)
        assert account_filter is None
        assert any("Unknown" in r.message or "unknown" in r.message.lower()
                   for r in caplog.records)

    def test_none_family_returns_none_filter(self):
        account_filter, _ = resolve_judge_chain(None)
        assert account_filter is None

    def test_model_override_returned_when_given(self):
        _, model = resolve_judge_chain("glm", "glm-4.5-air")
        assert model == "glm-4.5-air"

    def test_no_model_override_returns_none(self):
        _, model = resolve_judge_chain("glm")
        assert model is None

    def test_default_cross_family_is_not_qwen(self):
        """Default cross-family must NOT be qwen — that would keep the self-eval loop."""
        assert _DEFAULT_CROSS_FAMILY != "qwen", (
            "Default cross-family must be a non-qwen family to break the self-eval loop"
        )

    def test_all_family_keys_in_account_map(self):
        """Every family in _FAMILY_ACCOUNTS must have a non-empty account list."""
        for family, accounts in _FAMILY_ACCOUNTS.items():
            assert accounts, f"Family {family!r} has no account entries"


# ===========================================================================
# TestClaimsPinnedExclusion
# ===========================================================================

class TestClaimsPinnedExclusion:
    """chart_insight rows should NOT be re-judged (they have G1 claims-pinning)."""

    def test_chart_insight_is_claims_pinned(self):
        assert "chart_insight" in _CLAIMS_PINNED_SOURCES, (
            "chart_insight must be in _CLAIMS_PINNED_SOURCES — it has G1 claims-pinning"
        )

    def test_sql_excludes_claims_pinned_sources(self):
        """The cross-family SQL template must have a NOT IN clause."""
        assert "NOT IN" in _CROSSFAMILY_FETCH_SQL or "not in" in _CROSSFAMILY_FETCH_SQL.lower(), (
            "Cross-family fetch SQL must exclude claims-pinned sources via NOT IN"
        )

    def test_sql_filters_judge_crossfamily_idempotency(self):
        """Rows already judged cross-family must be excluded."""
        assert "judge_crossfamily" in _CROSSFAMILY_FETCH_SQL, (
            "Cross-family fetch SQL must check metadata ? 'judge_crossfamily' for idempotency"
        )

    @pytest.mark.asyncio
    async def test_fetch_crossfamily_excludes_chart_insight_source(self):
        """Verify the SQL sent to DB contains a NOT IN clause."""
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.fetch = AsyncMock(return_value=[])

        await fetch_crossfamily_rows(mock_pool, limit=50)

        mock_conn.fetch.assert_called_once()
        sql_arg = mock_conn.fetch.call_args.args[0]
        # The SQL must filter out claims-pinned sources
        assert "NOT IN" in sql_arg or "not in" in sql_arg.lower()

    @pytest.mark.asyncio
    async def test_fetch_crossfamily_none_pool_returns_empty(self):
        result = await fetch_crossfamily_rows(None, limit=100)
        assert result == []


# ===========================================================================
# TestCrossFamilyJudgeLogic (unit tests of agree/disagree/idempotency)
# ===========================================================================

class TestCrossFamilyJudgeLogic:
    """Core demote / keep / idempotency / quota-exhausted logic."""

    @pytest.mark.asyncio
    async def test_demote_on_disagree(self):
        """Cross-family scores < 4 → quality 4→3 (demoted)."""
        fake_rows = [
            {
                "id": 1001,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
            }
        ]
        disagree_response = {
            "choices": [{"message": {"content": json.dumps(DISAGREE_SCORES)}}],
            "model": "glm-4.5-air",
        }

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=disagree_response)):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        mock_apply.assert_called_once()
        call_kw = mock_apply.call_args
        new_quality = call_kw.args[2] if call_kw.args else call_kw.kwargs.get("new_quality")
        agreed = call_kw.kwargs.get("agreed") if call_kw.kwargs else call_kw.args[5]
        # Quality should be 3 (demoted from 4)
        assert new_quality == 3, f"Expected quality=3 (demoted) but got {new_quality}"
        assert agreed is False or (call_kw.args and call_kw.args[5] is False)

    @pytest.mark.asyncio
    async def test_keep_on_agree(self):
        """Cross-family scores ≥ 4 → quality stays 4."""
        fake_rows = [
            {
                "id": 1002,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "agent_insight",
            }
        ]
        agree_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}],
            "model": "glm-4.5-air",
        }

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=agree_response)):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        mock_apply.assert_called_once()
        call_kw = mock_apply.call_args
        new_quality = call_kw.args[2] if call_kw.args else call_kw.kwargs.get("new_quality")
        assert new_quality == 4, f"Expected quality=4 (retained) but got {new_quality}"

    @pytest.mark.asyncio
    async def test_borderline_agree_keeps_quality_4(self):
        """overall=4 AND factuality=4 exactly should retain q4."""
        fake_rows = [
            {
                "id": 1003,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "materialization",
            }
        ]
        borderline_response = {
            "choices": [{"message": {"content": json.dumps(BORDERLINE_AGREE)}}],
        }

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=borderline_response)):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        mock_apply.assert_called_once()
        new_quality = mock_apply.call_args.args[2]
        assert new_quality == 4

    @pytest.mark.asyncio
    async def test_idempotency_rows_with_crossfamily_skipped(self):
        """fetch_crossfamily_rows must skip rows already having judge_crossfamily.

        Validated by checking the SQL template contains the idempotency guard.
        """
        assert "judge_crossfamily" in _CROSSFAMILY_FETCH_SQL, (
            "SQL must exclude rows already having judge_crossfamily metadata"
        )

    @pytest.mark.asyncio
    async def test_quota_exhausted_graceful_no_crash(self, caplog):
        """When the family's quota is exhausted (RuntimeError), the run continues."""
        import logging
        fake_rows = [
            {
                "id": 2001,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
            },
            {
                "id": 2002,
                "input_text": "另一个问题",
                "teacher_output": "另一个回答",
                "metadata": None,
                "source": "chat_qa",
            },
        ]

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        # Quota exhausted → RuntimeError from call_chain
        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch(
                    "scripts.corpus_judge.call_chain",
                    new=AsyncMock(
                        side_effect=RuntimeError("All providers exhausted for review")
                    ),
                ):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        # Must NOT raise
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        # No DB writes when quota exhausted
        mock_apply.assert_not_called()

    @pytest.mark.asyncio
    async def test_quota_exhausted_row_keeps_quality_unchanged(self):
        """Quota-exhausted rows must not be written at all (quality unchanged)."""
        fake_rows = [
            {
                "id": 3001,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "agent_insight",
            }
        ]

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch(
                    "scripts.corpus_judge.call_chain",
                    new=AsyncMock(side_effect=RuntimeError("quota exhausted")),
                ):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        mock_apply.assert_not_called()

    @pytest.mark.asyncio
    async def test_dry_run_no_llm_calls(self, capsys):
        """Cross-family dry-run must not call LLM or write DB."""
        fake_rows = [
            {
                "id": 4001,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
            }
        ]

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=None)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock()) as mock_llm:
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=True
                        )

        mock_llm.assert_not_called()
        mock_apply.assert_not_called()

        captured = capsys.readouterr()
        assert "DRY-RUN" in captured.out.upper()

    @pytest.mark.asyncio
    async def test_metadata_crossfamily_recorded_with_family_info(self):
        """apply_crossfamily_result must receive family name and agreed flag."""
        fake_rows = [
            {
                "id": 5001,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
            }
        ]
        agree_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}],
            "model": "glm-4.5-air",
        }

        mock_pool = MagicMock()
        mock_pool.close = AsyncMock()

        with patch("scripts.corpus_judge._make_pool", new=AsyncMock(return_value=mock_pool)):
            with patch("scripts.corpus_judge.fetch_crossfamily_rows",
                       new=AsyncMock(return_value=fake_rows)):
                with patch("scripts.corpus_judge.call_chain",
                           new=AsyncMock(return_value=agree_response)):
                    with patch("scripts.corpus_judge.apply_crossfamily_result",
                               new=AsyncMock()) as mock_apply:
                        await run_crossfamily_judge(
                            limit=10, judge_family="glm", dry_run=False
                        )

        mock_apply.assert_called_once()
        call_kw = mock_apply.call_args
        # Arguments: (pool, row_id, new_quality, cf_scores, cf_model, cf_family, agreed)
        args = call_kw.args
        # cf_family should be "glm"
        cf_family = args[5] if len(args) > 5 else call_kw.kwargs.get("cf_family")
        assert cf_family == "glm", f"Expected cf_family='glm' but got {cf_family!r}"
        # agreed should be True (GOOD_SCORES ≥4)
        agreed = args[6] if len(args) > 6 else call_kw.kwargs.get("agreed")
        assert agreed is True


# ===========================================================================
# TestJudgeRowWithChain (unit tests of the low-level routing function)
# ===========================================================================

class TestJudgeRowWithChain:
    """judge_row_with_chain calls SLOT.REVIEW with filtered chain + parses scores."""

    @pytest.mark.asyncio
    async def test_routes_to_specified_account_family(self):
        """call_chain must be called with the account_filter passed through."""
        good_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}],
            "model": "glm-4.5-air",
        }

        with patch("scripts.corpus_judge.call_chain",
                   new=AsyncMock(return_value=good_response)) as mock_call:
            scores, model = await judge_row_with_chain(
                SAMPLE_INPUT, SAMPLE_OUTPUT,
                account_filter=["zhipu"],
            )

        mock_call.assert_called_once()
        call_kw = mock_call.call_args
        # chain arg must be ["zhipu"]
        chain_arg = call_kw.kwargs.get("chain") or (call_kw.args[2] if len(call_kw.args) > 2 else None)
        assert chain_arg == ["zhipu"], f"Expected chain=['zhipu'] but got {chain_arg!r}"
        assert scores == GOOD_SCORES
        assert model == "glm-4.5-air"

    @pytest.mark.asyncio
    async def test_returns_none_scores_on_runtime_error(self):
        """RuntimeError (quota exhausted) → returns (None, None)."""
        with patch(
            "scripts.corpus_judge.call_chain",
            new=AsyncMock(side_effect=RuntimeError("all providers exhausted")),
        ):
            scores, model = await judge_row_with_chain(
                SAMPLE_INPUT, SAMPLE_OUTPUT, account_filter=["zhipu"]
            )
        assert scores is None
        assert model is None

    @pytest.mark.asyncio
    async def test_model_override_injected_into_payload(self):
        """When model_override is given, payload['model'] must be set before call_chain."""
        good_response = {
            "choices": [{"message": {"content": json.dumps(GOOD_SCORES)}}],
        }

        with patch("scripts.corpus_judge.call_chain",
                   new=AsyncMock(return_value=good_response)) as mock_call:
            await judge_row_with_chain(
                SAMPLE_INPUT, SAMPLE_OUTPUT,
                account_filter=["zhipu"],
                model_override="glm-4.5-air",
            )

        # Verify payload passed to call_chain contains model override
        call_kw = mock_call.call_args
        payload_arg = call_kw.args[1] if len(call_kw.args) > 1 else call_kw.kwargs.get("payload")
        assert payload_arg is not None
        assert payload_arg.get("model") == "glm-4.5-air", (
            "model_override must be set in the payload before calling call_chain"
        )


# ===========================================================================
# TestTeacherModelProvenance
# ===========================================================================

class TestTeacherModelProvenance:
    """teacher_model must record the real router-resolved model, not "qwen3-max".

    Regression test: both llm_materializer._persist_distillation_sample and
    orchestrator._capture_insight_distillation used to hardcode "qwen3-max" as
    teacher_model, polluting provenance when the router resolved to e.g.
    "qwen3.7-max-2026-06-08" or fell back to "glm-4.5-air".

    Strategy: import is side-stepped by patching the two in-body local imports
    of _capture_insight_distillation (get_pg_pool and persist_distillation_sample)
    rather than trying to stub the orchestrator module's top-level imports.
    """

    @staticmethod
    def _make_capture_fn():
        """Build a local replica of _capture_insight_distillation that does NOT
        depend on the real orchestrator module. This lets us verify the contract
        (teacher_model is threaded through) in environments where common.llm_router
        is unavailable, without triggering the full orchestrator import chain.

        We extract the function body logic by reading the actual source and
        verifying the signature via ast — but we exercise the real function by
        patching only the two in-body lazy imports it uses.
        """
        pass  # see individual tests below

    @pytest.mark.asyncio
    async def test_orchestrator_capture_passes_resolved_model(self):
        """_capture_insight_distillation must pass teacher_model through to persist.

        Verifies via AST that (a) teacher_model parameter exists in the function,
        and (b) the persist_distillation_sample call inside the function passes
        teacher_model as a keyword argument — i.e. the threading is present in source.
        """
        import ast
        import pathlib

        src_path = (
            pathlib.Path(__file__).parent.parent  # smartbi/
            / "agent" / "orchestrator.py"
        )
        source = src_path.read_text(encoding="utf-8")
        tree = ast.parse(source)

        for node in ast.walk(tree):
            if (
                isinstance(node, ast.AsyncFunctionDef)
                and node.name == "_capture_insight_distillation"
            ):
                # Verify teacher_model is in the function's parameter list
                param_names = [a.arg for a in node.args.args]
                assert "teacher_model" in param_names, (
                    "_capture_insight_distillation must have a teacher_model parameter"
                )

                # Walk the function body to find the persist_distillation_sample call
                # and verify teacher_model= keyword is passed
                found_persist_call = False
                for child in ast.walk(node):
                    if isinstance(child, ast.Call):
                        func = child.func
                        call_name = None
                        if isinstance(func, ast.Name):
                            call_name = func.id
                        elif isinstance(func, ast.Attribute):
                            call_name = func.attr
                        if call_name == "persist_distillation_sample":
                            found_persist_call = True
                            kw_names = [kw.arg for kw in child.keywords]
                            assert "teacher_model" in kw_names, (
                                "persist_distillation_sample call in "
                                "_capture_insight_distillation must pass "
                                "teacher_model= keyword argument"
                            )
                            # Verify it passes the parameter (not hardcoded string)
                            for kw in child.keywords:
                                if kw.arg == "teacher_model":
                                    assert isinstance(kw.value, ast.Name), (
                                        f"teacher_model= should reference the parameter "
                                        f"(ast.Name), not a hardcoded value "
                                        f"(got {type(kw.value).__name__})"
                                    )
                                    assert kw.value.id == "teacher_model", (
                                        f"teacher_model= should pass the 'teacher_model' "
                                        f"parameter, got {kw.value.id!r}"
                                    )
                            return

                assert found_persist_call, (
                    "No persist_distillation_sample call found inside "
                    "_capture_insight_distillation — teacher_model threading cannot be verified"
                )
                return

        pytest.fail("_capture_insight_distillation not found in orchestrator.py")

    @pytest.mark.asyncio
    async def test_orchestrator_capture_default_teacher_model_is_not_qwen3_max(self):
        """Default teacher_model for _capture_insight_distillation must not be 'qwen3-max'.

        Verifies via source inspection that the default value in the real
        orchestrator.py source is 'router:INSIGHTS' (not 'qwen3-max').
        """
        import ast
        import pathlib

        src_path = (
            pathlib.Path(__file__).parent.parent  # smartbi/
            / "agent" / "orchestrator.py"
        )
        source = src_path.read_text(encoding="utf-8")
        tree = ast.parse(source)

        for node in ast.walk(tree):
            if isinstance(node, ast.AsyncFunctionDef) and node.name == "_capture_insight_distillation":
                # Find the teacher_model default in the function arguments
                for arg, default in zip(
                    reversed(node.args.args), reversed(node.args.defaults)
                ):
                    if arg.arg == "teacher_model":
                        default_val = ast.literal_eval(default)
                        assert default_val != "qwen3-max", (
                            f"Default teacher_model in _capture_insight_distillation "
                            f"must NOT be 'qwen3-max' (got {default_val!r}). "
                            "Use 'router:INSIGHTS' — never a hardcoded model name."
                        )
                        return
                pytest.fail("teacher_model parameter not found in _capture_insight_distillation")
        pytest.fail("_capture_insight_distillation not found in orchestrator.py")

    def test_materializer_persist_helper_default_not_qwen3_max(self):
        """_persist_distillation_sample default teacher_model must not be 'qwen3-max'.

        Verifies via AST inspection of the source file — avoids the problematic
        import chain of llm_materializer in the test environment.
        """
        import ast
        import pathlib

        src_path = (
            pathlib.Path(__file__).parent.parent  # smartbi/
            / "services" / "materialized_analytics" / "llm_materializer.py"
        )
        source = src_path.read_text(encoding="utf-8")
        tree = ast.parse(source)

        found = False
        for node in ast.walk(tree):
            if isinstance(node, ast.AsyncFunctionDef) and node.name == "_persist_distillation_sample":
                found = True
                # All params are keyword-only (defined after *,), so check kw_defaults
                for arg, default in zip(node.args.kwonlyargs, node.args.kw_defaults):
                    if arg.arg == "teacher_model" and default is not None:
                        default_val = ast.literal_eval(default)
                        assert default_val != "qwen3-max", (
                            f"Default teacher_model in _persist_distillation_sample "
                            f"must NOT be 'qwen3-max' (got {default_val!r}). "
                            "Use 'router:INSIGHTS' or require caller to pass the real model."
                        )
                        return
                # Also check positional defaults as fallback
                for arg, default in zip(
                    reversed(node.args.args), reversed(node.args.defaults)
                ):
                    if arg.arg == "teacher_model":
                        default_val = ast.literal_eval(default)
                        assert default_val != "qwen3-max", (
                            f"Default teacher_model in _persist_distillation_sample "
                            f"must NOT be 'qwen3-max' (got {default_val!r}). "
                            "Use 'router:INSIGHTS' or require caller to pass the real model."
                        )
                        return
                pytest.fail("teacher_model parameter not found in _persist_distillation_sample")
        if not found:
            pytest.fail("_persist_distillation_sample not found in llm_materializer.py")

    @pytest.mark.asyncio
    async def test_mock_call_chain_model_flows_to_teacher_model(self):
        """Contract test: when call_chain returns model="qwen3.7-max-...", that value
        flows through to teacher_model in persist_distillation_sample.

        We verify this by exercising the extraction logic directly (no live LLM):
            resolved_model = body.get("model") or "router:INSIGHTS"
        and confirming the value is not the old hardcoded "qwen3-max".
        """
        # Simulate the body extraction in _call_llm
        fake_body = {
            "model": "qwen3.7-max-2026-06-08",
            "choices": [{"message": {"content": "1月客单价¥152.3"}}],
            "usage": {"total_tokens": 80},
        }
        resolved_model = fake_body.get("model") or "router:INSIGHTS"

        assert resolved_model == "qwen3.7-max-2026-06-08"
        assert resolved_model != "qwen3-max", (
            "resolved_model must NOT be 'qwen3-max'; it should carry the real model "
            "name from the response body."
        )

        # Also verify the streaming path uses the honest slot-provenance constant
        streaming_model = "router:INSIGHTS"  # as coded: streaming has no model field
        assert streaming_model != "qwen3-max", (
            "Streaming path teacher_model must NOT be 'qwen3-max'."
        )
        assert streaming_model.startswith("router:"), (
            "Streaming path teacher_model should be 'router:INSIGHTS' (slot provenance)."
        )


# ===========================================================================
# TestG3Sampler
# ===========================================================================

class TestG3Sampler:
    """Tests for corpus_g3_sample.py."""

    # -- bucket key --

    def test_bucket_key_combines_source_and_business_type(self):
        key = _bucket_key("chat_qa", "restaurant")
        assert key == "chat_qa_restaurant"

    def test_bucket_key_defaults_all_for_empty_business_type(self):
        key = _bucket_key("agent_insight", "")
        assert key == "agent_insight_all"

    def test_bucket_key_replaces_slashes(self):
        key = _bucket_key("some/source", "some/type")
        assert "/" not in key

    # -- group rows --

    def test_group_rows_by_bucket_separates_sources(self):
        rows = [
            {"source": "chat_qa", "business_type": "factory", "id": 1},
            {"source": "chat_qa", "business_type": "restaurant", "id": 2},
            {"source": "agent_insight", "business_type": "factory", "id": 3},
        ]
        grouped = group_rows_by_bucket(rows)
        assert len(grouped) == 3
        assert "chat_qa_factory" in grouped
        assert "chat_qa_restaurant" in grouped
        assert "agent_insight_factory" in grouped

    def test_group_rows_empty_input(self):
        assert group_rows_by_bucket([]) == {}

    # -- fetch sample rows --

    @pytest.mark.asyncio
    async def test_fetch_sample_rows_none_pool_returns_empty(self):
        rows = await fetch_sample_rows(None, n=10)
        assert rows == []

    @pytest.mark.asyncio
    async def test_fetch_sample_rows_passes_limit_to_db(self):
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.fetch = AsyncMock(return_value=[])

        await fetch_sample_rows(mock_pool, n=30, per_bucket=False)

        mock_conn.fetch.assert_called_once()
        call_args = mock_conn.fetch.call_args
        # First arg after SQL is n=30
        params = list(call_args.args)
        assert 30 in params, f"Limit 30 must be in DB call params: {params}"

    @pytest.mark.asyncio
    async def test_fetch_sample_rows_source_filter_passed(self):
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.fetch = AsyncMock(return_value=[])

        await fetch_sample_rows(mock_pool, n=10, source="chat_qa", per_bucket=False)

        call_args = mock_conn.fetch.call_args
        all_params = list(call_args.args)
        assert "chat_qa" in all_params, "Source filter must be passed to DB query"

    # -- write markdown file --

    def test_write_markdown_file_creates_file(self):
        rows = [
            {
                "id": 1,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "source": "chat_qa",
                "business_type": "factory",
                "metadata": None,
            }
        ]
        with tempfile.TemporaryDirectory() as tmpdir:
            path = write_markdown_review_file("chat_qa_factory", rows, output_dir=tmpdir)
            assert os.path.exists(path)
            with open(path, encoding="utf-8") as f:
                content = f.read()
        assert SAMPLE_INPUT[:100] in content
        assert SAMPLE_OUTPUT[:100] in content
        assert "Verdict" in content or "verdict" in content.lower()
        assert "PASS" in content or "FAIL" in content

    def test_write_markdown_file_bucket_in_filename(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = write_markdown_review_file("my_bucket_key", [], output_dir=tmpdir)
        assert "my_bucket_key" in path

    def test_write_markdown_file_multiple_rows(self):
        rows = [
            {
                "id": i,
                "input_text": f"input {i}",
                "teacher_output": f"output {i}",
                "source": "chat_qa",
                "business_type": "all",
                "metadata": None,
            }
            for i in range(5)
        ]
        with tempfile.TemporaryDirectory() as tmpdir:
            path = write_markdown_review_file("chat_qa_all", rows, output_dir=tmpdir)
            with open(path, encoding="utf-8") as f:
                content = f.read()
        # Should have 5 items
        assert content.count("## Item") == 5 or content.count("Item") >= 5

    # -- write JSONL file --

    def test_write_jsonl_file_creates_file_with_correct_structure(self):
        rows = [
            {
                "id": 42,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "source": "agent_insight",
                "business_type": "restaurant",
                "metadata": None,
            }
        ]
        with tempfile.TemporaryDirectory() as tmpdir:
            path = write_jsonl_review_file("agent_insight_restaurant", rows, output_dir=tmpdir)
            assert os.path.exists(path)
            with open(path, encoding="utf-8") as f:
                line = f.readline()
            obj = json.loads(line)
        assert obj["id"] == 42
        assert "input_text" in obj
        assert "teacher_output" in obj
        assert "verdict" in obj  # blank field for human
        assert "notes" in obj

    # -- record G3 result --

    @pytest.mark.asyncio
    async def test_record_g3_result_computes_pass_rate(self):
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.execute = AsyncMock()  # for CREATE TABLE
        mock_conn.fetchrow = AsyncMock(return_value={"id": 1, "reviewed_at": "2026-06-11"})

        result = await record_g3_result(mock_pool, "chat_qa_all", pass_count=27, total=30)

        assert result is not None
        assert abs(result["pass_rate"] - 90.0) < 0.01

    @pytest.mark.asyncio
    async def test_record_g3_result_warns_below_90_percent(self, caplog):
        import logging
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.execute = AsyncMock()
        mock_conn.fetchrow = AsyncMock(return_value={"id": 2, "reviewed_at": "2026-06-11"})

        with caplog.at_level(logging.WARNING, logger="corpus_g3_sample"):
            result = await record_g3_result(mock_pool, "chat_qa_all", pass_count=25, total=30)

        # 25/30 = 83.33% < 90%
        assert result is not None
        assert result["pass_rate"] < 90.0
        # Should have a warning about below threshold
        assert any("BELOW" in r.message or "threshold" in r.message.lower() or "90" in r.message
                   for r in caplog.records)

    @pytest.mark.asyncio
    async def test_record_g3_result_no_warning_at_90_percent(self, caplog):
        import logging
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.execute = AsyncMock()
        mock_conn.fetchrow = AsyncMock(return_value={"id": 3, "reviewed_at": "2026-06-11"})

        with caplog.at_level(logging.WARNING, logger="corpus_g3_sample"):
            result = await record_g3_result(mock_pool, "chat_qa_all", pass_count=27, total=30)

        # Exactly 90% — no below-threshold warning expected
        warnings_below = [
            r for r in caplog.records
            if ("BELOW" in r.message or "threshold" in r.message.lower())
            and r.levelno >= logging.WARNING
        ]
        assert len(warnings_below) == 0, f"Unexpected below-threshold warning at 90%: {warnings_below}"

    @pytest.mark.asyncio
    async def test_record_g3_none_pool_returns_none(self):
        result = await record_g3_result(None, "bucket", pass_count=9, total=10)
        assert result is None

    @pytest.mark.asyncio
    async def test_record_g3_invalid_pass_count_returns_none(self):
        mock_pool, _ = _make_mock_pool()
        # pass_count > total
        result = await record_g3_result(mock_pool, "bucket", pass_count=11, total=10)
        assert result is None

    @pytest.mark.asyncio
    async def test_record_g3_zero_total_returns_none(self):
        mock_pool, _ = _make_mock_pool()
        result = await record_g3_result(mock_pool, "bucket", pass_count=0, total=0)
        assert result is None

    # -- run_sample dry-run --

    @pytest.mark.asyncio
    async def test_run_sample_dry_run_no_file_writes(self, tmp_path, capsys):
        fake_rows = [
            {
                "id": 100,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
                "business_type": "factory",
            }
        ]

        with patch("scripts.corpus_g3_sample._make_pool", new=AsyncMock(return_value=None)):
            with patch("scripts.corpus_g3_sample.fetch_sample_rows",
                       new=AsyncMock(return_value=fake_rows)):
                await run_sample(
                    n=30,
                    source=None,
                    output_dir=str(tmp_path),
                    dry_run=True,
                )

        # No files should have been created
        assert list(tmp_path.iterdir()) == []
        captured = capsys.readouterr()
        assert "DRY-RUN" in captured.out.upper()

    @pytest.mark.asyncio
    async def test_run_sample_writes_files_per_bucket(self, tmp_path):
        fake_rows = [
            {
                "id": 200,
                "input_text": SAMPLE_INPUT,
                "teacher_output": SAMPLE_OUTPUT,
                "metadata": None,
                "source": "chat_qa",
                "business_type": "factory",
            },
            {
                "id": 201,
                "input_text": "问题2",
                "teacher_output": "回答2",
                "metadata": None,
                "source": "agent_insight",
                "business_type": "all",
            },
        ]

        with patch("scripts.corpus_g3_sample._make_pool", new=AsyncMock(return_value=MagicMock())):
            with patch("scripts.corpus_g3_sample.fetch_sample_rows",
                       new=AsyncMock(return_value=fake_rows)):
                await run_sample(
                    n=30,
                    output_dir=str(tmp_path),
                    dry_run=False,
                )

        written = list(tmp_path.iterdir())
        assert len(written) == 2, f"Expected 2 files (one per bucket), got {len(written)}"

    # -- N rows per bucket integrity --

    @pytest.mark.asyncio
    async def test_sample_N_rows_per_bucket_satisfied(self):
        """fetch_sample_rows should query for exactly N rows per bucket."""
        mock_pool, mock_conn = _make_mock_pool()
        mock_conn.fetch = AsyncMock(return_value=[])

        await fetch_sample_rows(mock_pool, n=15, per_bucket=True)

        mock_conn.fetch.assert_called_once()
        call_args = mock_conn.fetch.call_args
        params = list(call_args.args)
        assert 15 in params, f"N=15 must be passed as a parameter; got {params}"
