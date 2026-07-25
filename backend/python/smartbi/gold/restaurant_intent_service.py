"""Execute immutable restaurant QueryPlans and enforce their answer contract.

Both Chat/SmartBI and the Java restaurant entry point call this service.
Natural-language v2 plans keep the same resolver list from semantic planning
through SQL execution; any mismatch, resolver miss, or contract failure
returns a non-executing clarification instead of a neighboring answer.
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
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    TRUSTED_PLANNER_AUTHORITIES,
    build_resolver_query,
    log_intent_capture,
    parse_restaurant_query,
)
from smartbi.gold.restaurant_ops_router import (
    demo_data_factory_for_code,
    extract_store_mentions,
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

_RESOLVER_DIMENSIONS = {
    "RESTAURANT_OPS_GROSS_MARGIN": frozenset({"dish"}),
    "RESTAURANT_OPS_RECIPE_COST": frozenset({"dish"}),
    "RESTAURANT_OPS_STORE_MARGIN": frozenset({"store", "dish"}),
    "RESTAURANT_OPS_WASTAGE_TOP": frozenset({"ingredient"}),
    "RESTAURANT_OPS_STOCK_SHORTAGE": frozenset({"ingredient"}),
    "RESTAURANT_OPS_REQUISITION_TREND": frozenset({"ingredient"}),
    "RESTAURANT_OPS_SALES_SUMMARY": frozenset(),
    "RESTAURANT_OPS_TREND_ANALYSIS": frozenset(),
    "RESTAURANT_OPS_INVENTORY_WARNING": frozenset({"ingredient"}),
    "RESTAURANT_OPS_STAFFING_ADVICE": frozenset(),
}


def _execution_mismatch(
    spec: RestaurantQuerySpec,
    plan: Tuple[str, ...],
    *,
    dish_mention: Optional[str],
    store_mention: Optional[str],
    store_dish: Optional[str],
) -> Optional[str]:
    """Reject any execution-time reinterpretation of an immutable v2 plan."""
    if spec.plan_version != "restaurant-query-plan-v2":
        return None
    if spec.planner_authority not in TRUSTED_PLANNER_AUTHORITIES:
        return "餐饮执行计划缺少可信语义来源"
    if not spec.plan_hash or tuple(spec.planned_intents) != plan:
        return "餐饮执行计划不完整"
    if spec.intent not in plan:
        return "主意图与执行步骤不一致"
    if store_dish and plan != ("RESTAURANT_OPS_STORE_MARGIN",):
        return "店菜范围与执行 resolver 不一致"
    if (
        dish_mention
        and not store_mention
        and "RESTAURANT_OPS_SALES_SUMMARY" in plan
    ):
        return "菜品范围不能由全店汇总 resolver 代答"
    if store_mention and any(
        code in ("RESTAURANT_OPS_SALES_SUMMARY", "RESTAURANT_OPS_GROSS_MARGIN")
        for code in plan
    ):
        return "门店范围不能由全店或全门店 resolver 代答"
    supported_dimensions = set().union(
        *(_RESOLVER_DIMENSIONS.get(code, frozenset()) for code in plan)
    )
    if not set(spec.dimensions).issubset(supported_dimensions):
        return "查询维度超出计划 resolver 的能力范围"
    return None


def _execution_receipt(
    spec: RestaurantQuerySpec,
    plan: Tuple[str, ...],
    executed_codes: Tuple[str, ...],
    meta: Dict[str, Any],
) -> Dict[str, Any]:
    receipt = dict(meta)
    supported_dimensions = set().union(
        *(_RESOLVER_DIMENSIONS.get(code, frozenset()) for code in executed_codes)
    )
    receipt.update({
        "query_plan_hash": spec.plan_hash,
        "query_plan_version": spec.plan_version,
        "planner_authority": spec.planner_authority,
        "executed_resolvers": list(executed_codes),
        "execution_plan_match": executed_codes == plan,
        "actual_dimensions": sorted(supported_dimensions),
        "scope_matches_request": bool(
            receipt.get("scope_matches_request", True)
            and set(spec.dimensions).issubset(supported_dimensions)
        ),
    })
    return receipt


def _structured_context(
    spec: RestaurantQuerySpec,
    result_meta: Dict[str, Any],
    *,
    dish_mention: Optional[str],
    store_mention: Optional[str],
) -> Dict[str, Any]:
    focus = result_meta.get("focus_entity")
    if not isinstance(focus, dict):
        ranked = result_meta.get("ranked_entities")
        if isinstance(ranked, list) and ranked and isinstance(ranked[0], dict):
            focus = ranked[0]
    if not isinstance(focus, dict):
        if dish_mention:
            focus = {"type": "dish", "name": dish_mention}
        elif store_mention:
            focus = {"type": "store", "name": store_mention}
    if isinstance(focus, dict):
        entity_type = focus.get("type")
        entity_name = focus.get("name")
        if entity_type not in ("dish", "store") or not isinstance(entity_name, str):
            focus = None
        else:
            focus = {
                "type": entity_type,
                "id": focus.get("id"),
                "name": entity_name[:80],
                "rank": focus.get("rank"),
            }
    topic_kind = None
    if result_meta.get("dish_ranking"):
        topic_kind = "dish_ranking"
    elif (
        isinstance(focus, dict)
        and focus.get("type") == "store"
        and focus.get("rank") is not None
    ):
        topic_kind = "store_ranking"
    return {
        "plan_hash": spec.plan_hash,
        "plan_version": spec.plan_version,
        "focus_entity": focus,
        "window_label": spec.window_label,
        "requested_metrics": list(spec.requested_metrics),
        "analysis_action": spec.analysis_action,
        "topic_kind": topic_kind,
        "ranking_direction": (
            result_meta.get("dish_ranking") or spec.ranking_direction
        ),
        "ranking_limit": (
            result_meta.get("ranking_limit") or spec.ranking_limit
        ),
        "excluded_entities": (
            result_meta.get("excluded_entities")
            if isinstance(result_meta.get("excluded_entities"), list)
            else list(spec.excluded_entities)
        ),
        "store_scope": spec.store_scope,
        "store_names": list(spec.store_slots),
        "compare_stores": spec.compare_stores,
    }


def _suggested_followups(context: Dict[str, Any]) -> List[Dict[str, str]]:
    topic_kind = context.get("topic_kind")
    if topic_kind in ("dish_ranking", "store_ranking"):
        noun = "哪个菜卖得最好" if topic_kind == "dish_ranking" else "哪家店业绩最好"
        current_window = str(context.get("window_label") or "")
        windows = ["本月", "上个月", "最近30天"]
        alternatives = [window for window in windows if window != current_window][:2]
        return [
            {
                "label": f"看{window}",
                "question": f"{window}{noun}？",
            }
            for window in alternatives
        ]

    focus = context.get("focus_entity")
    if not isinstance(focus, dict) or not focus.get("name"):
        return []
    name = str(focus["name"])
    window_label = str(context.get("window_label") or "").strip()
    time_prefix = (
        ""
        if not window_label or window_label == "全部历史"
        else window_label
    )
    current_metrics = set(context.get("requested_metrics") or [])
    if focus.get("type") == "dish":
        candidates = [
            ("sales_volume", "看菜品销量", f"{time_prefix}「{name}」的销量是多少？"),
            ("recipe_cost", "看菜品成本", f"{time_prefix}「{name}」的成本如何？"),
            ("gross_margin", "看菜品毛利", f"{time_prefix}「{name}」的毛利率如何？"),
        ]
    else:
        candidates = [
            ("revenue", "看门店营收", f"{time_prefix}「{name}」的营收如何？"),
            ("gross_margin", "看门店毛利", f"{time_prefix}「{name}」的毛利率如何？"),
        ]
    return [
        {"label": label, "question": question}
        for metric, label, question in candidates
        if metric not in current_metrics
    ][:2]


def _clarification_followups(spec: RestaurantQuerySpec) -> List[Dict[str, str]]:
    """Return deterministic choices for recognized structured-slot gaps."""
    if spec.clarification_question == TIME_CLARIFICATION_QUESTION:
        return [
            {"label": window, "question": window}
            for window in ("本月", "上个月", "最近7天", "最近30天")
        ]
    if spec.clarification_question == STORE_SCOPE_CLARIFICATION_QUESTION:
        choices = [{"label": "全部门店", "question": "全部门店"}]
        choices.extend(
            {"label": name[:12], "question": name}
            for name in spec.store_options[:3]
        )
        return choices
    return []


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
    allow_decompose: bool = True,
) -> Optional[Dict[str, Any]]:
    """Execute one contract-checked restaurant query plan.

    Natural-language requests always go through ``parse_restaurant_query``.
    Keyword/vector matches are candidate hints only; a v2 plan can execute
    only when its authority is the LLM planner or a validated plan-cache hit.
    Non-restaurant tenants still return ``None``. Once a v2 plan exists,
    resolver misses, exceptions, route/scope drift, and contract failures are
    fail-closed clarifications and never fall through to an adjacent intent.

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
    spec = precomputed_spec
    try:
        from smartbi.gold.restaurant_playbook import PLAYBOOK_CODE, PLAYBOOK_TRIGGERS
        if any(trigger in (query or "") for trigger in PLAYBOOK_TRIGGERS):
            # R16b: 此前 fail-open 依赖 chat.py 的 resolve_by_code 兜底; 反转后
            # Java 委托路径没有那个兜底 (fail-open → delegate:false → Java LLM
            # 误匹配工厂工具)。playbook 零 DB 零租户数据, 直接解析原文返回。
            resolved = await _resolve_tiered(PLAYBOOK_CODE, pool, factory_id, query=query)
            if resolved is not None:
                return {
                    "kind": "clarification",
                    "answer_text": str(getattr(resolved, "answer_text", "") or ""),
                    "spec": None,
                }
            return None
        from smartbi.gold.restaurant_ops_router import (
            RESTAURANT_CAPABILITIES_TEXT,
            RESTAURANT_OOD_TEXT,
            is_capability_question,
            is_out_of_domain_smalltalk,
        )
        if is_out_of_domain_smalltalk(query):
            # 域外闲聊 — 诚实拒答, 绝不编造外部事实 (R20)。
            return {
                "kind": "clarification",
                "answer_text": RESTAURANT_OOD_TEXT,
                "spec": None,
            }
        if is_capability_question(query):
            # 零 DB 静态能力自述 — 原文直出, 不走 Answer Contract (R14/G4)。
            return {
                "kind": "clarification",
                "answer_text": RESTAURANT_CAPABILITIES_TEXT,
                "spec": None,
            }
        # R28 复合问题 agent: LLM 只拆解, 子问题各自走本函数完整管道
        # (递归一层, allow_decompose=False 防套娃), 答案确定性拼装 —
        # LLM 不写正文不碰数字。拆解失败 fail-open 回单主题路径
        # (R26b 诚实尾注在那里兜底)。
        if allow_decompose:
            from smartbi.gold.restaurant_agent import (
                assemble_compound_answer,
                decompose_compound_question,
                is_compound_question,
            )
            if is_compound_question(query):
                parts = await decompose_compound_question(query)
                if parts:
                    import asyncio as _aio
                    raw_results = await _aio.gather(*[
                        tiered_answer(
                            part, pool, factory_id, role,
                            java_tool_name=java_tool_name,
                            allow_decompose=False,
                        )
                        for part in parts
                    ], return_exceptions=True)
                    results = [
                        r if isinstance(r, dict) else None for r in raw_results
                    ]
                    combined = assemble_compound_answer(parts, results)
                    if combined:
                        return combined
        if spec is None:
            spec = await parse_restaurant_query(
                query, pool, factory_id=factory_id, session_key=session_key,
            )
        if spec is None:
            return None
        if spec.clarification_needed or not spec.intent:
            clarification_result = {
                "kind": "clarification",
                "answer_text": (
                    spec.clarification_question
                    or "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
                ),
                "spec": spec,
            }
            followups = _clarification_followups(spec)
            if followups:
                clarification_result["suggested_followups"] = followups
            return clarification_result

        resolver_query = build_resolver_query(query, spec)
        execution_kwargs = _resolver_kwargs(spec, role, resolver_query)
        plan = spec.planned_intents or (spec.intent,)
        store_mentions = tuple(
            getattr(spec, "store_slots", ())
            or extract_store_mentions(resolver_query)
            or extract_store_mentions(query)
        )
        store_mention = (
            (store_mentions[0] if len(store_mentions) == 1 else None)
            or getattr(spec, "store_slot", None)
            if ("RESTAURANT_OPS_STORE_MARGIN" in plan
                or "RESTAURANT_OPS_GROSS_MARGIN" in plan
                or "RESTAURANT_OPS_SALES_SUMMARY" in plan)
            else None
        )
        from smartbi.gold.restaurant_ops_router import (
            extract_dish_candidate,
            store_dish_split_dish,
        )
        dish_mention = (
            extract_dish_candidate(resolver_query)
            or extract_dish_candidate(query)
            or getattr(spec, "dish_slot", None)
        )
        # R18: 店×菜下钻 — store_dish_rows 本就是店×菜粒度, 路由 STORE_MARGIN
        # 带 dish_mention 直答 (匿名「哪家店的X」排名 / 具名店+菜单店直答)。
        split_dish = store_dish_split_dish(query) or store_dish_split_dish(resolver_query)
        store_dish = split_dish or (dish_mention if store_mention else None)
        mismatch = _execution_mismatch(
            spec,
            plan,
            dish_mention=dish_mention,
            store_mention=store_mention,
            store_dish=store_dish,
        )
        if mismatch:
            return {
                "kind": "clarification",
                "answer_text": (
                    f"本次没有执行分析：{mismatch}。"
                    "请明确要看菜品、门店还是全店汇总，我不会改走相邻分析。"
                ),
                "contract_pass": False,
                "spec": spec,
            }
        planned_results: List[Tuple[str, Any]] = []
        for code in plan:
            code_kwargs = execution_kwargs
            if code == "RESTAURANT_OPS_GROSS_MARGIN" and dish_mention:
                code_kwargs = dict(execution_kwargs)
                code_kwargs["dish_mention"] = dish_mention
            if code == "RESTAURANT_OPS_STORE_MARGIN" and (
                store_mention or len(store_mentions) > 1 or store_dish
            ):
                code_kwargs = dict(execution_kwargs)
                if store_mention:
                    code_kwargs["store_mention"] = store_mention
                if len(store_mentions) > 1:
                    code_kwargs["store_mentions"] = list(store_mentions)
                if store_dish:
                    code_kwargs["dish_mention"] = store_dish
            code_factory = demo_data_factory_for_code(
                code,
                factory_id,
                store_scoped=bool(
                    store_mention
                    or store_dish
                    or (
                        spec.ranking_direction
                        and spec.store_scope in {"all", "multiple"}
                    )
                ),
            )
            resolved = await _resolve_tiered(
                code,
                pool,
                code_factory,
                **code_kwargs,
            )
            if (
                resolved is not None
                and code_factory != factory_id
                and "dish_not_found" in (getattr(resolved, "meta", None) or {})
            ):
                # R18b 跨空间菜单回落: 店×菜路由到 RES9 (16 店叙事), 但 RES9
                # 菜单没有 DEMO 自有菜 (如招牌藤椒味) — 换回本租户明细重试,
                # 两边都查不到才维持定向拒答。
                retry = await _resolve_tiered(
                    code,
                    pool,
                    factory_id,
                    **code_kwargs,
                )
                if retry is not None and "dish_not_found" not in (
                    getattr(retry, "meta", None) or {}
                ):
                    resolved = retry
            if resolved is not None:
                planned_results.append((code, resolved))
        tiered_result = (
            _combine_planned_answers(spec, planned_results)
            if len(plan) > 1
            else (planned_results[0][1] if planned_results else None)
        )
        if not tiered_result:
            if spec.plan_version == "restaurant-query-plan-v2":
                return {
                    "kind": "clarification",
                    "answer_text": (
                        "计划中的餐饮分析没有返回可验证结果，本次没有改走相邻分析。"
                        "请确认数据范围后重试。"
                    ),
                    "contract_pass": False,
                    "spec": spec,
                }
            return None

        # Guard declines (missing date reference, unknown/ambiguous store) are
        # clarifications: their text must reach the user verbatim instead of
        # being replaced by the generic "no displayable result" wrapper.
        guard_meta = getattr(tiered_result, "meta", None) or {}
        if any(
            key in guard_meta
            for key in ("missing_reference", "store_not_found", "store_mention_ambiguous",
                    "dish_not_found", "dish_mention_ambiguous", "no_pos_data")
        ):
            return {
                "kind": "clarification",
                "answer_text": sanitize_customer_ai_text(
                    str(getattr(tiered_result, "answer_text", "") or "")
                ),
                "spec": spec,
            }

        result_kpis = getattr(tiered_result, "kpis", None) or []
        executed_codes = tuple(code for code, _ in planned_results)
        result_meta = _execution_receipt(
            spec,
            plan,
            executed_codes,
            getattr(tiered_result, "meta", None) or {},
        )
        result_charts = getattr(tiered_result, "charts", None) or []
        answer_text = sanitize_customer_ai_text(
            str(getattr(tiered_result, "answer_text", "") or "")
        )
        contract = _contract.validate(
            spec,
            answer_text,
            result_kpis,
            result_meta,
        )
        displayable = has_displayable_business_result(answer_text)
        if not contract.passed or not displayable:
            missing = (
                _contract.describe_missing(contract.missing)
                if contract.missing
                else "可展示的真实业务结果"
            )
            safe_text = (
                f"本次结果没有可靠覆盖{missing}，因此没有向您展示可能答非所问的数据，"
                "也没有改走相邻指标。请补充具体范围后重试。"
            )
            capture_source = "java_entry_delegate" if java_tool_name else None
            asyncio.create_task(log_intent_capture(
                pool, spec, factory_id=factory_id, query=query,
                answer=safe_text, contract_pass=False, served=False,
                source=capture_source,
            ))
            return {
                "kind": "clarification",
                "answer_text": safe_text,
                "contract_pass": False,
                "spec": spec,
            }
        # R26b: 多主题复合句 ("这个月生意怎么样，另外米饭卖得好不好") 目前
        # 只执行一个主题 — 其余部分静默丢弃违反"部分完成不说成全部"。
        # 检测到复合分隔且计划只有单主题时, 尾注明示未覆盖部分。
        compound_tail = None
        for sep in ("，另外", "，再告诉我", "，然后", "；", "，顺便", "，以及"):
            if sep in query:
                compound_tail = sep
                break
        if compound_tail and len(plan) == 1 and "继续追问" not in query:
            answer_text += (
                "\n\n提示：您的问题包含多个部分，本次先回答了其中一个；"
                "其余部分（如「" + query.split(compound_tail)[-1].strip()[:24]
                + "」）请单独提问，我会逐个给出数据。"
            )
        contract_pass = True
        structured_context = _structured_context(
            spec,
            result_meta,
            dish_mention=dish_mention,
            store_mention=store_mention,
        )

        result: Dict[str, Any] = {
            "kind": "answer",
            "answer_text": answer_text,
            "charts": result_charts,
            "kpis": result_kpis,
            "title": str(getattr(tiered_result, "title", "") or "经营分析"),
            # Report the resolver that actually produced a single-topic answer.
            # The selected intent and metric plan can differ; returning the
            # selected code hid the 7/24 dish-ranking -> sales-summary mismatch.
            "code": planned_results[0][0] if len(planned_results) == 1 else spec.intent,
            "contract_pass": contract_pass,
            "query_plan_hash": spec.plan_hash,
            "executed_resolvers": list(executed_codes),
            "structured_context": structured_context,
            "suggested_followups": _suggested_followups(structured_context),
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
        logger.warning(f"[restaurant-intent] tiered path failed: {e}")
        if spec is not None and spec.plan_version == "restaurant-query-plan-v2":
            return {
                "kind": "clarification",
                "answer_text": (
                    "餐饮执行链暂时不可用，本次没有执行任何相邻分析。"
                    "请稍后重试。"
                ),
                "contract_pass": False,
                "spec": spec,
            }
        return None


def should_delegate(
    spec: Optional[RestaurantQuerySpec], java_tool_name: Optional[str] = None,
    query: Optional[str] = None,
) -> bool:
    """Return whether the Python answer path must own this request.

    Every sealed v2 LLM/cache plan and every v2 clarification stays in Python
    so Java cannot reinterpret it. Legacy specs retain the older compatibility
    rules below while callers migrate.
    """
    # Dish-scoped questions ("米饭的销量") delegate unconditionally: the
    # Java Gold tools have no per-dish answer path, while the Python
    # gross-margin resolver scopes to the named dish (Sheet 7/22 菜品链).
    if query:
        from smartbi.gold.restaurant_ops_router import (
            dish_ranking_direction,
            extract_dish_candidates,
            is_capability_question,
        )
        # 比较问 ("A和B哪个赚钱") 单菜提取拿不到 — 用复数提取 (R14)。
        if extract_dish_candidates(query):
            return True
        if dish_ranking_direction(query) or is_capability_question(query):
            return True
        # 复合问题 → python 侧 agent 拆解回答 (R28)。
        from smartbi.gold.restaurant_agent import is_compound_question
        if is_compound_question(query):
            return True
        # 域外闲聊 (天气/新闻) — 必须由 tiered 给诚实拒答, 落回 Java 会拿到
        # 工厂措辞的通用助手回复 (R20b)。
        from smartbi.gold.restaurant_ops_router import is_out_of_domain_smalltalk
        if is_out_of_domain_smalltalk(query):
            return True
        from smartbi.gold.restaurant_ops_router import store_dish_split_dish
        if store_dish_split_dish(query):
            return True
        # 盈亏存在性问 ("有没有店在亏损") — 裸「亏损」不在 _profit_intent
        # 词典里, 规则 3 接不住; 存在性正则命中即放行 (R15b)。
        from smartbi.gold.restaurant_ops_router import _NEGATIVE_MARGIN_EXISTENCE_RE
        if _NEGATIVE_MARGIN_EXISTENCE_RE.search(query):
            return True
        # 行业参考做法 (playbook) — intent 不在 MARGIN_CAPABLE, 规则 3 不放行;
        # 触发词命中即委托, tiered 层零 DB 直答 (R16b)。
        from smartbi.gold.restaurant_playbook import PLAYBOOK_TRIGGERS as _PB_TRIGGERS
        if any(t in query for t in _PB_TRIGGERS):
            return True
    if spec is None:
        return False
    if spec.plan_version == "restaurant-query-plan-v2":
        # The Java path must not reinterpret a plan the Python semantic
        # authority has already sealed. Clarifications (including planner
        # outages) and executable LLM/cache plans both stay on this path.
        return bool(
            spec.clarification_needed
            or (
                spec.intent
                and spec.plan_hash
                and spec.planner_authority in TRUSTED_PLANNER_AUTHORITIES
            )
        )
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
    # (R24 清理: R20b staffing / R22 llm-tier 显式规则已被下方规格即路由
    #  通则覆盖, 删除; 实体槽位规则保留 — T2 向量层也可能带槽位。)
    if getattr(spec, "dish_slot", None) or getattr(spec, "store_slot", None):
        return True
    # R23 规格即路由全量化: 确定性 T1 或置信过门的 T3 解析出 resolver
    # 支持的意图即委托 — 存量枚举放行规则自此退化为文档。两个保留例外:
    # (1) T2 向量层置信弱, 仍须经上面的显式规则 (选错意图会答错域);
    # (2) 2026-07-08 审计 A-3: 盈亏问落在不懂盈亏的 resolver 上会挂永久
    #     免责声明, 比 Java 原答案更差 — 该组合仍不直通。
    tier_trusted = spec.source_tier in ("keyword", "llm") or (
        # R24: T2 向量层高置信 (≥0.85 相似度) 也走规格即路由; 低于阈值仍走
        # 上面的显式规则 — 向量选错意图会答错域, 只给高分直通。
        spec.source_tier == "vector" and spec.confidence >= 0.85
    )
    if tier_trusted and not spec.clarification_needed:
        profit_ask = spec.asks_profitability or spec.wants_margin
        if not (profit_ask and spec.intent not in _MARGIN_CAPABLE_INTENTS):
            from smartbi.gold.restaurant_ops_router import is_supported_restaurant_ops_code
            if is_supported_restaurant_ops_code(spec.intent):
                return True
    return False
