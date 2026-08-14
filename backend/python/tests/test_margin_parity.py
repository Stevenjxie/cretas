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
    # 阳性对照: 该说的还在。⚠️ 2026-08-14 去重后进度感**不再说建议**,
    #    所以这条对照改成盯「没补的那几类要点名」——「下一步」由 T2 那一处说。
    got = render({"total": 6, "done": 3, "missing": ["税额"]})
    assert "税额" in got and "完全没有数据" in got
    assert "下一个" not in got, "进度感又开始说建议了 —— 那句话只有 T2 一处出"


def test_the_next_step_sentence_has_exactly_one_home():
    """🔴 owner 2026-08-14 去重: 「先补这 N 道 → 覆盖率 a% 到 b%」只有一个出处。

    改之前同一屏上说了两遍、措辞还不一样 —— **「两份会漂」漂进了面向用户的正文。**
    分工: T2 说「做什么」, 进度感说「我在哪」。
    """
    from smartbi.gold.restaurant.data_progress import render
    from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps

    gaps = [{"name": "A", "revenue": 300.0}, {"name": "B", "revenue": 200.0},
            {"name": "C", "revenue": 100.0}]
    view = render({"total": 6, "done": 2, "missing": ["税额", "损耗盘点"],
                   "next": {"source": "成本卡"}, "cost_source": "成本卡",
                   "cost_gaps": gaps, "coverage_ratio": 0.402,
                   "coverage_denominator": 10000.0})
    offer = offers_for_cost_gaps(gaps, 0.402, 10000.0)[0]["text"]

    # 阳性对照: 建议那一处**确实**在说这句话(否则下面的阴性断言恒真)
    assert "先补这" in offer and "提到约" in offer, offer
    # 进度感那一处**不许**再说一遍
    for word in ("先补这", "提到约", "最划算"):
        assert word not in view, f"进度感又把建议说了一遍: {view!r} 命中 {word!r}"
    # 但进度感该说的还在
    assert "6 类里的 2 类" in view and "完全没有数据" in view


def test_screen_says_each_thing_once():
    """整屏级别的去重 —— ⛔ 不许两段共享同一组数字。

    ⚠️ 判据是「同一个覆盖率区间出现几次」: 40.2%→47.7% 这对数只该出现一次。
    """
    from smartbi.gold.restaurant.data_progress import render
    from smartbi.gold.restaurant.fill_offers import offers_for_cost_gaps

    gaps = [{"name": "A", "revenue": 300.0}]
    body = "\n\n".join([
        offers_for_cost_gaps(gaps, 0.402, 10000.0)[0]["text"],
        render({"total": 6, "done": 2, "missing": ["税额"],
                "next": {"source": "成本卡"}, "cost_source": "成本卡",
                "cost_gaps": gaps, "coverage_ratio": 0.402,
                "coverage_denominator": 10000.0}),
    ])
    assert body.count("40.2%") == 1, f"同一个覆盖率在一屏上出现多次:\n{body}"


# ═══════════════════════════════════════════════════════════════════════════
# 整屏排序 + 限定语贴着数字（owner 2026-08-14）
# 🔴 判据: **合并不许变成删减** —— 三条事实各有一条断言, 一条都不许消失。
# ═══════════════════════════════════════════════════════════════════════════
def _profit_cell():
    """⚠️ basis 用**产品自己的两个常量拼**, ⛔ 不在夹具里写死一个串 ——
    写死就会像 2026-08-14 那次: 产品补了「折扣摊派」而夹具还是旧的, 于是
    断言测的是一个生产上不存在的形状(形态 B‴)。"""
    from smartbi.gold.restaurant.generic_executor import (
        _COST_CARD_BASIS, _DISCOUNT_ALLOC_BASIS, CellResult)
    return CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 124071.85}], (), "", "ESTIMATED",
        f"{_COST_CARD_BASIS}、{_DISCOUNT_ALLOC_BASIS}", 0.402, (),
        ({"name": "A", "revenue": 300.0},), 10000.0)


def test_the_most_important_number_is_first():
    """🔴 这一屏叫「今天怎么样」, 店长问的是「今天赚没赚」—— 那就是毛利。

    ⚠️ 而毛利恰好是**最不确定**的那个。把它排第三本身就是一种回避。
    """
    from smartbi.gold.restaurant.daily_close import DAILY_CLOSE_CELLS

    assert DAILY_CLOSE_CELLS[0][0] == "gross_profit", (
        f"第一行不是毛利, 是 {DAILY_CLOSE_CELLS[0][0]} —— 把最重要的数往后排")


def test_qualifier_rides_on_the_number_not_a_pile_below():
    """限定语必须和它限定的那个数**在同一段**。"""
    from smartbi.gold.restaurant.generic_answer import render

    text = render(_profit_cell(), "今天")
    first = text.split("\n\n")[0]
    assert "124,071.85" in first, "第一段不是那个数"
    assert "（" in first and "）" in first, f"限定语没跟着数字: {first}"


#: 限定语必须说全的**估算成分**与其它事实 -> (人话名, 在正文里怎么认出它)。
#: **唯一一份** —— ⛔ 两条路各写一份就是「守卫也有两份」, 那正是我们在修的病。
#:
#: 🔴 2026-08-14 订正: 原来叫 REQUIRED_FACTS, **漏了折扣摊派**。
#:    `理论用量` 管**成本侧**, `折扣摊派` 管**营收侧** —— 两个不同的估算成分,
#:    而闸只覆盖了一个。owner 靠「两条路 diff=0.0, 一边摊了一边没摊不可能相等」
#:    读出来的, **不是这组断言自己发现的**。
#: 📌 由此得到的判据: **一组「必须都在」的断言, 它自己的完备性没有任何东西在守。**
#:    ⇒ 下面 `test_the_fact_list_covers_every_estimation_component` 补这一条。
REQUIRED_FACTS = (
    ("这个数不是利润", "未扣人工"),
    ("覆盖多少", "% 的营收"),
    ("为什么只能是估的(成本侧)", "理论用量"),
    ("折扣怎么处理的(营收侧)", "摊派的折扣"),
    ("要怎样才变实", "盘一次库"),
)


def assert_required_facts_present(first_segment, where):
    """两条路共用这一个断言函数。"""
    for name, needle in REQUIRED_FACTS:
        assert needle in first_segment, (
            f"[{where}] 丢了「{name}」那条事实: {first_segment!r}")


def test_merging_did_not_drop_any_required_fact():
    """🔴 三条(+行动那条)事实各有一条断言 —— 合并之后一条都不许消失。

    · 这个数是什么      caveat_short        未扣人工、房租、水电
    · 覆盖多少          coverage_ratio      只算了 40.2% 的营收
    · 为什么只能是估的  estimation_basis    用的是成本卡的理论用量
    · 要怎样才变实      irreducible_reason  要更准得盘一次库
    """
    from smartbi.gold.restaurant.generic_answer import render

    first = render(_profit_cell(), "今天").split("\n\n")[0]
    assert_required_facts_present(first, "日结")


@pytest.mark.parametrize("victim,expect_gone", [
    ("caveat_short", "未扣人工"),
    ("estimation_basis", "理论用量"),
    ("irreducible", "盘一次库"),
])
def test_mutation_removing_one_fact_turns_its_assertion_red(
        monkeypatch, victim, expect_gone):
    """🔴 变异对照: 拿掉其中一条事实, 对应断言必须红。

    ⚠️ 先证明**行为变了**(那句话真的从正文里消失了)再看断言。
    """
    from smartbi.gold.restaurant import metric_registry as reg
    from smartbi.gold.restaurant.generic_answer import render

    before = render(_profit_cell(), "今天").split("\n\n")[0]
    assert expect_gone in before                      # 阳性对照: 改之前在

    if victim == "caveat_short":
        import dataclasses
        mutated = dict(reg.DERIVED)
        mutated["gross_profit"] = dataclasses.replace(
            mutated["gross_profit"], caveat_short="")
        monkeypatch.setattr(reg, "DERIVED", mutated)
        after = render(_profit_cell(), "今天").split("\n\n")[0]
    elif victim == "estimation_basis":
        from smartbi.gold.restaurant.generic_executor import CellResult
        cell = _profit_cell()
        after = render(CellResult(
            cell.metric_key, cell.metric_label, cell.dimension_key,
            cell.aggregation_key, cell.unit, cell.rows, (), "",
            "MEASURED", "", cell.coverage_ratio), "今天").split("\n\n")[0]
    else:
        monkeypatch.setattr(reg, "IRREDUCIBLE_ESTIMATE_COLUMNS", {})
        after = render(_profit_cell(), "今天").split("\n\n")[0]

    assert expect_gone not in after, (
        f"🔴 变异没生效 —— 「{expect_gone}」还在, 说明它不是从那个来源取的")


# ═══════════════════════════════════════════════════════════════════════════
# 两条路的**开头必须一致**（owner 2026-08-14，前置三退回后的新判据）
# ═══════════════════════════════════════════════════════════════════════════
def test_both_paths_share_one_headline_implementation():
    """⛔ 问答不许复制日结的拼装 —— 复制就是第三份。

    改之前问答第 3 行是「已覆盖部分毛利 …，加权毛利率 82.5%」**零限定**，
    而 82.5% 是在 40.2% 上算的 —— 店长最可能的读法是「这生意真赚钱」。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "render_headline" in src, "问答没调共用的那一处 —— 又长出一份"
    # 阴性: 旧的零限定那一行不许回来
    assert "加权毛利率 **{margin_text}**" not in src, (
        "🔴 合计毛利率又单独成行了 —— 它会被读成「这个店的毛利率」")


def test_the_qa_headline_carries_the_same_required_facts():
    """🔴 判据三: 与日结**共用同一组断言**, ⛔ 不许各写一份。"""
    from smartbi.gold.restaurant.generic_answer import render_headline

    first = render_headline(_profit_cell(), "2026-08-12 当天")
    assert_required_facts_present(first, "问答")
    # 阳性对照: 数字本身在
    assert "124,071.85" in first


def test_aggregate_margin_rate_never_gets_its_own_line():
    """🔴 合计毛利率**不单独成行**。

    ⚠️ 单品毛利率保留(每道菜都有成本卡, 有依据); 这里禁的是**合计**那个 ——
       一个在 40.2% 营收上算出来的比率, 单独成行就会被读成「这个店的毛利率」。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    for line in src.splitlines():
        s = line.strip()
        if not s.startswith('f"') or "#" in s.split('f"')[0]:
            continue
        if "毛利率" in s and "margin_text" in s:
            assert False, f"🔴 合计毛利率又单独成行: {s}"


def test_qa_actions_come_from_fill_offers_not_hand_written():
    """判据五: 「建议动作」来自 `build_fill_offers`。"""
    from smartbi.gold.restaurant import restaurant_ops_router as router
    from smartbi.gold.restaurant.generic_executor import CellResult

    cell = CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1.0}], (), "", "ESTIMATED", "成本卡的理论用量", 0.4,
        (), ({"name": "顺德干蒸鲜排骨", "revenue": 500.0},), 1000.0)
    # ⚠️ `_build_qa_fill_offers` 返回**结构化 offer**(按钮读它的 `kind`),
    #    正文那几行由 `_offer_texts` 从同一份产出渲染。
    #    ⛔ 别把它改回「直接返回字符串」—— 那正是按钮从上线起一次都没出现过
    #      的原因(`offer.get(...)` 抛异常, 被 resolver 的 except 吞掉)。
    lines = router._offer_texts(router._build_qa_fill_offers(cell))
    assert any("顺德干蒸鲜排骨" in ln for ln in lines), (
        "建议动作里没有具体菜名 —— 又退回泛泛之词")
    assert any("提到约" in ln for ln in lines), "没给覆盖率增量"
    # ⛔ 拿不到开价时**不留空标题**: 通用那三条仍在兜底
    empty = router._offer_texts(router._build_qa_fill_offers(CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1.0}], (), "", "MEASURED", "")))
    assert empty, "开价为空时连兜底建议都没有 —— 会留一个空的「建议动作:」"


def test_the_fact_list_covers_every_estimation_component():
    """🔴 判据: **一组「必须都在」的断言, 它自己的完备性没有任何东西在守。**

    2026-08-14 实测: `折扣摊派` 漏了整整一轮 —— 是 owner 靠「两条路 diff=0.0,
    一边摊了一边没摊不可能相等」读出来的, **不是这组断言自己发现的**。

    ⇒ 这条对照: 限定语里出现的**每一个估算成分**(basis 里用顿号分开的那些),
      都必须在 `REQUIRED_FACTS` 里有一条认得它。
    ⚠️ 它只覆盖 basis 那一维; `caveat_short` / 覆盖率 / 不可消除原因三条
      仍然靠人维护 —— 设计卡上写明了, 与那张列标注同等待遇。
    """
    from smartbi.gold.restaurant.generic_executor import (
        _COST_CARD_BASIS, _DISCOUNT_ALLOC_BASIS)

    components = [_COST_CARD_BASIS, _DISCOUNT_ALLOC_BASIS]
    assert components, "一个估算成分都没有 —— 这条断言会恒绿"
    needles = [n for _name, n in REQUIRED_FACTS]
    for comp in components:
        assert any(n in comp for n in needles), (
            f"🔴 估算成分「{comp}」在 REQUIRED_FACTS 里没有对应的断言 —— "
            f"限定语说了它, 而没有任何东西守它在不在")


def test_progress_line_stays_out_of_the_qa_body():
    """🔴 owner 2026-08-14 裁定: 进度感**不进问答正文**。

    「你的数据补到 6 类里的 2 类了」是**全局状态**, 属于「今天整体怎么样」那一屏。
    问答回答的是他问的那个问题, ⛔ 不该附带全局播报。
    ⚠️ 但**留在结构化输出里** —— 将来前端做常驻进度条会用到, 那才是它该待的地方。

    📌 判据: **附带信息进不进正文, 看它是不是在改善这一次的回答。**
       「先补这 3 道」**要**进(它直接改善他刚问的那个答案);
       「6 类补了 2 类」**不进**(它跟这次问的没关系)。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router.resolve_gross_margin)
    assert "data_progress" not in src, (
        "问答正文里接了进度感 —— 那是全局播报, 不改善这一次的回答")
    # 阳性对照: 日结那一屏**该**有它, 且结构化字段在
    from smartbi.gold.restaurant import daily_close as dc
    dc_src = inspect.getsource(dc.build_daily_close)
    assert "data_progress" in dc_src, "日结那一屏反倒没有 —— 它才是它该待的地方"
    assert '"progress_line"' in dc_src, "结构化输出里没带出去, 前端做进度条拿不到"


# ═══════════════════════════════════════════════════════════════════════════
# T1/T2 追问按钮（owner 2026-08-14，只做问答那条路）
# ═══════════════════════════════════════════════════════════════════════════
_SAMPLE_USED_DIMS = ("all", "product")


def _sample_body_and_offers():
    """正文按**生产那条路的顺序**拼: 主体 + 开价 + 「还能怎么拆」。

    🔴 最后那一句不是装饰: 按钮的 label(「按品牌」)要**逐字在正文里**,
       而它是由 `drilldown_note()` 写进正文的。夹具漏掉它 = 夹具和生产两种拼法,
       那时这条断言测的是一个生产上不存在的正文。
       (2026-08-14 实测: 加严 label 那条判据后, 这个夹具当场红。)
    """
    from smartbi.gold.restaurant.follow_up_actions import drilldown_note

    body = ("今天全部门店毛利合计 **¥124,071.85**（估算：只算了 40.2% 的营收）。"
            "\n\n> 先补这 3 道的成本卡（A、B、C）——能算进毛利的营收会从 40.2% 提到约 47.7%")
    offers = [{"kind": "fill_dishes", "dishes": (),
               "text": "先补这 3 道的成本卡（A、B、C）——能算进毛利的营收会从 40.2% 提到约 47.7%"}]
    note = drilldown_note("gross_profit", _SAMPLE_USED_DIMS)
    if note:
        body = f"{body}\n\n{note}"
    return body, offers


def test_every_button_points_at_something_the_body_already_says():
    """🔴 边界 1: **按钮不许说正文没说的话** —— 它是入口, 不是新内容。"""
    from smartbi.gold.restaurant.follow_up_actions import (
        assert_actions_anchored, build_actions)

    body, offers = _sample_body_and_offers()
    actions = build_actions(metric_key="gross_profit",
                            used_dimensions=("all", "product"),
                            offers=offers, answer_text=body)
    assert actions, "一个按钮都没有 —— 下面的断言会恒真"
    assert_actions_anchored(actions, body)
    # 变异: 塞一个正文里没有的 anchor, 那道核对必须红
    with pytest.raises(AssertionError):
        assert_actions_anchored(
            [{"label": "编的", "anchor": "正文里根本没有这句话"}], body)


def test_no_buttons_on_failure_with_a_positive_control():
    """🔴 边界 2: 故障时不出按钮。

    ⛔ 这条**必须**配阳性对照 —— 「故障时没按钮」在「永远没按钮」时同样成立。
       本仓踩过这个恒真式(阴性断言没有对照)。
    """
    from smartbi.gold.restaurant.follow_up_actions import build_actions

    body, offers = _sample_body_and_offers()
    kw = dict(metric_key="gross_profit", used_dimensions=("all", "product"),
              offers=offers, answer_text=body)

    # 阳性对照: 正常时**出得来**
    assert build_actions(**kw), "正常时就没有按钮 —— 下面那条阴性断言不作数"
    # 每一种故障信号都要能抑制
    for bad in ({"no_data": True}, {"rbac_masked": True},
                {"unavailable": "x"}, {"clarification": True}):
        assert build_actions(meta=bad, **kw) == (), f"{bad} 时还在出按钮"


def test_backend_sorts_by_priority_before_sending():
    """🔴 边界 3: 上限 4 是前端截的 —— **后端要排好序再送**。

    ⛔ 送一堆让前端 `.slice(0,4)`, 等于优先级由另一个仓的一行代码决定。
    """
    from smartbi.gold.restaurant.follow_up_actions import (
        MAX_ACTIONS, TYPE_PRIORITY, build_actions)

    body, offers = _sample_body_and_offers()
    actions = build_actions(metric_key="gross_profit",
                            used_dimensions=("all",), offers=offers,
                            answer_text=body)
    assert len(actions) <= MAX_ACTIONS, "后端没截断"
    ranks = [TYPE_PRIORITY[a["type"]] for a in actions]
    assert ranks == sorted(ranks), f"没按优先级排: {[a['type'] for a in actions]}"
    assert actions[0]["type"] == "T2", "T2 补数据不在最前面 —— 卡上是 T2 > T3 > T1"


def test_mutating_the_priority_turns_the_order_assertion_red(monkeypatch):
    """变异对照: 打乱优先级, 上面那条必须红。⚠️ 先证明行为真的变了。"""
    from smartbi.gold.restaurant import follow_up_actions as fa

    body, offers = _sample_body_and_offers()
    kw = dict(metric_key="gross_profit", used_dimensions=("all",),
              offers=offers, answer_text=body)
    before = [a["type"] for a in fa.build_actions(**kw)]
    assert before[0] == "T2"

    monkeypatch.setattr(fa, "TYPE_PRIORITY", {"T2": 9, "T3": 1, "T1": 0, "T4": 3})
    after = [a["type"] for a in fa.build_actions(**kw)]
    assert after[0] == "T1", "🔴 变异没生效 —— 排序读的不是那张表"
    assert before != after, "变异前后顺序没变, 这条对照是空转"


def test_drillable_dimensions_are_reverse_looked_up_not_hand_written():
    """判据三: T1 的维度是**反查**出来的。

    ⚠️ 「这次回答已经用了哪些维度」是那段回答代码自己的性质, 登记表不知道 ——
       由调用方声明; **候选集合**仍然反查, 那一半不许手写。
    """
    from smartbi.gold.restaurant import metric_registry as reg
    from smartbi.gold.restaurant.follow_up_actions import _drillable_dimensions

    got = set(_drillable_dimensions("gross_profit", ("all", "product")))
    assert got, "一个可下钻维度都没有 —— 断言会恒真"
    assert "all" not in got, "`all` 是不分组, 不是可下钻的对象"
    assert "product" not in got, "已经用过的维度还在推荐"
    # 反查链: 候选来自登记表, 且派生量取两个输入的**交集**
    left = set(reg.METRICS["revenue"].dimensions)
    right = set(reg.METRICS["food_cost"].dimensions)
    assert got <= (left & right), (
        "推荐了一个两边不都支持的维度 —— 点下去会拒答")


# ═══════════════════════════════════════════════════════════════════════════
# 100% 覆盖那条分支 —— MOCK_REST 造缺口之后，这里是它**唯一**的实测环境
# ═══════════════════════════════════════════════════════════════════════════
def test_full_coverage_drops_the_coverage_clause():
    """🔴 满覆盖时「只算了 X% 的营收」**必须消失**。

    ⚠️ 这条分支原来靠 MOCK_REST(100% 覆盖)在生产上被走到。2026-08-14 给
       MOCK_REST 造了缺口(覆盖率 100% → 65.3%)之后, **生产上再没人走它** ——
       ⛔ 不许留下「改完就没人验 100% 那条路」的状态, 所以在这里接住。

    📌 它当初正是这么发现的: 满覆盖时打出「只算了 100.0% 的营收」, 被一条
       几轮之前为了防「无条件的废话」写的阴性对照抓到。**一道老闸守住了一个
       当时还不存在的分支** —— 这条用例就是那件事的固化。
    """
    from smartbi.gold.restaurant.generic_answer import render_headline
    from smartbi.gold.restaurant.generic_executor import (
        _COST_CARD_BASIS, CellResult)

    def _cell(cov):
        return CellResult(
            "gross_profit", "毛利", "all", "summary", "money",
            [{"gross_profit": 475623.83}], (), "", "ESTIMATED",
            _COST_CARD_BASIS, cov)

    full = render_headline(_cell(1.0), "今天")
    assert "只算了" not in full, f"满覆盖还在说「只算了 100.0%」: {full}"
    # ⛔ 其余几条事实**不许**跟着消失 —— 满覆盖不代表它就是账上的数
    assert "理论用量" in full and "未扣人工" in full and "盘一次库" in full

    # 阳性对照: 不满覆盖时那句**必须**在(否则上面那条阴性断言恒真)
    partial = render_headline(_cell(0.653), "今天")
    assert "只算了 65.3% 的营收" in partial, partial


def test_mutating_the_full_coverage_threshold_turns_it_red(monkeypatch):
    """变异对照: 把满覆盖阈值改掉, 上面那条必须红。⚠️ 先证明行为变了。"""
    from smartbi.gold.restaurant import provenance as prov
    from smartbi.gold.restaurant.generic_answer import render_headline
    from smartbi.gold.restaurant.generic_executor import (
        _COST_CARD_BASIS, CellResult)

    cell = CellResult(
        "gross_profit", "毛利", "all", "summary", "money",
        [{"gross_profit": 1.0}], (), "", "ESTIMATED", _COST_CARD_BASIS, 1.0)
    assert "只算了" not in render_headline(cell, "今天")        # 改之前是对的

    # 变异: 阈值抬到 1.5 → 100% 不再算「满」→ 那句话会冒出来
    # ⛔ 只打 `provenance` 那一处 —— `_inline_qualifier` 是**函数内 import**,
    #    调用时才从模块取, 所以打得到。
    #    ⚠️ 第一版还顺手 `setattr(ga, "_FULL_COVERAGE", ..., raising=False)`,
    #    那会**凭空造一个没人读的属性** —— 变异看起来生效了而其实是另一处起的作用。
    monkeypatch.setattr(prov, "_FULL_COVERAGE", 1.5)
    after = render_headline(cell, "今天")
    assert "只算了 100.0% 的营收" in after, (
        "🔴 变异没生效 —— 那个阈值不是从 provenance 取的")


# ── T1 接通 (owner 2026-08-14 三条裁定) ────────────────────────────────────
def test_buttons_land_on_the_existing_suggested_followups_contract():
    """🔴 判据一: 按钮走既有的 `{label, question}`, **前端一行不改**就能读到。

    第一版新建了 `OpsAnswer.actions`(带 `payload`), 实测**三层都断**:
    没有消费者 / `fill_dishes` 没有 handler / 前端读的是 `suggestedFollowups`。
    ⇒ owner: 复用=1 份, 新增=2 份(其中一份没人读)。撤掉新建的那份。
    """
    from smartbi.gold.restaurant.follow_up_actions import (
        build_actions, to_followups)

    body, offers = _sample_body_and_offers()
    actions = build_actions(metric_key="gross_profit",
                            used_dimensions=_SAMPLE_USED_DIMS,
                            offers=offers, answer_text=body)
    followups = to_followups(actions)
    assert followups, "一个都没有 —— 下面的断言会恒真"
    for item in followups:
        # 前端 `normalizeFollowUpActions` 读 `item.question ?? item.text ?? item.label`
        assert set(item) == {"label", "question", "type"}, (
            f"多了或少了键: {item} —— 前端只认 label/question, "
            f"`anchor` 那种内部字段不许送出去")
        assert item["label"] and item["question"], item
    assert followups[0]["type"] == "T2", (
        f"T2 没排第一: {followups} —— 整体还要被前端 .slice(0,4) 截, "
        f"排后面等于被静默切掉")


def test_opsanswer_no_longer_carries_its_own_actions_contract():
    """🔴 判据一的另一半: 那个没人读的字段**真的删了**。

    ⛔ 只加新路径不删旧字段 = 契约从 2 份变成 3 份。
    """
    import dataclasses
    from smartbi.gold.restaurant.restaurant_ops_router import OpsAnswer

    names = {f.name for f in dataclasses.fields(OpsAnswer)}
    assert "actions" not in names, (
        f"`OpsAnswer.actions` 还在: {sorted(names)} —— 它没有任何消费者")


def test_the_three_hardcoded_java_followups_are_gone():
    """🔴 判据一: 三个硬编码问句已删(Java 两处实现)。

    「老板今天怎么用这张报表做决定？」这类话**换哪个报表都一样**, 所以它们
    不指向正文里的任何东西 —— 正是「按钮不许说正文没说的话」要挡的。
    ⚠️ 只数**代码**: 注释里会提到这几句话(说明为什么删), 不剥注释的话
       这条闸会把自己的说明也测进去(本仓记过这个形态)。
    """
    import pathlib
    import re

    root = pathlib.Path(__file__).resolve().parents[2] / "java"
    hits = []
    for path in root.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        # 剥行注释与块注释
        code = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        for phrase in ("老板今天怎么用这张报表做决定",
                       "哪些动作今天先不要做",
                       "明天看哪三个数判断有没有效果"):
            if phrase in code:
                hits.append(f"{path.name}: {phrase}")
    assert not hits, "硬编码问句还在代码里:\n  " + "\n  ".join(hits)


def test_drilldown_note_says_something_in_both_states():
    """🔴 判据四: 维度耗尽时**明说一句**; 没耗尽时说还能怎么拆。

    ⚠️ 两种状态都要说话 —— 只在耗尽时说的话, 「还能拆」那条路上的人
       读不到任何提示, 而**日结推送那种形态没有按钮只有正文**。
    """
    from smartbi.gold.restaurant.follow_up_actions import (
        drilldown_note, is_exhausted, _drillable_dimensions)

    still = drilldown_note("gross_profit", ("all", "product"))
    assert still and "还能" in still, f"还能下钻时没说话: {still!r}"
    assert not is_exhausted("gross_profit", ("all", "product"))

    every = ("all", *_drillable_dimensions("gross_profit", ()))
    assert is_exhausted("gross_profit", every), "把所有维度都用掉了却说还能拆"
    done = drilldown_note("gross_profit", every)
    assert done and "都看过了" in done, f"耗尽时没说话: {done!r}"
    assert "还能" not in done, f"耗尽了还在说「还能」: {done!r}"


def test_mutating_the_exhaustion_check_turns_the_note_red(monkeypatch):
    """🔴 判据四的变异: 让「耗尽」永远为假, 上面那条必须红。

    ⚠️ 打的是 `_drillable_dimensions`(判据的来源), ⛔ 不是直接改那句话 ——
       改字符串只能证明断言在读那个字符串, 证明不了它在守「拆完了」这个行为。
    """
    from smartbi.gold.restaurant import follow_up_actions as fa

    monkeypatch.setattr(fa, "_drillable_dimensions", lambda *a, **k: ("store",))
    every = ("all", "store", "product", "channel", "staff", "meal_period",
             "table", "date", "weekday", "hour", "city", "brand", "category")
    assert not fa.is_exhausted("gross_profit", every), "变异没生效"
    with pytest.raises(AssertionError):
        done = fa.drilldown_note("gross_profit", every)
        assert "都看过了" in done, f"耗尽时没说话: {done!r}"


def test_t2_button_question_matches_what_the_router_can_answer():
    """🔴 判据三的前置: T2 按钮发的问句, 路由**接得住**。

    ⛔ 两处不一致 = 按钮变哑弹(点下去拒答), 而它看起来完全正常 ——
       这正是这一轮在修的形态。
    """
    from smartbi.gold.restaurant.follow_up_actions import T2_FILL_QUESTION
    from smartbi.gold.restaurant.restaurant_ops_router import (
        _asks_missing_cost_card)

    assert _asks_missing_cost_card(T2_FILL_QUESTION), (
        f"T2 按钮发 {T2_FILL_QUESTION!r}, 而 router 的判据认不出它")
    # 阴性对照: 成本**排行**那种问法不许落进缺卡分支
    for other in ("食材成本最高的菜是哪些", "配方成本前 10 名", "这个月毛利多少"):
        assert not _asks_missing_cost_card(other), (
            f"{other!r} 被误判成「问缺卡」—— 它会拿到一份缺卡清单, 答非所问")


def test_the_buttons_are_built_from_the_real_offer_objects():
    """🔴🔴 按钮的输入必须是 **resolver 真的会传进去的那个对象**。

    ## 这条断言是一整轮缺陷的产物

    `_build_qa_fill_offers` 原来返回 `List[str]`(把 offer 拍平成文案), 而
    `build_actions` 读 `offer["kind"]` / `offer["text"]` —— 于是它每次都
    `AttributeError: 'str' object has no attribute 'get'`, 被 resolver 的
    `except` 吞掉, 日志写「追问按钮生成失败, 本次不带按钮」。
    **T1/T2 按钮从上线起一次都没出现在生产上**, 而单测因为**自己喂 dict** 全绿。

    ⇒ 判据: 一组按钮断言里, 至少一条的输入要**由产品那侧的函数产出**,
      ⛔ 不许全部自己捏。自己捏的夹具只能证明「形状对上时它工作」。
    """
    from smartbi.gold.restaurant.follow_up_actions import build_actions
    from smartbi.gold.restaurant.restaurant_ops_router import (
        _build_qa_fill_offers, _offer_texts)

    cell = _profit_cell()                      # 真的 CellResult
    offers = _build_qa_fill_offers(cell)       # 真的产出, ⛔ 不是我捏的
    assert offers, "开价一条都没有 —— 下面的断言会恒真"
    for offer in offers:
        assert isinstance(offer, dict), (
            f"resolver 传给按钮的是 {type(offer).__name__} 而不是 dict —— "
            f"`build_actions` 会 AttributeError, 而它被 except 吞掉, "
            f"表现是**按钮永远不出现**")
        assert offer.get("kind"), f"offer 没有 kind, 按钮认不出类型: {offer}"

    # 正文那几行仍然渲染得出来(同一份产出, 两个消费者)
    texts = _offer_texts(offers)
    assert texts and all(isinstance(t, str) and t for t in texts), texts

    body = "\n\n".join([texts[0]])
    actions = build_actions(metric_key="gross_profit",
                            used_dimensions=_SAMPLE_USED_DIMS,
                            offers=offers, answer_text=body)
    assert any(a["type"] == "T2" for a in actions), (
        f"用真实 offer 建不出 T2 按钮: {actions}")


def test_t1_only_offers_dimensions_the_resolver_can_actually_answer():
    """🔴🔴 判据: 按钮给的每个维度, resolver **声明得出来**。

    ## 这条是 4 颗哑弹换来的

    只按 `Metric.dimensions` 反查, MOCK_REST 上给出了
    「按品牌」「按菜品类别」「按渠道」「按城市」四颗按钮 —— **点下去全是拒答**:
    「这次是想看某道菜、某家门店，还是全店汇总？」。
    因为 `RESTAURANT_OPS_GROSS_MARGIN` 声明的能力只有 `{dish, time}`。

    ⚠️ 登记表说「毛利能按品牌分组」是**对的**(通用执行器真算得出来),
       但**问句路由不到那一层** —— 能算 ≠ 问得到。
    ⛔ `follow_up_actions` 文件里早写着「取并集会给出一个算不出来的组合
       (点下去就是拒答)」, 却只用在派生量的两个输入上。**同一条判据漏了一处。**
    """
    from smartbi.gold.restaurant.follow_up_actions import (
        _answerable_dimensions, _drillable_dimensions)
    from smartbi.gold.restaurant.metric_registry import (
        DIMENSIONS, canonical_dimensions)
    from smartbi.gold.restaurant.restaurant_intent_service import (
        _RESOLVER_DIMENSIONS)

    code = "RESTAURANT_OPS_GROSS_MARGIN"
    # ⚠️ 方向: `canonical_dimensions` 是**登记表 key → 管线名**(product→dish),
    #    而 `_RESOLVER_DIMENSIONS` 写的是管线名。拿它去归一 declared 是空操作,
    #    会把这条断言变成「候选必须是空集」—— 实测踩过。
    declared = _RESOLVER_DIMENSIONS[code]
    answerable = {k for k in DIMENSIONS
                  if canonical_dimensions((k,))[0] in declared}
    assert answerable == set(_answerable_dimensions(code)), (
        "断言和实现算出来的「能答的维度」不一样 —— 其中一个是错的")
    offered = set(_drillable_dimensions("gross_profit", ("all",), code))
    assert offered, "一个可下钻维度都没有 —— 下面的断言会恒真"
    assert offered <= answerable, (
        f"按钮给了 resolver 答不出的维度: {sorted(offered - answerable)}\n"
        f"resolver 声明能答: {sorted(answerable)}\n"
        f"⇒ 这些按钮点下去是拒答")

    # 阳性对照: 不收窄时**确实**会多给出维度 —— 否则这条断言测不到收窄
    wide = set(_drillable_dimensions("gross_profit", ("all",), None))
    assert wide - answerable, (
        "不收窄时也没有多余维度 —— 那这条断言守不住任何东西")


# ── 降级不是问题, 静默才是 (owner 2026-08-14) ──────────────────────────────
def test_programming_errors_are_counted_not_silently_degraded():
    """🔴 编程错误被吞掉时**计数器要动**, 而且打 ERROR 不是 WARNING。

    ## 这条断言是一整轮缺陷的产物

    `except Exception` + `logger.warning("本次不带按钮")` 把一个
    `AttributeError` 写成了「这里没有内容」——
    **T1/T2 按钮从上线起一次都没出现过, 而没有人知道。**

    ⚠️ 降级本身是对的(按钮出不来不该让答案挂掉), 所以这条断言查的**不是**
       「它还降不降级」, 而是「它降级时留没留痕」。
    """
    from smartbi.gold.restaurant import degrade_guard as dg

    dg.reset_counters()

    def _boom():
        raise AttributeError("'str' object has no attribute 'get'")

    got = dg.degrade_on_error("t.probe", "FALLBACK", _boom, what="按钮")
    assert got == "FALLBACK", "降级没发生 —— 那会让整个答案挂掉"
    assert dg.counter("t.probe") == 1, (
        f"编程错误被吞掉了却没计数: {dg.counters()} —— 这就是静默")

    # 阳性对照 1: **预期内**的失败不计数 —— 否则计数器永远不为 0, 断言失效
    class _Expected(Exception):
        pass

    dg.degrade_on_error("t.probe2", "F", lambda: (_ for _ in ()).throw(_Expected()),
                        expected=(_Expected,), what="外部不可达")
    assert dg.counter("t.probe2") == 0, (
        "预期内的失败也计数了 —— 计数器会永远不为 0, 那条断言就失效了")

    # 阳性对照 2: 正常路径不计数
    assert dg.degrade_on_error("t.probe3", "F", lambda: "OK") == "OK"
    assert dg.counter("t.probe3") == 0
    dg.reset_counters()


def test_the_real_button_path_swallows_no_programming_error():
    """🔴 正常路径跑完, 三个降级点**一个编程错误都没吞**。

    ⛔ 这条要跑在**产品那侧的函数**上 —— 上一轮的教训正是「单测自己喂 dict
       所以一直全绿, 而生产上每次都 AttributeError」。
    """
    from smartbi.gold.restaurant import degrade_guard as dg
    from smartbi.gold.restaurant import restaurant_ops_router as router

    dg.reset_counters()
    cell = _profit_cell()
    offers = router._build_qa_fill_offers(cell)          # 真的产出
    body = "\n\n".join(router._offer_texts(offers)[:1])
    note = router._drilldown_note("gross_profit", _SAMPLE_USED_DIMS,
                                  "RESTAURANT_OPS_GROSS_MARGIN")
    actions = router._build_follow_up_actions(
        offers=offers, answer_text=f"{body}\n\n{note}",
        used_dimensions=_SAMPLE_USED_DIMS,
        resolver_code="RESTAURANT_OPS_GROSS_MARGIN")

    assert offers and note and actions, (
        f"正常路径没产出东西, 下面那条断言会恒真: "
        f"offers={len(offers)} note={note!r} actions={len(actions)}")
    dg.assert_no_silent_programming_errors(
        router.DEGRADE_QA_OFFERS, router.DEGRADE_DRILL_NOTE,
        router.DEGRADE_FOLLOWUP_ACTIONS)


def test_mutating_the_offer_shape_makes_the_counter_fire(monkeypatch):
    """🔴 变异: 把 offer 拍回字符串(**上一轮真实的缺陷形态**), 计数器必须动。

    ⚠️ 打的是 `_build_qa_fill_offers` 的**返回形状**, ⛔ 不是直接 raise ——
       后者只证明 guard 会计数, 证明不了它守着**这个**缺陷。
    """
    from smartbi.gold.restaurant import degrade_guard as dg
    from smartbi.gold.restaurant import restaurant_ops_router as router

    dg.reset_counters()
    cell = _profit_cell()
    real = router._build_qa_fill_offers(cell)
    flattened = [str(o.get("text") or "") for o in real]   # ← 上一轮就是这样
    actions = router._build_follow_up_actions(
        offers=flattened, answer_text="正文", used_dimensions=_SAMPLE_USED_DIMS,
        resolver_code="RESTAURANT_OPS_GROSS_MARGIN")
    assert actions == (), "变异没生效 —— 它本该抛 AttributeError"
    assert dg.counter(router.DEGRADE_FOLLOWUP_ACTIONS) == 1, (
        f"按钮那个降级点吞了 AttributeError 却没计数: {dg.counters()}")
    with pytest.raises(AssertionError):
        dg.assert_no_silent_programming_errors(router.DEGRADE_FOLLOWUP_ACTIONS)
    dg.reset_counters()


def test_cost_card_presence_has_exactly_one_definition():
    """🔴 「这道菜有没有成本卡」全仓**只许有一处定义**。

    改之前是两份:
        日结/通用执行器   `c.food_cost IS NOT NULL`
        问答 resolver     `has_price_data = TRUE`

    三个租户实测 0 例分叉, 所以今天两边同义 —— **但它们本来就不同义**:
    ETL 写 `food_cost` 时套着 `COALESCE(SUM(line_cost), 0)`, **永远产不出 NULL**。
    一道菜配料全无价时 ETL 给 `food_cost = 0 / has_price_data = FALSE`,
    那时前者判「有卡」(成本 0 ⇒ 毛利率 100%), 后者判「没卡」——
    **两边会给出相反的答案。**

    ⚠️ 用 AST + 剥注释, ⛔ 不数字符串: 注释里会引用这两个条件(说明为什么收敛),
       不剥的话这条闸会把自己的说明也测进去(本仓记过这个形态)。
    """
    import ast
    import io
    import pathlib
    import tokenize

    root = pathlib.Path(__file__).resolve().parents[1] / "smartbi" / "gold"
    hits = []
    for path in root.rglob("*.py"):
        if "tests" in path.parts:
            continue
        src = path.read_text(encoding="utf-8", errors="ignore")
        try:
            toks = [t for t in tokenize.generate_tokens(io.StringIO(src).readline)
                    if t.type != tokenize.COMMENT]
            code = tokenize.untokenize(toks)
            tree = ast.parse(code)
        except (SyntaxError, tokenize.TokenError, ValueError):
            continue
        for node in ast.walk(tree):          # 剥 docstring
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) \
                    and isinstance(node.value.value, str):
                node.value.value = ""
        stripped = ast.unparse(tree)
        for needle in ("food_cost IS NOT NULL", "has_price_data = TRUE",
                       "has_price_data IS TRUE"):
            if needle in stripped:
                hits.append((path.name, needle))

    from smartbi.gold.restaurant.metric_registry import COST_CARD_PRESENT_SQL
    allowed = {("metric_registry.py", "has_price_data IS TRUE")}
    stray = [h for h in hits if h not in allowed]
    assert not stray, (
        "「有没有成本卡」出现了第二份定义:\n  "
        + "\n  ".join(f"{f}: {n}" for f, n in stray)
        + f"\n⇒ 用 `metric_registry.COST_CARD_PRESENT_SQL`"
          f"（现为 {COST_CARD_PRESENT_SQL!r}）")
    assert hits, "一处都没扫到 —— 这条闸会恒绿"


def test_missing_cost_card_window_is_a_concrete_date_range():
    """🔴 T2 按钮那条答案的窗口必须落成**具体日期**。

    `_explicit_window(None, None, 30)` 返回 `(None, None, "近 30 天")` ——
    别的 resolver 把 None 交给 SQL 里的 `COALESCE(...)` 兜, 而
    `_dish_cost_facts` 要具体日期: `date >= NULL` **一行都不返回**。

    实测长相(2026-08-14, prod): T2 按钮点下去答
    「近 30 天卖过的菜**都有成本卡**，没有需要补的」，
    而同一屏的抬头正说着「4 个菜品缺少完整成本」—— **答案自己跟自己打架**。
    """
    import inspect
    from smartbi.gold.restaurant import restaurant_ops_router as router

    src = inspect.getsource(router._resolve_missing_cost_cards)
    assert "window_end = date.today()" in src and "timedelta(" in src, (
        "没有把 None 落成具体日期 —— `_dish_cost_facts` 会拿到 NULL 日期, "
        "返回 0 行, 于是永远答「都有成本卡」")

    # 行为对照: `_explicit_window` 确实会给出 None(否则上面那条守的是幻觉)
    ws, we, _t = router._explicit_window(None, None, 30)
    assert ws is None and we is None, (
        "`_explicit_window` 不再返回 None 了 —— 上面那条断言失去意义, 该删")
