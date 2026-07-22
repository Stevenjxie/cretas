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
from typing import Any, Dict, List, Optional, Tuple

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
from smartbi.gold.restaurant_ops_router import (
    demo_data_factory_for_code,
    extract_store_mention,
    resolve_by_code as _resolve_tiered,
)

logger = logging.getLogger(__name__)

# 2026-07-08 audit fix A-3: resolver 能力表定义在 answer_contract (更底层,
# 本模块已 import 它, 反向会循环)。should_delegate 规则 3 与
# answer_contract.required_elements 共用同一份表, 两处判断保持一致。
_MARGIN_CAPABLE_INTENTS = _contract.MARGIN_CAPABLE_INTENTS

_PLAN_LABELS = {
    "RESTAURANT_OPS_RECIPE_COST": "菜品成本",
    "RESTAURANT_OPS_WASTAGE_TOP": "食材损耗",
    "RESTAURANT_OPS_GROSS_MARGIN": "菜品毛利",
    "RESTAURANT_OPS_STORE_MARGIN": "门店毛利",
    "RESTAURANT_OPS_SALES_SUMMARY": "营收与订单",
    "RESTAURANT_OPS_STAFFING_ADVICE": "排班人效",
}


def _resolver_kwargs(
    spec: RestaurantQuerySpec,
    role: Optional[str],
    query: str,
) -> Dict[str, Any]:
    kwargs: Dict[str, Any] = {"role": role, "query": query}
    start, end = spec.date_range
    if start is not None and end is not None and hasattr(end, "__sub__"):
        try:
            kwargs["days"] = max(1, min((end - start).days + 1, 365))
            kwargs["date_range"] = (start, end)
        except (AttributeError, TypeError):
            pass
    return kwargs


def _priority_section(results: List[Tuple[str, Any]]) -> str:
    by_code = {code: result for code, result in results}
    codes = tuple(code for code, _ in results)
    cost_diagnostic_codes = {
        "RESTAURANT_OPS_RECIPE_COST",
        "RESTAURANT_OPS_WASTAGE_TOP",
        "RESTAURANT_OPS_STORE_MARGIN",
    }
    if not cost_diagnostic_codes.issubset(by_code):
        labels = [_PLAN_LABELS.get(code, "经营指标") for code in codes]
        if set(codes) == {
            "RESTAURANT_OPS_SALES_SUMMARY",
            "RESTAURANT_OPS_STAFFING_ADVICE",
        }:
            labels = ["营收与订单", "排班人效"]
            reason = "先确认订单峰谷和业务量，再核对相同时段的人效与人员配置。"
        else:
            reason = "先核对结果指标，再沿问题中要求的驱动指标逐项下钻，避免只凭单一指标行动。"
        ordered = "\n".join(f"{index}. {label}" for index, label in enumerate(labels, 1))
        return f"\n\n联合分析优先级与依据：\n{ordered}\n依据：{reason}"

    store_meta = getattr(by_code.get("RESTAURANT_OPS_STORE_MARGIN"), "meta", {}) or {}
    wastage_meta = getattr(by_code.get("RESTAURANT_OPS_WASTAGE_TOP"), "meta", {}) or {}
    coverage = store_meta.get("costCoverageRatio")
    if coverage is None:
        coverage = store_meta.get("cost_coverage_ratio")
    wastage_cost = float(wastage_meta.get("total_cost") or 0.0)

    if coverage is not None and float(coverage) < 0.8:
        order = ("菜品成本", "食材损耗", "门店毛利")
        reason = "成本覆盖不足会先污染毛利判断，必须先补齐成本口径。"
    elif wastage_cost > 0:
        order = ("食材损耗", "门店毛利", "菜品成本")
        reason = "已有可量化损耗金额，先止损，再验证门店毛利和菜品成本结构。"
    else:
        order = ("门店毛利", "菜品成本", "食材损耗")
        reason = "先从结果指标定位问题门店，再向菜品成本和损耗原因下钻。"
    return (
        "\n\n联合排查优先级与依据：\n"
        f"1. {order[0]}\n2. {order[1]}\n3. {order[2]}\n"
        f"依据：{reason}"
    )


def _combine_planned_answers(
    spec: RestaurantQuerySpec,
    results: List[Tuple[str, Any]],
) -> Any:
    from smartbi.gold.restaurant_ops_router import OpsAnswer

    sections: List[str] = []
    charts: List[Dict[str, Any]] = []
    kpis: List[Dict[str, Any]] = []
    combined_meta: Dict[str, Any] = {
        "plan_complete": len(results) == len(spec.planned_intents),
        "planned_count": len(spec.planned_intents),
        "completed_count": len(results),
        "sub_results": {},
    }
    for code, result in results:
        label = _PLAN_LABELS.get(code, result.title or "经营分析")
        sections.append(f"{label}\n{result.answer_text}")
        charts.extend(result.charts or [])
        kpis.extend(result.kpis or [])
        sub_meta = result.meta or {}
        combined_meta["sub_results"][code] = sub_meta
        for key in ("stores", "weak_stores", "low_margin_dishes"):
            if sub_meta.get(key):
                combined_meta.setdefault(key, []).extend(sub_meta[key])
        for key in ("rbac_masked", "no_pos_data", "no_data"):
            if sub_meta.get(key) is True:
                combined_meta[key] = True
        if code in ("RESTAURANT_OPS_GROSS_MARGIN", "RESTAURANT_OPS_STORE_MARGIN"):
            if sub_meta.get("marginInvariantPass") is not None:
                combined_meta["marginInvariantPass"] = bool(
                    combined_meta.get("marginInvariantPass", True)
                    and sub_meta["marginInvariantPass"]
                )
            if sub_meta.get("scope_matches_request") is not None:
                combined_meta["scope_matches_request"] = bool(
                    combined_meta.get("scope_matches_request", True)
                    and sub_meta["scope_matches_request"]
                )

    answer_text = "\n\n".join(sections)
    if spec.asks_priority:
        answer_text += _priority_section(results)
    return OpsAnswer(
        code=spec.intent,
        title="餐饮经营联合分析",
        answer_text=answer_text,
        charts=charts,
        kpis=kpis,
        meta=combined_meta,
    )


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
        from smartbi.gold.restaurant_playbook import PLAYBOOK_TRIGGERS
        if any(trigger in (query or "") for trigger in PLAYBOOK_TRIGGERS):
            # Explicit playbook phrases are served by the deterministic T1
            # keyword resolver; the tiered parser doesn't know the code and
            # would degrade the request to a clarification. Fail open so the
            # caller's resolve_by_code fallback answers it.
            return None
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
        execution_kwargs = _resolver_kwargs(spec, role, resolver_query)
        plan = spec.planned_intents or (spec.intent,)
        store_mention = (
            extract_store_mention(resolver_query) or extract_store_mention(query)
            if ("RESTAURANT_OPS_STORE_MARGIN" in plan
                or "RESTAURANT_OPS_GROSS_MARGIN" in plan)
            else None
        )
        from smartbi.gold.restaurant_ops_router import extract_dish_candidate
        dish_mention = (
            extract_dish_candidate(resolver_query) or extract_dish_candidate(query)
        )
        planned_results: List[Tuple[str, Any]] = []
        for code in plan:
            effective_code = code
            if (
                dish_mention
                and code == "RESTAURANT_OPS_SALES_SUMMARY"
                and not store_mention
            ):
                # planner 把「X的销量」按 sales_volume 归入 SALES_SUMMARY, 但
                # 点名单菜的销量/营收数据在 gross-margin resolver 的 POS 行里
                # (Sheet 7/22 菜品链)。dish 限域接管, 全店概览不受影响
                # (无菜名 → dish_mention None)。
                effective_code = "RESTAURANT_OPS_GROSS_MARGIN"
            if (
                store_mention
                and code == "RESTAURANT_OPS_GROSS_MARGIN"
                and "RESTAURANT_OPS_STORE_MARGIN" not in plan
            ):
                # A margin question that names one store is a store-margin
                # question; the dish-level resolver would silently answer for
                # every store, which is the forbidden all-store fallback.
                effective_code = "RESTAURANT_OPS_STORE_MARGIN"
            code_kwargs = execution_kwargs
            if effective_code == "RESTAURANT_OPS_STORE_MARGIN" and store_mention:
                code_kwargs = dict(execution_kwargs)
                code_kwargs["store_mention"] = store_mention
            code_factory = demo_data_factory_for_code(
                effective_code,
                factory_id,
                store_scoped=bool(store_mention),
            )
            resolved = await _resolve_tiered(
                effective_code,
                pool,
                code_factory,
                **code_kwargs,
            )
            if resolved is not None:
                planned_results.append((effective_code, resolved))
        tiered_result = (
            _combine_planned_answers(spec, planned_results)
            if len(plan) > 1
            else (planned_results[0][1] if planned_results else None)
        )
        if not tiered_result:
            return None

        # Guard declines (missing date reference, unknown/ambiguous store) are
        # clarifications: their text must reach the user verbatim instead of
        # being replaced by the generic "no displayable result" wrapper.
        guard_meta = getattr(tiered_result, "meta", None) or {}
        if any(
            key in guard_meta
            for key in ("missing_reference", "store_not_found", "store_mention_ambiguous",
                    "dish_not_found", "dish_mention_ambiguous")
        ):
            return {
                "kind": "clarification",
                "answer_text": sanitize_customer_ai_text(
                    str(getattr(tiered_result, "answer_text", "") or "")
                ),
                "spec": spec,
            }

        result_kpis = getattr(tiered_result, "kpis", None) or []
        result_meta = getattr(tiered_result, "meta", None) or {}
        result_charts = getattr(tiered_result, "charts", None) or []
        answer_text = str(getattr(tiered_result, "answer_text", "") or "")
        contract = _contract.validate(
            spec,
            answer_text,
            result_kpis,
            result_meta,
        )
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
            "charts": result_charts,
            "kpis": result_kpis,
            "title": str(getattr(tiered_result, "title", "") or "经营分析"),
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
    query: Optional[str] = None,
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
    # Dish-scoped questions ("米饭的销量") delegate unconditionally: the
    # Java Gold tools have no per-dish answer path, while the Python
    # gross-margin resolver scopes to the named dish (Sheet 7/22 菜品链).
    if query and extract_store_mention(query) is None:
        from smartbi.gold.restaurant_ops_router import extract_dish_candidate
        if extract_dish_candidate(query):
            return True
    if spec is None:
        return False
    if spec.clarification_needed:
        return True
    if len(spec.planned_intents) > 1:
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
