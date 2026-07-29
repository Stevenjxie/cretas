"""客如云风格 **后厨供应链** adapter(领料 / 损耗 / 盘点)。

与订单 adapter 同源: 同一套 sign、同一套 `_strict_int`、同一套「业务码非 0 就抛错」。
这里是 import 复用而不是复制一份 —— 签名两端逐字节一致这件事已经由
`test_keruyun_adapter.py` 的对拍测试锁死, 复制出第二份实现就等于把那道锁绕开:
改了这边而对拍还盯着那边, 线上症状是"一直 AUTH_SIGN_INVALID 但两边代码看着都对"。

三类单据只在 URL 路径段与字段映射上有差异, 所以集中成一张
kind → (路径段, 解析函数) 的白名单表。**kind 决定 URL 路径段, 绝不能透传**:
拿调用方给的字符串去拼 URL, `../order` 这种就能把这个只读 adapter 指到别的
接口上, 而且解析函数还会按"领料"去读一份订单报文。所以先查表, 查不到就拒。

不碰 DB, 不管游标推进 —— 与订单 adapter 一样只负责 HTTP + 归一化。
"""
from __future__ import annotations

import datetime
import time
from typing import Callable, Dict, Tuple

from .keruyun import (
    PLATFORM,
    KeruyunBusinessError,
    KeruyunPayloadError,
    _strict_int,
    sign,
)
from .ops_models import (
    NormalizedIngredientRef,
    NormalizedRequisition,
    NormalizedStocktaking,
    NormalizedWastage,
    OpsFetchPage,
)

__all__ = [
    "KeruyunOpsAdapter",
    "KeruyunOpsKindError",
    "KeruyunBusinessError",
    "KeruyunPayloadError",
]


class KeruyunOpsKindError(ValueError):
    """请求了白名单之外的单据类型。"""


def _require_text(raw: dict, key: str) -> str:
    """取一个必填字符串字段, 缺失 / 空白 / 非字符串一律抛错。

    禁降级: 这几个字段是单据的身份(单号、门店、食材名)。缺了就填空串或
    "未知", Silver 侧会拿它当自然键 get-or-create, 于是多出一条谁也对不上的
    "空门店/未知食材"聚合行 —— 比整页失败更难发现, 因为它看起来像有数据。
    """
    if key not in raw:
        raise KeruyunPayloadError(f"缺少必填字段 {key}: {raw!r}")
    value = raw[key]
    if not isinstance(value, str):
        raise KeruyunPayloadError(
            f"{key} 必须是字符串, 收到 {type(value).__name__} {value!r}"
        )
    text = value.strip()
    if not text:
        raise KeruyunPayloadError(f"{key} 不能为空: {value!r}")
    return text


def _optional_text(raw: dict, key: str):
    """取一个可空的描述性字段。空白等同于没有。

    判据: 在 `ops_models` 里声明了默认值的才算可空(category / unit)。它们只
    影响维度分组的粒度, 不参与金额与数量的对账, 缺了不该让整页解析失败 ——
    与订单 adapter 对 dishCategory 的处理同一口径。
    """
    value = raw.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise KeruyunPayloadError(
            f"{key} 必须是字符串, 收到 {type(value).__name__} {value!r}"
        )
    return value.strip() or None


def _ingredient(raw: dict) -> NormalizedIngredientRef:
    return NormalizedIngredientRef(
        name=_require_text(raw, "ingredientName"),
        category=_optional_text(raw, "ingredientCategory"),
        unit=_optional_text(raw, "unit"),
    )


def _biz_date(raw: dict) -> datetime.date:
    # 先过 _require_text: 缺字段时 fromisoformat 拿到的是 KeyError/TypeError,
    # 报错信息里看不出是哪张单子的哪个字段。
    return datetime.date.fromisoformat(_require_text(raw, "bizDate"))


def _to_requisition(raw: dict) -> NormalizedRequisition:
    return NormalizedRequisition(
        platform=PLATFORM,
        doc_no=_require_text(raw, "docNo"),
        store_code=_require_text(raw, "shopCode"),
        biz_date=_biz_date(raw),
        ingredient=_ingredient(raw),
        qty_milli=_strict_int(raw["qty"], "requisition.qty"),
        cost_cents=_strict_int(raw["cost"], "requisition.cost"),
        # ⚠️ 绝不给 status 兜底。下游 Gold 按它过滤(领料是
        # APPROVED/SUBMITTED), 替平台编一个状态等于凭空决定这单算不算进成本;
        # 而编错了不会报错, 只会让 KPI 静默变 0。
        status=_require_text(raw, "status"),
    )


def _to_wastage(raw: dict) -> NormalizedWastage:
    return NormalizedWastage(
        platform=PLATFORM,
        doc_no=_require_text(raw, "docNo"),
        store_code=_require_text(raw, "shopCode"),
        biz_date=_biz_date(raw),
        ingredient=_ingredient(raw),
        # 损耗类型是损耗分析的主分组轴(变质 / 加工损耗 / 客诉退菜), 缺了这条
        # 记录就只能进"其它", 归因直接失效 —— 必填。
        wastage_type=_require_text(raw, "wastageType"),
        status=_require_text(raw, "status"),
        qty_milli=_strict_int(raw["qty"], "wastage.qty"),
        cost_cents=_strict_int(raw["cost"], "wastage.cost"),
    )


def _to_stocktaking(raw: dict) -> NormalizedStocktaking:
    return NormalizedStocktaking(
        platform=PLATFORM,
        doc_no=_require_text(raw, "docNo"),
        store_code=_require_text(raw, "shopCode"),
        biz_date=_biz_date(raw),
        ingredient=_ingredient(raw),
        status=_require_text(raw, "status"),
        system_qty_milli=_strict_int(raw["systemQty"], "stocktaking.systemQty"),
        actual_qty_milli=_strict_int(raw["actualQty"], "stocktaking.actualQty"),
        # 盘亏是负数, 这是正常业务形态而不是脏数据 —— 不做非负校验。
        # (盘点差异恰恰是"负的那一半"才有分析价值)
        diff_cost_cents=_strict_int(raw["diffCost"], "stocktaking.diffCost"),
    )


# kind → (URL 路径段, 解析函数)。路径段取自这张表而不是取自入参, 见模块 docstring。
_KIND_SPEC: Dict[str, Tuple[str, Callable[[dict], object]]] = {
    "requisition": ("requisition", _to_requisition),
    "wastage": ("wastage", _to_wastage),
    "stocktaking": ("stocktaking", _to_stocktaking),
}


class KeruyunOpsAdapter:
    """把客如云的后厨单据报文归一化成 ops_models 里的三种 dataclass。"""

    platform = PLATFORM

    def __init__(self, base_url: str, app_key: str, app_secret: str, client):
        self._base_url = base_url.rstrip("/")
        self._app_key = app_key
        self._app_secret = app_secret
        self._client = client

    async def fetch_page(self, kind: str, cursor: str, limit: int) -> OpsFetchPage:
        if kind not in _KIND_SPEC:
            # 在发请求之前就拒: 一旦拼进 URL, 错的 kind 会变成一次真实的
            # 外呼(甚至打到别的接口上), 再靠响应去发现就晚了。
            raise KeruyunOpsKindError(
                f"未知单据类型 {kind!r}, 仅支持 {sorted(_KIND_SPEC)}"
            )
        path_segment, parse = _KIND_SPEC[kind]
        params = {
            "appKey": self._app_key,
            "timestamp": str(int(time.time())),
            "cursor": str(cursor),
            "limit": str(limit),
        }
        params["sign"] = sign(params, self._app_secret)
        resp = await self._client.get(
            f"{self._base_url}/keruyun/open/stock/{path_segment}/list",
            params=params,
            timeout=30.0,
        )
        body = resp.json()
        code = str(body.get("code", ""))
        if code != "0":
            # 禁降级: 平台 HTTP 恒 200, 业务错误不能被当成"本轮无数据" ——
            # 当成空页就会推进游标, 这段增量永久丢失。
            raise KeruyunBusinessError(f"{code}: {body.get('message')}")
        data = body.get("data") or {}
        items = [parse(raw) for raw in data.get("list", [])]
        return OpsFetchPage(
            kind=kind,
            items=items,
            next_cursor=str(data.get("nextCursor", cursor)),
            has_more=bool(data.get("hasMore", False)),
        )
