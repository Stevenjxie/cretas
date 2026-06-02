"""P3 候选生成: 评价门店名 → gold dim_store 模糊匹配 → 写 dim_store_review_alias。

per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p3-store-review-revenue.md (T3)。

用法:
    # dry-run (只打印候选, 不写库)
    python -m smartbi.scripts.generate_store_review_aliases --factory RES_3101_009 --dry-run

    # 实写 (高置信 auto 直接可用, 低置信 auto 进确认队列, no_match 可选记录)
    python -m smartbi.scripts.generate_store_review_aliases --factory RES_3101_009 --apply

    # 也记录 no_match (避免每次重跑重新尝试)
    python -m smartbi.scripts.generate_store_review_aliases --factory RES_3101_009 --apply --record-no-match

DB 连接 (与 backfill_provenance 一致): 设 BACKFILL_PG_DSN 或 INTEGRATION_PG_DSN 指向
smartbi 库。脚本以 postgres / BYPASSRLS 角色跑, 或 SET app.factory_id=<factory>; 否则
FORCE RLS 会静默丢行。脚本启动时 SET app.factory_id (per #590 教训)。

⛔ fail-loud (per feedback_smartbi_table_grant_gap): 写入失败 (grant gap / RLS 拒)
**抛异常**, 绝不 fail-open 静默吞 — 否则 0 行无人发现。

⛔ 不覆盖人工 (per MEMORY #389): ON CONFLICT 对 decided_by='admin' 行只更新 updated_at,
保留人工金标准的 store_id / confidence / match_method。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import List, Optional, Tuple

import asyncpg

# smartbi/services/__init__.py 等用裸 `from services...`, 需 backend/python/smartbi
# 在 sys.path (与 promote_learnings.py / live app 解析方式一致)。
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from smartbi.gold.store_alias_matcher import (  # noqa: E402
    Candidate,
    match_review_store,
)

logger = logging.getLogger(__name__)


# 评价门店名 (大众点评导出): smart_bi_dynamic_data.row_data->>'评价门店',
# 按 评价ID 去重的 DISTINCT 门店集 (与 review_queries._DEDUP_CTE 同口径)。
_REVIEW_STORES_SQL = """
    SELECT DISTINCT row_data->>'评价门店' AS store
      FROM smart_bi_dynamic_data
     WHERE factory_id = $1
       AND row_data ? '星级分'
       AND NULLIF(row_data->>'评价门店', '') IS NOT NULL
     ORDER BY store
"""

_DIM_STORES_SQL = """
    SELECT store_id, name
      FROM dim_store
     WHERE factory_id = $1
       AND NULLIF(name, '') IS NOT NULL
"""

# fail-loud upsert. ON CONFLICT 不覆盖人工 (decided_by='admin')。
_UPSERT_SQL = """
    INSERT INTO dim_store_review_alias
      (factory_id, review_store_name, store_id, confidence, match_method, decided_by, landmark)
    VALUES ($1, $2, $3, $4, $5, $6, $7)
    ON CONFLICT (factory_id, review_store_name) DO UPDATE SET
      store_id     = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                          THEN dim_store_review_alias.store_id
                          ELSE EXCLUDED.store_id END,
      confidence   = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                          THEN dim_store_review_alias.confidence
                          ELSE EXCLUDED.confidence END,
      match_method = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                          THEN dim_store_review_alias.match_method
                          ELSE EXCLUDED.match_method END,
      landmark     = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                          THEN dim_store_review_alias.landmark
                          ELSE EXCLUDED.landmark END,
      decided_by   = CASE WHEN dim_store_review_alias.decided_by = 'admin'
                          THEN 'admin'
                          ELSE EXCLUDED.decided_by END,
      updated_at   = NOW()
    RETURNING id, decided_by
"""


def pick_best(cands: List[Candidate]) -> Optional[Candidate]:
    """从候选挑代表行: 唯一 → 该候选; 多候选 (歧义) → 取最高置信首个 (置信已被降到 0.60)。

    多候选时各候选 store_id 不同, 但表 UNIQUE(factory, review_name) 只存一行 ——
    我们存最高置信代表 (歧义状态 conf=0.60 → 进确认队列, 不参与 join, 由人工裁决)。
    """
    if not cands:
        return None
    return max(cands, key=lambda c: (c.confidence, c.store_id))


async def upsert_alias(
    conn: asyncpg.Connection,
    factory_id: str,
    review_name: str,
    cand: Optional[Candidate],
) -> Tuple[Optional[int], str]:
    """fail-loud 写一行。返回 (id, decided_by)。写失败抛异常 (不静默吞)。"""
    if cand is None:
        store_id, confidence, method, landmark = None, 0.0, "no_match", None
    else:
        store_id, confidence, method, landmark = (
            cand.store_id, cand.confidence, cand.match_method, cand.landmark,
        )
    # decided_by: 'unmapped' for no_match (已知无对应), 'auto' otherwise.
    decided_by = "unmapped" if cand is None else "auto"
    row = await conn.fetchrow(
        _UPSERT_SQL,
        factory_id, review_name, store_id, confidence, method, decided_by, landmark,
    )
    if row is None:
        # ON CONFLICT DO UPDATE 总会 RETURNING 一行 (即使 admin 行被保护, updated_at 也更新)。
        # 走到这里说明写真的没落 → fail-loud。
        raise RuntimeError(
            f"upsert_alias: no row returned for ({factory_id!r}, {review_name!r}) — "
            f"possible RLS/grant rejection (per feedback_smartbi_table_grant_gap)"
        )
    return int(row["id"]), row["decided_by"]


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
            # RLS 启动守卫 (per backfill_provenance C1 + #590): 无 app.factory_id 且非
            # BYPASSRLS → SELECT 0 行 + INSERT 被拒, 静默。强制 SET 当前租户。
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            check = await conn.fetchrow(
                "SELECT current_setting('app.factory_id', true) AS fid, current_user AS u, "
                "(SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) AS bypass"
            )
            logger.info("RLS ctx: user=%s factory_id=%r bypass_rls=%s",
                        check["u"], check["fid"], check["bypass"])

            review_rows = await conn.fetch(_REVIEW_STORES_SQL, factory_id)
            dim_rows = await conn.fetch(_DIM_STORES_SQL, factory_id)

        review_stores = [r["store"] for r in review_rows]
        dim_stores = [(int(r["store_id"]), r["name"]) for r in dim_rows]
        logger.info("评价门店 %d 家, gold dim_store %d 家", len(review_stores), len(dim_stores))
        if not review_stores:
            logger.warning("无评价门店 (该 factory 无大众点评评价数据上传?) — 退出。")
            return 0
        if not dim_stores:
            logger.warning("无 gold dim_store (POS 营收数据未物化?) — 退出。")
            return 0

        stats = {"auto_usable": 0, "pending": 0, "no_match": 0, "written": 0}
        plan: List[Tuple[str, Optional[Candidate]]] = []
        for rs in review_stores:
            cands = match_review_store(rs, dim_stores)
            best = pick_best(cands)
            plan.append((rs, best))
            if best is None:
                stats["no_match"] += 1
                tag = "[NO_MATCH]"
            elif best.confidence >= 0.90:
                stats["auto_usable"] += 1
                tag = "[AUTO 可用]"
            else:
                stats["pending"] += 1
                tag = "[待确认]"
            tgt = f"-> {best.gold_name} (conf {best.confidence:.2f}, {best.match_method})" if best else "-> (无)"
            logger.info("%-12s %-40s %s", tag, rs, tgt)

        logger.info(
            "候选汇总: auto可用 %d / 待确认 %d / no_match %d (共 %d)",
            stats["auto_usable"], stats["pending"], stats["no_match"], len(review_stores),
        )

        if not args.apply:
            logger.info("(dry-run) 未写库; 加 --apply 写入。")
            return 0

        async with pool.acquire() as conn:
            await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)
            for review_name, best in plan:
                if best is None and not args.record_no_match:
                    continue  # 默认不写 no_match (留人工显式标 unmapped)。
                _id, decided = await upsert_alias(conn, factory_id, review_name, best)
                stats["written"] += 1
        logger.info("已写 %d 行 (含 no_match=%s)。", stats["written"], args.record_no_match)
        return 0
    finally:
        await pool.close()


def main():
    parser = argparse.ArgumentParser(description="P3 评价门店名 → gold dim_store 候选生成。")
    parser.add_argument("--factory", required=True, help="factory_id, e.g. RES_3101_009")
    parser.add_argument("--apply", action="store_true", help="实写库 (默认 dry-run)。")
    parser.add_argument("--record-no-match", action="store_true",
                        help="同时写 no_match 行 (decided_by=unmapped), 避免重跑重试。")
    args = parser.parse_args()
    sys.exit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
