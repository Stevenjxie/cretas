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


def _status_of(source: str, fills: Dict[str, Optional[float]]) -> Tuple[str, str]:
    """一类数据的状态 + 一句人话。"""
    columns = _reg.columns_of_source(source)
    known = [v for v in (fills.get(c) for c in columns) if v is not None]
    if not known:
        return STATUS_MISSING, "这段时间没有可以参与计算的行"
    worst = min(known)
    if worst >= _HAVE_THRESHOLD:
        return STATUS_HAVE, ""
    if worst <= 0:
        # 全空 —— 但也许能从别的列导出来。⛔ 那种情况不是「缺数据」，是没接线，
        #    拿它去补 ETL 补不出东西来。
        for key, metric in _reg.METRICS.items():
            spec = getattr(metric, "derive_from", None)
            if spec and set(metric.requires) & set(columns):
                left, right, _op = spec
                return STATUS_NOT_WIRED, f"这一列没填，但可由「{left} − {right}」算出来"
        return STATUS_MISSING, "这一类还没有数据"
    return STATUS_PARTIAL, f"填了 {worst * 100:.0f}%"


def _unlock_count(source: str) -> int:
    """补齐这一类能解锁多少个指标 —— 排「下一个最划算」用。

    ⛔ 不手写优先级。谁解锁得多谁排前面，这是**算**出来的。
    """
    from smartbi.gold.restaurant.fill_offers import unlocked_by_column
    reverse = unlocked_by_column()
    unlocked: set = set()
    for column in _reg.columns_of_source(source):
        unlocked.update(reverse.get(column, ()))
    return len(unlocked)


async def measure(
    conn,
    factory_id: str,
    date_range,
    *,
    coverage_ratio: Optional[float] = None,
    cost_gaps: Sequence[Dict[str, Any]] = (),
    coverage_denominator: Optional[float] = None,
) -> Dict[str, Any]:
    """每一类数据补到哪了 + 下一个最划算的是哪一类。

    ⚠️ 成本卡那一类**用产品自己的 `coverage_ratio`**，⛔ 不用列填充率 ——
       填充率问的是「这一列有没有值」，覆盖率问的是「多少营收算得准」，
       后者才是店长关心的那个，而且它已经算好了。两个数放一起会打架。
    """
    sources = _reg.data_sources()
    fills = await _fill_rates(
        conn, factory_id, date_range, sorted(_reg.COLUMN_SOURCES))

    cost_source = _reg.source_of_column("agg_restaurant_product_cost.food_cost")
    rows: List[Dict[str, Any]] = []
    for source in sources:
        if source == cost_source and coverage_ratio is not None:
            status = (STATUS_HAVE if coverage_ratio >= _HAVE_THRESHOLD
                      else STATUS_PARTIAL if coverage_ratio > 0
                      else STATUS_MISSING)
            detail = f"能算准 {coverage_ratio * 100:.1f}% 的营收"
        else:
            status, detail = _status_of(source, fills)
        rows.append({"source": source, "status": status, "detail": detail,
                     "unlocks": _unlock_count(source)})

    done = [r for r in rows if r["status"] == STATUS_HAVE]
    todo = [r for r in rows if r["status"] != STATUS_HAVE]
    # 下一个最划算 = 解锁指标最多的那一类; 同分时按名字定序(⛔ 不许随机)
    todo.sort(key=lambda r: (-r["unlocks"], r["source"]))
    return {
        "sources": rows,
        "total": len(rows),
        "done": len(done),
        "next": todo[0] if todo else None,
        "cost_source": cost_source,
        "cost_gaps": tuple(cost_gaps),
        "coverage_ratio": coverage_ratio,
        "coverage_denominator": coverage_denominator,
    }


def render(progress: Dict[str, Any]) -> str:
    """一句话，面向店长。⛔ 不是技术读数 —— 不出现列名、表名、百分比以外的术语。"""
    if not progress or not progress.get("total"):
        return ""
    total, done = progress["total"], progress["done"]
    head = f"你的数据补到 {total} 类里的 {done} 类了"
    nxt = progress.get("next")
    if not nxt:
        # ⚠️ 只有在**真的**全齐时才说「都齐了」。`next` 为空而 done < total
        #    是内部不一致(有没齐的类却选不出下一个), 那时宁可不说后半句 ——
        #    「补到 6 类里的 3 类了 —— 都齐了」是自相矛盾, 比不说更糟。
        return f"{head} —— 都齐了。" if done >= total else f"{head}。"

    tail = f"下一个最划算的是【{nxt['source']}】"
    # 成本卡那一类能给出**具体数**: 补几道 → 覆盖率到几成。其余类目前只能说
    # 「补上能多算 N 个指标」—— ⛔ 不编一个覆盖率增量出来。
    if nxt["source"] == progress.get("cost_source"):
        from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps
        offers = offers_for_cost_gaps(
            progress.get("cost_gaps") or (),
            progress.get("coverage_ratio"),
            progress.get("coverage_denominator"))
        if offers:
            after = offers[0]["coverage_after"]
            before = offers[0]["coverage_before"]
            n = len(offers[0]["dishes"])
            tail += (f" —— 补 {n} 道菜的成本卡，"
                     f"能算准的营收就从 {before * 100:.1f}% 到约 {after * 100:.1f}%")
    elif nxt.get("unlocks"):
        tail += f" —— 补上能多算 {nxt['unlocks']} 个指标"
    return f"{head}。{tail}。"
