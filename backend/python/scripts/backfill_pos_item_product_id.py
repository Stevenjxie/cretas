"""回填 fact_pos_item.product_id —— 给「写了 Silver 但没解析菜品维度」的租户。

背景 (2026-07-29): 平台 connector 的 writer 早期只把菜名写进
`source_item_raw`，`product_id` 一直是 NULL。后果不是少一列而已 ——
`agg_product` / `agg_discount` 的物化 SQL 带 `WHERE product_id IS NOT NULL`，
于是菜品维度整个物化出 0 行，餐饮 AI 的语义规划器直接弃权
（tiered-answer 返回 `delegate:false`）。菜品是餐饮分析的主轴，缺了它
问答层等于不可用。

writer 已修好，新订单会带 product_id；这个脚本补历史行。

做法：按 `source_item_raw` 去重 → `dim_product` UPSERT（沿用
`canonical/dim_resolver.py` 的 SQL 形状，归一化用仓库既有的
`normalize_for_dim`，保证与其它通道的匹配口径一致）→ 批量 UPDATE 回填。

**category 留空**：历史行只存了菜名，分类无从得知。不猜也不填「未分类」
（禁降级）。后续新订单流过来时 writer 的
`category = COALESCE(EXCLUDED.category, dim_product.category)` 会把分类补上，
所以这里留 NULL 是会自愈的，不是永久空洞。

用法：
    cd backend/python
    PYTHONPATH=$PWD/smartbi python -m scripts.backfill_pos_item_product_id \\
        --factory-ids MOCK_REST [--dry-run]

    ⚠️ PYTHONPATH 必须指到 `backend/python/smartbi` —— `main.py` 会把这个目录
       塞进 sys.path，独立脚本不会，缺了就 `No module named 'services'`。
    ⚠️ POSTGRES_DB/USER/HOST/PORT 来自 systemd unit 的 `Environment=`，**不在**
       `.env.prod` 里；直接 `set -a; . .env.prod` 会拿到残缺值。

幂等：只碰 `product_id IS NULL` 的行，重复跑第二次影响 0 行。
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from typing import Dict, List

logger = logging.getLogger(__name__)

# 与 writer / canonical dim_resolver 同形。
_PRODUCT_UPSERT_SQL = """
INSERT INTO dim_product (factory_id, name, normalized_name, category)
VALUES ($1, $2, $3, NULL)
ON CONFLICT (factory_id, normalized_name)
  DO UPDATE SET updated_at = NOW()
RETURNING product_id
"""


def plan_backfill(raw_names):
    """原始菜名列表 → (raw → normalized 映射, 归一化后为空的那些)。

    纯函数，不碰库 —— 去重口径是这个脚本唯一容易写错的地方，单独拎出来测。
    多个原始名可能归一化到同一个菜（全角空格 / 标点差异），这正是要的效果。
    """
    from smartbi.canonical.entity_resolution.agents.deterministic import normalize_for_dim

    mapping = {}
    skipped = []
    for raw in raw_names:
        normalized = normalize_for_dim((raw or "").strip())
        if not normalized:
            # 纯标点之类，归一化后什么都不剩 —— 不猜，留 NULL 并报出来。
            skipped.append(raw)
            continue
        mapping[raw] = normalized
    return mapping, skipped


async def backfill_one_factory(pool, factory_id: str, dry_run: bool) -> dict:
    async with pool.acquire() as conn:
        # RLS: app.factory_id 是**事务级** GUC，asyncpg 上不开显式事务从不生效
        # （dim_product / fact_pos_item 的 tenant_isolation 没有 __internal__
        # 逃生门，靠连接池残留会碰运气）。整个回填包在一个事务里。
        async with conn.transaction():
            await conn.execute(
                "SELECT set_config('app.factory_id', $1, true)", factory_id)

            rows = await conn.fetch(
                "SELECT DISTINCT source_item_raw FROM fact_pos_item "
                "WHERE factory_id = $1 AND product_id IS NULL "
                "  AND source_item_raw IS NOT NULL AND btrim(source_item_raw) <> ''",
                factory_id,
            )
            raw_names: List[str] = [r["source_item_raw"] for r in rows]
            if not raw_names:
                return {"factory_id": factory_id, "distinct_dishes": 0, "rows_updated": 0}

            mapping, skipped = plan_backfill(raw_names)
            name_to_pid: Dict[str, int] = {}
            normalized_cache: Dict[str, int] = {}
            for raw, normalized in mapping.items():
                pid = normalized_cache.get(normalized)
                if pid is None:
                    if dry_run:
                        pid = -1
                    else:
                        pid = await conn.fetchval(
                            _PRODUCT_UPSERT_SQL, factory_id, raw.strip(), normalized)
                    normalized_cache[normalized] = pid
                name_to_pid[raw] = pid

            if dry_run:
                logger.info("[dry-run] %s: %d 个原始菜名 → %d 个去重菜品，跳过 %d",
                            factory_id, len(raw_names), len(normalized_cache), len(skipped))
                return {"factory_id": factory_id, "distinct_dishes": len(normalized_cache),
                        "rows_updated": 0, "skipped": len(skipped), "dry_run": True}

            # 一条 UPDATE ... FROM (VALUES ...) 搞定，不要逐菜发一条 UPDATE：
            # MOCK_REST 实测 24 万行明细，逐条往返会把几秒的事拖成几分钟。
            pairs = list(name_to_pid.items())
            updated = await conn.fetchval(
                """
                WITH m(raw, pid) AS (
                    SELECT * FROM UNNEST($2::text[], $3::bigint[])
                ), upd AS (
                    UPDATE fact_pos_item f
                       SET product_id = m.pid
                      FROM m
                     WHERE f.factory_id = $1
                       AND f.product_id IS NULL
                       AND f.source_item_raw = m.raw
                    RETURNING 1
                )
                SELECT count(*) FROM upd
                """,
                factory_id, [p[0] for p in pairs], [p[1] for p in pairs],
            )

    if skipped:
        logger.warning("%s: %d 个菜名归一化后为空，未回填（需人工看）：%s",
                       factory_id, len(skipped), skipped[:5])
    logger.info("%s: %d 个去重菜品，回填 %d 行", factory_id, len(normalized_cache), updated)
    return {"factory_id": factory_id, "distinct_dishes": len(normalized_cache),
            "rows_updated": updated, "skipped": len(skipped)}


async def _main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--factory-ids", required=True,
                        help="逗号分隔的 factory_id")
    parser.add_argument("--dry-run", action="store_true",
                        help="只统计去重后的菜品数，不写库")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    factory_ids = [f.strip() for f in args.factory_ids.split(",") if f.strip()]
    if not factory_ids:
        print("no factory ids", file=sys.stderr)
        sys.exit(2)

    from smartbi.config import get_pg_pool
    pool = await get_pg_pool()

    results = []
    for fid in factory_ids:
        # 失败隔离：一个租户炸掉不该让其余的不做。
        try:
            results.append(await backfill_one_factory(pool, fid, args.dry_run))
        except Exception as exc:  # noqa: BLE001
            logger.exception("%s 回填失败", fid)
            results.append({"factory_id": fid, "error": str(exc)})

    print()
    for r in results:
        print("  " + ", ".join(f"{k}: {v}" for k, v in r.items()))
    if any("error" in r for r in results):
        sys.exit(1)


if __name__ == "__main__":
    os.environ.setdefault("POSTGRES_ENABLED", "true")
    asyncio.run(_main())
