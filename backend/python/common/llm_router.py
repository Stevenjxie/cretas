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
_REGISTRY_AUDIT_DATE = datetime.date(2026, 8, 13)  # 08-13 全量重审: 30 条删 21 增 11
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
    # ── 上海电信 AI Store (owner 2026-08-13 confirmed 25M quota points and
    #    automatic stop on exhaustion; synthetic live probes passed) ──
    # The account is valid for about one month.  We deliberately hard-stop on
    # 2026-09-13 even if the upstream would continue serving: a conservative
    # availability cutoff is safer than assuming that an expired grant still
    # has the same billing boundary.
    ("aistore", "DeepSeek-V4-Flash-A"): _d(2026, 9, 13),
    ("aistore", "Qwen3-235B-A22B"): _d(2026, 9, 13),
    ("aistore", "Qwen3-32B"): _d(2026, 9, 13),

    # ── DeepSeek 官方 (2026-08-15 加, 接 aistore 9-13 到期的悬崖) ──────
    # 🔴 这两条的日期语义与上面 aistore 那三条**完全不同**, 不要照着读:
    #
    #   aistore 的 2026-09-13 = **真的额度到期**, 那天之后再调就是越界。
    #   下面的 2026-11-15     = **我们自己设的强制复审点**。按量付费本身
    #                           没有到期日, 这个日期的作用是「到那天回来看
    #                           一眼实际花了多少、还要不要留」。
    #
    # ⚠️ 不写清这一条, 下一个人会以为 DeepSeek 也会在那天断掉, 于是要么提前
    #    做一次不必要的迁移, 要么在它「过期」时以为线上出了故障。
    # 从 2026-08-15 起算三个月 —— 早一点复审比晚一点安全。
    ("deepseek", "deepseek-v4-flash"): _d(2026, 11, 15),
    ("deepseek", "deepseek-v4-pro"): _d(2026, 11, 15),

    # ══ 2026-08-13 全量重审 ════════════════════════════════════════
    # 判据不变: 控制台显示有余量 ∩ 生产探针非空 content。单边证据一律不收 ——
    # 探针 200 但控制台无余量的最危险: 那说明「免费额度用完即停」没覆盖它,
    # 那个 200 可能是真在计费。顺序无意义, 链顺序由 _build_chain 算。
    #
    # 🔴 昨天(08-12)双证通过的 30 条, 今天实测 **21 条已死**(403/402/429), 其中
    #    包括 _MINIMAL_SAFE_SET 的三根支柱 qwen3.8-max —— 它到期日 11/01、三账号
    #    各 100 万额度, 是全表跑道最长的条目。
    #    判据: **到期日只说「什么时候一定没」, 不说「今天还有没有」。** 跑道长
    #    不等于明天还在; 存活性必须每轮实测, 不能从到期日推。
    #
    # 🔴 更该记的是**地板自己死在地板位上**: _TEXT_TAIL 排最前的两条快地板
    #      ark/deepseek-v4-flash-ga-260731 → 429 TooManyRequests
    #      tencent/hy3                     → 402 gateway_error (Payment Required)
    #    它们昨天刚以「0.8s / 1.0s 快地板」的身份加进来, 24 小时后双双死亡。
    #    地板排在链尾, 只有前面全挂时才会被走到 —— 所以它坏了最不容易被发现,
    #    而它坏的那天恰恰就是最需要它的那天。**地板的存活性要和池内候选一样每轮量。**
    #
    # 📌 owner 三张控制台截图(A 4 / B 2 / C 4)与探针**零分歧**: 截图外 21 条
    #    全部非 200, 截图内 12 条全部 200。截图视觉上是滚动截断的片段, 但结论
    #    不靠「截图完不完整」—— 靠逐条打过。仪器是探针, 截图只是准入的另一半。

    # ── aliyun_a (控制台 4 个有额度, 全部通过探针) ──
    ("aliyun_a", "qwen3.5-ocr"): _d(2026, 9, 14),              # 999,522/100万
    ("aliyun_a", "kimi-k2.7-code"): _d(2026, 9, 14),           # 580,047/100万
    ("aliyun_a", "qwen3.7-max-2026-05-17"): _d(2026, 8, 24),   # 605,592/100万
    ("aliyun_a", "qwen3.7-max-preview"): _d(2026, 8, 24),      # 506,137/100万

    # ── aliyun_b (控制台 2 个有额度, 全部通过探针) ──
    ("aliyun_b", "qwen3.5-ocr"): _d(2026, 9, 14),              # 999,622/100万
    ("aliyun_b", "kimi-k2.7-code"): _d(2026, 9, 14),           # 874,931/100万

    # ── aliyun_c (控制台 4 个有额度, 3 个通过探针) ──
    ("aliyun_c", "qwen3.5-ocr"): _d(2026, 9, 14),              # 999,618/100万
    ("aliyun_c", "kimi-k2.7-code"): _d(2026, 9, 14),           # 377,603/100万
    ("aliyun_c", "qwen3.7-max-preview"): _d(2026, 8, 24),      # 702,349/100万
    # ⛔ ("aliyun_c", "deepseek-v4-flash-0731") **不收**: 控制台写着剩 479,703、
    #    到期 10/31, 而探针两种写法(-0731 / 不带日期)都是 403 FreeTierOnly。
    #    控制台与运行时打架时按**单边证据不收**处理 —— 这正是判据要求双证的
    #    场景, 不是「控制台更权威所以加上」。同型号在 a/b 上今天也是 403, 已一并删除。

    # ══ 非 DashScope (无到期日 → _expiry_of 返回 _FAR_FUTURE → 必然排最后) ══
    #
    # tencent: owner 2026-08-13 控制台 14 个服务 ID + 账号级「用完即停」ON。
    #   ⚠️ 我第一次是拿 GET /models 当清单的 —— 它返回 **102** 个, 而控制台只有
    #      14 个。判据: **接口目录 ≠ 账号权益**。这是形态 A 打在我自己的仪器上:
    #      我想量「这个账号免费能用哪些」, 实际量的是「这个接口对外宣称有哪些」。
    #   14 个里 9 个产出正文(len≥8), 5 个(kimi-k3 / glm-5 / glm-5-turbo /
    #   kimi-k2.6 / glm-5v-turbo)在 max_tokens=200 下 content 恒空 —— 推理 token
    #   把预算吃光, 走到生产的 outcome validation(_MIN_TEXT_LEN=8)一样会被判失败。
    ("tencent", "deepseek-v4-flash-202605"): None,    # 1.7s len=51 ← 跨轮稳定的快地板
    ("tencent", "kimi-k2.7-code-highspeed"): None,    # 1.2s len=53
    ("tencent", "kimi-k2.7-code"): None,              # 2.8s len=45
    ("tencent", "minimax-m2.7"): None,                # 4.9s, 既有条目
    ("tencent", "mimo-v2.5-pro"): None,               # 10.2s len=63, 慢, 不进交互池
    # 下面 4 个是**特化 SKU**(hy-mt2=机器翻译, hy-role/hunyuan-role=角色扮演),
    # 只因为它们是目前**仅有**的亚秒级活口才收进白名单。
    # ⛔ 收进白名单 ≠ 放进池: 它们不进任何 _SLOT_POOLS。要进得先跑
    #    llm_capability_rank 拿到契约通过率 —— 「一个模型在 A 槽表现好不构成把它
    #    放进 B 槽的理由」(本文件 _SLOT_POOLS.MAPPER 处的旧判据), 何况这里连 A 槽都没测过。
    ("tencent", "hunyuan-role-latest"): None,         # 0.68s len=30
    ("tencent", "hy-mt2-lite"): None,                 # 0.73s len=39
    ("tencent", "hy-role"): None,                     # 0.80s len=29
    ("tencent", "hy-mt2-plus"): None,                 # 0.81s len=41
    # ⛔ ("tencent", "hy3") 删除 08-13: **402 gateway_error**。402 是 Payment
    #    Required —— 在一个「用完即停」的账号上出现付费类状态码, 比 403 更该警觉。

    # ark (火山方舟): owner 2026-08-13 确认账号级「安心体验」= 用完即停不扣费。
    #   ⚠️ 控制台列表给的是**显示名**(Doubao-Seed-2.0-pro), API 要的是**带日期的 id**
    #      (doubao-seed-2-0-pro-260215)。两者之间没有可推导的规则 —— 我按显示名猜了
    #      17 个只中 3 个, 后来 owner 直接从文档页拷来 18 个官方 id 才定下来。
    #      判据: **显示名不是标识符**。下次控制台加模型, 去文档页拿 id, 不要拼。
    #   📌 官方 id 逐条实打的结果: 18 条里**只有 3 条可调**。其中两条的错误码是
    #      `ModelNotOpen`(本账号未开通), 其余 404 的原文是 "does not exist **or you
    #      do not have access to it**" —— 对一个已知有效的 id, 404 = 无权益不是 id 写错。
    ("ark", "doubao-seed-2-0-code-preview-260215"): None,  # 6/6  4.4s
    # ⛔ 另外两个可调的 ark 模型都**不收**:
    #    doubao-seed-2-0-pro-260215      —— 已在 _ARK_CONTRACT_REJECTED(前人实测:
    #      AOV 计划返 intent=null/confidence=0.3), 且本轮 llm_capability_rank
    #      量到 **45.5s** —— 没有任何槽的契约容得下。
    #    doubao-seed-character-260628    —— 角色扮演 SKU, 0.96s 快但**没跑过契约**。
    #      同日教训: tencent/kimi-k2.7-code-highspeed 也是「探针看着很快很好」,
    #      走生产管线 0/6 全 http400。快 ≠ 能用。
    # ⛔ ("ark", "deepseek-v4-flash-ga-260731") 删除 08-13: **429 TooManyRequests**。

    # zhipu: 既有地板, 钉在 _TEXT_TAIL 最后一位(08-09 事故结论, 见那里)。
    #   ⚠️ 今天两轮读数打架: 3.6s len=48 / 4.2s len=0。抖, 但它本来就在最后一位,
    #      只有前面全挂才会走到 —— 保留, 不因单轮空读数移除(移除要稳定证据)。
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
    # AI Store's account-level auto-stop was explicitly confirmed by the owner;
    # these remain admissible when the older shared registry becomes stale, but
    # _refuse_reason still hard-drops all three at their 2026-09-13 expiry.
    ("aistore", "DeepSeek-V4-Flash-A"),
    ("aistore", "Qwen3-235B-A22B"),
    ("aistore", "Qwen3-32B"),
    # ── DeepSeek 官方 (2026-08-15) ────────────────────────────────────
    # 🔴 只加进 `_SAFE_MODELS` 是**不够的**, 这一条是实测出来的:
    #
    #   `_REGISTRY_AUDIT_DATE(2026-08-13) + _REGISTRY_MAX_AGE_DAYS(21)`
    #   ⇒ **2026-09-04** 起 registry 超龄, `_refuse_reason` 把不在本集合里的
    #     一律判 "registry_stale"。实测:
    #        只加 _SAFE_MODELS  -> _refuse_reason(...) == 'registry_stale'
    #        同时加入本集合      -> _refuse_reason(...) is None
    #   ⇒ 不加进来的话, 它在 **09-04** 就死了 —— 比它要接的 9-13 悬崖**早 9 天**,
    #     恰好在最需要它的那天是不可用的。
    #
    # ⛔ 否决过的替代方案: 「复审时 bump _REGISTRY_AUDIT_DATE」。那是 21 天一次
    #    的手工续期, 而本集合的注释白纸黑字写着「它只在 registry 超龄时才被用到,
    #    所以它坏了不会有任何日常信号报出来」。**依赖「有人记得」的兜底不是兜底。**
    #
    # ⚠️ 本集合当前健康度已单独立项(见 smartbi/scripts/minimal_safe_set_liveness.py):
    #    2026-08-15 实测 8 条里 2 条死于配额、3 条中位超单跳预算; 扣掉 9-13 过期的
    #    aistore 三条后, 既活着又在 6.0s 内的只剩 zhipu 一条。加这两条正是补上
    #    这个集合眼下最缺的东西: 一条**快的、跑道长的**。
    ("deepseek", "deepseek-v4-flash"),
    ("deepseek", "deepseek-v4-pro"),
    # 2026-08-13 重建。上一版(08-09 建)的9 个条目今天实测 **6 个已死** ——
    # 包括它自己的三根支柱 qwen3.8-max(三账号各 100 万、到期 11/01)。
    #
    # 🔴 这是同一个陷阱第二次: 08-09 重建时的原话是「旧集合 13 个条目里 8 个
    #    已实测死亡 —— fail-safe 退守的目标本身是死的」。当时的修法是「只收跑道
    #    最长 + 当天探针通过的」—— 而「跑道最长」恰恰是今天全部死掉的那批。
    #    判据: **fail-safe 集合的存活性要和主池一起量, 每轮都要**。它只在
    #    registry 超龄时才被用到, 所以它坏了不会有任何日常信号报出来。
    ("tencent", "deepseek-v4-flash-202605"),    # 1.7s, 跨多轮稳定
    ("ark", "doubao-seed-2-0-code-preview-260215"),  # 3.5s
    ("tencent", "minimax-m2.7"), ("zhipu", "glm-4.5-air"),   # 既有文本地板
    ("aliyun_c", "kimi-k2.7-code"),             # 1.8s, aliyun 侧仅存的通用文本模型
})


def _expiry_of(account: str, model: str) -> datetime.date:
    """Sort key: the (account, model)'s free-grant expiry; _FAR_FUTURE if unknown/None
    (tencent/zhipu). Used to order chains soonest-expiry-first WITHIN a quality tier."""
    exp = _SAFE_MODELS.get((account, model), _FAR_FUTURE)
    return exp if exp is not None else _FAR_FUTURE


# ═══════════════════════════════════════════════════════════════════════════
# 实测能力表 —— `_build_chain` 的**主**排序键 (owner 2026-08-10 拍板「按能力排」)
#
# 产出方式: `python -m smartbi.scripts.llm_capability_rank --slot review --emit-table`
#   · prompt 同源: 生产自己的 `_build_t3_prompt`
#   · 判据同源: 生产自己的 `_t3_contract_violation` (call_chain 真正用来接受/
#     拒绝一次应答的那个函数), ⛔ 没有手写答案表
#   · 题目同源: 回归电池 CASES 里每条链的首问 + 无上下文依赖的单问, 选法算出来
#
# ⚠️ **这张表能区分什么, 不能区分什么 —— 先读这段再用它下结论。**
#    2026-08-10 首次实测 19 个 REVIEW 候选: **18 个 6/6 满分**。也就是说
#    「契约合格」是一道**地板题**, 不是能力题 —— 它区分不出模型强弱, 只能区分
#    「能不能用」。所以本表实际排出来的是: 达标 → 快 → 快到期先用。
#    ⛔ 不要因为某个模型在这里 6/6 就说它"能力强"; 它只说明它没被链拒绝。
#    要真正区分强弱, 需要一道会让好模型和更好的模型分开的题 —— 现在没有,
#    这是**已知缺口**, 写在这里以免下一个人把满分读成能力证明。
#
# 🔴 那次实测唯一真正被区分出来的事: `aliyun_c/deepseek-v3.2` **0/6 全 403 quota**,
#    而按旧的纯到期日排序它正好是 REVIEW 链的**链头** —— 每次 REVIEW 调用都先
#    在一个已耗尽的模型上撞一跳。这就是"按到期日排"最贵的失败形状: 排序键
#    对"这个模型今天还活着吗"完全沉默。
#
# 值 = (契约通过率, 中位延迟秒)。REVIEW/MAPPER 两槽分别测过, 同一 (账号,模型)
# 跨槽 p50 差 ≤0.1s, 故合成一张表; 未在此表中的条目 = 没测过, 见 `_capability_tier`。
_CAPABILITY_MEASURED_AT = datetime.date(2026, 8, 13)
_CAPABILITY_MAX_AGE_DAYS = 21   # 超龄 → 忽略本表, 退回纯到期日排序(旧行为)
_CAPABILITY_PASS_FLOOR = 0.5

# 2026-08-12 重测(同一脚本、同一 slot、同一 6 道电池真题, 22 个候选全覆盖 ——
# 这次把 _TEXT_TAIL 的地板也测进来了, 上一版只测了池内候选, 于是地板三条一直
# 「没测过」躺在 band 1)。
#
# ⚠️ **这张表能说什么, 不能说什么** —— 达标的 14 个**全是 6/6**。契约仍然是
#    地板题, 它测的是「能不能用」, 不是「谁更会规划」。⛔ 不要因为某条排在前面
#    就说它能力强 —— 排序里真正由本表决定的只有两件事: 达不达标(0.0 沉底) 和
#    落在哪个延迟档。
#
# 🔴 两轮同源读数暴露出一个真信号: **kimi-k2.7-code 是抖的, 不是慢的**。
#    同一晚两轮: aliyun_c 5.8s → 9.2s, aliyun_a 5.8s → 7.8s, 而同轮里
#    qwen3.7-flash-2026-07-15 是 1.7 → 1.5、qwen3.6-plus 是 4.0 → 4.1 (±0.1)。
#    08-10 记的 12.9/16.1/19.2 与今天任一轮都对不上。抖的候选放在链头最坏 ——
#    但**不需要手写黑名单**: 单跳预算 6s 一上它自己落进 band 3 沉底。
#    (owner 2026-08-12: 「别用 kimi k2.7 code」。判据成立, 只是理由是「不稳」
#     而不是「慢」。)
#
# 📌 zhipu/glm-4.5-air 今天 6/6 1.8s, 是地板里最快的 —— 但**不据此把它前移**:
#    它被钉在 _TEXT_TAIL 最后一位是 08-09 一次生产事故的结论(某些槽稳定超时,
#    连累后面 4 个健康候选被熔断)。一次好读数推翻不了那条判据。
_CAPABILITY: Dict[Tuple[str, str], Tuple[float, float]] = {
    # Shanghai Telecom AI Store, 2026-08-13.  Measured through the production
    # normalize -> slot-params -> T3 contract path, six real restaurant cases.
    ("aistore", "DeepSeek-V4-Flash-A"): (1.0, 3.0),
    ("aistore", "Qwen3-235B-A22B"): (1.0, 3.5),
    # ── 2026-08-13 重测: `python -m smartbi.scripts.llm_capability_rank
    #    --slot review` + `--slot reasoning`, 生产凭证、生产 prompt、生产判据 ──
    #
    # 🔴 本轮最贵的一条读数: **tencent 的 kimi-k2.7-code 与 -highspeed 都是 0/6
    #    全 http400**。而我自己写的探针给它们的读数是「1.2s / 2.8s, 正文 45-53 字,
    #    完全正常」。差别在于我的探针是**自己拼的 payload**, 没经过生产的
    #    normalize → _apply_slot_params 两步管线。
    #    ⚠️ 这个坑本文件 08-12 那段注释里已经写过一模一样的一次(「我第一版自己拼
    #       payload 的探针漏了 _apply_slot_params, 把 tencent/deepseek-v4-flash-202605
    #       误判成 empty」)。**写在注释里的教训没能拦住下一个人**, 所以再写一遍判据:
    #       ⛔ **判断一个候选能不能用, 只认走生产管线的探针。** 手拼 payload 的读数
    #          既会造假阴性(把好的判死), 也会造假阳性(把 400 的判活) —— 后者更贵,
    #          因为它会被排进链头。
    ("tencent", "deepseek-v4-flash-202605"): (1.0, 1.9),
    ("ark", "doubao-seed-2-0-code-preview-260215"): (1.0, 4.4),
    ("aliyun_a", "qwen3.7-max-2026-05-17"): (1.0, 7.3),
    ("aliyun_a", "qwen3.7-max-preview"): (1.0, 8.7),
    ("aliyun_a", "kimi-k2.7-code"): (1.0, 10.3),
    ("aliyun_b", "kimi-k2.7-code"): (1.0, 13.6),
    ("aliyun_c", "kimi-k2.7-code"): (1.0, 13.6),
    ("tencent", "mimo-v2.5-pro"): (1.0, 24.7),
    ("ark", "doubao-seed-2-0-pro-260215"): (1.0, 45.5),
    # ── 0/6 全 http400 —— 不是慢, 是生产管线直接被拒。达标线 0.5 之下 → 沉底 ──
    ("tencent", "kimi-k2.7-code-highspeed"): (0.0, 0.5),
    ("tencent", "kimi-k2.7-code"): (0.0, 0.5),
    # ── 08-12 读数, 本轮**未重测** ──────────────────────────────────────────
    # 排名脚本只跑 `_SLOT_POOLS` 里的候选, 不跑 `_TEXT_TAIL` 的地板。这两条是地板,
    # 所以它们的数还是 08-12 的。📌 这本身是个缺口: 地板恰恰是「前面全挂时唯一
    # 还能答的那个」, 它的读数却是最少被刷新的。08-13 已经实测到两条地板
    # (ark/deepseek-v4-flash-ga-260731 与 tencent/hy3)在 24 小时内死亡。
    ("tencent", "minimax-m2.7"): (1.0, 15.0),
    ("zhipu", "glm-4.5-air"): (1.0, 1.8),
}


# ═══════════════════════════════════════════════════════════════════════════
# 计划合法性 —— 「按能力排」真正用得上的那把尺子 (2026-08-10 实测)
#
# 值 = 在 7 道真实电池问句上, 模型**编造提示词里没有的枚举值**的处数。
# 产出: `python -m smartbi.scripts.llm_capability_rank --schema --slot review`
#       (合法值从 `_build_t3_prompt` 现解析, ⛔ 不在任何地方另写一份枚举)
#
# 🔑 为什么是这把尺子, 前两把为什么不行:
#   · 契约合格率: 19 个候选 18 个满分 —— 地板题。照它排序会退化成纯延迟升序,
#     实测把电池从 83 打到 61(见 _build_chain docstring 里的自我推翻)。
#   · 与参照模型比对: 参照自己会因额度烧完而死, 死了就全 0。
#   本表判的不是「答得对不对」(那要人猜标准答案), 而是「有没有编说明书里没有的
#   值」—— 客观, 且直接对应下游失败: 确定性代码只认枚举内的值。
#
# 🔴 端到端佐证: `qwen3.5-plus` 在「下周需要多少兼职」上编 `named="next_week"`;
#    它当链头的三轮电池里 [51] **三轮全挂**, 而 glm-4.6 当链头的三轮**一次没挂**。
#
# ⚠️ 单样本噪声: 同一模型在不同账号上打分不完全一致(qwen3.7-max-2026-05-17
#    在 a 上 1 处、b/c 上 0 处)。**别把它当精确排名** —— 它只可靠区分
#    「稳定零越界」与「会编枚举」两档, 所以下面的 `_plan_schema_tier` 也只分档。
_PLAN_SCHEMA_MEASURED_AT = datetime.date(2026, 8, 10)
_PLAN_SCHEMA_VIOLATIONS: Dict[Tuple[str, str], int] = {
    # Same-day seven-case schema probe.  DeepSeek stayed within the prompt's
    # enums; Qwen invented yesterday + next_week, so it is not admitted to the
    # semantic MAPPER/REVIEW pools despite passing the basic JSON contract.
    ("aistore", "DeepSeek-V4-Flash-A"): 0,
    ("aistore", "Qwen3-235B-A22B"): 2,
    ("aliyun_a", "qwen3.7-flash"): 0,
    ("aliyun_a", "qwen3.8-max"): 0,
    ("aliyun_b", "qwen3.8-max"): 0,
    ("aliyun_c", "qwen3.8-max"): 0,
    ("aliyun_b", "qwen3.7-max-2026-05-17"): 0,
    ("aliyun_c", "qwen3.7-max-2026-05-17"): 0,
    ("aliyun_c", "qwen3.7-max-2026-05-20"): 0,
    ("aliyun_a", "kimi-k2.7-code"): 0,
    ("aliyun_c", "kimi-k2.7-code"): 0,
    ("aliyun_a", "deepseek-v4-flash-0731"): 1,   # 编 "tomorrow"
    ("aliyun_b", "deepseek-v4-flash-0731"): 1,   # 编 "tomorrow"
    ("aliyun_a", "qwen3.7-flash-2026-07-15"): 1, # 编 "tomorrow"
    ("aliyun_a", "qwen3.7-max-2026-05-17"): 1,   # 编 "next_week"
    ("aliyun_b", "kimi-k2.7-code"): 1,           # 编 "next_week"
    ("aliyun_c", "qwen3.6-plus-2026-04-02"): 2,  # 编 "yesterday" + "tomorrow"
}


# 单跳延迟预算 + 延迟档 —— **粗档参与排序, 不作连续键**。
#
# 🔴 连续键 vs 粗档, 这个区别就是 2026-08-10 那次回归的全部内容: 把中位延迟当
#    **连续键**, 在「上一位全平手」时会退化成「最小最快的模型排最前」(实测把
#    回归电池从 83 打到 61)。粗档不会 —— 1.7s / 2.7s / 2.8s 同属一档, 它们之间
#    仍由到期日决定先后, 只有跨档(2.8s vs 19.2s)才重排。
#
# ⚠️ owner 2026-08-10 拍板把预算从 10s 放宽到 25s: 实测零越界的 kimi-k2.7-code
#    (12.9~19.2s)本来被 10s 上界整个挡在外面, 而**能答对但慢**好过**答不出来**。
#    定 20s 而不是更大: 总预算必须放得下两次满额尝试(见 test_semantic_planner_budget
#    的 test_total_budget_leaves_room_for_a_second_attempt), 20×2=40 ≤ 45 总预算,
#    而 45 又在 nginx 默认 60s 之下留了 15s 余量。⚠️ kimi-k2.7-code 在 aliyun_c 上
#    实测 19.2s, 正好卡在这条线上 —— 它是「勉强够得着」而不是「宽裕」。
#    放宽前逐层核对过上游没有更短的闸: Java `RestaurantAgentRuntimeClient`
#    是 `readTimeout(0)`(不限), 前端 75~180s, nginx 默认 60s —— Python 这 25s
#    原本就是瓶颈, 放到 25/45 仍在 nginx 之下。
#
# ⛔ 但放宽**不等于**让慢模型往前排: 一个 19.2s 的模型排在 2.8s 的前面, 只会让
#    降级路径上的用户多等 16 秒。所以 `_latency_band` 把「快」单独分一档。
# 2026-08-12: 20.0 → 6.0。上面那段把 20 的理由写成「kimi-k2.7-code 实测 19.2s,
# 正好卡在这条线上」—— 也就是**整条链的预算是被一个候选撑起来的**。当天重测:
# kimi 在 c/a 上是 5.8s、b 上 10.2s(08-10 记的 12.9/16.1/19.2 已过期), 而池子里
# 现在有 1.7s / 1.8s / 1.8s 的 6/6 候选。为了等一个抖到 20s 的候选让所有人等 45s,
# 这笔账不成立。
# ⛔ 这个数同时是 `_latency_band` 的 2/3 分界(实测可接受 vs 实测必然超时), 所以
#    砍它会连带改链顺序 —— 改完必须看 golden 快照的 diff, 那正是上一次发现
#    「新加的快模型被排到链尾」的方式。
_SLOT_HOP_BUDGET_SECONDS = 6.0     # 单跳预算; restaurant_intent 直接 import 它,
                                   # 不另写一份(两份必漂, 漂了就是「排序按 A 算、
                                   # 真超时按 B 算」)
_FAST_LATENCY_SECONDS = 5.0        # 「快」档门槛


def _latency_band(account: str, model: str) -> int:
    """0 = 实测快(≤5s), 1 = 没测过, 2 = 实测可接受(≤单跳预算), 3 = 实测必然超时。

    「没测过」照例排在「实测好」与「实测差」之间 —— 缺席不构成任何一侧的证据。
    """
    measured = _CAPABILITY.get((account, model))
    if measured is None:
        return 1
    p50 = measured[1]
    if p50 <= _FAST_LATENCY_SECONDS:
        return 0
    return 2 if p50 <= _SLOT_HOP_BUDGET_SECONDS else 3


def _plan_schema_tier(account: str, model: str) -> int:
    """0 = 实测零越界, 1 = **没测过**, 2 = 实测有越界。

    与 `_capability_tier` 同一条纪律: 没测过的既不提前也不沉底 —— 它没有证据
    支持任何一边, 把「缺席」折叠进任何一侧都是拿沉默当证据。
    """
    got = _PLAN_SCHEMA_VIOLATIONS.get((account, model))
    if got is None:
        return 1
    return 0 if got == 0 else 2


def _capability_stale(today: Optional[datetime.date] = None) -> bool:
    """能力表超龄 → 排序退回纯到期日(旧行为), 而不是拿一份陈旧读数硬排。"""
    today = today or _today()
    return (today - _CAPABILITY_MEASURED_AT).days > _CAPABILITY_MAX_AGE_DAYS


def _capability_tier(account: str, model: str) -> int:
    """0 = 实测达标, 1 = **没测过**, 2 = 实测不达标。

    没测过的排在达标之后、不达标之前 —— 它既没有证据支持提前, 也没有证据
    支持沉底。⛔ 不要把"没测过"折叠进任何一边: 那正是"缺席的证据被当成证据"
    (memory: feedback_omission_disguises_ambiguity_as_certainty)。
    """
    measured = _CAPABILITY.get((account, model))
    if measured is None:
        return 1
    return 0 if measured[0] >= _CAPABILITY_PASS_FLOOR else 2


# ⛔ 这里**刻意没有** `_capability_latency()` 排序键。第一版有, 它把链头从
#    glm-4.6 换成了 qwen3-next-80b(更快更小), 回归电池同版本两轮逐条相同的
#    61/85(改动前 80/85)。理由见 `_build_chain` docstring 里那段自我推翻:
#    契约是地板题, 18/19 满分并列 → 排序退化成纯延迟升序 = 最小最快的排最前。
#    延迟是**约束**不是**能力代理**; `_CAPABILITY` 里仍然存着 p50, 但它只用来
#    给人看(llm_capability_rank 的报表会标出超预算的候选), 不参与排序。


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
    SIMPLE_TEXT = "simple_text"
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
    # _build_chain 的排序必然把它们放在所有 aliyun 条目之后。
    #
    # ⛔ zhipu 必须留在**最后一位** —— 这条来自 2026-08-09 的一次生产事故(PR #2411):
    #    zhipu 在某些槽上会稳定超时, 而链是**串行且共享同一个总预算**。它排在前面
    #    会把预算吃光, 后面本来 0.5-1.1s 就能答的候选**因为分不到时间而跟着超时**,
    #    连续 2 次即被熔断 60 秒。当天实测有 4 个健康候选被这样连坐熔断。
    #    判据: **把一个会超时的候选排在前面, 等于把它后面的健康候选一起拖下水。**
    #    这几条到期日都是 None, 稳定排序原样保留书写顺序 —— 顺序由这几行决定。
    #
    # ── 2026-08-13 重建 ─────────────────────────────────────────
    # 上一版的三条快地板里有**两条今天死了**: ark/deepseek-v4-flash-ga-260731 (429)
    # 与 tencent/hy3 (402)。它们是 08-12 刚以「0.8s / 1.0s」加进来的。
    # 现在这三条均为 2026-08-13 实测(len≥8 且跨轮一致):
    ("tencent", "deepseek-v4-flash-202605"),          # 1.7s len=51
    ("ark", "doubao-seed-2-0-code-preview-260215"),   # 3.5s len=46
    ("tencent", "minimax-m2.7"),                      # 4.9s 慢地板
    ("zhipu", "glm-4.5-air"),                         # 钉死在最后一位, 见上
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
    "qwen3.7-max-preview",             # 6.0-8.5s (2026-08-13 aliyun_c 实测 67s)
    "minimax-m2.7",                    # 6.7s (地板, 见上)
    # ── 2026-08-13 新增, 均为当天实测 ──
    "mimo-v2.5-pro",                   # 10.2s (tencent)
    "doubao-seed-2-0-pro-260215",      # 12.5s (ark)
})

# 开思考会返回空 content 或极慢 → 只进 profile 里 enable_thinking=false 的槽。
# 2026-08-09 实测: glm-4.6 推理档 44s / qwen3.6-plus-2026-04-02 17.8s /
# qwen3.5-plus-2026-02-15 21.1s (关思考档全部 ~1s)。
_THINKING_OFF_ONLY: frozenset = frozenset({
    "glm-4.6", "qwen3.6-plus-2026-04-02", "qwen3.5-plus-2026-02-15",
    # 2026-08-12: 不带日期的 qwen3.6-plus 与上面那个快照是同一模型的两个计费条目,
    # 开思考的病同源(快照实测 17.8s), 归同一档 —— 不等它在生产上先慢一次再补。
    "qwen3.6-plus",
})

# 关思考会 400 → 只能进 REASONING(其 profile 为 {}, 不设 enable_thinking)。
# 2026-08-09 实测 aliyun_c/MiniMax-M2.5: 关思考 400, 开思考 3.6s OK。
_REASONING_ONLY: frozenset = frozenset({"MiniMax-M2.5"})


# ══ 候选池 ══════════════════════════════════════════════════════════════
# INSIGHTS 与 REVIEW 共用同一个质量档池: 两者判据逐字相同(质量优先 + 关思考档
# ≤4s), 各写一份 21 行迟早漂移成两张不一致的表。将来若真分化(例如 REVIEW 需要
# 更强的多轮上下文继承能力, 见 2026-08-09 的判别实验), 再从这里拆开。
_QUALITY_TIER_POOL: List[Tuple[str, str]] = [
    # AI Store quota expires first and is explicitly use-it-or-lose-it.  The A
    # DeepSeek endpoint is the restaurant-quality head.  Qwen 235B passed the
    # basic JSON contract but invented two time enums in the seven-case schema
    # probe, so it is deliberately absent from this semantic quality pool.
    ("aistore", "DeepSeek-V4-Flash-A"),
    # 🔴 2026-08-13 清空重建: 上一版 13 个条目**今天实测全部 403**(见 _SAFE_MODELS
    #    段落)。不是"淘汰几个", 是这个池一个活口都不剩 —— aliyun 三个账号今天只剩
    #    4 类模型有额度, 其中 qwen3.5-ocr 是 OCR SKU、另外三个都是 _THINKING_ONLY,
    #    没有一个能进"关思考 ≤4s"的质量池。
    #
    # ⚠️ 于是本池现在**全部是非 DashScope 的条目**。这是事实, 不是选择:
    #    质量档 = 关思考、≤4s、通用文本。今天满足这三条的只有这两个。
    #
    # ⛔ tencent/kimi-k2.7-code-highspeed 一度进过本池, 同日移出:
    #    llm_capability_rank 实测 **0/6 全 http400**(0.5s —— 快是因为它根本没在
    #    干活)。它仍在 _SAFE_MODELS(owner 控制台确认 + 计费安全), 只是进不了链。
    ("tencent", "deepseek-v4-flash-202605"),          # 6/6  1.9s
    ("ark", "doubao-seed-2-0-code-preview-260215"),   # 6/6  4.4s
    # DeepSeek 官方 (2026-08-15): 6/6 契约, 中位 1.06s —— 关思考、≤4s、通用文本,
    # 三条都满足。它是 aistore/DeepSeek-V4-Flash-A 9-13 到期后的**对位替代**。
    # ⛔ 顺序不表示链序: 跨到期日的先后由 _build_chain 按到期日升序算。
    ("deepseek", "deepseek-v4-flash"),                # 6/6  1.06s
]

# 每个槽只声明「够资格」的候选。⛔ 这里的顺序**不是**最终链顺序 ——
# 它只在「同一到期日」时生效(_build_chain 用稳定排序), 表达的是质量优先级。
# 跨到期日的先后由 _build_chain 按到期日升序算, 人不要在这里排。
_SLOT_POOLS: Dict[SLOT, List[Tuple[str, str]]] = {
    # 🔴 2026-08-13: CHAT / CHART / MAPPER 三个池的**全部原有条目今天实测 403**。
    #    它们清一色是 aliyun 的 flash / v4-flash / 3.8-max, 昨天还都是双证通过的。
    #    重建后三池只剩两条, 且都在 tencent —— aliyun 今天没有任何模型同时满足
    #    「有额度 + 通用文本 + 关思考可用」。
    #
    # CHAT — 高频低延迟, 关思考。只收关思考档 ≤2s 的通用文本模型。
    SLOT.CHAT: [
        ("aistore", "DeepSeek-V4-Flash-A"),
        ("tencent", "deepseek-v4-flash-202605"),   # 1.7s len=51
        ("deepseek", "deepseek-v4-flash"),         # 1.06s 关思考后
    ],
    # SIMPLE_TEXT — low-risk rewrite / summary / classification only.  Keeping
    # Qwen3-32B in a separate slot prevents it from silently receiving semantic
    # planning, chart or restaurant-review prompts when a larger model fails.
    SLOT.SIMPLE_TEXT: [
        ("aistore", "Qwen3-32B"),
    ],
    # CHART — 紧凑 JSON (关思考 + json_object)。与 CHAT 同一批快模型。
    SLOT.CHART: [
        ("aistore", "DeepSeek-V4-Flash-A"),
        ("aistore", "Qwen3-235B-A22B"),
        ("tencent", "deepseek-v4-flash-202605"),
        ("deepseek", "deepseek-v4-flash"),
    ],
    # MAPPER — 短 JSON 字段映射。契约是「短 JSON, 快而有界」, 由
    # test_mapper_uses_bounded_fast_models_without_max_or_reasoners 强制:
    # 池内不得含 _THINKING_ONLY / _SLOW_MODELS / Max 档。
    #
    # ⛔ 往本池加模型前先读这条(2026-08-10 生产事故, PR #2411): 当时因为某模型在
    #    **REVIEW** 槽的真实 prompt 上打分 3/3 就把它加进 MAPPER, 次日按 MAPPER
    #    的契约又移除。判据: **一个模型在 A 槽表现好, 不构成把它放进 B 槽的理由**
    #    —— 槽的契约(延迟上界 / 成本 / 是否强制 thinking)先于打分。
    #
    # ⛔ 同理, 2026-08-13 新进白名单的 4 个 tencent 特化 SKU(hy-mt2-lite/plus 是
    #    机器翻译, hy-role / hunyuan-role-latest 是角色扮演)虽然是**目前唯一的
    #    亚秒级活口**(0.68-0.81s), 也**没有**放进任何池 —— 它们连 llm_capability_rank
    #    都没跑过, "快"不是"会输出合契约的 JSON"的证据。要用先测。
    SLOT.MAPPER: [
        ("aistore", "DeepSeek-V4-Flash-A"),
        ("tencent", "deepseek-v4-flash-202605"),
        # 契约是「短 JSON, 快而有界」: deepseek-v4-flash 关思考后 1.06s,
        # 非 _THINKING_ONLY / 非 _SLOW_MODELS / 非 Max 档 —— 三条硬约束都过,
        # 由 test_mapper_uses_bounded_fast_models_without_max_or_reasoners 守。
        ("deepseek", "deepseek-v4-flash"),
    ],
    # INSIGHTS / REVIEW — 共用质量档池, 见上方 _QUALITY_TIER_POOL 定义。
    SLOT.INSIGHTS: list(_QUALITY_TIER_POOL),
    SLOT.REVIEW: list(_QUALITY_TIER_POOL),
    # REASONING — 允许慢, profile 为 {} (不设 enable_thinking)。
    # aliyun 今天仅存的 4 类模型里有 3 类是 _THINKING_ONLY, 它们只能落在这里。
    SLOT.REASONING: [
        ("aliyun_c", "kimi-k2.7-code"),                # 1.8s len=43
        ("aliyun_b", "kimi-k2.7-code"),                # 2.1s len=42
        ("aliyun_a", "qwen3.7-max-2026-05-17"),        # 2.2s len=48 (关思考会 400)
        ("aliyun_a", "kimi-k2.7-code"),                # 2.8s len=44
        ("aliyun_a", "qwen3.7-max-preview"),           # 4.6s len=45 (关思考会 400)
        ("tencent", "mimo-v2.5-pro"),                  # 6/6  24.7s
        # ⛔ ("aliyun_c", "qwen3.7-max-preview") **不进任何池**: 同一型号在 aliyun_a
        #    上 4.6s, 在 aliyun_c 上实测 **66.9 秒** —— 超过 call_chain 的 30s 总预算,
        #    选中它等于这一跳必然超时, 还会把后面候选的预算一起吃掉(见 _TEXT_TAIL
        #    里 08-09 那次连坐熔断)。它仍在 _SAFE_MODELS(控制台有 702,349 额度、
        #    探针 200), 只是没有任何槽的契约容得下 67 秒。
        #    📌 判据: **同一个模型在不同账号上的延迟可以差一个数量级** —— 延迟必须
        #       按 (账号, 模型) 量, 不能按模型名推。
    ],
    # VL — 空链。owner 2026-08-09 拍板(spec §9.1): 业务用不到 VL(prod 7 天仅 1 次
    #      真实调用), 明确报错优于把图片请求静默降级给文本模型瞎猜(CLAUDE.md 核心
    #      原则 1)。2026-08-13 复核: 原 VL 地板 zhipu/glm-4.6v 实测 **429**(余额不足),
    #      ark 的三个可调模型均非 VL SKU —— 仍然无候选, 维持空链。
    SLOT.VL: [
    ],
}

# 绝对置底 —— `_build_chain` 排序键的第 2 位, 优先于能力档/延迟档/到期日。
# 目前只有 zhipu/glm-4.5-air: 08-09 生产事故(PR #2411)证明它在某些槽上会稳定
# 超时, 而链是**串行且共享同一个总预算**, 它排在前面会把后面本来 0.5-1.1s 就能
# 答的候选一起拖到超时、连续 2 次即熔断 60 秒(当天实测连坐 4 个健康候选)。
# ⛔ 往这里加条目 = 声明"这个候选只在其它全挂时才可接受", 需要同等级别的实测证据。
_ABSOLUTE_LAST: frozenset = frozenset({("zhipu", "glm-4.5-air")})

# VL 槽不追加文本地板 —— 文本模型看不见图片, 追加只会把「明确失败」变成
# 「拿一段瞎猜的文字冒充图片理解」。这个集合是 _build_chain 的唯一例外,
# 也是 test_every_text_slot_has_a_floor 的唯一豁免项。
_NO_TEXT_TAIL_SLOTS: frozenset = frozenset({SLOT.VL})


def _build_chain(slot: SLOT) -> List[Tuple[str, str]]:
    """排序键 = (地板置底, 实测能力档, 到期日)。

    只有**有证据支撑**的那一档进排序键:

      1. 地板置底 —— 结构契约, 见下方行内注释
      2. 能力档 —— 实测达标 → 没测过 → 实测不达标 (见 `_capability_tier`)。
         唯一的实测依据是「这个 (账号,模型) 今天还答不答得出合契约的东西」。
      3. 到期日升序 —— use-it-or-lose-it, 原策略的合理内核, 保留。

    ⚠️ 这条修好了 2026-08-09 纯到期日排序的具体失败: `aliyun_c/deepseek-v3.2`
       08-13 到期(最早) → 排链头 → 而它实测 0/6 全 403 quota。排序键对
       「这个模型今天还活着吗」完全沉默。

    🔴 **2026-08-10 当天自我推翻的一版, 记在这里免得再犯**: 第一版排序键在
       能力档和到期日之间夹了一位**中位延迟升序**, 理由写的是「慢模型自然沉底」。
       它带来了一次真实回归 —— 回归电池同版本两轮**逐条相同的 61/85**
       (改动前 80/85)。

       根因是我自己在 `_CAPABILITY` 的注释里已经写明的事实: 契约是**地板题**,
       19 个候选 **18 个 6/6 满分**, 它区分不出模型强弱。18 个满分并列之后,
       排序**退化成了纯延迟升序** —— 也就是「最小最快的模型排最前」。链头因此
       从 glm-4.6 换成 qwen3-next-80b(0.8s), 而后者规划能力更弱。

       🔑 判据: **一个量, 你刚判定它区分不出 A, 就不能拿它去给 A 排序。**
          延迟是**约束**(超预算的不能进链), 不是**能力的代理**。快 ≠ 会规划。
       ⛔ 想真正「按能力排」, 需要一道会让好模型和更好的模型分开的题。现在没有
          —— 在有之前, 能力档只做「能不能用」这一件它做得到的事, 剩下的交回
          到期日。

    能力表超龄(>21 天) → 忽略能力档, 退回纯到期日排序(即旧行为)。陈旧读数
    不比没有读数好, 但**默默用陈旧读数**比两者都坏。

    稳定排序: 键全平手时保持 _SLOT_POOLS 里人写的顺序(= 人审的质量优先级)。
    """
    entries = list(_SLOT_POOLS[slot])
    if slot not in _NO_TEXT_TAIL_SLOTS:
        entries += _TEXT_TAIL
    if _capability_stale():
        return _dedup_chain(sorted(entries, key=lambda p: _expiry_of(*p)))
    tail_set = frozenset(_TEXT_TAIL)
    return _dedup_chain(sorted(entries, key=lambda p: (
        # 地板置底**先于**能力档 —— 这一位不是排序偏好, 是结构契约:
        # test_every_text_slot_has_a_floor 要求 chain[-1] 永远是地板, 否则
        # aliyun 全部过期那天该槽会变空。旧版靠地板的 _FAR_FUTURE 到期日
        # 隐式实现同一件事; 能力档一旦能把某个 aliyun 条目沉到底(实测不达标
        # 的 deepseek-v3.2 就是), 那个隐式保证当场失效 —— 所以显式写出来。
        #
        # 同理第二位: `_ABSOLUTE_LAST` 绝对置底 (2026-08-13 补)。
        # 🔴 `_TEXT_TAIL` 里那条「zhipu 必须留在最后一位」的判据(08-09 生产事故:
        #    它超时会把后面 4 个健康候选一起连坐熔断)此前**只写在注释里, 没有被
        #    任何东西执行**。注释的原话是「这几条到期日都是 None, 稳定排序原样
        #    保留书写顺序 —— 顺序由这几行决定」, 但排序键在到期日之前还有能力档
        #    和延迟档两位; 一旦这两位不同, 书写顺序就完全不起作用。实测(08-13):
        #    zhipu 被算到链的**第 2 位**, 因为它有一条 08-12 的能力读数(1.0, 1.8s)
        #    而当天新进的候选还没测过 → 前者档次更高。
        #    ⚠️ 而守这条判据的 test_fast_non_dashscope_floor_precedes_the_slow_one
        #       断言的是 `_TEXT_TAIL` **这个列表的书写下标**, 不是 `_build_chain`
        #       输出的链 —— 它守的东西和真正决定行为的东西不是同一个。
        #    判据: **一条「必须排最后」的不变量只能由结构键保证, 不能指望它从
        #          一张会变的数据表里涌现出来。**
        1 if p in tail_set else 0,
        1 if p in _ABSOLUTE_LAST else 0,
        _capability_tier(*p), _plan_schema_tier(*p),
        _latency_band(*p), _expiry_of(*p))))


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
    SLOT.SIMPLE_TEXT: {"enable_thinking": False},
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

# Shanghai Telecom AI Store accepts the common ``thinking`` object for all
# three admitted models.  Live probes also showed that this prevents the A
# DeepSeek endpoint from spending a short response budget on reasoning_content
# and returning empty content.
_AISTORE_THINKING_OBJECT_MODELS: frozenset[str] = frozenset({
    "DeepSeek-V4-Flash-A",
    "Qwen3-235B-A22B",
    "Qwen3-32B",
})

# DeepSeek 官方端点同样默认开 thinking, 必须显式关。
# 2026-08-15 实测(生产 prompt, with/without 对照):
#     不带该字段: 7.26s / 17.58s, completion=800 reasoning=800 content=0
#                 finish_reason=length  ← 把整个 max_tokens 烧在思考上, 返回空
#     带该字段  : 0.97s / 1.37s
# ⇒ 没有这个开关它**会**撞穿 6.0s 单跳预算并返回空 content。
#
# ⚠️ 这个注入并不是新东西: `_normalize_payload_for_provider` 的 docstring 记着
#    「The earlier DeepSeek-official `thinking.type=disabled` injection was
#    removed when deepseek-official was dropped from the chain (#580 Option 2)」
#    —— 随账号一起删的, 现在随账号一起回来。
_DEEPSEEK_THINKING_OBJECT_MODELS: frozenset[str] = frozenset({
    "deepseek-v4-flash",
    "deepseek-v4-pro",
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
    if (
        account == "aistore"
        and model in _AISTORE_THINKING_OBJECT_MODELS
        and prof.get("enable_thinking") is False
    ):
        p["thinking"] = {"type": "disabled"}
    if (
        account == "deepseek"
        and model in _DEEPSEEK_THINKING_OBJECT_MODELS
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


def _extract_upstream_error(body_json: Dict[str, Any]) -> Optional[str]:
    """Return a compact error label from an OpenAI-shaped response body.

    Some compatible gateways have been observed returning HTTP 200 with an
    ``error`` object.  A status-only success check would otherwise turn that
    protocol error into an empty assistant answer and retry it on every call.
    """
    error = (body_json or {}).get("error")
    if not error:
        return None
    if isinstance(error, dict):
        label = error.get("code") or error.get("type") or "error_body"
    else:
        label = "error_body"
    safe = "".join(ch for ch in str(label) if ch.isalnum() or ch in "._-")
    return (safe or "error_body")[:80]


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
        # Shanghai Telecom AI Store: OpenAI-compatible endpoint.  The API key
        # is production-secret-only; no fallback to another env name prevents
        # an unrelated credential from being sent to this provider by mistake.
        "aistore": (
            os.getenv("LLM_AISTORE_BASE_URL", "https://ai.api.coregpu.cn/v1"),
            os.getenv("LLM_AISTORE_API_KEY", ""),
        ),
        # DeepSeek official (2026-08-15, re-added). #580 Option 2 曾经删掉它
        # ("top up DeepSeek balance + re-add `deepseek` chain entry —
        # entire removal was 1 file"), 现在按那条路径加回来。
        #
        # 🔴 为什么现在加: aistore 三条 2026-09-13 硬到期, 而它们是
        #    CHAT/INSIGHTS/CHART/MAPPER/REVIEW **五个槽的链首**。
        #    2026-08-15 实测 `_MINIMAL_SAFE_SET` 8 条里已有 2 条死于配额、
        #    3 条中位超单跳预算 —— 扣掉 aistore 三条后, 既活着又在 6.0s 内的
        #    只剩 zhipu 一条, 而它被 08-09 事故钉在 `_TEXT_TAIL` 末位。
        #
        # ⛔ 与 aistore 同一条纪律: **不 fallback 到 LLM_API_KEY 或任何别的
        #    key**。写成 `os.getenv(..., "")` 而不是 `or os.getenv("LLM_API_KEY")`
        #    —— 把一个不相干的凭证发给这个 provider 是计费事故, 不是便利。
        #    由 test_deepseek_key_never_falls_back_to_an_unrelated_secret 钉住。
        #
        # ⚠️ 这是**按量付费**账号(仓里有 $19.49/12 天的成本前科, 见本文件
        #    History 段 May 9 2026)。
        #
        # 🔴 它**不在**链尾, 而是在**第 2 位** —— 这一条必须写清, 因为它与
        #    「付费的排最后」这个直觉相反, 而直觉在这里是错的:
        #    `_build_chain` 按**到期日升序**排, 而本条目带一个 2026-11-15 的
        #    **复审日**, 免费的 tencent/ark/zhipu 没有到期日(_FAR_FUTURE)
        #    ⇒ 复审日把它排到了那三个免费候选**前面**。实测派生链:
        #        aistore → **deepseek** → tencent → ark → minimax → zhipu
        #    ⇒ 合并当天起, aistore 每失败一次(实测约 9% 超时)就会立刻走一次
        #      **付费**调用, 不必等 9-13。
        #    ⇒ 这是 owner 的成本裁定, 不是实现细节。⛔ 不许靠手工重排改它
        #      (那违反「人不要在这里排」); 要改只能改到期日语义。
        "deepseek": (
            os.getenv("LLM_DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1"),
            os.getenv("LLM_DEEPSEEK_API_KEY", ""),
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
    "aistore", "aliyun_c", "aliyun_b", "aliyun_a", "tencent", "zhipu", "aliyun_a_deepseek",
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
    # Compatible gateways may wrap an upstream quota failure in HTTP 200.
    # Match only explicit quota/balance signatures; a generic 200 error body
    # remains a short circuit-breaker failure rather than a long quota skip.
    if status_code in (200, 402, 403, 429) and (
        "allocationquota" in lowered_body
        or "free_quota_exhausted" in lowered_body
        or "insufficient balance" in lowered_body
        or "余额不足" in body_text
    ):
        return True
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
        return "freetieronly" in lowered_body or "allocationquota" in lowered_body
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


_BUDGET_AWARE_FAST_SLOTS = frozenset({
    SLOT.CHAT, SLOT.SIMPLE_TEXT, SLOT.CHART, SLOT.MAPPER,
})
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
                upstream_error = _extract_upstream_error(body_json)
                if upstream_error:
                    _cb_record_failure(cb_key)
                    if _is_quota_exhausted(resp.status_code, body_text):
                        _quota_record_exhausted(cb_key)
                        errors.append(
                            f"{account}/{model}: quota_error_body_{upstream_error}"
                        )
                    else:
                        errors.append(
                            f"{account}/{model}: error_body_{upstream_error}"
                        )
                    logger.warning(
                        f"[llm_router] slot={slot.value} {account}/{model} "
                        f"returned a 2xx error body ({upstream_error}) — falling back"
                    )
                    continue
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
        stream_error_text = ""
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

                    upstream_error = _extract_upstream_error(obj)
                    if upstream_error:
                        stream_error_text = json.dumps(
                            obj.get("error"), ensure_ascii=False,
                        )
                        if first_delta_yielded:
                            logger.warning(
                                f"[llm_router_stream] {account}/{model} "
                                f"mid-stream error body ({upstream_error}) — "
                                "preserving partial result"
                            )
                            return
                        break

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
                if not first_delta_yielded:
                    _cb_record_failure(cb_key)
                    if stream_error_text and _is_quota_exhausted(200, stream_error_text):
                        _quota_record_exhausted(cb_key)
                        errors.append(f"{account}/{model}: quota_error_body")
                    else:
                        errors.append(
                            f"{account}/{model}: "
                            f"{'error_body' if stream_error_text else 'empty_stream'}"
                        )
                    logger.warning(
                        f"[llm_router_stream] slot={slot.value} {account}/{model} "
                        "ended before any content delta — falling back"
                    )
                    continue
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
