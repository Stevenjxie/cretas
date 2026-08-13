"""指标覆盖率普查 —— **纯只读**。owner 2026-08-13 第八件。

回答的问题: 「不只是毛利, 其他指标是不是也有同类问题?」

四分类:
  能算准   —— 产品自己判定 MEASURED, 且算出了值
  是估的   —— 产品自己判定 ESTIMATED, 附**产品自己算的**覆盖率
  算不出   —— 缺列 / 值为空, **必须写明是哪一列**
  没接线   —— 登记了但没有任何请求措辞能点到它。⚠️ 这不是数据问题, 是接线问题,
             混进「算不出」就会被当成缺数据去补 ETL, 补完还是点不到。

## 判据(逐条对照 owner 的五条)

1. 分类依据从 `Metric.requires` **反查**, ⛔ 无手写清单。
   → `_effective_requires()` 是产品自己的函数; 指标全集来自 `METRICS|DERIVED`。
     新登记一个指标会自动出现在本表里, 不会悄悄落在表外。
2. 覆盖率**复用产品现在用的那一份**, ⛔ 不另写算法。
   → 直接读 `CellResult.coverage_ratio`(由 `_coverage_ratio_of` 产出),
     本文件一行覆盖率算法都没有。探针与被测对象同口径。
3. ⛔ 不用「这张表有多少行」当判据。
   → 量的是「**参与计算的行**里这一列有多少是空的」, 按指标自己的粒度取行。
4. 两个租户各跑各的, 逐条贴全。输出**落文件**, ⛔ 禁管道截断。
5. 失败**计数并逐条贴**, ⛔ 不 continue 跳过 —— 跑不动的那几条信息量最大。

⛔ 红线: 不写库、不改 registry、不激活租户、不碰任何成本卡。
"""
from __future__ import annotations

import asyncio
import datetime
import json
import os
import sys
import traceback

from smartbi.scripts._probe_bootstrap import bootstrap_probe

TENANTS = [t for t in os.environ.get(
    "CENSUS_TENANTS", "RES_3101_009,DEMO_REST").split(",") if t]
DAY = datetime.date.fromisoformat(os.environ.get("CENSUS_DAY", "2026-08-12"))
OUT = os.environ.get("CENSUS_OUT", "census.json")


def _wiring_index():
    """每个指标**怎么被点到** —— 反查, ⛔ 不手写。

    🔴 第一版把「没有关键词规则」直接标成「没接线」, 报出来 14 条 ——
       **那个数不是我想知道的那个数**。实测 `_SEMANTIC_METRICS` 含全部 22 个
       登记 key, 也就是规划器**能**点到每一个。真正的「没接线」是三条路都够不着:
         · 关键词规则直达 (`_REQUEST_METRIC_RULES`)
         · 规划器按 key 命名 (`_SEMANTIC_METRICS`)
         · 固定格子清单 (`DAILY_CLOSE_CELLS`)
       两者的区别不是措辞: 前者会让人去补 ETL(数据问题), 后者才是接线问题。
    """
    from smartbi.gold.restaurant.restaurant_intent import (
        _REQUEST_METRIC_RULES, _SEMANTIC_METRICS)
    from smartbi.gold.restaurant.generic_answer import _SPEC_METRIC_ALIASES
    from smartbi.gold.restaurant.daily_close import DAILY_CLOSE_CELLS

    idx: dict = {}

    def _slot(key):
        return idx.setdefault(key, {"tokens": [], "fixed_cells": False,
                                    "planner_nameable": False})

    for spec_name, tokens in _REQUEST_METRIC_RULES:
        key = _SPEC_METRIC_ALIASES.get(spec_name, spec_name)
        _slot(key)["tokens"].extend(tokens)
    for metric_key, _dim, _agg in DAILY_CLOSE_CELLS:
        _slot(metric_key)["fixed_cells"] = True
    for key in _SEMANTIC_METRICS:
        _slot(key)["planner_nameable"] = True
    return idx


async def _null_rate(conn, factory_id, column, grain, bridge):
    """参与计算的行里, 这一列有多少是空的。

    ⛔ 不是「这张表有多少行」—— 0 行有三种含义(合法的空/没接上/真缺失),
       一律当缺口会得出有害结论(2026-08-12 实测三条里两条不是缺陷)。
    ⚠️ 行集按**指标自己的粒度**取, 与它算数时用的是同一批行。
    """
    from smartbi.gold.restaurant.metric_registry import GRAINS
    frm, join, alias = GRAINS[grain]
    table, col = column.split(".", 1)
    sql = (f"SELECT count(*) AS rows, "
           f"       count(*) FILTER (WHERE {_col_ref(table, col, grain)} IS NULL) AS nulls\n"
           f"  FROM {frm}\n  {join}\n"
           f" WHERE {alias}.factory_id = $1 AND {alias}.date >= $2 AND {alias}.date <= $3")
    args = [factory_id, DAY, DAY]
    if grain == "item_cost":
        args.extend(bridge)
    row = await conn.fetchrow(sql, *args)
    total = int(row["rows"] or 0)
    nulls = int(row["nulls"] or 0)
    return {"rows": total, "nulls": nulls,
            "fill_rate": (None if not total else round(1 - nulls / total, 4))}


#: 表名 → 该粒度下的别名。⛔ 从 GRAINS 的 join 串反查会脆; 这里显式登记,
#: 与 `GRAINS` 对不上时下面的查询会当场报错(而不是安静地量错一张表)。
_TABLE_ALIAS = {
    "fact_pos_transaction": "t",
    "fact_pos_item": "i",
    "agg_restaurant_product_cost": "c",
    "fact_restaurant_wastage": "w",
    "dim_product": "dp",
}


def _col_ref(table, col, grain):
    alias = _TABLE_ALIAS.get(table)
    if alias is None:
        raise KeyError(f"未登记的表别名: {table}(粒度 {grain})")
    return f"{alias}.{col}"


def _grain_of(metric_key):
    """这个指标算合计时实际用哪个粒度 —— 用产品自己的判断。"""
    from smartbi.gold.restaurant.generic_executor import (
        _base_grains, uses_cost_bridge)
    if uses_cost_bridge(metric_key):
        return "item_cost"
    grains = _base_grains(metric_key)
    if "txn" in grains:
        return "txn"
    return sorted(grains)[0] if grains else "txn"


def _derivable(item, computed_by_key):
    """这个量能不能从**别的、算得出来的**列导出。

    ⛔ 恒等式来自 `Metric.derive_from`(登记表), 本函数只做反查与可算性校验。
    ⚠️ 两个输入必须**自己都算得出来** —— 否则「可导出」是句空话。
    """
    spec = getattr(item, "derive_from", None)
    if not spec:
        return None
    left, right, op = spec
    for side in (left, right):
        got = computed_by_key.get(side)
        if got is None or got.get("value") is None:
            return None
    return left, right, op


async def census_one(conn, factory_id, wiring):
    from smartbi.gold.restaurant.generic_executor import (
        _effective_requires, execute_cell, existing_columns, uses_cost_bridge)
    from smartbi.gold.restaurant.metric_registry import DERIVED, METRICS
    from smartbi.gold.restaurant.provenance import ESTIMATED
    from smartbi.gold.restaurant.restaurant_cost_mapping import cost_bridge_pairs

    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
    cols = await existing_columns(conn)
    try:
        bridge = await cost_bridge_pairs(conn, factory_id)
    except Exception as exc:  # noqa: BLE001
        bridge = ([], [])
        print(f"  ⚠️ 成本桥接取不到: {type(exc).__name__}: {exc}")

    out = []
    for key in sorted(set(METRICS) | set(DERIVED)):
        item = METRICS.get(key) or DERIVED.get(key)
        requires = _effective_requires(key)
        wired = wiring.get(key, {"tokens": [], "fixed_cells": False,
                                 "planner_nameable": False})
        rec = {
            "metric": key,
            "label": getattr(item, "label", key),
            "category": getattr(item, "category", ""),
            "unit": getattr(item, "unit", ""),
            "requires": list(requires),
            "grain": _grain_of(key),
            "uses_cost_bridge": uses_cost_bridge(key),
            "wired_tokens": len(wired["tokens"]),
            "in_fixed_cells": wired["fixed_cells"],
            "planner_nameable": wired["planner_nameable"],
        }

        missing_schema = [c for c in requires if c not in cols]
        rec["missing_from_schema"] = missing_schema

        # 参与计算的行里, 每一列的填充率(判据 3)
        fills = {}
        for column in requires:
            try:
                fills[column] = await _null_rate(
                    conn, factory_id, column, rec["grain"], bridge)
            except Exception as exc:  # noqa: BLE001 —— ⛔ 不吞, 记下来
                fills[column] = {"error": f"{type(exc).__name__}: {exc}"}
        rec["fill"] = fills

        # 产品自己的执行路径(判据 2: 同口径)
        try:
            cell = await execute_cell(
                conn, factory_id=factory_id, metric_key=key,
                dimension_key="all", aggregation_key="summary",
                date_range=(DAY, DAY), available_columns=cols)
            value = cell.rows[0].get(key) if cell.rows else None
            rec["value"] = None if value is None else float(value)
            rec["provenance"] = cell.provenance
            rec["coverage_ratio"] = cell.coverage_ratio
            rec["missing_columns"] = list(cell.missing_columns)
            rec["exec_error"] = None
        except Exception as exc:  # noqa: BLE001 —— ⛔ 失败要计数并逐条贴
            rec.update(value=None, provenance=None, coverage_ratio=None,
                       missing_columns=[],
                       exec_error=f"{type(exc).__name__}: {exc}",
                       traceback=traceback.format_exc().splitlines()[-3:])

        out.append(rec)

    by_key = {r["metric"]: r for r in out}
    for rec in out:
        item = METRICS.get(rec["metric"]) or DERIVED.get(rec["metric"])
        fills = rec["fill"]
        missing_schema = rec["missing_from_schema"]
        wired = wiring.get(rec["metric"], {"tokens": [], "fixed_cells": False,
                                            "planner_nameable": False})
        # ── 分类 ────────────────────────────────────────────────────────
        if rec["exec_error"]:
            rec["verdict"] = "执行失败"
            rec["why"] = rec["exec_error"]
        elif rec["missing_columns"] or missing_schema:
            rec["verdict"] = "算不出"
            rec["why"] = "缺列: " + ", ".join(rec["missing_columns"] or missing_schema)
        elif rec["value"] is None:
            empty = [c for c, f in fills.items()
                     if isinstance(f, dict) and f.get("fill_rate") == 0.0]
            # 🔴 owner 2026-08-14: 「算不出」要先问一句**「这个量能不能从别的列
            #    导出」**。第一版问的是「这一列有没有填」—— 两个真租户的
            #    discount_amount 都是 0% 填充, 于是报「算不出」, 而毛利那条路
            #    **正在用**「原价 − 实收」算折扣。同一个量, 一条路说没有、
            #    一条路在用。⇒ 这是形态 A: 我量的不是我想知道的那个。
            # ⚠️ 恒等式从 `Metric.derive_from` **反查**, ⛔ 不在这里手写清单。
            derived = _derivable(item, by_key)
            if derived:
                left, right, op = derived
                rec["verdict"] = "有但没接线"
                rec["derivable_from"] = f"{left} {op} {right}"
                rec["why"] = (
                    f"这一列没接({', '.join(empty) or '无数据'}), 但这个量可由"
                    f"「{left} − {right}」算出来 —— 是**接线**问题不是缺数据, "
                    f"拿它去补 ETL 补不出东西")
            else:
                rec["verdict"] = "算不出"
                rec["why"] = ("参与计算的行里这些列全空: " + ", ".join(empty)) if empty \
                    else "这段时间没有参与计算的行"
        elif rec["provenance"] == ESTIMATED:
            rec["verdict"] = "是估的"
            cov = rec["coverage_ratio"]
            rec["why"] = f"覆盖率 {cov*100:.1f}%" if cov is not None else "覆盖率未知"
        else:
            rec["verdict"] = "能算准"
            rec["why"] = ""

        # 第四类单独判 —— ⛔ 不混进「算不出」。
        # 「没接线」= 三条路都够不着。⚠️ 只缺关键词规则**不算**没接线,
        #    规划器仍能按 key 点到它 —— 那是「没有快捷措辞」, 另记一栏。
        if not (wired["tokens"] or wired["fixed_cells"]
                or wired["planner_nameable"]):
            rec["unwired"] = True
        elif not wired["tokens"] and not wired["fixed_cells"]:
            rec["no_keyword_shortcut"] = True
    return out


async def main() -> int:
    result = {"day": str(DAY), "tenants": {}}
    for factory_id in TENANTS:
        print(f"=== {factory_id} ===")
        ctx = bootstrap_probe(factory_id)
        wiring = _wiring_index()
        pool = await ctx.pool()
        async with pool.acquire() as conn:
            result["tenants"][factory_id] = await census_one(conn, factory_id, wiring)
        print(f"  {len(result['tenants'][factory_id])} 条")
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=1)
    print(f"WROTE {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
