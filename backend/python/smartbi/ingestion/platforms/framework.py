"""拉取框架: 游标推进 / 幂等 / 失败隔离 / 禁降级。

禁降级在这里的含义: 拉不到或写不进, 都必须抛错并**保持游标不动**。
把失败当成「本轮无数据」再推进游标, 那批数据就永久丢了。
"""
from __future__ import annotations

import logging
import time
from typing import Any, Awaitable, Callable, Dict, List, Protocol

from .cursor_store import read_cursor, write_cursor
from .models import FetchPage, NormalizedOrder

logger = logging.getLogger(__name__)

DEFAULT_PAGE_SIZE = 200


class PlatformSyncError(RuntimeError):
    """本轮同步失败。调用方负责隔离: 一个平台失败不影响其他平台。"""

    def __init__(self, message: str, *, cursor=None):
        super().__init__(message)
        # 卡死检测要靠它区分「游标在推进但偶发失败」和「同一页反复重拉」。
        self.cursor = cursor


# ─────────────────────────────────────────────────────────────────────
# 卡死检测 —— 把「瞬时故障」和「永久卡死」分开
# ─────────────────────────────────────────────────────────────────────
#
# 本框架的失败语义是**宁可卡住也不漏**: 写失败 → 整页回滚 → write_cursor 被跳过
# → 游标不动 → 下轮重拉同一页。一条**永久性**坏记录会让那类数据永远停在那一页。
#
# 取舍本身是刻意的, 问题在于**它是隐性的**: 网络抖一下和数据永久卡死打出来的
# 日志一模一样。真卡了没人会知道 —— 游标静默停住、数据悄悄断流, 而唯一的补救
# (手工改游标行)的前提是先有人发现。
#
# 这里只做**观测**, 不碰完整性语义: 同一游标连续失败到阈值就打一条带稳定标记的
# ERROR(含游标值/连续轮次/持续时长), 便于 grep 与挂告警。游标变了就清零 ——
# 那说明数据在推进, 只是某轮抖动。
JAM_ALERT_THRESHOLD = 3

# {cursor_key: {"cursor":…, "consecutive_failures":…, "first_failed_at":…}}
_JAM_TRACKER: Dict[str, Dict[str, Any]] = {}


def reset_jam_tracker() -> None:
    """测试/运维用: 清空卡死跟踪。"""
    _JAM_TRACKER.clear()


def record_sync_success(cursor_key: str) -> None:
    """本轮成功 —— 清零。卡死解除必须能被观测到。"""
    _JAM_TRACKER.pop(cursor_key, None)


def record_sync_failure(cursor_key: str, cursor) -> bool:
    """记一次失败, 返回是否应当升级为卡死告警。

    游标与上轮不同 = 数据在推进, 重新计数(不是卡死)。
    """
    entry = _JAM_TRACKER.get(cursor_key)
    if entry is None or entry["cursor"] != cursor:
        _JAM_TRACKER[cursor_key] = {
            "cursor": cursor,
            "consecutive_failures": 1,
            "first_failed_at": time.time(),
        }
        return False
    entry["consecutive_failures"] += 1
    return entry["consecutive_failures"] >= JAM_ALERT_THRESHOLD


def jam_state() -> Dict[str, Dict[str, Any]]:
    """运维探针: 现在有没有卡住的、卡了多久。空 dict = 一切正常。"""
    now = time.time()
    return {
        key: {
            "cursor": e["cursor"],
            "consecutive_failures": e["consecutive_failures"],
            "stuck_seconds": round(now - e["first_failed_at"], 1),
        }
        for key, e in _JAM_TRACKER.items()
    }


def _note_sync_failure(cursor_key: str, exc: BaseException) -> None:
    """统一的失败记账 + 卡死升级。sync_all / sync_ops 共用。"""
    cursor = getattr(exc, "cursor", None)
    if not record_sync_failure(cursor_key, cursor):
        return
    entry = jam_state().get(cursor_key, {})
    # 稳定标记 [platform-sync][STUCK] 供 grep / 告警规则匹配。
    logger.error(
        "[platform-sync][STUCK] %s 卡在同一游标 cursor=%r 连续 %s 轮 "
        "(已 %.0f 秒), 该类数据已停止流入。最后一次错误: %s",
        cursor_key, cursor, entry.get("consecutive_failures"),
        entry.get("stuck_seconds", 0), exc,
    )


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
    try:
        cursor = await read_cursor(pool, factory_id, adapter.platform)
    except Exception as exc:  # noqa: BLE001 — 统一成 PlatformSyncError 供上层隔离
        raise PlatformSyncError(
            f"[{adapter.platform}] 读游标失败: {exc}"
        ) from exc
    total = 0
    for _ in range(max_pages):
        try:
            page = await adapter.fetch_page(cursor, page_size)
            # 属性访问也必须在保护范围内: adapter 返回畸形对象时,
            # 裸的 AttributeError 会绕过 PlatformSyncError 直接冒到 sync_all。
            orders = page.orders
            next_cursor = page.next_cursor
            has_more = page.has_more
        except Exception as exc:  # noqa: BLE001 — 统一成 PlatformSyncError 供上层隔离
            raise PlatformSyncError(
                f"[{adapter.platform}] 拉取失败 cursor={cursor}: {exc}", cursor=cursor,
            ) from exc
        if orders:
            try:
                written = await write_orders(pool, factory_id, orders)
            except Exception as exc:  # noqa: BLE001
                raise PlatformSyncError(
                    f"[{adapter.platform}] 写入失败 cursor={cursor}: {exc}", cursor=cursor,
                ) from exc
            total += written
        cursor = next_cursor
        try:
            await write_cursor(pool, factory_id, adapter.platform, cursor)
        except Exception as exc:  # noqa: BLE001
            raise PlatformSyncError(
                f"[{adapter.platform}] 推进游标失败 cursor={cursor}: {exc}", cursor=cursor,
            ) from exc
        if not has_more:
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
            record_sync_success(f"{factory_id}/{adapter.platform}")
        except PlatformSyncError as exc:
            logger.error("[platform-sync] %s", exc)
            _note_sync_failure(f"{factory_id}/{adapter.platform}", exc)
            results[adapter.platform] = f"ERROR: {exc}"
        except Exception as exc:  # noqa: BLE001
            # 最后一道防线: 故意没有对应测试 —— 它接的是我们没预料到的异常。
            # 失败隔离是硬契约, 任何异常都不能打断 for 循环让后面的平台整轮不同步。
            logger.exception("[platform-sync] %s 未预期异常", adapter.platform)
            results[adapter.platform] = f"ERROR: 未预期异常 {exc}"
    return results


# ── 后厨供应链拉取 (2026-07-29) ──────────────────────────────────────
# 与订单同一套「先写入、后推进游标」的纪律, 但三类单据各有独立游标:
# 游标表的 platform 列是自由字符串, 用 "keruyun:requisition" 这样分键。
# 合用一个游标的话, 三类数据的进度会互相顶掉 —— 拉完领料把游标推到 100,
# 损耗就从 100 开始, 前 100 条损耗永远拉不到。

OPS_KINDS = ("requisition", "wastage", "stocktaking")


async def sync_ops_kind(pool, adapter, *, factory_id: str, kind: str,
                        write_ops, max_pages: int = 20,
                        page_size: int = DEFAULT_PAGE_SIZE) -> int:
    """拉一轮某一类后厨单据。返回写入条数。"""
    cursor_key = f"{adapter.platform}:{kind}"
    try:
        cursor = await read_cursor(pool, factory_id, cursor_key)
    except Exception as exc:  # noqa: BLE001
        raise PlatformSyncError(f"[{cursor_key}] 读游标失败: {exc}") from exc
    total = 0
    for _ in range(max_pages):
        try:
            page = await adapter.fetch_page(kind, cursor, page_size)
            # 属性访问也在保护范围内(同 sync_platform): 畸形返回时裸的
            # AttributeError 会绕过 PlatformSyncError 冒到上层, 破坏失败隔离。
            items = page.items
            next_cursor = page.next_cursor
            has_more = page.has_more
        except Exception as exc:  # noqa: BLE001
            raise PlatformSyncError(
                f"[{cursor_key}] 拉取失败 cursor={cursor}: {exc}") from exc
        if items:
            try:
                total += await write_ops(pool, factory_id, kind, items)
            except Exception as exc:  # noqa: BLE001
                raise PlatformSyncError(
                    f"[{cursor_key}] 写入失败 cursor={cursor}: {exc}") from exc
        cursor = next_cursor
        try:
            await write_cursor(pool, factory_id, cursor_key, cursor)
        except Exception as exc:  # noqa: BLE001
            raise PlatformSyncError(
                f"[{cursor_key}] 推进游标失败 cursor={cursor}: {exc}") from exc
        if not has_more:
            break
    else:
        logger.info("[%s] 本轮达到 max_pages=%d, 剩余留给下一轮", cursor_key, max_pages)
    return total


async def sync_ops_all(pool, adapter, *, factory_id: str, write_ops) -> dict:
    """三类单据逐个同步。**失败隔离**: 一类抛错不影响其余两类。

    与 sync_all 同样的理由 —— 盘点接口挂了不该顺带让领料也停更。
    """
    results: dict = {}
    for kind in OPS_KINDS:
        try:
            results[kind] = await sync_ops_kind(
                pool, adapter, factory_id=factory_id, kind=kind, write_ops=write_ops)
            record_sync_success(f"{factory_id}/{adapter.platform}:{kind}")
        except PlatformSyncError as exc:
            logger.error("[platform-sync] %s", exc)
            # 三类各走各的游标键 —— 领料卡住不该算到损耗头上(反之亦然)。
            _note_sync_failure(f"{factory_id}/{adapter.platform}:{kind}", exc)
            results[kind] = f"ERROR: {exc}"
        except Exception as exc:  # noqa: BLE001
            # 最后一道防线, 同 sync_all: 失败隔离是硬契约。
            logger.exception("[platform-sync] %s 未预期异常", kind)
            results[kind] = f"ERROR: 未预期异常 {exc}"
    return results
