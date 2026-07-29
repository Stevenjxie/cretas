import random

import pytest
from fastapi.testclient import TestClient

from mock_platform.api._auth import keruyun_sign
from mock_platform.api.app import create_app
from mock_platform.db import connect
from mock_platform.world.generator import generate_orders
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
