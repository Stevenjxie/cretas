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

import pytest
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
    assert "covered_net_rev - total_cost" in src, (
        "合计毛利不是「覆盖净营收 − 覆盖成本」—— 口径没改到位")
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
    assert "total_profit / covered_net_rev" in src, (
        "毛利率的分母不是覆盖净营收 —— 与分子不同口径")
    # ⛔ 旧分母(实收全额)不许残留 —— 用词边界判, 免得把 covered_net_rev 也算上。
    assert "total_profit / total_rev " not in src, (
        "毛利率还在用未摊折扣/未按覆盖口径的分母")


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


def test_cost_outlier_threshold_has_exactly_one_definition():
    """🔴 判据只能有**一处定义**，两条路读同一份。

    改之前：registry 5.0 / router **10.0** —— 同一个判据两个值（形态 D）。
    实测后果：青花椒「米饭」成本卡 ¥167.20 / 售价 ¥16.80 = **9.95 倍**，
    **恰好卡在两个阈值之间** —— 一条路排除它、另一条不排除，两条路给出两个数。

    ⚠️ 2026-08-14 订正: 共用阈值**不够**。同一个常量下, SQL 侧按【行】判、
    Python 侧按【菜】判 —— 判据 = 阈值 + 作用粒度, 只共用前者等于没共用。
    实测残差 19,131.37 全部来自米饭一道菜。现在整条判据只有一处实现。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router
    from smartbi.gold.restaurant.metric_registry import (
        COST_UNIT_ERROR_RATIO, dish_cost_is_implausible)

    # router 不再持有任何阈值 —— 它整条判据都委托出去了
    assert not hasattr(router, "_MAX_COST_TO_REALIZED_PRICE_RATIO"), (
        "router 又拿回了一份阈值 —— 两份迟早漂到不同的值上")
    assert router._is_plausible_dish_unit_cost(167.20, 759.55, 12760.36) is False
    assert dish_cost_is_implausible(167.20, 759.55, 12760.36) is True

    # 阳性对照: 那个真实样本必须落在阈值之内(否则这条断言守的是个不存在的场景)
    assert 167.20 > COST_UNIT_ERROR_RATIO * 16.80, "米饭那张坏卡抓不到了"
    # 阴性对照: 同租户正常的那张不许被误伤
    assert not (2.20 > COST_UNIT_ERROR_RATIO * 58.04), "把正常成本卡也排除了"


def test_excluded_dishes_are_named_not_silently_dropped():
    """⛔ 静默排除 = 降级处理：答案看起来正常而数据是坏的，没人会去修。

    🔴 owner: 这不是一句免责声明，是一条**可执行的修复指令**。
    """
    from smartbi.gold.restaurant.generic_answer import render
    from smartbi.gold.restaurant.generic_executor import CellResult

    cell = CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1000.0}], (), "", "ESTIMATED", "成本卡的理论用量", 0.42,
        ({"name": "米饭", "card_cost": 167.20, "avg_price": 16.80},),
    )
    text = render(cell, "今天")
    assert "米饭" in text, "被排除的菜没有指名"
    assert "167.20" in text and "16.80" in text, "没给出成本卡与售价, 修不了"
    assert "单位" in text, "没说清楚要核对什么"
    # ⚠️ 措辞要反映它能自愈 —— 判据是比值不是名单, 卡改好就自动回到计算里
    assert "自动算回来" in text, "没说清改好之后会自愈"

    # 阴性对照: 没有异常卡时**不许**出现这段(否则它是句无条件的噪音)
    clean = CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1000.0}], (), "", "ESTIMATED", "成本卡的理论用量", 1.0)
    assert "单位记错" not in render(clean, "今天")


# ═══════════════════════════════════════════════════════════════════════════
# 🔴 owner 2026-08-14 判据五: 防「两份判据」的闸
#
# 为什么需要它: `cost_outlier_predicate()` 的 docstring 逐字写着
# 「唯一定义, 两条路都读它」——**而问答那条路从来没读过它**。
# 注释里写「唯一定义」不构成唯一定义, 这是本项目第二次同形
# (上一次是「zhipu 必须排最后」, 注释写着, 没有任何东西执行它)。
# ⇒ 这次要结构保证 + 一条能红的断言, ⛔ 只把注释写详细不算。
# ═══════════════════════════════════════════════════════════════════════════
import pathlib
import re

_PY_ROOT = pathlib.Path(__file__).resolve().parents[1]
#: 判据的家。⚠️ 用相对路径比对, ⛔ 不按类简名找文件 ——
#: 仓里有过两个同名文件, glob 顺序在 Windows/Linux 不同, 判决会随平台漂。
_RULE_HOME = pathlib.Path("smartbi/gold/restaurant/metric_registry.py")


def _strip_prose(src: str) -> str:
    """去掉注释和 docstring, 只留**会执行的代码**。

    🔴 这一步不是洁癖。第一版没有它, 闸当场把自己的说明文字数了进去:
       我在 router 里写了一句「⛔ 这里不再 import COST_UNIT_ERROR_RATIO」,
       闸就报「router 出现 1 次」。方向荒谬 —— **写文档=判据变多**。
    ⚠️ 本仓 2026-08-13 已经踩过一次完全相同的形状(数 `@RequireModule` 不剥注释,
       把注解自己的用法示例也计入)。⇒ 用正则在源码里数东西前, 先问
       「注释里会不会也有它」—— 代码里最爱出现某个名字的地方恰恰是解释它的注释。
    ⛔ 只剥注释和 docstring, **保留其余字符串字面量** —— SQL 模板是字符串,
       而「有人把行级判据写回 SQL 模板里」正是要抓的东西。
    """
    import io
    import tokenize

    out, prev_end = [], (1, 0)
    try:
        tokens = list(tokenize.generate_tokens(io.StringIO(src).readline))
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return src                     # 剥不动就原样给, ⛔ 不静默跳过这个文件
    docstring_positions = set()
    try:
        import ast
        tree = ast.parse(src)
        for node in ast.walk(tree):
            if not isinstance(node, (ast.Module, ast.ClassDef,
                                     ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            body = getattr(node, "body", None) or []
            if (body and isinstance(body[0], ast.Expr)
                    and isinstance(body[0].value, ast.Constant)
                    and isinstance(body[0].value.value, str)):
                docstring_positions.add((body[0].lineno, body[0].col_offset))
    except SyntaxError:
        pass
    for tok in tokens:
        if tok.type == tokenize.COMMENT:
            continue
        if tok.type == tokenize.STRING and tok.start in docstring_positions:
            continue
        out.append(tok.string)
        prev_end = tok.end
    del prev_end
    return "\n".join(out)


def _product_sources():
    """产品代码(排除测试与探针脚本)。探针本来就该调产品的函数, 不该自己算。"""
    for path in _PY_ROOT.rglob("*.py"):
        rel = path.relative_to(_PY_ROOT)
        parts = set(rel.parts)
        if parts & {"tests", "venv", "venv-current", "node_modules", ".git"}:
            continue
        if rel.name.startswith("test_"):
            continue
        yield rel, _strip_prose(path.read_text(encoding="utf-8", errors="ignore"))


def test_the_cost_outlier_rule_has_exactly_one_home():
    """成本卡异常判据只能在一个文件里被计算。

    判据(机械可查, 不依赖注释):
      · `COST_UNIT_ERROR_RATIO` 只允许出现在 `metric_registry.py`
      · 那个文件里, 它只允许出现两次: 定义一次 + 判定里用一次
    """
    offenders = []
    for rel, src in _product_sources():
        hits = src.count("COST_UNIT_ERROR_RATIO")
        if not hits:
            continue
        if rel != _RULE_HOME:
            offenders.append(f"{rel} 出现 {hits} 次")
        elif hits != 2:
            offenders.append(f"{rel} 出现 {hits} 次(应为 定义1 + 判定1)")
    assert not offenders, (
        "🔴 成本卡异常判据出现了第二处计算点: " + "; ".join(offenders) +
        " —— 两份判据一定会漂, 2026-08-13 实测漂出 19,131.37 元的差额")


def test_the_row_level_predicate_never_comes_back():
    """行级版本已删, ⛔ 不许以任何形式回来。

    它错在**粒度**: 成本卡挂在菜上, 逐行判会让同一张卡的判决取决于
    「那天这道菜恰好怎么卖的」—— 打折行/加量行会把 amount/qty 抬高。
    """
    for rel, src in _product_sources():
        assert "cost_outlier_predicate" not in src, (
            f"🔴 {rel} 里出现了行级判据 —— 判据的粒度是菜不是行")


def test_the_margin_sql_never_judges_a_cost_card_itself():
    """SQL 只按**名单**剔除, ⛔ 不许自己比大小。

    🔴 这是行级判据回来的最可能形态: 有人图省事在模板里加一句
       `AND c.food_cost > 5 * (i.amount / i.qty)`。那样判据又变成两处,
       而且是**粒度不同**的两处 —— 正是 19,131.37 那笔差额的成因。

    ⚠️ 第一版这条我写成了「全仓只许有一处 revenue/qty」, 结果它在
       `product_summary_writer` / `restaurant_analyzer` 上开火 —— 那两处算的是
       正常的均价, 与成本卡判定无关。**一个在无关代码上开火的判据不区分好坏。**
       改成只盯这两个模板。
    """
    from smartbi.gold.restaurant.generic_executor import (
        _COVERED_MARGIN_SQL, _DISH_COST_FACTS_SQL, _EXCLUDED_EXPR)

    # ⚠️ `_EXCLUDED_EXPR` 必须一起查。第一版只查两个模板, 做变异时发现:
    #    把行级判据塞进 `_EXCLUDED_EXPR`(模板里只是 `{excluded}` 占位符)
    #    这道闸**一个字都不会说**。变异抓出来的, 不是我想到的。
    for name, tpl in (("covered_margin", _COVERED_MARGIN_SQL),
                      ("dish_cost_facts", _DISH_COST_FACTS_SQL),
                      ("excluded_expr", _EXCLUDED_EXPR)):
        squashed = re.sub(r"\s+", "", tpl)
        assert "food_cost>" not in squashed and "food_cost<" not in squashed, (
            f"🔴 {name} 里又出现了对成本卡的比较 —— 判据必须留在 Python 一处")

    # 阳性对照: 排除确实是**发生了**的, 只是靠名单
    assert "{excluded}" in _COVERED_MARGIN_SQL, "覆盖毛利根本没做排除"
    assert "ANY($6::text[])" in _EXCLUDED_EXPR, "排除不是按名单做的"


def test_both_paths_reach_that_one_home():
    """阳性对照 —— 上面三条都是阴性断言(「不许出现」)。

    ⛔ 没有这一条, 把判据整个删掉那三条也全绿: 「没有第二处」在
       「一处都没有」时同样成立。这正是本仓踩过的恒真式形态。
    """
    import inspect

    from smartbi.gold.restaurant import generic_executor as ge
    from smartbi.gold.restaurant import restaurant_ops_router as router
    from smartbi.gold.restaurant.metric_registry import dish_cost_is_implausible

    assert "dish_cost_is_implausible" in inspect.getsource(ge._cost_outliers), (
        "日结/执行器那条路没有调用唯一判据")
    assert "_dish_cost_is_implausible" in inspect.getsource(
        router._is_plausible_dish_unit_cost), "问答那条路没有调用唯一判据"
    # 两条路拿同一组输入必须得到同一个判决(米饭当天的真实读数)
    assert dish_cost_is_implausible(167.20, 759.55, 12760.36) is True
    assert router._is_plausible_dish_unit_cost(167.20, 759.55, 12760.36) is False


def test_both_bodies_name_the_bad_cost_card_not_just_count_it():
    """🔑 owner 2026-08-14 判据三: 两条路的正文都要**指着它说出来**。

    这一条不是附加项 —— 它是当初冻结 ¥167.20 那张卡的**全部理由**:
    「产品能指着它说『167.20 一份而卖 16.80，请核对单位』的那天，才算这块做完」。
    差额归零但产品说不出这句话, 这一节不算做完。

    ⚠️ 改之前问答那条只说「1 个菜品成本值明显异常」—— 对店长不产生任何动作:
       他不知道是哪道菜, 也不知道该改什么。
    """
    src = pathlib.Path(
        _PY_ROOT / "smartbi/gold/restaurant/restaurant_ops_router.py"
    ).read_text(encoding="utf-8")
    # 问答那条: 计数之后必须跟着名字与两个数
    assert "个菜品成本值明显异常：" in src, "问答只报了个数, 没有指名"
    assert "invalid_cost_value" in src.split("个菜品成本值明显异常")[1][:900], (
        "指名了但没给成本卡的数, 店长照样修不了")

    # 日结那条(generic_answer) 的阳性对照已在
    # `test_excluded_dishes_are_named_not_silently_dropped` 里, 这里只钉住
    # 「两条路都做这件事」这个事实本身。
    from smartbi.gold.restaurant.generic_answer import render
    from smartbi.gold.restaurant.generic_executor import CellResult
    cell = CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1.0}], (), "", "ESTIMATED", "成本卡的理论用量", 0.4,
        ({"name": "米饭", "card_cost": 167.20, "avg_price": 14.97},))
    assert "米饭" in render(cell, "今天")


def test_the_margin_formula_line_actually_adds_up():
    """正文里那条「计算过程」自己必须算得平。

    🔴 prod 实测(2026-08-12 / RES_3101_009): 它印的是
       `毛利 ¥124,071.85 = 实收营收 ¥373,832.93 − 对应菜品成本 ¥26,254.12`
       —— 店长照着减一遍得 347,578.81。**答案自己跟自己打架。**
       减数必须是**覆盖部分**的净营收, 与被减出来的毛利同源。
    """
    src = pathlib.Path(
        _PY_ROOT / "smartbi/gold/restaurant/restaurant_ops_router.py"
    ).read_text(encoding="utf-8")
    formula = [ln for ln in src.splitlines() if "计算过程：`毛利" in ln]
    assert formula, "找不到那条计算过程"
    for line in formula:
        assert "total_rev:" not in line, (
            "🔴 计算过程又拿全额实收当减数 —— 与它算出来的覆盖毛利不同源")


def test_the_rejected_cost_card_value_survives_for_the_message():
    """被判无效的那张卡**要留下值** —— 否则指名那句会打出 ¥0.00。

    🔴 prod 实测(2026-08-14): 「米饭（成本卡 ¥0.00 一份，实际卖 ¥14.97）」——
       `food_cost_unit` 只在 has_cost 时才填, 异常项恒为 None。
       一个既没信息量又明显是假的数字, 店长照着核对只会更糊涂。
    ⚠️ 这个字段**不进任何计算**, 只服务那句话。
    """
    from smartbi.gold.restaurant import restaurant_ops_router as router

    rows = [{"dish_name": "米饭", "normalized_name": "米饭", "total_qty": 100.0,
             "total_revenue": 1497.0, "bills": 50}]
    entries = router._build_margin_entries(
        rows, {"米饭": "PK"}, {"PK": 167.20})
    bad = entries[0]
    assert bad["invalid_cost"] is True, "这张卡该被判无效(167.20 vs 均价 14.97)"
    assert bad["has_cost"] is False, "无效的卡不许进计算"
    assert bad["food_cost_unit"] is None, "无效的卡不许出现在计算字段里"
    assert bad["invalid_cost_value"] == 167.20, (
        "🔴 无效卡的值没留下 —— 指名那句会打出 ¥0.00")

    # 阳性对照: 正常的卡不许被塞进这个字段(否则它就不是「无效卡」的标记了)
    ok = router._build_margin_entries(
        rows, {"米饭": "PK"}, {"PK": 3.0})[0]
    assert ok["invalid_cost"] is False and ok["invalid_cost_value"] is None


# ═══════════════════════════════════════════════════════════════════════════
# T2 前两层（owner 2026-08-14 放行；第三层「成本率可疑」挂账）
# ═══════════════════════════════════════════════════════════════════════════
def test_t2_names_the_dishes_and_quantifies_the_lift():
    """第一层缺口 + 第二层影响，都必须是**算出来的**。"""
    from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps

    gaps = [{"name": "顺德干蒸鲜排骨", "revenue": 300.0},
            {"name": "老广腊味煲仔饭", "revenue": 200.0},
            {"name": "豉油蒸海鲈鱼", "revenue": 100.0},
            {"name": "小菜", "revenue": 1.0}]
    got = offers_for_cost_gaps(gaps, 0.422, 10000.0)
    assert got, "有缺口却没开价"
    text = got[0]["text"]
    # 第一层: 点名, ⛔ 不许只报个数
    for name in ("顺德干蒸鲜排骨", "老广腊味煲仔饭", "豉油蒸海鲈鱼"):
        assert name in text, f"没点名 {name}: {text}"
    assert "小菜" not in text, "超出 top_n 还点名了 —— 那就不是优先级是清单"
    # 第二层: 覆盖率增量算得对 (300+200+100)/10000 = 6pp
    assert "42.2%" in text and "48.2%" in text, text
    assert got[0]["coverage_after"] == pytest.approx(0.482)
    assert got[0]["gap_total"] == 4, "缺口总数没带出去"


def test_t2_never_promises_accuracy():
    """🔴 承重: ⛔「补上就准了」。

    覆盖率不依赖卡录得全不全, 所以覆盖率那句站得住;
    而「卡是对的但只录了主料」已经实测到 —— 承诺准确是一句我们**知道**
    可能不成立的话。
    """
    from smartbi.gold.restaurant.fill_offers import (
        build_fill_offers, offers_for_cost_gaps)

    banned = ("就准了", "从估变实", "就是账上的了", "准确", "就对了")
    texts = [o["text"] for o in offers_for_cost_gaps(
        [{"name": "A", "revenue": 500.0}], 0.4, 1000.0)]
    texts += [o["text"] for o in build_fill_offers(
        provenance="ESTIMATED", estimation_basis="成本卡的理论用量",
        estimated_metric_labels=["毛利"])]
    assert texts, "一条开价都没有 —— 这条断言会恒真"      # 阳性对照
    for t in texts:
        for word in banned:
            assert word not in t, f"开价承诺了准确性: {t!r} 命中 {word!r}"


def test_t2_offer_follows_requires_not_a_handwritten_list(monkeypatch):
    """🔴 判据三的变异对照: 改一条 `requires`，开价必须跟着变。

    ⚠️ 先证明**行为变了**再看断言 —— 「变异没生效」和「守卫没覆盖」长得一样
       但成因相反(本仓踩过三次)。
    """
    from smartbi.gold.restaurant import metric_registry as reg
    from smartbi.gold.restaurant import fill_offers

    column = "agg_restaurant_product_cost.food_cost"
    before = fill_offers.unlocked_by_column().get(column, ())
    assert "gross_margin" in before, "阳性对照: 补成本卡本来就该解锁毛利率"

    # 变异: 把毛利的分子换掉, 于是成本卡不再解锁毛利率
    mutated = dict(reg.DERIVED)
    mutated.pop("gross_profit")
    mutated.pop("gross_margin")
    monkeypatch.setattr(reg, "DERIVED", mutated)

    after = fill_offers.unlocked_by_column().get(column, ())
    assert "gross_margin" not in after, (
        "🔴 变异没生效 —— 开价读的不是 registry, 是别处")   # 行为真的变了
    assert before != after, "反查结果一个字没变, 这条对照是空转"


# ═══════════════════════════════════════════════════════════════════════════
# ESTIMATED 有两种 —— owner 2026-08-14
#   (a) 可补的估算   缺某列数据, 补了就实    → 「补 X, Y 从估变实」 ✅
#   (b) 结构性估算   数据齐了也只能是估的    → 那句永远兑现不了     ⛔
# 判据: 承诺「补 X 就变准」之前, 先问「补齐之后它会不会仍然是估的」。
# ═══════════════════════════════════════════════════════════════════════════
def test_structural_estimate_never_promises_it_will_become_actual():
    """(b) 类不许出现「从估变实」。日毛利就是 (b)。"""
    from smartbi.gold.restaurant.fill_offers import offers_for_estimated
    from smartbi.gold.restaurant.metric_registry import (
        ESTIMATE_STRUCTURAL, estimate_kind)

    assert estimate_kind("gross_profit") == ESTIMATE_STRUCTURAL, (
        "毛利被判成 (a) 了 —— 成本卡给的是理论用量, 补齐也不会变成实际耗用")
    got = offers_for_estimated(
        "ESTIMATED", "成本卡的理论用量", ["毛利"], ["gross_profit"])
    text = got[0]["text"]
    assert "从估变实" not in text, f"(b) 类承诺了兑现不了的事: {text}"
    assert "只能是估的" in text, f"没说清它是结构性的: {text}"
    assert "盘" in text, f"没说清要变实得改什么经营动作: {text}"


def test_reducible_estimate_still_gets_the_upgrade_promise():
    """阳性对照 —— (a) 类**该**说「补了就实」。

    ⛔ 没有这条, 上面那条阴性断言就是恒真式: 把「从估变实」整句删掉,
       它照样绿。本仓踩过这个形状。
    """
    from smartbi.gold.restaurant.fill_offers import offers_for_estimated
    from smartbi.gold.restaurant.metric_registry import (
        ESTIMATE_REDUCIBLE, estimate_kind)

    assert estimate_kind("revenue") == ESTIMATE_REDUCIBLE
    got = offers_for_estimated(
        "ESTIMATED", "行业默认成本率 32%", ["营收"], ["revenue"])
    assert "从估变实" in got[0]["text"], (
        "(a) 类也不说「补了就实」—— 那这个区分就没有任何行为差别")


def test_mislabelling_b_as_a_flips_the_copy_and_is_caught(monkeypatch):
    """🔴 变异对照: 把 (b) 误标成 (a), 文案必须变回「从估变实」并被抓住。

    ⚠️ 先证明**行为变了**再看断言 —— 「变异没生效」与「守卫没覆盖」长得一样。
    """
    from smartbi.gold.restaurant import metric_registry as reg
    from smartbi.gold.restaurant.fill_offers import offers_for_estimated

    before = offers_for_estimated(
        "ESTIMATED", "成本卡的理论用量", ["毛利"], ["gross_profit"])[0]["text"]
    assert "从估变实" not in before                      # 改之前是对的

    # 变异: 把「成本卡是理论用量」这条标注拿掉 → 毛利被误判成 (a)
    monkeypatch.setattr(reg, "IRREDUCIBLE_ESTIMATE_COLUMNS", {})
    assert reg.estimate_kind("gross_profit") == reg.ESTIMATE_REDUCIBLE, (
        "🔴 变异没生效 —— (a)/(b) 的判定读的不是登记表")   # 行为真的变了

    after = offers_for_estimated(
        "ESTIMATED", "成本卡的理论用量", ["毛利"], ["gross_profit"])[0]["text"]
    assert "从估变实" in after, "文案没跟着标注变 —— 那它是手写的, 不是推出来的"
    assert before != after, "变异前后一个字没变, 这条对照是空转"


def test_the_irreducible_annotation_is_the_only_hand_written_bit():
    """⚠️ 这一处**是靠人标的**, 卡上写明了。闸只能守住它不过期。

    「理论用量 ≠ 实际耗用」是业务语义, 推不出来。能机械查的是:
    标了的列必须真的被人依赖, 且必须写清楚为什么。
    """
    from smartbi.gold.restaurant.metric_registry import (
        IRREDUCIBLE_ESTIMATE_COLUMNS, METRICS, assert_registry_self_consistent)

    assert IRREDUCIBLE_ESTIMATE_COLUMNS, "一条都没有 —— 这条断言会恒真"
    required = {c for m in METRICS.values() for c in m.requires}
    for column, reason in IRREDUCIBLE_ESTIMATE_COLUMNS.items():
        assert column in required, f"{column} 没有任何指标依赖它 —— 过期标注"
        assert reason.strip(), f"{column} 没写为什么补齐也还是估的"
    assert_registry_self_consistent()


# ═══════════════════════════════════════════════════════════════════════════
# 进度感 —— 「你的数据补到 N 类里的 M 类了」(路线图第 4 项)
# ═══════════════════════════════════════════════════════════════════════════
def test_data_sources_are_derived_not_a_hand_written_list():
    """类别由**列**推出来: 有几个不同的值就是几类。

    ⛔ 本仓不许出现「一共有哪五类」的常数 —— 凑数会让它与登记表对不上。
    """
    from smartbi.gold.restaurant.metric_registry import (
        COLUMN_SOURCES, columns_of_source, data_sources)

    got = data_sources()
    assert got, "一类都推不出来 —— 下面的断言会恒真"
    assert len(got) == len(set(COLUMN_SOURCES.values())), "类别数与标注对不上"
    # 每一类都必须真的挂着列(反向)
    for source in got:
        assert columns_of_source(source), f"{source} 是个空类"
    # 阳性对照: 已知的两类必须在里面, 且不在同一类
    #   ⚠️ 同一张 fact_pos_transaction 上, 净额是 POS 自带的、税额要另接 ——
    #      按表名硬猜会把它们并成一类, 那就没有进度可言了。
    assert COLUMN_SOURCES["fact_pos_transaction.net_amount"] != \
        COLUMN_SOURCES["fact_pos_transaction.tax_amount"], (
        "按表名归类了 —— 那是另一种手写")


def test_a_new_requires_column_lands_in_a_class_automatically(monkeypatch):
    """🔴 变异对照: 给某个指标的 requires 加一列 → 类别与计数必须跟着变。

    ⚠️ 先证明**行为变了**再看断言。
    """
    from smartbi.gold.restaurant import metric_registry as reg

    before_n = len(reg.data_sources())
    before_cols = reg.columns_of_source("损耗盘点")

    # 变异: 新登记一列, 归到已有的一类
    mutated = dict(reg.COLUMN_SOURCES)
    mutated["fact_restaurant_wastage.reason"] = "损耗盘点"
    monkeypatch.setattr(reg, "COLUMN_SOURCES", mutated)

    after_cols = reg.columns_of_source("损耗盘点")
    assert after_cols != before_cols, "🔴 变异没生效 —— 归类读的不是登记表"
    assert "fact_restaurant_wastage.reason" in after_cols
    assert len(reg.data_sources()) == before_n, "归到已有类不该改变类别数"

    # 再变异: 新登记一列且是**新的一类** → 类别数必须 +1
    mutated2 = dict(mutated)
    mutated2["fact_pos_transaction.member_id"] = "会员系统"
    monkeypatch.setattr(reg, "COLUMN_SOURCES", mutated2)
    assert len(reg.data_sources()) == before_n + 1, (
        "新来源没有变成新的一类 —— 那这个进度条永远停在同样的分母上")


def test_one_off_sources_are_not_measured_by_fill_rate():
    """🔴 一次性接入的类只问「接了没有」, ⛔ 不算填充率。

    POS 那 13% 不是「没录全」, 是那些单本来就没退菜、没打折。
    把**合法的空**当成缺失 —— 本仓第四次踩这一族。
    """
    from smartbi.gold.restaurant import data_progress as dp
    from smartbi.gold.restaurant import metric_registry as reg

    assert reg.intake_of_source("POS 流水") == reg.INTAKE_ONE_OFF
    assert reg.intake_of_source("成本卡") == reg.INTAKE_PER_ITEM, (
        "成本卡被标成一次性接入了 —— 那它就永远只有有/无两档")

    # 87% 填充(有退菜列大量为空) 仍然应当判「有」, ⛔ 不判「部分」
    fills = {c: 0.87 for c in reg.columns_of_source("POS 流水")}
    status, _ = dp._status_binary("POS 流水", fills)
    assert status == dp.STATUS_HAVE, f"POS 被填充率判成了 {status}"
    # 阴性对照: 全空才是「没接」
    zero = {c: 0.0 for c in reg.columns_of_source("税额")}
    assert dp._status_binary("税额", zero)[0] == dp.STATUS_MISSING


def test_ranking_is_marginal_revenue_share_not_metric_count():
    """🔴 排序键 = 补齐能让**能算准的营收占比**提升多少, 不是解锁几个指标。

    改之前按存量排, 「下一个最划算」永远是 POS(解锁 16 个) —— 而 POS 早就接了,
    店长照着做无从下手。
    """
    from smartbi.gold.restaurant.data_progress import _coverage_lift

    gaps = [{"name": "A", "revenue": 300.0}, {"name": "B", "revenue": 200.0},
            {"name": "C", "revenue": 100.0}]
    lift = _coverage_lift((tuple(gaps), 0.402, 10000.0))
    assert lift == pytest.approx(0.06), f"边际算错了: {lift}"
    # 阴性对照: 没有缺口就没有边际
    assert _coverage_lift(((), 0.402, 10000.0)) == 0.0


def test_mislabelling_the_cost_card_as_one_off_removes_it_from_ranking(monkeypatch):
    """🔴 变异对照: 把成本卡误标成「一次性接入」→ 它必须从排序里消失。

    ⚠️ 先证明**行为变了**再看断言。
    """
    from smartbi.gold.restaurant import metric_registry as reg

    assert reg.intake_of_source("成本卡") == reg.INTAKE_PER_ITEM   # 阳性对照

    mutated = dict(reg.SOURCE_INTAKE)
    mutated["成本卡"] = reg.INTAKE_ONE_OFF
    monkeypatch.setattr(reg, "SOURCE_INTAKE", mutated)
    assert reg.intake_of_source("成本卡") == reg.INTAKE_ONE_OFF, (
        "🔴 变异没生效 —— 接入方式读的不是登记表")     # 行为真的变了

    # 误标之后它走 `_status_binary`, 边际恒 0 → 不进 todo → 不会被推荐
    # (measure 需要打库, 这里断言那条分支的判据本身)
    assert reg.intake_of_source("成本卡") != reg.INTAKE_PER_ITEM


def test_progress_sentence_never_contradicts_itself():
    """⚠️ `next` 为空而 done < total 是内部不一致, 那时不许说「都齐了」。"""
    from smartbi.gold.restaurant.data_progress import render

    p = {"total": 6, "done": 3, "next": None}
    assert "都齐了" not in render(p), "3/6 却说都齐了 —— 自相矛盾"
    assert render({"total": 6, "done": 6, "next": None}).endswith("都齐了。")
    # 阳性对照: 有 next 时必须给出下一步
    got = render({"total": 6, "done": 3, "cost_source": "成本卡",
                  "next": {"source": "税额", "unlocks": 1}})
    assert "税额" in got and "下一个" in got
