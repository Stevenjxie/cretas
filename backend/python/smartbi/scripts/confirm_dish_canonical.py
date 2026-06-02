"""P4a 人工确认 CLI: 把 dish 合并提议毕业为 canonical (spec §5 Task 7).

⛔ CRITICAL #1 — 这是 P4a 的**唯一人工闸门** (per MEMORY #364 / spec §R1 P0):
   dim_canonical_dish membership + dim_product.canonical_dish_id **只有这里**写。离线
   canonicalizer / agent 高置信全部只 propose 进队列; 必须人工跑本 CLI 才落库。错合并
   (红烧牛肉 ≠ 红烧牛腩) 静默污染所有按 canonical 的分析 → 每条 confirm 都是人为判断。

用法:
    # 列出待确认 dish 提议 (PENDING)
    python -m smartbi.scripts.confirm_dish_canonical --factory RES_3101_009 --list-pending

    # 确认提议 (毕业): 新建 canonical 并把成员 dim_product 全挂上
    python -m smartbi.scripts.confirm_dish_canonical --factory RES_3101_009 \
        --confirm 123 --canonical-name "招牌青花椒鱼"

    # 确认提议: 挂到一个**已存在**的 canonical (合并到既有)
    python -m smartbi.scripts.confirm_dish_canonical --factory RES_3101_009 \
        --confirm 124 --canonical-id 7

    # 拒绝提议 (不合并)
    python -m smartbi.scripts.confirm_dish_canonical --factory RES_3101_009 \
        --reject 125 --reason "青花椒鱼 与 青花椒虾 主料不同, 非同菜"

DB 连接同 generate_dish_canonical_candidates (BACKFILL_PG_DSN / INTEGRATION_PG_DSN)。
脚本 SET app.factory_id=<factory> 以满足 FORCE RLS。

毕业镜像 (per MEMORY #389): confirm 成功后, 对每个成员 dim_product best-effort upsert 进
entity_resolution_history (entity_type='dish', a_name=成员 SKU 名, b_name=canonical 名,
b_entity_id=canonical_dish_id, conf=1.0, decided_by_agent='admin'), **fail-open BUT
log WARNING** — history 写失败绝不阻塞 confirm (主路径=canonical + canonical_dish_id 已落库),
但绝不静默吞 (per #364: fail-open 必配可观测)。

⛔ fail-loud (per #390): canonical/link 写 (主路径) 失败抛异常; 只有 history 镜像 fail-open。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys
from typing import Any, Dict, List, Optional

import asyncpg

logger = logging.getLogger(__name__)


_LIST_PENDING_SQL = """
    SELECT id, raw_name, candidate_entity_id, confidence, decided_by_agent,
           priority, reasoning, extra, created_at
      FROM entity_resolution_admin_queue
     WHERE factory_id = $1
       AND entity_type = 'dish'
       AND status = 'PENDING'
     ORDER BY priority DESC, created_at DESC
"""

_GET_ITEM_SQL = """
    SELECT id, factory_id, status, raw_name, candidate_entity_id, extra
      FROM entity_resolution_admin_queue
     WHERE id = $1 AND factory_id = $2 AND entity_type = 'dish'
"""

# 新建 canonical (ON CONFLICT 同 (factory, normalized_key) → 复用既有, 避免重复建)。
_CREATE_CANONICAL_SQL = """
    INSERT INTO dim_canonical_dish
      (factory_id, canonical_name, normalized_key, category, member_count,
       status, created_by)
    VALUES ($1, $2, $3, $4, 0, 'active', 'admin')
    ON CONFLICT (factory_id, normalized_key) DO UPDATE SET
      canonical_name = EXCLUDED.canonical_name,
      updated_at = NOW()
    RETURNING canonical_dish_id
"""

# 把成员 dim_product 挂到 canonical (只有这里写 canonical_dish_id)。
_LINK_PRODUCT_SQL = """
    UPDATE dim_product
       SET canonical_dish_id = $1
     WHERE factory_id = $2 AND product_id = ANY($3::bigint[])
    RETURNING product_id
"""

# member_count 同步 = 当前挂在该 canonical 的 dim_product 数 (权威重算, 避免漂移)。
_RECOUNT_SQL = """
    UPDATE dim_canonical_dish
       SET member_count = (
           SELECT COUNT(*) FROM dim_product
            WHERE factory_id = $2 AND canonical_dish_id = $1
       )
     WHERE factory_id = $2 AND canonical_dish_id = $1
"""

_MARK_CONFIRMED_SQL = """
    UPDATE entity_resolution_admin_queue
       SET status = 'CONFIRMED',
           admin_action = 'confirm',
           admin_user = 'admin',
           admin_at = NOW(),
           admin_resolved_to_entity_id = $2
     WHERE id = $1 AND status = 'PENDING'
    RETURNING id
"""

_MARK_REJECTED_SQL = """
    UPDATE entity_resolution_admin_queue
       SET status = 'REJECTED',
           admin_action = 'reject',
           admin_user = 'admin',
           admin_at = NOW(),
           extra = COALESCE(extra, '{}'::jsonb)
                   || jsonb_build_object('reject_reason', $2::text)
     WHERE id = $1 AND status = 'PENDING'
    RETURNING id
"""

# 毕业镜像 (per #389): dish 走 entity_resolution_history, entity_type='dish' (V04 CHECK 已扩)。
_HISTORY_MIRROR_SQL = """
    INSERT INTO entity_resolution_history
      (factory_id, entity_type, a_name, b_name, b_entity_id,
       confidence, decided_by_agent, reasoning)
    VALUES ($1, 'dish', $2, $3, $4, 1.0, 'admin', $5)
    ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
      confidence = GREATEST(entity_resolution_history.confidence, EXCLUDED.confidence),
      decided_by_agent = EXCLUDED.decided_by_agent,
      reasoning = EXCLUDED.reasoning
"""


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


def _parse_extra(extra: Any) -> Dict[str, Any]:
    if extra is None:
        return {}
    if isinstance(extra, dict):
        return extra
    try:
        return json.loads(extra)
    except (json.JSONDecodeError, TypeError, ValueError):
        return {}


async def list_pending(conn: asyncpg.Connection, factory_id: str) -> int:
    rows = await conn.fetch(_LIST_PENDING_SQL, factory_id)
    if not rows:
        logger.info("无待确认 dish 提议 (status=PENDING)。")
        return 0
    logger.info("待确认 dish 提议 %d 条:", len(rows))
    for r in rows:
        extra = _parse_extra(r["extra"])
        members = extra.get("member_names", [])
        kind = extra.get("proposal_kind", "?")
        member_preview = " / ".join(str(m) for m in members[:5])
        more = f" (+{len(members) - 5})" if len(members) > 5 else ""
        logger.info(
            "  [%d] %-12s 建议名 '%s' (key=%s)  成员: %s%s",
            int(r["id"]), kind, r["raw_name"],
            extra.get("normalized_key", "?"), member_preview, more,
        )
    return 0


async def _mirror_history(
    conn: asyncpg.Connection,
    factory_id: str,
    member_names: List[str],
    canonical_name: str,
    canonical_id: int,
) -> None:
    """best-effort 写 entity_resolution_history (per #389). fail-open + WARNING。

    history 写失败 (grant gap / RLS / CHECK) 绝不阻塞 confirm — canonical + link 已落库。
    但记 WARNING 让 deploy/审计能发现 (per #364: fail-open 必配可观测, 不静默吞)。
    """
    try:
        for a_name in member_names:
            await conn.execute(
                _HISTORY_MIRROR_SQL,
                factory_id, str(a_name), canonical_name, canonical_id,
                "admin confirmed dish canonical (P4a)",
            )
        logger.info(
            "  毕业镜像 → entity_resolution_history OK (%d 行, entity_type=dish)",
            len(member_names),
        )
    except Exception as e:  # noqa: BLE001 — fail-open by design (per #389/#364)
        logger.warning(
            "  毕业镜像写 entity_resolution_history 失败 "
            "(fail-open, 不影响 confirm; 但需关注 — 可能 grant/RLS/CHECK gap): %s",
            e,
        )


async def confirm(
    conn: asyncpg.Connection,
    factory_id: str,
    queue_id: int,
    canonical_name: Optional[str],
    canonical_id: Optional[int],
) -> int:
    """毕业一条 dish 提议: 建/挂 canonical + 写 dim_product.canonical_dish_id。

    这是唯一写 canonical membership 的路径 (per #364 人工闸门)。
    """
    item = await conn.fetchrow(_GET_ITEM_SQL, queue_id, factory_id)
    if item is None:
        logger.error("提议 id=%d 不存在于 factory=%s 的 dish 队列。", queue_id, factory_id)
        return 1
    if item["status"] != "PENDING":
        logger.error("提议 id=%d 当前状态 %s, 非 PENDING, 无法确认。",
                     queue_id, item["status"])
        return 1

    extra = _parse_extra(item["extra"])
    member_ids: List[int] = [int(x) for x in (extra.get("member_product_ids") or [])]
    member_names: List[str] = [str(x) for x in (extra.get("member_names") or [])]
    normalized_key: str = extra.get("normalized_key") or ""
    category = extra.get("category")
    if not member_ids:
        logger.error("提议 id=%d 无成员 dim_product (extra.member_product_ids 空)。", queue_id)
        return 1

    # 主路径在单一事务内: 建/挂 canonical + link products + recount + mark confirmed。
    # fail-loud — RETURNING 没行 = RLS/grant 拒, 抛异常 (per #390)。
    async with conn.transaction():
        if canonical_id is not None:
            # 挂到既有 canonical: 校验存在 + active。
            exists = await conn.fetchrow(
                "SELECT canonical_name FROM dim_canonical_dish "
                "WHERE factory_id = $1 AND canonical_dish_id = $2 AND status = 'active'",
                factory_id, canonical_id,
            )
            if exists is None:
                raise RuntimeError(
                    f"--canonical-id {canonical_id} 不存在 / 非 active "
                    f"(factory={factory_id})"
                )
            resolved_id = canonical_id
            resolved_name = exists["canonical_name"]
        else:
            # 新建 canonical (ON CONFLICT 复用同 key)。canonical_name 优先用 --canonical-name,
            # 否则用提议建议名 (raw_name)。
            new_name = canonical_name or item["raw_name"]
            row = await conn.fetchrow(
                _CREATE_CANONICAL_SQL,
                factory_id, new_name, normalized_key, category,
            )
            if row is None:
                raise RuntimeError(
                    f"建 canonical 失败 (factory={factory_id}, key={normalized_key!r}) — "
                    f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
                )
            resolved_id = int(row["canonical_dish_id"])
            resolved_name = new_name

        linked = await conn.fetch(
            _LINK_PRODUCT_SQL, resolved_id, factory_id, member_ids
        )
        if not linked:
            raise RuntimeError(
                f"link dim_product 失败 (factory={factory_id}, members={member_ids}) — "
                f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
            )
        await conn.execute(_RECOUNT_SQL, resolved_id, factory_id)

        marked = await conn.fetchval(_MARK_CONFIRMED_SQL, queue_id, resolved_id)
        if marked is None:
            raise RuntimeError(
                f"标记队列 CONFIRMED 失败 (id={queue_id}) — 可能已被并发处理 (race)。"
            )

    logger.info(
        "已确认 (毕业 admin): 提议 id=%d → canonical_dish_id=%d '%s', "
        "挂 %d 个 dim_product。",
        queue_id, resolved_id, resolved_name, len(linked),
    )

    # 毕业镜像 (best-effort, 独立于主事务; fail-open + WARNING)。
    await _mirror_history(conn, factory_id, member_names, resolved_name, resolved_id)
    return 0


async def reject(
    conn: asyncpg.Connection, factory_id: str, queue_id: int, reason: str
) -> int:
    item = await conn.fetchrow(_GET_ITEM_SQL, queue_id, factory_id)
    if item is None:
        logger.error("提议 id=%d 不存在于 factory=%s 的 dish 队列。", queue_id, factory_id)
        return 1
    if item["status"] != "PENDING":
        logger.error("提议 id=%d 当前状态 %s, 非 PENDING, 无法拒绝。",
                     queue_id, item["status"])
        return 1
    marked = await conn.fetchval(_MARK_REJECTED_SQL, queue_id, reason)
    if marked is None:
        raise RuntimeError(f"标记队列 REJECTED 失败 (id={queue_id}) — 可能已被并发处理。")
    logger.info("已拒绝提议 id=%d (原因: %s)。", queue_id, reason)
    return 0


async def main_async(args) -> int:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s"
    )
    factory_id = args.factory

    dsn = os.environ.get("BACKFILL_PG_DSN") or os.environ.get("INTEGRATION_PG_DSN")
    if not dsn:
        logger.error("Set BACKFILL_PG_DSN or INTEGRATION_PG_DSN to a smartbi DB DSN.")
        return 2

    pool = await asyncpg.create_pool(dsn, min_size=1, max_size=2, timeout=10)
    try:
        async with pool.acquire() as conn:
            await _set_tenant(conn, factory_id)
            if args.list_pending:
                return await list_pending(conn, factory_id)
            if args.confirm is not None:
                return await confirm(
                    conn, factory_id, args.confirm,
                    args.canonical_name, args.canonical_id,
                )
            if args.reject is not None:
                if not args.reason or not args.reason.strip():
                    logger.error("--reject 需要 --reason <原因>")
                    return 2
                return await reject(conn, factory_id, args.reject, args.reason)
            logger.error("需指定 --list-pending / --confirm <id> / --reject <id> 之一。")
            return 2
    finally:
        await pool.close()


def main():
    parser = argparse.ArgumentParser(
        description="P4a dish canonical 人工确认 CLI (唯一写 canonical 的人工闸门)。"
    )
    parser.add_argument("--factory", required=True, help="factory_id, e.g. RES_3101_009")
    parser.add_argument(
        "--list-pending", action="store_true", help="列出待确认 dish 提议。"
    )
    parser.add_argument(
        "--confirm", type=int, metavar="QUEUE_ID",
        help="确认该提议 (毕业): 建/挂 canonical + 链接成员 dim_product。",
    )
    parser.add_argument(
        "--canonical-name", metavar="NAME",
        help="--confirm 新建 canonical 时的规范名 (默认用提议建议名)。",
    )
    parser.add_argument(
        "--canonical-id", type=int, default=None, metavar="ID",
        help="--confirm 挂到既有 canonical 的 canonical_dish_id (合并到既有)。",
    )
    parser.add_argument(
        "--reject", type=int, metavar="QUEUE_ID", help="拒绝该提议 (需 --reason)。"
    )
    parser.add_argument("--reason", metavar="REASON", help="--reject 的原因。")
    args = parser.parse_args()
    sys.exit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
