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


def test_three_segments_all_present():
    """判据 1: 数字 + 限定语 + 开价。"""
    prov, basis = ge._provenance_of("gross_profit")
    text = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")

    assert "¥8,642.00" in text, f"① 数字没出来: {text}"
    assert "不是账上的数" in text, f"② 限定语没出来: {text}"
    assert "从估变实" in text, f"③ 开价没出来: {text}"
    # ⛔ 限定语不许复述成两遍(② 和 ③ 相邻)
    assert text.count("成本卡的理论用量") == 1, f"basis 复述了两遍: {text}"


def test_mutation_forcing_measured_removes_the_qualifier():
    """🔴 判据 2 的变异对照: 强改成 MEASURED → 限定语**必须消失**。

    ⛔ 这条打在**被守的行为**(「估出来的数必须说自己是估的」)上,
       不是打在某一行实现上。
    """
    prov, basis = ge._provenance_of("gross_profit")
    with_qualifier = render(_cell("gross_profit", "毛利", 8642.0, prov, basis), "今天")
    assert "不是账上的数" in with_qualifier

    # 变异: 同一个数, 出处强改成 MEASURED
    muted = render(_cell("gross_profit", "毛利", 8642.0, "MEASURED", ""), "今天")
    assert "不是账上的数" not in muted, (
        "改成 MEASURED 限定语还在 —— 说明它是手写的, 不是由字段生成的")
    assert "从估变实" not in muted, "MEASURED 还开「从估变实」的价"
    # 阳性对照: 数字本身没变 —— 变的只是出处那一层
    assert "¥8,642.00" in muted


def test_revenue_section_has_no_qualifier():
    """阴性对照: 营收是账上的数, 不该挂限定语。

    ⛔ 没有这条, 上面那些可能只是「所有段都挂限定语」。
    """
    prov, basis = ge._provenance_of("revenue")
    text = render(_cell("revenue", "营收", 31200.0, prov, basis), "今天")
    assert "¥31,200.00" in text
    assert "不是账上的数" not in text
    assert "从估变实" not in text


# ── 整屏组装 ──────────────────────────────────────────────────
class _FakeConn:
    """只桩掉外部 IO —— `execute_cell` 的其余逻辑照跑（含缺列判定、后处理）。"""

    def __init__(self, values):
        self._values = values
        self.fetched_sql = []

    async def fetch(self, sql, *args):
        if "information_schema" in sql:
            # 所有列都在 —— 这样走的是「算得出」那条路
            from smartbi.gold.restaurant.metric_registry import METRICS
            cols = {c for m in METRICS.values() for c in m.requires}
            return [{"table_name": c.split(".")[0], "column_name": c.split(".")[1]}
                    for c in cols]
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
                async def fetchrow(sql, *args):
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
    assert "不是账上的数" in body, f"② 限定语没发出去: {body}"
    assert "从估变实" in body, f"③ 开价没发出去: {body}"
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
