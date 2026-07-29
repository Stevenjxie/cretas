"""客如云 adapter + Silver writer。

两类重点:
  1. **跨端签名对拍** —— 直接 import 模拟端的实现比对。两端算法不一致的线上
     症状是"一直 401 但两边代码看着都对", 查起来极痛苦, 必须用测试锁死。
  2. **失败路径**(F1-F7, 见 ledger) —— 门店/渠道映射查不到、未知支付方式、
     幂等命中、业务错误码。这些路径写错不会让正常路径的测试变红。
"""
import datetime
import pathlib
import sys

import pytest

from smartbi.ingestion.platforms.keruyun import (
    KeruyunAdapter, KeruyunBusinessError, sign,
)
from smartbi.ingestion.platforms.models import NormalizedItem, NormalizedOrder, NormalizedPayment
from smartbi.ingestion.platforms.writer import write_orders

# 模拟端在仓库根的 mock-platform/ 下, 不是 backend/python 的包 —— 显式加路径。
_MOCK_ROOT = pathlib.Path(__file__).resolve().parents[3] / "mock-platform"
if str(_MOCK_ROOT) not in sys.path:
    sys.path.insert(0, str(_MOCK_ROOT))


# ───────────────────────── 签名互操作 ─────────────────────────

def test_签名与模拟端逐字节一致():
    from mock_platform.api._auth import keruyun_sign

    params = {"appKey": "k", "timestamp": "1785300000", "cursor": "0", "limit": "50"}
    assert sign(params, "sec") == keruyun_sign(params, "sec")


def test_签名对多种参数组合都与模拟端一致():
    """单个用例可能碰巧相等; 多组覆盖排序、空值、中文、特殊字符。"""
    from mock_platform.api._auth import keruyun_sign

    cases = [
        {"z": "1", "a": "2", "m": "3"},                       # 乱序
        {"appKey": "k", "empty": "", "none": None, "x": "1"}, # 空值排除
        {"appKey": "键", "note": "带 空格与=号"},              # 非 ASCII
        {"limit": "200", "cursor": "999999999999"},           # 大数字
    ]
    for params in cases:
        assert sign(params, "s3cr3t") == keruyun_sign(params, "s3cr3t"), params


def test_签名排除sign本身与空值():
    base = {"appKey": "k", "timestamp": "1", "empty": ""}
    assert sign(base, "s") == sign(dict(base, sign="whatever"), "s")


# ───────────────────────── adapter ─────────────────────────

class _FakeResponse:
    def __init__(self, payload):
        self._payload = payload
        self.status_code = 200

    def json(self):
        return self._payload


class _FakeClient:
    def __init__(self, payload):
        self._payload = payload
        self.last_params = None

    async def get(self, url, params=None, timeout=None):
        self.last_params = params
        return _FakeResponse(self._payload)


@pytest.mark.asyncio
async def test_业务错误码被识别为失败_而不是当成空页():
    """平台 HTTP 恒 200, 只看 status_code 会把失败当成功 —— 禁降级在接入侧的落地。"""
    client = _FakeClient({"code": "AUTH_SIGN_INVALID", "message": "签名校验失败", "data": None})
    adapter = KeruyunAdapter("http://mock", "k", "s", client)
    with pytest.raises(KeruyunBusinessError, match="AUTH_SIGN_INVALID"):
        await adapter.fetch_page("0", 50)


@pytest.mark.asyncio
async def test_订单被正确归一化():
    payload = {
        "code": "0", "message": "success",
        "data": {
            "list": [{
                "orderNo": "MK2026072901000001", "shopCode": "MK01",
                "channel": "takeaway", "placedAt": "2026-07-29T12:05:00",
                "bizDate": "2026-07-29", "grossAmount": 12800,
                "discountAmount": 800, "netAmount": 12000, "guestCount": 1,
                "items": [{"dishName": "藤椒鸡", "qty": 2, "price": 5800, "amount": 11600}],
                "payments": [{"method": "platform", "amount": 12000}],
            }],
            "nextCursor": 42, "hasMore": False,
        },
    }
    adapter = KeruyunAdapter("http://mock", "k", "s", _FakeClient(payload))
    page = await adapter.fetch_page("0", 50)
    assert page.next_cursor == "42" and page.has_more is False
    order = page.orders[0]
    assert order.platform == "keruyun"
    assert order.platform_order_no == "MK2026072901000001"
    assert order.store_code == "MK01"
    assert order.biz_date == datetime.date(2026, 7, 29)
    assert order.placed_at == datetime.datetime(2026, 7, 29, 12, 5)
    assert (order.gross_cents, order.discount_cents, order.net_cents) == (12800, 800, 12000)
    assert order.items[0].dish_name == "藤椒鸡"
    assert order.payments[0].method == "platform"


@pytest.mark.asyncio
async def test_请求带上了签名参数():
    adapter = KeruyunAdapter("http://mock", "kk", "ss", _FakeClient(
        {"code": "0", "data": {"list": [], "nextCursor": 0, "hasMore": False}}))
    await adapter.fetch_page("7", 30)
    params = adapter._client.last_params
    assert params["appKey"] == "kk" and params["cursor"] == "7" and params["limit"] == "30"
    assert params["sign"] == sign({k: v for k, v in params.items() if k != "sign"}, "ss")


# ───────────────────────── writer 失败路径 ─────────────────────────

def _order(no="B1", store_code="MK01", method="wechat"):
    return NormalizedOrder(
        platform="keruyun", platform_order_no=no, store_code=store_code,
        channel="dine_in", placed_at=datetime.datetime(2026, 7, 29, 12, 0),
        biz_date=datetime.date(2026, 7, 29), gross_cents=1000,
        discount_cents=0, net_cents=1000, guest_count=2,
        items=[NormalizedItem(dish_name="米饭", qty=1, price_cents=1000, amount_cents=1000)],
        payments=[NormalizedPayment(method=method, amount_cents=1000)],
    )


class _FakeConn:
    """按 SQL 前缀分派的假连接。rows 决定各查询返回什么。"""

    def __init__(self, store_row=None, channel_row=None, txn_row=None):
        self._store_row = store_row
        self._channel_row = channel_row
        self._txn_row = txn_row
        self.executed = []

    async def execute(self, sql, *args):
        self.executed.append((sql, args))
        return "INSERT 0 1"

    async def fetchrow(self, sql, *args):
        self.executed.append((sql, args))
        if "platform_store_map" in sql:
            return self._store_row
        if "dim_payment_channel" in sql:
            return self._channel_row
        if "fact_pos_transaction" in sql:
            return self._txn_row
        raise AssertionError(f"未预期的查询: {sql[:60]}")

    def transaction(self):
        conn = self

        class _Txn:
            async def __aenter__(self_inner):
                return conn

            async def __aexit__(self_inner, *exc):
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


@pytest.mark.asyncio
async def test_F1_门店映射查不到就报错_不建未知门店也不丢弃():
    pool = _FakePool(_FakeConn(store_row=None))
    with pytest.raises(RuntimeError, match="门店映射失败"):
        await write_orders(pool, "MOCK_REST", [_order()])


@pytest.mark.asyncio
async def test_F2_支付渠道查不到就报错():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row=None, txn_row={"id": 99})
    with pytest.raises(RuntimeError, match="支付渠道映射失败"):
        await write_orders(_FakePool(conn), "MOCK_REST", [_order()])


@pytest.mark.asyncio
async def test_F3_未知支付方式就报错_并提示两处都要补():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    with pytest.raises(RuntimeError, match="未知支付方式"):
        await write_orders(_FakePool(conn), "MOCK_REST", [_order(method="meituan_wallet")])


@pytest.mark.asyncio
async def test_F4_幂等命中不计数也不写明细():
    """ON CONFLICT DO NOTHING 时 RETURNING 不出行 → 该单跳过。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row=None)
    n = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert n == 0
    assert not any("fact_pos_item" in sql for sql, _ in conn.executed), "已存在的单不该写明细"


@pytest.mark.asyncio
async def test_正常路径写主表明细支付各一次():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    n = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert n == 1
    sqls = [sql for sql, _ in conn.executed]
    assert sum("fact_pos_item" in s for s in sqls) == 1
    assert sum("fact_pos_payment" in s for s in sqls) == 1


@pytest.mark.asyncio
async def test_F7_RLS_必须先设factory_id且在事务内():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    first_sql, first_args = conn.executed[0]
    assert "set_config" in first_sql and "app.factory_id" in first_sql, (
        "第一条语句必须是 set_config(app.factory_id) —— asyncpg 上它是事务级的, "
        "不先设 RLS 就靠连接池残留碰运气"
    )
    assert first_args == ("MOCK_REST",)


@pytest.mark.asyncio
async def test_空批次不碰数据库():
    conn = _FakeConn()
    assert await write_orders(_FakePool(conn), "MOCK_REST", []) == 0
    assert conn.executed == []


@pytest.mark.asyncio
async def test_ON_CONFLICT_列清单必须与现成唯一约束完全一致():
    """fact_pos_transaction 的唯一约束是
    uq_fact_pos_txn (factory_id, source_type, store_id, source_bill_no)。
    ON CONFLICT 少写一列(尤其 store_id), Postgres 匹配不到约束会直接报错 ——
    而这类错误只在真连库时才暴露, 假连接测不出来, 所以用静态断言钉住。
    """
    src = (pathlib.Path(__file__).resolve().parents[1]
           / "smartbi" / "ingestion" / "platforms" / "writer.py").read_text(encoding="utf-8")
    assert "ON CONFLICT (factory_id, source_type, store_id, source_bill_no)" in src, (
        "ON CONFLICT 列清单必须与 uq_fact_pos_txn 完全一致(含 store_id)"
    )


def test_支付方式映射覆盖模拟端全部支付方式():
    """跨文件判别力: 模拟端 generator.py 新增支付方式时, 这里必须跟着补,
    否则 writer 会在运行期抛"未知支付方式", 整批订单写不进来。
    """
    import re

    from smartbi.ingestion.platforms.writer import _CHANNEL_NAME

    gen = _MOCK_ROOT / "mock_platform" / "world" / "generator.py"
    block = re.search(r"_PAY_BY_CHANNEL\s*=\s*\{(.*?)\n\}", gen.read_text(encoding="utf-8"), re.S)
    assert block, "模拟端 generator.py 里找不到 _PAY_BY_CHANNEL"
    methods = set(re.findall(r'"(\w+)"', block.group(1))) - {"dine_in", "takeaway", "groupon"}
    missing = methods - set(_CHANNEL_NAME)
    assert not missing, f"_CHANNEL_NAME 缺支付方式 {sorted(missing)}"
