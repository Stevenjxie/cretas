"""HTTP-layer tests for ``_parse_range`` optional-date handling.

WS1 review fix: the query layer already accepted ``None`` bounds (= all
history), but the HTTP layer forced both ``start_date`` / ``end_date`` via
``Query(...)`` and ``_parse_range`` assumed both present — so all-history was
unreachable from the endpoints. ``_parse_range`` now mirrors the query
contract: a missing bound is "open", and ordering is only validated when BOTH
bounds are present.

Import style mirrors ``test_gold_reads_rbac_strip.py`` which imports the local
helpers from ``smartbi.api.gold_reads`` directly (the production import path is
exercised by the FastAPI app, so the module imports cleanly here).
"""
from __future__ import annotations

from datetime import date

import pytest
from fastapi import HTTPException

from smartbi.api.gold_reads import _parse_range


def test_both_none_returns_none_none():
    """Omitting both dates → (None, None) = all history."""
    assert _parse_range(None, None) == (None, None)


def test_start_only_keeps_end_open():
    """start_date alone parses; end stays open (None)."""
    assert _parse_range("2026-01-01", None) == (date(2026, 1, 1), None)


def test_end_only_keeps_start_open():
    """end_date alone parses; start stays open (None)."""
    assert _parse_range(None, "2026-02-01") == (None, date(2026, 2, 1))


def test_both_present_normal_range():
    """Both present, start <= end → parsed tuple, no error."""
    assert _parse_range("2026-01-01", "2026-02-01") == (
        date(2026, 1, 1),
        date(2026, 2, 1),
    )


def test_inverted_range_raises_400():
    """Both present and inverted → HTTP 400."""
    with pytest.raises(HTTPException) as exc:
        _parse_range("2026-02-01", "2026-01-01")
    assert exc.value.status_code == 400
    assert "start_date > end_date" in exc.value.detail


def test_one_bound_inverted_check_skipped():
    """A single bound can never be 'inverted' — no comparison, no error.

    (Defensive: ensures the ``start is not None and end is not None`` guard
    short-circuits before the comparison when only one bound is supplied.)
    """
    assert _parse_range("2026-12-31", None) == (date(2026, 12, 31), None)
    assert _parse_range(None, "2026-01-01") == (None, date(2026, 1, 1))


def test_malformed_date_raises_400():
    """A present-but-malformed bound still 400s (delegates to _parse_date)."""
    with pytest.raises(HTTPException) as exc:
        _parse_range("not-a-date", None)
    assert exc.value.status_code == 400
    assert "start_date" in exc.value.detail
