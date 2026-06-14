"""Tests: review query functions return connected=false (not bare empty array)
when source table has 0 rows for the tenant.

Fool-proof-design Rule 5: empty shells must declare "not connected" so the
frontend can show a next-action guide instead of an all-zero dashboard.

Run with:
    cd backend/python
    python -m pytest smartbi/gold/tests/test_review_connected_contract.py -v
"""
from __future__ import annotations

import asyncio
import pytest

from smartbi.gold.review_queries import (
    check_review_data_exists,
    review_summary,
    review_store_ranking,
    review_city_ranking,
    review_vip,
    review_complaints,
    review_dish_issues,
    review_vip_tags,
    review_time_period,
    review_score_tags,
    review_good_tags,
    review_platform,
    review_trend,
    review_reply_rate,
)


# ---------------------------------------------------------------------------
# Fake pool / connection helpers
# ---------------------------------------------------------------------------

class _FakeRecord(dict):
    """Mimics asyncpg Record with attribute-style access."""


class _FakeConn:
    def __init__(self, *, existence_count: int = 0, rows=None, row=None):
        """existence_count: value returned for the _EXISTENCE_SQL count query."""
        self._existence_count = existence_count
        self._rows = rows or []
        self._row = row
        self._call_index = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def fetchrow(self, sql, *a, **k):
        # The existence probe is always the first call per acquire
        if "count(*) AS n" in sql and "星级分" in sql:
            return _FakeRecord({"n": self._existence_count})
        return self._row

    async def fetch(self, sql, *a, **k):
        return self._rows


class _FakePool:
    def __init__(self, *, existence_count: int = 0, rows=None, row=None):
        self._conn = _FakeConn(existence_count=existence_count, rows=rows, row=row)

    def acquire(self):
        return self._conn


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


FACTORY = "F_TEST"


# ---------------------------------------------------------------------------
# check_review_data_exists helper
# ---------------------------------------------------------------------------

class TestCheckReviewDataExists:
    def test_returns_false_when_count_zero(self):
        pool = _FakePool(existence_count=0)
        assert _run(check_review_data_exists(pool, FACTORY)) is False

    def test_returns_true_when_count_positive(self):
        pool = _FakePool(existence_count=5)
        assert _run(check_review_data_exists(pool, FACTORY)) is True


# ---------------------------------------------------------------------------
# Each query: empty-source path returns connected=False + no bare empty array
# ---------------------------------------------------------------------------

class TestConnectedFalseOnEmptySource:
    """All review functions must return connected=False (not a bare array) when
    the existence probe returns 0.  The minimum requirement is:
      - result["connected"] is False
      - result does NOT contain any list key with actual data
    """

    @pytest.fixture
    def empty_pool(self):
        return _FakePool(existence_count=0)

    def _assert_not_connected(self, result):
        assert result.get("connected") is False, (
            f"Expected connected=False, got connected={result.get('connected')!r}. "
            f"Full result: {result}"
        )

    def test_review_summary_not_connected(self, empty_pool):
        result = _run(review_summary(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("total_reviews", 0) == 0
        assert result.get("dimension_scores") == []

    def test_review_store_ranking_not_connected(self, empty_pool):
        result = _run(review_store_ranking(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("stores") == []

    def test_review_city_ranking_not_connected(self, empty_pool):
        result = _run(review_city_ranking(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("cities") == []

    def test_review_vip_not_connected(self, empty_pool):
        result = _run(review_vip(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("groups") == []

    def test_review_complaints_not_connected(self, empty_pool):
        result = _run(review_complaints(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("categories") == []
        assert result.get("dispute_count") == 0
        assert result.get("low_star_count") == 0

    def test_review_dish_issues_not_connected(self, empty_pool):
        result = _run(review_dish_issues(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("tags") == []

    def test_review_vip_tags_not_connected(self, empty_pool):
        result = _run(review_vip_tags(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("vip_good_tags") == []
        assert result.get("normal_good_tags") == []

    def test_review_time_period_not_connected(self, empty_pool):
        result = _run(review_time_period(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("periods") == []

    def test_review_score_tags_not_connected(self, empty_pool):
        result = _run(review_score_tags(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("tags") == []

    def test_review_good_tags_not_connected(self, empty_pool):
        result = _run(review_good_tags(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("high_star_count") == 0
        assert result.get("tags") == []

    def test_review_platform_not_connected(self, empty_pool):
        result = _run(review_platform(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("platforms") == []

    def test_review_trend_not_connected(self, empty_pool):
        result = _run(review_trend(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("months") == []

    def test_review_reply_rate_not_connected(self, empty_pool):
        result = _run(review_reply_rate(empty_pool, FACTORY))
        self._assert_not_connected(result)
        assert result.get("replied") == 0
        assert result.get("reply_rate") is None


# ---------------------------------------------------------------------------
# Smoke: connected=True path returns the flag and a list key
# ---------------------------------------------------------------------------

class TestConnectedTrueWhenDataPresent:
    """When existence probe > 0 and queries return rows, connected=True."""

    def test_review_platform_connected_true(self):
        platform_row = _FakeRecord({
            "platform": "大众点评", "n": 100,
            "avg_star": "4.8", "avg_service": "4.7", "avg_env": "4.6",
        })
        pool = _FakePool(existence_count=100, rows=[platform_row])
        result = _run(review_platform(pool, FACTORY))
        assert result.get("connected") is True
        assert len(result["platforms"]) == 1
        assert result["platforms"][0]["platform"] == "大众点评"

    def test_review_reply_rate_connected_true(self):
        summary_row = _FakeRecord({
            "replied": 180, "not_replied": 20,
            "not_replied_low_star": 5, "total_with_status": 200,
        })

        class _ConnWithRow(_FakeConn):
            async def fetchrow(self, sql, *a, **k):
                if "count(*) AS n" in sql and "星级分" in sql:
                    return _FakeRecord({"n": 200})
                return summary_row

        class _PoolWithRow:
            def acquire(self):
                return _ConnWithRow(existence_count=200)

        result = _run(review_reply_rate(_PoolWithRow(), FACTORY))
        assert result.get("connected") is True
        assert result["replied"] == 180
        assert result["reply_rate"] == 90.0
