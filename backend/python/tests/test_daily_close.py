"""日结 P0 的验收。

判据（owner 2026-08-13）：
  1 三段齐：毛利数字 + 限定语 + 开价
  2 provenance = ESTIMATED 且限定语跟着变（变异：强改 MEASURED → 限定语消失）
  3 打烊触发真的发出来了（那一条在 cron/推送侧验，不在这里）
  4 三段走现有 generic_executor + registry，**写死的只有 spec**
"""
import pytest

from datetime import date

from smartbi.gold.restaurant import generic_executor as ge
from smartbi.gold.restaurant.daily_close import (
    DAILY_CLOSE_CELLS,
    build_daily_close,
    daily_close_window,
)
from smartbi.gold.restaurant.generic_answer import render
from smartbi.gold.restaurant.generic_executor import CellResult
from smartbi.gold.restaurant.metric_registry import (
    AGGREGATIONS,
    DERIVED,
    DIMENSIONS,
    METRICS,
)


@pytest.fixture(autouse=True)
def _cretas_pool_for_cost_keys(monkeypatch):
    """成本键的权威来源(运营库)在本模块里用桩。

    🔴 2026-08-13 起日结的毛利格子会去运营库解析「菜名→成本键」——
       那正是这次要修的东西(那 9 道菜的映射只在运营库里)。
    ⚠️ 不打桩的话这些用例会去连真库, 失败原因是 `asyncpg` 连接错误 ——
       **看起来像本模块在测数据库**, 实际上什么都没测到。
    ⛔ 「来源断了要显式失败」由 `smartbi/gold/tests/test_cost_key_source.py` 守,
       不靠本模块碰巧连不上来守。
    """
    class _CretasConn:
        async def fetch(self, sql, *_args):
            if "product_types" in sql:
                return [{"id": "PK_LIVE", "name": "番茄炒蛋"}]
            return []

    class _Ctx:
        async def __aenter__(self):
            return _CretasConn()

        async def __aexit__(self, *_a):
            return None

    class _Pool:
        def acquire(self):
            return _Ctx()

    async def _get_cretas_pool():
        return _Pool()

    import smartbi.config as _config
    monkeypatch.setattr(_config, "get_cretas_pool", _get_cretas_pool)


# ── 判据 4: 写死的只有 spec ────────────────────────────────────────
def test_every_hardcoded_cell_is_a_registered_combination():
    """⛔ 写死的 spec 必须**全部**是 registry 上登记过的组合。

    不成立的话日结就得自己有一套「万一算不出就凑一个」—— 那正是要避免的
    第二套算法（形态 D：两个数字都对外，店长会问为什么不一样）。
    """
    assert DAILY_CLOSE_CELLS, "spec 是空的 —— 这条断言等于空转"
    for metric_key, dimension_key, aggregation_key in DAILY_CLOSE_CELLS:
        assert metric_key in METRICS or metric_key in DERIVED, (
            f"{metric_key} 不在 registry 上 —— 日结写死了一个登记表不认识的指标")
        assert dimension_key in DIMENSIONS, f"{dimension_key} 不是登记过的维度"
        assert aggregation_key in AGGREGATIONS, f"{aggregation_key} 不是登记过的聚合"


def _code_only(module) -> str:
    """源码里**只留代码**，去掉注释和 docstring。

    🔴 2026-08-13 修: 原本直接扫 `inspect.getsource`，于是我在 docstring 里写的
       一句「非金额角色不许看到 ¥」把这道闸自己咬红了。
       闸声称在守「本模块不自己格式化金额」，实际量的是「源码里出现过这个字符」——
       **量的不是我想知道的那个数**。散文里提到 ¥ 与代码里格式化 ¥ 是两回事。

    ⚠️ 只去注释和 docstring，**保留其余字符串字面量** —— 因为 `f"¥{x}"`
       和 `"SELECT ..."` 正是要抓的东西，把字符串一并去掉这道闸就废了。
    """
    import ast
    import inspect

    tree = ast.parse(inspect.getsource(module))
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef,
                                 ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = getattr(node, "body", [])
        if (body and isinstance(body[0], ast.Expr)
                and isinstance(body[0].value, ast.Constant)
                and isinstance(body[0].value.value, str)):
            body.pop(0)
    # ast.unparse 天然不带注释 —— 注释不进 AST。
    return ast.unparse(tree)


def test_code_only_keeps_string_literals_but_drops_prose():
    """🔴 上面那个助手自己要有对照 —— 否则它可能把**该抓的**也一起去掉了。

    ⛔ 没有这条，`_code_only` 大可以返回空串，所有禁字断言全绿。
    """
    import types

    mod = types.ModuleType("m")
    mod.__loader__ = None
    src = ('"""模块 docstring 里提到 ¥ 和 SELECT。"""\n'
           '# 注释里也提到 SUM(\n'
           'def f():\n'
           '    """函数 docstring 里提到 round(。"""\n'
           '    return f"¥{1}" + "SELECT 1"\n')

    import ast
    tree = ast.parse(src)
    for node in ast.walk(tree):
        body = getattr(node, "body", [])
        if (body and isinstance(body[0], ast.Expr)
                and isinstance(body[0].value, ast.Constant)
                and isinstance(body[0].value.value, str)):
            body.pop(0)
    out = ast.unparse(tree)

    # 散文里的都没了
    assert "模块 docstring" not in out and "注释里也提到" not in out
    assert "函数 docstring" not in out
    # 🔴 但代码里真正的字符串字面量必须还在 —— 那才是这道闸要抓的
    assert "¥" in out, "把字符串字面量也去掉了 —— 这道闸就再也抓不到格式化金额"
    assert "SELECT 1" in out


def test_daily_close_hardcodes_only_the_spec_not_the_algorithm():
    """⛔ 本模块不许出现取数 / 口径 / 格式化。

    判据是**代码里不出现这些东西**（注释和 docstring 不算，见 `_code_only`）。
    """
    from smartbi.gold.restaurant import daily_close

    src = _code_only(daily_close)
    for forbidden, why in (
        ("SELECT", "自己拼 SQL = 第二套取数"),
        ("SUM(", "自己写聚合 = 第二套口径"),
        ("¥", "自己格式化金额 = 第二套呈现"),
        ("round(", "自己做数值处理 = 第二套口径"),
    ):
        assert forbidden not in src, f"日结里出现了 {forbidden!r} —— {why}"


def test_window_is_today_not_last_one_day():
    """⚠️ 「当日」不是「最近 1 天」—— 后者会把昨天算进来。"""
    d = date(2026, 8, 13)
    assert daily_close_window(d) == (d, d)


# ── 判据 1 + 2: 三段齐, 且限定语跟着 provenance 变 ────────────────
def _cell(metric_key, label, value, provenance, basis):
    return CellResult(metric_key, label, "all", "total", "money",
                      [{metric_key: value}], (), "", provenance, basis)


def test_gross_profit_is_estimated_because_it_depends_on_the_cost_card():
    """🔴 出处是**推导**出来的, 不是日结手工标的。

    `gross_profit` 是 `Derived`，它自己的 `requires` 是空的 ——
    要递归展开到 `food_cost` 才看得见成本卡那一列。
    ⛔ 不展开就会把毛利说成账上的数, 而那是最坏的方向。
    """
    assert ge._provenance_of("gross_profit")[0] == "ESTIMATED", (
        "毛利被判成了实测值 —— 递归展开没生效, 成本卡那一列没看见")
    assert ge._provenance_of("gross_margin")[0] == "ESTIMATED"
    # 阴性对照: 不含成本卡的指标不许被判成估算
    assert ge._provenance_of("revenue")[0] == "MEASURED"
    assert ge._provenance_of("orders")[0] == "MEASURED"


def test_gross_profit_says_it_is_not_profit():
    """🔴 owner P0 口径的 ② 是**两句**，我第一版只出了第二句。

    漏掉的那句更要紧：店长看到「今天全部门店毛利合计 ¥50 万」很可能直接读成
    「今天赚了 50 万」，而毛利扣掉人工/房租/水电之后完全可能是亏的。
    ⛔ 只防「估算」防不住「把毛利当利润」。
    """
    prov, basis = ge._provenance_of("gross_profit")
    text = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")

    assert "未扣人工" in text and "房租" in text, f"没说「毛利不是利润」: {text}"
    assert "人工" in text and "房租" in text and "水电" in text, (
        f"没点名扣的是哪几样开销: {text}")
    # ⛔ 两句都要在, 不是二选一
    assert "成本卡的理论用量" in text, f"「按成本卡估算」那句丢了: {text}"


def test_caveat_comes_from_the_registry_not_the_narrator(monkeypatch):
    """🔴 变异对照打在**被守的行为**上: caveat 是登记表里的字段, 不是叙述层写死的。

    ⛔ 只断言「正文里有这句话」证明不了它是登记驱动的 —— 手写一句同样能过。
    """
    from smartbi.gold.restaurant import generic_answer as ga
    from smartbi.gold.restaurant import metric_registry as reg

    prov, basis = ge._provenance_of("gross_profit")
    before = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")
    # ⚠️ 2026-08-14: caveat 进了行内括号, 用的是短形 caveat_short。
    #    断言盯的仍然是「这个数不是利润」这条**事实**。
    assert "未扣人工" in before

    patched = dict(reg.DERIVED)
    original = patched["gross_profit"]
    # ⚠️ 2026-08-14: 正文用的是**短形** `caveat_short`(行内括号放不下整句),
    #    所以变异要打在短形上 —— 打在长形上会「变异没生效」而我会读成
    #    「守卫没覆盖」。两者长得一样但成因相反。
    patched["gross_profit"] = type(original)(
        **{**original.__dict__, "caveat_short": "换了一句完全不同的话"})
    # ⚠️ 打在 generic_answer 绑定的那个名字上 —— 它是 from-import。
    monkeypatch.setattr(reg, "DERIVED", patched)
    monkeypatch.setattr(ga, "DERIVED", patched)

    after = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")
    assert "换了一句完全不同的话" in after, (
        "改了 registry 的 caveat 而正文没变 —— 说明那句话是在叙述层写死的")
    assert "未扣人工" not in after


def test_registry_gate_requires_a_caveat_when_asks_admits_profit(monkeypatch):
    """闸的判据不是手写名单, 是**登记表自己的声明**。

    `gross_profit.asks = "毛利/赚了多少(金额)"` —— 登记表早就承认用户会那样问它。
    """
    from smartbi.gold.restaurant import metric_registry as reg

    patched = dict(reg.DERIVED)
    original = patched["gross_profit"]
    patched["gross_profit"] = type(original)(**{**original.__dict__, "caveat": ""})
    monkeypatch.setattr(reg, "DERIVED", patched)

    with pytest.raises(AssertionError, match="读成利润"):
        reg.assert_registry_self_consistent()


def test_three_segments_all_present():
    """判据 1: 数字 + 限定语 + 开价。"""
    prov, basis = ge._provenance_of("gross_profit")
    text = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")

    assert "¥8,642.00" in text, f"① 数字没出来: {text}"
    assert "估算：" in text, f"② 限定语没出来: {text}"
    # 🔴 2026-08-14 订正: 原来断言的是「从估变实」——**那是一句系统做不到的承诺**。
    #    `_provenance_of` 是指标键的纯函数, `gross_profit` 恒为 ESTIMATED:
    #    把全店的卡补齐它还是 ESTIMATED(basis 是成本卡的**理论**用量,
    #    实际耗用要盘点)。补卡提高的是**覆盖率**, 不是出处。
    #    ⇒ 现在守的是「开价出来了且**不撒谎**」。
    # ⚠️ 断言守的是**行为**(说清它只能是估的 + 要变实该做什么),
    #    ⛔ 不钉死措辞 —— 理由那句来自登记表, 改措辞不该让这条红。
    assert "估算：" in text and "盘" in text, f"③ 开价没出来: {text}"
    assert "从估变实" not in text, (
        "🔴 又承诺「补上就变实」了 —— 补卡改不了 provenance, 这是做不到的")
    assert "就是账上的了" not in text, "同上: 承诺了做不到的准确性"
    # ⛔ 限定语不许复述成两遍(② 和 ③ 相邻)
    assert text.count("成本卡的理论用量") == 1, f"basis 复述了两遍: {text}"
    # ⛔ 2026-08-13 定稿: 「不是账上的数」和「不能当实际毛利用」是同一件事,
    #    留一个。留了后者(更直接说清后果)。
    assert "不是账上的数" not in text, f"同一件事说了两遍: {text}"


def test_mutation_forcing_measured_removes_the_qualifier():
    """🔴 判据 2 的变异对照: 强改成 MEASURED → 限定语**必须消失**。

    ⛔ 这条打在**被守的行为**(「估出来的数必须说自己是估的」)上,
       不是打在某一行实现上。
    """
    prov, basis = ge._provenance_of("gross_profit")
    with_qualifier = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")
    assert "估算：" in with_qualifier

    # 变异: 同一个数, 出处强改成 MEASURED
    muted = render(_cell("gross_profit", "毛利", 8642.0, "MEASURED", ""), "今天")
    assert "估算：" not in muted, (
        "改成 MEASURED 限定语还在 —— 说明它是手写的, 不是由字段生成的")
    assert "估算：" not in muted, "MEASURED 还在开「估的」那条价"
    # 阳性对照: 数字本身没变 —— 变的只是出处那一层
    assert "¥8,642.00" in muted


def test_gross_profit_uses_paid_revenue_not_list_price():
    """🔴 owner 2026-08-13 裁定 2: 合计层的毛利 = **实收**营收 − 食材成本。

    改之前: 749,009.00(明细行原价) − 242,259.58 = 506,749.42
    改之后: 717,883.41(交易实收) − 242,259.58 = 475,623.83
    差 31,125.59 = 折扣额。⛔ 用原价算收入 = 把从未收到的钱算成收入。
    """
    from smartbi.gold.restaurant.metric_registry import AGGREGATIONS, DERIVED

    # 合计层(不分组)必须走拆分执行
    assert ge._needs_split_execution(DERIVED["gross_profit"],
                                     AGGREGATIONS["summary"]) is True
    assert ge._needs_split_execution(DERIVED["gross_margin"],
                                     AGGREGATIONS["summary"]) is True


def test_split_execution_is_scoped_to_the_aggregate_level():
    """⛔ 分组层**不拆** —— 那一层拿不到交易级折扣的归属, 已挂账。

    阴性对照: 没有这条, 上面那条可能只是「所有情况都拆」。
    """
    from smartbi.gold.restaurant.metric_registry import AGGREGATIONS, DERIVED, METRICS

    grouped = [a for a in AGGREGATIONS.values() if getattr(a, "needs_dimension", True)]
    assert grouped, "没有需要分组的聚合形态 —— 这条对照失去意义"
    for agg in grouped:
        assert ge._needs_split_execution(DERIVED["gross_profit"], agg) is False, (
            f"{agg} 这种分组形态也被拆了 —— 分组层没有折扣归属, 拆了算不对")

    # 基础指标永远不拆
    assert ge._needs_split_execution(METRICS["revenue"],
                                     AGGREGATIONS["summary"]) is False


def test_split_only_when_bases_do_not_share_the_txn_grain():
    """判据是**两个输入共不共享 txn 粒度**, 不是硬编 gross_profit。

    共享 txn 时原路径本来就用实收营收, 拆了纯属多跑一次查询。
    """
    from smartbi.gold.restaurant.metric_registry import AGGREGATIONS, DERIVED

    # ⚠️ 判据要递归: `gross_margin` 自己的两个输入都含 txn, 但分子 `gross_profit`
    #    是混的 —— 第一版我这条断言没跟着递归, 把 gross_margin 也算进「不该拆」,
    #    于是**测试自己错了**(代码是对的)。取「自己不混 且 下层也不混」的那些。
    clean = [k for k, d in DERIVED.items()
             if "txn" in (ge._base_grains(d.left) & ge._base_grains(d.right))
             and not ge._mixes_grains(d.left) and not ge._mixes_grains(d.right)]
    assert clean, "没有任何派生量是干净的 —— 这条对照失去意义"
    for k in clean:
        assert ge._needs_split_execution(DERIVED[k], AGGREGATIONS["summary"]) is False, (
            f"{k} 的两个输入都能用实收营收, 拆了只是多跑一次查询")

    # 阳性对照: 混口径的那些确实被判为要拆 —— 否则上面全 False 也能过
    dirty = [k for k in DERIVED if ge._mixes_grains(k)]
    assert set(dirty) >= {"gross_profit", "gross_margin"}, dirty


@pytest.mark.asyncio
async def test_split_execution_subtracts_paid_revenue():
    """跑在真入口上: 拆分执行确实用 txn 营收减 item 成本。"""
    conn = _FakeConn({"revenue": 717883.41, "food_cost": 242259.58,
                      "orders": 1970, "gross_profit": 999999.0})
    cell = await ge.execute_cell(
        conn, factory_id="T", metric_key="gross_profit",
        dimension_key="all", aggregation_key="summary",
        date_range=(date(2026, 8, 12), date(2026, 8, 12)))
    got = float(cell.rows[0]["gross_profit"])
    # 覆盖口径: (20000 − 800×0.625) − 6000 = 13500
    assert abs(got - 13500.0) < 0.01, (
        f"毛利 = {got}, 期望 13500(覆盖净营收 − 覆盖成本)。"
        f"若得到 999999 说明还在走单条 SQL 那条路; "
        f"若得到 14000 说明折扣没摊(20000−6000)")
    # 🔴 阳性对照: 不摊折扣会得到 14000 —— 两者差的正是摊到覆盖部分的那 500,
    #    没有这条就分不清「摊了」和「压根没走覆盖口径」。
    assert abs(got - 14000.0) > 1.0, "折扣没摊到覆盖部分"
    assert cell.provenance == "ESTIMATED"
    assert "摊派" in cell.estimation_basis


def _cell_cov(coverage):
    """带覆盖率的毛利格子。"""
    prov, basis = ge._provenance_of("gross_profit")
    return CellResult("gross_profit", "毛利", "all", "total", "money",
                      [{"gross_profit": 8642.0}], (), "", prov, basis, coverage)


def test_qualifier_states_how_much_of_revenue_lacks_a_cost_card():
    """🔴 判据 2: **构造**覆盖率不足的场景, 限定语要跟着变。

    ⛔ MOCK_REST 的覆盖率是 100.0%, 在那个租户上永远量不出这条 ——
       「平租户让闸恒绿」。所以这里必须构造。
    """
    text = render(_cell_cov(0.40), "今天")
    # 🔴 正反都说: 只说否定面会让店长觉得这数没用, 只说正面会淡化风险。
    assert "只算了 40.0% 的营收" in text, f"没说能算准的那部分: {text}"
    assert "只算了 40.0% 的营收" in text, f"没说估的那部分: {text}"
    assert "估算：" in text


def test_qualifier_percentage_follows_the_coverage_number():
    """🔴 变异对照: 换一个覆盖率, 那个百分比必须跟着变。

    ⛔ 只断言「出现了 60.0%」证明不了它是算出来的 —— 写死一个 60.0% 同样能过。
    """
    assert "只算了 40.0% 的营收" in render(_cell_cov(0.40), "今天")
    assert "只算了 25.0% 的营收" in render(_cell_cov(0.25), "今天")
    # 阴性对照: 全覆盖时那句话**必须消失**, 否则它就是句无条件的废话
    full = render(_cell_cov(1.0), "今天")
    assert "只算了" not in full, f"全覆盖还在说「只算了 100.0%」: {full}"
    assert "能算准" not in full, f"全覆盖还在拆分正反两面: {full}"
    assert "估算：" in full, "全覆盖时「按成本卡估的」那句不该跟着消失"


def test_unknown_coverage_is_not_reported_as_full():
    """⛔ 覆盖率算不出来时是 `None`, 不许当成 1.0。

    当成 1.0 = 拿猜测冒充读数, 而且方向最危险: 覆盖不足的租户会被说成全覆盖。
    """
    text = render(_cell_cov(None), "今天")
    assert "没有配方成本" not in text
    assert "估算：" in text


def test_revenue_section_has_no_qualifier():
    """阴性对照: 营收是账上的数, 不该挂限定语。

    ⛔ 没有这条, 上面那些可能只是「所有段都挂限定语」。
    """
    prov, basis = ge._provenance_of("revenue")
    text = render(_cell("revenue", "营收", 31200.0, prov, basis), "今天")
    assert "¥31,200.00" in text
    assert "估算：" not in text
    assert "从估变实" not in text


# ── 整屏组装 ──────────────────────────────────────────────────
class _FakeConn:
    """只桩掉外部 IO —— `execute_cell` 的其余逻辑照跑（含缺列判定、后处理）。"""

    def __init__(self, values):
        self._values = values
        self.fetched_sql = []

    #: 覆盖口径的合成读数。⚠️ 这几个数**互相之间必须自洽**, 否则断言在验一个
    #: 不可能出现的世界(形态 B‴)。这里的关系是:
    #:   折扣 = 全部明细 32000 − 交易实收 31200 = 800
    #:   覆盖占比 = 20000/32000 = 0.625
    #:   覆盖净营收 = 20000 − 800×0.625 = 19500
    #:   覆盖毛利   = 19500 − 6000 = 13500      毛利率 = 69.2%
    COVERED_GROSS, ALL_GROSS, COVERED_COST, PAID_NET = 20000.0, 32000.0, 6000.0, 31200.0

    async def fetchrow(self, sql, *args):
        if "covered_gross" in sql:
            return {"covered_gross": self.COVERED_GROSS,
                    "all_gross": self.ALL_GROSS,
                    "covered_cost": self.COVERED_COST}
        return None

    async def fetchval(self, sql, *args):
        if "net_amount" in sql:
            return self.PAID_NET
        return None

    #: 成本桥接的存量兜底层 + 这家店有哪些菜。
    #: ⚠️ 2026-08-13 起「菜名→成本键」统一由 `resolve_cost_keys` 解析,
    #:    执行器把配好的两个数组当参数传给 SQL。桩要覆盖这两条查询, 否则
    #:    它们会掉进下面那条兜底分支, 拿到一行 `{revenue: ...}` 当映射用。
    BRIDGE_ROWS = [("番茄炒蛋", "PK_STALE")]
    DISH_NAMES = ["番茄炒蛋"]

    async def fetch(self, sql, *args):
        if "information_schema" in sql:
            # 所有列都在 —— 这样走的是「算得出」那条路
            from smartbi.gold.restaurant.metric_registry import METRICS
            cols = {c for m in METRICS.values() for c in m.requires}
            return [{"table_name": c.split(".")[0], "column_name": c.split(".")[1]}
                    for c in cols]
        if "dim_restaurant_cost_product" in sql:
            return [{"normalized_name": n, "product_source_pk": pk}
                    for n, pk in self.BRIDGE_ROWS]
        if "SELECT DISTINCT normalized_name FROM dim_product" in sql:
            return [{"normalized_name": n} for n in self.DISH_NAMES]
        if "AS unit_cost" in sql:
            # 成本卡异常判定的**输入**(按菜聚合)。⚠️ 2026-08-14 起排除判据在
            #    Python 里按菜算, 执行器要先查这一条再拼覆盖毛利。
            # ⚠️ 这里给一张**正常**的卡: 3.00 一份而卖 10.00 —— 不触发排除,
            #    于是下面几条对毛利数值的断言不受影响(阳性对照在
            #    test_margin_parity 里, 用米饭那张真实的坏卡)。
            return [{"name": n, "qty": 100.0, "revenue": 1000.0,
                     "unit_cost": 3.0} for n in self.DISH_NAMES]
        self.fetched_sql.append(sql)
        key = next((k for k in self._values if k in sql), None)
        return [{k: v for k, v in self._values.items()}]


@pytest.mark.asyncio
async def test_build_daily_close_assembles_one_screen():
    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})
    screen = await build_daily_close(conn, factory_id="T_DAILY",
                                     today=date(2026, 8, 13))

    assert screen["date"] == "2026-08-13"
    assert len(screen["sections"]) == len(DAILY_CLOSE_CELLS)
    # 整屏出处取最保守的那个 —— 一段是估的, 整屏就不能当账上的数
    assert screen["provenance"] == "ESTIMATED", (
        "有一段是估的而整屏说成实测 —— 店长会拿它当账上的数去做决定")
    # 阳性对照: 确实有段是 MEASURED, 否则「取最保守」这句没被检验
    kinds = {s["provenance"] for s in screen["sections"]}
    assert kinds == {"MEASURED", "ESTIMATED"}, f"两种出处没同时出现: {kinds}"
    assert "¥" in screen["answer_text"]


class _FakePool:
    """`pool.acquire()` → 同一个 `_FakeConn`。`fetchrow`/`execute` 走内存防重表。"""

    def __init__(self, conn):
        self._conn = conn
        self.notified_log: list = []

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(self):
                # ⚠️ 只接管防重表那条查询, 其余**转回 conn 自己的** ——
                #    不分流的话它会把覆盖口径那条查询也吃掉, 表现是毛利那段
                #    变成「今天没有可用的毛利数据」(实测踩到)。
                _own = _FakeConn.fetchrow

                async def fetchrow(sql, *args):
                    if "notifications_log" not in sql:
                        return await _own(pool._conn, sql, *args)
                    key = (args[0], args[1], args[2])
                    return {"id": 1} if key in pool.notified_log else None

                async def execute(sql, *args):
                    pool.notified_log.append((args[0], args[1], args[2]))

                pool._conn.fetchrow = fetchrow
                pool._conn.execute = execute
                return pool._conn

            async def __aexit__(self, *a):
                return False

        return _Ctx()


def _sent_recorder():
    sent = []

    async def java_notify(*, factory_id, role, title, body, action_url):
        sent.append({"role": role, "title": title, "body": body})
        return True

    return sent, java_notify


@pytest.mark.asyncio
async def test_no_business_day_does_not_push():
    """🔴 2026-08-13 prod 实测抓到的: 当天没数据时那一屏是「— / 0 / —」,
    而推送照发。店长收到一屏空数字, 比不推更糟 —— 看起来像系统坏了。

    ⛔ 而且我的仪器当时报 rc=0 / sections_computed=3 —— 因为它按
       `missing_columns` 数(列都在), 把「schema 在」当成了「有数可说」。
    """
    from smartbi.gold.restaurant.daily_close import build_daily_close, push_daily_close

    # 没营业: 订单数 0, 金额全 None(SUM 无行 → NULL)
    conn = _FakeConn({"revenue": None, "orders": 0, "gross_profit": None})
    screen = await build_daily_close(conn, factory_id="T_DAILY", today=date(2026, 8, 13))
    assert screen["status"] == "no_business", screen["status"]

    sent, java_notify = _sent_recorder()
    out = await push_daily_close(_FakePool(_FakeConn(
        {"revenue": None, "orders": 0, "gross_profit": None})),
        factory_id="T_DAILY", today=date(2026, 8, 13),
        java_notify=java_notify, roles=["restaurant_manager"])

    assert sent == [], "没营业还是推了一屏空数字"
    assert out["notify"]["reason"] == "no_business"


@pytest.mark.asyncio
async def test_no_data_branch_is_defensive_only_not_an_etl_detector():
    """🔴 这条记录一个**我写错了的判据**, 免得下一个人再照它推理。

    我原本写的是:「营收算不出来是 None, 分不清没营业和执行链没跑通;
    订单数分得清(0 vs None)」。**订单数分不清** —— `orders` 是 `COUNT(...)`,
    空集上返回 0 而不是 NULL。

    2026-08-13 prod 实测: 拿一个根本不存在的租户跑, 得到 `no_business`。
    而这条单测之所以能构造出 `no_data`, 是因为 `_FakeConn` 直接喂了
    `orders: None` —— **真实 SQL 永远不会产出这个形状**。

    ⛔ 所以 `no_data` 是防御性分支(rows 为空/取不到值), 不是 ETL 探测器。
       「没营业」和「数据没落库」在事实表上同形, 分不开; 要分得看营业日历
       或 ETL 水位。已挂账。
    """
    from smartbi.gold.restaurant.daily_close import build_daily_close

    # 防御性分支: 只有在拿不到值时才到得了(真实 COUNT 到不了)
    dead = await build_daily_close(
        _FakeConn({"revenue": None, "orders": None, "gross_profit": None}),
        factory_id="T_DAILY", today=date(2026, 8, 13))
    assert dead["status"] == "no_data"

    # 🔴 生产上真正会发生的形状: 没有行 → COUNT 给 0, SUM 给 None
    closed = await build_daily_close(
        _FakeConn({"revenue": None, "orders": 0, "gross_profit": None}),
        factory_id="T_DAILY", today=date(2026, 8, 13))
    assert closed["status"] == "no_business", (
        "这是 prod 上「没营业」和「租户不存在」共同的长相 —— 两者分不开")

    # 阳性对照: 有营业的那天不许被判成上面任何一种
    ok = await build_daily_close(
        _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0}),
        factory_id="T_DAILY", today=date(2026, 8, 13))
    assert ok["status"] == "ok"


@pytest.mark.asyncio
async def test_status_is_not_derived_from_the_rendered_text():
    """⛔ 判「有没有数」只能看**值**, 不能 match 正文里的「—」。

    拿呈现层当数据层, 换个占位符(或者加个千分位)这道判断就静默失效。
    """
    import inspect

    from smartbi.gold.restaurant import daily_close

    src = _code_only(daily_close)
    assert "—" not in src, "用正文里的占位符判有没有数 —— 那是拿呈现层当数据层"
    assert '"value"' in src or "'value'" in src


@pytest.mark.asyncio
async def test_push_reuses_the_existing_channel_not_a_new_one():
    """判据 3 的接线面: 走的是 `value_notifier` 那条链, 不是日结自己写的推送。"""
    import inspect

    from smartbi.gold.restaurant import daily_close

    src = inspect.getsource(daily_close)
    assert "maybe_notify" in src, "没接现有通道"
    for forbidden in ("INSERT INTO", "notifications_log", "httpx", "requests."):
        assert forbidden not in src, (
            f"日结里出现了 {forbidden!r} —— 自己写了一份推送/防重, "
            f"两份实现会漂, 表现是店长一天收到两遍")


@pytest.mark.asyncio
async def test_push_sends_the_three_segments_to_the_manager():
    from smartbi.gold.restaurant.daily_close import push_daily_close

    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})
    pool = _FakePool(conn)
    sent, java_notify = _sent_recorder()

    out = await push_daily_close(pool, factory_id="T_DAILY", today=date(2026, 8, 13),
                                 java_notify=java_notify, roles=["restaurant_manager"])

    assert out["notify"]["notified"] == ["restaurant_manager"]
    body = sent[0]["body"]
    assert "¥" in body, f"① 数字没发出去: {body}"
    assert "估算：" in body, f"② 限定语没发出去: {body}"
    assert "估算：" in body and "盘" in body, f"③ 开价没发出去: {body}"
    assert "2026-08-13" in sent[0]["title"]


@pytest.mark.asyncio
async def test_non_price_role_never_receives_amounts():
    """🔴 RBAC: `factory_admin` 收推送但**不在** PRICE_VIEW_ROLES 里。

    第一版我把整屏原样推给所有角色 —— 那是把一道权限边界推平。
    """
    from smartbi_compat._rbac_strip import PRICE_VIEW_ROLES
    from smartbi.gold.restaurant.daily_close import push_daily_close

    # 阳性对照: 先证明这个角色确实是「收推送但看不到金额」的那一类,
    # ⛔ 否则这条断言可能只是在测一个根本不收推送的角色。
    assert "factory_admin" not in PRICE_VIEW_ROLES

    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})
    sent, java_notify = _sent_recorder()
    await push_daily_close(_FakePool(conn), factory_id="T_DAILY",
                           today=date(2026, 8, 13), java_notify=java_notify,
                           roles=["factory_admin"])

    body = sent[0]["body"]
    assert "¥" not in body, f"金额漏给了非金额角色: {body}"
    assert "8,642" not in body and "31,200" not in body
    # 阴性对照: 不是「什么都没发」—— 非金额段(订单数)照发
    assert "128" in body, f"裁过头了, 连不含金额的段都没了: {body}"


@pytest.mark.asyncio
async def test_rbac_filter_is_driven_by_registry_unit(monkeypatch):
    """🔴 变异对照打在**被守的行为**上: 把 `orders` 的 unit 改成 money,
    非金额角色就该一段都收不到(而不是继续收到它)。

    ⛔ 这证明过滤是**问 registry** 的, 不是在推送侧写死了「gross_profit 是金额」。
    """
    from smartbi.gold.restaurant import metric_registry as reg
    from smartbi.gold.restaurant.daily_close import build_daily_close, push_daily_close

    patched = dict(reg.METRICS)
    original = patched["orders"]
    patched["orders"] = type(original)(**{**original.__dict__, "unit": "money"})
    # 🔴 必须打在 `generic_executor` **绑定的那个名字**上。
    #    它是 `from ... import METRICS`, 所以 `setattr(reg, "METRICS", ...)`
    #    根本够不着 —— 实测第一版就是这样: 断言红了, 而红的原因是
    #    **变异没送达**, 不是「守卫没覆盖」。两者长得一模一样。
    monkeypatch.setattr(reg, "METRICS", patched)
    monkeypatch.setattr(ge, "METRICS", patched)

    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})

    # 阳性对照: 先证明变异**确实到达了** CellResult.unit, 再看推送。
    # ⛔ 没有这一步, 下面那条断言无论红绿都读不出信息。
    probe = await build_daily_close(conn, factory_id="T_DAILY", today=date(2026, 8, 13))
    assert {s["unit"] for s in probe["sections"]} == {"money"}, (
        f"变异没送达, 后面那条断言没有意义: {[s['unit'] for s in probe['sections']]}")

    sent, java_notify = _sent_recorder()
    out = await push_daily_close(_FakePool(conn), factory_id="T_DAILY",
                                 today=date(2026, 8, 13), java_notify=java_notify,
                                 roles=["factory_admin"])

    assert sent == [], "三段都成了金额段, 非金额角色却还收到了推送"
    assert out["notify"]["skipped"] == ["factory_admin"], (
        "一段可看的都没有时应当跳过, 而不是推一条空通知")


@pytest.mark.asyncio
async def test_same_day_twice_pushes_once():
    """幂等: cron 重试 / 手工补跑不许让店长收到两遍。"""
    from smartbi.gold.restaurant.daily_close import push_daily_close

    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})
    pool = _FakePool(conn)
    sent, java_notify = _sent_recorder()

    first = await push_daily_close(pool, factory_id="T_DAILY", today=date(2026, 8, 13),
                                   java_notify=java_notify, roles=["restaurant_manager"])
    second = await push_daily_close(pool, factory_id="T_DAILY", today=date(2026, 8, 13),
                                    java_notify=java_notify, roles=["restaurant_manager"])

    assert first["notify"]["notified"] == ["restaurant_manager"]
    assert second["notify"]["skipped"] == ["restaurant_manager"]
    assert len(sent) == 1, f"同一天推了 {len(sent)} 次"

    # 阳性对照: **换一天**必须能再推 —— 否则「幂等」可能只是「推一次就再也不推了」,
    # 那样日结从第二天起就静默失效, 而且和「幂等生效」长得一模一样。
    await push_daily_close(pool, factory_id="T_DAILY", today=date(2026, 8, 14),
                           java_notify=java_notify, roles=["restaurant_manager"])
    assert len(sent) == 2, "换了一天还是推不出去 —— 防重键不是日粒度"


def test_period_key_is_day_grained_and_needs_the_widened_column():
    """⚠️ 防重键是 `YYYY-MM-DD`(10 字符), 而那一列历史上是 `varchar(7)`。

    这条钉住「迁移必须先跑」——否则推送会在写防重日志时报错,
    表现是**每天都推**(写不进日志 → 下次不认为推过)。
    """
    from smartbi.gold.restaurant.daily_close import daily_close_window

    key = daily_close_window(date(2026, 8, 13))[0].isoformat()
    assert key == "2026-08-13"
    assert len(key) == 10 > 7, "周期键没超过 7 字符 —— 那这条迁移就是多余的"


@pytest.mark.asyncio
async def test_screen_provenance_is_conservative_not_majority():
    """⛔ 变异对照: 把「任一段是估的」改成「多数段是估的」会怎样。

    3 段里只有 1 段是估的 —— 按多数就会说成 MEASURED。这条钉住取最保守。
    """
    conn = _FakeConn({"revenue": 31200.0, "orders": 128, "gross_profit": 8642.0})
    screen = await build_daily_close(conn, factory_id="T_DAILY",
                                     today=date(2026, 8, 13))
    estimated = [s for s in screen["sections"] if s["provenance"] == "ESTIMATED"]
    assert len(estimated) * 2 < len(screen["sections"]), (
        "估算段占了多数 —— 这条变异对照失去意义, 换个构造")
    assert screen["provenance"] == "ESTIMATED"


def test_ledger_records_status_per_factory():
    """🔴 `no_business` 不推不告警 —— 所以它**必须**在台账里留痕。

    某家店连续 N 天 no_business，是 ETL 挂了的长相：店长觉得
    「系统好像忘了我」，而我们这边一切正常（不推不告警本来就是设计）。

    ⛔ 记 `factory_id → status` 的映射，不是只记计数 —— 只记「今天 1 家
       no_business」分不出「同一家连着 7 天」和「7 家各一天」，
       前者是故障，后者正常。
    """
    # ⚠️ 读文本不 import: `daily_close_ledger` 在**模块级**就读产出文件,
    #    import 它会当场 FileNotFoundError(实测踩到)。
    import io as _io
    from pathlib import Path as _Path

    root = _Path(__file__).resolve().parents[1]
    src = _io.open(root / "smartbi/scripts/daily_close_ledger.py",
                   encoding="utf-8", newline="").read()
    assert "status_by_factory" in src, "台账不记每家店的 status —— 静默失效看不出来"
    assert 'r.get("status")' in src

    # 阳性对照: 产出侧真的带了 status(否则台账记的是一片 None)
    push = _io.open(root / "smartbi/scripts/daily_close_push.py",
                    encoding="utf-8", newline="").read()
    assert '"status": screen["status"]' in push, (
        "跑批产出里没有 status —— 台账那一列会是空的")
