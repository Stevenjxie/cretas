"""两个 prod 实拍缺陷 —— 目录闸从未真正生效, 以及澄清被当计划缓存 6 小时。

2026-07-30 部署 #2007/#2008 后在 prod 日志里发现:

## 缺陷 1: dim_product_alias 不存在 → 整个目录加载失败

    asyncpg.exceptions.UndefinedTableError: relation "dim_product_alias" does not exist
    [dish-catalogue] load failed for MOCK_REST

``load_dish_catalogue`` 把 dim_product 与 dim_product_alias **UNION 在一条 SQL
里**, 别名表在 smartbi_prod_db 不存在 → 整条查询抛错 → fail-open 返回 None →
**#2007 的菜单目录闸在 prod 上从来没生效过**。

单元测试全绿掩盖了它: fake pool 永远不会报「表不存在」。所以这里补的测试必须
**显式模拟缺表**, 而不是只测 happy path。

必需表(dim_product)缺失仍应 fail-open —— 目录不可用不能挡住问答。

## 缺陷 2: 需要澄清的计划被写进零 token 计划缓存

prod 日志:

    zero-token plan-cache hit: authority=validated_plan_cache
    intent=RESTAURANT_OPS_WASTAGE_TOP clarification=True stale=False
    query=本月全部门店食材损耗成本是多少

``_semantic_plan_cache_put`` 的写入闸检查 zero_token_eligible /
plan_is_replayable / 时间槽是否来自历史, **唯独不检查 clarification_needed**。
于是 LLM 抖动窗口里产生的一句「我还缺一个关键信息」被当成可重放计划缓存,
TTL 6 小时 —— 之后每次问同一句都零 token 重放那句澄清, 稳定复现、看起来像
代码回归(实测 3/3), 实际是缓存投毒。

澄清天生不该缓存: 它表示「这轮没拿到足够信息」, 是**当时那一次**的状态, 不是
这句话的稳定计划; 而且重算澄清很便宜, 缓存它省不下什么, 却会把一次偶发故障
冻结成 6 小时的确定性错误答案。
"""
from __future__ import annotations

import pytest

import smartbi.gold.restaurant.restaurant_intent as RI
from smartbi.gold.restaurant import restaurant_ops_router as R


# ── 缺陷 1: 别名表缺失不得拖垮整个目录 ──────────────────────────────


class _Conn:
    def __init__(self, *, alias_missing: bool, product_missing: bool = False):
        self.alias_missing = alias_missing
        self.product_missing = product_missing
        self.queries = []

    async def execute(self, *a):
        return "SELECT 1"

    async def fetch(self, sql, *a):
        self.queries.append(sql)
        if "dim_product_alias" in sql:
            if self.alias_missing:
                raise RuntimeError('relation "dim_product_alias" does not exist')
            return [{"name": "招牌藤椒味(单人份)", "normalized_name": None}]
        if self.product_missing:
            raise RuntimeError('relation "dim_product" does not exist')
        return [
            {"name": "米饭", "normalized_name": "米饭"},
            {"name": "娃娃菜", "normalized_name": "娃娃菜"},
        ]


class _Pool:
    def __init__(self, conn):
        self.conn = conn

    def acquire(self):
        conn = self.conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


@pytest.fixture(autouse=True)
def _enable_catalogue_gate(monkeypatch):
    """目录闸 2026-07-30 起默认关闭(见 load_dish_catalogue 的 kill switch);
    这些用例测的就是闸本身, 显式打开。"""
    monkeypatch.setenv("RESTAURANT_DISH_CATALOGUE_GATE", "1")


@pytest.fixture(autouse=True)
def _clear_catalogue_cache():
    R._DISH_CATALOGUE_CACHE.clear()
    yield
    R._DISH_CATALOGUE_CACHE.clear()


@pytest.mark.asyncio
async def test_missing_alias_table_still_yields_the_product_catalogue():
    """prod 实拍: 别名表不存在时, 菜单目录必须照常从 dim_product 建起来。"""
    conn = _Conn(alias_missing=True)
    names = await R.load_dish_catalogue(_Pool(conn), "F_NOALIAS")

    assert names == frozenset({"米饭", "娃娃菜"}), (
        "别名表缺失把整个目录拖垮了 —— 目录闸会静默失效"
    )


@pytest.mark.asyncio
async def test_alias_names_are_included_when_the_table_exists():
    conn = _Conn(alias_missing=False)
    names = await R.load_dish_catalogue(_Pool(conn), "F_ALIAS")

    assert "招牌藤椒味(单人份)" in names
    assert "米饭" in names


@pytest.mark.asyncio
async def test_missing_product_table_still_fails_open():
    """必需表也缺 → 返回 None(目录不可用), 绝不抛错挡住问答。"""
    conn = _Conn(alias_missing=True, product_missing=True)
    assert await R.load_dish_catalogue(_Pool(conn), "F_NOTHING") is None


# ── 缺陷 2: 澄清不得进零 token 计划缓存 ─────────────────────────────


def _spec(*, clarification_needed: bool):
    return RI.RestaurantQuerySpec(
        intent="RESTAURANT_OPS_WASTAGE_TOP",
        domain="restaurant",
        date_range=(None, None),
        window_label="本月",
        relative_window=True,
        metrics=(),
        wants_margin=False,
        asks_profitability=False,
        dimensions=(),
        comparison=None,
        confidence=0.9,
        source_tier="llm",
        clarification_needed=clarification_needed,
    )


def test_clarification_plan_is_not_cached():
    """一次 LLM 抖动不能冻结成 6 小时的确定性错答案。"""
    RI.clear_semantic_plan_cache()
    RI._semantic_plan_cache_put(
        "F1", "本月全部门店食材损耗成本是多少", {"intent": "X"},
        spec=_spec(clarification_needed=True),
    )
    assert not RI._SEMANTIC_PLAN_CACHE, "澄清被写进了零 token 计划缓存"


def test_normal_plan_is_still_cached():
    """对照组: 正常计划照旧缓存 —— 零 token 出口不能被这次修复废掉。"""
    RI.clear_semantic_plan_cache()
    RI._semantic_plan_cache_put(
        "F1", "本月营收多少", {"intent": "X"},
        spec=_spec(clarification_needed=False),
    )
    assert RI._SEMANTIC_PLAN_CACHE
    RI.clear_semantic_plan_cache()
