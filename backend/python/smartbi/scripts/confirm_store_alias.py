"""P3 人工确认 CLI: 确认/纠正/标无对应 评价门店名 → gold dim_store 映射。

per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p3-store-review-revenue.md (T4)。

用法:
    # 列出待确认 (低置信 auto: decided_by=auto AND confidence<0.90)
    python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 --list-pending

    # 确认正确映射 (毕业: store_id + conf=1.0 + decided_by=admin)
    python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 \
        --confirm "鲜行者X顺德小馆(虹口龙之梦店)" --store-id 137

    # 显式标无 POS 对应 (外卖卫星店/鲜行者): store_id NULL + decided_by=unmapped
    python -m smartbi.scripts.confirm_store_alias --factory RES_3101_009 \
        --unmap "青花椒·外卖卫星店(五角场店)"

DB 连接同 generate_store_review_aliases (BACKFILL_PG_DSN / INTEGRATION_PG_DSN)。
脚本 SET app.factory_id=<factory> 以满足 FORCE RLS。

毕业镜像 (per MEMORY #389): --confirm 成功后 best-effort upsert 进
entity_resolution_history (entity_type='store', a_name=评价名, b_entity_id=store_id,
conf=1.0, decided_by_agent='admin'), **fail-open 不阻塞确认** — history 表有 grant
则写, 无则记 warning 不崩 (P3 主路径不依赖它, 它是 transitive agent 的未来复用增强)。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys

import asyncpg

logger = logging.getLogger(__name__)


_LIST_PENDING_SQL = """
    SELECT review_store_name, store_id, confidence, match_method, landmark, created_at
      FROM dim_store_review_alias
     WHERE factory_id = $1
       AND decided_by = 'auto'
       AND confidence < 0.90
     ORDER BY created_at DESC
"""

# 确认: 毕业为 admin 金标准。fail-loud (RETURNING 必须有行, 否则写没落)。
_CONFIRM_SQL = """
    INSERT INTO dim_store_review_alias
      (factory_id, review_store_name, store_id, confidence, match_method, decided_by)
    VALUES ($1, $2, $3, 1.0, 'admin', 'admin')
    ON CONFLICT (factory_id, review_store_name) DO UPDATE SET
      store_id     = EXCLUDED.store_id,
      confidence   = 1.0,
      match_method = 'admin',
      decided_by   = 'admin',
      updated_at   = NOW()
    RETURNING id
"""

# 标无对应: store_id NULL + decided_by=unmapped。
_UNMAP_SQL = """
    INSERT INTO dim_store_review_alias
      (factory_id, review_store_name, store_id, confidence, match_method, decided_by)
    VALUES ($1, $2, NULL, 0.0, 'no_match', 'unmapped')
    ON CONFLICT (factory_id, review_store_name) DO UPDATE SET
      store_id     = NULL,
      confidence   = 0.0,
      match_method = 'no_match',
      decided_by   = 'unmapped',
      updated_at   = NOW()
    RETURNING id
"""

# 毕业镜像进 entity_resolution_history (镜像 orchestrator._write_history 列形状)。
_HISTORY_MIRROR_SQL = """
    INSERT INTO entity_resolution_history
      (factory_id, entity_type, a_name, b_name, b_entity_id,
       confidence, decided_by_agent, reasoning)
    VALUES ($1, 'store', $2, $3, $4, 1.0, 'admin', $5)
    ON CONFLICT (factory_id, entity_type, a_name, b_entity_id) DO UPDATE SET
      confidence = GREATEST(entity_resolution_history.confidence, EXCLUDED.confidence),
      decided_by_agent = EXCLUDED.decided_by_agent,
      reasoning = EXCLUDED.reasoning
"""


async def _set_tenant(conn: asyncpg.Connection, factory_id: str) -> None:
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)


async def list_pending(conn: asyncpg.Connection, factory_id: str) -> int:
    rows = await conn.fetch(_LIST_PENDING_SQL, factory_id)
    if not rows:
        logger.info("无待确认映射 (decided_by=auto AND confidence<0.90)。")
        return 0
    logger.info("待确认映射 %d 条:", len(rows))
    for r in rows:
        logger.info(
            "  conf=%.2f [%s] 地标=%-8s store_id=%s  <- %s",
            float(r["confidence"]), r["match_method"], r["landmark"] or "-",
            r["store_id"], r["review_store_name"],
        )
    return 0


async def _mirror_to_history(
    conn: asyncpg.Connection, factory_id: str, review_name: str, store_id: int
) -> None:
    """best-effort 写 entity_resolution_history (fail-open: 不阻塞确认)。"""
    try:
        row = await conn.fetchrow(
            "SELECT name FROM dim_store WHERE factory_id = $1 AND store_id = $2",
            factory_id, store_id,
        )
        b_name = row["name"] if row else review_name
        await conn.execute(
            _HISTORY_MIRROR_SQL,
            factory_id, review_name, b_name, store_id,
            "P3 admin confirm store_review_alias",
        )
        logger.info("  毕业镜像 → entity_resolution_history OK (b_name=%s)", b_name)
    except Exception as e:  # noqa: BLE001 — fail-open by design (per §2.3)
        logger.warning(
            "  毕业镜像写 entity_resolution_history 失败 (fail-open, 不影响确认): %s", e,
        )


async def confirm(
    conn: asyncpg.Connection, factory_id: str, review_name: str, store_id: int
) -> int:
    # 校验 store_id 存在于该 factory 的 dim_store (防把评分挂到不存在的店)。
    exists = await conn.fetchval(
        "SELECT 1 FROM dim_store WHERE factory_id = $1 AND store_id = $2",
        factory_id, store_id,
    )
    if not exists:
        logger.error("store_id=%s 不存在于 factory=%s 的 dim_store — 拒绝确认。",
                     store_id, factory_id)
        return 1
    row_id = await conn.fetchval(_CONFIRM_SQL, factory_id, review_name, store_id)
    if row_id is None:
        raise RuntimeError(
            f"confirm: no row returned for ({factory_id!r}, {review_name!r}) — "
            f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
        )
    logger.info("已确认 (毕业 admin): '%s' → store_id=%s (id=%s)", review_name, store_id, row_id)
    await _mirror_to_history(conn, factory_id, review_name, store_id)
    return 0


async def unmap(conn: asyncpg.Connection, factory_id: str, review_name: str) -> int:
    row_id = await conn.fetchval(_UNMAP_SQL, factory_id, review_name)
    if row_id is None:
        raise RuntimeError(
            f"unmap: no row returned for ({factory_id!r}, {review_name!r}) — "
            f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
        )
    logger.info("已标无对应 (unmapped): '%s' (id=%s)", review_name, row_id)
    return 0


async def main_async(args) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
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
            if args.confirm:
                if args.store_id is None:
                    logger.error("--confirm 需要 --store-id <gold dim_store.store_id>")
                    return 2
                return await confirm(conn, factory_id, args.confirm, args.store_id)
            if args.unmap:
                return await unmap(conn, factory_id, args.unmap)
            logger.error("需指定 --list-pending / --confirm / --unmap 之一。")
            return 2
    finally:
        await pool.close()


def main():
    parser = argparse.ArgumentParser(description="P3 评价门店映射人工确认 CLI。")
    parser.add_argument("--factory", required=True, help="factory_id, e.g. RES_3101_009")
    parser.add_argument("--list-pending", action="store_true",
                        help="列出待确认 (低置信 auto)。")
    parser.add_argument("--confirm", metavar="REVIEW_NAME",
                        help="确认该评价门店名映射 (需 --store-id)。")
    parser.add_argument("--store-id", type=int, default=None,
                        help="--confirm 的目标 gold dim_store.store_id。")
    parser.add_argument("--unmap", metavar="REVIEW_NAME",
                        help="标该评价门店名为无 POS 对应 (外卖卫星/鲜行者)。")
    args = parser.parse_args()
    sys.exit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
