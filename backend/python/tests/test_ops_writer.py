"""后厨单据 → Silver 三表。

假连接记录每条语句「当时是否在事务里」—— 否则「必须在事务内」这类断言只
验得到执行顺序，验不到事务边界，把 set_config 挪出事务块测试照样绿
（2026-07-29 在订单 writer 上真踩过这个「名字说谎的测试」）。
"""
import datetime

import pytest

from smartbi.ingestion.platforms.ops_models import (
    NormalizedIngredientRef, NormalizedRequisition, NormalizedStocktaking,
    NormalizedWastage,
)
from smartbi.ingestion.platforms.ops_writer import write_ops

FID = "MOCK_REST"


def _ref(name="鸡腿肉", category="肉类", unit="kg"):
    return NormalizedIngredientRef(name=name, category=category, unit=unit)


def _req(doc="RQ1", name="鸡腿肉", status="APPROVED"):
    return NormalizedRequisition(
        platform="keruyun", doc_no=doc, store_code="MK01",
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(name),
        qty_milli=12500, cost_cents=30000, status=status,
    )


def _waste(doc="WS1", wtype="变质"):
    return NormalizedWastage(
        platform="keruyun", doc_no=doc, store_code="MK01",
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        wastage_type=wtype, status="APPROVED", qty_milli=500, cost_cents=1200,
    )


def _stock(doc="ST1", system=10000, actual=9700):
    return NormalizedStocktaking(
        platform="keruyun", doc_no=doc, store_code="MK01",
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        status="COMPLETED", system_qty_milli=system,
        actual_qty_milli=actual, diff_cost_cents=-720,
    )


class _FakeConn:
    def __init__(self):
        self.in_transaction = False
        self.executed = []          # [(sql, args, in_transaction)]
        self.ingredient_upserts = []
        self._next_id = 500

    async def execute(self, sql, *args):
        self.executed.append((sql, args, self.in_transaction))
        return "INSERT 0 1"

    async def fetchval(self, sql, *args):
        self.executed.append((sql, args, self.in_transaction))
        assert "dim_ingredient" in sql, f"fetchval 只该用于食材维度: {sql[:60]}"
        # args = (factory_id, source_pk, name, normalized_name, category, unit)
        self.ingredient_upserts.append((args[1], args[2], args[3], args[4], args[5]))
        self._next_id += 1
        return self._next_id

    def transaction(self):
        conn = self

        class _Txn:
            async def __aenter__(self_inner):
                conn.in_transaction = True
                return conn

            async def __aexit__(self_inner, *exc):
                conn.in_transaction = False
                return False

        return _Txn()


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        conn = self._conn

        class _Acq:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
                return False

        return _Acq()


def _sql_for(conn, table):
    return [s for s, _, _ in conn.executed if table in s]


@pytest.mark.asyncio
async def test_领料写进正确的表与列():
    conn = _FakeConn()
    n = await write_ops(_FakePool(conn), FID, "requisition", [_req()])
    assert n == 1
    sql = _sql_for(conn, "fact_restaurant_requisition")
    assert len(sql) == 1
    # 列名各表不同, 串了就是写错表
    assert "requested_qty" in sql[0] and "est_cost" in sql[0]
    assert "quantity" not in sql[0], "quantity 是损耗表的列, 不是领料表的"


@pytest.mark.asyncio
async def test_损耗与盘点各写各的表与列():
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "wastage", [_waste()])
    await write_ops(_FakePool(conn), FID, "stocktaking", [_stock()])
    w = _sql_for(conn, "fact_restaurant_wastage")[0]
    s = _sql_for(conn, "fact_restaurant_stocktaking")[0]
    assert "quantity" in w and "estimated_cost" in w
    assert "system_qty" in s and "difference_cost" in s


@pytest.mark.asyncio
async def test_毫单位换算成四位小数_分换算成元():
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req()])
    _, args, _ = next((s, a, t) for s, a, t in conn.executed
                      if "fact_restaurant_requisition" in s)
    # (factory, source_pk, doc_no, date, ing_id, status, qty, unit, cost)
    assert str(args[6]) == "12.5000", args
    assert str(args[8]) == "300.00", args


@pytest.mark.asyncio
async def test_三类单据的状态都原样落库_一个字都不编():
    """🔴 各表 Gold 过滤口径不同(领料 APPROVED/SUBMITTED、损耗 APPROVED、
    盘点 COMPLETED), 且 status 上**没有 CHECK 约束**。写个不在词表里的值
    不会报错, 行进得去、闸能开、AI 照答, 但按食材的 KPI 全空 ——
    "没数据"看起来就像"是 0"。所以一律透传。"""
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req(status="DRAFT")])
    _, args, _ = next((s, a, t) for s, a, t in conn.executed
                      if "fact_restaurant_requisition" in s)
    assert args[5] == "DRAFT"

    conn2 = _FakeConn()
    w = NormalizedWastage(
        platform="keruyun", doc_no="WX", store_code="MK01",
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        wastage_type="变质", status="REJECTED", qty_milli=1, cost_cents=1)
    await write_ops(_FakePool(conn2), FID, "wastage", [w])
    _, wargs, _ = next((s, a, t) for s, a, t in conn2.executed
                       if "fact_restaurant_wastage" in s)
    assert "REJECTED" in wargs, wargs

    conn3 = _FakeConn()
    st = NormalizedStocktaking(
        platform="keruyun", doc_no="SX", store_code="MK01",
        biz_date=datetime.date(2026, 7, 27), ingredient=_ref(),
        status="IN_PROGRESS", system_qty_milli=1, actual_qty_milli=1,
        diff_cost_cents=0)
    await write_ops(_FakePool(conn3), FID, "stocktaking", [st])
    _, sargs, _ = next((s, a, t) for s, a, t in conn3.executed
                       if "fact_restaurant_stocktaking" in s)
    assert "IN_PROGRESS" in sargs, sargs


@pytest.mark.asyncio
async def test_不写actual_qty_不拿requested顶替():
    """真实来源里 requested 与 actual 是两个字段(领了多少 vs 实到多少)。
    拿 requested 顶 actual 会让"请领差异"恒为 0, 与"执行完美"无法区分。"""
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req()])
    sql = _sql_for(conn, "fact_restaurant_requisition")[0]
    assert "actual_qty" not in sql, "平台没给就留 NULL, 不编"


@pytest.mark.asyncio
async def test_食材名归一化后再匹配_同一食材只建一次():
    """🔴 平台只给名字。不归一化的话「鸡腿肉」「鸡腿肉 」「鸡·腿肉」会裂成
    三个食材, 领料合计随之碎掉 —— 与 fact_pos_item.product_id 那次同类事故。"""
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [
        _req(doc="RQ1", name="鸡腿肉"),
        _req(doc="RQ2", name="鸡腿肉 "),
        _req(doc="RQ3", name="鸡·腿肉"),
    ])
    assert len(conn.ingredient_upserts) == 1, conn.ingredient_upserts
    assert conn.ingredient_upserts[0][2] == "鸡腿肉"      # normalized_name


@pytest.mark.asyncio
async def test_食材source_pk由归一化名确定性推出():
    """dim_ingredient 上有两个唯一约束 (factory,normalized_name) 与
    (factory,source_pk)。ON CONFLICT 只挂得住一个, 所以 source_pk 必须由
    normalized_name 确定性推出, 两个约束才 1:1 对齐; 否则会在没挂的那个
    约束上撞唯一冲突而 ON CONFLICT 捕不到。"""
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req(name="鸡·腿肉")])
    source_pk, name, normalized, _, _ = conn.ingredient_upserts[0]
    assert normalized in source_pk
    assert name == "鸡·腿肉", "name 保留原始写法"


@pytest.mark.asyncio
async def test_食材名为空就报错_不塞未知食材():
    conn = _FakeConn()
    bad = NormalizedRequisition(
        platform="keruyun", doc_no="RQ9", store_code="MK01",
        biz_date=datetime.date(2026, 7, 27),
        ingredient=NormalizedIngredientRef(name="  "),
        qty_milli=1, cost_cents=1, status="APPROVED",
    )
    with pytest.raises(RuntimeError, match="食材名"):
        await write_ops(_FakePool(conn), FID, "requisition", [bad])


@pytest.mark.asyncio
async def test_RLS_必须先设factory_id且在事务内():
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req()])
    first_sql, first_args, first_in_txn = conn.executed[0]
    assert "set_config" in first_sql and "app.factory_id" in first_sql
    assert first_args[0] == FID
    assert first_in_txn is True, (
        "set_config(...,true) 是事务级的 —— 挪出事务它就从不生效, "
        "而这几张表的 RLS 没有 __internal__ 逃生门"
    )
    assert all(t for _, _, t in conn.executed), "所有语句都必须在同一事务内"


@pytest.mark.asyncio
async def test_未知单据类型直接拒绝():
    conn = _FakeConn()
    with pytest.raises(ValueError, match="未知单据类型"):
        await write_ops(_FakePool(conn), FID, "../etc", [_req()])
    assert conn.executed == [], "拒绝要发生在碰库之前"


@pytest.mark.asyncio
async def test_空批次不碰数据库():
    conn = _FakeConn()
    assert await write_ops(_FakePool(conn), FID, "requisition", []) == 0
    assert conn.executed == []


@pytest.mark.asyncio
async def test_幂等键是source_pk():
    """单据可修订(盘点复核/损耗补录), 每次以平台最新值为准覆盖。"""
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "requisition", [_req()])
    sql = _sql_for(conn, "fact_restaurant_requisition")[0]
    assert "ON CONFLICT (factory_id, source_pk)" in sql
    assert "DO UPDATE" in sql


@pytest.mark.asyncio
async def test_盘点差异量由实盘减系统账算出():
    conn = _FakeConn()
    await write_ops(_FakePool(conn), FID, "stocktaking",
                    [_stock(system=10000, actual=9700)])
    _, args, _ = next((s, a, t) for s, a, t in conn.executed
                      if "fact_restaurant_stocktaking" in s)
    # (factory, source_pk, doc, date, ing, status, system, actual, diff_qty, diff_cost, unit)
    assert str(args[6]) == "10.0000" and str(args[7]) == "9.7000"
    assert str(args[8]) == "-0.3000", "盘亏应当是负数"
