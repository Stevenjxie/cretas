"""把规格翻译成格子、执行、组织成回答 —— 通用执行器的接线层。

⛔ 接法是**并行路径**，不动现有 20 个 resolver：
   只有当 `_RESOLVERS` 里**没有**手写 resolver 时才走这里。
   现有任何一条能答的问法，行为逐字不变。

⚠️ 叙述是**模板**不是模型：每种聚合形态一套句式，所有指标共用。
   实测现有答案里的「建议：…」两次不同提问逐字相同，说明系统本来就是模板拼的。
   ⛔ 不在这里调模型生成叙述 —— 那会把「数字不经模型」这条原则从后门破掉。
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Any, Dict, Optional, Tuple

from smartbi.gold.restaurant.generic_executor import (
    CellResult,
    UnsupportedCell,
    execute_cell,
    existing_columns,
)
from smartbi.gold.restaurant.metric_registry import (
    AGGREGATIONS,
    DERIVED,
    DIMENSIONS,
    METRICS,
)

logger = logging.getLogger(__name__)

#: 规格里的 requested_metrics → 登记表 metric key。
#: ⛔ 这**不是词表**：左边是规划器输出的**结构化枚举值**（不是用户原话），
#:    右边是登记 key。两套内部标识之间的对照必须显式，否则就是靠名字巧合。
#:    ⚠️ 对不上时**拒绝**，不猜 —— 猜错会答非所问。
_SPEC_METRIC_TO_KEY: Dict[str, str] = {
    "revenue": "revenue",
    "orders": "orders",
    "sales_volume": "sales_qty",
    "recipe_cost": "food_cost",
    "gross_margin": "gross_margin",
    "wastage": "wastage_cost",
}

_SPEC_DIMENSION_TO_KEY: Dict[str, str] = {
    "store": "store",
    "dish": "product",
    "product": "product",
    "channel": "channel",
    "ingredient": "ingredient",
}

#: analysis_action → 聚合形态。缺省是汇总。
_SPEC_ACTION_TO_AGG: Dict[str, str] = {
    "rank": "rank",
    "top": "rank",
    "compare": "compare",
    "summary": "summary",
}


def spec_to_cell(spec) -> Optional[Tuple[str, str, str]]:
    """规格 → (指标, 维度, 聚合)。翻译不出来就返回 None。

    ⛔ 返回 None 是**正常出口**，不是失败：它表示「这个问题不是一个
       指标×维度×聚合的取数问题」（预测、建议、归因都会走到这里）。
       调用方应当继续走原有路径，⛔ 不要把 None 当成「答不出来」。
    """
    metrics = list(getattr(spec, "requested_metrics", ()) or ())
    if not metrics:
        return None
    metric_key = _SPEC_METRIC_TO_KEY.get(metrics[0])
    if metric_key is None or (metric_key not in METRICS and metric_key not in DERIVED):
        return None

    dims = list(getattr(spec, "dimensions", ()) or ())
    dim_key: str = "all"
    for d in dims:
        mapped = _SPEC_DIMENSION_TO_KEY.get(d)
        if mapped is not None and mapped in DIMENSIONS:
            dim_key = mapped
            break

    action = (getattr(spec, "analysis_action", "") or "").lower()
    agg_key = _SPEC_ACTION_TO_AGG.get(action, "summary")
    if agg_key not in AGGREGATIONS:
        agg_key = "summary"
    # 「排名」要有分组对象；规格说排名但没给维度时退回汇总，而不是拒绝 ——
    # 用户问「哪个最高」而没说按什么分，给一个总数比什么都不给强。
    if AGGREGATIONS[agg_key].needs_dimension and dim_key == "all":
        agg_key = "summary"
    return metric_key, dim_key, agg_key


def _fmt(value: Any, unit: str) -> str:
    if value is None:
        return "—"
    try:
        num = float(value)
    except (TypeError, ValueError):
        return str(value)
    if unit == "money":
        return f"¥{num:,.2f}"
    if unit == "pct":
        return f"{num:.1f}%"
    if unit == "qty":
        return f"{num:,.1f}"
    return f"{num:,.0f}"


def render(result: CellResult, window_label: str) -> str:
    """叙述层：每种聚合形态一套句式，所有指标共用。

    ⛔ 缺列时**如实说缺**，绝不拿 0 充数（与执行器那条约束是同一条纪律的下游）。
    """
    label = result.metric_label
    if result.missing_columns:
        cols = "、".join(c.split(".")[-1] for c in result.missing_columns)
        return (f"这项分析需要**{label}**相关数据（{cols}），你的系统还没有接入这些字段。"
                f"接上之后本分析即可运行 —— 本次没有用其他数据替代。")
    if not result.rows:
        return f"{window_label}没有可用的{label}数据，本次没有用相邻区间替代。"

    dim_label = DIMENSIONS[result.dimension_key].label
    unit = result.unit
    key = result.metric_key

    if result.dimension_key == "all":
        value = result.rows[0].get(key)
        return f"{window_label}全部门店{label}合计 **{_fmt(value, unit)}**。"

    items = [(r.get("dim_label") or r.get("dim_key"), r.get(key)) for r in result.rows]
    if result.aggregation_key == "rank":
        head = f"{window_label}{dim_label}{label}排行："
        body = "\n".join(
            f"{i + 1}. {'**' + str(n) + '**' if i == 0 else str(n)} — {_fmt(v, unit)}"
            for i, (n, v) in enumerate(items))
        return head + "\n" + body
    if result.aggregation_key == "compare":
        body = "、".join(f"{n} {_fmt(v, unit)}" for n, v in items)
        return f"{window_label}各{dim_label}{label}对比：{body}。"
    body = "\n".join(f"- {n}: {_fmt(v, unit)}" for n, v in items)
    return f"{window_label}各{dim_label}{label}：\n{body}"


async def try_generic_answer(
    spec, smartbi_pool, factory_id: str, *,
    date_range: Optional[Tuple[date, date]] = None,
    window_label: str = "",
) -> Optional[Dict[str, Any]]:
    """尝试用通用执行器回答。返回 None = 这条不该走这里，调用方继续原路径。

    ⚠️ 任何异常都吞成 None —— 这是**并行路径**，它坏了不该让原有链路跟着挂。
    """
    cell = spec_to_cell(spec)
    if cell is None:
        return None
    metric_key, dim_key, agg_key = cell
    rng = date_range or getattr(spec, "date_range", None)
    if not rng:
        return None
    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id)
            cols = await existing_columns(conn)
            result = await execute_cell(
                conn, factory_id=factory_id, metric_key=metric_key,
                dimension_key=dim_key, aggregation_key=agg_key,
                date_range=rng, available_columns=cols)
    except UnsupportedCell as exc:
        logger.info("[generic-answer] 组合不成立, 交回原路径: %s", exc)
        return None
    except Exception:  # noqa: BLE001 — 并行路径, 坏了不连累主链路
        logger.exception("[generic-answer] 执行失败, 交回原路径")
        return None

    return {
        "code": f"GENERIC_{metric_key.upper()}_{dim_key.upper()}_{agg_key.upper()}",
        "title": f"{result.metric_label}分析",
        "answer_text": render(result, window_label or "所选区间"),
        "rows": result.rows,
        "served": result.ok and bool(result.rows),
        "cell": cell,
    }
