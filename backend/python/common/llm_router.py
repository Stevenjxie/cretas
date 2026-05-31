"""
Multi-provider LLM router with 403 AllocationQuota.FreeTierOnly fallback.

Chain (priority order): aliyun_b → aliyun_a → zhipu → aliyun_a_deepseek

No client-side quota estimation — we rely on Aliyun's 免费额度用完即停 toggle
which returns 403 when free quota exhausts. This is the HARD guarantee that
accounts will never be charged beyond free tier.

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
import logging
import os
import time
from enum import Enum
from threading import Lock
from typing import Any, AsyncIterator, Dict, List, Optional, Tuple

import httpx

from common.llm_client import get_llm_http_client

logger = logging.getLogger(__name__)


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
SLOT_MODELS: Dict[SLOT, Dict[str, Optional[str]]] = {
    SLOT.CHAT: {
        "aliyun_c":          "qwen-plus",                 # May 31 2026 (Steve re-confirm c allowlist update): qwen-flash FREE-TIER EXHAUSTED on C (403). qwen-plus free-pool intact on C, faster than qwen3-max for interactive CHAT. c is chain head → consumes first.  # noqa: E501
        "aliyun_b":          "qwen-flash",                # May 31 2026 swap: qwen-max FREE-TIER EXHAUSTED on B (403 AllocationQuota.FreeTierOnly). qwen-flash free-pool intact on B + is CHAT slot benchmark winner (1.4s concise). c's qwen-flash also exhausted → b is effective head.  # noqa: E501
        "aliyun_a":          "qwen3-max",                 # May 31 2026 swap: qwen3.6-max-preview EXHAUSTED on A (403). qwen3-max (旗舰) free-pool intact on ALL 3 accounts (independent of the 3.6-preview pool) → flagship-quality fallback.  # noqa: E501
        "zhipu":             "glm-4.5-air",               # 6.5M independent pool (NOT in 通用池 which is 0)
        "aliyun_a_deepseek": "deepseek-v4-pro",           # DashScope-hosted, free 999K on aliyun_a key
    },
    SLOT.INSIGHTS: {
        "aliyun_c":          "qwen3-max",                 # May 31 2026 (Steve re-confirm c allowlist): qwen-flash EXHAUSTED on C (403). qwen3-max free on C — INSIGHTS is offline materialization (Fix2 rich attribution) → flagship quality, c head consumes first.  # noqa: E501
        "aliyun_b":          "qwen3-max",                 # May 31 2026 swap: qwen3.6-35b-a3b EXHAUSTED on B (403). qwen3-max (旗舰) free on ALL 3 accts; INSIGHTS is offline materialization (Fix2 rich-attribution insights) → flagship quality > speed.  # noqa: E501
        "aliyun_a":          "qwen3-max",                 # May 31 2026 swap: qwen3.6-35b-a3b EXHAUSTED on A (403). qwen3-max free-pool intact on A.  # noqa: E501
        "zhipu":             "glm-4.5-air",               # 6.5M independent pool
        "aliyun_a_deepseek": "deepseek-v4-pro",
    },
    SLOT.CHART: {
        "aliyun_c":          "qwen3-max",                 # May 31 2026 (Steve re-confirm c allowlist): qwen-turbo EXHAUSTED on C (403). qwen3-max free on C, reliable structured JSON (CHART not interactive). On any malformed JSON, b glm-5 catches.  # noqa: E501
        "aliyun_b":          "glm-5",                     # 875K intact B (May 31 probe: still OK — chain catches here)
        "aliyun_a":          "qwen-turbo",                # May 31 2026 swap: glm-5 EXHAUSTED on A (403). qwen-turbo free on A + is CHART benchmark winner (valid compact JSON). Redundancy only — b glm-5 catches first.  # noqa: E501
        "zhipu":             "glm-4.5-air",
        "aliyun_a_deepseek": "deepseek-v4-pro",
    },
    SLOT.MAPPER: {
        "aliyun_c":          "qwen3-235b-a22b",           # May 31 2026 (Steve re-confirm c allowlist): qwen-turbo EXHAUSTED on C (403). qwen3-235b-a22b (big MoE) free on C — strong enough for correct mapping direction. b qwen3.5-122b-a10b catches on any issue.  # noqa: E501
        "aliyun_b":          "qwen3.5-122b-a10b",         # 998K intact (May 31 probe: still OK — chain catches here)
        "aliyun_a":          "qwen-turbo",                # May 31 2026 swap: qwen3.5-122b-a10b EXHAUSTED on A (403). qwen-turbo free on A + is MAPPER benchmark winner (correct mapping direction). Redundancy — b catches first.  # noqa: E501
        "zhipu":             "glm-4.5-air",
        "aliyun_a_deepseek": "deepseek-v4-pro",
    },
    SLOT.REASONING: {
        "aliyun_c":          "deepseek-v3",               # May 31 2026 (Steve re-confirm c allowlist): deepseek-v4-pro EXHAUSTED on C (403). deepseek-v3 free on C — deepseek-class depth, non-interactive REASONING. b qwen3.5-397b-a17b catches on any issue.  # noqa: E501
        "aliyun_b":          "qwen3.5-397b-a17b",         # 974K intact (May 31 probe: still OK — chain catches here)
        "aliyun_a":          "qwen3-235b-a22b",           # May 31 2026 swap: qwen3.5-397b-a17b EXHAUSTED on A (403). qwen3-235b-a22b (big MoE) free on A — strong reasoning. Redundancy — b catches first.  # noqa: E501
        "zhipu":             "glm-4.5-air",
        "aliyun_a_deepseek": "deepseek-v4-pro",
    },
    SLOT.VL: {
        "aliyun_c":          "qwen3-vl-plus-2025-12-19",  # ✅ Only allowlist-approved VL option on aliyun_c (2025-05-07 SKU is 404 NOSKU on new accounts — deprecated).  # noqa: E501
        "aliyun_b":          "qwen3-vl-plus-2025-12-19",  # 1M intact (May 31 probe: still OK)
        "aliyun_a":          "qwen-vl-max",               # May 31 2026 swap: qwen3-vl-plus-2025-12-19 EXHAUSTED on A (403). qwen-vl-max free on A — strong VL, same DashScope image_url format. Redundancy — c/b catch first.  # noqa: E501
        "zhipu":             "glm-4.6v",                  # ⚠️ payload format incompatible with image_url (zhipu needs different shape); 6M independent pool exists but call site must adapt  # noqa: E501
        "aliyun_a_deepseek": None,                        # DashScope has no DeepSeek VL — skip cleanly
    },
    SLOT.REVIEW: {
        "aliyun_c":          "qwen3-max-2026-01-23",      # ✅ May 14 benchmark winner: 9.3s, concise + complete.
        "aliyun_b":          "qwen3-max",                     # May 31 2026 swap: qwen-max EXHAUSTED on B (403). qwen3-max free on B — strong critique. Redundancy — c qwen3-max-2026-01-23 catches first.  # noqa: E501
        "aliyun_a":          "qwen3-max",                     # May 31 2026 swap: qwen3.5-397b-a17b EXHAUSTED on A (403). qwen3-max free on A. Redundancy — c catches first.  # noqa: E501
        "zhipu":             "glm-4.5-air",
        "aliyun_a_deepseek": None,                            # Skip new chain entry cleanly for REVIEW
    },
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
        "zhipu": (
            os.getenv("LLM_ZHIPU_BASE_URL", "https://open.bigmodel.cn/api/paas/v4"),
            os.getenv("LLM_ZHIPU_API_KEY", ""),
        ),
    }
    return mapping.get(account, ("", ""))


# Chain order — 5 providers, all free tier:
#   aliyun_c → aliyun_b → aliyun_a → zhipu → aliyun_a_deepseek
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
#   - May 14 2026 (this commit): added aliyun_c at chain HEAD — 3rd Aliyun
#     bailian account, brand-new free quota. Probe 12/13 candidate SKUs 200
#     OK (qwen3-vl-plus-2025-05-07 404 NOSKU on new accounts, deprecated;
#     replaced with the 2025-12-19 SKU used by aliyun_a/b).
#
# Re-audit recommended ~every 2 weeks or whenever "All providers exhausted"
# log line reappears (per `tests/qa-llm-quota/audit-matrix.md` cadence note).
DEFAULT_CHAIN: List[str] = ["aliyun_c", "aliyun_b", "aliyun_a", "zhipu", "aliyun_a_deepseek"]


def _is_quota_exhausted(status_code: int, body_text: str) -> bool:
    """Detect 免费额度用完即停 / rate-limit / quota-exceeded from response."""
    if status_code == 403:
        return "FreeTierOnly" in body_text or "AllocationQuota" in body_text
    if status_code == 429:
        # ZhipuAI / DeepSeek may use 429 for quota/rate. Treat as fallback trigger.
        return True
    if status_code == 402 and "Insufficient Balance" in body_text:
        # DeepSeek-official balance-0 returns 402 with body "Insufficient
        # Balance". Structurally identical to other quota exhaustion — fall
        # through with WARNING instead of generic ERROR (issue #581).
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
    chain = chain or DEFAULT_CHAIN
    client = get_llm_http_client()
    errors: List[str] = []

    for account in chain:
        model = SLOT_MODELS.get(slot, {}).get(account)
        if not model:
            continue

        # Circuit breaker — skip provider if in cooldown after CB_THRESHOLD fails
        if _cb_should_skip(account):
            logger.info(
                f"[llm_router] slot={slot.value} skipping {account} "
                f"(circuit breaker open, cooldown {CB_COOLDOWN}s)"
            )
            errors.append(f"{account}: cb_open")
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
                _cb_record_success(account)
                body_json = resp.json()
                _log_cache_and_record_budget(slot.value, account, model, body_json)
                logger.info(f"[llm_router] slot={slot.value} OK via {account}/{model}")
                return body_json

            if _is_quota_exhausted(resp.status_code, body_text):
                _cb_record_failure(account)
                fails = _CB_FAILURES.get(account, 0)
                logger.warning(
                    f"[llm_router] slot={slot.value} {account}/{model} "
                    f"quota exhausted (status={resp.status_code}, "
                    f"cb_fails={fails}/{CB_THRESHOLD}), falling back"
                )
                errors.append(f"{account}/{model}: quota {resp.status_code}")
                continue

            # Other errors: don't blindly fallback — log and raise
            _cb_record_failure(account)
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
            _cb_record_failure(account)
            fails = _CB_FAILURES.get(account, 0)
            logger.warning(
                f"[llm_router] {account}/{model} timeout "
                f"(cb_fails={fails}/{CB_THRESHOLD})"
            )
            errors.append(f"{account}/{model}: timeout")
            continue
        except Exception as e:
            _cb_record_failure(account)
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
    chain = chain or DEFAULT_CHAIN
    client = get_llm_http_client()
    errors: List[str] = []
    payload = {**payload, "stream": True}

    # When stream_options is missing the upstream may not report usage.
    # Add include_usage opportunistically — most upstreams ignore unknown
    # flags. AgentOrchestrator already sets this; chat path does not need it.
    payload.setdefault("stream_options", {"include_usage": True})

    for account in chain:
        model = SLOT_MODELS.get(slot, {}).get(account)
        if not model:
            continue

        # Circuit breaker — skip provider if in cooldown after CB_THRESHOLD fails
        if _cb_should_skip(account):
            logger.info(
                f"[llm_router_stream] slot={slot.value} skipping {account} "
                f"(circuit breaker open, cooldown {CB_COOLDOWN}s)"
            )
            errors.append(f"{account}: cb_open")
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
                    _cb_record_failure(account)
                    fails = _CB_FAILURES.get(account, 0)
                    if _is_quota_exhausted(resp.status_code, body_text):
                        logger.warning(
                            f"[llm_router_stream] slot={slot.value} {account}/{model} "
                            f"quota exhausted (status={resp.status_code}, "
                            f"cb_fails={fails}/{CB_THRESHOLD}), falling back"
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
                _cb_record_success(account)
                return

        except (asyncio.TimeoutError, httpx.TimeoutException):
            if first_delta_yielded:
                # Mid-stream errors don't trip CB (we got partial value from this provider)
                logger.warning(
                    f"[llm_router_stream] {account}/{model} mid-stream timeout — "
                    "propagating partial result (no fallback)"
                )
                return
            _cb_record_failure(account)
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
            _cb_record_failure(account)
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
