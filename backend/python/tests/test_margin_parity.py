"""两条路的**合计层毛利**必须给出同一个数。

## 为什么是闸而不是抽成一份（形态 D 的处置）

owner 2026-08-13 裁定：不选「合计层改走 `generic_executor`」——
那只消掉合计层那一份，分组层仍是 resolver 自己的 SQL，**并不能根治形态 D**，
只是把切分点换了个位置，代价却是动一个 5000 行 resolver。

⇒ 抽不动就立闸钉住两份一致。两份实现各自算，**算出来必须相同**。

## 两条路

| | 走哪 | 合计毛利 |
|---|---|---|
| 日结推送 | `generic_executor` 拆分执行 | `SUM(t.net_amount)` − `SUM(i.qty*food_cost)` |
| 通用问答 | `resolve_gross_margin` 自带 SQL | `_paid_revenue_in_window()` − 逐菜成本合计 |

⚠️ 真正的跨库对账在 `scripts/cron/margin-parity-daily.sh`（形状抄
`replay-equivalence-daily.sh`：同一天同一租户各算一次，比）。本模块守的是
**口径本身**：两边的公式必须是同一个。
"""
import inspect
import io
import re
from pathlib import Path

_PY_ROOT = Path(__file__).resolve().parents[1]
_REPO_ROOT = _PY_ROOT.parents[1]


def test_resolver_aggregate_uses_paid_revenue():
    """🔴 resolver 的合计层必须用**实收**，不能拿逐菜原价加总。

    改之前：`total_profit = sum(item["gross_profit"] for item in with_cost)`
    —— 那是明细行原价减成本，prod 实测比实收口径高 31,125.59（折扣额）。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "_paid_revenue_in_window" in src, "合计层没取实收营收"
    assert "total_paid_rev - total_cost" in src, (
        "合计毛利不是「实收 − 成本」—— 口径没改到位")
    # ⛔ 旧写法不许残留：逐菜 gross_profit 直接加总当合计
    assert 'sum(\n        float(item["gross_profit"]) for item in with_cost)' in src \
        or "total_cost = total_rev_with_cost - sum(" in src, (
        "找不到成本的推导 —— 这条断言的锚点过期了，请重新对齐")


def test_paid_revenue_query_does_not_join_line_items():
    """⛔ 取实收**不能** join 明细 —— 一张订单多条明细会扇出。

    2026-08-09 实测过 57 倍：米饭营收 ¥34,839 → ¥2,001,255。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router._paid_revenue_in_window)
    assert "SUM(t.net_amount)" in src, "没取实收字段"
    assert "fact_pos_item" not in src, (
        "取实收时 join 了明细表 —— 一张订单多条明细, SUM 会扇出")


def test_both_paths_use_the_same_revenue_column():
    """两条路的实收口径必须是**同一列**。

    ⛔ 一边 `t.net_amount` 一边别的列, 数字不会相等而且没人会注意到。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router
    from smartbi.gold.restaurant.metric_registry import METRICS

    executor_expr = METRICS["revenue"].exprs["txn"]
    resolver_src = inspect.getsource(router._paid_revenue_in_window)

    col = re.search(r"SUM\(\s*t\.(\w+)\s*\)", executor_expr)
    assert col, f"登记表里 revenue 的 txn 表达式看不出列名: {executor_expr}"
    assert f"t.{col.group(1)}" in resolver_src, (
        f"两条路的实收列不一致: 登记表用 t.{col.group(1)}, resolver 没用它")


def test_coverage_denominator_stays_item_grained():
    """⚠️ 覆盖率分子分母都得是 item 口径。

    分母换成实收会算出 >100%（749,009 ÷ 717,883 = 104.3%），
    而那个数会直接印在店长眼前。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "total_rev_with_cost / total_rev_items" in src, (
        "覆盖率的分母不是 item 口径 —— 会算出超过 100% 的覆盖率")


def test_per_dish_view_says_it_will_not_add_up():
    """🔴 拆开加不起来**必须说明** —— 这是修复的组成部分，不是挂账。

    合计用实收、逐菜用原价 ⇒ 按菜加总比合计高一个折扣额。
    店长点开一加发现对不上，会觉得系统在骗他。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "per_dish_no_discount_note" in src
    assert "按菜加起来会比合计高" in src, "没说清为什么加不起来"
    assert "折扣是整单的" in src
    # ⛔ 由 provenance 生成, 不手写限定语
    assert "provenance_qualifier(" in src and "PROV_ESTIMATED" in src, (
        "那句说明是手写的 —— 应当由 provenance 机制生成")


def test_the_note_is_conditional_on_there_being_a_discount():
    """阴性对照：没有折扣时（实收 == 原价）那句说明**不该出现**。

    ⛔ 无条件打印的说明等于噪音，而且会让「这次真的有折扣」这件事失去信号。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "abs(total_rev_items - total_paid_rev) > 0.01" in src, (
        "那句说明是无条件打印的 —— 没有折扣时也会出现")


def test_parity_cron_exists_and_is_three_state():
    """跨库对账那道闸要存在，且按硬约束 4 三态退出。"""
    sh = _REPO_ROOT / "scripts/cron/margin-parity-daily.sh"
    assert sh.exists(), "两条路对账的跑批不存在"
    src = io.open(sh, encoding="utf-8", newline="").read()
    assert "rm -f" in src, "跑批前没清上一次的产出(台账会脏读)"
    assert "INSTRUMENT DEAD" in src, "rc=2 没有单独告警"
    assert "-eq 2" in src, "退出码不是三态"
