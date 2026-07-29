import random

import pytest
from fastapi.testclient import TestClient

from mock_platform.api._auth import keruyun_sign
from mock_platform.api.app import create_app
from mock_platform.db import connect
from mock_platform.world.generator import generate_daily_ops, generate_orders
from mock_platform.world.seed import seed_world

APP_KEY = "mock-key"
APP_SECRET = "mock-secret"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    db = str(tmp_path / "api.db")
    monkeypatch.setenv("MOCK_DB_PATH", db)
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", APP_KEY)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", APP_SECRET)
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "cb")
    from mock_platform.config import get_settings
    get_settings.cache_clear()
    conn = connect(db)
    seed_world(conn, store_count=10)
    generate_orders(conn, store_id=1, biz_date="2026-07-29",
                    minute_of_day=12 * 60, count=25, rng=random.Random(11))
    # 后厨事件按当天实际销量派生, 所以必须在订单之后跑。
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-29", rng=random.Random(11))
    conn.close()
    yield TestClient(create_app())
    get_settings.cache_clear()


@pytest.fixture()
def client_monday(tmp_path, monkeypatch):
    """带**周一**数据的客户端。

    盘点是每周一次、只在周一产生的。主 fixture 用的 2026-07-29 是周三,
    盘点端点在那儿永远是空的 —— 那不是端点坏了, 是没到盘点日。
    单开一个 fixture 而不是往主 fixture 里加订单: 那会把既有的
    `test_游标分页不重不漏`(硬断言 25 单) 撞坏。
    """
    db = str(tmp_path / "mon.db")
    monkeypatch.setenv("MOCK_DB_PATH", db)
    monkeypatch.setenv("MOCK_KERUYUN_APP_KEY", APP_KEY)
    monkeypatch.setenv("MOCK_KERUYUN_APP_SECRET", APP_SECRET)
    monkeypatch.setenv("MOCK_CALLBACK_SECRET", "cb")
    from mock_platform.config import get_settings
    get_settings.cache_clear()
    conn = connect(db)
    seed_world(conn, store_count=10)
    generate_orders(conn, store_id=1, biz_date="2026-07-27",   # 周一
                    minute_of_day=12 * 60, count=15, rng=random.Random(12))
    generate_daily_ops(conn, store_id=1, biz_date="2026-07-27", rng=random.Random(12))
    conn.close()
    yield TestClient(create_app())
    get_settings.cache_clear()


def _signed(params: dict) -> dict:
    p = dict(params)
    p["appKey"] = APP_KEY
    p["timestamp"] = "1785300000"
    p["sign"] = keruyun_sign(p, APP_SECRET)
    return p


def test_签名错误被拒(client):
    p = _signed({"cursor": "0", "limit": "10"})
    p["sign"] = "deadbeef"
    r = client.get("/keruyun/open/order/list", params=p)
    assert r.status_code == 200          # 平台风格：HTTP 200 + 业务错误码
    assert r.json()["code"] == "AUTH_SIGN_INVALID"


def test_缺签名被拒(client):
    r = client.get("/keruyun/open/order/list", params={"cursor": "0", "limit": "10"})
    assert r.json()["code"] == "AUTH_SIGN_INVALID"


def test_游标分页不重不漏(client):
    seen, cursor, pages = [], "0", 0
    while True:
        r = client.get("/keruyun/open/order/list",
                       params=_signed({"cursor": cursor, "limit": "10"}))
        body = r.json()
        assert body["code"] == "0"
        seen.extend(o["orderNo"] for o in body["data"]["list"])
        pages += 1
        if not body["data"]["hasMore"]:
            break
        cursor = str(body["data"]["nextCursor"])
        assert pages < 10, "分页没有收敛"
    assert len(seen) == 25
    assert len(set(seen)) == 25


def test_limit超上限被拒(client):
    r = client.get("/keruyun/open/order/list",
                   params=_signed({"cursor": "0", "limit": "5000"}))
    assert r.json()["code"] == "PARAM_LIMIT_TOO_LARGE"


def test_超大cursor不得返回500(client):
    """恒 200 契约的边界: Python int 无上限, SQLite INTEGER 是 64 位。
    不挡住会在 SQL 绑定处 OverflowError → FastAPI 默认 500 + 非平台格式响应体。
    """
    r = client.get("/keruyun/open/order/list",
                   params=_signed({"cursor": "9" * 26, "limit": "10"}))
    assert r.status_code == 200, "任何输入都不该破坏恒 200 契约"
    assert r.json()["code"] == "PARAM_INVALID"


def test_负cursor被拒(client):
    r = client.get("/keruyun/open/order/list",
                   params=_signed({"cursor": "-1", "limit": "10"}))
    assert r.status_code == 200
    assert r.json()["code"] == "PARAM_INVALID"


def test_订单结构含明细与支付(client):
    body = client.get("/keruyun/open/order/list",
                      params=_signed({"cursor": "0", "limit": "1"})).json()
    order = body["data"]["list"][0]
    assert set(order) >= {"orderNo", "shopCode", "channel", "placedAt", "bizDate",
                          "grossAmount", "discountAmount", "netAmount", "items", "payments"}
    assert order["items"] and order["payments"]
    assert sum(i["amount"] for i in order["items"]) == order["grossAmount"]


def test_明细带菜品分类(client):
    """真实平台的订单明细都带分类。不给的话对端只能拿到光秃秃的菜名,
    菜品维度就没有分组可言(2026-07-29 实测: 少了它 agg_product 直接是 0 行)。"""
    body = client.get("/keruyun/open/order/list",
                      params=_signed({"cursor": "0", "limit": "5"})).json()
    items = [i for o in body["data"]["list"] for i in o["items"]]
    assert items, "种子数据应当有明细"
    assert all("dishCategory" in i for i in items), "每条明细都要有 dishCategory"
    # 种子里的分类就是这几类, 全空说明 JOIN 没取到列
    assert any(i["dishCategory"] for i in items)


# ── 后厨供应链端点 ──────────────────────────────────────────────────

@pytest.mark.parametrize("path,must_have", [
    ("/keruyun/open/stock/requisition/list",
     {"docNo", "shopCode", "bizDate", "ingredientName", "unit", "qty", "cost", "status"}),
    ("/keruyun/open/stock/wastage/list",
     {"docNo", "shopCode", "bizDate", "ingredientName", "wastageType", "qty", "cost"}),
    ("/keruyun/open/stock/stocktaking/list",
     {"docNo", "shopCode", "bizDate", "ingredientName", "systemQty", "actualQty", "diffCost"}),
])
def test_供应链端点返回约定字段(client_monday, path, must_have):
    # 用周一那份数据: 三类单据当天都有(盘点只在周一)。
    body = client_monday.get(path, params=_signed({"cursor": "0", "limit": "5"})).json()
    assert body["code"] == "0", body
    items = body["data"]["list"]
    assert items, f"{path} 应当有数据"
    assert set(items[0]) >= must_have, set(items[0])


def test_盘点端点在非盘点日为空而不是报错(client):
    """主 fixture 是周三。没到盘点日就是没有单据 —— 空列表 + code=0,
    不是错误。把"没数据"当异常会让上游误判平台故障。"""
    body = client.get("/keruyun/open/stock/stocktaking/list",
                      params=_signed({"cursor": "0", "limit": "5"})).json()
    assert body["code"] == "0"
    assert body["data"]["list"] == []
    assert body["data"]["hasMore"] is False


@pytest.mark.parametrize("path", [
    "/keruyun/open/stock/requisition/list",
    "/keruyun/open/stock/wastage/list",
    "/keruyun/open/stock/stocktaking/list",
])
def test_供应链端点同样验签(client, path):
    """三个端点是抽出来共用鉴权的 —— 这条钉住抽取没有漏掉哪一个。"""
    body = client.get(path, params={"appKey": "x", "timestamp": "1",
                                    "cursor": "0", "limit": "1", "sign": "bad"}).json()
    assert body["code"] == "AUTH_SIGN_INVALID"


def test_供应链游标不重不漏(client):
    p1 = client.get("/keruyun/open/stock/requisition/list",
                    params=_signed({"cursor": "0", "limit": "3"})).json()["data"]
    p2 = client.get("/keruyun/open/stock/requisition/list",
                    params=_signed({"cursor": str(p1["nextCursor"]), "limit": "3"})
                    ).json()["data"]
    ids1 = {i["docNo"] for i in p1["list"]}
    ids2 = {i["docNo"] for i in p2["list"]}
    assert ids1 and ids2
    assert not (ids1 & ids2), "两页不该有重叠"


def test_订单端点抽取鉴权后没坏(client):
    """把鉴权抽成公共函数时最容易顺手改坏原有端点。"""
    body = client.get("/keruyun/open/order/list",
                      params=_signed({"cursor": "0", "limit": "1"})).json()
    assert body["code"] == "0" and body["data"]["list"]
