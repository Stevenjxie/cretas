"""拉取框架: 游标推进 / 幂等 / 失败隔离 / 禁降级。

禁降级在这里的含义: 拉不到或写不进, 都必须抛错并**保持游标不动**。
把失败当成「本轮无数据」再推进游标, 那批数据就永久丢了。
"""
from __future__ import annotations

import logging
from typing import Awaitable, Callable, List, Protocol

from .cursor_store import read_cursor, write_cursor
from .models import FetchPage, NormalizedOrder

logger = logging.getLogger(__name__)

DEFAULT_PAGE_SIZE = 200


class PlatformSyncError(RuntimeError):
    """本轮同步失败。调用方负责隔离: 一个平台失败不影响其他平台。"""


class PlatformAdapter(Protocol):
    platform: str

    async def fetch_page(self, cursor: str, limit: int) -> FetchPage: ...


WriteOrders = Callable[[object, str, List[NormalizedOrder]], Awaitable[int]]


async def sync_platform(pool, adapter: PlatformAdapter, *, factory_id: str,
                        write_orders: WriteOrders, max_pages: int = 20,
                        page_size: int = DEFAULT_PAGE_SIZE) -> int:
    """拉一轮增量。返回本轮写入的订单数。

    每页「先写入、后推进游标」: 写入是幂等的(平台单号唯一键), 崩在中间下轮重拉
    只会命中冲突不会重复计数; 反过来先推进游标就会漏数据。
    """
    cursor = await read_cursor(pool, factory_id, adapter.platform)
    total = 0
    for _ in range(max_pages):
        try:
            page = await adapter.fetch_page(cursor, page_size)
        except Exception as exc:  # noqa: BLE001 — 统一成 PlatformSyncError 供上层隔离
            raise PlatformSyncError(
                f"[{adapter.platform}] 拉取失败 cursor={cursor}: {exc}"
            ) from exc
        if page.orders:
            try:
                written = await write_orders(pool, factory_id, page.orders)
            except Exception as exc:  # noqa: BLE001
                raise PlatformSyncError(
                    f"[{adapter.platform}] 写入失败 cursor={cursor}: {exc}"
                ) from exc
            total += written
        cursor = page.next_cursor
        await write_cursor(pool, factory_id, adapter.platform, cursor)
        if not page.has_more:
            break
    else:
        logger.info("[%s] 本轮达到 max_pages=%d, 剩余留给下一轮", adapter.platform, max_pages)
    return total


async def sync_all(pool, adapters, *, factory_id: str, write_orders: WriteOrders) -> dict:
    """按平台逐个同步。**失败隔离**: 一个平台抛错不影响其余平台。

    返回 {platform: 写入数 或 错误字符串}。
    """
    results: dict = {}
    for adapter in adapters:
        try:
            results[adapter.platform] = await sync_platform(
                pool, adapter, factory_id=factory_id, write_orders=write_orders
            )
        except PlatformSyncError as exc:
            logger.error("[platform-sync] %s", exc)
            results[adapter.platform] = f"ERROR: {exc}"
    return results
