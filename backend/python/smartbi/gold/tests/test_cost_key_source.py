"""成本键权威来源断了会不会**响** —— owner 2026-08-13 裁定条件 3。

🔴 要守的行为: cretas 运营库够不着时, 日结必须**显式失败**, 绝不许静默当成
   「这些菜没有成本卡」。后者与刚修掉的 `COALESCE(food_cost, 0)` 在数值上完全
   一样(毛利虚高), 而且更难发现 —— 它是间歇性的, 池子抖一次错一次, 答案每次
   都长得很正常。

⚠️ 每条阴性断言都配了阳性对照。「它会抛」这种断言最容易变成恒真式:
   夹具随便哪里坏掉都会抛, 而我会把那当成守卫生效。
   —— 见 memory `feedback_unstubbed_mock_makes_the_negative_assertion_vacuous`。
"""
import asyncio
import datetime
from decimal import Decimal

import pytest

from smartbi.gold.restaurant.generic_executor import execute_cell
from smartbi.gold.restaurant.restaurant_cost_mapping import (
    CostKeySourceUnavailable,
    cost_bridge_pairs,
    cost_key_of,
    normalize_dish_name,
    resolve_cost_keys,
)

DAY = datetime.date(2026, 8, 12)
RANGE = (DAY, DAY)

#: 执行器要用到的列都当作存在 —— 本文件测的不是缺列, 是来源断了。
COLUMNS = {
    "fact_pos_transaction.net_amount",
    "fact_pos_transaction.date",
    "fact_pos_item.amount",
    "fact_pos_item.qty",
    "agg_restaurant_product_cost.food_cost",
}


class _FakeCretasConn:
    """运营库那一侧。⚠️ 形状照真 SQL 的产出写:
    `product_types` 返回 id/name, 别名表返回 pos_name/product_type_id。"""

    def __init__(self, *, product_types, aliases=None, boom=False):
        self._product_types = product_types
        self._aliases = aliases
        self.boom = boom

    async def fetch(self, sql, *args):
        if self.boom:
            raise RuntimeError("connection reset by peer")
        if "product_types" in sql:
            return [{"id": pk, "name": name} for name, pk in self._product_types]
        if "dim_product_alias" in sql:
            if self._aliases is None:
                raise RuntimeError('relation "dim_product_alias" does not exist')
            return [{"pos_name": n, "product_type_id": pk} for n, pk in self._aliases]
        raise AssertionError(f"夹具没有为这条 SQL 打桩: {sql}")


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


class _FakeSmartbiConn:
    """SmartBI 那一侧。

    ⚠️ 形状必须是真 SQL 产得出来的(形态 B‴): 覆盖毛利那条全部字段带
       COALESCE, 所以**永远不是 NULL**; `COUNT` 空集是 0 不是 None。
    """

    def __init__(self, *, bridge_rows=(), dish_names=(), covered=None):
        self.bridge_rows = list(bridge_rows)
        self.dish_names = list(dish_names)
        self.covered = covered or {
            "covered_gross": Decimal("1000.00"),
            "all_gross": Decimal("2000.00"),
            "covered_cost": Decimal("400.00"),
        }
        self.bridge_args = []

    async def fetch(self, sql, *args):
        if "dim_restaurant_cost_product" in sql:
            return [{"normalized_name": n, "product_source_pk": pk}
                    for n, pk in self.bridge_rows]
        if "SELECT DISTINCT normalized_name FROM dim_product" in sql:
            return [{"normalized_name": n} for n in self.dish_names]
        # 异常成本卡清单
        self.bridge_args.append(args[3:5])
        return []

    async def fetchrow(self, sql, *args):
        self.bridge_args.append(args[3:5])
        return dict(self.covered)

    async def fetchval(self, sql, *args):
        return Decimal("1800.00")          # 实收 = 明细原价 2000 − 折扣 200


def _pool_with(product_types, **kw):
    return _FakePool(_FakeCretasConn(product_types=product_types, **kw))


# ═══════════════════════════════════════════════════════════════════════════
# ① 三层来源都进得来 —— 这是下面所有阴性断言的阳性对照
# ═══════════════════════════════════════════════════════════════════════════
def test_three_tiers_merge_with_authority_winning():
    """权威层压过存量兜底, 存量兜底补权威层没有的菜。

    🔑 这条正是 2026-08-13 那 20,701.63 元差额的根: 青花椒有 9 道菜的映射
       **只在运营库里**, 桥接表 exact=0/ci=0。日结原来只连 SmartBI 一个池。
    """
    smartbi = _FakeSmartbiConn(bridge_rows=[
        ("只在存量里的菜", "PK_OLD"),
        ("两边都有的菜", "PK_STALE"),
    ])
    pool = _pool_with([
        ("两边都有的菜", "PK_LIVE"),
        ("只在运营库里的菜", "PK_NEW"),
    ])
    mapping = asyncio.run(resolve_cost_keys(smartbi, "F1", cretas_pool=pool))

    assert mapping[normalize_dish_name("只在运营库里的菜")] == "PK_NEW", (
        "🔴 权威层没进来 —— 那 9 道菜就是这么丢的")
    assert mapping[normalize_dish_name("只在存量里的菜")] == "PK_OLD", (
        "存量兜底被丢了 —— 历史/演示租户的成本行会失联")
    assert mapping[normalize_dish_name("两边都有的菜")] == "PK_LIVE", (
        "存量兜底压过了权威层, 方向反了")


def test_alias_tier_fills_only_what_authority_missed():
    """别名层补 POS 名字漂移, 但不许压过权威层。"""
    smartbi = _FakeSmartbiConn()
    pool = _pool_with(
        [("番茄炒蛋", "PK_A")],
        aliases=[("番茄炒蛋", "PK_WRONG"), ("番茄 炒蛋(大)", "PK_B")],
    )
    mapping = asyncio.run(resolve_cost_keys(smartbi, "F1", cretas_pool=pool))
    assert mapping[normalize_dish_name("番茄炒蛋")] == "PK_A", "别名压过了权威层"
    assert mapping[normalize_dish_name("番茄 炒蛋(大)")] == "PK_B", "别名层没接上"


def test_same_name_two_keys_stays_unresolved():
    """同名不同键 → 这道菜不解析。⛔ 猜一个会安静地污染 COGS。"""
    smartbi = _FakeSmartbiConn()
    pool = _pool_with([("红烧肉", "PK_1"), ("红烧肉", "PK_2")])
    mapping = asyncio.run(resolve_cost_keys(smartbi, "F1", cretas_pool=pool))
    assert cost_key_of(mapping, "红烧肉") is None, "🔴 冲突时挑了一个"


def test_normalisation_is_shared_by_both_paths():
    """大小写/空白只规范化一次, 两条路读同一份。

    ⚠️ 2026-08-13 实测: 桥接表存小写 `营养多c番茄味`, dim_product 存大写
       `营养多C番茄味` —— 精确等值全部落空, 6 道菜的卡被漏掉, 差 7,297.97 元。
    """
    smartbi = _FakeSmartbiConn()
    pool = _pool_with([("营养多C番茄味(小小份)", "PK_C")])
    mapping = asyncio.run(resolve_cost_keys(smartbi, "F1", cretas_pool=pool))
    assert cost_key_of(mapping, "营养多c番茄味(小小份)") == "PK_C"
    assert cost_key_of(mapping, " 营养多C番茄味(小小份) ") == "PK_C"


def test_bridge_pairs_carry_dim_product_names_verbatim():
    """给 SQL 的数组装的是 dim_product 的**原样名字** —— 于是 join 可以是纯等值。

    ⛔ 一旦这里改成传规范化后的名字, SQL 那边就必须补 lower(), 两套规范化
       立刻开始漂。
    """
    smartbi = _FakeSmartbiConn(dish_names=["营养多C番茄味(小小份)", "没有卡的菜"])
    pool = _pool_with([("营养多c番茄味(小小份)", "PK_C")])
    names, keys = asyncio.run(
        cost_bridge_pairs(smartbi, "F1", cretas_pool=pool))
    assert names == ["营养多C番茄味(小小份)"], f"名字不是原样: {names}"
    assert keys == ["PK_C"]


# ═══════════════════════════════════════════════════════════════════════════
# ② 来源断了必须响 —— 每条都配 ① 里的阳性对照
# ═══════════════════════════════════════════════════════════════════════════
def test_missing_cretas_pool_raises_instead_of_returning_partial(monkeypatch):
    """池子拿不到 → 抛。⛔ 不返回「只有存量兜底」的部分映射。

    部分映射与「这些菜没有成本卡」在数值上**完全一样**, 而后者会被当成真话
    端给店长。

    ⚠️ 必须打桩 `get_cretas_pool` 而不是传 `cretas_pool=None`:
       后者的含义是「你自己去取」, 第一版这么写的时候它**真的去连库了** ——
       测试因为连不上真库而红, 看起来像守卫生效, 实际量的是另一件事。
       (形态 A: 我量的这个数不是我想知道的那个数。)
    """
    import smartbi.config as _config

    async def _no_pool():
        return None

    monkeypatch.setattr(_config, "get_cretas_pool", _no_pool)
    smartbi = _FakeSmartbiConn(bridge_rows=[("只在存量里的菜", "PK_OLD")])
    with pytest.raises(CostKeySourceUnavailable):
        asyncio.run(resolve_cost_keys(smartbi, "F1"))


def test_cretas_query_failure_raises():
    """权威层查询炸了 → 抛。⚠️ 与「池子是 None」是两条不同的路, 各测各的。"""
    smartbi = _FakeSmartbiConn()
    pool = _FakePool(_FakeCretasConn(product_types=[], boom=True))
    with pytest.raises(CostKeySourceUnavailable):
        asyncio.run(resolve_cost_keys(smartbi, "F1", cretas_pool=pool))


def test_smartbi_fallback_failure_is_survivable():
    """③ 存量兜底炸了**不致命** —— 它补的是历史行, 缺了只少算几道菜;
    ① 缺了是整条权威链断掉。两者不同档, 所以处置不同。

    ⚠️ 这条是上面两条的**反向对照**: 如果它也抛, 说明我把「任何一层出问题
       就抛」写成了守卫, 那上面两条断言就不区分好坏了。
    """
    class _Boom(_FakeSmartbiConn):
        async def fetch(self, sql, *args):
            if "dim_restaurant_cost_product" in sql:
                raise RuntimeError("permission denied")
            return await super().fetch(sql, *args)

    pool = _pool_with([("番茄炒蛋", "PK_A")])
    mapping = asyncio.run(resolve_cost_keys(_Boom(), "F1", cretas_pool=pool))
    assert cost_key_of(mapping, "番茄炒蛋") == "PK_A"


# ═══════════════════════════════════════════════════════════════════════════
# ③ 日结那条路上真的会响吗 —— 形态 B: 机制在, 有没有接上
# ═══════════════════════════════════════════════════════════════════════════
def _run_daily_close_cell(monkeypatch, *, pool):
    """跑日结实际会跑的那个格子(毛利/全店/合计)。"""
    import smartbi.config as _config

    async def _fake_get_cretas_pool():
        return pool

    monkeypatch.setattr(_config, "get_cretas_pool", _fake_get_cretas_pool)
    smartbi = _FakeSmartbiConn(
        bridge_rows=[("番茄炒蛋", "PK_A")], dish_names=["番茄炒蛋"])
    return asyncio.run(execute_cell(
        smartbi, factory_id="F1", metric_key="gross_profit",
        dimension_key="all", aggregation_key="summary",
        date_range=RANGE, available_columns=COLUMNS,
    ))


def test_daily_close_cell_fails_loudly_when_source_is_down(monkeypatch):
    """🔴 日结的毛利格子: 权威来源断了 → 抛, ⛔ 不给一个偏高的数。"""
    with pytest.raises(CostKeySourceUnavailable):
        _run_daily_close_cell(monkeypatch, pool=None)


def test_daily_close_cell_computes_when_source_is_up(monkeypatch):
    """阳性对照 —— 没有它, 上面那条「会抛」分不清是守卫生效还是夹具坏了。"""
    cell = _run_daily_close_cell(
        monkeypatch, pool=_pool_with([("番茄炒蛋", "PK_A")]))
    assert cell.rows, "阳性对照都算不出来 —— 夹具坏了, 上面那条阴性断言不作数"
    # 覆盖 1000 / 全额 2000 → share 0.5; 折扣 200 摊 0.5 = 100
    # 覆盖净营收 1000 − 100 = 900; 毛利 900 − 400 = 500
    assert float(cell.rows[0]["gross_profit"]) == pytest.approx(500.0)
    assert cell.coverage_ratio == pytest.approx(0.5)


def test_cost_bridge_arrays_actually_reach_the_sql(monkeypatch):
    """形态 B: 解析出来了, 但有没有**送到 SQL**。

    ⚠️ 只断言「不抛」是不够的 —— 数组传空一样不抛, 而传空 = 一道卡都桥不上
       = 毛利率 100%。所以要看实参。
    """
    smartbi = _FakeSmartbiConn(
        bridge_rows=[("番茄炒蛋", "PK_A")], dish_names=["番茄炒蛋"])

    import smartbi.config as _config
    pool = _pool_with([("番茄炒蛋", "PK_LIVE")])

    async def _fake_get_cretas_pool():
        return pool

    monkeypatch.setattr(_config, "get_cretas_pool", _fake_get_cretas_pool)
    asyncio.run(execute_cell(
        smartbi, factory_id="F1", metric_key="gross_profit",
        dimension_key="all", aggregation_key="summary",
        date_range=RANGE, available_columns=COLUMNS,
    ))
    assert smartbi.bridge_args, "SQL 一次都没收到桥接实参"
    for names, keys in smartbi.bridge_args:
        assert names == ["番茄炒蛋"], f"送进 SQL 的名字不对: {names}"
        assert keys == ["PK_LIVE"], f"送进 SQL 的成本键不对(权威层没赢): {keys}"
