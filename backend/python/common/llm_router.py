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
import tempfile
import time
from enum import Enum
from threading import Lock
from typing import Any, AsyncIterator, Callable, Dict, List, Optional, Tuple

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

# ═══════════════════════════════════════════════════════════════════════════
# Billing-safe model registry (per-(account, model)) — 2026-07-01 rebuild.
# Console scrape 2026-06-30/07-01 (aliyun a/b/c), live-probe verified (~40 pairs,
# 0 mismatch, account identity confirmed). 6-agent audit hardened. Spec:
# docs/superpowers/specs/2026-07-01-smart-llm-router-spec.md
#
# ⛔ SAFETY INVARIANT (billing red-line): a (account, model) is in _SAFE_MODELS
# IFF its `免费额度用完即停` toggle is 已开启(ON) on THAT account. ON ⇒ upstream
# returns 403 FreeTierOnly on exhaust/expiry, NEVER a paid 200. The router REFUSES
# any (account, model) not in this dict. This is the ONLY billing guard that matters
# (the router cannot detect a paid 200 in the response).
#
# ⛔ ON-toggle = SAFETY; expiry date = ORDERING/availability ONLY (orthogonal — never
# gate safety on the date, never trust the date for billing).
#
# ⛔ PER-(account, model): the SAME model has DIFFERENT toggle state per account —
# deepseek-v4-pro is ON on aliyun_c but 不支持开启(PAID) on aliyun_b. A global
# model allow/deny is WRONG.
#
# 2026-08-09: kimi-k2.7-code / qwen3.5-ocr were previously logged here as
# "未开启(BILLS) on b" (per a 2026-06-30 scrape) and excluded from aliyun_b. The
# 08-09 audit found the opposite: aliyun_b's 「免费额度用完即停」toggle is ON
# for both. Evidence: aliyun_b's own console screenshot that day lists both with
# ~1,000,000 remaining free quota, expiring 09-14; and aliyun_b returned 403
# AllocationQuota.FreeTierOnly for several OTHER models that same day, which is
# only possible when that account's 用完即停 setting is already ON — so a 200
# from aliyun_b is free-quota-served, not billed. Both are now registered below.
#
# Value = free-grant expiry date (own date from console; account bulk-expiry for
# exhausted-ON models that showed no date; None for tencent/zhipu which have no
# DashScope expiry — they are billing-safe via their own 用完即停/pool cap).
# ═══════════════════════════════════════════════════════════════════════════
_REGISTRY_AUDIT_DATE = datetime.date(2026, 8, 9)   # 三控制台截图 ∩ 生产探针全量核对
# 2026-08-09 三账号控制台截图 ∩ 生产探针(经 _apply_slot_params, 判据为非空
# content)全量核对。判据: 控制台显示有余量 ∩ 探针通过 —— 单边证据一律不收
# (探针 200 但控制台无余量的最危险: 可能是「用完即停」没覆盖它、其实在计费,
# glm-5.2 即因此三账号全删)。
#
# [SUPERSEDED — 2026-07-26 记录, 已被上面 08-09 的判据取代, 仅存档]
# (2026-07-26 用户逐账户控制台截图): 所拍 A/B/C 模型均已开启
# “免费额度用完即停”。这里只收录经任务能力筛选后的通用文本模型，不把
# OCR/VL/Math/Character/Code 专用 SKU 混入通用链。B/C 两账户的
# qwen3.7-flash-2026-07-15 与 qwen3.7-flash 均剩 1,000,000/1,000,000，
# 到期 2026-10-23；它们成为快速文本槽的主链和跨账户故障转移。
# 2026-08-09 重审推翻: 这两个 (aliyun_b, aliyun_c) 版本当天探针 403, 已从
# _SAFE_MODELS 整体移除, 只有 aliyun_a 的同名条目存活(见下方注册表 ~08-09
# 段落) —— 见 test_flash_quota_pair_retired_from_b_and_c_survives_only_on_a。
_REGISTRY_MAX_AGE_DAYS = 21  # staleness fail-safe: WARN + fall to minimal set beyond this
_FAR_FUTURE = datetime.date(2099, 1, 1)  # tencent/zhipu + missing dates sort last among safe

# Account bulk free-quota expiry (fallback sort-key for exhausted-ON DashScope models
# whose console row showed no own date). Beijing-time; server is cn-shanghai (CST) so
# datetime.date.today() aligns with DashScope's reset boundary.
_BULK_EXPIRY: Dict[str, datetime.date] = {
    "aliyun_a": datetime.date(2026, 7, 16),
    "aliyun_b": datetime.date(2026, 7, 16),
    "aliyun_c": datetime.date(2026, 8, 13),
}

_d = datetime.date
_SAFE_MODELS: Dict[Tuple[str, str], Optional[datetime.date]] = {
    # ══ 2026-08-09 全量重审 ══════════════════════════════════════════════
    # 判据: 控制台显示有余量 ∩ 探针(经 _apply_slot_params, 判据为非空 content)通过。
    # 单边证据一律不收 —— 探针 200 但控制台无余量的最危险: 那说明「免费额度
    # 用完即停」没覆盖它, 那个 200 可能是真在计费(glm-5.2 即因此三账号全删)。
    # 顺序无意义, 链顺序由 _build_chain 按到期日算; 这里只是事实表。

    # ── aliyun_a (控制台 8 个有额度, 全部通过探针) ──
    ("aliyun_a", "qwen3.8-max"): _d(2026, 11, 1),
    ("aliyun_a", "deepseek-v4-flash-0731"): _d(2026, 10, 31),
    ("aliyun_a", "qwen3.7-flash"): _d(2026, 10, 23),
    ("aliyun_a", "qwen3.7-flash-2026-07-15"): _d(2026, 10, 23),
    ("aliyun_a", "qwen3.5-ocr"): _d(2026, 9, 14),
    ("aliyun_a", "kimi-k2.7-code"): _d(2026, 9, 14),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): _d(2026, 8, 24),

    # ── aliyun_b (控制台 6 个有额度, 全部通过探针) ──
    ("aliyun_b", "qwen3.8-max"): _d(2026, 11, 1),
    ("aliyun_b", "deepseek-v4-flash-0731"): _d(2026, 10, 31),
    ("aliyun_b", "qwen3.5-ocr"): _d(2026, 9, 14),
    ("aliyun_b", "kimi-k2.7-code"): _d(2026, 9, 14),
    ("aliyun_b", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_b", "qwen3.7-max-preview"): _d(2026, 8, 24),

    # ── aliyun_c 长期 (> 08-13) ──
    ("aliyun_c", "qwen3.8-max"): _d(2026, 11, 1),
    ("aliyun_c", "kimi-k2.7-code"): _d(2026, 9, 14),
    ("aliyun_c", "qwen3.5-ocr"): _d(2026, 9, 14),
    ("aliyun_c", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-preview"): _d(2026, 8, 24),
    # ("aliyun_c", "qwen3.7-max") 移除 08-10: 见下方 08-10 探针剔除段落。
    ("aliyun_c", "qwen3.7-max-2026-05-20"): _d(2026, 8, 20),

    # ── aliyun_c 08-13 到期 (优先榨干; _build_chain 会把它们排在最前) ──
    ("aliyun_c", "qwen3-next-80b-a3b-instruct"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2-exp"): _d(2026, 8, 13),
    ("aliyun_c", "glm-4.6"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3.6-plus-2026-04-02"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3.5-plus-2026-02-15"): _d(2026, 8, 13),
    # ("aliyun_c", "qwen3-max-2025-09-23") 移除 08-10: 见下方段落。
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"): _d(2026, 8, 13),
    # ("aliyun_c", "qwen3-vl-32b-instruct") 移除 08-10: 见下方段落。
    ("aliyun_c", "kimi-k2-thinking"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-r1"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-235b-a22b-thinking-2507"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-r1-0528"): _d(2026, 8, 13),
    ("aliyun_c", "MiniMax-M2.5"): _d(2026, 8, 13),

    # ── 地板 (无到期日 → _expiry_of 返回 _FAR_FUTURE → 必然排最后) ──
    # tencent 9 个条目实测只剩这 1 个: 7 个 401008 FREE_QUOTA_EXHAUSTED,
    # kimi-k2.6 走参数层仍返回空内容。ark 两个全部 SetLimitExceeded → 条目
    # 清空, 但 _provider_config 里的 ark 配置与代码路径保留, 待 owner 提供
    # 完整可用清单后按判据加回(改数据即可, 不改代码)。
    # zhipu/glm-4.6v 已因 429 余额不足死亡, 从 VL 地板剔除(见 Task 5 VL 豁免)。
    ("tencent", "minimax-m2.7"): None,
    ("zhipu", "glm-4.5-air"): None,
}

# ── 2026-08-10 探针复审剔除 ──────────────────────────────────────────────
# 首次对新注册表跑生产探针(2026-08-10, 生产凭证), 发现三条 08-09 当天还
# 实测 OK 的条目 24 小时内变成 403 quota:
#   aliyun_c/qwen3-max-2025-09-23    08-09 OK 1.6s → 08-10 403 quota
#   aliyun_c/qwen3-vl-32b-instruct   08-09 OK 1.0s → 08-10 403 quota
#   aliyun_c/qwen3.7-max             08-09 OK 1.2s → 08-10 403 quota
# 移除只需探针证据(准入才需要控制台余量 ∩ 探针双证 —— 移除永远不会制造计费
# 风险, 只会让路由更保守), 三条已从 _SAFE_MODELS、每个引用它们的 _SLOT_POOLS、
# tests/test_llm_router_registry.py 的冻结表、golden 快照同步剔除。
#
# 连带后果(未回避, 刻意接受): SLOT.VL 只剩 aliyun_c/qwen3-vl-flash-2026-01-22
# 一条, 而它自己也在 08-13 到期 —— 届时 VL 变空链, call_chain 抛
# All providers exhausted for vl。这正是 §9.1 owner 拍板过的轨迹(明确报错
# 优于把图片请求静默丢给文本模型瞎猜), 不补新 VL 候选。

# Thinking-only models (cannot disable thinking → always reason → slow). Confined to
# REASONING slot; NEVER placed in fast slots (CHAT/MAPPER/CHART). Param layer also
# skips enable_thinking=false for these (no-op / unsupported).
_THINKING_ONLY: frozenset = frozenset({
    "deepseek-r1", "deepseek-r1-0528", "deepseek-r1-distill-qwen-32b",
    "qwen3-235b-a22b-thinking-2507", "qwq-plus", "kimi-k2.7-code", "kimi-k2-thinking",
    # 2026-07-26 live probes on A/B/C: both SKUs reject
    # enable_thinking=false with Algo.InvalidParameter. Keep them out of every
    # slot whose profile disables thinking; quota and protocol compatibility
    # are separate concerns.
    "qwen3.7-max-2026-05-17", "qwen3.7-max-preview",
})

# Minimal hard-coded known-safe fallback set if the registry goes stale (>21d).
# 2026-08-09 重建后是**以 aliyun_a 为主**的最长跑道集合(4 条 aliyun_a + 1 条
# aliyun_b + 2 条 aliyun_c)+ 两条非 DashScope 文本地板(tencent/zhipu 用完即停)。
# 上一版本注释说"aliyun_c 最长跑道"已经不真实 —— 08-09 重审后 aliyun_a 的
# qwen3.7-flash 系列(10/23 到期)比多数 aliyun_c 条目跑道更长, 集合按「跑道最长
# + 当天探针通过」逐条选, 不再是单一账号。
#
# ⛔ 集合里刻意**不含任何 VL 模型** —— VL 在 staleness 下会**完全变黑**, 这是
# 已知且记录在 plan 里的取舍(业务 7 天仅 1 次真实 VL 调用, 明确报错优于文本模型
# 瞎猜图片, 同 §9.1 对 VL 到期的处置一致), 不是遗漏。Fail SAFE, not open —— 但
# "safe" 不承诺"每个槽都有兜底", 只承诺"有兜底的槽兜底的是活的模型"。
_MINIMAL_SAFE_SET: frozenset = frozenset({
    # 2026-08-09 重建: 旧集合 13 个条目里 8 个已实测死亡 —— fail-safe 退守的
    # 目标本身是死的。只收「跑道最长 + 当天探针通过」的条目。
    ("aliyun_a", "qwen3.8-max"), ("aliyun_b", "qwen3.8-max"),
    ("aliyun_c", "qwen3.8-max"),                       # 11/01, 三账号各 100 万
    ("aliyun_a", "deepseek-v4-flash-0731"),            # 10/31
    ("aliyun_a", "qwen3.7-flash"),                     # 10/23 fast JSON/text
    ("aliyun_a", "qwen3.7-flash-2026-07-15"),          # 10/23
    ("aliyun_c", "kimi-k2.7-code"),                    # 09/14
    ("tencent", "minimax-m2.7"), ("zhipu", "glm-4.5-air"),   # 非 DashScope 文本地板
})


def _expiry_of(account: str, model: str) -> datetime.date:
    """Sort key: the (account, model)'s free-grant expiry; _FAR_FUTURE if unknown/None
    (tencent/zhipu). Used to order chains soonest-expiry-first WITHIN a quality tier."""
    exp = _SAFE_MODELS.get((account, model), _FAR_FUTURE)
    return exp if exp is not None else _FAR_FUTURE


def _today() -> datetime.date:
    """可注入时钟缝: 生产 = 真实今天; 测试 monkeypatch 此函数冻结日期,
    避免 call_chain 类测试随真实日期漂移碎裂 (2026-07-23 修)。"""
    return datetime.date.today()


def _registry_stale(today: Optional[datetime.date] = None) -> bool:
    """Staleness fail-safe: registry older than _REGISTRY_MAX_AGE_DAYS → caller WARNs
    and narrows to _MINIMAL_SAFE_SET (fail safe). Toggle states drift in console."""
    today = today or _today()
    return (today - _REGISTRY_AUDIT_DATE).days > _REGISTRY_MAX_AGE_DAYS


def _refuse_reason(account: str, model: str,
                   today: Optional[datetime.date] = None) -> Optional[str]:
    """SINGLE billing-safety gate — called from BOTH call_chain and call_chain_stream
    (streaming historically lacked the expiry gate — this unifies them). Returns a
    reason string to REFUSE (skip to next chain entry), or None to allow.

    Order: allowlist membership (the safety invariant) → paid-name denylist veto →
    call-time expiry hard-drop. The expiry drop MUST happen here, not just via sort:
    an ascending-expiry sort puts the most-expired model at the HEAD (tried first) —
    refusing at call time is the actual billing guard (billing-audit CRITICAL 1)."""
    today = today or _today()
    if _registry_stale(today) and (account, model) not in _MINIMAL_SAFE_SET:
        return "registry_stale"          # fail SAFE: only minimal set until re-audit
    if (account, model) not in _SAFE_MODELS:
        return "not_allowlisted"         # not a confirmed-ON model on this account
    if model in _PAID_MODEL_DENYLIST:
        return "paid_denylist"           # final veto on known-catastrophic bare names
    exp = _SAFE_MODELS.get((account, model))
    if exp is not None and today >= exp:
        return "expired"                 # free grant lapsed → may bill → hard-drop
    return None


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


def _cb_is_open_readonly(provider: str) -> bool:
    """`_cb_should_skip` 的**只读**版本：判断但不重置计数器。

    ⚠️ 为什么不能直接用 `_cb_should_skip`：它在冷却期满时会把失败计数清零，
    等于**消耗掉「再探一次」的额度**。可用性预检每次问答都要跑一遍，用带副作用的
    函数会让真正的调用失去那次重试机会 —— 预检本身不该改变系统行为。
    """
    with _CB_LOCK:
        fails = _CB_FAILURES.get(provider, 0)
        if fails < CB_THRESHOLD:
            return False
        return (time.time() - _CB_LAST_FAIL.get(provider, 0.0)) < CB_COOLDOWN


def slot_has_usable_provider(slot: "SLOT") -> bool:
    """该 slot 的降级链上还有没有一档现在能用。**只读，不改任何状态。**

    🔴 存在的理由（Steve 2026-08-07 拍板）：配额耗尽时要**整体封闭 AI 入口**，
    不许降级到非 LLM 能力。而确定性层（晋升表 / 缺口判定 / 域外拒答）压根不调
    LLM，它们会在链已经死透时照样产出答案 —— **部分可用比完全不可用更危险**，
    因为用户无法判断此刻这个答案的可信度。所以必须在**入口处**事前判定。

    ⛔ 判据只读现有状态（allowlist / 过期 / 熔断 / 有没有 key），不新增任何
    「猜它还行不行」的逻辑 —— 那会变成第二处可用性定义。
    """
    today = _today()
    configured = 0
    for account, model in SLOT_MODELS.get(slot, ()):
        _base_url, api_key = _provider_config(account)
        if not api_key:
            continue
        configured += 1
        if _refuse_reason(account, model, today) is not None:
            continue
        if _cb_is_open_readonly(f"{account}/{model}"):
            continue
        return True
    # ⛔ 一个 key 都没配 = **配置问题**, 不是运行期额度耗尽 —— 这道闸不判它。
    #    第一版把两者混为一谈: 测试环境本来就不配 key(LLM 在更上层被 mock),
    #    结果整套餐饮问答被封闭, **全仓新增 54 个失败**。
    #    判据: 这道闸的职责是「链路跑着跑着用完了」, 不是「有没有装好」。
    #    真的漏配 key 时, router 在被调用时照旧抛 exhausted, 那条路仍然安全。
    return configured == 0


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
_QUOTA_STRIKES: Dict[str, int] = {}             # "account/model" → 连续撞到次数
_QUOTA_LOCK = Lock()
QUOTA_SKIP_TTL = 6 * 3600.0        # 6h: re-probe ~4×/day to catch monthly free-quota reset
QUOTA_SKIP_TTL_MAX = 24 * 3600.0   # 退避上限 —— 每天仍至少试探一次, 接得住月度重置

# ─── Re-probe 节流 (2026-08-06 prod incident: chain total_timeout) ───────────
# 症状: 餐饮审计连续两轮 19/22, 报错是 `All providers exhausted ... chain:
# total_timeout` 而**不是**「没有候选」。实测当时 CHAT/CHART 链里 19 个候选有 5 个
# 是活的 —— 但第一个活的排在第 15 位。
#
# 根因: 一次失败请求会把沿途所有 quota-exhausted 的候选在**同一瞬间**打上标记,
# 于是它们的 TTL 也**同时到期**。下一个请求进来, `_quota_should_skip` 逐个「放行
# 一次 re-probe」, 一口气把 14 个死模型全部真探一遍 —— 每个都是真实 HTTP, 链的
# 总预算(12s)在够到第 15 位那个活模型之前就烧光了。然后它们又被重新标记 6h,
# 6h 后再来一遍 = 稳定复现的死循环。
#
# 修法: re-probe 是**全局限速**的, 不是每个 key 各自到期就放行。窗口到期后仍要
# 抢到这个全局槽位才能真探; 抢不到就顺延一小段, **strikes 不变**(退避语义不受
# 影响, 它衡量的是「这个模型连续几次确认没额度」, 与节流无关)。
#
# 为什么不改链的顺序: `SLOT_MODELS` 的顺序编码的是质量档次, 按「谁最近能用」重排
# 会把弱模型顶到链头。被 skip 的候选本来就是**零成本**跳过(命中即 continue, 不发
# HTTP), 所以死条目留在链头并不费预算 —— 真正费预算的只有这里的踩踏。
QUOTA_REPROBE_MIN_GAP = 30.0       # 全进程两次 re-probe 之间的最小间隔(秒)
_QUOTA_LAST_REPROBE_AT: float = 0.0

# 2026-08-01: 上面那套 6h TTL 只活在**单个进程的内存**里, 于是 prod 日志
# (07-25~08-01, 7 天) 显示:
#     真发请求撞到的 403 : 608 次 ≈ 87/天
#     记忆命中的跳过     : 8283 次 (0 成本 —— 缓存本身是有效的)
#     成功               : 5496 次
# 单个模型 aliyun_c/qwen3.7-max 被撞 70 次 = 10 次/天, 而 6h TTL 的设计预期是
# 4 次/天。多出来的约 60% 来自**每次部署重启清零** + **每个独立进程各撞一遍**
# (每日审计脚本 / eval / 一次性探针都从空白开始)。
#
# 代价不是钱(403 不计费), 是**延迟**: 重启后第一批请求要串行吃掉 7 个 403 往返,
# 而餐饮 T3 整条链的预算只有 _SEMANTIC_TOTAL_TIMEOUT_SECONDS = 12s —— 撞掉的
# 预算会让后面的好模型够不到, 表现为答案掉到更差的兜底档而不是"慢一点"。
#
# 两条改动:
#   1. 把这份记忆落到磁盘, 启动时载入 → 重启和跨进程不再重新发现;
#   2. 连续撞到就把窗口翻倍 (6h → 12h → 24h 封顶), 成功即归零。
#
# ⛔ 刻意**不做永久拉黑**: 免费额度是**月度重置**的, 上面 435-449 行的整段设计
# 就是为了接住那次重置。永久拉黑 = 额度回来了模型也永远不再启用, 而清除它的
# 唯一途径变成重启 —— 那正是我们刚刚在消除的东西。
#
# ⛔ 状态文件**不能**落在 backend/python 里: 部署用
# `rsync -az --delete-after`(deploy-smartbi-python.sh:304) 同步这棵树, 而
# exclude 列表里没有它 —— 每次部署都会把它删掉, 持久化白做。
# 也刻意**不**去 rsync 那边加一条 exclude: 那会让「文件放哪」由两处代码共同决定,
# 改了这里而忘了那里就静默失效。放到代码树之外, 单一真值。
# 落 tempdir: 跨进程共享 + 跨重启存活 + 不进任何同步/构建产物; 重启机器被清掉也
# 只是退回今天的行为(重新发现一次), 不会更糟。
_QUOTA_STATE_PATH = os.environ.get(
    "LLM_QUOTA_STATE_PATH",
    os.path.join(tempfile.gettempdir(), "cretas-llm-quota-state.json"),
)


def _quota_skip_seconds_for(cb_key: str) -> float:
    """当前这一次该跳过多久 —— 按连续撞到次数退避, 封顶 QUOTA_SKIP_TTL_MAX。"""
    strikes = max(1, _QUOTA_STRIKES.get(cb_key, 1))
    return min(QUOTA_SKIP_TTL * (2 ** (strikes - 1)), QUOTA_SKIP_TTL_MAX)


def _quota_save_state() -> None:
    """原子落盘。⚠️ 在调用主路径上 —— 任何异常都必须吞掉, 降级成纯内存行为。"""
    try:
        payload = {
            "version": 1,
            "entries": {
                k: {"until": v, "strikes": _QUOTA_STRIKES.get(k, 1)}
                for k, v in _QUOTA_EXHAUSTED_UNTIL.items()
            },
        }
        tmp = f"{_QUOTA_STATE_PATH}.tmp{os.getpid()}"
        try:
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(payload, fh)
            os.replace(tmp, _QUOTA_STATE_PATH)  # 原子: 并发进程最多互相覆盖, 不会读到半个文件
        except Exception:  # noqa: BLE001
            # os.replace 失败时 tmp 会残留, 每次失败每个 pid 留一个 —— 变异检验
            # (把 os.replace 改成 no-op) 时在工作区里真的看到了残留文件。清掉。
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
    except Exception:  # noqa: BLE001 — 持久化失败绝不能拖垮路由
        pass


def _quota_load_state() -> None:
    """启动时载入。取磁盘与内存里**更晚**的那个 until, 避免并发进程互相回退。"""
    try:
        with open(_QUOTA_STATE_PATH, encoding="utf-8") as fh:
            payload = json.load(fh)
        now = time.time()
        for key, rec in (payload.get("entries") or {}).items():
            until = float(rec.get("until") or 0.0)
            if until <= now:
                continue                       # 过期的不载入, 让它自然获得一次 re-probe
            if until > _QUOTA_EXHAUSTED_UNTIL.get(key, 0.0):
                _QUOTA_EXHAUSTED_UNTIL[key] = until
                _QUOTA_STRIKES[key] = int(rec.get("strikes") or 1)
    except FileNotFoundError:
        pass
    except Exception:  # noqa: BLE001 — 文件损坏/权限问题都只降级, 不抛
        pass


def _quota_should_skip(cb_key: str) -> bool:
    """True if this (account,model) returned a quota signal within its skip window.

    Auto-clears the mark once the window elapses so the model gets one re-probe; if
    that probe is still quota-exhausted it is re-marked for a **longer** window.
    """
    with _QUOTA_LOCK:
        until = _QUOTA_EXHAUSTED_UNTIL.get(cb_key, 0.0)
        if until <= 0.0:
            return False
        now = time.time()
        if now < until:
            return True
        # 窗口到期 — 但 re-probe 是全局限速的。一次失败请求会把沿途所有耗尽候选
        # 在同一瞬间打标, 它们的 TTL 于是同时到期; 若在这里逐个放行, 单个请求就会
        # 把十几个死模型全部真探一遍并烧光链预算(见 QUOTA_REPROBE_MIN_GAP 处的
        # 事故记录)。抢不到全局槽位的顺延一小段再试。
        global _QUOTA_LAST_REPROBE_AT
        if now - _QUOTA_LAST_REPROBE_AT < QUOTA_REPROBE_MIN_GAP:
            # 顺延, 且**不动 strikes** —— 这不是「又确认了一次没额度」, 只是没排上队。
            _QUOTA_EXHAUSTED_UNTIL[cb_key] = now + QUOTA_REPROBE_MIN_GAP
            return True
        _QUOTA_LAST_REPROBE_AT = now
        # 清掉标记放行一次 re-probe。strikes 保留: 若这次探针仍然 403,
        # 说明它确实还没恢复, 下一个窗口应该更长而不是退回 6h。
        del _QUOTA_EXHAUSTED_UNTIL[cb_key]
        return False


def _quota_record_exhausted(cb_key: str) -> None:
    """Mark this (account,model) as quota-exhausted, with escalating backoff."""
    with _QUOTA_LOCK:
        _QUOTA_STRIKES[cb_key] = _QUOTA_STRIKES.get(cb_key, 0) + 1
        _QUOTA_EXHAUSTED_UNTIL[cb_key] = time.time() + _quota_skip_seconds_for(cb_key)
        _quota_save_state()


def _quota_record_success(cb_key: str) -> None:
    """Clear the quota-exhausted mark on a clean success (quota came back).

    连 strikes 一起清零 —— 额度回来了就该立刻回到最短窗口, 否则一次抖动会把一个
    好模型长期压在 24h 退避里。
    """
    with _QUOTA_LOCK:
        had = _QUOTA_EXHAUSTED_UNTIL.pop(cb_key, None)
        had_strikes = _QUOTA_STRIKES.pop(cb_key, None)
        if had is not None or had_strikes is not None:
            _quota_save_state()


def get_quota_skip_stats() -> Dict[str, Any]:
    """Snapshot of currently quota-skipped (account,model) pairs for ops visibility."""
    with _QUOTA_LOCK:
        now = time.time()
        return {
            "skipped": {k: round(v - now, 1) for k, v in _QUOTA_EXHAUSTED_UNTIL.items() if v > now},
            "strikes": dict(_QUOTA_STRIKES),
            "ttl_seconds": QUOTA_SKIP_TTL,
            "ttl_max_seconds": QUOTA_SKIP_TTL_MAX,
            "state_path": _QUOTA_STATE_PATH,
        }


# 启动即载入 —— 不接这一行, 持久化就只写不读, 等于没做。
_quota_load_state()


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
# Most text slots = slot-tuned HEAD + shared _TEXT_TAIL (deduped). MAPPER is
# intentionally narrower: JSON classification must fail fast instead of
# cascading through expensive/slow Max, DeepSeek and Kimi models.
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


_TEXT_TAIL: List[Tuple[str, str]] = [
    # 非 DashScope 地板 —— 到期日为 None → _expiry_of 返回 _FAR_FUTURE →
    # _build_chain 的排序必然把它们放在所有 aliyun 条目之后。这正是
    # 「先榨干会过期的, 不过期的留到最后」。
    #
    # 2026-08-09 实测收缩: tencent 9 个只剩 minimax-m2.7(6.7s, 偏慢但是
    # 唯一非阿里/非智谱的活口); 其余 7 个 401008 FREE_QUOTA_EXHAUSTED,
    # kimi-k2.6 走参数层仍返回空内容。ark 两个全部 SetLimitExceeded → 清空。
    # 两家 provider 的配置与代码路径均保留, 待补齐清单后加回。
    #
    # ⛔ zhipu 必须留在**最后一位** —— 这条来自 2026-08-09 的一次生产事故(PR #2411,
    #    合并时从被本次重写删除的旧 MAPPER 链搬运至此, 判据本身没有过时):
    #    zhipu 在某些槽上会稳定超时, 而链是**串行且共享同一个总预算**。它排在前面
    #    会把预算吃光, 后面本来 0.5-1.1s 就能答的候选**因为分不到时间而跟着超时**,
    #    连续 2 次即被熔断 60 秒(CB_THRESHOLD=2, 403 与超时都计入失败)。当天实测
    #    有 4 个健康候选被这样连坐熔断, 而单独打时它们全部 HTTP 200。
    #    判据: **把一个会超时的候选排在前面, 等于把它后面的健康候选一起拖下水。**
    #    注: 这两条到期日都是 None, 稳定排序会原样保留此处的书写顺序, 所以顺序
    #    由这一行决定, 不由 _build_chain 决定。
    ("tencent", "minimax-m2.7"),
    ("zhipu", "glm-4.5-air"),
]

# ══ 资格层 ══════════════════════════════════════════════════════════════
# 下面三个名单是**人写的实测结论**, 独立于 _SLOT_POOLS 的定义 —— 闸拿它们
# 比对池内容时两边来源不同, 不是恒真式。

# 实测慢(关思考档 > 4s 或真实负载击穿 12s 交互预算)。禁止进交互槽的池。
# ⚠️ tencent/minimax-m2.7 也在此列, 但它属于 _TEXT_TAIL 地板, 由 _build_chain
#    单独追加, 不受本名单约束 —— 地板的职责是"前面全挂了还能答", 慢于不答。
_SLOW_MODELS: frozenset = frozenset({
    "deepseek-r1",                     # 8.6s 空载 / 13-25s 真实 REVIEW 负载
    "deepseek-r1-0528",                # 12.1s
    "qwen3-235b-a22b-thinking-2507",   # 9.5s
    "kimi-k2-thinking",                # 5.2s
    "qwen3.7-max-preview",             # 6.0-8.5s
    "minimax-m2.7",                    # 6.7s (地板, 见上)
})

# 开思考会返回空 content 或极慢 → 只进 profile 里 enable_thinking=false 的槽。
# 2026-08-09 实测: glm-4.6 推理档 44s / qwen3.6-plus-2026-04-02 17.8s /
# qwen3.5-plus-2026-02-15 21.1s (关思考档全部 ~1s)。
_THINKING_OFF_ONLY: frozenset = frozenset({
    "glm-4.6", "qwen3.6-plus-2026-04-02", "qwen3.5-plus-2026-02-15",
})

# 关思考会 400 → 只能进 REASONING(其 profile 为 {}, 不设 enable_thinking)。
# 2026-08-09 实测 aliyun_c/MiniMax-M2.5: 关思考 400, 开思考 3.6s OK。
_REASONING_ONLY: frozenset = frozenset({"MiniMax-M2.5"})


# ══ 候选池 ══════════════════════════════════════════════════════════════
# INSIGHTS 与 REVIEW 共用同一个质量档池: 两者判据逐字相同(质量优先 + 关思考档
# ≤4s), 各写一份 21 行迟早漂移成两张不一致的表。将来若真分化(例如 REVIEW 需要
# 更强的多轮上下文继承能力, 见 2026-08-09 的判别实验), 再从这里拆开。
_QUALITY_TIER_POOL: List[Tuple[str, str]] = [
    ("aliyun_c", "deepseek-v3.2"),                 # 08-13  1.1s
    ("aliyun_c", "glm-4.6"),                       # 08-13  0.9s
    # qwen3-max-2025-09-23 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
    ("aliyun_c", "qwen3.5-plus-2026-02-15"),       # 08-13  1.2s
    ("aliyun_c", "qwen3.6-plus-2026-04-02"),       # 08-13  1.1s
    ("aliyun_c", "qwen3-next-80b-a3b-instruct"),   # 08-13  0.8s
    ("aliyun_c", "qwen3.7-max-2026-05-20"),        # 08-20  1.1s
    # qwen3.7-max 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
    ("aliyun_c", "qwen3.7-max-2026-05-17"),        # 08-24  1.9s
    ("aliyun_a", "qwen3.7-max-2026-05-17"),        # 08-24  3.2s
    ("aliyun_b", "qwen3.7-max-2026-05-17"),        # 08-24  3.9s
    ("aliyun_c", "kimi-k2.7-code"),                # 09-14  1.8s
    ("aliyun_b", "kimi-k2.7-code"),                # 09-14  1.8s
    ("aliyun_a", "kimi-k2.7-code"),                # 09-14  2.1s
    ("aliyun_a", "qwen3.7-flash"),                 # 10-23  0.5s
    ("aliyun_a", "qwen3.7-flash-2026-07-15"),      # 10-23  0.6s
    ("aliyun_b", "deepseek-v4-flash-0731"),        # 10-31  1.3s
    ("aliyun_a", "deepseek-v4-flash-0731"),        # 10-31  1.5s
    ("aliyun_c", "qwen3.8-max"),                   # 11-01  1.0s
    ("aliyun_a", "qwen3.8-max"),                   # 11-01  1.1s
    ("aliyun_b", "qwen3.8-max"),                   # 11-01  1.1s
]

# 每个槽只声明「够资格」的候选。⛔ 这里的顺序**不是**最终链顺序 ——
# 它只在「同一到期日」时生效(_build_chain 用稳定排序), 表达的是质量优先级。
# 跨到期日的先后由 _build_chain 按到期日升序算, 人不要在这里排。
_SLOT_POOLS: Dict[SLOT, List[Tuple[str, str]]] = {
    # CHAT — 高频低延迟, 关思考。只收关思考档 ≤2s 的通用文本模型。
    SLOT.CHAT: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),   # 08-13  0.8s
        ("aliyun_c", "deepseek-v3.2-exp"),             # 08-13  0.9s
        ("aliyun_c", "glm-4.6"),                       # 08-13  0.9s
        ("aliyun_c", "deepseek-v3.2"),                 # 08-13  1.1s
        ("aliyun_c", "qwen3.6-plus-2026-04-02"),       # 08-13  1.1s
        ("aliyun_c", "qwen3.5-plus-2026-02-15"),       # 08-13  1.2s
        # qwen3-max-2025-09-23 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
        ("aliyun_c", "qwen3.7-max-2026-05-20"),        # 08-20  1.1s
        # qwen3.7-max 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
        ("aliyun_a", "qwen3.7-flash"),                 # 10-23  0.5s
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),      # 10-23  0.6s
        ("aliyun_b", "deepseek-v4-flash-0731"),        # 10-31  1.3s
        ("aliyun_a", "deepseek-v4-flash-0731"),        # 10-31  1.5s
        ("aliyun_c", "qwen3.8-max"),                   # 11-01  1.0s
        ("aliyun_a", "qwen3.8-max"),                   # 11-01  1.1s
        ("aliyun_b", "qwen3.8-max"),                   # 11-01  1.1s
    ],
    # CHART — 紧凑 JSON (关思考 + json_object)。与 CHAT 同一批快模型。
    SLOT.CHART: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "glm-4.6"),
        ("aliyun_c", "deepseek-v3.2"),
        # qwen3-max-2025-09-23 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
        ("aliyun_c", "qwen3.7-max-2026-05-20"),
        # qwen3.7-max 移除 08-10(探针 403, 见 _SAFE_MODELS 段落)
        ("aliyun_a", "qwen3.7-flash"),
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
        ("aliyun_c", "qwen3.8-max"),
        ("aliyun_a", "qwen3.8-max"),
        ("aliyun_b", "qwen3.8-max"),
    ],
    # MAPPER — 短 JSON 字段映射。池比 CHAT 更窄: Max 级对短分类既慢又浪费。
    #
    # ⚠️ 2026-08-09 前的旧链**故意不追加** _TEXT_TAIL, 注释原文: 「不追加通用
    # _TEXT_TAIL: Max/DeepSeek/Kimi 对短 JSON 分类既慢又浪费, 生产已证明会放大
    # 超时」。这条判据本身没有过时, 但本次重写让 MAPPER 落入 `_build_chain` 的
    # 通用规则(每个非 VL 槽都追加 _TEXT_TAIL), 于是 MAPPER 现在**也**以
    # tencent/minimax-m2.7(6.7s, `_SLOW_MODELS` 成员)收尾。这是**刻意推翻**旧
    # 判据, 不是遗漏: 08-13 那批到期后 MAPPER 池若无地板会变成空链(违反
    # test_every_text_slot_has_a_floor), 而"最后一跳偶尔 6.7s"被认为好于
    # "MAPPER 彻底答不出来"。⛔ 不要仅凭旧注释的"生产已证明会放大超时"就把
    # _TEXT_TAIL 从 MAPPER 摘掉 —— 那会让 MAPPER 在地板过期的那天重新变空链。
    #
    # ⛔ 往本池加模型前先读这条(2026-08-10 生产事故, PR #2411, 合并时搬运至此):
    #    当时因为 qwen3.7-max / qwen3-max-2025-09-23 在 **REVIEW** 槽的真实 prompt
    #    上打分 3/3, 就把它们加进了 MAPPER, 次日按 MAPPER 的契约又移除。
    #    判据: **一个模型在 A 槽表现好, 不构成把它放进 B 槽的理由** —— 槽的契约
    #    (延迟上界 / 成本 / 是否强制 thinking)先于打分。本池的契约是「短 JSON,
    #    快而有界」, 由 test_mapper_uses_bounded_fast_models_without_max_or_reasoners
    #    强制: 池内不得含 _THINKING_ONLY / _SLOW_MODELS / Max 档。
    #    (该闸 2026-08-09 从「模型名不含 max/deepseek/kimi」改成按实测属性判 ——
    #     名字代理会误伤 minimax-m2.7 里的 "max", 也拦不住一个叫得好听的慢模型。)
    SLOT.MAPPER: [
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "glm-4.6"),
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_a", "qwen3.7-flash"),
        ("aliyun_a", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
    ],
    # INSIGHTS / REVIEW — 共用质量档池, 见下方 _QUALITY_TIER_POOL 定义。
    SLOT.INSIGHTS: list(_QUALITY_TIER_POOL),
    SLOT.REVIEW: list(_QUALITY_TIER_POOL),
    # REASONING — 允许慢, profile 为 {} (不设 enable_thinking)。
    SLOT.REASONING: [
        ("aliyun_c", "deepseek-v3.2"),
        ("aliyun_c", "deepseek-v3.2-exp"),
        ("aliyun_c", "qwen3-next-80b-a3b-instruct"),
        ("aliyun_c", "MiniMax-M2.5"),                  # 仅此槽可用(关思考会 400)
        ("aliyun_c", "kimi-k2-thinking"),
        ("aliyun_c", "deepseek-r1"),
        ("aliyun_c", "qwen3-235b-a22b-thinking-2507"),
        ("aliyun_c", "deepseek-r1-0528"),
        ("aliyun_c", "qwen3.7-max-2026-05-17"),
        ("aliyun_a", "qwen3.7-max-2026-05-17"),
        ("aliyun_b", "qwen3.7-max-2026-05-17"),
        ("aliyun_c", "qwen3.7-max-preview"),
        ("aliyun_a", "qwen3.7-max-preview"),
        ("aliyun_b", "qwen3.7-max-preview"),
        ("aliyun_c", "kimi-k2.7-code"),
        ("aliyun_b", "kimi-k2.7-code"),
        ("aliyun_a", "kimi-k2.7-code"),
        ("aliyun_b", "deepseek-v4-flash-0731"),
        ("aliyun_a", "deepseek-v4-flash-0731"),
        ("aliyun_c", "qwen3.8-max"),
        ("aliyun_a", "qwen3.8-max"),
        ("aliyun_b", "qwen3.8-max"),
    ],
    # VL — 仅视觉。⚠️ qwen3-vl-32b-instruct 已于 08-10 探针剔除(见 _SAFE_MODELS
    #      段落), 只剩 qwen3-vl-flash-2026-01-22 一条, 而它自己也在 2026-08-13
    #      到期 —— 届时链会变空, call_chain 抛 All providers exhausted for vl。
    #      这是**期望行为**(spec §9.1, owner 2026-08-09 拍板): 业务用不到 VL
    #      (prod 7 天仅 1 次真实调用), 且原 VL 地板 zhipu/glm-4.6v 已因余额不足
    #      死亡。明确报错优于把图片请求静默降级给文本模型瞎猜 —— CLAUDE.md
    #      核心原则 1。不为它补新 VL 候选。
    SLOT.VL: [
        ("aliyun_c", "qwen3-vl-flash-2026-01-22"),     # 08-13  0.7s
    ],
}

# VL 槽不追加文本地板 —— 文本模型看不见图片, 追加只会把「明确失败」变成
# 「拿一段瞎猜的文字冒充图片理解」。这个集合是 _build_chain 的唯一例外,
# 也是 test_every_text_slot_has_a_floor 的唯一豁免项。
_NO_TEXT_TAIL_SLOTS: frozenset = frozenset({SLOT.VL})


def _build_chain(slot: SLOT) -> List[Tuple[str, str]]:
    """按免费额度到期日升序拼链 —— use-it-or-lose-it。

    ⚠️ 这里**推翻**了旧注释 "Runtime order is authoritative (no re-sort)"。
    旧契约要求人手写最终顺序, 而 _SAFE_MODELS 的 docstring 与 _expiry_of()
    从一开始就写着 "soonest-expiry-first" —— 意图在注释里, 约束不存在, 于是
    每个到期日都要人改一次, 漏一次链就腐烂一次。2026-08-09 实测后果: 三个
    aliyun 账号约 1800 万 token 可用额度 router 一个都够不着, 而链里 5 个
    aliyun_a/b 条目实测 5/5 全 403 在空转。改成代码算, 到期日一到自动重排。

    稳定排序: 同一到期日保持 _SLOT_POOLS 里人写的顺序(= 质量优先级),
    只有跨到期日才重排。到期日为 None 的地板 → _FAR_FUTURE → 必然沉底。
    """
    entries = list(_SLOT_POOLS[slot])
    if slot not in _NO_TEXT_TAIL_SLOTS:
        entries += _TEXT_TAIL
    return _dedup_chain(sorted(entries, key=lambda p: _expiry_of(*p)))


SLOT_MODELS: Dict[SLOT, List[Tuple[str, str]]] = {s: _build_chain(s) for s in SLOT}


# ═══════════════════════════════════════════════════════════════════════════
# Layer 3 — per-SLOT param profile (injected into every payload).
# Biggest lever: enable_thinking=false on fast slots. qwen3.5+/3.7 default thinking
# ON → wastes 10-20x latency + 1200-3561 reasoning tokens/call (measured on
# qwen3.7-max: 16.5s→1.1s, qwen3.5-flash: 11.6s→0.6s). thinking-only models can't
# toggle (skipped). json_object (CHART/MAPPER) needs enable_thinking=false + "json"
# in the prompt + no max_tokens (truncation = parse fail).
# ═══════════════════════════════════════════════════════════════════════════
_ALIYUN_ACCOUNTS: frozenset = frozenset({
    "aliyun_a", "aliyun_b", "aliyun_c", "aliyun_a_deepseek",
})

_SLOT_PARAMS: Dict[SLOT, Dict[str, Any]] = {
    SLOT.CHAT:      {"enable_thinking": False},
    SLOT.INSIGHTS:  {"enable_thinking": False},
    SLOT.CHART:     {"enable_thinking": False, "json": True, "temperature": 0, "seed": 1234},
    SLOT.MAPPER:    {"enable_thinking": False, "json": True, "temperature": 0, "seed": 1234},
    SLOT.REASONING: {},  # NO enable_thinking: deepseek-v3.1 400s on true (only supports
                          # false/absent, benchmark 2026-07-01); and forced deep thinking
                          # (31-64s) times out call_chain's 30s budget → fallback. deepseek /
                          # thinking-only models reason well by default in ~1s. Callers needing
                          # extended thinking use call_chain_stream (45s) + pass it themselves.
    SLOT.VL:        {"enable_thinking": False},
    SLOT.REVIEW:    {"enable_thinking": False},
}


def _payload_mentions_json(payload: Dict[str, Any]) -> bool:
    """True if any message content mentions 'json' — required before enabling
    response_format:json_object (DashScope 400s otherwise: 'messages must contain
    the word json')."""
    for m in (payload.get("messages") or []):
        c = m.get("content")
        if isinstance(c, str) and "json" in c.lower():
            return True
        if isinstance(c, list):
            for part in c:
                if isinstance(part, dict) and "json" in str(part.get("text", "")).lower():
                    return True
    return False


# ── TokenHub per-model payload constraints (2026-07-30, measured with prod keys).
#
# These are provider-enforced rules the OpenAI-compatible schema cannot express, so
# they have to live here — `_normalize_payload_for_provider` was a passthrough and
# never knew tencent existed, which is why the models below were 100% unusable
# rather than merely slow.

# TokenHub rejects any temperature but 1 for the kimi family:
#   HTTP 400 400001 "invalid temperature: only 1 is allowed for this model".
# Keyed by model and applied ONLY on tencent — the same kimi on DashScope takes
# temperature=0 fine, so this is a service constraint, not a model constraint.
_TOKENHUB_FORCED_TEMPERATURE: Dict[str, float] = {
    "kimi-k3": 1.0,
    "kimi-k2.6": 1.0,
    "kimi-k2.5": 1.0,
}

# TokenHub M2.x ignores thinking.disabled, so these spend the entire
# allowance on `reasoning_content` and return an EMPTY `content` — the router then
# logs "output invalid (empty)" and falls back, i.e. they look broken when they are
# merely starved. Measured at max_tokens=500: finish_reason='length',
# completion_tokens_details.reasoning_tokens=500, content=''.
# 1600 is what made minimax answer in the probe; it is a FLOOR, never a cap.
_TOKENHUB_MIN_MAX_TOKENS: Dict[str, int] = {
    "minimax-m2.7": 1600,
    "minimax-m2.5": 1600,
}

# TokenHub uses two different thinking switches by model family. The public
# OpenAI-compatible shape alone is insufficient: Qwen3.5 explicitly requires
# enable_thinking=false, while GLM / DeepSeek / MiniMax-M3 use the common
# thinking={"type":"disabled"} object. Omitting either reproduced the production
# failure (20s Qwen timeouts or HTTP 200 with empty content); measured 2026-08-02.
_TOKENHUB_ENABLE_THINKING_MODELS: frozenset[str] = frozenset({
    "qwen3.5-plus",
    "qwen3.5-flash",
})
_TOKENHUB_THINKING_OBJECT_MODELS: frozenset[str] = frozenset({
    "deepseek-v3.2",
    "deepseek-v4-flash",
    "deepseek-v4-flash-202605",
    "deepseek-v4-pro",
    "deepseek-v4-pro-202606",
    "glm-5.1",
    "glm-5.2",
    "minimax-m3",
})

# Ark expresses "do not think" with its OWN field — `thinking: {"type": "disabled"}`
# — not DashScope's `enable_thinking`. This is not a micro-optimisation: with
# thinking left on, Ark answers the T3 prompt CORRECTLY (5/5) but in 8-66s, which
# is a timeout against the caller's 5s-per-provider budget. Switching it off took
# the same models to 1.2-5.0s and made five of them viable. Measured 2026-07-30 on
# all 8 reachable Ark models — none rejected the field.
_ARK_DISABLE_THINKING: Dict[str, Any] = {"type": "disabled"}

# Zhipu's OpenAI-compatible endpoint uses the same object shape for GLM-4.5+
# (not DashScope's ``enable_thinking`` boolean).  Without this translation the
# model defaults to dynamic thinking and can spend the caller's entire output
# budget in ``reasoning_content``, leaving an empty ``content`` even on HTTP
# 200.  Keep the allowlist explicit so an older/future incompatible Zhipu SKU
# cannot inherit the parameter merely because it shares the provider account.
_ZHIPU_THINKING_OBJECT_MODELS: frozenset[str] = frozenset({
    "glm-4.5-air",
    "glm-4.6v",
})


def _apply_slot_params(slot: SLOT, account: str, model: str,
                       payload: Dict[str, Any]) -> Dict[str, Any]:
    """Apply the SLOT's param profile to a per-call payload (model already set).
    Returns a new dict. Provider-aware: enable_thinking is a DashScope param → only
    for aliyun + hybrid (non-thinking-only) models; TokenHub adds its own per-model
    sampling constraints (see the two maps above)."""
    prof = _SLOT_PARAMS.get(slot) or {}
    p = {**payload}
    is_aliyun = account in _ALIYUN_ACCOUNTS
    if "enable_thinking" in prof and is_aliyun and model not in _THINKING_ONLY:
        p["enable_thinking"] = prof["enable_thinking"]
        p.setdefault("enable_search", False)  # web-search off (latency/nondeterminism)
    # json_object only when the prompt already mentions "json" (else 400) — avoids
    # breaking callers whose CHART/MAPPER prompt lacks the keyword.
    if prof.get("json") and _payload_mentions_json(p):
        p["response_format"] = {"type": "json_object"}
        p.pop("max_tokens", None)
        p.pop("max_completion_tokens", None)
    if "temperature" in prof:
        p["temperature"] = prof["temperature"]
    if "seed" in prof and is_aliyun:
        p.setdefault("seed", prof["seed"])
    # Ark: translate the slot's thinking intent into Ark's own field. Only when the
    # profile actually asks for thinking off — a slot that wants reasoning (REASONING)
    # must keep it.
    if account == "ark" and prof.get("enable_thinking") is False:
        p["thinking"] = dict(_ARK_DISABLE_THINKING)
    # Zhipu GLM-4.5+ also requires the provider-specific object.  This belongs
    # after the Aliyun branch: never leak DashScope's ``enable_thinking`` into
    # Zhipu's OpenAI-compatible request.
    if (
        account == "zhipu"
        and model in _ZHIPU_THINKING_OBJECT_MODELS
        and prof.get("enable_thinking") is False
    ):
        p["thinking"] = {"type": "disabled"}
    # TokenHub is provider-compatible but not parameter-uniform. Follow its official
    # per-model guides instead of assuming DashScope or Ark semantics.
    if account == "tencent" and prof.get("enable_thinking") is False:
        if model in _TOKENHUB_ENABLE_THINKING_MODELS:
            p["enable_thinking"] = False
        elif model in _TOKENHUB_THINKING_OBJECT_MODELS:
            p["thinking"] = {"type": "disabled"}
    # TokenHub constraints last: they are hard provider requirements, so they must
    # win over both the caller's payload and the slot profile (violating them is a
    # guaranteed 400 / empty response, not a quality trade-off).
    if account == "tencent":
        forced_temp = _TOKENHUB_FORCED_TEMPERATURE.get(model)
        if forced_temp is not None:
            p["temperature"] = forced_temp
        floor = _TOKENHUB_MIN_MAX_TOKENS.get(model)
        if floor is not None:
            current = p.get("max_tokens")
            if not isinstance(current, int) or current < floor:
                p["max_tokens"] = floor
    return p


# ── Layer 4 — outcome validation (highest-value: router was quality-blind, any 200
# accepted → empty/garbage reached users with no fallback). Validate per-slot; on
# failure fall to the next chain entry.
_MIN_TEXT_LEN = 8  # INSIGHTS/REVIEW floor — shorter than this = likely garbage/refusal


def _extract_content(body_json: Dict[str, Any]) -> str:
    """Pull assistant content from an OpenAI-compatible response, defensively."""
    try:
        msg = (body_json.get("choices") or [{}])[0].get("message") or {}
        return (msg.get("content") or "").strip()
    except Exception:
        return ""


def _validate_output(slot: SLOT, content: str) -> Optional[str]:
    """Return an invalidity reason (→ fall to next model) or None if the output is
    acceptable for this slot. Cheap, deterministic — the free half of a FrugalGPT
    cascade (reject garbage + retry, no expensive judge)."""
    if not content or not content.strip():
        return "empty"
    if slot in (SLOT.CHART, SLOT.MAPPER):
        s = content.strip()
        if s.startswith("```"):  # strip markdown fences some models add
            s = s.strip("`")
            if s[:4].lower() == "json":
                s = s[4:]
        s = s.strip()
        try:
            json.loads(s)
        except Exception:
            return "bad_json"
    elif slot in (SLOT.INSIGHTS, SLOT.REVIEW):
        if len(content.strip()) < _MIN_TEXT_LEN:
            return "too_short"
    return None


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
        # ark (Volcengine 火山方舟, 2026-07-30): OpenAI-compatible, ~36 ACTIVE text
        # models each holding an untouched 500k free inference grant — a provider
        # fully independent of both DashScope and TokenHub.
        #
        # ⛔ Billing premise is DIFFERENT from tencent and must be restated here,
        # because getting it wrong is exactly the silent-billing failure this whole
        # registry exists to prevent: Ark bills post-paid after the free grant
        # UNLESS 安心体验模式 is on, in which case (per the official rule) 「仅消耗
        # 免费额度，超出免费额度时服务将自动暂停，不会产生额外费用」. Steve confirmed
        # it is ON for this account on 2026-07-30 — that confirmation, not the
        # console's per-model badge, is what makes these entries admissible.
        # If it is ever switched off, every ark entry must leave the chains.
        #
        # 🔴 Model ids MUST come from GET /api/v3/models, never from the console's
        # display name: `doubao-seed-1.6` returns 404
        # InvalidEndpointOrModel.NotFound while the callable id is
        # `doubao-seed-1-6-251015`. Same trap as TokenHub's deepseek-v4-pro vs
        # deepseek-v4-pro-202606. The list also carries a `status` field
        # (Shutdown / Retiring / active) — prefer active, Retiring is a countdown.
        "ark": (
            os.getenv("LLM_ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"),
            os.getenv("LLM_ARK_API_KEY", ""),
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
    """Detect a FREE-GRANT exhaustion (→ long 6h quota-skip until monthly reset).

    A generic 429 is a transient burst rate-limit and must keep the short circuit-
    breaker cooldown. Volcengine Ark also uses 429 for ``SetLimitExceeded`` though:
    that response explicitly says the account's configured inference allowance was
    reached and the model service was paused. Re-probing a paused model every minute
    only burns latency, so that one exact provider code belongs in the persistent
    quota-skip path while ordinary 429 responses do not. Zhipu (added 2026-08-09)
    signals the same free-grant exhaustion with 429 + a Chinese "余额不足" body or
    ``"code":"1113"``, with no ``SetLimitExceeded`` substring — matched separately
    below since the string signature is provider-specific, not the 429 status alone.
    """
    lowered_body = body_text.lower()
    if status_code == 429 and "setlimitexceeded" in lowered_body:
        return True
    # Zhipu: 余额耗尽用 429 + 中文报文 + code 1113, 不含 SetLimitExceeded。
    # 2026-08-09 实测 glm-4.6v。结构上等同额度耗尽($0 且不会自愈), 分到
    # 短熔断只会每 60s 空转重试一次。
    # 两个特征命中任意一个即可(OR, 不是 AND) —— 真实响应可能只带其中一个,
    # 要求同时命中会让它重新掉回 60s 空转循环, 正是本改动要消除的东西。
    # 普通 429 突发限流两个都不命中, 故不受影响, 见
    # test_plain_429_rate_limit_is_still_transient。
    if status_code == 429 and ("余额不足" in body_text or '"code":"1113"' in body_text):
        return True
    if status_code == 403:
        return "FreeTierOnly" in body_text or "AllocationQuota" in body_text
    if status_code == 402 and (
        "insufficient balance" in lowered_body
        or "free_quota_exhausted" in lowered_body
        or ("free trial quota" in lowered_body and "exhaust" in lowered_body)
        or "401008" in lowered_body
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


_BUDGET_AWARE_FAST_SLOTS = frozenset({SLOT.CHAT, SLOT.CHART, SLOT.MAPPER})
_FAST_SLOT_RESERVE_RATIO = 0.40
_FAST_SLOT_RESERVE_CAP_SECONDS = 1.0
_MIN_ATTEMPT_TIMEOUT_SECONDS = 0.05


def _has_callable_fallback(
    slot_chain: List[Tuple[str, str]],
    start_index: int,
) -> bool:
    """Whether a later candidate can pass every local pre-request gate."""
    for account, model in slot_chain[start_index:]:
        if (
            model
            and _refuse_reason(account, model) is None
            and not _cb_should_skip(f"{account}/{model}")
            and not _quota_should_skip(f"{account}/{model}")
            and bool(_provider_config(account)[1])
        ):
            return True
    return False


def _budgeted_attempt_timeout(
    slot: SLOT,
    per_provider_timeout: float,
    remaining: Optional[float],
    *,
    has_callable_fallback: bool,
) -> float:
    """Reserve part of an interactive deadline for a later healthy candidate."""
    if remaining is None:
        return per_provider_timeout
    usable = max(_MIN_ATTEMPT_TIMEOUT_SECONDS, remaining)
    if slot not in _BUDGET_AWARE_FAST_SLOTS or not has_callable_fallback:
        return min(per_provider_timeout, usable)
    reserve = min(
        _FAST_SLOT_RESERVE_CAP_SECONDS,
        usable * _FAST_SLOT_RESERVE_RATIO,
    )
    return min(
        per_provider_timeout,
        max(_MIN_ATTEMPT_TIMEOUT_SECONDS, usable - reserve),
    )


async def call_chain(
    slot: SLOT,
    payload: Dict[str, Any],
    chain: Optional[List[str]] = None,
    timeout: float = 30.0,
    total_timeout: Optional[float] = None,
    content_validator: Optional[Callable[[str], Optional[str]]] = None,
) -> Dict[str, Any]:
    """
    Call LLM via provider chain with automatic fallback on 403 FreeTierOnly / 429.

    ``content_validator`` (2026-07-30) closes the poison-pill hole: return a short
    reason string to REJECT this candidate's output and continue the cascade, or
    None to accept. `_validate_output` can only judge slot-generic shape (empty /
    bad JSON / too short); it cannot know that a syntactically perfect plan carries
    an out-of-contract field. Restaurant T3 hit exactly that — aliyun_c/deepseek-v3.2
    answers correctly with confidence=-1.0, the router counted it as success, and
    every healthy model behind it became unreachable. The caller owns its contract,
    so the caller supplies the predicate. A raising predicate is treated as
    "reject this candidate", never propagated to the user.

    Per-call timeout: 30s default (Apr 28 2026 optimization, was 120s).
    Worst-case full 4-provider cascade = 120s. qwen-plus typical 15-30s, so
    30s is comfortable margin while failing fast on overloaded providers.
    Callers with an interactive latency contract may also set ``total_timeout``;
    it is a wall-clock budget for the entire provider cascade, not another
    per-provider allowance.

    The payload's `model` field is OVERWRITTEN per-provider based on SLOT_MODELS.
    Other fields (messages, temperature, max_tokens, etc.) are preserved.

    Returns parsed JSON response from the first successful provider.
    Raises RuntimeError if all providers exhaust.
    """
    slot_chain = SLOT_MODELS.get(slot, [])
    if chain is not None:
        # Optional account-filter override (legacy callers pass account names).
        slot_chain = [(ac, m) for (ac, m) in slot_chain if ac in chain]
    # SLOT_MODELS order is computed once at import time by _build_chain (expiry-first
    # stable sort over _SLOT_POOLS + _TEXT_TAIL, see _build_chain's docstring) — this
    # function does NOT re-sort it. _refuse_reason drops expired/unsafe entries so
    # heads auto-switch as grants lapse, without anyone hand-reordering the list.
    client = get_llm_http_client()
    errors: List[str] = []
    deadline = (
        time.monotonic() + total_timeout
        if total_timeout is not None and total_timeout > 0
        else None
    )

    for candidate_index, (account, model) in enumerate(slot_chain):
        remaining = deadline - time.monotonic() if deadline is not None else None
        if remaining is not None and remaining <= 0:
            errors.append("chain: total_timeout")
            break
        if not model:
            continue
        # ⛔ Unified billing-safety gate (allowlist ∧ ¬denylist ∧ ¬expired ∧ ¬stale).
        # SAME helper in call_chain_stream so the two paths cannot drift. The router
        # cannot detect a paid 200, so refusing non-ON / expired models here IS the
        # billing guard.
        _refuse = _refuse_reason(account, model)
        if _refuse:
            logger.error(
                f"[llm_router] slot={slot.value} refusing {account}/{model} "
                f"({_refuse}) — protecting billing"
            )
            errors.append(f"{account}/{model}: {_refuse}")
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

        req_payload = _apply_slot_params(
            slot, account, model,
            _normalize_payload_for_provider({**payload, "model": model}, account),
        )
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

        try:
            logger.debug(f"[llm_router] slot={slot.value} try {account}/{model}")
            request_timeout = _budgeted_attempt_timeout(
                slot,
                timeout,
                remaining,
                has_callable_fallback=_has_callable_fallback(
                    slot_chain,
                    candidate_index + 1,
                ),
            )
            # Apr 28 2026 (post-review P1, then reviewer round 2 correction):
            # API consistency only — bare `timeout=timeout` and
            # `timeout=httpx.Timeout(timeout)` are EQUIVALENT in httpx (a bare
            # float is shorthand that sets connect=read=write=pool=value, all
            # independent budgets). Phase timeouts are NOT summed. The earlier
            # commit message claim about "TOTAL timeout / 7.5s per phase" was
            # wrong. Keeping the explicit form matches `call_chain_stream`
            # below for readability — no behavior change.
            resp = await asyncio.wait_for(
                client.post(
                    f"{base_url}/chat/completions",
                    headers=headers,
                    json=req_payload,
                    timeout=httpx.Timeout(request_timeout),
                ),
                timeout=request_timeout,
            )
            body_text = resp.text  # may trigger aread() internally

            if 200 <= resp.status_code < 300:
                body_json = resp.json()
                # Layer 4 — outcome validation: a 2xx with empty / garbage / invalid-
                # JSON body is NOT success. Fall to the next chain entry instead of
                # handing garbage to the caller (do NOT record CB success on bad output).
                content = _extract_content(body_json)
                invalid = _validate_output(slot, content)
                if not invalid and content_validator is not None:
                    try:
                        invalid = content_validator(content)
                    except Exception as exc:  # noqa: BLE001
                        # A buggy caller predicate must not turn a working provider
                        # cascade into a 500 — degrade to "this candidate is no good".
                        invalid = f"validator_error_{type(exc).__name__}"
                if invalid:
                    logger.warning(
                        f"[llm_router] slot={slot.value} {account}/{model} output "
                        f"invalid ({invalid}) — falling back"
                    )
                    errors.append(f"{account}/{model}: invalid_{invalid}")
                    continue
                _cb_record_success(cb_key)
                _quota_record_success(cb_key)
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
    # SLOT_MODELS order is computed once at import time by _build_chain (expiry-first
    # stable sort); this function does not re-sort it either — see call_chain above.
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
        # ⛔ Unified billing-safety gate — SAME _refuse_reason as call_chain. This
        # path historically LACKED the expiry gate (billing-audit CRITICAL 3); now
        # unified via one helper so the two paths cannot drift.
        _refuse = _refuse_reason(account, model)
        if _refuse:
            logger.error(
                f"[llm_router_stream] slot={slot.value} refusing {account}/{model} "
                f"({_refuse}) — protecting billing"
            )
            errors.append(f"{account}/{model}: {_refuse}")
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

        req_payload = _apply_slot_params(
            slot, account, model,
            _normalize_payload_for_provider({**payload, "model": model}, account),
        )
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
