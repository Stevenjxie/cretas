"""Restaurant intent flywheel promotion: capture -> two-level objective gate ->
human review (--apply) -> repo-committed ledger -> re-embedded vector index.

Design docs:
  docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md (section 5, flywheel)
  docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md

Philosophy mirror: smartbi/services/learning_promotion.py -- capture (already
in restaurant_intent.log_intent_capture / list_promotion_candidates) -> an
OBJECTIVE gate (never LLM-judged, never silent) -> a human runs
`scripts/restaurant-intent-promote.py --apply <reviewed.json>` -> the ledger
file below is written -> a human commits + deploys -> `populate_restaurant_ops`
re-embeds the merged sample set on next boot.

Why a repo-file ledger and not just a DB UPSERT (the key constraint driving
this module's shape):
  1. Python deployment is `rsync` of the code tree (server-operations.md /
     python-services-architecture.md) -- anything a live server process
     writes to disk is clobbered by the next deploy. A promotion decision
     that only lives in a DB row would survive deploys but the *code* that
     reads SAMPLE_QUERIES would still not know about it unless the vector
     index itself is DB-only source of truth, which conflicts with #2:
  2. When the embedding model changes, `populate_restaurant_ops` re-embeds
     from its Python-side sample dict. If a promoted query is not in that
     dict, a model upgrade silently drops it from the re-embedded set while
     leaving a stale-model row behind in `smart_bi_template_embeddings` --
     `count_embeddings` gates on `embedding_model = _MODEL`, so that stale
     row would be invisible to the current model's coverage count too,
     making the gap silent.
  So: apply_promotions() writes a JSON ledger *in the repo* (like
  learning_promotion's TRUNK_FILE), and `merge_samples()` is the single
  source of truth `populate_restaurant_ops` embeds from -- ledger entries are
  first-class members of the sample set, not a parallel DB-only index.

TWO DIFFERENT LEDGERS, DO NOT CONFLATE (2026-07-28):
  * the JSON file above = the sample corpus the VECTOR INDEX embeds. It is a
    retrieval hint. Repo file, for the two reasons just given.
  * `ai_promoted_routes` (Postgres, migration V20261030_01, written by
    `apply_route_promotions` below) = an EXECUTION GRANT: reviewed whole
    sentences that production may answer WITHOUT calling the planner. Neither
    of the two reasons above applies to it -- it must be visible to both
    uvicorn workers the moment a human approves it (no deploy), it carries a
    whole plan rather than a bare code, and nothing re-embeds it. That is why
    it is a table and the sample corpus is not.

Never silently graduates: `apply_promotions` / `apply_route_promotions` are
only ever invoked by a human running the CLI's `--apply-ledger` / `--apply`
flag with a file the human already reviewed. Nothing in this module,
`aggregate_candidates` included, writes on its own.
"""
from __future__ import annotations

import datetime as _dt
import json
import logging
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

_DATA = Path(__file__).parent.parent / "data"
LEDGER_FILE = _DATA / "promoted_restaurant_intent_samples.json"
# 否决账本 (2026-07-23): 人审否决过的问法 (写操作错接/上下文省略句等),
# 不再作为候选重复出现 — 否则每日 cron 日报会对同一批已否决条目反复告警。
# 格式: {"query": "...", "reason": "..."} 的 JSON list, 仅 CLI --apply 之外
# 人工编辑 (否决和通过一样是人的判断, 不自动写)。
REJECTED_FILE = _DATA / "rejected_restaurant_intent_samples.json"

# Miss 复盘处理状态 (卡5b 补充, 2026-07-28): `aggregate_misses` 每条是
# RESTAURANT_OPS_MISS 聚合行 (只读, 来自 capture 表), 复盘时人工标注"这条
# 打算怎么处理"没地方存 -- 同 REJECTED_FILE 一样是纯人工判断的小体量数据,
# 走 repo-committed JSON 文件而不是新建 DB 表/migration (本卡任务卡明确
# 禁止自建 migration; 这个数据量级和访问模式也不需要一张表)。
# 格式: {normalized_query: {"status": ..., "note": ..., "reviewed_by": ...,
# "updated_at": ISO8601}}。
MISS_STATUS_FILE = _DATA / "restaurant_intent_miss_status.json"
# 允许的处理状态 -- 显式枚举, 不接受任意字符串 (禁止降级/明确原则: 前端传
# 错值要 400, 不能悄悄存一个前端和后端理解不一致的自由文本)。
MISS_STATUS_VALUES = frozenset({
    "unreviewed",   # 默认/未处理 (未显式设置时的隐含状态, 不需要写一条记录)
    "planned",      # 已排入 resolver/意图目录扩展计划
    "wontfix",      # 评估后判定不做 (如误触发/低频/超出产品范围)
    "duplicate",    # 与已有 miss 或已支持意图重复
    "resolved",     # 已有后续版本覆盖 (resolver 已扩展/晋升表已收录)
})

# Two-level objective gate thresholds (spec section 5). Row-level filter (a)
# is always applied (tier=llm, contract_pass=true, served=true -- see the SQL
# in aggregate_candidates). Group-level recommendation (b) additionally
# requires either repeat occurrence or high single-shot confidence, and never
# recommends a group where different rows resolved to different codes
# (`conflict`) -- those still surface for human review, just unmarked.
_RECOMMEND_MIN_COUNT = 2
_RECOMMEND_MIN_CONFIDENCE = 0.85


# ─── RLS GUC helper (shared by aggregate_candidates / aggregate_misses) ────
#
# `factory_id=None` means "platform admin channel" (卡5b 运营台聚合看板 —
# 跨租户读所有 domain 命中行, 不按发起管理员自己的工厂过滤). This is
# DIFFERENT from "not calling set_config at all":
#
# `smartbi/tenant_ctx.py`'s asyncpg pool `setup` callback
# (`set_pg_connection_tenant`) already runs `SELECT set_config('app.factory_id',
# fid, false)` on EVERY connection checkout, using the ambient per-request
# ContextVar. When no tenant is in scope (e.g. a platform_admin JWT with no
# `factoryId` claim) that callback's `fid` falls back to the sentinel
# `"__internal__"` (`tenant_ctx.INTERNAL_SENTINEL`), NOT an empty string.
# `smart_bi_llm_fallback_log`'s FORCE RLS `tenant_select` policy is:
#     factory_id = current_setting('app.factory_id', true)
#     OR current_setting('app.factory_id', true) = ''
#     OR current_setting('app.factory_id', true) IS NULL
# `'__internal__'` matches none of those three branches (it isn't empty/NULL
# and no real row has factory_id='__internal__') -- so relying on "the pool
# already set something" for a platform-wide read would silently return ZERO
# rows, not "all rows". This is the same 假0 failure class as
# `feedback_smartbi_rls_set_guc_before_query` / the CLI's original bug, just
# one layer further from view (the pool sets *something*, it's just the
# wrong something for this call).
#
# So the admin channel here explicitly RESETS the GUC to `''` on the borrowed
# connection, after `pool.acquire()`, before the query -- deterministically
# overwriting whatever the pool `setup` callback (or a prior borrower, since
# asyncpg physically reuses connections) left behind. `is_local` stays
# `false` (session-scoped, not `SET LOCAL`/transaction-scoped) because
# asyncpg's `is_local=true` set_config has been verified in this project to
# NOT reliably apply across the acquire()/execute() step boundary on a pooled
# connection (`feedback_asyncpg_local_setconfig_rls_never_applies`) -- `false`
# mirrors both `tenant_ctx.set_pg_connection_tenant` and this module's
# pre-existing per-tenant `set_config` calls below.
async def _set_rls_guc(conn, factory_id: Optional[str]) -> None:
    """Explicitly set (or reset) `app.factory_id` on `conn` before a query.

    `factory_id=None` -> admin channel: reset to `''` so the FORCE RLS
    permissive branch matches and ALL tenants' rows are visible (platform-wide
    aggregation, e.g. 卡5b 运营台). `factory_id=<str>` -> tenant-scoped read
    (CLI usage, one factory at a time).
    """
    await conn.execute(
        "SELECT set_config('app.factory_id', $1, false)", factory_id or ""
    )


# ─── Ledger I/O ────────────────────────────────────────────────────────────

def load_promoted_samples() -> Dict[str, List[str]]:
    """Read the repo-committed ledger. Tolerates a missing/empty/corrupt file
    (fail-open, mirrors learning_promotion._load): callers treat {} as "no
    promotions yet", never as an error."""
    try:
        if LEDGER_FILE.exists():
            with open(LEDGER_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return {
                    str(code): [str(q) for q in queries if isinstance(q, str) and q.strip()]
                    for code, queries in data.items()
                    if isinstance(queries, list)
                }
    except Exception as exc:
        logger.warning(f"[restaurant-intent-promotion] load ledger failed (ignored): {exc}")
    return {}


def merge_samples(base: Optional[Dict[str, List[str]]] = None) -> Dict[str, List[str]]:
    """`base` (default `restaurant_ops_router.SAMPLE_QUERIES`) + the promoted
    ledger, deduped per code. This is the single source of truth
    `populate_restaurant_ops` embeds from and the source `aggregate_candidates`
    checks "already known" against -- so a promoted query is never re-offered
    as a candidate and never silently missing from the re-embedded index after
    an embedding-model upgrade (see module docstring point 2).

    Base entries keep their original order; ledger-only entries are appended
    sorted (stable diffs, no dependency on ledger file's on-disk order)."""
    if base is None:
        from smartbi.gold.restaurant.restaurant_ops_router import SAMPLE_QUERIES
        base = SAMPLE_QUERIES
    ledger = load_promoted_samples()
    merged: Dict[str, List[str]] = {}
    for code in sorted(set(base) | set(ledger)):
        base_list = list(base.get(code, []))
        extra = sorted(q for q in ledger.get(code, []) if q not in base_list)
        merged[code] = base_list + extra
    return merged


def load_rejected_queries() -> frozenset:
    """人审否决过的问法集合。fail-open: 文件缺失/损坏 → 空集。"""
    try:
        if REJECTED_FILE.exists():
            with open(REJECTED_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                return frozenset(
                    str(e.get("query")).strip()
                    for e in data
                    if isinstance(e, dict) and str(e.get("query") or "").strip()
                )
    except Exception as exc:
        logger.warning(f"[restaurant-intent-promotion] load rejected failed (ignored): {exc}")
    return frozenset()


def _known_query_set(merged: Dict[str, List[str]]) -> frozenset:
    return frozenset(q for queries in merged.values() for q in queries) | load_rejected_queries()


# ─── Question-family classification (evidence-based backlog) ──────────────
# Tags each LLM-tail question by intent FAMILY so the dimension backlog is
# demand-driven, not supply-driven (2026-07-08 strategy amendment, Fable review):
# we build a NEW attribution/write dimension only when the flywheel shows owners
# actually asking that family. Pure keyword heuristic, no LLM.
_ATTRIBUTION_CUES = (
    "为什么", "为啥", "怎么回事", "拖后腿", "垫底", "拉低", "拖累", "差在哪",
    "是客流还是", "是量还是", "是率还是", "归因", "原因", "哪个环节",
    # Colloquial owner phrasings (2026-07-08 role-play) — keep aligned with
    # synthesis_engine.plan_dimensions so the demand report labels these
    # attribution too. All are underperformance/comparison cues, so they do NOT
    # match neutral query examples ("哪家店订单最多" / "本周销量排行").
    "最不行", "做不起来", "做不起", "是没人来还是", "是人少还是", "是客人少还是",
    "谁最差", "哪家最差", "生意差在哪",  # "哪家差" dropped — ⊂ 哪家差不多 (audit B#3)
)
_WRITE_CUES = (
    "建个", "建一个", "新建", "创建", "帮我建", "录入", "开单", "下单", "开一张",
    "申请", "提交", "新增", "登记", "报个", "补录",
)


def classify_question_family(text: Optional[str]) -> str:
    """Return the intent family of a question: attribution | write | query.

    - attribution: "为什么亏 / 哪家店拖后腿 / 是客流还是客单价" — needs a
      deterministic decomposition producer (numeric attribution can NOT be
      LLM-generalized; see synthesis_engine docstring).
    - write: "帮我建个领料单 / 录入盘点" — needs a write Tool + preview/confirm.
    - query: everything else (lookup / ranking / trend) — deterministic resolver.
    """
    t = (text or "").lower()
    if any(c in t for c in _ATTRIBUTION_CUES):
        return "attribution"
    if any(c in t for c in _WRITE_CUES):
        return "write"
    return "query"


def family_breakdown(candidates: List[Dict[str, Any]]) -> Dict[str, int]:
    """Count promotion candidates per family — the evidence for whether the next
    backlog slot should be an attribution dimension, a write op, or neither."""
    out: Dict[str, int] = {"attribution": 0, "write": 0, "query": 0}
    for c in candidates:
        out[c.get("family") or classify_question_family(c.get("query"))] += 1
    return out


# ─── Candidate aggregation (objective gate, read-only) ────────────────────

async def aggregate_candidates(
    pool,
    *,
    min_confidence: float = 0.75,
    min_count: int = 1,
    limit: int = 200,
    factory_id: Optional[str] = "DEMO_REST",
) -> List[Dict[str, Any]]:
    """Aggregate `smart_bi_llm_fallback_log` rows into promotion candidates.

    `factory_id=None` -> platform admin channel (see `_set_rls_guc` docstring):
    reads across ALL tenants, for 卡5b's cross-tenant 运营台候选队列. The CLI
    keeps passing an explicit tenant string (default "DEMO_REST"), unaffected.

    Row-level gate (a) -- only rows that are ALL of: tier='llm' (T3 parsed
    it, meaning T1/T2 could not), contract_pass=true (Answer Contract did not
    flag missing elements), served=true (the answer actually reached the
    user) -- ever enter the aggregate. This is the same objective bar
    `list_promotion_candidates` in restaurant_intent.py checks per-row; this
    function groups those rows by normalized query text across repeats.

    Group-level recommendation (b) -- a group is `recommended` only when
    it is NOT `conflict` (all rows agree on the same RESTAURANT_OPS_* code)
    AND (occurred >= 2 times OR max confidence >= 0.85). Conflicting or
    below-threshold groups are still returned (a human may still want to look
    at them) but `recommended=False` -- the CLI prints them distinctly and
    `apply_promotions` does not gate on this flag (a human decides what goes
    into the JSON they pass to --apply); this flag is a review aid, not an
    enforcement mechanism.

    `min_confidence`/`min_count` are a loose pre-filter (HAVING, OR'd) so a
    caller with a large log table can narrow the read before the Python-side
    recommendation computation; defaults are permissive (virtually every
    row-level-qualifying group is returned) since exclusion of low-value
    groups is `recommended=False`, not omission.

    Fail-open: returns [] on any DB error (mirrors
    list_promotion_candidates / the rest of this module's philosophy)."""
    sql = """
        SELECT trim(query)                                              AS norm_query,
               array_agg(template_code)                                  AS codes,
               COUNT(*)                                                  AS occurrence_count,
               MAX(COALESCE((agg_meta->>'confidence')::float, 0))        AS max_confidence,
               MAX(created_at)                                           AS last_seen
          FROM smart_bi_llm_fallback_log
         WHERE source = 'template'
           AND template_code LIKE 'RESTAURANT_OPS_%%'
           AND (agg_meta->>'tier') = 'llm'
           AND (agg_meta->>'served') = 'true'
           AND (agg_meta->>'contract_pass') = 'true'
         GROUP BY trim(query)
        HAVING MAX(COALESCE((agg_meta->>'confidence')::float, 0)) >= $1
            OR COUNT(*) >= $2
         ORDER BY MAX(created_at) DESC
         LIMIT $3
    """
    try:
        async with pool.acquire() as conn:
            # smart_bi_llm_fallback_log 带 FORCE RLS (tenant_select 策略):
            # 不设 app.factory_id GUC 会假性 0 行 → CLI 误报"无候选"
            # (2026-07-23 首次真跑晋升 CLI 踩中, 同 feedback_smartbi_rls
            # 记忆里的裸 psql 坑)。显式 set_config 后再查 -- factory_id=None
            # 时用管理员通道 (见 _set_rls_guc), 否则单租户扫描 (CLI 用法)。
            await _set_rls_guc(conn, factory_id)
            rows = await conn.fetch(sql, min_confidence, min_count, limit)
    except Exception as exc:
        logger.warning(f"[restaurant-intent-promotion] aggregate_candidates query failed (fail-open): {exc}")
        return []

    known = _known_query_set(merge_samples())

    candidates: List[Dict[str, Any]] = []
    for r in rows:
        norm_query = (r["norm_query"] or "").strip()
        if not norm_query or norm_query in known:
            continue  # already promoted / already a shipped sample -- not a new candidate
        raw_codes = [c for c in (r["codes"] or []) if c]
        # restaurant_intent._VALID_CODES imported lazily to avoid import-time
        # coupling for callers that only need merge_samples/load_promoted_samples.
        from smartbi.gold.restaurant.restaurant_intent import _VALID_CODES
        valid_codes = [c for c in raw_codes if c in _VALID_CODES]
        if not valid_codes:
            continue
        counts = Counter(valid_codes)
        top_code, _top_n = counts.most_common(1)[0]
        conflict = len(counts) > 1
        occurrence_count = int(r["occurrence_count"] or 0)
        max_confidence = round(float(r["max_confidence"] or 0.0), 3)
        recommended = (
            not conflict
            and (occurrence_count >= _RECOMMEND_MIN_COUNT or max_confidence >= _RECOMMEND_MIN_CONFIDENCE)
        )
        candidates.append({
            "query": norm_query,
            "code": top_code,
            "codes": sorted(counts.keys()),
            "occurrence_count": occurrence_count,
            "max_confidence": max_confidence,
            "conflict": conflict,
            "recommended": recommended,
            "family": classify_question_family(norm_query),
            "last_seen": r["last_seen"],
        })
    return candidates


async def aggregate_misses(
    pool,
    *,
    limit: int = 200,
    factory_id: Optional[str] = "DEMO_REST",
) -> List[Dict[str, Any]]:
    """Aggregate delegate:false misses (哨兵 template_code='RESTAURANT_OPS_MISS',
    log_intent_miss 写入) -- 飞轮的另一半原料: tiered 没接住的问法。

    分组按 query 文本, 带 miss_reason 分布 (prefilter / should_delegate) 和
    spec_intent (should_delegate miss 时 T1-T3 实际解析出的意图, 有值说明
    "解析对了但路由拒了" -- 通常是 resolver 缺口或 A-3 类例外)。

    只读, fail-open []。RLS 同 aggregate_candidates: 查询前设 GUC
    (`factory_id=None` -> 管理员通道, 见 `_set_rls_guc`)。"""
    sql = """
        SELECT trim(query)                                          AS norm_query,
               COUNT(*)                                             AS occurrence_count,
               array_agg(DISTINCT agg_meta->>'miss_reason')         AS reasons,
               array_agg(DISTINCT agg_meta->>'spec_intent')
                   FILTER (WHERE agg_meta->>'spec_intent' IS NOT NULL) AS spec_intents,
               MAX(created_at)                                      AS last_seen
          FROM smart_bi_llm_fallback_log
         WHERE template_code = 'RESTAURANT_OPS_MISS'
         GROUP BY trim(query)
         ORDER BY COUNT(*) DESC, MAX(created_at) DESC
         LIMIT $1
    """
    try:
        async with pool.acquire() as conn:
            await _set_rls_guc(conn, factory_id)
            rows = await conn.fetch(sql, limit)
    except Exception as exc:
        logger.warning(f"[restaurant-intent-promotion] aggregate_misses query failed (fail-open): {exc}")
        return []
    return [
        {
            "query": (r["norm_query"] or "").strip(),
            "occurrence_count": int(r["occurrence_count"] or 0),
            "reasons": sorted(x for x in (r["reasons"] or []) if x),
            "spec_intents": sorted(x for x in (r["spec_intents"] or []) if x),
            "last_seen": r["last_seen"],
            "family": classify_question_family((r["norm_query"] or "").strip()),
        }
        for r in rows
        if (r["norm_query"] or "").strip()
    ]


# ─── Human-reviewed apply (the ONLY write path) ───────────────────────────

def apply_promotions(entries: List[Dict[str, str]]) -> Dict[str, Any]:
    """Write human-reviewed `[{"query": ..., "code": ...}, ...]` entries into
    the ledger file. This is the ONLY function in this module that writes,
    and it is only ever called by a human via the CLI's `--apply <file>` --
    never automatically, never from `aggregate_candidates` or any request
    path (spec section 5: "绝不静默自动毕业").

    Invalid entries (empty query, code not one of the 8 known
    RESTAURANT_OPS_* codes) and entries already present in the ledger are
    skipped with a reason, not silently dropped -- the caller (CLI) reports
    both `added` and `skipped` so a human can see exactly what happened.

    Returns a dict with `added`, `skipped`, `ledger_path`, `ledger_size` for
    the CLI to print as a diff summary. Does not write the file at all when
    nothing was added (no gratuitous touch of a file a human will `git diff`
    afterwards)."""
    from smartbi.gold.restaurant.restaurant_intent import _VALID_CODES

    ledger = load_promoted_samples()
    added: List[Dict[str, str]] = []
    skipped: List[Dict[str, str]] = []
    for e in entries or []:
        query = str((e or {}).get("query") or "").strip()
        code = str((e or {}).get("code") or "").strip()
        if not query or code not in _VALID_CODES:
            skipped.append({"query": query, "code": code, "reason": "invalid_code_or_empty_query"})
            continue
        bucket = ledger.setdefault(code, [])
        if query in bucket:
            skipped.append({"query": query, "code": code, "reason": "already_in_ledger"})
            continue
        bucket.append(query)
        added.append({"query": query, "code": code})

    if added:
        ordered = {code: sorted(set(qs)) for code, qs in ledger.items() if qs}
        LEDGER_FILE.parent.mkdir(parents=True, exist_ok=True)
        LEDGER_FILE.write_text(
            json.dumps(ordered, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    return {
        "added": added,
        "skipped": skipped,
        "ledger_path": str(LEDGER_FILE),
        "ledger_size": sum(len(v) for v in ledger.values()),
    }


# ─── Reviewed route promotion -> `ai_promoted_routes` (2026-07-28) ────────
#
# The ledger above answers "which sample sentences does the VECTOR INDEX
# embed"; it is a retrieval-hint corpus and stays a repo file for the two
# reasons in the module docstring (rsync deploys, embedding-model upgrades).
#
# This section answers a different question: "which reviewed sentences may be
# ANSWERED WITHOUT CALLING THE PLANNER". That is an execution grant, it must
# be readable by both uvicorn workers the moment it is approved, and it
# carries a whole plan rather than a bare code -- so it lives in Postgres
# (`ai_promoted_routes`, migration V20261030_01), which is exactly the storage
# the semantic-first branch of `parse_restaurant_query` reads.
#
# Still never automatic: `apply_route_promotions` is only reachable from a
# human running the CLI's `--apply` with a file that human reviewed.

_PROMOTION_SOURCES = ("flywheel", "manual_seed")
# RLS on `ai_promoted_routes` refuses a global-scope write from a tenant
# session; the reviewed-promotion path is explicitly an internal operation.
_INTERNAL_SENTINEL = "__internal__"


def default_seed_plan(code: str) -> Dict[str, Any]:
    """A minimal, contract-complete planner plan for a bare reviewed phrase.

    Used when a reviewed entry names only a resolver code. Time is left NULL
    on purpose: the phrase itself carries no window, so the deterministic time
    gate still asks for one -- identical to how the in-code exact registry
    behaved. A concrete date must NEVER appear in a stored plan (it would be
    replayed verbatim tomorrow); relative time belongs in `time_range`.
    """
    return {
        "intent": code,
        "time_range": None,
        "wants_margin": False,
        "asks_profitability": False,
        "requested_metrics": [],
        "analysis_action": "lookup",
        "dimensions": [],
        "dish": None,
        "store": None,
        "stores": [],
        "store_scope": None,
        "confidence": 1.0,
        "clarification_needed": False,
        "missing_fields": [],
        "clarification_question": None,
        "clarification_options": [],
    }


def _plan_rejection_reason(plan: Any, phrase: str) -> Optional[str]:
    """Reject a plan the runtime could not replay, BEFORE it reaches the
    table. Compiling it here is the same check the read path performs, so a
    row that would silently never fire is never written."""
    from smartbi.gold.restaurant.restaurant_intent import (
        _semantic_spec_from_t3,
        plan_is_replayable,
    )

    if not isinstance(plan, dict):
        return "plan_not_an_object"
    if json.dumps(plan, ensure_ascii=False).find('"date_range"') != -1:
        return "plan_contains_resolved_dates"
    try:
        spec = _semantic_spec_from_t3(plan, phrase)
    except Exception as exc:  # pragma: no cover - defensive
        return f"plan_compile_error:{exc}"
    if not plan_is_replayable(spec):
        return f"plan_not_replayable:{spec.planner_authority}"
    return None


async def apply_route_promotions(
    pool,
    entries: List[Dict[str, Any]],
    *,
    domain: str = "restaurant",
    scope: str = "global",
    source: str = "manual_seed",
    reviewed_by: Optional[str] = None,
) -> Dict[str, Any]:
    """UPSERT human-reviewed whole-sentence promotions into
    `ai_promoted_routes`.

    `entries` are `{"query": ..., "code": ...}` (a default plan is built) or
    `{"query": ..., "plan": {...}}` (an explicit reviewed plan). Every entry
    is normalized with the SAME whole-sentence normalizer the runtime matcher
    uses, so what is stored is exactly what can match.

    Invalid entries are reported with a reason rather than dropped. Raises on
    a DB failure: unlike the read path, an apply that did not persist must not
    look like it succeeded.
    """
    from smartbi.gold.restaurant.restaurant_intent import (
        _VALID_CODES,
        _normalize_exact_phrase,
        clear_promoted_routes_cache,
    )

    if source not in _PROMOTION_SOURCES:
        raise ValueError(f"source must be one of {_PROMOTION_SOURCES}, got {source!r}")
    if not scope:
        raise ValueError("scope must be 'global' or a factory_id")

    accepted: List[Dict[str, Any]] = []
    skipped: List[Dict[str, Any]] = []
    seen: set = set()
    for entry in entries or []:
        entry = entry or {}
        raw_query = str(entry.get("query") or "")
        phrase = _normalize_exact_phrase(raw_query)
        code = str(entry.get("code") or "").strip()
        plan = entry.get("plan")
        if plan is None and code:
            plan = default_seed_plan(code)
        if not phrase:
            skipped.append({"query": raw_query, "reason": "empty_query"})
            continue
        if not isinstance(plan, dict):
            skipped.append({"query": raw_query, "reason": "missing_plan_and_code"})
            continue
        if plan.get("intent") not in _VALID_CODES:
            skipped.append({
                "query": raw_query,
                "reason": f"unknown_intent:{plan.get('intent')}",
            })
            continue
        reason = _plan_rejection_reason(plan, phrase)
        if reason:
            skipped.append({"query": raw_query, "reason": reason})
            continue
        if phrase in seen:
            skipped.append({"query": raw_query, "reason": "duplicate_in_batch"})
            continue
        seen.add(phrase)
        accepted.append({"query": raw_query, "phrase": phrase, "plan": plan})

    written: List[Dict[str, Any]] = []
    if accepted:
        # The GUC decides what RLS lets us write: a global promotion is an
        # internal operation, a tenant promotion must run as that tenant.
        guc = _INTERNAL_SENTINEL if scope == "global" else scope
        async with pool.acquire() as conn:
            async with conn.transaction():
                await conn.execute(
                    "SELECT set_config('app.factory_id', $1, true)", guc,
                )
                for item in accepted:
                    await conn.execute(
                        """
                        INSERT INTO ai_promoted_routes
                            (domain, normalized_phrase, plan_json, plan_version,
                             source, scope, reviewed_by)
                        VALUES ($1, $2, $3::jsonb, $4, $5, $6, $7)
                        ON CONFLICT (domain, normalized_phrase) DO UPDATE
                           SET plan_json    = EXCLUDED.plan_json,
                               plan_version = EXCLUDED.plan_version,
                               source       = EXCLUDED.source,
                               scope        = EXCLUDED.scope,
                               reviewed_by  = EXCLUDED.reviewed_by
                        """,
                        domain,
                        item["phrase"],
                        json.dumps(item["plan"], ensure_ascii=False, sort_keys=True),
                        "restaurant-query-plan-v2",
                        source,
                        scope,
                        reviewed_by,
                    )
                    written.append({
                        "query": item["query"],
                        "normalized_phrase": item["phrase"],
                        "intent": item["plan"].get("intent"),
                    })

    # This process is the CLI, not a server worker; the servers pick the new
    # rows up on their own short catalogue TTL. Clearing here keeps a
    # same-process caller (tests, a future admin endpoint) honest.
    clear_promoted_routes_cache()

    return {
        "domain": domain,
        "scope": scope,
        "source": source,
        "written": written,
        "skipped": skipped,
    }


async def list_route_promotions(
    pool,
    *,
    domain: str = "restaurant",
    factory_id: str = _INTERNAL_SENTINEL,
) -> List[Dict[str, Any]]:
    """Read back what is currently promoted, for CLI review. Read-only."""
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id,
            )
            rows = await conn.fetch(
                """
                SELECT normalized_phrase, plan_json, plan_version, source,
                       scope, reviewed_by, created_at, hit_count
                  FROM ai_promoted_routes
                 WHERE domain = $1
                 ORDER BY normalized_phrase
                """,
                domain,
            )
    out: List[Dict[str, Any]] = []
    for row in rows or ():
        plan = row["plan_json"]
        if isinstance(plan, (bytes, bytearray)):
            plan = plan.decode("utf-8", "ignore")
        if isinstance(plan, str):
            try:
                plan = json.loads(plan)
            except (TypeError, ValueError):
                plan = {}
        out.append({
            "normalized_phrase": row["normalized_phrase"],
            "intent": (plan or {}).get("intent"),
            "plan_version": row["plan_version"],
            "source": row["source"],
            "scope": row["scope"],
            "reviewed_by": row["reviewed_by"],
            "created_at": row["created_at"],
            "hit_count": row["hit_count"],
        })
    return out


# ─── Human-reviewed reject (卡5b: web 否决通道, alongside the CLI/manual one) ─

def reject_candidate(
    query: str, reason: str, *, rejected_by: Optional[str] = None,
) -> Dict[str, Any]:
    """Append one human-reviewed rejection to `REJECTED_FILE`.

    Mirrors `apply_promotions`'s shape (explicit human decision, explicit
    write, never called from the read-only aggregation path) but for the
    否决账本 instead of the promotion ledger -- module docstring previously
    described this file as "仅 CLI --apply 之外人工编辑"; this is that same
    human decision now reachable from 卡5's web 一键否决 button instead of a
    manual JSON edit, not a new automated write path.

    Idempotent: re-rejecting an already-rejected query is a no-op (returns
    `already_rejected=True`, does not duplicate the entry or touch the file).

    ⚠️ Known limitation (same as `LEDGER_FILE`, see module docstring point 1):
    this file is deployed via `rsync` of the code tree, not owned by the DB --
    a rejection written here by the web UI survives until the next Python
    deploy overwrites the working tree from git, unless a human commits
    `REJECTED_FILE` afterwards (same operational discipline the promotion
    ledger already requires). The API response's `durable=False` flags this
    so a caller (web UI) can surface "记得 commit 否决账本" rather than
    silently assuming the rejection is permanent.
    """
    query = (query or "").strip()
    if not query:
        return {"ok": False, "reason": "empty_query"}

    entries: List[Dict[str, str]] = []
    if REJECTED_FILE.exists():
        try:
            with open(REJECTED_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                entries = [e for e in data if isinstance(e, dict)]
        except Exception as exc:
            logger.warning(f"[restaurant-intent-promotion] reject: load existing rejected.json failed (treating as empty): {exc}")
            entries = []

    if any((e.get("query") or "").strip() == query for e in entries):
        return {"ok": True, "already_rejected": True, "ledger_path": str(REJECTED_FILE)}

    entry: Dict[str, Any] = {"query": query, "reason": (reason or "").strip() or "未说明原因"}
    if rejected_by:
        entry["rejected_by"] = rejected_by
    entries.append(entry)

    REJECTED_FILE.parent.mkdir(parents=True, exist_ok=True)
    REJECTED_FILE.write_text(
        json.dumps(entries, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return {
        "ok": True,
        "already_rejected": False,
        "ledger_path": str(REJECTED_FILE),
        "ledger_size": len(entries),
        "durable": False,
    }


# ─── Miss 复盘处理状态 (卡5b 补充契约: POST /misses/status) ─────────────────

def load_miss_status() -> Dict[str, Dict[str, Any]]:
    """Read `MISS_STATUS_FILE`. Fail-open: missing/corrupt file -> {} (same
    fail-open convention as `load_promoted_samples`/`load_rejected_queries`)."""
    try:
        if MISS_STATUS_FILE.exists():
            with open(MISS_STATUS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, dict):
                return {
                    str(q): v for q, v in data.items()
                    if isinstance(v, dict) and isinstance(q, str) and q.strip()
                }
    except Exception as exc:
        logger.warning(f"[restaurant-intent-promotion] load miss status failed (ignored): {exc}")
    return {}


def set_miss_status(
    query: str, status: str, *, note: Optional[str] = None,
    reviewed_by: Optional[str] = None,
) -> Dict[str, Any]:
    """Human-reviewed write for `/misses/status` (卡5 前端补充契约). Same
    write-discipline as `reject_candidate`: explicit human action, explicit
    write, never called from a read-only aggregation path.

    `status` must be one of `MISS_STATUS_VALUES` -- rejects (does not
    silently accept) an unrecognized value so the FE and this ledger never
    drift into "free text the backend can't group/filter on".

    ⚠️ Same rsync-deploy durability caveat as `REJECTED_FILE`/`LEDGER_FILE`
    (see module docstring point 1 and `reject_candidate`'s docstring):
    response includes `durable=False`.
    """
    query = (query or "").strip()
    if not query:
        return {"ok": False, "reason": "empty_query"}
    if status not in MISS_STATUS_VALUES:
        return {"ok": False, "reason": f"invalid_status:{status!r} (允许值: {sorted(MISS_STATUS_VALUES)})"}

    all_status = load_miss_status()
    entry: Dict[str, Any] = {
        "status": status,
        "updated_at": _dt.datetime.utcnow().isoformat() + "Z",
    }
    if note:
        entry["note"] = note
    if reviewed_by:
        entry["reviewed_by"] = reviewed_by
    all_status[query] = entry

    MISS_STATUS_FILE.parent.mkdir(parents=True, exist_ok=True)
    MISS_STATUS_FILE.write_text(
        json.dumps(all_status, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {"ok": True, "query": query, "status": status, "ledger_path": str(MISS_STATUS_FILE), "durable": False}
