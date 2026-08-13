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

## 🔴 两道闸各自看不见什么（写下来，别让人以为哪道是全覆盖）

| 闸 | 抓什么 | **看不见什么** |
|---|---|---|
| 源码闸（本模块） | 明写的列名变了 | **变量拼装 / 等价写法** —— 它比的是字符串，resolver 哪天把 `t.net_amount` 换成变量或等价 SQL，它一声不吭（f-string 那个老盲区） |
| 对账闸（cron） | 两条路的数不一样了 | **两侧同源** —— 若两边最终走到同一段代码，口径一变两侧一起变，diff 恒 0，它永远绿 |

⇒ 两道**互补**，缺一不可：源码闸兜「数字碰巧相等但口径已经不同」，
对账闸兜「源码看着没变但算出来不一样」。

⚠️ 对账闸的「两侧同源」盲区**已经用真口径变异验过**（2026-08-13）：
把 `_paid_revenue_in_window` 打桩成 None → resolver 走自己的回退路径退回
item 口径 → diff = **-31,125.59**（正是折扣额），而 executor 侧一动不动。
⇒ 两侧确实独立。⛔ 这一步不能用容差变异代替：容差变异硬改阈值，
   不管两侧从哪来，测不出同源。
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
    assert "按菜加起来会比上面的合计高" in src, "没说清为什么加不起来"
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


def test_margin_rate_denominator_matches_the_numerator():
    """🔴 毛利率的分母必须和分子**同口径**。

    prod 实测漏过这一步：分子换成实收（475,623.83），分母还是 item 口径
    （749,009）→ 问答报 63.5%，日结报 66.3%。**毛利对上了，毛利率还是两个数。**
    ⛔ 改一半比不改更难发现：最显眼的那个数已经一致了。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "total_profit / total_rev\n" in src or \
           "total_profit / total_rev\b" in src or \
           "        total_profit / total_rev\n" in src, (
        "毛利率的分母不是实收营收 —— 与分子不同口径")
    assert "total_profit / total_rev_with_cost" not in src, (
        "毛利率还在用 item 口径的分母")


def test_customer_text_avoids_jargon_that_sanitize_would_rewrite():
    """⛔ 自己敲进源码的串不许靠 sanitize 兜。

    prod 实测：正文写「计算口径：」，而「口径」在 `INTERNAL_VOCAB` 里 →
    sanitize 替换成「计算方法」→ 店长看到「**计算计算方法：**」。
    """
    from smartbi.gold.customer_text import INTERNAL_VOCAB
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    # 只看进正文的那几行（f-string 里以 "> " 或 "- " 开头的）
    for line in src.splitlines():
        stripped = line.strip()
        if not stripped.startswith('f"'):
            continue
        if "⛔" in line or "⚠️" in line or "#" in line.split('f"')[0]:
            continue
        for word in INTERNAL_VOCAB:
            assert word not in stripped, (
                f"正文里出现黑话 {word!r}，sanitize 会改写它，改写后读不通：\n{stripped}")


def test_the_no_discount_note_reads_correctly_in_the_template():
    """⚠️ basis 是**名词短语** —— 限定语模板是「用{basis}估算，…」。

    prod 实测塞了一整句进去，读成
    「用这里没扣折扣 —— 折扣是整单的…会比合计高估算，这部分是估出来的」。
    ⛔ 同一个错在成本卡那条 basis 上已经犯过一次。判据：**把模板套一遍读出声**。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert 'PROV_ESTIMATED, "没扣折扣的单菜价"' in src, (
        "basis 不是名词短语 —— 套进「用{basis}估算」会读不通")
    # 解释放在模板外面, 不塞进 basis
    assert "折扣是整单的，摊不到单道菜" in src


def test_resolver_exposes_the_aggregate_for_the_parity_gate():
    """⛔ 闸读不到 = 闸不存在，只是它诚实地说了「没量到」。

    prod 实测：第一次跑对账闸就报 rc=2 —— executor 侧有数，resolver 侧 None，
    因为 kpis 没有 `label`，按标签猜读不到。合计层的数必须**具名**带出去。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert '"aggregate_gross_profit": total_profit' in src, (
        "合计毛利没具名带进 meta —— 对账闸读不到它")
    assert '"aggregate_paid_revenue": total_rev' in src

    probe = io.open(_PY_ROOT / "smartbi/scripts/margin_parity_probe.py",
                    encoding="utf-8", newline="").read()
    assert "aggregate_gross_profit" in probe, "闸没读那个具名字段"


def test_parity_probe_passes_a_price_viewing_role():
    """🔴 对账探针必须带角色，否则 RBAC 把金额全抹掉。

    prod 实测：不带 role → `meta = {"rbac_masked": True}`，一个数都没有，
    闸连报两次 rc=2。而我据此推断「meta 构造点没补全」—— **推断错了**。

    ⛔ 判据：闸报「没量到」时**先查仪器自己的调用参数**，再去查被测对象。
    """
    probe = io.open(_PY_ROOT / "smartbi/scripts/margin_parity_probe.py",
                    encoding="utf-8", newline="").read()
    assert "role=ctx.role" in probe, (
        "对账探针没传角色 —— RBAC 会把金额抹掉, 闸永远读不到 resolver 那一侧")


def test_basis_shape_is_enforced_at_the_consumer():
    """🔴 basis 形状约束放在**消费端**，不是每个产出端。

    同一个错犯过两次（成本卡、折扣）。「犯过、记过、又犯」说明记在 memory 里
    挡不住 —— 写 basis 的时候不会去翻 memory。改成当场炸。
    """
    import pytest as _pytest

    from smartbi.gold.restaurant.provenance import (
        ESTIMATED, ProvenanceError, qualifier)

    # ⛔ 整句 → 炸（正是 prod 上打出乱码那一句的形状）
    with _pytest.raises(ProvenanceError, match="名词短语|超过"):
        qualifier(ESTIMATED, "这里没扣折扣 —— 折扣是整单的，摊不到单道菜")
    with _pytest.raises(ProvenanceError, match="名词短语"):
        qualifier(ESTIMATED, "成本卡的理论用量。实际用了多少要等盘点")

    # ✅ 名词短语 → 过，且套进模板读得通
    text = qualifier(ESTIMATED, "没扣折扣的单菜价")
    assert "用没扣折扣的单菜价估算" in text

    # 阳性对照: 现役的两个 basis 都必须过 —— 否则这道闸会把生产打挂
    from smartbi.gold.restaurant.generic_executor import _COST_CARD_BASIS
    qualifier(ESTIMATED, _COST_CARD_BASIS)


def test_the_real_koujing_mutation_probe_is_kept():
    """🔴 真口径变异要**留在仓库里**，不是跑一次就丢。

    2026-08-13 prod 实测（`smartbi/scripts/margin_parity_mutation.py`）：

    | | executor | resolver | diff |
    |---|---|---|---|
    | 基线 | 475,623.834 | 475,623.834 | -0.0 |
    | 变异 | 475,623.834 | 506,749.424 | **-31,125.59** |

    四条判据全过：基线绿 / 变异红 / **红出的正是折扣额** / **两侧独立**。

    ⛔ 「红了」不够，要「红出正确的数」—— 红了但 diff 是别的数，
       等于变异没打到该打的地方（形态 C″ 的镜像）。
    """
    src = io.open(_PY_ROOT / "smartbi/scripts/margin_parity_mutation.py",
                  encoding="utf-8", newline="").read()
    for key in ("red_for_the_right_reason", "sides_are_independent",
                "baseline_green", "mutation_red"):
        assert key in src, f"变异探针少了判据 {key}"
    assert "EXPECTED_DISCOUNT" in src, "没有钉住「红出的必须是折扣额」"
    # ⛔ 不许退化成容差变异
    assert "PARITY_TOLERANCE" not in src, (
        "真口径变异不许靠改容差 —— 那测不出两侧同源")
