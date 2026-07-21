"""Shared restaurant tiered-intent answer service.

Design doc: docs/superpowers/specs/2026-07-07-restaurant-intent-phase2-java-entry-design.md
(Phase 2, building on Phase 1: 2026-07-07-restaurant-intent-tiered-routing-design.md)

This module is the extraction target called out in Phase 2 §4: the body of
what used to live inline as ``chat.py::_try_tiered_restaurant_intent`` now
lives here as ``tiered_answer()`` so it can be called from TWO places with
byte-identical behavior:

  1. ``smartbi/api/chat.py`` -- 3 existing SSE/JSON call sites (general
     analysis / trend pre-check / stream ops routing). ``chat.py`` keeps a
     thin wrapper with the exact original name + signature so none of the
     194 pre-existing tests need to change.
  2. The new ``POST /api/smartbi/gold/restaurant/tiered-answer`` endpoint
     (``smartbi/api/gold_reads.py``) that the Java
     ``GoldBackedRestaurantTool.doExecute`` delegate gate calls via
     ``GoldFinanceClient.fetchTieredIntentAnswer`` BEFORE running its own
     resolveWindow -> queryGold -> format flow.

``should_delegate()`` is the Phase 2 keystone (design §3): a small, pure,
independently-testable decision function that the new HTTP endpoint calls
to decide whether THIS query needs the full tiered/contract machinery
(delegate) or whether Java's existing Gold Tool flow is already good enough
(don't delegate -- zero behavior change on the query classes Java already
answers well).
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any, Dict, Optional

from smartbi.gold.customer_text import (
    has_displayable_business_result,
    sanitize_customer_ai_text,
)

from smartbi.gold import answer_contract as _contract
from smartbi.gold.restaurant_intent import (
    RestaurantQuerySpec,
    build_resolver_query,
    log_intent_capture,
    parse_restaurant_query,
)
from smartbi.gold.restaurant_ops_router import resolve_by_code as _resolve_tiered

logger = logging.getLogger(__name__)

# 2026-07-08 audit fix A-3: resolver 能力表定义在 answer_contract (更底层,
# 本模块已 import 它, 反向会循环)。should_delegate 规则 3 与
# answer_contract.required_elements 共用同一份表, 两处判断保持一致。
_MARGIN_CAPABLE_INTENTS = _contract.MARGIN_CAPABLE_INTENTS


async def tiered_answer(
    query: str,
    pool,
    factory_id: str,
    role: Optional[str],
    *,
    java_tool_name: Optional[str] = None,
    session_key: Optional[str] = None,
    precomputed_spec: Optional[RestaurantQuerySpec] = None,
) -> Optional[Dict[str, Any]]:
    """T2 (vector) / T3 (LLM) restaurant intent routing (2026-07-07 Phase 1
    design). ONLY call this after the existing T1 keyword fast path
    (``match_restaurant_ops``) has already missed at the call site --
    this function does not re-check keywords first, it goes straight to
    ``parse_restaurant_query`` (which itself re-tries T1 first, cheaply,
    before T2/T3 -- so calling it unconditionally is safe, just slightly
    redundant).

    Fail-open: returns None on any miss/exception/business-type-gate-closed,
    so every caller's existing fallback chain is reached exactly as before
    this feature existed (zero regression risk for non-restaurant tenants or
    when anything below throws).

    Return shape:
      {"kind": "clarification", "answer_text": str, "spec": spec}
      {"kind": "answer", "answer_text": str, "charts": list, "kpis": list,
       "title": str, "code": str, "contract_pass": bool, "spec": spec}

    ``java_tool_name`` is accepted for the Phase 2 delegate-gate call site:
    when non-empty, the fire-and-forget capture log tags
    ``agg_meta.source = "java_entry_delegate"`` (design doc section 2) so a
    human reviewer / the flywheel promotion script can tell a Java-delegated
    capture apart from a direct chat.py SSE capture. It is NOT branched on
    by this function's own routing/resolve logic -- the returned dict shape
    is identical regardless of ``java_tool_name`` (chat.py's 3 existing call
    sites never pass it, so their behavior is unchanged).

    ``session_key`` (2026-07-08 clarification-loop v1, additive/optional):
    forwarded straight through to ``parse_restaurant_query`` so a user's
    answer to a previous clarification gets parsed in context (see that
    module's docstring). Omitted/``None`` is byte-identical to this
    function's behavior before the feature existed.

    ``precomputed_spec`` (Phase 2 delegate-gate optimization, 2026-07-08):
    when the caller (the ``POST /restaurant/tiered-answer`` endpoint) has
    ALREADY called ``parse_restaurant_query`` once -- e.g. to feed
    ``should_delegate()`` its decision -- passing that same spec here skips
    a second internal call. This is not just an optimization: a second call
    with the same ``session_key`` would find the pending clarification
    entry already consumed by the first call (continuation is single-use,
    see the ``restaurant_intent`` module docstring) and silently degrade to
    a context-free fresh parse instead of the continuation the first call
    already resolved. When ``None`` (every other caller), this function
    calls ``parse_restaurant_query`` itself exactly as before.
    """
    try:
        spec = precomputed_spec if precomputed_spec is not None else await parse_restaurant_query(
            query, pool, factory_id=factory_id, session_key=session_key,
        )
        if spec is None:
            return None
        if spec.clarification_needed or not spec.intent:
            return {
                "kind": "clarification",
                "answer_text": (
                    spec.clarification_question
                    or "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
                ),
                "spec": spec,
            }

        resolver_query = build_resolver_query(query, spec)
        tiered_result = await _resolve_tiered(
            spec.intent, pool, factory_id, role=role, query=resolver_query,
        )
        if not tiered_result:
            return None

        contract = _contract.validate(
            spec, tiered_result.answer_text, tiered_result.kpis, tiered_result.meta,
        )
        answer_text = tiered_result.answer_text
        if not contract.passed:
            answer_text += (
                f"\n\n本次结果没有可靠覆盖{_contract.describe_missing(contract.missing)}，"
                "因此不把它当作完整结论，也没有用其他时间或指标替代。请补充具体范围后重试。"
            )
        answer_text = sanitize_customer_ai_text(answer_text)
        contract_pass = contract.passed and has_displayable_business_result(answer_text)

        result: Dict[str, Any] = {
            "kind": "answer",
            "answer_text": answer_text,
            "charts": tiered_result.charts,
            "kpis": tiered_result.kpis,
            "title": tiered_result.title,
            "code": spec.intent,
            "contract_pass": contract_pass,
            "spec": spec,
        }
        capture_source = "java_entry_delegate" if java_tool_name else None
        asyncio.create_task(log_intent_capture(
            pool, spec, factory_id=factory_id, query=query,
            answer=answer_text, contract_pass=contract_pass, served=True,
            source=capture_source,
        ))
        return result
    except Exception as e:
        logger.warning(f"[restaurant-intent] tiered fast path failed (fail-open): {e}")
        return None


def should_delegate(
    spec: Optional[RestaurantQuerySpec], java_tool_name: Optional[str] = None,
) -> bool:
    """Phase 2 delegate gate (design doc section 3): decide whether the Java
    ``GoldBackedRestaurantTool.doExecute`` entry point should hand this query
    off to the Python tiered router (+ Answer Contract) instead of running
    its own ``resolveWindow -> queryGold -> format`` flow.

    Rules (evaluated in order; first match wins -- mirrors the design doc's
    numbered list verbatim):

      1. ``spec is None`` (T1/T2/T3 all missed, non-restaurant tenant, or an
         upstream exception inside ``parse_restaurant_query``) -> False.
         Java keeps its own flow untouched.
      2. ``spec.clarification_needed`` -> True. Java's Gold Tool flow has no
         mechanism to ask a clarifying question; only the Python tiered path
         can.
      3. ``(spec.asks_profitability or spec.wants_margin) and spec.intent in
         _MARGIN_CAPABLE_INTENTS`` -> True. The Java Gold Tool family never
         produces a profit/margin verdict, so delegating buys the verdict —
         but ONLY when the Python resolver for the parsed intent can actually
         produce one (2026-07-08 audit fix A-3: delegating a WASTAGE_TOP-class
         intent on a secondary profit mention hands the answer to a resolver
         that ignores the profit ask entirely, and the Answer Contract then
         appends a permanent, unfixable disclaimer — worse than Java's own
         answer, so those stay in Java).
      4. ``spec.intent == "RESTAURANT_OPS_SALES_SUMMARY" and
         spec.relative_window`` -> True. "最近N天/周/月" ops-summary windows
         are only honored by the Python resolver (``_resolve_sales_date_range``
         family); Java's ``resolveWindow`` only understands absolute months /
         本月 / 上月.
      5. Otherwise -> False. Pure trend/ranking/absolute-month queries: Java's
         existing answer quality is already good here, so this keeps that
         path byte-for-byte unchanged (zero regression risk).

    ``java_tool_name`` is accepted (not currently branched on) so a future
    per-tool exception can be added to this function without changing the
    call signature at every call site (design doc section 3 lists the input
    as "spec（parse 结果）+ `java_tool_name`" without carving out a
    per-tool rule yet).
    """
    if spec is None:
        return False
    if spec.clarification_needed:
        return True
    if spec.comparison and spec.intent == "RESTAURANT_OPS_SALES_SUMMARY":
        return True
    if (
        (spec.asks_profitability or spec.wants_margin)
        and spec.intent in _MARGIN_CAPABLE_INTENTS
    ):
        return True
    if spec.intent == "RESTAURANT_OPS_SALES_SUMMARY" and spec.relative_window:
        return True
    return False
