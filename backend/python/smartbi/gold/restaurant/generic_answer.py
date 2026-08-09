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
#: ⛔ 左边**必须是 `_REQUEST_METRIC_RULES` 里真实存在的枚举值**。
#:    没映射的(net_profit / table_turnover / staffing / stocktaking_shortage /
#:    customer_review / production_time / service_speed / process_bottleneck)
#:    是**数据缺口**，翻译不出来 → 返回 None → 走原路径如实说没有，
#:    ⛔ 绝不硬凑一个相邻指标顶包。
_SPEC_METRIC_TO_KEY: Dict[str, str] = {
    "revenue": "revenue",
    "orders": "orders",
    "sales_volume": "sales_qty",
    "recipe_cost": "food_cost",
    "gross_margin": "gross_margin",
    "wastage": "wastage_cost",
    "return_rate": "return_rate",
}

#: 规划器的维度枚举只有 `_SEMANTIC_DIMENSIONS` 六个。
#: ⚠️ `customer` 没有对应的登记维度 —— 不映射，让它走原路径，
#:    ⛔ 别用「门店」之类近似的顶上去。
_SPEC_DIMENSION_TO_KEY: Dict[str, str] = {
    "store": "store",
    "dish": "product",
    "product": "product",
    "channel": "channel",
    "ingredient": "ingredient",
    "time": "date",
}


def spec_to_cell(spec) -> Optional[Tuple[str, str, str]]:
    """规格 → (指标, 维度, 聚合)。翻译不出来就返回 None。

    ⛔ 返回 None 是**正常出口**，不是失败：它表示「这个问题不是一个
       指标×维度×聚合的取数问题」（预测、建议、归因都会走到这里）。
       调用方应当继续走原有路径，⛔ 不要把 None 当成「答不出来」。

    🔴 2026-08-09 修正：第一版按 `analysis_action` 里的 "rank"/"top" 判排名 ——
       而规格里 `analysis_action` **只有 lookup|compare|diagnose|optimize 四个值**，
       "rank"/"top" 规划器一次都不会产出。于是所有排名问句都静默退化成汇总：
       问「哪家店最高」得到「全部门店合计 ¥2,872 万」，答的不是那个问题。
       判据 = **写枚举对照表前，先去看产出方**真正会给哪些值，
       ⛔ 别按自己觉得合理的词去映射 —— 对不上时它不会报错，只会静默走别的分支。
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

    # 🔑 规划器**直接说了**要哪种形态时，用它的。
    #    这是「打通 96% 死格子」的那一步：占比 / 集中度 / 两端 / 高于平均
    #    这四种形态，靠 analysis_action + ranking_direction **推不出来**，
    #    只能由规划器直接指定。⛔ 别在这里给它们编推断规则 —— 那是猜。
    stated = (getattr(spec, "aggregation", None) or "").lower()
    if stated in AGGREGATIONS:
        agg_key = stated
        if AGGREGATIONS[agg_key].needs_dimension and dim_key == "all":
            agg_key = "summary"
        return metric_key, dim_key, agg_key

    # 规划器没表态 → 退回旧规则推断。⚠️ 这一段与加 `aggregation` 槽之前**逐字同义**，
    #    所以这个槽是纯增量：模型不填它，现有行为一点不变。
    # 排名方向是**独立的槽**，不在 analysis_action 里。
    direction = (getattr(spec, "ranking_direction", None) or "").lower()
    action = (getattr(spec, "analysis_action", "") or "").lower()
    if direction == "best":
        agg_key = "rank"
    elif direction == "worst":
        agg_key = "bottom"
    elif action == "compare":
        agg_key = "compare"
    elif dim_key == "date":
        # 按时间分组而没说排名 = 走势。⛔ 用排名去答趋势会把时间序列打乱。
        agg_key = "trend"
    else:
        agg_key = "summary"
    if agg_key not in AGGREGATIONS:
        agg_key = "summary"
    # 需要分组对象的形态而规格没给维度时退回汇总，而不是拒绝 ——
    # 用户问「哪个最高」而没说按什么分，给一个总数比什么都不给强。
    if AGGREGATIONS[agg_key].needs_dimension and dim_key == "all":
        agg_key = "summary"
    return metric_key, dim_key, agg_key


def spec_limit(spec) -> Optional[int]:
    """用户说了要几条就给几条。

    ⛔ 不用聚合登记里的默认 5：规格里 `ranking_limit` 是**用户说的**，
       问「前 10」却回 5 条是答非所问，而且它长得像答对了。
    """
    n = getattr(spec, "ranking_limit", None)
    if isinstance(n, int) and 0 < n <= 100:
        return n
    return None


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

    # ⛔ 分组值为 NULL 时显示「未填写」，不是 "None" —— 前者是句实话
    #    (这家店没录台位号)，后者看着像个叫 None 的门店。
    #    ⚠️ 用 `is None` 判而不是 `or`：0 和空字符串是**合法的分组值**，
    #       用 `or` 会把「台位 0」也说成未填写。
    def _name(r: Dict[str, Any]) -> str:
        n = r.get("dim_label")
        if n is None:
            n = r.get("dim_key")
        return "未填写" if n is None else str(n)

    items = [(_name(r), r.get(key), r) for r in result.rows]
    agg = result.aggregation_key

    if agg in ("rank", "bottom"):
        word = "排行" if agg == "rank" else "倒数排行"
        body = "\n".join(
            f"{i + 1}. {'**' + n + '**' if i == 0 else n} — {_fmt(v, unit)}"
            for i, (n, v, _) in enumerate(items))
        return f"{window_label}{dim_label}{label}{word}：\n{body}"

    if agg == "trend":
        # 按维度自身顺序 —— 不加重任何一项，加重会把趋势读成排行榜。
        body = "\n".join(f"- {n}: {_fmt(v, unit)}" for n, v, _ in items)
        return f"{window_label}{label}按{dim_label}的走势：\n{body}"

    if agg == "share":
        body = "\n".join(
            f"- {n}: {_fmt(v, unit)}（{_fmt(r.get('share'), 'pct')}）"
            for n, v, r in items)
        return f"{window_label}各{dim_label}{label}及占比：\n{body}"

    if agg == "concentration":
        last = items[-1][2] if items else {}
        body = "\n".join(
            f"{i + 1}. {n} — {_fmt(v, unit)}（占 {_fmt(r.get('share'), 'pct')}，"
            f"累计 {_fmt(r.get('cum_share'), 'pct')}）"
            for i, (n, v, r) in enumerate(items))
        return (f"{window_label}{len(items)} 个{dim_label}贡献了 "
                f"{_fmt(last.get('cum_share'), 'pct')} 的{label}：\n{body}")

    if agg == "extremes":
        if len(items) < 2:
            return f"{window_label}只有一个{dim_label}有{label}数据，无法给出两端。"
        (hn, hv, _), (ln, lv, _) = items[0], items[-1]
        return (f"{window_label}{label}最高的{dim_label}是 **{hn}**（{_fmt(hv, unit)}），"
                f"最低的是 {ln}（{_fmt(lv, unit)}）。")

    if agg == "above_avg":
        if not items:
            return f"{window_label}没有{dim_label}的{label}高于平均线。"
        thr = items[0][2].get("_threshold")
        body = "\n".join(f"- {n}: {_fmt(v, unit)}" for n, v, _ in items)
        return (f"{window_label}{label}高于平均线（{_fmt(thr, unit)}）的"
                f"{dim_label}有 {len(items)} 个：\n{body}")

    if agg == "compare":
        body = "、".join(f"{n} {_fmt(v, unit)}" for n, v, _ in items)
        return f"{window_label}各{dim_label}{label}对比：{body}。"

    body = "\n".join(f"- {n}: {_fmt(v, unit)}" for n, v, _ in items)
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
                date_range=rng, available_columns=cols,
                limit_override=spec_limit(spec))
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
