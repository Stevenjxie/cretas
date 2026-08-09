"""指标 / 维度 / 聚合的登记表 —— 通用执行器的全部输入。

🔴 为什么要有这个（2026-08-09 证据）：
   现在 20 个 resolver 是「一个业务问题一个函数」的粒度 ——
   `resolve_sales_summary`(营收×全店×汇总)、`resolve_store_margin`(营收×门店×排名)…
   每个函数内部**硬编**了取哪个指标、按什么分组、怎么排。于是 20 个函数 = 20 个格子，
   而「指标 × 维度 × 聚合」的组合有 200+ 种。

   问「本月全部门店客单价最高的店是哪家」答不出来，不是因为算不了 ——
   当天我手写 6 行 SQL 就跑通了。缺的是**没有人为这个组合写过一个函数**。

⛔ 这里登记的是**元素**，不是格子。你永远不写格子：
   登记 N 个指标 + M 个维度 + K 个聚合 → N×M×K 个格子自动成立。
   新增一个指标是**一行**，它立刻在所有维度 × 所有聚合上生效。

⛔ 派生指标不单独登记（Steve 2026-08-09 定的判据：新增前先问「它是不是现有元素
   的组合」）：客单价 = 营收 ÷ 单数，它是 `RATIO` 运算而不是新指标。不这么做，
   「客单价 / 人均消费 / 每单金额」会变成三行 —— 业务嘴里是三个词，数学上是同一个式子。
   597 个 Java 工具就是这句话没人问的结果。

⚠️ 每个指标必须声明 `requires`（它依赖哪些列）。列不在就**如实说缺**，
   ⛔ 绝不允许算出 0 冒充答案 —— 「你的平台抽佣是 ¥0」比「答不出来」危险得多。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Optional, Tuple


# ═══════════════════════════════════════════════════════════════════════════
# 粒度 —— 一个粒度 = 一条事实链的 FROM/JOIN
# ═══════════════════════════════════════════════════════════════════════════
#: grain → (FROM 子句, 该粒度天然带的 JOIN, 事实表别名)
#:
#: ⛔ 放在登记表里而不是执行器里: 它是**登记**(有哪些事实链), 不是**拼装逻辑**。
#:    第一版放在执行器里, 于是登记表要校验粒度合法性时反向 import, 循环了。
#:
#: ⛔ item_cost 的成本桥接: agg_restaurant_product_cost.product_id 全库为 0
#:    (2026-08-09 实测), 只能按 dim_restaurant_cost_product 的 normalized_name 桥。
#:    按 product_id 直连会静默得到 0 成本 → 毛利率 100%, 一个看起来很棒的错数。
GRAINS: Dict[str, Tuple[str, str, str]] = {
    "txn": ("fact_pos_transaction t", "", "t"),
    "item": (
        "fact_pos_transaction t",
        "JOIN fact_pos_item i ON i.transaction_id = t.id",
        "t",
    ),
    "item_cost": (
        "fact_pos_transaction t",
        "JOIN fact_pos_item i ON i.transaction_id = t.id "
        "LEFT JOIN dim_product dp ON dp.product_id = i.product_id "
        "AND dp.factory_id = i.factory_id "
        "LEFT JOIN dim_restaurant_cost_product b "
        "ON b.factory_id = i.factory_id "
        "AND b.normalized_name = dp.normalized_name "
        "LEFT JOIN agg_restaurant_product_cost c "
        "ON c.factory_id = b.factory_id "
        "AND c.product_source_pk = b.product_source_pk",
        "t",
    ),
    # 损耗链: 独立事实表, 与 POS 链不共表
    "wastage": ("fact_restaurant_wastage w", "", "w"),
}


# ═══════════════════════════════════════════════════════════════════════════
# 指标
# ═══════════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Metric:
    """一个可度量的量。

    `expr` 是**聚合表达式**，直接进 SELECT。别名由执行器统一加，登记时不写别名 ——
    写了两处就会不一致。

    `requires` 是它依赖的列（`表.列`）。执行器在拼 SQL 之前先核对，
    缺列就走「如实说缺」的出口，不进 SQL。

    `unit` 决定输出格式：money → ¥ 千分位；count → 整数；qty → 一位小数；
    pct → 百分号。⛔ 不在这里写中文措辞，那是叙述层的事。
    """
    key: str
    label: str
    #: 🔴 **按粒度**给表达式，不是一个表达式走天下。
    #:
    #: 2026-08-09 证伪实验当场抓到的缺陷：第一版只登记了一个
    #: `SUM(t.net_amount)`，按菜品分组时要 join 明细表，于是**每张订单的净额
    #: 被每条明细各算一遍** —— 米饭营收从真实的 ¥34,839 变成 ¥2,001,255（57 倍），
    #: 毛利率 99.5%。**SQL 跑得通、数字看着像那么回事、结论完全是错的。**
    #:
    #: ⛔ 所以「营收」在订单粒度是 `SUM(t.net_amount)`，在明细粒度是
    #:    `SUM(i.amount)` —— 同一个业务词，两个表达式。缺哪个粒度就**拒绝**
    #:    那个组合，⛔ 绝不拿另一个粒度的表达式硬凑。
    exprs: Dict[str, str]
    requires: Tuple[str, ...]
    unit: str = "count"
    #: 这个指标能按哪些维度分组。⛔ 不是所有指标配所有维度 ——
    #: 损耗表里没有收银员，订单表里没有食材。写错会拼出跑不通的 SQL。
    dimensions: Tuple[str, ...] = ()

    def expr_at(self, grain: str) -> Optional[str]:
        return self.exprs.get(grain)


#: 订单粒度可用的维度 —— 分组列都在 `fact_pos_transaction` 上（或它 join 的 dim_store）。
_TXN_DIMS = ("all", "store", "channel", "staff", "meal_period", "table",
             "date", "weekday", "hour", "city", "brand")
#: 明细粒度可用的维度 = 订单粒度全部 + 明细才有的两个。
#: ⚠️ 订单粒度的维度在明细粒度上**依然成立**（按门店分组、求明细数量之和），
#:    所以是超集不是另一套 —— 这一条第一版写反过，被自洽闸挡下。
_ITEM_DIMS = _TXN_DIMS + ("product", "category")
#: 损耗链是**另一张事实表**，维度不与 POS 链共用。
_WASTAGE_DIMS = ("all", "ingredient", "wastage_type", "wastage_reason")

#: ⚠️ 下面这些指标里，有几个的列**当前租户全是 NULL**（2026-08-09 实测 MOCK_REST：
#:    `tax_amount` / `actual_receive` / `table_no` / `wastage.reason` 填充率 0）。
#:    **它们照样登记** —— 这正是「所有格子都做出来，客户接上数据就点亮」的形态：
#:    列在 → requires 通过 → 算出来是 NULL → 叙述层显示「—」而不是编一个 0。
#:    列不在 → 如实说「还没接入」。两条出口都不撒谎，这是登记它们的前提。
METRICS: Dict[str, Metric] = {
    # ── 金额类（订单粒度） ────────────────────────────────────────────────
    "revenue": Metric(
        key="revenue", label="营收", unit="money",
        exprs={"txn": "SUM(t.net_amount)", "item": "SUM(i.amount)"},
        requires=("fact_pos_transaction.net_amount", "fact_pos_item.amount"),
        dimensions=_ITEM_DIMS,
    ),
    "gross_revenue": Metric(
        key="gross_revenue", label="折前营收", unit="money",
        exprs={"txn": "SUM(t.gross_amount)"},
        requires=("fact_pos_transaction.gross_amount",),
        dimensions=_TXN_DIMS,
    ),
    "discount_amount": Metric(
        key="discount_amount", label="折扣额", unit="money",
        exprs={"txn": "SUM(t.discount_amount)"},
        requires=("fact_pos_transaction.discount_amount",),
        dimensions=_TXN_DIMS,
    ),
    "tax_amount": Metric(
        key="tax_amount", label="税额", unit="money",
        exprs={"txn": "SUM(t.tax_amount)"},
        requires=("fact_pos_transaction.tax_amount",),
        dimensions=_TXN_DIMS,
    ),
    "actual_receive": Metric(
        key="actual_receive", label="实收", unit="money",
        exprs={"txn": "SUM(t.actual_receive)"},
        requires=("fact_pos_transaction.actual_receive",),
        dimensions=_TXN_DIMS,
    ),
    "platform_fee": Metric(
        key="platform_fee", label="平台抽佣", unit="money",
        # 🔴 数据 2026-08-09 起已按渠道写入，但回答层此前 **0 个消费点** ——
        #    「有数据没人用」和「没数据」在用户那里长得一模一样。登记它就是接上消费点。
        exprs={"txn": "SUM(t.platform_fee_amount)"},
        requires=("fact_pos_transaction.platform_fee_amount",),
        dimensions=_TXN_DIMS,
    ),
    # ── 计数类 ────────────────────────────────────────────────────────────
    "orders": Metric(
        key="orders", label="订单数", unit="count",
        # DISTINCT 让它在两个粒度上都成立 —— 明细粒度下同一订单只数一次。
        exprs={"txn": "COUNT(DISTINCT t.id)", "item": "COUNT(DISTINCT t.id)"},
        requires=("fact_pos_transaction.id",),
        dimensions=_ITEM_DIMS,
    ),
    "guests": Metric(
        key="guests", label="客流", unit="count",
        # ⛔ 只有订单粒度。明细粒度下同一张单的人数会被每条明细重复加 ——
        #    不给 item 表达式 = 按菜品维度问客流会被如实拒绝，而不是给个膨胀值。
        exprs={"txn": "SUM(t.customer_count)"},
        requires=("fact_pos_transaction.customer_count",),
        dimensions=_TXN_DIMS,
    ),
    "discount_orders": Metric(
        key="discount_orders", label="折扣单数", unit="count",
        exprs={"txn": "COUNT(DISTINCT t.id) FILTER (WHERE t.has_discount)",
               "item": "COUNT(DISTINCT t.id) FILTER (WHERE t.has_discount)"},
        requires=("fact_pos_transaction.has_discount",),
        dimensions=_ITEM_DIMS,
    ),
    # ── 数量类（明细粒度） ────────────────────────────────────────────────
    "sales_qty": Metric(
        key="sales_qty", label="销量", unit="qty",
        # ⛔ 只有明细粒度。订单粒度问「销量」没有意义(一张单卖了几份什么?),
        #    不给 txn 表达式 = 按订单维度问销量会被如实拒绝。
        exprs={"item": "SUM(i.qty)"},
        requires=("fact_pos_item.qty",),
        dimensions=_ITEM_DIMS,
    ),
    "return_qty": Metric(
        key="return_qty", label="退菜量", unit="qty",
        exprs={"item": "SUM(i.return_qty)"},
        requires=("fact_pos_item.return_qty",),
        dimensions=_ITEM_DIMS,
    ),
    # ── 成本类 ────────────────────────────────────────────────────────────
    "food_cost": Metric(
        key="food_cost", label="食材成本", unit="money",
        exprs={"item": "SUM(i.qty * COALESCE(c.food_cost, 0))"},
        requires=("fact_pos_item.qty", "agg_restaurant_product_cost.food_cost"),
        dimensions=_ITEM_DIMS,
    ),
    # ── 损耗链（另一张事实表） ────────────────────────────────────────────
    "wastage_cost": Metric(
        key="wastage_cost", label="损耗成本", unit="money",
        # ⚠️ 这条建在**另一张事实表**(fact_restaurant_wastage)上, 不是 POS 链。
        #    它证明了一件事: 「新增指标是一行」只在**同一张事实表**内成立;
        #    换表还要加一个 GRAINS 条目。
        #    ⛔ 别把这条当成反例就否掉方案 —— 事实表的数量是有限的(餐饮 6 张),
        #       而指标是几十个; 一张表登记一次, 之后该表上的指标才是一行一个。
        exprs={"wastage": "SUM(w.estimated_cost)"},
        requires=("fact_restaurant_wastage.estimated_cost",),
        dimensions=_WASTAGE_DIMS,
    ),
    "wastage_qty": Metric(
        key="wastage_qty", label="损耗量", unit="qty",
        exprs={"wastage": "SUM(w.quantity)"},
        requires=("fact_restaurant_wastage.quantity",),
        dimensions=_WASTAGE_DIMS,
    ),
}


# ═══════════════════════════════════════════════════════════════════════════
# 派生运算 —— 这是「不新增指标」的关键
# ═══════════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Derived:
    """由两个已登记指标算出来的量。

    🔑 登记 2 个运算（差值 / 比值）就覆盖了 9 个「看起来像新指标」的东西：
       客单价 = 营收 ÷ 订单数      人均消费 = 营收 ÷ 客数
       毛利   = 营收 − 成本        毛利率   = 毛利 ÷ 营收
       折扣率 = 折扣额 ÷ 毛额      抽佣率   = 抽佣 ÷ 营收 …
    """
    key: str
    label: str
    op: str          # "ratio" | "ratio_pct" | "diff" | "ratio_of_diff"
    left: str        # 指标 key
    right: str       # 指标 key
    unit: str = "count"


#: ⛔ 这 8 个在业务嘴里都是「指标」，数学上没有一个是新的取数 ——
#:    全部由已登记的基础指标经 差/比 两种运算得到。
#:    这就是防膨胀闸要拦的东西：「客单价 / 人均消费 / 每单金额」是三个词一个式子。
#: ⚠️ 派生量的**可用维度不用声明** —— 执行器取它两侧基础指标的粒度交集，
#:    交集为空就拒绝。例如「人均消费 = 营收 ÷ 客流」，客流只有订单粒度，
#:    于是按菜品问人均消费会被自动拒绝，不需要在这里重复维护一张维度表。
DERIVED: Dict[str, Derived] = {
    "avg_ticket": Derived("avg_ticket", "客单价", "ratio", "revenue", "orders", "money"),
    "avg_per_capita": Derived("avg_per_capita", "人均消费", "ratio",
                              "revenue", "guests", "money"),
    "gross_profit": Derived("gross_profit", "毛利", "diff", "revenue", "food_cost", "money"),
    "gross_margin": Derived("gross_margin", "毛利率", "ratio_of_diff",
                            "gross_profit", "revenue", "pct"),
    "discount_rate": Derived("discount_rate", "折扣率", "ratio_pct",
                             "discount_amount", "gross_revenue", "pct"),
    "platform_fee_rate": Derived("platform_fee_rate", "抽佣率", "ratio_pct",
                                 "platform_fee", "revenue", "pct"),
    "dishes_per_order": Derived("dishes_per_order", "单均出品数", "ratio",
                                "sales_qty", "orders", "qty"),
    "return_rate": Derived("return_rate", "退菜率", "ratio_pct",
                           "return_qty", "sales_qty", "pct"),
}


# ═══════════════════════════════════════════════════════════════════════════
# 维度
# ═══════════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Dimension:
    """按什么分组。

    `group_expr` 进 GROUP BY，`label_expr` 是展示名（通常来自 dim_ 表）。
    `all` 是特例：不分组，整体汇总。
    """
    key: str
    label: str
    group_expr: Optional[str]
    label_expr: Optional[str]
    join: str = ""
    #: 这个维度**最低**要求的粒度: "item" = 分组列在明细表上, 必须 join 明细;
    #: None = 没有要求(分组列在订单表上, 或者根本不分组)。
    #:
    #: 🔴 这个字段改了两次, 两次都是自洽闸当场抓住的设计错误:
    #:    ① 第一版写成「这个维度的粒度」并给「全店」也写了 txn ——
    #:       但「不分组」和「订单粒度」是两件事, 于是「全店销量」被误判成不成立。
    #:    ② 第二版把门店也当成 txn 粒度 —— 但「门店销量」完全有意义
    #:       (按门店分组、求明细数量之和), 分组列在 t 上、粒度却可以是 item。
    #:    ⇒ 维度该声明的是**最低要求**, 不是「粒度」。粒度是两边取更细的那个。
    min_grain: Optional[str] = None


DIMENSIONS: Dict[str, Dimension] = {
    "all": Dimension("all", "全店", None, None),
    "store": Dimension(
        "store", "门店", "t.store_id", "s.name",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    "product": Dimension(
        # 分组列在明细表上 → 必须拉到明细粒度
        "product", "菜品", "i.product_id", "p.name", min_grain="item",
        join="LEFT JOIN dim_product p ON p.product_id = i.product_id "
             "AND p.factory_id = i.factory_id",
    ),
    "channel": Dimension("channel", "渠道", "t.order_type", "t.order_type"),
    # ── 订单表上直接带的分组列 ────────────────────────────────────────────
    "staff": Dimension(
        "staff", "员工", "t.staff_id", "st.name",
        join="LEFT JOIN dim_staff st ON st.staff_id = t.staff_id "
             "AND st.factory_id = t.factory_id",
    ),
    "meal_period": Dimension("meal_period", "餐段", "t.meal_period", "t.meal_period"),
    # ⚠️ MOCK_REST 上 `table_no` 实测**全为 NULL**。照样登记 ——
    #    客户接上台位数据这个格子就点亮；在此之前叙述层显示「未填写」，
    #    ⛔ 不编一个 0 也不假装这个维度不存在。
    "table": Dimension("table", "台位", "t.table_no", "t.table_no"),
    # ── 时间维度：同一列的三种切法，各答一类问题 ──────────────────────────
    #    日期→「这个月每天怎么走」 星期→「周末比平日好多少」 时段→「哪个钟点最忙」
    "date": Dimension("date", "日期", "t.date", "t.date"),
    "weekday": Dimension(
        "weekday", "星期", "EXTRACT(ISODOW FROM t.date)",
        "CASE EXTRACT(ISODOW FROM t.date) WHEN 1 THEN '周一' WHEN 2 THEN '周二' "
        "WHEN 3 THEN '周三' WHEN 4 THEN '周四' WHEN 5 THEN '周五' "
        "WHEN 6 THEN '周六' ELSE '周日' END",
    ),
    "hour": Dimension(
        "hour", "时段", "EXTRACT(HOUR FROM t.time)",
        "EXTRACT(HOUR FROM t.time) || ':00'",
    ),
    # ── 门店属性：与「门店」同一张 dim 表，但答的是不同问题 ────────────────
    "city": Dimension(
        "city", "城市", "s.city", "s.city",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    "brand": Dimension(
        "brand", "品牌", "s.brand", "s.brand",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    # ── 菜品属性 ──────────────────────────────────────────────────────────
    "category": Dimension(
        "category", "菜品类别", "p.category", "p.category", min_grain="item",
        join="LEFT JOIN dim_product p ON p.product_id = i.product_id "
             "AND p.factory_id = i.factory_id",
    ),
    # ── 损耗链的三个维度 —— 它们只在 wastage 粒度上成立 ────────────────────
    "ingredient": Dimension(
        "ingredient", "食材", "w.ingredient_id", "ing.name", min_grain="wastage",
        join="LEFT JOIN dim_ingredient ing ON ing.ingredient_id = w.ingredient_id "
             "AND ing.factory_id = w.factory_id",
    ),
    "wastage_type": Dimension(
        "wastage_type", "损耗类型", "w.wastage_type", "w.wastage_type",
        min_grain="wastage",
    ),
    "wastage_reason": Dimension(
        "wastage_reason", "损耗原因", "w.reason", "w.reason", min_grain="wastage",
    ),
}


# ═══════════════════════════════════════════════════════════════════════════
# 聚合形态
# ═══════════════════════════════════════════════════════════════════════════
@dataclass(frozen=True)
class Aggregation:
    """结果怎么组织。

    ⚠️ `summary` 与 `all` 维度不是同一件事：
       summary × store = 「每家门店各是多少」（列出来，不排名）
       rank    × store = 「哪家门店最高」（排序 + 取前 N）
    """
    key: str
    label: str
    order: str = ""          # "" | "desc" | "asc"
    limit: Optional[int] = None
    needs_dimension: bool = False
    #: 按**值**排序还是按**维度自身**排序。趋势要的是「1 号到 31 号」，
    #: 不是「最高的那天排第一」—— 按值排会把时间序列打乱成排行榜。
    order_by: str = "value"  # "value" | "dim"
    #: 取回行之后的处理形态。⛔ 都是**纯行处理**，不改 SQL ——
    #: 改 SQL 会让每种形态各自有一套取数口径，那正是要避免的事。
    post: str = ""           # "" | "share" | "extremes" | "above_avg" | "concentration"


#: 🔑 9 种**形状**，不是 9 个参数变体。判据：每一种都答一类现实里真会问的问题，
#:    且换成另一种形状答不了。「前 5」和「前 10」不是两种形状（是同一形状换 limit），
#:    所以⛔ 没有 top10 这一条 —— 那是防膨胀闸要拦的东西。
AGGREGATIONS: Dict[str, Aggregation] = {
    # 「本月营收多少」
    "summary": Aggregation("summary", "汇总"),
    # 「哪家店最高」
    "rank": Aggregation("rank", "排名", order="desc", limit=5, needs_dimension=True),
    # 「哪道菜卖得最差」—— ⛔ 不是 rank 加个参数就完事：方向反了，
    #    「最差」的业务含义（要处理的问题）和「最好」（要复制的经验）是两回事。
    "bottom": Aggregation("bottom", "倒数", order="asc", limit=5, needs_dimension=True),
    # 「各渠道分别多少」—— 全部列出，不截断
    "compare": Aggregation("compare", "对比", order="desc", needs_dimension=True),
    # 「这个月每天怎么走的」—— 按维度自身顺序，⛔ 不按值排
    "trend": Aggregation("trend", "趋势", order="asc", order_by="dim",
                         needs_dimension=True),
    # 「外卖占多少」—— 附占比列
    "share": Aggregation("share", "占比", order="desc", needs_dimension=True,
                         post="share"),
    # 「最好和最差的分别是哪个」—— 只留两端
    "extremes": Aggregation("extremes", "两端", order="desc", needs_dimension=True,
                            post="extremes"),
    # 「哪些店高于平均」—— 阈值是**算出来的**，不是拍的
    "above_avg": Aggregation("above_avg", "高于平均", order="desc",
                             needs_dimension=True, post="above_avg"),
    # 「几道菜贡献了八成营收」—— 帕累托，累计到 80% 为止
    "concentration": Aggregation("concentration", "集中度", order="desc",
                                 needs_dimension=True, post="concentration"),
}


# ═══════════════════════════════════════════════════════════════════════════
# 自洽校验 —— 登记表自己不能先坏掉
# ═══════════════════════════════════════════════════════════════════════════
def assert_registry_self_consistent() -> None:
    """登记表内部矛盾在跑之前就该发现，不必等打库。

    ⛔ 这些错会拼出跑不通的 SQL 或**跑得通但算错**的 SQL，
       后者尤其危险 —— 它会给出一个看起来合理的数字。
    """
    for m in METRICS.values():
        assert m.dimensions, f"指标 {m.key} 没声明能按哪些维度分组"
        for d in m.dimensions:
            assert d in DIMENSIONS, f"指标 {m.key} 引用了未登记的维度 {d}"
        assert m.requires, f"指标 {m.key} 没声明依赖哪些列 —— 列缺时就无法如实说缺"
        assert m.exprs, f"指标 {m.key} 一个粒度的表达式都没有"
        # ⛔ 不在这里手写「合法粒度清单」—— 第一版写死了 ("txn","item"),
        #    加损耗链(wastage 粒度)时当场被自己挡住。手写清单会随事实表增长而过期,
        #    而**唯一权威是执行器的 _SOURCES**(那里定义了每个粒度怎么 FROM/JOIN)。
        #    判据: 判据里出现手写清单, 就问「这张表错了会怎样」。
        for g in m.exprs:
            assert g in GRAINS, (
                f"指标 {m.key} 用了未登记的粒度 {g} —— "
                f"要先在 GRAINS 里定义它怎么 FROM/JOIN")
        # ⛔ 承重: 指标声明能按某维度分组, 就必须有那个维度粒度上的表达式。
        #    少了就会退回另一个粒度的表达式 → 扇出 → 跑得通但算错。
        for dk in m.dimensions:
            need = DIMENSIONS[dk].min_grain
            if need is None:      # 分组列在订单表上或不分组 —— 不限制粒度
                continue
            assert need in m.exprs, (
                f"指标 {m.key} 声明可按「{DIMENSIONS[dk].label}」分组"
                f"(该维度要求 {need} 粒度), 却没有该粒度的表达式 —— 会扇出算错")
    for d in DERIVED.values():
        for side in (d.left, d.right):
            assert side in METRICS or side in DERIVED, (
                f"派生量 {d.key} 引用了未登记的 {side}")
        assert d.key not in METRICS, (
            f"{d.key} 同时登记成指标和派生量 —— 两处定义必然打架")
    for d in DIMENSIONS.values():
        # ⛔ 分组列和展示名要么都有要么都没有(「全店」是后者)。只有一个会拼出
        #    `GROUP BY` 少一列的 SQL —— PG 会直接报错, 但那是运行时才发现。
        assert (d.group_expr is None) == (d.label_expr is None), (
            f"维度 {d.key} 的分组列与展示名只声明了一个")
        if d.min_grain is not None:
            assert d.min_grain in GRAINS, (
                f"维度 {d.key} 要求了未登记的粒度 {d.min_grain}")
    for a in AGGREGATIONS.values():
        assert a.order in ("", "desc", "asc"), f"聚合 {a.key} 的排序方向非法"
        assert a.order_by in ("value", "dim"), f"聚合 {a.key} 的排序依据非法"
        assert a.post in ("", "share", "extremes", "above_avg", "concentration"), (
            f"聚合 {a.key} 的行处理形态 {a.post!r} 未登记 —— "
            f"执行器不认识它, 会静默不做处理然后把结果当成做过了")
        # ⛔ 承重: 按维度排序的形态必须有维度。没有维度时 dim_key 列不存在,
        #    ORDER BY dim_key 会炸 —— 而这是登记时就能发现的错。
        if a.order_by == "dim":
            assert a.needs_dimension, (
                f"聚合 {a.key} 按维度排序却不要求维度 —— 无维度时没有可排的列")
        # ⛔ 承重: 所有行处理形态都要先有确定的顺序, 否则「两端」「累计到 80%」
        #    取到的是数据库返回的**任意**顺序 —— 跑得通, 每次结果还不一样。
        if a.post:
            assert a.order, f"聚合 {a.key} 要做行处理却没声明排序 —— 结果不确定"


assert_registry_self_consistent()


def registry_size() -> Dict[str, int]:
    """登记条数与它们撑起的格子数 —— 这两个数的差距就是这个方案的全部理由。"""
    cells = 0
    for m in list(METRICS.values()):
        cells += len(m.dimensions) * len(AGGREGATIONS)
    for d in DERIVED.values():
        base = METRICS.get(d.left) or METRICS.get(d.right)
        if base is not None:
            cells += len(base.dimensions) * len(AGGREGATIONS)
    return {
        "metrics": len(METRICS),
        "derived": len(DERIVED),
        "dimensions": len(DIMENSIONS),
        "aggregations": len(AGGREGATIONS),
        "registrations": len(METRICS) + len(DERIVED) + len(DIMENSIONS) + len(AGGREGATIONS),
        "cells": cells,
    }
