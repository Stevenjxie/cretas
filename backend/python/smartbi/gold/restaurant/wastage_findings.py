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


async def detect_share_spike(
    pool, factory_id: str, *, cur_days: int = 7, base_days: int = 21,
) -> Dict[str, Any]:
    """某食材的损耗份额相对自身基线放大。

    share(i, w)      = 食材 i 在窗口 w 的 wastage_cost / 窗口 w 全店 wastage_cost
    amplification(i) = share(i, cur) / share(i, base)
    触发 = amplification >= 1.4 AND share(i, cur) >= 5%

    分母用**全店总损耗**: 全店一起放大时分子分母对消, 所以 2026-07-30 那种
    「整个租户的损耗数据跳了 24 倍」不会被误读成 25 条食材异常。

    但份额归一化对消不了**食材名单变了** (同日 13 种 -> 25 种), 所以另有 Jaccard 闸。
    """
    rule = "share_spike"
    total_days = cur_days + base_days
    async with tenant_conn(pool, factory_id) as conn:
        cur_rows = await conn.fetch(
            """
            SELECT a.dim_value_id, i.name, i.unit,
                   SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON i.ingredient_id = a.dim_value_id
             WHERE a.factory_id = $1
               AND a.kpi_kind = 'wastage_cost'
               AND a.date > CURRENT_DATE - $2::int
               AND a.date <= CURRENT_DATE
             GROUP BY 1, 2, 3
            """,
            factory_id, cur_days,
        )
        base_rows = await conn.fetch(
            """
            SELECT a.dim_value_id, i.name, i.unit,
                   SUM(a.value_num)::float AS cost
              FROM agg_restaurant_daily_ops a
              JOIN dim_ingredient i ON i.ingredient_id = a.dim_value_id
             WHERE a.factory_id = $1
               AND a.kpi_kind = 'wastage_cost'
               AND a.date > CURRENT_DATE - $2::int
               AND a.date <= CURRENT_DATE - $3::int
             GROUP BY 1, 2, 3
            """,
            factory_id, total_days, cur_days,
        )
        days_row = await conn.fetchrow(
            """
            SELECT COUNT(DISTINCT date)::int AS days
              FROM agg_restaurant_daily_ops
             WHERE factory_id = $1
               AND kpi_kind = 'wastage_cost'
               AND date > CURRENT_DATE - $2::int
               AND date <= CURRENT_DATE - $3::int
            """,
            factory_id, total_days, cur_days,
        )

    # ── 闸 A: 基线历史长度 ──────────────────────────────────────────
    observed_base_days = int((days_row or {}).get("days") or 0)
    if observed_base_days < _SHARE_SPIKE_MIN_BASE_DAYS:
        return _skip(
            rule,
            f"基线历史不足: 前 {base_days} 天窗口内仅 {observed_base_days} 天有数据"
            f"(需 >= {_SHARE_SPIKE_MIN_BASE_DAYS} 天)",
        )

    cur_ids = {r["dim_value_id"] for r in cur_rows}
    base_ids = {r["dim_value_id"] for r in base_rows}
    union = cur_ids | base_ids
    if not union:
        return _ok(rule, [])

    # ── 闸 B: 食材名单同质 ──────────────────────────────────────────
    # 闸 A 挡不住 2026-07-30 那个 case (base 窗有 21 天数据会通过), 必须有这道。
    jaccard = len(cur_ids & base_ids) / len(union)
    if jaccard < _SHARE_SPIKE_MIN_JACCARD:
        return _skip(
            rule,
            f"两期食材名单不可比: 近 {cur_days} 天 {len(cur_ids)} 种 / "
            f"基线 {len(base_ids)} 种 (重合度 {jaccard:.0%}, 需 >= "
            f"{_SHARE_SPIKE_MIN_JACCARD:.0%})",
        )

    cur_total = sum(float(r["cost"] or 0.0) for r in cur_rows)
    base_total = sum(float(r["cost"] or 0.0) for r in base_rows)
    if cur_total <= 0 or base_total <= 0:
        return _ok(rule, [])

    base_by_id = {r["dim_value_id"]: float(r["cost"] or 0.0) for r in base_rows}

    findings: List[Dict[str, Any]] = []
    for r in cur_rows:
        base_cost = base_by_id.get(r["dim_value_id"], 0.0)
        if base_cost <= 0:
            # 只在 cur 出现的食材没有基线。不参与计算 —— 除零得不到
            # 「涨了无穷倍」这种结论, 它只是没有基线。名单变化已由闸 B 兜住。
            continue
        cur_cost = float(r["cost"] or 0.0)
        share_cur = cur_cost / cur_total
        share_base = base_cost / base_total
        amplification = share_cur / share_base
        if amplification < _SHARE_SPIKE_MIN_AMPLIFICATION:
            continue
        if share_cur < _SHARE_SPIKE_MIN_CUR_SHARE:
            continue
        findings.append({
            "code": "WASTAGE_SHARE_SPIKE",
            "subject_id": str(r["dim_value_id"]),
            "subject_name": r["name"],
            "severity": (
                "WARNING" if amplification >= _SHARE_SPIKE_WARNING_AMPLIFICATION else "INFO"
            ),
            "actionability": _SHARE_SPIKE_ACTIONABILITY,
            "facts": {
                "costCur": round(cur_cost, 2),
                "shareCur": round(share_cur * 100, 1),
                "shareBase": round(share_base * 100, 1),
                "amplification": round(amplification, 2),
                "windowDays": cur_days,
                "unit": r["unit"] or "",
            },
        })
    findings.sort(key=lambda f: f["facts"]["amplification"], reverse=True)
    return _ok(rule, findings)
