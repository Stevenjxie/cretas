"""分组层的毛利必须与抬头**同一个口径** —— 各组加起来 = 抬头（到分）。

## 🔴 为什么有这个文件（owner 2026-08-14 裁定一，阻塞项）

`_needs_split_execution` 原来只在**合计层**做口径修正，分组层走普通 SQL：

    SUM(i.amount) - SUM(i.qty * c.food_cost)

`i.amount` 是**明细原价**。于是同一租户、同一范围，抬头一个数、
「按品牌看毛利」另一个数。prod 实测（MOCK_REST 只有一个品牌，
所以那一格必须等于抬头）：

    抬头（摊了折扣）      ¥475,623.83
    按品牌（没摊）        ¥506,749.42      差 ¥31,125.59 = 折扣额，高估 6.5%

而「按品牌看毛利」是**用户打一句话就能到**的问题，不需要按钮。

## ⚠️ 夹具为什么是**逐行**的

假 conn 持有一张明细行表，像 SQL 那样自己聚合。
⛔ 不手填 `covered_gross=…` 这种聚合值 —— 手填等于把「两层应该相等」这个
   结论抄进夹具，断言就恒真了。逐行聚合时，只要两层的过滤条件或摊派比率
   有一处不同，和就对不上。

## ⚠️ 「各组加起来 = 抬头」只对**不截断**的形态成立

`rank` / `bottom` 登记了 `limit=5`，它们**按设计**只给前 5 名 ——
那时候和不等于抬头，不是缺陷。所以断言跑在 `compare` 上（无 limit）。
"""
from __future__ import annotations

import asyncio
from decimal import Decimal

import pytest

from smartbi.gold.restaurant import generic_executor as ge

FACTORY = "T_ALLOC"
_DAY = (__import__("datetime").date(2026, 8, 12),
        __import__("datetime").date(2026, 8, 12))

#: 明细行: (菜, 品牌, 明细原价, 份数, 卡上单位成本)。`None` = 没有成本卡。
#: 🔑 刻意造齐四种行, 每一种都能让某一层算错:
#:    ① 有卡的普通菜        —— 主体
#:    ② 没卡的菜(娃娃菜)     —— 分组层若把它算进营收, 和就大于抬头
#:    ③ 单位错的卡(米饭)     —— 两层若排除名单不同, 和就对不上
#:    ④ 第二个品牌          —— 只有一个品牌时「和 = 抬头」太容易蒙对
_LINES = [
    # (dish,      brand,     amount,      qty,     unit_cost)
    ("罗氏虾",    "模拟餐饮", "183040.00", "3704",  "49.43"),
    ("酸菜鱼",    "模拟餐饮", "220000.00", "5500",  "18.20"),
    ("娃娃菜",    "模拟餐饮", " 29656.00", "1160",  None),      # ② 没卡
    ("米饭",      "模拟餐饮", "  4050.00", "5000",  "81.00"),   # ③ 单位错 ×100
    ("干锅牛蛙",  "青花椒",   "160000.00", "2000",  "37.10"),
    ("凉拌木耳",  "青花椒",   " 26244.00", " 900",  None),      # ② 没卡
]

#: 交易实收。**小于**明细原价合计 ⇒ 确实有折扣可摊(否则这一版改的东西不生效,
#: 断言在「根本没有折扣」时两边天然相等 —— 那就是个恒真式)。
_PAID = Decimal("591873.41")


def _rows():
    for dish, brand, amount, qty, cost in _LINES:
        yield (dish, brand, Decimal(amount.strip()), Decimal(qty.strip()),
               None if cost is None else Decimal(cost))


class _FakeConn:
    """按行聚合的假 conn —— 复刻 SQL 的 FILTER 语义。

    ⚠️ `SUM(qty * food_cost)` 里 `food_cost IS NULL` 的行贡献 NULL,
       SUM 跳过它 —— 所以没卡的菜**不进成本**, 但**进 all_gross**。
       这正是分组层原来算错的地方, 夹具必须如实复刻。
    """

    def __init__(self, paid: Decimal = _PAID):
        self.paid = paid
        self.seen_sql = []

    @staticmethod
    def _agg(lines, excluded):
        covered_gross = sum((a for _d, _b, a, _q, c in lines
                             if c is not None and _d not in excluded),
                            Decimal(0))
        all_gross = sum((a for _d, _b, a, _q, _c in lines), Decimal(0))
        covered_cost = sum((q * c for _d, _b, _a, q, c in lines
                            if c is not None and _d not in excluded),
                           Decimal(0))
        return covered_gross, all_gross, covered_cost

    async def fetch(self, sql, *args):
        self.seen_sql.append(sql)
        lines = list(_rows())
        if "GROUP BY 1" in sql:                       # _DISH_COST_FACTS_SQL
            by_dish = {}
            for dish, _b, amount, qty, cost in lines:
                cur = by_dish.setdefault(
                    dish, {"name": dish, "qty": Decimal(0),
                           "revenue": Decimal(0), "unit_cost": cost})
                cur["qty"] += qty
                cur["revenue"] += amount
            return list(by_dish.values())
        if "AS dim_key" in sql:                       # 分组覆盖
            excluded = set(args[5] or ())
            # ⚠️ 按 SQL 里真实的 group_expr 分组, ⛔ 不管请求的是什么维度都按品牌
            #    分 —— 那样「截断」那条判据永远造不出 >5 组, 断言就空转了。
            idx = 1 if "s.brand" in sql else 0        # 1=品牌 0=菜名
            out = []
            for key in dict.fromkeys(ln[idx] for ln in lines):
                grp = [ln for ln in lines if ln[idx] == key]
                cg, ag, cc = self._agg(grp, excluded)
                out.append({"dim_key": key, "dim_label": key,
                            "covered_gross": cg, "all_gross": ag,
                            "covered_cost": cc})
            return out
        raise AssertionError(f"夹具没预料到的 SQL:\n{sql}")

    async def fetchrow(self, sql, *args):
        self.seen_sql.append(sql)
        excluded = set(args[5] or ())
        cg, ag, cc = self._agg(list(_rows()), excluded)
        return {"covered_gross": cg, "all_gross": ag, "covered_cost": cc}

    async def fetchval(self, sql, *args):
        assert "net_amount" in sql, f"实收不该从这条 SQL 取: {sql}"
        return self.paid


@pytest.fixture(autouse=True)
def _bridge(monkeypatch):
    """桥接固定住 —— 这个文件测的是摊派, 不是菜名解析。

    ⚠️ 打的是**消费方模块**的属性: `generic_executor` 是
       `from ...restaurant_cost_mapping import cost_bridge_pairs` 拿的它,
       打来源模块够不着(本仓有一道闸专门管这件事)。
    """
    async def _pairs(conn, factory_id):
        names = [d for d, *_ in _LINES]
        return names, [f"pk_{i}" for i, _ in enumerate(names)]
    monkeypatch.setattr(ge, "cost_bridge_pairs", _pairs)


async def _cell(dimension_key: str, aggregation_key: str, conn=None):
    return await ge.execute_cell(
        conn or _FakeConn(), factory_id=FACTORY, metric_key="gross_profit",
        dimension_key=dimension_key, aggregation_key=aggregation_key,
        date_range=_DAY, available_columns=set())


def _run(coro):
    return asyncio.get_event_loop_policy().new_event_loop().run_until_complete(coro)


def _cents(value) -> Decimal:
    return Decimal(str(value)).quantize(Decimal("0.01"))


def _headline() -> Decimal:
    cell = _run(_cell("all", "summary"))
    assert cell.rows, f"抬头没算出来: missing={cell.missing_columns} sql={cell.sql}"
    return _cents(cell.rows[0]["gross_profit"])


def _groups(dimension_key="brand", aggregation_key="compare"):
    cell = _run(_cell(dimension_key, aggregation_key))
    assert cell.rows, f"分组没算出来: missing={cell.missing_columns} sql={cell.sql}"
    return cell


# ── 判据一 ────────────────────────────────────────────────────────────────
def test_groups_sum_to_the_headline_to_the_cent():
    """🔴 各组加起来 = 抬头（**到分**，不是「差得不多」）。"""
    head = _headline()
    cell = _groups()
    total = _cents(sum(Decimal(str(r["gross_profit"])) for r in cell.rows))
    assert total == head, (
        f"分组层与抬头口径不同: 各组合计 {total} vs 抬头 {head}, "
        f"差 {total - head}\n各组: "
        + ", ".join(f"{r['dim_label']}={_cents(r['gross_profit'])}"
                    for r in cell.rows))


def test_the_fixture_actually_has_a_discount_to_allocate():
    """阳性对照: 夹具里**确实有折扣**。

    ⛔ 没有这条, 上面那条在「实收 = 原价」时天然成立 —— 那时候摊不摊都一样,
       断言测不到任何东西。
    """
    all_gross = sum(a for _d, _b, a, _q, _c in _rows())
    assert _PAID < all_gross, "夹具没有折扣, 上面那条断言是恒真式"
    assert (all_gross - _PAID) / all_gross > Decimal("0.03"), \
        "折扣太小, 四舍五入就能盖住 —— 抓不到「没摊」这个缺陷"


def test_the_fixture_has_uncovered_dishes_and_an_excluded_card():
    """阳性对照: 夹具里既有**没卡的菜**也有**单位错的卡**。

    这两样分别对应分组层原来的另外两个错法(把没卡的菜算进营收 / 排除名单
    与抬头不同)。⛔ 少了它们, 「和 = 抬头」只测到了折扣这一件。
    """
    assert any(c is None for *_x, c in _rows()), "没有「没卡」的菜"
    cell = _groups()
    assert cell.cost_outliers, "夹具里那张单位错的卡没有被判出来"


# ── 判据二 ────────────────────────────────────────────────────────────────
def test_single_brand_tenant_that_one_cell_equals_the_headline(monkeypatch):
    """🔴 单品牌租户上「按品牌」那一格 == 抬头。

    这正是 prod 上暴露问题的那个形状(MOCK_REST 只有「模拟餐饮」一个品牌)。
    """
    single = [ln for ln in _LINES if ln[1] == "模拟餐饮"]
    monkeypatch.setitem(globals(), "_LINES", single)
    head = _headline()
    cell = _groups()
    assert len(cell.rows) == 1, f"应该只有一个品牌, 实际 {len(cell.rows)}"
    assert _cents(cell.rows[0]["gross_profit"]) == head, (
        f"单品牌那一格 {_cents(cell.rows[0]['gross_profit'])} != 抬头 {head}")


# ── 判据三: 变异 ──────────────────────────────────────────────────────────
def test_mutation_grouped_layer_stops_allocating_turns_both_red(monkeypatch):
    """🔴 把**分组层**改回不摊折扣, 判据一和判据二必须红。

    ⚠️ 变异只打分组层: 抬头拿的是 `covered_net`(已经摊好的), 分组层拿的是
       `receipt_ratio`。把 ratio 换成 1 = 「分组层不摊」, 抬头纹丝不动。
    ⛔ 不去打 `_net_of` 本身 —— 那会同时改两层, 两层仍然相等, 变异**不生效**,
       而「变异不生效」和「守卫没覆盖」长得一模一样。
    """
    original = ge._covered_margin

    async def _grouped_without_allocation(*args, **kwargs):
        cov = await original(*args, **kwargs)
        return cov._replace(receipt_ratio=Decimal(1))

    monkeypatch.setattr(ge, "_covered_margin", _grouped_without_allocation)

    head = _headline()
    cell = _groups()
    total = _cents(sum(Decimal(str(r["gross_profit"])) for r in cell.rows))
    assert total != head, "变异后两层仍然相等 —— 这条断言守不住任何东西"
    # 而且方向必须是**高估**(不摊折扣 = 把没收到的钱算成收入)
    assert total > head, f"不摊折扣应当高估, 实际 {total} vs {head}"


def test_mutation_grouped_layer_uses_its_own_discount_rate(monkeypatch):
    """🔴 第二种错法: 分组层按**组内自己的**折扣率摊。

    它比「完全不摊」隐蔽得多 —— 每组看着都合理, 只有加起来才对不上。
    ⚠️ 折扣是整单的, 归不到组, 所以「组内折扣率」根本不存在;
       这里用「组内覆盖率」冒充它, 复刻的是那种**看起来讲得通**的写法。
    """
    original = ge._covered_margin_grouped

    async def _per_group_ratio(conn, factory_id, date_range, bridge, dim, cov,
                               entity_filter=None):
        got = await original(conn, factory_id, date_range, bridge, dim, cov,
                             entity_filter=entity_filter)
        if got is None:
            return None
        rows, sql = got
        # 每组按自己的份额再摊一次 —— 数值上就与抬头分叉了
        bumped = [{**r, "covered_net": r["covered_net"] * cov.share}
                  for r in rows]
        return bumped, sql

    monkeypatch.setattr(ge, "_covered_margin_grouped", _per_group_ratio)

    head = _headline()
    cell = _groups()
    total = _cents(sum(Decimal(str(r["gross_profit"])) for r in cell.rows))
    assert total != head, "组内自摊也应当让「和 = 抬头」红"


# ── 判据五: 截断形态本来就不该加得起来 ────────────────────────────────────
def test_truncating_aggregations_are_not_supposed_to_add_up():
    """🔴 `rank`/`bottom` 登记了 `limit=5`, 只给前 5 名 —— Σ ≠ 抬头 是**设计**。

    ⛔ 只写注释不够(owner 2026-08-14): 下一个人看到「截断形态加不起来」会去
       「修」它, 而那个修法(把 limit 去掉 / 把差额补进最后一组)都是错的。
    ⇒ 写成断言, 让「这里本来就不该相等」变成一条会红的东西。

    ⚠️ 断言的不只是「不相等」—— 还断言**差额恰好等于被截掉的那几组**。
       只断不相等的话, 分组层再长出第二个口径缺陷它照样绿。
    """
    from smartbi.gold.restaurant.metric_registry import AGGREGATIONS

    head = _headline()
    full = _groups("product", "compare")          # 无 limit, 全集
    assert _cents(sum(Decimal(str(r["gross_profit"])) for r in full.rows)) == head

    for agg_key in ("rank", "bottom"):
        limit = AGGREGATIONS[agg_key].limit
        assert limit, f"{agg_key} 没有 limit —— 这条判据挑错了形态"
        assert len(full.rows) > limit, (
            f"夹具只有 {len(full.rows)} 组 ≤ limit {limit}, "
            f"截断不会发生 —— 这条断言会空转")

        cell = _groups("product", agg_key)
        assert len(cell.rows) == limit, (
            f"{agg_key} 返回 {len(cell.rows)} 组, 登记的 limit 是 {limit}")
        total = _cents(sum(Decimal(str(r["gross_profit"])) for r in cell.rows))
        assert total != head, (
            f"{agg_key} 截断了却还等于抬头 —— 要么没真截断, "
            f"要么差额被谁补进去了(那是错的修法)")

        # 🔑 差额必须**恰好**是被截掉的那几组, 一分不多一分不少。
        kept = {r["dim_key"] for r in cell.rows}
        dropped = _cents(sum(Decimal(str(r["gross_profit"]))
                             for r in full.rows if r["dim_key"] not in kept))
        assert head - total == dropped, (
            f"{agg_key} 的差额 {head - total} ≠ 被截掉的那几组合计 {dropped} —— "
            f"截断之外还有第二个口径问题")


# ── 顺带: 分组层不许自己算折扣 ────────────────────────────────────────────
def test_the_receipt_ratio_has_exactly_one_home():
    """折扣摊派**只许有一处**。

    ⛔ 分组层自己再除一次 `paid / all_gross` = 同一个摊派两个来源,
       而两个来源迟早分叉 —— 这一版修的就是它。
    """
    import ast
    import inspect
    tree = ast.parse(inspect.getsource(ge))

    # ⚠️ 判据走 **AST**, ⛔ 不数 `"/ all_gross"` 这样的串 ——
    #    `share = covered_gross / all_gross` 是**覆盖率**, 与折扣无关,
    #    第一版就是被它打中的(本仓记过「过宽的正则打到不相干的代码」)。
    assigned = [n for n in ast.walk(tree) if isinstance(n, ast.Assign)
                for t in n.targets
                if isinstance(t, ast.Name) and t.id == "receipt_ratio"]
    assert len(assigned) == 1, (
        f"实收率被算了 {len(assigned)} 次 —— 分组层自己又摊了一遍折扣。\n"
        f"改法: 从 `_Covered.receipt_ratio` 拿, 见 `_net_of` 的推导")

    calls = [n for n in ast.walk(tree) if isinstance(n, ast.Call)
             and isinstance(n.func, ast.Name) and n.func.id == "_net_of"]
    assert len(calls) >= 2, (
        f"`_net_of` 只被调了 {len(calls)} 次 —— 两层都该走它, "
        f"少于 2 处说明有一层自己乘了比率")
