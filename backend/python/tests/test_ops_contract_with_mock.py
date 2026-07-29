"""跨两侧的契约测试：模拟端真发出来的报文，adapter 真能解析。

为什么需要它：`test_ops_adapter.py` 用的是手写的假报文，`test_keruyun_api.py`
断言的是模拟端自己发的字段名。两边各自都绿，但**没有任何测试把它们对起来**
—— 模拟端哪天把 `wastageType` 写成 `wasteType`，两边测试照样全绿，只会在线上
拉取时炸成一个 `KeruyunPayloadError`。

这里把模拟端的真 FastAPI app 起起来，用真 sqlite 造数据，让真 adapter 去拉，
中间只替换传输层（把 httpx 换成 TestClient）。字段名一错就红。

同一个思路在订单侧已经有先例：`test_支付方式映射覆盖模拟端全部支付方式`
从模拟端源码真解析支付方式集合，而不是手抄一份。
"""
import datetime
import pathlib
import random

import pytest

from smartbi.ingestion.platforms.ops_keruyun import KeruyunOpsAdapter
from smartbi.ingestion.platforms.ops_models import (
    NormalizedRequisition, NormalizedStocktaking, NormalizedWastage,
)

APP_KEY = "contract-key"
APP_SECRET = "contract-secret"
# 周一 —— 盘点每周只做一次，非周一那天盘点单据本来就是空的。
_MONDAY = "2026-07-27"


@pytest.fixture()
def mock_client(tmp_path, monkeypatch):
    monkeypatch.syspath_prepend(
        str(pathlib.Path(__file__).resolve().parents[3] / "mock-platform"))
    monkeypatch.setenv("MOCK_DB_PATH", str(tmp_path / "contract.db"))
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", APP_KEY)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", APP_SECRET)
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "cb")

    from fastapi.testclient import TestClient

    from mock_platform.api.app import create_app
    from mock_platform.config import get_settings
    from mock_platform.db import connect
    from mock_platform.world.generator import generate_daily_ops, generate_orders
    from mock_platform.world.seed import seed_world

    get_settings.cache_clear()
    conn = connect(str(tmp_path / "contract.db"))
    seed_world(conn, store_count=3)
    generate_orders(conn, store_id=1, biz_date=_MONDAY, minute_of_day=12 * 60,
                    count=30, rng=random.Random(4))
    generate_daily_ops(conn, store_id=1, biz_date=_MONDAY, rng=random.Random(4))
    conn.close()
    yield TestClient(create_app())
    get_settings.cache_clear()


class _TestClientTransport:
    """把 adapter 的 httpx 调用转接到模拟端的 TestClient。

    只替换传输，签名/参数/解析全走真代码 —— 换掉更多的话这个测试就退化成
    又一个假报文测试，那正是它要弥补的缺口。
    """

    def __init__(self, client):
        self._client = client

    async def get(self, url, params=None, timeout=None):
        path = url.split("/keruyun/", 1)[1]
        return self._client.get("/keruyun/" + path, params=params)


@pytest.mark.asyncio
@pytest.mark.parametrize("kind,cls", [
    ("requisition", NormalizedRequisition),
    ("wastage", NormalizedWastage),
    ("stocktaking", NormalizedStocktaking),
])
async def test_模拟端报文能被adapter原样解析(mock_client, kind, cls):
    adapter = KeruyunOpsAdapter("http://mock", APP_KEY, APP_SECRET,
                                _TestClientTransport(mock_client))
    page = await adapter.fetch_page(kind, "0", 50)
    assert page.items, f"{kind}: 模拟端该有数据，拿到空页说明两侧对不上"
    assert all(isinstance(i, cls) for i in page.items)
    first = page.items[0]
    assert first.doc_no
    assert first.store_code
    assert first.biz_date == datetime.date.fromisoformat(_MONDAY)
    assert first.ingredient.name, "食材名是 dim_ingredient 的自然键，不能为空"


@pytest.mark.asyncio
async def test_三类单据的状态与下游Gold的过滤口径对得上(mock_client):
    """🔴 这是整条链最容易静默失败的地方。实测 restaurant_ops_etl 的口径：
        领料 `WHERE status IN ('APPROVED','SUBMITTED')`
        损耗 `WHERE status = 'APPROVED'`
        盘点 `WHERE status = 'COMPLETED'`
    Silver 的 status 列**没有 CHECK 约束**，写个对不上的值不会报错：行进得去、
    租户闸能开、AI 照常回答，但按食材的领料/损耗 KPI 全是空的 ——
    「没数据」看起来就像「是 0」。所以这条要跨两侧钉住实际值，
    而不只是钉「字段存在」。
    """
    adapter = KeruyunOpsAdapter("http://mock", APP_KEY, APP_SECRET,
                                _TestClientTransport(mock_client))
    for kind, accepted in (("requisition", {"APPROVED", "SUBMITTED"}),
                           ("wastage", {"APPROVED"}),
                           ("stocktaking", {"COMPLETED"})):
        page = await adapter.fetch_page(kind, "0", 50)
        assert page.items, kind
        bad = {i.status for i in page.items} - accepted
        assert not bad, f"{kind}: 状态 {bad} 会被 Gold 过滤掉, KPI 将静默为 0"


@pytest.mark.asyncio
async def test_盘点方向一致_盘亏为负(mock_client):
    """模拟端的实盘偏差略偏亏损。这条同时钉住两侧对「差异」符号的理解一致：
    diff = 实盘 - 系统账，负数是盘亏。反了的话成本分析的正负会整体翻转。"""
    adapter = KeruyunOpsAdapter("http://mock", APP_KEY, APP_SECRET,
                                _TestClientTransport(mock_client))
    page = await adapter.fetch_page("stocktaking", "0", 100)
    assert page.items
    for item in page.items:
        assert (item.actual_qty_milli - item.system_qty_milli) == item.diff_qty_milli
        if item.diff_qty_milli < 0:
            assert item.diff_cost_cents <= 0, "盘亏的金额不该是正的"


@pytest.mark.asyncio
async def test_游标能翻完且不重复(mock_client):
    adapter = KeruyunOpsAdapter("http://mock", APP_KEY, APP_SECRET,
                                _TestClientTransport(mock_client))
    seen, cursor, rounds = set(), "0", 0
    while True:
        page = await adapter.fetch_page("requisition", cursor, 5)
        for item in page.items:
            assert item.doc_no not in seen, "翻页出现重复单据"
            seen.add(item.doc_no)
        rounds += 1
        assert rounds < 20, "分页没有收敛"
        if not page.has_more:
            break
        cursor = page.next_cursor
    assert seen
