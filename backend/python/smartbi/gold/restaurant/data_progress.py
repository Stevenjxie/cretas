"""进度感 —— 「你的数据补到 N 类里的 M 类了」。

## 为什么要它（owner 2026-08-14，路线图第 4 项）

T2 现在的形态是：给数 → 说清是估的 → 「先补这 3 道，40.2% → 47.7%」→ **完**。
他补了，然后**没有下文**。⇒ 没有这一环，T2 是一次性建议；有了它，回路才自我推进。

## 它与覆盖率不是一回事

| | 是什么 | 给谁看 |
|---|---|---|
| 覆盖率 40.2% | 技术读数：有多少营收算得准 | 判断这个数能不能用 |
| 「6 类补了 3 类」 | 用户视角的**推进感**：我做到哪一步了 | 知道自己在往前走 |

⛔ 两个都要有，别互相替代。

## 类别从哪来

`metric_registry.COLUMN_SOURCES`（列 → 数据来源），类别由列**推**出来 ——
有几个不同的值就是几类。⛔ 本模块里没有「一共有哪几类」的清单，
也⛔不按表名硬猜（同一张 `fact_pos_transaction` 上，净额是 POS 自带的，
而税额/折扣要另外接）。

⚠️ 那张列标注**是靠人标的**（「这一列的数据从哪来」是业务事实，推不出来），
设计卡上写明了，`assert_registry_self_consistent` 守它不缺不过期。
"""
from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Sequence, Tuple

from smartbi.gold.restaurant import metric_registry as _reg

logger = logging.getLogger(__name__)

#: 状态四态。⛔ 不合并「没接线」和「没有」——
#: 前者补 ETL 补不出东西来（数据其实在，只是这一列没填），后者才是真缺。
STATUS_HAVE = "有"
STATUS_PARTIAL = "部分"
STATUS_NOT_WIRED = "有但没接线"
STATUS_MISSING = "无"

#: 认为「这一类齐了」的门槛。⚠️ 不用 100%：真实数据里总有零星空值，
#: 卡死在 100% 会让永远没有一类是「齐的」，那这个进度条就永远不动。
_HAVE_THRESHOLD = 0.99

#: 每类各用哪条事实链去量「填了多少」。⛔ 从 `GRAINS` 取，不重抄。
_GRAIN_OF_TABLE = {
    "fact_pos_transaction": "txn",
    "fact_pos_item": "item",
    "fact_restaurant_wastage": "wastage",
}


async def _fill_rates(conn, factory_id: str, date_range,
                      columns: Sequence[str]) -> Dict[str, Optional[float]]:
    """这些列在**参与计算的行**里填了多少。

    ⛔ 不是「这张表有多少行」—— 0 行有三种含义，一律当缺口会得出有害结论。
    ⚠️ 按表分组，一张表一条查询（不是一列一条）——
       普查那版一列一条，22 个指标跑了几十次往返。
    """
    by_table: Dict[str, List[str]] = {}
    for column in columns:
        table = column.split(".", 1)[0]
        if table in _GRAIN_OF_TABLE:
            by_table.setdefault(table, []).append(column)

    out: Dict[str, Optional[float]] = {c: None for c in columns}
    for table, cols in by_table.items():
        frm, join, alias = _reg.GRAINS[_GRAIN_OF_TABLE[table]]
        selects = ", ".join(
            f"count({_reg.column_ref(c)}) AS c{i}" for i, c in enumerate(cols))
        sql = (f"SELECT count(*) AS n, {selects}\n"
               f"  FROM {frm}\n  {join}\n"
               f" WHERE {alias}.factory_id = $1"
               f"   AND {alias}.date >= $2 AND {alias}.date <= $3")
        try:
            row = await conn.fetchrow(sql, factory_id, date_range[0], date_range[1])
        except Exception:  # noqa: BLE001 —— ⛔ 不吞成「这一类没有」
            logger.warning("[data-progress] 填充率取数失败 table=%s", table,
                           exc_info=True)
            continue
        total = int((row or {}).get("n") or 0)
        for i, c in enumerate(cols):
            out[c] = (int(row[f"c{i}"] or 0) / total) if total else None
    return out


def _status_binary(source: str, fills: Dict[str, Optional[float]]) -> Tuple[str, str]:
    """一次性接入的类：只问**接了没有**，⛔ 不算填充率。

    🔴 owner 2026-08-14: 对一次性接入的类算填充率**本身就是错的仪器** ——
       POS 那 13% 不是「没录全」，是那些单本来就没退菜、没打折。
       把**合法的空**当成缺失，本仓这是第四次(0 行三种含义 / 行数不是找缺口的
       仪器 / 普查第四类 / 这次)。
    ⇒ 判据: 这一类**有没有任何数据**。有 = 接了; 全空 = 没接。
    """
    columns = _reg.columns_of_source(source)
    known = [v for v in (fills.get(c) for c in columns) if v is not None]
    if not known:
        return STATUS_MISSING, "这段时间没有可以参与计算的行"
    if max(known) > 0:
        return STATUS_HAVE, ""
    # 全空 —— 但也许能从别的列导出来。⛔ 那不是「缺数据」，是没接线，
    #    拿它去补 ETL 补不出东西来。
    for metric in _reg.METRICS.values():
        spec = getattr(metric, "derive_from", None)
        if spec and set(metric.requires) & set(columns):
            left, right, _op = spec
            return STATUS_NOT_WIRED, f"这一列没填，但可由「{left} − {right}」算出来"
    return STATUS_MISSING, "这一类还没有数据"


def _coverage_lift(progress_inputs) -> float:
    """补齐这一类, **能算准的营收占比**能提升多少。这是排序键。

    🔴 owner 2026-08-14: 排序键换成**边际**, 不是存量。
       「解锁多少指标」是存量(这一类总共支撑什么), 要的是边际(补了能多算什么)。
       实测那一版按存量排, 「下一个最划算」永远是 POS 流水(解锁 16 个) ——
       而 POS 早就接了, 那条建议店长照着做无从下手。
    ⚠️ 今天只有成本卡算得出边际(它是唯一的连续类)。其余类补了**不改变**
       能算准的营收占比 → 边际 0 → 永远不会被推荐。**这是对的, 不是退化。**
    """
    cost_gaps, coverage_ratio, denom = progress_inputs
    if not cost_gaps or coverage_ratio is None or not denom:
        return 0.0
    from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps
    offers = offers_for_cost_gaps(cost_gaps, coverage_ratio, denom)
    if not offers:
        return 0.0
    return float(offers[0]["coverage_after"]) - float(offers[0]["coverage_before"])


async def measure(
    conn,
    factory_id: str,
    date_range,
    *,
    coverage_ratio: Optional[float] = None,
    cost_gaps: Sequence[Dict[str, Any]] = (),
    coverage_denominator: Optional[float] = None,
) -> Dict[str, Any]:
    """每一类补到哪了 + 下一个最划算的是哪一类(没有清晰赢家时是 None)。

    ⚠️ 成本卡那一类**用产品自己的 `coverage_ratio`**, ⛔ 不用列填充率 ——
       填充率问「这一列有没有值」, 覆盖率问「多少营收算得准」, 后者才是
       店长关心的那个, 而且它已经算好了。两个数放一起会打架。
    """
    sources = _reg.data_sources()
    fills = await _fill_rates(
        conn, factory_id, date_range, sorted(_reg.COLUMN_SOURCES))
    cost_source = _reg.source_of_column("agg_restaurant_product_cost.food_cost")
    inputs = (tuple(cost_gaps), coverage_ratio, coverage_denominator)

    rows: List[Dict[str, Any]] = []
    for source in sources:
        intake = _reg.intake_of_source(source)
        if intake == _reg.INTAKE_PER_ITEM and source == cost_source                 and coverage_ratio is not None:
            status = (STATUS_HAVE if coverage_ratio >= _HAVE_THRESHOLD
                      else STATUS_PARTIAL if coverage_ratio > 0
                      else STATUS_MISSING)
            detail = f"能算准 {coverage_ratio * 100:.1f}% 的营收"
            lift = _coverage_lift(inputs)
        else:
            # 一次性接入 → 只问接了没有, ⛔ 不算填充率
            status, detail = _status_binary(source, fills)
            # 补它**不改变**能算准的营收占比 → 边际 0 → 不参与排序
            lift = 0.0
        rows.append({"source": source, "intake": intake, "status": status,
                     "detail": detail, "coverage_lift": lift})

    done = [r for r in rows if r["status"] == STATUS_HAVE]
    missing = [r for r in rows if r["status"] == STATUS_MISSING]
    # 🔴 「有」的一次性接入类**不参与排序** —— 它已经接了, 没什么可补的。
    #    ⛔ 边际为 0 的也不参与: 没有清晰赢家时**那句建议就不出**,
    #       只出进度视图(owner 2026-08-14)。
    todo = [r for r in rows
            if r["status"] != STATUS_HAVE and r["coverage_lift"] > 0]
    todo.sort(key=lambda r: (-r["coverage_lift"], r["source"]))
    return {
        "sources": rows,
        "total": len(rows),
        "done": len(done),
        "missing": [r["source"] for r in missing],
        "next": todo[0] if todo else None,
        "cost_source": cost_source,
        "cost_gaps": tuple(cost_gaps),
        "coverage_ratio": coverage_ratio,
        "coverage_denominator": coverage_denominator,
    }


def render(progress: Dict[str, Any]) -> str:
    """面向店长的进度视图。**视图本身才是价值, 建议只是附带。**

    ⛔ 不出现列名/表名。⚠️ 没有清晰边际赢家时**不出建议**, 只出视图。
    """
    if not progress or not progress.get("total"):
        return ""
    total, done = progress["total"], progress["done"]
    parts = [f"你的数据补到 {total} 类里的 {done} 类了"]

    # 🔑 即使暂时不建议他去补, 这也是店长该知道的一句话。
    missing = progress.get("missing") or []
    if missing:
        parts.append(f"还有 {len(missing)} 类完全没有数据：{('、'.join(missing))}")

    nxt = progress.get("next")
    if nxt:
        from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps
        offers = offers_for_cost_gaps(
            progress.get("cost_gaps") or (),
            progress.get("coverage_ratio"),
            progress.get("coverage_denominator"))
        tail = f"下一个最划算的是【{nxt['source']}】"
        if offers:
            o = offers[0]
            tail += (f"——补 {len(o['dishes'])} 道菜的成本卡，能算准的营收就从 "
                     f"{o['coverage_before'] * 100:.1f}% 到约 "
                     f"{o['coverage_after'] * 100:.1f}%")
        parts.append(tail)
    elif done >= total:
        parts.append("都齐了")
    return "。".join(parts) + "。"
