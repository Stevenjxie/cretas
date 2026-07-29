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
