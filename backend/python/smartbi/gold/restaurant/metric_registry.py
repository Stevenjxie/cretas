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
#: 指标的**大类**。用途只有一个：拒答时那句「我这儿有的是…」按类说，
#: 而不是把十几个指标名一口气念出来（§9.9 ③ 是能力边界，不是清单）。
#:
#: 🔴 owner 2026-08-12 裁定：类别必须是 **registry 上的字段**，不能是手写映射表。
#:    手写映射一旦落地，新登记的指标会悄悄落在所有类别之外、从「我这儿有的」
#:    里消失 —— **而消失是不报错的**。放在字段上，缺了就由
#:    `assert_registry_self_consistent` 当场红。
CATEGORIES: Tuple[str, ...] = ("营收和折扣", "成本和毛利", "损耗", "客流和销量")

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
    #: 大类, 见 `CATEGORIES`。⛔ 不许留空 —— 留空的指标会从「我这儿有的是」里
    #: 静默消失, 而消失是不报错的。由 `assert_registry_self_consistent` 兜住。
    category: str = ""
    #: 这个指标能按哪些维度分组。⛔ 不是所有指标配所有维度 ——
    #: 损耗表里没有收银员，订单表里没有食材。写错会拼出跑不通的 SQL。
    dimensions: Tuple[str, ...] = ()
    #: 🔴 给规划器看的一句话: 用户怎么问才算要这个指标。
    #: ⛔ 与 Dimension/Aggregation 的 `asks` 同一条纪律 —— prompt 由它渲染。
    asks: str = ""
    #: 见 `Derived.caveat` ——「这个数**是什么**」，与 provenance 的「准不准」是两件事。
    caveat: str = ""

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
        key="revenue", category="营收和折扣", label="营收", unit="money", asks="营业额/流水/收入/卖了多少钱",
        exprs={"txn": "SUM(t.net_amount)", "item": "SUM(i.amount)"},
        requires=("fact_pos_transaction.net_amount", "fact_pos_item.amount"),
        dimensions=_ITEM_DIMS,
    ),
    "gross_revenue": Metric(
        key="gross_revenue", category="营收和折扣", label="折前营收", unit="money", asks="打折前的原价总额",
        exprs={"txn": "SUM(t.gross_amount)"},
        requires=("fact_pos_transaction.gross_amount",),
        dimensions=_TXN_DIMS,
    ),
    "discount_amount": Metric(
        key="discount_amount", category="营收和折扣", label="折扣额", unit="money", asks="优惠/折扣一共减了多少钱",
        exprs={"txn": "SUM(t.discount_amount)"},
        requires=("fact_pos_transaction.discount_amount",),
        dimensions=_TXN_DIMS,
    ),
    "tax_amount": Metric(
        key="tax_amount", category="营收和折扣", label="税额", unit="money", asks="税金/税额",
        exprs={"txn": "SUM(t.tax_amount)"},
        requires=("fact_pos_transaction.tax_amount",),
        dimensions=_TXN_DIMS,
    ),
    "actual_receive": Metric(
        key="actual_receive", category="营收和折扣", label="实收", unit="money", asks="实际收到手的钱",
        exprs={"txn": "SUM(t.actual_receive)"},
        requires=("fact_pos_transaction.actual_receive",),
        dimensions=_TXN_DIMS,
    ),
    "platform_fee": Metric(
        key="platform_fee", category="营收和折扣", label="平台抽佣", unit="money", asks="外卖平台抽成/佣金/服务费",
        # 🔴 数据 2026-08-09 起已按渠道写入，但回答层此前 **0 个消费点** ——
        #    「有数据没人用」和「没数据」在用户那里长得一模一样。登记它就是接上消费点。
        exprs={"txn": "SUM(t.platform_fee_amount)"},
        requires=("fact_pos_transaction.platform_fee_amount",),
        dimensions=_TXN_DIMS,
    ),
    # ── 计数类 ────────────────────────────────────────────────────────────
    "orders": Metric(
        key="orders", category="客流和销量", label="订单数", unit="count", asks="单量/多少单/订单数",
        # DISTINCT 让它在两个粒度上都成立 —— 明细粒度下同一订单只数一次。
        exprs={"txn": "COUNT(DISTINCT t.id)", "item": "COUNT(DISTINCT t.id)"},
        requires=("fact_pos_transaction.id",),
        dimensions=_ITEM_DIMS,
    ),
    "guests": Metric(
        key="guests", category="客流和销量", label="客流", unit="count", asks="来了多少人/客流/人数/接待",
        # ⛔ 只有订单粒度。明细粒度下同一张单的人数会被每条明细重复加 ——
        #    不给 item 表达式 = 按菜品维度问客流会被如实拒绝，而不是给个膨胀值。
        exprs={"txn": "SUM(t.customer_count)"},
        requires=("fact_pos_transaction.customer_count",),
        dimensions=_TXN_DIMS,
    ),
    "discount_orders": Metric(
        key="discount_orders", category="营收和折扣", label="折扣单数", unit="count", asks="有多少单用了优惠",
        exprs={"txn": "COUNT(DISTINCT t.id) FILTER (WHERE t.has_discount)",
               "item": "COUNT(DISTINCT t.id) FILTER (WHERE t.has_discount)"},
        requires=("fact_pos_transaction.has_discount",),
        dimensions=_ITEM_DIMS,
    ),
    # ── 数量类（明细粒度） ────────────────────────────────────────────────
    "sales_qty": Metric(
        key="sales_qty", category="客流和销量", label="销量", unit="qty", asks="卖了多少份/销量/点了多少",
        # ⛔ 只有明细粒度。订单粒度问「销量」没有意义(一张单卖了几份什么?),
        #    不给 txn 表达式 = 按订单维度问销量会被如实拒绝。
        exprs={"item": "SUM(i.qty)"},
        requires=("fact_pos_item.qty",),
        dimensions=_ITEM_DIMS,
    ),
    "return_qty": Metric(
        key="return_qty", category="客流和销量", label="退菜量", unit="qty", asks="退菜/退了多少份",
        exprs={"item": "SUM(i.return_qty)"},
        requires=("fact_pos_item.return_qty",),
        dimensions=_ITEM_DIMS,
    ),
    # ── 成本类 ────────────────────────────────────────────────────────────
    "food_cost": Metric(
        key="food_cost", category="成本和毛利", label="食材成本", unit="money", asks="菜品成本/食材成本/配方成本",
        exprs={"item": "SUM(i.qty * COALESCE(c.food_cost, 0))"},
        requires=("fact_pos_item.qty", "agg_restaurant_product_cost.food_cost"),
        dimensions=_ITEM_DIMS,
    ),
    # ── 损耗链（另一张事实表） ────────────────────────────────────────────
    "wastage_cost": Metric(
        key="wastage_cost", category="损耗", label="损耗成本", unit="money", asks="损耗/报损/浪费花了多少钱",
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
        key="wastage_qty", category="损耗", label="损耗量", unit="qty", asks="损耗/报损了多少数量",
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
    #: 大类, 见 `CATEGORIES`。与 Metric 同一条纪律。
    category: str = ""
    #: 给规划器看的一句话。派生量在业务嘴里是独立指标, 必须能被指到。
    asks: str = ""
    #: 🔴 **这个数容易被读成别的东西**时, 在这里写一句人话说清它不是什么。
    #:
    #: 与 `provenance` 那条限定语是两件事, 缺一不可:
    #:   · provenance 说的是「这个数**准不准**」(估的还是账上的)
    #:   · caveat 说的是「这个数**是什么**」(毛利不是利润)
    #: 店长看到「今天全部门店毛利合计 ¥50 万」, 最可能的误读是「今天赚了 50 万」——
    #: 而毛利扣掉人工/房租/水电之后完全可能是亏的。只防「估算」防不住这个。
    #:
    #: ⛔ 不在叙述层手写 —— 手写的话新登记一个同类指标不会自动带上,
    #:    而漏掉**不报错**, 只是那个数从此可以被安全地误读。
    caveat: str = ""


#: ⛔ 这 8 个在业务嘴里都是「指标」，数学上没有一个是新的取数 ——
#:    全部由已登记的基础指标经 差/比 两种运算得到。
#:    这就是防膨胀闸要拦的东西：「客单价 / 人均消费 / 每单金额」是三个词一个式子。
#: ⚠️ 派生量的**可用维度不用声明** —— 执行器取它两侧基础指标的粒度交集，
#:    交集为空就拒绝。例如「人均消费 = 营收 ÷ 客流」，客流只有订单粒度，
#:    于是按菜品问人均消费会被自动拒绝，不需要在这里重复维护一张维度表。
DERIVED: Dict[str, Derived] = {
    "avg_ticket": Derived("avg_ticket", "客单价", "ratio", "revenue", "orders", "money",
                              category="客流和销量",
                              asks="客单价/每单平均消费"),
    "avg_per_capita": Derived("avg_per_capita", "人均消费", "ratio",
                              "revenue", "guests", "money",
                              category="客流和销量",
                              asks="人均消费/每人花多少"),
    "gross_profit": Derived("gross_profit", "毛利", "diff", "revenue", "food_cost", "money",
                                category="成本和毛利",
                              asks="毛利/赚了多少(金额)",
                              caveat="这是毛利，只扣了食材成本；人工、房租、水电"
                                     "都还没扣 —— 不等于今天赚了多少。"),
    "gross_margin": Derived("gross_margin", "毛利率", "ratio_of_diff",
                            "gross_profit", "revenue", "pct",
                            category="成本和毛利",
                              asks="毛利率/利润率(百分比)",
                              caveat="这是毛利率，只扣了食材成本；人工、房租、水电"
                                     "都还没扣 —— 不是利润率。"),
    "discount_rate": Derived("discount_rate", "折扣率", "ratio_pct",
                             "discount_amount", "gross_revenue", "pct",
                             category="营收和折扣",
                              asks="折扣率/优惠力度"),
    "platform_fee_rate": Derived("platform_fee_rate", "抽佣率", "ratio_pct",
                                 "platform_fee", "revenue", "pct",
                                 category="营收和折扣",
                              asks="抽佣率/平台抽成比例"),
    "dishes_per_order": Derived("dishes_per_order", "单均出品数", "ratio",
                                "sales_qty", "orders", "qty",
                                category="客流和销量",
                              asks="每单点几个菜/单均出品数"),
    "return_rate": Derived("return_rate", "退菜率", "ratio_pct",
                           "return_qty", "sales_qty", "pct",
                           category="客流和销量",
                              asks="退菜率"),
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
    #: 🔴 **给规划器看的一句话**: 什么样的问题该按这个维度分组。
    #: ⛔ 与 `Aggregation.asks` 同一条纪律: prompt 由它渲染, 空着 = 登记了一个
    #:    规划器永远指不到的维度。`assert_registry_self_consistent` 会红。
    asks: str = ""


DIMENSIONS: Dict[str, Dimension] = {
    "all": Dimension("all", "全店", None, None, asks="不分组, 要整体合计"),
    "store": Dimension(
        "store", "门店", "t.store_id", "s.name", asks="按门店/分店/哪家店分",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    "product": Dimension(
        # 分组列在明细表上 → 必须拉到明细粒度
        "product", "菜品", "i.product_id", "p.name", min_grain="item",
        asks="按菜品/菜/单品分",
        join="LEFT JOIN dim_product p ON p.product_id = i.product_id "
             "AND p.factory_id = i.factory_id",
    ),
    "channel": Dimension("channel", "渠道", "t.order_type", "t.order_type",
                         asks="按渠道分: 堂食/外卖/团购"),
    # ── 订单表上直接带的分组列 ────────────────────────────────────────────
    "staff": Dimension(
        "staff", "员工", "t.staff_id", "st.name", asks="按**某个具体员工个人**分; ⛔ 问「各岗位/工种/职位」时不要用它 —— 系统没有岗位维度, 这时 dimensions 留空",
        join="LEFT JOIN dim_staff st ON st.staff_id = t.staff_id "
             "AND st.factory_id = t.factory_id",
    ),
    "meal_period": Dimension("meal_period", "餐段", "t.meal_period", "t.meal_period",
                             asks="按餐段分: 午市/晚市/夜宵"),
    # ⚠️ MOCK_REST 上 `table_no` 实测**全为 NULL**。照样登记 ——
    #    客户接上台位数据这个格子就点亮；在此之前叙述层显示「未填写」，
    #    ⛔ 不编一个 0 也不假装这个维度不存在。
    "table": Dimension("table", "台位", "t.table_no", "t.table_no",
                       asks="按台位/桌号分"),
    # ── 时间维度：同一列的三种切法，各答一类问题 ──────────────────────────
    #    日期→「这个月每天怎么走」 星期→「周末比平日好多少」 时段→「哪个钟点最忙」
    "date": Dimension("date", "日期", "t.date", "t.date",
                      asks="按天分, 看每天的情况"),
    "weekday": Dimension(
        "weekday", "星期", "EXTRACT(ISODOW FROM t.date)",
        "CASE EXTRACT(ISODOW FROM t.date) WHEN 1 THEN '周一' WHEN 2 THEN '周二' "
        "WHEN 3 THEN '周三' WHEN 4 THEN '周四' WHEN 5 THEN '周五' "
        "WHEN 6 THEN '周六' ELSE '周日' END",
        asks="按星期几分, 比周末和平日",
    ),
    "hour": Dimension(
        "hour", "时段", "EXTRACT(HOUR FROM t.time)",
        "EXTRACT(HOUR FROM t.time) || ':00'",
        asks="按**小时**分(几点钟, 如 12:00/18:00); ⛔ 问餐段(午市/晚市)用 meal_period",
    ),
    # ── 门店属性：与「门店」同一张 dim 表，但答的是不同问题 ────────────────
    "city": Dimension(
        "city", "城市", "s.city", "s.city", asks="按城市分",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    "brand": Dimension(
        "brand", "品牌", "s.brand", "s.brand", asks="按品牌分",
        join="LEFT JOIN dim_store s ON s.store_id = t.store_id "
             "AND s.factory_id = t.factory_id",
    ),
    # ── 菜品属性 ──────────────────────────────────────────────────────────
    "category": Dimension(
        "category", "菜品类别", "p.category", "p.category", min_grain="item",
        asks="按菜品类别分: 热菜/凉菜/主食/饮品",
        join="LEFT JOIN dim_product p ON p.product_id = i.product_id "
             "AND p.factory_id = i.factory_id",
    ),
    # ── 损耗链的三个维度 —— 它们只在 wastage 粒度上成立 ────────────────────
    "ingredient": Dimension(
        "ingredient", "食材", "w.ingredient_id", "ing.name", min_grain="wastage",
        asks="按食材/原料分",
        join="LEFT JOIN dim_ingredient ing ON ing.ingredient_id = w.ingredient_id "
             "AND ing.factory_id = w.factory_id",
    ),
    "wastage_type": Dimension(
        "wastage_type", "损耗类型", "w.wastage_type", "w.wastage_type",
        min_grain="wastage", asks="按损耗类型分",
    ),
    "wastage_reason": Dimension(
        "wastage_reason", "损耗原因", "w.reason", "w.reason", min_grain="wastage",
        asks="按损耗原因分",
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
    #: 🔴 **给规划器看的一句话**：什么样的问题该选这个形态。
    #:
    #: ⛔ 存在的唯一理由是「prompt 从登记表渲染」。2026-08-09 实测：执行侧登记了
    #:    3168 个格子，而规划器穷举所有输出只能到达 147 个（4%）—— 因为它的可选
    #:    值是一张**手写在 prompt 里**的封闭清单。把说明写在这里，prompt 由它渲染，
    #:    登记表加一行规划器当场就能指到，永远不用再改 prompt。
    #: ⛔ 不许在 prompt 里另写一份 —— 那就是第四张手写表，同一个病换个位置。
    #:    `test_prompt_renders_every_registered_aggregation` 会因此变红。
    asks: str = ""


#: 🔑 9 种**形状**，不是 9 个参数变体。判据：每一种都答一类现实里真会问的问题，
#:    且换成另一种形状答不了。「前 5」和「前 10」不是两种形状（是同一形状换 limit），
#:    所以⛔ 没有 top10 这一条 —— 那是防膨胀闸要拦的东西。
AGGREGATIONS: Dict[str, Aggregation] = {
    "summary": Aggregation(
        "summary", "汇总",
        asks="要一个总数或各项分别是多少，没有比较或排序的意思"),
    "rank": Aggregation(
        "rank", "排名", order="desc", limit=5, needs_dimension=True,
        asks="问最高/最好/最多/前几名"),
    # ⛔ 不是 rank 加个参数就完事：方向反了，「最差」的业务含义（要处理的问题）
    #    和「最好」（要复制的经验）是两回事。
    "bottom": Aggregation(
        "bottom", "倒数", order="asc", limit=5, needs_dimension=True,
        asks="问最低/最差/最少/卖得不好的"),
    "compare": Aggregation(
        "compare", "对比", order="desc", needs_dimension=True,
        asks="问各项之间对比如何，要看全部不要截断"),
    "trend": Aggregation(
        "trend", "趋势", order="asc", order_by="dim", needs_dimension=True,
        asks="问走势/变化/怎么走的，要按时间先后看"),
    "share": Aggregation(
        "share", "占比", order="desc", needs_dimension=True, post="share",
        asks="问占比/占多少/比重/构成"),
    "extremes": Aggregation(
        "extremes", "两端", order="desc", needs_dimension=True, post="extremes",
        asks="一句话里同时要最高和最低"),
    # 阈值是**算出来的**，不是拍的
    "above_avg": Aggregation(
        "above_avg", "高于平均", order="desc", needs_dimension=True, post="above_avg",
        asks="问哪些高于平均/超过平均水平"),
    # 帕累托，累计到 80% 为止
    "concentration": Aggregation(
        "concentration", "集中度", order="desc", needs_dimension=True,
        post="concentration",
        asks="问集中度/几个贡献了大部分/二八分布"),
}


# ═══════════════════════════════════════════════════════════════════════════
# 自洽校验 —— 登记表自己不能先坏掉
# ═══════════════════════════════════════════════════════════════════════════
#: 「表.列」的**人话名** —— T2 补数据开价要说「补 {什么}, 能算出 {什么}」,
#: 前半句就取自这里。
#:
#: 🔴 为什么放在 registry 上而不是写在开价那段代码里(设计卡明令):
#:    与 `category` 同一条判据 —— 手写映射一旦落在工具里, **新登记的列会悄悄
#:    落在表外而不报错**, 于是开价时那一句变成「补 fact_pos_transaction.tax_amount」
#:    直接把列名怼到店长脸上, 或者干脆跳过不说。
#:    放在这里 + 下面那道断言, 缺了当场红。
#: ⛔ 措辞是**店长听得懂的话**, 不是字段名的翻译。「实收金额」不是「actual_receive」。
COLUMN_LABELS: Dict[str, str] = {
    "fact_pos_transaction.net_amount": "订单净额(POS 实收流水)",
    "fact_pos_transaction.gross_amount": "订单原价金额",
    "fact_pos_transaction.actual_receive": "实际到账金额",
    "fact_pos_transaction.discount_amount": "折扣金额",
    "fact_pos_transaction.has_discount": "这单有没有打折",
    "fact_pos_transaction.platform_fee_amount": "平台抽佣金额",
    "fact_pos_transaction.tax_amount": "税额",
    "fact_pos_transaction.customer_count": "就餐人数",
    "fact_pos_transaction.id": "订单号",
    "fact_pos_item.amount": "每道菜的销售额",
    "fact_pos_item.qty": "每道菜的销量",
    "fact_pos_item.return_qty": "退菜数量",
    "agg_restaurant_product_cost.food_cost": "每道菜的食材成本(来自成本卡/配方)",
    "fact_restaurant_wastage.quantity": "损耗数量",
    "fact_restaurant_wastage.estimated_cost": "损耗金额",
}


def assert_registry_self_consistent() -> None:
    """登记表内部矛盾在跑之前就该发现，不必等打库。

    ⛔ 这些错会拼出跑不通的 SQL 或**跑得通但算错**的 SQL，
       后者尤其危险 —— 它会给出一个看起来合理的数字。
    """
    # ── 会被读成「利润」的指标必须自己说清它不是 ──────────────────────────
    #
    # 🔴 判据不是手写名单, 是**登记表自己的声明**: 一个指标的 `asks` 里如果承认
    #    用户会用「利润 / 赚了多少」来问它, 那它就必然会被那样读回去。
    #    `gross_profit.asks = "毛利/赚了多少(金额)"` —— 登记表早就写着了。
    #
    # ⛔ 手写名单的坏法是确定的: 新登记一个同类指标不会自动进名单,
    #    而漏掉**不报错**, 只是那个数从此可以被安全地误读。
    _MISREAD_AS_PROFIT = ("利润", "赚了多少")
    for key, entry in list(METRICS.items()) + list(DERIVED.items()):
        asks = getattr(entry, "asks", "") or ""
        if any(w in asks for w in _MISREAD_AS_PROFIT):
            assert (getattr(entry, "caveat", "") or "").strip(), (
                f"{key} 的 asks 里承认用户会用「利润/赚了多少」问它"
                f"({asks!r}), 却没有 caveat —— 店长会把它读成利润。"
                f"人工/房租/水电都没扣, 毛利再高也可能是亏的。"
            )

    # ── 大类: 每一个已登记的指标都必须有, 且必须是 CATEGORIES 里的 ──────────
    #
    # 🔴 owner 2026-08-12 裁定的落地点。拒答时那句「我这儿有的是…」按类说,
    #    类别取自这个字段而**不是一张手写映射表** —— 手写映射一旦落地,
    #    新登记的指标会悄悄落在所有类别之外、从「我这儿有的」里**消失**,
    #    而消失是不报错的。放在字段上 + 这道断言, 缺了当场红。
    # ⛔ 连 DERIVED 一起查: 客单价/毛利率这些在业务嘴里就是独立指标,
    #    漏了它们等于漏了一半。
    for entry in list(METRICS.values()) + list(DERIVED.values()):
        assert entry.category, (
            f"指标 {entry.key} 没写 `category` —— 它会从拒答时那句"
            f"「我这儿有的是…」里静默消失(消失是不报错的)")
        assert entry.category in CATEGORIES, (
            f"指标 {entry.key} 的 category={entry.category!r} 不在 CATEGORIES 里 —— "
            f"自造一个类别等于给自己开了一个没人渲染的桶")

    # ── 列的人话名: 每一个被 `requires` 引用的列都必须有 ──────────────────
    #
    # 🔴 与上面 `category` 那段同一条判据。T2 补数据开价要说「补 {什么}」,
    #    没有人话名就只能把 `fact_pos_transaction.tax_amount` 怼到店长脸上,
    #    或者静默跳过那一句 —— 后者更糟, 因为缺口消失了而没人知道。
    # ⛔ 反向也查: 登记了却没有任何指标依赖的列说明它已经过期, 留着会让人
    #    以为「补它有用」。
    required_columns = {c for m in METRICS.values() for c in m.requires}
    for column in sorted(required_columns):
        assert column in COLUMN_LABELS, (
            f"列 {column} 被 requires 引用却没有人话名 —— "
            f"T2 开价时只能把库表列名怼到店长脸上, 或者静默跳过那句开价")
        assert COLUMN_LABELS[column].strip(), f"列 {column} 的人话名是空的"
    for column in sorted(COLUMN_LABELS):
        assert column in required_columns, (
            f"列 {column} 有人话名却没有任何指标依赖它 —— "
            f"过期的登记会让开价说「补它有用」, 而补了什么也解锁不了")

    for m in METRICS.values():
        assert m.dimensions, f"指标 {m.key} 没声明能按哪些维度分组"
        for d in m.dimensions:
            assert d in DIMENSIONS, f"指标 {m.key} 引用了未登记的维度 {d}"
        assert m.asks, (
            f"指标 {m.key} 没写 `asks`(用户怎么问才算要它) —— "
            f"规划器 prompt 由它渲染, 空着等于登记了一个永远指不到的指标")
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
        assert d.asks, f"派生量 {d.key} 没写 `asks` —— 规划器指不到它"
        assert d.key not in METRICS, (
            f"{d.key} 同时登记成指标和派生量 —— 两处定义必然打架")
    for d in DIMENSIONS.values():
        # ⛔ 分组列和展示名要么都有要么都没有(「全店」是后者)。只有一个会拼出
        #    `GROUP BY` 少一列的 SQL —— PG 会直接报错, 但那是运行时才发现。
        assert (d.group_expr is None) == (d.label_expr is None), (
            f"维度 {d.key} 的分组列与展示名只声明了一个")
        assert d.asks, (
            f"维度 {d.key} 没写 `asks`(什么样的问题该按它分组) —— "
            f"规划器 prompt 由它渲染, 空着等于登记了一个永远指不到的维度")
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
        # ⛔ 承重: 没写 `asks` 的聚合, prompt 渲染出来规划器**看不懂什么时候用它**,
        #    于是它永远不会被选中 —— 一个登记了却指不到的格子。
        #    这正是 2026-08-09 查出的「3168 个格子只有 147 个够得着」的成因。
        assert a.asks, (
            f"聚合 {a.key} 没写 `asks`(什么样的问题该选它) —— "
            f"规划器 prompt 由它渲染, 空着等于登记了一个永远选不中的形态")


assert_registry_self_consistent()


def render_aggregation_vocabulary() -> str:
    """给规划器 prompt 用的聚合可选值 —— **唯一来源是本登记表**。

    🔴 这个函数是「根治」的全部要点：规划器的可选值不该手写在 prompt 里。
       2026-08-09 实测，手写的后果是执行侧 3168 个格子里只有 147 个（4%）
       够得着 —— 造好了、验过了、用不上，而且**任何现有的闸都不会因此变红**。

    ⛔ 在 prompt 里另写一份聚合清单 = 第四张手写表，同一个病换个位置。
       `test_prompt_renders_every_registered_aggregation` 会因此变红。
    """
    parts = [f"{a.key}({a.label}, {a.asks})" for a in AGGREGATIONS.values()]
    return "、".join(parts)


#: 登记表的键 → **管线内部**使用的维度名。
#:
#: 🔴 方向是这样定的, 不是反过来: 管线里有 **8 处以上**消费者直接写着
#:    `"dish" in dimensions` / `"time" in dimensions`(契约要素、门店×菜品组合、
#:    时间对比剥离、resolver 能力表、已晋升的整句路由、计划缓存……)。
#:    登记表是后来的, 让**新写法归一到旧写法**只动一处; 反过来要改 8 处,
#:    而漏掉任何一处的症状是「那条路径悄悄按旧写法比对, 比不上就拒答」——
#:    2026-08-09 实测正是如此:「本月米饭的销量」被判成「查询维度超出能力范围」。
#: ⚠️ 新增的维度(staff/weekday/hour/…)没有旧写法, **原样通过**。
#: ⛔ prompt 里只给一套写法(登记表的键)。告诉模型「两种都接受」的后果是
#:    它**两个都写** —— 实测 `dimensions=('product','dish')`, 直接击穿
#:    resolver 能力的子集判断。给两套写法 = 邀请模型输出两套。
_REGISTRY_TO_PIPELINE_DIMENSION: Dict[str, str] = {
    "product": "dish",
    "date": "time",
}


def non_grouping_dimensions() -> frozenset:
    """**不分组**的那些维度键 —— 从登记表推导, ⛔ 不手写。

    `Dimension.group_expr is None` 就是「它不产生 GROUP BY」。今天只有 `all`,
    但判据是那个字段, 不是那个名字。
    """
    return frozenset(k for k, d in DIMENSIONS.items() if d.group_expr is None)


def grouping_dimensions(names) -> Tuple[str, ...]:
    """只保留**真正会分组**的维度。

    ## 🔴 为什么需要它(2026-08-13)

    `_RESOLVER_DIMENSIONS` 那张表列的是每个 resolver **能按什么分组**。
    拿「不分组」去查那张表是**范畴错误**: `all` 不在任何集合里, **也不可能在**,
    于是 `{'all'} ⊆ {'store'}` 恒不成立 —— **任何「全店合计」问句都被拒**,
    与指标无关、与 resolver 无关。

    prod 实测: 「今天赚多少」「今天营业额多少」「今天多少单」三题同一个形状被拒,
    而日结推送用同一批数字答得好好的。

    ⛔ **同一个比较在三处出现过**(执行前校验 / 契约修复 / 回执 scope 判定)。
       第一次只修了一处, 于是拒答只是从一道闸挪到了下一道 —— 实测到过。
       所以这个「减掉不分组维度」的动作收敛成**这一个函数**, 三处都调它。
    """
    skip = non_grouping_dimensions()
    return tuple(n for n in (names or ()) if n not in skip)


def canonical_dimensions(names) -> Tuple[str, ...]:
    """把维度名归一到**管线内部**的写法, 去重且保序。

    ⛔ 认不出的原样保留, 不丢弃 —— 丢弃会让「customer」这种没登记的维度悄悄
       消失, 而它本该让下游如实说「这项分析做不了」。
    """
    out: list = []
    for n in names or ():
        k = _REGISTRY_TO_PIPELINE_DIMENSION.get(n, n)
        if k not in out:
            out.append(k)
    return tuple(out)


#: 登记表的键 → **管线内部**使用的指标名。方向与 `_REGISTRY_TO_PIPELINE_DIMENSION`
#: 一致, 理由也一致: 管线里 `sales_volume`/`recipe_cost`/`wastage` 有大量消费者
#: (关键词编译表、契约要素表、resolver 选择、已晋升路由、计划缓存)。
#: ⚠️ 新指标(guests/platform_fee/avg_ticket/…)没有旧名, **原样通过**。
_REGISTRY_TO_PIPELINE_METRIC: Dict[str, str] = {
    "sales_qty": "sales_volume",
    "food_cost": "recipe_cost",
    "wastage_cost": "wastage",
}


def canonical_metrics(names) -> Tuple[str, ...]:
    """把指标名归一到**管线内部**的写法, 去重且保序。

    ⛔ 认不出的原样保留 —— 数据缺口项(net_profit/table_turnover/staffing…)
       正是靠它们原样穿过去, 让下游如实说「这项没有数据」。
    """
    out: list = []
    for n in names or ():
        k = _REGISTRY_TO_PIPELINE_METRIC.get(n, n)
        if k not in out:
            out.append(k)
    return tuple(out)


def render_metric_vocabulary() -> str:
    """给规划器 prompt 用的指标可选值 —— **唯一来源是本登记表**。

    ⛔ 数据缺口项(净利润/翻台率/人效/盘点差异/顾客评价…)**不在这里** ——
       它们没有登记, 本来就该走「如实说没有」。把它们塞进 prompt 等于
       让规划器承诺一个系统给不出的东西。
    """
    parts = [f"{m.key}({m.label}, {m.asks})" for m in METRICS.values()]
    parts += [f"{d.key}({d.label}, {d.asks})" for d in DERIVED.values()]
    return "、".join(parts)


def render_dimension_vocabulary() -> str:
    """给规划器 prompt 用的维度可选值 —— **唯一来源是本登记表**。

    与 `render_aggregation_vocabulary` 同一条纪律。2026-08-09 实测: 手写的
    6 个维度对 16 个已登记维度, 10 个规划器永远指不到。
    """
    return "、".join(f"{d.key}({d.label}, {d.asks})" for d in DIMENSIONS.values())


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
