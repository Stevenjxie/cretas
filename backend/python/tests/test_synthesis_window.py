"""Tests for synthesis _resolve_window relative-time parsing (F1).

An owner asking "这两个月" must get the last ~2 months of DATA, not the full
18-month history (the pre-F1 behaviour) and not "2 months from today" (demo /
historical data lives in the past, so the window anchors to the data's max date).
"""
import asyncio
import datetime

import smartbi.api.synthesis as syn


def _mock_data_range(monkeypatch, mn, mx):
    async def fake(pool, fid):
        return {"min_date": mn, "max_date": mx}
    monkeypatch.setattr(syn, "data_range", fake)


def _run(**kw):
    return asyncio.run(syn._resolve_window(object(), "DEMO_REST", **kw))


def test_relative_two_months_anchored_to_data_max(monkeypatch):
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date=None, end_date=None, question="我这两个月经营咋样")
    # ends at data_max, not today; ~60-day rolling window
    assert e == datetime.date(2026, 6, 30)
    assert s == datetime.date(2026, 6, 30) - datetime.timedelta(days=59)


def test_last_month_phrase(monkeypatch):
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date=None, end_date=None, question="上个月生意怎么样")
    # "上个月" relative to data_max 2026-06-30 → May 2026
    assert s == datetime.date(2026, 5, 1)
    assert e == datetime.date(2026, 5, 31)


def test_no_time_phrase_returns_full_span(monkeypatch):
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date=None, end_date=None, question="十六家店里头哪家最不行")
    assert (s, e) == (datetime.date(2025, 1, 1), datetime.date(2026, 6, 30))


def test_no_question_is_backcompat_full_span(monkeypatch):
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date=None, end_date=None)
    assert (s, e) == (datetime.date(2025, 1, 1), datetime.date(2026, 6, 30))


def test_explicit_dates_win_over_question(monkeypatch):
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date="2026-03-01", end_date="2026-03-31", question="这两个月")
    assert (s, e) == (datetime.date(2026, 3, 1), datetime.date(2026, 3, 31))


def test_relative_window_clamped_to_data_min(monkeypatch):
    # data span only 10 days but "这两个月" wants 60 → start clamped to data_min
    _mock_data_range(monkeypatch, "2026-06-20", "2026-06-30")
    s, e = _run(start_date=None, end_date=None, question="这两个月赚钱没")
    assert s == datetime.date(2026, 6, 20)  # clamped, not 60 days back
    assert e == datetime.date(2026, 6, 30)


def test_bare_today_not_collapsed_for_synthesis(monkeypatch):
    # "今天" in an action clause must NOT collapse a multi-dim synthesis to 1 day
    # (audit A#2) — falls back to the full data span.
    _mock_data_range(monkeypatch, "2025-01-01", "2026-06-30")
    s, e = _run(start_date=None, end_date=None,
                question="综合看看经营，今天先做哪几家店")
    assert (s, e) == (datetime.date(2025, 1, 1), datetime.date(2026, 6, 30))


def test_out_of_range_phrase_returns_honest_empty_not_full_span(monkeypatch):
    # Data only in July; "上个月" (June) has no data → return the June window
    # (honestly empty), NOT silently the full July span (audit A#1).
    _mock_data_range(monkeypatch, "2026-07-01", "2026-07-06")
    s, e = _run(start_date=None, end_date=None, question="上个月赚钱没")
    assert (s, e) == (datetime.date(2026, 6, 1), datetime.date(2026, 6, 30))
    assert (s, e) != (datetime.date(2026, 7, 1), datetime.date(2026, 7, 6))  # not full span
