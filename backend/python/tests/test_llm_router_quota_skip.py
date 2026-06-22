"""
Tests for the long-TTL quota-exhaustion skip cache in common/llm_router.py
(2026-06-22).

Distinct from the 60s circuit breaker (test_llm_router_cb.py): the circuit
breaker re-probes every CB_COOLDOWN (60s), which is right for transient
failures but wrong for free-tier *quota* exhaustion — that lasts until the
account's monthly reset. Re-probing an exhausted model every 60s just burns a
403 round-trip per request (egress audit 2026-06-22: qwen3.7-max-2026-06-08 on
aliyun_b = 409 calls/7d, 0 success, all 403). This cache skips a model that
returned a quota signal for QUOTA_SKIP_TTL, with a single re-probe afterwards to
pick up the monthly reset.

Verifies:
- a quota-exhausted record makes _quota_should_skip return True within the TTL
- after QUOTA_SKIP_TTL elapses, it auto-clears and allows one re-probe
- a clean success clears the quota-exhausted mark immediately
- the cache is keyed per (account,model) — one exhausted model does not skip
  a different free model
"""
import time

from common.llm_router import (
    _QUOTA_EXHAUSTED_UNTIL,
    _quota_record_exhausted,
    _quota_record_success,
    _quota_should_skip,
    QUOTA_SKIP_TTL,
)


def test_quota_exhausted_triggers_skip():
    """A single quota-exhausted record skips that (account,model) within TTL."""
    _QUOTA_EXHAUSTED_UNTIL.clear()
    key = "aliyun_b/qwen3.7-max-2026-06-08"
    assert not _quota_should_skip(key)
    _quota_record_exhausted(key)
    assert _quota_should_skip(key)


def test_quota_ttl_elapses_allows_reprobe():
    """After QUOTA_SKIP_TTL seconds, the skip auto-clears for one re-probe."""
    _QUOTA_EXHAUSTED_UNTIL.clear()
    key = "aliyun_b/qwen3-235b-a22b"
    _quota_record_exhausted(key)
    assert _quota_should_skip(key)
    # Backdate the skip-until past the TTL window
    _QUOTA_EXHAUSTED_UNTIL[key] = time.time() - 1
    assert not _quota_should_skip(key)
    # Entry was cleared, so a subsequent check is also a non-skip
    assert key not in _QUOTA_EXHAUSTED_UNTIL


def test_quota_success_clears_mark():
    """A clean success removes the quota-exhausted mark immediately."""
    _QUOTA_EXHAUSTED_UNTIL.clear()
    key = "aliyun_b/qwen3.7-max-2026-06-08"
    _quota_record_exhausted(key)
    assert _quota_should_skip(key)
    _quota_record_success(key)
    assert not _quota_should_skip(key)
    assert key not in _QUOTA_EXHAUSTED_UNTIL


def test_quota_skip_is_per_account_model():
    """Exhausting one model must not skip a different free model."""
    _QUOTA_EXHAUSTED_UNTIL.clear()
    exhausted = "aliyun_b/qwen3.7-max-2026-06-08"
    working = "aliyun_b/deepseek-v3.1"
    _quota_record_exhausted(exhausted)
    assert _quota_should_skip(exhausted)
    assert not _quota_should_skip(working)


def test_quota_ttl_is_meaningfully_long():
    """TTL must be long enough to stop per-request spin (>> the 60s CB cooldown)."""
    assert QUOTA_SKIP_TTL >= 3600.0
