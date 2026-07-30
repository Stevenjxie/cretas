"""有流水却没 Gold 汇总的租户, 不能悄无声息地整个不可用。

2026-07-31 实测(prod): `R_GML_DEMO` 有 **16,213 笔 POS 流水、132 家门店**, 但
`agg_restaurant_daily_totals` **一行都没有**。而餐饮租户的判据正是这张表 ——
于是这个租户的每一个问题都在 0.0 秒返回 `None`, **日志里连一个字都没有**
(grep 该 factory_id: 0 命中, grep "tenant gate": 0 命中)。

也就是说: 餐饮 AI 对这个租户 **100% 不可用, 而且完全无声**。没人会发现, 直到
客户来问「为什么问什么都没反应」。

⛔ 修法**不是**把闸放开: 没有 Gold 汇总, resolver 只会返回空数据 —— 那是把
「没反应」换成「一本正经地答 0」, 更糟。修的是**可见性**: 判成「不是餐饮租户」
之前, 先看它有没有 POS 流水; 有的话说明这是个**该能用却没物化**的租户, 必须
留下一条能 grep 的告警。

这一类(「换个租户就整体降级」)今天已经撞到第二次了 —— 第一次是 #2045(堂食外卖
resolver 照 DEMO_REST 的中文枚举写, 换个租户占比一条都不出)。
"""
from __future__ import annotations

import logging

import pytest

from smartbi.gold.restaurant import restaurant_intent as ri


class _Conn:
    """asyncpg 连接替身: 按 SQL 里出现的表名返回不同结果。"""

    def __init__(self, *, has_gold: bool, has_pos: bool):
        self.has_gold = has_gold
        self.has_pos = has_pos
        self.queried_tables: list[str] = []

    def transaction(self):
        class _Ctx:
            async def __aenter__(self_inner):
                return None

            async def __aexit__(self_inner, *exc):
                return False

        return _Ctx()

    async def execute(self, *args, **kwargs):
        return None

    async def fetchrow(self, sql, *args):
        if "agg_restaurant_daily_totals" in sql:
            self.queried_tables.append("gold")
            return {"?column?": 1} if self.has_gold else None
        if "fact_pos_transaction" in sql:
            self.queried_tables.append("silver")
            return {"?column?": 1} if self.has_pos else None
        return None


class _Pool:
    def __init__(self, conn: _Conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Ctx:
            async def __aenter__(self):
                return conn

            async def __aexit__(self, *exc):
                return False

        return _Ctx()


@pytest.fixture(autouse=True)
def _clear_cache():
    ri._RESTAURANT_TENANT_CACHE.clear()
    yield
    ri._RESTAURANT_TENANT_CACHE.clear()


@pytest.mark.asyncio
async def test_tenant_with_gold_is_a_restaurant_tenant():
    conn = _Conn(has_gold=True, has_pos=True)
    assert await ri._is_restaurant_tenant(_Pool(conn), "MOCK_REST") is True
    assert "silver" not in conn.queried_tables, "有 Gold 就不该再多查一次 Silver"


@pytest.mark.asyncio
async def test_pos_only_tenant_is_a_restaurant_tenant(caplog):
    """R_GML_DEMO 的形态: 有 POS 流水、没有后厨数据。

    ⚠️ 这条断言 2026-07-31 **反转过**(原本断言 False)。改的不是测试口径, 是
    发现判据本身选错了对象: `agg_restaurant_daily_totals` 只由后厨事实表
    (领料/损耗/盘点)驱动, 与 POS 无关 —— 拿它决定 POS 类问题能不能回答是错的。
    R_GML_DEMO 有 16,213 笔流水、132 家门店, 营收/菜品/门店/渠道问题本来完全
    答得了, 却因为没上传后厨数据被整个关掉。
    原测试守的「必须留下能 grep 的痕迹」保留了(下面仍断言日志带 factory_id)。
    """
    conn = _Conn(has_gold=False, has_pos=True)
    with caplog.at_level(logging.INFO):
        result = await ri._is_restaurant_tenant(_Pool(conn), "R_GML_DEMO")
    assert result is True
    assert "R_GML_DEMO" in caplog.text, "日志必须带 factory_id, 否则没法定位租户"


@pytest.mark.asyncio
async def test_kitchen_ops_tenant_still_passes_without_the_extra_probe():
    """有后厨聚合的租户走原来那条路, 不受本次放宽影响。"""
    conn = _Conn(has_gold=True, has_pos=False)
    assert await ri._is_restaurant_tenant(_Pool(conn), "DEMO_REST") is True
    assert "silver" not in conn.queried_tables


@pytest.mark.asyncio
async def test_genuinely_non_restaurant_tenant_stays_quiet(caplog):
    """真的没有餐饮数据的租户(如 R_SSW_DEMO: 0 笔流水)不该刷告警 ——
    每次问答都打一条无意义的 WARNING, 只会把真正的告警淹掉。"""
    conn = _Conn(has_gold=False, has_pos=False)
    with caplog.at_level(logging.WARNING):
        assert await ri._is_restaurant_tenant(_Pool(conn), "SOME_FACTORY") is False
    assert "SOME_FACTORY" not in caplog.text


@pytest.mark.asyncio
async def test_the_extra_lookup_does_not_run_on_every_request(caplog):
    """第二次查询只在「判否」那一次发生, 结果照旧进缓存 —— 否则给每个非餐饮
    租户的每个请求都加一次 SQL。"""
    conn = _Conn(has_gold=False, has_pos=True)
    await ri._is_restaurant_tenant(_Pool(conn), "R_GML_DEMO")
    first = list(conn.queried_tables)
    await ri._is_restaurant_tenant(_Pool(conn), "R_GML_DEMO")
    assert conn.queried_tables == first, "第二次调用应命中缓存, 不再查库"
