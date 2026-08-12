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
from typing import Any, Dict, List, Optional, Tuple

from smartbi.gold.customer_text import NO_SUBSTITUTION
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
#: ⛔ 只登记**别名**(管线旧写法 → 登记表 key)。登记表已有的键同名直通,
#:    不在这里重复列一遍 —— 列了就是第二份清单, 登记表加一行时两处必然漂移。
#: ⚠️ 没映射的(net_profit/table_turnover/staffing/stocktaking_shortage/
#:    customer_review/production_time/service_speed/process_bottleneck)是
#:    **数据缺口**: 翻译不出来 → 返回 None → 走原路径如实说没有,
#:    ⛔ 绝不硬凑一个相邻指标顶包。
_SPEC_METRIC_ALIASES: Dict[str, str] = {
    "sales_volume": "sales_qty",
    "recipe_cost": "food_cost",
    "wastage": "wastage_cost",
}


def _metric_key(name: str) -> Optional[str]:
    """规格里的指标名 → 登记表的 key。认不出返回 None(⛔ 不猜)。"""
    mapped = _SPEC_METRIC_ALIASES.get(name, name)
    return mapped if (mapped in METRICS or mapped in DERIVED) else None

#: 规划器的维度枚举只有 `_SEMANTIC_DIMENSIONS` 六个。
#: ⚠️ `customer` 没有对应的登记维度 —— 不映射，让它走原路径，
#:    ⛔ 别用「门店」之类近似的顶上去。
#: ⛔ 只登记**别名**。登记表里已有的键(store/product/channel/staff/weekday/…)
#:    走同名直通, 不在这里重复列一遍 —— 列了就是第二份清单, 登记表加一行时
#:    两处必然漂移(而漂移的方向是「新维度悄悄指不到」, 完全不报错)。
#: ⚠️ 别名存在的理由是**历史**: 计划缓存和已晋升的整句路由里存着 dish/time。
_SPEC_DIMENSION_ALIASES: Dict[str, str] = {
    "dish": "product",
    "time": "date",
    # `customer` 没有对应的登记维度 —— 故意不映射, 让它走原路径如实说没有,
    # ⛔ 别用「门店」之类近似的顶上去。
}


def _dimension_key(name: str) -> Optional[str]:
    """规格里的维度名 → 登记表的维度 key。认不出返回 None(⛔ 不猜)。"""
    mapped = _SPEC_DIMENSION_ALIASES.get(name, name)
    return mapped if mapped in DIMENSIONS else None


def spec_to_cell(spec, _metric_override: Optional[str] = None) -> Optional[Tuple[str, str, str]]:
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
    metrics = ([_metric_override] if _metric_override
               else list(getattr(spec, "requested_metrics", ()) or ()))
    if not metrics:
        return None
    metric_key = _metric_key(metrics[0])
    if metric_key is None:
        return None

    dims = list(getattr(spec, "dimensions", ()) or ())
    dim_key: str = "all"
    for d in dims:
        mapped = _dimension_key(d)
        if mapped is not None:
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


def spec_to_cells(spec) -> Tuple[List[Tuple[str, str, str]], List[str]]:
    """规格 → **一组**格子 + 翻译不出来的指标名。

    🔴 为什么要复数: 用户一句话可以问多个指标(「米饭的销量、毛利率和成本」),
       规划器的 `requested_metrics` 本来就是元组。而第一版只取 `metrics[0]`,
       **后面的静默丢弃** —— 电池 [56] 挂的就是这个: 要 3 个只算 1 个,
       答案契约检测到不完整后拒答。系统没有编数糊弄, 但也答不上。

    ⛔ 翻译不出来的指标要**单独返回**, 不能吞掉 —— 用户点了名的东西没答上,
       必须让上层知道并如实说明。吞掉就是「答了一部分却说成全部」。

    ⚠️ 所有指标共用同一个维度和聚合: 它们来自规格的同一组槽位, 不是每个指标
       各有一套。维度上算不了的那个指标会在执行时被拒绝, 而不是在这里硬凑。
    """
    metrics = list(getattr(spec, "requested_metrics", ()) or ())
    if not metrics:
        return [], []
    cells: List[Tuple[str, str, str]] = []
    untranslatable: List[str] = []
    for name in metrics:
        one = spec_to_cell(spec, _metric_override=name)
        if one is None:
            untranslatable.append(name)
        elif one not in cells:
            cells.append(one)
    return cells, untranslatable


def spec_entity_filter(spec) -> Optional[Tuple[str, str]]:
    """用户点名了某道菜/某家店时, 返回 (维度 key, 名字)。

    ⛔ 点了名却不过滤 = 用户问「米饭的销量」得到全部 10 道菜 —— 答非所问,
       而且看起来像答对了(米饭确实在里面)。
    """
    dish = getattr(spec, "dish_slot", None)
    if dish:
        return ("product", str(dish))
    stores = list(getattr(spec, "store_slots", ()) or ())
    if len(stores) == 1:
        return ("store", str(stores[0]))
    return None


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
        # 🔴 2026-08-13 T2 补数据开价接在这里。
        #    改之前这句话把**裸库表列名**怼给店长: `c.split(".")[-1]` 得到的是
        #    `net_amount` / `food_cost` 这种东西。设计卡明写「列的人话名必须在
        #    registry 上」——所以人话名从 `COLUMN_LABELS` 取, 而「补了能算出什么」
        #    由 `requires` 反查算出来, 两样都不在这里手写。
        # ⛔ 开价拿不出来时**退回原措辞**, 不静默丢掉缺口: 「没接入」这件事
        #    本身必须说, 开价只是加值。
        from smartbi.gold.restaurant.fill_offers import (
            build_fill_offers,
            column_label,
        )

        names = [column_label(c) or c.split(".")[-1] for c in result.missing_columns]
        cols = "、".join(names)
        # ⛔ 不说「字段」——「字段」是黑话, 店长不说这个词(见 `INTERNAL_VOCAB`)。
        text = (f"这项分析要用到**{label}**的数据（{cols}），你这边还没接进来。"
                f"接上就能算 —— 这次我没有拿别的数据顶替。")
        offers = build_fill_offers(missing_columns=result.missing_columns)
        if offers:
            text += "\n\n" + "\n".join(f"> {o['text']}" for o in offers)
        return text
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


def render_group(results: List[CellResult], window_label: str,
                 entity: Optional[str] = None) -> str:
    """一组格子合并成一段话。

    ⚠️ 只在**点名了一个实体**（某道菜/某家店）且每个格子只有一行时才拼成一句 ——
       那时几个数说的是同一个对象。否则各自成段：三个排行榜硬拼成一句话，
       会让人分不清哪个数属于哪个榜。
    """
    if entity and all(len(r.rows) == 1 for r in results if r.ok):
        parts = []
        for r in results:
            if not r.ok:
                cols = "、".join(c.split(".")[-1] for c in r.missing_columns)
                parts.append(f"{r.metric_label}（{cols} 还没有接入）")
            else:
                parts.append(
                    f"{r.metric_label} {_fmt(r.rows[0].get(r.metric_key), r.unit)}")
        return f"「{entity}」{window_label}：" + "、".join(parts) + "。"
    return "\n\n".join(render(r, window_label) for r in results)


async def try_generic_answer(
    spec, smartbi_pool, factory_id: str, *,
    date_range: Optional[Tuple[date, date]] = None,
    window_label: str = "",
) -> Optional[Dict[str, Any]]:
    """尝试用通用执行器回答。返回 None = 这条不该走这里，调用方继续原路径。

    🔴 一次算**一组**格子，不是一个 —— 用户问「米饭的销量、毛利率和成本」
       要的是三个数。第一版只取 `metrics[0]`，**后两个静默丢弃**，
       于是答案契约检测到不完整后拒答（电池 [56]）。

    ⛔ 有指标翻译不出来时**如实说出来**，不吞掉 —— 吞掉就是「答了一部分
       却说成全部」，那是最难被发现的一种错。
    ⚠️ 任何异常都吞成 None —— 这是**并行路径**，它坏了不该让原有链路跟着挂。
    """
    # ⚠️ 提前退出 —— 这是**省一次白算**，不是安全机制。别搞错承重点。
    #
    #    2026-08-10 事故: 兜底用「本月全部门店营收合计 ¥6,490,180.61。」
    #    回答了「本月营收比上月低是什么原因」。我第一反应是在这里加守卫，
    #    但那只堵了已知的这一种。**真正的根治是让兜底的答案走一遍契约**
    #    （见 `restaurant_intent_service` 的 `fb_contract`）。
    #
    #    🔑 决定性实验: 把这个守卫**关掉**之后, 归因问题**照样被拒** ——
    #       证明承重的是契约复验, 这里只是避免白算几条 SQL 再扔掉。
    #    ⛔ 所以: 删掉这段最多变慢, 删掉契约复验会**放出错答案**。
    #       判据「一个格子只能回答『是多少』」由契约兑现, 不是由这段兑现。
    action = (getattr(spec, "analysis_action", "") or "").lower()
    if action in ("diagnose", "optimize"):
        logger.info("[generic-answer] 归因/建议类问题, 提前退出(省一次白算): action=%s",
                    action)
        return None

    cells, untranslatable = spec_to_cells(spec)
    if not cells:
        return None
    rng = date_range or getattr(spec, "date_range", None)
    if not rng:
        return None
    ent = spec_entity_filter(spec)
    # ⛔ 点名的实体必须与这组格子的维度一致，否则过滤会打在错的列上
    #    （问「米饭的销量」却按门店分组时，拿菜名去比门店名 = 永远 0 行）。
    ent_value = ent[1] if (ent and all(c[1] == ent[0] for c in cells)) else None

    results: List[CellResult] = []
    try:
        async with smartbi_pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id)
            cols = await existing_columns(conn)
            for metric_key, dim_key, agg_key in cells:
                try:
                    results.append(await execute_cell(
                        conn, factory_id=factory_id, metric_key=metric_key,
                        dimension_key=dim_key, aggregation_key=agg_key,
                        date_range=rng, available_columns=cols,
                        limit_override=spec_limit(spec),
                        entity_filter=ent_value))
                except UnsupportedCell as exc:
                    # ⛔ 单个格子不成立不该让整组失败，但要记下来如实说。
                    logger.info("[generic-answer] 组合不成立: %s", exc)
                    untranslatable.append(metric_key)
    except Exception:  # noqa: BLE001 — 并行路径, 坏了不连累主链路
        logger.exception("[generic-answer] 执行失败, 交回原路径")
        return None

    served = [r for r in results if r.ok and r.rows]
    if not served:
        return None
    text = render_group(results, window_label or "所选区间", ent_value)
    if untranslatable:
        # ⛔ 如实披露：用户要了、但这里给不了的那些。
        # ⚠️ 说人话，不能甩内部键名（用户看到 "net_profit" 等于没说）。
        #    中文名取自规划器自己的词表首词，⛔ 不另建一张显示名表 ——
        #    另建就是第 N 张手写表，且加指标时两处必然漂移。
        #    ⚠️ 延迟 import：`restaurant_intent` → `restaurant_ops_router` →
        #       本模块，模块级 import 会成环。
        try:
            from smartbi.gold.restaurant.restaurant_intent import (
                _REQUEST_METRIC_RULES as _RULES)
            zh = {k: (words[0] if words else k) for k, words in _RULES}
        except Exception:  # noqa: BLE001 — 拿不到就退回键名, 不因措辞挂掉回答
            zh = {}
        names = "、".join(zh.get(m, m) for m in untranslatable)
        text += (f"\n\n这几项这次没给出：{names}"
                 f" —— 系统没有这项数据，{NO_SUBSTITUTION}。")
    return {
        "code": "GENERIC_" + "+".join(f"{m.upper()}_{d.upper()}_{a.upper()}"
                                      for m, d, a in cells),
        "title": "、".join(r.metric_label for r in results) + "分析",
        "answer_text": text,
        "rows": [r.rows for r in results],
        "served": bool(served),
        "cell": cells[0],
        "cells": cells,
    }
