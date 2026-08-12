"""通用执行器 —— 把 (指标, 维度, 聚合, 时间, 范围) 拼成 SQL 并执行。

🔴 它替代的是什么：现在 20 个 resolver 每个内部硬编「取哪个指标、按什么分组、
   怎么排」，于是 20 个函数 = 20 个格子。本模块不认识任何具体业务问题 ——
   它只认识登记表里的元素，把它们拼起来。14 条登记 → 78 个格子。

⛔ 三条承重约束（违反任何一条都会让这套变得比现在更危险）：

   1. **缺列就如实说缺，绝不算 0**。指标登记声明了 `requires`，拼 SQL 之前
      先核对真实 schema。「你的平台抽佣是 ¥0」比「这项数据你还没接入」危险得多 ——
      前者是个看起来合理的错数字，后者是句实话。

   2. **只拼登记过的东西**。指标表达式、维度分组、排序方向全部来自登记表，
      不接受调用方传入任意 SQL 片段。模型编不出不存在的指标，也注入不了 SQL。

   3. **成本口径走桥接表**。`agg_restaurant_product_cost.product_id` 全库都是 0
      （2026-08-09 实测），必须经 `dim_restaurant_cost_product` 按名字桥接。
      ⛔ 按 product_id 直连会静默得到 0 成本 → 毛利率 100%，一个看起来很棒的错数。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Any, Dict, List, Optional, Tuple

from smartbi.gold.restaurant.provenance import (
    ESTIMATED as PROV_ESTIMATED,
    MEASURED as PROV_MEASURED,
    qualifier as provenance_qualifier,
    validate as validate_provenance,
)
from smartbi.gold.restaurant.metric_registry import (
    AGGREGATIONS,
    DERIVED,
    DIMENSIONS,
    GRAINS,
    METRICS,
)

logger = logging.getLogger(__name__)


@dataclass
class CellResult:
    """一个格子的执行结果。

    `missing_columns` 非空时 `rows` 必须为空 —— 「缺数据」和「查出来是空的」
    是两件事，混在一起会让上层无法如实措辞。
    """
    metric_key: str
    metric_label: str
    dimension_key: str
    aggregation_key: str
    unit: str
    rows: List[Dict[str, Any]]
    missing_columns: Tuple[str, ...] = ()
    sql: str = ""
    # 🔴 2026-08-12 架构收口 C: 这个格子里的数是**账上的**还是**估出来的**。
    #    此前 CellResult 表达不了这个区别 —— 于是同一个店长问毛利, 经营看板
    #    (dish_margin 用行业默认成本率估) 和 AI 问答 (排除未覆盖成本的菜) 会给出
    #    两个不同的数字, 而系统不告诉他为什么。MOCK_REST 覆盖率 100% 所以今天
    #    不发作, 换真租户就发作。见 `provenance.py` 顶部。
    # ⛔ 默认 MEASURED: 新写的格子不标出处就是账上的数 —— 这是安全的一侧。
    #    反过来默认 ESTIMATED 会让没人管的格子悄悄带上「这是估的」。
    provenance: str = PROV_MEASURED
    #: ESTIMATED 时**必填**的依据(如「行业默认成本率 32%」)。限定语由它生成。
    estimation_basis: str = ""

    def __post_init__(self) -> None:
        # 出处不自洽当场炸, 不静默降级 —— 一个估出来的数被当成账上的数端出去,
        # 比不给数字更糟。
        validate_provenance(self.provenance, self.estimation_basis)

    @property
    def ok(self) -> bool:
        return not self.missing_columns

    def qualifier(self, *, coverage_ratio: Optional[float] = None) -> str:
        """这个格子的限定语。⛔ 由字段生成 —— 调用方不许再手写一份。"""
        return provenance_qualifier(
            self.provenance, self.estimation_basis, coverage_ratio=coverage_ratio
        )


class UnsupportedCell(ValueError):
    """请求的组合在登记表里不成立 —— 与「缺数据」是两回事，不能混。"""




async def existing_columns(conn) -> set:
    """当前库里真实存在的 `表.列` 集合。

    ⚠️ 查 information_schema 而不是维护一张「我们支持哪些列」的表 ——
       后者会与真实 schema 漂移，而漂移的方向恰恰是「表里说有、库里没有」，
       那时 requires 校验会放行一条注定失败的 SQL。
    """
    rows = await conn.fetch(
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = 'public'"
    )
    return {f"{r['table_name']}.{r['column_name']}" for r in rows}


def _resolve_spec(metric_key: str, dimension_key: str, aggregation_key: str):
    """把三个 key 解析成登记项，不成立就抛 UnsupportedCell。"""
    if aggregation_key not in AGGREGATIONS:
        raise UnsupportedCell(f"未登记的聚合形态: {aggregation_key}")
    agg = AGGREGATIONS[aggregation_key]
    if dimension_key not in DIMENSIONS:
        raise UnsupportedCell(f"未登记的维度: {dimension_key}")
    dim = DIMENSIONS[dimension_key]
    if agg.needs_dimension and dimension_key == "all":
        raise UnsupportedCell(f"{agg.label}需要一个分组维度，不能用「全店」")

    derived = DERIVED.get(metric_key)
    if derived is not None:
        return derived, dim, agg
    if metric_key not in METRICS:
        raise UnsupportedCell(f"未登记的指标: {metric_key}")
    metric = METRICS[metric_key]
    if dimension_key not in metric.dimensions:
        raise UnsupportedCell(
            f"「{metric.label}」不能按「{dim.label}」分组 —— "
            f"它所在的事实表没有这个维度")
    return metric, dim, agg


def _base_metrics_of(item) -> List[str]:
    """派生量摊平成它依赖的基础指标（递归，因为毛利率依赖毛利）。"""
    if item.__class__.__name__ != "Derived":
        return [item.key]
    out: List[str] = []
    for side in (item.left, item.right):
        if side in DERIVED:
            out.extend(_base_metrics_of(DERIVED[side]))
        else:
            out.append(side)
    seen, uniq = set(), []
    for k in out:
        if k not in seen:
            seen.add(k)
            uniq.append(k)
    return uniq


def build_sql(metric_key: str, dimension_key: str, aggregation_key: str,
              limit_override: Optional[int] = None,
              entity_filter: Optional[str] = None) -> Tuple[str, Tuple[str, ...], List[str]]:
    """返回 (SQL, 依赖的列, 参与计算的基础指标)。

    参数占位：$1=factory_id, $2=起始日期, $3=结束日期。
    ⛔ 三个参数全部走占位符，⛔ 绝不字符串拼接 —— 拼接会把租户隔离变成可注入的。
    """
    item, dim, agg = _resolve_spec(metric_key, dimension_key, aggregation_key)
    base_keys = _base_metrics_of(item)
    metrics = [METRICS[k] for k in base_keys]

    # 🔴 粒度由**维度**决定, 不由指标决定 —— 按菜品分组就必须拉到明细粒度,
    #    而订单级指标在那个粒度上要换表达式, 否则每张订单被每条明细重复计入。
    #    2026-08-09 实测这个 bug: 米饭营收 ¥34,839 → ¥2,001,255(57 倍),
    #    毛利率 99.5%。SQL 跑得通, 数字看着像那么回事, 结论完全错。
    # 维度不强制粒度时(「全店」), 取指标自己支持的粒度; 多个指标取交集里最细的。
    # 粒度 = 维度的最低要求 与 指标们共同支持的粒度, 取**更细**的那个。
    common = set(metrics[0].exprs)
    for m in metrics[1:]:
        common &= set(m.exprs)
    if not common:
        raise UnsupportedCell(
            "这几个指标没有共同的粒度, 放在一起算会扇出 —— 故拒绝")
    if dim.min_grain and dim.min_grain in common:
        grain = dim.min_grain
    elif dim.min_grain:
        raise UnsupportedCell(
            f"「{getattr(item,'label',metric_key)}」不在「{dim.label}」所属的事实表上")
    else:
        grain = "txn" if "txn" in common else sorted(common)[0]
    for m in metrics:
        if m.expr_at(grain) is None:
            raise UnsupportedCell(
                f"「{m.label}」在{DIMENSIONS[dimension_key].label}粒度上没有定义 —— "
                f"拿另一个粒度的表达式硬凑会算错, 故拒绝")

    # 成本要额外的桥接 join, 所以来源取「最宽」的那个
    needs_cost = any(m.key == "food_cost" for m in metrics)
    source = "item_cost" if needs_cost else grain
    from_clause, source_join, alias = GRAINS[source]

    selects, requires = [], []
    for m in metrics:
        selects.append(f"{m.expr_at(grain)} AS {m.key}")
        requires.extend(m.requires)

    joins = [source_join] if source_join else []
    group_cols, order_target = [], base_keys[0]
    if dim.group_expr:
        if dim.join and dim.join not in source_join:
            joins.append(dim.join)
        selects.insert(0, f"{dim.label_expr} AS dim_label")
        selects.insert(0, f"{dim.group_expr} AS dim_key")
        group_cols = [dim.group_expr, dim.label_expr]

    # 派生量在 SQL 里算完，避免上层再算一遍造成两处口径
    if item.__class__.__name__ == "Derived":
        selects.append(_derived_expr(item, grain) + f" AS {item.key}")
        order_target = item.key

    sql = f"SELECT {', '.join(selects)}\n  FROM {from_clause}\n"
    for j in joins:
        sql += f"  {j}\n"
    # ⚠️ 事实表别名来自 GRAINS —— 损耗链上没有 `t`，硬编 "t" 会拼出
    #    `missing FROM-clause entry for table "t"`（2026-08-09 加损耗指标时实测撞到）。
    sql += (f" WHERE {alias}.factory_id = $1 "
            f"AND {alias}.date >= $2 AND {alias}.date <= $3\n")
    # 🔑 实体过滤: 用户点名了某道菜/某家店时, 只算那一个。
    #
    # ⛔ 走占位符 $4, **绝不字符串拼接** —— 菜名是用户原话摘抄的, 拼接就是注入口。
    # ⚠️ 比的是 `label_expr`(展示名, 如 p.name) 而不是 `group_expr`(内部 id):
    #    规格里的 `dish_slot` 是**用户说的名字**, 不是 id。
    # ⛔ 只在有分组维度时才允许 —— 「全店」没有可过滤的对象, 传进来要拒绝而不是
    #    静默忽略(静默忽略 = 用户点了名却给了全部, 答非所问)。
    if entity_filter is not None:
        if not dim.label_expr:
            raise UnsupportedCell(
                f"「{dim.label}」没有可过滤的对象 —— 点名某一个的问法在这个维度上不成立")
        sql += f"   AND {dim.label_expr} = $4\n"
    if group_cols:
        sql += f" GROUP BY {', '.join(group_cols)}\n"
    if agg.order:
        # 🔴 「趋势」按**维度自身**排(1 号→31 号), 其余按**值**排(最高的在前)。
        #    用值排序做趋势会把时间序列打乱成排行榜 —— 图还是画得出来, 但它
        #    表达的东西完全不是用户问的那个。
        target = "dim_key" if agg.order_by == "dim" else order_target
        sql += f" ORDER BY {target} {agg.order.upper()} NULLS LAST\n"
    # 用户说了要几条就给几条；没说才用登记的默认值。
    # ⛔ 只对**本来就有 limit** 的形态生效 —— 给「对比」「趋势」加 limit 会
    #    悄悄截断结果，而截断过的清单和完整清单长得一模一样。
    effective_limit = limit_override if (agg.limit and limit_override) else agg.limit
    if effective_limit:
        sql += f" LIMIT {int(effective_limit)}\n"
    return sql, tuple(dict.fromkeys(requires)), base_keys


def _as_float(value) -> Optional[float]:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _post_process(rows: List[Dict[str, Any]], agg, value_key: str) -> List[Dict[str, Any]]:
    """聚合形态的**行处理** —— ⛔ 全部在这里做，不改 SQL。

    Why：同一个指标在 9 种形态下必须是**同一个数**。如果每种形态各写一段 SQL，
    「本月营收」和「本月营收占比里的营收」就会有两处口径，而它们迟早会不一致 ——
    这正是 20 个 resolver 各自硬编取数时发生过的事。

    ⚠️ 这些形态都依赖顺序（两端 / 累计到 80%），而顺序由 SQL 的 ORDER BY 保证。
       登记表里有一条断言强制「要做行处理就必须声明排序」，就是为了守这个前提。
    """
    if not agg.post or not rows:
        return rows
    values = [_as_float(r.get(value_key)) for r in rows]
    known = [v for v in values if v is not None]

    if agg.post == "extremes":
        # 「最好和最差分别是哪个」—— 只有 ≥2 行才有「两端」可言。
        return rows if len(rows) < 2 else [rows[0], rows[-1]]

    if agg.post == "above_avg":
        if not known:
            return rows
        mean = sum(known) / len(known)
        # ⚠️ 阈值是**算出来的**不是拍的; 附上均值让用户能核对这条线画在哪。
        # ⚠️ 严格大于, 不是 >= —— 「高于平均」里正好等于平均的那家**不算高于**。
        #    这不是抠字眼: 均匀分布时用 >= 会把全部项都列进「高于平均」,
        #    于是这个形态永远返回全集, 等于什么都没筛。
        out = []
        for r, v in zip(rows, values):
            if v is not None and v > mean:
                out.append({**r, "_threshold": mean})
        return out

    total = sum(known)
    if total <= 0:
        # ⛔ 总额为 0 或负时不算占比 —— 除出来的百分比没有意义, 而一个
        #    「占比 -340%」比没有占比更容易让人得出错误结论。
        return rows
    if agg.post == "share":
        return [{**r, "share": (v / total * 100) if v is not None else None}
                for r, v in zip(rows, values)]
    if agg.post == "concentration":
        out, cum = [], 0.0
        for r, v in zip(rows, values):
            if v is None:
                continue
            cum += v
            out.append({**r, "share": v / total * 100, "cum_share": cum / total * 100})
            if cum / total >= 0.8:
                break
        return out
    return rows


def _derived_expr(d, grain: str = "txn") -> str:
    """派生量的 SQL 表达式。除法一律 NULLIF 防除零 —— 除零会让整条查询炸，
    而「分母是 0」在业务上是「没有订单」，该返回空不是报错。"""
    left = METRICS[d.left].expr_at(grain) if d.left in METRICS else None
    right = METRICS[d.right].expr_at(grain) if d.right in METRICS else None
    if d.op == "diff":
        return f"({left} - {right})"
    if d.op == "ratio":
        return f"({left} / NULLIF({right}, 0))"
    if d.op == "ratio_pct":
        # 折扣率 / 抽佣率 / 退菜率 —— 与 ratio 的差别只有「乘 100」，
        # 但登记成两种运算而不是让叙述层去猜单位：单位是**数据的属性**，
        # 猜错的方向是把 0.32 显示成「32%」或把 32 显示成「32%」，两者都错得很像对。
        return f"({left} / NULLIF({right}, 0) * 100)"
    if d.op == "ratio_of_diff":
        inner = _derived_expr(DERIVED[d.left], grain)
        return f"({inner} / NULLIF({right}, 0) * 100)"
    raise UnsupportedCell(f"未登记的派生运算: {d.op}")


async def execute_cell(
    conn,
    *,
    factory_id: str,
    metric_key: str,
    dimension_key: str,
    aggregation_key: str,
    date_range: Tuple[date, date],
    available_columns: Optional[set] = None,
    limit_override: Optional[int] = None,
    entity_filter: Optional[str] = None,
) -> CellResult:
    """执行一个格子。缺列时**不发 SQL**，直接回「缺什么」。"""
    item, dim, agg = _resolve_spec(metric_key, dimension_key, aggregation_key)
    sql, requires, _base = build_sql(metric_key, dimension_key, aggregation_key,
                                     limit_override=limit_override,
                                     entity_filter=entity_filter)

    cols = available_columns if available_columns is not None else await existing_columns(conn)
    missing = tuple(c for c in requires if c not in cols)
    label = getattr(item, "label", metric_key)
    unit = getattr(item, "unit", "count")
    if missing:
        # ⛔ 这里 return 而不是继续跑 —— 跑下去 COALESCE 会把缺失算成 0。
        logger.info("[generic-executor] 缺列, 不执行: metric=%s missing=%s",
                    metric_key, missing)
        return CellResult(metric_key, label, dimension_key, aggregation_key,
                          unit, [], missing, sql)

    start, end = date_range
    args = [factory_id, start, end]
    if entity_filter is not None:
        args.append(entity_filter)
    rows = await conn.fetch(sql, *args)
    # 值列名: 派生量用它自己的 key, 基础指标用它的 key —— 两者都是 item.key。
    processed = _post_process([dict(r) for r in rows], agg, getattr(item, "key", metric_key))
    provenance, basis = _provenance_of(metric_key)
    return CellResult(
        metric_key, label, dimension_key, aggregation_key, unit,
        processed, (), sql, provenance, basis,
    )


#: 成本卡那一列。⛔ 它是**理论用量**的来源, 不是实际耗用。
#:
#: 🔴 owner 2026-08-13 定的口径: 当日毛利的成本项来自成本卡时,
#:    `provenance = ESTIMATED`。理由: **成本卡 × 销量是理论耗用, 实际耗用要盘点
#:    才知道**(邓总店里 10 天盘一次库 —— 当日实际耗用根本拿不到)。
#: ⛔ 这**不是**「算不出」, 正是 provenance 存在的理由: 算得出, 但要说清是估的。
_COST_CARD_COLUMN = "agg_restaurant_product_cost.food_cost"

#: 人话, 不是术语。店长要能据此判断这个数能不能用来做决定。
#: ⚠️ 写成**名词短语**(不是整句) —— 限定语模板是「用{basis}估算，…」,
#:    塞一句完整的话进去会读成「用按成本卡的理论用量算的，实际用了多少要等盘点估算」。
#:    实测过一次, 当场读不通。
_COST_CARD_BASIS = "成本卡的理论用量（实际用了多少要等盘点）"


def _effective_requires(metric_key: str) -> Tuple[str, ...]:
    """这个指标**实际依赖的列**, 派生量递归展开到基础指标。

    🔴 不展开就会漏: `gross_profit = revenue - food_cost` 是 `Derived`,
       它**没有 `requires`**(空元组), 于是「毛利」会被判成 MEASURED ——
       而毛利的成本项正来自成本卡。实测当场抓到这一条。
    ⛔ 与 `fill_offers` 那边的闭包是同一件事的两面, 但**不共用实现**:
       那边问「补这一列能解锁什么」, 这边问「这个数依赖哪些列」——
       方向相反, 合并会让两边都变得难读。⚠️ 两边都要跟着 registry 走, 别手写。
    """
    seen: set = set()
    stack = [metric_key]
    cols: set = set()
    while stack:
        key = stack.pop()
        if key in seen:
            continue
        seen.add(key)
        base = METRICS.get(key)
        if base is not None:
            cols.update(base.requires)
            continue
        derived = DERIVED.get(key)
        if derived is not None:
            stack.extend((derived.left, derived.right))
    return tuple(sorted(cols))


def _provenance_of(metric_key: str) -> Tuple[str, str]:
    """从**这个指标实际依赖的列**推出出处 —— ⛔ 不是查一张手写的指标名单。

    判据可推导: 依赖里出现成本卡那一列, 这个数就含理论耗用 -> ESTIMATED。
    手写「哪些指标是估的」会在新增指标时静默漏掉, 而漏掉的方向是
    **把估的说成实的** —— 那正是最坏的方向。

    ⚠️ 覆盖率(有多少菜有成本卡)是**另一件事**, 由 `coverage_ratio` 表达;
       这里回答的是「这个数的成本项是怎么来的」。两者都进限定语, 不互相替代。
    """
    if _COST_CARD_COLUMN in _effective_requires(metric_key):
        return PROV_ESTIMATED, _COST_CARD_BASIS
    return PROV_MEASURED, ""
