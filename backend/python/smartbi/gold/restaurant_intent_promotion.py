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

Never silently graduates: `apply_promotions` is only ever invoked by a human
running the CLI's `--apply` flag with a file the human already reviewed.
Nothing in this module, `aggregate_candidates` included, writes on its own.
"""
from __future__ import annotations

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

# Two-level objective gate thresholds (spec section 5). Row-level filter (a)
# is always applied (tier=llm, contract_pass=true, served=true -- see the SQL
# in aggregate_candidates). Group-level recommendation (b) additionally
# requires either repeat occurrence or high single-shot confidence, and never
# recommends a group where different rows resolved to different codes
# (`conflict`) -- those still surface for human review, just unmarked.
_RECOMMEND_MIN_COUNT = 2
_RECOMMEND_MIN_CONFIDENCE = 0.85


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
        from smartbi.gold.restaurant_ops_router import SAMPLE_QUERIES
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
    factory_id: str = "DEMO_REST",
) -> List[Dict[str, Any]]:
    """Aggregate `smart_bi_llm_fallback_log` rows into promotion candidates.

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
            # 记忆里的裸 psql 坑)。显式事务级 set_config 后再查。
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
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
        from smartbi.gold.restaurant_intent import _VALID_CODES
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
    factory_id: str = "DEMO_REST",
) -> List[Dict[str, Any]]:
    """Aggregate delegate:false misses (哨兵 template_code='RESTAURANT_OPS_MISS',
    log_intent_miss 写入) -- 飞轮的另一半原料: tiered 没接住的问法。

    分组按 query 文本, 带 miss_reason 分布 (prefilter / should_delegate) 和
    spec_intent (should_delegate miss 时 T1-T3 实际解析出的意图, 有值说明
    "解析对了但路由拒了" -- 通常是 resolver 缺口或 A-3 类例外)。

    只读, fail-open []。RLS 同 aggregate_candidates: 查询前设 GUC。"""
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
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
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
    from smartbi.gold.restaurant_intent import _VALID_CODES

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
