"""Unit tests for etl_intent_corpus.py.

Tests:
  - _build_intent_input_text: self-contained, includes query + candidates + factory
  - _derive_business_type: qhj*→restaurant, F*→factory, none→unknown
  - _quality_from_confirmed / _quality_from_feedback: quality mapping
  - _persist_one in dry-run mode: enumerates without writing (pool never touched)
  - End-to-end ETL functions with mocked asyncpg connections / pools
"""
from __future__ import annotations

import json
import sys
import os
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Make the scripts package importable from the tests directory
# ---------------------------------------------------------------------------
_PYTHON_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
_SCRIPTS_DIR = os.path.join(_PYTHON_ROOT, "scripts")
if _PYTHON_ROOT not in sys.path:
    sys.path.insert(0, _PYTHON_ROOT)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

# Import module under test
import importlib
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "etl_intent_corpus",
    os.path.join(_SCRIPTS_DIR, "etl_intent_corpus.py"),
)
_mod = importlib.util.module_from_spec(_spec)
sys.modules["etl_intent_corpus"] = _mod
_spec.loader.exec_module(_mod)

_derive_business_type = _mod._derive_business_type
_build_intent_input_text = _mod._build_intent_input_text
_build_fallback_input_text = _mod._build_fallback_input_text
_quality_from_confirmed = _mod._quality_from_confirmed
_quality_from_feedback = _mod._quality_from_feedback
_persist_one = _mod._persist_one


# ===========================================================================
# _derive_business_type
# ===========================================================================

class TestDeriveBusinessType:
    def test_qhj_lowercase_is_restaurant(self):
        assert _derive_business_type("qhj_prod") == "restaurant"

    def test_qhj_uppercase_is_restaurant(self):
        assert _derive_business_type("QHJ001") == "restaurant"

    def test_factory_F001_is_factory(self):
        assert _derive_business_type("F001") == "factory"

    def test_factory_f006_lowercase_is_factory(self):
        assert _derive_business_type("f006") == "factory"

    def test_none_is_unknown(self):
        assert _derive_business_type(None) == "unknown"

    def test_empty_string_is_unknown(self):
        assert _derive_business_type("") == "unknown"

    def test_unrecognised_prefix_is_unknown(self):
        assert _derive_business_type("XYZ999") == "unknown"

    def test_whitespace_only_is_unknown(self):
        assert _derive_business_type("   ") == "unknown"


# ===========================================================================
# _build_intent_input_text
# ===========================================================================

class TestBuildIntentInputText:
    def test_basic_structure(self):
        text = _build_intent_input_text(
            user_input="查一下上个月的销售额",
            top_candidates=None,
            factory_id="F001",
        )
        assert text.startswith("intent_routing|query:")
        assert "查一下上个月的销售额" in text
        assert "candidates:none" in text
        assert "factory:F001" in text

    def test_self_contained_has_query(self):
        """The input_text must contain the full query (self-contained)."""
        q = "今天的库存是多少"
        text = _build_intent_input_text(q, None, "F006")
        assert q in text

    def test_candidates_json_included_compactly(self):
        candidates_json = json.dumps([
            {"intentCode": "INVENTORY_QUERY", "confidence": 0.85},
            {"intentCode": "MATERIAL_QUERY", "confidence": 0.72},
        ])
        text = _build_intent_input_text("库存", candidates_json, "F001")
        assert "INVENTORY_QUERY" in text
        # Should be compact JSON (no extra spaces)
        assert " " not in text.split("candidates:")[1].split("|")[0]

    def test_invalid_candidates_json_fallback(self):
        """Non-JSON candidates are included as-is (stripped)."""
        text = _build_intent_input_text("test", "not-json-{{{", "F001")
        assert "candidates:" in text
        assert "not-json" in text

    def test_none_factory_id(self):
        text = _build_intent_input_text("hello", None, None)
        assert "factory:unknown" in text

    def test_qhj_factory_in_text(self):
        text = _build_intent_input_text("营业额对比", None, "qhj_prod")
        assert "factory:qhj_prod" in text


class TestBuildFallbackInputText:
    def test_basic_structure(self):
        text = _build_fallback_input_text("本月利润怎么样", "qhj_prod")
        assert text.startswith("intent_routing|query:")
        assert "本月利润怎么样" in text
        assert "candidates:none" in text
        assert "factory:qhj_prod" in text

    def test_none_factory_id_defaults_to_unknown(self):
        text = _build_fallback_input_text("test query", None)
        assert "factory:unknown" in text


# ===========================================================================
# Quality mapping
# ===========================================================================

class TestQualityMapping:
    # _quality_from_confirmed
    def test_confirmed_true_is_4(self):
        assert _quality_from_confirmed(True) == 4

    def test_confirmed_false_is_3(self):
        assert _quality_from_confirmed(False) == 3

    def test_confirmed_none_is_3(self):
        assert _quality_from_confirmed(None) == 3

    # _quality_from_feedback
    def test_thumbsup_positive_is_4(self):
        assert _quality_from_feedback(1) == 4

    def test_thumbsdown_negative_is_3(self):
        assert _quality_from_feedback(-1) == 3

    def test_feedback_none_is_3(self):
        assert _quality_from_feedback(None) == 3

    def test_feedback_zero_is_3(self):
        assert _quality_from_feedback(0) == 3


# ===========================================================================
# _persist_one — dry_run must enumerate without writing (pool never called)
# ===========================================================================

class TestPersistOneDryRun:
    @pytest.mark.asyncio
    async def test_dry_run_returns_true_without_touching_pool(self):
        """dry_run=True → returns True immediately, never calls persist_distillation_sample."""
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock()  # should NOT be called

        result = await _persist_one(
            mock_pool,
            input_text="intent_routing|query:test",
            teacher_output="INTENT_CODE: INVENTORY_QUERY",
            business_type="factory",
            factory_id="F001",
            quality=3,
            teacher_model=None,
            metadata={"etl_source": "intent_match_records"},
            dry_run=True,
        )

        assert result is True
        mock_pool.acquire.assert_not_called()

    @pytest.mark.asyncio
    async def test_dry_run_skips_empty_input(self):
        mock_pool = MagicMock()
        result = await _persist_one(
            mock_pool,
            input_text="",
            teacher_output="something",
            business_type="unknown",
            factory_id=None,
            quality=3,
            teacher_model=None,
            metadata={},
            dry_run=True,
        )
        assert result is False

    @pytest.mark.asyncio
    async def test_dry_run_skips_empty_teacher_output(self):
        mock_pool = MagicMock()
        result = await _persist_one(
            mock_pool,
            input_text="intent_routing|query:test",
            teacher_output="",
            business_type="unknown",
            factory_id=None,
            quality=3,
            teacher_model=None,
            metadata={},
            dry_run=True,
        )
        assert result is False

    @pytest.mark.asyncio
    async def test_live_mode_calls_persist_distillation_sample(self):
        """Non-dry-run: calls persist_distillation_sample with correct args."""
        mock_pool = MagicMock()
        captured: dict = {}

        async def fake_persist(pool, source, task_type, input_text, teacher_output, **kwargs):
            captured.update({
                "source": source,
                "task_type": task_type,
                "input_text": input_text,
                "teacher_output": teacher_output,
                "business_type": kwargs.get("business_type"),
                "quality": kwargs.get("quality"),
                "metadata": kwargs.get("metadata"),
            })

        with patch(
            "smartbi.services.distillation_capture.persist_distillation_sample",
            side_effect=fake_persist,
        ):
            result = await _persist_one(
                mock_pool,
                input_text="intent_routing|query:查库存|candidates:none|factory:F001",
                teacher_output="INVENTORY_QUERY",
                business_type="factory",
                factory_id="F001",
                quality=4,
                teacher_model="qwen3-max",
                metadata={"etl_source": "intent_match_records", "matched_intent": "INVENTORY_QUERY"},
                dry_run=False,
            )

        assert result is True
        assert captured["source"] == "intent_llm"
        assert captured["task_type"] == "intent"
        assert "intent_routing|query:" in captured["input_text"]
        assert captured["teacher_output"] == "INVENTORY_QUERY"
        assert captured["business_type"] == "factory"
        assert captured["quality"] == 4
        assert captured["metadata"]["etl_source"] == "intent_match_records"


# ===========================================================================
# _etl_intent_match_records — mocked cretas_conn and smartbi_pool
# ===========================================================================

class TestEtlIntentMatchRecords:
    def _make_row(self, **overrides) -> Dict[str, Any]:
        base = {
            "id": "uuid-123",
            "factory_id": "F001",
            "user_input": "查询上月生产计划",
            "llm_response": "PRODUCTION_PLAN_QUERY",
            "llm_intent_code": "PRODUCTION_PLAN_QUERY",
            "matched_intent_code": "PRODUCTION_PLAN_QUERY",
            "user_confirmed": None,
            "top_candidates": json.dumps([{"intentCode": "PRODUCTION_PLAN_QUERY", "confidence": 0.9}]),
            "created_at": None,
        }
        base.update(overrides)
        return base

    @pytest.mark.asyncio
    async def test_dry_run_counts_rows_without_writing(self):
        rows = [self._make_row(), self._make_row(factory_id="qhj_prod")]
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=rows)
        mock_smartbi_pool = MagicMock()

        written = await _mod._etl_intent_match_records(
            cretas_conn=mock_conn,
            smartbi_pool=mock_smartbi_pool,
            limit=100,
            dry_run=True,
        )

        assert written == 2
        # pool.acquire must never be called in dry-run
        mock_smartbi_pool.acquire.assert_not_called()

    @pytest.mark.asyncio
    async def test_skips_rows_with_empty_user_input(self):
        rows = [
            self._make_row(user_input=""),
            self._make_row(user_input="   "),
            self._make_row(),  # valid
        ]
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=rows)
        mock_smartbi_pool = MagicMock()

        written = await _mod._etl_intent_match_records(
            cretas_conn=mock_conn,
            smartbi_pool=mock_smartbi_pool,
            limit=100,
            dry_run=True,
        )
        assert written == 1  # only the valid row

    @pytest.mark.asyncio
    async def test_business_type_derived_per_row(self):
        rows = [
            self._make_row(factory_id="F001"),
            self._make_row(factory_id="qhj_prod"),
            self._make_row(factory_id=None),
        ]
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=rows)
        mock_smartbi_pool = MagicMock()

        written = await _mod._etl_intent_match_records(
            cretas_conn=mock_conn,
            smartbi_pool=mock_smartbi_pool,
            limit=100,
            dry_run=True,
        )
        assert written == 3  # all have valid user_input + llm_response

    @pytest.mark.asyncio
    async def test_confirmed_true_maps_to_quality_4(self):
        """user_confirmed=True → quality=4 in the call to persist."""
        rows = [self._make_row(user_confirmed=True)]
        mock_conn = AsyncMock()
        mock_conn.fetch = AsyncMock(return_value=rows)

        captured_quality: list = []

        async def fake_persist(pool, source, task_type, input_text, teacher_output, **kwargs):
            captured_quality.append(kwargs.get("quality"))

        with patch(
            "smartbi.services.distillation_capture.persist_distillation_sample",
            side_effect=fake_persist,
        ):
            mock_pool = MagicMock()
            await _mod._etl_intent_match_records(
                cretas_conn=mock_conn,
                smartbi_pool=mock_pool,
                limit=10,
                dry_run=False,
            )

        assert captured_quality == [4]


# ===========================================================================
# _etl_llm_fallback_log — mocked smartbi_pool
# ===========================================================================

class TestEtlLlmFallbackLog:
    def _make_row(self, **overrides) -> Dict[str, Any]:
        base = {
            "id": 42,
            "query": "上月总营收是多少",
            "factory_id": "qhj_prod",
            "answer": "根据统计，上月总营收为 ¥782,000",
            "user_feedback": None,
            "source": "fallback",
            "created_at": None,
        }
        base.update(overrides)
        return base

    @pytest.mark.asyncio
    async def test_dry_run_no_writes(self):
        rows = [self._make_row(), self._make_row(factory_id="F001")]
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_cm)
        mock_cm.__aexit__ = AsyncMock(return_value=False)
        mock_cm.fetch = AsyncMock(return_value=rows)
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(return_value=mock_cm)

        written = await _mod._etl_llm_fallback_log(
            smartbi_pool=mock_pool,
            limit=100,
            dry_run=True,
        )
        assert written == 2

    @pytest.mark.asyncio
    async def test_positive_feedback_maps_to_quality_4(self):
        rows = [self._make_row(user_feedback=1)]
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_cm)
        mock_cm.__aexit__ = AsyncMock(return_value=False)
        mock_cm.fetch = AsyncMock(return_value=rows)
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(return_value=mock_cm)

        captured_quality: list = []

        async def fake_persist(pool, source, task_type, input_text, teacher_output, **kwargs):
            captured_quality.append(kwargs.get("quality"))

        with patch(
            "smartbi.services.distillation_capture.persist_distillation_sample",
            side_effect=fake_persist,
        ):
            await _mod._etl_llm_fallback_log(
                smartbi_pool=mock_pool,
                limit=10,
                dry_run=False,
            )

        assert captured_quality == [4]

    @pytest.mark.asyncio
    async def test_skips_empty_answer(self):
        rows = [
            self._make_row(answer=None),
            self._make_row(answer=""),
            self._make_row(),  # valid
        ]
        mock_cm = AsyncMock()
        mock_cm.__aenter__ = AsyncMock(return_value=mock_cm)
        mock_cm.__aexit__ = AsyncMock(return_value=False)
        mock_cm.fetch = AsyncMock(return_value=rows)
        mock_pool = MagicMock()
        mock_pool.acquire = MagicMock(return_value=mock_cm)

        written = await _mod._etl_llm_fallback_log(
            smartbi_pool=mock_pool,
            limit=100,
            dry_run=True,
        )
        assert written == 1
