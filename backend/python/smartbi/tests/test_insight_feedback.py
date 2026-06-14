"""G4 feedback loop tests.

Tests for:
1. DOWN vote demotes quality when feedback_down reaches threshold (≥2)
2. DOWN-voted rows are excluded from serve-path
3. UP vote records positive signal without inflating quality
4. Row-not-found returns explicit error (no silent failure)
5. Idempotent vote counting (down counted per call, not de-duped — intentional)
6. Serve-skip: _read_corpus_cache returns None when feedback_down >= threshold
"""
from __future__ import annotations
from smartbi.services.insights.chart_insight_service import (
    ChartInsightService,
    ChartInsightContext,
    _FEEDBACK_DOWN_SERVE_THRESHOLD,
)

import json
import os
import sys
from typing import Any, Dict, Optional
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Path setup
# ---------------------------------------------------------------------------
SMARTBI_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)
if SMARTBI_ROOT not in sys.path:
    sys.path.insert(0, SMARTBI_ROOT)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_ctx(factory_id: str = "F001") -> ChartInsightContext:
    return ChartInsightContext(
        chart_type="BAR",
        x_dim="store",
        y_metric="revenue",
        aggregation="sum",
        domain="restaurant",
        data_pattern="ranking:top-share:65-80:n4-8",
        permission_tier="finance_visible",
        factory_id=factory_id,
        series_values=[100.0, 60.0, 40.0],
        series_labels=["门店A", "门店B", "门店C"],
    )


def _make_row(
    teacher_output: str,
    input_hash: str,
    quality: int = 5,
    metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Simulate an asyncpg Row as a plain dict (asyncpg Records support [] access)."""
    return {
        "teacher_output": teacher_output,
        "input_hash": input_hash,
        "quality": quality,
        "metadata": json.dumps(metadata) if metadata is not None else None,
    }


def _mock_fetchrow(row_dict: Optional[Dict[str, Any]]):
    """Return an AsyncMock that behaves like asyncpg conn.fetchrow()."""
    if row_dict is None:
        async def _fetch(*_args):
            return None
        return AsyncMock(side_effect=_fetch)
    # asyncpg Rows support [key] access — use a MagicMock that mimics it
    mock_row = MagicMock()
    mock_row.__getitem__ = lambda self, k: row_dict[k]
    mock_row.__contains__ = lambda self, k: k in row_dict

    async def _fetch(*_args):
        return mock_row
    return AsyncMock(side_effect=_fetch)


# ---------------------------------------------------------------------------
# TestServeSkip — _read_corpus_cache excludes demoted rows
# ---------------------------------------------------------------------------

class TestServeSkip:
    """Serve-path must skip rows where feedback_down >= threshold."""

    def _make_service(self, pool):
        budget = MagicMock()
        budget.check_budget = AsyncMock(return_value=MagicMock(blocked=False))
        budget.consume = AsyncMock()
        return ChartInsightService(pool=pool, budget_tracker=budget)

    @pytest.mark.asyncio
    async def test_fresh_row_served(self):
        """A row with no feedback signal is returned normally."""
        teacher_output = json.dumps({"finding": "门店A排名最高", "implication": None, "suggestion": None})
        input_hash = "abc123"
        row = _make_row(teacher_output, input_hash, quality=5, metadata={})

        conn = MagicMock()
        conn.fetchrow = _mock_fetchrow(row)
        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(
            return_value=conn), __aexit__=AsyncMock(return_value=False)))

        svc = self._make_service(pool)
        result = await svc._read_corpus_cache(input_hash)
        assert result is not None
        teacher_out, row_hash = result
        assert teacher_out == teacher_output
        assert row_hash == input_hash

    @pytest.mark.asyncio
    async def test_demoted_row_skipped(self):
        """A row with feedback_down >= threshold must NOT be served (returns None)."""
        teacher_output = json.dumps({"finding": "some bad answer", "implication": None, "suggestion": None})
        input_hash = "badrow456"
        # feedback_down is at the threshold
        row = _make_row(
            teacher_output, input_hash, quality=2,
            metadata={"feedback_down": _FEEDBACK_DOWN_SERVE_THRESHOLD, "demoted_by_feedback": True},
        )

        conn = MagicMock()
        conn.fetchrow = _mock_fetchrow(row)
        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(
            return_value=conn), __aexit__=AsyncMock(return_value=False)))

        svc = self._make_service(pool)
        result = await svc._read_corpus_cache(input_hash)
        assert result is None, "Demoted row must be excluded from serve"

    @pytest.mark.asyncio
    async def test_one_downvote_still_served(self):
        """A row with one downvote (below threshold) is still served."""
        teacher_output = json.dumps({"finding": "门店B偏低", "implication": None, "suggestion": None})
        input_hash = "onedown789"
        row = _make_row(
            teacher_output, input_hash, quality=5,
            metadata={"feedback_down": 1},
        )

        conn = MagicMock()
        conn.fetchrow = _mock_fetchrow(row)
        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(
            return_value=conn), __aexit__=AsyncMock(return_value=False)))

        svc = self._make_service(pool)
        result = await svc._read_corpus_cache(input_hash)
        assert result is not None, "Single downvote below threshold must still be served"

    @pytest.mark.asyncio
    async def test_cache_miss_returns_none(self):
        """A cache miss (row not found) returns None."""
        conn = MagicMock()
        conn.fetchrow = _mock_fetchrow(None)
        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(__aenter__=AsyncMock(
            return_value=conn), __aexit__=AsyncMock(return_value=False)))

        svc = self._make_service(pool)
        result = await svc._read_corpus_cache("notfound")
        assert result is None


# ---------------------------------------------------------------------------
# TestFeedbackEndpoint — insight_feedback() route logic
# ---------------------------------------------------------------------------

class TestFeedbackEndpoint:
    """Tests for POST /api/smartbi/insight/feedback endpoint logic (sans HTTP layer)."""

    def _make_request_state(self, factory_id: str = "F001", role: str = "factory_super_admin"):
        state = MagicMock()
        state.factory_id = factory_id
        state.role = role
        return state

    @pytest.mark.asyncio
    async def test_down_vote_demotes_quality_at_threshold(self):
        """Second downvote crosses threshold → quality capped at 2."""
        from smartbi.api.chart_insight import insight_feedback, InsightFeedbackRequest

        input_hash = "demote_me_hash"
        # Simulated existing row: quality=5, already has 1 downvote
        existing_row = {
            "id": 1,
            "quality": 5,
            "metadata": json.dumps({"feedback_down": 1, "gate": "passed"}),
        }
        mock_row = MagicMock()
        mock_row.__getitem__ = lambda self, k: existing_row[k]

        executed_updates = []

        async def fake_execute(sql, *args):
            executed_updates.append((sql, args))

        conn = MagicMock()
        conn.fetchrow = AsyncMock(return_value=mock_row)
        conn.execute = AsyncMock(side_effect=fake_execute)

        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(
            __aenter__=AsyncMock(return_value=conn),
            __aexit__=AsyncMock(return_value=False),
        ))

        # Patch _get_service to return a service with our fake pool
        mock_svc = MagicMock()
        mock_svc._pool = pool

        request = MagicMock()
        request.state = self._make_request_state("F001")

        body = InsightFeedbackRequest(input_hash=input_hash, vote="down", factory_id="F001")

        with patch("smartbi.api.chart_insight._get_service", new=AsyncMock(return_value=mock_svc)):
            result = await insight_feedback(request, body)

        assert result["success"] is True
        assert result["data"]["feedback_recorded"] is True
        assert result["data"]["vote"] == "down"
        # Quality must be demoted: min(5, 2) = 2
        assert result["data"]["new_quality"] == 2

        # Verify the UPDATE was called
        assert len(executed_updates) == 1
        _sql, update_args = executed_updates[0]
        new_meta = json.loads(update_args[0])
        assert new_meta["feedback_down"] == 2  # was 1, now 2
        assert new_meta["demoted_by_feedback"] is True
        assert update_args[1] == 2  # new_quality

    @pytest.mark.asyncio
    async def test_up_vote_records_without_inflating_quality(self):
        """Upvote increments feedback_up but does NOT raise quality above current value."""
        from smartbi.api.chart_insight import insight_feedback, InsightFeedbackRequest

        input_hash = "good_answer_hash"
        existing_row = {
            "id": 2,
            "quality": 5,
            "metadata": json.dumps({"gate": "passed", "feedback_up": 0}),
        }
        mock_row = MagicMock()
        mock_row.__getitem__ = lambda self, k: existing_row[k]

        executed_updates = []

        async def fake_execute(sql, *args):
            executed_updates.append((sql, args))

        conn = MagicMock()
        conn.fetchrow = AsyncMock(return_value=mock_row)
        conn.execute = AsyncMock(side_effect=fake_execute)

        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(
            __aenter__=AsyncMock(return_value=conn),
            __aexit__=AsyncMock(return_value=False),
        ))

        mock_svc = MagicMock()
        mock_svc._pool = pool

        request = MagicMock()
        request.state = self._make_request_state("F001")
        body = InsightFeedbackRequest(input_hash=input_hash, vote="up", factory_id="F001")

        with patch("smartbi.api.chart_insight._get_service", new=AsyncMock(return_value=mock_svc)):
            result = await insight_feedback(request, body)

        assert result["success"] is True
        assert result["data"]["vote"] == "up"
        # Quality unchanged (no inflation)
        assert result["data"]["new_quality"] == 5

        _sql, update_args = executed_updates[0]
        new_meta = json.loads(update_args[0])
        assert new_meta["feedback_up"] == 1
        assert "demoted_by_feedback" not in new_meta
        assert update_args[1] == 5  # quality unchanged

    @pytest.mark.asyncio
    async def test_row_not_found_explicit_error(self):
        """Row not found returns explicit error, no silent swallow."""
        from smartbi.api.chart_insight import insight_feedback, InsightFeedbackRequest

        conn = MagicMock()
        conn.fetchrow = AsyncMock(return_value=None)

        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(
            __aenter__=AsyncMock(return_value=conn),
            __aexit__=AsyncMock(return_value=False),
        ))

        mock_svc = MagicMock()
        mock_svc._pool = pool

        request = MagicMock()
        request.state = self._make_request_state("F001")
        body = InsightFeedbackRequest(input_hash="nonexistent_hash", vote="down", factory_id="F001")

        with patch("smartbi.api.chart_insight._get_service", new=AsyncMock(return_value=mock_svc)):
            result = await insight_feedback(request, body)

        assert result["success"] is False
        assert "not found" in result["message"].lower() or "ROW_NOT_FOUND" == result.get("code")

    @pytest.mark.asyncio
    async def test_first_downvote_no_demote(self):
        """First downvote (count becomes 1) does NOT demote quality (threshold is 2)."""
        from smartbi.api.chart_insight import insight_feedback, InsightFeedbackRequest

        input_hash = "first_down_hash"
        existing_row = {
            "id": 3,
            "quality": 5,
            "metadata": json.dumps({"gate": "passed"}),
        }
        mock_row = MagicMock()
        mock_row.__getitem__ = lambda self, k: existing_row[k]

        executed_updates = []

        async def fake_execute(sql, *args):
            executed_updates.append((sql, args))

        conn = MagicMock()
        conn.fetchrow = AsyncMock(return_value=mock_row)
        conn.execute = AsyncMock(side_effect=fake_execute)

        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(
            __aenter__=AsyncMock(return_value=conn),
            __aexit__=AsyncMock(return_value=False),
        ))

        mock_svc = MagicMock()
        mock_svc._pool = pool

        request = MagicMock()
        request.state = self._make_request_state("F001")
        body = InsightFeedbackRequest(input_hash=input_hash, vote="down", factory_id="F001")

        with patch("smartbi.api.chart_insight._get_service", new=AsyncMock(return_value=mock_svc)):
            result = await insight_feedback(request, body)

        assert result["success"] is True
        # Quality must NOT be demoted on first vote
        assert result["data"]["new_quality"] == 5

        _sql, update_args = executed_updates[0]
        new_meta = json.loads(update_args[0])
        assert new_meta["feedback_down"] == 1
        assert "demoted_by_feedback" not in new_meta

    @pytest.mark.asyncio
    async def test_idempotent_vote_counting(self):
        """Each vote call increments the counter (no de-duplication per call — intentional)."""
        from smartbi.api.chart_insight import insight_feedback, InsightFeedbackRequest

        input_hash = "idem_hash"
        existing_row = {
            "id": 4,
            "quality": 5,
            "metadata": json.dumps({"feedback_up": 3}),
        }
        mock_row = MagicMock()
        mock_row.__getitem__ = lambda self, k: existing_row[k]

        executed_updates = []

        async def fake_execute(sql, *args):
            executed_updates.append((sql, args))

        conn = MagicMock()
        conn.fetchrow = AsyncMock(return_value=mock_row)
        conn.execute = AsyncMock(side_effect=fake_execute)

        pool = MagicMock()
        pool.acquire = MagicMock(return_value=MagicMock(
            __aenter__=AsyncMock(return_value=conn),
            __aexit__=AsyncMock(return_value=False),
        ))

        mock_svc = MagicMock()
        mock_svc._pool = pool

        request = MagicMock()
        request.state = self._make_request_state("F001")
        body = InsightFeedbackRequest(input_hash=input_hash, vote="up", factory_id="F001")

        with patch("smartbi.api.chart_insight._get_service", new=AsyncMock(return_value=mock_svc)):
            await insight_feedback(request, body)

        _sql, update_args = executed_updates[0]
        new_meta = json.loads(update_args[0])
        # Was 3, now should be 4
        assert new_meta["feedback_up"] == 4
