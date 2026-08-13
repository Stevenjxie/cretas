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
from decimal import Decimal
from typing import Any, Dict, List, Optional, Tuple

from smartbi.gold.restaurant.provenance import (
    ESTIMATED as PROV_ESTIMATED,
    MEASURED as PROV_MEASURED,
    coverage_ratio as prov_coverage_ratio,
    qualifier as provenance_qualifier,
    validate as validate_provenance,
)
from smartbi.gold.restaurant.metric_registry import (
    AGGREGATIONS,
    COST_BRIDGE_KEY_PARAM,
    dish_cost_is_implausible,
    DERIVED,
    DIMENSIONS,
    GRAINS,
    METRICS,
)
from smartbi.gold.restaurant.restaurant_cost_mapping import (
    CostKeySourceUnavailable,
    cost_bridge_pairs,
)

logger = logging.getLogger(__name__)

#: item_cost 粒度上 `entity_filter` 的占位符 —— 排在两个桥接数组之后。
_ENTITY_PARAM_WITH_BRIDGE = COST_BRIDGE_KEY_PARAM + 1
#: 其余粒度上没有桥接数组, 实体过滤紧跟三个基础参数。
_ENTITY_PARAM_PLAIN = 4


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
    #: 有成本卡的营收 ÷ 全部营收。`None` = 这个格子不按覆盖率表达。
    #:
    #: 🔴 为什么必须有: `food_cost` 的表达式是
    #:      `SUM(i.qty * c.food_cost)`(2026-08-13 去掉 COALESCE 之前是补 0 的)
    #:    —— 补 0 时**没有成本卡的菜按零成本计入**, 毛利被抬高, 而限定语只说
    #:    「按成本卡估算」, 不说这个估只覆盖了几成。覆盖率 40% 的租户会看到
    #:    一个高得离谱的毛利 + 一句听起来已经解释过了的限定语。
    #: ⚠️ 分母用 **item 口径**(`SUM(i.amount)`), 因为 food_cost 是 item 粒度的。
    coverage_ratio: Optional[float] = None

    #: 因**成本卡单位明显错误**被排除出毛利计算的菜, 指名带出来。
    #: 🔴 owner 2026-08-13: 那不是一句免责声明, 是一条**可执行的修复指令** ——
    #:    「米饭这道菜的成本卡是 167.20 元一份, 而它卖 16.80, 请核对单位」。
    #: ⚠️ 静默排除 = 降级处理: 答案看起来正常而数据是坏的, 没人会去修。
    cost_outliers: Tuple[Dict[str, Any], ...] = ()

    #: T2 第一层「缺口」: 当期有销售但**没有成本卡**的菜, 按营收从高到低。
    #: 第二层「优先级」就是这个顺序 —— ⛔ 不另设一套打分。
    cost_gaps: Tuple[Dict[str, Any], ...] = ()
    #: 覆盖率的分母(item 口径全额营收)。开价要算「补 N 道能到几成」, 分母
    #: **必须**是算 `coverage_ratio` 用的那一个, ⛔ 不许另取一次数。
    coverage_denominator: Optional[float] = None

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


def uses_cost_bridge(metric_key: str) -> bool:
    """这个指标算下来要不要经过成本桥接 —— 也就是 SQL 里要不要那两个数组参数。

    🔴 **唯一定义。** 拼 SQL 的 `build_sql` 和准备实参的 `execute_cell` 必须读
       同一个答案, 否则 `$N` 与实参错位。这是「闸的左右两边来源相同」的反面:
       这里恰恰**要求**两处同源, 因为它们描述的是同一件事。
    ⚠️ 派生量要递归展开: `gross_margin` 自己没有 `requires`, 但它的分子
       `gross_profit` 里有 `food_cost`。不展开就会漏掉最需要桥接的那几个。
    """
    item = METRICS.get(metric_key) or DERIVED.get(metric_key)
    if item is None:
        return False
    return "food_cost" in _base_metrics_of(item)


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
    # ⛔ 判据走 `uses_cost_bridge` —— 与 `execute_cell` 准备实参时读的是同一处。
    needs_cost = uses_cost_bridge(metric_key)
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
        # ⚠️ 序号随粒度变: item_cost 上 $4/$5 已经被两个桥接数组占了。
        #    ⛔ 这一处和 `execute_cell` 拼实参那一处都读 `needs_cost`, 不许各判各的。
        entity_param = _ENTITY_PARAM_WITH_BRIDGE if needs_cost else _ENTITY_PARAM_PLAIN
        sql += f"   AND {dim.label_expr} = ${entity_param}\n"
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


def _base_grains(key: str) -> set:
    """一个指标自己支持的粒度集合。派生量取两个输入的并集。"""
    m = METRICS.get(key)
    if m is not None:
        return set(m.exprs)
    d = DERIVED.get(key)
    if d is None:
        return set()
    return _base_grains(d.left) | _base_grains(d.right)


def _needs_split_execution(item, agg) -> bool:
    """这个派生量在**合计层**必须拆开算吗。

    ## 🔴 为什么(owner 2026-08-13 裁定 2)

    毛利 = 营收 − 食材成本。食材成本只有 item 粒度的表达式, 于是
    `common = {item}`, 营收被迫用 `exprs["item"] = SUM(i.amount)` ——
    那是**明细行原价合计**, 没有扣掉交易级折扣。

    prod 实测(MOCK_REST / 2026-08-12): 749,009.00 − 242,259.58 = 506,749.42,
    而实收营收是 717,883.41。**毛利虚高 31,125.59(折扣额, 约 6.5%)。**
    靠满减跑量的店虚得更多。

    ⛔ **用原价算收入等于把从未收到的钱算成收入。** 毛利的定义是实收减成本,
       没有选择余地 —— 这不是「另一种口径」, 是错的。

    ⚠️ 当初选 item 的理由是可以理解的工程直觉: 成本是 item 级的, 配 item 级
       营收在同一粒度上, 而且能防扇出(2026-08-09 实测过 57 倍的扇出事故)。
       **但粒度一致不能凌驾于口径正确。** 防扇出的正解是拆开算, 不是换口径。

    ## 为什么只在合计层

    分组层(按菜/按店)拿不到交易级折扣的归属 —— 「这张单的满减该摊到哪道菜」
    是个有真争议的产品决定(按金额还是按份数?)。那一层**保持原样**并已挂账:
    将来的处置是 `provenance=ESTIMATED` + basis「折扣是整单的，没法摊到单道菜」,
    与成本卡那条同一个机制, 不用新建。

    判据: `agg.needs_dimension is False` —— 用登记表**已有的声明**判「这是合计层」,
    ⛔ 不新造假设。
    """
    if item.__class__.__name__ != "Derived":
        return False
    if getattr(agg, "needs_dimension", True):
        return False
    return _mixes_grains(item.key)


def _mixes_grains(key: str) -> bool:
    """这个派生量算出来会不会混口径。

    ⚠️ **必须递归**: `gross_margin = gross_profit ÷ revenue` 的两个输入都含
       txn 粒度, 看起来不混 —— 但分子 `gross_profit` 自己是混的。
       第一版没递归, `gross_margin` 就绕过了修正, 于是「毛利」对了而「毛利率」
       还是错的。**这条是被测试抓住的, 不是我想到的。**
    """
    d = DERIVED.get(key)
    if d is None:
        return False
    # 两个输入共享 txn 粒度 → 这一层本来就能用实收营收, 这一层不混。
    if "txn" not in (_base_grains(d.left) & _base_grains(d.right)):
        return True
    # 这一层不混, 但下面某一层可能混。
    return _mixes_grains(d.left) or _mixes_grains(d.right)


def _scalar(cell: "CellResult", key: str):
    return cell.rows[0].get(key) if cell.rows else None


#: 覆盖部分的三个量, 一次查出来 —— 分子分母必须来自**同一批行**。
#: join 从 `GRAINS["item_cost"]` 取, 不重抄(抄一份就是同一条 join 两个定义)。
#:
#: 🔴 2026-08-14: 排除条件从「行级 SQL 判据」改成「**菜名数组**」——
#:    判定在 Python 一处 (`dish_cost_is_implausible`), SQL 只负责照名单剔除。
#:    行级判会让同一张卡的判决取决于那天这道菜恰好怎么卖的, 实测差 19,131.37。
_COVERED_MARGIN_SQL = (
    "SELECT COALESCE(SUM(i.amount) FILTER (WHERE c.food_cost IS NOT NULL"
    "                                       AND NOT ({excluded})), 0)"
    "         AS covered_gross,\n"
    "       COALESCE(SUM(i.amount), 0)                    AS all_gross,\n"
    "       COALESCE(SUM(i.qty * c.food_cost) FILTER (WHERE NOT ({excluded})), 0)"
    "         AS covered_cost\n"
    "  FROM {frm}\n  {join}\n"
    " WHERE {alias}.factory_id = $1 AND {alias}.date >= $2 AND {alias}.date <= $3\n"
)

#: 「这道菜今天卖了多少、卡上写多少」—— 判定的**输入**, 不含判定本身。
#: ⚠️ 按菜聚合(GROUP BY 名字), 因为判据的粒度是菜。
_DISH_COST_FACTS_SQL = (
    "SELECT dp.normalized_name                    AS name,\n"
    "       SUM(i.qty)                            AS qty,\n"
    "       SUM(i.amount)                         AS revenue,\n"
    "       max(c.food_cost)                      AS unit_cost\n"
    "  FROM {frm}\n  {join}\n"
    " WHERE {alias}.factory_id = $1 AND {alias}.date >= $2 AND {alias}.date <= $3\n"
    # ⚠️ 2026-08-14: **不再**在 SQL 里滤掉「没有卡」的菜 —— 同一份取数要同时
    #    服务两件事: ①判卡对不对(只看有卡的) ②T2 开价(恰恰要没卡的那些)。
    #    ⛔ 为②另写一条 SQL 就是同一条事实链两个定义, 这一周修的就是这个病。
    #    有没有卡在 Python 里按 `unit_cost is None` 分, 一处判断。
    " GROUP BY 1\n"
)

#: 被排除的菜名进 SQL 用的占位符。⚠️ 只用于本模块的两个模板,
#: 与 `build_sql` 里 $6 = entity_filter 不冲突(不同模板, 各自独立编号)。
_EXCLUDED_PARAM = 6
#: SQL 里那句「这道菜在不在排除名单里」。⛔ 它**不是判据** —— 判据在 Python,
#: 这里只是把算好的名单套上去。
_EXCLUDED_EXPR = f"dp.normalized_name = ANY(${_EXCLUDED_PARAM}::text[])"


async def _dish_cost_facts(conn, factory_id: str, date_range, bridge):
    """逐菜 (名字, 份数, 营收, 卡)。**一次取数, 两个消费者。**

    ⛔ 不在 SQL 里滤「有没有卡」—— 判卡对不对要有卡的, T2 开价要没卡的,
       两条 SQL 就是同一条事实链两个定义。
    """
    frm, join, alias = GRAINS["item_cost"]
    names, keys = bridge
    rows = await conn.fetch(
        _DISH_COST_FACTS_SQL.format(frm=frm, join=join, alias=alias),
        factory_id, date_range[0], date_range[1], names, keys)
    out = []
    for row in rows or ():
        name = row["name"]
        if not name:
            continue
        out.append({
            "name": name,
            "qty": float(row["qty"] or 0),
            "revenue": float(row["revenue"] or 0),
            "unit_cost": row["unit_cost"],
        })
    return out


def _cost_outliers(facts):
    """按**菜**判成本卡单位错没错。返回 (排除名单, 指名用的明细)。

    🔴 判定只调 `dish_cost_is_implausible` —— 全仓唯一的一处。
       本函数负责组装, **一个比较符号都不写**。
    """
    excluded, detail = [], []
    for f in facts:
        unit_cost = f["unit_cost"]
        if unit_cost is None:          # 没有卡 ≠ 卡是坏的, 那一类由 T2 开价管
            continue
        if not dish_cost_is_implausible(unit_cost, f["qty"], f["revenue"]):
            continue
        excluded.append(f["name"])
        detail.append({
            "name": f["name"],
            "card_cost": float(unit_cost or 0),
            # 均价与判据用的是**同一个** revenue/qty, ⛔ 不许在正文里另算一个
            "avg_price": (f["revenue"] / f["qty"]) if f["qty"] else 0.0,
        })
    detail.sort(key=lambda d: d["card_cost"], reverse=True)
    return excluded, tuple(detail[:5])


def _cost_gaps(facts):
    """T2 第一层: **哪几道菜没有成本卡**, 按营收从高到低。

    🔴 owner 2026-08-14 放行 T2 前两层。第一层是「缺口」, 排序就是第二层的
       「优先级」—— ⛔ 优先级不是另一套打分, 就是营收本身。
    ⚠️ 只算**当期有销售**的菜: 菜单上有而没卖的补了也不改变毛利覆盖率,
       把它排进「先补这几道」是浪费店长的时间。
    """
    gaps = [{"name": f["name"], "revenue": f["revenue"]}
            for f in facts if f["unit_cost"] is None and f["revenue"] > 0]
    gaps.sort(key=lambda g: g["revenue"], reverse=True)
    return tuple(gaps)


#: 折扣按明细金额比例摊派 —— owner 2026-08-13 裁定。
#: 🔑 **折扣总额是实测的, 只有它在明细行之间怎么分是估的** ——
#:    我们不是在猜折扣, 是在**分配一个已知的数**。
#: 📌 为什么摊比不摊好: 不摊的误差**系统性偏向好看**(折扣永远是减项, 不减就一律虚高);
#:    按比例摊的误差不系统偏向任何一边。**两者不是同一档的近似。**
#: ⛔ 这不解冻「精确摊派规则」那条挂账 —— 那条争的是**定向折扣该不该归给它针对的
#:    那道菜**, 只改分配、不改总额。按比例摊是分配问题的默认解。
_DISCOUNT_ALLOC_BASIS = "按明细金额摊派的折扣"


async def _covered_margin(conn, factory_id: str, date_range, bridge):
    """覆盖部分的 (净营收, 成本, 覆盖率)。算不出来返回 None。

    🔴 owner 2026-08-13 裁定: **毛利的分子和分母都只算有成本卡的那部分。**
       改之前分子用全额营收、分母用覆盖额, 三个症状同源:
         DEMO_REST 日结毛利率 88.3% / 青花椒问答整个拒答 / 问答正文自己算不平。

    :param bridge: `(菜名[], 成本键[])` —— 由 `cost_bridge_pairs` 解析好传进来。
        ⛔ 不在这里自己去解析: 解析可能抛 `CostKeySourceUnavailable`, 而本函数
           的 `except` 是「取数失败返回 None」—— 那会把「权威来源断了」吞成
           「这段时间算不出毛利」, 正是要杜绝的静默降级。
    """
    frm, join, alias = GRAINS["item_cost"]
    names, keys = bridge
    # 🔴 先按**菜**判出排除名单 —— 判定在 `dish_cost_is_implausible` 一处,
    #    SQL 只照名单剔除。⛔ 曾经这里是一条行级 SQL 判据, 与问答那侧的
    #    菜级判据长得像但不等价, 实测两条路差 19,131.37 全部来自一道菜。
    try:
        facts = await _dish_cost_facts(conn, factory_id, date_range, bridge)
    except Exception:  # noqa: BLE001
        logger.warning("[generic-executor] 逐菜成本取数失败", exc_info=True)
        return None
    excluded, outliers = _cost_outliers(facts)
    # T2 第一层+第二层的原料: 没卡的菜, 按营收排好序。
    # ⛔ 与上面同一份 `facts` —— 两次取数会让「缺口」和「覆盖率」算的不是同一批菜。
    gaps = _cost_gaps(facts)

    sql = _COVERED_MARGIN_SQL.format(frm=frm, join=join, alias=alias,
                                     excluded=_EXCLUDED_EXPR)
    try:
        row = await conn.fetchrow(sql, factory_id, date_range[0], date_range[1],
                                  names, keys, excluded)
        paid = await conn.fetchval(
            "SELECT COALESCE(SUM(t.net_amount), 0) FROM fact_pos_transaction t "
            " WHERE t.factory_id = $1 AND t.date >= $2 AND t.date <= $3",
            factory_id, date_range[0], date_range[1])
    except Exception:  # noqa: BLE001
        logger.warning("[generic-executor] 覆盖毛利取数失败", exc_info=True)
        return None
    if row is None:
        return None
    covered_gross = Decimal(str(row["covered_gross"] or 0))
    all_gross = Decimal(str(row["all_gross"] or 0))
    covered_cost = Decimal(str(row["covered_cost"] or 0))
    if not all_gross:
        return None
    # 折扣总额 = 明细原价合计 − 交易实收。⛔ 它是**实测的**, 不是估的。
    discount = all_gross - Decimal(str(paid or 0))
    share = covered_gross / all_gross
    covered_net = covered_gross - discount * share

    # 被排除的菜**指名带出去** —— owner: 那不是一句免责声明, 是一条
    # **可执行的修复指令**(「米饭成本卡 167.20 而它卖 16.80, 请核对单位」)。
    # 🔑 owner 2026-08-14: 这句话是当初冻结那张卡的**全部理由** ——
    #    差额归零但产品说不出这句, 这一节不算做完。
    #
    # `gaps` / `all_gross` 是 T2 前两层的原料: 补某几道之后覆盖率能到多少,
    # 分母**必须是这里的 all_gross** —— 另取一次数就是第二个定义。
    return covered_net, covered_cost, share, outliers, gaps, float(all_gross)


async def _execute_derived_split(
    conn, item, *, dimension_key: str, aggregation_key: str,
    factory_id: str, date_range, available_columns, bridge,
) -> "CellResult":
    """把派生量拆成两个基础指标**各按自己的粒度**独立执行, 再在 Python 里合。

    ⛔ 不新写 SQL —— 两边都走同一个 `execute_cell`, 各自拿到自己正确的粒度
       (营收 → txn 实收; 食材成本 → item_cost)。**没有 join, 就没有扇出。**
    """
    label = getattr(item, "label", item.key)
    unit = getattr(item, "unit", "count")

    # 🔴 靠成本卡的派生量走**覆盖口径**: 分子分母都只算有成本卡的那部分,
    #    且折扣按明细金额比例摊到覆盖部分。⛔ 不再「全额分子 vs 覆盖额分母」。
    if _COST_CARD_COLUMN in _effective_requires(item.key):
        got = await _covered_margin(conn, factory_id, date_range, bridge)
        if got is None:
            return CellResult(item.key, label, dimension_key, aggregation_key,
                              unit, [], (), "-- covered-margin: 取数失败 --")
        covered_net, covered_cost, share, cost_outliers, cost_gaps, denom = got
        profit = covered_net - covered_cost
        if item.op == "diff":                 # 毛利
            value = profit
        elif item.op == "ratio_of_diff":      # 毛利率 = 覆盖毛利 ÷ **覆盖净营收**
            value = (profit / covered_net * 100) if covered_net else None
        else:
            raise UnsupportedCell(f"覆盖口径不支持的派生运算: {item.op}")
        return CellResult(
            item.key, label, dimension_key, aggregation_key, unit,
            [{item.key: value}], (), _COVERED_MARGIN_SQL,
            PROV_ESTIMATED,
            # ⚠️ 用顿号不用分号: 分号在 `_BASIS_FORBIDDEN` 里(它是句子的标志),
            #    而 basis 必须是名词短语 —— 我自己上一轮立的那条约束。
            f"{_COST_CARD_BASIS}、{_DISCOUNT_ALLOC_BASIS}",
            float(share),
            tuple(cost_outliers),
            tuple(cost_gaps),
            denom,
        )

    async def _one(key: str) -> "CellResult":
        return await execute_cell(
            conn, factory_id=factory_id, metric_key=key,
            dimension_key=dimension_key, aggregation_key=aggregation_key,
            date_range=date_range, available_columns=available_columns)

    left = await _one(item.left)
    right = await _one(item.right)
    # 缺列要原样上报 —— ⛔ 少一边就把另一边当成全部, 那是拿半个数冒充结果。
    missing = tuple(dict.fromkeys(left.missing_columns + right.missing_columns))
    sql = f"-- split(左) --\n{left.sql}\n-- split(右) --\n{right.sql}"
    if missing:
        return CellResult(item.key, label, dimension_key, aggregation_key,
                          unit, [], missing, sql)

    lv, rv = _scalar(left, item.left), _scalar(right, item.right)
    value = None
    if lv is not None and rv is not None:
        lv, rv = Decimal(str(lv)), Decimal(str(rv))
        if item.op == "diff":
            value = lv - rv
        elif item.op == "ratio":
            value = (lv / rv) if rv else None
        elif item.op == "ratio_pct":
            value = (lv / rv * 100) if rv else None
        elif item.op == "ratio_of_diff":
            # 分子本身是个派生量(毛利), 递归走同一条拆分路径。
            inner = await _execute_derived_split(
                conn, DERIVED[item.left], dimension_key=dimension_key,
                aggregation_key=aggregation_key, factory_id=factory_id,
                date_range=date_range, available_columns=available_columns,
                bridge=bridge)
            iv = _scalar(inner, item.left)
            value = (Decimal(str(iv)) / rv * 100) if (iv is not None and rv) else None
        else:
            raise UnsupportedCell(f"未登记的派生运算: {item.op}")

    provenance, basis = _provenance_of(item.key)
    coverage = (await _coverage_ratio_of(conn, factory_id, date_range, bridge)
                if provenance == PROV_ESTIMATED else None)
    return CellResult(
        item.key, label, dimension_key, aggregation_key, unit,
        [{item.key: value}], (), sql, provenance, basis, coverage,
    )


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
    """执行一个格子。缺列时**不发 SQL**，直接回「缺什么」。

    :raises CostKeySourceUnavailable: 要走成本桥接、而权威来源 (cretas 运营库)
        够不着时**直接抛**, ⛔ 不降级。见 `restaurant_cost_mapping` 顶部:
        少了权威层与「这些菜没有成本卡」在数值上完全一样, 而后者会被端给店长。
    """
    item, dim, agg = _resolve_spec(metric_key, dimension_key, aggregation_key)

    # 🔑 菜名→成本键**解析一次**, 本格子里所有 SQL 共用同一份。
    #    ⛔ 不许某条 SQL 自己再解析一遍 —— 两次解析之间池子状态可能不同,
    #       表现就是「毛利和覆盖率算的不是同一批菜」。
    bridge = (await cost_bridge_pairs(conn, factory_id)
              if uses_cost_bridge(metric_key) else None)

    # 🔴 合计层的口径修正 —— owner 2026-08-13 裁定 2。见 `_needs_split_execution`。
    if _needs_split_execution(item, agg):
        return await _execute_derived_split(
            conn, item, dimension_key=dimension_key, aggregation_key=aggregation_key,
            factory_id=factory_id, date_range=date_range,
            available_columns=available_columns, bridge=bridge)

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
    # ⚠️ 顺序必须与 `build_sql` 里的占位符编号一致: $1-$3 基础, $4/$5 桥接数组
    #    (只在 item_cost 粒度上), 之后才是 entity_filter。
    #    ⛔ 两处都由 `uses_cost_bridge(metric_key)` 决定, 不许各判各的。
    args = [factory_id, start, end]
    if bridge is not None:
        args.extend(bridge)
    if entity_filter is not None:
        args.append(entity_filter)
    rows = await conn.fetch(sql, *args)
    # 值列名: 派生量用它自己的 key, 基础指标用它的 key —— 两者都是 item.key。
    processed = _post_process([dict(r) for r in rows], agg, getattr(item, "key", metric_key))
    provenance, basis = _provenance_of(metric_key)
    # ⚠️ 只有靠成本卡估出来的格子才需要覆盖率 —— 别的格子多跑一次查询纯属浪费,
    #    而且 `qualifier()` 对 MEASURED + 覆盖率不足会说出「未覆盖成本的菜品
    #    无法判断盈亏」, 那对一个跟成本无关的指标(比如订单数)是句错话。
    coverage = (await _coverage_ratio_of(conn, factory_id, date_range, bridge)
                if provenance == PROV_ESTIMATED else None)
    return CellResult(
        metric_key, label, dimension_key, aggregation_key, unit,
        processed, (), sql, provenance, basis, coverage,
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
#: 🔴 2026-08-13 owner 定稿: 去掉原来的括号「（实际用了多少要等盘点）」——
#:    那是第三层嵌套, 而且**对店长不产生行动**(他不会因为这句去改盘点周期)。
#:    真要改, 第三段的开价会告诉他「补齐成本卡就能从估变实」。
_COST_CARD_BASIS = "成本卡的理论用量"


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


#: 覆盖率查询。**join 从 `GRAINS["item_cost"]` 取**, 不在这里重抄一份 ——
#: 抄一份就是同一条 join 有两个定义, 而漂的表现是「覆盖率和毛利算的不是同一批菜」。
_COVERAGE_SQL_TEMPLATE = (
    "SELECT COALESCE(SUM(i.amount), 0) AS total,\n"
    "       COALESCE(SUM(i.amount) FILTER (WHERE c.food_cost IS NOT NULL), 0) AS covered\n"
    "  FROM {frm}\n  {join}\n"
    " WHERE {alias}.factory_id = $1 AND {alias}.date >= $2 AND {alias}.date <= $3\n"
)


async def _coverage_ratio_of(conn, factory_id: str, date_range,
                             bridge) -> Optional[float]:
    """这段时间里, 有成本卡的营收占多少。

    ⛔ 算不出来时返回 `None`(= 不按覆盖率表达), **不返回 1.0** ——
       返回 1.0 等于说「全覆盖」, 那是拿一个猜测冒充读数, 且方向最危险
       (覆盖不足的租户会被说成全覆盖)。
    """
    if bridge is None:
        # 🔴 当场炸, ⛔ 不静默返回 None: 一个 ESTIMATED 的格子没有覆盖率, 限定语
        #    就只剩「按成本卡估算」——**不说这个估只覆盖了几成**。42% 覆盖率的
        #    租户会看到一个高得离谱的毛利配一句听起来已经解释过了的话。
        #    这是编程期的不一致(`provenance` 说要成本卡而 `uses_cost_bridge` 说不要),
        #    不是数据问题, 该让它响。
        raise AssertionError(
            "ESTIMATED 格子拿不到成本桥接 —— `_provenance_of` 与 "
            "`uses_cost_bridge` 判断不一致")
    names, keys = bridge
    frm, join, alias = GRAINS["item_cost"]
    sql = _COVERAGE_SQL_TEMPLATE.format(frm=frm, join=join, alias=alias)
    try:
        row = await conn.fetchrow(sql, factory_id, date_range[0], date_range[1],
                                  names, keys)
    except Exception:  # noqa: BLE001 — 覆盖率拿不到不该让整个格子失败
        logger.warning("[generic-executor] 覆盖率查询失败, 本格不带覆盖率",
                       exc_info=True)
        return None
    if row is None:
        return None
    total = float(row["total"] or 0)
    if not total:
        return None
    return prov_coverage_ratio(float(row["covered"] or 0), total)


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
