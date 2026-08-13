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
