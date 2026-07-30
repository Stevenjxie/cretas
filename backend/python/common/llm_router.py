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
# deepseek-v4-pro is ON on aliyun_c but 不支持开启(PAID) on aliyun_b; kimi-k2.7-code /
# qwen3.5-ocr are ON on a/c but 未开启(BILLS) on b. A global model allow/deny is WRONG.
#
# Value = free-grant expiry date (own date from console; account bulk-expiry for
# exhausted-ON models that showed no date; None for tencent/zhipu which have no
# DashScope expiry — they are billing-safe via their own 用完即停/pool cap).
# ═══════════════════════════════════════════════════════════════════════════
_REGISTRY_AUDIT_DATE = datetime.date(2026, 7, 26)  # 三控制台实测核对
# (2026-07-26 用户逐账户控制台截图): 所拍 A/B/C 模型均已开启
# “免费额度用完即停”。这里只收录经任务能力筛选后的通用文本模型，不把
# OCR/VL/Math/Character/Code 专用 SKU 混入通用链。B/C 两账户的
# qwen3.7-flash-2026-07-15 与 qwen3.7-flash 均剩 1,000,000/1,000,000，
# 到期 2026-10-23；它们成为快速文本槽的主链和跨账户故障转移。
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
    # ── aliyun_a (90bc) — ONLY the 13 screenshot-confirmed ON models. NO VL/deepseek/
    #    glm (toggle unknown → could bill). Sooner expiries → used first (use-it-or-lose-it).
    ("aliyun_a", "qwen3.6-plus-2026-04-02"): _d(2026, 7, 2),
    ("aliyun_a", "qwen3.6-flash"): _d(2026, 7, 17),
    ("aliyun_a", "kimi-k2.6"): _d(2026, 7, 21),
    ("aliyun_a", "qwen3.5-plus-2026-04-20"): _d(2026, 7, 23),
    ("aliyun_a", "qwen3.6-27b"): _d(2026, 7, 23),
    ("aliyun_a", "qwen3.7-max-2026-05-20"): _d(2026, 8, 20),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_a", "qwen3.7-max-preview"): _d(2026, 8, 24),
    ("aliyun_a", "qwen3.7-plus"): _d(2026, 9, 1),
    ("aliyun_a", "qwen3.7-plus-2026-05-26"): _d(2026, 9, 1),
    ("aliyun_a", "qwen3.7-max-2026-06-08"): _d(2026, 9, 8),
    ("aliyun_a", "kimi-k2.7-code"): _d(2026, 9, 14),  # thinking-only → REASONING slot only

    # ── aliyun_b (3177) — bulk 07/16; premium drained. Curated good ON models.
    ("aliyun_b", "qwen3.7-flash-2026-07-15"): _d(2026, 10, 23),
    ("aliyun_b", "qwen3.7-flash"): _d(2026, 10, 23),
    ("aliyun_b", "qwen3.7-max-2026-05-20"): _d(2026, 8, 20),
    ("aliyun_b", "qwen3.7-max-preview"): _d(2026, 8, 24),
    ("aliyun_b", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_b", "qwen3.7-plus"): _d(2026, 9, 1),
    ("aliyun_b", "qwen3.7-plus-2026-05-26"): _d(2026, 9, 1),
    ("aliyun_b", "qwen3-max-preview"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3-max-2025-09-23"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3.5-397b-a17b"): _d(2026, 7, 16),
    ("aliyun_b", "deepseek-v3"): _d(2026, 7, 16),
    ("aliyun_b", "deepseek-v3.2"): _d(2026, 7, 16),
    ("aliyun_b", "deepseek-r1"): _d(2026, 7, 16),          # thinking-only
    ("aliyun_b", "deepseek-r1-0528"): _d(2026, 7, 16),     # thinking-only
    ("aliyun_b", "glm-5"): _d(2026, 7, 16),
    ("aliyun_b", "glm-4.7"): _d(2026, 7, 16),  # bare glm-4.5 dropped — stream-only (H4 probe 400)
    ("aliyun_b", "qwen-turbo"): _d(2026, 7, 16),
    ("aliyun_b", "qwen-flash"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3-coder-flash"): _d(2026, 7, 16),
    ("aliyun_b", "qwen-plus-latest"): _d(2026, 7, 16),     # exhausted-ON (403 safe)
    ("aliyun_b", "qwen3-vl-plus-2025-12-19"): _d(2026, 7, 16),
    ("aliyun_b", "qwen-vl-max"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3-vl-plus"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3-vl-32b-instruct"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3-vl-flash"): _d(2026, 7, 16),
    ("aliyun_b", "qwen3.6-flash-2026-04-16"): _d(2026, 7, 17),
    ("aliyun_b", "kimi-k2.6"): _d(2026, 7, 21),
    ("aliyun_b", "qwen3.5-plus-2026-04-20"): _d(2026, 7, 23),
    ("aliyun_b", "qwen3.6-27b"): _d(2026, 7, 23),

    # ── aliyun_c (a736) — bulk 08/13; fullest account, nearly all ON+quota.
    ("aliyun_c", "qwen3.7-flash-2026-07-15"): _d(2026, 10, 23),
    ("aliyun_c", "qwen3.7-flash"): _d(2026, 10, 23),
    ("aliyun_c", "qwen3.5-flash"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3.6-flash-2026-04-16"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-coder-flash"): _d(2026, 8, 13),
    ("aliyun_c", "qwen-plus-latest"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-max-preview"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-max-2025-09-23"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3.7-max-2026-06-08"): _d(2026, 9, 8),
    ("aliyun_c", "qwen3.7-max"): _d(2026, 8, 20),
    ("aliyun_c", "qwen3.7-max-2026-05-20"): _d(2026, 8, 20),
    ("aliyun_c", "qwen3.7-max-preview"): _d(2026, 8, 24),
    ("aliyun_c", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),
    ("aliyun_c", "qwen3.7-plus"): _d(2026, 9, 1),
    ("aliyun_c", "qwen3.7-plus-2026-05-26"): _d(2026, 9, 1),
    ("aliyun_c", "deepseek-v3.1"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-v3"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-v3.2-exp"): _d(2026, 8, 13),
    ("aliyun_c", "deepseek-r1"): _d(2026, 8, 13),          # thinking-only
    ("aliyun_c", "deepseek-r1-0528"): _d(2026, 8, 13),     # thinking-only
    ("aliyun_c", "glm-5"): _d(2026, 8, 13),
    ("aliyun_c", "glm-5.1"): _d(2026, 8, 13),
    ("aliyun_c", "glm-5.2"): _d(2026, 9, 15),
    ("aliyun_c", "glm-4.6"): _d(2026, 8, 13),
    ("aliyun_c", "glm-4.7"): _d(2026, 8, 13),  # bare glm-4.5 dropped — stream-only (H4 probe 400)
    ("aliyun_c", "qwen3-vl-plus-2025-12-19"): _d(2026, 8, 13),
    ("aliyun_c", "qwen-vl-max"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-plus"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-32b-instruct"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-vl-30b-a3b-instruct"): _d(2026, 8, 13),
    ("aliyun_c", "qwen3-235b-a22b-thinking-2507"): _d(2026, 8, 13),  # thinking-only
    ("aliyun_c", "kimi-k2.6"): _d(2026, 8, 13),
    ("aliyun_c", "kimi-k2-thinking"): _d(2026, 8, 13),     # thinking-only
    ("aliyun_c", "kimi-k2.7-code"): _d(2026, 9, 14),       # thinking-only

    # ── tencent (m00t) TokenHub trial — 用完即停 safe, no DashScope expiry (None).
    #
    # 计费安全前提(不变): 该账号后付费未开启, 额度耗尽直接 402 拒绝而非计费
    # (实测报文 401008 "free trial quota exhausted ... postpaid billing is not
    # enabled")。所以整段用 None(不过期), 靠"用完即停"兜住 billing。
    #
    # 🔴 2026-07-30 晚, 按**控制台的服务 ID + 余量两列**核对了整张表(此前一直靠 402
    # 反推, 得出了"额度没领"的错误结论)。判据只有控制台的「状态 + 余量」——
    # `/v1/models` 的 status 只说模型在不在线, 与有没有额度是两件事。
    #
    # ⛔ 余额 0 / 已停止 —— 保留登记但**不得进任何 chain**(调它只会白烧一次请求,
    # 然后把该 (account,model) 塞进 6h quota-skip 缓存):
    ("tencent", "deepseek-v4-flash"): None,   # 控制台: 已停止, 余 0 / 1M
    ("tencent", "glm-5.1"): None,             # 控制台: 已停止, 余 0 / 500k
    ("tencent", "qwen3.5-flash"): None,       # 控制台: 已停止, 余 0 / 1M
    #
    # ✅ 2026-07-30 实测可用(餐饮 T3 真实 prompt, 5 个不同问句全过,
    #    生产原始 payload max_tokens=500 —— 即无需放宽任何参数):
    ("tencent", "hy-mt2-pro"): None,               # 余 1M, 1.3-1.5s, 最快最稳
    ("tencent", "deepseek-v3.1-terminus"): None,   # 余 500k, 2.2-2.6s, RPM 500
    ("tencent", "qwen3.5-plus"): None,             # 余 998k, 3.5-5.7s
    #
    # ⚠️ 只在放宽 max_tokens 后可用: TokenHub 忽略 enable_thinking=false, 500 token
    # 全烧在 reasoning_content 上, content 返回空。见 _TOKENHUB_MIN_MAX_TOKENS。
    ("tencent", "minimax-m2.7"): None,        # 余 908k
    #
    # ⚠️ 需要 temperature=1(TokenHub 的按模型采样约束), 见 _TOKENHUB_FORCED_TEMPERATURE。
    ("tencent", "kimi-k2.6"): None,           # 余 393k
    #
    # 控制台 ID 是 deepseek-v4-pro-202606(余 1M); 旧代码写的 `deepseek-v4-pro` 不存在,
    # 所以它的 402 一直被误读成"额度没领"。⚠️ 实测在餐饮 T3 prompt 上两档 max_tokens
    # 都不可用(500 截断 / 1600 转 thinking 后 content 空), 故只留给允许 thinking 的
    # REASONING 槽, 不进 REVIEW。
    ("tencent", "deepseek-v4-pro-202606"): None,
    # TokenHub 的 deepseek-v3.2 与 aliyun 同名模型**行为不同**(aliyun_c 的 confidence
    # 恒 -1.0, TokenHub 的给 0.98) —— 同名不同服务不能互推。
    # ⚠️ /v1/models 报 status="pre-offline"(控制台也标"模型待下线") —— 现在能用但
    # **不可依赖**, 已排在已验证模型之后, 下线后自动 fallback。
    ("tencent", "deepseek-v3.2"): None,       # 余 486k
    # ── zhipu (uUgu) — model-specific GLM pool, 用完即停 safe (None).
    ("zhipu", "glm-4.5-air"): None,
    ("zhipu", "glm-4.6v"): None,  # VL
    # ── ark (Volcengine 火山方舟) — 每模型 50 万免费额度, 与 DashScope/TokenHub
    # 完全独立。⛔ 计费前提是账号级的「安心体验模式」开启(超额自动暂停, 不计费),
    # Steve 2026-07-30 确认开启; 关掉的话这一段必须全部退出 chain。详见
    # _provider_config 里 "ark" 的注释。
    #
    # 只登记 2026-07-30 实测**两条判据都过**的五个: 餐饮 T3 真实 prompt 5 个问句
    # 全对(conf>=0.6) **且** 每次调用都在 T3 的 5s/provider 预算内。
    # 🔴 两条判据缺一不可: 开着 thinking 时这批模型内容照样 5/5, 但延迟 8-66s
    # —— 对 T3 等于超时。关掉 thinking(见 _ARK_DISABLE_THINKING)后掉到 1.2-5s。
    ("ark", "doubao-seed-2-0-mini-260428"): None,   # 1.2/1.7/2.1s  最快
    ("ark", "deepseek-v4-flash-260425"): None,      # 2.1/2.4/2.5s
    ("ark", "doubao-seed-2-1-pro-260628"): None,    # 2.6/2.8/2.8s
    ("ark", "glm-5-2-260617"): None,                # 3.0/3.5/4.8s
    ("ark", "deepseek-v4-pro-260425"): None,        # 3.8/4.7/4.9s  临界但够
    # ⛔ 实测**不登记**(别再加回来):
    #   doubao-seed-2-0-pro-260215    5/5 但 max 5.1s —— 超 5s 预算
    #   doubao-seed-2-1-turbo-260628  5/5 但 max 5.6s
    #   doubao-seed-2-0-lite-260428   4/5 且 max 6.1s —— 内容和延迟双不合格
    #   glm-4-5-air / qwen3-32b / qwen3-14b / qwen2-5-72b / doubao-smart-router
    #     → 全部 404 InvalidEndpointOrModel.NotFound。它们**在 /api/v3/models
    #       列表里且没有 status=Shutdown**, 但本账号没开通 —— 那个接口列的是
    #       平台全量模型, **不是账号的可调清单**。别拿它当开通凭据。
    #   doubao-seed-evolving          控制台显示"未开通"
}

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
# aliyun_c longest-runway ON models + the never-expiring non-DashScope floor
# (tencent/zhipu 用完即停) so NO slot — including VL — goes fully dark under staleness.
# Fail SAFE, not open.
_MINIMAL_SAFE_SET: frozenset = frozenset({
    ("aliyun_c", "qwen3.7-flash-2026-07-15"),  # 10/23 fast JSON/text
    ("aliyun_b", "qwen3.7-flash-2026-07-15"),  # independent-account fallback
    ("aliyun_c", "qwen3.7-flash"),
    ("aliyun_b", "qwen3.7-flash"),
    ("aliyun_c", "qwen3.7-max-2026-06-08"),   # 09/08 max
    ("aliyun_c", "glm-5.2"),                  # 09/15 quality
    ("aliyun_c", "qwen-plus-latest"),
    ("aliyun_c", "qwen3.6-flash-2026-04-16"),  # 07-23 实测: 替换额度耗尽的 qwen3.5-flash
    ("aliyun_c", "deepseek-v3.1"), ("aliyun_c", "qwen3-vl-plus-2025-12-19"),
    # text floor. 2026-07-30: 原本钉的是 tencent/qwen3.5-flash, 而控制台显示它
    # **已停止且余额 0** —— 也就是说"注册表过期时退守的最小安全集"里的非阿里云地板
    # 指向一个死模型, fail-safe 会 fail 成没有地板。换成实测 5/5 通过的 hy-mt2-pro。
    ("tencent", "hy-mt2-pro"), ("zhipu", "glm-4.5-air"),
    ("zhipu", "glm-4.6v"),                    # VL floor (never expires)
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


# Universal free-text fallback tail (appended to every text slot).
# Rebuilt 2026-06-11 (key rotation incident): keys were rotated to new accounts
# so the per-account free allowlists are stale. The OLD tail hit bare
# `qwen3-max` / `qwen-max` / `qwen-plus` (NOT on the free allowlists = PAID) and
# blew up the bill. EVERY entry below is now on the new free allowlist for its
# account (see reference_dashscope_free_model_allowlist 2026-06-11 section).
# Order: aliyun_c (a736, UNTOUCHED fresh) → aliyun_b (3177, huge free catalog) →
# tencent (free 用完即停) → zhipu (free 用完即停) → aliyun_a (90bc, partially
# consumed — LAST, only its tiny remaining-free list, no low-runway SKUs).
# Shared deep text fallback tail — broadly-capable NON-thinking-only models, EVERY
# entry ∈ _SAFE_MODELS, authored soonest-expiry-first (use-it-or-lose-it) with a
# non-DashScope floor (tencent/zhipu) that survives all aliyun bulk expiries.
# (Fast slots append this; the param layer sets enable_thinking=false so default-ON
# hybrids here don't waste 10-20x latency/tokens.)
_TEXT_TAIL: List[Tuple[str, str]] = [
    # aliyun_a perishable first (07/17-07/23) — burn before it's lost
    ("aliyun_a", "qwen3.6-flash"), ("aliyun_a", "kimi-k2.6"),
    ("aliyun_a", "qwen3.5-plus-2026-04-20"),
    # aliyun_b 07/16 bulk (still has quota)
    ("aliyun_b", "qwen3-max-preview"), ("aliyun_b", "glm-5"),
    ("aliyun_b", "deepseek-v3.2"), ("aliyun_b", "qwen-flash"),
    ("aliyun_b", "qwen3.6-flash-2026-04-16"),
    # aliyun_c 08/13 (fullest) + long runway
    ("aliyun_c", "qwen-plus-latest"), ("aliyun_c", "qwen3-max-preview"),
    ("aliyun_c", "glm-5.1"), ("aliyun_c", "qwen3.5-flash"),
    ("aliyun_c", "deepseek-v3.1"), ("aliyun_c", "qwen3.7-max-2026-06-08"),
    ("aliyun_c", "glm-5.2"),
    # ── non-DashScope floor (independent of aliyun expiries) ──────────────
    # 2026-07-30 晚重测(控制台 ID + 餐饮 T3 真实 prompt + 5 个不同问句)。
    #
    # 🔴 排序判据是**两条**, 不只是"答得对": 餐饮 T3 给每个 provider
    # _SEMANTIC_PROVIDER_TIMEOUT_SECONDS = 5.0s, 整条链
    # _SEMANTIC_TOTAL_TIMEOUT_SECONDS = 12.0s。一个 30 秒答对的模型对这条路径
    # 等于超时, 毫无价值 —— 而且它还会吃掉总预算, 让后面的模型够不到。
    # 所以按「进得了 5s 预算 + 延迟从小到大」排, 不按额度大小排。
    #
    #   hy-mt2-pro              ✅ 5/5, 1.3-1.5s, 余 1M   ← 唯一稳稳够快的, 排头
    #   deepseek-v3.1-terminus  ✅ 5/5, 2.2-2.6s, 余 500k
    #   deepseek-v3.2           ✅ 5/5, 2.3-2.8s, 余 486k ⚠️ 控制台标"待下线";
    #                              仍排在 qwen3.5-plus 前, 因为它快得多, 而真下线
    #                              后是一次**廉价**的 4xx 直接 fallback。
    #   qwen3.5-plus            ⚠️ 5/5 但 3.5-5.7s —— **会碰到/超过 5s 上限**,
    #                              排在快的后面, 当它前面两个都挂了才轮到。
    #   minimax-m2.7            ⚠️ 要 max_tokens>=1600 才有 content, 而抬了之后
    #                              实测 26.7s —— 对 T3 **永远等不到**。留在这里
    #                              只对预算更宽的槽位(CHAT/INSIGHTS)有意义。
    #   glm-5.2                 ❌ 4/5 —— 换成菜品问句就转 thinking 返回空。
    #                              单问句测会误判它可用; 放进链路 = 新毒丸。
    # ⛔ 已移除: qwen3.5-flash / glm-5.1 / deepseek-v4-flash(控制台已停止 + 余额 0),
    #    kimi-k2.6(temperature 约束未验证前不排进来)。
    # 之前这一段的条目**全部不可用**, 也就是说 _TEXT_TAIL 声称的
    # 「independent of aliyun expiries」地板实际是空的 —— 现在它是实的。
    #
    # 两个 provider **交错**排列, 不是 tencent 整段再 ark 整段: 地板的意义是
    # "阿里云全挂了还能答", 如果前几位全是同一个 provider, 那个 provider 一出问题
    # (key 失效 / 账号被停 / 平台故障)地板就又空了。交错之后要连续两家都挂才穿透。
    # 括号里是实测 med 延迟(5s/provider 预算)。
    ("tencent", "hy-mt2-pro"),                   # 1.4s
    ("ark", "doubao-seed-2-0-mini-260428"),      # 1.7s
    ("tencent", "deepseek-v3.1-terminus"),       # 2.4s
    ("ark", "deepseek-v4-flash-260425"),         # 2.4s
    ("tencent", "deepseek-v3.2"),                # 2.6s ⚠️ 待下线
    ("ark", "doubao-seed-2-1-pro-260628"),       # 2.8s
    ("ark", "glm-5-2-260617"),                   # 3.5s
    ("ark", "deepseek-v4-pro-260425"),           # 4.7s 临界
    ("tencent", "qwen3.5-plus"),                 # 5.7s max, 会超 5s
    ("tencent", "minimax-m2.7"),                 # 26.7s, T3 等不到; 宽预算槽位可用
    ("zhipu", "glm-4.5-air"),
]

# VL-only chain — vision models only (no _TEXT_TAIL). aliyun_a has NO confirmed-ON VL
# (screenshot didn't cover VL → toggle unknown → excluded to avoid billing).
_VL_CHAIN: List[Tuple[str, str]] = _dedup_chain([
    ("aliyun_b", "qwen3-vl-plus-2025-12-19"), ("aliyun_b", "qwen-vl-max"),  # 07/16 perishable
    ("aliyun_c", "qwen3-vl-plus-2025-12-19"), ("aliyun_c", "qwen-vl-max"),  # 08/13
    ("aliyun_c", "qwen3-vl-plus"), ("aliyun_c", "qwen3-vl-32b-instruct"),
    ("aliyun_c", "qwen3-vl-flash-2026-01-22"), ("zhipu", "glm-4.6v"),
])

# SLOT_MODELS — capability-tier head (quality-appropriate) + expiry order within tier
# (soonest-expiry account first) + shared tail. Every (account, model) ∈ _SAFE_MODELS
# (CI-enforced). thinking-only models (deepseek-r1/qwen3-*-thinking/kimi-k2.7-code)
# appear ONLY in REASONING. Runtime order is authoritative (no re-sort); _refuse_reason
# drops expired/unsafe entries so heads auto-switch as free grants lapse.
SLOT_MODELS: Dict[SLOT, List[Tuple[str, str]]] = {
    # CHAT — 高频低延迟, thinking off → flash/turbo, perishable first.
    SLOT.CHAT: _dedup_chain([
        ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash-2026-07-15"),
        ("aliyun_c", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-flash"),
        ("aliyun_a", "qwen3.6-flash"), ("aliyun_b", "qwen3.6-flash-2026-04-16"),
        ("aliyun_b", "qwen-flash"), ("aliyun_b", "qwen-turbo"),
        ("aliyun_c", "qwen3.5-flash"), ("aliyun_c", "qwen3.6-flash-2026-04-16"),
        # 2026-07-30: tencent/qwen3.5-flash 控制台已停止且余额 0, 换成实测 5/5 的
        # hy-mt2-pro(余 1M, 1.3-1.5s) —— 这一条是本槽唯一的非阿里云出口。
        ("aliyun_c", "qwen-plus-latest"), ("tencent", "hy-mt2-pro"),
    ] + _TEXT_TAIL),
    # INSIGHTS — 长经营分析优先 Plus（质量/时延平衡），Max 仅作深尾。
    # 2026-07-26 用户逐账户截图确认 A/B/C 的 Plus 与指定版本均有大额免费额度，
    # 且全部开启“免费额度用完即停”。生产 14:55 已证明把三个耗尽 Max 放在
    # 链头会连续 403，既浪费延迟也没有提升质量。当天真实最小探针确认
    # C/B/A Plus 均 200，A qwen3.7-max-2026-06-08 也 200；旧 A
    # qwen3.7-max-2026-05-20 已 403。按独立账户交错，在单账户故障时一次
    # 切换，同时只保留已验证兼容非思考请求的 Max 作为深尾。
    SLOT.INSIGHTS: _dedup_chain([
        ("aliyun_c", "qwen3.7-plus"), ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_a", "qwen3.7-plus"),
        ("aliyun_c", "qwen3.7-plus-2026-05-26"),
        ("aliyun_b", "qwen3.7-plus-2026-05-26"),
        ("aliyun_a", "qwen3.7-plus-2026-05-26"),
        ("aliyun_c", "glm-5.2"), ("aliyun_c", "qwen-plus-latest"),
        ("aliyun_a", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3-max-preview"),
    ] + _TEXT_TAIL),
    # CHART — compact JSON (thinking off + json_object) → flash/coder; NO glm-5 head (60s).
    SLOT.CHART: _dedup_chain([
        ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash-2026-07-15"),
        ("aliyun_c", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-flash"),
        ("aliyun_b", "qwen3.6-flash-2026-04-16"), ("aliyun_b", "qwen-turbo"),
        ("aliyun_c", "qwen3.5-flash"), ("aliyun_c", "qwen3-coder-flash"),
        # 2026-07-30: 同上, qwen3.5-flash 余额 0 → hy-mt2-pro(在 T3 的 JSON 契约
        # prompt 上实测 5/5, 故 JSON 能力有据)。
        ("aliyun_b", "qwen3-coder-flash"), ("tencent", "hy-mt2-pro"),
    ] + _TEXT_TAIL),
    # MAPPER — 字段映射 JSON (thinking off + json_object) → fast text models.
    # 2026-07-26 用户控制台截图确认 B/C 的 versioned Flash 与 alias 均有
    # 100 万免费额度且用完即停；用独立账户交错排列，单账户限流/故障时一次
    # 即切换。链尾只保留 GLM/Plus/Zhipu，不追加通用 _TEXT_TAIL：
    # Max/DeepSeek/Kimi 对短 JSON 分类既慢又浪费，生产已证明会放大超时。
    # 深度经营分析继续由 INSIGHTS/REASONING 槽负责。
    SLOT.MAPPER: _dedup_chain([
        ("aliyun_c", "qwen3.7-flash-2026-07-15"),
        ("aliyun_b", "qwen3.7-flash-2026-07-15"),
        ("aliyun_c", "qwen3.7-flash"),
        ("aliyun_b", "qwen3.7-flash"),
        ("aliyun_c", "glm-5.2"),
        ("aliyun_c", "qwen3.7-plus"),
        ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_a", "qwen3.7-plus"),
        ("zhipu", "glm-4.5-air"),
    ]),
    # REASONING — 深度 (thinking on / thinking-only OK) → deepseek/MoE reasoners.
    SLOT.REASONING: _dedup_chain([
        ("aliyun_c", "deepseek-v3.1"), ("aliyun_b", "deepseek-v3.2"),
        # 控制台服务 ID 是 deepseek-v4-pro-202606; 旧代码写的 `deepseek-v4-pro`
        # 在 TokenHub 上不存在, 所以它恒 402 —— 那个 402 一直被误读成"额度没领",
        # 实际是模型名错。真实余量 999978/1M, 几乎没动过。
        ("aliyun_c", "deepseek-v3.2"), ("tencent", "deepseek-v4-pro-202606"),
        ("aliyun_c", "qwen3-235b-a22b-thinking-2507"), ("aliyun_b", "qwen3.5-397b-a17b"),
        ("aliyun_c", "deepseek-r1"),
    ] + _TEXT_TAIL),
    # VL — 仅视觉链.
    SLOT.VL: _VL_CHAIN,
    # REVIEW — 中文 critique 质量 → verified non-thinking Max/Plus.
    # 05-17/preview Max 强制 enable_thinking=true，与 REVIEW 的低延迟
    # enable_thinking=false 契约冲突；改用 A/C 06-08（实测均兼容）并以
    # 三账户 Plus 收尾，避免每次稳定 400 后才 fallback。
    # 2026-07-30: Max/Plus 六个组合**每天下午必然全部 403 耗尽**(07-27 起稳定
    # 40-57 次/天), 之后 REVIEW 落到 deepseek-v3.2 —— 它给出的餐饮 T3 计划
    # **内容完全正确**(intent/metrics/dimensions/store_scope 全对), 但
    # `confidence` 返回 **-1.0**(不认这个字段)。餐饮 T3 的闸是
    # `confidence < _T3_MIN_CONFIDENCE(0.6) → clarification`, 于是一个 100%
    # 正确的计划被判成「我还缺一个关键信息」, 整条餐饮问答天天下午退化。
    # 实测同一问句: deepseek-v3.2 → confidence=-1.0; qwen3.7-flash → 0.95,
    # 计划内容两者一致。故把仍有额度的 flash 插在 deepseek **之前** ——
    # Max/Plus 有额度时链路不变(它们仍排在最前), 只改耗尽后的落点。
    SLOT.REVIEW: _dedup_chain([
        ("aliyun_a", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3.7-max-2026-06-08"),
        ("aliyun_c", "qwen3.7-plus"), ("aliyun_b", "qwen3.7-plus"),
        ("aliyun_a", "qwen3.7-plus"),
        # 2026-07-30 实测(scripts 见 PR 描述): 对餐饮 T3 真实 prompt 逐模型打分,
        # 判据是「答对 intent 且 confidence >= 0.6」而不是「能否调通」。
        # 下面六个全部实测 ✅(conf 0.95-0.98)。
        ("aliyun_c", "qwen3.7-flash"), ("aliyun_b", "qwen3.7-flash"),
        ("aliyun_c", "glm-5.2"), ("aliyun_c", "qwen3-max-preview"),
        ("aliyun_c", "glm-5.1"), ("aliyun_c", "deepseek-v3.1"),
    ] + _TEXT_TAIL + [
        # ⛔ aliyun_c/deepseek-v3.2 必须排在**整条链最后**, _TEXT_TAIL 之后。
        #
        # 它答对 intent 但 confidence 恒负(-1.0 / -0.95 实测), 而 confidence<0.6
        # 是餐饮 T3 的澄清闸 —— 也就是"内容对、契约不合规"。路由器看到 HTTP 200
        # 就算成功、**不再 fallback**, 于是它成了一颗毒丸。
        #
        # 2026-07-30 早先只把它压到"已验证的 aliyun 模型之后", 但那时它仍在
        # _TEXT_TAIL **之前** —— 阿里云每天下午一耗尽, 链路就停在它身上,
        # 腾讯那层非阿里云地板**结构上永远够不到**, 等于不存在。放到最后之后,
        # 阿里云耗尽 → 走腾讯地板 → 全挂了才落到它(那时它至少还能给个澄清)。
        #
        # 顺带: `_t3_llm_parse` 现在给 call_chain 传 content_validator, 负 confidence
        # 会被判为无效并继续 fallback。两道措施是冗余的, 故意的 —— 排序保证"够得到",
        # validator 保证"就算排序又漂了也不会被它吞掉"。
        ("aliyun_c", "deepseek-v3.2"),
    ]),
}


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

# TokenHub IGNORES enable_thinking=false on these, so they spend the entire
# allowance on `reasoning_content` and return an EMPTY `content` — the router then
# logs "output invalid (empty)" and falls back, i.e. they look broken when they are
# merely starved. Measured at max_tokens=500: finish_reason='length',
# completion_tokens_details.reasoning_tokens=500, content=''.
# 1600 is what made minimax answer in the probe; it is a FLOOR, never a cap.
_TOKENHUB_MIN_MAX_TOKENS: Dict[str, int] = {
    "minimax-m2.7": 1600,
    "minimax-m2.5": 1600,
}

# Ark expresses "do not think" with its OWN field — `thinking: {"type": "disabled"}`
# — not DashScope's `enable_thinking`. This is not a micro-optimisation: with
# thinking left on, Ark answers the T3 prompt CORRECTLY (5/5) but in 8-66s, which
# is a timeout against the caller's 5s-per-provider budget. Switching it off took
# the same models to 1.2-5.0s and made five of them viable. Measured 2026-07-30 on
# all 8 reachable Ark models — none rejected the field.
_ARK_DISABLE_THINKING: Dict[str, Any] = {"type": "disabled"}


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

    ⚠️ 429 is DELIBERATELY NOT here (gap-audit correctness fix). A 429 is a TRANSIENT
    burst rate-limit, not a spent monthly free grant — treating it as quota-exhausted
    sidelined a healthy quota-rich model for 6h on a load spike. 429 now falls through
    the 'other errors' path → circuit-breaker SHORT cooldown (CB_COOLDOWN) only, no 6h
    quota-skip. Only 403 FreeTierOnly / 402 grant-exhaustion warrant the long skip."""
    if status_code == 403:
        return "FreeTierOnly" in body_text or "AllocationQuota" in body_text
    lowered_body = body_text.lower()
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
    # No runtime re-sort: SLOT_MODELS order is authoritative (quality tier + expiry).
    # _refuse_reason drops expired/unsafe entries so heads auto-switch as grants lapse.
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
    # No runtime re-sort: SLOT_MODELS order authoritative (see call_chain).
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
