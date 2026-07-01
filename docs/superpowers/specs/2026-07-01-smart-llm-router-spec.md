# Smart LLM Router — Implementation Spec (2026-07-01)

**Status**: approved by Steve after 6-agent audit (3 billing/data/design + 3 industry/API/gap).
**File**: `backend/python/common/llm_router.py` (+ `common/llm_metrics.py` attribution fix).
**Branch**: `fix/tencent-402-quota-exhausted` (subsumes tencent 402 fix, PR #1136).
**Deploy**: `deploy-smartbi-python.sh --env prod` from main after merge (Opus ship-gate, not self-deploy).

---

## Reframe (governs all decisions)
All models are FREE tier (aliyun a/b/c + tencent + zhipu). Cost = $0 both ends. So per-query
ML routing / FrugalGPT scoring cascades / cross-account latency LB are OVERKILL (they optimize
dollars we don't spend). **Per-SLOT static routing + param profiles is the correct architecture.**
Optimization axes: (1) latency, (2) task-fit quality, (3) free-quota conservation, (4) billing-safety.

---

## 5 Layers

### Layer 1 — Billing-safety gate (audit-hardened)
- **`_SAFE_MODELS: Dict[(account, model), Optional[date]]`** — the ONLY allowlist. Contains a
  `(account, model)` iff that model's `免费额度用完即停` toggle is **已开启 (ON)** on that account
  (ON ⇒ 403 on exhaust/expiry, never bills). Value = free-grant expiry date (own date if console
  showed one; account **bulk** expiry if exhausted-ON with no date; `None` for tencent/zhipu).
  - **INCLUDE exhausted-ON models** (remaining "-"): they are billing-SAFE (403), just 0 quota now;
    keeping them preserves the monthly-reset recovery path (design-audit R2).
  - **tencent/zhipu are NOT DashScope** — no expiry concept; they enter via their own 用完即停/pool
    safety with `None` (far-future) sort key. Never refuse them for lacking a date (R1).
- **`_refuse_reason(account, model, today) -> Optional[str]`** — ONE shared helper, called from
  BOTH `call_chain` and `call_chain_stream` (streaming currently lacks the expiry gate — bug):
  1. not in `_SAFE_MODELS` → `"not_allowlisted"`
  2. in `_PAID_MODEL_DENYLIST` → `"paid_denylist"` (KEEP as final veto — cheap orthogonal backstop)
  3. DashScope expiry set AND `today >= expiry` → `"expired"` (hard call-time drop; sort alone is
     NOT enough — ascending sort would put expired at HEAD, tried first, billing risk — billing-audit C1)
- **Invariant (state in code)**: ON-toggle = SAFETY; expiry date = ORDERING/availability ONLY.
  Orthogonal. Never gate safety on the date; never trust the date for billing.
- **Staleness fail-safe**: `_REGISTRY_AUDIT_DATE`; if `today - audit_date > 21d` → loud WARN log +
  (optional) narrow to a hard-coded `_MINIMAL_SAFE_SET`. Fail SAFE not open.
- Keep existing circuit-breaker + quota-skip caches (keyed by `(account,model)`).

### Layer 2 — Capability model-pick (per-SLOT chains)
- Each SLOT chain = quality-appropriate models ordered by **capability-fit**, tiebroken by
  **per-model expiry ascending** (use-it-or-lose-it). NOT pure expiry sort (would degrade quality —
  design-audit R4). Implement as: bucket models by tier for the slot; within a tier sort by expiry.
- `_expiry_of(account, model)` reads `_SAFE_MODELS`; missing/None → far-future date.
- Replace `_account_rank` / `_expiry_aware_sort` / `_is_expired_paid` / `_ACCOUNT_FREE_ONLY` with
  the registry + `_refuse_reason` + per-model expiry sort.

### Layer 3 — Per-SLOT param profile (`_SLOT_PARAMS`)
Injected into payload per slot (DashScope non-standard params go top-level in raw JSON):

| SLOT | enable_thinking | response_format | temp / seed | max_tokens | notes |
|---|---|---|---|---|---|
| CHAT | false | (text) | 0.7 | keep | fast |
| INSIGHTS | false | (text) | 0.7 | keep | fast concise (probe: qwen3.7-max false=1.1s good) |
| CHART | **false** | **json_object** | **0 + seed** | **OMIT** | prompt MUST contain "json" |
| MAPPER | **false** | **json_object** | **0 + seed** | **OMIT** | prompt MUST contain "json" |
| REASONING | **true** (+thinking_budget) | (text) | default | keep | depth; stream recommended |
| VL | false (default) | (text) | default | keep | cap max_pixels |
| REVIEW | false | (text) | 0.7 | keep | fast good critique |

- `enable_thinking:false` = 10-20x speedup + saves 1200-3561 wasted reasoning tokens/call (measured
  on qwen3.7-max/qwen3.5-flash). Current prod sends nothing → default-ON hybrids burn quota — a
  live quota-drain bug this fixes.
- **thinking-only models** (can't disable, always think): `deepseek-r1*`, `qwen3-235b-a22b-thinking-2507`,
  `qwq-plus`, `kimi-k2.7-code`, `kimi-k2-thinking` → REASONING slot ONLY, never fast slots.
- **json_object requires enable_thinking:false** (thinking blocks it) AND "json" in prompt (else 400 —
  root of glm-4.5-air 400). Do NOT set max_tokens with json_object (truncation = parse fail).
- `enable_search:false` everywhere (default; keep off). `max_tokens`→`max_completion_tokens` migration.

### Layer 4 — Outcome validation + health-aware fallback (HIGHEST VALUE — gap-audit #1)
Router is currently quality-blind: any HTTP 200 = success, so empty/malformed/refusal/truncated
output reaches users with no fallback (the exact bug hand-patched twice: deepseek-r1-distill empty,
glm-5 60s).
- **`_validate_output(slot, content) -> bool`**: non-empty (all slots); `json.loads` parses
  (CHART/MAPPER, strip ```json fences first); min-length floor (INSIGHTS/REVIEW). On FAIL → treat as
  soft error, fall to next chain entry, log. This is the free half of a cascade (reject garbage+retry).
- **Split 429 vs 403 (correctness bug)**: `_is_quota_exhausted` currently treats ANY 429 as
  quota-exhausted → 6h skip → a transient burst 429 sidelines a healthy premium model 6h. Fix:
  403 FreeTierOnly / 402 FREE_QUOTA_EXHAUSTED|Insufficient Balance → long quota-skip; **429 → short
  backoff via circuit breaker only** (NOT the 6h quota cache).

### Layer 5 — Observability
- Fix `_guess_provider` in `llm_metrics.py`: **aliyun_c collapses to "dashscope"** (only a/b keyed) —
  add C, tencent, zhipu, aliyun_a_deepseek attribution (C is freshest + A being re-added → half-blind).
- Log `usage.prompt_tokens_details.cached_tokens` + validation-failure events.

---

## Explicitly SKIP (overkill for $0-both-ends)
Per-query difficulty routing (SLOTs already do coarse task→tier); FrugalGPT scoring cascade (take
only the validation half = Layer 4); cross-account latency LB (a/b/c same DashScope backend; would
destroy determinism + prompt-cache locality; the one benefit — dodge 429 — handled by Layer 4 split).

## DEFER (documented, not this PR)
Billing canary job (query real non-free spend/account, alarm >0 — the true registry-independent
backstop); data-driven Layer-2 re-rank from `smart_bi_llm_usage`; NOSKU/404 detection+alarm; golden
eval harness; context-cache optimization (MEASURE cached_tokens first — docs promise price discount
not free-quota-count reduction; note rotation-vs-cache-locality tension); "all exhausted" canned response.

---

## Registry data source (audit-verified, do NOT re-transcribe blind)
Console scrape 2026-06-30/07-01 (raw: `scratchpad/aliyun_{b,c}_quota_20260630.md` + A screenshot).
Data-accuracy audit: ~40 live-probe pairs, 0 mismatch, account identity confirmed (B=nick7039086005,
C=nick7750452697; keys not swapped). Cross-account divergence (per-(account,model) is MANDATORY):

| model | aliyun_a | aliyun_b | aliyun_c |
|---|---|---|---|
| deepseek-v4-pro | (n/a) | 不支持 PAID | ON (exhausted) |
| kimi-k2.7-code | ON | **未开启 BILLS** | ON |
| qwen3.5-ocr | (n/a) | **未开启 BILLS** | ON |
| glm-5.2 | (n/a) | 不支持 PAID | ON (09/15) |
| qwen-plus-latest | (n/a) | exhausted | ON 999k |

**aliyun_a**: ONLY the 13 screenshot-confirmed ON models (max/plus/flash/kimi/ocr) — NO VL/deepseek/glm
(toggles unknown → could bill). Expiries: qwen3.6-plus-2026-04-02 07/02, qwen3.6-flash 07/17,
kimi-k2.6 07/21, qwen3.5-plus-2026-04-20 07/23, qwen3.6-27b 07/23, qwen3.7-max-2026-05-20 08/20,
qwen3.7-max-2026-05-17 08/24, qwen3.7-max-preview 08/24, qwen3.7-plus 09/01, qwen3.7-plus-2026-05-26 09/01,
qwen3.7-max-2026-06-08 09/08, kimi-k2.7-code 09/14, qwen3.5-ocr 09/14.
**aliyun_b**: bulk 07/16; premium drained (qwen-plus-latest exhausted); AVOID 未开启 kimi-k2.7-code/qwen3.5-ocr, 不支持 deepseek-v4-pro/glm-5.2/namespaced.
**aliyun_c**: bulk 08/13; fullest; nearly all ON+quota.

**H4 gate before ship**: run live probe (`/tmp/llm_quota_probe.py` style) against EVERY registry
entry; reject any (account,model) not returning clean 200/403 (a 404/paid-200/other disqualifies).

## Capability facts (05-14 benchmark + thinking-probe + families)
- FAST clean (CHAT/MAPPER, thinking OFF): qwen-flash, qwen-turbo, qwen3.5-flash(false)=0.6s,
  qwen-plus-latest(default off)=0.77s. qwen3.5-flash WITH thinking=33s+hallucinate (avoid thinking-on).
- JSON compact (CHART/MAPPER): qwen-turbo valid; deepseek wraps ```json (needs json_object); glm-5 60s
  (too slow); glm-4.5-air 400 (missing "json" keyword — fixed by json_object+"json").
- REASONING depth: deepseek-v4-pro best (22s), deepseek-r1 deeper but 42s, qwen3-max concise (16s).
- REVIEW: qwen3-max/deepseek-v3.2 concise+complete; deepseek-r1-distill-qwen-32b EMPTY (broken).
- VL: only qwen*-vl-* / glm-4.6v.
- Fresh benchmark on current models (qwen3.7-*, glm-5.x, kimi, MiniMax) pending → refine Layer-2 order.

## CI must-have test (design-audit c)
Structural billing-safety invariant: every (account,model) in every SLOT chain ∈ `_SAFE_MODELS`,
AND `_SAFE_MODELS` ∩ landmine-set = ∅ (B/kimi-k2.7-code, B/qwen3.5-ocr, B/deepseek-v4-pro, glm-5.2,
all namespaced/*, bare qwen-max/qwen-plus/qwen3-max). Plus: config-drift refuse test (inject
B/kimi-k2.7-code → assert `_refuse_reason` refuses); future-date test (mock today=08/14 → each slot
still has ≥1 live fallback); 429-not-quota test; validation-fallback test.
