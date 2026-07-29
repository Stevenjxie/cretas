"""spec §3.1 卡 C1 — 预警 = 定时执行的 sealed QuerySpec + 阈值规则 (P1).

统一原则 R1: 交互问答 / 计划缓存 / 晋升路由 / 预警 全部执行**同一种** sealed
QuerySpec.  本模块是 R1 在预警侧的兑现: 它不新建计算引擎, 只是

    读规则表 (relative-time raw plan)
      -> 用 `_replay_plan_spec` 对 TODAY 重新编译并封章  (与晋升路由**同一个**
         编译器/校验器/authority, 不是平行实现)
      -> `tiered_answer(precomputed_spec=..., include_result_meta=True)` 执行
      -> 从 resolver 执行回执 `meta.comparison.*` 取数
      -> 过阈值
      -> 产出与 DiagnosticsEngine **同形状**的 diagnosis dict

产出的 dict 被 `api/restaurant_health_check.py` append 进同一个 `diagnoses`
列表, 经现有 `RestaurantHealthAlertBridgeService` 落成标准 AlertEvent.
这条 append 路径不是新发明 —— `supplier_price_anomaly` 早就这么做 (那也不是
DiagnosticsEngine 产的).  **告警引擎只有一套.**

⛔ 禁降级 (本模块最重要的不变量)
--------------------------------
「拿不到数」永远不等于「正常」.  `meta.comparison` 自带
`primary_no_data` / `baseline_no_data` / `coverage_mismatch` 诚实标记, 并且在
RBAC 脱敏或基数为 0 时把数值置 `None`.  任何一种情况都必须走 **unavailable**
分支 (不产诊断 + 上报前缀), 绝不能当成「阈值未触发」——因为「未触发」在下游
`RestaurantHealthAlertBridgeService` 的 standing-alert 语义里等于「已恢复」,
会 auto-resolve 掉一条真实存在的 OPEN 事件, 下一轮成功 sweep 又重建 = flap
重复推送 (这正是 F1 旗标当初为 supplier_price_anomaly 引入的原因).

⚠️ 为什么只读 `meta.comparison.*` 而不读 `kpis`
-----------------------------------------------
`OpsAnswer.kpis` 没有 schema, 实测六重不稳定: ①键名分裂 (14 个 resolver 用
`{title,value,rawValue}`, `resolve_store_directory` 用 `{label,value,unit}` 且
没有 `rawValue`) ②`value` 是格式化字符串 (`"¥1,234"` / `"12.3%"`) ③至少 10 个
文本型槽位硬编码 `"rawValue": 0` (0 是占位符不是值) ④数组长度随请求变
(`resolve_sales_summary` 条件性 append 毛利 KPI) ⑤部分 title 由数据拼出
(`f"{name}单量"`) ⑥RBAC 下 `value` 与 `rawValue` 双 `None`.
统一取数表达式跨 intent **不成立**.  规范化出口 (`OpsAnswer.metrics`) 是 P2,
要动 7200+ 行的 `restaurant_ops_router.py` 主链, 不在本轮范围.
因此 P1 能力边界 = 只支持 `RESTAURANT_OPS_SALES_SUMMARY` 的环比预警.
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

logger = logging.getLogger(__name__)

# 所有本模块产出的 metricKey 前缀.  Bridge 用它做 auto-resolve 的家族豁免,
# 所以它必须是稳定字面量 —— 改这里等于改一次线上事件的 businessEntityId.
METRIC_KEY_PREFIX = "plan_alert:"

# 整族不可用时上报的前缀 (规则表读不到 = 我们不知道任何一条规则的死活,
# 必须让 Bridge 跳过**所有** plan_alert:* 的 auto-resolve).
FAMILY_UNAVAILABLE_PREFIX = METRIC_KEY_PREFIX

# 捕获日志的来源标签 —— 让飞轮运营台能把机器发起的回放和真人提问分开.
# (晋升候选闸本来就要求 tier='llm', 回放出来是 exact, 不会污染晋升队列;
#  但 /overview 的问答量/命中率统计会算进去, 所以要能筛.)
CAPTURE_SOURCE = "plan_alert"

# P1 取数白名单.  只有这四个路径在 `resolve_sales_summary` 里被无条件写成
# 真实数值 (且带 no_data / coverage_mismatch 伴随标记).
# 见 restaurant_ops_router.py:6207-6262.
_ALLOWED_METRIC_PATHS: Dict[str, str] = {
    "comparison.revenue_change_pct": "营收环比变化率",
    "comparison.revenue_delta": "营收环比变化额",
    "comparison.bill_change_pct": "订单数环比变化率",
    "comparison.bill_delta": "订单数环比变化额",
}

# 百分比型路径 —— 只影响文案里的单位, 不影响比较逻辑.
_PERCENT_PATHS = frozenset({
    "comparison.revenue_change_pct",
    "comparison.bill_change_pct",
})

_OPS = {
    "lt": ("低于", lambda a, b: a < b),
    "lte": ("不高于", lambda a, b: a <= b),
    "gt": ("高于", lambda a, b: a > b),
    "gte": ("不低于", lambda a, b: a >= b),
}

# P1 只放行这一个 intent.  别的 intent 的 meta 要么没有结果数值
# (STOCK_SHORTAGE / RECIPE_COST / REQUISITION_TREND / CHANNEL_MIX 只有查询参数),
# 要么数值只在不稳定的 kpis 里 (TREND_ANALYSIS).
_SUPPORTED_INTENTS = frozenset({"RESTAURANT_OPS_SALES_SUMMARY"})

# 诚实标注: 数据新鲜度取决于上游 POS/财务同步节律, 不是秒级监控.
_LATENCY_DISCLAIMER = "（按数据刷新节律评估，非实时监控；实时性取决于 POS 数据接入）"

# 定时 sweep 没有登录用户, 但 resolver 的金额字段受 RBAC 门控:
# `resolve_sales_summary` 算 `can_see_money = bool(role) and role in
# PRICE_VIEW_ROLES` (restaurant_ops_router.py:5829). role=None ⇒ can_see_money
# 为 False ⇒ `comparison.revenue_*` 全被置 None ⇒ 所有营收类规则**永久**
# unavailable, 预警等于静默失效.
#
# 取 'restaurant_owner' 而不是 None, 依据是推送对象本身:
# RestaurantHealthAlertBridgeService.DEFAULT_NOTIFY_ROLES 就是
# ["restaurant_owner", "factory_super_admin"], 两者都在 PRICE_VIEW_ROLES 里 ——
# 也就是说这条预警的金额内容无论如何只会送达 price-view 角色, 用 owner 视角
# 求值不会让任何人看到他本来看不到的数字.
# (订单数类规则 comparison.bill_* 不受此门控, 任何 role 都能判定.)
SWEEP_ROLE = "restaurant_owner"


class RuleUnavailable(Exception):
    """这条规则**本次无法判定** —— 既不是触发也不是恢复.

    抛出它的调用方必须把 `plan_alert:<rule_code>` 上报为 unavailable, 让
    Bridge 跳过对该 metricKey 的 auto-resolve.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _dig(receipt: Dict[str, Any], dotted: str) -> Any:
    """按 dotted path 读执行回执.  任何一层不是 dict 都返回 None."""
    node: Any = receipt
    for part in dotted.split("."):
        if not isinstance(node, dict):
            return None
        node = node.get(part)
    return node


def extract_metric(receipt: Dict[str, Any], metric_path: str) -> float:
    """从 resolver 执行回执里取一个**可信数值**, 否则抛 RuleUnavailable.

    禁降级的全部判据集中在这里, 纯函数, 可单测 (无 DB / 无 LLM).
    """
    if metric_path not in _ALLOWED_METRIC_PATHS:
        raise RuleUnavailable(f"取数路径不在 P1 白名单内: {metric_path}")

    comparison = receipt.get("comparison")
    if not isinstance(comparison, dict):
        # 计划没带对比语义, 或 resolver 没解析出基线窗口.
        raise RuleUnavailable("本次执行没有产出环比对比数据（计划缺少对比窗口）")
    if comparison.get("answered") is not True:
        raise RuleUnavailable("本次执行没有回答对比问题")
    if comparison.get("primary_no_data") is True:
        raise RuleUnavailable("本期没有可用数据，无法判断环比（未用 0 代替）")
    if comparison.get("baseline_no_data") is True:
        raise RuleUnavailable("对比期没有可用数据，无法判断环比（未用 0 代替）")
    if comparison.get("coverage_mismatch") is True:
        raise RuleUnavailable("本期与对比期覆盖天数不同，口径不可比，本次不判定")

    value = _dig(receipt, metric_path)
    if value is None:
        # 三种来源: ①RBAC 剥零 (can_see_money=False) ②基数为 0 导致百分比
        # 无意义 ③resolver 走了不写该字段的分支.  都不是"正常".
        raise RuleUnavailable(
            f"{_ALLOWED_METRIC_PATHS[metric_path]}本次不可得"
            "（权限脱敏、对比基数为 0，或该分支未产出该指标）"
        )
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RuleUnavailable(
            f"{_ALLOWED_METRIC_PATHS[metric_path]}不是数值: {value!r}"
        )
    return float(value)


def _format_value(metric_path: str, value: float) -> str:
    if metric_path in _PERCENT_PATHS:
        return f"{value:.1f}%"
    if metric_path == "comparison.revenue_delta":
        return f"¥{value:,.2f}"
    return f"{value:,.0f}"


def build_diagnosis(
    rule: Dict[str, Any],
    value: float,
    comparison: Dict[str, Any],
) -> Dict[str, Any]:
    """把一条已触发的规则渲染成 DiagnosticsEngine 同形状的 diagnosis dict."""
    metric_path = str(rule["metric_path"])
    op = str(rule["threshold_op"])
    threshold = float(rule["threshold_value"])
    op_text = _OPS[op][0]
    metric_label = _ALLOWED_METRIC_PATHS[metric_path]

    primary_label = str(comparison.get("primary_label") or "本期")
    baseline_label = str(comparison.get("baseline_label") or "对比期")
    primary_range = _range_text(
        comparison.get("primary_start"), comparison.get("primary_end")
    )
    baseline_range = _range_text(
        comparison.get("baseline_start"), comparison.get("baseline_end")
    )

    desc = (
        f"{primary_label}{primary_range}对比{baseline_label}{baseline_range}："
        f"{metric_label} {_format_value(metric_path, value)}，"
        f"{op_text}预警阈值 {_format_value(metric_path, threshold)}。"
        f"{_LATENCY_DISCLAIMER}"
    )
    return {
        # 不含 period —— 这是 standing-alert dedup 的前提 (businessEntityId).
        "metricKey": f"{METRIC_KEY_PREFIX}{rule['rule_code']}",
        "metricNameZh": str(rule.get("rule_name") or metric_label),
        "severity": str(rule["severity"]),
        "status": "环比异常",
        "descriptionZh": desc,
        # 数字是 resolver 现算的真账, 不是估算.
        "estimated": False,
        "rxActions": [{
            "actionZh": (
                f"核对{primary_label}的经营动作（促销、门店开闭、渠道结构）"
                f"是否解释这一变化；必要时下钻到门店/菜品维度定位来源。"
            ),
        }],
    }


def _range_text(start: Any, end: Any) -> str:
    if start and end:
        return f"（{start} 至 {end}）"
    return ""


def evaluate_rule(rule: Dict[str, Any], receipt: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """规则 + 执行回执 -> 触发则返回 diagnosis dict, 未触发返回 None.

    无法判定时抛 `RuleUnavailable` (调用方负责上报 unavailable 前缀).
    纯函数, 无 IO —— 阈值语义的全部单测挂在这里.
    """
    metric_path = str(rule["metric_path"])
    op = str(rule["threshold_op"])
    if op not in _OPS:
        raise RuleUnavailable(f"未知比较符: {op}")

    value = extract_metric(receipt, metric_path)
    threshold = float(rule["threshold_value"])
    if not _OPS[op][1](value, threshold):
        # 明确的"本次正常" —— 缺席即恢复, 由 Bridge auto-resolve.
        return None
    comparison = receipt.get("comparison")
    return build_diagnosis(rule, value, comparison if isinstance(comparison, dict) else {})


async def load_plan_alert_rules(
    pool,
    factory_id: str,
    *,
    domain: str = "restaurant",
) -> List[Dict[str, Any]]:
    """读该租户启用中的预警规则.

    ⚠️ RLS: `restaurant_plan_alert_rules` 是 FORCE RLS 表, 且 '__internal__'
    在它上面是**万能钥匙**.  这里必须显式把 GUC 钉到提问租户, 而且必须在
    `async with conn.transaction()` 里 —— 本仓 asyncpg 池上, 事务外的
    `set_config(..., true)` 从不生效, 会静默回落到池默认 '__internal__'
    = 读到**每个**租户的规则.  与 `_load_promoted_routes` 同一写法.

    失败**不** fail-open: 抛出去, 由调用方转成整族 unavailable.  规则读不到
    时假装"没有规则"会让 Bridge 把所有 OPEN 的 plan_alert 事件当成已恢复.
    """
    async with pool.acquire() as conn:
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id
            )
            rows = await conn.fetch(
                """
                SELECT rule_code, rule_name, query_text, plan_json,
                       plan_version, metric_path, threshold_op,
                       threshold_value, severity
                  FROM restaurant_plan_alert_rules
                 WHERE factory_id = $1
                   AND domain = $2
                   AND enabled
                 ORDER BY rule_code
                """,
                factory_id, domain,
            )
    return [dict(row) for row in (rows or ())]


def _coerce_plan(raw: Any) -> Optional[Dict[str, Any]]:
    """asyncpg 未注册 JSONB codec 时会把 jsonb 交回字符串."""
    import json

    plan = raw
    if isinstance(plan, (bytes, bytearray)):
        plan = plan.decode("utf-8", "ignore")
    if isinstance(plan, str):
        try:
            plan = json.loads(plan)
        except (TypeError, ValueError):
            return None
    return plan if isinstance(plan, dict) else None


def compile_rule_spec(rule: Dict[str, Any]):
    """把存储的 relative-time raw plan 对 TODAY 重新编译成 sealed QuerySpec.

    走**晋升路由同一个** `_replay_plan_spec(authority_index=0)`, 因此得到的
    `planner_authority` 是 `promoted_exact` —— 与人工审核过的零 token 回放
    完全同权, 不引入新的 authority 字符串, 也不绕过执行契约.
    """
    from smartbi.gold.restaurant.restaurant_intent import (
        _PLAN_VERSION,
        _replay_plan_spec,
    )

    plan = _coerce_plan(rule.get("plan_json"))
    if plan is None:
        raise RuleUnavailable("规则的 plan_json 无法解析")
    if str(rule.get("plan_version") or "") != _PLAN_VERSION:
        # 计划契约升版 -> 旧规则不自动执行 (spec §1.6 版本化).
        raise RuleUnavailable(
            f"规则计划版本 {rule.get('plan_version')!r} 与当前契约 "
            f"{_PLAN_VERSION!r} 不一致，需重新审核"
        )
    intent = plan.get("intent")
    if intent not in _SUPPORTED_INTENTS:
        raise RuleUnavailable(
            f"P1 预警只支持 {sorted(_SUPPORTED_INTENTS)}，该规则是 {intent!r}"
        )
    # 定时预警绝不能钉在绝对区间上: `_parse_t3_time_range` 的 'absolute' 分支
    # 带 start/end 具体 ISO 日期, 存下来就等于每天对着同一个死窗口重复发预警,
    # 窗口永远不前进。交互问答里 absolute 是合法的 (用户就问了那个区间),
    # 定时执行里不是。DB 侧 chk_plan_alert_rules_time_not_absolute 是同一道闸。
    time_range = plan.get("time_range")
    if isinstance(time_range, dict) and time_range.get("type") == "absolute":
        raise RuleUnavailable(
            "规则计划使用了绝对时间区间，定时预警必须用相对/命名时间"
            "（否则每天都对同一个固定窗口重复告警）"
        )
    spec = _replay_plan_spec(
        plan,
        str(rule.get("query_text") or ""),
        available_stores=(),
        suggested_stores=(),
        authority_index=0,
    )
    if spec is None:
        raise RuleUnavailable("规则计划不满足执行契约（不可回放）")
    if spec.clarification_needed:
        raise RuleUnavailable("规则计划仍需澄清（缺少时间/门店范围），不能定时执行")
    return spec


async def run_plan_alerts(
    pool,
    factory_id: str,
    *,
    role: Optional[str] = SWEEP_ROLE,
    domain: str = "restaurant",
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """跑完该租户全部预警规则.

    :return: ``(diagnoses, unavailable_prefixes)``

    * ``diagnoses`` — 已触发的规则, DiagnosticsEngine 同形状, 直接 append 进
      体检报告的 ``diagnoses`` 列表.
    * ``unavailable_prefixes`` — 本次**无法判定**的 metricKey 前缀.  Bridge
      拿它跳过 auto-resolve, 防 flap.

    失败隔离粒度 = **单条规则**.  一条规则的计划编译失败 / 执行异常 / 取数不可得
    只让它自己进 unavailable, 其余规则照常判定.  只有"连规则表都读不到"才整族
    (``plan_alert:``) 不可用.
    """
    from smartbi.gold.restaurant.restaurant_intent_service import tiered_answer

    diagnoses: List[Dict[str, Any]] = []
    unavailable: List[str] = []

    try:
        rules = await load_plan_alert_rules(pool, factory_id, domain=domain)
    except Exception as exc:  # noqa: BLE001
        # 整族不可用: 我们不知道任何一条规则的死活.
        logger.warning(
            "[plan-alert] factory=%s 规则表不可用 (整族跳过 auto-resolve): %s",
            factory_id, exc,
        )
        return [], [FAMILY_UNAVAILABLE_PREFIX]

    if not rules:
        # 真的没有规则 != 读不到规则.  空列表 + 空 unavailable 是正确的:
        # 租户删光了规则, 遗留的 OPEN 事件本就该被 auto-resolve 掉.
        return [], []

    for rule in rules:
        rule_code = str(rule.get("rule_code") or "")
        metric_key = f"{METRIC_KEY_PREFIX}{rule_code}"
        try:
            spec = compile_rule_spec(rule)
            result = await tiered_answer(
                str(rule.get("query_text") or ""),
                pool,
                factory_id,
                role,
                precomputed_spec=spec,
                allow_decompose=False,
                include_result_meta=True,
                capture_source=CAPTURE_SOURCE,
            )
            if not isinstance(result, dict) or result.get("kind") != "answer":
                raise RuleUnavailable(
                    "计划执行没有返回可用答案"
                    f"（kind={None if not isinstance(result, dict) else result.get('kind')!r}）"
                )
            receipt = result.get("result_meta")
            if not isinstance(receipt, dict):
                raise RuleUnavailable("计划执行未返回执行回执")
            diagnosis = evaluate_rule(rule, receipt)
        except RuleUnavailable as exc:
            logger.info(
                "[plan-alert] factory=%s rule=%s 本次无法判定: %s",
                factory_id, rule_code, exc.reason,
            )
            unavailable.append(metric_key)
            continue
        except Exception as exc:  # noqa: BLE001
            logger.exception(
                "[plan-alert] factory=%s rule=%s 执行异常 (仅该规则跳过)",
                factory_id, rule_code,
            )
            unavailable.append(metric_key)
            continue

        if diagnosis is not None:
            diagnoses.append(diagnosis)

    logger.info(
        "[plan-alert] factory=%s rules=%d fired=%d unavailable=%d",
        factory_id, len(rules), len(diagnoses), len(unavailable),
    )
    return diagnoses, unavailable


__all__ = [
    "CAPTURE_SOURCE",
    "FAMILY_UNAVAILABLE_PREFIX",
    "METRIC_KEY_PREFIX",
    "SWEEP_ROLE",
    "RuleUnavailable",
    "build_diagnosis",
    "compile_rule_spec",
    "evaluate_rule",
    "extract_metric",
    "load_plan_alert_rules",
    "run_plan_alerts",
]
