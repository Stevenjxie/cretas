"""Chart Auto-Insight — Tier 2 backend service (U4 + U1 hardening).

Design: docs/superpowers/specs/2026-06-10-chart-auto-insight-design.md §2.1/§2.2/§2.6

Architecture:
  signature = SHA256(chartType|xDim|yMetric|agg|domain|dataPattern|permissionTier)
              NOTE: factoryId is NOT in the signature (U1.8 — cross-tenant template sharing).
              Security: templates contain ONLY parameterized {slots} — zero tenant data.
              Required_permission + permission_tier double-gate ensure RBAC by construction.
  Tier2a: lookup ai_insight_templates (is_active=true, sig-only, no factory_id scope) → fill
  Tier2b: budget check → LLM structured JSON → capture upsert → maybe_promote
  Distillation: proposal_count >= PROMOTE_THRESHOLD AND validate + poison_guard (ALL 3 fields)
                AND (finding-only → auto; suggestion → needs is_verified)

🔒 RBAC (red-line):
  - factoryId ALWAYS from JWT (jwt_factory_id param), NOT from context.factory_id
  - Cross-tenant (jwt_factory_id != ctx.factory_id) → blocked immediately
  - finance yMetric + required_permission='finance:read_write' →
    only roles in FINANCE_ROLES get the template; others → null
  - permissionTier BAKED into signature → templates never cross permission tiers
  - permissionTier ALWAYS server-derived (from caller_role via API endpoint) — body ignored
  - _safe_fill: any unfilled {slot} in result → field returns None (never show raw {slot})
  - poison + absolute ¥ checked on ALL THREE fields (finding+implication+suggestion)
  - budget_tracker=None → fail-closed (no LLM, return None + WARN)
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence

from .claim_recompute import recompute_claim

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

# Detect unparameterized numeric literals (raw numbers outside {} placeholders)
# Heuristic: 2+ digit standalone number not inside a {slot}
_UNSLOTTED_NUMBER_RE = re.compile(r"(?<!\{)\b\d{2,}\b(?!\})")

# Detect slot placeholders — any {word} markers
_SLOT_RE = re.compile(r"\{[^}]+\}")

# Default promote threshold (configurable per-factory; demo=1, prod=3)
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


# ---------------------------------------------------------------------------
# Template validation helpers (spec §2.2)
# ---------------------------------------------------------------------------

def validate_template_parameterization(finding_tpl: str) -> bool:
    """Return True iff the template is fully parameterized.

    Rules:
    - Must not be empty.
    - Must contain at least one {slot} placeholder (otherwise it's a literal sentence).
    - Must not contain absolute ¥ amounts (e.g. ¥12345 or 500万元).
    - Must not contain unslotted 2+ digit standalone numbers (literal data baked in).

    Only the finding_tpl is required to have slots; implication/suggestion can be
    prose with observation verbs as long as they don't bake in literal numbers.
    """
    if not finding_tpl or not finding_tpl.strip():
        return False

    # Must have at least one {placeholder}
    if not _SLOT_RE.search(finding_tpl):
        return False

    # Must not contain absolute ¥ amounts
    if _ABSOLUTE_AMOUNT_RE.search(finding_tpl):
        return False

    # Must not contain standalone 2+ digit numbers outside slots
    # (strip out slot contents first to avoid false positives on {slot123})
    stripped = _SLOT_RE.sub("", finding_tpl)
    if _UNSLOTTED_NUMBER_RE.search(stripped):
        return False

    return True


def _contains_poison(text: Optional[str]) -> bool:
    """Return True iff text contains a causal-prescriptive (banned) verb."""
    if not text:
        return False
    return bool(_POISON_VERB_RE.search(text))


# ---------------------------------------------------------------------------
# Slot filling (spec §2.2 — apply template to concrete series data)
# ---------------------------------------------------------------------------

def _fill_slots(template: str, slot_values: Dict[str, Any]) -> str:
    """Replace {placeholder} markers with computed values."""
    result = template
    for k, v in slot_values.items():
        result = result.replace("{" + k + "}", str(v))
    return result


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


def _safe_fill(template: str, slot_values: Dict[str, Any]) -> Optional[str]:
    """Fill slots and return None if any {slot} placeholder remains unfilled.

    U1.2 safety net: after filling all known slot values, scan the result with
    _SLOT_RE. Any remaining {word} means an unknown/illegal slot was in the
    template (LLM hallucinated a slot name not in the whitelist). Return None
    instead of returning a string containing raw {placeholder} markers to the user.

    Returns:
        Filled string if all placeholders resolved.
        None if any {slot} marker remains after filling.
    """
    filled = _fill_slots(template, slot_values)
    if _SLOT_RE.search(filled):
        logger.warning(
            "[chart-insight] _safe_fill: unfilled slot(s) remain in template: %r",
            filled,
        )
        return None
    return filled


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

    # Trend slots
    if len(values) >= 2:
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

# Regex to find Arabic numbers (integers and decimals) in prose
_ARABIC_NUMBER_RE = re.compile(r"\d+\.?\d*")


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
    claims = llm_obj.get("claims")
    if not claims:
        return None

    # --- Step 1: validate each claim by recomputation ---
    valid_claims: List[dict] = []
    for claim in claims:
        entity = claim.get("entity")  # may be None for entity-agnostic stats
        stat_type = claim.get("stat_type", "")
        claimed_value = claim.get("value")

        if claimed_value is None:
            continue  # skip malformed

        true_value = recompute_claim(entity, stat_type, ctx.series_values, ctx.series_labels)
        if true_value is None:
            # Cannot recompute (entity missing or stat unknown) → drop
            continue

        tol = tolerance_ratio if stat_type in _RATIO_STAT_TYPES else tolerance_pct
        if abs(claimed_value - true_value) <= tol:
            valid_claims.append(claim)
        # else: drop (claimed value doesn't match recomputed true value)

    # If no claims survived validation, reject
    if not valid_claims:
        return None

    # --- Step 2: numeric-adjacency gate ---
    # Collect all prose fields
    prose_fields = []
    for key in ("finding", "implication", "suggestion"):
        val = llm_obj.get(key)
        if val:
            prose_fields.append(str(val))
    full_prose = " ".join(prose_fields)

    # For each Arabic number in the prose, check it's anchored to a valid claim
    for match in _ARABIC_NUMBER_RE.finditer(full_prose):
        num_str = match.group()
        num_val = float(num_str)
        char_pos = match.start()

        # Find the valid claim(s) whose value matches this number within tolerance
        matching_claims = []
        for vc in valid_claims:
            stat_type = vc.get("stat_type", "")
            tol = tolerance_ratio if stat_type in _RATIO_STAT_TYPES else tolerance_pct
            if abs(num_val - vc["value"]) <= tol:
                matching_claims.append(vc)

        if not matching_claims:
            # This number has no valid claim anchor → reject
            return None

        # For entity-bearing claims (entity is not None), check entity adjacency
        # At least one matching claim must have its entity as the nearest label in the prose
        entity_bearing = [mc for mc in matching_claims if mc.get("entity") is not None]
        if entity_bearing:
            # Find which label is nearest to this number in the prose (by char distance)
            nearest_label = _nearest_label_in_prose(full_prose, char_pos, ctx.series_labels)
            # Check if nearest label matches at least one entity-bearing matching claim
            has_entity_match = any(
                mc["entity"] == nearest_label for mc in entity_bearing
            )
            if not has_entity_match:
                # The nearest entity in prose doesn't match the claim's entity → reject
                return None
        # else: entity-agnostic claims (growth/count/top2_share) — no entity check needed

    # All checks passed
    return {
        "finding": llm_obj.get("finding"),
        "implication": llm_obj.get("implication"),
        "suggestion": llm_obj.get("suggestion"),
    }


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
# Main service class
# ---------------------------------------------------------------------------

class ChartInsightService:
    """Tier 2 insight service: library lookup → LLM structured fallback → distillation.

    🔒 RBAC contract (enforced here, not only at endpoint):
    - jwt_factory_id MUST be passed and used as the authoritative tenant identifier.
    - If jwt_factory_id != ctx.factory_id → blocked (cross-tenant guard).
    - Templates with required_permission='finance:read_write' →
      only caller_role in FINANCE_ROLES may receive them.
    """

    def __init__(
        self,
        pool,  # asyncpg Pool
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
        """Return an InsightResult or None (data insufficient or blocked).

        jwt_factory_id: the factory_id extracted from the verified JWT.
                        If provided, it OVERRIDES ctx.factory_id for all DB ops.
                        If omitted (None) — use ctx.factory_id directly
                        (only for internal/test calls where JWT is not present).

        caller_role: role string from JWT for RBAC permission gating.
        """
        # 🔒 RBAC: JWT factory_id overrides body factory_id
        effective_factory_id = jwt_factory_id if jwt_factory_id is not None else ctx.factory_id

        # 🔒 Cross-tenant guard: if JWT says F001 but body says F002, block it
        if jwt_factory_id is not None and ctx.factory_id != jwt_factory_id:
            logger.warning(
                "[chart-insight] cross-tenant blocked: jwt=%s body=%s",
                jwt_factory_id, ctx.factory_id
            )
            return None

        # Build a resolved context with the authoritative factory_id
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

        sig = compute_signature(resolved_ctx)

        # Tier 2a: library lookup
        result = await self._lookup_template(sig, resolved_ctx, caller_role)
        if result is not None:
            return result

        # U1.3: budget None guard — fail-closed (no LLM, return None + WARN)
        if self._budget_tracker is None:
            logger.warning(
                "[chart-insight] budget_tracker is None (pool not initialized) — "
                "fail-closed: returning None without calling LLM"
            )
            return None

        # Tier 2b: budget check → LLM
        budget = await self._budget_tracker.check_budget(effective_factory_id)
        if budget.blocked:
            logger.info("[chart-insight] budget blocked for factory=%s", effective_factory_id)
            return None

        llm_resp = await self._call_llm(resolved_ctx)
        if llm_resp is None:
            return None

        finding_tpl = llm_resp.get("finding_tpl")
        if not finding_tpl:
            # LLM reported data insufficient — no fabrication
            return None

        implication_tpl = llm_resp.get("implication_tpl")
        suggestion_tpl = llm_resp.get("suggestion_tpl")

        # U1.6: realistic token accounting (~800 per LLM round-trip; flat estimate when no
        # precise usage info available — better than the previous flat 100 which was 7x off)
        await self._budget_tracker.consume(effective_factory_id, 800)

        # U1.5: poison + absolute ¥ checked on ALL THREE fields before capture
        has_poison = (
            _contains_poison(finding_tpl)
            or _contains_poison(implication_tpl)
            or _contains_poison(suggestion_tpl)
        )
        has_absolute_yen = (
            bool(_ABSOLUTE_AMOUNT_RE.search(finding_tpl))
            or bool(_ABSOLUTE_AMOUNT_RE.search(implication_tpl or ""))
            or bool(_ABSOLUTE_AMOUNT_RE.search(suggestion_tpl or ""))
        )

        # Capture if template is valid and clean
        valid = validate_template_parameterization(finding_tpl)

        if valid and not has_poison and not has_absolute_yen:
            await self._capture_template(sig, resolved_ctx, llm_resp)
        elif has_poison or has_absolute_yen:
            logger.info(
                "[chart-insight] LLM response rejected (poison=%s, abs_yen=%s) — not captured",
                has_poison, has_absolute_yen,
            )

        # U1.2: Fill using _safe_fill — unfilled {slot} → field None
        slot_values = _compute_slot_values(resolved_ctx)
        finding = _safe_fill(finding_tpl, slot_values)
        if finding is None:
            # finding is the primary field — None finding → discard entire result
            return None
        implication = _safe_fill(implication_tpl, slot_values) if implication_tpl else None
        suggestion = _safe_fill(suggestion_tpl, slot_values) if suggestion_tpl else None

        return InsightResult(
            finding=finding,
            implication=implication,
            suggestion=suggestion,
            source="llm",
            tier=2,
        )

    # ------------------------------------------------------------------
    # Tier 2a: library lookup
    # ------------------------------------------------------------------

    async def _lookup_template(
        self,
        signature_hash: str,
        ctx: ChartInsightContext,
        caller_role: Optional[str],
    ) -> Optional[InsightResult]:
        """Query ai_insight_templates for an active template matching this signature.

        🔒 RBAC: Templates tagged required_permission='finance:read_write' are only
        returned to callers whose role is in FINANCE_ROLES. Others get null (no fallback
        prose — the caller must wait for Tier2b or get nothing).
        """
        try:
            async with self._pool.acquire() as conn:
                # U1.8: lookup is NOT factory-scoped (cross-tenant template sharing).
                # U1.9 (fixed): gate on (suggestion missing OR is_verified). suggestion_tpl
                #        is a KEY inside the insight_template JSONB, NOT a column — must use
                #        insight_template->>'suggestion_tpl' (bare column ref errored every lookup).
                row = await conn.fetchrow(
                    """
                    SELECT insight_template, required_permission, hit_count
                    FROM ai_insight_templates
                    WHERE signature_hash = $1
                      AND is_active = true
                      AND ((insight_template->>'suggestion_tpl') IS NULL OR is_verified = true)
                    ORDER BY confidence DESC NULLS LAST
                    LIMIT 1
                    """,
                    signature_hash,
                )
        except Exception as exc:
            logger.warning("[chart-insight] template lookup failed: %s", exc)
            return None

        if row is None:
            return None

        required_perm = row["required_permission"]
        template_json = row["insight_template"]

        # 🔒 Permission gate: finance templates need the right role
        if required_perm == "finance:read_write":
            has_finance = caller_role in FINANCE_ROLES
            if not has_finance:
                logger.info(
                    "[chart-insight] RBAC: template requires finance:read_write; role=%s → blocked",
                    caller_role
                )
                return None

        # Parse template
        try:
            tmpl = json.loads(template_json) if isinstance(template_json, str) else template_json
        except (json.JSONDecodeError, TypeError) as exc:
            logger.warning("[chart-insight] template JSON parse failed: %s", exc)
            return None

        finding_tpl = tmpl.get("finding_tpl", "")
        implication_tpl = tmpl.get("implication_tpl")
        suggestion_tpl = tmpl.get("suggestion_tpl")

        # Pre-application assertion: validate template still makes sense (spec §2.7)
        if not finding_tpl:
            return None

        # U1.2: Fill slots via _safe_fill — unfilled {slot} → field None
        slot_values = _compute_slot_values(ctx)
        finding = _safe_fill(finding_tpl, slot_values)
        if finding is None:
            # finding is primary — broken template → discard
            return None
        implication = _safe_fill(implication_tpl, slot_values) if implication_tpl else None
        suggestion = _safe_fill(suggestion_tpl, slot_values) if suggestion_tpl else None

        # Async hit_count increment (best-effort)
        try:
            async with self._pool.acquire() as conn:
                await conn.execute(
                    "UPDATE ai_insight_templates SET hit_count = hit_count + 1, updated_at = NOW() "
                    "WHERE signature_hash = $1",
                    signature_hash,
                )
        except Exception as exc:
            logger.debug("[chart-insight] hit_count increment failed (non-fatal): %s", exc)

        return InsightResult(
            finding=finding,
            implication=implication,
            suggestion=suggestion,
            source="template",
            tier=2,
        )

    # ------------------------------------------------------------------
    # Tier 2b: LLM call (overridable for testing)
    # ------------------------------------------------------------------

    async def _call_llm(self, ctx: ChartInsightContext) -> Optional[Dict[str, Any]]:
        """Call the LLM and return a structured template JSON, or None on failure.

        Prompt instructs: return structured JSON with finding_tpl/implication_tpl/
        suggestion_tpl/slots using {placeholder} syntax.
        If data is insufficient for a meaningful business observation → return all-null.
        Suggestions MUST use observation verbs only (关注/排查/分析/了解).
        Suggestions MUST NOT use causal-prescriptive verbs (复制/引流/加大/扩张/推广).
        """
        try:
            from common.llm_router import call_chain, SLOT
            from common.llm_metrics import llm_caller_context
        except ImportError:
            logger.warning("[chart-insight] llm_router not available, skipping Tier2b")
            return None

        prompt = _build_insight_prompt(ctx)

        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "你是一名数据分析师，专门为ERP系统的图表生成结构化洞察模板。"
                        "你的输出必须是严格的JSON格式（不含代码块）。"
                        "使用观察动词（关注/排查/分析/了解），禁止因果归因动词（复制/引流/加大/扩张/推广）。"
                        "如果数据不足以得出有意义的业务观察，则将finding_tpl返回null，不要编造。"
                    ),
                },
                {"role": "user", "content": prompt},
            ],
            "response_format": {"type": "json_object"},
            "temperature": 0.1,  # U1.9: lowered from 0.3 for consistency
            # Hotfix: SLOT.CHART can fall through the provider chain to a reasoning
            # model (e.g. glm-5.1) that spends tokens on internal reasoning; 500 was
            # entirely consumed → empty content. Give room for reasoning + the JSON.
            "max_tokens": 2000,
        }

        try:
            with llm_caller_context("chart_insight", factory_id=ctx.factory_id):
                resp = await call_chain(SLOT.CHART, payload)
        except Exception as exc:
            logger.warning("[chart-insight] LLM call failed: %s", exc)
            return None

        if resp is None:
            return None

        # Parse response
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

    # ------------------------------------------------------------------
    # Capture + promote (spec §2.2 distillation)
    # ------------------------------------------------------------------

    async def _capture_template(
        self,
        signature_hash: str,
        ctx: ChartInsightContext,
        llm_resp: Dict[str, Any],
    ) -> None:
        """Upsert the LLM response into ai_insight_templates.

        Uses ON CONFLICT (signature_hash, factory_id) DO UPDATE to increment
        proposal_count and store the latest template. After upsert, check if
        promotion criteria are met.

        Promotion rules (spec §2.2):
        - proposal_count >= PROMOTE_THRESHOLD
        - AND validate_template_parameterization (finding_tpl must be fully parameterized)
        - AND NOT _contains_poison (no causal-prescriptive verbs in any field)
        - AND (has suggestion → requires is_verified=True before promote; finding-only → auto OK)
        """
        finding_tpl = llm_resp.get("finding_tpl") or ""
        implication_tpl = llm_resp.get("implication_tpl")
        suggestion_tpl = llm_resp.get("suggestion_tpl")
        slots = llm_resp.get("slots", [])

        # Compute chart_signature for storage
        chart_sig = {
            "chart_type": ctx.chart_type,
            "x_dim": ctx.x_dim,
            "y_metric": ctx.y_metric,
            "aggregation": ctx.aggregation,
            "domain": ctx.domain,
            "data_pattern": ctx.data_pattern,
            "permission_tier": ctx.permission_tier,
        }

        template_obj = {
            "finding_tpl": finding_tpl,
            "implication_tpl": implication_tpl,
            "suggestion_tpl": suggestion_tpl,
            "slots": slots,
        }

        # Determine required_permission based on y_metric
        required_perm = "finance:read_write" if ctx.y_metric in FINANCE_METRICS else None

        try:
            async with self._pool.acquire() as conn:
                # U1.7: ON CONFLICT does NOT overwrite insight_template — first capture wins.
                # Only proposal_count and updated_at are bumped on subsequent proposals.
                # U1.8: UNIQUE constraint is now (signature_hash) — no factory_id in conflict key.
                await conn.execute(
                    """
                    INSERT INTO ai_insight_templates
                        (factory_id, signature_hash, chart_signature, insight_template,
                         required_permission, source_type, confidence,
                         hit_count, proposal_count, is_active, is_verified,
                         created_at, updated_at)
                    VALUES
                        ($1, $2, $3::jsonb, $4::jsonb,
                         $5, 'LLM_FALLBACK', 0.5,
                         0, 1, false, false,
                         NOW(), NOW())
                    ON CONFLICT (signature_hash) DO UPDATE
                        SET proposal_count = ai_insight_templates.proposal_count + 1,
                            updated_at     = NOW()
                    """,
                    ctx.factory_id,
                    signature_hash,
                    json.dumps(chart_sig),
                    json.dumps(template_obj),
                    required_perm,
                )

                # Read back proposal_count for promotion check
                # U1.8: lookup by signature_hash only (no factory_id scope)
                proposal_count = await conn.fetchval(
                    "SELECT proposal_count FROM ai_insight_templates "
                    "WHERE signature_hash = $1",
                    signature_hash,
                )
        except Exception as exc:
            logger.warning("[chart-insight] template capture failed (non-fatal): %s", exc)
            return

        await self._maybe_promote(
            signature_hash=signature_hash,
            factory_id=ctx.factory_id,
            finding_tpl=finding_tpl,
            suggestion_tpl=suggestion_tpl,
            proposal_count=proposal_count or 0,
        )

    async def _maybe_promote(
        self,
        *,
        signature_hash: str,
        factory_id: str,
        finding_tpl: str,
        suggestion_tpl: Optional[str],
        proposal_count: int,
        implication_tpl: Optional[str] = None,
    ) -> None:
        """Promote template to is_active=True if all conditions are met.

        Conditions (spec §2.2):
        1. proposal_count >= PROMOTE_THRESHOLD
        2. validate_template_parameterization(finding_tpl) → True
        3. U1.5: NOT _contains_poison on ANY of finding/implication/suggestion
        4. U1.5: NOT _ABSOLUTE_AMOUNT_RE match on ANY of finding/implication/suggestion
        5. If suggestion_tpl is non-null → requires is_verified=True (not auto-promoted here)
           If finding-only (suggestion_tpl is None) → auto-promote OK
        """
        if proposal_count < self._promote_threshold:
            return

        if not validate_template_parameterization(finding_tpl):
            logger.info("[chart-insight] template not promoted: failed parameterization check")
            return

        # U1.5: poison check on ALL THREE fields
        if (
            _contains_poison(finding_tpl)
            or _contains_poison(implication_tpl)
            or _contains_poison(suggestion_tpl)
        ):
            logger.info("[chart-insight] template not promoted: poison verb detected in any field")
            return

        # U1.5: absolute ¥ check on ALL THREE fields
        if (
            _ABSOLUTE_AMOUNT_RE.search(finding_tpl)
            or _ABSOLUTE_AMOUNT_RE.search(implication_tpl or "")
            or _ABSOLUTE_AMOUNT_RE.search(suggestion_tpl or "")
        ):
            logger.info("[chart-insight] template not promoted: absolute ¥ amount in any field")
            return

        has_suggestion = bool(suggestion_tpl and suggestion_tpl.strip())
        if has_suggestion:
            # Suggestion-bearing templates require manual verification (is_verified=True)
            # Do NOT auto-promote — requires human review
            logger.info(
                "[chart-insight] template not auto-promoted: suggestion present, needs is_verified=True"
            )
            return

        # U1.8: promote by signature_hash only (cross-tenant template)
        try:
            async with self._pool.acquire() as conn:
                await conn.execute(
                    "UPDATE ai_insight_templates "
                    "SET is_active = true, updated_at = NOW() "
                    "WHERE signature_hash = $1 AND is_verified = false",
                    signature_hash,
                )
            logger.info(
                "[chart-insight] template promoted to is_active=true (sig=%s)",
                signature_hash[:12],
            )
        except Exception as exc:
            logger.warning("[chart-insight] template promotion failed (non-fatal): %s", exc)


# ---------------------------------------------------------------------------
# LLM prompt builder
# ---------------------------------------------------------------------------

def _build_insight_prompt(ctx: ChartInsightContext) -> str:
    """Build the structured prompt for Tier2b LLM call.

    U1.1: Prompt explicitly enumerates the ONLY allowed slot names and forbids
    any placeholders outside this whitelist. This prevents the LLM from inventing
    names like {topChannel}, {storeName}, {brandX} which would survive as literal
    {slot} markers in the output (caught by _safe_fill safety net).
    """
    return f"""请为以下图表生成一个**可复用的结构化洞察模板**（JSON格式）。

⚠️ 你**不知道也不需要知道**任何具体数值或名称——系统会在使用时自动把占位符替换成真实值。
你的唯一任务是产出**模板句式**，句中**所有**数值和名称**必须**写成 {{占位符}}。

图表信息（仅用于决定模板句式，不含真实数据）：
- 图表类型: {ctx.chart_type}
- X轴维度: {ctx.x_dim}
- Y轴指标: {ctx.y_metric}
- 聚合方式: {ctx.aggregation}
- 业务域: {ctx.domain}
- 数据模式: {ctx.data_pattern}

输出格式（严格JSON，禁止代码块）：
{{
  "finding_tpl": "发现句，所有数值/名称用占位符，如：{{topName}}占{{topShare}}，是末位{{botName}}的{{ratio}}倍",
  "implication_tpl": "含义句（可选，可为null）",
  "suggestion_tpl": "建议句（可选，可为null；只用观察动词：关注/排查/分析/了解）",
  "slots": ["topName", "topShare", "botName", "ratio"]
}}

❌ 错误示例（含字面量，将被系统拒绝）：
   {{"finding_tpl": "堂食占62.0%，是外卖的1.6倍"}}
✅ 正确示例（全占位符）：
   {{"finding_tpl": "{{topName}}占{{topShare}}，是末位{{botName}}的{{ratio}}倍"}}

重要规则：
1. finding_tpl 中**严禁**出现任何具体数字（如 62、1.6）或具体名称（如 堂食、蜀三味）——全部用占位符。注意 {{topShare}} 已自带 % 号，模板里不要再加 %。
2. 如果数据不足以得出有意义的业务观察，finding_tpl 返回 null。
3. 建议只用观察动词（关注/排查/分析/了解），严禁因果归因（复制/引流/加大/扩张/推广）。
4. 只返回 JSON，不要任何解释或代码块。
5. 【占位符白名单】只能用以下占位符，严禁白名单外（如 {{storeName}}, {{topChannel}}, {{brandX}} 均非法）：
   {{topName}} {{botName}} {{topShare}} {{ratio}} {{concLevel}} {{growthRate}} {{changeAmt}} {{changeDir}}"""
