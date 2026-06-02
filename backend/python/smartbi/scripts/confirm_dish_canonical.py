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
import logging
import os
import sys

import asyncpg

# Task 8 refactor: confirm/reject 核心移到共用服务模块, CLI 与 web-admin API 调同一份代码。
# CLI 保留 list_pending + argparse 入口; confirm/reject/_parse_extra 从 service re-export。
from smartbi.canonical.dish_confirm_service import (
    confirm,
    reject,
    _parse_extra,
)

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


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


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
