"""Unit tests for smartbi.gold.queries._validate_range.

Pure tests (no DB) — _validate_range is a plain guard function. WS1 Task 1
makes the range optional so a None start/end means "all history" (no filter
on that side). The only error case is when BOTH dates are present AND
start > end.
"""
from __future__ import annotations

from datetime import date

import pytest

from smartbi.gold.queries import _validate_range


def test_both_none_ok():
    """None/None means all-history — no error."""
    assert _validate_range(None, None) is None


def test_one_none_ok():
    """A single open-ended bound is allowed (start-only or end-only)."""
    assert _validate_range(date(2025, 1, 1), None) is None
    assert _validate_range(None, date(2025, 12, 31)) is None


def test_start_after_end_raises():
    """Both present and inverted → ValueError."""
    with pytest.raises(ValueError, match="start .* > end"):
        _validate_range(date(2025, 12, 31), date(2025, 1, 1))


def test_normal_range_ok():
    """Both present, start <= end → no error."""
    assert _validate_range(date(2025, 1, 1), date(2025, 12, 31)) is None
    # Equal bounds (single-day) is valid.
    assert _validate_range(date(2025, 6, 1), date(2025, 6, 1)) is None
