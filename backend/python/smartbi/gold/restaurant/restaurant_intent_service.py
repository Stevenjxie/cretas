"""Execute immutable restaurant QueryPlans and enforce their answer contract.

Both Chat/SmartBI and the Java restaurant entry point call this service.
Natural-language v2 plans keep the same resolver list from semantic planning
through SQL execution; any mismatch, resolver miss, or contract failure
returns a non-executing clarification instead of a neighboring answer.
"""
from __future__ import annotations

import asyncio
import logging
import re
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.customer_text import (
    has_displayable_business_result,
    sanitize_customer_ai_text,
)

from smartbi.gold.restaurant import answer_contract as _contract
from smartbi.gold.restaurant.restaurant_intent import (
    RestaurantQuerySpec,
    STORE_SCOPE_CLARIFICATION_QUESTION,
    TIME_CLARIFICATION_QUESTION,
    TRUSTED_PLANNER_AUTHORITIES,
    build_resolver_query,
    log_intent_capture,
    parse_restaurant_query,
    resolve_output_preference,
    unsupported_requirements_disclosure,
)
from smartbi.gold.restaurant.restaurant_ops_router import (
    _resolve_sales_query_spec,
    demo_data_factory_for_code,
    dish_catalogue_scope,
    extract_store_mentions,
    resolve_by_code as _resolve_tiered,
    resolver_supports_explicit_window,
)

logger = logging.getLogger(__name__)

# 2026-07-08 audit fix A-3: resolver 能力表定义在 answer_contract (更底层,
# 本模块已 import 它, 反向会循环)。should_delegate 规则 3 与
# answer_contract.required_elements 共用同一份表, 两处判断保持一致。
_MARGIN_CAPABLE_INTENTS = _contract.MARGIN_CAPABLE_INTENTS

_PLAN_LABELS = {
    "RESTAURANT_OPS_CAPABILITIES": "可用能力",
    "RESTAURANT_OPS_OUT_OF_DOMAIN": "可用数据范围",
    "RESTAURANT_OPS_PLAYBOOK": "经营参考做法",
    "RESTAURANT_OPS_STORE_DIRECTORY": "门店名单",
    "RESTAURANT_OPS_BUSINESS_OPTIMIZATION": "经营诊断与提升方案",
    "RESTAURANT_OPS_CHANNEL_MIX": "堂食与外卖",
    "RESTAURANT_OPS_DISCOUNT_SUMMARY": "折扣力度",
    "RESTAURANT_OPS_SUPPLIER_PRICE": "供应商比价",
    "RESTAURANT_OPS_DAYPART_PERFORMANCE": "时段表现",
    "RESTAURANT_OPS_RECIPE_COST": "菜品成本",
    "RESTAURANT_OPS_WASTAGE_TOP": "食材损耗",
    "RESTAURANT_OPS_GROSS_MARGIN": "菜品毛利",
    "RESTAURANT_OPS_STORE_MARGIN": "门店毛利",
    "RESTAURANT_OPS_SALES_SUMMARY": "营收与订单",
    "RESTAURANT_OPS_STAFFING_ADVICE": "排班人效",
}

_RESOLVER_DIMENSIONS = {
    "RESTAURANT_OPS_CAPABILITIES": frozenset(),
    "RESTAURANT_OPS_OUT_OF_DOMAIN": frozenset(),
    "RESTAURANT_OPS_PLAYBOOK": frozenset(),
    "RESTAURANT_OPS_STORE_DIRECTORY": frozenset({"store"}),
    "RESTAURANT_OPS_BUSINESS_OPTIMIZATION": frozenset(
        {"store", "dish", "ingredient", "channel", "customer", "time"}
    ),
    "RESTAURANT_OPS_CHANNEL_MIX": frozenset({"channel"}),
    # 折扣总额来自 agg_daily 的全店日汇总, 构成来自 agg_discount 的月粒度 ——
    # ⛔ 两个来源都**不带门店/菜品粒度**, 所以这里声明空集(声明的是真能出的粒度,
    #    不是希望它能出的)。问「哪家店折扣最多」会因此不落到这个 resolver。
    "RESTAURANT_OPS_DISCOUNT_SUMMARY": frozenset(),
    # 报价按食材粒度出(同一食材内比供应商), 不带门店/时段粒度。
    "RESTAURANT_OPS_SUPPLIER_PRICE": frozenset({"ingredient"}),
    # 时段本身是 time 维度; 结果里也带门店无关的全店汇总, 故只声明 time。
    # ⛔ 声明的是**这个 resolver 真能出的粒度**, 不是「希望它能出」的。
    "RESTAURANT_OPS_DAYPART_PERFORMANCE": frozenset({"time"}),
    "RESTAURANT_OPS_GROSS_MARGIN": frozenset({"dish", "time"}),
    "RESTAURANT_OPS_RECIPE_COST": frozenset({"dish"}),
    "RESTAURANT_OPS_STORE_MARGIN": frozenset({"store", "dish"}),
    "RESTAURANT_OPS_WASTAGE_TOP": frozenset({"ingredient"}),
    "RESTAURANT_OPS_STOCK_SHORTAGE": frozenset({"ingredient"}),
    "RESTAURANT_OPS_REQUISITION_TREND": frozenset({"ingredient"}),
    # The sales summary resolver returns a real per-store Top-N table in
    # addition to the chain aggregate, so an all-store revenue ranking is a
    # supported store-grain read rather than a resolver mismatch.
    "RESTAURANT_OPS_SALES_SUMMARY": frozenset({"store"}),
    "RESTAURANT_OPS_TREND_ANALYSIS": frozenset({"time"}),
    "RESTAURANT_OPS_INVENTORY_WARNING": frozenset({"ingredient"}),
    # Staffing facts are keyed by store and daypart (午市/晚市/夜宵), and the
    # resolver returns every store by default.  Both grains are therefore
    # executable.  Omitting ``store`` made normal questions such as
    # “明天各门店怎么排班” pass planning but fail the execution contract before
    # the grounded FactBook/LLM path could run.
    "RESTAURANT_OPS_STAFFING_ADVICE": frozenset({"store", "time"}),
}

_READ_ONLY_MUTATION_TOKENS = (
    "下架", "上架", "停售", "删除", "停用", "启用",
    "调价", "改价", "涨价", "降价", "创建活动", "发券",
)
_HISTORICAL_MUTATION_TOKENS = (
    "已下架", "下架记录", "下架情况", "停售记录", "调价记录", "调价历史",
)
_READ_ONLY_ACTION_WARNING = (
    "当前未执行任何下架、调价或其他业务操作。"
    "咨询模式只展示分析结果；如需执行，请切换到操作模式，"
    "先生成预览，确认后再执行。"
)


def _read_only_action_warning(query: str) -> Optional[str]:
    text = (query or "").strip()
    if not text or any(token in text for token in _HISTORICAL_MUTATION_TOKENS):
        return None
    if any(token in text for token in _READ_ONLY_MUTATION_TOKENS):
        return _READ_ONLY_ACTION_WARNING
    return None


def _read_only_action_warning_for_spec(
    query: str,
    spec: Optional[RestaurantQuerySpec],
) -> Optional[str]:
    """Retain a mutation warning across pure slot-clarification replies."""
    if (
        spec is not None
        and getattr(spec, "planner_authority", "")
        == "explicit_action_read_choice"
    ):
        return _READ_ONLY_ACTION_WARNING
    sources = [query]
    if spec is not None:
        sources.append(str(getattr(spec, "resolver_query_seed", "") or ""))
    for source in sources:
        warning = _read_only_action_warning(source)
        if warning:
            return warning
    return None


def _prepend_action_warning(answer_text: str, warning: Optional[str]) -> str:
    if not warning or warning in (answer_text or ""):
        return answer_text
    return f"**{warning}**\n\n{answer_text}"


def _llm_capacity_available() -> bool:
    """LLM 链路现在还有没有一档能用。

    ⛔ **fail-open**：判不了（router 变了 / import 失败 / 抛异常）时返回 True。
       判据是「不要因为一个探测器坏了就把整个 AI 关掉」—— 关闭是重手段，只能在
       **确知**链路不可用时才用。真的不可用时，下游 router 自己会抛 exhausted，
       那条路仍然是安全的，只是没有这条早退那么干净。

    ⚠️ 用 `slot_has_usable_provider` 的**只读**语义：预检每次问答都跑，
       不能顺手消耗掉熔断器的「再探一次」额度。
    """
    try:
        from common.llm_router import SLOT, slot_has_usable_provider

        # REVIEW 是餐饮 T3 用的档位（`_t3_llm_parse` 走它）。它没了 = 意图识别没了。
        return slot_has_usable_provider(SLOT.REVIEW)
    except Exception:  # noqa: BLE001
        logger.warning("[restaurant-intent] LLM 可用性预检失败, fail-open 放行", exc_info=True)
        return True


def _time_range_disclosure(spec: Any) -> str:
    """时间窗取了默认值就必须说出来 —— 与门店范围同一条判据。

    ⛔ 与门店那条的区别在于: 「全部门店」是无歧义的**超集**, 而「最近 30 天」是一个
    **选择**。正因为它是选择, 披露才更不能省 —— 用户看到数字之前就该知道这是哪一段
    时间的数字, 否则他会拿一个 30 天的数去对一个他心里想的 7 天的数。

    ⚠️ 窗口本身由 `_build_spec` 顶部补 `time_phrase` 落实（date_range /
    window_label / resolver seed 一起变），这里只负责把「这是代码替你选的」说出来。
    """
    if not getattr(spec, "time_range_defaulted", False):
        return ""
    # ⛔ 逐字引用同一个常量：说的窗口与算的窗口必须同源。各写一份，哪天默认从
    #    30 天改成 7 天，就会出现「按 30 天算、却说 7 天」—— 比反问更糟，
    #    因为用户会拿着错的口径去做决定。
    from smartbi.gold.restaurant.phrasing import TIME_RANGE_DISCLOSURE, pick_variant
    from smartbi.gold.restaurant.restaurant_intent import DEFAULT_TIME_PHRASE

    # 措辞按 (问句, 日期) 轮换 —— 同一天同一问句说法固定（刷新不会变，不制造疑心），
    # 跨天才换。⛔ 窗口值仍然只有 DEFAULT_TIME_PHRASE 一个来源。
    template = pick_variant(
        TIME_RANGE_DISCLOSURE,
        key=str(getattr(spec, "resolver_query_seed", "") or ""),
    )
    return "\n\n" + template.format(window=DEFAULT_TIME_PHRASE)


def _store_scope_disclosure(spec: Any) -> str:
    """门店范围取了默认值就必须说出来。

    ⛔ 这是 `store_scope_defaulted` 存在的**唯一**理由。默认「全部门店」本身没问题
    —— 它是无歧义的超集 —— 但不声明就成了「偷偷替用户选了口径」, 那才是降级处理。
    选了并说出来不是。

    只在**代码补的**默认上出现: 用户自己说了「全部门店」时再声明一遍是废话。
    判据是「这个口径是谁选的」, 不是「口径是什么」。
    """
    if not getattr(spec, "store_scope_defaulted", False):
        return ""
    options = tuple(getattr(spec, "store_options", ()) or ())
    if not options:
        # 拿不到门店名单时不谎报家数 —— 说范围, 不说数字。
        return "\n\n（范围：全部门店合计。想看单店直接说门店名即可。）"
    return (
        f"\n\n（范围：全部 {len(options)} 家门店合计。"
        f"想看单店直接说门店名即可，例如「{options[0]}」。）"
    )


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


async def _known_data_gap(pool, factory_id: str, query: str):
    """薄封装, 让缺口探测在路由之前也能用。见 tiered_answer 里的调用注释。

    ⛔ 与 `resolve_out_of_domain` 共用**同一个** `honest_gap_answer` —— 不复制判定
    逻辑。两处各写一份「什么算缺口」, 迟早一处认为缺、另一处认为不缺。
    """
    from smartbi.gold.restaurant.data_gaps import honest_gap_answer

    return await honest_gap_answer(pool, factory_id, query or "")


def _drop_planner_invented_metrics(spec, query):
    """去掉「用户从没提过、planner 自己加上」的指标要求。

    🔴 2026-08-07 prod 落库记录(smart_bi_llm_fallback_log.agg_meta)给出的根因:
       query='最近30天各门店对比如何'
       analysis_action=compare  dimensions=['store']
       requested_metrics=['revenue','orders','sales_volume']
       planned_intents=['RESTAURANT_OPS_STORE_MARGIN','RESTAURANT_OPS_SALES_SUMMARY']
       contract_pass=false
    用户只说了「各门店对比如何」—— 三个指标全是 T3 自己编的。而
    `_request_coverage_present` 要求答案文本里**每个** requested_metric 都出现对应词,
    答案给了营收/订单却没有「销量」, 于是契约不过 -> 用户拿到反问。

    🔑 判据: **契约的目的是防「答非所问」, 而用户从没提过的指标不可能让答案变成
    答非所问** —— 它只能造成假拒。所以要求覆盖的应该是「用户问了什么」,
    不是「planner 想到了什么」。

    ⛔ 只去掉**原句里一个词都没沾**的指标。用户说了「各门店**销量**对比」,
    sales_volume 就是他要的, 答案没给就该老实说没给 —— 那种失败是真的。

    ⛔ 复用 answer_contract._REQUEST_TEXT_TOKENS, **不另建一张词表**:
    判「用户提没提」与判「答案答没答」必须用同一份词, 两份迟早会打架
    (本轮已经栽过一次「喂 LLM 的文本与校验事实集不是同一份」)。
    """
    from dataclasses import replace as _replace
    from smartbi.gold.restaurant.answer_contract import _REQUEST_TEXT_TOKENS

    requested = tuple(getattr(spec, "requested_metrics", ()) or ())
    if not requested or not query:
        return spec

    kept = tuple(
        m for m in requested
        # 没登记词表的指标一律保留 —— 判不了就别动(同维度那条的处理)。
        if not _REQUEST_TEXT_TOKENS.get(m)
        or any(tok in query for tok in _REQUEST_TEXT_TOKENS[m])
    )
    if kept == requested:
        return spec

    logger.info(
        "[restaurant-contract] 去掉 planner 自造的指标要求: %s -> %s query=%r",
        requested, kept, query[:60],
    )
    return _replace(spec, requested_metrics=kept)


def _drop_unanswerable_mislabeled_dimensions(spec, plan, query):
    """见调用点的注释。返回可能被去掉误标维度的 spec（无改动时原样返回）。"""
    from dataclasses import replace as _replace
    from smartbi.gold.restaurant.restaurant_intent import _query_names_an_ingredient

    supported = set().union(
        *(_RESOLVER_DIMENSIONS.get(code, frozenset()) for code in plan)
    ) if plan else set()

    #: 粒度 -> 「这个粒度上有没有点名实体」的判定。只登记**能判**的粒度;
    #: 判不了的粒度一律不动 —— 猜错的代价是答非所问。
    namers = {"ingredient": _query_names_an_ingredient}

    dropped = []
    kept = []
    for dim in spec.dimensions:
        namer = namers.get(dim)
        if namer is not None and dim not in supported and not namer(query or ""):
            dropped.append(dim)
        else:
            kept.append(dim)
    if not dropped:
        return spec

    logger.info(
        "[restaurant-intent] 去掉误标且无 resolver 支持的维度: dropped=%s "
        "supported=%s plan=%s query=%r",
        dropped, sorted(supported), tuple(plan), (query or "")[:60],
    )
    return _replace(spec, dimensions=tuple(kept))


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
    elif spec.ranking_direction and (
        "dish" in spec.dimensions or bool(spec.dish_slot)
    ):
        topic_kind = "dish_ranking"
    elif (
        isinstance(focus, dict)
        and focus.get("type") == "store"
        and focus.get("rank") is not None
    ):
        topic_kind = "store_ranking"
    elif spec.ranking_direction and (
        "store" in spec.dimensions
        or spec.store_scope in {"all", "multiple"}
    ):
        topic_kind = "store_ranking"
    return {
        "plan_hash": spec.plan_hash,
        "plan_version": spec.plan_version,
        "focus_entity": focus,
        "window_label": spec.window_label,
        "requested_metrics": list(spec.requested_metrics),
        "analysis_action": spec.analysis_action,
        "comparison_kind": spec.comparison,
        "comparison_label": spec.comparison_label,
        "comparison_range": [
            value.isoformat() if hasattr(value, "isoformat") else value
            for value in spec.comparison_range
        ],
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
        # 换范围按钮要按它查 resolver 支不支持 store 粒度
        # (见 _store_scope_switch_followups)。
        "intent": spec.intent,
        "store_scope": spec.store_scope,
        "store_names": list(spec.store_slots),
        # 换范围按钮要拿它列可选门店(见 _store_scope_switch_followups)。
        "store_options": list(spec.store_options),
        # 换范围按钮必须发**完整问句**而不是裸范围词 —— 2026-07-31 实测: 只发
        # 「模拟·徐汇美罗城店」回来的是「查询维度超出计划 resolver 的能力范围」,
        # 按钮等于哑弹。同一条答案里能用的「看本月」之所以能用, 正是因为它带的
        # 是完整问句。
        "question_seed": str(getattr(spec, "resolver_query_seed", "") or ""),
        "compare_stores": spec.compare_stores,
    }


def _clarification_structured_context(spec: RestaurantQuerySpec) -> Dict[str, Any]:
    return _structured_context(
        spec,
        {},
        dish_mention=spec.dish_slot,
        store_mention=spec.store_slot,
    )


def _split_store_scope(
    seed: str, store_names: Sequence[str] = (),
) -> Tuple[str, str]:
    """把问句开头已有的门店范围切下来 → ``(前缀, 余下)``, 前缀可能是空串。

    只切**开头**的完整范围词/店名; 绝不在句中乱删, 那会改变问题本身
    (「哪家店」这种词出现在句中是问题的一部分)。

    换门店按钮只要「余下」(见 `_strip_store_scope`); 换**时间**按钮要把前缀原样
    拼回去, 否则「全部门店上个月…」点一下会退化成「本月…」—— 悄悄把范围从全部
    门店换成了会话继承的那个, 用户只想换时间。
    """
    text = (seed or "").strip()
    prefixes = ["全部门店", "所有门店", "全部店", "各门店"]
    # 长的先试, 免得短店名把长店名截断成半截。
    prefixes += sorted(
        (name.strip() for name in store_names if name and name.strip()),
        key=len, reverse=True,
    )
    for prefix in prefixes:
        if text.startswith(prefix):
            return prefix, text[len(prefix):].strip()
    return "", text


def _strip_store_scope(seed: str, store_names: Sequence[str] = ()) -> str:
    """`_split_store_scope` 的余下那一半 (换门店按钮用)。"""
    return _split_store_scope(seed, store_names)[1]


# 换时间按钮给出的候选窗口。与 `_clarification_followups` 的时间澄清选项**同源**
# —— 两处一漂, 用户就会在澄清里看到一组窗口、在按钮上看到另一组。
_SWITCHABLE_WINDOWS: Tuple[str, ...] = ("本月", "上个月", "最近7天", "最近30天")

# 问句开头可能出现的时间说法。只用于**剥离**, 比 `_SWITCHABLE_WINDOWS` 宽:
# 用户会写「这个月」「上周」, 而按钮只回给规范说法。长的排前面, 免得
# 「最近7天」被「最近7」之类的短前缀截半 (排序在下面统一做)。
_STRIPPABLE_TIME_PREFIXES: Tuple[str, ...] = _SWITCHABLE_WINDOWS + (
    "这个月", "本周", "上周", "本季度", "上季度", "今天", "昨天", "前天",
    "今年", "去年", "最近三十天", "最近七天", "最近30日", "最近7日",
)

# 绝对月份/日期写法: 「2026年6月」「2026年6月份」。
_ABSOLUTE_TIME_PREFIX_RE = re.compile(r"^\d{4}年\d{1,2}月份?")


def _split_time_scope(seed: str) -> Tuple[str, str]:
    """把问句开头的时间说法切下来 → ``(前缀, 余下)``, 前缀可能是空串。

    与 `_split_store_scope` 同一条纪律: 只动**开头**。句中的时间词是问题的一部分
    (「六月比七月高多少」里的月份不能碰)。
    """
    text = (seed or "").strip()
    match = _ABSOLUTE_TIME_PREFIX_RE.match(text)
    if match:
        return match.group(0), text[match.end():].strip()
    for prefix in sorted(_STRIPPABLE_TIME_PREFIXES, key=len, reverse=True):
        if text.startswith(prefix):
            return prefix, text[len(prefix):].strip()
    return "", text


def _time_window_switch_followups(context: Dict[str, Any]) -> List[Dict[str, str]]:
    """答案末尾的「换时间范围」按钮。

    门店范围已有显式出口(`_store_scope_switch_followups`), 时间范围没有 —— 用户
    想「同一个问题看上个月」只能自己重新打一遍问句。

    🔴 只在 resolver **真的会按请求的窗口取数**时才给。判据是签名里有没有
    `date_range`, 即 `resolve_by_code` 过滤 kwargs 用的同一条 —— 见
    `resolver_supports_explicit_window` 里为什么不能用 `_RESOLVER_DIMENSIONS['time']`。
    给一个拿不到窗口的 resolver 配按钮, 点下去会「看起来有反应、其实答的是另一个
    时间窗」, 比按钮报错更隐蔽。
    """
    if not resolver_supports_explicit_window(context.get("intent")):
        return []
    seed = str(context.get("question_seed") or "")
    if not seed.strip():
        # 拼不出完整问句就不给按钮 —— 与换范围按钮同一条纪律。
        return []
    store_prefix, remainder = _split_store_scope(
        seed,
        store_names=[
            name for name in (context.get("store_options") or [])
            if isinstance(name, str)
        ],
    )
    current_window, body = _split_time_scope(remainder)
    if not body:
        # 整句就是一个时间词, 换掉之后什么都不剩。
        return []
    # 当前窗口不再给一遍。两个来源都要排除: 规划层的 window_label, 以及问句里
    # 实际写着的那个 —— 后者在 window_label 缺失时是唯一线索。
    already = {
        str(context.get("window_label") or "").strip(),
        current_window.strip(),
    }
    return [
        {
            "label": f"看{window}",
            "question": f"{store_prefix}{window}{body}",
        }
        for window in _SWITCHABLE_WINDOWS
        if window not in already
    ][:2]


def _store_scope_switch_followups(context: Dict[str, Any]) -> List[Dict[str, str]]:
    """答案末尾的「换门店范围」按钮。

    2026-07-30: 门店范围现在会在同一个会话里串钩上一轮的选择(见
    `_inherited_store_scope`), 所以用户不再被反复追问 —— 代价是范围变成了隐式的。
    这几个按钮就是那个代价的解药: 让「我这次想看别的范围」有一个显式出口, 否则
    用户得自己想到要在问句里写门店名。

    只在**真的有得换**时出现: 单店租户(store_scope="single")没有第二个选择,
    给它按钮纯属噪音。
    """
    scope = context.get("store_scope")
    if not isinstance(scope, str) or scope in ("", "single"):
        return []
    # 🔴 只有当前 resolver **真能按门店拆**时才给这个按钮。
    # 2026-07-31 实测: 损耗答案上给出「只看某某店…」, 点下去回来
    # 「查询维度超出计划 resolver 的能力范围」—— WASTAGE_TOP 只服务 ingredient
    # 粒度, 按门店拆它本来就不支持。按钮发的问句格式对了也没用, **它提供的是一个
    # 系统答不了的问题**。判据复用 `_RESOLVER_DIMENSIONS`(下游拒答用的同一张表),
    # 不另造口径 —— 两处一漂, 表现就又是「按钮点了报错」。
    intent = context.get("intent") or ""
    if "store" not in _RESOLVER_DIMENSIONS.get(intent, frozenset()):
        return []
    # Forecast staffing serves the store grain by returning every store in its
    # FactBook, so store+time questions are executable.  It does not yet
    # consume a named-store filter from the natural-language query, however.
    # Do not turn execution-grain support into a false “只看 A 店” promise.
    if intent == "RESTAURANT_OPS_STAFFING_ADVICE":
        return []
    current = {
        name for name in (context.get("store_names") or [])
        if isinstance(name, str)
    }
    # 🔴 按钮发的必须是**能独立成立的问句**。裸范围词点下去, 下一轮没有待答澄清
    # 可以承接它, 于是被当成一个新问题 —— 2026-07-31 实测回来的是「查询维度超出
    # 计划 resolver 的能力范围」。所以把范围拼回这一轮的问句上。
    seed = _strip_store_scope(
        str(context.get("question_seed") or ""),
        # 换店时上一轮问句开头可能是某个具体店名, 也要剥掉, 否则拼出
        # 「模拟·徐汇美罗城店模拟·静安嘉里中心店最近30天…」。
        store_names=[
            name for name in (context.get("store_options") or [])
            if isinstance(name, str)
        ],
    )
    if not seed:
        # 没有可拼的问句就不给按钮 —— 宁可不给, 也不给一个点了会出错的。
        return []
    options: List[Dict[str, str]] = []
    if scope != "all":
        options.append({"label": "看全部门店", "question": f"全部门店{seed}"})
    for name in context.get("store_options") or []:
        if len(options) >= 3:
            break
        if isinstance(name, str) and name.strip() and name not in current:
            options.append({"label": f"只看{name[:10]}", "question": f"{name}{seed}"})
    return options


def _topic_followups(context: Dict[str, Any]) -> List[Dict[str, str]]:
    # ⛔ 这里原本有一个 dish_ranking / store_ranking 的时间按钮分支, 2026-07-31
    # 删除。它发的是**写死的泛问句**(「本月哪个菜卖得最好？」): 问「上个月毛利最低
    # 的三道菜」点「看本月」, 回来的是一个**换了问题**的答案, 而标签读起来是
    # 「同一个问题、换个月」。时间按钮现在统一走 `_time_window_switch_followups`
    # (复用原问句 + 能力闸), 只有一条路径。
    #
    # 覆盖面不减: 这两类话题由 GROSS_MARGIN / STORE_MARGIN / SALES_SUMMARY 承接,
    # 三个都声明了 date_range, 都会拿到时间按钮。
    focus = context.get("focus_entity")
    if not isinstance(focus, dict) or not focus.get("name"):
        return []
    current_metrics = set(context.get("requested_metrics") or [])
    if focus.get("type") == "dish":
        candidates = [
            ("sales_volume", "看菜品销量", "这个菜的销量呢？"),
            ("recipe_cost", "看菜品成本", "这个菜的成本呢？"),
            ("gross_margin", "看菜品毛利", "这个菜的毛利率呢？"),
        ]
    else:
        candidates = [
            ("revenue", "看门店营收", "这家店的营收呢？"),
            ("gross_margin", "看门店毛利", "这家店的毛利率呢？"),
        ]
    return [
        {"label": label, "question": question}
        for metric, label, question in candidates
        if metric not in current_metrics
    ][:2]


def _suggested_followups(context: Dict[str, Any]) -> List[Dict[str, str]]:
    """答案末尾的按钮 = 话题相关的追问 + 换门店范围。

    换范围排在后面: 用户多数时候是想接着往下问, 换范围是少数动作 —— 但它必须
    **存在**, 因为门店范围现在会隐式串钩上一轮(见 restaurant_intent
    `_inherited_store_scope`), 没有出口的话用户只能自己想到在问句里写门店名。
    按 question 去重, 免得两边给出同一个按钮。
    """
    combined: List[Dict[str, str]] = []
    seen = set()
    for item in (
        *_topic_followups(context),
        *_time_window_switch_followups(context),
        *_store_scope_switch_followups(context),
    ):
        question = item.get("question")
        if not question or question in seen:
            continue
        seen.add(question)
        combined.append(item)
    return combined[:4]


def _clarification_followups(spec: RestaurantQuerySpec) -> List[Dict[str, str]]:
    """Render LLM-selected choices after they passed factual allowlists."""
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
    if spec.clarification_options:
        return [
            {"label": option[:12], "question": option}
            for option in spec.clarification_options[:6]
        ]
    return []


def _resolver_kwargs(
    spec: RestaurantQuerySpec,
    role: Optional[str],
    query: str,
) -> Dict[str, Any]:
    kwargs: Dict[str, Any] = {
        "role": role,
        "query": query,
        "requested_metrics": tuple(spec.requested_metrics),
        "analysis_action": spec.analysis_action,
        "ranking_direction": spec.ranking_direction,
        "ranking_limit": spec.ranking_limit,
    }
    start, end = spec.date_range
    if start is not None and end is not None and hasattr(end, "__sub__"):
        try:
            kwargs["days"] = max(1, min((end - start).days + 1, 365))
            kwargs["date_range"] = (start, end)
            kwargs["window_label"] = spec.window_label
        except (AttributeError, TypeError):
            pass
    comparison_start, comparison_end = spec.comparison_range
    if comparison_start is None and comparison_end is None:
        # Legacy plans created before comparison windows became immutable
        # first-class slots still get the previous parser-based behavior.
        sales_spec = _resolve_sales_query_spec(query)
        comparison_start, comparison_end = sales_spec.comparison_range
    if comparison_start is not None and comparison_end is not None:
        kwargs["comparison_date_range"] = (comparison_start, comparison_end)
        kwargs["comparison_label"] = spec.comparison_label
        kwargs["comparison_kind"] = spec.comparison
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
    from smartbi.gold.restaurant.restaurant_ops_router import OpsAnswer

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


async def _resolve_business_optimization(
    pool,
    factory_id: str,
    query: str,
    spec: RestaurantQuerySpec,
    history: Optional[Sequence[Dict[str, Any]]],
):
    """Use the existing grounded multi-dimension engine for owner actions."""
    from smartbi.agent.synthesis_engine import ComprehensiveSynthesisEngine
    from smartbi.api.synthesis import _resolve_window
    from smartbi.gold.restaurant.restaurant_ops_router import OpsAnswer

    start, end = spec.date_range
    if start is None or end is None:
        date_range = await _resolve_window(
            pool,
            factory_id,
            None,
            None,
            question=query,
        )
    else:
        date_range = (start, end)
    response = await ComprehensiveSynthesisEngine(pool).synthesize(
        factory_id,
        query,
        date_range,
        conversation_history=list(history or [])[-20:] or None,
    )
    coverage = response.dimension_coverage or {}
    available_dimensions = coverage.get("available_dimensions") or []
    current_week_without_data = (
        not available_dimensions
        and any(token in query for token in ("这周", "本周"))
    )
    if current_week_without_data:
        answer = (
            "本周截至今天还没有可用的经营数据，暂时不能可靠判断怎么提高营收，"
            "也不会拿其他周期的数据冒充本周结果。你可以改看最近7天、上周或最近30天；"
            "我会保留“提高营收并给出今天能执行的动作”这个目标继续分析。"
        )
    else:
        answer = sanitize_customer_ai_text(response.answer or "")
    if not any(
        marker in answer
        for marker in ("优化建议", "优化动作", "行动建议", "接下来可以怎么做")
    ):
        answer = f"优化建议\n{answer}".strip()
    meta = {
        "scope_matches_request": True,
        "synthesis_source": response.source,
        "synthesis_plan": response.plan,
        "fact_check": response.fact_check,
        "dimension_coverage": coverage,
    }
    if current_week_without_data:
        meta.update({
            "no_pos_data": True,
            "period_data_unavailable": True,
            "suggested_followups": [
                {
                    "label": "最近7天",
                    "question": "最近7天全部门店营收怎么提高，给我今天能做的动作",
                },
                {
                    "label": "上周",
                    "question": "上周全部门店营收怎么提高，给我今天能做的动作",
                },
                {
                    "label": "最近30天",
                    "question": "最近30天全部门店营收怎么提高，给我今天能做的动作",
                },
            ],
        })
    return OpsAnswer(
        code="RESTAURANT_OPS_BUSINESS_OPTIMIZATION",
        title="经营诊断与提升方案",
        answer_text=answer,
        charts=response.charts or [],
        kpis=[],
        meta=meta,
    )


async def tiered_answer(
    query: str,
    pool,
    factory_id: str,
    role: Optional[str],
    *,
    java_tool_name: Optional[str] = None,
    session_key: Optional[str] = None,
    history: Optional[Sequence[Dict[str, Any]]] = None,
    precomputed_spec: Optional[RestaurantQuerySpec] = None,
    allow_decompose: bool = True,
    include_result_meta: bool = False,
    capture_source: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """Execute one contract-checked restaurant query plan.

    Natural-language requests always go through ``parse_restaurant_query``.
    Keyword/vector matches are candidate hints only; a v2 plan can execute
    only when its authority is the LLM planner, a validated plan-cache hit, or
    the reviewed whole-sentence exact registry. Non-restaurant tenants still
    return ``None``. Once a v2 plan exists, resolver misses, exceptions,
    route/scope drift, and contract failures are fail-closed clarifications
    and never fall through to an adjacent intent.

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

    ``include_result_meta`` (spec §3.1 卡 C1 预警计划化, 2026-07-29): when True
    the answer dict carries an extra ``result_meta`` key -- the resolver
    execution receipt (``_execution_receipt``) that ``structured_context`` is
    already derived from. The scheduled plan-alert runner
    (``smartbi/gold/restaurant/plan_alert.py``) reads its thresholds from
    ``result_meta["comparison"]``, which is the one numeric outlet that is
    stable across requests AND carries the honest ``primary_no_data`` /
    ``baseline_no_data`` / ``coverage_mismatch`` markers ``kpis`` does not.
    Default ``False`` keeps the returned dict byte-identical for every
    existing caller (chat SSE / Java delegate / mobile-rest-ai).

    ``capture_source`` (same change): overrides the fire-and-forget capture
    tag so a machine-initiated replay is distinguishable from a human question
    in the flywheel console. ``None`` (every existing caller) keeps the prior
    behavior -- ``"java_entry_delegate"`` when ``java_tool_name`` is set, else
    no tag.
    """
    # ── 额度耗尽 → 整体封闭，任何层都不产出答案 ───────────────────────────
    #
    # 🔴 Steve 2026-08-07 拍板：「如果 LLM 的额度没有了，那整个 AI 的，不管是
    #    第一层还是第二层，全部封闭掉」。
    #
    # 🔑 为什么必须在**最前面**：确定性层（晋升表回放 / 已知缺口 / 域外拒答）压根
    #    不调 LLM —— 链已经死透时它们照样能产出答案。而**部分可用比完全不可用
    #    更危险**：用户无法判断此刻这个答案为什么这么简单、可不可信。
    #    2026-08-07 实测过一次真实全线熔断，当时确定性路径仍在 43ms 内作答，
    #    我曾把它当成优点写进验收 —— 那是**反的**。
    #
    # ⛔ 缺口前置与域外拒答也一并关（计划文档 §4）：它们是纯事实、零风险，但用户
    #    分不清「这条是可信的事实」和「那条是因为 AI 挂了才这么答」。
    if not _llm_capacity_available():
        logger.warning(
            "[restaurant-intent] LLM 额度/链路不可用 -> 整体封闭 AI 入口: q=%r",
            (query or "")[:40],
        )
        return {
            "kind": "unavailable",
            "answer_text": (
                "**AI 助手当前不可用**：模型额度或链路暂时用尽，我不会用降级方式"
                "回答，以免给出一个你无法判断可信度的结果。请稍后再试；"
                "经营数据本身不受影响，可以直接看驾驶舱和各业务页面。"
            ),
            "charts": [],
            "kpis": [],
            "title": "AI 暂不可用",
            "code": "RESTAURANT_AI_UNAVAILABLE",
            "contract_pass": False,
            "spec": None,
        }

    capture_source = capture_source or (
        "java_entry_delegate" if java_tool_name else None
    )
    # ── 已知数据缺口: 在**路由之前**判 ──────────────────────────────────
    #
    # 🔴 2026-08-07 三轮实测:「最近30天哪个供应商报价最贵」在 B / C / D 三种归宿之间
    #    来回飘。B 类文案本身是好的(实测返回过「供应商报价目前还没有数据…录入后就能
    #    做比价」), 问题在于它**挂在 `resolve_out_of_domain` 的兜底分支上** ——
    #    T3 只要把这句路由到别的 resolver 或判成需要澄清, 那条分支就永远走不到。
    #
    # 🔑 判据: **「这件事我们还没有数据」是关于数据的事实, 不是关于路由的结论。**
    #    它不该依赖 LLM 恰好把问句分到某一个出口。提到路由之前, 归宿就稳定了,
    #    顺带省掉一次 LLM(命中时 0 token)。
    #
    # ⛔ 仍然**真查表**: 表里有行 / 查不动 都返回 None, 照旧走原来的完整链路。
    #    把「没数据」写死才是降级处理, 查过之后说没有不是。
    try:
        _gap = await _known_data_gap(pool, factory_id, query)
    except Exception:  # noqa: BLE001 - 缺口探测坏了不能拖垮正常问答
        logger.warning("[data-gaps] 缺口预检失败, 照旧走完整链路", exc_info=True)
        _gap = None
    if _gap is not None:
        logger.info(
            "[data-gaps] 路由前命中已知缺口 -> 直接给 B 类答案(0 LLM): subject=%s table=%s",
            _gap["subject"], _gap["table"],
        )
        return {
            "kind": "answer",
            "answer_text": _gap["answer_text"],
            "charts": [],
            "kpis": [],
            "title": f"{_gap['subject']}：暂无数据",
            "code": "RESTAURANT_OPS_DATA_GAP",
            "contract_pass": True,
            "spec": precomputed_spec,
            "meta": {"data_gap": True, "missing_table": _gap["table"]},
        }

    spec = precomputed_spec
    action_warning: Optional[str] = None
    try:
        if spec is None:
            # 菜单目录裁决「这句话里有没有菜」, 必须在规划之前就位 ——
            # 否则「本月人力成本是多少」会把「人力」当菜名并规划出一个菜品意图。
            async with dish_catalogue_scope(pool, factory_id):
                spec = await parse_restaurant_query(
                    query,
                    pool,
                    factory_id=factory_id,
                    history=history,
                    session_key=session_key,
                    semantic_first=True,
                )
        if spec is None:
            return None
        action_warning = _read_only_action_warning_for_spec(query, spec)

        if spec.clarification_needed or not spec.intent:
            clarification_text = (
                spec.clarification_question
                or "能再具体说说想看哪方面的数据吗？比如营收、毛利、损耗还是库存盘点。"
            )
            clarification_result = {
                "kind": "clarification",
                "answer_text": _prepend_action_warning(
                    clarification_text,
                    action_warning,
                ),
                "structured_context": _clarification_structured_context(spec),
                "spec": spec,
            }
            followups = _clarification_followups(spec)
            if followups:
                clarification_result["suggested_followups"] = followups
            if action_warning:
                clarification_result["warning"] = action_warning
            return clarification_result

        # The complete LLM decision has already been made above. Compound
        # decomposition is only an execution strategy beneath that plan. A
        # clarification always wins, so deterministic splitting cannot bypass
        # a question the semantic planner decided it still needs to ask.
        if allow_decompose:
            from smartbi.gold.restaurant.restaurant_agent import (
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
                            history=history,
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
        from smartbi.gold.restaurant.restaurant_ops_router import (
            extract_dish_candidate,
            extract_dish_candidates,
            store_dish_split_dish,
        )
        # The sealed QueryPlan is the execution authority.  Re-running a
        # best-effort regex over the contextualized resolver query can see
        # wording from an older turn and must never replace the already
        # validated dish slot (live example: “那成本呢” after a rice query).
        dish_mention = (
            getattr(spec, "dish_slot", None)
            or extract_dish_candidate(resolver_query)
            or extract_dish_candidate(query)
        )
        # R18: 店×菜下钻 — store_dish_rows 本就是店×菜粒度, 路由 STORE_MARGIN
        # 带 dish_mention 直答 (匿名「哪家店的X」排名 / 具名店+菜单店直答)。
        split_dish = store_dish_split_dish(query) or store_dish_split_dish(resolver_query)
        store_dish = split_dish or (dish_mention if store_mention else None)
        # ── 去掉**误标且无人能答**的维度 ────────────────────────────────
        #
        # 🔴 2026-08-07 prod 实测:「最近30天**食材成本**占营收多少」被 T3 标成
        #    dimensions=('ingredient',), 而它问的是**全店比率**(食材成本/营收),
        #    没有任何按食材的拆分。计划里的 resolver 都不支持 ingredient 粒度,
        #    于是被下面的 _execution_mismatch 拦成「查询维度超出计划 resolver 的
        #    能力范围」, 用户拿到一句反问。
        #
        # 判据与「『**加权**毛利率』的『加权』被当成菜名」同源:
        # **维度由「问的是哪个粒度」决定, 不是由句子里出现了哪个名词决定。**
        #
        # ⛔ 两个条件必须同时成立才去掉:
        #    1) 那个粒度上**一个实体都没点名**(点名了就是真按那个粒度问的);
        #    2) 计划里**没有任何 resolver 支持**该粒度 —— 支持的话本来就能答,
        #       动它就会把「损耗最高的食材是哪个」这类真·食材粒度问题弄坏。
        #    只满足 1) 不够: 实测 `extract_dish_candidate` 对「鲈鱼的损耗多少」
        #    也返回 None(它是按菜品-指标句式调的), 只凭它会**无条件**剥掉维度。
        #
        # 最坏情况只是把「必然被拦下」变成「按更粗的粒度回答」, 而答案契约仍会
        # 校验; 绝不会把一个本来能按细粒度答的问题降级。
        spec = _drop_unanswerable_mislabeled_dimensions(spec, plan, query)

        mismatch = _execution_mismatch(
            spec,
            plan,
            dish_mention=dish_mention,
            store_mention=store_mention,
            store_dish=store_dish,
        )
        if mismatch:
            # 🔴 这道闸会把一个用户问题挡成反问, 却**一行日志都不留** ——
            # 2026-08-07 排查 G1 里两条 D(「哪个时段生意最好」/「食材成本占营收
            # 多少」)时, 用户侧只看得到「查询维度超出计划 resolver 的能力范围」,
            # 服务端查不到是哪个意图、哪个维度超了, 离线也复现不了(要真 T3)。
            #
            # 判据: **会拦下用户问题的闸, 必须留下它拦的是什么**。否则下一个人
            # 只能靠猜, 或者靠放宽能力表 —— 而 `_RESOLVER_DIMENSIONS` 是能力
            # **声明**, resolver 不支持却放宽, 等于用错粒度回答, 比反问更糟。
            logger.warning(
                "[restaurant-intent] 执行前拦截(维度/口径不匹配): reason=%s "
                "intent=%s planned=%s dimensions=%s metrics=%s "
                "dish=%r store=%r store_dish=%r query=%r",
                mismatch, spec.intent, tuple(plan), tuple(spec.dimensions),
                tuple(spec.requested_metrics), dish_mention, store_mention,
                store_dish, (query or "")[:60],
            )
            mismatch_result = {
                "kind": "clarification",
                "answer_text": _prepend_action_warning(
                    (
                        f"本次没有执行分析：{mismatch}。"
                        "请明确要看菜品、门店还是全店汇总，我不会改走相邻分析。"
                    ),
                    action_warning,
                ),
                "contract_pass": False,
                "structured_context": _clarification_structured_context(spec),
                "spec": spec,
            }
            if action_warning:
                mismatch_result["warning"] = action_warning
            return mismatch_result
        planned_results: List[Tuple[str, Any]] = []
        for code in plan:
            code_kwargs = execution_kwargs
            if code == "RESTAURANT_OPS_GROSS_MARGIN":
                explicit_dishes = extract_dish_candidates(resolver_query)
                if len(explicit_dishes) >= 2:
                    code_kwargs = dict(execution_kwargs)
                    code_kwargs["dish_mentions"] = explicit_dishes
            if code == "RESTAURANT_OPS_GROSS_MARGIN" and dish_mention:
                code_kwargs = dict(execution_kwargs)
                if len(explicit_dishes) >= 2:
                    code_kwargs["dish_mentions"] = explicit_dishes
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
            if code == "RESTAURANT_OPS_BUSINESS_OPTIMIZATION":
                resolved = await _resolve_business_optimization(
                    pool,
                    code_factory,
                    resolver_query,
                    spec,
                    history,
                )
            else:
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
                retry_meta = getattr(retry, "meta", None) or {}
                # Keep the primary data-space answer when the fallback merely
                # trades one missing entity for another.  Live Demo regression:
                # RES_3101_009 correctly said a selected store had no rice
                # sales, then DEMO_REST could not find that store at all and
                # the worse "store not found / gross margin" response replaced
                # the truthful sales answer.
                if retry is not None and not any(
                    marker in retry_meta
                    for marker in (
                        "dish_not_found",
                        "store_not_found",
                        "store_mention_ambiguous",
                    )
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
                empty_result = {
                    "kind": "clarification",
                    "answer_text": _prepend_action_warning(
                        (
                            "计划中的餐饮分析没有返回可验证结果，本次没有改走相邻分析。"
                            "请确认数据范围后重试。"
                        ),
                        action_warning,
                    ),
                    "contract_pass": False,
                    "structured_context": _clarification_structured_context(spec),
                    "spec": spec,
                }
                if action_warning:
                    empty_result["warning"] = action_warning
                return empty_result
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
            guard_result = {
                "kind": "clarification",
                "answer_text": _prepend_action_warning(
                    sanitize_customer_ai_text(
                        str(getattr(tiered_result, "answer_text", "") or "")
                    ),
                    action_warning,
                ),
                "structured_context": _clarification_structured_context(spec),
                "spec": spec,
            }
            if action_warning:
                guard_result["warning"] = action_warning
            guard_followups = guard_meta.get("suggested_followups")
            if isinstance(guard_followups, list) and guard_followups:
                guard_result["suggested_followups"] = guard_followups
            return guard_result

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
        answer_text += unsupported_requirements_disclosure(
            spec.unsupported_requirements
        )
        # ⚠️ 只把过滤后的 spec 交给契约, **不改 spec 本身**: requested_metrics
        # 还有别的消费者(后续提问建议按它生成), 就地改会顺带改掉那些人的行为 ——
        # 第一版正是这么写的, 被 test_tiered_answer_returns_typed_focus_entity_and_followups
        # 当场抓到(建议从「看菜品成本」变成了「看菜品销量」)。
        contract = _contract.validate(
            _drop_planner_invented_metrics(spec, query),
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
            safe_text = _prepend_action_warning(safe_text, action_warning)
            asyncio.create_task(log_intent_capture(
                pool, spec, factory_id=factory_id, query=query,
                answer=safe_text, contract_pass=False, served=False,
                source=capture_source,
            ))
            failed_result = {
                "kind": "clarification",
                "answer_text": safe_text,
                "contract_pass": False,
                "structured_context": _clarification_structured_context(spec),
                "spec": spec,
            }
            if action_warning:
                failed_result["warning"] = action_warning
            return failed_result
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

        # 挂在契约校验**之后**: 范围声明是元信息, 不是业务结果。放在校验前会让
        # has_displayable_business_result 把这行括号当成「有可展示结果」, 于是一个
        # 本该被拦下的空答案会因为多了一句范围说明而蒙混过关。
        answer_text += _store_scope_disclosure(spec)
        answer_text += _time_range_disclosure(spec)
        answer_text = _prepend_action_warning(answer_text, action_warning)

        # 优先级链第 1 层（会话/租户配置）—— 输出口径这一项。
        # ⛔ fail-open：取不到就是 None，`resolve_output_preference` 会落到全局默认。
        #    配置查不动不该让一次问答失败。
        from smartbi.shared.async_config_lookup import resolve_config

        tenant_output_pref = await resolve_config(
            pool, factory_id, "restaurant.output_preference", default=None
        )
        if isinstance(tenant_output_pref, str):
            # JSONB 存的可能是 '["text","table"]' 也可能是裸串 'table'。
            # 两种都收，但**不猜**：解析不出来就当没配。
            import json as _json

            try:
                parsed = _json.loads(tenant_output_pref)
                tenant_output_pref = parsed if isinstance(parsed, list) else [parsed]
            except (TypeError, ValueError):
                tenant_output_pref = [tenant_output_pref]

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
            # spec §2.1: 渲染层按它分支 (文字/表格/图/报告文件)。这里给的是**已解析
            # 好的最终形态** —— 前端不该自己再猜租户默认, 否则 web-admin /
            # mobile-rest-ai / RN 三处会各猜一套, 客户口径立刻分裂。
            # 表格不需要后端新数据通道: charts 里 xAxis.data + series[].data 够渲染。
            # 2026-08-07: `tenant_default` 终于接上了 —— 该参数从设计之初就在
            # (`resolve_output_preference` 的 docstring 写着「客户口径注册表落地后
            # 就是接线口」)，但一直没人传，于是租户级口径**形同虚设**。
            # 存储用的是早就存在的 `business_config_overrides`，不新建表；
            # 读取走 `shared/async_config_lookup`（异步侧唯一承载，见该模块 docstring）。
            "output_preference": list(
                resolve_output_preference(spec, tenant_default=tenant_output_pref)
            ),
            "query_plan_hash": spec.plan_hash,
            "executed_resolvers": list(executed_codes),
            "structured_context": structured_context,
            "suggested_followups": _suggested_followups(structured_context),
            "spec": spec,
        }
        if include_result_meta:
            # spec §3.1: the scheduled plan-alert runner reads its thresholds
            # from the resolver execution receipt. Opt-in so the SSE/chat
            # payload shape is unchanged for every existing consumer.
            result["result_meta"] = result_meta
        if action_warning:
            result["warning"] = action_warning
        asyncio.create_task(log_intent_capture(
            pool, spec, factory_id=factory_id, query=query,
            answer=answer_text, contract_pass=contract_pass, served=True,
            source=capture_source,
        ))
        return result
    except Exception as e:
        logger.warning(f"[restaurant-intent] tiered path failed: {e}")
        if spec is not None and spec.plan_version == "restaurant-query-plan-v2":
            failure_result = {
                "kind": "clarification",
                "answer_text": _prepend_action_warning(
                    (
                        "餐饮执行链暂时不可用，本次没有执行任何相邻分析。"
                        "请稍后重试。"
                    ),
                    action_warning,
                ),
                "contract_pass": False,
                "structured_context": _clarification_structured_context(spec),
                "spec": spec,
            }
            if action_warning:
                failure_result["warning"] = action_warning
            return failure_result
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
        from smartbi.gold.restaurant.restaurant_ops_router import (
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
        from smartbi.gold.restaurant.restaurant_agent import is_compound_question
        if is_compound_question(query):
            return True
        # 域外闲聊 (天气/新闻) — 必须由 tiered 给诚实拒答, 落回 Java 会拿到
        # 工厂措辞的通用助手回复 (R20b)。
        from smartbi.gold.restaurant.restaurant_ops_router import is_out_of_domain_smalltalk
        if is_out_of_domain_smalltalk(query):
            return True
        from smartbi.gold.restaurant.restaurant_ops_router import store_dish_split_dish
        if store_dish_split_dish(query):
            return True
        # 盈亏存在性问 ("有没有店在亏损") — 裸「亏损」不在 _profit_intent
        # 词典里, 规则 3 接不住; 存在性正则命中即放行 (R15b)。
        from smartbi.gold.restaurant.restaurant_ops_router import _NEGATIVE_MARGIN_EXISTENCE_RE
        if _NEGATIVE_MARGIN_EXISTENCE_RE.search(query):
            return True
        # 行业参考做法 (playbook) — intent 不在 MARGIN_CAPABLE, 规则 3 不放行;
        # 触发词命中即委托, tiered 层零 DB 直答 (R16b)。
        from smartbi.gold.restaurant.restaurant_playbook import PLAYBOOK_TRIGGERS as _PB_TRIGGERS
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
    # 🔴 2026-08-08 撤除向量层的高置信直通(R24 那条 `vector and confidence>=0.85`)。
    #
    # ⇒ 判据(Steve 定的): **置信度不作授权依据。** 相似度是连续量、不可证伪 ——
    #   「一段很长的话里只有一个字不一样(高 vs 低), 整体相似度还是很高的」,
    #   而那一个字正是意图的全部差别。0.85 这个数挡不住那种情况。
    #
    # 📌 这条不是新规矩, 是**回到 R24 之前**: 同一段注释里原本就把向量列为
    #   保留例外「T2 向量层置信弱, 仍须经上面的显式规则(选错意图会答错域)」,
    #   R24 又给它开了个高分口子。现在把口子关上, 向量 tier 一律走显式规则。
    #
    # prod 历史 17k 条里 vector tier 有 63 条(0.4%) —— 影响面小, 但它是活的,
    # 不是死代码; 而「向量从不授权」这条要成立就不能有例外。
    tier_trusted = spec.source_tier in ("keyword", "llm")
    if tier_trusted and not spec.clarification_needed:
        profit_ask = spec.asks_profitability or spec.wants_margin
        if not (profit_ask and spec.intent not in _MARGIN_CAPABLE_INTENTS):
            from smartbi.gold.restaurant.restaurant_ops_router import is_supported_restaurant_ops_code
            if is_supported_restaurant_ops_code(spec.intent):
                return True
    return False
