"""connector 框架: 游标推进、幂等、失败隔离、禁降级。

不碰 DB / 不碰网络 —— adapter 与 writer 都用假实现注入。
"""
import datetime

import pytest

from smartbi.ingestion.platforms.framework import PlatformSyncError, sync_platform
from smartbi.ingestion.platforms.models import FetchPage, NormalizedOrder


def _order(no: str) -> NormalizedOrder:
    return NormalizedOrder(
        platform="keruyun", platform_order_no=no, store_code="MK01",
        channel="dine_in", placed_at=datetime.datetime(2026, 7, 29, 12, 0),
        biz_date=datetime.date(2026, 7, 29), gross_cents=1000,
        discount_cents=0, net_cents=1000, guest_count=2, items=[], payments=[],
    )


class _FakeAdapter:
    platform = "keruyun"

    def __init__(self, pages):
        self._pages = pages
        self.seen_cursors = []

    async def fetch_page(self, cursor, limit):
        self.seen_cursors.append(cursor)
        if not self._pages:
            return FetchPage(orders=[], next_cursor=cursor, has_more=False)
        return self._pages.pop(0)


class _FakeCursorStore(dict):
    pass


@pytest.mark.asyncio
async def test_多页拉取游标逐页推进(monkeypatch):
    written = []
    adapter = _FakeAdapter([
        FetchPage(orders=[_order("A1")], next_cursor="10", has_more=True),
        FetchPage(orders=[_order("A2")], next_cursor="20", has_more=False),
    ])
    store = {"cursor": "0"}

    async def _read(pool, factory_id, platform):
        return store["cursor"]

    async def _write(pool, factory_id, platform, cursor):
        store["cursor"] = cursor

    async def _write_orders(pool, factory_id, orders):
        written.extend(o.platform_order_no for o in orders)
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    n = await sync_platform(None, adapter, factory_id="MOCK_REST",
                            write_orders=_write_orders)
    assert n == 2
    assert written == ["A1", "A2"]
    assert adapter.seen_cursors == ["0", "10"]
    assert store["cursor"] == "20"


@pytest.mark.asyncio
async def test_写入失败不推进游标_下轮可重拉(monkeypatch):
    adapter = _FakeAdapter([
        FetchPage(orders=[_order("B1")], next_cursor="10", has_more=False),
    ])
    store = {"cursor": "0"}

    async def _read(pool, factory_id, platform):
        return store["cursor"]

    async def _write(pool, factory_id, platform, cursor):
        store["cursor"] = cursor

    async def _boom(pool, factory_id, orders):
        raise RuntimeError("silver 写入失败")

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    with pytest.raises(PlatformSyncError):
        await sync_platform(None, adapter, factory_id="MOCK_REST", write_orders=_boom)
    assert store["cursor"] == "0", "写失败必须保持游标不动，否则那批数据永久丢失"


@pytest.mark.asyncio
async def test_拉取失败明确抛错不静默当成无数据(monkeypatch):
    class _BrokenAdapter:
        platform = "keruyun"

        async def fetch_page(self, cursor, limit):
            raise ConnectionError("平台不可达")

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        raise AssertionError("不该推进游标")

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    async def _noop(pool, factory_id, orders):
        return 0

    with pytest.raises(PlatformSyncError, match="平台不可达"):
        await sync_platform(None, _BrokenAdapter(), factory_id="MOCK_REST",
                            write_orders=_noop)


@pytest.mark.asyncio
async def test_翻页有上限防打满(monkeypatch):
    adapter = _FakeAdapter([
        FetchPage(orders=[_order(f"C{i}")], next_cursor=str(i), has_more=True)
        for i in range(1, 100)
    ])

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        pass

    async def _write_orders(pool, factory_id, orders):
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    n = await sync_platform(None, adapter, factory_id="MOCK_REST",
                            write_orders=_write_orders, max_pages=5)
    assert n == 5, "单轮最多翻 max_pages 页，剩下的留给下一轮"


@pytest.mark.asyncio
async def test_游标读失败被隔离不打断其他平台(monkeypatch):
    """失败隔离是硬契约: 游标读写抛的不是 PlatformSyncError 时,
    也不能穿透 sync_all 打断 for 循环 —— 否则排在后面的平台整轮不同步。
    """
    from smartbi.ingestion.platforms.framework import sync_all

    class _A:
        platform = "bad"
        async def fetch_page(self, cursor, limit):
            raise AssertionError("不该走到这里")

    class _B:
        platform = "good"
        async def fetch_page(self, cursor, limit):
            return FetchPage(orders=[_order("OK1")], next_cursor="1", has_more=False)

    async def _read(pool, factory_id, platform):
        if platform == "bad":
            raise RuntimeError("模拟 asyncpg.PostgresError")   # 非 PlatformSyncError
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        pass

    async def _write_orders(pool, factory_id, orders):
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    results = await sync_all(None, [_A(), _B()], factory_id="MOCK_REST",
                             write_orders=_write_orders)
    assert str(results["bad"]).startswith("ERROR"), "坏平台应被记为错误"
    assert results["good"] == 1, "好平台必须照常同步 —— 失败隔离没生效就是这里挂"


@pytest.mark.asyncio
async def test_推进游标失败保持可重拉(monkeypatch):
    """write_cursor 失败必须转成 PlatformSyncError 抛出, 而不是静默吞掉。
    吞掉的话本轮看起来成功, 但游标没推进, 下轮会重复处理(靠幂等兜住)——
    问题是调用方完全不知道出过错。
    """
    from smartbi.ingestion.platforms.framework import PlatformSyncError, sync_platform

    adapter = _FakeAdapter([FetchPage(orders=[_order("D1")], next_cursor="9", has_more=False)])

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        raise RuntimeError("模拟写游标失败")

    async def _write_orders(pool, factory_id, orders):
        return len(orders)

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    with pytest.raises(PlatformSyncError, match="推进游标失败"):
        await sync_platform(None, adapter, factory_id="MOCK_REST",
                            write_orders=_write_orders)


@pytest.mark.asyncio
async def test_adapter返回畸形页也转成PlatformSyncError(monkeypatch):
    """adapter 返回 None / 缺字段时, 裸 AttributeError 不能绕过统一错误包装 ——
    绕过去的话 sync_all 那层只 except PlatformSyncError 的分支就接不住,
    要靠最后的宽兜底才拦得下, 而那时错误信息里已经没有平台/游标上下文了。
    """
    from smartbi.ingestion.platforms.framework import PlatformSyncError, sync_platform

    class _Malformed:
        platform = "broken"
        async def fetch_page(self, cursor, limit):
            return None            # 畸形返回

    async def _read(pool, factory_id, platform):
        return "0"

    async def _write(pool, factory_id, platform, cursor):
        raise AssertionError("不该推进游标")

    async def _write_orders(pool, factory_id, orders):
        return 0

    monkeypatch.setattr("smartbi.ingestion.platforms.framework.read_cursor", _read)
    monkeypatch.setattr("smartbi.ingestion.platforms.framework.write_cursor", _write)

    with pytest.raises(PlatformSyncError, match="拉取失败"):
        await sync_platform(None, _Malformed(), factory_id="MOCK_REST",
                            write_orders=_write_orders)


# ---------------------------------------------------------------------------
# cursor_store.py 结构性测试: 锁住 "set_config 必须在 conn.transaction() 内,
# 且和后续查询同一连接" 这个不变量 —— 本仓踩过的坑 (asyncpg 上 set_config(..., true)
# 不开显式事务就从不生效, RLS 靠连接池残留碰运气)。
# ---------------------------------------------------------------------------

class _TxnCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        self._conn.in_txn = True

    async def __aexit__(self, *exc):
        self._conn.in_txn = False


class _RecordingConn:
    def __init__(self):
        self.calls = []  # (kind, sql, in_txn_at_call_time)
        self.in_txn = False

    def transaction(self):
        return _TxnCtx(self)

    async def execute(self, sql, *args):
        self.calls.append(("execute", sql, self.in_txn))

    async def fetchrow(self, sql, *args):
        self.calls.append(("fetchrow", sql, self.in_txn))
        return None


class _AcquireCtx:
    def __init__(self, conn):
        self._conn = conn

    async def __aenter__(self):
        return self._conn

    async def __aexit__(self, *exc):
        pass


class _FakePool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return _AcquireCtx(self._conn)


@pytest.mark.asyncio
async def test_读游标的set_config必须在同一事务内(monkeypatch):
    from smartbi.ingestion.platforms import cursor_store

    conn = _RecordingConn()
    pool = _FakePool(conn)

    await cursor_store.read_cursor(pool, "MOCK_REST", "keruyun")

    assert conn.calls, "应该产生实际的 execute/fetchrow 调用"
    assert all(in_txn for (_, _, in_txn) in conn.calls), (
        "set_config 与后续查询必须包在同一个 conn.transaction() 内, "
        "否则 asyncpg 上 set_config(..., true) 不生效 (本仓踩过的坑)。"
        "如果这条测试变红, 大概率是有人删掉了 `async with conn.transaction():`"
    )
    kinds = [c[0] for c in conn.calls]
    assert "execute" in kinds and "fetchrow" in kinds
    assert "set_config" in conn.calls[0][1]


@pytest.mark.asyncio
async def test_写游标的set_config必须在同一事务内(monkeypatch):
    from smartbi.ingestion.platforms import cursor_store

    conn = _RecordingConn()
    pool = _FakePool(conn)

    await cursor_store.write_cursor(pool, "MOCK_REST", "keruyun", "42")

    assert conn.calls, "应该产生实际的 execute 调用"
    assert all(in_txn for (_, _, in_txn) in conn.calls), (
        "set_config 与后续 INSERT ... ON CONFLICT 必须包在同一个 conn.transaction() 内, "
        "否则 RLS 靠连接池残留碰运气。如果这条测试变红, 大概率是有人删掉了 "
        "`async with conn.transaction():`"
    )
    assert "set_config" in conn.calls[0][1]
    assert "platform_sync_cursor" in conn.calls[1][1]
