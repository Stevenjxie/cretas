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

import pytest

from common import llm_router
from common.llm_router import (
    _QUOTA_EXHAUSTED_UNTIL,
    _quota_record_exhausted,
    _quota_record_success,
    _quota_should_skip,
    QUOTA_REPROBE_MIN_GAP,
    QUOTA_SKIP_TTL,
)


@pytest.fixture(autouse=True)
def _reset_reprobe_throttle():
    """2026-08-06: re-probe 放行是**全局限速**的 (QUOTA_REPROBE_MIN_GAP), 那个
    时间戳是跨测试的共享状态 —— 不重置的话, 先跑的用例抢走槽位会让后面任何
    「TTL 到期应放行」的断言假红。与 _QUOTA_EXHAUSTED_UNTIL.clear() 同性质。"""
    llm_router._QUOTA_LAST_REPROBE_AT = 0.0
    yield
    llm_router._QUOTA_LAST_REPROBE_AT = 0.0


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


def test_expired_marks_do_not_all_reprobe_at_once():
    """一批同时到期的耗尽标记, 一次只放行一个 re-probe。

    2026-08-06 prod 事故的直接回归: 一次失败请求把沿途 14 个候选在同一瞬间打标,
    它们的 TTL 于是同时到期; 旧实现逐个放行, 单个请求就把 14 个死模型全部真探
    一遍, 链的总预算在够到第 15 位那个活模型之前烧光 → `chain: total_timeout`,
    然后重新标记 6h, 6h 后原样复现。
    """
    _QUOTA_EXHAUSTED_UNTIL.clear()
    keys = [f"aliyun_c/dead-model-{i}" for i in range(14)]
    for k in keys:
        _quota_record_exhausted(k)
    # 全部回拨到窗口之外 —— 模拟「同一瞬间打标 → 同时到期」
    past = time.time() - 1
    for k in keys:
        _QUOTA_EXHAUSTED_UNTIL[k] = past

    released = [k for k in keys if not _quota_should_skip(k)]
    assert len(released) == 1, (
        f"一个请求内只应放行 1 个 re-probe, 实际放行 {len(released)} 个: {released}"
    )
    # 没抢到槽位的仍然被跳过(零成本), 而不是变成真实 HTTP 探测
    assert sum(1 for k in keys if _quota_should_skip(k)) == 13


def test_deferred_reprobe_does_not_inflate_strikes():
    """抢不到槽位只是顺延, 不算「又确认一次没额度」—— strikes 必须不变。

    strikes 驱动指数退避(6h→12h→24h)。如果顺延也加 strikes, 一个健康模型会因为
    排队被越退越久, 那是把节流机制变成惩罚机制。
    """
    _QUOTA_EXHAUSTED_UNTIL.clear()
    llm_router._QUOTA_STRIKES.clear()
    first, second = "aliyun_c/first", "aliyun_c/second"
    for k in (first, second):
        _quota_record_exhausted(k)
        _QUOTA_EXHAUSTED_UNTIL[k] = time.time() - 1
    strikes_before = llm_router._QUOTA_STRIKES.get(second)

    assert not _quota_should_skip(first)    # 抢到槽位
    assert _quota_should_skip(second)       # 被顺延
    assert llm_router._QUOTA_STRIKES.get(second) == strikes_before


def test_reprobe_slot_reopens_after_gap():
    """限速是「间隔」不是「一次性」—— 过了 GAP 之后下一个候选应能被放行。"""
    _QUOTA_EXHAUSTED_UNTIL.clear()
    a, b = "aliyun_c/alpha", "aliyun_c/beta"
    for k in (a, b):
        _quota_record_exhausted(k)
        _QUOTA_EXHAUSTED_UNTIL[k] = time.time() - 1
    assert not _quota_should_skip(a)
    assert _quota_should_skip(b)
    # 把上一次 re-probe 时间回拨到 GAP 之外, b 的顺延窗口也一并回拨
    llm_router._QUOTA_LAST_REPROBE_AT = time.time() - QUOTA_REPROBE_MIN_GAP - 1
    _QUOTA_EXHAUSTED_UNTIL[b] = time.time() - 1
    assert not _quota_should_skip(b)


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


# ── 2026-08-01: 跨进程持久化 + 退避递增 ────────────────────────────────────
# prod 实测 (python-prod.log, 07-25 ~ 08-01 共 7 天):
#   真发请求撞到的 403 : 608 次 ≈ 87/天
#   记忆命中的跳过     : 8283 次 (0 成本, 说明缓存本身是有效的)
#   成功               : 5496 次
# 单个模型 aliyun_c/qwen3.7-max 被撞 70 次 = 10 次/天, 而 6h TTL 的设计预期是
# 4 次/天 —— 多出来的约 60% 来自**重启和独立进程**: _QUOTA_EXHAUSTED_UNTIL 是
# 模块级进程内字典, 每次部署重启就清零, 每个脚本进程(每日审计/eval/探针)也各撞一遍。
#
# ⛔ 不做「永久拉黑」: 6h TTL 是**故意**的, 为的是接住每月免费额度重置(见本模块
# 435-449 行注释)。永久拉黑 = 额度回来了模型也永远不再启用。改成退避递增。

def test_repeated_exhaustion_backs_off_instead_of_probing_at_a_fixed_rate():
    """连续撞到同一个模型 → 跳过窗口翻倍, 对真的已死的模型少试探。"""
    from common.llm_router import _quota_skip_seconds_for, QUOTA_SKIP_TTL_MAX

    key = "acct_x/model-dead"
    _quota_record_success(key)  # 归零起点

    _quota_record_exhausted(key)
    first = _quota_skip_seconds_for(key)
    assert abs(first - QUOTA_SKIP_TTL) < 5, first

    _quota_record_exhausted(key)
    second = _quota_skip_seconds_for(key)
    assert second > first * 1.5, (first, second)

    for _ in range(8):
        _quota_record_exhausted(key)
    assert _quota_skip_seconds_for(key) <= QUOTA_SKIP_TTL_MAX + 5


def test_a_clean_success_resets_the_backoff_not_just_the_mark():
    """额度回来了要立刻回到最短窗口, 否则一次抖动会长期压制一个好模型。"""
    from common.llm_router import _quota_skip_seconds_for

    key = "acct_x/model-recovers"
    for _ in range(5):
        _quota_record_exhausted(key)
    _quota_record_success(key)
    _quota_record_exhausted(key)
    assert abs(_quota_skip_seconds_for(key) - QUOTA_SKIP_TTL) < 5


def test_state_survives_process_restart(tmp_path, monkeypatch):
    """进程内字典清零后仍应跳过 —— 这正是每次部署都要重新撞 403 的根因。"""
    from common import llm_router

    monkeypatch.setattr(llm_router, "_QUOTA_STATE_PATH", str(tmp_path / "q.json"))
    key = "acct_p/model-p"
    _quota_record_success(key)
    _quota_record_exhausted(key)
    assert _quota_should_skip(key) is True

    # 模拟新进程: 内存清空, 只能靠磁盘
    llm_router._QUOTA_EXHAUSTED_UNTIL.clear()
    llm_router._QUOTA_STRIKES.clear()
    assert _quota_should_skip(key) is False, "清空后未加载, 前提不成立"
    llm_router._quota_load_state()
    assert _quota_should_skip(key) is True, "重启后忘记了 —— 下一批请求会重新撞 403"


def test_unwritable_state_file_never_breaks_the_router(tmp_path, monkeypatch):
    """持久化在**调用主路径上**, 它出问题必须降级成纯内存, 不能抛。"""
    from common import llm_router

    monkeypatch.setattr(
        llm_router, "_QUOTA_STATE_PATH", str(tmp_path / "no-such-dir" / "q.json"),
    )
    key = "acct_q/model-q"
    _quota_record_success(key)
    _quota_record_exhausted(key)          # 不能抛
    assert _quota_should_skip(key) is True  # 内存仍然生效
    llm_router._quota_load_state()          # 读不到也不能抛
