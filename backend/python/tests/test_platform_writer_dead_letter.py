"""坏单据隔离到死信表 —— 让游标能推进, 但绝不静默丢数据。

## 改的是什么语义

原本: 任一条记录写失败 → 整页回滚 → 游标不动 → 下轮重拉同一页。
一条**永久性**坏记录会让那类数据**永远停在那一页**。

现在: **写前校验**把永久坏的那部分挑出来隔离到 platform_ingest_dead_letter,
其余照常写, 游标得以推进。瞬时故障(DB 抖动等)仍然照旧卡住重试 —— 两者的
区别由校验判定, 不靠猜。

## ⚠️ 这组测试真正在守的东西

改动方向是「宁可卡住也不漏」→「隔离后继续」, **后果不对称**: 卡住是显性的
(数据停了看得见), 隔离逻辑写错则是**静默丢数据**, 比卡住严重得多。所以:

- `test_隔离写失败必须抛错` 是本文件最重要的一条 —— 隔离没落库就推进游标
  等于把坏记录**连同它那一页之后的一切**悄悄丢掉。
- `test_坏单据不能被静默跳过` 守「必须显式告警」, 禁降级的直接体现。
- `test_校验在写事务之外` 守事务边界: 校验若跑在写事务里, 事实表回滚会把
  隔离记录**一起回滚**, 于是既没写成也没隔离成, 下轮原样再来。
"""
from __future__ import annotations

import datetime

import pytest

from smartbi.ingestion.platforms.models import (
    NormalizedItem, NormalizedOrder, NormalizedPayment,
)
from smartbi.ingestion.platforms.writer import write_orders

FID = "MOCK_REST"


def _order(no="OD1", store="MK01", dish="鸡腿肉", method="wechat"):
    return NormalizedOrder(
        platform="keruyun", platform_order_no=no, store_code=store,
        channel="dine_in", placed_at=datetime.datetime(2026, 7, 29, 12, 0),
        biz_date=datetime.date(2026, 7, 29), gross_cents=1000,
        discount_cents=0, net_cents=1000, guest_count=2,
        items=[NormalizedItem(dish_name=dish, qty=1, price_cents=1000,
                              amount_cents=1000)],
        payments=[NormalizedPayment(method=method, amount_cents=1000)],
    )


class _FakeConn:
    """按 SQL 片段应答, 并记录每条语句执行时是否在事务里。"""

    def __init__(self, *, known_stores=("MK01",), dead_letter_fails=False):
        self.known_stores = set(known_stores)
        self.dead_letter_fails = dead_letter_fails
        self.in_transaction = False
        self.statements = []          # [(sql, args, in_transaction)]
        self._next_id = 100

    def transaction(self):
        conn = self

        class _Tx:
            async def __aenter__(self):
                conn.in_transaction = True

            async def __aexit__(self, *exc):
                conn.in_transaction = False
                return False

        return _Tx()

    def _record(self, sql, args):
        self.statements.append((sql, args, self.in_transaction))

    async def execute(self, sql, *args):
        self._record(sql, args)
        if "platform_ingest_dead_letter" in sql and self.dead_letter_fails:
            raise RuntimeError("死信表写入失败(磁盘满/权限/表不存在)")
        return "OK"

    async def fetchrow(self, sql, *args):
        self._record(sql, args)
        if "platform_store_map" in sql:
            return {"store_id": 7} if args[2] in self.known_stores else None
        if "dim_payment_channel" in sql:
            return {"channel_id": 3}
        if "fact_pos_transaction" in sql:
            self._next_id += 1
            return {"id": self._next_id}
        return None

    async def fetchval(self, sql, *args):
        self._record(sql, args)
        return 55  # product_id

    async def fetch(self, sql, *args):
        self._record(sql, args)
        return []


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


def _dead_letter_stmts(conn):
    return [s for s in conn.statements if "platform_ingest_dead_letter" in s[0]]


def _txn_inserts(conn):
    return [s for s in conn.statements if "INSERT INTO fact_pos_transaction" in s[0]]


# ── 核心行为 ──────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_坏单据进死信表_好单据照常写():
    conn = _FakeConn(known_stores=("MK01",))
    good, bad = _order("OD-good", store="MK01"), _order("OD-bad", store="UNKNOWN")

    written = await write_orders(_FakePool(conn), FID, [good, bad])

    assert written == 1, "好单据没被写进去"
    assert len(_dead_letter_stmts(conn)) == 1, "坏单据没被隔离"
    inserted_bill_nos = [s[1][3] for s in _txn_inserts(conn)]
    assert inserted_bill_nos == ["OD-good"], f"坏单据混进了事实表: {inserted_bill_nos}"


@pytest.mark.asyncio
async def test_全是坏单据也不抛错_游标才能推进():
    """整页都坏时若仍抛错, 游标照样卡死 —— 那这次改动就白做了。"""
    conn = _FakeConn(known_stores=())
    written = await write_orders(_FakePool(conn), FID, [_order("OD1", store="X")])
    assert written == 0
    assert len(_dead_letter_stmts(conn)) == 1


@pytest.mark.asyncio
async def test_隔离写失败必须抛错():
    """**本文件最重要的一条。**

    隔离没落库却让游标推进 = 把坏记录连同它那一页之后的一切悄悄丢掉。
    宁可退回「卡住」也不能丢。
    """
    conn = _FakeConn(known_stores=("MK01",), dead_letter_fails=True)
    with pytest.raises(Exception):
        await write_orders(_FakePool(conn), FID,
                           [_order("OD-good"), _order("OD-bad", store="X")])


@pytest.mark.asyncio
async def test_坏单据不能被静默跳过(caplog):
    """禁降级: 隔离了就必须显式告警, 不能悄悄少一条。"""
    conn = _FakeConn(known_stores=())
    with caplog.at_level("ERROR"):
        await write_orders(_FakePool(conn), FID, [_order("OD1", store="X")])
    assert any("DEAD-LETTER" in r.message or "死信" in r.getMessage()
               for r in caplog.records), f"没有 ERROR 级告警: {caplog.records}"


@pytest.mark.asyncio
async def test_校验在写事务之外():
    """校验若跑在写事务里, 事实表回滚会把隔离记录一起回滚 ——
    既没写成也没隔离成, 下轮原样再来, 等于没改。"""
    conn = _FakeConn(known_stores=("MK01",))
    await write_orders(_FakePool(conn), FID,
                       [_order("OD-good"), _order("OD-bad", store="X")])
    dl = _dead_letter_stmts(conn)
    assert dl, "没有隔离语句"
    # 隔离必须发生在**它自己的**事务里, 且与事实表插入不在同一个事务块
    assert dl[0][2] is True, "隔离写没在事务里 —— RLS 的 GUC 是事务级的"
    dl_idx = conn.statements.index(dl[0])
    txn_idx = [conn.statements.index(s) for s in _txn_inserts(conn)]
    assert all(i > dl_idx for i in txn_idx), (
        "事实表写在隔离之前 —— 隔离必须先落库, 否则中途失败会丢数据"
    )


# ── 各类永久坏记录都要被识别 ──────────────────────────────────────────

@pytest.mark.asyncio
@pytest.mark.parametrize("order,label", [
    (_order("OD1", store="NOPE"), "门店映射查不到"),
    (_order("OD2", dish=""), "菜名为空"),
    # 实测: normalize_for_dim 不把 ★ 当标点('★（）' -> '★', 非空), 所以那不算坏。
    # 真正会被吃空的是纯标点: '（）' / '--' / '...' 都 -> ''。
    (_order("OD3", dish="（）"), "菜名归一化后为空"),
    (_order("OD4", method="bitcoin"), "支付方式未知"),
])
async def test_四类永久坏记录都被隔离(order, label):
    conn = _FakeConn(known_stores=("MK01",))
    written = await write_orders(_FakePool(conn), FID, [order])
    assert written == 0, f"{label} 的坏单据被写进了事实表"
    assert len(_dead_letter_stmts(conn)) == 1, f"{label} 没被隔离"


@pytest.mark.asyncio
async def test_全好时不碰死信表():
    conn = _FakeConn(known_stores=("MK01",))
    written = await write_orders(_FakePool(conn), FID, [_order("A"), _order("B")])
    assert written == 2
    assert _dead_letter_stmts(conn) == []
