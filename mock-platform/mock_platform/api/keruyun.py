"""客如云风格订单接口。

平台风格：HTTP 恒 200，成败看业务 code。这一点刻意保留——真实平台大多如此，
connector 若只看 HTTP 状态码就会把失败当成功，正是要压测的地方。
"""
from __future__ import annotations

import hmac

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from ..config import get_settings
from ..db import connect
from ._auth import keruyun_sign
from ._paging import MAX_LIMIT, page_orders

router = APIRouter(prefix="/keruyun/open", tags=["keruyun"])

_SQLITE_INT_MAX = 2 ** 63 - 1


def _fail(code: str, message: str) -> JSONResponse:
    return JSONResponse({"code": code, "message": message, "data": None})


@router.get("/order/list")
async def order_list(request: Request):
    settings = get_settings()
    params = dict(request.query_params)
    expected = keruyun_sign(params, settings.keruyun_app_secret)
    if not hmac.compare_digest(params.get("sign", ""), expected):
        return _fail("AUTH_SIGN_INVALID", "签名校验失败")
    if params.get("appKey") != settings.keruyun_app_key:
        return _fail("AUTH_APPKEY_INVALID", "appKey 无效")
    try:
        cursor = int(params.get("cursor", "0"))
        limit = int(params.get("limit", "50"))
    except ValueError:
        return _fail("PARAM_INVALID", "cursor / limit 必须是整数")
    # SQLite 的 INTEGER 是 64 位有符号。Python int 无上限, 不挡住的话
    # 会一路流到 SQL 绑定处抛 OverflowError → FastAPI 默认 500,
    # 既破了"恒 200"契约, 响应体也不是平台格式。
    if cursor < 0 or cursor > _SQLITE_INT_MAX:
        return _fail("PARAM_INVALID", "cursor 超出取值范围")
    if limit > MAX_LIMIT:
        return _fail("PARAM_LIMIT_TOO_LARGE", f"limit 上限为 {MAX_LIMIT}")
    if limit <= 0:
        return _fail("PARAM_INVALID", "limit 必须为正")
    conn = connect(settings.db_path)
    try:
        orders, next_cursor, has_more = page_orders(conn, since_seq=cursor, limit=limit)
    finally:
        conn.close()
    return JSONResponse({
        "code": "0",
        "message": "success",
        "data": {"list": orders, "nextCursor": next_cursor, "hasMore": has_more},
    })
