"""餐饮损耗发现规则 (domain="restaurant")。

⛔ 本模块只产出结构化数字, 不产出话术 —— 渲染由 Java 侧 FindingTextRenderer 负责。
⛔ 本模块不重定义 ingredient_waste_rate。那个指标由 health_check_metrics.py 的
   DiagnosticsEngine 拥有 (损耗成本 / (领料成本 + 损耗成本), 对标行业 benchmark)。
   这里的两条规则是**不同的指标**: 一条看份额相对自身基线的漂移, 一条看类型集中度。

数据源: gold `agg_restaurant_daily_ops` (与 resolve_wastage_top 同源, 口径一致)。
  kpi_kind='wastage_cost'          dim_value_id  -> dim_ingredient.ingredient_id
  kpi_kind='wastage_cost_by_type'  dim_value_str -> 损耗类型 (中文自由文本)
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List

from smartbi.gold.queries import tenant_conn

logger = logging.getLogger(__name__)


# ⛔ 唯一定义处。不得在 Java 侧或 web-admin 再写一份。
#    取值是**中文自由文本** (实测: 加工损耗 / 客诉退菜 / 变质)。建表注释里的
#    EXPIRED/DAMAGED/SPOILED/PROCESSING/OTHER 是陈旧的, 库里没有这些值。
ACTIONABLE_WASTAGE_TYPES: Dict[str, bool] = {
    "变质": True,       # 可行动: 备货量 / FIFO / 冷链
    "客诉退菜": True,   # 可行动: 出品质量 / 菜品调整
    "加工损耗": False,  # 结构性: 切配边角料是常态, 店长知道也动不了
}

#: 未知新类型的默认归属。宁多报不漏报 —— 漏报一个真问题比多报一条噪音贵。
_UNKNOWN_TYPE_ACTIONABLE = True

_TYPE_CONCENTRATION_MIN_SHARE = 0.30
_TYPE_CONCENTRATION_WARNING_SHARE = 0.50
_TYPE_CONCENTRATION_ACTIONABILITY = 70

_SHARE_SPIKE_MIN_AMPLIFICATION = 1.4
_SHARE_SPIKE_MIN_CUR_SHARE = 0.05
_SHARE_SPIKE_WARNING_AMPLIFICATION = 2.0
_SHARE_SPIKE_ACTIONABILITY = 60
_SHARE_SPIKE_MIN_BASE_DAYS = 14
_SHARE_SPIKE_MIN_JACCARD = 0.8


def _skip(rule: str, reason: str) -> Dict[str, Any]:
    """「数据没采集到」—— 与「真的没有」(applicable=True, findings=[]) 严格区分。"""
    return {"rule": rule, "applicable": False, "skip_reason": reason, "findings": []}


def _ok(rule: str, findings: List[Dict[str, Any]]) -> Dict[str, Any]:
    return {"rule": rule, "applicable": True, "skip_reason": None, "findings": findings}


async def detect_type_concentration(
    pool, factory_id: str, *, window_days: int = 7,
) -> Dict[str, Any]:
    """单一损耗类型占比过高。单窗口, 无基线 —— 任何租户第一天可用。

    触发 = 类型属于可行动类型 AND 占比 >= 30%。
    刻意不设绝对金额闸: 占比对不同规模门店自动适配。
    """
    rule = "type_concentration"
    async with tenant_conn(pool, factory_id) as conn:
        rows = await conn.fetch(
            """
            SELECT dim_value_str AS wastage_type, SUM(value_num)::float AS cost
              FROM agg_restaurant_daily_ops
             WHERE factory_id = $1
               AND kpi_kind = 'wastage_cost_by_type'
               AND date > CURRENT_DATE - $2::int
               AND date <= CURRENT_DATE
             GROUP BY 1
            """,
            factory_id, window_days,
        )
        total = sum(float(r["cost"] or 0.0) for r in rows)

        if total <= 0:
            # 分不清「这家店本期真没损耗」和「per-type KPI 没物化」。
            # totals 表是直接从 Silver 算的, 拿它当阳性对照。
            totals_row = await conn.fetchrow(
                """
                SELECT COALESCE(SUM(wastage_cost_total), 0)::float AS total_cost
                  FROM agg_restaurant_daily_totals
                 WHERE factory_id = $1
                   AND date > CURRENT_DATE - $2::int
                   AND date <= CURRENT_DATE
                """,
                factory_id, window_days,
            )
            silver_total = float((totals_row or {}).get("total_cost") or 0.0)
            if silver_total > 0:
                return _skip(
                    rule,
                    f"损耗类型 KPI 未物化: totals 表本期有 ¥{silver_total:,.2f} "
                    f"但 wastage_cost_by_type 全为空",
                )
            return _ok(rule, [])

    findings: List[Dict[str, Any]] = []
    for r in rows:
        wastage_type = r["wastage_type"]
        cost = float(r["cost"] or 0.0)
        share = cost / total
        actionable = ACTIONABLE_WASTAGE_TYPES.get(wastage_type, _UNKNOWN_TYPE_ACTIONABLE)
        if not actionable or share < _TYPE_CONCENTRATION_MIN_SHARE:
            continue
        findings.append({
            "code": "WASTAGE_TYPE_CONCENTRATION",
            "subject_id": wastage_type,
            "subject_name": wastage_type,
            "severity": "WARNING" if share >= _TYPE_CONCENTRATION_WARNING_SHARE else "INFO",
            "actionability": _TYPE_CONCENTRATION_ACTIONABILITY,
            "facts": {
                "cost": round(cost, 2),
                "share": round(share * 100, 1),
                "windowDays": window_days,
                "totalCost": round(total, 2),
            },
        })
    findings.sort(key=lambda f: f["facts"]["cost"], reverse=True)
    return _ok(rule, findings)
