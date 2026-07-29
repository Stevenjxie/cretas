"""客如云风格 adapter。

⚠️ 平台风格: HTTP 状态码恒 200, 成败看业务 code。只看 status_code 会把失败
当成功 —— 这正是模拟器刻意保留的真实平台行为, 所以这里显式判 code != "0"
就抛错, 这是禁降级在接入侧的具体落地。

签名算法必须与模拟端 mock_platform/api/_auth.py 的 keruyun_sign **逐字节一致**,
有一条对拍测试直接 import 两边做比对。改这里必须同步改那边。
"""
from __future__ import annotations

import datetime
import hashlib
import hmac
import time

from .models import FetchPage, NormalizedItem, NormalizedOrder, NormalizedPayment

PLATFORM = "keruyun"


class KeruyunBusinessError(RuntimeError):
    """平台返回了非 0 业务码。"""


def sign(params: dict, app_secret: str) -> str:
    """参数按名字典序拼成 key=value&, 用 app_secret 做 HMAC-SHA256, 取小写 hex。

    参与签名的参数排除 sign 本身与空值。
    与模拟端 mock_platform.api._auth.keruyun_sign 逐字节一致。
    """
    items = sorted(
        (k, str(v)) for k, v in params.items()
        if k != "sign" and v is not None and str(v) != ""
    )
    payload = "&".join(f"{k}={v}" for k, v in items)
    return hmac.new(
        app_secret.encode("utf-8"), payload.encode("utf-8"), hashlib.sha256
    ).hexdigest().lower()


class KeruyunAdapter:
    """把客如云报文归一化成 NormalizedOrder。不碰 DB, 不管游标。"""

    platform = PLATFORM

    def __init__(self, base_url: str, app_key: str, app_secret: str, client):
        self._base_url = base_url.rstrip("/")
        self._app_key = app_key
        self._app_secret = app_secret
        self._client = client

    async def fetch_page(self, cursor: str, limit: int) -> FetchPage:
        params = {
            "appKey": self._app_key,
            "timestamp": str(int(time.time())),
            "cursor": str(cursor),
            "limit": str(limit),
        }
        params["sign"] = sign(params, self._app_secret)
        resp = await self._client.get(
            f"{self._base_url}/keruyun/open/order/list", params=params, timeout=30.0
        )
        body = resp.json()
        code = str(body.get("code", ""))
        if code != "0":
            # 禁降级: 平台的业务错误不能被当成"本轮无数据"。
            raise KeruyunBusinessError(f"{code}: {body.get('message')}")
        data = body.get("data") or {}
        orders = [self._to_order(raw) for raw in data.get("list", [])]
        return FetchPage(
            orders=orders,
            next_cursor=str(data.get("nextCursor", cursor)),
            has_more=bool(data.get("hasMore", False)),
        )

    @staticmethod
    def _to_order(raw: dict) -> NormalizedOrder:
        return NormalizedOrder(
            platform=PLATFORM,
            platform_order_no=raw["orderNo"],
            store_code=raw["shopCode"],
            channel=raw["channel"],
            placed_at=datetime.datetime.fromisoformat(raw["placedAt"]),
            biz_date=datetime.date.fromisoformat(raw["bizDate"]),
            gross_cents=int(raw["grossAmount"]),
            discount_cents=int(raw["discountAmount"]),
            net_cents=int(raw["netAmount"]),
            guest_count=int(raw.get("guestCount", 1)),
            items=[
                NormalizedItem(
                    dish_name=i["dishName"], qty=int(i["qty"]),
                    price_cents=int(i["price"]), amount_cents=int(i["amount"]),
                )
                for i in raw.get("items", [])
            ],
            payments=[
                NormalizedPayment(method=p["method"], amount_cents=int(p["amount"]))
                for p in raw.get("payments", [])
            ],
        )
