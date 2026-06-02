"""P4a 候选生成: dim_product → 规则聚类 + agent resolve → PROPOSE 进 admin queue。

per docs/superpowers/specs/2026-06-02-qhj-deep-analysis-p4-dish-canonicalization.md (Task 6)。

用法:
    # dry-run (只打印聚类/提议, 不写库)
    python -m smartbi.scripts.generate_dish_canonical_candidates --factory RES_3101_009 --dry-run

    # 实写: 把提议入队 entity_resolution_admin_queue (PENDING, 等人工 confirm)
    python -m smartbi.scripts.generate_dish_canonical_candidates --factory RES_3101_009 --apply

⛔ CRITICAL (per MEMORY #364 / spec §R1 P0): 本脚本**绝不**写 dim_product.canonical_dish_id,
   **绝不**创建 dim_canonical_dish。它只把合并提议入队, 等 confirm_dish_canonical CLI 人工
   拍板。规则层"同 key"高置信也只 propose (no silent graduation)。

DB 连接 (与 P3 generate_store_review_aliases 一致): 设 BACKFILL_PG_DSN 或 INTEGRATION_PG_DSN
指向 smartbi 库。脚本以 postgres / BYPASSRLS 角色跑, 或 SET app.factory_id=<factory>; 否则
FORCE RLS 静默丢行。canonicalize_factory 内每个 conn SET app.factory_id (per #590)。

⛔ fail-loud (per #390): 入队失败 (grant gap / RLS 拒) **抛异常**, 绝不 fail-open 静默吞。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys

import asyncpg

# smartbi/services/__init__.py 等用裸 `from services...`, 需 backend/python/smartbi
# 在 sys.path (与 promote_learnings.py / live app 解析方式一致)。
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from smartbi.canonical.dish_canonicalizer import (  # noqa: E402
    PROPOSAL_CREATE_NEW,
    PROPOSAL_RULE_CLUSTER,
    canonicalize_factory,
)

logger = logging.getLogger(__name__)


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
        # RLS ctx 自检 (per #590 / P3 模式)。
        async with pool.acquire() as conn:
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, false)", factory_id
            )
            check = await conn.fetchrow(
                "SELECT current_user AS u, "
                "(SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user) AS bypass"
            )
            logger.info(
                "RLS ctx: user=%s factory_id=%r bypass_rls=%s",
                check["u"], factory_id, check["bypass"],
            )

        plan = await canonicalize_factory(
            pool, factory_id, dry_run=not args.apply
        )

        logger.info(
            "dim_product %d (已归一跳过 %d) → 提议 %d (规则聚类 %d / 新建 %d)",
            plan.total_products, plan.skipped_already_linked,
            len(plan.proposals), plan.rule_cluster_count, plan.create_new_count,
        )
        for p in plan.proposals:
            tag = "[聚类合并]" if p.proposal_kind == PROPOSAL_RULE_CLUSTER else "[新建]"
            members = " / ".join(p.member_names[:5])
            more = f" (+{len(p.member_names) - 5})" if len(p.member_names) > 5 else ""
            logger.info(
                "%-10s key=%-20s -> 建议规范名 '%s'  成员: %s%s",
                tag, p.normalized_key, p.canonical_name_suggested, members, more,
            )

        if not args.apply:
            logger.info(
                "(dry-run) 未写库; 加 --apply 把提议入队 (PENDING, 等人工 confirm)。"
            )
        else:
            logger.info(
                "已入队 %d 条提议 (PENDING)。用 confirm_dish_canonical CLI 人工确认/拒绝。"
                " ⛔ 绝不自动写 canonical_dish_id。",
                plan.enqueued,
            )
        # 与 PROPOSAL_CREATE_NEW 引用对齐 (silence unused import on some linters):
        assert PROPOSAL_CREATE_NEW  # noqa: S101
        return 0
    finally:
        await pool.close()


def main():
    parser = argparse.ArgumentParser(
        description="P4a dish canonical 候选生成 (PROPOSE only, 绝不自动合并)。"
    )
    parser.add_argument("--factory", required=True, help="factory_id, e.g. RES_3101_009")
    parser.add_argument(
        "--apply", action="store_true",
        help="实写: 把提议入队 admin queue (默认 dry-run)。",
    )
    args = parser.parse_args()
    sys.exit(asyncio.run(main_async(args)))


if __name__ == "__main__":
    main()
