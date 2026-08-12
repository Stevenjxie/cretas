"""架构收口 C 的验收：CellResult 表达得了「估出来的」，两条路各自说得清自己的数。

🔴 被测的病：同一个店长问毛利，经营看板(`dish_margin`)和 AI 问答
   (`restaurant_ops_router`)会给出**两个不同的数字**，而系统不告诉他为什么。
   MOCK_REST 成本覆盖率 100% 所以今天不发作，换真租户就发作 ——
   所以验收**必须构造覆盖率 < 100% 的场景**，在 100% 上测等于没测。

⛔ 不改任何现有数字。断言的是「两个数字仍然不同，且各自的 provenance 解释了这个不同」。
"""
import pytest

from smartbi.gold.restaurant.provenance import (
    ESTIMATED,
    MEASURED,
    ProvenanceError,
    coverage_ratio,
    qualifier,
)
from smartbi.gold.restaurant.generic_executor import CellResult


# ── 覆盖率 < 100% 的场景：10 道菜，只有一半有配方成本 ────────────────
# 全店营收 1000，其中 600 有成本(成本 400)，400 没有配方。
REVENUE_TOTAL = 1000.0
REVENUE_WITH_COST = 600.0
COST_OF_COVERED = 400.0
INDUSTRY_COST_RATIO = 0.32


def _measured_path():
    """AI 问答那条路：只算有成本的部分，未覆盖的排除在结论外。"""
    profit = REVENUE_WITH_COST - COST_OF_COVERED           # 200
    rate = profit / REVENUE_WITH_COST                       # 33.3%
    return profit, rate


def _estimated_path():
    """经营看板那条路：没配方的菜按行业默认成本率折一个毛利，算进总数。"""
    measured_profit = REVENUE_WITH_COST - COST_OF_COVERED   # 200
    uncovered = REVENUE_TOTAL - REVENUE_WITH_COST           # 400
    est_profit = uncovered * (1 - INDUSTRY_COST_RATIO)      # 272
    combined = measured_profit + est_profit                 # 472
    return combined, combined / REVENUE_TOTAL               # 47.2%


def test_the_two_paths_really_do_disagree_at_partial_coverage():
    """先证明这个场景真的能让两条路分叉 —— 否则后面的断言测的是空气。

    ⛔ 这是阳性对照。在覆盖率 100% 的租户上这两个数是相等的，
       那时「provenance 解释了差异」这句话无从检验。
    """
    m_profit, m_rate = _measured_path()
    e_profit, e_rate = _estimated_path()
    assert coverage_ratio(REVENUE_WITH_COST, REVENUE_TOTAL) == pytest.approx(0.6)
    assert m_profit != e_profit, "构造的场景没让两条路分叉, 这个测试就是恒真的"
    assert m_rate == pytest.approx(1 / 3, abs=1e-4)
    assert e_rate == pytest.approx(0.472, abs=1e-4)


def test_each_path_qualifier_explains_its_own_number():
    """两个数字不同 —— 各自的限定语要能解释这个不同。"""
    cov = coverage_ratio(REVENUE_WITH_COST, REVENUE_TOTAL)

    measured_q = qualifier(MEASURED, coverage_ratio=cov)
    estimated_q = qualifier(ESTIMATED, "行业默认成本率 32%", coverage_ratio=cov)

    # MEASURED 那条：说清楚「结论只盖住了 60%」
    assert "60.0%" in measured_q
    assert "不在结论内" in measured_q

    # ESTIMATED 那条：说清楚「另外 40% 是估的，依据是什么」
    assert "40.0%" in estimated_q
    assert "行业默认成本率 32%" in estimated_q
    assert "不是账上的数" in estimated_q

    # 两句话必须不同 —— 相同就说明限定语没有跟着出处走。
    assert measured_q != estimated_q


def test_full_coverage_says_nothing():
    """覆盖率 100% 时 MEASURED 不出限定语 —— 说了是噪音。

    这也解释了为什么 MOCK_REST 上今天看不出问题。
    """
    assert qualifier(MEASURED, coverage_ratio=1.0) == ""
    assert qualifier(MEASURED, coverage_ratio=None) == ""
    # 但 ESTIMATED 任何时候都要说 —— 估出来的数没有「不用说」的情况。
    assert qualifier(ESTIMATED, "行业默认成本率 32%", coverage_ratio=1.0) != ""


def test_estimated_without_basis_is_rejected():
    """一个估出来的数没有出处 = 店长无从判断能不能用它做决定。当场炸。"""
    with pytest.raises(ProvenanceError, match="estimation_basis"):
        qualifier(ESTIMATED, "")
    with pytest.raises(ProvenanceError, match="estimation_basis"):
        CellResult(metric_key="gross_margin", metric_label="毛利", dimension_key="all",
                   aggregation_key="total", unit="元", rows=[], provenance=ESTIMATED)


def test_unknown_provenance_is_rejected_not_downgraded():
    """⛔ 不认识的出处一律炸，不静默当成 MEASURED —— 静默降级正是要防的病。"""
    with pytest.raises(ProvenanceError, match="ALLOCATED_BY_CYCLE"):
        qualifier("ALLOCATED_BY_CYCLE", "按盘点周期摊销")


def test_cellresult_generates_its_qualifier_from_the_field():
    """限定语由字段生成 —— 这是变异对照要打的那个点。

    变异：把 provenance 改成 MEASURED → 限定语必须消失(覆盖率 100% 时)
    或换成另一句(覆盖率 < 100% 时)。改了字段而限定语不变 = 它是手写的。
    """
    est = CellResult(
        metric_key="gross_margin", metric_label="毛利", dimension_key="all",
        aggregation_key="total", unit="元", rows=[{"v": 472.0}],
        provenance=ESTIMATED, estimation_basis="行业默认成本率 32%",
    )
    mea = CellResult(
        metric_key="gross_margin", metric_label="毛利", dimension_key="all",
        aggregation_key="total", unit="元", rows=[{"v": 200.0}],
    )
    assert mea.provenance == MEASURED, "默认必须是 MEASURED(安全的一侧)"

    assert "不是账上的数" in est.qualifier(coverage_ratio=0.6)
    assert "不是账上的数" not in mea.qualifier(coverage_ratio=0.6)
    assert mea.qualifier(coverage_ratio=1.0) == ""
    # 同一个格子, 只改出处 → 限定语跟着变。
    assert est.qualifier(coverage_ratio=0.6) != mea.qualifier(coverage_ratio=0.6)


class _FakeConn:
    """按 SQL 内容分发的假连接 —— 让**真实的** compute_dish_margins 跑起来。

    ⛔ 不是「我自己重算一遍两条路的算术再断言」。上一轮刚踩过同形状的坑:
       源码闸绿是因为它在断言里**模拟**了 sanitize, 而生产那条路根本没调它。
       断言必须驱动被守的那段代码本身。
    """

    def __init__(self, pos_rows, cost_rows):
        self._pos, self._cost = pos_rows, cost_rows

    async def execute(self, *a, **k):
        return None

    async def fetch(self, sql, *a, **k):
        if "fact_pos_item" in sql:
            return self._pos
        if "agg_restaurant_product_cost" in sql:
            return self._cost
        return []


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *a):
                return False

        return _Ctx()


@pytest.mark.asyncio
async def test_real_dish_margin_path_at_partial_coverage(monkeypatch):
    """跑**真实** compute_dish_margins：4 道菜，只有 2 道有配方成本。

    验收 owner 的判据：两条路数字仍然不同，但各自 provenance 能解释这个不同。
    """
    from smartbi.gold.restaurant import dish_margin as dm

    pos_rows = [
        {"dish_name": "红烧肉", "normalized_name": "红烧肉", "qty": 10.0,
         "revenue": 300.0, "bills": 10},
        {"dish_name": "宫保鸡丁", "normalized_name": "宫保鸡丁", "qty": 10.0,
         "revenue": 300.0, "bills": 10},
        {"dish_name": "白灼虾", "normalized_name": "白灼虾", "qty": 10.0,
         "revenue": 200.0, "bills": 10},
        {"dish_name": "例汤", "normalized_name": "例汤", "qty": 10.0,
         "revenue": 200.0, "bills": 10},
    ]
    # 只有前两道桥接得到成本 → 覆盖率 600/1000 = 60%
    cost_rows = [
        {"product_source_pk": "pk-hsr", "food_cost": 20.0},
        {"product_source_pk": "pk-gbjd", "food_cost": 20.0},
    ]

    async def _fake_merge(pool, factory_id, names, mapping):
        return {"红烧肉": "pk-hsr", "宫保鸡丁": "pk-gbjd"}

    monkeypatch.setattr(dm, "merge_cost_product_mapping", _fake_merge)
    pool = _FakePool(_FakeConn(pos_rows, cost_rows))

    data = await dm.compute_dish_margins(pool, "T_PARTIAL", days=30)

    # 阳性对照: 这个场景真的是部分覆盖, 不是 0% 也不是 100%
    assert data["coverage"]["revenueRatio"] == pytest.approx(0.6)
    assert data["coverage"]["dishCount"] == 2
    assert data["coverage"]["totalDishCount"] == 4

    # 两条路的数字: 账上口径 200 vs 掺估算 472 —— **仍然不同**(一个数都没改)
    assert data["totalProfit"] == pytest.approx(200.0)
    assert data["totalProfitWithEstimated"] == pytest.approx(472.0)

    # 而现在每个数字带得出自己的出处
    assert data["provenance"]["totalProfit"] == MEASURED
    assert data["provenance"]["totalProfitWithEstimated"] == ESTIMATED
    assert "行业默认成本率 32%" in data["estimationBasis"]

    # 限定语由字段生成, 且解释了那 272 元是哪来的
    assert "40.0%" in data["qualifier"]
    assert "行业默认成本率 32%" in data["qualifier"]
    assert "不是账上的数" in data["qualifier"]


@pytest.mark.asyncio
async def test_real_dish_margin_path_at_full_coverage_says_nothing(monkeypatch):
    """阴性对照: 全覆盖时不掺估算, 出处退回 MEASURED, 限定语为空。

    ⚠️ 这条就是 MOCK_REST 今天的样子 —— 也是为什么这个缺陷在它身上不发作。
    """
    from smartbi.gold.restaurant import dish_margin as dm

    pos_rows = [{"dish_name": "红烧肉", "normalized_name": "红烧肉", "qty": 10.0,
                 "revenue": 300.0, "bills": 10}]
    cost_rows = [{"product_source_pk": "pk-hsr", "food_cost": 20.0}]

    async def _fake_merge(pool, factory_id, names, mapping):
        return {"红烧肉": "pk-hsr"}

    monkeypatch.setattr(dm, "merge_cost_product_mapping", _fake_merge)
    data = await dm.compute_dish_margins(
        _FakePool(_FakeConn(pos_rows, cost_rows)), "T_FULL", days=30)

    assert data["coverage"]["revenueRatio"] == pytest.approx(1.0)
    assert data["provenance"]["totalProfitWithEstimated"] == MEASURED
    assert data["estimationBasis"] == ""
    assert data["qualifier"] == ""


@pytest.mark.parametrize("covered", [True, False])
@pytest.mark.asyncio
async def test_provenance_and_basis_cannot_diverge(monkeypatch, covered):
    """出处与依据必须同源 —— 「说是账上的数，却附着估算依据」不许存在。

    🔴 这条是**变异实测逼出来的**：第一版两个字段各挂各的条件，把 provenance
       强改成 MEASURED 之后出现了
         provenance=MEASURED + estimationBasis="行业默认成本率 32%"
         + 限定语「未覆盖成本的菜品不在结论内」
       而那个合并毛利里**恰恰含着**那些菜 —— 限定语在替这个数说谎。
    """
    from smartbi.gold.restaurant import dish_margin as dm

    pos_rows = [{"dish_name": "红烧肉", "normalized_name": "红烧肉", "qty": 10.0,
                 "revenue": 300.0, "bills": 10}]
    if not covered:
        pos_rows.append({"dish_name": "例汤", "normalized_name": "例汤", "qty": 10.0,
                         "revenue": 200.0, "bills": 10})

    async def _fake_merge(pool, factory_id, names, mapping):
        return {"红烧肉": "pk-hsr"}

    monkeypatch.setattr(dm, "merge_cost_product_mapping", _fake_merge)
    data = await dm.compute_dish_margins(
        _FakePool(_FakeConn(pos_rows, [{"product_source_pk": "pk-hsr",
                                        "food_cost": 20.0}])), "T", days=30)

    is_estimated = data["provenance"]["totalProfitWithEstimated"] == ESTIMATED
    assert is_estimated == bool(data["estimationBasis"]), (
        f"出处={data['provenance']['totalProfitWithEstimated']} 却 "
        f"estimationBasis={data['estimationBasis']!r} —— 两个字段没同源")
    if not is_estimated:
        assert "行业默认" not in data["qualifier"]


def test_ops_router_qualifier_is_no_longer_hand_written():
    """三处手写限定语已经收敛到一个生成器。

    🔴 收的时候发现第三处的措辞**已经和另外两处不一样了**（「未覆盖成本的部分」
       vs「未覆盖成本的菜品」）—— 手写限定语必然漂移, 这是实证。
    """
    import inspect

    from smartbi.gold.restaurant import restaurant_ops_router

    src = inspect.getsource(restaurant_ops_router)
    assert "未覆盖成本的部分无法判断盈亏" not in src, "第三处手写限定语还在"
    assert "未覆盖成本的菜品无法判断盈亏" not in src, "手写限定语还在正文里"
    assert src.count("provenance_qualifier(PROV_MEASURED") == 4
