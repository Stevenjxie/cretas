"""P4a·卡3 restaurant_dish_alias pending 候选人审确认 (confirm/reject).

migration V20260728_02 给 restaurant_dish_alias 加了三态 status (pending/confirmed/
rejected)。dish_alias_matcher.propose_dish_alias_candidates 只产出 pending 行 (机器
初匹配, 绝不自动确认, per MEMORY #364)。本模块是 pending → confirmed/rejected 的
唯一人工闸门, 供 CLI / API 共用 —— 镜像 dish_confirm_service.py 对
entity_resolution_admin_queue 的做法, 但作用在 restaurant_dish_alias 本表 (migration
已把三态字段落在别名行本身, 不需要额外 queue 表)。

⛔ resolver (dish_alias_resolver.py) 只读 status='confirmed' —— 在这里 confirm 之前,
pending 候选对线上答案零影响 (fail-closed, per migration 头注)。

⛔ fail-loud (per #390): 标记 confirmed/rejected 若被并发处理抢先 (RETURNING 空)
抛 RuntimeError, 绝不静默吞。前置检查失败 (不存在 / 非 pending) 返回 1, 不抛异常
(镜像 dish_confirm_service.confirm/reject 的返回码约定)。
"""
from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    import asyncpg

logger = logging.getLogger(__name__)


_GET_PENDING_SQL = """
    SELECT id, factory_id, original_name, canonical_name, canonical_dish_id, status
      FROM restaurant_dish_alias
     WHERE id = $1 AND factory_id = $2
"""

_CONFIRM_SQL = """
    UPDATE restaurant_dish_alias
       SET status = 'confirmed',
           canonical_name = COALESCE($3, canonical_name),
           reviewed_by = $4,
           reviewed_at = NOW()
     WHERE id = $1 AND factory_id = $2 AND status = 'pending'
    RETURNING id
"""

_REJECT_SQL = """
    UPDATE restaurant_dish_alias
       SET status = 'rejected',
           reviewed_by = $3,
           reviewed_at = NOW()
     WHERE id = $1 AND factory_id = $2 AND status = 'pending'
    RETURNING id
"""


async def confirm_alias_candidate(
    conn: "asyncpg.Connection",
    factory_id: str,
    alias_id: int,
    reviewer: str,
    canonical_name: Optional[str] = None,
) -> int:
    """人工确认一条 pending 别名候选 → status='confirmed'.

    canonical_name 给定时覆盖候选建议名 (人工可修正机器建议的展示名, canonical_dish_id
    本身不可在这里改 —— 挂错 ID 应 reject 后重新提议, 不做"边确认边改绑定"的危险操作)。

    Returns:
        0 成功; 1 = 不存在 / 非 pending (幂等失败, 调用方按需处理, 不抛异常)。

    Raises:
        RuntimeError: RETURNING 空 (并发抢先处理 / RLS-grant 拒绝) — fail-loud。
    """
    item = await conn.fetchrow(_GET_PENDING_SQL, alias_id, factory_id)
    if item is None:
        logger.error("别名候选 id=%d 不存在于 factory=%s 的 restaurant_dish_alias。",
                     alias_id, factory_id)
        return 1
    if item["status"] != "pending":
        logger.error(
            "别名候选 id=%d 当前状态 %s, 非 pending, 无法确认。", alias_id, item["status"]
        )
        return 1

    row = await conn.fetchrow(_CONFIRM_SQL, alias_id, factory_id, canonical_name, reviewer)
    if row is None:
        raise RuntimeError(
            f"确认别名候选失败 (id={alias_id}, factory={factory_id}) — "
            f"可能已被并发处理 (race), 或权限拒绝。"
        )
    logger.info(
        "别名候选 id=%d 已确认 (reviewer=%s) → canonical_dish_id=%s。",
        alias_id, reviewer, item["canonical_dish_id"],
    )
    return 0


async def reject_alias_candidate(
    conn: "asyncpg.Connection",
    factory_id: str,
    alias_id: int,
    reviewer: str,
) -> int:
    """人工拒绝一条 pending 别名候选 → status='rejected' (不合并, 保留记录不再复议)。

    Returns:
        0 成功; 1 = 不存在 / 非 pending。

    Raises:
        RuntimeError: RETURNING 空 (并发抢先处理) — fail-loud。
    """
    item = await conn.fetchrow(_GET_PENDING_SQL, alias_id, factory_id)
    if item is None:
        logger.error("别名候选 id=%d 不存在于 factory=%s 的 restaurant_dish_alias。",
                     alias_id, factory_id)
        return 1
    if item["status"] != "pending":
        logger.error(
            "别名候选 id=%d 当前状态 %s, 非 pending, 无法拒绝。", alias_id, item["status"]
        )
        return 1

    row = await conn.fetchrow(_REJECT_SQL, alias_id, factory_id, reviewer)
    if row is None:
        raise RuntimeError(
            f"拒绝别名候选失败 (id={alias_id}, factory={factory_id}) — 可能已被并发处理。"
        )
    logger.info("别名候选 id=%d 已拒绝 (reviewer=%s)。", alias_id, reviewer)
    return 0
