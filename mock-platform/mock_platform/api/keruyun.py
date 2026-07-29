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
from ._paging import MAX_LIMIT, page_ops, page_orders

router = APIRouter(prefix="/keruyun/open", tags=["keruyun"])

_SQLITE_INT_MAX = 2 ** 63 - 1


def _fail(code: str, message: str) -> JSONResponse:
    return JSONResponse({"code": code, "message": message, "data": None})


def _authorize_and_page_params(params: dict):
    """鉴权 + cursor/limit 校验。返回 (cursor, limit) 或 (None, 失败响应)。

    抽出来是因为供应链那三个端点与订单端点的这段逻辑逐字相同 —— 抄四遍的话,
    以后补一条校验就得记得改四处, 漏一处就是一个静默的鉴权缺口。
    """
    settings = get_settings()
    expected = keruyun_sign(params, settings.keruyun_app_secret)
    if not hmac.compare_digest(params.get("sign", ""), expected):
        return None, _fail("AUTH_SIGN_INVALID", "签名校验失败")
    if params.get("appKey") != settings.keruyun_app_key:
        return None, _fail("AUTH_APPKEY_INVALID", "appKey 无效")
    try:
        cursor = int(params.get("cursor", "0"))
        limit = int(params.get("limit", "50"))
    except ValueError:
        return None, _fail("PARAM_INVALID", "cursor / limit 必须是整数")
    # SQLite 的 INTEGER 是 64 位有符号。Python int 无上限, 不挡住的话
    # 会一路流到 SQL 绑定处抛 OverflowError → FastAPI 默认 500,
    # 既破了"恒 200"契约, 响应体也不是平台格式。
    if cursor < 0 or cursor > _SQLITE_INT_MAX:
        return None, _fail("PARAM_INVALID", "cursor 超出取值范围")
    if limit > MAX_LIMIT:
        return None, _fail("PARAM_LIMIT_TOO_LARGE", f"limit 上限为 {MAX_LIMIT}")
    if limit <= 0:
        return None, _fail("PARAM_INVALID", "limit 必须为正")
    return (cursor, limit), None


def _ok(items, next_cursor, has_more) -> JSONResponse:
    return JSONResponse({
        "code": "0",
        "message": "success",
        "data": {"list": items, "nextCursor": next_cursor, "hasMore": has_more},
    })


@router.get("/order/list")
async def order_list(request: Request):
    parsed, failure = _authorize_and_page_params(dict(request.query_params))
    if failure is not None:
        return failure
    cursor, limit = parsed
    conn = connect(get_settings().db_path)
    try:
        orders, next_cursor, has_more = page_orders(conn, since_seq=cursor, limit=limit)
    finally:
        conn.close()
    return _ok(orders, next_cursor, has_more)


# ── 后厨供应链 (2026-07-29) ─────────────────────────────────────────
# 真实平台把领料/损耗/盘点分成不同接口, 这里照做。三条路由共用同一个
# 实现: 它们只差一个 kind, 各写一遍纯属重复。kind 走白名单常量表,
# 不是把路径串直接拼进 SQL。

async def _ops_list(request: Request, kind: str) -> JSONResponse:
    parsed, failure = _authorize_and_page_params(dict(request.query_params))
    if failure is not None:
        return failure
    cursor, limit = parsed
    conn = connect(get_settings().db_path)
    try:
        items, next_cursor, has_more = page_ops(
            conn, kind, since_seq=cursor, limit=limit)
    finally:
        conn.close()
    return _ok(items, next_cursor, has_more)


@router.get("/stock/requisition/list")
async def requisition_list(request: Request):
    return await _ops_list(request, "requisition")


@router.get("/stock/wastage/list")
async def wastage_list(request: Request):
    return await _ops_list(request, "wastage")


@router.get("/stock/stocktaking/list")
async def stocktaking_list(request: Request):
    return await _ops_list(request, "stocktaking")
