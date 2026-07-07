"""Tiered restaurant intent router: keyword (T1) -> vector (T2) -> LLM (T3).

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-tiered-routing-design.md

Architecture (spec section references below are to that doc):

  parse_restaurant_query(query, pool, factory_id=..., history=...)
      T1 keyword match_restaurant_ops()               (<1ms,  confidence=0.95)
      T2 vector cosine_topk(code_prefix=RESTAURANT_OPS_) (~30ms, confidence=similarity)
      T3 LLM structured QuerySpec parse (SLOT.MAPPER)  (thinking off, confidence=0.0-1.0)
      miss -> None (caller falls through to its existing chain, unchanged)

Six architecture principles (spec section 1.3), enforced here:
  1. LLM never computes numbers or dates -- only a structured time descriptor;
     real dates come from `_resolve_sales_date_range` (imported from
     restaurant_ops_router, unmodified) and are always recomputed on every
     call (never cached), so a cached routing decision from yesterday still
     resolves "最近两个月" against *today*.
  2. Intent selection is tiered; slot extraction (time/margin/profitability/
     dimension/comparison) is a single deterministic layer shared by all
     three tiers.
  3. `_OPS_PATTERNS` (T1) is frozen -- new phrasing is absorbed by T2/T3, not
     by editing the keyword table.
  4. Business-type gate: T2/T3 only run for restaurant tenants (see
     `_is_restaurant_tenant`). T1 is ungated (existing behavior).
  5. T1 is byte-for-byte unchanged: callers get confidence=0.95,
     source_tier="keyword", and can keep passing `query=` straight through to
     `resolve_by_code` exactly as before.
  6. Fail-open: any exception at any tier is swallowed and logged; the
     function returns None so the caller's existing fallback chain runs
     (mirrors template_rag.hybrid_match's "never raises" contract).
"""
from __future__ import annotations

import json
import logging
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant_ops_router import (
    SAMPLE_QUERIES,
    _profit_intent,
    _resolve_sales_date_range,
    _uses_relative_sales_window,
    match_restaurant_ops,
)

logger = logging.getLogger(__name__)


# ─── QuerySpec ────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class RestaurantQuerySpec:
    """Stable output contract of the tiered router (spec section 3.1)."""

    intent: str                                   # RESTAURANT_OPS_* code, or "" when unresolved
    domain: str
    date_range: Tuple[Optional[Any], Optional[Any]]
    window_label: str
    relative_window: bool
    metrics: Tuple[str, ...]
    wants_margin: bool
    asks_profitability: bool
    dimensions: Tuple[str, ...]
    comparison: Optional[str]
    confidence: float
    source_tier: str                              # "keyword" | "vector" | "llm"
    clarification_needed: bool = False
    clarification_question: Optional[str] = None


# ─── Intent catalogue (used by the T3 prompt) ──────────────────────────────
# One-line description per code so the LLM has enough signal to classify
# without re-deriving it from SAMPLE_QUERIES on every call. Kept in sync with
# the 8 RESTAURANT_OPS_* codes in restaurant_ops_router. No new codes are
# introduced in this round (spec section 7 defers new domains).
_INTENT_DESCRIPTIONS: Dict[str, str] = {
    "RESTAURANT_OPS_WASTAGE_TOP": "损耗/浪费/报损排行，按食材或类型统计损耗量和金额",
    "RESTAURANT_OPS_STOCK_SHORTAGE": "库存盘点差异（盘亏/盘盈）排行",
    "RESTAURANT_OPS_RECIPE_COST": "菜品食材成本排行（不含毛利/售价）",
    "RESTAURANT_OPS_REQUISITION_TREND": "食材领料趋势和领用量排行",
    "RESTAURANT_OPS_GROSS_MARGIN": "菜品级别的毛利/毛利率分析",
    "RESTAURANT_OPS_STORE_MARGIN": "门店级别的毛利/毛利率对比",
    "RESTAURANT_OPS_SALES_SUMMARY": "总体经营概览：营收、订单、客单价、是否盈利",
    "RESTAURANT_OPS_TREND_ANALYSIS": "营收的同比/环比/月度趋势分析",
}

_VALID_CODES = frozenset(_INTENT_DESCRIPTIONS)


# ─── Deterministic slot detectors (shared across all 3 tiers) ─────────────

_STORE_TOKENS = ("门店", "分店", "店铺", "哪家店", "哪个店", "各店")
_DISH_TOKENS = ("菜品", "菜系", "菜价", "哪道菜", "哪个菜", "单品")
_INGREDIENT_TOKENS = ("食材", "原料", "配料", "领料", "领用")


def _detect_dimensions(text: str) -> Tuple[str, ...]:
    """Which real-world objects the question is asking to break down by.

    Order is deterministic (store, dish, ingredient) so tests/logs are stable.
    """
    dims: List[str] = []
    if any(tok in text for tok in _STORE_TOKENS) or ("店" in text and "点" not in text):
        dims.append("store")
    if any(tok in text for tok in _DISH_TOKENS) or "菜" in text:
        dims.append("dish")
    if any(tok in text for tok in _INGREDIENT_TOKENS):
        dims.append("ingredient")
    return tuple(dims)


def _detect_comparison(text: str) -> Optional[str]:
    if "同比" in text:
        return "yoy"
    if "环比" in text:
        return "mom"
    if any(tok in text for tok in ("比上周", "周比", "跟上周比")):
        return "wow"
    return None


_DEFAULT_METRICS_BY_CODE: Dict[str, Tuple[str, ...]] = {
    "RESTAURANT_OPS_WASTAGE_TOP": ("wastage_qty", "wastage_cost"),
    "RESTAURANT_OPS_STOCK_SHORTAGE": ("shortage_qty",),
    "RESTAURANT_OPS_RECIPE_COST": ("food_cost",),
    "RESTAURANT_OPS_REQUISITION_TREND": ("requisition_qty", "requisition_cost"),
    "RESTAURANT_OPS_GROSS_MARGIN": ("gross_profit", "gross_margin"),
    "RESTAURANT_OPS_STORE_MARGIN": ("gross_profit", "gross_margin"),
    "RESTAURANT_OPS_SALES_SUMMARY": ("revenue", "orders", "avg_ticket"),
    "RESTAURANT_OPS_TREND_ANALYSIS": ("revenue",),
}


def _default_metrics_for_code(code: str, wants_margin: bool) -> Tuple[str, ...]:
    metrics = list(_DEFAULT_METRICS_BY_CODE.get(code, ()))
    if wants_margin and "gross_margin" not in metrics:
        metrics.append("gross_margin")
    return tuple(metrics)


def _build_spec(
    code: str,
    query: str,
    *,
    confidence: float,
    tier: str,
    clarification_needed: bool = False,
    clarification_question: Optional[str] = None,
) -> RestaurantQuerySpec:
    """Compose the final QuerySpec: deterministic slots ALWAYS recomputed
    fresh against `query` + today's date, regardless of which tier picked
    `code` or whether that tier's decision came from cache. This is what
    keeps a cached "最近两个月" routing decision from serving yesterday's
    date window (principle 1 in the module docstring)."""
    date_range, window_label = _resolve_sales_date_range(query)
    wants_margin, asks_profitability = _profit_intent(query)
    relative_window = _uses_relative_sales_window(query)
    dimensions = _detect_dimensions(query)
    comparison = _detect_comparison(query)
    metrics = _default_metrics_for_code(code, wants_margin) if code else ()

    return RestaurantQuerySpec(
        intent=code or "",
        domain="restaurant",
        date_range=date_range,
        window_label=window_label,
        relative_window=relative_window,
        metrics=metrics,
        wants_margin=wants_margin,
        asks_profitability=asks_profitability,
        dimensions=dimensions,
        comparison=comparison,
        confidence=confidence,
        source_tier=tier,
        clarification_needed=clarification_needed,
        clarification_question=clarification_question,
    )


# ─── Routing-decision cache (T1/T2/T3 code+confidence+tier only) ──────────
# NOT the full spec -- date_range/window_label are always recomputed fresh in
# `_build_spec` above. Caching only the (code, confidence, tier, clarification)
# tuple is safe across days; caching the resolved date window would NOT be
# (see docstring principle 1). Simple size-capped OrderedDict (manual LRU) --
# no external dependency, no TTL needed since the cached payload is
# date-agnostic.
_ROUTE_CACHE: "OrderedDict[Tuple[str, str], Tuple[str, float, str, bool, Optional[str]]]" = OrderedDict()
_ROUTE_CACHE_MAX = 500


def _cache_get(factory_id: str, norm_query: str):
    key = (factory_id, norm_query)
    hit = _ROUTE_CACHE.get(key)
    if hit is not None:
        _ROUTE_CACHE.move_to_end(key)
    return hit


def _cache_put(factory_id: str, norm_query: str, value) -> None:
    key = (factory_id, norm_query)
    _ROUTE_CACHE[key] = value
    _ROUTE_CACHE.move_to_end(key)
    while len(_ROUTE_CACHE) > _ROUTE_CACHE_MAX:
        _ROUTE_CACHE.popitem(last=False)


def clear_route_cache() -> None:
    """Test-only helper: reset the in-process routing cache."""
    _ROUTE_CACHE.clear()


def _normalize_query(query: str) -> str:
    return (query or "").strip()


def build_resolver_query(query: str, spec: RestaurantQuerySpec) -> str:
    """The string chat.py should pass as `query=` into `resolve_by_code`.

    Resolvers (e.g. resolve_sales_summary) re-derive date_range/margin/
    profitability from the raw query text themselves -- they don't accept a
    precomputed RestaurantQuerySpec. For T1 that's fine (the resolver's own
    parse of the raw query IS how `spec` was built). For T2/T3, `spec` may
    have been built from a query augmented with the LLM's structured time
    hint (see `_build_spec` in the T3 branch of `parse_restaurant_query`) --
    so the resolver must see that same augmentation, or it will silently
    re-derive a wider/emptier window than the one already shown to the user.

    Appending `spec.window_label` (itself always valid `_resolve_sales_date_range`
    vocabulary, e.g. "最近2个月"/"今天"/"本周"/"本月") to the original query is
    idempotent for T1/T2 (the window was already derivable from the original
    text) and closes the gap for T3 paraphrases. Original wording is kept
    intact so profit/margin keyword detection (`_profit_intent`) still sees
    the user's actual words.
    """
    if spec.window_label == "全部历史":
        return query
    if spec.window_label in query:
        return query
    return f"{query} {spec.window_label}".strip()


# ─── Business-type gate (spec section 3.4) ────────────────────────────────
# T2/T3 must only run for restaurant tenants -- there is no shared, public,
# per-factory-id business-type helper today (chat.py's _derive_business_type
# is private/module-local and importing it back into this module would set
# up a circular import since chat.py imports restaurant_intent). Per spec's
# explicit fallback, gate on "this factory has data in agg_restaurant_daily_*"
# and cache the (cheap, stable-for-a-tenant's-lifetime) result in-process.
_RESTAURANT_TENANT_CACHE: Dict[str, bool] = {}


async def _is_restaurant_tenant(pool, factory_id: str) -> bool:
    if not factory_id:
        return False
    cached = _RESTAURANT_TENANT_CACHE.get(factory_id)
    if cached is not None:
        return cached
    is_restaurant = False
    try:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT 1 FROM agg_restaurant_daily_totals WHERE factory_id = $1 LIMIT 1",
                factory_id,
            )
        is_restaurant = row is not None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] tenant gate lookup failed for {factory_id}: {exc}")
        is_restaurant = False
    _RESTAURANT_TENANT_CACHE[factory_id] = is_restaurant
    return is_restaurant


def clear_tenant_gate_cache() -> None:
    """Test-only helper: reset the in-process tenant gate cache."""
    _RESTAURANT_TENANT_CACHE.clear()


# ─── T2 vector tier ─────────────────────────────────────────────────────

_T2_HIGH_CONFIDENCE = 0.78   # mirrors template_rag.HIGH_CONFIDENCE
_T2_MIN_USEFUL = 0.70        # mirrors template_rag.MIN_USEFUL


async def _t2_vector_match(pool, query: str) -> Tuple[Optional[str], float, Optional[Tuple[str, float]]]:
    """Return (code_if_high_confidence, similarity, hint_for_t3).

    hint_for_t3 is (code, similarity) for a 0.70-0.78 candidate to pass into
    the T3 prompt, or None. code is None when nothing clears
    _T2_HIGH_CONFIDENCE (either because it's a hint-only match or no match at
    all).
    """
    try:
        from smartbi.services.template_embedding_index import cosine_topk
        candidates = await cosine_topk(
            pool, query, k=3, min_similarity=_T2_MIN_USEFUL,
            code_prefix="RESTAURANT_OPS_",
        )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T2 vector match failed: {exc}")
        return None, 0.0, None

    if not candidates:
        return None, 0.0, None

    top_code, top_sim, _sample = candidates[0]
    if top_code not in _VALID_CODES:
        return None, 0.0, None
    if top_sim >= _T2_HIGH_CONFIDENCE:
        return top_code, top_sim, None
    return None, top_sim, (top_code, top_sim)


# ─── T3 LLM tier ────────────────────────────────────────────────────────

_T3_TIMEOUT_SECONDS = 5.0
_T3_MIN_CONFIDENCE = 0.6


def _build_t3_prompt(query: str, hint: Optional[Tuple[str, float]], history: Optional[Sequence[Dict[str, str]]]) -> str:
    intent_lines = "\n".join(
        f'  - "{code}": {desc}' for code, desc in _INTENT_DESCRIPTIONS.items()
    )
    hint_line = ""
    if hint:
        hint_code, hint_sim = hint
        hint_line = (
            f"\n提示: 向量检索认为最可能是 \"{hint_code}\" (相似度 {hint_sim:.2f})，"
            f"仅供参考，请结合问题原文自行判断。\n"
        )
    few_shot = (
        '示例1: "这两个月生意咋样，挣着钱没" -> '
        '{"intent": "RESTAURANT_OPS_SALES_SUMMARY", "time_range": {"type": "relative", '
        '"unit": "month", "count": 2}, "wants_margin": true, "asks_profitability": true, '
        '"dimensions": [], "comparison": null, "confidence": 0.9, '
        '"clarification_needed": false, "clarification_question": null}\n'
        '示例2: "情况怎么样" (完全没有可判断的对象/指标) -> '
        '{"intent": null, "time_range": null, "wants_margin": false, '
        '"asks_profitability": false, "dimensions": [], "comparison": null, '
        '"confidence": 0.2, "clarification_needed": true, '
        '"clarification_question": "您想了解营收、毛利、损耗还是库存盘点的情况？"}\n'
    )
    return (
        "你是餐饮老板问答系统的意图解析器。将用户问题解析为一个 JSON 对象，不要输出任何其他文字。\n"
        "可选 intent 取值（必须从下面列表中选择一个，或者在无法判断时输出 null）：\n"
        f"{intent_lines}\n"
        f"{hint_line}\n"
        "严格规则:\n"
        "1. 你绝对不能计算或输出具体日期！time_range 只能是结构化描述，例如: "
        '{"type": "relative", "unit": "month", "count": 2} (最近2个月), '
        '{"type": "relative", "unit": "day", "count": 10} (最近10天), '
        '{"type": "named", "value": "today"|"this_week"|"this_month"}, '
        '{"type": "all_history"}, 或 null (未提及时间)。真实日期由确定性代码计算，不是你的工作。\n'
        "2. confidence 是你对 intent 判断的把握程度 (0.0-1.0)。\n"
        "3. 如果问题太模糊无法判断 intent，输出 intent:null 且 clarification_needed:true，"
        "并给出一个具体的澄清问题(clarification_question)。\n"
        "4. 只输出 JSON，不要 markdown 代码块，不要解释。\n\n"
        f"{few_shot}\n"
        f'用户问题: "{query}"\n'
        "JSON:"
    )


def _parse_t3_time_range(time_range: Any) -> str:
    """Turn the LLM's structured time_range object back into a phrase that
    `_resolve_sales_date_range` (the SAME deterministic parser T1/T2 use) can
    understand -- so real date computation never touches the LLM's output
    directly. Returns "" (no time phrase) on anything unexpected (fail-open)."""
    if not isinstance(time_range, dict):
        return ""
    kind = time_range.get("type")
    if kind == "relative":
        unit = time_range.get("unit")
        count = time_range.get("count")
        unit_cn = {"day": "天", "week": "周", "month": "个月"}.get(unit)
        if unit_cn and isinstance(count, (int, float)) and count > 0:
            return f"最近{int(count)}{unit_cn}"
        return ""
    if kind == "named":
        value = time_range.get("value")
        return {
            "today": "今天",
            "this_week": "本周",
            "this_month": "本月",
        }.get(value, "")
    if kind == "all_history":
        return ""
    return ""


async def _t3_llm_parse(
    query: str,
    *,
    hint: Optional[Tuple[str, float]],
    history: Optional[Sequence[Dict[str, str]]],
) -> Optional[Dict[str, Any]]:
    """Call the SmartBI LLM router (SLOT.MAPPER: thinking off, json_object,
    temperature 0) to structurally parse `query`. Returns the parsed dict, or
    None on any failure/timeout (fail-open -- caller treats None as "T3 had
    nothing to offer")."""
    try:
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        prompt = _build_t3_prompt(query, hint, history)
        payload = {
            "messages": [
                {
                    "role": "system",
                    "content": "你只输出JSON格式的意图解析结果，不输出任何其他文字。",
                },
                {"role": "user", "content": prompt},
            ],
            "temperature": 0,
            "max_tokens": 500,
        }
        with llm_caller_context("restaurant_intent"):
            result = await call_chain(SLOT.MAPPER, payload, timeout=_T3_TIMEOUT_SECONDS)
        content = (result["choices"][0]["message"]["content"] or "").strip()
        if content.startswith("```"):
            content = content.strip("`")
            if content[:4].lower() == "json":
                content = content[4:]
            content = content.strip()
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            return None
        return parsed
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T3 LLM parse failed/timed out: {exc}")
        return None


# ─── Public entry point ────────────────────────────────────────────────

async def parse_restaurant_query(
    query: str,
    pool,
    *,
    factory_id: str,
    history: Optional[Sequence[Dict[str, str]]] = None,
) -> Optional[RestaurantQuerySpec]:
    """Resolve `query` to a RestaurantQuerySpec via T1 -> T2 -> T3, or None.

    Fail-open at every tier: any exception is logged and swallowed, and the
    function degrades to the next tier (or to None), never raising into the
    caller's chat.py SSE stream.
    """
    norm_query = _normalize_query(query)
    if not norm_query or not factory_id:
        return None

    # ── T1: keyword (ungated, unchanged, <1ms) ──
    try:
        t1_code = match_restaurant_ops(norm_query)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T1 keyword match raised (fail-open): {exc}")
        t1_code = None
    if t1_code:
        return _build_spec(t1_code, norm_query, confidence=0.95, tier="keyword")

    # ── Business-type gate: T2/T3 only for restaurant tenants ──
    try:
        if not await _is_restaurant_tenant(pool, factory_id):
            return None
    except Exception as exc:
        logger.warning(f"[restaurant-intent] tenant gate raised (fail-open, treat as non-restaurant): {exc}")
        return None

    cached = _cache_get(factory_id, norm_query)
    if cached is not None:
        code, confidence, tier, clarification_needed, clarification_question = cached
        return _build_spec(
            code or None, norm_query, confidence=confidence, tier=tier,
            clarification_needed=clarification_needed,
            clarification_question=clarification_question,
        )

    # ── T2: vector (code_prefix=RESTAURANT_OPS_, ~30ms, 0 LLM tokens) ──
    try:
        t2_code, t2_sim, t2_hint = await _t2_vector_match(pool, norm_query)
    except Exception as exc:
        logger.warning(f"[restaurant-intent] T2 vector match raised (fail-open): {exc}")
        t2_code, t2_sim, t2_hint = None, 0.0, None
    if t2_code:
        _cache_put(factory_id, norm_query, (t2_code, t2_sim, "vector", False, None))
        return _build_spec(t2_code, norm_query, confidence=t2_sim, tier="vector")

    # ── T3: LLM structured parse (thinking off, temperature 0, 5s timeout) ──
    parsed = await _t3_llm_parse(norm_query, hint=t2_hint, history=history)
    if parsed is None:
        return None  # miss at every tier -> caller falls through, unchanged

    t3_code = parsed.get("intent")
    if t3_code not in _VALID_CODES:
        t3_code = None
    try:
        t3_confidence = float(parsed.get("confidence") or 0.0)
    except (TypeError, ValueError):
        t3_confidence = 0.0
    clarification_needed = bool(parsed.get("clarification_needed"))
    clarification_question = parsed.get("clarification_question")
    if not isinstance(clarification_question, str):
        clarification_question = None

    if t3_code and t3_confidence >= _T3_MIN_CONFIDENCE and not clarification_needed:
        # Splice the LLM's structured (non-date) time_range hint back into
        # plain Chinese so `_resolve_sales_date_range` (T1/T2's SAME
        # deterministic parser) can compute the actual dates. If the LLM gave
        # no usable hint, or the raw query already carries an explicit time
        # phrase, `_build_spec` -> `_resolve_sales_date_range` still works off
        # `norm_query` directly, so this is purely additive.
        time_phrase = _parse_t3_time_range(parsed.get("time_range"))
        effective_query = f"{norm_query} {time_phrase}".strip() if time_phrase else norm_query
        _cache_put(factory_id, norm_query, (t3_code, t3_confidence, "llm", False, None))
        return _build_spec(t3_code, effective_query, confidence=t3_confidence, tier="llm")

    # Low confidence or explicit clarification request -> surface a
    # clarification instead of querying data with a guess.
    if not clarification_question:
        clarification_question = "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
    _cache_put(
        factory_id, norm_query,
        (t3_code or "", t3_confidence, "llm", True, clarification_question),
    )
    return _build_spec(
        t3_code, norm_query, confidence=t3_confidence, tier="llm",
        clarification_needed=True, clarification_question=clarification_question,
    )


# ─── Flywheel v1: capture + candidate listing (spec section 5) ────────────

async def log_intent_capture(
    pool,
    spec: RestaurantQuerySpec,
    *,
    factory_id: str,
    query: str,
    answer: str,
    contract_pass: Optional[bool],
    served: bool,
    total_wall_ms: int = 0,
) -> Optional[int]:
    """Fire-and-forget capture of a parse+serve outcome, reusing the existing
    llm_fallback_logger table (no new schema). tier/confidence/contract_pass
    ride in agg_meta (JSONB) -- this is what a human reviewer / promotion
    script reads to find T3 (LLM-tier) queries worth promoting into
    SAMPLE_QUERIES / the vector index (per spec section 5, promotion stays
    manual --apply in this v1).
    """
    try:
        from smartbi.services.llm_fallback_logger import log_template_hit
        agg_meta = {
            "tier": spec.source_tier,
            "confidence": spec.confidence,
            "contract_pass": contract_pass,
            "served": served,
            "window_label": spec.window_label,
            "clarification_needed": spec.clarification_needed,
        }
        return await log_template_hit(
            pool, query, factory_id, None,
            spec.intent or "RESTAURANT_OPS_CLARIFICATION",
            answer, total_wall_ms, agg_meta=agg_meta,
        )
    except Exception as exc:
        logger.warning(f"[restaurant-intent] capture logging failed (non-fatal): {exc}")
        return None


async def list_promotion_candidates(
    pool, *, min_confidence: float = 0.6, limit: int = 50,
) -> List[Dict[str, Any]]:
    """List T3 (LLM-tier) queries that passed the Answer Contract -- v1
    promotion candidates for a human to review and, if approved, append to
    SAMPLE_QUERIES / upsert into the vector index (manual --apply, per spec
    section 5; automatic two-gate promotion is deferred to Phase 2)."""
    sql = """
        SELECT query, template_code, agg_meta, created_at
          FROM smart_bi_llm_fallback_log
         WHERE source = 'template'
           AND template_code LIKE 'RESTAURANT_OPS_%%'
           AND (agg_meta->>'tier') = 'llm'
           AND (agg_meta->>'contract_pass') = 'true'
           AND COALESCE((agg_meta->>'confidence')::float, 0) >= $1
         ORDER BY created_at DESC
         LIMIT $2
    """
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, min_confidence, limit)
        return [dict(r) for r in rows]
    except Exception as exc:
        logger.warning(f"[restaurant-intent] list_promotion_candidates failed: {exc}")
        return []
