"""
Multi-provider LLM router with 403 AllocationQuota.FreeTierOnly fallback.

Per-slot DEEP fallback chains (rebuilt 2026-05-31, Steve directive): each SLOT
has a list of (account, model) entries — ≥10 free-tier models spanning
aliyun_c → aliyun_b → aliyun_a → zhipu (see SLOT_MODELS). The router tries them
in order; an exhausted free model returns 403 FreeTierOnly and falls to the
NEXT entry, so it keeps trying free models across all 3 aliyun accounts before
any chance of error. EVERY entry is FREE + "免费额度用完即停=已开启" per the
console (memory reference_dashscope_free_model_allowlist) — NO paid SKU is ever
in a chain, so paid billing is structurally impossible.

No client-side quota estimation — we rely on Aliyun's 免费额度用完即停 toggle
which returns 403 when free quota exhausts. This is the HARD guarantee that
accounts will never be charged beyond free tier. Circuit breaker is keyed per
(account, model) so one model's quota-403 doesn't skip other free models on the
same account.

The metrics hook in common/llm_metrics.py records each attempt (including
failed fallback attempts) so we have per-account usage visibility.

Usage:
    from common.llm_router import call_chain, SLOT
    with llm_caller_context("chart", factory_id="F001"):
        resp_json = await call_chain(SLOT.CHART, payload)

    # Streaming variant (Apr 25 2026, E2a):
    from common.llm_router import call_chain_stream, SLOT
    with llm_caller_context("chat"):
        async for event in call_chain_stream(SLOT.CHAT, payload):
            if event["type"] == "delta":
                print(event["text"], end="")
            elif event["type"] == "usage":
                total = event["tokens"]
"""
from __future__ import annotations

import asyncio
import json
import datetime
import logging
import os
import time
from enum import Enum
from threading import Lock
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import httpx

from common.llm_client import get_llm_http_client

logger = logging.getLogger(__name__)


# ─── Runtime PAID-model denylist guard (2026-06-11 key rotation incident) ───
# Belt-and-suspenders: even if config drift sneaks a PAID (off-free-allowlist)
# model back into a chain, the per-attempt call path REFUSES to call it and
# skips to the next chain entry. These exact codes caused the 2026-06-11 bill
# (keys rotated to new accounts → stale allowlists → these off-list models
# silently billed when free heads exhausted). Aliyun's 免费额度用完即停 toggle
# ONLY protects models ON each account's free allowlist; an off-list model has
# no toggle and bills silently.
_PAID_MODEL_DENYLIST: frozenset = frozenset({
    "qwen3-max",              # bare — not on any rotated account's free list
    "qwen3-max-2026-01-23",   # off-list on rotated accounts
    "qwen-max",               # bare — not free
    "qwen-plus",              # bare — use qwen-plus-latest (free) instead
    "qwen3.5-122b-a10b",      # not on allowlist
})

# Per-account free-only allowlist (2026-06-11 console audit). aliyun_a (90bc) was
# the BILLED account: only these SKUs have real free quota; its other ~32 models
# show "- -" (no free quota = PAID if called, incl qwen3-max-2026-01-23 / glm-5 /
# deepseek-v4-pro — which ARE free on aliyun_c/b/tencent so can't be globally
# denylisted). The call path REFUSES any aliyun_a model not in this set (skips to
# next chain entry) — physical guard against A's paid landmines even on config drift.
_ACCOUNT_FREE_ONLY: Dict[str, frozenset] = {
    "aliyun_a": frozenset({
        "qwen3.7-max-2026-06-08", "qwen3.7-plus", "qwen3.7-plus-2026-05-26",
        "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3.7-max-2026-05-20",
        "qwen3.5-plus-2026-04-20", "kimi-k2.6", "qwen3.6-27b", "qwen3.6-flash",
    }),
    # aliyun_a_deepseek = A 的 key 调 deepseek 类; A 上 deepseek-v4-pro/flash 全 "- -"
    # (付费) → 空集 = 守卫拒绝该账号上的所有模型 (强制走 tencent 免费 deepseek).
    "aliyun_a_deepseek": frozenset(),
}

# Expiry-aware account ordering (2026-06-11 console audit). Free quota is
# "use-it-or-lose-it": spend the SOONEST-expiring account's quota first while it's
# valid; an account auto-sinks below fresher ones once its bulk quota expires
# (so the head SWITCHES automatically at the expiry date — no redeploy needed).
#   aliyun_b (3177) bulk expires 2026-07-16 → use FIRST until then
#   aliyun_c (a736) bulk expires 2026-08-13 → 2nd now, HEAD after 07/16
#   aliyun_a (90bc) consumed                → always last
# qwen3.7-* SKUs (08/20-09/08) are the long-runway backbone; re-audit before 08/13.
_ALIYUN_B_EXPIRY = datetime.date(2026, 7, 16)
_ALIYUN_C_EXPIRY = datetime.date(2026, 8, 13)

# Only the qwen3.7-* SKUs survive past each account's bulk expiry (their free quota
# runs to 08/20-09/08). After bulk expiry, an account's OTHER models become "- -"
# (no free quota = PAID) — exactly how A(90bc) got billed. _is_expired_paid date-gates
# B (after 07/16) and C (after 08/13) to ONLY these survivors (Fable audit 2026-06-11 #4).
_QWEN37_SURVIVORS = frozenset({
    "qwen3.7-max-2026-06-08", "qwen3.7-plus", "qwen3.7-plus-2026-05-26",
    "qwen3.7-max", "qwen3.7-max-2026-05-17", "qwen3.7-max-2026-05-20",
    "qwen3.7-max-preview",
})


def _is_expired_paid(account: str, model: str) -> bool:
    """True if a model's free quota has expired on its account (→ would bill).
    After B's 07/16 / C's 08/13 bulk expiry, only qwen3.7-* SKUs still have free quota."""
    today = datetime.date.today()
    if account == "aliyun_b" and today >= _ALIYUN_B_EXPIRY:
        return model not in _QWEN37_SURVIVORS
    if account == "aliyun_c" and today >= _ALIYUN_C_EXPIRY:
        return model not in _QWEN37_SURVIVORS
    return False


def _account_rank(account: str) -> int:
    """Lower = tried first. Expiry-aware so perishable free quota is spent first
    and an expired account sinks below fresher ones (head auto-switches at expiry)."""
    today = datetime.date.today()
    if account == "aliyun_b":
        return 0 if today < _ALIYUN_B_EXPIRY else 50      # B first → sink after 07/16
    if account == "aliyun_c":
        if today < _ALIYUN_B_EXPIRY:
            return 10                                     # 2nd now (preserve C runway)
        return 5 if today < _ALIYUN_C_EXPIRY else 50      # HEAD after 07/16 → sink after 08/13
    if account == "tencent":
        return 20
    if account == "zhipu":
        return 30
    if account in ("aliyun_a", "aliyun_a_deepseek"):
        return 60                                         # consumed account always last
    return 40


def _expiry_aware_sort(chain: List[Tuple[str, str]]) -> List[Tuple[str, str]]:
    """Stable-sort a chain by expiry-aware account rank (preserves each account's
    internal model order). Auto-switches B↔C head as their free quotas expire."""
    return sorted(chain, key=lambda am: _account_rank(am[0]))


def _log_cache_and_record_budget(slot_value: str, account: str, model: str, body: Dict[str, Any]) -> None:
    """Parse usage from a successful response: log cache-hit ratio. Mirrors
    the streaming path's [cache] log line so observability is uniform across
    both paths.

    DashScope emits `prompt_tokens_details.cached_tokens`; we read it
    defensively along with the legacy `prompt_cache_hit_tokens` field that
    older providers used.
    """
    try:
        usage = (body or {}).get("usage") or {}
        prompt_total = int(usage.get("prompt_tokens") or 0)
        completion = int(usage.get("completion_tokens") or 0)
        details = usage.get("prompt_tokens_details") or {}
        cached = int(
            details.get("cached_tokens")
            or usage.get("prompt_cache_hit_tokens")
            or 0
        )
        if prompt_total > 0:
            pct = 100 * cached // prompt_total if cached else 0
            logger.info(
                f"[cache] slot={slot_value} via {account}/{model}: "
                f"prompt={prompt_total} cached={cached} ({pct}%) completion={completion}"
            )
    except Exception as e:
        logger.debug(f"[cache] parse failed (non-fatal): {e}")


# ─── Per-account circuit breaker (J1 — Apr 24 2026) ───
# When aliyun_b/glm-5 rate-limits, every chart-recommend used to pay the full
# cascade (~28s observed in prod). After CB_THRESHOLD consecutive failures of
# a provider, skip it for CB_COOLDOWN seconds. Resets on first success or
# after cooldown expires.
_CB_FAILURES: Dict[str, int] = {}        # provider name → consecutive failure count
_CB_LAST_FAIL: Dict[str, float] = {}     # provider name → unix ts of last failure
_CB_LOCK = Lock()

CB_THRESHOLD = 2      # consecutive failures before skip kicks in (Apr 28 was 3)
CB_COOLDOWN = 60.0    # seconds to skip after threshold reached


def _cb_should_skip(provider: str) -> bool:
    """Return True if the provider is currently in cooldown.

    Auto-resets the failure counter when cooldown elapses so the provider
    gets one re-probe attempt; if that probe fails the counter starts again.
    """
    with _CB_LOCK:
        fails = _CB_FAILURES.get(provider, 0)
        if fails < CB_THRESHOLD:
            return False
        last = _CB_LAST_FAIL.get(provider, 0.0)
        if (time.time() - last) < CB_COOLDOWN:
            return True
        # Cooldown elapsed — reset and allow a re-probe
        _CB_FAILURES[provider] = 0
        return False


def _cb_record_failure(provider: str) -> None:
    """Increment failure counter and stamp the failure time."""
    with _CB_LOCK:
        _CB_FAILURES[provider] = _CB_FAILURES.get(provider, 0) + 1
        _CB_LAST_FAIL[provider] = time.time()


def _cb_record_success(provider: str) -> None:
    """Reset failure counter on a clean success."""
    with _CB_LOCK:
        if _CB_FAILURES.get(provider):
            _CB_FAILURES[provider] = 0


def get_cb_stats() -> Dict[str, Any]:
    """Snapshot of circuit-breaker state for ops visibility."""
    with _CB_LOCK:
        now = time.time()
        skip_now = [
            p for p, n in _CB_FAILURES.items()
            if n >= CB_THRESHOLD and (now - _CB_LAST_FAIL.get(p, 0.0)) < CB_COOLDOWN
        ]
        return {
            "failures": dict(_CB_FAILURES),
            "last_fail": dict(_CB_LAST_FAIL),
            "skip_now": skip_now,
            "threshold": CB_THRESHOLD,
            "cooldown_seconds": CB_COOLDOWN,
        }


# ─── Quota-exhaustion skip cache (2026-06-22) ───
# The circuit breaker above re-probes every CB_COOLDOWN (60s). That cadence is
# correct for *transient* failures (timeout / 5xx) but wrong for free-tier
# *quota* exhaustion, which lasts until the account's MONTHLY reset. Re-probing
# an exhausted model every 60s just burns a 403 round-trip on every request
# (egress audit 2026-06-22: qwen3.7-max-2026-06-08 on aliyun_b = 409 calls/7d,
# 0 success, all 403 FreeTierOnly — pure spin before falling to a working SKU).
#
# So when a model returns a *quota* signal (403 FreeTierOnly / 429 / 402, i.e.
# _is_quota_exhausted is True), skip that (account,model) for QUOTA_SKIP_TTL —
# long enough to stop the per-request spin, short enough to re-probe a few times
# a day and pick up the monthly free-quota reset. This is layered ON TOP of the
# circuit breaker (both checked before a call); a clean success clears both.
#
# Quality is preserved: premium SKUs (qwen3.7-max for INSIGHTS/REVIEW) still sit
# at the chain head and are tried first after each TTL re-probe / monthly reset —
# they're only skipped while *known* exhausted, not demoted out of the chain.
_QUOTA_EXHAUSTED_UNTIL: Dict[str, float] = {}   # "account/model" → unix ts to skip until
_QUOTA_LOCK = Lock()
QUOTA_SKIP_TTL = 6 * 3600.0    # 6h: re-probe ~4×/day to catch monthly free-quota reset


def _quota_should_skip(cb_key: str) -> bool:
    """True if this (account,model) returned a quota signal within QUOTA_SKIP_TTL.

    Auto-clears the mark once the TTL elapses so the model gets one re-probe; if
    that probe is still quota-exhausted it is re-marked for another TTL window.
    """
    with _QUOTA_LOCK:
        until = _QUOTA_EXHAUSTED_UNTIL.get(cb_key, 0.0)
        if until <= 0.0:
            return False
        if time.time() < until:
            return True
        # TTL elapsed — clear and allow a re-probe
        del _QUOTA_EXHAUSTED_UNTIL[cb_key]
        return False


def _quota_record_exhausted(cb_key: str) -> None:
    """Mark this (account,model) as quota-exhausted for QUOTA_SKIP_TTL seconds."""
    with _QUOTA_LOCK:
        _QUOTA_EXHAUSTED_UNTIL[cb_key] = time.time() + QUOTA_SKIP_TTL


def _quota_record_success(cb_key: str) -> None:
    """Clear the quota-exhausted mark on a clean success (quota came back)."""
    with _QUOTA_LOCK:
        _QUOTA_EXHAUSTED_UNTIL.pop(cb_key, None)


def get_quota_skip_stats() -> Dict[str, Any]:
    """Snapshot of currently quota-skipped (account,model) pairs for ops visibility."""
    with _QUOTA_LOCK:
        now = time.time()
        return {
            "skipped": {k: round(v - now, 1) for k, v in _QUOTA_EXHAUSTED_UNTIL.items() if v > now},
            "ttl_seconds": QUOTA_SKIP_TTL,
        }


class SLOT(str, Enum):
    """Logical slot that maps to a specific model per provider."""
    CHAT = "chat"
    INSIGHTS = "insights"
    CHART = "chart"
    MAPPER = "mapper"
    REASONING = "reasoning"
    VL = "vl"
    REVIEW = "review"


# ─── Slot → per-provider model name ───
# May 14 2026 — added aliyun_c (3rd Aliyun bailian account, brand-new free
# quota intact). May 13 2026 mid-month re-audit + #580 Option 2 simplification.
#
# Audit sources (per `tests/qa-llm-quota/audit-matrix.md`):
#   1. Live SKU probe vs prod keys.
#   2. Steve console-screenshot audit (Aliyun bailian + Zhipu open.bigmodel).
#
# Working free SKUs in use:
#   aliyun_c: NEW account May 14 2026 — SKU choices driven by speed+quality
#             benchmark (scripts/benchmark-llm-account.py, results in
#             tests/qa-llm-quota/2026-05-14-aliyun-c-benchmark.md). For each
#             SLOT, 4-6 allowlist-approved candidates were run 3× with a
#             representative prompt; the winner per SLOT was chosen on
#             combined latency + output quality:
#               CHAT      : qwen-flash               (1.4s, clean concise, no hallucination)
#               INSIGHTS  : qwen-flash               (1.2s, 1-sentence精炼洞察, 26 tokens)
#               CHART     : qwen-turbo               (1.1s, valid compact JSON; glm-5 was 60s)
#               MAPPER    : qwen-turbo               (1.2s, correct mapping direction)
#               REASONING : deepseek-v4-pro          (22.7s, best depth+structure; acceptable
#                                                    latency for non-interactive REASONING)
#               VL        : qwen3-vl-plus-2025-12-19 (only allowlist VL option;
#                                                    -2025-05-07 is 404 NOSKU on new accounts)
#               REVIEW    : qwen3-max-2026-01-23     (9.3s, concise+complete;
#                                                    deepseek-r1-distill-qwen-32b emitted EMPTY
#                                                    output on benchmark — broken for REVIEW)
#             aliyun_c sits at chain HEAD so its fresh quota consumes first
#             before aliyun_b/a deplete.
#
#             ⛔ HARD CONSTRAINT (Steve May 14 2026): aliyun_c may use ONLY
#             SKUs from Steve's allowlist screenshot. Any SKU outside that
#             list must NOT be added on this account without re-confirmation,
#             even if the API allows it. If quota on these 7 ever exhausts,
#             fall through to aliyun_b/a per the chain — do not silently swap
#             in another C-account SKU.
#
#             ↑ RE-CONFIRMED + EXPANDED (Steve May 31 2026): c's original cheap
#             SKUs (qwen-flash CHAT/INSIGHTS, qwen-turbo CHART/MAPPER,
#             deepseek-v4-pro REASONING) hit FREE-TIER EXHAUSTED (403
#             AllocationQuota.FreeTierOnly). Live probe confirmed these strong
#             SKUs have INTACT free pools on C and Steve approved adding them to
#             the c allowlist: qwen-plus (CHAT), qwen3-max (INSIGHTS/CHART),
#             qwen3-235b-a22b (MAPPER), deepseek-v3 (REASONING). VL
#             (qwen3-vl-plus-2025-12-19) + REVIEW (qwen3-max-2026-01-23) still
#             intact, unchanged. The "do not silently swap" rule still holds —
#             any FURTHER c SKU change needs Steve re-confirm + a fresh probe.
#   aliyun_b: qwen-max (CHAT), qwen3.6-35b-a3b (INSIGHTS), glm-5 (CHART),
#             qwen3.5-122b-a10b (MAPPER), qwen3.5-397b-a17b (REASONING),
#             qwen3-vl-plus-2025-12-19 (VL), qwen-max (REVIEW — May 14 fix:
#             was deepseek-r1-distill-qwen-32b which emits EMPTY output on
#             REVIEW prompts, universal bug; see SLOT.REVIEW comment)
#   aliyun_a: qwen3.6-max-preview (CHAT), qwen3.6-35b-a3b (INSIGHTS),
#             glm-5 (CHART), qwen3.5-122b-a10b (MAPPER),
#             qwen3.5-397b-a17b (REASONING + REVIEW),
#             qwen3-vl-plus-2025-12-19 (VL)
#   zhipu   : glm-4.5-air (most slots — 6.5M model-specific pool, NOT in 通用池),
#             glm-4.6v (VL — 6M model-specific pool)
#   aliyun_a_deepseek: deepseek-v4-pro (5 slots; None for VL/REVIEW).
#                      Same endpoint+key as aliyun_a, different model class.
#                      DashScope-hosted deepseek-v4-pro has its own free pool
#                      (~999K/month on Aliyun-A per Steve audit), independent
#                      of the qwen-* pool that aliyun_a consumes.
#
# Chain — 5 providers, all free:
#   aliyun_c → aliyun_b → aliyun_a → zhipu → aliyun_a_deepseek
#
# Why no DeepSeek-official tail any more (#580 Option 2): account balance 0
# across all SKUs, 402 fell through "Other errors" path but never reached a
# next provider (end of chain), making the 5th slot a no-op. With
# `aliyun_a_deepseek` already covering DeepSeek-class quality via free quota,
# DeepSeek-official is redundant. Removed (see #580, PR docs/issue-580-…).
# If a paid cross-vendor fallback is needed again, top up DeepSeek balance
# + re-add `"deepseek"` chain entry — entire removal was 1 file.
#
# Triggered by prod incident "All providers exhausted for chat" 2026-05-13.
#
# Each provider's 403/429/402 triggers fallback per `_is_quota_exhausted`.
# DeepSeek-official balance-0 returns 402 'Insufficient Balance' — issue #581
# added that case so the chain logs WARNING quota-exhausted instead of generic
# ERROR before exhausting cleanly.
# =====================================================================
# Free-tier deep fallback chains — rebuilt 2026-05-31 (Steve directive:
# "每个 slot 至少 10 个 fallback, 把所有可用免费模型都串进 fallback").
#
# ⛔ HARD RULE: EVERY (account, model) below MUST be a model that is FREE +
# "免费额度用完即停 = 已开启" on THAT account per the DashScope console (recorded
# in memory reference_dashscope_free_model_allowlist). A code NOT on the
# account's free-enabled list = PAID billing. With 已开启 ON, an exhausted free
# model returns 403 AllocationQuota.FreeTierOnly → router falls to the NEXT
# entry, never billing paid. A long chain = keep trying free models across all
# 3 aliyun accounts + zhipu until one has quota; only "every free model on
# every account exhausted" surfaces an error — a paid charge is structurally
# impossible (no paid SKU is ever in a chain).
#
# Each slot = slot-tuned HEAD + shared _TEXT_TAIL (deduped) → ≥10 fallbacks.
# Order: aliyun_c (freshest, exp 2026/08) → aliyun_b → aliyun_a (small free
# list) → tencent (TokenHub free trial) → zhipu (independent GLM pool). VL slot
# uses a vision-only chain.
#
# tencent (TokenHub, added 2026-06-01): 腾讯云 TokenHub 90-day free trial — 语言
# 模型 50万-100万 token 免费额度 (OpenAI-compatible, base
# https://tokenhub.tencentmaas.com/v1, Bearer key from env LLM_TENCENT_API_KEY).
# Probed-free codes: deepseek-v4-pro / deepseek-v4-flash / glm-5.1 / glm-5 /
# kimi-k2.6 / qwen3.5-flash / minimax-m2.7. TokenHub free trial is "用完即停"
# like aliyun (Steve 2026-06-01) — when the 90-day window / token quota is
# consumed it STOPS; it does NOT silently fall to account-balance billing. So
# tencent is structurally as safe as aliyun (no paid SKU reachable) and is
# placed by QUALITY, not buried in a never-reached deep tail: deepseek-v4-pro
# (best free reasoner, FREE on TokenHub, NOT free on aliyun) HEADS the REASONING
# + INSIGHTS slots, and tencent fast models (qwen3.5-flash / deepseek-v4-flash /
# glm-5.1) sit 2nd on CHAT/MAPPER/CHART/REVIEW so the trial quota is actually
# used before it expires. High-freq CHAT/MAPPER still LEAD with a proven aliyun
# fast model (latency); the circuit breaker auto-skips tencent if it ever flakes.
# Remaining tencent codes (kimi-k2.6 / minimax-m2.7) live in _TEXT_TAIL. Re-audit
# when the 90-day trial expires. NOTE: deepseek-v4-pro is FORBIDDEN on aliyun (no
# free list) but FREE on tencent — the rule is aliyun-scoped, $0 on TokenHub.
#
# ⚠️ NEVER add (NOT free-enabled, would bill): aliyun deepseek-v4-pro (no free list);
# glm-5 on B (only glm-4.5/4.6/4.7 free on B; glm-5 IS free on C); qwen3.7-max /
# qwen3.7-max-preview / qwen3.7-max-2026-05-20 (C/A 未开启 → bill when exhausted);
# any bare max-class on A (A free list is tiny: see _A_FREE below).
# Free quotas EXPIRE (A: 2026/06-08, B/C: 2026/07-08) → re-audit + rotate; when a
# model 403s persistently, confirm it's still on the console free-enabled list.
# =====================================================================


def _dedup_chain(pairs: List[Tuple[str, str]]) -> List[Tuple[str, str]]:
    seen: set = set()
    out: List[Tuple[str, str]] = []
    for ac, m in pairs:
        if (ac, m) not in seen:
            seen.add((ac, m))
            out.append((ac, m))
    return out


# Universal free-text fallback tail (appended to every text slot).
# Rebuilt 2026-06-11 (key rotation incident): keys were rotated to new accounts
# so the per-account free allowlists are stale. The OLD tail hit bare
# `qwen3-max` / `qwen-max` / `qwen-plus` (NOT on the free allowlists = PAID) and
# blew up the bill. EVERY entry below is now on the new free allowlist for its
# account (see reference_dashscope_free_model_allowlist 2026-06-11 section).
# Order: aliyun_c (a736, UNTOUCHED fresh) → aliyun_b (3177, huge free catalog) →
# tencent (free 用完即停) → zhipu (free 用完即停) → aliyun_a (90bc, partially
# consumed — LAST, only its tiny remaining-free list, no low-runway SKUs).
_TEXT_TAIL: List[Tuple[str, str]] = [
    # ── 终极 router (2026-06-11 控制台 ground-truth: 额度+过期) ──
    # aliyun_c (a736) — 最新+过期最晚(bulk 08/13, qwen3.7-* 到 09/08). 链头.
    ("aliyun_c", "qwen3.7-max-2026-06-08"), ("aliyun_c", "qwen3-max-preview"),
    ("aliyun_c", "qwen3-235b-a22b"), ("aliyun_c", "qwen3.5-397b-a17b"),
    ("aliyun_c", "deepseek-v3.1"), ("aliyun_c", "deepseek-r1"),
    ("aliyun_c", "qwen3.5-flash-2026-02-23"), ("aliyun_c", "qwen3.6-flash-2026-04-16"),
    ("aliyun_c", "qwen-plus-latest"), ("aliyun_c", "glm-5"), ("aliyun_c", "glm-4.6"),
    # aliyun_b (3177) — 满额但 bulk 07/16 较早. 2nd.
    ("aliyun_b", "qwen3.7-max-2026-06-08"), ("aliyun_b", "qwen3-235b-a22b"),
    ("aliyun_b", "qwen-flash-2025-07-28"), ("aliyun_b", "qwen3.5-flash-2026-02-23"),
    ("aliyun_b", "deepseek-v3.1"), ("aliyun_b", "glm-4.6"), ("aliyun_b", "qwen-plus-latest"),
    # tencent (m00t) TokenHub free 用完即停
    ("tencent", "deepseek-v4-pro"), ("tencent", "glm-5.1"),
    ("tencent", "qwen3.5-flash"), ("tencent", "kimi-k2.6"),
    ("tencent", "deepseek-v4-flash"), ("tencent", "minimax-m2.7"),
    # zhipu (uUgu) free 用完即停
    ("zhipu", "glm-4.5-air"),
    # aliyun_a (90bc) — LAST. ⛔仅控制台确认有额度的 SKU(其余 32 个 "- -" = 付费雷, 含
    # qwen3-max-2026-01-23/glm-5/deepseek-v4-pro). 由 _A_SAFE per-account 守卫双保险.
    ("aliyun_a", "qwen3.7-max-2026-06-08"), ("aliyun_a", "qwen3.7-plus"),
    ("aliyun_a", "qwen3.6-27b"), ("aliyun_a", "kimi-k2.6"),
    ("aliyun_a", "qwen3.5-plus-2026-04-20"),
]

# VL-only chain. aliyun_c/b VL 模型控制台确认免费; aliyun_a VL 全 "- -" → 不含.
_VL_CHAIN: List[Tuple[str, str]] = _dedup_chain([
    ("aliyun_c", "qwen3-vl-plus-2025-12-19"), ("aliyun_c", "qwen-vl-max"),
    ("aliyun_c", "qwen3-vl-plus"), ("aliyun_c", "qwen3-vl-32b-instruct"),
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"), ("aliyun_c", "qwen3-vl-30b-a3b-instruct"),
    ("aliyun_b", "qwen3-vl-plus-2025-12-19"), ("aliyun_b", "qwen-vl-max"),
    ("aliyun_b", "qwen3-vl-plus"), ("aliyun_b", "qwen3-vl-32b-instruct"),
    ("zhipu", "glm-4.6v"),
])

# SLOT_MODELS — 终极 router. 头用 C 上控制台确认有额度的模型(避开 qwen-turbo/bare
# qwen-flash/bare qwen3.6-flash —— 这些在 a736 上 403 耗尽/不在清单). 质量用最长
# runway qwen3.7-max-2026-06-08(三账号都 09/08). 快用 dated flash(C 有额度).
SLOT_MODELS: Dict[SLOT, List[Tuple[str, str]]] = {
    # CHAT — 高频低延迟 → C dated flash 头(48万), B flash 2nd, tencent fast 3rd.
    SLOT.CHAT: _dedup_chain([
        ("aliyun_c", "qwen-flash-2025-07-28"), ("aliyun_c", "qwen3.5-flash-2026-02-23"),
        ("aliyun_b", "qwen-flash-2025-07-28"),
        ("tencent", "qwen3.5-flash"), ("tencent", "deepseek-v4-flash"),
    ] + _TEXT_TAIL),
    # INSIGHTS — 质量 → C 最新 max(09/08), B 2nd, tencent v4-pro 3rd.
    SLOT.INSIGHTS: _dedup_chain([
        ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ("aliyun_b", "qwen3.7-max-2026-06-08"),
        ("tencent", "deepseek-v4-pro"),
    ] + _TEXT_TAIL),
    # CHART — compact JSON → C dated flash 头(非推理, C 98.9万), glm 仅后备(避免空content).
    SLOT.CHART: _dedup_chain([
        ("aliyun_c", "qwen3.5-flash-2026-02-23"), ("aliyun_c", "qwen3.6-flash-2026-04-16"),
        ("aliyun_b", "qwen3.5-flash-2026-02-23"),
        ("tencent", "glm-5.1"), ("aliyun_c", "glm-5"), ("aliyun_b", "glm-4.6"),
    ] + _TEXT_TAIL),
    # MAPPER — 字段映射 → C dated flash 头, B flash 2nd, tencent v4-flash 3rd.
    SLOT.MAPPER: _dedup_chain([
        ("aliyun_c", "qwen3.5-flash-2026-02-23"), ("aliyun_b", "qwen-flash-2025-07-28"),
        ("tencent", "deepseek-v4-flash"),
        ("aliyun_c", "qwen3-235b-a22b"), ("aliyun_b", "qwen3-235b-a22b"),
    ] + _TEXT_TAIL),
    # REASONING — 深度 → C free deepseek/MoE 头, B 2nd, tencent v4-pro 3rd.
    SLOT.REASONING: _dedup_chain([
        ("aliyun_c", "deepseek-v3.1"),
        ("aliyun_b", "qwen3.5-397b-a17b"),
        ("tencent", "deepseek-v4-pro"),
        ("aliyun_c", "qwen3-235b-a22b"), ("aliyun_c", "deepseek-r1"),
    ] + _TEXT_TAIL),
    # VL — 仅视觉链.
    SLOT.VL: _VL_CHAIN,
    # REVIEW — 中文 critique → C 最新 max 头(09/08), B 2nd, tencent v4-pro 3rd.
    SLOT.REVIEW: _dedup_chain([
        ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ("aliyun_b", "qwen3.7-max-2026-06-08"),
        ("tencent", "deepseek-v4-pro"),
    ] + _TEXT_TAIL),
}


def _provider_config(account: str) -> Tuple[str, str]:
    """Return (base_url, api_key) for a provider account."""
    mapping = {
        # aliyun_c (May 14 2026): 3rd Aliyun bailian account — brand-new free
        # quota intact (1M/SKU per Steve console screenshot). Sits at chain
        # HEAD so its fresh quota consumes first, preserving aliyun_b/a balances.
        "aliyun_c": (
            os.getenv("LLM_ALIYUN_C_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            os.getenv("LLM_ALIYUN_C_API_KEY", ""),
        ),
        "aliyun_a": (
            os.getenv("LLM_ALIYUN_A_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            os.getenv("LLM_ALIYUN_A_API_KEY") or os.getenv("LLM_API_KEY", ""),
        ),
        # aliyun_a_deepseek (May 13 2026): same endpoint + key as aliyun_a, but
        # SLOT_MODELS routes DeepSeek-class SKUs (deepseek-v4-pro) here. DashScope
        # compatible-mode hosts those models with their own free-quota pool
        # (~999K intact on Steve's screenshot 2026-05-13) — independent of the
        # qwen-* quota that the `aliyun_a` slot consumes. After #580 Option 2
        # this is now the SOLE DeepSeek-class entry in the chain.
        "aliyun_a_deepseek": (
            os.getenv("LLM_ALIYUN_A_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            os.getenv("LLM_ALIYUN_A_API_KEY") or os.getenv("LLM_API_KEY", ""),
        ),
        "aliyun_b": (
            os.getenv("LLM_ALIYUN_B_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            os.getenv("LLM_ALIYUN_B_API_KEY", ""),
        ),
        # tencent (TokenHub, June 1 2026): 腾讯云 TokenHub 90-day free trial,
        # OpenAI-compatible. "用完即停" like aliyun (no silent paid billing), so
        # placed by quality: deepseek-v4-pro heads REASONING/INSIGHTS, tencent
        # fast models sit 2nd elsewhere, rest in _TEXT_TAIL. See top-of-file note.
        "tencent": (
            os.getenv("LLM_TENCENT_BASE_URL", "https://tokenhub.tencentmaas.com/v1"),
            os.getenv("LLM_TENCENT_API_KEY", ""),
        ),
        "zhipu": (
            os.getenv("LLM_ZHIPU_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"),
            os.getenv("LLM_ZHIPU_API_KEY", ""),
        ),
    }
    return mapping.get(account, ("", ""))


# Chain order — providers, all free tier:
#   aliyun_c → aliyun_b → aliyun_a → tencent → zhipu (→ aliyun_a_deepseek)
#
# tencent (June 1 2026): TokenHub 90-day free trial, between aliyun + zhipu.
#
# aliyun_c (May 14 2026): 3rd Aliyun bailian account, brand-new free quota.
# Placed at HEAD so its fresh 1M/SKU pool consumes first before aliyun_b/a
# deplete — preserves runway on the older accounts.
#
# Each provider's 403/AllocationQuota.FreeTierOnly + 429 triggers fallback
# per `_is_quota_exhausted`. After a full cascade exhausts the chain raises
# RuntimeError; callers handle this (e.g., agent_orchestrator returns a
# degraded response).
#
# History:
#   - May 9 2026 (free-first re-order, PR #215): chain ordered free → paid
#     to avoid the $19.49/12-day DeepSeek-official cost incident.
#   - May 13 2026 (PR #577 + #578): mid-month SKU refresh after prod incident
#     "All providers exhausted for chat". Added `aliyun_a_deepseek` 5th entry
#     routing deepseek-v4-pro via DashScope free quota.
#   - May 13 2026 (#580 Option 2): dropped deepseek-official 5th slot since
#     `aliyun_a_deepseek` already covers DeepSeek-class quality on free tier
#     and deepseek-official balance is 0 anyway.
#   - May 14 2026: added aliyun_c at chain HEAD — 3rd Aliyun
#     bailian account, brand-new free quota. Probe 12/13 candidate SKUs 200
#     OK (qwen3-vl-plus-2025-05-07 404 NOSKU on new accounts, deprecated;
#     replaced with the 2025-12-19 SKU used by aliyun_a/b).
#   - June 1 2026 (this commit): added `tencent` (TokenHub 90-day free trial)
#     in the deep _TEXT_TAIL between aliyun + zhipu, and deepseek-v4-pro to the
#     REASONING head (free on tencent, NOT free on aliyun). See top-of-file note.
#
# Re-audit recommended ~every 2 weeks or whenever "All providers exhausted"
# log line reappears (per `tests/qa-llm-quota/audit-matrix.md` cadence note).
DEFAULT_CHAIN: List[str] = [
    "aliyun_c", "aliyun_b", "aliyun_a", "tencent", "zhipu", "aliyun_a_deepseek",
]


def _is_quota_exhausted(status_code: int, body_text: str) -> bool:
    """Detect 免费额度用完即停 / rate-limit / quota-exceeded from response."""
    if status_code == 403:
        return "FreeTierOnly" in body_text or "AllocationQuota" in body_text
    if status_code == 429:
        # ZhipuAI / DeepSeek may use 429 for quota/rate. Treat as fallback trigger.
        return True
    if status_code == 402 and (
        "Insufficient Balance" in body_text
        or "FREE_QUOTA_EXHAUSTED" in body_text
    ):
        # DeepSeek-official balance-0 returns 402 with body "Insufficient
        # Balance". Tencent TokenHub returns 402 with body "endpoint is
        # inactive: FREE_QUOTA_EXHAUSTED" once its 90-day free trial is
        # consumed (probe 2026-06-30: deepseek-v4-pro/flash, glm-5.1,
        # qwen3.5-flash all 402). Both are structurally identical to other
        # quota exhaustion ($0, free trial stopped) — classify as quota so the
        # caller (a) marks the (account,model) quota-skip cache for QUOTA_SKIP_TTL
        # instead of re-probing every request, and (b) logs WARNING not ERROR.
        return True
    return False


def _normalize_payload_for_provider(payload: Dict[str, Any], account: str) -> Dict[str, Any]:
    """Adjust payload per provider's accepted schema.

    Currently a passthrough — all 4 chain providers (aliyun_b / aliyun_a /
    zhipu / aliyun_a_deepseek) reach DashScope or Zhipu compatible-mode
    endpoints, which handle thinking semantics natively. The earlier
    DeepSeek-official `thinking.type=disabled` injection was removed when
    deepseek-official was dropped from the chain (#580 Option 2).
    """
    return {**payload}


async def call_chain(
    slot: SLOT,
    payload: Dict[str, Any],
    chain: Optional[List[str]] = None,
    timeout: float = 30.0,
) -> Dict[str, Any]:
    """
    Call LLM via provider chain with automatic fallback on 403 FreeTierOnly / 429.

    Per-call timeout: 30s default (Apr 28 2026 optimization, was 120s).
    Worst-case full 4-provider cascade = 120s. qwen-plus typical 15-30s, so
    30s is comfortable margin while failing fast on overloaded providers.

    The payload's `model` field is OVERWRITTEN per-provider based on SLOT_MODELS.
    Other fields (messages, temperature, max_tokens, etc.) are preserved.

    Returns parsed JSON response from the first successful provider.
    Raises RuntimeError if all providers exhaust.
    """
    slot_chain = SLOT_MODELS.get(slot, [])
    if chain is not None:
        # Optional account-filter override (legacy callers pass account names).
        slot_chain = [(ac, m) for (ac, m) in slot_chain if ac in chain]
    slot_chain = _expiry_aware_sort(slot_chain)  # 过期感知: B 先(07/16前) → 自动切 C 头
    client = get_llm_http_client()
    errors: List[str] = []

    for account, model in slot_chain:
        if not model:
            continue
        # PAID-model denylist guard (2026-06-11): refuse to bill — skip to next.
        if model in _PAID_MODEL_DENYLIST:
            logger.error(
                f"[llm_router] slot={slot.value} refusing PAID model {model} "
                f"(account={account}) — skipping to protect billing"
            )
            errors.append(f"{account}/{model}: paid_denylist")
            continue
        # Per-account free-only guard (2026-06-11): aliyun_a (90bc) only has free
        # quota on a few SKUs; refuse any other model on it (would bill silently).
        _acct_allow = _ACCOUNT_FREE_ONLY.get(account)
        if _acct_allow is not None and model not in _acct_allow:
            logger.error(
                f"[llm_router] slot={slot.value} refusing {model} on {account} "
                f"(no free quota on this account) — skipping to protect billing"
            )
            errors.append(f"{account}/{model}: account_free_only")
            continue
        # Expiry date-gate (Fable #4): after B's 07/16 / C's 08/13 bulk expiry, all but
        # the qwen3.7-* survivors become "- -" (paid). Refuse them (would bill).
        if _is_expired_paid(account, model):
            logger.error(
                f"[llm_router] slot={slot.value} refusing {model} on {account} "
                f"(free quota expired for this SKU) — skipping to protect billing"
            )
            errors.append(f"{account}/{model}: expired_paid")
            continue
        cb_key = f"{account}/{model}"  # circuit-breaker per (account,model): one
        # model's free-quota 403 must NOT skip other free models on same account

        # Circuit breaker — skip this (account,model) if in cooldown after CB_THRESHOLD fails
        if _cb_should_skip(cb_key):
            logger.info(
                f"[llm_router] slot={slot.value} skipping {account}/{model} "
                f"(circuit breaker open, cooldown {CB_COOLDOWN}s)"
            )
            errors.append(f"{account}: cb_open")
            continue

        # Quota-exhaustion skip — a model known free-quota-exhausted is skipped
        # for QUOTA_SKIP_TTL instead of re-probed (and re-403'd) every request.
        if _quota_should_skip(cb_key):
            logger.info(
                f"[llm_router] slot={slot.value} skipping {account}/{model} "
                f"(quota-exhausted, re-probe after TTL)"
            )
            errors.append(f"{account}/{model}: quota_skip")
            continue

        base_url, api_key = _provider_config(account)
        if not api_key:
            logger.debug(f"[llm_router] {account}: no API key, skip")
            continue

        req_payload = _normalize_payload_for_provider({**payload, "model": model}, account)
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

        try:
            logger.debug(f"[llm_router] slot={slot.value} try {account}/{model}")
            # Apr 28 2026 (post-review P1, then reviewer round 2 correction):
            # API consistency only — bare `timeout=timeout` and
            # `timeout=httpx.Timeout(timeout)` are EQUIVALENT in httpx (a bare
            # float is shorthand that sets connect=read=write=pool=value, all
            # independent budgets). Phase timeouts are NOT summed. The earlier
            # commit message claim about "TOTAL timeout / 7.5s per phase" was
            # wrong. Keeping the explicit form matches `call_chain_stream`
            # below for readability — no behavior change.
            resp = await client.post(
                f"{base_url}/chat/completions",
                headers=headers,
                json=req_payload,
                timeout=httpx.Timeout(timeout),
            )
            body_text = resp.text  # may trigger aread() internally

            if 200 <= resp.status_code < 300:
                _cb_record_success(cb_key)
                _quota_record_success(cb_key)
                body_json = resp.json()
                _log_cache_and_record_budget(slot.value, account, model, body_json)
                logger.info(f"[llm_router] slot={slot.value} OK via {account}/{model}")
                return body_json

            if _is_quota_exhausted(resp.status_code, body_text):
                _cb_record_failure(cb_key)
                _quota_record_exhausted(cb_key)
                fails = _CB_FAILURES.get(account, 0)
                logger.warning(
                    f"[llm_router] slot={slot.value} {account}/{model} "
                    f"quota exhausted (status={resp.status_code}, "
                    f"cb_fails={fails}/{CB_THRESHOLD}), skip {QUOTA_SKIP_TTL/3600:.0f}h, falling back"
                )
                errors.append(f"{account}/{model}: quota {resp.status_code}")
                continue

            # Other errors: don't blindly fallback — log and raise
            _cb_record_failure(cb_key)
            fails = _CB_FAILURES.get(account, 0)
            logger.error(
                f"[llm_router] slot={slot.value} {account}/{model} "
                f"error status={resp.status_code} (cb_fails={fails}/{CB_THRESHOLD}): "
                f"{body_text[:200]}"
            )
            errors.append(f"{account}/{model}: http {resp.status_code}")
            # Non-quota errors still fall through to next provider since the
            # endpoint may transiently be broken. Net: we try all providers.
            continue

        except asyncio.TimeoutError:
            _cb_record_failure(cb_key)
            fails = _CB_FAILURES.get(account, 0)
            logger.warning(
                f"[llm_router] {account}/{model} timeout "
                f"(cb_fails={fails}/{CB_THRESHOLD})"
            )
            errors.append(f"{account}/{model}: timeout")
            continue
        except Exception as e:
            _cb_record_failure(cb_key)
            fails = _CB_FAILURES.get(account, 0)
            logger.warning(
                f"[llm_router] {account}/{model} exception "
                f"(cb_fails={fails}/{CB_THRESHOLD}): {e}"
            )
            errors.append(f"{account}/{model}: {type(e).__name__}")
            continue

    raise RuntimeError(f"[llm_router] All providers exhausted for {slot.value}: {'; '.join(errors)}")


# ---------------------------------------------------------------------------
# Streaming variant (Apr 25 2026, E2a)
# ---------------------------------------------------------------------------

async def call_chain_stream(
    slot: SLOT,
    payload: Dict[str, Any],
    chain: Optional[List[str]] = None,
    timeout: float = 45.0,
) -> AsyncIterator[Dict[str, Any]]:
    """Streaming variant of call_chain — yields token deltas with provider fallback.

    Per-call timeout: 45s default (Apr 28 2026 optimization, was 180s).
    Streaming completion can take longer than non-streaming (token-by-token),
    so cap is higher than call_chain. Worst-case 4-provider chain = 180s.
    Mid-stream timeouts after first delta still propagate (no retry by design).

    The payload's `model` field is OVERWRITTEN per-provider based on SLOT_MODELS.
    `stream=True` is forced. Other fields preserved.

    Yielded events:
      - {"type": "delta", "text": "..."}  — many events, one per content chunk
      - {"type": "usage", "tokens": N}    — at most one event, when upstream
                                            reports usage on the final [DONE]
                                            (requires stream_options.include_usage)

    Fallback semantics: provider-level fallback occurs ONLY before the first
    delta has been yielded. Once we've started streaming content from a
    provider, mid-stream errors are propagated as a final delta(text=err)
    + StopIteration to keep the SSE contract intact (caller has already
    sent partial output). This matches existing behavior of the surfaces
    being migrated.

    Pre-stream failures (HTTP error from response.raise_for_status, 403/429,
    timeout connecting, or connection error) trigger fallback to next provider.

    Raises RuntimeError if all providers exhaust BEFORE any delta is yielded.
    """
    slot_chain = SLOT_MODELS.get(slot, [])
    if chain is not None:
        slot_chain = [(ac, m) for (ac, m) in slot_chain if ac in chain]
    slot_chain = _expiry_aware_sort(slot_chain)  # 过期感知: B 先(07/16前) → 自动切 C 头
    client = get_llm_http_client()
    errors: List[str] = []
    payload = {**payload, "stream": True}

    # When stream_options is missing the upstream may not report usage.
    # Add include_usage opportunistically — most upstreams ignore unknown
    # flags. AgentOrchestrator already sets this; chat path does not need it.
    payload.setdefault("stream_options", {"include_usage": True})

    for account, model in slot_chain:
        if not model:
            continue
        # PAID-model denylist guard (2026-06-11): refuse to bill — skip to next.
        if model in _PAID_MODEL_DENYLIST:
            logger.error(
                f"[llm_router_stream] slot={slot.value} refusing PAID model {model} "
                f"(account={account}) — skipping to protect billing"
            )
            errors.append(f"{account}/{model}: paid_denylist")
            continue
        # Per-account free-only guard (2026-06-11): aliyun_a (90bc) only has free
        # quota on a few SKUs; refuse any other model on it (would bill silently).
        _acct_allow = _ACCOUNT_FREE_ONLY.get(account)
        if _acct_allow is not None and model not in _acct_allow:
            logger.error(
                f"[llm_router] slot={slot.value} refusing {model} on {account} "
                f"(no free quota on this account) — skipping to protect billing"
            )
            errors.append(f"{account}/{model}: account_free_only")
            continue
        cb_key = f"{account}/{model}"  # CB per (account,model), see call_chain

        # Circuit breaker — skip this (account,model) if in cooldown after CB_THRESHOLD fails
        if _cb_should_skip(cb_key):
            logger.info(
                f"[llm_router_stream] slot={slot.value} skipping {account}/{model} "
                f"(circuit breaker open, cooldown {CB_COOLDOWN}s)"
            )
            errors.append(f"{account}: cb_open")
            continue

        # Quota-exhaustion skip — see call_chain. Skip a known-exhausted model
        # for QUOTA_SKIP_TTL instead of re-probing it every request.
        if _quota_should_skip(cb_key):
            logger.info(
                f"[llm_router_stream] slot={slot.value} skipping {account}/{model} "
                f"(quota-exhausted, re-probe after TTL)"
            )
            errors.append(f"{account}/{model}: quota_skip")
            continue

        base_url, api_key = _provider_config(account)
        if not api_key:
            logger.debug(f"[llm_router_stream] {account}: no API key, skip")
            continue

        req_payload = _normalize_payload_for_provider({**payload, "model": model}, account)
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

        first_delta_yielded = False
        try:
            logger.debug(f"[llm_router_stream] slot={slot.value} try {account}/{model}")
            async with client.stream(
                "POST",
                f"{base_url}/chat/completions",
                headers=headers,
                json=req_payload,
                timeout=httpx.Timeout(timeout),
            ) as resp:
                # Pre-stream error branch — fallback before content yielded
                if resp.status_code >= 400:
                    body_text = (await resp.aread()).decode("utf-8", errors="replace")
                    _cb_record_failure(cb_key)
                    fails = _CB_FAILURES.get(account, 0)
                    if _is_quota_exhausted(resp.status_code, body_text):
                        _quota_record_exhausted(cb_key)
                        logger.warning(
                            f"[llm_router_stream] slot={slot.value} {account}/{model} "
                            f"quota exhausted (status={resp.status_code}, "
                            f"cb_fails={fails}/{CB_THRESHOLD}), skip {QUOTA_SKIP_TTL/3600:.0f}h, falling back"
                        )
                        errors.append(f"{account}/{model}: quota {resp.status_code}")
                        continue
                    logger.error(
                        f"[llm_router_stream] slot={slot.value} {account}/{model} "
                        f"error status={resp.status_code} (cb_fails={fails}/{CB_THRESHOLD}): "
                        f"{body_text[:200]}"
                    )
                    errors.append(f"{account}/{model}: http {resp.status_code}")
                    continue

                logger.info(f"[llm_router_stream] slot={slot.value} streaming via {account}/{model}")

                async for line in resp.aiter_lines():
                    line = line.strip()
                    if not line:
                        continue
                    # OpenAI SSE format: "data: {...}" or "data:{...}"
                    if line.startswith("data:"):
                        data_str = line[5:].strip()
                    elif line.startswith("data: "):
                        data_str = line[6:]
                    else:
                        continue

                    if data_str == "[DONE]":
                        break
                    try:
                        obj = json.loads(data_str)
                    except json.JSONDecodeError:
                        continue

                    choices = obj.get("choices") or []
                    if choices:
                        delta = choices[0].get("delta") or {}
                        content = delta.get("content")
                        if content:
                            first_delta_yielded = True
                            yield {"type": "delta", "text": content}

                    usage = obj.get("usage")
                    if usage:
                        total = int(usage.get("total_tokens") or 0)
                        # Apr 27 2026 (F8 audit): log cache hit on streaming
                        # path so prod cache behavior is observable.
                        # DeepSeek emits prompt_tokens_details.cached_tokens
                        # AND prompt_cache_hit_tokens; DashScope emits
                        # prompt_tokens_details.cached_tokens. Read both.
                        prompt_total = int(usage.get("prompt_tokens") or 0)
                        details = usage.get("prompt_tokens_details") or {}
                        cached = int(
                            details.get("cached_tokens")
                            or usage.get("prompt_cache_hit_tokens")
                            or 0
                        )
                        completion = int(usage.get("completion_tokens") or 0)
                        if prompt_total > 0:
                            pct = 100 * cached // prompt_total if cached else 0
                            logger.info(
                                f"[cache] slot={slot.value} via {account}/{model}: "
                                f"prompt={prompt_total} cached={cached} ({pct}%) "
                                f"completion={completion}"
                            )
                        # 真实记账流式 usage (钩子抓不到, 否则记成 0-token)
                        try:
                            from common.llm_metrics import record_stream_usage
                            record_stream_usage(
                                account, model, prompt_total, completion,
                                total or (prompt_total + completion),
                            )
                        except Exception as _e:
                            logger.debug(f"[llm_router_stream] usage record skipped: {_e}")
                        if total:
                            yield {"type": "usage", "tokens": total}
                # Successful stream — record CB success and return
                _cb_record_success(cb_key)
                _quota_record_success(cb_key)
                return

        except (asyncio.TimeoutError, httpx.TimeoutException):
            if first_delta_yielded:
                # Mid-stream errors don't trip CB (we got partial value from this provider)
                logger.warning(
                    f"[llm_router_stream] {account}/{model} mid-stream timeout — "
                    "propagating partial result (no fallback)"
                )
                return
            _cb_record_failure(cb_key)
            fails = _CB_FAILURES.get(account, 0)
            logger.warning(
                f"[llm_router_stream] {account}/{model} pre-stream timeout, "
                f"falling back (cb_fails={fails}/{CB_THRESHOLD})"
            )
            errors.append(f"{account}/{model}: timeout")
            continue
        except Exception as e:
            if first_delta_yielded:
                # Mid-stream errors don't trip CB (we got partial value from this provider)
                logger.warning(
                    f"[llm_router_stream] {account}/{model} mid-stream exception {type(e).__name__}: {e} — "
                    "propagating partial result (no fallback)"
                )
                return
            _cb_record_failure(cb_key)
            fails = _CB_FAILURES.get(account, 0)
            logger.warning(
                f"[llm_router_stream] {account}/{model} pre-stream exception "
                f"(cb_fails={fails}/{CB_THRESHOLD}): {e}"
            )
            errors.append(f"{account}/{model}: {type(e).__name__}")
            continue

    raise RuntimeError(
        f"[llm_router_stream] All providers exhausted for {slot.value}: {'; '.join(errors)}"
    )
