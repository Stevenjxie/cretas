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
async def test_菜品分类被带过来():
    payload = {"code": "0", "data": {"list": [{
        "orderNo": "B1", "shopCode": "MK01", "channel": "dine_in",
        "placedAt": "2026-07-29T12:05:00", "bizDate": "2026-07-29",
        "grossAmount": 1, "discountAmount": 0, "netAmount": 1, "guestCount": 1,
        "items": [{"dishName": "藤椒鸡", "dishCategory": "热菜",
                   "qty": 1, "price": 1, "amount": 1}],
        "payments": [],
    }], "nextCursor": 1, "hasMore": False}}
    page = await KeruyunAdapter("http://m", "k", "s", _FakeClient(payload)).fetch_page("0", 50)
    assert page.orders[0].items[0].category == "热菜"


@pytest.mark.asyncio
async def test_菜品分类缺失不炸_整页仍可解析():
    """老报文没有 dishCategory。它只影响菜品分组粒度, 不该让整页解析失败。"""
    payload = {"code": "0", "data": {"list": [{
        "orderNo": "B1", "shopCode": "MK01", "channel": "dine_in",
        "placedAt": "2026-07-29T12:05:00", "bizDate": "2026-07-29",
        "grossAmount": 1, "discountAmount": 0, "netAmount": 1, "guestCount": 1,
        "items": [{"dishName": "藤椒鸡", "qty": 1, "price": 1, "amount": 1}],
        "payments": [],
    }], "nextCursor": 1, "hasMore": False}}
    page = await KeruyunAdapter("http://m", "k", "s", _FakeClient(payload)).fetch_page("0", 50)
    assert page.orders[0].items[0].category is None


@pytest.mark.asyncio
async def test_金额带小数必须报错_不许静默截断():
    """裸 int(128.5) 静默给 128。金额单位是分, 截断 = 无声改写一笔订单金额,
    财务对账会拿到"看起来正常"的错数且零留痕。这个文件里其余失败路径都
    显式 raise, 金额不能是唯一例外。
    """
    from smartbi.ingestion.platforms.keruyun import KeruyunPayloadError

    def _payload(gross):
        return {"code": "0", "data": {"list": [{
            "orderNo": "X1", "shopCode": "MK01", "channel": "dine_in",
            "placedAt": "2026-07-29T12:00:00", "bizDate": "2026-07-29",
            "grossAmount": gross, "discountAmount": 0, "netAmount": 100,
            "guestCount": 1, "items": [], "payments": [],
        }], "nextCursor": 1, "hasMore": False}}

    for bad in (128.5, "128.5", "12.0元", None, True):
        adapter = KeruyunAdapter("http://mock", "k", "s", _FakeClient(_payload(bad)))
        with pytest.raises(KeruyunPayloadError):
            await adapter.fetch_page("0", 50)


@pytest.mark.asyncio
async def test_整数形态的浮点与字符串仍被接受():
    """128.0 和 "128" 是合法的整数分, 不该误伤。"""
    def _payload(gross):
        return {"code": "0", "data": {"list": [{
            "orderNo": "X1", "shopCode": "MK01", "channel": "dine_in",
            "placedAt": "2026-07-29T12:00:00", "bizDate": "2026-07-29",
            "grossAmount": gross, "discountAmount": 0, "netAmount": 100,
            "guestCount": 1, "items": [], "payments": [],
        }], "nextCursor": 1, "hasMore": False}}

    for good in (128, 128.0, "128", " 128 "):
        adapter = KeruyunAdapter("http://mock", "k", "s", _FakeClient(_payload(good)))
        page = await adapter.fetch_page("0", 50)
        assert page.orders[0].gross_cents == 128


@pytest.mark.asyncio
async def test_请求带上了签名参数():
    adapter = KeruyunAdapter("http://mock", "kk", "ss", _FakeClient(
        {"code": "0", "data": {"list": [], "nextCursor": 0, "hasMore": False}}))
    await adapter.fetch_page("7", 30)
    params = adapter._client.last_params
    assert params["appKey"] == "kk" and params["cursor"] == "7" and params["limit"] == "30"
    assert params["sign"] == sign({k: v for k, v in params.items() if k != "sign"}, "ss")


# ───────────────────────── writer 失败路径 ─────────────────────────

def _order(no="B1", store_code="MK01", method="wechat", items=None):
    return NormalizedOrder(
        platform="keruyun", platform_order_no=no, store_code=store_code,
        channel="dine_in", placed_at=datetime.datetime(2026, 7, 29, 12, 0),
        biz_date=datetime.date(2026, 7, 29), gross_cents=1000,
        discount_cents=0, net_cents=1000, guest_count=2,
        items=items if items is not None else [
            NormalizedItem(dish_name="米饭", qty=1, price_cents=1000,
                           amount_cents=1000, category="主食")
        ],
        payments=[NormalizedPayment(method=method, amount_cents=1000)],
    )


class _FakeConn:
    """假连接。

    每条语句都连同「当时是否处于事务中」一起记录 —— 否则"必须在事务内"这类
    断言只能验到执行顺序，验不到事务边界，把 set_config 挪出事务块测试照样绿。
    `txn_rows` 支持按调用序给不同返回值，用来表达"同一批里一单新增一单已存在"。
    """

    def __init__(self, store_row=None, channel_row=None, txn_row=None, txn_rows=None):
        self._store_row = store_row
        self._channel_row = channel_row
        self._txn_rows = list(txn_rows) if txn_rows is not None else None
        self._txn_row = txn_row
        self.in_transaction = False
        self.executed = []          # [(sql, args, in_transaction)]
        # dim_product UPSERT 每次发一个新 id, 这样测试能看出"同名菜是不是
        # 只解析了一次"——发固定值的话缓存失效根本测不出来。
        self._next_product_id = 900
        self.product_upserts = []   # [(name, normalized_name, category)]

    def _record(self, sql, args):
        self.executed.append((sql, args, self.in_transaction))

    async def execute(self, sql, *args):
        self._record(sql, args)
        return "INSERT 0 1"

    async def fetchval(self, sql, *args):
        self._record(sql, args)
        assert "dim_product" in sql, f"假连接的 fetchval 只认 dim_product: {sql[:80]}"
        # args = (factory_id, name, normalized_name, category)
        self.product_upserts.append((args[1], args[2], args[3]))
        self._next_product_id += 1
        return self._next_product_id

    async def fetchrow(self, sql, *args):
        self._record(sql, args)
        # 按表名分派。断言互斥, 防止将来某条 SQL 同时提到两个表名时静默走错分支。
        hits = [t for t in ("platform_store_map", "dim_payment_channel",
                            "fact_pos_transaction") if t in sql]
        assert len(hits) == 1, f"SQL 同时命中多张表, 假连接分派会出错: {hits}"
        table = hits[0]
        if table == "platform_store_map":
            return self._store_row
        if table == "dim_payment_channel":
            return self._channel_row
        if self._txn_rows is not None:
            return self._txn_rows.pop(0)
        return self._txn_row

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


@pytest.mark.asyncio
async def test_F1_门店映射查不到就隔离_不建未知门店也不丢弃():
    """2026-07-30 契约修订: 从「抛错卡住游标」改成「隔离到死信表 + 显式告警」。

    本意没变 —— 既不建"未知门店", 也不静默丢弃。变的只是: 一条坏单据不再让
    该类数据永远卡在同一页(见 V20261101_03 与 writer 头注)。
    """
    conn = _FakeConn(store_row=None)
    written = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert written == 0, "查不到门店的单据被写进了事实表"
    assert [e for e in conn.executed if "platform_ingest_dead_letter" in e[0]], "既没写也没隔离 —— 那就是静默丢弃"


@pytest.mark.asyncio
async def test_F2_支付渠道查不到就隔离():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row=None, txn_row={"id": 99})
    written = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert written == 0
    assert [e for e in conn.executed if "platform_ingest_dead_letter" in e[0]], "支付渠道查不到的单据没被隔离"


@pytest.mark.asyncio
async def test_F3_未知支付方式就隔离_原因写清是哪种():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    written = await write_orders(_FakePool(conn), "MOCK_REST",
                                 [_order(method="meituan_wallet")])
    assert written == 0
    rows = [e for e in conn.executed if "platform_ingest_dead_letter" in e[0]]
    assert rows, "未知支付方式的单据没被隔离"
    # 原因必须点名是哪种支付方式, 否则运维还得自己去翻报文
    assert any("meituan_wallet" in str(a) for a in rows[0][1]), rows[0][1]


@pytest.mark.asyncio
async def test_F4_幂等命中不计数也不写明细():
    """ON CONFLICT DO NOTHING 时 RETURNING 不出行 → 该单跳过。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row=None)
    n = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert n == 0
    assert not any("fact_pos_item" in sql for sql, _, _ in conn.executed), "已存在的单不该写明细"


@pytest.mark.asyncio
async def test_正常路径写主表明细支付各一次():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    n = await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert n == 1
    sqls = [sql for sql, _, _ in conn.executed]
    assert sum("fact_pos_item" in s for s in sqls) == 1
    assert sum("fact_pos_payment" in s for s in sqls) == 1


# ── 菜品维度 (product_id) ───────────────────────────────────────────
# 背景: 2026-07-29 上线后实测发现 fact_pos_item.product_id 全是 NULL
# (241276 行非空 0), 于是 agg_product 物化出来 0 行, 餐饮 AI 的语义规划器
# 直接弃权(tiered-answer 返回 delegate:false)。菜品维度是餐饮分析的主轴。

@pytest.mark.asyncio
async def test_明细写入带上了product_id():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    item_sql, item_args, _ = next(
        (s, a, t) for s, a, t in conn.executed if "fact_pos_item" in s)
    assert "product_id" in item_sql, "明细 INSERT 必须写 product_id"
    # args = (txn_id, factory_id, product_id, source_item_raw, qty, unit_price, amount)
    assert item_args[2] == 901, "product_id 应当来自 dim_product UPSERT 的返回值"
    assert item_args[3] == "米饭", "source_item_raw 仍保留原始菜名"


@pytest.mark.asyncio
async def test_菜品维度按归一化名UPSERT并带上分类():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    assert conn.product_upserts == [("米饭", "米饭", "主食")]
    upsert_sql = next(s for s, _, _ in conn.executed if "dim_product" in s)
    assert "ON CONFLICT (factory_id, normalized_name)" in upsert_sql, (
        "必须挂在 dim_product 现成的唯一约束上, 否则并发下会插出重复菜品"
    )


@pytest.mark.asyncio
async def test_菜名归一化后再匹配_全角空格与标点不算新菜():
    """normalize_for_dim 是仓库既有的维度匹配口径, 这里必须复用而不是自创。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order(items=[
        NormalizedItem(dish_name="水煮牛肉", qty=1, price_cents=1, amount_cents=1),
        NormalizedItem(dish_name="水煮·牛肉 ", qty=1, price_cents=1, amount_cents=1),
    ])])
    # 两条明细, 但归一化后同名 → 只 UPSERT 一次
    assert len(conn.product_upserts) == 1, conn.product_upserts
    assert conn.product_upserts[0][1] == "水煮牛肉"


@pytest.mark.asyncio
async def test_同批重复菜只解析一次_但不同菜各解析一次():
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_rows=[{"id": 1}, {"id": 2}])
    o1 = _order(no="B1", items=[
        NormalizedItem(dish_name="米饭", qty=1, price_cents=1, amount_cents=1)])
    o2 = _order(no="B2", items=[
        NormalizedItem(dish_name="米饭", qty=1, price_cents=1, amount_cents=1),
        NormalizedItem(dish_name="藤椒鸡", qty=1, price_cents=1, amount_cents=1)])
    await write_orders(_FakePool(conn), "MOCK_REST", [o1, o2])
    names = [n for n, _, _ in conn.product_upserts]
    assert names == ["米饭", "藤椒鸡"], f"跨订单的同名菜应命中缓存: {names}"


@pytest.mark.asyncio
async def test_菜品缓存不跨批次():
    """缓存做成模块级会跨租户串 product_id, 也会发陈旧值。"""
    def _fresh():
        return _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                         txn_row={"id": 99})
    c1 = _fresh()
    await write_orders(_FakePool(c1), "MOCK_REST", [_order()])
    c2 = _fresh()
    await write_orders(_FakePool(c2), "MOCK_REST", [_order(no="B2")])
    assert len(c2.product_upserts) == 1, "新一批必须重新解析, 不能吃上一批的缓存"


@pytest.mark.asyncio
async def test_菜名为空就隔离_不塞未知菜品():
    """禁降级: 建个"未知菜品"会让菜品分析里多出一坨假聚合。

    契约修订后仍然**不建**未知菜品, 只是改成隔离而非卡住整页。
    """
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    bad = _order(items=[
        NormalizedItem(dish_name="  ", qty=1, price_cents=1, amount_cents=1)])
    written = await write_orders(_FakePool(conn), "MOCK_REST", [bad])
    assert written == 0
    assert not conn.product_upserts, "空菜名仍然建了 dim_product —— 假聚合会回来"
    assert [e for e in conn.executed if "platform_ingest_dead_letter" in e[0]], "空菜名单据没被隔离"


@pytest.mark.asyncio
async def test_菜品UPSERT也在事务内且在set_config之后():
    """dim_product 的 RLS 没有 __internal__ 逃生门, GUC 没设就会被策略挡住。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    idx_cfg = next(i for i, (s, _, _) in enumerate(conn.executed) if "set_config" in s)
    idx_prod = next(i for i, (s, _, _) in enumerate(conn.executed) if "dim_product" in s)
    assert idx_cfg < idx_prod, "set_config 必须排在 dim_product UPSERT 之前"
    assert conn.executed[idx_prod][2] is True, "dim_product UPSERT 必须在事务内"


@pytest.mark.asyncio
async def test_F7_RLS_必须先设factory_id且在事务内():
    """set_config(..., true) 是**事务级**的: asyncpg 上不开显式事务从不生效,
    RLS 会靠连接池残留碰运气。所以既要验它是第一条, 也要验它**在事务里**。
    """
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    first_sql, first_args, first_in_txn = conn.executed[0]
    assert "set_config" in first_sql and "app.factory_id" in first_sql, (
        "第一条语句必须是 set_config(app.factory_id)"
    )
    assert first_args == ("MOCK_REST",)
    assert first_in_txn is True, (
        "set_config 必须在显式事务内 —— 挪到事务外它就从不生效, 而这条测试"
        "过去只验执行顺序, 挪出去照样绿, 等于没锁住"
    )


@pytest.mark.asyncio
async def test_所有语句都在同一事务内():
    """整批一个事务是刻意取舍: 任一笔失败整页回滚, 框架不推进游标, 下轮重拉。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_row={"id": 99})
    await write_orders(_FakePool(conn), "MOCK_REST", [_order()])
    outside = [sql[:50] for sql, _, in_txn in conn.executed if not in_txn]
    assert outside == [], f"这些语句跑在事务外: {outside}"


@pytest.mark.asyncio
async def test_批内混合_一单新增一单已存在时计数正确():
    """幂等命中的单不计入 written, 但不能影响同批其他单。"""
    conn = _FakeConn(store_row={"store_id": 1}, channel_row={"channel_id": 7},
                     txn_rows=[{"id": 1}, None])       # 第一单新增, 第二单已存在
    n = await write_orders(_FakePool(conn), "MOCK_REST",
                           [_order(no="NEW1"), _order(no="DUP1")])
    assert n == 1, "只该计新增的那一单"
    assert sum("fact_pos_item" in sql for sql, _, _ in conn.executed) == 1, (
        "已存在的单不该重写明细"
    )


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
