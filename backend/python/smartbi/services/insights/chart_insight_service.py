"""Chart Auto-Insight — Tier 2 backend service (v4 — claims-pinning architecture).

Design: docs/superpowers/specs/2026-06-10-chart-insight-distillation-redesign.md §2 C1+C3

Architecture (v4):
  signature = SHA256(chartType|xDim|yMetric|agg|domain|dataPattern|permissionTier)
              NOTE: factoryId is NOT in the signature (cross-tenant signature sharing).

  get_insight serve flow:
    1. Cross-tenant guard: jwt_factory_id != ctx.factory_id → None
    2. budget_tracker is None → fail-closed (None + WARN)
    3. budget.check_budget blocked → None
    4. _call_llm(ctx) → structured dict {claims, finding, implication, suggestion} or None
    5. _validate_claims(llm_obj, ctx) — server-side recompute + numeric-adjacency gate → None rejects
    6. finance_hidden ¥ serve-gate: scan validated text with _ABSOLUTE_AMOUNT_RE → None if any match
    7. Return InsightResult(source="llm", tier=2)

  In-database template table (ai_insight_templates) is preserved for offline M4 curation;
  this service no longer reads from or writes to it at serve time.

🔒 RBAC (red-line):
  - factoryId ALWAYS from JWT (jwt_factory_id param), NOT from context.factory_id
  - Cross-tenant (jwt_factory_id != ctx.factory_id) → blocked immediately
  - permission_tier ALWAYS server-derived (from caller_role via API endpoint) — body ignored
  - finance_hidden callers: prompt excludes changeAmt + raw series; ¥ serve-gate hard-blocks
  - budget_tracker=None → fail-closed (no LLM, return None + WARN)
  - _validate_claims rejects hallucinated entity swaps and invented numbers (no-fake-data)
"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence

from .claim_recompute import recompute_claim
from ..distillation_capture import persist_distillation_sample, compute_input_hash

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Finance-sensitive yMetrics (spec §2.6)
FINANCE_METRICS: frozenset[str] = frozenset({
    "revenue", "margin", "profit", "cost",
})

# Roles allowed to see absolute ¥ values in insights (mirrors Java PRICE_VIEW_ROLES)
FINANCE_ROLES: frozenset[str] = frozenset({
    "factory_super_admin",
    "platform_admin",
    "procurement_manager",
    "finance_manager",
    "sales_manager",
    "dispatcher",
    "production_manager",
    "restaurant_manager",
    "restaurant_owner",
    "restaurant_purchaser",
    "permission_admin",
    "department_admin",
})

# Observation verbs are allowed; causal-prescriptive verbs are BANNED (spec §2.3)
_POISON_VERB_RE = re.compile(r"复制|引流|加大|扩张|推广")

# Regex to detect literal absolute ¥ amounts (e.g. "¥12345", "500万元")
_ABSOLUTE_AMOUNT_RE = re.compile(
    r"¥\s*\d|"           # ¥ followed by digit
    r"\d+\s*[万亿]?\s*元(?!\d)",  # numeric + 元 (not slot suffix)
    re.UNICODE,
)

# Default promote threshold (retained for test import compatibility)
DEFAULT_PROMOTE_THRESHOLD = 3


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass
class ChartInsightContext:
    """All inputs needed to compute a signature and generate/fill an insight."""
    chart_type: str          # e.g. "BAR", "LINE", "PIE"
    x_dim: str               # "time"|"store"|"product"|"channel"|"category"|"other"
    y_metric: str            # "revenue"|"quantity"|"margin"|"cost"|"count"|"pct"|"other"
    aggregation: str         # "sum"|"avg"|"max"|"count"
    domain: str              # "restaurant"|"factory"|"finance"
    data_pattern: str        # canonical bucket string e.g. "ranking:top-share:65-80:cat-count:4-8"
    permission_tier: str     # "finance_visible"|"price_hidden"|"finance_hidden"
    factory_id: str          # tenant identifier (from request body — OVERRIDDEN by JWT)
    series_values: List[float] = field(default_factory=list)
    series_labels: List[str] = field(default_factory=list)


@dataclass
class InsightResult:
    """Structured insight output — mirrors the frontend InsightResult interface."""
    finding: str
    implication: Optional[str] = None
    suggestion: Optional[str] = None
    source: str = "template"   # "template" | "llm"
    tier: int = 2
    input_hash: Optional[str] = None  # G4: corpus row key — exposed so frontend can send feedback


# ---------------------------------------------------------------------------
# Signature computation (spec §2.1)
# ---------------------------------------------------------------------------

def compute_signature(ctx: ChartInsightContext) -> str:
    """SHA256 of the canonical pipe-delimited feature string.

    U1.8: Format is chartType|xDim|yMetric|aggregation|domain|dataPattern|permissionTier
    factoryId is EXCLUDED — templates are cross-tenant shared.
    Safety: templates contain ONLY {slot} placeholders (zero tenant data).
    RBAC by construction: required_permission + permission_tier (server-derived) double-gate.
    """
    raw = "|".join([
        ctx.chart_type,
        ctx.x_dim,
        ctx.y_metric,
        ctx.aggregation,
        ctx.domain,
        ctx.data_pattern,
        ctx.permission_tier,
    ])
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _contains_poison(text: Optional[str]) -> bool:
    """Return True iff text contains a causal-prescriptive (banned) verb."""
    if not text:
        return False
    return bool(_POISON_VERB_RE.search(text))


def _extract_json_object(text: str) -> Optional[Dict[str, Any]]:
    """Robustly extract a JSON object from an LLM response.

    LLMs (e.g. glm-5.x) often wrap JSON in ```json fences or prepend reasoning
    text, so a bare json.loads(content) raises 'Expecting value: line 1 column 1'
    and the whole Tier2 distillation silently never captures. Strip markdown
    fences, try direct parse, then fall back to the first balanced {...} block.
    Returns None (honest) when nothing parseable is found.
    """
    if not text or not text.strip():
        return None
    s = text.strip()
    # Strip markdown code fences: ```json ... ``` or ``` ... ```
    if s.startswith("```"):
        s = re.sub(r"^```[a-zA-Z]*\s*", "", s)
        s = re.sub(r"\s*```\s*$", "", s).strip()
    # Direct parse
    try:
        obj = json.loads(s)
        return obj if isinstance(obj, dict) else None
    except json.JSONDecodeError:
        pass
    # Fallback: first balanced {...} block
    start = s.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(s)):
        if s[i] == "{":
            depth += 1
        elif s[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    obj = json.loads(s[start:i + 1])
                    return obj if isinstance(obj, dict) else None
                except json.JSONDecodeError:
                    return None
    return None


def _compute_slot_values(ctx: ChartInsightContext) -> Dict[str, Any]:
    """Derive concrete slot values from ctx.series_values + series_labels.

    Computes:
      topName, botName: highest/lowest-value label
      topShare: top value as % of total
      ratio: top/bottom ratio (1 decimal)
      concLevel: concentration tier label
      growthRate, changeAmt, changeDir: trend-oriented slots
    """
    values = ctx.series_values
    labels = ctx.series_labels
    slots: Dict[str, Any] = {}

    if not values:
        return slots

    total = sum(values)
    if len(values) >= 2 and labels:
        max_idx = values.index(max(values))
        min_idx = values.index(min(values))
        top_val = values[max_idx]
        bot_val = values[min_idx]
        slots["topName"] = labels[max_idx] if max_idx < len(labels) else f"第{max_idx+1}项"
        slots["botName"] = labels[min_idx] if min_idx < len(labels) else f"第{min_idx+1}项"
        slots["topShare"] = f"{round(top_val / total * 100, 1)}" if total > 0 else "0"
        if bot_val and bot_val != 0:
            slots["ratio"] = f"{round(top_val / bot_val, 1)}"
        else:
            slots["ratio"] = "—"
        top_pct = (top_val / total * 100) if total > 0 else 0
        if top_pct >= 80:
            slots["concLevel"] = "极高"
        elif top_pct >= 65:
            slots["concLevel"] = "偏高"
        else:
            slots["concLevel"] = "适中"

    # Trend slots — ONLY meaningful for a time-ordered series. For proportion/ranking charts
    # (x_dim=channel/category/store/...), first→last is not a trend; computing growthRate there
    # produces a meaningless number the LLM then reports (and which coincidentally collides with
    # other entities' shares). Gate on x_dim == "time".
    if ctx.x_dim == "time" and len(values) >= 2:
        first, last = values[0], values[-1]
        if first and first != 0:
            change_pct = round((last - first) / abs(first) * 100, 1)
            slots["growthRate"] = f"{change_pct}"
            slots["changeAmt"] = f"{round(last - first, 1)}"
            slots["changeDir"] = "上升" if last > first else "下降" if last < first else "持平"

    return slots


# ---------------------------------------------------------------------------
# C1.2 — claims-pinning: validate claims + numeric-adjacency gate (MF1)
# ---------------------------------------------------------------------------

# Stat types where the tolerance is applied as a ratio (not percentage-points)
_RATIO_STAT_TYPES: frozenset[str] = frozenset({"ratio"})

# Stat types that use pct-point tolerance (share/derived share types, growth)
_PCT_STAT_TYPES: frozenset[str] = frozenset({
    "value", "share", "top2_share", "complement", "diff", "growth", "count",
})

# Single-entity stats: prose attributes the number to ONE entity ("{entity}占{number}").
# Entity-adjacency is enforced only for these (entity-swap is the clear danger here).
# ratio/diff are relational (two entities); top2_share/growth/count are entity-agnostic.
_SINGLE_ENTITY_STATS: frozenset[str] = frozenset({"value", "share", "complement"})

# Regex to find Arabic numbers (integers and decimals) in prose
_ARABIC_NUMBER_RE = re.compile(r"\d+\.?\d*")


def _coerce_number(v: Any) -> Optional[float]:
    """Coerce an LLM-emitted claim value to float. Handles int/float and strings like
    '62', '62.0', '62%', '1.6倍', '￥38000', '3.8万', '约62'. Returns None if uncoercible."""
    if v is None:
        return None
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if not isinstance(v, str):
        return None
    s = v.strip().replace(",", "").replace("约", "").replace("近", "").replace("超", "")
    s = s.replace("¥", "").replace("￥", "").replace("%", "").replace("倍", "")
    mult = 1.0
    if s.endswith("万"):
        mult = 10000.0
        s = s[:-1]
    elif s.endswith("亿"):
        mult = 1e8
        s = s[:-1]
    m = re.search(r"-?\d+\.?\d*", s)
    if not m:
        return None
    try:
        return float(m.group()) * mult
    except ValueError:
        return None


def _validate_claims(
    llm_obj: dict,
    ctx: "ChartInsightContext",
    tolerance_pct: float = 1.0,
    tolerance_ratio: float = 0.3,
) -> Optional[dict]:
    """C1.2 — Validate LLM structured claims by server-side recomputation + numeric-adjacency gate.

    Algorithm:
      1. For each claim in llm_obj["claims"], recompute the true value from ctx.series_*.
         If recompute returns None OR abs(claim.value - true) > tolerance → drop the claim.
      2. Numeric-adjacency gate: scan finding+implication+suggestion prose for every Arabic
         number. Each number must:
           a) Match (within tolerance) at least one valid claim's value.
           b) For entity-bearing claims (entity is not None), the nearest entity-label
              occurrence in the prose to that number must equal the claim's entity.
         Any prose number failing either check → return None (reject entire response).
      3. Empty/missing claims → return None.
      4. All checks pass → return {"finding":…, "implication":…, "suggestion":…}.

    Args:
        llm_obj: Parsed LLM response dict with keys "claims", "finding", "implication",
                 "suggestion".
        ctx: ChartInsightContext carrying series_values and series_labels.
        tolerance_pct: Absolute tolerance (in same units as the stat, e.g. percentage points)
                       for non-ratio stats.
        tolerance_ratio: Absolute tolerance for ratio-type stats.

    Returns:
        Dict with finding/implication/suggestion if all claims and prose numbers are valid.
        None otherwise.
    """
    # Collect prose fields (finding is primary; require a non-empty finding).
    finding = llm_obj.get("finding")
    if not finding or not str(finding).strip():
        logger.info("[chart-insight] validate: empty finding (keys=%s)", list(llm_obj.keys()))
        return None
    prose_fields = [str(llm_obj.get(k)) for k in ("finding", "implication", "suggestion") if llm_obj.get(k)]
    full_prose = " ".join(prose_fields)

    # --- Gate: every Arabic number in the prose must anchor to a TRUE recomputed stat ---
    # (Fable MF1: validate against the full arithmetic closure of the series, not just the
    # LLM's emitted claims — the closure is dense enough that checking doesn't throttle the
    # LLM's intelligence; an invented number matches no true stat → reject.)
    numbers = list(_ARABIC_NUMBER_RE.finditer(full_prose))
    if not numbers:
        # A data insight must cite at least one (verifiable) number.
        logger.info("[chart-insight] validate: finding has no numbers to verify → reject")
        return None

    for match in numbers:
        num_str = match.group()
        try:
            num_val = float(num_str)
        except ValueError:
            continue
        char_pos = match.start()

        candidates = _truth_candidates(num_val, ctx, tolerance_pct, tolerance_ratio)
        if not candidates:
            # The number matches no true stat of the series → invented/hallucinated → reject.
            logger.info("[chart-insight] validate: prose number %s matches no true series stat → reject",
                        num_str)
            return None

        # Entity-adjacency — only when ALL candidates are single-entity stats (share/value/
        # complement). Relational (ratio/diff) or agnostic (top2_share/growth/count) candidates
        # mean the number legitimately has no single owner → numeric anchor suffices.
        single_entity = [c for c in candidates if c[1] in _SINGLE_ENTITY_STATS and c[0] is not None]
        other = [c for c in candidates if c[1] not in _SINGLE_ENTITY_STATS]
        if single_entity and not other:
            attributed = _nearest_preceding_label(full_prose, char_pos, ctx.series_labels)
            if attributed is None:
                attributed = _nearest_label_in_prose(full_prose, char_pos, ctx.series_labels)
            if not any(ent == attributed for ent, _ in single_entity):
                logger.info("[chart-insight] validate: number %s attributed=%r ≠ true-stat entities %s → reject",
                            num_str, attributed, [ent for ent, _ in single_entity])
                return None

    # All prose numbers anchored to true stats + correctly attributed.
    return {
        "finding": llm_obj.get("finding"),
        "implication": llm_obj.get("implication"),
        "suggestion": llm_obj.get("suggestion"),
    }


def _truth_candidates(
    num_val: float, ctx: "ChartInsightContext", tol_pct: float, tol_ratio: float
) -> List[tuple]:
    """Return [(entity, stat_type), ...] whose recomputed value ≈ num_val (within tolerance).

    Scans the full recompute closure: single-entity stats over every label, plus
    entity-agnostic stats. This is what makes the LLM's claims array advisory rather than
    load-bearing — any number the LLM uses that is a TRUE stat of the series anchors here.
    """
    cands: List[tuple] = []
    labels = ctx.series_labels or []
    # NOTE: 'complement' is intentionally excluded from the closure. In a 2-element series
    # complement(B) == share(A), so "B占{share(A)}%" (an entity-swap) would falsely anchor to
    # B's complement and bypass swap detection. The LLM states each entity's own share directly,
    # so excluding complement costs nothing while keeping the swap gate sound.
    for stat in ("value", "share", "ratio", "diff"):
        tol = tol_ratio if stat in _RATIO_STAT_TYPES else tol_pct
        for ent in labels:
            tv = recompute_claim(ent, stat, ctx.series_values, labels)
            if tv is not None and abs(num_val - tv) <= tol:
                cands.append((ent, stat))
            # also accept a fraction form (LLM wrote 0.62 for 62%) for pct stats
            elif tv is not None and stat not in _RATIO_STAT_TYPES and abs(num_val * 100 - tv) <= tol:
                cands.append((ent, stat))
    agnostic = ("top2_share", "count")
    if ctx.x_dim == "time":
        agnostic = ("top2_share", "growth", "count")  # growth only meaningful for time series
    for stat in agnostic:
        tol = tol_ratio if stat in _RATIO_STAT_TYPES else tol_pct
        tv = recompute_claim(None, stat, ctx.series_values, labels)
        if tv is not None and abs(num_val - tv) <= tol:
            cands.append((None, stat))
    return cands


def _nearest_preceding_label(prose: str, target_pos: int, labels: List[str]) -> Optional[str]:
    """Find the series label whose occurrence is nearest to target_pos but at-or-before it.

    Chinese BI prose attributes a number to the entity stated just before it
    ("{entity}占{number}%"). So the entity that owns a number is the closest label
    occurrence starting at index <= target_pos. Returns None if no label precedes.
    """
    best_label: Optional[str] = None
    best_start: int = -1
    for label in labels:
        if not label:
            continue
        start = 0
        while True:
            idx = prose.find(label, start)
            if idx == -1 or idx > target_pos:
                break
            if idx > best_start:  # latest-starting occurrence at-or-before target
                best_start = idx
                best_label = label
            start = idx + 1
    return best_label


def _nearest_label_in_prose(prose: str, target_pos: int, labels: List[str]) -> Optional[str]:
    """Find the series label whose occurrence in the prose is nearest (by char distance) to target_pos.

    For each label, find all occurrences in the prose. Compute the minimum char distance
    from target_pos to any occurrence of that label. Return the label with the smallest
    minimum distance.

    If a label is a substring of another (e.g. "外卖" vs "外卖平台"), we still do simple
    substring search; the shortest-distance match wins regardless of label length.

    Returns None if no label appears in the prose at all.
    """
    best_label: Optional[str] = None
    best_dist: int = len(prose) + 1  # sentinel: larger than any real distance

    for label in labels:
        if not label:
            continue
        start = 0
        while True:
            idx = prose.find(label, start)
            if idx == -1:
                break
            # Distance from target_pos to the start of this occurrence
            # (could also measure to end; start is fine for nearest-mention intent)
            dist = abs(idx - target_pos)
            if dist < best_dist:
                best_dist = dist
                best_label = label
            start = idx + 1  # look for next occurrence

    return best_label


# ---------------------------------------------------------------------------
# C2 — corpus helpers: domain mapping + stable data-rich input_text (MF2/MF4)
# ---------------------------------------------------------------------------

_DOMAIN_TO_BUSINESS_TYPE: dict = {
    "restaurant": "restaurant",
    "finance":    "factory",
    "factory":    "factory",
}


def _map_domain(domain: Optional[str]) -> str:
    """Map chart domain to corpus business_type bucket.

    restaurant → "restaurant"
    finance    → "factory"   (finance charts are factory-side)
    factory    → "factory"
    anything else / None → "unknown"
    """
    if not domain:
        return "unknown"
    return _DOMAIN_TO_BUSINESS_TYPE.get(domain, "unknown")


def _corpus_input_text(ctx: "ChartInsightContext") -> str:
    """Build a stable, data-rich string that is used as corpus input_text and read-back cache key.

    Spec §2 C2 (MF4): "input_text 含真实 series 绝对值: 避免同分布跨租户 input_hash 撞覆盖 + 给模型真数据".

    Includes:
    - chart structural features (chart_type, x_dim, y_metric, aggregation, domain, data_pattern)
    - permission_tier (different tiers get different corpus rows)
    - factory_id (tenant-specific: different factories → different hash → no cross-tenant collision)
    - ABSOLUTE series_values and series_labels (key: prevents same-distribution different-tenant collision)

    The resulting sha256 hash (compute_input_hash(text)) is used both:
    - As the dedup key for INSERT ON CONFLICT in smart_bi_distillation_samples
    - As the read-back cache lookup: SELECT WHERE input_hash=$1 AND created_at::date=CURRENT_DATE
    """
    labels_str = ",".join(ctx.series_labels) if ctx.series_labels else ""
    values_str = ",".join(str(v) for v in ctx.series_values) if ctx.series_values else ""
    return (
        f"chart_insight|{ctx.chart_type}|{ctx.x_dim}|{ctx.y_metric}|{ctx.aggregation}"
        f"|{ctx.domain}|{ctx.data_pattern}|{ctx.permission_tier}"
        f"|factory:{ctx.factory_id}"
        f"|labels:[{labels_str}]"
        f"|values:[{values_str}]"
    )


# SQL for reading back a today's corpus row by input_hash (MF4).
# smart_bi_distillation_samples has NO RLS (per audit) — plain query is safe.
# Filter by factory-baked input_hash so cross-tenant collision is impossible.
# G4: also fetch metadata to check feedback_down signal; skip rows already demoted.
_CORPUS_READ_SQL = """
    SELECT teacher_output, input_hash, metadata
    FROM smart_bi_distillation_samples
    WHERE input_hash = $1
      AND created_at::date = CURRENT_DATE
    LIMIT 1
"""

# G4 feedback demote threshold: once a row accumulates this many downvotes, stop serving it.
_FEEDBACK_DOWN_SERVE_THRESHOLD = 2

# System prompt used in _call_llm — duplicated here so corpus persist can store it.
_SYSTEM_PROMPT = (
    "你是一名数据分析师，专门为ERP系统的图表生成结构化洞察。"
    "你的输出必须是严格的JSON格式（不含代码块）。"
    "使用观察动词（关注/排查/分析/了解），禁止因果归因动词（复制/引流/加大/扩张/推广）。"
    "如果数据不足以得出有意义的业务观察，则将claims返回空数组[]，finding返回null，不要编造。"
)


# ---------------------------------------------------------------------------
# Main service class
# ---------------------------------------------------------------------------

class ChartInsightService:
    """Tier 2 insight service: LLM claims-pinning serve (v4).

    🔒 RBAC contract (enforced here, not only at endpoint):
    - jwt_factory_id MUST be passed and used as the authoritative tenant identifier.
    - If jwt_factory_id != ctx.factory_id → blocked immediately (cross-tenant guard).
    - permission_tier ALWAYS server-derived (from caller_role via API endpoint) — body ignored.
    - finance_hidden: prompt excludes changeAmt + raw series; ¥ serve-gate hard-blocks output.
    - budget_tracker=None → fail-closed (return None + WARN, no LLM call).
    - _validate_claims rejects hallucinated entity swaps and invented numbers (no-fake-data).
    """

    def __init__(
        self,
        pool,  # asyncpg Pool (retained for future Task 5 corpus persist)
        budget_tracker,  # AgentBudgetTracker or compatible
        promote_threshold: int = DEFAULT_PROMOTE_THRESHOLD,
    ):
        self._pool = pool
        self._budget_tracker = budget_tracker
        self._promote_threshold = promote_threshold

    # ------------------------------------------------------------------
    # Public entry point
    # ------------------------------------------------------------------

    async def get_insight(
        self,
        ctx: ChartInsightContext,
        *,
        caller_role: Optional[str] = None,
        jwt_factory_id: Optional[str] = None,
    ) -> Optional[InsightResult]:
        """Return an InsightResult or None (data insufficient, hallucination, or blocked).

        Serve flow (v4 claims-pinning):
          1. Cross-tenant guard: jwt_factory_id != ctx.factory_id → None
          2. budget_tracker None → fail-closed (None + WARN)
          3. budget.check_budget blocked → None
          4. _call_llm(ctx) → structured {claims, finding, implication, suggestion} or None
          5. _validate_claims(llm_obj, ctx) → None rejects hallucinated values
          6. finance_hidden ¥ serve-gate: _ABSOLUTE_AMOUNT_RE scan validated text → None if match
          7. Return InsightResult(source="llm", tier=2)

        jwt_factory_id: factory_id from verified JWT; if provided, OVERRIDES ctx.factory_id.
        caller_role: role string from JWT for RBAC permission gating.
        """
        # 🔒 RBAC: JWT factory_id overrides body factory_id
        effective_factory_id = jwt_factory_id if jwt_factory_id is not None else ctx.factory_id

        # 🔒 Cross-tenant guard
        if jwt_factory_id is not None and ctx.factory_id != jwt_factory_id:
            logger.warning(
                "[chart-insight] cross-tenant blocked: jwt=%s body=%s",
                jwt_factory_id, ctx.factory_id,
            )
            return None

        # Build resolved context with authoritative factory_id
        resolved_ctx = ChartInsightContext(
            chart_type=ctx.chart_type,
            x_dim=ctx.x_dim,
            y_metric=ctx.y_metric,
            aggregation=ctx.aggregation,
            domain=ctx.domain,
            data_pattern=ctx.data_pattern,
            permission_tier=ctx.permission_tier,
            factory_id=effective_factory_id,
            series_values=ctx.series_values,
            series_labels=ctx.series_labels,
        )

        # budget_tracker None → fail-closed
        if self._budget_tracker is None:
            logger.warning(
                "[chart-insight] budget_tracker is None — fail-closed: returning None without calling LLM"
            )
            return None

        # Budget check
        budget = await self._budget_tracker.check_budget(effective_factory_id)
        if budget.blocked:
            logger.info("[chart-insight] budget blocked for factory=%s", effective_factory_id)
            return None

        # C2 MF4: compute stable data-rich input_text (used as corpus key + read-back cache key)
        input_text = _corpus_input_text(resolved_ctx)
        input_hash = compute_input_hash(input_text)

        # C2 MF4: read-back cache — check if today's corpus already has an accepted output
        # for this exact (factory-baked) input. If yes, re-run the ¥ serve-gate for the
        # CURRENT caller's tier and return from cache (0 LLM).
        # G4: _read_corpus_cache returns (teacher_output, row_input_hash) or None.
        #     None means cache miss OR serve-skip (👎 demoted row).
        cache_result = await self._read_corpus_cache(input_hash)
        if cache_result is not None:
            cached_teacher_output, cached_row_hash = cache_result
            cached_obj = _extract_json_object(cached_teacher_output)
            if cached_obj is not None:
                # Re-run ¥ serve-gate for current caller's tier
                permission_tier = resolved_ctx.permission_tier
                if permission_tier == "finance_hidden":
                    combined_text = " ".join(filter(None, [
                        cached_obj.get("finding"),
                        cached_obj.get("implication"),
                        cached_obj.get("suggestion"),
                    ]))
                    if _ABSOLUTE_AMOUNT_RE.search(combined_text):
                        # Cached output fails gate for this tier — fall through to LLM
                        logger.info(
                            "[chart-insight] corpus cache hit but ¥ gate fails for tier=%s, "
                            "falling through to LLM", permission_tier
                        )
                    else:
                        logger.debug("[chart-insight] corpus cache hit (input_hash=%s...)", input_hash[:12])
                        return InsightResult(
                            finding=cached_obj.get("finding"),
                            implication=cached_obj.get("implication"),
                            suggestion=cached_obj.get("suggestion"),
                            source="cache",
                            tier=2,
                            input_hash=cached_row_hash,  # G4: expose for feedback
                        )
                else:
                    logger.debug("[chart-insight] corpus cache hit (input_hash=%s...)", input_hash[:12])
                    return InsightResult(
                        finding=cached_obj.get("finding"),
                        implication=cached_obj.get("implication"),
                        suggestion=cached_obj.get("suggestion"),
                        source="cache",
                        tier=2,
                        input_hash=cached_row_hash,  # G4: expose for feedback
                    )

        # LLM call: returns structured {claims, finding, implication, suggestion} or None
        llm_obj = await self._call_llm(resolved_ctx)

        # C5: count tokens regardless of LLM success/failure — provider charges on attempt,
        # so budget must reflect all LLM calls to prevent undercounting.
        await self._budget_tracker.consume(effective_factory_id, 800)

        if llm_obj is None:
            return None

        # C1.2: claims-pinning validation (recompute + numeric-adjacency gate)
        validated = _validate_claims(llm_obj, resolved_ctx)
        if validated is None:
            logger.info(
                "[chart-insight] _validate_claims rejected LLM response (hallucination or no claims)"
            )
            return None

        # C1.3 / MF5: finance_hidden ¥ serve-gate — hard-block any ¥ literal in output
        permission_tier = resolved_ctx.permission_tier
        if permission_tier == "finance_hidden":
            combined_text = " ".join(filter(None, [
                validated.get("finding"),
                validated.get("implication"),
                validated.get("suggestion"),
            ]))
            if _ABSOLUTE_AMOUNT_RE.search(combined_text):
                logger.info(
                    "[chart-insight] finance_hidden ¥ serve-gate: rejected absolute ¥ in output"
                )
                return None

        # C2 MF2: gate-passed → persist accepted output into corpus (fire-and-forget).
        # Only accepted outputs are stored. Rejected paths (validate None / ¥-gate None)
        # return before this point and never reach here.
        # persist_distillation_sample never raises (swallows all exceptions internally),
        # so awaiting it directly is safe and does not risk the user-facing response.
        await persist_distillation_sample(
            self._pool,
            source="chart_insight",
            task_type="insights",
            input_text=input_text,
            teacher_output=json.dumps(validated, ensure_ascii=False),
            business_type=_map_domain(resolved_ctx.domain),
            factory_id=resolved_ctx.factory_id,
            system_prompt=_SYSTEM_PROMPT,
            teacher_model=self._get_teacher_model(),
            quality=5,  # claims-pinning verified: highest quality tier
            metadata={
                "permission_tier": resolved_ctx.permission_tier,
                "gate": "passed",
            },
        )

        return InsightResult(
            finding=validated["finding"],
            implication=validated.get("implication"),
            suggestion=validated.get("suggestion"),
            source="llm",
            tier=2,
            input_hash=input_hash,  # G4: expose for feedback
        )

    # ------------------------------------------------------------------
    # C2 MF4 helpers: corpus read-back cache + teacher model name
    # ------------------------------------------------------------------

    async def _read_corpus_cache(self, input_hash: str) -> Optional[tuple]:
        """Read back today's corpus row by input_hash.

        Returns (teacher_output: str, row_input_hash: str) or None.

        Serve-skip (G4): if the row's metadata.feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD,
        the row has been 👎'd enough times to be excluded from serving — returns None so the
        caller falls through to LLM for a fresh answer.

        On any error (pool None, DB error, non-string result, etc.) → returns None and logs.
        Never raises — cache miss gracefully falls through to LLM.
        """
        if self._pool is None:
            return None
        try:
            async with self._pool.acquire() as conn:
                row = await conn.fetchrow(_CORPUS_READ_SQL, input_hash)
                if row is None:
                    return None
                teacher_output = row["teacher_output"]
                row_hash = row["input_hash"]
                if not isinstance(teacher_output, str):
                    # Unexpected type (e.g. mock returning int in tests) — treat as miss
                    return None
                # G4 serve-skip: check feedback_down in metadata
                raw_meta = row["metadata"]
                try:
                    meta_dict = raw_meta if isinstance(raw_meta, dict) else (
                        json.loads(raw_meta) if raw_meta else {}
                    )
                    feedback_down = int(meta_dict.get("feedback_down", 0))
                    feedback_up = int(meta_dict.get("feedback_up", 0))
                    if feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD or feedback_down > feedback_up:
                        if feedback_down >= _FEEDBACK_DOWN_SERVE_THRESHOLD:
                            logger.info(
                                "[chart-insight] serve-skip: row input_hash=%s... has "
                                "feedback_down=%d >= threshold=%d — falling through to LLM",
                                input_hash[:12], feedback_down, _FEEDBACK_DOWN_SERVE_THRESHOLD,
                            )
                            return None
                except Exception as meta_exc:
                    # Metadata parse failure → treat as no-feedback (safe to serve)
                    logger.debug("[chart-insight] metadata parse failed (non-blocking): %s", meta_exc)
                return (teacher_output, row_hash)
        except Exception as exc:
            logger.warning("[chart-insight] corpus cache read failed (non-blocking): %s", exc)
            return None

    def _get_teacher_model(self) -> str:
        """Return the teacher model name for corpus metadata.

        Respects the CHART_INSIGHT_LLM_SLOT env override (e.g. REVIEW → qwen3-max)
        the same way _call_llm does — so the recorded teacher_model reflects which
        model actually generated the output, not always 'chart'.

        Resolution order:
          1. Resolve effective slot: CHART_INSIGHT_LLM_SLOT env → SLOT[name], else SLOT.CHART.
          2. Ask the router for the actual model string for that slot (via get_model_for_slot
             if available).
          3. Fall back to the slot name (lower-case) if model string unavailable.
          4. Fall back to 'unknown' on any import/attribute error.
        """
        try:
            from common.llm_router import SLOT  # noqa: PLC0415
        except Exception:
            return "unknown"

        # Resolve the effective slot (mirrors _call_llm logic exactly)
        effective_slot = SLOT.CHART
        _slot_name_env = os.getenv("CHART_INSIGHT_LLM_SLOT")
        if _slot_name_env:
            try:
                effective_slot = SLOT[_slot_name_env.strip().upper()]
            except (KeyError, TypeError):
                pass  # unknown env value → keep SLOT.CHART

        # Attempt to get the actual resolved model name from the router
        try:
            from common.llm_router import get_model_for_slot  # noqa: PLC0415
            model_name = get_model_for_slot(effective_slot)
            if model_name and isinstance(model_name, str):
                return model_name
        except Exception:
            pass

        # Fall back to the slot enum value (e.g. 'review', 'chart')
        try:
            if hasattr(effective_slot, "value"):
                return str(effective_slot.value)
            return str(effective_slot).lower()
        except Exception:
            return "unknown"

    # ------------------------------------------------------------------
    # LLM call — returns structured claims dict (overridable for testing)
    # ------------------------------------------------------------------

    async def _call_llm(self, ctx: ChartInsightContext) -> Optional[Dict[str, Any]]:
        """Call the LLM and return structured {claims, finding, implication, suggestion}, or None.

        Prompt (built by _build_insight_prompt) instructs the LLM to return a structured
        claims-pinning JSON:
          {"claims":[{"entity":..,"stat_type":..,"value":..}],
           "finding":.., "implication":.., "suggestion":..}

        If data is insufficient for a meaningful observation → LLM should return claims=[] and
        finding=null (no fabrication). Returns None on LLM failure or unparseable response.
        """
        try:
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context
        except ImportError:
            logger.warning("[chart-insight] llm_router not available, skipping LLM call")
            return None

        prompt = _build_insight_prompt(ctx, ctx.permission_tier)

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": _SYSTEM_PROMPT,
                },
                {"role": "user", "content": prompt},
            ],
            "response_format": {"type": "json_object"},
            "temperature": 0.1,
            "max_tokens": 2000,
        }

        # Distillation teacher override: the seeder/sweeps set CHART_INSIGHT_LLM_SLOT=REVIEW
        # to route corpus generation through a HIGH-QUALITY teacher (qwen3-max) instead of the
        # fast live-serve model (glm-5/SLOT.CHART). Teacher quality = corpus quality = (via
        # serve-from-corpus read-back) user-facing quality. Live serve (no env) keeps SLOT.CHART
        # for low latency. Quota: qwen3-max(REVIEW) healthy across aliyun_b/c.
        _slot = SLOT.CHART
        _slot_name = os.getenv("CHART_INSIGHT_LLM_SLOT")
        if _slot_name:
            try:
                _slot = SLOT[_slot_name.strip().upper()]
            except KeyError:
                logger.warning("[chart-insight] unknown CHART_INSIGHT_LLM_SLOT=%r, using CHART", _slot_name)
        try:
            with llm_caller_context("chart_insight", factory_id=ctx.factory_id):
                resp = await call_chain(_slot, payload)
        except Exception as exc:
            logger.warning("[chart-insight] LLM call failed: %s", exc)
            return None

        if resp is None:
            return None

        try:
            choices = resp.get("choices", [])
            if not choices:
                return None
            content = choices[0].get("message", {}).get("content", "")
            parsed = _extract_json_object(content)
            if parsed is None:
                logger.warning(
                    "[chart-insight] LLM response had no parseable JSON object (len=%d)",
                    len(content or ""),
                )
            return parsed
        except (KeyError, IndexError, TypeError) as exc:
            logger.warning("[chart-insight] LLM response parse failed: %s", exc)
            return None


# ---------------------------------------------------------------------------
# C1.3 — finance_hidden stat whitelist (MF5)
# ---------------------------------------------------------------------------

# Keys that are safe for finance_hidden callers (relative / non-absolute stats only).
# changeAmt = last - first is an absolute ¥ delta; must be excluded for finance_hidden.
# Raw series_values are also absolute — they are never put in the prompt stats dict directly,
# but _stats_for_tier enforces the whitelist at the slot-dict level.
_FINANCE_HIDDEN_STAT_WHITELIST: frozenset[str] = frozenset({
    "topName",
    "botName",
    "topShare",
    "ratio",
    "concLevel",
    "growthRate",
})


def _stats_for_tier(slots: Dict[str, Any], permission_tier: str) -> Dict[str, Any]:
    """Filter computed slot values for the given permission tier (MF5 RBAC).

    finance_hidden callers must NOT be fed absolute ¥ amounts.
    - 'changeAmt' (= last - first in series, an absolute amount) is excluded.
    - 'changeDir' is excluded when changeAmt is (it references the direction of the absolute move).
    - Raw series values are never in 'slots', so no special handling needed there.
    - All other tiers (finance_visible, price_hidden, …) receive the full slot dict unchanged.

    Args:
        slots: dict returned by _compute_slot_values(ctx).
        permission_tier: server-derived permission tier string.

    Returns:
        Filtered dict safe to feed into the LLM prompt for this tier.
    """
    if permission_tier != "finance_hidden":
        return slots  # finance_visible and any other tier: no filtering

    # finance_hidden: return only the whitelisted relative stats
    return {k: v for k, v in slots.items() if k in _FINANCE_HIDDEN_STAT_WHITELIST}


# ---------------------------------------------------------------------------
# LLM prompt builder (C1.3 — structured-claims contract)
# ---------------------------------------------------------------------------

def _build_insight_prompt(ctx: ChartInsightContext, permission_tier: str) -> str:
    """Build the structured-claims prompt for Tier2b LLM call (C1.3).

    Key changes vs the old slot-template prompt:
    - Instructs the LLM to return *structured claims* in a JSON envelope:
        {"claims":[{"entity":..,"stat_type":..,"value":..}],
         "finding":.., "implication":.., "suggestion":..}
      where stat_type ∈ {value,share,top2_share,complement,ratio,diff,growth,count}.
    - Provides the server-computed stats (tier-filtered via _stats_for_tier) as reference
      so the LLM can choose which claims to make — but number values must come from the
      stats dict, not be invented.
    - finance_hidden: raw series_values are NOT embedded; only relative stats are provided
      (MF5 — no absolute ¥ leak to the LLM which would echo them into prose).
    - finance_visible: may receive changeAmt and the full stats dict.

    Args:
        ctx: ChartInsightContext (series_values and series_labels used to compute stats).
        permission_tier: server-derived permission tier (NOT from request body).

    Returns:
        Prompt string ready to pass to the LLM.
    """
    raw_slots = _compute_slot_values(ctx)
    safe_stats = _stats_for_tier(raw_slots, permission_tier)

    # Build a human-readable stats summary from the filtered safe stats
    stats_lines = []
    for k, v in safe_stats.items():
        stats_lines.append(f"  {k}: {v}")
    stats_summary = "\n".join(stats_lines) if stats_lines else "  (无可用统计数据)"

    # For finance_visible, also provide context about value scale — but NOT for finance_hidden
    series_info = ""
    if permission_tier != "finance_hidden" and ctx.series_values:
        series_info = (
            f"\n数据规模参考（仅供理解量级，禁止直接引用原始数值到声明中）：\n"
            f"  数据点数量: {len(ctx.series_values)}\n"
            f"  维度标签: {', '.join(ctx.series_labels[:5]) if ctx.series_labels else '(无)'}\n"
        )

    return f"""请为以下图表生成结构化洞察（JSON格式）。

图表信息：
- 图表类型: {ctx.chart_type}
- X轴维度: {ctx.x_dim}
- Y轴指标: {ctx.y_metric}
- 聚合方式: {ctx.aggregation}
- 业务域: {ctx.domain}
- 数据模式: {ctx.data_pattern}

服务端已计算的统计摘要（你的声明数值必须来自这里，不得编造其他数字）：
{stats_summary}{series_info}

**输出格式**（严格JSON，禁止代码块）：
{{
  "claims": [
    {{"entity": "实体名或null（如"堂食"）", "stat_type": "share", "value": 62.0}},
    {{"entity": null, "stat_type": "top2_share", "value": 80.0}}
  ],
  "finding": "观察发现句（散文，数值必须来自上面 claims，不得引入未声明的数字）",
  "implication": "含义句（可选，可为null）",
  "suggestion": "建议句（可选，可为null；只用观察动词：关注/排查/分析/了解）"
}}

stat_type 取值范围（必须是以下之一）：
  value（绝对值）、share（占比%）、top2_share（前两名合计占比%）、
  complement（1-topShare，尾部占比%）、ratio（倍数）、diff（差值）、
  growth（增长率%）、count（数量）

重要规则：
1. claims 中的每个 value 必须来自上方"服务端已计算的统计摘要"，不得编造。
2. finding/implication/suggestion 中出现的**每个阿拉伯数字**必须在 claims 中有对应条目。
3. 建议只用观察动词（关注/排查/分析/了解），严禁因果归因（复制/引流/加大/扩张/推广）。
4. 如果数据不足以得出有意义的业务观察，claims 返回空数组 []，finding 返回 null。
5. 只返回 JSON，不要任何解释或代码块。"""
