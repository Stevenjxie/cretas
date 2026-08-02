"""客如云风格 **菜单主数据** adapter(菜品 / 食材 / 配方)。

与订单、后厨供应链两个 adapter 同源: 同一套 sign、同一套 `_strict_int`、同一套
「业务码非 0 就抛错」。**import 复用而不是复制** —— 复制出第三份 sign 实现就等于
把 `test_keruyun_adapter.py` 的对拍锁绕开: 改了这边而对拍还盯着那边, 线上症状是
「一直 AUTH_SIGN_INVALID 但两边代码看着都对」。

三类主数据只在 URL 路径段与字段映射上有差异, 所以同样集中成一张
kind → (路径段, 解析函数) 的白名单表。**kind 决定 URL 路径段, 绝不能透传**。

不碰 DB, 不管游标推进 —— 只负责 HTTP + 归一化。
"""
from __future__ import annotations

import time
from typing import Callable, Dict, Tuple

from .keruyun import (
    PLATFORM,
    KeruyunBusinessError,
    KeruyunPayloadError,
    _strict_int,
    sign,
)
from .menu_models import (
    MenuFetchPage,
    NormalizedDish,
    NormalizedIngredient,
    NormalizedRecipeLine,
)

__all__ = [
    "KeruyunMenuAdapter",
    "KeruyunMenuKindError",
    "KeruyunBusinessError",
    "KeruyunPayloadError",
]


class KeruyunMenuKindError(ValueError):
    """请求了白名单之外的主数据类型。"""


def _require_text(raw: dict, key: str) -> str:
    """必填字符串。缺失 / 空白 / 非字符串一律抛错。

    禁降级: 这些字段是主数据的身份(菜编码、菜名、食材编码)。缺了就兜底成空串,
    写库时会按自然键 get-or-create 出一条谁也对不上的「空菜品」, 比整页失败更难
    发现 —— 它看起来像有数据。
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
    value = raw.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise KeruyunPayloadError(
            f"{key} 必须是字符串, 收到 {type(value).__name__} {value!r}"
        )
    return value.strip() or None


def _to_dish(raw: dict) -> NormalizedDish:
    cost = _strict_int(raw["cost"], "dish.cost")
    price = _strict_int(raw["price"], "dish.price")
    # 成本 <= 0 的菜进不了成本表(ETL 的 food_cost > 0 过滤), 与其静默丢一道菜,
    # 不如在这里就说清楚是哪一道 —— 丢菜的表现是「毛利榜少了一行」, 没人会察觉。
    if cost <= 0:
        raise KeruyunPayloadError(f"菜品成本必须为正: {raw!r}")
    if price <= 0:
        raise KeruyunPayloadError(f"菜品售价必须为正: {raw!r}")
    return NormalizedDish(
        platform=PLATFORM,
        dish_code=_require_text(raw, "dishCode"),
        name=_require_text(raw, "dishName"),
        category=_optional_text(raw, "category"),
        price_cents=price,
        cost_cents=cost,
    )


def _to_ingredient(raw: dict) -> NormalizedIngredient:
    unit_price = _strict_int(raw["unitPrice"], "ingredient.unitPrice")
    if unit_price <= 0:
        # 单价为 0 的食材会让整道菜的分摊分母塌成 0 —— 分摊算不出来。
        raise KeruyunPayloadError(f"食材单价必须为正: {raw!r}")
    return NormalizedIngredient(
        platform=PLATFORM,
        ingredient_code=_require_text(raw, "ingredientCode"),
        name=_require_text(raw, "ingredientName"),
        category=_optional_text(raw, "category"),
        unit=_optional_text(raw, "unit"),
        unit_price_cents=unit_price,
    )


def _to_recipe_line(raw: dict) -> NormalizedRecipeLine:
    qty = _strict_int(raw["qty"], "recipe.qty")
    if qty <= 0:
        raise KeruyunPayloadError(f"配方用量必须为正: {raw!r}")
    return NormalizedRecipeLine(
        platform=PLATFORM,
        dish_code=_require_text(raw, "dishCode"),
        ingredient_code=_require_text(raw, "ingredientCode"),
        qty_milli=qty,
    )


# kind → (URL 路径段, 解析函数)。路径段取自本表而不是入参, 见模块 docstring。
_KIND_SPEC: Dict[str, Tuple[str, Callable[[dict], object]]] = {
    "dish": ("dish", _to_dish),
    "ingredient": ("ingredient", _to_ingredient),
    "recipe": ("recipe", _to_recipe_line),
}


class KeruyunMenuAdapter:
    """把客如云的菜单主数据报文归一化成 menu_models 里的三种 dataclass。"""

    platform = PLATFORM

    def __init__(self, base_url: str, app_key: str, app_secret: str, client):
        self._base_url = base_url.rstrip("/")
        self._app_key = app_key
        self._app_secret = app_secret
        self._client = client

    async def fetch_page(self, kind: str, cursor: str, limit: int) -> MenuFetchPage:
        if kind not in _KIND_SPEC:
            raise KeruyunMenuKindError(
                f"未知主数据类型 {kind!r}, 仅支持 {sorted(_KIND_SPEC)}"
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
            f"{self._base_url}/keruyun/open/menu/{path_segment}/list",
            params=params,
            timeout=30.0,
        )
        body = resp.json()
        code = str(body.get("code", ""))
        if code != "0":
            # 禁降级: 平台 HTTP 恒 200, 业务错误不能被当成「本轮无数据」——
            # 当成空页会推进游标, 这批主数据就再也不会被拉取。
            raise KeruyunBusinessError(
                f"平台业务码非 0: code={code} message={body.get('message')!r}"
            )
        data = body.get("data") or {}
        raw_list = data.get("list")
        if not isinstance(raw_list, list):
            raise KeruyunPayloadError(f"data.list 不是数组: {data!r}")
        items = tuple(parse(item) for item in raw_list)
        return MenuFetchPage(
            items=items,
            next_cursor=str(data.get("nextCursor", cursor)),
            has_more=bool(data.get("hasMore", False)),
        )

    async def fetch_all(self, kind: str, limit: int = 200) -> Tuple[object, ...]:
        """把一类主数据翻到底。

        主数据总量很小(菜品 10 / 食材 13 / 配方 22), 且**分摊必须拿到整套**才能
        算 —— 只有一半配方时分母是错的, 会算出偏高的 line_cost。所以这里一次翻完
        而不是逐页写库。有界: 翻页次数上限防止平台游标不推进时死循环。
        """
        out: list = []
        cursor = "0"
        for _ in range(64):
            page = await self.fetch_page(kind, cursor, limit)
            out.extend(page.items)
            if not page.has_more:
                return tuple(out)
            if str(page.next_cursor) == str(cursor):
                raise KeruyunPayloadError(
                    f"游标未推进({cursor}), 拒绝无限翻页 kind={kind}"
                )
            cursor = str(page.next_cursor)
        raise KeruyunPayloadError(f"翻页次数超上限, kind={kind}")
